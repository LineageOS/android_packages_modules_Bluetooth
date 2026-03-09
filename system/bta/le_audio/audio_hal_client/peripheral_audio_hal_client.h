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

#pragma once

#include <memory>

#include "audio_hal_interface/le_audio_peripheral.h"
#include "common/message_loop_thread.h"

namespace bluetooth::bta::le_audio {

struct audio_channel_info {
  tBLE_BD_ADDR address_with_type;
  ::bluetooth::le_audio::types::LeAudioCodecId codec_id;
  std::vector<uint8_t> codec_config;
  uint8_t target_latency;
  uint32_t pres_delay;
  uint8_t target_phy;
  std::vector<uint8_t> metadata;

  std::string toString() const {
    std::stringstream sstream;
    sstream << "audio_channel_info{";
    sstream << ", address_with_type: " << address_with_type.ToString();
    sstream << ", codec_id: " << codec_id;
    sstream << ", codec_config: " << codec_config.size();
    sstream << ", pres_delay: " << pres_delay;
    sstream << ", target_phy: " << static_cast<int>(target_phy);
    sstream << ", metadata: " << metadata.size();
    return sstream.str();
  }
};

/**
 * Thin wrapper for the LE Audio Peripheral decoding path
 */
class PeripheralAudioHalDecoder {
public:
  /**
   * Constructs a new PeripheralAudioHalDecoder.
   *
   * @param callbacks The callbacks for stream events.
   * @param message_loop The message loop for handling events.
   * @param factory The factory for creating audio sessions.
   */
  PeripheralAudioHalDecoder(
          const ::bluetooth::audio::le_audio::PeripheralStreamCallbacks& callbacks,
          ::bluetooth::common::MessageLoopThread* message_loop,
          ::bluetooth::audio::le_audio::IPeripheralAudioSessionFactory* factory = nullptr);
  ~PeripheralAudioHalDecoder();

  // Disable copy and assign to prevent ownership issues
  PeripheralAudioHalDecoder(const PeripheralAudioHalDecoder&) = delete;
  PeripheralAudioHalDecoder& operator=(const PeripheralAudioHalDecoder&) = delete;

  /**
   * Writes audio data to the HAL.
   *
   * @param buffer The buffer containing the audio data.
   * @param bytes The number of bytes to write.
   * @return The number of bytes written.
   */
  size_t Write(const uint8_t* buffer, uint32_t bytes);

  /**
   * Starts the audio session.
   */
  void Start();

  /**
   * Stops the audio session.
   */
  void Stop();

  /**
   * Confirms a streaming request.
   */
  void ConfirmStreamingRequest();

  /**
   * Retrieves the list of streaming encode devices.
   *
   * @return A constant reference to a vector of RawAddress representing the
   * streaming devices.
   */
  const std::vector<RawAddress>& GetStreamingDevices();

  /**
   * Handles audio channel parameter changes.
   *
   * @param pseudo_address The pseudo-address of the device.
   * @param cis_conn_handle The CIS connection handle.
   * @param channel_info The new audio channel information.
   */
  void OnAudioChannelParametersChanged(RawAddress pseudo_address, uint16_t cis_conn_handle,
                                       const audio_channel_info& channel_info);

  /**
   * Handles audio channel removal.
   *
   * @param pseudo_address The pseudo-address of the device.
   * @param cis_conn_handle The CIS connection handle.
   */
  void OnAudioChannelRemoved(RawAddress pseudo_address, uint16_t cis_conn_handle);

  /**
   * Requests ASE (Audio Stream Endpoint) configurations from the HAL.
   *
   * This function sends a request to the LE Audio HAL to configure the
   * sink and source ASEs with the specified parameters.
   *
   * @param sink_configs A vector of configuration requests for the sink ASEs.
   * @param source_configs A vector of configuration requests for the source
   * ASEs.
   * @return An endpoint_config_rsp structure containing the HAL's response
   * to the configuration request, which may include the actually set
   * configurations or a response reason in case of unfulfilled request.
   */
  ::bluetooth::audio::le_audio::endpoint_config_rsp RequestAseConfigurations(
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
          const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& source_configs)
          const;

private:
  class impl;
  std::unique_ptr<impl> pimpl_;
};

/**
 * Thin wrapper for the LE Audio Peripheral encoding path
 */
class PeripheralAudioHalEncoder {
public:
  /**
   * Constructs a new PeripheralAudioHalEncoder.
   *
   * @param callbacks The callbacks for stream events.
   * @param message_loop The message loop for handling events.
   * @param factory The factory for creating audio sessions.
   */
  PeripheralAudioHalEncoder(
          const ::bluetooth::audio::le_audio::PeripheralStreamCallbacks& callbacks,
          ::bluetooth::common::MessageLoopThread* message_loop,
          ::bluetooth::audio::le_audio::IPeripheralAudioSessionFactory* factory = nullptr);
  ~PeripheralAudioHalEncoder();

  // Disable copy and assign to prevent ownership issues
  PeripheralAudioHalEncoder(const PeripheralAudioHalEncoder&) = delete;
  PeripheralAudioHalEncoder& operator=(const PeripheralAudioHalEncoder&) = delete;

  /**
   * Reads audio data from the HAL.
   *
   * @param buffer The buffer to store the audio data.
   * @param bytes The number of bytes to read.
   * @return The number of bytes read.
   */
  size_t Read(uint8_t* buffer, uint32_t bytes);

  /**
   * Starts the audio stream.
   */
  void Start();

  /**
   * Stops the audio stream.
   */
  void Stop();

  /**
   * Confirms a streaming request.
   */
  void ConfirmStreamingRequest();

  /**
   * Retrieves the list of streaming encode devices.
   *
   * @return A constant reference to a vector of RawAddress representing the
   * streaming devices.
   */
  const std::vector<RawAddress>& GetStreamingDevices();

  /**
   * Handles audio channel parameter changes.
   *
   * @param pseudo_address The pseudo-address of the device.
   * @param cis_conn_handle The CIS connection handle.
   * @param channel_info The new audio channel information.
   */
  void OnAudioChannelParametersChanged(RawAddress pseudo_address, uint16_t cis_conn_handle,
                                       const audio_channel_info& channel_info);
  /**
   * Handles audio channel removal.
   *
   * @param pseudo_address The pseudo-address of the device.
   * @param cis_conn_handle The CIS connection handle.
   */
  void OnAudioChannelRemoved(RawAddress pseudo_address, uint16_t cis_conn_handle);

  /**
   * Requests ASE (Audio Stream Endpoint) configurations from the HAL.
   *
   * This function sends a request to the LE Audio HAL to configure the
   * sink and source ASEs with the specified parameters.
   *
   * @param sink_configs A vector of configuration requests for the sink ASEs.
   * @param source_configs A vector of configuration requests for the source
   * ASEs.
   * @return An endpoint_config_rsp structure containing the HAL's response
   * to the configuration request, which may include the actually set
   * configurations or a response reason in case of unfulfilled request.
   */
  bluetooth::audio::le_audio::endpoint_config_rsp RequestAseConfigurations(
          const std::vector<bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
          const std::vector<bluetooth::audio::le_audio::endpoint_config_req>& source_configs) const;

private:
  class impl;
  std::unique_ptr<impl> pimpl_;
};
}  // namespace bluetooth::bta::le_audio
