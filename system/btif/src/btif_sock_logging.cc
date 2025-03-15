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

#include "btif/include/btif_sock.h"
#include "common/time_util.h"
#include "main/shim/metrics_api.h"
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

static android::bluetooth::SocketConnectionstateEnum toConnectionStateEnum(int state);
static android::bluetooth::SocketRoleEnum toSocketRoleEnum(int role);
static android::bluetooth::SocketErrorEnum toSocketErrorEnum(btsock_error_code_t error_code);
static uint64_t getConnectionDuration(uint64_t start_time_ms);

void btif_sock_connection_logger(const RawAddress& address, int port, int type, int state, int role,
                                 int uid, int server_port, int64_t tx_bytes, int64_t rx_bytes,
                                 const char* server_name, uint64_t connection_start_time_ms,
                                 btsock_error_code_t error_code, btsock_data_path_t data_path) {
  log::verbose("bd_addr: {}, port: {}, role: {}, state: {}, data_path: {}", address, port, role,
               state, data_path);

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
  bluetooth::shim::LogMetricSocketConnectionState(
          address, port, type, toConnectionStateEnum(state), tx_bytes, rx_bytes, uid, server_port,
          toSocketRoleEnum(role), getConnectionDuration(connection_start_time_ms),
          toSocketErrorEnum(error_code), data_path == BTSOCK_DATA_PATH_HARDWARE_OFFLOAD);
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
  snprintf(eventtime, sizeof(eventtime), "%s.%03ld", temptime, timestamp.tv_nsec / 1000000);

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
          addr.ToRedactedStringForLogging().c_str(), str_state, str_role, channel, str_type,
          server_name);
}

static android::bluetooth::SocketConnectionstateEnum toConnectionStateEnum(int state) {
  switch (state) {
    case SOCKET_CONNECTION_STATE_LISTENING:
      return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_LISTENING;
      break;
    case SOCKET_CONNECTION_STATE_CONNECTING:
      return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_CONNECTING;
    case SOCKET_CONNECTION_STATE_CONNECTED:
      return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_CONNECTED;
    case SOCKET_CONNECTION_STATE_DISCONNECTING:
      return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_DISCONNECTING;
    case SOCKET_CONNECTION_STATE_DISCONNECTED:
      return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_DISCONNECTED;
  }
  return android::bluetooth::SocketConnectionstateEnum::SOCKET_CONNECTION_STATE_UNKNOWN;
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

static android::bluetooth::SocketErrorEnum toSocketErrorEnum(btsock_error_code_t error_code) {
  switch (error_code) {
    case BTSOCK_ERROR_NONE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_NONE;
    case BTSOCK_ERROR_SERVER_START_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_SERVER_START_FAILURE;
    case BTSOCK_ERROR_CLIENT_INIT_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_CLIENT_INIT_FAILURE;
    case BTSOCK_ERROR_LISTEN_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_LISTEN_FAILURE;
    case BTSOCK_ERROR_CONNECTION_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_CONNECTION_FAILURE;
    case BTSOCK_ERROR_OPEN_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_OPEN_FAILURE;
    case BTSOCK_ERROR_OFFLOAD_SERVER_NOT_ACCEPTING:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_OFFLOAD_SERVER_NOT_ACCEPTING;
    case BTSOCK_ERROR_OFFLOAD_HAL_OPEN_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_OFFLOAD_HAL_OPEN_FAILURE;
    case BTSOCK_ERROR_SEND_TO_APP_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_SEND_TO_APP_FAILURE;
    case BTSOCK_ERROR_RECEIVE_DATA_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_RECEIVE_DATA_FAILURE;
    case BTSOCK_ERROR_READ_SIGNALED_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_READ_SIGNALED_FAILURE;
    case BTSOCK_ERROR_WRITE_SIGNALED_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_WRITE_SIGNALED_FAILURE;
    case BTSOCK_ERROR_SEND_SCN_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_SEND_SCN_FAILURE;
    case BTSOCK_ERROR_SCN_ALLOCATION_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_SCN_ALLOCATION_FAILURE;
    case BTSOCK_ERROR_ADD_SDP_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_ADD_SDP_FAILURE;
    case BTSOCK_ERROR_SDP_DISCOVERY_FAILURE:
      return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_SDP_DISCOVERY_FAILURE;
  }
  return android::bluetooth::SocketErrorEnum::SOCKET_ERROR_NONE;
}

static uint64_t getConnectionDuration(uint64_t start_time_ms) {
  // start time is 0 before the connection state, use 0 for duration
  if (start_time_ms == 0) {
    return 0;
  }
  uint64_t current_time_ms = common::time_gettimeofday_us() / 1000;
  if (current_time_ms <= start_time_ms) {
    log::warn("Socket connection end time is not greater than start time, logging 0 ms instead");
    return 0;
  }
  return current_time_ms - start_time_ms;
}
