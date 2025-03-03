/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/*****************************************************************************
 *
 *  Filename:      uipc.cc
 *
 *  Description:   UIPC implementation for fluoride
 *
 *****************************************************************************/

#define LOG_TAG "uipc"

#include "udrv/include/uipc.h"

#include <bluetooth/log.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <mutex>

#include "osi/include/osi.h"
#include "osi/include/socket_utils/sockets.h"

using namespace bluetooth;

/*****************************************************************************
 *  Constants & Macros
 *****************************************************************************/

// AUDIO_STREAM_OUTPUT_BUFFER_SZ controls the size of the audio socket buffer.
// If one assumes the write buffer is always full during normal BT playback,
// then increasing this value increases our playback latency.
//
// FIXME: The BT HAL should consume data at a constant rate.
// AudioFlinger assumes that the HAL draws data at a constant rate, which is
// true for most audio devices; however, the BT engine reads data at a variable
// rate (over the short term), which confuses both AudioFlinger as well as
// applications which deliver data at a (generally) fixed rate.
//
// 20 * 512 is not sufficient to smooth the variability for some BT devices,
// resulting in mixer sleep and throttling. We increase this to 28 * 512 to help
// reduce the effect of variable data consumption.
#define AUDIO_STREAM_OUTPUT_BUFFER_SZ (28 * 512)

#define MAX(a, b) ((a) > (b) ? (a) : (b))

#define CASE_RETURN_STR(const) \
  case const:                  \
    return #const;

#define UIPC_DISCONNECTED (-1)

#define SAFE_FD_ISSET(fd, set) (((fd) == -1) ? false : FD_ISSET((fd), (set)))

#define UIPC_FLUSH_BUFFER_SIZE 1024

/*****************************************************************************
 *  Local type definitions
 *****************************************************************************/

typedef enum {
  UIPC_TASK_FLAG_DISCONNECT_CHAN = 0x1,
} tUIPC_TASK_FLAGS;

/*****************************************************************************
 *  Static functions
 *****************************************************************************/
static int uipc_close_ch_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id);

/*****************************************************************************
 *  Externs
 *****************************************************************************/

/*****************************************************************************
 *   Helper functions
 *****************************************************************************/

const char* dump_uipc_event(tUIPC_EVENT event) {
  switch (event) {
    CASE_RETURN_STR(UIPC_OPEN_EVT)
    CASE_RETURN_STR(UIPC_CLOSE_EVT)
    CASE_RETURN_STR(UIPC_RX_DATA_EVT)
    CASE_RETURN_STR(UIPC_RX_DATA_READY_EVT)
    CASE_RETURN_STR(UIPC_TX_DATA_READY_EVT)
    default:
      return "UNKNOWN MSG ID";
  }
}

/*****************************************************************************
 *   socket helper functions
 ****************************************************************************/

static inline int create_server_socket(const char* name) {
  int s = socket(AF_LOCAL, SOCK_STREAM, 0);
  if (s < 0) {
    return -1;
  }

  log::debug("create_server_socket {}", name);

  if (osi_socket_local_server_bind(s, name,
#ifdef __ANDROID__
                                   ANDROID_SOCKET_NAMESPACE_ABSTRACT
#else   // !__ANDROID__
                                   ANDROID_SOCKET_NAMESPACE_FILESYSTEM
#endif  // __ANDROID__
                                   ) < 0) {
    log::debug("socket failed to create ({})", strerror(errno));
    close(s);
    return -1;
  }

  if (listen(s, 5) < 0) {
    log::debug("listen failed: {}", strerror(errno));
    close(s);
    return -1;
  }

  log::debug("created socket fd {}", s);
  return s;
}

static int accept_server_socket(int sfd) {
  struct sockaddr_un remote;
  struct pollfd pfd;
  int fd;
  socklen_t len = sizeof(struct sockaddr_un);

  log::debug("accept fd {}", sfd);

  /* make sure there is data to process */
  pfd.fd = sfd;
  pfd.events = POLLIN;

  int poll_ret;
  OSI_NO_INTR(poll_ret = poll(&pfd, 1, 0));
  if (poll_ret == 0) {
    log::warn("accept poll timeout");
    return -1;
  }

  OSI_NO_INTR(fd = accept(sfd, (struct sockaddr*)&remote, &len));
  if (fd == -1) {
    log::error("sock accept failed ({})", strerror(errno));
    return -1;
  }

  // match socket buffer size option with client
  const int size = AUDIO_STREAM_OUTPUT_BUFFER_SZ;
  int ret = setsockopt(fd, SOL_SOCKET, SO_RCVBUF, (char*)&size, (int)sizeof(size));
  if (ret < 0) {
    log::error("setsockopt failed ({})", strerror(errno));
  }

  return fd;
}

