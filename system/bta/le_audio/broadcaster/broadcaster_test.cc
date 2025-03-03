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
#include <hardware/audio.h>

#include <chrono>

#include "bta/include/bta_le_audio_api.h"
#include "bta/include/bta_le_audio_broadcaster_api.h"
#include "bta/le_audio/audio_hal_client/audio_hal_client.h"
#include "bta/le_audio/broadcaster/broadcast_configuration_provider.h"
#include "bta/le_audio/broadcaster/mock_state_machine.h"
#include "bta/le_audio/content_control_id_keeper.h"
#include "bta/le_audio/le_audio_types.h"
#include "bta/le_audio/mock_codec_manager.h"
#include "btif/include/btif_common.h"
#include "hci/controller_interface_mock.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/main_thread.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"
#include "test/mock/mock_osi_alarm.h"
#include "test/mock/mock_stack_btm_iso.h"

#define TEST_BT com::android::bluetooth::flags

using namespace std::chrono_literals;

using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::LeAudioContextType;

using testing::_;
using testing::AtLeast;
using testing::DoAll;
using testing::Invoke;
using testing::Matcher;
using testing::Mock;
using testing::NotNull;
using testing::Return;
using testing::ReturnRef;
using testing::SaveArg;
using testing::Test;

using namespace bluetooth::le_audio;
using namespace bluetooth;

using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::LeAudioCodecConfiguration;
using bluetooth::le_audio::LeAudioSourceAudioHalClient;
using bluetooth::le_audio::broadcaster::BigConfig;
using bluetooth::le_audio::broadcaster::BroadcastStateMachine;
using bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig;

// Disables most likely false-positives from base::SplitString()
extern "C" const char* __asan_default_options();
extern "C" const char* __asan_default_options() { return "detect_container_overflow=0"; }

struct alarm_t {
  alarm_callback_t cb;
  void* data;

  alarm_t(const char* /* name */) {
    cb = nullptr;
    data = nullptr;
  }
};

static base::Callback<void(BT_OCTET8)> generator_cb;

void btsnd_hcic_ble_rand(base::Callback<void(BT_OCTET8)> cb) { generator_cb = cb; }

std::atomic<int> num_async_tasks;
bluetooth::common::MessageLoopThread message_loop_thread("test message loop");
bluetooth::common::MessageLoopThread* get_main_thread() { return &message_loop_thread; }
void invoke_switch_buffer_size_cb(bool /*is_low_latency_buffer_size*/) {}

