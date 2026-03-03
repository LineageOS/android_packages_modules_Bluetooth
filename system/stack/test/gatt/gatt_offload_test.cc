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
#include <gtest/gtest.h>
#include <unistd.h>

#include <atomic>
#include <functional>
#include <future>
#include <memory>
#include <thread>

#include "avrcp.h"
#include "btif_status.h"
#include "common/message_loop_thread.h"
#include "gmock/gmock.h"
#include "hal/gatt_hal.h"
#include "hardware/bt_gatt_types.h"
#include "lpp/lpp_offload_interface_mock.h"
#include "metrics/mock/metrics_mock.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/gatt_api.h"
#include "stack/include/main_thread.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_security_client_interface.h"

using android::bluetooth::gatt::GattOffloadErrorEnum;
using android::bluetooth::gatt::GattOffloadSessionStateEnum;
using android::bluetooth::gatt::GattRoleEnum;
using bluetooth::hal::GattHalCallback;
using bluetooth::hal::GattSession;
using ::testing::_;
using ::testing::DoAll;
using ::testing::MockFunction;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::SaveArg;

// Mocks and fakes are provided by the build environment.
extern tGATT_CB gatt_cb;
namespace bluetooth::lpp::testing {
extern MockLppOffloadInterface* mock_lpp_offload_interface_;
}  // namespace bluetooth::lpp::testing
namespace {
const uint16_t kCharacteristicHandle = 2;
std::atomic<int> num_async_tasks;
bluetooth::common::MessageLoopThread message_loop_thread("gatt_offload_test_thread");

static void init_message_loop_thread() {
  num_async_tasks = 0;
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }
}

static void cleanup_message_loop_thread() { message_loop_thread.ShutDown(); }

void SyncOnMainLoop() {
  while (num_async_tasks > 0) {
    std::this_thread::yield();
  }
}
}  // namespace

BtStatus do_in_main_thread(base::OnceClosure task) {
  if (!message_loop_thread.DoInThread(base::BindOnce(
              [](base::OnceClosure task, std::atomic<int>& num_async_tasks) {
                std::move(task).Run();
                num_async_tasks--;
              },
              std::move(task), std::ref(num_async_tasks)))) {
    return BtifStatus(FAIL);
  }
  num_async_tasks++;
  return BtifStatus();
}

bluetooth::common::MessageLoopThread* get_main_thread() { return &message_loop_thread; }

bluetooth::common::PostableContext* get_main() { return message_loop_thread.Postable(); }

bool is_main_thread() { return message_loop_thread.IsRunningOnSameThread(); }

BtStatus do_in_main_thread_delayed(base::OnceClosure task, std::chrono::microseconds /*delay*/) {
  return do_in_main_thread(std::move(task));
}

void post_on_bt_main(base::OnceClosure closure);
void post_on_bt_main(base::OnceClosure closure) { do_in_main_thread(std::move(closure)); }

void main_thread_start_up() {}

void main_thread_shut_down() {}

std::vector<btgatt_db_element_t> create_service_vector();

class GattOffloadTest : public ::testing::Test {
protected:
  void SetUp() override {
    gatt_cb = tGATT_CB{};
    for (int i = 0; i < GATT_MAX_PHY_CHANNEL; i++) {
      gatt_cb.tcb[i].in_use = false;
    }
  }
};

class GattOffloadCharacteristicsTest : public GattOffloadTest {
protected:
  void SetUp() override {
    init_message_loop_thread();
    GattOffloadTest::SetUp();
    mock_ = std::make_unique<bluetooth::lpp::testing::MockLppOffloadInterface>();
    bluetooth::lpp::testing::mock_lpp_offload_interface_ = mock_.get();
    mock_metrics_logger_ = std::make_shared<bluetooth::metrics::MockMetrics>();
    bluetooth::metrics::MockMetrics::SetInstance(mock_metrics_logger_);
    set_security_client_interface(mock_btm_security_);
    set_mock_btm_client_interface(&btm_client_interface_);

    EXPECT_CALL(*mock_, InitializeGattHal(_))
            .WillOnce(DoAll(SaveArg<0>(&gatt_hal_callback_), Return(true)));
    ASSERT_TRUE(gatt_offload_init());
    ASSERT_NE(gatt_hal_callback_, nullptr);

    const tCONN_ID conn_id = 0;
    tGATT_TCB& tcb = gatt_cb.tcb[conn_id];
    tcb.in_use = true;
    tcb.ch_state = GATT_CH_OPEN;
    tcb.tcb_idx = conn_id;
    tcb.payload_size = 251;
  }

