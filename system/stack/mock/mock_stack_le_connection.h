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
#include <base/functional/callback.h>

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

// Name: leConnectionUpdateSubrateConfig
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr,
//         GATT_SUBRATE_MODE subrate_mode uint16_t subrate_max,
//         uint16_t subrate_min, uint16_t cont_num
// Return: tGATT_STATUS
struct leConnectionUpdateSubrateConfig {
  std::function<tGATT_STATUS(tGATT_IF gatt_if, const RawAddress& bd_addr,
                             tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max,
                             uint16_t subrate_min, uint16_t cont_num)>
          body{[](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */,
                  tGATT_SUBRATE_MODE /*subrate_mode*/, uint16_t /*subrate_max*/,
                  uint16_t /*subrate_min*/, uint16_t /*cont_num*/) { return GATT_SUCCESS; }};
  tGATT_STATUS operator()(tGATT_IF gatt_if, const RawAddress& bd_addr,
                          tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max,
                          uint16_t subrate_min, uint16_t cont_num) {
    return body(gatt_if, bd_addr, subrate_mode, subrate_max, subrate_min, cont_num);
  }
};
extern struct leConnectionUpdateSubrateConfig leConnectionUpdateSubrateConfig;

// Name: leConnectionSubrateModeRequest
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode
// Return: bool
struct leConnectionSubrateModeRequest {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode)>
          body{[](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */,
                  tGATT_SUBRATE_MODE /* subrate_mode */) { return return_value; }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, tGATT_SUBRATE_MODE subrate_mode) {
    return body(gatt_if, bd_addr, subrate_mode);
  }
};
extern struct leConnectionSubrateModeRequest leConnectionSubrateModeRequest;

// Name: leConnectionCancelConnect
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct
// Return: bool
struct leConnectionCancelConnect {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct)> body{
          [](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */, bool /* is_direct */) {
            return return_value;
          }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct) {
    return body(gatt_if, bd_addr, is_direct);
  }
};
extern struct leConnectionCancelConnect leConnectionCancelConnect;

// Name: leConnectionConnect
// Params: tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct,
// uint16_t preferred_mtu, bool prefer_relax_mode,
// bool auto_mtu_enabled Return: bool
struct leConnectionConnect {
  static bool return_value;
  std::function<bool(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                     tBTM_BLE_CONN_TYPE connection_type, uint16_t preferred_mtu,
                     bool prefer_relax_mode, bool auto_mtu_enabled)>
          body{[](tGATT_IF /* gatt_if */, const RawAddress& /* bd_addr */,
                  tBLE_ADDR_TYPE /* addr_type */, tBTM_BLE_CONN_TYPE /* connection_type */,
                  uint16_t /* preferred_mtu */, bool /* prefer_relax_mode */,
                  bool /* auto_mtu_enabled */) { return return_value; }};
  bool operator()(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                  tBTM_BLE_CONN_TYPE connection_type, uint16_t preferred_mtu,
                  bool prefer_relax_mode, bool auto_mtu_enabled) {
    return body(gatt_if, bd_addr, addr_type, connection_type, preferred_mtu, prefer_relax_mode,
                auto_mtu_enabled);
  }
};
extern struct leConnectionConnect leConnectionConnect;

struct leConnectionSubrateRequest {
  std::function<void(const RawAddress& bd_addr, uint16_t subrate_min, uint16_t subrate_max,
                     uint16_t max_latency, uint16_t cont_num, uint16_t timeout)>
          body{[](const RawAddress& /* bd_addr */, uint16_t /* subrate_min */,
                  uint16_t /* subrate_max */, uint16_t /* max_latency */, uint16_t /* cont_num */,
                  uint16_t /* timeout */) {}};
  void operator()(const RawAddress& bd_addr, uint16_t subrate_min, uint16_t subrate_max,
                  uint16_t max_latency, uint16_t cont_num, uint16_t timeout) {
    body(bd_addr, subrate_min, subrate_max, max_latency, cont_num, timeout);
  }
};
extern struct leConnectionSubrateRequest leConnectionSubrateRequest;

// Name: leConnectionUpdate
// Params: const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
//         uint16_t latency, uint16_t timeout, uint16_t min_ce_len, uint16_t max_ce_len
// Return: void
struct leConnectionUpdate {
  std::function<void(const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
                     uint16_t latency, uint16_t timeout, uint16_t min_ce_len, uint16_t max_ce_len)>
          body{[](const RawAddress& /* bd_addr */, uint16_t /* min_interval */,
                  uint16_t /* max_interval */, uint16_t /* latency */, uint16_t /* timeout */,
                  uint16_t /* min_ce_len */, uint16_t /* max_ce_len */) {}};
  void operator()(const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
                  uint16_t latency, uint16_t timeout, uint16_t min_ce_len, uint16_t max_ce_len) {
    body(bd_addr, min_interval, max_interval, latency, timeout, min_ce_len, max_ce_len);
  }
};
extern struct leConnectionUpdate leConnectionUpdate;

// Name: leConnectionSetPhy
// Params: const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys, uint16_t phy_options
// Return: void
struct leConnectionSetPhy {
  std::function<void(const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys,
                     uint16_t phy_options)>
          body{[](const RawAddress& /* bd_addr */, uint8_t /* tx_phys */, uint8_t /* rx_phys */,
                  uint16_t /* phy_options */) {}};
  void operator()(const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys,
                  uint16_t phy_options) {
    body(bd_addr, tx_phys, rx_phys, phy_options);
  }
};
extern struct leConnectionSetPhy leConnectionSetPhy;

// Name: leConnectionReadPhy
// Params: const RawAddress& bd_addr, base::OnceCallback<void(uint8_t tx_phy,
// uint8_t rx_phy, uint8_t status Return: void
struct leConnectionReadPhy {
  std::function<void(
          const RawAddress& bd_addr,
          base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> callback)>
          body{[](const RawAddress& /* bd_addr */,
                  base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)>
                  /* callback */) {}};
  void operator()(
          const RawAddress& bd_addr,
          base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> callback) {
    body(bd_addr, std::move(callback));
  }
};
extern struct leConnectionReadPhy leConnectionReadPhy;
}  // namespace stack_le_connection
}  // namespace mock
}  // namespace test

// END mockcify generation
