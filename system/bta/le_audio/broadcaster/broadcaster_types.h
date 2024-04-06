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

#pragma once

#include <bluetooth/log.h>

#include <optional>

#include "bta/le_audio/le_audio_types.h"
#include "bta_le_audio_api.h"
#include "bta_le_audio_broadcaster_api.h"

/* Types used internally by various modules of the broadcaster but not exposed
 * in the API.
 */

namespace bluetooth::le_audio {
struct LeAudioCodecConfiguration;

namespace broadcaster {
static const uint16_t kBroadcastAudioAnnouncementServiceUuid = 0x1852;
static const uint16_t kBasicAudioAnnouncementServiceUuid = 0x1851;
static const uint16_t kPublicBroadcastAnnouncementServiceUuid = 0x1856;

static const uint8_t kBisIndexInvalid = 0;

bool ToRawPacket(bluetooth::le_audio::BasicAudioAnnouncementData const&,
                 std::vector<uint8_t>&);

void PrepareAdvertisingData(
    bool is_public, const std::string& broadcast_name,
    bluetooth::le_audio::BroadcastId& broadcast_id,
    const bluetooth::le_audio::PublicBroadcastAnnouncementData&
        public_announcement,
    std::vector<uint8_t>& adv_data);
void PreparePeriodicData(
    const bluetooth::le_audio::BasicAudioAnnouncementData& announcement,
    std::vector<uint8_t>& periodic_data);

struct BroadcastSubgroupBisCodecConfig {
  BroadcastSubgroupBisCodecConfig(
      uint8_t num_bis, types::LeAudioLtvMap codec_specific,
      std::optional<std::vector<uint8_t>> vendor_codec_specific = std::nullopt)
      : num_bis_(num_bis),
        codec_specific_(codec_specific),
        vendor_codec_specific_(vendor_codec_specific) {}

  bool operator==(const BroadcastSubgroupBisCodecConfig& other) const {
    return (num_bis_ == other.num_bis_) &&
           (codec_specific_ == other.codec_specific_) &&
           (vendor_codec_specific_ == other.vendor_codec_specific_);
  }

  bool operator!=(const BroadcastSubgroupBisCodecConfig& other) const {
    return !(*this == other);
  }

  uint8_t GetNumBis() const { return num_bis_; }

  const types::LeAudioLtvMap& GetCodecSpecData() const {
    return codec_specific_;
  };

  const std::optional<std::vector<uint8_t>>& GetVendorCodecSpecific() const {
    return vendor_codec_specific_;
  }

  bool HasVendorCodecSpecific() const {
    return vendor_codec_specific_.has_value();
  }

  uint8_t GetNumChannels() const { return num_bis_ * GetNumChannelsPerBis(); }

  uint32_t GetSamplingFrequencyHz() const {
    return codec_specific_.GetAsCoreCodecConfig().GetSamplingFrequencyHz();
  }

  uint8_t GetNumChannelsPerBis() const {
    return codec_specific_.GetAsCoreCodecConfig().GetChannelCountPerIsoStream();
  }

 private:
  uint8_t num_bis_;
  /* Codec Specific Configuration */
  types::LeAudioLtvMap codec_specific_;
  std::optional<std::vector<uint8_t>> vendor_codec_specific_;
};

std::ostream& operator<<(
    std::ostream& os,
    const le_audio::broadcaster::BroadcastSubgroupBisCodecConfig& config);

struct BroadcastSubgroupCodecConfig {
  BroadcastSubgroupCodecConfig(
      types::LeAudioCodecId codec_id,
      std::vector<BroadcastSubgroupBisCodecConfig> bis_codec_configs,
      uint8_t bits_per_sample,
      std::optional<std::vector<uint8_t>> subgroup_vendor_codec_config =
          std::nullopt)
      : codec_id_(codec_id),
        bis_codec_configs_(bis_codec_configs),
        subgroup_vendor_codec_config_(subgroup_vendor_codec_config),
        bits_per_sample_(bits_per_sample) {}

  bool operator==(const BroadcastSubgroupCodecConfig& other) const {
    if (subgroup_vendor_codec_config_.has_value() !=
        other.subgroup_vendor_codec_config_.has_value())
      return false;

    if (subgroup_vendor_codec_config_.has_value()) {
      if (subgroup_vendor_codec_config_->size() !=
          other.subgroup_vendor_codec_config_->size())
        return false;
    }

    if (0 != memcmp(subgroup_vendor_codec_config_->data(),
                    other.subgroup_vendor_codec_config_->data(),
                    subgroup_vendor_codec_config_->size())) {
      return false;
    }

    return (codec_id_ == other.codec_id_) &&
           (bis_codec_configs_ == other.bis_codec_configs_) &&
           (bits_per_sample_ == other.bits_per_sample_);
  }

