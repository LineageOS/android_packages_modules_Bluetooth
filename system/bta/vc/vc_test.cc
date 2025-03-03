/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <aics/api.h>
#include <base/functional/bind.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include "bta/include/bta_vc_api.h"
#include "bta/test/common/bta_gatt_api_mock.h"
#include "bta/test/common/bta_gatt_queue_mock.h"
#include "bta/test/common/btm_api_mock.h"
#include "bta/test/common/mock_csis_client.h"
#include "bta/vc/types.h"
#include "gatt/database_builder.h"
#include "hardware/bt_gatt_types.h"
#include "include/bind_helpers.h"
#include "osi/test/alarm_mock.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_status.h"
#include "test/common/mock_functions.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

struct alarm_t {
  alarm_callback_t cb = nullptr;
  void* data = nullptr;
  bool on_main_loop = false;
};

using ::testing::NiceMock;

namespace bluetooth {
namespace vc {
namespace internal {
namespace {

using base::Bind;
using base::Unretained;

using bluetooth::aics::GainMode;
using bluetooth::aics::Mute;
using bluetooth::vc::ConnectionState;
using bluetooth::vc::VolumeControlCallbacks;

using testing::_;
using testing::DoAll;
using testing::DoDefault;
using testing::Invoke;
using testing::Mock;
using testing::NotNull;
using testing::Return;
using testing::SaveArg;
using testing::SetArgPointee;
using testing::WithArg;

static RawAddress GetTestAddress(int index) {
  EXPECT_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, static_cast<uint8_t>(index)}};
  return result;
}

class MockVolumeControlCallbacks : public VolumeControlCallbacks {
public:
  MockVolumeControlCallbacks() = default;
  MockVolumeControlCallbacks(const MockVolumeControlCallbacks&) = delete;
  MockVolumeControlCallbacks& operator=(const MockVolumeControlCallbacks&) = delete;

  ~MockVolumeControlCallbacks() override = default;

