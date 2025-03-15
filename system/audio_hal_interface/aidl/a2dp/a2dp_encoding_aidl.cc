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
#define LOG_TAG "BTAudioA2dpAIDL"

#include "a2dp_encoding_aidl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <vector>

#include "a2dp_provider_info.h"
#include "audio_aidl_interfaces.h"
#include "client_interface_aidl.h"
#include "codec_status_aidl.h"
#include "transport_instance.h"

typedef enum {
  A2DP_CTRL_CMD_NONE,
  A2DP_CTRL_CMD_CHECK_READY,
  A2DP_CTRL_CMD_START,
  A2DP_CTRL_CMD_STOP,
  A2DP_CTRL_CMD_SUSPEND,
  A2DP_CTRL_GET_INPUT_AUDIO_CONFIG,
  A2DP_CTRL_GET_OUTPUT_AUDIO_CONFIG,
  A2DP_CTRL_SET_OUTPUT_AUDIO_CONFIG,
  A2DP_CTRL_GET_PRESENTATION_POSITION,
} tA2DP_CTRL_CMD;

namespace std {
template <>
struct formatter<tA2DP_CTRL_CMD> : enum_formatter<tA2DP_CTRL_CMD> {};
template <>
struct formatter<audio_usage_t> : enum_formatter<audio_usage_t> {};
template <>
struct formatter<audio_content_type_t> : enum_formatter<audio_content_type_t> {};
}  // namespace std

namespace bluetooth {
namespace audio {
namespace aidl {
namespace a2dp {

namespace {

using ::bluetooth::audio::a2dp::Status;
using ::bluetooth::audio::aidl::a2dp::LatencyMode;

// Provide call-in APIs for the Bluetooth Audio HAL
class A2dpTransport : public ::bluetooth::audio::aidl::a2dp::IBluetoothTransportInstance {
public:
  A2dpTransport(SessionType sessionType);

  Status StartRequest(bool is_low_latency) override;

  Status SuspendRequest() override;

  void StopRequest() override;

  void SetLatencyMode(LatencyMode latency_mode) override;

  bool GetPresentationPosition(uint64_t* remote_delay_report_ns, uint64_t* total_bytes_read,
                               timespec* data_position) override;

  tA2DP_CTRL_CMD GetPendingCmd() const;

  void ResetPendingCmd();

  void ResetPresentationPosition();

  void LogBytesRead(size_t bytes_read) override;

