/*
 * Copyright 2014 Samsung System LSI
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "btif_sock_l2cap.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <mutex>

#include "bta/include/bta_jv_api.h"
#include "btif/include/btif_dm.h"
#include "btif/include/btif_sock.h"
#include "btif/include/btif_sock_logging.h"
#include "btif/include/btif_sock_thread.h"
#include "btif/include/btif_sock_util.h"
#include "btif/include/btif_uid.h"
#include "gd/os/rand.h"
#include "include/hardware/bluetooth.h"
#include "internal_include/bt_target.h"
#include "lpp/lpp_offload_interface.h"
#include "main/shim/entry.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/l2cdefs.h"
#include "types/raw_address.h"

using namespace bluetooth;

struct packet {
  struct packet *next, *prev;
  uint32_t len;
  uint8_t* data;
};

typedef struct l2cap_socket {
  struct l2cap_socket* prev;  // link to prev list item
  struct l2cap_socket* next;  // link to next list item
  RawAddress addr;            // other side's address
  char name[256];             // user-friendly name of the service
  uint32_t id;                // just a tag to find this struct
  int app_uid;                // The UID of the app who requested this socket
  int handle;                 // handle from lower layers
  unsigned security;          // security flags
  int channel;                // PSM
  int our_fd;                 // fd from our side
  int app_fd;                 // fd from app's side
  int listen_fd;              // listen socket fd from our side

  unsigned bytes_buffered;
  struct packet* first_packet;  // fist packet to be delivered to app
  struct packet* last_packet;   // last packet to be delivered to app

  unsigned server : 1;            // is a server? (or connecting?)
  unsigned connected : 1;         // is connected?
  unsigned outgoing_congest : 1;  // should we hold?
  unsigned server_psm_sent : 1;   // The server shall only send PSM once.
  bool is_le_coc;                 // is le connection oriented channel?
  uint16_t rx_mtu;
  uint16_t tx_mtu;
  // Cumulative number of bytes transmitted on this socket
  int64_t tx_bytes;
  // Cumulative number of bytes received on this socket
  int64_t rx_bytes;
  uint16_t local_cid;   // The local CID
  uint16_t remote_cid;  // The remote CID
  Uuid conn_uuid;       // The connection uuid
  uint64_t socket_id;   // Socket ID in connected state
  btsock_data_path_t data_path;  // socket data path
  char socket_name[128];         // descriptive socket name
  uint64_t hub_id;               // ID of the hub to which the end point belongs
  uint64_t endpoint_id;          // ID of the hub end point
  bool is_accepting;             // is app accepting on server socket?
} l2cap_socket;

static void btsock_l2cap_server_listen(l2cap_socket* sock);
static uint64_t btif_l2cap_sock_generate_socket_id();
static void on_cl_l2cap_psm_connect_offload_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock);
static void on_srv_l2cap_psm_connect_offload_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock);

static std::mutex state_lock;

static l2cap_socket* socks = NULL;
static uint32_t last_sock_id = 0;
static uid_set_t* uid_set = NULL;
static int pth = -1;

static void btsock_l2cap_cbk(tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t l2cap_socket_id);

/* TODO: Consider to remove this buffer, as we have a buffer in l2cap as well,
 * and we risk a buffer overflow with this implementation if the socket data is not
 * read from JAVA for a while. In such a case we should use flow control to tell the
 * sender to back off.
 * BUT remember we need to avoid blocking the BTA task execution - hence
 * we cannot directly write to the socket.  We should be able to change to store the
 * data pointer here, and just wait confirming the l2cap_ind until we have more space
 * in the buffer. */

/* returns false if none - caller must free "data" memory when done with it */
static char packet_get_head_l(l2cap_socket* sock, uint8_t** data, uint32_t* len) {
  struct packet* p = sock->first_packet;

  if (!p) {
    return false;
  }

  if (data) {
    *data = sock->first_packet->data;
  }
  if (len) {
    *len = sock->first_packet->len;
  }
  sock->first_packet = p->next;
  if (sock->first_packet) {
    sock->first_packet->prev = NULL;
  } else {
    sock->last_packet = NULL;
  }

  if (len) {
    sock->bytes_buffered -= *len;
  }

  osi_free(p);

  return true;
}

static struct packet* packet_alloc(const uint8_t* data, uint32_t len) {
  struct packet* p = (struct packet*)osi_calloc(sizeof(*p));
  uint8_t* buf = (uint8_t*)osi_malloc(len);

  p->data = buf;
  p->len = len;
  memcpy(p->data, data, len);
  return p;
}

/* makes a copy of the data, returns true on success */
static char packet_put_head_l(l2cap_socket* sock, const void* data, uint32_t len) {
  struct packet* p = packet_alloc((const uint8_t*)data, len);

  /*
   * We do not check size limits here since this is used to undo "getting" a
   * packet that the user read incompletely. That is to say the packet was
   * already in the queue. We do check thos elimits in packet_put_tail_l() since
   * that function is used to put new data into the queue.
   */

  if (!p) {
    return false;
  }

  p->prev = NULL;
  p->next = sock->first_packet;
  sock->first_packet = p;
  if (p->next) {
    p->next->prev = p;
  } else {
    sock->last_packet = p;
  }

  sock->bytes_buffered += len;

  return true;
}

/* makes a copy of the data, returns true on success */
static char packet_put_tail_l(l2cap_socket* sock, const void* data, uint32_t len) {
  if (sock->bytes_buffered >= L2CAP_MAX_RX_BUFFER) {
    log::error("Unable to add to buffer due to buffer overflow socket_id:{}", sock->id);
    return false;
  }

  struct packet* p = packet_alloc((const uint8_t*)data, len);
  p->next = NULL;
  p->prev = sock->last_packet;
  sock->last_packet = p;
  if (p->prev) {
    p->prev->next = p;
  } else {
    sock->first_packet = p;
  }

  sock->bytes_buffered += len;

  return true;
}

static char is_inited(void) {
  std::unique_lock<std::mutex> lock(state_lock);
  return pth != -1;
}

/* only call with std::mutex taken */
static l2cap_socket* btsock_l2cap_find_by_id_l(uint32_t id) {
  l2cap_socket* sock = socks;

  while (sock && sock->id != id) {
    sock = sock->next;
  }

  return sock;
}

/* only call with std::mutex taken */
static l2cap_socket* btsock_l2cap_find_by_conn_uuid_l(Uuid& conn_uuid) {
  l2cap_socket* sock = socks;

  while (sock) {
    if (sock->conn_uuid == conn_uuid) {
      return sock;
    }
    sock = sock->next;
  }

  return nullptr;
}

