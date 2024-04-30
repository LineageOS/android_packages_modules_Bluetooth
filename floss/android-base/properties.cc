/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "android-base/properties.h"

#include "gd/os/system_properties.h"

namespace android {
namespace base {

std::string GetProperty(const std::string& key,
                        const std::string& default_value) {
  return bluetooth::os::GetSystemProperty(key).value_or(default_value);
}

// Sets the system property `key` to `value`.
bool SetProperty(const std::string& key, const std::string& value) {
  return bluetooth::os::SetSystemProperty(key, value);
}

}  // namespace base
}  // namespace android