  // delay reports from AVDTP is based on 1/10 ms (100us)
  void SetRemoteDelay(uint16_t delay_report);

private:
  static tA2DP_CTRL_CMD a2dp_pending_cmd_;
  static uint16_t remote_delay_report_;
  uint64_t total_bytes_read_;
  timespec data_position_;
};

}  // namespace

using ::bluetooth::audio::a2dp::Status;
using ::bluetooth::audio::a2dp::StreamCallbacks;

static StreamCallbacks null_stream_callbacks_;
static StreamCallbacks const* stream_callbacks_ = &null_stream_callbacks_;

namespace {

using ::aidl::android::hardware::bluetooth::audio::A2dpStreamConfiguration;
using ::aidl::android::hardware::bluetooth::audio::AudioConfiguration;
using ::aidl::android::hardware::bluetooth::audio::ChannelMode;
using ::aidl::android::hardware::bluetooth::audio::CodecConfiguration;
using ::aidl::android::hardware::bluetooth::audio::PcmConfiguration;
using ::aidl::android::hardware::bluetooth::audio::SessionType;

using ::bluetooth::audio::aidl::a2dp::BluetoothAudioClientInterface;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpAacToHalConfig;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpAptxToHalConfig;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpCodecToHalBitsPerSample;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpCodecToHalChannelMode;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpCodecToHalSampleRate;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpLdacToHalConfig;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpOpusToHalConfig;
using ::bluetooth::audio::aidl::a2dp::codec::A2dpSbcToHalConfig;

/***
 *
 * A2dpTransport functions and variables
 *
 ***/

tA2DP_CTRL_CMD A2dpTransport::a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE;

uint16_t A2dpTransport::remote_delay_report_ = 0;

A2dpTransport::A2dpTransport(SessionType sessionType)
    : IBluetoothTransportInstance(sessionType, (AudioConfiguration){}),
      total_bytes_read_(0),
      data_position_({}) {
  a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE;
  remote_delay_report_ = 0;
}

Status A2dpTransport::StartRequest(bool is_low_latency) {
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

  log::info("");

  auto status = stream_callbacks_->StartStream(is_low_latency);
  a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_START : A2DP_CTRL_CMD_NONE;

  return status;
}

Status A2dpTransport::SuspendRequest() {
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
  log::info("");

  auto status = stream_callbacks_->StopStream();
  a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_STOP : A2DP_CTRL_CMD_NONE;
}

void A2dpTransport::SetLatencyMode(LatencyMode latency_mode) {
  stream_callbacks_->SetLatencyMode(latency_mode == LatencyMode::LOW_LATENCY);
}

bool A2dpTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                            uint64_t* total_bytes_read, timespec* data_position) {
  *remote_delay_report_ns = remote_delay_report_ * 100000u;
  *total_bytes_read = total_bytes_read_;
  *data_position = data_position_;
  log::verbose("delay={}/10ms, data={} byte(s), timestamp={}.{}s", remote_delay_report_,
               total_bytes_read_, data_position_.tv_sec, data_position_.tv_nsec);
  return true;
}

tA2DP_CTRL_CMD A2dpTransport::GetPendingCmd() const { return a2dp_pending_cmd_; }

void A2dpTransport::ResetPendingCmd() { a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE; }

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

/***
 *
 * Global functions and variables
 *
 ***/

// delay reports from AVDTP is based on 1/10 ms (100us)
void A2dpTransport::SetRemoteDelay(uint16_t delay_report) { remote_delay_report_ = delay_report; }

// Common interface to call-out into Bluetooth Audio HAL
BluetoothAudioClientInterface* software_hal_interface = nullptr;
BluetoothAudioClientInterface* offloading_hal_interface = nullptr;
BluetoothAudioClientInterface* active_hal_interface = nullptr;

// ProviderInfo for A2DP hardware offload encoding and decoding data paths,
// if supported by the HAL and enabled. nullptr if not supported
// or disabled.
std::unique_ptr<::bluetooth::audio::aidl::a2dp::ProviderInfo> provider_info;

// Save the value if the remote reports its delay before this interface is
// initialized
uint16_t remote_delay = 0;

bool is_low_latency_mode_allowed = false;

bool a2dp_get_selected_hal_codec_config(A2dpCodecConfig* a2dp_config, uint16_t peer_mtu,
                                        CodecConfiguration* codec_config) {
  btav_a2dp_codec_config_t current_codec = a2dp_config->getCodecConfig();
  switch (current_codec.codec_type) {
    case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
      [[fallthrough]];
    case BTAV_A2DP_CODEC_INDEX_SINK_SBC: {
      if (!A2dpSbcToHalConfig(codec_config, a2dp_config)) {
        return false;
      }
      break;
    }
    case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
      [[fallthrough]];
    case BTAV_A2DP_CODEC_INDEX_SINK_AAC: {
      if (!A2dpAacToHalConfig(codec_config, a2dp_config)) {
        return false;
      }
      break;
    }
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
      [[fallthrough]];
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD: {
      if (!A2dpAptxToHalConfig(codec_config, a2dp_config)) {
        return false;
      }
      break;
    }
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC: {
      if (!A2dpLdacToHalConfig(codec_config, a2dp_config)) {
        return false;
      }
      break;
    }
    case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS: {
      if (!A2dpOpusToHalConfig(codec_config, a2dp_config)) {
        return false;
      }
      break;
    }
    case BTAV_A2DP_CODEC_INDEX_MAX:
      [[fallthrough]];
    default:
      log::error("Unknown codec_type={}", current_codec.codec_type);
      return false;
  }
  codec_config->encodedAudioBitrate = a2dp_config->getTrackBitRate();
  codec_config->peerMtu = peer_mtu;
  log::info("CodecConfiguration={}", codec_config->toString());
  return true;
}

static bool a2dp_get_selected_hal_pcm_config(A2dpCodecConfig* a2dp_codec_configs,
                                             int preferred_encoding_interval_us,
                                             PcmConfiguration* pcm_config) {
  if (pcm_config == nullptr) {
    return false;
  }

  btav_a2dp_codec_config_t current_codec = a2dp_codec_configs->getCodecConfig();
  pcm_config->sampleRateHz = A2dpCodecToHalSampleRate(current_codec);
  pcm_config->bitsPerSample = A2dpCodecToHalBitsPerSample(current_codec);
  pcm_config->channelMode = A2dpCodecToHalChannelMode(current_codec);
  pcm_config->dataIntervalUs = preferred_encoding_interval_us;

  return pcm_config->sampleRateHz > 0 && pcm_config->bitsPerSample > 0 &&
         pcm_config->channelMode != ChannelMode::UNKNOWN;
}

}  // namespace

bool update_codec_offloading_capabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference,
        bool supports_a2dp_hw_offload_v2) {
  /* Load the provider information if supported by the HAL. */
  provider_info = ::bluetooth::audio::aidl::a2dp::ProviderInfo::GetProviderInfo(
          supports_a2dp_hw_offload_v2);
  return ::bluetooth::audio::aidl::a2dp::codec::UpdateOffloadingCapabilities(framework_preference);
}

// Checking if new bluetooth_audio is enabled
bool is_hal_enabled() { return active_hal_interface != nullptr; }

// Check if new bluetooth_audio is running with offloading encoders
bool is_hal_offloading() {
  if (!is_hal_enabled()) {
    return false;
  }
  return active_hal_interface->GetTransportInstance()->GetSessionType() ==
         SessionType::A2DP_HARDWARE_OFFLOAD_ENCODING_DATAPATH;
}

// Opens the HAL client interface of the specified session type and check
// that is is valid. Returns nullptr if the client interface did not open
// properly.
static BluetoothAudioClientInterface* new_hal_interface(SessionType session_type) {
  auto a2dp_transport = new A2dpTransport(session_type);
  auto hal_interface = new BluetoothAudioClientInterface(a2dp_transport);
  if (hal_interface->IsValid()) {
    return hal_interface;
  } else {
    log::error("BluetoothAudio HAL for a2dp is invalid");
    delete a2dp_transport;
    delete hal_interface;
    return nullptr;
  }
}

/// Delete the selected HAL client interface.
static void delete_hal_interface(BluetoothAudioClientInterface* hal_interface) {
  if (hal_interface == nullptr) {
    return;
  }
  auto a2dp_transport = static_cast<A2dpTransport*>(hal_interface->GetTransportInstance());
  delete a2dp_transport;
  delete hal_interface;
}

// Initialize BluetoothAudio HAL: openProvider
bool init(bluetooth::common::MessageLoopThread* /*message_loop*/,
          StreamCallbacks const* stream_callbacks, bool offload_enabled) {
  log::info("");
  log::assert_that(stream_callbacks != nullptr, "stream_callbacks != nullptr");

  if (software_hal_interface != nullptr) {
    return true;
  }

  if (!BluetoothAudioClientInterface::is_aidl_available()) {
    log::error("BluetoothAudio AIDL implementation does not exist");
    return false;
  }

  software_hal_interface = new_hal_interface(SessionType::A2DP_SOFTWARE_ENCODING_DATAPATH);
  if (software_hal_interface == nullptr) {
    return false;
  }

  if (offload_enabled && offloading_hal_interface == nullptr) {
    offloading_hal_interface =
            new_hal_interface(SessionType::A2DP_HARDWARE_OFFLOAD_ENCODING_DATAPATH);
    if (offloading_hal_interface == nullptr) {
      delete_hal_interface(software_hal_interface);
      software_hal_interface = nullptr;
      return false;
    }
  }

  stream_callbacks_ = stream_callbacks;
  active_hal_interface =
          (offloading_hal_interface != nullptr ? offloading_hal_interface : software_hal_interface);

  if (remote_delay != 0) {
    log::info("restore DELAY {} ms", static_cast<float>(remote_delay / 10.0));
    static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())
            ->SetRemoteDelay(remote_delay);
    remote_delay = 0;
  }
  return true;
}

// Clean up BluetoothAudio HAL
void cleanup() {
  if (!is_hal_enabled()) {
    return;
  }
  end_session();

  auto a2dp_sink = active_hal_interface->GetTransportInstance();
  static_cast<A2dpTransport*>(a2dp_sink)->ResetPendingCmd();
  static_cast<A2dpTransport*>(a2dp_sink)->ResetPresentationPosition();
  active_hal_interface = nullptr;

  a2dp_sink = software_hal_interface->GetTransportInstance();
  delete software_hal_interface;
  software_hal_interface = nullptr;
  delete a2dp_sink;
  if (offloading_hal_interface != nullptr) {
    a2dp_sink = offloading_hal_interface->GetTransportInstance();
    delete offloading_hal_interface;
    offloading_hal_interface = nullptr;
    delete a2dp_sink;
  }

  stream_callbacks_ = &null_stream_callbacks_;
  remote_delay = 0;
}

// Set up the codec into BluetoothAudio HAL
bool setup_codec(A2dpCodecConfig* a2dp_config, uint16_t peer_mtu,
                 int preferred_encoding_interval_us) {
  log::assert_that(a2dp_config != nullptr, "received invalid codec configuration");

  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return false;
  }

