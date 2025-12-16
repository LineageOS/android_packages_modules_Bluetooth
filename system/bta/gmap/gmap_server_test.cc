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
#include "bta_gatt_api_mock.h"
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
using bluetooth::le_audio::GmapCharacteristic;
using bluetooth::le_audio::GmapServer;

class GmapServerTest : public ::testing::Test {
public:
  RawAddress addr = RawAddress("11:22:33:44:55:66");
  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface;
  uint8_t role = 0b1;
  uint8_t UGG_feature = 0b111;

  void SetUp(void) override {
    reset_mock_function_count_map();
    gatt::SetMockBtaGattServerInterface(&gatt_server_interface);
    EXPECT_CALL(gatt_server_interface, AppRegister(_, _, _)).Times(1);
    GmapServer::Initialize(role, UGG_feature);
  }
};

TEST_F(GmapServerTest, test_get_role) { ASSERT_EQ(GmapServer::GetRole(), role); }

TEST_F(GmapServerTest, test_get_UGG_feature) {
  ASSERT_EQ(GmapServer::GetUGGFeature(), UGG_feature);
}

TEST_F(GmapServerTest, test_add_service) {
  tBTA_GATTS gatts_cb_data;
  uint8_t server_if = 10;
  gatts_cb_data.reg_oper.status = GATT_SUCCESS;
  gatts_cb_data.reg_oper.server_if = server_if;

  EXPECT_CALL(gatt_server_interface, AddService(_, _, _)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_REG_EVT, &gatts_cb_data);
}

TEST_F(GmapServerTest, test_app_deregister) {
  tBTA_GATTS gatts_cb_data;
  EXPECT_CALL(gatt_server_interface, AppDeregister(_)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_DEREG_EVT, &gatts_cb_data);
}

TEST_F(GmapServerTest, test_read_invalid_characteristic) {
  uint16_t handle = 10;
  tGATTS_DATA gatts_data;
  gatts_data.read_req.handle = handle;
  tBTA_GATTS gatts_cb_data;
  gatts_cb_data.req_data.p_data = &gatts_data;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_INVALID_HANDLE, _)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_cb_data);
}

TEST_F(GmapServerTest, test_read_invalid_role_characteristic) {
  uint16_t handle = 10;
  GmapCharacteristic invalidGmapCharacteristic{
          .uuid_ = bluetooth::le_audio::uuid::kTelephonyMediaAudioProfileRoleCharacteristicUuid,
          .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = invalidGmapCharacteristic;

  tGATTS_DATA gatts_data;
  gatts_data.read_req.handle = handle;
  tBTA_GATTS gatts_cb_data;
  gatts_cb_data.req_data.p_data = &gatts_data;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_ILLEGAL_PARAMETER, _)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_cb_data);
}

TEST_F(GmapServerTest, test_read_valid_role_characteristic) {
  uint16_t handle = 10;
  GmapCharacteristic gmapCharacteristic{.uuid_ = bluetooth::le_audio::uuid::kRoleCharacteristicUuid,
                                        .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  tGATTS_DATA gatts_data;
  gatts_data.read_req.handle = handle;
  tBTA_GATTS gatts_cb_data;
  gatts_cb_data.req_data.p_data = &gatts_data;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_cb_data);
}

TEST_F(GmapServerTest, test_read_valid_ugg_feature_characteristic) {
  uint16_t handle = 10;
  GmapCharacteristic gmapCharacteristic{
          .uuid_ = bluetooth::le_audio::uuid::kUnicastGameGatewayCharacteristicUuid,
          .attribute_handle_ = handle};
  GmapServer::GetCharacteristics()[handle] = gmapCharacteristic;

  tGATTS_DATA gatts_data;
  gatts_data.read_req.handle = handle;
  tBTA_GATTS gatts_cb_data;
  gatts_cb_data.req_data.p_data = &gatts_data;

  EXPECT_CALL(gatt_server_interface, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);
  GmapServer::GattsCallback(BTA_GATTS_READ_CHARACTERISTIC_EVT, &gatts_cb_data);
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