  bool operator!=(const BroadcastSubgroupCodecConfig& other) const {
    return !(*this == other);
  }

  types::LeAudioLtvMap GetCommonBisCodecSpecData() const {
    if (bis_codec_configs_.empty()) return types::LeAudioLtvMap();
    auto common_ltv = bis_codec_configs_[0].GetCodecSpecData();
    for (auto it = bis_codec_configs_.begin() + 1;
         it != bis_codec_configs_.end(); ++it) {
      common_ltv = it->GetCodecSpecData().GetIntersection(common_ltv);
    }
    return common_ltv;
  }

  std::optional<std::vector<uint8_t>> GetVendorCodecSpecData() const {
    return subgroup_vendor_codec_config_;
  }

  std::optional<std::vector<uint8_t>> GetBisVendorCodecSpecData(
      uint8_t bis_idx) const {
    if (bis_codec_configs_.empty()) return std::nullopt;
    auto config = bis_codec_configs_.at(0);
    if ((bis_idx != 0) && (bis_idx < bis_codec_configs_.size())) {
      config = bis_codec_configs_.at(bis_idx);
    }

    if (config.HasVendorCodecSpecific()) {
      return config.GetVendorCodecSpecific().value();
    }

    return std::nullopt;
  }

  uint16_t GetBisOctetsPerCodecFrame(uint8_t bis_idx) const {
    // Check the subgroup level parameters first, then the specific BIS
    auto num_octets = GetCommonBisCodecSpecData()
                          .GetAsCoreCodecConfig()
                          .octets_per_codec_frame.value_or(0);
    if (num_octets) return num_octets;

    // Currently not a single software vendor codec was integrated and only the
    // LTVs parameters are understood by the BT stack.
    auto opt_ltvs = GetBisCodecSpecData(bis_idx);
    if (opt_ltvs) {
      return opt_ltvs->GetAsCoreCodecConfig().octets_per_codec_frame.value_or(
                 0) *
             opt_ltvs->GetAsCoreCodecConfig()
                 .codec_frames_blocks_per_sdu.value_or(0);
    }

    return 0;
  }

  std::optional<types::LeAudioLtvMap> GetBisCodecSpecData(
      uint8_t bis_idx) const {
    if (bis_codec_configs_.empty()) return std::nullopt;
    auto config = bis_codec_configs_.at(0);
    if ((bis_idx != 0) && (bis_idx < bis_codec_configs_.size())) {
      config = bis_codec_configs_.at(bis_idx);
    }

    if (config.HasVendorCodecSpecific()) {
      return std::nullopt;
    }

    auto cfg = config.GetCodecSpecData();
    /* Set the audio locations if not set */
    if (!cfg.Find(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation)) {
      switch (bis_idx) {
        case 0:
          cfg.Add(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
                  codec_spec_conf::kLeAudioLocationFrontLeft);
          break;
        case 1:
          cfg.Add(codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
                  codec_spec_conf::kLeAudioLocationFrontRight);
          break;
        default:
          break;
      }
    }
    return cfg;
  }

  const types::LeAudioCodecId& GetLeAudioCodecId() const { return codec_id_; }

  uint8_t GetNumBis() const {
    uint8_t value = 0;
    // Iterate over BISes
    for (auto const& cfg : bis_codec_configs_) {
      value += cfg.GetNumBis();
    }
    return value;
  }

  uint8_t GetNumBis(uint8_t bis_idx) const {
    if (bis_idx < bis_codec_configs_.size()) {
      return bis_codec_configs_.at(bis_idx).GetNumBis();
    }
    return 0;
  }

  uint8_t GetNumChannelsTotal() const {
    uint8_t value = 0;
    // Iterate over BISes
    for (auto const& cfg : bis_codec_configs_) {
      value += cfg.GetNumChannels();
    }
    return value;
  }

  uint32_t GetSamplingFrequencyHzMax() const {
    uint32_t value = 0;
    // Iterate over BISes
    for (auto const& cfg : bis_codec_configs_) {
      value += cfg.GetSamplingFrequencyHz();
    }
    return value;
  }

