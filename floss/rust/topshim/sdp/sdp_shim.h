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
#ifndef GD_RUST_TOPSHIM_SDP_SDP_SHIM_H
#define GD_RUST_TOPSHIM_SDP_SDP_SHIM_H

#include <bluetooth/types/uuid.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_sdp.h>

#include <memory>

#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// C++ Sdp Interface that matches the Rust Sdp FFI defined in /topshim/src/profiles/sdp.rs
// Provides a translation for the btsdp_interface_t defined in /system/btif/src/btif_sdp.cc
class SdpIntf {
public:
  SdpIntf(const btsdp_interface_t* sdp_intf) : sdp_intf_(sdp_intf) {}
  ~SdpIntf() = default;

  tBT_STATUS_LEGACY init() const;
  tBT_STATUS_LEGACY deinit() const;
  tBT_STATUS_LEGACY sdp_search(RawAddress addr, Uuid uuid) const;
  tBT_STATUS_LEGACY create_sdp_record(bluetooth_sdp_record record, int& record_handle) const;
  tBT_STATUS_LEGACY remove_sdp_record(int sdp_handle) const;

private:
  const btsdp_interface_t* sdp_intf_;
};

std::unique_ptr<SdpIntf> GetSdpProfile(const BtIntf& intf);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_SDP_SDP_SHIM_H