  void TearDown() override {
    reset_mock_btm_client_interface();
    bluetooth::lpp::testing::mock_lpp_offload_interface_ = nullptr;
    bluetooth::metrics::MockMetrics::SetInstance(nullptr);
    mock_metrics_logger_ = nullptr;
    GattOffloadTest::TearDown();
    cleanup_message_loop_thread();
  }

  std::unique_ptr<bluetooth::lpp::testing::MockLppOffloadInterface> mock_;
  GattHalCallback* gatt_hal_callback_ = nullptr;
  NiceMock<MockSecurityClientInterface> mock_btm_security_;
  MockBtmClientInterface btm_client_interface_;
  std::shared_ptr<bluetooth::metrics::MockMetrics> mock_metrics_logger_;
};

TEST_F(GattOffloadTest, init_success) {
  // Manually create and destroy the mock for this test to ensure no interference.
  auto* mock = new bluetooth::lpp::testing::MockLppOffloadInterface();
  bluetooth::lpp::testing::mock_lpp_offload_interface_ = mock;

  EXPECT_CALL(*mock, InitializeGattHal(_)).WillOnce(Return(true));
  ASSERT_TRUE(gatt_offload_init());

  delete mock;
  bluetooth::lpp::testing::mock_lpp_offload_interface_ = nullptr;
}

TEST_F(GattOffloadTest, init_failure) {
  auto* mock = new bluetooth::lpp::testing::MockLppOffloadInterface();
  bluetooth::lpp::testing::mock_lpp_offload_interface_ = mock;

  EXPECT_CALL(*mock, InitializeGattHal(_)).WillOnce(Return(false));
  ASSERT_FALSE(gatt_offload_init());

  delete mock;
  bluetooth::lpp::testing::mock_lpp_offload_interface_ = nullptr;
}

TEST_F(GattOffloadTest, init_null_manager) {
  // Ensure the global pointer is null.
  bluetooth::lpp::testing::mock_lpp_offload_interface_ = nullptr;
  ASSERT_FALSE(gatt_offload_init());
}

std::vector<btgatt_db_element_t> create_service_vector() {
  // Define the data as a local vector
  std::vector<btgatt_db_element_t> service_db = {
          {
                  .uuid = bluetooth::Uuid("00001801-0000-1000-8000-00805f9b34fb"),
                  .type = BTGATT_DB_PRIMARY_SERVICE,
                  .attribute_handle = 1,
          },
          {
                  .uuid = bluetooth::Uuid("00002a05-0000-1000-8000-00805f9b34fb"),
                  .type = BTGATT_DB_CHARACTERISTIC,
                  .attribute_handle = 2,
                  .properties = 2,
          }};

  // The vector is returned by value (efficiently moved)
  return service_db;
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_success) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, GattRoleEnum::GATT_ROLE_CLIENT,
                      GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _, 0,
                      GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_invalid_conn_id) {
  const tCONN_ID conn_id = 0;
  // Invalidate the connection ID
  gatt_cb.tcb[conn_id].in_use = false;
  std::vector<btgatt_db_element_t> service = create_service_vector();

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_INVALID_HANDLE);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_with_invalid_acl_handle) {
  const tCONN_ID conn_id = 0;
  const uint16_t fake_acl_handle = GATT_INVALID_ACL_HANDLE;

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillOnce(Return(fake_acl_handle));

  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));

  auto result = future.get();
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_INVALID_HANDLE);
}

struct PermissionTestParams {
  uint16_t permissions;
  uint8_t properties;
  bool mock_is_encrypted;
  bool mock_is_bonded;
  tGATT_STATUS expected_status;
};

