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

#include "mcp/mcp_client.h"

#include <base/functional/bind.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/include/bta_api.h"
#include "bta/include/bta_gatt_api.h"
#include "gatt/database_builder.h"
#include "hardware/bt_le_audio.h"
#include "mcp/mcp_types.h"
#include "test/common/bta_gatt_api_mock.h"
#include "test/common/bta_gatt_queue_mock.h"
#include "test/common/btm_api_mock.h"
#include "test/common/mock_functions.h"

using namespace bluetooth;
using namespace bluetooth::mcp;

using ::testing::_;
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
  MOCK_METHOD(void, OnConnectionState, (const RawAddress&, le_audio::ConnectionState), (override));
  MOCK_METHOD(void, OnDiscovered, (const RawAddress&), (override));
  MOCK_METHOD(void, OnMediaPlayerNameChanged, (const RawAddress&, const std::string&), (override));
  MOCK_METHOD(void, OnMediaStateChanged, (const RawAddress&, uint8_t), (override));
  MOCK_METHOD(void, OnTrackChanged, (const RawAddress&), (override));
  MOCK_METHOD(void, OnTrackTitleChanged, (const RawAddress&, const std::string&), (override));
  MOCK_METHOD(void, OnTrackDurationChanged, (const RawAddress&, int32_t), (override));
  MOCK_METHOD(void, OnTrackPositionChanged, (const RawAddress&, int32_t), (override));
  MOCK_METHOD(void, OnPlaybackSpeedChanged, (const RawAddress&, int8_t), (override));
  MOCK_METHOD(void, OnSeekingSpeedChanged, (const RawAddress&, int8_t), (override));
  MOCK_METHOD(void, OnMediaControlResult, (const RawAddress&, uint8_t, MediaControlResultCode),
              (override));
  MOCK_METHOD(void, OnOpcodesSupportedChanged, (const RawAddress&, uint32_t), (override));
  MOCK_METHOD(void, OnPlayingOrdersSupportedChanged, (const RawAddress&, uint16_t), (override));
};

class McpClientTest : public ::testing::Test {
public:
  void SetUp() override {
    reset_mock_function_count_map();
    mock_callbacks_ = std::make_unique<NiceMock<MockMcpClientCallbacks>>();
    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    gatt::SetMockBtaGattInterface(&gatt_client_interface_);
    gatt::SetMockBtaGattQueue(&gatt_queue_mock_);

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

    gatt::DatabaseBuilder builder;
    builder.AddService(0x0010, 0x0040, kGenericMediaControlServiceUuid, true);
    builder.AddCharacteristic(0x0012, kMediaStateHandle, kMediaStateUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x0014, kMcpHandle, kMediaControlPointUuid,
                              GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_INDICATE);
    builder.AddCharacteristic(0x0016, kOpcodesSupportedHandle,
                              kMediaControlPointOpcodesSupportedUuid, GATT_CHAR_PROP_BIT_READ);
    builder.AddCharacteristic(0x0018, kTrackChangedHandle, kTrackChangedUuid,
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x001A, kTrackTitleHandle, kTrackTitleUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddCharacteristic(0x001C, kPlayingOrdersSupportedHandle, kPlayingOrderSupportedUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);

    fake_services_ = builder.Build().Services();
    EXPECT_CALL(gatt_client_interface_, GetServices(conn_id)).WillOnce(Return(&fake_services_));

    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kMediaStateHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kMcpHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackChangedHandle));
    EXPECT_CALL(gatt_client_interface_, RegisterForNotifications(_, address, kTrackTitleHandle));
    EXPECT_CALL(*mock_callbacks_, OnDiscovered(address));

    (*gatt_callback_)(BTA_GATTC_SEARCH_CMPL_EVT, &p_data_search_cmpl);
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

  std::list<gatt::Service> fake_services_;
  tBTA_GATTC_CBACK* gatt_callback_ = nullptr;

  std::unique_ptr<MockMcpClientCallbacks> mock_callbacks_;
  McpClient* mcp_client_ = nullptr;
  NiceMock<gatt::MockBtaGattInterface> gatt_client_interface_;
  NiceMock<gatt::MockBtaGattQueue> gatt_queue_mock_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface;
};

TEST_F(McpClientTest, initialize_and_cleanup) { ASSERT_NE(mcp_client_, nullptr); }

TEST_F(McpClientTest, connect_and_discover_flow) {
  EXPECT_CALL(btm_interface, IsDeviceBonded(kTestAddress, BT_TRANSPORT_LE)).WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);

  EXPECT_CALL(*mock_callbacks_,
              OnConnectionState(kTestAddress, le_audio::ConnectionState::CONNECTED));
  EXPECT_CALL(btm_interface, BTM_IsEncrypted(kTestAddress, BT_TRANSPORT_LE)).WillOnce(Return(true));
  EXPECT_CALL(gatt_client_interface_, ServiceSearchRequest(kTestConnId, NotNull()));
  SimulateGattConnect(kTestAddress, kTestConnId);

  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);
}

TEST_F(McpClientTest, play_command) {
  EXPECT_CALL(btm_interface, IsDeviceBonded(kTestAddress, BT_TRANSPORT_LE)).WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> expected_value = {kMcpOpcodePlay};
  EXPECT_CALL(gatt_queue_mock_, WriteCharacteristic(kTestConnId, kMcpHandle, expected_value,
                                                    GATT_WRITE_NO_RSP, _, _));
  mcp_client_->Play(kTestAddress);
}

TEST_F(McpClientTest, media_state_notification) {
  EXPECT_CALL(btm_interface, IsDeviceBonded(kTestAddress, BT_TRANSPORT_LE)).WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  std::vector<uint8_t> media_state_value = {0x01};  // Playing
  EXPECT_CALL(*mock_callbacks_, OnMediaStateChanged(kTestAddress, 0x01));

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
  EXPECT_CALL(btm_interface, IsDeviceBonded(kTestAddress, BT_TRANSPORT_LE)).WillOnce(Return(true));
  mcp_client_->Connect(kTestAddress);
  SimulateGattConnect(kTestAddress, kTestConnId);
  SimulateSearchCompleteAndDiscover(kTestAddress, kTestConnId);

  uint8_t opcode = kMcpOpcodePlay;
  auto result_code = MediaControlResultCode::SUCCESS;
  std::vector<uint8_t> indication_value = {opcode, static_cast<uint8_t>(result_code)};

  EXPECT_CALL(*mock_callbacks_, OnMediaControlResult(kTestAddress, opcode, result_code));
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
