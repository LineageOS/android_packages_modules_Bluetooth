/*
 * Copyright (C) 2026 The Android Open Source Project
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
#include "stack/connection_manager/connection_manager.h"

#include <base/bind_helpers.h>
#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include <memory>

#include "gd/hci/acl_manager/acl_manager_le_mock.h"
#include "osi/include/alarm.h"
#include "osi/test/alarm_mock.h"
#include "test/mock/mock_main_shim_entry.h"

using testing::_;
using testing::DoAll;
using testing::Eq;
using testing::Mock;
using testing::Return;
using testing::SaveArg;

using bluetooth::hci::AddressWithType;

using connection_manager::tAPP_ID;
namespace test = bluetooth::hci::testing;

const RawAddress address1("01:01:01:01:01:07");
const RawAddress address2("22:22:02:22:33:22");

const AddressWithType address1_hci{{0x07, 0x01, 0x01, 0x01, 0x01, 0x01},
                                   bluetooth::hci::AddressType::PUBLIC_DEVICE_ADDRESS};
const AddressWithType address2_hci{{0x22, 0x33, 0x22, 0x02, 0x22, 0x22},
                                   bluetooth::hci::AddressType::PUBLIC_DEVICE_ADDRESS};

constexpr tAPP_ID CLIENT1 = 1;
constexpr tAPP_ID CLIENT2 = 2;
constexpr tAPP_ID CLIENT3 = 3;
constexpr tAPP_ID CLIENT10 = 10;

std::string get_client_name(uint8_t /* gatt_if */) { return ""; }

class MockConnTimeout {
public:
  MOCK_METHOD2(OnConnectionTimedOut, void(uint8_t, const RawAddress&));
};

std::unique_ptr<MockConnTimeout> localConnTimeoutMock;

static bool call_connection_complete_in_callback = false;

namespace connection_manager {
void on_connection_timed_out(uint8_t app_id, const RawAddress& address) {
  localConnTimeoutMock->OnConnectionTimedOut(app_id, address);
  if (call_connection_complete_in_callback) {
    on_connection_complete(address);
  }
}
}  // namespace connection_manager

namespace connection_manager {
class BleConnectionManager : public testing::Test {
  void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    call_connection_complete_in_callback = false;
    localConnTimeoutMock = std::make_unique<MockConnTimeout>();
    /* extern */ test::mock_acl_manager_ =
            std::make_unique<bluetooth::hci::testing::MockAclManager>();
    /* extern */ test::mock_controller_ =
            std::make_unique<testing::NiceMock<bluetooth::hci::testing::MockController>>();
    ON_CALL(*test::mock_controller_, GetLeFilterAcceptListSize()).WillByDefault(Return(16));

    auto alarm_mock = AlarmMock::Get();
    ON_CALL(*alarm_mock, AlarmNew(_)).WillByDefault(testing::Invoke([](const char* /*name*/) {
      // We must return something from alarm_new in tests, if we just return
      // null, unique_ptr will misbehave.
      return (alarm_t*)new uint8_t[30];
    }));
    ON_CALL(*alarm_mock, AlarmFree(_)).WillByDefault(testing::Invoke([](alarm_t* alarm) {
      if (alarm) {
        uint8_t* ptr = (uint8_t*)alarm;
        delete[] ptr;
      }
    }));
  }

  void TearDown() override {
    connection_manager::reset(true);
    AlarmMock::Reset();
    test::mock_controller_.reset();
    test::mock_acl_manager_.reset();
    localConnTimeoutMock.reset();
  }
};

/** Verify that app can add a device to acceptlist, it is returned as interested
 * app, and then can remove the device later. */
TEST_F(BleConnectionManager, test_background_connection_add_remove) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  std::set<tAPP_ID> apps = get_apps_connecting_to(address1);
  EXPECT_EQ(apps.size(), 1UL);
  EXPECT_EQ(apps.count(CLIENT1), 1UL);

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(_, _, _)).Times(0);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);

  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 0UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify that multiple clients adding same device multiple times, result in
 * device being added to whtie list only once, also, that device is removed only
 * after last client removes it. */
