/*
 * Copyright 2020 HIMSA II K/S - www.himsa.dk.
 * Represented by EHIMA - www.ehima.com
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
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <vector>

#include "bta/test/common/fake_osi.h"
#include "hci/controller_interface_mock.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cdefs.h"
#include "stack/test/common/mock_btm_api_layer.h"
#include "stack/test/common/mock_eatt.h"
#include "stack/test/common/mock_gatt_layer.h"
#include "stack/test/common/mock_l2cap_layer.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_stack_l2cap_interface.h"
#include "types/raw_address.h"

using testing::_;
using testing::DoAll;
using testing::MockFunction;
using testing::NiceMock;
using testing::NotNull;
using testing::Return;
using testing::SaveArg;
using testing::SaveArgPointee;
using testing::StrictMock;

using bluetooth::eatt::EattChannel;
using bluetooth::eatt::EattChannelState;
using namespace bluetooth;

#define BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK 0x01

extern struct fake_osi_alarm_set_on_mloop fake_osi_alarm_set_on_mloop_;

/* Needed for testing context */
static tGATT_TCB test_tcb;
void gatt_consolidate(const RawAddress& /*identity_addr*/, const RawAddress& /*rpa*/) {}
void gatt_data_process(tGATT_TCB& /*tcb*/, uint16_t /*cid*/, BT_HDR* /*p_buf*/) { return; }
tGATT_TCB* gatt_find_tcb_by_addr(const RawAddress& /*bda*/, tBT_TRANSPORT /*transport*/) {
  log::info("");
  return &test_tcb;
}

namespace {
const RawAddress test_address({0x11, 0x11, 0x11, 0x11, 0x11, 0x11});
std::vector<uint16_t> test_local_cids{61, 62, 63, 64, 65};

class EattTest : public ::testing::Test {
protected:
  void ConnectDeviceEattSupported(int num_of_accepted_connections, bool collision = false) {
    ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
            .WillByDefault([](const RawAddress& addr,
                              base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
              std::move(cb).Run(addr, BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK);
              return true;
            });
    EXPECT_CALL(gatt_interface_, GetEattSupport).WillRepeatedly([](const RawAddress& /*addr*/) {
      return true;
    });

    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
            .WillOnce(Return(test_local_cids));
    ON_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(test_local_cids[0]))
            .WillByDefault(Return(true));

    eatt_instance_->Connect(test_address);

    if (collision) {
      /* Collision should be handled only if all channels has been rejected in
       * first place.*/
      if (num_of_accepted_connections == 0) {
        EXPECT_CALL(mock_stack_l2cap_interface_,
                    L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
                .Times(1);
      }

      l2cap_app_info_.pL2CA_CreditBasedCollisionInd_Cb(test_address);
    }

    int i = 0;
    for (uint16_t cid : test_local_cids) {
      EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
      ASSERT_TRUE(channel != nullptr);
      ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_PENDING);

      if (i < num_of_accepted_connections) {
        l2cap_app_info_.pL2CA_CreditBasedConnectCfm_Cb(
                test_address, cid, EATT_MIN_MTU_MPS,
                tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK);
        connected_cids_.push_back(cid);

        ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
        ASSERT_TRUE(channel->tx_mtu_ == EATT_MIN_MTU_MPS);
      } else {
        l2cap_app_info_.pL2CA_Error_Cb(cid,
                                       static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES));

        EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
        ASSERT_TRUE(channel == nullptr);
      }
      i++;
    }

