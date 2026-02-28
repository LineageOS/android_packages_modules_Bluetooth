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
#include "bta_dm_cfg_mock.h"

#include <iterator>

#include "bta/dm/bta_dm_int.h"
#include "bta/sys/bta_sys.h"

// ------------------------------------ Mock of the bta_dm_cfg.cc -------------------------------
// This shall be used for test cases, where we want the implementation under test to be tested
// on a replica of the actual sniff configuration table.

tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_CFG mock_bta_dm_pm_cfg[BTA_DM_NUM_PM_ENTRY + 1] = {
        {BTA_ID_SYS, BTA_DM_NUM_PM_ENTRY, 0}, /* reserved: specifies length of this table. */
        {BTA_ID_AG, BTA_ALL_APP_ID, 0},       /* ag uses first spec table for app id 0 */
        {BTA_ID_CT, 1, 1},                    /* ct (BTA_ID_CT,APP ID=1) spec table */
        {BTA_ID_CG, BTA_ALL_APP_ID, 1},       /* cg reuse ct spec table */
        {BTA_ID_DG, BTA_ALL_APP_ID, 2},       /* dg spec table */
        {BTA_ID_AV, BTA_ALL_APP_ID, 4},       /* av spec table */
        {BTA_ID_FTC, BTA_ALL_APP_ID, 7},      /* ftc spec table */
        {BTA_ID_FTS, BTA_ALL_APP_ID, 8},      /* fts spec table */
        {BTA_ID_HD, BTA_ALL_APP_ID, 3},       /* hd spec table */
        {BTA_ID_HH, BTA_HH_APP_ID_JOY, 5},    /* app BTA_HH_APP_ID_JOY,
                                                 similar to hh spec table */
        {BTA_ID_HH, BTA_HH_APP_ID_GPAD, 5},   /* app BTA_HH_APP_ID_GPAD,
                                                 similar to hh spec table */
        {BTA_ID_HH, BTA_ALL_APP_ID, 6},       /* hh spec table */
        {BTA_ID_PBC, BTA_ALL_APP_ID, 2},      /* reuse dg spec table */
        {BTA_ID_PBS, BTA_ALL_APP_ID, 8},      /* reuse fts spec table */
        {BTA_ID_OPC, BTA_ALL_APP_ID, 7},      /* reuse ftc spec table */
        {BTA_ID_OPS, BTA_ALL_APP_ID, 8},      /* reuse fts spec table */
        {BTA_ID_MSE, BTA_ALL_APP_ID, 8},      /* reuse fts spec table */
        {BTA_ID_JV, BTA_JV_PM_ID_1, 7},       /* app BTA_JV_PM_ID_1, reuse ftc spec table */
        {BTA_ID_JV, BTA_ALL_APP_ID, 8},       /* reuse fts spec table */
        {BTA_ID_HL, BTA_ALL_APP_ID, 9},       /* reuse fts spec table */
        {BTA_ID_PAN, BTUI_PAN_ID_PANU, 10},   /* PANU spec table */
        {BTA_ID_PAN, BTUI_PAN_ID_NAP, 11},    /* NAP spec table */
        {BTA_ID_HS, BTA_ALL_APP_ID, 12},      /* HS spec table */
        {BTA_ID_GATTC, BTA_ALL_APP_ID, 14},   /* gattc spec table */
        {BTA_ID_GATTS, BTA_ALL_APP_ID, 15}    /* gatts spec table */
};

tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_SPEC* mock_get_bta_dm_pm_spec() {
  static tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_SPEC bta_dm_pm_spec[BTA_DM_NUM_PM_SPEC] = {
          /* AG : 0 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 7000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open sniff  */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_SNIFF_SCO_OPEN_IDX, 7000},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* sco open, active */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 7000},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* sco close sniff  */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 7000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},            /* busy */
                   {{BTA_DM_PM_RETRY, 7000}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* CT, CG : 1 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* conn open */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 5000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* sco open sniff */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* busy */
                   {{BTA_DM_PM_RETRY, 5000}, {BTA_DM_PM_NO_ACTION, 0}}   /* mode change retry */
           }},

          /* DG, PBC : 2 */
          {(BTA_DM_PM_ACTIVE), /* no power saving mode allowed */
           BTA_DM_PM_SSR2,     /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF, 5000}, {BTA_DM_PM_NO_ACTION, 0}},  /* conn open active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF, 1000}, {BTA_DM_PM_NO_ACTION, 0}},  /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}  /* mode change retry */
           }},

          /* HD : 3 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR3,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_HD_ACTIVE_IDX, 5000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open sniff */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close */
                   {{BTA_DM_PM_SNIFF_HD_IDLE_IDX, 5000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_SNIFF_HD_ACTIVE_IDX, 0}, {BTA_DM_PM_NO_ACTION, 0}},  /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* AV : 4 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 7000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open  sniff */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 7000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},            /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* HH for joysticks and gamepad : 5 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR1,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF6, BTA_DM_PM_HH_OPEN_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open  sniff */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* sco close, used for HH suspend */
                   {{BTA_DM_PM_SNIFF6, BTA_DM_PM_HH_IDLE_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_SNIFF6, BTA_DM_PM_HH_ACTIVE_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}},                          /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* HH : 6 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR1,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_HH_OPEN_IDX, BTA_DM_PM_HH_OPEN_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open  sniff */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* sco close, used for HH suspend */
                   {{BTA_DM_PM_SNIFF_HH_IDLE_IDX, BTA_DM_PM_HH_IDLE_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_SNIFF_HH_ACTIVE_IDX, BTA_DM_PM_HH_ACTIVE_DELAY},
                    {BTA_DM_PM_NO_ACTION, 0}},                          /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* FTC, OPC, JV : 7 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, BTA_FTC_IDLE_TO_SNIFF_DELAY_MS},
                    {BTA_DM_PM_NO_ACTION, 0}},                          /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* FTS, PBS, OPS, MSE, BTA_JV_PM_ID_1 : 8 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, BTA_FTS_OPS_IDLE_TO_SNIFF_DELAY_MS},
                    {BTA_DM_PM_NO_ACTION, 0}},                          /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* HL : 9 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 5000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open sniff  */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open, active */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close sniff  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}  /* mode change retry */
           }},

          /* PANU : 10 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 5000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},            /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* NAP : 11 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 5000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},            /* busy */

                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* HS : 12 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF, 7000}, {BTA_DM_PM_NO_ACTION, 0}},  /* conn open sniff  */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_SNIFF3, 7000}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open, active */
                   {{BTA_DM_PM_SNIFF, 7000}, {BTA_DM_PM_NO_ACTION, 0}},  /* sco close sniff  */
                   {{BTA_DM_PM_SNIFF, 7000}, {BTA_DM_PM_NO_ACTION, 0}},  /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* busy */
                   {{BTA_DM_PM_RETRY, 7000}, {BTA_DM_PM_NO_ACTION, 0}}   /* mode change retry */
           }},

          /* AVK : 13 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF, 3000}, {BTA_DM_PM_NO_ACTION, 0}},  /* conn open  sniff */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF4, 3000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* busy */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}  /* mode change retry */
           }},

          /* GATTC : 14 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 10000},
                    {BTA_DM_PM_NO_ACTION, 0}},                           /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}},   /* conn close  */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},    /* app open */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_ACTION, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close   */
                   {{BTA_DM_PM_SNIFF_A2DP_IDX, 10000}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_ACTIVE, 0}, {BTA_DM_PM_NO_ACTION, 0}},             /* busy */
                   {{BTA_DM_PM_RETRY, 5000}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},

          /* GATTS : 15 */
          {(BTA_DM_PM_SNIFF), /* allow sniff */
           BTA_DM_PM_SSR2,    /* the SSR entry */
           {
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* conn open  active */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* conn close  */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app open */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* app close */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco open  */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* sco close */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* idle */
                   {{BTA_DM_PM_NO_PREF, 0}, {BTA_DM_PM_NO_ACTION, 0}}, /* busy */
                   {{BTA_DM_PM_RETRY, 5000}, {BTA_DM_PM_NO_ACTION, 0}} /* mode change retry */
           }},
  };
  return bta_dm_pm_spec;
}

tBTA_DM_PM_TYPE_QUALIFIER tBTM_PM_PWR_MD mock_bta_dm_pm_md[BTA_DM_PM_MD_SIZE] = {
        /* sniff modes: max interval, min interval, attempt, timeout */
        {BTA_DM_PM_SNIFF_MAX, BTA_DM_PM_SNIFF_MIN, BTA_DM_PM_SNIFF_ATTEMPT, BTA_DM_PM_SNIFF_TIMEOUT,
         BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF - A2DP */
        {BTA_DM_PM_SNIFF1_MAX, BTA_DM_PM_SNIFF1_MIN, BTA_DM_PM_SNIFF1_ATTEMPT,
         BTA_DM_PM_SNIFF1_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF1 */
        {BTA_DM_PM_SNIFF2_MAX, BTA_DM_PM_SNIFF2_MIN, BTA_DM_PM_SNIFF2_ATTEMPT,
         BTA_DM_PM_SNIFF2_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF2- HD idle */
        {BTA_DM_PM_SNIFF3_MAX, BTA_DM_PM_SNIFF3_MIN, BTA_DM_PM_SNIFF3_ATTEMPT,
         BTA_DM_PM_SNIFF3_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF3- SCO open */
        {BTA_DM_PM_SNIFF4_MAX, BTA_DM_PM_SNIFF4_MIN, BTA_DM_PM_SNIFF4_ATTEMPT,
         BTA_DM_PM_SNIFF4_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF4- HD active */
        {BTA_DM_PM_SNIFF5_MAX, BTA_DM_PM_SNIFF5_MIN, BTA_DM_PM_SNIFF5_ATTEMPT,
         BTA_DM_PM_SNIFF5_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF5- HD active */
        {BTA_DM_PM_SNIFF6_MAX, BTA_DM_PM_SNIFF6_MIN, BTA_DM_PM_SNIFF6_ATTEMPT,
         BTA_DM_PM_SNIFF6_TIMEOUT, BTM_PM_MD_SNIFF}, /* for BTA_DM_PM_SNIFF6- HD active */
};

tBTA_DM_SSR_SPEC mock_ssr_spec[] = {{0, 0, 0, "no_ssr"},
                                    {0, 0, 2, "hid_host"},
                                    {1200, 2, 2, "sniff_capable"},
                                    {360, 160, 1600, "hid_device"},
                                    {1200, 65534, 65534, "a2dp"}};
