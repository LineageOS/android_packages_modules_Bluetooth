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

#include "mcp/mcp_client.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <list>
#include <memory>
#include <mutex>
#include <sstream>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_mcp_client_api.h"
#include "bta_gatt_queue.h"
#include "mcp/mcp_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/gatt_api.h"

using namespace bluetooth;
using namespace bluetooth::mcp;

namespace {
class McpClientImpl;
extern std::unique_ptr<McpClientImpl> instance;
constexpr int kGmcsServiceId = 0;
std::mutex instance_mutex;

/**
 * Overview:
 * This is the Media Control Profile client class. It handles GATT operations and
 * state for multiple connected Media Control Service (MCS) server devices.
 *
 * It is a singleton that registers a single GATT client application interface
 * and manages individual CcpDevice state objects for each connection.
 * All GATT events are received in a central callback and dispatched to the
 * appropriate CcpDevice instance.
 */

class McpClientImpl : public McpClient {
public:
  McpClientImpl(McpClientCallbacks* callbacks, base::OnceClosure initCb) : callbacks_(callbacks) {
    BTA_GATTC_AppRegister(
            "mcp_client",
            [](tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
              if (instance && p_data) {
                instance->GattcCallback(event, p_data);
              }
            },
            base::BindOnce(
                    [](base::OnceClosure initCb, uint8_t client_id, uint8_t status) {
                      if (status != GATT_SUCCESS) {
                        log::error("Failed to register MCP client app");
                        return;
                      }
                      if (instance) {
                        instance->gatt_if_ = client_id;
                      }
                      std::move(initCb).Run();
                    },
                    std::move(initCb)),
            true);
  }

  ~McpClientImpl() override = default;

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
    if (com_android_bluetooth_flags_leaudio_peripheral_mcp_link_abstraction_layer()) {
      StartOpportunisticConnect(address);
    } else {
      BTA_GATTC_Open(gatt_if_, address, BTM_BLE_OPPORTUNISTIC);
    }
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

  void AddFromStorage(const RawAddress& address) {
    log::info("{}", address);
    StartOpportunisticConnect(address);
  }

  void Play(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodePlay);
  }

