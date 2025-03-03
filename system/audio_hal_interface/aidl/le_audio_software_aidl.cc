/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#define LOG_TAG "BTAudioLeAudioAIDL"

#include "le_audio_software_aidl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <atomic>
#include <bitset>
#include <unordered_map>
#include <vector>

#include "common/strings.h"
#include "hal_version_manager.h"
#include "le_audio_utils.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace le_audio {

using ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::AudioLocation;
using ::aidl::android::hardware::bluetooth::audio::ChannelMode;
using ::aidl::android::hardware::bluetooth::audio::CodecType;
using ::aidl::android::hardware::bluetooth::audio::Lc3Configuration;
using ::aidl::android::hardware::bluetooth::audio::LeAudioCodecConfiguration;
using ::aidl::android::hardware::bluetooth::audio::LeAudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::PcmConfiguration;
using ::bluetooth::audio::aidl::AudioConfiguration;
using ::bluetooth::audio::aidl::BluetoothAudioCtrlAck;
using ::bluetooth::audio::le_audio::LeAudioClientInterface;
using ::bluetooth::audio::le_audio::StartRequestState;
using ::bluetooth::audio::le_audio::StreamCallbacks;
using ::bluetooth::le_audio::types::AseConfiguration;
using ::bluetooth::le_audio::types::LeAudioCoreCodecConfig;

static ChannelMode le_audio_channel_mode2audio_hal(uint8_t channels_count) {
  switch (channels_count) {
    case 1:
      return ChannelMode::MONO;
    case 2:
      return ChannelMode::STEREO;
  }
  return ChannelMode::UNKNOWN;
}

LeAudioTransport::LeAudioTransport(void (*flush)(void), StreamCallbacks stream_cb,
                                   PcmConfiguration pcm_config)
    : flush_(std::move(flush)),
      stream_cb_(std::move(stream_cb)),
      remote_delay_report_ms_(0),
      total_bytes_processed_(0),
      data_position_({}),
      pcm_config_(std::move(pcm_config)),
      start_request_state_(StartRequestState::IDLE),
      dsa_mode_(DsaMode::DISABLED),
      cached_source_metadata_({}) {}

LeAudioTransport::~LeAudioTransport() {
  if (cached_source_metadata_.tracks != nullptr) {
    free(cached_source_metadata_.tracks);
    cached_source_metadata_.tracks = nullptr;
  }
}

BluetoothAudioCtrlAck LeAudioTransport::StartRequest(bool /*is_low_latency*/) {
  // Check if operation is pending already
  if (GetStartRequestState() == StartRequestState::PENDING_AFTER_RESUME) {
    log::info("Start request is already pending. Ignore the request");
    return BluetoothAudioCtrlAck::PENDING;
  }

  SetStartRequestState(StartRequestState::PENDING_BEFORE_RESUME);
  if (stream_cb_.on_resume_(true)) {
    std::lock_guard<std::mutex> guard(start_request_state_mutex_);

    switch (start_request_state_) {
      case StartRequestState::CONFIRMED:
        log::info("Start completed.");
        SetStartRequestState(StartRequestState::IDLE);
        return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
      case StartRequestState::CANCELED:
        log::info("Start request failed.");
        SetStartRequestState(StartRequestState::IDLE);
        return BluetoothAudioCtrlAck::FAILURE;
      case StartRequestState::PENDING_BEFORE_RESUME:
        log::info("Start pending.");
        SetStartRequestState(StartRequestState::PENDING_AFTER_RESUME);
        return BluetoothAudioCtrlAck::PENDING;
      default:
        SetStartRequestState(StartRequestState::IDLE);
        log::error("Unexpected state {}", static_cast<int>(start_request_state_.load()));
        return BluetoothAudioCtrlAck::FAILURE;
    }
  }

  SetStartRequestState(StartRequestState::IDLE);
  log::info("On resume failed.");
  return BluetoothAudioCtrlAck::FAILURE;
}

BluetoothAudioCtrlAck LeAudioTransport::SuspendRequest() {
  log::info("");
  if (stream_cb_.on_suspend_()) {
    flush_();
    log::info("completed with a success");
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  } else {
    log::info("completed with a failure");
    return BluetoothAudioCtrlAck::FAILURE;
  }
}

void LeAudioTransport::StopRequest() {
  log::info("");
  if (stream_cb_.on_suspend_()) {
    flush_();
    log::info("completed with a success");
  }
}

