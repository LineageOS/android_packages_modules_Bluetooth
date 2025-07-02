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

#include <bluetooth/types/address.h>

#include <cstdint>

#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/bt_name.h"
#include "stack/include/hci_error_code.h"

void btm_process_inq_complete(tHCI_STATUS status, uint8_t mode);

void btm_acl_process_sca_cmpl_pkt(uint8_t len, uint8_t* data);
tINQ_DB_ENT* btm_inq_db_new(const RawAddress& p_bda, bool is_ble);
void btm_inq_db_set_inq_by_rssi(void);
