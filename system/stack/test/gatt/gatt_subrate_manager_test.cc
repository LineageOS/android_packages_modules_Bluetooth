/*
 * Copyright 2021 The Android Open Source Project
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
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include "hci/controller_mock.h"
#include "stack/btm/btm_int_types.h"
#include "stack/gatt/gatt_int.h"
#include "stack/l2cap/l2c_int.h"
#include "stack/mock/mock_stack_acl.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/mock/mock_stack_l2cap_utils.h"
#include "stack/mock/mock_stack_sdp_legacy_api.h"
#include "test/mock/mock_main_shim_entry.h"

#define TEST_BT com::android::bluetooth::flags

extern tGATT_CB gatt_cb;

using ::testing::_;
using ::testing::DoAll;
using ::testing::MockFunction;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::NiceMock;
using ::testing::Eq;

RawAddress test_addr_("C0:DE:C0:DE:00:00");

class GattSubrateManagerTest : public ::testing::Test {
protected:
void SetUp() override {
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    ON_CALL(*bluetooth::hci::testing::mock_controller_,
        SupportsBleConnectionSubrating()).WillByDefault(Return(true));
    test::mock::stack_acl::acl_peer_supports_ble_connection_subrating.body
        = [](const RawAddress& /*bd_addr*/) { return true; };
    test::mock::stack_acl::acl_peer_supports_ble_connection_subrating_host.body
        = [](const RawAddress& /*bd_addr*/) { return true; };
    test::mock::stack_acl::acl_link_is_disconnecting.body =
        [](const RawAddress& /*bd_addr*/, tBT_TRANSPORT /*transport*/) { return false; };
    test::mock::stack_l2cap_utils::l2cu_find_lcb_by_bd_addr.body
        = [](const RawAddress& /*bd_addr*/, tBT_TRANSPORT /* transport */)
            { return (tL2C_LCB*) malloc(sizeof(tL2C_LCB)); };
    test::mock::stack_sdp_legacy::api_.SDP_CreateRecord = []() { return uint32_t(0x10000); };
    test::mock::stack_sdp_legacy::api_.SDP_AddServiceClassIdList =
            [](uint32_t /*handle*/, uint16_t /*num_services*/, uint16_t* /*p_service_uuids*/) {
              return true;
            };
    test::mock::stack_sdp_legacy::api_.SDP_AddAttribute =
            [](uint32_t /*handle*/, uint16_t /*attr_id*/, uint8_t /*attr_type*/,
               uint32_t /*attr_len*/, uint8_t* /*p_val*/) { return true; };
    test::mock::stack_sdp_legacy::api_.SDP_AddProtocolList =
            [](uint32_t /*handle*/, uint16_t /*num_elem*/, tSDP_PROTOCOL_ELEM* /*p_elem_list*/) {
              return true;
            };
    test::mock::stack_sdp_legacy::api_.SDP_AddUuidSequence =
            [](uint32_t /*handle*/, uint16_t /*attr_id*/, uint16_t /*num_uuids*/,
               uint16_t* /*p_uuids*/) { return true; };
    gatt_init();
  }

  void TearDown() override {
    bluetooth::hci::testing::mock_controller_.reset();
    test::mock::stack_sdp_legacy::api_ = {};
    gatt_free();
  }

  tGATT_IF test_client_if_ = 10;
  testing::NiceMock<bluetooth::testing::stack::l2cap::Mock> mock_stack_l2cap_interface_;
};

/*
   Part 1: Initialization and Cleanup Tests
*/

TEST_F(GattSubrateManagerTest, InitSubrateCb_Supported) {
    // Setup Mocks: Subrate is supported by both local controller and peer
    ON_CALL(*bluetooth::hci::testing::mock_controller_,
        SupportsBleConnectionSubrating()).WillByDefault(Return(true));

    gatt_init_subrate_cb(test_addr_);

    // Verify that the control block was initialized
    ASSERT_TRUE(gatt_cb.subrate_info.contains(test_addr_));
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
    gatt_cb.subrate_info = {};
}

