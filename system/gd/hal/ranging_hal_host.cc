/*
 * Copyright 2024 The Android Open Source Project
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

// AIDL uses syslog.h, so these defines conflict with os/log.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING

#include "ranging_hal.h"

namespace bluetooth {
namespace hal {

class RangingHalHost : public RangingHal {
 public:
  bool IsBound() override {
    return false;
  }
  void RegisterCallback(RangingHalCallback* /* callback */) override {}
  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override {
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics = {};
    return vendor_specific_characteristics;
  };
  void OpenSession(
      uint16_t /* connection_handle */,
      uint16_t /* att_handle */,
      const std::vector<hal::VendorSpecificCharacteristic>& /* vendor_specific_data */) override{};

 protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {}

  void Stop() override {}

  std::string ToString() const override {
    return std::string("RangingHalHost");
  }
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalHost(); });

}  // namespace hal
}  // namespace bluetooth
