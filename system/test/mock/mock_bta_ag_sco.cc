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
 *   Functions generated:24
 *
 *  mockcify.pl ver 0.7.1
 */

// Mock include file to share data between tests and mock
#include "test/mock/mock_bta_ag_sco.h"

#include <cstdint>

#include "test/common/mock_functions.h"

// Original usings
using HfpInterface = bluetooth::audio::hfp::HfpClientInterface;
using namespace bluetooth;

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace bta_ag_sco {

// Function state capture and return values, if needed
struct bta_ag_api_set_active_device bta_ag_api_set_active_device;
struct bta_ag_codec_negotiate bta_ag_codec_negotiate;
struct bta_ag_create_sco bta_ag_create_sco;
struct bta_ag_get_active_device bta_ag_get_active_device;
struct bta_ag_get_sco_offload_enabled bta_ag_get_sco_offload_enabled;
struct bta_ag_is_sco_managed_by_audio bta_ag_is_sco_managed_by_audio;
struct bta_ag_sco_close bta_ag_sco_close;
struct bta_ag_sco_codec_nego bta_ag_sco_codec_nego;
struct bta_ag_sco_conn_close bta_ag_sco_conn_close;
struct bta_ag_sco_conn_open bta_ag_sco_conn_open;
struct bta_ag_sco_conn_rsp bta_ag_sco_conn_rsp;
struct bta_ag_sco_is_active_device bta_ag_sco_is_active_device;
struct bta_ag_sco_is_open bta_ag_sco_is_open;
struct bta_ag_sco_is_opening bta_ag_sco_is_opening;
struct bta_ag_sco_listen bta_ag_sco_listen;
struct bta_ag_sco_open bta_ag_sco_open;
struct bta_ag_sco_read bta_ag_sco_read;
struct bta_ag_sco_shutdown bta_ag_sco_shutdown;
struct bta_ag_sco_write bta_ag_sco_write;
struct bta_ag_set_sco_allowed bta_ag_set_sco_allowed;
struct bta_ag_set_sco_offload_enabled bta_ag_set_sco_offload_enabled;
struct bta_ag_stream_suspended bta_ag_stream_suspended;
struct bta_clear_active_device bta_clear_active_device;

}  // namespace bta_ag_sco
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace bta_ag_sco {

const RawAddress& bta_ag_get_active_device::return_value = RawAddress::kEmpty;
bool bta_ag_get_sco_offload_enabled::return_value = false;
bool bta_ag_is_sco_managed_by_audio::return_value = false;
bool bta_ag_sco_is_active_device::return_value = false;
bool bta_ag_sco_is_open::return_value = false;
bool bta_ag_sco_is_opening::return_value = false;
size_t bta_ag_sco_read::return_value = 0;
size_t bta_ag_sco_write::return_value = 0;

}  // namespace bta_ag_sco
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void bta_ag_api_set_active_device(const RawAddress& new_active_device) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_api_set_active_device(new_active_device);
}
void bta_ag_codec_negotiate(tBTA_AG_SCB* p_scb) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_codec_negotiate(p_scb);
}
void bta_ag_create_sco(tBTA_AG_SCB* p_scb, bool is_orig) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_create_sco(p_scb, is_orig);
}
const RawAddress& bta_ag_get_active_device() {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_get_active_device();
}
bool bta_ag_get_sco_offload_enabled() {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_get_sco_offload_enabled();
}
bool bta_ag_is_sco_managed_by_audio() {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_is_sco_managed_by_audio();
}
void bta_ag_sco_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_close(p_scb, data);
}
void bta_ag_sco_codec_nego(tBTA_AG_SCB* p_scb, bool result) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_codec_nego(p_scb, result);
}
void bta_ag_sco_conn_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_conn_close(p_scb, data);
}
void bta_ag_sco_conn_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_conn_open(p_scb, data);
}
void bta_ag_sco_conn_rsp(tBTA_AG_SCB* p_scb, tBTM_ESCO_CONN_REQ_EVT_DATA* p_data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_conn_rsp(p_scb, p_data);
}
bool bta_ag_sco_is_active_device(const RawAddress& bd_addr) {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_sco_is_active_device(bd_addr);
}
bool bta_ag_sco_is_open(tBTA_AG_SCB* p_scb) {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_sco_is_open(p_scb);
}
bool bta_ag_sco_is_opening(tBTA_AG_SCB* p_scb) {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_sco_is_opening(p_scb);
}
void bta_ag_sco_listen(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_listen(p_scb, data);
}
void bta_ag_sco_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_open(p_scb, data);
}
size_t bta_ag_sco_read(uint8_t* p_buf, uint32_t len) {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_sco_read(p_buf, len);
}
void bta_ag_sco_shutdown(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_sco_shutdown(p_scb, data);
}
size_t bta_ag_sco_write(const uint8_t* p_buf, uint32_t len) {
  inc_func_call_count(__func__);
  return test::mock::bta_ag_sco::bta_ag_sco_write(p_buf, len);
}
void bta_ag_set_sco_allowed(bool value) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_set_sco_allowed(value);
}
void bta_ag_set_sco_offload_enabled(bool value) {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_set_sco_offload_enabled(value);
}
void bta_ag_stream_suspended() {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_ag_stream_suspended();
}
void bta_clear_active_device() {
  inc_func_call_count(__func__);
  test::mock::bta_ag_sco::bta_clear_active_device();
}
// Mocked functions complete
// END mockcify generation