TEST_F(GattSubrateManagerTest, InitSubrateCb_NotSupported) {
    // Setup Mocks: Subrate is NOT supported by the peer (using not_supported_addr_)
    ON_CALL(*bluetooth::hci::testing::mock_controller_,
        SupportsBleConnectionSubrating()).WillByDefault(Return(false));

    gatt_init_subrate_cb(test_addr_);

    // Verify that the control block was NOT initialized
    ASSERT_FALSE(gatt_cb.subrate_info.contains(test_addr_));
}

TEST_F(GattSubrateManagerTest, ReleaseSubrateCb) {
    // Setup: Initialize the CB first
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{.bda = test_addr_};
    ASSERT_TRUE(gatt_cb.subrate_info.contains(test_addr_));

    // Action
    gatt_release_subrate_cb(test_addr_);

    // Verification
    EXPECT_FALSE(gatt_cb.subrate_info.contains(test_addr_));
}

/*
  Part 2 : Subrate Request and Priority Tests
*/

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_TriggersLowRequest) {
    // Setup: CB exists and L2CAP mocks
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // Mock L2CAP parameters for calculation: Conn Interval = 8 (8 * 1.25 = 10 ms), Latency = 4
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // The target_latency is 10 * (4 + 1) = 50.
    // subrate_max = ceil(50 / 10) = 5. subrate_min = 5 - 2 = 3.
    // cont_num for LOW (ratio 25) = 3 * 25 / 100 = 1.
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_SubrateRequest(Eq(test_addr_), Eq(3), Eq(5), Eq(0), Eq(1), Eq(500)))
            .Times(1).WillOnce(Return(true));

    // Action: Register a LOW mode request
    bool success = gatt_register_subrate_config(test_client_if_,
                                                test_addr_, GATT_SUBRATE_MODE_LOW);

    // Verification
    EXPECT_TRUE(success);
    // State should be CONFIG_PENDING
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // Pending queue should be empty (consumed by process_subrate_request)
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());
    // Config map should contain the client
    EXPECT_FALSE(gatt_cb.subrate_info.at(test_addr_).
                    config_map.at(GATT_SUBRATE_MODE_LOW).empty());
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_TriggersBalancedRequest) {
    // Setup: CB exists and L2CAP mocks
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // Mock L2CAP parameters for calculation: Conn Interval = 8 (8 * 1.25 = 10 ms), Latency = 4
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // The target_latency is 10 * (4 + 1) = 50.
    // subrate_max = ceil(50 / 10) = 5. subrate_min = 5 - 2 = 3.
    // cont_num for BALANCED (ratio 50) = 3 * 50 / 100 = 2.
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_SubrateRequest(Eq(test_addr_), Eq(3), Eq(5), Eq(0), Eq(2), Eq(500)))
            .Times(1).WillOnce(Return(true));

    // Action: Register a BALANCED mode request
    bool success = gatt_register_subrate_config(test_client_if_,
                                                test_addr_, GATT_SUBRATE_MODE_BALANCED);

    // Verification
    EXPECT_TRUE(success);
    // State should be CONFIG_PENDING
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // Pending queue should be empty (consumed by process_subrate_request)
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());
    // Config map should contain the client
    EXPECT_FALSE(gatt_cb.subrate_info.at(test_addr_).
                    config_map.at(GATT_SUBRATE_MODE_BALANCED).empty());
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_TriggersHighRequest) {
    // Setup: CB exists and L2CAP mocks
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // Mock L2CAP parameters for calculation: Conn Interval = 8 (8 * 1.25 = 10 ms), Latency = 4
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // The target_latency is 10 * (4 + 1) = 50.
    // subrate_max = ceil(50 / 10) = 5. subrate_min = 5 - 2 = 3.
    // cont_num for HIGH (ratio 75) = 3 * 75 / 100 = 2.
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_SubrateRequest(Eq(test_addr_), Eq(3), Eq(5), Eq(0), Eq(2), Eq(500)))
            .Times(1).WillOnce(Return(true));

    // Action: Register a HIGH mode request
    bool success = gatt_register_subrate_config(test_client_if_,
                                                test_addr_, GATT_SUBRATE_MODE_HIGH);

    // Verification
    EXPECT_TRUE(success);
    // State should be CONFIG_PENDING
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // Pending queue should be empty (consumed by process_subrate_request)
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());
    // Config map should contain the client
    EXPECT_FALSE(gatt_cb.subrate_info.at(test_addr_).
                    config_map.at(GATT_SUBRATE_MODE_HIGH).empty());
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_TriggersLeaRequest) {
    // Setup: CB exists and L2CAP mocks
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // Mock L2CAP parameters for calculation: Conn Interval = 8 (8 * 1.25 = 10 ms), Latency = 4
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // LEA Parameters are fixed
    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_SubrateRequest(Eq(test_addr_),
                                    Eq(kDefaultSubrateLeAudioModeMinSubrate),
                                    Eq(kDefaultSubrateLeAudioModeMaxSubrate),
                                    Eq(0), Eq(kDefaultSubrateLeAudioModeContNum),
                                    Eq(500)))
            .Times(1).WillOnce(Return(true));

    // Action: Register a LEA mode request
    bool success = gatt_register_subrate_config(test_client_if_,
                                                test_addr_, GATT_SUBRATE_MODE_LEA);

    // Verification
    EXPECT_TRUE(success);
    // State should be CONFIG_PENDING
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // Pending queue should be empty (consumed by process_subrate_request)
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());
    // Config map should contain the client
    EXPECT_FALSE(gatt_cb.subrate_info.at(test_addr_).
                    config_map.at(GATT_SUBRATE_MODE_LEA).empty());
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_PriorityHandling) {
    // Setup: CB exists. L2CAP mocks for required parameters (using minimal expectations)
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // There is MODE HIGH from client_if = 1 in pending_queue
    tGATT_SUBRATE_REQ req = {
        .request_type = GATT_SUBRATE_REQ_TYPE_NEW_REQ,
        .client_if = 1,
        .bda = test_addr_,
        .mode = GATT_SUBRATE_MODE_HIGH,
    };

    gatt_cb.subrate_info[test_addr_].pending_queue.push_back(req);

    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(Eq(test_addr_))).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(Eq(test_addr_))).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(Eq(test_addr_))).WillRepeatedly(Return(500));

    EXPECT_FALSE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());

    // Expect the highest priority mode (HIGH) to be sent: subrate_min=3, subrate_max=5, cont_num=2
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_SubrateRequest(Eq(test_addr_), Eq(3), Eq(5), Eq(0), Eq(2), Eq(500)))
            .Times(1).WillOnce(Return(true));

    // Register a LOW mode request (LOW priority)
    gatt_register_subrate_config(2, test_addr_, GATT_SUBRATE_MODE_LOW);

    // Verification: The second call should trigger an update which selects HIGH mode
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // The pending config should reflect the HIGH mode settings
    EXPECT_EQ(GATT_SUBRATE_MODE_HIGH, gatt_cb.subrate_info.at(test_addr_).pending_config.mode);
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_OffModeRemovesFromMap) {
    // Setup: Already in HIGH mode with client 1
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};
    gatt_cb.subrate_info.at(test_addr_).config_map[GATT_SUBRATE_MODE_HIGH].push_back(1);
    gatt_cb.subrate_info.at(test_addr_).current_config = {
        .mode = GATT_SUBRATE_MODE_HIGH,
        .subrate_min = 3,
        .subrate_max = 5,
        .cont_num = 2,
        .subrate_factor = 4, // Factor is within [3, 5]
    };

    // Mock L2CAP parameters (prevent logs)
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // Expect an OFF mode request to be sent:
    // subrate_min=1, subrate_max=1, cont_num=0, max_latency=periph_latency (4)
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_SubrateRequest(
        _, Eq(1), Eq(1), Eq(4), Eq(0), _))
        .Times(1).WillOnce(Return(true));

    // Action: Client 1 registers OFF mode
    gatt_register_subrate_config(1, test_addr_, GATT_SUBRATE_MODE_OFF);

    // Verification: Client 1 is removed from the HIGH map, and OFF mode is the target
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).config_map.at(GATT_SUBRATE_MODE_HIGH).empty());
    EXPECT_EQ(GATT_SUBRATE_MODE_OFF, gatt_cb.subrate_info.at(test_addr_).pending_config.mode);
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_NoUpdateNeeded) {
    // Setup: Manager is in a specific state.
    // A new request arrives, but it doesn't change the target mode.
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};
    // Add an existing client to the highest map mode
    gatt_cb.subrate_info.at(test_addr_).config_map[GATT_SUBRATE_MODE_HIGH].push_back(1);
    // Pre-populate the current config to match the target config
    gatt_cb.subrate_info.at(test_addr_).current_config = {
        .mode = GATT_SUBRATE_MODE_HIGH,
        .subrate_min = 3,
        .subrate_max = 5,
        .cont_num = 2,
        .subrate_factor = 4, // Factor is within [3, 5]
    };

    // Mock L2CAP parameters for calculation:
    // Conn Interval = 8, Latency = 4, resulting in target [2, 4]
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(_)).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(_)).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(_)).WillRepeatedly(Return(500));

    // Expect: NO L2CAP request is sent, but the notification is triggered for the new client
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_SubrateRequest(_, _, _, _, _, _)).Times(0);

    // Action: Register a new client (client 2) also for HIGH mode
    gatt_register_subrate_config(2, test_addr_, GATT_SUBRATE_MODE_HIGH);

    // Verification
    // State remains IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
    // Config map now contains both clients
    EXPECT_EQ(2,
        (int)gatt_cb.subrate_info.at(test_addr_).config_map.at(GATT_SUBRATE_MODE_HIGH).size());
}