void LeAudioTransport::SetLatencyMode(LatencyMode latency_mode) {
  log::debug("Latency mode: {}",
             ::aidl::android::hardware::bluetooth::audio::toString(latency_mode));

  DsaMode prev_dsa_mode = dsa_mode_;

  switch (latency_mode) {
    case LatencyMode::FREE:
      dsa_mode_ = DsaMode::DISABLED;
      break;
    case LatencyMode::LOW_LATENCY:
      dsa_mode_ = DsaMode::ACL;
      break;
    case LatencyMode::DYNAMIC_SPATIAL_AUDIO_SOFTWARE:
      dsa_mode_ = DsaMode::ISO_SW;
      break;
    case LatencyMode::DYNAMIC_SPATIAL_AUDIO_HARDWARE:
      dsa_mode_ = DsaMode::ISO_HW;
      break;
    default:
      log::warn(", invalid latency mode: {}", (int)latency_mode);
      return;
  }

  if (dsa_mode_ != prev_dsa_mode && cached_source_metadata_.tracks != nullptr &&
      cached_source_metadata_.tracks != 0) {
    log::info(", latency mode changed, update source metadata");
    stream_cb_.on_metadata_update_(cached_source_metadata_, dsa_mode_);
  }
}

bool LeAudioTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                               uint64_t* total_bytes_processed,
                                               timespec* data_position) {
  log::verbose("data={} byte(s), timestamp={}.{}s, delay report={} msec.", total_bytes_processed_,
               data_position_.tv_sec, data_position_.tv_nsec, remote_delay_report_ms_);
  if (remote_delay_report_ns != nullptr) {
    *remote_delay_report_ns = static_cast<uint64_t>(remote_delay_report_ms_) * 1000000u;
  }
  if (total_bytes_processed != nullptr) {
    *total_bytes_processed = total_bytes_processed_;
  }
  if (data_position != nullptr) {
    *data_position = data_position_;
  }

  return true;
}

void LeAudioTransport::SourceMetadataChanged(const source_metadata_v7_t& source_metadata) {
  auto track_count = source_metadata.track_count;

  if (cached_source_metadata_.tracks != nullptr) {
    free(cached_source_metadata_.tracks);
    cached_source_metadata_.tracks = nullptr;
  }

  log::info(", caching source metadata");

  playback_track_metadata_v7* tracks = nullptr;
  if (track_count != 0) {
    tracks = (playback_track_metadata_v7*)malloc(sizeof(*tracks) * track_count);
    memcpy(tracks, source_metadata.tracks, sizeof(*tracks) * track_count);
  }

  cached_source_metadata_.track_count = track_count;
  cached_source_metadata_.tracks = tracks;

  stream_cb_.on_metadata_update_(source_metadata, dsa_mode_);
}

void LeAudioTransport::SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata) {
  auto track_count = sink_metadata.track_count;

  if (stream_cb_.on_sink_metadata_update_) {
    stream_cb_.on_sink_metadata_update_(sink_metadata);
  }
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

const PcmConfiguration& LeAudioTransport::LeAudioGetSelectedHalPcmConfig() { return pcm_config_; }

void LeAudioTransport::LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz, uint8_t bit_rate,
                                                      uint8_t channels_count,
                                                      uint32_t data_interval) {
  pcm_config_.sampleRateHz = (sample_rate_hz);
  pcm_config_.bitsPerSample = (bit_rate);
  pcm_config_.channelMode = le_audio_channel_mode2audio_hal(channels_count);
  pcm_config_.dataIntervalUs = data_interval;
}

void LeAudioTransport::LeAudioSetBroadcastConfig(
        const ::bluetooth::le_audio::broadcast_offload_config& offload_config) {
  broadcast_config_.streamMap.resize(0);
  for (auto& [handle, location] : offload_config.stream_map) {
    Lc3Configuration lc3_config{
            .pcmBitDepth = static_cast<int8_t>(offload_config.bits_per_sample),
            .samplingFrequencyHz = static_cast<int32_t>(offload_config.sampling_rate),
            .frameDurationUs = static_cast<int32_t>(offload_config.frame_duration),
            .octetsPerFrame = static_cast<int32_t>(offload_config.octets_per_frame),
            .blocksPerSdu = static_cast<int8_t>(offload_config.blocks_per_sdu),
    };
    broadcast_config_.streamMap.push_back({
            .streamHandle = handle,
            .audioChannelAllocation = static_cast<int32_t>(location),
            .leAudioCodecConfig = std::move(lc3_config),
    });
  }
}