static void btsock_l2cap_free_l(l2cap_socket* sock) {
  uint8_t* buf;
  l2cap_socket* t = socks;

  while (t && t != sock) {
    t = t->next;
  }

  if (!t) { /* prever double-frees */
    return;
  }

  log::info(
          "Disconnected L2CAP connection for device: {}, channel: {}, app_uid: {}, "
          "id: {}, is_le: {}, socket_id: {}",
          sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc, sock->socket_id);
  btif_sock_connection_logger(
          sock->addr, sock->id, sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
          SOCKET_CONNECTION_STATE_DISCONNECTED,
          sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION, sock->app_uid, sock->channel,
          sock->tx_bytes, sock->rx_bytes, sock->name);
  if (com::android::bluetooth::flags::socket_settings_api()) {
    if (sock->data_path == BTSOCK_DATA_PATH_HARDWARE_OFFLOAD && !sock->server &&
        sock->socket_id != 0) {
      bluetooth::shim::GetLppOffloadManager()->SocketClosed(sock->socket_id);
    }
  }
  if (sock->next) {
    sock->next->prev = sock->prev;
  }

  if (sock->prev) {
    sock->prev->next = sock->next;
  } else {
    socks = sock->next;
  }

  shutdown(sock->our_fd, SHUT_RDWR);
  close(sock->our_fd);
  if (sock->app_fd != -1) {
    close(sock->app_fd);
  } else {
    log::info("Application has already closed l2cap socket socket_id:{}", sock->id);
  }

  while (packet_get_head_l(sock, &buf, NULL)) {
    osi_free(buf);
  }

  // lower-level close() should be idempotent... so let's call it and see...
  if (sock->is_le_coc) {
    // Only call if we are non server connections
    if (sock->handle >= 0 && (!sock->server)) {
      BTA_JvL2capClose(sock->handle);
    }
    if ((sock->channel >= 0) && (sock->server)) {
      BTA_JvFreeChannel(sock->channel, tBTA_JV_CONN_TYPE::L2CAP_LE);
      log::info("Stopped L2CAP LE COC server socket_id:{} channel:{}", sock->id, sock->channel);
      BTA_JvL2capStopServer(sock->channel, sock->id);
    }
  } else {
    // Only call if we are non server connections
    if ((sock->handle >= 0) && (!sock->server)) {
      BTA_JvL2capClose(sock->handle);
    }
    if ((sock->channel >= 0) && (sock->server)) {
      BTA_JvFreeChannel(sock->channel, tBTA_JV_CONN_TYPE::L2CAP);
      BTA_JvL2capStopServer(sock->channel, sock->id);
    }
  }

  osi_free(sock);
}

static l2cap_socket* btsock_l2cap_alloc_l(const char* name, const RawAddress* addr, char is_server,
                                          int flags) {
  unsigned security = 0;
  int fds[2];
  l2cap_socket* sock = (l2cap_socket*)osi_calloc(sizeof(*sock));
  int sock_type = SOCK_SEQPACKET;

  if (flags & BTSOCK_FLAG_ENCRYPT) {
    security |= is_server ? BTM_SEC_IN_ENCRYPT : BTM_SEC_OUT_ENCRYPT;
  }
  if (flags & BTSOCK_FLAG_AUTH) {
    security |= is_server ? BTM_SEC_IN_AUTHENTICATE : BTM_SEC_OUT_AUTHENTICATE;
  }
  if (flags & BTSOCK_FLAG_AUTH_MITM) {
    security |= is_server ? BTM_SEC_IN_MITM : BTM_SEC_OUT_MITM;
  }
  if (flags & BTSOCK_FLAG_AUTH_16_DIGIT) {
    security |= BTM_SEC_IN_MIN_16_DIGIT_PIN;
  }

#if TARGET_FLOSS
  // Changed socket type to SOCK_STREAM to address a platform issue on FLOSS.
  // This is a workaround and not the recommended approach.
  // SOCK_SEQPACKET is preferred for L2CAP LE CoC channels because it preserves L2CAP
  // packet boundaries, ensuring message integrity.
  sock_type = SOCK_STREAM;
#endif
  if (socketpair(AF_LOCAL, sock_type, 0, fds)) {
    log::error("socketpair failed:{}", strerror(errno));
    goto fail_sockpair;
  }

  sock->our_fd = fds[0];
  sock->app_fd = fds[1];
  sock->listen_fd = -1;
  sock->security = security;
  sock->server = is_server;
  sock->connected = false;
  sock->handle = 0;
  sock->server_psm_sent = false;
  sock->app_uid = -1;
  sock->conn_uuid = Uuid::kEmpty;
  sock->socket_id = 0;
  sock->data_path = BTSOCK_DATA_PATH_NO_OFFLOAD;
  sock->hub_id = 0;
  sock->endpoint_id = 0;
  sock->is_accepting = false;

  if (name) {
    strncpy(sock->name, name, sizeof(sock->name) - 1);
  }
  if (addr) {
    sock->addr = *addr;
  }

  sock->first_packet = NULL;
  sock->last_packet = NULL;

  sock->tx_mtu = L2CAP_LE_MIN_MTU;

  sock->next = socks;
  sock->prev = NULL;
  if (socks) {
    socks->prev = sock;
  }
  sock->id = last_sock_id + 1;
  sock->tx_bytes = 0;
  sock->rx_bytes = 0;
  socks = sock;
  /* paranoia cap on: verify no ID duplicates due to overflow and fix as needed
   */
  while (1) {
    l2cap_socket* t;
    t = socks->next;
    while (t && t->id != sock->id) {
      t = t->next;
    }
    if (!t && sock->id) { /* non-zero handle is unique -> we're done */
      break;
    }
    /* if we're here, we found a duplicate */
    if (!++sock->id) { /* no zero IDs allowed */
      sock->id++;
    }
  }
  last_sock_id = sock->id;
  log::info("Allocated l2cap socket structure socket_id:{}", sock->id);
  return sock;

fail_sockpair:
  osi_free(sock);
  return NULL;
}

bt_status_t btsock_l2cap_init(int handle, uid_set_t* set) {
  std::unique_lock<std::mutex> lock(state_lock);
  pth = handle;
  socks = NULL;
  uid_set = set;
  return BT_STATUS_SUCCESS;
}

bt_status_t btsock_l2cap_cleanup() {
  std::unique_lock<std::mutex> lock(state_lock);
  pth = -1;
  while (socks) {
    btsock_l2cap_free_l(socks);
  }
  return BT_STATUS_SUCCESS;
}

static inline bool send_app_psm_or_chan_l(l2cap_socket* sock) {
  log::info("Sending l2cap socket socket_id:{} channel:{}", sock->id, sock->channel);
  return sock_send_all(sock->our_fd, (const uint8_t*)&sock->channel, sizeof(sock->channel)) ==
         sizeof(sock->channel);
}

static bool send_app_err_code(l2cap_socket* sock, tBTA_JV_L2CAP_REASON code) {
  log::info("Sending l2cap failure reason socket_id:{} reason code:{}", sock->id, code);
  int err_channel = 0;
  if (sock_send_all(sock->our_fd, (const uint8_t*)&err_channel, sizeof(err_channel)) !=
      sizeof(err_channel)) {
    return false;
  }
  return sock_send_all(sock->our_fd, (const uint8_t*)&code, sizeof(code)) == sizeof(code);
}

