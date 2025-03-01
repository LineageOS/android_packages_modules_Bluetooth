/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "codec_manager.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include "audio_hal_client/audio_hal_client.h"
#include "audio_hal_interface/le_audio_software.h"
#include "hci/controller_interface_mock.h"
#include "hci/hci_packets.h"
#include "internal_include/stack_config.h"
#include "le_audio/gmap_client.h"
#include "le_audio/gmap_server.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_set_configuration_provider.h"
#include "osi/include/properties.h"
#include "test/mock/mock_legacy_hci_interface.h"
#include "test/mock/mock_main_shim_entry.h"

using ::testing::_;
using ::testing::Mock;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::Test;

using bluetooth::hci::OpCode;
using bluetooth::hci::iso_manager::kIsoDataPathHci;
using bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
using bluetooth::le_audio::types::AudioSetConfiguration;
using bluetooth::le_audio::types::CodecLocation;
using bluetooth::le_audio::types::kLeAudioDirectionSink;
using bluetooth::le_audio::types::kLeAudioDirectionSource;

static const std::vector<AudioSetConfiguration> offload_capabilities_none(0);

const std::vector<AudioSetConfiguration>* offload_capabilities = &offload_capabilities_none;

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

namespace bluetooth::audio::le_audio {
OffloadCapabilities get_offload_capabilities() {
  return {*offload_capabilities, *offload_capabilities};
}
std::optional<bluetooth::le_audio::ProviderInfo> LeAudioClientInterface::GetCodecConfigProviderInfo(
        void) const {
  return std::nullopt;
}
LeAudioClientInterface* LeAudioClientInterface::Get() { return nullptr; }
}  // namespace bluetooth::audio::le_audio

namespace bluetooth::le_audio {

void GmapClient::UpdateGmapOffloaderSupport(bool) {}
void GmapServer::UpdateGmapOffloaderSupport(bool) {}

class MockLeAudioSourceHalClient;
MockLeAudioSourceHalClient* mock_le_audio_source_hal_client_;
std::unique_ptr<LeAudioSourceAudioHalClient> owned_mock_le_audio_source_hal_client_;
bool is_audio_unicast_source_acquired;
bool is_audio_broadcast_source_acquired;

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireUnicast() {
  if (is_audio_unicast_source_acquired) {
    return nullptr;
  }
  is_audio_unicast_source_acquired = true;
  return std::move(owned_mock_le_audio_source_hal_client_);
}

MockLeAudioSourceHalClient* mock_broadcast_le_audio_source_hal_client_;
std::unique_ptr<LeAudioSourceAudioHalClient> owned_mock_broadcast_le_audio_source_hal_client_;

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireBroadcast() {
  if (is_audio_broadcast_source_acquired) {
    return nullptr;
  }
  is_audio_broadcast_source_acquired = true;
  return std::move(owned_mock_broadcast_le_audio_source_hal_client_);
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

  MOCK_METHOD((std::optional<broadcaster::BroadcastConfiguration>), GetBroadcastConfig,
              ((const std::vector<std::pair<types::LeAudioContextType, uint8_t>>&),
               (const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>&)),
              (const override));

  MOCK_METHOD((std::optional<::bluetooth::le_audio::types::AudioSetConfiguration>),
              GetUnicastConfig,
              (types::LeAudioContextType,
               std::optional<const ::bluetooth::le_audio::types::PublishedAudioCapabilities*>,
               std::optional<const ::bluetooth::le_audio::types::PublishedAudioCapabilities*>),
              (const override));

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
  MOCK_METHOD((void), UpdateBroadcastAudioConfigToHal,
              (const ::bluetooth::le_audio::broadcast_offload_config&), (override));
  MOCK_METHOD((void), SuspendedForReconfiguration, (), (override));
  MOCK_METHOD((void), ReconfigurationComplete, (), (override));

  MOCK_METHOD((std::optional<broadcaster::BroadcastConfiguration>), GetBroadcastConfig,
              ((const std::vector<std::pair<types::LeAudioContextType, uint8_t>>&),
               (const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>&)),
              (const override));

  MOCK_METHOD((std::optional<::bluetooth::le_audio::types::AudioSetConfiguration>),
              GetUnicastConfig, (const CodecManager::UnicastConfigurationRequirements&),
              (const override));

  MOCK_METHOD((void), OnDestroyed, ());
  virtual ~MockLeAudioSourceHalClient() override { OnDestroyed(); }
};

static const types::LeAudioCodecId kLeAudioCodecIdLc3 = {
        .coding_format = types::kLeAudioCodingFormatLC3,
        .vendor_company_id = types::kLeAudioVendorCompanyIdUndefined,
        .vendor_codec_id = types::kLeAudioVendorCodecIdUndefined};

static const types::LeAudioCodecId kLeAudioCodecIdVendor_C0DE = {
        .coding_format = types::kLeAudioCodingFormatVendorSpecific,
        .vendor_company_id = types::kLeAudioVendorCompanyIdGoogle,
        .vendor_codec_id = 0xC0DE};

static const types::CodecConfigSetting lc3_16_2 = {
        .id = kLeAudioCodecIdLc3,
        .params = types::LeAudioLtvMap({
                LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq16000Hz),
                LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(40),
        }),
        .channel_count_per_iso_stream = 1,
};

