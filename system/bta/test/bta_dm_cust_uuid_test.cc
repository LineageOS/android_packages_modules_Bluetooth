/******************************************************************************
 *
 *  Copyright 2021 The Android Open Source Project
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
 ******************************************************************************/

#include <bluetooth/types/uuid.h>
#include <gtest/gtest.h>

#include "bta/dm/bta_dm_int.h"
#include "bta/test/bta_test_fixtures.h"

using bluetooth::Uuid;

namespace {
uint32_t handle1 = 1;
uint32_t handle2 = 2;
static constexpr Uuid uuid1 =
        Uuid::From128BitBE(Uuid::UUID128Bit{{0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
                                             0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}});
static constexpr Uuid uuid2 =
        Uuid::From128BitBE(Uuid::UUID128Bit{{0x00, 0x00, 0x00, 0x00, 0x22, 0x22, 0x22, 0x22, 0x33,
                                             0x33, 0x55, 0x55, 0x55, 0x55, 0x55, 0x59}});
// Test constants for 16-bit and 32-bit UUIDs
static constexpr Uuid valid_uuid16 = Uuid::From16Bit(0x1800);  // Generic Access
static const Uuid valid_uuid32 = Uuid::From32Bit(0x00001801);  // Generic Attribute
}  // namespace

// Test we can remove/add 128 bit custom UUID from/to bta_dm_cb.bta_custom_uuid
TEST_F(BtaWithMocksTest, test_add_remove_cust_uuid) {
  tBTA_CUSTOM_UUID& curr0 = bta_dm_cb.bta_custom_uuid[0];
  tBTA_CUSTOM_UUID& curr1 = bta_dm_cb.bta_custom_uuid[1];
  tBTA_CUSTOM_UUID curr0_expect = {uuid1, handle1};
  tBTA_CUSTOM_UUID curr1_expect = {uuid2, handle2};
  // Add first 128 bit custom UUID
  bta_dm_eir_update_cust_uuid(curr0_expect, true);
  ASSERT_STREQ(uuid1.ToString().c_str(), curr0.custom_uuid.ToString().c_str());
  // Add second 128 bit custom UUID
  bta_dm_eir_update_cust_uuid(curr1_expect, true);
  ASSERT_STREQ(uuid2.ToString().c_str(), curr1.custom_uuid.ToString().c_str());

  curr0_expect.custom_uuid = Uuid::kEmpty;
  curr1_expect.custom_uuid = Uuid::kEmpty;
  // Remove first 128 bit custom UUID
  bta_dm_eir_update_cust_uuid(curr0_expect, false);
  ASSERT_STREQ(Uuid::kEmpty.ToString().c_str(), curr0.custom_uuid.ToString().c_str());
  // Remove second 128 bit custom UUID
  bta_dm_eir_update_cust_uuid(curr1_expect, false);
  ASSERT_STREQ(Uuid::kEmpty.ToString().c_str(), curr1.custom_uuid.ToString().c_str());
}

TEST_F(BtaWithMocksTest, test_eir_ignores_empty_custom_uuid) {
  tBTA_CUSTOM_UUID valid_uuid = {uuid1, handle1};
  tBTA_CUSTOM_UUID empty_uuid = {Uuid::kEmpty, handle2};

  EXPECT_CALL(mock_btm_client_interface_, BTM_WriteEIR).WillRepeatedly([](BT_HDR* p_buf) {
    osi_free(p_buf);
    return tBTM_STATUS::BTM_SUCCESS;
  });

  bta_dm_eir_update_cust_uuid(valid_uuid, true);
  bta_dm_eir_update_cust_uuid(empty_uuid, true);

  ASSERT_STREQ(uuid1.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[0].custom_uuid.ToString().c_str());
  ASSERT_STREQ(Uuid::kEmpty.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[1].custom_uuid.ToString().c_str());
}

TEST_F(BtaWithMocksTest, test_eir_ignores_zero_16_and_32_bit_uuid) {
  tBTA_CUSTOM_UUID valid_16 = {valid_uuid16, 1};
  tBTA_CUSTOM_UUID zero_16 = {Uuid::From16Bit(0x0000), 2};
  tBTA_CUSTOM_UUID valid_32 = {valid_uuid32, 3};
  tBTA_CUSTOM_UUID zero_32 = {Uuid::From32Bit(0x00000000), 4};

  EXPECT_CALL(mock_btm_client_interface_, BTM_WriteEIR).WillRepeatedly([](BT_HDR* p_buf) {
    osi_free(p_buf);
    return tBTM_STATUS::BTM_SUCCESS;
  });

  bta_dm_eir_update_cust_uuid(valid_16, true);
  bta_dm_eir_update_cust_uuid(zero_16, true);
  bta_dm_eir_update_cust_uuid(valid_32, true);
  bta_dm_eir_update_cust_uuid(zero_32, true);

  ASSERT_STREQ(valid_uuid16.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[0].custom_uuid.ToString().c_str());
  ASSERT_STREQ(Uuid::From16Bit(0x0000).ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[1].custom_uuid.ToString().c_str());
  ASSERT_STREQ(valid_uuid32.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[2].custom_uuid.ToString().c_str());
  ASSERT_STREQ(Uuid::From32Bit(0x00000000).ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[3].custom_uuid.ToString().c_str());
}

TEST_F(BtaWithMocksTest, test_eir_ignores_uuid_with_zero_handle) {
  tBTA_CUSTOM_UUID valid_16 = {valid_uuid16, 1};
  tBTA_CUSTOM_UUID valid_32 = {valid_uuid32, 2};
  tBTA_CUSTOM_UUID valid_128 = {uuid1, 3};

  EXPECT_CALL(mock_btm_client_interface_, BTM_WriteEIR).WillRepeatedly([](BT_HDR* p_buf) {
    osi_free(p_buf);
    return tBTM_STATUS::BTM_SUCCESS;
  });

  bta_dm_eir_update_cust_uuid(valid_16, true);
  bta_dm_eir_update_cust_uuid(valid_32, true);
  bta_dm_eir_update_cust_uuid(valid_128, true);

  bta_dm_eir_update_cust_uuid(valid_16, false);
  bta_dm_eir_update_cust_uuid(valid_32, false);
  bta_dm_eir_update_cust_uuid(valid_128, false);

  ASSERT_STREQ(valid_uuid16.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[0].custom_uuid.ToString().c_str());
  ASSERT_STREQ(valid_uuid32.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[1].custom_uuid.ToString().c_str());
  ASSERT_STREQ(uuid1.ToString().c_str(),
               bta_dm_cb.bta_custom_uuid[2].custom_uuid.ToString().c_str());

  ASSERT_EQ(0u, bta_dm_cb.bta_custom_uuid[0].handle);
  ASSERT_EQ(0u, bta_dm_cb.bta_custom_uuid[1].handle);
  ASSERT_EQ(0u, bta_dm_cb.bta_custom_uuid[2].handle);
}
