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

#include <android/hardware/bluetooth/1.0/types.h>
#include <android/hardware/bluetooth/1.1/IBluetoothHci.h>
#include <android/hardware/bluetooth/1.1/IBluetoothHciCallbacks.h>
#include <bluetooth/log.h>

#include "common/init_flags.h"
#include "common/stop_watch.h"
#include "common/strings.h"
#include "hal/hci_backend.h"
#include "hal/hci_hal.h"
#include "os/alarm.h"
#include "os/log.h"
#include "os/system_properties.h"

using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;
using bluetooth::common::BindOnce;
using bluetooth::os::Handler;

using IBluetoothHci_1_0 = ::android::hardware::bluetooth::V1_0::IBluetoothHci;
using IBluetoothHci_1_1 = ::android::hardware::bluetooth::V1_1::IBluetoothHci;
using IBluetoothHciCallbacks_1_1 = ::android::hardware::bluetooth::V1_1::IBluetoothHciCallbacks;

namespace bluetooth::hal {

class HidlHciCallbacks : public IBluetoothHciCallbacks_1_1 {
 public:
  HidlHciCallbacks(std::shared_ptr<HciBackendCallbacks> callbacks) : callbacks_(callbacks) {}

  using HidlStatus = ::android::hardware::bluetooth::V1_0::Status;
  Return<void> initializationComplete(HidlStatus status) override {
    log::assert_that(status == HidlStatus::SUCCESS, "status == HidlStatus::SUCCESS");
    callbacks_->initializationComplete();
    return Void();
  }

  Return<void> hciEventReceived(const hidl_vec<uint8_t>& packet) override {
    callbacks_->hciEventReceived(packet);
    return Void();
  }

  Return<void> aclDataReceived(const hidl_vec<uint8_t>& packet) override {
    callbacks_->aclDataReceived(packet);
    return Void();
  }

  Return<void> scoDataReceived(const hidl_vec<uint8_t>& data) override {
    callbacks_->scoDataReceived(data);
    return Void();
  }

  Return<void> isoDataReceived(const hidl_vec<uint8_t>& data) override {
    callbacks_->isoDataReceived(data);
    return Void();
  }

 private:
  std::shared_ptr<HciBackendCallbacks> callbacks_;
};

class HidlHci : public HciBackend {
  class DeathRecipient : public ::android::hardware::hidl_death_recipient {
   public:
    virtual void serviceDied(
        uint64_t /*cookie*/, const android::wp<::android::hidl::base::V1_0::IBase>& /*who*/) {
      log::error("The Bluetooth HAL service died. Dumping logs and crashing in 1 second.");
      common::StopWatch::DumpStopWatchLog();
      // At shutdown, sometimes the HAL service gets killed before Bluetooth.
      std::this_thread::sleep_for(std::chrono::seconds(1));
      log::fatal("The Bluetooth HAL died.");
    }
  };

 public:
  HidlHci(Handler* module_handler) {
    common::StopWatch stop_watch(__func__);
    log::info("Trying to find a HIDL interface");

    auto get_service_alarm = new os::Alarm(module_handler);
    get_service_alarm->Schedule(
        BindOnce([] {
          const std::string kBoardProperty = "ro.product.board";
          const std::string kCuttlefishBoard = "cutf";
          auto board_name = os::GetSystemProperty(kBoardProperty);
          bool emulator = board_name.has_value() && board_name.value() == kCuttlefishBoard;
          if (emulator) {
            log::error("board_name: {}", board_name.value());
            log::error(
                "Unable to get a Bluetooth service after 500ms, start the HAL before starting "
                "Bluetooth");
            return;
          }
          log::fatal(
              "Unable to get a Bluetooth service after 500ms, start the HAL before starting "
              "Bluetooth");
        }),
        std::chrono::milliseconds(500));

    hci_1_1_ = IBluetoothHci_1_1::getService();
    if (hci_1_1_)
      hci_ = hci_1_1_;
    else
      hci_ = IBluetoothHci_1_0::getService();

    get_service_alarm->Cancel();
    delete get_service_alarm;

    log::assert_that(hci_ != nullptr, "assert failed: hci_ != nullptr");

    death_recipient_ = new DeathRecipient();
    auto death_link = hci_->linkToDeath(death_recipient_, 0);
    log::assert_that(death_link.isOk(), "Unable to set the death recipient for the Bluetooth HAL");
  }

  ~HidlHci() {
    log::assert_that(hci_ != nullptr, "assert failed: hci_ != nullptr");
    auto death_unlink = hci_->unlinkToDeath(death_recipient_);
    if (!death_unlink.isOk()) {
      log::error("Error unlinking death recipient from the Bluetooth HAL");
    }
    auto close_status = hci_->close();
    if (!close_status.isOk()) {
      log::error("Error calling close on the Bluetooth HAL");
    }
    hci_ = nullptr;
    hci_1_1_ = nullptr;
  }

  void initialize(std::shared_ptr<HciBackendCallbacks> callbacks) override {
    hci_callbacks_ = new HidlHciCallbacks(callbacks);
    if (hci_1_1_ != nullptr)
      hci_1_1_->initialize_1_1(hci_callbacks_);
    else
      hci_->initialize(hci_callbacks_);
  }

  void sendHciCommand(const std::vector<uint8_t>& command) override {
    hci_->sendHciCommand(command);
  }

  void sendAclData(const std::vector<uint8_t>& packet) override {
    hci_->sendAclData(packet);
  }

  void sendScoData(const std::vector<uint8_t>& packet) override {
    hci_->sendScoData(packet);
  }

  void sendIsoData(const std::vector<uint8_t>& packet) override {
    if (hci_1_1_ == nullptr) {
      log::error("ISO is not supported in HAL v1.0");
      return;
    }
    hci_1_1_->sendIsoData(packet);
  }

 private:
  android::sp<DeathRecipient> death_recipient_;
  android::sp<HidlHciCallbacks> hci_callbacks_;
  android::sp<IBluetoothHci_1_0> hci_;
  android::sp<IBluetoothHci_1_1> hci_1_1_;
};

std::shared_ptr<HciBackend> HciBackend::CreateHidl(Handler* handler) {
  return std::make_shared<HidlHci>(handler);
}

}  // namespace bluetooth::hal
