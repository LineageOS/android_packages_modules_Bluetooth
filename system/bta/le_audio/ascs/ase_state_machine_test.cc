/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "ase_state_machine.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <memory>

#include "ascs_types.h"
#include "stack/include/main_thread.h"
#include "test/common/sync_main_handler.h"

using namespace ::testing;
using namespace bluetooth;
using testing::_;

namespace bluetooth::le_audio::test {

namespace {
class MockServiceCallbacks : public AscsAseStateMachine::ServiceCallbacks {
public:
  MOCK_METHOD(void, OnAseTransition, (uint8_t ase_id, const RawAddress& pseudo_addr), (override));
};

static RawAddress GetTestAddress(uint8_t index) {
  return std::array<uint8_t, 6>{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
}

class AseStateMachineTest : public ::testing::TestWithParam<bool> {
protected:
  void SetUp() override {
    bool is_source_ase = GetParam();

    main_thread_start_up();
    post_on_bt_main([]() { log::info("Main thread started up"); });
    callbacks_ = std::make_unique<NiceMock<MockServiceCallbacks>>();
    sm_ = std::make_unique<AscsAseStateMachine>(is_source_ase, 1, GetTestAddress(1),
                                                callbacks_.get());
    sm_->Start();
    sync_main_handler();
  }

  void TearDown() override {
    // Execute all the callbacks scheduled on the main thread, and verify the mock
    post_on_bt_main([]() { log::info("Main thread shutting down"); });
    main_thread_shut_down();
    Mock::VerifyAndClearExpectations(callbacks_.get());

    sm_.reset();
  }

  std::unique_ptr<NiceMock<MockServiceCallbacks>> callbacks_;
  std::unique_ptr<AscsAseStateMachine> sm_;
};

TEST_P(AseStateMachineTest, test_initial_state) {
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::IDLE);
}

TEST_P(AseStateMachineTest, test_config_codec) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));

  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
}

TEST_P(AseStateMachineTest, test_config_qos) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
}

TEST_P(AseStateMachineTest, test_enable) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

  std::vector<uint8_t> metadata;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, &metadata);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
}

TEST_P(AseStateMachineTest, test_receiver_start_ready) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

  std::vector<uint8_t> metadata;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, &metadata);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::ENABLING);

  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_START_READY, nullptr);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::STREAMING);
}

TEST_P(AseStateMachineTest, test_disable) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

  std::vector<uint8_t> metadata;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, &metadata);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::ENABLING);

  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_START_READY, nullptr);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::STREAMING);

  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::DISABLE, nullptr);
  sync_main_handler();

  // Only the Source ASE needs a RECEIVER_STOP_READY confirmation to proceed further
  bool is_source_ase = GetParam();
  if (is_source_ase) {
    ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::DISABLING);

    EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
    sm_->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_STOP_READY, nullptr);
    sync_main_handler();
  }

  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
}

TEST_P(AseStateMachineTest, test_release) {
  ascs::AseStateCodecConfiguration codec_config;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  bool is_caching = false;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1))).Times(2);
  sm_->ProcessEvent(AscsAseStateMachine::Events::RELEASE, &is_caching);
  sync_main_handler();
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::IDLE);
}

TEST_P(AseStateMachineTest, test_release_caching) {
  ascs::AseStateCodecConfiguration codec_config;
  codec_config.pres_delay_min = 100;
  codec_config.pres_delay_max = 200;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);

  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1)));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);

  bool is_caching = true;
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1))).Times(2);
  sm_->ProcessEvent(AscsAseStateMachine::Events::RELEASE, &is_caching);
  sync_main_handler();
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
}

TEST_P(AseStateMachineTest, test_invalid_transition) {
  EXPECT_CALL(*callbacks_, OnAseTransition(1, GetTestAddress(1))).Times(0);
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, nullptr);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::IDLE);
}

TEST_P(AseStateMachineTest, ValidateEnableEvent) {
  // false in IDLE
  ASSERT_FALSE(sm_->ProcessEvent(AscsAseStateMachine::Events::VALIDATE_ENABLE, nullptr));

  // false in CODEC_CONFIGURED
  ascs::AseStateCodecConfiguration codec_config;
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
  ASSERT_FALSE(sm_->ProcessEvent(AscsAseStateMachine::Events::VALIDATE_ENABLE, nullptr));

  // true in QOS_CONFIGURED
  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
  ASSERT_TRUE(sm_->ProcessEvent(AscsAseStateMachine::Events::VALIDATE_ENABLE, nullptr));

  // false in ENABLING
  std::vector<uint8_t> metadata;
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, &metadata);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
  ASSERT_FALSE(sm_->ProcessEvent(AscsAseStateMachine::Events::VALIDATE_ENABLE, nullptr));
}

TEST_P(AseStateMachineTest, EnableConfirmedFlagLifecycle) {
  // Initial state IDLE
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::IDLE);
  ASSERT_FALSE(sm_->IsEnableConfirmed());

  // Transition to CODEC_CONFIGURED
  ascs::AseStateCodecConfiguration codec_config;
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, &codec_config);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::CODEC_CONFIGURED);
  ASSERT_FALSE(sm_->IsEnableConfirmed());

  // Transition to QOS_CONFIGURED
  ascs::AseStateQosConfiguration qos_config;
  ascs::DataPathConfiguration datapath_config;
  auto sm_params = std::make_pair(std::move(qos_config), std::move(datapath_config));
  sm_->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, &sm_params);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
  ASSERT_FALSE(sm_->IsEnableConfirmed());

  // Transition to ENABLING
  std::vector<uint8_t> metadata;
  sm_->ProcessEvent(AscsAseStateMachine::Events::ENABLE, &metadata);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::ENABLING);
  ASSERT_FALSE(sm_->IsEnableConfirmed());

  // Set the flag
  sm_->SetEnableConfirmed(true);
  ASSERT_TRUE(sm_->IsEnableConfirmed());

  // Transition to STREAMING, flag should be cleared
  sm_->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_START_READY, nullptr);
  sync_main_handler();
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::STREAMING);
  ASSERT_FALSE(sm_->IsEnableConfirmed());

  // Transition back to QOS_CONFIGURED, flag should remain cleared
  sm_->ProcessEvent(AscsAseStateMachine::Events::DISABLE, nullptr);
  sync_main_handler();

  bool is_source_ase = GetParam();
  if (is_source_ase) {
    ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::DISABLING);
    sm_->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_STOP_READY, nullptr);
    sync_main_handler();
  }
  ASSERT_EQ(sm_->GetStateId(), AscsAseStateMachine::StateId::QOS_CONFIGURED);
  ASSERT_FALSE(sm_->IsEnableConfirmed());
}

INSTANTIATE_TEST_CASE_P(AseStateMachineTestParametrizedByIsSourceAse, AseStateMachineTest,
                        ::testing::Values(false, true));

}  // namespace

}  // namespace bluetooth::le_audio::test