/*****************************************************************************
 *
 *   uipc helper functions
 *
 ****************************************************************************/

static int uipc_main_init(tUIPC_STATE& uipc) {
  int i;

  log::debug("### uipc_main_init ###");

  uipc.tid = 0;
  uipc.running = 0;
  memset(&uipc.active_set, 0, sizeof(uipc.active_set));
  memset(&uipc.read_set, 0, sizeof(uipc.read_set));
  uipc.max_fd = 0;
  memset(&uipc.signal_fds, 0, sizeof(uipc.signal_fds));
  memset(&uipc.ch, 0, sizeof(uipc.ch));

  /* setup interrupt socket pair */
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, uipc.signal_fds) < 0) {
    return -1;
  }

  FD_SET(uipc.signal_fds[0], &uipc.active_set);
  uipc.max_fd = MAX(uipc.max_fd, uipc.signal_fds[0]);

  for (i = 0; i < UIPC_CH_NUM; i++) {
    tUIPC_CHAN* p = &uipc.ch[i];
    p->srvfd = UIPC_DISCONNECTED;
    p->fd = UIPC_DISCONNECTED;
    p->task_evt_flags = 0;
    p->cback = NULL;
  }

  return 0;
}

static void uipc_main_cleanup(tUIPC_STATE& uipc) {
  int i;

  log::debug("uipc_main_cleanup");

  close(uipc.signal_fds[0]);
  close(uipc.signal_fds[1]);

  /* close any open channels */
  for (i = 0; i < UIPC_CH_NUM; i++) {
    uipc_close_ch_locked(uipc, i);
  }
}

/* check pending events in read task */
static void uipc_check_task_flags_locked(tUIPC_STATE& uipc) {
  int i;

  for (i = 0; i < UIPC_CH_NUM; i++) {
    if (uipc.ch[i].task_evt_flags & UIPC_TASK_FLAG_DISCONNECT_CHAN) {
      uipc.ch[i].task_evt_flags &= ~UIPC_TASK_FLAG_DISCONNECT_CHAN;
      uipc_close_ch_locked(uipc, i);
    }

    /* add here */
  }
}

static int uipc_check_fd_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  if (ch_id >= UIPC_CH_NUM) {
    return -1;
  }

  if (SAFE_FD_ISSET(uipc.ch[ch_id].srvfd, &uipc.read_set)) {
    log::debug("INCOMING CONNECTION ON CH {}", ch_id);

    // Close the previous connection
    if (uipc.ch[ch_id].fd != UIPC_DISCONNECTED) {
      log::debug("CLOSE CONNECTION (FD {})", uipc.ch[ch_id].fd);
      close(uipc.ch[ch_id].fd);
      FD_CLR(uipc.ch[ch_id].fd, &uipc.active_set);
      uipc.ch[ch_id].fd = UIPC_DISCONNECTED;
    }

    uipc.ch[ch_id].fd = accept_server_socket(uipc.ch[ch_id].srvfd);

    log::debug("NEW FD {}", uipc.ch[ch_id].fd);

    if ((uipc.ch[ch_id].fd >= 0) && uipc.ch[ch_id].cback) {
      /*  if we have a callback we should add this fd to the active set
          and notify user with callback event */
      log::debug("ADD FD {} TO ACTIVE SET", uipc.ch[ch_id].fd);
      FD_SET(uipc.ch[ch_id].fd, &uipc.active_set);
      uipc.max_fd = MAX(uipc.max_fd, uipc.ch[ch_id].fd);
    }

    if (uipc.ch[ch_id].fd < 0) {
      log::error("FAILED TO ACCEPT CH {}", ch_id);
      return -1;
    }

    if (uipc.ch[ch_id].cback) {
      uipc.ch[ch_id].cback(ch_id, UIPC_OPEN_EVT);
    }
  }

  if (SAFE_FD_ISSET(uipc.ch[ch_id].fd, &uipc.read_set)) {
    if (uipc.ch[ch_id].cback) {
      uipc.ch[ch_id].cback(ch_id, UIPC_RX_DATA_READY_EVT);
    }
  }
  return 0;
}

static void uipc_check_interrupt_locked(tUIPC_STATE& uipc) {
  if (SAFE_FD_ISSET(uipc.signal_fds[0], &uipc.read_set)) {
    char sig_recv = 0;
    OSI_NO_INTR(recv(uipc.signal_fds[0], &sig_recv, sizeof(sig_recv), MSG_WAITALL));
  }
}

