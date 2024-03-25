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

class MockCodecManager {
 public:
  static MockCodecManager* GetInstance();

  MockCodecManager() = default;
  MockCodecManager(const MockCodecManager&) = delete;
  MockCodecManager& operator=(const MockCodecManager&) = delete;

  virtual ~MockCodecManager() = default;

  MOCK_METHOD((bluetooth::le_audio::types::CodecLocation), GetCodecLocation, (),
              (const));
  MOCK_METHOD((bool), IsDualBiDirSwbSupported, (), (const));
  MOCK_METHOD(
      (void), UpdateActiveAudioConfig,
      (const bluetooth::le_audio::types::BidirectionalPair<
           bluetooth::le_audio::stream_parameters>& stream_params,
       bluetooth::le_audio::types::BidirectionalPair<uint16_t> delays_ms,
       std::function<void(const ::bluetooth::le_audio::offload_config& config,
                          uint8_t direction)>
           update_receiver));
  MOCK_METHOD(
      (std::unique_ptr<
          bluetooth::le_audio::set_configurations::AudioSetConfiguration>),
      GetCodecConfig,
      (const bluetooth::le_audio::CodecManager::
           UnicastConfigurationRequirements& requirements,
       bluetooth::le_audio::CodecManager::UnicastConfigurationVerifier),
      (const));
  MOCK_METHOD(
      (bool), CheckCodecConfigIsBiDirSwb,
      (const bluetooth::le_audio::set_configurations::AudioSetConfiguration&),
      (const));
  MOCK_METHOD((std::unique_ptr<
                  bluetooth::le_audio::broadcaster::BroadcastConfiguration>),
              GetBroadcastConfig,
              (const bluetooth::le_audio::CodecManager::
                   BroadcastConfigurationRequirements&),
              (const));
  MOCK_METHOD((std::vector<bluetooth::le_audio::btle_audio_codec_config_t>),
              GetLocalAudioOutputCodecCapa, ());
  MOCK_METHOD((std::vector<bluetooth::le_audio::btle_audio_codec_config_t>),
              GetLocalAudioInputCodecCapa, ());
  MOCK_METHOD(
      (void), UpdateBroadcastConnHandle,
      (const std::vector<uint16_t>& conn_handle,
       std::function<
           void(const ::bluetooth::le_audio::broadcast_offload_config& config)>
           update_receiver));
  MOCK_METHOD((void), UpdateCisConfiguration,
              (const std::vector<struct bluetooth::le_audio::types::cis>& cises,
               const bluetooth::le_audio::stream_parameters& stream_params,
               uint8_t direction),
              (const));
  MOCK_METHOD((void), ClearCisConfiguration, (uint8_t direction));

  MOCK_METHOD((void), Start, ());
  MOCK_METHOD((void), Stop, ());
};
