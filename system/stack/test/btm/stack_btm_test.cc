/*
 *  Copyright 2020 The Android Open Source Project
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

#include <bluetooth/types/ble_address_with_type.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <iostream>
#include <sstream>

#include "bta/ag/bta_ag_int.h"
#include "bta/dm/bta_dm_act.h"
#include "bta/dm/bta_dm_sec_int.h"
#include "bta/gatt/bta_gattc_int.h"
#include "bta/include/bta_dm_acl.h"
#include "bta/sys/bta_sys.h"
#include "hci/controller_mock.h"
#include "hci/hci_layer_mock.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sco.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_security.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/acl_api.h"
#include "stack/include/acl_hci_link_interface.h"
#include "stack/include/btm_client_interface.h"
#include "stack/l2cap/l2c_int.h"
#include "stack/mock/mock_stack_hcic_layer.h"
#include "stack/rnr/remote_name_request.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"

using ::testing::_;
using ::testing::Each;
using ::testing::Eq;
using ::testing::Invoke;

tL2C_CB l2cb;

const std::string kBroadcastAudioConfigOptions("mock broadcast audio config options");

// TODO: remove dependency on BTA symbols
void BTA_dm_acl_up(const AclLinkSpec&, uint16_t, bool) { inc_func_call_count(__func__); }
void BTA_dm_acl_up_failed(const AclLinkSpec&, tHCI_STATUS, bool) {}
void BTA_dm_acl_down(const AclLinkSpec&) {}
void BTA_dm_report_role_change(RawAddress, tHCI_ROLE, tHCI_STATUS) {}
void BTA_dm_notify_remote_features_complete(RawAddress) {}
void bta_dm_process_remove_device(const RawAddress&) {}
void bta_dm_remote_key_missing(RawAddress, tBTM_KEY_MISSING_REASON) {}
void bta_dm_on_encryption_change(bt_encryption_change_evt) {}
void bta_dm_remove_device(const RawAddress&) {}
void bta_gattc_continue_discovery_if_needed(const RawAddress&, uint16_t) {}
void bta_sys_notify_collision(const RawAddress&) {}
size_t bta_ag_sco_read(uint8_t*, uint32_t) { return 0; }
size_t bta_ag_sco_write(const uint8_t*, uint32_t) { return 0; }

void btm_inq_remote_name_timer_timeout(void*) {}

namespace {

using testing::Return;
using testing::Test;

class StackBtmTest : public BtmWithMocksTest {
protected:
  void SetUp() override {
    BtmWithMocksTest::SetUp();
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<bluetooth::hci::testing::MockController>();
  }
  void TearDown() override {
    bluetooth::hci::testing::mock_controller_.reset();
    BtmWithMocksTest::TearDown();
  }
};

class StackBtmWithQueuesTest : public StackBtmTest {
protected:
  void SetUp() override {
    StackBtmTest::SetUp();
    up_thread_ = new bluetooth::os::Thread("up_thread", bluetooth::os::Thread::Priority::NORMAL);
    up_handler_ = new bluetooth::os::Handler(up_thread_);
    down_thread_ =
            new bluetooth::os::Thread("down_thread", bluetooth::os::Thread::Priority::NORMAL);
    down_handler_ = new bluetooth::os::Handler(down_thread_);
    bluetooth::hci::testing::mock_hci_layer_ =
            std::make_unique<bluetooth::hci::testing::MockHciLayer>();
    bluetooth::hci::testing::mock_gd_shim_handler_ = up_handler_;
    hcic::SetMockHcicInterface(&legacy_hci_mock_);
    EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, RegisterForScoConnectionRequests(_));
    EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, RegisterForDisconnects(_));
  }
  void TearDown() override {
    up_handler_->Clear();
    delete up_handler_;
    delete up_thread_;
    down_handler_->Clear();
    delete down_handler_;
    delete down_thread_;
    bluetooth::hci::testing::mock_hci_layer_.release();
    StackBtmTest::TearDown();
  }
  bluetooth::common::BidiQueue<bluetooth::hci::ScoView, bluetooth::hci::ScoBuilder> sco_queue_{10};
  hcic::MockHcicInterface legacy_hci_mock_;
  bluetooth::os::Thread* up_thread_;
  bluetooth::os::Handler* up_handler_;
  bluetooth::os::Thread* down_thread_;
  bluetooth::os::Handler* down_handler_;
};

class StackBtmWithInitFreeTest : public StackBtmWithQueuesTest {
protected:
  void SetUp() override {
    StackBtmWithQueuesTest::SetUp();
    EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
            .WillOnce(Return(sco_queue_.GetUpEnd()));
    btm_cb.Init();
    BtmSecurity::Get().Init(BTM_SEC_MODE_SC);
  }
  void TearDown() override {
    BtmSecurity::Get().Free();
    btm_cb.Free();
    StackBtmWithQueuesTest::TearDown();
  }
};

TEST_F(StackBtmWithQueuesTest, GlobalLifecycle) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();
  get_btm_client_interface().lifecycle.btm_free();
}

TEST_F(StackBtmTest, DynamicLifecycle) {
  auto* btm = new tBTM_CB();
  delete btm;
}

TEST_F(StackBtmWithQueuesTest, InitFree) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  btm_cb.Init();
  btm_cb.Free();
}

TEST_F(StackBtmWithQueuesTest, tSCO_CB) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  tSCO_CB* p_sco = &btm_cb.sco_cb;
  p_sco->Init();
  p_sco->Free();
}

TEST_F(StackBtmWithQueuesTest, InformClientOnConnectionSuccess) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();

  RawAddress bda("11:22:33:44:55:66");

  on_acl_br_edr_connected(bda, 2, 0, false, HCI_ROLE_CENTRAL);
  ASSERT_EQ(1, get_func_call_count("BTA_dm_acl_up"));

  get_btm_client_interface().lifecycle.btm_free();
}

TEST_F(StackBtmWithQueuesTest, RoleIsSetOnSuccessfulConnection) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();

  RawAddress bda("11:22:33:44:55:66");

  on_acl_br_edr_connected(bda, 2, 0, false, HCI_ROLE_CENTRAL);

  // Verify that the role is correctly set
  tHCI_ROLE role = HCI_ROLE_UNKNOWN;
  ASSERT_EQ(BTM_GetRole(bda, BT_TRANSPORT_BR_EDR, &role), tBTM_STATUS::BTM_SUCCESS);
  ASSERT_EQ(role, HCI_ROLE_CENTRAL);

  get_btm_client_interface().lifecycle.btm_free();
}

TEST_F(StackBtmWithQueuesTest, NoInformClientOnConnectionFail) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();

  RawAddress bda("11:22:33:44:55:66");

  on_acl_br_edr_failed(bda, HCI_ERR_NO_CONNECTION, false);
  ASSERT_EQ(0, get_func_call_count("BTA_dm_acl_up"));

  get_btm_client_interface().lifecycle.btm_free();
}

TEST_F(StackBtmWithQueuesTest, default_packet_type) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();

  btm_cb.acl_cb_.SetDefaultPacketTypeMask(0x4321);
  ASSERT_EQ(0x4321, btm_cb.acl_cb_.DefaultPacketTypes());

  get_btm_client_interface().lifecycle.btm_free();
}

TEST_F(StackBtmWithQueuesTest, change_packet_type) {
  EXPECT_CALL(*bluetooth::hci::testing::mock_hci_layer_, GetScoQueueEnd())
          .WillOnce(Return(sco_queue_.GetUpEnd()));
  get_btm_client_interface().lifecycle.btm_init();

  uint16_t handle = 0x123;

  btm_cb.acl_cb_.SetDefaultPacketTypeMask(0xffff);
  ASSERT_EQ(0xffff, btm_cb.acl_cb_.DefaultPacketTypes());

  // Create connection
  RawAddress bda("11:22:33:44:55:66");
  AclLinkSpec link_spec = {.addrt = {.type = BLE_ADDR_PUBLIC, .bda = bda},
                           .transport = BT_TRANSPORT_BR_EDR};
  btm_acl_created(link_spec, handle, HCI_ROLE_CENTRAL);

  uint64_t features = 0xffffffffffffffff;
  acl_process_supported_features(0x123, features);

  EXPECT_CALL(legacy_hci_mock_,
              ChangeConnectionPacketType(handle, 0x4400 | HCI_PKT_TYPES_MASK_DM1));
  EXPECT_CALL(legacy_hci_mock_, ChangeConnectionPacketType(handle, 0xcc00 | HCI_PKT_TYPES_MASK_DM1 |
                                                                           HCI_PKT_TYPES_MASK_DH1));

  btm_set_packet_types_from_address(bda, 0x55aa);
  btm_set_packet_types_from_address(bda, 0xffff);
  // Illegal mask, won't be sent.
  btm_set_packet_types_from_address(bda, 0x0);

  get_btm_client_interface().lifecycle.btm_free();
}

TEST(BtmTest, BTM_EIR_MAX_SERVICES) { ASSERT_EQ(46, BTM_EIR_MAX_SERVICES); }

}  // namespace

void btm_sec_rmt_name_request_complete(const RawAddress* p_bd_addr, const uint8_t* p_bd_name,
                                       tHCI_STATUS status);

struct {
  RawAddress bd_addr;
  BD_NAME bd_name;
} btm_test;

namespace {
void BTM_RMT_NAME_CALLBACK(const RawAddress& bd_addr, const BD_NAME& bd_name) {
  btm_test.bd_addr = bd_addr;
  memcpy(btm_test.bd_name, bd_name, BD_NAME_LEN);
}
}  // namespace

TEST_F(StackBtmWithInitFreeTest, btm_sec_rmt_name_request_complete) {
  btm_cb.rnr.p_rmt_name_callback = BTM_RMT_NAME_CALLBACK;

  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint8_t* p_bd_name = (const uint8_t*)"MyTestName";

  btm_test = {};
  btm_sec_rmt_name_request_complete(&bd_addr, p_bd_name, HCI_SUCCESS);

  ASSERT_THAT(btm_test.bd_name, Each(Eq(0)));
  ASSERT_EQ(btm_test.bd_addr, RawAddress::kEmpty);

  btm_test = {};
  ASSERT_TRUE(btm_find_or_alloc_dev(bd_addr) != nullptr);
  btm_sec_rmt_name_request_complete(&bd_addr, p_bd_name, HCI_SUCCESS);

  ASSERT_STREQ((const char*)p_bd_name, (const char*)btm_test.bd_name);
  ASSERT_EQ(bd_addr, btm_test.bd_addr);
}

TEST_F(StackBtmWithInitFreeTest, btm_acl_role_changed_with_unknown_address) {
  const RawAddress bd_addr = RawAddress("01:02:03:04:05:06");
  const tHCI_ROLE new_role = HCI_ROLE_CENTRAL;

  // This should not crash and just log an error because there is no active ACL
  // connection for bd_addr.
  btm_acl_role_changed(HCI_SUCCESS, bd_addr, new_role);
}

TEST_F(StackBtmTest, sco_state_text) {
  std::vector<std::pair<tSCO_STATE, std::string>> states = {
          std::make_pair(SCO_ST_UNUSED, "SCO_ST_UNUSED"),
          std::make_pair(SCO_ST_LISTENING, "SCO_ST_LISTENING"),
          std::make_pair(SCO_ST_W4_CONN_RSP, "SCO_ST_W4_CONN_RSP"),
          std::make_pair(SCO_ST_CONNECTING, "SCO_ST_CONNECTING"),
          std::make_pair(SCO_ST_CONNECTED, "SCO_ST_CONNECTED"),
          std::make_pair(SCO_ST_DISCONNECTING, "SCO_ST_DISCONNECTING"),
          std::make_pair(SCO_ST_PEND_UNSNIFF, "SCO_ST_PEND_UNSNIFF"),
          std::make_pair(SCO_ST_PEND_ROLECHANGE, "SCO_ST_PEND_ROLECHANGE"),
          std::make_pair(SCO_ST_PEND_MODECHANGE, "SCO_ST_PEND_MODECHANGE"),
  };
  for (const auto& state : states) {
    ASSERT_STREQ(state.second.c_str(), sco_state_text(state.first).c_str());
  }
  std::ostringstream oss;
  oss << "unknown_sco_state: " << std::numeric_limits<std::uint16_t>::max();
  ASSERT_STREQ(oss.str().c_str(),
               sco_state_text(static_cast<tSCO_STATE>(std::numeric_limits<std::uint16_t>::max()))
                       .c_str());
}

TEST_F(StackBtmWithInitFreeTest, Init) { ASSERT_FALSE(btm_cb.rnr.remname_active); }
