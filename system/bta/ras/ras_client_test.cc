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
#include "bta/mock/bta_gatt_api_mock.h"
#include "bta/ras/ras_types.h"
#include "bta_gatt_queue_mock.h"
#include "btm_api_mock.h"
#include "fake_osi.h"
#include "include/hardware/bluetooth.h"
#include "internal_include/stack_config.h"
#include "log/include/bluetooth/log.h"
#include "stack/btm/btm_device_record.h"
#include "stack/include/bt_types.h"
#include "stack/include/main_thread.h"
#include "test/mock/mock_main_shim_entry.h"

using testing::_;
using testing::AtLeast;
using testing::AtMost;
using testing::DoAll;
using testing::Invoke;
using testing::Mock;
using testing::MockFunction;
using testing::NiceMock;
using testing::NotNull;
using testing::Return;
using testing::SaveArg;
using testing::Test;
using testing::WithArg;

using namespace bluetooth::ras;
using namespace ::ras;
using namespace ::ras::uuid;
using namespace bluetooth;
extern struct fake_osi_alarm_set_on_mloop fake_osi_alarm_set_on_mloop_;

static const uint16_t kVendorSpecificCharacteristic16Bit1 = 0x5566;
static const uint16_t kVendorSpecificCharacteristic16Bit2 = 0x5567;
static constexpr bluetooth::Uuid kVendorSpecificCharacteristic1 =
        bluetooth::Uuid::From16Bit(kVendorSpecificCharacteristic16Bit1);
static constexpr bluetooth::Uuid kVendorSpecificCharacteristic2 =
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

gatt::Service test_ranging_service_;
std::list<gatt::Service> services_to_return_;

static uint16_t GetDescriptorHandle(const bluetooth::Uuid& uuid) {
  return GetCharacteristicHandle(uuid) + 1;
}

