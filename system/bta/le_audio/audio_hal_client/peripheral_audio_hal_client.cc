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

#include "peripheral_audio_hal_client.h"

#include <base/memory/weak_ptr.h>

#include "audio_hal_interface/le_audio_peripheral.h"
#include "common/message_loop_thread.h"

using namespace bluetooth::bta::le_audio;
namespace audio_le_audio = bluetooth::audio::le_audio;

class HalStreamManager {
  /* The Bluetooth stack doesn't have a way to negotiate the PCM sample bit depth for the LE Audio
     Peripheral role. Since there is no dedicated API to configure this, we use the same default
     value as the LE Audio Central role for consistency.*/
  static const uint8_t kBitsPerSample = 16;

public:
  HalStreamManager() = default;

  /* Update the local stream configuration map cache */
  void OnAudioChannelParametersChanged(RawAddress pseudo_address, uint16_t cis_conn_handle,
                                       const audio_channel_info& channel_info) {
    // TODO(b/480075117): Get these parameters via the HAL's new translation API
    ::bluetooth::le_audio::types::LeAudioLtvMap translated_codec_params;
    translated_codec_params.Parse(channel_info.codec_config.data(),
                                  channel_info.codec_config.size());
    auto core_codec_conf = translated_codec_params.GetAsCoreCodecConfig();

    stream_config_.sampling_frequency_hz = core_codec_conf.GetSamplingFrequencyHz();
    stream_config_.frame_duration_us = core_codec_conf.GetFrameDurationUs();
    stream_config_.octets_per_codec_frame = core_codec_conf.GetOctetsPerFrame();
    stream_config_.codec_frames_blocks_per_sdu = core_codec_conf.GetCodecFrameBlocksPerSdu();
    stream_config_.bits_per_sample = kBitsPerSample;
    stream_config_.peer_delay_ms = channel_info.pres_delay / 1000;

    ::bluetooth::le_audio::stream_map_info map_info(
            cis_conn_handle, core_codec_conf.GetAudioChannelAllocation(), true);

    map_info.codec_config = ::bluetooth::le_audio::types::CodecConfigSetting({
            .id = channel_info.codec_id,
            .params = translated_codec_params,
            .vendor_params = channel_info.codec_config,
            .channel_count_per_iso_stream = static_cast<uint8_t>(
                    std::bitset<32>(core_codec_conf.GetAudioChannelAllocation()).count()),
    });
    map_info.target_phy = channel_info.target_phy;
    map_info.target_latency = channel_info.target_latency;

    ::bluetooth::le_audio::types::LeAudioLtvMap metadata_ltv;
    if (metadata_ltv.Parse(channel_info.metadata.data(), channel_info.metadata.size())) {
      map_info.metadata = metadata_ltv;
    }

    if (std::find(streaming_devices_.begin(), streaming_devices_.end(), pseudo_address) ==
        streaming_devices_.end()) {
      streaming_devices_.push_back(pseudo_address);
    }
    map_info.address = channel_info.address_with_type.bda;
    map_info.address_type = channel_info.address_with_type.type;

    auto stream_map_entry =
            std::find_if(stream_config_.stream_map.begin(), stream_config_.stream_map.end(),
                         [&cis_conn_handle](auto const& map_entry) {
                           return map_entry.stream_handle == cis_conn_handle;
                         });
    if (stream_map_entry != stream_config_.stream_map.end()) {
      *stream_map_entry = map_info;
    } else {
      stream_config_.stream_map.push_back(map_info);
    }
  }

  void OnAudioChannelRemoved(RawAddress pseudo_address, uint16_t cis_conn_handle) {
    stream_config_.stream_map.erase(
            std::find_if(stream_config_.stream_map.begin(), stream_config_.stream_map.end(),
                         [&cis_conn_handle](auto const& map_entry) {
                           return map_entry.stream_handle == cis_conn_handle;
                         }));
    streaming_devices_.erase(
            std::remove(streaming_devices_.begin(), streaming_devices_.end(), pseudo_address),
            streaming_devices_.end());
  }

  const ::bluetooth::le_audio::stream_config& GetConfig() const { return stream_config_; }

  const std::vector<RawAddress>& GetStreamingDevices() const { return streaming_devices_; }

  bool IsEmpty() const { return stream_config_.stream_map.empty(); }

private:
  ::bluetooth::le_audio::stream_config stream_config_;
  std::vector<RawAddress> streaming_devices_;
};

/*
 * Decoder (Playback) Wrapper
 */
