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

#include "mock_bta_dm_api.h"

#include <cstdint>
#include <vector>

namespace {
MockBtaDmApi* instance;
}

void MockBtaDmApi::SetInstance(MockBtaDmApi* ptr) { instance = ptr; }

void BTA_DmAddBleDevice(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                        tBT_DEVICE_TYPE dev_type) {
  if (instance) {
    instance->BTA_DmAddBleDevice(bd_addr, addr_type, dev_type);
  }
}

void BTA_DmAddBleKey(const RawAddress& bd_addr, const PairingType& pairing_type,
                     tBTM_LE_KEY_TYPE key_type, const tBTA_LE_KEY_VALUE& le_key) {
  if (instance) {
    instance->BTA_DmAddBleKey(bd_addr, pairing_type, key_type, le_key);
  }
}

void BTA_DmAddDevice(const RawAddress& bd_addr, const DEV_CLASS& dev_class,
                     const PairingType& pairing_type, const LinkKey& link_key, uint8_t key_type,
                     uint8_t pin_length) {
  if (instance) {
    instance->BTA_DmAddDevice(bd_addr, dev_class, pairing_type, link_key, key_type, pin_length);
  }
}

void BTA_DmAllowWakeByHid(std::vector<RawAddress> classic_hid_devices,
                          std::vector<std::pair<RawAddress, uint8_t>> le_hid_devices) {
  if (instance) {
    instance->BTA_DmAllowWakeByHid(classic_hid_devices, le_hid_devices);
  }
}

void BTA_DmBleAuthCmplCbRegister(tBTA_DM_SEC_CBACK* p_cback) {
  if (instance) {
    instance->BTA_DmBleAuthCmplCbRegister(p_cback);
  }
}

void BTA_DmBleConfigLocalPrivacy(bool privacy_enable) {
  if (instance) {
    instance->BTA_DmBleConfigLocalPrivacy(privacy_enable);
  }
}

void BTA_DmBleConfirmReply(const RawAddress& bd_addr, bool accept) {
  if (instance) {
    instance->BTA_DmBleConfirmReply(bd_addr, accept);
  }
}

void BTA_DmBleCsisObserve(bool observe, tBTA_DM_SEARCH_CBACK* p_results_cb) {
  if (instance) {
    instance->BTA_DmBleCsisObserve(observe, p_results_cb);
  }
}

void BTA_DmBleGetEnergyInfo(tBTA_BLE_ENERGY_INFO_CBACK* p_cmpl_cback) {
  if (instance) {
    instance->BTA_DmBleGetEnergyInfo(p_cmpl_cback);
  }
}

void BTA_DmBlePasskeyReply(const RawAddress& bd_addr, bool accept, uint32_t passkey) {
  if (instance) {
    instance->BTA_DmBlePasskeyReply(bd_addr, accept, passkey);
  }
}

void BTA_DmBleRequestMaxTxDataLength(const RawAddress& remote_device) {
  if (instance) {
    instance->BTA_DmBleRequestMaxTxDataLength(remote_device);
  }
}

void BTA_DmBleResetId(void) {
  if (instance) {
    instance->BTA_DmBleResetId();
  }
}

void BTA_DmBleScan(bool start, uint8_t duration_sec) {
  if (instance) {
    instance->BTA_DmBleScan(start, duration_sec);
  }
}

void BTA_DmBleSecurityGrant(const RawAddress& bd_addr, tBTA_DM_BLE_SEC_GRANT res) {
  if (instance) {
    instance->BTA_DmBleSecurityGrant(bd_addr, res);
  }
}

void BTA_DmBond(const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport) {
  if (instance) {
    instance->BTA_DmBond(bd_addr, addr_type, transport);
  }
}

void BTA_DmBondCancel(const RawAddress& bd_addr) {
  if (instance) {
    instance->BTA_DmBondCancel(bd_addr);
  }
}

bool BTA_DmCheckLeAudioCapable(const RawAddress& address) {
  return instance ? instance->BTA_DmCheckLeAudioCapable(address) : false;
}

void BTA_DmClearEventFilter(void) {
  if (instance) {
    instance->BTA_DmClearEventFilter();
  }
}

