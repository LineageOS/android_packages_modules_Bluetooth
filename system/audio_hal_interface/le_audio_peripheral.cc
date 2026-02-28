/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "audio_hal_interface/le_audio_peripheral.h"

#include <bluetooth/log.h>

#include "bta/le_audio/le_audio_types.h"
#include "bta/le_audio/le_audio_utils.h"
#include "le_audio_software.h"

namespace bluetooth::audio::le_audio {

namespace {
using namespace bluetooth::le_audio::codec_spec_caps;
using namespace bluetooth::le_audio::types;

static void EndpointConfigResponseLegacyBuilder(const std::vector<endpoint_config_req>& requests,
                                                endpoint_config_rsp& result) {
  // TODO: b/480075117 - Remove once HAL adapter is ready
  for (const auto& request : requests) {
    log::debug("ASE: {}", request.ase_id);

    std::variant_alternative_t<0, endpoint_config_rsp::value_type::second_type> ase_configured;
    ase_configured.codec_id = request.codec_configuration.codec_id;
    ase_configured.codec_spec_conf = request.codec_configuration.codec_spec_conf;

    // Use the client's preferred PHY
    ase_configured.preferred_phy = bluetooth::le_audio::utils::GetPreferredPhyFromTargetPhy(
            request.codec_configuration.target_phy);

    ase_configured.preferred_retrans_nb = 13;
    ase_configured.framing = 0x00;

    // Select Max_Transport_Latency based on the client's Target_Latency hint.
    // These values are chosen based on BAP v1.0.1, Table 5.2 examples.
    switch (request.codec_configuration.target_latency) {
      case bluetooth::le_audio::types::kTargetLatencyLower:
        ase_configured.max_transport_latency = 10;  // 10ms for low latency
        ase_configured.preferred_retrans_nb = 2;
        break;
      case bluetooth::le_audio::types::kTargetLatencyHigherReliability:
        ase_configured.max_transport_latency = 100;  // 100ms for high reliability
        ase_configured.preferred_retrans_nb = 13;
        break;
      case bluetooth::le_audio::types::kTargetLatencyBalancedLatencyReliability:
      default:
        ase_configured.max_transport_latency = 40;  // 40ms for balanced
        ase_configured.preferred_retrans_nb = 5;
        break;
    }

    log::info("Selected max_transport_latency: {}ms for target_latency: {:#x}",
              ase_configured.max_transport_latency, request.codec_configuration.target_latency);
    log::info("Selected preferred_retrans_nb: {} for target_latency: {:#x}",
              ase_configured.preferred_retrans_nb, request.codec_configuration.target_latency);

    /* The value of the Presentation_Delay parameter includes any implementation-specific
     * delays in the Unicast Server and/or Broadcast Sink, such as processing time for
     * internal transports, codec processing, ADC/DAC delays, application-specific audio
     * processing, etc. A Unicast Server that implements its codec within its Bluetooth
     * Controller shall ensure that the values exposed for the Presentation_Delay_Min and
     * Presentation_Delay_Max fields accommodate the values of the Min_Controller_Delay and
     * Max_Controller_Delay parameters as defined in Volume 4, Part E, Section 7.4.11 in [1].
     * Presentation refers to the time at which the audio signal passes through an
     * electroacoustic transducer to or from the user, or the time at which the audio signal
     * passes through an interface to another system external to the Bluetooth system on a
     * device.
     */
    ase_configured.pres_delay_min = 40000;
    ase_configured.pres_delay_max = 40000;
    ase_configured.preferred_pres_delay_min = bluetooth::le_audio::types::kPresDelayNoPreference;
    ase_configured.preferred_pres_delay_max = bluetooth::le_audio::types::kPresDelayNoPreference;

    // Push a static config result
    result.insert_or_assign(request.ase_id, ase_configured);
  }
}

/*
 * Private adapter implementation that routes calls from the new peripheral
 * interface to the legacy, central-role focused LeAudioClientInterface.
 */
class PeripheralAudioOutLegacyAdapter : public IPeripheralAudioOut {
public:
  PeripheralAudioOutLegacyAdapter(const PeripheralStreamCallbacks& callbacks,
                                  common::MessageLoopThread* message_loop) {
    // Translate our new peripheral callbacks to the legacy StreamCallbacks
    legacy_callbacks_.on_resume_ = [cb_on_resume = callbacks.OnStartRequest](bool) -> bool {
      if (cb_on_resume) {
        cb_on_resume();
      }
      return true;
    };
    legacy_callbacks_.on_suspend_ = [cb_on_suspend = callbacks.OnSuspendRequest]() -> bool {
      if (cb_on_suspend) {
        cb_on_suspend();
      }
      return true;
    };
    legacy_callbacks_.on_metadata_update_ = [cb_on_meta = callbacks.OnPlaybackMetadataUpdate](
                                                    const source_metadata_v7_t& metadata,
                                                    DsaMode) -> bool {
      if (cb_on_meta) {
        cb_on_meta(metadata);
      }
      return true;
    };

    legacy_interface_ = LeAudioClientInterface::Get();
    if (legacy_interface_) {
      legacy_source_ = legacy_interface_->GetSource(legacy_callbacks_, message_loop);
    } else {
      log::error("Could not get LeAudioClientInterface");
    }
  }