namespace bluetooth::ras {

class MockRasClientCallbacks : public RasClientCallbacks {
public:
  MOCK_METHOD(void, OnConnected,
              (const RawAddress& address, uint16_t att_handle,
               const std::vector<VendorSpecificCharacteristic>& vendor_specific_characteristics,
               uint16_t conn_interval),
              (override));
  MOCK_METHOD(void, OnConnIntervalUpdated, (const RawAddress& address, uint16_t conn_interval),
              (override));
  MOCK_METHOD(void, OnDisconnected,
              (const RawAddress& address, const RasDisconnectReason& ras_disconnect_reason),
              (override));
  MOCK_METHOD(void, OnWriteVendorSpecificReplyComplete, (const RawAddress& address, bool success),
              (override));
  MOCK_METHOD(void, OnRemoteData, (const RawAddress& address, const std::vector<uint8_t>& data),
              (override));
  MOCK_METHOD(void, OnRemoteDataTimeout, (const RawAddress& address), (override));
  MOCK_METHOD(void, OnMtuChangedFromClient, (const RawAddress& address, uint16_t mtu), (override));
};

class RasClientTestNoInit : public ::testing::Test {
protected:
  void SetUp() override {
    // Init test data
    InitRangingService();
    gatt::SetMockBtaGattInterface(&mock_gatt_interface_);
    bluetooth::manager::SetMockBtmInterface(&btm_interface_);
    test_address_ = RawAddress::FromString("11:22:33:44:55:66").value();
    VendorSpecificCharacteristic vendor_specific_characteristic1, vendor_specific_characteristic2;
    vendor_specific_characteristic1.characteristicUuid_ = kVendorSpecificCharacteristic1;
    vendor_specific_characteristic1.reply_value_ = {0x01, 0x02, 0x03};
    vendor_specific_characteristic2.characteristicUuid_ = kVendorSpecificCharacteristic2;
    vendor_specific_characteristic2.reply_value_ = {0x04, 0x05, 0x06};
    vendor_specific_characteristics_.push_back(vendor_specific_characteristic1);
    vendor_specific_characteristics_.push_back(vendor_specific_characteristic2);
    // Set a default behavior for all calls to ReadCharacteristic
    ON_CALL(mock_gatt_interface_, ReadCharacteristic(test_conn_id_, _, _, _, _))
            .WillByDefault(Invoke([](tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ /*auth_req*/,
                                     GATT_READ_OP_CB callback, void* cb_data) -> void {
              std::vector<uint8_t> value;
              switch (handle) {
                case 0x0001:  // kRasFeaturesCharacteristic
                  value.assign(4, 0xFF);
                  break;
                case 0x0008:  // kRasRangingDataReadyCharacteristic
                case 0x000a:  // kRasRangingDataOverWrittenCharacteristic
                  value.assign(2, 1);
                  break;
                case 0x000c:  // kVendorSpecificCharacteristic1
                  value.assign(4, 2);
                  break;
                case 0x000d:  // kVendorSpecificCharacteristic2
                  value.assign(3, 1);
                  break;
                default:
                  FAIL();
                  return;
              }
              callback(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
            }));
    ON_CALL(mock_gatt_interface_, WriteCharValue(test_conn_id_, _, _, _, _, _, _))
            .WillByDefault(Invoke([](tCONN_ID conn_id, uint16_t handle,
                                     tGATT_WRITE_TYPE /* write_type */, std::vector<uint8_t> value,
                                     tGATT_AUTH_REQ /* auth_req */, GATT_WRITE_OP_CB callback,
                                     void* cb_data) -> void {
              if (callback) {
                callback(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
              }
            }));
  }

  void TearDown() {
    gatt::SetMockBtaGattInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
  }

  void DisconnectGatt() {
    tBTA_GATTC p_data5;
    tBTA_GATTC_CLOSE close_data;
    close_data.remote_bda = test_address_;
    close_data.conn_id = test_conn_id_;
    close_data.status = GATT_SUCCESS;
    close_data.reason = GATT_CONN_TIMEOUT;
    p_data5.close = close_data;
    captured_gatt_callback_(BTA_GATTC_CLOSE_EVT, &p_data5);
  }

  void InitRangingService() {
    if (test_ranging_service_.characteristics.size() > 1) {
      // Prevent for duplicate init
      return;
    }

    test_ranging_service_.handle = 1;              // Or whatever the starting handle should be
    test_ranging_service_.uuid = kRangingService;  // Ranging Service UUID
    test_ranging_service_.is_primary = true;
    test_ranging_service_.end_handle = 0x000d;  // Adjust based on the last attribute handle

    // RAS Features
    gatt::Characteristic ras_features;
    ras_features.declaration_handle = GetCharacteristicHandle(kRasFeaturesCharacteristic);
    ras_features.uuid = kRasFeaturesCharacteristic;
    ras_features.value_handle = GetCharacteristicHandle(kRasFeaturesCharacteristic);
    ras_features.properties = GATT_CHAR_PROP_BIT_READ;
    test_ranging_service_.characteristics.push_back(ras_features);

    // Real-time Ranging Data
    gatt::Characteristic real_time_data;
    real_time_data.declaration_handle =
            GetCharacteristicHandle(kRasRealTimeRangingDataCharacteristic);
    real_time_data.uuid = kRasRealTimeRangingDataCharacteristic;
    real_time_data.value_handle = GetCharacteristicHandle(kRasRealTimeRangingDataCharacteristic);
    real_time_data.properties = GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    gatt::Descriptor ccc_descriptor;
    ccc_descriptor.handle = GetDescriptorHandle(kRasRealTimeRangingDataCharacteristic);
    ccc_descriptor.uuid = kClientCharacteristicConfiguration;
    real_time_data.descriptors.push_back(ccc_descriptor);
    test_ranging_service_.characteristics.push_back(real_time_data);

    // On-demand Ranging Data
    gatt::Characteristic on_demand_data;
    on_demand_data.declaration_handle = GetCharacteristicHandle(kRasOnDemandDataCharacteristic);
    on_demand_data.uuid = kRasOnDemandDataCharacteristic;
    on_demand_data.value_handle = GetCharacteristicHandle(kRasOnDemandDataCharacteristic);
    on_demand_data.properties = GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    ccc_descriptor.handle = GetDescriptorHandle(kRasOnDemandDataCharacteristic);
    on_demand_data.descriptors.push_back(ccc_descriptor);
    test_ranging_service_.characteristics.push_back(on_demand_data);

    // RAS Control Point (RAS-CP)
    gatt::Characteristic ras_control_point;
    ras_control_point.declaration_handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
    ras_control_point.uuid = kRasControlPointCharacteristic;
    ras_control_point.value_handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
    ras_control_point.properties = GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_INDICATE;
    ccc_descriptor.handle = GetDescriptorHandle(kRasControlPointCharacteristic);
    ras_control_point.descriptors.push_back(ccc_descriptor);
    test_ranging_service_.characteristics.push_back(ras_control_point);

    // Ranging Data Ready
    gatt::Characteristic ranging_data_ready;
    ranging_data_ready.declaration_handle =
            GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
    ranging_data_ready.uuid = kRasRangingDataReadyCharacteristic;
    ranging_data_ready.value_handle = GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
    ranging_data_ready.properties =
            GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    ccc_descriptor.handle = GetDescriptorHandle(kRasRangingDataReadyCharacteristic);
    ranging_data_ready.descriptors.push_back(ccc_descriptor);
    test_ranging_service_.characteristics.push_back(ranging_data_ready);

    // Ranging Data Overwritten
    gatt::Characteristic ranging_data_overwritten;
    ranging_data_overwritten.declaration_handle =
            GetCharacteristicHandle(kRasRangingDataOverWrittenCharacteristic);
    ranging_data_overwritten.uuid = kRasRangingDataOverWrittenCharacteristic;
    ranging_data_overwritten.value_handle =
            GetCharacteristicHandle(kRasRangingDataOverWrittenCharacteristic);
    ranging_data_overwritten.properties =
            GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    ccc_descriptor.handle = GetDescriptorHandle(kRasRangingDataOverWrittenCharacteristic);
    ranging_data_overwritten.descriptors.push_back(ccc_descriptor);
    test_ranging_service_.characteristics.push_back(ranging_data_overwritten);

    // Vendor Specific Characteristic1 (0x5566)
    gatt::Characteristic vendor_specific_characteristic1;
    vendor_specific_characteristic1.declaration_handle =
            GetCharacteristicHandle(kVendorSpecificCharacteristic1);
    vendor_specific_characteristic1.uuid = kVendorSpecificCharacteristic1;
    vendor_specific_characteristic1.value_handle =
            GetCharacteristicHandle(kVendorSpecificCharacteristic1);
    vendor_specific_characteristic1.properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE;
    test_ranging_service_.characteristics.push_back(vendor_specific_characteristic1);

    // Vendor Specific Characteristic2 (0x5567)
    gatt::Characteristic vendor_specific_characteristic2;
    vendor_specific_characteristic2.declaration_handle =
            GetCharacteristicHandle(kVendorSpecificCharacteristic2);
    vendor_specific_characteristic2.uuid = kVendorSpecificCharacteristic2;
    vendor_specific_characteristic2.value_handle =
            GetCharacteristicHandle(kVendorSpecificCharacteristic2);
    vendor_specific_characteristic2.properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE;
    test_ranging_service_.characteristics.push_back(vendor_specific_characteristic2);

    services_to_return_.push_back(test_ranging_service_);
  }
  RawAddress test_address_;
  uint16_t test_conn_id_ = 0x0001;
  tBTA_GATTC_CBACK* captured_gatt_callback_ = nullptr;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface_;
  gatt::MockBtaGattInterface mock_gatt_interface_;
  MockRasClientCallbacks mock_ras_client_callbacks_;
  std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics_;
};

class RasClientTest : public RasClientTestNoInit {
  void SetUp() override {
    RasClientTestNoInit::SetUp();
    // AppRegister should be triggered when Initialize
    EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
            .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
    GetRasClient()->Initialize();
    ASSERT_NE(captured_gatt_callback_, nullptr);
    GetRasClient()->RegisterCallbacks(&mock_ras_client_callbacks_);

    // Open should be triggered when connect
    EXPECT_CALL(mock_gatt_interface_, Open(_, test_address_, BTM_BLE_OPPORTUNISTIC)).Times(1);
    GetRasClient()->Connect(test_address_);

    // ServiceSearchRequest should be trigger after BTA_GATTC_OPEN_EVT
    EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(test_conn_id_)).Times(1);
    tBTA_GATTC p_data;
    tBTA_GATTC_OPEN open_event_data;
    open_event_data.remote_bda = test_address_;
    open_event_data.conn_id = test_conn_id_;
    open_event_data.status = GATT_SUCCESS;
    open_event_data.transport = BT_TRANSPORT_LE;
    p_data.open = open_event_data;
    captured_gatt_callback_(BTA_GATTC_OPEN_EVT, &p_data);

    // GetServices should after BTA_GATTC_SEARCH_CMPL_EVT
    EXPECT_CALL(mock_gatt_interface_, GetServices(test_conn_id_))
            .WillOnce(Return(&services_to_return_));

    // ConfigureMTU should after be trigger BTA_GATTC_OPEN_EVT
    EXPECT_CALL(mock_gatt_interface_, ConfigureMTU(test_conn_id_, _)).Times(1);
    tBTA_GATTC p_data2;
    tBTA_GATTC_SEARCH_CMPL search_cmpl_event_data;
    search_cmpl_event_data.conn_id = test_conn_id_;
    p_data2.search_cmpl = search_cmpl_event_data;
    captured_gatt_callback_(BTA_GATTC_SEARCH_CMPL_EVT, &p_data2);

    // OnMtuChangedFromClient should be triggered after receiving BTA_GATTC_CFG_MTU_EVT
    EXPECT_CALL(mock_ras_client_callbacks_, OnMtuChangedFromClient(test_address_, 517)).Times(1);
    tBTA_GATTC p_data3;
    tBTA_GATTC_CFG_MTU config_mtu_data;
    config_mtu_data.conn_id = test_conn_id_;
    config_mtu_data.status = GATT_SUCCESS;
    config_mtu_data.mtu = 517;
    p_data3.cfg_mtu = config_mtu_data;
    captured_gatt_callback_(BTA_GATTC_CFG_MTU_EVT, &p_data3);

    // OnWriteVendorSpecificReplyComplete should be triggered after all vendor specific reply sent
    EXPECT_CALL(mock_ras_client_callbacks_, OnWriteVendorSpecificReplyComplete(test_address_, true))
            .Times(1);
    GetRasClient()->SendVendorSpecificReply(test_address_, vendor_specific_characteristics_);
  }

  void TearDown() override {
    // OnDisconnected should be triggered after receiving BTA_GATTC_CLOSE_EVT
    EXPECT_CALL(mock_ras_client_callbacks_,
                OnDisconnected(test_address_, RasDisconnectReason::GATT_DISCONNECT))
            .Times(1);
    DisconnectGatt();
    RasClientTestNoInit::TearDown();
  }

protected:
  void SimulateNotification(uint16_t handle, const std::vector<uint8_t>& value) {
    tBTA_GATTC p_data;
    tBTA_GATTC_NOTIFY notify_data;
    notify_data.conn_id = test_conn_id_;
    notify_data.handle = handle;
    notify_data.len = value.size();
    std::copy(value.begin(), value.end(), notify_data.value);
    p_data.notify = notify_data;
    captured_gatt_callback_(BTA_GATTC_NOTIF_EVT, &p_data);
  }
};

TEST_F(RasClientTestNoInit, InitializationSuccessful) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasClient()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);
}

