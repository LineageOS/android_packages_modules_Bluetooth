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

#include <bluetooth/types/string_helpers.h>
#include <hardware/bt_le_audio.h>

#include <bitset>
#include <string>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "bta_groups.h"
#include "common/time_util.h"
#include "le_audio_types.h"

namespace bluetooth::le_audio {
namespace utils {

inline uint8_t GetPreferredPhyFromTargetPhy(uint8_t target_phy) {
  switch (target_phy) {
    case types::kTargetPhy1M:
      return bluetooth::hci::kIsoCigPhy1M;
    case types::kTargetPhy2M:
      return bluetooth::hci::kIsoCigPhy2M;
    case types::kTargetPhyCoded:
      return bluetooth::hci::kIsoCigPhyC;
    case types::kTargetPhyUndefined:
      [[fallthrough]];  // bluetooth::hci::kIsoCigPhy2M
    default:
      return bluetooth::hci::kIsoCigPhy2M;
  }
}

inline uint8_t GetTargetPhyFromPreferredPhy(uint8_t preferred_phy) {
  if (preferred_phy & bluetooth::hci::kIsoCigPhy2M) {
    return types::kTargetPhy2M;
  } else if (preferred_phy & bluetooth::hci::kIsoCigPhy1M) {
    return types::kTargetPhy1M;
  } else if (preferred_phy & bluetooth::hci::kIsoCigPhyC) {
    return types::kTargetPhyCoded;
  } else {
    return types::kTargetPhyUndefined;
  }
}

types::LeAudioContextType AudioContentToLeAudioContext(audio_content_type_t content_type,
                                                       audio_usage_t usage);
types::AudioContexts GetAudioContextsFromSourceMetadata(
        const std::vector<struct playback_track_metadata_v7>& source_metadata);
size_t GetConfigurationHash(const bluetooth::le_audio::types::AudioSetConfiguration& conf);

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

class AudioDeviceActiveSpeedTracker {
public:
  AudioDeviceActiveSpeedTracker(void)
      : group_id_(bluetooth::groups::kGroupUnknown),
        start_time_(0),
        total_time_(0),
        end_ts_(0),
        suspend_calls_({0, 0}),
        resume_calls_({0, 0}) {}

  void Start(int group_id) {
    if (group_id_ != bluetooth::groups::kGroupUnknown) {
      return;
    }
    group_id_ = group_id;
    start_time_ = bluetooth::common::time_get_os_boottime_us();
    clock_gettime(CLOCK_REALTIME, &start_ts_);
    total_time_ = 0;
  }

  void Stop(void) {
    if (group_id_ == bluetooth::groups::kGroupUnknown) {
      return;
    }
    total_time_ = (bluetooth::common::time_get_os_boottime_us() - start_time_) / 1000;
    clock_gettime(CLOCK_REALTIME, &end_ts_);
    log::verbose("AudioDeviceActiveSpeedTracker group_id: {}, total time {} ms", group_id_,
                 total_time_);
  }

  void Dump(std::stringstream& stream) {
    char start_ts[20];
    std::strftime(start_ts, sizeof(start_ts), "%T", std::gmtime(&start_ts_.tv_sec));
    char end_ts[20];
    std::strftime(end_ts, sizeof(end_ts), "%T", std::gmtime(&end_ts_.tv_sec));
    auto lsink_s = suspend_calls_.sink;
    auto lsink_r = resume_calls_.sink;
    auto lsource_s = suspend_calls_.source;
    auto lsource_r = resume_calls_.source;

    char lsink_r_ts[20] = {0};
    if (lsink_r > 0) {
      std::strftime(lsink_r_ts, sizeof(lsink_r_ts), "%T",
                    std::gmtime(&resume_calls_ts_.sink.tv_sec));
    }

    char lsource_r_ts[20] = {0};
    if (lsource_r > 0) {
      std::strftime(lsource_r_ts, sizeof(lsource_r_ts), "%T",
                    std::gmtime(&resume_calls_ts_.source.tv_sec));
    }

    stream << "[ " << start_ts << "->" << end_ts << ", gID:" << group_id_ << ", t:" << total_time_
           << "ms, " << "LSink(s/r) " << lsink_s << "/" << lsink_r << ": " << lsink_r_ts
           << ", LSource(s/r)" << lsource_s << "/" << lsource_r << ": " << lsource_r_ts << "]";
  }

  void Reset(void) {
    group_id_ = bluetooth::groups::kGroupUnknown;
    start_time_ = 0;
    suspend_calls_ = {0, 0};
    resume_calls_ = {0, 0};
  }

  void LogAHALSuspendOperation(int group_id, uint8_t direction) {
    if (group_id_ != group_id) {
      return;
    }
    suspend_calls_.get(direction)++;
  }

  void LogAHALResumeOperation(int group_id, uint8_t direction) {
    if (group_id_ != group_id) {
      return;
    }
    resume_calls_.get(direction)++;
    clock_gettime(CLOCK_REALTIME, &resume_calls_ts_.get(direction));
  }

private:
  int group_id_;
  uint64_t start_time_;
  uint64_t total_time_;
  timespec start_ts_;
  timespec end_ts_;
  types::BidirectionalPair<int> suspend_calls_;
  types::BidirectionalPair<int> resume_calls_;
  types::BidirectionalPair<timespec> resume_calls_ts_;
};

class StreamSpeedTracker {
public:
  StreamSpeedTracker(void)
      : is_started_(false),
        group_id_(bluetooth::groups::kGroupUnknown),
        num_of_devices_(0),
        context_type_(types::LeAudioContextType::UNSPECIFIED),
        reconfig_start_ts_(0),
        setup_start_ts_(0),
        total_time_(0),
        reconfig_time_(0),
        stream_setup_time_(0) {}