static uint64_t uuid_lsb(const Uuid& uuid) {
  uint64_t lsb = 0;

  auto uu = uuid.To128BitBE();
  for (int i = 8; i <= 15; i++) {
    lsb <<= 8;
    lsb |= uu[i];
  }

  return lsb;
}

static uint64_t uuid_msb(const Uuid& uuid) {
  uint64_t msb = 0;

  auto uu = uuid.To128BitBE();
  for (int i = 0; i <= 7; i++) {
    msb <<= 8;
    msb |= uu[i];
  }

  return msb;
}

static bool send_app_connect_signal(int fd, const RawAddress* addr, int channel, int status,
                                    int send_fd, uint16_t rx_mtu, uint16_t tx_mtu,
                                    const Uuid& conn_uuid, uint64_t socket_id) {
  sock_connect_signal_t cs;
  cs.size = sizeof(cs);
  cs.bd_addr = *addr;
  cs.channel = channel;
  cs.status = status;
  cs.max_rx_packet_size = rx_mtu;
  cs.max_tx_packet_size = tx_mtu;
  cs.conn_uuid_lsb = uuid_lsb(conn_uuid);
  cs.conn_uuid_msb = uuid_msb(conn_uuid);
  cs.socket_id = socket_id;
  if (send_fd != -1) {
    if (sock_send_fd(fd, (const uint8_t*)&cs, sizeof(cs), send_fd) == sizeof(cs)) {
      return true;
    }
  } else if (sock_send_all(fd, (const uint8_t*)&cs, sizeof(cs)) == sizeof(cs)) {
    return true;
  }

  log::error("Unable to send data to socket fd:{} send_fd:{}", fd, send_fd);
  return false;
}

static void on_srv_l2cap_listen_started(tBTA_JV_L2CAP_START* p_start, uint32_t id) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  if (p_start->status != tBTA_JV_STATUS::SUCCESS) {
    log::error("Unable to start l2cap server socket_id:{}", sock->id);
    btsock_l2cap_free_l(sock);
    return;
  }

  sock->handle = p_start->handle;

  log::info(
          "Listening for L2CAP connection for device: {}, channel: {}, app_uid: "
          "{}, id: {}, is_le: {}",
          sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc);
  btif_sock_connection_logger(sock->addr, sock->id,
                              sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_LISTENING,
                              sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              sock->app_uid, sock->channel, 0, 0, sock->name);

  if (!sock->server_psm_sent) {
    if (!send_app_psm_or_chan_l(sock)) {
      // closed
      log::info("Unable to send socket to application socket_id:{}", sock->id);
      btsock_l2cap_free_l(sock);
    } else {
      sock->server_psm_sent = true;
    }
  }
}

static void on_cl_l2cap_init(tBTA_JV_L2CAP_CL_INIT* p_init, uint32_t id) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  if (p_init->status != tBTA_JV_STATUS::SUCCESS) {
    log::error("Initialization status failed socket_id:{}", id);
    btsock_l2cap_free_l(sock);
    return;
  }

  sock->handle = p_init->handle;
}

/**
 * Here we allocate a new sock instance to mimic the BluetoothSocket. The socket
 * will be a clone of the sock representing the BluetoothServerSocket.
 * */
static void on_srv_l2cap_psm_connect_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock) {
  // state_lock taken by caller
  l2cap_socket* accept_rs = btsock_l2cap_alloc_l(sock->name, &p_open->rem_bda, false, 0);
  accept_rs->connected = true;
  accept_rs->security = sock->security;
  accept_rs->channel = sock->channel;
  accept_rs->handle = sock->handle;
  accept_rs->app_uid = sock->app_uid;
  sock->handle = -1; /* We should no longer associate this handle with the server socket */
  accept_rs->is_le_coc = sock->is_le_coc;
  accept_rs->tx_mtu = sock->tx_mtu = p_open->tx_mtu;
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349374
    accept_rs->rx_mtu = sock->rx_mtu;
  }
  accept_rs->local_cid = p_open->local_cid;
  accept_rs->remote_cid = p_open->remote_cid;
  // TODO(b/342012881) Remove connection uuid when offload socket API is landed.
  Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
  accept_rs->conn_uuid = uuid;
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349374
    accept_rs->socket_id = btif_l2cap_sock_generate_socket_id();
    accept_rs->data_path = sock->data_path;
    strncpy(accept_rs->socket_name, sock->socket_name, sizeof(accept_rs->socket_name) - 1);
    accept_rs->socket_name[sizeof(accept_rs->socket_name) - 1] = '\0';
    accept_rs->hub_id = sock->hub_id;
    accept_rs->endpoint_id = sock->endpoint_id;
  }

  /* Swap IDs to hand over the GAP connection to the accepted socket, and start
     a new server on the newly create socket ID. */
  uint32_t new_listen_id = accept_rs->id;
  accept_rs->id = sock->id;
  sock->id = new_listen_id;

  log::info(
          "Connected to L2CAP connection for device: {}, channel: {}, app_uid: {}, "
          "id: {}, is_le: {}, socket_id: {}, rx_mtu: {}",
          accept_rs->addr, accept_rs->channel, accept_rs->app_uid, accept_rs->id,
          accept_rs->is_le_coc, accept_rs->socket_id, accept_rs->rx_mtu);
  btif_sock_connection_logger(accept_rs->addr, accept_rs->id,
                              accept_rs->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_CONNECTED,
                              accept_rs->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              accept_rs->app_uid, accept_rs->channel, 0, 0, accept_rs->name);

  // start monitor the socket
  btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_EXCEPTION, sock->id);
  btsock_thread_add_fd(pth, accept_rs->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, accept_rs->id);
  send_app_connect_signal(sock->our_fd, &accept_rs->addr, sock->channel, 0, accept_rs->app_fd,
                          sock->rx_mtu, p_open->tx_mtu, accept_rs->conn_uuid, accept_rs->socket_id);
  accept_rs->app_fd = -1;  // The fd is closed after sent to app in send_app_connect_signal()
  // But for some reason we still leak a FD - either the server socket
  // one or the accept socket one.
  btsock_l2cap_server_listen(sock);
  // start monitoring the socketpair to get call back when app is accepting on server socket
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349375
    btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
  }
}

