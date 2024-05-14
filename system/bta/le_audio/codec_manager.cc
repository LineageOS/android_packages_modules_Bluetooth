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

#include "codec_manager.h"

#include <bluetooth/log.h>

#include <bitset>
#include <sstream>

#include "audio_hal_client/audio_hal_client.h"
#include "broadcaster/broadcast_configuration_provider.h"
#include "broadcaster/broadcaster_types.h"
#include "hci/controller_interface.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_set_configuration_provider.h"
#include "le_audio_utils.h"
#include "main/shim/entry.h"
#include "os/log.h"
#include "osi/include/properties.h"
#include "stack/include/hcimsgs.h"

namespace {

using bluetooth::hci::iso_manager::kIsoDataPathHci;
using bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
using bluetooth::le_audio::CodecManager;
using bluetooth::le_audio::types::CodecLocation;
using bluetooth::legacy::hci::GetInterface;

using bluetooth::le_audio::AudioSetConfigurationProvider;
using bluetooth::le_audio::btle_audio_codec_config_t;
using bluetooth::le_audio::btle_audio_codec_index_t;
using bluetooth::le_audio::set_configurations::AseConfiguration;
using bluetooth::le_audio::set_configurations::AudioSetConfiguration;
using bluetooth::le_audio::set_configurations::AudioSetConfigurations;

typedef struct offloader_stream_maps {
  std::vector<bluetooth::le_audio::stream_map_info> streams_map_target;
  std::vector<bluetooth::le_audio::stream_map_info> streams_map_current;
  bool has_changed;
  bool is_initial;
} offloader_stream_maps_t;
}  // namespace

