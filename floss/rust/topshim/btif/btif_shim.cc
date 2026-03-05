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
#include "topshim/btif/btif_shim.h"

#include <bta/include/bta_api.h>
#include <btcore/include/hal_util.h>

#include "src/btif.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

// Helper functions
::rust::Slice<const bt_property_t> toRustSlice(int num_properties, bt_property_t* properties) {
  return ::rust::Slice<const bt_property_t>(properties, num_properties);
}

namespace internal {

// Singleton instance of BtIntf
static BtIntf* g_bt_if;

// Callbacks for bt_callbacks_t
static void adapter_state_cb(bt_state_t state) { rusty::adapter_state_cb(state); }

static void adapter_properties_cb(bt_status_t status, int num_properties,
                                  bt_property_t* properties) {
  rusty::adapter_properties_cb(status, toRustSlice(num_properties, properties));
}

static void remote_device_properties_cb(bt_status_t status, RawAddress bd_addr,
                                        uint8_t address_type, int num_properties,
                                        bt_property_t* properties) {
  rusty::remote_device_properties_cb(status, bd_addr, address_type,
                                     toRustSlice(num_properties, properties));
}

static void device_found_cb(int num_properties, bt_property_t* properties) {
  rusty::device_found_cb(toRustSlice(num_properties, properties));
}

static void discovery_state_cb(bt_discovery_state_t state) { rusty::discovery_state_cb(state); }

static void pin_request_cb(RawAddress remote_bd_addr, bt_bdname_t* bd_name, uint32_t cod,
                           bool min_16_digit, int pairing_algorithm) {
  std::string bd_name_str;
  if (bd_name) {
    const char* name = reinterpret_cast<const char*>(bd_name->name);
    size_t len = strnlen(name, sizeof(bd_name->name));
    bd_name_str = std::string(name, len);
  } else {
    bd_name_str = "";
  }
  rusty::pin_request_cb(remote_bd_addr, bd_name_str, cod, min_16_digit, pairing_algorithm);
}

static void ssp_request_cb(RawAddress remote_bd_addr, int transport, PairingVariant pairing_variant,
                           uint32_t pass_key, int pairing_algorithm) {
  rusty::ssp_request_cb(remote_bd_addr, transport, pairing_variant, pass_key, pairing_algorithm);
}

static void bond_state_cb(bt_status_t status, RawAddress remote_bd_addr, tBT_TRANSPORT transport,
                          bt_bond_state_t state, PairingType pairing_type, int fail_reason,
                          PairingInitiator pairing_initiator) {
  rusty::bond_state_cb(status, remote_bd_addr, transport, state, pairing_type, fail_reason,
                       pairing_initiator);
}

static void acl_state_cb(bt_status_t status, AclLinkSpec& link_spec, bt_acl_state_t state,
                         bt_hci_error_code_t hci_reason, bt_conn_direction_t direction,
                         uint16_t acl_handle) {
  rusty::acl_state_cb(status, link_spec, state, hci_reason, direction, acl_handle);
}

static void thread_evt_cb(bt_cb_thread_evt evt) { rusty::thread_evt_cb(evt); }

static void key_missing_cb(const RawAddress bd_addr, uint8_t reason) {
  rusty::key_missing_cb(bd_addr, reason);
}

// Null implementation of unused callbacks
static void address_consolidate_cb(RawAddress main_bd_addr, RawAddress secondary_bd_addr) {}
static void le_address_associate_cb(RawAddress main_bd_addr, RawAddress secondary_bd_addr,
                                    uint8_t identity_address_type) {}
static void dut_mode_recv_cb(uint16_t opcode, uint8_t* buf, uint8_t len) {}
static void le_test_mode_cb(bt_status_t status, uint16_t num_packets) {}
static void energy_info_cb(bt_activity_energy_info* energy_info, bt_uid_traffic_t* uid_data) {}
static void link_quality_report_cb(uint64_t timestamp, int report_id, int rssi, int snr,
                                   int retransmission_count, int packets_not_receive_count,
                                   int negative_acknowledgement_count) {}
static void generate_local_oob_data_cb(tBT_TRANSPORT transport, bt_oob_data_t oob_data) {}
static void switch_buffer_size_cb(bool is_low_latency_buffer_size) {}
static void switch_codec_cb(bool is_low_latency_buffer_size) {}
static void le_rand_cb(uint64_t random) {}
static void encryption_change_cb(const bt_encryption_change_evt encryption_change) {}

bt_callbacks_t bt_callbacks = {
        sizeof(bt_callbacks_t),
        adapter_state_cb,
        adapter_properties_cb,
        remote_device_properties_cb,
        device_found_cb,
        discovery_state_cb,
        pin_request_cb,
        ssp_request_cb,
        bond_state_cb,
        address_consolidate_cb,
        le_address_associate_cb,
        acl_state_cb,
        thread_evt_cb,
        dut_mode_recv_cb,
        le_test_mode_cb,
        energy_info_cb,
        link_quality_report_cb,
        generate_local_oob_data_cb,
        switch_buffer_size_cb,
        switch_codec_cb,
        le_rand_cb,
        key_missing_cb,
        encryption_change_cb,
};

static int acquire_wake_lock(const char* lock_name) { return 0; }
static int release_wake_lock(const char* lock_name) { return 0; }

bt_os_callouts_t bt_os_callouts = {
        sizeof(bt_os_callouts_t),
        acquire_wake_lock,
        release_wake_lock,
};

}  // namespace internal

::rust::Slice<const uint8_t> get_property_bytes(const bt_property_t& property) {
  if (property.val == nullptr || property.len <= 0) {
    return ::rust::Slice<const uint8_t>();  // Return an empty slice
  }
  return ::rust::Slice<const uint8_t>(static_cast<const uint8_t*>(property.val), property.len);
}