const LeAudioBroadcastConfiguration& LeAudioTransport::LeAudioGetBroadcastConfig() {
  return broadcast_config_;
}

bool LeAudioTransport::IsRequestCompletedAfterUpdate(
        const std::function<std::pair<StartRequestState, bool>(StartRequestState)>& lambda) {
  std::lock_guard<std::mutex> guard(start_request_state_mutex_);
  auto result = lambda(start_request_state_);
  auto new_state = std::get<0>(result);
  if (new_state != start_request_state_) {
    start_request_state_ = new_state;
  }

  auto ret = std::get<1>(result);
  log::verbose("new state: {}, return {}", (int)(start_request_state_.load()), ret);

  return ret;
}

StartRequestState LeAudioTransport::GetStartRequestState(void) {
  std::lock_guard<std::mutex> guard(start_request_state_mutex_);
  return start_request_state_;
}
void LeAudioTransport::ClearStartRequestState(void) {
  start_request_state_ = StartRequestState::IDLE;
}
void LeAudioTransport::SetStartRequestState(StartRequestState state) {
  start_request_state_ = state;
}

inline void flush_unicast_sink() {
  if (LeAudioSinkTransport::interface_unicast_ == nullptr) {
    return;
  }

  LeAudioSinkTransport::interface_unicast_->FlushAudioData();
}

inline void flush_broadcast_sink() {
  if (LeAudioSinkTransport::interface_broadcast_ == nullptr) {
    return;
  }

  LeAudioSinkTransport::interface_broadcast_->FlushAudioData();
}

inline bool is_broadcaster_session(SessionType session_type) {
  if (session_type == SessionType::LE_AUDIO_BROADCAST_HARDWARE_OFFLOAD_ENCODING_DATAPATH ||
      session_type == SessionType::LE_AUDIO_BROADCAST_SOFTWARE_ENCODING_DATAPATH) {
    return true;
  }

  return false;
}

LeAudioSinkTransport::LeAudioSinkTransport(SessionType session_type, StreamCallbacks stream_cb)
    : IBluetoothSinkTransportInstance(session_type, (AudioConfiguration){}) {
  transport_ = new LeAudioTransport(
          is_broadcaster_session(session_type) ? flush_broadcast_sink : flush_unicast_sink,
          std::move(stream_cb), {16000, ChannelMode::STEREO, 16, 0});
}

LeAudioSinkTransport::~LeAudioSinkTransport() { delete transport_; }

BluetoothAudioCtrlAck LeAudioSinkTransport::StartRequest(bool is_low_latency) {
  return transport_->StartRequest(is_low_latency);
}

BluetoothAudioCtrlAck LeAudioSinkTransport::SuspendRequest() {
  return transport_->SuspendRequest();
}

void LeAudioSinkTransport::StopRequest() { transport_->StopRequest(); }

void LeAudioSinkTransport::SetLatencyMode(LatencyMode latency_mode) {
  transport_->SetLatencyMode(latency_mode);
}

bool LeAudioSinkTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                                   uint64_t* total_bytes_read,
                                                   timespec* data_position) {
  return transport_->GetPresentationPosition(remote_delay_report_ns, total_bytes_read,
                                             data_position);
}

void LeAudioSinkTransport::SourceMetadataChanged(const source_metadata_v7_t& source_metadata) {
  transport_->SourceMetadataChanged(source_metadata);
}

void LeAudioSinkTransport::SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata) {
  transport_->SinkMetadataChanged(sink_metadata);
}

void LeAudioSinkTransport::ResetPresentationPosition() { transport_->ResetPresentationPosition(); }

void LeAudioSinkTransport::LogBytesRead(size_t bytes_read) {
  transport_->LogBytesProcessed(bytes_read);
}

void LeAudioSinkTransport::SetRemoteDelay(uint16_t delay_report_ms) {
  transport_->SetRemoteDelay(delay_report_ms);
}

const PcmConfiguration& LeAudioSinkTransport::LeAudioGetSelectedHalPcmConfig() {
  return transport_->LeAudioGetSelectedHalPcmConfig();
}

void LeAudioSinkTransport::LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz, uint8_t bit_rate,
                                                          uint8_t channels_count,
                                                          uint32_t data_interval) {
  transport_->LeAudioSetSelectedHalPcmConfig(sample_rate_hz, bit_rate, channels_count,
                                             data_interval);
}