TEST_F(BleConnectionManager, test_background_connection_multiple_clients) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_add(CLIENT2, address1));
  EXPECT_TRUE(background_connect_add(CLIENT3, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 3UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(_, _, _)).Times(0);

  // removing from nonexisting client, should fail
  EXPECT_FALSE(background_connect_remove(CLIENT10, address1));

  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));
  // already removed,  removing from same client twice should return false;
  EXPECT_FALSE(background_connect_remove(CLIENT1, address1));
  EXPECT_TRUE(background_connect_remove(CLIENT2, address1));

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT3, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 0UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify adding/removing device to direct connection. */
TEST_F(BleConnectionManager, test_direct_connection_client) {
  // Direct connect attempt: use faster scan parameters, add to acceptlist,
  // start 30 timeout

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  // App already doing a direct connection, do nothing
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  // Client that don't do direct connection should fail attempt to stop it
  EXPECT_FALSE(direct_connect_remove(CLIENT2, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  // Removal should lower the connection parameters, and free the alarm.
  // Even though we call AcceptlistRemove, it won't be executed over HCI until
  // acceptlist is in use, i.e. next connection attempt
  EXPECT_TRUE(direct_connect_remove(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify direct connection timeout does remove device from acceptlist, and
 * lower the connection scan parameters */
TEST_F(BleConnectionManager, test_direct_connect_timeout) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  alarm_callback_t alarm_callback = nullptr;
  void* alarm_data = nullptr;

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .Times(1)
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback), SaveArg<3>(&alarm_data)));

  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT1, address1)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  // simulate timeout seconds passed, alarm executing
  alarm_callback(alarm_data);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify that we properly handle successfull direct connection */
TEST_F(BleConnectionManager, test_direct_connection_success) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);

  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
  // simulate event from lower layers - connections was established
  // successfully.
  on_connection_maybe(address1);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  on_connection_complete(address1);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify that we properly handle application unregistration */
TEST_F(BleConnectionManager, test_app_unregister) {
  /* Test scenario:
   * - Client 1 connecting to address1 and address2.
   * - Client 2 connecting to address2
   * - unregistration of Client1 should trigger address1 removal from acceptlist
   * - unregistration of Client2 should trigger address2 removal
   */

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address2_hci, false, false)).Times(1);
  EXPECT_TRUE(background_connect_add(CLIENT1, address2));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address2_hci, true, false)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT2, address2, /* prefer_relax_mode */ false));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  on_app_deregistered(CLIENT1);
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address2_hci)).Times(1);
  on_app_deregistered(CLIENT2);
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

/** Verify adding device to both direct connection and background connection. */
TEST_F(BleConnectionManager, test_direct_and_background_connect) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);
  // add device as both direct and background connection
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
  // not removing from acceptlist yet, as the background connection is still
  // pending.
  EXPECT_TRUE(direct_connect_remove(CLIENT1, address1));

  // remove from acceptlist, because no more interest in device.
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_direct_and_background_connect__direct_timeouts) {
  call_connection_complete_in_callback = true;

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  alarm_callback_t alarm_callback = nullptr;
  void* alarm_data = nullptr;

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .Times(1)
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback), SaveArg<3>(&alarm_data)));
  // add device as both direct and background connection
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  if (!com_android_bluetooth_flags_gd_conn_mgr_one_timeout()) {
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  }
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT1, address1)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  if (!com_android_bluetooth_flags_gd_conn_mgr_one_timeout()) {
    EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);
  } else {
    /* it's a timeout - background connect should stay, we should NOT cancel it or have to
     * send it again to lower layers */
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
    EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(0);
    EXPECT_CALL(*test::mock_acl_manager_, CancelDirectConnect(address1_hci)).Times(1);
  }
  // simulate timeout on direct connect
  alarm_callback(alarm_data);

  std::set<tAPP_ID> apps = get_apps_connecting_to(address1);
  EXPECT_EQ(apps.size(), 1UL);
  EXPECT_EQ(apps.count(CLIENT1), 1UL);
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(localConnTimeoutMock.get());
}

TEST_F(BleConnectionManager, test_target_announement_connect) {
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT1, address1));
}

