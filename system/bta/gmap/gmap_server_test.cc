/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "bta/le_audio/gmap_server.h"

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <hardware/bluetooth.h>

#include "bta/le_audio/le_audio_types.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "test/common/mock_functions.h"

using ::testing::_;
using ::testing::AnyNumber;
using ::testing::DoAll;
using ::testing::DoDefault;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::Sequence;
using ::testing::SetArgPointee;
using ::testing::WithArg;

using ::testing::NiceMock;

using bluetooth::Uuid;
using namespace bluetooth;

namespace bluetooth::le_audio {

class GmapServerTest : public ::testing::Test {
public:
  RawAddress addr = RawAddress("11:22:33:44:55:66");
  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface;
  uint8_t role = 0b1;
  uint8_t UGG_feature = 0b111;

  void SetUp(void) override {
    uint8_t server_if = 10;
    reset_mock_function_count_map();
    gatt::SetMockBtaGattServerInterface(&gatt_server_interface);
    EXPECT_CALL(gatt_server_interface, AppRegister(_, _, _)).Times(1).WillOnce(Return(server_if));
    EXPECT_CALL(gatt_server_interface, AddService(_, _)).WillOnce(Return(GATT_SERVICE_STARTED));
    GmapServer::Initialize(role, UGG_feature);
  }
};

TEST_F(GmapServerTest, test_get_role) { ASSERT_EQ(GmapServer::GetRole(), role); }

TEST_F(GmapServerTest, test_get_UGG_feature) {
  ASSERT_EQ(GmapServer::GetUGGFeature(), UGG_feature);
}

TEST_F(GmapServerTest, test_read_invalid_characteristic) {
  uint16_t handle = 10;
  uint16_t conn_id = 1;
  uint32_t trans_id = 1;
  RawAddress remote_bda = RawAddress::kEmpty;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_INVALID_HANDLE, _)).Times(1);
  GmapServer::OnReadCharacteristic(conn_id, trans_id, remote_bda, handle, 0, false);
}

TEST_F(GmapServerTest, test_read_invalid_role_characteristic) {
  uint16_t handle = 10;
  uint16_t conn_id = 1;
  uint32_t trans_id = 1;
  RawAddress remote_bda = RawAddress::kEmpty;

  GmapCharacteristic invalidGmapCharacteristic{
          .uuid_ = bluetooth::le_audio::uuid::kTelephonyMediaAudioProfileRoleCharacteristicUuid,
          .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = invalidGmapCharacteristic;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_ILLEGAL_PARAMETER, _)).Times(1);
  GmapServer::OnReadCharacteristic(conn_id, trans_id, remote_bda, handle, 0, false);
}

TEST_F(GmapServerTest, test_read_valid_role_characteristic) {
  uint16_t handle = 10;
  uint16_t conn_id = 1;
  uint32_t trans_id = 1;
  RawAddress remote_bda = RawAddress::kEmpty;

  GmapCharacteristic gmapCharacteristic{.uuid_ = bluetooth::le_audio::uuid::kRoleCharacteristicUuid,
                                        .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  GmapServer::OnReadCharacteristic(conn_id, trans_id, remote_bda, handle, 0, false);
}

TEST_F(GmapServerTest, test_read_valid_ugg_feature_characteristic) {
  uint16_t handle = 10;
  uint16_t conn_id = 1;
  uint32_t trans_id = 1;
  RawAddress remote_bda = RawAddress::kEmpty;

  GmapCharacteristic gmapCharacteristic{
          .uuid_ = bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid,
          .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  GmapServer::OnReadCharacteristic(conn_id, trans_id, remote_bda, handle, 0, false);
}

TEST_F(GmapServerTest, test_get_UGG_feature_handle) {
  uint16_t handle = 10;
  GmapCharacteristic gmapCharacteristic{
          .uuid_ = bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid,
          .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  ASSERT_EQ(GmapServer::GetUGGFeatureHandle(), handle);
}

TEST_F(GmapServerTest, test_read_invalid_UGG_feature_handle) {
  uint16_t handle = 10;
  GmapServer::GetCharacteristics().clear();

  ASSERT_NE(GmapServer::GetUGGFeatureHandle(), handle);
}

TEST_F(GmapServerTest, test_get_role_handle) {
  uint16_t handle = 10;
  GmapCharacteristic gmapCharacteristic{.uuid_ = bluetooth::le_audio::uuid::kRoleCharacteristicUuid,
                                        .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  ASSERT_EQ(GmapServer::GetRoleHandle(), handle);
}

}  // namespace bluetooth::le_audio
