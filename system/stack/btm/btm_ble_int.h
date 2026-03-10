/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  this file contains the main Bluetooth Manager (BTM) internal
 *  definitions.
 *
 ******************************************************************************/

#pragma once

#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>

#include "stack/btm/btm_device_record.h"

void btm_send_hci_set_scan_params(uint8_t scan_type, uint16_t scan_int_1m, uint16_t scan_win_1m,
                                  uint16_t scan_int_coded, uint16_t scan_win_coded,
                                  uint8_t scan_phy, tBLE_ADDR_TYPE addr_type_own,
                                  uint8_t scan_filter_policy);

void btm_ble_init(void);
void btm_ble_free();
void btm_ble_connected(const RawAddress& bda, uint16_t handle, uint8_t enc_mode, uint8_t role,
                       tBLE_ADDR_TYPE addr_type, bool can_read_discoverable_characteristics);
void btm_ble_connection_established(const RawAddress& bda);

/* BLE address management */
BtmDevice* btm_ble_resolve_random_addr(const RawAddress& random_bda);

void btm_ble_batchscan_init(void);
void btm_ble_adv_filter_init(void);
tBTM_STATUS btm_ble_start_inquiry(uint8_t duration);
void btm_ble_stop_inquiry(void);
