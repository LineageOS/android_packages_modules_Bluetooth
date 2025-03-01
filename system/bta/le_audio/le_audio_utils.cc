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

#include <cstdint>
#include <sstream>
#include <string>
#include <type_traits>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "common/strings.h"
#include "hardware/bt_le_audio.h"
#include "le_audio/codec_manager.h"
#include "le_audio_types.h"

using bluetooth::common::ToString;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::LeAudioContextType;

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

namespace bluetooth::le_audio {
namespace utils {

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
      return LeAudioContextType::GAME;
    case AUDIO_USAGE_NOTIFICATION:
      return LeAudioContextType::NOTIFICATIONS;
    case AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
      return LeAudioContextType::RINGTONE;
    case AUDIO_USAGE_ALARM:
      return LeAudioContextType::ALERTS;
    case AUDIO_USAGE_EMERGENCY:
      return LeAudioContextType::EMERGENCYALARM;
    case AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
      return LeAudioContextType::INSTRUCTIONAL;
    case AUDIO_USAGE_ASSISTANCE_SONIFICATION:
      return LeAudioContextType::SOUNDEFFECTS;
    default:
      break;
  }

  return LeAudioContextType::MEDIA;
}

static std::string usageToString(audio_usage_t usage) {
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

static std::string contentTypeToString(audio_content_type_t content_type) {
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

static const char* audioSourceToStr(audio_source_t source) {
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

static bool isMetadataTagPresent(const char* tags, const char* tag) {
  std::istringstream iss(tags);
  std::string t;
  while (std::getline(iss, t, AUDIO_ATTRIBUTES_TAGS_SEPARATOR)) {
    log::verbose("Tag {}", t);
    if (t.compare(tag) == 0) {
      return true;
    }
  }
  return false;
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

AudioContexts GetAudioContextsFromSinkMetadata(
        const std::vector<struct record_track_metadata_v7>& sink_metadata) {
  AudioContexts all_track_contexts;

  for (const auto& entry : sink_metadata) {
    auto track = entry.base;
    if (track.source == AUDIO_SOURCE_INVALID) {
      continue;
    }
    LeAudioContextType track_context;

    log::debug(
            "source={}(0x{:02x}), gain={:f}, destination device=0x{:08x}, "
            "destination device address={:32s}",
            audioSourceToStr(track.source), track.source, track.gain, track.dest_device,
            track.dest_device_address);

    if (track.source == AUDIO_SOURCE_MIC) {
      track_context = LeAudioContextType::LIVE;

    } else if (track.source == AUDIO_SOURCE_VOICE_COMMUNICATION) {
      track_context = LeAudioContextType::CONVERSATIONAL;

    } else {
      /* Fallback to voice assistant
       * This will handle also a case when the device is
       * AUDIO_SOURCE_VOICE_RECOGNITION
       */
      track_context = LeAudioContextType::VOICEASSISTANTS;
      log::warn(
              "Could not match the recording track type to group available "
              "context. Using context {}.",
              ToString(track_context));
    }

    all_track_contexts.set(track_context);
  }

  log::info("Allowed contexts from sink metadata: {} (0x{:08x})",
            bluetooth::common::ToString(all_track_contexts), all_track_contexts.value());
  return all_track_contexts;
}

bluetooth::le_audio::btle_audio_codec_index_t translateBluetoothCodecFormatToCodecType(
        uint8_t codec_format) {
  switch (codec_format) {
    case types::kLeAudioCodingFormatLC3:
      return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3;
  }
  return bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_INVALID;
}

bluetooth::le_audio::btle_audio_sample_rate_index_t translateToBtLeAudioCodecConfigSampleRate(
        uint32_t sample_rate) {
  log::info("{}", sample_rate);
  switch (sample_rate) {
    case LeAudioCodecConfiguration::kSampleRate8000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_8000HZ;
    case LeAudioCodecConfiguration::kSampleRate16000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_16000HZ;
    case LeAudioCodecConfiguration::kSampleRate24000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_24000HZ;
    case LeAudioCodecConfiguration::kSampleRate32000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_32000HZ;
    case LeAudioCodecConfiguration::kSampleRate44100:
      return LE_AUDIO_SAMPLE_RATE_INDEX_44100HZ;
    case LeAudioCodecConfiguration::kSampleRate48000:
      return LE_AUDIO_SAMPLE_RATE_INDEX_48000HZ;
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

  out_config.codec_type = translateBluetoothCodecFormatToCodecType(config.id.coding_format);
  if (out_config.codec_type != bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3) {
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

static bool is_known_codec(const types::LeAudioCodecId& codec_id) {
  switch (codec_id.coding_format) {
    case types::kLeAudioCodingFormatLC3:
      return true;
  }
  return false;
}

static void fillRemotePacsCapabilitiesToBtLeAudioCodecConfig(
        const struct types::acs_ac_record& record,
        std::vector<bluetooth::le_audio::btle_audio_codec_config_t>& vec) {
  if (!utils::IsCodecUsingLtvFormat(record.codec_id)) {
    log::warn(
            "Unknown codec capability format. Unable to report known codec "
            "parameters.");
    return;
  }
  log::assert_that(!record.codec_spec_caps.IsEmpty(),
                   "Codec specific capabilities are not parsed appropriately.");

  const struct types::LeAudioCoreCodecCapabilities capa =
          record.codec_spec_caps.GetAsCoreCodecCapabilities();
  for (uint8_t freq_bit = codec_spec_conf::kLeAudioSamplingFreq8000Hz;
       freq_bit <= codec_spec_conf::kLeAudioSamplingFreq384000Hz; freq_bit++) {
    if (!capa.IsSamplingFrequencyConfigSupported(freq_bit)) {
      continue;
    }
    for (uint8_t fd_bit = codec_spec_conf::kLeAudioCodecFrameDur7500us;
         fd_bit <= codec_spec_conf::kLeAudioCodecFrameDur10000us; fd_bit++) {
      if (!capa.IsFrameDurationConfigSupported(fd_bit)) {
        continue;
      }
      if (!capa.HasSupportedAudioChannelCounts()) {
        bluetooth::le_audio::btle_audio_codec_config_t config = {
                .codec_type = utils::translateBluetoothCodecFormatToCodecType(
                        record.codec_id.coding_format),
                .sample_rate = utils::translateToBtLeAudioCodecConfigSampleRate(
                        types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                .bits_per_sample = utils::translateToBtLeAudioCodecConfigBitPerSample(16),
                .channel_count = utils::translateToBtLeAudioCodecConfigChannelCount(1),
                .frame_duration = utils::translateToBtLeAudioCodecConfigFrameDuration(
                        types::LeAudioCoreCodecConfig::GetFrameDurationUs(fd_bit)),
        };
        vec.push_back(config);
      } else {
        for (int chan_bit = 1; chan_bit <= 2; chan_bit++) {
          if (!capa.IsAudioChannelCountsSupported(chan_bit)) {
            continue;
          }

          bluetooth::le_audio::btle_audio_codec_config_t config = {
                  .codec_type = utils::translateBluetoothCodecFormatToCodecType(
                          record.codec_id.coding_format),
                  .sample_rate = utils::translateToBtLeAudioCodecConfigSampleRate(
                          types::LeAudioCoreCodecConfig::GetSamplingFrequencyHz(freq_bit)),
                  .bits_per_sample = utils::translateToBtLeAudioCodecConfigBitPerSample(16),
                  .channel_count = utils::translateToBtLeAudioCodecConfigChannelCount(chan_bit),
                  .frame_duration = utils::translateToBtLeAudioCodecConfigFrameDuration(
                          types::LeAudioCoreCodecConfig::GetFrameDurationUs(fd_bit)),
          };
          vec.push_back(config);
        }
      }
    }
  }
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t> GetRemoteBtLeAudioCodecConfigFromPac(
        const types::PublishedAudioCapabilities& group_pacs) {
  std::vector<bluetooth::le_audio::btle_audio_codec_config_t> vec;

  for (auto& [handles, pacs_record] : group_pacs) {
    for (auto& pac : pacs_record) {
      if (!is_known_codec(pac.codec_id)) {
        continue;
      }

      fillRemotePacsCapabilitiesToBtLeAudioCodecConfig(pac, vec);
    }
  }
  return vec;
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

  // We need at least 2 ASEs in the group config to set up more than one device
  if (cfgs.size() == 1) {
    return types::LeAudioConfigurationStrategy::RFU;
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
