/*
 *  Copyright 2024 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_security.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/mock/mock_main_shim_entry.h"

class StackBtmDevTest : public BtmWithMocksTest {
protected:
  void SetUp() override { BtmWithMocksTest::SetUp(); }
  void TearDown() override { BtmWithMocksTest::TearDown(); }
};

TEST_F(StackBtmDevTest, btm_sec_allocate_dev_rec__no_list) {
  const RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  ASSERT_EQ(nullptr, btm_sec_allocate_dev_rec(bd_addr));
  ::BtmSecurity::Get().Init(BTM_SEC_MODE_SC);
  ::BtmSecurity::Get().Free();
  ASSERT_EQ(nullptr, btm_sec_allocate_dev_rec(bd_addr));
}

TEST_F(StackBtmDevTest, btm_sec_allocate_dev_rec__with_list) {
  const RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  ::BtmSecurity::Get().Init(BTM_SEC_MODE_SC);
  ASSERT_NE(nullptr, btm_sec_allocate_dev_rec(bd_addr));
  ::BtmSecurity::Get().Free();
}

TEST_F(StackBtmDevTest, DumpsysRecord) { DumpsysRecord(STDOUT_FILENO); }