  if (provider::supports_codec(a2dp_config->codecIndex())) {
    // The codec is supported in the provider info (AIDL v4).
    // In this case, the codec is offloaded, and the configuration passed
    // as A2dpStreamConfiguration to the UpdateAudioConfig() interface
    // method.
    uint8_t codec_info[AVDT_CODEC_SIZE];
    A2dpStreamConfiguration a2dp_stream_configuration;

    a2dp_config->copyOutOtaCodecConfig(codec_info);
    a2dp_stream_configuration.peerMtu = peer_mtu;
    a2dp_stream_configuration.codecId =
            provider_info->GetCodec(a2dp_config->codecIndex()).value()->id;

    size_t parameters_start = 0;
    size_t parameters_end = 0;
    switch (a2dp_config->codecIndex()) {
      case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
      case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
        parameters_start = 3;
        parameters_end = 1 + codec_info[0];
        break;
      default:
        parameters_start = 9;
        parameters_end = 1 + codec_info[0];
        break;
    }

    a2dp_stream_configuration.configuration.insert(a2dp_stream_configuration.configuration.end(),
                                                   codec_info + parameters_start,
                                                   codec_info + parameters_end);

    if (!is_hal_offloading()) {
      log::warn("Switching BluetoothAudio HAL to Hardware");
      end_session();
      active_hal_interface = offloading_hal_interface;
    }

    return active_hal_interface->UpdateAudioConfig(AudioConfiguration(a2dp_stream_configuration));
  }

