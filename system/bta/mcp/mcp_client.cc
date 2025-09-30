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

#include "bta/include/bta_api.h"
#include "bta/include/bta_gatt_api.h"
#include "bta_gatt_queue.h"
#include "common/strings.h"
#include "hardware/bt_le_audio.h"
#include "mcp/mcp_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/gatt_api.h"

using bluetooth::le_audio::ConnectionState;
using namespace bluetooth;
using namespace bluetooth::mcp;

namespace {
class McpClientImpl;
std::unique_ptr<McpClientImpl> instance = nullptr;
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
  McpClientImpl(McpClientCallbacks* callbacks, base::Closure initCb) : callbacks_(callbacks) {
    BTA_GATTC_AppRegister(
            "mcp_client",
            [](tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
              if (instance && p_data) {
                instance->GattcCallback(event, p_data);
              }
            },
            base::Bind(
                    [](base::Closure initCb, uint8_t client_id, uint8_t status) {
                      if (status != GATT_SUCCESS) {
                        log::error("Failed to register MCP client app");
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
    if (!BTM_IsBonded(address, BT_TRANSPORT_LE)) {
      log::error("Connecting {} when not bonded", address);
      callbacks_->OnConnectionState(address, ConnectionState::DISCONNECTED);
      return;
    }
    BTA_GATTC_Open(gatt_if_, address, BTM_BLE_DIRECT_CONNECTION, true);
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

  void Play(const RawAddress& address) override { WriteMediaControlPoint(address, kMcpOpcodePlay); }

  void Pause(const RawAddress& address) override {
    WriteMediaControlPoint(address, kMcpOpcodePause);
  }

  void Stop(const RawAddress& address) override { WriteMediaControlPoint(address, kMcpOpcodeStop); }

  void NextTrack(const RawAddress& address) override {
    WriteMediaControlPoint(address, kMcpOpcodeNextTrack);
  }

  void PreviousTrack(const RawAddress& address) override {
    WriteMediaControlPoint(address, kMcpOpcodePreviousTrack);
  }

  void FastRewind(const RawAddress& address) override {
    WriteMediaControlPoint(address, kMcpOpcodeFastRewind);
  }

  void FastForward(const RawAddress& address) override {
    WriteMediaControlPoint(address, kMcpOpcodeFastForward);
  }

  void MoveRelative(const RawAddress& address, int32_t offset) override {
    std::vector<uint8_t> value(4);
    uint8_t* ptr = value.data();
    UINT32_TO_STREAM(ptr, offset);
    WriteMediaControlPoint(address, kMcpOpcodeMoveRelative, value);
  }

  void SetTrackPosition(const RawAddress& address, int32_t position) override {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected() || device->track_position_handle == 0) {
      log::error("Device not ready for SetTrackPosition: {}", address);
      return;
    }
    std::vector<uint8_t> value(4);
    uint8_t* ptr = value.data();
    UINT32_TO_STREAM(ptr, position);
    BtaGattQueue::WriteCharacteristic(device->conn_id, device->track_position_handle, value,
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
      case BTA_GATTC_DEREG_EVT:
        break;
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
                             BTM_IsEncrypted(p_data->enc_cmpl.remote_bda, BT_TRANSPORT_LE));
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

    if (status != GATT_SUCCESS) {
      log::warn("Read failed for handle 0x{:04x}, status {}", handle, gatt_status_text(status));
      return;
    }

    if (handle == device->media_player_name_handle) {
      ParseMediaPlayerNameNotification(device, value, len);
    } else if (handle == device->track_title_handle) {
      ParseTrackTitleNotification(device, value, len);
    } else if (handle == device->track_duration_handle) {
      ParseTrackDurationNotification(device, value, len);
    } else if (handle == device->track_position_handle) {
      ParseTrackPositionNotification(device, value, len);
    } else if (handle == device->media_state_handle) {
      ParseMediaStateNotification(device, value, len);
    } else if (handle == device->opcodes_supported_handle) {
      ParseOpcodesSupported(device, value, len);
    } else if (handle == device->playing_orders_supported_handle) {
      ParsePlayingOrdersSupported(device, value, len);
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
      log::info("Adding new McpDevice for {}", evt.remote_bda);
      device = std::make_shared<McpDevice>(evt.remote_bda);
      device->conn_id = evt.conn_id;
      devices_.emplace_back(device);
    }
    callbacks_->OnConnectionState(evt.remote_bda, ConnectionState::CONNECTED);

    if (BTM_IsEncrypted(device->addr, BT_TRANSPORT_LE)) {
      OnEncryptionComplete(device->addr, true);
    } else {
      tBTM_STATUS result = BTM_SetEncryption(device->addr, BT_TRANSPORT_LE, nullptr, nullptr,
                                             BTM_BLE_SEC_ENCRYPT);

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
      BTA_GATTC_ServiceSearchRequest(device->conn_id, kGenericMediaControlServiceUuid);
    }
  }

  void OnServiceChangeEvent(const RawAddress& bda) {
    auto device = FindDevice(bda);
    if (!device) {
      return;
    }

    log::info("Service changed for {}", device->addr);
    device->ClearHandles();
    BTA_GATTC_ServiceSearchRequest(device->conn_id, kGenericMediaControlServiceUuid);
  }

  void OnServiceDiscoveryDoneEvent(const RawAddress& bda) {
    auto device = FindDevice(bda);
    if (!device) {
      return;
    }

    log::info("Service discovery done for {}", device->addr);
    if (!device->service_found) {
      BTA_GATTC_ServiceSearchRequest(device->conn_id, kGenericMediaControlServiceUuid);
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
      if (service.uuid != kGenericMediaControlServiceUuid) {
        continue;
      }

      log::info("Found MCS on {}. Discovering characteristics...", device->addr);
      device->service_found = true;
      for (const auto& chrc : service.characteristics) {
        if (chrc.uuid == kMediaPlayerNameUuid) {
          device->media_player_name_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackChangedUuid) {
          device->track_changed_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackTitleUuid) {
          device->track_title_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackDurationUuid) {
          device->track_duration_handle = chrc.value_handle;
        } else if (chrc.uuid == kTrackPositionUuid) {
          device->track_position_handle = chrc.value_handle;
        } else if (chrc.uuid == kPlaybackSpeedUuid) {
          device->playback_speed_handle = chrc.value_handle;
        } else if (chrc.uuid == kPlayingOrderSupportedUuid) {
          device->playing_orders_supported_handle = chrc.value_handle;
        } else if (chrc.uuid == kSeekingSpeedUuid) {
          device->seeking_speed_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaStateUuid) {
          device->media_state_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaControlPointUuid) {
          device->media_control_point_handle = chrc.value_handle;
        } else if (chrc.uuid == kMediaControlPointOpcodesSupportedUuid) {
          device->opcodes_supported_handle = chrc.value_handle;
        } else if (chrc.uuid == kContentControlIdUuid) {
          device->content_control_id_handle = chrc.value_handle;
        }
      }
    }

    if (device->service_found) {
      if (device->media_state_handle == 0 || device->media_control_point_handle == 0 ||
          device->opcodes_supported_handle == 0) {
        log::error("Mandatory MCS characteristics not found on {}", device->addr);
        BTA_GATTC_Close(device->conn_id);
        return;
      }
      callbacks_->OnDiscovered(device->addr);
      RegisterForNotifications(device);
      ReadInitialState(device);
    } else {
      log::error("MCS not found on device {}", device->addr);
      BTA_GATTC_Close(device->conn_id);
    }
  }

  void ParseMcpIndication(const std::shared_ptr<McpDevice>& device, const tBTA_GATTC_NOTIFY& evt) {
    if (evt.handle == device->media_control_point_handle) {
      if (evt.len >= 2) {
        uint8_t opcode = evt.value[0];
        MediaControlResultCode result = static_cast<MediaControlResultCode>(evt.value[1]);
        callbacks_->OnMediaControlResult(device->addr, opcode, result);
      }
    }
  }

  void ParseMediaPlayerNameNotification(const std::shared_ptr<McpDevice>& device,
                                        const uint8_t* value, uint16_t len) {
    callbacks_->OnMediaPlayerNameChanged(device->addr, std::string((char*)value, len));
  }

  void ParseTrackChangedNotification(const std::shared_ptr<McpDevice>& device) {
    callbacks_->OnTrackChanged(device->addr);
  }

  void ParseTrackTitleNotification(const std::shared_ptr<McpDevice>& device, const uint8_t* value,
                                   uint16_t len) {
    callbacks_->OnTrackTitleChanged(device->addr, std::string((char*)value, len));
  }

  void ParseTrackDurationNotification(const std::shared_ptr<McpDevice>& device,
                                      const uint8_t* value, uint16_t len) {
    if (len != 4) {
      log::error("Invalid Track Duration notification from device: {}, len: {}", device->addr, len);
      return;
    }
    int32_t duration;
    const uint8_t* p = value;
    STREAM_TO_UINT32(duration, p);
    callbacks_->OnTrackDurationChanged(device->addr, duration);
  }

  void ParseTrackPositionNotification(const std::shared_ptr<McpDevice>& device,
                                      const uint8_t* value, uint16_t len) {
    if (len != 4) {
      log::error("Invalid Track Position notification from device: {}, len: {}", device->addr, len);
      return;
    }
    int32_t position;
    const uint8_t* p = value;
    STREAM_TO_UINT32(position, p);
    callbacks_->OnTrackPositionChanged(device->addr, position);
  }

  void ParseMediaStateNotification(const std::shared_ptr<McpDevice>& device, const uint8_t* value,
                                   uint16_t len) {
    if (len != 1) {
      log::error("Invalid Media State notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnMediaStateChanged(device->addr, value[0]);
  }

  void ParsePlaybackSpeedNotification(const std::shared_ptr<McpDevice>& device,
                                      const uint8_t* value, uint16_t len) {
    if (len != 1) {
      log::error("Invalid Playback Speed notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnPlaybackSpeedChanged(device->addr, static_cast<int8_t>(value[0]));
  }

  void ParsePlayingOrdersSupported(const std::shared_ptr<McpDevice>& device, const uint8_t* value,
                                   uint16_t len) {
    if (len != 2) {
      log::error("Invalid Playing Orders Supported notification from device: {}, len: {}",
                 device->addr, len);
      return;
    }
    uint16_t playing_orders;
    const uint8_t* p = value;
    STREAM_TO_UINT16(playing_orders, p);
    callbacks_->OnPlayingOrdersSupportedChanged(device->addr, playing_orders);
  }

  void ParseSeekingSpeedNotification(const std::shared_ptr<McpDevice>& device, const uint8_t* value,
                                     uint16_t len) {
    if (len != 1) {
      log::error("Invalid Seeking Speed notification from device: {}, len: {}", device->addr, len);
      return;
    }
    callbacks_->OnSeekingSpeedChanged(device->addr, static_cast<int8_t>(value[0]));
  }

  void ParseOpcodesSupported(const std::shared_ptr<McpDevice>& device, const uint8_t* value,
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

  void OnNotification(const tBTA_GATTC_NOTIFY& evt) {
    auto device = FindDevice(evt.conn_id);
    if (!device) {
      return;
    }

    if (!evt.is_notify) {  // Indication
      BTA_GATTC_SendIndConfirm(device->conn_id, evt.cid);
      ParseMcpIndication(device, evt);
      return;
    }

    if (evt.handle == device->media_player_name_handle) {
      ParseMediaPlayerNameNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->track_changed_handle) {
      ParseTrackChangedNotification(device);
    } else if (evt.handle == device->track_title_handle) {
      ParseTrackTitleNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->track_duration_handle) {
      ParseTrackDurationNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->track_position_handle) {
      ParseTrackPositionNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->playback_speed_handle) {
      ParsePlaybackSpeedNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->seeking_speed_handle) {
      ParseSeekingSpeedNotification(device, evt.value, evt.len);
    } else if (evt.handle == device->media_state_handle) {
      ParseMediaStateNotification(device, evt.value, evt.len);
    } else {
      log::warn("Unhandled notification on handle 0x{:04x}", evt.handle);
    }
  }

  void ReadInitialState(std::shared_ptr<McpDevice>& device) {
    if (device->media_player_name_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->media_player_name_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->media_state_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->media_state_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->opcodes_supported_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->opcodes_supported_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->track_title_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->track_title_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->track_duration_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->track_duration_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->track_position_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->track_position_handle,
                                       OnGattReadStatic, nullptr);
    }
    if (device->playing_orders_supported_handle != kInvalidGattHandle) {
      BtaGattQueue::ReadCharacteristic(device->conn_id, device->playing_orders_supported_handle,
                                       OnGattReadStatic, nullptr);
    }
  }

  void DeregisterNotifications(const std::shared_ptr<McpDevice>& device) {
    if (device->media_player_name_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->media_player_name_handle);
    }
    if (device->track_changed_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->track_changed_handle);
    }
    if (device->track_title_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->track_title_handle);
    }
    if (device->track_duration_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->track_duration_handle);
    }
    if (device->track_position_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->track_position_handle);
    }
    if (device->playback_speed_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->playback_speed_handle);
    }
    if (device->seeking_speed_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->seeking_speed_handle);
    }
    if (device->media_state_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr, device->media_state_handle);
    }
    if (device->media_control_point_handle != kInvalidGattHandle) {
      BTA_GATTC_DeregisterForNotifications(gatt_if_, device->addr,
                                           device->media_control_point_handle);
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
    if (device->media_player_name_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->media_player_name_handle);
    }
    if (device->track_changed_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->track_changed_handle);
    }
    if (device->track_title_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->track_title_handle);
    }
    if (device->track_duration_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->track_duration_handle);
    }
    if (device->track_position_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->track_position_handle);
    }
    if (device->playback_speed_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->playback_speed_handle);
    }
    if (device->seeking_speed_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->seeking_speed_handle);
    }
    if (device->media_state_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr, device->media_state_handle);
    }
    if (device->media_control_point_handle != kInvalidGattHandle) {
      BTA_GATTC_RegisterForNotifications(gatt_if_, device->addr,
                                         device->media_control_point_handle);
    }
  }

  void WriteMediaControlPoint(const RawAddress& address, uint8_t opcode) {
    WriteMediaControlPoint(address, opcode, {});
  }

  void WriteMediaControlPoint(const RawAddress& address, uint8_t opcode,
                              const std::vector<uint8_t>& params) {
    auto device = FindDevice(address);
    if (!device || !device->IsConnected() || device->media_control_point_handle == 0) {
      log::error("Device not ready for MCP command: {}", address);
      return;
    }
    std::vector<uint8_t> value_to_write;
    value_to_write.push_back(opcode);
    value_to_write.insert(value_to_write.end(), params.begin(), params.end());
    BtaGattQueue::WriteCharacteristic(device->conn_id, device->media_control_point_handle,
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

}  // namespace

// --- McpClient static methods ---
void McpClient::Initialize(McpClientCallbacks* callbacks, base::Closure initCb) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }
  instance = std::make_unique<McpClientImpl>(callbacks, initCb);
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

