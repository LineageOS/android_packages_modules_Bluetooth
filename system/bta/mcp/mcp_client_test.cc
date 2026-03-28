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

#include <base/functional/bind.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <set>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_mcp_client_api.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "gatt/database_builder.h"
#include "mcp/mcp_types.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_security_client_interface.h"
#include "test/common/bta_gatt_queue_mock.h"
#include "test/common/btm_api_mock.h"
#include "test/common/mock_functions.h"

#define TEST_BT com::android::bluetooth::flags

using namespace bluetooth;
using namespace bluetooth::mcp;

using ::testing::_;
using ::testing::AnyNumber;
using ::testing::DoAll;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::WithArg;

class MockMcpClientCallbacks : public McpClientCallbacks {
public:
  // clang-format off
  MOCK_METHOD(void, OnConnectionState, (const RawAddress&, ConnectionState), (override));
  MOCK_METHOD(void, OnDiscovered, (const RawAddress&), (override));
  MOCK_METHOD(void, OnMediaPlayerNameChanged, (const RawAddress&, int, const std::string&),
              (override));
  MOCK_METHOD(void, OnMediaStateChanged, (const RawAddress&, int, MediaState), (override));
  MOCK_METHOD(void, OnPlayingOrderChanged, (const RawAddress&, int, PlayingOrder), (override));
  MOCK_METHOD(void, OnTrackChanged, (const RawAddress&, int), (override));
  MOCK_METHOD(void, OnTrackTitleChanged, (const RawAddress&, int, const std::string&), (override));
  MOCK_METHOD(void, OnTrackDurationChanged, (const RawAddress&, int, int32_t), (override));
  MOCK_METHOD(void, OnTrackPositionChanged, (const RawAddress&, int, int32_t), (override));
  MOCK_METHOD(void, OnPlaybackSpeedChanged, (const RawAddress&, int, int8_t), (override));
  MOCK_METHOD(void, OnSeekingSpeedChanged, (const RawAddress&, int, int8_t), (override));
  MOCK_METHOD(void, OnMediaControlResult, (const RawAddress&, int, uint8_t, MediaControlResultCode),
              (override));
  MOCK_METHOD(void, OnOpcodesSupportedChanged, (const RawAddress&, int, uint32_t), (override));
  MOCK_METHOD(void, OnPlayingOrdersSupportedChanged, (const RawAddress&, int, uint16_t),
              (override));
  // clang-format on
};

class McpClientTest : public ::testing::Test {
public:
  void SetUp() override {
    com::android::bluetooth::flags::provider_->reset_flags();
    com::android::bluetooth::flags::provider_->leaudio_peripheral_mcp_link_abstraction_layer(true);

    reset_mock_function_count_map();
    mock_callbacks_ = std::make_unique<NiceMock<MockMcpClientCallbacks>>();
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

    McpClient::Initialize(mock_callbacks_.get(), base::Bind([]() {}));
    std::move(app_register_callback).Run(kTestAppId, GATT_SUCCESS);

    mcp_client_ = McpClient::Get();
    ASSERT_NE(mcp_client_, nullptr);
  }

  void TearDown() override {
    McpClient::Cleanup();
    mcp_client_ = nullptr;
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
    tBTA_GATTC p_data = {.open = open_data};
    (*gatt_callback_)(BTA_GATTC_OPEN_EVT, &p_data);
  }

  void SimulateSearchCompleteAndDiscover(const RawAddress& address, uint16_t conn_id) {
    tBTA_GATTC_SEARCH_CMPL search_cmpl_data;
    search_cmpl_data.conn_id = conn_id;
    search_cmpl_data.status = GATT_SUCCESS;
    tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};

