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

#include <cstdint>
#include <functional>
#include <string>

// Original included files, if any
#include <bluetooth/types/acl_link_spec.h>
#include <bluetooth/types/address.h>

#include "hci/class_of_device.h"
#include "hci/hci_packets.h"
#include "stack/acl/acl.h"
#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_status.h"

// Mocked compile conditionals, if any
namespace test {
namespace mock {
namespace stack_acl {

// Name: BTM_BLE_IS_RESOLVE_BDA
// Params: const RawAddress& x
// Returns: bool
struct BTM_BLE_IS_RESOLVE_BDA {
  std::function<bool(const RawAddress& x)> body{[](const RawAddress& /* x */) { return false; }};
  bool operator()(const RawAddress& x) { return body(x); }
};
extern struct BTM_BLE_IS_RESOLVE_BDA BTM_BLE_IS_RESOLVE_BDA;
// Name: BTM_IsAclConnectionUp
// Params: const RawAddress& remote_bda, tBT_TRANSPORT transport
// Returns: bool
struct BTM_IsAclConnectionUp {
  std::function<bool(const RawAddress& remote_bda, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* remote_bda */, tBT_TRANSPORT /* transport */) { return false; }};
  bool operator()(const RawAddress& remote_bda, tBT_TRANSPORT transport) {
    return body(remote_bda, transport);
  }
};
extern struct BTM_IsAclConnectionUp BTM_IsAclConnectionUp;
// Name: BTM_IsBleConnection
// Params: uint16_t hci_handle
// Returns: bool
struct BTM_IsBleConnection {
  std::function<bool(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { return false; }};
  bool operator()(uint16_t hci_handle) { return body(hci_handle); }
};
extern struct BTM_IsBleConnection BTM_IsBleConnection;
// Name: BTM_ReadRemoteConnectionAddr
// Params: const RawAddress& pseudo_addr, RawAddress& conn_addr, bool
// ota_address tBLE_ADDR_TYPE* p_addr_type Returns: bool
struct BTM_ReadRemoteConnectionAddr {
  std::function<bool(const RawAddress& pseudo_addr, RawAddress& conn_addr,
                     tBLE_ADDR_TYPE* p_addr_type, bool ota_address)>
          body{[](const RawAddress& /* pseudo_addr */, RawAddress& /* conn_addr */,
                  tBLE_ADDR_TYPE* /* p_addr_type */, bool /* ota_address */) { return false; }};
  bool operator()(const RawAddress& pseudo_addr, RawAddress& conn_addr, tBLE_ADDR_TYPE* p_addr_type,
                  bool ota_address) {
    return body(pseudo_addr, conn_addr, p_addr_type, ota_address);
  }
};
extern struct BTM_ReadRemoteConnectionAddr BTM_ReadRemoteConnectionAddr;
// Name: BTM_is_sniff_allowed_for
// Params: const RawAddress& peer_addr
// Returns: bool
struct BTM_is_sniff_allowed_for {
  std::function<bool(const RawAddress& peer_addr)> body{
          [](const RawAddress& /* peer_addr */) { return false; }};
  bool operator()(const RawAddress& peer_addr) { return body(peer_addr); }
};
extern struct BTM_is_sniff_allowed_for BTM_is_sniff_allowed_for;
// Name: acl_send_data_packet_br_edr
// Params: const RawAddress& bd_addr, BT_HDR* p_buf
// Returns: void
struct acl_send_data_packet_br_edr {
  std::function<void(const RawAddress& bd_addr, BT_HDR* p_buf)> body{
          [](const RawAddress& /* bd_addr */, BT_HDR* /* p_buf */) {}};
  void operator()(const RawAddress& bd_addr, BT_HDR* p_buf) { return body(bd_addr, p_buf); }
};
extern struct acl_send_data_packet_br_edr acl_send_data_packet_br_edr;
// Name: acl_is_switch_role_idle
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Returns: bool
struct acl_is_switch_role_idle {
  std::function<bool(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) { return false; }};
  bool operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct acl_is_switch_role_idle acl_is_switch_role_idle;
// Name: acl_link_is_disconnecting
// Params: const RawAddress& remote_bda, tBT_TRANSPORT transport
// Returns: bool
struct acl_link_is_disconnecting {
  std::function<bool(const RawAddress& remote_bda, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* remote_bda */, tBT_TRANSPORT /* transport */) { return false; }};
  bool operator()(const RawAddress& remote_bda, tBT_TRANSPORT transport) {
    return body(remote_bda, transport);
  }
};
extern struct acl_link_is_disconnecting acl_link_is_disconnecting;
// Name: acl_peer_supports_ble_2m_phy
// Params: uint16_t hci_handle
// Returns: bool
struct acl_peer_supports_ble_2m_phy {
  std::function<bool(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { return false; }};
  bool operator()(uint16_t hci_handle) { return body(hci_handle); }
};
extern struct acl_peer_supports_ble_2m_phy acl_peer_supports_ble_2m_phy;
// Name: acl_peer_supports_ble_coded_phy
// Params: uint16_t hci_handle
// Returns: bool
struct acl_peer_supports_ble_coded_phy {
  std::function<bool(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { return false; }};
  bool operator()(uint16_t hci_handle) { return body(hci_handle); }
};
extern struct acl_peer_supports_ble_coded_phy acl_peer_supports_ble_coded_phy;
// Name: acl_peer_supports_ble_connection_parameters_request
// Params:  const RawAddress& remote_bda
// Returns: bool
struct acl_peer_supports_ble_connection_parameters_request {
  std::function<bool(const RawAddress& remote_bda)> body{
          [](const RawAddress& /* remote_bda */) { return false; }};
  bool operator()(const RawAddress& remote_bda) { return body(remote_bda); }
};
extern struct acl_peer_supports_ble_connection_parameters_request
        acl_peer_supports_ble_connection_parameters_request;
// Name: acl_peer_supports_ble_connection_parameters_request
// Params:  const RawAddress& remote_bda
// Returns: bool
struct acl_ble_connection_parameters_request {
  std::function<void(uint16_t handle, uint16_t conn_int_min, uint16_t conn_int_max,
                     uint16_t conn_latency, uint16_t conn_timeout, uint16_t min_ce_len,
                     uint16_t max_ce_len)>
          body{[](uint16_t /* handle */, uint16_t /* conn_int_min */, uint16_t /* conn_int_max */,
                  uint16_t /* conn_latency */, uint16_t /* conn_timeout */,
                  uint16_t /* min_ce_len */, uint16_t /* max_ce_len */) {}};
  void operator()(uint16_t handle, uint16_t conn_int_min, uint16_t conn_int_max,
                  uint16_t conn_latency, uint16_t conn_timeout, uint16_t min_ce_len,
                  uint16_t max_ce_len) {
    body(handle, conn_int_min, conn_int_max, conn_latency, conn_timeout, min_ce_len, max_ce_len);
  }
};
extern struct acl_ble_connection_parameters_request acl_ble_connection_parameters_request;
// Name: acl_peer_supports_ble_packet_extension
// Params: uint16_t hci_handle
// Returns: bool
struct acl_peer_supports_ble_packet_extension {
  std::function<bool(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { return false; }};
  bool operator()(uint16_t hci_handle) { return body(hci_handle); }
};
extern struct acl_peer_supports_ble_packet_extension acl_peer_supports_ble_packet_extension;
// Name: acl_peer_supports_sniff_subrating
// Params: const RawAddress& remote_bda
// Returns: bool
struct acl_peer_supports_sniff_subrating {
  std::function<bool(const RawAddress& remote_bda)> body{
          [](const RawAddress& /* remote_bda */) { return false; }};
  bool operator()(const RawAddress& remote_bda) { return body(remote_bda); }
};
extern struct acl_peer_supports_sniff_subrating acl_peer_supports_sniff_subrating;
// Name: acl_peer_supports_ble_connection_subrating
// Params: const RawAddress& remote_bda
// Returns: bool
struct acl_peer_supports_ble_connection_subrating {
  std::function<bool(const RawAddress& remote_bda)> body{
          [](const RawAddress& /* remote_bda */) { return false; }};
  bool operator()(const RawAddress& remote_bda) { return body(remote_bda); }
};
extern struct acl_peer_supports_ble_connection_subrating acl_peer_supports_ble_connection_subrating;
// Name: acl_peer_supports_ble_connection_subrating_host
// Params: const RawAddress& remote_bda
// Returns: bool
struct acl_peer_supports_ble_connection_subrating_host {
  std::function<bool(const RawAddress& remote_bda)> body{
          [](const RawAddress& /* remote_bda */) { return false; }};
  bool operator()(const RawAddress& remote_bda) { return body(remote_bda); }
};
extern struct acl_peer_supports_ble_connection_subrating_host
        acl_peer_supports_ble_connection_subrating_host;
// Name: acl_refresh_remote_address
// Params: const RawAddress& identity_address, tBLE_ADDR_TYPE
// identity_address_type, const RawAddress& bda, tBLE_ADDR_TYPE rra_type,
// const RawAddress& rpa Returns: bool
struct acl_refresh_remote_address {
  std::function<bool(const RawAddress& identity_address, tBLE_ADDR_TYPE identity_address_type,
                     const RawAddress& bda, tBLE_RAND_ADDR_TYPE rra_type, const RawAddress& rpa)>
          body{[](const RawAddress& /* identity_address */,
                  tBLE_ADDR_TYPE /* identity_address_type */, const RawAddress& /* bda */,
                  tBLE_RAND_ADDR_TYPE /* rra_type */,
                  const RawAddress& /* rpa */) { return false; }};
  bool operator()(const RawAddress& identity_address, tBLE_ADDR_TYPE identity_address_type,
                  const RawAddress& bda, tBLE_RAND_ADDR_TYPE rra_type, const RawAddress& rpa) {
    return body(identity_address, identity_address_type, bda, rra_type, rpa);
  }
};
extern struct acl_refresh_remote_address acl_refresh_remote_address;
// Name: acl_set_peer_le_features_from_handle
// Params: uint16_t hci_handle, const uint8_t* p
// Returns: bool
struct acl_set_peer_le_features_from_handle {
  std::function<bool(uint16_t hci_handle, const uint8_t* p)> body{
          [](uint16_t /* hci_handle */, const uint8_t* /* p */) { return false; }};
  bool operator()(uint16_t hci_handle, const uint8_t* p) { return body(hci_handle, p); }
};
extern struct acl_set_peer_le_features_from_handle acl_set_peer_le_features_from_handle;
// Name: btm_acl_for_bda
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Returns: tACL_CONN*
struct btm_acl_for_bda {
  std::function<tACL_CONN*(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) { return nullptr; }};
  tACL_CONN* operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_acl_for_bda btm_acl_for_bda;
// Name: BTM_SetLinkSuperTout
// Params: const RawAddress& remote_bda, uint16_t timeout
// Returns: tBTM_STATUS
struct BTM_SetLinkSuperTout {
  std::function<tBTM_STATUS(const RawAddress& remote_bda, uint16_t timeout)> body{
          [](const RawAddress& /* remote_bda */, uint16_t /* timeout */) {
            return tBTM_STATUS::BTM_SUCCESS;
          }};
  tBTM_STATUS operator()(const RawAddress& remote_bda, uint16_t timeout) {
    return body(remote_bda, timeout);
  }
};
extern struct BTM_SetLinkSuperTout BTM_SetLinkSuperTout;
// Name: btm_remove_acl
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Returns: tBTM_STATUS
struct btm_remove_acl {
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) {
            return tBTM_STATUS::BTM_SUCCESS;
          }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct btm_remove_acl btm_remove_acl;
// Name: btm_get_acl_disc_reason_code
// Params: void
// Returns: tHCI_REASON
struct btm_get_acl_disc_reason_code {
  std::function<tHCI_REASON(void)> body{[](void) { return HCI_SUCCESS; }};
  tHCI_REASON operator()(void) { return body(); }
};
extern struct btm_get_acl_disc_reason_code btm_get_acl_disc_reason_code;
// Name: BTM_GetNumAclLinks
// Params: void
// Returns: uint16_t
struct BTM_GetNumAclLinks {
  std::function<uint16_t(void)> body{[](void) { return 0; }};
  uint16_t operator()(void) { return body(); }
};
extern struct BTM_GetNumAclLinks BTM_GetNumAclLinks;
// Name: acl_get_supported_packet_types
// Params:
// Returns: uint16_t
struct acl_get_supported_packet_types {
  std::function<uint16_t()> body{[]() { return 0; }};
  uint16_t operator()() { return body(); }
};
extern struct acl_get_supported_packet_types acl_get_supported_packet_types;
// Name: acl_link_role_from_handle
// Params: uint16_t handle
// Returns: uint8_t
struct acl_link_role_from_handle {
  std::function<uint8_t(uint16_t handle)> body{[](uint16_t /* handle */) { return 0; }};
  uint8_t operator()(uint16_t handle) { return body(handle); }
};
extern struct acl_link_role_from_handle acl_link_role_from_handle;
// Name: btm_handle_to_acl_index
// Params: uint16_t hci_handle
// Returns: uint8_t
struct btm_handle_to_acl_index {
  std::function<uint8_t(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { return 0; }};
  uint8_t operator()(uint16_t hci_handle) { return body(hci_handle); }
};
extern struct btm_handle_to_acl_index btm_handle_to_acl_index;
// Name: BTM_ReadConnectionAddr
// Params: const RawAddress& remote_bda, RawAddress& local_conn_addr, bool
// ota_address tBLE_ADDR_TYPE* p_addr_type Returns: void
struct BTM_ReadConnectionAddr {
  std::function<void(const RawAddress& remote_bda, RawAddress& local_conn_addr,
                     tBLE_ADDR_TYPE* p_addr_type, bool ota_address)>
          body{[](const RawAddress& /* remote_bda */, RawAddress& /* local_conn_addr */,
                  tBLE_ADDR_TYPE* /* p_addr_type */, bool /* ota_address */) { ; }};
  void operator()(const RawAddress& remote_bda, RawAddress& local_conn_addr,
                  tBLE_ADDR_TYPE* p_addr_type, bool ota_address) {
    body(remote_bda, local_conn_addr, p_addr_type, ota_address);
  }
};
extern struct BTM_ReadConnectionAddr BTM_ReadConnectionAddr;
// Name: BTM_acl_after_controller_started
// Returns: void
struct BTM_acl_after_controller_started {
  std::function<void()> body{[]() { ; }};
  void operator()() { body(); }
};
extern struct BTM_acl_after_controller_started BTM_acl_after_controller_started;
// Name: acl_disconnect_after_role_switch
// Params: uint16_t conn_handle, tHCI_STATUS reason
// Returns: void
struct acl_disconnect_after_role_switch {
  std::function<void(uint16_t conn_handle, tHCI_STATUS reason, std::string comment)> body{
          [](uint16_t /* conn_handle */, tHCI_STATUS /* reason */, std::string /* comment */) {}};
  void operator()(uint16_t conn_handle, tHCI_STATUS reason, std::string comment) {
    body(conn_handle, reason, comment);
  }
};
extern struct acl_disconnect_after_role_switch acl_disconnect_after_role_switch;
// Name: acl_disconnect_from_handle
// Params: uint16_t handle, tHCI_STATUS reason
// Returns: void
struct acl_disconnect_from_handle {
  std::function<void(uint16_t handle, tHCI_STATUS reason, std::string comment)> body{
          [](uint16_t /* handle */, tHCI_STATUS /* reason */, std::string /* comment */) { ; }};
  void operator()(uint16_t handle, tHCI_STATUS reason, std::string comment) {
    body(handle, reason, comment);
  }
};
extern struct acl_disconnect_from_handle acl_disconnect_from_handle;
// Name: acl_packets_completed
// Params: uint16_t handle, uint16_t credits
// Returns: void
struct acl_packets_completed {
  std::function<void(uint16_t handle, uint16_t credits)> body{
          [](uint16_t /* handle */, uint16_t /* credits */) { ; }};
  void operator()(uint16_t handle, uint16_t credits) { body(handle, credits); }
};
extern struct acl_packets_completed acl_packets_completed;
// Name: acl_process_extended_features
// Params: uint16_t handle, uint8_t current_page_number, uint8_t
// max_page_number, uint64_t features Returns: void
struct acl_process_extended_features {
  std::function<void(uint16_t handle, uint8_t current_page_number, uint8_t max_page_number,
                     uint64_t features)>
          body{[](uint16_t /* handle */, uint8_t /* current_page_number */,
                  uint8_t /* max_page_number */, uint64_t /* features */) { ; }};
  void operator()(uint16_t handle, uint8_t current_page_number, uint8_t max_page_number,
                  uint64_t features) {
    body(handle, current_page_number, max_page_number, features);
  }
};
extern struct acl_process_extended_features acl_process_extended_features;
// Name: acl_process_supported_features
// Params: uint16_t handle, uint64_t features
// Returns: void
struct acl_process_supported_features {
  std::function<void(uint16_t handle, uint64_t features)> body{
          [](uint16_t /* handle */, uint64_t /* features */) { ; }};
  void operator()(uint16_t handle, uint64_t features) { body(handle, features); }
};
extern struct acl_process_supported_features acl_process_supported_features;
// Name: acl_rcv_acl_data
// Params: BT_HDR* p_msg
// Returns: void
struct acl_rcv_acl_data {
  std::function<void(BT_HDR* p_msg)> body{[](BT_HDR* /* p_msg */) { ; }};
  void operator()(BT_HDR* p_msg) { body(p_msg); }
};
extern struct acl_rcv_acl_data acl_rcv_acl_data;
// Name: acl_send_data_packet_ble
// Params: const RawAddress& bd_addr, BT_HDR* p_buf
// Returns: void
struct acl_send_data_packet_ble {
  std::function<void(const RawAddress& bd_addr, BT_HDR* p_buf)> body{
          [](const RawAddress& /* bd_addr */, BT_HDR* /* p_buf */) { ; }};
  void operator()(const RawAddress& bd_addr, BT_HDR* p_buf) { body(bd_addr, p_buf); }
};
extern struct acl_send_data_packet_ble acl_send_data_packet_ble;
// Name: acl_set_disconnect_reason
// Params: tHCI_STATUS acl_disc_reason
// Returns: void
struct acl_set_disconnect_reason {
  std::function<void(tHCI_STATUS acl_disc_reason)> body{
          [](tHCI_STATUS /* acl_disc_reason */) { ; }};
  void operator()(tHCI_STATUS acl_disc_reason) { body(acl_disc_reason); }
};
extern struct acl_set_disconnect_reason acl_set_disconnect_reason;
// Name: btm_connection_request
// Params: const RawAddress& bda, const bluetooth::hci::ClassOfDevice& cod
// Returns: void
struct btm_connection_request {
  std::function<void(const RawAddress& bda, const bluetooth::hci::ClassOfDevice& cod)> body{
          [](const RawAddress& /* bda */, const bluetooth::hci::ClassOfDevice& /* cod */) { ; }};
  void operator()(const RawAddress& bda, const bluetooth::hci::ClassOfDevice& cod) {
    body(bda, cod);
  }
};
extern struct btm_connection_request btm_connection_request;
// Name: btm_acl_created
// Params: const AclLinkSpec& link_spec, uint16_t hci_handle, tHCI_ROLE link_role, bool
// locally_initiated Returns: void
struct btm_acl_created {
  std::function<void(const AclLinkSpec& link_spec, uint16_t hci_handle, tHCI_ROLE link_role,
                     bool locally_initiated)>
          body{[](const AclLinkSpec& /* link_spec */, uint16_t /* hci_handle */,
                  tHCI_ROLE /* link_role */, bool /*locally_initiated*/) { ; }};
  void operator()(const AclLinkSpec& link_spec, uint16_t hci_handle, tHCI_ROLE link_role,
                  bool locally_initiated) {
    body(link_spec, hci_handle, link_role, locally_initiated);
  }
};
extern struct btm_acl_created btm_acl_created;
// Name: btm_acl_device_down
// Params: void
// Returns: void
struct btm_acl_device_down {
  std::function<void(void)> body{[](void) { ; }};
  void operator()(void) { body(); }
};
extern struct btm_acl_device_down btm_acl_device_down;
// Name: btm_acl_disconnected
// Params: tHCI_STATUS status, uint16_t handle, tHCI_REASON reason
// Returns: void
struct btm_acl_disconnected {
  std::function<void(tHCI_STATUS status, uint16_t handle, tHCI_REASON reason)> body{
          [](tHCI_STATUS /* status */, uint16_t /* handle */, tHCI_REASON /* reason */) { ; }};
  void operator()(tHCI_STATUS status, uint16_t handle, tHCI_REASON reason) {
    body(status, handle, reason);
  }
};
extern struct btm_acl_disconnected btm_acl_disconnected;
// Name: btm_acl_encrypt_change
// Params: uint16_t handle, uint8_t status, uint8_t encr_enable
// Returns: void
struct btm_acl_encrypt_change {
  std::function<void(uint16_t handle, uint8_t status, uint8_t encr_enable)> body{
          [](uint16_t /* handle */, uint8_t /* status */, uint8_t /* encr_enable */) { ; }};
  void operator()(uint16_t handle, uint8_t status, uint8_t encr_enable) {
    body(handle, status, encr_enable);
  }
};
extern struct btm_acl_encrypt_change btm_acl_encrypt_change;
// Name: btm_acl_notif_conn_collision
// Params: const RawAddress& bda
// Returns: void
struct btm_acl_notif_conn_collision {
  std::function<void(const RawAddress& bda)> body{[](const RawAddress& /* bda */) { ; }};
  void operator()(const RawAddress& bda) { body(bda); }
};
extern struct btm_acl_notif_conn_collision btm_acl_notif_conn_collision;
// Name: btm_acl_process_sca_cmpl_pkt
// Params: uint8_t len, uint8_t* data
// Returns: void
struct btm_acl_process_sca_cmpl_pkt {
  std::function<void(uint8_t len, uint8_t* data)> body{
          [](uint8_t /* len */, uint8_t* /* data */) { ; }};
  void operator()(uint8_t len, uint8_t* data) { body(len, data); }
};
extern struct btm_acl_process_sca_cmpl_pkt btm_acl_process_sca_cmpl_pkt;
// Name: btm_acl_removed
// Params: uint16_t handle
// Returns: void
struct btm_acl_removed {
  std::function<void(uint16_t handle)> body{[](uint16_t /* handle */) { ; }};
  void operator()(uint16_t handle) { body(handle); }
};
extern struct btm_acl_removed btm_acl_removed;
// Name: btm_acl_flush
// Params: uint16_t handle
// Returns: void
struct btm_acl_flush {
  std::function<void(uint16_t handle)> body{[](uint16_t /* handle */) { ; }};
  void operator()(uint16_t handle) { body(handle); }
};
extern struct btm_acl_flush btm_acl_flush;
// Name: btm_acl_role_changed
// Params: tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE
// new_role Returns: void
struct btm_acl_role_changed {
  std::function<void(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role)> body{
          [](tHCI_STATUS /* hci_status */, const RawAddress& /* bd_addr */,
             tHCI_ROLE /* new_role */) { ; }};
  void operator()(tHCI_STATUS hci_status, const RawAddress& bd_addr, tHCI_ROLE new_role) {
    body(hci_status, bd_addr, new_role);
  }
};
extern struct btm_acl_role_changed btm_acl_role_changed;
// Name: btm_cont_rswitch_from_handle
// Params: uint16_t hci_handle
// Returns: void
struct btm_cont_rswitch_from_handle {
  std::function<void(uint16_t hci_handle)> body{[](uint16_t /* hci_handle */) { ; }};
  void operator()(uint16_t hci_handle) { body(hci_handle); }
};
extern struct btm_cont_rswitch_from_handle btm_cont_rswitch_from_handle;
// Name: btm_establish_continue_from_address
// Params: const RawAddress& bda, tBT_TRANSPORT transport
// Returns: void
struct btm_establish_continue_from_address {
  std::function<void(const RawAddress& bda, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bda */, tBT_TRANSPORT /* transport */) { ; }};
  void operator()(const RawAddress& bda, tBT_TRANSPORT transport) { body(bda, transport); }
};
extern struct btm_establish_continue_from_address btm_establish_continue_from_address;
// Name: btm_read_automatic_flush_timeout_complete
// Params: uint8_t* p
// Returns: void
struct btm_read_automatic_flush_timeout_complete {
  std::function<void(bluetooth::hci::CommandCompleteView view)> body{
          [](bluetooth::hci::CommandCompleteView /* view */) { ; }};
  void operator()(bluetooth::hci::CommandCompleteView view) { body(view); }
};
// Name: btm_read_remote_version_complete
// Params: tHCI_STATUS status, uint16_t handle, uint8_t lmp_version, uint16_t
// manufacturer, uint16_t lmp_subversion Returns: void
struct btm_read_remote_version_complete {
  std::function<void(tHCI_STATUS status, uint16_t handle, uint8_t lmp_version,
                     uint16_t manufacturer, uint16_t lmp_subversion)>
          body{[](tHCI_STATUS /* status */, uint16_t /* handle */, uint8_t /* lmp_version */,
                  uint16_t /* manufacturer */, uint16_t /* lmp_subversion */) { ; }};
  void operator()(tHCI_STATUS status, uint16_t handle, uint8_t lmp_version, uint16_t manufacturer,
                  uint16_t lmp_subversion) {
    body(status, handle, lmp_version, manufacturer, lmp_subversion);
  }
};
extern struct btm_read_remote_version_complete btm_read_remote_version_complete;
// Name: btm_read_rssi_complete
// Params: uint8_t* p
// Returns: void
struct btm_read_rssi_complete {
  std::function<void(bluetooth::hci::CommandCompleteView view)> body{
          [](bluetooth::hci::CommandCompleteView /* view */) { ; }};
  void operator()(bluetooth::hci::CommandCompleteView view) { body(view); }
};
extern struct btm_read_rssi_complete btm_read_rssi_complete;
// Name: btm_rejectlist_role_change_device
// Params: const RawAddress& bd_addr, uint8_t hci_status
// Returns: void
struct btm_rejectlist_role_change_device {
  std::function<void(const RawAddress& bd_addr, uint8_t hci_status)> body{
          [](const RawAddress& /* bd_addr */, uint8_t /* hci_status */) { ; }};
  void operator()(const RawAddress& bd_addr, uint8_t hci_status) { body(bd_addr, hci_status); }
};
extern struct btm_rejectlist_role_change_device btm_rejectlist_role_change_device;
// Name: btm_set_packet_types_from_address
// Params: const RawAddress& bd_addr, uint16_t pkt_types
// Returns: void
struct btm_set_packet_types_from_address {
  std::function<void(const RawAddress& bd_addr, uint16_t pkt_types)> body{
          [](const RawAddress& /* bd_addr */, uint16_t /* pkt_types */) { ; }};
  void operator()(const RawAddress& bd_addr, uint16_t pkt_types) { body(bd_addr, pkt_types); }
};
extern struct btm_set_packet_types_from_address btm_set_packet_types_from_address;
// Name: on_acl_br_edr_connected
// Params: const RawAddress& bda, uint16_t handle, uint8_t enc_mode, bool locally_initiated,
// tHCI_ROLE role
//  Returns: void
struct on_acl_br_edr_connected {
  std::function<void(const RawAddress& bda, uint16_t handle, uint8_t enc_mode,
                     bool locally_initiated, tHCI_ROLE role)>
          body{[](const RawAddress& /* bda */, uint16_t /* handle */, uint8_t /* enc_mode */,
                  bool /* locally_initiated */, tHCI_ROLE /* role */) { ; }};
  void operator()(const RawAddress& bda, uint16_t handle, uint8_t enc_mode, bool locally_initiated,
                  tHCI_ROLE role) {
    body(bda, handle, enc_mode, locally_initiated, role);
  }
};
extern struct on_acl_br_edr_connected on_acl_br_edr_connected;
// Name: on_acl_br_edr_failed
// Params: const RawAddress& bda, tHCI_STATUS status, bool locally_initiated
// Returns: void
struct on_acl_br_edr_failed {
  std::function<void(const RawAddress& bda, tHCI_STATUS status, bool locally_initiated)> body{
          [](const RawAddress& /* bda */, tHCI_STATUS /* status */, bool /* locally_initiated */) {
          }};
  void operator()(const RawAddress& bda, tHCI_STATUS status, bool locally_initiated) {
    body(bda, status, locally_initiated);
  }
};
extern struct on_acl_br_edr_failed on_acl_br_edr_failed;

// Manually added
struct BTM_unblock_role_switch_and_sniff_mode_for {
  std::function<void(const RawAddress& peer_addr)> body{[](const RawAddress& /* peer_addr */) {}};
  void operator()(const RawAddress& peer_addr) { body(peer_addr); }
};
extern struct BTM_unblock_role_switch_and_sniff_mode_for BTM_unblock_role_switch_and_sniff_mode_for;

}  // namespace stack_acl
}  // namespace mock
}  // namespace test

// END mockcify generation