  MOCK_METHOD((void), OnConnectionState, (ConnectionState state, const RawAddress& address),
              (override));
  MOCK_METHOD((void), OnDeviceAvailable,
              (const RawAddress& address, uint8_t num_offset, uint8_t num_inputs), (override));
  MOCK_METHOD((void), OnVolumeStateChanged,
              (const RawAddress& address, uint8_t volume, bool mute, uint8_t flags,
               bool isAutonomous),
              (override));
  MOCK_METHOD((void), OnGroupVolumeStateChanged,
              (int group_id, uint8_t volume, bool mute, bool isAutonomous), (override));
  MOCK_METHOD((void), OnExtAudioOutVolumeOffsetChanged,
              (const RawAddress& address, uint8_t ext_output_id, int16_t offset), (override));
  MOCK_METHOD((void), OnExtAudioOutLocationChanged,
              (const RawAddress& address, uint8_t ext_output_id, uint32_t location), (override));
  MOCK_METHOD((void), OnExtAudioOutDescriptionChanged,
              (const RawAddress& address, uint8_t ext_output_id, std::string descr), (override));
  MOCK_METHOD((void), OnExtAudioInStateChanged,
              (const RawAddress& address, uint8_t ext_input_id, int8_t gain_setting, Mute mute,
               GainMode gain_mode),
              (override));
  MOCK_METHOD((void), OnExtAudioInSetGainSettingFailed,
              (const RawAddress& address, uint8_t ext_input_id), (override));
  MOCK_METHOD((void), OnExtAudioInSetMuteFailed, (const RawAddress& address, uint8_t ext_input_id),
              (override));
  MOCK_METHOD((void), OnExtAudioInSetGainModeFailed,
              (const RawAddress& address, uint8_t ext_input_id), (override));
  MOCK_METHOD((void), OnExtAudioInStatusChanged,
              (const RawAddress& address, uint8_t ext_input_id, VolumeInputStatus status),
              (override));
  MOCK_METHOD((void), OnExtAudioInTypeChanged,
              (const RawAddress& address, uint8_t ext_input_id, VolumeInputType type), (override));
  MOCK_METHOD((void), OnExtAudioInGainSettingPropertiesChanged,
              (const RawAddress& address, uint8_t ext_input_id, uint8_t unit, int8_t min,
               int8_t max),
              (override));
  MOCK_METHOD((void), OnExtAudioInDescriptionChanged,
              (const RawAddress& address, uint8_t ext_input_id, std::string description,
               bool is_writable),
              (override));
};

class VolumeControlTest : public ::testing::Test {
private:
  void set_sample_database(uint16_t conn_id, bool vcs, bool vcs_broken, bool aics, bool aics_broken,
                           bool vocs, bool vocs_broken) {
    gatt::DatabaseBuilder builder;
    builder.AddService(0x0001, 0x0003, Uuid::From16Bit(0x1800), true);
    builder.AddCharacteristic(0x0002, 0x0003, Uuid::From16Bit(0x2a00), GATT_CHAR_PROP_BIT_READ);
    /* 0x0004-0x000f RFU */
    if (vcs) {
      /* VCS */
      builder.AddService(0x0010, 0x0026, kVolumeControlUuid, true);
      if (aics) {
        builder.AddIncludedService(0x0011, kVolumeAudioInputUuid, 0x0030, 0x003e);
        builder.AddIncludedService(0x0012, kVolumeAudioInputUuid, 0x0050, 0x005f);
      }
      if (vocs) {
        builder.AddIncludedService(0x0013, kVolumeOffsetUuid, 0x0070, 0x0079);
        builder.AddIncludedService(0x0014, kVolumeOffsetUuid, 0x0080, 0x008b);
      }
      /* 0x0015-0x001f RFU */
      builder.AddCharacteristic(0x0020, 0x0021, kVolumeControlStateUuid,
                                GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
      builder.AddDescriptor(0x0022, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
      if (!vcs_broken) {
        builder.AddCharacteristic(0x0023, 0x0024, kVolumeControlPointUuid,
                                  GATT_CHAR_PROP_BIT_WRITE);
      }
      builder.AddCharacteristic(0x0025, 0x0026, kVolumeFlagsUuid, GATT_CHAR_PROP_BIT_READ);
      /* 0x0027-0x002f RFU */
      if (aics) {
        /* AICS 1st instance */
        builder.AddService(0x0030, 0x003e, kVolumeAudioInputUuid, false);
        builder.AddCharacteristic(0x0031, 0x0032, kVolumeAudioInputStateUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        builder.AddDescriptor(0x0033, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        builder.AddCharacteristic(0x0034, 0x0035, kVolumeAudioInputGainSettingPropertiesUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        builder.AddCharacteristic(0x0036, 0x0037, kVolumeAudioInputTypeUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        builder.AddCharacteristic(0x0038, 0x0039, kVolumeAudioInputStatusUuid,
                                  GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x003a, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        builder.AddCharacteristic(0x003b, 0x003c, kVolumeAudioInputControlPointUuid,
                                  GATT_CHAR_PROP_BIT_WRITE);
        builder.AddCharacteristic(0x003d, 0x003e, kVolumeAudioInputDescriptionUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        /* 0x003f-0x004f RFU */

        /* AICS 2nd instance */
        builder.AddService(0x0050, 0x005f, kVolumeAudioInputUuid, false);
        builder.AddCharacteristic(0x0051, 0x0052, kVolumeAudioInputStateUuid,
                                  GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x0053, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        if (!aics_broken) {
          builder.AddCharacteristic(0x0054, 0x0055, kVolumeAudioInputGainSettingPropertiesUuid,
                                    GATT_CHAR_PROP_BIT_READ);
        }
        builder.AddCharacteristic(0x0056, 0x0057, kVolumeAudioInputTypeUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        builder.AddCharacteristic(0x0058, 0x0059, kVolumeAudioInputStatusUuid,
                                  GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x005a, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        builder.AddCharacteristic(0x005b, 0x005c, kVolumeAudioInputControlPointUuid,
                                  GATT_CHAR_PROP_BIT_WRITE);
        builder.AddCharacteristic(
                0x005d, 0x005e, kVolumeAudioInputDescriptionUuid,
                GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x005f, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        /* 0x0060-0x006f RFU */
      }
      if (vocs) {
        /* VOCS 1st instance */
        builder.AddService(0x0070, 0x0079, kVolumeOffsetUuid, false);
        builder.AddCharacteristic(0x0071, 0x0072, kVolumeOffsetStateUuid,
                                  GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x0073, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        builder.AddCharacteristic(0x0074, 0x0075, kVolumeOffsetLocationUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        builder.AddCharacteristic(0x0076, 0x0077, kVolumeOffsetControlPointUuid,
                                  GATT_CHAR_PROP_BIT_WRITE);
        builder.AddCharacteristic(0x0078, 0x0079, kVolumeOffsetOutputDescriptionUuid,
                                  GATT_CHAR_PROP_BIT_READ);
        /* 0x007a-0x007f RFU */

        /* VOCS 2nd instance */
        builder.AddService(0x0080, 0x008b, kVolumeOffsetUuid, false);
        builder.AddCharacteristic(0x0081, 0x0082, kVolumeOffsetStateUuid,
                                  GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x0083, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        if (!vocs_broken) {
          builder.AddCharacteristic(0x0084, 0x0085, kVolumeOffsetLocationUuid,
                                    GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE_NR |
                                            GATT_CHAR_PROP_BIT_NOTIFY);
          builder.AddDescriptor(0x0086, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
        builder.AddCharacteristic(0x0087, 0x0088, kVolumeOffsetControlPointUuid,
                                  GATT_CHAR_PROP_BIT_WRITE);
        builder.AddCharacteristic(
                0x0089, 0x008a, kVolumeOffsetOutputDescriptionUuid,
                GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_NOTIFY);
        builder.AddDescriptor(0x008b, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
      }
    }
    /* 0x008c-0x008f RFU */

    /* GATTS */
    builder.AddService(0x0090, 0x0093, Uuid::From16Bit(UUID_SERVCLASS_GATT_SERVER), true);
    builder.AddCharacteristic(0x0091, 0x0092, Uuid::From16Bit(GATT_UUID_GATT_SRV_CHGD),
                              GATT_CHAR_PROP_BIT_NOTIFY);
    builder.AddDescriptor(0x0093, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
    services_map[conn_id] = builder.Build().Services();

    ON_CALL(gatt_queue, ReadCharacteristic(conn_id, _, _, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, uint16_t handle, GATT_READ_OP_CB cb,
                                      void* cb_data) -> void {
              std::vector<uint8_t> value;

              switch (handle) {
                case 0x0003:
                  /* device name */
                  value.resize(20);
                  break;

                case 0x0021:
                  /* volume state */
                  value.resize(3);
                  break;

                case 0x0026:
                  /* volume flags */
                  value.resize(1);
                  break;

                case 0x0032:  // 1st AICS instance
                case 0x0052:  // 2nd AICS instance
                  /* audio input state */
                  value.resize(4);
                  break;

                case 0x0035:  // 1st AICS instance
                case 0x0055:  // 2nd AICS instance
                  /* audio input gain settings */
                  value.resize(3);
                  break;

                case 0x0037:  // 1st AICS instance
                case 0x0057:  // 2nd AICS instance
                  /* audio input type */
                  value.resize(1);
                  break;

                case 0x0039:  // 1st AICS instance
                case 0x0059:  // 2nd AICS instance
                  /* audio input status */
                  value.resize(1);
                  break;

                case 0x003e:  // 1st AICS instance
                case 0x005e:  // 2nd AICS instance
                  /* audio input description */
                  value.resize(14);
                  break;

                case 0x0072:  // 1st VOCS instance
                case 0x0082:  // 2nd VOCS instance
                  /* offset state */
                  value.resize(3);
                  break;

                case 0x0075:  // 1st VOCS instance
                case 0x0085:  // 2nd VOCS instance
                  /* offset location */
                  value.resize(4);
                  break;

                case 0x0079:  // 1st VOCS instance
                case 0x008a:  // 2nd VOCS instance
                  /* offset output description */
                  value.resize(10);
                  break;

                default:
                  FAIL();
                  return;
              }

              if (do_not_respond_to_reads) {
                return;
              }
              cb(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
            }));

    ON_CALL(gatt_queue, ReadMultiCharacteristic(conn_id, _, _, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, tBTA_GATTC_MULTI& handles,
                                      GATT_READ_MULTI_OP_CB cb, void* cb_data) -> void {
              std::vector<uint8_t> value;

              auto add_element = [&](uint8_t data[], uint8_t len) -> void {
                // LE order, 2 octets
                value.push_back(len);
                value.push_back(0x00);

                uint8_t* p = &data[0];
                for (size_t i = 0; i < len; i++) {
                  value.push_back(*p++);
                }
              };

              for (size_t i = 0; i < handles.num_attr; i++) {
                switch (handles.handles[i]) {
                  case 0x0003: {
                    /* device name */
                    uint8_t name[] = "UnknownName";
                    add_element(name, sizeof(name));
                    break;
                  }
                  case 0x0021: {
                    /* state */
                    uint8_t state[3] = {0x00, 0x00, 0x00};
                    add_element(state, sizeof(state));
                    break;
                  }
                  case 0x0026: {
                    /* volume flags */
                    uint8_t flags[] = {0x01};
                    add_element(flags, sizeof(flags));
                    break;
                  }
                  case 0x0032:  // 1st AICS instance
                  case 0x0052:  // 2nd AICS instance
                  {
                    /* audio input state */
                    uint8_t state[4] = {0x01, 0x01, 0x01, 0x00};
                    add_element(state, sizeof(state));
                    break;
                  }
                  case 0x0035:  // 1st AICS instance
                  case 0x0055:  // 2nd AICS instance
                  {
                    /* audio input gain settings */
                    uint8_t gain_settings[3] = {0x01, 0x01, 0x01};
                    add_element(gain_settings, sizeof(gain_settings));
                    break;
                  }
                  case 0x0037:  // 1st AICS instance
                  case 0x0057:  // 2nd AICS instance
                  {
                    /* audio input type */
                    uint8_t type[] = {0x01};
                    add_element(type, sizeof(type));
                    break;
                  }
                  case 0x0039:  // 1st AICS instance
                  case 0x0059:  // 2nd AICS instance
                  {
                    /* audio input status */
                    uint8_t status[] = {0x00};
                    add_element(status, sizeof(status));
                    break;
                  }
                  case 0x003e:  // 1st AICS instance
                  case 0x005e:  // 2nd AICS instance
                  {
                    /* audio input description */
                    uint8_t dest[] = "input";
                    add_element(dest, sizeof(dest));
                    break;
                  }
                  case 0x0072:  // 1st VOCS instance
                  case 0x0082:  // 2nd VOCS instance
                  {
                    /* offset state */
                    uint8_t state[3] = {0x00, 0x20, 0x00};
                    add_element(state, sizeof(state));
                    break;
                  }
                  case 0x0075:  // 1st VOCS instance
                  case 0x0085:  // 2nd VOCS instance
                  {
                    /* offset location */
                    uint8_t location[4] = {0x00, 0x02, 0x00, 0x01};
                    add_element(location, sizeof(location));
                    break;
                  }
                  case 0x0079:  // 1st VOCS instance
                  case 0x008a:  // 2nd VOCS instance
                  {
                    /* offset output description */
                    uint8_t dest[] = "VOCS_D";
                    add_element(dest, sizeof(dest));
                    break;
                  }
                  default:
                    FAIL();
                    return;
                }
              }

              if (do_not_respond_to_reads) {
                return;
              }
              cb(conn_id, GATT_SUCCESS, handles, value.size(), value.data(), cb_data);
            }));
  }

protected:
  bool do_not_respond_to_reads = false;

  void SetUp(void) override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com::android::bluetooth::flags::provider_->reset_flags();

    com::android::bluetooth::flags::provider_->leaudio_add_aics_support(true);

    bluetooth::manager::SetMockBtmInterface(&btm_interface);
    MockCsisClient::SetMockInstanceForTesting(&mock_csis_client_module_);
    gatt::SetMockBtaGattInterface(&gatt_interface);
    gatt::SetMockBtaGattQueue(&gatt_queue);
    reset_mock_function_count_map();

    ON_CALL(btm_interface, IsDeviceBonded(_, _)).WillByDefault(DoAll(Return(true)));

    // default action for GetCharacteristic function call
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

    // default action for GetOwningService function call
    ON_CALL(gatt_interface, GetOwningService(_, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, uint16_t handle) -> const gatt::Service* {
              std::list<gatt::Service>& services = services_map[conn_id];
              for (auto const& service : services) {
                if (service.handle <= handle && service.end_handle >= handle) {
                  return &service;
                }
              }

              return nullptr;
            }));

    // default action for GetServices function call
    ON_CALL(gatt_interface, GetServices(_))
            .WillByDefault(WithArg<0>(Invoke([&](uint16_t conn_id) -> std::list<gatt::Service>* {
              return &services_map[conn_id];
            })));

    // default action for RegisterForNotifications function call
    ON_CALL(gatt_interface, RegisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    // default action for DeregisterForNotifications function call
    ON_CALL(gatt_interface, DeregisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    // default action for WriteDescriptor function call
    ON_CALL(gatt_queue, WriteDescriptor(_, _, _, _, _, _))
            .WillByDefault(Invoke([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                                     tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb,
                                     void* cb_data) -> void {
              if (cb) {
                cb(conn_id, GATT_SUCCESS, handle, value.size(), value.data(), cb_data);
              }
            }));
    auto mock_alarm = AlarmMock::Get();
    ON_CALL(*mock_alarm, AlarmNew(_)).WillByDefault(Invoke([](const char* /*name*/) {
      return new alarm_t();
    }));
    ON_CALL(*mock_alarm, AlarmFree(_)).WillByDefault(Invoke([](alarm_t* alarm) {
      if (alarm) {
        free(alarm);
      }
    }));
    ON_CALL(*mock_alarm, AlarmCancel(_)).WillByDefault(Invoke([](alarm_t* alarm) {
      if (alarm) {
        alarm->cb = nullptr;
        alarm->data = nullptr;
        alarm->on_main_loop = false;
      }
    }));
    ON_CALL(*mock_alarm, AlarmIsScheduled(_)).WillByDefault(Invoke([](const alarm_t* alarm) {
      if (alarm) {
        return alarm->cb != nullptr;
      }
      return false;
    }));
    ON_CALL(*mock_alarm, AlarmSet(_, _, _, _))
            .WillByDefault(Invoke(
                    [](alarm_t* alarm, uint64_t /*interval_ms*/, alarm_callback_t cb, void* data) {
                      if (alarm) {
                        alarm->data = data;
                        alarm->cb = cb;
                      }
                    }));
    ON_CALL(*mock_alarm, AlarmSetOnMloop(_, _, _, _))
            .WillByDefault(Invoke(
                    [](alarm_t* alarm, uint64_t /*interval_ms*/, alarm_callback_t cb, void* data) {
                      if (alarm) {
                        alarm->on_main_loop = true;
                        alarm->data = data;
                        alarm->cb = cb;
                      }
                    }));
  }

  void TearDown(void) override {
    services_map.clear();
    gatt::SetMockBtaGattQueue(nullptr);
    gatt::SetMockBtaGattInterface(nullptr);
    bluetooth::manager::SetMockBtmInterface(nullptr);
    AlarmMock::Reset();
  }

  void TestAppRegister(void) {
    BtaAppRegisterCallback app_register_callback;
    EXPECT_CALL(gatt_interface, AppRegister(_, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
    VolumeControl::Initialize(&callbacks, base::DoNothing());
    ASSERT_TRUE(gatt_callback);
    ASSERT_TRUE(app_register_callback);
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
    ASSERT_TRUE(VolumeControl::IsVolumeControlRunning());
  }

  void TestAppUnregister(void) {
    EXPECT_CALL(gatt_interface, AppDeregister(gatt_if));
    VolumeControl::CleanUp();
    ASSERT_FALSE(VolumeControl::IsVolumeControlRunning());
    gatt_callback = nullptr;
  }

  void TestConnect(const RawAddress& address) {
    // by default indicate link as encrypted
    ON_CALL(btm_interface, BTM_IsEncrypted(address, _)).WillByDefault(DoAll(Return(true)));

    EXPECT_CALL(gatt_interface, Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, true));
    VolumeControl::Get()->Connect(address);
    Mock::VerifyAndClearExpectations(&gatt_interface);
  }

  void TestRemove(const RawAddress& address, uint16_t conn_id) {
    EXPECT_CALL(gatt_interface, CancelOpen(gatt_if, address, true));
    if (conn_id) {
      EXPECT_CALL(gatt_interface, Close(conn_id));
    } else {
      EXPECT_CALL(gatt_interface, Close(conn_id)).Times(0);
    }
    VolumeControl::Get()->Remove(address);
    Mock::VerifyAndClearExpectations(&gatt_interface);
  }

  void TestDisconnect(const RawAddress& address, uint16_t conn_id) {
    if (conn_id) {
      EXPECT_CALL(gatt_interface, Close(conn_id));
    } else {
      EXPECT_CALL(gatt_interface, Close(conn_id)).Times(0);
    }
    VolumeControl::Get()->Disconnect(address);
    Mock::VerifyAndClearExpectations(&gatt_interface);
  }

  void TestAddFromStorage(const RawAddress& address) {
    // by default indicate link as encrypted
    ON_CALL(btm_interface, BTM_IsEncrypted(address, _)).WillByDefault(DoAll(Return(true)));

    EXPECT_CALL(gatt_interface, Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, true));
    VolumeControl::Get()->AddFromStorage(address);
  }

  void TestSubscribeNotifications(const RawAddress& address, uint16_t conn_id,
                                  const std::map<uint16_t, uint16_t>& handle_pairs) {
    SetSampleDatabase(conn_id);
    TestAppRegister();
    TestConnect(address);
    GetConnectedEvent(address, conn_id);

    EXPECT_CALL(gatt_queue, WriteDescriptor(_, _, _, _, _, _)).WillRepeatedly(DoDefault());
    EXPECT_CALL(gatt_interface, RegisterForNotifications(_, _, _)).WillRepeatedly(DoDefault());

    std::vector<uint8_t> notify_value({0x01, 0x00});
    for (auto const& handles : handle_pairs) {
      EXPECT_CALL(gatt_queue,
                  WriteDescriptor(conn_id, handles.second, notify_value, GATT_WRITE, _, _))
              .WillOnce(DoDefault());
      EXPECT_CALL(gatt_interface, RegisterForNotifications(gatt_if, address, handles.first))
              .WillOnce(DoDefault());
    }

    GetSearchCompleteEvent(conn_id);
    TestAppUnregister();
  }

  void TestReadCharacteristic(const RawAddress& address, uint16_t conn_id,
                              std::vector<uint16_t> handles) {
    SetSampleDatabase(conn_id);
    TestAppRegister();
    TestConnect(address);
    GetConnectedEvent(address, conn_id);

    tBTA_GATTC_MULTI received_to_read_1{};
    tBTA_GATTC_MULTI received_to_read_2{};

    if (!com::android::bluetooth::flags::le_ase_read_multiple_variable()) {
      EXPECT_CALL(gatt_queue, ReadCharacteristic(conn_id, _, _, _)).WillRepeatedly(DoDefault());
      for (auto const& handle : handles) {
        EXPECT_CALL(gatt_queue, ReadCharacteristic(conn_id, handle, _, _)).WillOnce(DoDefault());
      }
    } else {
      EXPECT_CALL(gatt_queue, ReadMultiCharacteristic(_, _, _, _)).Times(testing::AtLeast(1));
    }

    GetSearchCompleteEvent(conn_id);
    TestAppUnregister();
  }

  void GetConnectedEvent(const RawAddress& address, uint16_t conn_id,
                         tGATT_STATUS status = GATT_SUCCESS) {
    tBTA_GATTC_OPEN event_data = {
            .status = status,
            .conn_id = conn_id,
            .client_if = gatt_if,
            .remote_bda = address,
            .transport = BT_TRANSPORT_LE,
            .mtu = 240,
    };

    gatt_callback(BTA_GATTC_OPEN_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }

  void GetDisconnectedEvent(const RawAddress& address, uint16_t conn_id) {
    tBTA_GATTC_CLOSE event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
            .client_if = gatt_if,
            .remote_bda = address,
            .reason = GATT_CONN_TERMINATE_PEER_USER,
    };

    gatt_callback(BTA_GATTC_CLOSE_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }

  void GetSearchCompleteEvent(uint16_t conn_id) {
    tBTA_GATTC_SEARCH_CMPL event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
    };

    gatt_callback(BTA_GATTC_SEARCH_CMPL_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }

  void GetEncryptionCompleteEvt(const RawAddress& bda) {
    tBTA_GATTC cb_data{};

    cb_data.enc_cmpl.client_if = gatt_if;
    cb_data.enc_cmpl.remote_bda = bda;
    gatt_callback(BTA_GATTC_ENC_CMPL_CB_EVT, &cb_data);
  }

  void SetEncryptionResult(const RawAddress& address, bool success) {
    ON_CALL(btm_interface, BTM_IsEncrypted(address, _)).WillByDefault(DoAll(Return(false)));
    ON_CALL(btm_interface, IsDeviceBonded(address, _)).WillByDefault(DoAll(Return(true)));
    ON_CALL(btm_interface, SetEncryption(address, _, _, _, BTM_BLE_SEC_ENCRYPT))
            .WillByDefault(
                    Invoke([&success, this](const RawAddress& bd_addr, tBT_TRANSPORT transport,
                                            tBTM_SEC_CALLBACK* p_callback, void* p_ref_data,
                                            tBTM_BLE_SEC_ACT /*sec_act*/) -> tBTM_STATUS {
                      if (p_callback) {
                        p_callback(bd_addr, transport, p_ref_data,
                                   success ? tBTM_STATUS::BTM_SUCCESS
                                           : tBTM_STATUS::BTM_FAILED_ON_SECURITY);
                      }
                      GetEncryptionCompleteEvt(bd_addr);
                      return tBTM_STATUS::BTM_SUCCESS;
                    }));
    EXPECT_CALL(btm_interface, SetEncryption(address, _, _, _, BTM_BLE_SEC_ENCRYPT)).Times(1);
  }

  void SetSampleDatabaseVCS(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, false, false, false, false);
  }

  void SetSampleDatabaseAICS(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, true, false, false, false);
  }

  void SetSampleDatabaseAICSBroken(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, true, true, true, false);
  }

  void SetSampleDatabaseNoVCS(uint16_t conn_id) {
    set_sample_database(conn_id, false, false, true, false, true, false);
  }

  void SetSampleDatabaseVCSBroken(uint16_t conn_id) {
    set_sample_database(conn_id, true, true, true, false, true, false);
  }

  void SetSampleDatabaseVOCS(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, false, false, true, false);
  }

  void SetSampleDatabaseVOCSBroken(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, true, false, true, true);
  }

  void SetSampleDatabase(uint16_t conn_id) {
    set_sample_database(conn_id, true, false, true, false, true, false);
  }

  NiceMock<MockVolumeControlCallbacks> callbacks;
  NiceMock<bluetooth::manager::MockBtmInterface> btm_interface;
  MockCsisClient mock_csis_client_module_;
  NiceMock<gatt::MockBtaGattInterface> gatt_interface;
  NiceMock<gatt::MockBtaGattQueue> gatt_queue;

  tBTA_GATTC_CBACK* gatt_callback;
  const uint8_t gatt_if = 0xff;
  std::map<uint16_t, std::list<gatt::Service>> services_map;
};

TEST_F(VolumeControlTest, test_get_uninitialized) { ASSERT_DEATH(VolumeControl::Get(), ""); }

TEST_F(VolumeControlTest, test_initialize) {
  bool init_cb_called = false;
  BtaAppRegisterCallback app_register_callback;
  EXPECT_CALL(gatt_interface, AppRegister(_, _, _, _))
          .WillOnce(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  VolumeControl::Initialize(
          &callbacks,
          base::Bind([](bool* init_cb_called) { *init_cb_called = true; }, &init_cb_called));
  ASSERT_TRUE(gatt_callback);
  ASSERT_TRUE(app_register_callback);
  app_register_callback.Run(gatt_if, GATT_SUCCESS);
  ASSERT_TRUE(init_cb_called);

  ASSERT_TRUE(VolumeControl::IsVolumeControlRunning());
  VolumeControl::CleanUp();
}

TEST_F(VolumeControlTest, test_initialize_twice) {
  VolumeControl::Initialize(&callbacks, base::DoNothing());
  VolumeControl* volume_control_p = VolumeControl::Get();
  VolumeControl::Initialize(&callbacks, base::DoNothing());
  ASSERT_EQ(volume_control_p, VolumeControl::Get());
  VolumeControl::CleanUp();
}

TEST_F(VolumeControlTest, test_cleanup_initialized) {
  VolumeControl::Initialize(&callbacks, base::DoNothing());
  VolumeControl::CleanUp();
  ASSERT_FALSE(VolumeControl::IsVolumeControlRunning());
}

TEST_F(VolumeControlTest, test_cleanup_uninitialized) {
  VolumeControl::CleanUp();
  ASSERT_FALSE(VolumeControl::IsVolumeControlRunning());
}

TEST_F(VolumeControlTest, test_app_registration) {
  TestAppRegister();
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_connect) {
  TestAppRegister();
  TestConnect(GetTestAddress(0));
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_connect_after_remove) {
  TestAppRegister();

  const RawAddress test_address = GetTestAddress(0);
  uint16_t conn_id = 1;

  TestConnect(test_address);
  GetConnectedEvent(test_address, conn_id);
  Mock::VerifyAndClearExpectations(&callbacks);

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(1);

  TestRemove(test_address, conn_id);
  Mock::VerifyAndClearExpectations(&callbacks);

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(1);
  ON_CALL(btm_interface, IsDeviceBonded(_, _)).WillByDefault(DoAll(Return(false)));

  VolumeControl::Get()->Connect(test_address);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_reconnect_after_interrupted_discovery) {
  const RawAddress test_address = GetTestAddress(0);

  // Initial connection - no callback calls yet as we want to disconnect in the
  // middle
  SetSampleDatabaseVOCS(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(0);
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 2, _)).Times(0);
  GetConnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&callbacks);

  // Remote disconnects in the middle of the service discovery
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  GetDisconnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&callbacks);

  // This time let the service discovery pass
  ON_CALL(gatt_interface, ServiceSearchRequest(_, _))
          .WillByDefault(Invoke([&](uint16_t conn_id, const bluetooth::Uuid* p_srvc_uuid) -> void {
            if (*p_srvc_uuid == kVolumeControlUuid) {
              GetSearchCompleteEvent(conn_id);
            }
          }));

  // Remote is being connected by another GATT client
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 2, _));
  GetConnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&callbacks);

  // Request connect when the remote was already connected by another service
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 2, _)).Times(0);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  VolumeControl::Get()->Connect(test_address);
  // The GetConnectedEvent(test_address, 1); should not be triggered here, since
  // GATT implementation will not send this event for the already connected
  // device
  Mock::VerifyAndClearExpectations(&callbacks);

  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_verify_opportunistic_connect_active_after_connect_timeout) {
  const RawAddress address = GetTestAddress(0);

  TestAppRegister();
  TestAddFromStorage(address);
  Mock::VerifyAndClearExpectations(&gatt_interface);

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, address)).Times(1);
  TestConnect(address);

  EXPECT_CALL(gatt_interface, CancelOpen(gatt_if, address, _)).Times(0);
  EXPECT_CALL(gatt_interface, Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, true)).Times(1);

  GetConnectedEvent(address, 1, GATT_ERROR);
  Mock::VerifyAndClearExpectations(&callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_reconnect_after_timeout) {
  const RawAddress address = GetTestAddress(0);

  // Initial connection
  SetSampleDatabaseVOCS(1);
  TestAppRegister();

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, address)).Times(0);
  TestConnect(address);

  // Disconnect not connected device - upper layer times out and needs a
  // disconnection event to leave the transient Connecting state
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, address));
  EXPECT_CALL(gatt_interface, CancelOpen(gatt_if, address, _)).Times(0);
  TestDisconnect(address, 0);

  // Above the device was not connected and we got Disconnect request from the
  // upper layer - it means it has timed-out but still wants to connect, thus
  // native is still doing background or opportunistic connect. Let the remote
  // device reconnect now.
  ON_CALL(gatt_interface, ServiceSearchRequest(_, _))
          .WillByDefault(Invoke([&](uint16_t conn_id, const bluetooth::Uuid* p_srvc_uuid) -> void {
            if (*p_srvc_uuid == kVolumeControlUuid) {
              GetSearchCompleteEvent(conn_id);
            }
          }));
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, address));
  EXPECT_CALL(callbacks, OnDeviceAvailable(address, 2, _));
  GetConnectedEvent(address, 1);
  Mock::VerifyAndClearExpectations(&callbacks);

