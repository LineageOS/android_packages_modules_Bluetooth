/*
 * Copyright (C) 2021 The Android Open Source Project
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
#include <hardware/bluetooth_headset_interface.h>

#include <memory>

#include "rust/cxx.h"
#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"

namespace bluetooth {
namespace topshim {
namespace rust {

struct TelephonyDeviceStatus;
struct CallInfo;
struct PhoneState;

class HfpIntf {
public:
  HfpIntf(headset::Interface* intf) : intf_(intf) {}

  tBT_STATUS_LEGACY init();
  tBT_STATUS_LEGACY connect(RawAddress addr);
  tBT_STATUS_LEGACY connect_audio(RawAddress addr, bool sco_offload, int disabled_codecs);
  tBT_STATUS_LEGACY set_active_device(RawAddress addr);
  tBT_STATUS_LEGACY set_volume(int8_t volume, RawAddress addr);
  tBT_STATUS_LEGACY set_mic_volume(int8_t volume, RawAddress addr);
  tBT_STATUS_LEGACY disconnect(RawAddress addr);
  tBT_STATUS_LEGACY disconnect_audio(RawAddress addr);
  tBT_STATUS_LEGACY device_status_notification(TelephonyDeviceStatus status, RawAddress addr);
  tBT_STATUS_LEGACY indicator_query_response(TelephonyDeviceStatus device_status,
                                             PhoneState phone_state, RawAddress addr);
  tBT_STATUS_LEGACY current_calls_query_response(const ::rust::Vec<CallInfo>& call_list,
                                                 RawAddress addr);
  tBT_STATUS_LEGACY phone_state_change(PhoneState phone_state, const ::rust::String& number,
                                       RawAddress addr);
  tBT_STATUS_LEGACY simple_at_response(bool ok, RawAddress addr);
  void debug_dump();
  void cleanup();

private:
  headset::Interface* intf_;
};

std::unique_ptr<HfpIntf> GetHfpProfile(const BtIntf& intf);
bool interop_insert_call_when_sco_start(RawAddress addr);
bool interop_disable_hf_profile(const ::rust::String& name);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
