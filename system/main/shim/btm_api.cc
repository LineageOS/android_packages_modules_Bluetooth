/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "bt_shim_btm"

#include "main/shim/btm_api.h"

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include "hci/acl_manager/acl_manager_le.h"
#include "hci/controller.h"
#include "main/shim/acl.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "main/shim/stack.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"

tBTM_STATUS bluetooth::shim::BTM_ClearEventFilter() {
  GetController()->SetEventFilterClearAll();
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearEventMask() {
  GetController()->SetEventMask(0);
  GetController()->LeSetEventMask(0);
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearFilterAcceptList() {
  Stack::GetInstance()->GetAcl()->ClearFilterAcceptList();
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_DisconnectAllAcls() {
  Stack::GetInstance()->GetAcl()->DisconnectAllForSuspend();
  //  Stack::GetInstance()->GetAcl()->Shutdown();
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterConnectionSetupAllDevices() {
  // Autoplumbed
  GetController()->SetEventFilterConnectionSetupAllDevices(
          bluetooth::hci::AutoAcceptFlag::AUTO_ACCEPT_ON_ROLE_SWITCH_ENABLED);
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_AllowWakeByHid(
        std::vector<RawAddress> classic_hid_devices,
        std::vector<std::pair<RawAddress, uint8_t>> le_hid_devices) {
  if (!com_android_bluetooth_flags_le_hid_connection_policy_suspend()) {
    // First set ACL to suspended state.
    Stack::GetInstance()->GetAcl()->SetSystemSuspendState(/*suspended=*/true);
  }
  // Allow classic HID wake.
  auto controller = GetController();
  for (auto device : classic_hid_devices) {
    controller->SetEventFilterConnectionSetupAddress(device, hci::AutoAcceptFlag::AUTO_ACCEPT_OFF);
  }

  if (com_android_bluetooth_flags_le_hid_connection_policy_suspend()) {
    return tBTM_STATUS::BTM_SUCCESS;
  }
  // Allow BLE HID
  for (auto hid_address : le_hid_devices) {
    tBLE_BD_ADDR bdaddr = BTM_Sec_GetAddressWithType(hid_address.first);
    bluetooth::shim::GetAclManagerLe()->CreateLeConnection(ToAddressWithTypeFromLegacy(bdaddr),
                                                           /*is_direct=*/false,
                                                           /*prefer_relax_mode=*/false);
  }

  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_RestoreFilterAcceptList(
        std::vector<std::pair<RawAddress, uint8_t>> le_devices) {
  if (!com_android_bluetooth_flags_le_hid_connection_policy_suspend()) {
    // First, mark ACL as no longer suspended.
    Stack::GetInstance()->GetAcl()->SetSystemSuspendState(/*suspended=*/false);
  }
  // Next, Allow BLE connection from all devices that need to be restored.
  // This will also re-arm the LE connection.
  for (auto address_pair : le_devices) {
    tBLE_BD_ADDR bdaddr = BTM_Sec_GetAddressWithType(address_pair.first);
    bluetooth::shim::GetAclManagerLe()->CreateLeConnection(ToAddressWithTypeFromLegacy(bdaddr),
                                                           /*is_direct=*/false,
                                                           /*prefer_relax_mode=*/false);
  }

  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetDefaultEventMaskExcept(uint64_t mask, uint64_t le_mask) {
  uint64_t applied_mask = bluetooth::hci::Controller::kDefaultEventMask & ~(mask);
  uint64_t applied_le_mask = bluetooth::hci::Controller::kDefaultLeEventMask & ~(le_mask);
  GetController()->SetEventMask(applied_mask);
  GetController()->LeSetEventMask(applied_le_mask);
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterInquiryResultAllDevices() {
  // Autoplumbed
  GetController()->SetEventFilterInquiryResultAllDevices();
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetSuspendState(bool suspend) {
  Stack::GetInstance()->GetAcl()->SetSystemSuspendState(suspend);
  return tBTM_STATUS::BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_BleResetId() {
  get_security_client_interface().BTM_BleResetId();
  return tBTM_STATUS::BTM_SUCCESS;
}

size_t bluetooth::shim::BTM_BleGetNumberOfAdvertisingInstancesInUse(void) {
  return GetAdvertising()->GetNumberOfAdvertisingInstancesInUse();
}
