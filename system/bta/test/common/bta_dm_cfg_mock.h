/*
 * Copyright 2025 The Android Open Source Project
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

#include "bta/dm/bta_dm_int.h"
#include "bta/sys/bta_sys.h"

extern tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_CFG mock_bta_dm_pm_cfg[];

extern tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_SPEC* mock_get_bta_dm_pm_spec();

extern tBTA_DM_PM_TYPE_QUALIFIER tBTM_PM_PWR_MD mock_bta_dm_pm_md[];

extern tBTA_DM_SSR_SPEC mock_ssr_spec[];

// Define profile specific APP_IDs
#define BTA_HH_APP_ID_JOY 1
#define BTA_HH_APP_ID_GPAD 2
#define BTA_JV_PM_ID_1 3
#define BTUI_PAN_ID_PANU 4
#define BTUI_PAN_ID_NAP 5

#define BTA_DM_NUM_PM_ENTRY 25 /* number of entries in bta_dm_pm_cfg except the first */
#define BTA_DM_NUM_PM_SPEC 16  /* number of entries in bta_dm_pm_spec */
#define BTA_DM_PM_MD_SIZE 7    /* number of entries in mock_bta_dm_pm_md */