namespace bluetooth::le_audio {
template <>
offloader_stream_maps_t& types::BidirectionalPair<offloader_stream_maps_t>::get(
    uint8_t direction) {
  log::assert_that(direction < types::kLeAudioDirectionBoth,
                   "Unsupported complex direction. Reference to a single "
                   "complex direction value is not supported.");
  return (direction == types::kLeAudioDirectionSink) ? sink : source;
}

// The mapping for sampling rate, frame duration, and the QoS config
static std::unordered_map<
    int, std::unordered_map<
             int, bluetooth::le_audio::broadcaster::BroadcastQosConfig>>
    bcast_high_reliability_qos = {
        {LeAudioCodecConfiguration::kSampleRate16000,
         {{LeAudioCodecConfiguration::kInterval7500Us,
           bluetooth::le_audio::broadcaster::qos_config_4_45},
          {LeAudioCodecConfiguration::kInterval10000Us,
           bluetooth::le_audio::broadcaster::qos_config_4_60}}},
        {LeAudioCodecConfiguration::kSampleRate24000,
         {{LeAudioCodecConfiguration::kInterval7500Us,
           bluetooth::le_audio::broadcaster::qos_config_4_45},
          {LeAudioCodecConfiguration::kInterval10000Us,
           bluetooth::le_audio::broadcaster::qos_config_4_60}}},
        {LeAudioCodecConfiguration::kSampleRate32000,
         {{LeAudioCodecConfiguration::kInterval7500Us,
           bluetooth::le_audio::broadcaster::qos_config_4_45},
          {LeAudioCodecConfiguration::kInterval10000Us,
           bluetooth::le_audio::broadcaster::qos_config_4_60}}},
        {LeAudioCodecConfiguration::kSampleRate48000,
         {{LeAudioCodecConfiguration::kInterval7500Us,
           bluetooth::le_audio::broadcaster::qos_config_4_50},
          {LeAudioCodecConfiguration::kInterval10000Us,
           bluetooth::le_audio::broadcaster::qos_config_4_65}}}};

struct codec_manager_impl {
 public:
  codec_manager_impl() {
    offload_enable_ = osi_property_get_bool(
                          "ro.bluetooth.leaudio_offload.supported", false) &&
                      !osi_property_get_bool(
                          "persist.bluetooth.leaudio_offload.disabled", true);
    if (offload_enable_ == false) {
      log::info("offload disabled");
      return;
    }

    if (!LeAudioHalVerifier::SupportsLeAudioHardwareOffload()) {
      log::warn("HAL not support hardware offload");
      return;
    }

    if (!bluetooth::shim::GetController()->IsSupported(
            bluetooth::hci::OpCode::CONFIGURE_DATA_PATH)) {
      log::warn("Controller does not support config data path command");
      return;
    }

    log::info("LeAudioCodecManagerImpl: configure_data_path for encode");
    GetInterface().ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER,
                                     kIsoDataPathPlatformDefault, {});
    GetInterface().ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST,
                                     kIsoDataPathPlatformDefault, {});
    SetCodecLocation(CodecLocation::ADSP);
  }
  void start(
      const std::vector<btle_audio_codec_config_t>& offloading_preference) {
    dual_bidirection_swb_supported_ = osi_property_get_bool(
        "bluetooth.leaudio.dual_bidirection_swb.supported", false);
    bluetooth::le_audio::AudioSetConfigurationProvider::Initialize(
        GetCodecLocation());
    UpdateOffloadCapability(offloading_preference);
  }
  ~codec_manager_impl() {
    if (GetCodecLocation() != CodecLocation::HOST) {
      GetInterface().ConfigureDataPath(hci_data_direction_t::HOST_TO_CONTROLLER,
                                       kIsoDataPathHci, {});
      GetInterface().ConfigureDataPath(hci_data_direction_t::CONTROLLER_TO_HOST,
                                       kIsoDataPathHci, {});
    }
    bluetooth::le_audio::AudioSetConfigurationProvider::Cleanup();
  }
  CodecLocation GetCodecLocation(void) const { return codec_location_; }

  bool IsDualBiDirSwbSupported(void) const {
    if (GetCodecLocation() == CodecLocation::ADSP) {
      // Whether dual bidirection swb is supported by property and for offload
      return offload_dual_bidirection_swb_supported_;
    } else if (GetCodecLocation() == CodecLocation::HOST) {
      // Whether dual bidirection swb is supported for software
      return dual_bidirection_swb_supported_;
    }

    return false;
  }

  std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
  GetLocalAudioOutputCodecCapa() {
    return codec_output_capa;
  }

  std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
  GetLocalAudioInputCodecCapa() {
    return codec_input_capa;
  }

  void UpdateActiveAudioConfig(
      const types::BidirectionalPair<stream_parameters>& stream_params,
      types::BidirectionalPair<uint16_t> delays_ms,
      std::function<void(const offload_config& config, uint8_t direction)>
          update_receiver) {
    if (GetCodecLocation() != bluetooth::le_audio::types::CodecLocation::ADSP) {
      return;
    }

    for (auto direction :
         {bluetooth::le_audio::types::kLeAudioDirectionSink,
          bluetooth::le_audio::types::kLeAudioDirectionSource}) {
      auto& stream_map = offloader_stream_maps.get(direction);
      if (!stream_map.has_changed && !stream_map.is_initial) {
        continue;
      }
      if (stream_params.get(direction).stream_locations.empty()) {
        continue;
      }

      bluetooth::le_audio::offload_config unicast_cfg = {
          .stream_map = (stream_map.is_initial ||
                         LeAudioHalVerifier::SupportsStreamActiveApi())
                            ? stream_map.streams_map_target
                            : stream_map.streams_map_current,
          // TODO: set the default value 16 for now, would change it if we
          // support mode bits_per_sample
          .bits_per_sample = 16,
          .sampling_rate = stream_params.get(direction).sample_frequency_hz,
          .frame_duration = stream_params.get(direction).frame_duration_us,
          .octets_per_frame =
              stream_params.get(direction).octets_per_codec_frame,
          .blocks_per_sdu =
              stream_params.get(direction).codec_frames_blocks_per_sdu,
          .peer_delay_ms = delays_ms.get(direction),
      };
      update_receiver(unicast_cfg, direction);
      stream_map.is_initial = false;
    }
  }

  bool UpdateActiveUnicastAudioHalClient(
      LeAudioSourceAudioHalClient* source_unicast_client,
      LeAudioSinkAudioHalClient* sink_unicast_client, bool is_active) {
    log::debug("local_source: {}, local_sink: {}, is_active: {}",
               fmt::ptr(source_unicast_client), fmt::ptr(sink_unicast_client),
               is_active);

    if (source_unicast_client == nullptr && sink_unicast_client == nullptr) {
      return false;
    }

    if (is_active) {
      if (source_unicast_client && unicast_local_source_hal_client != nullptr) {
        log::error("Trying to override previous source hal client {}",
                   fmt::ptr(unicast_local_source_hal_client));
        return false;
      }

      if (sink_unicast_client && unicast_local_sink_hal_client != nullptr) {
        log::error("Trying to override previous sink hal client {}",
                   fmt::ptr(unicast_local_sink_hal_client));
        return false;
      }

      if (source_unicast_client) {
        unicast_local_source_hal_client = source_unicast_client;
      }

      if (sink_unicast_client) {
        unicast_local_sink_hal_client = sink_unicast_client;
      }

      return true;
    }

    if (source_unicast_client &&
        source_unicast_client != unicast_local_source_hal_client) {
      log::error("local source session does not match {} != {}",
                 fmt::ptr(source_unicast_client),
                 fmt::ptr(unicast_local_source_hal_client));
      return false;
    }

    if (sink_unicast_client &&
        sink_unicast_client != unicast_local_sink_hal_client) {
      log::error("local source session does not match {} != {}",
                 fmt::ptr(sink_unicast_client),
                 fmt::ptr(unicast_local_sink_hal_client));
      return false;
    }

    if (source_unicast_client) {
      unicast_local_source_hal_client = nullptr;
    }

    if (sink_unicast_client) {
      unicast_local_sink_hal_client = nullptr;
    }

    return true;
  }

  bool UpdateActiveBroadcastAudioHalClient(
      LeAudioSourceAudioHalClient* source_broadcast_client, bool is_active) {
    log::debug("local_source: {},is_active: {}",
               fmt::ptr(source_broadcast_client), is_active);

    if (source_broadcast_client == nullptr) {
      return false;
    }

    if (is_active) {
      if (broadcast_local_source_hal_client != nullptr) {
        log::error("Trying to override previous source hal client {}",
                   fmt::ptr(broadcast_local_source_hal_client));
        return false;
      }
      broadcast_local_source_hal_client = source_broadcast_client;
      return true;
    }

    if (source_broadcast_client != broadcast_local_source_hal_client) {
      log::error("local source session does not match {} != {}",
                 fmt::ptr(source_broadcast_client),
                 fmt::ptr(broadcast_local_source_hal_client));
      return false;
    }

    broadcast_local_source_hal_client = nullptr;

    return true;
  }

  AudioSetConfigurations GetSupportedCodecConfigurations(
      const CodecManager::UnicastConfigurationRequirements& requirements)
      const {
    if (GetCodecLocation() == le_audio::types::CodecLocation::ADSP) {
      log::verbose("Get offload config for the context type: {}",
                   (int)requirements.audio_context_type);

      // TODO: Need to have a mechanism to switch to software session if offload
      // doesn't support.
      return context_type_offload_config_map_.count(
                 requirements.audio_context_type)
                 ? context_type_offload_config_map_.at(
                       requirements.audio_context_type)
                 : AudioSetConfigurations();
    }

    log::verbose("Get software config for the context type: {}",
                 (int)requirements.audio_context_type);
    return *AudioSetConfigurationProvider::Get()->GetConfigurations(
        requirements.audio_context_type);
  }

  void PrintDebugState() const {
    for (types::LeAudioContextType ctx_type :
         types::kLeAudioContextAllTypesArray) {
      std::stringstream os;
      os << ctx_type << ": ";
      if (context_type_offload_config_map_.count(ctx_type) == 0) {
        os << "{empty}";
      } else {
        os << "{";
        for (const auto& conf : context_type_offload_config_map_.at(ctx_type)) {
          os << conf->name << ", ";
        }
        os << "}";
      }
      log::info("Offload configs for {}", os.str());
    }
  }

  std::unique_ptr<AudioSetConfiguration> GetCodecConfig(
      const CodecManager::UnicastConfigurationRequirements& requirements,
      CodecManager::UnicastConfigurationVerifier verifier) {
    auto configs = GetSupportedCodecConfigurations(requirements);
    if (configs.empty()) {
      log::error("No valid configuration matching the requirements: {}",
                 requirements);
      PrintDebugState();
      return nullptr;
    }

    // Remove the dual bidir SWB config if not supported
    if (!IsDualBiDirSwbSupported()) {
      configs.erase(
          std::remove_if(configs.begin(), configs.end(),
                         [](auto const& el) {
                           if (el->confs.source.empty()) return false;
                           return AudioSetConfigurationProvider::Get()
                               ->CheckConfigurationIsDualBiDirSwb(*el);
                         }),
          configs.end());
    }

    // Note: For the only supported right now legacy software configuration
    //       provider, we use the device group logic to match the proper
    //       configuration with group capabilities. Note that this path only
    //       supports the LC3 codec format. For the multicodec support we should
    //       rely on the configuration matcher behind the AIDL interface.
    auto conf = verifier(requirements, &configs);
    return conf ? std::make_unique<AudioSetConfiguration>(*conf) : nullptr;
  }

  bool CheckCodecConfigIsBiDirSwb(const AudioSetConfiguration& config) {
    return AudioSetConfigurationProvider::Get()->CheckConfigurationIsBiDirSwb(
        config);
  }

  bool CheckCodecConfigIsDualBiDirSwb(const AudioSetConfiguration& config) {
    return AudioSetConfigurationProvider::Get()
        ->CheckConfigurationIsDualBiDirSwb(config);
  }

  void UpdateSupportedBroadcastConfig(
      const std::vector<AudioSetConfiguration>& adsp_capabilities) {
    log::info("UpdateSupportedBroadcastConfig");

    for (const auto& adsp_audio_set_conf : adsp_capabilities) {
      if (adsp_audio_set_conf.confs.sink.empty() ||
          !adsp_audio_set_conf.confs.source.empty()) {
        continue;
      }

      auto& adsp_config = adsp_audio_set_conf.confs.sink[0];

      const types::LeAudioCoreCodecConfig core_config =
          adsp_config.codec.params.GetAsCoreCodecConfig();
      bluetooth::le_audio::broadcast_offload_config broadcast_config;
      broadcast_config.stream_map.resize(adsp_audio_set_conf.confs.sink.size());

      // Enable the individual channels per BIS in the stream map
      auto all_channels = adsp_config.codec.channel_count_per_iso_stream;
      uint8_t channel_alloc_idx = 0;
      for (auto& [_, channels] : broadcast_config.stream_map) {
        if (all_channels) {
          channels |= (0b1 << channel_alloc_idx++);
          --all_channels;
        }
      }

      broadcast_config.bits_per_sample =
          LeAudioCodecConfiguration::kBitsPerSample16;
      broadcast_config.sampling_rate = core_config.GetSamplingFrequencyHz();
      broadcast_config.frame_duration = core_config.GetFrameDurationUs();
      broadcast_config.octets_per_frame = *(core_config.octets_per_codec_frame);
      broadcast_config.blocks_per_sdu = 1;

      int sample_rate = broadcast_config.sampling_rate;
      int frame_duration = broadcast_config.frame_duration;

      if (bcast_high_reliability_qos.find(sample_rate) !=
              bcast_high_reliability_qos.end() &&
          bcast_high_reliability_qos[sample_rate].find(frame_duration) !=
              bcast_high_reliability_qos[sample_rate].end()) {
        auto qos = bcast_high_reliability_qos[sample_rate].at(frame_duration);
        broadcast_config.retransmission_number = qos.getRetransmissionNumber();
        broadcast_config.max_transport_latency = qos.getMaxTransportLatency();
        supported_broadcast_config.push_back(broadcast_config);
      } else {
        log::error(
            "Cannot find the correspoding QoS config for the sampling_rate: "
            "{}, frame_duration: {}",
            sample_rate, frame_duration);
      }

      log::info("broadcast_config sampling_rate: {}",
                broadcast_config.sampling_rate);
    }
  }

  const broadcast_offload_config* GetBroadcastOffloadConfig(
      uint8_t preferred_quality) {
    if (supported_broadcast_config.empty()) {
      log::error("There is no valid broadcast offload config");
      return nullptr;
    }
    /* Broadcast audio config selection based on source broadcast capability
     *
     * If the preferred_quality is HIGH, the configs ranking is
     * 48_4 > 48_2 > 24_2(sink mandatory) > 16_2(source & sink mandatory)
     *
     * If the preferred_quality is STANDARD, the configs ranking is
     * 24_2(sink mandatory) > 16_2(source & sink mandatory)
     */
    broadcast_target_config = -1;
    for (int i = 0; i < (int)supported_broadcast_config.size(); i++) {
      if (preferred_quality == bluetooth::le_audio::QUALITY_STANDARD) {
        if (supported_broadcast_config[i].sampling_rate == 24000u &&
            supported_broadcast_config[i].octets_per_frame == 60) {  // 24_2
          broadcast_target_config = i;
          break;
        }

        if (supported_broadcast_config[i].sampling_rate == 16000u &&
            supported_broadcast_config[i].octets_per_frame == 40) {  // 16_2
          broadcast_target_config = i;
        }

        continue;
      }

      // perferred_quality = bluetooth::le_audio::QUALITY_HIGH
      if (supported_broadcast_config[i].sampling_rate == 48000u &&
          supported_broadcast_config[i].octets_per_frame == 120) {  // 48_4
        broadcast_target_config = i;
        break;
      }

      if ((supported_broadcast_config[i].sampling_rate == 48000u &&
           supported_broadcast_config[i].octets_per_frame == 100) ||  // 48_2
          (supported_broadcast_config[i].sampling_rate == 24000u &&
           supported_broadcast_config[i].octets_per_frame == 60) ||  // 24_2
          (supported_broadcast_config[i].sampling_rate == 16000u &&
           supported_broadcast_config[i].octets_per_frame == 40)) {  // 16_2
        if (broadcast_target_config == -1 ||
            (supported_broadcast_config[i].sampling_rate >
             supported_broadcast_config[broadcast_target_config].sampling_rate))
          broadcast_target_config = i;
      }
    }

    if (broadcast_target_config == -1) {
      log::error(
          "There is no valid broadcast offload config with preferred_quality");
      return nullptr;
    }

    log::info(
        "stream_map.size(): {}, sampling_rate: {}, frame_duration(us): {}, "
        "octets_per_frame: {}, blocks_per_sdu {}, retransmission_number: {}, "
        "max_transport_latency: {}",
        supported_broadcast_config[broadcast_target_config].stream_map.size(),
        supported_broadcast_config[broadcast_target_config].sampling_rate,
        supported_broadcast_config[broadcast_target_config].frame_duration,
        supported_broadcast_config[broadcast_target_config].octets_per_frame,
        (int)supported_broadcast_config[broadcast_target_config].blocks_per_sdu,
        (int)supported_broadcast_config[broadcast_target_config]
            .retransmission_number,
        supported_broadcast_config[broadcast_target_config]
            .max_transport_latency);

    return &supported_broadcast_config[broadcast_target_config];
  }

  std::unique_ptr<broadcaster::BroadcastConfiguration> GetBroadcastConfig(
      const CodecManager::BroadcastConfigurationRequirements& requirements) {
    if (GetCodecLocation() != types::CodecLocation::ADSP) {
      // Get the software supported broadcast configuration
      return std::make_unique<broadcaster::BroadcastConfiguration>(
          ::bluetooth::le_audio::broadcaster::GetBroadcastConfig(
              requirements.subgroup_quality));
    }

    /* Subgroups with different audio qualities is not being supported now,
     * if any subgroup preferred to use standard audio config, choose
     * the standard audio config instead
     */
    uint8_t BIG_audio_quality = bluetooth::le_audio::QUALITY_HIGH;
    for (const auto& [_, quality] : requirements.subgroup_quality) {
      if (quality == bluetooth::le_audio::QUALITY_STANDARD) {
        BIG_audio_quality = bluetooth::le_audio::QUALITY_STANDARD;
      }
    }

    auto offload_config = GetBroadcastOffloadConfig(BIG_audio_quality);
    if (offload_config == nullptr) {
      log::error("No Offload configuration supported for quality index: {}.",
                 BIG_audio_quality);
      return nullptr;
    }

    types::LeAudioLtvMap codec_params;
    // Map sample freq. value to LE Audio codec specific config value
    if (types::LeAudioCoreCodecConfig::sample_rate_map.count(
            offload_config->sampling_rate)) {
      codec_params.Add(codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                       types::LeAudioCoreCodecConfig::sample_rate_map.at(
                           offload_config->sampling_rate));
    }
    // Map data interval value to LE Audio codec specific config value
    if (types::LeAudioCoreCodecConfig::data_interval_map.count(
            offload_config->frame_duration)) {
      codec_params.Add(codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                       types::LeAudioCoreCodecConfig::data_interval_map.at(
                           offload_config->frame_duration));
    }
    codec_params.Add(codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
                     offload_config->octets_per_frame);

    // Note: We do not support a different channel count on each BIS within the
    // same subgroup.
    uint8_t allocated_channel_count =
        offload_config->stream_map.size()
            ? std::bitset<32>{offload_config->stream_map.at(0).second}.count()
            : 1;
    bluetooth::le_audio::broadcaster::BroadcastSubgroupCodecConfig codec_config(
        bluetooth::le_audio::broadcaster::kLeAudioCodecIdLc3,
        {bluetooth::le_audio::broadcaster::BroadcastSubgroupBisCodecConfig(
            static_cast<uint8_t>(offload_config->stream_map.size()),
            allocated_channel_count, codec_params)},
        offload_config->bits_per_sample);

    bluetooth::le_audio::broadcaster::BroadcastQosConfig qos_config(
        offload_config->retransmission_number,
        offload_config->max_transport_latency);

    // Change the default software encoder config data path ID
    auto data_path = broadcaster::lc3_data_path;
    data_path.dataPathId =
        bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;

    uint16_t max_sdu_octets = 0;
    for (auto [_, allocation] : offload_config->stream_map) {
      auto alloc_channels_per_bis = std::bitset<32>{allocation}.count() ?: 1;
      auto sdu_octets = offload_config->octets_per_frame *
                        offload_config->blocks_per_sdu * alloc_channels_per_bis;
      if (max_sdu_octets < sdu_octets) max_sdu_octets = sdu_octets;
    }

    if (requirements.subgroup_quality.size() > 1) {
      log::error("More than one subgroup is not supported!");
    }

    return std::make_unique<broadcaster::BroadcastConfiguration>(
        broadcaster::BroadcastConfiguration({
            .subgroups = {codec_config},
            .qos = qos_config,
            .data_path = data_path,
            .sduIntervalUs = offload_config->frame_duration,
            .maxSduOctets = max_sdu_octets,
            .phy = 0x02,   // PHY_LE_2M
            .packing = 0,  // Sequential
            .framing = 0   // Unframed,
        }));
  }

  void UpdateBroadcastConnHandle(
      const std::vector<uint16_t>& conn_handle,
      std::function<
          void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
          update_receiver) {
    if (GetCodecLocation() != le_audio::types::CodecLocation::ADSP) {
      return;
    }

    if (broadcast_target_config == -1 ||
        broadcast_target_config >= (int)supported_broadcast_config.size()) {
      log::error("There is no valid broadcast offload config");
      return;
    }

    auto broadcast_config = supported_broadcast_config[broadcast_target_config];
    log::assert_that(conn_handle.size() == broadcast_config.stream_map.size(),
                     "assert failed: conn_handle.size() == "
                     "broadcast_config.stream_map.size()");

    if (broadcast_config.stream_map.size() ==
        LeAudioCodecConfiguration::kChannelNumberStereo) {
      broadcast_config.stream_map[0] = std::pair<uint16_t, uint32_t>{
          conn_handle[0], codec_spec_conf::kLeAudioLocationFrontLeft};
      broadcast_config.stream_map[1] = std::pair<uint16_t, uint32_t>{
          conn_handle[1], codec_spec_conf::kLeAudioLocationFrontRight};
    } else if (broadcast_config.stream_map.size() ==
               LeAudioCodecConfiguration::kChannelNumberMono) {
      broadcast_config.stream_map[0] = std::pair<uint16_t, uint32_t>{
          conn_handle[0], codec_spec_conf::kLeAudioLocationFrontCenter};
    }

    update_receiver(broadcast_config);
  }

  void ClearCisConfiguration(uint8_t direction) {
    if (GetCodecLocation() != bluetooth::le_audio::types::CodecLocation::ADSP) {
      return;
    }

    auto& stream_map = offloader_stream_maps.get(direction);
    stream_map.streams_map_target.clear();
    stream_map.streams_map_current.clear();
  }

  static uint32_t AdjustAllocationForOffloader(uint32_t allocation) {
    if ((allocation & codec_spec_conf::kLeAudioLocationAnyLeft) &&
        (allocation & codec_spec_conf::kLeAudioLocationAnyRight)) {
      return codec_spec_conf::kLeAudioLocationStereo;
    }
    if (allocation & codec_spec_conf::kLeAudioLocationAnyLeft) {
      return codec_spec_conf::kLeAudioLocationFrontLeft;
    }
    if (allocation & codec_spec_conf::kLeAudioLocationAnyRight) {
      return codec_spec_conf::kLeAudioLocationFrontRight;
    }
    return 0;
  }

  void UpdateCisConfiguration(const std::vector<struct types::cis>& cises,
                              const stream_parameters& stream_params,
                              uint8_t direction) {
    if (GetCodecLocation() != bluetooth::le_audio::types::CodecLocation::ADSP) {
      return;
    }

    auto available_allocations =
        AdjustAllocationForOffloader(stream_params.audio_channel_allocation);
    if (available_allocations == 0) {
      log::error("There is no CIS connected");
      return;
    }

    auto& stream_map = offloader_stream_maps.get(direction);
    if (stream_map.streams_map_target.empty()) {
      stream_map.is_initial = true;
    } else if (stream_map.is_initial ||
               LeAudioHalVerifier::SupportsStreamActiveApi()) {
      /* As multiple CISes phone call case, the target_allocation already have
       * the previous data, but the is_initial flag not be cleared. We need to
       * clear here to avoid make duplicated target allocation stream map. */
      stream_map.streams_map_target.clear();
    }

    stream_map.streams_map_current.clear();
    stream_map.has_changed = true;
    bool all_cises_connected =
        (available_allocations == codec_spec_conf::kLeAudioLocationStereo);

    /* If all the cises are connected as stream started, reset changed_flag that
     * the bt stack wouldn't send another audio configuration for the connection
     * status. */
    if (stream_map.is_initial && all_cises_connected) {
      stream_map.has_changed = false;
    }

    const std::string tag = types::BidirectionalPair<std::string>(
                                {.sink = "Sink", .source = "Source"})
                                .get(direction);

    constexpr types::BidirectionalPair<types::CisType> cis_types = {
        .sink = types::CisType::CIS_TYPE_UNIDIRECTIONAL_SINK,
        .source = types::CisType::CIS_TYPE_UNIDIRECTIONAL_SOURCE};
    auto cis_type = cis_types.get(direction);

    for (auto const& cis_entry : cises) {
      if ((cis_entry.type == types::CisType::CIS_TYPE_BIDIRECTIONAL ||
           cis_entry.type == cis_type) &&
          cis_entry.conn_handle != 0) {
        uint32_t target_allocation = 0;
        uint32_t current_allocation = 0;
        bool is_active = false;
        for (const auto& s : stream_params.stream_locations) {
          if (s.first == cis_entry.conn_handle) {
            is_active = true;
            target_allocation = AdjustAllocationForOffloader(s.second);
            current_allocation = target_allocation;
            if (!all_cises_connected) {
              /* Tell offloader to mix on this CIS.*/
              current_allocation = codec_spec_conf::kLeAudioLocationStereo;
            }
            break;
          }
        }

        if (target_allocation == 0) {
          /* Take missing allocation for that one .*/
          target_allocation =
              codec_spec_conf::kLeAudioLocationStereo & ~available_allocations;
        }

        log::info(
            "{}: Cis handle 0x{:04x}, target allocation  0x{:08x}, current "
            "allocation 0x{:08x}, active: {}",
            tag, cis_entry.conn_handle, target_allocation, current_allocation,
            is_active);

        if (stream_map.is_initial ||
            LeAudioHalVerifier::SupportsStreamActiveApi()) {
          stream_map.streams_map_target.emplace_back(stream_map_info(
              cis_entry.conn_handle, target_allocation, is_active));
        }
        stream_map.streams_map_current.emplace_back(stream_map_info(
            cis_entry.conn_handle, current_allocation, is_active));
      }
    }
  }

 private:
  void SetCodecLocation(CodecLocation location) {
    if (offload_enable_ == false) return;
    codec_location_ = location;
  }

  bool IsLc3ConfigMatched(
      const set_configurations::CodecConfigSetting& target_config,
      const set_configurations::CodecConfigSetting& adsp_config) {
    if (adsp_config.id.coding_format != types::kLeAudioCodingFormatLC3 ||
        target_config.id.coding_format != types::kLeAudioCodingFormatLC3) {
      return false;
    }

    const types::LeAudioCoreCodecConfig adsp_lc3_config =
        adsp_config.params.GetAsCoreCodecConfig();
    const types::LeAudioCoreCodecConfig target_lc3_config =
        target_config.params.GetAsCoreCodecConfig();

    if (adsp_lc3_config.sampling_frequency !=
            target_lc3_config.sampling_frequency ||
        adsp_lc3_config.frame_duration != target_lc3_config.frame_duration ||
        adsp_config.GetChannelCountPerIsoStream() !=
            target_config.GetChannelCountPerIsoStream() ||
        adsp_lc3_config.octets_per_codec_frame !=
            target_lc3_config.octets_per_codec_frame) {
      return false;
    }

    return true;
  }

  bool IsAseConfigurationMatched(const AseConfiguration& software_ase_config,
                                 const AseConfiguration& adsp_ase_config) {
    // Skip the check of strategy due to ADSP doesn't have the info
    return IsLc3ConfigMatched(software_ase_config.codec, adsp_ase_config.codec);
  }

  bool IsAudioSetConfigurationMatched(
      const AudioSetConfiguration* software_audio_set_conf,
      std::unordered_set<uint8_t>& offload_preference_set,
      const std::vector<AudioSetConfiguration>& adsp_capabilities) {
    if (software_audio_set_conf->confs.sink.empty() &&
        software_audio_set_conf->confs.source.empty()) {
      return false;
    }

    // No match if the codec is not on the preference list
    for (auto direction : {le_audio::types::kLeAudioDirectionSink,
                           le_audio::types::kLeAudioDirectionSource}) {
      for (auto const& conf : software_audio_set_conf->confs.get(direction)) {
        if (offload_preference_set.find(conf.codec.id.coding_format) ==
            offload_preference_set.end()) {
          return false;
        }
      }
    }

    // Checks any of offload config matches the input audio set config
    for (const auto& adsp_audio_set_conf : adsp_capabilities) {
      size_t match_cnt = 0;
      size_t expected_match_cnt = 0;

      for (auto direction : {le_audio::types::kLeAudioDirectionSink,
                             le_audio::types::kLeAudioDirectionSource}) {
        auto const& software_set_ase_confs =
            software_audio_set_conf->confs.get(direction);
        auto const& adsp_set_ase_confs =
            adsp_audio_set_conf.confs.get(direction);

        if (!software_set_ase_confs.size() || !adsp_set_ase_confs.size()) {
          continue;
        }

        // Check for number of ASEs mismatch
        if (adsp_set_ase_confs.size() != software_set_ase_confs.size()) {
          log::error(
              "{}: ADSP config size mismatches the software: {} != {}",
              direction == types::kLeAudioDirectionSink ? "Sink" : "Source",
              adsp_set_ase_confs.size(), software_set_ase_confs.size());
          continue;
        }

        // The expected number of ASE configs, the ADSP config needs to match
        expected_match_cnt += software_set_ase_confs.size();
        if (expected_match_cnt == 0) {
          continue;
        }

        // Check for matching configs
        for (auto const& adsp_set_conf : adsp_set_ase_confs) {
          for (auto const& software_set_conf : software_set_ase_confs) {
            if (IsAseConfigurationMatched(software_set_conf, adsp_set_conf)) {
              match_cnt++;
              // Check the next adsp config if the first software config matches
              break;
            }
          }
        }
        if (match_cnt != expected_match_cnt) {
          break;
        }
      }

      // Check the match count
      if (match_cnt == expected_match_cnt) {
        return true;
      }
    }

    return false;
  }

  std::string getStrategyString(types::LeAudioConfigurationStrategy strategy) {
    switch (strategy) {
      case types::LeAudioConfigurationStrategy::MONO_ONE_CIS_PER_DEVICE:
        return "MONO_ONE_CIS_PER_DEVICE";
      case types::LeAudioConfigurationStrategy::STEREO_TWO_CISES_PER_DEVICE:
        return "STEREO_TWO_CISES_PER_DEVICE";
      case types::LeAudioConfigurationStrategy::STEREO_ONE_CIS_PER_DEVICE:
        return "STEREO_ONE_CIS_PER_DEVICE";
      default:
        return "RFU";
    }
  }

  uint8_t sampleFreqToBluetoothSigBitMask(int sample_freq) {
    switch (sample_freq) {
      case 8000:
        return bluetooth::le_audio::codec_spec_caps::kLeAudioSamplingFreq8000Hz;
      case 16000:
        return bluetooth::le_audio::codec_spec_caps::
            kLeAudioSamplingFreq16000Hz;
      case 24000:
        return bluetooth::le_audio::codec_spec_caps::
            kLeAudioSamplingFreq24000Hz;
      case 32000:
        return bluetooth::le_audio::codec_spec_caps::
            kLeAudioSamplingFreq32000Hz;
      case 44100:
        return bluetooth::le_audio::codec_spec_caps::
            kLeAudioSamplingFreq44100Hz;
      case 48000:
        return bluetooth::le_audio::codec_spec_caps::
            kLeAudioSamplingFreq48000Hz;
    }
    return bluetooth::le_audio::codec_spec_caps::kLeAudioSamplingFreq8000Hz;
  }

  void storeLocalCapa(
      std::vector<
          ::bluetooth::le_audio::set_configurations::AudioSetConfiguration>&
          adsp_capabilities,
      const std::vector<btle_audio_codec_config_t>& offload_preference_set) {
    log::debug("Print adsp_capabilities:");

    for (auto& adsp : adsp_capabilities) {
      log::debug("'{}':", adsp.name);
      for (auto direction : {le_audio::types::kLeAudioDirectionSink,
                             le_audio::types::kLeAudioDirectionSource}) {
        log::debug(
            "dir: {}: number of confs {}:",
            direction == types::kLeAudioDirectionSink ? "sink" : "source",
            (int)(adsp.confs.get(direction).size()));
        for (auto conf : adsp.confs.sink) {
          log::debug(
              "codecId: {}, sample_freq: {}, interval {}, channel_cnt: {}",
              conf.codec.id.coding_format, conf.codec.GetSamplingFrequencyHz(),
              conf.codec.GetDataIntervalUs(),
              conf.codec.GetChannelCountPerIsoStream());

          /* TODO: How to get bits_per_sample ? */
          btle_audio_codec_config_t capa_to_add = {
              .codec_type = (conf.codec.id.coding_format ==
                             types::kLeAudioCodingFormatLC3)
                                ? btle_audio_codec_index_t::
                                      LE_AUDIO_CODEC_INDEX_SOURCE_LC3
                                : btle_audio_codec_index_t::
                                      LE_AUDIO_CODEC_INDEX_SOURCE_INVALID,
              .sample_rate = utils::translateToBtLeAudioCodecConfigSampleRate(
                  conf.codec.GetSamplingFrequencyHz()),
              .bits_per_sample =
                  utils::translateToBtLeAudioCodecConfigBitPerSample(16),
              .channel_count =
                  utils::translateToBtLeAudioCodecConfigChannelCount(
                      conf.codec.GetChannelCountPerIsoStream()),
              .frame_duration =
                  utils::translateToBtLeAudioCodecConfigFrameDuration(
                      conf.codec.GetDataIntervalUs()),
          };

          auto& capa_container = (direction == types::kLeAudioDirectionSink)
                                     ? codec_output_capa
                                     : codec_input_capa;
          if (std::find(capa_container.begin(), capa_container.end(),
                        capa_to_add) == capa_container.end()) {
            log::debug("Adding {} capa {}",
                       (direction == types::kLeAudioDirectionSink) ? "output"
                                                                   : "input",
                       static_cast<int>(capa_container.size()));
            capa_container.push_back(capa_to_add);
          }
        }
      }
    }

    log::debug("Output capa: {}, Input capa: {}",
               static_cast<int>(codec_output_capa.size()),
               static_cast<int>(codec_input_capa.size()));

    log::debug("Print offload_preference_set: {}",
               (int)(offload_preference_set.size()));

    int i = 0;
    for (auto set : offload_preference_set) {
      log::debug("set {}, {}", i++, set.ToString());
    }
  }

  void UpdateOffloadCapability(
      const std::vector<btle_audio_codec_config_t>& offloading_preference) {
    log::info("");
    std::unordered_set<uint8_t> offload_preference_set;

    if (AudioSetConfigurationProvider::Get() == nullptr) {
      log::error("Audio set configuration provider is not available.");
      return;
    }

    auto adsp_capabilities =
        ::bluetooth::audio::le_audio::get_offload_capabilities();

    storeLocalCapa(adsp_capabilities.unicast_offload_capabilities,
                   offloading_preference);

    for (auto codec : offloading_preference) {
      auto it = btle_audio_codec_type_map_.find(codec.codec_type);

      if (it != btle_audio_codec_type_map_.end()) {
        offload_preference_set.insert(it->second);
      }
    }

    for (types::LeAudioContextType ctx_type :
         types::kLeAudioContextAllTypesArray) {
      // Gets the software supported context type and the corresponding config
      // priority
      const AudioSetConfigurations* software_audio_set_confs =
          AudioSetConfigurationProvider::Get()->GetConfigurations(ctx_type);

      for (const auto& software_audio_set_conf : *software_audio_set_confs) {
        if (IsAudioSetConfigurationMatched(
                software_audio_set_conf, offload_preference_set,
                adsp_capabilities.unicast_offload_capabilities)) {
          log::info("Offload supported conf, context type: {}, settings -> {}",
                    (int)ctx_type, software_audio_set_conf->name);
          if (dual_bidirection_swb_supported_ &&
              AudioSetConfigurationProvider::Get()
                  ->CheckConfigurationIsDualBiDirSwb(
                      *software_audio_set_conf)) {
            offload_dual_bidirection_swb_supported_ = true;
          }
          context_type_offload_config_map_[ctx_type].push_back(
              software_audio_set_conf);
        }
      }
    }
    UpdateSupportedBroadcastConfig(
        adsp_capabilities.broadcast_offload_capabilities);
  }

  CodecLocation codec_location_ = CodecLocation::HOST;
  bool offload_enable_ = false;
  bool offload_dual_bidirection_swb_supported_ = false;
  bool dual_bidirection_swb_supported_ = false;
  types::BidirectionalPair<offloader_stream_maps_t> offloader_stream_maps;
  std::vector<bluetooth::le_audio::broadcast_offload_config>
      supported_broadcast_config;
  std::unordered_map<types::LeAudioContextType, AudioSetConfigurations>
      context_type_offload_config_map_;
  std::unordered_map<btle_audio_codec_index_t, uint8_t>
      btle_audio_codec_type_map_ = {
          {::bluetooth::le_audio::LE_AUDIO_CODEC_INDEX_SOURCE_LC3,
           types::kLeAudioCodingFormatLC3}};

  std::vector<btle_audio_codec_config_t> codec_input_capa = {};
  std::vector<btle_audio_codec_config_t> codec_output_capa = {};
  int broadcast_target_config = -1;

  LeAudioSourceAudioHalClient* unicast_local_source_hal_client = nullptr;
  LeAudioSinkAudioHalClient* unicast_local_sink_hal_client = nullptr;
  LeAudioSourceAudioHalClient* broadcast_local_source_hal_client = nullptr;
};