  // Make sure that the upper layer gets the disconnection event even if not
  // connecting actively anymore due to the mentioned time-out mechanism.
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, address));
  GetDisconnectedEvent(address, 1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_add_from_storage) {
  TestAppRegister();
  TestAddFromStorage(GetTestAddress(0));
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_remove_non_connected) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  TestRemove(test_address, 0);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_remove_connected) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestConnect(test_address);
  GetConnectedEvent(test_address, 1);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  TestDisconnect(test_address, 1);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_disconnect_non_connected) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  TestDisconnect(test_address, 0);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_disconnect_connected) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestConnect(test_address);
  GetConnectedEvent(test_address, 1);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  TestDisconnect(test_address, 1);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_disconnected) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestConnect(test_address);
  GetConnectedEvent(test_address, 1);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  GetDisconnectedEvent(test_address, 1);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_disconnected_while_autoconnect) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestAddFromStorage(test_address);
  GetConnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  // autoconnect - don't indicate disconnection
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(0);
  GetDisconnectedEvent(test_address, 1);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_disconnect_when_link_key_gone) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestAddFromStorage(test_address);

  ON_CALL(btm_interface, BTM_IsEncrypted(test_address, _)).WillByDefault(DoAll(Return(false)));
  ON_CALL(btm_interface, SetEncryption(test_address, _, _, _, BTM_BLE_SEC_ENCRYPT))
          .WillByDefault(Return(tBTM_STATUS::BTM_ERR_KEY_MISSING));

  // autoconnect - don't indicate disconnection
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(0);
  EXPECT_CALL(gatt_interface, Close(1));
  GetConnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&btm_interface);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_reconnect_after_encryption_failed) {
  const RawAddress test_address = GetTestAddress(0);
  TestAppRegister();
  TestAddFromStorage(test_address);
  SetEncryptionResult(test_address, false);
  // autoconnect - don't indicate disconnection
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address)).Times(0);
  GetConnectedEvent(test_address, 1);
  Mock::VerifyAndClearExpectations(&btm_interface);
  SetEncryptionResult(test_address, true);
  GetConnectedEvent(test_address, 1);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_service_discovery_completed_before_encryption) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVCS(1);
  TestAppRegister();
  TestConnect(test_address);

  ON_CALL(btm_interface, BTM_IsEncrypted(test_address, _)).WillByDefault(DoAll(Return(false)));
  ON_CALL(btm_interface, IsDeviceBonded(test_address, _)).WillByDefault(DoAll(Return(true)));
  ON_CALL(btm_interface, SetEncryption(test_address, _, _, _, _))
          .WillByDefault(Return(tBTM_STATUS::BTM_SUCCESS));

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(0);
  uint16_t conn_id = 1;
  GetConnectedEvent(test_address, conn_id);
  GetSearchCompleteEvent(conn_id);
  Mock::VerifyAndClearExpectations(&btm_interface);
  Mock::VerifyAndClearExpectations(&callbacks);

  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address)).Times(1);

  ON_CALL(btm_interface, BTM_IsEncrypted(test_address, _)).WillByDefault(DoAll(Return(true)));
  EXPECT_CALL(gatt_interface, ServiceSearchRequest(_, _));

  GetEncryptionCompleteEvt(test_address);
  GetSearchCompleteEvent(conn_id);

  Mock::VerifyAndClearExpectations(&callbacks);
  Mock::VerifyAndClearExpectations(&gatt_interface);

  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_discovery_vcs_found) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVCS(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, _, _));
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  GetConnectedEvent(test_address, 1);
  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_discovery_vcs_not_found) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseNoVCS(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  GetConnectedEvent(test_address, 1);

  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_discovery_vcs_broken) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVCSBroken(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  GetConnectedEvent(test_address, 1);
  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_subscribe_vcs_volume_state) {
  std::map<uint16_t, uint16_t> handles({{0x0021, 0x0022}});
  TestSubscribeNotifications(GetTestAddress(0), 1, handles);
}