static inline void uipc_wakeup_locked(tUIPC_STATE& uipc) {
  char sig_on = 1;
  log::debug("UIPC SEND WAKE UP");

  OSI_NO_INTR(send(uipc.signal_fds[1], &sig_on, sizeof(sig_on), 0));
}

static int uipc_setup_server_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id, const char* name,
                                    tUIPC_RCV_CBACK* cback) {
  int fd;

  log::debug("SETUP CHANNEL SERVER {}", ch_id);

  if (ch_id >= UIPC_CH_NUM) {
    return -1;
  }

  std::lock_guard<std::recursive_mutex> guard(uipc.mutex);

  fd = create_server_socket(name);

  if (fd < 0) {
    log::error("failed to setup {}: {}", name, strerror(errno));
    return -1;
  }

  log::debug("ADD SERVER FD TO ACTIVE SET {}", fd);
  FD_SET(fd, &uipc.active_set);
  uipc.max_fd = MAX(uipc.max_fd, fd);

  uipc.ch[ch_id].srvfd = fd;
  uipc.ch[ch_id].cback = cback;
  uipc.ch[ch_id].read_poll_tmo_ms = DEFAULT_READ_POLL_TMO_MS;

  /* trigger main thread to update read set */
  uipc_wakeup_locked(uipc);

  return 0;
}

static void uipc_flush_ch_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  char buf[UIPC_FLUSH_BUFFER_SIZE];
  struct pollfd pfd;

  pfd.events = POLLIN;
  pfd.fd = uipc.ch[ch_id].fd;

  if (uipc.ch[ch_id].fd == UIPC_DISCONNECTED) {
    log::debug("fd disconnected. Exiting");
    return;
  }

  while (1) {
    int ret;
    OSI_NO_INTR(ret = poll(&pfd, 1, 1));
    if (ret == 0) {
      log::verbose("poll() timeout - nothing to do. Exiting");
      return;
    }
    if (ret < 0) {
      log::warn("poll() failed: return {} errno {} ({}). Exiting", ret, errno, strerror(errno));
      return;
    }
    log::verbose("polling fd {}, revents: 0x{:x}, ret {}", pfd.fd, pfd.revents, ret);
    if (pfd.revents & (POLLERR | POLLHUP)) {
      log::warn("POLLERR or POLLHUP. Exiting");
      return;
    }

    /* read sufficiently large buffer to ensure flush empties socket faster than
       it is getting refilled */
    (void)read(pfd.fd, &buf, UIPC_FLUSH_BUFFER_SIZE);
  }
}

static void uipc_flush_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  if (ch_id >= UIPC_CH_NUM) {
    return;
  }

  switch (ch_id) {
    case UIPC_CH_ID_AV_CTRL:
      uipc_flush_ch_locked(uipc, UIPC_CH_ID_AV_CTRL);
      break;

    case UIPC_CH_ID_AV_AUDIO:
      uipc_flush_ch_locked(uipc, UIPC_CH_ID_AV_AUDIO);
      break;
  }
}

static int uipc_close_ch_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  int wakeup = 0;

  log::debug("CLOSE CHANNEL {}", ch_id);

  if (ch_id >= UIPC_CH_NUM) {
    return -1;
  }

  if (uipc.ch[ch_id].srvfd != UIPC_DISCONNECTED) {
    log::debug("CLOSE SERVER (FD {})", uipc.ch[ch_id].srvfd);
    close(uipc.ch[ch_id].srvfd);
    FD_CLR(uipc.ch[ch_id].srvfd, &uipc.active_set);
    uipc.ch[ch_id].srvfd = UIPC_DISCONNECTED;
    wakeup = 1;
  }

  if (uipc.ch[ch_id].fd != UIPC_DISCONNECTED) {
    log::debug("CLOSE CONNECTION (FD {})", uipc.ch[ch_id].fd);
    close(uipc.ch[ch_id].fd);
    FD_CLR(uipc.ch[ch_id].fd, &uipc.active_set);
    uipc.ch[ch_id].fd = UIPC_DISCONNECTED;
    wakeup = 1;
  }

  /* notify this connection is closed */
  if (uipc.ch[ch_id].cback) {
    uipc.ch[ch_id].cback(ch_id, UIPC_CLOSE_EVT);
  }

  /* trigger main thread update if something was updated */
  if (wakeup) {
    uipc_wakeup_locked(uipc);
  }

  return 0;
}

