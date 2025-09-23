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

#include <fcntl.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include <atomic>

#include "bta/include/bta_vaps_server_api.h"
#include "bta/test/common/bta_gatt_api_mock.h"
#include "bta/vaps/vaps_server_types.h"
#include "btm_api_mock.h"
#include "common/message_loop_thread.h"
#include "hardware/bt_vaps_server.h"
#include "stack/include/bt_types.h"

using namespace ::testing;
using namespace bluetooth::vaps;

extern std::atomic<int> num_async_tasks;
extern bluetooth::common::MessageLoopThread message_loop_thread;
void init_message_loop_thread();
void cleanup_message_loop_thread();

namespace bluetooth::vaps {

static uint16_t GetCharacteristicHandle(const bluetooth::Uuid& uuid) {
  if (uuid == ::vaps::uuid::kVaeNameCharacteristic) {
    return 0x0001;
  }
  if (uuid == ::vaps::uuid::kVaeUuidCharacteristic) {
    return 0x0003;
  }
  if (uuid == ::vaps::uuid::kVaeControlPointCharacteristic) {
    return 0x0005;
  }
  if (uuid == ::vaps::uuid::kVaeCcidCharacteristic) {
    return 0x0007;
  }
  if (uuid == ::vaps::uuid::kVaSessionStateCharacteristic) {
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
      if (element.uuid == ::vaps::uuid::kClientCharacteristicConfiguration) {
        element.attribute_handle = GetDescriptorHandle(last_char_uuid);
      }
    }
  }
}

class MockVapsServerCallbacks : public VapsServerCallbacks {
public:
  MOCK_METHOD(void, OnInitialized, (), (override));
  MOCK_METHOD(void, OnStartVaSession, (const RawAddress& addr), (override));
  MOCK_METHOD(void, OnStopVaSession, (const RawAddress& addr), (override));
};

class VapsServerTest : public Test {
protected:
  void SetUp() override {
    init_message_loop_thread();
    gatt::SetMockBtaGattServerInterface(&mock_gatt_server_interface_);
    bluetooth::manager::SetMockBtmInterface(&btm_interface_);
    test_address_ = RawAddress::FromString("11:22:33:44:55:66").value();

    // GetVapsServer() will create an instance if it's null
    EXPECT_CALL(mock_gatt_server_interface_, AppRegister(_, _, _))
            .WillOnce(SaveArg<1>(&captured_gatt_callback_));
    GetVapsServer()->Initialize(&mock_callbacks_);
    SyncOnMainLoop();
    ASSERT_NE(captured_gatt_callback_, nullptr);

    tGATT_IF captured_server_if;
    std::vector<btgatt_db_element_t> captured_service;
    BTA_GATTS_AddServiceCb captured_cb;
    EXPECT_CALL(mock_gatt_server_interface_, AddService(_, _, _))
            .WillOnce(DoAll(SaveArg<0>(&captured_server_if), SaveArg<1>(&captured_service),
                            SaveArg<2>(&captured_cb), Return()));

    tBTA_GATTS gatts_cb_data;
    gatts_cb_data.reg_oper.status = GATT_SUCCESS;
    gatts_cb_data.reg_oper.server_if = 1;
    captured_gatt_callback_(BTA_GATTS_REG_EVT, &gatts_cb_data);

    EXPECT_CALL(mock_callbacks_, OnInitialized());
    UpdateTestServiceHandle(captured_service);
    captured_cb.Run(GATT_SUCCESS, captured_server_if, std::move(captured_service));
    SyncOnMainLoop();

    // Connect a client
    tBTA_GATTS p_data_conn;
    p_data_conn.conn = {.remote_bda = test_address_, .conn_id = 1, .transport = BT_TRANSPORT_LE};
    captured_gatt_callback_(BTA_GATTS_CONNECT_EVT, &p_data_conn);
  }

  void TearDown() override {
    tBTA_GATTS p_data_conn;
    p_data_conn.conn = {.remote_bda = test_address_, .conn_id = 1, .transport = BT_TRANSPORT_LE};
    captured_gatt_callback_(BTA_GATTS_DISCONNECT_EVT, &p_data_conn);
    GetVapsServer()->Cleanup();
    SyncOnMainLoop();
    gatt::SetMockBtaGattServerInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
    cleanup_message_loop_thread();
  }

