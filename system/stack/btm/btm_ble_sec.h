/*
 * Copyright 2023 The Android Open Source Project
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
 */

#pragma once

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>

#include <optional>

#include "stack/include/bt_device_type.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"

/* LE security function from btm_sec.cc */
tBTM_BLE_SEC_REQ_ACT btm_ble_link_sec_check(const RawAddress& bd_addr, tBTM_LE_AUTH_REQ auth_req);
void btm_ble_ltk_request_reply(const RawAddress& bda, bool use_stk, const Octet16& stk);
tBTM_STATUS btm_proc_smp_cback(tSMP_EVT event, const RawAddress& bd_addr, tSMP_EVT_DATA* p_data);
tBTM_STATUS btm_ble_set_encryption(const RawAddress& bd_addr, tBTM_BLE_SEC_ACT sec_act,
                                   uint8_t link_role);
tBTM_STATUS btm_ble_start_encrypt(const RawAddress& bda, bool use_stk, Octet16* p_stk);
void btm_ble_link_encrypted(const RawAddress& bd_addr, uint8_t encr_enable);

void btm_ble_reset_id(void);

bool btm_get_local_div(const RawAddress& bd_addr, uint16_t* p_div);
bool btm_ble_get_enc_key_type(const RawAddress& bd_addr, uint8_t* p_key_types);

void btm_sec_save_le_key(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                         const tBTM_LE_KEY_VALUE& key, bool pass_to_application);
void btm_ble_update_sec_key_size(const RawAddress& bd_addr, uint8_t enc_key_size);
uint8_t btm_ble_read_sec_key_size(const RawAddress& bd_addr);

tBTM_STATUS btm_ble_start_sec_check(const RawAddress& bd_addr, uint16_t psm, bool outgoing,
                                    tBTM_SEC_CALLBACK* p_callback, void* p_ref_data);

// Added for API consistency
void btm_ble_load_local_keys(uint8_t key_type, tBTM_BLE_LOCAL_KEYS* p_key);
void btm_sec_add_ble_device(const RawAddress& bd_addr, tBT_DEVICE_TYPE dev_type,
                            tBLE_ADDR_TYPE addr_type);
void btm_sec_add_ble_key(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                         const tBTM_LE_KEY_VALUE& key);
void btm_ble_sirk_confirm_device_reply(const RawAddress& bd_addr, tBTM_STATUS res);
void btm_ble_passkey_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint32_t passkey);
const Octet16& btm_get_device_enc_root();
const Octet16& btm_get_device_id_root();
const Octet16& btm_get_device_dhk();
void btm_security_grant(const RawAddress& bd_addr, tBTM_STATUS res);
void btm_ble_confirm_reply(const RawAddress& bd_addr, tBTM_STATUS res);
void btm_ble_oob_data_reply(const RawAddress& bd_addr, tBTM_STATUS res, uint8_t len,
                            uint8_t* p_data);
void btm_ble_secure_connection_oob_data_reply(const RawAddress& bd_addr, uint8_t* p_c,
                                              uint8_t* p_r);
bool btm_ble_data_signature(const RawAddress& bd_addr, uint8_t* p_text, uint16_t len,
                            BLE_SIGNATURE signature);
bool btm_ble_verify_signature(const RawAddress& bd_addr, uint8_t* p_orig, uint16_t len,
                              uint32_t counter, uint8_t* p_comp);
std::optional<Octet16> btm_ble_get_peer_ltk(const RawAddress address);
std::optional<Octet16> btm_ble_get_peer_irk(const RawAddress address);
std::optional<tBLE_BD_ADDR> btm_ble_get_identity_address(const RawAddress address);
