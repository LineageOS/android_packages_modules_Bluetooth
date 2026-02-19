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

#include <bluetooth/types/address.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <sys/socket.h>

#include "bta/dm/bta_dm_sec_int.h"
#include "bta/test/bta_test_fixtures.h"
#include "stack/include/btm_status.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_rnr_interface.h"

using ::testing::_;
using ::testing::ElementsAre;
using ::testing::Return;

namespace {
const RawAddress kRawAddress("11:22:33:44:55:66");
const DEV_CLASS kDeviceClass = {0x11, 0x22, 0x33};

constexpr char kRemoteName[] = "TheRemoteName";

}  // namespace

class BtaSecTest : public BtaWithHwOnTest {
protected:
  void SetUp() override {
    BtaWithHwOnTest::SetUp();
    bluetooth::testing::stack::rnr::set_interface(&mock_stack_rnr_interface_);
  }

  void TearDown() override {
    bluetooth::testing::stack::rnr::reset_interface();
    BtaWithHwOnTest::TearDown();
  }

  bluetooth::testing::stack::rnr::Mock mock_stack_rnr_interface_;
};

TEST_F(BtaSecTest, bta_dm_sp_cback__BTM_SP_CFM_REQ_EVT_WithName) {
  constexpr uint32_t kNumVal = 1234;
  static bool callback_sent = false;

  static tBTA_DM_SP_CFM_REQ cfm_req{};
  bta_dm_sec_enable([](tBTA_DM_SEC_EVT /*event*/, tBTA_DM_SEC* p_data) {
    callback_sent = true;
    cfm_req = p_data->cfm_req;
  });

  tBTM_SP_EVT_DATA data = {
          .cfm_req =
                  {
                          // tBTM_SP_CFM_REQ
                          .bd_addr = kRawAddress,
                          .dev_class = {},
                          .bd_name = {},
                          .num_val = kNumVal,
                          .just_works = false,
                          .loc_auth_req = BTM_AUTH_SP_YES,
                          .rmt_auth_req = BTM_AUTH_SP_YES,
                          .loc_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                          .rmt_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                  },
  };
  data.cfm_req.dev_class = kDeviceClass;
  bd_name_from_char_pointer(data.cfm_req.bd_name, kRemoteName);

  ASSERT_EQ(
          btm_status_text(tBTM_STATUS::BTM_CMD_STARTED),
          btm_status_text(bluetooth::legacy::testing::bta_dm_sp_cback(BTM_SP_CFM_REQ_EVT, &data)));
  ASSERT_EQ(kNumVal, bta_dm_sec_cb.num_val);
  ASSERT_TRUE(callback_sent);

  ASSERT_EQ(kRawAddress, cfm_req.bd_addr);
  ASSERT_THAT(cfm_req.dev_class, ElementsAre(kDeviceClass[0], kDeviceClass[1], kDeviceClass[2]));
  ASSERT_STREQ(kRemoteName, reinterpret_cast<const char*>(cfm_req.bd_name));
  ASSERT_EQ(kNumVal, cfm_req.num_val);
  ASSERT_EQ(false, cfm_req.just_works);
  ASSERT_EQ(BTM_AUTH_SP_YES, cfm_req.loc_auth_req);
  ASSERT_EQ(BTM_AUTH_SP_YES, cfm_req.rmt_auth_req);
  ASSERT_EQ(BtIoCap::NO_INPUT_NO_OUTPUT, cfm_req.loc_io_caps);
  ASSERT_EQ(BtIoCap::NO_INPUT_NO_OUTPUT, cfm_req.rmt_io_caps);
}

TEST_F(BtaSecTest, bta_dm_sp_cback__BTM_SP_CFM_REQ_EVT_WithoutName_RNRSuccess) {
  constexpr uint32_t kNumVal = 1234;
  static bool callback_sent = false;
  reset_mock_btm_client_interface();

  EXPECT_CALL(mock_stack_rnr_interface_, BTM_ReadRemoteDeviceName(_, _, _))
          .WillOnce(Return(tBTM_STATUS::BTM_CMD_STARTED));

  static tBTA_DM_SP_CFM_REQ cfm_req{};
  bta_dm_sec_enable([](tBTA_DM_SEC_EVT /*event*/, tBTA_DM_SEC* p_data) {
    callback_sent = true;
    cfm_req = p_data->cfm_req;
  });

  tBTM_SP_EVT_DATA data = {
          .cfm_req =
                  {
                          // tBTM_SP_CFM_REQ
                          .bd_addr = kRawAddress,
                          .dev_class = {},
                          .bd_name = {0},  // No name available
                          .num_val = kNumVal,
                          .just_works = false,
                          .loc_auth_req = BTM_AUTH_SP_YES,
                          .rmt_auth_req = BTM_AUTH_SP_YES,
                          .loc_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                          .rmt_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                  },
  };
  data.cfm_req.dev_class = kDeviceClass;

  ASSERT_EQ(
          btm_status_text(tBTM_STATUS::BTM_CMD_STARTED),
          btm_status_text(bluetooth::legacy::testing::bta_dm_sp_cback(BTM_SP_CFM_REQ_EVT, &data)));
  ASSERT_EQ(kNumVal, bta_dm_sec_cb.num_val);
  ASSERT_FALSE(callback_sent);
}

