/*
 * Copyright 2025 The Android Open Source Project
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

#include <vector>

#include "bta/include/bta_hearing_aid_api.h"

namespace bluetooth {
namespace testing {
namespace stack {
namespace hearing_aid_audio_source {

class Interface {
public:
  virtual ~Interface() = default;

  virtual void Start(const asha::CodecConfiguration codec_configuration,
                     asha::HearingAidAudioReceiver* audio_receiver, uint16_t remote_delay_ms) = 0;
  virtual void Stop() = 0;
  virtual void Initialize() = 0;
  virtual void CleanUp() = 0;
  virtual void DebugDump(int fd) = 0;
};

class Mock : public Interface {
public:
  ~Mock() = default;

  MOCK_METHOD(void, Start,
              (const asha::CodecConfiguration codec_configuration,
               asha::HearingAidAudioReceiver* audio_receiver, uint16_t remote_delay_ms));
  MOCK_METHOD(void, Stop, ());
  MOCK_METHOD(void, Initialize, ());
  MOCK_METHOD(void, CleanUp, ());
  MOCK_METHOD(void, DebugDump, (int fd));
};

void reset_interface();
void set_interface(bluetooth::testing::stack::hearing_aid_audio_source::Interface* interface_);
Interface& get_interface();

}  // namespace hearing_aid_audio_source
}  // namespace stack
}  // namespace testing
}  // namespace bluetooth
