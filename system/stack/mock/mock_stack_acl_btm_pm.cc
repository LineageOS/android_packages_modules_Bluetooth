/*
 * Copyright 2020 The Android Open Source Project
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
 *   Functions generated:17
 */

#include <bluetooth/types/address.h>

#include <cstdint>

#include "stack/btm/power_mode.h"
#include "stack/include/acl_api.h"
#include "stack/include/acl_hci_link_interface.h"
#include "stack/include/btm_status.h"
#include "test/common/mock_functions.h"

bool BTM_ReadPowerMode(const RawAddress& /* remote_bda */, tBTM_PM_MODE* /* p_mode */) {
  inc_func_call_count(__func__);
  return false;
}
bool BTM_SetLinkPolicyActiveMode(const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
  return false;
}
uint8_t BTM_PM_ReadSniffLinkCount(void) {
  inc_func_call_count(__func__);
  return 0;
}
uint8_t BTM_PM_ReadBleLinkCount(void) {
  inc_func_call_count(__func__);
  return 0;
}
bool BTM_PM_DeviceInScanState(void) {
  inc_func_call_count(__func__);
  return false;
}
uint32_t BTM_PM_ReadBleScanDutyCycle(void) {
  inc_func_call_count(__func__);
  return 0;
}
void BTM_PM_OnConnected(uint16_t /* handle */, const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
}
void BTM_PM_OnDisconnected(uint16_t /* handle */) { inc_func_call_count(__func__); }
void btm_pm_on_mode_change(tHCI_STATUS /* status */, uint16_t /* handle */,
                           tHCI_MODE /* current_mode */, uint16_t /* interval */) {
  inc_func_call_count(__func__);
}
void btm_pm_on_sniff_subrating(tHCI_STATUS /* status */, uint16_t /* handle */,
                               uint16_t /* maximum_transmit_latency */,
                               uint16_t /* maximum_receive_latency */,
                               uint16_t /* minimum_remote_timeout */,
                               uint16_t /* minimum_local_timeout */) {
  inc_func_call_count(__func__);
}
void btm_pm_proc_cmd_status(tHCI_STATUS /* status */) { inc_func_call_count(__func__); }
void btm_pm_proc_mode_change(tHCI_STATUS /* hci_status */, uint16_t /* hci_handle */,
                             tHCI_MODE /* hci_mode */, uint16_t /* interval */) {
  inc_func_call_count(__func__);
}
void btm_pm_proc_ssr_evt(uint8_t* /* p */, uint16_t /* evt_len */) {
  inc_func_call_count(__func__);
}
void btm_pm_reset(void) { inc_func_call_count(__func__); }
