/*
 * Copyright 2023 The Android Open Source Project
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

#include "bta/include/bta_api.h"  // tBTA_DM_SEARCH_CBACK
#include "stack/include/bt_hdr.h"

// Bta module start and stop entry points
void bta_dm_disc_start(bool delay_close_gatt);
void bta_dm_disc_stop();

// Bta device discovery start and stop entry points
void bta_dm_disc_start_device_discovery(tBTA_DM_SEARCH_CBACK*);
void bta_dm_disc_stop_device_discovery();

// Bta service discovery start and stop entry points
void bta_dm_disc_start_service_discovery(service_discovery_callbacks cbacks,
                                         const RawAddress& bd_addr, tBT_TRANSPORT transport);

// Bta subsystem entrypoint and lifecycle
void bta_dm_disc_disable_disc();

// LE observe and scan interface
void bta_dm_ble_scan(bool start, uint8_t duration_sec);
void bta_dm_ble_csis_observe(bool observe, tBTA_DM_SEARCH_CBACK* p_cback);

// Checks if there is a device discovery request queued
bool bta_dm_is_search_request_queued();

// GATT service discovery
void bta_dm_disc_gattc_register();
void bta_dm_disc_gatt_cancel_open(const RawAddress& bd_addr);
void bta_dm_disc_gatt_refresh(const RawAddress& bd_addr);

// Stop service discovery procedure, if any, for removed device
void bta_dm_disc_remove_device(const RawAddress& bd_addr);

// Provide data for the dumpsys procedure
void DumpsysBtaDmDisc(int fd);
