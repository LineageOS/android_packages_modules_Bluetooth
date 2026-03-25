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

#include "ccp_client.h"

#include <android_bluetooth_sysprop.h>
#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>

#include <list>
#include <memory>
#include <mutex>
#include <sstream>

#include "bta/include/bta_api.h"
#include "bta/include/bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "ccp/ccp_types.h"
#include "hardware/bt_le_audio.h"
#include "osi/include/properties.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/gatt_api.h"

using bluetooth::le_audio::ConnectionState;
using namespace bluetooth;
using namespace bluetooth::ccp;

namespace {
class CcpClientImpl;
extern std::unique_ptr<CcpClientImpl> instance;
std::mutex instance_mutex;

static constexpr std::size_t kCallEntrySize = 3;
static constexpr uint8_t kMaxCallState = 0x06;
static constexpr uint8_t kCallFlagsRfuMask = 0xF8;    // 11111000
static constexpr uint8_t kCallFlagsValidMask = 0x07;  // 00000111
static constexpr std::size_t kMinCallListItemHeaderSize = 4;
static constexpr std::size_t kMinCallListItemPayloadSize = 3;
/**
 * Overview:
 * This is the Call Control Profile client class. It handles GATT operations and
 * state for multiple connected Telephony Bearer Service (TBS) server devices.
 *
 * It is a singleton that registers a single GATT client application interface
 * and manages individual CcpDevice state objects for each connection.
 * All GATT events are received in a central callback and dispatched to the
 * appropriate CcpDevice instance.
 */
class CcpClientImpl : public CcpClient {
public:
  CcpClientImpl(CcpClientCallbacks* callbacks, base::Closure initCb) : callbacks_(callbacks) {
    BTA_GATTC_AppRegister(
            "ccp_client",
            [](tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
              if (instance && p_data) {
                instance->GattcCallback(event, p_data);
              }
            },
            base::Bind(
                    [](base::Closure initCb, uint8_t client_id, uint8_t status) {
                      if (status != GATT_SUCCESS) {
                        log::error("Failed to register CCP client app");
                        return;
                      }
                      if (instance) {
                        instance->gatt_if_ = client_id;
                      }
                      initCb.Run();
                    },
                    initCb),
            true);
  }

  ~CcpClientImpl() override = default;

  void Cleanup() {
    if (gatt_if_ != 0) {
      BTA_GATTC_AppDeregister(gatt_if_);
    }
    for (auto& device : devices_) {
      if (device->IsConnected()) {
        BTA_GATTC_Close(device->conn_id);
      }
      DoDisconnectCleanup(device);
    }

    devices_.clear();
  }

  void Connect(const RawAddress& address) override {
    log::info("{}", address);
    auto device = FindDevice(address);
    if (device) {
      log::warn("Connect requested for already tracked device {}", address);
      return;
    }
    if (!get_security_client_interface().BTM_IsBonded(address, BT_TRANSPORT_LE)) {
      log::error("Connecting {} when not bonded", address);
      callbacks_->OnConnectionState(address, ConnectionState::DISCONNECTED);
      return;
    }
    BTA_GATTC_Open(gatt_if_, address, BTM_BLE_OPPORTUNISTIC);
  }

  void Disconnect(const RawAddress& address) override {
    log::info("{}", address);
    auto device = FindDevice(address);
    if (device == nullptr) {
      log::warn("Device not connected to profile {}", address);
      callbacks_->OnConnectionState(address, ConnectionState::DISCONNECTED);
      return;
    }
    if (device->IsConnected()) {
      BTA_GATTC_Close(device->conn_id);
    } else {
      BTA_GATTC_CancelOpen(gatt_if_, address, false);
      DoDisconnectCleanup(device);
      callbacks_->OnConnectionState(address, ConnectionState::DISCONNECTED);
    }
  }

  void AcceptCall(const RawAddress& address, uint8_t call_index) override {
    WriteCallControlPoint(address, kCallControlPointOpcodeAcceptCall, {call_index});
  }

  void TerminateCall(const RawAddress& address, uint8_t call_index) override {
    WriteCallControlPoint(address, kCallControlPointOpcodeTerminateCall, {call_index});
  }

