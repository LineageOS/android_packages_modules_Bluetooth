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

#include <unordered_map>

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

class BluetoothChannelSoundingSessionTracker : public BnBluetoothChannelSoundingSessionCallback {
 public:
  BluetoothChannelSoundingSessionTracker(
      uint16_t connection_handle, RangingHalCallback* ranging_hal_callback)
      : connection_handle_(connection_handle), ranging_hal_callback_(ranging_hal_callback){};

  ::ndk::ScopedAStatus onOpened(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    return ::ndk::ScopedAStatus::ok();
  };

  ::ndk::ScopedAStatus onOpenFailed(
      ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    ranging_hal_callback_->OnOpenFailed(connection_handle_);
    return ::ndk::ScopedAStatus::ok();
  };

  ::ndk::ScopedAStatus onResult(
      const ::aidl::android::hardware::bluetooth::ranging::RangingResult& in_result) {
    log::verbose("resultMeters {}", in_result.resultMeters);
    return ::ndk::ScopedAStatus::ok();
  };
  ::ndk::ScopedAStatus onClose(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    return ::ndk::ScopedAStatus::ok();
  };
  ::ndk::ScopedAStatus onCloseFailed(
      ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    return ::ndk::ScopedAStatus::ok();
  };

  std::shared_ptr<IBluetoothChannelSoundingSession>& GetSession() {
    return bluetooth_channel_sounding_session_;
  };

 private:
  std::shared_ptr<IBluetoothChannelSoundingSession> bluetooth_channel_sounding_session_ = nullptr;
  uint16_t connection_handle_;
  RangingHalCallback* ranging_hal_callback_;
};

class RangingHalAndroid : public RangingHal {
 public:
  bool IsBound() override {
    return bluetooth_channel_sounding_ != nullptr;
  }

  void RegisterCallback(RangingHalCallback* callback) {
    ranging_hal_callback_ = callback;
  }

  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override {
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

  void OpenSession(
      uint16_t connection_handle,
      uint16_t att_handle,
      const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data) {
    log::info(
        "connection_handle 0x{:04x}, att_handle 0x{:04x} size of vendor_specific_data {}",
        connection_handle,
        att_handle,
        vendor_specific_data.size());
    session_trackers_[connection_handle] =
        ndk::SharedRefBase::make<BluetoothChannelSoundingSessionTracker>(
            connection_handle, ranging_hal_callback_);
    BluetoothChannelSoundingParameters parameters;
    parameters.aclHandle = connection_handle;
    parameters.role = aidl::android::hardware::bluetooth::ranging::Role::INITIATOR;
    parameters.realTimeProcedureDataAttHandle = att_handle;
    CopyVendorSpecificData(vendor_specific_data, parameters.vendorSpecificData);

    auto& tracker = session_trackers_[connection_handle];
    bluetooth_channel_sounding_->openSession(parameters, tracker, &tracker->GetSession());

    if (tracker->GetSession() != nullptr) {
      std::vector<VendorSpecificCharacteristic> vendor_specific_reply = {};
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      tracker->GetSession()->getVendorSpecificReplies(&vendorSpecificDataOptional);

      if (vendorSpecificDataOptional.has_value()) {
        for (auto& data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ = data->characteristicUuid;
          vendor_specific_characteristic.value_ = data->opaqueValue;
          vendor_specific_reply.emplace_back(vendor_specific_characteristic);
        }
      }
      ranging_hal_callback_->OnOpened(connection_handle, vendor_specific_reply);
    }
  }

  void CopyVendorSpecificData(
      const std::vector<hal::VendorSpecificCharacteristic>& source,
      std::optional<std::vector<std::optional<VendorSpecificData>>>& dist) {
    dist = std::make_optional<std::vector<std::optional<VendorSpecificData>>>();
    for (auto& data : source) {
      VendorSpecificData vendor_specific_data;
      vendor_specific_data.characteristicUuid = data.characteristicUuid_;
      vendor_specific_data.opaqueValue = data.value_;
      dist->push_back(vendor_specific_data);
    }
  }

 protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {
    std::string instance = std::string() + IBluetoothChannelSounding::descriptor + "/default";
    log::info("AServiceManager_isDeclared {}", AServiceManager_isDeclared(instance.c_str()));
    if (AServiceManager_isDeclared(instance.c_str())) {
      ::ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
      bluetooth_channel_sounding_ = IBluetoothChannelSounding::fromBinder(binder);
      log::info("Bind IBluetoothChannelSounding {}", IsBound() ? "Success" : "Fail");
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
  RangingHalCallback* ranging_hal_callback_;
  std::unordered_map<uint16_t, std::shared_ptr<BluetoothChannelSoundingSessionTracker>>
      session_trackers_;
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalAndroid(); });

}  // namespace hal
}  // namespace bluetooth
