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

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include <chrono>

#include "bta/csis/csis_types.h"
#include "bta_gatt_api_mock.h"
#include "bta_gatt_queue_mock.h"
#include "bta_groups.h"
#include "bta_le_audio_api.h"
#include "bta_le_audio_broadcaster_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/mock_core_callbacks.h"
#include "btif_storage_mock.h"
#include "btm_api_mock.h"
#include "btm_iso_api.h"
#include "common/message_loop_thread.h"
#include "fake_osi.h"
#include "gatt/database_builder.h"
#include "hardware/bt_gatt_types.h"
#include "hardware/bt_le_audio.h"
#include "hci/controller_interface_mock.h"
#include "internal_include/stack_config.h"
#include "le_audio/codec_manager.h"
#include "le_audio/mock_codec_interface.h"
#include "le_audio_health_status.h"
#include "le_audio_set_configuration_provider.h"
#include "le_audio_types.h"
#include "mock_codec_manager.h"
#include "mock_csis_client.h"
#include "mock_device_groups.h"
#include "mock_state_machine.h"
#include "osi/include/properties.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_stack_btm_iso.h"

#define TEST_BT com::android::bluetooth::flags

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

using bluetooth::Uuid;

using namespace bluetooth::le_audio;

using bluetooth::le_audio::LeAudioCodecConfiguration;
using bluetooth::le_audio::LeAudioDeviceGroup;
using bluetooth::le_audio::LeAudioHealthStatus;
using bluetooth::le_audio::LeAudioSinkAudioHalClient;
using bluetooth::le_audio::LeAudioSourceAudioHalClient;

using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::DsaModes;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::BidirectionalPair;
using bluetooth::le_audio::types::LeAudioContextType;

extern struct fake_osi_alarm_set_on_mloop fake_osi_alarm_set_on_mloop_;

constexpr int max_num_of_ases = 5;
constexpr bluetooth::le_audio::types::LeAudioContextType kLeAudioDefaultConfigurationContext =
        bluetooth::le_audio::types::LeAudioContextType::UNSPECIFIED;

static constexpr char kNotifyUpperLayerAboutGroupBeingInIdleDuringCall[] =
        "persist.bluetooth.leaudio.notify.idle.during.call";

// Disables most likely false-positives from base::SplitString()
extern "C" const char* __asan_default_options();
extern "C" const char* __asan_default_options() { return "detect_container_overflow=0"; }

std::atomic<int> num_async_tasks;
bluetooth::common::MessageLoopThread message_loop_thread("test message loop");
bluetooth::common::MessageLoopThread* get_main_thread() { return &message_loop_thread; }

bt_status_t do_in_main_thread(base::OnceClosure task) {
  // Wrap the task with task counter so we could later know if there are
  // any callbacks scheduled and we should wait before performing some actions
  if (!message_loop_thread.DoInThread(base::BindOnce(
              [](base::OnceClosure task, std::atomic<int>& num_async_tasks) {
                std::move(task).Run();
                num_async_tasks--;
              },
              std::move(task), std::ref(num_async_tasks)))) {
    bluetooth::log::error("failed to post task to task runner!");
    return BT_STATUS_FAIL;
  }
  num_async_tasks++;
  return BT_STATUS_SUCCESS;
}

bt_status_t do_in_main_thread_delayed(base::OnceClosure task, std::chrono::microseconds /*delay*/) {
  /* For testing purpose it is ok to just skip delay */
  return do_in_main_thread(std::move(task));
}

static void init_message_loop_thread() {
  num_async_tasks = 0;
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling()) {
    bluetooth::log::error("Unable to set real time scheduling");
  }
}

static void cleanup_message_loop_thread() { message_loop_thread.ShutDown(); }

const tBLE_BD_ADDR BTM_Sec_GetAddressWithType(const RawAddress& bd_addr) {
  return tBLE_BD_ADDR{.type = BLE_ADDR_PUBLIC, .bda = bd_addr};
}

void invoke_switch_codec_cb(bool /*is_low_latency_buffer_size*/) {}
void invoke_switch_buffer_size_cb(bool /*is_low_latency_buffer_size*/) {}

const std::string kSmpOptions("mock smp options");
static bool get_pts_avrcp_test(void) { return false; }
static bool get_pts_secure_only_mode(void) { return false; }
static bool get_pts_conn_updates_disabled(void) { return false; }
static bool get_pts_crosskey_sdp_disable(void) { return false; }
static const std::string* get_pts_smp_options(void) { return &kSmpOptions; }
static int get_pts_smp_failure_case(void) { return 123; }
static bool get_pts_force_eatt_for_notifications(void) { return false; }
static bool get_pts_connect_eatt_unconditionally(void) { return false; }
static bool get_pts_connect_eatt_before_encryption(void) { return false; }
static bool get_pts_unencrypt_broadcast(void) { return false; }
static bool get_pts_eatt_peripheral_collision_support(void) { return false; }
static bool get_pts_force_le_audio_multiple_contexts_metadata(void) { return false; }
static bool get_pts_le_audio_disable_ases_before_stopping(void) { return false; }
static config_t* get_all(void) { return nullptr; }

stack_config_t mock_stack_config{
        .get_pts_avrcp_test = get_pts_avrcp_test,
        .get_pts_secure_only_mode = get_pts_secure_only_mode,
        .get_pts_conn_updates_disabled = get_pts_conn_updates_disabled,
        .get_pts_crosskey_sdp_disable = get_pts_crosskey_sdp_disable,
        .get_pts_smp_options = get_pts_smp_options,
        .get_pts_smp_failure_case = get_pts_smp_failure_case,
        .get_pts_force_eatt_for_notifications = get_pts_force_eatt_for_notifications,
        .get_pts_connect_eatt_unconditionally = get_pts_connect_eatt_unconditionally,
        .get_pts_connect_eatt_before_encryption = get_pts_connect_eatt_before_encryption,
        .get_pts_unencrypt_broadcast = get_pts_unencrypt_broadcast,
        .get_pts_eatt_peripheral_collision_support = get_pts_eatt_peripheral_collision_support,
        .get_pts_force_le_audio_multiple_contexts_metadata =
                get_pts_force_le_audio_multiple_contexts_metadata,
        .get_pts_le_audio_disable_ases_before_stopping =
                get_pts_le_audio_disable_ases_before_stopping,
        .get_all = get_all,
};
const stack_config_t* stack_config_get_interface(void) { return &mock_stack_config; }

bool LeAudioBroadcaster::IsLeAudioBroadcasterRunning() { return false; }

namespace bluetooth::le_audio {
class MockLeAudioSourceHalClient;
MockLeAudioSourceHalClient* mock_le_audio_source_hal_client_;
std::unique_ptr<LeAudioSourceAudioHalClient> owned_mock_le_audio_source_hal_client_;
bool is_audio_unicast_source_acquired;

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireUnicast() {
  if (is_audio_unicast_source_acquired) {
    return nullptr;
  }
  is_audio_unicast_source_acquired = true;
  return std::move(owned_mock_le_audio_source_hal_client_);
}

void LeAudioSourceAudioHalClient::DebugDump(int /*fd*/) {}

class MockLeAudioSinkHalClient;
MockLeAudioSinkHalClient* mock_le_audio_sink_hal_client_;
std::unique_ptr<LeAudioSinkAudioHalClient> owned_mock_le_audio_sink_hal_client_;
bool is_audio_unicast_sink_acquired;

std::unique_ptr<LeAudioSinkAudioHalClient> LeAudioSinkAudioHalClient::AcquireUnicast() {
  if (is_audio_unicast_sink_acquired) {
    return nullptr;
  }
  is_audio_unicast_sink_acquired = true;
  return std::move(owned_mock_le_audio_sink_hal_client_);
}

void LeAudioSinkAudioHalClient::DebugDump(int /*fd*/) {}

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index}};
  return result;
}

class MockAudioHalClientCallbacks : public bluetooth::le_audio::LeAudioClientCallbacks {
public:
  MOCK_METHOD((void), OnInitialized, (), (override));
  MOCK_METHOD((void), OnConnectionState, (ConnectionState state, const RawAddress& address),
              (override));
  MOCK_METHOD((void), OnGroupStatus, (int group_id, GroupStatus group_status), (override));
  MOCK_METHOD((void), OnGroupStreamStatus, (int group_id, GroupStreamStatus group_stream_status),
              (override));
  MOCK_METHOD((void), OnGroupNodeStatus,
              (const RawAddress& bd_addr, int group_id, GroupNodeStatus node_status), (override));
  MOCK_METHOD((void), OnAudioConf,
              (uint8_t direction, int group_id, std::optional<std::bitset<32>> snk_audio_location,
               std::optional<std::bitset<32>> src_audio_location, uint16_t avail_cont),
              (override));
  MOCK_METHOD(void, OnSinkAudioLocationAvailable,
              (const RawAddress& bd_addr, std::optional<std::bitset<32>> snk_audio_location),
              (override));
  MOCK_METHOD((void), OnAudioLocalCodecCapabilities,
              (std::vector<btle_audio_codec_config_t> local_input_capa_codec_conf,
               std::vector<btle_audio_codec_config_t> local_output_capa_codec_conf),
              (override));
  MOCK_METHOD((void), OnAudioGroupCurrentCodecConf,
              (int group_id, btle_audio_codec_config_t input_codec_conf,
               btle_audio_codec_config_t output_codec_conf),
              (override));
  MOCK_METHOD((void), OnAudioGroupSelectableCodecConf,
              (int group_id, std::vector<btle_audio_codec_config_t> input_selectable_codec_conf,
               std::vector<btle_audio_codec_config_t> output_selectable_codec_conf),
              (override));
  MOCK_METHOD((void), OnHealthBasedRecommendationAction,
              (const RawAddress& address, LeAudioHealthBasedAction action), (override));
  MOCK_METHOD((void), OnHealthBasedGroupRecommendationAction,
              (int group_id, LeAudioHealthBasedAction action), (override));
  MOCK_METHOD((void), OnUnicastMonitorModeStatus,
              (uint8_t direction, UnicastMonitorModeStatus status));
};

