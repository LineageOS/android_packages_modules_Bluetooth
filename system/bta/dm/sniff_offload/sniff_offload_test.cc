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

#include "sniff_offload.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <format>
#include <future>
#include <memory>
#include <utility>

#include "internal_include/stack_config.h"
#include "log/include/bluetooth/log.h"
#include "sniff_offload_config_reader.h"
#include "sniff_offload_structs.h"
#include "sniff_offload_vsc_sender.h"
#include "stack/include/bt_types.h"
#include "stack/include/main_thread.h"
#include "bluetooth/types/address.h"

namespace bluetooth {
namespace sniff_offload {
namespace {

using ::testing::_;
using ::testing::Eq;
using ::testing::Invoke;
using ::testing::InvokeArgument;
using ::testing::Return;

static constexpr uint8_t kWaitJitterMs = 10;
static constexpr uint8_t kDebounceDelay20Ms = 20;

// Choose an arbitrary not too high wait time
// for start to complete
static constexpr uint8_t kStartWaitInMs = 50;

static constexpr uint16_t kDefaultSubrateMaxLatency = 0xAAAA;
static constexpr uint16_t kDefaultSubrateMinRemoteTimeout = 0xBCBC;
static constexpr uint16_t kDefaultSubrateMinLocalTimeout = 0xEEDD;
static constexpr uint16_t kTestDefaultSniffMaxInterval = 0x0001;
// =================================================
// Test Data
// =================================================

// Factory for the default parameters used to start the offload module.
// The combination:- sniff_max_interval > 0, link_idle_timeout = 0,
// allow_exit_on_rx = allow_exit_on_tx = true refers to the Prefer-Active
// Sniff_Offload mode. This should be default state of parameters
// when all profiles have relinquished control.
SniffOffloadParameters CreateDefaultStartParams() {
  return {
          .sniff_max_interval = kTestDefaultSniffMaxInterval,
          .sniff_min_interval = 0,
          .sniff_attempts = 0,
          .sniff_timeout = 0,
          .link_idle_timeout = 0,
          .subrate_max_latency = kDefaultSubrateMaxLatency,
          .min_remote_timeout = kDefaultSubrateMinRemoteTimeout,
          .min_local_timeout = kDefaultSubrateMinLocalTimeout,
          .allow_exit_on_rx = true,
          .allow_exit_on_tx = true,
  };
}

// Factory to generate a map of profile configs for multi-profile tests
std::unordered_map<ProfileId, SniffOffloadConfig> CreateMultiProfileTestData() {
  std::unordered_map<ProfileId, SniffOffloadConfig> test_data_map;
  test_data_map[ProfileId::BTA_ID_AG] = {
          .parameters_ = {.sniff_max_interval = 20, .sniff_min_interval = 10},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  test_data_map[ProfileId::BTA_ID_OPC] = {
          .parameters_ = {.sniff_max_interval = 40, .sniff_min_interval = 20},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };
  test_data_map[ProfileId::BTA_ID_PBC] = {
          .parameters_ = {.sniff_max_interval = 80, .sniff_min_interval = 40},
          .priority_ = Priority::kPriority3,
          .allow_subrating_update_ = true,
  };
  return test_data_map;
}

// =================================================
// Mock Dependencies
// =================================================

class MockSniffOffloadVscSender : public SniffOffloadVscSender {
 public:
   MOCK_METHOD(void, WriteSniffOffloadEnable,
               (uint16_t, uint16_t, uint16_t, bool, bool, WriteSniffOffloadEnableCompleteCallback),
               (override));
   MOCK_METHOD(void, WriteSniffOffloadParameters,
               (uint16_t, SniffOffloadParameters, WriteSniffOffloadParametersCompleteCallback),
               (override));
};

class MockSniffConfigReader : public SniffConfigReader {
 public:
   MOCK_METHOD(SniffOffloadConfig, ReadSniffConfig, (ProfileId, uint8_t, ProfileState), (override));
};

class MockSniffOffloadCallbacks : public SniffOffloadCallbacks {
 public:
   MOCK_METHOD(void, OnSniffOffloadStarted, (tHCI_STATUS), (override));
   MOCK_METHOD(void, OnLinkParamsUpdated, (uint16_t, SniffOffloadParameters, tHCI_STATUS),
               (override));
};

// =================================================================
// Test Fixture
// =================================================================

class SniffOffloadTest : public ::testing::Test {
 protected:
   static void SetUpTestSuite() {
     main_thread_start_up();
     post_on_bt_main([]() { log::info("Main thread started up for test suite."); });
   }

   static void TearDownTestSuite() {
     post_on_bt_main([]() { log::info("Main thread shutting down for test suite."); });
     main_thread_shut_down();
   }

   void SetUp() override {
     vsc_sender_ = std::make_unique<MockSniffOffloadVscSender>();
     config_reader_ = std::make_unique<MockSniffConfigReader>();
     mock_vsc_interface_ = vsc_sender_.get();
     mock_config_reader_ = config_reader_.get();
     mock_callbacks_ = std::make_shared<MockSniffOffloadCallbacks>();

     sniff_offload_ =
             GetSniffOffloadInstance(config_reader_.get(), vsc_sender_.get(), update_delay_);
   }

   void TearDown() override {}

   /**
    * @brief Helper function to start the sniff offload module successfully.
    * This encapsulates the boilerplate for setting expectations and waiting for the
    * asynchronous start operation to complete.
    */
   void StartOffloadSuccessfully(bool suppress_mode_change_event = true,
                                 bool suppress_subrating_event = false) {
     std::promise<void> start_promise;
     auto start_future = start_promise.get_future();
     uint16_t subrate_max_latency = kDefaultSubrateMaxLatency;
     uint16_t subrate_min_remote_timeout = kDefaultSubrateMinRemoteTimeout;
     uint16_t subrate_min_local_timeout = kDefaultSubrateMinLocalTimeout;

     EXPECT_CALL(*mock_vsc_interface_,
                 WriteSniffOffloadEnable(Eq(subrate_max_latency), Eq(subrate_min_remote_timeout),
                                         Eq(subrate_min_local_timeout), suppress_mode_change_event,
                                         suppress_subrating_event, _))
             .WillOnce(InvokeArgument<5>(HCI_SUCCESS));

     EXPECT_CALL(*mock_callbacks_, OnSniffOffloadStarted(Eq(HCI_SUCCESS)))
             .WillOnce(Invoke([&start_promise]() { start_promise.set_value(); }));

     sniff_offload_->Start(subrate_max_latency, subrate_min_remote_timeout,
                           subrate_min_local_timeout, suppress_mode_change_event,
                           suppress_subrating_event, mock_callbacks_);
     log::info("Going to wait now for the start to complete!!");
     start_future.wait_for(std::chrono::milliseconds(50));
   }

  /**
   * @brief Helper to set expectations for a successful parameter update and
   * return a future for synchronization.
   * @return A std::future<void> that will be fulfilled when the OnLinkParamsUpdated
   * callback is invoked.
   */
  std::future<void> ExpectSuccessfulParamsUpdate(uint16_t expected_handle,
                                                 const SniffOffloadParameters& expected_params) {
    auto promise = std::make_shared<std::promise<void>>();
    auto future = promise->get_future();

    EXPECT_CALL(*mock_vsc_interface_,
                WriteSniffOffloadParameters(expected_handle, expected_params, _))
            .WillOnce(InvokeArgument<2>(expected_handle, HCI_SUCCESS));

    EXPECT_CALL(*mock_callbacks_,
                OnLinkParamsUpdated(expected_handle, expected_params, Eq(HCI_SUCCESS)))
            .WillOnce(Invoke([promise]() { promise->set_value(); }));

    return future;
  }

  // Sets up expectations for a FAILED parameter update.
  // This is useful for testing the error handling path.
  std::future<void> ExpectFailedParamsUpdate(uint16_t expected_handle,
                                             const SniffOffloadParameters& expected_params,
                                             tHCI_STATUS failure_status) {
    auto promise = std::make_shared<std::promise<void>>();
    auto future = promise->get_future();

    EXPECT_CALL(*mock_vsc_interface_,
                WriteSniffOffloadParameters(expected_handle, expected_params, _))
            .WillOnce(InvokeArgument<2>(expected_handle, failure_status));

    EXPECT_CALL(*mock_callbacks_,
                OnLinkParamsUpdated(expected_handle, expected_params, Eq(failure_status)))
            .WillOnce(Invoke([promise]() { promise->set_value(); }));

    return future;
  }

  std::shared_ptr<SniffOffload> sniff_offload_;
  std::shared_ptr<MockSniffOffloadCallbacks> mock_callbacks_;
  std::chrono::milliseconds update_delay_{kDebounceDelay20Ms};

  MockSniffOffloadVscSender* mock_vsc_interface_;
  MockSniffConfigReader* mock_config_reader_;

  std::unique_ptr<MockSniffConfigReader> config_reader_;
  std::unique_ptr<MockSniffOffloadVscSender> vsc_sender_;
};

// =================================================================
// Test Cases
// =================================================================

TEST_F(SniffOffloadTest, start_success) {
  // Call helper that does the start and does the verification of started
  StartOffloadSuccessfully();
}

TEST_F(SniffOffloadTest, start_failure) {
  // Setup
  std::promise<void> start_promise;
  auto start_future = start_promise.get_future();
  bool suppress_mode_change_event = false;
  bool suppress_subrating_event = true;
  uint16_t subrate_max_latency = kDefaultSubrateMaxLatency;
  uint16_t subrate_min_remote_timeout = kDefaultSubrateMinRemoteTimeout;
  uint16_t subrate_min_local_timeout = kDefaultSubrateMinLocalTimeout;

  EXPECT_CALL(*mock_vsc_interface_,
              WriteSniffOffloadEnable(Eq(subrate_max_latency), Eq(subrate_min_remote_timeout),
                                      Eq(subrate_min_local_timeout), suppress_mode_change_event,
                                      suppress_subrating_event, _))
          .WillOnce(InvokeArgument<5>(HCI_ERR_UNSPECIFIED));
  EXPECT_CALL(*mock_callbacks_, OnSniffOffloadStarted(HCI_ERR_UNSPECIFIED));

  // Issue start
  sniff_offload_->Start(subrate_max_latency, subrate_min_remote_timeout, subrate_min_local_timeout,
                        suppress_mode_change_event, suppress_subrating_event, mock_callbacks_);
  start_future.wait_for(std::chrono::milliseconds(kStartWaitInMs + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, single_profile_event) {
  // Setup: Start the module
  StartOffloadSuccessfully();

  // Define test data and expectations for the profile event
  uint16_t test_handle = 0x1234;
  ProfileId test_profile_id = ProfileId::BTA_ID_AG;
  SniffOffloadConfig test_config = {
          .parameters_ = {.sniff_max_interval = 20, .sniff_min_interval = 10},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(test_profile_id, _, _))
          .WillRepeatedly(Return(test_config));
  auto params_set_future = ExpectSuccessfulParamsUpdate(test_handle, test_config.parameters_);

  // Step 1: Start
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for the parameters to set.
  params_set_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, profile_a_blinks) {
  // Setup: Start the module
  StartOffloadSuccessfully();

  // Setup: Set expectations that no parameter update should occur
  uint16_t test_handle = 0x1234;
  EXPECT_CALL(*mock_vsc_interface_, WriteSniffOffloadParameters(test_handle, _, _)).Times(0);
  EXPECT_CALL(*mock_callbacks_, OnLinkParamsUpdated(_, _, _)).Times(0);

  // Step 1: A profile opens and closes within the debounce delay
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_CLOSE);

  // Wait longer than the debounce delay to ensure no call was made
  std::promise<void> timer_promise;
  auto timer_future = timer_promise.get_future();
  timer_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, profile_a_blinks2) {
  // Setup: Start the module
  StartOffloadSuccessfully();

  // Setup: Set expectations that no parameter update should occur
  uint16_t test_handle = 0x1234;
  EXPECT_CALL(*mock_vsc_interface_, WriteSniffOffloadParameters(test_handle, _, _)).Times(0);
  EXPECT_CALL(*mock_callbacks_, OnLinkParamsUpdated(_, _, _)).Times(0);

  // Step 1: A profile opens and closes within the debounce delay
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_CLOSE);

  // Wait longer than the debounce delay to ensure no call was made
  std::promise<void> timer_promise;
  auto timer_future = timer_promise.get_future();
  timer_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, multiple_profile_events_priority_variation) {
  // Setup: Start the module and set up test data
  StartOffloadSuccessfully();
  auto test_data_map = CreateMultiProfileTestData();
  uint16_t test_handle = 0x1234;

  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(_, _, _))
          .WillRepeatedly(Invoke([&test_data_map](ProfileId profile_id, uint8_t, ProfileState) {
            return test_data_map.at(profile_id);
          }));

  // Setup: Expect that params for BTA_ID_AG (highest priority) are written
  auto highest_prio_config = test_data_map.at(ProfileId::BTA_ID_AG);
  auto params_set_future =
          ExpectSuccessfulParamsUpdate(test_handle, highest_prio_config.parameters_);

  // Step 1: Send state changes for all profiles
  for (const auto& [profile_id, config] : test_data_map) {
    sniff_offload_->OnProfileStateChanged(test_handle, profile_id, 0xFF,
                                          ProfileState::BTA_SYS_CONN_OPEN);
  }

  // Wait for operation to complete
  params_set_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, multiple_profile_is_highest_idle_timeout_selected) {
  // Setup: Start the module
  StartOffloadSuccessfully();

  // Setup: Construct test data with varying idle timeouts
  uint16_t test_handle = 0x1234;
  std::unordered_map<ProfileId, SniffOffloadConfig> test_data_map;
  test_data_map[ProfileId::BTA_ID_AG] = {
          // Highest priority
          .parameters_ = {.sniff_max_interval = 20, .link_idle_timeout = 100},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  test_data_map[ProfileId::BTA_ID_OPC] = {
          // Highest idle timeout
          .parameters_ = {.sniff_max_interval = 40, .link_idle_timeout = 500},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(_, _, _))
          .WillRepeatedly(Invoke([&test_data_map](ProfileId profile_id, uint8_t, ProfileState) {
            return test_data_map.at(profile_id);
          }));

  // Setup more: Expect params from the highest priority profile, but with the *maximum* idle
  // timeout
  SniffOffloadParameters expected_params = test_data_map.at(ProfileId::BTA_ID_AG).parameters_;
  expected_params.link_idle_timeout =
          test_data_map.at(ProfileId::BTA_ID_OPC).parameters_.link_idle_timeout;
  auto params_set_future = ExpectSuccessfulParamsUpdate(test_handle, expected_params);

  // Take action to issue profile state changes
  for (const auto& [profile_id, config] : test_data_map) {
    sniff_offload_->OnProfileStateChanged(test_handle, profile_id, 0xFF,
                                          ProfileState::BTA_SYS_CONN_OPEN);
  }

  // Wait for operation to complete
  params_set_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, multiple_profiles_profile_a_blinks) {
  // Setup: Start the module and set up test data
  StartOffloadSuccessfully();
  auto test_data_map = CreateMultiProfileTestData();
  uint16_t test_handle = 0x1234;

  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(_, _, _))
          .WillRepeatedly(Invoke([&test_data_map](ProfileId profile_id, uint8_t, ProfileState) {
            return test_data_map.at(profile_id);
          }));

  // Setup: Expect params for BTA_ID_OPC (next highest priority) to be written
  auto next_highest_prio_config = test_data_map.at(ProfileId::BTA_ID_OPC);
  auto params_set_future =
          ExpectSuccessfulParamsUpdate(test_handle, next_highest_prio_config.parameters_);

  // Take action: Open all profiles, then immediately close the highest priority one
  for (const auto& [profile_id, config] : test_data_map) {
    sniff_offload_->OnProfileStateChanged(test_handle, profile_id, 0xFF,
                                          ProfileState::BTA_SYS_CONN_OPEN);
  }
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_CLOSE);

