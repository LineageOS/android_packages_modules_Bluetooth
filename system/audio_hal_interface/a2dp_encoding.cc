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

#include "a2dp_encoding.h"

#include <vector>

#include "aidl/a2dp/a2dp_encoding_aidl.h"
#include "hal_version_manager.h"
#include "hardware/bt_av.h"
#include "hidl/a2dp_encoding_hidl.h"

namespace bluetooth {
namespace audio {
namespace a2dp {

std::string ahal_codec_configuration::ToString() const {
  std::string result_string;
  auto out = std::back_inserter(result_string);

  std::format_to(out,
                 "ahal_codec_configuration: {{\n"
                 "  codec_config: {{ {} }}\n"
                 "  peer_mtu: {}\n"
                 "  preferred_encoding_interval_us: {}\n"
                 "  codec_bitrate: {}\n",
                 codec_config.ToString(), peer_mtu, preferred_encoding_interval_us, codec_bitrate);

  std::format_to(out, "  codec_specific_information_elements: [\n    ");

  for (size_t i = 0; i < AVDT_CODEC_SIZE; ++i) {
    std::format_to(out, "0x{:02x}",
                   static_cast<unsigned int>(codec_specific_information_elements[i]));
    if (i < AVDT_CODEC_SIZE - 1) {
      std::format_to(out, ", ");
      if ((i + 1) % 8 == 0) {
        std::format_to(out, "\n    ");
      }
    }
  }

  std::format_to(out, "\n  ]");
  std::format_to(out, "\n}}");

  return result_string;
}

bool update_codec_offloading_capabilities(
        const std::vector<btav_a2dp_codec_config_t>& framework_preference,
        bool supports_a2dp_hw_offload_v2) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::update_codec_offloading_capabilities(framework_preference);
  }
  return aidl::a2dp::update_codec_offloading_capabilities(framework_preference,
                                                          supports_a2dp_hw_offload_v2);
}

// Check if new bluetooth_audio is enabled
bool is_hal_enabled() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::is_hal_2_0_enabled();
  }
  return aidl::a2dp::is_hal_enabled();
}

// Check if new bluetooth_audio is running with offloading encoders
bool is_hal_offloading() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::is_hal_2_0_offloading();
  }
  return aidl::a2dp::is_hal_offloading();
}

// Initialize BluetoothAudio HAL: openProvider
bool init(bluetooth::common::MessageLoopThread* message_loop,
          bluetooth::audio::a2dp::StreamCallbacks const* stream_callbacks, bool offload_enabled) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::init(message_loop, stream_callbacks, offload_enabled);
  }
  return aidl::a2dp::init(message_loop, stream_callbacks, offload_enabled);
}

// Initialize BluetoothAudio HAL for decoding session
bool init_decoder(bluetooth::audio::a2dp::StreamCallbacks const* stream_callbacks,
                  bool offload_enabled) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    return aidl::a2dp::init_decoder(stream_callbacks, offload_enabled);
  }
  return false;
}

// Clean up BluetoothAudio HAL
void cleanup() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::cleanup();
    return;
  }
  aidl::a2dp::cleanup();
}

// Set up the codec into BluetoothAudio HAL
bool setup_codec(const ahal_codec_configuration& config) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::setup_codec(config);
  }
  return aidl::a2dp::setup_codec(config);
}

// Send command to the BluetoothAudio HAL: StartSession, EndSession,
// StreamStarted, StreamSuspended
void start_session() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::start_session();
    return;
  }
  aidl::a2dp::start_session();
}

void end_session() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    return aidl::a2dp::end_session();
  }
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::end_session();
    return;
  }
}

void ack_stream_started(Status status) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::ack_stream_started(status);
    return;
  }
  return aidl::a2dp::ack_stream_started(status);
}

void ack_stream_suspended(Status status) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::ack_stream_suspended(status);
    return;
  }
  aidl::a2dp::ack_stream_suspended(status);
}

// Read from the FMQ of BluetoothAudio HAL
size_t read(uint8_t* p_buf, uint32_t len) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::read(p_buf, len);
  }
  return aidl::a2dp::read(p_buf, len);
}