TEST_F(VolumeControlTest, test_subscribe_vocs_offset_state) {
  std::map<uint16_t, uint16_t> handles({{0x0072, 0x0073}, {0x0082, 0x0083}});
  TestSubscribeNotifications(GetTestAddress(0), 1, handles);
}

TEST_F(VolumeControlTest, test_subscribe_vocs_offset_location) {
  std::map<uint16_t, uint16_t> handles({{0x0085, 0x0086}});
  TestSubscribeNotifications(GetTestAddress(0), 1, handles);
}

TEST_F(VolumeControlTest, test_subscribe_vocs_output_description) {
  std::map<uint16_t, uint16_t> handles({{0x008a, 0x008b}});
  TestSubscribeNotifications(GetTestAddress(0), 1, handles);
}

TEST_F(VolumeControlTest, test_read_vcs_volume_state) {
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address, _, _, _, true)).Times(1);
  std::vector<uint16_t> handles({0x0021});
  TestReadCharacteristic(test_address, 1, handles);
}

TEST_F(VolumeControlTest, test_read_vcs_volume_flags) {
  std::vector<uint16_t> handles({0x0026});
  TestReadCharacteristic(GetTestAddress(0), 1, handles);
}

TEST_F(VolumeControlTest, test_read_vocs_volume_offset) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(false);
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0072, 0x0082});
  TestReadCharacteristic(test_address, 1, handles);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(VolumeControlTest, test_read_vocs_volume_offset_multi) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(true);
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0072, 0x0082});
  TestReadCharacteristic(test_address, 1, handles);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(VolumeControlTest, test_read_vocs_offset_location) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(false);
  const RawAddress test_address = GetTestAddress(0);
  // It is called twice because after connect read is done once and second read is coming from the
  // test.
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0075, 0x0085});
  TestReadCharacteristic(test_address, 1, handles);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(VolumeControlTest, test_read_vocs_offset_location_multi) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(true);
  const RawAddress test_address = GetTestAddress(0);
  // It is called twice because after connect read is done once and second read is coming from the
  // test.
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0075, 0x0085});
  TestReadCharacteristic(test_address, 1, handles);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(VolumeControlTest, test_read_vocs_output_description) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(false);
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0079, 0x008a});
  TestReadCharacteristic(test_address, 1, handles);
}

TEST_F(VolumeControlTest, test_read_vocs_output_description_multi) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(true);
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 1, _)).Times(1);
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, _)).Times(1);
  std::vector<uint16_t> handles({0x0079, 0x008a});
  TestReadCharacteristic(test_address, 1, handles);
}