  void HoldCall(const RawAddress& address, uint8_t call_index) override {
    WriteCallControlPoint(address, kCallControlPointOpcodeHoldCall, {call_index});
  }

  void RetrieveCall(const RawAddress& address, uint8_t call_index) override {
    WriteCallControlPoint(address, kCallControlPointOpcodeRetrieveCall, {call_index});
  }

  void PlaceCall(const RawAddress& address, const std::string& uri) override {
    std::vector<uint8_t> params(uri.begin(), uri.end());
    WriteCallControlPoint(address, kCallControlPointOpcodePlaceCall, params);
  }

  void JoinCalls(const RawAddress& address, const std::vector<uint8_t>& call_indexes) override {
    WriteCallControlPoint(address, kCallControlPointOpcodeJoinCalls, call_indexes);
  }

  void DebugDump(int fd) {
    std::stringstream stream;
    stream << "CcpClient:\n";
    for (const auto& device : devices_) {
      device->DebugDump(stream);
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  void GattcCallback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
    log::verbose("event: {}", gatt_client_event_text(event));

    switch (event) {
      case BTA_GATTC_OPEN_EVT:
        OnGattConnected(p_data->open);
        break;
      case BTA_GATTC_CLOSE_EVT:
        OnGattDisconnected(p_data->close);
        break;
      case BTA_GATTC_SEARCH_CMPL_EVT:
        OnSearchComplete(p_data->search_cmpl);
        break;
      case BTA_GATTC_NOTIF_EVT:
        OnNotification(p_data->notify);
        break;
      case BTA_GATTC_ENC_CMPL_CB_EVT:
        OnEncryptionComplete(p_data->enc_cmpl.remote_bda,
                             get_security_client_interface().BTM_IsEncrypted(
                                     p_data->enc_cmpl.remote_bda, BT_TRANSPORT_LE));
        break;
      case BTA_GATTC_SRVC_CHG_EVT:
        OnServiceChangeEvent(p_data->service_changed.remote_bda);
        break;
      case BTA_GATTC_SRVC_DISC_DONE_EVT:
        OnServiceDiscoveryDoneEvent(p_data->service_discovery_done.remote_bda);
        break;
      default:
        break;
    }
  }

  static void OnGattReadStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                               uint8_t* value, void* data) {
    if (instance) {
      instance->OnCharacteristicRead(conn_id, status, handle, len, value, data);
    }
  }

private:
  static constexpr uint16_t kInvalidGattHandle = 0x0000;

  void OnGattConnected(const tBTA_GATTC_OPEN& evt) {
    log::info("Connected to {}, conn_id {}", evt.remote_bda, evt.conn_id);

    if (evt.status != GATT_SUCCESS) {
      log::error("Connect failed for {}: {}", evt.remote_bda, gatt_status_text(evt.status));
      callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::DISCONNECTED);
      return;
    }

    auto device = FindDevice(evt.remote_bda);
    if (device) {
      log::warn("Device {} already tracked, updating conn_id {}", evt.remote_bda, evt.conn_id);
      device->conn_id = evt.conn_id;
    } else {
      log::info("Adding new CcpDevice for {}", evt.remote_bda);
      device = std::make_shared<CcpDevice>(evt.remote_bda);
      device->conn_id = evt.conn_id;
      devices_.emplace_back(device);
    }
    callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::CONNECTED);

