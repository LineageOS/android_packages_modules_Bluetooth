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

#include <base/bind_helpers.h>
#include <base/functional/bind.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <bluetooth/types/bt_transport.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>
#include <osi/include/alarm.h>
#include <string.h>
#include <sys/socket.h>

#include <variant>

#include "bta/le_audio/le_audio_types.h"
#include "bta/mock/bta_gatt_api_mock.h"
#include "bta/mock/mock_bta_hearing_aid_audio_source.h"
#include "bta_gatt_queue_mock.h"
#include "bta_hearing_aid_api.h"
#include "btif_status.h"
#include "btif_storage_mock.h"
#include "btm_api_mock.h"
#include "gatt/database_builder.h"
#include "hardware/bt_gatt_types.h"
#include "hci/controller_mock.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "stack/mock/mock_stack_btm_interface.h"
#include "stack/mock/mock_stack_gap_conn_interface.h"
#include "stack/mock/mock_stack_l2cap_interface.h"
#include "stack/mock/mock_stack_security_client_interface.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"

static std::map<const char*, bool> fake_osi_bool_props;

namespace bluetooth::asha {
namespace {

using base::HexEncode;

using bluetooth::common::MessageLoopThread;
using ::testing::_;
using ::testing::AnyNumber;
using ::testing::AtLeast;
using ::testing::DoAll;
using ::testing::DoDefault;
using ::testing::Invoke;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::SaveArg;
using ::testing::Sequence;
using ::testing::SetArgPointee;
using ::testing::WithArg;

std::atomic<int> num_async_tasks;
bluetooth::common::MessageLoopThread message_loop_thread(
        "test message loop", bluetooth::os::Thread::Priority::REAL_TIME);

BtStatus do_in_main_thread(base::OnceClosure task) {
  // Wrap the task with task counter so we could later know if there are
  // any callbacks scheduled and we should wait before performing some actions
  if (!message_loop_thread.DoInThread(base::BindOnce(
              [](base::OnceClosure task, std::atomic<int>& num_async_tasks) {
                std::move(task).Run();
                num_async_tasks--;
              },
              std::move(task), std::ref(num_async_tasks)))) {
    log::error("failed to post task to task runner!");
    return BtifStatus(FAIL);
  }
  num_async_tasks++;
  return BtifStatus();
}

static void init_message_loop_thread() {
  num_async_tasks = 0;
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling()) {
    log::error("Unable to set real time scheduling");
  }
}

static void cleanup_message_loop_thread() { message_loop_thread.ShutDown(); }

void SyncOnMainLoop() {
  // Wait for the main loop to flush
  // WARNING: Not tested with Timers pushing periodic tasks to the main loop
  while (num_async_tasks > 0) {
  }
}

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
  return RawAddress(bytes);
}

static uint16_t GetTestConnId(const RawAddress& address) {
  return address.address[RawAddress::kLength - 1];
}

class MockHearingAidCallbacks : public HearingAidCallbacks {
public:
  MockHearingAidCallbacks() = default;
  MockHearingAidCallbacks(const MockHearingAidCallbacks&) = delete;
  ~MockHearingAidCallbacks() override = default;

  MOCK_METHOD((void), OnConnectionState, (ConnectionState state, const RawAddress& address),
              (override));
  MOCK_METHOD((void), OnDeviceAvailable,
              (uint8_t capabilities, uint64_t hiSyncId, const RawAddress& address), (override));
};

class HearingAidTestBase : public ::testing::Test {
protected:
  HearingAidAudioReceiver* audio_receiver_;

  Uuid HEARING_AID_UUID = Uuid("FDF0");
  Uuid READ_ONLY_PROPERTIES_UUID = Uuid("6333651e-c481-4a3e-9169-7c902aad37bb");
  Uuid AUDIO_CONTROL_POINT_UUID = Uuid("f0d4de7e-4a88-476c-9d9f-1937b0996cc0");
  Uuid AUDIO_STATUS_UUID = Uuid("38663f1a-e711-4cac-b641-326b56404837");
  Uuid VOLUME_UUID = Uuid("00e4ca9e-ab14-41e4-8823-f9e70c7e91df");
  Uuid LE_PSM_UUID = Uuid("2d410339-82b6-42aa-b34e-e2e01df8cc1a");