TEST_F(VolumeControlTest, test_discovery_vocs_found) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVOCS(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 2, _));
  GetConnectedEvent(test_address, 1);
  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_discovery_vocs_not_found) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVCS(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 0, _));
  GetConnectedEvent(test_address, 1);
  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_discovery_vocs_broken) {
  const RawAddress test_address = GetTestAddress(0);
  SetSampleDatabaseVOCSBroken(1);
  TestAppRegister();
  TestConnect(test_address);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::CONNECTED, test_address));
  EXPECT_CALL(callbacks, OnDeviceAvailable(test_address, 1, _));
  GetConnectedEvent(test_address, 1);
  GetSearchCompleteEvent(1);
  Mock::VerifyAndClearExpectations(&callbacks);
  TestAppUnregister();
}

TEST_F(VolumeControlTest, test_read_vcs_database_out_of_sync) {
  const RawAddress test_address = GetTestAddress(0);
  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address, _, _, _, true));
  std::vector<uint16_t> handles({0x0021});
  uint16_t conn_id = 1;

  SetSampleDatabase(conn_id);
  TestAppRegister();
  TestConnect(test_address);
  GetConnectedEvent(test_address, conn_id);

  EXPECT_CALL(gatt_queue, ReadCharacteristic(conn_id, _, _, _)).WillRepeatedly(DoDefault());
  for (auto const& handle : handles) {
    EXPECT_CALL(gatt_queue, ReadCharacteristic(conn_id, handle, _, _)).WillOnce(DoDefault());
  }
  GetSearchCompleteEvent(conn_id);

  /* Simulate database change on the remote side. */
  ON_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _))
          .WillByDefault(Invoke([this](uint16_t conn_id, uint16_t handle,
                                       std::vector<uint8_t> value, tGATT_WRITE_TYPE /*write_type*/,
                                       GATT_WRITE_OP_CB cb, void* cb_data) {
            auto* svc = gatt::FindService(services_map[conn_id], handle);
            if (svc == nullptr) {
              return;
            }

            tGATT_STATUS status = GATT_DATABASE_OUT_OF_SYNC;
            if (cb) {
              cb(conn_id, status, handle, value.size(), value.data(), cb_data);
            }
          }));

  ON_CALL(gatt_interface, ServiceSearchRequest(_, _)).WillByDefault(Return());
  EXPECT_CALL(gatt_interface, ServiceSearchRequest(_, _));
  VolumeControl::Get()->SetVolume(test_address, 15);
  Mock::VerifyAndClearExpectations(&gatt_interface);
  TestAppUnregister();
}

class VolumeControlCallbackTest : public VolumeControlTest {
protected:
  const RawAddress test_address = GetTestAddress(0);
  uint16_t conn_id = 22;

  void SetUp(void) override {
    VolumeControlTest::SetUp();
    SetSampleDatabase(conn_id);
    TestAppRegister();
    TestConnect(test_address);
    GetConnectedEvent(test_address, conn_id);
    GetSearchCompleteEvent(conn_id);
  }

  void TearDown(void) override {
    TestAppUnregister();
    VolumeControlTest::TearDown();
  }

  void GetNotificationEvent(uint16_t handle, const std::vector<uint8_t>& value) {
    tBTA_GATTC_NOTIFY event_data = {
            .conn_id = conn_id,
            .bda = test_address,
            .handle = handle,
            .len = (uint8_t)value.size(),
            .is_notify = true,
    };

    std::copy(value.begin(), value.end(), event_data.value);
    gatt_callback(BTA_GATTC_NOTIF_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }
};

TEST_F(VolumeControlCallbackTest, test_volume_state_changed_stress) {
  std::vector<uint8_t> value({0x03, 0x01, 0x02});
  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address, 0x03, true, _, true));
  GetNotificationEvent(0x0021, value);
}

TEST_F(VolumeControlCallbackTest, test_volume_state_changed_malformed) {
  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address, _, _, _, _)).Times(0);
  std::vector<uint8_t> too_short({0x03, 0x01});
  GetNotificationEvent(0x0021, too_short);
  std::vector<uint8_t> too_long({0x03, 0x01, 0x02, 0x03});
  GetNotificationEvent(0x0021, too_long);
}

TEST_F(VolumeControlCallbackTest, audio_input_state_changed__invalid_mute__is_rejected) {
  uint8_t invalid_mute = 0x03;
  std::vector<uint8_t> value({0x03, invalid_mute, (uint8_t)GainMode::MANUAL, 0x04});
  EXPECT_CALL(callbacks, OnExtAudioInStateChanged(_, _, _, _, _)).Times(0);
  GetNotificationEvent(0x0032, value);
}

TEST_F(VolumeControlCallbackTest, audio_input_state_changed__invalid_gain_mode__is_rejected) {
  uint8_t invalid_gain_mode = 0x06;
  std::vector<uint8_t> value({0x03, (uint8_t)Mute::MUTED, invalid_gain_mode, 0x04});
  EXPECT_CALL(callbacks, OnExtAudioInStateChanged(_, _, _, _, _)).Times(0);
  GetNotificationEvent(0x0032, value);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_state_changed__muted) {
  std::vector<uint8_t> value({0x03, (uint8_t)Mute::MUTED, (uint8_t)GainMode::MANUAL, 0x04});
  EXPECT_CALL(callbacks,
              OnExtAudioInStateChanged(test_address, _, 0x03, Mute::MUTED, GainMode::MANUAL));
  GetNotificationEvent(0x0032, value);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_state_changed__disabled) {
  std::vector<uint8_t> value({0x03, (uint8_t)Mute::DISABLED, (uint8_t)GainMode::MANUAL, 0x04});
  EXPECT_CALL(callbacks,
              OnExtAudioInStateChanged(test_address, _, 0x03, Mute::DISABLED, GainMode::MANUAL));
  GetNotificationEvent(0x0032, value);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_state_changed_malformed) {
  EXPECT_CALL(callbacks, OnExtAudioInStateChanged(test_address, _, _, _, _)).Times(0);
  std::vector<uint8_t> too_short({0x03, 0x01, 0x02});
  GetNotificationEvent(0x0032, too_short);
  std::vector<uint8_t> too_long({0x03, 0x01, 0x02, 0x04, 0x05});
  GetNotificationEvent(0x0032, too_long);
}

TEST_F(VolumeControlCallbackTest, test_audio_gain_props_changed) {
  std::vector<uint8_t> value({0x03, 0x01, 0x02});
  EXPECT_CALL(callbacks,
              OnExtAudioInGainSettingPropertiesChanged(test_address, _, 0x03, 0x01, 0x02));
  GetNotificationEvent(0x0055, value);
}

TEST_F(VolumeControlCallbackTest, test_audio_gain_props_changed_malformed) {
  EXPECT_CALL(callbacks, OnExtAudioInGainSettingPropertiesChanged(test_address, _, _, _, _))
          .Times(0);
  std::vector<uint8_t> too_short({0x03, 0x01});
  GetNotificationEvent(0x0055, too_short);
  std::vector<uint8_t> too_long({0x03, 0x01, 0x02, 0x03});
  GetNotificationEvent(0x0055, too_long);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_status_changed) {
  std::vector<uint8_t> value({static_cast<uint8_t>(bluetooth::vc::VolumeInputStatus::Inactive)});
  EXPECT_CALL(callbacks, OnExtAudioInStatusChanged(test_address, _,
                                                   bluetooth::vc::VolumeInputStatus::Inactive));
  GetNotificationEvent(0x0039, value);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_status_changed_malformed) {
  EXPECT_CALL(callbacks, OnExtAudioInStatusChanged(test_address, _, _)).Times(0);
  std::vector<uint8_t> too_short(0);
  GetNotificationEvent(0x0039, too_short);
  std::vector<uint8_t> too_long({0x03, 0x01});
  GetNotificationEvent(0x0039, too_long);
}

TEST_F(VolumeControlCallbackTest, test_audio_input_description_changed) {
  std::string description = "SPDIF";
  std::vector<uint8_t> value(description.begin(), description.end());
  EXPECT_CALL(callbacks, OnExtAudioInDescriptionChanged(test_address, _, description, _));
  GetNotificationEvent(0x005e, value);
}

TEST_F(VolumeControlCallbackTest, test_volume_offset_changed) {
  std::vector<uint8_t> value({0x04, 0x05, 0x06});
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, 0x0504));
  GetNotificationEvent(0x0082, value);
}

TEST_F(VolumeControlCallbackTest, test_volume_offset_changed_malformed) {
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 2, _)).Times(0);
  std::vector<uint8_t> too_short({0x04});
  GetNotificationEvent(0x0082, too_short);
  std::vector<uint8_t> too_long({0x04, 0x05, 0x06, 0x07});
  GetNotificationEvent(0x0082, too_long);
}

TEST_F(VolumeControlCallbackTest, test_offset_location_changed) {
  std::vector<uint8_t> value({0x01, 0x02, 0x03, 0x04});
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, 0x04030201));
  GetNotificationEvent(0x0085, value);
}

TEST_F(VolumeControlCallbackTest, test_offset_location_changed_malformed) {
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, _)).Times(0);
  std::vector<uint8_t> too_short({0x04});
  GetNotificationEvent(0x0085, too_short);
  std::vector<uint8_t> too_long({0x04, 0x05, 0x06});
  GetNotificationEvent(0x0085, too_long);
}

TEST_F(VolumeControlCallbackTest, test_audio_output_description_changed) {
  std::string descr = "left";
  std::vector<uint8_t> value(descr.begin(), descr.end());
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, descr));
  GetNotificationEvent(0x008a, value);
}

class VolumeControlValueGetTest : public VolumeControlTest {
protected:
  const RawAddress test_address = GetTestAddress(0);
  uint16_t conn_id = 22;
  GATT_READ_OP_CB cb;
  void* cb_data;
  uint16_t handle;

