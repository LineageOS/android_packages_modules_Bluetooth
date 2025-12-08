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

#include "sniff_offload_config_reader.h"

#include <cstdint>
#include <bluetooth/log.h>
#include "bta/dm/bta_dm_int.h"
#include "bluetooth/types/address.h"

namespace bluetooth {
namespace sniff_offload {

class SniffConfigReaderImpl : public SniffConfigReader {

public:
  SniffOffloadConfig ReadSniffConfig(ProfileId profile_id, uint8_t app_id, ProfileState state) {
    bool valid_sniff_params = false;
    SniffOffloadParameters sniff_params{.allow_exit_on_rx = true, .allow_exit_on_tx = true};

    log::info("ReadSniffConfig: profile id = {}, app_id = {}, state = {}.", profile_id, app_id,
              state);

    // Read from legacy tables.
    size_t index = 0;
    size_t num_pm_entry = bta_dm_get_num_pm_entry();
    // The iteration starts at index 1, since the 0th entry in bta_dm_pm_cfg to which the
    // p_bta_dm_pm_cfg points, is reserved for BTA_ID_SYS.
    for (index = 1; index <= num_pm_entry; index++) {
      if ((p_bta_dm_pm_cfg[index].id == static_cast<uint8_t>(profile_id)) &&
          ((p_bta_dm_pm_cfg[index].app_id == BTA_ALL_APP_ID) ||
           (p_bta_dm_pm_cfg[index].app_id == app_id))) {
        break;
      }
    }

    if (index > num_pm_entry) {
      log::error("No config found for profile id = {}, app_id = {}, state = {}.", profile_id,
                 app_id, state);
      return SniffOffloadConfig{
              .parameters_ = sniff_params,
              .priority_ = Priority::kNoPriority,
              .allow_subrating_update_ = false,
      };
    }

    const tBTA_DM_PM_CFG* p_pm_cfg = &p_bta_dm_pm_cfg[index];
    const tBTA_DM_PM_SPEC* p_pm_spec = &get_bta_dm_pm_spec()[p_pm_cfg->spec_idx];
    const tBTA_DM_PM_ACTN* p_act0 = &p_pm_spec->actn_tbl[static_cast<uint8_t>(state)][0];
    uint8_t sniff_subrating_index = 0;
    bool allow_subrating_update = true;
    switch(state) {
      case ProfileState::BTA_SYS_CONN_OPEN: {
        // The strategy to follow to read the config for CONN_OPEN is:
        // 1. Check the action for CONN_OPEN.
        // 2. If the action is not a sniff action,
        //    read the action for CONN_IDLE.
        // 3. Use the action specified in CONN_IDLE.
        if ((p_act0->power_mode > BTA_DM_PM_SNIFF7) ||
            (p_act0->power_mode < BTA_DM_PM_SNIFF)) {
          p_act0 = &p_pm_spec->actn_tbl[static_cast<uint8_t>(ProfileState::BTA_SYS_CONN_IDLE)][0];
        }

        // Decide exit on rx and tx flags.
        // If the action for CONN_BUSY is a sniff action,
        // do not allow exit on rx and tx. It basically means some profile wants
        // to keep the link in sniff mode when it is busy. So we will not allow
        // exit on rx and tx.
        const tBTA_DM_PM_ACTN* p_act_busy =
                &p_pm_spec->actn_tbl[static_cast<uint8_t>(ProfileState::BTA_SYS_CONN_BUSY)][0];
        if ((p_act_busy->power_mode <= BTA_DM_PM_SNIFF7) &&
            (p_act_busy->power_mode >= BTA_DM_PM_SNIFF)) {
          sniff_params.allow_exit_on_rx = false;
          sniff_params.allow_exit_on_tx = false;
        }
        valid_sniff_params = true;

        // Read the sniff subrating parameters index
        sniff_subrating_index = p_pm_spec->ssr;
        break;
      }
      case ProfileState::BTA_SYS_CONN_BUSY: {
        if(profile_id == ProfileId::BTA_ID_AV) {
          // Handle CONN_BUSY for AV only.
          valid_sniff_params = true;
          allow_subrating_update = false;
          sniff_subrating_index = BTA_DM_PM_SSR4;
        }
        break;
      }
      case ProfileState::BTA_SYS_CONN_IDLE: {
        if(profile_id == ProfileId::BTA_ID_AV) {
          // Handle CONN_IDLE for AV only.
          valid_sniff_params = true;
          allow_subrating_update = true;

          // Read the sniff subrating parameters index
          sniff_subrating_index = p_pm_spec->ssr;
        }
        break;
      }
      case ProfileState::BTA_SYS_SCO_OPEN: {
        valid_sniff_params = true;
        break;
      }

      // Parameters resulting from the BTA_SYS_SCO_CLOSE and
      // BTA_SYS_CONN_CLOSE should be returned as parameters with
      // kNoPriority.
      case ProfileState::BTA_SYS_SCO_CLOSE:
      case ProfileState::BTA_SYS_CONN_CLOSE: {
        break;
      }
      default: {
        // If the control has reached here, the valid_sniff_params hasn't changed from its
        // initial value of false. It is safe to do an early return from here. Care should be
        // taken to only modify the valid_sniff_params within the current switch block.
        return SniffOffloadConfig{
                .parameters_ = SniffOffloadParameters(),
                .priority_ = Priority::kNoPriority,
                .allow_subrating_update_ = false,
        };
      }
    }

    if (!valid_sniff_params) {
      // If no valid sniff params, use the default values and no priority.
      return SniffOffloadConfig{
              .parameters_ = SniffOffloadParameters(),
              .priority_ = Priority::kNoPriority,
              .allow_subrating_update_ = false,
      };
    }

    if (p_act0->power_mode == BTA_DM_PM_ACTIVE) {
      return SniffOffloadConfig{
              .parameters_ = SniffOffloadParameters{.sniff_max_interval = 0},
              .priority_ = Priority::kPriorityHighest,
              .allow_subrating_update_ = false,
      };
    }

    if ((p_act0->power_mode == BTA_DM_PM_NO_ACTION) && (sniff_subrating_index == BTA_DM_PM_SSR0)) {
      allow_subrating_update = false;
    }

    if ((p_act0->power_mode <= BTA_DM_PM_SNIFF7) && (p_act0->power_mode >= BTA_DM_PM_SNIFF)) {
      tBTM_PM_PWR_MD pwr_md = bta_dm_pm_get_sniff_entry(size_t(p_act0->power_mode & 0x0F));
      tBTA_DM_SSR_SPEC* p_spec = &p_bta_dm_ssr_spec[sniff_subrating_index];
      return SniffOffloadConfig{
              .parameters_ =
                      {
                              .sniff_max_interval = pwr_md.max,
                              .sniff_min_interval = pwr_md.min,
                              .sniff_attempts = pwr_md.attempt,
                              .sniff_timeout = pwr_md.timeout,
                              .link_idle_timeout = p_act0->timeout,
                              .subrate_max_latency = p_spec->max_lat,
                              .min_remote_timeout = p_spec->min_rmt_to,
                              .min_local_timeout = p_spec->min_loc_to,
                              .allow_exit_on_rx = sniff_params.allow_exit_on_rx,
                              .allow_exit_on_tx = sniff_params.allow_exit_on_tx,
                      },
              .priority_ = static_cast<Priority>(p_act0->power_mode - BTA_DM_PM_SNIFF + 1),
              .allow_subrating_update_ = allow_subrating_update,
      };
    }

    // We've reached here, return default with no priority
    return SniffOffloadConfig{
            .parameters_ = SniffOffloadParameters(),
            .priority_ = Priority::kNoPriority,
            .allow_subrating_update_ = false,
    };
  }
};

SniffConfigReader& getSniffConfigReader() {
  static SniffConfigReaderImpl instance;
  return instance;
}

}  // namespace sniff_offload
}  // namespace bluetooth