TEST_F(GattSubrateManagerTest, RegisterSubrateConfig_AclDisconnecting) {
    // Setup: ACL link is disconnecting
    test::mock::stack_acl::acl_link_is_disconnecting.body =
        [](const RawAddress& /*bd_addr*/, tBT_TRANSPORT /*transport*/) { return true; };

    gatt_cb.subrate_info[test_addr_] =
        tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_IDLE};

    // Action: Register a LOW mode request
    bool success = gatt_register_subrate_config(
        test_client_if_, test_addr_, GATT_SUBRATE_MODE_LOW);

    // Verification
    EXPECT_FALSE(success);
    // Pending queue should be empty as the request should be rejected early
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).pending_queue.empty());
    // Config map should not contain the client
    EXPECT_TRUE(gatt_cb.subrate_info.at(test_addr_).config_map.empty());
    // State should remain IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
}

/*
  Part 3 : Callback and Retry Handling Tests: Subrate Changed and Connection Updated Changed
*/

TEST_F(GattSubrateManagerTest, HandleSubrateCallback_Success) {
    // Setup: Manager is CONFIG_PENDING
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_CONFIG_PENDING};
    gatt_cb.subrate_info.at(test_addr_).pending_config.mode = GATT_SUBRATE_MODE_HIGH;

    // Action: Simulate a successful response from L2CAP
    bool result = gatt_handle_subrate_cback_status(
        test_addr_,
        4,      // subrate_factor (new)
        0,      // latency (new)
        2,      // cont_num (new)
        500,    // timeout (new)
        HCI_SUCCESS);

    // Verification
    EXPECT_TRUE(result);
    // State should reset to IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
    // Current config should be updated
    EXPECT_EQ(4, gatt_cb.subrate_info.at(test_addr_).current_config.subrate_factor);
    EXPECT_EQ(GATT_SUBRATE_MODE_HIGH, gatt_cb.subrate_info.at(test_addr_).current_config.mode);
    EXPECT_EQ(0, gatt_cb.subrate_info.at(test_addr_).retry_count);
    // Pending config should be cleared
    EXPECT_EQ(GATT_SUBRATE_MODE_OFF, gatt_cb.subrate_info.at(test_addr_).pending_config.mode);
}