class MockLeAudioSinkHalClient : public LeAudioSinkAudioHalClient {
public:
  MockLeAudioSinkHalClient() = default;
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioSinkAudioHalClient::Callbacks* audioReceiver, DsaModes dsa_modes),
              (override));
  MOCK_METHOD((void), Stop, (), (override));
  MOCK_METHOD((size_t), SendData, (uint8_t* data, uint16_t size), (override));
  MOCK_METHOD((void), ConfirmStreamingRequest, (), (override));
  MOCK_METHOD((void), CancelStreamingRequest, (), (override));
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay), (override));
  MOCK_METHOD((void), UpdateAudioConfigToHal, (const ::bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD((void), SuspendedForReconfiguration, (), (override));
  MOCK_METHOD((void), ReconfigurationComplete, (), (override));

  MOCK_METHOD((void), OnDestroyed, ());
  virtual ~MockLeAudioSinkHalClient() override { OnDestroyed(); }
};

class MockLeAudioSourceHalClient : public LeAudioSourceAudioHalClient {
public:
  MockLeAudioSourceHalClient() = default;
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioSourceAudioHalClient::Callbacks* audioReceiver, DsaModes dsa_modes),
              (override));
  MOCK_METHOD((void), Stop, (), (override));
  MOCK_METHOD((void), ConfirmStreamingRequest, (), (override));
  MOCK_METHOD((void), CancelStreamingRequest, (), (override));
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay), (override));
  MOCK_METHOD((void), UpdateAudioConfigToHal, (const ::bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD((std::optional<broadcaster::BroadcastConfiguration>), GetBroadcastConfig,
              ((const std::vector<std::pair<types::LeAudioContextType, uint8_t>>&),
               (const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>&)),
              (const override));
  MOCK_METHOD((std::optional<::bluetooth::le_audio::types::AudioSetConfiguration>),
              GetUnicastConfig, (const CodecManager::UnicastConfigurationRequirements&),
              (const override));
  MOCK_METHOD((void), UpdateBroadcastAudioConfigToHal,
              (const ::bluetooth::le_audio::broadcast_offload_config&), (override));
  MOCK_METHOD((void), SuspendedForReconfiguration, (), (override));
  MOCK_METHOD((void), ReconfigurationComplete, (), (override));

  MOCK_METHOD((void), OnDestroyed, ());
  virtual ~MockLeAudioSourceHalClient() override { OnDestroyed(); }
};

class UnicastTestNoInit : public Test {
public:
  bool use_handover_mode = false;

protected:
  void RegisterSourceHalClientMock() {
    owned_mock_le_audio_source_hal_client_.reset(new NiceMock<MockLeAudioSourceHalClient>());
    mock_le_audio_source_hal_client_ =
            (MockLeAudioSourceHalClient*)owned_mock_le_audio_source_hal_client_.get();

    is_audio_unicast_source_acquired = false;
    ON_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _))
            .WillByDefault([this](const LeAudioCodecConfiguration& /*codec_configuration*/,
                                  LeAudioSourceAudioHalClient::Callbacks* audioReceiver,
                                  DsaModes /*dsa_modes*/) {
              unicast_source_hal_cb_ = audioReceiver;
              return true;
            });
    ON_CALL(*mock_le_audio_source_hal_client_, OnDestroyed).WillByDefault([]() {
      mock_le_audio_source_hal_client_ = nullptr;
      is_audio_unicast_source_acquired = false;
    });
  }

  void RegisterSinkHalClientMock() {
    owned_mock_le_audio_sink_hal_client_.reset(new NiceMock<MockLeAudioSinkHalClient>());
    mock_le_audio_sink_hal_client_ =
            (MockLeAudioSinkHalClient*)owned_mock_le_audio_sink_hal_client_.get();

    is_audio_unicast_sink_acquired = false;
    ON_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _))
            .WillByDefault([this](const LeAudioCodecConfiguration& /*codec_configuration*/,
                                  LeAudioSinkAudioHalClient::Callbacks* audioReceiver,
                                  DsaModes /*dsa_modes*/) {
              unicast_sink_hal_cb_ = audioReceiver;
              return true;
            });
    ON_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed).WillByDefault([]() {
      mock_le_audio_sink_hal_client_ = nullptr;
      is_audio_unicast_sink_acquired = false;
    });
  }

  void SetUpMockAudioHal() {
    /* Since these are returned by the Acquire() methods as unique_ptrs, we
     * will not free them manually.
     */
    RegisterSourceHalClientMock();

    owned_mock_le_audio_sink_hal_client_.reset(new NiceMock<MockLeAudioSinkHalClient>());
    mock_le_audio_sink_hal_client_ =
            (MockLeAudioSinkHalClient*)owned_mock_le_audio_sink_hal_client_.get();

    owned_mock_le_audio_source_hal_client_.reset(new NiceMock<MockLeAudioSourceHalClient>());
    mock_le_audio_source_hal_client_ =
            (MockLeAudioSourceHalClient*)owned_mock_le_audio_source_hal_client_.get();

    is_audio_unicast_source_acquired = false;
    ON_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _))
            .WillByDefault([this](const LeAudioCodecConfiguration& /*codec_configuration*/,
                                  LeAudioSourceAudioHalClient::Callbacks* audioReceiver,
                                  DsaModes /*dsa_modes*/) {
              unicast_source_hal_cb_ = audioReceiver;
              return true;
            });
    ON_CALL(*mock_le_audio_source_hal_client_, OnDestroyed).WillByDefault([]() {
      mock_le_audio_source_hal_client_ = nullptr;
      is_audio_unicast_source_acquired = false;
    });

    is_audio_unicast_sink_acquired = false;
    ON_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _))
            .WillByDefault([this](const LeAudioCodecConfiguration& /*codec_configuration*/,
                                  LeAudioSinkAudioHalClient::Callbacks* audioReceiver,
                                  DsaModes /*dsa_modes*/) {
              unicast_sink_hal_cb_ = audioReceiver;
              return true;
            });
    ON_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed).WillByDefault([]() {
      mock_le_audio_sink_hal_client_ = nullptr;
      is_audio_unicast_sink_acquired = false;
    });

    ON_CALL(*mock_le_audio_sink_hal_client_, SendData)
            .WillByDefault([](uint8_t* /*data*/, uint16_t size) { return size; });

    // HAL
    ON_CALL(mock_hal_2_1_verifier, Call()).WillByDefault([]() -> bool { return true; });
  }

  void InjectGroupDeviceRemoved(const RawAddress& address, int group_id) {
    group_callbacks_->OnGroupMemberRemoved(address, group_id);
  }

  void InjectGroupDeviceAdded(const RawAddress& address, int group_id) {
    bluetooth::Uuid uuid = bluetooth::le_audio::uuid::kCapServiceUuid;

    int group_members_num = 0;
    for (const auto& [addr, id] : groups) {
      if (id == group_id) {
        group_members_num++;
      }
    }

    bool first_device = (group_members_num == 1);
    do_in_main_thread(base::BindOnce(
            [](const RawAddress& addr, int group_id, bluetooth::Uuid uuid,
               bluetooth::groups::DeviceGroupsCallbacks* group_callbacks, bool first_device) {
              if (first_device) {
                group_callbacks->OnGroupAdded(addr, uuid, group_id);
              } else {
                group_callbacks->OnGroupMemberAdded(addr, group_id);
              }
            },
            address, group_id, uuid, base::Unretained(this->group_callbacks_), first_device));
  }

  void InjectServiceChangedEvent(const RawAddress& address, uint16_t conn_id) {
    tBTA_GATTC_SERVICE_CHANGED event_data = {.remote_bda = address, .conn_id = conn_id};

    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_SERVICE_CHANGED event_data) {
              gatt_callback(BTA_GATTC_SRVC_CHG_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectConnectedEvent(const RawAddress& address, uint16_t conn_id,
                            tGATT_STATUS status = GATT_SUCCESS) {
    ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
    tBTA_GATTC_OPEN event_data = {
            .status = status,
            .conn_id = conn_id,
            .client_if = gatt_if,
            .remote_bda = address,
            .transport = BT_TRANSPORT_LE,
            .mtu = 240,
    };

    if (status == GATT_SUCCESS) {
      ASSERT_NE(peer_devices.count(conn_id), 0u);
      peer_devices.at(conn_id)->connected = true;
    }

    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_OPEN event_data) {
              gatt_callback(BTA_GATTC_OPEN_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectEncryptionChangedEvent(const RawAddress& address) {
    tBTA_GATTC_ENC_CMPL_CB event_data = {
            .client_if = gatt_if,
            .remote_bda = address,
    };

    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_ENC_CMPL_CB event_data) {
              gatt_callback(BTA_GATTC_ENC_CMPL_CB_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void TriggerDisconnectionFromApp(const RawAddress& address) {
    do_in_main_thread(base::BindOnce(
            [](LeAudioClient* client, const RawAddress& address) { client->Disconnect(address); },
            LeAudioClient::Get(), address));
  }

  void InjectDisconnectedEvent(uint16_t conn_id,
                               tGATT_DISCONN_REASON reason = GATT_CONN_TERMINATE_LOCAL_HOST) {
    ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
    ASSERT_NE(peer_devices.count(conn_id), 0u);

    tBTA_GATTC_CLOSE event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
            .client_if = gatt_if,
            .remote_bda = peer_devices.at(conn_id)->addr,
            .reason = reason,
    };

    peer_devices.at(conn_id)->connected = false;
    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_CLOSE event_data) {
              gatt_callback(BTA_GATTC_CLOSE_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectPhyChangedEvent(uint16_t conn_id, uint8_t tx_phy, uint8_t rx_phy,
                             tGATT_STATUS status) {
    ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
    tBTA_GATTC_PHY_UPDATE event_data = {
            .conn_id = conn_id,
            .tx_phy = tx_phy,
            .rx_phy = rx_phy,
            .status = status,
    };

    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_PHY_UPDATE event_data) {
              gatt_callback(BTA_GATTC_PHY_UPDATE_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectSearchCompleteEvent(uint16_t conn_id) {
    ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
    tBTA_GATTC_SEARCH_CMPL event_data = {
            .conn_id = conn_id,
            .status = GATT_SUCCESS,
    };

    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_SEARCH_CMPL event_data) {
              gatt_callback(BTA_GATTC_SEARCH_CMPL_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectNotificationEvent(const RawAddress& test_address, uint16_t conn_id, uint16_t handle,
                               std::vector<uint8_t> value) {
    ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
    tBTA_GATTC_NOTIFY event_data = {
            .conn_id = conn_id,
            .bda = test_address,
            .handle = handle,
            .len = (uint8_t)value.size(),
            .is_notify = true,
    };

    std::copy(value.begin(), value.end(), event_data.value);
    do_in_main_thread(base::BindOnce(
            [](tBTA_GATTC_CBACK* gatt_callback, tBTA_GATTC_NOTIFY event_data) {
              gatt_callback(BTA_GATTC_NOTIF_EVT, (tBTA_GATTC*)&event_data);
            },
            base::Unretained(this->gatt_callback), event_data));
  }

  void InjectContextTypes(const RawAddress& test_address, uint16_t conn_id, uint16_t handle,
                          AudioContexts sink_ctxs, AudioContexts source_ctxs) {
    std::vector<uint8_t> contexts = {
            (uint8_t)(sink_ctxs.value()), (uint8_t)(sink_ctxs.value() >> 8),
            (uint8_t)(source_ctxs.value()), (uint8_t)(source_ctxs.value() >> 8)};

    InjectNotificationEvent(test_address, conn_id, handle, contexts);
  }

  void InjectSupportedContextTypes(const RawAddress& test_address, uint16_t conn_id,
                                   AudioContexts sink_ctxs, AudioContexts source_ctxs) {
    /* 0x0077 pacs->supp_contexts_char + 1 */
    InjectContextTypes(test_address, conn_id, 0x0077, sink_ctxs, source_ctxs);
    SyncOnMainLoop();
  }

  void InjectAvailableContextTypes(const RawAddress& test_address, uint16_t conn_id,
                                   AudioContexts sink_ctxs, AudioContexts source_ctxs,
                                   bool sync_on_mainloop = true) {
    /* 0x0074 is pacs->avail_contexts_char + 1 */
    InjectContextTypes(test_address, conn_id, 0x0074, sink_ctxs, source_ctxs);
    if (sync_on_mainloop) {
      SyncOnMainLoop();
    }
  }

  void SetUpMockGatt() {
    // default action for GetCharacteristic function call
    ON_CALL(mock_gatt_interface_, GetCharacteristic(_, _))
            .WillByDefault(
                    Invoke([&](uint16_t conn_id, uint16_t handle) -> const gatt::Characteristic* {
                      std::list<gatt::Service>& services = peer_devices.at(conn_id)->services;
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
    ON_CALL(mock_gatt_interface_, GetOwningService(_, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, uint16_t handle) -> const gatt::Service* {
              std::list<gatt::Service>& services = peer_devices.at(conn_id)->services;
              for (auto const& service : services) {
                if (service.handle <= handle && service.end_handle >= handle) {
                  return &service;
                }
              }

              return nullptr;
            }));

    // default action for ServiceSearchRequest function call
    ON_CALL(mock_gatt_interface_, ServiceSearchRequest(_, _))
            .WillByDefault(WithArg<0>(
                    Invoke([&](uint16_t conn_id) { InjectSearchCompleteEvent(conn_id); })));

    // default action for GetServices function call
    ON_CALL(mock_gatt_interface_, GetServices(_))
            .WillByDefault(WithArg<0>(Invoke([&](uint16_t conn_id) -> std::list<gatt::Service>* {
              return &peer_devices.at(conn_id)->services;
            })));

    // default action for RegisterForNotifications function call
    ON_CALL(mock_gatt_interface_, RegisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    // default action for DeregisterForNotifications function call
    ON_CALL(mock_gatt_interface_, DeregisterForNotifications(gatt_if, _, _))
            .WillByDefault(Return(GATT_SUCCESS));

    // default action for WriteDescriptor function call
    ON_CALL(mock_gatt_queue_, WriteDescriptor(_, _, _, _, _, _))
            .WillByDefault(Invoke([this](uint16_t conn_id, uint16_t handle,
                                         std::vector<uint8_t> value,
                                         tGATT_WRITE_TYPE /*write_type*/, GATT_WRITE_OP_CB cb,
                                         void* cb_data) -> void {
              auto& ascs = peer_devices.at(conn_id)->ascs;
              uint8_t idx;

              if (handle == ascs->ctp_ccc) {
                value = UINT16_TO_VEC_UINT8(ascs->ctp_ccc_val);
              } else {
                for (idx = 0; idx < max_num_of_ases; idx++) {
                  if (handle == ascs->sink_ase_ccc[idx] + 1) {
                    value = UINT16_TO_VEC_UINT8(ascs->sink_ase_ccc_val[idx]);
                    break;
                  }
                  if (handle == ascs->source_ase_char[idx] + 1) {
                    value = UINT16_TO_VEC_UINT8(ascs->source_ase_ccc_val[idx]);
                    break;
                  }
                }
              }

              if (cb) {
                do_in_main_thread(base::BindOnce(
                        [](GATT_WRITE_OP_CB cb, uint16_t conn_id, uint16_t handle, uint16_t len,
                           uint8_t* value, void* cb_data) {
                          cb(conn_id, GATT_SUCCESS, handle, len, value, cb_data);
                        },
                        cb, conn_id, handle, value.size(), value.data(), cb_data));
              }
            }));

    global_conn_id = 1;
    ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _))
            .WillByDefault(Invoke([&](tGATT_IF /*client_if*/, const RawAddress& remote_bda,
                                      bool /*is_direct*/, bool /*opportunistic*/) {
              InjectConnectedEvent(remote_bda, global_conn_id++);
            }));

    ON_CALL(mock_gatt_interface_, Close(_)).WillByDefault(Invoke([&](uint16_t conn_id) {
      ASSERT_NE(conn_id, GATT_INVALID_CONN_ID);
      InjectDisconnectedEvent(conn_id);
    }));

    // default Characteristic read handler dispatches requests to service mocks
    ON_CALL(mock_gatt_queue_, ReadCharacteristic(_, _, _, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, uint16_t handle, GATT_READ_OP_CB cb,
                                      void* cb_data) {
              do_in_main_thread(base::BindOnce(
                      [](std::map<uint16_t, std::unique_ptr<NiceMock<MockDeviceWrapper>>>*
                                 peer_devices,
                         uint16_t conn_id, uint16_t handle, GATT_READ_OP_CB cb,
                         void* cb_data) -> void {
                        if (peer_devices->count(conn_id)) {
                          auto& device = peer_devices->at(conn_id);
                          auto svc = std::find_if(device->services.begin(), device->services.end(),
                                                  [handle](const gatt::Service& svc) {
                                                    return (handle >= svc.handle) &&
                                                           (handle <= svc.end_handle);
                                                  });
                          if (svc == device->services.end()) {
                            return;
                          }

                          GattStatus status;
                          std::vector<uint8_t> value;
                          // Dispatch to mockable handler functions
                          if (svc->handle == device->csis->start) {
                            std::tie(status, value) =
                                    device->csis->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device->cas->start) {
                            std::tie(status, value) = device->cas->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device->ascs->start) {
                            std::tie(status, value) =
                                    device->ascs->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device->pacs->start) {
                            std::tie(status, value) =
                                    device->pacs->OnGetCharacteristicValue(handle);
                          } else {
                            return;
                          }

                          cb(conn_id, status, handle, value.size(), value.data(), cb_data);
                        }
                      },
                      &peer_devices, conn_id, handle, cb, cb_data));
            }));

    // default multiple Characteristic read handler dispatches requests to service mocks
    ON_CALL(mock_gatt_queue_, ReadMultiCharacteristic(_, _, _, _))
            .WillByDefault(Invoke([&](uint16_t conn_id, tBTA_GATTC_MULTI& handles,
                                      GATT_READ_MULTI_OP_CB cb, void* cb_data) {
              do_in_main_thread(base::BindOnce(
                      [](std::map<uint16_t, std::unique_ptr<NiceMock<MockDeviceWrapper>>>*
                                 peer_devices,
                         uint16_t conn_id, tBTA_GATTC_MULTI handles, GATT_READ_MULTI_OP_CB cb,
                         void* cb_data) -> void {
                        if (!peer_devices->count(conn_id)) {
                          return;
                        }
                        auto& device = peer_devices->at(conn_id);

                        auto get_char_value_helper = [&](NiceMock<MockDeviceWrapper>& device,
                                                         uint16_t handle) {
                          auto svc = std::find_if(device.services.begin(), device.services.end(),
                                                  [handle](const gatt::Service& svc) {
                                                    return (handle >= svc.handle) &&
                                                           (handle <= svc.end_handle);
                                                  });
                          if (svc == device.services.end()) {
                            return std::make_pair(GATT_ERROR, std::vector<uint8_t>());
                          }

                          // Dispatch to mockable handler functions
                          if (svc->handle == device.csis->start) {
                            return device.csis->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device.cas->start) {
                            return device.cas->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device.ascs->start) {
                            return device.ascs->OnGetCharacteristicValue(handle);
                          } else if (svc->handle == device.pacs->start) {
                            return device.pacs->OnGetCharacteristicValue(handle);
                          } else {
                            return std::make_pair(GATT_ERROR, std::vector<uint8_t>());
                          };
                        };
                        std::array<uint8_t, GATT_MAX_ATTR_LEN> value;
                        uint16_t value_end = 0;
                        for (int i = 0; i < handles.num_attr; i++) {
                          GattStatus status;
                          std::vector<uint8_t> curr_val;
                          std::tie(status, curr_val) =
                                  get_char_value_helper(*device, handles.handles[i]);

                          if (status != GATT_SUCCESS) {
                            cb(conn_id, status, handles, 0, value.data(), cb_data);
                            return;
                          }

                          value[value_end] = (curr_val.size() & 0x00ff);
                          value[value_end + 1] = (curr_val.size() & 0xff00) >> 8;
                          value_end += 2;

                          // concatenate all read values together
                          std::copy(curr_val.begin(), curr_val.end(), value.data() + value_end);
                          value_end += curr_val.size();
                        }
                        cb(conn_id, GATT_SUCCESS, handles, value_end, value.data(), cb_data);
                      },
                      &peer_devices, conn_id, handles, cb, cb_data));
            }));
  }

  void SetUpMockGroups() {
    MockCsisClient::SetMockInstanceForTesting(&mock_csis_client_module_);
    MockDeviceGroups::SetMockInstanceForTesting(&mock_groups_module_);
    MockLeAudioGroupStateMachine::SetMockInstanceForTesting(&mock_state_machine_);

    ON_CALL(mock_csis_client_module_, Get()).WillByDefault(Return(&mock_csis_client_module_));

    // Store group callbacks so that we could inject grouping events
    group_callbacks_ = nullptr;
    ON_CALL(mock_groups_module_, Initialize(_)).WillByDefault(SaveArg<0>(&group_callbacks_));

    ON_CALL(mock_groups_module_, GetGroupId(_, _))
            .WillByDefault([this](const RawAddress& addr, bluetooth::Uuid /*uuid*/) {
              if (groups.find(addr) != groups.end()) {
                return groups.at(addr);
              }
              return bluetooth::groups::kGroupUnknown;
            });

    ON_CALL(mock_groups_module_, RemoveDevice(_, _))
            .WillByDefault([this](const RawAddress& addr, int /*group_id*/) {
              int group_id = -1;
              if (groups.find(addr) != groups.end()) {
                group_id = groups[addr];
                groups.erase(addr);
              }
              if (group_id < 0) {
                return;
              }

              do_in_main_thread(base::BindOnce(
                      [](const RawAddress& address, int group_id,
                         bluetooth::groups::DeviceGroupsCallbacks* group_callbacks) {
                        group_callbacks->OnGroupMemberRemoved(address, group_id);
                      },
                      addr, group_id, base::Unretained(group_callbacks_)));
            });

    // Our test devices have unique LSB - use it for unique grouping when
    // devices added with a non-CIS context and no grouping info
    ON_CALL(mock_groups_module_, AddDevice(_, bluetooth::le_audio::uuid::kCapServiceUuid, _))
            .WillByDefault(
                    [this](const RawAddress& addr,
                           bluetooth::Uuid /*uuid*/ = bluetooth::le_audio::uuid::kCapServiceUuid,
                           int group_id = bluetooth::groups::kGroupUnknown) -> int {
                      if (group_id == bluetooth::groups::kGroupUnknown) {
                        /* Generate group id from address */
                        groups[addr] = addr.address[RawAddress::kLength - 1];
                        group_id = groups[addr];
                      } else {
                        groups[addr] = group_id;
                      }

                      InjectGroupDeviceAdded(addr, groups[addr]);
                      return addr.address[RawAddress::kLength - 1];
                    });

    ON_CALL(mock_state_machine_, Initialize(_))
            .WillByDefault(SaveArg<0>(&state_machine_callbacks_));

    ON_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _))
            .WillByDefault([this](LeAudioDeviceGroup* group, types::LeAudioContextType context_type,
                                  types::BidirectionalPair<types::AudioContexts>
                                          metadata_context_types,
                                  types::BidirectionalPair<std::vector<uint8_t>> ccid_lists,
                                  bool configure_qos) {
              auto group_state = group->GetState();
              bool isReconfiguration = group->IsPendingConfiguration();

              log::info(
                      "ConfigureStream: group {} state {}, context type {} sink metadata_ctx {}, "
                      "source metadata_ctx {}, ccid_sink size {}, ccid_source_size {}, "
                      "isReconfiguration {}",
                      group->group_id_, bluetooth::common::ToString(group_state),
                      bluetooth::common::ToString(context_type),
                      bluetooth::common::ToString(metadata_context_types.sink),
                      bluetooth::common::ToString(metadata_context_types.source),
                      ccid_lists.sink.size(), ccid_lists.source.size(), isReconfiguration);

              /* Do what ReleaseCisIds(group) does: start */
              LeAudioDevice* leAudioDevice = group->GetFirstDevice();
              while (leAudioDevice != nullptr) {
                for (auto& ase : leAudioDevice->ases_) {
                  ase.cis_id = bluetooth::le_audio::kInvalidCisId;
                }
                leAudioDevice = group->GetNextDevice(leAudioDevice);
              }
              group->ClearAllCises();
              /* end */

              if (!group->Configure(context_type, metadata_context_types, ccid_lists)) {
                log::error("ConfigureStream: Could not configure ASEs for group {} content type {}",
                           group->group_id_, int(context_type));

                return false;
              }

              group->cig.GenerateCisIds(context_type);

              types::AseState config_state =
                      types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED;

              if (configure_qos) {
                // Make sure CIG is created
                config_state = types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED;
                group->cig.SetState(types::CigState::CREATED);
              }

              for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
                   device = group->GetNextDevice(device)) {
                if (!group->cig.AssignCisIds(device)) {
                  continue;
                }

                if (group->cig.GetState() == types::CigState::CREATED) {
                  group->AssignCisConnHandlesToAses(device);
                }

                for (auto& ase : device->ases_) {
                  if (!ase.active) {
                    continue;
                  }

                  ase.cis_state = types::CisState::IDLE;
                  ase.data_path_state = types::DataPathState::IDLE;
                  ase.state = config_state;
                }
              }

              // Inject the state
              group->SetTargetState(config_state);
              group->SetState(group->GetTargetState());
              if (group->IsPendingConfiguration()) {
                group->ClearPendingConfiguration();
                do_in_main_thread(base::BindOnce(
                        [](int group_id, bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks*
                                                 state_machine_callbacks) {
                          state_machine_callbacks->StatusReportCb(
                                  group_id, GroupStreamStatus::CONFIGURED_BY_USER);
                        },
                        group->group_id_, base::Unretained(this->state_machine_callbacks_)));
              }
              return true;
            });

    ON_CALL(mock_state_machine_, AttachToStream(_, _, _))
            .WillByDefault([this](LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                  types::BidirectionalPair<std::vector<uint8_t>> ccids) {
              log::info(
                      "AttachToStream: group_id {}, address {}, current_state {}, target_state {}",
                      group->group_id_, leAudioDevice->address_,
                      bluetooth::common::ToString(group->GetState()),
                      bluetooth::common::ToString(group->GetTargetState()));

              if (group->GetState() != types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
                if (group->GetTargetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
                  attach_to_stream_scheduled = true;
                }

                return false;
              }

              group->Configure(group->GetConfigurationContextType(), group->GetMetadataContexts(),
                               ccids);
              if (!group->cig.AssignCisIds(leAudioDevice)) {
                return false;
              }
              group->AssignCisConnHandlesToAses(leAudioDevice);

              auto* stream_conf = &group->stream_conf;

              for (auto& ase : leAudioDevice->ases_) {
                if (!ase.active) {
                  continue;
                }

                // And also skip the ase establishment procedure which should
                // be tested as part of the state machine unit tests
                ase.cis_state = types::CisState::CONNECTED;
                ase.data_path_state = types::DataPathState::CONFIGURED;
                ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;

                uint16_t cis_conn_hdl = ase.cis_conn_hdl;
                auto core_config = ase.codec_config.params.GetAsCoreCodecConfig();

                /* Copied from state_machine.cc ProcessHciNotifSetupIsoDataPath */
                if (ase.direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
                  auto iter = std::find_if(
                          stream_conf->stream_params.source.stream_config.stream_map.begin(),
                          stream_conf->stream_params.source.stream_config.stream_map.end(),
                          [cis_conn_hdl](auto& info) {
                            return cis_conn_hdl == info.stream_handle;
                          });

                  if (iter == stream_conf->stream_params.source.stream_config.stream_map.end()) {
                    stream_conf->stream_params.source.stream_config.stream_map.emplace_back(
                            stream_map_info(ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                                            true));

                    stream_conf->stream_params.source.num_of_devices++;
                    stream_conf->stream_params.source.num_of_channels +=
                            ase.codec_config.channel_count_per_iso_stream;

                    log::info(
                            "AttachToStream: Added Source Stream Configuration. CIS Connection "
                            "Handle: "
                            "{}, Audio Channel Allocation: {}, Source Number Of "
                            "Devices: {}, Source Number Of Channels: {}",
                            ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                            stream_conf->stream_params.source.num_of_devices,
                            stream_conf->stream_params.source.num_of_channels);
                  }
                } else {
                  auto iter = std::find_if(
                          stream_conf->stream_params.sink.stream_config.stream_map.begin(),
                          stream_conf->stream_params.sink.stream_config.stream_map.end(),
                          [cis_conn_hdl](auto& info) {
                            return cis_conn_hdl == info.stream_handle;
                          });

                  if (iter == stream_conf->stream_params.sink.stream_config.stream_map.end()) {
                    stream_conf->stream_params.sink.stream_config.stream_map.emplace_back(
                            stream_map_info(ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                                            true));

                    stream_conf->stream_params.sink.num_of_devices++;
                    stream_conf->stream_params.sink.num_of_channels +=
                            ase.codec_config.channel_count_per_iso_stream;

                    log::info(
                            "AttachToStream: Added Sink Stream Configuration. CIS Connection "
                            "Handle: "
                            "{}, Audio Channel Allocation: {}, Sink Number Of Devices: "
                            "{}, Sink Number Of Channels: {}",
                            ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                            stream_conf->stream_params.sink.num_of_devices,
                            stream_conf->stream_params.sink.num_of_channels);
                  }
                }
              }

              // When the device attaches to the stream we send again the state machine state to
              // stimulate the stream map update
              // see LeAudioGroupStateMachineImpl::SendStreamingStatusCbIfNeeded(group);
              if (!group->HaveAllCisesDisconnected() &&
                  (group->GetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) &&
                  (group->GetTargetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
                do_in_main_thread(base::BindOnce(
                        [](int group_id, bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks*
                                                 state_machine_callbacks) {
                          state_machine_callbacks->StatusReportCb(group_id,
                                                                  GroupStreamStatus::STREAMING);
                        },
                        group->group_id_, base::Unretained(this->state_machine_callbacks_)));
              }

              return true;
            });

    ON_CALL(mock_state_machine_, StartStream(_, _, _, _))
            .WillByDefault([this](LeAudioDeviceGroup* group, types::LeAudioContextType context_type,
                                  types::BidirectionalPair<types::AudioContexts>
                                          metadata_context_types,
                                  types::BidirectionalPair<std::vector<uint8_t>> ccid_lists) {
              auto group_state = group->GetState();
              log::info(
                      "StartStream: group {} state {}, context type {} sink metadata_ctx {}, "
                      "source metadata_ctx {}, ccid_sink size {}, ccid_source_size {}",
                      group->group_id_, bluetooth::common::ToString(group_state),
                      bluetooth::common::ToString(context_type),
                      bluetooth::common::ToString(metadata_context_types.sink),
                      bluetooth::common::ToString(metadata_context_types.source),
                      ccid_lists.sink.size(), ccid_lists.source.size());

              /* Do nothing if already streaming - the implementation would
               * probably update the metadata.
               */
              if (group_state == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
                return true;
              }

              // Inject the state
              group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

              if (group_state != types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
                /* Do what ReleaseCisIds(group) does: start */
                LeAudioDevice* leAudioDevice = group->GetFirstDevice();
                while (leAudioDevice != nullptr) {
                  for (auto& ase : leAudioDevice->ases_) {
                    ase.cis_id = bluetooth::le_audio::kInvalidCisId;
                  }
                  leAudioDevice = group->GetNextDevice(leAudioDevice);
                }
                group->ClearAllCises();
                /* end */

                if (!group->Configure(context_type, metadata_context_types, ccid_lists)) {
                  log::error("StartStream: failed to set ASE configuration");
                  return false;
                }

                if (group_state == types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE ||
                    group_state == types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) {
                  group->cig.GenerateCisIds(context_type);

                  std::vector<uint16_t> conn_handles;
                  for (uint8_t i = 0; i < (uint8_t)(group->cig.cises.size()); i++) {
                    conn_handles.push_back(iso_con_counter_++);
                  }
                  group->cig.AssignCisConnHandles(conn_handles);
                  for (LeAudioDevice* device = group->GetFirstActiveDevice(); device != nullptr;
                       device = group->GetNextActiveDevice(device)) {
                    if (!group->cig.AssignCisIds(device)) {
                      return false;
                    }
                    group->AssignCisConnHandlesToAses(device);
                  }
                }
              }

              auto* stream_conf = &group->stream_conf;

              // Fake ASE configuration
              for (LeAudioDevice* device = group->GetFirstActiveDevice(); device != nullptr;
                   device = group->GetNextActiveDevice(device)) {
                for (auto& ase : device->ases_) {
                  if (!ase.active) {
                    continue;
                  }

                  // And also skip the ase establishment procedure which should
                  // be tested as part of the state machine unit tests
                  ase.cis_state = types::CisState::CONNECTED;
                  ase.data_path_state = types::DataPathState::CONFIGURED;
                  ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;
                  ase.qos_preferences.pres_delay_min = 2500;
                  ase.qos_preferences.pres_delay_max = 2500;
                  ase.qos_preferences.preferred_pres_delay_min = 2500;
                  ase.qos_preferences.preferred_pres_delay_max = 2500;
                  auto core_config = ase.codec_config.params.GetAsCoreCodecConfig();

                  uint16_t cis_conn_hdl = ase.cis_conn_hdl;

                  /* Copied from state_machine.cc AddCisToStreamConfiguration */
                  if (ase.direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
                    auto iter = std::find_if(
                            stream_conf->stream_params.source.stream_config.stream_map.begin(),
                            stream_conf->stream_params.source.stream_config.stream_map.end(),
                            [cis_conn_hdl](auto& info) {
                              return cis_conn_hdl == info.stream_handle;
                            });

                    if (iter == stream_conf->stream_params.source.stream_config.stream_map.end()) {
                      stream_conf->stream_params.source.stream_config.stream_map.emplace_back(
                              stream_map_info(ase.cis_conn_hdl,
                                              *core_config.audio_channel_allocation, true));

                      stream_conf->stream_params.source.num_of_devices++;
                      stream_conf->stream_params.source.num_of_channels +=
                              ase.codec_config.channel_count_per_iso_stream;
                      stream_conf->stream_params.source.audio_channel_allocation |=
                              *core_config.audio_channel_allocation;
                      stream_conf->stream_params.source.stream_config.peer_delay_ms = 44;

                      if (stream_conf->stream_params.source.stream_config.sampling_frequency_hz ==
                          0) {
                        stream_conf->stream_params.source.stream_config.sampling_frequency_hz =
                                core_config.GetSamplingFrequencyHz();
                      } else {
                        log::assert_that(stream_conf->stream_params.source.stream_config
                                                         .sampling_frequency_hz ==
                                                 core_config.GetSamplingFrequencyHz(),
                                         "StartStream: sample freq mismatch: {}!={}",
                                         stream_conf->stream_params.source.stream_config
                                                 .sampling_frequency_hz,
                                         core_config.GetSamplingFrequencyHz());
                      }

                      if (stream_conf->stream_params.source.stream_config.octets_per_codec_frame ==
                          0) {
                        stream_conf->stream_params.source.stream_config.octets_per_codec_frame =
                                *core_config.octets_per_codec_frame;
                      } else {
                        log::assert_that(stream_conf->stream_params.source.stream_config
                                                         .octets_per_codec_frame ==
                                                 *core_config.octets_per_codec_frame,
                                         "StartStream: octets per frame mismatch: {}!={}",
                                         stream_conf->stream_params.source.stream_config
                                                 .octets_per_codec_frame,
                                         *core_config.octets_per_codec_frame);
                      }

                      if (stream_conf->stream_params.source.stream_config
                                  .codec_frames_blocks_per_sdu == 0) {
                        stream_conf->stream_params.source.stream_config
                                .codec_frames_blocks_per_sdu =
                                *core_config.codec_frames_blocks_per_sdu;
                        stream_conf->stream_params.source.stream_config.frame_duration_us =
                                core_config.GetFrameDurationUs();
                      } else {
                        log::assert_that(stream_conf->stream_params.source.stream_config
                                                         .codec_frames_blocks_per_sdu ==
                                                 *core_config.codec_frames_blocks_per_sdu,
                                         "StartStream: codec_frames_blocks_per_sdu: {}!={}",
                                         stream_conf->stream_params.source.stream_config
                                                 .codec_frames_blocks_per_sdu,
                                         *core_config.codec_frames_blocks_per_sdu);
                      }

                      log::info(
                              "StartStream: Added Source Stream Configuration. CIS Connection "
                              "Handle: {}, Audio Channel Allocation: {}, Source Number "
                              "Of Devices: {}, Source Number Of Channels: {}",
                              ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                              stream_conf->stream_params.source.num_of_devices,
                              stream_conf->stream_params.source.num_of_channels);
                    }
                  } else {
                    auto iter = std::find_if(
                            stream_conf->stream_params.sink.stream_config.stream_map.begin(),
                            stream_conf->stream_params.sink.stream_config.stream_map.end(),
                            [cis_conn_hdl](auto& info) {
                              return cis_conn_hdl == info.stream_handle;
                            });

                    if (iter == stream_conf->stream_params.sink.stream_config.stream_map.end()) {
                      stream_conf->stream_params.sink.stream_config.stream_map.emplace_back(
                              stream_map_info(ase.cis_conn_hdl,
                                              *core_config.audio_channel_allocation, true));

                      stream_conf->stream_params.sink.num_of_devices++;
                      stream_conf->stream_params.sink.num_of_channels +=
                              ase.codec_config.channel_count_per_iso_stream;
                      stream_conf->stream_params.sink.audio_channel_allocation |=
                              *core_config.audio_channel_allocation;
                      stream_conf->stream_params.sink.stream_config.peer_delay_ms = 44;

                      if (stream_conf->stream_params.sink.stream_config.sampling_frequency_hz ==
                          0) {
                        stream_conf->stream_params.sink.stream_config.sampling_frequency_hz =
                                core_config.GetSamplingFrequencyHz();
                      } else {
                        log::assert_that(
                                stream_conf->stream_params.sink.stream_config
                                                .sampling_frequency_hz ==
                                        core_config.GetSamplingFrequencyHz(),
                                "StartStream: sample freq mismatch: {}!={}",
                                stream_conf->stream_params.sink.stream_config.sampling_frequency_hz,
                                core_config.GetSamplingFrequencyHz());
                      }

                      if (stream_conf->stream_params.sink.stream_config.octets_per_codec_frame ==
                          0) {
                        stream_conf->stream_params.sink.stream_config.octets_per_codec_frame =
                                *core_config.octets_per_codec_frame;
                      } else {
                        log::assert_that(stream_conf->stream_params.sink.stream_config
                                                         .octets_per_codec_frame ==
                                                 *core_config.octets_per_codec_frame,
                                         "StartStream: octets per frame mismatch: {}!={}",
                                         stream_conf->stream_params.sink.stream_config
                                                 .octets_per_codec_frame,
                                         *core_config.octets_per_codec_frame);
                      }

                      if (stream_conf->stream_params.sink.stream_config
                                  .codec_frames_blocks_per_sdu == 0) {
                        stream_conf->stream_params.sink.stream_config.codec_frames_blocks_per_sdu =
                                *core_config.codec_frames_blocks_per_sdu;
                        stream_conf->stream_params.sink.stream_config.frame_duration_us =
                                core_config.GetFrameDurationUs();
                      } else {
                        log::assert_that(stream_conf->stream_params.sink.stream_config
                                                         .codec_frames_blocks_per_sdu ==
                                                 *core_config.codec_frames_blocks_per_sdu,
                                         "StartStream: codec_frames_blocks_per_sdu: {}!={}",
                                         stream_conf->stream_params.sink.stream_config
                                                 .codec_frames_blocks_per_sdu,
                                         *core_config.codec_frames_blocks_per_sdu);
                      }

                      log::info(
                              "StartStream: Added Sink Stream Configuration. CIS Connection "
                              "Handle: "
                              "{}, Audio Channel Allocation: {}, Sink Number Of "
                              "Devices: {}, Sink Number Of Channels: {}",
                              ase.cis_conn_hdl, *core_config.audio_channel_allocation,
                              stream_conf->stream_params.sink.num_of_devices,
                              stream_conf->stream_params.sink.num_of_channels);
                    }
                  }
                }
                group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
                /* Assume CIG is created */
                group->cig.SetState(bluetooth::le_audio::types::CigState::CREATED);
              }

              streaming_groups[group->group_id_] = group;

              if (stay_at_qos_config_in_start_stream) {
                return true;
              }

              group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
              // Set streaming metadata
              for (LeAudioDevice* device = group->GetFirstActiveDevice(); device != nullptr;
                   device = group->GetNextActiveDevice(device)) {
                for (auto& ase : device->ases_) {
                  if (!ase.active) {
                    continue;
                  }
                  group->SetStreamingMetadataContexts(metadata_context_types.get(ase.direction),
                                                      ase.direction);
                }
              }

              do_in_main_thread(base::BindOnce(
                      [](int group_id, bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks*
                                               state_machine_callbacks) {
                        state_machine_callbacks->StatusReportCb(group_id,
                                                                GroupStreamStatus::STREAMING);
                      },
                      group->group_id_, base::Unretained(this->state_machine_callbacks_)));
              return true;
            });

    ON_CALL(mock_state_machine_, SuspendStream(_)).WillByDefault([this](LeAudioDeviceGroup* group) {
      // Fake ASE state
      for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
           device = group->GetNextDevice(device)) {
        for (auto& ase : device->ases_) {
          ase.cis_state = types::CisState::CONNECTED;
          ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED;
        }
      }

      // Inject the state
      group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
      group->ClearStreamingMetadataContexts();
      group->SetState(group->GetTargetState());
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::SUSPENDED);
    });

    ON_CALL(mock_state_machine_, ProcessHciNotifAclDisconnected(_, _))
            .WillByDefault([this](LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
              if (!group) {
                return;
              }

              for (auto& ase : leAudioDevice->ases_) {
                group->RemoveCisFromStreamIfNeeded(leAudioDevice, ase.cis_conn_hdl);
              }

              if (group->IsEmpty()) {
                group->cig.SetState(bluetooth::le_audio::types::CigState::NONE);
                InjectCigRemoved(group->group_id_);
              }
            });

    ON_CALL(mock_state_machine_, ProcessHciNotifCisDisconnected(_, _, _))
            .WillByDefault([this](LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                  const bluetooth::hci::iso_manager::cis_disconnected_evt* event) {
              if (!group) {
                return;
              }
              auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(event->cis_conn_hdl);
              if (ases_pair.sink) {
                ases_pair.sink->cis_state = types::CisState::ASSIGNED;
                ases_pair.sink->active = false;
              }
              if (ases_pair.source) {
                ases_pair.source->active = false;
                ases_pair.source->cis_state = types::CisState::ASSIGNED;
              }

              group->RemoveCisFromStreamIfNeeded(leAudioDevice, event->cis_conn_hdl);

              // When the device detaches from the stream we send again the state machine state to
              // stimulate the stream map update
              // see LeAudioGroupStateMachineImpl::SendStreamingStatusCbIfNeeded(group);
              if (!group->HaveAllCisesDisconnected() &&
                  (group->GetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) &&
                  (group->GetTargetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
                do_in_main_thread(base::BindOnce(
                        [](int group_id, bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks*
                                                 state_machine_callbacks) {
                          state_machine_callbacks->StatusReportCb(group_id,
                                                                  GroupStreamStatus::STREAMING);
                        },
                        group->group_id_, base::Unretained(this->state_machine_callbacks_)));
              }
            });

    ON_CALL(mock_state_machine_, StopStream(_)).WillByDefault([this](LeAudioDeviceGroup* group) {
      for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
           device = group->GetNextDevice(device)) {
        /* Invalidate stream configuration if needed */
        auto* stream_conf = &group->stream_conf;
        if (!stream_conf->stream_params.sink.stream_config.stream_map.empty() ||
            !stream_conf->stream_params.source.stream_config.stream_map.empty()) {
          stream_conf->stream_params.sink.stream_config.stream_map.erase(
                  std::remove_if(stream_conf->stream_params.sink.stream_config.stream_map.begin(),
                                 stream_conf->stream_params.sink.stream_config.stream_map.end(),
                                 [device, &stream_conf](auto& info) {
                                   auto ases = device->GetAsesByCisConnHdl(info.stream_handle);

                                   log::info(
                                           ", sink ase to delete. Cis handle: {}, ase "
                                           "pointer: {}",
                                           (int)(info.stream_handle), std::format_ptr(+ases.sink));
                                   if (ases.sink) {
                                     stream_conf->stream_params.sink.num_of_devices--;
                                     stream_conf->stream_params.sink.num_of_channels -=
                                             ases.sink->codec_config.channel_count_per_iso_stream;

                                     log::info(
                                             "Sink Number Of Devices: {}, Sink Number Of "
                                             "Channels: {}",
                                             stream_conf->stream_params.sink.num_of_devices,
                                             stream_conf->stream_params.sink.num_of_channels);
                                   }
                                   return ases.sink;
                                 }),
                  stream_conf->stream_params.sink.stream_config.stream_map.end());

          stream_conf->stream_params.source.stream_config.stream_map.erase(
                  std::remove_if(stream_conf->stream_params.source.stream_config.stream_map.begin(),
                                 stream_conf->stream_params.source.stream_config.stream_map.end(),
                                 [device, &stream_conf](auto& info) {
                                   auto ases = device->GetAsesByCisConnHdl(info.stream_handle);

                                   log::info(
                                           ", source to delete. Cis handle: {}, ase pointer: "
                                           "{}",
                                           (int)(info.stream_handle),
                                           std::format_ptr(+ases.source));
                                   if (ases.source) {
                                     stream_conf->stream_params.source.num_of_devices--;
                                     stream_conf->stream_params.source.num_of_channels -=
                                             ases.source->codec_config.channel_count_per_iso_stream;

                                     log::info(
                                             ", Source Number Of Devices: {}, Source Number "
                                             "Of Channels: {}",
                                             stream_conf->stream_params.source.num_of_devices,
                                             stream_conf->stream_params.source.num_of_channels);
                                   }
                                   return ases.source;
                                 }),
                  stream_conf->stream_params.source.stream_config.stream_map.end());
        }

        group->ClearStreamingMetadataContexts();
        for (auto& ase : device->ases_) {
          group->cig.UnassignCis(device, ase.cis_conn_hdl);

          ase.cis_state = types::CisState::IDLE;
          ase.data_path_state = types::DataPathState::IDLE;
          ase.active = false;
          ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;
          ase.cis_id = 0;
          ase.cis_conn_hdl = bluetooth::le_audio::kInvalidCisConnHandle;
        }
      }

      // Inject the state
      group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
      group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::RELEASING);

      if (stay_at_releasing_stop_stream) {
        log::info("StopStream {} -> stay in Releasing state", group->group_id_);
        return;
      }
      group->SetState(group->GetTargetState());

      do_in_main_thread(base::BindOnce(
              [](bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* cb, int group_id) {
                cb->StatusReportCb(group_id, GroupStreamStatus::IDLE);
              },
              state_machine_callbacks_, group->group_id_));
    });
  }

  void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com::android::bluetooth::flags::provider_->reset_flags();

    init_message_loop_thread();
    reset_mock_function_count_map();
    ON_CALL(controller_, SupportsBleConnectedIsochronousStreamCentral).WillByDefault(Return(true));
    ON_CALL(controller_, SupportsBleConnectedIsochronousStreamPeripheral)
            .WillByDefault(Return(true));
    ON_CALL(controller_, SupportsBle2mPhy).WillByDefault(Return(true));
    bluetooth::hci::testing::mock_controller_ = &controller_;
    bluetooth::manager::SetMockBtmInterface(&mock_btm_interface_);
    gatt::SetMockBtaGattInterface(&mock_gatt_interface_);
    gatt::SetMockBtaGattQueue(&mock_gatt_queue_);
    bluetooth::storage::SetMockBtifStorageInterface(&mock_btif_storage_);

    iso_manager_ = bluetooth::hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();

    mock_iso_manager_ = MockIsoManager::GetInstance();
    ON_CALL(*mock_iso_manager_, RegisterCigCallbacks(_)).WillByDefault(SaveArg<0>(&cig_callbacks_));

    ON_CALL(mock_btm_interface_, IsDeviceBonded(_, _)).WillByDefault(DoAll(Return(true)));

    // Required since we call OnAudioDataReady()
    const auto codec_location = ::bluetooth::le_audio::types::CodecLocation::HOST;

    SetUpMockAudioHal();
    SetUpMockGroups();
    SetUpMockGatt();
    SetUpMockCodecManager(codec_location);

    stay_at_qos_config_in_start_stream = false;
    stay_at_releasing_stop_stream = false;

    available_snk_context_types_ = 0xffff;
    available_src_context_types_ = 0xffff;
    supported_snk_context_types_ = 0xffff;
    supported_src_context_types_ = 0xffff;

    empty_source_pack_ = false;
    empty_sink_pack_ = false;

    bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(codec_location);
    ASSERT_FALSE(LeAudioClient::IsLeAudioClientRunning());
  }

  void SetUpMockCodecManager(types::CodecLocation location) {
    codec_manager_ = bluetooth::le_audio::CodecManager::GetInstance();
    ASSERT_NE(codec_manager_, nullptr);
    std::vector<bluetooth::le_audio::btle_audio_codec_config_t> mock_offloading_preference(0);
    codec_manager_->Start(mock_offloading_preference);
    mock_codec_manager_ = MockCodecManager::GetInstance();
    ASSERT_NE((void*)mock_codec_manager_, (void*)codec_manager_);
    ASSERT_NE(mock_codec_manager_, nullptr);
    ON_CALL(*mock_codec_manager_, GetCodecLocation()).WillByDefault(Return(location));
    ON_CALL(*mock_codec_manager_, UpdateActiveUnicastAudioHalClient(_, _, _))
            .WillByDefault(Return(true));
    ON_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(_, _))
            .WillByDefault(Return(true));
    // Turn on the dual bidir SWB support
    ON_CALL(*mock_codec_manager_, IsDualBiDirSwbSupported).WillByDefault(Return(true));
    // Regardless of the codec location, return all the possible configurations
    ON_CALL(*mock_codec_manager_, GetCodecConfig)
            .WillByDefault(Invoke([](const CodecManager::UnicastConfigurationRequirements&
                                             requirements,
                                     CodecManager::UnicastConfigurationProvider provider) {
              auto filtered = *le_audio::AudioSetConfigurationProvider::Get()->GetConfigurations(
                      requirements.audio_context_type);
              // Filter out the dual bidir SWB configurations
              if (!bluetooth::le_audio::CodecManager::GetInstance()->IsDualBiDirSwbSupported()) {
                filtered.erase(std::remove_if(filtered.begin(), filtered.end(),
                                              [](auto const& el) {
                                                if (el->confs.source.empty()) {
                                                  return false;
                                                }
                                                return AudioSetConfigurationProvider::Get()
                                                        ->CheckConfigurationIsDualBiDirSwb(*el);
                                              }),
                               filtered.end());
              }
              return provider(requirements, &filtered);
            }));
  }

  void TearDown() override {
    // WARNING: Message loop cleanup should wait for all the 'till now' scheduled calls
    // so it should be called right at the very begginning of teardown.
    cleanup_message_loop_thread();

    if (is_audio_unicast_source_acquired) {
      if (unicast_source_hal_cb_ != nullptr) {
        EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop).Times(1);
      }
      EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
    }

    if (is_audio_unicast_sink_acquired) {
      if (unicast_sink_hal_cb_ != nullptr) {
        EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop).Times(1);
      }
      EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
    }

    // This is required since Stop() and Cleanup() may trigger some callbacks or
    // drop unique pointers to mocks we have raw pointer for and we want to
    // verify them all.
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

    if (LeAudioClient::IsLeAudioClientRunning()) {
      EXPECT_CALL(mock_gatt_interface_, AppDeregister(gatt_if)).Times(1);
      LeAudioClient::Cleanup();
      ASSERT_FALSE(LeAudioClient::IsLeAudioClientRunning());
    }

    owned_mock_le_audio_sink_hal_client_.reset();
    owned_mock_le_audio_source_hal_client_.reset();

    if (bluetooth::le_audio::AudioSetConfigurationProvider::Get()) {
      bluetooth::le_audio::AudioSetConfigurationProvider::Cleanup();
    }

    iso_manager_->Stop();
    bluetooth::hci::testing::mock_controller_ = nullptr;
  }

protected:
  class MockDeviceWrapper {
    class IGattHandlers {
    public:
      // IGattHandlers() = default;
      virtual ~IGattHandlers() = default;
      virtual std::pair<GattStatus, std::vector<uint8_t>> OnGetCharacteristicValue(
              uint16_t handle) = 0;
      virtual void OnWriteCharacteristic(uint16_t handle, std::vector<uint8_t> value,
                                         tGATT_WRITE_TYPE write_type, GATT_WRITE_OP_CB cb,
                                         void* cb_data) = 0;
    };

  public:
    struct csis_mock : public IGattHandlers {
      uint16_t start = 0;
      uint16_t end = 0;
      uint16_t sirk_char = 0;
      uint16_t sirk_ccc = 0;
      uint16_t size_char = 0;
      uint16_t size_ccc = 0;
      uint16_t lock_char = 0;
      uint16_t lock_ccc = 0;
      uint16_t rank_char = 0;

      int rank = 0;
      int size = 0;

      MOCK_METHOD((std::pair<GattStatus, std::vector<uint8_t>>), OnGetCharacteristicValue,
                  (uint16_t handle), (override));
      MOCK_METHOD((void), OnWriteCharacteristic,
                  (uint16_t handle, std::vector<uint8_t> value, tGATT_WRITE_TYPE write_type,
                   GATT_WRITE_OP_CB cb, void* cb_data),
                  (override));
    };

    struct cas_mock : public IGattHandlers {
      uint16_t start = 0;
      uint16_t end = 0;
      uint16_t csis_include = 0;

      MOCK_METHOD((std::pair<GattStatus, std::vector<uint8_t>>), OnGetCharacteristicValue,
                  (uint16_t handle), (override));
      MOCK_METHOD((void), OnWriteCharacteristic,
                  (uint16_t handle, std::vector<uint8_t> value, tGATT_WRITE_TYPE write_type,
                   GATT_WRITE_OP_CB cb, void* cb_data),
                  (override));
    };

    struct pacs_mock : public IGattHandlers {
      uint16_t start = 0;
      uint16_t sink_pac_char = 0;
      uint16_t sink_pac_ccc = 0;
      uint16_t sink_audio_loc_char = 0;
      uint16_t sink_audio_loc_ccc = 0;
      uint16_t source_pac_char = 0;
      uint16_t source_pac_ccc = 0;
      uint16_t source_audio_loc_char = 0;
      uint16_t source_audio_loc_ccc = 0;
      uint16_t avail_contexts_char = 0;
      uint16_t avail_contexts_ccc = 0;
      uint16_t supp_contexts_char = 0;
      uint16_t supp_contexts_ccc = 0;
      uint16_t end = 0;

      MOCK_METHOD((std::pair<GattStatus, std::vector<uint8_t>>), OnGetCharacteristicValue,
                  (uint16_t handle), (override));
      MOCK_METHOD((void), OnWriteCharacteristic,
                  (uint16_t handle, std::vector<uint8_t> value, tGATT_WRITE_TYPE write_type,
                   GATT_WRITE_OP_CB cb, void* cb_data),
                  (override));
    };

    struct ascs_mock : public IGattHandlers {
      uint16_t start = 0;
      uint16_t sink_ase_char[max_num_of_ases] = {0};
      uint16_t sink_ase_ccc[max_num_of_ases] = {0};
      uint16_t sink_ase_ccc_val[max_num_of_ases] = {0};
      uint16_t source_ase_char[max_num_of_ases] = {0};
      uint16_t source_ase_ccc[max_num_of_ases] = {0};
      uint16_t source_ase_ccc_val[max_num_of_ases] = {0};
      uint16_t ctp_char = 0;
      uint16_t ctp_ccc = 0;
      uint16_t ctp_ccc_val = 0;
      uint16_t end = 0;

      MOCK_METHOD((std::pair<GattStatus, std::vector<uint8_t>>), OnGetCharacteristicValue,
                  (uint16_t handle), (override));
      MOCK_METHOD((void), OnWriteCharacteristic,
                  (uint16_t handle, std::vector<uint8_t> value, tGATT_WRITE_TYPE write_type,
                   GATT_WRITE_OP_CB cb, void* cb_data),
                  (override));
    };

    MockDeviceWrapper(RawAddress addr, const std::list<gatt::Service>& services,
                      std::unique_ptr<NiceMock<MockDeviceWrapper::csis_mock>> csis,
                      std::unique_ptr<NiceMock<MockDeviceWrapper::cas_mock>> cas,
                      std::unique_ptr<NiceMock<MockDeviceWrapper::ascs_mock>> ascs,
                      std::unique_ptr<NiceMock<MockDeviceWrapper::pacs_mock>> pacs)
        : addr(addr) {
      this->services = services;
      this->csis = std::move(csis);
      this->cas = std::move(cas);
      this->ascs = std::move(ascs);
      this->pacs = std::move(pacs);
    }

    ~MockDeviceWrapper() {
      Mock::VerifyAndClearExpectations(csis.get());
      Mock::VerifyAndClearExpectations(cas.get());
      Mock::VerifyAndClearExpectations(ascs.get());
      Mock::VerifyAndClearExpectations(pacs.get());
    }

    RawAddress addr;
    bool connected = false;

    // A list of services and their useful params
    std::list<gatt::Service> services;
    std::unique_ptr<csis_mock> csis;
    std::unique_ptr<cas_mock> cas;
    std::unique_ptr<ascs_mock> ascs;
    std::unique_ptr<pacs_mock> pacs;
  };

  void SyncOnMainLoop() {
    // Wait for the main loop to flush
    // WARNING: Not tested with Timers pushing periodic tasks to the main loop
    while (num_async_tasks > 0) {
    }
  }

  void ConnectLeAudio(const RawAddress& address, bool isEncrypted = true,
                      bool expect_connected_event = true) {
    // by default indicate link as encrypted
    ON_CALL(mock_btm_interface_, BTM_IsEncrypted(address, _))
            .WillByDefault(DoAll(Return(isEncrypted)));

    ON_CALL(mock_btm_interface_, IsDeviceBonded(address, _)).WillByDefault(DoAll(Return(true)));

    EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, _))
            .Times(1);

    /* If connected event is not expected to arrive, don't test those two below
     */
    if (expect_connected_event) {
      EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, address, false));
      EXPECT_CALL(mock_gatt_interface_,
                  Open(gatt_if, address, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
              .Times(1);
    }

    do_in_main_thread(base::BindOnce(&LeAudioClient::Connect,
                                     base::Unretained(LeAudioClient::Get()), address));

    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_btm_interface_);
    Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  }

  void DisconnectLeAudioWithGattClose(
          const RawAddress& address, uint16_t conn_id,
          tGATT_DISCONN_REASON /*reason*/ = GATT_CONN_TERMINATE_LOCAL_HOST) {
    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnConnectionState(ConnectionState::DISCONNECTED, address))
            .Times(1);

    // For test purpose use the acl handle same as conn_id
    ON_CALL(mock_btm_interface_, GetHCIConnHandle(address, _))
            .WillByDefault([conn_id](RawAddress const& /*bd_addr*/, tBT_TRANSPORT /*transport*/) {
              return conn_id;
            });
    EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _)).Times(0);
    EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(1);

    do_in_main_thread(base::Bind(&LeAudioClient::Disconnect, base::Unretained(LeAudioClient::Get()),
                                 address));
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_btm_interface_);
    Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  }

  void DisconnectLeAudioWithAclClose(const RawAddress& address, uint16_t conn_id,
                                     tGATT_DISCONN_REASON reason = GATT_CONN_TERMINATE_LOCAL_HOST) {
    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnConnectionState(ConnectionState::DISCONNECTED, address))
            .Times(1);

    // For test purpose use the acl handle same as conn_id
    ON_CALL(mock_btm_interface_, GetHCIConnHandle(address, _))
            .WillByDefault([conn_id](RawAddress const& /*bd_addr*/, tBT_TRANSPORT /*transport*/) {
              return conn_id;
            });
    EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _))
            .WillOnce([this, &reason](uint16_t handle, tHCI_STATUS /*rs*/) {
              InjectDisconnectedEvent(handle, reason);
            });
    EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(0);

    do_in_main_thread(base::Bind(&LeAudioClient::Disconnect, base::Unretained(LeAudioClient::Get()),
                                 address));
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_btm_interface_);
    Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  }

  void DisconnectLeAudioNoDisconnectedEvtExpected(const RawAddress& address, uint16_t conn_id) {
    EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(0);
    EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _)).Times(1);
    do_in_main_thread(base::BindOnce(&LeAudioClient::Disconnect,
                                     base::Unretained(LeAudioClient::Get()), address));
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
    Mock::VerifyAndClearExpectations(&mock_btm_interface_);
  }

  void ConnectCsisDevice(const RawAddress& addr, uint16_t conn_id, uint32_t sink_audio_allocation,
                         uint32_t source_audio_allocation, uint8_t group_size, int group_id,
                         uint8_t rank, bool connect_through_csis = false, bool new_device = true) {
    SetSampleDatabaseEarbudsValid(conn_id, addr, sink_audio_allocation, source_audio_allocation,
                                  default_channel_cnt, default_channel_cnt,
                                  0x0034, /* source sample freq 16/24k/32hz */
                                  true,   /*add_csis*/
                                  true,   /*add_cas*/
                                  true,   /*add_pacs*/
                                  true,   /*add_ascs*/
                                  group_size, rank);
    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnConnectionState(ConnectionState::CONNECTED, addr))
            .Times(1);

    if (new_device) {
      EXPECT_CALL(mock_audio_hal_client_callbacks_,
                  OnGroupNodeStatus(addr, group_id, GroupNodeStatus::ADDED))
              .Times(1);
    }

    if (connect_through_csis) {
      // Add it the way CSIS would do: add to group and then connect
      do_in_main_thread(base::BindOnce(&LeAudioClient::GroupAddNode,
                                       base::Unretained(LeAudioClient::Get()), group_id, addr));
      ConnectLeAudio(addr);
    } else {
      // The usual connect
      // Since device has CSIS, lets add it here to groups already now
      groups[addr] = group_id;
      ConnectLeAudio(addr);
      InjectGroupDeviceAdded(addr, group_id);
    }
  }

  void ConnectNonCsisDevice(const RawAddress& addr, uint16_t conn_id,
                            uint32_t sink_audio_allocation, uint32_t source_audio_allocation) {
    SetSampleDatabaseEarbudsValid(conn_id, addr, sink_audio_allocation, source_audio_allocation,
                                  default_channel_cnt, default_channel_cnt, 0x0004,
                                  /* source sample freq 16khz */ false, /*add_csis*/
                                  true,                                 /*add_cas*/
                                  true,                                 /*add_pacs*/
                                  true,                                 /*add_ascs*/
                                  0, 0);
    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnConnectionState(ConnectionState::CONNECTED, addr))
            .Times(1);

    ConnectLeAudio(addr);
  }

  void UpdateLocalSourceMetadata(std::vector<struct playback_track_metadata> tracks,
                                 bool reconfigure_existing_stream = false) {
    std::vector<playback_track_metadata_v7> tracks_vec;
    tracks_vec.reserve(tracks.size());
    for (const auto& track : tracks) {
      playback_track_metadata_v7 desc_track = {
              .base =
                      {
                              .usage = static_cast<audio_usage_t>(track.usage),
                              .content_type = static_cast<audio_content_type_t>(track.content_type),
                              .gain = track.gain,
                      },
      };
      if (test_tags_ptr_) {
        memcpy(desc_track.tags, test_tags_ptr_, strlen(test_tags_ptr_));
      }

      tracks_vec.push_back(desc_track);
    }

    ASSERT_NE(nullptr, mock_le_audio_source_hal_client_);
    /* Local Source may reconfigure once the metadata is updated */
    if (reconfigure_existing_stream) {
      Expectation reconfigure =
              EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration())
                      .Times(1);
      EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(1);
      EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete())
              .Times(1)
              .After(reconfigure);
    } else {
      EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration()).Times(0);
      EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(0);
    }

    ASSERT_NE(unicast_source_hal_cb_, nullptr);
    unicast_source_hal_cb_->OnAudioMetadataUpdate(std::move(tracks_vec), DsaMode::DISABLED);
  }

  void UpdateLocalSourceMetadata(audio_usage_t usage, audio_content_type_t content_type,
                                 bool reconfigure_existing_stream = false) {
    std::vector<struct playback_track_metadata> tracks = {
            {{AUDIO_USAGE_UNKNOWN, AUDIO_CONTENT_TYPE_UNKNOWN, 0},
             {AUDIO_USAGE_UNKNOWN, AUDIO_CONTENT_TYPE_UNKNOWN, 0}}};

    tracks[0].usage = usage;
    tracks[0].content_type = content_type;
    UpdateLocalSourceMetadata(tracks, reconfigure_existing_stream);
  }

  void UpdateLocalSinkMetadata(
          std::optional<audio_source_t> audio_source,
          std::optional<audio_source_t> additional_audio_source = std::nullopt) {
    std::vector<struct record_track_metadata> tracks = {
            {{AUDIO_SOURCE_INVALID, 0.5, AUDIO_DEVICE_NONE, "00:11:22:33:44:55"}}};

    if (audio_source.has_value() && (audio_source.value() != AUDIO_SOURCE_INVALID)) {
      tracks.push_back(
              {audio_source.value(), 0.7, AUDIO_DEVICE_OUT_BLE_HEADSET, "AA:BB:CC:DD:EE:FF"});
    }
    if (additional_audio_source.has_value() &&
        (additional_audio_source.value() != AUDIO_SOURCE_INVALID)) {
      tracks.push_back({additional_audio_source.value(), 0.7, AUDIO_DEVICE_OUT_BLE_HEADSET,
                        "AA:BB:CC:DD:EE:FF"});
    }

    // Call the callback if we have added a valid track or we explicitly want to send no tracks
    if (!audio_source.has_value() || tracks.size() > 1) {
      std::vector<record_track_metadata_v7> tracks_vec;
      tracks_vec.reserve(tracks.size());
      for (const auto& track : tracks) {
        record_track_metadata_v7 desc_track = {
                .base =
                        {
                                .source = static_cast<audio_source_t>(track.source),
                                .gain = track.gain,
                                .dest_device = static_cast<audio_devices_t>(track.dest_device),
                        },
        };

        snprintf(desc_track.base.dest_device_address, AUDIO_DEVICE_MAX_ADDRESS_LEN, "%s",
                 track.dest_device_address);
        tracks_vec.push_back(desc_track);
      }

      ASSERT_NE(nullptr, unicast_sink_hal_cb_);
      unicast_sink_hal_cb_->OnAudioMetadataUpdate(std::move(tracks_vec));
    }
  }

  void LocalAudioSourceSuspend(void) {
    ASSERT_NE(unicast_source_hal_cb_, nullptr);
    unicast_source_hal_cb_->OnAudioSuspend();
    SyncOnMainLoop();
  }

  void LocalAudioSourceResume(bool expected_confirmation = true, bool expected_cancel = false) {
    ASSERT_NE(nullptr, mock_le_audio_source_hal_client_);
    if (expected_confirmation) {
      EXPECT_CALL(*mock_le_audio_source_hal_client_, ConfirmStreamingRequest()).Times(1);
    }

    if (expected_cancel) {
      EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(1);
    }

    do_in_main_thread(base::BindOnce(
            [](LeAudioSourceAudioHalClient::Callbacks* cb) {
              if (cb) {
                cb->OnAudioResume();
              }
            },
            unicast_source_hal_cb_));

    SyncOnMainLoop();
    if (expected_confirmation || expected_cancel) {
      Mock::VerifyAndClearExpectations(&*mock_le_audio_source_hal_client_);
    }
  }

  void LocalAudioSinkSuspend(void) {
    ASSERT_NE(unicast_sink_hal_cb_, nullptr);
    unicast_sink_hal_cb_->OnAudioSuspend();
    SyncOnMainLoop();
  }

  void LocalAudioSinkResume(void) {
    ASSERT_NE(unicast_sink_hal_cb_, nullptr);
    do_in_main_thread(
            base::BindOnce([](LeAudioSinkAudioHalClient::Callbacks* cb) { cb->OnAudioResume(); },
                           unicast_sink_hal_cb_));

    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  }

  void StartStreaming(audio_usage_t usage, audio_content_type_t content_type, int /*group_id*/,
                      audio_source_t audio_source = AUDIO_SOURCE_INVALID,
                      bool reconfigure_existing_stream = false,
                      bool expected_resume_confirmation = true) {
    ASSERT_NE(unicast_source_hal_cb_, nullptr);

    UpdateLocalSourceMetadata(usage, content_type, reconfigure_existing_stream);
    UpdateLocalSinkMetadata(audio_source);

    /* Stream has been automatically restarted on UpdateLocalSourceMetadata */
    if (reconfigure_existing_stream) {
      return;
    }

    LocalAudioSourceResume(expected_resume_confirmation);
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(&mock_state_machine_);

    if (usage == AUDIO_USAGE_VOICE_COMMUNICATION || audio_source != AUDIO_SOURCE_INVALID) {
      ASSERT_NE(unicast_sink_hal_cb_, nullptr);
      do_in_main_thread(
              base::BindOnce([](LeAudioSinkAudioHalClient::Callbacks* cb) { cb->OnAudioResume(); },
                             unicast_sink_hal_cb_));
    }
    SyncOnMainLoop();
  }

  void StopStreaming(int /*group_id*/, bool suspend_source = false) {
    ASSERT_NE(unicast_source_hal_cb_, nullptr);

    /* TODO We should have a way to confirm Stop() otherwise, audio framework
     * might have different state that it is in the le_audio code - as tearing
     * down CISes might take some time
     */
    /* It's enough to call only one resume even if it'll be bi-directional
     * streaming. First suspend will trigger GroupStop.
     *
     * There is no - 'only source receiver' scenario (e.g. single microphone).
     * If there will be such test oriented scenario, such resume choose logic
     * should be applied.
     */
    unicast_source_hal_cb_->OnAudioSuspend();

    if (suspend_source) {
      ASSERT_NE(unicast_sink_hal_cb_, nullptr);
      unicast_sink_hal_cb_->OnAudioSuspend();
    }
    SyncOnMainLoop();
  }

  void set_sample_database(uint16_t conn_id, RawAddress addr,
                           std::unique_ptr<NiceMock<MockDeviceWrapper::csis_mock>> csis,
                           std::unique_ptr<NiceMock<MockDeviceWrapper::cas_mock>> cas,
                           std::unique_ptr<NiceMock<MockDeviceWrapper::ascs_mock>> ascs,
                           std::unique_ptr<NiceMock<MockDeviceWrapper::pacs_mock>> pacs) {
    gatt::DatabaseBuilder bob;

    /* Generic Access Service */
    bob.AddService(0x0001, 0x0003, Uuid::From16Bit(0x1800), true);
    /* Device Name Char. */
    bob.AddCharacteristic(0x0002, 0x0003, Uuid::From16Bit(0x2a00), GATT_CHAR_PROP_BIT_READ);

    if (csis->start) {
      bool is_primary = true;
      bob.AddService(csis->start, csis->end, bluetooth::csis::kCsisServiceUuid, is_primary);
      if (csis->sirk_char) {
        bob.AddCharacteristic(csis->sirk_char, csis->sirk_char + 1, bluetooth::csis::kCsisSirkUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        if (csis->sirk_ccc) {
          bob.AddDescriptor(csis->sirk_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (csis->size_char) {
        bob.AddCharacteristic(csis->size_char, csis->size_char + 1, bluetooth::csis::kCsisSizeUuid,
                              GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
        if (csis->size_ccc) {
          bob.AddDescriptor(csis->size_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (csis->lock_char) {
        bob.AddCharacteristic(
                csis->lock_char, csis->lock_char + 1, bluetooth::csis::kCsisLockUuid,
                GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_WRITE);
        if (csis->lock_ccc) {
          bob.AddDescriptor(csis->lock_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (csis->rank_char) {
        bob.AddCharacteristic(csis->rank_char, csis->rank_char + 1, bluetooth::csis::kCsisRankUuid,
                              GATT_CHAR_PROP_BIT_READ);
      }
    }

    if (cas->start) {
      bool is_primary = true;
      bob.AddService(cas->start, cas->end, bluetooth::le_audio::uuid::kCapServiceUuid, is_primary);
      // Include CSIS service inside
      if (cas->csis_include) {
        bob.AddIncludedService(cas->csis_include, bluetooth::csis::kCsisServiceUuid, csis->start,
                               csis->end);
      }
    }

    if (pacs->start) {
      bool is_primary = true;
      bob.AddService(pacs->start, pacs->end,
                     bluetooth::le_audio::uuid::kPublishedAudioCapabilityServiceUuid, is_primary);

      if (pacs->sink_pac_char) {
        bob.AddCharacteristic(
                pacs->sink_pac_char, pacs->sink_pac_char + 1,
                bluetooth::le_audio::uuid::kSinkPublishedAudioCapabilityCharacteristicUuid,
                GATT_CHAR_PROP_BIT_READ);
        if (pacs->sink_pac_ccc) {
          bob.AddDescriptor(pacs->sink_pac_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (pacs->sink_audio_loc_char) {
        bob.AddCharacteristic(pacs->sink_audio_loc_char, pacs->sink_audio_loc_char + 1,
                              bluetooth::le_audio::uuid::kSinkAudioLocationCharacteristicUuid,
                              GATT_CHAR_PROP_BIT_READ);
        if (pacs->sink_audio_loc_ccc) {
          bob.AddDescriptor(pacs->sink_audio_loc_ccc,
                            Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (pacs->source_pac_char) {
        bob.AddCharacteristic(
                pacs->source_pac_char, pacs->source_pac_char + 1,
                bluetooth::le_audio::uuid::kSourcePublishedAudioCapabilityCharacteristicUuid,
                GATT_CHAR_PROP_BIT_READ);
        if (pacs->source_pac_ccc) {
          bob.AddDescriptor(pacs->source_pac_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (pacs->source_audio_loc_char) {
        bob.AddCharacteristic(pacs->source_audio_loc_char, pacs->source_audio_loc_char + 1,
                              bluetooth::le_audio::uuid::kSourceAudioLocationCharacteristicUuid,
                              GATT_CHAR_PROP_BIT_READ);
        if (pacs->source_audio_loc_ccc) {
          bob.AddDescriptor(pacs->source_audio_loc_ccc,
                            Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (pacs->avail_contexts_char) {
        bob.AddCharacteristic(
                pacs->avail_contexts_char, pacs->avail_contexts_char + 1,
                bluetooth::le_audio::uuid::kAudioContextAvailabilityCharacteristicUuid,
                GATT_CHAR_PROP_BIT_READ);
        if (pacs->avail_contexts_ccc) {
          bob.AddDescriptor(pacs->avail_contexts_ccc,
                            Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }

      if (pacs->supp_contexts_char) {
        bob.AddCharacteristic(pacs->supp_contexts_char, pacs->supp_contexts_char + 1,
                              bluetooth::le_audio::uuid::kAudioSupportedContextCharacteristicUuid,
                              GATT_CHAR_PROP_BIT_READ);
        if (pacs->supp_contexts_ccc) {
          bob.AddDescriptor(pacs->supp_contexts_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }
    }

    if (ascs->start) {
      bool is_primary = true;
      bob.AddService(ascs->start, ascs->end,
                     bluetooth::le_audio::uuid::kAudioStreamControlServiceUuid, is_primary);
      for (int i = 0; i < max_num_of_ases; i++) {
        if (ascs->sink_ase_char[i]) {
          bob.AddCharacteristic(ascs->sink_ase_char[i], ascs->sink_ase_char[i] + 1,
                                bluetooth::le_audio::uuid::kSinkAudioStreamEndpointUuid,
                                GATT_CHAR_PROP_BIT_READ);
          if (ascs->sink_ase_ccc[i]) {
            bob.AddDescriptor(ascs->sink_ase_ccc[i], Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
          }
        }
        if (ascs->source_ase_char[i]) {
          bob.AddCharacteristic(ascs->source_ase_char[i], ascs->source_ase_char[i] + 1,
                                bluetooth::le_audio::uuid::kSourceAudioStreamEndpointUuid,
                                GATT_CHAR_PROP_BIT_READ);
          if (ascs->source_ase_ccc[i]) {
            bob.AddDescriptor(ascs->source_ase_ccc[i],
                              Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
          }
        }
      }
      if (ascs->ctp_char) {
        bob.AddCharacteristic(
                ascs->ctp_char, ascs->ctp_char + 1,
                bluetooth::le_audio::uuid::kAudioStreamEndpointControlPointCharacteristicUuid,
                GATT_CHAR_PROP_BIT_READ);
        if (ascs->ctp_ccc) {
          bob.AddDescriptor(ascs->ctp_ccc, Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG));
        }
      }
    }

    // Assign conn_id to a certain device - this does not mean it is connected
    auto dev_wrapper = std::make_unique<NiceMock<MockDeviceWrapper>>(
            addr, bob.Build().Services(), std::move(csis), std::move(cas), std::move(ascs),
            std::move(pacs));
    peer_devices.emplace(conn_id, std::move(dev_wrapper));
  }

  void SetSampleDatabaseEmpty(uint16_t conn_id, RawAddress addr) {
    auto csis = std::make_unique<NiceMock<MockDeviceWrapper::csis_mock>>();
    auto cas = std::make_unique<NiceMock<MockDeviceWrapper::cas_mock>>();
    auto pacs = std::make_unique<NiceMock<MockDeviceWrapper::pacs_mock>>();
    auto ascs = std::make_unique<NiceMock<MockDeviceWrapper::ascs_mock>>();
    set_sample_database(conn_id, addr, std::move(csis), std::move(cas), std::move(ascs),
                        std::move(pacs));
  }

  struct SampleDatabaseParameters {
    uint16_t conn_id;
    RawAddress addr;

    std::optional<uint32_t> sink_audio_allocation = std::nullopt;
    std::optional<uint32_t> source_audio_allocation = std::nullopt;
    uint8_t sink_channel_cnt = 0x03;
    uint8_t source_channel_cnt = 0x03;
    uint16_t sample_freq_mask = 0x0004;
    bool add_csis = true;
    bool add_cas = true;
    bool add_pacs = true;
    int add_ascs_cnt = 1;
    uint8_t set_size = 2;
    uint8_t rank = 1;
    GattStatus gatt_status = GATT_SUCCESS;
    uint8_t max_supported_codec_frames_per_sdu = 1;
  };

  void SetSampleDatabaseEarbudsValid(
          uint16_t conn_id, RawAddress addr, uint32_t sink_audio_allocation,
          uint32_t source_audio_allocation, uint8_t sink_channel_cnt = 0x03,
          uint8_t source_channel_cnt = 0x03, uint16_t sample_freq_mask = 0x0004,
          bool add_csis = true, bool add_cas = true, bool add_pacs = true, int add_ascs_cnt = 1,
          uint8_t set_size = 2, uint8_t rank = 1, GattStatus gatt_status = GATT_SUCCESS) {
    SetSampleDatabaseEarbudsValid(SampleDatabaseParameters{
            .conn_id = conn_id,
            .addr = addr,
            .sink_audio_allocation = sink_audio_allocation,
            .source_audio_allocation = source_audio_allocation,
            .sink_channel_cnt = sink_channel_cnt,
            .source_channel_cnt = source_channel_cnt,
            .sample_freq_mask = sample_freq_mask,
            .add_csis = add_csis,
            .add_cas = add_cas,
            .add_pacs = add_pacs,
            .add_ascs_cnt = add_ascs_cnt,
            .set_size = set_size,
            .rank = rank,
            .gatt_status = gatt_status,
            .max_supported_codec_frames_per_sdu = 1,
    });
  }

  void SetSampleDatabaseEarbudsValid(const SampleDatabaseParameters& params) {
    auto conn_id = params.conn_id;
    auto addr = params.addr;
    auto sink_audio_allocation = params.sink_audio_allocation;
    auto source_audio_allocation = params.source_audio_allocation;
    auto sink_channel_cnt = params.sink_channel_cnt;
    auto source_channel_cnt = params.source_channel_cnt;
    auto sample_freq_mask = params.sample_freq_mask;
    auto add_csis = params.add_csis;
    auto add_cas = params.add_cas;
    auto add_pacs = params.add_pacs;
    auto add_ascs_cnt = params.add_ascs_cnt;
    auto set_size = params.set_size;
    auto rank = params.rank;
    auto gatt_status = params.gatt_status;
    auto max_supported_codec_frames_per_sdu = params.max_supported_codec_frames_per_sdu;

    auto csis = std::make_unique<NiceMock<MockDeviceWrapper::csis_mock>>();
    if (add_csis) {
      // attribute handles
      csis->start = 0x0010;
      csis->sirk_char = 0x0020;
      csis->sirk_ccc = 0x0022;
      csis->size_char = 0x0023;
      csis->size_ccc = 0x0025;
      csis->lock_char = 0x0026;
      csis->lock_ccc = 0x0028;
      csis->rank_char = 0x0029;
      csis->end = 0x0030;
      // other params
      csis->size = set_size;
      csis->rank = rank;
    }

    auto cas = std::make_unique<NiceMock<MockDeviceWrapper::cas_mock>>();
    if (add_cas) {
      // attribute handles
      cas->start = 0x0040;
      if (add_csis) {
        cas->csis_include = 0x0041;
      }
      cas->end = 0x0050;
      // other params
    }

    auto pacs = std::make_unique<NiceMock<MockDeviceWrapper::pacs_mock>>();
    if (add_pacs) {
      // attribute handles
      pacs->start = 0x0060;
      if (sink_audio_allocation.has_value()) {
        pacs->sink_pac_char = 0x0061;
        pacs->sink_pac_ccc = 0x0063;
        pacs->sink_audio_loc_char = 0x0064;
        pacs->sink_audio_loc_ccc = 0x0066;
      }
      if (source_audio_allocation.has_value()) {
        pacs->source_pac_char = 0x0067;
        pacs->source_pac_ccc = 0x0069;
        pacs->source_audio_loc_char = 0x0070;
        pacs->source_audio_loc_ccc = 0x0072;
      }
      pacs->avail_contexts_char = 0x0073;
      pacs->avail_contexts_ccc = 0x0075;
      pacs->supp_contexts_char = 0x0076;
      pacs->supp_contexts_ccc = 0x0078;
      pacs->end = 0x0080;
      // other params
    }

    auto ascs = std::make_unique<NiceMock<MockDeviceWrapper::ascs_mock>>();
    if (add_ascs_cnt > 0) {
      // attribute handles
      ascs->start = 0x0090;
      uint16_t handle = 0x0091;
      for (int i = 0; i < add_ascs_cnt; i++) {
        if (sink_audio_allocation.has_value()) {
          ascs->sink_ase_char[i] = handle;
          handle += 2;
          ascs->sink_ase_ccc[i] = handle;
          handle++;
        }

        if (source_audio_allocation.has_value()) {
          ascs->source_ase_char[i] = handle;
          handle += 2;
          ascs->source_ase_ccc[i] = handle;
          handle++;
        }
      }
      ascs->ctp_char = handle;
      handle += 2;
      ascs->ctp_ccc = handle;
      handle++;
      ascs->end = handle;
      // other params
    }

    set_sample_database(conn_id, addr, std::move(csis), std::move(cas), std::move(ascs),
                        std::move(pacs));

    if (add_pacs) {
      uint8_t sample_freq[2];
      sample_freq[0] = (uint8_t)(sample_freq_mask);
      sample_freq[1] = (uint8_t)(sample_freq_mask >> 8);

      // Set pacs default read values
      ON_CALL(*peer_devices.at(conn_id)->pacs, OnGetCharacteristicValue(_))
              .WillByDefault([=, this](uint16_t handle) {
                auto& pacs = peer_devices.at(conn_id)->pacs;
                std::vector<uint8_t> value;
                if (gatt_status == GATT_SUCCESS) {
                  if (handle == pacs->sink_pac_char + 1) {
                    if (empty_sink_pack_) {
                      value = {0x00};
                    } else {
                      value = {
                              // Num records
                              0x02,
                              // Codec_ID
                              0x06,
                              0x00,
                              0x00,
                              0x00,
                              0x00,
                              // Codec Spec. Caps. Len
                              0x10,
                              0x03, /* sample freq */
                              0x01,
                              sample_freq[0],
                              sample_freq[1],
                              0x02,
                              0x02, /* frame duration */
                              0x03,
                              0x02, /* channel count */
                              0x03,
                              sink_channel_cnt,
                              0x05,
                              0x04,
                              0x1E,
                              0x00,
                              0x78,
                              0x00,
                              // Metadata Length
                              0x00,
                              // Codec_ID
                              0x06,
                              0x00,
                              0x00,
                              0x00,
                              0x00,
                              // Codec Spec. Caps. Len
                              0x13,
                              0x03, /* sample freq */
                              0x01,
                              0x80, /* 48kHz */
                              0x00,
                              0x02, /* frame duration */
                              0x02,
                              0x03,
                              0x02, /* channel count */
                              0x03,
                              sink_channel_cnt,
                              0x05, /* octects per frame */
                              0x04,
                              0x78,
                              0x00,
                              0x78,
                              0x00,
                              0x02, /* Max supported codec frames per SDU */
                              0x05,
                              max_supported_codec_frames_per_sdu,
                              // Metadata Length
                              0x00,
                      };
                    }
                  } else if (handle == pacs->sink_audio_loc_char + 1) {
                    value = {
                            (uint8_t)(sink_audio_allocation.value_or(0)),
                            (uint8_t)(sink_audio_allocation.value_or(0) >> 8),
                            (uint8_t)(sink_audio_allocation.value_or(0) >> 16),
                            (uint8_t)(sink_audio_allocation.value_or(0) >> 24),
                    };
                  } else if (handle == pacs->source_pac_char + 1) {
                    if (empty_source_pack_) {
                      value = {0x00};
                    } else {
                      value = {
                              // Num records
                              0x02,
                              // Codec_ID
                              0x06,
                              0x00,
                              0x00,
                              0x00,
                              0x00,
                              // Codec Spec. Caps. Len
                              0x10,
                              0x03,
                              0x01,
                              sample_freq[0],
                              sample_freq[1],
                              0x02,
                              0x02,
                              0x03,
                              0x02,
                              0x03,
                              source_channel_cnt,
                              0x05,
                              0x04,
                              0x1E,
                              0x00,
                              0x78,
                              0x00,
                              // Metadata Length
                              0x00,
                              // Codec_ID
                              0x06,
                              0x00,
                              0x00,
                              0x00,
                              0x00,
                              // Codec Spec. Caps. Len
                              0x10,
                              0x03,
                              0x01,
                              0x24,
                              0x00,
                              0x02,
                              0x02,
                              0x03,
                              0x02,
                              0x03,
                              source_channel_cnt,
                              0x05,
                              0x04,
                              0x1E,
                              0x00,
                              0x50,
                              0x00,
                              // Metadata Length
                              0x00,
                      };
                    }
                  } else if (handle == pacs->source_audio_loc_char + 1) {
                    value = {
                            (uint8_t)(source_audio_allocation.value_or(0)),
                            (uint8_t)(source_audio_allocation.value_or(0) >> 8),
                            (uint8_t)(source_audio_allocation.value_or(0) >> 16),
                            (uint8_t)(source_audio_allocation.value_or(0) >> 24),
                    };
                  } else if (handle == pacs->avail_contexts_char + 1) {
                    value = {
                            // Sink Avail Contexts
                            (uint8_t)(available_snk_context_types_),
                            (uint8_t)(available_snk_context_types_ >> 8),
                            // Source Avail Contexts
                            (uint8_t)(available_src_context_types_),
                            (uint8_t)(available_src_context_types_ >> 8),
                    };
                  } else if (handle == pacs->supp_contexts_char + 1) {
                    value = {
                            // Sink Supp Contexts
                            (uint8_t)(supported_snk_context_types_),
                            (uint8_t)(supported_snk_context_types_ >> 8),
                            // Source Supp Contexts
                            (uint8_t)(supported_src_context_types_),
                            (uint8_t)(supported_src_context_types_ >> 8),
                    };
                  }
                }
                return std::make_pair(gatt_status, value);
              });
    }

    if (add_ascs_cnt > 0) {
      // Set ascs default read values
      ON_CALL(*peer_devices.at(conn_id)->ascs, OnGetCharacteristicValue(_))
              .WillByDefault([this, conn_id, gatt_status](uint16_t handle) {
                auto& ascs = peer_devices.at(conn_id)->ascs;
                std::vector<uint8_t> value;
                bool is_ase_sink_request = false;
                bool is_ase_src_request = false;
                uint8_t idx;

                if (handle == ascs->ctp_ccc && ccc_stored_byte_val_.has_value()) {
                  value = {*ccc_stored_byte_val_, 00};
                  return std::make_pair(gatt_read_ctp_ccc_status_, value);
                }

                if (gatt_status == GATT_SUCCESS) {
                  if (handle == ascs->ctp_ccc) {
                    value = UINT16_TO_VEC_UINT8(ascs->ctp_ccc_val);
                  } else {
                    for (idx = 0; idx < max_num_of_ases; idx++) {
                      if (handle == ascs->sink_ase_ccc[idx] + 1) {
                        value = UINT16_TO_VEC_UINT8(ascs->sink_ase_ccc_val[idx]);
                        break;
                      }
                      if (handle == ascs->source_ase_char[idx] + 1) {
                        value = UINT16_TO_VEC_UINT8(ascs->source_ase_ccc_val[idx]);
                        break;
                      }
                    }
                  }

                  for (idx = 0; idx < max_num_of_ases; idx++) {
                    if (handle == ascs->sink_ase_char[idx] + 1) {
                      is_ase_sink_request = true;
                      break;
                    }
                    if (handle == ascs->source_ase_char[idx] + 1) {
                      is_ase_src_request = true;
                      break;
                    }
                  }

                  if (is_ase_sink_request) {
                    value = {
                            // ASE ID
                            static_cast<uint8_t>(idx + 1),
                            // State
                            static_cast<uint8_t>(bluetooth::le_audio::types::AseState::
                                                         BTA_LE_AUDIO_ASE_STATE_IDLE),
                            // No Additional ASE params for IDLE state
                    };
                  } else if (is_ase_src_request) {
                    value = {
                            // ASE ID
                            static_cast<uint8_t>(idx + 6),
                            // State
                            static_cast<uint8_t>(bluetooth::le_audio::types::AseState::
                                                         BTA_LE_AUDIO_ASE_STATE_IDLE),
                            // No Additional ASE params for IDLE state
                    };
                  }
                }
                return std::make_pair(gatt_status, value);
              });
    }
  }

  void TestAudioDataTransfer(int group_id, uint8_t cis_count_out, uint8_t cis_count_in,
                             int data_len, int in_data_len = 40, uint16_t decoded_in_data_len = 0) {
    ASSERT_NE(unicast_source_hal_cb_, nullptr);
    ASSERT_NE(mock_le_audio_sink_hal_client_, nullptr);

    // Expect two channels ISO Data to be sent
    std::vector<uint16_t> handles;
    if (cis_count_out) {
      EXPECT_CALL(*mock_iso_manager_, SendIsoData(_, _, _))
              .Times(cis_count_out)
              .WillRepeatedly([&handles](uint16_t iso_handle, const uint8_t* /*data*/,
                                         uint16_t /*data_len*/) { handles.push_back(iso_handle); });
    }
    std::vector<uint8_t> data(data_len);
    unicast_source_hal_cb_->OnAudioDataReady(data);

    // Inject microphone data from group (2 CISes - pass stereo data in 1 call)
    if (decoded_in_data_len) {
      EXPECT_CALL(*mock_le_audio_sink_hal_client_, SendData(_, decoded_in_data_len))
              .Times(cis_count_in > 0 ? 1 : 0);
    } else {
      EXPECT_CALL(*mock_le_audio_sink_hal_client_, SendData(_, _)).Times(cis_count_in > 0 ? 1 : 0);
    }
    ASSERT_EQ(streaming_groups.count(group_id), 1u);

    if (cis_count_in) {
      ASSERT_NE(unicast_sink_hal_cb_, nullptr);

      ASSERT_NE(0lu, streaming_groups.count(group_id));
      auto group = streaming_groups.at(group_id);
      for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
           device = group->GetNextDevice(device)) {
        for (auto& ase : device->ases_) {
          if (ase.direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
            InjectIncomingIsoData(group_id, ase.cis_conn_hdl, in_data_len);
            --cis_count_in;
            if (!cis_count_in) {
              break;
            }
          }
        }
        if (!cis_count_in) {
          break;
        }
      }
    }

    SyncOnMainLoop();
    std::sort(handles.begin(), handles.end());
    ASSERT_EQ(cis_count_in, 0);
    handles.clear();

    Mock::VerifyAndClearExpectations(mock_iso_manager_);
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  }

  void InjectIncomingIsoData(uint16_t cig_id, uint16_t cis_con_hdl, size_t payload_size) {
    BT_HDR* bt_hdr = (BT_HDR*)malloc(sizeof(BT_HDR) + payload_size);

    bt_hdr->offset = 0;
    bt_hdr->len = payload_size;

    bluetooth::hci::iso_manager::cis_data_evt cis_evt;
    cis_evt.cig_id = cig_id;
    cis_evt.cis_conn_hdl = cis_con_hdl;
    cis_evt.ts = 0;
    cis_evt.evt_lost = 0;
    cis_evt.p_msg = bt_hdr;

    ASSERT_NE(cig_callbacks_, nullptr);
    cig_callbacks_->OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDataAvailable, &cis_evt);
    free(bt_hdr);
  }

  void InjectCisDisconnected(uint16_t cig_id, uint16_t cis_con_hdl, uint8_t reason = 0) {
    bluetooth::hci::iso_manager::cis_disconnected_evt cis_evt;
    cis_evt.cig_id = cig_id;
    cis_evt.cis_conn_hdl = cis_con_hdl;
    cis_evt.reason = reason;

    ASSERT_NE(cig_callbacks_, nullptr);
    cig_callbacks_->OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCisDisconnected, &cis_evt);
  }

  void InjectCigRemoved(uint8_t cig_id) {
    bluetooth::hci::iso_manager::cig_remove_cmpl_evt evt;
    evt.status = 0;
    evt.cig_id = cig_id;

    ASSERT_NE(cig_callbacks_, nullptr);
    cig_callbacks_->OnCisEvent(bluetooth::hci::iso_manager::kIsoEventCigOnRemoveCmpl, &evt);
  }

  NiceMock<MockAudioHalClientCallbacks> mock_audio_hal_client_callbacks_;
  LeAudioSourceAudioHalClient::Callbacks* unicast_source_hal_cb_ = nullptr;
  LeAudioSinkAudioHalClient::Callbacks* unicast_sink_hal_cb_ = nullptr;

  uint8_t default_channel_cnt = 0x03;
  uint8_t default_src_channel_cnt = default_channel_cnt;
  uint8_t default_ase_cnt = 1;

  NiceMock<MockCsisClient> mock_csis_client_module_;
  NiceMock<MockDeviceGroups> mock_groups_module_;
  bluetooth::groups::DeviceGroupsCallbacks* group_callbacks_;
  NiceMock<MockLeAudioGroupStateMachine> mock_state_machine_;

  NiceMock<MockFunction<void()>> mock_storage_load;
  NiceMock<MockFunction<bool()>> mock_hal_2_1_verifier;

  NiceMock<bluetooth::manager::MockBtmInterface> mock_btm_interface_;
  NiceMock<gatt::MockBtaGattInterface> mock_gatt_interface_;
  NiceMock<gatt::MockBtaGattQueue> mock_gatt_queue_;
  tBTA_GATTC_CBACK* gatt_callback;
  const uint8_t gatt_if = 0xfe;
  uint16_t global_conn_id = 1;
  bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks_;
  std::map<int, LeAudioDeviceGroup*> streaming_groups;
  bool stay_at_qos_config_in_start_stream = false;
  bool stay_at_releasing_stop_stream = false;

  bool attach_to_stream_scheduled = false;

  bluetooth::hci::IsoManager* iso_manager_;
  MockIsoManager* mock_iso_manager_;
  bluetooth::hci::iso_manager::CigCallbacks* cig_callbacks_ = nullptr;
  uint16_t iso_con_counter_ = 1;

  bluetooth::le_audio::CodecManager* codec_manager_;
  MockCodecManager* mock_codec_manager_;

  uint16_t available_snk_context_types_ = 0xffff;
  uint16_t available_src_context_types_ = 0xffff;
  uint16_t supported_snk_context_types_ = 0xffff;
  uint16_t supported_src_context_types_ = 0xffff;

  bool empty_source_pack_;
  bool empty_sink_pack_;

  NiceMock<bluetooth::storage::MockBtifStorageInterface> mock_btif_storage_;

  std::map<uint16_t, std::unique_ptr<NiceMock<MockDeviceWrapper>>> peer_devices;
  std::list<int> group_locks;
  std::map<RawAddress, int> groups;

  /* CCC descriptor data */
  tGATT_STATUS gatt_read_ctp_ccc_status_ = GATT_SUCCESS;
  std::optional<uint8_t> ccc_stored_byte_val_ = std::nullopt;

  /* Audio track metadata */
  char* test_tags_ptr_ = nullptr;
  NiceMock<bluetooth::hci::testing::MockControllerInterface> controller_;
};

class UnicastTest : public UnicastTestNoInit {
protected:
  void SetUp() override {
    UnicastTestNoInit::SetUp();

    EXPECT_CALL(mock_hal_2_1_verifier, Call()).Times(1);
    EXPECT_CALL(mock_storage_load, Call()).Times(1);

    ON_CALL(mock_btm_interface_, GetHCIConnHandle(_, _))
            .WillByDefault(
                    [this](RawAddress const& bd_addr, tBT_TRANSPORT /*transport*/) -> uint16_t {
                      for (auto const& [conn_id, dev_wrapper] : peer_devices) {
                        if (dev_wrapper->addr == bd_addr) {
                          return conn_id;
                        }
                      }
                      log::error("GetHCIConnHandle Mock: not a valid test device!");
                      return 0x00FE;
                    });
    ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _))
            .WillByDefault([this](uint16_t handle, tHCI_STATUS /*rs*/) {
              ASSERT_NE(handle, GATT_INVALID_CONN_ID);
              InjectDisconnectedEvent(handle, GATT_CONN_TERMINATE_LOCAL_HOST);
            });

    std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;
    BtaAppRegisterCallback app_register_callback;
    EXPECT_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
            .WillOnce(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
    LeAudioClient::Initialize(
            &mock_audio_hal_client_callbacks_,
            base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
            base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); },
                       &mock_hal_2_1_verifier),
            framework_encode_preference);

    SyncOnMainLoop();
    ASSERT_TRUE(gatt_callback);
    ASSERT_TRUE(group_callbacks_);
    ASSERT_TRUE(app_register_callback);
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
    Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  }

  void TearDown() override {
    MockCodecInterface::ClearMockInstanceHookList();

    // Clear the default actions before the parent class teardown is called
    Mock::VerifyAndClear(&mock_btm_interface_);
    Mock::VerifyAndClear(&mock_gatt_interface_);
    Mock::VerifyAndClear(&mock_audio_hal_client_callbacks_);
    groups.clear();
    UnicastTestNoInit::TearDown();
  }

  void TestSetupRemoteDevices(int group_id) {
    uint8_t group_size = 2;

    // Report working CSIS
    ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

    // First earbud
    const RawAddress test_address0 = GetTestAddress(0);
    EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
    ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                      codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id,
                      1 /* rank*/);

    // Second earbud
    const RawAddress test_address1 = GetTestAddress(1);
    EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
    ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                      codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id,
                      2 /* rank*/, true /*connect_through_csis*/);

    constexpr int gmcs_ccid = 1;
    constexpr int gtbs_ccid = 2;
    EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
    LeAudioClient::Get()->SetCcidInformation(gmcs_ccid,
                                             static_cast<int>(LeAudioContextType::MEDIA));
    LeAudioClient::Get()->SetCcidInformation(gtbs_ccid,
                                             static_cast<int>(LeAudioContextType::CONVERSATIONAL));
    LeAudioClient::Get()->GroupSetActive(group_id);
  }

  void TestSetCodecPreference(
          const btle_audio_codec_config_t* preferred_codec_config_before_streaming,
          const btle_audio_codec_config_t* preferred_codec_config_during_streaming,
          LeAudioContextType context_type, int group_id, bool set_before_streaming,
          bool set_while_streaming, bool is_using_set_before_streaming_codec_during_streaming,
          bool is_using_set_while_streaming_codec_during_streaming, bool is_reconfig) {
    auto config_before_streaming_str = preferred_codec_config_before_streaming
                                               ? preferred_codec_config_before_streaming->ToString()
                                               : "null";
    auto config_during_streaming_str = preferred_codec_config_during_streaming
                                               ? preferred_codec_config_during_streaming->ToString()
                                               : "null";
    log::debug(
            "preferred_codec_config_before_streaming: {}, "
            "preferred_codec_config_during_streaming: {}, context_type: {}, "
            "group_id: {}, set_before_streaming: {}, "
            "set_while_streaming: {}, "
            "is_using_set_before_streaming_codec_during_streaming: "
            "{},is_using_set_while_streaming_codec_during_streaming:{}, "
            "is_reconfig: {}",
            config_before_streaming_str, config_during_streaming_str,
            bluetooth::common::ToString(context_type), group_id, set_before_streaming,
            set_while_streaming, is_using_set_before_streaming_codec_during_streaming,
            is_using_set_while_streaming_codec_during_streaming, is_reconfig);

    if (context_type != LeAudioContextType::MEDIA &&
        context_type != LeAudioContextType::CONVERSATIONAL) {
      return;
    }

    if (set_before_streaming) {
      do_in_main_thread(base::BindOnce(&LeAudioClient::SetCodecConfigPreference,
                                       base::Unretained(LeAudioClient::Get()), group_id,
                                       *preferred_codec_config_before_streaming,
                                       *preferred_codec_config_before_streaming));
      SyncOnMainLoop();
    }

    types::BidirectionalPair<std::vector<uint8_t>> ccids;
    constexpr int gmcs_ccid = 1;
    constexpr int gtbs_ccid = 2;
    if (context_type == LeAudioContextType::MEDIA) {
      ccids = types::BidirectionalPair<std::vector<uint8_t>>{{gmcs_ccid}, {}};
      EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
      StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
    } else {
      ccids = types::BidirectionalPair<std::vector<uint8_t>>{{gtbs_ccid}, {gtbs_ccid}};
      EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
      StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
    }
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

    ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(group_id,
                                                                static_cast<int>(context_type)),
              is_using_set_before_streaming_codec_during_streaming);

    uint8_t cis_count_out = 2;
    uint8_t cis_count_in = context_type == LeAudioContextType::MEDIA ? 0 : 2;
    TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

    if (set_while_streaming) {
      EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(is_reconfig);
      EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(is_reconfig);

      do_in_main_thread(base::BindOnce(&LeAudioClient::SetCodecConfigPreference,
                                       base::Unretained(LeAudioClient::Get()), group_id,
                                       *preferred_codec_config_during_streaming,
                                       *preferred_codec_config_during_streaming));
      SyncOnMainLoop();
      ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(group_id,
                                                                  static_cast<int>(context_type)),
                is_using_set_while_streaming_codec_during_streaming);

      if (context_type == LeAudioContextType::MEDIA) {
        ccids = types::BidirectionalPair<std::vector<uint8_t>>{{gmcs_ccid}, {}};
        EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
        StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
      } else {
        ccids = types::BidirectionalPair<std::vector<uint8_t>>{{gtbs_ccid}, {gtbs_ccid}};
        EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
        StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
      }
    }

    StopStreaming(group_id, context_type == LeAudioContextType::CONVERSATIONAL);
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    Mock::VerifyAndClearExpectations(&mock_state_machine_);
  }
};

class UnicastTestHealthStatus : public UnicastTest {
protected:
  void SetUp() override {
    UnicastTest::SetUp();
    group_ = new LeAudioDeviceGroup(group_id_);
  }

  void TearDown() override {
    delete group_;
    UnicastTest::TearDown();
  }

  const int group_id_ = 0;
  LeAudioDeviceGroup* group_ = nullptr;
};

class UnicastTestHandoverMode : public UnicastTest {
protected:
  void SetUp() override {
    use_handover_mode = true;
    UnicastTest::SetUp();
    group_ = new LeAudioDeviceGroup(group_id_);
  }

  void TearDown() override {
    delete group_;
    UnicastTest::TearDown();
  }

  const int group_id_ = 0;
  LeAudioDeviceGroup* group_ = nullptr;
};

TEST_F(UnicastTest, Initialize) {
  ASSERT_NE(LeAudioClient::Get(), nullptr);
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
}

TEST_F(UnicastTestNoInit, InitializeNoHal_2_1) {
  ASSERT_FALSE(LeAudioClient::IsLeAudioClientRunning());

  // Report False when asked for Audio HAL 2.1 support
  ON_CALL(mock_hal_2_1_verifier, Call()).WillByDefault([]() -> bool { return false; });

  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;

  EXPECT_DEATH(
          LeAudioClient::Initialize(
                  &mock_audio_hal_client_callbacks_,
                  base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
                  base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); },
                             &mock_hal_2_1_verifier),
                  framework_encode_preference),
          "LE Audio Client requires Bluetooth Audio HAL V2.1 at least. Either "
          "disable LE Audio Profile, or update your HAL");
}

TEST_F(UnicastTest, CleanupWhenUserConnecting) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_src_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  /* Remove default action on the direct connect */
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _)).WillByDefault(Return());
  ConnectLeAudio(test_address0, false, false);

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(1);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _)).Times(0);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(0);

  LeAudioClient::Cleanup();
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, CleanupWhenAutoConnecting) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  log::info("Connect device");
  ConnectLeAudio(test_address0);

  /* Remove default action on the autoconnect */
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .WillByDefault(Return());

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  /* Make sure when remote device disconnects us, TA is used */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  log::info("Device is in auto connect");

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(0);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _)).Times(0);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(0);

  LeAudioClient::Cleanup();
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, CleanupWhenConnected) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  log::info("Connect device");
  ConnectLeAudio(test_address0);

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(0);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(conn_id, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(1);

  LeAudioClient::Cleanup();
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, ConnectAndSetupPhy) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  EXPECT_CALL(mock_btm_interface_, BleSetPhy(test_address0, PHY_LE_2M, PHY_LE_2M, 0)).Times(1);
  ConnectLeAudio(test_address0, false);
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);

  EXPECT_CALL(mock_btm_interface_, BleSetPhy(test_address0, PHY_LE_2M, PHY_LE_2M, 0)).Times(1);
  InjectPhyChangedEvent(conn_id, 0, 0, GATT_REQ_NOT_SUPPORTED);
  SyncOnMainLoop();
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));
  InjectEncryptionChangedEvent(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);

  /* Make sure flag `acl_phy_update_done_` is cleared after disconnect.
   * Just repeat previous steps after reconnection
   */
  InjectDisconnectedEvent(conn_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_btm_interface_, BleSetPhy(test_address0, PHY_LE_2M, PHY_LE_2M, 0)).Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(false)));
  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);

  EXPECT_CALL(mock_btm_interface_, BleSetPhy(test_address0, PHY_LE_2M, PHY_LE_2M, 0)).Times(1);
  InjectPhyChangedEvent(conn_id, 0, 0, GATT_REQ_NOT_SUPPORTED);
  SyncOnMainLoop();
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));
  InjectEncryptionChangedEvent(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
}

