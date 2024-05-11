/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:85
 */

#include <cstdint>

#include "main/shim/btm_api.h"
#include "stack/include/bt_octets.h"
#include "stack/include/btm_ble_api_types.h"
#include "test/common/mock_functions.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

tBTM_STATUS bluetooth::shim::BTM_ClearEventFilter() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearEventMask() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_ClearFilterAcceptList() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_DisconnectAllAcls() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterConnectionSetupAllDevices() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_AllowWakeByHid(
    std::vector<RawAddress> /* classic_hid_devices */,
    std::vector<std::pair<RawAddress, uint8_t>> /* le_hid_devices */) {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_RestoreFilterAcceptList(
    std::vector<std::pair<RawAddress, uint8_t>> /* le_devices */) {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetDefaultEventMaskExcept(
    uint64_t /* mask */, uint64_t /* le_mask */) {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_SetEventFilterInquiryResultAllDevices() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}

tBTM_STATUS bluetooth::shim::BTM_BleResetId() {
  inc_func_call_count(__func__);
  return BTM_SUCCESS;
}
size_t bluetooth::shim::BTM_BleGetNumberOfAdvertisingInstancesInUse() {
  inc_func_call_count(__func__);
  return 0;
}
