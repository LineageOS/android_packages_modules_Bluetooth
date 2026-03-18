/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ccp_client.h"

#include <base/functional/bind.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/include/bta_api.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "ccp/ccp_types.h"
#include "gatt/database_builder.h"
#include "hardware/bt_le_audio.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_security_client_interface.h"
#include "test/common/bta_gatt_queue_mock.h"
#include "test/common/btm_api_mock.h"
#include "test/common/mock_functions.h"

using namespace bluetooth;
using namespace bluetooth::ccp;

using ::testing::_;
using ::testing::DoAll;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::WithArg;

// Mock callbacks to verify interactions with the upper layer.
class MockCcpClientCallbacks : public CcpClientCallbacks {
public:
  MOCK_METHOD(void, OnConnectionState, (const RawAddress&, le_audio::ConnectionState), (override));
  MOCK_METHOD(void, OnDiscovered, (const RawAddress&), (override));
  MOCK_METHOD(void, OnCallState, (const RawAddress&, const std::vector<Call>&), (override));
  MOCK_METHOD(void, OnCallControlResult, (const RawAddress&, uint8_t, uint8_t, CallControlResultCode),
              (override));
  MOCK_METHOD(void, OnStatusFlags, (const RawAddress&, uint8_t), (override));
  MOCK_METHOD(void, OnBearerProviderName, (const RawAddress&, const std::string&), (override));
  MOCK_METHOD(void, OnBearerTechnology, (const RawAddress&, uint8_t), (override));
  MOCK_METHOD(void, OnOpcodesSupportedChanged, (const RawAddress&, uint32_t), (override));
  MOCK_METHOD(void, OnBearerUriSchemesSupportedChanged, (const RawAddress&, const std::string&),
              (override));
  MOCK_METHOD(void, OnTerminationReason, (const RawAddress&, uint8_t, TerminationReasonCode),
              (override));
  MOCK_METHOD(void, OnIncomingCall, (const RawAddress&, uint8_t, const std::string&), (override));
  MOCK_METHOD(void, OnIncomingCallTarget, (const RawAddress&, uint8_t, const std::string&),
              (override));
  MOCK_METHOD(void, OnCallFriendlyName, (const RawAddress&, uint8_t, const std::string&),
              (override));
  MOCK_METHOD(void, OnBearerSignalStrength, (const RawAddress&, uint8_t), (override));
};

// Test fixture for the CcpClient tests.
class CcpClientTest : public ::testing::Test {
public:
  void SetUp() override {
    reset_mock_function_count_map();
    mock_callbacks_ = std::make_unique<NiceMock<MockCcpClientCallbacks>>();
    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    gatt::SetMockBtaGattInterface(&gatt_client_interface_);
    gatt::SetMockBtaGattQueue(&gatt_queue_mock_);

    set_security_client_interface(mock_btm_security_);

    BtaAppRegisterCallback app_register_callback;
    EXPECT_CALL(gatt_client_interface_, AppRegister(_, NotNull(), _, _))
            .WillOnce(Invoke([&](auto, tBTA_GATTC_CBACK* cb, BtaAppRegisterCallback app_cb, auto) {
              gatt_callback_ = cb;
              app_register_callback = std::move(app_cb);
            }));

    CcpClient::Initialize(mock_callbacks_.get(), base::Bind([]() {}));
    std::move(app_register_callback).Run(kTestAppId, GATT_SUCCESS);

    ccp_client_ = CcpClient::Get();
    ASSERT_NE(ccp_client_, nullptr);
  }