static void on_cl_l2cap_psm_connect_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock) {
  sock->addr = p_open->rem_bda;
  sock->tx_mtu = p_open->tx_mtu;
  sock->local_cid = p_open->local_cid;
  sock->remote_cid = p_open->remote_cid;
  // TODO(b/342012881) Remove connection uuid when offload socket API is landed.
  Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
  sock->conn_uuid = uuid;
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349374
    sock->socket_id = btif_l2cap_sock_generate_socket_id();
  }

  if (!send_app_psm_or_chan_l(sock)) {
    log::error("Unable to send l2cap socket to application socket_id:{}", sock->id);
    return;
  }

  if (!send_app_connect_signal(sock->our_fd, &sock->addr, sock->channel, 0, -1, sock->rx_mtu,
                               p_open->tx_mtu, sock->conn_uuid, sock->socket_id)) {
    log::error("Unable to connect l2cap socket to application socket_id:{}", sock->id);
    return;
  }

  log::info(
          "Connected to L2CAP connection for device: {}, channel: {}, app_uid: {}, "
          "id: {}, is_le: {}, socket_id: {}, rx_mtu: {}",
          sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc, sock->socket_id,
          sock->rx_mtu);
  btif_sock_connection_logger(sock->addr, sock->id,
                              sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_CONNECTED,
                              sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              sock->app_uid, sock->channel, 0, 0, sock->name);

  // start monitoring the socketpair to get call back when app writing data
  btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
  log::info("Connected l2cap socket socket_id:{}", sock->id);
  sock->connected = true;
}

static void on_l2cap_connect(tBTA_JV* p_data, uint32_t id) {
  tBTA_JV_L2CAP_OPEN* psm_open = &p_data->l2c_open;
  tBTA_JV_L2CAP_LE_OPEN* le_open = &p_data->l2c_le_open;

  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  sock->tx_mtu = le_open->tx_mtu;
  if (psm_open->status == tBTA_JV_STATUS::SUCCESS) {
    if (!com::android::bluetooth::flags::socket_settings_api() ||  // Added with aosp/3349378
        sock->data_path == BTSOCK_DATA_PATH_NO_OFFLOAD) {
      if (!sock->server) {
        on_cl_l2cap_psm_connect_l(psm_open, sock);
      } else {
        on_srv_l2cap_psm_connect_l(psm_open, sock);
      }
    } else {
      if (!sock->server) {
        on_cl_l2cap_psm_connect_offload_l(psm_open, sock);
      } else {
        on_srv_l2cap_psm_connect_offload_l(psm_open, sock);
      }
    }
  } else {
    log::error("Unable to open socket after receiving connection socket_id:{}", sock->id);
    btsock_l2cap_free_l(sock);
  }
}

static void on_l2cap_close(tBTA_JV_L2CAP_CLOSE* p_close, uint32_t id) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::info("Unable to find probably already closed l2cap socket with socket_id:{}", id);
    return;
  }

  log::info(
          "Disconnecting from L2CAP connection for device: {}, channel: {}, "
          "app_uid: {}, id: {}, is_le: {}",
          sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc);
  btif_sock_connection_logger(sock->addr, sock->id,
                              sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_DISCONNECTING,
                              sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              sock->app_uid, sock->channel, 0, 0, sock->name);
  if (com::android::bluetooth::flags::donot_push_error_code_to_app_when_connected()) {
    if (!sock->connected) {
      if (!send_app_err_code(sock, p_close->reason)) {
        log::error("Unable to send l2cap socket to application socket_id:{}", sock->id);
      }
    } else {
      log::info("Don't push error for already connected socket:{}", sock->id);
    }
  } else {
    if (!send_app_err_code(sock, p_close->reason)) {
      log::error("Unable to send l2cap socket to application socket_id:{}", sock->id);
    }
  }
  // TODO: This does not seem to be called...
  // I'm not sure if this will be called for non-server sockets?
  if (sock->server) {
    BTA_JvFreeChannel(sock->channel, tBTA_JV_CONN_TYPE::L2CAP);
  }
  btsock_l2cap_free_l(sock);
}

static void on_l2cap_outgoing_congest(tBTA_JV_L2CAP_CONG* p, uint32_t id) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  sock->outgoing_congest = p->cong ? 1 : 0;

  if (!sock->outgoing_congest) {
    log::verbose("Monitoring l2cap socket for outgoing data socket_id:{}", sock->id);
    btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
  }
}

static void on_l2cap_write_done(uint16_t len, uint32_t id) {
  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  int app_uid = sock->app_uid;
  if (!sock->outgoing_congest) {
    btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
  } else {
    log::info("Socket congestion on socket_id:{}", sock->id);
  }

  sock->tx_bytes += len;
  uid_set_add_tx(uid_set, app_uid, len);
}

static void on_l2cap_data_ind(tBTA_JV* /* evt */, uint32_t id) {
  l2cap_socket* sock;

  int app_uid = -1;
  uint32_t bytes_read = 0;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  app_uid = sock->app_uid;

  uint32_t count;

  if (BTA_JvL2capReady(sock->handle, &count) == tBTA_JV_STATUS::SUCCESS) {
    std::vector<uint8_t> buffer(count);
    if (BTA_JvL2capRead(sock->handle, sock->id, buffer.data(), count) == tBTA_JV_STATUS::SUCCESS) {
      if (packet_put_tail_l(sock, buffer.data(), count)) {
        bytes_read = count;
        btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_WR, sock->id);
      } else {  // connection must be dropped
        log::warn("Closing socket as unable to push data to socket socket_id:{}", sock->id);
        BTA_JvL2capClose(sock->handle);
        btsock_l2cap_free_l(sock);
        return;
      }
    }
  }

  sock->rx_bytes += bytes_read;
  uid_set_add_rx(uid_set, app_uid, bytes_read);
}

static void btsock_l2cap_cbk(tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t l2cap_socket_id) {
  switch (event) {
    case BTA_JV_L2CAP_START_EVT:
      on_srv_l2cap_listen_started(&p_data->l2c_start, l2cap_socket_id);
      break;

    case BTA_JV_L2CAP_CL_INIT_EVT:
      on_cl_l2cap_init(&p_data->l2c_cl_init, l2cap_socket_id);
      break;

    case BTA_JV_L2CAP_OPEN_EVT:
      on_l2cap_connect(p_data, l2cap_socket_id);
      BTA_JvSetPmProfile(p_data->l2c_open.handle, BTA_JV_PM_ID_1, BTA_JV_CONN_OPEN);
      break;

    case BTA_JV_L2CAP_CLOSE_EVT:
      on_l2cap_close(&p_data->l2c_close, l2cap_socket_id);
      break;

    case BTA_JV_L2CAP_DATA_IND_EVT:
      on_l2cap_data_ind(p_data, l2cap_socket_id);
      break;

    case BTA_JV_L2CAP_READ_EVT:
      break;

    case BTA_JV_L2CAP_WRITE_EVT:
      on_l2cap_write_done(p_data->l2c_write.len, l2cap_socket_id);
      break;

    case BTA_JV_L2CAP_CONG_EVT:
      on_l2cap_outgoing_congest(&p_data->l2c_cong, l2cap_socket_id);
      break;

    default:
      log::error("Unhandled event:{} l2cap_socket_id:{}", bta_jv_event_text(event),
                 l2cap_socket_id);
      break;
  }
}

const tL2CAP_ERTM_INFO obex_l2c_etm_opt = {L2CAP_FCR_ERTM_MODE,
                                           /* Mandatory for OBEX over l2cap */};