  void SyncOnMainLoop() {
    if (message_loop_thread.IsRunningOnSameThread()) {
      return;
    }

    // Create synchronization objects unique to this call.
    std::promise<void> promise;
    std::future<void> future = promise.get_future();

    // Queue the signaling task onto the thread's message queue.
    if (!message_loop_thread.DoInThread(
                base::BindOnce([](std::promise<void> p) { p.set_value(); }, std::move(promise)))) {
      bluetooth::log::error("failed to post sync task to main thread!");
      return;
    }

    // Block the calling thread (the test thread) until the signal arrives.
    future.wait();
  }

  RawAddress test_address_;
  tBTA_GATTS_CBACK* captured_gatt_callback_ = nullptr;
  gatt::MockBtaGattServerInterface mock_gatt_server_interface_;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface_;
  MockVapsServerCallbacks mock_callbacks_;
};

TEST_F(VapsServerTest, Initialization) {
  GetVapsServer()->SetVaeName("TestVae");
  SyncOnMainLoop();
}

TEST_F(VapsServerTest, StartVaSession) {
  GetVapsServer()->SetVaeName("MyVae");
  SyncOnMainLoop();

  // Enable notifications for Control Point to allow for multiple commands
  uint16_t cp_ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  tGATTS_DATA p_data_write_cp_ccc_req_data ={
    .write_req={
      .handle = cp_ccc_handle,
      .len = 2,
      .value = {ccc_notification_value[0], ccc_notification_value[1]},
      .need_rsp = true
    }
  };

  tBTA_GATTS p_data_write_cp_ccc = {.req_data = {.remote_bda = test_address_,
                                                 .trans_id = 1,
                                                 .conn_id = 1,
                                                 .p_data = &p_data_write_cp_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_cp_ccc);

  uint16_t cp_handle = GetCharacteristicHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);

  // The session must be initialized before it can be started.
  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(AnyNumber());
  tGATTS_DATA p_data_write_init_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::INITIALIZE_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write_init = {.req_data = {.remote_bda = test_address_,
                                               .trans_id = 2,
                                               .conn_id = 1,
                                               .p_data = &p_data_write_init_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write_init);

  EXPECT_CALL(mock_callbacks_, OnStartVaSession(test_address_)).Times(1);

  tGATTS_DATA p_data_write_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::START_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write = {.req_data = {.remote_bda = test_address_,
                                          .trans_id = 3,
                                          .conn_id = 1,
                                          .p_data = &p_data_write_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write);
}

TEST_F(VapsServerTest, StopVaSession) {
  GetVapsServer()->SetVaeName("MyVae");

  // Enable notifications for Control Point to allow for multiple commands
  uint16_t cp_ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  tGATTS_DATA p_data_write_cp_ccc_req_data = {.write_req = {
                                                      .handle = cp_ccc_handle,
                                                      .len = 2,
                                                      .value = {ccc_notification_value[0], ccc_notification_value[1]},
                                                      .need_rsp = true,
                                              }};


  tBTA_GATTS p_data_write_cp_ccc = {.req_data = {.remote_bda = test_address_,
                                                 .trans_id = 1,
                                                 .conn_id = 1,
                                                 .p_data = &p_data_write_cp_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_cp_ccc);

  uint16_t cp_handle = GetCharacteristicHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);

  // The session must be initialized before it can be started.
  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(AnyNumber());
  tGATTS_DATA p_data_write_init_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::INITIALIZE_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write_init = {.req_data = {.remote_bda = test_address_,
                                               .trans_id = 2,
                                               .conn_id = 1,
                                               .p_data = &p_data_write_init_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write_init);

  EXPECT_CALL(mock_callbacks_, OnStartVaSession(test_address_)).Times(1);

  tGATTS_DATA p_data_write_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::START_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write = {.req_data = {.remote_bda = test_address_,
                                          .trans_id = 3,
                                          .conn_id = 1,
                                          .p_data = &p_data_write_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write);
  SyncOnMainLoop();

  GetVapsServer()->NotifyVaSessionStarted({test_address_}, true);
  SyncOnMainLoop();

  EXPECT_CALL(mock_callbacks_, OnStopVaSession(test_address_)).Times(1);

  tGATTS_DATA p_data_write_stop_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::STOP_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write_stop = {.req_data = {.remote_bda = test_address_,
                                               .trans_id = 4,
                                               .conn_id = 1,
                                               .p_data = &p_data_write_stop_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write_stop);
}

TEST_F(VapsServerTest, OnGattMtuChanged) {
  uint16_t new_mtu = 512;
  tGATTS_DATA p_data_mtu_data = {.mtu = new_mtu};
  tBTA_GATTS p_data_mtu = {.req_data = {.remote_bda = test_address_, .p_data = &p_data_mtu_data}};
  captured_gatt_callback_(BTA_GATTS_MTU_EVT, &p_data_mtu);
}

TEST_F(VapsServerTest, OnReadCharacteristic_VaeName) {
  std::string vae_name = "TestVaeName";
  GetVapsServer()->SetVaeName(vae_name);
  SyncOnMainLoop();

  uint16_t handle = GetCharacteristicHandle(::vaps::uuid::kVaeNameCharacteristic);
  ASSERT_NE(0, handle);

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));

  tGATTS_DATA p_data_read_req_data = {.read_req = {.handle = handle, .offset = 0}};
  tBTA_GATTS p_data_read = {.req_data = {.remote_bda = test_address_,
                                         .trans_id = 1,
                                         .conn_id = 1,
                                         .p_data = &p_data_read_req_data}};
  captured_gatt_callback_(BTA_GATTS_READ_CHARACTERISTIC_EVT, &p_data_read);
}

TEST_F(VapsServerTest, OnReadDescriptor_Ccc) {
  uint16_t ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));

  tGATTS_DATA p_data_read_req_data = {.read_req = {.handle = ccc_handle, .offset = 0}};
  tBTA_GATTS p_data_read = {.req_data = {.remote_bda = test_address_,
                                         .trans_id = 1,
                                         .conn_id = 1,
                                         .p_data = &p_data_read_req_data}};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, &p_data_read);
}