  static constexpr uint16_t kSvcStartHdl = 0x0010;
  static constexpr uint16_t kReadOnlyProperties = 0x0012;
  static constexpr uint16_t kAudioControlPoint = 0x0015;
  static constexpr uint16_t kAudioStatusPoint = 0x0018;
  static constexpr uint16_t kVolume = 0x001B;
  static constexpr uint16_t kLePsm = 0x001E;
  static constexpr uint16_t kSvcEndHdl = kLePsm;

  void set_sample_database(uint16_t conn_id) {
    static constexpr uint16_t kGapSvcStartHdl = 0x0001;
    static constexpr uint16_t kGapDeviceNameValHdl = 0x0003;

    gatt::DatabaseBuilder bob;

    /* Generic Access Service */
    bob.AddService(kGapSvcStartHdl, kGapDeviceNameValHdl, Uuid::From16Bit(0x1800), true);
    /* Device Name Char. */
    bob.AddCharacteristic(kGapDeviceNameValHdl - 1, kGapDeviceNameValHdl, Uuid::From16Bit(0x2a00),
                          GATT_CHAR_PROP_BIT_READ);

    /* ASHA Service */
    bob.AddService(kSvcStartHdl, kSvcEndHdl, HEARING_AID_UUID, true);
    bob.AddCharacteristic(kReadOnlyProperties - 1, kReadOnlyProperties, READ_ONLY_PROPERTIES_UUID,
                          GATT_CHAR_PROP_BIT_READ);
    bob.AddCharacteristic(kAudioControlPoint - 1, kAudioControlPoint, AUDIO_CONTROL_POINT_UUID,
                          GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE_NR);
    bob.AddCharacteristic(kAudioStatusPoint - 1, kAudioStatusPoint, AUDIO_STATUS_UUID,
                          GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
    bob.AddDescriptor(kAudioStatusPoint + 1, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
    bob.AddCharacteristic(kVolume - 1, kVolume, VOLUME_UUID, GATT_CHAR_PROP_BIT_WRITE_NR);
    bob.AddCharacteristic(kLePsm - 1, kLePsm, LE_PSM_UUID, GATT_CHAR_PROP_BIT_READ);

    services_map[conn_id] = bob.Build().Services();

    ON_CALL(gatt_queue, ReadCharacteristic(conn_id, _, _, _))
            .WillByDefault(Invoke(
                    [this](uint16_t conn_id, uint16_t handle, GATT_READ_OP_CB cb, void* cb_data) {
                      auto* svc = gatt::FindService(services_map[conn_id], handle);
                      if (svc == nullptr) {
                        return;
                      }

                      std::vector<uint8_t> value;
                      tGATT_STATUS status = GATT_SUCCESS;

                      switch (handle) {
                        case kReadOnlyProperties:
                          value.resize(17);
                          value[0] = 0x01;  // Version
                          if (device_capabilities_.count(conn_id)) {
                            value[1] = device_capabilities_.at(conn_id);
                          } else {
                            value[1] = 0x00;  // default - left, monaural, CSIS not supported
                          }

                          for (int i = 0; i < 8; i++) {
                            value[2 + i] = 0xDE;  // HiSyncId
                          }
                          value[10] = 0x01;  // FeatureMap
                          value[11] = 0x01;  // RenderDelay
                          value[12] = 0x01;  // PreparationDelay
                          value[14] = 0x00;  // RFU
                          value[13] = 0x00;  // RFU
                          value[15] = 0x02;  // Codec IDs - G.722 @ 16 kHz
                          value[16] = 0x00;
                          break;
                        case kAudioStatusPoint:
                          value.resize(1);
                          value.assign(1, 0);
                          break;
                        case kLePsm:
                          value.resize(2);
                          value.assign(2, 0x0080);
                          break;
                          /* passthrough */
                        default:
                          status = GATT_READ_NOT_PERMIT;
                          break;
                      }

                      if (cb) {
                        cb(conn_id, status, handle, value.size(), value.data(), cb_data);
                      }
                    }));

    ON_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _))
            .WillByDefault(Invoke([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                                     tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb,
                                     void* cb_data) -> void {
              if (cb) {
                cb(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
              }
            }));

    /* default action for GetCharacteristic function call */
    ON_CALL(gatt_interface, GetCharacteristic(_, _))
            .WillByDefault(
                    Invoke([&](uint16_t conn_id, uint16_t handle) -> const gatt::Characteristic* {
                      std::list<gatt::Service>& services = services_map[conn_id];
                      for (auto const& service : services) {
                        for (auto const& characteristic : service.characteristics) {
                          if (characteristic.value_handle == handle) {
                            return &characteristic;
                          }
                        }
                      }

                      return nullptr;
                    }));

    ON_CALL(gatt_interface, ServiceSearchRequest(_))
            .WillByDefault(WithArg<0>(
                    Invoke([&](uint16_t conn_id) { InjectSearchCompleteEvent(conn_id); })));

    /* default action for GetServices function call */
    ON_CALL(gatt_interface, GetServices(_))
            .WillByDefault(WithArg<0>(Invoke([&](uint16_t conn_id) -> std::list<gatt::Service>* {
              return &services_map[conn_id];
            })));

    /* default action for RegisterForNotifications function call */
    ON_CALL(gatt_interface, RegisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    /* default action for DeregisterForNotifications function call */
    ON_CALL(gatt_interface, DeregisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    /* default action for WriteDescriptor function call */
    ON_CALL(gatt_queue, WriteDescriptor(_, _, _, _, _, _))
            .WillByDefault(Invoke([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                                     tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb,
                                     void* cb_data) -> void {
              if (cb) {
                cb(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
              }
            }));
  }

  //    void send_audio_data() { }

  void start_audio_ticks() {}
  //
  //    void stop_audio_ticks() { }

  void SetUp(void) override {
    fake_osi_bool_props.clear();
    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    bluetooth::storage::SetMockBtifStorageInterface(&btif_storage_interface_);
    bluetooth::testing::stack::hearing_aid_audio_source::set_interface(
            &hearing_aid_audio_source_interface_);
    gatt::SetMockBtaGattInterface(&gatt_interface);
    gatt::SetMockBtaGattQueue(&gatt_queue);

    set_security_client_interface(mock_btm_security_);

    callbacks.reset(new MockHearingAidCallbacks());
    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    bluetooth::testing::stack::gap_conn::set_interface(&mock_stack_gap_conn_interface_);

    init_message_loop_thread();

    encryption_result = true;

    ON_CALL(mock_stack_l2cap_interface_, L2CA_UpdateBleConnParams(_, _, _, _, _, _, _))
            .WillByDefault(Invoke([&](const RawAddress& /*rem_bda*/, uint16_t min_int,
                                      uint16_t /*max_int*/, uint16_t latency, uint16_t timeout,
                                      uint16_t /*min_ce_len*/, uint16_t /*max_ce_len*/) {
              req_int = min_int;
              req_latency = latency;
              req_timeout = timeout;
              return true;
            }));

    ON_CALL(mock_stack_gap_conn_interface_, GAP_ConnOpen(_, _, _, _, _, _, _, _, _, _, _))
            .WillByDefault(DoAll(SaveArg<9>(&gap_conn_cb),
                                 Invoke([&](const char*, uint8_t, bool, const RawAddress* p_rem_bda,
                                            uint16_t, uint16_t, tL2CAP_CFG_INFO*, tL2CAP_ERTM_INFO*,
                                            uint16_t, tGAP_CONN_CALLBACK*,
                                            tBT_TRANSPORT) { return GetTestConnId(*p_rem_bda); })));

    ON_CALL(mock_stack_gap_conn_interface_, GAP_ConnGetRemoteAddr(_))
            .WillByDefault(
                    Invoke([&](uint16_t gap_handle) { return &connected_devices[gap_handle]; }));

    /* by default connect only direct connection requests */
    ON_CALL(gatt_interface, Open(_, _, _))
            .WillByDefault(Invoke([&](tGATT_IF /*client_if*/, const RawAddress& remote_bda,
                                      tBTM_BLE_CONN_TYPE connection_type) {
              if (connection_type == BTM_BLE_DIRECT_CONNECTION) {
                InjectConnectedEvent(remote_bda, GetTestConnId(remote_bda));
              }
            }));

    ON_CALL(gatt_interface, Close(_)).WillByDefault(Invoke([&](uint16_t conn_id) {
      /* We arrive here once, when we call Disconnect; and second time, after
       * we send OnGattDisconnected - but device was already removed */
      if (connected_devices.count(conn_id) > 0) {
        InjectDisconnectedEvent(conn_id);
      }
    }));

    ON_CALL(hearing_aid_audio_source_interface_, Start(_, _, _))
            .WillByDefault(Invoke(
                    [&](const CodecConfiguration& /*codec_config*/,
                        HearingAidAudioReceiver* audio_receiver,
                        uint16_t /*remote_delay_ms*/) { audio_receiver_ = audio_receiver; }));
  }

  void TearDown(void) override {
    services_map.clear();
    reset_mock_btm_client_interface();
    gatt::SetMockBtaGattQueue(nullptr);
    gatt::SetMockBtaGattInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
    bluetooth::hci::testing::mock_controller_.reset();
    bluetooth::testing::stack::l2cap::reset_interface();
    bluetooth::testing::stack::gap_conn::reset_interface();
    Mock::VerifyAndClearExpectations(&*callbacks);
    Mock::VerifyAndClearExpectations(&gatt_queue);
    Mock::VerifyAndClearExpectations(&gatt_interface);
    Mock::VerifyAndClearExpectations(&btm_interface);
    callbacks.reset();
    cleanup_message_loop_thread();
  }

  void InjectGapOpen(uint16_t gap_handle) {
    if (gap_conn_cb) {
      gap_conn_cb(gap_handle, GAP_EVT_CONN_OPENED, nullptr);
    }
  }

  void InjectConnectedEvent(const RawAddress& address, uint16_t conn_id,
                            tGATT_STATUS status = GATT_SUCCESS) {
    tBTA_GATTC_OPEN event_data = {
            .status = status,
            .conn_id = conn_id,
            .client_if = gatt_if,
            .remote_bda = address,
            .transport = BT_TRANSPORT_LE,
            .mtu = 240,
    };
    connected_devices[conn_id] = address;
    gatt_callback(BTA_GATTC_OPEN_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectConnUpdateEvent(uint16_t conn_id, uint16_t interval, uint16_t latency,
                             uint16_t timeout, tGATT_STATUS status = GATT_SUCCESS) {
    tBTA_GATTC_CONN_UPDATE event_data = {
            .conn_id = conn_id,
            .interval = interval,
            .latency = latency,
            .timeout = timeout,
            .status = status,
    };

    gatt_callback(BTA_GATTC_CONN_UPDATE_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectDisconnectedEvent(uint16_t conn_id,
                               tGATT_DISCONN_REASON reason = GATT_CONN_TERMINATE_LOCAL_HOST,
                               bool allow_fake_conn = false) {
    if (!allow_fake_conn) {
      ASSERT_NE(connected_devices.count(conn_id), 0u);
    }

    tBTA_GATTC_CLOSE event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
            .client_if = gatt_if,
            .remote_bda = connected_devices[conn_id],
            .reason = reason,
    };

    connected_devices.erase(conn_id);
    gatt_callback(BTA_GATTC_CLOSE_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectSearchCompleteEvent(uint16_t conn_id) {
    tBTA_GATTC_SEARCH_CMPL event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
    };

    gatt_callback(BTA_GATTC_SEARCH_CMPL_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectServiceChangedEvent(uint16_t conn_id) {
    tBTA_GATTC_SERVICE_CHANGED event_data = {.remote_bda = test_address, .conn_id = conn_id};

    gatt_callback(BTA_GATTC_SRVC_CHG_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectConnectionUpdateEvent(uint16_t conn_id) {
    tBTA_GATTC_CONN_UPDATE event_data = {
            .conn_id = conn_id,
            .interval = 0x0010  // CONNECTION_INTERVAL_20MS_PARAM
    };

    gatt_callback(BTA_GATTC_CONN_UPDATE_EVT, (tBTA_GATTC*)&event_data);
  }

  void InjectServiceSearchCompleteEvent() {
    tBTA_GATTC_SERVICE_DISCOVERY_DONE event = {.remote_bda = test_address};

    gatt_callback(BTA_GATTC_SRVC_DISC_DONE_EVT, (tBTA_GATTC*)&event);
  }

  void InjectNotificationEvent(const RawAddress& test_address, uint16_t conn_id, uint16_t handle,
                               std::vector<uint8_t> value, bool indicate = false) {
    tBTA_GATTC_NOTIFY event_data = {
            .conn_id = conn_id,
            .bda = test_address,
            .handle = handle,
            .len = (uint8_t)value.size(),
            .is_notify = !indicate,
    };

    ASSERT_TRUE(value.size() < GATT_MAX_ATTR_LEN);
    std::copy(value.begin(), value.end(), event_data.value);
    gatt_callback(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&event_data);
  }

  void SetEncryptionResult(const RawAddress& address, bool success) {
    encryption_result = success;

    ON_CALL(mock_btm_security_, BTM_IsEncrypted(address, _))
            .WillByDefault(Return(encryption_result));

    ON_CALL(mock_btm_security_, BTM_IsBonded(address, _)).WillByDefault(Return(true));
  }

  std::unique_ptr<MockHearingAidCallbacks> callbacks;
  bluetooth::manager::MockBtmInterface btm_interface;
  bluetooth::storage::MockBtifStorageInterface btif_storage_interface_;
  bluetooth::testing::stack::hearing_aid_audio_source::Mock hearing_aid_audio_source_interface_;
  gatt::MockBtaGattInterface gatt_interface;
  gatt::MockBtaGattQueue gatt_queue;
  tBTA_GATTC_CBACK* gatt_callback;
  const uint8_t gatt_if = 0xfe;
  std::map<uint8_t, RawAddress> connected_devices;
  std::map<uint16_t, std::list<gatt::Service>> services_map;
  std::map<uint16_t, uint8_t> device_capabilities_;
  bluetooth::testing::stack::l2cap::Mock mock_stack_l2cap_interface_;
  bluetooth::testing::stack::gap_conn::Mock mock_stack_gap_conn_interface_;
  NiceMock<MockSecurityClientInterface> mock_btm_security_;
  tGAP_CONN_CALLBACK* gap_conn_cb;
  uint16_t req_int;
  uint16_t req_latency;
  uint16_t req_timeout;
  bool encryption_result;
  const RawAddress test_address = GetTestAddress(1);
};

class HearingAidTest : public HearingAidTestBase {
  void SetUp(void) override {
    HearingAidTestBase::SetUp();
    BtaAppRegisterCallback app_register_callback;
    EXPECT_CALL(gatt_interface, AppRegister(_, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&gatt_callback),
                            WithArg<2>([&](auto arg) { app_register_callback = std::move(arg); })));
    HearingAid::Initialize(callbacks.get(), base::DoNothing());
    ASSERT_TRUE(gatt_callback);
    ASSERT_TRUE(app_register_callback);
    std::move(app_register_callback).Run(gatt_if, GATT_SUCCESS);
    ASSERT_TRUE(HearingAid::IsHearingAidRunning());
    Mock::VerifyAndClearExpectations(&gatt_interface);
  }
  void TearDown(void) override {
    EXPECT_CALL(gatt_interface, AppDeregister(gatt_if));
    if (HearingAid::IsHearingAidRunning()) {
      HearingAid::CleanUp();
    }
    ASSERT_FALSE(HearingAid::IsHearingAidRunning());
    gatt_callback = nullptr;
    HearingAidTestBase::TearDown();
  }
};

/* Test that hearing aid is initialized and cleaned up */
TEST_F(HearingAidTestBase, initialize) {
  ASSERT_FALSE(HearingAid::IsHearingAidRunning());
  HearingAid::Initialize(callbacks.get(), base::DoNothing());
  ASSERT_TRUE(HearingAid::IsHearingAidRunning());
  HearingAid::CleanUp();
  ASSERT_FALSE(HearingAid::IsHearingAidRunning());
}

/* Test that connect cancellation works */
TEST_F(HearingAidTest, disconnect_when_connecting) {
  /* Override the default action to prevent us sending the connected event */
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_DIRECT_CONNECTION))
          .WillOnce(Return());
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address)).Times(0);
  HearingAid::Connect(test_address);

  /* Single call from HearingAid:Disconnect*/
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address, _)).Times(AnyNumber());
  EXPECT_CALL(gatt_interface, Close(_)).Times(0);
  HearingAid::Disconnect(test_address);
}

/* Test that connect works and Connected state gets reported */
TEST_F(HearingAidTest, connect) {
  set_sample_database(1);

  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_DIRECT_CONNECTION));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address));
  ON_CALL(mock_btm_security_, BTM_IsEncrypted(test_address, _)).WillByDefault(Return(true));

  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);
}