    ASSERT_TRUE(test_tcb.eatt == num_of_accepted_connections);
  }

  void ConnectDeviceBothSides(int num_of_accepted_connections,
                              std::vector<uint16_t>& incoming_cids) {
    base::OnceCallback<void(const RawAddress&, uint8_t)> eatt_supp_feat_cb;

    ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
            .WillByDefault(
                    [&eatt_supp_feat_cb](const RawAddress& /*addr*/,
                                         base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
                      eatt_supp_feat_cb = std::move(cb);
                      return true;
                    });

    // Return false to trigger supported features request
    ON_CALL(gatt_interface_, GetEattSupport).WillByDefault([](const RawAddress& /*addr*/) {
      return false;
    });

    std::vector<uint16_t> test_local_cids{61, 62, 63, 64, 65};
    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
            .WillOnce(Return(test_local_cids));

    eatt_instance_->Connect(test_address);

    // Let the remote connect while we are trying to connect
    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_ConnectCreditBasedRsp(test_address, 1, incoming_cids,
                                           tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK, _))
            .WillOnce(Return(true));
    l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                   EATT_MIN_MTU_MPS, 1);

    // Respond to feature request scheduled by the connect request
    ASSERT_TRUE(eatt_supp_feat_cb);
    if (eatt_supp_feat_cb) {
      std::move(eatt_supp_feat_cb).Run(test_address, BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK);
    }

    int i = 0;
    for (uint16_t cid : test_local_cids) {
      EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
      ASSERT_TRUE(channel != nullptr);
      ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_PENDING);

      if (i < num_of_accepted_connections) {
        l2cap_app_info_.pL2CA_CreditBasedConnectCfm_Cb(
                test_address, cid, EATT_MIN_MTU_MPS,
                tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK);
        connected_cids_.push_back(cid);

        ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
        ASSERT_TRUE(channel->tx_mtu_ == EATT_MIN_MTU_MPS);
      } else {
        l2cap_app_info_.pL2CA_Error_Cb(cid,
                                       static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES));

        EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
        ASSERT_TRUE(channel == nullptr);
      }
      i++;
    }

    // Check the incoming CIDs as well
    for (auto cid : incoming_cids) {
      EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
      ASSERT_NE(nullptr, channel);
      ASSERT_EQ(channel->state_, EattChannelState::EATT_CHANNEL_OPENED);
      ASSERT_TRUE(channel->tx_mtu_ == EATT_MIN_MTU_MPS);
    }

    ASSERT_EQ(test_tcb.eatt, num_of_accepted_connections + incoming_cids.size());
  }

  void DisconnectEattByPeer(void) {
    for (uint16_t cid : connected_cids_) {
      l2cap_app_info_.pL2CA_DisconnectInd_Cb(cid, true);
    }
    ASSERT_EQ(0, test_tcb.eatt);
  }

  void DisconnectEattDevice(std::vector<uint16_t> cids) {
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(_)).Times(cids.size());
    eatt_instance_->Disconnect(test_address);

    ASSERT_EQ(0, test_tcb.eatt);
  }

  void SetUp() override {
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    tL2CAP_APPL_INFO l2cap_callbacks{};

    le_buffer_size_.le_data_packet_length_ = 128;
    le_buffer_size_.total_num_le_packets_ = 24;
    EXPECT_CALL(controller_, GetLeBufferSize).WillRepeatedly(Return(le_buffer_size_));
    bluetooth::l2cap::SetMockInterface(&l2cap_interface_);
    bluetooth::manager::SetMockBtmApiInterface(&btm_api_interface_);
    bluetooth::gatt::SetMockGattInterface(&gatt_interface_);
    bluetooth::hci::testing::mock_controller_ = &controller_;

    // Clear the static memory for each test case
    memset(&test_tcb, 0, sizeof(test_tcb));

    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_RegisterLECoc(BT_PSM_EATT, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&l2cap_app_info_), ::testing::ReturnArg<0>()));

    hci_role_ = HCI_ROLE_CENTRAL;

    EXPECT_CALL(l2cap_interface_, LeCreditDefault()).WillRepeatedly(DoAll(Return(0xfff)));

    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_GetBleConnRole(_))
            .WillRepeatedly(DoAll(Return(hci_role_)));

    eatt_instance_ = EattExtension::GetInstance();
    eatt_instance_->Start();

    Test::SetUp();
  }

  void TearDown() override {
    com::android::bluetooth::flags::provider_->reset_flags();

    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DeregisterLECoc(BT_PSM_EATT)).Times(1);

    eatt_instance_->Stop();
    eatt_instance_ = nullptr;
    hci_role_ = HCI_ROLE_CENTRAL;
    connected_cids_.clear();

    bluetooth::gatt::SetMockGattInterface(nullptr);
    bluetooth::l2cap::SetMockInterface(nullptr);
    bluetooth::testing::stack::l2cap::reset_interface();
    bluetooth::manager::SetMockBtmApiInterface(nullptr);
    bluetooth::hci::testing::mock_controller_ = nullptr;

    Test::TearDown();
  }

  tL2CAP_APPL_INFO reg_info_;

  bluetooth::manager::MockBtmApiInterface btm_api_interface_;
  bluetooth::l2cap::MockL2capInterface l2cap_interface_;
  bluetooth::testing::stack::l2cap::Mock mock_stack_l2cap_interface_;
  bluetooth::gatt::MockGattInterface gatt_interface_;
  bluetooth::hci::testing::MockControllerInterface controller_;
  bluetooth::hci::LeBufferSize le_buffer_size_;

  tL2CAP_APPL_INFO l2cap_app_info_;
  EattExtension* eatt_instance_;
  std::vector<uint16_t> connected_cids_;
  tHCI_ROLE hci_role_ = HCI_ROLE_CENTRAL;
};