    fake_services_ = BuildServices({});
    EXPECT_CALL(gatt_client_interface_, GetServices(conn_id)).WillOnce(Return(&fake_services_));

    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kMediaStateHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kMcpHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackChangedHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackTitleHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kMediaPlayerNameHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackDurationHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackPositionHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kPlayingOrderHandle));
    EXPECT_CALL(gatt_client_interface_,
                RegisterForNotifications(_, address, kOpcodesSupportedHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kPlaybackSpeedHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kSeekingSpeedHandle));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kMediaPlayerNameHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kMediaStateHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kOpcodesSupportedHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kTrackTitleHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kTrackDurationHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kTrackPositionHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kPlayingOrdersSupportedHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kPlayingOrderHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kPlaybackSpeedHandle, _, _));
    EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(conn_id, kSeekingSpeedHandle, _, _));
    EXPECT_CALL(*mock_callbacks_, OnDiscovered(address));

    (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);
  }

  void AddCharacteristicToBuilder(gatt::DatabaseBuilder& builder, uint16_t handle, const Uuid& uuid,
                                  uint8_t properties, const std::set<uint16_t>& missing_handles) {
    if (missing_handles.find(handle) == missing_handles.end()) {
      builder.AddCharacteristic(handle, handle, uuid, properties);
    }
  }

  std::list<gatt::Service> BuildServices(const std::set<uint16_t>& missing_handles) {
    gatt::DatabaseBuilder builder;
    builder.AddService(0x0010, 0x0040, kGenericMediaControlServiceUuid, true);
    AddCharacteristicToBuilder(builder, kMediaStateHandle, kMediaStateUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kMcpHandle, kMediaControlPointUuid,
                               GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_INDICATE,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kOpcodesSupportedHandle,
                               kMediaControlPointOpcodesSupportedUuid, GATT_CHAR_PROP_BIT_READ,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kTrackChangedHandle, kTrackChangedUuid,
                               GATT_CHAR_PROP_BIT_NOTIFY, missing_handles);
    AddCharacteristicToBuilder(builder, kTrackTitleHandle, kTrackTitleUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kPlayingOrdersSupportedHandle, kPlayingOrderSupportedUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(
            builder, kPlayingOrderHandle, kPlayingOrderUuid,
            GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_NOTIFY,
            missing_handles);
    AddCharacteristicToBuilder(builder, kMediaPlayerNameHandle, kMediaPlayerNameUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kTrackDurationHandle, kTrackDurationUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(
            builder, kTrackPositionHandle, kTrackPositionUuid,
            GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_NOTIFY,
            missing_handles);
    AddCharacteristicToBuilder(
            builder, kPlaybackSpeedHandle, kPlaybackSpeedUuid,
            GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_NOTIFY,
            missing_handles);
    AddCharacteristicToBuilder(builder, kSeekingSpeedHandle, kSeekingSpeedUuid,
                               GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                               missing_handles);
    AddCharacteristicToBuilder(builder, kContentControlIdHandle, kContentControlIdUuid,
                               GATT_CHAR_PROP_BIT_READ, missing_handles);

    return builder.Build().Services();
  }

protected:
  const RawAddress kTestAddress = RawAddress("11:22:33:44:55:66");
  const tGATT_IF kTestAppId = 6;
  const uint16_t kTestConnId = 11;
  const uint16_t kMediaStateHandle = 0x0012;
  const uint16_t kMcpHandle = 0x0014;
  const uint16_t kOpcodesSupportedHandle = 0x0016;
  const uint16_t kTrackChangedHandle = 0x0018;
  const uint16_t kTrackTitleHandle = 0x001A;
  const uint16_t kPlayingOrdersSupportedHandle = 0x001C;
  const uint16_t kMediaPlayerNameHandle = 0x001E;
  const uint16_t kTrackDurationHandle = 0x0020;
  const uint16_t kTrackPositionHandle = 0x0022;
  const uint16_t kContentControlIdHandle = 0x0024;
  const uint16_t kPlayingOrderHandle = 0x0026;
  const uint16_t kPlaybackSpeedHandle = 0x0028;
  const uint16_t kSeekingSpeedHandle = 0x002A;

  std::list<gatt::Service> fake_services_;
  tBTA_GATTC_CBACK* gatt_callback_ = nullptr;

  std::unique_ptr<MockMcpClientCallbacks> mock_callbacks_;
  McpClient* mcp_client_ = nullptr;
  NiceMock<gatt::MockBtaGattInterface> gatt_client_interface_;
  NiceMock<gatt::MockBtaGattQueue> gatt_queue_mock_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface;
  NiceMock<MockSecurityClientInterface> mock_btm_security_;
};

TEST_F(McpClientTest, initialize_and_cleanup) { ASSERT_NE(mcp_client_, nullptr); }

TEST_F(McpClientTest, connect_and_discover_flow) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);

  EXPECT_CALL(*mock_callbacks_, OnConnectionState(kTestAddress, ConnectionState::CONNECTED));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  EXPECT_CALL(gatt_client_interface_, ServiceSearchRequest(kTestConnId));
  SimulateGattConnect(kTestAddress, kTestConnId);

  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);
}

