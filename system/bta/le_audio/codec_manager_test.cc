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

#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include "audio_hal_client/audio_hal_client.h"
#include "audio_hal_interface/le_audio_software.h"
#include "hci/controller_mock.h"
#include "hci/hci_packets.h"
#include "internal_include/stack_config.h"
#include "le_audio/gmap_client.h"
#include "le_audio/gmap_server.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_set_configuration_provider.h"
#include "osi/include/properties.h"
#include "stack/mock/mock_stack_hcic_layer.h"
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

std::optional<bluetooth::le_audio::ProviderInfo> provider_info = std::nullopt;

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
};

const stack_config_t* stack_config_get_interface(void) { return &mock_stack_config; }

namespace bluetooth::audio::le_audio {
OffloadCapabilities get_offload_capabilities() {
  return {*offload_capabilities, *offload_capabilities};
}
std::optional<bluetooth::le_audio::ProviderInfo> LeAudioClientInterface::GetCodecConfigProviderInfo(
        void) const {
  return provider_info;
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
  MOCK_METHOD((void), StreamSuspended, (), (override));
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay), (override));
  MOCK_METHOD((void), UpdateAudioConfigToHal, (const ::bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD((void), SetCodecPriority,
              (const ::bluetooth::le_audio::types::LeAudioCodecId& codecId, int32_t priority),
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
  MOCK_METHOD((void), StreamSuspended, (), (override));
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay), (override));
  MOCK_METHOD((void), UpdateAudioConfigToHal, (const ::bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD((void), SetCodecPriority,
              (const ::bluetooth::le_audio::types::LeAudioCodecId& codecId, int32_t priority),
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

static const types::LeAudioCodecId kLeAudioCodecIdVendor_Opus = {
        .coding_format = types::kLeAudioCodingFormatVendorSpecific,
        .vendor_company_id = types::kLeAudioVendorCompanyIdGoogle,
        .vendor_codec_id = 0x0001};

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
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
  return RawAddress(bytes);
}

static auto PrepareStackProviderInfo(bool is_encoding, bool with_vendor, bool opus) {
  ProviderInfo stack_provider_info;
  std::vector<ProviderInfo::CentralCodecInfo>* central_codec_infos;

  if (is_encoding) {
    central_codec_infos = &stack_provider_info.encoding_codec_configs;
  } else {
    central_codec_infos = &stack_provider_info.decoding_codec_configs;
  }

  ProviderInfo::CentralCodecInfo lc3_codec_info = {.codec_id = kLeAudioCodecIdLc3};
  lc3_codec_info.supported_configs.push_back({.sample_freq = 16000,
                                              .frame_duration = 7500,
                                              .channel_count = 1,
                                              .bits_per_sample = 16});
  lc3_codec_info.supported_configs.push_back({
          .sample_freq = 48000,
          .frame_duration = 10000,
          .channel_count = 1,
          .bits_per_sample = 16,
  });
  central_codec_infos->push_back(lc3_codec_info);

  if (with_vendor) {
    stack_provider_info.isMulticodecSupported = true;
    ProviderInfo::CentralCodecInfo vs_codec_info = {.codec_id = kLeAudioCodecIdVendor_C0DE};
    vs_codec_info.supported_configs.push_back({.sample_freq = 16000,
                                               .frame_duration = 7500,
                                               .channel_count = 1,
                                               .bits_per_sample = 16});
    vs_codec_info.supported_configs.push_back({.sample_freq = 48000,
                                               .frame_duration = 10000,
                                               .channel_count = 1,
                                               .bits_per_sample = 16});
    central_codec_infos->push_back(vs_codec_info);
  }

  if (opus) {
    stack_provider_info.isMulticodecSupported = true;
    ProviderInfo::CentralCodecInfo opus_codec_info = {.codec_id = kLeAudioCodecIdVendor_Opus};
    opus_codec_info.supported_configs.push_back({.sample_freq = 48000,
                                                 .frame_duration = 7500,
                                                 .channel_count = 1,
                                                 .bits_per_sample = 16});
    opus_codec_info.supported_configs.push_back({.sample_freq = 96000,
                                                 .frame_duration = 10000,
                                                 .channel_count = 1,
                                                 .bits_per_sample = 16});
    central_codec_infos->push_back(opus_codec_info);
  }

  return stack_provider_info;
}

class CodecManagerTest : public ::testing::TestWithParam<std::vector<const char*>> {
public:
  virtual void SetUp() override {
    osi_property_set_bool(kPropLeAudioOffloadSupported, false);
    osi_property_set_bool(kPropLeAudioOffloadDisabled, false);
    osi_property_set_bool(kPropLeAudioBidirSwbSupported, false);
    osi_property_set_bool(kPropLeAudioCodecExtensibility, false);

    properties_ = GetParam();
    for (auto const* prop : properties_) {
      log::error("Set prop: {}", prop);
      osi_property_set_bool(prop, true);
    }

    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    com_android_bluetooth_flags_reset_flags();
    set_com_android_bluetooth_flags_leaudio_codec_id_support(true);
    set_mock_offload_capabilities(offload_capabilities_none);

    hcic::SetMockHcicInterface(&legacy_hci_mock_);

    bluetooth::hci::testing::mock_controller_ =
            std::make_unique<NiceMock<bluetooth::hci::testing::MockController>>();
    ON_CALL(*bluetooth::hci::testing::mock_controller_, SupportsBleIsochronousBroadcaster)
            .WillByDefault(Return(true));
    ON_CALL(*bluetooth::hci::testing::mock_controller_, IsSupported(OpCode::CONFIGURE_DATA_PATH))
            .WillByDefault(Return(true));

    codec_manager = CodecManager::GetInstance();
    provider_info = std::nullopt;

    RegisterSourceHalClientMock();
    RegisterSinkHalClientMock();
  }

  virtual void TearDown() override {
    /* It is essential to clear expectations on mocks that are not owned by
     * the test fixture.
     */
    if (mock_le_audio_source_hal_client_) {
      Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);
    }
    owned_mock_le_audio_source_hal_client_.reset();
    mock_le_audio_source_hal_client_ = nullptr;

    if (mock_broadcast_le_audio_source_hal_client_) {
      Mock::VerifyAndClearExpectations(mock_broadcast_le_audio_source_hal_client_);
    }
    owned_mock_broadcast_le_audio_source_hal_client_.reset();
    mock_broadcast_le_audio_source_hal_client_ = nullptr;

    if (mock_le_audio_sink_hal_client_) {
      Mock::VerifyAndClearExpectations(mock_le_audio_sink_hal_client_);
    }
    owned_mock_le_audio_sink_hal_client_.reset();
    mock_le_audio_sink_hal_client_ = nullptr;

    codec_manager->Stop();
    bluetooth::hci::testing::mock_controller_.release();
  }

  std::vector<const char*> properties_;
  CodecManager* codec_manager;
  hcic::MockHcicInterface legacy_hci_mock_;

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

TEST_P(CodecManagerTest, test_init) { ASSERT_EQ(codec_manager, CodecManager::GetInstance()); }

TEST_P(CodecManagerTest, test_start) {
  bool is_using_adsp = true;
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    is_using_adsp = false;
  }

  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(is_using_adsp ? 1 : 0);
  EXPECT_CALL(legacy_hci_mock_, ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST,
                                                  kIsoDataPathPlatformDefault, _))
          .Times(is_using_adsp ? 1 : 0);

  // Verify data path is reset on Stop()
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER, kIsoDataPathHci, _))
          .Times(is_using_adsp ? 1 : 0);
  EXPECT_CALL(legacy_hci_mock_,
              ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST, kIsoDataPathHci, _))
          .Times(is_using_adsp ? 1 : 0);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  ASSERT_EQ(codec_manager->GetCodecLocation(),
            is_using_adsp ? CodecLocation::ADSP : CodecLocation::HOST);
}