static const types::CodecConfigSetting lc3_24_2 = {
        .id = kLeAudioCodecIdLc3,
        .params = types::LeAudioLtvMap({
                LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq24000Hz),
                LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(60),
        }),
        .channel_count_per_iso_stream = 1,
};

static const types::CodecConfigSetting lc3_32_2 = {
        .id = kLeAudioCodecIdLc3,
        .params = types::LeAudioLtvMap({
                LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq32000Hz),
                LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(80),
        }),
        .channel_count_per_iso_stream = 1,
};

static const types::CodecConfigSetting lc3_48_2 = {
        .id = kLeAudioCodecIdLc3,
        .params = types::LeAudioLtvMap({
                LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq48000Hz),
                LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(100),
        }),
        .channel_count_per_iso_stream = 1,
};

static const types::CodecConfigSetting vendor_code_48_2 = {
        .id = kLeAudioCodecIdVendor_C0DE,
        .params = types::LeAudioLtvMap({
                LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq48000Hz),
                LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                LTV_ENTRY_OCTETS_PER_CODEC_FRAME(100),
        }),
        .vendor_params = {03, 01, 02, 03},
        .channel_count_per_iso_stream = 1,
};

static void set_mock_offload_capabilities(const std::vector<AudioSetConfiguration>& caps) {
  offload_capabilities = &caps;
}

static constexpr char kPropLeAudioOffloadSupported[] = "ro.bluetooth.leaudio_offload.supported";
static constexpr char kPropLeAudioCodecExtensibility[] =
        "bluetooth.core.le_audio.codec_extension_aidl.enabled";
static constexpr char kPropLeAudioOffloadDisabled[] = "persist.bluetooth.leaudio_offload.disabled";
static constexpr char kPropLeAudioBidirSwbSupported[] =
        "bluetooth.leaudio.dual_bidirection_swb.supported";

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  RawAddress result = {{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index}};
  return result;
}

class CodecManagerTestBase : public Test {
public:
  virtual void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com::android::bluetooth::flags::provider_->reset_flags();
    set_mock_offload_capabilities(offload_capabilities_none);

    bluetooth::legacy::hci::testing::SetMock(legacy_hci_mock_);

    ON_CALL(controller_interface, SupportsBleIsochronousBroadcaster).WillByDefault(Return(true));
    ON_CALL(controller_interface, IsSupported(OpCode::CONFIGURE_DATA_PATH))
            .WillByDefault(Return(true));
    bluetooth::hci::testing::mock_controller_ = &controller_interface;

    codec_manager = CodecManager::GetInstance();

    RegisterSourceHalClientMock();
    RegisterSinkHalClientMock();
  }

  virtual void TearDown() override { codec_manager->Stop(); }

  NiceMock<bluetooth::hci::testing::MockControllerInterface> controller_interface;
  CodecManager* codec_manager;
  bluetooth::legacy::hci::testing::MockInterface legacy_hci_mock_;

protected:
  void RegisterSourceHalClientMock() {
    owned_mock_le_audio_source_hal_client_.reset(new NiceMock<MockLeAudioSourceHalClient>());
    mock_le_audio_source_hal_client_ =
            (MockLeAudioSourceHalClient*)owned_mock_le_audio_source_hal_client_.get();

    is_audio_unicast_source_acquired = false;

    owned_mock_broadcast_le_audio_source_hal_client_.reset(
            new NiceMock<MockLeAudioSourceHalClient>());
    mock_broadcast_le_audio_source_hal_client_ =
            (MockLeAudioSourceHalClient*)owned_mock_broadcast_le_audio_source_hal_client_.get();
    is_audio_broadcast_source_acquired = false;

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

    ON_CALL(*mock_le_audio_sink_hal_client_, OnDestroyed).WillByDefault([]() {
      mock_le_audio_sink_hal_client_ = nullptr;
      is_audio_unicast_sink_acquired = false;
    });
  }
};

/*----------------- ADSP codec manager tests ------------------*/
class CodecManagerTestAdsp : public CodecManagerTestBase {
public:
  virtual void SetUp() override {
    // Enable the HW offloader
    osi_property_set_bool(kPropLeAudioOffloadSupported, true);
    osi_property_set_bool(kPropLeAudioOffloadDisabled, false);

    // Allow for bidir SWB configurations
    osi_property_set_bool(kPropLeAudioBidirSwbSupported, true);

    // Disable codec extensibility by default
    osi_property_set_bool(kPropLeAudioCodecExtensibility, false);

    com::android::bluetooth::flags::provider_->leaudio_mono_location_errata(false);

    CodecManagerTestBase::SetUp();
  }
};

class CodecManagerTestAdspNoSwb : public CodecManagerTestBase {
public:
  virtual void SetUp() override {
    // Enable the HW offloader
    osi_property_set_bool(kPropLeAudioOffloadSupported, true);
    osi_property_set_bool(kPropLeAudioOffloadDisabled, false);

    // Allow for bidir SWB configurations
    osi_property_set_bool(kPropLeAudioBidirSwbSupported, false);

    CodecManagerTestBase::SetUp();
  }
};