TEST_F(McpClientTest, play_command) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> expected_value = {kMcpOpcodePlay};
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kMcpHandle, expected_value,
                                                    GATT_WRITE_NO_RSP, _, _));
  mcp_client_->Play(kTestAddress, 0);
}

TEST_F(McpClientTest, media_state_notification) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> media_state_value = {0x01};  // Playing
  EXPECT_CALL(*mock_callbacks_, OnMediaStateChanged(kTestAddress, 0, MediaState::PLAYING));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kMediaStateHandle;
  notify_data.len = (uint8_t)media_state_value.size();
  notify_data.is_notify = true;
  std::copy(media_state_value.begin(), media_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, media_control_point_indication) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  uint8_t opcode = kMcpOpcodePlay;
  auto result_code = MediaControlResultCode::SUCCESS;
  std::vector<uint8_t> indication_value = {opcode, static_cast<uint8_t>(result_code)};

  EXPECT_CALL(*mock_callbacks_, OnMediaControlResult(kTestAddress, 0, opcode, result_code));
  EXPECT_CALL(gatt_client_interface_, SendIndConfirm(kTestConnId, _));

  tBTA_GATTC_NOTIFY indication_data;
  indication_data.conn_id = kTestConnId;
  indication_data.bda = kTestAddress;
  indication_data.handle = kMcpHandle;
  indication_data.len = (uint8_t)indication_value.size();
  indication_data.is_notify = false;  // This is an indication
  std::copy(indication_value.begin(), indication_value.end(), indication_data.value);
  tBTA_GATTC p_data = {.notify = indication_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, playing_order_command) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> expected_value = {static_cast<uint8_t>(PlayingOrder::SHUFFLE_ONCE)};
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kPlayingOrderHandle,
                                                    expected_value, GATT_WRITE_NO_RSP, _, _));
  mcp_client_->SetPlayingOrder(kTestAddress, 0, PlayingOrder::SHUFFLE_ONCE);
}

TEST_F(McpClientTest, playing_order_notification) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> value = {static_cast<uint8_t>(PlayingOrder::SHUFFLE_REPEAT)};
  EXPECT_CALL(*mock_callbacks_,
              OnPlayingOrderChanged(kTestAddress, 0, PlayingOrder::SHUFFLE_REPEAT));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kPlayingOrderHandle;
  notify_data.len = (uint8_t)value.size();
  notify_data.is_notify = true;
  std::copy(value.begin(), value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, validation_failed_missing_mandatory_characteristic) {
  std::vector<uint16_t> mandatory_handles = {kMediaStateHandle,      kTrackChangedHandle,
                                             kTrackTitleHandle,      kTrackDurationHandle,
                                             kTrackPositionHandle,   kContentControlIdHandle,
                                             kMediaPlayerNameHandle, kOpcodesSupportedHandle};

  for (uint16_t missing_handle : mandatory_handles) {
    EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
            .WillOnce(Return(true));
    mcp_client_->Connect(kTestAddress);
    SimulateGattConnect(kTestAddress, kTestConnId);

    tBTA_GATTC_SEARCH_CMPL search_cmpl_data;
    search_cmpl_data.conn_id = kTestConnId;
    search_cmpl_data.status = GATT_SUCCESS;
    tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};

    fake_services_ = BuildServices({missing_handle});
    EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId)).WillOnce(Return(&fake_services_));

    EXPECT_CALL(gatt_client_interface_, Close(kTestConnId));
    EXPECT_CALL(*mock_callbacks_, OnDiscovered(_)).Times(0);

    (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);

    tBTA_GATTC_CLOSE close_data;
    close_data.conn_id = kTestConnId;
    close_data.remote_bda = kTestAddress;
    close_data.status = GATT_SUCCESS;
    close_data.reason = GATT_CONN_TERMINATE_LOCAL_HOST;
    tBTA_GATTC p_data_close = {.close = close_data};
    (*gatt_callback_)(BTA_GATTC_CLOSE_EVT, &p_data_close);

    Mock::VerifyAndClearExpectations(&btm_interface);
    Mock::VerifyAndClearExpectations(&gatt_client_interface_);
    Mock::VerifyAndClearExpectations(mock_callbacks_.get());
  }
}