  void TearDown() override {
    CcpClient::Cleanup();
    ccp_client_ = nullptr;
    reset_mock_btm_client_interface();
    gatt::SetMockBtaGattInterface(nullptr);
    gatt::SetMockBtaGattQueue(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
    mock_callbacks_.reset();
  }

  void SimulateGattConnect(const RawAddress& address, uint16_t conn_id) {
    tBTA_GATTC_OPEN open_data;
    open_data.status = GATT_SUCCESS;
    open_data.conn_id = conn_id;
    open_data.remote_bda = address;
    open_data.transport = BT_TRANSPORT_LE;
    open_data.mtu = 247;

    tBTA_GATTC p_data = {.open = open_data};
    (*gatt_callback_)(BTA_GATTC_OPEN_EVT, &p_data);
  }

  void SimulateGattDisconnect(const RawAddress& address, uint16_t conn_id) {
    tBTA_GATTC_CLOSE close_data;
    close_data.status = GATT_SUCCESS;
    close_data.conn_id = conn_id;
    close_data.remote_bda = address;
    close_data.reason = GATT_CONN_TERMINATE_PEER_USER;

    tBTA_GATTC p_data = {.close = close_data};
    (*gatt_callback_)(BTA_GATTC_CLOSE_EVT, &p_data);
  }

  void SimulateSearchCompleteAndDiscover(const RawAddress& address, uint16_t conn_id) {
    // 1. Service Search Completes
    tBTA_GATTC_SEARCH_CMPL search_cmpl_data;
    search_cmpl_data.conn_id = conn_id;
    search_cmpl_data.status = GATT_SUCCESS;
    tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};

    // 2. Build a fake GATT database with the correct Generic TBS UUID
    gatt::DatabaseBuilder builder;
    builder.AddService(0x0010, 0x0040, kGenericTelephonyBearerServiceUuid, true);
    builder.AddCharacteristic(0x0012, kCallStateHandle, kCallStateUuid, GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0014, kCcpHandle, kCallControlPointUuid,
                              GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_INDICATE);
    builder.AddCharacteristic(0x0016, kStatusFlagsHandle, kStatusFlagsUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0018, kBearerProviderNameHandle, kBearerProviderNameUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_READ);
    builder.AddCharacteristic(0x001A, kBearerTechnologyHandle, kBearerTechnologyUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_READ);
    builder.AddCharacteristic(0x001C, kBearerSignalStrengthHandle, kBearerSignalStrengthUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_READ);
    builder.AddCharacteristic(0x001E, kTerminationReasonHandle, kTerminationReasonUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0020, kIncomingCallHandle, kIncomingCallUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0022, kIncomingCallTargetBearerUriHandle,
                              kIncomingCallTargetBearerUriUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_READ);
    builder.AddCharacteristic(0x0024, kCallFriendlyNameHandle, kCallFriendlyNameUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0026, kBearerListCurrentCallsHandle, kBearerListCurrentCallsUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);

    fake_services_ = builder.Build().Services();
    EXPECT_CALL(gatt_client_interface_, GetServices(conn_id)).WillOnce(Return(&fake_services_));

    // 3. We expect notifications to be registered for all notifiable characteristics
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kCallStateHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kCcpHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kStatusFlagsHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kBearerProviderNameHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kBearerTechnologyHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kBearerSignalStrengthHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kTerminationReasonHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kIncomingCallHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kIncomingCallTargetBearerUriHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kCallFriendlyNameHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kBearerListCurrentCallsHandle));

    EXPECT_CALL(*mock_callbacks_, OnDiscovered(address));

    (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);
  }

protected:
  const RawAddress kTestAddress = RawAddress("11:22:33:44:55:66");
  const tGATT_IF kTestAppId = 5;
  const uint16_t kTestConnId = 10;
  const uint16_t kCallStateHandle = 0x0012;
  const uint16_t kCcpHandle = 0x0014;
  const uint16_t kStatusFlagsHandle = 0x0016;
  const uint16_t kBearerProviderNameHandle = 0x0018;
  const uint16_t kBearerTechnologyHandle = 0x001A;
  const uint16_t kBearerSignalStrengthHandle = 0x001C;
  const uint16_t kTerminationReasonHandle = 0x001E;
  const uint16_t kIncomingCallHandle = 0x0020;
  const uint16_t kIncomingCallTargetBearerUriHandle = 0x0022;
  const uint16_t kCallFriendlyNameHandle = 0x0024;
  const uint16_t kBearerListCurrentCallsHandle = 0x0026;

  std::list<gatt::Service> fake_services_;
  tBTA_GATTC_CBACK* gatt_callback_ = nullptr;

  std::unique_ptr<NiceMock<MockCcpClientCallbacks>> mock_callbacks_;
  CcpClient* ccp_client_ = nullptr;
  NiceMock<gatt::MockBtaGattInterface> gatt_client_interface_;
  NiceMock<gatt::MockBtaGattQueue> gatt_queue_mock_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface;
  NiceMock<MockSecurityClientInterface> mock_btm_security_;
};