TEST_F(VapsServerTest, InitializeVaSession) {
  GetVapsServer()->SetVaeName("MyVae");
  SyncOnMainLoop();

  // Enable notifications for Control Point and Session State
  uint16_t cp_ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  uint16_t ss_ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled

  tGATTS_DATA p_data_write_cp_ccc_req_data ={
          .write_req = {.handle = cp_ccc_handle,
                        .len = 2,
                        .value = {ccc_notification_value[0], ccc_notification_value[1]},
                        .need_rsp = true}
  };

  tBTA_GATTS p_data_write_cp_ccc = {.req_data = {.remote_bda = test_address_,
                                                 .trans_id = 1,
                                                 .conn_id = 1,
                                                 .p_data = &p_data_write_cp_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_cp_ccc);

  tGATTS_DATA p_data_write_ss_ccc_req_data={.write_req={
                                                      .handle = ss_ccc_handle,
                                                      .len = 2,
                                                      .value = {ccc_notification_value[0], ccc_notification_value[1]},
                                                       .need_rsp = true,
                                                    }};
  tBTA_GATTS p_data_write_ss_ccc = {.req_data = {.remote_bda = test_address_,
                                                 .trans_id = 2,
                                                 .conn_id = 1,
                                                 .p_data = &p_data_write_ss_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_ss_ccc);

  // Expect notifications

  // Send Initialize command
  uint16_t cp_handle = GetCharacteristicHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);

  tGATTS_DATA p_data_write_req_data;
  p_data_write_req_data.write_req.handle = cp_handle;
  p_data_write_req_data.write_req.len = 1;
  p_data_write_req_data.write_req.value[0] = (uint8_t)::vaps::CtpOpcode::INITIALIZE_VA_SESSION;
  p_data_write_req_data.write_req.need_rsp = false;
  tBTA_GATTS p_data_write = {.req_data = {.remote_bda = test_address_,
                                          .trans_id = 3,
                                          .conn_id = 1,
                                          .p_data = &p_data_write_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write);
}

TEST_F(VapsServerTest, NotifyVaSessionStopped_Success) {
  GetVapsServer()->SetVaeName("MyVae");
  SyncOnMainLoop();

  // Enable notifications for Session State
  uint16_t ss_ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  tGATTS_DATA p_data_write_ss_ccc_req_data ={
    .write_req = {.handle = ss_ccc_handle,
                  .len = 2,
                  .value = {ccc_notification_value[0], ccc_notification_value[1]},
                  .need_rsp = false}
  };
  tBTA_GATTS p_data_write_ss_ccc = {.req_data = {.remote_bda = test_address_,
                                                 .trans_id = 1,
                                                 .conn_id = 1,
                                                 .p_data = &p_data_write_ss_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, _, _, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_ss_ccc);

  std::vector<uint8_t> active_value = {(uint8_t)::vaps::VaSessionState::VA_SESSION_ACTIVE};

  GetVapsServer()->NotifyVaSessionStarted({test_address_}, true);
  SyncOnMainLoop();

  // Expect session state notification for stop
  std::vector<uint8_t> ready_value = {(uint8_t)::vaps::VaSessionState::VA_SESSION_READY};
  GetVapsServer()->NotifyVaSessionStopped({test_address_}, true);
  SyncOnMainLoop();
}

TEST_F(VapsServerTest, NotifyVaSessionStopped_SessionNotActive) {
  GetVapsServer()->SetVaeName("MyVae");
  SyncOnMainLoop();
  // Session state is not ACTIVE here.

  EXPECT_CALL(mock_gatt_server_interface_, HandleValueIndication(_, _, _, _)).Times(0);
  GetVapsServer()->NotifyVaSessionStopped({test_address_}, true);
  SyncOnMainLoop();
}

TEST_F(VapsServerTest, DebugDump) {
  // The session must be initialized before we can set all debug values
  uint16_t cp_handle = GetCharacteristicHandle(::vaps::uuid::kVaeControlPointCharacteristic);
  ASSERT_NE(0, cp_handle);
  tGATTS_DATA p_data_write_init_req_data = {
          .write_req = {.handle = cp_handle,
                        .len = 1,
                        .value = {(uint8_t)::vaps::CtpOpcode::INITIALIZE_VA_SESSION, 0x00},
                        .need_rsp = false}};
  tBTA_GATTS p_data_write_init = {.req_data = {.remote_bda = test_address_,
                                               .trans_id = 1,
                                               .conn_id = 1,
                                               .p_data = &p_data_write_init_req_data}};
  captured_gatt_callback_(BTA_GATTS_WRITE_CHARACTERISTIC_EVT, &p_data_write_init);
  SyncOnMainLoop();

  // Setup some state
  GetVapsServer()->SetVaeName("MyVae");
  GetVapsServer()->SetCcid(12);
  SyncOnMainLoop();

  // Use a pipe to capture output
  int fds[2];
  ASSERT_EQ(0, pipe(fds));
  fcntl(fds[0], F_SETFL, O_NONBLOCK);

  GetVapsServer()->DebugDump(fds[1]);
  close(fds[1]);

  char buf[1024];
  ssize_t len = read(fds[0], buf, sizeof(buf) - 1);
  close(fds[0]);
  ASSERT_GT(len, 0);
  buf[len] = '\0';

  std::string output(buf);
  EXPECT_THAT(output, HasSubstr("VAPS Server Manager:"));
  EXPECT_THAT(output, HasSubstr("VAE Name: MyVae"));
  EXPECT_THAT(output, HasSubstr("VAPS CCID: 12"));
  EXPECT_THAT(output, HasSubstr("Remote Client: 11:22:33:44:55:66"));
}

TEST_F(VapsServerTest, OnWriteDescriptor_UnknownClient) {
  RawAddress unknown_address = RawAddress::FromString("00:11:22:33:44:55").value();
  uint16_t ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_value[] = {0x01, 0x00};  // Notification enabled

  tGATTS_DATA p_data_write_req_data = {
    .write_req = {.handle = ccc_handle,
                  .len = 2,
                  .value = {ccc_value[0], ccc_value[1]},
                  .need_rsp = false}
  };


  tBTA_GATTS p_data_write = {.req_data = {.remote_bda = unknown_address,
                                          .trans_id = 1,
                                          .conn_id = 2,  // different conn_id
                                          .p_data = &p_data_write_req_data}};

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_ILLEGAL_PARAMETER, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write);
}

TEST_F(VapsServerTest, OnWriteDescriptor_CccSuccess) {
  uint16_t ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaeCcidCharacteristic);
  uint8_t ccc_value[] = {0x01, 0x00};  // Notification enabled

  tGATTS_DATA p_data_write_req_data = {
    .write_req ={
      .handle = ccc_handle,
      .len = 2,
      .value = {ccc_value[0], ccc_value[1]},
      .need_rsp = false
    }
  };

  tBTA_GATTS p_data_write = {.req_data = {.remote_bda = test_address_,
                                          .trans_id = 1,
                                          .conn_id = 1,
                                          .p_data = &p_data_write_req_data}};

  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write);
}