TEST_F(RasClientTestNoInit, ConnectDisconnect) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasClient()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);
  GetRasClient()->RegisterCallbacks(&mock_ras_client_callbacks_);

  // Open should be triggered when connect
  EXPECT_CALL(mock_gatt_interface_, Open(_, test_address_, BTM_BLE_OPPORTUNISTIC)).Times(1);
  GetRasClient()->Connect(test_address_);

  // ServiceSearchRequest should be trigger after BTA_GATTC_OPEN_EVT
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(test_conn_id_)).Times(1);
  tBTA_GATTC p_data;
  tBTA_GATTC_OPEN open_event_data;
  open_event_data.remote_bda = test_address_;
  open_event_data.conn_id = test_conn_id_;
  open_event_data.status = GATT_SUCCESS;
  open_event_data.transport = BT_TRANSPORT_LE;
  p_data.open = open_event_data;
  captured_gatt_callback_(BTA_GATTC_OPEN_EVT, &p_data);

  // GetServices should after BTA_GATTC_SEARCH_CMPL_EVT
  EXPECT_CALL(mock_gatt_interface_, GetServices(test_conn_id_))
          .WillOnce(Return(&services_to_return_));

  // ConfigureMTU should after be trigger BTA_GATTC_OPEN_EVT
  EXPECT_CALL(mock_gatt_interface_, ConfigureMTU(test_conn_id_, _)).Times(1);
  tBTA_GATTC p_data2;
  tBTA_GATTC_SEARCH_CMPL search_cmpl_event_data;
  search_cmpl_event_data.conn_id = test_conn_id_;
  p_data2.search_cmpl = search_cmpl_event_data;
  captured_gatt_callback_(BTA_GATTC_SEARCH_CMPL_EVT, &p_data2);

  // OnMtuChangedFromClient should be triggered after receiving BTA_GATTC_CFG_MTU_EVT
  EXPECT_CALL(mock_ras_client_callbacks_, OnMtuChangedFromClient(test_address_, 517)).Times(1);
  tBTA_GATTC p_data3;
  tBTA_GATTC_CFG_MTU config_mtu_data;
  config_mtu_data.conn_id = test_conn_id_;
  config_mtu_data.status = GATT_SUCCESS;
  config_mtu_data.mtu = 517;
  p_data3.cfg_mtu = config_mtu_data;
  captured_gatt_callback_(BTA_GATTC_CFG_MTU_EVT, &p_data3);

  // OnWriteVendorSpecificReplyComplete should be triggered after all vendor specific reply sent
  EXPECT_CALL(mock_ras_client_callbacks_, OnWriteVendorSpecificReplyComplete(test_address_, true))
          .Times(1);
  GetRasClient()->SendVendorSpecificReply(test_address_, vendor_specific_characteristics_);

  // OnConnIntervalUpdated should be triggered after receiving BTA_GATTC_CONN_UPDATE_EVT
  EXPECT_CALL(mock_ras_client_callbacks_, OnConnIntervalUpdated(test_address_, 0x1111)).Times(1);
  tBTA_GATTC p_data4;
  tBTA_GATTC_CONN_UPDATE conn_update_data;
  conn_update_data.conn_id = test_conn_id_;
  conn_update_data.interval = 0x1111;
  p_data4.conn_update = conn_update_data;
  captured_gatt_callback_(BTA_GATTC_CONN_UPDATE_EVT, &p_data4);

  // OnDisconnected should be triggered after receiving BTA_GATTC_CLOSE_EVT
  EXPECT_CALL(mock_ras_client_callbacks_,
              OnDisconnected(test_address_, RasDisconnectReason::GATT_DISCONNECT))
          .Times(1);

  DisconnectGatt();
}