TEST_F(UnicastTest, ConnectOneEarbudEmpty) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEmpty(1, test_address0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
}

TEST_F(UnicastTest, ConnectOneEarbudNoPacs) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                false,                               /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
}

TEST_F(UnicastTest, ConnectOneEarbudNoAscs) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                0 /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
}

TEST_F(UnicastTest, ConnectOneEarbudNoCas) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                false,                               /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
}

TEST_F(UnicastTest, ConnectOneEarbudNoCsis) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ false, /*add_csis*/
                                true,                                 /*add_cas*/
                                true,                                 /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
}

TEST_F(UnicastTest, ConnectOneEarbudWithInvalidCsis) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  /* Make sure Group has not knowledge about the device */
  ON_CALL(mock_groups_module_, GetGroupId(_, _))
          .WillByDefault([](const RawAddress& /*addr*/, bluetooth::Uuid /*uuid*/) {
            return bluetooth::groups::kGroupUnknown;
          });

  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudEmpty_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEmpty(1, test_address0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudNoPacs_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                false,                               /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudNoAscs_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                0 /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);
  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudNoCas_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                false,                               /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudNoCsis_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ false, /*add_csis*/
                                true,                                 /*add_cas*/
                                true,                                 /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudWithInvalidCsis_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt /*add_ascs*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedRecommendationAction(test_address0, LeAudioHealthBasedAction::DISABLE))
          .Times(1);

  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(1);

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  /* Make sure Group has not knowledge about the device */
  ON_CALL(mock_groups_module_, GetGroupId(_, _))
          .WillByDefault([](const RawAddress& /*addr*/, bluetooth::Uuid /*uuid*/) {
            return bluetooth::groups::kGroupUnknown;
          });

  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  LeAudioHealthStatus::Get()->RemoveStatistics(test_address0, bluetooth::groups::kGroupUnknown);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudDisable_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  int conn_id = 1;

  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  LeAudioClient::Get()->GroupSetActive(group_id_);
  auto device = std::make_shared<LeAudioDevice>(test_address0, DeviceConnectState::DISCONNECTED);
  group_->AddNode(device);
  SyncOnMainLoop();

  auto health_status = LeAudioHealthStatus::Get();

  /* Inject stream error */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedGroupRecommendationAction(group_id_, LeAudioHealthBasedAction::DISABLE))
          .Times(1);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);

  /* Do not act on disconnect */
  ON_CALL(mock_gatt_interface_, Close(_)).WillByDefault(DoAll(Return()));
  ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).WillByDefault(DoAll(Return()));

  state_machine_callbacks_->OnStateTransitionTimeout(group_id_);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedGroupRecommendationAction(group_id_, LeAudioHealthBasedAction::DISABLE))
          .Times(0);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHealthStatus, ConnectOneEarbudConsiderDisabling_withHealthStatus) {
  const RawAddress test_address0 = GetTestAddress(0);
  int conn_id = 1;

  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  ConnectLeAudio(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  LeAudioClient::Get()->GroupSetActive(group_id_);
  auto device = std::make_shared<LeAudioDevice>(test_address0, DeviceConnectState::DISCONNECTED);
  group_->AddNode(device);
  SyncOnMainLoop();

  auto health_status = LeAudioHealthStatus::Get();

  /* Inject stream success and error */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnHealthBasedGroupRecommendationAction(group_id_,
                                                     LeAudioHealthBasedAction::CONSIDER_DISABLING))
          .Times(1);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_SUCCESS);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);

  /* Do not act on disconnect */
  ON_CALL(mock_gatt_interface_, Close(_)).WillByDefault(DoAll(Return()));
  ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).WillByDefault(DoAll(Return()));

  state_machine_callbacks_->OnStateTransitionTimeout(group_id_);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(
          mock_audio_hal_client_callbacks_,
          OnHealthBasedGroupRecommendationAction(1, LeAudioHealthBasedAction::CONSIDER_DISABLING))
          .Times(0);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  health_status->AddStatisticForGroup(group_, LeAudioHealthGroupStatType::STREAM_CREATE_CIS_FAILED);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, ConnectDisconnectOneEarbud) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
  DisconnectLeAudioWithAclClose(test_address0, 1);
}