bt_status_t do_in_main_thread(base::OnceClosure task) {
  // Wrap the task with task counter so we could later know if there are
  // any callbacks scheduled and we should wait before performing some actions
  if (!message_loop_thread.DoInThread(base::BindOnce(
              [](base::OnceClosure task, std::atomic<int>& num_async_tasks) {
                std::move(task).Run();
                num_async_tasks--;
              },
              std::move(task), std::ref(num_async_tasks)))) {
    log::error("failed to post task to task runner!");
    return BT_STATUS_FAIL;
  }
  num_async_tasks++;
  return BT_STATUS_SUCCESS;
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

bool LeAudioClient::IsLeAudioClientRunning(void) { return false; }

namespace bluetooth::le_audio {
namespace broadcaster {
BroadcastConfiguration GetBroadcastConfig(
        const std::vector<std::pair<types::LeAudioContextType, uint8_t>>& subgroup_quality) {
  BroadcastConfiguration config = {
          .subgroups = {},
          .qos = qos_config_4_60,  // default QoS value for reliability
          .data_path = lc3_data_path,
          .sduIntervalUs = 10000,
          .phy = 0x02,   // PHY_LE_2M
          .packing = 0,  // Sequential
          .framing = 0,  // Unframed
  };

  for (auto [context, quality] : subgroup_quality) {
    // Select QoS - Check for low latency contexts
    if (AudioContexts(context).test_any(types::LeAudioContextType::GAME |
                                        types::LeAudioContextType::LIVE |
                                        types::LeAudioContextType::INSTRUCTIONAL |
                                        types::LeAudioContextType::SOUNDEFFECTS)) {
      config.qos = qos_config_2_10;
    }

    // Select codec quality
    if (quality == bluetooth::le_audio::QUALITY_STANDARD) {
      // STANDARD
      config.subgroups.push_back(lc3_mono_16_2);
    } else {
      // HIGH
      config.subgroups.push_back(lc3_stereo_48_4);
    }
  }
  return config;
}
}  // namespace broadcaster

class MockAudioHalClientEndpoint;
MockAudioHalClientEndpoint* mock_audio_source_;
bool is_audio_hal_acquired;
void (*iso_active_callback)(bool);

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireBroadcast() {
  if (mock_audio_source_) {
    std::unique_ptr<LeAudioSourceAudioHalClient> ptr(
            (LeAudioSourceAudioHalClient*)mock_audio_source_);
    is_audio_hal_acquired = true;
    return ptr;
  }
  return nullptr;
}

static constexpr uint8_t default_ccid = 0xDE;
static constexpr auto default_context =
        static_cast<std::underlying_type<LeAudioContextType>::type>(LeAudioContextType::ALERTS);
std::vector<uint8_t> default_subgroup_qualities = {bluetooth::le_audio::QUALITY_STANDARD};
static constexpr BroadcastCode default_code = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                               0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
static const std::vector<uint8_t> default_metadata = {
        bluetooth::le_audio::types::kLeAudioMetadataStreamingAudioContextLen + 1,
        bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
        default_context & 0x00FF, (default_context & 0xFF00) >> 8};
static const std::vector<uint8_t> default_public_metadata = {
        5, bluetooth::le_audio::types::kLeAudioMetadataTypeProgramInfo, 0x1, 0x2, 0x3, 0x4};
static const std::vector<uint8_t> audio_active_state_false = {
        2, bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState, 0x00};
static const std::vector<uint8_t> audio_active_state_true = {
        2, bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState, 0x01};
// bit 0: encrypted, bit 1: standard quality present
static const uint8_t test_public_broadcast_features = 0x3;

static constexpr uint8_t media_ccid = 0xC0;
static constexpr auto media_context =
        static_cast<std::underlying_type<LeAudioContextType>::type>(LeAudioContextType::MEDIA);
static const std::vector<uint8_t> media_metadata = {
        bluetooth::le_audio::types::kLeAudioMetadataStreamingAudioContextLen + 1,
        bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
        media_context & 0x00FF, (media_context & 0xFF00) >> 8};
static const std::string test_broadcast_name = "Test";

static const std::string big_terminate_timer_name = "BigTerminateTimer";
static const std::string broadcast_stop_timer_name = "BroadcastStopTimer";

class MockLeAudioBroadcasterCallbacks : public bluetooth::le_audio::LeAudioBroadcasterCallbacks {
public:
  MOCK_METHOD((void), OnBroadcastCreated, (uint32_t broadcast_id, bool success), (override));
  MOCK_METHOD((void), OnBroadcastDestroyed, (uint32_t broadcast_id), (override));
  MOCK_METHOD((void), OnBroadcastStateChanged,
              (uint32_t broadcast_id, bluetooth::le_audio::BroadcastState state), (override));
  MOCK_METHOD((void), OnBroadcastMetadataChanged,
              (uint32_t broadcast_id, const BroadcastMetadata& broadcast_metadata), (override));
  MOCK_METHOD((void), OnBroadcastAudioSessionCreated, (bool success), (override));
};

class MockAudioHalClientEndpoint : public LeAudioSourceAudioHalClient {
public:
  MockAudioHalClientEndpoint() = default;
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioSourceAudioHalClient::Callbacks* audioReceiver,
               ::bluetooth::le_audio::DsaModes dsa_modes),
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
  MOCK_METHOD((std::optional<::le_audio::types::AudioSetConfiguration>), GetUnicastConfig,
              (const CodecManager::UnicastConfigurationRequirements& requirements),
              (const override));
  MOCK_METHOD((void), UpdateBroadcastAudioConfigToHal,
              (const ::bluetooth::le_audio::broadcast_offload_config&), (override));
  MOCK_METHOD((void), SuspendedForReconfiguration, (), (override));
  MOCK_METHOD((void), ReconfigurationComplete, (), (override));

  MOCK_METHOD((void), OnDestroyed, ());
  virtual ~MockAudioHalClientEndpoint() { OnDestroyed(); }
};

class BroadcasterTest : public Test {
protected:
  void SetUp() override {
    com::android::bluetooth::flags::provider_->reset_flags();

    test::mock::osi_alarm::alarm_free.body = [](alarm_t* alarm) {
      if (alarm) {
        delete alarm;
      }
    };

    test::mock::osi_alarm::alarm_new.body = [this](const char* name) {
      if (std::string(name) == big_terminate_timer_name) {
        this->big_terminate_timer_ = new alarm_t(name);
        return this->big_terminate_timer_;
      } else if (std::string(name) == broadcast_stop_timer_name) {
        this->broadcast_stop_timer_ = new alarm_t(name);
        return this->broadcast_stop_timer_;
      } else {
        return (alarm_t*)(nullptr);
      }
    };

    test::mock::osi_alarm::alarm_set_on_mloop.body = [](alarm_t* alarm, uint64_t /*interval_ms*/,
                                                        alarm_callback_t cb, void* data) {
      alarm->cb = cb;
      alarm->data = data;
    };

    test::mock::osi_alarm::alarm_cancel.body = [](alarm_t* alarm) {
      if (alarm) {
        alarm->cb = nullptr;
        alarm->data = nullptr;
      }
    };

    init_message_loop_thread();

    reset_mock_function_count_map();
    bluetooth::hci::testing::mock_controller_ = &mock_controller_;
    ON_CALL(mock_controller_, SupportsBleIsochronousBroadcaster).WillByDefault(Return(true));

    iso_manager_ = bluetooth::hci::IsoManager::GetInstance();
    ASSERT_NE(iso_manager_, nullptr);
    iso_manager_->Start();

    mock_iso_manager_ = MockIsoManager::GetInstance();
    ON_CALL(*mock_iso_manager_, RegisterBigCallbacks(_)).WillByDefault(SaveArg<0>(&big_callbacks_));

    ConfigAudioHalClientMock();

    EXPECT_CALL(*MockIsoManager::GetInstance(), RegisterOnIsoTrafficActiveCallbacks)
            .WillOnce(SaveArg<0>(&iso_active_callback));

    ASSERT_FALSE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());
    LeAudioBroadcaster::Initialize(&mock_broadcaster_callbacks_,
                                   base::Bind([]() -> bool { return true; }));

    ContentControlIdKeeper::GetInstance()->Start();
    ContentControlIdKeeper::GetInstance()->SetCcid(LeAudioContextType::MEDIA, media_ccid);

    /* Simulate random generator */
    uint8_t random[] = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    generator_cb.Run(random);

    ConfigCodecManagerMock(types::CodecLocation::HOST);

    ON_CALL(*mock_codec_manager_, UpdateActiveUnicastAudioHalClient(_, _, _))
            .WillByDefault(Return(true));
    ON_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(_, _))
            .WillByDefault(Return(true));
    ON_CALL(*mock_codec_manager_, GetBroadcastConfig)
            .WillByDefault(Invoke(
                    [](const bluetooth::le_audio::CodecManager::BroadcastConfigurationRequirements&
                               requirements) {
                      return std::make_unique<broadcaster::BroadcastConfiguration>(
                              bluetooth::le_audio::broadcaster::GetBroadcastConfig(
                                      requirements.subgroup_quality));
                    }));
  }

  void ConfigAudioHalClientMock() {
    is_audio_hal_acquired = false;
    mock_audio_source_ = new MockAudioHalClientEndpoint();
    ON_CALL(*mock_audio_source_, Start).WillByDefault(Return(true));
    ON_CALL(*mock_audio_source_, OnDestroyed).WillByDefault([]() {
      mock_audio_source_ = nullptr;
      is_audio_hal_acquired = false;
    });
  }

  void ConfigCodecManagerMock(types::CodecLocation location) {
    codec_manager_ = le_audio::CodecManager::GetInstance();
    ASSERT_NE(codec_manager_, nullptr);
    std::vector<bluetooth::le_audio::btle_audio_codec_config_t> mock_offloading_preference(0);
    codec_manager_->Start(mock_offloading_preference);
    mock_codec_manager_ = MockCodecManager::GetInstance();
    ASSERT_NE(mock_codec_manager_, nullptr);
    ON_CALL(*mock_codec_manager_, GetCodecLocation()).WillByDefault(Return(location));
  }

  void TearDown() override {
    // Message loop cleanup should wait for all the 'till now' scheduled calls
    // so it should be called right at the very begginning of teardown.
    cleanup_message_loop_thread();

    // This is required since Stop() and Cleanup() may trigger some callbacks.
    Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);

    Mock::VerifyAndClearExpectations(MockIsoManager::GetInstance());
    Mock::VerifyAndClearExpectations(MockBroadcastStateMachine::GetLastInstance());

    LeAudioBroadcaster::Stop();
    LeAudioBroadcaster::Cleanup();
    ASSERT_FALSE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());

    ContentControlIdKeeper::GetInstance()->Stop();

    bluetooth::hci::testing::mock_controller_ = nullptr;
    delete mock_audio_source_;
    iso_active_callback = nullptr;
    delete mock_audio_source_;
    iso_manager_->Stop();
    if (codec_manager_) {
      codec_manager_->Stop();
      mock_codec_manager_ = nullptr;
    }

    test::mock::osi_alarm::alarm_free = {};
    test::mock::osi_alarm::alarm_new = {};
    test::mock::osi_alarm::alarm_set_on_mloop = {};
    test::mock::osi_alarm::alarm_cancel = {};
    big_terminate_timer_ = nullptr;
    broadcast_stop_timer_ = nullptr;
  }

  uint32_t InstantiateBroadcast(std::vector<uint8_t> metadata = default_metadata,
                                BroadcastCode code = default_code,
                                std::vector<uint8_t> quality_array = default_subgroup_qualities,
                                bool is_queued = false) {
    uint32_t broadcast_id = LeAudioBroadcaster::kInstanceIdUndefined;
    if (!is_queued) {
      EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastCreated(_, true))
              .WillOnce(SaveArg<0>(&broadcast_id));
    }

    std::vector<std::vector<uint8_t>> metadata_array;
    for (uint8_t i = 0; i < quality_array.size(); i++) {
      // use the same default_metadata for each subgroup
      metadata_array.push_back(metadata);
    }

    // Add multiple subgroup settings with the same content
    LeAudioBroadcaster::Get()->CreateAudioBroadcast(true, test_broadcast_name, code,
                                                    default_public_metadata, quality_array,
                                                    metadata_array);

    return broadcast_id;
  }

  void InjectBigCreateComplete(uint8_t big_id, uint8_t status) {
    std::vector<uint16_t> conn_handles = {0x10, 0x12};

    hci::iso_manager::big_create_cmpl_evt evt = {
            .status = status,
            .big_id = big_id,
            .big_sync_delay = 1231,
            .transport_latency_big = 1234,
            .phy = 2,
            .nse = 3,
            .bn = 2,
            .pto = 2,
            .irc = 2,
            .max_pdu = 128,
            .iso_interval = 10,
            .conn_handles = conn_handles,
    };

    big_callbacks_->OnBigEvent(bluetooth::hci::iso_manager::kIsoEventBigOnCreateCmpl, &evt);
  }

  void InjectBigTerminateComplete(uint8_t big_id, uint8_t reason) {
    hci::iso_manager::big_terminate_cmpl_evt evt = {.big_id = big_id, .reason = reason};
    big_callbacks_->OnBigEvent(bluetooth::hci::iso_manager::kIsoEventBigOnTerminateCmpl, &evt);
  }

