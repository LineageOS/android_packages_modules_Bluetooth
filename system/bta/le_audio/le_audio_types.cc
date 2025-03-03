/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

/*
 * This file contains definitions for Basic Audio Profile / Audio Stream Control
 * and Published Audio Capabilities definitions, structures etc.
 */

#include "le_audio_types.h"

#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iterator>
#include <map>
#include <memory>
#include <optional>
#include <ostream>
#include <sstream>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

#include "audio_hal_client/audio_hal_client.h"
#include "common/strings.h"
#include "hardware/bt_le_audio.h"
#include "internal_include/bt_trace.h"
#include "le_audio_utils.h"
#include "stack/include/bt_types.h"

namespace bluetooth::le_audio {
using types::acs_ac_record;
using types::LeAudioContextType;

namespace types {
using types::CodecConfigSetting;
using types::kLeAudioCodingFormatLC3;
using types::LeAudioCoreCodecConfig;

uint16_t CodecConfigSetting::GetOctetsPerFrame() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      return params.GetAsCoreCodecConfig().GetOctetsPerFrame();
    default:
      log::warn(", invalid codec id: 0x{:02x}", id.coding_format);
      return 0;
  }
}

uint32_t CodecConfigSetting::GetSamplingFrequencyHz() const {
  // We also mandate the sampling frequency parameter for vendor spec. codecs
  return params.GetAsCoreCodecConfig().GetSamplingFrequencyHz();
}

uint32_t CodecConfigSetting::GetDataIntervalUs() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      return params.GetAsCoreCodecConfig().GetFrameDurationUs() *
             params.GetAsCoreCodecConfig().codec_frames_blocks_per_sdu.value_or(1);
    default:
      log::warn(", invalid codec id: 0x{:02x}", id.coding_format);
      return 0;
  }
}

uint8_t CodecConfigSetting::GetBitsPerSample() const {
  switch (id.coding_format) {
    case kLeAudioCodingFormatLC3:
      /* XXX LC3 supports 16, 24, 32 */
      return 16;
    default:
      log::warn(", invalid codec id: 0x{:02x}", id.coding_format);
      return 0;
  }
}

std::ostream& operator<<(std::ostream& os, const QosConfigSetting& config) {
  os << "QosConfigSetting{";
  os << "targetLatency: " << (int)config.target_latency;
  os << ", retransmissionNum: " << (int)config.retransmission_number;
  os << ", maxTransportLatency: " << (int)config.max_transport_latency;
  os << ", sduIntervalUs: " << (int)config.sduIntervalUs;
  os << ", maxSdu: " << (int)config.maxSdu;
  os << "}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const AseConfiguration& config) {
  os << "AseConfiguration{";
  os << "dataPath: " << config.data_path_configuration;
  os << ", codec: " << config.codec;
  os << ", qos: " << config.qos;
  os << "}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const AudioSetConfiguration& config) {
  os << "AudioSetConfiguration{";
  os << "name: " << config.name;
  os << ", packing: " << (int)config.packing;
  os << ", sinkConfs: [";
  for (auto const& conf : config.confs.sink) {
    os << conf;
    os << ", ";
  }
  os << "], sourceConfs: [";
  for (auto const& conf : config.confs.source) {
    os << conf;
    os << ", ";
  }
  os << "]}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const CodecConfigSetting& config) {
  os << "CodecConfigSetting{";
  os << ", id: " << config.id;
  os << ", codecSpecParams: " << config.params.GetAsCoreCodecConfig();
  os << ", bitsPerSample: " << (int)config.GetBitsPerSample();
  os << ", channelCountPerIsoStream: " << (int)config.GetChannelCountPerIsoStream();
  if (!config.vendor_params.empty()) {
    os << ", vendorParams: "
       << base::HexEncode(config.vendor_params.data(), config.vendor_params.size());
  }
  os << "}";
  return os;
}

