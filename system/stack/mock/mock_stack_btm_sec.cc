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
/*
 * Generated mock file from original source file
 *   Functions generated:66
 *
 *  mockcify.pl ver 0.6.0
 */
// Mock include file to share data between tests and mock
#include "stack/mock/mock_stack_btm_sec.h"

#include <bluetooth/types/address.h>

#include <cstdint>
#include <string>

#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_sec_utils.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "test/common/mock_functions.h"

// Original usings

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_btm_sec {

// Function state capture and return values, if needed
struct BTM_CanReadDiscoverableCharacteristics BTM_CanReadDiscoverableCharacteristics;
struct btm_confirm_req_reply btm_confirm_req_reply;
struct btm_is_authenticated btm_is_authenticated;
struct btm_is_encrypted btm_is_encrypted;
struct btm_is_link_key_authed btm_is_link_key_authed;
struct btm_is_bonded btm_is_bonded;
struct btm_pin_code_reply btm_pin_code_reply;
struct btm_passkey_req_reply btm_passkey_req_reply;
struct btm_peer_supports_secure_connections btm_peer_supports_secure_connections;
struct btm_read_local_oob_data btm_read_local_oob_data;
struct btm_remote_oob_data_reply btm_remote_oob_data_reply;
struct btm_sec_bond btm_sec_bond;
struct btm_sec_bond_cancel btm_sec_bond_cancel;
struct btm_sec_clr_service btm_sec_clr_service;
struct btm_sec_clr_service_by_psm btm_sec_clr_service_by_psm;
struct btm_sec_get_device_link_key_type btm_sec_get_device_link_key_type;
struct btm_sec_is_le_security_pending btm_sec_is_le_security_pending;
struct btm_sec_register btm_sec_register;
struct btm_set_encryption btm_set_encryption;
struct btm_set_pin_type btm_set_pin_type;
struct btm_set_security_level btm_set_security_level;
struct BTM_update_version_info BTM_update_version_info;
struct btm_create_conn_cancel_complete btm_create_conn_cancel_complete;
struct btm_get_dev_class btm_get_dev_class;
struct btm_io_capabilities_req btm_io_capabilities_req;
struct btm_io_capabilities_rsp btm_io_capabilities_rsp;
struct btm_proc_sp_req_evt btm_proc_sp_req_evt;
struct btm_read_local_oob_complete btm_read_local_oob_complete;
struct btm_rem_oob_req btm_rem_oob_req;
struct btm_sec_abort_access_req btm_sec_abort_access_req;
struct btm_sec_auth_complete btm_sec_auth_complete;
struct btm_sec_bond_by_transport btm_sec_bond_by_transport;
struct btm_sec_clear_ble_keys btm_sec_clear_ble_keys;
struct btm_sec_conn_req btm_sec_conn_req;
struct btm_sec_connected btm_sec_connected;
struct btm_sec_cr_loc_oob_data_cback_event btm_sec_cr_loc_oob_data_cback_event;
struct btm_sec_dev_rec_cback_event btm_sec_dev_rec_cback_event;
struct btm_sec_dev_reset btm_sec_dev_reset;
struct btm_sec_disconnect btm_sec_disconnect;
struct btm_sec_disconnected btm_sec_disconnected;
struct btm_sec_encrypt_change btm_sec_encrypt_change;
struct btm_sec_encryption_change_evt btm_sec_encryption_change_evt;
struct btm_sec_l2cap_access_req btm_sec_l2cap_access_req;
struct btm_sec_l2cap_access_req_by_requirement btm_sec_l2cap_access_req_by_requirement;
struct btm_sec_link_key_notification btm_sec_link_key_notification;
struct btm_sec_encryption_key_refresh_complete btm_sec_encryption_key_refresh_complete;
struct btm_sec_link_key_request btm_sec_link_key_request;
struct btm_sec_service_access_request btm_sec_service_access_request;
struct btm_sec_pin_code_request btm_sec_pin_code_request;
struct btm_sec_rmt_host_support_feat_evt btm_sec_rmt_host_support_feat_evt;
struct btm_sec_rmt_name_request_complete btm_sec_rmt_name_request_complete;
struct btm_sec_role_changed btm_sec_role_changed;
struct btm_sec_set_peer_sec_caps btm_sec_set_peer_sec_caps;
struct btm_sec_update_clock_offset btm_sec_update_clock_offset;
struct btm_simple_pair_complete btm_simple_pair_complete;
struct btm_is_bond_lost btm_is_bond_lost;
struct btm_update_bond_lost btm_update_bond_lost;
struct is_autonomous_repairing_supported is_autonomous_repairing_supported;
struct btm_get_security_mode btm_get_security_mode;
struct btm_sec_report_bond_loss btm_sec_report_bond_loss;
struct btm_sec_hci_delete_stored_link_key btm_sec_hci_delete_stored_link_key;
struct btm_sec_get_min_enc_key_size btm_sec_get_min_enc_key_size;

}  // namespace stack_btm_sec
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_btm_sec {

bool BTM_CanReadDiscoverableCharacteristics::return_value = false;
bool btm_is_authenticated::return_value = false;
bool btm_is_encrypted::return_value = false;
bool btm_is_link_key_authed::return_value = false;
bool btm_is_bonded::return_value = false;
bool btm_peer_supports_secure_connections::return_value = false;
tBTM_STATUS btm_sec_bond::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS btm_sec_bond_cancel::return_value = tBTM_STATUS::BTM_SUCCESS;
uint8_t btm_sec_clr_service::return_value = 0;
uint8_t btm_sec_clr_service_by_psm::return_value = 0;
tBTM_LINK_KEY_TYPE btm_sec_get_device_link_key_type::return_value = 0;
bool btm_sec_is_le_security_pending::return_value = false;
bool btm_sec_register::return_value = false;
tBTM_STATUS btm_set_encryption::return_value = tBTM_STATUS::BTM_SUCCESS;
bool btm_set_security_level::return_value = false;
DEV_CLASS btm_get_dev_class::return_value = kDevClassEmpty;
tBTM_STATUS btm_sec_bond_by_transport::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS btm_sec_disconnect::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS btm_sec_l2cap_access_req::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS btm_sec_l2cap_access_req_by_requirement::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS btm_sec_service_access_request::return_value = tBTM_STATUS::BTM_SUCCESS;
bool btm_is_bond_lost::return_value = false;
bool is_autonomous_repairing_supported::return_value = false;
uint8_t btm_get_security_mode::return_value = 0;
tBTM_STATUS btm_sec_report_bond_loss::return_value = tBTM_STATUS::BTM_SUCCESS;
uint8_t btm_sec_get_min_enc_key_size::return_value = MIN_KEY_SIZE_DEFAULT;

}  // namespace stack_btm_sec
}  // namespace mock
}  // namespace test

