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

#include "bta_jv_api.h"
#include "types/raw_address.h"

void bta_collect_rfc_metrics_after_sdp_fail(tBTA_JV_STATUS sdp_status, RawAddress addr, int app_uid,
                                            int security, bool is_server, uint64_t sdp_duration);

void bta_collect_rfc_metrics_after_port_fail(tPORT_RESULT port_result, bool sdp_initiated,
                                             tBTA_JV_STATUS sdp_status, RawAddress addr,
                                             int app_uid, int security, bool is_server,
                                             uint64_t sdp_duration_ms);
