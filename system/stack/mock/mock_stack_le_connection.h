/*
 * Copyright 2026 The Android Open Source Project
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
 *   Functions generated:26
 *
 *  mockcify.pl ver 0.5.0
 */

#include <cstdint>
#include <functional>
#include <string>

// Original included files, if any

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>

#include "stack/include/stack_le_connection.h"

// Original usings
using bluetooth::Uuid;

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_le_connection {

// Name: GATT_UpdateSubrateConfig
// Params: GATT_SUBRATE_MODE subrate_mode uint16_t subrate_max,
//         uint16_t subrate_min, uint16_t cont_num
// Return: void
struct GATT_UpdateSubrateConfig {
  std::function<void(tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max, uint16_t subrate_min,
                     uint16_t cont_num)>
          body{[](tGATT_SUBRATE_MODE /*subrate_mode*/, uint16_t /*subrate_max*/,
                  uint16_t /*subrate_min*/, uint16_t /*cont_num*/) {}};
  void operator()(tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max, uint16_t subrate_min,
                  uint16_t cont_num) {
    body(subrate_mode, subrate_max, subrate_min, cont_num);
  }
};
extern struct GATT_UpdateSubrateConfig GATT_UpdateSubrateConfig;

// Name: GATT_SubrateRequest
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode
// Return: bool
struct GATT_SubrateRequest {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode)>
          body{[](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */,
                  tGATT_SUBRATE_MODE /* subrate_mode */) { return return_value; }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode) {
    return body(gatt_if, bd_addr, subrate_mode);
  }
};
extern struct GATT_SubrateRequest GATT_SubrateRequest;

// Name: GATT_CancelConnect
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct
// Return: bool
struct GATT_CancelConnect {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct)> body{
          [](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */, bool /* is_direct */) {
            return return_value;
          }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct) {
    return body(gatt_if, bd_addr, is_direct);
  }
};
extern struct GATT_CancelConnect GATT_CancelConnect;

// Name: GATT_LE_Connect
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct,
// bool opportunistic, uint16_t preferred_mtu, bool prefer_relax_mode,
// bool auto_mtu_enabled Return: bool
struct GATT_LE_Connect {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                     tBTM_BLE_CONN_TYPE connection_type, bool opportunistic, uint16_t preferred_mtu,
                     bool prefer_relax_mode, bool auto_mtu_enabled)>
          body{[](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */,
                  tBLE_ADDR_TYPE /* addr_type */, tBTM_BLE_CONN_TYPE /* connection_type */,
                  bool /* opportunistic */, uint16_t /* preferred_mtu */,
                  bool /* prefer_relax_mode */,
                  bool /* auto_mtu_enabled */) { return return_value; }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                  tBTM_BLE_CONN_TYPE connection_type, bool opportunistic, uint16_t preferred_mtu,
                  bool prefer_relax_mode, bool auto_mtu_enabled) {
    return body(gatt_if, bd_addr, addr_type, connection_type, opportunistic, preferred_mtu,
                prefer_relax_mode, auto_mtu_enabled);
  }
};
extern struct GATT_LE_Connect GATT_LE_Connect;
}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

// END mockcify generation