TEST_F(RasClientTestNoInit, SetFirstSegmentTimeoutInLowPowerMode) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasClient()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);
  GetRasClient()->RegisterCallbacks(&mock_ras_client_callbacks_);

  // Open should be triggered when connect
  EXPECT_CALL(mock_gatt_interface_, Open(_, test_address_, BTM_BLE_OPPORTUNISTIC)).Times(1);
  GetRasClient()->Connect(test_address_);

  // ServiceSearchRequest should be trigger after BTA_GATTC_OPEN_EVT
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(test_conn_id_)).Times(1);
  tBTA_GATTC p_data;
  tBTA_GATTC_OPEN open_event_data;
  open_event_data.remote_bda = test_address_;
  open_event_data.conn_id = test_conn_id_;
  open_event_data.status = GATT_SUCCESS;
  open_event_data.transport = BT_TRANSPORT_LE;
  p_data.open = open_event_data;
  captured_gatt_callback_(BTA_GATTC_OPEN_EVT, &p_data);

  // GetServices should after BTA_GATTC_SEARCH_CMPL_EVT
  EXPECT_CALL(mock_gatt_interface_, GetServices(test_conn_id_))
          .WillOnce(Return(&services_to_return_));

  BtmDevice btm_device;
  btm_device.conn_params.peripheral_latency = 2;
  EXPECT_CALL(btm_interface_, FindDevice(_)).WillOnce(Return(&btm_device));

  tBTA_GATTC p_data2;
  tBTA_GATTC_SEARCH_CMPL search_cmpl_event_data;
  search_cmpl_event_data.conn_id = test_conn_id_;
  p_data2.search_cmpl = search_cmpl_event_data;
  captured_gatt_callback_(BTA_GATTC_SEARCH_CMPL_EVT, &p_data2);

  EXPECT_EQ(fake_osi_alarm_set_on_mloop_.interval_ms, (uint64_t)10000);

  EXPECT_CALL(mock_ras_client_callbacks_, OnRemoteDataTimeout(test_address_));

  fake_osi_alarm_expired(fake_osi_alarm_set_on_mloop_);

  DisconnectGatt();
}

