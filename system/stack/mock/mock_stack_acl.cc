/*
 * Copyright 2021 The Android Open Source Project
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
 *   Functions generated:125
 *
 *  mockcify.pl ver 0.2.1
 */
// Mock include file to share data between tests and mock
#include "stack/mock/mock_stack_acl.h"

#include <bluetooth/types/address.h>

#include <cstdint>
#include <string>

#include "hci/class_of_device.h"
#include "stack/include/acl_api.h"
#include "stack/include/acl_hci_link_interface.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/inq_hci_link_interface.h"
#include "stack/include/l2cap_acl_interface.h"
#include "test/common/mock_functions.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_acl {

// Function state capture and return values, if needed
struct BTM_BLE_IS_RESOLVE_BDA BTM_BLE_IS_RESOLVE_BDA;
struct BTM_IsAclConnectionUp BTM_IsAclConnectionUp;
struct BTM_IsBleConnection BTM_IsBleConnection;
struct BTM_ReadRemoteConnectionAddr BTM_ReadRemoteConnectionAddr;
struct BTM_is_sniff_allowed_for BTM_is_sniff_allowed_for;
struct acl_is_switch_role_idle acl_is_switch_role_idle;
struct acl_link_is_disconnecting acl_link_is_disconnecting;
struct acl_peer_supports_ble_2m_phy acl_peer_supports_ble_2m_phy;
struct acl_peer_supports_ble_coded_phy acl_peer_supports_ble_coded_phy;
struct acl_send_data_packet_br_edr acl_send_data_packet_br_edr;
struct acl_peer_supports_ble_connection_parameters_request
        acl_peer_supports_ble_connection_parameters_request;
struct acl_ble_connection_parameters_request acl_ble_connection_parameters_request;
struct acl_peer_supports_ble_packet_extension acl_peer_supports_ble_packet_extension;
struct acl_peer_supports_sniff_subrating acl_peer_supports_sniff_subrating;
struct acl_peer_supports_ble_connection_subrating acl_peer_supports_ble_connection_subrating;
struct acl_peer_supports_ble_connection_subrating_host
        acl_peer_supports_ble_connection_subrating_host;
struct acl_refresh_remote_address acl_refresh_remote_address;
struct acl_set_peer_le_features_from_handle acl_set_peer_le_features_from_handle;
struct btm_acl_for_bda btm_acl_for_bda;
struct BTM_SetLinkSuperTout BTM_SetLinkSuperTout;
struct btm_remove_acl btm_remove_acl;
struct btm_get_acl_disc_reason_code btm_get_acl_disc_reason_code;
struct BTM_GetNumAclLinks BTM_GetNumAclLinks;
struct acl_get_supported_packet_types acl_get_supported_packet_types;
struct acl_link_role_from_handle acl_link_role_from_handle;
struct btm_handle_to_acl_index btm_handle_to_acl_index;
struct BTM_ReadConnectionAddr BTM_ReadConnectionAddr;
struct BTM_acl_after_controller_started BTM_acl_after_controller_started;
struct btm_connection_request btm_connection_request;
struct acl_disconnect_after_role_switch acl_disconnect_after_role_switch;
struct acl_disconnect_from_handle acl_disconnect_from_handle;
struct acl_packets_completed acl_packets_completed;
struct acl_process_extended_features acl_process_extended_features;
struct acl_process_supported_features acl_process_supported_features;
struct acl_rcv_acl_data acl_rcv_acl_data;
struct acl_send_data_packet_ble acl_send_data_packet_ble;
struct acl_set_disconnect_reason acl_set_disconnect_reason;
struct btm_acl_created btm_acl_created;
struct btm_acl_device_down btm_acl_device_down;
struct btm_acl_disconnected btm_acl_disconnected;
struct btm_acl_flush btm_acl_flush;
struct btm_acl_encrypt_change btm_acl_encrypt_change;
struct btm_acl_notif_conn_collision btm_acl_notif_conn_collision;
struct btm_acl_process_sca_cmpl_pkt btm_acl_process_sca_cmpl_pkt;
struct btm_acl_removed btm_acl_removed;
struct btm_acl_role_changed btm_acl_role_changed;
struct btm_cont_rswitch_from_handle btm_cont_rswitch_from_handle;
struct btm_establish_continue_from_address btm_establish_continue_from_address;
struct btm_read_automatic_flush_timeout_complete btm_read_automatic_flush_timeout_complete;
struct btm_read_remote_version_complete btm_read_remote_version_complete;
struct btm_read_rssi_complete btm_read_rssi_complete;
struct btm_rejectlist_role_change_device btm_rejectlist_role_change_device;
struct btm_set_packet_types_from_address btm_set_packet_types_from_address;
struct on_acl_br_edr_connected on_acl_br_edr_connected;
struct on_acl_br_edr_failed on_acl_br_edr_failed;
struct BTM_unblock_role_switch_and_sniff_mode_for BTM_unblock_role_switch_and_sniff_mode_for;

}  // namespace stack_acl
}  // namespace mock
}  // namespace test