void LeAudioSinkTransport::LeAudioSetBroadcastConfig(
        const ::bluetooth::le_audio::broadcast_offload_config& offload_config) {
  transport_->LeAudioSetBroadcastConfig(offload_config);
}

const LeAudioBroadcastConfiguration& LeAudioSinkTransport::LeAudioGetBroadcastConfig() {
  return transport_->LeAudioGetBroadcastConfig();
}

bool LeAudioSinkTransport::IsRequestCompletedAfterUpdate(
        const std::function<std::pair<StartRequestState, bool>(StartRequestState)>& lambda) {
  return transport_->IsRequestCompletedAfterUpdate(lambda);
}

StartRequestState LeAudioSinkTransport::GetStartRequestState(void) {
  return transport_->GetStartRequestState();
}
void LeAudioSinkTransport::ClearStartRequestState(void) { transport_->ClearStartRequestState(); }
void LeAudioSinkTransport::SetStartRequestState(StartRequestState state) {
  transport_->SetStartRequestState(state);
}

void flush_source() {
  if (LeAudioSourceTransport::interface == nullptr) {
    return;
  }

  LeAudioSourceTransport::interface->FlushAudioData();
}

LeAudioSourceTransport::LeAudioSourceTransport(SessionType session_type, StreamCallbacks stream_cb)
    : IBluetoothSourceTransportInstance(session_type, (AudioConfiguration){}) {
  transport_ = new LeAudioTransport(flush_source, std::move(stream_cb),
                                    {16000, ChannelMode::STEREO, 16, 0});
}

LeAudioSourceTransport::~LeAudioSourceTransport() { delete transport_; }

BluetoothAudioCtrlAck LeAudioSourceTransport::StartRequest(bool is_low_latency) {
  return transport_->StartRequest(is_low_latency);
}

BluetoothAudioCtrlAck LeAudioSourceTransport::SuspendRequest() {
  return transport_->SuspendRequest();
}

void LeAudioSourceTransport::StopRequest() { transport_->StopRequest(); }

void LeAudioSourceTransport::SetLatencyMode(LatencyMode latency_mode) {
  transport_->SetLatencyMode(latency_mode);
}

bool LeAudioSourceTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                                     uint64_t* total_bytes_written,
                                                     timespec* data_position) {
  return transport_->GetPresentationPosition(remote_delay_report_ns, total_bytes_written,
                                             data_position);
}

void LeAudioSourceTransport::SourceMetadataChanged(const source_metadata_v7_t& source_metadata) {
  transport_->SourceMetadataChanged(source_metadata);
}