TEST_F(GattSubrateManagerTest, HandleSubrateCallback_FailureAndRetry) {
    // Setup: Manager is CONFIG_PENDING and retry_count is 0
    gatt_cb.subrate_info[test_addr_]
        = tGATT_SUBRATE_MGR_CB{.bda = test_addr_, .state = GATT_SUBRATE_SM_CONFIG_PENDING};
    gatt_cb.subrate_info.at(test_addr_).pending_config = {
        .mode = GATT_SUBRATE_MODE_HIGH,
        .conn_interval = 10,
        .subrate_min = 3,
        .subrate_max = 5,
        .max_latency = 0,
        .cont_num = 2,
        .timeout = 500,
    };

    // Action: Simulate a failure response from L2CAP
    bool result = gatt_handle_subrate_cback_status(
        test_addr_, 0, 0, 0, 0, 0x0C); // HCI_COMMAND_DISALLOWED_ERROR

    // Verification
    EXPECT_FALSE(result);
    // State should remain CONFIG_PENDING
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
    // Retry count should increment
    EXPECT_EQ(1, gatt_cb.subrate_info.at(test_addr_).retry_count);
}

TEST_F(GattSubrateManagerTest, HandleSubrateCallback_FailureMaxRetries) {
    // Setup: Manager is CONFIG_PENDING and retry_count is max (e.g., GATT_SUBRATE_MAX_RETRY = 3)
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{
        .bda = test_addr_,
        .state = GATT_SUBRATE_SM_CONFIG_PENDING,
        .retry_count = GATT_SUBRATE_MAX_RETRY // Max retries reached
    };

    // Action: Simulate a failure response
    bool result = gatt_handle_subrate_cback_status(
        test_addr_, 0, 0, 0, 0, 0x0C); // HCI_COMMAND_DISALLOWED_ERROR

    // Verification
    EXPECT_TRUE(result);
    // State should reset to IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
    // Retry count should reset to 0
    EXPECT_EQ(0, gatt_cb.subrate_info.at(test_addr_).retry_count);
    // Pending config should be cleared
    EXPECT_EQ(GATT_SUBRATE_MODE_OFF, gatt_cb.subrate_info.at(test_addr_).pending_config.mode);
}

