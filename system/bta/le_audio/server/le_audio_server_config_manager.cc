/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "le_audio_server_config_manager.h"

#include <map>

#include "audio_hal_interface/le_audio_software.h"
#include "bta/le_audio/le_audio_types.h"
#include "osi/include/properties.h"

namespace bluetooth {
namespace le_audio {

LeAudioServerConfigManager::LeAudioServerConfigManager() {}
LeAudioServerConfigManager::~LeAudioServerConfigManager() {}

Pacs::ServiceDescriptor LeAudioServerConfigManager::GetPacsDescriptor(
        const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& playback_capa,
        const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& recording_capa) {
  le_audio::types::BidirectionalPair<std::vector<Pacs::PacSet>> pacs;
  uint8_t pac_set_id = 0;

  // Expose the local playback capabilities to peer devices as Sink endpoint capabilities
  if (!playback_capa.empty()) {
    Pacs::PacSet sink_pac_set = {.id = pac_set_id++, .records = {}};
    for (const auto& cap : playback_capa) {
      sink_pac_set.records.push_back(
              {le_audio::types::LeAudioCodecId(cap.coding_format, cap.vendor_company_id,
                                               cap.vendor_codec_id),
               cap.codec_spec_caps,
               {/*metadata*/}});
    }
    pacs.sink.push_back(sink_pac_set);
  }

  // Expose the local recording capabilities to peer devices as Source endpoint capabilities
  if (!recording_capa.empty()) {
    Pacs::PacSet source_pac_set = {.id = pac_set_id++, .records = {}};
    for (const auto& cap : recording_capa) {
      source_pac_set.records.push_back(
              {le_audio::types::LeAudioCodecId(cap.coding_format, cap.vendor_company_id,
                                               cap.vendor_codec_id),
               cap.codec_spec_caps,
               {/*metadata*/}});
    }
    pacs.source.push_back(source_pac_set);
  }

  Pacs::ServiceDescriptor pacs_descriptor{
          .pac_sets = pacs,
          .audio_locations = le_audio::types::BidirectionalPair<le_audio::types::AudioLocations>(
                  le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                          le_audio::codec_spec_conf::kLeAudioLocationFrontRight,
                  le_audio::codec_spec_conf::kLeAudioLocationMonoAudio),
          .supported_audio_contexts =
                  le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>(
                          le_audio::types::kLeAudioContextAllTypes,
                          le_audio::types::kLeAudioContextAllTypes),
  };
  return pacs_descriptor;
}

Ascs::ServiceDescriptor LeAudioServerConfigManager::GetAscsDescriptor() {
  return {
          .num_sink_ases = 2,
          .num_source_ases = 1,
  };
}

LeAudioCodecConfiguration LeAudioServerConfigManager::GetAudioSessionConfig() {
  // TODO(b/481255899): Align the stream parameters along the whole audio pipeline, to avoid
  // suboptimal audio quality conversions
  return LeAudioCodecConfiguration{
          .num_channels = 2,
          .sample_rate = audio::le_audio::kSampleRate32000,
          .bits_per_sample = audio::le_audio::kBitsPerSample16,
          .data_interval_us = LeAudioCodecConfiguration::kInterval10000Us,
  };
}

le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>
LeAudioServerConfigManager::GetAvailableAudioContexts(const RawAddress& /*address*/) {
  // TODO(b/480940695): Implement a more sophisticated policy to manage per peer device values
  return le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>(
          le_audio::types::kLeAudioContextAllTypes, le_audio::types::kLeAudioContextAllTypes);
}

std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                               std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
LeAudioServerConfigManager::VerifyQosParameters(
        std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                     ascs::AseStateQosConfiguration>>
                params) {
  std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
          result;

  for (auto const& [ase_id, _] : params) {
    // TODO(b/480075117): Verify the qos for the givec codec configuration against what the HAL
    //  gave us, using the API.
    log::debug("Qos Configuration from config manager OK for {}", ase_id);

    ascs::DataPathConfiguration data_path;

    auto offload_enabled =
            osi_property_get_bool("ro.bluetooth.leaudio_offload.supported", false) &&
            !osi_property_get_bool("persist.bluetooth.leaudio_offload.disabled", true);
    data_path.dataPathId = offload_enabled
                                   ? bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault
                                   : bluetooth::hci::iso_manager::kIsoDataPathHci;
    data_path.dataPathConfig = {};

    data_path.isoDataPathConfig.codecId = {bluetooth::hci::kIsoCodingFormatTransparent, 0, 0};
    data_path.isoDataPathConfig.isTransparent = true;
    data_path.isoDataPathConfig.controllerDelayUs = 0;
    data_path.isoDataPathConfig.configuration = {};

    result.insert_or_assign(ase_id, std::move(data_path));
  }

  return result;
}

}  // namespace le_audio
}  // namespace bluetooth