/* Helper map for matching various frequency notations */
const std::map<uint8_t, uint32_t> LeAudioCoreCodecConfig::sampling_freq_map = {
        {codec_spec_conf::kLeAudioSamplingFreq8000Hz, LeAudioCodecConfiguration::kSampleRate8000},
        {codec_spec_conf::kLeAudioSamplingFreq16000Hz, LeAudioCodecConfiguration::kSampleRate16000},
        {codec_spec_conf::kLeAudioSamplingFreq24000Hz, LeAudioCodecConfiguration::kSampleRate24000},
        {codec_spec_conf::kLeAudioSamplingFreq32000Hz, LeAudioCodecConfiguration::kSampleRate32000},
        {codec_spec_conf::kLeAudioSamplingFreq44100Hz, LeAudioCodecConfiguration::kSampleRate44100},
        {codec_spec_conf::kLeAudioSamplingFreq48000Hz,
         LeAudioCodecConfiguration::kSampleRate48000}};

/* Helper map for matching various frequency notations */
const std::map<uint32_t, uint8_t> LeAudioCoreCodecConfig::sample_rate_map = {
        {LeAudioCodecConfiguration::kSampleRate8000, codec_spec_conf::kLeAudioSamplingFreq8000Hz},
        {LeAudioCodecConfiguration::kSampleRate16000, codec_spec_conf::kLeAudioSamplingFreq16000Hz},
        {LeAudioCodecConfiguration::kSampleRate24000, codec_spec_conf::kLeAudioSamplingFreq24000Hz},
        {LeAudioCodecConfiguration::kSampleRate32000, codec_spec_conf::kLeAudioSamplingFreq32000Hz},
        {LeAudioCodecConfiguration::kSampleRate44100, codec_spec_conf::kLeAudioSamplingFreq44100Hz},
        {LeAudioCodecConfiguration::kSampleRate48000, codec_spec_conf::kLeAudioSamplingFreq48000Hz},
};

/* Helper map for matching various frame durations notations */
const std::map<uint8_t, uint32_t> LeAudioCoreCodecConfig::frame_duration_map = {
        {codec_spec_conf::kLeAudioCodecFrameDur7500us, LeAudioCodecConfiguration::kInterval7500Us},
        {codec_spec_conf::kLeAudioCodecFrameDur10000us,
         LeAudioCodecConfiguration::kInterval10000Us}};

/* Helper map for matching various frame durations notations */
const std::map<uint32_t, uint8_t> LeAudioCoreCodecConfig::data_interval_map = {
        {LeAudioCodecConfiguration::kInterval7500Us, codec_spec_conf::kLeAudioCodecFrameDur7500us},
        {LeAudioCodecConfiguration::kInterval10000Us,
         codec_spec_conf::kLeAudioCodecFrameDur10000us},
};

static std::string CapabilityTypeToStr(const uint8_t& type) {
  switch (type) {
    case codec_spec_caps::kLeAudioLtvTypeSupportedSamplingFrequencies:
      return "Supported Sampling Frequencies";
    case codec_spec_caps::kLeAudioLtvTypeSupportedFrameDurations:
      return "Supported Frame Durations";
    case codec_spec_caps::kLeAudioLtvTypeSupportedAudioChannelCounts:
      return "Supported Audio Channel Count";
    case codec_spec_caps::kLeAudioLtvTypeSupportedOctetsPerCodecFrame:
      return "Supported Octets Per Codec Frame";
    case codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu:
      return "Supported Max Codec Frames Per SDU";
    default:
      return "Unknown";
  }
}

