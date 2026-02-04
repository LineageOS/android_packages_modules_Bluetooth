/*
 * Copyright 2026 The Android Open Source Project
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

#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_security.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_ble_api.h"
#include "stack/test/btm/btm_test_fixtures.h"

// Access internal function to populate inquiry DB
extern tINQ_DB_ENT* btm_inq_db_new(const RawAddress& p_bda, bool is_ble);
static const RawAddress kTestAddress("11:22:33:44:55:66");

namespace {

class StackBtmBleTest : public BtmWithMocksTest {
protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    BtmSecurity::Get().Init(BTM_SEC_MODE_SC);
  }

  void TearDown() override {
    BtmSecurity::Get().Free();
    BtmWithMocksTest::TearDown();
  }
};

TEST_F(StackBtmBleTest, BTM_ReadDevInfo_UnknownDevice) {
  DevInfo info = BTM_ReadDevInfo(kTestAddress);

  EXPECT_EQ(info.addr, kTestAddress);
  EXPECT_EQ(info.device_type, BT_DEVICE_TYPE_BREDR);  // "unknown device, BR/EDR assumed"
}

TEST_F(StackBtmBleTest, BTM_ReadDevInfo_InquiryOnly) {
  // Create inquiry record
  tINQ_DB_ENT* p_ent = btm_inq_db_new(kTestAddress, true);
  ASSERT_NE(p_ent, nullptr);
  p_ent->inq_info.results.device_type = BT_DEVICE_TYPE_BLE;
  p_ent->inq_info.results.ble_addr_type = BLE_ADDR_RANDOM;

  DevInfo info = BTM_ReadDevInfo(kTestAddress);

  EXPECT_EQ(info.device_type, BT_DEVICE_TYPE_BLE);
  EXPECT_EQ(info.addr_type, BLE_ADDR_RANDOM);
}

TEST_F(StackBtmBleTest, BTM_ReadDevInfo_DeviceRecordOnly) {
  BtmDevice* p_dev = btm_sec_allocate_dev_rec(kTestAddress);
  ASSERT_NE(p_dev, nullptr);
  p_dev->device_type = BT_DEVICE_TYPE_BLE;
  p_dev->ble.SetAddressType(BLE_ADDR_PUBLIC);
  // btm_sec_allocate_dev_rec sets pseudo_addr to addr

  DevInfo info = BTM_ReadDevInfo(kTestAddress);

  EXPECT_EQ(info.device_type, BT_DEVICE_TYPE_BLE);
  EXPECT_EQ(info.addr_type, BLE_ADDR_PUBLIC);
}

TEST_F(StackBtmBleTest, BTM_ReadDevInfo_UpdateFromInquiry_FlagEnabled) {
  set_com_android_bluetooth_flags_pairing_transport_selection(true);

  // Device record exists but unknown type
  BtmDevice* p_dev = btm_sec_allocate_dev_rec(kTestAddress);
  ASSERT_NE(p_dev, nullptr);
  p_dev->device_type = BT_DEVICE_TYPE_UNKNOWN;

  // Inquiry record has specific type
  tINQ_DB_ENT* p_ent = btm_inq_db_new(kTestAddress, true);
  ASSERT_NE(p_ent, nullptr);
  p_ent->inq_info.results.device_type = BT_DEVICE_TYPE_BLE;
  p_ent->inq_info.results.ble_addr_type = BLE_ADDR_RANDOM;

  DevInfo info = BTM_ReadDevInfo(kTestAddress);

  // Should update device record and return new type
  EXPECT_EQ(p_dev->device_type, BT_DEVICE_TYPE_BLE);
  EXPECT_EQ(info.device_type, BT_DEVICE_TYPE_BLE);
  EXPECT_EQ(p_dev->ble.AddressType(), BLE_ADDR_RANDOM);
}

}  // namespace
