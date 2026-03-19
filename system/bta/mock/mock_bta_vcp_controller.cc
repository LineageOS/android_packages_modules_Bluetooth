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
 *   Functions generated:7
 */

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>

#include "bta/include/bta_vcp_controller_api.h"
#include "test/common/mock_functions.h"

void VolumeController::AddFromStorage(const RawAddress& /* address */) {
  inc_func_call_count(__func__);
}
void VolumeController::CleanUp() { inc_func_call_count(__func__); }
void VolumeController::DebugDump(int /* fd */) { inc_func_call_count(__func__); }
VolumeController* VolumeController::Get(void) {
  inc_func_call_count(__func__);
  return nullptr;
}
bool VolumeController::IsRunning() {
  inc_func_call_count(__func__);
  return false;
}
void VolumeController::Initialize(bluetooth::vcp::VolumeControllerCallbacks* /* callbacks */,
                                  base::OnceClosure /* initCb */) {
  inc_func_call_count(__func__);
}