TEST_F(McpClientTest, optional_characteristics_missing_success) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);

  tBTA_GATTC_SEARCH_CMPL search_cmpl_data;
  search_cmpl_data.conn_id = kTestConnId;
  search_cmpl_data.status = GATT_SUCCESS;
  tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};

  fake_services_ = BuildServices(
          {kMcpHandle, kOpcodesSupportedHandle, kPlaybackSpeedHandle, kSeekingSpeedHandle});
  EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId)).WillOnce(Return(&fake_services_));

  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, kMediaStateHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackChangedHandle));
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, kTrackTitleHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kMediaPlayerNameHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackDurationHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackPositionHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kPlayingOrderHandle));

  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kMediaPlayerNameHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kMediaStateHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackTitleHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackDurationHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackPositionHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_,
              ReadCharacteristic(kTestConnId, kPlayingOrdersSupportedHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kPlayingOrderHandle, _, _));

  EXPECT_CALL(*mock_callbacks_, OnDiscovered(kTestAddress));

  (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);
}

TEST_F(McpClientTest, discover_fallback_to_gmcs) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);

  // 1. Simulate Search Complete with ONLY MCS (no GMCS)
  tBTA_GATTC_SEARCH_CMPL search_cmpl_data = {.conn_id = kTestConnId, .status = GATT_SUCCESS};
  tBTA_GATTC p_data_1 = {.search_cmpl = search_cmpl_data};

  // Mock GetServices to return only MCS initially
  std::list<gatt::Service> mcs_only_services;
  gatt::Service mcs_service;
  mcs_service.uuid = kMediaControlServiceUuid;
  mcs_service.handle = 0x0010;
  mcs_service.end_handle = 0x0020;
  mcs_only_services.push_back(mcs_service);

  EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId))
          .WillOnce(Return(&mcs_only_services));

  // Expect a NEW search request for GMCS
  EXPECT_CALL(gatt_client_interface_, ServiceSearchRequest(kTestConnId));

  (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_1);

  // 2. Simulate Second Search Complete with GMCS
  // Use BuildServices from fixture which creates GMCS
  fake_services_ = BuildServices({});

  EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId)).WillOnce(Return(&fake_services_));

  // Expect discovery success
  EXPECT_CALL(*mock_callbacks_, OnDiscovered(kTestAddress));

  // Expect registration for notifications (ignoring specific handles for brevity)
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, _, _)).Times(AnyNumber());
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(_, _, _, _)).Times(AnyNumber());

  (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_1);
}

