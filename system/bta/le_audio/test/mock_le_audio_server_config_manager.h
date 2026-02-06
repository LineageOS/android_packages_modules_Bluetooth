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

#include <gmock/gmock.h>

#include "bta/le_audio/server/le_audio_server_config_manager.h"

namespace bluetooth::le_audio {

class MockLeAudioServerConfigManager : public LeAudioServerConfigManager {
public:
  MOCK_METHOD(Pacs::ServiceDescriptor, GetPacsDescriptor,
              (const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& playback_capa,
               const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& recording_capa),
              (override));
  MOCK_METHOD(Ascs::ServiceDescriptor, GetAscsDescriptor, (), (override));
  MOCK_METHOD(LeAudioCodecConfiguration, GetAudioSessionConfig, (), (override));
  MOCK_METHOD((std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                                              std::pair<ascs::AseCtpResponseCode,
                                                        ascs::AseCtpResponseReason>>>),
              VerifyQosParameters,
              ((std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                             ascs::AseStateQosConfiguration>>)),
              (override));
  MOCK_METHOD(le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>,
                      GetAvailableAudioContexts, (const RawAddress&), (override));
};

}  // namespace bluetooth::le_audio