  void SetUp(void) override {
    VolumeControlTest::SetUp();
    SetSampleDatabase(conn_id);
    TestAppRegister();
    TestConnect(test_address);
    GetConnectedEvent(test_address, conn_id);
    GetSearchCompleteEvent(conn_id);
    EXPECT_CALL(gatt_queue, ReadCharacteristic(conn_id, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&handle), SaveArg<2>(&cb), SaveArg<3>(&cb_data)));
  }

  void TearDown(void) override {
    TestAppUnregister();
    cb = nullptr;
    cb_data = nullptr;
    handle = 0;
    VolumeControlTest::TearDown();
  }
};

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_out_volume_offset) {
  VolumeControl::Get()->GetExtAudioOutVolumeOffset(test_address, 1);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({0x01, 0x02, 0x03});
  EXPECT_CALL(callbacks, OnExtAudioOutVolumeOffsetChanged(test_address, 1, 0x0201));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_out_location) {
  VolumeControl::Get()->GetExtAudioOutLocation(test_address, 2);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({0x01, 0x02, 0x03, 0x04});
  EXPECT_CALL(callbacks, OnExtAudioOutLocationChanged(test_address, 2, 0x04030201));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_out_description) {
  VolumeControl::Get()->GetExtAudioOutDescription(test_address, 2);
  EXPECT_TRUE(cb);
  std::string descr = "right";
  std::vector<uint8_t> value(descr.begin(), descr.end());
  EXPECT_CALL(callbacks, OnExtAudioOutDescriptionChanged(test_address, 2, descr));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_in_state) {
  VolumeControl::Get()->GetExtAudioInState(test_address, 1);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({0x01, (uint8_t)Mute::NOT_MUTED, (uint8_t)GainMode::MANUAL, 0x03});
  EXPECT_CALL(callbacks,
              OnExtAudioInStateChanged(test_address, 1, 0x01, Mute::NOT_MUTED, GainMode::MANUAL));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_in_status) {
  VolumeControl::Get()->GetExtAudioInStatus(test_address, 0);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({static_cast<uint8_t>(bluetooth::vc::VolumeInputStatus::Active)});
  EXPECT_CALL(callbacks,
              OnExtAudioInStatusChanged(test_address, 0, bluetooth::vc::VolumeInputStatus::Active));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_in_gain_props) {
  VolumeControl::Get()->GetExtAudioInGainProps(test_address, 0);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({0x01, 0x02, 0x03});
  EXPECT_CALL(callbacks,
              OnExtAudioInGainSettingPropertiesChanged(test_address, 0, 0x01, 0x02, 0x03));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_in_description) {
  VolumeControl::Get()->GetExtAudioInDescription(test_address, 1);
  EXPECT_TRUE(cb);
  std::string description = "AUX-IN";
  std::vector<uint8_t> value(description.begin(), description.end());
  EXPECT_CALL(callbacks, OnExtAudioInDescriptionChanged(test_address, 1, description, _));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

TEST_F(VolumeControlValueGetTest, test_get_ext_audio_in_type) {
  VolumeControl::Get()->GetExtAudioInType(test_address, 1);
  EXPECT_TRUE(cb);
  std::vector<uint8_t> value({static_cast<uint8_t>(bluetooth::vc::VolumeInputType::Ambient)});
  EXPECT_CALL(callbacks,
              OnExtAudioInTypeChanged(test_address, 1, bluetooth::vc::VolumeInputType::Ambient));
  cb(conn_id, GATT_SUCCESS, handle, (uint16_t)value.size(), value.data(), cb_data);
}

class VolumeControlValueSetTest : public VolumeControlTest {
protected:
  const RawAddress test_address = GetTestAddress(0);
  uint16_t conn_id = 22;

  void SetUp(void) override {
    VolumeControlTest::SetUp();
    SetSampleDatabase(conn_id);
    TestAppRegister();
    TestConnect(test_address);
    GetConnectedEvent(test_address, conn_id);
    GetSearchCompleteEvent(conn_id);

    ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
            .WillByDefault([this](uint16_t conn_id, uint16_t /*handle*/, std::vector<uint8_t> value,
                                  tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb,
                                  void* cb_data) {
              uint8_t write_rsp;

              std::vector<uint8_t> ntf_value({value[0], 0, static_cast<uint8_t>(value[1] + 1)});
              switch (value[0]) {
                case 0x06:  // mute
                  ntf_value[1] = 1;
                  break;
                case 0x05:  // unmute
                  break;
                case 0x04:  // set abs. volume
                  ntf_value[0] = value[2];
                  ntf_value[1] = (value[2] ? 0 : 1);
                  break;
                case 0x03:  // unmute rel. up
                  break;
                case 0x02:  // unmute rel. down
                  break;
                case 0x01:  // rel. up
                  break;
                case 0x00:  // rel. down
                  break;
                default:
                  break;
              }
              GetNotificationEvent(0x0021, ntf_value);
              cb(conn_id, GATT_SUCCESS, 0x0024, 0, &write_rsp, cb_data);
            });
  }

  void GetNotificationEvent(uint16_t handle, const std::vector<uint8_t>& value) {
    tBTA_GATTC_NOTIFY event_data = {
            .conn_id = conn_id,
            .bda = test_address,
            .handle = handle,
            .len = (uint8_t)value.size(),
            .is_notify = true,
    };

    std::copy(value.begin(), value.end(), event_data.value);
    gatt_callback(BTA_GATTC_NOTIF_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }

  void TearDown(void) override {
    TestAppUnregister();
    VolumeControlTest::TearDown();
  }
};

TEST_F(VolumeControlValueSetTest, test_volume_operation_failed) {
  const std::vector<uint8_t> vol_x10({0x04, 0x00, 0x10});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  ON_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _))
          .WillByDefault(Invoke([this](uint16_t conn_id, uint16_t handle,
                                       std::vector<uint8_t> value, tGATT_WRITE_TYPE /*write_type*/,
                                       GATT_WRITE_OP_CB cb, void* cb_data) {
            auto* svc = gatt::FindService(services_map[conn_id], handle);
            if (svc == nullptr) {
              return;
            }

            tGATT_STATUS status = GATT_ERROR;
            if (cb) {
              cb(conn_id, status, handle, value.size(), value.data(), cb_data);
            }
          }));

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(1);
  EXPECT_CALL(*AlarmMock::Get(), AlarmCancel(_)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(AlarmMock::Get());
}

TEST_F(VolumeControlValueSetTest, test_volume_operation_failed_due_to_device_disconnection) {
  const std::vector<uint8_t> vol_x10({0x04, 0x00, 0x10});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  ON_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _))
          .WillByDefault(Invoke([](uint16_t /*conn_id*/, uint16_t /*handle*/,
                                   std::vector<uint8_t> /*value*/, tGATT_WRITE_TYPE /*write_type*/,
                                   GATT_WRITE_OP_CB /*cb*/, void* /*cb_data*/) {
            /* Do nothing */
          }));

  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _)).Times(0);

  alarm_callback_t active_alarm_cb = nullptr;
  EXPECT_CALL(*AlarmMock::Get(), AlarmSetOnMloop(_, _, _, _))
          .WillOnce(Invoke([&](alarm_t* alarm, uint64_t /*interval_ms*/, alarm_callback_t cb,
                               void* /*data*/) {
            if (alarm) {
              alarm->on_main_loop = true;
              alarm->cb = cb;
              active_alarm_cb = cb;
            }
          }));
  ON_CALL(*AlarmMock::Get(), AlarmCancel(_)).WillByDefault(Invoke([&](alarm_t* alarm) {
    if (alarm) {
      alarm->cb = nullptr;
      alarm->on_main_loop = false;
      active_alarm_cb = nullptr;
    }
  }));

  VolumeControl::Get()->SetVolume(test_address, 0x10);

  Mock::VerifyAndClearExpectations(&gatt_queue);
  Mock::VerifyAndClearExpectations(AlarmMock::Get());
  ASSERT_NE(active_alarm_cb, nullptr);

  EXPECT_CALL(*AlarmMock::Get(), AlarmCancel(_)).Times(1);
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address));
  GetDisconnectedEvent(test_address, conn_id);

  ASSERT_EQ(active_alarm_cb, nullptr);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(VolumeControlValueSetTest, test_set_volume) {
  const std::vector<uint8_t> vol_x10({0x04, 0x00, 0x10});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);

  // Same volume level should not be applied twice
  const std::vector<uint8_t> vol_x10_2({0x04, 0x01, 0x10});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10_2, GATT_WRITE, _, _))
          .Times(0);
  VolumeControl::Get()->SetVolume(test_address, 0x10);

  const std::vector<uint8_t> vol_x20({0x04, 0x01, 0x20});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x20, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x20);
}