TEST_F(McpClientTest, multiple_services_discovery_and_operation) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);

  // Service 1 (GMCS)
  fake_services_ = BuildServices({});

  // Service 2 (MCS)
  gatt::DatabaseBuilder builder2;
  builder2.AddService(0x0050, 0x0080, kMediaControlServiceUuid, true);
  uint16_t offset = 0x0040;
  AddCharacteristicToBuilder(builder2, kMediaStateHandle + offset, kMediaStateUuid,
                             GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kMcpHandle + offset, kMediaControlPointUuid,
                             GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_INDICATE, {});
  AddCharacteristicToBuilder(builder2, kOpcodesSupportedHandle + offset,
                             kMediaControlPointOpcodesSupportedUuid, GATT_CHAR_PROP_BIT_READ, {});
  AddCharacteristicToBuilder(builder2, kTrackChangedHandle + offset, kTrackChangedUuid,
                             GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kTrackTitleHandle + offset, kTrackTitleUuid,
                             GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kPlayingOrdersSupportedHandle + offset,
                             kPlayingOrderSupportedUuid,
                             GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(
          builder2, kPlayingOrderHandle + offset, kPlayingOrderUuid,
          GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kMediaPlayerNameHandle + offset, kMediaPlayerNameUuid,
                             GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kTrackDurationHandle + offset, kTrackDurationUuid,
                             GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(
          builder2, kTrackPositionHandle + offset, kTrackPositionUuid,
          GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_NOTIFY, {});
  AddCharacteristicToBuilder(builder2, kContentControlIdHandle + offset, kContentControlIdUuid,
                             GATT_CHAR_PROP_BIT_READ, {});

  auto services2 = builder2.Build().Services();
  fake_services_.splice(fake_services_.end(), services2);

  EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId)).WillOnce(Return(&fake_services_));
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, _))
          .Times(AnyNumber());
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, _, _, _)).Times(AnyNumber());
  EXPECT_CALL(*mock_callbacks_, OnDiscovered(kTestAddress));

  tBTA_GATTC_SEARCH_CMPL search_cmpl_data = {.conn_id = kTestConnId, .status = GATT_SUCCESS};
  tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};
  (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);

  // Test Operations
  std::vector<uint8_t> expected_value = {kMcpOpcodePlay};

  // Play on Service 0 (GMCS)
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kMcpHandle, expected_value,
                                                    GATT_WRITE_NO_RSP, _, _));
  mcp_client_->Play(kTestAddress, 0);

  // Play on Service 1 (MCS)
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kMcpHandle + offset,
                                                    expected_value, GATT_WRITE_NO_RSP, _, _));
  mcp_client_->Play(kTestAddress, 1);

  // Test Notifications
  // Notification on Service 0
  std::vector<uint8_t> media_state_value = {0x01};  // Playing
  EXPECT_CALL(*mock_callbacks_, OnMediaStateChanged(kTestAddress, 0, MediaState::PLAYING));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kMediaStateHandle;
  notify_data.len = (uint8_t)media_state_value.size();
  notify_data.is_notify = true;
  std::copy(media_state_value.begin(), media_state_value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);

  // Notification on Service 1
  EXPECT_CALL(*mock_callbacks_,
              OnMediaStateChanged(kTestAddress, 1, MediaState::PAUSED));  // Paused

  notify_data.handle = kMediaStateHandle + offset;
  notify_data.value[0] = 0x02;
  p_data.notify = notify_data;
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, playback_speed_command) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> expected_value = {10};
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kPlaybackSpeedHandle,
                                                    expected_value, GATT_WRITE_NO_RSP, _, _));
  mcp_client_->SetPlaybackSpeed(kTestAddress, 0, 10);
}

TEST_F(McpClientTest, playback_speed_notification) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> value = {20};
  EXPECT_CALL(*mock_callbacks_, OnPlaybackSpeedChanged(kTestAddress, 0, 20));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kPlaybackSpeedHandle;
  notify_data.len = (uint8_t)value.size();
  notify_data.is_notify = true;
  std::copy(value.begin(), value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, seeking_speed_notification) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> value = {5};
  EXPECT_CALL(*mock_callbacks_, OnSeekingSpeedChanged(kTestAddress, 0, 5));

  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = kTestConnId;
  notify_data.bda = kTestAddress;
  notify_data.handle = kSeekingSpeedHandle;
  notify_data.len = (uint8_t)value.size();
  notify_data.is_notify = true;
  std::copy(value.begin(), value.end(), notify_data.value);
  tBTA_GATTC p_data = {.notify = notify_data};
  (*gatt_callback_)(BTA_GATTC_NOTIF_EVT, &p_data);
}

