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

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gtest/gtest.h>
#include <stdlib.h>

#include <cstdint>

#include "os/log.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/sdp_api.h"
#include "stack/sdp/sdp_discovery_db.h"
#include "stack/sdp/sdpint.h"
#include "stack/test/sdp/sdp_packet00.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_osi_allocator.h"
#include "test/mock/mock_stack_l2cap_api.h"

constexpr uint32_t kBtDefaultBufferSize =
    static_cast<uint32_t>(BT_DEFAULT_BUFFER_SIZE);

#define TEST_BT com::android::bluetooth::flags

using bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api;

namespace {

const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
constexpr size_t kSdpDbSize = BTA_DM_SDP_DB_SIZE * 16;
constexpr size_t kSdpPacketStartOffset = 9;
int L2CA_ConnectReq2_cid = 0x42;

class StackSdpParserWithMocksTest : public ::testing::Test {
 protected:
  void SetUp() override {
    reset_mock_function_count_map();
    fake_osi_ = std::make_unique<test::fake::FakeOsi>();
    test::mock::stack_l2cap_api::L2CA_ConnectReq2.body =
        [](uint16_t /* psm */, const RawAddress& /* p_bd_addr */,
           uint16_t /* sec_level */) { return ++L2CA_ConnectReq2_cid; };
    test::mock::stack_l2cap_api::L2CA_DataWrite.body = [](uint16_t /* cid */,
                                                          BT_HDR* p_data) {
      osi_free_and_reset((void**)&p_data);
      return 0;
    };
    test::mock::stack_l2cap_api::L2CA_DisconnectReq.body =
        [](uint16_t /* cid */) { return true; };
    test::mock::stack_l2cap_api::L2CA_Register2.body =
        [](uint16_t psm, const tL2CAP_APPL_INFO& /* p_cb_info */,
           bool /* enable_snoop */, tL2CAP_ERTM_INFO* /* p_ertm_info */,
           uint16_t /* my_mtu */, uint16_t /* required_remote_mtu */,
           uint16_t /* sec_level */) { return psm; };
  }

  void TearDown() override {
    test::mock::stack_l2cap_api::L2CA_Register2 = {};
    test::mock::stack_l2cap_api::L2CA_DisconnectReq = {};
    test::mock::stack_l2cap_api::L2CA_DataWrite = {};
    test::mock::stack_l2cap_api::L2CA_ConnectReq2 = {};
    fake_osi_.reset();
  }

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

class StackSdpParserInitTest : public StackSdpParserWithMocksTest {
 protected:
  void SetUp() override {
    StackSdpParserWithMocksTest::SetUp();
    sdp_init();
    p_db_ = (tSDP_DISCOVERY_DB*)osi_malloc(kSdpDbSize);
  }

  void TearDown() override {
    osi_free(p_db_);
    p_db_ = nullptr;
    sdp_free();
    StackSdpParserWithMocksTest::TearDown();
  }

  tSDP_DISCOVERY_DB* p_db_{nullptr};
};

}  // namespace

TEST_F(StackSdpParserInitTest, SDP_InitDiscoveryDb) {
  bluetooth::Uuid uuid;
  const bool success = get_legacy_stack_sdp_api()->service.SDP_InitDiscoveryDb(
      p_db_, kBtDefaultBufferSize, 1, &uuid, 0, nullptr);
  ASSERT_TRUE(success);

  ASSERT_TRUE(get_legacy_stack_sdp_api()->service.SDP_ServiceSearchRequest(
      kRawAddress, p_db_,
      [](const RawAddress& /* bd_addr */, tSDP_RESULT /* result */) {}));
}

class StackSdpAsClientParseTest : public StackSdpParserInitTest {
 protected:
  void SetUp() override {
    StackSdpParserInitTest::SetUp();
    ASSERT_TRUE(get_legacy_stack_sdp_api()->service.SDP_InitDiscoveryDb(
        p_db_, kSdpDbSize, 1, p_uuid_list, 0, nullptr));
    ASSERT_TRUE(get_legacy_stack_sdp_api()->service.SDP_ServiceSearchRequest(
        kRawAddress, p_db_,
        [](const RawAddress& /* bd_addr */, tSDP_RESULT /* result */) {}));

    // Fast forward to to accept SDP responses as originator
    p_ccb_ = sdpu_find_ccb_by_db(p_db_);
    ASSERT_NE(nullptr, p_ccb_);
    p_ccb_->disc_state = SDP_DISC_WAIT_SEARCH_ATTR;
    p_ccb_->con_state = SDP_STATE_CONNECTED;
    p_ccb_->con_flags = SDP_FLAGS_IS_ORIG;
  }

  void TearDown() override {
    sdpu_release_ccb(*p_ccb_);
    StackSdpParserInitTest::TearDown();
  }

  tCONN_CB* p_ccb_{nullptr};
  const bluetooth::Uuid p_uuid_list[1]{
      bluetooth::Uuid::GetRandom(),
  };