  ~PeripheralAudioOutLegacyAdapter() override {
    if (legacy_interface_ && legacy_source_) {
      legacy_interface_->ReleaseSource(legacy_source_);
    }
  }

  size_t Write(const uint8_t* buffer, uint32_t bytes) override {
    if (!legacy_source_) {
      return 0;
    }
    return legacy_source_->Write(buffer, bytes);
  }

  void Start() override {
    if (legacy_source_) {
      legacy_source_->StartSession();
    }
  }

  void Stop() override {
    if (legacy_source_) {
      legacy_source_->StopSession();
    }
  }

  void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) override {
    if (legacy_source_) {
      legacy_source_->UpdateAudioConfigToHal(config);
    }
  }

  void ConfirmStreamingRequest() override {
    if (legacy_source_) {
      legacy_source_->ConfirmStreamingRequest();
    }
  }

  void CancelStreamingRequest() override {
    if (legacy_source_) {
      legacy_source_->CancelStreamingRequest();
    }
  }

  endpoint_config_rsp RequestAseConfigurations(
          const std::vector<endpoint_config_req>& sink_configs,
          const std::vector<endpoint_config_req>& source_configs) const override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    endpoint_config_rsp result;
    EndpointConfigResponseLegacyBuilder(sink_configs, result);
    EndpointConfigResponseLegacyBuilder(source_configs, result);

    return result;
  }

private:
  LeAudioClientInterface* legacy_interface_ = nullptr;
  LeAudioClientInterface::Source* legacy_source_ = nullptr;
  StreamCallbacks legacy_callbacks_;
};

/*
 * Private adapter implementation for the recording path.
 */
class PeripheralAudioInLegacyAdapter : public IPeripheralAudioIn {
public:
  PeripheralAudioInLegacyAdapter(const PeripheralStreamCallbacks& callbacks,
                                 common::MessageLoopThread* message_loop) {
    legacy_callbacks_.on_resume_ = [cb_on_resume = callbacks.OnStartRequest](bool) -> bool {
      if (cb_on_resume) {
        cb_on_resume();
      }
      return true;
    };
    legacy_callbacks_.on_suspend_ = [cb_on_suspend = callbacks.OnSuspendRequest]() -> bool {
      if (cb_on_suspend) {
        cb_on_suspend();
      }
      return true;
    };
    legacy_callbacks_.on_sink_metadata_update_ =
            [cb_on_meta = callbacks.OnRecordingMetadataUpdate](
                    const sink_metadata_v7_t& metadata) -> bool {
      if (cb_on_meta) {
        cb_on_meta(metadata);
      }
      return true;
    };

    legacy_interface_ = LeAudioClientInterface::Get();
    if (legacy_interface_) {
      // The peripheral role is for unicast
      legacy_sink_ = legacy_interface_->GetSink(legacy_callbacks_, message_loop,
                                                false /* is_broadcasting */);
    } else {
      log::error("Could not get LeAudioClientInterface");
    }
  }

  ~PeripheralAudioInLegacyAdapter() override {
    if (legacy_interface_ && legacy_sink_) {
      legacy_interface_->ReleaseSink(legacy_sink_);
    }
  }

  size_t Read(uint8_t* buffer, uint32_t bytes) override {
    if (!legacy_sink_) {
      return 0;
    }
    return legacy_sink_->Read(buffer, bytes);
  }

