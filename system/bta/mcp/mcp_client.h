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

#include <cstdint>
#include <memory>
#include <sstream>
#include <vector>

#include "common/strings.h"
#include "hardware/bt_le_audio.h"
#include "mcp_types.h"
#include "stack/include/bt_types.h"
#include "stack/include/gatt_api.h"

namespace bluetooth {
namespace mcp {

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

// Represents the state of a single connected device for the Media Control Profile.
class McpDevice : public GattServiceDevice {
public:
  McpDevice(const RawAddress& address) : GattServiceDevice(address) {}

  struct MatchAddress {
    MatchAddress(const RawAddress& address) : address(address) {}
    bool operator()(const std::shared_ptr<McpDevice>& other) const {
      return address == other->addr;
    }
    RawAddress address;
  };

  struct MatchConnId {
    MatchConnId(uint16_t conn_id) : conn_id(conn_id) {}
    bool operator()(const std::shared_ptr<McpDevice>& other) const {
      return conn_id == other->conn_id;
    }
    uint16_t conn_id;
  };

  void DebugDump(std::stringstream& stream) const;

  void ClearHandles() {
    service_found = false;
    media_player_name_handle = 0;
    track_changed_handle = 0;
    track_title_handle = 0;
    track_duration_handle = 0;
    track_position_handle = 0;
    playback_speed_handle = 0;
    playing_orders_supported_handle = 0;
    seeking_speed_handle = 0;
    media_state_handle = 0;
    media_control_point_handle = 0;
    opcodes_supported_handle = 0;
    content_control_id_handle = 0;
  }

  // Characteristic handles specific to MCP/MCS
  uint16_t media_player_name_handle = 0;
  uint16_t track_changed_handle = 0;
  uint16_t track_title_handle = 0;
  uint16_t track_duration_handle = 0;
  uint16_t track_position_handle = 0;
  uint16_t playback_speed_handle = 0;
  uint16_t playing_orders_supported_handle = 0;
  uint16_t seeking_speed_handle = 0;
  uint16_t media_state_handle = 0;
  uint16_t media_control_point_handle = 0;
  uint16_t opcodes_supported_handle = 0;
  uint16_t content_control_id_handle = 0;
};

// Interface for MCP Client callbacks.
class McpClientCallbacks {
public:
  virtual ~McpClientCallbacks() = default;
  virtual void OnConnectionState(const RawAddress& address, le_audio::ConnectionState state) = 0;
  virtual void OnDiscovered(const RawAddress& address) = 0;
  virtual void OnMediaPlayerNameChanged(const RawAddress& address, const std::string& name) = 0;
  virtual void OnTrackChanged(const RawAddress& address) = 0;
  virtual void OnTrackTitleChanged(const RawAddress& address, const std::string& title) = 0;
  virtual void OnTrackDurationChanged(const RawAddress& address, int32_t duration) = 0;
  virtual void OnTrackPositionChanged(const RawAddress& address, int32_t position) = 0;
  virtual void OnPlaybackSpeedChanged(const RawAddress& address, int8_t speed) = 0;
  virtual void OnPlayingOrdersSupportedChanged(const RawAddress& address,
                                               uint16_t playing_orders) = 0;
  virtual void OnSeekingSpeedChanged(const RawAddress& address, int8_t speed) = 0;
  virtual void OnMediaStateChanged(const RawAddress& address, uint8_t state) = 0;
  virtual void OnMediaControlResult(const RawAddress& address, uint8_t opcode,
                                    MediaControlResultCode result) = 0;
  virtual void OnOpcodesSupportedChanged(const RawAddress& address, uint32_t opcodes) = 0;
};

// Main interface for the MCP Client module.
class McpClient {
public:
  static void Initialize(McpClientCallbacks* callbacks, base::Closure initCb);
  static void Cleanup();
  static McpClient* Get();
  static void DebugDump(int fd);

  virtual void Connect(const RawAddress& address) = 0;
  virtual void Disconnect(const RawAddress& address) = 0;

  // Media Control Point commands
  virtual void Play(const RawAddress& address) = 0;
  virtual void Pause(const RawAddress& address) = 0;
  virtual void Stop(const RawAddress& address) = 0;
  virtual void NextTrack(const RawAddress& address) = 0;
  virtual void PreviousTrack(const RawAddress& address) = 0;
  virtual void FastRewind(const RawAddress& address) = 0;
  virtual void FastForward(const RawAddress& address) = 0;
  virtual void MoveRelative(const RawAddress& address, int32_t offset) = 0;

  // Track Position Characteristic commands
  virtual void SetTrackPosition(const RawAddress& address, int32_t position) = 0;

  virtual ~McpClient() = default;
};

}  // namespace mcp
}  // namespace bluetooth