  // Local audio source sample resolution
  uint8_t GetBitsPerSample() const { return bits_per_sample_; }

  size_t GetAllBisConfigCount() const { return bis_codec_configs_.size(); }

  friend std::ostream& operator<<(
      std::ostream& os,
      const le_audio::broadcaster::BroadcastSubgroupCodecConfig& config);

 private:
  types::LeAudioCodecId codec_id_;
  /* A list of distinct BIS configurations - each config can be allied to
   * num_bis number of BISes
   */
  std::vector<BroadcastSubgroupBisCodecConfig> bis_codec_configs_;
  std::optional<std::vector<uint8_t>> subgroup_vendor_codec_config_;

  /* Local audio source sample resolution - this should consider the HW
   * offloader requirements
   */
  uint8_t bits_per_sample_;
};

std::ostream& operator<<(
    std::ostream& os,
    const bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig&
        config);

struct BroadcastQosConfig {
  BroadcastQosConfig(uint8_t retransmission_number,
                     uint16_t max_transport_latency)
      : retransmission_number_(retransmission_number),
        max_transport_latency_(max_transport_latency) {}

  bool operator==(const BroadcastQosConfig& other) const {
    return (retransmission_number_ == other.retransmission_number_) &&
           (max_transport_latency_ == other.max_transport_latency_);
  }

  bool operator!=(const BroadcastQosConfig& other) const {
    return !(*this == other);
  }

  uint8_t getRetransmissionNumber() const { return retransmission_number_; }
  uint16_t getMaxTransportLatency() const { return max_transport_latency_; }

 private:
  uint8_t retransmission_number_;
  uint16_t max_transport_latency_;
};

std::ostream& operator<<(
    std::ostream& os,
    const bluetooth::le_audio::broadcaster::BroadcastQosConfig& config);

struct BroadcastConfiguration {
  bool operator==(const BroadcastConfiguration& other) const {
    if ((sduIntervalUs != other.sduIntervalUs) ||
        (maxSduOctets != other.maxSduOctets) || (phy != other.phy) ||
        (packing != other.packing) || (framing != other.framing)) {
      return false;
    }

    if (qos != other.qos) return false;
    if (data_path != other.data_path) return false;
    if (subgroups.size() != other.subgroups.size()) return false;

    for (auto const& subgroup : subgroups) {
      if (std::find(other.subgroups.begin(), other.subgroups.end(), subgroup) ==
          other.subgroups.end()) {
        return false;
      }
    }

    return true;
  }

  bool operator!=(const BroadcastConfiguration& other) const {
    return !(*this == other);
  }

  uint8_t GetNumBisTotal() const {
    auto count = 0;
    // Iterate over subgroups
    for (auto const& cfg : subgroups) {
      count += cfg.GetNumBis();
    }
    return count;
  }

  uint8_t GetNumChannelsMax() const {
    uint8_t value = 0;
    for (auto const& cfg : subgroups) {
      if (cfg.GetNumChannelsTotal() > value) value = cfg.GetNumChannelsTotal();
    }
    return value;
  }

  uint32_t GetSamplingFrequencyHzMax() const {
    uint32_t value = 0;
    for (auto const& cfg : subgroups) {
      if (cfg.GetSamplingFrequencyHzMax() > value)
        value = cfg.GetSamplingFrequencyHzMax();
    }
    return value;
  }

  uint32_t GetSduIntervalUs() const { return sduIntervalUs; }

  uint16_t GetMaxSduOctets() const { return maxSduOctets; }

  LeAudioCodecConfiguration GetAudioHalClientConfig() const;

  std::vector<BroadcastSubgroupCodecConfig> subgroups;
  BroadcastQosConfig qos;

  types::DataPathConfiguration data_path;

  uint32_t sduIntervalUs;
  uint16_t maxSduOctets;
  uint8_t phy;
  uint8_t packing;
  uint8_t framing;
};

std::ostream& operator<<(
    std::ostream& os,
    const le_audio::broadcaster::BroadcastConfiguration& config);

}  // namespace broadcaster
}  // namespace bluetooth::le_audio

/* BroadcastAnnouncements compare helper */
namespace bluetooth::le_audio {
bool operator==(const BasicAudioAnnouncementData& lhs,
                const BasicAudioAnnouncementData& rhs);
bool operator==(const PublicBroadcastAnnouncementData& lhs,
                const PublicBroadcastAnnouncementData& rhs);
}  // namespace bluetooth::le_audio