protected:
  MockLeAudioBroadcasterCallbacks mock_broadcaster_callbacks_;
  bluetooth::hci::testing::MockControllerInterface mock_controller_;
  bluetooth::hci::IsoManager* iso_manager_;
  MockIsoManager* mock_iso_manager_;
  bluetooth::hci::iso_manager::BigCallbacks* big_callbacks_ = nullptr;

  le_audio::CodecManager* codec_manager_ = nullptr;
  MockCodecManager* mock_codec_manager_ = nullptr;

  alarm_t* big_terminate_timer_ = nullptr;
  alarm_t* broadcast_stop_timer_ = nullptr;
};

TEST_F(BroadcasterTest, Initialize) {
  ASSERT_NE(LeAudioBroadcaster::Get(), nullptr);
  ASSERT_TRUE(LeAudioBroadcaster::IsLeAudioBroadcasterRunning());
}

TEST_F(BroadcasterTest, CleanupWithBroadcastInstance) {
  auto broadcast_id = InstantiateBroadcast();
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, false))
          .WillOnce(Return(false));
}

TEST_F(BroadcasterTest, GetStreamingPhy) {
  LeAudioBroadcaster::Get()->SetStreamingPhy(1);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 1);
  LeAudioBroadcaster::Get()->SetStreamingPhy(2);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 2);
}

TEST_F(BroadcasterTest, CreateAudioBroadcast) {
  auto broadcast_id = InstantiateBroadcast();
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_EQ(broadcast_id, MockBroadcastStateMachine::GetLastInstance()->GetBroadcastId());

  auto& instance_config = MockBroadcastStateMachine::GetLastInstance()->cfg;
  ASSERT_EQ(instance_config.broadcast_code, default_code);
  for (auto& subgroup : instance_config.announcement.subgroup_configs) {
    ASSERT_EQ(types::LeAudioLtvMap(subgroup.metadata).RawPacket(), default_metadata);
  }
  // Note: There shall be a separate test to verify audio parameters
}

TEST_F(BroadcasterTest, CreateAudioBroadcastInvalidBroadcastCode) {
  std::optional<bluetooth::le_audio::BroadcastCode> invalid_broadcast_code =
          bluetooth::le_audio::BroadcastCode({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                              0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF});

  std::vector<std::vector<uint8_t>> metadata_array;
  for (uint8_t i = 0; i < default_subgroup_qualities.size(); i++) {
    // use the same default_metadata for each subgroup
    metadata_array.push_back(default_metadata);
  }

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastCreated(bluetooth::le_audio::kBroadcastIdInvalid, false))
          .Times(1);
  // Add multiple subgroup settings with the same content
  LeAudioBroadcaster::Get()->CreateAudioBroadcast(true, test_broadcast_name, invalid_broadcast_code,
                                                  default_public_metadata,
                                                  default_subgroup_qualities, metadata_array);

  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);
}

TEST_F(BroadcasterTest, CreateAudioBroadcastMultiGroups) {
  // Test with two subgroups
  auto broadcast_id = InstantiateBroadcast(
          default_metadata, default_code,
          {bluetooth::le_audio::QUALITY_STANDARD, bluetooth::le_audio::QUALITY_STANDARD});
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_EQ(broadcast_id, MockBroadcastStateMachine::GetLastInstance()->GetBroadcastId());

  auto& instance_config = MockBroadcastStateMachine::GetLastInstance()->cfg;
  ASSERT_EQ(instance_config.broadcast_code, default_code);
  ASSERT_EQ(instance_config.announcement.subgroup_configs.size(), (uint8_t)2);
  for (auto& subgroup : instance_config.announcement.subgroup_configs) {
    ASSERT_EQ(types::LeAudioLtvMap(subgroup.metadata).RawPacket(), default_metadata);
  }
}