void McpDevice::DebugDump(std::stringstream& stream) const {
  GattServiceDevice::DebugDump(stream);

  stream << "\n    Media Player Name Handle: "
         << bluetooth::common::ToHexString(media_player_name_handle)
         << "\n    Track Changed Handle: " << bluetooth::common::ToHexString(track_changed_handle)
         << "\n    Track Title Handle: " << bluetooth::common::ToHexString(track_title_handle)
         << "\n    Track Duration Handle: " << bluetooth::common::ToHexString(track_duration_handle)
         << "\n    Track Position Handle: " << bluetooth::common::ToHexString(track_position_handle)
         << "\n    Playback Speed Handle: " << bluetooth::common::ToHexString(playback_speed_handle)
         << "\n    Seeking Speed Handle: " << bluetooth::common::ToHexString(seeking_speed_handle)
         << "\n    Media State Handle: " << bluetooth::common::ToHexString(media_state_handle)
         << "\n    Media Control Point Handle: "
         << bluetooth::common::ToHexString(media_control_point_handle)
         << "\n    Opcodes Supported Handle: "
         << bluetooth::common::ToHexString(opcodes_supported_handle)
         << "\n    Content Control ID Handle: "
         << bluetooth::common::ToHexString(content_control_id_handle) << "\n";
}
