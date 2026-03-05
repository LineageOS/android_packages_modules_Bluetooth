/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_INCLUDE_BT_GATT_SERVER_H
#define ANDROID_INCLUDE_BT_GATT_SERVER_H

#include <bluetooth/types/address.h>
#include <hardware/bt_common_types.h>
#include <stdint.h>

#include "bt_gatt_types.h"
#include "bt_status.h"

__BEGIN_DECLS

/** GATT value type used in response to remote read requests */
typedef struct {
  uint8_t value[GATT_MAX_ATTR_LEN];
  uint16_t handle;
  uint16_t offset;
  uint16_t len;
  uint8_t auth_req;
} btgatt_value_t;

/** GATT remote read request response type */
typedef union {
  btgatt_value_t attr_value;
  uint16_t handle;
} btgatt_response_t;

/** BT-GATT Server callback structure. */

/** Callback invoked in response to register_server */
typedef void (*register_server_callback)(int status, int server_if,
                                         const bluetooth::Uuid& app_uuid);

/** Callback indicating that a remote device has connected or been disconnected
 */
typedef void (*connection_callback)(int conn_id, int server_if, int transport, int connected,
                                    const RawAddress& bda);

/** Callback invoked in response to create_service */
typedef void (*service_added_callback)(int status, int server_if,
                                       const btgatt_db_element_t* service, size_t service_count);

/** Callback triggered when a service has been deleted */
typedef void (*service_deleted_callback)(int status, int server_if, int srvc_handle);

/**
 * Callback invoked when a remote device has requested to read a characteristic
 * or descriptor. The application must respond by calling send_response
 */
typedef void (*request_read_callback)(int conn_id, int trans_id, const RawAddress& bda,
                                      int attr_handle, int offset, bool is_long);

/**
 * Callback invoked when a remote device has requested to write to a
 * characteristic or descriptor.
 */
typedef void (*request_write_callback)(int conn_id, int trans_id, const RawAddress& bda,
                                       int attr_handle, int offset, bool need_rsp, bool is_prep,
                                       const uint8_t* value, size_t length);

/** Callback invoked when a previously prepared write is to be executed */
typedef void (*request_exec_write_callback)(int conn_id, int trans_id, const RawAddress& bda,
                                            int exec_write);

/**
 * Callback triggered in response to send_response if the remote device
 * sends a confirmation.
 */
typedef void (*response_confirmation_callback)(int status, int handle);

/**
 * Callback confirming that a notification or indication has been sent
 * to a remote device.
 */
typedef void (*indication_sent_callback)(int conn_id, int status);

/**
 * Callback notifying an application that a remote device connection is
 * currently congested and cannot receive any more data. An application should
 * avoid sending more data until a further callback is received indicating the
 * congestion status has been cleared.
 */
typedef void (*congestion_callback)(int conn_id, bool congested);

/** Callback invoked when the MTU for a given connection changes */
typedef void (*mtu_changed_callback)(int conn_id, int mtu);

/** Callback invoked when the PHY for a given connection changes */
typedef void (*phy_updated_callback)(int conn_id, uint8_t tx_phy, uint8_t rx_phy, uint8_t status);

/** Callback invoked when the connection parameters for a given connection
 * changes */
typedef void (*conn_updated_callback)(int conn_id, uint16_t interval, uint16_t latency,
                                      uint16_t timeout, uint8_t status);

/** Callback invoked when the subrate change event for a given connection
 * is received */
typedef void (*subrate_change_callback)(int conn_id, uint16_t subrate_factor, uint16_t latency,
                                        uint16_t cont_num, uint16_t timeout, uint8_t subrate_mode,
                                        uint8_t status);

/** Callback invoked when the characteristics unoffloaded event for a given connection is received
 */
typedef void (*characteristics_unoffloaded_callback)(int conn_id, int session_id, uint8_t status);

typedef struct {
  register_server_callback register_server_cb;
  connection_callback connection_cb;
  service_added_callback service_added_cb;
  service_deleted_callback service_deleted_cb;
  request_read_callback request_read_characteristic_cb;
  request_read_callback request_read_descriptor_cb;
  request_write_callback request_write_characteristic_cb;
  request_write_callback request_write_descriptor_cb;
  request_exec_write_callback request_exec_write_cb;
  response_confirmation_callback response_confirmation_cb;
  indication_sent_callback indication_sent_cb;
  congestion_callback congestion_cb;
  mtu_changed_callback mtu_changed_cb;
  phy_updated_callback phy_updated_cb;
  conn_updated_callback conn_updated_cb;
  subrate_change_callback subrate_chg_cb;
  characteristics_unoffloaded_callback characteristics_unoffloaded_cb;
} btgatt_server_callbacks_t;

/** Represents the standard BT-GATT server interface. */
typedef struct {
  /** Registers a GATT server application with the stack */
  BtStatus (*register_server)(const bluetooth::Uuid& uuid, bool eatt_support);

  /** Unregister a server application from the stack */
  BtStatus (*unregister_server)(int server_if);

  /** Create a connection to a remote peripheral */
  BtStatus (*connect)(int server_if, const RawAddress& bd_addr, uint8_t addr_type, bool is_direct,
                      int transport);

  /** Disconnect an established connection or cancel a pending one */
  BtStatus (*disconnect)(int server_if, const RawAddress& bd_addr, int conn_id);

  /** Create a new service */
  BtStatus (*add_service)(int server_if, const btgatt_db_element_t* service, size_t service_count);

  /** Delete a local service */
  BtStatus (*delete_service)(int server_if, int service_handle);

  /** Send value indication to a remote device */
  BtStatus (*send_indication)(int server_if, int attribute_handle, int conn_id, int confirm,
                              const uint8_t* value, size_t length);

  /** Send a response to a read/write operation */
  BtStatus (*send_response)(int conn_id, int trans_id, int status,
                            const btgatt_response_t& response);

  BtStatus (*set_preferred_phy)(const RawAddress& bd_addr, uint8_t tx_phy, uint8_t rx_phy,
                                uint16_t phy_options);

  BtStatus (*read_phy)(const RawAddress& bd_addr,
                       base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb);

  /** Offload GATT characteristics */
  BtStatus (*offload_characteristics)(int conn_id, btgatt_db_element_t* service,
                                      size_t element_count, uint64_t endpoint_id, uint64_t hub_id,
                                      int uid, std::string attribution_tag,
                                      btgatt_offload_result_t* result);

  /** Unoffload GATT characteristics */
  BtStatus (*unoffload_characteristics)(int conn_id, int session_id);
} btgatt_server_interface_t;

__END_DECLS

#endif /* ANDROID_INCLUDE_BT_GATT_CLIENT_H */
