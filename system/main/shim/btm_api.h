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

#pragma once

#include <vector>

#include "base/functional/callback.h"
#include "device/include/esco_parameters.h"
#include "hci/le_rand_callback.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_ble_api_types.h"
#include "types/hci_role.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace shim {

/*******************************************************************************
 *
 * Function         BTM_ClearEventFilter
 *
 * Description      Clears the event filter in the controller
 *
 * Returns          Return btm status
 *
 ******************************************************************************/
tBTM_STATUS BTM_ClearEventFilter(void);

/*******************************************************************************
 *
 * Function         BTM_ClearEventMask
 *
 * Description      Clears the event mask in the controller
 *
 * Returns          Return btm status
 *
 ******************************************************************************/
tBTM_STATUS BTM_ClearEventMask(void);

/*******************************************************************************
 *
 * Function         BTM_ClearFilterAcceptList
 *
 * Description      Clears the connect list in the controller
 *
 * Returns          Return btm status
 *
 ******************************************************************************/
tBTM_STATUS BTM_ClearFilterAcceptList(void);

/*******************************************************************************
 *
 * Function         BTM_DisconnectAllAcls
 *
 * Description      Disconnects all of the ACL connections
 *
 * Returns          Return btm status
 *
 ******************************************************************************/
tBTM_STATUS BTM_DisconnectAllAcls(void);

/*******************************************************************************
 *
 * Function        BTM_SetEventFilterConnectionSetupAllDevices
 *
 * Description    Tell the controller to allow all devices
 *
 * Parameters
 *
 *******************************************************************************/
tBTM_STATUS BTM_SetEventFilterConnectionSetupAllDevices(void);

/*******************************************************************************
 *
 * Function        BTM_AllowWakeByHid
 *
 * Description     Allow the device to be woken by HID devices
 *
 * Parameters      std::vector of RawAddress
 *
 *******************************************************************************/
tBTM_STATUS BTM_AllowWakeByHid(
    std::vector<RawAddress> classic_hid_devices,
    std::vector<std::pair<RawAddress, uint8_t>> le_hid_devices);

/*******************************************************************************
 *
 * Function        BTM_RestoreFilterAcceptList
 *
 * Description    Floss: Restore the state of the for the filter accept list
 *
 * Parameters
 *
 *******************************************************************************/
tBTM_STATUS BTM_RestoreFilterAcceptList(
    std::vector<std::pair<RawAddress, uint8_t>> le_devices);

/*******************************************************************************
 *
 * Function        BTM_SetDefaultEventMaskExcept
 *
 * Description    Floss: Set the default event mask for Classic and LE except
 *                the given values (they will be disabled in the final set
 *                mask).
 *
 * Parameters     Bits set for event mask and le event mask that should be
 *                disabled in the final value.
 *
 *******************************************************************************/
tBTM_STATUS BTM_SetDefaultEventMaskExcept(uint64_t mask, uint64_t le_mask);

/*******************************************************************************
 *
 * Function        BTM_SetEventFilterInquiryResultAllDevices
 *
 * Description    Floss: Set the event filter to inquiry result device all
 *
 * Parameters
 *
 *******************************************************************************/
tBTM_STATUS BTM_SetEventFilterInquiryResultAllDevices(void);

/*******************************************************************************
 *
 * Function         BTM_BleResetId
 *
 * Description      Resets the local BLE keys
 *
 *******************************************************************************/
tBTM_STATUS BTM_BleResetId(void);

/*******************************************************************************
 *
 * Function         BTM_BleGetNumberOfAdvertisingInstancesInUse
 *
 * Description      Obtains the number of BLE advertising instances in use
 *
 * Returns          Return the number of BLE advertising instances in use
 *******************************************************************************/
size_t BTM_BleGetNumberOfAdvertisingInstancesInUse(void);

}  // namespace shim
}  // namespace bluetooth