void BtIntf::set_adapter_index(int adapter_index) const { intf_->set_adapter_index(adapter_index); }

void BtIntf::bluetooth_init(bool guest_mode, bool is_common_criteria_mode,
                            int config_compare_result, bool is_atv,
                            ::rust::String hci_instance_name) const {
  ::bluetooth_init(&internal::bt_callbacks, guest_mode, is_common_criteria_mode,
                   config_compare_result, is_atv,
                   std::string(hci_instance_name.data(), hci_instance_name.size()),
                   &internal::bt_os_callouts, /* autonomous_repairing_initiation = */ false);
}

void BtIntf::bluetooth_enable(::rust::String local_name) const {
  return ::bluetooth_enable(std::string(local_name));
}

void BtIntf::bluetooth_disable() const { return ::bluetooth_disable(); }

void BtIntf::bluetooth_cleanup() const { return ::bluetooth_cleanup(); }

int BtIntf::get_adapter_property(bt_property_type_t type) const {
  return intf_->get_adapter_property(type);
}

void BtIntf::set_scan_mode(bt_scan_mode_t mode) const { return intf_->set_scan_mode(mode); }
void BtIntf::set_local_name(::rust::String local_name) const {
  BTA_DmSetDeviceName(std::string(local_name).c_str());
}

int BtIntf::set_adapter_property(bt_property_t property) const {
  return intf_->set_adapter_property(&property);
}

int BtIntf::set_remote_device_property(RawAddress remote_addr, bt_property_t property) const {
  return intf_->set_remote_device_property(remote_addr, &property);
}

int BtIntf::get_remote_services(RawAddress remote_addr, int transport) const {
  return intf_->get_remote_services(remote_addr, transport);
}

int BtIntf::start_discovery() const { return intf_->start_discovery(); }

int BtIntf::cancel_discovery() const { return intf_->cancel_discovery(); }

int BtIntf::create_bond(RawAddress bd_addr, int transport) const {
  return intf_->create_bond(bd_addr, transport);
}

int BtIntf::remove_bond(RawAddress bd_addr) const { return intf_->remove_bond(bd_addr); }

int BtIntf::cancel_bond(RawAddress bd_addr) const { return intf_->cancel_bond(bd_addr); }

bool BtIntf::pairing_is_busy() const { return intf_->pairing_is_busy(); }

int BtIntf::get_connection_state(RawAddress bd_addr) const {
  return intf_->get_connection_state(bd_addr);
}

int BtIntf::pin_reply(RawAddress bd_addr, uint8_t accept, uint8_t pin_len,
                      bt_pin_code_t& pin_code) const {
  return intf_->pin_reply(bd_addr, accept, pin_len, &pin_code);
}

int BtIntf::ssp_reply(RawAddress bd_addr, PairingVariant variant, uint8_t accept,
                      uint32_t passkey) const {
  return intf_->ssp_reply(bd_addr, variant, accept, passkey);
}

const void* BtIntf::get_profile_interface(const char* profile_id) const {
  return intf_->get_profile_interface(profile_id);
}

void BtIntf::dump(int fd) const { return intf_->dump(fd, nullptr); }

bluetooth::avrcp::ServiceInterface* BtIntf::get_avrcp_service() const {
  return intf_->get_avrcp_service();
}

int BtIntf::generate_local_oob_data(tBT_TRANSPORT transport) const {
  return intf_->generate_local_oob_data(transport);
}

int BtIntf::clear_event_filter() const { return intf_->clear_event_filter(); }

int BtIntf::clear_event_mask() const { return intf_->clear_event_mask(); }

int BtIntf::clear_filter_accept_list() const { return intf_->clear_filter_accept_list(); }

int BtIntf::disconnect_all_acls() const { return intf_->disconnect_all_acls(); }

int BtIntf::disconnect_acl(RawAddress bd_addr, int transport) const {
  return intf_->disconnect_acl(bd_addr, transport);
}

int BtIntf::le_rand() const { return intf_->le_rand(); }

int BtIntf::set_event_filter_inquiry_result_all_devices() const {
  return intf_->set_event_filter_inquiry_result_all_devices();
}

int BtIntf::set_default_event_mask_except(uint64_t mask, uint64_t le_mask) const {
  return intf_->set_default_event_mask_except(mask, le_mask);
}

int BtIntf::restore_filter_accept_list() const { return intf_->restore_filter_accept_list(); }

int BtIntf::allow_wake_by_hid() const { return intf_->allow_wake_by_hid(); }

int BtIntf::set_event_filter_connection_setup_all_devices() const {
  return intf_->set_event_filter_connection_setup_all_devices();
}

int BtIntf::set_suspend_state(bool suspend) const { return intf_->set_suspend_state(suspend); }

bool BtIntf::get_wbs_supported() const { return intf_->get_wbs_supported(); }

bool BtIntf::get_swb_supported() const { return intf_->get_swb_supported(); }

bool BtIntf::is_coding_format_supported(uint8_t coding_format) const {
  return intf_->is_coding_format_supported(coding_format);
}

std::unique_ptr<BtIntf> GetBtIntf() {
  if (internal::g_bt_if) {
    std::abort();
  }

  const bt_interface_t* sBluetoothInterface = NULL;
  if (hal_util_load_bt_library(&sBluetoothInterface)) {
    log::error("No Bluetooth Library found");
    std::abort();
  }

  auto bt_if = std::make_unique<BtIntf>(sBluetoothInterface);
  internal::g_bt_if = bt_if.get();
  return bt_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