void LeAudioSourceTransport::SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata) {
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

const PcmConfiguration& LeAudioSourceTransport::LeAudioGetSelectedHalPcmConfig() {
  return transport_->LeAudioGetSelectedHalPcmConfig();
}

void LeAudioSourceTransport::LeAudioSetSelectedHalPcmConfig(uint32_t sample_rate_hz,
                                                            uint8_t bit_rate,
                                                            uint8_t channels_count,
                                                            uint32_t data_interval) {
  transport_->LeAudioSetSelectedHalPcmConfig(sample_rate_hz, bit_rate, channels_count,
                                             data_interval);
}

bool LeAudioSourceTransport::IsRequestCompletedAfterUpdate(
        const std::function<std::pair<StartRequestState, bool>(StartRequestState)>& lambda) {
  return transport_->IsRequestCompletedAfterUpdate(lambda);
}

StartRequestState LeAudioSourceTransport::GetStartRequestState(void) {
  return transport_->GetStartRequestState();
}
void LeAudioSourceTransport::ClearStartRequestState(void) { transport_->ClearStartRequestState(); }

void LeAudioSourceTransport::SetStartRequestState(StartRequestState state) {
  transport_->SetStartRequestState(state);
}

std::unordered_map<int32_t, uint8_t> sampling_freq_map{
        {8000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq8000Hz},
        {16000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq16000Hz},
        {24000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq24000Hz},
        {32000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq32000Hz},
        {44100, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq44100Hz},
        {48000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq48000Hz},
        {88200, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq88200Hz},
        {96000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq96000Hz},
        {176400, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq176400Hz},
        {192000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioSamplingFreq192000Hz}};

std::unordered_map<int32_t, uint8_t> frame_duration_map{
        {7500, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameDur7500us},
        {10000, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameDur10000us}};

std::unordered_map<int32_t, uint16_t> octets_per_frame_map{
        {30, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen30},
        {40, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen40},
        {60, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen60},
        {80, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen80},
        {100, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen100},
        {120, ::bluetooth::le_audio::codec_spec_conf::kLeAudioCodecFrameLen120}};

std::unordered_map<AudioLocation, uint32_t> audio_location_map{
        {AudioLocation::UNKNOWN,
         ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontCenter},
        {AudioLocation::FRONT_LEFT,
         ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft},
        {AudioLocation::FRONT_RIGHT,
         ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontRight},
        {static_cast<AudioLocation>(static_cast<uint8_t>(AudioLocation::FRONT_LEFT) |
                                    static_cast<uint8_t>(AudioLocation::FRONT_RIGHT)),
         ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontLeft |
                 ::bluetooth::le_audio::codec_spec_conf::kLeAudioLocationFrontRight}};

bool hal_ucast_capability_to_stack_format(const UnicastCapability& hal_capability,
                                          CodecConfigSetting& stack_capability) {
  if (hal_capability.codecType != CodecType::LC3) {
    log::warn("Unsupported codecType: {}", toString(hal_capability.codecType));
    return false;
  }
  if (hal_capability.leAudioCodecCapabilities.getTag() !=
      UnicastCapability::LeAudioCodecCapabilities::lc3Capabilities) {
    log::warn("Unknown LE Audio capabilities(vendor proprietary?)");
    return false;
  }

  auto& hal_lc3_capability =
          hal_capability.leAudioCodecCapabilities
                  .get<UnicastCapability::LeAudioCodecCapabilities::lc3Capabilities>();
  auto supported_channel = hal_capability.supportedChannel;
  auto sample_rate_hz = hal_lc3_capability.samplingFrequencyHz[0];
  auto frame_duration_us = hal_lc3_capability.frameDurationUs[0];
  auto octets_per_frame = hal_lc3_capability.octetsPerFrame[0];
  auto codec_frame_blocks_per_sdu =
          hal_lc3_capability.blocksPerSdu.size() ? hal_lc3_capability.blocksPerSdu[0] : 1;
  auto channel_count = hal_capability.channelCountPerDevice;

  if (sampling_freq_map.find(sample_rate_hz) == sampling_freq_map.end() ||
      frame_duration_map.find(frame_duration_us) == frame_duration_map.end() ||
      octets_per_frame_map.find(octets_per_frame) == octets_per_frame_map.end() ||
      audio_location_map.find(supported_channel) == audio_location_map.end()) {
    log::error(
            "Failed to convert HAL format to stack format\nsample rate hz = "
            "{}\nframe duration us = {}\noctets per frame= {}\nsupported channel = "
            "{}\nchannel count per device = {}\ndevice count = {}",
            sample_rate_hz, frame_duration_us, octets_per_frame, toString(supported_channel),
            channel_count, hal_capability.deviceCount);

    return false;
  }

  stack_capability.id = ::bluetooth::le_audio::types::LeAudioCodecIdLc3;
  stack_capability.channel_count_per_iso_stream = channel_count;

  stack_capability.params.Add(::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                              sampling_freq_map[sample_rate_hz]);
  stack_capability.params.Add(::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                              frame_duration_map[frame_duration_us]);
  stack_capability.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
          audio_location_map[supported_channel]);
  stack_capability.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
          octets_per_frame_map[octets_per_frame]);
  stack_capability.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeCodecFrameBlocksPerSdu,
          uint8_t(codec_frame_blocks_per_sdu));
  return true;
}