static std::string CapabilityValueToStr(const uint8_t& type, const std::vector<uint8_t>& value) {
  std::string string = "";

  switch (type) {
    case codec_spec_conf::kLeAudioLtvTypeSamplingFreq: {
      if (value.size() != 2) {
        return "Invalid size";
      }

      uint16_t u16_val = VEC_UINT8_TO_UINT16(value);

      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq8000Hz) {
        string += "8";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq11025Hz) {
        string += std::string(string.empty() ? "" : "|") + "11.025";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq16000Hz) {
        string += std::string(string.empty() ? "" : "|") + "16";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq22050Hz) {
        string += std::string(string.empty() ? "" : "|") + "22.050";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq24000Hz) {
        string += std::string(string.empty() ? "" : "|") + "24";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq32000Hz) {
        string += std::string(string.empty() ? "" : "|") + "32";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq44100Hz) {
        string += std::string(string.empty() ? "" : "|") + "44.1";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq48000Hz) {
        string += std::string(string.empty() ? "" : "|") + "48";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq88200Hz) {
        string += std::string(string.empty() ? "" : "|") + "88.2";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq96000Hz) {
        string += std::string(string.empty() ? "" : "|") + "96";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq176400Hz) {
        string += std::string(string.empty() ? "" : "|") + "176.4";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq192000Hz) {
        string += std::string(string.empty() ? "" : "|") + "192";
      }
      if (u16_val & codec_spec_caps::kLeAudioSamplingFreq384000Hz) {
        string += std::string(string.empty() ? "" : "|") + "384";
      }

      return string += " [kHz]\n";
    }
    case codec_spec_conf::kLeAudioLtvTypeFrameDuration: {
      if (value.size() != 1) {
        return "Invalid size";
      }

      uint8_t u8_val = VEC_UINT8_TO_UINT8(value);

      if (u8_val & codec_spec_caps::kLeAudioCodecFrameDur7500us) {
        string += "7.5";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecFrameDur10000us) {
        string += std::string(string.empty() ? "" : "|") + "10";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecFrameDurPrefer7500us) {
        string += std::string(string.empty() ? "" : "|") + "7.5 preferred";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecFrameDurPrefer10000us) {
        string += std::string(string.empty() ? "" : "|") + "10 preferred";
      }

      return string += " [ms]\n";
    }
    case codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation: {
      if (value.size() != 1) {
        return "Invalid size";
      }

      uint8_t u8_val = VEC_UINT8_TO_UINT8(value);

      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountNone) {
        string += "0";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountSingleChannel) {
        string += std::string(string.empty() ? "" : "|") + "1";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountTwoChannel) {
        string += std::string(string.empty() ? "" : "|") + "2";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountThreeChannel) {
        string += std::string(string.empty() ? "" : "|") + "3";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountFourChannel) {
        string += std::string(string.empty() ? "" : "|") + "4";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountFiveChannel) {
        string += std::string(string.empty() ? "" : "|") + "5";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountSixChannel) {
        string += std::string(string.empty() ? "" : "|") + "6";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountSevenChannel) {
        string += std::string(string.empty() ? "" : "|") + "7";
      }
      if (u8_val & codec_spec_caps::kLeAudioCodecChannelCountEightChannel) {
        string += std::string(string.empty() ? "" : "|") + "8";
      }

      return string += " channel/s\n";
    }
    case codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame: {
      if (value.size() != 4) {
        return "Invalid size";
      }

      uint16_t u16_min_number_of_octets = VEC_UINT8_TO_UINT16(value);
      uint16_t u16_max_number_of_octets =
              OFF_VEC_UINT8_TO_UINT16(value, sizeof(u16_min_number_of_octets));

      string += "Minimum: " + std::to_string(u16_min_number_of_octets);
      string += ", Maximum: " + std::to_string(u16_max_number_of_octets) + "\n";

      return string;
    }
    case codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu: {
      if (value.size() != 1) {
        return "Invalid size";
      }

      uint8_t u8_val = VEC_UINT8_TO_UINT8(value);

      string += std::to_string(u8_val) + " frame/s\n";

      return string;
    }
    default:
      return base::HexEncode(value.data(), value.size()) + "\n";
  }
}

std::string CodecCapabilitiesLtvFormat(const uint8_t& type, const std::vector<uint8_t>& value) {
  std::string string = "";

  string += CapabilityTypeToStr(type) + ": ";
  string += CapabilityValueToStr(type, value);

  return string;
}