TEST_F(EattTest, ConnectSucceed) {
  ConnectDeviceEattSupported(1);
  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, IncomingEattConnectionByUnknownDevice) {
  std::vector<uint16_t> incoming_cids{71, 72, 73, 74, 75};

  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return true; });
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_ConnectCreditBasedRsp(test_address, 1, incoming_cids,
                                         tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK, _))
          .WillOnce(Return(true));

  l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                 EATT_MIN_MTU_MPS, 1);

  DisconnectEattDevice(incoming_cids);
}

TEST_F(EattTest, IncomingEattConnectionByKnownDevice) {
  hci_role_ = HCI_ROLE_PERIPHERAL;
  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return true; });
  ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
          .WillByDefault([](const RawAddress& addr,
                            base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
            std::move(cb).Run(addr, BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK);
            return true;
          });
  ON_CALL(gatt_interface_, GetEattSupport).WillByDefault([](const RawAddress& /*addr*/) {
    return true;
  });

  eatt_instance_->Connect(test_address);
  std::vector<uint16_t> incoming_cids{71, 72, 73, 74, 75};

  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_ConnectCreditBasedRsp(test_address, 1, incoming_cids,
                                         tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK, _))
          .WillOnce(Return(true));

  l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                 EATT_MIN_MTU_MPS, 1);

  DisconnectEattDevice(incoming_cids);

  hci_role_ = HCI_ROLE_CENTRAL;
}

TEST_F(EattTest, IncomingEattConnectionByKnownDeviceEncryptionOff) {
  hci_role_ = HCI_ROLE_PERIPHERAL;
  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return false; });
  ON_CALL(btm_api_interface_, IsDeviceBonded)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return true; });
  ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
          .WillByDefault([](const RawAddress& addr,
                            base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
            std::move(cb).Run(addr, BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK);
            return true;
          });
  ON_CALL(gatt_interface_, GetEattSupport).WillByDefault([](const RawAddress& /*addr*/) {
    return true;
  });

  eatt_instance_->Connect(test_address);
  std::vector<uint16_t> incoming_cids{71, 72, 73, 74, 75};

  EXPECT_CALL(
          mock_stack_l2cap_interface_,
          L2CA_ConnectCreditBasedRsp(test_address, 1, _,
                                     tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_INSUFFICIENT_ENCRYP, _))
          .WillOnce(Return(true));

  l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                 EATT_MIN_MTU_MPS, 1);

  hci_role_ = HCI_ROLE_CENTRAL;
}

TEST_F(EattTest, IncomingEattConnectionByUnknownDeviceEncryptionOff) {
  std::vector<uint16_t> incoming_cids{71, 72, 73, 74, 75};

  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return false; });
  ON_CALL(btm_api_interface_, IsDeviceBonded)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return false; });
  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_ConnectCreditBasedRsp(
                      test_address, 1, _,
                      tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_INSUFFICIENT_AUTHENTICATION, _))
          .WillOnce(Return(true));

  l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                 EATT_MIN_MTU_MPS, 1);
}

TEST_F(EattTest, ReconnectInitiatedByRemoteSucceed) {
  ConnectDeviceEattSupported(1);
  DisconnectEattDevice(connected_cids_);
  std::vector<uint16_t> incoming_cids{71, 72, 73, 74, 75};

  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return true; });

  EXPECT_CALL(mock_stack_l2cap_interface_,
              L2CA_ConnectCreditBasedRsp(test_address, 1, incoming_cids,
                                         tL2CAP_LE_RESULT_CODE::L2CAP_LE_RESULT_CONN_OK, _))
          .WillOnce(Return(true));

  l2cap_app_info_.pL2CA_CreditBasedConnectInd_Cb(test_address, incoming_cids, BT_PSM_EATT,
                                                 EATT_MIN_MTU_MPS, 1);

  DisconnectEattDevice(incoming_cids);
}

