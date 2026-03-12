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

#include "le_audio_utils.h"

#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>

#include <cstdint>
#include <sstream>
#include <type_traits>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "com_android_bluetooth_flags.h"
#include "hardware/bt_le_audio.h"
#include "le_audio/codec_manager.h"
#include "le_audio_types.h"

using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::LeAudioContextType;

namespace bluetooth::le_audio {
namespace utils {

size_t GetConfigurationHash(const bluetooth::le_audio::types::AudioSetConfiguration& conf) {
  /* This has should be use to represent CIG configuration. We want to use it to check
   * if changing configuration requires CIG reconfiguration
   */

  std::vector<uint8_t> value_to_hash;
  auto target_latency = conf.getTargetLatency();
  auto max_sdu = conf.getMaxSdu();

  value_to_hash.push_back(target_latency.sink);
  value_to_hash.push_back(target_latency.source);
  value_to_hash.push_back(static_cast<uint8_t>(max_sdu.sink));
  value_to_hash.push_back(static_cast<uint8_t>(max_sdu.sink >> 8));
  value_to_hash.push_back(static_cast<uint8_t>(max_sdu.source));
  value_to_hash.push_back(static_cast<uint8_t>(max_sdu.source >> 8));
  value_to_hash.push_back(conf.packing);

  auto hash = std::hash<std::string_view>{}(
          {reinterpret_cast<const char*>(value_to_hash.data()), value_to_hash.size()});

  log::info("{}, hash: {:#x}", conf.name, hash);
  return hash;
}

/* The returned LeAudioContextType should have its entry in the
 * AudioSetConfigurationProvider's ContextTypeToScenario mapping table.
 * Otherwise the AudioSetConfigurationProvider will fall back
 * to default scenario.
 */
LeAudioContextType AudioContentToLeAudioContext(audio_content_type_t content_type,
                                                audio_usage_t usage) {
  /* Check audio attribute usage of stream */
  switch (usage) {
    case AUDIO_USAGE_MEDIA:
      return LeAudioContextType::MEDIA;
    case AUDIO_USAGE_ASSISTANT:
      return LeAudioContextType::VOICEASSISTANTS;
    case AUDIO_USAGE_VOICE_COMMUNICATION:
    case AUDIO_USAGE_CALL_ASSISTANT:
      return LeAudioContextType::CONVERSATIONAL;
    case AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING:
      if (content_type == AUDIO_CONTENT_TYPE_SPEECH) {
        return LeAudioContextType::CONVERSATIONAL;
      }

      if (content_type == AUDIO_CONTENT_TYPE_SONIFICATION) {
        return LeAudioContextType::RINGTONE;
      }

      return LeAudioContextType::SOUNDEFFECTS;
    case AUDIO_USAGE_GAME:
      if (content_type == AUDIO_CONTENT_TYPE_SONIFICATION &&
          com_android_bluetooth_flags_leaudio_use_game_sonification_as_regular_sonification()) {
        return LeAudioContextType::SOUNDEFFECTS;
      }
      return LeAudioContextType::GAME;
    case AUDIO_USAGE_NOTIFICATION:
    case AUDIO_USAGE_NOTIFICATION_EVENT:
      return LeAudioContextType::NOTIFICATIONS;
    case AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
      return LeAudioContextType::RINGTONE;
    case AUDIO_USAGE_ALARM:
      return LeAudioContextType::ALERTS;
    case AUDIO_USAGE_EMERGENCY:
      return LeAudioContextType::EMERGENCYALARM;
    case AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY:
    case AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
      return LeAudioContextType::INSTRUCTIONAL;
    case AUDIO_USAGE_ASSISTANCE_SONIFICATION:
      return LeAudioContextType::SOUNDEFFECTS;
    default:
      break;
  }

  return LeAudioContextType::MEDIA;
}

AudioContexts GetAudioContextsFromSourceMetadata(
        const std::vector<struct playback_track_metadata_v7>& source_metadata) {
  AudioContexts track_contexts;
  for (const auto& entry : source_metadata) {
    auto track = entry.base;
    if (track.content_type == 0 && track.usage == 0) {
      continue;
    }

    log::info("usage={}({}), content_type={}({}), gain={:f}, tag:{}", usageToString(track.usage),
              track.usage, contentTypeToString(track.content_type), track.content_type, track.gain,
              entry.tags);

    if (isMetadataTagPresent(entry.tags, "VX_AOSP_SAMPLESOUND")) {
      track_contexts.set(LeAudioContextType::SOUNDEFFECTS);
    } else {
      track_contexts.set(AudioContentToLeAudioContext(track.content_type, track.usage));
    }
  }
  return track_contexts;
}

bluetooth::le_audio::btle_audio_codec_index_t translateLeAudioCodecIdToCodecType(
        const types::LeAudioCodecId& codecId, std::optional<uint32_t> sampling_frequency_hz) {
  if (codecId == types::LeAudioCodecIdLc3) {
    return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3;
  } else if (codecId == types::LeAudioCodecIdOpus) {
    if (sampling_frequency_hz.has_value() &&
        sampling_frequency_hz.value() > LeAudioCodecConfiguration::kSampleRate48000) {
      return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS_HI_RES;
    }
    return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS;
  }

  log::warn("Unable to translate codecID: {} to codec type index.", common::ToString(codecId));
  return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_INVALID;
}

types::LeAudioCodecId translateCodecTypeToLeAudioCodecId(btle_audio_codec_index_t codecIndex) {
  switch (codecIndex) {
    case bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3:
      return types::LeAudioCodecIdLc3;
    case bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS:
      return types::LeAudioCodecIdOpus;
    case bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_OPUS_HI_RES:
      return types::LeAudioCodecIdOpus;
    default:
      break;
  }
  log::warn("Unable to translate codec type index: {} to codecID.", +codecIndex);
  return types::LeAudioCodecId({.coding_format = types::kLeAudioCodingFormatVendorSpecific,
                                .vendor_company_id = types::kLeAudioVendorCompanyIdUndefined,
                                .vendor_codec_id = types::kLeAudioVendorCodecIdUndefined});
}
bluetooth::le_audio::btle_audio_sample_rate_index_t translateToBtLeAudioCodecConfigSampleRate(
        uint32_t sample_rate) {
  log::info("{}", sample_rate);
  switch (sample_rate) {
    case LeAudioCodecConfiguration::kSampleRate8000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_8000HZ;
    case LeAudioCodecConfiguration::kSampleRate11025:
      return LE_AUDIO_SAMPLE_RATE_INDEX_11025HZ;
    case LeAudioCodecConfiguration::kSampleRate16000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ;
    case LeAudioCodecConfiguration::kSampleRate22050:
      return LE_AUDIO_SAMPLE_RATE_INDEX_22050HZ;
    case LeAudioCodecConfiguration::kSampleRate24000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ;
    case LeAudioCodecConfiguration::kSampleRate32000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ;
    case LeAudioCodecConfiguration::kSampleRate44100:
      return LE_AUDIO_SAMPLE_RATE_INDEX_44100HZ;
    case LeAudioCodecConfiguration::kSampleRate48000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ;
    case LeAudioCodecConfiguration::kSampleRate88200:
      return LE_AUDIO_SAMPLE_RATE_INDEX_88200HZ;
    case LeAudioCodecConfiguration::kSampleRate96000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_96000HZ;
    case LeAudioCodecConfiguration::kSampleRate176400:
      return LE_AUDIO_SAMPLE_RATE_INDEX_176400HZ;
    case LeAudioCodecConfiguration::kSampleRate192000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_192000HZ;
    case LeAudioCodecConfiguration::kSampleRate384000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_384000HZ;
  }

  return LE_AUDIO_SAMPLE_RATE_INDEX_NONE;
}

bluetooth::le_audio::btle_audio_bits_per_sample_index_t translateToBtLeAudioCodecConfigBitPerSample(
        uint8_t bits_per_sample) {
  switch (bits_per_sample) {
    case 16:
      return bluetooth::le_audio::LE_AUDIO_BITS_PER_SAMPLE_INDEX_16;
    case 24:
      return bluetooth::le_audio::LE_AUDIO_BITS_PER_SAMPLE_INDEX_24;
    case 32:
      return bluetooth::le_audio::LE_AUDIO_BITS_PER_SAMPLE_INDEX_32;
  }
  return bluetooth::le_audio::LE_AUDIO_BITS_PER_SAMPLE_INDEX_NONE;
}

bluetooth::le_audio::btle_audio_channel_count_index_t translateToBtLeAudioCodecConfigChannelCount(
        uint8_t channel_count) {
  switch (channel_count) {
    case 1:
      return bluetooth::le_audio::LE_AUDIO_CHANNEL_COUNT_INDEX_1;
    case 2:
      return bluetooth::le_audio::LE_AUDIO_CHANNEL_COUNT_INDEX_2;
  }
  return bluetooth::le_audio::LE_AUDIO_CHANNEL_COUNT_INDEX_NONE;
}

bluetooth::le_audio::btle_audio_frame_duration_index_t translateToBtLeAudioCodecConfigFrameDuration(
        int frame_duration) {
  switch (frame_duration) {
    case 7500:
      return bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_7500US;
    case 10000:
      return bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_10000US;
    case 20000:
      return bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_20000US;
  }
  return bluetooth::le_audio::LE_AUDIO_FRAME_DURATION_INDEX_NONE;
}

void fillStreamParamsToBtLeAudioCodecConfig(
        const std::vector<struct types::AseConfiguration>& confs,
        bluetooth::le_audio::btle_audio_codec_config_t& out_config) {
  if (confs.size() == 0) {
    log::warn("Stream params are null");
    return;
  }

  auto config = confs.at(0).codec;

  out_config.codec_type =
          translateLeAudioCodecIdToCodecType(config.id, config.GetSamplingFrequencyHz());
  if (out_config.codec_type == bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_INVALID) {
    log::error("Invalid codec identifier: {}", common::ToString(config.id));
    return;
  }

  out_config.sample_rate =
          translateToBtLeAudioCodecConfigSampleRate(config.GetSamplingFrequencyHz());
  out_config.bits_per_sample =
          translateToBtLeAudioCodecConfigBitPerSample(config.GetBitsPerSample());
  out_config.frame_duration =
          translateToBtLeAudioCodecConfigFrameDuration(config.GetDataIntervalUs());
  out_config.octets_per_frame = config.GetOctetsPerFrame();
  out_config.channel_count =
          translateToBtLeAudioCodecConfigChannelCount(config.GetChannelCountPerIsoStream());
}

bool IsCodecUsingLtvFormat(const types::LeAudioCodecId& codec_id) {
  if (codec_id == types::LeAudioCodecIdLc3) {
    return true;
  }
  return false;
}

::bluetooth::le_audio::LeAudioCodecConfiguration
GetAudioSessionCodecConfigFromAudioSetConfiguration(
        const bluetooth::le_audio::types::AudioSetConfiguration& audio_set_conf,
        uint8_t remote_direction) {
  /* Note: For now we expect that each ASE in a particular direction needs
   *       exactly the same audio codec parameters.
   */

  LeAudioCodecConfiguration group_config = {0, 0, 0, 0};
  for (const auto& conf : audio_set_conf.confs.get(remote_direction)) {
    if (group_config.sample_rate != 0 &&
        conf.codec.GetSamplingFrequencyHz() != group_config.sample_rate) {
      log::warn(
              "Stream configuration could not be determined (multiple, different "
              "sampling frequencies) for remote_direction: {:#x}",
              remote_direction);
      break;
    }
    group_config.sample_rate = conf.codec.GetSamplingFrequencyHz();

    if (group_config.data_interval_us != 0 &&
        conf.codec.GetDataIntervalUs() != group_config.data_interval_us) {
      log::warn(
              "Stream configuration could not be determined (multiple, different "
              "data intervals) for remote_direction: {:#x}",
              remote_direction);
      break;
    }
    group_config.data_interval_us = conf.codec.GetDataIntervalUs();

    if (group_config.bits_per_sample != 0 &&
        conf.codec.GetBitsPerSample() != group_config.bits_per_sample) {
      log::warn(
              "Stream configuration could not be determined (multiple, different "
              "bits per sample) for remote_direction: {:#x}",
              remote_direction);
      break;
    }
    group_config.bits_per_sample = conf.codec.GetBitsPerSample();
    group_config.num_channels += conf.codec.GetChannelCountPerIsoStream();
  }
  if (group_config.num_channels > 2) {
    group_config.num_channels = 2;
  }

  return group_config;
}

types::LeAudioConfigurationStrategy GetStrategyForAseConfig(
        const std::vector<le_audio::types::AseConfiguration>& cfgs, uint8_t device_cnt) {
  if (cfgs.size() == 0) {
    return types::LeAudioConfigurationStrategy::RFU;
  }

  /* Banded headphones or the Classic TWS style topology (a single device) */
  if (device_cnt == 1) {
    if (cfgs.at(0).codec.GetChannelCountPerIsoStream() == 1) {
      /* One mono ASE - could be a single channel microphone */
      if (cfgs.size() == 1) {
        return types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE;
      }

      /* Each channel on a dedicated ASE - TWS style split channel re-routing */
      return types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE;
    }

    /* Banded headphones with 1 ASE - requires two channels per CIS */
    return types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE;
  }

  if (!com_android_bluetooth_flags_leaudio_always_use_group_size_to_check_audio_config()) {
    // We need at least 2 ASEs in the group config to set up more than one device
    if (cfgs.size() == 1) {
      return types::LeAudioConfigurationStrategy::RFU;
    }
  }

  /* The common one channel per device topology */
  return types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE;
}

static bool IsCodecConfigSupported(const types::LeAudioLtvMap& pacs,
                                   const types::LeAudioLtvMap& reqs, uint8_t channel_cnt_per_ase) {
  auto caps = pacs.GetAsCoreCodecCapabilities();
  auto config = reqs.GetAsCoreCodecConfig();

  /* Sampling frequency */
  if (!caps.HasSupportedSamplingFrequencies() || !config.sampling_frequency) {
    log::debug("Missing supported sampling frequencies capability");
    return false;
  }
  if (!caps.IsSamplingFrequencyConfigSupported(config.sampling_frequency.value())) {
    log::debug("Cfg: SamplingFrequency= {:#x}", config.sampling_frequency.value());
    log::debug("Cap: SupportedSamplingFrequencies= {:#x}",
               caps.supported_sampling_frequencies.value());
    log::debug("Sampling frequency not supported");
    return false;
  }

  /* Channel counts */
  if (!caps.IsAudioChannelCountsSupported(channel_cnt_per_ase)) {
    log::debug("Cfg: Allocated channel count= {:#x}", channel_cnt_per_ase);
    log::debug("Cap: Supported channel counts= {:#x}",
               caps.supported_audio_channel_counts.value_or(1));
    log::debug("Channel count not supported");
    return false;
  }

  /* Frame duration */
  if (!caps.HasSupportedFrameDurations() || !config.frame_duration) {
    log::debug("Missing supported frame durations capability");
    return false;
  }
  if (!caps.IsFrameDurationConfigSupported(config.frame_duration.value())) {
    log::debug("Cfg: FrameDuration= {:#x}", config.frame_duration.value());
    log::debug("Cap: SupportedFrameDurations= {:#x}", caps.supported_frame_durations.value());
    log::debug("Frame duration not supported");
    return false;
  }

  /* Octets per frame */
  if (!caps.HasSupportedOctetsPerCodecFrame() || !config.octets_per_codec_frame) {
    log::debug("Missing supported octets per codec frame");
    return false;
  }
  if (!caps.IsOctetsPerCodecFrameConfigSupported(config.octets_per_codec_frame.value())) {
    log::debug("Cfg: Octets per frame={}", config.octets_per_codec_frame.value());
    log::debug("Cap: Min octets per frame={}", caps.supported_min_octets_per_codec_frame.value());
    log::debug("Cap: Max octets per frame={}", caps.supported_max_octets_per_codec_frame.value());
    log::debug("Octets per codec frame outside the capabilities");
    return false;
  }

  return true;
}

static bool IsCodecConfigSettingSupported(const types::acs_ac_record& pac,
                                          const types::CodecConfigSetting& codec_config_setting) {
  const auto& codec_id = codec_config_setting.id;
  if (codec_id != pac.codec_id) {
    return false;
  }

  log::debug("Verifying coding format: {:#02x} ", codec_id.coding_format);

  if (utils::IsCodecUsingLtvFormat(codec_id)) {
    log::assert_that(!pac.codec_spec_caps.IsEmpty(),
                     "Codec specific capabilities are not parsed appropriately.");
    return IsCodecConfigSupported(pac.codec_spec_caps, codec_config_setting.params,
                                  codec_config_setting.GetChannelCountPerIsoStream());
  }

  log::error("Codec {}, seems to be not supported here.", bluetooth::common::ToString(codec_id));
  return false;
}

const struct types::acs_ac_record* GetConfigurationSupportedPac(
        const types::PublishedAudioCapabilities& pacs,
        const types::CodecConfigSetting& codec_config_setting) {
  for (const auto& pac_tuple : pacs) {
    for (const auto& pac : std::get<1>(pac_tuple)) {
      if (utils::IsCodecConfigSettingSupported(pac, codec_config_setting)) {
        return &pac;
      }
    };
  }
  /* Doesn't match required configuration with any PAC */
  if (pacs.size() == 0) {
    log::error("No PAC records");
  } else {
    log::error("No matching PAC record");
  }
  return nullptr;
}

bool IsAseConfigMatchedWithPreferredRequirements(
        const std::vector<struct types::AseConfiguration>& ase_confs,
        const std::vector<
                CodecManager::UnicastConfigurationRequirements::DeviceDirectionRequirements>& reqs,
        uint8_t channel_cnt_per_ase) {
  if (ase_confs.empty() || reqs.empty() || ase_confs.size() != reqs.size()) {
    return false;
  }

  for (auto i = 0; i < static_cast<int>(ase_confs.size()); ++i) {
    const auto& ase_config = ase_confs.at(i).codec.params.GetAsCoreCodecConfig();
    const auto& req_config = reqs.at(i).params.GetAsCoreCodecConfig();

    /* Sampling frequency */
    if (!ase_config.sampling_frequency || !req_config.sampling_frequency) {
      log::debug("Missing sampling frequencies capability");
      return false;
    }
    if (ase_config.sampling_frequency.value() != req_config.sampling_frequency.value()) {
      log::debug("Ase cfg: SamplingFrequency= {:#x}", ase_config.sampling_frequency.value());
      log::debug("Req cfg: SamplingFrequency= {:#x}", req_config.sampling_frequency.value());
      log::debug("Sampling frequency not supported");
      return false;
    }

    /* Channel counts */
    if (ase_confs.at(i).codec.GetChannelCountPerIsoStream() != channel_cnt_per_ase) {
      log::debug("Ase cfg: Allocated channel count= {:#x}",
                 ase_confs.at(i).codec.GetChannelCountPerIsoStream());
      log::debug("Req cfg: Allocated channel counts= {:#x}", channel_cnt_per_ase);
      log::debug("Channel count not supported");
      return false;
    }

    /* Frame duration */
    if (!ase_config.frame_duration || !req_config.frame_duration) {
      log::debug("Missing frame duration capability");
      return false;
    }
    if (ase_config.frame_duration.value() != ase_config.frame_duration.value()) {
      log::debug("Ase cfg: FrameDuration= {:#x}", ase_config.frame_duration.value());
      log::debug("Req cfg: FrameDuration= {:#x}", req_config.frame_duration.value());
      log::debug("Frame duration not supported");
      return false;
    }

    /* Octets per frame */
    if (!ase_config.octets_per_codec_frame || !req_config.octets_per_codec_frame) {
      log::debug("Missing octets per codec frame");
      return false;
    }
    if (ase_config.octets_per_codec_frame.value() != req_config.octets_per_codec_frame.value()) {
      log::debug("Ase cfg: Octets per frame={}", ase_config.octets_per_codec_frame.value());
      log::debug("Req cfg: Octets per frame={}", req_config.octets_per_codec_frame.value());
      return false;
    }
  }

  return true;
}

}  // namespace utils
}  // namespace bluetooth::le_audio
