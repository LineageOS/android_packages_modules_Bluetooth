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

#include <base/strings/stringprintf.h>
#include <gtest/gtest.h>
#include <stdlib.h>

#include "hci_error_code.h"
#include "stack/btm/btm_int_types.h"
#include "stack/include/inq_hci_link_interface.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/fake/fake_looper.h"
#include "test/mock/mock_osi_allocator.h"
#include "test/mock/mock_osi_thread.h"
#include "types/raw_address.h"

extern tBTM_CB btm_cb;

namespace {
const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const RawAddress kRawAddress2 =
    RawAddress({0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc});
const BD_NAME kBdName = {'A', ' ', 'B', 'd', ' ', 'N', 'a', 'm', 'e', '\0'};
const BD_NAME kEmptyName = "";

tBTM_REMOTE_DEV_NAME gBTM_REMOTE_DEV_NAME{};
bool gBTM_REMOTE_DEV_NAME_sent{false};

}  // namespace

class BtmInqTest : public BtmWithMocksTest {
 protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    btm_cb = {};
  }

  void TearDown() override { BtmWithMocksTest::TearDown(); }
};

class BtmInqActiveTest : public BtmInqTest {
 protected:
  void SetUp() override {
    BtmInqTest::SetUp();
    gBTM_REMOTE_DEV_NAME = {};
    gBTM_REMOTE_DEV_NAME_sent = false;

    btm_cb.btm_inq_vars.remname_active = true;
    btm_cb.btm_inq_vars.remname_bda = kRawAddress;
    btm_cb.btm_inq_vars.p_remname_cmpl_cb =
        [](const tBTM_REMOTE_DEV_NAME* name) {
          gBTM_REMOTE_DEV_NAME = *name;
          gBTM_REMOTE_DEV_NAME_sent = true;
        };
  }

  void TearDown() override { BtmInqTest::TearDown(); }
};

TEST_F(BtmInqActiveTest, btm_process_remote_name__typical) {
  btm_process_remote_name(&kRawAddress, kBdName, 0, HCI_SUCCESS);
  ASSERT_FALSE(btm_cb.btm_inq_vars.p_remname_cmpl_cb);
  ASSERT_FALSE(btm_cb.btm_inq_vars.remname_active);
  ASSERT_EQ(btm_cb.btm_inq_vars.remname_bda, RawAddress::kEmpty);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  ASSERT_TRUE(gBTM_REMOTE_DEV_NAME_sent);
  ASSERT_EQ(BTM_SUCCESS, gBTM_REMOTE_DEV_NAME.status);
  ASSERT_EQ(HCI_SUCCESS, gBTM_REMOTE_DEV_NAME.hci_status);
  ASSERT_EQ(kRawAddress, gBTM_REMOTE_DEV_NAME.bd_addr);
  ASSERT_STREQ((char*)kBdName, (char*)gBTM_REMOTE_DEV_NAME.remote_bd_name);
}

TEST_F(BtmInqActiveTest, btm_process_remote_name__no_name) {
  btm_process_remote_name(&kRawAddress, nullptr, 0, HCI_SUCCESS);
  ASSERT_FALSE(btm_cb.btm_inq_vars.p_remname_cmpl_cb);
  ASSERT_FALSE(btm_cb.btm_inq_vars.remname_active);
  ASSERT_EQ(btm_cb.btm_inq_vars.remname_bda, RawAddress::kEmpty);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  ASSERT_TRUE(gBTM_REMOTE_DEV_NAME_sent);
  ASSERT_EQ(BTM_SUCCESS, gBTM_REMOTE_DEV_NAME.status);
  ASSERT_EQ(HCI_SUCCESS, gBTM_REMOTE_DEV_NAME.hci_status);
  ASSERT_EQ(kRawAddress, gBTM_REMOTE_DEV_NAME.bd_addr);
  ASSERT_STREQ((char*)kEmptyName, (char*)gBTM_REMOTE_DEV_NAME.remote_bd_name);
}

TEST_F(BtmInqActiveTest, btm_process_remote_name__bad_status) {
  btm_process_remote_name(&kRawAddress, kBdName, 0, HCI_ERR_PAGE_TIMEOUT);
  ASSERT_FALSE(btm_cb.btm_inq_vars.p_remname_cmpl_cb);
  ASSERT_FALSE(btm_cb.btm_inq_vars.remname_active);
  ASSERT_EQ(btm_cb.btm_inq_vars.remname_bda, RawAddress::kEmpty);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  ASSERT_TRUE(gBTM_REMOTE_DEV_NAME_sent);
  ASSERT_EQ(BTM_BAD_VALUE_RET, gBTM_REMOTE_DEV_NAME.status);
  ASSERT_EQ(HCI_ERR_PAGE_TIMEOUT, gBTM_REMOTE_DEV_NAME.hci_status);
  ASSERT_EQ(kRawAddress, gBTM_REMOTE_DEV_NAME.bd_addr);
  ASSERT_STREQ((char*)kEmptyName, (char*)gBTM_REMOTE_DEV_NAME.remote_bd_name);
}

TEST_F(BtmInqActiveTest, btm_process_remote_name__no_address) {
  btm_process_remote_name(nullptr, kBdName, 0, HCI_SUCCESS);
  ASSERT_FALSE(btm_cb.btm_inq_vars.p_remname_cmpl_cb);
  ASSERT_FALSE(btm_cb.btm_inq_vars.remname_active);
  ASSERT_EQ(btm_cb.btm_inq_vars.remname_bda, RawAddress::kEmpty);
  ASSERT_EQ(1, get_func_call_count("alarm_cancel"));

  ASSERT_TRUE(gBTM_REMOTE_DEV_NAME_sent);
  ASSERT_EQ(BTM_SUCCESS, gBTM_REMOTE_DEV_NAME.status);
  ASSERT_EQ(HCI_SUCCESS, gBTM_REMOTE_DEV_NAME.hci_status);
  ASSERT_EQ(RawAddress::kEmpty, gBTM_REMOTE_DEV_NAME.bd_addr);
  ASSERT_STREQ((char*)kBdName, (char*)gBTM_REMOTE_DEV_NAME.remote_bd_name);
}

TEST_F(BtmInqActiveTest, btm_process_remote_name__different_address) {
  btm_cb.btm_inq_vars.remname_bda = kRawAddress2;
  btm_process_remote_name(&kRawAddress, kBdName, 0, HCI_SUCCESS);
  ASSERT_TRUE(btm_cb.btm_inq_vars.p_remname_cmpl_cb);
  ASSERT_TRUE(btm_cb.btm_inq_vars.remname_active);
  ASSERT_NE(btm_cb.btm_inq_vars.remname_bda, RawAddress::kEmpty);
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  ASSERT_FALSE(gBTM_REMOTE_DEV_NAME_sent);
}
