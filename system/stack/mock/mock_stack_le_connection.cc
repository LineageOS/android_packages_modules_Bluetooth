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

#include "base/functional/bind.h"
#include "test/common/mock_functions.h"

// Original usings
using bluetooth::Uuid;

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_le_connection {

// Function state capture and return values, if needed
struct leConnectionUpdateSubrateConfig leConnectionUpdateSubrateConfig;
struct leConnectionSubrateModeRequest leConnectionSubrateModeRequest;
struct leConnectionCancelConnect leConnectionCancelConnect;
struct leConnectionConnect leConnectionConnect;
struct leConnectionSubrateRequest leConnectionSubrateRequest;
struct leConnectionUpdate leConnectionUpdate;
struct leConnectionSetPhy leConnectionSetPhy;
struct leConnectionReadPhy leConnectionReadPhy;
}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_le_connection {

bool leConnectionSubrateModeRequest::return_value = false;
bool leConnectionCancelConnect::return_value = false;
bool leConnectionConnect::return_value = false;

}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

namespace bluetooth::stack {
// Mocked functions, if any
tGATT_STATUS leConnectionUpdateSubrateConfig(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                             tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max,
                                             uint16_t subrate_min, uint16_t cont_num) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionUpdateSubrateConfig(
          gatt_if, bd_addr, subrate_mode, subrate_max, subrate_min, cont_num);
}
bool leConnectionSubrateModeRequest(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                    tGATT_SUBRATE_MODE subrate_mode) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionSubrateModeRequest(gatt_if, bd_addr,
                                                                         subrate_mode);
}
bool leConnectionCancelConnect(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionCancelConnect(gatt_if, bd_addr, is_direct);
}
bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBTM_BLE_CONN_TYPE connection_type, uint16_t preferred_mtu,
                         bool prefer_relax_mode, bool auto_mtu_enabled) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionConnect(gatt_if, bd_addr, addr_type,
                                                              connection_type, preferred_mtu,
                                                              prefer_relax_mode, auto_mtu_enabled);
}
bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                         tBTM_BLE_CONN_TYPE connection_type) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionConnect(gatt_if, bd_addr, 0, connection_type,
                                                              0, false, false);
}

void leConnectionSubrateRequest(const RawAddress& bd_addr, uint16_t subrate_min,
                                uint16_t subrate_max, uint16_t max_latency, uint16_t cont_num,
                                uint16_t timeout) {
  inc_func_call_count(__func__);
  return test::mock::stack_le_connection::leConnectionSubrateRequest(
          bd_addr, subrate_min, subrate_max, max_latency, cont_num, timeout);
}

void leConnectionUpdate(const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
                        uint16_t latency, uint16_t timeout, uint16_t min_ce_len,
                        uint16_t max_ce_len) {
  inc_func_call_count(__func__);
  test::mock::stack_le_connection::leConnectionUpdate(bd_addr, min_interval, max_interval, latency,
                                                      timeout, min_ce_len, max_ce_len);
}

void leConnectionSetPhy(const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys,
                        uint16_t phy_options) {
  inc_func_call_count(__func__);
  test::mock::stack_le_connection::leConnectionSetPhy(bd_addr, tx_phys, rx_phys, phy_options);
}
void leConnectionReadPhy(
        const RawAddress& bd_addr,
        base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb) {
  inc_func_call_count(__func__);
  test::mock::stack_le_connection::leConnectionReadPhy(bd_addr, std::move(cb));
}
}  // namespace bluetooth::stack

// END mockcify generation
