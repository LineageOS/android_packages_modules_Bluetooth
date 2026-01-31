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
#include "topshim/hh/hh_shim.h"

#include "src/profiles/hid_host.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

namespace internal {

// Singleton instance of HhIntf
static HhIntf* g_hh_if;

static void connection_state_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                                bthh_connection_state_t state, bthh_status_t hh_status) {
  rusty::connection_state_cb(addr, addr_type, transport, state, hh_status);
}

static void hid_info_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                        bthh_hid_info_t hid_info) {
  rusty::hid_info_cb(addr, addr_type, transport, hid_info);
}

static void protocol_mode_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                             bthh_status_t hh_status, bthh_protocol_mode_t mode) {
  rusty::protocol_mode_cb(addr, addr_type, transport, hh_status, mode);
}

static void idle_time_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                         bthh_status_t hh_status, int idle_rate) {
  rusty::idle_time_cb(addr, addr_type, transport, hh_status, idle_rate);
}
static void get_report_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                          bthh_status_t hh_status, uint8_t* rpt_data, int rpt_size) {
  rusty::get_report_cb(addr, addr_type, transport, hh_status, rpt_data, rpt_size);
}
static void virtual_unplug_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                              bthh_status_t hh_status) {
  rusty::virtual_unplug_cb(addr, addr_type, transport, hh_status);
}
static void handshake_cb(RawAddress addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                         bthh_status_t hh_status) {
  rusty::handshake_cb(addr, addr_type, transport, hh_status);
}

bthh_callbacks_t hh_callbacks = {
        sizeof(bthh_callbacks_t), connection_state_cb, hid_info_cb,
        protocol_mode_cb,         idle_time_cb,        get_report_cb,
        virtual_unplug_cb,        handshake_cb,
};

}  // namespace internal

tBT_STATUS_LEGACY HhIntf::init() const {
  return toLegacyStatus(hh_intf_->init(&internal::hh_callbacks));
}

tBT_STATUS_LEGACY HhIntf::connect(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                  tBT_TRANSPORT transport, bool direct) const {
  return toLegacyStatus(hh_intf_->connect(addr, addr_type, transport, direct));
}

tBT_STATUS_LEGACY HhIntf::disconnect(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                     tBT_TRANSPORT transport,
                                     bthh_reconnect_policy_t reconnect_policy) const {
  return toLegacyStatus(hh_intf_->disconnect(addr, addr_type, transport, reconnect_policy));
}

tBT_STATUS_LEGACY HhIntf::virtual_unplug(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                         tBT_TRANSPORT transport) const {
  return toLegacyStatus(hh_intf_->virtual_unplug(addr, addr_type, transport));
}

tBT_STATUS_LEGACY HhIntf::get_idle_time(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                        tBT_TRANSPORT transport) const {
  return toLegacyStatus(hh_intf_->get_idle_time(addr, addr_type, transport));
}

tBT_STATUS_LEGACY HhIntf::set_idle_time(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                        tBT_TRANSPORT transport, uint8_t idle_time) const {
  return toLegacyStatus(hh_intf_->set_idle_time(addr, addr_type, transport, idle_time));
}

tBT_STATUS_LEGACY HhIntf::set_info(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                   tBT_TRANSPORT transport, bthh_hid_info_t hid_info) const {
  return toLegacyStatus(hh_intf_->set_info(addr, addr_type, transport, hid_info));
}

tBT_STATUS_LEGACY HhIntf::get_protocol(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                       tBT_TRANSPORT transport,
                                       bthh_protocol_mode_t protocol_mode) const {
  return toLegacyStatus(hh_intf_->get_protocol(addr, addr_type, transport, protocol_mode));
}

tBT_STATUS_LEGACY HhIntf::set_protocol(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                       tBT_TRANSPORT transport,
                                       bthh_protocol_mode_t protocol_mode) const {
  return toLegacyStatus(hh_intf_->set_protocol(addr, addr_type, transport, protocol_mode));
}

tBT_STATUS_LEGACY HhIntf::get_report(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                     tBT_TRANSPORT transport, bthh_report_type_t report_type,
                                     uint8_t report_id, int buffer_size) const {
  return toLegacyStatus(
          hh_intf_->get_report(addr, addr_type, transport, report_type, report_id, buffer_size));
}

tBT_STATUS_LEGACY HhIntf::get_report_reply(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                           tBT_TRANSPORT transport, bthh_status_t status,
                                           ::rust::Vec<uint8_t> report, uint16_t size) const {
  return toLegacyStatus(hh_intf_->get_report_reply(addr, addr_type, transport, status,
                                                   reinterpret_cast<char*>(report.data()), size));
}

tBT_STATUS_LEGACY HhIntf::set_report(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                     tBT_TRANSPORT transport, bthh_report_type_t report_type,
                                     ::rust::Vec<uint8_t> report) const {
  return toLegacyStatus(hh_intf_->set_report(addr, addr_type, transport, report_type,
                                             reinterpret_cast<char*>(report.data())));
}

tBT_STATUS_LEGACY HhIntf::send_data(RawAddress addr, tBLE_ADDR_TYPE addr_type,
                                    tBT_TRANSPORT transport, ::rust::Vec<uint8_t> data) const {
  return toLegacyStatus(
          hh_intf_->send_data(addr, addr_type, transport, reinterpret_cast<char*>(data.data())));
}

void HhIntf::cleanup() const { hh_intf_->cleanup(); }

void HhIntf::configure_enabled_profiles(bool enable_hidp, bool enable_hogp) const {
  hh_intf_->configure_enabled_profiles(enable_hidp, enable_hogp);
}

std::unique_ptr<HhIntf> GetHhProfile(const BtIntf& intf) {
  if (internal::g_hh_if) {
    std::abort();
  }

  auto hh_if = std::make_unique<HhIntf>(reinterpret_cast<const bthh_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_HIDHOST_ID)));
  internal::g_hh_if = hh_if.get();
  return hh_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
