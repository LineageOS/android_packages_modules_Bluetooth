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

#define LOG_TAG "bluetooth-a2dp-aidl"

#include "a2dp_encoding_aidl_utils.h"

#include <vector>

#include "a2dp_encoding.h"
#include "audio_aidl_interfaces.h"
#include "hardware/bt_av.h"
#include "stack/include/a2dp_constants.h"

namespace bluetooth::audio::aidl::a2dp {

using ::aidl::android::hardware::bluetooth::audio::A2dpConfiguration;
using ::aidl::android::hardware::bluetooth::audio::ChannelMode;
using ::aidl::android::hardware::bluetooth::audio::CodecId;
using ::aidl::android::hardware::bluetooth::audio::CodecInfo;
using ::aidl::android::hardware::bluetooth::audio::CodecParameters;
using ::bluetooth::audio::a2dp::provider::a2dp_configuration;

btav_a2dp_codec_channel_mode_t convertChannelMode(ChannelMode channel_mode) {
  switch (channel_mode) {
    case ChannelMode::MONO:
      return BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
    case ChannelMode::STEREO:
      return BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
    default:
      log::error("unknown channel mode");
      break;
  }
  return BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
}

btav_a2dp_codec_channel_mode_t convertChannelMode(const std::vector<ChannelMode>& channel_mode) {
  int32_t result = 0;
  for (auto const& mode : channel_mode) {
    result |= convertChannelMode(mode);
  }
  return static_cast<btav_a2dp_codec_channel_mode_t>(result);
}

btav_a2dp_codec_sample_rate_t convertSampleRate(int32_t sampling_frequency_hz) {
  switch (sampling_frequency_hz) {
    case 44100:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
    case 48000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    case 88200:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_88200;
    case 96000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
    case 176400:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_176400;
    case 192000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
    case 16000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_16000;
    case 24000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_24000;
    default:
      log::error("unknown sampling frequency {}", sampling_frequency_hz);
      break;
  }
  return BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
}

btav_a2dp_codec_sample_rate_t convertSampleRate(const std::vector<int32_t>& sampling_frequency_hz) {
  int32_t result = 0;
  for (auto const& sample_rate : sampling_frequency_hz) {
    result |= convertSampleRate(sample_rate);
  }
  return static_cast<btav_a2dp_codec_sample_rate_t>(result);
}

btav_a2dp_codec_bits_per_sample_t convertBitsPerSample(int32_t bitdepth) {
  switch (bitdepth) {
    case 16:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
    case 24:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
    case 32:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
    default:
      log::error("unknown bit depth {}", bitdepth);
      break;
  }
  return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
}

btav_a2dp_codec_bits_per_sample_t convertBitsPerSample(const std::vector<int32_t>& bitdepth) {
  int32_t result = 0;
  for (auto const& bits_per_sample : bitdepth) {
    result |= convertBitsPerSample(bits_per_sample);
  }
  return static_cast<btav_a2dp_codec_bits_per_sample_t>(result);
}

/***
 * Helper to convert CodecId and byte[] configuration to
 * the Media Codec Capabilities format.
 * Returns true if the capabilities were successfully converted.
 ***/
bool convertCodecCapabilities(const CodecId& codec_id, const std::vector<uint8_t>& capabilities,
                              uint8_t* codec_info) {
  switch (codec_id.getTag()) {
    case CodecId::a2dp: {
      auto id = codec_id.get<CodecId::a2dp>();
      codec_info[0] = 2 + capabilities.size();
      codec_info[1] = AVDT_MEDIA_TYPE_AUDIO << 4;
      codec_info[2] = static_cast<uint8_t>(id);
      memcpy(codec_info + 3, capabilities.data(), capabilities.size());
      return true;
    }
    case CodecId::vendor: {
      auto id = codec_id.get<CodecId::vendor>();
      uint32_t vendor_id = static_cast<uint32_t>(id.id);
      uint16_t codec_id = static_cast<uint16_t>(id.codecId);
      codec_info[0] = 8 + capabilities.size();
      codec_info[1] = AVDT_MEDIA_TYPE_AUDIO << 4;
      codec_info[2] = A2DP_MEDIA_CT_NON_A2DP;
      codec_info[3] = static_cast<uint8_t>(vendor_id >> 0);
      codec_info[4] = static_cast<uint8_t>(vendor_id >> 8);
      codec_info[5] = static_cast<uint8_t>(vendor_id >> 16);
      codec_info[6] = static_cast<uint8_t>(vendor_id >> 24);
      codec_info[7] = static_cast<uint8_t>(codec_id >> 0);
      codec_info[8] = static_cast<uint8_t>(codec_id >> 8);
      memcpy(codec_info + 9, capabilities.data(), capabilities.size());
      return true;
    }
    case CodecId::core:
    default:
      break;
  }
  return false;
}

a2dp_configuration convertA2dpConfiguration(const A2dpConfiguration& config) {
  a2dp_configuration result_config;
  result_config.remote_seid = config.remoteSeid;
  result_config.vendor_specific_parameters = config.parameters.vendorSpecificParameters;
  convertCodecCapabilities(config.id, config.configuration, result_config.codec_config);
  result_config.codec_parameters.channel_mode = convertChannelMode(config.parameters.channelMode);
  result_config.codec_parameters.sample_rate =
          convertSampleRate(config.parameters.samplingFrequencyHz);
  result_config.codec_parameters.bits_per_sample = convertBitsPerSample(config.parameters.bitdepth);
  return result_config;
}

void convertCodecParameters(const CodecParameters& aidl_params,
                            btav_a2dp_codec_config_t* stack_params) {
  log::assert_that(stack_params != nullptr, "stack_params != nullptr");
  stack_params->channel_mode = convertChannelMode(aidl_params.channelMode);
  stack_params->sample_rate = convertSampleRate(aidl_params.samplingFrequencyHz);
  stack_params->bits_per_sample = convertBitsPerSample(aidl_params.bitdepth);
}

std::optional<CodecId> convertCodecId(::bluetooth::a2dp::CodecId codec_id) {
  uint16_t codec_specific_id = 0;
  uint16_t vendor_id = 0;
  CodecId id;

  uint8_t codec_type = static_cast<uint8_t>(codec_id);
  switch (codec_type) {
    case A2DP_MEDIA_CT_SBC:
      codec_specific_id = static_cast<uint16_t>(::bluetooth::a2dp::CodecId::SBC);
      break;
    case A2DP_MEDIA_CT_AAC:
      codec_specific_id = static_cast<uint16_t>(::bluetooth::a2dp::CodecId::AAC);
      break;
    case A2DP_MEDIA_CT_NON_A2DP:
      codec_specific_id = static_cast<uint16_t>(static_cast<uint64_t>(codec_id) >> 24);
      vendor_id = static_cast<uint16_t>(static_cast<uint64_t>(codec_id) >> 8);
      break;
    default:
      log::error("unknown codec_type {}", codec_type);
      return std::nullopt;
  }

  if (vendor_id == 0) {
    switch (codec_id) {
      case ::bluetooth::a2dp::CodecId::SBC:
      case ::bluetooth::a2dp::CodecId::AAC:
        id = CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(codec_specific_id));
        break;
      default:
        log::error("unknown codec_specific_id {}", codec_specific_id);
        return std::nullopt;
    }
  } else {
    id = CodecId::make<CodecId::vendor>(
            CodecId::Vendor({.id = (int32_t)vendor_id, .codecId = codec_specific_id}));
  }