std::ostream& operator<<(
    std::ostream& os,
    const CodecManager::UnicastConfigurationRequirements& req) {
  os << "{audio context type: " << req.audio_context_type << "}";
  return os;
}

struct CodecManager::impl {
  impl(const CodecManager& codec_manager) : codec_manager_(codec_manager) {}

  void Start(
      const std::vector<btle_audio_codec_config_t>& offloading_preference) {
    log::assert_that(!codec_manager_impl_,
                     "assert failed: !codec_manager_impl_");
    codec_manager_impl_ = std::make_unique<codec_manager_impl>();
    codec_manager_impl_->start(offloading_preference);
  }

  void Stop() {
    log::assert_that(codec_manager_impl_ != nullptr,
                     "assert failed: codec_manager_impl_ != nullptr");
    codec_manager_impl_.reset();
  }

  bool IsRunning() { return codec_manager_impl_ ? true : false; }

  const CodecManager& codec_manager_;
  std::unique_ptr<codec_manager_impl> codec_manager_impl_;
};

CodecManager::CodecManager() : pimpl_(std::make_unique<impl>(*this)) {}

void CodecManager::Start(
    const std::vector<btle_audio_codec_config_t>& offloading_preference) {
  if (!pimpl_->IsRunning()) pimpl_->Start(offloading_preference);
}