// Mocked functions, if any
bool BTM_CanReadDiscoverableCharacteristics(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::BTM_CanReadDiscoverableCharacteristics(bd_addr);
}
void btm_confirm_req_reply(tBTM_STATUS res, const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_confirm_req_reply(res, bd_addr);
}
bool btm_is_authenticated(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_is_authenticated(bd_addr, transport);
}
bool btm_is_encrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_is_encrypted(bd_addr, transport);
}
bool btm_is_link_key_authed(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_is_link_key_authed(bd_addr, transport);
}
bool btm_is_bonded(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_is_bonded(bd_addr, transport);
}
void btm_pin_code_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                        PinCode pin_code) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_pin_code_reply(bd_addr, res, pin_len, pin_code);
}
void btm_passkey_req_reply(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_passkey_req_reply(res, bd_addr, passkey);
}
bool btm_peer_supports_secure_connections(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_peer_supports_secure_connections(bd_addr);
}
void btm_read_local_oob_data(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_read_local_oob_data();
}
void btm_remote_oob_data_reply(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                               const Octet16& r) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_remote_oob_data_reply(res, bd_addr, c, r);
}
tBTM_STATUS btm_sec_bond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_bond(bd_addr, addr_type, transport);
}
tBTM_STATUS btm_sec_bond_cancel(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_bond_cancel(bd_addr);
}
uint8_t btm_sec_clr_service(uint8_t service_id) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_clr_service(service_id);
}
uint8_t btm_sec_clr_service_by_psm(uint16_t psm) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_clr_service_by_psm(psm);
}
tBTM_LINK_KEY_TYPE btm_sec_get_device_link_key_type(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_get_device_link_key_type(bd_addr);
}
bool btm_sec_is_le_security_pending(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_is_le_security_pending(bd_addr);
}
bool btm_sec_register(const BtmAppReg& app_reg) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_register(app_reg);
}
tBTM_STATUS btm_set_encryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                               tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                               tBTM_BLE_SEC_ACT sec_act) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_set_encryption(bd_addr, transport, p_callback, p_ref_data,
                                                       sec_act);
}
void btm_set_pin_type(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_set_pin_type(pin_type, pin_code, pin_code_len);
}
bool btm_set_security_level(bool outgoing, const char* p_name, uint8_t service_id,
                            uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                            uint32_t mx_chan_id) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_set_security_level(outgoing, p_name, service_id, sec_level,
                                                           psm, mx_proto_id, mx_chan_id);
}
void BTM_update_version_info(const RawAddress& bd_addr,
                             const remote_version_info& remote_version_info) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::BTM_update_version_info(bd_addr, remote_version_info);
}
void btm_create_conn_cancel_complete(uint8_t status, const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_create_conn_cancel_complete(status, bd_addr);
}
DEV_CLASS btm_get_dev_class(const RawAddress& bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_get_dev_class(bda);
}
void btm_io_capabilities_req(const RawAddress& p) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_io_capabilities_req(p);
}
void btm_io_capabilities_rsp(const tBTM_SP_IO_RSP evt_data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_io_capabilities_rsp(evt_data);
}
void btm_proc_sp_req_evt(tBTM_SP_EVT event, const RawAddress& bd_addr, uint32_t value) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_proc_sp_req_evt(event, bd_addr, value);
}
void btm_read_local_oob_complete(const tBTM_SP_LOC_OOB evt_data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_read_local_oob_complete(evt_data);
}
void btm_rem_oob_req(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_rem_oob_req(bd_addr);
}
void btm_sec_abort_access_req(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_abort_access_req(bd_addr);
}
void btm_sec_auth_complete(uint16_t handle, tHCI_STATUS status) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_auth_complete(handle, status);
}
tBTM_STATUS btm_sec_bond_by_transport(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                                      tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_bond_by_transport(bd_addr, addr_type, transport);
}
void btm_sec_clear_ble_keys(BtmDevice* p_device) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_clear_ble_keys(p_device);
}
void btm_sec_conn_req(const RawAddress& bda, const DEV_CLASS dc) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_conn_req(bda, dc);
}
void btm_sec_connected(const RawAddress& bda, uint16_t handle, tHCI_STATUS status, uint8_t enc_mode,
                       bool locally_initiated, tHCI_ROLE assigned_role) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_connected(bda, handle, status, enc_mode, locally_initiated,
                                               assigned_role);
}
void btm_sec_cr_loc_oob_data_cback_event(const RawAddress& address,
                                         tSMP_LOC_OOB_DATA loc_oob_data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_cr_loc_oob_data_cback_event(address, loc_oob_data);
}
void btm_sec_dev_rec_cback_event(BtmDevice* p_device, tBTM_STATUS btm_status,
                                 bool is_le_transport) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_dev_rec_cback_event(p_device, btm_status, is_le_transport);
}
void btm_sec_dev_reset(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_dev_reset();
}
tBTM_STATUS btm_sec_disconnect(uint16_t handle, tHCI_STATUS reason, std::string comment) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_disconnect(handle, reason, comment);
}
void btm_sec_disconnected(uint16_t handle, tHCI_REASON reason, std::string comment) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_disconnected(handle, reason, comment);
}
void btm_sec_encrypt_change(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable,
                            uint8_t key_size, bool from_key_refresh) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_encrypt_change(handle, status, encr_enable, key_size,
                                                    from_key_refresh);
}
void btm_sec_encryption_change_evt(uint16_t handle, tHCI_STATUS status, uint8_t encr_enable,
                                   uint8_t key_size) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_encryption_change_evt(handle, status, encr_enable, key_size);
}
tBTM_STATUS btm_sec_l2cap_access_req(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                     tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_l2cap_access_req(bd_addr, psm, outgoing, p_callback,
                                                             p_ref_data);
}
tBTM_STATUS btm_sec_l2cap_access_req_by_requirement(const RawAddress& bd_addr,
                                                    uint16_t security_required, bool outgoing,
                                                    tBTM_SEC_CALLBACK* p_callback,
                                                    void* p_ref_data) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_l2cap_access_req_by_requirement(
          bd_addr, security_required, outgoing, p_callback, p_ref_data);
}
void btm_sec_link_key_notification(const RawAddress& p_bda, const Octet16& link_key,
                                   uint8_t key_type) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_link_key_notification(p_bda, link_key, key_type);
}
void btm_sec_encryption_key_refresh_complete(uint16_t handle, tHCI_STATUS status) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_encryption_key_refresh_complete(handle, status);
}
void btm_sec_link_key_request(const RawAddress& bda) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_link_key_request(bda);
}
tBTM_STATUS btm_sec_service_access_request(const RawAddress& bd_addr, bool outgoing,
                                           uint16_t security_required,
                                           tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_service_access_request(
          bd_addr, outgoing, security_required, p_callback, p_ref_data);
}
void btm_sec_pin_code_request(const RawAddress& bda) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_pin_code_request(bda);
}
void btm_sec_rmt_host_support_feat_evt(const RawAddress& bd_addr, uint8_t features_0) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_rmt_host_support_feat_evt(bd_addr, features_0);
}
void btm_sec_rmt_name_request_complete(const RawAddress* p_bd_addr, const uint8_t* p_bd_name,
                                       tHCI_STATUS status) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_rmt_name_request_complete(p_bd_addr, p_bd_name, status);
}
void btm_sec_role_changed(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_role_changed(hci_status, bd_addr, new_role);
}
void btm_sec_set_peer_sec_caps(uint16_t hci_handle, bool ssp_supported, bool host_sc_supported,
                               bool controller_sc_supported, bool hci_role_switch_supported,
                               bool br_edr_supported, bool le_supported) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_set_peer_sec_caps(
          hci_handle, ssp_supported, host_sc_supported, controller_sc_supported,
          hci_role_switch_supported, br_edr_supported, le_supported);
}
void btm_sec_update_clock_offset(uint16_t handle, uint16_t clock_offset) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_update_clock_offset(handle, clock_offset);
}
void btm_simple_pair_complete(const RawAddress& bd_addr, uint8_t status) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_simple_pair_complete(bd_addr, status);
}
bool btm_is_bond_lost(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_is_bond_lost(bd_addr);
}
void btm_update_bond_lost(const RawAddress& bd_addr, bool bond_lost) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_update_bond_lost(bd_addr, bond_lost);
}
bool is_autonomous_repairing_supported() {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::is_autonomous_repairing_supported();
}
void set_autonomous_repairing_supported(bool) { inc_func_call_count(__func__); }
uint8_t btm_get_security_mode() {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_get_security_mode();
}
tBTM_STATUS btm_sec_report_bond_loss(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_report_bond_loss(bd_addr, transport);
}
void btm_sec_hci_delete_stored_link_key(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_sec::btm_sec_hci_delete_stored_link_key(bd_addr);
}
uint8_t btm_sec_get_min_enc_key_size() {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_sec::btm_sec_get_min_enc_key_size();
}
// Mocked functions complete
// END mockcify generation