TEST_F(CodecManagerTestAdsp, test_init) { ASSERT_EQ(codec_manager, CodecManager::GetInstance()); }

TEST_F(CodecManagerTestAdsp, test_start) {
  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(1);
  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(1);

  // Verify data path is reset on Stop()
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER, kIsoDataPathHci, _))
          .Times(1);
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST, kIsoDataPathHci, _))
          .Times(1);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  ASSERT_EQ(codec_manager->GetCodecLocation(), CodecLocation::ADSP);
}

TEST_F(CodecManagerTestAdsp, testStreamConfigurationAdspDownMix) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  // Current CIS configuration for two earbuds
  std::vector<struct types::cis> cises{
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 96,
          },
          {
                  .id = 0x01,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 97,
          },
  };

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontLeft,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info(
                                                  97, codec_spec_conf::kLeAudioLocationFrontLeft,
                                                  true)},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 16000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 40,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 1,
                          .num_of_devices = 1,
                  },
          .source =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontLeft,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info(
                                                  97, codec_spec_conf::kLeAudioLocationBackLeft,
                                                  true)},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 16000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 40,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 1,
                          .num_of_devices = 1,
                  },
  };

  codec_manager->UpdateCisConfiguration(cises, stream_params.sink, kLeAudioDirectionSink);
  codec_manager->UpdateCisConfiguration(cises, stream_params.source, kLeAudioDirectionSource);

  // Verify the offloader config content
  types::BidirectionalPair<std::optional<stream_config>> out_offload_configs;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect the same configuration for sink and source
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    uint32_t allocation = 0;
    auto& config = out_offload_configs.get(direction).value();
    ASSERT_EQ(2lu, config.stream_map.size());
    for (const auto& info : config.stream_map) {
      if (info.stream_handle == 96) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationFrontRight, info.audio_channel_allocation);
        // The disconnected should be inactive
        ASSERT_FALSE(info.is_stream_active);

      } else if (info.stream_handle == 97) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationFrontLeft, info.audio_channel_allocation);
        // The connected should be active
        ASSERT_TRUE(info.is_stream_active);

      } else {
        ASSERT_EQ(97, info.stream_handle);
      }
      allocation |= info.audio_channel_allocation;
    }

    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(16000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(40u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
    ASSERT_EQ(codec_spec_conf::kLeAudioLocationStereo, allocation);
  }

  // Clear the CIS configuration map (no active CISes).
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSink);
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSource);
  out_offload_configs.sink = std::nullopt;
  out_offload_configs.source = std::nullopt;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect sink & source configurations with empty CIS channel allocation map.
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    auto& config = out_offload_configs.get(direction).value();
    ASSERT_EQ(0lu, config.stream_map.size());
    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(16000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(40u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
  }
}

TEST_F(CodecManagerTestAdsp, testStreamConfigurationMono) {
  com::android::bluetooth::flags::provider_->leaudio_mono_location_errata(true);
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  // Current CIS configuration for two earbuds
  std::vector<struct types::cis> cises{
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 96,
                  .addr = RawAddress::kEmpty,  // Disconnected
          },
          {
                  .id = 0x01,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 97,
                  .addr = GetTestAddress(1),
          },
  };

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationMonoAudio,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info(
                                                  97, codec_spec_conf::kLeAudioLocationMonoAudio,
                                                  true)},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 16000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 40,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 1,
                          .num_of_devices = 1,
                  },
          .source =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationMonoAudio,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info(
                                                  97, codec_spec_conf::kLeAudioLocationMonoAudio,
                                                  true)},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 16000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 40,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 1,
                          .num_of_devices = 1,
                  },
  };

  ASSERT_TRUE(
          codec_manager->UpdateCisConfiguration(cises, stream_params.sink, kLeAudioDirectionSink));
  ASSERT_TRUE(codec_manager->UpdateCisConfiguration(cises, stream_params.source,
                                                    kLeAudioDirectionSource));

  // Verify the offloader config content
  types::BidirectionalPair<std::optional<stream_config>> out_offload_configs;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect the same configuration for sink and source
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    uint32_t allocation = 0;
    auto& config = out_offload_configs.get(direction).value();
    ASSERT_EQ(2lu, config.stream_map.size());
    for (const auto& info : config.stream_map) {
      if (info.stream_handle == 96) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationMonoAudio, info.audio_channel_allocation);
        // The disconnected should be inactive
        ASSERT_FALSE(info.is_stream_active);

      } else if (info.stream_handle == 97) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationMonoAudio, info.audio_channel_allocation);
        // The connected should be active
        ASSERT_TRUE(info.is_stream_active);

      } else {
        ASSERT_EQ(97, info.stream_handle);
      }
      allocation |= info.audio_channel_allocation;
    }

    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(16000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(40u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
    ASSERT_EQ(codec_spec_conf::kLeAudioLocationMonoAudio, allocation);
  }

  // Clear the CIS configuration map (no active CISes).
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSink);
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSource);
  out_offload_configs.sink = std::nullopt;
  out_offload_configs.source = std::nullopt;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect sink & source configurations with empty CIS channel allocation map.
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    auto& config = out_offload_configs.get(direction).value();
    ASSERT_EQ(0lu, config.stream_map.size());
    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(16000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(40u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
  }
}

