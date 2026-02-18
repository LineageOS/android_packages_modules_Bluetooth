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
#include <stdlib.h>

#include <cstddef>
#include <cstdint>
#include <memory>

#include "btif/include/btif_common.h"
#include "osi/include/allocator.h"
#include "stack/include/sdp_api.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/sdp/sdpint.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_osi_allocator.h"

#ifndef BT_DEFAULT_BUFFER_SIZE
#define BT_DEFAULT_BUFFER_SIZE (4096 + 16)
#endif

using ::testing::_;
using ::testing::DoAll;
using ::testing::Invoke;
using ::testing::Return;
using ::testing::ReturnArg;
using ::testing::SaveArg;

namespace {
constexpr uint8_t kSDP_MAX_CONNECTIONS = static_cast<uint8_t>(SDP_MAX_CONNECTIONS);

const RawAddress kRawAddress = RawAddress("A1:A2:A3:A4:A5:A6");
int L2CA_ConnectReqWithSecurity_cid = 0x42;

class StackSdpWithMocksTest : public ::testing::Test {
protected:
  void SetUp() override {
    fake_osi_ = std::make_unique<::test::fake::FakeOsi>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);

    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&l2cap_callbacks_), ::testing::ReturnArg<0>()));
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Deregister(_));
  }

  void TearDown() override {
    bluetooth::testing::stack::l2cap::reset_interface();
    fake_osi_.reset();
  }

  tL2CAP_APPL_INFO l2cap_callbacks_{};
  bluetooth::testing::stack::l2cap::Mock mock_stack_l2cap_interface_;
  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

class StackSdpApiTest : public StackSdpWithMocksTest {
protected:
  void SetUp() override {
    StackSdpWithMocksTest::SetUp();
    sdp_init();
  }

  void TearDown() override {
    sdp_free();
    StackSdpWithMocksTest::TearDown();
  }
};

}  // namespace

TEST_F(StackSdpApiTest, nop) {}

TEST_F(StackSdpApiTest, SDP_ServiceSearchRequest) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly(Invoke([](uint16_t /* psm */, const RawAddress& /* p_bd_addr */,
                                    uint16_t /* sec_level */) -> uint16_t {
            return L2CA_ConnectReqWithSecurity_cid;
          }));
  for (uint8_t i = 0; i < kSDP_MAX_CONNECTIONS; i++) {
    RawAddress bd_addr = RawAddress(std::array<uint8_t, 6>({0x11, 0x22, 0x33, 0x44, 0x55, i}));
    ASSERT_NE(nullptr, sdp_conn_originate(bd_addr));
  }
  tSDP_DISCOVERY_DB db;
  ASSERT_FALSE(bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api()->SDP_ServiceSearchRequest(
          kRawAddress, &db, [](const RawAddress& /* bd_addr */, tSDP_RESULT /* result */) {}));
}

TEST_F(StackSdpApiTest, SDP_ServiceSearchAttributeRequest) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly(Invoke([](uint16_t /* psm */, const RawAddress& /* p_bd_addr */,
                                    uint16_t /* sec_level */) -> uint16_t {
            return L2CA_ConnectReqWithSecurity_cid;
          }));
  for (uint8_t i = 0; i < kSDP_MAX_CONNECTIONS; i++) {
    RawAddress bd_addr = RawAddress(std::array<uint8_t, 6>({0x11, 0x22, 0x33, 0x44, 0x55, i}));
    ASSERT_NE(nullptr, sdp_conn_originate(bd_addr));
  }
  tSDP_DISCOVERY_DB db;
  ASSERT_FALSE(bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api()
                       ->SDP_ServiceSearchAttributeRequest(
                               kRawAddress, &db,
                               [](const RawAddress& /* bd_addr */, tSDP_RESULT /* result */) {}));
}

TEST_F(StackSdpApiTest, SDP_ServiceSearchAttributeRequest2) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly(Invoke([](uint16_t /* psm */, const RawAddress& /* p_bd_addr */,
                                    uint16_t /* sec_level */) -> uint16_t {
            return L2CA_ConnectReqWithSecurity_cid;
          }));
  for (uint8_t i = 0; i < kSDP_MAX_CONNECTIONS; i++) {
    RawAddress bd_addr = RawAddress(std::array<uint8_t, 6>({0x11, 0x22, 0x33, 0x44, 0x55, i}));
    ASSERT_NE(nullptr, sdp_conn_originate(bd_addr));
  }
  tSDP_DISCOVERY_DB db;
  ASSERT_FALSE(bluetooth::legacy::stack::sdp::get_legacy_stack_sdp_api()
                       ->SDP_ServiceSearchAttributeRequest2(
                               kRawAddress, &db,
                               base::BindRepeating([](const RawAddress& /* bd_addr */,
                                                      tSDP_RESULT /* result */) {})));
}