/**
 * When using a dynamic PSM, a PSM allocation is requested from
 * btsock_l2cap_listen_or_connect().
 * The PSM allocation event is refeived in the JV-callback - currently located
 * in RFC-code -
 * and this function is called with the newly allocated PSM.
 */
void on_l2cap_psm_assigned(int id, int psm) {
  /* Setup ETM settings:
   *  mtu will be set below */
  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_find_by_id_l(id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", id);
    return;
  }

  sock->channel = psm;

  btsock_l2cap_server_listen(sock);
}

static void btsock_l2cap_server_listen(l2cap_socket* sock) {
  tBTA_JV_CONN_TYPE connection_type =
          sock->is_le_coc ? tBTA_JV_CONN_TYPE::L2CAP_LE : tBTA_JV_CONN_TYPE::L2CAP;

  /* If we have a channel specified in the request, just start the server,
   * else we request a PSM and start the server after we receive a PSM. */
  if (sock->channel <= 0) {
    BTA_JvGetChannelId(connection_type, sock->id, 0);
    return;
  }

  /* Setup ETM settings: mtu will be set below */
  std::unique_ptr<tL2CAP_CFG_INFO> cfg = std::make_unique<tL2CAP_CFG_INFO>(
          tL2CAP_CFG_INFO{.fcr_present = true, .fcr = kDefaultErtmOptions});
  /* For hardware offload data path, host stack sets the initial credits to 0. The offload stack
   * should send initial credits to peer device through L2CAP signaling command when the data path
   * is switched successfully. */
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349376
    if (sock->data_path == BTSOCK_DATA_PATH_HARDWARE_OFFLOAD) {
      cfg->init_credit_present = true;
      cfg->init_credit = 0;
    }
  }

  std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info;
  if (!sock->is_le_coc) {
    ertm_info.reset(new tL2CAP_ERTM_INFO(obex_l2c_etm_opt));
  }

  BTA_JvL2capStartServer(connection_type, sock->security, std::move(ertm_info), sock->channel,
                         sock->rx_mtu, std::move(cfg), btsock_l2cap_cbk, sock->id);
}

/*
 * Determine the local MTU for the offloaded L2CAP connection.
 *
 * The local MTU is selected as the minimum of:
 *   - The socket hal's offload capabilities (socket_cap.leCocCapabilities.mtu)
 *   - The application's requested maximum RX packet size (app_max_rx_packet_size)
 *
 * However, the MTU must be at least the minimum required by the L2CAP LE
 * specification (L2CAP_SDU_LENGTH_LE_MIN).
 */

static bool btsock_l2cap_get_offload_mtu(uint16_t* rx_mtu, uint16_t app_max_rx_packet_size) {
  hal::SocketCapabilities socket_cap =
          bluetooth::shim::GetLppOffloadManager()->GetSocketCapabilities();
  if (!socket_cap.le_coc_capabilities.number_of_supported_sockets) {
    return false;
  }
  /* Socket HAL client has already verified that the MTU is in a valid range. */
  uint16_t mtu = static_cast<uint16_t>(socket_cap.le_coc_capabilities.mtu);
  mtu = std::min(mtu, app_max_rx_packet_size);
  if (mtu < L2CAP_SDU_LENGTH_LE_MIN) {
    mtu = L2CAP_SDU_LENGTH_LE_MIN;
  }
  *rx_mtu = mtu;
  return true;
}

static bt_status_t btsock_l2cap_listen_or_connect(const char* name, const RawAddress* addr,
                                                  int channel, int* sock_fd, int flags, char listen,
                                                  int app_uid, btsock_data_path_t data_path,
                                                  const char* socket_name, uint64_t hub_id,
                                                  uint64_t endpoint_id, int max_rx_packet_size) {
  if (!is_inited()) {
    return BT_STATUS_NOT_READY;
  }

  bool is_le_coc = (flags & BTSOCK_FLAG_LE_COC) != 0;

  if (is_le_coc) {
    if (listen) {
      if (flags & BTSOCK_FLAG_NO_SDP) {
        /* For LE COC server; set channel to zero so that it will be assigned */
        channel = 0;
      } else if (channel <= 0) {
        log::error("type BTSOCK_L2CAP_LE: invalid channel={}", channel);
        return BT_STATUS_SOCKET_ERROR;
      }
    } else {
      // Ensure device is in inquiry database during L2CAP CoC connection
      btif_check_device_in_inquiry_db(*addr);
    }
  }

  if (!sock_fd) {
    log::info("Invalid socket descriptor");
    return BT_STATUS_PARM_INVALID;
  }

  // TODO: This is kind of bad to lock here, but it is needed for the current
  // design.
  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_alloc_l(name, addr, listen, flags);
  if (!sock) {
    return BT_STATUS_NOMEM;
  }

  sock->channel = channel;
  sock->app_uid = app_uid;
  sock->is_le_coc = is_le_coc;
  if (com::android::bluetooth::flags::socket_settings_api() &&  // Added with aosp/3349377
      data_path == BTSOCK_DATA_PATH_HARDWARE_OFFLOAD) {
    if (!btsock_l2cap_get_offload_mtu(&sock->rx_mtu, static_cast<uint16_t>(max_rx_packet_size))) {
      return BT_STATUS_UNSUPPORTED;
    }
  } else {
    sock->rx_mtu = is_le_coc ? L2CAP_SDU_LENGTH_LE_MAX : L2CAP_SDU_LENGTH_MAX;
  }
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349374
    sock->data_path = data_path;
    if (socket_name) {
      strncpy(sock->socket_name, socket_name, sizeof(sock->socket_name) - 1);
      sock->socket_name[sizeof(sock->socket_name) - 1] = '\0';
    }
    sock->hub_id = hub_id;
    sock->endpoint_id = endpoint_id;
  }

  /* "role" is never initialized in rfcomm code */
  if (listen) {
    btsock_l2cap_server_listen(sock);
    // start monitoring the socketpair to get call back when app is accepting on server socket
    if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349375
      btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
    }
  } else {
    tBTA_JV_CONN_TYPE connection_type =
            sock->is_le_coc ? tBTA_JV_CONN_TYPE::L2CAP_LE : tBTA_JV_CONN_TYPE::L2CAP;

    /* Setup ETM settings: mtu will be set below */
    std::unique_ptr<tL2CAP_CFG_INFO> cfg = std::make_unique<tL2CAP_CFG_INFO>(
            tL2CAP_CFG_INFO{.fcr_present = true, .fcr = kDefaultErtmOptions});
    /* For hardware offload data path, host stack sets the initial credits to 0. The offload stack
     * should send initial credits to peer device through L2CAP signaling command when the data path
     * is switched successfully. */
    if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349376
      if (sock->data_path == BTSOCK_DATA_PATH_HARDWARE_OFFLOAD) {
        cfg->init_credit_present = true;
        cfg->init_credit = 0;
      }
    }

    std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info;
    if (!sock->is_le_coc) {
      ertm_info.reset(new tL2CAP_ERTM_INFO(obex_l2c_etm_opt));
    }

    BTA_JvL2capConnect(connection_type, sock->security, std::move(ertm_info), channel, sock->rx_mtu,
                       std::move(cfg), sock->addr, btsock_l2cap_cbk, sock->id);
  }

  *sock_fd = sock->app_fd;
  /* We pass the FD to JAVA, but since it runs in another process, we need to
   * also close it in native, either straight away, as done when accepting an
   * incoming connection, or when doing cleanup after this socket */
  sock->app_fd = -1;
  /*This leaks the file descriptor. The FD should be closed in JAVA but it
   * apparently do not work */
  btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_EXCEPTION, sock->id);

  return BT_STATUS_SUCCESS;
}

