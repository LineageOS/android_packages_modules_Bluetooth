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

#include "os/parameter_provider.h"

#include <private/android_filesystem_config.h>
#include <unistd.h>

#include <mutex>
#include <string>

namespace bluetooth {
namespace os {

namespace {
std::mutex parameter_mutex;
std::string hci_instance_name;
bluetooth_keystore::BluetoothKeystoreInterface* bt_keystore_interface = nullptr;
bool is_common_criteria_mode = false;
int common_criteria_config_compare_result = 0b11;
}  // namespace

// On Android we always write a single default location
std::string ParameterProvider::ConfigFilePath() {
  const std::string name = GetHciInstanceName();
  return (name == "default") ? "/data/misc/bluedroid/bt_config.conf"
                             : "/data/misc/bluedroid/" + name + "_bt_config.conf";
}

std::string ParameterProvider::SnoopLogDirPath() {
  const std::string name = GetHciInstanceName();
  return (name == "default") ? "/data/misc/bluetooth/logs" : "/data/misc/bluetooth/logs/" + name;
}

// Android doesn't have a need for the sysprops module
std::string ParameterProvider::SyspropsFilePath() { return ""; }

bluetooth_keystore::BluetoothKeystoreInterface* ParameterProvider::GetBtKeystoreInterface() {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  return bt_keystore_interface;
}

void ParameterProvider::SetBtKeystoreInterface(
        bluetooth_keystore::BluetoothKeystoreInterface* bt_keystore) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  bt_keystore_interface = bt_keystore;
}

bool ParameterProvider::IsCommonCriteriaMode() {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  return (getuid() == AID_BLUETOOTH) && is_common_criteria_mode;
}

void ParameterProvider::SetCommonCriteriaMode(bool enable) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  is_common_criteria_mode = enable;
}

int ParameterProvider::GetCommonCriteriaConfigCompareResult() {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  return common_criteria_config_compare_result;
}

void ParameterProvider::SetCommonCriteriaConfigCompareResult(int result) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  common_criteria_config_compare_result = result;
}

void ParameterProvider::SetHciInstanceName(const std::string& name) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  hci_instance_name = name;
}

std::string ParameterProvider::GetHciInstanceName() {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  return hci_instance_name;
}

}  // namespace os
}  // namespace bluetooth
