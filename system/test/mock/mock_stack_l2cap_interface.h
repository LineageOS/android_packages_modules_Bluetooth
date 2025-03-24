/*
 * Copyright 2024 The Android Open Source Project
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

#include <vector>

#include "stack/include/l2cap_interface.h"

namespace bluetooth {
namespace testing {
namespace stack {
namespace l2cap {

class Mock : public ::bluetooth::stack::l2cap::Interface {
public:
  ~Mock() = default;

  MOCK_METHOD(uint16_t, L2CA_Register,
              (uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
               tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu, uint16_t required_remote_mtu,
               uint16_t sec_level));
  MOCK_METHOD(uint16_t, L2CA_RegisterWithSecurity,
              (uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
               tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu, uint16_t required_remote_mtu,
               uint16_t sec_level));
  MOCK_METHOD(void, L2CA_Deregister, (uint16_t psm));

  MOCK_METHOD(uint16_t, L2CA_AllocateLePSM, ());
  MOCK_METHOD(void, L2CA_FreeLePSM, (uint16_t psm));

  MOCK_METHOD(uint16_t, L2CA_RegisterLECoc,
              (uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, uint16_t sec_level,
               tL2CAP_LE_CFG_INFO cfg));
  MOCK_METHOD(void, L2CA_DeregisterLECoc, (uint16_t psm));

  // Methods used for, both BR/EDR and BLE
  MOCK_METHOD(bool, L2CA_IsLinkEstablished, (const RawAddress& bd_addr, tBT_TRANSPORT transport));
  MOCK_METHOD(bool, L2CA_SetIdleTimeoutByBdAddr,
              (const RawAddress& bd_addr, uint16_t timeout, tBT_TRANSPORT transport));
  MOCK_METHOD(bool, L2CA_GetRemoteChannelId, (uint16_t lcid, uint16_t* rcid));

  MOCK_METHOD(uint16_t, L2CA_ConnectReq, (uint16_t psm, const RawAddress& bd_addr));

  MOCK_METHOD(uint16_t, L2CA_ConnectReqWithSecurity,
              (uint16_t psm, const RawAddress& bd_addr, uint16_t sec_level));
  MOCK_METHOD(bool, L2CA_SetAclLatency, (const RawAddress& bd_addr, tL2CAP_LATENCY latency));
  MOCK_METHOD(bool, L2CA_UseLatencyMode, (const RawAddress& bd_addr, bool use_latency_mode));
  MOCK_METHOD(bool, L2CA_GetPeerFeatures,
              (const RawAddress& bd_addr, uint32_t* p_ext_feat, uint8_t* p_chnl_mask));
  MOCK_METHOD(bool, L2CA_SetAclPriority, (const RawAddress& bd_addr, tL2CAP_PRIORITY priority));
  MOCK_METHOD(void, L2CA_AdjustConnectionIntervals,
              (uint16_t* min_interval, uint16_t* max_interval, uint16_t floor_interval));
  MOCK_METHOD(void, L2CA_SetEcosystemBaseInterval, (uint32_t base_interval));

  MOCK_METHOD(bool, L2CA_SubrateRequest,
              (const RawAddress& bd_addr, uint16_t subrate_min, uint16_t subrate_max,
               uint16_t max_latency, uint16_t cont_num, uint16_t timeout));
  MOCK_METHOD(uint16_t, L2CA_FlushChannel, (uint16_t lcid, uint16_t num_to_flush));
  MOCK_METHOD(bool, L2CA_SetTxPriority, (uint16_t cid, tL2CAP_CHNL_PRIORITY priority));
  MOCK_METHOD(bool, L2CA_SetChnlFlushability, (uint16_t cid, bool is_flushable));

  MOCK_METHOD(uint16_t, L2CA_ConnectLECocReq,
              (uint16_t psm, const RawAddress& bd_addr, tL2CAP_LE_CFG_INFO* p_cfg,
               uint16_t sec_level));
  MOCK_METHOD(std::vector<uint16_t>, L2CA_ConnectCreditBasedReq,
              (uint16_t psm, const RawAddress& bd_addr, tL2CAP_LE_CFG_INFO* p_cfg));
  MOCK_METHOD(bool, L2CA_ConnectCreditBasedRsp,
              (const RawAddress& bd_addr, uint8_t id, std::vector<uint16_t>& accepted_lcids,
               tL2CAP_LE_RESULT_CODE result, tL2CAP_LE_CFG_INFO* p_cfg));
  MOCK_METHOD(uint16_t, L2CA_GetPeerLECocCredit, (const RawAddress& bd_addr, uint16_t lcid));

  MOCK_METHOD(bool, L2CA_ReconfigCreditBasedConnsReq,
              (const RawAddress& bd_addr, std::vector<uint16_t>& lcids, tL2CAP_LE_CFG_INFO* p_cfg));

  MOCK_METHOD(bool, L2CA_UpdateBleConnParams,
              (const RawAddress& bd_addr, uint16_t min_int, uint16_t max_int, uint16_t latency,
               uint16_t timeout, uint16_t min_ce_len, uint16_t max_ce_len));
  MOCK_METHOD(void, L2CA_LockBleConnParamsForServiceDiscovery,
              (const RawAddress& bd_addr, bool lock));
  MOCK_METHOD(void, L2CA_LockBleConnParamsForProfileConnection,
              (const RawAddress& bd_addr, bool lock));
  MOCK_METHOD(tHCI_ROLE, L2CA_GetBleConnRole, (const RawAddress& bd_addr));
  MOCK_METHOD(uint16_t, L2CA_GetBleConnInterval, (const RawAddress& bd_addr));
  MOCK_METHOD(bool, L2CA_SetLeGattTimeout, (const RawAddress& bd_addr, uint16_t idle_tout));
  MOCK_METHOD(bool, L2CA_MarkLeLinkAsActive, (const RawAddress& bd_addr));
  MOCK_METHOD(bool, L2CA_GetPeerLECocConfig, (uint16_t lcid, tL2CAP_LE_CFG_INFO* peer_cfg));
  MOCK_METHOD(uint16_t, L2CA_LeCreditDefault, ());
  MOCK_METHOD(uint16_t, L2CA_LeCreditThreshold, ());

  MOCK_METHOD(void, L2CA_Consolidate, (const RawAddress& identity_addr, const RawAddress& rpa));
  MOCK_METHOD(bool, L2CA_GetAclHandle, (uint16_t lcid, uint16_t* acl_handle));
  MOCK_METHOD(bool, L2CA_GetLocalMtu, (uint16_t lcid, uint16_t* local_mtu));

  // Disconnect methods an active connection for both BR/EDR and BLE
  MOCK_METHOD(bool, L2CA_DisconnectReq, (uint16_t cid));
  MOCK_METHOD(bool, L2CA_DisconnectLECocReq, (uint16_t cid));

  // Data write methods for both BR/EDR and BLE
  MOCK_METHOD(tL2CAP_DW_RESULT, L2CA_DataWrite, (uint16_t cid, BT_HDR* p_data));
  MOCK_METHOD(tL2CAP_DW_RESULT, L2CA_LECocDataWrite, (uint16_t cid, BT_HDR* p_data));

  MOCK_METHOD(bool, L2CA_RegisterFixedChannel, (uint16_t fixed_cid, tL2CAP_FIXED_CHNL_REG* p_freg));
  MOCK_METHOD(bool, L2CA_ConnectFixedChnl, (uint16_t fixed_cid, const RawAddress& bd_addr));
  MOCK_METHOD(tL2CAP_DW_RESULT, L2CA_SendFixedChnlData,
              (uint16_t fixed_cid, const RawAddress& bd_addr, BT_HDR* p_buf));
  MOCK_METHOD(bool, L2CA_RemoveFixedChnl, (uint16_t fixed_cid, const RawAddress& bd_addr));

  // Media methods
  MOCK_METHOD(void, L2CA_SetMediaStreamChannel, (uint16_t local_media_cid, bool status));
  MOCK_METHOD(bool, L2CA_isMediaChannel, (uint16_t handle, uint16_t channel_id, bool is_local_cid));
};

void reset_interface();
void set_interface(bluetooth::stack::l2cap::Interface* interface_);

}  // namespace l2cap
}  // namespace stack
}  // namespace testing
}  // namespace bluetooth
