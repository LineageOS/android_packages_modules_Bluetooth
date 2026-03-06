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
 */

#include "mock_stack_btm_interface.h"

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>

#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"

namespace {
MockBtmClientInterface default_btm_client_interface_mock;
MockBtmClientInterface* btm_client_interface_mock = &default_btm_client_interface_mock;

struct btm_client_interface_t btm_client_interface = {
        .lifecycle = {
                .BTM_PmRegister =
                        [](uint8_t mask, uint8_t* p_pm_id, tBTM_PM_STATUS_CBACK* p_cback) {
                          return btm_client_interface_mock->BTM_PmRegister(mask, p_pm_id, p_cback);
                        },
                .ACL_RegisterClient =
                        [](struct acl_client_callback_s* callbacks) {
                          btm_client_interface_mock->ACL_RegisterClient(callbacks);
                        },
                .ACL_UnregisterClient =
                        [](struct acl_client_callback_s* callbacks) {
                          btm_client_interface_mock->ACL_UnregisterClient(callbacks);
                        },
                .btm_init = []() { btm_client_interface_mock->btm_init(); },
                .btm_free = []() { btm_client_interface_mock->btm_free(); },
                .btm_ble_init = []() { btm_client_interface_mock->btm_ble_init(); },
                .btm_ble_free = []() { btm_client_interface_mock->btm_ble_free(); },
                .BTM_reset_complete = []() { btm_client_interface_mock->BTM_reset_complete(); },
        },
        .peer = {
                .BTM_IsAclConnectionUp =
                        [](const RawAddress& bd_addr, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_IsAclConnectionUp(bd_addr,
                                                                                  transport);
                        },
                .BTM_GetConnectedTransportAddress =
                        [](RawAddress bd_addr) {
                          return btm_client_interface_mock->BTM_GetConnectedTransportAddress(
                                  bd_addr);
                        },
                .BTM_ReadRemoteFeatures =
                        [](const RawAddress& bd_addr) {
                          return btm_client_interface_mock->BTM_ReadRemoteFeatures(bd_addr);
                        },
                .BTM_ReadDevInfo =
                        [](const RawAddress& bd_addr) {
                          return btm_client_interface_mock->BTM_ReadDevInfo(bd_addr);
                        },
                .BTM_GetMaxPacketSize =
                        [](const RawAddress& bd_addr) {
                          return btm_client_interface_mock->BTM_GetMaxPacketSize(bd_addr);
                        },
                .BTM_ReadRemoteVersion =
                        [](const RawAddress& addr, uint8_t* lmp_version, uint16_t* manufacturer,
                           uint16_t* lmp_sub_version) {
                          return btm_client_interface_mock->BTM_ReadRemoteVersion(
                                  addr, lmp_version, manufacturer, lmp_sub_version);
                        },
                .BTM_GetPeerDeviceTypeFromFeatures =
                        [](const RawAddress& bd_addr) {
                          return btm_client_interface_mock->BTM_GetPeerDeviceTypeFromFeatures(
                                  bd_addr);
                        },
                .BTM_RequestPeerSCA =
                        [](const RawAddress& remote_bda, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_RequestPeerSCA(remote_bda,
                                                                               transport);
                        },
                .BTM_GetPeerSCA =
                        [](const RawAddress& remote_bda, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_GetPeerSCA(remote_bda, transport);
                        },
                .BTM_IsPhy2mSupported =
                        [](const RawAddress& remote_bda, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_IsPhy2mSupported(remote_bda,
                                                                                 transport);
                        },
                .BTM_GetHCIConnHandle =
                        [](const RawAddress& bd_addr, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_GetHCIConnHandle(bd_addr,
                                                                                 transport);
                        },
                .BTM_IsAclConnectionUpAndHandleValid =
                        [](const RawAddress& remote_bda, tBT_TRANSPORT transport) {
                          return btm_client_interface_mock->BTM_IsAclConnectionUpAndHandleValid(
                                  remote_bda, transport);
                        },
        },
        .link_policy = {
                .BTM_GetRole =
                        [](const RawAddress& remote_bd_addr, tBT_TRANSPORT transport,
                           tHCI_ROLE* p_role) {
                          return btm_client_interface_mock->BTM_GetRole(remote_bd_addr, transport,
                                                                        p_role);
                        },
                .BTM_SetPowerMode =
                        [](uint8_t pm_id, const RawAddress& bd_addr, const tBTM_PM_PWR_MD* p_mode) {
                          return btm_client_interface_mock->BTM_SetPowerMode(pm_id, bd_addr,
                                                                             p_mode);
                        },
                .BTM_SetSsrParams =
                        [](const RawAddress& bd_addr, uint16_t max_lat, uint16_t min_rmt_to,
                           uint16_t min_loc_to) {
                          return btm_client_interface_mock->BTM_SetSsrParams(
                                  bd_addr, max_lat, min_rmt_to, min_loc_to);
                        },
                .BTM_SwitchRoleToCentral =
                        [](const RawAddress& remote_bd_addr) {
                          return btm_client_interface_mock->BTM_SwitchRoleToCentral(remote_bd_addr);
                        },
                .BTM_block_role_switch_for =
                        [](const RawAddress& peer_addr) {
                          btm_client_interface_mock->BTM_block_role_switch_for(peer_addr);
                        },
                .BTM_block_sniff_mode_for =
                        [](const RawAddress& peer_addr) {
                          btm_client_interface_mock->BTM_block_sniff_mode_for(peer_addr);
                        },
                .BTM_unblock_role_switch_for =
                        [](const RawAddress& peer_addr) {
                          btm_client_interface_mock->BTM_unblock_role_switch_for(peer_addr);
                        },
                .BTM_unblock_sniff_mode_for =
                        [](const RawAddress& peer_addr) {
                          btm_client_interface_mock->BTM_unblock_sniff_mode_for(peer_addr);
                        },
                .BTM_WritePageTimeout =
                        [](uint16_t timeout) {
                          btm_client_interface_mock->BTM_WritePageTimeout(timeout);
                        },
        },
        .link_controller = {
                .BTM_GetLinkSuperTout =
                        [](const RawAddress& bd_addr, uint16_t* p_timeout) {
                          return btm_client_interface_mock->BTM_GetLinkSuperTout(bd_addr,
                                                                                 p_timeout);
                        },
                .BTM_ReadRSSI =
                        [](const RawAddress& bd_addr, tBTM_READ_RSSI_CB* p_cb) {
                          return btm_client_interface_mock->BTM_ReadRSSI(bd_addr, p_cb);
                        },
        },
        .ble = {
                .BTM_BleGetEnergyInfo =
                        [](tBTM_BLE_ENERGY_INFO_CBACK* callback) {
                          return btm_client_interface_mock->BTM_BleGetEnergyInfo(callback);
                        },
                .BTM_BleObserve =
                        [](bool start, uint8_t duration, tBTM_INQ_RESULTS_CB* p_results_cb,
                           tBTM_INQUIRY_CMPL_CB* p_cmpl_cb) {
                          return btm_client_interface_mock->BTM_BleObserve(start, duration,
                                                                           p_results_cb, p_cmpl_cb);
                        },
                .BTM_SetBleDataLength =
                        [](const RawAddress& bd_addr, uint16_t tx_pdu_length,
                           bool is_privileged_client) {
                          return btm_client_interface_mock->BTM_SetBleDataLength(
                                  bd_addr, tx_pdu_length, is_privileged_client);
                        },
                .BTM_BleReadControllerFeatures =
                        [](tBTM_BLE_CTRL_FEATURES_CBACK* p_vsc_cback) {
                          btm_client_interface_mock->BTM_BleReadControllerFeatures(p_vsc_cback);
                        },
                .BTM_BleSetPrefConnParams =
                        [](const RawAddress& bd_addr, uint16_t min_conn_int, uint16_t max_conn_int,
                           uint16_t peripheral_latency, uint16_t supervision_tout) {
                          btm_client_interface_mock->BTM_BleSetPrefConnParams(
                                  bd_addr, min_conn_int, max_conn_int, peripheral_latency,
                                  supervision_tout);
                        },
                .BTM_UseLeLink =
                        [](const RawAddress& bd_addr) {
                          return btm_client_interface_mock->BTM_UseLeLink(bd_addr);
                        },
                .BTM_IsRemoteVersionReceived =
                        [](const RawAddress& remote_bda) {
                          return btm_client_interface_mock->BTM_IsRemoteVersionReceived(remote_bda);
                        },
                .BTM_SetConsolidationCallback =
                        [](BTM_CONSOLIDATION_CB* cb) {
                          btm_client_interface_mock->BTM_SetConsolidationCallback(cb);
                        },
        },
        .sco = {
                .BTM_CreateSco =
                        [](const RawAddress* bd_addr, bool is_orig, uint16_t pkt_types,
                           uint16_t* p_sco_inx, tBTM_SCO_CB* p_conn_cb,
                           tBTM_SCO_WITH_REASON_CB* p_disc_cb) {
                          return btm_client_interface_mock->BTM_CreateSco(
                                  bd_addr, is_orig, pkt_types, p_sco_inx, p_conn_cb, p_disc_cb);
                        },
                .BTM_RegForEScoEvts =
                        [](uint16_t sco_inx, tBTM_ESCO_CBACK* p_esco_cback) {
                          return btm_client_interface_mock->BTM_RegForEScoEvts(sco_inx,
                                                                               p_esco_cback);
                        },
                .BTM_RemoveSco =
                        [](uint16_t sco_inx) {
                          return btm_client_interface_mock->BTM_RemoveSco(sco_inx);
                        },
                .BTM_RemoveScoByBdaddr =
                        [](const RawAddress& bda) {
                          btm_client_interface_mock->BTM_RemoveScoByBdaddr(bda);
                        },
                .BTM_WriteVoiceSettings =
                        [](uint16_t settings) {
                          btm_client_interface_mock->BTM_WriteVoiceSettings(settings);
                        },
                .BTM_EScoConnRsp =
                        [](uint16_t sco_inx, tHCI_STATUS hci_status, enh_esco_params_t* p_parms) {
                          btm_client_interface_mock->BTM_EScoConnRsp(sco_inx, hci_status, p_parms);
                        },
                .BTM_GetNumScoLinks =
                        []() { return btm_client_interface_mock->BTM_GetNumScoLinks(); },
                .BTM_SetEScoMode =
                        [](enh_esco_params_t* p_parms) {
                          return btm_client_interface_mock->BTM_SetEScoMode(p_parms);
                        },
                .BTM_GetScoDebugDump =
                        []() { return btm_client_interface_mock->BTM_GetScoDebugDump(); },
                .BTM_IsScoActiveByBdaddr =
                        [](const RawAddress& remote_bda) {
                          return btm_client_interface_mock->BTM_IsScoActiveByBdaddr(remote_bda);
                        },
        },
        .local = {
                .BTM_ReadLocalDeviceName =
                        [](const char** p_name) {
                          return btm_client_interface_mock->BTM_ReadLocalDeviceName(p_name);
                        },
                .BTM_SetLocalDeviceName =
                        [](const char* p_name) {
                          return btm_client_interface_mock->BTM_SetLocalDeviceName(p_name);
                        },
                .BTM_SetDeviceClass =
                        [](DEV_CLASS dev_class) {
                          return btm_client_interface_mock->BTM_SetDeviceClass(dev_class);
                        },
                .BTM_IsDeviceUp = []() { return btm_client_interface_mock->BTM_IsDeviceUp(); },
                .BTM_ReadDeviceClass =
                        []() { return btm_client_interface_mock->BTM_ReadDeviceClass(); },
        },
        .eir = {
                .BTM_WriteEIR =
                        [](BT_HDR* p_buff) {
                          return btm_client_interface_mock->BTM_WriteEIR(p_buff);
                        },
                .BTM_GetEirSupportedServices =
                        [](uint32_t* p_eir_uuid, uint8_t** p, uint8_t max_num_uuid16,
                           uint8_t* p_num_uuid16) {
                          return btm_client_interface_mock->BTM_GetEirSupportedServices(
                                  p_eir_uuid, p, max_num_uuid16, p_num_uuid16);
                        },
                .BTM_GetEirUuidList =
                        [](const uint8_t* p_eir, size_t eir_len, uint8_t uuid_size,
                           uint8_t* p_num_uuid, uint8_t* p_uuid_list, uint8_t max_num_uuid) {
                          return btm_client_interface_mock->BTM_GetEirUuidList(
                                  p_eir, eir_len, uuid_size, p_num_uuid, p_uuid_list, max_num_uuid);
                        },
                .BTM_AddEirService =
                        [](uint32_t* p_eir_uuid, uint16_t uuid16) {
                          btm_client_interface_mock->BTM_AddEirService(p_eir_uuid, uuid16);
                        },
                .BTM_RemoveEirService =
                        [](uint32_t* p_eir_uuid, uint16_t uuid16) {
                          btm_client_interface_mock->BTM_RemoveEirService(p_eir_uuid, uuid16);
                        },
        },
        .db = {
                .BTM_InqDbRead =
                        [](const RawAddress& p_bda) {
                          return btm_client_interface_mock->BTM_InqDbRead(p_bda);
                        },
                .BTM_InqDbFirst = []() { return btm_client_interface_mock->BTM_InqDbFirst(); },
                .BTM_InqDbNext =
                        [](tBTM_INQ_INFO* p_cur) {
                          return btm_client_interface_mock->BTM_InqDbNext(p_cur);
                        },
                .BTM_ClearInqDb =
                        [](const RawAddress* p_bda) {
                          return btm_client_interface_mock->BTM_ClearInqDb(p_bda);
                        },
        },
        .vendor =
                {
                        .BTM_VendorSpecificCommand =
                                [](uint16_t opcode, uint8_t param_len, uint8_t* p_param_buf,
                                   tBTM_VSC_CMPL_CB* p_cb) {
                                  btm_client_interface_mock->BTM_VendorSpecificCommand(
                                          opcode, param_len, p_param_buf, p_cb);
                                },
                },
};
}  // namespace

void BTM_BleReadControllerFeatures(void (*)(tHCI_ERROR_CODE)) {}
tBTM_STATUS BTM_BleGetEnergyInfo(tBTM_BLE_ENERGY_INFO_CBACK* /* p_ener_cback */) {
  return tBTM_STATUS::BTM_SUCCESS;
}

// Reset the working btm client interface to the default
void reset_mock_btm_client_interface() {
  btm_client_interface_mock = &default_btm_client_interface_mock;
}
void set_mock_btm_client_interface(MockBtmClientInterface* mock) {
  btm_client_interface_mock = mock;
}

// Serve the working btm client interface
struct btm_client_interface_t& get_btm_client_interface() { return btm_client_interface; }