TEST_F(RasClientTestNoInit, OnGattNotification_BeforeServiceDiscovery) {
  // AppRegister should be triggered when Initialize
  EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillOnce(testing::SaveArg<1>(&captured_gatt_callback_));
  GetRasClient()->Initialize();
  ASSERT_NE(captured_gatt_callback_, nullptr);
  GetRasClient()->RegisterCallbacks(&mock_ras_client_callbacks_);

  // Open should be triggered when connect
  EXPECT_CALL(mock_gatt_interface_, Open(_, test_address_, BTM_BLE_OPPORTUNISTIC)).Times(1);
  GetRasClient()->Connect(test_address_);

  // EXPECT the ServiceSearchRequest to be called *immediately*
  // by the BTA_GATTC_OPEN_EVT handler.
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(test_conn_id_)).Times(1);

  // 1. Simulate the BTA_GATTC_OPEN_EVT
  // This creates the RasTracker and triggers the ServiceSearchRequest.
  tBTA_GATTC p_data;
  tBTA_GATTC_OPEN open_event_data;
  open_event_data.remote_bda = test_address_;
  open_event_data.conn_id = test_conn_id_;
  open_event_data.status = GATT_SUCCESS;
  open_event_data.transport = BT_TRANSPORT_LE;
  p_data.open = open_event_data;
  captured_gatt_callback_(BTA_GATTC_OPEN_EVT, &p_data);

  // 2. Simulate the BTA_GATTC_NOTIF_EVT (the crash-causing event)
  // This notification arrives BEFORE service discovery is complete.
  // We expect OnRemoteData to NOT be called, and the client should not crash.
  EXPECT_CALL(mock_ras_client_callbacks_, OnRemoteData(test_address_, _)).Times(0);

  // Simulate the notification
  tBTA_GATTC p_data_notify;
  tBTA_GATTC_NOTIFY notify_data;
  notify_data.conn_id = test_conn_id_;
  notify_data.handle = GetCharacteristicHandle(kRasRealTimeRangingDataCharacteristic);
  notify_data.len = 5;
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04, 0x05};
  std::copy(data.begin(), data.end(), notify_data.value);
  p_data_notify.notify = notify_data;
  captured_gatt_callback_(BTA_GATTC_NOTIF_EVT, &p_data_notify);

  // 3. Now, simulate the (delayed) service discovery completion
  // This will assign tracker->service_
  EXPECT_CALL(mock_gatt_interface_, GetServices(test_conn_id_))
          .WillOnce(Return(&services_to_return_));
  EXPECT_CALL(mock_gatt_interface_, ConfigureMTU(test_conn_id_, _)).Times(1);

  tBTA_GATTC p_data2;
  tBTA_GATTC_SEARCH_CMPL search_cmpl_event_data;
  search_cmpl_event_data.conn_id = test_conn_id_;
  p_data2.search_cmpl = search_cmpl_event_data;
  captured_gatt_callback_(BTA_GATTC_SEARCH_CMPL_EVT, &p_data2);

  // 4. Clean up the connection
  EXPECT_CALL(mock_ras_client_callbacks_,
              OnDisconnected(test_address_, RasDisconnectReason::GATT_DISCONNECT))
          .Times(1);
  DisconnectGatt();
}