std::optional<std::vector<uint8_t>> LeAudioLtvMap::Find(uint8_t type) const {
  auto iter = std::find_if(values.cbegin(), values.cend(),
                           [type](const auto& value) { return value.first == type; });

  if (iter == values.cend()) {
    return std::nullopt;
  }

  return iter->second;
}

uint8_t* LeAudioLtvMap::RawPacket(uint8_t* p_buf) const {
  for (auto const& value : values) {
    UINT8_TO_STREAM(p_buf, value.second.size() + 1);
    UINT8_TO_STREAM(p_buf, value.first);
    ARRAY_TO_STREAM(p_buf, value.second.data(), static_cast<int>(value.second.size()));
  }

  return p_buf;
}

std::vector<uint8_t> LeAudioLtvMap::RawPacket() const {
  std::vector<uint8_t> data(RawPacketSize());
  RawPacket(data.data());
  return data;
}

void LeAudioLtvMap::Append(const LeAudioLtvMap& other) {
  /* This will override values for the already existing keys */
  for (auto& el : other.values) {
    values[el.first] = el.second;
  }

  invalidate();
}

LeAudioLtvMap LeAudioLtvMap::Parse(const uint8_t* p_value, uint8_t len, bool& success) {
  LeAudioLtvMap ltv_map;
  success = ltv_map.Parse(p_value, len);
  if (!success) {
    log::error("Error parsing LTV map");
  }
  return ltv_map;
}

bool LeAudioLtvMap::Parse(const uint8_t* p_value, uint8_t len) {
  if (len > 0) {
    const auto p_value_end = p_value + len;

    while ((p_value_end - p_value) > 0) {
      uint8_t ltv_len;
      STREAM_TO_UINT8(ltv_len, p_value);

      // Unusual, but possible case
      if (ltv_len == 0) {
        continue;
      }

      if (p_value_end < (p_value + ltv_len)) {
        log::error("Invalid ltv_len: {}", static_cast<int>(ltv_len));
        invalidate();
        return false;
      }

      uint8_t ltv_type;
      STREAM_TO_UINT8(ltv_type, p_value);
      ltv_len -= sizeof(ltv_type);

      const auto p_temp = p_value;
      p_value += ltv_len;

      std::vector<uint8_t> ltv_value(p_temp, p_value);
      values.emplace(ltv_type, std::move(ltv_value));
    }
  }
  invalidate();

  return true;
}

size_t LeAudioLtvMap::RawPacketSize() const {
  size_t bytes = 0;

  for (auto const& value : values) {
    bytes += (/* ltv_len + ltv_type */ 2 + value.second.size());
  }

  return bytes;
}

std::string LeAudioLtvMap::ToString(const std::string& indent_string,
                                    std::string (*format)(const uint8_t&,
                                                          const std::vector<uint8_t>&)) const {
  std::string debug_str;

  for (const auto& value : values) {
    std::stringstream sstream;

    if (format == nullptr) {
      sstream << indent_string + "type: " << std::to_string(value.first)
              << "\tlen: " << std::to_string(value.second.size())
              << "\tdata: " << base::HexEncode(value.second.data(), value.second.size()) + "\n";
    } else {
      sstream << indent_string + format(value.first, value.second);
    }

    debug_str += sstream.str();
  }

  return debug_str;
}

const struct LeAudioCoreCodecConfig& LeAudioLtvMap::GetAsCoreCodecConfig() const {
  log::assert_that(!core_capabilities, "LTVs were already parsed for capabilities!");
  log::assert_that(!metadata, "LTVs were already parsed for metadata!");

  if (!core_config) {
    core_config = LtvMapToCoreCodecConfig(*this);
  }
  return *core_config;
}

