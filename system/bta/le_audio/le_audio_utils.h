/*
 * Copyright 2022 The Android Open Source Project
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

#pragma once

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif

#include <hardware/bt_le_audio.h>

#include <bitset>
#include <string>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "le_audio_types.h"

namespace bluetooth::le_audio {
namespace utils {
types::LeAudioContextType AudioContentToLeAudioContext(audio_content_type_t content_type,
                                                       audio_usage_t usage);
types::AudioContexts GetAudioContextsFromSourceMetadata(
        const std::vector<struct playback_track_metadata_v7>& source_metadata);
types::AudioContexts GetAudioContextsFromSinkMetadata(
        const std::vector<struct record_track_metadata_v7>& sink_metadata);
inline uint8_t GetTargetLatencyForAudioContext(types::LeAudioContextType ctx) {
  switch (ctx) {
    case types::LeAudioContextType::MEDIA:
      return types::kTargetLatencyHigherReliability;

    case types::LeAudioContextType::LIVE:
      FALLTHROUGH_INTENDED;
    case types::LeAudioContextType::GAME:
      return types::kTargetLatencyLower;

    case types::LeAudioContextType::RINGTONE:
      FALLTHROUGH_INTENDED;
    case types::LeAudioContextType::CONVERSATIONAL:
      return types::kTargetLatencyBalancedLatencyReliability;

    default:
      return types::kTargetLatencyUndefined;
  }

  return types::kTargetLatencyUndefined;
}

inline const char* audioSourceToStr(audio_source_t source) {
  const char* strArr[] = {"AUDIO_SOURCE_DEFAULT",           "AUDIO_SOURCE_MIC",
                          "AUDIO_SOURCE_VOICE_UPLINK",      "AUDIO_SOURCE_VOICE_DOWNLINK",
                          "AUDIO_SOURCE_VOICE_CALL",        "AUDIO_SOURCE_CAMCORDER",
                          "AUDIO_SOURCE_VOICE_RECOGNITION", "AUDIO_SOURCE_VOICE_COMMUNICATION",
                          "AUDIO_SOURCE_REMOTE_SUBMIX",     "AUDIO_SOURCE_UNPROCESSED",
                          "AUDIO_SOURCE_VOICE_PERFORMANCE"};

  if (static_cast<uint32_t>(source) < (sizeof(strArr) / sizeof(strArr[0]))) {
    return strArr[source];
  }
  return "UNKNOWN";
}

inline bool isMetadataTagPresent(const char* tags, const char* tag) {
  std::istringstream iss(tags);
  std::string t;
  while (std::getline(iss, t, AUDIO_ATTRIBUTES_TAGS_SEPARATOR)) {
    if (t.compare(tag) == 0) {
      log::debug("Tag {} is present", t);
      return true;
    }
  }
  return false;
}

inline std::string usageToString(audio_usage_t usage) {
  switch (usage) {
    case AUDIO_USAGE_UNKNOWN:
      return "USAGE_UNKNOWN";
    case AUDIO_USAGE_MEDIA:
      return "USAGE_MEDIA";
    case AUDIO_USAGE_VOICE_COMMUNICATION:
      return "USAGE_VOICE_COMMUNICATION";
    case AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING:
      return "USAGE_VOICE_COMMUNICATION_SIGNALLING";
    case AUDIO_USAGE_ALARM:
      return "USAGE_ALARM";
    case AUDIO_USAGE_NOTIFICATION:
      return "USAGE_NOTIFICATION";
    case AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
      return "USAGE_NOTIFICATION_TELEPHONY_RINGTONE";
    case AUDIO_USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
      return "USAGE_NOTIFICATION_COMMUNICATION_REQUEST";
    case AUDIO_USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
      return "USAGE_NOTIFICATION_COMMUNICATION_INSTANT";
    case AUDIO_USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
      return "USAGE_NOTIFICATION_COMMUNICATION_DELAYED";
    case AUDIO_USAGE_NOTIFICATION_EVENT:
      return "USAGE_NOTIFICATION_EVENT";
    case AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY:
      return "USAGE_ASSISTANCE_ACCESSIBILITY";
    case AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
      return "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE";
    case AUDIO_USAGE_ASSISTANCE_SONIFICATION:
      return "USAGE_ASSISTANCE_SONIFICATION";
    case AUDIO_USAGE_GAME:
      return "USAGE_GAME";
    case AUDIO_USAGE_ASSISTANT:
      return "USAGE_ASSISTANT";
    case AUDIO_USAGE_CALL_ASSISTANT:
      return "USAGE_CALL_ASSISTANT";
    case AUDIO_USAGE_EMERGENCY:
      return "USAGE_EMERGENCY";
    case AUDIO_USAGE_SAFETY:
      return "USAGE_SAFETY";
    case AUDIO_USAGE_VEHICLE_STATUS:
      return "USAGE_VEHICLE_STATUS";
    case AUDIO_USAGE_ANNOUNCEMENT:
      return "USAGE_ANNOUNCEMENT";
    default:
      return "unknown usage ";
  }
}

inline std::string contentTypeToString(audio_content_type_t content_type) {
  switch (content_type) {
    case AUDIO_CONTENT_TYPE_UNKNOWN:
      return "CONTENT_TYPE_UNKNOWN";
    case AUDIO_CONTENT_TYPE_SPEECH:
      return "CONTENT_TYPE_SPEECH";
    case AUDIO_CONTENT_TYPE_MUSIC:
      return "CONTENT_TYPE_MUSIC";
    case AUDIO_CONTENT_TYPE_MOVIE:
      return "CONTENT_TYPE_MOVIE";
    case AUDIO_CONTENT_TYPE_SONIFICATION:
      return "CONTENT_TYPE_SONIFICATION";
    default:
      return "unknown content type ";
  }
}

/* Helpers to get btle_audio_codec_config_t for Java */
bluetooth::le_audio::btle_audio_codec_index_t translateLeAudioCodecIdToCodecType(
        const types::LeAudioCodecId& codecId);
