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

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <hardware/bt_mcp_client.h>

#include <memory>

#include "bta/include/bta_mcp_client_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_le_audio_peripheral.h"
#include "stack/include/main_thread.h"

using base::BindOnce;
using base::Unretained;
using bluetooth::mcp::ConnectionState;
using bluetooth::mcp::McpClient;
using bluetooth::mcp::McpClientCallbacks;
using bluetooth::mcp::McpClientInterface;
using bluetooth::mcp::MediaControlResultCode;

namespace {
std::unique_ptr<McpClientInterface> mcp_client_instance;

class McpClientInterfaceImpl : public McpClientInterface, public McpClientCallbacks {
  ~McpClientInterfaceImpl() override = default;

  void Init(McpClientCallbacks* callbacks) override {
    this->callbacks_ = callbacks;
    do_in_main_thread(BindOnce(&McpClient::Initialize, this, BindOnce([] {})));
  }

  void Cleanup() override { do_in_main_thread(BindOnce(&McpClient::Cleanup)); }

  void Connect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Connect, Unretained(McpClient::Get()), address));
  }

  void Disconnect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Disconnect, Unretained(McpClient::Get()), address));
  }

  void Play(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Play, Unretained(McpClient::Get()), address));
  }

  void Pause(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Pause, Unretained(McpClient::Get()), address));
  }

  void Stop(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Stop, Unretained(McpClient::Get()), address));
  }

  void NextTrack(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::NextTrack, Unretained(McpClient::Get()), address));
  }

  void PreviousTrack(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::PreviousTrack, Unretained(McpClient::Get()), address));
  }

  void FastRewind(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::FastRewind, Unretained(McpClient::Get()), address));
  }

  void FastForward(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::FastForward, Unretained(McpClient::Get()), address));
  }

  void MoveRelative(const RawAddress& address, int32_t offset) override {
    do_in_main_thread(
            BindOnce(&McpClient::MoveRelative, Unretained(McpClient::Get()), address, offset));
  }

  void SetTrackPosition(const RawAddress& address, int32_t position) override {
    do_in_main_thread(BindOnce(&McpClient::SetTrackPosition, Unretained(McpClient::Get()), address,
                               position));
  }

  // Callbacks
  void OnConnectionState(const RawAddress& address, ConnectionState state) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnConnectionState, Unretained(callbacks_),
                              address, state));
  }

  void OnDiscovered(const RawAddress& address) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnDiscovered, Unretained(callbacks_), address));
  }

  void OnMediaPlayerNameChanged(const RawAddress& address, const std::string& name) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaPlayerNameChanged, Unretained(callbacks_),
                              address, name));
  }

  void OnTrackChanged(const RawAddress& address) override {
    do_in_jni_thread(
            BindOnce(&McpClientCallbacks::OnTrackChanged, Unretained(callbacks_), address));
  }

  void OnTrackTitleChanged(const RawAddress& address, const std::string& title) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackTitleChanged, Unretained(callbacks_),
                              address, title));
  }

  void OnTrackDurationChanged(const RawAddress& address, int32_t duration) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackDurationChanged, Unretained(callbacks_),
                              address, duration));
  }

  void OnTrackPositionChanged(const RawAddress& address, int32_t position) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackPositionChanged, Unretained(callbacks_),
                              address, position));
  }

  void OnPlaybackSpeedChanged(const RawAddress& address, int8_t speed) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnPlaybackSpeedChanged, Unretained(callbacks_),
                              address, speed));
  }

  void OnPlayingOrdersSupportedChanged(const RawAddress& address,
                                       uint16_t playing_orders) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnPlayingOrdersSupportedChanged,
                              Unretained(callbacks_), address, playing_orders));
  }

  void OnSeekingSpeedChanged(const RawAddress& address, int8_t speed) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnSeekingSpeedChanged, Unretained(callbacks_),
                              address, speed));
  }

  void OnMediaStateChanged(const RawAddress& address, uint8_t state) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaStateChanged, Unretained(callbacks_),
                              address, state));
  }

  void OnMediaControlResult(const RawAddress& address, uint8_t opcode,
                            MediaControlResultCode result) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaControlResult, Unretained(callbacks_),
                              address, opcode, result));
  }

  void OnOpcodesSupportedChanged(const RawAddress& address, uint32_t opcodes) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnOpcodesSupportedChanged,
                              Unretained(callbacks_), address, opcodes));
  }

private:
  McpClientCallbacks* callbacks_;
};

}  // namespace

McpClientInterface* btif_mcp_client_get_interface() {
  if (!mcp_client_instance) {
    mcp_client_instance.reset(new McpClientInterfaceImpl());
  }
  return mcp_client_instance.get();
}