/* Test that connected device can be disconnected */
TEST_F(HearingAidTest, disconnect_when_connected) {
  set_sample_database(1);

  ON_CALL(mock_btm_security_, BTM_IsEncrypted(test_address, _)).WillByDefault(Return(true));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address));
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  /* First call from HearingAid:Disconnect. Second call from OnGattDisconnected*/
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(2);
  EXPECT_CALL(gatt_interface, Close(_)).Times(2);
  HearingAid::Disconnect(test_address);
}

/* Test that bonded device that was loaded from storage refreshes GATT handles */
TEST_F(HearingAidTest, load_from_storage) {
  set_sample_database(1);
  SetEncryptionResult(test_address, true);
  const uint16_t conn_id = GetTestConnId(test_address);
  HearingDevice saved_dev;

  ON_CALL(btif_storage_interface_, AddHearingAid(_))
          .WillByDefault(Invoke([&](const HearingDevice* dev_info) { saved_dev = *dev_info; }));

  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_DIRECT_CONNECTION));
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  Mock::VerifyAndClearExpectations(&*callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&btm_interface);

  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address, _)).Times(AnyNumber());
  HearingAid::Disconnect(test_address);

  ON_CALL(btif_storage_interface_, GetHearingAidProp(_, _, _, _, _, _))
          .WillByDefault(Invoke([&](const RawAddress& /*address*/, uint8_t* capabilities,
                                    uint64_t* hi_sync_id, uint16_t* render_delay,
                                    uint16_t* preparation_delay, uint16_t* codecs) {
            *capabilities = saved_dev.capabilities;
            *hi_sync_id = saved_dev.hi_sync_id;
            *render_delay = saved_dev.render_delay;
            *preparation_delay = saved_dev.preparation_delay;
            *codecs = saved_dev.codecs;
            return true;
          }));

  Mock::VerifyAndClearExpectations(&gatt_interface);

  ON_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_BKG_CONNECT_ALLOW_LIST))
          .WillByDefault(Invoke([&](tGATT_IF, const RawAddress& remote_bda, tBTM_BLE_CONN_TYPE) {
            InjectConnectedEvent(remote_bda, conn_id);
          }));

  EXPECT_CALL(gatt_interface, ServiceSearchRequest).Times(1);
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_BKG_CONNECT_ALLOW_LIST));
  HearingAid::AddFromStorage(saved_dev, true);
}

