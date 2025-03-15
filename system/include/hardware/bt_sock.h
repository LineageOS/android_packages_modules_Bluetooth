/*
 * Copyright (C) 2012 The Android Open Source Project
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

#pragma once

#include <stddef.h>

#include "bluetooth.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

__BEGIN_DECLS

#define BTSOCK_FLAG_ENCRYPT 1
#define BTSOCK_FLAG_AUTH (1 << 1)
#define BTSOCK_FLAG_NO_SDP (1 << 2)
#define BTSOCK_FLAG_AUTH_MITM (1 << 3)
#define BTSOCK_FLAG_AUTH_16_DIGIT (1 << 4)
#define BTSOCK_FLAG_LE_COC (1 << 5)

typedef enum {
  BTSOCK_RFCOMM = 1,
  BTSOCK_SCO = 2,
  BTSOCK_L2CAP = 3,
  BTSOCK_L2CAP_LE = 4
} btsock_type_t;

typedef enum {
  BTSOCK_ERROR_NONE = 0,
  BTSOCK_ERROR_SERVER_START_FAILURE = 1,
  BTSOCK_ERROR_CLIENT_INIT_FAILURE = 2,
  BTSOCK_ERROR_LISTEN_FAILURE = 3,
  BTSOCK_ERROR_CONNECTION_FAILURE = 4,
  BTSOCK_ERROR_OPEN_FAILURE = 5,
  BTSOCK_ERROR_OFFLOAD_SERVER_NOT_ACCEPTING = 6,
  BTSOCK_ERROR_OFFLOAD_HAL_OPEN_FAILURE = 7,
  BTSOCK_ERROR_SEND_TO_APP_FAILURE = 8,
  BTSOCK_ERROR_RECEIVE_DATA_FAILURE = 9,
  BTSOCK_ERROR_READ_SIGNALED_FAILURE = 10,
  BTSOCK_ERROR_WRITE_SIGNALED_FAILURE = 11,
  BTSOCK_ERROR_SEND_SCN_FAILURE = 12,
  BTSOCK_ERROR_SCN_ALLOCATION_FAILURE = 13,
  BTSOCK_ERROR_ADD_SDP_FAILURE = 14,
  BTSOCK_ERROR_SDP_DISCOVERY_FAILURE = 15,
} btsock_error_code_t;

/**
 * Data path used for Bluetooth socket communication.
 *
 * NOTE: The values must be same as:
 *    - BluetoothSocketSettings.DATA_PATH_NO_OFFLOAD = 0
 *    - BluetoothSocketSettings.DATA_PATH_HARDWARE_OFFLOAD = 1
 */
typedef enum {
  BTSOCK_DATA_PATH_NO_OFFLOAD = 0,
  BTSOCK_DATA_PATH_HARDWARE_OFFLOAD = 1,
} btsock_data_path_t;

/** Represents the standard BT SOCKET interface. */
typedef struct {
  int16_t size;
  RawAddress bd_addr;
  int channel;
  int status;

  // The writer must make writes using a buffer of this maximum size
  // to avoid loosing data. (L2CAP only)
  uint16_t max_tx_packet_size;

  // The reader must read using a buffer of at least this size to avoid
  // loosing data. (L2CAP only)
  uint16_t max_rx_packet_size;

  // The connection uuid. (L2CAP only)
  uint64_t conn_uuid_lsb;
  uint64_t conn_uuid_msb;

  // Socket ID in connected state
  uint64_t socket_id;
} __attribute__((packed)) sock_connect_signal_t;

typedef struct {
  uint16_t size;
  uint16_t is_accepting;
} __attribute__((packed)) sock_accept_signal_t;

typedef struct {
  /** set to size of this struct*/
  size_t size;

  /**
   * Listen to a RFCOMM UUID or channel. It returns the socket fd from which
   * btsock_connect_signal can be read out when a remote device connected.
   * If neither a UUID nor a channel is provided, a channel will be allocated
   * and a service record can be created providing the channel number to
   * create_sdp_record(...) in bt_sdp.
   * The callingUid is the UID of the application which is requesting the
   * socket. This is used for traffic accounting purposes.
   */
  bt_status_t (*listen)(btsock_type_t type, const char* service_name,
                        const bluetooth::Uuid* service_uuid, int channel, int* sock_fd, int flags,
                        int callingUid, btsock_data_path_t data_path, const char* socket_name,
                        uint64_t hub_id, uint64_t endpoint_id, int max_rx_packet_size);

  /**
   * Connect to a RFCOMM UUID channel of remote device, It returns the socket fd
   * from which the btsock_connect_signal and a new socket fd to be accepted can
   * be read out when connected. The callingUid is the UID of the application
   * which is requesting the socket. This is used for traffic accounting
   * purposes.
   */
  bt_status_t (*connect)(const RawAddress* bd_addr, btsock_type_t type, const bluetooth::Uuid* uuid,
                         int channel, int* sock_fd, int flags, int callingUid,
                         btsock_data_path_t data_path, const char* socket_name, uint64_t hub_id,
                         uint64_t endpoint_id, int max_rx_packet_size);

  /**
   * Set the LE Data Length value to this connected peer to the
   * maximum supported by this BT controller. This command
   * suggests to the BT controller to set its maximum transmission
   * packet size.
   */
  void (*request_max_tx_data_length)(const RawAddress& bd_addr);

  /**
   * Send control parameters to the peer. So far only for qualification use.
   * RFCOMM layer starts the control request only when it is the client.
   * This API allows the host to start the control request while it works as an
   * RFCOMM server.
   */
  bt_status_t (*control_req)(uint8_t dlci, const RawAddress& bd_addr, uint8_t modem_signal,
                             uint8_t break_signal, uint8_t discard_buffers,
                             uint8_t break_signal_seq, bool fc);

  /**
   * Disconnect all RFCOMM and L2CAP socket connections with the associated
   * device address.
   */
  bt_status_t (*disconnect_all)(const RawAddress* bd_addr);

  /**
   * Get L2CAP local channel ID with the associated connection uuid.
   */
  bt_status_t (*get_l2cap_local_cid)(bluetooth::Uuid& conn_uuid, uint16_t* cid);

  /**
   * Get L2CAP remote channel ID with the associated connection uuid.
   */
  bt_status_t (*get_l2cap_remote_cid)(bluetooth::Uuid& conn_uuid, uint16_t* cid);
} btsock_interface_t;

__END_DECLS

#if __has_include(<bluetooth/log.h>)
#include <bluetooth/log.h>

namespace std {
template <>
struct formatter<btsock_type_t> : enum_formatter<btsock_type_t> {};

template <>
struct formatter<btsock_data_path_t> : enum_formatter<btsock_data_path_t> {};
}  // namespace std

#endif  // __has_include(<bluetooth/log.h>)
