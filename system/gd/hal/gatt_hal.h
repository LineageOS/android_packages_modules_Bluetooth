/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <bluetooth/types/uuid.h>

#include <cstdint>
#include <vector>

#include "hal/endpoint.h"

namespace bluetooth::hal {

enum GattStatus {
  GATT_SUCCESS = 0,
  GATT_INVALID_ENDPOINT_ID,
  GATT_UNSUPPORTED_ROLE,
  GATT_INSUFFICIENT_RESOURCES,
  GATT_FAILURE,
};

enum GattRole {
  GATT_SERVER = 0,
  GATT_CLIENT,
};

enum GattError {
  GATT_ERROR_UNKNOWN = 0,
  GATT_ERROR_DATABASE_OUT_OF_SYNC,
  GATT_ERROR_RESPONSE_TIMEOUT,
  GATT_ERROR_PROTOCOL_VIOLATION,
  GATT_ERROR_NONE,
};

struct GattCapabilities {
  /**
   * The supported properties of characteristic which contains a bit mask of property flags
   * indicating the features of this characteristic by GATT client.
   *
   * This field represents the supported properties of a Bluetooth Low Energy (BLE)
   * characteristic, encoded as a bit mask. Each bit corresponds to a specific
   * characteristic property flag, as defined in the Bluetooth Core Specification
   * Version 6.0, Volume 3, Part G, Section 3.3.1.1.
   *
   * If GATT client offload is supported, Notify (0x01) must be supported and set. Other
   * properties are optional.
   */
  int supported_gatt_client_properties;

  /**
   * The supported properties of characteristic which contains a bit mask of property flags
   * indicating the features of this characteristic by GATT server.
   *
   * This field represents the supported properties of a Bluetooth Low Energy (BLE)
   * characteristic, encoded as a bit mask. Each bit corresponds to a specific
   * characteristic property flag, as defined in the Bluetooth Core Specification
   * Version 6.0, Volume 3, Part G, Section 3.3.1.1.
   *
   * If GATT server offload is supported, Notify (0x01) must be supported and set. Other
   * properties are optional.
   */
  int supported_gatt_server_properties;
};

struct GattCharacteristic {
  // UUID of characteristic for attribute type.
  Uuid uuid;

  // This 8-bit value is used to define a set of the allowed operations.
  uint8_t properties;

  // Attribute handle of characteristic which is a 16-bit value.
  uint16_t value_handle;
};

struct GattSession {
  // Identifier assigned to the offload session by the host stack.
  int id;

  // ACL connection handle for the GATT connection.
  uint16_t acl_connection_handle;

  // Maximum Transmission Unit for ATT.
  uint16_t att_mtu;

  // GATT role for the GATT connection.
  GattRole role;

  // UUID of service provided by GATT server.
  Uuid service_uuid;

  // List of characteristics to be offloaded in this service.
  std::vector<GattCharacteristic> characteristics;

  // Endpoint information.
  EndpointInfo endpoint_info;
};

class GattHalCallback {
public:
  virtual ~GattHalCallback() = default;

  /**
   * Invoked when IBluetoothSocket.registerService() has completed.
   *
   * @param sessionId Identifier for the GATT session that was previously assigned when the service
   * was offloaded
   * @param status Status indicating success or failure
   */
  virtual void registerServiceComplete(uint16_t session_id, GattStatus status) const = 0;

  /**
   * Invoked when IBluetoothSocket.unregisterService() has completed.
   *
   * @param sessionId Identifier for the GATT session that was previously assigned when the service
   * was offloaded
   * @param status Status indicating success or failure
   */
  virtual void unregisterServiceComplete(uint16_t session_id) const = 0;

  /**
   * Invoked when IBluetoothSocket.clearService() has completed.
   *
   * @param acl_connection_handle ACL connection handle
   */
  virtual void clearServicesComplete(uint16_t acl_connection_handle) const = 0;

  /**
   * Invoked when offload app or stack notifies host stack that a error has
   * occurred on the GATT connection. Host stack is responsible for handling the error
   * appropriately based on the type of error.
   *
   * @param acl_connection_handle ACL connection handle
   * @param local_cid L2cap local channel ID
   * @param error The reported error
   */
  virtual void errorReport(uint16_t acl_connection_handle, uint16_t local_cid,
                           GattError error) const = 0;
};

class GattHal {
public:
  virtual ~GattHal() = default;

  /**
   * Initializes GATT hal and registers a callback function to receive asynchronous events from GATT
   * HAL.
   *
   * @param callback A pointer to the callback function. Must not be nullptr and must have static
   * lifetime.
   * @return True if the callback was successfully registered with initialization, false otherwise.
   */
  virtual bool Initialize(hal::GattHalCallback const* callback) = 0;

  /**
   * Retrieves the supported offloaded GATT capabilities.
   *
   * @return Supported GATT capabilities
   */
  virtual hal::GattCapabilities GetGattCapabilities() const = 0;

  /**
   * Registers the GATT service to the endpoint for GATT client or server.
   *
   * @param session GATT session including session ID, connection info, GATT role, GATT service, and
   * endpoint info
   * @return Result of calling this method
   */
  virtual bool RegisterService(const hal::GattSession& session) const = 0;

  /**
   * Unregisters a previously offloaded GATT offload session or signals its closure.
   *
   * @param session_id The unique identifier for the GATT session that was previously assigned when
   * the service was offloaded
   */
  virtual void UnregisterService(int session_id) const = 0;

  /**
   * Requests the offload stack to clear the GATT sessions for the selected ACL Connection.
   *
   * @param acl_connection_handle Handle of the selected ACL connection.
   */
  virtual void ClearServices(int acl_connection_handle) const = 0;
};

}  // namespace bluetooth::hal
