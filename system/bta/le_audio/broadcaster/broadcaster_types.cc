/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include "broadcaster_types.h"

#include <base/strings/string_number_conversions.h>

#include <vector>

#include "bta/le_audio/audio_hal_client/audio_hal_client.h"
#include "bta_le_audio_broadcaster_api.h"
#include "btm_ble_api_types.h"
#include "internal_include/stack_config.h"
#include "osi/include/properties.h"
#include "stack/include/bt_types.h"

using bluetooth::le_audio::BasicAudioAnnouncementBisConfig;
using bluetooth::le_audio::BasicAudioAnnouncementCodecConfig;
using bluetooth::le_audio::BasicAudioAnnouncementData;
using bluetooth::le_audio::BasicAudioAnnouncementSubgroup;
using bluetooth::le_audio::types::LeAudioContextType;

namespace bluetooth::le_audio {
namespace broadcaster {

static void EmitHeader(const BasicAudioAnnouncementData& announcement_data,
                       std::vector<uint8_t>& data) {
  size_t old_size = data.size();
  data.resize(old_size + 3);

  // Set the cursor behind the old data
  uint8_t* p_value = data.data() + old_size;

  UINT24_TO_STREAM(p_value, announcement_data.presentation_delay_us);
}

static void EmitCodecConfiguration(
    const BasicAudioAnnouncementCodecConfig& config, std::vector<uint8_t>& data,
    const BasicAudioAnnouncementCodecConfig* lower_lvl_config) {
  size_t old_size = data.size();

  // Add 5 for full, or 1 for short Codec ID
  uint8_t codec_config_length = 5;

  auto ltv = types::LeAudioLtvMap(config.codec_specific_params);
  auto codec_spec_raw_sz = ltv.RawPacketSize();
  if (config.vendor_codec_specific_params) {
    codec_spec_raw_sz = config.vendor_codec_specific_params->size();
  }

  // Add 1 for the codec spec. config length + config spec. data itself
  codec_config_length += 1 + codec_spec_raw_sz;

  // Resize and set the cursor behind the old data
  data.resize(old_size + codec_config_length);
  uint8_t* p_value = data.data() + old_size;

  // Codec ID
  UINT8_TO_STREAM(p_value, config.codec_id);
  UINT16_TO_STREAM(p_value, config.vendor_company_id);
  UINT16_TO_STREAM(p_value, config.vendor_codec_id);

  // Codec specific config length and data (either vendor specific or the LTVs)
  UINT8_TO_STREAM(p_value, codec_spec_raw_sz);
  if (config.vendor_codec_specific_params) {
    ARRAY_TO_STREAM(
        p_value, config.vendor_codec_specific_params->data(),
        static_cast<int>(config.vendor_codec_specific_params->size()));
  } else {
    p_value = ltv.RawPacket(p_value);
  }
}

static void EmitMetadata(
    const std::map<uint8_t, std::vector<uint8_t>>& metadata,
    std::vector<uint8_t>& data) {
  auto ltv = types::LeAudioLtvMap(metadata);
  auto ltv_raw_sz = ltv.RawPacketSize();

  size_t old_size = data.size();
  data.resize(old_size + ltv_raw_sz + 1);

  // Set the cursor behind the old data
  uint8_t* p_value = data.data() + old_size;

  UINT8_TO_STREAM(p_value, ltv_raw_sz);
  if (ltv_raw_sz > 0) {
    p_value = ltv.RawPacket(p_value);
  }
}

static void EmitBroadcastName(const std::string& name,
                              std::vector<uint8_t>& data) {
  int name_len = name.length();
  size_t old_size = data.size();
  data.resize(old_size + name_len + 2);

  // Set the cursor behind the old data
  uint8_t* p_value = data.data() + old_size;
  UINT8_TO_STREAM(p_value, name_len + 1);
  UINT8_TO_STREAM(p_value, BTM_BLE_AD_TYPE_BROADCAST_NAME);

  std::vector<uint8_t> vec(name.begin(), name.end());
  ARRAY_TO_STREAM(p_value, vec.data(), name_len);
}

static void EmitBisConfigs(
    const std::vector<BasicAudioAnnouncementBisConfig>& bis_configs,
    std::vector<uint8_t>& data) {
  // Emit each BIS config - that's the level 3 data
  for (auto const& bis_config : bis_configs) {
    auto ltv = types::LeAudioLtvMap(bis_config.codec_specific_params);
    auto ltv_raw_sz = ltv.RawPacketSize();

    size_t old_size = data.size();
    data.resize(old_size + ltv_raw_sz + 2);

    // Set the cursor behind the old data
    auto* p_value = data.data() + old_size;

    // BIS_index[i[k]]
    UINT8_TO_STREAM(p_value, bis_config.bis_index);

    // Per BIS Codec Specific Params[i[k]]
    UINT8_TO_STREAM(p_value, ltv_raw_sz);
    if (ltv_raw_sz > 0) {
      p_value = ltv.RawPacket(p_value);
    }
  }
}

static void EmitSubgroup(const BasicAudioAnnouncementSubgroup& subgroup_config,
                         std::vector<uint8_t>& data) {
  // That's the level 2 data

  // Resize for the num_bis
  size_t initial_offset = data.size();
  data.resize(initial_offset + 1);

  // Set the cursor behind the old data and adds the level 2 Num_BIS[i]
  uint8_t* p_value = data.data() + initial_offset;
  UINT8_TO_STREAM(p_value, subgroup_config.bis_configs.size());

  EmitCodecConfiguration(subgroup_config.codec_config, data, nullptr);
  EmitMetadata(subgroup_config.metadata, data);

  // This adds the level 3 data
  EmitBisConfigs(subgroup_config.bis_configs, data);
}

bool ToRawPacket(BasicAudioAnnouncementData const& in,
                 std::vector<uint8_t>& data) {
  EmitHeader(in, data);

  // Set the cursor behind the old data and resize
  size_t old_size = data.size();
  data.resize(old_size + 1);
  uint8_t* p_value = data.data() + old_size;

  // Emit the subgroup size and each subgroup
  // That's the level 1 Num_Subgroups
  UINT8_TO_STREAM(p_value, in.subgroup_configs.size());
  for (const auto& subgroup_config : in.subgroup_configs) {
    // That's the level 2 and higher level data
    EmitSubgroup(subgroup_config, data);
  }

  return true;
}

void PrepareAdvertisingData(
    bool is_public, const std::string& broadcast_name,
    bluetooth::le_audio::BroadcastId& broadcast_id,
    const bluetooth::le_audio::PublicBroadcastAnnouncementData&
        public_announcement,
    std::vector<uint8_t>& adv_data) {
  adv_data.resize(7);
  uint8_t* data_ptr = adv_data.data();
  UINT8_TO_STREAM(data_ptr, 6);
  UINT8_TO_STREAM(data_ptr, BTM_BLE_AD_TYPE_SERVICE_DATA_TYPE);
  UINT16_TO_STREAM(data_ptr, kBroadcastAudioAnnouncementServiceUuid);
  UINT24_TO_STREAM(data_ptr, broadcast_id);

  // Prepare public broadcast announcement data
  if (is_public) {
    size_t old_size = adv_data.size();
    // 5: datalen(1) + adtype(1) + serviceuuid(2) + features(1)
    adv_data.resize(old_size + 5);
    // Skip the data length field until the full content is generated
    data_ptr = adv_data.data() + old_size + 1;
    UINT8_TO_STREAM(data_ptr, BTM_BLE_AD_TYPE_SERVICE_DATA_TYPE);
    UINT16_TO_STREAM(data_ptr, kPublicBroadcastAnnouncementServiceUuid);
    UINT8_TO_STREAM(data_ptr, public_announcement.features);
    // Set metadata length to 0 if no meta data present
    EmitMetadata(public_announcement.metadata, adv_data);

    // Update the length field accordingly
    data_ptr = adv_data.data() + old_size;
    UINT8_TO_STREAM(data_ptr, adv_data.size() - old_size - 1);

    // Prepare broadcast name
    if (!broadcast_name.empty()) {
      EmitBroadcastName(broadcast_name, adv_data);
    }
  }
}

void PreparePeriodicData(const BasicAudioAnnouncementData& announcement,
                         std::vector<uint8_t>& periodic_data) {
  /* Account for AD Type + Service UUID */
  periodic_data.resize(4);
  /* Skip the data length field until the full content is generated */
  uint8_t* data_ptr = periodic_data.data() + 1;
  UINT8_TO_STREAM(data_ptr, BTM_BLE_AD_TYPE_SERVICE_DATA_TYPE);
  UINT16_TO_STREAM(data_ptr, kBasicAudioAnnouncementServiceUuid);

  /* Append the announcement */
  ToRawPacket(announcement, periodic_data);

  /* Update the length field accordingly */
  data_ptr = periodic_data.data();
  UINT8_TO_STREAM(data_ptr, periodic_data.size() - 1);
}

le_audio::LeAudioCodecConfiguration
BroadcastConfiguration::GetAudioHalClientConfig() const {
  return {
      // Get the maximum number of channels
      .num_channels = GetNumChannelsMax(),
      // Get the max sampling frequency
      .sample_rate = GetSamplingFrequencyHzMax(),
      // Use the default 16 bits per sample resolution in the audio framework
      .bits_per_sample = 16,
      // Get the data interval
      .data_interval_us = GetSduIntervalUs(),
  };
}

std::ostream& operator<<(
    std::ostream& os,
    const bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig&
        config) {
  os << " BroadcastSubgroupCodecConfig={";
  os << "CodecID="
     << "{" << +config.GetLeAudioCodecId().coding_format << ":"
     << +config.GetLeAudioCodecId().vendor_company_id << ":"
     << +config.GetLeAudioCodecId().vendor_codec_id << "}, ";
  os << "BISes=[";
  if (!config.bis_codec_configs_.empty()) {
    for (auto const& bis_config : config.bis_codec_configs_) {
      os << bis_config << ", ";
    }
    os << "\b\b";
  }
  os << "]";
  os << ", BitsPerSample=" << +config.GetBitsPerSample() << "}";
  os << "}";
  return os;
}

std::ostream& operator<<(
    std::ostream& os,
    const le_audio::broadcaster::BroadcastSubgroupBisCodecConfig& config) {
  os << "BisCfg={numBis=" << +config.GetNumBis()
     << ", NumChannelsPerBis=" << +config.GetNumChannelsPerBis()
     << ", CodecSpecific=" << config.GetCodecSpecData().GetAsCoreCodecConfig();
  if (config.GetVendorCodecSpecific().has_value()) {
    os << ", VendorSpecific=[";
    if (!config.GetVendorCodecSpecific()->empty()) {
      os << base::HexEncode(config.GetVendorCodecSpecific()->data(),
                            config.GetVendorCodecSpecific()->size());
    }
    os << "]";
  }
  os << "}";
  return os;
}

std::ostream& operator<<(
    std::ostream& os,
    const bluetooth::le_audio::broadcaster::BroadcastQosConfig& config) {
  os << " BroadcastQosConfig=[";
  os << "RTN=" << +config.getRetransmissionNumber();
  os << ", MaxTransportLatency=" << config.getMaxTransportLatency();
  os << "]";
  return os;
}

std::ostream& operator<<(
    std::ostream& os,
    const le_audio::broadcaster::BroadcastConfiguration& config) {
  os << "BroadcastCfg={";
  for (const auto& subgroup_cfg : config.subgroups) {
    os << subgroup_cfg << std::endl;
  }
  os << config.qos << std::endl;
  os << config.data_path << std::endl;
  os << ", sduIntervalUs=" << config.sduIntervalUs;
  os << ", maxSduOctets=" << config.maxSduOctets;
  os << ", phy=" << config.phy;
  os << ", packing=" << config.packing;
  os << ", framing=" << config.framing;
  os << "}" << std::endl;

  return os;
}

} /* namespace broadcaster */
}  // namespace bluetooth::le_audio