  // Wait for operation to complete
  params_set_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, single_profile_slow_state_changes) {
  // Setup: Start the module
  SniffOffloadParameters start_params = CreateDefaultStartParams();
  StartOffloadSuccessfully();

  // Setup: Define test data
  uint16_t test_handle = 0x1234;
  ProfileId test_profile_id = ProfileId::BTA_ID_AG;
  SniffOffloadConfig test_config = {
          .parameters_ = {.sniff_max_interval = 20, .sniff_min_interval = 10},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(_, _, _)).WillRepeatedly(Return(test_config));

  // Step 1: Profile opens
  auto future1 = ExpectSuccessfulParamsUpdate(test_handle, test_config.parameters_);
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future1.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Step 2: Profile closes, should revert to default start_params
  auto future2 = ExpectSuccessfulParamsUpdate(test_handle, start_params);
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_CLOSE);
  future2.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, dual_profile_new_higher_priority_profile_opened_closed) {
  // Setup: Start the module
  StartOffloadSuccessfully();
  uint16_t test_handle = 0x1234;

  // Setup: Setup initial profile (AG, priority 5)
  SniffOffloadConfig ag_config = {
          .parameters_ = {.sniff_max_interval = 20, .sniff_min_interval = 10},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .WillRepeatedly(Return(ag_config));

  // Step 1: AG profile opens
  auto future1 = ExpectSuccessfulParamsUpdate(test_handle, ag_config.parameters_);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future1.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Setup: Setup a new, higher priority profile (OPC, priority 6)
  SniffOffloadConfig opc_config = {
          .parameters_ = {.sniff_max_interval = 10, .sniff_min_interval = 5},
          .priority_ = Priority::kPriority6,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .WillRepeatedly(Return(opc_config));

  // Step 2: OPC profile opens, its params should be chosen
  auto future2 = ExpectSuccessfulParamsUpdate(test_handle, opc_config.parameters_);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future2.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Step 3: OPC profile closes, params should revert to AG's
  auto future3 = ExpectSuccessfulParamsUpdate(test_handle, ag_config.parameters_);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_CLOSE);
  future3.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, state_is_managed_per_connection_handle) {
  // This test ensures that the module correctly isolates state between
  // two different active connections. An event on one handle should not
  // affect the other.
  StartOffloadSuccessfully();
  uint16_t handle_a = 0x0001;
  uint16_t handle_b = 0x0002;

  SniffOffloadConfig config_a = {
          .parameters_ = {.sniff_max_interval = 100},
          .priority_ = Priority::kPriority3,
          .allow_subrating_update_ = true,
  };
  SniffOffloadConfig config_b = {
          .parameters_ = {.sniff_max_interval = 200},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };

  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_a));
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_b));

  // Step 1: Open profile connection on connection 'a'
  auto future_a = ExpectSuccessfulParamsUpdate(handle_a, config_a.parameters_);
  sniff_offload_->OnProfileStateChanged(handle_a, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future_a.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Step 2: Open profile connection on connection 'b'
  // This should trigger a new update for handle 'b', leaving A unaffected.
  auto future_b = ExpectSuccessfulParamsUpdate(handle_b, config_b.parameters_);
  sniff_offload_->OnProfileStateChanged(handle_b, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future_b.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, is_parameter_update_failure_reported) {
  // Make sure that if a mock controller rejects our VSC,
  // the failure is correctly reported up to callback client.
  StartOffloadSuccessfully();
  uint16_t test_handle = 0x1234;

  SniffOffloadConfig test_config = {
          .parameters_ = {.sniff_max_interval = 20},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(_, _, _)).WillRepeatedly(Return(test_config));
  // Expect a failure.
  auto future = ExpectFailedParamsUpdate(test_handle, test_config.parameters_,
                                         HCI_ERR_COMMAND_DISALLOWED);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, redundant_update_is_skipped) {
  // If a profile state change does not result in unique/new parameters after comparing
  // parameters of all possible candidates, a parameter update request should not be
  // initiated by the sniff_offload module
  StartOffloadSuccessfully();
  uint16_t test_handle = 0x1234;

  SniffOffloadConfig high_prio_config = {
          .parameters_ = {.sniff_max_interval = 50},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  SniffOffloadConfig low_prio_config = {
          .parameters_ = {.sniff_max_interval = 100},
          .priority_ = Priority::kPriority2,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .WillRepeatedly(Return(high_prio_config));
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .WillRepeatedly(Return(low_prio_config));

  // Step 1: High priority profile becomes active
  auto future1 = ExpectSuccessfulParamsUpdate(test_handle, high_prio_config.parameters_);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future1.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Step 2: Lower priority profile also becomes active
  // Since the high priority profile is still present, the final parameters
  // don't change. Expect NO new call to the VSC sender.
  EXPECT_CALL(*mock_vsc_interface_, WriteSniffOffloadParameters(_, _, _)).Times(0);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for debouncing + extra delay.
  std::promise<void>().get_future().wait_for(
          std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, subrating_params_from_min_latency_profile) {
  // This test validates standard path for subrating parameter selection logic.
  // When two active profiles, both allow subrating updates.
  // Expectation is that most parameters are from the highest priority profile, but the three
  // specific subrating fields from the profile with the minimum subrating latency.
  StartOffloadSuccessfully();
  uint16_t test_handle = 0x1234;

  // Setup:
  // Profile 'a': highest priority, but higher latency.
  SniffOffloadConfig config_a = {
          .parameters_ = {.sniff_max_interval = 50,
                          .subrate_max_latency = 800,
                          .min_remote_timeout = 80,
                          .min_local_timeout = 80},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  // Profile 'b': lower priority, but minimum latency.
  SniffOffloadConfig config_b = {
          .parameters_ = {.sniff_max_interval = 100,
                          .subrate_max_latency = 200,
                          .min_remote_timeout = 20,
                          .min_local_timeout = 20},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };

  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_a));
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_b));

  // Assemble the expected final parameters:
  // Base parameters are from 'a', but the three subrating fields are from 'b'.
  SniffOffloadParameters expected_params = config_a.parameters_;
  expected_params.subrate_max_latency = config_b.parameters_.subrate_max_latency;
  expected_params.min_remote_timeout = config_b.parameters_.min_remote_timeout;
  expected_params.min_local_timeout = config_b.parameters_.min_local_timeout;
  auto future = ExpectSuccessfulParamsUpdate(test_handle, expected_params);

  // Issue profile state change
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for operations to complete
  future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, subrating_params_preserved_when_new_high_prio_profile_disallows) {
  // This test validates subrating parameters are not update to controller,
  // When a link is active with one set of subrating params. A new, higher
  // priority profile comes, but it DISALLOWS subrating updates. The expectation
  // is that the subrating fields to remain unchanged from their previous state.
  StartOffloadSuccessfully();
  uint16_t test_handle = 0x1234;
  testing::InSequence s;

  // Step 1 : Profile B is active first. Its subrating params would act as base.
  SniffOffloadConfig config_b = {
          .parameters_ = {.sniff_max_interval = 100,
                          .subrate_max_latency = 200,
                          .min_remote_timeout = 20,
                          .min_local_timeout = 20},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_b));
  auto future1 = ExpectSuccessfulParamsUpdate(test_handle, config_b.parameters_);

  // Step 2: Issue Profile B's state change.
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  future1.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));

  // Step 3: A new, higher priority profile arrives and disallows updates.
  SniffOffloadConfig config_a = {
          .parameters_ = {.sniff_max_interval = 50, .subrate_max_latency = 800},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = false,
  };
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .Times(2)
          .WillRepeatedly(Return(config_a));
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_OPC, _, _))
          .WillOnce(Return(config_b));

  // Assemble the expected final parameters for 2nd update:
  // Base is from A, but the three subrating fields must be preserved from B.
  SniffOffloadParameters expected_params2 = config_a.parameters_;
  expected_params2.subrate_max_latency = config_b.parameters_.subrate_max_latency;
  expected_params2.min_remote_timeout = config_b.parameters_.min_remote_timeout;
  expected_params2.min_local_timeout = config_b.parameters_.min_local_timeout;
  auto future2 = ExpectSuccessfulParamsUpdate(test_handle, expected_params2);

  // Step 4: Issue another profile state change
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for this update to complete:
  future2.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, profile_with_no_priority_triggers_no_update) {
  // This test ensures that if a profile with kNoPriority becomes active, and
  // the link is already at default parameters, no redundant parameter update
  // is sent to the controller.

  // Start the sniff offload
  StartOffloadSuccessfully();

  uint16_t test_handle = 0x1234;

  // Define a profile config that is marked with kNoPriority.
  SniffOffloadConfig no_prio_config = {
          .parameters_ = {.sniff_max_interval = 50, .sniff_min_interval = 25},
          .priority_ = Priority::kNoPriority,
          .allow_subrating_update_ = false,
  };

  // Set up the mock reader to return "no priority" config.
  EXPECT_CALL(*mock_config_reader_, ReadSniffConfig(ProfileId::BTA_ID_AG, _, _))
          .Times(2)
          .WillRepeatedly(Return(no_prio_config));

  // Since activating this profile does not change the effective link
  // parameters (they remain the default), no VSC should be sent.
  EXPECT_CALL(*mock_vsc_interface_, WriteSniffOffloadParameters(_, _, _)).Times(0);
  EXPECT_CALL(*mock_callbacks_, OnLinkParamsUpdated(_, _, _)).Times(0);

  // The profile with kNoPriority becomes active.
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for a period longer than the debounce delay to ensure
  // that no calls were made.
  std::promise<void> timer_promise;
  auto timer_future = timer_promise.get_future();
  timer_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, high_priority_profile_params_persist_in_idle_state) {
  // This test validates that once a profile (A) sets high-priority parameters,
  // those parameters remain the "winning" ones for comparison even if profile A
  // later moves to an idle state that has kNoPriority. A new, lower-priority
  // profile (B) becoming active should NOT cause a parameter update.

  StartOffloadSuccessfully();

  uint16_t test_handle = 0x1234;

  // Define configs for the profiles and their states.
  SniffOffloadConfig profile_a_active_config = {
          .parameters_ = {.sniff_max_interval = 20},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };
  SniffOffloadConfig profile_a_idle_config = {
          .parameters_ = {.sniff_max_interval = 800},
          .priority_ = Priority::kNoPriority,
          .allow_subrating_update_ = true,
  };
  SniffOffloadConfig profile_b_active_config = {
          .parameters_ = {.sniff_max_interval = 100},
          .priority_ = Priority::kPriority4,
          .allow_subrating_update_ = true,
  };

  // Profile A becomes active, setting the high-priority baseline

  EXPECT_CALL(*mock_config_reader_,
              ReadSniffConfig(ProfileId::BTA_ID_AG, _, ProfileState::BTA_SYS_CONN_OPEN))
          .Times(2)
          .WillRepeatedly(Return(profile_a_active_config));

  // The first update uses Profile A's high-priority parameters.
  auto future1 = ExpectSuccessfulParamsUpdate(test_handle, profile_a_active_config.parameters_);

  // Activate Profile A.
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for the first update to complete to establish the baseline.
  ASSERT_EQ(future1.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs)),
            std::future_status::ready)
          << "Timeout on initial update for Profile A";

  // Setup mock calls for the subsequent state changes.
  EXPECT_CALL(*mock_config_reader_,
              ReadSniffConfig(ProfileId::BTA_ID_OPC, _, ProfileState::BTA_SYS_CONN_OPEN))
          .Times(2)
          .WillRepeatedly(Return(profile_b_active_config));
  EXPECT_CALL(*mock_config_reader_,
              ReadSniffConfig(ProfileId::BTA_ID_AG, _, ProfileState::BTA_SYS_CONN_IDLE))
          .WillOnce(Return(profile_a_idle_config));

  // NO parameter update should occur. The system should remember
  // Profile A's priority-5 config and see that the new active profile (B) has a
  // lower priority (4), resulting in no change to the active parameters.
  EXPECT_CALL(*mock_vsc_interface_, WriteSniffOffloadParameters(_, _, _)).Times(0);
  EXPECT_CALL(*mock_callbacks_, OnLinkParamsUpdated(_, _, _)).Times(0);

  // Transition Profile A to idle, then activate Profile B.
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_AG, 0xFF,
                                        ProfileState::BTA_SYS_CONN_IDLE);
  sniff_offload_->OnProfileStateChanged(test_handle, ProfileId::BTA_ID_OPC, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);

  // Wait for a period longer than the debounce delay to ensure
  // that no calls were made.
  std::promise<void> timer_promise;
  auto timer_future = timer_promise.get_future();
  timer_future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs));
}

