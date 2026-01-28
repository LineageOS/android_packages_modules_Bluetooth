/*
 * Copyright 2025 The Android Open Source Project
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

#define LOG_TAG "btm_sec_api"

#include "stack/include/btm_sec_api.h"

#include <bluetooth/log.h>
#include <bluetooth/types/bt_transport.h>

#include "stack/btm/btm_ble_sec.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_security.h"
#include "stack/include/security_client_callbacks.h"

using namespace bluetooth;

void BTM_Sec_Init() { BtmSecurity::Get().Init(); }

void BTM_Sec_Free() { BtmSecurity::Get().Free(); }

void BTM_SetPinType(uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len) {
  btm_set_pin_type(pin_type, pin_code, pin_code_len);
}

tBTM_LINK_KEY_TYPE BTM_SecGetDeviceLinkKeyType(const RawAddress& bd_addr) {
  return btm_sec_get_device_link_key_type(bd_addr);
}

void BTM_ConfirmReqReply(tBTM_STATUS res, const RawAddress& bd_addr) {
  btm_confirm_req_reply(res, bd_addr);
}

void BTM_PasskeyReqReply(tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey) {
  btm_passkey_req_reply(res, bd_addr, passkey);
}

void BTM_ReadLocalOobData(void) { btm_read_local_oob_data(); }

bool BTM_PeerSupportsSecureConnections(const RawAddress& bd_addr) {
  return btm_peer_supports_secure_connections(bd_addr);
}

bool BTM_SecRegister(const tBTM_APPL_INFO* p_cb_info) { return btm_sec_register(p_cb_info); }

void BTM_BleLoadLocalKeys(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) {
  btm_ble_load_local_keys(key_type, p_key);
}

void BTM_SecAddDevice(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                      const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                      uint8_t pin_length) {
  btm_sec_add_device(bd_addr, dev_class, pairing_type, link_key, key_type, pin_length);
}

void BTM_SecAddBleDevice(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                         tBLE_ADDR_TYPE addr_type) {
  btm_sec_add_ble_device(bd_addr, dev_type, addr_type);
}

bool BTM_SecDeleteDevice(const RawAddress& bd_addr) { return btm_sec_delete_device(bd_addr); }

void BTM_SecAddBleKey(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                      const tBTM_LE_KEY_VALUE& key) {
  btm_sec_add_ble_key(bd_addr, key_type, key);
}

void BTM_SecClearSecurityFlags(const RawAddress& bd_addr) { btm_sec_clear_security_flags(bd_addr); }

tBTM_STATUS BTM_SetEncryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                              tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                              tBTM_BLE_SEC_ACT sec_act) {
  return btm_set_encryption(bd_addr, transport, p_callback, p_ref_data, sec_act);
}

bool BTM_IsEncrypted(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  return btm_is_encrypted(bd_addr, transport);
}

bool BTM_SecIsLeSecurityPending(const RawAddress& bd_addr) {
  return btm_sec_is_le_security_pending(bd_addr);
}

bool BTM_IsBonded(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  return btm_is_bonded(bd_addr, transport);
}

bool BTM_SetSecurityLevel(bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
                          uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id) {
  return btm_set_security_level(outgoing, p_name, service_id, sec_level, psm, mx_proto_id,
                                mx_chan_id);
}

uint8_t BTM_SecClrService(uint8_t service_id) { return btm_sec_clr_service(service_id); }

uint8_t BTM_SecClrServiceByPsm(uint16_t psm) { return btm_sec_clr_service_by_psm(psm); }

tBTM_STATUS BTM_SecBond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                        tBT_TRANSPORT transport) {
  return btm_sec_bond(bd_addr, addr_type, transport);
}

tBTM_STATUS BTM_SecBondCancel(const RawAddress& bd_addr) { return btm_sec_bond_cancel(bd_addr); }

void BTM_RemoteOobDataReply(tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c,
                            const Octet16& r) {
  btm_remote_oob_data_reply(res, bd_addr, c, r);
}

void BTM_PINCodeReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len,
                      PinCode pin_code) {
  btm_pin_code_reply(bd_addr, res, pin_len, pin_code);
}

void BTM_SecConfirmReqReply(tBTM_STATUS res, tBT_TRANSPORT transport, const RawAddress bd_addr) {
  if (transport == BT_TRANSPORT_BR_EDR) {
    get_security_client_interface().BTM_ConfirmReqReply(res, bd_addr);
  } else if (transport == BT_TRANSPORT_LE) {
    btm_ble_confirm_reply(bd_addr, res);
  } else {
    log::error("Unexpected transport:{}", transport);
  }
}

void BTM_BleSirkConfirmDeviceReply(const RawAddress& bd_addr, tBTM_STATUS res) {
  btm_ble_sirk_confirm_device_reply(bd_addr, res);
}

void BTM_BlePasskeyReply(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey) {
  btm_ble_passkey_reply(bd_addr, res, passkey);
}

uint8_t BTM_BleReadSecKeySize(const RawAddress& bd_addr) {
  return btm_ble_read_sec_key_size(bd_addr);
}

void BTM_SecHciDeleteStoredLinkKey(const RawAddress& bd_addr) {
  btm_sec_hci_delete_stored_link_key(bd_addr);
}

uint8_t BTM_GetSecurityMode() { return btm_get_security_mode(); }

const char* BTM_SecReadDevName(const RawAddress& bd_addr) { return btm_sec_read_dev_name(bd_addr); }

DEV_CLASS BTM_SecReadDevClass(const RawAddress& bd_addr) { return btm_sec_read_dev_class(bd_addr); }

tBTM_STATUS BTM_SecReportBondLoss(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  return btm_sec_report_bond_loss(bd_addr, transport);
}

const Octet16& BTM_GetDeviceEncRoot() { return btm_get_device_enc_root(); }

const Octet16& BTM_GetDeviceIDRoot() { return btm_get_device_id_root(); }

const Octet16& BTM_GetDeviceDHK() { return btm_get_device_dhk(); }

void BTM_SecurityGrant(const RawAddress& bd_addr, tBTM_STATUS res) {
  btm_security_grant(bd_addr, res);
}

void BTM_BleConfirmReply(const RawAddress& bd_addr, tBTM_STATUS res) {
  btm_ble_confirm_reply(bd_addr, res);
}

void BTM_BleOobDataReply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len, uint8_t* p_data) {
  btm_ble_oob_data_reply(bd_addr, res, len, p_data);
}

void BTM_BleSecureConnectionOobDataReply(const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r) {
  btm_ble_secure_connection_oob_data_reply(bd_addr, p_c, p_r);
}

bool BTM_BleDataSignature(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                          BLE_SIGNATURE signature) {
  return btm_ble_data_signature(bd_addr, p_text, len, signature);
}

bool BTM_BleVerifySignature(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                            uint32_t counter, uint8_t* p_comp) {
  return btm_ble_verify_signature(bd_addr, p_orig, len, counter, p_comp);
}

std::optional<Octet16> BTM_BleGetPeerLTK(const RawAddress address) {
  return btm_ble_get_peer_ltk(address);
}

std::optional<Octet16> BTM_BleGetPeerIRK(const RawAddress address) {
  return btm_ble_get_peer_irk(address);
}

std::optional<tBLE_BD_ADDR> BTM_BleGetIdentityAddress(const RawAddress address) {
  return btm_ble_get_identity_address(address);
}

static tBTM_BLE_SEC_REQ_ACT BTM_BleLinkSecCheck(const RawAddress& bd_addr,
                                                tBTM_LE_AUTH_REQ auth_req) {
  return btm_ble_link_sec_check(bd_addr, auth_req);
}

static void BTM_BleLtkRequestReply(const RawAddress& bda, bool use_stk, const Octet16& stk) {
  btm_ble_ltk_request_reply(bda, use_stk, stk);
}

static tBTM_STATUS BTM_BleStartEncrypt(const RawAddress& bda, bool use_stk, Octet16* p_stk) {
  return btm_ble_start_encrypt(bda, use_stk, p_stk);
}

static tBTM_STATUS BTM_BleStartSecCheck(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                        tBTM_SEC_CALLBACK* p_callback, void* p_ref_data) {
  return btm_ble_start_sec_check(bd_addr, psm, outgoing, p_callback, p_ref_data);
}

static bool BTM_GetLocalDiv(const RawAddress& bd_addr, uint16_t* p_div) {
  return btm_get_local_div(bd_addr, p_div);
}

static bool BTM_BleGetEncKeyType(const RawAddress& bd_addr, uint8_t* p_key_types) {
  return btm_ble_get_enc_key_type(bd_addr, p_key_types);
}

static void BTM_SecSaveLeKey(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                             const tBTM_LE_KEY_VALUE& key, bool pass_to_application) {
  btm_sec_save_le_key(bd_addr, key_type, key, pass_to_application);
}

static void BTM_BleUpdateSecKeySize(const RawAddress& bd_addr, uint8_t enc_key_size) {
  btm_ble_update_sec_key_size(bd_addr, enc_key_size);
}

static void BTM_BleResetId(void) { btm_ble_reset_id(); }

static SecurityClientInterface security = {
        .BTM_Sec_Init = BTM_Sec_Init,
        .BTM_Sec_Free = BTM_Sec_Free,
        .BTM_SetPinType = BTM_SetPinType,
        .BTM_SecGetDeviceLinkKeyType = BTM_SecGetDeviceLinkKeyType,
        .BTM_ConfirmReqReply = BTM_ConfirmReqReply,
        .BTM_PasskeyReqReply = BTM_PasskeyReqReply,
        .BTM_ReadLocalOobData = BTM_ReadLocalOobData,
        .BTM_PeerSupportsSecureConnections = BTM_PeerSupportsSecureConnections,
        .BTM_SecRegister = BTM_SecRegister,

        .BTM_BleLoadLocalKeys = BTM_BleLoadLocalKeys,

        .BTM_SecAddDevice = BTM_SecAddDevice,
        .BTM_SecAddBleDevice = BTM_SecAddBleDevice,
        .BTM_SecDeleteDevice = BTM_SecDeleteDevice,
        .BTM_SecAddBleKey = BTM_SecAddBleKey,
        .BTM_SecClearSecurityFlags = BTM_SecClearSecurityFlags,
        .BTM_SetEncryption = BTM_SetEncryption,
        .BTM_IsEncrypted = BTM_IsEncrypted,
        .BTM_SecIsLeSecurityPending = BTM_SecIsLeSecurityPending,
        .BTM_IsBonded = BTM_IsBonded,

        .BTM_SetSecurityLevel = BTM_SetSecurityLevel,
        .BTM_SecClrService = BTM_SecClrService,
        .BTM_SecClrServiceByPsm = BTM_SecClrServiceByPsm,

        .BTM_SecBond = BTM_SecBond,
        .BTM_SecBondCancel = BTM_SecBondCancel,
        .BTM_RemoteOobDataReply = BTM_RemoteOobDataReply,
        .BTM_PINCodeReply = BTM_PINCodeReply,
        .BTM_SecConfirmReqReply = BTM_SecConfirmReqReply,
        .BTM_BleSirkConfirmDeviceReply = BTM_BleSirkConfirmDeviceReply,
        .BTM_BlePasskeyReply = BTM_BlePasskeyReply,

        .BTM_BleReadSecKeySize = BTM_BleReadSecKeySize,
        .BTM_SecHciDeleteStoredLinkKey = BTM_SecHciDeleteStoredLinkKey,

        .BTM_GetSecurityMode = BTM_GetSecurityMode,

        .BTM_SecReadDevName = BTM_SecReadDevName,
        .BTM_SecReadDevClass = BTM_SecReadDevClass,
        .BTM_SecReportBondLoss = BTM_SecReportBondLoss,

        .BTM_GetDeviceEncRoot = BTM_GetDeviceEncRoot,
        .BTM_GetDeviceIDRoot = BTM_GetDeviceIDRoot,
        .BTM_GetDeviceDHK = BTM_GetDeviceDHK,
        .BTM_SecurityGrant = BTM_SecurityGrant,
        .BTM_BleConfirmReply = BTM_BleConfirmReply,
        .BTM_BleOobDataReply = BTM_BleOobDataReply,
        .BTM_BleSecureConnectionOobDataReply = BTM_BleSecureConnectionOobDataReply,
        .BTM_BleDataSignature = BTM_BleDataSignature,
        .BTM_BleVerifySignature = BTM_BleVerifySignature,
        .BTM_BleGetPeerLTK = BTM_BleGetPeerLTK,
        .BTM_BleGetPeerIRK = BTM_BleGetPeerIRK,
        .BTM_BleGetIdentityAddress = BTM_BleGetIdentityAddress,
        .BTM_BleLinkSecCheck = BTM_BleLinkSecCheck,
        .BTM_BleLtkRequestReply = BTM_BleLtkRequestReply,
        .BTM_BleStartEncrypt = BTM_BleStartEncrypt,
        .BTM_BleStartSecCheck = BTM_BleStartSecCheck,
        .BTM_GetLocalDiv = BTM_GetLocalDiv,
        .BTM_BleGetEncKeyType = BTM_BleGetEncKeyType,
        .BTM_SecSaveLeKey = BTM_SecSaveLeKey,
        .BTM_BleUpdateSecKeySize = BTM_BleUpdateSecKeySize,
        .BTM_BleResetId = BTM_BleResetId,
};

const SecurityClientInterface& get_security_client_interface() { return security; }
