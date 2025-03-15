/*
 * Copyright 2022 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/dm/bta_dm_disc_int.h"
#include "bta/test/bta_test_fixtures.h"
#include "hci/controller_interface_mock.h"
#include "test/mock/mock_main_shim_entry.h"

namespace {
const char kName[] = "Hello";
}

class BtaSdpTest : public BtaWithHwOnTest {
protected:
  void SetUp() override {
    BtaWithHwOnTest::SetUp();
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockControllerInterface>();
    ON_CALL(*bluetooth::hci::testing::mock_controller_, LeRand)
            .WillByDefault([](bluetooth::hci::LeRandCallback cb) { cb(0x1234); });
  }

  void TearDown() override {
    BtaWithHwOnTest::TearDown();
    bluetooth::hci::testing::mock_controller_.reset();
  }
};

class BtaSdpRegisteredTest : public BtaSdpTest {
protected:
  void SetUp() override { BtaSdpTest::SetUp(); }

  void TearDown() override { BtaSdpTest::TearDown(); }
};

TEST_F(BtaSdpTest, nop) {}

TEST_F(BtaSdpRegisteredTest, bta_dm_sdp_result_SDP_SUCCESS) {
  std::unique_ptr<tBTA_DM_SDP_STATE> state = std::make_unique<tBTA_DM_SDP_STATE>(
          tBTA_DM_SDP_STATE{.service_index = BTA_MAX_SERVICE_ID});
  bta_dm_sdp_result(tSDP_STATUS::SDP_SUCCESS, state.get());
}