const struct LeAudioCoreCodecCapabilities& LeAudioLtvMap::GetAsCoreCodecCapabilities() const {
  log::assert_that(!core_config, "LTVs were already parsed for configurations!");
  log::assert_that(!metadata, "LTVs were already parsed for metadata!");

  if (!core_capabilities) {
    core_capabilities = LtvMapToCoreCodecCapabilities(*this);
  }
  return *core_capabilities;
}

const struct LeAudioMetadata& LeAudioLtvMap::GetAsLeAudioMetadata() const {
  log::assert_that(!core_config, "LTVs were already parsed for configurations!");
  log::assert_that(!core_capabilities, "LTVs were already parsed for capabilities!");

  if (!metadata) {
    metadata = LtvMapToMetadata(*this);
  }
  return *metadata;
}

void LeAudioLtvMap::RemoveAllTypes(const LeAudioLtvMap& other) {
  for (auto const& [key, _] : other.values) {
    Remove(key);
  }
}

LeAudioLtvMap LeAudioLtvMap::GetIntersection(const LeAudioLtvMap& other) const {
  LeAudioLtvMap result;
  for (auto const& [key, value] : values) {
    auto entry = other.Find(key);
    if (entry->size() != value.size()) {
      continue;
    }
    if (memcmp(entry->data(), value.data(), value.size()) == 0) {
      result.Add(key, value);
    }
  }
  return result;
}

}  // namespace types

void AppendMetadataLtvEntryForCcidList(std::vector<uint8_t>& metadata,
                                       const std::vector<uint8_t>& ccid_list) {
  if (ccid_list.size() == 0) {
    log::warn("Empty CCID list.");
    return;
  }

  metadata.push_back(static_cast<uint8_t>(types::kLeAudioMetadataTypeLen + ccid_list.size()));
  metadata.push_back(static_cast<uint8_t>(types::kLeAudioMetadataTypeCcidList));

  metadata.insert(metadata.end(), ccid_list.begin(), ccid_list.end());
}

void AppendMetadataLtvEntryForStreamingContext(std::vector<uint8_t>& metadata,
                                               types::AudioContexts context_type) {
  std::vector<uint8_t> streaming_context_ltv_entry;

  streaming_context_ltv_entry.resize(types::kLeAudioMetadataTypeLen +
                                     types::kLeAudioMetadataLenLen +
                                     types::kLeAudioMetadataStreamingAudioContextLen);
  uint8_t* streaming_context_ltv_entry_buf = streaming_context_ltv_entry.data();

  UINT8_TO_STREAM(streaming_context_ltv_entry_buf,
                  types::kLeAudioMetadataTypeLen + types::kLeAudioMetadataStreamingAudioContextLen);
  UINT8_TO_STREAM(streaming_context_ltv_entry_buf,
                  types::kLeAudioMetadataTypeStreamingAudioContext);
  UINT16_TO_STREAM(streaming_context_ltv_entry_buf, context_type.value());

  metadata.insert(metadata.end(), streaming_context_ltv_entry.begin(),
                  streaming_context_ltv_entry.end());
}

uint8_t GetMaxCodecFramesPerSduFromPac(const acs_ac_record* pac) {
  if (utils::IsCodecUsingLtvFormat(pac->codec_id)) {
    auto tlv_ent = pac->codec_spec_caps.Find(
            codec_spec_caps::kLeAudioLtvTypeSupportedMaxCodecFramesPerSdu);

    if (tlv_ent) {
      return VEC_UINT8_TO_UINT8(tlv_ent.value());
    }
  }

  return 1;
}

