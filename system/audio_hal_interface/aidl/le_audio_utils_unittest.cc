/*
 * Copyright 2024 The Android Open Source Project
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

#include "le_audio_utils.h"

#include <bluetooth/log.h>
#include <gtest/gtest.h>

#include <cstdint>
#include <optional>
#include <tuple>
#include <vector>

#include "bta/le_audio/broadcaster/broadcaster_types.h"
#include "bta/le_audio/le_audio_types.h"
#include "btm_iso_api_types.h"

namespace bluetooth {
namespace {

using ::bluetooth::audio::aidl::GetAidlCodecIdFromStackFormat;
using ::bluetooth::audio::aidl::GetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat;
using ::bluetooth::audio::aidl::GetAidlLeAudioDeviceCapabilitiesFromStackFormat;
using ::bluetooth::audio::aidl::GetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat;
using ::bluetooth::audio::aidl::GetAidlMetadataFromStackFormat;
using ::bluetooth::audio::aidl::GetStackBisConfigFromAidlFormat;
using ::bluetooth::audio::aidl::GetStackBroadcastConfigurationFromAidlFormat;
using ::bluetooth::audio::aidl::GetStackCodecIdFromAidlFormat;
using ::bluetooth::audio::aidl::GetStackLeAudioLtvMapFromAidlFormat;
using ::bluetooth::audio::aidl::GetStackSubgroupsFromAidlFormat;
using ::bluetooth::audio::aidl::GetStackUnicastConfigurationFromAidlFormat;

/* LC3 Core Codec: BT Stack and matching AIDL types */
static const ::bluetooth::le_audio::types::LeAudioCodecId kStackCodecLc3 = {
        .coding_format = ::bluetooth::le_audio::types::kLeAudioCodingFormatLC3,
        .vendor_company_id = ::bluetooth::le_audio::types::kLeAudioVendorCompanyIdUndefined,
        .vendor_codec_id = ::bluetooth::le_audio::types::kLeAudioVendorCodecIdUndefined};
aidl::android::hardware::bluetooth::audio::CodecId::Core kAidlCodecLc3 =
        aidl::android::hardware::bluetooth::audio::CodecId::Core::LC3;

/* Vendor Codec: BT Stack and matching AIDL types */
static const ::bluetooth::le_audio::types::LeAudioCodecId kStackCodecVendor1 = {
        .coding_format = ::bluetooth::le_audio::types::kLeAudioCodingFormatVendorSpecific,
        .vendor_company_id = 0xC0DE,
        .vendor_codec_id = 0xF00D};
aidl::android::hardware::bluetooth::audio::CodecId::Vendor kAidlCodecVendor1{.id = 0xC0DE,
                                                                             .codecId = 0xF00D};

static const ::bluetooth::le_audio::types::LeAudioCodecId kStackCodecTransparent = {
        .coding_format = ::bluetooth::hci::kIsoCodingFormatTransparent,
        .vendor_company_id = ::bluetooth::le_audio::types::kLeAudioVendorCompanyIdUndefined,
        .vendor_codec_id = ::bluetooth::le_audio::types::kLeAudioVendorCodecIdUndefined};

namespace test_utils {

static auto PrepareStackMetadataLtv() {
  ::bluetooth::le_audio::types::LeAudioLtvMap metadata_ltvs;
  // Prepare the metadata LTVs
  metadata_ltvs
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypePreferredAudioContext,
               (uint16_t)10)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext, (uint16_t)8)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeProgramInfo,
               std::string{"ProgramInfo"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeLanguage, std::string{"ice"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeCcidList,
               std::vector<uint8_t>{1, 2, 3})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeparentalRating, (uint8_t)0x01)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeProgramInfoUri,
               std::string{"ProgramInfoUri"})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeAudioActiveState, false)
          .Add(::bluetooth::le_audio::types::
                       kLeAudioMetadataTypeBroadcastAudioImmediateRenderingFlag,
               true)
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeExtendedMetadata,
               std::vector<uint8_t>{1, 2, 3})
          .Add(::bluetooth::le_audio::types::kLeAudioMetadataTypeVendorSpecific,
               std::vector<uint8_t>{1, 2, 3});
  return metadata_ltvs;
}

static std::pair<
        std::vector<std::optional<::aidl::android::hardware::bluetooth::audio::MetadataLtv>>,
        bluetooth::le_audio::types::LeAudioLtvMap>
PrepareReferenceMetadata() {
  std::vector<std::optional<::aidl::android::hardware::bluetooth::audio::MetadataLtv>>
          aidl_metadata;
  bluetooth::le_audio::types::LeAudioLtvMap stack_metadata;

  aidl_metadata.push_back(
          ::aidl::android::hardware::bluetooth::audio::MetadataLtv::PreferredAudioContexts{
                  .values.bitmask =
                          ::aidl::android::hardware::bluetooth::audio::AudioContext::GAME |
                          ::aidl::android::hardware::bluetooth::audio::AudioContext::
                                  CONVERSATIONAL});
  stack_metadata.Add(bluetooth::le_audio::types::kLeAudioMetadataTypePreferredAudioContext,
                     (uint16_t)((bluetooth::le_audio::types::LeAudioContextType::GAME |
                                 bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL)
                                        .value()));

  aidl_metadata.push_back(
          ::aidl::android::hardware::bluetooth::audio::MetadataLtv::StreamingAudioContexts{
                  .values.bitmask =
                          ::aidl::android::hardware::bluetooth::audio::AudioContext::GAME});
  stack_metadata.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeStreamingAudioContext,
                     (uint16_t)(bluetooth::le_audio::types::LeAudioContextType::GAME));

  aidl_metadata.push_back(::aidl::android::hardware::bluetooth::audio::MetadataLtv::VendorSpecific{
          .companyId = 0x0201, .opaqueValue = {0x03}});
  stack_metadata.Add(bluetooth::le_audio::types::kLeAudioMetadataTypeVendorSpecific, 0x0201,
                     {0x03});

  return {aidl_metadata, stack_metadata};
}

static auto PrepareStackCapability(uint16_t capa_sampling_frequency, uint8_t capa_frame_duration,
                                   uint8_t audio_channel_counts, uint16_t octets_per_frame_min,
                                   uint16_t ocets_per_frame_max, uint8_t codec_frames_per_sdu) {
  uint32_t octets_per_frame_range = octets_per_frame_min | (ocets_per_frame_max << 16);

  return ::bluetooth::le_audio::types::LeAudioLtvMap({
          {::bluetooth::le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedSamplingFrequencies,
           UINT16_TO_VEC_UINT8(capa_sampling_frequency)},
          {::bluetooth::le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedFrameDurations,
           UINT8_TO_VEC_UINT8(capa_frame_duration)},
          {::bluetooth::le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts,
           UINT8_TO_VEC_UINT8(audio_channel_counts)},
          {::bluetooth::le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedOctetsPerCodecFrame,
           UINT32_TO_VEC_UINT8(octets_per_frame_range)},
          {::bluetooth::le_audio::codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu,
           UINT8_TO_VEC_UINT8(codec_frames_per_sdu)},
  });
}

static auto PrepareStackPacRecord(::bluetooth::le_audio::types::LeAudioCodecId codec_id,
                                  uint16_t capa_sampling_frequency, uint8_t capa_frame_duration,
                                  uint8_t audio_channel_counts, uint16_t octets_per_frame_min,
                                  uint16_t octets_per_frame_max, uint8_t codec_frames_per_sdu = 1) {
  auto ltv_map =
          PrepareStackCapability(capa_sampling_frequency, capa_frame_duration, audio_channel_counts,
                                 octets_per_frame_min, octets_per_frame_max, codec_frames_per_sdu);
  return ::bluetooth::le_audio::types::acs_ac_record({.codec_id = codec_id,
                                                      .codec_spec_caps = ltv_map,
                                                      .codec_spec_caps_raw = ltv_map.RawPacket(),
                                                      .metadata = PrepareStackMetadataLtv()});
}

std::pair<aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioDataPathConfiguration,
          ::bluetooth::le_audio::types::DataPathConfiguration>