/* 1. Hearing aid gets connected.
 * 2. Volume is set.
 * 3. Audio stream starts.
 *
 * Check if all GATT operations were executed.
 */
TEST_F(HearingAidTest, start_stream) {
  set_sample_database(1);
  SetEncryptionResult(test_address, true);

  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, ServiceSearchRequest);
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kLePsm, _, _));
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kReadOnlyProperties, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _))
          .Times(AnyNumber());
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kVolume, _, _, _, _));

  HearingAid::SetVolume(20);
  /* Simulate AF sending Audio Resume */
  auto start_dummy_ticks = []() { log::info("start_audio_ticks: waiting for data path opened"); };
  do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioResume,
                                   base::Unretained(audio_receiver_), start_dummy_ticks));
  SyncOnMainLoop();
}

/* 1. Hearing aid gets connected.
 * 2. Service changed event is received
 * 3. Connection Update event is received
 *    Check if write to AudioControlPoint was executed, using old handle.
 */
TEST_F(HearingAidTest, conn_update_after_service_changed) {
  set_sample_database(1);
  SetEncryptionResult(test_address, true);

  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, ServiceSearchRequest);
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kLePsm, _, _));
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kReadOnlyProperties, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(AtLeast(1));
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  InjectServiceChangedEvent(1);
  InjectConnectionUpdateEvent(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(0);
  SyncOnMainLoop();
}

