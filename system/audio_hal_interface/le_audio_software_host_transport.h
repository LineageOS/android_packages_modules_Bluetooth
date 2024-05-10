/*
 * Copyright 2024 The Android Open Source Project
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

#include <functional>

#include "audio_hal_interface/le_audio_software.h"
#include "audio_hal_interface/le_audio_software_host.h"
#include "bta/le_audio/le_audio_types.h"

namespace bluetooth {
namespace audio {
namespace host {
namespace le_audio {

using ::bluetooth::le_audio::set_configurations::AudioSetConfiguration;
using ::bluetooth::le_audio::set_configurations::CodecConfigSetting;

using ::bluetooth::audio::le_audio::LeAudioClientInterface;
using ::bluetooth::audio::le_audio::StartRequestState;
using ::bluetooth::audio::le_audio::StreamCallbacks;

typedef LeAudioClientInterface::PcmParameters PcmParameters;

class LeAudioTransport {
 public:
  LeAudioTransport(std::function<void()> flush, StreamCallbacks stream_cb,
                   PcmParameters pcm_config);

  bool StartRequest();

  bool SuspendRequest();

  void StopRequest();

  bool GetPresentationPosition(uint64_t* remote_delay_report_ns,
                               uint64_t* total_bytes_processed,
                               timespec* data_position);

  void SourceMetadataChanged(const source_metadata_v7_t& source_metadata);

  void SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata);

  void ResetPresentationPosition();

  void LogBytesProcessed(size_t bytes_processed);

  void SetRemoteDelay(uint16_t delay_report_ms);

  const PcmParameters& LeAudioGetSelectedHalPcmConfig();

  void LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz, uint8_t bit_rate,
                                      uint8_t channels_count,
                                      uint32_t data_interval);

  StartRequestState GetStartRequestState(void);
  void ClearStartRequestState(void);
  void SetStartRequestState(StartRequestState state);

 private:
  std::function<void()> flush_;
  StreamCallbacks stream_cb_;
  uint16_t remote_delay_report_ms_;
  uint64_t total_bytes_processed_;
  timespec data_position_;
  PcmParameters pcm_config_;
  std::atomic<StartRequestState> start_request_state_;
};

// Sink transport implementation for Le Audio
class LeAudioSinkTransport {
 public:
  LeAudioSinkTransport(StreamCallbacks stream_cb);

  ~LeAudioSinkTransport();

  bool StartRequest();

  bool SuspendRequest();

  void StopRequest();

  bool GetPresentationPosition(uint64_t* remote_delay_report_ns,
                               uint64_t* total_bytes_read,
                               timespec* data_position);

  void SourceMetadataChanged(const source_metadata_v7_t& source_metadata);

  void SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata);

  void ResetPresentationPosition();

  void LogBytesRead(size_t bytes_read);

  void SetRemoteDelay(uint16_t delay_report_ms);

  const PcmParameters& LeAudioGetSelectedHalPcmConfig();

  void LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz, uint8_t bit_rate,
                                      uint8_t channels_count,
                                      uint32_t data_interval);

  StartRequestState GetStartRequestState(void);
  void ClearStartRequestState(void);
  void SetStartRequestState(StartRequestState state);

  static inline LeAudioSinkTransport* instance = nullptr;
  static inline bool stream_started = false;

 private:
  LeAudioTransport* transport_;
};

class LeAudioSourceTransport {
 public:
  LeAudioSourceTransport(StreamCallbacks stream_cb);

  ~LeAudioSourceTransport();

  bool StartRequest();

  bool SuspendRequest();

  void StopRequest();

  bool GetPresentationPosition(uint64_t* remote_delay_report_ns,
                               uint64_t* total_bytes_written,
                               timespec* data_position);

  void SourceMetadataChanged(const source_metadata_v7_t& source_metadata);

  void SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata);

  void ResetPresentationPosition();

  void LogBytesWritten(size_t bytes_written);

  void SetRemoteDelay(uint16_t delay_report_ms);

  const PcmParameters& LeAudioGetSelectedHalPcmConfig();

  void LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz, uint8_t bit_rate,
                                      uint8_t channels_count,
                                      uint32_t data_interval);

  StartRequestState GetStartRequestState(void);
  void ClearStartRequestState(void);
  void SetStartRequestState(StartRequestState state);

  static inline LeAudioSourceTransport* instance = nullptr;
  static inline bool stream_started = false;

 private:
  LeAudioTransport* transport_;
};

}  // namespace le_audio
}  // namespace host
}  // namespace audio
}  // namespace bluetooth