class GattOffloadPermissionTest : public GattOffloadCharacteristicsTest,
                                  public ::testing::WithParamInterface<PermissionTestParams> {
protected:
  void TearDown() override { GattOffloadCharacteristicsTest::TearDown(); }

  static bool mock_is_encrypted_;
  static bool mock_is_bonded_;
};

bool GattOffloadPermissionTest::mock_is_encrypted_;
bool GattOffloadPermissionTest::mock_is_bonded_;

TEST_P(GattOffloadPermissionTest, OffloadCharacteristicsPermissionFail) {
  const auto& params = GetParam();
  const tCONN_ID conn_id = 0;
  mock_is_encrypted_ = params.mock_is_encrypted;
  mock_is_bonded_ = params.mock_is_bonded;

  std::vector<btgatt_db_element_t> service = {
          {.uuid = bluetooth::Uuid("00001801-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_PRIMARY_SERVICE,
           .attribute_handle = 1},
          {.uuid = bluetooth::Uuid("00002a05-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_CHARACTERISTIC,
           .attribute_handle = 2,
           .properties = params.properties,
           .permissions = params.permissions}};

  ON_CALL(mock_btm_security_, BTM_IsEncrypted(_, _)).WillByDefault(Return(mock_is_encrypted_));
  ON_CALL(mock_btm_security_, BTM_IsBonded(_, _)).WillByDefault(Return(mock_is_bonded_));

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /* is_server=*/true, service.data(), std::size(service),
                               /*endpoint_id=*/0,
                               /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"", std::move(promise));

  auto result = future.get();
  EXPECT_EQ(result.status, params.expected_status);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

INSTANTIATE_TEST_SUITE_P(
        GattOffloadPermissionTests, GattOffloadPermissionTest,
        ::testing::Values(
                // Read Auth Required Fail: Requires authentication, but not encrypted/bonded.
                PermissionTestParams{GATT_READ_AUTH_REQUIRED, 2 /*Read*/,
                                     /*mock_is_encrypted=*/false, /*mock_is_bonded=*/false,
                                     GATT_INSUF_AUTHENTICATION},
                // Read MITM Required Fail: Requires MITM, but BTM_IsEncrypted is true,
                // but no MITM is established (default mock behavior).
                PermissionTestParams{GATT_READ_MITM_REQUIRED, 2 /*Read*/,
                                     /*mock_is_encrypted=*/true, /*mock_is_bonded=*/true,
                                     GATT_INSUF_AUTHENTICATION},
                // Read Encrypted Required Fail: Requires encryption, but BTM_IsEncrypted is false.
                PermissionTestParams{GATT_READ_ENCRYPTED_REQUIRED, 2 /*Read*/,
                                     /*mock_is_encrypted=*/false, /*mock_is_bonded=*/true,
                                     GATT_INSUF_AUTHENTICATION},
                // Write Auth Required Fail: Requires authentication, but not encrypted/bonded.
                PermissionTestParams{GATT_WRITE_AUTH_REQUIRED, 8 /*Write*/,
                                     /*mock_is_encrypted=*/false, /*mock_is_bonded=*/false,
                                     GATT_INSUF_AUTHENTICATION},
                // Write MITM Required Fail: Requires MITM, but BTM_IsEncrypted is false.
                PermissionTestParams{GATT_WRITE_MITM_REQUIRED, 8 /*Write*/,
                                     /*mock_is_encrypted=*/false, /*mock_is_bonded=*/false,
                                     GATT_INSUF_AUTHENTICATION},
                // Write Encrypted Perm Fail: Requires encryption, but BTM_IsEncrypted is false.
                PermissionTestParams{GATT_WRITE_ENCRYPTED_PERM, 8 /*Write*/,
                                     /*mock_is_encrypted=*/false, /*mock_is_bonded=*/true,
                                     GATT_INSUF_AUTHENTICATION}));

TEST_F(GattOffloadPermissionTest, offload_characteristics_invalid_db_element_type_fail) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = {
          {.uuid = bluetooth::Uuid("00001801-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_PRIMARY_SERVICE,
           .attribute_handle = 1},
          {.uuid = bluetooth::Uuid("00002a05-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_INCLUDED_SERVICE,
           .attribute_handle = 2}};

  ON_CALL(mock_btm_security_, BTM_IsEncrypted(_, _)).WillByDefault(Return(true));
  ON_CALL(mock_btm_security_, BTM_IsBonded(_, _)).WillByDefault(Return(true));

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/true, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_INSUF_AUTHENTICATION);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_null_service) {
  const tCONN_ID conn_id = 0;
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/false, nullptr, /*elements_count=*/2,
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_ILLEGAL_PARAMETER);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_no_characteristics) {
  const tCONN_ID conn_id = 0;
  btgatt_db_element_t service[] = {{.uuid = bluetooth::Uuid("00001801-0000-1000-8000-00805f9b34fb"),
                                    .type = BTGATT_DB_PRIMARY_SERVICE,
                                    .attribute_handle = 1}};

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service, std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_ILLEGAL_PARAMETER);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_hall_cal_fail) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& /*session*/) {
    return false;
  });

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_INTERNAL_ERROR);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_hal_callback_fail) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_FAILED, _, _,
                      GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_HAL_FAILURE, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_FAILURE);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_INTERNAL_ERROR);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_add_invalid_db_elements_fail) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = {
          {.uuid = bluetooth::Uuid("00001801-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_PRIMARY_SERVICE,
           .attribute_handle = 1},
          {.uuid = bluetooth::Uuid("00002a05-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_INCLUDED_SERVICE,
           .attribute_handle = 2}};

  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_ILLEGAL_PARAMETER);
  EXPECT_EQ(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, offload_characteristics_duplicate_session_fail) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service1 = create_service_vector();

  std::vector<btgatt_db_element_t> service2 = {
          {.uuid = bluetooth::Uuid("0000180A-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_PRIMARY_SERVICE,
           .attribute_handle = 10},
          {.uuid = bluetooth::Uuid("00002a06-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_CHARACTERISTIC,
           .attribute_handle = 2,
           .properties = 2}};
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  std::promise<btgatt_offload_result_t> promise1;
  auto future1 = promise1.get_future();
  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id, /*is_server=*/false, service1.data(), std::size(service1),
                               /*endpoint_id=*/0, /*hub_id*/ 0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise1));

  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result1 = future1.get();
  EXPECT_EQ(result1.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result1.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  std::promise<btgatt_offload_result_t> promise2;
  auto future2 = promise2.get_future();
  gatt_offload_characteristics(conn_id, /*is_server=*/false, service2.data(), std::size(service2),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise2));
  SyncOnMainLoop();

  auto result2 = future2.get();
  EXPECT_EQ(result2.status, tGATT_STATUS::GATT_DUP_REG);
  EXPECT_EQ(result2.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
}

TEST_F(GattOffloadCharacteristicsTest, unoffload_session) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, UnregisterGattService(_)).Times(1);

  gatt_unoffload_session(conn_id, gatt_cb.offload_sessions.begin()->first,
                         tGATT_STATUS::GATT_SUCCESS);
}