  void Start() override {
    if (legacy_sink_) {
      legacy_sink_->StartSession();
    }
  }

  void Stop() override {
    if (legacy_sink_) {
      legacy_sink_->StopSession();
    }
  }

  void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) override {
    if (legacy_sink_) {
      legacy_sink_->UpdateAudioConfigToHal(config);
    }
  }

  void ConfirmStreamingRequest() override {
    if (legacy_sink_) {
      legacy_sink_->ConfirmStreamingRequest();
    }
  }

  void CancelStreamingRequest() override {
    if (legacy_sink_) {
      legacy_sink_->CancelStreamingRequest();
    }
  }

  endpoint_config_rsp RequestAseConfigurations(
          const std::vector<endpoint_config_req>& sink_configs,
          const std::vector<endpoint_config_req>& source_configs) const override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    endpoint_config_rsp result;
    EndpointConfigResponseLegacyBuilder(sink_configs, result);
    EndpointConfigResponseLegacyBuilder(source_configs, result);

    return result;
  }

private:
  LeAudioClientInterface* legacy_interface_ = nullptr;
  LeAudioClientInterface::Sink* legacy_sink_ = nullptr;
  StreamCallbacks legacy_callbacks_;
};
}  // namespace

class LegacyAudioProviderImpl : public IPeripheralAudioProvider {
public:
  LegacyAudioProviderImpl(bool is_playback) : is_playback_(is_playback) {}
  ~LegacyAudioProviderImpl() override = default;

  std::vector<AudioHalCapability> GetProviderCapabilities() override {
    // This is a helper map to convert sample rate in hertz to a BAP-defined enum
    const std::map<uint32_t, uint16_t> sample_rate_map = {
            {8000, kLeAudioSamplingFreq8000Hz},
            {11025, kLeAudioSamplingFreq11025Hz},
            {16000, kLeAudioSamplingFreq16000Hz},
            {22050, kLeAudioSamplingFreq22050Hz},
            {24000, kLeAudioSamplingFreq24000Hz},
            {32000, kLeAudioSamplingFreq32000Hz},
            {44100, kLeAudioSamplingFreq44100Hz},
            {48000, kLeAudioSamplingFreq48000Hz},
            {88200, kLeAudioSamplingFreq88200Hz},
            {96000, kLeAudioSamplingFreq96000Hz},
            {176400, kLeAudioSamplingFreq176400Hz},
            {192000, kLeAudioSamplingFreq192000Hz},
            {384000, kLeAudioSamplingFreq384000Hz},
    };

    // This is a helper map to convert frame duration in us to a BAP-defined enum
    const std::map<uint32_t, uint8_t> frame_duration_map = {
            {7500, kLeAudioCodecFrameDur7500us},
            {10000, kLeAudioCodecFrameDur10000us},
    };

    std::vector<AudioHalCapability> capabilities;
    auto* interface = LeAudioClientInterface::Get();
    if (!interface) {
      log::error("Failed to get LeAudioClientInterface");
      return capabilities;
    }

    auto provider_info = interface->GetCodecConfigProviderInfo();
    if (!provider_info) {
      log::error("Failed to get codec provider info");
      return capabilities;
    }

    const auto& codec_configs = is_playback_ ? provider_info->encoding_codec_configs
                                             : provider_info->decoding_codec_configs;

    struct AggregatedCaps {
      uint16_t supported_sampling_frequencies = 0;
      uint8_t supported_frame_durations = 0;
      uint8_t supported_audio_channel_counts = 0;
    };
    std::map<LeAudioCodecId, AggregatedCaps> aggregated_capabilities;

    for (const auto& codec_info : codec_configs) {
      if (codec_info.supported_configs.empty()) {
        continue;
      }

      LeAudioCodecId codec_id(codec_info.codec_id.coding_format,
                              codec_info.codec_id.vendor_company_id,
                              codec_info.codec_id.vendor_codec_id);

      auto& caps = aggregated_capabilities[codec_id];

      for (const auto& config : codec_info.supported_configs) {
        if (sample_rate_map.count(config.sample_freq)) {
          caps.supported_sampling_frequencies |= sample_rate_map.at(config.sample_freq);
        }
        if (frame_duration_map.count(config.frame_duration)) {
          caps.supported_frame_durations |= frame_duration_map.at(config.frame_duration);
        }
        if (config.channel_count > 0 && config.channel_count <= 8) {
          caps.supported_audio_channel_counts |= (1 << (config.channel_count - 1));
        }
      }
    }

    // Convert the aggregate type to a raw BAP compliant capability buffer
    for (const auto& [codec_id, caps] : aggregated_capabilities) {
      LeAudioLtvMap spec_caps_ltv;
      if (caps.supported_sampling_frequencies) {
        spec_caps_ltv.Add(kLeAudioLtvTypeSupportedSamplingFrequencies,
                          caps.supported_sampling_frequencies);
      }
      if (caps.supported_frame_durations) {
        spec_caps_ltv.Add(kLeAudioLtvTypeSupportedFrameDurations, caps.supported_frame_durations);
      }
      if (caps.supported_audio_channel_counts) {
        spec_caps_ltv.Add(kLeAudioLtvTypeSupportedAudioChannelCounts,
                          caps.supported_audio_channel_counts);
      }

      // Note: This one is absent in the legeacy HAL interface
      spec_caps_ltv.Add(kLeAudioLtvTypeSupportedOctetsPerCodecFrame,
                        std::vector<uint8_t>{static_cast<uint8_t>(kLeAudioCodecFrameLen30 >> 0),
                                             static_cast<uint8_t>(kLeAudioCodecFrameLen30 >> 8),
                                             static_cast<uint8_t>(kLeAudioCodecFrameLen120 >> 0),
                                             static_cast<uint8_t>(kLeAudioCodecFrameLen120 >> 8)});

      AudioHalCapability cap = {
              .coding_format = codec_id.coding_format,
              .vendor_company_id = codec_id.vendor_company_id,
              .vendor_codec_id = codec_id.vendor_codec_id,
              .codec_spec_caps = spec_caps_ltv.RawPacket(),
              .metadata = {}, /* ProviderInfo does not provide metadata */
      };
      capabilities.push_back(std::move(cap));
    }

    return capabilities;
  }

private:
  bool is_playback_;
};