TEST_F(RasClientTest, OnConnIntervalUpdatedInvalid) {
  EXPECT_CALL(mock_ras_client_callbacks_, OnConnIntervalUpdated(test_address_, 0x1111))
          .Times(AtMost(1));
  // conn interval was not updated
  tBTA_GATTC p_data;
  tBTA_GATTC_CONN_UPDATE conn_update_data;
  conn_update_data.conn_id = test_conn_id_;
  conn_update_data.interval = 0x1111;
  p_data.conn_update = conn_update_data;
  captured_gatt_callback_(BTA_GATTC_CONN_UPDATE_EVT, &p_data);
  captured_gatt_callback_(BTA_GATTC_CONN_UPDATE_EVT, &p_data);
  // no ongoing measurement, skip
  p_data.conn_update.conn_id = test_conn_id_ + 1;
  captured_gatt_callback_(BTA_GATTC_CONN_UPDATE_EVT, &p_data);
}

TEST_F(RasClientTest, OnGattNotification_RealTimeRangingData) {
  uint16_t handle = GetCharacteristicHandle(kRasRealTimeRangingDataCharacteristic);
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04, 0x05};

  EXPECT_CALL(mock_ras_client_callbacks_, OnRemoteData(test_address_, data)).Times(1);
  SimulateNotification(handle, data);
}