namespace types {
std::ostream& operator<<(std::ostream& os, const CisState& state) {
  static const char* char_value_[5] = {"IDLE", "ASSIGNED", "CONNECTING", "CONNECTED",
                                       "DISCONNECTING"};

  os << char_value_[static_cast<uint8_t>(state)] << " (" << "0x" << std::setfill('0')
     << std::setw(2) << static_cast<int>(state) << ")";
  return os;
}
std::ostream& operator<<(std::ostream& os, const DataPathState& state) {
  static const char* char_value_[4] = {"IDLE", "CONFIGURING", "CONFIGURED", "REMOVING"};

  os << char_value_[static_cast<uint8_t>(state)] << " (" << "0x" << std::setfill('0')
     << std::setw(2) << static_cast<int>(state) << ")";
  return os;
}
std::ostream& operator<<(std::ostream& os, const types::CigState& state) {
  static const char* char_value_[5] = {"NONE", "CREATING", "CREATED", "REMOVING", "RECOVERING"};

  os << char_value_[static_cast<uint8_t>(state)] << " (" << "0x" << std::setfill('0')
     << std::setw(2) << static_cast<int>(state) << ")";
  return os;
}
std::ostream& operator<<(std::ostream& os, const types::AseState& state) {
  static const char* char_value_[7] = {
          "IDLE",      "CODEC_CONFIGURED", "QOS_CONFIGURED", "ENABLING",
          "STREAMING", "DISABLING",        "RELEASING",
  };

  os << char_value_[static_cast<uint8_t>(state)] << " (" << "0x" << std::setfill('0')
     << std::setw(2) << static_cast<int>(state) << ")";
  return os;
}

std::ostream& operator<<(std::ostream& os, const LeAudioCodecId& codec_id) {
  os << "LeAudioCodecId{CodingFormat: " << loghex(codec_id.coding_format)
     << ", CompanyId: " << loghex(codec_id.vendor_company_id)
     << ", CodecId: " << loghex(codec_id.vendor_codec_id) << "}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const types::LeAudioCoreCodecConfig& config) {
  os << "LeAudioCoreCodecConfig{SamplFreq: " << loghex(*config.sampling_frequency)
     << ", FrameDur: " << loghex(*config.frame_duration)
     << ", OctetsPerFrame: " << int(*config.octets_per_codec_frame)
     << ", CodecFramesBlocksPerSDU: " << int(*config.codec_frames_blocks_per_sdu)
     << ", AudioChanLoc: " << loghex(*config.audio_channel_allocation) << "}";
  return os;
}

std::string contextTypeToStr(const LeAudioContextType& context) {
  switch (context) {
    case LeAudioContextType::UNINITIALIZED:
      return "UNINITIALIZED";
    case LeAudioContextType::UNSPECIFIED:
      return "UNSPECIFIED";
    case LeAudioContextType::CONVERSATIONAL:
      return "CONVERSATIONAL";
    case LeAudioContextType::MEDIA:
      return "MEDIA";
    case LeAudioContextType::GAME:
      return "GAME";
    case LeAudioContextType::INSTRUCTIONAL:
      return "INSTRUCTIONAL";
    case LeAudioContextType::VOICEASSISTANTS:
      return "VOICEASSISTANTS";
    case LeAudioContextType::LIVE:
      return "LIVE";
    case LeAudioContextType::SOUNDEFFECTS:
      return "SOUNDEFFECTS";
    case LeAudioContextType::NOTIFICATIONS:
      return "NOTIFICATIONS";
    case LeAudioContextType::RINGTONE:
      return "RINGTONE";
    case LeAudioContextType::ALERTS:
      return "ALERTS";
    case LeAudioContextType::EMERGENCYALARM:
      return "EMERGENCYALARM";
    default:
      return "UNKNOWN";
  }
}

std::ostream& operator<<(std::ostream& os, const LeAudioContextType& context) {
  os << contextTypeToStr(context);
  return os;
}

AudioContexts operator|(std::underlying_type<LeAudioContextType>::type lhs,
                        const LeAudioContextType rhs) {
  using T = std::underlying_type<LeAudioContextType>::type;
  return AudioContexts(lhs | static_cast<T>(rhs));
}

AudioContexts& operator|=(AudioContexts& lhs, AudioContexts const& rhs) {
  lhs = AudioContexts(lhs.value() | rhs.value());
  return lhs;
}

AudioContexts& operator&=(AudioContexts& lhs, AudioContexts const& rhs) {
  lhs = AudioContexts(lhs.value() & rhs.value());
  return lhs;
}

std::string ToHexString(const LeAudioContextType& value) {
  using T = std::underlying_type<LeAudioContextType>::type;
  return bluetooth::common::ToHexString(static_cast<T>(value));
}

std::string AudioContexts::to_string() const {
  std::stringstream s;
  s << bluetooth::common::ToHexString(mValue);
  if (mValue != 0) {
    s << " [";
    auto initial_pos = s.tellp();
    for (auto ctx : bluetooth::le_audio::types::kLeAudioContextAllTypesArray) {
      if (test(ctx)) {
        if (s.tellp() != initial_pos) {
          s << " | ";
        }
        s << ctx;
      }
    }
    s << "]";
  }
  return s.str();
}

std::ostream& operator<<(std::ostream& os, const AudioContexts& contexts) {
  os << contexts.to_string();
  return os;
}

/* Bidirectional getter trait for AudioContexts bidirectional pair */
template <>
AudioContexts get_bidirectional(BidirectionalPair<AudioContexts> p) {
  return p.sink | p.source;
}

template <>
std::vector<uint8_t> get_bidirectional(BidirectionalPair<std::vector<uint8_t>> bidir) {
  std::vector<uint8_t> res = bidir.sink;
  res.insert(std::end(res), std::begin(bidir.source), std::end(bidir.source));
  return res;
}

template <>
AudioLocations get_bidirectional(BidirectionalPair<AudioLocations> bidir) {
  return bidir.sink | bidir.source;
}

std::ostream& operator<<(std::ostream& os,
                         const le_audio::types::IsoDataPathConfiguration& config) {
  os << "IsoDataPathCfg{codecId: " << config.codecId << ", isTransparent: " << config.isTransparent
     << ", controllerDelayUs: " << config.controllerDelayUs
     << ", configuration.size: " << config.configuration.size() << "}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const le_audio::types::DataPathConfiguration& config) {
  os << "DataPathCfg{datapathId: " << +config.dataPathId
     << ", dataPathCfg.size: " << +config.dataPathConfig.size()
     << ", isoDataPathCfg: " << config.isoDataPathConfig << "}";
  return os;
}

std::ostream& operator<<(std::ostream& os, const LeAudioMetadata& config) {
  os << "LeAudioMetadata{";
  if (config.preferred_audio_context) {
    os << "preferred_audio_context: ";
    os << AudioContexts(config.preferred_audio_context.value());
  }
  if (config.streaming_audio_context) {
    os << ", streaming_audio_context: ";
    os << AudioContexts(config.streaming_audio_context.value());
  }
  if (config.program_info) {
    os << ", program_info: ";
    os << config.program_info.value();
  }
  if (config.language) {
    os << ", language: ";
    os << config.language.value();
  }
  if (config.ccid_list) {
    os << ", ccid_list: ";
    os << base::HexEncode(config.ccid_list.value().data(), config.ccid_list.value().size());
  }
  if (config.parental_rating) {
    os << ", parental_rating: ";
    os << (int)config.parental_rating.value();
  }
  if (config.program_info_uri) {
    os << ", program_info_uri: ";
    os << config.program_info_uri.value();
  }
  if (config.extended_metadata) {
    os << ", extended_metadata: ";
    os << base::HexEncode(config.extended_metadata.value().data(),
                          config.extended_metadata.value().size());
  }
  if (config.vendor_specific) {
    os << ", vendor_specific: ";
    os << base::HexEncode(config.vendor_specific.value().data(),
                          config.vendor_specific.value().size());
  }
  if (config.audio_active_state) {
    os << ", audio_active_state: ";
    os << config.audio_active_state.value();
  }
  if (config.broadcast_audio_immediate_rendering) {
    os << ", broadcast_audio_immediate_rendering: ";
    os << config.broadcast_audio_immediate_rendering.value();
  }
  os << "}";
  return os;
}

}  // namespace types
}  // namespace bluetooth::le_audio
