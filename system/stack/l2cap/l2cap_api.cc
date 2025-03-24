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

#include "stack/include/l2cap_interface.h"
#include "stack/l2cap/internal/l2c_api.h"
#include "stack/l2cap/l2c_api.h"

static bluetooth::stack::l2cap::Impl l2cap_impl;
static bluetooth::stack::l2cap::Interface* interface_ = &l2cap_impl;

bluetooth::stack::l2cap::Interface& bluetooth::stack::l2cap::get_interface() { return *interface_; }

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_Register(
        uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
        tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu, uint16_t required_remote_mtu,
        uint16_t sec_level) {
  return ::L2CA_Register(psm, p_cb_info, enable_snoop, p_ertm_info, my_mtu, required_remote_mtu,
                         sec_level);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_RegisterWithSecurity(
        uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, bool enable_snoop,
        tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu, uint16_t required_remote_mtu,
        uint16_t sec_level) {
  return ::L2CA_RegisterWithSecurity(psm, p_cb_info, enable_snoop, p_ertm_info, my_mtu,
                                     required_remote_mtu, sec_level);
}

void bluetooth::stack::l2cap::Impl::L2CA_Deregister(uint16_t psm) { ::L2CA_Deregister(psm); }

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_AllocateLePSM(void) {
  return ::L2CA_AllocateLePSM();
}

void bluetooth::stack::l2cap::Impl::L2CA_FreeLePSM(uint16_t psm) { return ::L2CA_FreeLePSM(psm); }

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_ConnectReq(uint16_t psm,
                                                                      const RawAddress& p_bd_addr) {
  return ::L2CA_ConnectReq(psm, p_bd_addr);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_ConnectReqWithSecurity(
        uint16_t psm, const RawAddress& p_bd_addr, uint16_t sec_level) {
  return ::L2CA_ConnectReqWithSecurity(psm, p_bd_addr, sec_level);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_RegisterLECoc(
        uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info, uint16_t sec_level,
        tL2CAP_LE_CFG_INFO cfg) {
  return ::L2CA_RegisterLECoc(psm, p_cb_info, sec_level, cfg);
}

void bluetooth::stack::l2cap::Impl::L2CA_DeregisterLECoc(uint16_t psm) {
  ::L2CA_DeregisterLECoc(psm);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_ConnectLECocReq(
        uint16_t psm, const RawAddress& p_bd_addr, tL2CAP_LE_CFG_INFO* p_cfg, uint16_t sec_level) {
  return ::L2CA_ConnectLECocReq(psm, p_bd_addr, p_cfg, sec_level);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_GetPeerLECocConfig(
        uint16_t lcid, tL2CAP_LE_CFG_INFO* peer_cfg) {
  return ::L2CA_GetPeerLECocConfig(lcid, peer_cfg);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_GetPeerLECocCredit(
        const RawAddress& bd_addr, uint16_t lcid) {
  return ::L2CA_GetPeerLECocCredit(bd_addr, lcid);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_ReconfigCreditBasedConnsReq(
        const RawAddress& bd_addr, std::vector<uint16_t>& lcids, tL2CAP_LE_CFG_INFO* p_cfg) {
  return ::L2CA_ReconfigCreditBasedConnsReq(bd_addr, lcids, p_cfg);
}

[[nodiscard]] std::vector<uint16_t> bluetooth::stack::l2cap::Impl::L2CA_ConnectCreditBasedReq(
        uint16_t psm, const RawAddress& p_bd_addr, tL2CAP_LE_CFG_INFO* p_cfg) {
  return ::L2CA_ConnectCreditBasedReq(psm, p_bd_addr, p_cfg);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_ConnectCreditBasedRsp(
        const RawAddress& p_bd_addr, uint8_t id, std::vector<uint16_t>& accepted_lcids,
        tL2CAP_LE_RESULT_CODE result, tL2CAP_LE_CFG_INFO* p_cfg) {
  return ::L2CA_ConnectCreditBasedRsp(p_bd_addr, id, accepted_lcids, result, p_cfg);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_DisconnectReq(uint16_t cid) {
  return ::L2CA_DisconnectReq(cid);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_DisconnectLECocReq(uint16_t cid) {
  return ::L2CA_DisconnectLECocReq(cid);
}

[[nodiscard]] tL2CAP_DW_RESULT bluetooth::stack::l2cap::Impl::L2CA_DataWrite(uint16_t cid,
                                                                             BT_HDR* p_data) {
  return ::L2CA_DataWrite(cid, p_data);
}

[[nodiscard]] tL2CAP_DW_RESULT bluetooth::stack::l2cap::Impl::L2CA_LECocDataWrite(uint16_t cid,
                                                                                  BT_HDR* p_data) {
  return ::L2CA_LECocDataWrite(cid, p_data);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetIdleTimeoutByBdAddr(
        const RawAddress& bd_addr, uint16_t timeout, tBT_TRANSPORT transport) {
  return ::L2CA_SetIdleTimeoutByBdAddr(bd_addr, timeout, transport);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_FlushChannel(uint16_t lcid,
                                                                        uint16_t num_to_flush) {
  return ::L2CA_FlushChannel(lcid, num_to_flush);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_UseLatencyMode(const RawAddress& bd_addr,
                                                                      bool use_latency_mode) {
  return ::L2CA_UseLatencyMode(bd_addr, use_latency_mode);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetAclPriority(const RawAddress& bd_addr,
                                                                      tL2CAP_PRIORITY priority) {
  return ::L2CA_SetAclPriority(bd_addr, priority);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetAclLatency(const RawAddress& bd_addr,
                                                                     tL2CAP_LATENCY latency) {
  return ::L2CA_SetAclLatency(bd_addr, latency);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetTxPriority(
        uint16_t cid, tL2CAP_CHNL_PRIORITY priority) {
  return ::L2CA_SetTxPriority(cid, priority);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetChnlFlushability(uint16_t cid,
                                                                           bool is_flushable) {
  return ::L2CA_SetChnlFlushability(cid, is_flushable);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_GetPeerFeatures(const RawAddress& bd_addr,
                                                                       uint32_t* p_ext_feat,
                                                                       uint8_t* p_chnl_mask) {
  return ::L2CA_GetPeerFeatures(bd_addr, p_ext_feat, p_chnl_mask);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_RegisterFixedChannel(
        uint16_t fixed_cid, tL2CAP_FIXED_CHNL_REG* p_freg) {
  return ::L2CA_RegisterFixedChannel(fixed_cid, p_freg);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_ConnectFixedChnl(uint16_t fixed_cid,
                                                                        const RawAddress& bd_addr) {
  return ::L2CA_ConnectFixedChnl(fixed_cid, bd_addr);
}

[[nodiscard]] tL2CAP_DW_RESULT bluetooth::stack::l2cap::Impl::L2CA_SendFixedChnlData(
        uint16_t fixed_cid, const RawAddress& rem_bda, BT_HDR* p_buf) {
  return ::L2CA_SendFixedChnlData(fixed_cid, rem_bda, p_buf);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_RemoveFixedChnl(uint16_t fixed_cid,
                                                                       const RawAddress& rem_bda) {
  return ::L2CA_RemoveFixedChnl(fixed_cid, rem_bda);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SetLeGattTimeout(const RawAddress& rem_bda,
                                                                        uint16_t idle_tout) {
  return ::L2CA_SetLeGattTimeout(rem_bda, idle_tout);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_MarkLeLinkAsActive(
        const RawAddress& rem_bda) {
  return ::L2CA_MarkLeLinkAsActive(rem_bda);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_UpdateBleConnParams(
        const RawAddress& rem_bda, uint16_t min_int, uint16_t max_int, uint16_t latency,
        uint16_t timeout, uint16_t min_ce_len, uint16_t max_ce_len) {
  return ::L2CA_UpdateBleConnParams(rem_bda, min_int, max_int, latency, timeout, min_ce_len,
                                    max_ce_len);
}

void bluetooth::stack::l2cap::Impl::L2CA_LockBleConnParamsForServiceDiscovery(
        const RawAddress& rem_bda, bool lock) {
  ::L2CA_LockBleConnParamsForServiceDiscovery(rem_bda, lock);
}

void bluetooth::stack::l2cap::Impl::L2CA_LockBleConnParamsForProfileConnection(
        const RawAddress& rem_bda, bool lock) {
  ::L2CA_LockBleConnParamsForProfileConnection(rem_bda, lock);
}

void bluetooth::stack::l2cap::Impl::L2CA_Consolidate(const RawAddress& identity_addr,
                                                     const RawAddress& rpa) {
  ::L2CA_Consolidate(identity_addr, rpa);
}

[[nodiscard]] tHCI_ROLE bluetooth::stack::l2cap::Impl::L2CA_GetBleConnRole(
        const RawAddress& bd_addr) {
  return ::L2CA_GetBleConnRole(bd_addr);
}

[[nodiscard]] uint16_t bluetooth::stack::l2cap::Impl::L2CA_GetBleConnInterval(
        const RawAddress& bd_addr) {
  return ::L2CA_GetBleConnInterval(bd_addr);
}

void bluetooth::stack::l2cap::Impl::L2CA_AdjustConnectionIntervals(uint16_t* min_interval,
                                                                   uint16_t* max_interval,
                                                                   uint16_t floor_interval) {
  ::L2CA_AdjustConnectionIntervals(min_interval, max_interval, floor_interval);
}

void bluetooth::stack::l2cap::Impl::L2CA_SetEcosystemBaseInterval(uint32_t base_interval) {
  ::L2CA_SetEcosystemBaseInterval(base_interval);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_IsLinkEstablished(const RawAddress& bd_addr,
                                                                         tBT_TRANSPORT transport) {
  return ::L2CA_IsLinkEstablished(bd_addr, transport);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_SubrateRequest(
        const RawAddress& rem_bda, uint16_t subrate_min, uint16_t subrate_max, uint16_t max_latency,
        uint16_t cont_num, uint16_t timeout) {
  return ::L2CA_SubrateRequest(rem_bda, subrate_min, subrate_max, max_latency, cont_num, timeout);
}

void bluetooth::stack::l2cap::Impl::L2CA_SetMediaStreamChannel(uint16_t local_media_cid,
                                                               bool status) {
  ::L2CA_SetMediaStreamChannel(local_media_cid, status);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_isMediaChannel(uint16_t handle,
                                                                      uint16_t channel_id,
                                                                      bool is_local_cid) {
  return ::L2CA_isMediaChannel(handle, channel_id, is_local_cid);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_GetRemoteChannelId(uint16_t lcid,
                                                                          uint16_t* rcid) {
  return ::L2CA_GetRemoteChannelId(lcid, rcid);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_GetAclHandle(uint16_t lcid,
                                                                    uint16_t* acl_handle) {
  return ::L2CA_GetAclHandle(lcid, acl_handle);
}

[[nodiscard]] bool bluetooth::stack::l2cap::Impl::L2CA_GetLocalMtu(uint16_t lcid,
                                                                   uint16_t* local_mtu) {
  return ::L2CA_GetLocalMtu(lcid, local_mtu);
}