TEST_F(UnicastTest, ConnectRemoteServiceDiscoveryCompleteBeforeEncryption) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  ConnectLeAudio(test_address0, false);
  InjectSearchCompleteEvent(conn_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));
  InjectEncryptionChangedEvent(test_address0);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, DisconnectWhenLinkKeyIsGone) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;
  SetSampleDatabaseEarbudsValid(conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(false)));

  ON_CALL(mock_btm_interface_, SetEncryption(test_address0, _, _, _, _))
          .WillByDefault(Return(tBTM_STATUS::BTM_ERR_KEY_MISSING));

  EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(1);
  do_in_main_thread(base::BindOnce(&LeAudioClient::Connect, base::Unretained(LeAudioClient::Get()),
                                   test_address0));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

/* same as above case except the disconnect is initiated by remote */
TEST_F(UnicastTest, ConnectRemoteDisconnectOneEarbud) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  /* Make sure when remote device disconnects us, TA is used */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  /* When reconnected, we always remove background connect, as we do not track
   * which type (allow list or TA) was used and then make sure the TA is used.
   */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

/* same as above case except the disconnect is initiated by remote */
TEST_F(UnicastTest, ConnectRemoteDisconnectOnTimeoutOneEarbud) {
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  /* Remove default action on the direct connect */
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _)).WillByDefault(Return());

  /* For remote disconnection, expect stack to try background re-connect */
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  InjectDisconnectedEvent(1, GATT_CONN_TIMEOUT);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, ConnectTwoEarbudsCsisGrouped) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  /* for Target announcements AutoConnect is always there, until
   * device is removed
   */
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, false)).Times(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, false)).Times(0);

  // Verify grouping information
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  DisconnectLeAudioWithAclClose(test_address0, 1);
  DisconnectLeAudioWithAclClose(test_address1, 2);
}

TEST_F(UnicastTest, ConnectTwoEarbudsCsisGroupUnknownAtConnect) {
  uint8_t group_size = 2;
  uint8_t group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  // First earbud connects without known grouping
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  // Verify grouping information
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  /* for Target announcements AutoConnect is always there, until
   *  device is removed
   */
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, false)).Times(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, false)).Times(0);
  DisconnectLeAudioWithAclClose(test_address0, 1);
  DisconnectLeAudioWithAclClose(test_address1, 2);
}

TEST_F(UnicastTestNoInit, ConnectFailedDueToInvalidParameters) {
  // Prepare two devices
  uint8_t group_size = 2;
  uint8_t group_id = 2;

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, false))
          .WillByDefault(DoAll(Return()));

  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 1);

  const RawAddress test_address1 = GetTestAddress(1);
  SetSampleDatabaseEarbudsValid(2, test_address1, codec_spec_conf::kLeAudioLocationFrontRight,
                                codec_spec_conf::kLeAudioLocationFrontRight, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 2);

  // Load devices from the storage when storage API is called
  bool autoconnect = true;

  /* Common storage values */
  std::vector<uint8_t> handles;
  LeAudioClient::GetHandlesForStorage(test_address0, handles);

  std::vector<uint8_t> ases;
  LeAudioClient::GetAsesForStorage(test_address0, ases);

  std::vector<uint8_t> src_pacs;
  LeAudioClient::GetSourcePacsForStorage(test_address0, src_pacs);

  std::vector<uint8_t> snk_pacs;
  LeAudioClient::GetSinkPacsForStorage(test_address0, snk_pacs);

  std::vector<uint8_t> gmap_data;
  LeAudioClient::GetGmapForStorage(test_address0, gmap_data);

  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::Bind(&LeAudioClient::AddFromStorage, test_address0, autoconnect,
                                 codec_spec_conf::kLeAudioLocationFrontLeft,
                                 codec_spec_conf::kLeAudioLocationFrontLeft, 0xff, 0xff,
                                 std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                 std::move(ases), std::move(gmap_data)));
    do_in_main_thread(base::Bind(&LeAudioClient::AddFromStorage, test_address1, autoconnect,
                                 codec_spec_conf::kLeAudioLocationFrontRight,
                                 codec_spec_conf::kLeAudioLocationFrontRight, 0xff, 0xff,
                                 std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                 std::move(ases), std::move(gmap_data)));
  });

  // Expect stored device0 to connect automatically (first directed connection )
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  // Expect stored device1 to connect automatically (first direct connection)
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address1, _))
          .WillByDefault(DoAll(Return(true)));
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  ON_CALL(mock_groups_module_, GetGroupId(_, _)).WillByDefault(DoAll(Return(group_id)));

  ON_CALL(mock_btm_interface_, GetSecurityFlagsByTransport(test_address0, NotNull(), _))
          .WillByDefault(DoAll(SetArgPointee<1>(BTM_SEC_FLAG_ENCRYPTED), Return(true)));

  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;

  // Initialize
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Simulate connect parameters are invalid and phone does not fallback
  // to background connect.
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(0);

  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(0);

  // Devices not found
  InjectConnectedEvent(test_address0, 0, GATT_ILLEGAL_PARAMETER);
  InjectConnectedEvent(test_address1, 0, GATT_ILLEGAL_PARAMETER);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTestNoInit, LoadStoredEarbudsBroakenStorage) {
  // Prepare two devices
  uint8_t group_size = 2;
  uint8_t group_id = 2;
  /* If the storage has been broken, make sure device will be rediscovered after
   * reconnection
   */

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, false))
          .WillByDefault(DoAll(Return()));

  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 1);

  const RawAddress test_address1 = GetTestAddress(1);
  SetSampleDatabaseEarbudsValid(2, test_address1, codec_spec_conf::kLeAudioLocationFrontRight,
                                codec_spec_conf::kLeAudioLocationFrontRight, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 2);

  // Load devices from the storage when storage API is called
  bool autoconnect = true;
  std::vector<uint8_t> empty_buf;

  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::BindOnce(
            &LeAudioClient::AddFromStorage, test_address0, autoconnect,
            codec_spec_conf::kLeAudioLocationFrontLeft, codec_spec_conf::kLeAudioLocationFrontLeft,
            0xff, 0xff, std::move(empty_buf), std::move(empty_buf), std::move(empty_buf),
            std::move(empty_buf), std::move(empty_buf)));
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address1, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontRight,
                                     codec_spec_conf::kLeAudioLocationFrontRight, 0xff, 0xff,
                                     std::move(empty_buf), std::move(empty_buf),
                                     std::move(empty_buf), std::move(empty_buf),
                                     std::move(empty_buf)));
    SyncOnMainLoop();
  });

  // Expect stored device0 to connect automatically (first directed connection )
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  // Expect stored device1 to connect automatically (first direct connection)
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address1, _))
          .WillByDefault(DoAll(Return(true)));
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  ON_CALL(mock_groups_module_, GetGroupId(_, _)).WillByDefault(DoAll(Return(group_id)));

  ON_CALL(mock_btm_interface_, GetSecurityFlagsByTransport(test_address0, NotNull(), _))
          .WillByDefault(DoAll(SetArgPointee<1>(BTM_SEC_FLAG_ENCRYPTED), Return(true)));

  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;

  // Initialize
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Simulate devices are not there and phone fallbacks to targeted
  // announcements
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  // Devices not found
  InjectConnectedEvent(test_address0, 0, GATT_ERROR);
  InjectConnectedEvent(test_address1, 0, GATT_ERROR);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  /* Stack should rediscover services as storage is broken */
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(2, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(1, _)).Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address1))
          .Times(1);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  InjectConnectedEvent(test_address1, 2);
  SyncOnMainLoop();

  // Verify if all went well and we got the proper group
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  DisconnectLeAudioWithAclClose(test_address0, 1);
  DisconnectLeAudioWithAclClose(test_address1, 2);
}

TEST_F(UnicastTestNoInit, LoadStoredEarbudsCsisGrouped) {
  // Prepare two devices
  uint8_t group_size = 2;
  uint8_t group_id = 2;

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, false))
          .WillByDefault(DoAll(Return()));

  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 1);

  const RawAddress test_address1 = GetTestAddress(1);
  SetSampleDatabaseEarbudsValid(2, test_address1, codec_spec_conf::kLeAudioLocationFrontRight,
                                codec_spec_conf::kLeAudioLocationFrontRight, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 2);

  // Load devices from the storage when storage API is called
  bool autoconnect = true;

  /* Common storage values */
  std::vector<uint8_t> handles;
  LeAudioClient::GetHandlesForStorage(test_address0, handles);

  std::vector<uint8_t> ases;
  LeAudioClient::GetAsesForStorage(test_address0, ases);

  std::vector<uint8_t> src_pacs;
  LeAudioClient::GetSourcePacsForStorage(test_address0, src_pacs);

  std::vector<uint8_t> snk_pacs;
  LeAudioClient::GetSinkPacsForStorage(test_address0, snk_pacs);

  std::vector<uint8_t> gmap_data;
  LeAudioClient::GetGmapForStorage(test_address0, gmap_data);

  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address0, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontLeft,
                                     codec_spec_conf::kLeAudioLocationFrontLeft, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address1, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontRight,
                                     codec_spec_conf::kLeAudioLocationFrontRight, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    SyncOnMainLoop();
  });

  // Expect stored device0 to connect automatically (first directed connection )
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  // Expect stored device1 to connect automatically (first direct connection)
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address1, _))
          .WillByDefault(DoAll(Return(true)));
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  ON_CALL(mock_groups_module_, GetGroupId(_, _)).WillByDefault(DoAll(Return(group_id)));

  ON_CALL(mock_btm_interface_, GetSecurityFlagsByTransport(test_address0, NotNull(), _))
          .WillByDefault(DoAll(SetArgPointee<1>(BTM_SEC_FLAG_ENCRYPTED), Return(true)));

  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;

  // Initialize
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Simulate devices are not there and phone fallbacks to targeted
  // announcements
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  // Devices not found
  InjectConnectedEvent(test_address0, 0, GATT_ERROR);
  InjectConnectedEvent(test_address1, 0, GATT_ERROR);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address1))
          .Times(1);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  InjectConnectedEvent(test_address1, 2);
  SyncOnMainLoop();

  // Verify if all went well and we got the proper group
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  DisconnectLeAudioWithAclClose(test_address0, 1);
  DisconnectLeAudioWithAclClose(test_address1, 2);
}

TEST_F(UnicastTest, LoadStoredBandedHeadphones) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0,
          codec_spec_conf::kLeAudioLocationFrontLeft | codec_spec_conf::kLeAudioLocationFrontRight,
          codec_spec_conf::kLeAudioLocationMonoAudio, 2, 1, 0x0004,
          /* source sample freq 16khz */ false, /*add_csis*/
          true,                                 /*add_cas*/
          true,                                 /*add_pacs*/
          true,                                 /*add_ascs*/
          0, 0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  /* Connect and fill the device storage */
  ConnectLeAudio(test_address0);

  std::vector<uint8_t> handles;
  LeAudioClient::GetHandlesForStorage(test_address0, handles);

  std::vector<uint8_t> ases;
  LeAudioClient::GetAsesForStorage(test_address0, ases);

  std::vector<uint8_t> src_pacs;
  LeAudioClient::GetSourcePacsForStorage(test_address0, src_pacs);

  std::vector<uint8_t> snk_pacs;
  LeAudioClient::GetSinkPacsForStorage(test_address0, snk_pacs);

  std::vector<uint8_t> gmap_data;
  LeAudioClient::GetGmapForStorage(test_address0, gmap_data);

  /* Disconnect & Cleanup */
  DisconnectLeAudioWithAclClose(test_address0, conn_id);
  if (LeAudioClient::IsLeAudioClientRunning()) {
    LeAudioClient::Cleanup();
    ASSERT_FALSE(LeAudioClient::IsLeAudioClientRunning());
  }

  Mock::VerifyAndClearExpectations(&mock_hal_2_1_verifier);
  Mock::VerifyAndClearExpectations(&mock_storage_load);

  // Load devices from the storage when storage API is called
  bool autoconnect = true;
  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address0, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontLeft |
                                             codec_spec_conf::kLeAudioLocationFrontRight,
                                     codec_spec_conf::kLeAudioLocationMonoAudio, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    SyncOnMainLoop();
  });

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, false))
          .WillByDefault(DoAll(Return()));

  // Re-Initialize & load from storage
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  InjectConnectedEvent(test_address0, conn_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Verify if all went well and we got the proper group
  auto group_id = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address0);
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());

  SetUpMockCodecManager(::bluetooth::le_audio::types::CodecLocation::HOST);

  // Start streaming
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupCurrentCodecConf(group_id, _, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);

  auto device = group->GetFirstDevice();
  ASSERT_NE(device, nullptr);
  ASSERT_EQ(device->audio_directions_, bluetooth::le_audio::types::kLeAudioDirectionSink |
                                               bluetooth::le_audio::types::kLeAudioDirectionSource);

  DisconnectLeAudioWithAclClose(test_address0, conn_id);
}