TEST_F(McpClientTest, connect_cached_services_before_encryption) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);

  EXPECT_CALL(*mock_callbacks_, OnConnectionState(kTestAddress, ConnectionState::CONNECTED));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(false));
  EXPECT_CALL(mock_btm_security_, BTM_SetEncryption(kTestAddress, BT_TRANSPORT_LE, _, _, _))
          .WillOnce(Return(tBTM_STATUS::BTM_CMD_STARTED));

  SimulateGattConnect(kTestAddress, kTestConnId);

  tBTA_GATTC_SEARCH_CMPL search_cmpl_data;
  search_cmpl_data.conn_id = kTestConnId;
  search_cmpl_data.status = GATT_SUCCESS;
  tBTA_GATTC p_data_search_cmpl = {.search_cmpl = search_cmpl_data};

  fake_services_ = BuildServices({});
  EXPECT_CALL(gatt_client_interface_, GetServices(kTestConnId)).WillOnce(Return(&fake_services_));

  EXPECT_CALL(*mock_callbacks_, OnDiscovered(kTestAddress));
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, _, _)).Times(0);
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(_, _, _, _)).Times(0);

  (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);

  Mock::VerifyAndClearExpectations(&gatt_client_interface_);
  Mock::VerifyAndClearExpectations(&gatt_queue_mock_);

  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));

  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, kMediaStateHandle));
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, kMcpHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackChangedHandle));
  EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, kTestAddress, kTrackTitleHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kMediaPlayerNameHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackDurationHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kTrackPositionHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kPlayingOrderHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kOpcodesSupportedHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kPlaybackSpeedHandle));
  EXPECT_CALL(gatt_client_interface_,
              RegisterForNotifications(_, kTestAddress, kSeekingSpeedHandle));

  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kMediaPlayerNameHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kMediaStateHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kOpcodesSupportedHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackTitleHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackDurationHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kTrackPositionHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_,
              ReadCharacteristic(kTestConnId, kPlayingOrdersSupportedHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kPlayingOrderHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kPlaybackSpeedHandle, _, _));
  EXPECT_CALL(gatt_queue_mock_, ReadCharacteristic(kTestConnId, kSeekingSpeedHandle, _, _));

  tBTA_GATTC_ENC_CMPL_CB enc_cmpl_data;
  enc_cmpl_data.remote_bda = kTestAddress;
  tBTA_GATTC p_data_enc_cmpl = {.enc_cmpl = enc_cmpl_data};
  (*gatt_callback_)(BTA_GATTC_ENC_CMPL_CB_EVT, &p_data_enc_cmpl);
}

TEST_F(McpClientTest, add_from_storage) {
  EXPECT_CALL(gatt_client_interface_, Open(kTestAppId, kTestAddress, BTM_BLE_OPPORTUNISTIC));
  McpClient::AddFromStorage(kTestAddress);
}

TEST_F(McpClientTest, reconnect_after_disconnect) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));

  // Initial connect
  EXPECT_CALL(gatt_client_interface_, Open(kTestAppId, kTestAddress, BTM_BLE_OPPORTUNISTIC));
  mcp_client_->Connect(kTestAddress);

  // Connected
  EXPECT_CALL(*mock_callbacks_, OnConnectionState(kTestAddress, ConnectionState::CONNECTED));
  EXPECT_CALL(mock_btm_security_, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE))
          .WillRepeatedly(Return(true));
  EXPECT_CALL(gatt_client_interface_, ServiceSearchRequest(kTestConnId));
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  // Disconnect
  EXPECT_CALL(*mock_callbacks_, OnConnectionState(kTestAddress, ConnectionState::DISCONNECTED));
  // Expect re-connect (opportunistic)
  EXPECT_CALL(gatt_client_interface_, Open(kTestAppId, kTestAddress, BTM_BLE_OPPORTUNISTIC));

  tBTA_GATTC_CLOSE close_data;
  close_data.conn_id = kTestConnId;
  close_data.remote_bda = kTestAddress;
  close_data.status = GATT_SUCCESS;
  close_data.reason = GATT_CONN_TERMINATE_PEER_USER;
  tBTA_GATTC p_data_close = {.close = close_data};
  (*gatt_callback_)(BTA_GATTC_CLOSE_EVT, &p_data_close);
}

TEST_F(McpClientTest, reconnect_after_connection_failure) {
  EXPECT_CALL(mock_btm_security_, BTM_IsBonded(kTestAddress, BT_TRANSPORT_LE))
          .WillOnce(Return(true));

  // Initial connect
  EXPECT_CALL(gatt_client_interface_, Open(kTestAppId, kTestAddress, BTM_BLE_OPPORTUNISTIC));
  mcp_client_->Connect(kTestAddress);

  // Connect failure
  EXPECT_CALL(*mock_callbacks_, OnConnectionState(kTestAddress, ConnectionState::DISCONNECTED));
  // Expect re-connect (opportunistic)
  EXPECT_CALL(gatt_client_interface_, Open(kTestAppId, kTestAddress, BTM_BLE_OPPORTUNISTIC));

  tBTA_GATTC_OPEN open_data;
  open_data.status = GATT_ERROR;
  open_data.conn_id = kTestConnId;
  open_data.remote_bda = kTestAddress;
  open_data.transport = BT_TRANSPORT_LE;
  tBTA_GATTC p_data = {.open = open_data};
  (*gatt_callback_)(BTA_GATTC_OPEN_EVT, &p_data);
}