TEST_F(EattTest, ConnectInitiatedWhenRemoteConnects) {
  ON_CALL(btm_api_interface_, IsEncrypted)
          .WillByDefault(
                  [](const RawAddress& /*addr*/, tBT_TRANSPORT /*transport*/) { return true; });

  std::vector<uint16_t> incoming_cids{71, 72, 73, 74};
  ConnectDeviceBothSides(1, incoming_cids);

  std::vector<uint16_t> disconnecting_cids;
  disconnecting_cids.insert(disconnecting_cids.end(), incoming_cids.begin(), incoming_cids.end());
  disconnecting_cids.insert(disconnecting_cids.end(), connected_cids_.begin(),
                            connected_cids_.end());
  DisconnectEattDevice(disconnecting_cids);
}

TEST_F(EattTest, ConnectSucceedMultipleChannels) {
  ConnectDeviceEattSupported(5);
  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ConnectFailedEattNotSupported) {
  ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
          .WillByDefault([](const RawAddress& addr,
                            base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
            std::move(cb).Run(addr, 0);
            return true;
          });
  ON_CALL(gatt_interface_, GetEattSupport).WillByDefault([](const RawAddress& /*addr*/) {
    return false;
  });

  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
          .Times(0);
  eatt_instance_->Connect(test_address);
  ASSERT_TRUE(eatt_instance_->IsEattSupportedByPeer(test_address) == false);
}

TEST_F(EattTest, ConnectFailedSlaveOnTheLink) {
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
          .Times(0);

  hci_role_ = HCI_ROLE_PERIPHERAL;
  eatt_instance_->Connect(test_address);

  /* Back to default btm role */
  hci_role_ = HCI_ROLE_CENTRAL;
}

TEST_F(EattTest, DisonnectByPeerSucceed) {
  ConnectDeviceEattSupported(2);

  uint16_t cid = connected_cids_[0];
  EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
  ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);

  DisconnectEattByPeer();

  channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
  ASSERT_TRUE(channel == nullptr);
}

TEST_F(EattTest, ReconfigAllSucceed) {
  ConnectDeviceEattSupported(3);

  std::vector<uint16_t> cids;
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ReconfigCreditBasedConnsReq(_, _, _))
          .WillOnce(DoAll(SaveArg<1>(&cids), Return(true)));

  uint16_t new_mtu = 300;
  eatt_instance_->ReconfigureAll(test_address, new_mtu);

  ASSERT_TRUE(cids.size() == connected_cids_.size());

  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_OK, .mtu = new_mtu};

  for (uint16_t cid : cids) {
    l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cid, true, &cfg);

    EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
    ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
    ASSERT_TRUE(channel->rx_mtu_ == new_mtu);
  }

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ReconfigAllFailed) {
  ConnectDeviceEattSupported(4);

  std::vector<uint16_t> cids;
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ReconfigCreditBasedConnsReq(_, _, _))
          .WillOnce(DoAll(SaveArg<1>(&cids), Return(true)));

  uint16_t new_mtu = 300;
  eatt_instance_->ReconfigureAll(test_address, new_mtu);

  ASSERT_TRUE(cids.size() == connected_cids_.size());

  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_FAILED_NO_REASON,
                            .mtu = new_mtu};

  for (uint16_t cid : cids) {
    l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cid, true, &cfg);

    EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
    ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
    ASSERT_TRUE(channel->rx_mtu_ != new_mtu);
  }

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ReconfigSingleSucceed) {
  ConnectDeviceEattSupported(2);

  std::vector<uint16_t> cids;
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ReconfigCreditBasedConnsReq(_, _, _))
          .WillOnce(DoAll(SaveArg<1>(&cids), Return(true)));

  uint16_t new_mtu = 300;
  eatt_instance_->Reconfigure(test_address, connected_cids_[1], new_mtu);

  ASSERT_EQ(1U, cids.size());

  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_OK, .mtu = new_mtu};

  auto it = std::find(connected_cids_.begin(), connected_cids_.end(), cids[0]);
  ASSERT_TRUE(it != connected_cids_.end());

  l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cids[0], true, &cfg);
  EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cids[0]);
  ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
  ASSERT_TRUE(channel->rx_mtu_ == new_mtu);

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ReconfigSingleFailed) {
  ConnectDeviceEattSupported(2);

  std::vector<uint16_t> cids;
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ReconfigCreditBasedConnsReq(_, _, _))
          .WillOnce(DoAll(SaveArg<1>(&cids), Return(true)));

  uint16_t new_mtu = 300;
  eatt_instance_->ReconfigureAll(test_address, new_mtu);

  ASSERT_TRUE(cids.size() == connected_cids_.size());

  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_FAILED_NO_REASON,
                            .mtu = new_mtu};

  auto it = std::find(connected_cids_.begin(), connected_cids_.end(), cids[0]);
  ASSERT_TRUE(it != connected_cids_.end());

  l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cids[0], true, &cfg);
  EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cids[0]);
  ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
  ASSERT_TRUE(channel->rx_mtu_ != new_mtu);

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ReconfigPeerSucceed) {
  ConnectDeviceEattSupported(3);

  uint16_t new_mtu = 300;
  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_OK, .mtu = new_mtu};

  for (uint16_t cid : connected_cids_) {
    l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cid, false, &cfg);

    EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
    ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
    ASSERT_TRUE(channel->tx_mtu_ == new_mtu);
  }

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, ReconfigPeerFailed) {
  ConnectDeviceEattSupported(2);

  uint16_t new_mtu = 300;
  tL2CAP_LE_CFG_INFO cfg = {.result = tL2CAP_CFG_RESULT::L2CAP_CFG_FAILED_NO_REASON,
                            .mtu = new_mtu};

  for (uint16_t cid : connected_cids_) {
    l2cap_app_info_.pL2CA_CreditBasedReconfigCompleted_Cb(test_address, cid, false, &cfg);

    EattChannel* channel = eatt_instance_->FindEattChannelByCid(test_address, cid);
    ASSERT_TRUE(channel->state_ == EattChannelState::EATT_CHANNEL_OPENED);
    ASSERT_TRUE(channel->tx_mtu_ != new_mtu);
  }

  DisconnectEattDevice(connected_cids_);
}

