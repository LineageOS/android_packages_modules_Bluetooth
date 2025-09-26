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

#include "sniff_offload_config_reader.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <format>
#include <iterator>
#include <utility>

#include "bta/dm/bta_dm_int.h"
#include "bta/test/common/bta_dm_cfg_mock.h"
#include "sniff_offload_structs.h"

// Flag indicating the what kind of config is requested by a test
// test_config_select_mock_dm_cfg, if TRUE, means mock dm cfg is selected
// test_config_select_mock_dm_cfg, if FALSE, means modifiable small configuration is selected
static bool test_config_select_mock_dm_cfg = false;

// ----------------------------------- Modifiable Configurations -------------------------------
// Small storage arrays containing test configs
// These shall be modifiable within each test
#define SMALL_TEST_CONFIG_SIZE 5
tBTA_DM_PM_CFG g_test_bta_dm_pm_cfg[SMALL_TEST_CONFIG_SIZE];
tBTA_DM_PM_SPEC g_test_bta_dm_pm_spec[SMALL_TEST_CONFIG_SIZE];
tBTM_PM_PWR_MD g_test_btm_pm_pwr_md[SMALL_TEST_CONFIG_SIZE];
tBTA_DM_SSR_SPEC g_test_bta_dm_ssr_spec[SMALL_TEST_CONFIG_SIZE];

// Actual variable containing pm entry count for test
size_t g_test_num_pm_entry = 0;

// ------------------------------------ Dependencies --------------------------------------------
// Define dependencies to default to the "Modifiable Configurations".
// Dependency pointers pointing to test config arrays
const tBTA_DM_PM_CFG* p_bta_dm_pm_cfg = g_test_bta_dm_pm_cfg;
tBTA_DM_SSR_SPEC* p_bta_dm_ssr_spec = g_test_bta_dm_ssr_spec;

// Dependency to return pm entry count
size_t bta_dm_get_num_pm_entry() {
  if (test_config_select_mock_dm_cfg == true) {
    return BTA_DM_NUM_PM_ENTRY;
  }
  return g_test_num_pm_entry;
}

// Wrapper function (also a dependency) to return the test spec
tBTA_DM_PM_TYPE_QUALIFIER tBTA_DM_PM_SPEC* get_bta_dm_pm_spec() {
  if (test_config_select_mock_dm_cfg == true) {
    return mock_get_bta_dm_pm_spec();
  }
  return g_test_bta_dm_pm_spec;
}

// Function (also a dependency) to return a real sniff entry from index
tBTM_PM_PWR_MD bta_dm_pm_get_sniff_entry(size_t index) {
  if (test_config_select_mock_dm_cfg == true) {
    if (index < BTA_DM_PM_MD_SIZE) {
      return mock_bta_dm_pm_md[index];
    }
  } else if (index < SMALL_TEST_CONFIG_SIZE) {
    return g_test_btm_pm_pwr_md[index];
  }
  return {};
}

// ----------------------------- Setup Functions to select dependency types ---------------------
void SetupSmallTestConfiguration() {
  // Set the mock dm cfg to 'false'
  test_config_select_mock_dm_cfg = false;
  p_bta_dm_pm_cfg = g_test_bta_dm_pm_cfg;
  p_bta_dm_ssr_spec = g_test_bta_dm_ssr_spec;
}

void SetupMockDmCfgConfiguration() {
  // Set the mock dm cfg to 'true'
  test_config_select_mock_dm_cfg = true;
  p_bta_dm_pm_cfg = mock_bta_dm_pm_cfg;
  p_bta_dm_ssr_spec = mock_ssr_spec;
}

namespace bluetooth {
namespace sniff_offload {
namespace {

using namespace bluetooth::sniff_offload;

class SniffConfigReaderTest : public ::testing::Test {
protected:
  void SetUp() override {
    std::fill(std::begin(g_test_bta_dm_pm_cfg), std::end(g_test_bta_dm_pm_cfg), tBTA_DM_PM_CFG{});
    std::fill(std::begin(g_test_bta_dm_pm_spec), std::end(g_test_bta_dm_pm_spec),
              tBTA_DM_PM_SPEC{});
    std::fill(std::begin(g_test_btm_pm_pwr_md), std::end(g_test_btm_pm_pwr_md), tBTM_PM_PWR_MD{});
    std::fill(std::begin(g_test_bta_dm_ssr_spec), std::end(g_test_bta_dm_ssr_spec),
              tBTA_DM_SSR_SPEC{});

    g_test_num_pm_entry = 0;

    reader_ = &(getSniffConfigReader());
  }

  void TearDown() override {}

  SniffConfigReader* reader_;
};

TEST_F(SniffConfigReaderTest, read_config__profile_does_not_exist) {
  g_test_num_pm_entry = 0;

  SetupSmallTestConfiguration();

  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_AV, 0, ProfileState::BTA_SYS_CONN_OPEN);

