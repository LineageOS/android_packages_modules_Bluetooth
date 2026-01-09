/******************************************************************************
 *
 *  Copyright 2018 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/* Hearing Aid Profile Interface */

#include "btif_hearing_aid.h"

#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/types/address.h>
#include <hardware/bt_hearing_aid.h>

#include <cstdint>
#include <memory>
#include <utility>

#include "bta_hearing_aid_api.h"
#include "btif_common.h"
#include "btif_profile_storage.h"
#include "hardware/avrcp/avrcp.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"

using base::BindOnce;
using base::Unretained;

// template specialization
template <>
base::OnceCallback<void()> jni_thread_wrapper(base::OnceCallback<void()> cb) {
  return base::BindOnce([](base::OnceCallback<void()> cb) { do_in_jni_thread(std::move(cb)); },
                        std::move(cb));
}

namespace bluetooth::asha {
namespace {

std::unique_ptr<HearingAidInterface> hearingAidInstance;

class HearingAidInterfaceImpl : public HearingAidInterface, public HearingAidCallbacks {
  ~HearingAidInterfaceImpl() override = default;

  void Init(HearingAidCallbacks* callbacks) override {
    this->callbacks = callbacks;
    do_in_main_thread(
            BindOnce(&HearingAid::Initialize, this,
                     jni_thread_wrapper(base::BindOnce(&btif_storage_load_bonded_hearing_aids))));
  }

  void OnConnectionState(ConnectionState state, const RawAddress& address) override {
    do_in_jni_thread(BindOnce(&HearingAidCallbacks::OnConnectionState, Unretained(callbacks), state,
                              address));
  }

  void OnDeviceAvailable(uint8_t capabilities, uint64_t hiSyncId,
                         const RawAddress& address) override {
    do_in_jni_thread(BindOnce(&HearingAidCallbacks::OnDeviceAvailable, Unretained(callbacks),
                              capabilities, hiSyncId, address));
  }

  void Connect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(
            [](RawAddress bd_addr) {
              stack::l2cap::get_interface().L2CA_LockBleConnParamsForProfileConnection(bd_addr,
                                                                                       false);
            },
            address));
    do_in_main_thread(BindOnce(&HearingAid::Connect, address));
  }

  void Disconnect(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&HearingAid::Disconnect, address));
    do_in_jni_thread(BindOnce(&btif_storage_set_hearing_aid_acceptlist, address, false));
  }

  void AddToAcceptlist(const RawAddress& address) override {
    do_in_main_thread(BindOnce(&HearingAid::AddToAcceptlist, address));
    do_in_jni_thread(BindOnce(&btif_storage_set_hearing_aid_acceptlist, address, true));
  }

  void SetVolume(int8_t volume) override {
    do_in_main_thread(BindOnce(&HearingAid::SetVolume, volume));
  }

  void RemoveDevice(const RawAddress& address) override {
    // RemoveDevice can be called on devices that don't have HA enabled
    if (HearingAid::IsHearingAidRunning()) {
      do_in_main_thread(BindOnce(&HearingAid::Disconnect, address));
    }

    do_in_jni_thread(BindOnce(&btif_storage_remove_hearing_aid, address));
  }

  void Cleanup(void) override { do_in_main_thread(BindOnce(&HearingAid::CleanUp)); }

private:
  HearingAidCallbacks* callbacks;
};

}  // namespace
}  // namespace bluetooth::asha

bluetooth::asha::HearingAidInterface* btif_hearing_aid_get_interface() {
  if (!bluetooth::asha::hearingAidInstance) {
    bluetooth::asha::hearingAidInstance.reset(new bluetooth::asha::HearingAidInterfaceImpl());
  }

  return bluetooth::asha::hearingAidInstance.get();
}
