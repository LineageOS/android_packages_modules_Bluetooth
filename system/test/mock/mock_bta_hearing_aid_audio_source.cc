/*
 * Copyright 2021 The Android Open Source Project
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

#include <base/functional/callback.h>

#include <cstdint>

#include "bta/include/bta_gatt_queue.h"
#include "bta/include/bta_hearing_aid_api.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

void HearingAidAudioSource::Start(const CodecConfiguration& /*codecConfiguration*/,
                                  HearingAidAudioReceiver* /*audioReceiver*/,
                                  uint16_t /*remote_delay_ms*/) {
  inc_func_call_count(__func__);
}

void HearingAidAudioSource::Stop() { inc_func_call_count(__func__); }

void HearingAidAudioSource::Initialize() { inc_func_call_count(__func__); }

void HearingAidAudioSource::CleanUp() { inc_func_call_count(__func__); }

void HearingAidAudioSource::DebugDump(int /*fd*/) { inc_func_call_count(__func__); }
