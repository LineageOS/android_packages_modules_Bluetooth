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

#include <memory>

#include "bta/jv/bta_jv_int.h"
#include "bta_jv_api.h"
#include "osi/include/allocator.h"
#include "stack/include/sdp_status.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_stack_sdp_legacy_api.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

namespace {
const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const RawAddress kRawAddress2 =
    RawAddress({0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc});
const bluetooth::Uuid kUuid = bluetooth::Uuid::From16Bit(0x1234);
const bluetooth::Uuid kUuid2 = bluetooth::Uuid::From16Bit(0x789a);

constexpr uint32_t kSlotId = 0x1234568;
constexpr uint8_t kScn = 123;

}  // namespace

namespace bluetooth::legacy::testing {

void bta_jv_start_discovery_cback(const RawAddress& bd_addr, tSDP_RESULT result,
                                  const void* user_data);

}  // namespace bluetooth::legacy::testing

class FakeSdp {
 public:
  FakeSdp() {
    test::mock::stack_sdp_legacy::api_ = {
        .service = {
            .SDP_InitDiscoveryDb = [](tSDP_DISCOVERY_DB*, uint32_t, uint16_t,
                                      const bluetooth::Uuid*, uint16_t,
                                      const uint16_t*) -> bool { return true; },
            .SDP_CancelServiceSearch = nullptr,
            .SDP_ServiceSearchRequest = nullptr,
            .SDP_ServiceSearchAttributeRequest = nullptr,
            .SDP_ServiceSearchAttributeRequest2 =
                [](const RawAddress& /* p_bd_addr */,
                   tSDP_DISCOVERY_DB* /* p_db */,
                   tSDP_DISC_CMPL_CB2* /* p_cb2 */, const void* user_data) {
                  if (user_data) osi_free((void*)user_data);
                  return true;
                },
        },
        .db =
            {
                .SDP_FindServiceInDb = nullptr,
                .SDP_FindServiceUUIDInDb =
                    [](const tSDP_DISCOVERY_DB* /* p_db */,
                       const bluetooth::Uuid& /* uuid */,
                       tSDP_DISC_REC* /* p_start_rec */) -> tSDP_DISC_REC* {
                  return nullptr;
                },
                .SDP_FindServiceInDb_128bit = nullptr,
            },
        .record =
            {
                .SDP_FindAttributeInRec = nullptr,
                .SDP_FindServiceUUIDInRec_128bit = nullptr,
                .SDP_FindProtocolListElemInRec =
                    [](const tSDP_DISC_REC* /* p_rec */,
                       uint16_t /* layer_uuid */,
                       tSDP_PROTOCOL_ELEM* /* p_elem */) -> bool {
                  return false;
                },
                .SDP_FindProfileVersionInRec = nullptr,
                .SDP_FindServiceUUIDInRec = nullptr,
            },
        .handle =
            {
                .SDP_CreateRecord = nullptr,
                .SDP_DeleteRecord = nullptr,
                .SDP_AddAttribute = nullptr,
                .SDP_AddSequence = nullptr,
                .SDP_AddUuidSequence = nullptr,
                .SDP_AddProtocolList = nullptr,
                .SDP_AddAdditionProtoLists = nullptr,
                .SDP_AddProfileDescriptorList = nullptr,
                .SDP_AddLanguageBaseAttrIDList = nullptr,
                .SDP_AddServiceClassIdList = nullptr,
            },
        .device_id =
            {
                .SDP_SetLocalDiRecord = nullptr,
                .SDP_DiDiscover = nullptr,
                .SDP_GetNumDiRecords = nullptr,
                .SDP_GetDiRecord = nullptr,
            },
    };
  }

  ~FakeSdp() { test::mock::stack_sdp_legacy::api_ = {}; }
};

class BtaJvMockAndFakeTest : public ::testing::Test {
 protected:
  void SetUp() override {
    reset_mock_function_count_map();
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
    fake_sdp_ = std::make_unique<FakeSdp>();
  }

  void TearDown() override {}

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
  std::unique_ptr<FakeSdp> fake_sdp_;
};

