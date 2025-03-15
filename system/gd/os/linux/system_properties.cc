/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "os/system_properties.h"

#include <mutex>  // NOLINT
#include <string>
#include <unordered_map>

namespace bluetooth {
namespace os {

namespace {
std::mutex properties_mutex;

// Properties set along with some default values for Floss.
std::unordered_map<std::string, std::string> properties = {
        {"bluetooth.profile.avrcp.target.enabled", "true"},
        {"bluetooth.gd.start_timeout", "12000"},
        {"bluetooth.gd.stop_timeout", "12000"},
        /* HCI Reset timeout: 10s + Default cleanup timeout: 1s = 11s */
        {"bluetooth.cleanup_timeout", "11000"}};
}  // namespace

std::optional<std::string> GetSystemProperty(const std::string& property) {
  std::lock_guard<std::mutex> lock(properties_mutex);
  auto iter = properties.find(property);
  if (iter == properties.end()) {
    return std::nullopt;
  }
  return iter->second;
}

bool SetSystemProperty(const std::string& property, const std::string& value) {
  std::lock_guard<std::mutex> lock(properties_mutex);
  properties.insert_or_assign(property, value);
  return true;
}

bool ClearSystemPropertiesForHost() {
  std::lock_guard<std::mutex> lock(properties_mutex);
  properties.clear();
  return true;
}

bool IsRootCanalEnabled() { return false; }

int GetAndroidVendorReleaseVersion() { return 0; }

}  // namespace os
}  // namespace bluetooth
