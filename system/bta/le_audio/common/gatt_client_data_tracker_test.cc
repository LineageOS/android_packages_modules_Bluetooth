/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "bta/le_audio/common/gatt_client_data_tracker.h"

#include <android/log.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta_gatt_api_mock.h"
#include "btm_api_mock.h"
#include "stack/include/bt_types.h"

namespace bluetooth {

using ::testing::_;
using ::testing::DoAll;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SetArgPointee;

static RawAddress GetTestAddress(int index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes = {0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)};
  return RawAddress(bytes);
}

class GattClientDataTrackerTest : public ::testing::Test {
public:
  struct TestData {
    // Nothing here
  };

  GattClientDataTracker<TestData> tracker_;

  NiceMock<gatt::MockBtaGattServerInterface> gatt_server_interface_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface_;

  void SetUp(void) override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com_android_bluetooth_flags_reset_flags();

    // Use peripheral role by default
    get_btm_client_interface().link_policy.BTM_GetRole = [](const RawAddress& /* remote_bd_addr */,
                                                            tBT_TRANSPORT /* transport */,
                                                            tHCI_ROLE* p_role) -> tBTM_STATUS {
      *p_role = HCI_ROLE_PERIPHERAL;
      return tBTM_STATUS::BTM_SUCCESS;
    };

    gatt::SetMockBtaGattServerInterface(&gatt_server_interface_);
  }

  void TearDown(void) override {}
};

TEST_F(GattClientDataTrackerTest, ConnectDisconnect) {
  auto test_addr1 = GetTestAddress(1);
  auto test_addr2 = GetTestAddress(2);

  // Connect only the first device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), 0x0010);
  ASSERT_EQ(test_addr1, tracker_.FindConnectedDevice(0x0010)->pseudo_addr);
  ASSERT_EQ(tracker_.FindConnectionId(test_addr2), GATT_INVALID_CONN_ID);

  // Connect the second device
  cb_data.conn.conn_id = 0x0020;
  cb_data.conn.remote_bda = test_addr2;

  ASSERT_EQ(tracker_.FindConnectionId(test_addr2), GATT_INVALID_CONN_ID);
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), 0x0010);
  ASSERT_EQ(test_addr1, tracker_.FindConnectedDevice(0x0010)->pseudo_addr);
  ASSERT_EQ(tracker_.FindConnectionId(test_addr2), 0x0020);
  ASSERT_EQ(test_addr2, tracker_.FindConnectedDevice(0x0020)->pseudo_addr);

  // Disconnect the first one
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.remote_bda = test_addr1;
  tracker_.OnGattDisconnectedEventHandler(&cb_data);

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(nullptr, tracker_.FindConnectedDevice(0x0010));
  ASSERT_EQ(tracker_.FindConnectionId(test_addr2), 0x0020);
  ASSERT_EQ(test_addr2, tracker_.FindConnectedDevice(0x0020)->pseudo_addr);

  // Disconnect the second device
  cb_data.conn.conn_id = 0x0020;
  cb_data.conn.remote_bda = test_addr2;
  tracker_.OnGattDisconnectedEventHandler(&cb_data);

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(nullptr, tracker_.FindConnectedDevice(0x0010));
  ASSERT_EQ(tracker_.FindConnectionId(test_addr2), GATT_INVALID_CONN_ID);
  ASSERT_EQ(nullptr, tracker_.FindConnectedDevice(0x0020));
}

TEST_F(GattClientDataTrackerTest, DisconnectNotConnected) {
  auto test_addr1 = GetTestAddress(1);

  // Call the disconnected callback which should be ignored in the current tracker state
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  tracker_.OnGattDisconnectedEventHandler(&cb_data);

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(nullptr, tracker_.FindConnectedDevice(0x0010));
}

TEST_F(GattClientDataTrackerTest, DoubleConnect) {
  auto test_addr1 = GetTestAddress(1);

  // Call the connected callback which should be ignored in the current tracker state
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), 0x0010);
  ASSERT_EQ(test_addr1, tracker_.FindConnectedDevice(0x0010)->pseudo_addr);

  // Make sure one disconnect event is enough
  tracker_.OnGattDisconnectedEventHandler(&cb_data);
  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
}

