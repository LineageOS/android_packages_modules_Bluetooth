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

#include <functional>
#include <memory>

#include "bta/le_audio/ascs/ascs_types.h"
#include "bta/le_audio/le_audio_types.h"
#include "common/message_loop_thread.h"

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif  // TARGET_FLOSS

namespace bluetooth::audio::le_audio {

using endpoint_config_req = ::bluetooth::le_audio::ascs::AseCodecConfigurationReq;
using endpoint_config_rsp =
        std::map<uint8_t,
                 std::variant<::bluetooth::le_audio::ascs::AseStateCodecConfiguration,
                              std::pair<::bluetooth::le_audio::ascs::AseCtpResponseCode,
                                        ::bluetooth::le_audio::ascs::AseCtpResponseReason>>>;

/**
 * Common stream callbacks for both playback and recording for the peripheral
 * role
 */
struct PeripheralStreamCallbacks {
  /// Callback for when the stream is requested to start.
  std::function<void(void)> OnStartRequest;
  /// Callback for when the stream is requested to suspend.
  std::function<void(void)> OnSuspendRequest;
  /**
   * Callback for playback metadata updates.
   * @param metadata The updated playback metadata.
   */
  std::function<void(const source_metadata_v7_t& metadata)> OnPlaybackMetadataUpdate;
  /**
   * Callback for recording metadata updates.
   * @param metadata The updated recording metadata.
   */
  std::function<void(const sink_metadata_v7_t& metadata)> OnRecordingMetadataUpdate;
};

struct AudioHalCapability {
  /** The unique identifier for the audio codec (e.g., LC3). */
  uint8_t coding_format;
  uint16_t vendor_company_id;
  uint16_t vendor_codec_id;
  /** Codec-specific capabilities (e.g., supported sampling frequencies). */
  std::vector<uint8_t> codec_spec_caps;
  /** Codec-specific metadata. */
  std::vector<uint8_t> metadata;
};

/**
 * Abstract interface for a peripheral audio playback session (e.g. BT speaker).
 */
class IPeripheralAudioOut {
public:
  virtual ~IPeripheralAudioOut() = default;

  /**
   * Writes audio data to the HAL.
   *
   * @param buffer The buffer containing the audio data.
   * @param bytes The number of bytes to write.
   * @return The number of bytes written.
   */
  virtual size_t Write(const uint8_t* buffer, uint32_t bytes) = 0;

  /**
   * Starts the audio session.
   */
  virtual void Start() = 0;

  /**
   * Stops the audio stream.
   */
  virtual void Stop() = 0;

  /**
   * Updates the audio configuration in the HAL.
   *
   * @param config The new audio stream configuration.
   */
  virtual void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) = 0;

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
  virtual endpoint_config_rsp RequestAseConfigurations(
          const std::vector<endpoint_config_req>& sink_configs,
          const std::vector<endpoint_config_req>& source_configs) const = 0;

  /**
   * Confirms a streaming request.
   */
  virtual void ConfirmStreamingRequest() = 0;

  /**
   * Cancels a streaming request.
   */
  virtual void CancelStreamingRequest() = 0;
};

/**
 * Abstract interface for a peripheral audio recording session (e.g. BT microphone).
 */
class IPeripheralAudioIn {
public:
  virtual ~IPeripheralAudioIn() = default;

  /**
   * Reads audio data from the HAL.
   *
   * @param buffer The buffer to store the audio data.
   * @param bytes The number of bytes to read.
   * @return The number of bytes read.
   */
  virtual size_t Read(uint8_t* buffer, uint32_t bytes) = 0;

  /**
   * Starts the audio session.
   */
  virtual void Start() = 0;

  /**
   * Stops the audio session.
   */
  virtual void Stop() = 0;

  /**
   * Updates the audio configuration in the HAL.
   *
   * @param config The new audio stream configuration.
   */
  virtual void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) = 0;

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
  virtual endpoint_config_rsp RequestAseConfigurations(
          const std::vector<endpoint_config_req>& sink_configs,
          const std::vector<endpoint_config_req>& source_configs) const = 0;

  /**
   * Confirms a streaming request.
   */
  virtual void ConfirmStreamingRequest() = 0;

  /**
   * Cancels a streaming request.
   */
  virtual void CancelStreamingRequest() = 0;
};

/**
 * Factory to acquire audio session instances.
 */
class IPeripheralAudioSessionFactory {
public:
  virtual ~IPeripheralAudioSessionFactory() = default;

  /**
   * Acquires a playback session.
   *
   * @param callbacks The callbacks for stream events.
   * @param message_loop The message loop for handling events.
   * @return A unique pointer to the playback session.
   */
  virtual std::unique_ptr<IPeripheralAudioOut> AcquirePlaybackSession(
          const PeripheralStreamCallbacks& callbacks, common::MessageLoopThread* message_loop) = 0;

  /**
   * Releases a playback session.
   *
   * @param session The playback session to release.
   */
  virtual void ReleasePlaybackSession(std::unique_ptr<IPeripheralAudioOut> session) = 0;

  /**
   * Acquires a recording session.
   *
   * @param callbacks The callbacks for stream events.
   * @param message_loop The message loop for handling events.
   * @return A unique pointer to the recording session.
   */
  virtual std::unique_ptr<IPeripheralAudioIn> AcquireRecordingSession(
          const PeripheralStreamCallbacks& callbacks, common::MessageLoopThread* message_loop) = 0;

  /**
   * Releases a recording session.
   *
   * @param session The recording session to release.
   */
  virtual void ReleaseRecordingSession(std::unique_ptr<IPeripheralAudioIn> session) = 0;

  /**
   * Get the default factory implementation.
   *
   * @return The default factory instance.
   */
  static IPeripheralAudioSessionFactory* Get();
};

/**
 * Abstract interface for a peripheral audio provider.
 */
class IPeripheralAudioProvider {
public:
  virtual ~IPeripheralAudioProvider() = default;

  /**
   * Get the provider supported capabilities for the particular session.
   *
   * @return std::vector<AudioHalCapability> Provider capabilities.
   */
  virtual std::vector<AudioHalCapability> GetProviderCapabilities() = 0;
};

/**
 * Factory to acquire IPeripheralAudioProvider interface.
 */
class IPeripheralAudioProviderFactory {
public:
  virtual ~IPeripheralAudioProviderFactory() = default;

  /**
   * Get the playback session audio provider.
   *
   * @return std::unique_ptr<IPeripheralAudioProvider> The provider instance.
   */
  virtual std::unique_ptr<IPeripheralAudioProvider> GetPlaybackSessionAudioProvider() = 0;
  /**
   * Get the recording session audio provider.
   *
   * @return std::unique_ptr<IPeripheralAudioProvider> The provider instance.
   */
  virtual std::unique_ptr<IPeripheralAudioProvider> GetRecordingSessionAudioProvider() = 0;

  /**
   * Get the factory instance.
   *
   * @return IPeripheralAudioProviderFactory* Factory instance.
   */
  static IPeripheralAudioProviderFactory* Get();
};

}  // namespace bluetooth::audio::le_audio