TEST_F(CodecManagerTestAdsp, test_capabilities_none) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  bool has_null_config = false;
  auto match_first_config =
          [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
              const types::AudioSetConfigurations* confs)
          -> std::unique_ptr<types::AudioSetConfiguration> {
    // Don't expect the matcher being called on nullptr
    if (confs == nullptr) {
      has_null_config = true;
    }
    if (confs && confs->size()) {
      // For simplicity return the first element, the real matcher should
      // check the group capabilities.
      return std::make_unique<AudioSetConfiguration>(*(confs->at(0)));
    }
    return nullptr;
  };

  // Verify every context
  for (::bluetooth::le_audio::types::LeAudioContextType ctx_type :
       ::bluetooth::le_audio::types::kLeAudioContextAllTypesArray) {
    has_null_config = false;
    CodecManager::UnicastConfigurationRequirements requirements = {
            .audio_context_type = ctx_type,
    };
    ASSERT_EQ(nullptr, codec_manager->GetCodecConfig(requirements, match_first_config));
    ASSERT_FALSE(has_null_config);
  }
}

TEST_F(CodecManagerTestAdsp, test_capabilities) {
  for (auto test_context : ::bluetooth::le_audio::types::kLeAudioContextAllTypesArray) {
    // Build the offloader capabilities vector using the configuration provider
    // in HOST mode to get all the .json file configuration entries.
    std::vector<AudioSetConfiguration> offload_capabilities;
    AudioSetConfigurationProvider::Initialize(bluetooth::le_audio::types::CodecLocation::HOST);
    auto all_local_configs = AudioSetConfigurationProvider::Get()->GetConfigurations(test_context);
    ASSERT_NE(0lu, all_local_configs->size());

    for (auto& cap : *all_local_configs) {
      offload_capabilities.push_back(*cap);
    }

    ASSERT_NE(0u, offload_capabilities.size());
    set_mock_offload_capabilities(offload_capabilities);
    // Clean up before the codec manager starts it in ADSP mode.
    AudioSetConfigurationProvider::Cleanup();

    const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
            {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
    codec_manager->Start(offloading_preference);

    auto output_capabilities = codec_manager->GetLocalAudioOutputCodecCapa();
    bool is_multiplex_supported = false;
    for (auto& capa : output_capabilities) {
      if (capa.channel_count > bluetooth::le_audio::LE_AUDIO_CHANNEL_COUNT_INDEX_1) {
        is_multiplex_supported = true;
        break;
      }
    }

    ASSERT_TRUE(is_multiplex_supported);

    size_t available_configs_size = 0;
    auto match_first_config =
            [&available_configs_size](
                    const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
                    const types::AudioSetConfigurations* confs)
            -> std::unique_ptr<types::AudioSetConfiguration> {
      if (confs && confs->size()) {
        available_configs_size = confs->size();
        // For simplicity return the first element, the real matcher should
        // check the group capabilities.
        return std::make_unique<AudioSetConfiguration>(*(confs->at(0)));
      }
      return nullptr;
    };

    CodecManager::UnicastConfigurationRequirements requirements = {
            .audio_context_type = test_context,
    };
    auto cfg = codec_manager->GetCodecConfig(requirements, match_first_config);
    ASSERT_NE(nullptr, cfg);
    ASSERT_EQ(offload_capabilities.size(), available_configs_size);

    // Clean up the before testing any other offload capabilities.
    codec_manager->Stop();
  }
}

TEST_F(CodecManagerTestAdsp, test_broadcast_config) {
  static const types::CodecConfigSetting bc_lc3_48_2 = {
          .id = kLeAudioCodecIdLc3,
          .params = types::LeAudioLtvMap({
                  LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq48000Hz),
                  LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                  LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                  LTV_ENTRY_OCTETS_PER_CODEC_FRAME(100),
          }),
          .channel_count_per_iso_stream = 2,
  };

  std::vector<AudioSetConfiguration> offload_capabilities = {{
          .name = "Test_Broadcast_Config_No_Dev_lc3_48_2",
          .confs = {.sink = {types::AseConfiguration(bc_lc3_48_2),
                             types::AseConfiguration(bc_lc3_48_2)},
                    .source = {}},
  }};
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  CodecManager::BroadcastConfigurationRequirements requirements = {
          .subgroup_quality = {{types::LeAudioContextType::MEDIA, 1}}};
  auto cfg = codec_manager->GetBroadcastConfig(requirements);
  ASSERT_EQ(2, cfg->GetNumBisTotal());
  ASSERT_EQ(2, cfg->GetNumChannelsMax());
  ASSERT_EQ(48000u, cfg->GetSamplingFrequencyHzMax());
  ASSERT_EQ(10000u, cfg->GetSduIntervalUs());
  ASSERT_EQ(100u, cfg->GetMaxSduOctets());
  ASSERT_EQ(1lu, cfg->subgroups.size());
  ASSERT_EQ(2lu, cfg->subgroups.at(0).GetNumBis());
  ASSERT_EQ(2lu, cfg->subgroups.at(0).GetNumChannelsTotal());

  ASSERT_EQ(2lu, cfg->subgroups.at(0).GetBisCodecConfigs().at(0).GetNumBis());
  ASSERT_EQ(2lu, cfg->subgroups.at(0).GetBisCodecConfigs().at(0).GetNumChannels());
  ASSERT_EQ(1lu, cfg->subgroups.at(0).GetBisCodecConfigs().at(0).GetNumChannelsPerBis());

  // Clean up the before testing any other offload capabilities.
  codec_manager->Stop();
}