TEST_F(BroadcasterTest, SuspendAudioBroadcast) {
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();

  if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
    ASSERT_NE(audio_receiver, nullptr);
    audio_receiver->OnAudioResume();
  } else {
    LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);
  }

  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
          .Times(1);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(AtLeast(1));
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, _))
          .Times(0);
  LeAudioBroadcaster::Get()->SuspendAudioBroadcast(broadcast_id);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(BroadcasterTest, StartAudioBroadcast) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Add Audio Actie State while broadcast created
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);

  auto broadcast_id = InstantiateBroadcast();
  ASSERT_NE(audio_receiver, nullptr);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(0);
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, false))
          .Times(0);
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  // Timers not started
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  audio_receiver->OnAudioResume();

  // NOTICE: This is really an implementation specific part, we fake the BIG
  //         config as the mocked state machine does not even call the
  //         IsoManager to prepare one (and that's good since IsoManager is also
  //         a mocked one).
  BigConfig big_cfg;
  big_cfg.big_id = MockBroadcastStateMachine::GetLastInstance()->GetAdvertisingSid();
  big_cfg.connection_handles = {0x10, 0x12};
  big_cfg.max_pdu = 128;
  MockBroadcastStateMachine::GetLastInstance()->SetExpectedBigConfig(big_cfg);

  // Inject the audio and verify call on the Iso manager side.
  EXPECT_CALL(*MockIsoManager::GetInstance(), SendIsoData).Times(1);
  std::vector<uint8_t> sample_data(320, 0);
  audio_receiver->OnAudioDataReady(sample_data);

  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(BroadcasterTest, StartAudioBroadcastMedia) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Add Audio Actie State while broadcast created
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);
  auto broadcast_id =
          InstantiateBroadcast(media_metadata, default_code, {bluetooth::le_audio::QUALITY_HIGH});
  ASSERT_NE(audio_receiver, nullptr);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);

  // Timers not started
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(0);
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, false))
          .Times(0);

  audio_receiver->OnAudioResume();

  // NOTICE: This is really an implementation specific part, we fake the BIG
  //         config as the mocked state machine does not even call the
  //         IsoManager to prepare one (and that's good since IsoManager is also
  //         a mocked one).

  auto mock_state_machine = MockBroadcastStateMachine::GetLastInstance();
  BigConfig big_cfg;
  big_cfg.big_id = mock_state_machine->GetAdvertisingSid();
  big_cfg.connection_handles = {0x10, 0x12};
  big_cfg.max_pdu = 128;
  mock_state_machine->SetExpectedBigConfig(big_cfg);

  // Inject the audio and verify call on the Iso manager side.
  EXPECT_CALL(*MockIsoManager::GetInstance(), SendIsoData).Times(2);
  std::vector<uint8_t> sample_data(1920, 0);
  audio_receiver->OnAudioDataReady(sample_data);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(BroadcasterTest, StopAudioBroadcast) {
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();

  if (com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
    ASSERT_NE(audio_receiver, nullptr);
    audio_receiver->OnAudioResume();
  } else {
    LeAudioBroadcaster::Get()->StartAudioBroadcast(broadcast_id);
  }

  // NOTICE: This is really an implementation specific part, we fake the BIG
  //         config as the mocked state machine does not even call the
  //         IsoManager to prepare one (and that's good since IsoManager is also
  //         a mocked one).

  auto mock_state_machine = MockBroadcastStateMachine::GetLastInstance();
  BigConfig big_cfg;
  big_cfg.big_id = mock_state_machine->GetAdvertisingSid();
  big_cfg.connection_handles = {0x10, 0x12};
  big_cfg.max_pdu = 128;
  mock_state_machine->SetExpectedBigConfig(big_cfg);

  InjectBigCreateComplete(big_cfg.big_id, 0x00);
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STOPPED))
          .Times(1);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(AtLeast(1));
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
    EXPECT_CALL(*mock_codec_manager_,
                UpdateActiveBroadcastAudioHalClient(mock_audio_source_, false))
            .Times(1);
  }
  InjectBigTerminateComplete(big_cfg.big_id, 0x16);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(BroadcasterTest, DestroyAudioBroadcast) {
  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);

  auto broadcast_id = InstantiateBroadcast();

  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, false))
          .Times(1);
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastDestroyed(broadcast_id)).Times(1);
  LeAudioBroadcaster::Get()->DestroyAudioBroadcast(broadcast_id);

  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  ASSERT_EQ(mock_audio_source_, nullptr);

  /* Create a mock again for the test purpose */
  ConfigAudioHalClientMock();

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, _))
          .Times(0);

  // Expect not being able to interact with this Broadcast
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastStateChanged(broadcast_id, _)).Times(0);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(0);
  LeAudioBroadcaster::Get()->StopAudioBroadcast(broadcast_id);

  EXPECT_CALL(*mock_audio_source_, Start).Times(0);

  EXPECT_CALL(*mock_audio_source_, Stop).Times(0);
  LeAudioBroadcaster::Get()->SuspendAudioBroadcast(broadcast_id);

  Mock::VerifyAndClearExpectations(mock_codec_manager_);
  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);
  // Verify the expectations before the CleanUp, which may call Stop()
  Mock::VerifyAndClearExpectations(mock_audio_source_);
}

TEST_F(BroadcasterTest, GetBroadcastAllStates) {
  auto broadcast_id = InstantiateBroadcast();
  auto broadcast_id2 = InstantiateBroadcast();
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_NE(broadcast_id2, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_NE(broadcast_id, broadcast_id2);

  /* In the current implementation state machine switches to the correct state
   * on itself, therefore here when we use mocked state machine this is not
   * being verified.
   */
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastStateChanged(broadcast_id, _)).Times(1);
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastStateChanged(broadcast_id2, _)).Times(1);

  LeAudioBroadcaster::Get()->GetAllBroadcastStates();
}

TEST_F(BroadcasterTest, UpdateMetadata) {
  auto broadcast_id = InstantiateBroadcast();
  std::vector<uint8_t> ccid_list;
  std::vector<uint8_t> expected_public_meta;
  std::string expected_broadcast_name;

  EXPECT_CALL(*MockBroadcastStateMachine::GetLastInstance(), UpdateBroadcastAnnouncement)
          .WillOnce([&](bluetooth::le_audio::BasicAudioAnnouncementData announcement) {
            for (auto subgroup : announcement.subgroup_configs) {
              if (subgroup.metadata.count(types::kLeAudioMetadataTypeCcidList)) {
                ccid_list = subgroup.metadata.at(types::kLeAudioMetadataTypeCcidList);
                break;
              }
            }
          });

  EXPECT_CALL(*MockBroadcastStateMachine::GetLastInstance(), UpdatePublicBroadcastAnnouncement)
          .WillOnce([&](uint32_t /*broadcast_id*/, const std::string& broadcast_name,
                        const bluetooth::le_audio::PublicBroadcastAnnouncementData& announcement) {
            expected_broadcast_name = broadcast_name;
            expected_public_meta = types::LeAudioLtvMap(announcement.metadata).RawPacket();
          });

  ContentControlIdKeeper::GetInstance()->SetCcid(LeAudioContextType::ALERTS, default_ccid);

  LeAudioBroadcaster::Get()->UpdateMetadata(
          broadcast_id, test_broadcast_name, default_public_metadata,
          {std::vector<uint8_t>({0x02, 0x01, 0x02, 0x03, 0x02, 0x04, 0x04})});

  std::vector<uint8_t> public_metadata(default_public_metadata);
  public_metadata.insert(public_metadata.end(), audio_active_state_false.begin(),
                         audio_active_state_false.end());

  ASSERT_EQ(2u, ccid_list.size());
  ASSERT_NE(0, std::count(ccid_list.begin(), ccid_list.end(), media_ccid));
  ASSERT_NE(0, std::count(ccid_list.begin(), ccid_list.end(), default_ccid));
  ASSERT_EQ(expected_broadcast_name, test_broadcast_name);
  if (!com::android::bluetooth::flags::leaudio_big_depends_on_audio_state()) {
    ASSERT_EQ(expected_public_meta, default_public_metadata);
  } else {
    ASSERT_EQ(expected_public_meta, public_metadata);
  }
}

