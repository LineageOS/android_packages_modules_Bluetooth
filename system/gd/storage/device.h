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

#include <bluetooth/types/uuid.h>

#include <functional>
#include <limits>
#include <optional>
#include <string>
#include <type_traits>
#include <unordered_set>
#include <utility>

#include "hci/address.h"
#include "hci/address_with_type.h"
#include "hci/class_of_device.h"
#include "hci/enum_helper.h"
#include "storage/config_cache.h"
#include "storage/config_cache_helper.h"
#include "storage/config_keys.h"

namespace bluetooth {
namespace storage {

class LeDevice;

// Make sure our macro is used
#ifdef GENERATE_PROPERTY_GETTER
static_assert(false, "GENERATE_PROPERTY_GETTER() must be uniquely defined once in this file");
#endif

#define GENERATE_PROPERTY_GETTER(NAME, RETURN_TYPE, PROPERTY_KEY)                \
public:                                                                          \
  std::optional<RETURN_TYPE> Get##NAME() const {                                 \
    return ConfigCacheHelper(*config_).Get<RETURN_TYPE>(section_, PROPERTY_KEY); \
  }

// A think wrapper of device in ConfigCache, allowing easy access to various predefined properties
// of a Bluetooth device
//
// Device, LeDevice, and Classic device objects are fully copyable, comparable hashable
//
// A newly created device does not have any DeviceType information and user can only read or write
// the values in this common Device abstraction layer.
//
// As soon as a user determines the type of device, they should call SetDeviceType() to assign
// device to a type After that, Classic() or Le() will return interfaces that allows access to
// deeper layer properties
class Device {
public:
  enum ConfigKeyAddressType {
    LEGACY_KEY_ADDRESS,
    CLASSIC_ADDRESS,
    LE_IDENTITY_ADDRESS,
    LE_LEGACY_PSEUDO_ADDRESS
  };

  Device(ConfigCache* config, const hci::Address& key_address,
         ConfigKeyAddressType key_address_type);
  Device(ConfigCache* config, std::string section);

  // for copy
  Device(const Device& other) noexcept = default;
  Device& operator=(const Device& other) noexcept = default;

  // Only works when GetDeviceType() returns LE or DUAL, will crash otherwise
  // For first time use, please SetDeviceType() to the right value
  LeDevice Le();

  hci::Address GetAddress() const;

  // Property names that correspond to a link key used in Bluetooth Classic and LE device
  static const std::unordered_set<std::string_view> kLinkKeyProperties;

private:
  ConfigCache* config_;
  std::string section_;

public:
  GENERATE_PROPERTY_GETTER(DeviceType, hci::DeviceType, BTIF_STORAGE_KEY_DEV_TYPE);

  void SetDeviceType(const hci::DeviceType& value) {
    auto current_value = GetDeviceType().value_or(hci::DeviceType::UNKNOWN);
    config_->SetProperty(section_, BTIF_STORAGE_KEY_DEV_TYPE,
                         std::to_string(current_value | value));
  }

  std::optional<std::vector<Uuid>> GetServiceUuidsLe() const {
    auto value = config_->GetProperty(section_, BTIF_STORAGE_KEY_REMOTE_SERVICE_LE);
    if (!value) {
      return std::nullopt;
    }
    auto values = common::StringSplit(*value, " ");
    std::vector<Uuid> result;
    result.reserve(values.size());
    for (const auto& str : values) {
      auto v = Uuid::FromString(str);
      if (!v) {
        return std::nullopt;
      }
      result.push_back(*v);
    }
    return result;
  }

  GENERATE_PROPERTY_GETTER(ManufacturerCode, uint16_t, "Manufacturer");
  GENERATE_PROPERTY_GETTER(LmpVersion, uint8_t, "LmpVer");
  GENERATE_PROPERTY_GETTER(LmpSubVersion, uint16_t, "LmpSubVer");
  GENERATE_PROPERTY_GETTER(SdpDiManufacturer, uint16_t, "SdpDiManufacturer");
  GENERATE_PROPERTY_GETTER(SdpDiModel, uint16_t, "SdpDiModel");
  GENERATE_PROPERTY_GETTER(SdpDiHardwareVersion, uint16_t, "SdpDiHardwareVersion");
  GENERATE_PROPERTY_GETTER(SdpDiVendorIdSource, uint16_t, "SdpDiVendorIdSource");
};

}  // namespace storage
}  // namespace bluetooth