/* Helper functions for comparing BroadcastAnnouncements */
namespace bluetooth::le_audio {

static bool isMetadataSame(std::map<uint8_t, std::vector<uint8_t>> m1,
                           std::map<uint8_t, std::vector<uint8_t>> m2) {
  if (m1.size() != m2.size()) return false;

  for (auto& m1pair : m1) {
    if (m2.count(m1pair.first) == 0) return false;

    auto& m2val = m2.at(m1pair.first);
    if (m1pair.second.size() != m2val.size()) return false;

    if (m1pair.second.size() != 0) {
      if (memcmp(m1pair.second.data(), m2val.data(), m2val.size()) != 0)
        return false;
    }
  }
  return true;
}

bool operator==(const BasicAudioAnnouncementData& lhs,
                const BasicAudioAnnouncementData& rhs) {
  if (lhs.presentation_delay_us != rhs.presentation_delay_us) return false;

  if (lhs.subgroup_configs.size() != rhs.subgroup_configs.size()) return false;

  for (auto i = 0lu; i < lhs.subgroup_configs.size(); ++i) {
    auto& lhs_subgroup = lhs.subgroup_configs[i];
    auto& rhs_subgroup = rhs.subgroup_configs[i];

    if (lhs_subgroup.codec_config.codec_id !=
        rhs_subgroup.codec_config.codec_id)
      return false;

    if (lhs_subgroup.codec_config.vendor_company_id !=
        rhs_subgroup.codec_config.vendor_company_id)
      return false;

    if (lhs_subgroup.codec_config.vendor_codec_id !=
        rhs_subgroup.codec_config.vendor_codec_id)
      return false;

    if (!isMetadataSame(lhs_subgroup.codec_config.codec_specific_params,
                        rhs_subgroup.codec_config.codec_specific_params))
      return false;

    if (!isMetadataSame(lhs_subgroup.metadata, rhs_subgroup.metadata))
      return false;

    for (auto j = 0lu; j < lhs_subgroup.bis_configs.size(); ++j) {
      auto& lhs_bis_config = lhs_subgroup.bis_configs[i];
      auto& rhs_bis_config = rhs_subgroup.bis_configs[i];
      if (lhs_bis_config.bis_index != rhs_bis_config.bis_index) return false;

      if (!isMetadataSame(lhs_bis_config.codec_specific_params,
                          rhs_bis_config.codec_specific_params))
        return false;
    }
  }

  return true;
}

bool operator==(const PublicBroadcastAnnouncementData& lhs,
                const PublicBroadcastAnnouncementData& rhs) {
  if (lhs.features != rhs.features) return false;
  if (!isMetadataSame(lhs.metadata, rhs.metadata)) return false;

  return true;
}
}  // namespace bluetooth::le_audio