  void parse_sdp_responses(const bluetooth::testing::raw_packet_t* pkts,
                           size_t num_pkts) {
    for (size_t i = 0; i < num_pkts; i++) {
      const bluetooth::testing::raw_packet_t* pkt = &pkts[i];
      char* data = (char*)osi_malloc(pkt->len + sizeof(BT_HDR));
      BT_HDR* bt_hdr = (BT_HDR*)data;
      *bt_hdr = {
          .event = 0,
          .len = (uint16_t)pkt->len,
          .offset = 0,
          .layer_specific = 0,
      };
      uint8_t* payload = (uint8_t*)(bt_hdr + 1);
      memcpy(payload, (const void*)(pkt->data + kSdpPacketStartOffset),
             pkt->len - kSdpPacketStartOffset);
      sdp_disc_server_rsp(p_ccb_, bt_hdr);
      osi_free(data);
      bluetooth::log::info("i:{} L2CA_DisconnectReq:{}", i,
                           get_func_call_count("L2CA_DisconnectReq"));
    }
  }
};

TEST_F(StackSdpAsClientParseTest, nop) {}

TEST_F_WITH_FLAGS(StackSdpAsClientParseTest, sdp_disc_server_rsp_packets00,
                  REQUIRES_FLAGS_DISABLED(ACONFIG_FLAG(
                      TEST_BT, stack_sdp_detect_nil_property_type))) {
  parse_sdp_responses(bluetooth::testing::stack::sdp::packets00::rx_pkts,
                      bluetooth::testing::stack::sdp::packets00::kNumRxPkts);

  ASSERT_EQ(1U, sdp_get_num_records(*p_db_));

  tSDP_DISC_REC* p_sdp_rec = p_db_->p_first_rec;
  ASSERT_NE(nullptr, p_sdp_rec);
  ASSERT_EQ(6U, sdp_get_num_attributes(*p_sdp_rec));

  // Service Record Handle
  ASSERT_EQ(0x00010009U,
            get_legacy_stack_sdp_api()
                ->record
                .SDP_FindAttributeInRec(p_sdp_rec, ATTR_ID_SERVICE_RECORD_HDL)
                ->attr_value.v.u32);

  // Service Class ID List
  p_sdp_rec = p_db_->p_first_rec;
  p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb_128bit(
      p_db_, p_sdp_rec);
  //  ASSERT_NE(nullptr, p_sdp_rec);
  auto uuid_list = std::vector<bluetooth::Uuid>(1);
  p_sdp_rec = p_db_->p_first_rec;
  ASSERT_EQ(true,
            get_legacy_stack_sdp_api()->record.SDP_FindServiceUUIDInRec_128bit(
                p_sdp_rec, &uuid_list[0]));
  ASSERT_EQ(1U, uuid_list.size());
  ASSERT_STREQ("4de17a00-52cb-11e6-bdf4-0800200c9a66",
               uuid_list.front().ToString().c_str());

  // Service Record State
  ASSERT_EQ(0x008f5162U,
            get_legacy_stack_sdp_api()
                ->record
                .SDP_FindAttributeInRec(p_sdp_rec, ATTR_ID_SERVICE_RECORD_STATE)
                ->attr_value.v.u32);

  // Protocol Descriptor List
  tSDP_PROTOCOL_ELEM pe;
  ASSERT_EQ(true,
            get_legacy_stack_sdp_api()->record.SDP_FindProtocolListElemInRec(
                p_sdp_rec, UUID_PROTOCOL_L2CAP, &pe));
  ASSERT_EQ(UUID_PROTOCOL_L2CAP, pe.protocol_uuid);
  ASSERT_EQ(0U, pe.num_params);

  ASSERT_EQ(true,
            get_legacy_stack_sdp_api()->record.SDP_FindProtocolListElemInRec(
                p_sdp_rec, UUID_PROTOCOL_RFCOMM, &pe));
  ASSERT_EQ(UUID_PROTOCOL_RFCOMM, pe.protocol_uuid);
  ASSERT_EQ(1U, pe.num_params);
  ASSERT_EQ(UUID_PROTOCOL_RFCOMM, pe.params[0]);

  // Browse Group List
  ASSERT_NE(nullptr, get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
                         p_sdp_rec, ATTR_ID_BROWSE_GROUP_LIST));

  // Bluetooth Profile List
  ASSERT_NE(nullptr, get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
                         p_sdp_rec, ATTR_ID_BT_PROFILE_DESC_LIST));

  // Service Name
  ASSERT_EQ(
      nullptr,
      (const char*)get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
          p_sdp_rec, ATTR_ID_SERVICE_NAME));
}

TEST_F_WITH_FLAGS(StackSdpAsClientParseTest, sdp_disc_server_rsp_packets00b,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(
                      TEST_BT, stack_sdp_detect_nil_property_type))) {
  parse_sdp_responses(bluetooth::testing::stack::sdp::packets00::rx_pkts,
                      bluetooth::testing::stack::sdp::packets00::kNumRxPkts);

  ASSERT_EQ(1U, sdp_get_num_records(*p_db_));

  tSDP_DISC_REC* p_sdp_rec = p_db_->p_first_rec;
  ASSERT_NE(nullptr, p_sdp_rec);
  ASSERT_EQ(7U, sdp_get_num_attributes(*p_sdp_rec));
}
