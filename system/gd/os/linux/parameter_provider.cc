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

#include <unistd.h>

#include <cerrno>
#include <mutex>
#include <string>

namespace bluetooth {
namespace os {

namespace {
std::mutex parameter_mutex;
std::string hci_instance_name;
std::string sysprops_file_path;
}  // namespace

// Write to $PWD/bt_stack.conf if $PWD can be found, otherwise, write to $HOME/bt_stack.conf
std::string ParameterProvider::ConfigFilePath() { return "/var/lib/bluetooth/bt_config.conf"; }

std::string ParameterProvider::SnoopLogDirPath() { return "/var/log/bluetooth"; }

std::string ParameterProvider::SyspropsFilePath() {
  {
    std::lock_guard<std::mutex> lock(parameter_mutex);
    if (!sysprops_file_path.empty()) {
      return sysprops_file_path;
    }
  }
  return "/var/lib/bluetooth/sysprops.conf";
}

void ParameterProvider::OverrideSyspropsFilePath(const std::string& path) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  sysprops_file_path = path;
}

bluetooth_keystore::BluetoothKeystoreInterface* ParameterProvider::GetBtKeystoreInterface() {
  return nullptr;
}

void ParameterProvider::SetBtKeystoreInterface(
        bluetooth_keystore::BluetoothKeystoreInterface* bt_keystore) {}

bool ParameterProvider::IsCommonCriteriaMode() { return false; }

void ParameterProvider::SetCommonCriteriaMode(bool enable) {}

int ParameterProvider::GetCommonCriteriaConfigCompareResult() { return 0b11; }

void ParameterProvider::SetCommonCriteriaConfigCompareResult(int result) {}

void ParameterProvider::SetHciInstanceName(const std::string& name) {
  std::lock_guard<std::mutex> lock(parameter_mutex);
  hci_instance_name = name;
}

std::string ParameterProvider::GetHciInstanceName() {
  if (!hci_instance_name.empty()) {
    return hci_instance_name;
  }
  return "default";
}

}  // namespace os
}  // namespace bluetooth
