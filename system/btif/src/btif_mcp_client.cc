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
#include "btif/include/btif_profile_storage.h"
#include "stack/include/main_thread.h"

using base::BindOnce;
using base::Unretained;
using bluetooth::mcp::ConnectionState;
using bluetooth::mcp::McpClient;
using bluetooth::mcp::McpClientCallbacks;
using bluetooth::mcp::McpClientInterface;
using bluetooth::mcp::MediaControlResultCode;
using bluetooth::mcp::MediaState;
using bluetooth::mcp::PlayingOrder;

namespace {
std::unique_ptr<McpClientInterface> mcp_client_instance;

class McpClientInterfaceImpl : public McpClientInterface, public McpClientCallbacks {
  ~McpClientInterfaceImpl() override = default;

  void Init(McpClientCallbacks* callbacks) override {
    this->callbacks_ = callbacks;
    do_in_main_thread(BindOnce(
            &McpClient::Initialize, this,
            jni_thread_wrapper(base::BindOnce(&btif_storage_load_bonded_mcp_client_devices))));
  }

  void Cleanup() override { do_in_main_thread(BindOnce(&McpClient::Cleanup)); }

  void Connect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Connect, Unretained(McpClient::Get()), address));
  }

  void Disconnect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&McpClient::Disconnect, Unretained(McpClient::Get()), address));
  }

  void Play(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(
            BindOnce(&McpClient::Play, Unretained(McpClient::Get()), address, media_controller_id));
  }

  void Pause(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(BindOnce(&McpClient::Pause, Unretained(McpClient::Get()), address,
                               media_controller_id));
  }

  void Stop(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(
            BindOnce(&McpClient::Stop, Unretained(McpClient::Get()), address, media_controller_id));
  }

  void NextTrack(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(BindOnce(&McpClient::NextTrack, Unretained(McpClient::Get()), address,
                               media_controller_id));
  }

  void PreviousTrack(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(BindOnce(&McpClient::PreviousTrack, Unretained(McpClient::Get()), address,
                               media_controller_id));
  }

  void FastRewind(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(BindOnce(&McpClient::FastRewind, Unretained(McpClient::Get()), address,
                               media_controller_id));
  }

  void FastForward(const RawAddress& address, int media_controller_id) override {
    do_in_main_thread(BindOnce(&McpClient::FastForward, Unretained(McpClient::Get()), address,
                               media_controller_id));
  }

  void MoveRelative(const RawAddress& address, int media_controller_id, int32_t offset) override {
    do_in_main_thread(BindOnce(&McpClient::MoveRelative, Unretained(McpClient::Get()), address,
                               media_controller_id, offset));
  }

  void SetTrackPosition(const RawAddress& address, int media_controller_id,
                        int32_t position) override {
    do_in_main_thread(BindOnce(&McpClient::SetTrackPosition, Unretained(McpClient::Get()), address,
                               media_controller_id, position));
  }

  void SetPlaybackSpeed(const RawAddress& address, int media_controller_id, int8_t speed) override {
    do_in_main_thread(BindOnce(&McpClient::SetPlaybackSpeed, Unretained(McpClient::Get()), address,
                               media_controller_id, speed));
  }

  void SetPlayingOrder(const RawAddress& address, int media_controller_id,
                       PlayingOrder playing_order) override {
    do_in_main_thread(BindOnce(&McpClient::SetPlayingOrder, Unretained(McpClient::Get()), address,
                               media_controller_id, playing_order));
  }

  // Callbacks
  void OnConnectionState(const RawAddress& address, ConnectionState state) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnConnectionState, Unretained(callbacks_),
                              address, state));
  }

  void OnDiscovered(const RawAddress& address) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnDiscovered, Unretained(callbacks_), address));
  }

  void OnMediaPlayerNameChanged(const RawAddress& address, int media_controller_id,
                                const std::string& name) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaPlayerNameChanged, Unretained(callbacks_),
                              address, media_controller_id, name));
  }

  void OnTrackChanged(const RawAddress& address, int media_controller_id) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackChanged, Unretained(callbacks_), address,
                              media_controller_id));
  }

  void OnTrackTitleChanged(const RawAddress& address, int media_controller_id,
                           const std::string& title) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackTitleChanged, Unretained(callbacks_),
                              address, media_controller_id, title));
  }

  void OnTrackDurationChanged(const RawAddress& address, int media_controller_id,
                              int32_t duration) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackDurationChanged, Unretained(callbacks_),
                              address, media_controller_id, duration));
  }

  void OnTrackPositionChanged(const RawAddress& address, int media_controller_id,
                              int32_t position) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnTrackPositionChanged, Unretained(callbacks_),
                              address, media_controller_id, position));
  }

  void OnPlaybackSpeedChanged(const RawAddress& address, int media_controller_id,
                              int8_t speed) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnPlaybackSpeedChanged, Unretained(callbacks_),
                              address, media_controller_id, speed));
  }

  void OnPlayingOrderChanged(const RawAddress& address, int media_controller_id,
                             PlayingOrder playing_order) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnPlayingOrderChanged, Unretained(callbacks_),
                              address, media_controller_id, playing_order));
  }

  void OnPlayingOrdersSupportedChanged(const RawAddress& address, int media_controller_id,
                                       uint16_t playing_orders) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnPlayingOrdersSupportedChanged,
                              Unretained(callbacks_), address, media_controller_id,
                              playing_orders));
  }

  void OnSeekingSpeedChanged(const RawAddress& address, int media_controller_id,
                             int8_t speed) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnSeekingSpeedChanged, Unretained(callbacks_),
                              address, media_controller_id, speed));
  }

  void OnMediaStateChanged(const RawAddress& address, int media_controller_id,
                           MediaState state) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaStateChanged, Unretained(callbacks_),
                              address, media_controller_id, state));
  }

  void OnMediaControlResult(const RawAddress& address, int media_controller_id, uint8_t opcode,
                            MediaControlResultCode result) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnMediaControlResult, Unretained(callbacks_),
                              address, media_controller_id, opcode, result));
  }

  void OnOpcodesSupportedChanged(const RawAddress& address, int media_controller_id,
                                 uint32_t opcodes) override {
    do_in_jni_thread(BindOnce(&McpClientCallbacks::OnOpcodesSupportedChanged,
                              Unretained(callbacks_), address, media_controller_id, opcodes));
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