  ASSERT_EQ(config.priority_, Priority::kNoPriority);
}

TEST_F(SniffConfigReaderTest, read_config__av_conn_open_success) {
  g_test_num_pm_entry = 1;

  g_test_bta_dm_pm_cfg[1].id = static_cast<uint8_t>(ProfileId::BTA_ID_AV);
  g_test_bta_dm_pm_cfg[1].app_id = BTA_ALL_APP_ID;
  g_test_bta_dm_pm_cfg[1].spec_idx = 0;

  auto& spec = g_test_bta_dm_pm_spec[0];
  spec.actn_tbl[BTA_SYS_CONN_OPEN][0].power_mode = BTA_DM_PM_SNIFF;
  spec.actn_tbl[BTA_SYS_CONN_OPEN][0].timeout = 5000;
  spec.ssr = 1;

  auto& pwr_md = g_test_btm_pm_pwr_md[BTA_DM_PM_SNIFF & 0x0F];
  pwr_md.max = 800;
  pwr_md.min = 400;
  pwr_md.attempt = 4;
  pwr_md.timeout = 1;

  g_test_bta_dm_ssr_spec[1].max_lat = 1200;
  g_test_bta_dm_ssr_spec[1].min_rmt_to = 2;
  g_test_bta_dm_ssr_spec[1].min_loc_to = 2;

  SetupSmallTestConfiguration();

  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_AV, 0, ProfileState::BTA_SYS_CONN_OPEN);

  ASSERT_EQ(config.priority_, static_cast<Priority>(1));
  ASSERT_TRUE(config.allow_subrating_update_);
  ASSERT_TRUE(config.parameters_.allow_exit_on_rx);
  ASSERT_TRUE(config.parameters_.allow_exit_on_tx);
  EXPECT_EQ(config.parameters_.sniff_max_interval, 800);
  EXPECT_EQ(config.parameters_.sniff_min_interval, 400);
  EXPECT_EQ(config.parameters_.sniff_attempts, 4);
  EXPECT_EQ(config.parameters_.sniff_timeout, 1);
  EXPECT_EQ(config.parameters_.link_idle_timeout, 5000);
  EXPECT_EQ(config.parameters_.subrate_max_latency, 1200);
  EXPECT_EQ(config.parameters_.min_remote_timeout, 2);
  EXPECT_EQ(config.parameters_.min_remote_timeout, 2);
}

TEST_F(SniffConfigReaderTest, read_config_on_standard_table__av_conn_open_success) {
  // Load the mock tables from bta_dm_cfg
  SetupMockDmCfgConfiguration();

  // Read the config for A2DP when a BTA_SYS_CONN_OPEN.
  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_AV, BTA_ALL_APP_ID,
                                         ProfileState::BTA_SYS_CONN_OPEN);

  // Verify priority for BTA_DM_PM_SNIFF_A2DP_IDX is 1.
  ASSERT_EQ(config.priority_, static_cast<Priority>(1));
  EXPECT_TRUE(config.allow_subrating_update_);
  // Exit flags should be true.
  EXPECT_TRUE(config.parameters_.allow_exit_on_rx);
  EXPECT_TRUE(config.parameters_.allow_exit_on_tx);

  // Verify intervals from bta_dm_pm_md[BTA_DM_PM_SNIFF_A2DP_IDX]
  EXPECT_EQ(config.parameters_.sniff_max_interval, BTA_DM_PM_SNIFF_MAX);  // 800
  EXPECT_EQ(config.parameters_.sniff_min_interval, BTA_DM_PM_SNIFF_MIN);  // 400
  EXPECT_EQ(config.parameters_.link_idle_timeout, 7000);

  // Verify SSR max latency from bta_dm_ssr_spec[BTA_DM_PM_SSR2]
  EXPECT_EQ(config.parameters_.subrate_max_latency, 1200);
}

TEST_F(SniffConfigReaderTest, read_config_on_standard_table__av_conn_busy) {
  // Load the mock tables from bta_dm_cfg
  SetupMockDmCfgConfiguration();

  // Read config of AV for BTA_SYS_CONN_BUSY
  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_AV, BTA_ALL_APP_ID,
                                         ProfileState::BTA_SYS_CONN_BUSY);

  // Verify subrating update is disabled.
  // The power mode is active, and priority is kPriorityHighest.
  ASSERT_EQ(config.priority_, Priority::kPriorityHighest);
  EXPECT_FALSE(config.allow_subrating_update_);
  // The parameters are for active mode, so max interval is 0.
  EXPECT_EQ(config.parameters_.sniff_max_interval, 0);
  EXPECT_EQ(config.parameters_.subrate_max_latency, 0);
}

