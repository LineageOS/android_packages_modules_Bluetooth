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

#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

// Test accessible feature page
uint8_t hci_feature_bytes_per_page[HCI_FEATURE_BYTES_PER_PAGE] = {};

namespace {

struct btm_client_interface_t default_btm_client_interface = {
        .lifecycle = {
                .BTM_PmRegister = [](uint8_t /* mask */, uint8_t* /* p_pm_id */,
                                     tBTM_PM_STATUS_CBACK* /* p_cb */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .ACL_RegisterClient = [](struct acl_client_callback_s* /* callbacks */) {},
                .ACL_UnregisterClient = [](struct acl_client_callback_s* /* callbacks */) {},
                .btm_init = []() {},
                .btm_free = []() {},
                .btm_ble_init = []() {},
                .btm_ble_free = []() {},
                .BTM_reset_complete = []() {},
        },
        .peer = {
                .BTM_IsAclConnectionUp = [](const RawAddress& /* remote_bda */,
                                            tBT_TRANSPORT /* transport */) -> bool {
                  return false;
                },
                .BTM_ReadConnectedTransportAddress = [](RawAddress* /* remote_bda */,
                                                        tBT_TRANSPORT /* transport */) -> bool {
                  return false;
                },
                .BTM_ReadRemoteFeatures = [](const RawAddress& /* addr */) -> uint8_t* {
                  return hci_feature_bytes_per_page;
                },
                .BTM_ReadDevInfo = [](const RawAddress& /* remote_bda */,
                                      tBT_DEVICE_TYPE* /* p_dev_type */,
                                      tBLE_ADDR_TYPE* /* p_addr_type */) {},
                .BTM_GetMaxPacketSize = [](const RawAddress& /* bd_addr */) -> uint16_t {
                  return 0;
                },
                .BTM_ReadRemoteVersion =
                        [](const RawAddress& /* addr */, uint8_t* /* lmp_version */,
                           uint16_t* /* manufacturer */,
                           uint16_t* /* lmp_sub_version */) -> bool { return false; },

                .BTM_GetPeerDeviceTypeFromFeatures = [](const RawAddress& /* bd_addr */)
                        -> tBT_DEVICE_TYPE { return BT_DEVICE_TYPE_DUMO; },

                .BTM_RequestPeerSCA = [](const RawAddress& /* remote_bda */,
                                         tBT_TRANSPORT /* transport */) {},
                .BTM_GetPeerSCA = [](const RawAddress& /* remote_bda */,
                                     tBT_TRANSPORT /* transport */) -> uint8_t { return 0; },
                .BTM_IsPhy2mSupported = [](const RawAddress& /* remote_bda */,
                                           tBT_TRANSPORT /* transport */) -> bool { return true; },
                .BTM_GetHCIConnHandle = [](const RawAddress& /* remote_bda */,
                                           tBT_TRANSPORT /* transport */) -> uint16_t { return 0; },
        },
        .link_policy = {
                .BTM_GetRole = [](const RawAddress& /* remote_bd_addr */, tHCI_ROLE* /* p_role */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_SetPowerMode = [](uint8_t /* pm_id */, const RawAddress& /* remote_bda */,
                                       const tBTM_PM_PWR_MD* /* p_mode */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_SetSsrParams = [](RawAddress const& /* bd_addr */, uint16_t /* max_lat */,
                                       uint16_t /* min_rmt_to */, uint16_t /* min_loc_to */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_SwitchRoleToCentral = [](const RawAddress& /* remote_bd_addr */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_block_role_switch_for = [](const RawAddress& /* peer_addr */) {},
                .BTM_block_sniff_mode_for = [](const RawAddress& /* peer_addr */) {},
                .BTM_default_unblock_role_switch = []() {},
                .BTM_unblock_role_switch_for = [](const RawAddress& /* peer_addr */) {},
                .BTM_unblock_sniff_mode_for = [](const RawAddress& /* peer_addr */) {},
                .BTM_WritePageTimeout = [](uint16_t /* timeout */) {},
        },
        .link_controller = {
                .BTM_GetLinkSuperTout = [](const RawAddress& /* remote_bda */,
                                           uint16_t* /* p_timeout */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_ReadRSSI = [](const RawAddress& /* remote_bda */, tBTM_CMPL_CB* /* p_cb */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
        },
        .security = {
                .BTM_Sec_Init = []() {},
                .BTM_Sec_Free = []() {},
                .BTM_SecRegister = [](const tBTM_APPL_INFO* /* p_cb_info */) -> bool {
                  return false;
                },
                .BTM_BleLoadLocalKeys = [](uint8_t /* key_type */,
                                           tBTM_BLE_LOCAL_KEYS* /* p_key */) {},
                .BTM_SecAddDevice = [](const RawAddress& /* bd_addr */, DEV_CLASS /* dev_class */,
                                       LinkKey /* link_key */, uint8_t /* key_type */,
                                       uint8_t /* pin_length */) {},
                .BTM_SecAddBleDevice = [](const RawAddress& /* bd_addr */,
                                          tBT_DEVICE_TYPE /* dev_type */,
                                          tBLE_ADDR_TYPE /* addr_type */) {},
                .BTM_SecDeleteDevice = [](const RawAddress& /* bd_addr */) -> bool { return true; },
                .BTM_SecAddBleKey = [](const RawAddress& /* bd_addr */,
                                       tBTM_LE_KEY_VALUE* /* p_le_key */,
                                       tBTM_LE_KEY_TYPE /* key_type */) {},
                .BTM_SecClearSecurityFlags = [](const RawAddress& /* bd_addr */) {},
                .BTM_SetEncryption = [](const RawAddress& /* bd_addr */,
                                        tBT_TRANSPORT /* transport */,
                                        tBTM_SEC_CALLBACK* /* p_callback */, void* /* p_ref_data */,
                                        tBTM_BLE_SEC_ACT /* sec_act */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_IsEncrypted = [](const RawAddress& /* bd_addr */,
                                      tBT_TRANSPORT /* transport */) -> bool { return false; },
                .BTM_SecIsLeSecurityPending = [](const RawAddress& /* bd_addr */) -> bool {
                  return false;
                },
                .BTM_IsBonded = [](const RawAddress& /* bd_addr */,
                                         tBT_TRANSPORT /* transport */) -> bool { return false; },
                .BTM_SetSecurityLevel = [](bool /* is_originator */, const char* /*p_name */,
                                           uint8_t /* service_id */, uint16_t /* sec_level */,
                                           uint16_t /* psm */, uint32_t /* mx_proto_id */,
                                           uint32_t /* mx_chan_id */) -> bool { return false; },
                .BTM_SecClrService = [](uint8_t /* service_id */) -> uint8_t { return 0; },
                .BTM_SecClrServiceByPsm = [](uint16_t /* psm */) -> uint8_t { return 0; },
                .BTM_SecBond = [](const RawAddress& /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
                                  tBT_TRANSPORT /* transport */, tBT_DEVICE_TYPE /* device_type */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_SecBondCancel = [](const RawAddress& /* bd_addr */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_RemoteOobDataReply = [](tBTM_STATUS /* res */, const RawAddress& /* bd_addr */,
                                             const Octet16& /* c */, const Octet16& /* r */) {},
                .BTM_PINCodeReply = [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */,
                                       uint8_t /* pin_len */, uint8_t* /* p_pin */) {},
                .BTM_SecConfirmReqReply = [](tBTM_STATUS /* res */, tBT_TRANSPORT /* transport */,
                                             const RawAddress /* bd_addr */) {},
                .BTM_BleSirkConfirmDeviceReply = [](const RawAddress& /* bd_addr */,
                                                    tBTM_STATUS /* res */) {},
                .BTM_BlePasskeyReply = [](const RawAddress& /* bd_addr */, tBTM_STATUS /* res */,
                                          uint32_t /* passkey */) {},
                .BTM_GetSecurityMode = []() -> uint8_t { return 0; },
                .BTM_SecReadDevName = [](const RawAddress& /* bd_addr */) -> const char* {
                  return nullptr;
                },
                .BTM_SecReadDevClass = [](const RawAddress& /* bd_addr */) -> DEV_CLASS {
                  return kDevClassEmpty;
                },
        },
        .ble = {
                .BTM_BleGetEnergyInfo = [](tBTM_BLE_ENERGY_INFO_CBACK* /* p_ener_cback */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_BleObserve = [](bool /* start */, uint8_t /* duration */,
                                     tBTM_INQ_RESULTS_CB* /* p_results_cb */,
                                     tBTM_CMPL_CB* /* p_cmpl_cb */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_SetBleDataLength = [](const RawAddress& /* bd_addr */,
                                           uint16_t /* tx_pdu_length */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_BleReadControllerFeatures =
                        [](tBTM_BLE_CTRL_FEATURES_CBACK* /* p_vsc_cback */) {},
                .BTM_BleSetPhy = [](const RawAddress& /* bd_addr */, uint8_t /* tx_phys */,
                                    uint8_t /* rx_phys */, uint16_t /* phy_options */) {},
                .BTM_BleSetPrefConnParams =
                        [](const RawAddress& /* bd_addr */, uint16_t /* min_conn_int */,
                           uint16_t /* max_conn_int */, uint16_t /* peripheral_latency */,
                           uint16_t /* supervision_tout */) {},
                .BTM_UseLeLink = [](const RawAddress& /* bd_addr */) -> bool { return false; },
                .BTM_IsRemoteVersionReceived = [](const RawAddress& /* remote_bda */) -> bool {
                  return true;
                },
                .BTM_SetConsolidationCallback = [](BTM_CONSOLIDATION_CB* /* cb */) {},
        },
        .sco = {
                .BTM_CreateSco = [](const RawAddress* /* remote_bda */, bool /* is_orig */,
                                    uint16_t /* pkt_types */, uint16_t* /* p_sco_inx */,
                                    tBTM_SCO_CB* /* p_conn_cb */, tBTM_SCO_CB* /* p_disc_cb */)
                        -> tBTM_STATUS { return tBTM_STATUS::BTM_SUCCESS; },
                .BTM_RegForEScoEvts = [](uint16_t /* sco_inx */,
                                         tBTM_ESCO_CBACK* /* p_esco_cback */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_RemoveSco = [](uint16_t /* sco_inx */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_RemoveScoByBdaddr = [](const RawAddress& /* bd_addr */) {},
                .BTM_WriteVoiceSettings = [](uint16_t /* settings */) {},
                .BTM_EScoConnRsp = [](uint16_t /* sco_inx */, tHCI_STATUS /* hci_status */,
                                      enh_esco_params_t* /* p_parms */) {},
                .BTM_GetNumScoLinks = []() -> uint8_t { return 0; },
                .BTM_SetEScoMode = [](enh_esco_params_t* /* p_parms */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_GetScoDebugDump = []() -> tBTM_SCO_DEBUG_DUMP { return {}; },
                .BTM_IsScoActiveByBdaddr = [](const RawAddress& /* remote_bda */) -> bool {
                  return false;
                },
        },
        .local = {
                .BTM_ReadLocalDeviceName = [](const char** /* p_name */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_SetLocalDeviceName = [](const char* /* p_name */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_SetDeviceClass = [](DEV_CLASS /* dev_class */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_IsDeviceUp = []() -> bool { return true; },
                .BTM_ReadDeviceClass = []() -> DEV_CLASS { return kDevClassEmpty; },
        },
        .eir = {
                .BTM_WriteEIR = [](BT_HDR* /* p_buff */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
                .BTM_GetEirSupportedServices = [](uint32_t* /* p_eir_uuid */, uint8_t** /* p */,
                                                  uint8_t /* max_num_uuid16 */,
                                                  uint8_t* /* p_num_uuid16 */) -> uint8_t {
                  return 0;
                },
                .BTM_GetEirUuidList = [](const uint8_t* /* p_eir */, size_t /* eir_len */,
                                         uint8_t /* uuid_size */, uint8_t* /* p_num_uuid */,
                                         uint8_t* /* p_uuid_list */,
                                         uint8_t /* max_num_uuid */) -> uint8_t { return 0; },
                .BTM_AddEirService = [](uint32_t* /* p_eir_uuid */, uint16_t /* uuid16 */) {},
                .BTM_RemoveEirService = [](uint32_t* /* p_eir_uuid */, uint16_t /* uuid16 */) {},
        },
        .db = {
                .BTM_InqDbRead = [](const RawAddress& /* p_bda */) -> tBTM_INQ_INFO* {
                  return nullptr;
                },
                .BTM_InqDbFirst = []() -> tBTM_INQ_INFO* { return nullptr; },
                .BTM_InqDbNext = [](tBTM_INQ_INFO* /* p_cur */) -> tBTM_INQ_INFO* {
                  return nullptr;
                },
                .BTM_ClearInqDb = [](const RawAddress* /* p_bda */) -> tBTM_STATUS {
                  return tBTM_STATUS::BTM_SUCCESS;
                },
        },
        .vendor =
                {
                        .BTM_VendorSpecificCommand =
                                [](uint16_t /* opcode */, uint8_t /* param_len */,
                                   uint8_t* /* p_param_buf */, tBTM_VSC_CMPL_CB* /* p_cb */) {},
                },
};

}  // namespace

void BTM_BleReadControllerFeatures(void (*)(tHCI_ERROR_CODE)) {}
tBTM_STATUS BTM_BleGetEnergyInfo(tBTM_BLE_ENERGY_INFO_CBACK* /* p_ener_cback */) {
  return tBTM_STATUS::BTM_SUCCESS;
}

// Initialize the working btm client interface to the default
struct btm_client_interface_t mock_btm_client_interface = default_btm_client_interface;

// Reset the working btm client interface to the default
void reset_mock_btm_client_interface() { mock_btm_client_interface = default_btm_client_interface; }

// Serve the working btm client interface
struct btm_client_interface_t& get_btm_client_interface() { return mock_btm_client_interface; }
