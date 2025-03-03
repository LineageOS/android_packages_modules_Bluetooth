/*
 * Copyright 2022 The Android Open Source Project
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
 *   Functions generated:52
 *
 *  mockcify.pl ver 0.5.0
 */

#include <base/functional/callback.h>

#include <cstdint>
#include <functional>
#include <optional>

// Original included files, if any
#include "stack/btm/btm_ble_sec.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_octets.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "types/ble_address_with_type.h"
#include "types/raw_address.h"

typedef uint8_t tBTM_SEC_ACTION;

// Original usings

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_btm_ble {

// Shared state between mocked functions and tests
// Name: BTM_BleConfirmReply
// Params: const RawAddress& bd_addr, uint8_t res
// Return: void
struct BTM_BleConfirmReply {
  std::function<void(const RawAddress& /* bd_addr */, tBTM_STATUS /* res */)> body{
          [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_STATUS res) { body(bd_addr, res); }
};
extern struct BTM_BleConfirmReply BTM_BleConfirmReply;

// Name: BTM_BleDataSignature
// Params: const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
// BLE_SIGNATURE signature Return: bool
struct BTM_BleDataSignature {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                     BLE_SIGNATURE signature)>
          body{[](const RawAddress& /* bd_addr */, uint8_t* /* p_text */, uint16_t /* len */,
                  BLE_SIGNATURE /* signature */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                  BLE_SIGNATURE signature) {
    return body(bd_addr, p_text, len, signature);
  }
};
extern struct BTM_BleDataSignature BTM_BleDataSignature;

// Name: BTM_BleLoadLocalKeys
// Params: uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key
// Return: void
struct BTM_BleLoadLocalKeys {
  std::function<void(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key)> body{
          [](uint8_t /* key_type */, tBTM_BLE_LOCAL_KEYS* /* p_key */) {}};
  void operator()(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) { body(key_type, p_key); }
};
extern struct BTM_BleLoadLocalKeys BTM_BleLoadLocalKeys;

// Name: BTM_BleOobDataReply
// Params: const RawAddress& bd_addr, uint8_t res, uint8_t len, uint8_t* p_data
// Return: void
struct BTM_BleOobDataReply {
  std::function<void(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len, uint8_t* p_data)>
          body{[](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */, uint8_t /* len */,
                  uint8_t* /* p_data */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len, uint8_t* p_data) {
    body(bd_addr, res, len, p_data);
  }
};
extern struct BTM_BleOobDataReply BTM_BleOobDataReply;

// Name: BTM_BlePasskeyReply
// Params: const RawAddress& bd_addr, uint8_t res, uint32_t passkey
// Return: void
struct BTM_BlePasskeyReply {
  std::function<void(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey)> body{
          [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */, uint32_t /* passkey */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey) {
    body(bd_addr, res, passkey);
  }
};
extern struct BTM_BlePasskeyReply BTM_BlePasskeyReply;

// Name: BTM_BleReadPhy
// Params: const RawAddress& bd_addr, base::Callback<void(uint8_t tx_phy,
// uint8_t rx_phy, uint8_t status Return: void
struct BTM_BleReadPhy {
  std::function<void(const RawAddress& bd_addr,
                     base::Callback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> callback)>
          body{[](const RawAddress& /* bd_addr */,
                  base::Callback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)>
                  /* callback */) {}};
  void operator()(const RawAddress& bd_addr,
                  base::Callback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> callback) {
    body(bd_addr, callback);
  }
};
extern struct BTM_BleReadPhy BTM_BleReadPhy;

// Name: BTM_BleReceiverTest
// Params: uint8_t rx_freq, tBTM_CMPL_CB* p_cmd_cmpl_cback
// Return: void
struct BTM_BleReceiverTest {
  std::function<void(uint8_t rx_freq, tBTM_CMPL_CB* p_cmd_cmpl_cback)> body{
          [](uint8_t /* rx_freq */, tBTM_CMPL_CB* /* p_cmd_cmpl_cback */) {}};
  void operator()(uint8_t rx_freq, tBTM_CMPL_CB* p_cmd_cmpl_cback) {
    body(rx_freq, p_cmd_cmpl_cback);
  }
};
extern struct BTM_BleReceiverTest BTM_BleReceiverTest;

// Name: BTM_BleSecureConnectionOobDataReply
// Params: const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r
// Return: void
struct BTM_BleSecureConnectionOobDataReply {
  std::function<void(const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r)> body{
          [](const RawAddress& /* bd_addr */, uint8_t* /* p_c */, uint8_t* /* p_r */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r) {
    body(bd_addr, p_c, p_r);
  }
};
extern struct BTM_BleSecureConnectionOobDataReply BTM_BleSecureConnectionOobDataReply;

// Name: BTM_BleTestEnd
// Params: tBTM_CMPL_CB* p_cmd_cmpl_cback
// Return: void
struct BTM_BleTestEnd {
  std::function<void(tBTM_CMPL_CB* p_cmd_cmpl_cback)> body{
          [](tBTM_CMPL_CB* /* p_cmd_cmpl_cback */) {}};
  void operator()(tBTM_CMPL_CB* p_cmd_cmpl_cback) { body(p_cmd_cmpl_cback); }
};
extern struct BTM_BleTestEnd BTM_BleTestEnd;

// Name: BTM_BleTransmitterTest
// Params: uint8_t tx_freq, uint8_t test_data_len, uint8_t packet_payload,
// tBTM_CMPL_CB* p_cmd_cmpl_cback Return: void
struct BTM_BleTransmitterTest {
  std::function<void(uint8_t tx_freq, uint8_t test_data_len, uint8_t packet_payload,
                     tBTM_CMPL_CB* p_cmd_cmpl_cback)>
          body{[](uint8_t /* tx_freq */, uint8_t /* test_data_len */, uint8_t /* packet_payload */,
                  tBTM_CMPL_CB* /* p_cmd_cmpl_cback */) {}};
  void operator()(uint8_t tx_freq, uint8_t test_data_len, uint8_t packet_payload,
                  tBTM_CMPL_CB* p_cmd_cmpl_cback) {
    body(tx_freq, test_data_len, packet_payload, p_cmd_cmpl_cback);
  }
};
extern struct BTM_BleTransmitterTest BTM_BleTransmitterTest;

// Name: BTM_BleVerifySignature
// Params: const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len, uint32_t
// counter, uint8_t* p_comp Return: bool
struct BTM_BleVerifySignature {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len, uint32_t counter,
                     uint8_t* p_comp)>
          body{[](const RawAddress& /* bd_addr */, uint8_t* /* p_orig */, uint16_t /* len */,
                  uint32_t /* counter */, uint8_t* /* p_comp */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len, uint32_t counter,
                  uint8_t* p_comp) {
    return body(bd_addr, p_orig, len, counter, p_comp);
  }
};
extern struct BTM_BleVerifySignature BTM_BleVerifySignature;

// Name: BTM_GetDeviceDHK
// Params:
// Return: const Octet16&
struct BTM_GetDeviceDHK {
  static const Octet16 return_value;
  std::function<const Octet16&()> body{
          // Explicit return type is needed otherwise it returns copy of object
          []() -> const Octet16& { return return_value; }};
  const Octet16& operator()() { return body(); }
};
extern struct BTM_GetDeviceDHK BTM_GetDeviceDHK;

// Name: BTM_GetDeviceEncRoot
// Params:
// Return: const Octet16&
struct BTM_GetDeviceEncRoot {
  static const Octet16 return_value;
  std::function<const Octet16&()> body{
          // Explicit return type is needed otherwise it returns copy of object
          []() -> const Octet16& { return return_value; }};
  const Octet16& operator()() { return body(); }
};
extern struct BTM_GetDeviceEncRoot BTM_GetDeviceEncRoot;

// Name: BTM_GetDeviceIDRoot
// Params:
// Return: const Octet16&
struct BTM_GetDeviceIDRoot {
  static const Octet16 return_value;
  std::function<const Octet16&()> body{
          // Explicit return type is needed otherwise it returns copy of object
          []() -> const Octet16& { return return_value; }};
  const Octet16& operator()() { return body(); }
};
extern struct BTM_GetDeviceIDRoot BTM_GetDeviceIDRoot;

// Name: BTM_SecAddBleDevice
// Params: const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE
// addr_type Return: void
struct BTM_SecAddBleDevice {
  std::function<void(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE addr_type)>
          body{[](const RawAddress& /* bd_addr */, tBT_DEVICE_TYPE /* dev_type */,
                  tBLE_ADDR_TYPE /* addr_type */) {}};
  void operator()(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE addr_type) {
    body(bd_addr, dev_type, addr_type);
  }
};
extern struct BTM_SecAddBleDevice BTM_SecAddBleDevice;

// Name: BTM_GetRemoteDeviceName
// Params: const RawAddress& bd_addr, BD_NAME bd_name
// Return: bool
struct BTM_GetRemoteDeviceName {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, BD_NAME bd_name)> body{
          [](const RawAddress& /* bd_addr */, BD_NAME /* bd_name */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr, BD_NAME bd_name) { return body(bd_addr, bd_name); }
};
extern struct BTM_GetRemoteDeviceName BTM_GetRemoteDeviceName;

// Name: BTM_SecAddBleKey
// Params: const RawAddress& bd_addr, tBTM_LE_KEY_VALUE* p_le_key,
// tBTM_LE_KEY_TYPE key_type Return: void
struct BTM_SecAddBleKey {
  std::function<void(const RawAddress& bd_addr, tBTM_LE_KEY_VALUE* p_le_key,
                     tBTM_LE_KEY_TYPE key_type)>
          body{[](const RawAddress& /* bd_addr */, tBTM_LE_KEY_VALUE* /* p_le_key */,
                  tBTM_LE_KEY_TYPE /* key_type */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_LE_KEY_VALUE* p_le_key,
                  tBTM_LE_KEY_TYPE key_type) {
    body(bd_addr, p_le_key, key_type);
  }
};
extern struct BTM_SecAddBleKey BTM_SecAddBleKey;

// Name: BTM_SecurityGrant
// Params: const RawAddress& bd_addr, uint8_t res
// Return: void
struct BTM_SecurityGrant {
  std::function<void(const RawAddress& bd_addr, tBTM_STATUS res)> body{
          [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_STATUS res) { body(bd_addr, res); }
};
extern struct BTM_SecurityGrant BTM_SecurityGrant;

// Name: btm_ble_connected
// Params: const RawAddress& bda, uint16_t handle, uint8_t enc_mode, uint8_t
// role, tBLE_ADDR_TYPE addr_type, bool addr_matched, bool can_read_discoverable_characteristics
// Return: void
struct btm_ble_connected {
  std::function<void(const RawAddress& bda, uint16_t handle, uint8_t enc_mode, uint8_t role,
                     tBLE_ADDR_TYPE addr_type, bool addr_matched,
                     bool can_read_discoverable_characteristics)>
          body{[](const RawAddress& /* bda */, uint16_t /* handle */, uint8_t /* enc_mode */,
                  uint8_t /* role */, tBLE_ADDR_TYPE /* addr_type */, bool /* addr_matched */,
                  bool /* can_read_discoverable_characteristics */) {}};
  void operator()(const RawAddress& bda, uint16_t handle, uint8_t enc_mode, uint8_t role,
                  tBLE_ADDR_TYPE addr_type, bool addr_matched,
                  bool can_read_discoverable_characteristics) {
    body(bda, handle, enc_mode, role, addr_type, addr_matched,
         can_read_discoverable_characteristics);
  }
};
extern struct btm_ble_connected btm_ble_connected;

// Name: btm_ble_connection_established
// Params: const RawAddress& bda Return: void
struct btm_ble_connection_established {
  std::function<void(const RawAddress& bda)> body{[](const RawAddress& /* bda */) {}};
  void operator()(const RawAddress& bda) { body(bda); }
};
extern struct btm_ble_connection_established btm_ble_connection_established;

// Name: btm_ble_get_acl_remote_addr
// Params: uint16_t hci_handle, RawAddress& conn_addr, tBLE_ADDR_TYPE*
// p_addr_type Return: bool
struct btm_ble_get_acl_remote_addr {
  static bool return_value;
  std::function<bool(uint16_t hci_handle, RawAddress& conn_addr, tBLE_ADDR_TYPE* p_addr_type)> body{
          [](uint16_t /* hci_handle */, RawAddress& /* conn_addr */,
             tBLE_ADDR_TYPE* /* p_addr_type */) { return return_value; }};
  bool operator()(uint16_t hci_handle, RawAddress& conn_addr, tBLE_ADDR_TYPE* p_addr_type) {
    return body(hci_handle, conn_addr, p_addr_type);
  }
};
extern struct btm_ble_get_acl_remote_addr btm_ble_get_acl_remote_addr;

// Name: btm_ble_get_enc_key_type
// Params: const RawAddress& bd_addr, uint8_t* p_key_types
// Return: bool
struct btm_ble_get_enc_key_type {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, uint8_t* p_key_types)> body{
          [](const RawAddress& /* bd_addr */, uint8_t* /* p_key_types */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr, uint8_t* p_key_types) {
    return body(bd_addr, p_key_types);
  }
};
extern struct btm_ble_get_enc_key_type btm_ble_get_enc_key_type;

// Name: btm_ble_link_encrypted
// Params: const RawAddress& bd_addr, uint8_t encr_enable
// Return: void
struct btm_ble_link_encrypted {
  std::function<void(const RawAddress& bd_addr, uint8_t encr_enable)> body{
          [](const RawAddress& /* bd_addr */, uint8_t /* encr_enable */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t encr_enable) { body(bd_addr, encr_enable); }
};
extern struct btm_ble_link_encrypted btm_ble_link_encrypted;

// Name: btm_ble_link_sec_check
// Params: const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req,
// tBTM_BLE_SEC_REQ_ACT* p_sec_req_act Return: void
struct btm_ble_link_sec_check {
  std::function<void(const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req,
                     tBTM_BLE_SEC_REQ_ACT* p_sec_req_act)>
          body{[](const RawAddress& /* bd_addr */, tBTM_LE_AUTH_REQ /* auth_req */,
                  tBTM_BLE_SEC_REQ_ACT* /* p_sec_req_act */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req,
                  tBTM_BLE_SEC_REQ_ACT* p_sec_req_act) {
    body(bd_addr, auth_req, p_sec_req_act);
  }
};
extern struct btm_ble_link_sec_check btm_ble_link_sec_check;

// Name: btm_ble_ltk_request
// Params: uint16_t handle, uint8_t rand[8], uint16_t ediv
// Return: void
struct btm_ble_ltk_request {
  std::function<void(uint16_t handle, BT_OCTET8 rand, uint16_t ediv)> body{
          [](uint16_t /* handle */, BT_OCTET8 /* rand */, uint16_t /* ediv */) {}};
  void operator()(uint16_t handle, BT_OCTET8 rand, uint16_t ediv) { body(handle, rand, ediv); }
};
extern struct btm_ble_ltk_request btm_ble_ltk_request;

// Name: btm_ble_ltk_request_reply
// Params: const RawAddress& bda, bool use_stk, const Octet16& stk
// Return: void
struct btm_ble_ltk_request_reply {
  std::function<void(const RawAddress& bda, bool use_stk, const Octet16& stk)> body{
          [](const RawAddress& /* bda */, bool /* use_stk */, const Octet16& /* stk */) {}};
  void operator()(const RawAddress& bda, bool use_stk, const Octet16& stk) {
    body(bda, use_stk, stk);
  }
};
extern struct btm_ble_ltk_request_reply btm_ble_ltk_request_reply;

// Name: btm_ble_read_sec_key_size
// Params: const RawAddress& bd_addr
// Return: uint8_t
struct btm_ble_read_sec_key_size {
  static uint8_t return_value;
  std::function<uint8_t(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  uint8_t operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct btm_ble_read_sec_key_size btm_ble_read_sec_key_size;

// Name: btm_ble_reset_id
// Params: void
// Return: void
struct btm_ble_reset_id {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btm_ble_reset_id btm_ble_reset_id;

// Name: btm_ble_set_encryption
// Params: const RawAddress& bd_addr, tBTM_BLE_SEC_ACT sec_act, uint8_t
// link_role Return: tBTM_STATUS
struct btm_ble_set_encryption {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, tBTM_BLE_SEC_ACT sec_act, uint8_t link_role)>
          body{[](const RawAddress& /* bd_addr */, tBTM_BLE_SEC_ACT /* sec_act */,
                  uint8_t /* link_role */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, tBTM_BLE_SEC_ACT sec_act, uint8_t link_role) {
    return body(bd_addr, sec_act, link_role);
  }
};
extern struct btm_ble_set_encryption btm_ble_set_encryption;

// Name: btm_ble_start_encrypt
// Params: const RawAddress& bda, bool use_stk, Octet16* p_stk
// Return: tBTM_STATUS
struct btm_ble_start_encrypt {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bda, bool use_stk, Octet16* p_stk)> body{
          [](const RawAddress& /* bda */, bool /* use_stk */, Octet16* /* p_stk */) {
            return return_value;
          }};
  tBTM_STATUS operator()(const RawAddress& bda, bool use_stk, Octet16* p_stk) {
    return body(bda, use_stk, p_stk);
  }
};
extern struct btm_ble_start_encrypt btm_ble_start_encrypt;

// Name: btm_ble_start_sec_check
// Params: const RawAddress& bd_addr, uint16_t psm, bool is_originator,
// tBTM_SEC_CALLBACK* p_callback, void* p_ref_data Return: tL2CAP_LE_RESULT_CODE
struct btm_ble_start_sec_check {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(const RawAddress& bd_addr, uint16_t psm, bool is_originator,
                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data)>
          body{[](const RawAddress& /* bd_addr */, uint16_t /* psm */, bool /* is_originator */,
                  tBTM_SEC_CALLBACK* /* p_callback */,
                  void* /* p_ref_data */) { return return_value; }};
  tBTM_STATUS operator()(const RawAddress& bd_addr, uint16_t psm, bool is_originator,
                         tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
    return body(bd_addr, psm, is_originator, p_callback, p_ref_data);
  }
};
extern struct btm_ble_start_sec_check btm_ble_start_sec_check;

// Name: btm_ble_test_command_complete
// Params: uint8_t* p
// Return: void
struct btm_ble_test_command_complete {
  std::function<void(uint8_t* p)> body{[](uint8_t* /* p */) {}};
  void operator()(uint8_t* p) { body(p); }
};
extern struct btm_ble_test_command_complete btm_ble_test_command_complete;

// Name: btm_ble_update_sec_key_size
// Params: const RawAddress& bd_addr, uint8_t enc_key_size
// Return: void
struct btm_ble_update_sec_key_size {
  std::function<void(const RawAddress& bd_addr, uint8_t enc_key_size)> body{
          [](const RawAddress& /* bd_addr */, uint8_t /* enc_key_size */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t enc_key_size) { body(bd_addr, enc_key_size); }
};
extern struct btm_ble_update_sec_key_size btm_ble_update_sec_key_size;

// Name: btm_get_local_div
// Params: const RawAddress& bd_addr, uint16_t* p_div
// Return: bool
struct btm_get_local_div {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr, uint16_t* p_div)> body{
          [](const RawAddress& /* bd_addr */, uint16_t* /* p_div */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr, uint16_t* p_div) { return body(bd_addr, p_div); }
};
extern struct btm_get_local_div btm_get_local_div;

// Name: btm_proc_smp_cback
// Params: tSMP_EVT event, const RawAddress& bd_addr, const tSMP_EVT_DATA*
// p_data Return: tBTM_STATUS
struct btm_proc_smp_cback {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(tSMP_EVT event, const RawAddress& bd_addr, tSMP_EVT_DATA* p_data)> body{
          [](tSMP_EVT /* event */, const RawAddress& /* bd_addr */,
             const tSMP_EVT_DATA* /* p_data */) { return return_value; }};
  tBTM_STATUS operator()(tSMP_EVT event, const RawAddress& bd_addr, tSMP_EVT_DATA* p_data) {
    return body(event, bd_addr, p_data);
  }
};
extern struct btm_proc_smp_cback btm_proc_smp_cback;

// Name: btm_sec_save_le_key
// Params: const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
// tBTM_LE_KEY_VALUE* p_keys, bool pass_to_application Return: void
struct btm_sec_save_le_key {
  std::function<void(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                     tBTM_LE_KEY_VALUE* p_keys, bool pass_to_application)>
          body{[](const RawAddress& /* bd_addr */, tBTM_LE_KEY_TYPE /* key_type */,
                  tBTM_LE_KEY_VALUE* /* p_keys */, bool /* pass_to_application */) {}};
  void operator()(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type, tBTM_LE_KEY_VALUE* p_keys,
                  bool pass_to_application) {
    body(bd_addr, key_type, p_keys, pass_to_application);
  }
};
extern struct btm_sec_save_le_key btm_sec_save_le_key;

}  // namespace stack_btm_ble
}  // namespace mock
}  // namespace test

// END mockcify generation
