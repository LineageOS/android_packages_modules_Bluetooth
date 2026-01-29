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
#include "bta/test/common/bta_gatt_api_mock.h"
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
  return 0xFFFF;
}

static uint16_t GetDescriptorHandle(const bluetooth::Uuid& uuid) {
  return GetCharacteristicHandle(uuid) + 1;
}

static void UpdateTestServiceHandle(std::vector<btgatt_db_element_t>& service) {
  bluetooth::Uuid last_char_uuid;
  for (auto& element : service) {
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
            .WillOnce(SaveArg<1>(&captured_gatt_callback_));
    GetVapServer()->Initialize(&mock_callbacks_);
    SyncOnMainLoop();
    ASSERT_NE(captured_gatt_callback_, nullptr);

    tGATT_IF captured_server_if;
    std::vector<btgatt_db_element_t> captured_service;
    BTA_GATTS_AddServiceCb captured_cb;
    EXPECT_CALL(mock_gatt_server_interface_, AddService(_, _, _))
            .WillOnce(DoAll(SaveArg<0>(&captured_server_if), SaveArg<1>(&captured_service),
                            testing::WithArg<2>([&](auto arg) { captured_cb = std::move(arg); })));

    tBTA_GATTS gatts_cb_data;
    gatts_cb_data.reg_oper.status = GATT_SUCCESS;
    gatts_cb_data.reg_oper.server_if = 1;
    captured_gatt_callback_(BTA_GATTS_REG_EVT, &gatts_cb_data);
    SyncOnMainLoop();

    EXPECT_CALL(mock_callbacks_, OnInitialized());
    UpdateTestServiceHandle(captured_service);
    std::move(captured_cb).Run(GATT_SUCCESS, captured_server_if, std::move(captured_service));
    SyncOnMainLoop();

    // Connect a client
    tBTA_GATTS p_data_conn;
    p_data_conn.conn = {.remote_bda = test_address_, .conn_id = 1, .transport = BT_TRANSPORT_LE};
    captured_gatt_callback_(BTA_GATTS_CONNECT_EVT, &p_data_conn);
    SyncOnMainLoop();
  }

  void TearDown() override {
    tBTA_GATTS p_data_conn;
    p_data_conn.conn = {.remote_bda = test_address_, .conn_id = 1, .transport = BT_TRANSPORT_LE};
    captured_gatt_callback_(BTA_GATTS_DISCONNECT_EVT, &p_data_conn);
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
  tBTA_GATTS_CBACK* captured_gatt_callback_ = nullptr;
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
  auto* p_data_write_cp_ccc_req_data = new tGATTS_DATA{
          .write_req = {
                  .handle = cp_ccc_handle,
                  .len = 2,
                  .value = {ccc_notification_value[0], ccc_notification_value[1]},
                  .need_rsp = true,
          }};

  auto* p_data_write_cp_ccc = new tBTA_GATTS;
  p_data_write_cp_ccc->req_data = {.remote_bda = test_address_,
                                   .trans_id = 1,
                                   .conn_id = 1,
                                   .p_data = p_data_write_cp_ccc_req_data};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, p_data_write_cp_ccc);
  SyncOnMainLoop();

  uint16_t cp_handle = GetCharacteristicHandle(::vap::uuid::kVasControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(1, cp_handle, _, _)).Times(1);

  auto* p_data_write_init_req_data = new tGATTS_DATA{
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vap::CtpOpcode::INITIALIZE_VA_SESSION},
                        .need_rsp = false}};
  auto* p_data_write_init = new tBTA_GATTS;
  p_data_write_init->req_data = {.remote_bda = test_address_,
                                 .trans_id = 2,
                                 .conn_id = 1,
                                 .p_data = p_data_write_init_req_data};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, p_data_write_init);
  SyncOnMainLoop();

  EXPECT_CALL(mock_callbacks_, OnStartVaSession(test_address_)).Times(1);

  auto* p_data_write_req_data = new tGATTS_DATA{
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vap::CtpOpcode::START_VA_SESSION},
                        .need_rsp = false}};
  auto* p_data_write = new tBTA_GATTS;
  p_data_write->req_data = {.remote_bda = test_address_,
                            .trans_id = 3,
                            .conn_id = 1,
                            .p_data = p_data_write_req_data};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, p_data_write);
  SyncOnMainLoop();

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(1, cp_handle, _, _)).Times(1);

  GetVapServer()->NotifyVaSessionStarted({test_address_}, true);
  SyncOnMainLoop();

  EXPECT_CALL(mock_callbacks_, OnStopVaSession(test_address_)).Times(1);

  auto* p_data_write_stop_req_data = new tGATTS_DATA{
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vap::CtpOpcode::STOP_VA_SESSION},
                        .need_rsp = false}};
  auto* p_data_write_stop = new tBTA_GATTS;
  p_data_write_stop->req_data = {.remote_bda = test_address_,
                                 .trans_id = 4,
                                 .conn_id = 1,
                                 .p_data = p_data_write_stop_req_data};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, p_data_write_stop);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_gatt_mtu_changed) {
  uint16_t new_mtu = 512;
  auto* p_data_mtu_data = new tGATTS_DATA{.mtu = new_mtu};
  auto* p_data_mtu = new tBTA_GATTS;
  p_data_mtu->req_data = {.remote_bda = test_address_, .p_data = p_data_mtu_data};
  captured_gatt_callback_(BTA_GATTS_MTU_EVT, p_data_mtu);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_characteristic_va_name) {
  std::string va_name = "TestVaName";
  GetVapServer()->SetVaName(va_name);
  SyncOnMainLoop();

  uint16_t handle = GetCharacteristicHandle(::vap::uuid::kVaNameCharacteristic);
  ASSERT_NE(0, handle);

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));

  auto* p_data_read_req_data = new tGATTS_DATA{.read_req = {.handle = handle, .offset = 0}};
  auto* p_data_read = new tBTA_GATTS;
  p_data_read->req_data = {.remote_bda = test_address_,
                           .trans_id = 1,
                           .conn_id = 1,
                           .p_data = p_data_read_req_data};
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, p_data_read);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_descriptor_ccc) {
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));

  auto* p_data_read_req_data = new tGATTS_DATA{.read_req = {.handle = ccc_handle, .offset = 0}};
  auto* p_data_read = new tBTA_GATTS;
  p_data_read->req_data = {.remote_bda = test_address_,
                           .trans_id = 1,
                           .conn_id = 1,
                           .p_data = p_data_read_req_data};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, p_data_read);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, notify_va_session_stopped_success) {
  GetVapServer()->SetVaName("MyVa");
  SyncOnMainLoop();

  // Enable notifications for Session State
  uint16_t ss_ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  auto* p_data_write_ss_ccc_req_data = new tGATTS_DATA{
          .write_req = {.handle = ss_ccc_handle,
                        .len = 2,
                        .value = {ccc_notification_value[0], ccc_notification_value[1]},
                        .need_rsp = false}};
  auto* p_data_write_ss_ccc = new tBTA_GATTS;
  p_data_write_ss_ccc->req_data = {.remote_bda = test_address_,
                                   .trans_id = 1,
                                   .conn_id = 1,
                                   .p_data = p_data_write_ss_ccc_req_data};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, p_data_write_ss_ccc);
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
  auto* p_data_write_init_req_data = new tGATTS_DATA{
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vap::CtpOpcode::INITIALIZE_VA_SESSION},
                        .need_rsp = false}};
  auto* p_data_write_init = new tBTA_GATTS;
  p_data_write_init->req_data = {.remote_bda = test_address_,
                                 .trans_id = 1,
                                 .conn_id = 1,
                                 .p_data = p_data_write_init_req_data};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, p_data_write_init);
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

  auto* p_data_write_req_data = new tGATTS_DATA{.write_req = {.handle = ccc_handle,
                                                     .len = 2,
                                                     .value = {ccc_value[0], ccc_value[1]},
                                                     .need_rsp = false}};

  auto* p_data_write = new tBTA_GATTS;
  p_data_write->req_data = {.remote_bda = unknown_address,
                            .trans_id = 1,
                            .conn_id = 2,  // different conn_id
                            .p_data = p_data_write_req_data};

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_ILLEGAL_PARAMETER, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, p_data_write);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_write_descriptor_ccc_success) {
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaCcidCharacteristic);
  uint8_t ccc_value[] = {0x01, 0x00};  // Notification enabled

  auto* p_data_write_req_data = new tGATTS_DATA{.write_req = {.handle = ccc_handle,
                                                     .len = 2,
                                                     .value = {ccc_value[0], ccc_value[1]},
                                                     .need_rsp = false}};

  auto* p_data_write = new tBTA_GATTS;
  p_data_write->req_data = {.remote_bda = test_address_,
                            .trans_id = 1,
                            .conn_id = 1,
                            .p_data = p_data_write_req_data};

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, p_data_write);
  SyncOnMainLoop();
}