TEST_F(BleConnectionManager, test_add_targeted_announement_when_allow_list_used) {
  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);

  /* This shall be called when registering announcements */
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_add_background_connect_when_targeted_announcement_are_enabled) {
  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(0);

  /* This shall be called when registering announcements */
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_re_add_background_connect_to_allow_list) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(0);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  /* Now remove app using targeted announcement and expect device
   * to be added to white list
   */

  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);

  EXPECT_TRUE(background_connect_remove(CLIENT2, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_re_add_to_allow_list_after_timeout_with_multiple_clients) {
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  alarm_callback_t alarm_callback = nullptr;
  void* alarm_data = nullptr;

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .Times(1)
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback), SaveArg<3>(&alarm_data)));
  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT2, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());

  // simulate timeout seconds passed, alarm executing
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT2, address1)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  if (com_android_bluetooth_flags_gd_conn_mgr_one_timeout()) {
    EXPECT_CALL(*test::mock_acl_manager_, CancelDirectConnect(address1_hci)).Times(1);
  } else {
    EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false, false)).Times(1);
  }
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  alarm_callback(alarm_data);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_direct_connection_add_remove_from_multiple_clients) {
  alarm_callback_t alarm_callback1 = nullptr;
  void* alarm_data1 = nullptr;
  alarm_callback_t alarm_callback2 = nullptr;
  void* alarm_data2 = nullptr;

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback1), SaveArg<3>(&alarm_data1)));

  // Client 1 connects
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(AlarmMock::Get());

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
    EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
            .WillOnce(DoAll(SaveArg<2>(&alarm_callback2), SaveArg<3>(&alarm_data2)));
  }

  // Expect NO CreateLeConnection call as one is already pending to same address
  // This is same expectation without the flag as it was merging the 2nd req with first one
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(0);

  // Client 2 connects to same address
  // Should NOT call CreateLeConnection again
  EXPECT_TRUE(direct_connect_add(CLIENT2, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(AlarmMock::Get());

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    // Crucial check: Should NOT cancel connection because Client 2 is still waiting
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  } else {
    // with previous implementation, cancelLeConnect was called on remove from 1st client
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  }

  // Now remove client1 & ensure no LE cancel connection called
  EXPECT_TRUE(direct_connect_remove(CLIENT1, address1));

  // try to remove the same client again & expect FALSE
  EXPECT_FALSE(direct_connect_remove(CLIENT1, address1));

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    // Now remove conn req from client2 & expect the LE cancel connection
    Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
    EXPECT_TRUE(direct_connect_remove(CLIENT2, address1));
  } else {
    // with previous implementation there was nothing added to queue
    // and hence no pending thing to remove
    EXPECT_FALSE(direct_connect_remove(CLIENT2, address1));
  }

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
}

TEST_F(BleConnectionManager, test_direct_connection_multiple_clients_timeout) {
  alarm_callback_t alarm_callback1 = nullptr;
  void* alarm_data1 = nullptr;
  alarm_callback_t alarm_callback2 = nullptr;
  void* alarm_data2 = nullptr;

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback1), SaveArg<3>(&alarm_data1)));

  // Client 1 connects
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(AlarmMock::Get());

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
    EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
            .WillOnce(DoAll(SaveArg<2>(&alarm_callback2), SaveArg<3>(&alarm_data2)));
  }

  // Expect NO CreateLeConnection call as one is already pending to same address
  // This is same expectation without the flag as it was merging the 2nd req with first one
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true, false)).Times(0);

  // Client 2 connects to same address
  // Should NOT call CreateLeConnection again
  EXPECT_TRUE(direct_connect_add(CLIENT2, address1, /* prefer_relax_mode */ false));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(AlarmMock::Get());

  // Client 1 times out
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT1, address1)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    // Crucial check: Should NOT cancel connection because Client 2 is still waiting
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  } else {
    // with previous implementation, connection was released after the 1st client cancels it
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  }

  alarm_callback1(alarm_data1);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  Mock::VerifyAndClearExpectations(localConnTimeoutMock.get());
  Mock::VerifyAndClearExpectations(AlarmMock::Get());

  if (com_android_bluetooth_flags_cancel_pending_le_conn_on_socket_close()) {
    // Client 2 times out
    EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT2, address1)).Times(1);
    EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
    EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);

    alarm_callback2(alarm_data2);

    Mock::VerifyAndClearExpectations(test::mock_acl_manager_.get());
  } else {
    // with previous implementation, connection was released after the 1st client cancels it
    // and there was no entry penging in the queue
  }
}
}  // namespace connection_manager