TEST_F(SniffConfigReaderTest, hidhost_gamepad_conn_open__allow_sniff_exit_on_rx_tx_is_false) {
  // Load the mock tables from bta_dm_cfg
  SetupMockDmCfgConfiguration();

  // Read config for a HID Gamepad, which has a specific app_id.
  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_HH, BTA_HH_APP_ID_GPAD,
                                         ProfileState::BTA_SYS_CONN_OPEN);

  // CONN_OPEN action is BTA_DM_PM_SNIFF6. Priority is (SNIFF6 - SNIFF + 1) = 7.
  ASSERT_EQ(config.priority_, static_cast<Priority>(7));

  // The BUSY action is also BTA_DM_PM_SNIFF6, so allow_exit_on_rx/tx should be false.
  EXPECT_FALSE(config.parameters_.allow_exit_on_rx);
  EXPECT_FALSE(config.parameters_.allow_exit_on_tx);
  EXPECT_EQ(config.parameters_.sniff_max_interval, BTA_DM_PM_SNIFF6_MAX);
  EXPECT_EQ(config.parameters_.link_idle_timeout, BTA_DM_PM_HH_OPEN_DELAY);

  // Value from bta_dm_ssr_spec[BTA_DM_PM_SSR1]
  EXPECT_EQ(config.parameters_.subrate_max_latency, 0);
}

TEST_F(SniffConfigReaderTest, profile_with_no_pref_should_return_no_priority_config) {
  // Load the mock tables from bta_dm_cfg
  SetupMockDmCfgConfiguration();

  // Choose to read a profile with no preference. GATTS is one such profile.
  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_GATTS, BTA_ALL_APP_ID,
                                         ProfileState::BTA_SYS_CONN_OPEN);

  // Verify that the action is BTA_DM_PM_NO_PREF, which is not a sniff mode.
  // This should in returning a default config with no priority.
  ASSERT_EQ(config.priority_, Priority::kNoPriority);
  EXPECT_FALSE(config.allow_subrating_update_);
  EXPECT_EQ(config.parameters_.sniff_max_interval, 0);
}

// Since not all the profiles specify a sniff config for the BTA_SYS_CONN_OPEN state.
// The SniffConfigReader reads the config for IDLE state for those profile.
// This test case verifies that this path is correctly executed for a known such profile.
// The FTP client role (BTA_ID_FTC)'s CONN_OPEN action is BTA_DM_PM_ACTIVE
// so the config read should be on the CONN_IDLE action, which is a sniff mode.
TEST_F(SniffConfigReaderTest, profiles_config_for_idle_state_selected) {
  // Load the mock tables from bta_dm_cfg
  SetupMockDmCfgConfiguration();

  // Read config for FTP client for BTA_SYS_CONN_OPEN state.
  auto config = reader_->ReadSniffConfig(ProfileId::BTA_ID_FTC, BTA_ALL_APP_ID,
                                         ProfileState::BTA_SYS_CONN_OPEN);

  // Verify it uses the CONN_IDLE settings from spec_idx=7.
  // The CONN_IDLE action is BTA_DM_PM_SNIFF_A2DP_IDX, priority should be 1.
  ASSERT_EQ(config.priority_, static_cast<Priority>(1));
  EXPECT_TRUE(config.allow_subrating_update_);
  EXPECT_EQ(config.parameters_.sniff_max_interval, BTA_DM_PM_SNIFF_MAX);

  // Link idle timeout should be BTA_FTC_IDLE_TO_SNIFF_DELAY_MS
  EXPECT_EQ(config.parameters_.link_idle_timeout, BTA_FTC_IDLE_TO_SNIFF_DELAY_MS);

  // Value from bta_dm_ssr_spec[BTA_DM_PM_SSR2]
  EXPECT_EQ(config.parameters_.subrate_max_latency, 1200);
}

// Verifies that a configuration with no priority is returned for the
// SCO_CLOSE state. This is because a closed SCO link is an event and does not
// represent a persistent state.
TEST_F(SniffConfigReaderTest, read_config__sco_close_returns_no_priority) {
  // Set up a test configuration for a profile. The specific actions don't
  // matter as SCO_CLOSE should always result in no priority.
  g_test_num_pm_entry = 1;

  g_test_bta_dm_pm_cfg[1].id = static_cast<uint8_t>(ProfileId::BTA_ID_AV);
  g_test_bta_dm_pm_cfg[1].app_id = BTA_ALL_APP_ID;
  g_test_bta_dm_pm_cfg[1].spec_idx = 0;

  SetupSmallTestConfiguration();

  // Call ReadSniffConfig for SCO_CLOSE state.
  auto config =
          reader_->ReadSniffConfig(ProfileId::BTA_ID_AV, 0, ProfileState::BTA_SYS_SCO_CLOSE);

  // Verify that the returned config has no priority.
  ASSERT_EQ(config.priority_, Priority::kNoPriority);
  EXPECT_FALSE(config.allow_subrating_update_);
  EXPECT_EQ(config.parameters_.sniff_max_interval, 0);
}

}  // namespace
}  // namespace sniff_offload
}  // namespace bluetooth
