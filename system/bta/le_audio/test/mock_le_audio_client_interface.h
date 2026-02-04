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

#include "audio_hal_interface/le_audio_software.h"
#include "common/message_loop_thread.h"

namespace bluetooth::audio::le_audio {

/*
 * Gmock implementation of the LeAudioClientInterface.
 */
class MockLeAudioClientInterface : public LeAudioClientInterface {
public:
  MOCK_METHOD(Sink *, GetSink, (StreamCallbacks, bluetooth::common::MessageLoopThread*, bool));
  MOCK_METHOD(bool, ReleaseSink, (Sink*));
  MOCK_METHOD(Source *, GetSource, (StreamCallbacks, bluetooth::common::MessageLoopThread*));
  MOCK_METHOD(bool, ReleaseSource, (Source*));
};

}  // namespace bluetooth::audio::le_audio

/*
 * A global pointer to a mock of the Le Audio Client Interface.
 *
 * This is used by the fake implementation in
 * mock_le_audio_client_interface.cc to redirect calls from the singleton's
 * Get() method to the mock object, allowing it to be controlled by tests.
 *
 * Tests should set this variable before use and clear it after.
 */
extern bluetooth::audio::le_audio::MockLeAudioClientInterface* mock_le_audio_client_interface;
