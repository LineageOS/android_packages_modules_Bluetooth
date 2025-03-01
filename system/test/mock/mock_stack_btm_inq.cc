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
/*
 * Generated mock file from original source file
 *   Functions generated:43
 *
 *  mockcify.pl ver 0.6.0
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_stack_btm_inq.h"

#include <cstdint>

#include "stack/btm/internal/btm_api.h"
#include "stack/include/btm_inq.h"
#include "stack/include/btm_status.h"
#include "stack/include/inq_hci_link_interface.h"
#include "test/common/mock_functions.h"

// Original usings

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_btm_inq {

// Function state capture and return values, if needed
struct BTM_CancelInquiry BTM_CancelInquiry;
struct BTM_EnableInterlacedInquiryScan BTM_EnableInterlacedInquiryScan;
struct BTM_EnableInterlacedPageScan BTM_EnableInterlacedPageScan;
struct BTM_HasEirService BTM_HasEirService;
struct BTM_IsInquiryActive BTM_IsInquiryActive;
struct BTM_SetConnectability BTM_SetConnectability;
struct BTM_SetDiscoverability BTM_SetDiscoverability;
struct BTM_SetInquiryMode BTM_SetInquiryMode;
struct BTM_StartInquiry BTM_StartInquiry;
struct btm_clr_inq_result_flt btm_clr_inq_result_flt;
struct btm_inq_db_find btm_inq_db_find;
struct btm_inq_db_new btm_inq_db_new;
struct btm_inq_db_reset btm_inq_db_reset;
struct btm_inq_find_bdaddr btm_inq_find_bdaddr;
struct btm_process_inq_complete btm_process_inq_complete;

}  // namespace stack_btm_inq
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_btm_inq {

bool BTM_HasEirService::return_value = false;
uint16_t BTM_IsInquiryActive::return_value = 0;
tBTM_STATUS BTM_SetConnectability::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS BTM_SetDiscoverability::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS BTM_SetInquiryMode::return_value = tBTM_STATUS::BTM_SUCCESS;
tBTM_STATUS BTM_StartInquiry::return_value = tBTM_STATUS::BTM_SUCCESS;
tINQ_DB_ENT* btm_inq_db_find::return_value = nullptr;
tINQ_DB_ENT* btm_inq_db_new::return_value = nullptr;
bool btm_inq_find_bdaddr::return_value = false;

}  // namespace stack_btm_inq
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void BTM_CancelInquiry(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::BTM_CancelInquiry();
}
void BTM_EnableInterlacedInquiryScan() {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::BTM_EnableInterlacedInquiryScan();
}
void BTM_EnableInterlacedPageScan() {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::BTM_EnableInterlacedPageScan();
}
bool BTM_HasEirService(const uint32_t* p_eir_uuid, uint16_t uuid16) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_HasEirService(p_eir_uuid, uuid16);
}
uint16_t BTM_IsInquiryActive(void) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_IsInquiryActive();
}
tBTM_STATUS BTM_SetConnectability(uint16_t page_mode) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_SetConnectability(page_mode);
}
tBTM_STATUS BTM_SetDiscoverability(uint16_t inq_mode) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_SetDiscoverability(inq_mode);
}
tBTM_STATUS BTM_SetInquiryMode(uint8_t mode) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_SetInquiryMode(mode);
}
tBTM_STATUS BTM_StartInquiry(tBTM_INQ_RESULTS_CB* p_results_cb, tBTM_CMPL_CB* p_cmpl_cb) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::BTM_StartInquiry(p_results_cb, p_cmpl_cb);
}
void btm_clr_inq_result_flt(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::btm_clr_inq_result_flt();
}
tINQ_DB_ENT* btm_inq_db_find(const RawAddress& p_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::btm_inq_db_find(p_bda);
}
tINQ_DB_ENT* btm_inq_db_new(const RawAddress& p_bda, bool is_ble) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::btm_inq_db_new(p_bda, is_ble);
}
void btm_inq_db_reset(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::btm_inq_db_reset();
}
bool btm_inq_find_bdaddr(const RawAddress& p_bda) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_inq::btm_inq_find_bdaddr(p_bda);
}
void btm_process_inq_complete(tHCI_STATUS status, uint8_t mode) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_inq::btm_process_inq_complete(status, mode);
}
// Mocked functions complete
// END mockcify generation