TEST_P(CodecManagerTest, testStreamConfigurationAdspDownMix) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_configuration_update_cis_disconnected) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference(0);
  codec_manager->Start(offloading_preference);

  /* Scenario:
   * 1. There are two devices and two unidirectional CISes
   * 2. Call UpdateActiveAudioConfig twice and make sure only once the `update_receiver` is called.
   * 3. One device gets disconnected and CISes are updated
   * 4. Call UpdateActiveAudioConfig twice and make sure only once the `update_receiver` is called.
   * 5. Reconnect device and update CISes
   * 6. Call UpdateActiveAudioConfig twice and make sure only once the `update_receiver` is called.
   */

  // Current CIS configuration for two earbuds
  std::vector<struct types::cis> cises_both_connected{
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_UNIDIRECTIONAL_SINK,
                  .conn_handle = 96,
          },
          {
                  .id = 0x01,
                  .type = types::CisType::CIS_TYPE_UNIDIRECTIONAL_SINK,
                  .conn_handle = 97,
          },
  };

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params_both_connected{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontLeft |
                                                      codec_spec_conf::kLeAudioLocationFrontRight,
                          .stream_config =
                                  {
                                          .stream_map =
                                                  {
                                                          stream_map_info(
                                                                  96,
                                                                  codec_spec_conf::
                                                                          kLeAudioLocationFrontLeft,
                                                                  true),
                                                          stream_map_info(
                                                                  97,
                                                                  codec_spec_conf::
                                                                          kLeAudioLocationFrontRight,
                                                                  true),
                                                  },
                                          .bits_per_sample = 16,
                                          .sampling_frequency_hz = 16000,
                                          .frame_duration_us = 10000,
                                          .octets_per_codec_frame = 40,
                                          .codec_frames_blocks_per_sdu = 1,
                                          .peer_delay_ms = 44,
                                  },
                          .num_of_channels = 2,
                          .num_of_devices = 2,
                  },
  };

  codec_manager->UpdateCisConfiguration(cises_both_connected, stream_params_both_connected.sink,
                                        kLeAudioDirectionSink);

  // Verify the offloader config content
  types::BidirectionalPair<int> number_of_calls = {0, 0};
  codec_manager->UpdateActiveAudioConfig(
          stream_params_both_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });

  codec_manager->UpdateActiveAudioConfig(
          stream_params_both_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });

  // Expect sink & source configurations with empty CIS channel allocation map.
  ASSERT_EQ(number_of_calls.sink, 1);
  ASSERT_EQ(number_of_calls.source, 0);

  /* Disconnect first CIS */
  std::vector<struct types::cis> cises_one_connected{
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_UNIDIRECTIONAL_SINK,
                  .conn_handle = 96,
          },
  };

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params_one_connected{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontLeft,
                          .stream_config =
                                  {
                                          .stream_map =
                                                  {
                                                          stream_map_info(
                                                                  96,
                                                                  codec_spec_conf::
                                                                          kLeAudioLocationFrontLeft,
                                                                  true),
                                                  },
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

  codec_manager->UpdateCisConfiguration(cises_one_connected, stream_params_one_connected.sink,
                                        kLeAudioDirectionSink);

  codec_manager->UpdateActiveAudioConfig(
          stream_params_one_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });

  // Expect sink & source configurations with empty CIS channel allocation map.
  ASSERT_EQ(number_of_calls.sink, 2);
  ASSERT_EQ(number_of_calls.source, 0);

  // Call again and check that Audio HAL (callback) is not called as configuration was already
  // notified
  codec_manager->UpdateActiveAudioConfig(
          stream_params_one_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });
  ASSERT_EQ(number_of_calls.sink, 2);
  ASSERT_EQ(number_of_calls.source, 0);

  // Update CISes for bidirectional case
  std::vector<struct types::cis> cises_bidirectional_connected{
          {
                  .id = 0x00,
                  .type = types::CisType::CIS_TYPE_BIDIRECTIONAL,
                  .conn_handle = 96,
          },
  };

  // Stream parameters
  types::BidirectionalPair<stream_parameters> stream_params_bidirectional_connected{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationFrontLeft,
                          .stream_config =
                                  {
                                          .stream_map =
                                                  {
                                                          stream_map_info(
                                                                  96,
                                                                  codec_spec_conf::
                                                                          kLeAudioLocationFrontLeft,
                                                                  true),
                                                  },
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
                                          .stream_map =
                                                  {
                                                          stream_map_info(
                                                                  96,
                                                                  codec_spec_conf::
                                                                          kLeAudioLocationFrontLeft,
                                                                  true),
                                                  },
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

  codec_manager->UpdateCisConfiguration(cises_bidirectional_connected,
                                        stream_params_bidirectional_connected.sink,
                                        kLeAudioDirectionSink);
  codec_manager->UpdateCisConfiguration(cises_bidirectional_connected,
                                        stream_params_bidirectional_connected.source,
                                        kLeAudioDirectionSource);

  codec_manager->UpdateActiveAudioConfig(
          stream_params_bidirectional_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });

  ASSERT_EQ(number_of_calls.sink, 3);
  ASSERT_EQ(number_of_calls.source, 1);

  // Call again and check that Audio HAL (callback) is not called as configuration was already
  // notified
  codec_manager->UpdateActiveAudioConfig(
          stream_params_bidirectional_connected,
          [&number_of_calls](const stream_config& /*config*/, uint8_t direction) {
            number_of_calls.get(direction)++;
          });
  ASSERT_EQ(number_of_calls.sink, 3);
  ASSERT_EQ(number_of_calls.source, 1);
}