bt_status_t btsock_l2cap_listen(const char* name, int channel, int* sock_fd, int flags, int app_uid,
                                btsock_data_path_t data_path, const char* socket_name,
                                uint64_t hub_id, uint64_t endpoint_id, int max_rx_packet_size) {
  return btsock_l2cap_listen_or_connect(name, NULL, channel, sock_fd, flags, 1, app_uid, data_path,
                                        socket_name, hub_id, endpoint_id, max_rx_packet_size);
}

bt_status_t btsock_l2cap_connect(const RawAddress* bd_addr, int channel, int* sock_fd, int flags,
                                 int app_uid, btsock_data_path_t data_path, const char* socket_name,
                                 uint64_t hub_id, uint64_t endpoint_id, int max_rx_packet_size) {
  return btsock_l2cap_listen_or_connect(NULL, bd_addr, channel, sock_fd, flags, 0, app_uid,
                                        data_path, socket_name, hub_id, endpoint_id,
                                        max_rx_packet_size);
}

/* return true if we have more to send and should wait for user readiness, false
 * else
 * (for example: unrecoverable error or no data)
 */
static bool flush_incoming_que_on_wr_signal_l(l2cap_socket* sock) {
  uint8_t* buf;
  uint32_t len;

  while (packet_get_head_l(sock, &buf, &len)) {
    ssize_t sent;
    OSI_NO_INTR(sent = send(sock->our_fd, buf, len, MSG_DONTWAIT));
    int saved_errno = errno;

    if (sent == (signed)len) {
      osi_free(buf);
    } else if (sent >= 0) {
      packet_put_head_l(sock, buf + sent, len - sent);
      osi_free(buf);
      if (!sent) { /* special case if other end not keeping up */
        return true;
      }
    } else {
      packet_put_head_l(sock, buf, len);
      osi_free(buf);
      return saved_errno == EWOULDBLOCK || saved_errno == EAGAIN;
    }
  }

  return false;
}

inline BT_HDR* malloc_l2cap_buf(uint16_t len) {
  // We need FCS only for L2CAP_FCR_ERTM_MODE, but it's just 2 bytes so it's ok
  BT_HDR* msg = (BT_HDR*)osi_malloc(BT_HDR_SIZE + L2CAP_MIN_OFFSET + len + L2CAP_FCS_LENGTH);
  msg->offset = L2CAP_MIN_OFFSET;
  msg->len = len;
  return msg;
}

inline uint8_t* get_l2cap_sdu_start_ptr(BT_HDR* msg) {
  return (uint8_t*)(msg) + BT_HDR_SIZE + msg->offset;
}

// state_lock taken by caller
static bool btsock_l2cap_read_signaled_on_connected_socket(int fd, int flags, uint32_t user_id,
                                                           l2cap_socket* sock) {
  if (!sock->connected) {
    return false;
  }
  int size = 0;
  bool ioctl_success = ioctl(sock->our_fd, FIONREAD, &size) == 0;
  if (!(flags & SOCK_THREAD_FD_EXCEPTION) || (ioctl_success && size)) {
    /* FIONREAD return number of bytes that are immediately available for
      reading, might be bigger than awaiting packet.

      BluetoothSocket.write(...) guarantees that any packet send to this
      socket is broken into pieces no bigger than MTU bytes (as requested
      by BT spec). */
    size = std::min(size, (int)sock->tx_mtu);

    BT_HDR* buffer = malloc_l2cap_buf(size);
    /* The socket is created with SOCK_SEQPACKET, hence we read one message
     * at the time. */
    ssize_t count;
    OSI_NO_INTR(count = recv(fd, get_l2cap_sdu_start_ptr(buffer), size,
                             MSG_NOSIGNAL | MSG_DONTWAIT | MSG_TRUNC));
    if (count > sock->tx_mtu) {
      /* This can't happen thanks to check in BluetoothSocket.java but leave
       * this in case this socket is ever used anywhere else*/
      log::error("recv more than MTU. Data will be lost: {}", count);
      count = sock->tx_mtu;
    }

    /* When multiple packets smaller than MTU are flushed to the socket, the
      size of the single packet read could be smaller than the ioctl
      reported total size of awaiting packets. Hence, we adjust the buffer
      length. */
    buffer->len = count;

    // will take care of freeing buffer
    BTA_JvL2capWrite(sock->handle, PTR_TO_UINT(buffer), buffer, user_id);
  }
  return true;
}

// state_lock taken by caller
static bool btsock_l2cap_read_signaled_on_listen_socket(int fd, int /* flags */,
                                                        uint32_t /* user_id */,
                                                        l2cap_socket* sock) {
  int size = 0;
  bool ioctl_success = ioctl(sock->our_fd, FIONREAD, &size) == 0;
  if (ioctl_success && size) {
    sock_accept_signal_t accept_signal = {};
    ssize_t count;
    OSI_NO_INTR(count = recv(fd, reinterpret_cast<uint8_t*>(&accept_signal), sizeof(accept_signal),
                             MSG_NOSIGNAL | MSG_DONTWAIT | MSG_TRUNC));
    if (count != sizeof(accept_signal) || count != accept_signal.size) {
      log::error("Unexpected count: {}, sizeof(accept_signal): {}, accept_signal.size: {}", count,
                 sizeof(accept_signal), accept_signal.size);
      return false;
    }
    sock->is_accepting = accept_signal.is_accepting;
    log::info("Server socket: {}, is_accepting: {}", sock->id, sock->is_accepting);
  }
  return true;
}

static void btsock_l2cap_signaled_flagged(int fd, int flags, uint32_t user_id) {
  char drop_it = false;

  /* We use MSG_DONTWAIT when sending data to JAVA, hence it can be accepted to
   * hold the lock. */
  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_find_by_id_l(user_id);
  if (!sock) {
    return;
  }
  if (flags & SOCK_THREAD_FD_RD) {
    if (!sock->server) {
      // app sending data on connection socket
      if (!btsock_l2cap_read_signaled_on_connected_socket(fd, flags, user_id, sock)) {
        drop_it = true;
      }
    } else {
      // app sending signal on listen socket
      if (!btsock_l2cap_read_signaled_on_listen_socket(fd, flags, user_id, sock)) {
        drop_it = true;
      }
    }
  }
  if (flags & SOCK_THREAD_FD_WR) {
    // app is ready to receive more data, tell stack to enable the data flow
    if (flush_incoming_que_on_wr_signal_l(sock) && sock->connected) {
      btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_WR, sock->id);
    }
  }
  if (drop_it || (flags & SOCK_THREAD_FD_EXCEPTION)) {
    int size = 0;
    if (drop_it || ioctl(sock->our_fd, FIONREAD, &size) != 0 || size == 0) {
      btsock_l2cap_free_l(sock);
    }
  }
}