  void Init(int group_id, types::LeAudioContextType context_type, int num_of_devices) {
    Reset(bluetooth::groups::kGroupUnknown);
    group_id_ = group_id;
    context_type_ = context_type;
    num_of_devices_ = num_of_devices;
    log::verbose("StreamSpeedTracker group_id: {}, context: {} #{}", group_id_,
                 common::ToString(context_type_), num_of_devices);
  }

  void Reset(int group_id) {
    if (group_id != bluetooth::groups::kGroupUnknown && group_id != group_id_) {
      log::verbose("StreamSpeedTracker Reset called for invalid group_id: {} != {}", group_id,
                   group_id_);
      return;
    }

    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    is_started_ = false;
    group_id_ = bluetooth::groups::kGroupUnknown;
    reconfig_start_ts_ = setup_start_ts_ = total_time_ = reconfig_time_ = stream_setup_time_ =
            num_of_devices_ = 0;
    context_type_ = types::LeAudioContextType::UNSPECIFIED;
  }

  void ReconfigStarted(void) {
    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    reconfig_time_ = 0;
    is_started_ = true;
    reconfig_start_ts_ = bluetooth::common::time_get_os_boottime_us();
  }

  void StartStream(void) {
    log::verbose("StreamSpeedTracker group_id: {}", group_id_);
    setup_start_ts_ = bluetooth::common::time_get_os_boottime_us();
    clock_gettime(CLOCK_REALTIME, &start_ts_);
    is_started_ = true;
  }

  void ReconfigurationComplete(void) {
    reconfig_time_ = (bluetooth::common::time_get_os_boottime_us() - reconfig_start_ts_) / 1000;
    log::verbose("StreamSpeedTracker group_id: {}, {} reconfig time {} ms", group_id_,
                 common::ToString(context_type_), reconfig_time_);
  }

  void StreamCreated(void) {
    stream_setup_time_ = (bluetooth::common::time_get_os_boottime_us() - setup_start_ts_) / 1000;
    log::verbose("StreamSpeedTracker group_id: {}, {} stream create  time {} ms", group_id_,
                 common::ToString(context_type_), stream_setup_time_);
  }

  void StopStreamSetup(void) {
    is_started_ = false;
    uint64_t start_ts = reconfig_time_ != 0 ? reconfig_start_ts_ : setup_start_ts_;
    total_time_ = (bluetooth::common::time_get_os_boottime_us() - start_ts) / 1000;
    clock_gettime(CLOCK_REALTIME, &end_ts_);
    log::verbose("StreamSpeedTracker group_id: {}, {} setup time {} ms", group_id_,
                 common::ToString(context_type_), total_time_);
  }

  bool IsStarted(int group_id) {
    if (is_started_ && group_id_ == group_id) {
      log::verbose("StreamSpeedTracker group_id: {}, {} is_started_: {} ", group_id_,
                   common::ToString(context_type_), is_started_);
      return true;
    }
    log::verbose("StreamSpeedTracker not started {} or group_id does not match ({} ! = {}) ",
                 is_started_, group_id, group_id_);
    return false;
  }

  void Dump(std::stringstream& stream) {
    char start_ts[20];
    std::strftime(start_ts, sizeof(start_ts), "%T", std::gmtime(&start_ts_.tv_sec));
    char end_ts[20];
    std::strftime(end_ts, sizeof(end_ts), "%T", std::gmtime(&end_ts_.tv_sec));

    if (total_time_ < 900) {
      stream << "[ 🌕 ";
    } else if (total_time_ < 1500) {
      stream << "[ 🌔 ";
    } else if (total_time_ < 2500) {
      stream << "[ 🌓 ";
    } else {
      stream << "[ 🌒 ";
    }

    stream << start_ts << "->" << end_ts << ", gID:" << group_id_ << ", #dev:" << num_of_devices_
           << ", " << context_type_;
    auto hal_idle = total_time_ - stream_setup_time_ - reconfig_time_;
    if (reconfig_time_ != 0) {
      stream << ", t:" << total_time_ << "ms (r:" << reconfig_time_ << "/s:" << stream_setup_time_
             << "/hal:" << hal_idle << ")";
    } else {
      stream << ", t:" << total_time_ << "ms (hal:" << hal_idle << ")";
    }
    stream << "]";
  }

private:
  bool is_started_;
  int group_id_;
  int num_of_devices_;
  types::LeAudioContextType context_type_;
  struct timespec start_ts_;
  struct timespec end_ts_;
  uint64_t reconfig_start_ts_;
  uint64_t setup_start_ts_;
  uint64_t total_time_;
  uint64_t reconfig_time_;
  uint64_t stream_setup_time_;
};

/* Helpers to get btle_audio_codec_config_t for Java */
bluetooth::le_audio::btle_audio_codec_index_t translateLeAudioCodecIdToCodecType(
        const types::LeAudioCodecId& codecId,
        std::optional<uint32_t> sampling_frequency_hz = std::nullopt);
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