TEST_F(VolumeControlValueSetTest, test_set_volume_to_previous_during_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x11({0x04, /*change_cnt*/ 1, 0x11});
  std::vector<uint8_t> ntf_value_x11({0x11, 0, 2});
  const std::vector<uint8_t> vol_x10_2({0x04, /*change_cnt*/ 2, 0x10});
  std::vector<uint8_t> ntf_value_x10_2({0x10, 0, 3});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x11, GATT_WRITE, _, _)).Times(1);

  // Allow queuing the same as on the device but different from the started one
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10_2, GATT_WRITE, _, _))
          .Times(1);

  VolumeControl::Get()->SetVolume(test_address, 0x11);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x11);
  GetNotificationEvent(0x0021, ntf_value_x10_2);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_set_volume_to_same_during_other_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x00({0x04, /*change_cnt*/ 1, 0x00});
  std::vector<uint8_t> ntf_value_x00({0x00, 0, 2});
  const std::vector<uint8_t> mute({0x06, /*change_cnt*/ 2});
  std::vector<uint8_t> ntf_value_mute({0x00, 1, 3});
  const std::vector<uint8_t> vol_x00_2({0x04, /*change_cnt*/ 3, 0x00});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x00, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x00);
  GetNotificationEvent(0x0021, ntf_value_x00);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute, GATT_WRITE, _, _)).Times(1);

  // Not queued because the volume is the same as on the device and no volume operation started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x00_2, GATT_WRITE, _, _))
          .Times(0);

  VolumeControl::Get()->Mute(test_address);
  VolumeControl::Get()->SetVolume(test_address, 0x00);
  GetNotificationEvent(0x0021, ntf_value_mute);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_set_volume_to_same_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x00({0x04, /*change_cnt*/ 1, 0x00});
  std::vector<uint8_t> ntf_value_x00({0x00, 0, 2});
  const std::vector<uint8_t> vol_x00_2({0x04, /*change_cnt*/ 2, 0x00});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x00, GATT_WRITE, _, _)).Times(1);

  // Not queued because the volume is the same as on started volume operation
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x00_2, GATT_WRITE, _, _))
          .Times(0);

  VolumeControl::Get()->SetVolume(test_address, 0x00);
  VolumeControl::Get()->SetVolume(test_address, 0x00);
  GetNotificationEvent(0x0021, ntf_value_x00);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_unmute_to_previous_during_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> mute({0x06, /*change_cnt*/ 1});
  std::vector<uint8_t> ntf_value_mute({0x10, 1, 2});
  const std::vector<uint8_t> unmute({0x05, /*change_cnt*/ 2});
  std::vector<uint8_t> ntf_value_unmute({0x10, 0, 3});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute, GATT_WRITE, _, _)).Times(1);

  // Allow queuing the same as on the device but different from the started one
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute, GATT_WRITE, _, _)).Times(1);

  VolumeControl::Get()->Mute(test_address);
  VolumeControl::Get()->UnMute(test_address);
  GetNotificationEvent(0x0021, ntf_value_mute);
  GetNotificationEvent(0x0021, ntf_value_unmute);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_mute_to_previous_during_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> mute({0x06, /*change_cnt*/ 1});
  std::vector<uint8_t> ntf_value_mute({0x10, 1, 2});
  const std::vector<uint8_t> unmute({0x05, /*change_cnt*/ 2});
  std::vector<uint8_t> ntf_value_unmute({0x10, 0, 3});
  const std::vector<uint8_t> mute_2({0x06, /*change_cnt*/ 3});
  std::vector<uint8_t> ntf_value_mute_2({0x10, 1, 4});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->Mute(test_address);
  GetNotificationEvent(0x0021, ntf_value_mute);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute, GATT_WRITE, _, _)).Times(1);

  // Allow queuing the same as on the device but different from the started one
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute_2, GATT_WRITE, _, _)).Times(1);

  VolumeControl::Get()->UnMute(test_address);
  VolumeControl::Get()->Mute(test_address);
  GetNotificationEvent(0x0021, ntf_value_unmute);
  GetNotificationEvent(0x0021, ntf_value_mute_2);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_unmute_to_same_during_other_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x11({0x04, /*change_cnt*/ 1, 0x11});
  std::vector<uint8_t> ntf_value_x11({0x11, 0, 2});
  const std::vector<uint8_t> unmute({0x05, /*change_cnt*/ 3});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x11, GATT_WRITE, _, _)).Times(1);

  // Not queued because the mute state is the same as on the device and no mute operation started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute, GATT_WRITE, _, _)).Times(0);

  VolumeControl::Get()->SetVolume(test_address, 0x11);
  VolumeControl::Get()->UnMute(test_address);
  GetNotificationEvent(0x0021, ntf_value_x11);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_mute_to_same_during_other_pending) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> mute({0x06, /*change_cnt*/ 1});
  std::vector<uint8_t> ntf_value_mute({0x10, 1, 2});
  const std::vector<uint8_t> vol_x11({0x04, /*change_cnt*/ 2, 0x11});
  std::vector<uint8_t> ntf_value_x11({0x11, 1, 3});
  const std::vector<uint8_t> mute_2({0x06, /*change_cnt*/ 3});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address, 0x10);
  GetNotificationEvent(0x0021, ntf_value_x10);

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->Mute(test_address);
  GetNotificationEvent(0x0021, ntf_value_mute);

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x11, GATT_WRITE, _, _)).Times(1);

  // Not queued because the mute state is the same as on the device and no unmute operation started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute_2, GATT_WRITE, _, _)).Times(0);

  VolumeControl::Get()->SetVolume(test_address, 0x11);
  VolumeControl::Get()->Mute(test_address);
  GetNotificationEvent(0x0021, ntf_value_x11);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_remove_pending_mute_operation) {
  com::android::bluetooth::flags::provider_->vcp_allow_set_same_volume_if_pending(true);

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x06:  // mute
                break;
              case 0x05:  // unmute
                break;
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> mute({0x06, /*change_cnt*/ 1});
  const std::vector<uint8_t> unmute({0x05, /*change_cnt*/ 1});

  // Started
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);

  // All (un)mute operations was removed and not queued at the end as last has same value as remote
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute, GATT_WRITE, _, _)).Times(0);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute, GATT_WRITE, _, _)).Times(0);

  VolumeControl::Get()->SetVolume(test_address, 0x10);
  VolumeControl::Get()->Mute(test_address);
  VolumeControl::Get()->UnMute(test_address);
  VolumeControl::Get()->Mute(test_address);
  VolumeControl::Get()->UnMute(test_address);
  GetNotificationEvent(0x0021, ntf_value_x10);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_set_volume_stress) {
  uint8_t n = 100;
  uint8_t change_cnt = 0;
  uint8_t vol = 1;

  for (uint8_t i = 1; i < n; i++) {
    const std::vector<uint8_t> vol_x10({0x04, change_cnt, vol});
    EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _))
            .Times(1);
    VolumeControl::Get()->SetVolume(test_address, vol);
    Mock::VerifyAndClearExpectations(&gatt_queue);
    change_cnt++;
    vol++;
  }
}

TEST_F(VolumeControlValueSetTest, test_set_volume_stress_2) {
  uint8_t change_cnt = 0;
  uint8_t vol = 1;

  // In this test we simulate notification coming later and operations will be queued
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x11({0x04, /*change_cnt*/ 1, 0x11});
  std::vector<uint8_t> ntf_value_x11({0x11, 0, 2});
  const std::vector<uint8_t> vol_x12({0x04, /*change_cnt*/ 2, 0x12});
  std::vector<uint8_t> ntf_value_x12({0x12, 0, 3});
  const std::vector<uint8_t> vol_x13({0x04, /*change_cnt*/ 3, 0x13});
  std::vector<uint8_t> ntf_value_x13({0x13, 0, 4});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x11, GATT_WRITE, _, _)).Times(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x12, GATT_WRITE, _, _)).Times(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x13, GATT_WRITE, _, _)).Times(1);

  VolumeControl::Get()->SetVolume(test_address, 0x10);
  VolumeControl::Get()->SetVolume(test_address, 0x11);
  GetNotificationEvent(0x0021, ntf_value_x10);
  GetNotificationEvent(0x0021, ntf_value_x11);
  VolumeControl::Get()->SetVolume(test_address, 0x12);
  VolumeControl::Get()->SetVolume(test_address, 0x13);
  GetNotificationEvent(0x0021, ntf_value_x12);
  GetNotificationEvent(0x0021, ntf_value_x13);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_set_volume_stress_3) {
  uint8_t change_cnt = 0;
  uint8_t vol = 1;

  // In this test we simulate notification coming later and operations will be queued but some will
  // be removed from the queue
  ON_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, _, GATT_WRITE, _, _))
          .WillByDefault([](uint16_t conn_id, uint16_t handle, std::vector<uint8_t> value,
                            tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb, void* cb_data) {
            uint8_t write_rsp;

            switch (value[0]) {
              case 0x04:  // set abs. volume
                break;
              default:
                break;
            }
            cb(conn_id, GATT_SUCCESS, handle, 0, &write_rsp, cb_data);
          });

  const std::vector<uint8_t> vol_x10({0x04, /*change_cnt*/ 0, 0x10});
  std::vector<uint8_t> ntf_value_x10({0x10, 0, 1});
  const std::vector<uint8_t> vol_x11({0x04, /*change_cnt*/ 1, 0x11});
  std::vector<uint8_t> ntf_value_x11({0x11, 0, 2});
  const std::vector<uint8_t> vol_x12({0x04, /*change_cnt*/ 1, 0x12});
  std::vector<uint8_t> ntf_value_x12({0x12, 0, 3});
  const std::vector<uint8_t> vol_x13({0x04, /*change_cnt*/ 1, 0x13});
  std::vector<uint8_t> ntf_value_x13({0x13, 0, 4});

  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x10, GATT_WRITE, _, _)).Times(1);

  // Those two belowe will be removed from the queue
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x11, GATT_WRITE, _, _)).Times(0);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x12, GATT_WRITE, _, _)).Times(0);

  // This one shall be sent with a change count 1.
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, vol_x13, GATT_WRITE, _, _)).Times(1);

  VolumeControl::Get()->SetVolume(test_address, 0x10);
  VolumeControl::Get()->SetVolume(test_address, 0x11);
  VolumeControl::Get()->SetVolume(test_address, 0x12);
  VolumeControl::Get()->SetVolume(test_address, 0x13);
  GetNotificationEvent(0x0021, ntf_value_x10);
  GetNotificationEvent(0x0021, ntf_value_x11);
  GetNotificationEvent(0x0021, ntf_value_x12);
  GetNotificationEvent(0x0021, ntf_value_x13);

  Mock::VerifyAndClearExpectations(&gatt_queue);
}