PrepareReferenceLeAudioDataPathConfigurationVendor() {
  aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::LeAudioDataPathConfiguration
          config;
  config.dataPathId = 0xC0DEC0DE;
  config.dataPathConfiguration.configuration = std::vector<uint8_t>{0, 1, 2, 3};

  config.isoDataPathConfiguration.codecId = kAidlCodecVendor1;
  config.isoDataPathConfiguration.isTransparent = false;
  config.isoDataPathConfiguration.controllerDelayUs = 128;
  config.isoDataPathConfiguration.configuration = std::vector<uint8_t>();

  ::bluetooth::le_audio::types::DataPathConfiguration stack_config;
  stack_config.dataPathId = config.dataPathId;
  stack_config.dataPathConfig = *config.dataPathConfiguration.configuration;
  stack_config.isoDataPathConfig.codecId = config.isoDataPathConfiguration.isTransparent
                                                   ? kStackCodecTransparent
                                                   : kStackCodecVendor1;
  stack_config.isoDataPathConfig.isTransparent = config.isoDataPathConfiguration.isTransparent;
  stack_config.isoDataPathConfig.controllerDelayUs =
          config.isoDataPathConfiguration.controllerDelayUs;
  stack_config.isoDataPathConfig.configuration = *config.isoDataPathConfiguration.configuration;

  return {config, stack_config};
}

std::pair<aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioDataPathConfiguration,
          ::bluetooth::le_audio::types::DataPathConfiguration>