types::LeAudioCodecId translateCodecTypeToLeAudioCodecId(btle_audio_codec_index_t codecIndex);

bluetooth::le_audio::btle_audio_sample_rate_index_t translateToBtLeAudioCodecConfigSampleRate(
        uint32_t sample_rate_capa);
bluetooth::le_audio::btle_audio_bits_per_sample_index_t translateToBtLeAudioCodecConfigBitPerSample(
        uint8_t bits_per_sample);
bluetooth::le_audio::btle_audio_channel_count_index_t translateToBtLeAudioCodecConfigChannelCount(
        uint8_t channel_count);
bluetooth::le_audio::btle_audio_frame_duration_index_t translateToBtLeAudioCodecConfigFrameDuration(
        int frame_duration);
void fillStreamParamsToBtLeAudioCodecConfig(
        const std::vector<struct types::AseConfiguration>& confs,
        bluetooth::le_audio::btle_audio_codec_config_t& out_config);

std::vector<bluetooth::le_audio::btle_audio_codec_config_t> GetRemoteBtLeAudioCodecConfigFromPac(
        const types::PublishedAudioCapabilities& group_pacs);
bool IsCodecUsingLtvFormat(const types::LeAudioCodecId& codec_id);
types::LeAudioConfigurationStrategy GetStrategyForAseConfig(
        const std::vector<le_audio::types::AseConfiguration>& cfgs, uint8_t device_cnt);
::bluetooth::le_audio::LeAudioCodecConfiguration
GetAudioSessionCodecConfigFromAudioSetConfiguration(
        const ::bluetooth::le_audio::types::AudioSetConfiguration& audio_set_conf,
        uint8_t remote_direction);
const struct types::acs_ac_record* GetConfigurationSupportedPac(
        const ::bluetooth::le_audio::types::PublishedAudioCapabilities& pacs,
        const ::bluetooth::le_audio::types::CodecConfigSetting& codec_config_setting);
bool IsAseConfigMatchedWithPreferredRequirements(
        const std::vector<struct types::AseConfiguration>& ase_confs,
        const std::vector<
                CodecManager::UnicastConfigurationRequirements::DeviceDirectionRequirements>& reqs,
        uint8_t channel_cnt_per_ase);
}  // namespace utils
}  // namespace bluetooth::le_audio

namespace std {
template <>
struct formatter<audio_usage_t> : enum_formatter<audio_usage_t> {};
template <>
struct formatter<audio_content_type_t> : enum_formatter<audio_content_type_t> {};
template <>
struct formatter<audio_source_t> : enum_formatter<audio_source_t> {};
template <>
struct formatter<audio_devices_t> : enum_formatter<audio_devices_t> {};
}  // namespace std