TEST_P(CodecManagerTest, testStreamConfigurationMono) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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
  auto stream_map_entry_mono_bidir =
          stream_map_info(97, codec_spec_conf::kLeAudioLocationMonoAudio, true);
  stream_map_entry_mono_bidir.codec_config.id = kLeAudioCodecIdLc3;
  types::BidirectionalPair<stream_parameters> stream_params{
          .sink =
                  {
                          .audio_channel_allocation = codec_spec_conf::kLeAudioLocationMonoAudio,
                          .stream_config =
                                  {
                                          .stream_map = {stream_map_entry_mono_bidir},
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
                                          .stream_map = {stream_map_entry_mono_bidir},
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
        ASSERT_EQ(info.codec_config.id.coding_format, kLeAudioCodecIdLc3.coding_format);

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

TEST_P(CodecManagerTest, test_capabilities_none) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_capabilities) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  // Run only on SWB supporting test suite to check those as well
  if (osi_property_get_bool(kPropLeAudioBidirSwbSupported, false) == false) {
    GTEST_SKIP();
  }

  for (auto test_context : ::bluetooth::le_audio::types::kLeAudioContextAllTypesArray) {
    // Build the offloader capabilities vector using the configuration provider
    // in HOST mode to get all the .json file configuration entries.
    std::vector<AudioSetConfiguration> offload_capabilities;
    AudioSetConfigurationProvider::Initialize(bluetooth::le_audio::types::CodecLocation::HOST);
    auto all_local_configs = AudioSetConfigurationProvider::Get()->GetConfigurations(test_context);
    ASSERT_NE(0lu, all_local_configs->size());

    for (auto& cap : *all_local_configs) {
      // Note: If we run this test with SWB disabled, we should filter out those configs here
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

TEST_P(CodecManagerTest, test_broadcast_config) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_broadcast_config_with_source_capability) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

  // This configuration has both sink and source capabilities. Before the fix,
  // this would be ignored. After the fix, it should be considered a valid
  // broadcast capability based on its sink part.
  std::vector<AudioSetConfiguration> offload_capabilities = {{
          .name = "Test_Broadcast_Config_With_Source_lc3_48_2",
          .confs = {.sink = {types::AseConfiguration(bc_lc3_48_2),
                             types::AseConfiguration(bc_lc3_48_2)},
                    .source = {types::AseConfiguration(lc3_16_2)}},
  }};
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {
          {.codec_type = bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3}};
  codec_manager->Start(offloading_preference);

  CodecManager::BroadcastConfigurationRequirements requirements = {
          .subgroup_quality = {{types::LeAudioContextType::MEDIA, 1}}};
  auto cfg = codec_manager->GetBroadcastConfig(requirements);

  // Verify that the configuration was processed correctly
  ASSERT_NE(nullptr, cfg);
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

TEST_P(CodecManagerTest, test_update_broadcast_offloader) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_audio_session_update) {
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

TEST_P(CodecManagerTest, test_non_bidir_swb) {
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

TEST_P(CodecManagerTest, test_dual_bidir_swb) {
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

TEST_P(CodecManagerTest, test_dual_bidir_swb_supported) {
  if (osi_property_get_bool(kPropLeAudioBidirSwbSupported, false) == false) {
    // Skip test is SWB not supported
    GTEST_SKIP();
  }

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

  // Make sure some dual bidir SWB configs were returned
  ASSERT_NE(0, num_of_dual_bidir_swb_configs);
}

TEST_P(CodecManagerTest, test_dual_bidir_swb_not_supported) {
  if (osi_property_get_bool(kPropLeAudioBidirSwbSupported, false)) {
    // Skip if SWB enabled
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_dont_update_broadcast_offloader) {
  if (osi_property_get_bool(kPropLeAudioOffloadSupported, false)) {
    // Skip if offload unsupported or disabled
    if (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == false) {
      GTEST_SKIP();
    }
  }

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

TEST_P(CodecManagerTest, test_dont_call_hal_for_config) {
  if (osi_property_get_bool(kPropLeAudioOffloadSupported, false)) {
    // Skip if offload unsupported or disabled
    if (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == false) {
      GTEST_SKIP();
    }
  }

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

TEST_P(CodecManagerTest, test_hal_client_set_unset) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);
  codec_manager->UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                   mock_le_audio_sink_hal_client_, true);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, GetUnicastConfig(_)).Times(1);
  codec_manager->GetCodecConfig(
          {.audio_context_type = types::LeAudioContextType::MEDIA},
          [&](const CodecManager::UnicastConfigurationRequirements& /*requirements*/,
              const types::AudioSetConfigurations* /*confs*/)
                  -> std::unique_ptr<types::AudioSetConfiguration> {
            // In this case the chosen configuration doesn't matter - select none
            return nullptr;
          });
  Mock::VerifyAndClearExpectations(mock_le_audio_source_hal_client_);

  // Unset the hal client references and expect the call to be handled gracefully
  codec_manager->UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                   mock_le_audio_sink_hal_client_, false);

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

TEST_P(CodecManagerTest, testStreamConfigurationVendor) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

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

TEST_P(CodecManagerTest, test_notify_hal_with_empty_cis_handles_unsupported) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);
  codec_manager->UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                   mock_le_audio_sink_hal_client_, true);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(0);
  EXPECT_CALL(*mock_le_audio_sink_hal_client_, UpdateAudioConfigToHal(_)).Times(0);

  auto lc3_config = types::AudioSetConfiguration({
          .name = "Two-OneChan-SnkAse-Lc3_16_2-Two-OneChan-SrcAse-Lc3_16_2",
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  });
  codec_manager->UpdateSelectedCodecConfig(lc3_config);
}

TEST_P(CodecManagerTest, test_notify_hal_with_empty_cis_handles) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  provider_info = bluetooth::le_audio::ProviderInfo({.isMulticodecSupported = true});

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);
  codec_manager->UpdateActiveUnicastAudioHalClient(mock_le_audio_source_hal_client_,
                                                   mock_le_audio_sink_hal_client_, true);

  EXPECT_CALL(*mock_le_audio_source_hal_client_, UpdateAudioConfigToHal(_)).Times(1);

  auto lc3_config = types::AudioSetConfiguration({
          .name = "Two-OneChan-SnkAse-Lc3_16_2-Two-OneChan-SrcAse-Lc3_16_2",
          .confs = {.sink = {types::AseConfiguration(lc3_16_2), types::AseConfiguration(lc3_16_2)},
                    .source = {types::AseConfiguration(lc3_16_2),
                               types::AseConfiguration(lc3_16_2)}},
  });
  codec_manager->UpdateSelectedCodecConfig(lc3_config);
}

