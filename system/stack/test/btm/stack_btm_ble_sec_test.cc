/*
 * Copyright 2025 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stack/btm/btm_ble_sec.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_security.h"
#include "stack/include/btm_ble_sec_api_types.h"
#include "stack/include/main_thread.h"
#include "stack/include/smp_api_types.h"
#include "stack/test/btm/btm_test_fixtures.h"

using namespace bluetooth;
using ::testing::_;
using ::testing::Return;

class MockBtmLeCallback {
public:
  MOCK_METHOD(tBTM_STATUS, Callback,
              (tBTM_LE_EVT event, const RawAddress& bda, tBTM_LE_EVT_DATA* p_data));
};

static MockBtmLeCallback* p_mock_le_callback = nullptr;

static tBTM_STATUS StaticLeCallback(tBTM_LE_EVT event, const RawAddress& bda,
                                    tBTM_LE_EVT_DATA* p_data) {
  if (p_mock_le_callback) {
    return p_mock_le_callback->Callback(event, bda, p_data);
  }
  return tBTM_STATUS::BTM_SUCCESS;
}

class StackBtmBleSecTest : public BtmWithMocksTest {
protected:
  void SetUp() override {
    main_thread_start_up();
    BtmWithMocksTest::SetUp();
    BTM_Sec_Init();
    p_mock_le_callback = &mock_le_callback_;
    btm_sec_cb.api_.p_le_callback = StaticLeCallback;
  }

  void TearDown() override {
    btm_sec_cb.api_.p_le_callback = nullptr;
    p_mock_le_callback = nullptr;
    BTM_Sec_Free();
    BtmWithMocksTest::TearDown();
    main_thread_shut_down();
  }

  MockBtmLeCallback mock_le_callback_;
};

TEST_F(StackBtmBleSecTest, btm_ble_user_confirmation_req_pairing_state_busy_without_flag) {
  set_com_android_bluetooth_flags_prevent_btm_sec_cb_overwrite_during_pairing(false);

  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = BTM_PAIR_STATE_GET_REM_NAME;
  btm_sec_cb.pairing_flags_ = 0;

  tSMP_EVT_DATA smp_data;
  smp_data.passkey = 123456;

  EXPECT_CALL(mock_le_callback_,
              Callback(static_cast<tBTM_LE_EVT>(SMP_PASSKEY_REQ_EVT), bd_addr, _))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  btm_proc_smp_cback(SMP_PASSKEY_REQ_EVT, bd_addr, &smp_data);

  EXPECT_TRUE(p_device->sec_rec.sec_flags & BTM_SEC_LE_AUTHENTICATED);
  EXPECT_EQ(tSECURITY_STATE::AUTHENTICATING, p_device->sec_rec.le_link);
  EXPECT_EQ(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
  EXPECT_EQ(BT_TRANSPORT_LE, btm_sec_cb.link_spec_.transport);
  EXPECT_TRUE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
}

TEST_F(StackBtmBleSecTest, btm_ble_sec_req_pairing_state_idle) {
  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = BTM_PAIR_STATE_IDLE;
  btm_sec_cb.pairing_flags_ = 0;

  tSMP_EVT_DATA smp_data;

  EXPECT_CALL(mock_le_callback_,
              Callback(static_cast<tBTM_LE_EVT>(SMP_SEC_REQUEST_EVT), bd_addr, _))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  btm_proc_smp_cback(SMP_SEC_REQUEST_EVT, bd_addr, &smp_data);

  EXPECT_EQ(tSECURITY_STATE::AUTHENTICATING, p_device->sec_rec.le_link);
  EXPECT_EQ(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
  EXPECT_EQ(BT_TRANSPORT_LE, btm_sec_cb.link_spec_.transport);
  EXPECT_TRUE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
}

TEST_F(StackBtmBleSecTest, btm_ble_sec_req_pairing_state_busy) {
  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = BTM_PAIR_STATE_GET_REM_NAME;
  btm_sec_cb.pairing_flags_ = 0;

  tSMP_EVT_DATA smp_data;

  EXPECT_CALL(mock_le_callback_,
              Callback(static_cast<tBTM_LE_EVT>(SMP_SEC_REQUEST_EVT), bd_addr, _))
          .Times(0);

  btm_proc_smp_cback(SMP_SEC_REQUEST_EVT, bd_addr, &smp_data);

  // Ensure link_spec was NOT updated to the new device
  EXPECT_NE(tSECURITY_STATE::AUTHENTICATING, p_device->sec_rec.le_link);
  EXPECT_NE(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
  EXPECT_FALSE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
}

TEST_F(StackBtmBleSecTest, btm_ble_consent_req_pairing_state_busy_without_flag) {
  set_com_android_bluetooth_flags_prevent_btm_sec_cb_overwrite_during_pairing(false);

  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = BTM_PAIR_STATE_GET_REM_NAME;
  btm_sec_cb.pairing_flags_ = 0;

  tSMP_EVT_DATA smp_data;

  EXPECT_CALL(mock_le_callback_,
              Callback(static_cast<tBTM_LE_EVT>(SMP_CONSENT_REQ_EVT), bd_addr, _))
          .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));

  btm_proc_smp_cback(SMP_CONSENT_REQ_EVT, bd_addr, &smp_data);

  EXPECT_EQ(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
  EXPECT_EQ(BT_TRANSPORT_LE, btm_sec_cb.link_spec_.transport);
  EXPECT_TRUE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
}

class StackBtmBleSecParamTest
    : public StackBtmBleSecTest,
      public ::testing::WithParamInterface<std::tuple<tBTM_PAIRING_STATE, bool, tBT_TRANSPORT>> {
protected:
  void SetUp() override {
    StackBtmBleSecTest::SetUp();
    set_com_android_bluetooth_flags_prevent_btm_sec_cb_overwrite_during_pairing(true);
  }
};

TEST_P(StackBtmBleSecParamTest, btm_ble_user_confirmation_req) {
  auto [pairing_state, is_same_addr, transport] = GetParam();

  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  const RawAddress other_addr = RawAddress({0x66, 0x55, 0x44, 0x33, 0x22, 0x11});

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = pairing_state;
  btm_sec_cb.link_spec_.addrt.bda = is_same_addr ? bd_addr : other_addr;
  btm_sec_cb.link_spec_.transport = transport;
  btm_sec_cb.pairing_flags_ = 0;

  bool expect_processed = true;
  if (pairing_state != BTM_PAIR_STATE_IDLE) {
    if (!is_same_addr || transport != BT_TRANSPORT_LE) {
      expect_processed = false;
    }
  }

  tSMP_EVT_DATA smp_data;
  smp_data.passkey = 123456;

  if (expect_processed) {
    EXPECT_CALL(mock_le_callback_,
                Callback(static_cast<tBTM_LE_EVT>(SMP_PASSKEY_REQ_EVT), bd_addr, _))
            .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));
  } else {
    EXPECT_CALL(mock_le_callback_,
                Callback(static_cast<tBTM_LE_EVT>(SMP_PASSKEY_REQ_EVT), bd_addr, _))
            .Times(0);
  }

  btm_proc_smp_cback(SMP_PASSKEY_REQ_EVT, bd_addr, &smp_data);

  if (expect_processed) {
    EXPECT_TRUE(p_device->sec_rec.sec_flags & BTM_SEC_LE_AUTHENTICATED);
    EXPECT_EQ(tSECURITY_STATE::AUTHENTICATING, p_device->sec_rec.le_link);
    EXPECT_EQ(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
    EXPECT_EQ(BT_TRANSPORT_LE, btm_sec_cb.link_spec_.transport);
    EXPECT_TRUE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
  } else {
    EXPECT_FALSE(p_device->sec_rec.sec_flags & BTM_SEC_LE_AUTHENTICATED);
    EXPECT_NE(tSECURITY_STATE::AUTHENTICATING, p_device->sec_rec.le_link);
  }
}

TEST_P(StackBtmBleSecParamTest, btm_ble_consent_req) {
  auto [pairing_state, is_same_addr, transport] = GetParam();

  const RawAddress bd_addr = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  const RawAddress other_addr = RawAddress({0x66, 0x55, 0x44, 0x33, 0x22, 0x11});

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  btm_sec_cb.pairing_state_ = pairing_state;
  btm_sec_cb.link_spec_.addrt.bda = is_same_addr ? bd_addr : other_addr;
  btm_sec_cb.link_spec_.transport = transport;
  btm_sec_cb.pairing_flags_ = 0;

  bool expect_processed = true;
  if (pairing_state != BTM_PAIR_STATE_IDLE) {
    if (!is_same_addr || transport != BT_TRANSPORT_LE) {
      expect_processed = false;
    }
  }

  tSMP_EVT_DATA smp_data;

  if (expect_processed) {
    EXPECT_CALL(mock_le_callback_,
                Callback(static_cast<tBTM_LE_EVT>(SMP_CONSENT_REQ_EVT), bd_addr, _))
            .WillOnce(Return(tBTM_STATUS::BTM_SUCCESS));
  } else {
    EXPECT_CALL(mock_le_callback_,
                Callback(static_cast<tBTM_LE_EVT>(SMP_CONSENT_REQ_EVT), bd_addr, _))
            .Times(0);
  }

  btm_proc_smp_cback(SMP_CONSENT_REQ_EVT, bd_addr, &smp_data);

  if (expect_processed) {
    EXPECT_EQ(bd_addr, btm_sec_cb.link_spec_.addrt.bda);
    EXPECT_EQ(BT_TRANSPORT_LE, btm_sec_cb.link_spec_.transport);
    EXPECT_TRUE(btm_sec_cb.pairing_flags_ & BTM_PAIR_FLAGS_LE_ACTIVE);
  } else {
    // If ignored, verify no unexpected state changes to current device logic
  }
}

INSTANTIATE_TEST_SUITE_P(
        StackBtmBleSecParamTest, StackBtmBleSecParamTest,
        ::testing::Combine(::testing::Values(BTM_PAIR_STATE_IDLE, BTM_PAIR_STATE_GET_REM_NAME),
                           ::testing::Bool(),
                           ::testing::Values(BT_TRANSPORT_LE, BT_TRANSPORT_BR_EDR)));
