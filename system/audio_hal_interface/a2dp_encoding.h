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

#pragma once

#include <iomanip>
#include <sstream>
#include <vector>

#include "common/message_loop_thread.h"
#include "hardware/bt_av.h"
#include "stack/include/a2dp_codec_api.h"
#include "stack/include/a2dp_constants.h"
#include "stack/include/avdt_api.h"

namespace bluetooth {
namespace audio {
namespace a2dp {

/// Loosely copied after the definition from the Bluetooth Audio interface:
/// audio/aidl/android/hardware/bluetooth/audio/BluetoothAudioStatus.aidl
enum class Status {
  SUCCESS = 0,
  UNKNOWN,
  UNSUPPORTED_CODEC_CONFIGURATION,
  FAILURE,
  PENDING,
  RECONFIGURATION,
};

/// Loosely copied after the definition from the Bluetooth Audio interface:
/// audio/aidl/android/hardware/bluetooth/audio/IBluetoothAudioPort.aidl
///
/// Implements callbacks for the BT Audio HAL to start, suspend and configure
/// the audio stream. Completion of the requested operation is indicated
/// by the methods ack_stream_started, ack_stream_suspended.
///
/// The callbacks are always invoked from one of the binder threads.
class StreamCallbacks {
public:
  virtual ~StreamCallbacks() {}
  virtual Status StartStream(bool /*low_latency*/) const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
  virtual Status SuspendStream() const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
  virtual Status StopStream() const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
  virtual Status SetLatencyMode(bool /*low_latency*/) const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
  virtual Status SourceMetadataChanged(btav_a2dp_codec_audio_context_t /*audio_context*/) const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
  virtual Status UpdateSinkLatency(int64_t /* latency_ms */) const {
    log::error("unimplemented");
    return Status::FAILURE;
  }
};

struct ahal_codec_configuration {
  uint16_t peer_mtu;
  int preferred_encoding_interval_us;
  int codec_bitrate;
  uint8_t codec_specific_information_elements[AVDT_CODEC_SIZE];
  btav_a2dp_codec_config_t codec_config;

  std::string ToString() const;
};

bool update_codec_offloading_capabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference,
        bool supports_a2dp_hw_offload_v2);

// Check if new bluetooth_audio is enabled
bool is_hal_enabled();

// Check if new bluetooth_audio is running with offloading encoders
bool is_hal_offloading();

// Initialize BluetoothAudio HAL: openProvider
bool init(bluetooth::common::MessageLoopThread* message_loop,
          StreamCallbacks const* strean_callbacks, bool offload_enabled);

// Initialize BluetoothAudio HAL for decoding session
bool init_decoder(StreamCallbacks const* stream_callbacks, bool offload_enabled);

// Clean up BluetoothAudio HAL
void cleanup();

// Set up the codec into BluetoothAudio HAL
bool setup_codec(const ahal_codec_configuration& config);

// Set low latency buffer mode allowed or disallowed
void set_audio_low_latency_mode_allowed(bool allowed);

// Send command to the BluetoothAudio HAL: StartSession, EndSession,
// StreamStarted, StreamSuspended
void start_session();
void end_session();
void ack_stream_started(Status status);
void ack_stream_suspended(Status status);

// Read from the FMQ of BluetoothAudio HAL
size_t read(uint8_t* p_buf, uint32_t len);

// Clear the audio FMQ.
void flush_source();

// Update A2DP delay report to BluetoothAudio HAL
void set_remote_delay(uint16_t delay_report);

// Check whether OPUS is supported
bool is_opus_supported();

// Definitions for A2DP hardware offload codec extensibility.
namespace provider {

// Lookup the codec info in the list of supported offloaded sink codecs.
std::optional<btav_a2dp_codec_index_t> sink_codec_index(const uint8_t* p_codec_info);

// Lookup the codec info in the list of supported offloaded source codecs.
std::optional<btav_a2dp_codec_index_t> source_codec_index(const uint8_t* p_codec_info);

// Return the name of the codec which is assigned to the input index.
// The codec index must be in the ranges
// BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SINK_EXT_MAX or
// BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MAX.
// Returns nullopt if the codec_index is not assigned or codec extensibility
// is not supported or enabled.
std::optional<const char*> codec_index_str(btav_a2dp_codec_index_t codec_index);

// Return true if the codec is supported for the session type
// A2DP_HARDWARE_ENCODING_DATAPATH or A2DP_HARDWARE_DECODING_DATAPATH.
bool supports_codec(btav_a2dp_codec_index_t codec_index);

// Return the A2DP capabilities for the selected codec.
// `codec_info` returns the OTA codec capabilities, `codec_config`
// returns the supported capabilities in a generic format.
bool codec_info(btav_a2dp_codec_index_t codec_index, bluetooth::a2dp::CodecId* codec_id,
                uint8_t* codec_info, btav_a2dp_codec_config_t* codec_config);

struct a2dp_configuration {
  int remote_seid;
  uint8_t codec_config[AVDT_CODEC_SIZE];
  btav_a2dp_codec_config_t codec_parameters;
  std::vector<uint8_t> vendor_specific_parameters;

  inline std::string toString() const {
    std::ostringstream os;
    os << "A2dpConfiguration{";
    os << "remote_seid: " << remote_seid;
    os << ", codec_index: " << codec_parameters.codec_type;
    os << ", codec_config: {";
    for (int i = 0; i < AVDT_CODEC_SIZE; i++) {
      os << "0x" << std::hex << std::setw(2) << std::setfill('0')
         << static_cast<int>(codec_config[i]);
      if (i != AVDT_CODEC_SIZE - 1) {
        os << ",";
      }
    }
    os << "}";
    os << "}";
    return os.str();
  }
};

struct a2dp_remote_capabilities {
  int seid;
  uint8_t const* capabilities;

  inline std::string toString() const {
    std::ostringstream os;
    os << "A2dpRemoteCapabilities{";
    os << "seid: " << seid;
    os << ", capabilities: {";
    if (capabilities != nullptr) {
      for (int i = 0; i < AVDT_CODEC_SIZE; i++) {
        os << "0x" << std::hex << std::setw(2) << std::setfill('0')
           << static_cast<int>(capabilities[i]);
        if (i != AVDT_CODEC_SIZE - 1) {
          os << ",";
        }
      }
    }
    os << "}";
    os << "}";
    return os.str();
  }
};

// Query the codec selection fromt the audio HAL.
// The HAL is expected to pick the best audio configuration based on the
// discovered remote SEPs.
std::optional<a2dp_configuration> get_a2dp_configuration(
        RawAddress peer_address, std::vector<a2dp_remote_capabilities> const& remote_seps,
        btav_a2dp_codec_config_t const& user_preferences,
        std::optional<::bluetooth::a2dp::CodecId> user_preferred_codec_id, bool is_source);

// Query the codec parameters from the audio HAL.
// The HAL is expected to parse the codec configuration
// received from the peer and decide whether accept
// the it or not.
tA2DP_STATUS parse_a2dp_configuration(::bluetooth::a2dp::CodecId codec_id,
                                      const uint8_t* codec_info,
                                      btav_a2dp_codec_config_t* codec_parameters,
                                      std::vector<uint8_t>* vendor_specific_parameters);

}  // namespace provider

std::optional<btav_a2dp_hal_provider_info_t> get_provider_info();

}  // namespace a2dp
}  // namespace audio
}  // namespace bluetooth

namespace std {
template <>
struct formatter<::bluetooth::audio::a2dp::Status>
    : enum_formatter<::bluetooth::audio::a2dp::Status> {};
}  // namespace std