static BasicAudioAnnouncementData prepareAnnouncement(
        const BroadcastSubgroupCodecConfig& codec_config,
        std::map<uint8_t, std::vector<uint8_t>> metadata) {
  BasicAudioAnnouncementData announcement;

  announcement.presentation_delay_us = 40000;
  auto const& codec_id = codec_config.GetLeAudioCodecId();
  auto const subgroup_codec_spec = codec_config.GetCommonBisCodecSpecData();

  // Note: This is a single subgroup announcement.
  announcement.subgroup_configs = {{
          .codec_config =
                  {
                          .codec_id = codec_id.coding_format,
                          .vendor_company_id = codec_id.vendor_company_id,
                          .vendor_codec_id = codec_id.vendor_codec_id,
                          .codec_specific_params = subgroup_codec_spec.Values(),
                  },
          .metadata = std::move(metadata),
          .bis_configs = {},
  }};

  uint8_t bis_count = 0;
  for (uint8_t cfg_idx = 0; cfg_idx < codec_config.GetAllBisConfigCount(); ++cfg_idx) {
    for (uint8_t bis_num = 0; bis_num < codec_config.GetNumBis(cfg_idx); ++bis_num) {
      ++bis_count;

      // Check for vendor byte array
      bluetooth::le_audio::BasicAudioAnnouncementBisConfig bis_config;
      auto vendor_config = codec_config.GetBisVendorCodecSpecData(bis_num);
      if (vendor_config) {
        bis_config.vendor_codec_specific_params = vendor_config.value();
      }

      // Check for non vendor LTVs
      auto config_ltv = codec_config.GetBisCodecSpecData(bis_num, cfg_idx);
      if (config_ltv) {
        bis_config.codec_specific_params = config_ltv->Values();
      }

      // Internally BISes are indexed from 0 in each subgroup, but the BT spec
      // requires the indices to be indexed from 1 in the entire BIG.
      bis_config.bis_index = bis_count;
      announcement.subgroup_configs[0].bis_configs.push_back(std::move(bis_config));
    }
  }

  return announcement;
}

TEST_F(BroadcasterTest, UpdateMetadataFromAudioTrackMetadata) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Add Audio Actie State while broadcast created
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));

  ContentControlIdKeeper::GetInstance()->SetCcid(LeAudioContextType::MEDIA, media_ccid);
  auto broadcast_id = InstantiateBroadcast();

  ASSERT_NE(audio_receiver, nullptr);
  audio_receiver->OnAudioResume();

  auto sm = MockBroadcastStateMachine::GetLastInstance();
  std::vector<uint8_t> ccid_list;
  std::vector<uint8_t> context_types_map;
  EXPECT_CALL(*sm, UpdateBroadcastAnnouncement)
          .WillOnce([&](bluetooth::le_audio::BasicAudioAnnouncementData announcement) {
            for (auto subgroup : announcement.subgroup_configs) {
              if (subgroup.metadata.count(types::kLeAudioMetadataTypeCcidList)) {
                ccid_list = subgroup.metadata.at(types::kLeAudioMetadataTypeCcidList);
              }
              if (subgroup.metadata.count(types::kLeAudioMetadataTypeStreamingAudioContext)) {
                context_types_map =
                        subgroup.metadata.at(types::kLeAudioMetadataTypeStreamingAudioContext);
              }
            }
          });

  std::map<uint8_t, std::vector<uint8_t>> meta = {};
  auto codec_config = broadcaster::lc3_mono_16_2;
  auto announcement = prepareAnnouncement(codec_config, meta);

  ON_CALL(*sm, GetBroadcastAnnouncement()).WillByDefault(ReturnRef(announcement));

  std::vector<struct playback_track_metadata> multitrack_source_metadata = {
          {{AUDIO_USAGE_GAME, AUDIO_CONTENT_TYPE_SONIFICATION, 0},
           {AUDIO_USAGE_MEDIA, AUDIO_CONTENT_TYPE_MUSIC, 0},
           {AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING, AUDIO_CONTENT_TYPE_SPEECH, 0},
           {AUDIO_USAGE_UNKNOWN, AUDIO_CONTENT_TYPE_UNKNOWN, 0}}};

  std::vector<playback_track_metadata_v7> tracks_vec;
  tracks_vec.reserve(multitrack_source_metadata.size());
  for (const auto& track : multitrack_source_metadata) {
    playback_track_metadata_v7 desc_track = {
            .base =
                    {
                            .usage = static_cast<audio_usage_t>(track.usage),
                            .content_type = static_cast<audio_content_type_t>(track.content_type),
                            .gain = track.gain,
                    },
    };
    tracks_vec.push_back(desc_track);
  }

  audio_receiver->OnAudioMetadataUpdate(std::move(tracks_vec), DsaMode::DISABLED);

  // Verify ccid
  ASSERT_NE(ccid_list.size(), 0u);
  ASSERT_TRUE(std::find(ccid_list.begin(), ccid_list.end(), media_ccid) != ccid_list.end());

  // Verify context type
  ASSERT_NE(context_types_map.size(), 0u);
  AudioContexts context_type;
  auto pp = context_types_map.data();
  STREAM_TO_UINT16(context_type.value_ref(), pp);
  ASSERT_TRUE(context_type.test_all(LeAudioContextType::MEDIA | LeAudioContextType::GAME));
}

TEST_F(BroadcasterTest, GetMetadata) {
  auto broadcast_id = InstantiateBroadcast();
  bluetooth::le_audio::BroadcastMetadata metadata;

  static const uint8_t test_adv_sid = 0x14;
  std::optional<bluetooth::le_audio::BroadcastCode> test_broadcast_code =
          bluetooth::le_audio::BroadcastCode({1, 2, 3, 4, 5, 6});

  auto sm = MockBroadcastStateMachine::GetLastInstance();

  std::map<uint8_t, std::vector<uint8_t>> meta = {};
  auto codec_config = broadcaster::lc3_mono_16_2;
  auto announcement = prepareAnnouncement(codec_config, meta);

  bool is_public_metadata_valid;
  types::LeAudioLtvMap public_ltv = types::LeAudioLtvMap::Parse(
          default_public_metadata.data(), default_public_metadata.size(), is_public_metadata_valid);
  PublicBroadcastAnnouncementData pb_announcement = {.features = test_public_broadcast_features,
                                                     .metadata = public_ltv.Values()};

  ON_CALL(*sm, IsPublicBroadcast()).WillByDefault(Return(true));
  ON_CALL(*sm, GetBroadcastName()).WillByDefault(Return(test_broadcast_name));
  ON_CALL(*sm, GetBroadcastCode()).WillByDefault(Return(test_broadcast_code));
  ON_CALL(*sm, GetAdvertisingSid()).WillByDefault(Return(test_adv_sid));
  ON_CALL(*sm, GetBroadcastAnnouncement()).WillByDefault(ReturnRef(announcement));
  ON_CALL(*sm, GetPublicBroadcastAnnouncement()).WillByDefault(ReturnRef(pb_announcement));

  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastMetadataChanged(broadcast_id, _))
          .Times(1)
          .WillOnce(SaveArg<1>(&metadata));
  LeAudioBroadcaster::Get()->GetBroadcastMetadata(broadcast_id);

  ASSERT_NE(LeAudioBroadcaster::kInstanceIdUndefined, metadata.broadcast_id);
  ASSERT_EQ(sm->GetBroadcastId(), metadata.broadcast_id);
  ASSERT_EQ(sm->GetBroadcastCode(), metadata.broadcast_code);
  ASSERT_EQ(sm->GetBroadcastAnnouncement(), metadata.basic_audio_announcement);
  ASSERT_EQ(sm->GetPaInterval(), metadata.pa_interval);
  ASSERT_EQ(sm->GetOwnAddress(), metadata.addr);
  ASSERT_EQ(sm->GetOwnAddressType(), metadata.addr_type);
  ASSERT_EQ(sm->GetAdvertisingSid(), metadata.adv_sid);
  ASSERT_EQ(sm->IsPublicBroadcast(), metadata.is_public);
  ASSERT_EQ(sm->GetBroadcastName(), metadata.broadcast_name);
  ASSERT_EQ(sm->GetPublicBroadcastAnnouncement(), metadata.public_announcement);
}