TEST_F(CodecManagerTestAdsp, test_update_broadcast_offloader) {
  static const types::CodecConfigSetting bc_lc3_48_2 = {
          .id = kLeAudioCodecIdLc3,
          .params = types::LeAudioLtvMap({
                  LTV_ENTRY_SAMPLING_FREQUENCY(codec_spec_conf::kLeAudioSamplingFreq48000Hz),
                  LTV_ENTRY_FRAME_DURATION(codec_spec_conf::kLeAudioCodecFrameDur10000us),
                  LTV_ENTRY_AUDIO_CHANNEL_ALLOCATION(codec_spec_conf::kLeAudioLocationStereo),
                  LTV_ENTRY_OCTETS_PER_CODEC_FRAME(100),
          }),
          .channel_count_per_iso_stream = 2,
  };
  std::vector<AudioSetConfiguration> offload_capabilities = {{
          .name = "Test_Broadcast_Config_For_Offloader",
          .confs = {.sink = {types::AseConfiguration(bc_lc3_48_2),
                             types::AseConfiguration(bc_lc3_48_2)},
                    .source = {}},
  }};
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  CodecManager::BroadcastConfigurationRequirements requirements = {
          .subgroup_quality = {{types::LeAudioContextType::MEDIA, 1}}};
  codec_manager->GetBroadcastConfig(requirements);

  bool was_called = false;
  bluetooth::le_audio::broadcast_offload_config bcast_config;
  codec_manager->UpdateBroadcastConnHandle(
          {0x0001, 0x0002}, [&](const bluetooth::le_audio::broadcast_offload_config& config) {
            was_called = true;
            bcast_config = config;
          });

  // Expect a call for ADSP encoding
  ASSERT_TRUE(was_called);
  ASSERT_EQ(2lu, bcast_config.stream_map.size());
  ASSERT_EQ(16, bcast_config.bits_per_sample);
  ASSERT_EQ(48000lu, bcast_config.sampling_rate);
  ASSERT_EQ(10000lu, bcast_config.frame_duration);
  ASSERT_EQ(100u, bcast_config.octets_per_frame);
  ASSERT_EQ(1u, bcast_config.blocks_per_sdu);
  ASSERT_NE(0u, bcast_config.retransmission_number);
  ASSERT_NE(0u, bcast_config.max_transport_latency);
}

/*----------------- HOST codec manager tests ------------------*/
class CodecManagerTestHost : public CodecManagerTestBase {
public:
  virtual void SetUp() override {
    // Enable the HW offloader
    osi_property_set_bool(kPropLeAudioOffloadSupported, false);
    osi_property_set_bool(kPropLeAudioOffloadDisabled, false);

    // Allow for bidir SWB configurations
    osi_property_set_bool(kPropLeAudioBidirSwbSupported, true);

    // Codec extensibility disabled by default
    osi_property_set_bool(kPropLeAudioCodecExtensibility, false);

    CodecManagerTestBase::SetUp();
  }
};

class CodecManagerTestHostNoSwb : public CodecManagerTestBase {
public:
  virtual void SetUp() override {
    // Enable the HW offloader
    osi_property_set_bool(kPropLeAudioOffloadSupported, true);
    osi_property_set_bool(kPropLeAudioOffloadDisabled, false);

    // Do not allow for bidir SWB configurations
    osi_property_set_bool(kPropLeAudioBidirSwbSupported, false);

    // Codec extensibility disabled by default
    osi_property_set_bool(kPropLeAudioCodecExtensibility, false);

    CodecManagerTestBase::SetUp();
  }
};

TEST_F(CodecManagerTestHost, test_init) { ASSERT_EQ(codec_manager, CodecManager::GetInstance()); }

TEST_F(CodecManagerTestHost, test_audio_session_update) {
  ASSERT_EQ(codec_manager, CodecManager::GetInstance());

  auto unicast_source = LeAudioSourceAudioHalClient::AcquireUnicast();
  auto unicast_sink = LeAudioSinkAudioHalClient::AcquireUnicast();
  auto broadcast_source = LeAudioSourceAudioHalClient::AcquireBroadcast();

  // codec manager not started
  ASSERT_FALSE(codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(),
                                                                unicast_sink.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(),
                                                                unicast_sink.get(), false));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), false));

  std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);

  // Start codec manager
  codec_manager->Start(offloading_preference);

  ASSERT_TRUE(codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(),
                                                               unicast_sink.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(),
                                                                unicast_sink.get(), true));
  ASSERT_TRUE(codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(),
                                                               unicast_sink.get(), false));
  ASSERT_TRUE(
          codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(), nullptr, true));
  ASSERT_TRUE(codec_manager->UpdateActiveUnicastAudioHalClient(nullptr, unicast_sink.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveUnicastAudioHalClient(nullptr, nullptr, false));
  ASSERT_FALSE(codec_manager->UpdateActiveUnicastAudioHalClient(nullptr, nullptr, true));
  ASSERT_TRUE(codec_manager->UpdateActiveUnicastAudioHalClient(nullptr, unicast_sink.get(), false));
  ASSERT_TRUE(
          codec_manager->UpdateActiveUnicastAudioHalClient(unicast_source.get(), nullptr, false));

  ASSERT_TRUE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), true));
  ASSERT_TRUE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), false));
  ASSERT_TRUE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(broadcast_source.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(unicast_source.get(), true));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(unicast_source.get(), false));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(nullptr, false));
  ASSERT_FALSE(codec_manager->UpdateActiveBroadcastAudioHalClient(nullptr, true));
}

