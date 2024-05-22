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

#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSounding.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSession.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSessionCallback.h>
#include <aidl/android/hardware/bluetooth/ranging/IBluetoothChannelSounding.h>
#include <android/binder_manager.h>
#include <bluetooth/log.h>

// AIDL uses syslog.h, so these defines conflict with os/log.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING

#include "ranging_hal.h"

using aidl::android::hardware::bluetooth::ranging::BluetoothChannelSoundingParameters;
using aidl::android::hardware::bluetooth::ranging::BnBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::ChannelSoudingRawData;
using aidl::android::hardware::bluetooth::ranging::ComplexNumber;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSounding;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSession;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::StepTonePct;
using aidl::android::hardware::bluetooth::ranging::VendorSpecificData;

namespace bluetooth {
namespace hal {

class RangingHalAndroid : public RangingHal {
 public:
  bool isBound() override {
    return bluetooth_channel_sounding_ != nullptr;
  }

  std::vector<VendorSpecificCharacteristic> getVendorSpecificCharacteristics() override {
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics = {};
    if (bluetooth_channel_sounding_ != nullptr) {
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      bluetooth_channel_sounding_->getVendorSpecificData(&vendorSpecificDataOptional);
      if (vendorSpecificDataOptional.has_value()) {
        for (auto vendor_specific_data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ =
              vendor_specific_data->characteristicUuid;
          vendor_specific_characteristic.value_ = vendor_specific_data->opaqueValue;
          vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
        }
      }
      log::info("size {}", vendor_specific_characteristics.size());
    } else {
      log::warn("bluetooth_channel_sounding_ is nullptr");
    }

    return vendor_specific_characteristics;
  };

 protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {
    std::string instance = std::string() + IBluetoothChannelSounding::descriptor + "/default";
    log::info("AServiceManager_isDeclared {}", AServiceManager_isDeclared(instance.c_str()));
    if (AServiceManager_isDeclared(instance.c_str())) {
      ::ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
      bluetooth_channel_sounding_ = IBluetoothChannelSounding::fromBinder(binder);
      log::info("Bind IBluetoothChannelSounding {}", isBound() ? "Success" : "Fail");
    }
  }

  void Stop() override {
    bluetooth_channel_sounding_ = nullptr;
  }

  std::string ToString() const override {
    return std::string("RangingHalAndroid");
  }

 private:
  std::shared_ptr<IBluetoothChannelSounding> bluetooth_channel_sounding_;
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalAndroid(); });

}  // namespace hal
}  // namespace bluetooth