  // Fallback to legacy offloading path.
  CodecConfiguration codec_config{};

  if (!a2dp_get_selected_hal_codec_config(a2dp_config, peer_mtu, &codec_config)) {
    log::error("Failed to get CodecConfiguration");
    return false;
  }

  bool should_codec_offloading =
          bluetooth::audio::aidl::a2dp::codec::IsCodecOffloadingEnabled(codec_config);
  if (should_codec_offloading && !is_hal_offloading()) {
    log::warn("Switching BluetoothAudio HAL to Hardware");
    end_session();
    active_hal_interface = offloading_hal_interface;
  } else if (!should_codec_offloading && is_hal_offloading()) {
    log::warn("Switching BluetoothAudio HAL to Software");
    end_session();
    active_hal_interface = software_hal_interface;
  }

  AudioConfiguration audio_config{};
  if (active_hal_interface->GetTransportInstance()->GetSessionType() ==
      SessionType::A2DP_HARDWARE_OFFLOAD_ENCODING_DATAPATH) {
    audio_config.set<AudioConfiguration::a2dpConfig>(codec_config);
  } else {
    PcmConfiguration pcm_config{};
    if (!a2dp_get_selected_hal_pcm_config(a2dp_config, preferred_encoding_interval_us,
                                          &pcm_config)) {
      log::error("Failed to get PcmConfiguration");
      return false;
    }
    audio_config.set<AudioConfiguration::pcmConfig>(pcm_config);
  }

