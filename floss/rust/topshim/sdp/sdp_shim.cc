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
#include "topshim/sdp/sdp_shim.h"

#include "src/profiles/sdp.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

namespace internal {

// Singleton instance of SdpIntf
static SdpIntf* g_sdp_if;

static void sdp_search_cb(BtStatus status, const RawAddress& bd_addr, const Uuid& uuid,
                          int num_records, bluetooth_sdp_record* records) {
  rusty::sdp_search_cb(toLegacyStatus(status), bd_addr, uuid, num_records, *records);
}

btsdp_callbacks_t sdp_callbacks = {
        sizeof(btsdp_callbacks_t),
        sdp_search_cb,
};

}  // namespace internal

tBT_STATUS_LEGACY SdpIntf::init() const {
  return toLegacyStatus(sdp_intf_->init(&internal::sdp_callbacks));
}

tBT_STATUS_LEGACY SdpIntf::deinit() const { return toLegacyStatus(sdp_intf_->deinit()); }
tBT_STATUS_LEGACY SdpIntf::sdp_search(RawAddress addr, Uuid uuid) const {
  return toLegacyStatus(sdp_intf_->sdp_search(addr, uuid));
}
tBT_STATUS_LEGACY SdpIntf::create_sdp_record(bluetooth_sdp_record record,
                                             int& record_handle) const {
  return toLegacyStatus(sdp_intf_->create_sdp_record(&record, &record_handle));
}
tBT_STATUS_LEGACY SdpIntf::remove_sdp_record(int sdp_handle) const {
  return toLegacyStatus(sdp_intf_->remove_sdp_record(sdp_handle));
}

std::unique_ptr<SdpIntf> GetSdpProfile(const BtIntf& intf) {
  if (internal::g_sdp_if) {
    std::abort();
  }

  auto sdp_if = std::make_unique<SdpIntf>(reinterpret_cast<const btsdp_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_SDP_CLIENT_ID)));
  internal::g_sdp_if = sdp_if.get();
  return sdp_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