/* 1. Hearing aid gets connected.
 * 2. Service changed event is received
 * 3. Stream start is requested
 * 4. Volume is set
 *    Check if GATT operations were not executed after service changed event.
 * 5. Service search complete event arrives
 *    Check if write to AudioControlPoint was executed.
 * 6. Second Service changed event is received
 * 7. Stream is suspended
 *    Check if write to AudioControlPoint was executed, using old handle.
 */
TEST_F(HearingAidTest, service_changed_before_stream_start_gatt_omitted_after_svc_changed) {
  set_sample_database(1);
  SetEncryptionResult(test_address, true);

  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, ServiceSearchRequest);
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kLePsm, _, _));
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kReadOnlyProperties, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(AtLeast(1));
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(0);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kVolume, _, _, _, _)).Times(0);

  InjectServiceChangedEvent(1);

  /* Simulate AF sending Audio Resume */
  auto start_dummy_ticks = []() { log::info("start_audio_ticks: waiting for data path opened"); };
  do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioResume,
                                   base::Unretained(audio_receiver_), start_dummy_ticks));
  HearingAid::SetVolume(20);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(AtLeast(0));

  InjectServiceSearchCompleteEvent();

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kVolume, _, _, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _));
  /* Simulate AF sending Audio Suspend */
  do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioSuspend,
                                   base::Unretained(audio_receiver_), start_dummy_ticks));
  /* Simulate AF sending Audio Resume */
  do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioResume,
                                   base::Unretained(audio_receiver_), start_dummy_ticks));
  HearingAid::SetVolume(20);
  SyncOnMainLoop();
}