class PeripheralAudioHalDecoder::impl {
public:
  impl(const audio_le_audio::PeripheralStreamCallbacks& callbacks,
       bluetooth::common::MessageLoopThread* message_loop,
       audio_le_audio::IPeripheralAudioSessionFactory* factory)
      : factory_(factory) {
    if (factory_ == nullptr) {
      factory_ = audio_le_audio::IPeripheralAudioSessionFactory::Get();
    }

    session_ = factory_->AcquirePlaybackSession(callbacks, message_loop);
  }

  ~impl() {
    if (session_) {
      factory_->ReleasePlaybackSession(std::move(session_));
    }
  }

  size_t Write(const uint8_t* buffer, uint32_t bytes) {
    if (session_) {
      return session_->Write(buffer, bytes);
    }
    return 0;
  }

  void Start() {
    if (session_) {
      session_->Start();
    }
  }

  void Stop() {
    if (session_) {
      session_->Stop();
    }
  }

  void ConfirmStreamingRequest() {
    if (session_) {
      session_->ConfirmStreamingRequest();
    }
  }

  void OnAudioChannelParametersChanged(RawAddress pseudo_address, uint16_t cis_conn_handle,
                                       const audio_channel_info& channel_info) {
    /* Recompute the stream map and update HAL with the new mapping */
    stream_manager_.OnAudioChannelParametersChanged(pseudo_address, cis_conn_handle, channel_info);
    session_->UpdateAudioConfigToHal(stream_manager_.GetConfig());
  }

  ::bluetooth::audio::le_audio::endpoint_config_rsp RequestAseConfigurations(
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& source_configs)
          const {
    return session_->RequestAseConfigurations(sink_configs, source_configs);
  }

  void OnAudioChannelRemoved(RawAddress pseudo_address, uint16_t cis_conn_handle) {
    stream_manager_.OnAudioChannelRemoved(pseudo_address, cis_conn_handle);
    session_->UpdateAudioConfigToHal(stream_manager_.GetConfig());
  }

  const std::vector<RawAddress>& GetStreamingDevices() {
    return stream_manager_.GetStreamingDevices();
  }

  audio_le_audio::IPeripheralAudioSessionFactory* factory_;
  std::unique_ptr<audio_le_audio::IPeripheralAudioOut> session_;
  HalStreamManager stream_manager_;
};

PeripheralAudioHalDecoder::PeripheralAudioHalDecoder(
        const audio_le_audio::PeripheralStreamCallbacks& callbacks,
        bluetooth::common::MessageLoopThread* message_loop,
        audio_le_audio::IPeripheralAudioSessionFactory* factory)
    : pimpl_(std::make_unique<impl>(callbacks, message_loop, factory)) {}

PeripheralAudioHalDecoder::~PeripheralAudioHalDecoder() = default;

size_t PeripheralAudioHalDecoder::Write(const uint8_t* buffer, uint32_t bytes) {
  if (pimpl_) {
    return pimpl_->Write(buffer, bytes);
  }
  return 0;
}

void PeripheralAudioHalDecoder::Start() {
  if (pimpl_) {
    pimpl_->Start();
  }
}

void PeripheralAudioHalDecoder::Stop() {
  if (pimpl_) {
    pimpl_->Stop();
  }
}

void PeripheralAudioHalDecoder::ConfirmStreamingRequest() {
  if (pimpl_) {
    pimpl_->ConfirmStreamingRequest();
  }
}

void PeripheralAudioHalDecoder::OnAudioChannelParametersChanged(
        RawAddress pseudo_address, uint16_t cis_conn_handle,
        const audio_channel_info& channel_info) {
  if (pimpl_) {
    pimpl_->OnAudioChannelParametersChanged(pseudo_address, cis_conn_handle, channel_info);
  }
}

::bluetooth::audio::le_audio::endpoint_config_rsp
PeripheralAudioHalDecoder::RequestAseConfigurations(
        const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
        const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& source_configs)
        const {
  if (pimpl_) {
    return pimpl_->RequestAseConfigurations(sink_configs, source_configs);
  }
  return {};
}

void PeripheralAudioHalDecoder::OnAudioChannelRemoved(RawAddress pseudo_address,
                                                      uint16_t cis_conn_handle) {
  if (pimpl_) {
    pimpl_->OnAudioChannelRemoved(pseudo_address, cis_conn_handle);
  }
}

const std::vector<RawAddress>& PeripheralAudioHalDecoder::GetStreamingDevices() {
  if (pimpl_) {
    return pimpl_->GetStreamingDevices();
  }
  static const std::vector<RawAddress> empty;
  return empty;
}

/*
 * Encoder (Recording) Wrapper
 */