  void Pause(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodePause);
  }

  void Stop(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodeStop);
  }

  void NextTrack(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodeNextTrack);
  }

  void PreviousTrack(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodePreviousTrack);
  }

  void FastRewind(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodeFastRewind);
  }

  void FastForward(const RawAddress& address, int service_id) override {
    WriteMediaControlPoint(address, service_id, kMcpOpcodeFastForward);
  }

  void MoveRelative(const RawAddress& address, int service_id, int32_t offset) override {
    std::vector<uint8_t> value(4);
    uint8_t* ptr = value.data();
    UINT32_TO_STREAM(ptr, offset);
    WriteMediaControlPoint(address, service_id, kMcpOpcodeMoveRelative, value);
  }

  void SetTrackPosition(const RawAddress& address, int service_id, int32_t position) override {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected()) {
      log::error("Device not ready for SetTrackPosition: {}", address);
      return;
    }
    auto service = device->GetService(service_id);
    if (!service || service->track_position_handle == kInvalidGattHandle) {
      log::error("Service not ready for SetTrackPosition: {}", address);
      return;
    }
    std::vector<uint8_t> value(kTrackPositionLen);
    uint8_t* ptr = value.data();
    UINT32_TO_STREAM(ptr, position);
    BtaGattQueue::WriteCharacteristic(device->conn_id, service->track_position_handle, value,
                                      GATT_WRITE_NO_RSP, nullptr, nullptr);
  }

  void SetPlaybackSpeed(const RawAddress& address, int service_id, int8_t speed) override {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected()) {
      log::error("Device not ready for SetPlaybackSpeed: {}", address);
      return;
    }
    auto service = device->GetService(service_id);
    if (!service || service->playback_speed_handle == kInvalidGattHandle) {
      log::error("Service not ready for SetPlaybackSpeed: {}", address);
      return;
    }
    std::vector<uint8_t> value(kPlaybackSpeedLen);
    value[kPlaybackSpeedIndex] = static_cast<uint8_t>(speed);
    BtaGattQueue::WriteCharacteristic(device->conn_id, service->playback_speed_handle, value,
                                      GATT_WRITE_NO_RSP, nullptr, nullptr);
  }

  void SetPlayingOrder(const RawAddress& address, int service_id,
                       PlayingOrder playing_order) override {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected()) {
      log::error("Device not ready for SetPlayingOrder: {}", address);
      return;
    }
    auto service = device->GetService(service_id);
    if (!service || service->playing_order_handle == kInvalidGattHandle) {
      log::error("Service not ready for SetPlayingOrder: {}", address);
      return;
    }
    std::vector<uint8_t> value(kPlayingOrderLen);
    value[kPlayingOrderIndex] = static_cast<uint8_t>(playing_order);
    BtaGattQueue::WriteCharacteristic(device->conn_id, service->playing_order_handle, value,
                                      GATT_WRITE_NO_RSP, nullptr, nullptr);
  }

  void DebugDump(int fd) {
    std::stringstream stream;
    stream << "McpClient:\n";
    for (const auto& device : devices_) {
      device->DebugDump(stream);
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  // GATT event handlers
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

  void OnCharacteristicRead(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                            uint8_t* value, void* /*data*/) {
    auto device = FindDevice(conn_id);
    if (!device) {
      return;
    }

    auto service = device->GetServiceByHandle(handle);
    if (!service) {
      return;
    }

    if (status != GATT_SUCCESS) {
      log::warn("Read failed for handle 0x{:04x}, status {}", handle, gatt_status_text(status));
      return;
    }

    if (handle == service->media_player_name_handle) {
      ParseMediaPlayerNameNotification(device, *service, value, len);
    } else if (handle == service->track_title_handle) {
      ParseTrackTitleNotification(device, *service, value, len);
    } else if (handle == service->track_duration_handle) {
      ParseTrackDurationNotification(device, *service, value, len);
    } else if (handle == service->track_position_handle) {
      ParseTrackPositionNotification(device, *service, value, len);
    } else if (handle == service->media_state_handle) {
      ParseMediaStateNotification(device, *service, value, len);
    } else if (handle == service->opcodes_supported_handle) {
      ParseOpcodesSupported(device, *service, value, len);
    } else if (handle == service->playing_order_handle) {
      ParsePlayingOrderNotification(device, *service, value, len);
    } else if (handle == service->playing_orders_supported_handle) {
      ParsePlayingOrdersSupported(device, *service, value, len);
    } else if (handle == service->playback_speed_handle) {
      ParsePlaybackSpeedNotification(device, *service, value, len);
    } else if (handle == service->seeking_speed_handle) {
      ParseSeekingSpeedNotification(device, *service, value, len);
    }
  }

  static void OnGattReadStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                               uint8_t* value, void* data) {
    if (instance) {
      instance->OnCharacteristicRead(conn_id, status, handle, len, value, data);
    }
  }

private:
  void StartOpportunisticConnect(const RawAddress& address) {
    if (!com_android_bluetooth_flags_leaudio_peripheral_mcp_link_abstraction_layer()) {
      return;
    }
    log::info("{}", address);
    BTA_GATTC_Open(gatt_if_, address, BTM_BLE_OPPORTUNISTIC);
  }

  void OnGattConnected(const tBTA_GATTC_OPEN& evt) {
    log::info("Connected to {}, conn_id {}", evt.remote_bda, evt.conn_id);

    if (evt.status != GATT_SUCCESS) {
      log::error("Connect failed for {}: {}", evt.remote_bda, gatt_status_text(evt.status));
      callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::DISCONNECTED);
      StartOpportunisticConnect(evt.remote_bda);
      return;
    }

    auto device = FindDevice(evt.remote_bda);
    if (device) {
      log::warn("Device {} already tracked, updating conn_id {}", evt.remote_bda, evt.conn_id);
      device->conn_id = evt.conn_id;
    } else {
      log::info("Adding new McpDevice for {}", evt.remote_bda);
      device = std::make_shared<McpDevice>(evt.remote_bda);
      device->conn_id = evt.conn_id;
      devices_.emplace_back(device);
    }
    callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::CONNECTED);

    if (get_security_client_interface().BTM_IsEncrypted(device->addr, BT_TRANSPORT_LE)) {
      OnEncryptionComplete(device->addr, true);
    } else {
      tBTM_STATUS result = get_security_client_interface().BTM_SetEncryption(
              device->addr, BT_TRANSPORT_LE, nullptr, nullptr, BTM_BLE_SEC_ENCRYPT);

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
    StartOpportunisticConnect(evt.remote_bda);
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
      if (com::android::bluetooth::flags::leaudio_peripheral_mcp_link_abstraction_layer()) {
        ReadInitialState(device);
      }
    } else {
      log::debug("Initiating service search for {}", device->addr);
      device->ClearHandles();
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
      log::debug("Initiating service search for {}", device->addr);
      device->ClearHandles();
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

    int mcs_index = 1;
    bool gmcs_found = false;
    for (const auto& service : *services) {
      if (service.uuid == kGenericMediaControlServiceUuid) {
        Mcs mcs;
        mcs.id = kGmcsServiceId;
        mcs.is_gmcs = true;
        mcs.start_handle = service.handle;
        mcs.end_handle = service.end_handle;
        device->services.push_back(mcs);
        gmcs_found = true;
      } else if (service.uuid == kMediaControlServiceUuid) {
        Mcs mcs;
        mcs.id = mcs_index++;
        mcs.is_gmcs = false;
        mcs.start_handle = service.handle;
        mcs.end_handle = service.end_handle;
        device->services.push_back(mcs);
      }
    }

    if (!gmcs_found) {
      if (device->searching_for_gmcs) {
        log::error("GMCS not found on device {}", device->addr);
        BTA_GATTC_Close(device->conn_id);
        return;
      }
      device->ClearHandles();
      device->searching_for_gmcs = true;
      BTA_GATTC_ServiceSearchRequest(device->conn_id);
      return;
    }
    device->searching_for_gmcs = false;
    if (!com::android::bluetooth::flags::leaudio_peripheral_mcp_link_abstraction_layer()) {
      device->service_found = true;
    }

    log::info("Found {} GMCS/MCS services on {}. Discovering characteristics...",
              device->services.size(), device->addr);

    for (const auto& service : *services) {
      auto mcs = device->GetServiceByHandle(service.handle);
      if (!mcs) {
        continue;
      }

      for (const auto& chrc : service.characteristics) {
        if (chrc.uuid == kMediaPlayerNameUuid) {
          mcs->media_player_name_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackChangedUuid) {
          mcs->track_changed_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackTitleUuid) {
          mcs->track_title_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackDurationUuid) {
          mcs->track_duration_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackPositionUuid) {
          mcs->track_position_handle = chrc.value_handle;
        } else if (chrc.uuid == kPlaybackSpeedUuid) {
          mcs->playback_speed_handle = chrc.value_handle;
        } else if (chrc.uuid == kPlayingOrderUuid) {
          mcs->playing_order_handle = chrc.value_handle;
        } else if (chrc.uuid == kPlayingOrderSupportedUuid) {
          mcs->playing_orders_supported_handle = chrc.value_handle;
        } else if (chrc.uuid == kSeekingSpeedUuid) {
          mcs->seeking_speed_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaStateUuid) {
          mcs->media_state_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaControlPointUuid) {
          mcs->media_control_point_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaControlPointOpcodesSupportedUuid) {
          mcs->opcodes_supported_handle = chrc.value_handle;
        } else if (chrc.uuid == kContentControlIdUuid) {
          mcs->content_control_id_handle = chrc.value_handle;
        }
      }
    }

    for (const auto& service : device->services) {
      if (service.media_player_name_handle == kInvalidGattHandle ||
          service.track_changed_handle == kInvalidGattHandle ||
          service.track_title_handle == kInvalidGattHandle ||
          service.track_duration_handle == kInvalidGattHandle ||
          service.track_position_handle == kInvalidGattHandle ||
          service.media_state_handle == kInvalidGattHandle ||
          service.content_control_id_handle == kInvalidGattHandle ||
          (service.media_control_point_handle != kInvalidGattHandle &&
           service.opcodes_supported_handle == kInvalidGattHandle)) {
        log::error("Mandatory MCS characteristics not found on {} for service {}", device->addr,
                   service.id);
        BTA_GATTC_Close(device->conn_id);
        return;
      }
    }

    callbacks_->OnDiscovered(device->addr);

    if (com::android::bluetooth::flags::leaudio_peripheral_mcp_link_abstraction_layer()) {
      device->service_found = true;

      /* Initial state would be read after encryption complete */
      if (get_security_client_interface().BTM_IsEncrypted(device->addr, BT_TRANSPORT_LE)) {
        RegisterForNotifications(device);
        ReadInitialState(device);
      }
    } else {
      RegisterForNotifications(device);
      ReadInitialState(device);
    }
  }

  void ParseMcpIndication(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                          const tBTA_GATTC_NOTIFY& evt) {
    if (evt.handle == service.media_control_point_handle) {
      if (evt.len >= kMcpNotificationLen) {
        uint8_t opcode = evt.value[kMcpNotificationOpcodeIndex];
        MediaControlResultCode result =
                static_cast<MediaControlResultCode>(evt.value[kMcpNotificationResultIndex]);
        callbacks_->OnMediaControlResult(device->addr, service.id, opcode, result);
      }
    }
  }

  void ParseMediaPlayerNameNotification(const std::shared_ptr<McpDevice>& device,
                                        const Mcs& service, const uint8_t* value, uint16_t len) {
    callbacks_->OnMediaPlayerNameChanged(device->addr, service.id, std::string((char*)value, len));
  }

  void ParseTrackChangedNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service) {
    callbacks_->OnTrackChanged(device->addr, service.id);
  }

  void ParseTrackTitleNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                   const uint8_t* value, uint16_t len) {
    callbacks_->OnTrackTitleChanged(device->addr, service.id, std::string((char*)value, len));
  }

  void ParseTrackDurationNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                      const uint8_t* value, uint16_t len) {
    if (len != kTrackDurationLen) {
      log::error("Invalid Track Duration notification from device: {}, len: {}", device->addr, len);
      return;
    }
    int32_t duration;
    const uint8_t* p = value;
    STREAM_TO_UINT32(duration, p);
    callbacks_->OnTrackDurationChanged(device->addr, service.id, duration);
  }

  void ParseTrackPositionNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                      const uint8_t* value, uint16_t len) {
    if (len != kTrackPositionLen) {
      log::error("Invalid Track Position notification from device: {}, len: {}", device->addr, len);
      return;
    }
    int32_t position;
    const uint8_t* p = value;
    STREAM_TO_UINT32(position, p);
    callbacks_->OnTrackPositionChanged(device->addr, service.id, position);
  }

  void ParseMediaStateNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                   const uint8_t* value, uint16_t len) {
    if (len != kMediaStateLen) {
      log::error("Invalid Media State notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnMediaStateChanged(device->addr, service.id,
                                    static_cast<MediaState>(value[kMediaStateIndex]));
  }

  void ParsePlaybackSpeedNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                      const uint8_t* value, uint16_t len) {
    if (len != kPlaybackSpeedLen) {
      log::error("Invalid Playback Speed notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnPlaybackSpeedChanged(device->addr, service.id,
                                       static_cast<int8_t>(value[kPlaybackSpeedIndex]));
  }

  void ParsePlayingOrderNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                     const uint8_t* value, uint16_t len) {
    if (len != kPlayingOrderLen) {
      log::error("Invalid Playing Order notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnPlayingOrderChanged(device->addr, service.id,
                                      static_cast<PlayingOrder>(value[kPlayingOrderIndex]));
  }

  void ParsePlayingOrdersSupported(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                   const uint8_t* value, uint16_t len) {
    if (len != kPlayingOrdersSupportedLen) {
      log::error("Invalid Playing Orders Supported notification from device: {}, len: {}",
                 device->addr, len);
      return;
    }
    uint16_t playing_orders;
    const uint8_t* p = value;
    STREAM_TO_UINT16(playing_orders, p);
    callbacks_->OnPlayingOrdersSupportedChanged(device->addr, service.id, playing_orders);
  }

  void ParseSeekingSpeedNotification(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                                     const uint8_t* value, uint16_t len) {
    if (len != kSeekingSpeedLen) {
      log::error("Invalid Seeking Speed notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnSeekingSpeedChanged(device->addr, service.id,
                                      static_cast<int8_t>(value[kSeekingSpeedIndex]));
  }

  void ParseOpcodesSupported(const std::shared_ptr<McpDevice>& device, const Mcs& service,
                             const uint8_t* value, uint16_t len) {
    if (len != kOpcodesSupportedLen) {
      log::error("Invalid Opcodes Supported notification from device: {}, len: {}", device->addr,
                 len);
      return;
    }
    uint32_t opcodes;
    const uint8_t* p = value;
    STREAM_TO_UINT32(opcodes, p);
    callbacks_->OnOpcodesSupportedChanged(device->addr, service.id, opcodes);
  }

  void OnNotification(const tBTA_GATTC_NOTIFY& evt) {
    auto device = FindDevice(evt.conn_id);
    if (!device) {
      return;
    }

    auto service = device->GetServiceByHandle(evt.handle);
    if (!service) {
      return;
    }

    if (!evt.is_notify) {  // Indication
      BTA_GATTC_SendIndConfirm(device->conn_id, evt.cid);
      ParseMcpIndication(device, *service, evt);
      return;
    }

    if (evt.handle == service->media_player_name_handle) {
      ParseMediaPlayerNameNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->track_changed_handle) {
      ParseTrackChangedNotification(device, *service);
    } else if (evt.handle == service->track_title_handle) {
      ParseTrackTitleNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->track_duration_handle) {
      ParseTrackDurationNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->track_position_handle) {
      ParseTrackPositionNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->playback_speed_handle) {
      ParsePlaybackSpeedNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->seeking_speed_handle) {
      ParseSeekingSpeedNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->playing_order_handle) {
      ParsePlayingOrderNotification(device, *service, evt.value, evt.len);
    } else if (evt.handle == service->media_state_handle) {
      ParseMediaStateNotification(device, *service, evt.value, evt.len);
    } else {
      log::warn("Unhandled notification on handle 0x{:04x}", evt.handle);
    }
  }

  void ReadInitialState(std::shared_ptr<McpDevice>& device) {
    for (const auto& service : device->services) {
      if (service.media_player_name_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.media_player_name_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.media_state_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.media_state_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.opcodes_supported_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.opcodes_supported_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.track_title_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.track_title_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.track_duration_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.track_duration_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.track_position_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.track_position_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.playing_order_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.playing_order_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.playing_orders_supported_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.playing_orders_supported_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.playback_speed_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.playback_speed_handle,
                                         OnGattReadStatic, nullptr);
      }
      if (service.seeking_speed_handle != kInvalidGattHandle) {
        BtaGattQueue::ReadCharacteristic(device->conn_id, service.seeking_speed_handle,
                                         OnGattReadStatic, nullptr);
      }
    }
  }

  void DeregisterNotifications(const std::shared_ptr<McpDevice>& device) {
    for (const auto& service : device->services) {
      if (service.media_player_name_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                             service.media_player_name_handle);
      }
      if (service.track_changed_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.track_changed_handle);
      }
      if (service.track_title_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.track_title_handle);
      }
      if (service.track_duration_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.track_duration_handle);
      }
      if (service.track_position_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.track_position_handle);
      }
      if (service.playback_speed_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.playback_speed_handle);
      }
      if (service.playing_order_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.playing_order_handle);
      }
      if (service.seeking_speed_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.seeking_speed_handle);
      }
      if (service.media_state_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, service.media_state_handle);
      }
      if (service.media_control_point_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                             service.media_control_point_handle);
      }
      if (service.opcodes_supported_handle != kInvalidGattHandle) {
        BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                             service.opcodes_supported_handle);
      }
    }
  }

  void DoDisconnectCleanup(const std::shared_ptr<McpDevice>& device) {
    log::debug("{}", device->addr);

    DeregisterNotifications(device);

    if (device->IsConnected()) {
      BtaGattQueue::Clean(device->conn_id);
    }
    device->conn_id = GATT_INVALID_CONN_ID;
  }

  void RegisterForNotifications(const std::shared_ptr<McpDevice>& device) {
    for (const auto& service : device->services) {
      if (service.media_player_name_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                           service.media_player_name_handle);
      }
      if (service.track_changed_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.track_changed_handle);
      }
      if (service.track_title_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.track_title_handle);
      }
      if (service.track_duration_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.track_duration_handle);
      }
      if (service.track_position_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.track_position_handle);
      }
      if (service.playback_speed_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.playback_speed_handle);
      }
      if (service.playing_order_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.playing_order_handle);
      }
      if (service.seeking_speed_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.seeking_speed_handle);
      }
      if (service.media_state_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, service.media_state_handle);
      }
      if (service.media_control_point_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                           service.media_control_point_handle);
      }
      if (service.opcodes_supported_handle != kInvalidGattHandle) {
        BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                           service.opcodes_supported_handle);
      }
    }
  }

  void WriteMediaControlPoint(const RawAddress& address, int service_id, uint8_t opcode) {
    WriteMediaControlPoint(address, service_id, opcode, {});
  }

  void WriteMediaControlPoint(const RawAddress& address, int service_id, uint8_t opcode,
                              const std::vector<uint8_t>& params) {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected()) {
      log::error("Device not ready for MCP command: {}", address);
      return;
    }
    auto service = device->GetService(service_id);
    if (!service || service->media_control_point_handle == kInvalidGattHandle) {
      log::error("Service not ready for MCP command: {}", address);
      return;
    }
    std::vector<uint8_t> value_to_write;
    value_to_write.push_back(opcode);
    value_to_write.insert(value_to_write.end(), params.begin(), params.end());
    BtaGattQueue::WriteCharacteristic(device->conn_id, service->media_control_point_handle,
                                      value_to_write, GATT_WRITE_NO_RSP, nullptr, nullptr);
  }

  std::shared_ptr<McpDevice> FindDevice(const RawAddress& address) {
    auto it = std::find_if(devices_.begin(), devices_.end(), McpDevice::MatchAddress(address));
    return (it == devices_.end()) ? nullptr : *it;
  }

  std::shared_ptr<McpDevice> FindDevice(uint16_t conn_id) {
    auto it = std::find_if(devices_.begin(), devices_.end(), McpDevice::MatchConnId(conn_id));
    return (it == devices_.end()) ? nullptr : *it;
  }

private:
  McpClientCallbacks* callbacks_;
  tGATT_IF gatt_if_ = 0;
  std::list<std::shared_ptr<McpDevice>> devices_;
};

std::unique_ptr<McpClientImpl> instance = nullptr;

}  // namespace

// --- McpClient static methods ---
void McpClient::Initialize(McpClientCallbacks* callbacks, base::OnceClosure initCb) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }
  instance = std::make_unique<McpClientImpl>(callbacks, std::move(initCb));
}

void McpClient::Cleanup() {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    return;
  }
  instance->Cleanup();
  instance = nullptr;
}

McpClient* McpClient::Get() { return instance.get(); }

void McpClient::AddFromStorage(const RawAddress& address) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    instance->AddFromStorage(address);
  }
}

void McpClient::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    instance->DebugDump(fd);
  }
}
