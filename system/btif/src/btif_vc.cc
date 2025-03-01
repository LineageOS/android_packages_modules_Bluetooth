/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

/* Volume Control Interface */

#include <aics/api.h>
#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <hardware/bt_vc.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <variant>

#include "bta/include/bta_vc_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_profile_storage.h"
#include "btif_le_audio.h"
#include "stack/include/main_thread.h"
#include "types/raw_address.h"

using base::Bind;
using base::Unretained;
using bluetooth::aics::GainMode;
using bluetooth::aics::Mute;
using bluetooth::vc::ConnectionState;
using bluetooth::vc::VolumeControlCallbacks;
using bluetooth::vc::VolumeControlInterface;

namespace {
static std::unique_ptr<VolumeControlInterface> vc_instance;
static std::atomic_bool initialized = false;

class VolumeControlInterfaceImpl : public VolumeControlInterface, public VolumeControlCallbacks {
  ~VolumeControlInterfaceImpl() override = default;

  void Init(VolumeControlCallbacks* callbacks) override {
    this->callbacks_ = callbacks;
    do_in_main_thread(
            Bind(&VolumeControl::Initialize, this,
                 jni_thread_wrapper(Bind(&btif_storage_load_bonded_volume_control_devices))));

    /* It might be not yet initialized, but setting this flag here is safe,
     * because other calls will check this and the native instance
     */
    initialized = true;
  }

