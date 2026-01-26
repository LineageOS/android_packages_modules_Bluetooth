/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>

namespace bluetooth {
namespace mcp {

enum class ConnectionState { DISCONNECTED = 0, CONNECTING, CONNECTED, DISCONNECTING };

enum class MediaState : uint8_t {
  INACTIVE = 0x00,
  PLAYING = 0x01,
  PAUSED = 0x02,
  SEEKING = 0x03,
};

enum class PlayingOrder : uint8_t {
  SINGLE_ONCE = 0x01,
  SINGLE_REPEAT = 0x02,
  IN_ORDER_ONCE = 0x03,
  IN_ORDER_REPEAT = 0x04,
  OLDEST_ONCE = 0x05,
  OLDEST_REPEAT = 0x06,
  NEWEST_ONCE = 0x07,
  NEWEST_REPEAT = 0x08,
  SHUFFLE_ONCE = 0x09,
  SHUFFLE_REPEAT = 0x0A,
};

enum class MediaControlResultCode : uint8_t {
  SUCCESS = 0x01,
  OPCODE_NOT_SUPPORTED = 0x02,
  MEDIA_PLAYER_INACTIVE = 0x03,
  COMMAND_CANNOT_BE_COMPLETED = 0x04,
};

class McpClientCallbacks {
public:
  virtual ~McpClientCallbacks() = default;
  virtual void OnConnectionState(const RawAddress& address, ConnectionState state) = 0;
  virtual void OnDiscovered(const RawAddress& address) = 0;
  virtual void OnMediaPlayerNameChanged(const RawAddress& address, int media_controller_id,
                                        const std::string& name) = 0;
  virtual void OnTrackChanged(const RawAddress& address, int media_controller_id) = 0;
  virtual void OnTrackTitleChanged(const RawAddress& address, int media_controller_id,
                                   const std::string& title) = 0;
  virtual void OnTrackDurationChanged(const RawAddress& address, int media_controller_id,
                                      int32_t duration) = 0;
  virtual void OnTrackPositionChanged(const RawAddress& address, int media_controller_id,
                                      int32_t position) = 0;
  virtual void OnPlaybackSpeedChanged(const RawAddress& address, int media_controller_id,
                                      int8_t speed) = 0;
  virtual void OnPlayingOrderChanged(const RawAddress& address, int media_controller_id,
                                     PlayingOrder playing_order) = 0;
  virtual void OnPlayingOrdersSupportedChanged(const RawAddress& address, int media_controller_id,
                                               uint16_t playing_orders) = 0;
  virtual void OnSeekingSpeedChanged(const RawAddress& address, int media_controller_id,
                                     int8_t speed) = 0;
  virtual void OnMediaStateChanged(const RawAddress& address, int media_controller_id,
                                   MediaState state) = 0;
  virtual void OnMediaControlResult(const RawAddress& address, int media_controller_id,
                                    uint8_t opcode, MediaControlResultCode result) = 0;
  virtual void OnOpcodesSupportedChanged(const RawAddress& address, int media_controller_id,
                                         uint32_t opcodes) = 0;
};

class McpClientInterface {
public:
  virtual ~McpClientInterface() = default;

  virtual void Init(McpClientCallbacks* callbacks) = 0;
  virtual void Cleanup() = 0;
  virtual void Connect(const RawAddress& address) = 0;
  virtual void Disconnect(const RawAddress& address) = 0;
  virtual void Play(const RawAddress& address, int media_controller_id) = 0;
  virtual void Pause(const RawAddress& address, int media_controller_id) = 0;
  virtual void Stop(const RawAddress& address, int media_controller_id) = 0;
  virtual void NextTrack(const RawAddress& address, int media_controller_id) = 0;
  virtual void PreviousTrack(const RawAddress& address, int media_controller_id) = 0;
  virtual void FastRewind(const RawAddress& address, int media_controller_id) = 0;
  virtual void FastForward(const RawAddress& address, int media_controller_id) = 0;
  virtual void MoveRelative(const RawAddress& address, int media_controller_id, int32_t offset) = 0;
  virtual void SetTrackPosition(const RawAddress& address, int media_controller_id,
                                int32_t position) = 0;
  virtual void SetPlaybackSpeed(const RawAddress& address, int media_controller_id,
                                int8_t speed) = 0;
  virtual void SetPlayingOrder(const RawAddress& address, int media_controller_id,
                               PlayingOrder playing_order) = 0;
};

}  // namespace mcp
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::mcp::ConnectionState>
    : enum_formatter<bluetooth::mcp::ConnectionState> {};
template <>
struct formatter<bluetooth::mcp::MediaState> : enum_formatter<bluetooth::mcp::MediaState> {};
template <>
struct formatter<bluetooth::mcp::PlayingOrder> : enum_formatter<bluetooth::mcp::PlayingOrder> {};
template <>
struct formatter<bluetooth::mcp::MediaControlResultCode>
    : enum_formatter<bluetooth::mcp::MediaControlResultCode> {};
}  // namespace std
