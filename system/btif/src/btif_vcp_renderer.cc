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

#include <bluetooth/types/address.h>
#include <hardware/bt_vcp_renderer.h>

#include <memory>

#include "bta_vcp_renderer_api.h"
#include "btif_common.h"
#include "btif_le_audio_peripheral.h"
#include "stack/include/main_thread.h"

using base::BindOnce;
using base::BindRepeating;
using base::Unretained;
using namespace bluetooth;
using vcp::GattConnectionState;
using vcp::MuteState;
using vcp::VolumeFlags;
using vcp::VolumeRendererCallbacks;
using vcp::VolumeRendererConfig;
using vcp::VolumeRendererInterface;

namespace {
class VolumeRendererInterfaceImpl;
std::unique_ptr<VolumeRendererInterface> volumeRendererInstance;
std::atomic_bool initialized = false;

class VolumeRendererInterfaceImpl : public VolumeRendererInterface, public VolumeRendererCallbacks {
public:
  VolumeRendererInterfaceImpl() = default;
  ~VolumeRendererInterfaceImpl() = default;

  void OnInitialized(void) override {
    do_in_jni_thread(
            BindRepeating(&VolumeRendererCallbacks::OnInitialized, Unretained(callbacks_)));
  }

  void OnGattConnectionStateChanged(const RawAddress& address, GattConnectionState state) override {
    do_in_jni_thread(BindRepeating(&VolumeRendererCallbacks::OnGattConnectionStateChanged,
                                   Unretained(callbacks_), address, state));
  }

  void OnVolumeStateChangeRequest(uint8_t volume, MuteState mute_state) override {
    do_in_jni_thread(BindRepeating(&VolumeRendererCallbacks::OnVolumeStateChangeRequest,
                                   Unretained(callbacks_), volume, mute_state));
  }

  void Initialize(VolumeRendererCallbacks* callbacks, const VolumeRendererConfig& config) override {
    callbacks_ = callbacks;
    do_in_main_thread(BindOnce(&vcp::VolumeRenderer::Initialize, this, config, nullptr));
    initialized = true;
  }

  void Cleanup(void) override {
    initialized = false;
    do_in_main_thread(BindOnce([]() { vcp::VolumeRenderer::Cleanup(); }));
  }

  void UpdateVolumeState(uint8_t volume, MuteState mute_state) override {
    if (!initialized) {
      log::warn(
              "call ignored, due to already started cleanup procedure or service being not ready");
      return;
    }

    do_in_main_thread(BindOnce(&vcp::VolumeRenderer::UpdateVolumeState,
                               Unretained(vcp::VolumeRenderer::Get()), volume, mute_state));
  }

  void UpdateVolumeFlags(const VolumeFlags& flags) override {
    if (!initialized) {
      log::warn(
              "call ignored, due to already started cleanup procedure or service being not ready");
      return;
    }

    do_in_main_thread(BindOnce(&vcp::VolumeRenderer::UpdateVolumeFlags,
                               Unretained(vcp::VolumeRenderer::Get()), flags));
  }

private:
  VolumeRendererCallbacks* callbacks_;
};

}  // namespace

VolumeRendererInterface* btif_vcp_renderer_get_interface() {
  if (!volumeRendererInstance) {
    volumeRendererInstance.reset(new VolumeRendererInterfaceImpl());
  }

  return volumeRendererInstance.get();
}
