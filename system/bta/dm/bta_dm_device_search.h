/*
 * Copyright 2024 The Android Open Source Project
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
#include <bluetooth/types/bt_transport.h>

#include "bta/dm/bta_dm_device_search_int.h"
#include "bta/include/bta_api.h"  // tBTA_DM_SEARCH_CBACK
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/rnr_interface.h"

// Bta module start and stop entry points
void bta_dm_search_stop();

// Bta device discovery start and stop entry points
void bta_dm_disc_start_device_discovery(tBTA_DM_SEARCH_CBACK*);
void bta_dm_disc_stop_device_discovery();

void bta_dm_disc_disable_search();

// LE observe and scan interface
void bta_dm_ble_scan(bool start, uint8_t duration_sec);
void bta_dm_ble_csis_observe(bool observe, tBTA_DM_SEARCH_CBACK* p_cback);

// Checks if there is a device discovery request queued
bool bta_dm_is_search_request_queued();

// Provide data for the dumpsys procedure
void DumpsysBtaDmSearch(int fd);

namespace bluetooth::legacy::testing {

void bta_dm_disc_init_search_cb(tBTA_DM_SEARCH_CB& bta_dm_search_cb);
bool bta_dm_read_remote_device_name(const RawAddress& bd_addr, tBT_TRANSPORT transport);
tBTA_DM_SEARCH_CB& bta_dm_disc_search_cb();
void bta_dm_discover_next_device();
void bta_dm_inq_cmpl();
void bta_dm_inq_cmpl_cb(tBTM_INQUIRY_CMPL* p_result);
void bta_dm_observe_cmpl_cb(tBTM_INQUIRY_CMPL* p_result);
void bta_dm_observe_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir, uint16_t eir_len);
void bta_dm_opportunistic_observe_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir,
                                             uint16_t eir_len);
void bta_dm_queue_search(tBTA_DM_API_SEARCH& search);
void bta_dm_remname_cback(const tBTM_REMOTE_DEV_NAME* p);
void bta_dm_start_scan(uint8_t duration_sec);

}  // namespace bluetooth::legacy::testing
