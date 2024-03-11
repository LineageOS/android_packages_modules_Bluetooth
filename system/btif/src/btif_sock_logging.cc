/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "bt_btif_sock"

#include "btif/include/btif_sock_logging.h"

#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <time.h>

#include <atomic>

#include "btif/include/btif_metrics_logging.h"
#include "btif/include/btif_sock.h"
#include "os/log.h"
#include "types/raw_address.h"

#define SOCK_LOGGER_SIZE_MAX 16

using namespace bluetooth;

struct SockConnectionEvent {
  bool used;
  RawAddress addr;
  int state;
  int role;
  int channel;
  int type;
  char server_name[64];
  struct timespec timestamp;

  void dump(const int fd);
};

static std::atomic<uint8_t> logger_index;

static SockConnectionEvent connection_logger[SOCK_LOGGER_SIZE_MAX];

static android::bluetooth::SocketConnectionstateEnum toConnectionStateEnum(
    int state);
static android::bluetooth::SocketRoleEnum toSocketRoleEnum(int role);

void btif_sock_connection_logger(const RawAddress& address, int port, int type,
                                 int state, int role, int uid, int server_port,
                                 int64_t tx_bytes, int64_t rx_bytes,
                                 const char* server_name) {
  uint8_t index = logger_index++ % SOCK_LOGGER_SIZE_MAX;

  connection_logger[index] = {
      .used = true,
      .addr = address,
      .state = state,
      .role = role,
      .channel = server_port,
      .type = type,
      .server_name = {'\0'},
  };

  if (server_name != nullptr) {
    strncpy(connection_logger[index].server_name, server_name,
            sizeof(connection_logger[index].server_name) - 1);
  }

  clock_gettime(CLOCK_REALTIME, &connection_logger[index].timestamp);
  log_socket_connection_state(address, port, type, toConnectionStateEnum(state),
                              tx_bytes, rx_bytes, uid, server_port,
                              toSocketRoleEnum(role));
}

void btif_sock_dump(int fd) {
  dprintf(fd, "\nSocket Events: \n");
  dprintf(fd,
          "  Time        \tAddress          \tState             \tRole"
          "              \tChannel   \tType     \tServerName\n");

  const uint8_t head = logger_index.load() % SOCK_LOGGER_SIZE_MAX;

  uint8_t index = head;
  do {
    connection_logger[index].dump(fd);

    index++;
    index %= SOCK_LOGGER_SIZE_MAX;
  } while (index != head);
  dprintf(fd, "\n");
}

void SockConnectionEvent::dump(const int fd) {
  if (!used) {
    return;
  }

  char eventtime[20];
  char temptime[20];
  struct tm* tstamp = localtime(&timestamp.tv_sec);
  strftime(temptime, sizeof(temptime), "%H:%M:%S", tstamp);
  snprintf(eventtime, sizeof(eventtime), "%s.%03ld", temptime,
           timestamp.tv_nsec / 1000000);

  const char* str_state;
  switch (state) {
    case SOCKET_CONNECTION_STATE_LISTENING:
      str_state = "STATE_LISTENING";
      break;
    case SOCKET_CONNECTION_STATE_CONNECTING:
      str_state = "STATE_CONNECTING";
      break;
    case SOCKET_CONNECTION_STATE_CONNECTED:
      str_state = "STATE_CONNECTED";
      break;
    case SOCKET_CONNECTION_STATE_DISCONNECTING:
      str_state = "STATE_DISCONNECTING";
      break;
    case SOCKET_CONNECTION_STATE_DISCONNECTED:
      str_state = "STATE_DISCONNECTED";
      break;
    default:
      str_state = "STATE_UNKNOWN";
      break;
  }

  const char* str_role;
  switch (role) {
    case SOCKET_ROLE_LISTEN:
      str_role = "ROLE_LISTEN";
      break;
    case SOCKET_ROLE_CONNECTION:
      str_role = "ROLE_CONNECTION";
      break;
    default:
      str_role = "ROLE_UNKNOWN";
      break;
  }

  const char* str_type;
  switch (type) {
    case BTSOCK_RFCOMM:
      str_type = "RFCOMM";
      break;
    case BTSOCK_L2CAP:
      str_type = "L2CAP";
      break;
    case BTSOCK_L2CAP_LE:
      str_type = "L2CAP_LE";
      break;
    case BTSOCK_SCO:
      str_type = "SCO";
      break;
    default:
      str_type = "UNKNOWN";
      break;
  }

  dprintf(fd, "  %s\t%s\t%s   \t%s      \t%d         \t%s\t%s\n", eventtime,
          ADDRESS_TO_LOGGABLE_CSTR(addr), str_state, str_role, channel,
          str_type, server_name);
}

static android::bluetooth::SocketConnectionstateEnum toConnectionStateEnum(
    int state) {
  switch (state) {
    case SOCKET_CONNECTION_STATE_LISTENING:
      return android::bluetooth::SocketConnectionstateEnum::
          SOCKET_CONNECTION_STATE_LISTENING;
      break;
    case SOCKET_CONNECTION_STATE_CONNECTING:
      return android::bluetooth::SocketConnectionstateEnum::
          SOCKET_CONNECTION_STATE_CONNECTING;
    case SOCKET_CONNECTION_STATE_CONNECTED:
      return android::bluetooth::SocketConnectionstateEnum::
          SOCKET_CONNECTION_STATE_CONNECTED;
    case SOCKET_CONNECTION_STATE_DISCONNECTING:
      return android::bluetooth::SocketConnectionstateEnum::
          SOCKET_CONNECTION_STATE_DISCONNECTING;
    case SOCKET_CONNECTION_STATE_DISCONNECTED:
      return android::bluetooth::SocketConnectionstateEnum::
          SOCKET_CONNECTION_STATE_DISCONNECTED;
  }
  return android::bluetooth::SocketConnectionstateEnum::
      SOCKET_CONNECTION_STATE_UNKNOWN;
}

static android::bluetooth::SocketRoleEnum toSocketRoleEnum(int role) {
  switch (role) {
    case SOCKET_ROLE_LISTEN:
      return android::bluetooth::SOCKET_ROLE_LISTEN;
    case SOCKET_ROLE_CONNECTION:
      return android::bluetooth::SOCKET_ROLE_CONNECTION;
  }
  return android::bluetooth::SOCKET_ROLE_UNKNOWN;
}