// Mocked functions, if any
bool BTM_BLE_IS_RESOLVE_BDA(const RawAddress& x) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_BLE_IS_RESOLVE_BDA(x);
}
bool BTM_IsAclConnectionUp(const RawAddress& remote_bda, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_IsAclConnectionUp(remote_bda, transport);
}
bool BTM_IsBleConnection(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_IsBleConnection(hci_handle);
}
bool BTM_ReadRemoteConnectionAddr(const RawAddress& pseudo_addr, RawAddress& conn_addr,
                                  tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_ReadRemoteConnectionAddr(pseudo_addr, conn_addr, p_addr_type,
                                                             ota_address);
}
bool BTM_is_sniff_allowed_for(const RawAddress& peer_addr) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_is_sniff_allowed_for(peer_addr);
}
bool acl_is_switch_role_idle(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_is_switch_role_idle(bd_addr, transport);
}
bool acl_link_is_disconnecting(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_link_is_disconnecting(bd_addr, transport);
}
bool acl_peer_supports_ble_2m_phy(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_2m_phy(hci_handle);
}
bool acl_peer_supports_ble_coded_phy(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_coded_phy(hci_handle);
}
bool acl_peer_supports_ble_connection_parameters_request(const RawAddress& remote_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_connection_parameters_request(remote_bda);
}
void acl_ble_connection_parameters_request(uint16_t handle, uint16_t conn_int_min,
                                           uint16_t conn_int_max, uint16_t conn_latency,
                                           uint16_t conn_timeout, uint16_t min_ce_len,
                                           uint16_t max_ce_len) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_ble_connection_parameters_request(
          handle, conn_int_min, conn_int_max, conn_latency, conn_timeout, min_ce_len, max_ce_len);
}
bool acl_peer_supports_ble_packet_extension(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_packet_extension(hci_handle);
}
bool acl_peer_supports_sniff_subrating(const RawAddress& remote_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_sniff_subrating(remote_bda);
}
bool acl_peer_supports_ble_connection_subrating(const RawAddress& remote_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_connection_subrating(remote_bda);
}
bool acl_peer_supports_ble_connection_subrating_host(const RawAddress& remote_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_peer_supports_ble_connection_subrating_host(remote_bda);
}
bool acl_refresh_remote_address(const RawAddress& identity_address,
                                tBLE_ADDR_TYPE identity_address_type, const RawAddress& bda,
                                tBLE_RAND_ADDR_TYPE rra_type, const RawAddress& rpa) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_refresh_remote_address(identity_address, identity_address_type,
                                                           bda, rra_type, rpa);
}
bool acl_set_peer_le_features_from_handle(uint16_t hci_handle, const uint8_t* p) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_set_peer_le_features_from_handle(hci_handle, p);
}
void acl_send_data_packet_br_edr(const RawAddress& bd_addr, BT_HDR* p_buf) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_send_data_packet_br_edr(bd_addr, p_buf);
}
tACL_CONN* btm_acl_for_bda(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::btm_acl_for_bda(bd_addr, transport);
}
tBTM_STATUS BTM_SetLinkSuperTout(const RawAddress& remote_bda, uint16_t timeout) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_SetLinkSuperTout(remote_bda, timeout);
}
tBTM_STATUS btm_remove_acl(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::btm_remove_acl(bd_addr, transport);
}
tHCI_REASON btm_get_acl_disc_reason_code(void) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::btm_get_acl_disc_reason_code();
}
uint16_t BTM_GetNumAclLinks(void) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::BTM_GetNumAclLinks();
}
uint16_t acl_get_supported_packet_types() {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_get_supported_packet_types();
}
uint8_t acl_link_role_from_handle(uint16_t handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::acl_link_role_from_handle(handle);
}
uint8_t btm_handle_to_acl_index(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_acl::btm_handle_to_acl_index(hci_handle);
}
void BTM_ReadConnectionAddr(const RawAddress& remote_bda, RawAddress& local_conn_addr,
                            tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::BTM_ReadConnectionAddr(remote_bda, local_conn_addr, p_addr_type,
                                                ota_address);
}
void BTM_acl_after_controller_started() {
  inc_func_call_count(__func__);
  test::mock::stack_acl::BTM_acl_after_controller_started();
}
void acl_disconnect_after_role_switch(uint16_t conn_handle, tHCI_STATUS reason,
                                      std::string comment) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_disconnect_after_role_switch(conn_handle, reason, comment);
}
void acl_disconnect_from_handle(uint16_t handle, tHCI_STATUS reason, std::string comment) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_disconnect_from_handle(handle, reason, comment);
}
void acl_packets_completed(uint16_t handle, uint16_t credits) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_packets_completed(handle, credits);
}
void acl_process_extended_features(uint16_t handle, uint8_t current_page_number,
                                   uint8_t max_page_number, uint64_t features) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_process_extended_features(handle, current_page_number, max_page_number,
                                                       features);
}
void acl_process_supported_features(uint16_t handle, uint64_t features) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_process_supported_features(handle, features);
}
void acl_rcv_acl_data(BT_HDR* p_msg) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_rcv_acl_data(p_msg);
}
void acl_send_data_packet_ble(const RawAddress& bd_addr, BT_HDR* p_buf) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_send_data_packet_ble(bd_addr, p_buf);
}
void acl_set_disconnect_reason(tHCI_STATUS acl_disc_reason) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::acl_set_disconnect_reason(acl_disc_reason);
}
void btm_acl_created(const AclLinkSpec& link_spec, uint16_t hci_handle, tHCI_ROLE link_role,
                     bool locally_initiated) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_created(link_spec, hci_handle, link_role, locally_initiated);
}
void btm_acl_device_down(void) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_device_down();
}
void btm_acl_disconnected(tHCI_STATUS status, uint16_t handle, tHCI_REASON reason) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_disconnected(status, handle, reason);
}
void btm_acl_encrypt_change(uint16_t handle, uint8_t status, uint8_t encr_enable) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_encrypt_change(handle, status, encr_enable);
}
void btm_acl_notif_conn_collision(const RawAddress& bda) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_notif_conn_collision(bda);
}
void btm_acl_process_sca_cmpl_pkt(uint8_t len, uint8_t* data) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_process_sca_cmpl_pkt(len, data);
}
void btm_acl_removed(uint16_t handle) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_removed(handle);
}
void btm_acl_flush(uint16_t handle) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_flush(handle);
}
void btm_acl_role_changed(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_acl_role_changed(hci_status, bd_addr, new_role);
}
void btm_cont_rswitch_from_handle(uint16_t hci_handle) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_cont_rswitch_from_handle(hci_handle);
}
void btm_establish_continue_from_address(const RawAddress& bda, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_establish_continue_from_address(bda, transport);
}
void btm_read_automatic_flush_timeout_complete(bluetooth::hci::CommandCompleteView view) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_read_automatic_flush_timeout_complete(view);
}
void btm_read_remote_version_complete(tHCI_STATUS status, uint16_t handle, uint8_t lmp_version,
                                      uint16_t manufacturer, uint16_t lmp_subversion) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_read_remote_version_complete(status, handle, lmp_version, manufacturer,
                                                          lmp_subversion);
}
void btm_read_rssi_complete(bluetooth::hci::CommandCompleteView view) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_read_rssi_complete(view);
}
void btm_rejectlist_role_change_device(const RawAddress& bd_addr, uint8_t hci_status) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_rejectlist_role_change_device(bd_addr, hci_status);
}
void btm_set_packet_types_from_address(const RawAddress& bd_addr, uint16_t pkt_types) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::btm_set_packet_types_from_address(bd_addr, pkt_types);
}
void btm_connection_request(const RawAddress& bda, const bluetooth::hci::ClassOfDevice& cod) {
  test::mock::stack_acl::btm_connection_request(bda, cod);
}
void on_acl_br_edr_connected(const RawAddress& bda, uint16_t handle, uint8_t enc_mode,
                             bool locally_initiated, tHCI_ROLE role) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::on_acl_br_edr_connected(bda, handle, enc_mode, locally_initiated, role);
}
void on_acl_br_edr_failed(const RawAddress& bda, tHCI_STATUS status, bool locally_initiated) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::on_acl_br_edr_failed(bda, status, locally_initiated);
}

void BTM_unblock_role_switch_and_sniff_mode_for(const RawAddress& peer_addr) {
  inc_func_call_count(__func__);
  test::mock::stack_acl::BTM_unblock_role_switch_and_sniff_mode_for(peer_addr);
}

// END mockcify generation

void BTM_block_role_switch_and_sniff_mode_for(const RawAddress& /* peer_addr */) {}
