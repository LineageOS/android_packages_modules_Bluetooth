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

#include <gmock/gmock.h>

#include "stack/include/btm_client_interface.h"

class MockBtmClientInterface {
public:
  MOCK_METHOD(tBTM_STATUS, BTM_PmRegister,
              (uint8_t mask, uint8_t* p_pm_id, tBTM_PM_STATUS_CBACK* p_cback));
  MOCK_METHOD(void, ACL_RegisterClient, (struct acl_client_callback_s* callbacks));
  MOCK_METHOD(void, ACL_UnregisterClient, (struct acl_client_callback_s* callbacks));
  MOCK_METHOD(void, btm_init, ());
  MOCK_METHOD(void, btm_free, ());
  MOCK_METHOD(void, btm_ble_init, ());
  MOCK_METHOD(void, btm_ble_free, ());
  MOCK_METHOD(void, BTM_reset_complete, ());
  MOCK_METHOD(bool, BTM_IsAclConnectionUp, (const RawAddress& bd_addr, tBT_TRANSPORT transport));
  MOCK_METHOD((std::pair<RawAddress, RawAddress>), BTM_GetConnectedTransportAddress,
              (RawAddress bd_addr));
  MOCK_METHOD(uint8_t*, BTM_ReadRemoteFeatures, (const RawAddress&));
  MOCK_METHOD(DevInfo, BTM_ReadDevInfo, (const RawAddress& bd_addr));
  MOCK_METHOD(uint16_t, BTM_GetMaxPacketSize, (const RawAddress& bd_addr));
  MOCK_METHOD(bool, BTM_ReadRemoteVersion,
              (const RawAddress& addr, uint8_t* lmp_version, uint16_t* manufacturer,
               uint16_t* lmp_sub_version));
  MOCK_METHOD(tBT_DEVICE_TYPE, BTM_GetPeerDeviceTypeFromFeatures, (const RawAddress& bd_addr));
  MOCK_METHOD(void, BTM_RequestPeerSCA, (const RawAddress& remote_bda, tBT_TRANSPORT transport));
  MOCK_METHOD(uint8_t, BTM_GetPeerSCA, (const RawAddress& remote_bda, tBT_TRANSPORT transport));
  MOCK_METHOD(bool, BTM_IsPhy2mSupported, (const RawAddress& remote_bda, tBT_TRANSPORT transport));
  MOCK_METHOD(uint16_t, BTM_GetHCIConnHandle,
              (const RawAddress& bd_addr, tBT_TRANSPORT transport));
  MOCK_METHOD(bool, BTM_IsAclConnectionUpAndHandleValid,
              (const RawAddress& remote_bda, tBT_TRANSPORT transport));
  MOCK_METHOD(tBTM_STATUS, BTM_GetRole,
              (const RawAddress& remote_bd_addr, tBT_TRANSPORT transport, tHCI_ROLE* p_role));
  MOCK_METHOD(tBTM_STATUS, BTM_SetPowerMode,
              (uint8_t pm_id, const RawAddress& bd_addr, const tBTM_PM_PWR_MD* p_mode));
  MOCK_METHOD(tBTM_STATUS, BTM_SetSsrParams,
              (const RawAddress& bd_addr, uint16_t max_lat, uint16_t min_rmt_to,
               uint16_t min_loc_to));
  MOCK_METHOD(tBTM_STATUS, BTM_SwitchRoleToCentral, (const RawAddress& remote_bd_addr));
  MOCK_METHOD(void, BTM_block_role_switch_for, (const RawAddress& peer_addr));
  MOCK_METHOD(void, BTM_block_sniff_mode_for, (const RawAddress& peer_addr));
  MOCK_METHOD(void, BTM_default_unblock_role_switch, ());
  MOCK_METHOD(void, BTM_unblock_role_switch_for, (const RawAddress& peer_addr));
  MOCK_METHOD(void, BTM_unblock_sniff_mode_for, (const RawAddress& peer_addr));
  MOCK_METHOD(void, BTM_WritePageTimeout, (uint16_t timeout));
  MOCK_METHOD(tBTM_STATUS, BTM_GetLinkSuperTout, (const RawAddress& bd_addr, uint16_t* p_timeout));
  MOCK_METHOD(tBTM_STATUS, BTM_ReadRSSI, (const RawAddress& bd_addr, tBTM_READ_RSSI_CB* p_cb));
  MOCK_METHOD(tBTM_STATUS, BTM_BleGetEnergyInfo, (tBTM_BLE_ENERGY_INFO_CBACK* callback));
  MOCK_METHOD(tBTM_STATUS, BTM_BleObserve,
              (bool start, uint8_t duration, tBTM_INQ_RESULTS_CB* p_results_cb,
               tBTM_INQUIRY_CMPL_CB* p_cmpl_cb));
  MOCK_METHOD(tBTM_STATUS, BTM_SetBleDataLength,
              (const RawAddress& bd_addr, uint16_t tx_pdu_length, bool is_privileged_client));
  MOCK_METHOD(void, BTM_BleReadControllerFeatures, (tBTM_BLE_CTRL_FEATURES_CBACK* p_vsc_cback));
  MOCK_METHOD(void, BTM_BleSetPhy,
              (const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys, uint16_t phy_options));
  MOCK_METHOD(void, BTM_BleSetPrefConnParams,
              (const RawAddress& bd_addr, uint16_t min_conn_int, uint16_t max_conn_int,
               uint16_t peripheral_latency, uint16_t supervision_tout));
  MOCK_METHOD(bool, BTM_UseLeLink, (const RawAddress& bd_addr));
  MOCK_METHOD(bool, BTM_IsRemoteVersionReceived, (const RawAddress& remote_bda));
  MOCK_METHOD(void, BTM_SetConsolidationCallback, (BTM_CONSOLIDATION_CB* cb));
  MOCK_METHOD(tBTM_STATUS, BTM_CreateSco,
              (const RawAddress* bd_addr, bool is_orig, uint16_t pkt_types, uint16_t* p_sco_inx,
               tBTM_SCO_CB* p_conn_cb, tBTM_SCO_WITH_REASON_CB* p_disc_cb));
  MOCK_METHOD(tBTM_STATUS, BTM_RegForEScoEvts, (uint16_t sco_inx, tBTM_ESCO_CBACK* p_esco_cback));
  MOCK_METHOD(tBTM_STATUS, BTM_RemoveSco, (uint16_t sco_inx));
  MOCK_METHOD(void, BTM_RemoveScoByBdaddr, (const RawAddress& bda));
  MOCK_METHOD(void, BTM_WriteVoiceSettings, (uint16_t settings));
  MOCK_METHOD(void, BTM_EScoConnRsp,
              (uint16_t sco_inx, tHCI_STATUS hci_status, enh_esco_params_t* p_parms));
  MOCK_METHOD(uint8_t, BTM_GetNumScoLinks, ());
  MOCK_METHOD(tBTM_STATUS, BTM_SetEScoMode, (enh_esco_params_t* p_parms));
  MOCK_METHOD(tBTM_SCO_DEBUG_DUMP, BTM_GetScoDebugDump, ());
  MOCK_METHOD(bool, BTM_IsScoActiveByBdaddr, (const RawAddress& remote_bda));
  MOCK_METHOD(tBTM_STATUS, BTM_ReadLocalDeviceName, (const char** p_name));
  MOCK_METHOD(tBTM_STATUS, BTM_SetLocalDeviceName, (const char* p_name));
  MOCK_METHOD(tBTM_STATUS, BTM_SetDeviceClass, (DEV_CLASS dev_class));
  MOCK_METHOD(bool, BTM_IsDeviceUp, ());
  MOCK_METHOD(DEV_CLASS, BTM_ReadDeviceClass, ());
  MOCK_METHOD(tBTM_STATUS, BTM_WriteEIR, (BT_HDR* p_buff));
  MOCK_METHOD(uint8_t, BTM_GetEirSupportedServices,
              (uint32_t* p_eir_uuid, uint8_t** p, uint8_t max_num_uuid16, uint8_t* p_num_uuid16));
  MOCK_METHOD(uint8_t, BTM_GetEirUuidList,
              (const uint8_t* p_eir, size_t eir_len, uint8_t uuid_size, uint8_t* p_num_uuid,
               uint8_t* p_uuid_list, uint8_t max_num_uuid));
  MOCK_METHOD(void, BTM_AddEirService, (uint32_t* p_eir_uuid, uint16_t uuid16));
  MOCK_METHOD(void, BTM_RemoveEirService, (uint32_t* p_eir_uuid, uint16_t uuid16));
  MOCK_METHOD(tBTM_INQ_INFO*, BTM_InqDbRead, (const RawAddress& p_bda));
  MOCK_METHOD(tBTM_INQ_INFO*, BTM_InqDbFirst, ());
  MOCK_METHOD(tBTM_INQ_INFO*, BTM_InqDbNext, (tBTM_INQ_INFO* p_cur));
  MOCK_METHOD(tBTM_STATUS, BTM_ClearInqDb, (const RawAddress* p_bda));
  MOCK_METHOD(void, BTM_VendorSpecificCommand,
              (uint16_t opcode, uint8_t param_len, uint8_t* p_param_buf, tBTM_VSC_CMPL_CB* p_cb));
};

void set_mock_btm_client_interface(MockBtmClientInterface*);
void reset_mock_btm_client_interface();