void CodecManager::Stop() {
  if (pimpl_->IsRunning()) pimpl_->Stop();
}

types::CodecLocation CodecManager::GetCodecLocation(void) const {
  if (!pimpl_->IsRunning()) {
    return CodecLocation::HOST;
  }

  return pimpl_->codec_manager_impl_->GetCodecLocation();
}

bool CodecManager::IsDualBiDirSwbSupported(void) const {
  if (!pimpl_->IsRunning()) {
    return false;
  }

  return pimpl_->codec_manager_impl_->IsDualBiDirSwbSupported();
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioOutputCodecCapa() {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->GetLocalAudioOutputCodecCapa();
  }

  std::vector<bluetooth::le_audio::btle_audio_codec_config_t> empty{};
  return empty;
}

std::vector<bluetooth::le_audio::btle_audio_codec_config_t>
CodecManager::GetLocalAudioInputCodecCapa() {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->GetLocalAudioOutputCodecCapa();
  }
  std::vector<bluetooth::le_audio::btle_audio_codec_config_t> empty{};
  return empty;
}

void CodecManager::UpdateActiveAudioConfig(
    const types::BidirectionalPair<stream_parameters>& stream_params,
    types::BidirectionalPair<uint16_t> delays_ms,
    std::function<void(const offload_config& config, uint8_t direction)>
        update_receiver) {
  if (pimpl_->IsRunning())
    pimpl_->codec_manager_impl_->UpdateActiveAudioConfig(
        stream_params, delays_ms, update_receiver);
}

