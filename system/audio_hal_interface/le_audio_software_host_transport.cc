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

#include "audio_hal_interface/le_audio_software_host_transport.h"

#include <bluetooth/log.h>

#include <functional>

namespace bluetooth {
namespace audio {
namespace host {
namespace le_audio {

LeAudioTransport::LeAudioTransport(std::function<void()> flush,
                                   StreamCallbacks stream_cb,
                                   PcmParameters pcm_config)
    : flush_(std::move(flush)),
      stream_cb_(std::move(stream_cb)),
      remote_delay_report_ms_(0),
      total_bytes_processed_(0),
      data_position_({}),
      pcm_config_(std::move(pcm_config)),
      start_request_state_(StartRequestState::IDLE){};

bool LeAudioTransport::StartRequest() {
  SetStartRequestState(StartRequestState::PENDING_BEFORE_RESUME);
  if (stream_cb_.on_resume_(true)) {
    if (start_request_state_ == StartRequestState::CONFIRMED) {
      log::info("Start completed.");
      SetStartRequestState(StartRequestState::IDLE);
      return true;
    }

    if (start_request_state_ == StartRequestState::CANCELED) {
      log::info("Start request failed.");
      SetStartRequestState(StartRequestState::IDLE);
      return false;
    }

    log::info("Start pending.");
    SetStartRequestState(StartRequestState::PENDING_AFTER_RESUME);
    return true;
  }

  log::error("Start request failed.");
  SetStartRequestState(StartRequestState::IDLE);
  return false;
}

bool LeAudioTransport::SuspendRequest() {
  log::info("");
  if (stream_cb_.on_suspend_()) {
    flush_();
    return true;
  } else {
    return false;
  }
}

void LeAudioTransport::StopRequest() {
  log::info("");
  if (stream_cb_.on_suspend_()) {
    flush_();
  }
}

bool LeAudioTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                               uint64_t* total_bytes_processed,
                                               timespec* data_position) {
  log::verbose("data={} byte(s), timestamp={}.{}s, delay report={} msec.",
               total_bytes_processed_, data_position_.tv_sec,
               data_position_.tv_nsec, remote_delay_report_ms_);
  if (remote_delay_report_ns != nullptr) {
    *remote_delay_report_ns = remote_delay_report_ms_ * 1000000u;
  }
  if (total_bytes_processed != nullptr)
    *total_bytes_processed = total_bytes_processed_;
  if (data_position != nullptr) *data_position = data_position_;

  return true;
}

void LeAudioTransport::SourceMetadataChanged(
    const source_metadata_v7_t& source_metadata) {
  auto track_count = source_metadata.track_count;

  if (track_count == 0) {
    log::warn(", invalid number of metadata changed tracks");
    return;
  }

  stream_cb_.on_metadata_update_(source_metadata,
                                 ::bluetooth::le_audio::DsaMode::DISABLED);
}

void LeAudioTransport::SinkMetadataChanged(
    const sink_metadata_v7_t& sink_metadata) {
  auto track_count = sink_metadata.track_count;

  if (track_count == 0) {
    log::warn(", invalid number of metadata changed tracks");
    return;
  }

  stream_cb_.on_sink_metadata_update_(sink_metadata);
}

void LeAudioTransport::ResetPresentationPosition() {
  log::verbose("called.");
  remote_delay_report_ms_ = 0;
  total_bytes_processed_ = 0;
  data_position_ = {};
}

void LeAudioTransport::LogBytesProcessed(size_t bytes_processed) {
  if (bytes_processed) {
    total_bytes_processed_ += bytes_processed;
    clock_gettime(CLOCK_MONOTONIC, &data_position_);
  }
}

void LeAudioTransport::SetRemoteDelay(uint16_t delay_report_ms) {
  log::info("delay_report={} msec", delay_report_ms);
  remote_delay_report_ms_ = delay_report_ms;
}

const PcmParameters& LeAudioTransport::LeAudioGetSelectedHalPcmConfig() {
  return pcm_config_;
}

void LeAudioTransport::LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz,
                                                      uint8_t bit_rate,
                                                      uint8_t channels_count,
                                                      uint32_t data_interval) {
  pcm_config_.sample_rate = sample_rate_hz;
  pcm_config_.bits_per_sample = bit_rate;
  pcm_config_.channels_count = channels_count;
  pcm_config_.data_interval_us = data_interval;
}

StartRequestState LeAudioTransport::GetStartRequestState(void) {
  return start_request_state_;
}
void LeAudioTransport::ClearStartRequestState(void) {
  start_request_state_ = StartRequestState::IDLE;
  remote_delay_report_ms_ = 0;
}
void LeAudioTransport::SetStartRequestState(StartRequestState state) {
  start_request_state_ = state;
}

static PcmParameters get_source_default_pcm_parameters() {
  return PcmParameters{
      .data_interval_us = 0,
      .sample_rate = 16000,
      .bits_per_sample = 16,
      .channels_count = 1,
  };
}

static PcmParameters get_sink_default_pcm_parameters() {
  return PcmParameters{
      .data_interval_us = 0,
      .sample_rate = 16000,
      .bits_per_sample = 16,
      .channels_count = 2,
  };
}

LeAudioSinkTransport::LeAudioSinkTransport(StreamCallbacks stream_cb) {
  std::function<void()> flush_sink = [&]() {
    // TODO(b/331315361): investigate the effect of flushing the buffer
  };

  transport_ = new LeAudioTransport(flush_sink, std::move(stream_cb),
                                    get_sink_default_pcm_parameters());
};

LeAudioSinkTransport::~LeAudioSinkTransport() { delete transport_; }

bool LeAudioSinkTransport::StartRequest() { return transport_->StartRequest(); }

bool LeAudioSinkTransport::SuspendRequest() {
  return transport_->SuspendRequest();
}

void LeAudioSinkTransport::StopRequest() { transport_->StopRequest(); }

bool LeAudioSinkTransport::GetPresentationPosition(
    uint64_t* remote_delay_report_ns, uint64_t* total_bytes_read,
    timespec* data_position) {
  return transport_->GetPresentationPosition(remote_delay_report_ns,
                                             total_bytes_read, data_position);
}

void LeAudioSinkTransport::SourceMetadataChanged(
    const source_metadata_v7_t& source_metadata) {
  transport_->SourceMetadataChanged(source_metadata);
}

void LeAudioSinkTransport::SinkMetadataChanged(
    const sink_metadata_v7_t& sink_metadata) {
  transport_->SinkMetadataChanged(sink_metadata);
}

void LeAudioSinkTransport::ResetPresentationPosition() {
  transport_->ResetPresentationPosition();
}

void LeAudioSinkTransport::LogBytesRead(size_t bytes_read) {
  transport_->LogBytesProcessed(bytes_read);
}

void LeAudioSinkTransport::SetRemoteDelay(uint16_t delay_report_ms) {
  transport_->SetRemoteDelay(delay_report_ms);
}

const PcmParameters& LeAudioSinkTransport::LeAudioGetSelectedHalPcmConfig() {
  return transport_->LeAudioGetSelectedHalPcmConfig();
}

void LeAudioSinkTransport::LeAudioSetSelectedHalPcmConfig(
    uint32_t sample_rate_hz, uint8_t bit_rate, uint8_t channels_count,
    uint32_t data_interval) {
  transport_->LeAudioSetSelectedHalPcmConfig(sample_rate_hz, bit_rate,
                                             channels_count, data_interval);
}

StartRequestState LeAudioSinkTransport::GetStartRequestState(void) {
  return transport_->GetStartRequestState();
}
void LeAudioSinkTransport::ClearStartRequestState(void) {
  transport_->ClearStartRequestState();
}
void LeAudioSinkTransport::SetStartRequestState(StartRequestState state) {
  transport_->SetStartRequestState(state);
}

LeAudioSourceTransport::LeAudioSourceTransport(StreamCallbacks stream_cb) {
  std::function<void()> flush_source = [&]() {
    // TODO(b/331315361): investigate the effect of flushing the buffer
  };

  transport_ = new LeAudioTransport(flush_source, std::move(stream_cb),
                                    get_source_default_pcm_parameters());
};

LeAudioSourceTransport::~LeAudioSourceTransport() { delete transport_; }

bool LeAudioSourceTransport::StartRequest() {
  return transport_->StartRequest();
}

bool LeAudioSourceTransport::SuspendRequest() {
  return transport_->SuspendRequest();
}

void LeAudioSourceTransport::StopRequest() { transport_->StopRequest(); }

bool LeAudioSourceTransport::GetPresentationPosition(
    uint64_t* remote_delay_report_ns, uint64_t* total_bytes_written,
    timespec* data_position) {
  return transport_->GetPresentationPosition(
      remote_delay_report_ns, total_bytes_written, data_position);
}

void LeAudioSourceTransport::SourceMetadataChanged(
    const source_metadata_v7_t& source_metadata) {}

void LeAudioSourceTransport::SinkMetadataChanged(
    const sink_metadata_v7_t& sink_metadata) {
  transport_->SinkMetadataChanged(sink_metadata);
}

void LeAudioSourceTransport::ResetPresentationPosition() {
  transport_->ResetPresentationPosition();
}

void LeAudioSourceTransport::LogBytesWritten(size_t bytes_written) {
  transport_->LogBytesProcessed(bytes_written);
}

void LeAudioSourceTransport::SetRemoteDelay(uint16_t delay_report_ms) {
  transport_->SetRemoteDelay(delay_report_ms);
}

const PcmParameters& LeAudioSourceTransport::LeAudioGetSelectedHalPcmConfig() {
  return transport_->LeAudioGetSelectedHalPcmConfig();
}

void LeAudioSourceTransport::LeAudioSetSelectedHalPcmConfig(
    uint32_t sample_rate_hz, uint8_t bit_rate, uint8_t channels_count,
    uint32_t data_interval) {
  transport_->LeAudioSetSelectedHalPcmConfig(sample_rate_hz, bit_rate,
                                             channels_count, data_interval);
}

StartRequestState LeAudioSourceTransport::GetStartRequestState(void) {
  return transport_->GetStartRequestState();
}
void LeAudioSourceTransport::ClearStartRequestState(void) {
  transport_->ClearStartRequestState();
}
void LeAudioSourceTransport::SetStartRequestState(StartRequestState state) {
  transport_->SetStartRequestState(state);
}
}  // namespace le_audio
}  // namespace host
}  // namespace audio
}  // namespace bluetooth