TEST_F(VolumeControlValueSetTest, test_mute_unmute) {
  std::vector<uint8_t> mute_x0({0x06, 0x00});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute_x0, GATT_WRITE, _, _)).Times(1);
  // Don't mute when already muted
  std::vector<uint8_t> mute_x1({0x06, 0x01});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, mute_x1, GATT_WRITE, _, _)).Times(0);
  VolumeControl::Get()->Mute(test_address);
  VolumeControl::Get()->Mute(test_address);

  // Needs to be muted to unmute
  std::vector<uint8_t> unmute_x1({0x05, 0x01});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute_x1, GATT_WRITE, _, _))
          .Times(1);
  // Don't unmute when already unmuted
  std::vector<uint8_t> unmute_x2({0x05, 0x02});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0024, unmute_x2, GATT_WRITE, _, _))
          .Times(0);
  VolumeControl::Get()->UnMute(test_address);
  VolumeControl::Get()->UnMute(test_address);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_out_volume_offset) {
  std::vector<uint8_t> expected_data({0x01, 0x00, 0x34, 0x12});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x0088, expected_data, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioOutVolumeOffset(test_address, 2, 0x1234);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_out_location) {
  std::vector<uint8_t> expected_data({0x44, 0x33, 0x22, 0x11});
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(conn_id, 0x0085, expected_data, GATT_WRITE_NO_RSP, _, _));
  VolumeControl::Get()->SetExtAudioOutLocation(test_address, 2, 0x11223344);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_out_location_non_writable) {
  EXPECT_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _)).Times(0);
  VolumeControl::Get()->SetExtAudioOutLocation(test_address, 1, 0x11223344);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_out_description) {
  std::string descr = "right front";
  std::vector<uint8_t> expected_data(descr.begin(), descr.end());
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(conn_id, 0x008a, expected_data, GATT_WRITE_NO_RSP, _, _));
  VolumeControl::Get()->SetExtAudioOutDescription(test_address, 2, descr);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_out_description_non_writable) {
  std::string descr = "left front";
  EXPECT_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _)).Times(0);
  VolumeControl::Get()->SetExtAudioOutDescription(test_address, 1, descr);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_in_description) {
  std::string descr = "HDMI";
  std::vector<uint8_t> expected_data(descr.begin(), descr.end());
  EXPECT_CALL(gatt_queue,
              WriteCharacteristic(conn_id, 0x005e, expected_data, GATT_WRITE_NO_RSP, _, _));
  VolumeControl::Get()->SetExtAudioInDescription(test_address, 1, descr);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_in_description_non_writable) {
  std::string descr = "AUX";
  std::vector<uint8_t> expected_data(descr.begin(), descr.end());
  EXPECT_CALL(gatt_queue, WriteCharacteristic(_, _, _, _, _, _)).Times(0);
  VolumeControl::Get()->SetExtAudioInDescription(test_address, 0, descr);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_in_gain_setting) {
  std::vector<uint8_t> expected_data({0x01, 0x00, 0x34});
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x005c, expected_data, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioInGainSetting(test_address, 1, 0x34);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_in_gain_mode) {
  std::vector<uint8_t> mode_manual({0x04, 0x00});  // 0x04 is the opcode for Manual
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x005c, mode_manual, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioInGainMode(test_address, 1, GainMode::MANUAL);
  std::vector<uint8_t> mode_automatic({0x05, 0x00});  // 0x05 is the opcode for Automatic
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x005c, mode_automatic, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioInGainMode(test_address, 1, GainMode::AUTOMATIC);
}

TEST_F(VolumeControlValueSetTest, test_set_ext_audio_in_gain_mute) {
  std::vector<uint8_t> mute({0x03, 0x00});  // 0x03 is the opcode for Mute
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x005c, mute, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioInMute(test_address, 1, Mute::MUTED);
  std::vector<uint8_t> unmute({0x02, 0x00});  // 0x02 is the opcode for UnMute
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id, 0x005c, unmute, GATT_WRITE, _, _));
  VolumeControl::Get()->SetExtAudioInMute(test_address, 1, Mute::NOT_MUTED);
}

class VolumeControlCsis : public VolumeControlTest {
protected:
  const RawAddress test_address_1 = GetTestAddress(0);
  const RawAddress test_address_2 = GetTestAddress(1);
  std::vector<RawAddress> csis_group = {test_address_1, test_address_2};

  uint16_t conn_id_1 = 22;
  uint16_t conn_id_2 = 33;
  int group_id = 5;

  void SetUp(void) override {
    VolumeControlTest::SetUp();

    ON_CALL(mock_csis_client_module_, Get()).WillByDefault(Return(&mock_csis_client_module_));

    // Report working CSIS
    ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

    ON_CALL(mock_csis_client_module_, GetDeviceList(_)).WillByDefault(Return(csis_group));

    ON_CALL(mock_csis_client_module_, GetGroupId(_, _)).WillByDefault(Return(group_id));

    SetSampleDatabase(conn_id_1);
    SetSampleDatabase(conn_id_2);

    TestAppRegister();
  }

  void TearDown(void) override {
    TestAppUnregister();
    VolumeControlTest::TearDown();
  }

  void GetNotificationEvent(uint16_t conn_id, const RawAddress& test_address, uint16_t handle,
                            const std::vector<uint8_t>& value) {
    tBTA_GATTC_NOTIFY event_data = {
            .conn_id = conn_id,
            .bda = test_address,
            .handle = handle,
            .len = (uint8_t)value.size(),
            .is_notify = true,
    };

    std::copy(value.begin(), value.end(), event_data.value);
    gatt_callback(BTA_GATTC_NOTIF_EVT, reinterpret_cast<tBTA_GATTC*>(&event_data));
  }
};

TEST_F(VolumeControlCsis, test_set_volume) {
  TestConnect(test_address_1);
  GetConnectedEvent(test_address_1, conn_id_1);
  GetSearchCompleteEvent(conn_id_1);
  TestConnect(test_address_2);
  GetConnectedEvent(test_address_2, conn_id_2);
  GetSearchCompleteEvent(conn_id_2);

  /* Set value for the group */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_1, 0x0024, _, GATT_WRITE, _, _));
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_2, 0x0024, _, GATT_WRITE, _, _));

  VolumeControl::Get()->SetVolume(group_id, 10);

  /* Now inject notification and make sure callback is sent up to Java layer */
  EXPECT_CALL(callbacks, OnGroupVolumeStateChanged(group_id, 0x03, true, false));

  std::vector<uint8_t> value({0x03, 0x01, 0x02});
  GetNotificationEvent(conn_id_1, test_address_1, 0x0021, value);
  GetNotificationEvent(conn_id_2, test_address_2, 0x0021, value);

  /* Verify exactly one operation with this exact value is queued for each
   * device */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_1, 0x0024, _, GATT_WRITE, _, _)).Times(1);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_2, 0x0024, _, GATT_WRITE, _, _)).Times(1);
  VolumeControl::Get()->SetVolume(test_address_1, 20);
  VolumeControl::Get()->SetVolume(test_address_2, 20);
  VolumeControl::Get()->SetVolume(test_address_1, 20);
  VolumeControl::Get()->SetVolume(test_address_2, 20);

  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address_1, 20, false, _, false));
  EXPECT_CALL(callbacks, OnVolumeStateChanged(test_address_2, 20, false, _, false));
  std::vector<uint8_t> value2({20, 0x00, 0x03});
  GetNotificationEvent(conn_id_1, test_address_1, 0x0021, value2);
  GetNotificationEvent(conn_id_2, test_address_2, 0x0021, value2);
}

TEST_F(VolumeControlCsis, test_set_volume_device_not_ready) {
  /* Make sure we did not get responds to the initial reads,
   * so that the device was not marked as ready yet.
   */
  do_not_respond_to_reads = true;

  TestConnect(test_address_1);
  GetConnectedEvent(test_address_1, conn_id_1);
  GetSearchCompleteEvent(conn_id_1);
  TestConnect(test_address_2);
  GetConnectedEvent(test_address_2, conn_id_2);
  GetSearchCompleteEvent(conn_id_2);

  /* Set value for the group */
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_1, 0x0024, _, GATT_WRITE, _, _)).Times(0);
  EXPECT_CALL(gatt_queue, WriteCharacteristic(conn_id_2, 0x0024, _, GATT_WRITE, _, _)).Times(0);

  VolumeControl::Get()->SetVolume(group_id, 10);
}

TEST_F(VolumeControlCsis, autonomus_test_set_volume) {
  TestConnect(test_address_1);
  GetConnectedEvent(test_address_1, conn_id_1);
  GetSearchCompleteEvent(conn_id_1);
  TestConnect(test_address_2);
  GetConnectedEvent(test_address_2, conn_id_2);
  GetSearchCompleteEvent(conn_id_2);

  /* Now inject notification and make sure callback is sent up to Java layer */
  EXPECT_CALL(callbacks, OnGroupVolumeStateChanged(group_id, 0x03, false, true));

  std::vector<uint8_t> value({0x03, 0x00, 0x02});
  GetNotificationEvent(conn_id_1, test_address_1, 0x0021, value);
  GetNotificationEvent(conn_id_2, test_address_2, 0x0021, value);
}

TEST_F(VolumeControlCsis, autonomus_single_device_test_set_volume) {
  TestConnect(test_address_1);
  GetConnectedEvent(test_address_1, conn_id_1);
  GetSearchCompleteEvent(conn_id_1);
  TestConnect(test_address_2);
  GetConnectedEvent(test_address_2, conn_id_2);
  GetSearchCompleteEvent(conn_id_2);

  /* Disconnect one device. */
  EXPECT_CALL(callbacks, OnConnectionState(ConnectionState::DISCONNECTED, test_address_1));
  GetDisconnectedEvent(test_address_1, conn_id_1);

  /* Now inject notification and make sure callback is sent up to Java layer */
  EXPECT_CALL(callbacks, OnGroupVolumeStateChanged(group_id, 0x03, false, true));

  std::vector<uint8_t> value({0x03, 0x00, 0x02});
  GetNotificationEvent(conn_id_2, test_address_2, 0x0021, value);
}

}  // namespace
}  // namespace internal
}  // namespace vc
}  // namespace bluetooth
