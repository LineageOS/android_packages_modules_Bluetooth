/*
 * Copyright 2025 The Android Open Source Project
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

/* Vendor Codec Capability - a temporary solution
 *
 * WARNING: Please do note that this module will be deprecated once the BT Audio Hal API adds
 * support for the vendor codec capability translation and b/416662050 fixed. It is highly addvised
 * to NOT add any additional codecs here.
 */

#pragma once

#include "hardware/bt_le_audio.h"
#include "le_audio_types.h"
#include "le_audio_utils.h"

namespace bluetooth::le_audio::vendor {
namespace google {

namespace opus {
constexpr uint8_t kOpusCodecConfigFrameDur20000us = 0x02;
constexpr uint8_t kOpusDefaultBitDepth = 16;

static const std::map<uint8_t, bluetooth::le_audio::btle_audio_frame_duration_index_t>
        frame_duration_map = {
                {codec_spec_conf::kLeAudioCodecFrameDur7500us,
                 bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_7500US},
                {codec_spec_conf::kLeAudioCodecFrameDur10000us,
                 bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_10000US},
                {kOpusCodecConfigFrameDur20000us,
                 bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_20000US},
};

static void FillRemoteCapabilityToBtLeAudioCodecConfigs(
        types::LeAudioCodecId codec_id, const std::vector<uint8_t>& capabilities,
        std::vector<btle_audio_codec_config_t>& vec) {
  // Note: OPUS is using core ltv format with a custom frame duration LTV value for 20ms
  types::LeAudioLtvMap ltv;
  if (!ltv.Parse(capabilities.data(), capabilities.size())) {
    log::error("Error parsing Opus codec capabilities");
    return;
  }
  auto caps = ltv.GetAsCoreCodecCapabilities();

  for (uint8_t freq_bit = codec_spec_conf::kLeAudioSamplingFreq8000Hz;
       freq_bit <= codec_spec_conf::kLeAudioSamplingFreq384000Hz; freq_bit++) {
    if (!caps.IsSamplingFrequencyConfigSupported(freq_bit)) {
      continue;
    }
    for (auto [fd_bit, fd_idx] : frame_duration_map) {
      if (!caps.IsFrameDurationConfigSupported(fd_bit)) {
        continue;
      }
      if (!caps.HasSupportedAudioChannelCounts()) {
        btle_audio_codec_config_t config = {
                .codec_type = utils::translateLeAudioCodecIdToCodecType(
                        codec_id, types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                .sample_rate = utils::translateToBtLeAudioCodecConfigSampleRate(
                        types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                .bits_per_sample =
                        utils::translateToBtLeAudioCodecConfigBitPerSample(kOpusDefaultBitDepth),
                .channel_count = utils::translateToBtLeAudioCodecConfigChannelCount(1),
                .frame_duration = fd_idx,
        };
        vec.push_back(config);
      } else {
        for (int chan_bit = 1; chan_bit <= 2; chan_bit++) {
          if (!caps.IsAudioChannelCountsSupported(chan_bit)) {
            continue;
          }
          btle_audio_codec_config_t config = {
                  .codec_type = utils::translateLeAudioCodecIdToCodecType(
                          codec_id,
                          types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                  .sample_rate = utils::translateToBtLeAudioCodecConfigSampleRate(
                          types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                  .bits_per_sample =
                          utils::translateToBtLeAudioCodecConfigBitPerSample(kOpusDefaultBitDepth),
                  .channel_count = utils::translateToBtLeAudioCodecConfigChannelCount(chan_bit),
                  .frame_duration = fd_idx,
          };
          vec.push_back(config);
        }
      }
    }
  }
}

}  // namespace opus

static void FillRemoteCapabilityToBtLeAudioCodecConfigs(
        types::LeAudioCodecId codec_id, const std::vector<uint8_t>& capabilities,
        std::vector<bluetooth::le_audio::btle_audio_codec_config_t>& out_vec) {
  switch (codec_id.vendor_codec_id) {
    case types::kLeAudioVendorCodecIdOpus: {
      opus::FillRemoteCapabilityToBtLeAudioCodecConfigs(codec_id, capabilities, out_vec);
    } break;
    default:
      log::error("Unknown vendor codec identifier: {}", common::ToString(codec_id));
      break;
  }
}

static bool IsKnownCodec(uint16_t vendor_codec_id) {
  switch (vendor_codec_id) {
    case types::kLeAudioVendorCodecIdOpus:
      return true;
    default:
      log::error("Unknown vendor codec identifier: {}", +vendor_codec_id);
      return false;
  }
  return false;
}
}  // namespace google

static bool IsKnownCodec(const types::LeAudioCodecId& codec_id) {
  if (codec_id.coding_format != types::kLeAudioCodingFormatVendorSpecific) {
    log::error("Codec: {} is not a vendor specific coding format.", common::ToString(codec_id));
    return false;
  }

  switch (codec_id.vendor_company_id) {
    case types::kLeAudioVendorCompanyIdGoogle:
      return google::IsKnownCodec(codec_id.vendor_codec_id);
    default:
      log::error("Unknown vendor codec: {}", common::ToString(codec_id));
      return false;
  }
  return false;
}

static void FillRemoteCapabilityToBtLeAudioCodecConfigs(
        const types::LeAudioCodecId& codec_id, const std::vector<uint8_t>& capabilities,
        std::vector<bluetooth::le_audio::btle_audio_codec_config_t>& out_vec) {
  if (codec_id.coding_format != types::kLeAudioCodingFormatVendorSpecific) {
    log::error("Codec: {} is not a vendor specific coding format.", common::ToString(codec_id));
    return;
  }

  switch (codec_id.vendor_company_id) {
    case types::kLeAudioVendorCompanyIdGoogle:
      google::FillRemoteCapabilityToBtLeAudioCodecConfigs(codec_id, capabilities, out_vec);
      break;
    default:
      log::error("Unknown vendor codec: {}", common::ToString(codec_id));
      break;
  }
}
}  // namespace bluetooth::le_audio::vendor