    if (get_security_client_interface().BTM_IsEncrypted(device->addr, BT_TRANSPORT_LE)) {
      OnEncryptionComplete(device->addr, true);
    } else {
      tBTM_STATUS result = get_security_client_interface().BTM_SetEncryption(
              device->addr, BT_TRANSPORT_LE, nullptr, nullptr, BTM_BLE_SEC_ENCRYPT);

      log::info("Encryption required for {}. Request result: 0x{:02x}", device->addr, result);

      if (result == tBTM_STATUS::BTM_ERR_KEY_MISSING) {
        log::error("Link key unknown for {}, disconnect profile", device->addr);
        BTA_GATTC_Close(device->conn_id);
      }
    }
  }

  void OnGattDisconnected(const tBTA_GATTC_CLOSE& evt) {
    log::info("Disconnected from {}", evt.remote_bda);
    auto device = FindDevice(evt.remote_bda);
    if (!device) {
      log::warn("Disconnected from untracked device {}", evt.remote_bda);
      return;
    }

    DoDisconnectCleanup(device);
    devices_.remove(device);
    callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::DISCONNECTED);
  }

  void OnEncryptionComplete(const RawAddress& bda, bool success) {
    auto device = FindDevice(bda);
    if (!device) {
      log::warn("Unknown device for encryption completion: {}", bda);
      return;
    }

    if (!success) {
      log::error("Encryption failed for {}", device->addr);
      BTA_GATTC_Close(device->conn_id);
      return;
    }

    log::info("Encryption complete for {}", device->addr);
    if (device->service_found) {
      log::debug("Service already discovered, re-registering notifications for {}", device->addr);
      RegisterForNotifications(device);
    } else {
      log::debug("Initiating service search for {}", device->addr);
      BTA_GATTC_ServiceSearchRequest(device->conn_id);
    }
  }

  void OnServiceChangeEvent(const RawAddress& bda) {
    auto device = FindDevice(bda);
    if (!device) {
      return;
    }

    log::info("Service changed for {}", device->addr);
    device->ClearHandles();
    BTA_GATTC_ServiceSearchRequest(device->conn_id);
  }

  void OnServiceDiscoveryDoneEvent(const RawAddress& bda) {
    auto device = FindDevice(bda);
    if (!device) {
      return;
    }

    log::info("Service discovery done for {}", device->addr);

    if (!device->service_found) {
      BTA_GATTC_ServiceSearchRequest(device->conn_id);
    }
  }

  void OnSearchComplete(const tBTA_GATTC_SEARCH_CMPL& evt) {
    auto device = FindDevice(evt.conn_id);
    if (!device) {
      return;
    }

    if (evt.status != GATT_SUCCESS) {
      log::error("Service search failed for device {}: {}", device->addr,
                 gatt_status_text(evt.status));
      BTA_GATTC_Close(device->conn_id);
      return;
    }

    const std::list<gatt::Service>* services = BTA_GATTC_GetServices(device->conn_id);
    if (!services) {
      log::error("No services found for device {}", device->addr);
      BTA_GATTC_Close(device->conn_id);
      return;
    }

    device->ClearHandles();

    for (const auto& service : *services) {
      if (service.uuid != kGenericTelephonyBearerServiceUuid) {
        continue;
      }
      log::info("Found TBS service on {}. Discovering characteristics...", device->addr);
      device->service_found = true;
      for (const auto& chrc : service.characteristics) {
        if (chrc.uuid == kBearerProviderNameUuid) {
          device->bearer_provider_name_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerUciUuid) {
          device->bearer_uci_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerTechnologyUuid) {
          device->bearer_technology_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerUriSchemesSupportedListUuid) {
          device->bearer_uri_schemes_supported_list_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerSignalStrengthUuid) {
          device->bearer_signal_strength_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerSignalStrengthReportingIntervalUuid) {
          device->bearer_signal_strength_reporting_interval_handle = chrc.value_handle;
        } else if (chrc.uuid == kBearerListCurrentCallsUuid) {
          device->bearer_list_current_calls_handle = chrc.value_handle;
        } else if (chrc.uuid == kContentControlIdUuid) {
          device->content_control_id_handle = chrc.value_handle;
        } else if (chrc.uuid == kStatusFlagsUuid) {
          device->status_flags_handle = chrc.value_handle;
        } else if (chrc.uuid == kIncomingCallTargetBearerUriUuid) {
          device->incoming_call_target_bearer_uri_handle = chrc.value_handle;
        } else if (chrc.uuid == kCallStateUuid) {
          device->call_state_handle = chrc.value_handle;
        } else if (chrc.uuid == kCallControlPointUuid) {
          device->call_control_point_handle = chrc.value_handle;
        } else if (chrc.uuid == kCallControlPointOptionalOpcodesUuid) {
          device->opcodes_supported_handle = chrc.value_handle;
        } else if (chrc.uuid == kTerminationReasonUuid) {
          device->termination_reason_handle = chrc.value_handle;
        } else if (chrc.uuid == kIncomingCallUuid) {
          device->incoming_call_handle = chrc.value_handle;
        } else if (chrc.uuid == kCallFriendlyNameUuid) {
          device->call_friendly_name_handle = chrc.value_handle;
        }
      }
    }

    if (device->service_found) {
      if (device->call_state_handle == 0 || device->call_control_point_handle == 0) {
        log::error("Mandatory TBS characteristics not found on {}", device->addr);
        BTA_GATTC_Close(device->conn_id);
        return;
      }
      callbacks_->OnDiscovered(device->addr);
      RegisterForNotifications(device);
      ReadInitialState(device);
    } else {
      log::error("TBS service not found on device {}", device->addr);
      BTA_GATTC_Close(device->conn_id);
    }
  }

  void DeregisterNotifications(const std::shared_ptr<CcpDevice>& device) {
    if (device->bearer_provider_name_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->bearer_provider_name_handle);
    }
    if (device->bearer_technology_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->bearer_technology_handle);
    }
    if (device->bearer_uri_schemes_supported_list_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->bearer_uri_schemes_supported_list_handle);
    }
    if (device->bearer_signal_strength_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->bearer_signal_strength_handle);
    }
    if (device->bearer_list_current_calls_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->bearer_list_current_calls_handle);
    }
    if (device->status_flags_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->status_flags_handle);
    }
    if (device->incoming_call_target_bearer_uri_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->incoming_call_target_bearer_uri_handle);
    }
    if (device->call_state_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->call_state_handle);
    }
    if (device->call_control_point_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->call_control_point_handle);
    }
    if (device->termination_reason_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->termination_reason_handle);
    }
    if (device->incoming_call_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->incoming_call_handle);
    }
    if (device->call_friendly_name_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->call_friendly_name_handle);
    }
  }

  /* Cleans up after the device disconnection */
  void DoDisconnectCleanup(const std::shared_ptr<CcpDevice>& device) {
    log::debug("{}", device->addr);

    DeregisterNotifications(device);

    if (device->IsConnected()) {
      BtaGattQueue::Clean(device->conn_id);
      device->conn_id = GATT_INVALID_CONN_ID;
    }
  }

  void RegisterForNotifications(const std::shared_ptr<CcpDevice>& device) {
    if (device->bearer_provider_name_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->bearer_provider_name_handle);
    }
    if (device->bearer_technology_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->bearer_technology_handle);
    }
    if (device->bearer_uri_schemes_supported_list_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->bearer_uri_schemes_supported_list_handle);
    }
    if (device->bearer_signal_strength_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->bearer_signal_strength_handle);
    }
    if (device->bearer_list_current_calls_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->bearer_list_current_calls_handle);
    }
    if (device->status_flags_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->status_flags_handle);
    }
    if (device->incoming_call_target_bearer_uri_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->incoming_call_target_bearer_uri_handle);
    }
    if (device->call_state_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->call_state_handle);
    }
    if (device->call_control_point_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->call_control_point_handle);
    }
    if (device->termination_reason_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->termination_reason_handle);
    }
    if (device->incoming_call_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->incoming_call_handle);
    }
    if (device->call_friendly_name_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->call_friendly_name_handle);
    }
  }

  void ParseCcpIndication(const std::shared_ptr<CcpDevice>& device, const tBTA_GATTC_NOTIFY& evt) {
    if (evt.handle == device->call_control_point_handle) {
      if (evt.len < 3) {
        log::error("Invalid CCP indication length: {}", evt.len);
        return;
      }
      uint8_t request_opcode = evt.value[0];
      uint8_t call_index = evt.value[1];
      auto result_code = static_cast<CallControlResultCode>(evt.value[2]);
      callbacks_->OnCallControlResult(device->addr, request_opcode, call_index, result_code);
    }
  }

  void ParseCallState(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                      uint16_t len) {
    const uint8_t* p = value;
    const uint8_t* end = value + len;
    std::vector<Call> new_calls;

    while (static_cast<std::size_t>(end - p) >= kCallEntrySize) {
      Call call;
      STREAM_TO_UINT8(call.index, p);
      STREAM_TO_UINT8(call.state, p);
      STREAM_TO_UINT8(call.flags, p);

      if (call.state > kMaxCallState) {
        log::warn("Ignoring call with invalid state: {}", call.state);
        continue;
      }

      if ((call.flags & kCallFlagsRfuMask) != 0) {
        log::warn("Received call with RFU flags set: {:#x}", call.flags);
        call.flags &= kCallFlagsValidMask;
      }

      new_calls.push_back(call);
    }

    if (p != end) {
      log::error("Malformed call state data for {}, remaining len={}", device->addr,
                 static_cast<long>(end - p));
    }

    callbacks_->OnCallState(device->addr, std::move(new_calls));
  }

  void ParseStatusFlags(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                        uint16_t len) {
    if (len != 1) {
      log::error("Invalid Status Flags notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnStatusFlags(device->addr, value[0]);
  }

  void ParseBearerProviderName(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                               uint16_t len) {
    std::string name(reinterpret_cast<const char*>(value), len);
    callbacks_->OnBearerProviderName(device->addr, name);
  }

  void ParseBearerTechnology(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                             uint16_t len) {
    if (len != 1) {
      log::error("Invalid Bearer Technology notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    callbacks_->OnBearerTechnology(device->addr, value[0]);
  }

  void ParseBearerListCurrentCalls(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                                   uint16_t len) {
    const uint8_t* p = value;
    const uint8_t* end = value + len;
    std::vector<Call> new_calls;

    while (static_cast<std::size_t>(end - p) >= kMinCallListItemHeaderSize) {
      const uint8_t* item_start = p;

      uint8_t item_len;
      STREAM_TO_UINT8(item_len, p);

      if (item_len < kMinCallListItemPayloadSize) {
        log::error("Malformed Bearer List item: item_len={} is too small", item_len);
        break;
      }

      if (static_cast<std::size_t>(end - p) < item_len) {
        log::error("Malformed Bearer List item: item_len={} but only {} bytes remaining", item_len,
                   static_cast<long>(end - p));
        break;
      }

      const uint8_t* item_end = item_start + 1 + item_len;
      Call call;
      STREAM_TO_UINT8(call.index, p);
      STREAM_TO_UINT8(call.state, p);
      STREAM_TO_UINT8(call.flags, p);

      call.uri = std::string(reinterpret_cast<const char*>(p), item_end - p);
      new_calls.push_back(call);

      p = item_end;
    }

    if (p != end) {
      log::error("Malformed Bearer List: trailing data of {} bytes", static_cast<long>(end - p));
    }

    callbacks_->OnCallState(device->addr, std::move(new_calls));
  }

  void ParseTerminationReason(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                              uint16_t len) {
    if (len != 2) {
      log::error("Invalid Termination Reason notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    callbacks_->OnTerminationReason(device->addr, value[0],
                                    static_cast<TerminationReasonCode>(value[1]));
  }

  void ParseIncomingCall(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                         uint16_t len) {
    if (len < 1) {
      log::error("Invalid Incoming Call notification from device: {}, len: {}", device->addr, len);
      return;
    }
    const uint8_t* p = value;
    uint8_t call_index;
    STREAM_TO_UINT8(call_index, p);
    std::string uri(reinterpret_cast<const char*>(p), len - 1);
    callbacks_->OnIncomingCall(device->addr, call_index, uri);
  }

  void ParseIncomingCallTarget(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                               uint16_t len) {
    if (len < 1) {
      log::error("Invalid Incoming Call Target notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    const uint8_t* p = value;
    uint8_t call_index;
    STREAM_TO_UINT8(call_index, p);
    std::string uri(reinterpret_cast<const char*>(p), len - 1);
    callbacks_->OnIncomingCallTarget(device->addr, call_index, uri);
  }

  void ParseCallFriendlyName(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                             uint16_t len) {
    if (len < 1) {
      log::error("Invalid Call Friendly Name notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    const uint8_t* p = value;
    uint8_t call_index;
    STREAM_TO_UINT8(call_index, p);
    std::string name(reinterpret_cast<const char*>(p), len - 1);
    callbacks_->OnCallFriendlyName(device->addr, call_index, name);
  }

  void ParseBearerSignalStrength(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                                 uint16_t len) {
    if (len != 1) {
      log::error("Invalid Bearer Signal Strength notification from device: {}, len: {}",
                 device->addr, len);
      return;
    }
    callbacks_->OnBearerSignalStrength(device->addr, value[0]);
  }

  void ParseOpcodesSupported(const std::shared_ptr<CcpDevice>& device, const uint8_t* value,
                             uint16_t len) {
    if (len != 4) {
      log::error("Invalid Opcodes Supported notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    uint32_t opcodes;
    const uint8_t* p = value;
    STREAM_TO_UINT32(opcodes, p);
    callbacks_->OnOpcodesSupportedChanged(device->addr, opcodes);
  }

  void ParseBearerUriSchemesSupported(const std::shared_ptr<CcpDevice>& device,
                                      const uint8_t* value, uint16_t len) {
    std::string schemes(reinterpret_cast<const char*>(value), len);
    callbacks_->OnBearerUriSchemesSupportedChanged(device->addr, schemes);
  }

  void OnNotification(const tBTA_GATTC_NOTIFY& evt) {
    auto device = FindDevice(evt.conn_id);
    if (!device) {
      return;
    }

    if (!evt.is_notify) {
      BTA_GATTC_SendIndConfirm(device->conn_id, evt.cid);
      ParseCcpIndication(device, evt);
      return;
    }

    if (evt.handle == device->call_state_handle) {
      ParseCallState(device, evt.value, evt.len);
    } else if (evt.handle == device->status_flags_handle) {
      ParseStatusFlags(device, evt.value, evt.len);
    } else if (evt.handle == device->bearer_provider_name_handle) {
      ParseBearerProviderName(device, evt.value, evt.len);
    } else if (evt.handle == device->bearer_technology_handle) {
      ParseBearerTechnology(device, evt.value, evt.len);
    } else if (evt.handle == device->bearer_signal_strength_handle) {
      ParseBearerSignalStrength(device, evt.value, evt.len);
    } else if (evt.handle == device->bearer_list_current_calls_handle) {
      ParseBearerListCurrentCalls(device, evt.value, evt.len);
    } else if (evt.handle == device->termination_reason_handle) {
      ParseTerminationReason(device, evt.value, evt.len);
    } else if (evt.handle == device->incoming_call_handle) {
      ParseIncomingCall(device, evt.value, evt.len);
    } else if (evt.handle == device->incoming_call_target_bearer_uri_handle) {
      ParseIncomingCallTarget(device, evt.value, evt.len);
    } else if (evt.handle == device->call_friendly_name_handle) {
      ParseCallFriendlyName(device, evt.value, evt.len);
    } else if (evt.handle == device->bearer_uri_schemes_supported_list_handle) {
      ParseBearerUriSchemesSupported(device, evt.value, evt.len);
    } else {
      log::warn("Unhandled notification on handle 0x{:04x}", evt.handle);
    }
  }

  void OnCharacteristicRead(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                            uint8_t* value, void* /*data*/) {
    auto device = FindDevice(conn_id);
    if (!device) {
      return;
    }

    if (status != GATT_SUCCESS) {
      log::warn("Read failed for handle 0x{:04x}, status {}", handle, gatt_status_text(status));
      return;
    }

    if (handle == device->bearer_provider_name_handle) {
      ParseBearerProviderName(device, value, len);
    } else if (handle == device->bearer_technology_handle) {
      ParseBearerTechnology(device, value, len);
    } else if (handle == device->bearer_signal_strength_handle) {
      ParseBearerSignalStrength(device, value, len);
    } else if (handle == device->bearer_list_current_calls_handle) {
      ParseBearerListCurrentCalls(device, value, len);
    } else if (handle == device->status_flags_handle) {
      ParseStatusFlags(device, value, len);
    } else if (handle == device->opcodes_supported_handle) {
      ParseOpcodesSupported(device, value, len);
    } else if (handle == device->bearer_uri_schemes_supported_list_handle) {
      ParseBearerUriSchemesSupported(device, value, len);
    } else if (handle == device->incoming_call_target_bearer_uri_handle) {
      ParseIncomingCallTarget(device, value, len);
    }
  }

  void ReadInitialState(const std::shared_ptr<CcpDevice>& device) {
    if (device->bearer_provider_name_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->bearer_provider_name_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->bearer_technology_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->bearer_technology_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->bearer_signal_strength_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->bearer_signal_strength_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->bearer_list_current_calls_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->bearer_list_current_calls_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->status_flags_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->status_flags_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->opcodes_supported_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->opcodes_supported_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->bearer_uri_schemes_supported_list_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id,
                                       device->bearer_uri_schemes_supported_list_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->incoming_call_target_bearer_uri_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id,
                                       device->incoming_call_target_bearer_uri_handle,
                                       OnGattReadStatic, nullptr);
    }
  }

  void WriteCallControlPoint(const RawAddress& address, uint8_t opcode,
                             const std::vector<uint8_t>& params) {
    auto device = FindDevice(address);
    if (!device) {
      log::error("No device for address {}", address);
      return;
    }
    if (device->call_control_point_handle == 0) {
      log::error("No Call Control Point handle for device {}", address);
      return;
    }
    log::info("Writing to CCP on {}, opcode {:#04x}", address, opcode);
    std::vector<uint8_t> value_to_write;
    value_to_write.push_back(opcode);
    value_to_write.insert(value_to_write.end(), params.begin(), params.end());
    BtaGattQueue::WriteCharacteristic(device->conn_id, device->call_control_point_handle,
                                      value_to_write, GATT_WRITE_NO_RSP, nullptr, nullptr);
  }

  std::shared_ptr<CcpDevice> FindDevice(const RawAddress& address) {
    auto it = std::find_if(devices_.begin(), devices_.end(), CcpDevice::MatchAddress(address));
    return (it == devices_.end()) ? nullptr : *it;
  }

  std::shared_ptr<CcpDevice> FindDevice(uint16_t conn_id) {
    auto it = std::find_if(devices_.begin(), devices_.end(), CcpDevice::MatchConnId(conn_id));
    return (it == devices_.end()) ? nullptr : *it;
  }

private:
  CcpClientCallbacks* callbacks_;
  tGATT_IF gatt_if_ = 0;
  std::list<std::shared_ptr<CcpDevice>> devices_;
};

std::unique_ptr<CcpClientImpl> instance = nullptr;

}  // namespace

// --- CcpClient static methods ---
void CcpClient::Initialize(CcpClientCallbacks* callbacks, base::Closure initCb) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }
  instance = std::make_unique<CcpClientImpl>(callbacks, initCb);
}

void CcpClient::Cleanup() {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    return;
  }
  instance->Cleanup();
  instance = nullptr;
}

CcpClient* CcpClient::Get() { return instance.get(); }

void CcpDevice::DebugDump(std::stringstream& stream) const {
  GattServiceDevice::DebugDump(stream);

  stream << "\n    Bearer Provider Name Handle: "
         << bluetooth::common::ToHexString(bearer_provider_name_handle)
         << "\n    Bearer UCI Handle: " << bluetooth::common::ToHexString(bearer_uci_handle)
         << "\n    Bearer Technology Handle: "
         << bluetooth::common::ToHexString(bearer_technology_handle)
         << "\n    Bearer URI Schemes Supported Handle: "
         << bluetooth::common::ToHexString(bearer_uri_schemes_supported_list_handle)
         << "\n    Bearer Signal Strength Handle: "
         << bluetooth::common::ToHexString(bearer_signal_strength_handle)
         << "\n    Bearer Signal Strength Reporting Interval Handle: "
         << bluetooth::common::ToHexString(bearer_signal_strength_reporting_interval_handle)
         << "\n    Bearer List Current Calls Handle: "
         << bluetooth::common::ToHexString(bearer_list_current_calls_handle)
         << "\n    Content Control ID Handle: "
         << bluetooth::common::ToHexString(content_control_id_handle)
         << "\n    Status Flags Handle: " << bluetooth::common::ToHexString(status_flags_handle)
         << "\n    Incoming Call Target Bearer URI Handle: "
         << bluetooth::common::ToHexString(incoming_call_target_bearer_uri_handle)
         << "\n    Call State Handle: " << bluetooth::common::ToHexString(call_state_handle)
         << "\n    Call Control Point Handle: "
         << bluetooth::common::ToHexString(call_control_point_handle)
         << "\n    Optional Opcodes Handle: "
         << bluetooth::common::ToHexString(opcodes_supported_handle)
         << "\n    Termination Reason Handle: "
         << bluetooth::common::ToHexString(termination_reason_handle)
         << "\n    Incoming Call Handle: " << bluetooth::common::ToHexString(incoming_call_handle)
         << "\n    Call Friendly Name Handle: "
         << bluetooth::common::ToHexString(call_friendly_name_handle) << "\n";
}
