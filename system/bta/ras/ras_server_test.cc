/*
 * Copyright 2025 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "bta/test/common/bta_gatt_api_mock.h"
#include "btm_api_mock.h"
#include "include/hardware/bluetooth.h"
#include "internal_include/stack_config.h"
#include "log/include/bluetooth/log.h"
#include "stack/include/bt_types.h"
#include "stack/include/main_thread.h"
#include "test/mock/mock_main_shim_entry.h"

using testing::_;
using testing::AnyNumber;
using testing::AtLeast;
using testing::AtMost;
using testing::DoAll;
using testing::Expectation;
using testing::InSequence;
using testing::Invoke;
using testing::Matcher;
using testing::Mock;
using testing::MockFunction;
using testing::NiceMock;
using testing::NotNull;
using testing::Return;
using testing::SaveArg;
using testing::SetArgPointee;
using testing::Test;
using testing::WithArg;

using namespace bluetooth::ras;
using namespace ::ras;
using namespace ::ras::uuid;
using namespace bluetooth;

static const uint16_t kVendorSpecificCharacteristic16Bit1 = 0x5566;
static const uint16_t kVendorSpecificCharacteristic16Bit2 = 0x5567;
static const bluetooth::Uuid kVendorSpecificCharacteristic1 =
        bluetooth::Uuid::From16Bit(kVendorSpecificCharacteristic16Bit1);
static const bluetooth::Uuid kVendorSpecificCharacteristic2 =
        bluetooth::Uuid::From16Bit(kVendorSpecificCharacteristic16Bit2);

static uint16_t GetCharacteristicHandle(const bluetooth::Uuid& uuid) {
  switch (uuid.As16Bit()) {
    case kRasFeaturesCharacteristic16bit:
      return 0x0001;
    case kRasRealTimeRangingDataCharacteristic16bit:
      return 0x0002;
    case kRasOnDemandDataCharacteristic16bit:
      return 0x0004;
    case kRasControlPointCharacteristic16bit:
      return 0x0006;
    case kRasRangingDataReadyCharacteristic16bit:
      return 0x0008;
    case kRasRangingDataOverWrittenCharacteristic16bit:
      return 0x000a;
    case kVendorSpecificCharacteristic16Bit1:
      return 0x000c;
    case kVendorSpecificCharacteristic16Bit2:
      return 0x000d;
    default:
      bluetooth::log::warn("Unknown uuid");
      return 0xFFF0;
  }
}

static uint16_t GetDescriptorHandle(const bluetooth::Uuid& uuid) {
  return GetCharacteristicHandle(uuid) + 1;
}

static void UpdateTestServiceHandle(std::vector<btgatt_db_element_t>& service) {
  for (uint16_t i = 0; i < service.size(); i++) {
    service[i].attribute_handle = GetCharacteristicHandle(service[i].uuid);
    // Check if descriptor exist
    if (i < service.size() - 1 && service[i + 1].type == BTGATT_DB_DESCRIPTOR) {
      service[i + 1].attribute_handle = GetDescriptorHandle(service[i].uuid);
      i++;
    }
  }
}

bt_status_t do_in_main_thread(base::OnceClosure task) {
  if (task.is_null()) {
    bluetooth::log::error("Task is null!");
    return BT_STATUS_FAIL;
  }
  std::move(task).Run();
  return BT_STATUS_SUCCESS;
}

namespace bluetooth::ras {

class MockRasServerCallbacks : public RasServerCallbacks {
public:
  MOCK_METHOD(void, OnVendorSpecificReply,
              (const RawAddress& address,
               const std::vector<VendorSpecificCharacteristic>& vendor_specific_reply),
              (override));
  MOCK_METHOD(void, OnRasServerConnected, (const RawAddress& identity_address), (override));
  MOCK_METHOD(void, OnMtuChangedFromServer, (const RawAddress& address, uint16_t mtu), (override));
  MOCK_METHOD(void, OnRasServerDisconnected, (const RawAddress& identity_address), (override));
};

class RasServerTestNoInit : public ::testing::Test {
protected:
  void SetUp() override {
    // Init test data
    gatt::SetMockBtaGattServerInterface(&mock_gatt_server_interface_);
    bluetooth::manager::SetMockBtmInterface(&btm_interface_);
    RawAddress::FromString("11:22:33:44:55:66", test_address_);
    VendorSpecificCharacteristic vendor_specific_characteristic1, vendor_specific_characteristic2;
    vendor_specific_characteristic1.characteristicUuid_ = kVendorSpecificCharacteristic1;
    vendor_specific_characteristic1.value_ = {0x01, 0x02, 0x03};
    vendor_specific_characteristic2.characteristicUuid_ = kVendorSpecificCharacteristic2;
    vendor_specific_characteristic2.value_ = {0x04, 0x05, 0x06};
    vendor_specific_characteristics_.push_back(vendor_specific_characteristic1);
    vendor_specific_characteristics_.push_back(vendor_specific_characteristic2);
  }

  void TearDown() override {
    gatt::SetMockBtaGattInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
  }

  std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics_;
  RawAddress test_address_;
  uint16_t test_conn_id_ = 0x0001;
  tBTA_GATTS_CBACK* captured_gatt_callback_ = nullptr;
  gatt::MockBtaGattServerInterface mock_gatt_server_interface_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface_;
  MockRasServerCallbacks mock_ras_server_callbacks_;
};

class RasServerTest : public RasServerTestNoInit {
protected:
  void SetUp() override {
    RasServerTestNoInit::SetUp();
    // AppRegister should be triggered when Initialize
    EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
            .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
    GetRasServer()->SetVendorSpecificCharacteristic(vendor_specific_characteristics_);
    GetRasServer()->Initialize();
    ASSERT_NE(captured_gatt_callback_, nullptr);

    // RegisterCallback
    GetRasServer()->RegisterCallbacks(&mock_ras_server_callbacks_);

    // AddService should be triggered after receiving BTA_GATTS_REG_EVT
    tGATT_IF captured_server_if;
    std::vector<btgatt_db_element_t> captured_service;
    BTA_GATTS_AddServiceCb captured_cb;
    EXPECT_CALL(mock_gatt_server_interface_, AddService(_, _, _))
            .WillOnce(testing::DoAll(testing::SaveArg<0>(&captured_server_if),
                                     testing::SaveArg<1>(&captured_service),
                                     testing::SaveArg<2>(&captured_cb), testing::Return()));

    // Mock BTA_GATTS_REG_EVT
    tBTA_GATTS gatts_cb_data;
    gatts_cb_data.reg_oper.status = GATT_SUCCESS;
    captured_gatt_callback_(BTA_GATTS_REG_EVT, &gatts_cb_data);

    // Update handle for testing
    UpdateTestServiceHandle(captured_service);

    // Run BTA_GATTS_AddServiceCb
    captured_cb.Run(GATT_SUCCESS, captured_server_if, std::move(captured_service));

    // OnRasServerConnected should be triggered after receiving BTA_GATTS_CONNECT_EVT
    EXPECT_CALL(mock_ras_server_callbacks_, OnRasServerConnected(test_address_)).Times(1);
    tBTA_GATTS p_data;
    p_data.conn.transport = BT_TRANSPORT_LE;
    p_data.conn.remote_bda = test_address_;
    p_data.conn.conn_id = test_conn_id_;
    captured_gatt_callback_(BTA_GATTS_CONNECT_EVT, &p_data);
  }

  void TearDown() override {
    tBTA_GATTS p_data;
    p_data.conn.remote_bda = test_address_;
    p_data.conn.conn_id = test_conn_id_;
    captured_gatt_callback_(BTA_GATTS_DISCONNECT_EVT, &p_data);
    RasServerTestNoInit::TearDown();
  }
};

TEST_F(RasServerTestNoInit, InitializationSuccessful) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasServer()->SetVendorSpecificCharacteristic(vendor_specific_characteristics_);
  GetRasServer()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);

  // AddService should be triggered after receiving BTA_GATTS_REG_EVT
  tGATT_IF captured_server_if;
  std::vector<btgatt_db_element_t> captured_service;
  BTA_GATTS_AddServiceCb captured_cb;
  EXPECT_CALL(mock_gatt_server_interface_, AddService(_, _, _))
          .WillOnce(testing::DoAll(testing::SaveArg<0>(&captured_server_if),
                                   testing::SaveArg<1>(&captured_service),
                                   testing::SaveArg<2>(&captured_cb), testing::Return()));

  // Mock BTA_GATTS_REG_EVT
  tBTA_GATTS gatts_cb_data;
  gatts_cb_data.reg_oper.status = GATT_SUCCESS;
  captured_gatt_callback_(BTA_GATTS_REG_EVT, &gatts_cb_data);

  // Run BTA_GATTS_AddServiceCb
  captured_cb.Run(GATT_SUCCESS, captured_server_if, std::move(captured_service));
}

TEST_F(RasServerTestNoInit, ConnectAndDisconnect) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasServer()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);

  // RegisterCallback
  GetRasServer()->RegisterCallbacks(&mock_ras_server_callbacks_);

  // OnRasServerConnected should be triggered after receiving BTA_GATTS_CONNECT_EVT
  EXPECT_CALL(mock_ras_server_callbacks_, OnRasServerConnected(test_address_)).Times(1);
  tBTA_GATTS p_data;
  p_data.conn.transport = BT_TRANSPORT_LE;
  p_data.conn.remote_bda = test_address_;
  p_data.conn.conn_id = test_conn_id_;
  captured_gatt_callback_(BTA_GATTS_CONNECT_EVT, &p_data);

  // OnRasServerDisconnected should be triggered after receiving BTA_GATTS_DISCONNECT_EVT
  EXPECT_CALL(mock_ras_server_callbacks_, OnRasServerDisconnected(test_address_)).Times(1);
  tBTA_GATTS p_data2;
  p_data2.conn.remote_bda = test_address_;
  p_data2.conn.conn_id = test_conn_id_;
  captured_gatt_callback_(BTA_GATTS_DISCONNECT_EVT, &p_data2);
}

TEST_F(RasServerTestNoInit, IgnoreBrEdr) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasServer()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);

  // RegisterCallback
  GetRasServer()->RegisterCallbacks(&mock_ras_server_callbacks_);

  // OnRasServerConnected should be triggered after receiving BTA_GATTS_CONNECT_EVT
  EXPECT_CALL(mock_ras_server_callbacks_, OnRasServerConnected(test_address_)).Times(0);
  tBTA_GATTS p_data;
  p_data.conn.transport = BT_TRANSPORT_BR_EDR;
  p_data.conn.remote_bda = test_address_;
  p_data.conn.conn_id = test_conn_id_;
  captured_gatt_callback_(BTA_GATTS_CONNECT_EVT, &p_data);
}

TEST_F(RasServerTest, EmptyTest) {}

TEST_F(RasServerTest, GattMtuChanged) {
  uint16_t mtu = 512;
  // OnMtuChangedFromServer should be triggered after receiving BTA_GATTS_MTU_EVT
  EXPECT_CALL(mock_ras_server_callbacks_, OnMtuChangedFromServer(test_address_, mtu)).Times(1);
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.mtu = mtu;
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_MTU_EVT, &p_data);
}

TEST_F(RasServerTest, ReadCharacteristic) {
  // Read kRasFeaturesCharacteristic
  tGATT_STATUS captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.read_req.handle = GetCharacteristicHandle(kRasFeaturesCharacteristic);
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);

  // Read kRasRangingDataReadyCharacteristic
  captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.p_data->read_req.handle =
          GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);

  // Read kRasRangingDataOverWrittenCharacteristic
  captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.p_data->read_req.handle =
          GetCharacteristicHandle(kRasRangingDataOverWrittenCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);

  // Read kVendorSpecificCharacteristic1
  captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.p_data->read_req.handle = GetCharacteristicHandle(kVendorSpecificCharacteristic1);
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);
}

TEST_F(RasServerTest, ReadCharacteristicInvalid) {
  // Read invalid handle
  tGATT_STATUS captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.read_req.handle = 0x1234;  // Invalid handle
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_INVALID_HANDLE, captured_status);

  // Read invalid address
  captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  RawAddress invalid_address;
  RawAddress::FromString("11:22:33:44:55:77", invalid_address);
  p_data.req_data.remote_bda = invalid_address;
  p_data.req_data.p_data->read_req.handle =
          GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  bluetooth::log::info("captured_status");
  EXPECT_EQ(GATT_ILLEGAL_PARAMETER, captured_status);

  // Unhandled uuid
  captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data->read_req.handle =
          GetCharacteristicHandle(kRasRealTimeRangingDataCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_ILLEGAL_PARAMETER, captured_status);
}

TEST_F(RasServerTest, ReadWriteDescriptor) {
  // Write descriptor of kRasRangingDataReadyCharacteristic
  uint16_t ccc_value = GATT_CLT_CONFIG_INDICATION;
  tGATT_STATUS captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);

  // Read descriptor of kRasRangingDataReadyCharacteristic
  captured_status = GATT_ERROR;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(SaveArg<2>(&captured_status));
  p_data.req_data.p_data->read_req.handle = GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);
}

TEST_F(RasServerTest, ReadWriteDescriptorInvalid) {
  // Write descriptor, only Client Characteristic Configuration (CCC) descriptor is expected
  uint16_t ccc_value = GATT_CLT_CONFIG_INDICATION;
  tGATT_STATUS captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_INVALID_HANDLE, captured_status);

  // Invalid address
  captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  RawAddress invalid_address;
  RawAddress::FromString("11:22:33:44:55:77", invalid_address);
  p_data.req_data.remote_bda = invalid_address;
  p_data.req_data.p_data->write_req.handle =
          GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_ILLEGAL_PARAMETER, captured_status);

  // Check that On-demand and Real-time are not registered at the same time
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data->write_req.handle = GetDescriptorHandle(kRasOnDemandDataCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_SUCCESS, captured_status);

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.p_data->write_req.handle =
          GetDescriptorHandle(kRasRealTimeRangingDataCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_CCC_CFG_ERR, captured_status);

  // Read descriptor, only Client Characteristic Configuration (CCC) descriptor is expected
  captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(SaveArg<2>(&captured_status));
  p_data.req_data.p_data->read_req.handle =
          GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, &p_data);
  EXPECT_EQ(GATT_INVALID_HANDLE, captured_status);
}

TEST_F(RasServerTest, WriteCharacteristicInalid) {
  // Invalid handle
  tGATT_STATUS captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = 0x3456;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_INVALID_HANDLE, captured_status);

  // Invalid uuid
  captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  p_data.req_data.p_data->write_req.handle =
          GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_ILLEGAL_PARAMETER, captured_status);

  // Invalid address
  captured_status = GATT_SUCCESS;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, _, _))
          .WillOnce(testing::SaveArg<2>(&captured_status));
  RawAddress invalid_address;
  RawAddress::FromString("11:22:33:44:55:77", invalid_address);
  p_data.req_data.remote_bda = invalid_address;
  p_data.req_data.p_data->write_req.handle =
          GetCharacteristicHandle(kRasControlPointCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data);
  EXPECT_EQ(GATT_ILLEGAL_PARAMETER, captured_status);
}

TEST_F(RasServerTest, PushRealTimeData) {
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04};
  uint16_t procedure_counter = 0x1234;

  // Enable Real-time notifications
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasRealTimeRangingDataCharacteristic);
  uint16_t ccc_value = GATT_CLT_CONFIG_NOTIFICATION;
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);

  // Expect a notification
  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(test_conn_id_, _, _, false))
          .Times(1);

  GetRasServer()->PushProcedureData(test_address_, procedure_counter, true, data);
}

TEST_F(RasServerTest, PushOnDemandData) {
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04};
  uint16_t procedure_counter = 0x1234;

  // Enable data ready indications
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
  uint16_t ccc_value = GATT_CLT_CONFIG_INDICATION;
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);

  // Expect a data ready indication
  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(test_conn_id_, _, _, true))
          .Times(1);

  GetRasServer()->PushProcedureData(test_address_, procedure_counter, true, data);
}

TEST_F(RasServerTest, DataOverwritten) {
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04};
  uint16_t procedure_counter1 = 0x1234;
  uint16_t procedure_counter2 = 0x1235;
  uint16_t procedure_counter3 = 0x1236;
  uint16_t procedure_counter4 = 0x1237;

  // Enable data overwritten indications
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasOnDemandDataCharacteristic);
  uint16_t ccc_value = GATT_CLT_CONFIG_INDICATION;
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasRangingDataOverWrittenCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);

  // Expect a data overwritten indication for procedure_counter1
  std::vector<uint8_t> overwritten_value1(2);
  overwritten_value1[0] = (procedure_counter1 & 0xFF);
  overwritten_value1[1] = (procedure_counter1 >> 8) & 0xFF;

  EXPECT_CALL(mock_gatt_server_interface_,
              HandleValueIndication(test_conn_id_, _, overwritten_value1, true))
          .Times(1);

  // Push data segments to fill and overflow buffer
  GetRasServer()->PushProcedureData(test_address_, procedure_counter1, true, data);
  GetRasServer()->PushProcedureData(test_address_, procedure_counter2, true, data);
  GetRasServer()->PushProcedureData(test_address_, procedure_counter3, true, data);
  GetRasServer()->PushProcedureData(test_address_, procedure_counter4, true, data);
}

TEST_F(RasServerTest, WriteVendorSpecificCharacteristic) {
  std::vector<uint8_t> value1 = {0x0A, 0x0B, 0x0C};
  std::vector<uint8_t> value2 = {0x0D, 0x0E, 0x0F};

  // Expect OnVendorSpecificReply to be called
  EXPECT_CALL(mock_ras_server_callbacks_, OnVendorSpecificReply(test_address_, _)).Times(1);

  // Write the first characteristic
  tBTA_GATTS p_data1;
  tGATTS_DATA p_req_data1;
  p_req_data1.write_req.handle = GetCharacteristicHandle(kVendorSpecificCharacteristic1);
  p_req_data1.write_req.len = value1.size();
  memcpy(p_req_data1.write_req.value, value1.data(), value1.size());
  p_data1.req_data.remote_bda = test_address_;
  p_data1.req_data.conn_id = test_conn_id_;
  p_data1.req_data.p_data = &p_req_data1;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data1);

  // Write the second characteristic
  tBTA_GATTS p_data2;
  tGATTS_DATA p_req_data2;
  p_req_data2.write_req.handle = GetCharacteristicHandle(kVendorSpecificCharacteristic2);
  p_req_data2.write_req.len = value2.size();
  memcpy(p_req_data2.write_req.value, value2.data(), value2.size());
  p_data2.req_data.remote_bda = test_address_;
  p_data2.req_data.conn_id = test_conn_id_;
  p_data2.req_data.p_data = &p_req_data2;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data2);

  // Expect SendRsp to be called with GATT_SUCCESS
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(test_conn_id_, _, GATT_SUCCESS, _)).Times(1);
  GetRasServer()->HandleVendorSpecificReplyComplete(test_address_, true);
}

TEST_F(RasServerTest, UnsupportedOpcode) {
  std::vector<uint8_t> command = {
          static_cast<uint8_t>(Opcode::FILTER),  // Unsupported opcode
          0x01,                                  // Some parameter
          0x02,
  };

  // Expect SendRsp to be called with GATT_SUCCESS (for the write)
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(_, _, GATT_SUCCESS, _)).Times(1);

  // Expect HandleValueIndication to be called with the response code
  std::vector<uint8_t> expected_response = {
          static_cast<uint8_t>(EventCode::RESPONSE_CODE),
          static_cast<uint8_t>(ResponseCodeValue::OP_CODE_NOT_SUPPORTED),
  };
  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(_, _, expected_response, true))
          .Times(1);

  // Simulate a write to the RAS Control Point
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
  p_req_data.write_req.len = command.size();
  memcpy(p_req_data.write_req.value, command.data(), command.size());
  p_req_data.write_req.need_rsp = true;
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data);
}

TEST_F(RasServerTest, GetAckRangingData) {
  // Enable On-demand indications and data ready indications
  tBTA_GATTS p_data;
  tGATTS_DATA p_req_data;
  p_req_data.write_req.handle = GetDescriptorHandle(kRasOnDemandDataCharacteristic);
  uint16_t ccc_value = GATT_CLT_CONFIG_INDICATION;
  p_req_data.write_req.len = 0x02;
  memcpy(p_req_data.write_req.value, &ccc_value, sizeof(uint16_t));
  p_data.req_data.remote_bda = test_address_;
  p_data.req_data.p_data = &p_req_data;
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);

  p_req_data.write_req.handle = GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data);

  uint16_t procedure_counter = 0x6677;
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04};
  EXPECT_CALL(mock_gatt_server_interface_,
              HandleValueIndication(_, GetCharacteristicHandle(kRasRangingDataReadyCharacteristic),
                                    _, true))
          .Times(1);
  // Push data into the buffer
  GetRasServer()->PushProcedureData(test_address_, procedure_counter, true, data);

  // Construct a GET_RANGING_DATA command
  std::vector<uint8_t> get_command = {
          static_cast<uint8_t>(Opcode::GET_RANGING_DATA),
          static_cast<uint8_t>(procedure_counter & 0xFF),
          static_cast<uint8_t>((procedure_counter >> 8) & 0xFF),
  };

  // Expect HandleValueIndication for the control point response
  std::vector<uint8_t> expected_get_response = {
          static_cast<uint8_t>(EventCode::COMPLETE_RANGING_DATA_RESPONSE),
          static_cast<uint8_t>(procedure_counter & 0xFF),
          static_cast<uint8_t>((procedure_counter >> 8) & 0xFF),
  };
  EXPECT_CALL(mock_gatt_server_interface_,
              HandleValueIndication(_, GetCharacteristicHandle(kRasOnDemandDataCharacteristic), _,
                                    true))
          .Times(1);
  EXPECT_CALL(mock_gatt_server_interface_,
              HandleValueIndication(_, GetCharacteristicHandle(kRasControlPointCharacteristic),
                                    expected_get_response, true))
          .Times(1);

  // Simulate a write to the RAS Control Point
  tBTA_GATTS p_data2;
  tGATTS_DATA p_req_data2;
  p_req_data2.write_req.handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
  p_req_data2.write_req.len = get_command.size();
  memcpy(p_req_data2.write_req.value, get_command.data(), get_command.size());
  p_req_data2.write_req.need_rsp = true;
  p_data2.req_data.remote_bda = test_address_;
  p_data2.req_data.p_data = &p_req_data2;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data2);

  // Construct an ACK_RANGING_DATA command
  std::vector<uint8_t> ack_command = {
          static_cast<uint8_t>(Opcode::ACK_RANGING_DATA),
          static_cast<uint8_t>(procedure_counter & 0xFF),
          static_cast<uint8_t>((procedure_counter >> 8) & 0xFF),
  };

  // Expect HandleValueIndication for the control point response (No Records
  // Found)
  std::vector<uint8_t> expected_ack_response = {
          static_cast<uint8_t>(EventCode::RESPONSE_CODE),
          static_cast<uint8_t>(ResponseCodeValue::SUCCESS),
  };
  EXPECT_CALL(mock_gatt_server_interface_,
              HandleValueIndication(_, GetCharacteristicHandle(kRasControlPointCharacteristic),
                                    expected_ack_response, true))
          .Times(1);

  // Simulate a write to the RAS Control Point
  tBTA_GATTS p_data3;
  tGATTS_DATA p_req_data3;
  p_req_data3.write_req.handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
  p_req_data3.write_req.len = ack_command.size();
  memcpy(p_req_data3.write_req.value, ack_command.data(), ack_command.size());
  p_req_data3.write_req.need_rsp = true;
  p_data3.req_data.remote_bda = test_address_;
  p_data3.req_data.p_data = &p_req_data3;
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data3);
}

}  // namespace bluetooth::ras