TEST_F(GattOffloadCharacteristicsTest, gattc_inform_notification_handle_gatt_server) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, GattRoleEnum::GATT_ROLE_SERVER,
                      GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _, 0,
                      GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id, /*is_server=*/true, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, UnregisterGattService(_)).Times(0);

  gattc_inform_notification_handle(&gatt_cb.tcb[conn_id], kCharacteristicHandle);
}

TEST_F(GattOffloadCharacteristicsTest, gattc_inform_notification_handle_gatt_client) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, UnregisterGattService(_)).Times(1);
  gattc_inform_notification_handle(&gatt_cb.tcb[conn_id], kCharacteristicHandle);
}

TEST_F(GattOffloadCharacteristicsTest, gattc_handle_service_changed_indication_gatt_server) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, GattRoleEnum::GATT_ROLE_SERVER,
                      GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _, 0,
                      GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/true, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));

  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto res = future.get();
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, UnregisterGattService(_)).Times(0);  // as role would be server
  gattc_offload_handle_service_changed_indication(&gatt_cb.tcb[conn_id]);
}

TEST_F(GattOffloadCharacteristicsTest, gattc_handle_service_changed_indication_gatt_client) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto res = future.get();
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, UnregisterGattService(_)).Times(1);
  gattc_offload_handle_service_changed_indication(&gatt_cb.tcb[conn_id]);
}