/* 1. Hearing aid gets connected.
 * 2. Service changed event is received
 * 3. Connection Update event is received
 *    Check if write to AudioControlPoint was not executed.
 */
TEST_F(HearingAidTest, conn_update_after_service_changed_gatt_omitted_after_svc_changed) {
  set_sample_database(1);
  SetEncryptionResult(test_address, true);

  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address)).Times(1);
  EXPECT_CALL(gatt_interface, ServiceSearchRequest);
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kLePsm, _, _));
  EXPECT_CALL(gatt_queue, ReadCharacteristic(1, kReadOnlyProperties, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(AtLeast(1));
  HearingAid::Connect(test_address);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  Mock::VerifyAndClearExpectations(callbacks.get());
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&gatt_queue);

  InjectServiceChangedEvent(1);
  InjectConnectionUpdateEvent(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(1, kAudioControlPoint, _, _, _, _)).Times(0);
  SyncOnMainLoop();
}

/* Test that if second of two devices fails to reconnect, reconnection is attempted */
TEST_F(HearingAidTest, reconnect_first_success_second_fail) {
  set_com_android_bluetooth_flags_asha_retry_reconnect_when_in_set(true);
  const RawAddress test_address1 = GetTestAddress(1);
  const RawAddress test_address2 = GetTestAddress(2);
  const uint16_t conn_id1 = GetTestConnId(test_address1);
  const uint16_t conn_id2 = GetTestConnId(test_address2);
  HearingDevice saved_dev1;
  HearingDevice saved_dev2;
  device_capabilities_[GetTestConnId(GetTestAddress(1))] = 0x06;  // left
  device_capabilities_[GetTestConnId(GetTestAddress(2))] = 0x07;  // right

  set_sample_database(conn_id1);
  set_sample_database(conn_id2);
  SetEncryptionResult(test_address1, true);
  SetEncryptionResult(test_address2, true);

  ON_CALL(btif_storage_interface_, AddHearingAid(_))
          .WillByDefault(Invoke([&](const HearingDevice* dev_info) {
            if (dev_info->address == test_address1) {
              saved_dev1 = *dev_info;
            } else {
              saved_dev2 = *dev_info;
            }
          }));

  /* First device connects successfully */
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address1));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address1));
  HearingAid::Connect(test_address1);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  /* Second device connects successfully */
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_DIRECT_CONNECTION));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address2));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address2));
  HearingAid::Connect(test_address2);
  InjectGapOpen(2);
  InjectConnectionUpdateEvent(2);

  Mock::VerifyAndClearExpectations(&*callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&btm_interface);

  /* Disconnect both devices */
  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address1, _)).Times(AnyNumber());
  HearingAid::Disconnect(test_address1);
  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address2, _)).Times(AnyNumber());
  HearingAid::Disconnect(test_address2);

  ON_CALL(btif_storage_interface_, GetHearingAidProp(_, _, _, _, _, _))
          .WillByDefault(Invoke([&](const RawAddress& address, uint8_t* capabilities,
                                    uint64_t* hi_sync_id, uint16_t* render_delay,
                                    uint16_t* preparation_delay, uint16_t* codecs) {
            HearingDevice* restored_dev;
            if (address == test_address1) {
              restored_dev = &saved_dev1;
            } else {
              restored_dev = &saved_dev2;
            }
            *capabilities = restored_dev->capabilities;
            *hi_sync_id = restored_dev->hi_sync_id;
            *render_delay = restored_dev->render_delay;
            *preparation_delay = restored_dev->preparation_delay;
            *codecs = restored_dev->codecs;
            return true;
          }));

  Mock::VerifyAndClearExpectations(&*callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&btm_interface);

  /* Add both devices froms storage. Second device fails to connect. Verify connection retry. */
  ON_CALL(gatt_interface, Open(gatt_if, _, BTM_BLE_BKG_CONNECT_ALLOW_LIST)).WillByDefault(Return());
  ON_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_DIRECT_CONNECTION))
          .WillByDefault(Invoke([&](tGATT_IF, const RawAddress& remote_bda, tBTM_BLE_CONN_TYPE) {
            InjectConnectedEvent(remote_bda, conn_id2, GATT_ERROR);
          }));
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_ALLOW_LIST));
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_BKG_CONNECT_ALLOW_LIST))
          .Times(2);
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_DIRECT_CONNECTION));

  HearingAid::AddFromStorage(saved_dev1, true);
  HearingAid::AddFromStorage(saved_dev2, true);
  InjectConnectedEvent(test_address1, conn_id1);
  InjectGapOpen(1);
}