TEST_F(CodecManagerTestHost, test_start) {
  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(0);
  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(0);

  // Verify data path is NOT reset on Stop() for the Host encoding session
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER, kIsoDataPathHci, _))
          .Times(0);
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST, kIsoDataPathHci, _))
          .Times(0);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  ASSERT_EQ(codec_manager->GetCodecLocation(), CodecLocation::HOST);
}

TEST_F(CodecManagerTestHost, test_non_bidir_swb) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  // NON-SWB configs
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_48_2), types::AseConfiguration(lc3_48_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_48_2),
                               types::AseConfiguration(lc3_48_2)}},
  }));

  // NON-DUAL-SWB configs
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));

  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_24_2), types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_24_2),
                               types::AseConfiguration(lc3_24_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_48_2), types::AseConfiguration(lc3_48_2)}},
  }));
  ASSERT_FALSE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.source = {types::AseConfiguration(lc3_48_2),
                               types::AseConfiguration(lc3_48_2)}},
  }));
}

TEST_F(CodecManagerTestHost, test_dual_bidir_swb) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  // Single Dev BiDir SWB configs
  ASSERT_TRUE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_TRUE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_48_2), types::AseConfiguration(lc3_48_2)},
                    .source = {types::AseConfiguration(lc3_32_2),
                               types::AseConfiguration(lc3_32_2)}},
  }));
  ASSERT_TRUE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_32_2), types::AseConfiguration(lc3_32_2)},
                    .source = {types::AseConfiguration(lc3_48_2),
                               types::AseConfiguration(lc3_48_2)}},
  }));
  ASSERT_TRUE(codec_manager->CheckCodecConfigIsDualBiDirSwb({
          .confs = {.sink = {types::AseConfiguration(lc3_48_2), types::AseConfiguration(lc3_48_2)},
                    .source = {types::AseConfiguration(lc3_48_2),
                               types::AseConfiguration(lc3_48_2)}},
  }));
}

TEST_F(CodecManagerTestHost, test_dual_bidir_swb_supported) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  int num_of_dual_bidir_swb_configs = 0;
  for (auto context : types::kLeAudioContextAllTypesArray) {
    bool got_null_cfgs_container = false;
    auto ptr = codec_manager->GetCodecConfig(
            {.audio_context_type = context},
            [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
                const types::AudioSetConfigurations* confs)
                    -> std::unique_ptr<types::AudioSetConfiguration> {
              if (confs == nullptr) {
                got_null_cfgs_container = true;
              } else {
                num_of_dual_bidir_swb_configs +=
                        std::count_if(confs->begin(), confs->end(), [&](auto const& cfg) {
                          bool is_bidir = codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                          return codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                        });
              }
              // In this case the chosen configuration doesn't matter - select none
              return nullptr;
            });
    ASSERT_FALSE(got_null_cfgs_container);
  }

  // Make sure some dual bidir SWB configs were returned
  ASSERT_NE(0, num_of_dual_bidir_swb_configs);
}

TEST_F(CodecManagerTestAdsp, test_dual_bidir_swb_supported) {
  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities = {
          {
                  .name = "Test_Bidir_SWB_Config_No_Dev_lc3_32_2",
                  .confs = {.sink = {types::AseConfiguration(lc3_32_2),
                                     types::AseConfiguration(lc3_32_2)},
                            .source = {types::AseConfiguration(lc3_32_2),
                                       types::AseConfiguration(lc3_32_2)}},
          },
          {
                  .name = "Test_Bidir_Non_SWB_Config_No_Dev_lc3_16_2",
                  .confs = {.sink = {types::AseConfiguration(lc3_16_2),
                                     types::AseConfiguration(lc3_16_2)},
                            .source = {types::AseConfiguration(lc3_16_2),
                                       types::AseConfiguration(lc3_16_2)}},
          }};
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  int num_of_dual_bidir_swb_configs = 0;
  for (auto context : types::kLeAudioContextAllTypesArray) {
    bool got_null_cfgs_container = false;
    auto ptr = codec_manager->GetCodecConfig(
            {.audio_context_type = context},
            [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
                const types::AudioSetConfigurations* confs)
                    -> std::unique_ptr<types::AudioSetConfiguration> {
              if (confs == nullptr) {
                got_null_cfgs_container = true;
              } else {
                num_of_dual_bidir_swb_configs +=
                        std::count_if(confs->begin(), confs->end(), [&](auto const& cfg) {
                          bool is_bidir = codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                          return codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                        });
              }
              // In this case the chosen configuration doesn't matter - select none
              return nullptr;
            });
    ASSERT_FALSE(got_null_cfgs_container);
  }

  // Make sure some dual bidir SWB configs were returned
  ASSERT_NE(0, num_of_dual_bidir_swb_configs);
}