PrepareReferenceLeAudioDataPathConfigurationLc3() {
  auto config = ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioDataPathConfiguration{
                  .dataPathId = 0x01,  // kIsoDataPathPlatformDefault
                  // Empty vector
                  .dataPathConfiguration = {.configuration = {}},
                  .isoDataPathConfiguration =
                          {
                                  .codecId = kAidlCodecLc3,
                                  // Transparent - the controller does not encode/decode
                                  .isTransparent = true,
                                  // Irrelevant for the transparent ISO data path
                                  .controllerDelayUs = 0,
                                  // Empty for LC3 codec
                                  .configuration = std::nullopt,
                          },
          };

  ::bluetooth::le_audio::types::DataPathConfiguration stack_config;
  stack_config.dataPathId = config.dataPathId;
  stack_config.dataPathConfig = *config.dataPathConfiguration.configuration;
  stack_config.isoDataPathConfig.codecId =
          config.isoDataPathConfiguration.isTransparent ? kStackCodecTransparent : kStackCodecLc3;
  stack_config.isoDataPathConfig.isTransparent = config.isoDataPathConfiguration.isTransparent;
  stack_config.isoDataPathConfig.controllerDelayUs =
          config.isoDataPathConfiguration.controllerDelayUs;
  stack_config.isoDataPathConfig.configuration = *config.isoDataPathConfiguration.configuration;

  return {config, stack_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseQosConfiguration,
          bluetooth::le_audio::types::QosConfigSetting>
PrepareReferenceQosConfiguration(bool is_low_latency) {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::LeAudioAseQosConfiguration
          aidl_ase_config = ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseQosConfiguration{
                          .sduIntervalUs = 10000,
                          .framing = ::aidl::android::hardware::bluetooth::audio::
                                  IBluetoothAudioProvider::Framing::UNFRAMED,
                          .phy = {::aidl::android::hardware::bluetooth::audio::Phy::TWO_M},
                          .maxTransportLatencyMs = 10,  // Preferred max transport latency
                          .maxSdu = 120,
                          .retransmissionNum = 2,
                  };
  bluetooth::le_audio::types::QosConfigSetting stack_ase_config = {
          .target_latency =
                  is_low_latency
                          ? bluetooth::le_audio::types::kTargetLatencyLower
                          : bluetooth::le_audio::types::kTargetLatencyBalancedLatencyReliability,
          .retransmission_number = 2,
          .max_transport_latency = 10,
          .sduIntervalUs = 10000,
          .maxSdu = 120,
  };

  return {aidl_ase_config, stack_ase_config};
}

std::pair<std::vector<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv>,
          ::bluetooth::le_audio::types::LeAudioLtvMap>
PrepareReferenceCodecSpecificConfigurationLc3(bool is_low_latency, bool is_left, bool is_right) {
  ::bluetooth::le_audio::types::LeAudioLtvMap stack_params;
  std::vector<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv>
          aidl_params;

  aidl_params.push_back(
          is_low_latency ? ::aidl::android::hardware::bluetooth::audio::
                                   CodecSpecificConfigurationLtv::SamplingFrequency::HZ24000
                         : ::aidl::android::hardware::bluetooth::audio::
                                   CodecSpecificConfigurationLtv::SamplingFrequency::HZ48000);
  stack_params.Add(
          le_audio::codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
          (uint8_t)(is_low_latency ? le_audio::codec_spec_conf::kLeAudioSamplingFreq24000Hz
                                   : le_audio::codec_spec_conf::kLeAudioSamplingFreq48000Hz));

  aidl_params.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  AudioChannelAllocation{
                          .bitmask = (is_left ? ::aidl::android::hardware::bluetooth::audio::
                                                        CodecSpecificConfigurationLtv::
                                                                AudioChannelAllocation::FRONT_LEFT
                                              : 0) |
                                     (is_right ? ::aidl::android::hardware::bluetooth::audio::
                                                         CodecSpecificConfigurationLtv::
                                                                 AudioChannelAllocation::FRONT_RIGHT
                                               : 0)});
  stack_params.Add(
          le_audio::codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
          (uint32_t)((is_left ? le_audio::codec_spec_conf::kLeAudioLocationFrontLeft : 0) |
                     (is_right ? le_audio::codec_spec_conf::kLeAudioLocationFrontRight : 0)));

  aidl_params.push_back(::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                                FrameDuration::US7500);
  stack_params.Add(le_audio::codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                   (uint8_t)le_audio::codec_spec_conf::kLeAudioCodecFrameDur7500us);

  aidl_params.push_back(::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                                CodecFrameBlocksPerSDU{.value = 1});
  stack_params.Add(le_audio::codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu, (uint8_t)1);

  // TODO: Verify these values with the standard 48kHz and 24kHz configs sets
  aidl_params.push_back(::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                                OctetsPerCodecFrame{.value = is_low_latency ? 80 : 120});
  stack_params.Add(le_audio::codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                   (uint16_t)(is_low_latency ? 80 : 120));

  return {aidl_params, stack_params};
}

std::pair<std::vector<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv>,
          ::bluetooth::le_audio::types::LeAudioLtvMap>
PrepareReferenceCodecSpecificConfigurationVendor1(bool is_low_latency, bool is_left,
                                                  bool is_right) {
  ::bluetooth::le_audio::types::LeAudioLtvMap stack_params;
  std::vector<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv>
          aidl_params;

  aidl_params.push_back(
          is_low_latency ? ::aidl::android::hardware::bluetooth::audio::
                                   CodecSpecificConfigurationLtv::SamplingFrequency::HZ24000
                         : ::aidl::android::hardware::bluetooth::audio::
                                   CodecSpecificConfigurationLtv::SamplingFrequency::HZ48000);
  stack_params.Add(
          le_audio::codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
          (uint8_t)(is_low_latency ? le_audio::codec_spec_conf::kLeAudioSamplingFreq24000Hz
                                   : le_audio::codec_spec_conf::kLeAudioSamplingFreq48000Hz));

  aidl_params.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  AudioChannelAllocation{
                          .bitmask = (is_left ? ::aidl::android::hardware::bluetooth::audio::
                                                        CodecSpecificConfigurationLtv::
                                                                AudioChannelAllocation::FRONT_LEFT
                                              : 0) |
                                     (is_right ? ::aidl::android::hardware::bluetooth::audio::
                                                         CodecSpecificConfigurationLtv::
                                                                 AudioChannelAllocation::FRONT_RIGHT
                                               : 0)});
  stack_params.Add(
          le_audio::codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
          (uint32_t)((is_left ? le_audio::codec_spec_conf::kLeAudioLocationFrontLeft : 0) |
                     (is_right ? le_audio::codec_spec_conf::kLeAudioLocationFrontRight : 0)));

  return {aidl_params, stack_params};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseConfigurationSetting::AseDirectionConfiguration,
          ::bluetooth::le_audio::types::AseConfiguration>
PrepareReferenceAseDirectionConfigLc3(bool is_left, bool is_right, bool is_low_latency,
                                      bool has_qos = true, bool has_datapath = true) {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioAseConfigurationSetting::AseDirectionConfiguration aidl_ase_config;

  ::bluetooth::le_audio::types::CodecConfigSetting stack_codec;
  ::bluetooth::le_audio::types::AseConfiguration stack_ase_config(stack_codec);

  aidl_ase_config.aseConfiguration.targetLatency =
          is_low_latency ? ::aidl::android::hardware::bluetooth::audio::LeAudioAseConfiguration::
                                   TargetLatency::LOWER
                         : ::aidl::android::hardware::bluetooth::audio::LeAudioAseConfiguration::
                                   TargetLatency::BALANCED_LATENCY_RELIABILITY;

  /* Default Phy */
  aidl_ase_config.aseConfiguration.targetPhy =
          ::aidl::android::hardware::bluetooth::audio::Phy::TWO_M;
  // Note: Phy parameter is selected by the BT stack based on the remote support
  //       Phy parameter from the AIDL is considered as a suggestion

  /* Default Codec */
  aidl_ase_config.aseConfiguration.codecId = kAidlCodecLc3;
  stack_ase_config.codec.id = kStackCodecLc3;

  /* Default Codec Parameters */
  auto [aidl_params, stack_params] =
          PrepareReferenceCodecSpecificConfigurationLc3(is_low_latency, is_left, is_right);
  aidl_ase_config.aseConfiguration.codecConfiguration = aidl_params;
  stack_ase_config.codec.params = stack_params;

  /* No vendor codec parameters */
  aidl_ase_config.aseConfiguration.vendorCodecConfiguration = std::nullopt;
  stack_ase_config.codec.vendor_params = {};

  /* Default metadata */
  auto [aidl_metadata, stack_metadata] = PrepareReferenceMetadata();
  aidl_ase_config.aseConfiguration.metadata = aidl_metadata;
  stack_ase_config.metadata = stack_metadata;

  auto stack_codec_params = stack_params.GetAsCoreCodecConfig();

  // Value of 0x00000000 means MonoAudio - one channel per iso stream
  auto location_bitset = std::bitset<32>(stack_codec_params.audio_channel_allocation.value_or(0));
  stack_ase_config.codec.channel_count_per_iso_stream =
          location_bitset.any() ? location_bitset.count() : 1;

  /* QoS configuration */
  if (has_qos) {
    auto [aidl_qos_config, stack_qos_config] = PrepareReferenceQosConfiguration(is_low_latency);
    aidl_ase_config.qosConfiguration = aidl_qos_config;
    stack_ase_config.qos = stack_qos_config;
  }

  /* Data path configuration */
  if (has_datapath) {
    auto [aidl_datapath_config, stack_datapath_config] =
            PrepareReferenceLeAudioDataPathConfigurationLc3();
    aidl_ase_config.dataPathConfiguration = aidl_datapath_config;
    stack_ase_config.data_path_configuration = stack_datapath_config;
  }

  return {aidl_ase_config, stack_ase_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseConfigurationSetting::AseDirectionConfiguration,
          ::bluetooth::le_audio::types::AseConfiguration>
PrepareReferenceAseDirectionConfigVendor1(bool is_left, bool is_right, bool is_low_latency,
                                          bool has_qos = true, bool has_datapath = true) {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioAseConfigurationSetting::AseDirectionConfiguration aidl_ase_config;

  ::bluetooth::le_audio::types::CodecConfigSetting stack_codec;
  ::bluetooth::le_audio::types::AseConfiguration stack_ase_config(stack_codec);

  aidl_ase_config.aseConfiguration.targetLatency =
          is_low_latency ? ::aidl::android::hardware::bluetooth::audio::LeAudioAseConfiguration::
                                   TargetLatency::LOWER
                         : ::aidl::android::hardware::bluetooth::audio::LeAudioAseConfiguration::
                                   TargetLatency::BALANCED_LATENCY_RELIABILITY;

  /* Default Phy */
  aidl_ase_config.aseConfiguration.targetPhy =
          ::aidl::android::hardware::bluetooth::audio::Phy::TWO_M;

  /* Default Codec */
  aidl_ase_config.aseConfiguration.codecId = kAidlCodecVendor1;
  stack_ase_config.codec.id = kStackCodecVendor1;

  /* Default Codec Parameters */
  auto [aidl_params, stack_params] =
          PrepareReferenceCodecSpecificConfigurationVendor1(is_low_latency, is_left, is_right);
  aidl_ase_config.aseConfiguration.codecConfiguration = aidl_params;
  stack_ase_config.codec.params = stack_params;

  /* Vendor codec parameters */
  aidl_ase_config.aseConfiguration.vendorCodecConfiguration = {0x02, 0x03, 0x05};
  stack_ase_config.codec.vendor_params = {0x02, 0x03, 0x05};

  /* Default metadata */
  auto [aidl_metadata, stack_metadata] = PrepareReferenceMetadata();
  aidl_ase_config.aseConfiguration.metadata = aidl_metadata;
  stack_ase_config.metadata = stack_metadata;

  /* It is mandatory even for the vendor codecs to get the basic core parameters from the AIDL
   * including the audio allocation, and the sampling frequency.
   */
  auto stack_codec_params = stack_params.GetAsCoreCodecConfig();

  // Value of 0x00000000 means MonoAudio - one channel per iso stream
  auto location_bitset = std::bitset<32>(stack_codec_params.audio_channel_allocation.value_or(0));
  stack_ase_config.codec.channel_count_per_iso_stream =
          location_bitset.any() ? location_bitset.count() : 1;

  /* QoS configuration */
  if (has_qos) {
    auto [aidl_qos_config, stack_qos_config] = PrepareReferenceQosConfiguration(is_low_latency);
    aidl_ase_config.qosConfiguration = aidl_qos_config;
    stack_ase_config.qos = stack_qos_config;
  }

  /* Data path configuration */
  if (has_datapath) {
    auto [aidl_datapath_config, stack_datapath_config] =
            PrepareReferenceLeAudioDataPathConfigurationVendor();
    aidl_ase_config.dataPathConfiguration = aidl_datapath_config;
    stack_ase_config.data_path_configuration = stack_datapath_config;
  }

  return {aidl_ase_config, stack_ase_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseConfigurationSetting,
          ::bluetooth::le_audio::types::AudioSetConfiguration>
PrepareReferenceAseConfigurationSetting(
        ::bluetooth::le_audio::types::LeAudioContextType ctx_type,
        std::optional<uint32_t> source_locations =
                le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                le_audio::codec_spec_conf::kLeAudioLocationFrontRight) {
  // Prepare the AIDL format config
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioAseConfigurationSetting aidl_audio_set_config;
  ::bluetooth::le_audio::types::AudioSetConfiguration stack_audio_set_config;

  aidl_audio_set_config.audioContext.bitmask = (uint16_t)ctx_type;

  // Packing
  // AIDL:
  aidl_audio_set_config.packing =
          ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::Packing::SEQUENTIAL;
  // STACK:
  stack_audio_set_config.packing = bluetooth::hci::kIsoCigPackingSequential;

  /* Stereo playback - Two sink ASES */
  if (!aidl_audio_set_config.sinkAseConfiguration) {
    log::error("Has no sink container");
    aidl_audio_set_config.sinkAseConfiguration = std::vector<
            std::optional<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                  LeAudioAseConfigurationSetting::AseDirectionConfiguration>>();
  }

  // Left ASE config
  auto [aidl_ase_config_left, stack_ase_config_left] =
          PrepareReferenceAseDirectionConfigLc3(true, false, false);
  // AIDL:
  aidl_audio_set_config.sinkAseConfiguration->push_back(aidl_ase_config_left);
  // STACK:
  stack_audio_set_config.confs.sink.push_back(stack_ase_config_left);

  // Right ASE config
  auto [aidl_ase_config_right, stack_ase_config_right] =
          PrepareReferenceAseDirectionConfigLc3(false, true, false);
  // AIDL:
  aidl_audio_set_config.sinkAseConfiguration->push_back(aidl_ase_config_right);
  // STACK:
  stack_audio_set_config.confs.sink.push_back(stack_ase_config_right);

  // Config Flags
  // AIDL:
  aidl_audio_set_config.flags->bitmask =
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::SPATIAL_AUDIO |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::LOW_LATENCY |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::
                  ALLOW_ASYMMETRIC_CONFIGURATIONS |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::MONO_MIC_CONFIGURATION;

  /* Low latency microphone */
  if (source_locations) {
    if (!aidl_audio_set_config.sourceAseConfiguration) {
      log::error("Has no source container");
      aidl_audio_set_config.sourceAseConfiguration = std::vector<
              std::optional<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                    LeAudioAseConfigurationSetting::AseDirectionConfiguration>>();
    }

    // Left ASE config
    if (source_locations.value() & le_audio::codec_spec_conf::kLeAudioLocationFrontLeft) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigLc3(true, false, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }

    // Right ASE config
    if (source_locations.value() & le_audio::codec_spec_conf::kLeAudioLocationFrontRight) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigLc3(false, true, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }

    // Use Mono ASE config if not Left nor Right
    if (stack_audio_set_config.confs.source.empty()) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigLc3(false, false, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }
  }

  return {aidl_audio_set_config, stack_audio_set_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioAseConfigurationSetting,
          ::bluetooth::le_audio::types::AudioSetConfiguration>
PrepareReferenceAseConfigurationSettingVendor1(
        ::bluetooth::le_audio::types::LeAudioContextType ctx_type,
        std::optional<uint32_t> source_locations =
                le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                le_audio::codec_spec_conf::kLeAudioLocationFrontRight) {
  // Prepare the AIDL format config
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioAseConfigurationSetting aidl_audio_set_config;
  ::bluetooth::le_audio::types::AudioSetConfiguration stack_audio_set_config;

  aidl_audio_set_config.audioContext.bitmask = (uint16_t)ctx_type;

  // Packing
  // AIDL:
  aidl_audio_set_config.packing =
          ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::Packing::SEQUENTIAL;
  // STACK:
  stack_audio_set_config.packing = bluetooth::hci::kIsoCigPackingSequential;

  /* Stereo playback - Two sink ASES */
  if (!aidl_audio_set_config.sinkAseConfiguration) {
    log::error("Has no sink container");
    aidl_audio_set_config.sinkAseConfiguration = std::vector<
            std::optional<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                  LeAudioAseConfigurationSetting::AseDirectionConfiguration>>();
  }

  // Left ASE config
  auto [aidl_ase_config_left, stack_ase_config_left] =
          PrepareReferenceAseDirectionConfigVendor1(true, false, false);
  // AIDL:
  aidl_audio_set_config.sinkAseConfiguration->push_back(aidl_ase_config_left);
  // STACK:
  stack_audio_set_config.confs.sink.push_back(stack_ase_config_left);

  // Right ASE config
  auto [aidl_ase_config_right, stack_ase_config_right] =
          PrepareReferenceAseDirectionConfigVendor1(false, true, false);
  // AIDL:
  aidl_audio_set_config.sinkAseConfiguration->push_back(aidl_ase_config_right);
  // STACK:
  stack_audio_set_config.confs.sink.push_back(stack_ase_config_right);

  // Config Flags
  // AIDL:
  aidl_audio_set_config.flags->bitmask =
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::SPATIAL_AUDIO |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::LOW_LATENCY |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::
                  ALLOW_ASYMMETRIC_CONFIGURATIONS |
          ::aidl::android::hardware::bluetooth::audio::ConfigurationFlags::MONO_MIC_CONFIGURATION;

  /* Low latency microphone */
  if (source_locations) {
    if (!aidl_audio_set_config.sourceAseConfiguration) {
      log::error("Has no source container");
      aidl_audio_set_config.sourceAseConfiguration = std::vector<
              std::optional<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                    LeAudioAseConfigurationSetting::AseDirectionConfiguration>>();
    }

    // Left ASE config
    if (source_locations.value() & le_audio::codec_spec_conf::kLeAudioLocationFrontLeft) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigVendor1(true, false, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }

    // Right ASE config
    if (source_locations.value() & le_audio::codec_spec_conf::kLeAudioLocationFrontRight) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigVendor1(false, true, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }

    // Use Mono ASE config if not Left nor Right
    if (stack_audio_set_config.confs.source.empty()) {
      auto [aidl_ase_config_source, stack_ase_config_source] =
              PrepareReferenceAseDirectionConfigVendor1(false, false, true);
      // AIDL:
      aidl_audio_set_config.sourceAseConfiguration->push_back(aidl_ase_config_source);
      // STACK:
      stack_audio_set_config.confs.source.push_back(stack_ase_config_source);
    }
  }

  return {aidl_audio_set_config, stack_audio_set_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioSubgroupBisConfiguration,
          ::bluetooth::le_audio::broadcaster::BroadcastSubgroupBisCodecConfig>
PrepareReferenceBisConfiguration() {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioSubgroupBisConfiguration aidl_cfg;

  auto [aidl_codec_spec, stack_codec_spec] =
          test_utils::PrepareReferenceCodecSpecificConfigurationLc3(true, true, true);

  auto [aidl_metadata, stack_metadata] = test_utils::PrepareReferenceMetadata();

  aidl_cfg.numBis = 2;
  aidl_cfg.bisConfiguration.codecId = kAidlCodecLc3;
  aidl_cfg.bisConfiguration.codecConfiguration = aidl_codec_spec;
  aidl_cfg.bisConfiguration.vendorCodecConfiguration = {0x02, 0x03};
  aidl_cfg.bisConfiguration.metadata = aidl_metadata;

  // TODO: Add metadata support at the BIS level
  ::bluetooth::le_audio::broadcaster::BroadcastSubgroupBisCodecConfig stack_cfg(
          aidl_cfg.numBis, 1, stack_codec_spec, std::vector<uint8_t>{0x02, 0x03});

  return {aidl_cfg, stack_cfg};
}

std::pair<std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                              LeAudioBroadcastSubgroupConfiguration>,
          std::vector<::bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig>>
PrepareReferenceBroadcastSubgroups() {
  std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                      LeAudioBroadcastSubgroupConfiguration>
          aidl_subgroups;
  std::vector<::bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig> stack_subgroups;

  auto [aidl_left_params, stack_left_params] =
          PrepareReferenceCodecSpecificConfigurationLc3(false, true, false);

  auto [aidl_right_params, stack_right_params] =
          PrepareReferenceCodecSpecificConfigurationLc3(false, false, true);

  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioBroadcastSubgroupConfiguration aidl_subgroup{
                  .bisConfigurations =
                          {::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                   LeAudioSubgroupBisConfiguration{
                                           .numBis = 1,
                                           .bisConfiguration =
                                                   {
                                                           .codecId = kAidlCodecLc3,
                                                           .codecConfiguration = aidl_left_params,
                                                           .vendorCodecConfiguration =
                                                                   {},  // no vendor codec config
                                                                        // The stack does not yet
                                                                        // support metadata at BIS
                                                                        // config level
                                                           .metadata = std::nullopt,
                                                   },
                                   },
                           ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                   LeAudioSubgroupBisConfiguration{
                                           .numBis = 1,
                                           .bisConfiguration =
                                                   {
                                                           .codecId = kAidlCodecLc3,
                                                           .codecConfiguration = aidl_right_params,
                                                           .vendorCodecConfiguration =
                                                                   {},  // no vendor codec config
                                                                        // The stack does not yet
                                                                        // support metadata at BIS
                                                                        // config level
                                                           .metadata = std::nullopt,
                                                   },
                                   }},
                  .vendorCodecConfiguration = std::nullopt,
          };
  aidl_subgroups.push_back(aidl_subgroup);

  ::bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig stack_subgroup(
          kStackCodecLc3,
          {le_audio::broadcaster::BroadcastSubgroupBisCodecConfig(1, 1, stack_left_params,
                                                                  std::nullopt),
           le_audio::broadcaster::BroadcastSubgroupBisCodecConfig(1, 1, stack_right_params,
                                                                  std::nullopt)},
          16, std::nullopt);
  stack_subgroups.push_back(stack_subgroup);

  return {aidl_subgroups, stack_subgroups};
}

::bluetooth::le_audio::broadcaster::BroadcastQosConfig PrepareStackBroadcastQosConfig(
        uint8_t rtn = 2, uint16_t max_transport_latency = 50) {
  ::bluetooth::le_audio::broadcaster::BroadcastQosConfig qos(rtn, max_transport_latency);
  return qos;
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioBroadcastConfigurationSetting,
          ::bluetooth::le_audio::broadcaster::BroadcastConfiguration>
PrepareReferenceBroadcastConfigurationLc3() {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioBroadcastConfigurationSetting aidl_config;

  auto [aidl_datapath_config, stack_datapath_config] =
          PrepareReferenceLeAudioDataPathConfigurationLc3();

  auto [aidl_bis_subgroups, stack_bis_subgroups] = test_utils::PrepareReferenceBroadcastSubgroups();

  aidl_config.dataPathConfiguration = aidl_datapath_config;
  aidl_config.sduIntervalUs = 10000;
  aidl_config.numBis = 2;
  aidl_config.maxSduOctets = 120;
  aidl_config.maxTransportLatencyMs = 100;
  aidl_config.retransmitionNum = 4;
  aidl_config.phy = {::aidl::android::hardware::bluetooth::audio::Phy::TWO_M};
  aidl_config.packing =
          ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::Packing::SEQUENTIAL;
  aidl_config.framing =
          ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::Framing::UNFRAMED;
  aidl_config.subgroupsConfigurations = aidl_bis_subgroups;

  ::bluetooth::le_audio::broadcaster::BroadcastConfiguration stack_config = {
          .subgroups = stack_bis_subgroups,
          .qos = PrepareStackBroadcastQosConfig(aidl_config.retransmitionNum,
                                                aidl_config.maxTransportLatencyMs),
          .data_path = stack_datapath_config,
          .sduIntervalUs = 10000,
          .maxSduOctets = 120,
          .phy = hci::kIsoCigPhy2M,
          .packing = bluetooth::hci::kIsoCigPackingSequential,
          .framing = hci::kIsoCigFramingUnframed,
  };

  return {aidl_config, stack_config};
}

std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                  LeAudioBroadcastConfigurationRequirement,
          const std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>>
PrepareReferenceBroadcastRequirements() {
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioBroadcastConfigurationRequirement aidl_requirements;
  std::vector<std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t>>
          stack_requirements;

  std::pair<::bluetooth::le_audio::types::LeAudioContextType, uint8_t> stack_req =
          std::make_pair(le_audio::types::LeAudioContextType::MEDIA, le_audio::QUALITY_HIGH);
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioBroadcastSubgroupConfigurationRequirement aidl_req = {
                  .audioContext.bitmask = (int)le_audio::types::LeAudioContextType::MEDIA,
                  .quality = ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                          BroadcastQuality::HIGH,
                  // TODO: Currently there is no equivalent of this in the stack data
                  // format
                  .bisNumPerSubgroup = 2,
          };

  aidl_requirements.subgroupConfigurationRequirements.push_back(aidl_req);
  stack_requirements.push_back(stack_req);
  return {aidl_requirements, stack_requirements};
}

static std::pair<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                         LeAudioConfigurationRequirement::AseDirectionRequirement,
                 ::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements::
                         DeviceDirectionRequirements>
PrepareReferenceDirectionRequirements(int32_t aidl_location, uint32_t stack_location) {
  ::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements::DeviceDirectionRequirements
          stack_req;
  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioConfigurationRequirement::AseDirectionRequirement aidl_req;

  // Target latency
  stack_req.target_latency = ::bluetooth::le_audio::types::kTargetLatencyBalancedLatencyReliability;
  aidl_req.aseConfiguration.targetLatency = aidl::android::hardware::bluetooth::audio::
          LeAudioAseConfiguration::TargetLatency::BALANCED_LATENCY_RELIABILITY;

  // PHY
  stack_req.target_Phy = ::bluetooth::le_audio::types::kTargetPhy2M;
  aidl_req.aseConfiguration.targetPhy = aidl::android::hardware::bluetooth::audio::Phy::TWO_M;

  // Sampling frequency
  stack_req.params.Add(
          le_audio::codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
          (uint8_t)::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq32000Hz);
  aidl_req.aseConfiguration.codecConfiguration.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  SamplingFrequency::HZ32000);

  // Frame duration
  stack_req.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeFrameDuration,
          (uint8_t)::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameDur10000us);
  aidl_req.aseConfiguration.codecConfiguration.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  FrameDuration::US10000);

  // Codec frame Blocks per SDU
  stack_req.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
          (uint8_t)1);
  aidl_req.aseConfiguration.codecConfiguration.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  CodecFrameBlocksPerSDU{.value = 1});

  // Audio channel allocation
  stack_req.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
          (uint32_t)stack_location);
  aidl_req.aseConfiguration.codecConfiguration.push_back(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  AudioChannelAllocation{.bitmask = aidl_location});

  return {aidl_req, stack_req};
}

}  // namespace test_utils

TEST(BluetoothAudioClientInterfaceAidlTest, testGetAidlCodecIdFromStackFormatLc3) {
  /* Verify LC3 core codec */
  auto aidl_codec = GetAidlCodecIdFromStackFormat(kStackCodecLc3);
  ASSERT_EQ(aidl_codec.getTag(), aidl::android::hardware::bluetooth::audio::CodecId::core);
  ASSERT_EQ(aidl_codec.get<aidl::android::hardware::bluetooth::audio::CodecId::core>(),
            kAidlCodecLc3);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetAidlCodecIdFromStackFormatVendor1) {
  /* Verify vendor codec */
  auto aidl_codec = GetAidlCodecIdFromStackFormat(kStackCodecVendor1);
  ASSERT_EQ(aidl_codec.getTag(), aidl::android::hardware::bluetooth::audio::CodecId::vendor);
  ASSERT_EQ(aidl_codec.get<aidl::android::hardware::bluetooth::audio::CodecId::vendor>(),
            kAidlCodecVendor1);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackCodecIdFromAidlFormatLc3) {
  /* Verify LC3 core codec */
  auto stack_codec = GetStackCodecIdFromAidlFormat(kAidlCodecLc3);
  ASSERT_EQ(stack_codec, kStackCodecLc3);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackCodecIdFromAidlFormatVendor1) {
  /* Verify vendor codec */
  auto stack_codec = GetStackCodecIdFromAidlFormat(kAidlCodecVendor1);
  ASSERT_EQ(stack_codec, kStackCodecVendor1);
}

static void verifyMetadata(
        const std::optional<::aidl::android::hardware::bluetooth::audio::MetadataLtv>& aidl_meta,
        const ::bluetooth::le_audio::types::LeAudioMetadata& stack_meta,
        bool& matched_preferredAudioContexts, bool& matched_streamingAudioContexts,
        bool& matched_vendorSpecific) {
  if (aidl_meta->getTag() ==
      ::aidl::android::hardware::bluetooth::audio::MetadataLtv::preferredAudioContexts) {
    ASSERT_EQ(aidl_meta
                      ->get<::aidl::android::hardware::bluetooth::audio::MetadataLtv::
                                    preferredAudioContexts>()
                      .values.bitmask,
              stack_meta.preferred_audio_context.value().value());
    matched_preferredAudioContexts = true;

  } else if (aidl_meta->getTag() ==
             ::aidl::android::hardware::bluetooth::audio::MetadataLtv::streamingAudioContexts) {
    ASSERT_EQ(aidl_meta
                      ->get<::aidl::android::hardware::bluetooth::audio::MetadataLtv::
                                    streamingAudioContexts>()
                      .values.bitmask,
              stack_meta.streaming_audio_context.value().value());
    matched_streamingAudioContexts = true;

  } else if (aidl_meta->getTag() ==
             ::aidl::android::hardware::bluetooth::audio::MetadataLtv::vendorSpecific) {
    auto vendor_spec = aidl_meta->get<
            ::aidl::android::hardware::bluetooth::audio::MetadataLtv::vendorSpecific>();

    /* Company ID is a 2 octet value */
    ASSERT_EQ(vendor_spec.companyId,
              stack_meta.vendor_specific->at(0) | (stack_meta.vendor_specific->at(1) << 8));
    auto expected_payload_size = stack_meta.vendor_specific->size() - 2;
    ASSERT_EQ(vendor_spec.opaqueValue.size(), expected_payload_size);
    ASSERT_EQ(0, memcmp(vendor_spec.opaqueValue.data(), stack_meta.vendor_specific->data() + 2,
                        expected_payload_size));
    matched_vendorSpecific = true;
  } else {
    GTEST_FAIL();
  }
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetAidlMetadataFromStackFormat) {
  ::bluetooth::le_audio::types::LeAudioLtvMap metadata_ltvs = test_utils::PrepareStackMetadataLtv();
  auto aidl_metadata = GetAidlMetadataFromStackFormat(metadata_ltvs);
  ASSERT_TRUE(aidl_metadata.has_value());

  /* Only kLeAudioMetadataTypePreferredAudioContext,
   *      kLeAudioMetadataTypeStreamingAudioContext,
   *      kLeAudioMetadataVendorSpecific types are supported on the AIDL
   */
  const size_t maxAidlSupportedMetadataTypes = 3;
  ASSERT_EQ(aidl_metadata->size(), maxAidlSupportedMetadataTypes);

  bool matched_preferredAudioContexts = false;
  bool matched_streamingAudioContexts = false;
  bool matched_vendorSpecific = false;

  for (const auto& meta : *aidl_metadata) {
    ASSERT_TRUE(meta.has_value());
    verifyMetadata(meta, metadata_ltvs.GetAsLeAudioMetadata(), matched_preferredAudioContexts,
                   matched_streamingAudioContexts, matched_vendorSpecific);
  }

  ASSERT_TRUE(matched_preferredAudioContexts);
  ASSERT_TRUE(matched_streamingAudioContexts);
  ASSERT_TRUE(matched_vendorSpecific);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetAidlLeAudioDeviceCapabilitiesFromStackFormat) {
  std::vector<bluetooth::le_audio::types::acs_ac_record> pac_records;

  // Add some records
  auto stack_record = test_utils::PrepareStackPacRecord(
          kStackCodecLc3, ::bluetooth::le_audio::codec_spec_caps::kLeAudioSamplingFreq16000Hz,
          ::bluetooth::le_audio::codec_spec_caps::kLeAudioCodecFrameDurPrefer7500us,
          ::bluetooth::le_audio::codec_spec_caps::kLeAudioCodecChannelCountTwoChannel, 80, 120);
  pac_records.push_back(stack_record);

  auto aidl_pacs = GetAidlLeAudioDeviceCapabilitiesFromStackFormat(pac_records);
  ASSERT_TRUE(aidl_pacs.has_value());

  bool matched_supportedSamplingFrequencies = false;
  bool matched_supportedFrameDurations = false;
  bool matched_supportedAudioChannelCounts = false;
  bool matched_supportedOctetsPerCodecFrame = false;
  bool matched_supportedMaxCodecFramesPerSDU = false;

  for (auto const& aidl_pac : *aidl_pacs) {
    ASSERT_TRUE(aidl_pac.has_value());
    ASSERT_EQ(aidl::android::hardware::bluetooth::audio::CodecId::core, aidl_pac->codecId.getTag());
    ASSERT_EQ(kAidlCodecLc3,
              aidl_pac->codecId.get<aidl::android::hardware::bluetooth::audio::CodecId::core>());
    for (auto const& cap : aidl_pac->codecSpecificCapabilities) {
      if (cap.getTag() == ::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificCapabilitiesLtv::supportedSamplingFrequencies) {
        ASSERT_EQ(::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                          SupportedSamplingFrequencies::HZ16000,
                  cap.get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificCapabilitiesLtv::supportedSamplingFrequencies>()
                          .bitmask);
        matched_supportedSamplingFrequencies = true;

      } else if (cap.getTag() == ::aidl::android::hardware::bluetooth::audio::
                                         CodecSpecificCapabilitiesLtv::supportedFrameDurations) {
        ASSERT_EQ(::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                          SupportedFrameDurations::US7500PREFERRED,
                  cap.get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificCapabilitiesLtv::supportedFrameDurations>()
                          .bitmask);
        matched_supportedFrameDurations = true;

      } else if (cap.getTag() ==
                 ::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                         supportedAudioChannelCounts) {
        ASSERT_EQ(::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                          SupportedAudioChannelCounts::TWO,
                  cap.get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificCapabilitiesLtv::supportedAudioChannelCounts>()
                          .bitmask);
        matched_supportedAudioChannelCounts = true;

      } else if (cap.getTag() ==
                 ::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                         supportedOctetsPerCodecFrame) {
        ASSERT_EQ(80, cap.get<::aidl::android::hardware::bluetooth::audio::
                                      CodecSpecificCapabilitiesLtv::supportedOctetsPerCodecFrame>()
                              .min);
        ASSERT_EQ(120, cap.get<::aidl::android::hardware::bluetooth::audio::
                                       CodecSpecificCapabilitiesLtv::supportedOctetsPerCodecFrame>()
                               .max);
        matched_supportedOctetsPerCodecFrame = true;

      } else if (cap.getTag() ==
                 ::aidl::android::hardware::bluetooth::audio::CodecSpecificCapabilitiesLtv::
                         supportedMaxCodecFramesPerSDU) {
        ASSERT_EQ(1, cap.get<::aidl::android::hardware::bluetooth::audio::
                                     CodecSpecificCapabilitiesLtv::supportedMaxCodecFramesPerSDU>()
                             .value);
        matched_supportedMaxCodecFramesPerSDU = true;

      } else {
        GTEST_FAIL();
      }
    }

    ASSERT_TRUE(aidl_pac->vendorCodecSpecificCapabilities.has_value());
    ASSERT_EQ(stack_record.codec_spec_caps_raw.size(),
              aidl_pac->vendorCodecSpecificCapabilities->size());
    ASSERT_EQ(0, memcmp(stack_record.codec_spec_caps_raw.data(),
                        aidl_pac->vendorCodecSpecificCapabilities->data(),
                        aidl_pac->vendorCodecSpecificCapabilities->size()));

    ASSERT_TRUE(aidl_pac->metadata.has_value());
    bool matched_preferredAudioContexts = false;
    bool matched_streamingAudioContexts = false;
    bool matched_vendorSpecific = false;
    for (auto const& meta : *aidl_pac->metadata) {
      verifyMetadata(meta, stack_record.metadata.GetAsLeAudioMetadata(),
                     matched_preferredAudioContexts, matched_streamingAudioContexts,
                     matched_vendorSpecific);
    }

    ASSERT_TRUE(matched_preferredAudioContexts);
    ASSERT_TRUE(matched_streamingAudioContexts);
    ASSERT_TRUE(matched_vendorSpecific);
  }

  ASSERT_TRUE(matched_supportedSamplingFrequencies);
  ASSERT_TRUE(matched_supportedFrameDurations);
  ASSERT_TRUE(matched_supportedAudioChannelCounts);
  ASSERT_TRUE(matched_supportedOctetsPerCodecFrame);
  ASSERT_TRUE(matched_supportedMaxCodecFramesPerSDU);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackLeAudioLtvMapFromAidlFormat) {
  auto [aidl_codec_spec, matching_stack_codec_spec] =
          test_utils::PrepareReferenceCodecSpecificConfigurationLc3(true, true, true);

  auto stack_codec_config_ltv = GetStackLeAudioLtvMapFromAidlFormat(aidl_codec_spec);
  ASSERT_EQ(matching_stack_codec_spec, stack_codec_config_ltv);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackDataPathFromAidlFormat) {
  auto [aidl_config, reference_stack_config] =
          test_utils::PrepareReferenceLeAudioDataPathConfigurationVendor();
  auto stack_config = bluetooth::audio::aidl::GetStackDataPathFromAidlFormat(aidl_config);
  ASSERT_EQ(stack_config, reference_stack_config);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackMetadataFromAidlFormat) {
  auto [aidl_metadata, reference_stack_metadata] = test_utils::PrepareReferenceMetadata();
  auto stack_metadata = bluetooth::audio::aidl::GetStackMetadataFromAidlFormat(aidl_metadata);
  ASSERT_EQ(stack_metadata, reference_stack_metadata);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackUnicastConfigurationFromAidlFormat) {
  auto source_allocations = le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                            le_audio::codec_spec_conf::kLeAudioLocationFrontRight;
  auto [aidl_config, expected_stack_config] = test_utils::PrepareReferenceAseConfigurationSetting(
          ::bluetooth::le_audio::types::LeAudioContextType::GAME, source_allocations);

  auto stack_config = GetStackUnicastConfigurationFromAidlFormat(
          ::bluetooth::le_audio::types::LeAudioContextType::GAME, aidl_config);
  ASSERT_TRUE(stack_config.has_value());
  ASSERT_EQ(stack_config->confs.sink.size(), 2ul);
  ASSERT_EQ(stack_config->confs.source.size(), 2ul);
  ASSERT_EQ(*stack_config, expected_stack_config);
  ASSERT_EQ(stack_config->name,
            "AIDL-2-1chan-SinkAse-CodecId_6_0_0-48000hz_120oct_7500us-BalancedReliability-"
            "2-1chan-SourceAse-CodecId_6_0_0-24000hz_80oct_7500us-LowLatency");
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackUnicastConfigurationFromAidlFormatVendor1) {
  auto source_allocations = le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                            le_audio::codec_spec_conf::kLeAudioLocationFrontRight;
  auto [aidl_config, expected_stack_config] =
          test_utils::PrepareReferenceAseConfigurationSettingVendor1(
                  ::bluetooth::le_audio::types::LeAudioContextType::GAME, source_allocations);

  auto stack_config = GetStackUnicastConfigurationFromAidlFormat(
          ::bluetooth::le_audio::types::LeAudioContextType::GAME, aidl_config);
  ASSERT_TRUE(stack_config.has_value());
  ASSERT_EQ(stack_config->confs.sink.size(), 2ul);
  ASSERT_EQ(stack_config->confs.source.size(), 2ul);
  ASSERT_EQ(*stack_config, expected_stack_config);
  ASSERT_NE(stack_config->confs.sink.at(0).codec.vendor_params.size(), 0lu);
  ASSERT_NE(stack_config->confs.sink.at(1).codec.vendor_params.size(), 0lu);
  ASSERT_NE(stack_config->confs.source.at(0).codec.vendor_params.size(), 0lu);
  ASSERT_NE(stack_config->confs.source.at(1).codec.vendor_params.size(), 0lu);
  ASSERT_NE(stack_config->confs.sink.at(0).metadata.Size(), 0lu);
  ASSERT_NE(stack_config->confs.sink.at(1).metadata.Size(), 0lu);
  ASSERT_NE(stack_config->confs.source.at(0).metadata.Size(), 0lu);
  ASSERT_NE(stack_config->confs.source.at(1).metadata.Size(), 0lu);
  ASSERT_EQ(stack_config->name,
            "AIDL-2-1chan-SinkAse-CodecId_255_49374_61453-48000hz-BalancedReliability-2-1chan-"
            "SourceAse-CodecId_255_49374_61453-24000hz-LowLatency");
}

TEST(BluetoothAudioClientInterfaceAidlTest, GetAidlLeAudioAseConfigurationFromStackFormat) {
  auto [reference_aidl_codec_params, reference_stack_codec_params] =
          test_utils::PrepareReferenceCodecSpecificConfigurationLc3(true, true, false);
  auto [reference_aidl_metadata, reference_stack_metadata] = test_utils::PrepareReferenceMetadata();

  ::bluetooth::le_audio::types::CodecConfigSetting codec;
  codec.id = kStackCodecLc3;
  codec.params = reference_stack_codec_params;
  codec.vendor_params = std::vector<uint8_t>{1, 2, 3};
  codec.channel_count_per_iso_stream = 1;

  auto aidl_le_audio_ase_configuration = audio::aidl::GetAidlLeAudioAseConfigurationFromStackFormat(
          codec, bluetooth::le_audio::types::kTargetLatencyLower,
          ::bluetooth::le_audio::types::kTargetPhy2M, reference_stack_metadata);

  // Verify the parameters
  ASSERT_EQ(::aidl::android::hardware::bluetooth::audio::LeAudioAseConfiguration::TargetLatency::
                    LOWER,
            aidl_le_audio_ase_configuration.targetLatency);
  ASSERT_EQ(::aidl::android::hardware::bluetooth::audio::Phy::TWO_M,
            aidl_le_audio_ase_configuration.targetPhy);

  ASSERT_TRUE(aidl_le_audio_ase_configuration.codecId.has_value());
  ASSERT_EQ(aidl::android::hardware::bluetooth::audio::CodecId::core,
            aidl_le_audio_ase_configuration.codecId->getTag());
  ASSERT_EQ(kAidlCodecLc3,
            aidl_le_audio_ase_configuration.codecId
                    ->get<aidl::android::hardware::bluetooth::audio::CodecId::core>());

  for (auto const& reference_ltv : reference_aidl_codec_params) {
    auto it = std::find_if(aidl_le_audio_ase_configuration.codecConfiguration.begin(),
                           aidl_le_audio_ase_configuration.codecConfiguration.end(),
                           [reference_ltv](const ::aidl::android::hardware::bluetooth::audio::
                                                   CodecSpecificConfigurationLtv& el) {
                             return el.getTag() == reference_ltv.getTag();
                           });
    ASSERT_NE(it, aidl_le_audio_ase_configuration.codecConfiguration.end());

    switch (it->getTag()) {
      case ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::Tag::
              codecFrameBlocksPerSDU:
        ASSERT_EQ(
                it->get<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                                codecFrameBlocksPerSDU>(),
                reference_ltv.get<::aidl::android::hardware::bluetooth::audio::
                                          CodecSpecificConfigurationLtv::codecFrameBlocksPerSDU>());
        break;
      case ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::Tag::
              samplingFrequency:
        ASSERT_EQ(it->get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificConfigurationLtv::samplingFrequency>(),
                  reference_ltv.get<::aidl::android::hardware::bluetooth::audio::
                                            CodecSpecificConfigurationLtv::samplingFrequency>());
        break;
      case ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::Tag::
              frameDuration:
        ASSERT_EQ(it->get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificConfigurationLtv::frameDuration>(),
                  reference_ltv.get<::aidl::android::hardware::bluetooth::audio::
                                            CodecSpecificConfigurationLtv::frameDuration>());
        break;
      case ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::Tag::
              audioChannelAllocation:
        ASSERT_EQ(
                it->get<::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                                audioChannelAllocation>(),
                reference_ltv.get<::aidl::android::hardware::bluetooth::audio::
                                          CodecSpecificConfigurationLtv::audioChannelAllocation>());
        break;
      case ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::Tag::
              octetsPerCodecFrame:
        ASSERT_EQ(it->get<::aidl::android::hardware::bluetooth::audio::
                                  CodecSpecificConfigurationLtv::octetsPerCodecFrame>(),
                  reference_ltv.get<::aidl::android::hardware::bluetooth::audio::
                                            CodecSpecificConfigurationLtv::octetsPerCodecFrame>());
        break;
      default:
        FAIL();
        break;
    }
  }

  ASSERT_NE(aidl_le_audio_ase_configuration.codecConfiguration.size(), 0lu);
  ASSERT_EQ(reference_aidl_codec_params.size(),
            aidl_le_audio_ase_configuration.codecConfiguration.size());

  ASSERT_TRUE(aidl_le_audio_ase_configuration.vendorCodecConfiguration.has_value());
  ASSERT_EQ(codec.vendor_params, aidl_le_audio_ase_configuration.vendorCodecConfiguration.value());

  ASSERT_TRUE(aidl_le_audio_ase_configuration.metadata.has_value());
  ASSERT_EQ(reference_aidl_metadata, aidl_le_audio_ase_configuration.metadata.value());
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackUnicastConfigurationFromAidlFormatMonoLoc) {
  auto source_allocations = le_audio::codec_spec_conf::kLeAudioLocationMonoAudio;
  auto [aidl_config, expected_stack_config] = test_utils::PrepareReferenceAseConfigurationSetting(
          ::bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL, source_allocations);

  auto stack_config = GetStackUnicastConfigurationFromAidlFormat(
          ::bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL, aidl_config);
  ASSERT_TRUE(stack_config.has_value());
  ASSERT_EQ(stack_config->confs.sink.size(), 2ul);
  ASSERT_EQ(stack_config->confs.source.size(), 1ul);
  ASSERT_EQ(*stack_config, expected_stack_config);
  ASSERT_EQ(stack_config->name,
            "AIDL-2-1chan-SinkAse-CodecId_6_0_0-48000hz_120oct_7500us-BalancedReliability-"
            "1-1chan-SourceAse-CodecId_6_0_0-24000hz_80oct_7500us-LowLatency");
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackBisConfigFromAidlFormat) {
  auto [aidl_config, expected_stack_config] = test_utils::PrepareReferenceBisConfiguration();
  ::bluetooth::le_audio::types::LeAudioCodecId out_stack_codec_id;

  auto stack_config = GetStackBisConfigFromAidlFormat(aidl_config, out_stack_codec_id);
  ASSERT_EQ(stack_config, expected_stack_config);
  ASSERT_EQ(out_stack_codec_id, kStackCodecLc3);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackSubgroupsFromAidlFormat) {
  auto [aidl_subgroups, expected_stack_subgroups] =
          test_utils::PrepareReferenceBroadcastSubgroups();
  auto stack_subgroups = GetStackSubgroupsFromAidlFormat(aidl_subgroups);

  ASSERT_FALSE(aidl_subgroups.empty());
  ASSERT_FALSE(stack_subgroups.empty());
  ASSERT_EQ(stack_subgroups, expected_stack_subgroups);
}

TEST(BluetoothAudioClientInterfaceAidlTest, testGetStackBroadcastConfigurationFromAidlFormat) {
  auto [aidl_config, expected_stack_config] =
          test_utils::PrepareReferenceBroadcastConfigurationLc3();
  auto stack_config = GetStackBroadcastConfigurationFromAidlFormat(aidl_config);
  ASSERT_TRUE(stack_config.has_value());
  ASSERT_EQ(stack_config.value(), expected_stack_config);
}

TEST(BluetoothAudioClientInterfaceAidlTest,
     testGetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat) {
  auto [reference_aidl_requirements, stack_requirements] =
          test_utils::PrepareReferenceBroadcastRequirements();
  auto aidl_requirements =
          GetAidlLeAudioBroadcastConfigurationRequirementFromStackFormat(stack_requirements);
  ASSERT_EQ(aidl_requirements, reference_aidl_requirements);
}

TEST(BluetoothAudioClientInterfaceAidlTest,
     testGetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat) {
  auto stack_context = le_audio::types::LeAudioContextType::CONVERSATIONAL;
  auto aidl_context = ::aidl::android::hardware::bluetooth::audio::AudioContext{
          .bitmask = ::aidl::android::hardware::bluetooth::audio::AudioContext::CONVERSATIONAL,
  };

  ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
          LeAudioConfigurationRequirement reference_aidl_requirements;
  reference_aidl_requirements.audioContext = aidl_context;

  auto [aidl_req_l, stack_req_l] = test_utils::PrepareReferenceDirectionRequirements(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  AudioChannelAllocation::FRONT_LEFT,
          le_audio::codec_spec_conf::kLeAudioLocationFrontLeft);

  auto [aidl_req_r, stack_req_r] = test_utils::PrepareReferenceDirectionRequirements(
          ::aidl::android::hardware::bluetooth::audio::CodecSpecificConfigurationLtv::
                  AudioChannelAllocation::FRONT_RIGHT,
          le_audio::codec_spec_conf::kLeAudioLocationFrontRight);

  // For this case lets make the sink and source requirements symmetric
  std::vector<::bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements::
                      DeviceDirectionRequirements>
          stack_sink_reqs = {stack_req_l, stack_req_r};
  auto stack_source_reqs = stack_sink_reqs;
  reference_aidl_requirements.sinkAseRequirement = std::vector<
          std::optional<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                LeAudioConfigurationRequirement::AseDirectionRequirement>>{
          aidl_req_l, aidl_req_r};
  reference_aidl_requirements.sourceAseRequirement = reference_aidl_requirements.sinkAseRequirement;

  ::bluetooth::le_audio::CodecManager::Flags flags =
          ::bluetooth::le_audio::CodecManager::Flags::NONE;
  auto aidl_requirements = GetAidlLeAudioUnicastConfigurationRequirementsFromStackFormat(
          stack_context, stack_sink_reqs, stack_source_reqs, flags);

  ASSERT_EQ(aidl_requirements.audioContext, reference_aidl_requirements.audioContext);
  ASSERT_EQ(aidl_requirements.flags, reference_aidl_requirements.flags);

  ASSERT_EQ(aidl_requirements.sinkAseRequirement.has_value(),
            reference_aidl_requirements.sinkAseRequirement.has_value());
  if (reference_aidl_requirements.sinkAseRequirement.has_value()) {
    for (auto const& reference_req : reference_aidl_requirements.sinkAseRequirement.value()) {
      auto iter = std::find_if(
              aidl_requirements.sinkAseRequirement->begin(),
              aidl_requirements.sinkAseRequirement->end(), [reference_req](auto const& aidl_req) {
                if (reference_req.has_value() != aidl_req.has_value()) {
                  return false;
                }
                if (reference_req->aseConfiguration.targetLatency !=
                    aidl_req->aseConfiguration.targetLatency) {
                  return false;
                }
                if (reference_req->aseConfiguration.targetPhy !=
                    aidl_req->aseConfiguration.targetPhy) {
                  return false;
                }
                if (reference_req->aseConfiguration.codecId != aidl_req->aseConfiguration.codecId) {
                  return false;
                }
                if (reference_req->aseConfiguration.vendorCodecConfiguration !=
                    aidl_req->aseConfiguration.vendorCodecConfiguration) {
                  return false;
                }
                if (reference_req->aseConfiguration.metadata !=
                    aidl_req->aseConfiguration.metadata) {
                  return false;
                }
                for (auto const& ref_el : reference_req->aseConfiguration.codecConfiguration) {
                  if (std::find(aidl_req->aseConfiguration.codecConfiguration.begin(),
                                aidl_req->aseConfiguration.codecConfiguration.end(),
                                ref_el) == aidl_req->aseConfiguration.codecConfiguration.end()) {
                    return false;
                  }
                }

                return true;
              });
      ASSERT_NE(iter, aidl_requirements.sinkAseRequirement->end());
    }
  }

  ASSERT_EQ(aidl_requirements.sourceAseRequirement.has_value(),
            reference_aidl_requirements.sourceAseRequirement.has_value());
  if (reference_aidl_requirements.sourceAseRequirement.has_value()) {
    for (auto const& reference_req : reference_aidl_requirements.sourceAseRequirement.value()) {
      auto iter = std::find_if(
              aidl_requirements.sourceAseRequirement->begin(),
              aidl_requirements.sourceAseRequirement->end(), [reference_req](auto const& aidl_req) {
                if (reference_req.has_value() != aidl_req.has_value()) {
                  return false;
                }
                if (reference_req->aseConfiguration.targetLatency !=
                    aidl_req->aseConfiguration.targetLatency) {
                  return false;
                }
                if (reference_req->aseConfiguration.targetPhy !=
                    aidl_req->aseConfiguration.targetPhy) {
                  return false;
                }
                if (reference_req->aseConfiguration.codecId != aidl_req->aseConfiguration.codecId) {
                  return false;
                }
                if (reference_req->aseConfiguration.vendorCodecConfiguration !=
                    aidl_req->aseConfiguration.vendorCodecConfiguration) {
                  return false;
                }
                if (reference_req->aseConfiguration.metadata !=
                    aidl_req->aseConfiguration.metadata) {
                  return false;
                }
                for (auto const& ref_el : reference_req->aseConfiguration.codecConfiguration) {
                  if (std::find(aidl_req->aseConfiguration.codecConfiguration.begin(),
                                aidl_req->aseConfiguration.codecConfiguration.end(),
                                ref_el) == aidl_req->aseConfiguration.codecConfiguration.end()) {
                    return false;
                  }
                }

                return true;
              });
      ASSERT_NE(iter, aidl_requirements.sourceAseRequirement->end());
    }
  }
}

}  // namespace
}  // namespace bluetooth
