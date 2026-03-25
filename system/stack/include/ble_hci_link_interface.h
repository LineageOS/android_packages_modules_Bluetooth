/*
 *  Copyright 2020 The Android Open Source Project
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
 */

#pragma once

#include <cstdint>
#include <vector>

#include "hci/hci_packets.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/rnr_interface.h"

// This header contains functions for HCI-ble to invoke
void btm_ble_read_remote_features_complete(uint8_t* p, uint8_t length);
void btm_ble_ltk_request(uint16_t handle, Octet8 rand, uint16_t ediv);
void btm_ble_test_command_complete(bluetooth::hci::CommandCompleteView view);
void btm_ble_read_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view);
void btm_ble_remove_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view);
void btm_ble_add_resolving_list_entry_complete(bluetooth::hci::CommandCompleteView view);
void btm_ble_clear_resolving_list_complete(bluetooth::hci::CommandCompleteView view);
tBTM_STATUS btm_ble_read_remote_cod(const RawAddress& remote_bda);
tBTM_STATUS btm_ble_read_remote_name(const RawAddress& remote_bda, tBTM_NAME_CMPL_CB* p_cb);
bool btm_ble_cancel_remote_name(const RawAddress& remote_bda);
void btm_ble_decrement_link_topology_mask(uint8_t link_role);
void btm_ble_increment_link_topology_mask(uint8_t link_role);
DEV_CLASS btm_ble_get_appearance_as_cod(std::vector<uint8_t> const& data);
void btm_ble_process_adv_addr(RawAddress& raw_address, tBLE_ADDR_TYPE* address_type);
void btm_ble_process_adv_pkt_cont_for_inquiry(uint16_t event_type, tBLE_ADDR_TYPE address_type,
                                              const RawAddress& raw_address, uint8_t primary_phy,
                                              uint8_t secondary_phy, uint8_t advertising_sid,
                                              int8_t tx_power, int8_t rssi,
                                              uint16_t periodic_adv_int,
                                              std::vector<uint8_t> advertising_data);
void btm_ble_process_adv_pkt_cont(uint16_t evt_type, tBLE_ADDR_TYPE addr_type,
                                  const RawAddress& bda, uint8_t primary_phy, uint8_t secondary_phy,
                                  uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                                  uint16_t periodic_adv_int, uint8_t data_len, const uint8_t* data,
                                  const RawAddress& original_bda);