TEST_F(SniffOffloadTest, rapid_state_changes_after_open__use_open_config) {
  // 1. A profile connection opens, which establishes a baseline configuration with a real priority.
  // 2. Before the debounce delay expires, the same profile transitions to BUSY and then to IDLE.
  // 3. The IDLE state is configured to have kNoPriority.
  //
  // The expected outcome is that the system handles rapid state changes, and when
  // the debounce timer finally fires, it evaluates the final state. Because the IDLE state has
  // kNoPriority, the logic MUST fall back to the saved configuration from the initial CONN_OPEN
  // state. Therefore, a single parameter update should occur using the CONN_OPEN parameters.

  testing::InSequence s;
  StartOffloadSuccessfully();

  uint16_t test_handle = 0x1234;
  ProfileId test_profile_id = ProfileId::BTA_ID_AV;

  // Define distinct configs for each state.
  SniffOffloadConfig open_config = {
          .parameters_ = {.sniff_max_interval = 100},
          .priority_ = Priority::kPriority5,
          .allow_subrating_update_ = true,
  };

  SniffOffloadConfig idle_config = {
          .parameters_ = {.sniff_max_interval = 999},
          .priority_ = Priority::kNoPriority,
          .allow_subrating_update_ = true,
  };

  // Set up mock reader to be called for state change OPEN and IDLE
  // and not for the BUSY state as intermediate states within the debounce period
  // do not cause a read config to take place.
  EXPECT_CALL(*mock_config_reader_,
              ReadSniffConfig(test_profile_id, _, ProfileState::BTA_SYS_CONN_OPEN))
          .WillOnce(Return(open_config));

  EXPECT_CALL(*mock_config_reader_,
              ReadSniffConfig(test_profile_id, _, ProfileState::BTA_SYS_CONN_IDLE))
          .WillOnce(Return(idle_config));

  // The final parameter update MUST use the parameters from the 'open_config'.
  auto future = ExpectSuccessfulParamsUpdate(test_handle, open_config.parameters_);

  // Trigger three state changes in quick succession, within the debounce delay.
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_OPEN);
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_BUSY);
  sniff_offload_->OnProfileStateChanged(test_handle, test_profile_id, 0xFF,
                                        ProfileState::BTA_SYS_CONN_IDLE);

  // Wait for the single, debounced update to complete and verify it used the correct params.
  ASSERT_EQ(future.wait_for(std::chrono::milliseconds(kDebounceDelay20Ms + kWaitJitterMs)),
            std::future_status::ready)
          << "Timeout waiting for the debounced parameter update";
}

}  // namespace
}  // namespace sniff_offload
}  // namespace bluetooth