TEST_F(GattClientDataTrackerTest, CheckUserData) {
  auto test_addr1 = GetTestAddress(1);

  // Connect only the first device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), 0x0010);

  auto device_data = tracker_.FindConnectedDevice(0x0010);
  ASSERT_NE(device_data.get(), nullptr);
  ASSERT_EQ(test_addr1, device_data->pseudo_addr);
  ASSERT_FALSE(device_data->is_stale);
  ASSERT_EQ(device_data.use_count(), 2);

  // Disconnect the first one
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.remote_bda = test_addr1;
  tracker_.OnGattDisconnectedEventHandler(&cb_data);

  // Check if not able to find the disconnected device data
  auto disconnected_device_data = tracker_.FindConnectedDevice(0x0010);
  ASSERT_EQ(disconnected_device_data.get(), nullptr);

  // Verify that we still hold one (and now only) istance of the old data, its just stale since the
  // device got disconnected
  ASSERT_TRUE(device_data->is_stale);
  ASSERT_EQ(device_data.use_count(), 1);
}

TEST_F(GattClientDataTrackerTest, RejectCentralRole) {
  auto test_addr1 = GetTestAddress(1);

  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  // Mock BTM_GetRole to return HCI_ROLE_CENTRAL
  get_btm_client_interface().link_policy.BTM_GetRole = [](const RawAddress& /* remote_bd_addr */,
                                                          tBT_TRANSPORT /* transport */,
                                                          tHCI_ROLE* p_role) -> tBTM_STATUS {
    *p_role = HCI_ROLE_CENTRAL;
    return tBTM_STATUS::BTM_SUCCESS;
  };

  // Expect BTA_GATTS_Close to be called
  EXPECT_CALL(gatt_server_interface_, Close(cb_data.conn.conn_id)).Times(1);

  // Call the handler
  auto device_entry = tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // Verify the device was not added
  ASSERT_EQ(device_entry, nullptr);
  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(tracker_.FindConnectedDevice(cb_data.conn.conn_id), nullptr);
}

TEST_F(GattClientDataTrackerTest, RejectBtmGetRoleFailure) {
  auto test_addr1 = GetTestAddress(1);

  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = 0x0010;
  cb_data.conn.server_if = 0xC0;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  cb_data.conn.remote_bda = test_addr1;

  // Mock BTM_GetRole to return failure
  get_btm_client_interface().link_policy.BTM_GetRole =
          [](const RawAddress& /* remote_bd_addr */, tBT_TRANSPORT /* transport */,
             tHCI_ROLE* /* p_role */) -> tBTM_STATUS { return tBTM_STATUS::BTM_WRONG_MODE; };

  // Expect BTA_GATTS_Close to be called
  EXPECT_CALL(gatt_server_interface_, Close(cb_data.conn.conn_id)).Times(1);

  // Call the handler
  auto device_entry = tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // Verify the device was not added
  ASSERT_EQ(device_entry, nullptr);
  ASSERT_EQ(tracker_.FindConnectionId(test_addr1), GATT_INVALID_CONN_ID);
  ASSERT_EQ(tracker_.FindConnectedDevice(cb_data.conn.conn_id), nullptr);
}

TEST_F(GattClientDataTrackerTest, OnGattWriteDescriptor) {
  auto test_addr = GetTestAddress(1);
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;

  // Connect the device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = conn_id;
  cb_data.conn.remote_bda = test_addr;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // Prepare write request
  tGATTS_DATA gatt_data;
  gatt_data.write_req.handle = handle;
  gatt_data.write_req.offset = 0;
  gatt_data.write_req.len = 2;
  gatt_data.write_req.value[0] = 0xAB;
  gatt_data.write_req.value[1] = 0xCD;
  gatt_data.write_req.need_rsp = true;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &gatt_data;

  // Expect response
  EXPECT_CALL(gatt_server_interface_, SendRsp(conn_id, trans_id, GATT_SUCCESS, _)).Times(1);

  // Call handler
  tracker_.OnGattWriteDescriptor(&cb_data);

  // Verify data is written
  auto device = tracker_.FindConnectedDevice(conn_id);
  ASSERT_NE(device, nullptr);
  ASSERT_EQ(device->GetDescriptorValueAsU16(handle), 0xCDAB);
}

TEST_F(GattClientDataTrackerTest, OnGattWriteDescriptorNoRsp) {
  auto test_addr = GetTestAddress(1);
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;

  // Connect the device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = conn_id;
  cb_data.conn.remote_bda = test_addr;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // Prepare write request
  tGATTS_DATA gatt_data;
  gatt_data.write_req.handle = handle;
  gatt_data.write_req.offset = 0;
  gatt_data.write_req.len = 2;
  gatt_data.write_req.value[0] = 0xAB;
  gatt_data.write_req.value[1] = 0xCD;
  gatt_data.write_req.need_rsp = false;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &gatt_data;

  // Expect NO response
  EXPECT_CALL(gatt_server_interface_, SendRsp(_, _, _, _)).Times(0);

  // Call handler
  tracker_.OnGattWriteDescriptor(&cb_data);

  // Verify data is written
  auto device = tracker_.FindConnectedDevice(conn_id);
  ASSERT_NE(device, nullptr);
  ASSERT_EQ(device->GetDescriptorValueAsU16(handle), 0xCDAB);
}