TEST_F(CodecManagerTestHostNoSwb, test_dual_bidir_swb_not_supported) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  int num_of_dual_bidir_swb_configs = 0;
  for (auto context : types::kLeAudioContextAllTypesArray) {
    bool got_null_cfgs_container = false;
    auto ptr = codec_manager->GetCodecConfig(
            {.audio_context_type = context},
            [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
                const types::AudioSetConfigurations* confs)
                    -> std::unique_ptr<types::AudioSetConfiguration> {
              if (confs == nullptr) {
                got_null_cfgs_container = true;
              } else {
                num_of_dual_bidir_swb_configs +=
                        std::count_if(confs->begin(), confs->end(), [&](auto const& cfg) {
                          return codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                        });
              }
              // In this case the chosen configuration doesn't matter - select none
              return nullptr;
            });
    ASSERT_FALSE(got_null_cfgs_container);
  }

  // Make sure no dual bidir SWB configs were returned
  ASSERT_EQ(0, num_of_dual_bidir_swb_configs);
}

TEST_F(CodecManagerTestAdspNoSwb, test_dual_bidir_swb_not_supported) {
  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities = {
          {
                  .name = "Test_Bidir_SWB_Config_No_Dev_lc3_32_2",
                  .confs = {.sink = {types::AseConfiguration(lc3_32_2),
                                     types::AseConfiguration(lc3_32_2)},
                            .source = {types::AseConfiguration(lc3_32_2),
                                       types::AseConfiguration(lc3_32_2)}},
          },
          {
                  .name = "Test_Bidir_Non_SWB_Config_No_Dev_lc3_16_2",
                  .confs = {.sink = {types::AseConfiguration(lc3_16_2),
                                     types::AseConfiguration(lc3_16_2)},
                            .source = {types::AseConfiguration(lc3_16_2),
                                       types::AseConfiguration(lc3_16_2)}},
          }};
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  int num_of_dual_bidir_swb_configs = 0;
  for (auto context : types::kLeAudioContextAllTypesArray) {
    bool got_null_cfgs_container = false;
    auto ptr = codec_manager->GetCodecConfig(
            {.audio_context_type = context},
            [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
                const types::AudioSetConfigurations* confs)
                    -> std::unique_ptr<types::AudioSetConfiguration> {
              if (confs == nullptr) {
                got_null_cfgs_container = true;
              } else {
                num_of_dual_bidir_swb_configs +=
                        std::count_if(confs->begin(), confs->end(), [&](auto const& cfg) {
                          return codec_manager->CheckCodecConfigIsDualBiDirSwb(*cfg);
                        });
              }
              // In this case the chosen configuration doesn't matter - select none
              return nullptr;
            });
    ASSERT_FALSE(got_null_cfgs_container);
  }

  // Make sure no dual bidir SWB configs were returned
  ASSERT_EQ(0, num_of_dual_bidir_swb_configs);
}

TEST_F(CodecManagerTestHost, test_dont_update_broadcast_offloader) {
  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  bool was_called = false;
  codec_manager->UpdateBroadcastConnHandle(
          {0x0001, 0x0002}, [&](const bluetooth::le_audio::broadcast_offload_config& /*config*/) {
            was_called = true;
          });

  // Expect no call for HOST encoding
  ASSERT_FALSE(was_called);
}

TEST_F(CodecManagerTestHost, test_dont_call_hal_for_config) {
  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);
  codec_manager->UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                   mock_le_audio_sink_hal_client_, true);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, GetUnicastConfig(_)).Times(0);
  codec_manager->GetCodecConfig(
          {.audio_context_type = types::LeAudioContextType::MEDIA},
          [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
              const types::AudioSetConfigurations* /*confs*/)
                  -> std::unique_ptr<types::AudioSetConfiguration> {
            // In this case the chosen configuration doesn't matter - select none
            return nullptr;
          });
}

