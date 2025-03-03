/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

#include <gmock/gmock.h>

#include <vector>

#include "codec_manager.h"

namespace bluetooth::le_audio {
class LeAudioSinkAudioHalClient;
class LeAudioSourceAudioHalClient;
}  // namespace bluetooth::le_audio

class MockCodecManager {
public:
  static MockCodecManager* GetInstance();

  MockCodecManager() = default;
  MockCodecManager(const MockCodecManager&) = delete;
  MockCodecManager& operator=(const MockCodecManager&) = delete;

  virtual ~MockCodecManager() = default;

  MOCK_METHOD((bluetooth::le_audio::types::CodecLocation), GetCodecLocation, (), (const));
  MOCK_METHOD(std::optional<bluetooth::le_audio::ProviderInfo>, GetCodecConfigProviderInfo, (),
              (const));
  MOCK_METHOD((bool), IsDualBiDirSwbSupported, (), (const));

  MOCK_METHOD((bool), UpdateActiveUnicastAudioHalClient,
              (::bluetooth::le_audio::LeAudioSourceAudioHalClient * source_unicast_client,
               ::bluetooth::le_audio::LeAudioSinkAudioHalClient* sink_unicast_client,
               bool is_active));

  MOCK_METHOD((bool), UpdateActiveBroadcastAudioHalClient,
              (::bluetooth::le_audio::LeAudioSourceAudioHalClient * source_broadcast_client,
               bool is_active));

  MOCK_METHOD((void), UpdateActiveAudioConfig,
              (const bluetooth::le_audio::types::BidirectionalPair<
                       bluetooth::le_audio::stream_parameters>& stream_params,
               std::function<void(const ::bluetooth::le_audio::stream_config& config,
                                  uint8_t direction)>
                       update_receiver,
               uint8_t directions_to_update));
  MOCK_METHOD(
          (std::unique_ptr<bluetooth::le_audio::types::AudioSetConfiguration>), GetCodecConfig,
          (const bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements& requirements,
           bluetooth::le_audio::CodecManager::UnicastConfigurationProvider),
          (const));
  MOCK_METHOD((bool), CheckCodecConfigIsBiDirSwb,
              (const bluetooth::le_audio::types::AudioSetConfiguration& config), (const));
  MOCK_METHOD((bool), CheckCodecConfigIsDualBiDirSwb,
              (const bluetooth::le_audio::types::AudioSetConfiguration& config), (const));
  MOCK_METHOD((std::unique_ptr<bluetooth::le_audio::broadcaster::BroadcastConfiguration>),
              GetBroadcastConfig,
              (const bluetooth::le_audio::CodecManager::BroadcastConfigurationRequirements&),
              (const));
  MOCK_METHOD((std::vector<bluetooth::le_audio::btle_audio_codec_config_t>),
              GetLocalAudioOutputCodecCapa, ());
  MOCK_METHOD((std::vector<bluetooth::le_audio::btle_audio_codec_config_t>),
              GetLocalAudioInputCodecCapa, ());
  MOCK_METHOD((void), UpdateBroadcastConnHandle,
              (const std::vector<uint16_t>& conn_handle,
               std::function<void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
                       update_receiver));
  MOCK_METHOD((bool), UpdateCisConfiguration,
              (const std::vector<struct bluetooth::le_audio::types::cis>& cises,
               const bluetooth::le_audio::stream_parameters& stream_params, uint8_t direction),
              (const));
  MOCK_METHOD((void), ClearCisConfiguration, (uint8_t direction));
  MOCK_METHOD((bool), IsUsingCodecExtensibility, (), (const));

  MOCK_METHOD((void), Start, ());
  MOCK_METHOD((void), Stop, ());
};