TEST_F(GattOffloadCharacteristicsTest, clear_session_by_handle) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));

  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();
  auto res = future.get();
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  ASSERT_EQ(1u, gatt_cb.offload_sessions.size());
  uint16_t acl_handle = gatt_cb.offload_sessions.begin()->second.hal_session.acl_connection_handle;

  EXPECT_CALL(*mock_, ClearGattServices(acl_handle)).WillOnce([&](uint16_t /*acl_handle*/) {
    return true;
  });

  gatt_offload_clear_sessions_by_acl_handle(acl_handle, bluetooth::hal::GattError::GATT_ERROR_NONE);
}

TEST_F(GattOffloadCharacteristicsTest, clear_session_by_handle_failure) {
  // Case 1: Handle not found
  EXPECT_FALSE(gatt_offload_clear_sessions_by_acl_handle(
          0x1234, bluetooth::hal::GattError::GATT_ERROR_NONE));

  // Case 2: Unoffload already in progress
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillOnce(Return(0x1234));

  uint16_t acl_handle = 0x1234;

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(result.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  EXPECT_CALL(*mock_, ClearGattServices(acl_handle)).Times(1);
  EXPECT_TRUE(gatt_offload_clear_sessions_by_acl_handle(
          acl_handle, bluetooth::hal::GattError::GATT_ERROR_NONE));

  // Second call should fail
  EXPECT_FALSE(gatt_offload_clear_sessions_by_acl_handle(
          acl_handle, bluetooth::hal::GattError::GATT_ERROR_NONE));
}

TEST_F(GattOffloadCharacteristicsTest, clear_session_by_conn_id) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), std::size(service),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto res = future.get();
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  ASSERT_EQ(1u, gatt_cb.offload_sessions.size());

  session_id = gatt_cb.offload_sessions.begin()->first;

  EXPECT_CALL(*mock_, UnregisterGattService(session_id)).Times(1);

  gatt_offload_clear_sessions_by_conn_id(conn_id);
}

TEST_F(GattOffloadCharacteristicsTest, clear_multiple_sessions_by_conn_id_failure) {
  const tCONN_ID conn_id1 = 0;
  const tCONN_ID conn_id2 = 1;
  std::vector<btgatt_db_element_t> service1 = create_service_vector();
  std::vector<btgatt_db_element_t> service2 = {
          {.uuid = bluetooth::Uuid("0000180A-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_PRIMARY_SERVICE,
           .attribute_handle = 10},
          {.uuid = bluetooth::Uuid("00002a06-0000-1000-8000-00805f9b34fb"),
           .type = BTGATT_DB_CHARACTERISTIC,
           .attribute_handle = 11,
           .properties = 2}};

  std::promise<btgatt_offload_result_t> promise1;
  auto future1 = promise1.get_future();
  uint16_t session_id1 = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id1 = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id1, /*is_server=*/false, service1.data(), std::size(service1),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise1));
  gatt_hal_callback_->registerServiceComplete(session_id1, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();
  auto res1 = future1.get();
  EXPECT_EQ(res1.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res1.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  std::promise<btgatt_offload_result_t> promise2;
  auto future2 = promise2.get_future();
  uint16_t session_id2 = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id2 = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id2, /*is_server=*/false, service2.data(), std::size(service2),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise2));
  gatt_hal_callback_->registerServiceComplete(session_id2, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();
  auto res2 = future2.get();
  EXPECT_EQ(res2.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res2.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  ASSERT_EQ(2u, gatt_cb.offload_sessions.size());

  // Expect UnregisterGattService to be called for both sessions associated with conn_id
  EXPECT_CALL(*mock_, UnregisterGattService(session_id1)).Times(1);
  EXPECT_CALL(*mock_, UnregisterGattService(session_id2)).Times(1);
  gatt_offload_clear_sessions_by_conn_id(conn_id1);
  SyncOnMainLoop();
  gatt_offload_clear_sessions_by_conn_id(conn_id2);
  SyncOnMainLoop();
  // The sessions are removed from gatt_cb.offload_sessions upon
  // unregisterServiceComplete, which is not mocked here.
  // We only verify the calls to the HAL mock.
}

TEST_F(GattOffloadCharacteristicsTest, dump_no_sessions) {
  ASSERT_TRUE(gatt_cb.offload_sessions.empty());

  int fds[2];
  ASSERT_EQ(0, pipe(fds));

  gatt_offload_sessions_dump(fds[1]);
  close(fds[1]);

  char buf[1024] = {};
  ssize_t len = read(fds[0], buf, sizeof(buf) - 1);
  close(fds[0]);
  ASSERT_GT(len, 0);
  buf[len] = '\0';

  std::string output(buf);
  EXPECT_NE(std::string::npos, output.find("GATT offload sessions"));
  EXPECT_NE(std::string::npos, output.find("Number of active offload sessions: 0"));
}

TEST_F(GattOffloadCharacteristicsTest, dump_one_session) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);
  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);

  SyncOnMainLoop();

  auto res = future.get();
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);
  EXPECT_NE(res.session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);

  int fds[2];
  ASSERT_EQ(0, pipe(fds));

  gatt_offload_sessions_dump(fds[1]);
  close(fds[1]);

  char buf[1024] = {};
  ssize_t len = read(fds[0], buf, sizeof(buf) - 1);
  close(fds[0]);
  ASSERT_GT(len, 0);
  buf[len] = '\0';

  std::string output(buf);
  EXPECT_NE(std::string::npos, output.find("GATT offload sessions"));
}

