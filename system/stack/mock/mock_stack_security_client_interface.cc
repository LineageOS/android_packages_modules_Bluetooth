/*
 * Copyright 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

#include "stack/mock/mock_stack_security_client_interface.h"

#include "stack/include/security_client_callbacks.h"

namespace {

static Octet16 dummy_octet16;

// Initialize the working btm client interface to the default
MockSecurityClientInterface default_mock_security_client_interface;

// Initialize the working btm client interface to the default
MockSecurityClientInterface* mock_security_client_interface =
        &default_mock_security_client_interface;

// Security client interface with callbacks pointing to the mock interface.
const SecurityClientInterface security_client_interface = {
        .BTM_Sec_Init = []() { mock_security_client_interface->BTM_Sec_Init(); },
        .BTM_Sec_Free = []() { mock_security_client_interface->BTM_Sec_Free(); },
        .BTM_SetPinType = [](uint8_t /* pin_type */, PinCode /* pin_code */,
                             uint8_t /* pin_code_len */) {},
        .BTM_SecGetDeviceLinkKeyType = [](const RawAddress& /* bd_addr */) -> tBTM_LINK_KEY_TYPE {
          return BTM_LKEY_TYPE_IGNORE;
        },
        .BTM_ConfirmReqReply = [](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */) {},
        .BTM_PasskeyReqReply = [](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */,
                                  uint32_t /* passkey */) {},
        .BTM_ReadLocalOobData = []() {},
        .BTM_PeerSupportsSecureConnections = [](const RawAddress& /* bd_addr */) -> bool {
          return false;
        },
        .BTM_SecRegister = [](const BtmAppReg& app_reg) -> bool {
          return mock_security_client_interface->BTM_SecRegister(app_reg);
        },
        .BTM_BleLoadLocalKeys =
                [](uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) {
                  mock_security_client_interface->BTM_BleLoadLocalKeys(key_type, p_key);
                },
        .BTM_SecAddDevice =
                [](const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                   const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                   uint8_t pin_length) {
                  mock_security_client_interface->BTM_SecAddDevice(bd_addr, dev_class, pairing_type,
                                                                   link_key, key_type, pin_length);
                },
        .BTM_SecAddBleDevice =
                [](const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE addr_type) {
                  mock_security_client_interface->BTM_SecAddBleDevice(bd_addr, dev_type, addr_type);
                },
        .BTM_SecDeleteDevice = [](const RawAddress& bd_addr) -> bool {
          return mock_security_client_interface->BTM_SecDeleteDevice(bd_addr);
        },
        .BTM_SecAddBleKey =
                [](const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                   const tBTM_LE_KEY_VALUE& key) {
                  mock_security_client_interface->BTM_SecAddBleKey(bd_addr, key_type, key);
                },
        .BTM_SecClearSecurityFlags =
                [](const RawAddress& bd_addr) {
                  mock_security_client_interface->BTM_SecClearSecurityFlags(bd_addr);
                },
        .BTM_SetEncryption = [](const RawAddress& bd_addr, tBT_TRANSPORT transport,
                                tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                                tBTM_BLE_SEC_ACT sec_act) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_SetEncryption(bd_addr, transport, p_callback,
                                                                   p_ref_data, sec_act);
        },
        .BTM_IsEncrypted = [](const RawAddress& bd_addr, tBT_TRANSPORT transport) -> bool {
          return mock_security_client_interface->BTM_IsEncrypted(bd_addr, transport);
        },
        .BTM_SecIsLeSecurityPending = [](const RawAddress& bd_addr) -> bool {
          return mock_security_client_interface->BTM_SecIsLeSecurityPending(bd_addr);
        },
        .BTM_IsBonded = [](const RawAddress& bd_addr, tBT_TRANSPORT transport) -> bool {
          return mock_security_client_interface->BTM_IsBonded(bd_addr, transport);
        },
        .BTM_SetSecurityLevel = [](bool outgoing, const char* p_name, uint8_t service_id,
                                   uint16_t sec_level, uint16_t psm, uint32_t mx_proto_id,
                                   uint32_t mx_chan_id) -> bool {
          return mock_security_client_interface->BTM_SetSecurityLevel(
                  outgoing, p_name, service_id, sec_level, psm, mx_proto_id, mx_chan_id);
        },
        .BTM_SecClrService = [](uint8_t service_id) -> uint8_t {
          return mock_security_client_interface->BTM_SecClrService(service_id);
        },
        .BTM_SecClrServiceByPsm = [](uint16_t psm) -> uint8_t {
          return mock_security_client_interface->BTM_SecClrServiceByPsm(psm);
        },
        .BTM_SecBond = [](const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                          tBT_TRANSPORT transport) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_SecBond(bd_addr, addr_type, transport);
        },
        .BTM_SecBondCancel = [](const RawAddress& bd_addr) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_SecBondCancel(bd_addr);
        },
        .BTM_RemoteOobDataReply =
                [](tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c, const Octet16& r) {
                  mock_security_client_interface->BTM_RemoteOobDataReply(res, bd_addr, c, r);
                },
        .BTM_PINCodeReply =
                [](const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len, PinCode pin_code) {
                  mock_security_client_interface->BTM_PINCodeReply(bd_addr, res, pin_len, pin_code);
                },
        .BTM_SecConfirmReqReply =
                [](tBTM_STATUS res, tBT_TRANSPORT transport, const RawAddress bd_addr) {
                  mock_security_client_interface->BTM_SecConfirmReqReply(res, transport, bd_addr);
                },
        .BTM_BleSirkConfirmDeviceReply =
                [](const RawAddress& bd_addr, tBTM_STATUS res) {
                  mock_security_client_interface->BTM_BleSirkConfirmDeviceReply(bd_addr, res);
                },
        .BTM_BlePasskeyReply =
                [](const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey) {
                  mock_security_client_interface->BTM_BlePasskeyReply(bd_addr, res, passkey);
                },
        .BTM_BleReadSecKeySize = [](const RawAddress& bd_addr) -> uint8_t {
          return mock_security_client_interface->BTM_BleReadSecKeySize(bd_addr);
        },
        .BTM_SecHciDeleteStoredLinkKey = [](const RawAddress& /* bd_addr */) {},
        .BTM_GetSecurityMode = []() -> uint8_t {
          return mock_security_client_interface->BTM_GetSecurityMode();
        },
        .BTM_SecReadDevName = [](const RawAddress& bd_addr) -> const char* {
          return mock_security_client_interface->BTM_SecReadDevName(bd_addr);
        },
        .BTM_SecReadDevClass = [](const RawAddress& bd_addr) -> DEV_CLASS {
          return mock_security_client_interface->BTM_SecReadDevClass(bd_addr);
        },
        .BTM_SecReportBondLoss = [](const RawAddress& bd_addr,
                                    tBT_TRANSPORT transport) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_SecReportBondLoss(bd_addr, transport);
        },
        .BTM_GetDeviceEncRoot = []() -> const Octet16& { return dummy_octet16; },
        .BTM_GetDeviceIDRoot = []() -> const Octet16& { return dummy_octet16; },
        .BTM_GetDeviceDHK = []() -> const Octet16& { return dummy_octet16; },

        .BTM_SecurityGrant = [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */) {},
        .BTM_BleConfirmReply = [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */) {},
        .BTM_BleOobDataReply = [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */,
                                  uint8_t /* len */, uint8_t* /* p_data */) {},
        .BTM_BleSecureConnectionOobDataReply = [](const RawAddress& /* bd_addr */,
                                                  uint8_t* /* p_c */, uint8_t* /* p_r */) {},
        .BTM_BleDataSignature = [](const RawAddress& /* bd_addr */, uint8_t* /* p_text */,
                                   uint16_t /* len */,
                                   BLE_SIGNATURE /* signature */) -> bool { return false; },
        .BTM_BleVerifySignature = [](const RawAddress& /* bd_addr */, uint8_t* /* p_orig */,
                                     uint16_t /* len */, uint32_t /* counter */,
                                     uint8_t* /* p_comp */) -> bool { return false; },
        .BTM_BleGetPeerLTK = [](const RawAddress /* address */) -> std::optional<Octet16> {
          return std::nullopt;
        },
        .BTM_BleGetPeerIRK = [](const RawAddress /* address */) -> std::optional<Octet16> {
          return std::nullopt;
        },
        .BTM_BleGetIdentityAddress = [](const RawAddress /* address */)
                -> std::optional<tBLE_BD_ADDR> { return std::nullopt; },
        .BTM_BleLinkSecCheck = [](const RawAddress& bd_addr,
                                  tBTM_LE_AUTH_REQ auth_req) -> tBTM_BLE_SEC_REQ_ACT {
          return mock_security_client_interface->BTM_BleLinkSecCheck(bd_addr, auth_req);
        },
        .BTM_BleLtkRequestReply =
                [](const RawAddress& bda, bool use_stk, const Octet16& stk) {
                  mock_security_client_interface->BTM_BleLtkRequestReply(bda, use_stk, stk);
                },
        .BTM_BleStartEncrypt = [](const RawAddress& bda, bool use_stk,
                                  Octet16* p_stk) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_BleStartEncrypt(bda, use_stk, p_stk);
        },
        .BTM_BleStartSecCheck = [](const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                   tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) -> tBTM_STATUS {
          return mock_security_client_interface->BTM_BleStartSecCheck(bd_addr, psm, outgoing,
                                                                      p_callback, p_ref_data);
        },
        .BTM_GetLocalDiv = [](const RawAddress& /* bd_addr */, uint16_t* /* p_div */) -> bool {
          return false;
        },
        .BTM_BleGetEncKeyType = [](const RawAddress& /* bd_addr */,
                                   uint8_t* /* p_key_types */) -> bool { return false; },
        .BTM_SecSaveLeKey =
                [](const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                   const tBTM_LE_KEY_VALUE& key, bool pass_to_application) {
                  mock_security_client_interface->BTM_SecSaveLeKey(bd_addr, key_type, key,
                                                                   pass_to_application);
                },
        .BTM_BleUpdateSecKeySize = [](const RawAddress& /* bd_addr */,
                                      uint8_t /* enc_key_size */) {},
        .BTM_BleResetId = []() {},
};

}  // namespace

// Reset the working btm client interface to the default
void reset_mock_security_client_interface() {
  mock_security_client_interface = &default_mock_security_client_interface;
}

// Serve the working mock security interface
const SecurityClientInterface& get_security_client_interface() { return security_client_interface; }

void set_security_client_interface(MockSecurityClientInterface& interface) {
  mock_security_client_interface = &interface;
}