TEST_F(CodecManagerTestAdsp, testStreamConfigurationVendor) {
  com::android::bluetooth::flags::provider_->leaudio_mono_location_errata(true);
  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  // Current CIS configuration
  std::vector<struct types::cis> cises{
          // One earbud disconnected
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 96,
                  .addr = GetTestAddress(1),
          },
          // Second earbud connected
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 97,
                  .addr = GetTestAddress(1),
          },
  };

  std::vector<uint8_t> metadata_vec;
  AppendMetadataLtvEntryForStreamingContext(metadata_vec,
                                            types::AudioContexts(types::LeAudioContextType::GAME));

  stream_map_info stream_map_info_sink_left(cises[0].conn_handle,
                                            codec_spec_conf::kLeAudioLocationFrontLeft, false);
  stream_map_info_sink_left.codec_config = vendor_code_48_2;
  stream_map_info_sink_left.target_latency = 0x03;
  stream_map_info_sink_left.target_phy = PHY_LE_2M;
  stream_map_info_sink_left.address = cises[1].addr;
  stream_map_info_sink_left.address_type = BLE_ADDR_PUBLIC;
  stream_map_info_sink_left.metadata.Parse(metadata_vec.data(), metadata_vec.size());

  stream_map_info stream_map_info_sink_right(cises[1].conn_handle,
                                             codec_spec_conf::kLeAudioLocationFrontRight, true);
  stream_map_info_sink_right.codec_config = vendor_code_48_2;
  stream_map_info_sink_right.target_latency = 0x03;
  stream_map_info_sink_right.target_phy = PHY_LE_2M;
  stream_map_info_sink_right.address = cises[1].addr;
  stream_map_info_sink_right.address_type = BLE_ADDR_PUBLIC;
  stream_map_info_sink_right.metadata.Parse(metadata_vec.data(), metadata_vec.size());

  stream_map_info stream_map_info_source_right(cises[1].conn_handle,
                                               codec_spec_conf::kLeAudioLocationFrontRight, true);
  stream_map_info_source_right.codec_config = vendor_code_48_2;
  stream_map_info_source_right.target_latency = 0x03;
  stream_map_info_source_right.target_phy = PHY_LE_2M;
  stream_map_info_source_right.address = cises[1].addr;
  stream_map_info_source_right.address_type = BLE_ADDR_PUBLIC;
  stream_map_info_source_right.metadata.Parse(metadata_vec.data(), metadata_vec.size());

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontRight,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info_sink_right},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 48000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 100,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 2,
                          .num_of_devices = 1,
                  },
          .source =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontRight,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_info_source_right},
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 48000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 100,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 1,
                          .num_of_devices = 1,
                  },
  };

  ASSERT_TRUE(
          codec_manager->UpdateCisConfiguration(cises, stream_params.sink, kLeAudioDirectionSink));
  ASSERT_TRUE(codec_manager->UpdateCisConfiguration(cises, stream_params.source,
                                                    kLeAudioDirectionSource));

  // Verify the offloader config content
  types::BidirectionalPair<std::optional<stream_config>> out_offload_configs;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect the same configuration for sink and source
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    uint32_t allocation = 0;
    auto& config = out_offload_configs.get(direction).value();

    ASSERT_EQ(2lu, config.stream_map.size());

    for (const auto& info : config.stream_map) {
      if (info.stream_handle == 96) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationFrontLeft, info.audio_channel_allocation);
        // The disconnected should be inactive
        ASSERT_FALSE(info.is_stream_active);

      } else if (info.stream_handle == 97) {
        ASSERT_EQ(codec_spec_conf::kLeAudioLocationFrontRight, info.audio_channel_allocation);
        // The connected should be active
        ASSERT_TRUE(info.is_stream_active);

        ASSERT_EQ(vendor_code_48_2.id, info.codec_config.id);
        ASSERT_EQ(vendor_code_48_2.params, info.codec_config.params);
        ASSERT_EQ(vendor_code_48_2.vendor_params, info.codec_config.vendor_params);
        ASSERT_EQ(0x03, info.target_latency);
        ASSERT_EQ(PHY_LE_2M, info.target_phy);
        ASSERT_EQ(cises[1].addr, info.address);
        ASSERT_EQ(BLE_ADDR_PUBLIC, info.address_type);
        ASSERT_EQ(stream_map_info_sink_right.metadata, info.metadata);

      } else {
        ASSERT_EQ(97, info.stream_handle);
      }
      allocation |= info.audio_channel_allocation;
    }

    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(48000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(100u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
    ASSERT_EQ(codec_spec_conf::kLeAudioLocationStereo, allocation);
  }

  // Clear the CIS configuration map (no active CISes).
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSink);
  codec_manager->ClearCisConfiguration(kLeAudioDirectionSource);
  out_offload_configs.sink = std::nullopt;
  out_offload_configs.source = std::nullopt;
  codec_manager->UpdateActiveAudioConfig(
          stream_params, [&out_offload_configs](const stream_config& config, uint8_t direction) {
            out_offload_configs.get(direction) = config;
          });

  // Expect sink & source configurations with empty CIS channel allocation map.
  ASSERT_TRUE(out_offload_configs.sink.has_value());
  ASSERT_TRUE(out_offload_configs.source.has_value());
  for (auto direction : {bluetooth::le_audio::types::kLeAudioDirectionSink,
                         bluetooth::le_audio::types::kLeAudioDirectionSource}) {
    auto& config = out_offload_configs.get(direction).value();
    ASSERT_EQ(0lu, config.stream_map.size());
    ASSERT_EQ(16, config.bits_per_sample);
    ASSERT_EQ(48000u, config.sampling_frequency_hz);
    ASSERT_EQ(10000u, config.frame_duration_us);
    ASSERT_EQ(100u, config.octets_per_codec_frame);
    ASSERT_EQ(1, config.codec_frames_blocks_per_sdu);
    ASSERT_EQ(44, config.peer_delay_ms);
  }
}

}  // namespace bluetooth::le_audio
