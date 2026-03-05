/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "hardware/bt_mcp_client.h"

namespace bluetooth {
namespace mcp {

// Main interface for the MCP Client module.
class McpClient {
public:
  static void Initialize(McpClientCallbacks* callbacks, base::OnceClosure initCb);
  static void Cleanup();
  static McpClient* Get();
  static void AddFromStorage(const RawAddress& address);
  static void DebugDump(int fd);

  virtual void Connect(const RawAddress& address) = 0;
  virtual void Disconnect(const RawAddress& address) = 0;

  // Media Control Point commands
  virtual void Play(const RawAddress& address, int service_id) = 0;
  virtual void Pause(const RawAddress& address, int service_id) = 0;
  virtual void Stop(const RawAddress& address, int service_id) = 0;
  virtual void NextTrack(const RawAddress& address, int service_id) = 0;
  virtual void PreviousTrack(const RawAddress& address, int service_id) = 0;
  virtual void FastRewind(const RawAddress& address, int service_id) = 0;
  virtual void FastForward(const RawAddress& address, int service_id) = 0;
  virtual void MoveRelative(const RawAddress& address, int service_id, int32_t offset) = 0;

  // Track Position Characteristic command
  virtual void SetTrackPosition(const RawAddress& address, int service_id, int32_t position) = 0;

  // Playback Speed Characteristic command
  virtual void SetPlaybackSpeed(const RawAddress& address, int service_id, int8_t speed) = 0;

  // Playing Order Characteristic command
  virtual void SetPlayingOrder(const RawAddress& address, int media_controller_id,
                               PlayingOrder playing_order) = 0;

  virtual ~McpClient() = default;
};

}  // namespace mcp
}  // namespace bluetooth
