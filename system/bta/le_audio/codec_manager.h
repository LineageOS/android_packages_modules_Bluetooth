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

#include <bluetooth/log.h>

#include <vector>

#include "broadcaster/broadcaster_types.h"
#include "hardware/bt_le_audio.h"
#include "le_audio_types.h"

namespace bluetooth::le_audio {

class LeAudioSinkAudioHalClient;
class LeAudioSourceAudioHalClient;

struct stream_map_info {
  stream_map_info(uint16_t stream_handle, uint32_t audio_channel_allocation,
                  bool is_stream_active)
      : stream_handle(stream_handle),
        audio_channel_allocation(audio_channel_allocation),
        is_stream_active(is_stream_active) {}
  uint16_t stream_handle;
  uint32_t audio_channel_allocation;
  bool is_stream_active;
};

struct offload_config {
  std::vector<stream_map_info> stream_map;
  uint8_t bits_per_sample;
  uint32_t sampling_rate;
  uint32_t frame_duration;
  uint16_t octets_per_frame;
  uint8_t blocks_per_sdu;
  uint16_t peer_delay_ms;
};

struct broadcast_offload_config {
  std::vector<std::pair<uint16_t, uint32_t>> stream_map;
  uint8_t bits_per_sample;
  uint32_t sampling_rate;
  uint32_t frame_duration;
  uint16_t octets_per_frame;
  uint8_t blocks_per_sdu;
  uint8_t retransmission_number;
  uint16_t max_transport_latency;
};

class CodecManager {
 public:
  struct UnicastConfigurationRequirements {
    ::bluetooth::le_audio::types::LeAudioContextType audio_context_type;
    std::optional<std::vector<types::acs_ac_record>> sink_pacs;
    std::optional<std::vector<types::acs_ac_record>> source_pacs;

    struct DeviceDirectionRequirements {
      uint8_t target_latency = types::kTargetLatencyUndefined;
      uint8_t target_Phy = types::kTargetPhyUndefined;
      types::LeAudioLtvMap params;
    };

    std::optional<std::vector<DeviceDirectionRequirements>> sink_requirements;
    std::optional<std::vector<DeviceDirectionRequirements>> source_requirements;
  };

  /* The verifier function checks each possible configuration (from the set of
   * all possible, supported configuration acquired from
   * AudioSetConfigurationProvider for the given scenario), to select a single
   * configuration, matching the current streaming audio group requirements.
   * Note: Used only with the legacy AudioSetConfigurationProvider.
   */
  typedef std::function<const set_configurations::AudioSetConfiguration*(
      const UnicastConfigurationRequirements& requirements,
      const set_configurations::AudioSetConfigurations* confs)>
      UnicastConfigurationVerifier;

  struct BroadcastConfigurationRequirements {
    std::vector<
        std::pair<bluetooth::le_audio::types::LeAudioContextType, uint8_t>>
        subgroup_quality;
  };

  virtual ~CodecManager() = default;
  static CodecManager* GetInstance(void) {
    static CodecManager* instance = new CodecManager();
    return instance;
  }
  void Start(const std::vector<bluetooth::le_audio::btle_audio_codec_config_t>&
                 offloading_preference);
  void Stop(void);
  virtual types::CodecLocation GetCodecLocation(void) const;
  virtual bool IsDualBiDirSwbSupported(void) const;
  virtual void UpdateCisConfiguration(
      const std::vector<struct types::cis>& cises,
      const stream_parameters& stream_params, uint8_t direction);
  virtual void ClearCisConfiguration(uint8_t direction);
  virtual bool UpdateActiveUnicastAudioHalClient(
      LeAudioSourceAudioHalClient* source_unicast_client,
      LeAudioSinkAudioHalClient* sink_unicast_client, bool is_active);
  virtual bool UpdateActiveBroadcastAudioHalClient(
      LeAudioSourceAudioHalClient* source_broadcast_client, bool is_active);
  virtual void UpdateActiveAudioConfig(
      const types::BidirectionalPair<stream_parameters>& stream_params,
      types::BidirectionalPair<uint16_t> delays_ms,
      std::function<void(const offload_config& config, uint8_t direction)>
          update_receiver);
  virtual std::unique_ptr<
      ::bluetooth::le_audio::set_configurations::AudioSetConfiguration>
  GetCodecConfig(const UnicastConfigurationRequirements& requirements,
                 UnicastConfigurationVerifier verifier);
  virtual bool CheckCodecConfigIsBiDirSwb(
      const ::bluetooth::le_audio::set_configurations::AudioSetConfiguration&
          config) const;
  virtual bool CheckCodecConfigIsDualBiDirSwb(
      const ::bluetooth::le_audio::set_configurations::AudioSetConfiguration&
          config) const;
  virtual std::unique_ptr<broadcaster::BroadcastConfiguration>
  GetBroadcastConfig(
      const BroadcastConfigurationRequirements& requirements) const;

  virtual void UpdateBroadcastConnHandle(
      const std::vector<uint16_t>& conn_handle,
      std::function<
          void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
          update_receiver);
  virtual std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
  GetLocalAudioOutputCodecCapa();
  virtual std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
  GetLocalAudioInputCodecCapa();

 private:
  CodecManager();
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

std::ostream& operator<<(
    std::ostream& os,
    const CodecManager::UnicastConfigurationRequirements& req);
}  // namespace bluetooth::le_audio

namespace fmt {
template <>
struct formatter<
    bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements>
    : ostream_formatter {};
}  // namespace fmt