TEST_F(CcpClientTest, life_cycle_initialize_and_cleanup) {
  // Verifies the singleton can be initialized, cleaned up, and re-initialized.

  // The CcpClient is initialized in the initial SetUp call.
  ASSERT_NE(ccp_client_, nullptr);
  ASSERT_NE(CcpClient::Get(), nullptr);

  // TearDown cleans up the instance.
  TearDown();
  EXPECT_EQ(CcpClient::Get(), nullptr);

  // SetUp re-initializes it.
  SetUp();
  EXPECT_NE(ccp_client_, nullptr);
}

TEST_F(CcpClientTest, connection_connect_and_discover) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);

  EXPECT_CALL(*mock_callbacks_,
              OnConnectionState(kTestAddress, le_audio::ConnectionState::CONNECTED));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(gatt_client_interface_, ServiceSearchRequest(kTestConnId));
  SimulateGattConnect(kTestAddress, kTestConnId);

  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);
}

TEST_F(CcpClientTest, connection_disconnect) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);

  EXPECT_CALL(gatt_client_interface_, Close(kTestConnId));
  ccp_client_->Disconnect(kTestAddress);

  EXPECT_CALL(*mock_callbacks_,
              OnConnectionState(kTestAddress, le_audio::ConnectionState::DISCONNECTED));
  SimulateGattDisconnect(kTestAddress, kTestConnId);
}

TEST_F(CcpClientTest, actions_accept_call) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);
  Mock::VerifyAndClearExpectations(&gatt_client_interface_);

  std::vector<uint8_t> expected_value = {0x00, 0x01};  // Accept, index 1
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kCcpHandle, expected_value,
                                                    GATT_WRITE_NO_RSP, _, _));
  ccp_client_->AcceptCall(kTestAddress, 1);
}

