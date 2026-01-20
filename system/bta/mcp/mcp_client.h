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

#include "common/strings.h"
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

  void DebugDump(std::stringstream& stream) const {
    GattServiceDevice::DebugDump(stream);

    stream << "\n    Media Player Name Handle: "
           << bluetooth::common::ToHexString(media_player_name_handle)
           << "\n    Track Changed Handle: " << bluetooth::common::ToHexString(track_changed_handle)
           << "\n    Track Title Handle: " << bluetooth::common::ToHexString(track_title_handle)
           << "\n    Track Duration Handle: "
           << bluetooth::common::ToHexString(track_duration_handle)
           << "\n    Track Position Handle: "
           << bluetooth::common::ToHexString(track_position_handle)
           << "\n    Playback Speed Handle: "
           << bluetooth::common::ToHexString(playback_speed_handle)
           << "\n    Seeking Speed Handle: " << bluetooth::common::ToHexString(seeking_speed_handle)
           << "\n    Media State Handle: " << bluetooth::common::ToHexString(media_state_handle)
           << "\n    Media Control Point Handle: "
           << bluetooth::common::ToHexString(media_control_point_handle)
           << "\n    Opcodes Supported Handle: "
           << bluetooth::common::ToHexString(opcodes_supported_handle)
           << "\n    Content Control ID Handle: "
           << bluetooth::common::ToHexString(content_control_id_handle) << "\n";
  }

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

}  // namespace mcp
}  // namespace bluetooth
