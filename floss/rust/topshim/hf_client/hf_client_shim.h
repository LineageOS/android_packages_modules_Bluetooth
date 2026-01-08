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
#ifndef GD_RUST_TOPSHIM_HF_CLIENT_HF_CLIENT_SHIM_H
#define GD_RUST_TOPSHIM_HF_CLIENT_HF_CLIENT_SHIM_H

#include <bluetooth/types/ble_address_with_type.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_hf_client.h>

#include <memory>

#include "rust/cxx.h"
#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// C++ HfClient Interface that matches the Rust HfClient FFI defined in
// /topshim/src/profiles/hf_client.rs Provides a translation for the bthf_client_interface_t defined
// in /system/btif/src/btif_hf_client.cc
class HfClientIntf {
public:
  HfClientIntf(const bthf_client_interface_t* hf_client_intf) : hf_client_intf_(hf_client_intf) {}
  ~HfClientIntf() = default;

  tBT_STATUS_LEGACY init() const;
  tBT_STATUS_LEGACY connect(RawAddress addr) const;
  tBT_STATUS_LEGACY disconnect(RawAddress addr) const;
  tBT_STATUS_LEGACY connect_audio(RawAddress addr) const;
  tBT_STATUS_LEGACY disconnect_audio(RawAddress addr) const;
  void cleanup() const;

private:
  const bthf_client_interface_t* hf_client_intf_;
};

std::unique_ptr<HfClientIntf> GetHfClientProfile(const BtIntf& intf);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_HF_CLIENT_HF_CLIENT_SHIM_H