void btsock_l2cap_signaled(int fd, int flags, uint32_t user_id) {
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3349375
    btsock_l2cap_signaled_flagged(fd, flags, user_id);
    return;
  }
  char drop_it = false;

  /* We use MSG_DONTWAIT when sending data to JAVA, hence it can be accepted to
   * hold the lock. */
  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = btsock_l2cap_find_by_id_l(user_id);
  if (!sock) {
    return;
  }

  if ((flags & SOCK_THREAD_FD_RD) && !sock->server) {
    // app sending data
    if (sock->connected) {
      int size = 0;
      bool ioctl_success = ioctl(sock->our_fd, FIONREAD, &size) == 0;
      if (!(flags & SOCK_THREAD_FD_EXCEPTION) || (ioctl_success && size)) {
        /* FIONREAD return number of bytes that are immediately available for
           reading, might be bigger than awaiting packet.

           BluetoothSocket.write(...) guarantees that any packet send to this
           socket is broken into pieces no bigger than MTU bytes (as requested
           by BT spec). */
        size = std::min(size, (int)sock->tx_mtu);

        BT_HDR* buffer = malloc_l2cap_buf(size);
        /* The socket is created with SOCK_SEQPACKET, hence we read one message
         * at the time. */
        ssize_t count;
        OSI_NO_INTR(count = recv(fd, get_l2cap_sdu_start_ptr(buffer), size,
                                 MSG_NOSIGNAL | MSG_DONTWAIT | MSG_TRUNC));
        if (count > sock->tx_mtu) {
          /* This can't happen thanks to check in BluetoothSocket.java but leave
           * this in case this socket is ever used anywhere else*/
          log::error("recv more than MTU. Data will be lost: {}", count);
          count = sock->tx_mtu;
        }

        /* When multiple packets smaller than MTU are flushed to the socket, the
           size of the single packet read could be smaller than the ioctl
           reported total size of awaiting packets. Hence, we adjust the buffer
           length. */
        buffer->len = count;

        // will take care of freeing buffer
        BTA_JvL2capWrite(sock->handle, PTR_TO_UINT(buffer), buffer, user_id);
      }
    } else {
      drop_it = true;
    }
  }
  if (flags & SOCK_THREAD_FD_WR) {
    // app is ready to receive more data, tell stack to enable the data flow
    if (flush_incoming_que_on_wr_signal_l(sock) && sock->connected) {
      btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_WR, sock->id);
    }
  }
  if (drop_it || (flags & SOCK_THREAD_FD_EXCEPTION)) {
    int size = 0;
    if (drop_it || ioctl(sock->our_fd, FIONREAD, &size) != 0 || size == 0) {
      btsock_l2cap_free_l(sock);
    }
  }
}

bt_status_t btsock_l2cap_disconnect(const RawAddress* bd_addr) {
  if (!bd_addr) {
    return BT_STATUS_PARM_INVALID;
  }
  if (!is_inited()) {
    return BT_STATUS_NOT_READY;
  }

  std::unique_lock<std::mutex> lock(state_lock);
  l2cap_socket* sock = socks;

  while (sock) {
    l2cap_socket* next = sock->next;
    if (sock->addr == *bd_addr) {
      btsock_l2cap_free_l(sock);
    }
    sock = next;
  }

  return BT_STATUS_SUCCESS;
}

bt_status_t btsock_l2cap_get_l2cap_local_cid(Uuid& conn_uuid, uint16_t* cid) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_conn_uuid_l(conn_uuid);
  if (!sock) {
    log::error("Unable to find l2cap socket with conn_uuid:{}", conn_uuid.ToString());
    return BT_STATUS_SOCKET_ERROR;
  }
  *cid = sock->local_cid;
  return BT_STATUS_SUCCESS;
}

bt_status_t btsock_l2cap_get_l2cap_remote_cid(Uuid& conn_uuid, uint16_t* cid) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_conn_uuid_l(conn_uuid);
  if (!sock) {
    log::error("Unable to find l2cap socket with conn_uuid:{}", conn_uuid.ToString());
    return BT_STATUS_SOCKET_ERROR;
  }
  *cid = sock->remote_cid;
  return BT_STATUS_SUCCESS;
}

// TODO(b/380189525): Replace the randomized socket ID with static counter when we don't have
// security concerns about using static counter.
static uint64_t btif_l2cap_sock_generate_socket_id() {
  uint64_t socket_id;
  do {
    socket_id = bluetooth::os::GenerateRandomUint64();
  } while (!socket_id);
  return socket_id;
}

/* only call with state_lock taken */
static l2cap_socket* btsock_l2cap_find_by_socket_id_l(uint64_t socket_id) {
  l2cap_socket* sock = socks;

  while (sock) {
    if (sock->socket_id == socket_id) {
      return sock;
    }
    sock = sock->next;
  }

  return nullptr;
}

bool btsock_l2cap_in_use(uint64_t socket_id) {
  std::unique_lock<std::mutex> lock(state_lock);
  return btsock_l2cap_find_by_socket_id_l(socket_id) != nullptr;
}

void on_btsocket_l2cap_opened_complete(uint64_t socket_id, bool success) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_socket_id_l(socket_id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", socket_id);
    return;
  }
  if (!success) {
    log::error("L2CAP opened complete failed with socket_id:{}", socket_id);
    btsock_l2cap_free_l(sock);
    return;
  }
  // If the socket was accepted from listen socket, use listen_fd.
  if (sock->listen_fd != -1) {
    send_app_connect_signal(sock->listen_fd, &sock->addr, sock->channel, 0, sock->app_fd,
                            sock->rx_mtu, sock->tx_mtu, sock->conn_uuid, sock->socket_id);
    // The fd is closed after sent to app in send_app_connect_signal()
    sock->app_fd = -1;
  } else {
    if (!send_app_psm_or_chan_l(sock)) {
      log::error("Unable to send l2cap socket to application socket_id:{}", sock->id);
      return;
    }
    if (!send_app_connect_signal(sock->our_fd, &sock->addr, sock->channel, 0, -1, sock->rx_mtu,
                                 sock->tx_mtu, sock->conn_uuid, sock->socket_id)) {
      log::error("Unable to connect l2cap socket to application socket_id:{}", sock->id);
      return;
    }

    log::info(
            "Connected to L2CAP connection for device: {}, channel: {}, app_uid: {}, id: {}, "
            "is_le: {}, socket_id: {}, rx_mtu: {}",
            sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc, sock->socket_id,
            sock->rx_mtu);
    btif_sock_connection_logger(sock->addr, sock->id,
                                sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                                SOCKET_CONNECTION_STATE_CONNECTED,
                                sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                                sock->app_uid, sock->channel, 0, 0, sock->name);

    log::info("Connected l2cap socket socket_id:{}", sock->id);
    sock->connected = true;
  }
}