TEST_F(UnicastTestNoInit, ServiceChangedBeforeServiceIsConnected) {
  // Prepare two devices
  uint8_t group_size = 2;
  uint8_t group_id = 2;

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, false))
          .WillByDefault(DoAll(Return()));

  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 1);

  const RawAddress test_address1 = GetTestAddress(1);
  SetSampleDatabaseEarbudsValid(2, test_address1, codec_spec_conf::kLeAudioLocationFrontRight,
                                codec_spec_conf::kLeAudioLocationFrontRight, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 2);

  // Load devices from the storage when storage API is called
  bool autoconnect = true;

  /* Common storage values */
  std::vector<uint8_t> handles;
  LeAudioClient::GetHandlesForStorage(test_address0, handles);

  std::vector<uint8_t> ases;
  LeAudioClient::GetAsesForStorage(test_address0, ases);

  std::vector<uint8_t> src_pacs;
  LeAudioClient::GetSourcePacsForStorage(test_address0, src_pacs);

  std::vector<uint8_t> snk_pacs;
  LeAudioClient::GetSinkPacsForStorage(test_address0, snk_pacs);

  std::vector<uint8_t> gmap_data;
  LeAudioClient::GetGmapForStorage(test_address0, gmap_data);

  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address0, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontLeft,
                                     codec_spec_conf::kLeAudioLocationFrontLeft, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address1, autoconnect,
                                     codec_spec_conf::kLeAudioLocationFrontRight,
                                     codec_spec_conf::kLeAudioLocationFrontRight, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    SyncOnMainLoop();
  });

  // Expect stored device0 to connect automatically (first directed connection )
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  // Expect stored device1 to connect automatically (first direct connection)
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address1, _))
          .WillByDefault(DoAll(Return(true)));
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  ON_CALL(mock_groups_module_, GetGroupId(_, _)).WillByDefault(DoAll(Return(group_id)));

  ON_CALL(mock_btm_interface_, GetSecurityFlagsByTransport(test_address0, NotNull(), _))
          .WillByDefault(DoAll(SetArgPointee<1>(BTM_SEC_FLAG_ENCRYPTED), Return(true)));

  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;

  // Initialize
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  /* Inject Service Changed */
  InjectServiceChangedEvent(test_address1, 0xffff);
  SyncOnMainLoop();
  InjectServiceChangedEvent(test_address0, 0xffff);
  SyncOnMainLoop();
  /* Stack should rediscover services as storage is broken */
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(2, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(1, _)).Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address1))
          .Times(1);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  InjectConnectedEvent(test_address1, 2);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestNoInit, LoadStoredEarbudsCsisGroupedDifferently) {
  // Prepare two devices
  uint8_t group_size = 1;

  // Device 0
  uint8_t group_id0 = 2;
  bool autoconnect0 = true;
  const RawAddress test_address0 = GetTestAddress(0);
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                true,                                /*add_ascs*/
                                group_size, 1);

  ON_CALL(mock_groups_module_, GetGroupId(test_address0, _))
          .WillByDefault(DoAll(Return(group_id0)));

  // Device 1
  uint8_t group_id1 = 3;
  bool autoconnect1 = false;
  const RawAddress test_address1 = GetTestAddress(1);
  SetSampleDatabaseEarbudsValid(2, test_address1, codec_spec_conf::kLeAudioLocationFrontRight,
                                codec_spec_conf::kLeAudioLocationFrontRight, default_channel_cnt,
                                default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ true, /*add_csis*/
                                true,                                /*add_cas*/
                                true,                                /*add_pacs*/
                                default_ase_cnt,                     /*add_ascs_cnt*/
                                group_size, 2);

  ON_CALL(mock_groups_module_, GetGroupId(test_address1, _))
          .WillByDefault(DoAll(Return(group_id1)));

  /* Commont storage values */
  std::vector<uint8_t> handles;
  LeAudioClient::GetHandlesForStorage(test_address0, handles);

  std::vector<uint8_t> ases;
  LeAudioClient::GetAsesForStorage(test_address0, ases);

  std::vector<uint8_t> src_pacs;
  LeAudioClient::GetSourcePacsForStorage(test_address0, src_pacs);

  std::vector<uint8_t> snk_pacs;
  LeAudioClient::GetSinkPacsForStorage(test_address0, snk_pacs);

  std::vector<uint8_t> gmap_data;
  LeAudioClient::GetGmapForStorage(test_address0, gmap_data);

  // Load devices from the storage when storage API is called
  EXPECT_CALL(mock_storage_load, Call()).WillOnce([&]() {
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address0, autoconnect0,
                                     codec_spec_conf::kLeAudioLocationFrontLeft,
                                     codec_spec_conf::kLeAudioLocationFrontLeft, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
    do_in_main_thread(base::BindOnce(&LeAudioClient::AddFromStorage, test_address1, autoconnect1,
                                     codec_spec_conf::kLeAudioLocationFrontRight,
                                     codec_spec_conf::kLeAudioLocationFrontRight, 0xff, 0xff,
                                     std::move(handles), std::move(snk_pacs), std::move(src_pacs),
                                     std::move(ases), std::move(gmap_data)));
  });

  // Expect stored device0 to connect automatically
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  // First device will got connected
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  // Expect stored device1 to NOT connect automatically
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address1))
          .Times(0);
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address1, _))
          .WillByDefault(DoAll(Return(true)));

  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(0);

  // Initialize
  BtaAppRegisterCallback app_register_callback;
  ON_CALL(mock_gatt_interface_, AppRegister(_, _, _, _))
          .WillByDefault(DoAll(SaveArg<1>(&gatt_callback), SaveArg<2>(&app_register_callback)));
  std::vector<::bluetooth::le_audio::btle_audio_codec_config_t> framework_encode_preference;
  LeAudioClient::Initialize(
          &mock_audio_hal_client_callbacks_,
          base::Bind([](MockFunction<void()>* foo) { foo->Call(); }, &mock_storage_load),
          base::Bind([](MockFunction<bool()>* foo) { return foo->Call(); }, &mock_hal_2_1_verifier),
          framework_encode_preference);
  if (app_register_callback) {
    app_register_callback.Run(gatt_if, GATT_SUCCESS);
  }

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Simulate device is not there and phone fallbacks to targeted announcements
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  // Devices 0 is connected. Disconnect it
  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  /* Keep device in Getting Ready state */
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(false)));
  ON_CALL(mock_btm_interface_, SetEncryption(test_address0, _, _, _, _))
          .WillByDefault(Return(tBTM_STATUS::BTM_SUCCESS));

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);

  // We need to wait for the storage callback before verifying stuff
  SyncOnMainLoop();
  ASSERT_TRUE(LeAudioClient::IsLeAudioClientRunning());
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id0);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_EQ(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  devs = LeAudioClient::Get()->GetGroupDevices(group_id1);
  ASSERT_EQ(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  /* Disconnects while being in getting ready state */
  DisconnectLeAudioWithGattClose(test_address0, 1);
}

TEST_F(UnicastTest, GroupingAddRemove) {
  // Earbud connects without known grouping
  uint8_t group_id0 = bluetooth::groups::kGroupUnknown;
  const RawAddress test_address0 = GetTestAddress(0);

  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectNonCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                       codec_spec_conf::kLeAudioLocationFrontLeft);

  group_id0 = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address0);

  // Earbud connects without known grouping
  uint8_t group_id1 = bluetooth::groups::kGroupUnknown;
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectNonCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                       codec_spec_conf::kLeAudioLocationFrontRight);

  group_id1 = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address1);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  // Verify individual groups
  ASSERT_NE(group_id0, bluetooth::groups::kGroupUnknown);
  ASSERT_NE(group_id1, bluetooth::groups::kGroupUnknown);
  ASSERT_NE(group_id0, group_id1);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id0).size(), 1u);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id1).size(), 1u);

  // Expectations on reassigning second earbud to the first group
  int dev1_storage_group = bluetooth::groups::kGroupUnknown;
  int dev1_new_group = bluetooth::groups::kGroupUnknown;

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address1, group_id1, GroupNodeStatus::REMOVED))
          .Times(AtLeast(1));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address1, _, GroupNodeStatus::ADDED))
          .WillRepeatedly(SaveArg<1>(&dev1_new_group));
  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address1, group_id1)).Times(AtLeast(1));
  EXPECT_CALL(mock_groups_module_, AddDevice(test_address1, _, _)).Times(AnyNumber());

  LeAudioClient::Get()->GroupRemoveNode(group_id1, test_address1);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_groups_module_);
  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  EXPECT_CALL(mock_groups_module_, AddDevice(test_address1, _, group_id0)).Times(1);

  LeAudioClient::Get()->GroupAddNode(group_id0, test_address1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_groups_module_);

  dev1_storage_group = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address1);

  // Verify regrouping results
  EXPECT_EQ(dev1_new_group, group_id0);
  EXPECT_EQ(dev1_new_group, dev1_storage_group);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id1).size(), 0u);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id0).size(), 2u);
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id0);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());
}

TEST_F(UnicastTest, DoubleResumeFromAF) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  stay_at_qos_config_in_start_stream = true;

  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  LocalAudioSourceResume(false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Additional resume shall be ignored.
  LocalAudioSourceResume(false, false);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);

  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(state_machine_callbacks_)));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source cis
  constexpr uint8_t cis_count_out = 1;
  constexpr uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, DoubleResumeFromAFOnLocalSink) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  stay_at_qos_config_in_start_stream = true;

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  EXPECT_CALL(*mock_le_audio_sink_hal_client_, CancelStreamingRequest()).Times(0);

  // Actuall test here: send additional resume which shall be ignored.
  LocalAudioSinkResume();

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);

  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(state_machine_callbacks_)));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on local audio sink which is started
  constexpr uint8_t cis_count_out = 0;
  constexpr uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 0, 40);
}

TEST_F(UnicastTest, HandleResumeWithoutMetadataUpdateOnLocalSink) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * In this test we want to make sure that if MetadataUpdate is
   * not called before Resume, but the context type is supported,
   * stream should be created
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on local audio sink which is started
  constexpr uint8_t cis_count_out = 0;
  constexpr uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 0, 40);

  SyncOnMainLoop();
  /* Clear cache by changing context types, this is required for the test
   * as setting active device actually generate cache
   */
  auto sink_available_context = types::kLeAudioContextAllRemoteSinkOnly;
  auto source_available_context = types::kLeAudioContextAllRemoteSource;
  InjectAvailableContextTypes(test_address0, 1, sink_available_context, source_available_context);

  StopStreaming(group_id, true);
  SyncOnMainLoop();

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  // Resume without metadata update while cached configuration is cleared
  LocalAudioSinkResume();
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, GroupSetActiveNonConnectedGroup) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;
  int conn_id = 1;
  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, GroupStatus::INACTIVE))
          .Times(1);

  InjectDisconnectedEvent(conn_id);
  SyncOnMainLoop();

  // Audio sessions are started only when device gets active
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupSelectableCodecConf(group_id, _, _))
          .Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupCurrentCodecConf(group_id, _, _))
          .Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(0);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(0);

  // try to set active group on non connected group

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, GroupSetActive_CurrentCodecSentOfActive) {
  com::android::bluetooth::flags::provider_->leaudio_codec_config_callback_order_fix(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupSelectableCodecConf(group_id, _, _))
          .Times(1);
  btle_audio_codec_config_t empty_conf{};
  btle_audio_codec_config_t output_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 120};

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnAudioGroupCurrentCodecConf(group_id, empty_conf, output_config))
          .Times(1);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, GroupSetActive) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupSelectableCodecConf(group_id, _, _))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, GroupStatus::ACTIVE))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(_, GroupStatus::INACTIVE)).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, true))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(UnicastTest, GroupSetActive_SinkPacksEmpty) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  empty_sink_pack_ = true;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  std::vector<btle_audio_codec_config_t> empty_confs;

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnAudioGroupSelectableCodecConf(group_id, _, empty_confs))
          .Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, true))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(UnicastTest, GroupSetActive_SourcePacksEmpty) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  empty_source_pack_ = true;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  std::vector<btle_audio_codec_config_t> empty_confs;

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnAudioGroupSelectableCodecConf(group_id, empty_confs, _))
          .Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, GroupSetActive_and_InactiveDuringStreamConfiguration) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  empty_source_pack_ = true;

  /**
   * In this test we want to make sure that StopStream is called when group is set to inactive
   * while being between IDLE and CONFIGURED state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  stay_at_qos_config_in_start_stream = true;

  LeAudioClient::Get()->GroupSetActive(group_id);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID, false,
                 false);

  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, AnotherGroupSetActive_DuringMediaStream) {
  com::android::bluetooth::flags::provider_->leaudio_improve_switch_during_phone_call(true);
  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);
  int group_id_1 = 1;
  int group_id_2 = 2;
  int group_size = 1;

  default_channel_cnt = 2;
  default_src_channel_cnt = 1;

  /**
   * Scenario:
   * 1. Voip is started
   * 2. Group 1 is set active - no fast configuration is expected
   * 3. Audio HAL resumse the stream and Group 1 is streaming
   * 3. Group 2 is set to active - it is expected that stream to Group 1 is stopped and Group 2
   * configuration goes to QoS configured
   */
  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));
  ON_CALL(mock_csis_client_module_, GetDesiredSize(_)).WillByDefault(Return(group_size));

  log::info("Connecting first Group with device {}", test_address0);
  ConnectCsisDevice(
          test_address0, 1 /* conn_id*/,
          codec_spec_conf::kLeAudioLocationFrontLeft | codec_spec_conf::kLeAudioLocationFrontRight,
          codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_1, 1 /* rank*/);

  SyncOnMainLoop();

  log::info("Connecting second Group with device {}", test_address1);
  ConnectCsisDevice(
          test_address1, 2 /* conn_id*/,
          codec_spec_conf::kLeAudioLocationFrontLeft | codec_spec_conf::kLeAudioLocationFrontRight,
          codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_2, 1 /* rank*/);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(0);

  log::info("Group is getting Active");
  LeAudioClient::Get()->GroupSetActive(group_id_1);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("Audio HAL opens for Media");

  constexpr int gmcs_ccid = 1;
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, static_cast<int>(LeAudioContextType::MEDIA));

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::MEDIA, _, ccids))
          .Times(AtLeast(1));

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id_1);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  log::info("Set group 2 as active one ");
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_,
              ConfigureStream(_, types::LeAudioContextType::MEDIA, _, _, false))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id_2);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  LocalAudioSourceResume();
  SyncOnMainLoop();
}

TEST_F(UnicastTest, AnotherGroupSetActive_DuringVoip) {
  com::android::bluetooth::flags::provider_->leaudio_improve_switch_during_phone_call(true);
  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);
  int group_id_1 = 1;
  int group_id_2 = 2;
  int group_size = 1;

  default_channel_cnt = 2;
  default_src_channel_cnt = 1;

  /**
   * Scenario:
   * 1. Voip is started
   * 2. Group 1 is set active - no fast configuration is expected
   * 3. Audio HAL resumse the stream and Group 1 is streaming
   * 3. Group 2 is set to active - it is expected that stream to Group 1 is stopped and Group 2
   * configuration goes to QoS configured
   */
  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));
  ON_CALL(mock_csis_client_module_, GetDesiredSize(_)).WillByDefault(Return(group_size));

  log::info("Connecting first Group with device {}", test_address0);
  ConnectCsisDevice(
          test_address0, 1 /* conn_id*/,
          codec_spec_conf::kLeAudioLocationFrontLeft | codec_spec_conf::kLeAudioLocationFrontRight,
          codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_1, 1 /* rank*/);

  SyncOnMainLoop();

  log::info("Connecting second Group with device {}", test_address1);
  ConnectCsisDevice(
          test_address1, 2 /* conn_id*/,
          codec_spec_conf::kLeAudioLocationFrontLeft | codec_spec_conf::kLeAudioLocationFrontRight,
          codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_2, 1 /* rank*/);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(0);

  log::info("Group is getting Active");
  LeAudioClient::Get()->GroupSetActive(group_id_1);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("Audio HAL opens for Converstational");

  // VOIP not using Telecom API has no ccids.
  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {}, .source = {}};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, _, ccids))
          .Times(AtLeast(1));

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id_1);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  log::info("Set group 2 as active one ");
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, true)).Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id_2);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, GroupSetActive_and_GroupSetInactive_DuringPhoneCall) {
  com::android::bluetooth::flags::provider_->leaudio_improve_switch_during_phone_call(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario:
   * 1. Call is started
   * 2. Group is set active - it is expected the state machine to be instructed to Configure to Qos
   * 3. Group is set to inactive - it is expected that state machine is instructed to stop *
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, true)).Times(1);

  log::info("Call is started and group is getting Active");
  LeAudioClient::Get()->SetInCall(true);
  LeAudioClient::Get()->GroupSetActive(group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("Group is getting inctive");
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, GroupSetActive_DuringPhoneCall_ThenResume) {
  com::android::bluetooth::flags::provider_->leaudio_improve_switch_during_phone_call(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario:
   * 1. Call is started
   * 2. Group is set active - it is expected the state machine to be instructed to Configure to Qos
   * 3. Audio Framework callse Resume - expect stream is started.
   * 4. Group is set to inactive - it is expected that state machine is instructed to stop *
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, true)).Times(1);

  log::info("Call is started and group is getting Active");
  LeAudioClient::Get()->SetInCall(true);
  LeAudioClient::Get()->GroupSetActive(group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("AF resumes the stream");
  /* Simulate resume and expect StartStream to be called.
   * Do not expect confirmation on resume, as this part is not mocked on the state machine
   */
  EXPECT_CALL(mock_state_machine_, StartStream(_, LeAudioContextType::CONVERSATIONAL, _, _))
          .Times(1);
  LocalAudioSourceResume(true, false);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("Group is getting inactive");
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, ChangeAvailableContextTypeWhenInCodecConfigured) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * In this test we want to make sure that Available context change reach Java
   * when group is in Configured state
   */

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on local audio sink which is started
  constexpr uint8_t cis_count_out = 0;
  constexpr uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 0, 40);

  // Remember group to set the state after stopping the stream
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  SyncOnMainLoop();
  StopStreaming(group_id, true);
  SyncOnMainLoop();

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // Simulate state Configured
  group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioConf(_, _, _, _, _));

  /* Check if available context will be sent to Java */
  auto sink_available_context = types::kLeAudioContextAllRemoteSinkOnly;
  auto source_available_context = types::kLeAudioContextAllRemoteSource;

  InjectAvailableContextTypes(test_address0, 1, sink_available_context, source_available_context);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, TestUpdateConfigurationCallbackWhileStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupCurrentCodecConf(group_id, _, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // When metadata update happen, there should be no configuration change
  // callback sent
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnAudioGroupCurrentCodecConf(group_id, _, _))
          .Times(0);

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_ALARM, AUDIO_CONTENT_TYPE_UNKNOWN);

  // Inject STREAMING Status from state machine.
  auto group = streaming_groups.at(group_id);
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks,
             LeAudioDeviceGroup* /*group*/) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(this->state_machine_callbacks_), std::move(group)));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, TestDeactivateWhileStartingStream) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  // Deactivate while starting to stream
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);

  // Inject STREAMING Status from state machine.
  auto group = streaming_groups.at(group_id);
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks,
             LeAudioDeviceGroup* /*group*/) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(this->state_machine_callbacks_), std::move(group)));
  SyncOnMainLoop();
}

TEST_F(UnicastTest, RemoveNodeWhileStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  constexpr uint8_t cis_count_out = 1;
  constexpr uint8_t cis_count_in = 0;

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address0, group_id)).Times(1);
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  EXPECT_CALL(mock_state_machine_, ProcessHciNotifAclDisconnected(_, _)).Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, group_id, GroupNodeStatus::REMOVED));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  LeAudioClient::Get()->GroupRemoveNode(group_id, test_address0);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_groups_module_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, InactiveDeviceOnInternalStateMachineError) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  constexpr uint8_t cis_count_out = 1;
  constexpr uint8_t cis_count_in = 0;

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  btle_audio_codec_config_t empty_conf{};
  btle_audio_codec_config_t output_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_2,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 120};

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnAudioGroupCurrentCodecConf(group_id, empty_conf, output_config))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, GroupStatus::INACTIVE))
          .Times(1);

  /* This is internal error of the state machine */
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::RELEASING);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
}

TEST_F(UnicastTest, GroupingAddTwiceNoRemove) {
  // Earbud connects without known grouping
  uint8_t group_id0 = bluetooth::groups::kGroupUnknown;
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true))
          .WillOnce(Return())
          .RetiresOnSaturation();
  ConnectNonCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                       codec_spec_conf::kLeAudioLocationFrontLeft);

  group_id0 = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address0);

  // Earbud connects without known grouping
  uint8_t group_id1 = bluetooth::groups::kGroupUnknown;
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true))
          .WillOnce(Return())
          .RetiresOnSaturation();
  ConnectNonCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                       codec_spec_conf::kLeAudioLocationFrontRight);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  group_id1 = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address1);
  // Verify individual groups
  ASSERT_NE(group_id0, bluetooth::groups::kGroupUnknown);
  ASSERT_NE(group_id1, bluetooth::groups::kGroupUnknown);
  ASSERT_NE(group_id0, group_id1);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id0).size(), 1u);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id1).size(), 1u);

  // Expectations on reassigning second earbud to the first group
  int dev1_storage_group = bluetooth::groups::kGroupUnknown;
  int dev1_new_group = bluetooth::groups::kGroupUnknown;

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address1, group_id1, GroupNodeStatus::REMOVED))
          .Times(AtLeast(1));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address1, _, GroupNodeStatus::ADDED))
          .WillRepeatedly(SaveArg<1>(&dev1_new_group));

  // FIXME: We should expect removal with group_id context. No such API exists.
  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address1, group_id1)).Times(AtLeast(1));
  EXPECT_CALL(mock_groups_module_, AddDevice(test_address1, _, _)).Times(AnyNumber());
  EXPECT_CALL(mock_groups_module_, AddDevice(test_address1, _, group_id0)).Times(1);

  // Regroup device: assign new group without removing it from the first one
  LeAudioClient::Get()->GroupAddNode(group_id0, test_address1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_groups_module_);

  dev1_storage_group = MockDeviceGroups::DeviceGroups::Get()->GetGroupId(test_address1);

  // Verify regrouping results
  EXPECT_EQ(dev1_new_group, group_id0);
  EXPECT_EQ(dev1_new_group, dev1_storage_group);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id1).size(), 0u);
  ASSERT_EQ(LeAudioClient::Get()->GetGroupDevices(group_id0).size(), 2u);
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id0);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());
}

TEST_F(UnicastTest, RemoveTwoEarbudsCsisGrouped) {
  uint8_t group_size = 2;
  int group_id0 = 2;
  int group_id1 = 3;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  // First group - First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id0, 1 /* rank*/);

  // First group - Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id0, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Second group - First earbud
  const RawAddress test_address2 = GetTestAddress(2);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address2, true)).Times(1);
  ConnectCsisDevice(test_address2, 3 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id1, 1 /* rank*/);

  // Second group - Second earbud
  const RawAddress test_address3 = GetTestAddress(3);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address3, true)).Times(1);
  ConnectCsisDevice(test_address3, 4 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id1, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // First group - verify grouping information
  std::vector<RawAddress> group0_devs = LeAudioClient::Get()->GetGroupDevices(group_id0);
  ASSERT_NE(std::find(group0_devs.begin(), group0_devs.end(), test_address0), group0_devs.end());
  ASSERT_NE(std::find(group0_devs.begin(), group0_devs.end(), test_address1), group0_devs.end());

  // Second group - verify grouping information
  std::vector<RawAddress> group1_devs = LeAudioClient::Get()->GetGroupDevices(group_id1);
  ASSERT_NE(std::find(group1_devs.begin(), group1_devs.end(), test_address2), group1_devs.end());
  ASSERT_NE(std::find(group1_devs.begin(), group1_devs.end(), test_address3), group1_devs.end());
  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  // Expect one of the groups to be dropped and devices to be disconnected
  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address0, group_id0)).Times(1);
  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address1, group_id0)).Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, group_id0, GroupNodeStatus::REMOVED));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address1, group_id0, GroupNodeStatus::REMOVED));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address1))
          .Times(1);

  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(1, _)).Times(1);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(2, _)).Times(1);

  // Expect the other groups to be left as is
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id1, _)).Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address2))
          .Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address3))
          .Times(0);

  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(3, _)).Times(0);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(4, _)).Times(0);

  do_in_main_thread(base::BindOnce(&LeAudioClient::GroupDestroy,
                                   base::Unretained(LeAudioClient::Get()), group_id0));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
}

TEST_F(UnicastTest, ConnectAfterRemove) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  uint16_t conn_id = 1;

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  /* RemoveDevice */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));
  SyncOnMainLoop();

  ON_CALL(mock_btm_interface_, IsDeviceBonded(_, _)).WillByDefault(DoAll(Return(false)));

  do_in_main_thread(base::BindOnce(&LeAudioClient::Connect, base::Unretained(LeAudioClient::Get()),
                                   test_address0));
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveDeviceWhenConnected) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  uint16_t conn_id = 1;

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_queue_, Clean(conn_id)).Times(AtLeast(1));
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(1, _)).Times(1);

  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveDeviceWhenUserConnecting) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(DoAll(Return()));

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  ConnectLeAudio(test_address0, true, false);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveDeviceWhenAutoConnectingWithTargetedAnnouncements) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  /* Scenario
   * 1. Connect device
   * 2. Disconnect by remote device -> this shall start Reconnection Using TA
   * 3. Remove device
   */
  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0, true);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  // Inject disconnected event, Reconnect with TA shall start
  InjectDisconnectedEvent(conn_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Remove device when being in auto connect state.
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveDeviceWhenAutoConnectingAfterConnectionTimeout) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  /* Scenario
   * 1. Connect device
   * 2. Disconnect remote device with connection timeout -> this shall start direct connect
   * 3. Remove device
   */
  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0, true);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Prepare mock for direct connect and inject connection timeout
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(DoAll(Return()));
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  InjectDisconnectedEvent(conn_id, GATT_CONN_TIMEOUT);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Remove device when being in auto connect state after connection timeout
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveDeviceWhenGettingConnectionReady) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  /* Prepare  mock to not inject Service Search Complete*/
  ON_CALL(mock_gatt_interface_, ServiceSearchRequest(_, _)).WillByDefault(DoAll(Return()));

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(0);
  ConnectLeAudio(test_address0);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  EXPECT_CALL(mock_gatt_queue_, Clean(conn_id)).Times(AtLeast(1));
  EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(1);

  /* Cancel should be called in RemoveDevice */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, DisconnectDeviceWhenConnected) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;
  uint16_t conn_id = 1;

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  /* for Target announcements AutoConnect is always there, until
   * device is removed
   */
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, false)).Times(0);
  EXPECT_CALL(mock_gatt_queue_, Clean(conn_id)).Times(AtLeast(1));
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(1, _)).Times(1);

  LeAudioClient::Get()->Disconnect(test_address0);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, DisconnectDeviceWhenConnecting) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = 1;

  /* Prepare  mock to not inject connect event so the device can stay in
   * CONNECTING state*/
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(DoAll(Return()));

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  ConnectLeAudio(test_address0, true, false);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  /* Prepare on call mock on Close - to not trigger Inject Disconnection, as it
   * is done in default mock.
   */
  ON_CALL(mock_gatt_interface_, Close(_)).WillByDefault(DoAll(Return()));
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, true)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  LeAudioClient::Get()->Disconnect(test_address0);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, DisconnectDeviceWhenGettingConnectionReady) {
  const RawAddress test_address0 = GetTestAddress(0);
  uint16_t conn_id = global_conn_id;

  /* Prepare  mock to not inject Service Search Complete*/
  ON_CALL(mock_gatt_interface_, ServiceSearchRequest(_, _)).WillByDefault(DoAll(Return()));

  SetSampleDatabaseEarbudsValid(
          conn_id, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  ConnectLeAudio(test_address0);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  /* TA reconnect is enabled in ConnectLeAudio. Make sure this is not removed */
  EXPECT_CALL(mock_gatt_queue_, Clean(conn_id)).Times(AtLeast(1));
  EXPECT_CALL(mock_gatt_interface_, Close(conn_id)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(0);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, _, _)).Times(0);

  LeAudioClient::Get()->Disconnect(test_address0);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, RemoveWhileStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  constexpr uint8_t cis_count_out = 1;
  constexpr uint8_t cis_count_in = 0;

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  EXPECT_CALL(mock_groups_module_, RemoveDevice(test_address0, group_id)).Times(1);

  LeAudioDeviceGroup* group = nullptr;
  EXPECT_CALL(mock_state_machine_, ProcessHciNotifAclDisconnected(_, _))
          .WillOnce(DoAll(SaveArg<0>(&group)));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, group_id, GroupNodeStatus::REMOVED));

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_groups_module_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  ASSERT_EQ(group, nullptr);
}

TEST_F(UnicastTest, DisconnecteWhileAlmostStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  /* We want here to CIS be established but device not being yet in streaming
   * state
   */
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  /* This is test code, which will change the group state to the one which
   * is required by test
   */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group_inject = streaming_groups.at(group_id);
  group_inject->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);

  LeAudioDeviceGroup* group = nullptr;
  EXPECT_CALL(mock_state_machine_, ProcessHciNotifAclDisconnected(_, _))
          .WillOnce(DoAll(SaveArg<0>(&group)));

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);

  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(
          base::BindOnce([](LeAudioClient* client,
                            const RawAddress& test_address0) { client->Disconnect(test_address0); },
                         LeAudioClient::Get(), test_address0));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_groups_module_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  ASSERT_EQ(group->GetState(), types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
}

TEST_F(UnicastTest, DisconnecteWhileAlmostStreaming_twoDevices) {
  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);
  int group_id = 5;

  TestSetupRemoteDevices(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group_inject = streaming_groups.at(group_id);

  // This shall be called once only when first device from the group is disconnecting.
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);

  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnConnectionState(ConnectionState::DISCONNECTED, _))
          .Times(0);

  // Do not got to IDLE state imidiatelly.
  stay_at_releasing_stop_stream = true;

  log::info("First of all disconnect: {}", test_address0);
  TriggerDisconnectionFromApp(test_address0);
  SyncOnMainLoop();

  log::info("Secondly disconnect: {}", test_address1);
  TriggerDisconnectionFromApp(test_address1);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address1))
          .Times(1);

  do_in_main_thread(base::BindOnce(
          [](bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* cb, int group_id) {
            cb->StatusReportCb(group_id, GroupStreamStatus::IDLE);
          },
          state_machine_callbacks_, group_id));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, EarbudsTwsStyleStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, 0x01, 0x01,
                                codec_spec_caps::kLeAudioSamplingFreq16000Hz, false /*add_csis*/,
                                true /*add_cas*/, true /*add_pacs*/, 2 /*add_asc_cnt*/,
                                1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  log::info("Connect device");
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Expected CIS count on streaming
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;

  log::info("Group is getting Active");
  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  log::info("Start stream");
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  log::info("Verify Data transfer on one audio source cis");
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  log::info("Suspend");
  EXPECT_CALL(mock_state_machine_, SuspendStream(_)).Times(1);
  LeAudioClient::Get()->GroupSuspend(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  log::info("Resume");
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  log::info("Stop");
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, false))
          .Times(1);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(UnicastTest, SpeakerFailedConversationalStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_src_context_types_ = 0;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_snk_context_types_ = 0x0004;
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo, 0,
                                default_channel_cnt, default_channel_cnt, 0x0004,
                                /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Nothing to do - expect no crash */
}

TEST_F(UnicastTest, SpeakerStreaming) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Suspend
  /*TODO Need a way to verify STOP */
  LeAudioClient::Get()->GroupSuspend(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Resume
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Stop
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, false))
          .Times(1);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(UnicastTest, SpeakerStreamingNonDefault) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario test steps
   * 1. Set group active and stream VOICEASSISTANT
   * 2. Suspend group and resume with VOICEASSISTANT
   * 3. Stop Stream and make group inactive
   * 4. Start stream without setting metadata.
   * 5. Verify that UNSPECIFIED context type is used.
   */

  available_snk_context_types_ =
          (types::LeAudioContextType::VOICEASSISTANTS | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::UNSPECIFIED)
                  .value();
  supported_snk_context_types_ = available_snk_context_types_;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_ASSISTANT, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Suspend
  /*TODO Need a way to verify STOP */
  LeAudioClient::Get()->GroupSuspend(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Resume
  StartStreaming(AUDIO_USAGE_ASSISTANT, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Stop
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  /* When session is closed, the hal client mocks are freed - get new ones */
  SetUpMockAudioHal();
  /* Expect the previous release to clear the old audio session metadata */
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::VOICEASSISTANTS, _, _))
          .Times(0);
  EXPECT_CALL(mock_state_machine_, StartStream(_, kLeAudioDefaultConfigurationContext, _, _))
          .Times(1);
  LocalAudioSourceResume();
}

