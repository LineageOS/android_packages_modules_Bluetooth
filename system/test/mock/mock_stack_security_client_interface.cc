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

#include "test/mock/mock_stack_security_client_interface.h"

#include "stack/include/security_client_callbacks.h"

namespace {

// Initialize the working btm client interface to the default
MockSecurityClientInterface default_mock_security_client_interface;

// Initialize the working btm client interface to the default
SecurityClientInterface* mock_security_client_interface = &default_mock_security_client_interface;

}  // namespace

// Reset the working btm client interface to the default
void reset_mock_security_client_interface() {
  mock_security_client_interface = &default_mock_security_client_interface;
}

// Serve the working mock security interface
const SecurityClientInterface& get_security_client_interface() {
  return *mock_security_client_interface;
}

void set_security_client_interface(SecurityClientInterface& interface) {
  mock_security_client_interface = &interface;
}

MockSecurityClientInterface::MockSecurityClientInterface() {
  SecurityClientInterface::BTM_Sec_Init = []() {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_Sec_Init();
  };
  SecurityClientInterface::BTM_Sec_Free = []() {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_Sec_Free();
  };
  SecurityClientInterface::BTM_SecRegister = [](const BtmAppReg& app_reg) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecRegister(app_reg);
  };
  SecurityClientInterface::BTM_BleLoadLocalKeys = [](uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_BleLoadLocalKeys(key_type, p_key);
  };
  SecurityClientInterface::BTM_SecAddDevice = [](const RawAddress& bd_addr,
                                                 const DEV_CLASS& dev_class,
                                                 const PairingType& pairing_type,
                                                 const LinkKey& link_key, uint8_t key_type,
                                                 uint8_t pin_length) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecAddDevice(bd_addr, dev_class, pairing_type, link_key, key_type, pin_length);
  };
  SecurityClientInterface::BTM_SecAddBleDevice =
          [](const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type, tBLE_ADDR_TYPE addr_type) {
            static_cast<MockSecurityClientInterface&>(
                    const_cast<SecurityClientInterface&>(get_security_client_interface()))
                    .BTM_SecAddBleDevice(bd_addr, dev_type, addr_type);
          };
  SecurityClientInterface::BTM_SecDeleteDevice = [](const RawAddress& bd_addr) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecDeleteDevice(bd_addr);
  };
  SecurityClientInterface::BTM_SecAddBleKey =
          [](const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type, const tBTM_LE_KEY_VALUE& key) {
            static_cast<MockSecurityClientInterface&>(
                    const_cast<SecurityClientInterface&>(get_security_client_interface()))
                    .BTM_SecAddBleKey(bd_addr, key_type, key);
          };
  SecurityClientInterface::BTM_SecClearSecurityFlags = [](const RawAddress& bd_addr) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecClearSecurityFlags(bd_addr);
  };
  SecurityClientInterface::BTM_SetEncryption =
          [](const RawAddress& bd_addr, tBT_TRANSPORT transport, tBTM_SEC_CALLBACK* p_callback,
             void* p_ref_data, tBTM_BLE_SEC_ACT sec_act) -> tBTM_STATUS {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SetEncryption(bd_addr, transport, p_callback, p_ref_data, sec_act);
  };
  SecurityClientInterface::BTM_IsEncrypted = [](const RawAddress& bd_addr,
                                                tBT_TRANSPORT transport) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_IsEncrypted(bd_addr, transport);
  };
  SecurityClientInterface::BTM_SecIsLeSecurityPending = [](const RawAddress& bd_addr) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecIsLeSecurityPending(bd_addr);
  };
  SecurityClientInterface::BTM_IsBonded = [](const RawAddress& bd_addr,
                                             tBT_TRANSPORT transport) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_IsBonded(bd_addr, transport);
  };
  SecurityClientInterface::BTM_SetSecurityLevel =
          [](bool outgoing, const char* p_name, uint8_t service_id, uint16_t sec_level,
             uint16_t psm, uint32_t mx_proto_id, uint32_t mx_chan_id) -> bool {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SetSecurityLevel(outgoing, p_name, service_id, sec_level, psm, mx_proto_id,
                                  mx_chan_id);
  };
  SecurityClientInterface::BTM_SecClrService = [](uint8_t service_id) -> uint8_t {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecClrService(service_id);
  };
  SecurityClientInterface::BTM_SecClrServiceByPsm = [](uint16_t psm) -> uint8_t {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecClrServiceByPsm(psm);
  };
  SecurityClientInterface::BTM_SecBond = [](const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                                            tBT_TRANSPORT transport) -> tBTM_STATUS {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecBond(bd_addr, addr_type, transport);
  };
  SecurityClientInterface::BTM_SecBondCancel = [](const RawAddress& bd_addr) -> tBTM_STATUS {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecBondCancel(bd_addr);
  };
  SecurityClientInterface::BTM_RemoteOobDataReply = [](tBTM_STATUS res, const RawAddress& bd_addr,
                                                       const Octet16& c, const Octet16& r) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_RemoteOobDataReply(res, bd_addr, c, r);
  };
  SecurityClientInterface::BTM_PINCodeReply = [](const RawAddress& bd_addr, tBTM_STATUS res,
                                                 uint8_t pin_len, PinCode pin_code) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_PINCodeReply(bd_addr, res, pin_len, pin_code);
  };
  SecurityClientInterface::BTM_SecConfirmReqReply = [](tBTM_STATUS res, tBT_TRANSPORT transport,
                                                       const RawAddress bd_addr) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecConfirmReqReply(res, transport, bd_addr);
  };
  SecurityClientInterface::BTM_BleSirkConfirmDeviceReply = [](const RawAddress& bd_addr,
                                                              tBTM_STATUS res) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_BleSirkConfirmDeviceReply(bd_addr, res);
  };
  SecurityClientInterface::BTM_BlePasskeyReply = [](const RawAddress& bd_addr, tBTM_STATUS res,
                                                    uint32_t passkey) {
    static_cast<MockSecurityClientInterface&>(
            const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_BlePasskeyReply(bd_addr, res, passkey);
  };
  SecurityClientInterface::BTM_BleReadSecKeySize = [](const RawAddress& bd_addr) -> uint8_t {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_BleReadSecKeySize(bd_addr);
  };
  SecurityClientInterface::BTM_GetSecurityMode = []() -> uint8_t {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_GetSecurityMode();
  };
  SecurityClientInterface::BTM_SecReadDevName = [](const RawAddress& bd_addr) -> const char* {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecReadDevName(bd_addr);
  };
  SecurityClientInterface::BTM_SecReadDevClass = [](const RawAddress& bd_addr) -> DEV_CLASS {
    return static_cast<MockSecurityClientInterface&>(
                   const_cast<SecurityClientInterface&>(get_security_client_interface()))
            .BTM_SecReadDevClass(bd_addr);
  };
}
