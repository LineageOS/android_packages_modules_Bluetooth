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

#include <gtest/gtest.h>

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_security.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/mock/mock_main_shim_entry.h"

using namespace bluetooth;

namespace {
const RawAddress kRawAddress = RawAddress("11:22:33:44:55:66");
const uint8_t kBdName[] = "kBdName";
}  // namespace

class BtmSecRmtNameRequestCompleteTest : public BtmWithMocksTest {
protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    main_thread_start_up();
    get_security_client_interface().BTM_Sec_Init();
  }

  void TearDown() override {
    get_security_client_interface().BTM_Sec_Free();
    main_thread_shut_down();
    BtmWithMocksTest::TearDown();
  }
};

static tBTM_STATUS StaticLeCallback(tBTM_LE_EVT, const RawAddress&, tBTM_LE_EVT_DATA*) {
  return tBTM_STATUS::BTM_SUCCESS;
}

static bool pin_callback_called = false;

static tBTM_STATUS StaticPinCallback(const RawAddress&, const DEV_CLASS&, const BD_NAME&, bool,
                                     PairingAlgorithm) {
  pin_callback_called = true;
  return tBTM_STATUS::BTM_SUCCESS;
}

static tBTM_STATUS StaticLinkKeyCallback(const RawAddress&, const BD_NAME&, const LinkKey&, uint8_t,
                                         bool) {
  return tBTM_STATUS::BTM_SUCCESS;
}

static void StaticAuthCompleteCallback(const RawAddress&, const BD_NAME&, tHCI_REASON) {}

static void StaticBondCancelCmplCallback(tBTM_STATUS) {}

static tBTM_STATUS StaticSpCallback(tBTM_SP_EVT, tBTM_SP_EVT_DATA*) {
  return tBTM_STATUS::BTM_SUCCESS;
}

static void StaticLeKeyCallback(uint8_t, tBTM_BLE_LOCAL_KEYS*) {}

static tBTM_STATUS StaticSirkVerificationCallback(const RawAddress&) {
  return tBTM_STATUS::BTM_SUCCESS;
}

TEST_F(BtmSecRmtNameRequestCompleteTest, NullAddress) {
  btm_sec_rmt_name_request_complete(nullptr, kBdName, HCI_SUCCESS);
  // Should not crash
}

TEST_F(BtmSecRmtNameRequestCompleteTest, UnknownDevice) {
  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_SUCCESS);
  // Should not crash, and should not add name if device not found (unless it was in GETTING_NAME
  // state which we haven't set up)
}

TEST_F(BtmSecRmtNameRequestCompleteTest, KnownDeviceSuccess) {
  BtmDevice* p_device = btm_sec_allocate_dev_rec(kRawAddress);
  ASSERT_NE(nullptr, p_device);
  p_device->sec_rec.classic_link = tSECURITY_STATE::GETTING_NAME;

  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_SUCCESS);

  EXPECT_EQ(tSECURITY_STATE::IDLE, p_device->sec_rec.classic_link);
  EXPECT_TRUE(p_device->sec_rec.sec_flags & BTM_SEC_NAME_KNOWN);
  EXPECT_EQ(0, memcmp(p_device->sec_bd_name, kBdName, sizeof(kBdName)));
}

TEST_F(BtmSecRmtNameRequestCompleteTest, KnownDeviceFailure) {
  BtmDevice* p_device = btm_sec_allocate_dev_rec(kRawAddress);
  ASSERT_NE(nullptr, p_device);
  p_device->sec_rec.classic_link = tSECURITY_STATE::GETTING_NAME;

  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_ERR_HW_FAILURE);

  EXPECT_EQ(tSECURITY_STATE::IDLE, p_device->sec_rec.classic_link);
  // Name should not be set if failure, although the function might set it to empty string or keep
  // previous? Looking at code: p_device->sec_bd_name[0] = 0;
  EXPECT_EQ(0, p_device->sec_bd_name[0]);
}

TEST_F(BtmSecRmtNameRequestCompleteTest, WaitLocalPinState) {
  BtmDevice* p_device = btm_sec_allocate_dev_rec(kRawAddress);
  ASSERT_NE(nullptr, p_device);
  p_device->sec_rec.classic_link = tSECURITY_STATE::GETTING_NAME;

  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_WAIT_LOCAL_PIN;
  BtmSecurity::Get().link_spec_.addrt.bda = kRawAddress;
  BtmSecurity::Get().pairing_flags_ = 0;

  // We need to mock the pin callback
  BtmAppReg app_reg = {
          .pin_callback = StaticPinCallback,
          .link_key_callback = StaticLinkKeyCallback,
          .auth_complete_callback = StaticAuthCompleteCallback,
          .bond_cancel_cmpl_callback = StaticBondCancelCmplCallback,
          .sp_callback = StaticSpCallback,
          .le_callback = StaticLeCallback,
          .le_key_callback = StaticLeKeyCallback,
          .sirk_verification_callback = StaticSirkVerificationCallback,
  };
  btm_sec_register(app_reg);

  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_SUCCESS);

  EXPECT_TRUE(pin_callback_called);
  EXPECT_TRUE(BtmSecurity::Get().pairing_flags_ & BTM_PAIR_FLAGS_PIN_REQD);
}