TEST_F(UnicastTest, TestUnidirectionalGameAndLiveRecording) {
  com::android::bluetooth::flags::provider_->le_audio_support_unidirectional_voice_assistant(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario test steps
   * 1. Configure group to support GAME only on SINK
   * 2. Configure group to support LIVE
   * 3. Start recording with LIVE
   * 4. Update context type with GAME
   * 5. Verify that Configuration did not changed.
   */

  available_snk_context_types_ =
          (types::LeAudioContextType::GAME | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::LIVE)
                  .value();
  supported_snk_context_types_ = available_snk_context_types_;

  available_src_context_types_ =
          (types::LeAudioContextType::LIVE | types::LeAudioContextType::UNSPECIFIED).value();
  supported_src_context_types_ = available_src_context_types_;

  default_channel_cnt = 1;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  types::BidirectionalPair<types::AudioContexts> metadata_contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::LIVE),
          .source = types::AudioContexts(types::LeAudioContextType::LIVE)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::LIVE, metadata_contexts, _))
          .Times(1);

  log::info("Connecting LeAudio to {}", test_address0);
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, true))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // We do expect only unidirectional CIS
  uint8_t cis_count_out = 0;
  uint8_t cis_count_in = 1;

  // Verify Data transfer on one local audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 0, 40);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN);
  LocalAudioSourceResume();
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TestUnidirectionalGameAndLiveRecordingMicOnlyDev) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario test steps
   * 1. Configure group to support GAME | LIVE | CONVERSATIONAL | VOICEASSISTANTS only on Source
   * 2. Start a GAME
   * 3. Start recording during the GAME
   */

  // No sink at all
  available_snk_context_types_ = 0;
  supported_snk_context_types_ = 0;

  // Source available for
  available_src_context_types_ =
          (types::LeAudioContextType::CONVERSATIONAL | types::LeAudioContextType::GAME |
           types::LeAudioContextType::LIVE | types::LeAudioContextType::VOICEASSISTANTS |
           types::LeAudioContextType::UNSPECIFIED)
                  .value();
  supported_src_context_types_ = available_src_context_types_;

  // Setup a single mic-only device
  empty_sink_pack_ = true;
  default_channel_cnt = 1;
  SampleDatabaseParameters db_params{
          .conn_id = 1,
          .addr = test_address0,
          .sink_audio_allocation = std::nullopt,
          .source_audio_allocation = codec_spec_conf::kLeAudioLocationMonoAudio,
          .sink_channel_cnt = 0,
          .source_channel_cnt = default_channel_cnt,
          .sample_freq_mask = le_audio::codec_spec_caps::kLeAudioSamplingFreq32000Hz,
          .add_csis = false,
          .add_cas = false,
          .add_pacs = true,
          .add_ascs_cnt = 1,
          .set_size = 0,
          .rank = 0,
          .gatt_status = GATT_SUCCESS,
          .max_supported_codec_frames_per_sdu = 1,
  };
  SetSampleDatabaseEarbudsValid(db_params);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  types::BidirectionalPair<types::AudioContexts> expected_metadata_contexts = {
          .sink = types::AudioContexts(),
          .source = types::AudioContexts(types::LeAudioContextType::GAME)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::GAME, expected_metadata_contexts, _))
          .Times(1);

  log::info("Connecting LeAudio to {}", test_address0);
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Both audio sessions are always started to monitor the metadata (even for mic only devices)
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, true))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // Configure a bidirectional GAME scenario (on a mic-only device)
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN);
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();

  // We do expect only unidirectional CIS
  uint8_t cis_count_out = 0;
  uint8_t cis_count_in = 1;

  // Verify Data transfer on one local audio sink
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 0, 40);
  SyncOnMainLoop();

  // Expect no reconfiguration triggered by the GAME updates
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN);
  LocalAudioSinkSuspend();
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TestUnidirectionalVoiceAssistant_Sink) {
  com::android::bluetooth::flags::provider_->le_audio_support_unidirectional_voice_assistant(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario test steps
   * 1. Configure group to support VOICEASSISTANT only on SINK
   * 2. Start stream
   * 5. Verify that Unidirectional VOICEASSISTANT has been created
   */

  available_snk_context_types_ =
          (types::LeAudioContextType::VOICEASSISTANTS | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::UNSPECIFIED)
                  .value();
  supported_snk_context_types_ = available_snk_context_types_;

  available_src_context_types_ =
          (types::LeAudioContextType::LIVE | types::LeAudioContextType::UNSPECIFIED).value();
  supported_src_context_types_ = available_src_context_types_;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  types::BidirectionalPair<types::AudioContexts> metadata_contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::VOICEASSISTANTS),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::VOICEASSISTANTS, metadata_contexts, _))
          .Times(1);

  log::info("Connecting LeAudio to {}", test_address0);
  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // We do expect only unidirectional CIS
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);

  EXPECT_CALL(*mock_codec_manager_,
              UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                mock_le_audio_sink_hal_client_, true))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_ASSISTANT, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  SyncOnMainLoop();

  // Verify Data transfer on one local audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, TestUnidirectionalVoiceAssistant_Source) {
  com::android::bluetooth::flags::provider_->le_audio_support_unidirectional_voice_assistant(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /**
   * Scenario test steps
   * 1. Configure group to support VOICEASSISTANT only on SOURCE
   * 2. Start stream
   * 5. Verify that uni-direction VOICEASSISTANT has been created
   */

  available_snk_context_types_ =
          (types::LeAudioContextType::MEDIA | types::LeAudioContextType::UNSPECIFIED).value();
  supported_snk_context_types_ = available_snk_context_types_;

  available_src_context_types_ =
          (types::LeAudioContextType::VOICEASSISTANTS | types::LeAudioContextType::LIVE |
           types::LeAudioContextType::UNSPECIFIED)
                  .value();
  supported_src_context_types_ = available_src_context_types_;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationFrontLeftOfCenter,
          codec_spec_conf::kLeAudioLocationFrontLeftOfCenter, default_channel_cnt,
          default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 2 /*set_size*/, 1 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  types::BidirectionalPair<types::AudioContexts> metadata_contexts = {
          .sink = types::AudioContexts(),
          .source = types::AudioContexts(types::LeAudioContextType::VOICEASSISTANTS)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::VOICEASSISTANTS, metadata_contexts, _))
          .Times(1);

  log::info("Connecting LeAudio device {}", test_address0);

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Expected only unidirectional CIS
  uint8_t cis_count_out = 0;
  uint8_t cis_count_in = 1;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  UpdateLocalSinkMetadata(AUDIO_SOURCE_VOICE_RECOGNITION);
  LocalAudioSinkResume();

  // Verify Data transfer on one local audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, SpeakerStreamingAutonomousRelease) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, 1 /* cis_count_out */, 0 /* cis_count_in */, 1920);

  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, GroupStatus::INACTIVE))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);

  // Inject the IDLE state as if an autonomous release happened
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);
  for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      ase.cis_state = types::CisState::IDLE;
      ase.data_path_state = types::DataPathState::IDLE;
      ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;
      InjectCisDisconnected(group_id, ase.cis_conn_hdl);
    }
  }
  // Verify no Data transfer after the autonomous release
  TestAudioDataTransfer(group_id, 0 /* cis_count_out */, 0 /* cis_count_in */, 1920);

  // Inject Releasing
  state_machine_callbacks_->StatusReportCb(group->group_id_,
                                           GroupStreamStatus::RELEASING_AUTONOMOUS);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, TwoEarbudsStreaming) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));
  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  /* Make sure configurations are non empty */
  btle_audio_codec_config_t call_config = {.codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
                                           .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
                                           .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
                                           .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
                                           .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
                                           .octets_per_frame = 80};

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnAudioGroupCurrentCodecConf(group_id, call_config, call_config))
          .Times(1);

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // Suspend
  LeAudioClient::Get()->GroupSuspend(group_id);
  SyncOnMainLoop();

  // Resume
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer still works
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // Stop
  StopStreaming(group_id, true);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Check if cache configuration is still present
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Setting group inactive, shall not change cached configuration
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());
}

TEST_F(UnicastTest, TestSetValidSingleOutputPreferredCodecConfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  btle_audio_codec_config_t preferred_output_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};
  // We did not set input preferred codec config
  btle_audio_codec_config_t empty_input_codec_config;

  int group_id = 2;
  TestSetupRemoteDevices(group_id);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
  do_in_main_thread(base::BindOnce(&LeAudioClient::SetCodecConfigPreference,
                                   base::Unretained(LeAudioClient::Get()), group_id,
                                   empty_input_codec_config, preferred_output_codec_config));
  SyncOnMainLoop();
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
  // We only set output preferred codec config so bidirectional context would
  // use default config
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::CONVERSATIONAL)),
            false);
}

TEST_F(UnicastTest, TestSetPreferredCodecConfigToNonActiveGroup) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);

  // Inactivate group 2
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);

  btle_audio_codec_config_t preferred_codec_config = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  // Re-initialize mock for destroyed hal client
  RegisterSourceHalClientMock();
  RegisterSinkHalClientMock();

  // Reconfiguration not needed as set preferred config to non active group
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(0);

  do_in_main_thread(base::BindOnce(&LeAudioClient::SetCodecConfigPreference,
                                   base::Unretained(LeAudioClient::Get()), group_id,
                                   preferred_codec_config, preferred_codec_config));
  SyncOnMainLoop();

  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Activate group 2 again
  do_in_main_thread(base::BindOnce(&LeAudioClient::GroupSetActive,
                                   base::Unretained(LeAudioClient::Get()), group_id));
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsClearPreferenceBeforeMedia) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  btle_audio_codec_config_t preferred_codec_config_before_media = {.codec_priority = -1};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  // Use legacy codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessBeforeMedia) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = false;
  // Use preferred codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceFailBeforeMedia) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  // Use legacy codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessDuringMediaWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = true;
  // Should reconfig and use preferred codec while streaming
  bool is_reconfig = true;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessDuringMediaWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 120};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = true;
  // Use preferred codec but not reconfig while streaming since same codec with
  // original
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceFailDuringMediaWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  // Use original codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeMediaClearPreferenceDuringMediaWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  btle_audio_codec_config_t preferred_codec_config_during_media = {.codec_priority = -1};

  bool set_before_media = true;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = false;
  // Should reconfig to legacy codec while streaming as we clear preferred codec
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_media, &preferred_codec_config_during_media,
                         LeAudioContextType::MEDIA, group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeMediaSetPreferenceSuccessDuringMediaWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};

  bool set_before_media = true;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = true;
  // Should reconfig to new preferred codec from old preferred codec while streaming
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_media, &preferred_codec_config_during_media,
                         LeAudioContextType::MEDIA, group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeMediaSetPreferenceSuccessDuringMediaWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  bool set_before_media = true;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = true;
  // Should not reconfig while streaming because same as previous preferred codec
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, &preferred_codec_config_during_media,
                         LeAudioContextType::MEDIA, group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeMediaSetPreferenceFailDuringMediaWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can not be used by media
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  bool set_before_media = true;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = false;
  // Should reconfig to legacy codec while streaming because invalid preferred codec
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_media, &preferred_codec_config_during_media,
                         LeAudioContextType::MEDIA, group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);
}

TEST_F(UnicastTest, TwoEarbudsClearPreferenceBeforeConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  btle_audio_codec_config_t preferred_codec_config_before_conv = {.codec_priority = -1};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Use legacy codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_conv, nullptr,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessBeforeConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Use preferred codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_conv, nullptr,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceFailBeforeConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Use legacy codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_conv, nullptr,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessDuringConvWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = true;
  // Should reconfig and use preferred codec while streaming
  bool is_reconfig = true;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSuccessDuringConvWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = true;
  // Use preferred codec but not reconfig while streaming since same codec with
  // original
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceFailDuringConvWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Use original codec and should not reconfig while streaming
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceSucessBeforeConvClearPreferenceDuringConvWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  btle_audio_codec_config_t preferred_codec_config_during_conv = {.codec_priority = -1};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Should reconfig to legacy codec while streaming as we clear preferred codec
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_conv, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeConvSetPreferenceSuccessDuringConvWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = true;
  // Should reconfig to new preferred codec from old preferred codec while
  // streaming
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_conv, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeConvSetPreferenceSuccessDuringConvWithoutReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = true;
  // Should not reconfig while streaming because same as previous preferred
  // codec
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_conv, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest,
       TwoEarbudsSetPreferenceSucessBeforeConvSetPreferenceFailDuringConvWithReconfig) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by conv
  btle_audio_codec_config_t preferred_codec_config_before_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};
  // This codec can not be used by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = true;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  // Should reconfig to legacy codec while streaming because invalid preferred
  // codec
  bool is_reconfig = true;
  TestSetCodecPreference(&preferred_codec_config_before_conv, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenIdleForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use preferred codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenIdleForMediaNotForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media but not by conv
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = true;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use preferred codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenIdleNotForMediaForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media but by conv
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenIdleNotForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_before_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 10};

  bool set_before_media = true;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(&preferred_codec_config_before_media, nullptr, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenMediaForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = true;
  // should use preferred codec and reconfig
  bool is_reconfig = true;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  log::info("simulate suspend timeout passed, alarm executing");
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  log::info("SetInCall is used by GTBS - and only then we can expect CCID to be set.");
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  log::info("should use preferred codec when switching back to media");
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenMediaForMediaNotForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can be used by media but not by conv
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = true;
  // should use preferred codec and reconfig
  bool is_reconfig = true;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use preferred codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenMediaNotForMediaForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media and but by conv
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  // should use legacy codec
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = true;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenMediaNotForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  // This codec can not be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_during_media = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  bool set_before_media = false;
  bool set_while_media = true;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  // should use legacy codec
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_media, LeAudioContextType::MEDIA,
                         group_id, set_before_media, set_while_media,
                         is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = false;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::CONVERSATIONAL, group_id,
                         set_before_conv, set_while_conv,
                         is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenConvForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  bool set_before_media = false;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::MEDIA, group_id, set_before_media,
                         set_while_media, is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // This codec can be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 40};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = true;
  // should use preferred codec and reconfig
  is_reconfig = true;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use preferred codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenConvForMediaNotForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  bool set_before_media = false;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::MEDIA, group_id, set_before_media,
                         set_while_media, is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // This codec can be used by media but not by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 60};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  // should use legacy codec
  is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use preferred codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            true);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenConvNotForMediaForConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  bool set_before_media = false;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::MEDIA, group_id, set_before_media,
                         set_while_media, is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // This codec can not be used by media but by conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 80};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = true;
  // should use preferred codec but not reconfig
  is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, TwoEarbudsSetPreferenceWhenConvNotForBothMediaAndConv) {
  com::android::bluetooth::flags::provider_->leaudio_set_codec_config_preference(true);

  int group_id = 2;
  TestSetupRemoteDevices(group_id);

  bool set_before_media = false;
  bool set_while_media = false;
  bool is_using_set_before_media_codec_during_media = false;
  bool is_using_set_while_media_codec_during_media = false;
  bool is_reconfig = false;
  TestSetCodecPreference(nullptr, nullptr, LeAudioContextType::MEDIA, group_id, set_before_media,
                         set_while_media, is_using_set_before_media_codec_during_media,
                         is_using_set_while_media_codec_during_media, is_reconfig);

  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // This codec can not be used by media and conv
  btle_audio_codec_config_t preferred_codec_config_during_conv = {
          .codec_type = LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
          .sample_rate = LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ,
          .bits_per_sample = LE_AUDIO_BITS_PER_SAMPLE_INDEX_16,
          .channel_count = LE_AUDIO_CHANNEL_COUNT_INDEX_1,
          .frame_duration = LE_AUDIO_FRAME_DURATION_INDEX_10000US,
          .octets_per_frame = 70};

  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);

  bool set_before_conv = false;
  bool set_while_conv = true;
  bool is_using_set_before_conv_codec_during_conv = false;
  bool is_using_set_while_conv_codec_during_conv = false;
  // should use legacy codec
  is_reconfig = false;
  TestSetCodecPreference(nullptr, &preferred_codec_config_during_conv,
                         LeAudioContextType::CONVERSATIONAL, group_id, set_before_conv,
                         set_while_conv, is_using_set_before_conv_codec_during_conv,
                         is_using_set_while_conv_codec_during_conv, is_reconfig);
  LeAudioClient::Get()->SetInCall(false);

  // should use legacy codec when switching back to media
  ASSERT_EQ(LeAudioClient::Get()->IsUsingPreferredCodecConfig(
                    group_id, static_cast<int>(types::LeAudioContextType::MEDIA)),
            false);
}

TEST_F(UnicastTest, StreamingVxAospSampleSound) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Test to verify that tag VX_AOSP_SAMPLESOUND is always mapped to
   * LeAudioContextType::SOUNDEFFECTS
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Set a test TAG
  char test_tag[] = "TEST_TAG2;VX_AOSP_SAMPLESOUND;TEST_TAG1";

  test_tags_ptr_ = test_tag;

  auto initial_context = types::LeAudioContextType::SOUNDEFFECTS;
  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, _, ccids)).Times(1);
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 0);
}

TEST_F(UnicastTest, UpdateActiveAudioConfigForLocalSinkSource) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Set group as active
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Start streaming - expect HAL being notified by both directions config change
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);

  /* Expect one update per direction - 2 in total for voice communication usage */
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveAudioConfig(_, _, _))
          .WillRepeatedly([&](const types::BidirectionalPair<stream_parameters>& stream_params,
                              std::function<void(const stream_config& config, uint8_t direction)>
                                      update_receiver,
                              uint8_t directions_to_update) {
            bluetooth::le_audio::stream_config unicast_cfg;
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSink) &&
                stream_params.sink.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSink);
            }
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSource) &&
                stream_params.source.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSource);
            }
          });
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_codec_manager_);

  // Verify Data transfer on two peer sinks and two sources
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // Suspend
  LeAudioClient::Get()->GroupSuspend(group_id);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, UpdateActiveAudioConfigForLocalSinkSourceLateJoin) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Set group as active
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Start streaming - expect HAL being notified by both directions config change
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);

  /* Expect one update per direction - 2 in total for voice communication usage */
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveAudioConfig(_, _, _))
          .WillRepeatedly([&](const types::BidirectionalPair<stream_parameters>& stream_params,
                              std::function<void(const stream_config& config, uint8_t direction)>
                                      update_receiver,
                              uint8_t directions_to_update) {
            bluetooth::le_audio::stream_config unicast_cfg;
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSink) &&
                stream_params.sink.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSink);
            }
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSource) &&
                stream_params.source.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSource);
            }
          });
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Second earbud join should trigger audio config update to HAL
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);

  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_codec_manager_);

  // Verify Data transfer on two peer sinks and two sources
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Second earbud disconnect should trigger audio config update to HAL
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);

  /* Simulate ASE releasing and CIS Disconnection */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);
  auto device = group->GetFirstDevice();
  for (auto& ase : device->ases_) {
    /* Releasing state */
    if (!ase.active) {
      continue;
    }

    std::vector<uint8_t> releasing_state = {
            ase.id, static_cast<uint8_t>(types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)};
    InjectNotificationEvent(device->address_, device->conn_id_, ase.hdls.val_hdl, releasing_state);
    SyncOnMainLoop();
    InjectCisDisconnected(group_id, ase.cis_conn_hdl);
    SyncOnMainLoop();
  }

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_codec_manager_);

  // Suspend
  LeAudioClient::Get()->GroupSuspend(group_id);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, UpdateActiveAudioConfigForLocalSource) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Set group as active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(0);
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveAudioConfig(_, _, _))
          .Times(1)
          .WillOnce([](const types::BidirectionalPair<stream_parameters>& stream_params,
                       std::function<void(const stream_config& config, uint8_t direction)>
                               update_receiver,
                       uint8_t directions_to_update) {
            bluetooth::le_audio::stream_config unicast_cfg;
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSink) &&
                stream_params.sink.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSink);
            }
            if ((directions_to_update & bluetooth::le_audio::types::kLeAudioDirectionSource) &&
                stream_params.source.stream_config.peer_delay_ms != 0) {
              update_receiver(unicast_cfg, bluetooth::le_audio::types::kLeAudioDirectionSource);
            }
          });
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_codec_manager_);

  // Verify Data transfer on two peer sinks and no source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // Suspend
  LeAudioClient::Get()->GroupSuspend(group_id);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, TwoEarbudsStreamingContextSwitchNoReconfigure) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Start streaming with new metadata, there was no previous stream so start
  // with this new configuration
  auto initial_context = types::LeAudioContextType::NOTIFICATIONS;
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(initial_context), .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, contexts, _)).Times(1);

  StartStreaming(AUDIO_USAGE_NOTIFICATION, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Do a metadata content switch to ALERTS but stay on previous configuration
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start).Times(0);
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::ALERTS),
              .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, contexts, _)).Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_ALARM, AUDIO_CONTENT_TYPE_UNKNOWN);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Do a metadata content switch to EMERGENCY but stay on previous
  // configuration
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start).Times(0);

  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::EMERGENCYALARM),
              .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, contexts, _)).Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_EMERGENCY, AUDIO_CONTENT_TYPE_UNKNOWN);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Do a metadata content switch to INSTRUCTIONAL but stay on previous
  // configuration
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start).Times(0);
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::INSTRUCTIONAL),
              .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, contexts, _)).Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AUDIO_CONTENT_TYPE_UNKNOWN);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, TwoEarbudsStopConversational_StartStreamSonification) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Start streaming with CONVERSATIONAL, there was no previous stream so start
  // with this new configuration
  auto initial_context = types::LeAudioContextType::CONVERSATIONAL;
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(initial_context),
          .source = types::AudioContexts(initial_context)};
  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, contexts, _)).Times(1);

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Stop the stream but KEEP cis UP
  StopStreaming(group_id, true);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  types::BidirectionalPair<types::AudioContexts> reconfigure_contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::ALERTS),
          .source = types::AudioContexts()};

  EXPECT_CALL(mock_state_machine_, StartStream(_, initial_context, reconfigure_contexts, _))
          .Times(1);

  // Change context type but expect configuration to by as previous
  StartStreaming(AUDIO_USAGE_ALARM, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Stop the stream and drop CISes
  StopStreaming(group_id, true);
  SyncOnMainLoop();
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  auto reconfigure_context = types::LeAudioContextType::ALERTS;

  EXPECT_CALL(mock_state_machine_, StartStream(_, reconfigure_context, reconfigure_contexts, _))
          .Times(1);

  // Update metadata
  StartStreaming(AUDIO_USAGE_ALARM, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TwoEarbudsStreamingContextSwitchReconfigure) {
  // TODO(b/352686917). Remove the test when flag will be removing
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(false);

  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Start streaming MEDIA
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Stop
  StopStreaming(group_id);
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  log::info("SetInCall is used by GTBS - and only then we can expect CCID to be set.");
  LeAudioClient::Get()->SetInCall(true);

  // Conversational is a bidirectional scenario so expect GTBS CCID
  // in the metadata for both directions. Can be called twice when one
  // direction resume after the other and metadata is updated.
  ccids = {.sink = {gtbs_ccid}, .source = {gtbs_ccid}};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, _, ccids))
          .Times(AtLeast(1));
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks and one source
  cis_count_out = 2;
  cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  log::info("End call");
  LeAudioClient::Get()->SetInCall(false);
  UpdateLocalSourceMetadata(AUDIO_USAGE_UNKNOWN, AUDIO_CONTENT_TYPE_UNKNOWN, false);
  UpdateLocalSinkMetadata(std::nullopt);
  // Stop
  StopStreaming(group_id, true);

  log::info("Switch back to MEDIA");
  ccids = {.sink = {gmcs_ccid}, .source = {}};
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              ConfigureStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts,
                              ccids, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID, true);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, TwoEarbudsStreamingContextSwitchReconfigure_SpeedUpReconfigFlagEnabled) {
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(true);

  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  log::info("Start streaming MEDIA");
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  log::info("Simulate incoming call");

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  Expectation reconfigure =
          EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete())
          .Times(1)
          .After(reconfigure);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(1);
  // SetInCall is used by GTBS - and only then we can expect CCID to be set.
  LeAudioClient::Get()->SetInCall(true);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Conversational is a bidirectional scenario so expect GTBS CCID
  // in the metadata for both directions. Can be called twice when one
  // direction resume after the other and metadata is updated.
  ccids = {.sink = {gtbs_ccid}, .source = {gtbs_ccid}};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, _, ccids))
          .Times(AtLeast(1));
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks and one source
  cis_count_out = 2;
  cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Stop stream will be called by SetInCall
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  reconfigure =
          EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete())
          .Times(1)
          .After(reconfigure);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(1);

  LeAudioClient::Get()->SetInCall(false);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  log::info("Switch back to media");

  ccids = {.sink = {gmcs_ccid}, .source = {}};
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              ConfigureStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts,
                              ccids, _))
          .Times(0);
  EXPECT_CALL(
          mock_state_machine_,
          StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, ccids))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID,
                 false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, TwoEarbudsVoipStreamingVerifyMetadataUpdate) {
  uint8_t group_size = 2;
  int group_id = 2;

  /*
   * Scenario
   * 1. Configure stream for the VOIP
   * 2. Verify CONVERSATIONAL metadata and context is used.
   * 3. Resume LocalSink
   * 4. Make sure there is no change of the metadata and context
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  constexpr int gtbs_ccid = 2;

  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // VOIP not using Telecom API has no ccids.
  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {}, .source = {}};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, _, ccids))
          .Times(AtLeast(1));

  UpdateLocalSourceMetadata(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH);
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);

  LocalAudioSourceResume();
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer are sending. The LocalSink is not yet resumed.
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 0);

  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL),
          .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, contexts, ccids))
          .Times(AtLeast(1));

  LocalAudioSinkResume();
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TwoEarbudsVoipDuringLiveVerifyMetadataUpdate) {
  uint8_t group_size = 2;
  int group_id = 2;

  /*
   * Scenario
   * 1. Configure stream for the mixed CONVERSATIONAL and MEDIA
   * 2. Start and Stop streaming
   * 3. Update CONVERSATIONAL metadata with additional LIVE usage for the mixed contexts
   * 4. Resume LocalSink and LocalSource
   * 5. Make sure there is only the leading CONVERSATIONAL context in the metadata and
   *    LIVE is not mixed in, as the remote devices often are confused when any other
   *    bidirectional audio context is mixed with CONVERSATIONAL
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  available_snk_context_types_ =
          (types::LeAudioContextType::CONVERSATIONAL | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::LIVE)
                  .value();
  available_src_context_types_ = available_snk_context_types_;

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  constexpr int gtbs_ccid = 2;

  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  StopStreaming(group_id);

  // Add LIVE into the mix but expect staying with CONVERSATIONAL for the configuration and the
  // metadata
  types::BidirectionalPair<types::AudioContexts> meta_contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL),
          .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, meta_contexts, _))
          .Times(AtLeast(1));

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC, AUDIO_SOURCE_VOICE_COMMUNICATION);
  UpdateLocalSourceMetadata(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, false);
  SyncOnMainLoop();

  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, TwoReconfigureAndVerifyEnableContextType) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Earbuds streaming MEDIA
   * 2. Reconfigure to VOIP
   * 3. Check if Metadata in Enable command are set to CONVERSATIONAL
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Start streaming MEDIA
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Conversational is a bidirectional scenario so expect GTBS CCID
  // in the metadata for both directions. Can be called twice when one
  // direction resume after the other and metadata is updated.
  ccids = {.sink = {gtbs_ccid}, .source = {gtbs_ccid}};
  types::BidirectionalPair<types::AudioContexts> conversiational_contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL),
          .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};

  EXPECT_CALL(mock_state_machine_,
              ConfigureStream(_, types::LeAudioContextType::CONVERSATIONAL, _, _, _))
          .Times(AtLeast(1));

  // Update metadata and resume
  UpdateLocalSourceMetadata(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, true);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::CONVERSATIONAL,
                                               conversiational_contexts, ccids))
          .Times(AtLeast(1));

  LeAudioClient::Get()->SetInCall(true);

  LocalAudioSourceResume(true);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TwoEarbuds2ndLateConnect) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect one iso channel to be fed with data
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Second earbud connects during stream
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  cis_count_out = 2;
  cis_count_in = 0;

  /* The above will trigger reconfiguration. After that Audio Hal action
   * is needed to restart the stream */
  LocalAudioSourceResume();

  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, TestStreamingContextTypeBehaviour) {
  uint8_t group_size = 2;
  int group_id = 2;
  int conn_id_1 = 1;
  int conn_id_2 = 2;

  /* Scenario
   * 1. Connect Set of devices with all the context types available
   * 2. Create stream for Media
   * 3. Remote devices removes all the Available Contexts but UNSPECIFIED
   * 4. Verify GetAvailableContexts() returns accepted MEDIA and UNSPECIFIED
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, conn_id_1, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, conn_id_2, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  auto group = streaming_groups.at(group_id);

  /* Simulate available context type being cleared */
  InjectAvailableContextTypes(test_address0, conn_id_1,
                              types::AudioContexts(LeAudioContextType::UNSPECIFIED),
                              types::AudioContexts(LeAudioContextType::UNSPECIFIED));
  InjectAvailableContextTypes(test_address1, conn_id_2,
                              types::AudioContexts(LeAudioContextType::UNSPECIFIED),
                              types::AudioContexts(LeAudioContextType::UNSPECIFIED));

  auto remote_sink_contexts =
          group->GetAvailableContexts(bluetooth::le_audio::types::kLeAudioDirectionSink);
  auto remote_source_contexts =
          group->GetAvailableContexts(bluetooth::le_audio::types::kLeAudioDirectionSource);
  ASSERT_EQ(remote_sink_contexts,
            types::AudioContexts(LeAudioContextType::MEDIA | LeAudioContextType::UNSPECIFIED));
  ASSERT_EQ(remote_source_contexts, types::AudioContexts(LeAudioContextType::UNSPECIFIED));
}

