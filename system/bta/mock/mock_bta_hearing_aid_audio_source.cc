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

/*
 * Generated mock file from original source file
 *   Functions generated:8
 */

#include "mock_bta_hearing_aid_audio_source.h"

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>

#include <cstdint>

#include "bta/include/bta_gatt_queue.h"
#include "bta/include/bta_hearing_aid_api.h"
#include "test/common/mock_functions.h"

namespace {
bluetooth::testing::stack::hearing_aid_audio_source::Mock mock_hearing_aid_audio_source_interface;
bluetooth::testing::stack::hearing_aid_audio_source::Interface* interface_ =
        &mock_hearing_aid_audio_source_interface;
}  // namespace

void bluetooth::testing::stack::hearing_aid_audio_source::reset_interface() {
  interface_ = &mock_hearing_aid_audio_source_interface;
}

void bluetooth::testing::stack::hearing_aid_audio_source::set_interface(
        bluetooth::testing::stack::hearing_aid_audio_source::Interface* interface) {
  interface_ = interface;
}

namespace bluetooth::asha {

void HearingAidAudioSource::Start(const CodecConfiguration& codecConfiguration,
                                  HearingAidAudioReceiver* audioReceiver,
                                  uint16_t remote_delay_ms) {
  inc_func_call_count(__func__);

  interface_->Start(codecConfiguration, audioReceiver, remote_delay_ms);
}

void HearingAidAudioSource::Stop() {
  inc_func_call_count(__func__);

  interface_->Stop();
}

void HearingAidAudioSource::Initialize() {
  inc_func_call_count(__func__);

  interface_->Initialize();
}

void HearingAidAudioSource::CleanUp() {
  inc_func_call_count(__func__);

  interface_->CleanUp();
}

void HearingAidAudioSource::DebugDump(int /*fd*/) { inc_func_call_count(__func__); }

}  // namespace bluetooth::asha