class PeripheralAudioHalEncoder::impl {
public:
  impl(const audio_le_audio::PeripheralStreamCallbacks& callbacks,
       bluetooth::common::MessageLoopThread* message_loop,
       audio_le_audio::IPeripheralAudioSessionFactory* factory)
      : factory_(factory) {
    if (factory_ == nullptr) {
      factory_ = audio_le_audio::IPeripheralAudioSessionFactory::Get();
    }

    session_ = factory_->AcquireRecordingSession(callbacks, message_loop);
  }

  ~impl() {
    if (session_) {
      factory_->ReleaseRecordingSession(std::move(session_));
    }
  }

  size_t Read(uint8_t* buffer, uint32_t bytes) {
    if (session_) {
      return session_->Read(buffer, bytes);
    }
    return 0;
  }

  void Start() {
    if (session_) {
      session_->Start();
    }
  }

  void Stop() {
    if (session_) {
      session_->Stop();
    }
  }

  void ConfirmStreamingRequest() {
    if (session_) {
      session_->ConfirmStreamingRequest();
    }
  }

  void OnAudioChannelParametersChanged(RawAddress pseudo_address, uint16_t cis_conn_handle,
                                       const audio_channel_info& channel_info) {
    stream_manager_.OnAudioChannelParametersChanged(pseudo_address, cis_conn_handle, channel_info);
    session_->UpdateAudioConfigToHal(stream_manager_.GetConfig());
  }

  ::bluetooth::audio::le_audio::endpoint_config_rsp RequestAseConfigurations(
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& source_configs)
          const {
    return session_->RequestAseConfigurations(sink_configs, source_configs);
  }

  void OnAudioChannelRemoved(RawAddress pseudo_address, uint16_t cis_conn_handle) {
    stream_manager_.OnAudioChannelRemoved(pseudo_address, cis_conn_handle);
    session_->UpdateAudioConfigToHal(stream_manager_.GetConfig());
  }

  const std::vector<RawAddress>& GetStreamingDevices() {
    return stream_manager_.GetStreamingDevices();
  }

  audio_le_audio::IPeripheralAudioSessionFactory* factory_;
  std::unique_ptr<audio_le_audio::IPeripheralAudioIn> session_;
  HalStreamManager stream_manager_;
};

PeripheralAudioHalEncoder::PeripheralAudioHalEncoder(
        const audio_le_audio::PeripheralStreamCallbacks& callbacks,
        bluetooth::common::MessageLoopThread* message_loop,
        audio_le_audio::IPeripheralAudioSessionFactory* factory)
    : pimpl_(std::make_unique<impl>(callbacks, message_loop, factory)) {}

PeripheralAudioHalEncoder::~PeripheralAudioHalEncoder() = default;

size_t PeripheralAudioHalEncoder::Read(uint8_t* buffer, uint32_t bytes) {
  if (pimpl_) {
    return pimpl_->Read(buffer, bytes);
  }
  return 0;
}

void PeripheralAudioHalEncoder::Start() {
  if (pimpl_) {
    pimpl_->Start();
  }
}

void PeripheralAudioHalEncoder::Stop() {
  if (pimpl_) {
    pimpl_->Stop();
  }
}

void PeripheralAudioHalEncoder::ConfirmStreamingRequest() {
  if (pimpl_) {
    pimpl_->ConfirmStreamingRequest();
  }
}

const std::vector<RawAddress>& PeripheralAudioHalEncoder::GetStreamingDevices() {
  if (pimpl_) {
    return pimpl_->GetStreamingDevices();
  }
  static const std::vector<RawAddress> empty;
  return empty;
}

void PeripheralAudioHalEncoder::OnAudioChannelParametersChanged(
        RawAddress pseudo_address, uint16_t cis_conn_handle,
        const audio_channel_info& channel_info) {
  if (pimpl_) {
    pimpl_->OnAudioChannelParametersChanged(pseudo_address, cis_conn_handle, channel_info);
  }
}

::bluetooth::audio::le_audio::endpoint_config_rsp
PeripheralAudioHalEncoder::RequestAseConfigurations(
        const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
        const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& source_configs)
        const {
  if (pimpl_) {
    return pimpl_->RequestAseConfigurations(sink_configs, source_configs);
  }
  return {};
}

void PeripheralAudioHalEncoder::OnAudioChannelRemoved(RawAddress pseudo_address,
                                                      uint16_t cis_conn_handle) {
  if (pimpl_) {
    pimpl_->OnAudioChannelRemoved(pseudo_address, cis_conn_handle);
  }
}
