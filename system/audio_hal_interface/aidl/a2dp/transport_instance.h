/*
 * Copyright 2022 The Android Open Source Project
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

#include <hardware/audio.h>

#include "a2dp_encoding.h"
#include "audio_aidl_interfaces.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace a2dp {

using ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::LatencyMode;
using ::aidl::android::hardware::bluetooth::audio::SessionType;
using ::bluetooth::audio::a2dp::Status;

/***
 * An IBluetoothTransportInstance needs to be implemented by a Bluetooth
 * audio transport, such as A2DP or Hearing Aid, to handle callbacks from Audio
 * HAL.
 ***/
class IBluetoothTransportInstance {
public:
  IBluetoothTransportInstance(SessionType sessionType, AudioConfiguration audioConfig)
      : session_type_(sessionType), audio_config_(std::move(audioConfig)) {}
  virtual ~IBluetoothTransportInstance() = default;

  SessionType GetSessionType() const { return session_type_; }
  AudioConfiguration GetAudioConfiguration() const { return audio_config_; }

  void UpdateAudioConfiguration(const AudioConfiguration& audio_config) {
    switch (audio_config.getTag()) {
      case AudioConfiguration::pcmConfig:
        audio_config_.set<AudioConfiguration::pcmConfig>(
                audio_config.get<AudioConfiguration::pcmConfig>());
        break;
      case AudioConfiguration::a2dpConfig:
        audio_config_.set<AudioConfiguration::a2dpConfig>(
                audio_config.get<AudioConfiguration::a2dpConfig>());
        break;
      case AudioConfiguration::a2dp:
        audio_config_.set<AudioConfiguration::a2dp>(audio_config.get<AudioConfiguration::a2dp>());
        break;
      case AudioConfiguration::leAudioConfig:
      case AudioConfiguration::leAudioBroadcastConfig:
      case AudioConfiguration::hfpConfig:
        // Unused by the A2DP client interface.
        break;
    }
  }

  virtual Status StartRequest(bool is_low_latency) = 0;
  virtual Status SuspendRequest() = 0;
  virtual void StopRequest() = 0;

  virtual void SetLatencyMode(LatencyMode latency_mode) = 0;
  virtual bool GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                       uint64_t* total_bytes_readed, timespec* data_position) = 0;
  virtual void SourceMetadataChanged(btav_a2dp_codec_audio_context_t audio_context) = 0;

  /***
   * Invoked when the transport is requested to reset presentation position
   ***/
  virtual void ResetPresentationPosition() = 0;

  /***
   * Invoked when the transport is requested to log bytes read
   ***/
  virtual void LogBytesRead(size_t bytes_readed) = 0;

private:
  const SessionType session_type_;
  AudioConfiguration audio_config_;
};

}  // namespace a2dp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