bool CodecManager::UpdateActiveUnicastAudioHalClient(
    LeAudioSourceAudioHalClient* source_unicast_client,
    LeAudioSinkAudioHalClient* sink_unicast_client, bool is_active) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->UpdateActiveUnicastAudioHalClient(
        source_unicast_client, sink_unicast_client, is_active);
  }
  return false;
}

bool CodecManager::UpdateActiveBroadcastAudioHalClient(
    LeAudioSourceAudioHalClient* source_broadcast_client, bool is_active) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->UpdateActiveBroadcastAudioHalClient(
        source_broadcast_client, is_active);
  }
  return false;
}

std::unique_ptr<AudioSetConfiguration> CodecManager::GetCodecConfig(
    const CodecManager::UnicastConfigurationRequirements& requirements,
    CodecManager::UnicastConfigurationVerifier verifier) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->GetCodecConfig(requirements, verifier);
  }

  return nullptr;
}

bool CodecManager::CheckCodecConfigIsBiDirSwb(
    const set_configurations::AudioSetConfiguration& config) const {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->CheckCodecConfigIsBiDirSwb(config);
  }
  return false;
}

bool CodecManager::CheckCodecConfigIsDualBiDirSwb(
    const set_configurations::AudioSetConfiguration& config) const {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->CheckCodecConfigIsDualBiDirSwb(config);
  }
  return false;
}

std::unique_ptr<broadcaster::BroadcastConfiguration>
CodecManager::GetBroadcastConfig(
    const CodecManager::BroadcastConfigurationRequirements& requirements)
    const {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->GetBroadcastConfig(requirements);
  }

  return nullptr;
}

void CodecManager::UpdateBroadcastConnHandle(
    const std::vector<uint16_t>& conn_handle,
    std::function<
        void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
        update_receiver) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->UpdateBroadcastConnHandle(
        conn_handle, update_receiver);
  }
}

void CodecManager::UpdateCisConfiguration(
    const std::vector<struct types::cis>& cises,
    const stream_parameters& stream_params, uint8_t direction) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->UpdateCisConfiguration(
        cises, stream_params, direction);
  }
}

void CodecManager::ClearCisConfiguration(uint8_t direction) {
  if (pimpl_->IsRunning()) {
    return pimpl_->codec_manager_impl_->ClearCisConfiguration(direction);
  }
}

}  // namespace bluetooth::le_audio