TEST_F(RasClientTest, OnGattNotification_OnDemandRangingData) {
  uint16_t handle = GetCharacteristicHandle(kRasOnDemandDataCharacteristic);
  std::vector<uint8_t> data = {0x06, 0x07, 0x08, 0x09, 0x0A};

  EXPECT_CALL(mock_ras_client_callbacks_, OnRemoteData(test_address_, data)).Times(1);
  SimulateNotification(handle, data);
}

TEST_F(RasClientTest, OnRangingDataReady) {
  uint16_t handle = GetCharacteristicHandle(kRasRangingDataReadyCharacteristic);
  std::vector<uint8_t> data = {0x01, 0x02};  // Ranging counter 0x0201

  // Expect a write to the RAS Control Point to get the ranging data.
  EXPECT_CALL(mock_gatt_interface_,
              WriteCharValue(test_conn_id_, GetCharacteristicHandle(kRasControlPointCharacteristic),
                             _, _, _, _, _))
          .Times(1);
  SimulateNotification(handle, data);
}

TEST_F(RasClientTest, OnControlPointEvent_CompleteRangingDataResponse) {
  uint16_t handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
  std::vector<uint8_t> data = {static_cast<uint8_t>(EventCode::COMPLETE_RANGING_DATA_RESPONSE),
                               0x01, 0x02};  // Counter 0x0201

  // Expect a write to the RAS Control Point to ACK the ranging data.
  EXPECT_CALL(mock_gatt_interface_,
              WriteCharValue(test_conn_id_, GetCharacteristicHandle(kRasControlPointCharacteristic),
                             _, _, _, _, _))
          .Times(AtLeast(1));
  SimulateNotification(handle, data);
}

TEST_F(RasClientTest, OnControlPointEvent_ResponseCode) {
  uint16_t handle = GetCharacteristicHandle(kRasControlPointCharacteristic);
  std::vector<uint8_t> data = {static_cast<uint8_t>(EventCode::RESPONSE_CODE), 0x00};

  // No expectations for writes (just logging in the implementation).
  EXPECT_CALL(mock_gatt_interface_, WriteCharValue(_, _, _, _, _, _, _)).Times(0);

  SimulateNotification(handle, data);
}

}  // namespace bluetooth::ras