TEST_F(VapsServerTest, OnReadDescriptor_CccWithValue) {
  // First, write a value to the CCC descriptor
  uint16_t ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);
  uint8_t ccc_notification_value[] = {0x01, 0x00};  // Notification enabled
  tGATTS_DATA p_data_write_ccc_req_data = {
    .write_req = {
      .handle = ccc_handle,
      .len = 2,
      .value = {ccc_notification_value[0], ccc_notification_value[1]},
      .need_rsp = true
    }
  };

  tBTA_GATTS p_data_write_ccc = {.req_data = {.remote_bda = test_address_,
                                              .trans_id = 1,
                                              .conn_id = 1,
                                              .p_data = &p_data_write_ccc_req_data}};
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 1, GATT_SUCCESS, _));
  captured_gatt_callback_(BTA_GATTS_WRITE_DESCRIPTOR_EVT, &p_data_write_ccc);

  // Now, read it back
  tGATTS_RSP* captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(1, 2, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            captured_rsp = new tGATTS_RSP();
            *captured_rsp = *p_msg;
          }));

  tGATTS_DATA p_data_read_req_data = {.read_req = {.handle = ccc_handle, .offset = 0}};
  tBTA_GATTS p_data_read = {.req_data = {.remote_bda = test_address_,
                                         .trans_id = 2,
                                         .conn_id = 1,
                                         .p_data = &p_data_read_req_data}};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, &p_data_read);

  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0001);
  delete captured_rsp;
}

