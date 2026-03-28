/*
 * Copyright 2026 The Android Open Source Project
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

#include <fcntl.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include <atomic>
#include <thread>

#include "bta/include/bta_vap_server_api.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "bta/test/common/mock_csis_client.h"
#include "bta/vap/vap_server_types.h"
#include "bta_csis_api.h"
#include "btm_api_mock.h"
#include "common/message_loop_thread.h"
#include "hardware/bt_vap_server.h"
#include "stack/include/bt_types.h"

using namespace ::testing;
using namespace bluetooth::vap;

extern std::atomic<int> num_async_tasks;
extern bluetooth::common::MessageLoopThread message_loop_thread;
void init_message_loop_thread();
void cleanup_message_loop_thread();

namespace bluetooth::vap {

static uint16_t GetCharacteristicHandle(const bluetooth::Uuid& uuid) {
  if (uuid == ::vap::uuid::kVaNameCharacteristic) {
    return 0x0001;
  }
  if (uuid == ::vap::uuid::kVaUuidCharacteristic) {
    return 0x0003;
  }
  if (uuid == ::vap::uuid::kVasControlPointCharacteristic) {
    return 0x0005;
  }
  if (uuid == ::vap::uuid::kVaCcidCharacteristic) {
    return 0x0007;
  }
  if (uuid == ::vap::uuid::kVaSessionStateCharacteristic) {
    return 0x0009;
  }
  if (uuid == ::vap::uuid::kVaSupportedFeaturesCharacteristic) {
    return 0x000B;
  }
  return 0xFFFF;
}

static uint16_t GetDescriptorHandle(const bluetooth::Uuid& uuid) {
  return GetCharacteristicHandle(uuid) + 1;
}

static void UpdateTestServiceHandle(std::vector<btgatt_db_element_t>* service) {
  bluetooth::Uuid last_char_uuid;
  for (auto& element : *service) {
    if (element.type == BTGATT_DB_CHARACTERISTIC) {
      element.attribute_handle = GetCharacteristicHandle(element.uuid);
      last_char_uuid = element.uuid;
    } else if (element.type == BTGATT_DB_DESCRIPTOR) {
      if (element.uuid == ::vap::uuid::kClientCharacteristicConfiguration) {
        element.attribute_handle = GetDescriptorHandle(last_char_uuid);
      }
    }
  }
}

class MockVapServerCallbacks : public VapServerCallbacks {
public:
  MOCK_METHOD(void, OnInitialized, (), (override));
  MOCK_METHOD(void, OnStartVaSession, (const RawAddress& addr), (override));
  MOCK_METHOD(void, OnStopVaSession, (const RawAddress& addr), (override));
};

class VapServerTest : public Test {
protected:
  void SetUp() override {
    init_message_loop_thread();
    gatt::SetMockBtaGattServerInterface(&mock_gatt_server_interface_);
    bluetooth::manager::SetMockBtmInterface(&btm_interface_);
    MockCsisClient::SetMockInstanceForTesting(&mock_csis_client_);
    test_address_ = RawAddress::FromString("11:22:33:44:55:66").value();

    // GetVapServer() will create an instance if it's null
    EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
            .WillOnce(DoAll(testing::SaveArg<1>(&captured_gatt_callback_), Return(1)));

    EXPECT_CALL(mock_gatt_server_interface_, AddService(_, _))
            .WillOnce([](tGATT_IF /*server_if*/,
                         std::vector<btgatt_db_element_t>* service) -> tGATT_STATUS {
              UpdateTestServiceHandle(service);
              return GATT_SERVICE_STARTED;
            });
    EXPECT_CALL(mock_callbacks_, OnInitialized());

    GetVapServer()->Initialize(&mock_callbacks_);
    SyncOnMainLoop();
    ASSERT_NE(captured_gatt_callback_, nullptr);

    // Connect a client
    captured_gatt_callback_->p_conn_cb(1, test_address_, 1, true, GATT_CONN_OK, BT_TRANSPORT_LE);
    SyncOnMainLoop();
  }

  void TearDown() override {
    captured_gatt_callback_->p_conn_cb(1, test_address_, 1, false, GATT_CONN_OK, BT_TRANSPORT_LE);
    EXPECT_CALL(mock_gatt_server_interface_, AppDeregister(1));
    GetVapServer()->Cleanup();
    SyncOnMainLoop();
    gatt::SetMockBtaGattServerInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
    MockCsisClient::SetMockInstanceForTesting(nullptr);
    cleanup_message_loop_thread();
  }

  void SyncOnMainLoop() {
    if (message_loop_thread.IsRunningOnSameThread()) {
      return;
    }
    while (num_async_tasks > 0) {
      std::this_thread::yield();
    }
  }

  RawAddress test_address_;
  const stack::tGATT_CBACK* captured_gatt_callback_ = nullptr;
  gatt::MockBtaGattServerInterface mock_gatt_server_interface_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface_;
  NiceMock<MockCsisClient> mock_csis_client_;
  MockVapServerCallbacks mock_callbacks_;
};

