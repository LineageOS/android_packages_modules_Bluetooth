/*
 * Copyright (C) 2025 The Android Open Source Project
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
#include "topshim/hf_client/hf_client_shim.h"

#include "src/profiles/hf_client.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

namespace internal {

// Singleton instance of HfClientIntf
static HfClientIntf* g_hf_client_if;

static void connection_state_cb(RawAddress addr, bthf_client_connection_state_t state,
                                unsigned int peer_feat, unsigned int chld_feat) {
  rusty::hf_client_connection_state_cb(addr, state, peer_feat, chld_feat);
}

static void audio_state_cb(RawAddress addr, bthf_client_audio_state_t state) {
  rusty::hf_client_audio_state_cb(addr, state);
}

// Remaining callbacks are unused, so leave as no-ops
static void vr_cmd_cb(RawAddress bd_addr, bthf_client_vr_state_t state) {}
static void network_state_cb(RawAddress bd_addr, bthf_client_network_state_t state) {}
static void network_roaming_cb(RawAddress bd_addr, bthf_client_service_type_t type) {}
static void network_signal_cb(RawAddress bd_addr, int signal_strength) {}
static void battery_level_cb(RawAddress bd_addr, int battery_level) {}
static void current_operator_cb(RawAddress bd_addr, const char* name) {}
static void call_cb(RawAddress bd_addr, bthf_client_call_t call) {}
static void callsetup_cb(RawAddress bd_addr, bthf_client_callsetup_t callsetup) {}
static void callheld_cb(RawAddress bd_addr, bthf_client_callheld_t callheld) {}
static void resp_and_hold_cb(RawAddress bd_addr, bthf_client_resp_and_hold_t resp_and_hold) {}
static void clip_cb(RawAddress bd_addr, const char* number) {}
static void call_waiting_cb(RawAddress bd_addr, const char* number) {}
static void current_calls_cb(RawAddress bd_addr, int index, bthf_client_call_direction_t dir,
                             bthf_client_call_state_t state, bthf_client_call_mpty_type_t mpty,
                             const char* number) {}
static void volume_change_cb(RawAddress bd_addr, bthf_client_volume_type_t type, int volume) {}
static void cmd_complete_cb(RawAddress bd_addr, bthf_client_cmd_complete_t type, int cme) {}
static void subscriber_info_cb(RawAddress bd_addr, const char* name,
                               bthf_client_subscriber_service_type_t type) {}
static void in_band_ring_tone_cb(RawAddress bd_addr, bthf_client_in_band_ring_state_t state) {}
static void last_voice_tag_number_callback(RawAddress bd_addr, const char* number) {}
static void ring_indication_cb(RawAddress bd_addr) {}
static void unknown_event_cb(RawAddress bd_addr, const char* unknown_event) {}

bthf_client_callbacks_t hf_client_callbacks = {
        sizeof(bthf_client_callbacks_t),
        connection_state_cb,
        audio_state_cb,
        vr_cmd_cb,
        network_state_cb,
        network_roaming_cb,
        network_signal_cb,
        battery_level_cb,
        current_operator_cb,
        call_cb,
        callsetup_cb,
        callheld_cb,
        resp_and_hold_cb,
        clip_cb,
        call_waiting_cb,
        current_calls_cb,
        volume_change_cb,
        cmd_complete_cb,
        subscriber_info_cb,
        in_band_ring_tone_cb,
        last_voice_tag_number_callback,
        ring_indication_cb,
        unknown_event_cb,
};

}  // namespace internal

tBT_STATUS_LEGACY HfClientIntf::init() const {
  return toLegacyStatus(hf_client_intf_->init(&internal::hf_client_callbacks));
}

tBT_STATUS_LEGACY HfClientIntf::connect(RawAddress addr) const {
  return toLegacyStatus(hf_client_intf_->connect(addr));
}

tBT_STATUS_LEGACY HfClientIntf::disconnect(RawAddress addr) const {
  return toLegacyStatus(hf_client_intf_->disconnect(addr));
}

tBT_STATUS_LEGACY HfClientIntf::connect_audio(RawAddress addr) const {
  return toLegacyStatus(hf_client_intf_->connect_audio(addr));
}

tBT_STATUS_LEGACY HfClientIntf::disconnect_audio(RawAddress addr) const {
  return toLegacyStatus(hf_client_intf_->disconnect_audio(addr));
}

void HfClientIntf::cleanup() const { hf_client_intf_->cleanup(); }

std::unique_ptr<HfClientIntf> GetHfClientProfile(const BtIntf& intf) {
  if (internal::g_hf_client_if) {
    std::abort();
  }

  auto hf_client_if =
          std::make_unique<HfClientIntf>(reinterpret_cast<const bthf_client_interface_t*>(
                  intf.get_profile_interface(BT_PROFILE_HANDSFREE_CLIENT_ID)));
  internal::g_hf_client_if = hf_client_if.get();
  return hf_client_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
