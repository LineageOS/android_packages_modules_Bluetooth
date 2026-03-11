/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/string_helpers.h>

#include <cstdint>
#include <memory>
#include <sstream>
#include <vector>

#include "ccp_types.h"
#include "hardware/bt_le_audio.h"
#include "stack/include/bt_types.h"
#include "stack/include/gatt_api.h"

namespace bluetooth {
namespace ccp {

// Base class for GATT service devices, holding common connection state.
class GattServiceDevice {
public:
  GattServiceDevice(const RawAddress& address) : addr(address) {}
  virtual ~GattServiceDevice() = default;

  bool IsConnected() const { return conn_id != GATT_INVALID_CONN_ID; }
  const RawAddress& GetAddress() const { return addr; }

  void DebugDump(std::stringstream& stream) const {
    stream << "  Device Address: " << addr.ToRedactedStringForLogging() << ", ConnID: " << conn_id
           << ", Service Found: " << (service_found ? "true" : "false");
  }

  RawAddress addr;
  uint16_t conn_id = GATT_INVALID_CONN_ID;
  bool service_found = false;
};

// Represents the state of a single connected device for the Call Control Profile.
class CcpDevice : public GattServiceDevice {
public:
  CcpDevice(const RawAddress& address) : GattServiceDevice(address) {}

  struct MatchAddress {
    MatchAddress(const RawAddress& address) : address(address) {}
    bool operator()(const std::shared_ptr<CcpDevice>& other) const {
      return address == other->addr;
    }
    RawAddress address;
  };

  struct MatchConnId {
    MatchConnId(uint16_t conn_id) : conn_id(conn_id) {}
    bool operator()(const std::shared_ptr<CcpDevice>& other) const {
      return conn_id == other->conn_id;
    }
    uint16_t conn_id;
  };

  void DebugDump(std::stringstream& stream) const;

  void ClearHandles() {
    service_found = false;
    bearer_provider_name_handle = 0;
    bearer_uci_handle = 0;
    bearer_technology_handle = 0;
    bearer_uri_schemes_supported_list_handle = 0;
    bearer_signal_strength_handle = 0;
    bearer_signal_strength_reporting_interval_handle = 0;
    bearer_list_current_calls_handle = 0;
    content_control_id_handle = 0;
    status_flags_handle = 0;
    incoming_call_target_bearer_uri_handle = 0;
    call_state_handle = 0;
    call_control_point_handle = 0;
    opcodes_supported_handle = 0;
    termination_reason_handle = 0;
    incoming_call_handle = 0;
    call_friendly_name_handle = 0;
  }

  // Characteristic handles specific to CCP/TBS
  uint16_t bearer_provider_name_handle = 0;
  uint16_t bearer_uci_handle = 0;
  uint16_t bearer_technology_handle = 0;
  uint16_t bearer_uri_schemes_supported_list_handle = 0;
  uint16_t bearer_signal_strength_handle = 0;
  uint16_t bearer_signal_strength_reporting_interval_handle = 0;
  uint16_t bearer_list_current_calls_handle = 0;
  uint16_t content_control_id_handle = 0;
  uint16_t status_flags_handle = 0;
  uint16_t incoming_call_target_bearer_uri_handle = 0;
  uint16_t call_state_handle = 0;
  uint16_t call_control_point_handle = 0;
  uint16_t opcodes_supported_handle = 0;
  uint16_t termination_reason_handle = 0;
  uint16_t incoming_call_handle = 0;
  uint16_t call_friendly_name_handle = 0;
};

// Interface for CCP Client callbacks to notify the upper layer (JNI -> Java Service)
class CcpClientCallbacks {
public:
  virtual ~CcpClientCallbacks() = default;
  virtual void OnConnectionState(const RawAddress& address, le_audio::ConnectionState state) = 0;
  virtual void OnDiscovered(const RawAddress& address) = 0;
  virtual void OnCallState(const RawAddress& address, const std::vector<Call>& calls) = 0;
  virtual void OnCallControlResult(const RawAddress& address, uint8_t opcode, uint8_t call_index,
                                   CallControlResultCode result) = 0;
  virtual void OnStatusFlags(const RawAddress& address, uint8_t flags) = 0;
  virtual void OnBearerProviderName(const RawAddress& address, const std::string& name) = 0;
  virtual void OnBearerTechnology(const RawAddress& address, uint8_t technology) = 0;
  virtual void OnOpcodesSupportedChanged(const RawAddress& address, uint32_t opcodes) = 0;
  virtual void OnBearerUriSchemesSupportedChanged(const RawAddress& address,
                                                  const std::string& schemes) = 0;
  virtual void OnTerminationReason(const RawAddress& address, uint8_t call_index,
                                   TerminationReasonCode reason_code) = 0;
  virtual void OnIncomingCall(const RawAddress& address, uint8_t call_index,
                              const std::string& uri) = 0;
  virtual void OnIncomingCallTarget(const RawAddress& address, uint8_t call_index,
                                    const std::string& uri) = 0;
  virtual void OnCallFriendlyName(const RawAddress& address, uint8_t call_index,
                                  const std::string& name) = 0;
  virtual void OnBearerSignalStrength(const RawAddress& address, uint8_t strength) = 0;
};

// Main interface for the CCP Client module.
class CcpClient {
public:
  // Initializes the CCP client singleton.
  static void Initialize(CcpClientCallbacks* callbacks, base::Closure initCb);
  // Cleans up the CCP client singleton.
  static void Cleanup();
  // Gets the singleton instance.
  static CcpClient* Get();
  // Dumps debugging information.
  static void DebugDump(int fd);

  // Initiates a connection to a remote device.
  virtual void Connect(const RawAddress& address) = 0;
  // Disconnects from a remote device.
  virtual void Disconnect(const RawAddress& address) = 0;

  // Sends CCP commands to the remote device.
  virtual void AcceptCall(const RawAddress& address, uint8_t call_index) = 0;
  virtual void TerminateCall(const RawAddress& address, uint8_t call_index) = 0;
  virtual void HoldCall(const RawAddress& address, uint8_t call_index) = 0;
  virtual void RetrieveCall(const RawAddress& address, uint8_t call_index) = 0;
  virtual void PlaceCall(const RawAddress& address, const std::string& uri) = 0;
  virtual void JoinCalls(const RawAddress& address, const std::vector<uint8_t>& call_indexes) = 0;

  virtual ~CcpClient() = default;
};

}  // namespace ccp
}  // namespace bluetooth
