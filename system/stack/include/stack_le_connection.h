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

#include <base/functional/callback_forward.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>

#include "stack/include/btm_ble_api_types.h"
#include "stack/include/gatt_api.h"  // tGATT_SUBRATE_MODE

namespace bluetooth::stack {

/*******************************************************************************
 *
 * Function         leConnectionConnect
 *
 * Description      This function initiate a connection to a remote device on
 *                  GATT channel.
 *
 * Parameters       gatt_if: application interface
 *                  bd_addr: peer device address
 *                  addr_type: peer device address type
 *                  connection_type: connection type
 *                  transport : Physical transport for GATT connection
 *                              (BR/EDR or LE)
 *
 * Returns          true if connection started; else false
 *
 ******************************************************************************/
[[nodiscard]] bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                       tBLE_ADDR_TYPE addr_type, tBTM_BLE_CONN_TYPE connection_type,
                                       uint16_t preferred_mtu, bool prefer_relax_mode,
                                       bool auto_mtu_enabled);

[[nodiscard]] bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                       tBTM_BLE_CONN_TYPE connection_type);

/*******************************************************************************
 *
 * Function         leConnectionCancelConnect
 *
 * Description      Terminate the connection initiation to a remote device on a
 *                  GATT channel.
 *
 * Parameters       gatt_if: client interface. If 0 used as unconditionally
 *                           disconnect, typically used for direct connection
 *                           cancellation.
 *                  bd_addr: peer device address.
 *                  is_direct: is a direct connection or a background auto
 *                             connection
 *
 * Returns          true if connection started; else false
 *
 ******************************************************************************/
[[nodiscard]] bool leConnectionCancelConnect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                             bool is_direct);

/*******************************************************************************
 * Function         leConnectionSubrateModeRequest
 *
 * Description      Configure subrate config for each client_if
 *
 * Parameters       gatt_if: application interface
 *                  bd_addr: peer device address
 *                  subrate_mode: subrate_mode
 *
 * Returns          true if config successfully.
 *
 ******************************************************************************/
bool leConnectionSubrateModeRequest(tGATT_IF client_if, const RawAddress& bd_addr,
                                    tGATT_SUBRATE_MODE subrate_mode);

/*******************************************************************************
 * Function         leConnectionUpdateSubrateConfig
 *
 * Description      Update fixed subrate parameters of subrate mode in config.
 *
 * Parameters       subrate_mode: subrate_mode
 *                  Subrate parameters
 *
 ******************************************************************************/
tGATT_STATUS leConnectionUpdateSubrateConfig(tGATT_IF gatt_if, const RawAddress& bd_addr,
                                             tGATT_SUBRATE_MODE subrate_mode,
                                             uint16_t subrate_max = 0, uint16_t subrate_min = 0,
                                             uint16_t cont_num = 0);

/*******************************************************************************
 *
 * Function         leConnectionSubrateRequest
 *
 * Description      subrate request, can only be used when connection is up.
 *
 * Parameters:      bd_addr       - BD address of the peer
 *                  subrate_min   - subrate min
 *                  subrate_max   - subrate max
 *                  max_latency   - max latency
 *                  cont_num      - continuation number
 *                  timeout       - supervision timeout
 *
 * Returns          void
 *
 ******************************************************************************/
void leConnectionSubrateRequest(const RawAddress& bd_addr, uint16_t subrate_min,
                                uint16_t subrate_max, uint16_t max_latency, uint16_t cont_num,
                                uint16_t timeout);

/*******************************************************************************
 *
 * Function         leConnectionUpdate
 *
 * Description      Update connection parameters.
 *
 * Parameters:      bd_addr       - BD address of the peer
 *                  min_interval  - minimum connection interval
 *                  max_interval  - maximum connection interval
 *                  latency       - peripheral latency
 *                  timeout       - supervision timeout
 *                  min_ce_len    - minimum connection event length
 *                  max_ce_len    - maximum connection event length
 *
 * Returns          void
 *
 ******************************************************************************/
void leConnectionUpdate(const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
                        uint16_t latency, uint16_t timeout, uint16_t min_ce_len,
                        uint16_t max_ce_len);

/**
 * To set PHY preferences for specified LE connection
 *
 * @param bd_addr remote device address
 * @param tx_phys preferred transmit PHYs
 * @param rx_phys preferred receive PHYs
 * @param phy_options PHY options
 */
void leConnectionSetPhy(const RawAddress& bd_addr, uint8_t tx_phys, uint8_t rx_phys,
                        uint16_t phy_options);

/**
 * To read the current PHYs for specified LE connection
 */
void leConnectionReadPhy(
        const RawAddress& bd_addr,
        base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb);

}  // namespace bluetooth::stack
