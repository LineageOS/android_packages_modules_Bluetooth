/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <aidl/android/hardware/bluetooth/BnBluetoothHci.h>
#include <aidl/android/hardware/bluetooth/BnBluetoothHciCallbacks.h>
#include <aidl/android/hardware/bluetooth/IBluetoothHci.h>
#include <android/binder_manager.h>
#include <bluetooth/log.h>

#include "common/stop_watch.h"
#include "hal/hci_backend.h"

namespace bluetooth::hal {

class AidlHciCallbacks : public ::aidl::android::hardware::bluetooth::BnBluetoothHciCallbacks {
public:
  AidlHciCallbacks(std::shared_ptr<HciBackendCallbacks> callbacks) : callbacks_(callbacks) {}

  using AidlStatus = ::aidl::android::hardware::bluetooth::Status;
  ::ndk::ScopedAStatus initializationComplete(AidlStatus status) override {
    log::assert_that(status == AidlStatus::SUCCESS, "status == AidlStatus::SUCCESS");
    callbacks_->initializationComplete();
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus hciEventReceived(const std::vector<uint8_t>& packet) override {
    callbacks_->hciEventReceived(packet);
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus aclDataReceived(const std::vector<uint8_t>& packet) override {
    callbacks_->aclDataReceived(packet);
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus scoDataReceived(const std::vector<uint8_t>& packet) override {
    callbacks_->scoDataReceived(packet);
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus isoDataReceived(const std::vector<uint8_t>& packet) override {
    callbacks_->isoDataReceived(packet);
    return ::ndk::ScopedAStatus::ok();
  }

private:
  std::shared_ptr<HciBackendCallbacks> callbacks_;
};

class AidlHci : public HciBackend {
public:
  AidlHci(const char* service_name) {
    ::ndk::SpAIBinder binder(AServiceManager_waitForService(service_name));
    hci_ = aidl::android::hardware::bluetooth::IBluetoothHci::fromBinder(binder);
    log::assert_that(hci_ != nullptr, "Failed to retrieve AIDL interface.");

    death_recipient_ =
            ::ndk::ScopedAIBinder_DeathRecipient(AIBinder_DeathRecipient_new([](void* /* cookie*/) {
              log::error("The Bluetooth HAL service died. Dumping logs and crashing in 1 second.");
              common::StopWatch::DumpStopWatchLog();
              // At shutdown, sometimes the HAL service gets killed before Bluetooth.
              std::this_thread::sleep_for(std::chrono::seconds(1));
              log::fatal("The Bluetooth HAL died.");
            }));

    auto death_link = AIBinder_linkToDeath(hci_->asBinder().get(), death_recipient_.get(), this);
    log::assert_that(death_link == STATUS_OK,
                     "Unable to set the death recipient for the Bluetooth HAL");
  }

  ~AidlHci() {
    auto death_unlink =
            AIBinder_unlinkToDeath(hci_->asBinder().get(), death_recipient_.get(), this);
    if (death_unlink != STATUS_OK) {
      log::error("Error unlinking death recipient from the Bluetooth HAL");
    }
    auto close_status = hci_->close();
    if (!close_status.isOk()) {
      log::error("Error calling close on the Bluetooth HAL");
    }
  }

  void initialize(std::shared_ptr<HciBackendCallbacks> callbacks) {
    hci_callbacks_ = ::ndk::SharedRefBase::make<AidlHciCallbacks>(callbacks);
    hci_->initialize(hci_callbacks_);
  }

  void sendHciCommand(const std::vector<uint8_t>& command) override {
    hci_->sendHciCommand(command);
  }

  void sendAclData(const std::vector<uint8_t>& packet) override { hci_->sendAclData(packet); }

  void sendScoData(const std::vector<uint8_t>& packet) override { hci_->sendScoData(packet); }

  void sendIsoData(const std::vector<uint8_t>& packet) override { hci_->sendIsoData(packet); }

private:
  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
  std::shared_ptr<aidl::android::hardware::bluetooth::IBluetoothHci> hci_;
  std::shared_ptr<AidlHciCallbacks> hci_callbacks_;
};

std::shared_ptr<HciBackend> HciBackend::CreateAidl() {
  static constexpr char kBluetoothAidlHalServiceName[] =
          "android.hardware.bluetooth.IBluetoothHci/default";

  if (AServiceManager_isDeclared(kBluetoothAidlHalServiceName)) {
    return std::make_shared<AidlHci>(kBluetoothAidlHalServiceName);
  }

  return std::shared_ptr<HciBackend>();
}

}  // namespace bluetooth::hal
