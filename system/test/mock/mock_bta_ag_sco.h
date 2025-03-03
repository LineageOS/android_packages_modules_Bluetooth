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
 *   Functions generated:24
 *
 *  mockcify.pl ver 0.7.1
 */

#include <cstdint>
#include <functional>

// Original included files, if any
// NOTE: Since this is a mock file with mock definitions some number of
//       include files may not be required.  The include-what-you-use
//       still applies, but crafting proper inclusion is out of scope
//       for this effort.  This compilation unit may compile as-is, or
//       may need attention to prune from (or add to ) the inclusion set.
#include <base/functional/bind.h>
#include <bluetooth/log.h>

#include <cstdint>

#include "audio_hal_interface/hfp_client_interface.h"
#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_ag_swb_aptx.h"
#include "btm_status.h"
#include "hci/controller_interface.h"
#include "internal_include/bt_target.h"
#include "main/shim/entry.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sco.h"
#include "stack/btm/btm_sco_hfp_hal.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "types/raw_address.h"

// Original usings
using HfpInterface = bluetooth::audio::hfp::HfpClientInterface;
using namespace bluetooth;

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace bta_ag_sco {

// Shared state between mocked functions and tests
// Name: bta_ag_api_set_active_device
// Params: const RawAddress& new_active_device
// Return: void
struct bta_ag_api_set_active_device {
  std::function<void(const RawAddress& new_active_device)> body{
          [](const RawAddress& /* new_active_device */) {}};
  void operator()(const RawAddress& new_active_device) { body(new_active_device); }
};
extern struct bta_ag_api_set_active_device bta_ag_api_set_active_device;

// Name: bta_ag_codec_negotiate
// Params: tBTA_AG_SCB* p_scb
// Return: void
struct bta_ag_codec_negotiate {
  std::function<void(tBTA_AG_SCB* p_scb)> body{[](tBTA_AG_SCB* /* p_scb */) {}};
  void operator()(tBTA_AG_SCB* p_scb) { body(p_scb); }
};
extern struct bta_ag_codec_negotiate bta_ag_codec_negotiate;

// Name: bta_ag_create_sco
// Params: tBTA_AG_SCB* p_scb, bool is_orig
// Return: void
struct bta_ag_create_sco {
  std::function<void(tBTA_AG_SCB* p_scb, bool is_orig)> body{
          [](tBTA_AG_SCB* /* p_scb */, bool /* is_orig */) {}};
  void operator()(tBTA_AG_SCB* p_scb, bool is_orig) { body(p_scb, is_orig); }
};
extern struct bta_ag_create_sco bta_ag_create_sco;

// Name: bta_ag_get_active_device
// Params:
// Return: const RawAddress&
struct bta_ag_get_active_device {
  static const RawAddress& return_value;
  std::function<const RawAddress&()> body{[]() { return return_value; }};
  const RawAddress& operator()() { return body(); }
};
extern struct bta_ag_get_active_device bta_ag_get_active_device;

// Name: bta_ag_get_sco_offload_enabled
// Params:
// Return: bool
struct bta_ag_get_sco_offload_enabled {
  static bool return_value;
  std::function<bool()> body{[]() { return return_value; }};
  bool operator()() { return body(); }
};
extern struct bta_ag_get_sco_offload_enabled bta_ag_get_sco_offload_enabled;

// Name: bta_ag_is_sco_managed_by_audio
// Params:
// Return: bool
struct bta_ag_is_sco_managed_by_audio {
  static bool return_value;
  std::function<bool()> body{[]() { return return_value; }};
  bool operator()() { return body(); }
};
extern struct bta_ag_is_sco_managed_by_audio bta_ag_is_sco_managed_by_audio;

// Name: bta_ag_sco_close
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA&
// Return: void
struct bta_ag_sco_close {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_close bta_ag_sco_close;

// Name: bta_ag_sco_codec_nego
// Params: tBTA_AG_SCB* p_scb, bool result
// Return: void
struct bta_ag_sco_codec_nego {
  std::function<void(tBTA_AG_SCB* p_scb, bool result)> body{
          [](tBTA_AG_SCB* /* p_scb */, bool /* result */) {}};
  void operator()(tBTA_AG_SCB* p_scb, bool result) { body(p_scb, result); }
};
extern struct bta_ag_sco_codec_nego bta_ag_sco_codec_nego;

// Name: bta_ag_sco_conn_close
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA&
// Return: void
struct bta_ag_sco_conn_close {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_conn_close bta_ag_sco_conn_close;

// Name: bta_ag_sco_conn_open
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA&
// Return: void
struct bta_ag_sco_conn_open {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_conn_open bta_ag_sco_conn_open;

// Name: bta_ag_sco_conn_rsp
// Params: tBTA_AG_SCB* p_scb, tBTM_ESCO_CONN_REQ_EVT_DATA* p_data
// Return: void
struct bta_ag_sco_conn_rsp {
  std::function<void(tBTA_AG_SCB* p_scb, tBTM_ESCO_CONN_REQ_EVT_DATA* p_data)> body{
          [](tBTA_AG_SCB* /* p_scb */, tBTM_ESCO_CONN_REQ_EVT_DATA* /* p_data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, tBTM_ESCO_CONN_REQ_EVT_DATA* p_data) { body(p_scb, p_data); }
};
extern struct bta_ag_sco_conn_rsp bta_ag_sco_conn_rsp;

// Name: bta_ag_sco_is_active_device
// Params: const RawAddress& bd_addr
// Return: bool
struct bta_ag_sco_is_active_device {
  static bool return_value;
  std::function<bool(const RawAddress& bd_addr)> body{
          [](const RawAddress& /* bd_addr */) { return return_value; }};
  bool operator()(const RawAddress& bd_addr) { return body(bd_addr); }
};
extern struct bta_ag_sco_is_active_device bta_ag_sco_is_active_device;

// Name: bta_ag_sco_is_open
// Params: tBTA_AG_SCB* p_scb
// Return: bool
struct bta_ag_sco_is_open {
  static bool return_value;
  std::function<bool(tBTA_AG_SCB* p_scb)> body{
          [](tBTA_AG_SCB* /* p_scb */) { return return_value; }};
  bool operator()(tBTA_AG_SCB* p_scb) { return body(p_scb); }
};
extern struct bta_ag_sco_is_open bta_ag_sco_is_open;

// Name: bta_ag_sco_is_opening
// Params: tBTA_AG_SCB* p_scb
// Return: bool
struct bta_ag_sco_is_opening {
  static bool return_value;
  std::function<bool(tBTA_AG_SCB* p_scb)> body{
          [](tBTA_AG_SCB* /* p_scb */) { return return_value; }};
  bool operator()(tBTA_AG_SCB* p_scb) { return body(p_scb); }
};
extern struct bta_ag_sco_is_opening bta_ag_sco_is_opening;

// Name: bta_ag_sco_listen
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA&
// Return: void
struct bta_ag_sco_listen {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_listen bta_ag_sco_listen;

// Name: bta_ag_sco_open
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data
// Return: void
struct bta_ag_sco_open {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_open bta_ag_sco_open;

// Name: bta_ag_sco_read
// Params: uint8_t* p_buf, uint32_t len
// Return: size_t
struct bta_ag_sco_read {
  static size_t return_value;
  std::function<size_t(uint8_t* p_buf, uint32_t len)> body{
          [](uint8_t* /* p_buf */, uint32_t /* len */) { return return_value; }};
  size_t operator()(uint8_t* p_buf, uint32_t len) { return body(p_buf, len); }
};
extern struct bta_ag_sco_read bta_ag_sco_read;

// Name: bta_ag_sco_shutdown
// Params: tBTA_AG_SCB* p_scb, const tBTA_AG_DATA&
// Return: void
struct bta_ag_sco_shutdown {
  std::function<void(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data)> body{
          [](tBTA_AG_SCB* /* p_scb */, const tBTA_AG_DATA& /* data */) {}};
  void operator()(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) { body(p_scb, data); }
};
extern struct bta_ag_sco_shutdown bta_ag_sco_shutdown;

// Name: bta_ag_sco_write
// Params: const uint8_t* p_buf, uint32_t len
// Return: size_t
struct bta_ag_sco_write {
  static size_t return_value;
  std::function<size_t(const uint8_t* p_buf, uint32_t len)> body{
          [](const uint8_t* /* p_buf */, uint32_t /* len */) { return return_value; }};
  size_t operator()(const uint8_t* p_buf, uint32_t len) { return body(p_buf, len); }
};
extern struct bta_ag_sco_write bta_ag_sco_write;

// Name: bta_ag_set_sco_allowed
// Params: bool value
// Return: void
struct bta_ag_set_sco_allowed {
  std::function<void(bool value)> body{[](bool /* value */) {}};
  void operator()(bool value) { body(value); }
};
extern struct bta_ag_set_sco_allowed bta_ag_set_sco_allowed;

// Name: bta_ag_set_sco_offload_enabled
// Params: bool value
// Return: void
struct bta_ag_set_sco_offload_enabled {
  std::function<void(bool value)> body{[](bool /* value */) {}};
  void operator()(bool value) { body(value); }
};
extern struct bta_ag_set_sco_offload_enabled bta_ag_set_sco_offload_enabled;

// Name: bta_ag_stream_suspended
// Params:
// Return: void
struct bta_ag_stream_suspended {
  std::function<void()> body{[]() {}};
  void operator()() { body(); }
};
extern struct bta_ag_stream_suspended bta_ag_stream_suspended;

// Name: bta_clear_active_device
// Params:
// Return: void
struct bta_clear_active_device {
  std::function<void()> body{[]() {}};
  void operator()() { body(); }
};
extern struct bta_clear_active_device bta_clear_active_device;

}  // namespace bta_ag_sco
}  // namespace mock
}  // namespace test

// END mockcify generation