static void uipc_close_locked(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  if (uipc.ch[ch_id].srvfd == UIPC_DISCONNECTED) {
    log::debug("CHANNEL {} ALREADY CLOSED", ch_id);
    return;
  }

  /* schedule close on this channel */
  uipc.ch[ch_id].task_evt_flags |= UIPC_TASK_FLAG_DISCONNECT_CHAN;
  uipc_wakeup_locked(uipc);
}

static void* uipc_read_task(void* arg) {
  tUIPC_STATE& uipc = *((tUIPC_STATE*)arg);
  int ch_id;
  int result;

  prctl(PR_SET_NAME, (unsigned long)"uipc-main", 0, 0, 0);

  while (uipc.running) {
    uipc.read_set = uipc.active_set;

    result = select(uipc.max_fd + 1, &uipc.read_set, NULL, NULL, NULL);

    if (result == 0) {
      log::debug("select timeout");
      continue;
    }
    if (result < 0) {
      if (errno != EINTR) {
        log::debug("select failed {}", strerror(errno));
      }
      continue;
    }

    {
      std::lock_guard<std::recursive_mutex> guard(uipc.mutex);

      /* clear any wakeup interrupt */
      uipc_check_interrupt_locked(uipc);

      /* check pending task events */
      uipc_check_task_flags_locked(uipc);

      /* make sure we service audio channel first */
      uipc_check_fd_locked(uipc, UIPC_CH_ID_AV_AUDIO);

      /* check for other connections */
      for (ch_id = 0; ch_id < UIPC_CH_NUM; ch_id++) {
        if (ch_id != UIPC_CH_ID_AV_AUDIO) {
          uipc_check_fd_locked(uipc, ch_id);
        }
      }
    }
  }

  log::debug("UIPC READ THREAD EXITING");

  uipc_main_cleanup(uipc);

  uipc.tid = 0;

  log::debug("UIPC READ THREAD DONE");

  return nullptr;
}

static int uipc_start_main_server_thread(tUIPC_STATE& uipc) {
  uipc.running = 1;

  if (pthread_create(&uipc.tid, (const pthread_attr_t*)NULL, uipc_read_task, &uipc) != 0) {
    log::error("uipc_thread_create pthread_create failed:{}", errno);
    return -1;
  }

  return 0;
}

/* blocking call */
static void uipc_stop_main_server_thread(tUIPC_STATE& uipc) {
  /* request shutdown of read thread */
  {
    std::lock_guard<std::recursive_mutex> lock(uipc.mutex);
    uipc.running = 0;
    uipc_wakeup_locked(uipc);
  }

  /* wait until read thread is fully terminated */
  /* tid might hold pointer value where it's value
     is negative value with signed bit is set, so
     corrected the logic to check zero or non zero */
  if (uipc.tid) {
    pthread_join(uipc.tid, NULL);
  }
}

/*******************************************************************************
 **
 ** Function         UIPC_Init
 **
 ** Description      Initialize UIPC module
 **
 ** Returns          void
 **
 ******************************************************************************/
std::unique_ptr<tUIPC_STATE> UIPC_Init() {
  std::unique_ptr<tUIPC_STATE> uipc = std::make_unique<tUIPC_STATE>();
  log::debug("UIPC_Init");

  std::lock_guard<std::recursive_mutex> lock(uipc->mutex);

  uipc_main_init(*uipc);
  uipc_start_main_server_thread(*uipc);

  return uipc;
}

/*******************************************************************************
 **
 ** Function         UIPC_Open
 **
 ** Description      Open UIPC interface
 **
 ** Returns          true in case of success, false in case of failure.
 **
 ******************************************************************************/
bool UIPC_Open(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id, tUIPC_RCV_CBACK* p_cback,
               const char* socket_path) {
  log::debug("UIPC_Open : ch_id {}", ch_id);

  std::lock_guard<std::recursive_mutex> lock(uipc.mutex);

  if (ch_id >= UIPC_CH_NUM) {
    return false;
  }

  if (uipc.ch[ch_id].srvfd != UIPC_DISCONNECTED) {
    log::debug("CHANNEL {} ALREADY OPEN", ch_id);
    return 0;
  }

  uipc_setup_server_locked(uipc, ch_id, socket_path, p_cback);

  return true;
}

/*******************************************************************************
 **
 ** Function         UIPC_Close
 **
 ** Description      Close UIPC interface
 **
 ** Returns          void
 **
 ******************************************************************************/
void UIPC_Close(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id) {
  log::debug("UIPC_Close : ch_id {}", ch_id);

  /* special case handling uipc shutdown */
  if (ch_id != UIPC_CH_ID_ALL) {
    std::lock_guard<std::recursive_mutex> lock(uipc.mutex);
    uipc_close_locked(uipc, ch_id);
    return;
  }

  log::debug("UIPC_Close : waiting for shutdown to complete");
  uipc_stop_main_server_thread(uipc);
  log::debug("UIPC_Close : shutdown complete");
}