TEST_F(EattTest, DoubleDisconnect) {
  ConnectDeviceEattSupported(1);
  DisconnectEattDevice(connected_cids_);

  /* Force second disconnect */
  eatt_instance_->Disconnect(test_address);
}

TEST_F(EattTest, TestCollisionHandling) {
  ConnectDeviceEattSupported(0, true /* collision*/);
  ConnectDeviceEattSupported(5, true /* collision*/);
}

TEST_F(EattTest, ChannelUnavailableWhileOpening) {
  // arrange
  ON_CALL(gatt_interface_, ClientReadSupportedFeatures)
          .WillByDefault([](const RawAddress& addr,
                            base::OnceCallback<void(const RawAddress&, uint8_t)> cb) {
            std::move(cb).Run(addr, BLE_GATT_SVR_SUP_FEAT_EATT_BITMASK);
            return true;
          });
  ON_CALL(gatt_interface_, GetEattSupport).WillByDefault(Return(true));

  // expect
  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_ConnectCreditBasedReq(BT_PSM_EATT, test_address, _))
          .WillOnce(Return(std::vector<uint16_t>{61}));

  // act: start
  eatt_instance_->Connect(test_address);
  auto available_channel_for_request =
          eatt_instance_->GetChannelAvailableForClientRequest(test_address);
  auto available_channel_for_indication =
          eatt_instance_->GetChannelAvailableForIndication(test_address);

  // assert
  ASSERT_EQ(available_channel_for_request, nullptr);
  ASSERT_EQ(available_channel_for_indication, nullptr);
}

TEST_F(EattTest, ChannelUnavailableWhileReconfiguring) {
  // arrange
  ON_CALL(mock_stack_l2cap_interface_, L2CA_ReconfigCreditBasedConnsReq(_, _, _))
          .WillByDefault(Return(true));
  ConnectDeviceEattSupported(/* num_of_accepted_connections = */ 1);

  // act: reconfigure, then get available channels
  eatt_instance_->Reconfigure(test_address, connected_cids_[0], 300);
  auto available_channel_for_request =
          eatt_instance_->GetChannelAvailableForClientRequest(test_address);
  auto available_channel_for_indication =
          eatt_instance_->GetChannelAvailableForIndication(test_address);

  // assert
  ASSERT_EQ(available_channel_for_request, nullptr);
  ASSERT_EQ(available_channel_for_indication, nullptr);
}

TEST_F(EattTest, DisconnectChannelOnIndicationConfirmationTimeout) {
  com::android::bluetooth::flags::provider_->gatt_disconnect_fix(true);
  ConnectDeviceEattSupported(1);

  eatt_instance_->StartIndicationConfirmationTimer(test_address, test_local_cids[0]);

  EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_DisconnectReq(test_local_cids[0])).Times(1);
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
}

}  // namespace