TEST_P(CodecManagerTest, test_vendor_specific_codec_config) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  provider_info = PrepareStackProviderInfo(true, true, false);

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);

  auto local_capa = codec_manager->GetLocalAudioOutputCodecCapa();
  bool is_vsc_supported = false;
  for (auto& capa : local_capa) {
    if (capa.codec_type == btle_audio_codec_index_t::LE_AUDIO_CODEC_INDEX_SOURCE_VENDOR_SPECIFIC) {
      is_vsc_supported = true;
      ASSERT_TRUE(kLeAudioCodecIdVendor_C0DE.getCodecIdRaw() == capa.codec_id);
      break;
    }
  }
  ASSERT_TRUE(is_vsc_supported);
}

TEST_P(CodecManagerTest, test_vendor_specific_codec_opus_config) {
  // Skip if offload unsupported or disabled
  if ((osi_property_get_bool(kPropLeAudioOffloadSupported, false) == false) ||
      (osi_property_get_bool(kPropLeAudioOffloadDisabled, false) == true)) {
    GTEST_SKIP();
  }

  osi_property_set_bool(kPropLeAudioCodecExtensibility, true);

  provider_info = PrepareStackProviderInfo(true, true, true);

  // Set the offloader capabilities
  std::vector<AudioSetConfiguration> offload_capabilities;
  set_mock_offload_capabilities(offload_capabilities);

  const std::vector<bluetooth::le_audio::btle_audio_codec_config_t> offloading_preference = {};
  codec_manager->Start(offloading_preference);

  auto local_capa = codec_manager->GetLocalAudioOutputCodecCapa();
  bool has_opus_high = false;
  bool has_opus = false;
  for (auto& capa : local_capa) {
    switch (capa.codec_type) {
      case btle_audio_codec_index_t::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS:
        has_opus = true;
        break;
      case btle_audio_codec_index_t::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS_HI_RES:
        has_opus_high = true;
        break;
      default:
        continue;
        break;
    }
  }

  ASSERT_TRUE(has_opus_high);
  ASSERT_TRUE(has_opus);
}

/*----------------- ADSP codec manager tests ------------------*/
INSTANTIATE_TEST_CASE_P(CodecManagerTestAdsp, CodecManagerTest,
                        ::testing::Values(std::vector<const char*>{kPropLeAudioOffloadSupported,
                                                                   kPropLeAudioBidirSwbSupported}));

INSTANTIATE_TEST_CASE_P(CodecManagerTestAdspNoSwb, CodecManagerTest,
                        ::testing::Values(std::vector<const char*>{kPropLeAudioOffloadSupported}));

/*----------------- HOST codec manager tests ------------------*/
INSTANTIATE_TEST_CASE_P(CodecManagerHostTest, CodecManagerTest,
                        ::testing::Values(std::vector<const char*>{kPropLeAudioBidirSwbSupported}));

INSTANTIATE_TEST_CASE_P(CodecManagerTestHostNoSwb, CodecManagerTest,
                        ::testing::Values(std::vector<const char*>{kPropLeAudioOffloadSupported}));

}  // namespace bluetooth::le_audio