class GattOffloadHalCallbackTest : public GattOffloadCharacteristicsTest {};

TEST_F(GattOffloadHalCallbackTest, register_service_complete_success) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.session_id, session_id);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);
}

TEST_F(GattOffloadHalCallbackTest, unregister_service_complete) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    return true;
  });
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));

  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  SyncOnMainLoop();
  auto result = future.get();
  EXPECT_EQ(result.session_id, session_id);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);

  EXPECT_CALL(*mock_, UnregisterGattService(session_id)).Times(1);
  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STOPPED, _,
                      _, GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, _, _))
          .Times(1);
  gatt_unoffload_session(conn_id, session_id, tGATT_STATUS::GATT_SUCCESS);
  gatt_hal_callback_->unregisterServiceComplete(session_id);
  SyncOnMainLoop();

  ASSERT_EQ(gatt_cb.offload_sessions.find(session_id), gatt_cb.offload_sessions.end());
}

TEST_F(GattOffloadHalCallbackTest, clearServicesComplete) {
  uint16_t acl_handle = 0;

  gatt_hal_callback_->clearServicesComplete(acl_handle);
  SyncOnMainLoop();

  ASSERT_TRUE(gatt_cb.offload_sessions.empty());
}

TEST_F(GattOffloadHalCallbackTest, error_report_out_of_sync) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;
  uint16_t acl_handle = 0;

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillOnce(Return(0x1234));

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    acl_handle = session.acl_connection_handle;
    return true;
  });

  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.session_id, session_id);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);

  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  ASSERT_NE(acl_handle, 0);

  EXPECT_CALL(*mock_, ClearGattServices(acl_handle)).WillOnce([&](uint16_t /*acl_handle*/) {
    return true;
  });
  gatt_hal_callback_->errorReport(acl_handle, 0,
                                  bluetooth::hal::GattError::GATT_ERROR_DATABASE_OUT_OF_SYNC);
  SyncOnMainLoop();
}

TEST_F(GattOffloadHalCallbackTest, error_report_err_rsp_timeout) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;
  uint16_t acl_handle = 0;

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillRepeatedly(Return(0x1234));

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    acl_handle = session.acl_connection_handle;
    return true;
  });

  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.session_id, session_id);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);

  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  ASSERT_NE(acl_handle, 0);

  EXPECT_CALL(*mock_, ClearGattServices(acl_handle)).Times(1);
  gatt_hal_callback_->errorReport(acl_handle, 0,
                                  bluetooth::hal::GattError::GATT_ERROR_RESPONSE_TIMEOUT);
  SyncOnMainLoop();
}