TEST_F(VapsServerTest, OnReadDescriptor_UnknownClient) {
  RawAddress unknown_address = RawAddress::FromString("00:11:22:33:44:55").value();
  uint16_t ccc_handle = GetDescriptorHandle(::vaps::uuid::kVaSessionStateCharacteristic);

  tGATTS_RSP* captured_rsp = nullptr;
  EXPECT_CALL(mock_gatt_server_interface_, SendRsp(2, 1, GATT_SUCCESS, _))
          .WillOnce(Invoke([&](tCONN_ID, uint32_t, tGATT_STATUS, tGATTS_RSP* p_msg) {
            captured_rsp = new tGATTS_RSP();
            *captured_rsp = *p_msg;
          }));

  tGATTS_DATA p_data_read_req_data = {.read_req = {.handle = ccc_handle, .offset = 0}};
  tBTA_GATTS p_data_read = {.req_data = {.remote_bda = unknown_address,
                                         .trans_id = 1,
                                         .conn_id = 2,
                                         .p_data = &p_data_read_req_data}};
  captured_gatt_callback_(BTA_GATTS_READ_DESCRIPTOR_EVT, &p_data_read);

  ASSERT_NE(captured_rsp, nullptr);
  ASSERT_EQ(captured_rsp->attr_value.len, 2);
  uint16_t read_value;
  const uint8_t* value_ptr = captured_rsp->attr_value.value;
  STREAM_TO_UINT16(read_value, value_ptr);
  EXPECT_EQ(read_value, 0x0000);
  delete captured_rsp;
}

}  // namespace bluetooth::vaps