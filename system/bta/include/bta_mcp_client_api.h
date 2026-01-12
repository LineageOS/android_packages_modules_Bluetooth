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