static bool hal_bcast_capability_to_stack_format(const BroadcastCapability& hal_bcast_capability,
                                                 CodecConfigSetting& stack_capability) {
  if (hal_bcast_capability.codecType != CodecType::LC3) {
    log::warn("Unsupported codecType: {}", toString(hal_bcast_capability.codecType));
    return false;
  }
  if (hal_bcast_capability.leAudioCodecCapabilities.getTag() !=
      BroadcastCapability::LeAudioCodecCapabilities::lc3Capabilities) {
    log::warn("Unknown LE Audio capabilities(vendor proprietary?)");
    return false;
  }

  auto& hal_lc3_capabilities =
          hal_bcast_capability.leAudioCodecCapabilities
                  .get<BroadcastCapability::LeAudioCodecCapabilities::lc3Capabilities>();

  if (hal_lc3_capabilities->size() != 1) {
    log::warn("The number of config is not supported yet.");
  }

  auto supported_channel = hal_bcast_capability.supportedChannel;
  auto sample_rate_hz = (*hal_lc3_capabilities)[0]->samplingFrequencyHz[0];
  auto frame_duration_us = (*hal_lc3_capabilities)[0]->frameDurationUs[0];
  auto octets_per_frame = (*hal_lc3_capabilities)[0]->octetsPerFrame[0];
  auto channel_count = hal_bcast_capability.channelCountPerStream;

  if (sampling_freq_map.find(sample_rate_hz) == sampling_freq_map.end() ||
      frame_duration_map.find(frame_duration_us) == frame_duration_map.end() ||
      octets_per_frame_map.find(octets_per_frame) == octets_per_frame_map.end() ||
      audio_location_map.find(supported_channel) == audio_location_map.end()) {
    log::warn(
            "Failed to convert HAL format to stack format\nsample rate hz = "
            "{}\nframe duration us = {}\noctets per frame= {}\nsupported channel = "
            "{}\nchannel count per stream = {}",
            sample_rate_hz, frame_duration_us, octets_per_frame, toString(supported_channel),
            channel_count);

    return false;
  }

  stack_capability.id = ::bluetooth::le_audio::types::LeAudioCodecIdLc3;
  stack_capability.channel_count_per_iso_stream = channel_count;

  stack_capability.params.Add(::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeSamplingFreq,
                              sampling_freq_map[sample_rate_hz]);
  stack_capability.params.Add(::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeFrameDuration,
                              frame_duration_map[frame_duration_us]);
  stack_capability.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeAudioChannelAllocation,
          audio_location_map[supported_channel]);
  stack_capability.params.Add(
          ::bluetooth::le_audio::codec_spec_conf::kLeAudioLtvTypeOctetsPerCodecFrame,
          octets_per_frame_map[octets_per_frame]);
  return true;
}

bluetooth::audio::le_audio::OffloadCapabilities get_offload_capabilities() {
  log::info("");
  std::vector<AudioSetConfiguration> offload_capabilities;
  std::vector<AudioSetConfiguration> broadcast_offload_capabilities;
  std::vector<AudioCapabilities> le_audio_hal_capabilities =
          BluetoothAudioSinkClientInterface::GetAudioCapabilities(
                  SessionType::LE_AUDIO_HARDWARE_OFFLOAD_ENCODING_DATAPATH);
  std::string str_capability_log;

  for (auto hal_cap : le_audio_hal_capabilities) {
    CodecConfigSetting encode_cap, decode_cap, bcast_cap;
    UnicastCapability hal_encode_cap =
            hal_cap.get<AudioCapabilities::leAudioCapabilities>().unicastEncodeCapability;
    UnicastCapability hal_decode_cap =
            hal_cap.get<AudioCapabilities::leAudioCapabilities>().unicastDecodeCapability;
    BroadcastCapability hal_bcast_cap =
            hal_cap.get<AudioCapabilities::leAudioCapabilities>().broadcastCapability;
    AudioSetConfiguration audio_set_config = {.name = "offload capability"};
    str_capability_log.clear();

    if (hal_ucast_capability_to_stack_format(hal_encode_cap, encode_cap)) {
      auto ase_cnt = hal_encode_cap.deviceCount * hal_encode_cap.channelCountPerDevice;
      while (ase_cnt--) {
        audio_set_config.confs.sink.push_back(AseConfiguration(encode_cap));
      }
      str_capability_log = " Encode Capability: " + hal_encode_cap.toString();
    }

    if (hal_ucast_capability_to_stack_format(hal_decode_cap, decode_cap)) {
      auto ase_cnt = hal_decode_cap.deviceCount * hal_decode_cap.channelCountPerDevice;
      while (ase_cnt--) {
        audio_set_config.confs.source.push_back(AseConfiguration(decode_cap));
      }
      str_capability_log += " Decode Capability: " + hal_decode_cap.toString();
    }

    if (hal_bcast_capability_to_stack_format(hal_bcast_cap, bcast_cap)) {
      AudioSetConfiguration audio_set_config = {.name = "broadcast offload capability"};
      // Note: The offloader config supports multiple channels per stream
      //       (subgroup), corresponding to the number of BISes, where each BIS
      //       has a single channel.
      bcast_cap.channel_count_per_iso_stream = 1;
      auto bis_cnt = hal_bcast_cap.channelCountPerStream;
      while (bis_cnt--) {
        audio_set_config.confs.sink.push_back(AseConfiguration(bcast_cap));
      }
      broadcast_offload_capabilities.push_back(audio_set_config);
      str_capability_log += " Broadcast Capability: " + hal_bcast_cap.toString();
    }

    if (!audio_set_config.confs.sink.empty() || !audio_set_config.confs.source.empty()) {
      offload_capabilities.push_back(audio_set_config);
      log::info("Supported codec capability ={}", str_capability_log);

    } else {
      log::info("Unknown codec capability ={}", hal_cap.toString());
    }
  }

  return {offload_capabilities, broadcast_offload_capabilities};
}