  return active_hal_interface->UpdateAudioConfig(audio_config);
}

void start_session() {
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  std::vector<LatencyMode> latency_modes = {LatencyMode::FREE};
  if (is_low_latency_mode_allowed) {
    latency_modes.push_back(LatencyMode::LOW_LATENCY);
  }
  active_hal_interface->SetAllowedLatencyModes(latency_modes);
  active_hal_interface->StartSession();
}

void end_session() {
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  active_hal_interface->EndSession();
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())->ResetPendingCmd();
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())
          ->ResetPresentationPosition();
}

void ack_stream_started(Status ack) {
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  log::info("result={}", ack);
  auto a2dp_sink = static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance());
  auto pending_cmd = a2dp_sink->GetPendingCmd();
  if (pending_cmd == A2DP_CTRL_CMD_START) {
    active_hal_interface->StreamStarted(ack);
  } else {
    log::warn("pending={} ignore result={}", pending_cmd, ack);
    return;
  }
  if (ack != Status::PENDING) {
    a2dp_sink->ResetPendingCmd();
  }
}

void ack_stream_suspended(Status ack) {
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  log::info("result={}", ack);
  auto a2dp_sink = static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance());
  auto pending_cmd = a2dp_sink->GetPendingCmd();
  if (pending_cmd == A2DP_CTRL_CMD_SUSPEND) {
    active_hal_interface->StreamSuspended(ack);
  } else if (pending_cmd == A2DP_CTRL_CMD_STOP) {
    log::info("A2DP_CTRL_CMD_STOP result={}", ack);
  } else {
    log::warn("pending={} ignore result={}", pending_cmd, ack);
    return;
  }
  if (ack != Status::PENDING) {
    a2dp_sink->ResetPendingCmd();
  }
}

// Read from the FMQ of BluetoothAudio HAL
size_t read(uint8_t* p_buf, uint32_t len) {
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return 0;
  }
  if (is_hal_offloading()) {
    log::error("session_type={} is not A2DP_SOFTWARE_ENCODING_DATAPATH",
               toString(active_hal_interface->GetTransportInstance()->GetSessionType()));
    return 0;
  }
  return active_hal_interface->ReadAudioData(p_buf, len);
}

// Update A2DP delay report to BluetoothAudio HAL
void set_remote_delay(uint16_t delay_report) {
  if (!is_hal_enabled()) {
    log::info("not ready for DelayReport {} ms", static_cast<float>(delay_report / 10.0));
    remote_delay = delay_report;
    return;
  }
  log::verbose("DELAY {} ms", static_cast<float>(delay_report / 10.0));
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())
          ->SetRemoteDelay(delay_report);
}