void BTA_DmClearEventMask(void) {
  if (instance) {
    instance->BTA_DmClearEventMask();
  }
}

void BTA_DmClearFilterAcceptList(void) {
  if (instance) {
    instance->BTA_DmClearFilterAcceptList();
  }
}

void BTA_DmConfirm(const RawAddress& bd_addr, bool accept) {
  if (instance) {
    instance->BTA_DmConfirm(bd_addr, accept);
  }
}

void BTA_DmDisconnectAllAcls() {
  if (instance) {
    instance->BTA_DmDisconnectAllAcls();
  }
}

void BTA_DmDiscover(const RawAddress& bd_addr, service_discovery_callbacks cbacks,
                    tBT_TRANSPORT transport) {
  if (instance) {
    instance->BTA_DmDiscover(bd_addr, cbacks, transport);
  }
}

bool BTA_DmGetConnectionState(const RawAddress& bd_addr) {
  return instance ? instance->BTA_DmGetConnectionState(bd_addr) : false;
}

void BTA_DmLeRand(bluetooth::hci::LeRandCallback cb) {
  if (instance) {
    instance->BTA_DmLeRand(std::move(cb));
  }
}

void BTA_DmLocalOob(void) {
  if (instance) {
    instance->BTA_DmLocalOob();
  }
}

void BTA_DmPinReply(const RawAddress& bd_addr, bool accept, uint8_t pin_len, uint8_t* p_pin) {
  if (instance) {
    instance->BTA_DmPinReply(bd_addr, accept, pin_len, p_pin);
  }
}

tBTA_STATUS BTA_DmRemoveDevice(const RawAddress& bd_addr) {
  return instance ? instance->BTA_DmRemoveDevice(bd_addr) : BTA_SUCCESS;
}

void BTA_DmRestoreFilterAcceptList(std::vector<std::pair<RawAddress, uint8_t>> le_devices) {
  if (instance) {
    instance->BTA_DmRestoreFilterAcceptList(le_devices);
  }
}

void BTA_DmSearch(tBTA_DM_SEARCH_CBACK* p_cback) {
  if (instance) {
    instance->BTA_DmSearch(p_cback);
  }
}

void BTA_DmSearchCancel(void) {
  if (instance) {
    instance->BTA_DmSearchCancel();
  }
}

void BTA_DmSetDefaultEventMaskExcept(uint64_t mask, uint64_t le_mask) {
  if (instance) {
    instance->BTA_DmSetDefaultEventMaskExcept(mask, le_mask);
  }
}

void BTA_DmSetDeviceName(const char* p_name) {
  if (instance) {
    instance->BTA_DmSetDeviceName(p_name);
  }
}

void BTA_DmSetEncryption(const RawAddress& bd_addr, tBT_TRANSPORT transport,
                         tBTA_DM_ENCRYPT_CBACK* p_callback, tBTM_BLE_SEC_ACT sec_act) {
  if (instance) {
    instance->BTA_DmSetEncryption(bd_addr, transport, p_callback, sec_act);
  }
}

void BTA_DmSetEventFilterConnectionSetupAllDevices() {
  if (instance) {
    instance->BTA_DmSetEventFilterConnectionSetupAllDevices();
  }
}

void BTA_DmSetEventFilterInquiryResultAllDevices() {
  if (instance) {
    instance->BTA_DmSetEventFilterInquiryResultAllDevices();
  }
}

bool BTA_DmSetLocalDiRecord(tSDP_DI_RECORD* p_device_info) {
  return instance ? instance->BTA_DmSetLocalDiRecord(p_device_info) : true;
}

void BTA_DmSirkConfirmDeviceReply(const RawAddress& bd_addr, bool accept) {
  if (instance) {
    instance->BTA_DmSirkConfirmDeviceReply(bd_addr, accept);
  }
}

void BTA_DmSirkSecCbRegister(tBTA_DM_SEC_CBACK* p_cback) {
  if (instance) {
    instance->BTA_DmSirkSecCbRegister(p_cback);
  }
}

void BTA_dm_init() {
  if (instance) {
    instance->BTA_dm_init();
  }
}
