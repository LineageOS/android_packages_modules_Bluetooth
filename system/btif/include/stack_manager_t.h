/******************************************************************************
 *
 *  Copyright 2014 Google, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <stdbool.h>

#include <future>

#include "core_callbacks.h"
#include "osi/include/future.h"

using ProfileStartCallback = void();
using ProfileStopCallback = void();

bool stack_is_running();
void stack_init(bluetooth::core::CoreInterface*);
void stack_enable(ProfileStartCallback startProfiles, const std::string local_name);
void stack_disable(ProfileStopCallback stopProfiles);
void stack_cleanup();

// TODO(zachoverflow): remove this terrible hack once the startup sequence is
// more sane
future_t* stack_manager_get_hack_future();

bluetooth::core::CoreInterface* GetInterfaceToProfiles();

namespace bluetooth::legacy::testing {
void set_interface_to_profiles(bluetooth::core::CoreInterface* interfaceToProfiles);
}  // namespace bluetooth::legacy::testing
