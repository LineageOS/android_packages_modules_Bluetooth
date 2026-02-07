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

#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bt_has.h>

#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include "bta_has_api.h"
#include "btif_common.h"
#include "btif_le_audio.h"
#include "btif_profile_storage.h"
#include "stack/include/main_thread.h"

using base::BindOnce;
using base::Unretained;
using bluetooth::has::ConnectionState;
using bluetooth::has::ErrorCode;
using bluetooth::has::HasClientCallbacks;
using bluetooth::has::HasClientInterface;
using bluetooth::has::PresetInfo;
using bluetooth::has::PresetInfoReason;

using bluetooth::le_audio::has::HasClient;

namespace {
std::unique_ptr<HasClientInterface> has_client_instance;

class HearingAaccessClientServiceInterfaceImpl : public HasClientInterface,
                                                 public HasClientCallbacks {
  ~HearingAaccessClientServiceInterfaceImpl() override = default;

  void Init(HasClientCallbacks* callbacks) override {
    this->callbacks_ = callbacks;

    do_in_main_thread(BindOnce(
            &HasClient::Initialize, this,
            jni_thread_wrapper(base::BindOnce(&btif_storage_load_bonded_leaudio_has_devices))));
  }

  void Connect(const RawAddress& addr) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::Connect, GetWeakPtr(), addr));
    } else {
      do_in_main_thread(BindOnce(&HasClient::Connect, Unretained(HasClient::Get()), addr));
    }
    do_in_jni_thread(BindOnce(&btif_storage_set_leaudio_has_acceptlist, addr, true));
  }

  void Disconnect(const RawAddress& addr) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::Disconnect, GetWeakPtr(), addr));
    } else {
      do_in_main_thread(BindOnce(&HasClient::Disconnect, Unretained(HasClient::Get()), addr));
    }
    do_in_jni_thread(BindOnce(&btif_storage_set_leaudio_has_acceptlist, addr, false));
  }

  void SelectActivePreset(std::variant<RawAddress, int> addr_or_group_id,
                          uint8_t preset_index) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::SelectActivePreset, GetWeakPtr(),
                                 std::move(addr_or_group_id), preset_index));
      return;
    }
    do_in_main_thread(BindOnce(&HasClient::SelectActivePreset, Unretained(HasClient::Get()),
                               std::move(addr_or_group_id), preset_index));
  }

  void NextActivePreset(std::variant<RawAddress, int> addr_or_group_id) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(
              BindOnce(&HasClient::NextActivePreset, GetWeakPtr(), std::move(addr_or_group_id)));
      return;
    }
    do_in_main_thread(BindOnce(&HasClient::NextActivePreset, Unretained(HasClient::Get()),
                               std::move(addr_or_group_id)));
  }

  void PreviousActivePreset(std::variant<RawAddress, int> addr_or_group_id) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::PreviousActivePreset, GetWeakPtr(),
                                 std::move(addr_or_group_id)));
      return;
    }
    do_in_main_thread(BindOnce(&HasClient::PreviousActivePreset, Unretained(HasClient::Get()),
                               std::move(addr_or_group_id)));
  }

  void GetPresetInfo(const RawAddress& addr, uint8_t preset_index) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::GetPresetInfo, GetWeakPtr(), addr, preset_index));
      return;
    }
    do_in_main_thread(
            BindOnce(&HasClient::GetPresetInfo, Unretained(HasClient::Get()), addr, preset_index));
  }

  void GetAllPresetInfo(const RawAddress& addr) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::GetAllPresetInfo, GetWeakPtr(), addr));
      return;
    }
    do_in_main_thread(BindOnce(&HasClient::GetAllPresetInfo, Unretained(HasClient::Get()), addr));
  }

  void SetPresetName(std::variant<RawAddress, int> addr_or_group_id, uint8_t preset_index,
                     std::string preset_name) override {
    if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
      do_in_main_thread(BindOnce(&HasClient::SetPresetName, GetWeakPtr(),
                                 std::move(addr_or_group_id), preset_index,
                                 std::move(preset_name)));
      return;
    }
    do_in_main_thread(BindOnce(&HasClient::SetPresetName, Unretained(HasClient::Get()),
                               std::move(addr_or_group_id), preset_index, std::move(preset_name)));
  }

  void RemoveDevice(const RawAddress& addr) override {
    /* RemoveDevice can be called on devices that don't have BAS enabled */
    if (HasClient::IsHasClientRunning()) {
      if (com_android_bluetooth_flags_hap_keep_bonded_dev_in_ram()) {
        do_in_main_thread(BindOnce(&HasClient::RemoveDevice, GetWeakPtr(), addr));
      } else {
        do_in_main_thread(BindOnce(&HasClient::Disconnect, Unretained(HasClient::Get()), addr));
      }
    }

    do_in_jni_thread(BindOnce(&btif_storage_remove_leaudio_has, addr));
  }

  void Cleanup(void) override { do_in_main_thread(BindOnce(&HasClient::CleanUp)); }

  void OnConnectionState(ConnectionState state, const RawAddress& addr) override {
    do_in_jni_thread(
            BindOnce(&HasClientCallbacks::OnConnectionState, Unretained(callbacks_), state, addr));
  }

  void OnDeviceAvailable(const RawAddress& addr, uint8_t features) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnDeviceAvailable, Unretained(callbacks_), addr,
                              features));
  }

  void OnFeaturesUpdate(const RawAddress& addr, uint8_t features) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnFeaturesUpdate, Unretained(callbacks_), addr,
                              features));
  }

  void OnActivePresetSelected(const RawAddress& addr, uint8_t preset_index) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnActivePresetSelected, Unretained(callbacks_),
                              std::move(addr), preset_index));
  }

  void OnActivePresetSelectedForGroup(int group_id, uint8_t preset_index) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnActivePresetSelectedForGroup,
                              Unretained(callbacks_), group_id, preset_index));
  }

  void OnActivePresetSelectError(std::variant<RawAddress, int> addr_or_group_id,
                                 ErrorCode result_code) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnActivePresetSelectError,
                              Unretained(callbacks_), std::move(addr_or_group_id), result_code));
  }

  void OnPresetInfo(std::variant<RawAddress, int> addr_or_group_id, PresetInfoReason change_id,
                    std::vector<PresetInfo> detail_records) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnPresetInfo, Unretained(callbacks_),
                              std::move(addr_or_group_id), change_id, std::move(detail_records)));
  }

  void OnPresetInfoError(std::variant<RawAddress, int> addr_or_group_id, uint8_t preset_index,
                         ErrorCode result_code) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnPresetInfoError, Unretained(callbacks_),
                              std::move(addr_or_group_id), preset_index, result_code));
  }

  void OnSetPresetNameError(std::variant<RawAddress, int> addr_or_group_id, uint8_t preset_index,
                            ErrorCode result_code) override {
    do_in_jni_thread(BindOnce(&HasClientCallbacks::OnSetPresetNameError, Unretained(callbacks_),
                              std::move(addr_or_group_id), preset_index, result_code));
  }

private:
  base::WeakPtr<HasClient> GetWeakPtr() { return HasClient::GetWeakPtr(); }

  HasClientCallbacks* callbacks_;
};

} /* namespace */

HasClientInterface* btif_has_client_get_interface(void) {
  if (!has_client_instance) {
    has_client_instance.reset(new HearingAaccessClientServiceInterfaceImpl());
  }

  return has_client_instance.get();
}
