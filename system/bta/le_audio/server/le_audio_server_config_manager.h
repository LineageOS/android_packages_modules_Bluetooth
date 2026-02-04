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

#include <map>
#include <memory>
#include <vector>

#include "audio_hal_interface/le_audio_peripheral.h"
#include "bta/le_audio/ascs/ascs.h"
#include "bta/le_audio/ascs/ase_manager.h"
#include "bta/le_audio/audio_hal_client/audio_hal_client.h"
#include "bta/le_audio/le_audio_types.h"
#include "bta/le_audio/pacs/pacs.h"

namespace bluetooth {
namespace le_audio {

class LeAudioServerConfigManager {
public:
  LeAudioServerConfigManager();
  virtual ~LeAudioServerConfigManager();

  virtual Pacs::ServiceDescriptor GetPacsDescriptor(
          const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& playback_capa,
          const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>& recording_capa);
  virtual Ascs::ServiceDescriptor GetAscsDescriptor();
  virtual LeAudioCodecConfiguration GetAudioSessionConfig();
  virtual std::map<uint8_t,
                   std::variant<ascs::DataPathConfiguration,
                                std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
  VerifyQosParameters(std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                                   ascs::AseStateQosConfiguration>>
                              params);
  virtual le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>
  GetAvailableAudioContexts(const RawAddress& address);
};

}  // namespace le_audio
}  // namespace bluetooth
