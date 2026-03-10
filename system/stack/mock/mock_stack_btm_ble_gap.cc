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
 *   Functions generated:47
 */

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>

#include <cstdint>
#include <vector>

#include "stack/btm/btm_ble_int.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/rnr_interface.h"
#include "test/common/mock_functions.h"

using StartSyncCb = base::RepeatingCallback<void(
        uint8_t /*status*/, uint16_t /*sync_handle*/, uint8_t /*advertising_sid*/,
        uint8_t /*address_type*/, RawAddress /*address*/, uint8_t /*phy*/, uint16_t /*interval*/)>;
using SyncReportCb =
        base::RepeatingCallback<void(uint16_t /*sync_handle*/, int8_t /*tx_power*/, int8_t /*rssi*/,
                                     uint8_t /*status*/, std::vector<uint8_t> /*data*/)>;
using SyncLostCb = base::RepeatingCallback<void(uint16_t /*sync_handle*/)>;
using SyncTransferCb = base::RepeatingCallback<void(uint8_t /*status*/, RawAddress)>;

bool BTM_BleConfigPrivacy(bool /* privacy_mode */) {
  inc_func_call_count(__func__);
  return false;
}
bool BTM_BleLocalPrivacyEnabled(void) {
  inc_func_call_count(__func__);
  return false;
}
tBTM_STATUS btm_ble_read_remote_cod(const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
  return tBTM_STATUS::BTM_SUCCESS;
}
bool btm_ble_cancel_remote_name(const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
  return false;
}
void BTM_BleOpportunisticObserve(bool /* enable */, tBTM_INQ_RESULTS_CB* /* p_results_cb */) {
  inc_func_call_count(__func__);
}
void BTM_BleTargetAnnouncementObserve(bool /* enable */, tBTM_INQ_RESULTS_CB* /* p_results_cb */) {
  inc_func_call_count(__func__);
}
tBTM_STATUS btm_ble_read_remote_name(const RawAddress& /* remote_bda */,
                                     tBTM_NAME_CMPL_CB* /* p_cb */) {
  inc_func_call_count(__func__);
  return tBTM_STATUS::BTM_SUCCESS;
}
tBTM_STATUS btm_ble_start_inquiry(uint8_t /* duration */) {
  inc_func_call_count(__func__);
  return tBTM_STATUS::BTM_SUCCESS;
}
void BTM_BleGetDynamicAudioBuffer(
        tBTM_BT_DYNAMIC_AUDIO_BUFFER_CB /* p_dynamic_audio_buffer_cb*/[]) {
  inc_func_call_count(__func__);
}
void BTM_BleGetVendorCapabilities(tBTM_BLE_VSC_CB* /* p_cmn_vsc_cb */) {
  inc_func_call_count(__func__);
}
void btm_ble_decrement_link_topology_mask(uint8_t /* link_role */) {
  inc_func_call_count(__func__);
}
void btm_ble_increment_link_topology_mask(uint8_t /* link_role */) {
  inc_func_call_count(__func__);
}
void btm_ble_init(void) { inc_func_call_count(__func__); }
DEV_CLASS btm_ble_get_appearance_as_cod(std::vector<uint8_t> const& /* data */) {
  inc_func_call_count(__func__);
  return kDevClassUnclassified;
}
void btm_ble_process_adv_addr(RawAddress& /* bda */, tBLE_ADDR_TYPE* /* addr_type */) {
  inc_func_call_count(__func__);
}
void btm_ble_process_adv_pkt_cont(uint16_t /* evt_type */, tBLE_ADDR_TYPE /* addr_type */,
                                  const RawAddress& /* bda */, uint8_t /* primary_phy */,
                                  uint8_t /* secondary_phy */, uint8_t /* advertising_sid */,
                                  int8_t /* tx_power */, int8_t /* rssi */,
                                  uint16_t /* periodic_adv_int */, uint8_t /* data_len */,
                                  const uint8_t* /* data */, const RawAddress& /* original_bda */) {
  inc_func_call_count(__func__);
}
void btm_ble_process_adv_pkt_cont_for_inquiry(
        uint16_t /* evt_type */, tBLE_ADDR_TYPE /* addr_type */, const RawAddress& /* bda */,
        uint8_t /* primary_phy */, uint8_t /* secondary_phy */, uint8_t /* advertising_sid */,
        int8_t /* tx_power */, int8_t /* rssi */, uint16_t /* periodic_adv_int */,
        std::vector<uint8_t> /* advertising_data */) {
  inc_func_call_count(__func__);
}
void btm_ble_read_remote_features_complete(uint8_t* /* p */, uint8_t /* length */) {
  inc_func_call_count(__func__);
}
void btm_ble_stop_inquiry(void) { inc_func_call_count(__func__); }
void btm_send_hci_set_scan_params(uint8_t /* scan_type */, uint16_t /* scan_int_1m */,
                                  uint16_t /* scan_win_1m */, uint16_t /* scan_int_coded */,
                                  uint16_t /* scan_win_coded */, uint8_t /* scan_phy */,
                                  tBLE_ADDR_TYPE /* addr_type_own */,
                                  uint8_t /* scan_filter_policy */) {
  inc_func_call_count(__func__);
}