TEST_F(GattSubrateManagerTest, HandleSubrateCallback_RemoteUpdateInIdleState) {
    // Setup: Manager is IDLE and has some current config
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{
        .bda = test_addr_,
        .state = GATT_SUBRATE_SM_IDLE,
        .current_config = {.mode = GATT_SUBRATE_MODE_BALANCED, .cont_num = 1, .subrate_factor = 2}
    };

    // Action: Simulate a successful, but different, subrate update from the remote
    bool result = gatt_handle_subrate_cback_status(
        test_addr_,
        3,      // New subrate_factor
        0,      // latency
        2,      // New cont_num
        500,    // timeout
        HCI_SUCCESS);

    // Verification
    EXPECT_TRUE(result);
    // State remains IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
    // Current config should reflect the new remote parameters
    EXPECT_EQ(3, gatt_cb.subrate_info.at(test_addr_).current_config.subrate_factor);
    EXPECT_EQ(2, gatt_cb.subrate_info.at(test_addr_).current_config.cont_num);
    // Mode should be marked as SYSTEM_UPDATE
    EXPECT_EQ(GATT_SUBRATE_MODE_SYSTEM_UPDATE,
        gatt_cb.subrate_info.at(test_addr_).current_config.mode);
}


TEST_F(GattSubrateManagerTest, HandleConnUpdateCallback_NoSubrateUpdateChangeIdleMode) {
    // Setup: Manager is IDLE and has some current config
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{
        .bda = test_addr_,
        .state = GATT_SUBRATE_SM_IDLE,
        .current_config = {.mode = GATT_SUBRATE_MODE_OFF}
    };

    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(Eq(test_addr_))).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(Eq(test_addr_))).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(Eq(test_addr_))).WillRepeatedly(Return(500));

    // Expect: NO L2CAP request is sent, but the notification is triggered for the new client
    EXPECT_CALL(mock_stack_l2cap_interface_, L2CA_SubrateRequest(_, _, _, _, _, _)).Times(0);

    // Action: Simulate a successful, but different, subrate update from the remote
    gatt_handle_conn_parameter_cback_status(
        test_addr_,
        8);

    // State remains IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_IDLE, gatt_cb.subrate_info.at(test_addr_).state);
}