TEST_F(BtaSecTest, bta_dm_sp_cback__BTM_SP_CFM_REQ_EVT_WithoutName_RNRFail) {
  constexpr uint32_t kNumVal = 1234;
  static bool callback_sent = false;

  EXPECT_CALL(mock_stack_rnr_interface_, BTM_ReadRemoteDeviceName(_, _, _))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  static tBTA_DM_SP_CFM_REQ cfm_req{};
  bta_dm_sec_enable([](tBTA_DM_SEC_EVT /*event*/, tBTA_DM_SEC* p_data) {
    callback_sent = true;
    cfm_req = p_data->cfm_req;
  });

  tBTM_SP_EVT_DATA data = {
          .cfm_req =
                  {
                          // tBTM_SP_CFM_REQ
                          .bd_addr = kRawAddress,
                          .dev_class = {},
                          .bd_name = {0},
                          .num_val = kNumVal,
                          .just_works = false,
                          .loc_auth_req = BTM_AUTH_SP_YES,
                          .rmt_auth_req = BTM_AUTH_SP_YES,
                          .loc_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                          .rmt_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT,
                  },
  };
  data.cfm_req.dev_class = kDeviceClass;

  ASSERT_EQ(
          btm_status_text(tBTM_STATUS::BTM_CMD_STARTED),
          btm_status_text(bluetooth::legacy::testing::bta_dm_sp_cback(BTM_SP_CFM_REQ_EVT, &data)));
  ASSERT_EQ(kNumVal, bta_dm_sec_cb.num_val);
  ASSERT_TRUE(callback_sent);

  ASSERT_EQ(kRawAddress, cfm_req.bd_addr);
  ASSERT_THAT(cfm_req.dev_class, ElementsAre(kDeviceClass[0], kDeviceClass[1], kDeviceClass[2]));
  ASSERT_EQ(kNumVal, cfm_req.num_val);
  ASSERT_EQ(false, cfm_req.just_works);
  ASSERT_EQ(BTM_AUTH_SP_YES, cfm_req.loc_auth_req);
  ASSERT_EQ(BTM_AUTH_SP_YES, cfm_req.rmt_auth_req);
  ASSERT_EQ(BtIoCap::NO_INPUT_NO_OUTPUT, cfm_req.loc_io_caps);
  ASSERT_EQ(BtIoCap::NO_INPUT_NO_OUTPUT, cfm_req.rmt_io_caps);
}

TEST_F(BtaSecTest, bta_dm_sp_cback__BTM_SP_KEY_NOTIF_EVT) {
  constexpr uint32_t kPassKey = 1234;
  static bool callback_sent = false;

  ON_CALL(mock_stack_rnr_interface_, BTM_ReadRemoteDeviceName(_, _, _))
          .WillByDefault(Return(tBTM_STATUS::BTM_CMD_STARTED));

  static tBTA_DM_SP_KEY_NOTIF key_notif{};
  bta_dm_sec_enable([](tBTA_DM_SEC_EVT /*event*/, tBTA_DM_SEC* p_data) {
    callback_sent = true;
    key_notif = p_data->key_notif;
  });

  tBTM_SP_EVT_DATA data = {
          .key_notif =
                  {
                          // tBTM_SP_KEY_NOTIF
                          .bd_addr = kRawAddress,
                          .dev_class = {},
                          .bd_name = {},
                          .passkey = kPassKey,
                  },
  };
  data.key_notif.dev_class = kDeviceClass;
  bd_name_from_char_pointer(data.key_notif.bd_name, kRemoteName);

  ASSERT_EQ(btm_status_text(tBTM_STATUS::BTM_CMD_STARTED),
            btm_status_text(
                    bluetooth::legacy::testing::bta_dm_sp_cback(BTM_SP_KEY_NOTIF_EVT, &data)));
  ASSERT_EQ(kPassKey, bta_dm_sec_cb.num_val);
  ASSERT_TRUE(callback_sent);

  ASSERT_EQ(kRawAddress, key_notif.bd_addr);
  ASSERT_THAT(key_notif.dev_class, ElementsAre(kDeviceClass[0], kDeviceClass[1], kDeviceClass[2]));
  ASSERT_STREQ(kRemoteName, reinterpret_cast<const char*>(key_notif.bd_name));
  ASSERT_EQ(kPassKey, key_notif.passkey);
}
