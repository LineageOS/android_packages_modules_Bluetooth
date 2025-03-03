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

#include <gmock/gmock.h>

#include "stack/include/security_client_callbacks.h"

struct MockSecurityClientInterface : public SecurityClientInterface {
  MockSecurityClientInterface() = default;
  MOCK_METHOD((void), BTM_Sec_Init, ());
  MOCK_METHOD((void), BTM_Sec_Free, ());
  MOCK_METHOD((bool), BTM_SecRegister, (const tBTM_APPL_INFO*));
  MOCK_METHOD((void), BTM_BleLoadLocalKeys,
              (uint8_t /* key_type */, tBTM_BLE_LOCAL_KEYS* /* p_key */));
  MOCK_METHOD((void), BTM_SecAddDevice,
              (const RawAddress&, DEV_CLASS /* dev_class */, LinkKey /* link_key */,
               uint8_t /* key_type */, uint8_t /* pin_length */));
  MOCK_METHOD((void), BTM_SecAddBleDevice,
              (const RawAddress& /* bd_addr */, tBT_DEVICE_TYPE /* dev_type */,
               tBLE_ADDR_TYPE /* addr_type */));
  MOCK_METHOD((bool), BTM_SecDeleteDevice, (const RawAddress& /* bd_addr */));
  MOCK_METHOD((void), BTM_SecAddBleKey,
              (const RawAddress& /* bd_addr */, tBTM_LE_KEY_VALUE* /* p_le_key */,
               tBTM_LE_KEY_TYPE /* key_type */));
  MOCK_METHOD((void), BTM_SecClearSecurityFlags, (const RawAddress& /* bd_addr */));
  MOCK_METHOD((tBTM_STATUS), BTM_SetEncryption,
              (const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */,
               tBTM_SEC_CALLBACK* /* p_callback */, void* /* p_ref_data */,
               tBTM_BLE_SEC_ACT /* sec_act */));
  MOCK_METHOD((bool), BTM_IsEncrypted,
              (const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */));
  MOCK_METHOD((bool), BTM_SecIsLeSecurityPending, (const RawAddress& /* bd_addr */));
  MOCK_METHOD((bool), BTM_IsDeviceBonded,
              (const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */));
  MOCK_METHOD((bool), BTM_SetSecurityLevel,
              (bool /* is_originator */, const char* /* p_name */, uint8_t /* service_id */,
               uint16_t /* sec_level */, uint16_t /* psm */, uint32_t /* mx_proto_id */,
               uint32_t /* mx_chan_id */));
  MOCK_METHOD((uint8_t), BTM_SecClrService, (uint8_t /* service_id */));
  MOCK_METHOD((uint8_t), BTM_SecClrServiceByPsm, (uint16_t /* psm */));
  MOCK_METHOD((tBTM_STATUS), BTM_SecBond,
              (const RawAddress& /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
               tBT_TRANSPORT /* transport */, tBT_DEVICE_TYPE /* device_type */));
  MOCK_METHOD((tBTM_STATUS), BTM_SecBondCancel, (const RawAddress& /* bd_addr */));
  MOCK_METHOD((void), BTM_RemoteOobDataReply,
              (tBTM_STATUS /* res */, const RawAddress& /* bd_addr */, const Octet16& /* c */,
               const Octet16& /* r */));
  MOCK_METHOD((void), BTM_PINCodeReply,
              (const RawAddress& /* bd_addr */, tBTM_STATUS /* res */, uint8_t /* pin_len */,
               uint8_t* /* p_pin */));
  MOCK_METHOD((void), BTM_SecConfirmReqReply,
              (tBTM_STATUS /* res */, tBT_TRANSPORT /* transport */,
               const RawAddress /* bd_addr */));
  MOCK_METHOD((void), BTM_BleSirkConfirmDeviceReply,
              (const RawAddress& /* bd_addr */, uint8_t /* res */));
  MOCK_METHOD((void), BTM_BlePasskeyReply,
              (const RawAddress& /* bd_addr */, uint8_t /* res */, uint32_t /* passkey */));
  MOCK_METHOD((uint8_t), BTM_GetSecurityMode, ());
  MOCK_METHOD((const char*), BTM_SecReadDevName, (const RawAddress& /* bd_addr */));
  MOCK_METHOD((DEV_CLASS), BTM_SecReadDevClass, (const RawAddress& /* bd_addr */));
};

// Initialize the working btm client interface to the default
// Reset the working btm client interface to the default
void reset_mock_security_client_interface();

// Serve the working mock security interface
const SecurityClientInterface& get_security_client_interface();

// Set the working mock security interface
void set_security_client_interface(SecurityClientInterface& interface);
