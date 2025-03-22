/*
 * Copyright 2025 The Android Open Source Project
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

#include "test/mock/mock_stack_gap_conn_interface.h"

#include "stack/include/gap_api.h"
#include "test/common/mock_functions.h"

namespace {
bluetooth::testing::stack::gap_conn::Mock mock_gap_conn_interface;
bluetooth::testing::stack::gap_conn::Interface* interface_ = &mock_gap_conn_interface;
}  // namespace

void bluetooth::testing::stack::gap_conn::reset_interface() {
  interface_ = &mock_gap_conn_interface;
}

void bluetooth::testing::stack::gap_conn::set_interface(
        bluetooth::testing::stack::gap_conn::Interface* interface) {
  interface_ = interface;
}

uint16_t GAP_ConnOpen(const char* p_serv_name, uint8_t service_id, bool is_server,
                      const RawAddress* p_rem_bda, uint16_t psm, uint16_t le_mps,
                      tL2CAP_CFG_INFO* p_cfg, tL2CAP_ERTM_INFO* ertm_info, uint16_t security,
                      tGAP_CONN_CALLBACK* p_cb, tBT_TRANSPORT transport) {
  inc_func_call_count(__func__);

  return interface_->GAP_ConnOpen(p_serv_name, service_id, is_server, p_rem_bda, psm, le_mps, p_cfg,
                                  ertm_info, security, p_cb, transport);
}

const RawAddress* GAP_ConnGetRemoteAddr(uint16_t gap_handle) {
  inc_func_call_count(__func__);

  return interface_->GAP_ConnGetRemoteAddr(gap_handle);
}
int GAP_GetRxQueueCnt(uint16_t /* handle */, uint32_t* /* p_rx_queue_count */) {
  inc_func_call_count(__func__);
  return 0;
}
uint16_t GAP_ConnClose(uint16_t /* gap_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
uint16_t GAP_ConnGetL2CAPCid(uint16_t /* gap_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
uint16_t GAP_ConnGetRemMtuSize(uint16_t /* gap_handle */) {
  inc_func_call_count(__func__);
  return 0;
}
uint16_t GAP_ConnReadData(uint16_t /* gap_handle */, uint8_t* /* p_data */, uint16_t /* max_len */,
                          uint16_t* /* p_len */) {
  inc_func_call_count(__func__);
  return 0;
}
uint16_t GAP_ConnWriteData(uint16_t /* gap_handle */, BT_HDR* /* msg */) {
  inc_func_call_count(__func__);
  return 0;
}
bool GAP_GetLeChannelInfo(uint16_t /* gap_handle */, uint16_t* /*remote_mtu */,
                          uint16_t* /* local_mps */, uint16_t* /* remote_mps */,
                          uint16_t* /* local_credit */, uint16_t* /* remote_credit */,
                          uint16_t* /* local_cid */, uint16_t* /* remote_cid */,
                          uint16_t* /* acl_handle */) {
  inc_func_call_count(__func__);
  return false;
}
bool GAP_IsTransportLe(uint16_t /* gap_handle */) {
  inc_func_call_count(__func__);
  return false;
}
void GAP_Init(void) { inc_func_call_count(__func__); }

bluetooth::testing::stack::gap_conn::Interface&
bluetooth::testing::stack::gap_conn::get_interface() {
  return *interface_;
}