// Set low latency buffer mode allowed or disallowed
void set_low_latency_mode_allowed(bool allowed) {
  is_low_latency_mode_allowed = allowed;
  if (!is_hal_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  std::vector<LatencyMode> latency_modes = {LatencyMode::FREE};
  if (is_low_latency_mode_allowed) {
    latency_modes.push_back(LatencyMode::LOW_LATENCY);
  }
  active_hal_interface->SetAllowedLatencyModes(latency_modes);
}

/***
 * Lookup the codec info in the list of supported offloaded sink codecs.
 ***/
std::optional<btav_a2dp_codec_index_t> provider::sink_codec_index(const uint8_t* p_codec_info) {
  return provider_info ? provider_info->SinkCodecIndex(p_codec_info) : std::nullopt;
}

/***
 * Lookup the codec info in the list of supported offloaded source codecs.
 ***/
std::optional<btav_a2dp_codec_index_t> provider::source_codec_index(const uint8_t* p_codec_info) {
  return provider_info ? provider_info->SourceCodecIndex(p_codec_info) : std::nullopt;
}

/***
 * Return the name of the codec which is assigned to the input index.
 * The codec index must be in the ranges
 * BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SINK_EXT_MAX or
 * BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MAX.
 * Returns nullopt if the codec_index is not assigned or codec extensibility
 * is not supported or enabled.
 ***/
std::optional<const char*> provider::codec_index_str(btav_a2dp_codec_index_t codec_index) {
  return provider_info ? provider_info->CodecIndexStr(codec_index) : std::nullopt;
}

/***
 * Return true if the codec is supported for the session type
 * A2DP_HARDWARE_ENCODING_DATAPATH or A2DP_HARDWARE_DECODING_DATAPATH.
 ***/
bool provider::supports_codec(btav_a2dp_codec_index_t codec_index) {
  return provider_info ? provider_info->SupportsCodec(codec_index) : false;
}

/***
 * Return the A2DP capabilities for the selected codec.
 ***/
bool provider::codec_info(btav_a2dp_codec_index_t codec_index, bluetooth::a2dp::CodecId* codec_id,
                          uint8_t* codec_info, btav_a2dp_codec_config_t* codec_config) {
  return provider_info
                 ? provider_info->CodecCapabilities(codec_index, codec_id, codec_info, codec_config)
                 : false;
}

static btav_a2dp_codec_channel_mode_t convert_channel_mode(ChannelMode channel_mode) {
  switch (channel_mode) {
    case ChannelMode::MONO:
      return BTAV_A2DP_CODEC_CHANNEL_MODE_MONO;
    case ChannelMode::STEREO:
      return BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO;
    default:
      log::error("unknown channel mode");
      break;
  }
  return BTAV_A2DP_CODEC_CHANNEL_MODE_NONE;
}

static btav_a2dp_codec_sample_rate_t convert_sampling_frequency_hz(int sampling_frequency_hz) {
  switch (sampling_frequency_hz) {
    case 44100:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_44100;
    case 48000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_48000;
    case 88200:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_88200;
    case 96000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_96000;
    case 176400:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_176400;
    case 192000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_192000;
    case 16000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_16000;
    case 24000:
      return BTAV_A2DP_CODEC_SAMPLE_RATE_24000;
    default:
      log::error("unknown sampling frequency {}", sampling_frequency_hz);
      break;
  }
  return BTAV_A2DP_CODEC_SAMPLE_RATE_NONE;
}

static btav_a2dp_codec_bits_per_sample_t convert_bitdepth(int bitdepth) {
  switch (bitdepth) {
    case 16:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16;
    case 24:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24;
    case 32:
      return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32;
    default:
      log::error("unknown bit depth {}", bitdepth);
      break;
  }
  return BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE;
}

/***
 * Query the codec selection fromt the audio HAL.
 * The HAL is expected to pick the best audio configuration based on the
 * discovered remote SEPs.
 ***/
std::optional<::bluetooth::audio::a2dp::provider::a2dp_configuration>
provider::get_a2dp_configuration(
        RawAddress peer_address,
        std::vector<::bluetooth::audio::a2dp::provider::a2dp_remote_capabilities> const&
                remote_seps,
        btav_a2dp_codec_config_t const& user_preferences) {
  if (provider_info == nullptr) {
    return std::nullopt;
  }

  using ::aidl::android::hardware::bluetooth::audio::A2dpRemoteCapabilities;
  using ::aidl::android::hardware::bluetooth::audio::CodecId;

  // Convert the remote audio capabilities to the exchange format used
  // by the HAL.
  std::vector<A2dpRemoteCapabilities> a2dp_remote_capabilities;
  for (auto const& sep : remote_seps) {
    size_t capabilities_start = 0;
    size_t capabilities_end = 0;
    CodecId id;
    switch (sep.capabilities[2]) {
      case A2DP_MEDIA_CT_SBC:
      case A2DP_MEDIA_CT_AAC: {
        id = CodecId::make<CodecId::a2dp>(static_cast<CodecId::A2dp>(sep.capabilities[2]));
        capabilities_start = 3;
        capabilities_end = 1 + sep.capabilities[0];
        break;
      }
      case A2DP_MEDIA_CT_NON_A2DP: {
        uint32_t vendor_id = (static_cast<uint32_t>(sep.capabilities[3]) << 0) |
                             (static_cast<uint32_t>(sep.capabilities[4]) << 8) |
                             (static_cast<uint32_t>(sep.capabilities[5]) << 16) |
                             (static_cast<uint32_t>(sep.capabilities[6]) << 24);
        uint16_t codec_id = (static_cast<uint16_t>(sep.capabilities[7]) << 0) |
                            (static_cast<uint16_t>(sep.capabilities[8]) << 8);
        id = CodecId::make<CodecId::vendor>(
                CodecId::Vendor({.id = (int32_t)vendor_id, .codecId = codec_id}));
        capabilities_start = 9;
        capabilities_end = 1 + sep.capabilities[0];
        break;
      }
      default:
        continue;
    }
    A2dpRemoteCapabilities& capabilities = a2dp_remote_capabilities.emplace_back();
    capabilities.seid = sep.seid;
    capabilities.id = id;
    capabilities.capabilities.insert(capabilities.capabilities.end(),
                                     sep.capabilities + capabilities_start,
                                     sep.capabilities + capabilities_end);
  }

  // Convert the user preferences into a configuration hint.
  A2dpConfigurationHint hint;
  hint.bdAddr = peer_address.ToArray();
  auto& codecParameters = hint.codecParameters.emplace();
  switch (user_preferences.channel_mode) {
    case BTAV_A2DP_CODEC_CHANNEL_MODE_MONO:
      codecParameters.channelMode = ChannelMode::MONO;
      break;
    case BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO:
      codecParameters.channelMode = ChannelMode::STEREO;
      break;
    default:
      break;
  }
  switch (user_preferences.sample_rate) {
    case BTAV_A2DP_CODEC_SAMPLE_RATE_44100:
      codecParameters.samplingFrequencyHz = 44100;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_48000:
      codecParameters.samplingFrequencyHz = 48000;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_88200:
      codecParameters.samplingFrequencyHz = 88200;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_96000:
      codecParameters.samplingFrequencyHz = 96000;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_176400:
      codecParameters.samplingFrequencyHz = 176400;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_192000:
      codecParameters.samplingFrequencyHz = 192000;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_16000:
      codecParameters.samplingFrequencyHz = 16000;
      break;
    case BTAV_A2DP_CODEC_SAMPLE_RATE_24000:
      codecParameters.samplingFrequencyHz = 24000;
      break;
    default:
      break;
  }
  switch (user_preferences.bits_per_sample) {
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16:
      codecParameters.bitdepth = 16;
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24:
      codecParameters.bitdepth = 24;
      break;
    case BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32:
      codecParameters.bitdepth = 32;
      break;
    default:
      break;
  }

  auto codec = provider_info->GetCodec(user_preferences.codec_type);
  if (codec.has_value()) {
    hint.codecId = codec.value()->id;
  }

  log::info("remote capabilities:");
  for (auto const& sep : a2dp_remote_capabilities) {
    log::info("- {}", sep.toString());
  }
  log::info("hint: {}", hint.toString());

  if (offloading_hal_interface == nullptr &&
      (offloading_hal_interface = new_hal_interface(
               SessionType::A2DP_HARDWARE_OFFLOAD_ENCODING_DATAPATH)) == nullptr) {
    log::error("the offloading HAL interface cannot be opened");
    return std::nullopt;
  }

  // Invoke the HAL GetAdpCapabilities method with the
  // remote capabilities.
  auto result = offloading_hal_interface->GetA2dpConfiguration(a2dp_remote_capabilities, hint);

  // Convert the result configuration back to the stack's format.
  if (!result.has_value()) {
    log::info("provider cannot resolve the a2dp configuration");
    return std::nullopt;
  }

  log::info("provider selected {}", result->toString());

  ::bluetooth::audio::a2dp::provider::a2dp_configuration a2dp_configuration;
  a2dp_configuration.remote_seid = result->remoteSeid;
  a2dp_configuration.vendor_specific_parameters = result->parameters.vendorSpecificParameters;
  ProviderInfo::BuildCodecCapabilities(result->id, result->configuration,
                                       a2dp_configuration.codec_config);
  a2dp_configuration.codec_parameters.codec_type =
          provider_info->SourceCodecIndex(result->id).value();
  a2dp_configuration.codec_parameters.channel_mode =
          convert_channel_mode(result->parameters.channelMode);
  a2dp_configuration.codec_parameters.sample_rate =
          convert_sampling_frequency_hz(result->parameters.samplingFrequencyHz);
  a2dp_configuration.codec_parameters.bits_per_sample =
          convert_bitdepth(result->parameters.bitdepth);

  return std::make_optional(a2dp_configuration);
}

/***
 * Query the codec parameters from the audio HAL.
 * The HAL is expected to parse the codec configuration
 * received from the peer and decide whether accept
 * the it or not.
 ***/
tA2DP_STATUS provider::parse_a2dp_configuration(btav_a2dp_codec_index_t codec_index,
                                                const uint8_t* codec_info,
                                                btav_a2dp_codec_config_t* codec_parameters,
                                                std::vector<uint8_t>* vendor_specific_parameters) {
  std::vector<uint8_t> configuration;
  CodecParameters codec_parameters_aidl;

  if (provider_info == nullptr) {
    log::error("provider_info is null");
    return A2DP_FAIL;
  }

  auto codec = provider_info->GetCodec(codec_index);
  if (!codec.has_value()) {
    log::error("codec index not recognized by provider");
    return A2DP_FAIL;
  }

  std::copy(codec_info, codec_info + AVDT_CODEC_SIZE, std::back_inserter(configuration));

  auto a2dp_status = offloading_hal_interface->ParseA2dpConfiguration(
          codec.value()->id, configuration, &codec_parameters_aidl);

  if (!a2dp_status.has_value()) {
    log::error("provider failed to parse configuration");
    return A2DP_FAIL;
  }

  if (codec_parameters != nullptr) {
    codec_parameters->channel_mode = convert_channel_mode(codec_parameters_aidl.channelMode);
    codec_parameters->sample_rate =
            convert_sampling_frequency_hz(codec_parameters_aidl.samplingFrequencyHz);
    codec_parameters->bits_per_sample = convert_bitdepth(codec_parameters_aidl.bitdepth);
  }

  if (vendor_specific_parameters != nullptr) {
    *vendor_specific_parameters = codec_parameters_aidl.vendorSpecificParameters;
  }

  return static_cast<tA2DP_STATUS>(a2dp_status.value());
}

}  // namespace a2dp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