TEST_F(GattSubrateManagerTest, HandleConnUpdateCallback_SameFactorWithNonIdleMode) {
    // Setup: Manager is IDLE and has some current config
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{
        .bda = test_addr_,
        .state = GATT_SUBRATE_SM_IDLE,
        .current_config = {.mode = GATT_SUBRATE_MODE_HIGH}
    };
    // Add an existing client to the highest map mode
    gatt_cb.subrate_info.at(test_addr_).config_map[GATT_SUBRATE_MODE_HIGH].push_back(1);
    // Pre-populate the current config to match the target config
    gatt_cb.subrate_info.at(test_addr_).current_config = {
        .mode = GATT_SUBRATE_MODE_HIGH,
        .subrate_min = 3,
        .subrate_max = 5,
        .cont_num = 2,
        .subrate_factor = 4, // Factor is within [3, 5]
    };

    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(Eq(test_addr_))).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(Eq(test_addr_))).WillRepeatedly(Return(4));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(Eq(test_addr_))).WillRepeatedly(Return(500));

    // Expect: Subrate request is sent
    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_SubrateRequest(Eq(test_addr_), Eq(3), Eq(5), Eq(0), Eq(2),
                                    Eq(500)))
        .Times(1);

    // Action: Simulate a successful, but different, subrate update from the remote
    gatt_handle_conn_parameter_cback_status(
        test_addr_,
        8);

    // State remains IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
}

TEST_F(GattSubrateManagerTest, HandleConnUpdateCallback_UpdateFactorWithNonIdleMode) {
    // Setup: Manager is IDLE and has some current config
    gatt_cb.subrate_info[test_addr_] = tGATT_SUBRATE_MGR_CB{
        .bda = test_addr_,
        .state = GATT_SUBRATE_SM_IDLE,
        .current_config = {.mode = GATT_SUBRATE_MODE_HIGH}
    };
    // Add an existing client to the highest map mode
    gatt_cb.subrate_info.at(test_addr_).config_map[GATT_SUBRATE_MODE_HIGH].push_back(1);
    // Pre-populate the current config to match the target config
    gatt_cb.subrate_info.at(test_addr_).current_config = {
        .mode = GATT_SUBRATE_MODE_HIGH,
        .subrate_min = 3,
        .subrate_max = 5,
        .cont_num = 2,
        .subrate_factor = 4, // Factor is within [3, 5]
    };

    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleConnInterval(Eq(test_addr_))).WillRepeatedly(Return(8));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBlePeriphLatency(Eq(test_addr_))).WillRepeatedly(Return(5));
    EXPECT_CALL(mock_stack_l2cap_interface_,
        L2CA_GetBleSupervisionTimeout(Eq(test_addr_))).WillRepeatedly(Return(500));

    // Expect: Subrate request is sent
    EXPECT_CALL(mock_stack_l2cap_interface_,
                L2CA_SubrateRequest(Eq(test_addr_), Eq(4), Eq(6), Eq(0), Eq(3),
                                    Eq(500)))
        .Times(1);

    // Action: Simulate a successful, but different, subrate update from the remote
    gatt_handle_conn_parameter_cback_status(
        test_addr_,
        8);

    // State remains IDLE
    EXPECT_EQ(GATT_SUBRATE_SM_CONFIG_PENDING, gatt_cb.subrate_info.at(test_addr_).state);
}
