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

#include "bta/le_audio/pacs/pacs.h"

namespace bluetooth::le_audio {

class MockPacs : public Pacs {
public:
  MockPacs() = default;
  MockPacs(const MockPacs&) = delete;
  MockPacs& operator=(const MockPacs&) = delete;

  MOCK_METHOD(void, RegisterGattService,
              (const ServiceDescriptor& service_descriptor, Callbacks* callbacks), (override));
  MOCK_METHOD(void, UpdateAvailableAudioContexts,
              (const RawAddress& pseudo_addr,
               const le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>& contexts),
              (override));
  MOCK_METHOD(void, UpdateAudioChannelLocations,
              (const le_audio::types::BidirectionalPair<le_audio::types::AudioLocations>&
                       audio_locations),
              (override));
  MOCK_METHOD(void, UpdatePacSet, (uint8_t pac_set_id, const std::vector<PacRecord>& records),
              (override));
  MOCK_METHOD(void, ConfirmAudioLocationsWritten, (const RawAddress& pseudo_addr, bool accepted),
              (override));
  MOCK_METHOD(void, Dump, (std::stringstream& stream), (const, override));
};

}  // namespace bluetooth::le_audio