AudioConfiguration stream_config_to_hal_audio_config(
        const ::bluetooth::le_audio::stream_config& offload_config) {
  LeAudioConfiguration ucast_config = {
          .peerDelayUs = static_cast<int32_t>(offload_config.peer_delay_ms * 1000)};

  if (offload_config.stream_map.size() == 0) {
    log::error("Invalid stream map");
    return AudioConfiguration(ucast_config);
  }

  bool lc3_codec_config_found = false;
  for (auto& info : offload_config.stream_map) {
    if (!lc3_codec_config_found &&
        info.codec_config.id == ::bluetooth::le_audio::types::LeAudioCodecIdLc3) {
      /* For now we have single configuration per directions, so this is enought to use
       * configuration from the streaming cis. Find configuration and copy it.
       */
      log::verbose(
              "Found LC3 config: bits_per_sample: {}, sampling_frequency_hz: {}, "
              "frame_duration_us: {}, octets_per_codec_frame: {}, codec_frames_blocks_per_sdu: {}",
              offload_config.bits_per_sample, offload_config.sampling_frequency_hz,
              offload_config.frame_duration_us, offload_config.octets_per_codec_frame,
              offload_config.codec_frames_blocks_per_sdu);

      Lc3Configuration lc3_config{
              .pcmBitDepth = static_cast<int8_t>(offload_config.bits_per_sample),
              .samplingFrequencyHz = static_cast<int32_t>(offload_config.sampling_frequency_hz),
              .frameDurationUs = static_cast<int32_t>(offload_config.frame_duration_us),
              .octetsPerFrame = static_cast<int32_t>(offload_config.octets_per_codec_frame),
              .blocksPerSdu = static_cast<int8_t>(offload_config.codec_frames_blocks_per_sdu),
      };
      ucast_config.leAudioCodecConfig = LeAudioCodecConfiguration(lc3_config);
      lc3_codec_config_found = true;
    }

    LeAudioConfiguration::StreamMap::BluetoothDeviceAddress aidl_device_address;
    // The address should be set only if stream is active
    if (info.is_stream_active) {
      aidl_device_address.deviceAddress = info.address.ToArray();
      aidl_device_address.deviceAddressType =
              (info.address_type == BLE_ADDR_PUBLIC || info.address_type == BLE_ADDR_PUBLIC_ID)
                      ? LeAudioConfiguration::StreamMap::BluetoothDeviceAddress::DeviceAddressType::
                                BLE_ADDRESS_PUBLIC
                      : LeAudioConfiguration::StreamMap::BluetoothDeviceAddress::DeviceAddressType::
                                BLE_ADDRESS_RANDOM;
    }

    ucast_config.streamMap.push_back({
            .streamHandle = info.stream_handle,
            .audioChannelAllocation = static_cast<int32_t>(info.audio_channel_allocation),
            .isStreamActive = info.is_stream_active,
            .aseConfiguration = GetAidlLeAudioAseConfigurationFromStackFormat(
                    info.codec_config, info.target_latency, info.target_phy, info.metadata),
            .bluetoothDeviceAddress = aidl_device_address,
    });
  }

  if (!lc3_codec_config_found) {
    auto id = offload_config.stream_map.at(0).codec_config.id;
    log::info("Non LC3 Codec config is used. Format: {}, Vendor: {}, Company: {}", id.coding_format,
              id.vendor_codec_id, id.vendor_company_id);
  }
  return AudioConfiguration(ucast_config);
}

AudioConfiguration broadcast_config_to_hal_audio_config(
        const LeAudioBroadcastConfiguration& bcast_config) {
  return AudioConfiguration(bcast_config);
}

}  // namespace le_audio
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