class BtaJvTest : public BtaJvMockAndFakeTest {
 protected:
  void SetUp() override {
    BtaJvMockAndFakeTest::SetUp();
    bta_jv_cb.sdp_cb = {};
  }

  void TearDown() override {
    bta_jv_cb.sdp_cb = {};
    BtaJvMockAndFakeTest::TearDown();
  }
};

TEST_F(BtaJvTest, bta_jv_start_discovery_cback__no_callback) {
  uint32_t* user_data = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *user_data = 0x12345678;

  bta_jv_enable(nullptr);
  bluetooth::legacy::testing::bta_jv_start_discovery_cback(
      kRawAddress, SDP_SUCCESS, (const void*)user_data);
}

TEST_F(BtaJvTest,
       bta_jv_start_discovery_cback__with_callback_success_no_record) {
  uint32_t* user_data = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *user_data = kSlotId;

  // Ensure that there was an sdp active
  bta_jv_cb.sdp_cb = {
      .sdp_active = true,
      .bd_addr = kRawAddress,
      .uuid = kUuid,
  };
  bta_jv_enable([](tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t id) {
    switch (event) {
      case BTA_JV_DISCOVERY_COMP_EVT:
        ASSERT_EQ(p_data->disc_comp.status, tBTA_JV_STATUS::FAILURE);
        ASSERT_EQ(kSlotId, id);
        break;

      case BTA_JV_ENABLE_EVT:
        ASSERT_EQ(p_data->disc_comp.status, tBTA_JV_STATUS::SUCCESS);
        ASSERT_EQ(0U, id);
        break;

      default:
        FAIL();
    }
  });
  bluetooth::legacy::testing::bta_jv_start_discovery_cback(
      kRawAddress, SDP_SUCCESS, (const void*)user_data);
}

TEST_F(BtaJvTest,
       bta_jv_start_discovery_cback__with_callback_success_with_record) {
  uint32_t* user_data = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *user_data = kSlotId;

  static tSDP_DISC_REC sdp_disc_rec = {
      .p_first_attr = nullptr,
      .p_next_rec = nullptr,
      .time_read = 1,
      .remote_bd_addr = RawAddress::kAny,
  };

  test::mock::stack_sdp_legacy::api_.db.SDP_FindServiceUUIDInDb =
      [](const tSDP_DISCOVERY_DB* /* p_db */, const bluetooth::Uuid& /* uuid */,
         tSDP_DISC_REC* /* p_start_rec */) -> tSDP_DISC_REC* {
    return &sdp_disc_rec;
  };

  test::mock::stack_sdp_legacy::api_.record.SDP_FindProtocolListElemInRec =
      [](const tSDP_DISC_REC* /* p_rec */, uint16_t /* layer_uuid */,
         tSDP_PROTOCOL_ELEM* p_elem) -> bool {
    p_elem->params[0] = (uint16_t)kScn;
    return true;
  };

  // Ensure that there was an sdp active
  bta_jv_cb.sdp_cb = {
      .sdp_active = true,
      .bd_addr = kRawAddress,
      .uuid = kUuid,
  };
  bta_jv_enable([](tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t id) {
    switch (event) {
      case BTA_JV_DISCOVERY_COMP_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::SUCCESS, p_data->disc_comp.status);
        ASSERT_EQ(kScn, p_data->disc_comp.scn);
        ASSERT_EQ(kSlotId, id);
        break;

      case BTA_JV_ENABLE_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::SUCCESS, p_data->disc_comp.status);
        ASSERT_EQ(0U, id);
        break;

      default:
        FAIL();
    }
  });
  bluetooth::legacy::testing::bta_jv_start_discovery_cback(
      kRawAddress, SDP_SUCCESS, (const void*)user_data);
}