void on_btsocket_l2cap_close(uint64_t socket_id) {
  l2cap_socket* sock;

  std::unique_lock<std::mutex> lock(state_lock);
  sock = btsock_l2cap_find_by_socket_id_l(socket_id);
  if (!sock) {
    log::error("Unable to find l2cap socket with socket_id:{}", socket_id);
    return;
  }
  log::info("L2CAP close request for socket_id:{}", socket_id);
  btsock_l2cap_free_l(sock);
}

static void on_cl_l2cap_psm_connect_offload_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock) {
  sock->addr = p_open->rem_bda;
  sock->tx_mtu = p_open->tx_mtu;
  sock->local_cid = p_open->local_cid;
  sock->remote_cid = p_open->remote_cid;
  // TODO(b/342012881) Remove connection uuid when offload socket API is landed.
  Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
  sock->conn_uuid = uuid;
  sock->socket_id = btif_l2cap_sock_generate_socket_id();

  log::info(
          "Connected to L2CAP connection for device: {}, channel: {}, app_uid: {}, "
          "id: {}, is_le: {}, socket_id: {}, rx_mtu: {}",
          sock->addr, sock->channel, sock->app_uid, sock->id, sock->is_le_coc, sock->socket_id,
          sock->rx_mtu);
  btif_sock_connection_logger(sock->addr, sock->id,
                              sock->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_CONNECTED,
                              sock->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              sock->app_uid, sock->channel, 0, 0, sock->name);

  bluetooth::hal::SocketContext socket_context = {
          .socket_id = sock->socket_id,
          .name = sock->socket_name,
          .acl_connection_handle = p_open->acl_handle,
          .channel_info = bluetooth::hal::LeCocChannelInfo(
                  p_open->local_cid, p_open->remote_cid, static_cast<uint16_t>(sock->channel),
                  sock->rx_mtu, sock->tx_mtu, p_open->local_coc_mps, p_open->remote_coc_mps,
                  p_open->local_coc_credit, p_open->remote_coc_credit),
          .endpoint_info.hub_id = sock->hub_id,
          .endpoint_info.endpoint_id = sock->endpoint_id,
  };
  if (!bluetooth::shim::GetLppOffloadManager()->SocketOpened(socket_context)) {
    log::warn("L2CAP socket opened failed. Disconnect the incoming connection.");
    btsock_l2cap_free_l(sock);
  } else {
    log::info(
            "L2CAP socket opened successful. Will send connect signal in "
            "on_btsocket_l2cap_opened_complete() asynchronously.");
  }
}

static void on_srv_l2cap_psm_connect_offload_l(tBTA_JV_L2CAP_OPEN* p_open, l2cap_socket* sock) {
  // std::mutex locked by caller
  l2cap_socket* accept_rs = btsock_l2cap_alloc_l(sock->name, &p_open->rem_bda, false, 0);
  accept_rs->connected = true;
  accept_rs->security = sock->security;
  accept_rs->channel = sock->channel;
  accept_rs->handle = sock->handle;
  accept_rs->app_uid = sock->app_uid;
  sock->handle = -1; /* We should no longer associate this handle with the server socket */
  accept_rs->is_le_coc = sock->is_le_coc;
  accept_rs->tx_mtu = sock->tx_mtu = p_open->tx_mtu;
  accept_rs->rx_mtu = sock->rx_mtu;
  accept_rs->local_cid = p_open->local_cid;
  accept_rs->remote_cid = p_open->remote_cid;
  // TODO(b/342012881) Remove connection uuid when offload socket API is landed.
  Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
  accept_rs->conn_uuid = uuid;
  accept_rs->socket_id = btif_l2cap_sock_generate_socket_id();
  accept_rs->data_path = sock->data_path;
  strncpy(accept_rs->socket_name, sock->socket_name, sizeof(accept_rs->socket_name) - 1);
  accept_rs->socket_name[sizeof(accept_rs->socket_name) - 1] = '\0';
  accept_rs->hub_id = sock->hub_id;
  accept_rs->endpoint_id = sock->endpoint_id;
  accept_rs->listen_fd = sock->our_fd;

  /* Swap IDs to hand over the GAP connection to the accepted socket, and start
     a new server on the newly create socket ID. */
  uint32_t new_listen_id = accept_rs->id;
  accept_rs->id = sock->id;
  sock->id = new_listen_id;

  log::info(
          "Connected to L2CAP connection for device: {}, channel: {}, app_uid: {}, "
          "id: {}, is_le: {}, socket_id: {}, rx_mtu: {}",
          accept_rs->addr, accept_rs->channel, accept_rs->app_uid, accept_rs->id,
          accept_rs->is_le_coc, accept_rs->socket_id, accept_rs->rx_mtu);
  btif_sock_connection_logger(accept_rs->addr, accept_rs->id,
                              accept_rs->is_le_coc ? BTSOCK_L2CAP_LE : BTSOCK_L2CAP,
                              SOCKET_CONNECTION_STATE_CONNECTED,
                              accept_rs->server ? SOCKET_ROLE_LISTEN : SOCKET_ROLE_CONNECTION,
                              accept_rs->app_uid, accept_rs->channel, 0, 0, accept_rs->name);

  bluetooth::hal::SocketContext socket_context = {
          .socket_id = accept_rs->socket_id,
          .name = accept_rs->socket_name,
          .acl_connection_handle = p_open->acl_handle,
          .channel_info = bluetooth::hal::LeCocChannelInfo(
                  p_open->local_cid, p_open->remote_cid, static_cast<uint16_t>(accept_rs->channel),
                  accept_rs->rx_mtu, accept_rs->tx_mtu, p_open->local_coc_mps,
                  p_open->remote_coc_mps, p_open->local_coc_credit, p_open->remote_coc_credit),
          .endpoint_info.hub_id = accept_rs->hub_id,
          .endpoint_info.endpoint_id = accept_rs->endpoint_id,
  };
  if (!sock->is_accepting) {
    log::warn("Server socket is not accepting. Disconnect the incoming connection.");
    btsock_l2cap_free_l(accept_rs);
  } else if (!bluetooth::shim::GetLppOffloadManager()->SocketOpened(socket_context)) {
    log::warn("L2CAP socket opened failed. Disconnect the incoming connection.");
    btsock_l2cap_free_l(accept_rs);
  } else {
    log::info("L2CAP socket opened successful. Will send connect signal in async callback.");
  }
  // start monitor the socket
  btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_EXCEPTION, sock->id);
  btsock_l2cap_server_listen(sock);
  // start monitoring the socketpair to get call back when app is accepting on server socket
  btsock_thread_add_fd(pth, sock->our_fd, BTSOCK_L2CAP, SOCK_THREAD_FD_RD, sock->id);
}
