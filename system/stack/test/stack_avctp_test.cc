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
#include <gtest/gtest.h>
#include <unistd.h>

#include "stack/include/avct_api.h"
#include "stack/include/bt_psm_types.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/mock/mock_stack_l2cap_utils.h"
#include "test/fake/fake_osi.h"

using ::testing::_;
using ::testing::Args;
using ::testing::DoAll;
using ::testing::SaveArg;

namespace {
constexpr uint16_t kRemoteCid = 0x0123;
constexpr uint16_t kRemoteBrowseCid = 0x0456;
const RawAddress kRawAddress = RawAddress("11:22:33:44:55:66");
}  // namespace

class StackAvctpWithMocksTest : public ::testing::Test {
protected:
  void SetUp() override {
    fake_osi_ = std::make_unique<::test::fake::FakeOsi>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    test::mock::stack_l2cap_utils::l2cu_find_lcb_by_bd_addr.body =
            [](const RawAddress& /*bd_addr*/, tBT_TRANSPORT /* transport */) {
              tL2C_LCB* l2c_lcb = (tL2C_LCB*)malloc(sizeof(tL2C_LCB));
              l2c_lcb->peer_ext_fea |= L2CAP_EXTFEA_ENH_RETRANS;
              return l2c_lcb;
            };
  }

  void TearDown() override {}

  bluetooth::testing::stack::l2cap::Mock mock_stack_l2cap_interface_;
  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

class StackAvctpTest : public StackAvctpWithMocksTest {
protected:
  void SetUp() override {
    StackAvctpWithMocksTest::SetUp();
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
            .WillRepeatedly([this](uint16_t psm, const tL2CAP_APPL_INFO& cb, bool /* c */,
                                   tL2CAP_ERTM_INFO* /*d*/, uint16_t /* e */, uint16_t /* f */,
                                   uint16_t /* g */) {
              this->callback_map_.insert(std::make_tuple(psm, cb));
              return psm;
            });
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(_)).WillRepeatedly([]() {
      return true;
    });
    AVCT_Register();
    // Make sure we have a callback for both PSMs
    ASSERT_EQ(2U, callback_map_.size());
  }

  void TearDown() override {
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Deregister(BT_PSM_AVCTP));
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Deregister(BT_PSM_AVCTP_BROWSE));
    AVCT_Deregister();
    StackAvctpWithMocksTest::TearDown();
  }

  std::map<uint16_t, tL2CAP_APPL_INFO> callback_map_;
  int fd_{STDOUT_FILENO};
};

TEST_F(StackAvctpTest, AVCT_Dumpsys) { AVCT_Dumpsys(fd_); }

TEST_F(StackAvctpTest, AVCT_CreateConn) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly([](uint16_t /* psm */, const RawAddress /* bd_addr */,
                             uint16_t /* sec_level */) { return 0x1234; });

  uint8_t handle;
  tAVCT_CC cc = {
          .p_ctrl_cback = [](uint8_t /* handle */, uint8_t /* event */, uint16_t /* result */,
                             const RawAddress* /* peer_addr */) {},
          .p_msg_cback = [](uint8_t /* handle */, uint8_t /* label */, uint8_t /* cr */,
                            BT_HDR* /* p_pkt */) {},
          .pid = 0x1234,
          .role = AVCT_ROLE_INITIATOR,
          .control = 1,
  };
  ASSERT_EQ(AVCT_SUCCESS, AVCT_CreateConn(&handle, &cc, kRawAddress));
  ASSERT_EQ(AVCT_SUCCESS, AVCT_RemoveConn(handle));
}

TEST_F(StackAvctpTest, AVCT_CreateBrowse) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly([](uint16_t /* psm */, const RawAddress /* bd_addr */,
                             uint16_t /* sec_level */) { return 0x1234; });

  uint8_t handle;
  tAVCT_CC cc = {
          .p_ctrl_cback = [](uint8_t /* handle */, uint8_t /* event */, uint16_t /* result */,
                             const RawAddress* /* peer_addr */) {},
          .p_msg_cback = [](uint8_t /* handle */, uint8_t /* label */, uint8_t /* cr */,
                            BT_HDR* /* p_pkt */) {},
          .pid = 0x1234,
          .role = AVCT_ROLE_INITIATOR,
          .control = 1,
  };
  ASSERT_EQ(AVCT_SUCCESS, AVCT_CreateConn(&handle, &cc, kRawAddress));

  ASSERT_EQ(AVCT_SUCCESS, AVCT_CreateBrowse(handle, AVCT_ROLE_INITIATOR));

  ASSERT_EQ(AVCT_SUCCESS, AVCT_RemoveBrowse(handle));
  ASSERT_EQ(AVCT_SUCCESS, AVCT_RemoveConn(handle));
}