TEST_F(BtaJvTest, bta_jv_start_discovery_cback__with_callback_failure) {
  tSDP_RESULT result = SDP_CONN_FAILED;
  uint32_t* user_data = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *user_data = kSlotId;

  // Ensure that there was an sdp active
  bta_jv_cb.sdp_cb = {
      .sdp_active = true,
      .bd_addr = kRawAddress,
      .uuid = kUuid,
  };
  bta_jv_enable([](tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t id) {
    switch (event) {
      case BTA_JV_DISCOVERY_COMP_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::FAILURE, p_data->disc_comp.status);
        ASSERT_EQ(kSlotId, id);
        break;

      case BTA_JV_ENABLE_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::SUCCESS, p_data->disc_comp.status);
        ASSERT_EQ(0U, id);
        break;

      default:
        FAIL();
    }
  });
  bluetooth::legacy::testing::bta_jv_start_discovery_cback(
      kRawAddress, result, (const void*)user_data);
}

TEST_F(BtaJvTest, bta_jv_start_discovery__idle) {
  bluetooth::Uuid uuid_list[1] = {
      kUuid,
  };
  uint16_t num_uuid = (uint16_t)(sizeof(uuid_list) / sizeof(uuid_list[0]));

  bta_jv_start_discovery(kRawAddress, num_uuid, uuid_list, kSlotId);

  ASSERT_EQ(true, bta_jv_cb.sdp_cb.sdp_active);
  ASSERT_EQ(kRawAddress, bta_jv_cb.sdp_cb.bd_addr);
  ASSERT_EQ(kUuid, bta_jv_cb.sdp_cb.uuid);
}

TEST_F(BtaJvTest, bta_jv_start_discovery__idle_failed_to_start) {
  bluetooth::Uuid uuid_list[1] = {
      kUuid,
  };
  uint16_t num_uuid = (uint16_t)(sizeof(uuid_list) / sizeof(uuid_list[0]));

  test::mock::stack_sdp_legacy::api_.service
      .SDP_ServiceSearchAttributeRequest2 =
      [](const RawAddress& /* p_bd_addr */, tSDP_DISCOVERY_DB* /* p_db */,
         tSDP_DISC_CMPL_CB2* /* p_cb2 */,
         const void* /* user_data */) { return false; };

  bta_jv_enable([](tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t id) {
    switch (event) {
      case BTA_JV_DISCOVERY_COMP_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::FAILURE, p_data->disc_comp.status);
        ASSERT_EQ(kSlotId, id);
        break;

      case BTA_JV_ENABLE_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::SUCCESS, p_data->disc_comp.status);
        ASSERT_EQ(0U, id);
        break;

      default:
        FAIL();
    }
  });
  bta_jv_start_discovery(kRawAddress2, num_uuid, uuid_list, kSlotId);

  ASSERT_EQ(false, bta_jv_cb.sdp_cb.sdp_active);
  ASSERT_EQ(RawAddress::kEmpty, bta_jv_cb.sdp_cb.bd_addr);
  ASSERT_EQ(bluetooth::Uuid::kEmpty, bta_jv_cb.sdp_cb.uuid);
}

TEST_F(BtaJvTest, bta_jv_start_discovery__already_active) {
  bta_jv_cb.sdp_cb = {
      .sdp_active = true,
      .bd_addr = kRawAddress,
      .uuid = kUuid,
  };

  bluetooth::Uuid uuid_list[1] = {
      kUuid2,
  };
  uint16_t num_uuid = (uint16_t)(sizeof(uuid_list) / sizeof(uuid_list[0]));

  bta_jv_enable([](tBTA_JV_EVT event, tBTA_JV* p_data, uint32_t id) {
    switch (event) {
      case BTA_JV_DISCOVERY_COMP_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::BUSY, p_data->disc_comp.status);
        ASSERT_EQ(kSlotId, id);
        break;

      case BTA_JV_ENABLE_EVT:
        ASSERT_EQ(tBTA_JV_STATUS::SUCCESS, p_data->disc_comp.status);
        ASSERT_EQ(0U, id);
        break;

      default:
        FAIL();
    }
  });
  bta_jv_start_discovery(kRawAddress2, num_uuid, uuid_list, kSlotId);

  ASSERT_EQ(true, bta_jv_cb.sdp_cb.sdp_active);
  ASSERT_EQ(kRawAddress, bta_jv_cb.sdp_cb.bd_addr);
  ASSERT_EQ(kUuid, bta_jv_cb.sdp_cb.uuid);
}
