/*
 * Copyright 2023 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:66
 *
 *  mockcify.pl ver 0.6.0
 */

#include <cstdint>
#include <functional>
#include <string>

// Original included files, if any
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/hci_role.h>

#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/security_client_callbacks.h"

// Original usings

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_btm_sec {

// Name: BTM_CanReadDiscoverableCharacteristics
// Params: const RawAddress& bd_addr
// Return: bool
struct BTM_CanReadDiscoverableCharacteristics {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct BTM_CanReadDiscoverableCharacteristics BTM_CanReadDiscoverableCharacteristics;

// Name: btm_confirm_req_reply
// Params: tBTM_STATUS res, const RawAddress& bd_addr
// Return: void
struct btm_confirm_req_reply {
  std::function<void(tBTM_STATUS res, const RawAddress& bd_addr)> body{
          [](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */) {}};
  void operator()(tBTM_STATUS res, const RawAddress& bd_addr) { body(res, bd_addr); }
};
extern struct btm_confirm_req_reply btm_confirm_req_reply;

// Name: btm_is_authenticated
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: bool
struct btm_is_authenticated {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_is_authenticated btm_is_authenticated;

// Name: btm_is_encrypted
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: bool
struct btm_is_encrypted {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_is_encrypted btm_is_encrypted;

// Name: btm_is_link_key_authed
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: bool
struct btm_is_link_key_authed {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_is_link_key_authed btm_is_link_key_authed;

// Name: btm_is_bonded
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: bool
struct btm_is_bonded {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_is_bonded btm_is_bonded;

// Name: btm_pin_code_reply
// Params: const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len, PinCode pin_code
// p_pin Return: void
struct btm_pin_code_reply {
  std::function<void(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len, PinCode pin_code)>
          body{[](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */, uint8_t /* pin_len */,
                  PinCode /* pin_code */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len, PinCode pin_code) {
    body(bd_addr, res, pin_len, pin_code);
  }
};
extern struct btm_pin_code_reply btm_pin_code_reply;

// Name: btm_passkey_req_reply
// Params: tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey
// Return: void
struct btm_passkey_req_reply {
  std::function<void(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey)> body{
          [](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */, uint32_t /* passkey */) {}};
  void operator()(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey) {
    body(res, bd_addr, passkey);
  }
};
extern struct btm_passkey_req_reply btm_passkey_req_reply;

// Name: btm_peer_supports_secure_connections
// Params: const RawAddress& bd_addr
// Return: bool
struct btm_peer_supports_secure_connections {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_peer_supports_secure_connections btm_peer_supports_secure_connections;

// Name: btm_read_local_oob_data
// Params: void
// Return: void
struct btm_read_local_oob_data {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btm_read_local_oob_data btm_read_local_oob_data;

// Name: btm_remote_oob_data_reply
// Params: tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c, const
// Octet16& r Return: void
struct btm_remote_oob_data_reply {
  std::function<void(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                     const Octet16& r)>
          body{[](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */, const Octet16& /* c */,
                  const Octet16& /* r */) {}};
  void operator()(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c, const Octet16& r) {
    body(res, bd_addr, c, r);
  }
};
extern struct btm_remote_oob_data_reply btm_remote_oob_data_reply;

// Name: btm_sec_bond
// Params: const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT
// transport
// Return: tBTM_STATUS
struct btm_sec_bond {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                            tBT_TRANSPORT transport)>
          body{[](const RawAddress& /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
                  tBT_TRANSPORT /* transport */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBT_TRANSPORT transport) {
    return body(bd_addr, addr_type, transport);
  }
};
extern struct btm_sec_bond btm_sec_bond;

// Name: btm_sec_bond_cancel
// Params: const RawAddress& bd_addr
// Return: tBTM_STATUS
struct btm_sec_bond_cancel {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_sec_bond_cancel btm_sec_bond_cancel;

// Name: btm_sec_clr_service
// Params: uint8_t service_id
// Return: uint8_t
struct btm_sec_clr_service {
  static uint8_t return_value;
  std::function<uint8_t(uint8_t service_id)> body{
          [](uint8_t /* service_id */) { return return_value; }};
  uint8_t operator()(uint8_t service_id) { return body(service_id); }
};
extern struct btm_sec_clr_service btm_sec_clr_service;

// Name: btm_sec_clr_service_by_psm
// Params: uint16_t psm
// Return: uint8_t
struct btm_sec_clr_service_by_psm {
  static uint8_t return_value;
  std::function<uint8_t(uint16_t psm)> body{[](uint16_t /* psm */) { return return_value; }};
  uint8_t operator()(uint16_t psm) { return body(psm); }
};
extern struct btm_sec_clr_service_by_psm btm_sec_clr_service_by_psm;

// Name: btm_sec_get_device_link_key_type
// Params: const RawAddress& bd_addr
// Return: tBTM_LINK_KEY_TYPE
struct btm_sec_get_device_link_key_type {
  static tBTM_LINK_KEY_TYPE return_value;
  std::function<tBTM_LINK_KEY_TYPE(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  tBTM_LINK_KEY_TYPE operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_sec_get_device_link_key_type btm_sec_get_device_link_key_type;

// Name: btm_sec_is_le_security_pending
// Params: const RawAddress& bd_addr
// Return: bool
struct btm_sec_is_le_security_pending {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_sec_is_le_security_pending btm_sec_is_le_security_pending;

// Name: btm_sec_register
// Params: const BtmAppReg& app_reg
// Return: bool
struct btm_sec_register {
  static bool return_value;
  std::function<bool(const BtmAppReg& app_reg)> body{
          [](const BtmAppReg& /* app_reg */) { return return_value; }};
  bool operator()(const BtmAppReg& app_reg) { return body(app_reg); }
};
extern struct btm_sec_register btm_sec_register;

// Name: btm_set_encryption
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport,
// tBTM_SEC_CALLBACK* p_callback, void* p_ref_data, tBTM_BLE_SEC_ACT sec_act
// Return: tBTM_STATUS
struct btm_set_encryption {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                            tBTM_BLE_SEC_ACT sec_act)>
          body{[](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */,
                  tBTM_SEC_CALLBACK* /* p_callback */, void* /* p_ref_data */,
                  tBTM_BLE_SEC_ACT /* sec_act */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                         tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                         tBTM_BLE_SEC_ACT sec_act) {
    return body(bd_addr, transport, p_callback, p_ref_data, sec_act);
  }
};
extern struct btm_set_encryption btm_set_encryption;

// Name: btm_set_pin_type
// Params: uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len
// Return: void
struct btm_set_pin_type {
  std::function<void(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len)> body{
          [](uint8_t /* pin_type */, PinCode /* pin_code */, uint8_t /* pin_code_len */) {}};
  void operator()(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len) {
    body(pin_type, pin_code, pin_code_len);
  }
};
extern struct btm_set_pin_type btm_set_pin_type;

// Name: btm_set_security_level
// Params: bool outgoing, const char* p_name, uint8_t service_id, uint16_t
// sec_level, uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id Return:
// bool
struct btm_set_security_level {
  static bool return_value;
  std::function<bool(bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
                     uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id)>
          body{[](bool /* outgoing */, const char* /* p_name */, uint8_t /* service_id */,
                  uint16_t /* sec_level */, uint16_t /* psm */, uint32_t /* mx_proto_id */,
                  uint32_t /* mx_chan_id */) { return return_value; }};
  bool operator()(bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
                  uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id) {
    return body(outgoing, p_name, service_id, sec_level, psm, mx_proto_id, mx_chan_id);
  }
};
extern struct btm_set_security_level btm_set_security_level;

// Name: BTM_update_version_info
// Params: const RawAddress& bd_addr, const remote_version_info&
// remote_version_info Return: void
struct BTM_update_version_info {
  std::function<void(const RawAddress& bd_addr, const remote_version_info& remote_version_info)>
          body{[](const RawAddress& /* bd_addr */,
                  const remote_version_info& /* remote_version_info */) {}};
  void operator()(const RawAddress& bd_addr, const remote_version_info& remote_version_info) {
    body(bd_addr, remote_version_info);
  }
};
extern struct BTM_update_version_info BTM_update_version_info;

// Name: btm_create_conn_cancel_complete
// Params: uint8_t status, const RawAddress& bd_addr
// Return: void
struct btm_create_conn_cancel_complete {
  std::function<void(uint8_t status, const RawAddress& bd_addr)> body{
          [](uint8_t /* status */, const RawAddress& /* bd_addr */) {}};
  void operator()(uint8_t status, const RawAddress& bd_addr) { body(status, bd_addr); }
};
extern struct btm_create_conn_cancel_complete btm_create_conn_cancel_complete;

// Name: btm_get_dev_class
// Params: const RawAddress& bda
// Return: DEV_CLASS
struct btm_get_dev_class {
  static DEV_CLASS return_value;
  std::function<DEV_CLASS(const RawAddress& bda)> body{
          [](const RawAddress& /* bda */) { return return_value; }};
  DEV_CLASS operator()(const RawAddress& bda) { return body(bda); }
};
extern struct btm_get_dev_class btm_get_dev_class;

// Name: btm_io_capabilities_req
// Params: const RawAddress& p
// Return: void
struct btm_io_capabilities_req {
  std::function<void(const RawAddress& p)> body{[](const RawAddress& /* p */) {}};
  void operator()(const RawAddress& p) { body(p); }
};
extern struct btm_io_capabilities_req btm_io_capabilities_req;

// Name: btm_io_capabilities_rsp
// Params: tBTM_SP_IO_RSP evt_data
// Return: void
struct btm_io_capabilities_rsp {
  std::function<void(const tBTM_SP_IO_RSP evt_data)> body{
          [](const tBTM_SP_IO_RSP /* evt_data */) {}};
  void operator()(const tBTM_SP_IO_RSP evt_data) { body(evt_data); }
};
extern struct btm_io_capabilities_rsp btm_io_capabilities_rsp;

// Name: btm_proc_sp_req_evt
// Params: tBTM_SP_EVT event, const uint8_t* p
// Return: void
struct btm_proc_sp_req_evt {
  std::function<void(tBTM_SP_EVT event, const RawAddress& bda, uint32_t value)> body{
          [](tBTM_SP_EVT /* event */, const RawAddress& /* bda */, uint32_t /* value */) {}};
  void operator()(tBTM_SP_EVT event, const RawAddress& bda, uint32_t value) {
    body(event, bda, value);
  }
};
extern struct btm_proc_sp_req_evt btm_proc_sp_req_evt;

// Name: btm_read_local_oob_complete
// Params:
// tBTM_SP_LOC_OOB evt_data;
// uint8_t status;
// Return: void
struct btm_read_local_oob_complete {
  std::function<void(const tBTM_SP_LOC_OOB evt_data)> body{
          [](const tBTM_SP_LOC_OOB /* evt_data */) {}};
  void operator()(const tBTM_SP_LOC_OOB evt_data) { body(evt_data); }
};
extern struct btm_read_local_oob_complete btm_read_local_oob_complete;

// Name: btm_rem_oob_req
// Params: const RawAddress& bda
// Return: void
struct btm_rem_oob_req {
  std::function<void(const RawAddress& bda)> body{[](const RawAddress& /* bda */) {}};
  void operator()(const RawAddress& bda) { body(bda); }
};
extern struct btm_rem_oob_req btm_rem_oob_req;

// Name: btm_sec_abort_access_req
// Params: const RawAddress& bd_addr
// Return: void
struct btm_sec_abort_access_req {
  std::function<void(const RawAddress& bd_addr)> body{[](const RawAddress& /* bd_addr */) {}};
  void operator()(const RawAddress& bd_addr) { body(bd_addr); }
};
extern struct btm_sec_abort_access_req btm_sec_abort_access_req;

// Name: btm_sec_auth_complete
// Params: uint16_t handle, tHCI_STATUS status
// Return: void
struct btm_sec_auth_complete {
  std::function<void(uint16_t handle, tHCI_STATUS status)> body{
          [](uint16_t /* handle */, tHCI_STATUS /* status */) {}};
  void operator()(uint16_t handle, tHCI_STATUS status) { body(handle, status); }
};
extern struct btm_sec_auth_complete btm_sec_auth_complete;

// Name: btm_sec_bond_by_transport
// Params: const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT
// transport, uint8_t pin_len, uint8_t* p_pin Return: tBTM_STATUS
struct btm_sec_bond_by_transport {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                            tBT_TRANSPORT transport)>
          body{[](const RawAddress& /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
                  tBT_TRANSPORT /* transport */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBT_TRANSPORT transport) {
    return body(bd_addr, addr_type, transport);
  }
};
extern struct btm_sec_bond_by_transport btm_sec_bond_by_transport;

// Name: btm_sec_clear_ble_keys
// Params: BtmDevice* p_device
// Return: void
struct btm_sec_clear_ble_keys {
  std::function<void(BtmDevice* p_device)> body{[](BtmDevice* /* p_device */) {}};
  void operator()(BtmDevice* p_device) { body(p_device); }
};
extern struct btm_sec_clear_ble_keys btm_sec_clear_ble_keys;

// Name: btm_sec_conn_req
// Params: const RawAddress& bda, const DEV_CLASS dc
// Return: void
struct btm_sec_conn_req {
  std::function<void(const RawAddress& bda, const DEV_CLASS dc)> body{
          [](const RawAddress& /* bda */, const DEV_CLASS /* dc */) {}};
  void operator()(const RawAddress& bda, const DEV_CLASS dc) { body(bda, dc); }
};
extern struct btm_sec_conn_req btm_sec_conn_req;

// Name: btm_sec_connected
// Params: const RawAddress& bda, uint16_t handle, tHCI_STATUS status, uint8_t
// enc_mode, tHCI_ROLE assigned_role Return: void
struct btm_sec_connected {
  std::function<void(const RawAddress& bda, uint16_t handle, tHCI_STATUS status, uint8_t enc_mode,
                     bool locally_initiated, tHCI_ROLE assigned_role)>
          body{[](const RawAddress& /* bda */, uint16_t /* handle */, tHCI_STATUS /* status */,
                  uint8_t /* enc_mode */, bool /* locally_initiated */,
                  tHCI_ROLE /* assigned_role */) {}};
  void operator()(const RawAddress& bda, uint16_t handle, tHCI_STATUS status, uint8_t enc_mode,
                  bool locally_initiated, tHCI_ROLE assigned_role) {
    body(bda, handle, status, enc_mode, locally_initiated, assigned_role);
  }
};
extern struct btm_sec_connected btm_sec_connected;

// Name: btm_sec_cr_loc_oob_data_cback_event
// Params: const RawAddress& address, tSMP_LOC_OOB_DATA loc_oob_data
// Return: void
struct btm_sec_cr_loc_oob_data_cback_event {
  std::function<void(const RawAddress& address, tSMP_LOC_OOB_DATA loc_oob_data)> body{
          [](const RawAddress& /* address */, tSMP_LOC_OOB_DATA /* loc_oob_data */) {}};
  void operator()(const RawAddress& address, tSMP_LOC_OOB_DATA loc_oob_data) {
    body(address, loc_oob_data);
  }
};
extern struct btm_sec_cr_loc_oob_data_cback_event btm_sec_cr_loc_oob_data_cback_event;

// Name: btm_sec_dev_rec_cback_event
// Params: BtmDevice* p_device, tBTM_STATUS btm_status, bool
// is_le_transport Return: void
struct btm_sec_dev_rec_cback_event {
  std::function<void(BtmDevice* p_device, tBTM_STATUS btm_status, bool is_le_transport)> body{
          [](BtmDevice* /* p_device */, tBTM_STATUS /* btm_status */, bool /* is_le_transport */) {
          }};
  void operator()(BtmDevice* p_device, tBTM_STATUS btm_status, bool is_le_transport) {
    body(p_device, btm_status, is_le_transport);
  }
};
extern struct btm_sec_dev_rec_cback_event btm_sec_dev_rec_cback_event;

// Name: btm_sec_dev_reset
// Params: void
// Return: void
struct btm_sec_dev_reset {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btm_sec_dev_reset btm_sec_dev_reset;

// Name: btm_sec_disconnect
// Params: uint16_t handle, tHCI_STATUS reason, std::string comment
// Return: tBTM_STATUS
struct btm_sec_disconnect {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(uint16_t handle, tHCI_STATUS reason, std::string comment)> body{
          [](uint16_t /* handle */, tHCI_STATUS /* reason */, std::string /* comment */) {
            return return_value;
          }};
  tBTM_STATUS operator()(uint16_t handle, tHCI_STATUS reason, std::string comment) {
    return body(handle, reason, comment);
  }
};
extern struct btm_sec_disconnect btm_sec_disconnect;

// Name: btm_sec_disconnected
// Params: uint16_t handle, tHCI_REASON reason, std::string comment
// Return: void
struct btm_sec_disconnected {
  std::function<void(uint16_t handle, tHCI_REASON reason, std::string comment)> body{
          [](uint16_t /* handle */, tHCI_REASON /* reason */, std::string /* comment */) {}};
  void operator()(uint16_t handle, tHCI_REASON reason, std::string comment) {
    body(handle, reason, comment);
  }
};
extern struct btm_sec_disconnected btm_sec_disconnected;

// Name: btm_sec_encrypt_change
// Params: uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size, bool
// from_key_refresh Return: void
struct btm_sec_encrypt_change {
  std::function<void(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size,
                     bool from_key_refresh)>
          body{[](uint16_t /* handle */, tHCI_STATUS /* status */, uint8_t /* encr_enable */,
                  uint8_t /* key_size */, bool /* from_key_refresh */) {}};
  void operator()(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size,
                  bool from_key_refresh) {
    body(handle, status, encr_enable, key_size, from_key_refresh);
  }
};
extern struct btm_sec_encrypt_change btm_sec_encrypt_change;

// Name: btm_sec_encryption_change_evt
// Params: uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size
// Return: void
struct btm_sec_encryption_change_evt {
  std::function<void(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size)>
          body{[](uint16_t /* handle */, tHCI_STATUS /* status */, uint8_t /* encr_enable */,
                  uint8_t /* key_size */) {}};
  void operator()(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable, uint8_t key_size) {
    body(handle, status, encr_enable, key_size);
  }
};
extern struct btm_sec_encryption_change_evt btm_sec_encryption_change_evt;

// Name: btm_sec_l2cap_access_req
// Params: const RawAddress& bd_addr, uint16_t psm, bool outgoing,
// tBTM_SEC_CALLBACK* p_callback, void* p_ref_data Return: tBTM_STATUS
struct btm_sec_l2cap_access_req {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data)>
          body{[](const RawAddress& /* bd_addr */, uint16_t /* psm */, bool /* outgoing */,
                  tBTM_SEC_CALLBACK* /* p_callback */,
                  void* /* p_ref_data */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                         tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
    return body(bd_addr, psm, outgoing, p_callback, p_ref_data);
  }
};
extern struct btm_sec_l2cap_access_req btm_sec_l2cap_access_req;

// Name: btm_sec_l2cap_access_req_by_requirement
// Params: const RawAddress& bd_addr, uint16_t security_required, bool
// outgoing, tBTM_SEC_CALLBACK* p_callback, void* p_ref_data Return:
// tBTM_STATUS
struct btm_sec_l2cap_access_req_by_requirement {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, uint16_t security_required, bool outgoing,
                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data)>
          body{[](const RawAddress& /* bd_addr */, uint16_t /* security_required */,
                  bool /* outgoing */, tBTM_SEC_CALLBACK* /* p_callback */,
                  void* /* p_ref_data */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, uint16_t security_required, bool outgoing,
                         tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
    return body(bd_addr, security_required, outgoing, p_callback, p_ref_data);
  }
};
extern struct btm_sec_l2cap_access_req_by_requirement btm_sec_l2cap_access_req_by_requirement;

// Name: btm_sec_link_key_notification
// Params: const RawAddress& p_bda, const Octet16& link_key, uint8_t key_type
// Return: void
struct btm_sec_link_key_notification {
  std::function<void(const RawAddress& p_bda, const Octet16& link_key, uint8_t key_type)> body{
          [](const RawAddress& /* p_bda */, const Octet16& /* link_key */, uint8_t /* key_type */) {
          }};
  void operator()(const RawAddress& p_bda, const Octet16& link_key, uint8_t key_type) {
    body(p_bda, link_key, key_type);
  }
};
extern struct btm_sec_link_key_notification btm_sec_link_key_notification;

// Name: btm_sec_encryption_key_refresh_complete
// Params: uint16_t handle, tHCI_STATUS status
// Return: void
struct btm_sec_encryption_key_refresh_complete {
  std::function<void(uint16_t handle, tHCI_STATUS status)> body{
          [](uint16_t /* handle */, tHCI_STATUS /* status */) -> void {}};
  void operator()(uint16_t handle, tHCI_STATUS status) { body(handle, status); }
};
extern struct btm_sec_encryption_key_refresh_complete btm_sec_encryption_key_refresh_complete;

// Name: btm_sec_link_key_request
// Params: const uint8_t* p_event
// Return: void
struct btm_sec_link_key_request {
  std::function<void(const RawAddress& bda)> body{[](const RawAddress& /* bda */) {}};
  void operator()(const RawAddress& bda) { body(bda); }
};
extern struct btm_sec_link_key_request btm_sec_link_key_request;

// Name: btm_sec_service_access_request
// Params: const RawAddress& bd_addr, bool outgoing, uint16_t
// security_required, tBTM_SEC_CALLBACK* p_callback, void* p_ref_data Return:
// tBTM_STATUS
struct btm_sec_service_access_request {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, bool outgoing, uint16_t security_required,
                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data)>
          body{[](const RawAddress& /* bd_addr */, bool /* outgoing */,
                  uint16_t /* security_required */, tBTM_SEC_CALLBACK* /* p_callback */,
                  void* /* p_ref_data */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, bool outgoing, uint16_t security_required,
                         tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
    return body(bd_addr, outgoing, security_required, p_callback, p_ref_data);
  }
};
extern struct btm_sec_service_access_request btm_sec_service_access_request;

// Name: btm_sec_pin_code_request
// Params: const uint8_t* p_event
// Return: void
struct btm_sec_pin_code_request {
  std::function<void(const RawAddress& bda)> body{[](const RawAddress& /* bda */) {}};
  void operator()(const RawAddress& bda) { body(bda); }
};
extern struct btm_sec_pin_code_request btm_sec_pin_code_request;

// Name: btm_sec_rmt_host_support_feat_evt
// Params: const uint8_t* p
// Return: void
struct btm_sec_rmt_host_support_feat_evt {
  std::function<void(const RawAddress& bd_addr, uint8_t features_0)> body{
          [](const RawAddress& /* bd_addr */, uint8_t /* features_0 */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t features_0) { body(bd_addr, features_0); }
};
extern struct btm_sec_rmt_host_support_feat_evt btm_sec_rmt_host_support_feat_evt;

// Name: btm_sec_rmt_name_request_complete
// Params: const RawAddress* p_bd_addr, const uint8_t* p_bd_name, tHCI_STATUS
// status Return: void
struct btm_sec_rmt_name_request_complete {
  std::function<void(const RawAddress* p_bd_addr, const uint8_t* p_bd_name, tHCI_STATUS status)>
          body{[](const RawAddress* /* p_bd_addr */, const uint8_t* /* p_bd_name */,
                  tHCI_STATUS /* status */) {}};
  void operator()(const RawAddress* p_bd_addr, const uint8_t* p_bd_name, tHCI_STATUS status) {
    body(p_bd_addr, p_bd_name, status);
  }
};
extern struct btm_sec_rmt_name_request_complete btm_sec_rmt_name_request_complete;

// Name: btm_sec_role_changed
// Params: tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role
// Return: void
struct btm_sec_role_changed {
  std::function<void(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role)> body{
          [](tHCI_STATUS /* hci_status */, const RawAddress& /* bd_addr */,
             tHCI_ROLE /* new_role */) {}};
  void operator()(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role) {
    body(hci_status, bd_addr, new_role);
  }
};
extern struct btm_sec_role_changed btm_sec_role_changed;

// Name: btm_sec_set_peer_sec_caps
// Params: uint16_t hci_handle, bool ssp_supported, bool sc_supported, bool
// hci_role_switch_supported, bool br_edr_supported, bool le_supported Return:
// void
struct btm_sec_set_peer_sec_caps {
  std::function<void(uint16_t hci_handle, bool ssp_supported, bool host_sc_supported,
                     bool controller_sc_supported, bool hci_role_switch_supported,
                     bool br_edr_supported, bool le_supported)>
          body{[](uint16_t /* hci_handle */, bool /* ssp_supported */, bool /* host_sc_supported */,
                  bool /* controller_sc_supported */, bool /* hci_role_switch_supported */,
                  bool /* br_edr_supported */, bool /* le_supported */) {}};
  void operator()(uint16_t hci_handle, bool ssp_supported, bool host_sc_supported,
                  bool controller_sc_supported, bool hci_role_switch_supported,
                  bool br_edr_supported, bool le_supported) {
    body(hci_handle, ssp_supported, host_sc_supported, controller_sc_supported,
         hci_role_switch_supported, br_edr_supported, le_supported);
  }
};
extern struct btm_sec_set_peer_sec_caps btm_sec_set_peer_sec_caps;

// Name: btm_sec_update_clock_offset
// Params: uint16_t handle, uint16_t clock_offset
// Return: void
struct btm_sec_update_clock_offset {
  std::function<void(uint16_t handle, uint16_t clock_offset)> body{
          [](uint16_t /* handle */, uint16_t /* clock_offset */) {}};
  void operator()(uint16_t handle, uint16_t clock_offset) { body(handle, clock_offset); }
};
extern struct btm_sec_update_clock_offset btm_sec_update_clock_offset;

// Name: btm_simple_pair_complete
// Params: RawAddress bd_addr, uint8_t status
// Return: void
struct btm_simple_pair_complete {
  std::function<void(const RawAddress& bd_addr, uint8_t status)> body{
          [](const RawAddress& /* bd_addr */, uint8_t /* status */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t status) { body(bd_addr, status); }
};
extern struct btm_simple_pair_complete btm_simple_pair_complete;

// Name: btm_is_bond_lost
// Params: RawAddress bd_addr
// Return: bool
struct btm_is_bond_lost {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_is_bond_lost btm_is_bond_lost;

// Name: btm_update_bond_lost
// Params: RawAddress bd_addr, bool bond_lost
// Return: void
struct btm_update_bond_lost {
  std::function<void(const RawAddress& bd_addr, bool bond_lost)> body{
          [](const RawAddress& /* bd_addr */, bool /* bond_lost */) {}};
  void operator()(const RawAddress& bd_addr, bool bond_lost) { body(bd_addr, bond_lost); }
};
extern struct btm_update_bond_lost btm_update_bond_lost;

// Name: BTM_IsRemoteNameKnown
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: bool
struct BTM_IsRemoteNameKnown {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct BTM_IsRemoteNameKnown BTM_IsRemoteNameKnown;

// Name: is_autonomous_repairing_supported
// Params: void
// Return: bool
struct is_autonomous_repairing_supported {
  static bool return_value;
  std::function<bool(void)> body{[](void) { return return_value; }};
  bool operator()(void) { return body(); }
};
extern struct is_autonomous_repairing_supported is_autonomous_repairing_supported;

// Name: btm_get_security_mode
// Params: void
// Return: uint8_t
struct btm_get_security_mode {
  static uint8_t return_value;
  std::function<uint8_t(void)> body{[](void) { return return_value; }};
  uint8_t operator()(void) { return body(); }
};
extern struct btm_get_security_mode btm_get_security_mode;

// Name: btm_sec_report_bond_loss
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Return: tBTM_STATUS
struct btm_sec_report_bond_loss {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return return_value;
          }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_sec_report_bond_loss btm_sec_report_bond_loss;

// Name: btm_sec_hci_delete_stored_link_key
// Params: const RawAddress& bd_addr
// Return: void
struct btm_sec_hci_delete_stored_link_key {
  std::function<void(const RawAddress& bd_addr)> body{[](const RawAddress& /* bd_addr */) {}};
  void operator()(const RawAddress& bd_addr) { body(bd_addr); }
};
extern struct btm_sec_hci_delete_stored_link_key btm_sec_hci_delete_stored_link_key;

// Name: btm_sec_get_min_enc_key_size
// Params: void
// Return: uint8_t
struct btm_sec_get_min_enc_key_size {
  static uint8_t return_value;
  std::function<uint8_t(void)> body{[](void) { return return_value; }};
  uint8_t operator()(void) { return body(); }
};
extern struct btm_sec_get_min_enc_key_size btm_sec_get_min_enc_key_size;

}  // namespace stack_btm_sec
}  // namespace mock
}  // namespace test

// END mockcify generation