TEST_F(StackAvctpTest, AVCT_RemoteInitiatesControl) {
  // AVCT Control
  callback_map_[BT_PSM_AVCTP].pL2CA_ConnectInd_Cb(kRawAddress, kRemoteCid, BT_PSM_AVCTP, 0);
  callback_map_[BT_PSM_AVCTP].pL2CA_ConnectCfm_Cb(kRemoteCid, tL2CAP_CONN::L2CAP_CONN_OK);
}

TEST_F(StackAvctpTest, AVCT_RemoteInitiatesBrowse) {
  // AVCT Control
  callback_map_[BT_PSM_AVCTP].pL2CA_ConnectInd_Cb(kRawAddress, kRemoteCid, BT_PSM_AVCTP, 0);
  callback_map_[BT_PSM_AVCTP].pL2CA_ConnectCfm_Cb(kRemoteCid, tL2CAP_CONN::L2CAP_CONN_OK);

  // AVCT Browse
  callback_map_[BT_PSM_AVCTP_BROWSE].pL2CA_ConnectInd_Cb(kRawAddress, kRemoteCid,
                                                         BT_PSM_AVCTP_BROWSE, 0);
  callback_map_[BT_PSM_AVCTP_BROWSE].pL2CA_ConnectCfm_Cb(kRemoteCid, tL2CAP_CONN::L2CAP_CONN_OK);

  AVCT_Dumpsys(fd_);
}

TEST_F(StackAvctpWithMocksTest, AVCT_Lifecycle) {
  // Register the AVCT profile and capture the l2cap callbacks
  std::map<uint16_t, tL2CAP_APPL_INFO> callback_map;
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_RegisterWithSecurity(_, _, _, _, _, _, _))
          .WillRepeatedly([&callback_map](uint16_t psm, const tL2CAP_APPL_INFO& cb, bool /* c */,
                                          tL2CAP_ERTM_INFO* /*d*/, uint16_t /* e */,
                                          uint16_t /* f */, uint16_t /* g */) {
            callback_map.insert(std::make_tuple(psm, cb));
            return psm;
          });
  AVCT_Register();

  // Return well known l2cap channel IDs for each of the two PSMs
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectReqWithSecurity(_, _, _))
          .WillRepeatedly(
                  [](uint16_t psm, const RawAddress /* bd_addr */, uint16_t /* sec_level */) {
                    return (psm == BT_PSM_AVCTP) ? kRemoteCid : kRemoteBrowseCid;
                  });

  uint8_t handle;
  tAVCT_CC cc = {
          .p_ctrl_cback = [](uint8_t /* handle */, uint8_t /* event */, uint16_t /* result */,
                             const RawAddress* /* peer_addr */) {},
          .p_msg_cback = [](uint8_t /* handle */, uint8_t /* label */, uint8_t /* cr */,
                            BT_HDR* /* p_pkt */) {},
          .pid = 0x1234,
          .role = AVCT_ROLE_INITIATOR,
          .control = 1,
  };

  // Initiate the creation of both control and browse AVCT connections
  ASSERT_EQ(AVCT_SUCCESS, AVCT_CreateConn(&handle, &cc, kRawAddress));
  ASSERT_EQ(AVCT_SUCCESS, AVCT_CreateBrowse(handle, AVCT_ROLE_INITIATOR));

  // Provide appropriate l2cap remote responses to open both channels
  tL2CAP_CFG_INFO l2cap_cfg{};
  callback_map[BT_PSM_AVCTP].pL2CA_ConnectCfm_Cb(kRemoteCid, tL2CAP_CONN::L2CAP_CONN_OK);
  callback_map[BT_PSM_AVCTP].pL2CA_ConfigCfm_Cb(kRemoteCid, 0, &l2cap_cfg);
  callback_map[BT_PSM_AVCTP_BROWSE].pL2CA_ConnectCfm_Cb(kRemoteBrowseCid,
                                                        tL2CAP_CONN::L2CAP_CONN_OK);
  callback_map[BT_PSM_AVCTP_BROWSE].pL2CA_ConfigCfm_Cb(kRemoteBrowseCid, 0, &l2cap_cfg);

  // Close the profile by deregistering from l2cap
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Deregister(BT_PSM_AVCTP));
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_Deregister(BT_PSM_AVCTP_BROWSE));
  AVCT_Deregister();

  // Ensure that API calls with a cached handle value or any run from a reactor
  // thread or message loop do not fail
  ASSERT_EQ(AVCT_SUCCESS, AVCT_RemoveBrowse(handle));
  ASSERT_EQ(AVCT_SUCCESS, AVCT_RemoveConn(handle));
}