TEST_F(GattClientDataTrackerTest, OnGattWriteDescriptorDisconnected) {
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;

  // Prepare write request for a non-existent connection
  tBTA_GATTS cb_data;
  tGATTS_DATA gatt_data;
  gatt_data.write_req.handle = handle;
  gatt_data.write_req.offset = 0;
  gatt_data.write_req.len = 2;
  gatt_data.write_req.value[0] = 0xAB;
  gatt_data.write_req.value[1] = 0xCD;
  gatt_data.write_req.need_rsp = true;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &gatt_data;

  // Expect error response
  EXPECT_CALL(gatt_server_interface_, SendRsp(conn_id, trans_id, GATT_INTERNAL_ERROR, _)).Times(1);

  // Call handler
  tracker_.OnGattWriteDescriptor(&cb_data);
}

TEST_F(GattClientDataTrackerTest, OnGattReadDescriptor) {
  auto test_addr = GetTestAddress(1);
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;
  uint16_t value_to_write = 0xABCD;

  // Connect the device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = conn_id;
  cb_data.conn.remote_bda = test_addr;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // First, write a value to the descriptor cache
  tGATTS_DATA write_gatt_data;
  write_gatt_data.write_req.handle = handle;
  write_gatt_data.write_req.offset = 0;
  write_gatt_data.write_req.len = sizeof(value_to_write);
  uint8_t* p = write_gatt_data.write_req.value;
  UINT16_TO_STREAM(p, value_to_write);
  write_gatt_data.write_req.need_rsp = false;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &write_gatt_data;
  tracker_.OnGattWriteDescriptor(&cb_data);

  // Now, prepare read request
  tGATTS_DATA read_gatt_data;
  read_gatt_data.read_req.handle = handle;
  read_gatt_data.read_req.offset = 0;

  cb_data.req_data.p_data = &read_gatt_data;

  // Expect response with the correct value
  EXPECT_CALL(gatt_server_interface_, SendRsp(conn_id, trans_id, GATT_SUCCESS, _))
          .WillOnce([value_to_write](uint16_t, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, sizeof(value_to_write));
            uint16_t read_value = 0;
            const uint8_t* p_read = p_msg->attr_value.value;
            STREAM_TO_UINT16(read_value, p_read);
            ASSERT_EQ(read_value, value_to_write);
          });

  // Call handler
  tracker_.OnGattReadDescriptor(&cb_data);
}

TEST_F(GattClientDataTrackerTest, OnGattReadDescriptorUnknownHandle) {
  auto test_addr = GetTestAddress(1);
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;

  // Connect the device
  tBTA_GATTS cb_data;
  cb_data.conn.conn_id = conn_id;
  cb_data.conn.remote_bda = test_addr;
  cb_data.conn.transport = BT_TRANSPORT_LE;
  tracker_.OnGattConnectedEventHandler(&cb_data, TestData());

  // Prepare read request for a handle that was not written to
  tGATTS_DATA read_gatt_data;
  read_gatt_data.read_req.handle = handle;
  read_gatt_data.read_req.offset = 0;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &read_gatt_data;

  // Expect response with a default value (0x0000)
  EXPECT_CALL(gatt_server_interface_, SendRsp(conn_id, trans_id, GATT_SUCCESS, _))
          .WillOnce([](uint16_t, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            ASSERT_EQ(p_msg->attr_value.len, 2);
            ASSERT_EQ(p_msg->attr_value.value[0], 0);
            ASSERT_EQ(p_msg->attr_value.value[1], 0);
          });

  // Call handler
  tracker_.OnGattReadDescriptor(&cb_data);
}

TEST_F(GattClientDataTrackerTest, OnGattReadDescriptorDisconnected) {
  uint16_t conn_id = 0x0010;
  uint16_t handle = 0x1234;
  uint32_t trans_id = 1;

  // Prepare read request for a non-existent connection
  tBTA_GATTS cb_data;
  tGATTS_DATA gatt_data;
  gatt_data.read_req.handle = handle;
  gatt_data.read_req.offset = 0;

  cb_data.req_data.conn_id = conn_id;
  cb_data.req_data.trans_id = trans_id;
  cb_data.req_data.p_data = &gatt_data;

  // Expect error response
  EXPECT_CALL(gatt_server_interface_, SendRsp(conn_id, trans_id, GATT_INTERNAL_ERROR, _)).Times(1);

  // Call handler
  tracker_.OnGattReadDescriptor(&cb_data);
}

}  // namespace bluetooth