TEST_F(BroadcasterTest, SetStreamingPhy) {
  LeAudioBroadcaster::Get()->SetStreamingPhy(2);
  // From now on new streams should be using Phy = 2.
  InstantiateBroadcast();
  ASSERT_EQ(MockBroadcastStateMachine::GetLastInstance()->cfg.streaming_phy, 2);

  // From now on new streams should be using Phy = 1.
  LeAudioBroadcaster::Get()->SetStreamingPhy(1);
  InstantiateBroadcast();
  ASSERT_EQ(MockBroadcastStateMachine::GetLastInstance()->cfg.streaming_phy, 1);
  ASSERT_EQ(LeAudioBroadcaster::Get()->GetStreamingPhy(), 1);
}

TEST_F(BroadcasterTest, StreamParamsAlerts) {
  uint8_t expected_channels = 1u;
  InstantiateBroadcast();
  auto config = MockBroadcastStateMachine::GetLastInstance()->cfg;

  // Check audio configuration
  ASSERT_EQ(config.config.subgroups.at(0).GetNumChannelsTotal(), expected_channels);

  // Matches number of bises in the announcement
  ASSERT_EQ(config.announcement.subgroup_configs[0].bis_configs.size(), expected_channels);
  // Note: Num of bises at IsoManager level is verified by state machine tests
}

TEST_F(BroadcasterTest, StreamParamsMedia) {
  uint8_t expected_channels = 2u;
  ContentControlIdKeeper::GetInstance()->SetCcid(LeAudioContextType::MEDIA, media_ccid);
  InstantiateBroadcast(media_metadata, default_code, {bluetooth::le_audio::QUALITY_HIGH});

  auto config = MockBroadcastStateMachine::GetLastInstance()->cfg;

  // Check audio configuration
  ASSERT_EQ(config.config.subgroups.at(0).GetNumBis(), expected_channels);
  ASSERT_EQ(config.config.subgroups.at(0).GetNumChannelsTotal(), expected_channels);
  // Note there is one BIS configuration applied to both (stereo) BISes
  ASSERT_EQ(config.config.subgroups.at(0).GetAllBisConfigCount(), 1u);
  ASSERT_EQ(config.config.subgroups.at(0).GetNumBis(0), expected_channels);

  // Matches number of bises in the announcement
  ASSERT_EQ(config.announcement.subgroup_configs.size(), 1ul);

  auto& announcement_subgroup = config.announcement.subgroup_configs[0];
  ASSERT_EQ(announcement_subgroup.bis_configs.size(), expected_channels);
  // Verify CCID for Media
  auto ccid_list_opt = types::LeAudioLtvMap(announcement_subgroup.metadata)
                               .Find(bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList);
  ASSERT_TRUE(ccid_list_opt.has_value());
  auto ccid_list = ccid_list_opt.value();
  ASSERT_EQ(1u, ccid_list.size());
  ASSERT_EQ(media_ccid, ccid_list[0]);
  // Note: Num of bises at IsoManager level is verified by state machine tests
}

TEST_F(BroadcasterTest, QueuedBroadcast) {
  uint32_t broadcast_id = LeAudioBroadcaster::kInstanceIdUndefined;

  iso_active_callback(true);

  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastCreated(_, true))
          .WillOnce(SaveArg<0>(&broadcast_id));

  /* Trigger broadcast create but due to active ISO, queue request */
  InstantiateBroadcast(default_metadata, default_code, default_subgroup_qualities, true);

  /* Notify about ISO being free, check if broadcast would be created */
  iso_active_callback(false);
  ASSERT_NE(broadcast_id, LeAudioBroadcaster::kInstanceIdUndefined);
  ASSERT_EQ(broadcast_id, MockBroadcastStateMachine::GetLastInstance()->GetBroadcastId());

  auto& instance_config = MockBroadcastStateMachine::GetLastInstance()->cfg;
  ASSERT_EQ(instance_config.broadcast_code, default_code);
  for (auto& subgroup : instance_config.announcement.subgroup_configs) {
    ASSERT_EQ(types::LeAudioLtvMap(subgroup.metadata).RawPacket(), default_metadata);
  }
}

TEST_F(BroadcasterTest, QueuedBroadcastBusyIso) {
  iso_active_callback(true);

  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastCreated(_, true)).Times(0);

  /* Trigger broadcast create but due to active ISO, queue request */
  InstantiateBroadcast(default_metadata, default_code, default_subgroup_qualities, true);
}

constexpr types::LeAudioCodecId kLeAudioCodecIdVendor1 = {
        .coding_format = types::kLeAudioCodingFormatVendorSpecific,
        // Not a particualr vendor - just some random numbers
        .vendor_company_id = 0xC0,
        .vendor_codec_id = 0xDE,
};

static const types::DataPathConfiguration vendor_data_path = {
        .dataPathId = bluetooth::hci::iso_manager::kIsoDataPathHci,
        .dataPathConfig = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
                           0x0C, 0x0D, 0x0E, 0x0F},
        .isoDataPathConfig =
                {
                        .codecId = kLeAudioCodecIdVendor1,
                        .isTransparent = true,
                        .controllerDelayUs = 0x00000000,  // irrlevant for transparent mode
                        .configuration = {0x1F, 0x2E, 0x3D, 0x4C, 0x5B, 0x6A, 0x79, 0x88, 0x97,
                                          0xA6, 0xB5, 0xC4, 0xD3, 0xE2, 0xF1},
                },
};

// Quality subgroup configurations
static const broadcaster::BroadcastSubgroupCodecConfig vendor_stereo_16_2 =
        broadcaster::BroadcastSubgroupCodecConfig(
                kLeAudioCodecIdVendor1,
                {broadcaster::BroadcastSubgroupBisCodecConfig{
                        // num_bis
                        2,
                        // bis_channel_cnt
                        1,
                        // codec_specific
                        types::LeAudioLtvMap({
                                LTV_ENTRY_SAMPLING_FREQUENCY(
                                        codec_spec_conf::kLeAudioSamplingFreq16000Hz),
                                LTV_ENTRY_FRAME_DURATION(
                                        codec_spec_conf::kLeAudioCodecFrameDur10000us),
                                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(50),
                        }),
                        // vendor_codec_specific
                        std::vector<uint8_t>{0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
                                             0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xF0},
                }},
                // bits_per_sample
                24,
                // vendor_codec_specific
                std::vector<uint8_t>{0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99,
                                     0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF});

static const broadcaster::BroadcastConfiguration vendor_stereo_16_2_1 = {
        // subgroup list, qos configuration, data path configuration
        .subgroups = {vendor_stereo_16_2},
        .qos = broadcaster::qos_config_2_10,
        .data_path = vendor_data_path,
        .sduIntervalUs = 5000,
        .maxSduOctets = 128,
        .phy = 0x01,   // PHY_LE_1M
        .packing = 1,  // Interleaved
        .framing = 1,  // Framed
};