TEST_F(UnicastTest, LateStreamConnectBasedOnContextType) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Two devices A and B are connect. Device A has all available context
   * types, Device B has no available context types.
   * 2. Stream creation to Device A has been started
   * 3. Device B notified us with new available Context Types - while A is not
   * yet streaming
   * 4. Make sure AttachToStream was called for Device B
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Simulate available context type being cleared */
  InjectAvailableContextTypes(test_address1, 2, types::AudioContexts(0), types::AudioContexts(0));

  // Block streaming state
  stay_at_qos_config_in_start_stream = true;

  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  LocalAudioSourceResume(false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  InjectAvailableContextTypes(test_address1, 2, types::kLeAudioContextAllRemoteSinkOnly,
                              types::AudioContexts(0), false);

  // Now simulate group is finally streaming
  auto group = streaming_groups.at(group_id);
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks,
             LeAudioDeviceGroup* group) {
            group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(this->state_machine_callbacks_), std::move(group)));

  SyncOnMainLoop();

  /* verify AttachToStream was called while stream was not yet created. */
  ASSERT_TRUE(attach_to_stream_scheduled);

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, LateStreamConnectBasedOnContextTypeNotFullyConnected) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Device A is connected
   * 2. Stream creation to Device A has been started
   * 3. Stream is stopped
   * 4. Device B is connected but has ongoing operations of available context types read.
   * 5. Device B sends available context type  read response
   * 6. Make sure AttachToStream was NOT called for Device B since it is not in a CONNECTED state
   * yet
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);
  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  auto group = streaming_groups.at(group_id);

  // Stop streaming - simulate suspend timeout passed, alarm executing
  StopStreaming(group_id);
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  // Second earbud connects and is set to Getting Ready state
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);
  auto device1 = group->GetNextDevice(group->GetFirstDevice());
  device1->SetConnectionState(DeviceConnectState::CONNECTED_AUTOCONNECT_GETTING_READY);

  // Resume but block the final streaming state - keep the group in transition
  stay_at_qos_config_in_start_stream = true;
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  LocalAudioSourceResume(false);

  // Do not expect to attach the device on context update as it is not fully connected
  EXPECT_CALL(mock_state_machine_, AttachToStream(_, _, _)).Times(0);
  InjectAvailableContextTypes(test_address1, 2, types::kLeAudioContextAllRemoteSinkOnly,
                              types::AudioContexts(0), false);

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, CheckDeviceIsNotAttachedToStreamWhenNotNeeded) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Two devices A and B are connect. Both Devices have all available
   * contexts types
   * 2. Stream creation to Device A and B has been started
   * 3. Device B notified us with new available Context Types - while Group is
   * not yet streaming
   * 4. Make sure AttachToStream was not called for Device B since it is taking
   * part in Stream creation already
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // Block streaming state
  stay_at_qos_config_in_start_stream = true;

  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  LocalAudioSourceResume(false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  InjectAvailableContextTypes(test_address1, 2, types::kLeAudioContextAllRemoteSinkOnly,
                              types::AudioContexts(0), false);

  // Now simulate group is finally streaming
  auto group = streaming_groups.at(group_id);
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks,
             LeAudioDeviceGroup* group) {
            group->SetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(this->state_machine_callbacks_), std::move(group)));

  SyncOnMainLoop();

  /* verify AttachToStream was NOT called while stream was not yet created. */
  ASSERT_FALSE(attach_to_stream_scheduled);

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, ReconnectedDeviceAndAttachedToStreamBecauseOfAvailableContextTypeChange) {
  com::android::bluetooth::flags::provider_->le_ase_read_multiple_variable(true);

  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Two devices A and B are streaming
   * 2. Device A Release ASE and removes all available context types
   * 3. Device B keeps streaming
   * 4. Device A disconnectes
   * 5. Device A reconnect
   * 6. Device A has context available for streaming  and should be attached to the stream
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Get group and Device A */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);
  auto device = group->GetFirstDevice();

  /* Simulate available context type being cleared */
  InjectAvailableContextTypes(device->address_, device->conn_id_, types::AudioContexts(0),
                              types::AudioContexts(0));

  /* Simulate ASE releasing and CIS Disconnection */
  for (auto& ase : device->ases_) {
    /* Releasing state */
    if (!ase.active) {
      continue;
    }

    std::vector<uint8_t> releasing_state = {
            ase.id, static_cast<uint8_t>(types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)};
    InjectNotificationEvent(device->address_, device->conn_id_, ase.hdls.val_hdl, releasing_state);
    SyncOnMainLoop();
    InjectCisDisconnected(group_id, ase.cis_conn_hdl);
    SyncOnMainLoop();
  }

  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Device A will disconnect, and do not reconnect automatically */
  ON_CALL(mock_gatt_interface_, Open(_, device->address_, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());

  /* Disconnect first device */
  auto conn_id = device->conn_id_;
  InjectDisconnectedEvent(conn_id, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  /* For background connect, test needs to Inject Connected Event.
   * Note that initial available_snk_context_types_ available_src_context_types_ will
   * be read after reconnection, which should bring device to the stream again. */

  InjectConnectedEvent(device->address_, conn_id);
  SyncOnMainLoop();

  /* Check single device is streaming */
  cis_count_out = 2;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, ReconnectedDeviceNotAttachedToStreamBecauseOfNotAvailableContext) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Two devices A and B are streaming
   * 2. Device A Release ASE and removes all available context types
   * 3. Device B keeps streaming
   * 4. Device A disconnectes
   * 5. Device A reconnect and should not be attached to the stream
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Get group and Device A */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);
  auto device = group->GetFirstDevice();

  /* Simulate available context type being cleared */
  InjectAvailableContextTypes(device->address_, device->conn_id_, types::AudioContexts(0),
                              types::AudioContexts(0));

  /* Simulate ASE releasing and CIS Disconnection */
  for (auto& ase : device->ases_) {
    /* Releasing state */
    if (!ase.active) {
      continue;
    }

    std::vector<uint8_t> releasing_state = {
            ase.id, static_cast<uint8_t>(types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)};
    InjectNotificationEvent(device->address_, device->conn_id_, ase.hdls.val_hdl, releasing_state);
    SyncOnMainLoop();
    InjectCisDisconnected(group_id, ase.cis_conn_hdl);
    SyncOnMainLoop();
  }

  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Device A will disconnect, and do not reconnect automatically */
  ON_CALL(mock_gatt_interface_, Open(_, device->address_, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());

  /* Disconnect first device */
  auto conn_id = device->conn_id_;
  InjectDisconnectedEvent(conn_id, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  /* For background connect, test needs to Inject Connected Event.
   * Since after reconnect Android reads available context types, make sure
   * 0 is read */
  available_snk_context_types_ = 0;
  available_src_context_types_ = 0;

  InjectConnectedEvent(device->address_, conn_id);
  SyncOnMainLoop();

  /* Check single device is streaming */
  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, TwoEarbuds2ndReleaseAseRemoveAvailableContextAndBack) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario
   * 1. Two devices A and B are streaming
   * 2. Device A Release ASE and removes all available context types
   * 3. Device B keeps streaming
   * 4. Device A sets available context types
   * 5. Device A should be attached to the stream
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Get group and Device A */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_NE(group, nullptr);
  auto device = group->GetFirstDevice();

  /* Simulate available context type being cleared */
  InjectAvailableContextTypes(device->address_, device->conn_id_, types::AudioContexts(0),
                              types::AudioContexts(0));

  /* Simulate ASE releasing and CIS Disconnection */
  for (auto& ase : device->ases_) {
    /* Releasing state */
    if (!ase.active) {
      continue;
    }

    std::vector<uint8_t> releasing_state = {
            ase.id, static_cast<uint8_t>(types::AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)};
    InjectNotificationEvent(device->address_, device->conn_id_, ase.hdls.val_hdl, releasing_state);
    SyncOnMainLoop();
    InjectCisDisconnected(group_id, ase.cis_conn_hdl);
    SyncOnMainLoop();
  }

  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Bring back available context types */
  InjectAvailableContextTypes(device->address_, device->conn_id_, types::kLeAudioContextAllTypes,
                              types::kLeAudioContextAllTypes);

  /* Check both devices are streaming */
  cis_count_out = 2;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, StartStream_AvailableContextTypeNotifiedLater) {
  uint8_t group_size = 2;
  int group_id = 2;

  available_snk_context_types_ = 0;

  /* Scenario (Devices A and B called "Remote")
   * 1. Remote  does supports all the context types, but has NO available
   * contexts at the beginning
   * 2. After connection Remote add Available context types
   * 3. Android start stream with MEDIA
   * 4. Make sure stream will be started
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Inject Supported and available context types
  auto sink_available_contexts = types::kLeAudioContextAllRemoteSinkOnly;
  auto source_available_contexts = types::kLeAudioContextAllRemoteSource;

  InjectAvailableContextTypes(test_address0, 1, sink_available_contexts, source_available_contexts);
  InjectAvailableContextTypes(test_address1, 2, sink_available_contexts, source_available_contexts);
  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  BidirectionalPair<AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
          .source = types::AudioContexts()};

  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, ModifyContextTypeOnDeviceA_WhileDeviceB_IsDisconnected) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario (Device A and B called Remote)
   * 1. Remote set does supports all the context types and make them available
   * 2. Android start stream with MEDIA, verify it works.
   * 3. Android stops the stream
   * 4. Device B disconnects
   * 5. Device A removes Media from Available Contexts
   * 6. Android start stream with MEDIA, verify it will not be started
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  BidirectionalPair<AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
          .source = types::AudioContexts()};

  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Stop
  StopStreaming(group_id);
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Device B got disconnected and will not reconnect.
  ON_CALL(mock_gatt_interface_, Open(_, test_address1, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());
  InjectDisconnectedEvent(2, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  // Device A changes available context type
  // Inject Supported and available context types
  auto sink_supported_context = types::kLeAudioContextAllRemoteSinkOnly;
  sink_supported_context.unset(LeAudioContextType::MEDIA);
  sink_supported_context.set(LeAudioContextType::UNSPECIFIED);

  auto source_supported_context = types::kLeAudioContextAllRemoteSource;
  source_supported_context.set(LeAudioContextType::UNSPECIFIED);

  InjectSupportedContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectAvailableContextTypes(test_address0, 1, sink_supported_context, source_supported_context);

  /* Android starts stream. */
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, _)).Times(0);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID, false,
                 false);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, StartStreamToUnsupportedContextTypeUsingUnspecified) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario (Devices A and B called "Remote")
   * 1. Remote  does supports all the context types and make them available
   * 2. Remote removes SoundEffect from the supported and available context
   * types
   * 3. Android start stream with SoundEffects
   * 4. Make sure stream will be started with Unspecified context type
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Inject Supported and available context types
  auto sink_supported_context = types::kLeAudioContextAllRemoteSinkOnly;
  sink_supported_context.unset(LeAudioContextType::SOUNDEFFECTS);
  sink_supported_context.set(LeAudioContextType::UNSPECIFIED);

  auto source_supported_context = types::kLeAudioContextAllRemoteSource;
  source_supported_context.set(LeAudioContextType::UNSPECIFIED);

  InjectSupportedContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectAvailableContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectSupportedContextTypes(test_address1, 2, sink_supported_context, source_supported_context);
  InjectAvailableContextTypes(test_address1, 2, sink_supported_context, source_supported_context);
  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  BidirectionalPair<AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::UNSPECIFIED),
          .source = types::AudioContexts(0)};

  EXPECT_CALL(
          mock_state_machine_,
          StartStream(_, bluetooth::le_audio::types::LeAudioContextType::SOUNDEFFECTS, contexts, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, StartStreamToUnsupportedContextTypeUnspecifiedNotAvailable) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario (Device A and B called Remote)
   * 1. Remote does supports all the context types and make them available
   * 2. Remote removes SoundEffect from the Available Context Types
   * 3. Remote also removes UNSPECIFIED from the Available Context Types.
   * 4. Android start stream with SoundEffects
   * 5. Make sure stream will be NOT be started
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Inject Supported and available context types
  auto sink_supported_context = types::kLeAudioContextAllRemoteSinkOnly;
  sink_supported_context.unset(LeAudioContextType::SOUNDEFFECTS);
  sink_supported_context.set(LeAudioContextType::UNSPECIFIED);

  auto source_supported_context = types::kLeAudioContextAllRemoteSource;
  source_supported_context.set(LeAudioContextType::UNSPECIFIED);

  InjectSupportedContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectSupportedContextTypes(test_address1, 2, sink_supported_context, source_supported_context);

  auto sink_available_context = sink_supported_context;
  sink_available_context.unset(LeAudioContextType::UNSPECIFIED);

  auto source_available_context = source_supported_context;
  source_available_context.unset(LeAudioContextType::UNSPECIFIED);

  InjectAvailableContextTypes(test_address0, 1, sink_available_context, source_available_context);
  InjectAvailableContextTypes(test_address1, 2, sink_available_context, source_available_context);
  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  BidirectionalPair<AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::UNSPECIFIED),
          .source = types::AudioContexts()};

  EXPECT_CALL(
          mock_state_machine_,
          StartStream(_, bluetooth::le_audio::types::LeAudioContextType::SOUNDEFFECTS, contexts, _))
          .Times(0);

  StartStreaming(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION, group_id,
                 AUDIO_SOURCE_INVALID, false, false);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, StartStreamToSupportedContextTypeThenMixUnavailable) {
  uint8_t group_size = 2;
  int group_id = 2;

  /* Scenario (Device A and B called Remote)
   * 1. Remote set does supports all the context types and make them available
   * 2. Abdriud start stream with MEDIA, verify it works.
   * 3. Stream becomes to be mixed with Soundeffect and Media - verify metadata
   *    update
   * 4. Android Stop stream.
   * 5. Remote removes SoundEffect from the supported and available context
   * types
   * 6. Android start stream with MEDIA, verify it works.
   * 7. Stream becomes to be mixed with Soundeffect and Media
   * 8. Make sure metadata updated does not contain unavailable context
   *    note: eventually, Audio framework should not give us unwanted context
   * types
   */

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  // First earbud connects
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud connects
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  BidirectionalPair<AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
          .source = types::AudioContexts()};

  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // Expect two iso channel to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  contexts.sink = types::AudioContexts(types::LeAudioContextType::MEDIA |
                                       types::LeAudioContextType::SOUNDEFFECTS);
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  /* Simulate metadata update, expect upadate , metadata */
  std::vector<struct playback_track_metadata> tracks = {
          {{AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, 0},
           {AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION, 0}}};
  UpdateLocalSourceMetadata(tracks);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  /* Stop stream */
  StopStreaming(group_id);
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Inject Supported and available context types
  auto sink_supported_context = types::kLeAudioContextAllRemoteSinkOnly;
  sink_supported_context.unset(LeAudioContextType::SOUNDEFFECTS);
  sink_supported_context.set(LeAudioContextType::UNSPECIFIED);

  auto source_supported_context = types::kLeAudioContextAllRemoteSource;
  source_supported_context.set(LeAudioContextType::UNSPECIFIED);

  InjectSupportedContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectAvailableContextTypes(test_address0, 1, sink_supported_context, source_supported_context);
  InjectSupportedContextTypes(test_address1, 2, sink_supported_context, source_supported_context);
  InjectAvailableContextTypes(test_address1, 2, sink_supported_context, source_supported_context);

  // Verify cache has been removed due to available context change
  ASSERT_EQ(nullptr, group->GetCachedConfiguration(types::LeAudioContextType::MEDIA));
  /* Start Media again */
  contexts.sink = types::AudioContexts(types::LeAudioContextType::MEDIA);
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  SyncOnMainLoop();

  // Verify cache has been rebuilt
  ASSERT_NE(nullptr, group->GetCachedConfiguration(types::LeAudioContextType::MEDIA));
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::MEDIA)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Expect two iso channel to be fed with data
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  /* Update metadata, and do not expect new context type*/
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);

  /* Simulate metadata update */
  UpdateLocalSourceMetadata(tracks);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, TwoEarbuds2ndDisconnected) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();
  ASSERT_EQ(2, group->NumOfConnected());

  // Expect two iso channels to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Disconnect one device and expect the group to keep on streaming
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  auto device = group->GetFirstDevice();
  for (auto& ase : device->ases_) {
    InjectCisDisconnected(group_id, ase.cis_conn_hdl);
  }

  /* Disconnect ACL and do not reconnect. */
  ON_CALL(mock_gatt_interface_, Open(_, device->address_, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());
  EXPECT_CALL(mock_gatt_interface_, Open(_, device->address_, BTM_BLE_DIRECT_CONNECTION, false))
          .Times(1);

  // Record NumOfConnected when groupStateMachine_ gets notified about the
  // disconnection
  int num_of_connected = 0;
  ON_CALL(mock_state_machine_, ProcessHciNotifAclDisconnected(_, _))
          .WillByDefault(
                  [&num_of_connected](LeAudioDeviceGroup* group, LeAudioDevice* /*leAudioDevice*/) {
                    num_of_connected = group->NumOfConnected();
                  });

  auto conn_id = device->conn_id_;
  InjectDisconnectedEvent(device->conn_id_, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  // Make sure the state machine knows about the disconnected device
  ASSERT_EQ(1, num_of_connected);

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Expect one channel ISO Data to be sent
  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  InjectConnectedEvent(device->address_, conn_id);
  SyncOnMainLoop();

  // Expect two iso channels to be fed with data
  cis_count_out = 2;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, TwoEarbudsStreamingProfileDisconnect) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channels to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);

  /* Do not inject OPEN_EVENT by default */
  ON_CALL(mock_gatt_interface_, Open(_, _, _, _)).WillByDefault(DoAll(Return()));
  ON_CALL(mock_gatt_interface_, Close(_)).WillByDefault(DoAll(Return()));
  ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).WillByDefault(DoAll(Return()));

  DisconnectLeAudioNoDisconnectedEvtExpected(test_address0, 1);
  DisconnectLeAudioNoDisconnectedEvtExpected(test_address1, 2);

  EXPECT_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(2);

  InjectDisconnectedEvent(1);
  InjectDisconnectedEvent(2);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, TwoEarbudsStreamingProfileDisconnectStreamStopTimeout) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Expect two iso channels to be fed with data
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Expect StopStream to be called before Close or ACL Disconnect is called.
  ON_CALL(mock_state_machine_, StopStream(_)).WillByDefault([](LeAudioDeviceGroup* group) {
    /* Stub the process of stopping stream, just set the target state.
     * this simulates issue with stopping the stream
     */
    group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
  });

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(2);
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(0);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).Times(0);

  do_in_main_thread(base::Bind(&LeAudioClient::Disconnect, base::Unretained(LeAudioClient::Get()),
                               test_address0));
  do_in_main_thread(base::Bind(&LeAudioClient::Disconnect, base::Unretained(LeAudioClient::Get()),
                               test_address1));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  /* Now stream is trying to be stopped and devices are about to be
   * disconnected. Simulate stop stream failure and timeout fired. Make sure
   * code will not try to do recovery connect
   */
  ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).WillByDefault(DoAll(Return()));
  EXPECT_CALL(mock_gatt_interface_, Close(_)).Times(0);
  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).Times(2);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);
  ASSERT_TRUE(group != nullptr);
  ASSERT_GT(group->NumOfConnected(), 0);

  state_machine_callbacks_->OnStateTransitionTimeout(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  auto device = group->GetFirstDevice();
  ASSERT_TRUE(device != nullptr);
  ASSERT_NE(device->GetConnectionState(), DeviceConnectState::DISCONNECTING_AND_RECOVER);
  device = group->GetNextDevice(device);
  ASSERT_TRUE(device != nullptr);
  ASSERT_NE(device->GetConnectionState(), DeviceConnectState::DISCONNECTING_AND_RECOVER);
}

TEST_F(UnicastTest, TwoEarbudsStreamingProfileDisconnectForSingleEarbudStreamStopTimeout) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Use StartStream to generate situation when target state is IDLE, one device is streaming and
   * other one reached IDLE state.
   */
  ON_CALL(mock_state_machine_, StartStream(_, _, _, _))
          .WillByDefault([this](LeAudioDeviceGroup* group,
                                types::LeAudioContextType /* context_type */,
                                types::BidirectionalPair<
                                        types::AudioContexts> /* metadata_context_types */,
                                types::BidirectionalPair<std::vector<uint8_t>> /* ccid_lists */) {
            group->SetTargetState(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
            auto group_state = group->GetState();
            LeAudioDevice* device = group->GetFirstDevice();
            for (auto& ase : device->ases_) {
              ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING;
              ase.active = true;
            }
            device = group->GetNextDevice(device);
            for (auto& ase : device->ases_) {
              ase.state = types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE;
              ase.active = false;
            }

            state_machine_callbacks_->OnStateTransitionTimeout(group->group_id_);

            return true;
          });

  // Do not accept direct connect, but expect it to arrive.
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _)).WillByDefault(Return());

  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _)).Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, GroupStatus::INACTIVE))
          .Times(0);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID, false,
                 false);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, EarbudsWithStereoSinkMonoSourceSupporting32kHz) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = 0;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0024,
                                /* source sample freq 32/16khz */ true, /*add_csis*/
                                true,                                   /*add_cas*/
                                true,                                   /*add_pacs*/
                                default_ase_cnt /*add_ascs_cnt*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);

  // LeAudioCodecConfiguration received_af_sink_config;
  const LeAudioCodecConfiguration expected_af_sink_config = {
          .num_channels = 2,
          .sample_rate = bluetooth::audio::le_audio::kSampleRate32000,
          .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
          .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(expected_af_sink_config, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, TwoEarbudsWithSourceSupporting32kHz) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = 0;
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024,
                                /* source sample freq 32/16khz */ true, /*add_csis*/
                                true,                                   /*add_cas*/
                                true,                                   /*add_pacs*/
                                default_ase_cnt /*add_ascs_cnt*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  ConnectLeAudio(test_address0);

  // LeAudioCodecConfiguration received_af_sink_config;
  const LeAudioCodecConfiguration expected_af_sink_config = {
          .num_channels = 2,
          .sample_rate = bluetooth::audio::le_audio::kSampleRate32000,
          .bits_per_sample = bluetooth::audio::le_audio::kBitsPerSample16,
          .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(expected_af_sink_config, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, MicrophoneAttachToCurrentMediaScenario) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // When the local audio source resumes we have no knowledge of recording
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, _, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_INVALID);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on one audio source cis
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // When the local audio sink resumes we should reconfigure
  EXPECT_CALL(mock_state_machine_,
              ConfigureStream(_, bluetooth::le_audio::types::LeAudioContextType::LIVE, _, _, _))
          .Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(1);

  // Update metadata on local audio sink
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);

  // Resume on local audio sink
  ASSERT_NE(unicast_sink_hal_cb_, nullptr);
  do_in_main_thread(
          base::BindOnce([](LeAudioSinkAudioHalClient::Callbacks* cb) { cb->OnAudioResume(); },
                         unicast_sink_hal_cb_));

  /* The above will trigger reconfiguration. After that Audio Hal action
   * is needed to restart the stream */
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  LocalAudioSourceResume();
  do_in_main_thread(
          base::BindOnce([](LeAudioSinkAudioHalClient::Callbacks* cb) { cb->OnAudioResume(); },
                         unicast_sink_hal_cb_));
  SyncOnMainLoop();

  auto group = streaming_groups.at(group_id);
  group->PrintDebugState();

  // Verify Data transfer on one audio source and sink cis
  cis_count_out = 1;
  cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 60);

  // Stop
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, SwitchBetweenMicrophoneAndSoundEffectScenario) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /* Scenario:
   * 1. User starts Recording - this creates bidiretional CISes
   * 2. User stops recording  - this starts suspend timeout (500ms)
   * 3. Since user touch the screen it generates touch tone - this shall reuse existing CISes when
   * it happens 500ms before stop
   */
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // When the local audio source resumes we have no knowledge of recording
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::LIVE, _, _))
          .Times(1);

  log::info("Start Microphone recording - bidirectional CISes are expected");
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  ASSERT_EQ(0, get_func_call_count("alarm_set_on_mloop"));
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source cis
  uint8_t cis_count_out = 0;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 60);

  log::info("Suspend microphone recording - suspend timeout is not fired");
  LocalAudioSinkSuspend();
  SyncOnMainLoop();

  log::info("Expect VBC and Suspend timeouts to be started");
  ASSERT_EQ(2, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));

  log::info("Resume local source with touch tone - expect suspend timeout to be canceled");

  UpdateLocalSourceMetadata(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION);
  LocalAudioSourceResume();
  SyncOnMainLoop();

  log::info("Expect VBC and Suspend timeouts to be started");
  ASSERT_EQ(2, get_func_call_count("alarm_set_on_mloop"));

  auto group = streaming_groups.at(group_id);
  group->PrintDebugState();

  // Verify Data transfer on one audio source and sink cis
  cis_count_out = 1;
  cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 60);
}

TEST_F(UnicastTest, SwitchBetweenSoundEffectAndMicrophoneScenario) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /* Scenario:
   * 1. User starts Recording which first triggers SoundEffect
   * 2. Just after that LIVE metadata arrives and this creates bidiretional CISes
   */
  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // When the local audio source resumes we have no knowledge of recording
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::SOUNDEFFECTS, _, _))
          .Times(1);

  StartStreaming(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION, group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  auto group = streaming_groups.at(group_id);
  group->PrintDebugState();

  log::info("Start Microphone recording - reconfiguration is expected");
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, CancelStreamingRequest()).Times(1);

  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  group->PrintDebugState();

  log::info("Resume after reconfiguration - bidirectional CISes are expected");
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  // Verify Data transfer on one audio source cis
  uint8_t cis_count_out = 0;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 60);

  ASSERT_EQ(1, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_EQ(0, get_func_call_count("alarm_cancel"));
}

/* When a certain context is unavailable and not supported we should stream
 * as UNSPECIFIED for the backwards compatibility.
 * Since UNSPECIFIED is available, put the UNSPECIFIED into the metadata instead
 * What we can do now is to keep streaming (and reconfigure if needed for the
 * use case).
 */
TEST_F(UnicastTest, UpdateNotSupportedContextTypeUnspecifiedAvailable) {
  // TODO(b/352686917). Remove the test when flag will be removing
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(false);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();
  supported_snk_context_types_ = available_snk_context_types_;
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ = available_src_context_types_;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  LeAudioClient::Get()->SetInCall(true);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  LeAudioClient::Get()->SetInCall(false);
  LocalAudioSinkSuspend();
  UpdateLocalSinkMetadata(std::nullopt);

  /* We should use GAME configuration, but do not send the GAME context type, as
   * it is not available on the remote device.
   */
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::UNSPECIFIED),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::GAME, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN, false);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, UpdateNotSupportedContextTypeUnspecifiedAvailable_SpeedUpReconfigFlagEnabled) {
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(true);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();
  supported_snk_context_types_ = available_snk_context_types_;
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ = available_src_context_types_;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  LeAudioClient::Get()->SetInCall(true);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  LeAudioClient::Get()->SetInCall(false);
  LocalAudioSinkSuspend();

  /* We should use GAME configuration, but do not send the GAME context type, as
   * it is not available on the remote device.
   */
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::UNSPECIFIED),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::GAME, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN, false);
  SyncOnMainLoop();
}

/* Some bidirectional scenarios are triggered by the local sink, local source
 * metadata or the In Call preference callback call. Since each call invalidates
 * previous context source, make sure that getting all of these in a sequence,
 * always results with one bidirectional context, so that the remote device
 * is not confused about our intentions.
 */
TEST_F(UnicastTest, UpdateMultipleBidirContextTypes) {
  // TODO(b/352686917). Remove the test when flag will be removing
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(false);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ = (types::LeAudioContextType::CONVERSATIONAL |
                                  types::LeAudioContextType::GAME | types::LeAudioContextType::LIVE)
                                         .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // When the local audio sink resumes expect only LIVE context
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::LIVE),
          .source = types::AudioContexts(types::LeAudioContextType::LIVE)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::LIVE, contexts, _))
          .Times(1);

  // 1) Start the recording. Sink resume will trigger the reconfiguration
  // ---------------------------------------------------------------------
  ASSERT_NE(nullptr, unicast_sink_hal_cb_);
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  /* After the reconfiguration the local Audio Sink HAL has to resume again */
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source and sink cis
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // Stop
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // 2) Now set in call preference to get CONVERSATIONAL into the mix
  // -----------------------------------------------------------------
  LeAudioClient::Get()->SetInCall(true);

  // Verify that we only got CONVERSATIONAL context and no LIVE
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);

  // Start with ringtone on local source
  ASSERT_NE(nullptr, unicast_sink_hal_cb_);
  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  // Resume both directions
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source cis
  cis_count_out = 1;
  cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // 3) Disable call so we could go to GAME
  // ---------------------------------------
  LeAudioClient::Get()->SetInCall(false);

  /* Start the game on local source - expect no previous sink (LIVE) metadata */
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::GAME),
              .source = types::AudioContexts(types::LeAudioContextType::GAME)};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::GAME, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN, false);

  /* If the above triggers reconfiguration, Audio Hal action is needed to
   * restart the stream.
   */
  LocalAudioSourceResume();
  LocalAudioSinkResume();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // 4) Stop streaming
  // ------------------
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));
  SyncOnMainLoop();
}

TEST_F(UnicastTest, UpdateMultipleBidirContextTypes_SpeedUpReconfigFlagEnabled) {
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(true);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ = (types::LeAudioContextType::CONVERSATIONAL |
                                  types::LeAudioContextType::GAME | types::LeAudioContextType::LIVE)
                                         .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // When the local audio sink resumes expect only LIVE context
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::LIVE),
          .source = types::AudioContexts(types::LeAudioContextType::LIVE)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::LIVE, contexts, _))
          .Times(1);

  log::info("Step 1 Start the recording. Sink resume will trigger the reconfiguration");
  // ---------------------------------------------------------------------
  ASSERT_NE(nullptr, unicast_sink_hal_cb_);
  UpdateLocalSinkMetadata(AUDIO_SOURCE_MIC);
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("After the reconfiguration the local Audio Sink HAL has to resume again");

  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info("Verify Data transfer on one audio source and sink cis");

  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  log::info("Step 2 Now set in call preference to get CONVERSATIONAL into the mix");
  // -----------------------------------------------------------------

  EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  LeAudioClient::Get()->SetInCall(true);
  SyncOnMainLoop();

  // Verify that we only got CONVERSATIONAL context and no LIVE
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);

  log::info("Start with ringtone on local source");
  ASSERT_NE(nullptr, unicast_sink_hal_cb_);
  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  // Resume both directions
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source cis
  cis_count_out = 1;
  cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  log::info("Step 3 Disable call so we could go to GAME");
  // ---------------------------------------
  EXPECT_CALL(*mock_le_audio_source_hal_client_, SuspendedForReconfiguration()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, CancelStreamingRequest()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ReconfigurationComplete()).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(0);
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);

  LeAudioClient::Get()->SetInCall(false);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  log::info("Start the game on local source - expect no previous sink (LIVE) metadata");

  /* Stream shall keep streaming */
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::GAME),
              .source = types::AudioContexts(types::LeAudioContextType::GAME)};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, contexts, _)).Times(1);

  UpdateLocalSourceMetadata(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_UNKNOWN, false);
  LocalAudioSourceResume();
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  cis_count_out = 1;
  cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  log::info(" Step 4 Stop streaming");
  // ------------------
  StopStreaming(group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));
  SyncOnMainLoop();
}

TEST_F(UnicastTest, UpdateDisableLocalAudioSinkOnGame) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ = (types::LeAudioContextType::CONVERSATIONAL |
                                  types::LeAudioContextType::GAME | types::LeAudioContextType::LIVE)
                                         .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  // Start GAME stream
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::GAME),
          .source = types::AudioContexts(types::LeAudioContextType::GAME)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::GAME, contexts, _))
          .Times(1);

  // 1) Start the recording. Sink resume will trigger the reconfiguration
  // ---------------------------------------------------------------------
  StartStreaming(AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_MUSIC, group_id, AUDIO_SOURCE_MIC);

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source and sink cis
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  SyncOnMainLoop();

  // 2) Now Lets suspend MIC and do not expect reconfiguration
  // -----------------------------------------------------------------

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  LocalAudioSinkSuspend();
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

/* Start music when in a call, end the call, continue with music only */
TEST_F(UnicastTest, MusicDuringCallContextTypes) {
  // TODO(b/352686917). Remove the test when flag will be removing
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(false);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::CONVERSATIONAL | types::LeAudioContextType::RINGTONE |
           types::LeAudioContextType::GAME | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::LIVE | types::LeAudioContextType::NOTIFICATIONS)
                  .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  available_src_context_types_ &=
          ~((types::LeAudioContextType::NOTIFICATIONS | types::LeAudioContextType::MEDIA).value());
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  log::info("TESTPOINT 1: Start with the call first");
  // -----------------------------
  // CONVERSATIONAL is from In Call preference, and RINGTONE is from metadata
  LeAudioClient::Get()->SetInCall(true);
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::RINGTONE |
                                       types::LeAudioContextType::CONVERSATIONAL),
          .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  LocalAudioSinkResume();

  // Verify
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  log::info("TESTPOINT 2: Start MEDIA during the call, expect MEDIA only on the remote sink");
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL |
                                           types::LeAudioContextType::MEDIA),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, false);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  log::info(
          "TESTPOINT 3: Disable In Call preference but do not suspend the local sink. Play "
          "notification on the same stream.");
  // Verify both context are sent as the metadata.
  // ---------------------------------------
  LeAudioClient::Get()->SetInCall(false);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::NOTIFICATIONS |
                                           types::LeAudioContextType::CONVERSATIONAL),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_NOTIFICATION, AUDIO_CONTENT_TYPE_UNKNOWN,
                            /*reconfigure=*/false);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  log::info("TESTPOINT 4: Disable call so we could go back to MEDIA");
  // ---------------------------------------
  // Suspend should stop the stream
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  UpdateLocalSinkMetadata(std::nullopt);
  LocalAudioSourceSuspend();
  LocalAudioSinkSuspend();
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Restart the stream with MEDIA
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
              .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC,
                            /*reconfigure=*/false);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  /* The source needs to resume to reconfigure to MEDIA */
  LocalAudioSourceResume(/*expect_confirm=*/false);
  LocalAudioSourceResume(/*expect_confirm=*/true);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  log::info("TESTPOINT 5: Stop streaming");
  // ------------------
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));
  SyncOnMainLoop();
}

TEST_F(UnicastTest, MetadataUpdateDuringReconfiguration) {
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  /* Scenario
   * 1. Start reconfiguration
   * 2. Send metadata update
   * 3. Make sure metadata updates are ignored
   */
  available_snk_context_types_ =
          (types::LeAudioContextType::CONVERSATIONAL | types::LeAudioContextType::RINGTONE |
           types::LeAudioContextType::GAME | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::LIVE | types::LeAudioContextType::NOTIFICATIONS)
                  .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::MEDIA, _, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  auto group = streaming_groups.at(group_id);

  stay_at_qos_config_in_start_stream = true;
  log::info("Reconfigure to conversational and stay in Codec Config");

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(1);

  LeAudioClient::Get()->SetInCall(true);
  SyncOnMainLoop();

  ASSERT_TRUE(group->GetState() == types::AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

  log::info("Expect not action on metadata change");

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  EXPECT_CALL(mock_state_machine_, ConfigureStream(_, _, _, _, _)).Times(0);

  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
}

TEST_F(UnicastTest, MusicDuringCallContextTypes_SpeedUpReconfigFlagEnabled) {
  com::android::bluetooth::flags::provider_->leaudio_speed_up_reconfiguration_between_call(true);

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::CONVERSATIONAL | types::LeAudioContextType::RINGTONE |
           types::LeAudioContextType::GAME | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::LIVE | types::LeAudioContextType::NOTIFICATIONS)
                  .value();
  supported_snk_context_types_ =
          available_snk_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();
  available_src_context_types_ = available_snk_context_types_;
  supported_src_context_types_ =
          available_src_context_types_ |
          types::AudioContexts(types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationAnyLeft,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0024, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  log::info("Step 1 Start with the call first");
  // -----------------------------
  // CONVERSATIONAL is from In Call preference, and RINGTONE is from metadata
  LeAudioClient::Get()->SetInCall(true);
  types::BidirectionalPair<types::AudioContexts> contexts = {
          .sink = types::AudioContexts(types::LeAudioContextType::RINGTONE |
                                       types::LeAudioContextType::CONVERSATIONAL),
          .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);
  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);
  LocalAudioSourceResume();
  LocalAudioSinkResume();

  // Verify
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 1;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  log::info("Step  2) Start MEDIA during the call, expect MEDIA only on the remote sink");

  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL |
                                           types::LeAudioContextType::MEDIA),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL,
                          contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, false);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::MEDIA |
                                           types::LeAudioContextType::CONVERSATIONAL),
              .source = types::AudioContexts(types::LeAudioContextType::CONVERSATIONAL)};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::CONVERSATIONAL, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC,
                            /*reconfigure=*/false);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  log::info("Step 3) Disable call so we could go back to MEDIA");
  // ---------------------------------------
  // Suspend should stop the stream
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(1);
  LocalAudioSourceSuspend();
  LocalAudioSinkSuspend();
  // simulate suspend timeout passed, alarm executing
  fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);

  LeAudioClient::Get()->SetInCall(false);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Restart the stream with MEDIA
  contexts = {.sink = types::AudioContexts(types::LeAudioContextType::MEDIA),
              .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::MEDIA, contexts, _))
          .Times(1);
  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC,
                            /*reconfigure=*/false);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  /* The source needs to resume to reconfigure to MEDIA */
  LocalAudioSourceResume(/*expect_confirm=*/false);
  LocalAudioSourceResume(/*expect_confirm=*/true);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  log::info("Step 3) Stop streaming");

  // ------------------
  StopStreaming(group_id);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Release
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));
  SyncOnMainLoop();
}

/* When a certain context is unavailable but supported we should not stream that
 * context - either stop the stream or eliminate this strim from the mix
 * This could be na IOP issue so continue streaming (and reconfigure if needed
 * for that use case).
 * Since the unavailable context is supported, do not put this context into
 * the metadata, and do not replace it with UNSPECIFIED.
 */
TEST_F(UnicastTest, StartNotAvailableSupportedContextType) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  // EMERGENCYALARM is not available, but supported
  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = types::kLeAudioContextAllTypes.value();
  supported_src_context_types_ =
          (types::kLeAudioContextAllRemoteSource | types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Expect configuring to (or staying with) the right configuration but the
  // metadata should not get the EMERGENCYALARM context, nor the UNSPECIFIED
  // Since the initial config is UNSPECIFIED, then even for sonification events
  // we should reconfigure to less generic EMERGENCYALARM scenario
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {.sink = types::AudioContexts(),
                                                             .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::EMERGENCYALARM, metadata, _))
          .Times(0);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_EMERGENCY, AUDIO_CONTENT_TYPE_UNKNOWN, group_id, AUDIO_SOURCE_INVALID,
                 false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

/* When a certain context is unavailable and not supported and the UNSPECIFIED
 * is not available we should stop the stream.
 * For now, stream will not be started in such a case.
 * In future we should be able to eliminate this context from the track mix.
 */
TEST_F(UnicastTest, StartNotAvailableUnsupportedContextTypeUnspecifiedUnavail) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  // EMERGENCYALARM is not available, nor supported
  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::MEDIA)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ =
          (available_snk_context_types_ | types::LeAudioContextType::UNSPECIFIED).value();
  supported_src_context_types_ =
          (available_src_context_types_ | types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Expect configuring to the default config since the EMERGENCYALARM is
  // not on the list of supported contexts and UNSPECIFIED should not be
  // in the metadata as it is unavailable.
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {.sink = types::AudioContexts(),
                                                             .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::EMERGENCYALARM, metadata, _))
          .Times(0);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_EMERGENCY, AUDIO_CONTENT_TYPE_UNKNOWN, group_id, AUDIO_SOURCE_INVALID,
                 false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

/* This test verifies if we use UNSPCIFIED context when another context is
 * unavailable and not supported but UNSPCIFIED is in available audio contexts.
 */
TEST_F(UnicastTest, StartNotAvailableUnsupportedContextTypeUnspecifiedAvail) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  // EMERGENCYALARM is not available, nor supported
  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = available_snk_context_types_;
  supported_src_context_types_ = available_src_context_types_;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Expect configuring to the default config since the EMERGENCYALARM is
  // not on the list of supported contexts and UNSPECIFIED will be used in
  // the metadata.
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {
          .sink = types::AudioContexts(types::LeAudioContextType::UNSPECIFIED),
          .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::EMERGENCYALARM, metadata, _))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_EMERGENCY, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on one audio source cis
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);
}

TEST_F(UnicastTest, NotifyAboutGroupTunrnedIdleEnabled) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  osi_property_set_bool(kNotifyUpperLayerAboutGroupBeingInIdleDuringCall, true);

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  LeAudioClient::Get()->SetInCall(true);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Release

  /* To be called twice
   * 1. GroupStatus::INACTIVE
   * 2. GroupStatus::TURNED_IDLE_DURING_CALL
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, _)).Times(2);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);

  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  LeAudioClient::Get()->SetInCall(false);
  osi_property_set_bool(kNotifyUpperLayerAboutGroupBeingInIdleDuringCall, false);
}

TEST_F(UnicastTest, NotifyAboutGroupTunrnedIdleDisabled) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  LeAudioClient::Get()->SetInCall(true);

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_UNKNOWN, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  // Release

  /* To be called once only
   * 1. GroupStatus::INACTIVE
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_, OnGroupStatus(group_id, _)).Times(1);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);

  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client) { client->GroupSetActive(bluetooth::groups::kGroupUnknown); },
          LeAudioClient::Get()));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  LeAudioClient::Get()->SetInCall(false);
}

TEST_F(UnicastTest, HandleDatabaseOutOfSync) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  /* Simulate DATABASE OUT OF SYNC */
  ccc_stored_byte_val_ = 0x01;
  gatt_read_ctp_ccc_status_ = GATT_DATABASE_OUT_OF_SYNC;

  EXPECT_CALL(mock_gatt_queue_, WriteDescriptor(_, _, _, _, _, _)).Times(0);
  ON_CALL(mock_gatt_interface_, ServiceSearchRequest(_, _)).WillByDefault(Return());
  EXPECT_CALL(mock_gatt_interface_, ServiceSearchRequest(_, _));

  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
}

