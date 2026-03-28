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

#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <gmock/gmock.h>

#include <cstdint>
#include <vector>

#include "bta/include/bta_api.h"
#include "bta/include/bta_sec_api.h"
#include "hci/le_rand_callback.h"
#include "stack/include/bt_device_type.h"

// Alias used to circumvent gmock macro expansion issues.
typedef std::pair<RawAddress, uint8_t> LeAddressWithType;

class MockBtaDmApi {
public:
  // clang-format off
  MOCK_METHOD(void, BTA_DmAddBleDevice,
              (const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_DEVICE_TYPE dev_type));
  MOCK_METHOD(void, BTA_DmAddBleKey,
              (const RawAddress& bd_addr, const PairingType& pairing_type,
               tBTM_LE_KEY_TYPE key_type, const tBTA_LE_KEY_VALUE& le_key));
  MOCK_METHOD(void, BTA_DmAddDevice,
              (const RawAddress& bd_addr, const DEV_CLASS& dev_class,
               const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
               uint8_t pin_length));
  MOCK_METHOD(void, BTA_DmAllowWakeByHid,
              (std::vector<RawAddress> classic_hid_devices,
               std::vector<LeAddressWithType> le_hid_devices));
  MOCK_METHOD(void, BTA_DmBleAuthCmplCbRegister, (tBTA_DM_SEC_CBACK * p_cback));
  MOCK_METHOD(void, BTA_DmBleConfigLocalPrivacy, (bool privacy_enable));
  MOCK_METHOD(void, BTA_DmBleConfirmReply, (const RawAddress& bd_addr, bool accept));
  MOCK_METHOD(void, BTA_DmBleCsisObserve, (bool observe, tBTA_DM_SEARCH_CBACK* p_results_cb));
  MOCK_METHOD(void, BTA_DmBleGetEnergyInfo, (tBTA_BLE_ENERGY_INFO_CBACK* p_cmpl_cback));
  MOCK_METHOD(void, BTA_DmBlePasskeyReply,
              (const RawAddress& bd_addr, bool accept, uint32_t passkey));
  MOCK_METHOD(void, BTA_DmBleRequestMaxTxDataLength, (const RawAddress& remote_device));
  MOCK_METHOD(void, BTA_DmBleResetId, ());
  MOCK_METHOD(void, BTA_DmBleScan, (bool start, uint8_t duration_sec));
  MOCK_METHOD(void, BTA_DmBleSecurityGrant,
              (const RawAddress& bd_addr, tBTA_DM_BLE_SEC_GRANT res));
  MOCK_METHOD(void, BTA_DmBond,
              (const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport));
  MOCK_METHOD(void, BTA_DmBondCancel, (const RawAddress& bd_addr));
  MOCK_METHOD(bool, BTA_DmCheckLeAudioCapable, (const RawAddress& address));
  MOCK_METHOD(void, BTA_DmClearEventFilter, ());
  MOCK_METHOD(void, BTA_DmClearEventMask, ());
  MOCK_METHOD(void, BTA_DmClearFilterAcceptList, ());
  MOCK_METHOD(void, BTA_DmConfirm, (const RawAddress& bd_addr, bool accept));
  MOCK_METHOD(void, BTA_DmDisconnectAllAcls, ());
  MOCK_METHOD(void, BTA_DmDiscover,
              (const RawAddress& bd_addr, service_discovery_callbacks cbacks,
               tBT_TRANSPORT transport));
  MOCK_METHOD(bool, BTA_DmGetConnectionState, (const RawAddress& bd_addr));
  MOCK_METHOD(void, BTA_DmLeRand, (bluetooth::hci::LeRandCallback cb));
  MOCK_METHOD(void, BTA_DmLocalOob, ());
  MOCK_METHOD(void, BTA_DmPinReply,
              (const RawAddress& bd_addr, bool accept, uint8_t pin_len, uint8_t* p_pin));
  MOCK_METHOD(tBTA_STATUS, BTA_DmRemoveDevice, (const RawAddress& bd_addr));
  MOCK_METHOD(void, BTA_DmRestoreFilterAcceptList, (std::vector<LeAddressWithType> le_devices));
  MOCK_METHOD(void, BTA_DmSearch, (tBTA_DM_SEARCH_CBACK* p_cback));
  MOCK_METHOD(void, BTA_DmSearchCancel, ());
  MOCK_METHOD(void, BTA_DmSetDefaultEventMaskExcept, (uint64_t mask, uint64_t le_mask));
  MOCK_METHOD(void, BTA_DmSetDeviceName, (const char* p_name));
  MOCK_METHOD(void, BTA_DmSetEncryption,
              (const RawAddress& bd_addr, tBT_TRANSPORT transport,
               tBTA_DM_ENCRYPT_CBACK* p_callback, tBTM_BLE_SEC_ACT sec_act));
  MOCK_METHOD(void, BTA_DmSetEventFilterConnectionSetupAllDevices, ());
  MOCK_METHOD(void, BTA_DmSetEventFilterInquiryResultAllDevices, ());
  MOCK_METHOD(bool, BTA_DmSetLocalDiRecord, (tSDP_DI_RECORD* p_device_info));
  MOCK_METHOD(void, BTA_DmSirkConfirmDeviceReply, (const RawAddress& bd_addr, bool accept));
  MOCK_METHOD(void, BTA_DmSirkSecCbRegister, (tBTA_DM_SEC_CBACK* p_cback));
  MOCK_METHOD(void, BTA_dm_init, ());
  // clang-format on

  static void SetInstance(MockBtaDmApi* ptr);
};
