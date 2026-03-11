/*
 * Copyright 2025 The Android Open Source Project
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
#define LOG_TAG "bluetooth-a2dp-aidl"

#include "a2dp_aidl_transport.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <vector>

#include "audio_aidl_interfaces.h"
#include "client_interface_aidl.h"
#include "codec_status_aidl.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace a2dp {

using ::bluetooth::audio::a2dp::Status;
using ::bluetooth::audio::a2dp::StreamCallbacks;

using ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::SessionType;

A2dpTransport::A2dpTransport(SessionType sessionType, StreamCallbacks const* stream_callbacks)
    : session_type_(sessionType), stream_callbacks_(stream_callbacks) {
  log::assert_that(stream_callbacks_ != nullptr, "stream_callbacks != nullptr");
}

void A2dpTransport::UpdateAudioConfiguration(const AudioConfiguration& audio_config) {
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
    default:
      log::warn("Unsupported audio config: {}", audio_config.toString());
      break;
  }
}

Status A2dpTransport::StartRequest(bool is_low_latency) {
  std::lock_guard<std::mutex> lock(mutex_);

  // Check if a previous Start request is ongoing.
  if (a2dp_pending_cmd_ == A2DP_CTRL_CMD_START) {
    log::warn("unable to start stream: already pending");
    return Status::PENDING;
  }

  // Check if a different request is ongoing.
  if (a2dp_pending_cmd_ != A2DP_CTRL_CMD_NONE) {
    log::warn("unable to start stream: busy with pending command {}", a2dp_pending_cmd_);
    return Status::FAILURE;
  }

  log::info("is_low_latency={}", is_low_latency);

  auto status = stream_callbacks_->StartStream(is_low_latency);
  a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_START : A2DP_CTRL_CMD_NONE;

  return status;
}

Status A2dpTransport::SuspendRequest() {
  std::lock_guard<std::mutex> lock(mutex_);

  // Check if a previous Suspend request is ongoing.
  if (a2dp_pending_cmd_ == A2DP_CTRL_CMD_SUSPEND) {
    log::warn("unable to suspend stream: already pending");
    return Status::PENDING;
  }

  // Check if a different request is ongoing.
  if (a2dp_pending_cmd_ != A2DP_CTRL_CMD_NONE) {
    log::warn("unable to suspend stream: busy with pending command {}", a2dp_pending_cmd_);
    return Status::FAILURE;
  }

  log::info("");

  auto status = stream_callbacks_->SuspendStream();
  a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_SUSPEND : A2DP_CTRL_CMD_NONE;

  return status;
}

void A2dpTransport::StopRequest() {
  std::lock_guard<std::mutex> lock(mutex_);

  log::info("");

  auto status = stream_callbacks_->StopStream();
  a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_STOP : A2DP_CTRL_CMD_NONE;
}

void A2dpTransport::SetLatencyMode(LatencyMode latency_mode) {
  log::info("latency_mode={}", ::aidl::android::hardware::bluetooth::audio::toString(latency_mode));
  stream_callbacks_->SetLatencyMode(latency_mode == LatencyMode::LOW_LATENCY);
}

void A2dpTransport::UpdateSinkLatency(int64_t latency_ms) {
  if (session_type_ != SessionType::A2DP_HARDWARE_OFFLOAD_DECODING_DATAPATH) {
    return;
  }
  log::info("latency_ms: {}", latency_ms);
  stream_callbacks_->UpdateSinkLatency(latency_ms);
}

void A2dpTransport::SourceMetadataChanged(btav_a2dp_codec_audio_context_t audio_context) {
  stream_callbacks_->SourceMetadataChanged(audio_context);
}

bool A2dpTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                            uint64_t* total_bytes_read, timespec* data_position) {
  *remote_delay_report_ns = remote_delay_report_ * 100000u;
  *total_bytes_read = total_bytes_read_;
  *data_position = data_position_;
  log::debug("delay={}/10ms, data={} byte(s), timestamp={}.{}s", remote_delay_report_,
             total_bytes_read_, data_position_.tv_sec, data_position_.tv_nsec);
  return true;
}

tA2DP_CTRL_CMD A2dpTransport::GetPendingCmd() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return a2dp_pending_cmd_;
}

void A2dpTransport::ResetPendingCmd() {
  std::lock_guard<std::mutex> lock(mutex_);
  a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE;
}

void A2dpTransport::ResetPresentationPosition() {
  remote_delay_report_ = 0;
  total_bytes_read_ = 0;
  data_position_ = {};
}

void A2dpTransport::LogBytesRead(size_t bytes_read) {
  if (bytes_read != 0) {
    total_bytes_read_ += bytes_read;
    clock_gettime(CLOCK_MONOTONIC, &data_position_);
  }
}

// delay reports from AVDTP is based on 1/10 ms (100us)
void A2dpTransport::SetRemoteDelay(uint16_t delay_report) { remote_delay_report_ = delay_report; }

}  // namespace a2dp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
