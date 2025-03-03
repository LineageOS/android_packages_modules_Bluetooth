#include "stack/connection_manager/connection_manager.h"

#include <base/bind_helpers.h>
#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <memory>

#include "gd/hci/acl_manager_mock.h"
#include "gd/hci/controller_mock.h"
#include "main/shim/acl_api.h"
#include "main/shim/entry.h"
#include "main/shim/le_scanning_manager.h"
#include "osi/include/alarm.h"
#include "osi/test/alarm_mock.h"
#include "security_device_record.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/btm_log_history.h"
#include "stack/l2cap/internal/l2c_api.h"
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

const RawAddress address1{{0x01, 0x01, 0x01, 0x01, 0x01, 0x07}};
const RawAddress address2{{0x22, 0x22, 0x02, 0x22, 0x33, 0x22}};

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

namespace connection_manager {
void on_connection_timed_out(uint8_t app_id, const RawAddress& address) {
  localConnTimeoutMock->OnConnectionTimedOut(app_id, address);
}
}  // namespace connection_manager

namespace connection_manager {
class BleConnectionManager : public testing::Test {
  void SetUp() override {
    localConnTimeoutMock = std::make_unique<MockConnTimeout>();
    /* extern */ test::mock_acl_manager_ = new bluetooth::hci::testing::MockAclManager();
    /* extern */ test::mock_controller_ =
            new testing::NiceMock<bluetooth::hci::testing::MockControllerInterface>();
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
    delete test::mock_controller_;
    delete test::mock_acl_manager_;
    localConnTimeoutMock.reset();
  }
};

/** Verify that app can add a device to acceptlist, it is returned as interested
 * app, and then can remove the device later. */
TEST_F(BleConnectionManager, test_background_connection_add_remove) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  std::set<tAPP_ID> apps = get_apps_connecting_to(address1);
  EXPECT_EQ(apps.size(), 1UL);
  EXPECT_EQ(apps.count(CLIENT1), 1UL);

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(_, _)).Times(0);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);

  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 0UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify that multiple clients adding same device multiple times, result in
 * device being added to whtie list only once, also, that device is removed only
 * after last client removes it. */
TEST_F(BleConnectionManager, test_background_connection_multiple_clients) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_add(CLIENT2, address1));
  EXPECT_TRUE(background_connect_add(CLIENT3, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 3UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(_, _)).Times(0);

  // removing from nonexisting client, should fail
  EXPECT_FALSE(background_connect_remove(CLIENT10, address1));

  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));
  // already removed,  removing from same client twice should return false;
  EXPECT_FALSE(background_connect_remove(CLIENT1, address1));
  EXPECT_TRUE(background_connect_remove(CLIENT2, address1));

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT3, address1));

  EXPECT_EQ(get_apps_connecting_to(address1).size(), 0UL);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify adding/removing device to direct connection. */
TEST_F(BleConnectionManager, test_direct_connection_client) {
  // Direct connect attempt: use faster scan parameters, add to acceptlist,
  // start 30 timeout

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1));

  // App already doing a direct connection, attempt to re-add result in failure
  EXPECT_FALSE(direct_connect_add(CLIENT1, address1));

  // Client that don't do direct connection should fail attempt to stop it
  EXPECT_FALSE(direct_connect_remove(CLIENT2, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  // Removal should lower the connection parameters, and free the alarm.
  // Even though we call AcceptlistRemove, it won't be executed over HCI until
  // acceptlist is in use, i.e. next connection attempt
  EXPECT_TRUE(direct_connect_remove(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify direct connection timeout does remove device from acceptlist, and
 * lower the connection scan parameters */
TEST_F(BleConnectionManager, test_direct_connect_timeout) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  alarm_callback_t alarm_callback = nullptr;
  void* alarm_data = nullptr;

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .Times(1)
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback), SaveArg<3>(&alarm_data)));

  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT1, address1)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);

  // simulate timeout seconds passed, alarm executing
  alarm_callback(alarm_data);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify that we properly handle successfull direct connection */
TEST_F(BleConnectionManager, test_direct_connection_success) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);

  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
  // simulate event from lower layers - connections was established
  // successfully.
  on_connection_complete(address1);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify that we properly handle application unregistration */
TEST_F(BleConnectionManager, test_app_unregister) {
  /* Test scenario:
   * - Client 1 connecting to address1 and address2.
   * - Client 2 connecting to address2
   * - unregistration of Client1 should trigger address1 removal from acceptlist
   * - unregistration of Client2 should trigger address2 removal
   */

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address2_hci, false)).Times(1);
  EXPECT_TRUE(background_connect_add(CLIENT1, address2));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address2_hci, true)).Times(1);
  EXPECT_TRUE(direct_connect_add(CLIENT2, address2));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address1_hci)).Times(1);
  on_app_deregistered(CLIENT1);
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(address2_hci)).Times(1);
  on_app_deregistered(CLIENT2);
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

/** Verify adding device to both direct connection and background connection. */
TEST_F(BleConnectionManager, test_direct_and_background_connect) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, true)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);
  // add device as both direct and background connection
  EXPECT_TRUE(direct_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
  // not removing from acceptlist yet, as the background connection is still
  // pending.
  EXPECT_TRUE(direct_connect_remove(CLIENT1, address1));

  // remove from acceptlist, because no more interest in device.
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

TEST_F(BleConnectionManager, test_target_announement_connect) {
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT1, address1));
}

TEST_F(BleConnectionManager, test_add_targeted_announement_when_allow_list_used) {
  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);

  /* This shall be called when registering announcements */
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

TEST_F(BleConnectionManager, test_add_background_connect_when_targeted_announcement_are_enabled) {
  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(0);

  /* This shall be called when registering announcements */
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

TEST_F(BleConnectionManager, test_re_add_background_connect_to_allow_list) {
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(0);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_targeted_announcement_add(CLIENT2, address1));

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  /* Now remove app using targeted announcement and expect device
   * to be added to white list
   */

  /* Accept adding to allow list */
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);

  EXPECT_TRUE(background_connect_remove(CLIENT2, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(1);
  EXPECT_TRUE(background_connect_remove(CLIENT1, address1));
  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

TEST_F(BleConnectionManager, test_re_add_to_allow_list_after_timeout_with_multiple_clients) {
  EXPECT_CALL(*AlarmMock::Get(), AlarmNew(_)).Times(1);
  alarm_callback_t alarm_callback = nullptr;
  void* alarm_data = nullptr;

  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);

  EXPECT_TRUE(background_connect_add(CLIENT1, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .Times(1)
          .WillOnce(DoAll(SaveArg<2>(&alarm_callback), SaveArg<3>(&alarm_data)));
  // Start direct connect attempt...
  EXPECT_TRUE(direct_connect_add(CLIENT2, address1));

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);

  // simulate timeout seconds passed, alarm executing
  EXPECT_CALL(*localConnTimeoutMock, OnConnectionTimedOut(CLIENT2, address1)).Times(1);
  EXPECT_CALL(*test::mock_acl_manager_, CancelLeConnect(_)).Times(0);
  EXPECT_CALL(*test::mock_acl_manager_, CreateLeConnection(address1_hci, false)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmFree(_)).Times(1);
  alarm_callback(alarm_data);

  Mock::VerifyAndClearExpectations(test::mock_acl_manager_);
}

}  // namespace connection_manager
