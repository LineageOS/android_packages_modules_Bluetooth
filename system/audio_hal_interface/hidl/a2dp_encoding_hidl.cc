/*
 * Copyright 2019 The Android Open Source Project
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
#define LOG_TAG "BTAudioA2dpHIDL"

#include "a2dp_encoding_hidl.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <vector>

#include "client_interface_hidl.h"
#include "codec_status_hidl.h"
#include "osi/include/properties.h"
#include "stack/include/a2dp_sbc_constants.h"

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
namespace hidl {
namespace a2dp {

static bluetooth::audio::a2dp::StreamCallbacks null_stream_callbacks;
static bluetooth::audio::a2dp::StreamCallbacks const* stream_callbacks_ = &null_stream_callbacks;

namespace {

using ::bluetooth::audio::hidl::AudioConfiguration;
using ::bluetooth::audio::hidl::BluetoothAudioCtrlAck;
using ::bluetooth::audio::hidl::PcmParameters;
using ::bluetooth::audio::hidl::SessionType;

using ::bluetooth::audio::hidl::BluetoothAudioSinkClientInterface;
using ::bluetooth::audio::hidl::codec::CodecConfiguration;

using ::bluetooth::audio::a2dp::Status;

static BluetoothAudioCtrlAck a2dp_ack_to_bt_audio_ctrl_ack(Status ack) {
  switch (ack) {
    case Status::SUCCESS:
      return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
    case Status::PENDING:
      return BluetoothAudioCtrlAck::PENDING;
    case Status::UNSUPPORTED_CODEC_CONFIGURATION:
      return BluetoothAudioCtrlAck::FAILURE_UNSUPPORTED;
    case Status::UNKNOWN:
    case Status::FAILURE:
    default:
      return BluetoothAudioCtrlAck::FAILURE;
  }
}

// Provide call-in APIs for the Bluetooth Audio HAL
class A2dpTransport : public ::bluetooth::audio::hidl::IBluetoothSinkTransportInstance {
public:
  A2dpTransport(SessionType sessionType)
      : IBluetoothSinkTransportInstance(sessionType, (AudioConfiguration){}),
        total_bytes_read_(0),
        data_position_({}) {
    a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE;
    remote_delay_report_ = 0;
  }

  BluetoothAudioCtrlAck StartRequest() override {
    // Check if a previous Start request is ongoing.
    if (a2dp_pending_cmd_ == A2DP_CTRL_CMD_START) {
      log::warn("unable to start stream: already pending");
      return BluetoothAudioCtrlAck::PENDING;
    }

    // Check if a different request is ongoing.
    if (a2dp_pending_cmd_ != A2DP_CTRL_CMD_NONE) {
      log::warn("unable to start stream: busy with pending command {}", a2dp_pending_cmd_);
      return BluetoothAudioCtrlAck::FAILURE;
    }

    log::info("");

    auto status = stream_callbacks_->StartStream(false);
    a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_START : A2DP_CTRL_CMD_NONE;

    return a2dp_ack_to_bt_audio_ctrl_ack(status);
  }

  BluetoothAudioCtrlAck SuspendRequest() override {
    // Check if a previous Suspend request is ongoing.
    if (a2dp_pending_cmd_ == A2DP_CTRL_CMD_SUSPEND) {
      log::warn("unable to suspend stream: already pending");
      return BluetoothAudioCtrlAck::PENDING;
    }

    // Check if a different request is ongoing.
    if (a2dp_pending_cmd_ != A2DP_CTRL_CMD_NONE) {
      log::warn("unable to suspend stream: busy with pending command {}", a2dp_pending_cmd_);
      return BluetoothAudioCtrlAck::FAILURE;
    }

    log::info("");

    auto status = stream_callbacks_->SuspendStream();
    a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_SUSPEND : A2DP_CTRL_CMD_NONE;

    return a2dp_ack_to_bt_audio_ctrl_ack(status);
  }

  void StopRequest() override {
    log::info("");

    auto status = stream_callbacks_->SuspendStream();
    a2dp_pending_cmd_ = status == Status::PENDING ? A2DP_CTRL_CMD_STOP : A2DP_CTRL_CMD_NONE;
  }

  bool GetPresentationPosition(uint64_t* remote_delay_report_ns, uint64_t* total_bytes_read,
                               timespec* data_position) override {
    *remote_delay_report_ns = remote_delay_report_ * 100000u;
    *total_bytes_read = total_bytes_read_;
    *data_position = data_position_;
    log::verbose("delay={}/10ms, data={} byte(s), timestamp={}.{}s", remote_delay_report_,
                 total_bytes_read_, data_position_.tv_sec, data_position_.tv_nsec);
    return true;
  }

  void MetadataChanged(const source_metadata_t& source_metadata) override {
    auto track_count = source_metadata.track_count;
    auto tracks = source_metadata.tracks;
    log::verbose("{} track(s) received", track_count);
    while (track_count) {
      log::verbose("usage={}, content_type={}, gain={}", tracks->usage, tracks->content_type,
                   tracks->gain);
      --track_count;
      ++tracks;
    }
  }

  tA2DP_CTRL_CMD GetPendingCmd() const { return a2dp_pending_cmd_; }

  void ResetPendingCmd() { a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE; }

  void ResetPresentationPosition() override {
    remote_delay_report_ = 0;
    total_bytes_read_ = 0;
    data_position_ = {};
  }

  void LogBytesRead(size_t bytes_read) override {
    if (bytes_read != 0) {
      total_bytes_read_ += bytes_read;
      clock_gettime(CLOCK_MONOTONIC, &data_position_);
    }
  }

  // delay reports from AVDTP is based on 1/10 ms (100us)
  void SetRemoteDelay(uint16_t delay_report) { remote_delay_report_ = delay_report; }

private:
  static tA2DP_CTRL_CMD a2dp_pending_cmd_;
  static uint16_t remote_delay_report_;
  uint64_t total_bytes_read_;
  timespec data_position_;
};

tA2DP_CTRL_CMD A2dpTransport::a2dp_pending_cmd_ = A2DP_CTRL_CMD_NONE;
uint16_t A2dpTransport::remote_delay_report_ = 0;

// Common interface to call-out into Bluetooth Audio HAL
BluetoothAudioSinkClientInterface* software_hal_interface = nullptr;
BluetoothAudioSinkClientInterface* offloading_hal_interface = nullptr;
BluetoothAudioSinkClientInterface* active_hal_interface = nullptr;

// Save the value if the remote reports its delay before this interface is
// initialized
uint16_t remote_delay = 0;

}  // namespace

bool update_codec_offloading_capabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference) {
  return ::bluetooth::audio::hidl::codec::UpdateOffloadingCapabilities(framework_preference);
}

// Checking if new bluetooth_audio is enabled
bool is_hal_2_0_enabled() { return active_hal_interface != nullptr; }

// Check if new bluetooth_audio is running with offloading encoders
bool is_hal_2_0_offloading() {
  if (!is_hal_2_0_enabled()) {
    return false;
  }
  return active_hal_interface->GetTransportInstance()->GetSessionType() ==
         SessionType::A2DP_HARDWARE_OFFLOAD_DATAPATH;
}

// Initialize BluetoothAudio HAL: openProvider
bool init(bluetooth::common::MessageLoopThread* message_loop,
          bluetooth::audio::a2dp::StreamCallbacks const* stream_callbacks, bool offload_enabled) {
  log::info("");
  log::assert_that(stream_callbacks != nullptr, "stream_callbacks != nullptr");

  auto a2dp_sink = new A2dpTransport(SessionType::A2DP_SOFTWARE_ENCODING_DATAPATH);
  software_hal_interface = new BluetoothAudioSinkClientInterface(a2dp_sink, message_loop);
  if (!software_hal_interface->IsValid()) {
    log::warn("BluetoothAudio HAL for A2DP is invalid?!");
    delete software_hal_interface;
    software_hal_interface = nullptr;
    delete a2dp_sink;
    return false;
  }

  if (offload_enabled) {
    a2dp_sink = new A2dpTransport(SessionType::A2DP_HARDWARE_OFFLOAD_DATAPATH);
    offloading_hal_interface = new BluetoothAudioSinkClientInterface(a2dp_sink, message_loop);
    if (!offloading_hal_interface->IsValid()) {
      log::fatal("BluetoothAudio HAL for A2DP offloading is invalid?!");
      delete offloading_hal_interface;
      offloading_hal_interface = nullptr;
      delete a2dp_sink;
      a2dp_sink = static_cast<A2dpTransport*>(software_hal_interface->GetTransportInstance());
      delete software_hal_interface;
      software_hal_interface = nullptr;
      delete a2dp_sink;
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
  if (!is_hal_2_0_enabled()) {
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

  stream_callbacks_ = &null_stream_callbacks;
  remote_delay = 0;
}

// Set up the codec into BluetoothAudio HAL
bool setup_codec(const ::bluetooth::audio::a2dp::ahal_codec_configuration& config) {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return false;
  }

  AudioConfiguration audio_config{};
  CodecConfiguration codec_config{};
  PcmParameters pcm_config{};

  // Compute the codec configuration for the hardware encoding session and
  // check if the parameters are supported.
  if (codec::getHalCodecConfiguration(config, &codec_config)) {
    if (!is_hal_2_0_offloading()) {
      log::info("Switching BluetoothAudio HAL to Hardware");
      end_session();
      active_hal_interface = offloading_hal_interface;
    }

    audio_config.codecConfig(codec_config);
    return active_hal_interface->UpdateAudioConfig(audio_config);
  }

  // Compute the PCM configuration for the software encoding session and
  // check if the parameters are supported.
  if (codec::getHalPcmConfiguration(config, &pcm_config)) {
    if (is_hal_2_0_offloading()) {
      log::info("Switching BluetoothAudio HAL to Software");
      end_session();
      active_hal_interface = software_hal_interface;
    }
    audio_config.pcmConfig(pcm_config);
    return active_hal_interface->UpdateAudioConfig(audio_config);
  }

  log::error(
          "The codec configuration cannot be set for either"
          " software or hardware sessions:\n{}",
          config.ToString());
  return false;
}

void start_session() {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  active_hal_interface->StartSession();
}

void end_session() {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  active_hal_interface->EndSession();
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())->ResetPendingCmd();
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())
          ->ResetPresentationPosition();
}

void ack_stream_started(Status ack) {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  log::info("result={}", ack);
  auto a2dp_sink = static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance());
  auto pending_cmd = a2dp_sink->GetPendingCmd();
  if (pending_cmd == A2DP_CTRL_CMD_START) {
    active_hal_interface->StreamStarted(a2dp_ack_to_bt_audio_ctrl_ack(ack));
  } else {
    log::warn("pending={} ignore result={}", pending_cmd, ack);
    return;
  }
  if (ack != Status::PENDING) {
    a2dp_sink->ResetPendingCmd();
  }
}

void ack_stream_suspended(Status ack) {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  log::info("result={}", ack);
  auto a2dp_sink = static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance());
  auto pending_cmd = a2dp_sink->GetPendingCmd();
  if (pending_cmd == A2DP_CTRL_CMD_SUSPEND) {
    active_hal_interface->StreamSuspended(a2dp_ack_to_bt_audio_ctrl_ack(ack));
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
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return 0;
  }
  if (is_hal_2_0_offloading()) {
    log::error("session_type={} is not A2DP_SOFTWARE_ENCODING_DATAPATH",
               toString(active_hal_interface->GetTransportInstance()->GetSessionType()));
    return 0;
  }
  return active_hal_interface->ReadAudioData(p_buf, len);
}

// Read from the FMQ of BluetoothAudio HAL
void flush_source() {
  if (!is_hal_2_0_enabled()) {
    log::error("BluetoothAudio HAL is not enabled");
    return;
  }
  if (is_hal_2_0_offloading()) {
    log::error("session_type={} is not A2DP_SOFTWARE_ENCODING_DATAPATH",
               toString(active_hal_interface->GetTransportInstance()->GetSessionType()));
    return;
  }
  return active_hal_interface->FlushAudioData();
}

// Update A2DP delay report to BluetoothAudio HAL
void set_remote_delay(uint16_t delay_report) {
  if (!is_hal_2_0_enabled()) {
    log::info("not ready for DelayReport {} ms", static_cast<float>(delay_report / 10.0));
    remote_delay = delay_report;
    return;
  }
  log::verbose("DELAY {} ms", static_cast<float>(delay_report / 10.0));
  static_cast<A2dpTransport*>(active_hal_interface->GetTransportInstance())
          ->SetRemoteDelay(delay_report);
}

}  // namespace a2dp
}  // namespace hidl
}  // namespace audio
}  // namespace bluetooth
