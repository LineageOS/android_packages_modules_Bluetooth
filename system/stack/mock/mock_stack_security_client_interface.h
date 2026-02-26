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

#pragma once

#include <gmock/gmock.h>

#include "stack/include/btm_sec_api.h"

class MockSecurityClientInterface : public SecurityClientInterface {
public:
  // clang-format off
  MOCK_METHOD(void, BTM_Sec_Init, (), (const, override));
  MOCK_METHOD(void, BTM_Sec_Free, (), (const, override));
  MOCK_METHOD(void, BTM_SetPinType, (uint8_t pin_type, PinCode pin_code, uint8_t pin_code_len),
              (const, override));
  MOCK_METHOD(tBTM_LINK_KEY_TYPE, BTM_SecGetDeviceLinkKeyType, (const RawAddress& bd_addr),
              (const, override));
  MOCK_METHOD(void, BTM_ConfirmReqReply, (tBTM_STATUS res, const RawAddress& bd_addr),
              (const, override));
  MOCK_METHOD(void, BTM_PasskeyReqReply,
              (tBTM_STATUS res, const RawAddress& bd_addr, uint32_t passkey), (const, override));
  MOCK_METHOD(void, BTM_ReadLocalOobData, (), (const, override));
  MOCK_METHOD(bool, BTM_PeerSupportsSecureConnections, (const RawAddress& bd_addr),
              (const, override));
  MOCK_METHOD(bool, BTM_SecRegister, (const BtmAppReg& app_reg), (const, override));
  MOCK_METHOD(void, BTM_BleLoadLocalKeys, (uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key),
              (const, override));
  MOCK_METHOD(void, BTM_SecAddDevice,
              (const RawAddress& bd_addr, const DEV_CLASS& dev_class,
               const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
               uint8_t pin_length),
              (const, override));
  MOCK_METHOD(void, BTM_SecAddBleDevice,
              (const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE addr_type),
              (const, override));
  MOCK_METHOD(bool, BTM_SecDeleteDevice, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(void, BTM_SecAddBleKey,
              (const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type, const tBTM_LE_KEY_VALUE& key),
              (const, override));
  MOCK_METHOD(void, BTM_SecClearSecurityFlags, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_SetEncryption,
              (const RawAddress& bd_addr, tBT_TRANSPORT transport, tBTM_SEC_CALLBACK* p_callback,
               void* p_ref_data, tBTM_BLE_SEC_ACT sec_act),
              (const, override));
  MOCK_METHOD(bool, BTM_IsEncrypted, (const RawAddress& bd_addr, tBT_TRANSPORT transport),
              (const, override));
  MOCK_METHOD(bool, BTM_SecIsLeSecurityPending, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(bool, BTM_IsBonded, (const RawAddress& bd_addr, tBT_TRANSPORT transport),
              (const, override));
  MOCK_METHOD(bool, BTM_SetSecurityLevel,
              (bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
               uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id),
              (const, override));
  MOCK_METHOD(uint8_t, BTM_SecClrService, (uint8_t service_id), (const, override));
  MOCK_METHOD(uint8_t, BTM_SecClrServiceByPsm, (uint16_t psm), (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_SecBond,
              (const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport),
              (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_SecBondCancel, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(void, BTM_RemoteOobDataReply,
              (tBTM_STATUS res, const RawAddress& bd_addr, const Octet16& c, const Octet16& r),
              (const, override));
  MOCK_METHOD(void, BTM_PINCodeReply,
              (const RawAddress& bd_addr, tBTM_STATUS res, uint8_t pin_len, PinCode pin_code),
              (const, override));
  MOCK_METHOD(void, BTM_SecConfirmReqReply,
              (tBTM_STATUS res, tBT_TRANSPORT transport, const RawAddress bd_addr),
              (const, override));
  MOCK_METHOD(void, BTM_BleSirkConfirmDeviceReply, (const RawAddress& bd_addr, tBTM_STATUS res),
              (const, override));
  MOCK_METHOD(void, BTM_BlePasskeyReply,
              (const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey), (const, override));
  MOCK_METHOD(uint8_t, BTM_BleReadSecKeySize, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(void, BTM_SecHciDeleteStoredLinkKey, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(uint8_t, BTM_GetSecurityMode, (), (const, override));
  MOCK_METHOD(const char*, BTM_SecReadDevName, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(DEV_CLASS, BTM_SecReadDevClass, (const RawAddress& bd_addr), (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_SecReportBondLoss,
              (const RawAddress& bd_addr, tBT_TRANSPORT transport), (const, override));
  MOCK_METHOD(const Octet16&, BTM_GetDeviceEncRoot, (), (const, override));
  MOCK_METHOD(const Octet16&, BTM_GetDeviceIDRoot, (), (const, override));
  MOCK_METHOD(const Octet16&, BTM_GetDeviceDHK, (), (const, override));
  MOCK_METHOD(void, BTM_SecurityGrant, (const RawAddress& bd_addr, tBTM_STATUS res),
              (const, override));
  MOCK_METHOD(void, BTM_BleConfirmReply, (const RawAddress& bd_addr, tBTM_STATUS res),
              (const, override));
  MOCK_METHOD(void, BTM_BleOobDataReply,
              (const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len, uint8_t* p_data),
              (const, override));
  MOCK_METHOD(void, BTM_BleSecureConnectionOobDataReply,
              (const RawAddress& bd_addr, uint8_t* p_c, uint8_t* p_r), (const, override));
  MOCK_METHOD(bool, BTM_BleDataSignature,
              (const RawAddress& bd_addr, uint8_t* p_text, uint16_t len, BLE_SIGNATURE signature),
              (const, override));
  MOCK_METHOD(bool, BTM_BleVerifySignature,
              (const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len, uint32_t counter,
               uint8_t* p_comp),
              (const, override));
  MOCK_METHOD(std::optional<Octet16>, BTM_BleGetPeerLTK, (const RawAddress address),
              (const, override));
  MOCK_METHOD(std::optional<Octet16>, BTM_BleGetPeerIRK, (const RawAddress address),
              (const, override));
  MOCK_METHOD(std::optional<tBLE_BD_ADDR>, BTM_BleGetIdentityAddress, (const RawAddress address),
              (const, override));
  MOCK_METHOD(tBTM_BLE_SEC_REQ_ACT, BTM_BleLinkSecCheck,
              (const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req), (const, override));
  MOCK_METHOD(void, BTM_BleLtkRequestReply,
              (const RawAddress& bda, bool use_stk, const Octet16& stk), (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_BleStartEncrypt,
              (const RawAddress& bda, bool use_stk, Octet16* p_stk), (const, override));
  MOCK_METHOD(tBTM_STATUS, BTM_BleStartSecCheck,
              (const RawAddress& bd_addr, uint16_t psm, bool outgoing,
               tBTM_SEC_CALLBACK* p_callback, void* p_ref_data),
              (const, override));
  MOCK_METHOD(bool, BTM_GetLocalDiv, (const RawAddress& bd_addr, uint16_t* p_div),
              (const, override));
  MOCK_METHOD(bool, BTM_BleGetEncKeyType, (const RawAddress& bd_addr, uint8_t* p_key_types),
              (const, override));
  MOCK_METHOD(void, BTM_SecSaveLeKey,
              (const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type, const tBTM_LE_KEY_VALUE& key,
               bool pass_to_application),
              (const, override));
  MOCK_METHOD(void, BTM_BleUpdateSecKeySize, (const RawAddress& bd_addr, uint8_t enc_key_size),
              (const, override));
  MOCK_METHOD(void, BTM_BleResetId, (), (const, override));
  // clang-format on
};

// Initialize the working btm client interface to the default
// Reset the working btm client interface to the default
void reset_mock_security_client_interface();

// Set the working mock security interface
void set_security_client_interface(MockSecurityClientInterface& interface);