// Clear the audio FMQ.
void flush_source() {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    return hidl::a2dp::flush_source();
  }
  return aidl::a2dp::flush_source();
}

// Update A2DP delay report to BluetoothAudio HAL
void set_remote_delay(uint16_t delay_report) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::HIDL) {
    hidl::a2dp::set_remote_delay(delay_report);
    return;
  }
  aidl::a2dp::set_remote_delay(delay_report);
}

// Set low latency buffer mode allowed or disallowed
void set_audio_low_latency_mode_allowed(bool allowed) {
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    aidl::a2dp::set_low_latency_mode_allowed(allowed);
  }
}

// Check if OPUS codec is supported
bool is_opus_supported() {
  // OPUS codec was added after HIDL HAL was frozen
  if (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL) {
    return true;
  }
  return false;
}

namespace provider {

// Lookup the codec info in the list of supported offloaded sink codecs.
std::optional<btav_a2dp_codec_index_t> sink_codec_index(const uint8_t* p_codec_info) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::sink_codec_index(p_codec_info)
                 : std::nullopt;
}

// Lookup the codec info in the list of supported offloaded source codecs.
std::optional<btav_a2dp_codec_index_t> source_codec_index(const uint8_t* p_codec_info) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::source_codec_index(p_codec_info)
                 : std::nullopt;
}

// Return the name of the codec which is assigned to the input index.
// The codec index must be in the ranges
// BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SINK_EXT_MAX or
// BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN..BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MAX.
// Returns nullopt if the codec_index is not assigned or codec extensibility
// is not supported or enabled.
std::optional<const char*> codec_index_str(btav_a2dp_codec_index_t codec_index) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::codec_index_str(codec_index)
                 : std::nullopt;
}

// Return true if the codec is supported for the session type
// A2DP_HARDWARE_ENCODING_DATAPATH or A2DP_HARDWARE_DECODING_DATAPATH.
bool supports_codec(btav_a2dp_codec_index_t codec_index) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::supports_codec(codec_index)
                 : false;
}

// Return the A2DP capabilities for the selected codec.
bool codec_info(btav_a2dp_codec_index_t codec_index, bluetooth::a2dp::CodecId* codec_id,
                uint8_t* codec_info, btav_a2dp_codec_config_t* codec_config) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::codec_info(codec_index, codec_id, codec_info, codec_config)
                 : false;
}

// Query the codec selection fromt the audio HAL.
// The HAL is expected to pick the best audio configuration based on the
// discovered remote SEPs.
std::optional<a2dp_configuration> get_a2dp_configuration(
        RawAddress peer_address, std::vector<a2dp_remote_capabilities> const& remote_seps,
        btav_a2dp_codec_config_t const& user_preferences,
        std::optional<::bluetooth::a2dp::CodecId> user_preferred_codec_id, bool is_source) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::get_a2dp_configuration(peer_address, remote_seps,
                                                                user_preferences,
                                                                user_preferred_codec_id, is_source)
                 : std::nullopt;
}

// Query the codec parameters from the audio HAL.
// The HAL performs a two part validation:
//  - check if the configuration is valid
//  - check if the configuration is supported by the audio provider
// In case any of these checks fails, the corresponding A2DP
// status is returned. If the configuration is valid and supported,
// A2DP_OK is returned.
tA2DP_STATUS parse_a2dp_configuration(::bluetooth::a2dp::CodecId codec_id,
                                      const uint8_t* codec_info,
                                      btav_a2dp_codec_config_t* codec_parameters,
                                      std::vector<uint8_t>* vendor_specific_parameters) {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::provider::parse_a2dp_configuration(
                           codec_id, codec_info, codec_parameters, vendor_specific_parameters)
                 : A2DP_FAIL;
}

}  // namespace provider

std::optional<btav_a2dp_hal_provider_info_t> get_provider_info() {
  return (HalVersionManager::GetHalTransport() == BluetoothAudioHalTransport::AIDL)
                 ? aidl::a2dp::get_provider_info()
                 : std::nullopt;
}

}  // namespace a2dp
}  // namespace audio
}  // namespace bluetooth
