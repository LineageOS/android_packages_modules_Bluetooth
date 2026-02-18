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
/*
 * Generated mock file from original source file
 *   Functions generated:26
 *
 *  mockcify.pl ver 0.5.0
 */
// Mock include file to share data between tests and mock
#include "stack/mock/mock_stack_le_connection.h"

#include <cstdint>
#include <string>

#include "test/common/mock_functions.h"

// Original usings
using bluetooth::Uuid;

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_le_connection {

// Function state capture and return values, if needed
struct GATT_UpdateSubrateConfig GATT_UpdateSubrateConfig;
struct GATT_SubrateRequest GATT_SubrateRequest;
struct GATT_CancelConnect GATT_CancelConnect;
struct GATT_LE_Connect GATT_LE_Connect;
}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_le_connection {

bool GATT_SubrateRequest::return_value = false;
bool GATT_CancelConnect::return_value = false;
bool GATT_LE_Connect::return_value = false;

}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void GATT_UpdateSubrateConfig(tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max,
                              uint16_t subrate_min, uint16_t cont_num) {
  inc_func_call_count(__func__);
  test::mock::stack_le_connection::GATT_UpdateSubrateConfig(subrate_mode, subrate_max, subrate_min,
                                                            cont_num);
}
bool GATT_SubrateRequest(tGATT_IF gatt_if, const RawAddress& bd_addr,
                         tGATT_SUBRATE_MODE subrate_mode) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::GATT_SubrateRequest(gatt_if, bd_addr, subrate_mode);
}
bool GATT_CancelConnect(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::GATT_CancelConnect(gatt_if, bd_addr, is_direct);
}
bool GATT_LE_Connect(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                     tBTM_BLE_CONN_TYPE connection_type, bool opportunistic, uint16_t preferred_mtu,
                     bool prefer_relax_mode, bool auto_mtu_enabled) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::GATT_LE_Connect(
          gatt_if, bd_addr, addr_type, connection_type, opportunistic, preferred_mtu,
          prefer_relax_mode, auto_mtu_enabled);
}
bool GATT_LE_Connect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                     tBTM_BLE_CONN_TYPE connection_type, bool opportunistic) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::GATT_LE_Connect(gatt_if, bd_addr, 0, connection_type,
                                                          opportunistic, 0, false, false);
}
// END mockcify generation
