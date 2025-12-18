/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <string>
#include <unordered_set>

#include "hci/hci_packets.h"
#include "storage/config_cache.h"
#include "storage/config_keys.h"
#include "storage/device.h"

namespace bluetooth {
namespace storage {

class LeDevice {
public:
  LeDevice(ConfigCache* config, std::string section)
      : config_(config), section_(std::move(section)) {}

  // for copy
  LeDevice(const LeDevice& other) noexcept = default;
  LeDevice& operator=(const LeDevice& other) noexcept = default;

  // Property names that correspond to a link key used in Bluetooth LE device
  static const std::unordered_set<std::string_view> kLinkKeyProperties;

private:
  ConfigCache* config_;
  std::string section_;

public:
  // Get LE address type of the key address
  GENERATE_PROPERTY_GETTER(AddressType, hci::AddressType, BTIF_STORAGE_KEY_ADDR_TYPE);

  void SetAddressType(const hci::AddressType& value) {
    config_->SetProperty(section_, BTIF_STORAGE_KEY_ADDR_TYPE, std::to_string(int(value)));
  }
};

}  // namespace storage
}  // namespace bluetooth