  return std::make_optional<CodecId>(id);
}

std::optional<::bluetooth::a2dp::CodecId> convertCodecId(CodecId codec_id) {
  ::bluetooth::a2dp::CodecId id = {};

  switch (codec_id.getTag()) {
    case CodecId::Tag::a2dp:
      id = static_cast<::bluetooth::a2dp::CodecId>(codec_id.get<CodecId::Tag::a2dp>());
      break;
    case CodecId::Tag::vendor:
      id = static_cast<::bluetooth::a2dp::CodecId>(::bluetooth::a2dp::VendorCodecId(
              static_cast<uint16_t>(codec_id.get<CodecId::Tag::vendor>().id),
              static_cast<uint16_t>(codec_id.get<CodecId::Tag::vendor>().codecId)));
      break;
    case CodecId::Tag::core:
    default:
      return std::nullopt;
  }
  return std::make_optional<::bluetooth::a2dp::CodecId>(id);
}

std::optional<btav_a2dp_codec_info_t> convertCodecInfo(const CodecInfo& codec_info) {
  btav_a2dp_codec_info_t provider_codec_info = {};
  auto transport = codec_info.transport.get<CodecInfo::Transport::a2dp>();

  auto stack_codec_id = convertCodecId(codec_info.id);
  if (!stack_codec_id.has_value()) {
    log::error("AIDL codec ID unrecognised: {}", transport.toString());
    return std::nullopt;
  }

  provider_codec_info.codec_id = stack_codec_id.value();
  provider_codec_info.name = codec_info.name;

  if (!convertCodecCapabilities(codec_info.id, transport.capabilities,
                                provider_codec_info.media_codec_capabilites)) {
    log::error("convertCodecCapabilities failed for new codec: {}, id: {}",
               provider_codec_info.name,
               ::bluetooth::a2dp::CodecIdToString(provider_codec_info.codec_id));
    return std::nullopt;
  }

  provider_codec_info.codec_capabilities.codec_priority = BTAV_A2DP_CODEC_PRIORITY_DEFAULT;
  provider_codec_info.codec_capabilities.channel_mode = convertChannelMode(transport.channelMode);
  provider_codec_info.codec_capabilities.sample_rate =
          convertSampleRate(transport.samplingFrequencyHz);
  provider_codec_info.codec_capabilities.bits_per_sample = convertBitsPerSample(transport.bitdepth);
  provider_codec_info.lossless = transport.lossless;

  return std::make_optional<btav_a2dp_codec_info_t>(provider_codec_info);
}

}  // namespace bluetooth::audio::aidl::a2dp