TEST_F(VapServerTest, on_read_descriptor_ccc_val) {
  // First, write a value to the CCC descriptor
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  auto* p_data_write_ccc_req_data = new tGATTS_DATA{
          .write_req = {.handle = ccc_handle,
                        .len = 2,
                        .value = {ccc_notification_value[0], ccc_notification_value[1]},
                        .need_rsp = true}};

  auto* p_data_write_ccc = new tBTA_GATTS;
  p_data_write_ccc->req_data = {.remote_bda = test_address_,
                                .trans_id = 1,
                                .conn_id = 1,
                                .p_data = p_data_write_ccc_req_data};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, p_data_write_ccc);
  SyncOnMainLoop();

  // Now, read it back
  tGATTS_RSP* captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 2, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            captured_rsp = new tGATTS_RSP();
            *captured_rsp = *p_msg;
          }));

  auto* p_data_read_req_data = new tGATTS_DATA{.read_req = {.handle = ccc_handle, .offset = 0}};
  auto* p_data_read = new tBTA_GATTS;
  p_data_read->req_data = {.remote_bda = test_address_,
                           .trans_id = 2,
                           .conn_id = 1,
                           .p_data = p_data_read_req_data};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, p_data_read);
  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  SyncOnMainLoop();
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0001);
  delete captured_rsp;
}

TEST_F(VapServerTest, on_read_descriptor_unknown_client) {
  RawAddress unknown_address = RawAddress::FromString("00:11:22:33:44:55").value();
  uint16_t ccc_handle = GetDescriptorHandle(::vap::uuid::kVaSessionStateCharacteristic);

  tGATTS_RSP* captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            captured_rsp = new tGATTS_RSP();
            *captured_rsp = *p_msg;
          }));

  auto* p_data_read_req_data = new tGATTS_DATA{.read_req = {.handle = ccc_handle, .offset = 0}};
  auto* p_data_read = new tBTA_GATTS;
  p_data_read->req_data = {.remote_bda = unknown_address,
                           .trans_id = 1,
                           .conn_id = 2,
                           .p_data = p_data_read_req_data};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, p_data_read);
  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  SyncOnMainLoop();
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0000);
  delete captured_rsp;
}

}  // namespace bluetooth::vap