TEST_F(BroadcasterTest, SanityTest) {
  ASSERT_EQ(broadcaster::lc3_mono_16_2_1, broadcaster::lc3_mono_16_2_1);
  ASSERT_EQ(vendor_stereo_16_2_1, vendor_stereo_16_2_1);
}

TEST_F(BroadcasterTest, VendorCodecConfig) {
  ON_CALL(*mock_codec_manager_, GetBroadcastConfig)
          .WillByDefault(Invoke(
                  [](const bluetooth::le_audio::CodecManager::BroadcastConfigurationRequirements&) {
                    return std::make_unique<broadcaster::BroadcastConfiguration>(
                            vendor_stereo_16_2_1);
                  }));
  ContentControlIdKeeper::GetInstance()->SetCcid(LeAudioContextType::MEDIA, media_ccid);

  // iso_active_callback(false);
  auto broadcast_id =
          InstantiateBroadcast(media_metadata, default_code, {bluetooth::le_audio::QUALITY_HIGH});
  ASSERT_NE(LeAudioBroadcaster::kInstanceIdUndefined, broadcast_id);

  auto mock_state_machine = MockBroadcastStateMachine::GetLastInstance();
  ASSERT_NE(nullptr, mock_state_machine);

  // Verify the codec config
  ASSERT_EQ(vendor_stereo_16_2_1, mock_state_machine->cfg.config);

  // Verify the basic audio announcement
  ASSERT_NE(0lu, mock_state_machine->cfg.announcement.presentation_delay_us);

  // One subgroup
  ASSERT_EQ(1lu, mock_state_machine->cfg.announcement.subgroup_configs.size());
  auto const& subgroup = mock_state_machine->cfg.announcement.subgroup_configs.at(0);

  auto const& expected_subgroup_codec_conf = vendor_stereo_16_2_1.subgroups.at(0);
  ASSERT_EQ(expected_subgroup_codec_conf.GetNumBis(), subgroup.bis_configs.size());

  // Subgroup level codec configuration
  ASSERT_EQ(expected_subgroup_codec_conf.GetLeAudioCodecId().coding_format,
            subgroup.codec_config.codec_id);
  ASSERT_EQ(expected_subgroup_codec_conf.GetLeAudioCodecId().vendor_company_id,
            subgroup.codec_config.vendor_company_id);
  ASSERT_EQ(expected_subgroup_codec_conf.GetLeAudioCodecId().vendor_codec_id,
            subgroup.codec_config.vendor_codec_id);

  // There should be no common set of parameters in the LTV format if there is
  // a vendor specific configuration
  ASSERT_TRUE(subgroup.codec_config.codec_specific_params.empty());
  ASSERT_TRUE(subgroup.codec_config.vendor_codec_specific_params.has_value());
  ASSERT_EQ(0, memcmp(expected_subgroup_codec_conf.GetVendorCodecSpecData()->data(),
                      subgroup.codec_config.vendor_codec_specific_params->data(),
                      subgroup.codec_config.vendor_codec_specific_params->size()));

  // Subgroup metadata
  ASSERT_NE(0lu, subgroup.metadata.size());

  // Verify the BISes
  ASSERT_EQ(expected_subgroup_codec_conf.GetNumBis(), subgroup.bis_configs.size());

  // Verify BIS 1
  uint8_t bis_idx = 1;
  ASSERT_EQ(bis_idx, subgroup.bis_configs.at(0).bis_index);
  // Expect only the vendor specific data
  ASSERT_TRUE(subgroup.bis_configs.at(0).codec_specific_params.empty());
  ASSERT_TRUE(subgroup.bis_configs.at(0)
                      .vendor_codec_specific_params.has_value());  // BIS vendor specific parameters
  ASSERT_NE(0lu, subgroup.bis_configs.at(0).vendor_codec_specific_params->size());
  ASSERT_EQ(expected_subgroup_codec_conf.GetBisVendorCodecSpecData(0)->size(),
            subgroup.bis_configs.at(0).vendor_codec_specific_params->size());
  ASSERT_EQ(0, memcmp(expected_subgroup_codec_conf.GetBisVendorCodecSpecData(0)->data(),
                      subgroup.bis_configs.at(0).vendor_codec_specific_params->data(),
                      subgroup.bis_configs.at(0).vendor_codec_specific_params->size()));

  // Verify BIS 2
  bis_idx = 2;
  ASSERT_EQ(bis_idx, subgroup.bis_configs.at(1).bis_index);
  // Expect only the vendor specific data
  ASSERT_TRUE(subgroup.bis_configs.at(1).codec_specific_params.empty());
  ASSERT_TRUE(subgroup.bis_configs.at(1)
                      .vendor_codec_specific_params.has_value());  // BIS vendor specific parameters
  ASSERT_NE(0lu, subgroup.bis_configs.at(1).vendor_codec_specific_params->size());
  ASSERT_EQ(expected_subgroup_codec_conf.GetBisVendorCodecSpecData(1)->size(),
            subgroup.bis_configs.at(1).vendor_codec_specific_params->size());
  ASSERT_EQ(0, memcmp(expected_subgroup_codec_conf.GetBisVendorCodecSpecData(1)->data(),
                      subgroup.bis_configs.at(1).vendor_codec_specific_params->data(),
                      subgroup.bis_configs.at(1).vendor_codec_specific_params->size()));
}

TEST_F(BroadcasterTest, AudioActiveState) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  std::vector<uint8_t> updated_public_meta;
  PublicBroadcastAnnouncementData pb_announcement;

  std::vector<uint8_t> public_metadata_audio_false(default_public_metadata);
  public_metadata_audio_false.insert(public_metadata_audio_false.end(),
                                     audio_active_state_false.begin(),
                                     audio_active_state_false.end());
  std::vector<uint8_t> public_metadata_audio_true(default_public_metadata);
  public_metadata_audio_true.insert(public_metadata_audio_true.end(),
                                    audio_active_state_true.begin(), audio_active_state_true.end());

  // Add Audio Actie State while broadcast created
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));

  auto broadcast_id = InstantiateBroadcast();
  ASSERT_NE(audio_receiver, nullptr);
  auto sm = MockBroadcastStateMachine::GetLastInstance();
  pb_announcement = sm->GetPublicBroadcastAnnouncement();
  auto created_public_meta = types::LeAudioLtvMap(pb_announcement.metadata).RawPacket();
  ASSERT_EQ(created_public_meta, public_metadata_audio_false);

  ON_CALL(*sm, UpdatePublicBroadcastAnnouncement(broadcast_id, _, _))
          .WillByDefault(
                  [&](uint32_t /*broadcast_id*/, const std::string& /*broadcast_name*/,
                      const bluetooth::le_audio::PublicBroadcastAnnouncementData& announcement) {
                    pb_announcement = announcement;
                    updated_public_meta = types::LeAudioLtvMap(announcement.metadata).RawPacket();
                  });
  ON_CALL(*sm, GetPublicBroadcastAnnouncement()).WillByDefault(ReturnRef(pb_announcement));

  // Update to true Audio Active State while audio resumed
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  audio_receiver->OnAudioResume();
  ASSERT_EQ(updated_public_meta, public_metadata_audio_true);

  // No update Audio Active State if the same value
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement).Times(0);
  audio_receiver->OnAudioResume();

  // Updated to false Audio Active State while audio suspended
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(updated_public_meta, public_metadata_audio_false);

  // No update Audio Active State if the same value
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement).Times(0);
  audio_receiver->OnAudioSuspend();

  // Update to false Audio Active State while metada updated
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  LeAudioBroadcaster::Get()->UpdateMetadata(
          broadcast_id, test_broadcast_name, default_public_metadata,
          {std::vector<uint8_t>({0x02, 0x01, 0x02, 0x03, 0x02, 0x04, 0x04})});
  ASSERT_EQ(updated_public_meta, public_metadata_audio_false);

  // Update to true Audio Active State while audio resumed
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  audio_receiver->OnAudioResume();
  ASSERT_EQ(updated_public_meta, public_metadata_audio_true);

  // Update to true Audio Active State while metada updated
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  LeAudioBroadcaster::Get()->UpdateMetadata(
          broadcast_id, test_broadcast_name, default_public_metadata,
          {std::vector<uint8_t>({0x02, 0x01, 0x02, 0x03, 0x02, 0x04, 0x04})});
  ASSERT_EQ(updated_public_meta, public_metadata_audio_true);

  // Updated to false Audio Active State while broadcast suspended
  EXPECT_CALL(*sm, UpdatePublicBroadcastAnnouncement);
  LeAudioBroadcaster::Get()->SuspendAudioBroadcast(broadcast_id);
  ASSERT_EQ(updated_public_meta, public_metadata_audio_false);
}