TEST_F(VapServerTest, init) {
  GetVapServer()->SetVaName("TestVa");
  SyncOnMainLoop();
}

TEST_F(VapServerTest, init_start_stop_va_session) {
  GetVapServer()->SetVaName("MyVa");
  SyncOnMainLoop();

  // Enable notifications for Control Point to allow for multiple commands
  uint16_t cp_ccc_handle = GetDescriptorHandle(::vap::uuid::kVasControlPointCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_->p_req_cb->write_descriptor_cb(1, 1, test_address_, cp_ccc_handle, 0,
                                                         true, false, ccc_notification_value, 2);
  SyncOnMainLoop();

  uint16_t cp_handle = GetCharacteristicHandle(::vap::uuid::kVasControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(1, cp_handle, _, _)).Times(1);

  uint8_t init_req_value[] = {(uint8_t)::vap::CtpOpcode::INITIALIZE_VA_SESSION};
  captured_gatt_callback_->p_req_cb->write_characteristic_cb(
          1, 2, test_address_, cp_handle, 0, false, false, init_req_value, sizeof(init_req_value));
  SyncOnMainLoop();

  EXPECT_CALL(mock_callbacks_, OnStartVaSession(test_address_)).Times(1);

  uint8_t start_req_value[] = {(uint8_t)::vap::CtpOpcode::START_VA_SESSION};
  captured_gatt_callback_->p_req_cb->write_characteristic_cb(1, 3, test_address_, cp_handle, 0,
                                                             false, false, start_req_value,
                                                             sizeof(start_req_value));
  SyncOnMainLoop();

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(1, cp_handle, _, _)).Times(1);

  GetVapServer()->NotifyVaSessionStarted({test_address_}, true);
  SyncOnMainLoop();

  EXPECT_CALL(mock_callbacks_, OnStopVaSession(test_address_)).Times(1);

  uint8_t stop_req_value[] = {(uint8_t)::vap::CtpOpcode::STOP_VA_SESSION};
  captured_gatt_callback_->p_req_cb->write_characteristic_cb(
          1, 4, test_address_, cp_handle, 0, false, false, stop_req_value, sizeof(stop_req_value));
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_gatt_mtu_changed) {
  uint16_t new_mtu = 512;
  captured_gatt_callback_->p_req_cb->mtu_changed_cb(1, test_address_, new_mtu);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_characteristic_va_name) {
  std::string va_name = "TestVaName";
  GetVapServer()->SetVaName(va_name);
  SyncOnMainLoop();

  uint16_t handle = GetCharacteristicHandle(::vap::uuid::kVaNameCharacteristic);
  ASSERT_NE(0, handle);

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));

  captured_gatt_callback_->p_req_cb->read_characteristic_cb(1, 1, test_address_, handle, 0, false);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_descriptor_ccc) {
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));

  captured_gatt_callback_->p_req_cb->read_descriptor_cb(1, 1, test_address_, ccc_handle, 0, false);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, notify_va_session_stopped_success) {
  GetVapServer()->SetVaName("MyVa");
  SyncOnMainLoop();

  // Enable notifications for Session State
  uint16_t ss_ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));
  captured_gatt_callback_->p_req_cb->write_descriptor_cb(1, 1, test_address_, ss_ccc_handle, 0,
                                                         false, false, ccc_notification_value, 2);
  SyncOnMainLoop();

  std::vector<uint8_t> active_value = {(uint8_t)::vap::VaSessionState::VA_SESSION_ACTIVE};

  GetVapServer()->NotifyVaSessionStarted({test_address_}, true);
  SyncOnMainLoop();

  // Expect session state notification for stop
  std::vector<uint8_t> ready_value = {(uint8_t)::vap::VaSessionState::VA_SESSION_READY};
  GetVapServer()->NotifyVaSessionStopped({test_address_}, true);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, notify_vasession_stopped_session_not_active) {
  GetVapServer()->SetVaName("MyVa");
  SyncOnMainLoop();
  // Session state is not ACTIVE here.

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  GetVapServer()->NotifyVaSessionStopped({test_address_}, true);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, debug_dump) {
  // The session must be initialized before we can set all debug values
  uint16_t cp_handle = GetCharacteristicHandle(::vap::uuid::kVasControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);
  uint8_t init_req_value[] = {(uint8_t)::vap::CtpOpcode::INITIALIZE_VA_SESSION};
  captured_gatt_callback_->p_req_cb->write_characteristic_cb(
          1, 1, test_address_, cp_handle, 0, false, false, init_req_value, sizeof(init_req_value));
  SyncOnMainLoop();

  // Setup some state
  GetVapServer()->SetVaName("MyVa");
  GetVapServer()->SetCcid(12);
  SyncOnMainLoop();

  // Use a pipe to capture output
  int fds[2];
  ASSERT_EQ(0, pipe(fds));
  fcntl(fds[0], F_SETFL, O_NONBLOCK);

  GetVapServer()->DebugDump(fds[1]);
  SyncOnMainLoop();
  close(fds[1]);

  char buf[1024];
  ssize_t len = read(fds[0], buf, sizeof(buf) - 1);
  close(fds[0]);
  ASSERT_GT(len, 0);
  buf[len] = '\0';

  std::string output(buf);
  EXPECT_THAT(output, HasSubstr("VAP Server Manager:"));
  EXPECT_THAT(output, HasSubstr("VA Name: MyVa"));
  EXPECT_THAT(output, HasSubstr("VAP CCID: 12"));
  EXPECT_THAT(output, HasSubstr("Remote Client: 11:22:33:44:55:66"));
}