TEST_F(CcpClientTest, notification_ccp_operation_result) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  uint8_t opcode = 0x00;  // Accept
  uint8_t call_index = 1;
  auto result = CallControlResultCode::SUCCESS;
  std::vector<uint8_t> indication_value = {opcode, call_index, static_cast<uint8_t>(result)};

  EXPECT_CALL(*mock_callbacks_, OnCallControlResult(kTestAddress, opcode, call_index, result));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCcpHandle;
  notify_data.len = (uint8_t)indication_value.size();
  notify_data.is_notify = false;  // This is an indication
  std::copy(indication_value.begin(), indication_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_call_state_multiple_calls) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> call_state_value = {
          0x01,  // index
          0x04,  // state (active)
          0x01,  // flags (outgoing)
          0x02,  // index
          0x01,  // state (incoming)
          0x00,  // flags (not outgoing)
  };

  std::vector<Call> expected_calls;
  expected_calls.push_back({.index = 1, .state = 4, .flags = 1});
  expected_calls.push_back({.index = 2, .state = 1, .flags = 0});

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCallStateHandle;
  notify_data.len = (uint8_t)call_state_value.size();
  notify_data.is_notify = true;
  std::copy(call_state_value.begin(), call_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_call_state_empty) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> call_state_value = {};
  std::vector<Call> expected_calls = {};

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCallStateHandle;
  notify_data.len = (uint8_t)call_state_value.size();
  notify_data.is_notify = true;
  std::copy(call_state_value.begin(), call_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_call_state_invalid_state) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> call_state_value = {
          0x01,  // index
          0x07,  // state (invalid)
          0x01,  // flags (outgoing)
          0x02,  // index
          0x01,  // state (incoming)
          0x00,  // flags (not outgoing)
  };

  std::vector<Call> expected_calls;
  expected_calls.push_back({.index = 2, .state = 1, .flags = 0});

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCallStateHandle;
  notify_data.len = (uint8_t)call_state_value.size();
  notify_data.is_notify = true;
  std::copy(call_state_value.begin(), call_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_call_state_invalid_flags) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> call_state_value = {
          0x01,  // index
          0x04,  // state (active)
          0x81,  // flags (outgoing + RFU bit)
  };

  std::vector<Call> expected_calls;
  expected_calls.push_back({.index = 1, .state = 4, .flags = 1});

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCallStateHandle;
  notify_data.len = (uint8_t)call_state_value.size();
  notify_data.is_notify = true;
  std::copy(call_state_value.begin(), call_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_call_state_malformed_data) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> call_state_value = {
          0x01,  // index
          0x04,  // state (active)
          0x01,  // flags (outgoing)
          0x02,  // index
          0x01,  // state (incoming)
                 // Missing flags byte
  };

  std::vector<Call> expected_calls;
  expected_calls.push_back({.index = 1, .state = 4, .flags = 1});

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kCallStateHandle;
  notify_data.len = (uint8_t)call_state_value.size();
  notify_data.is_notify = true;
  std::copy(call_state_value.begin(), call_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(CcpClientTest, notification_bearer_list_current_calls_empty) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> empty_list_value = {};
  std::vector<Call> expected_calls = {};

  EXPECT_CALL(*mock_callbacks_, OnCallState(kTestAddress, expected_calls));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kBearerListCurrentCallsHandle;
  notify_data.len = (uint8_t)empty_list_value.size();
  notify_data.is_notify = true;
  std::copy(empty_list_value.begin(), empty_list_value.end(), notify_data.value);
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_bearer_provider_name) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::string provider_name = "Test";
  std::vector<uint8_t> provider_name_value(provider_name.begin(), provider_name.end());
  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kBearerProviderNameHandle;
  notify_data.len = (uint8_t)provider_name_value.size();
  notify_data.is_notify = true;
  std::copy(provider_name_value.begin(), provider_name_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnBearerProviderName(kTestAddress, provider_name));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_status_flags) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::vector<uint8_t> status_flags_value = {0x03};
  notify_data.handle = kStatusFlagsHandle;
  notify_data.len = (uint8_t)status_flags_value.size();
  notify_data.is_notify = true;
  std::copy(status_flags_value.begin(), status_flags_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnStatusFlags(kTestAddress, 0x03));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_bearer_technology) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::vector<uint8_t> tech_value = {0x01};
  notify_data.handle = kBearerTechnologyHandle;
  notify_data.len = (uint8_t)tech_value.size();
  notify_data.is_notify = true;
  std::copy(tech_value.begin(), tech_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnBearerTechnology(kTestAddress, 0x01));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_bearer_signal_strength) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::vector<uint8_t> strength_value = {100};
  notify_data.handle = kBearerSignalStrengthHandle;
  notify_data.len = (uint8_t)strength_value.size();
  notify_data.is_notify = true;
  std::copy(strength_value.begin(), strength_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnBearerSignalStrength(kTestAddress, 100));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_termination_reason) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::vector<uint8_t> reason_value = {1, 0x02};
  notify_data.handle = kTerminationReasonHandle;
  notify_data.len = (uint8_t)reason_value.size();
  notify_data.is_notify = true;
  std::copy(reason_value.begin(), reason_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_,
              OnTerminationReason(kTestAddress, 1, TerminationReasonCode::REMOTE_ENDED_CALL));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_incoming_call) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::string uri = "TestUri";
  std::vector<uint8_t> incoming_value;
  incoming_value.push_back(2);
  incoming_value.insert(incoming_value.end(), uri.begin(), uri.end());
  notify_data.handle = kIncomingCallHandle;
  notify_data.len = (uint8_t)incoming_value.size();
  notify_data.is_notify = true;
  std::copy(incoming_value.begin(), incoming_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnIncomingCall(kTestAddress, 2, uri));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_incoming_call_target) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::string target_uri = "TestUri";
  std::vector<uint8_t> target_value;
  target_value.push_back(4);
  target_value.insert(target_value.end(), target_uri.begin(), target_uri.end());
  notify_data.handle = kIncomingCallTargetBearerUriHandle;
  notify_data.len = (uint8_t)target_value.size();
  notify_data.is_notify = true;
  std::copy(target_value.begin(), target_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnIncomingCallTarget(kTestAddress, 4, target_uri));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}

TEST_F(CcpClientTest, notification_call_friendly_name) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  ccp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;

  std::string name = "Test";
  std::vector<uint8_t> name_value;
  name_value.push_back(3);
  name_value.insert(name_value.end(), name.begin(), name.end());
  notify_data.handle = kCallFriendlyNameHandle;
  notify_data.len = (uint8_t)name_value.size();
  notify_data.is_notify = true;
  std::copy(name_value.begin(), name_value.end(), notify_data.value);
  EXPECT_CALL(*mock_callbacks_, OnCallFriendlyName(kTestAddress, 3, name));
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&notify_data);
}