TEST_F(BroadcasterTest, BigTerminationAndBroadcastStopWhenNoSoundFromTheBeginning) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Timers created
  ASSERT_TRUE(big_terminate_timer_ != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_ != nullptr);

  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(0);
  // Timers not started
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);
  ASSERT_EQ(0, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_NE(audio_receiver, nullptr);
}

TEST_F(BroadcasterTest, BigTerminationAndBroadcastStopWhenNoSoundAfterSuspend) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Timers created
  ASSERT_TRUE(big_terminate_timer_ != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_ != nullptr);

  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  // Timers not started
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  // Start Broadcast
  audio_receiver->OnAudioResume();

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(2, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // OnAudioResume before timer execution cancel them and not change the broadcast state
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastStateChanged(broadcast_id, _)).Times(0);
  audio_receiver->OnAudioResume();
  // Timers cancelled
  ASSERT_EQ(4, get_func_call_count("alarm_cancel"));
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(4, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // BIG termination timer execution, state machine go to CONFIGURED state so BIG terminated
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
          .Times(1);
  // Imitate execution of BIG termination timer
  big_terminate_timer_->cb(big_terminate_timer_->data);

  // Broadcast stop timer execution, state machine go to STOPPED state
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STOPPED))
          .Times(1);
  // Imitate execution of BIG termination timer
  broadcast_stop_timer_->cb(broadcast_stop_timer_->data);
}

TEST_F(BroadcasterTest, BigCreationTerminationDependsOnAudioResumeSuspend) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  // Timers created
  ASSERT_TRUE(big_terminate_timer_ != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_ != nullptr);

  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  // Timers not started
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  // Start Broadcast
  audio_receiver->OnAudioResume();

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(2, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // OnAudioResume before timer execution cancel them and not change the broadcast state
  EXPECT_CALL(mock_broadcaster_callbacks_, OnBroadcastStateChanged(broadcast_id, _)).Times(0);
  audio_receiver->OnAudioResume();
  // Timers cancelled
  ASSERT_EQ(4, get_func_call_count("alarm_cancel"));
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(4, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // BIG termination timer execution, state machine go to CONFIGURED state so BIG terminated
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
          .Times(1);
  // Imitate execution of BIG termination timer
  big_terminate_timer_->cb(big_terminate_timer_->data);

  // onAudioResume cause state machine go to STREAMING state so BIG creation
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  audio_receiver->OnAudioResume();
  // Timers Cancelled
  ASSERT_EQ(6, get_func_call_count("alarm_cancel"));
  ASSERT_TRUE(big_terminate_timer_->cb == nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb == nullptr);
}

TEST_F(BroadcasterTest, AudioResumeWhileStreaming) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();

  ASSERT_NE(audio_receiver, nullptr);

  // onAudioResume cause state machine go to STREAMING state so BIG creation
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(mock_audio_source_);

  // 2nd stream resume
  EXPECT_CALL(*mock_audio_source_, ConfirmStreamingRequest()).Times(1);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(mock_audio_source_);

  // 3rd stream resume
  EXPECT_CALL(*mock_audio_source_, ConfirmStreamingRequest()).Times(1);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(mock_audio_source_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);
}

TEST_F(BroadcasterTest, AudioResumeAfterSuspend) {
  com::android::bluetooth::flags::provider_->leaudio_big_depends_on_audio_state(true);

  EXPECT_CALL(*mock_codec_manager_, UpdateActiveBroadcastAudioHalClient(mock_audio_source_, true))
          .Times(1);
  LeAudioSourceAudioHalClient::Callbacks* audio_receiver;
  EXPECT_CALL(*mock_audio_source_, Start)
          .WillOnce(DoAll(SaveArg<1>(&audio_receiver), Return(true)))
          .WillRepeatedly(Return(false));
  auto broadcast_id = InstantiateBroadcast();

  ASSERT_NE(audio_receiver, nullptr);

  // OnAudioResume cause state machine go to STREAMING state so BIG creation
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(mock_audio_source_);
  Mock::VerifyAndClearExpectations(mock_codec_manager_);

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(2, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // BIG termination timer execution, state machine go to CONFIGURED state so BIG terminated
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
          .Times(1);
  // Imitate execution of BIG termination timer
  big_terminate_timer_->cb(big_terminate_timer_->data);
  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);

  // OnAudioResume cause state machine go to STREAMING state so BIG creation
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);

  // OnAudioSuspend cause starting the BIG termination timer
  audio_receiver->OnAudioSuspend();
  ASSERT_EQ(4, get_func_call_count("alarm_set_on_mloop"));
  ASSERT_TRUE(big_terminate_timer_->cb != nullptr);
  ASSERT_TRUE(broadcast_stop_timer_->cb != nullptr);

  // BIG termination timer execution, state machine go to CONFIGURED state so BIG terminated
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::CONFIGURED))
          .Times(1);
  // Imitate execution of BIG termination timer
  big_terminate_timer_->cb(big_terminate_timer_->data);

  // Imitate busy ISO
  iso_active_callback(true);

  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(0);
  audio_receiver->OnAudioResume();
  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);

  // Verify if iso de-activation start streaming
  EXPECT_CALL(mock_broadcaster_callbacks_,
              OnBroadcastStateChanged(broadcast_id, BroadcastState::STREAMING))
          .Times(1);
  iso_active_callback(false);
  Mock::VerifyAndClearExpectations(&mock_broadcaster_callbacks_);
}

// TODO: Add tests for:
// ToRawPacket(BasicAudioAnnouncementData const& in, std::vector<uint8_t>& data)

}  // namespace bluetooth::le_audio