TEST_F(VapServerTest, on_write_descriptor_unknown_client) {
  RawAddress unknown_address = RawAddress::FromString("00:11:22:33:44:55").value();
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_value[] = {0x01, 0x00};  // Notification enabled

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_ILLEGAL_PARAMETER, _));
  captured_gatt_callback_->p_req_cb->write_descriptor_cb(2, 1, unknown_address, ccc_handle, 0,
                                                         false, false, ccc_value, 2);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_write_descriptor_ccc_success) {
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaCcidCharacteristic);
  uint8_t ccc_value[] = {0x01, 0x00};  // Notification enabled

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_->p_req_cb->write_descriptor_cb(1, 1, test_address_, ccc_handle, 0, false,
                                                         false, ccc_value, 2);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_descriptor_ccc_val) {
  // First, write a value to the CCC descriptor
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_->p_req_cb->write_descriptor_cb(1, 1, test_address_, ccc_handle, 0, true,
                                                         false, ccc_notification_value, 2);
  SyncOnMainLoop();

  // Now, read it back
  std::unique_ptr<tGATTS_RSP> captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 2, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS,
                               std::unique_ptr<tGATTS_RSP> p_msg) { captured_rsp.swap(p_msg); }));

  captured_gatt_callback_->p_req_cb->read_descriptor_cb(1, 2, test_address_, ccc_handle, 0, false);
  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  SyncOnMainLoop();
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0001);
}

TEST_F(VapServerTest, on_read_descriptor_unknown_client) {
  RawAddress unknown_address = RawAddress::FromString("00:11:22:33:44:55").value();
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);

  std::unique_ptr<tGATTS_RSP> captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS,
                               std::unique_ptr<tGATTS_RSP> p_msg) { captured_rsp.swap(p_msg); }));

  captured_gatt_callback_->p_req_cb->read_descriptor_cb(2, 1, unknown_address, ccc_handle, 0,
                                                        false);
  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  SyncOnMainLoop();
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0000);
}

}  // namespace bluetooth::vap