TEST_F(GattOffloadHalCallbackTest, error_report_err_protocol_violation) {
  const tCONN_ID conn_id = 0;
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;
  uint16_t acl_handle = 0;

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillRepeatedly(Return(0x1234));

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    acl_handle = session.acl_connection_handle;
    return true;
  });

  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto result = future.get();
  EXPECT_EQ(result.session_id, session_id);
  EXPECT_EQ(result.status, tGATT_STATUS::GATT_SUCCESS);

  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  ASSERT_NE(acl_handle, 0);

  EXPECT_CALL(*mock_, ClearGattServices(acl_handle)).Times(1);
  gatt_hal_callback_->errorReport(acl_handle, 0,
                                  bluetooth::hal::GattError::GATT_ERROR_PROTOCOL_VIOLATION);
  SyncOnMainLoop();
}

MockFunction<void(tGATT_IF, tCONN_ID, uint32_t, tGATT_STATUS)> mock_characteristics_unoffloaded;

// Define the C-style Trampoline Function
// This function has the exact signature required by the typedef.
static void characteristics_unoffloaded_trampoline(tGATT_IF gatt_if, tCONN_ID conn_id,
                                                   uint32_t session_id, tGATT_STATUS status) {
  // Call the mock object inside the trampoline
  mock_characteristics_unoffloaded.Call(gatt_if, conn_id, session_id, status);
}

TEST_F(GattOffloadHalCallbackTest, error_report_out_of_sync_with_callback) {
  const tGATT_IF gatt_if = 5;
  const tCONN_ID conn_id = gatt_create_conn_id(0, gatt_if);

  // Register a callback
  auto reg = std::make_unique<tGATT_REG>();
  reg->in_use = true;
  reg->gatt_if = gatt_if;
  reg->app_cb.p_characteristics_unoffloaded_cb = &characteristics_unoffloaded_trampoline;
  gatt_cb.cl_rcb_map[gatt_if] = std::move(reg);

  // Offload a service
  std::vector<btgatt_db_element_t> service = create_service_vector();
  std::promise<btgatt_offload_result_t> promise;
  auto future = promise.get_future();
  uint16_t session_id = BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;
  uint16_t acl_handle = 0;

  EXPECT_CALL(btm_client_interface_, BTM_GetHCIConnHandle).WillOnce(Return(0x1234));

  EXPECT_CALL(*mock_, RegisterGattService(_)).WillOnce([&](const GattSession& session) {
    session_id = session.id;
    acl_handle = session.acl_connection_handle;
    return true;
  });

  EXPECT_CALL(*mock_metrics_logger_,
              LogGattOffloadSessionStateChanged(
                      _, _, _, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED, _,
                      0, _, _, _))
          .Times(1);

  gatt_offload_characteristics(conn_id, /*is_server=*/false, service.data(), service.size(),
                               /*endpoint_id=*/0, /*hub_id=*/0, /*uid=*/0, /*attribution_tag=*/"",
                               std::move(promise));
  gatt_hal_callback_->registerServiceComplete(session_id, bluetooth::hal::GATT_SUCCESS);
  SyncOnMainLoop();

  auto res = future.get();
  EXPECT_EQ(res.session_id, session_id);
  EXPECT_EQ(res.status, tGATT_STATUS::GATT_SUCCESS);

  ASSERT_NE(session_id, BTGATT_OFFLOAD_SESSION_ID_UNKNOWN);
  ASSERT_NE(acl_handle, 0);
  ASSERT_EQ(1u, gatt_cb.offload_sessions.size());

  // Trigger error report and expect callback
  EXPECT_CALL(mock_characteristics_unoffloaded,
              Call(gatt_if, conn_id, session_id, GATT_DATABASE_OUT_OF_SYNC));

  gatt_hal_callback_->errorReport(acl_handle, 0,
                                  bluetooth::hal::GattError::GATT_ERROR_DATABASE_OUT_OF_SYNC);
  SyncOnMainLoop();
}
