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
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>
#include <osi/include/alarm.h>
#include <string.h>
#include <sys/socket.h>

#include <variant>

#include "bta/le_audio/le_audio_types.h"
#include "bta_gatt_api_mock.h"
#include "bta_gatt_queue_mock.h"
#include "bta_hearing_aid_api.h"
#include "btif_storage_mock.h"
#include "btm_api_mock.h"
#include "gatt/database_builder.h"
#include "hardware/bt_gatt_types.h"
#include "hci/controller_interface_mock.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_status.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_stack_gap_conn_interface.h"
#include "test/mock/mock_stack_l2cap_interface.h"
#include "types/bt_transport.h"

static std::map<const char*, bool> fake_osi_bool_props;

namespace bluetooth {
namespace hearing_aid {
namespace internal {
namespace {

using base::HexEncode;

using namespace bluetooth::hearing_aid;

using ::bluetooth::hearing_aid::ConnectionState;
using ::bluetooth::hearing_aid::HearingAidCallbacks;
using ::bluetooth::hearing_aid::HearingAidInterface;

using ::testing::_;
using ::testing::AnyNumber;
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

RawAddress GetTestAddress(int index) {
  CHECK_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)}};
  return result;
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
  Uuid HEARING_AID_UUID = Uuid::FromString("FDF0");
  Uuid READ_ONLY_PROPERTIES_UUID = Uuid::FromString("6333651e-c481-4a3e-9169-7c902aad37bb");
  Uuid AUDIO_CONTROL_POINT_UUID = Uuid::FromString("f0d4de7e-4a88-476c-9d9f-1937b0996cc0");
  Uuid AUDIO_STATUS_UUID = Uuid::FromString("38663f1a-e711-4cac-b641-326b56404837");
  Uuid VOLUME_UUID = Uuid::FromString("00e4ca9e-ab14-41e4-8823-f9e70c7e91df");
  Uuid LE_PSM_UUID = Uuid::FromString("2d410339-82b6-42aa-b34e-e2e01df8cc1a");

  void set_sample_database(uint16_t conn_id) {
    static constexpr uint16_t kGapSvcStartHdl = 0x0001;
    static constexpr uint16_t kGapDeviceNameValHdl = 0x0003;
    static constexpr uint16_t kGapSvcEndHdl = kGapDeviceNameValHdl;

    static constexpr uint16_t kSvcStartHdl = 0x0010;
    static constexpr uint16_t kReadOnlyProperties = 0x0012;
    static constexpr uint16_t kAudioControlPoint = 0x0015;
    static constexpr uint16_t kAudioStatusPoint = 0x0018;
    static constexpr uint16_t kVolume = 0x001B;
    static constexpr uint16_t kLePsm = 0x001E;
    static constexpr uint16_t kSvcEndHdl = kLePsm;

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
                          value.assign(17, 0x01);
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

    ON_CALL(gatt_interface, ServiceSearchRequest(_, _))
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

  void SetUp(void) override {
    fake_osi_bool_props.clear();
    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    bluetooth::storage::SetMockBtifStorageInterface(&btif_storage_interface_);
    gatt::SetMockBtaGattInterface(&gatt_interface);
    gatt::SetMockBtaGattQueue(&gatt_queue);
    callbacks.reset(new MockHearingAidCallbacks());
    bluetooth::hci::testing::mock_controller_ =
        std::make_unique<NiceMock<bluetooth::hci::testing::MockControllerInterface>>();
    bluetooth::testing::stack::l2cap::set_interface(&mock_stack_l2cap_interface_);
    bluetooth::testing::stack::gap_conn::set_interface(&mock_stack_gap_conn_interface_);

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
            .WillByDefault(Invoke([&](const char* /* p_serv_name */, uint8_t /*service_id*/,
                                      bool /*is_server*/, const RawAddress* p_rem_bda,
                                      uint16_t /*psm*/, uint16_t /*le_mps*/,
                                      tL2CAP_CFG_INFO* /*p_cfg*/, tL2CAP_ERTM_INFO* /*ertm_info*/,
                                      uint16_t /*security*/, tGAP_CONN_CALLBACK* p_cb,
                                      tBT_TRANSPORT /*transport*/) {
              InjectConnUpdateEvent(p_rem_bda->address[5], req_int, req_latency, req_timeout);

              gap_conn_cb = p_cb;
              if (gap_conn_cb) {
                gap_conn_cb(0xFFFF, GAP_EVT_CONN_OPENED, nullptr);
              }
              return 1;
            }));

    ON_CALL(mock_stack_gap_conn_interface_, GAP_ConnGetRemoteAddr(_))
            .WillByDefault(Invoke([&](uint16_t /*gap_handle*/) { return &test_address; }));

    /* by default connect only direct connection requests */
    ON_CALL(gatt_interface, Open(_, _, _, _))
            .WillByDefault(Invoke([&](tGATT_IF /*client_if*/, const RawAddress& remote_bda,
                                      tBTM_BLE_CONN_TYPE connection_type, bool /*opportunistic*/) {
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
  }

  void TearDown(void) override {
    services_map.clear();
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

    ON_CALL(btm_interface, BTM_IsEncrypted(address, _)).WillByDefault(Return(encryption_result));

    ON_CALL(btm_interface, IsDeviceBonded(address, _)).WillByDefault(Return(true));
  }

  std::unique_ptr<MockHearingAidCallbacks> callbacks;
  bluetooth::manager::MockBtmInterface btm_interface;
  bluetooth::storage::MockBtifStorageInterface btif_storage_interface_;
  gatt::MockBtaGattInterface gatt_interface;
  gatt::MockBtaGattQueue gatt_queue;
  tBTA_GATTC_CBACK* gatt_callback;
  const uint8_t gatt_if = 0xfe;
  std::map<uint8_t, RawAddress> connected_devices;
  std::map<uint16_t, std::list<gatt::Service>> services_map;
  bluetooth::testing::stack::l2cap::Mock mock_stack_l2cap_interface_;
  bluetooth::testing::stack::gap_conn::Mock mock_stack_gap_conn_interface_;
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
            .WillOnce(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
    HearingAid::Initialize(callbacks.get(), base::DoNothing());
    ASSERT_TRUE(gatt_callback);
    ASSERT_TRUE(app_register_callback);
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
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
  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_DIRECT_CONNECTION, _))
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

  EXPECT_CALL(gatt_interface, Open(gatt_if, test_address, BTM_BLE_DIRECT_CONNECTION, _));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address));
  ON_CALL(btm_interface, BTM_IsEncrypted(test_address, _)).WillByDefault(Return(true));

  HearingAid::Connect(test_address);
}

/* Test that connected device can be disconnected */
TEST_F(HearingAidTest, disconnect_when_connected) {
  set_sample_database(1);

  ON_CALL(btm_interface, BTM_IsEncrypted(test_address, _)).WillByDefault(Return(true));
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);
  EXPECT_CALL(*callbacks, OnDeviceAvailable(_, _, test_address));
  HearingAid::Connect(test_address);

  /* First call from HearingAid:Disconnect. Second call from OnGattDisconnected*/
  EXPECT_CALL(*callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(2);
  EXPECT_CALL(gatt_interface, Close(_)).Times(2);
  HearingAid::Disconnect(test_address);
}

}  // namespace
}  // namespace internal
}  // namespace hearing_aid
}  // namespace bluetooth
