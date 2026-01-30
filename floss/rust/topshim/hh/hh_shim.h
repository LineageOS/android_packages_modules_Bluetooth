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
#ifndef GD_RUST_TOPSHIM_HH_HH_SHIM_H
#define GD_RUST_TOPSHIM_HH_HH_SHIM_H

#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_transport.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_hh.h>

#include <memory>

#include "rust/cxx.h"
#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// C++ HH Interface that matches the Rust HH FFI defined in /topshim/src/profiles/hid_host.rs
// Provides a translation for the bthh_interface_t defined in /system/btif/src/btif_hh.cc
class HhIntf {
public:
  HhIntf(const bthh_interface_t* hh_intf) : hh_intf_(hh_intf) {}
  ~HhIntf() = default;

  tBT_STATUS_LEGACY init() const;
  tBT_STATUS_LEGACY connect(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                            bool direct) const;
  tBT_STATUS_LEGACY disconnect(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                               bthh_reconnect_policy_t reconnect_policy) const;
  tBT_STATUS_LEGACY virtual_unplug(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                   tBT_TRANSPORT transport) const;
  tBT_STATUS_LEGACY get_idle_time(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                  tBT_TRANSPORT transport) const;
  tBT_STATUS_LEGACY set_idle_time(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                  tBT_TRANSPORT transport, uint8_t idle_time) const;
  tBT_STATUS_LEGACY set_info(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                             bthh_hid_info_t hid_info) const;
  tBT_STATUS_LEGACY get_protocol(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                                 bthh_protocol_mode_t protocol_mode) const;
  tBT_STATUS_LEGACY set_protocol(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                                 bthh_protocol_mode_t protocol_mode) const;
  tBT_STATUS_LEGACY get_report(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                               bthh_report_type_t report_type, uint8_t report_id,
                               int buffer_size) const;
  tBT_STATUS_LEGACY get_report_reply(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                     tBT_TRANSPORT transport, bthh_status_t status,
                                     ::rust::Vec<uint8_t> report, uint16_t size) const;
  tBT_STATUS_LEGACY set_report(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                               bthh_report_type_t report_type, ::rust::Vec<uint8_t> report) const;
  tBT_STATUS_LEGACY send_data(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                              ::rust::Vec<uint8_t> data) const;
  void cleanup() const;
  void configure_enabled_profiles(bool enable_hidp, bool enable_hogp) const;

private:
  const bthh_interface_t* hh_intf_;
};

std::unique_ptr<HhIntf> GetHhProfile(const BtIntf& intf);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_HH_HH_SHIM_H
