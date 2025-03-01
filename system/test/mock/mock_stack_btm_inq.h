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

/*
 * Generated mock file from original source file
 *   Functions generated:43
 *
 *  mockcify.pl ver 0.6.0
 */

#include <cstdint>
#include <functional>

// Original included files, if any

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_status.h"
#include "stack/rnr/remote_name_request.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

// Original usings
using bluetooth::Uuid;

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_btm_inq {

// Name: BTM_CancelInquiry
// Params: void
// Return: void
struct BTM_CancelInquiry {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct BTM_CancelInquiry BTM_CancelInquiry;

// Name: BTM_EnableInterlacedInquiryScan
// Params:
// Return: void
struct BTM_EnableInterlacedInquiryScan {
  std::function<void()> body{[]() {}};
  void operator()() { body(); }
};
extern struct BTM_EnableInterlacedInquiryScan BTM_EnableInterlacedInquiryScan;

// Name: BTM_EnableInterlacedPageScan
// Params:
// Return: void
struct BTM_EnableInterlacedPageScan {
  std::function<void()> body{[]() {}};
  void operator()() { body(); }
};
extern struct BTM_EnableInterlacedPageScan BTM_EnableInterlacedPageScan;

// Name: BTM_HasEirService
// Params: const uint32_t* p_eir_uuid, uint16_t uuid16
// Return: bool
struct BTM_HasEirService {
  static bool return_value;
  std::function<bool(const uint32_t* p_eir_uuid, uint16_t uuid16)> body{
          [](const uint32_t* /* p_eir_uuid */, uint16_t /* uuid16 */) { return return_value; }};
  bool operator()(const uint32_t* p_eir_uuid, uint16_t uuid16) { return body(p_eir_uuid, uuid16); }
};
extern struct BTM_HasEirService BTM_HasEirService;

// Name: BTM_IsInquiryActive
// Params: void
// Return: uint16_t
struct BTM_IsInquiryActive {
  static uint16_t return_value;
  std::function<uint16_t(void)> body{[](void) { return return_value; }};
  uint16_t operator()(void) { return body(); }
};
extern struct BTM_IsInquiryActive BTM_IsInquiryActive;

// Name: BTM_SetConnectability
// Params: uint16_t page_mode
// Return: tBTM_STATUS
struct BTM_SetConnectability {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(uint16_t page_mode)> body{
          [](uint16_t /* page_mode */) { return return_value; }};
  tBTM_STATUS operator()(uint16_t page_mode) { return body(page_mode); }
};
extern struct BTM_SetConnectability BTM_SetConnectability;

// Name: BTM_SetDiscoverability
// Params: uint16_t inq_mode
// Return: tBTM_STATUS
struct BTM_SetDiscoverability {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(uint16_t inq_mode)> body{
          [](uint16_t /* inq_mode */) { return return_value; }};
  tBTM_STATUS operator()(uint16_t inq_mode) { return body(inq_mode); }
};
extern struct BTM_SetDiscoverability BTM_SetDiscoverability;

// Name: BTM_SetInquiryMode
// Params: uint8_t mode
// Return: tBTM_STATUS
struct BTM_SetInquiryMode {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(uint8_t mode)> body{[](uint8_t /* mode */) { return return_value; }};
  tBTM_STATUS operator()(uint8_t mode) { return body(mode); }
};
extern struct BTM_SetInquiryMode BTM_SetInquiryMode;

// Name: BTM_StartInquiry
// Params: tBTM_INQ_RESULTS_CB* p_results_cb, tBTM_CMPL_CB* p_cmpl_cb
// Return: tBTM_STATUS
struct BTM_StartInquiry {
  static tBTM_STATUS return_value;
  std::function<tBTM_STATUS(tBTM_INQ_RESULTS_CB* p_results_cb, tBTM_CMPL_CB* p_cmpl_cb)> body{
          [](tBTM_INQ_RESULTS_CB* /* p_results_cb */, tBTM_CMPL_CB* /* p_cmpl_cb */) {
            return return_value;
          }};
  tBTM_STATUS operator()(tBTM_INQ_RESULTS_CB* p_results_cb, tBTM_CMPL_CB* p_cmpl_cb) {
    return body(p_results_cb, p_cmpl_cb);
  }
};
extern struct BTM_StartInquiry BTM_StartInquiry;

// Name: btm_clr_inq_result_flt
// Params: void
// Return: void
struct btm_clr_inq_result_flt {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btm_clr_inq_result_flt btm_clr_inq_result_flt;

// Name: btm_inq_db_find
// Params: const RawAddress& p_bda
// Return: tINQ_DB_ENT*
struct btm_inq_db_find {
  static tINQ_DB_ENT* return_value;
  std::function<tINQ_DB_ENT*(const RawAddress& p_bda)> body{
          [](const RawAddress& /* p_bda */) { return return_value; }};
  tINQ_DB_ENT* operator()(const RawAddress& p_bda) { return body(p_bda); }
};
extern struct btm_inq_db_find btm_inq_db_find;

// Name: btm_inq_db_new
// Params: const RawAddress& p_bda
// Return: tINQ_DB_ENT*
struct btm_inq_db_new {
  static tINQ_DB_ENT* return_value;
  std::function<tINQ_DB_ENT*(const RawAddress& p_bda, bool is_ble)> body{
          [](const RawAddress& /* p_bda */, bool /* is_ble */) { return return_value; }};
  tINQ_DB_ENT* operator()(const RawAddress& p_bda, bool is_ble) { return body(p_bda, is_ble); }
};
extern struct btm_inq_db_new btm_inq_db_new;

// Name: btm_inq_db_reset
// Params: void
// Return: void
struct btm_inq_db_reset {
  std::function<void(void)> body{[](void) {}};
  void operator()(void) { body(); }
};
extern struct btm_inq_db_reset btm_inq_db_reset;

// Name: btm_inq_find_bdaddr
// Params: const RawAddress& p_bda
// Return: bool
struct btm_inq_find_bdaddr {
  static bool return_value;
  std::function<bool(const RawAddress& p_bda)> body{
          [](const RawAddress& /* p_bda */) { return return_value; }};
  bool operator()(const RawAddress& p_bda) { return body(p_bda); }
};
extern struct btm_inq_find_bdaddr btm_inq_find_bdaddr;

// Name: btm_process_inq_complete
// Params: tHCI_STATUS status, uint8_t mode
// Return: void
struct btm_process_inq_complete {
  std::function<void(tHCI_STATUS status, uint8_t mode)> body{
          [](tHCI_STATUS /* status */, uint8_t /* mode */) {}};
  void operator()(tHCI_STATUS status, uint8_t mode) { body(status, mode); }
};
extern struct btm_process_inq_complete btm_process_inq_complete;

}  // namespace stack_btm_inq
}  // namespace mock
}  // namespace test

// END mockcify generation