  void OnConnectionState(ConnectionState state, const RawAddress& address) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnConnectionState, Unretained(callbacks_), state,
                          address));
  }

  void OnVolumeStateChanged(const RawAddress& address, uint8_t volume, bool mute, uint8_t flags,
                            bool isAutonomous) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnVolumeStateChanged, Unretained(callbacks_),
                          address, volume, mute, flags, isAutonomous));
  }

  void OnGroupVolumeStateChanged(int group_id, uint8_t volume, bool mute,
                                 bool isAutonomous) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnGroupVolumeStateChanged,
                          Unretained(callbacks_), group_id, volume, mute, isAutonomous));
  }

  void OnDeviceAvailable(const RawAddress& address, uint8_t num_offset,
                         uint8_t num_inputs) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnDeviceAvailable, Unretained(callbacks_),
                          address, num_offset, num_inputs));
  }

  /* Callbacks for Volume Offset Control Service (VOCS) - Extended Audio Outputs
   */

  void OnExtAudioOutVolumeOffsetChanged(const RawAddress& address, uint8_t ext_output_id,
                                        int16_t offset) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioOutVolumeOffsetChanged,
                          Unretained(callbacks_), address, ext_output_id, offset));
  }

  void OnExtAudioOutLocationChanged(const RawAddress& address, uint8_t ext_output_id,
                                    uint32_t location) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioOutLocationChanged,
                          Unretained(callbacks_), address, ext_output_id, location));
  }

  void OnExtAudioOutDescriptionChanged(const RawAddress& address, uint8_t ext_output_id,
                                       std::string descr) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioOutDescriptionChanged,
                          Unretained(callbacks_), address, ext_output_id, descr));
  }

  /* Callbacks for Audio Input Stream (AIS) - Extended Audio Inputs */
  void OnExtAudioInStateChanged(const RawAddress& address, uint8_t ext_input_id,
                                int8_t gain_setting, ::Mute mute, ::GainMode gain_mode) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInStateChanged, Unretained(callbacks_),
                          address, ext_input_id, gain_setting, mute, gain_mode));
  }

  void OnExtAudioInSetGainSettingFailed(const RawAddress& address, uint8_t ext_input_id) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInSetGainSettingFailed,
                          Unretained(callbacks_), address, ext_input_id));
  }

  void OnExtAudioInSetMuteFailed(const RawAddress& address, uint8_t ext_input_id) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInSetMuteFailed,
                          Unretained(callbacks_), address, ext_input_id));
  }
  void OnExtAudioInSetGainModeFailed(const RawAddress& address, uint8_t ext_input_id) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInSetGainModeFailed,
                          Unretained(callbacks_), address, ext_input_id));
  }

  void OnExtAudioInStatusChanged(const RawAddress& address, uint8_t ext_input_id,
                                 bluetooth::vc::VolumeInputStatus status) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInStatusChanged,
                          Unretained(callbacks_), address, ext_input_id, status));
  }

  void OnExtAudioInTypeChanged(const RawAddress& address, uint8_t ext_input_id,
                               bluetooth::vc::VolumeInputType type) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInTypeChanged, Unretained(callbacks_),
                          address, ext_input_id, type));
  }

  void OnExtAudioInGainSettingPropertiesChanged(const RawAddress& address, uint8_t ext_input_id,
                                                uint8_t unit, int8_t min, int8_t max) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInGainSettingPropertiesChanged,
                          Unretained(callbacks_), address, ext_input_id, unit, min, max));
  }

  void OnExtAudioInDescriptionChanged(const RawAddress& address, uint8_t ext_input_id,
                                      std::string description, bool is_writable) override {
    do_in_jni_thread(Bind(&VolumeControlCallbacks::OnExtAudioInDescriptionChanged,
                          Unretained(callbacks_), address, ext_input_id, description, is_writable));
  }

  void Connect(const RawAddress& address) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::Connect, Unretained(VolumeControl::Get()), address));
  }

  void Disconnect(const RawAddress& address) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }
    do_in_main_thread(Bind(&VolumeControl::Disconnect, Unretained(VolumeControl::Get()), address));
  }

  void SetVolume(std::variant<RawAddress, int> addr_or_group_id, uint8_t volume) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::SetVolume, Unretained(VolumeControl::Get()),
                           std::move(addr_or_group_id), volume));
  }

  void Mute(std::variant<RawAddress, int> addr_or_group_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::Mute, Unretained(VolumeControl::Get()),
                           std::move(addr_or_group_id)));
  }

  void Unmute(std::variant<RawAddress, int> addr_or_group_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::UnMute, Unretained(VolumeControl::Get()),
                           std::move(addr_or_group_id)));
  }

  void RemoveDevice(const RawAddress& address) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    /* RemoveDevice can be called on devices that don't have HA enabled */
    if (VolumeControl::IsVolumeControlRunning()) {
      do_in_main_thread(Bind(&VolumeControl::Remove, Unretained(VolumeControl::Get()), address));
    }
  }

  void GetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioOutVolumeOffset,
                           Unretained(VolumeControl::Get()), address, ext_output_id));
  }

  void SetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id,
                                  int16_t offset_val) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioOutVolumeOffset,
                           Unretained(VolumeControl::Get()), address, ext_output_id, offset_val));
  }

  void GetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioOutLocation, Unretained(VolumeControl::Get()),
                           address, ext_output_id));
  }

  void SetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id,
                              uint32_t location) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioOutLocation, Unretained(VolumeControl::Get()),
                           address, ext_output_id, location));
  }

  void GetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioOutDescription,
                           Unretained(VolumeControl::Get()), address, ext_output_id));
  }

  void SetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id,
                                 std::string descr) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioOutDescription,
                           Unretained(VolumeControl::Get()), address, ext_output_id, descr));
  }

  void GetExtAudioInState(const RawAddress& address, uint8_t ext_input_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioInState, Unretained(VolumeControl::Get()),
                           address, ext_input_id));
  }

  void GetExtAudioInStatus(const RawAddress& address, uint8_t ext_input_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioInStatus, Unretained(VolumeControl::Get()),
                           address, ext_input_id));
  }

  void GetExtAudioInType(const RawAddress& address, uint8_t ext_input_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioInType, Unretained(VolumeControl::Get()),
                           address, ext_input_id));
  }

  void GetExtAudioInGainProps(const RawAddress& address, uint8_t ext_input_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioInGainProps, Unretained(VolumeControl::Get()),
                           address, ext_input_id));
  }

  void GetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    do_in_main_thread(Bind(&VolumeControl::GetExtAudioInDescription,
                           Unretained(VolumeControl::Get()), address, ext_input_id));
  }

  bool SetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id,
                                std::string descr) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return false;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioInDescription,
                           Unretained(VolumeControl::Get()), address, ext_input_id, descr));
    return true;
  }

  bool SetExtAudioInGainSetting(const RawAddress& address, uint8_t ext_input_id,
                                int8_t gain_setting) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service being not read");
      return false;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioInGainSetting,
                           Unretained(VolumeControl::Get()), address, ext_input_id, gain_setting));
    return true;
  }

  bool SetExtAudioInGainMode(const RawAddress& address, uint8_t ext_input_id,
                             ::GainMode gain_mode) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service being not read");
      return false;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioInGainMode, Unretained(VolumeControl::Get()),
                           address, ext_input_id, gain_mode));
    return true;
  }

  bool SetExtAudioInMute(const RawAddress& address, uint8_t ext_input_id, ::Mute mute) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service being not read");
      return false;
    }

    do_in_main_thread(Bind(&VolumeControl::SetExtAudioInMute, Unretained(VolumeControl::Get()),
                           address, ext_input_id, mute));
    return true;
  }

  void Cleanup(void) override {
    if (!initialized || !VolumeControl::IsVolumeControlRunning()) {
      bluetooth::log::verbose(
              "call ignored, due to already started cleanup procedure or service "
              "being not read");
      return;
    }

    initialized = false;
    do_in_main_thread(Bind(&VolumeControl::CleanUp));
  }

private:
  VolumeControlCallbacks* callbacks_;
};

} /* namespace */

VolumeControlInterface* btif_volume_control_get_interface(void) {
  if (!vc_instance) {
    vc_instance.reset(new VolumeControlInterfaceImpl());
  }

  return vc_instance.get();
}
