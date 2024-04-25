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

#include "bta/include/bta_api.h"  // tBTA_DM_SEARCH_CBACK
#include "stack/include/bt_hdr.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

// Bta module start and stop entry points
void bta_dm_search_stop();

// Bta device discovery start and stop entry points
void bta_dm_disc_start_device_discovery(tBTA_DM_SEARCH_CBACK*);
void bta_dm_disc_stop_device_discovery();

void bta_dm_disc_disable_search();

// LE observe and scan interface
void bta_dm_ble_scan(bool start, uint8_t duration_sec, bool low_latency_scan);
void bta_dm_ble_csis_observe(bool observe, tBTA_DM_SEARCH_CBACK* p_cback);

// Checks if there is a device discovery request queued
bool bta_dm_is_search_request_queued();

// Provide data for the dumpsys procedure
void DumpsysBtaDmSearch(int fd);