/* Test that if first of two devices fails to reconnect, reconnection is attempted after
 * the second one connects successfully */
TEST_F(HearingAidTest, reconnect_first_fail_second_success) {
  set_com_android_bluetooth_flags_asha_retry_reconnect_when_in_set(true);
  const RawAddress test_address1 = GetTestAddress(1);
  const RawAddress test_address2 = GetTestAddress(2);
  const uint16_t conn_id1 = GetTestConnId(test_address1);
  const uint16_t conn_id2 = GetTestConnId(test_address2);
  HearingDevice saved_dev1;
  HearingDevice saved_dev2;
  device_capabilities_[GetTestConnId(GetTestAddress(1))] = 0x06;  // left
  device_capabilities_[GetTestConnId(GetTestAddress(2))] = 0x07;  // right

  set_sample_database(conn_id1);
  set_sample_database(conn_id2);
  SetEncryptionResult(test_address1, true);
  SetEncryptionResult(test_address2, true);

  ON_CALL(btif_storage_interface_, AddHearingAid(_))
          .WillByDefault(Invoke([&](const HearingDevice* dev_info) {
            if (dev_info->address == test_address1) {
              saved_dev1 = *dev_info;
            } else {
              saved_dev2 = *dev_info;
            }
          }));

  /* First device connects successfully */
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address1));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address1));
  HearingAid::Connect(test_address1);
  InjectGapOpen(1);
  InjectConnectionUpdateEvent(1);

  /* Second device connects successfully */
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_DIRECT_CONNECTION));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address2));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address2));
  HearingAid::Connect(test_address2);
  InjectGapOpen(2);
  InjectConnectionUpdateEvent(2);

  Mock::VerifyAndClearExpectations(&*callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&btm_interface);

  /* Disconnect both devices */
  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address1, _)).Times(AnyNumber());
  HearingAid::Disconnect(test_address1);
  EXPECT_CALL(gatt_interface, CancelOpen(_, test_address2, _)).Times(AnyNumber());
  HearingAid::Disconnect(test_address2);

  ON_CALL(btif_storage_interface_, GetHearingAidProp(_, _, _, _, _, _))
          .WillByDefault(Invoke([&](const RawAddress& address, uint8_t* capabilities,
                                    uint64_t* hi_sync_id, uint16_t* render_delay,
                                    uint16_t* preparation_delay, uint16_t* codecs) {
            HearingDevice* restored_dev;
            if (address == test_address1) {
              restored_dev = &saved_dev1;
            } else {
              restored_dev = &saved_dev2;
            }
            *capabilities = restored_dev->capabilities;
            *hi_sync_id = restored_dev->hi_sync_id;
            *render_delay = restored_dev->render_delay;
            *preparation_delay = restored_dev->preparation_delay;
            *codecs = restored_dev->codecs;
            return true;
          }));

  Mock::VerifyAndClearExpectations(&*callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  Mock::VerifyAndClearExpectations(&btm_interface);

  /* Add both devices froms storage. Second device fails to connect. Verify connection retry. */
  ON_CALL(gatt_interface, Open(gatt_if, _, BTM_BLE_BKG_CONNECT_ALLOW_LIST)).WillByDefault(Return());
  ON_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION))
          .WillByDefault(Invoke([&](tGATT_IF, const RawAddress& remote_bda, tBTM_BLE_CONN_TYPE) {
            InjectConnectedEvent(remote_bda, conn_id2, GATT_ERROR);
          }));
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address2, BTM_BLE_BKG_CONNECT_ALLOW_LIST));
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_ALLOW_LIST))
          .Times(2);
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION));

  HearingAid::AddFromStorage(saved_dev1, true);
  HearingAid::AddFromStorage(saved_dev2, true);
  InjectConnectedEvent(test_address2, conn_id1);
  InjectGapOpen(1);
}

}  // namespace
}  // namespace bluetooth::asha