TEST_F(UnicastTest, TestRemoteDeviceKeepCccValues) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);

  /* Simulate remote cache is good */
  ccc_stored_byte_val_ = 0x01;

  EXPECT_CALL(mock_gatt_queue_, WriteDescriptor(_, _, _, _, _, _)).Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, TestRemoteDeviceForgetsCccValues) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::DISCONNECTED, test_address0))
          .Times(1);
  InjectDisconnectedEvent(1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);

  /* Simulate remote cache is broken */
  ccc_stored_byte_val_ = 0;
  EXPECT_CALL(mock_gatt_queue_, WriteDescriptor(_, _, _, _, _, _)).Times(AtLeast(1));
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);

  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_queue_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, SpeakerStreamingTimeout) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SetSampleDatabaseEarbudsValid(
          1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
          codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt, default_channel_cnt, 0x0004,
          /* source sample freq 16khz */ false /*add_csis*/, true /*add_cas*/, true /*add_pacs*/,
          default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/, 0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  // Start streaming
  uint8_t cis_count_out = 1;
  uint8_t cis_count_in = 0;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on one audio source cis
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920);

  auto group = streaming_groups.at(group_id);
  auto device = group->GetFirstActiveDevice();

  // Do not accept direct connect, but expect it to arrive.
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _)).WillByDefault(Return());

  EXPECT_CALL(mock_btm_interface_, AclDisconnectFromHandle(device->conn_id_, _)).Times(1);
  ON_CALL(mock_btm_interface_, AclDisconnectFromHandle(_, _))
          .WillByDefault([](uint16_t handle, tHCI_STATUS /*rs*/) {
            ASSERT_NE(handle, GATT_INVALID_CONN_ID);
            // Do nothing here now.
          });

  state_machine_callbacks_->OnStateTransitionTimeout(group_id);
  SyncOnMainLoop();
  ASSERT_EQ(device->GetConnectionState(), DeviceConnectState::DISCONNECTING_AND_RECOVER);

  InjectDisconnectedEvent(device->conn_id_, GATT_CONN_TERMINATE_LOCAL_HOST);
  SyncOnMainLoop();

  /* No assigned cises should remain when transition remains in IDLE state */
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  ASSERT_EQ(0, static_cast<int>(group->cig.cises.size()));
  ASSERT_TRUE(device != nullptr);
  ASSERT_EQ(device->GetConnectionState(), DeviceConnectState::CONNECTING_AUTOCONNECT);
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
}

TEST_F(UnicastTest, AddMemberToAllowListWhenOneDeviceConnected) {
  uint8_t group_size = 2;
  int group_id = 2;
  int conn_id_dev_0 = 1;
  int conn_id_dev_1 = 2;

  /*Scenario to test
   * 1. Connect Device A and disconnect
   * 2. Connect Device B
   * 3. verify Device B is in the allow list with direct connect.
   */
  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);

  ConnectCsisDevice(test_address0, conn_id_dev_0, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  SyncOnMainLoop();

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  InjectDisconnectedEvent(conn_id_dev_0);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);

  /* Do not connect first  device but expect Open will arrive.*/
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);
  ON_CALL(mock_gatt_interface_, Open(_, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());

  ConnectCsisDevice(test_address1, conn_id_dev_1, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, ResetToDefaultReconnectionMode) {
  uint8_t group_size = 2;
  int group_id = 2;
  int conn_id_dev_0 = 1;
  int conn_id_dev_1 = 2;

  /*Scenario to test
   * 1. Connect Device A and disconnect
   * 2. Connect Device B
   * 3. verify Device B is in the allow list.
   * 4. Disconnect B device
   * 5, Verify A and B device are back in targeted announcement reconnection
   * mode
   */
  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);

  ConnectCsisDevice(test_address0, conn_id_dev_0, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  SyncOnMainLoop();

  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  InjectDisconnectedEvent(conn_id_dev_0);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);

  /* Verify first earbud will start doing direct connect first */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  ON_CALL(mock_gatt_interface_, Open(_, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .WillByDefault(Return());
  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);

  ConnectCsisDevice(test_address1, conn_id_dev_1, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);

  // Disconnect Device B, expect default reconnection mode for Device A.
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, false)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address1, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address1, false)).Times(1);

  InjectDisconnectedEvent(conn_id_dev_1, GATT_CONN_TERMINATE_PEER_USER);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
}

TEST_F(UnicastTest, DisconnectAclBeforeGettingReadResponses) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  const RawAddress test_address0 = GetTestAddress(0);
  const RawAddress test_address1 = GetTestAddress(1);

  /* Due to imitated problems with GATT read operations (status != GATT_SUCCESS)
   * a CONNECTED state should not be propagated together with audio location
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(0);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnSinkAudioLocationAvailable(test_address0,
                                           std::make_optional<std::bitset<32>>(
                                                   codec_spec_conf::kLeAudioLocationFrontLeft)))
          .Times(0);

  // First earbud initial connection
  SetSampleDatabaseEarbudsValid(1 /* conn_id */, test_address0,
                                codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004, /* source sample freq 16khz */
                                true,                        /*add_csis*/
                                true,                        /*add_cas*/
                                true,                        /*add_pacs*/
                                true,                        /*add_ascs*/
                                group_size, 1 /* rank */, GATT_INTERNAL_ERROR);
  groups[test_address0] = group_id;
  // by default indicate link as encrypted
  ON_CALL(mock_btm_interface_, BTM_IsEncrypted(test_address0, _))
          .WillByDefault(DoAll(Return(true)));

  EXPECT_CALL(mock_gatt_interface_, Open(gatt_if, test_address0, BTM_BLE_DIRECT_CONNECTION, _))
          .Times(1);
  /* When connected it will got to TA */
  EXPECT_CALL(mock_gatt_interface_, CancelOpen(gatt_if, test_address0, _)).Times(1);
  EXPECT_CALL(mock_gatt_interface_,
              Open(gatt_if, test_address0, BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS, _))
          .Times(1);

  do_in_main_thread(base::BindOnce(&LeAudioClient::Connect, base::Unretained(LeAudioClient::Get()),
                                   test_address0));

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_btm_interface_);
  Mock::VerifyAndClearExpectations(&mock_gatt_interface_);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  InjectGroupDeviceAdded(test_address0, group_id);

  // Second earbud initial connection
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnSinkAudioLocationAvailable(test_address1,
                                           std::make_optional<std::bitset<32>>(
                                                   codec_spec_conf::kLeAudioLocationFrontRight)))
          .Times(1);

  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  /* for Target announcements AutoConnect is always there, until
   * device is removed
   */
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, false)).Times(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, false)).Times(0);

  // Verify grouping information
  std::vector<RawAddress> devs = LeAudioClient::Get()->GetGroupDevices(group_id);
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address0), devs.end());
  ASSERT_NE(std::find(devs.begin(), devs.end(), test_address1), devs.end());

  /* Remove default action on the direct connect */
  ON_CALL(mock_gatt_interface_, Open(_, _, BTM_BLE_DIRECT_CONNECTION, _)).WillByDefault(Return());

  /* Initiate disconnection with timeout reason, the possible reason why GATT
   * read attribute operation may be not handled
   */
  InjectDisconnectedEvent(1, GATT_CONN_TIMEOUT);
  SyncOnMainLoop();

  /* After reconnection a sink audio location callback with connection state
   * should be propagated.
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnSinkAudioLocationAvailable(test_address0,
                                           std::make_optional<std::bitset<32>>(
                                                   codec_spec_conf::kLeAudioLocationFrontLeft)))
          .Times(1);

  /* Prepare valid GATT status responsing attributes */
  SetSampleDatabaseEarbudsValid(1 /* conn_id */, test_address0,
                                codec_spec_conf::kLeAudioLocationFrontLeft,
                                codec_spec_conf::kLeAudioLocationFrontLeft, default_channel_cnt,
                                default_channel_cnt, 0x0004, /* source sample freq 16khz */
                                true,                        /*add_csis*/
                                true,                        /*add_cas*/
                                true,                        /*add_pacs*/
                                true,                        /*add_ascs*/
                                group_size, 1 /* rank */);

  /* For background connect, test needs to Inject Connected Event */
  InjectConnectedEvent(test_address0, 1);
  SyncOnMainLoop();
}

TEST_F(UnicastTest, GroupStreamStatus) {
  int group_id = bluetooth::groups::kGroupUnknown;

  InSequence s;

  /* Check if all states are properly notified */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::IDLE);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::RELEASING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::RELEASING_AUTONOMOUS);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::SUSPENDING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::SUSPENDED);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::CONFIGURED_AUTONOMOUS);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::CONFIGURED_BY_USER);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::DESTROYED);

  /* Check if there are no resending of the same state */
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::RELEASING);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::SUSPENDING);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::SUSPENDED);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::CONFIGURED_AUTONOMOUS);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::CONFIGURED_BY_USER);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::IDLE);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
}

TEST_F(UnicastTest, GroupStreamStatusManyGroups) {
  uint8_t group_size = 2;
  int group_id_1 = 1;
  int group_id_2 = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(_)).WillByDefault(Return(group_size));

  // First group - First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_1,
                    1 /* rank*/);

  // First group - Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id_1,
                    2 /* rank*/, true /*connect_through_csis*/);

  // Second group - First earbud
  const RawAddress test_address2 = GetTestAddress(2);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address2, true)).Times(1);
  ConnectCsisDevice(test_address2, 3 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_2,
                    1 /* rank*/);

  // Second group - Second earbud
  const RawAddress test_address3 = GetTestAddress(3);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address3, true)).Times(1);
  ConnectCsisDevice(test_address3, 4 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id_2,
                    2 /* rank*/, true /*connect_through_csis*/);

  InSequence s;

  // Group 1 IDLE
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id_1, GroupStreamStatus::IDLE);

  // Group 2 IDLE
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_2, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id_2, GroupStreamStatus::IDLE);

  log::info("Group 1 active and start streaming");
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::STREAMING))
          .Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id_1);
  SyncOnMainLoop();
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id_1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  log::info("Group 2 is getting active");
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::IDLE))
          .Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id_2);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  log::info("Group 2 is starts streaming");
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_2, GroupStreamStatus::STREAMING))
          .Times(1);
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id_2);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTest, GroupStreamStatusManyGroups_GettingConfigWhileOtherGroupIsStreaming) {
  uint8_t group_size = 2;
  int group_id_1 = 1;
  int group_id_2 = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(_)).WillByDefault(Return(group_size));

  // First group - First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_1,
                    1 /* rank*/);

  // First group - Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id_1,
                    2 /* rank*/, true /*connect_through_csis*/);

  // Second group - First earbud
  const RawAddress test_address2 = GetTestAddress(2);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address2, true)).Times(1);
  ConnectCsisDevice(test_address2, 3 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id_2,
                    1 /* rank*/);

  // Second group - Second earbud
  const RawAddress test_address3 = GetTestAddress(3);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address3, true)).Times(1);
  ConnectCsisDevice(test_address3, 4 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id_2,
                    2 /* rank*/, true /*connect_through_csis*/);

  InSequence s;

  // Group 1 IDLE
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id_1, GroupStreamStatus::IDLE);

  // Group 2 IDLE
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_2, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id_2, GroupStreamStatus::IDLE);

  log::info("Group 1 active and start streaming");
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::STREAMING))
          .Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id_1);
  SyncOnMainLoop();
  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id_1);
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  log::info("Group 2 is getting active");
  stay_at_releasing_stop_stream = true;
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_1, GroupStreamStatus::IDLE))
          .Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id_2);
  SyncOnMainLoop();

  log::info("Group 2 is starts streaming");
  stay_at_qos_config_in_start_stream = true;
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id_2, GroupStreamStatus::STREAMING))
          .Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, ConfirmStreamingRequest()).Times(1);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, group_id_2, AUDIO_SOURCE_INVALID,
                 false, false);

  log::info("Group 1 going to IDLE");
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::IDLE);
          },
          group_id_1, base::Unretained(this->state_machine_callbacks_)));
  SyncOnMainLoop();
  log::info("Group 2 going to STREAMING");
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id_2, base::Unretained(this->state_machine_callbacks_)));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, GroupStreamStatusResendAfterRemove) {
  uint8_t group_size = 2;
  int group_id = 1;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(_)).WillByDefault(Return(group_size));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  Mock::VerifyAndClearExpectations(&mock_btif_storage_);

  InSequence s;

  // Activate group, start streaming and immediately stop
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::STREAMING))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // No resend
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(0);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::IDLE);

  // No resend after removing only one device
  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address0) {
            client->RemoveDevice(test_address0);
          },
          LeAudioClient::Get(), test_address0));
  SyncOnMainLoop();
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(0);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::IDLE);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Resend after removing last device
  /*
   * StopStream will put calls on main_loop so to keep the correct order
   * of operations and to avoid races we put the test command on main_loop as
   * well.
   */
  do_in_main_thread(base::BindOnce(
          [](LeAudioClient* client, const RawAddress& test_address1) {
            client->RemoveDevice(test_address1);
          },
          LeAudioClient::Get(), test_address1));
  SyncOnMainLoop();
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupStreamStatus(group_id, GroupStreamStatus::IDLE))
          .Times(1);
  state_machine_callbacks_->StatusReportCb(group_id, GroupStreamStatus::IDLE);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHandoverMode, SetSinkMonitorModeWhileUnicastIsActive) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, true /* enable */));

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // Stop streaming and expect Service to be informed about straming suspension
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Stop
  StopStreaming(group_id, true);

  if (com::android::bluetooth::flags::leaudio_use_audio_recording_listener()) {
    // simulate suspend timeout passed, alarm executing
    fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
    SyncOnMainLoop();
  } else {
    // Check if cache configuration is still present
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSink)
                        .size());
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSource)
                        .size());

    // Release, Sink HAL client should remain in monitor mode
    EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
    EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(0);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(0);
    LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
    SyncOnMainLoop();

    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

    // Re-initialize mock for destroyed hal client
    RegisterSourceHalClientMock();

    // Setting group inactive, shall not change cached configuration
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSink)
                        .size());
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSource)
                        .size());

    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                           UnicastMonitorModeStatus::STREAMING_REQUESTED))
            .Times(1);

    // Start streaming to trigger next group going to IDLE state
    LocalAudioSinkResume();

    EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
    LeAudioClient::Get()->GroupSetActive(group_id);
    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    SyncOnMainLoop();

    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

    StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

    // Stop streaming and expect Service to be informed about straming suspension
    EXPECT_CALL(mock_audio_hal_client_callbacks_,
                OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                           UnicastMonitorModeStatus::STREAMING_SUSPENDED))
            .Times(1);

    // Stop
    StopStreaming(group_id, true);

    // Release, Sink HAL client should remain in monitor mode
    EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
    EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(0);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(0);
    LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
    SyncOnMainLoop();

    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

    // De-activate monitoring mode
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
    do_in_main_thread(base::BindOnce(
            &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
            bluetooth::le_audio::types::kLeAudioDirectionSink, false /* enable */));
    SyncOnMainLoop();
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  }
}

TEST_F(UnicastTestHandoverMode, SetSinkMonitorModeWhileUnicastIsInactive) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, true /* enable */));

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Expect no streaming request on stream resume when group is already active
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                         UnicastMonitorModeStatus::STREAMING_REQUESTED))
          .Times(0);

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // Stop streaming and expect Service to be informed about straming suspension
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Stop
  StopStreaming(group_id, true);

  if (com::android::bluetooth::flags::leaudio_use_audio_recording_listener()) {
    // simulate suspend timeout passed, alarm executing
    fake_osi_alarm_set_on_mloop_.cb(fake_osi_alarm_set_on_mloop_.data);
    SyncOnMainLoop();
  } else {
    // Check if cache configuration is still present
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSink)
                        .size());
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSource)
                        .size());

    // Release, Sink HAL client should remain in monitor mode
    EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
    EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(0);
    EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(0);
    LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
    SyncOnMainLoop();

    Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
    Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
    Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

    // Setting group inactive, shall not change cached configuration
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSink)
                        .size());
    ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                        ->confs.get(le_audio::types::kLeAudioDirectionSource)
                        .size());
  }
}

TEST_F(UnicastTestHandoverMode, ClearSinkMonitorModeWhileUnicastIsActive) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, true /* enable */));

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return group_size; }));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Expect no streaming request on stream resume when group is already active
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSink,
                                         UnicastMonitorModeStatus::STREAMING_REQUESTED))
          .Times(0);

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // De-activate monitoring mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, false /* enable */));

  // Stop
  StopStreaming(group_id, true);
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  // Check if cache configuration is still present
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());

  // Release of sink and source hals due to de-activating monitor mode
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  // Setting group inactive, shall not change cached configuration
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());
}

TEST_F(UnicastTestHandoverMode, SetAndClearSinkMonitorModeWhileUnicastIsInactive) {
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(0);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(0);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(0);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(0);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(0);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, true /* enable */));
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, false /* enable */));

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
}

TEST_F(UnicastTestHandoverMode, SetSourceMonitorModeWhileUnicastIsInactive) {
  /* Enabling monitor mode for source while group is not active should result in
   * sending STREAMING_SUSPENDED notification.
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHandoverMode, SetTwiceSourceMonitorModeWhileUnicastIsInactive) {
  /* Enabling monitor mode for source while group is not active should result in
   * sending STREAMING_SUSPENDED notification.
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  // Imitate second activation of monitor mode - should not be notified
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHandoverMode, SetSourceMonitorModeWhileUnicastIsNotStreaming) {
  int group_id = 2;

  LeAudioClient::Get()->GroupSetActive(group_id);

  /* Enabling monitor mode for source while group is not active should result in
   * sending STREAMING_SUSPENDED notification.
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
}

TEST_F(UnicastTestHandoverMode, SetSourceMonitorModeWhileUnicastIsActive) {
  uint8_t group_size = 2;
  int group_id = 2;

  // Report working CSIS
  ON_CALL(mock_csis_client_module_, IsCsisClientRunning()).WillByDefault(Return(true));

  // First earbud
  const RawAddress test_address0 = GetTestAddress(0);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address0, true)).Times(1);
  ConnectCsisDevice(test_address0, 1 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontLeft,
                    codec_spec_conf::kLeAudioLocationFrontLeft, group_size, group_id, 1 /* rank*/);

  // Second earbud
  const RawAddress test_address1 = GetTestAddress(1);
  EXPECT_CALL(mock_btif_storage_, AddLeaudioAutoconnect(test_address1, true)).Times(1);
  ConnectCsisDevice(test_address1, 2 /*conn_id*/, codec_spec_conf::kLeAudioLocationFrontRight,
                    codec_spec_conf::kLeAudioLocationFrontRight, group_size, group_id, 2 /* rank*/,
                    true /*connect_through_csis*/);

  ON_CALL(mock_csis_client_module_, GetDesiredSize(group_id))
          .WillByDefault(Invoke([&](int /*group_id*/) { return 2; }));

  // Start streaming
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
  SyncOnMainLoop();

  // Verify Data transfer on two peer sinks and one source
  uint8_t cis_count_out = 2;
  uint8_t cis_count_in = 2;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in, 1920, 40);

  /* Enabling monitor mode for source while stream is active should result in
   * sending STREAMING notification.
   */
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING))
          .Times(1);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);

  ASSERT_NE(0lu, streaming_groups.count(group_id));
  auto group = streaming_groups.at(group_id);

  // Stop streaming and expect Service to be informed about straming suspension
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Stop
  StopStreaming(group_id, true);

  // Check if cache configuration is still present
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());

  // Both Sink and Source HAL clients should be stopped
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  // Re-initialize mock for destroyed hal client
  RegisterSourceHalClientMock();
  RegisterSinkHalClientMock();

  // Setting group inactive, shall not change cached configuration
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSink)
                      .size());
  ASSERT_TRUE(group->GetCachedConfiguration(types::LeAudioContextType::CONVERSATIONAL)
                      ->confs.get(le_audio::types::kLeAudioDirectionSource)
                      .size());

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_REQUESTED))
          .Times(1);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->GroupSetActive(group_id);

  // Start streaming to trigger next group going to IDLE state
  StartStreaming(AUDIO_USAGE_VOICE_COMMUNICATION, AUDIO_CONTENT_TYPE_SPEECH, group_id);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  // Stop streaming and expect Service to be informed about straming suspension
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(1);

  // Stop
  StopStreaming(group_id, true);

  // Both Sink and Source HAL clients should be stopped
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_source_hal_client_, OnDestroyed()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Stop()).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed()).Times(1);
  LeAudioClient::Get()->GroupSetActive(bluetooth::groups::kGroupUnknown);
  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);

  // De-activate monitoring mode
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(bluetooth::le_audio::types::kLeAudioDirectionSource,
                                         UnicastMonitorModeStatus::STREAMING_SUSPENDED))
          .Times(0);

  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSink, false /* enable */));
}

TEST_F(UnicastTestHandoverMode, SetAllowedContextMask) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::SOUNDEFFECTS)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = types::kLeAudioContextAllTypes.value();
  supported_src_context_types_ =
          (types::kLeAudioContextAllRemoteSource | types::LeAudioContextType::UNSPECIFIED).value();
  /* Don't allow SOUNDEFFECTS context type to be streamed */
  int allowed_context_types =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::SOUNDEFFECTS, _, _))
          .Times(0);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Set the same allowed context mask for sink and source */
  LeAudioClient::Get()->SetGroupAllowedContextMask(group_id, allowed_context_types,
                                                   allowed_context_types);

  StartStreaming(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_UNKNOWN, group_id,
                 AUDIO_SOURCE_INVALID, false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, NoContextvalidateStreamingRequest) {
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = types::kLeAudioContextAllTypes.value();
  supported_src_context_types_ =
          (types::kLeAudioContextAllRemoteSource | types::LeAudioContextType::UNSPECIFIED).value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {.sink = types::AudioContexts(),
                                                             .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_,
              StartStream(_, types::LeAudioContextType::SOUNDEFFECTS, metadata, _))
          .Times(0);

  LeAudioClient::Get()->GroupSetActive(group_id);

  // Imitate activation of monitor mode
  do_in_main_thread(base::BindOnce(
          &LeAudioClient::SetUnicastMonitorMode, base::Unretained(LeAudioClient::Get()),
          bluetooth::le_audio::types::kLeAudioDirectionSource, true /* enable */));
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  SyncOnMainLoop();

  // Stop streaming and expect Service to be informed about streaming suspension
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnUnicastMonitorModeStatus(
                      bluetooth::le_audio::types::kLeAudioDirectionSource,
                      UnicastMonitorModeStatus::STREAMING_REQUESTED_NO_CONTEXT_VALIDATE))
          .Times(1);

  StartStreaming(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_UNKNOWN, group_id,
                 AUDIO_SOURCE_INVALID, false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
}

TEST_F(UnicastTest, CodecFrameBlocks2) {
  auto const max_codec_frames_per_sdu = 2;
  uint32_t data_len = 1920;

  // Register a on-the-fly hook for codec interface mock mutation to prepare the
  // codec mock for encoding
  std::list<MockCodecInterface*> codec_mocks;
  MockCodecInterface::RegisterMockInstanceHook([&](MockCodecInterface* mock, bool is_destroyed) {
    if (is_destroyed) {
      log::debug("Codec Interface Destroyed: {}", std::format_ptr(mock));
      codec_mocks.remove(mock);
    } else {
      log::debug("Codec Interface Created: {}", std::format_ptr(mock));
      ON_CALL(*mock, GetNumOfSamplesPerChannel()).WillByDefault(Return(960));
      ON_CALL(*mock, GetNumOfBytesPerSample()).WillByDefault(Return(2));  // 16bits samples
      ON_CALL(*mock, Encode(_, _, _, _, _))
              .WillByDefault(Return(CodecInterface::Status::STATUS_OK));
      codec_mocks.push_back(mock);
    }
  });

  // Add a frame block PAC passing provider
  bool is_fb2_passed_as_requirement = false;
  ON_CALL(*mock_codec_manager_, GetCodecConfig)
          .WillByDefault(Invoke(
                  [&](const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements&
                              requirements,
                      bluetooth::le_audio::CodecManager::UnicastConfigurationProvider provider) {
                    auto filtered = *bluetooth::le_audio::AudioSetConfigurationProvider::Get()
                                             ->GetConfigurations(requirements.audio_context_type);
                    // Filter out the dual bidir SWB configurations
                    if (!bluetooth::le_audio::CodecManager::GetInstance()
                                 ->IsDualBiDirSwbSupported()) {
                      filtered.erase(
                              std::remove_if(filtered.begin(), filtered.end(),
                                             [](auto const& el) {
                                               if (el->confs.source.empty()) {
                                                 return false;
                                               }
                                               return AudioSetConfigurationProvider::Get()
                                                       ->CheckConfigurationIsDualBiDirSwb(*el);
                                             }),
                              filtered.end());
                    }
                    auto cfg = provider(requirements, &filtered);
                    if (cfg == nullptr) {
                      return std::unique_ptr<bluetooth::le_audio::types::AudioSetConfiguration>(
                              nullptr);
                    }

                    if (requirements.sink_pacs.has_value()) {
                      for (auto const& rec : requirements.sink_pacs.value()) {
                        auto caps = rec.codec_spec_caps.GetAsCoreCodecCapabilities();
                        if (caps.HasSupportedMaxCodecFramesPerSdu()) {
                          if (caps.supported_max_codec_frames_per_sdu.value() ==
                              max_codec_frames_per_sdu) {
                            // Inject the proper Codec Frames Per SDU as the json
                            // configs are conservative and will always give us 1
                            for (auto& entry : cfg->confs.sink) {
                              entry.codec.params.Add(
                                      codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
                                      (uint8_t)max_codec_frames_per_sdu);
                            }
                            is_fb2_passed_as_requirement = true;
                          }
                        }
                      }
                    }
                    return cfg;
                  }));

  types::BidirectionalPair<stream_parameters> codec_manager_stream_params;
  ON_CALL(*mock_codec_manager_, UpdateActiveAudioConfig)
          .WillByDefault(
                  Invoke([&](const types::BidirectionalPair<stream_parameters>& stream_params,
                             std::function<void(const stream_config& config, uint8_t direction)>
                             /*updater*/,
                             uint8_t /*directions_to_update*/) {
                    codec_manager_stream_params = stream_params;
                  }));

  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  SampleDatabaseParameters remote_params{
          .conn_id = 1,
          .addr = test_address0,
          .sink_audio_allocation = codec_spec_conf::kLeAudioLocationStereo,
          .source_audio_allocation = codec_spec_conf::kLeAudioLocationStereo,
          .sink_channel_cnt = default_channel_cnt,
          .source_channel_cnt = default_channel_cnt,
          .sample_freq_mask = le_audio::codec_spec_caps::kLeAudioSamplingFreq32000Hz,
          .add_csis = false,
          .add_cas = true,
          .add_pacs = true,
          .add_ascs_cnt = 1,
          .set_size = 0,
          .rank = 0,
          .gatt_status = GATT_SUCCESS,
          .max_supported_codec_frames_per_sdu = 2,
  };
  SetSampleDatabaseEarbudsValid(remote_params);

  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  constexpr int gmcs_ccid = 1;
  constexpr int gtbs_ccid = 2;

  // Audio sessions are started only when device gets active
  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, Start(_, _, _)).Times(1);
  LeAudioClient::Get()->SetCcidInformation(gmcs_ccid, 4 /* Media */);
  LeAudioClient::Get()->SetCcidInformation(gtbs_ccid, 2 /* Phone */);
  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  types::BidirectionalPair<std::vector<uint8_t>> ccids = {.sink = {gmcs_ccid}, .source = {}};
  EXPECT_CALL(mock_state_machine_, StartStream(_, _, _, ccids)).Times(1);

  stay_at_qos_config_in_start_stream = true;

  UpdateLocalSourceMetadata(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC);
  LocalAudioSourceResume(false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  ASSERT_TRUE(is_fb2_passed_as_requirement);

  // Verify codec fram blocks per SDU has been applied to the device
  ASSERT_NE(0lu, streaming_groups.count(group_id));
  uint8_t device_configured_codec_frame_blocks_per_sdu = 0;
  auto group = streaming_groups.at(group_id);
  for (LeAudioDevice* device = group->GetFirstDevice(); device != nullptr;
       device = group->GetNextDevice(device)) {
    for (auto& ase : device->ases_) {
      if (ase.active) {
        auto cfg = ase.codec_config.params.GetAsCoreCodecConfig();
        ASSERT_TRUE(cfg.codec_frames_blocks_per_sdu.has_value());
        device_configured_codec_frame_blocks_per_sdu = cfg.codec_frames_blocks_per_sdu.value();
      }
    }
  }

  // Verify the configured codec frame blocks per SDU
  ASSERT_EQ(device_configured_codec_frame_blocks_per_sdu,
            remote_params.max_supported_codec_frames_per_sdu);

  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);
  do_in_main_thread(base::BindOnce(
          [](int group_id,
             bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
            state_machine_callbacks->StatusReportCb(group_id, GroupStreamStatus::STREAMING);
          },
          group_id, base::Unretained(state_machine_callbacks_)));
  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  // Verify Data transfer on one audio source cis
  constexpr uint8_t cis_count_out = 1;
  constexpr uint8_t cis_count_in = 0;
  TestAudioDataTransfer(group_id, cis_count_out, cis_count_in,
                        data_len * device_configured_codec_frame_blocks_per_sdu);

  ASSERT_NE(codec_mocks.size(), 0ul);

  // Verify that the initially started session was updated with the new params
  ASSERT_EQ(codec_manager_stream_params.sink.stream_config.codec_frames_blocks_per_sdu,
            max_codec_frames_per_sdu);
}

TEST_F(UnicastTestHandoverMode, UpdateMetadataToNotAllowedContexts) {
  com::android::bluetooth::flags::provider_->leaudio_stop_updated_to_not_available_context_stream(
          true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::SOUNDEFFECTS)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = types::kLeAudioContextAllTypes.value();
  supported_src_context_types_ =
          (types::kLeAudioContextAllRemoteSource | types::LeAudioContextType::UNSPECIFIED).value();
  /* Don't allow SOUNDEFFECTS context type to be streamed */
  int allowed_context_types =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {.sink = types::AudioContexts(),
                                                             .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::MEDIA, _, _)).Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Set the same allowed context mask for sink and source */
  LeAudioClient::Get()->SetGroupAllowedContextMask(group_id, allowed_context_types,
                                                   allowed_context_types);

  StartStreaming(AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_UNKNOWN, group_id, AUDIO_SOURCE_INVALID,
                 false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  /* Expect stream to be stopped when not allowed context would be updated in metadata */
  EXPECT_CALL(mock_state_machine_, StopStream(_));

  UpdateLocalSourceMetadata(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_UNKNOWN, true);
}

TEST_F(UnicastTestHandoverMode, UpdateMetadataToNotAllowedContextsInCallMode) {
  com::android::bluetooth::flags::provider_->leaudio_stop_updated_to_not_available_context_stream(
          true);
  const RawAddress test_address0 = GetTestAddress(0);
  int group_id = bluetooth::groups::kGroupUnknown;

  available_snk_context_types_ =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA |
           types::LeAudioContextType::SOUNDEFFECTS)
                  .value();
  available_src_context_types_ = available_snk_context_types_;
  supported_snk_context_types_ = types::kLeAudioContextAllTypes.value();
  supported_src_context_types_ =
          (types::kLeAudioContextAllRemoteSource | types::LeAudioContextType::UNSPECIFIED).value();

  /* Don't allow SOUNDEFFECTS context type to be streamed */
  int allowed_context_types =
          (types::LeAudioContextType::RINGTONE | types::LeAudioContextType::CONVERSATIONAL |
           types::LeAudioContextType::UNSPECIFIED | types::LeAudioContextType::MEDIA)
                  .value();

  SetSampleDatabaseEarbudsValid(1, test_address0, codec_spec_conf::kLeAudioLocationStereo,
                                codec_spec_conf::kLeAudioLocationStereo, default_channel_cnt,
                                default_channel_cnt, 0x0004, false /*add_csis*/, true /*add_cas*/,
                                true /*add_pacs*/, default_ase_cnt /*add_ascs_cnt*/, 1 /*set_size*/,
                                0 /*rank*/);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnConnectionState(ConnectionState::CONNECTED, test_address0))
          .Times(1);
  EXPECT_CALL(mock_audio_hal_client_callbacks_,
              OnGroupNodeStatus(test_address0, _, GroupNodeStatus::ADDED))
          .WillOnce(DoAll(SaveArg<1>(&group_id)));

  ConnectLeAudio(test_address0);
  ASSERT_NE(group_id, bluetooth::groups::kGroupUnknown);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, Start(_, _, _)).Times(1);
  types::BidirectionalPair<types::AudioContexts> metadata = {.sink = types::AudioContexts(),
                                                             .source = types::AudioContexts()};
  EXPECT_CALL(mock_state_machine_, StartStream(_, types::LeAudioContextType::CONVERSATIONAL, _, _))
          .Times(1);

  LeAudioClient::Get()->GroupSetActive(group_id);
  SyncOnMainLoop();

  /* Set the same allowed context mask for sink and source */
  LeAudioClient::Get()->SetGroupAllowedContextMask(group_id, allowed_context_types,
                                                   allowed_context_types);

  StartStreaming(AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE, AUDIO_CONTENT_TYPE_SONIFICATION,
                 group_id, AUDIO_SOURCE_INVALID, false, false);

  SyncOnMainLoop();
  Mock::VerifyAndClearExpectations(&mock_audio_hal_client_callbacks_);
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  /* Expect stream to not be stopped when not allowed context would be updated in metadata and
   * inCall mode is set */
  LeAudioClient::Get()->SetInCall(true);
  EXPECT_CALL(mock_state_machine_, StopStream(_)).Times(0);

  /* Expect no reconfiguration while being in call mode */
  UpdateLocalSourceMetadata(AUDIO_USAGE_ASSISTANCE_SONIFICATION, AUDIO_CONTENT_TYPE_SONIFICATION,
                            false);

  SyncOnMainLoop();

  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
  Mock::VerifyAndClearExpectations(&mock_state_machine_);

  /* Expect stream to be stopped when not allowed context is set and inCall mode is not set */
  EXPECT_CALL(mock_state_machine_, StopStream(_));
  LeAudioClient::Get()->SetInCall(false);
}
}  // namespace bluetooth::le_audio