/*******************************************************************************
 **
 ** Function         UIPC_Send
 **
 ** Description      Called to transmit a message over UIPC.
 **
 ** Returns          true in case of success, false in case of failure.
 **
 ******************************************************************************/
bool UIPC_Send(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id, uint16_t /* msg_evt */, const uint8_t* p_buf,
               uint16_t msglen) {
  log::verbose("UIPC_Send : ch_id:{} {} bytes", ch_id, msglen);

  std::lock_guard<std::recursive_mutex> lock(uipc.mutex);

  ssize_t ret;
  OSI_NO_INTR(ret = write(uipc.ch[ch_id].fd, p_buf, msglen));
  if (ret < 0) {
    log::error("failed to write ({})", strerror(errno));
    return false;
  }

  return true;
}

/*******************************************************************************
 **
 ** Function         UIPC_Read
 **
 ** Description      Called to read a message from UIPC.
 **
 ** Returns          return the number of bytes read.
 **
 ******************************************************************************/

uint32_t UIPC_Read(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id, uint8_t* p_buf, uint32_t len) {
  if (ch_id >= UIPC_CH_NUM) {
    log::error("UIPC_Read : invalid ch id {}", ch_id);
    return 0;
  }

  int n_read = 0;
  int fd = uipc.ch[ch_id].fd;
  struct pollfd pfd;

  if (fd == UIPC_DISCONNECTED) {
    log::error("UIPC_Read : channel {} closed", ch_id);
    return 0;
  }

  while (n_read < (int)len) {
    pfd.fd = fd;
    pfd.events = POLLIN | POLLHUP;

    /* make sure there is data prior to attempting read to avoid blocking
       a read for more than poll timeout */

    int poll_ret;
    OSI_NO_INTR(poll_ret = poll(&pfd, 1, uipc.ch[ch_id].read_poll_tmo_ms));
    if (poll_ret == 0) {
      log::warn("poll timeout ({} ms)", uipc.ch[ch_id].read_poll_tmo_ms);
      break;
    }
    if (poll_ret < 0) {
      log::error("poll() failed: return {} errno {} ({})", poll_ret, errno, strerror(errno));
      break;
    }

    if (pfd.revents & (POLLHUP | POLLNVAL)) {
      log::warn("poll : channel detached remotely");
      std::lock_guard<std::recursive_mutex> lock(uipc.mutex);
      uipc_close_locked(uipc, ch_id);
      return 0;
    }

    ssize_t n;
    OSI_NO_INTR(n = recv(fd, p_buf + n_read, len - n_read, 0));

    if (n == 0) {
      log::warn("UIPC_Read : channel detached remotely");
      std::lock_guard<std::recursive_mutex> lock(uipc.mutex);
      uipc_close_locked(uipc, ch_id);
      return 0;
    }

    if (n < 0) {
      log::warn("UIPC_Read : read failed ({})", strerror(errno));
      return 0;
    }

    n_read += n;
  }

  return n_read;
}

/*******************************************************************************
 *
 * Function         UIPC_Ioctl
 *
 * Description      Called to control UIPC.
 *
 * Returns          void
 *
 ******************************************************************************/

bool UIPC_Ioctl(tUIPC_STATE& uipc, tUIPC_CH_ID ch_id, uint32_t request, void* param) {
  log::debug("#### UIPC_Ioctl : ch_id {}, request {} ####", ch_id, request);
  std::lock_guard<std::recursive_mutex> lock(uipc.mutex);

  switch (request) {
    case UIPC_REQ_RX_FLUSH:
      uipc_flush_locked(uipc, ch_id);
      break;

    case UIPC_REG_REMOVE_ACTIVE_READSET:
      /* user will read data directly and not use select loop */
      if (uipc.ch[ch_id].fd != UIPC_DISCONNECTED) {
        /* remove this channel from active set */
        FD_CLR(uipc.ch[ch_id].fd, &uipc.active_set);

        /* refresh active set */
        uipc_wakeup_locked(uipc);
      }
      break;

    case UIPC_SET_READ_POLL_TMO:
      uipc.ch[ch_id].read_poll_tmo_ms = (intptr_t)param;
      log::debug("UIPC_SET_READ_POLL_TMO : CH {}, TMO {} ms", ch_id,
                 uipc.ch[ch_id].read_poll_tmo_ms);
      break;

    default:
      log::debug("UIPC_Ioctl : request not handled ({})", request);
      break;
  }

  return false;
}