class PeripheralAudioProviderFactoryImpl : public IPeripheralAudioProviderFactory {
public:
  std::unique_ptr<IPeripheralAudioProvider> GetPlaybackSessionAudioProvider() override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    return std::make_unique<LegacyAudioProviderImpl>(true);
  }

  std::unique_ptr<IPeripheralAudioProvider> GetRecordingSessionAudioProvider() override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    return std::make_unique<LegacyAudioProviderImpl>(false);
  }
};

static PeripheralAudioProviderFactoryImpl* provider_instance = nullptr;

IPeripheralAudioProviderFactory* IPeripheralAudioProviderFactory::Get() {
  if (provider_instance == nullptr) {
    provider_instance = new PeripheralAudioProviderFactoryImpl();
  }
  return provider_instance;
}

class PeripheralAudioSessionFactoryImpl : public IPeripheralAudioSessionFactory {
public:
  std::unique_ptr<IPeripheralAudioOut> AcquirePlaybackSession(
          const PeripheralStreamCallbacks& callbacks,
          common::MessageLoopThread* message_loop) override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    return std::make_unique<PeripheralAudioOutLegacyAdapter>(callbacks, message_loop);
  }

  void ReleasePlaybackSession(std::unique_ptr<IPeripheralAudioOut> /*session*/) override {
    // Let unique_ptr go out of scope
  }

  std::unique_ptr<IPeripheralAudioIn> AcquireRecordingSession(
          const PeripheralStreamCallbacks& callbacks,
          common::MessageLoopThread* message_loop) override {
    // TODO: b/480075117 - Update once HAL adapter is ready
    return std::make_unique<PeripheralAudioInLegacyAdapter>(callbacks, message_loop);
  }

  void ReleaseRecordingSession(std::unique_ptr<IPeripheralAudioIn> /*session*/) override {
    // Let unique_ptr go out of scope
  }
};

static PeripheralAudioSessionFactoryImpl* instance = nullptr;

IPeripheralAudioSessionFactory* IPeripheralAudioSessionFactory::Get() {
  if (instance == nullptr) {
    instance = new PeripheralAudioSessionFactoryImpl();
  }
  return instance;
}

}  // namespace bluetooth::audio::le_audio
