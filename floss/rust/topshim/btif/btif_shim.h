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
#ifndef GD_RUST_TOPSHIM_BTIF_BTIF_SHIM_H
#define GD_RUST_TOPSHIM_BTIF_BTIF_SHIM_H

#include <bluetooth/types/bt_transport.h>
#include <hardware/bluetooth.h>

#include <memory>

#include "rust/cxx.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// BluetoothProperty helper functions
::rust::Slice<const uint8_t> get_property_bytes(const bt_property_t& property);

// C++ Bluetooth Interface that matches the Rust BTIF FFI defined in /topshim/src/btif.rs
// Provides a translation for the bt_interface_t defined in /system/btif/src/bluetooth.cc
class BtIntf {
public:
  BtIntf(const bt_interface_t* intf) : intf_(intf) {}
  ~BtIntf() = default;

  void set_adapter_index(int adapter_index) const;
  void bluetooth_init(bool guest_mode, bool is_common_criteria_mode, int config_compare_result,
                      bool is_atv, const ::rust::String hci_instance_name) const;
  void bluetooth_enable(::rust::String local_name) const;
  void bluetooth_disable() const;
  void bluetooth_cleanup() const;
  int get_adapter_property(bt_property_type_t type) const;
  void set_scan_mode(bt_scan_mode_t mode) const;
  void set_local_name(::rust::String local_name) const;
  int set_adapter_property(bt_property_t property) const;
  int set_remote_device_property(RawAddress remote_addr, bt_property_t property) const;
  int get_remote_services(RawAddress remote_addr, int transport) const;
  int start_discovery() const;
  int cancel_discovery() const;
  int create_bond(RawAddress bd_addr, int transport) const;
  int remove_bond(RawAddress bd_addr) const;
  int cancel_bond(RawAddress bd_addr) const;
  bool pairing_is_busy() const;
  int get_connection_state(RawAddress bd_addr) const;
  int pin_reply(RawAddress bd_addr, uint8_t accept, uint8_t pin_len, bt_pin_code_t& pin_code) const;
  int ssp_reply(RawAddress bd_addr, PairingVariant variant, uint8_t accept, uint32_t passkey) const;
  const void* get_profile_interface(const char* profile_id) const;
  void dump(int fd) const;
  bluetooth::avrcp::ServiceInterface* get_avrcp_service() const;
  int generate_local_oob_data(tBT_TRANSPORT transport) const;
  int clear_event_filter() const;
  int clear_event_mask() const;
  int clear_filter_accept_list() const;
  int disconnect_all_acls() const;
  int disconnect_acl(RawAddress bd_addr, int transport) const;
  int le_rand() const;
  int set_event_filter_inquiry_result_all_devices() const;
  int set_default_event_mask_except(uint64_t mask, uint64_t le_mask) const;
  int restore_filter_accept_list() const;
  int allow_wake_by_hid() const;
  int set_event_filter_connection_setup_all_devices() const;
  int set_suspend_state(bool suspend) const;
  bool get_wbs_supported() const;
  bool get_swb_supported() const;
  bool is_coding_format_supported(uint8_t coding_format) const;

private:
  const bt_interface_t* intf_;
};

std::unique_ptr<BtIntf> GetBtIntf();

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_BTIF_BTIF_SHIM_H
