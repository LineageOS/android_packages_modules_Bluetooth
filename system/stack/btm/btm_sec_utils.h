/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <bluetooth/types/address.h>
#include <bluetooth/types/remote_version.h>

#include "stack/btm/btm_device_record.h"
#include "stack/btm/btm_sec_int_types.h"

bool concurrentPeerAuthIsEnabled();
bool handleUnexpectedEncryptionChange();
void btm_sec_store_device_sc_support(uint16_t hci_handle, bool host_sc_supported,
                                     bool controller_sc_supported);
bool btm_sec_is_enc_algo_downgrade(uint16_t hci_handle, bool host_sc_supported,
                                   bool controller_sc_supported);
bool btm_sec_is_session_key_size_downgrade(uint16_t hci_handle, uint8_t key_size);
void btm_sec_update_session_key_size(uint16_t hci_handle, uint8_t key_size);

bool btm_dev_authenticated(const BtmDevice* p_device);
bool btm_dev_encrypted(const BtmDevice* p_device);
bool btm_dev_16_digit_authenticated(const BtmDevice* p_device);

bool access_secure_service_from_temp_bond(const BtmDevice* p_device, bool locally_initiated,
                                          uint16_t security_req);

bool BTM_CanReadDiscoverableCharacteristics(const RawAddress& bd_addr);

// Return DEV_CLASS of bda. If record doesn't exist, create one.
DEV_CLASS btm_get_dev_class(const RawAddress& bda);

void BTM_update_version_info(const RawAddress& bd_addr,
                             const remote_version_info& remote_version_info);

const char* btm_pair_state_descr(tBTM_PAIRING_STATE state);

bool is_autonomous_repairing_supported();
void set_autonomous_repairing_supported(bool platform_support_autonomous_repairing_initiation);
