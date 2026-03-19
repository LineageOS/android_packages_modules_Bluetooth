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

#include "hci_hal_impl_android.h"

#include <android_bluetooth_sysprop.h>
#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>

#include <format>
#include <future>
#include <mutex>

#include "common/stop_watch.h"
#include "hal/hci_backend.h"
#include "hal/hci_hal.h"
#include "hal/link_clocker.h"
#include "hal/snoop_logger.h"
#include "os/parameter_provider.h"
#include "os/system_properties.h"

namespace bluetooth::hal {

template <class VecType>
static std::string GetTimerText(const char* func_name, VecType vec) {
  return std::format("{}: len {}, 1st 5 bytes '{}'", func_name, vec.size(),
                     common::ToHexString(vec.begin(), std::min(vec.end(), vec.begin() + 5)));
}

class HciCallbacksImpl : public HciBackendCallbacks {
  class : public HciHalCallbacks {
  public:
    void hciEventReceived(HciPacket) override {
      log::warn("Dropping HCI Event, since callback is not set");
    }
    void aclDataReceived(HciPacket) override {
      log::warn("Dropping ACL Data, since callback is not set");
    }
    void scoDataReceived(HciPacket) override {
      log::warn("Dropping SCO Data, since callback is not set");
    }
    void isoDataReceived(HciPacket) override {
      log::warn("Dropping ISO Data, since callback is not set");
    }
  } kNullCallbacks;

public:
  HciCallbacksImpl(SnoopLogger* btsnoop_logger, LinkClocker& link_clocker)
      : link_clocker_(link_clocker), btsnoop_logger_(btsnoop_logger) {}

  void SetCallback(HciHalCallbacks* callback) {
    log::assert_that(callback_ == &kNullCallbacks, "callbacks already set");
    log::assert_that(callback != nullptr, "callback != nullptr");
    std::lock_guard<std::mutex> lock(mutex_);
    log::info("callbacks have been set!");
    callback_ = callback;
  }

  void ResetCallback() {
    std::lock_guard<std::mutex> lock(mutex_);
    log::info("callbacks have been reset!");
    callback_ = &kNullCallbacks;
  }

  void initializationComplete() override {
    common::StopWatch stop_watch(common::StopWatch::hciHalRxBuffer_, __func__);
    init_promise_.set_value();
  }

  void waitForInitialization() {
    if (!com_android_bluetooth_flags_threading_remove_management_thread()) {
      init_promise_.get_future().wait();
      return;
    }
    std::chrono::milliseconds start_timeout;
    uint32_t hw_timeout_multiplier = os::GetSystemPropertyUint32("ro.hw_timeout_multiplier", 1);
    if (android::sysprop::bluetooth::Hardware::degraded_performance_mode() ||
        hw_timeout_multiplier != 1) {
      log::warn("Running in degraded performance mode due to slow hardware");
      start_timeout = std::chrono::milliseconds(8000) * hw_timeout_multiplier;
    } else if (bluetooth::os::GetSystemPropertyUint32("ro.build.version.sdk", 99) < 37) {
      start_timeout = std::chrono::milliseconds(
              os::GetSystemPropertyUint32("bluetooth.gd.start_timeout", 3000));
    } else {
      start_timeout = std::chrono::milliseconds(3000);
    }

    auto init_status = init_promise_.get_future().wait_for(start_timeout);
    log::assert_that(init_status == std::future_status::ready, "Can't start HAL");
  }

  void hciEventReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(common::StopWatch::hciHalRxBuffer_,
                                 GetTimerText(__func__, packet));
    link_clocker_.OnHciEvent(packet);

    auto start_time = std::chrono::steady_clock::now();
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::EVT);
    auto end_time = std::chrono::steady_clock::now();
    auto snoop_duration =
            std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);
    // TODO(b/493507987): Remove this log after debugging.
    if (snoop_duration >= std::chrono::milliseconds(500)) {
      log::error("Snoop logger capture took too long: {}ms for packet: {}", snoop_duration.count(),
                 GetTimerText(__func__, packet));
      common::StopWatch::DumpStopWatchLog();
    }

    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->hciEventReceived(packet);
    }
  }

  void aclDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(common::StopWatch::hciHalRxBuffer_,
                                 GetTimerText(__func__, packet));
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::ACL);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->aclDataReceived(packet);
    }
  }

  void scoDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(common::StopWatch::hciHalRxBuffer_,
                                 GetTimerText(__func__, packet));
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::SCO);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->scoDataReceived(packet);
    }
  }

  void isoDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(common::StopWatch::hciHalRxBuffer_,
                                 GetTimerText(__func__, packet));
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::ISO);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->isoDataReceived(packet);
    }
  }

private:
  std::mutex mutex_;
  std::promise<void> init_promise_;
  HciHalCallbacks* callback_ = &kNullCallbacks;
  LinkClocker& link_clocker_;
  SnoopLogger* btsnoop_logger_;
};

void HciHalImpl::registerIncomingPacketCallback(HciHalCallbacks* callback) {
  callbacks_->SetCallback(callback);
}

void HciHalImpl::unregisterIncomingPacketCallback() { callbacks_->ResetCallback(); }

void HciHalImpl::sendHciCommand(HciPacket packet) {
  common::StopWatch stop_watch(common::StopWatch::hciHalTxBuffer_, GetTimerText(__func__, packet));
  btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING, SnoopLogger::PacketType::CMD);
  backend_->sendHciCommand(packet);
}

void HciHalImpl::sendAclData(HciPacket packet) {
  common::StopWatch stop_watch(common::StopWatch::hciHalTxBuffer_, GetTimerText(__func__, packet));
  btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING, SnoopLogger::PacketType::ACL);
  backend_->sendAclData(packet);
}

void HciHalImpl::sendScoData(HciPacket packet) {
  common::StopWatch stop_watch(common::StopWatch::hciHalTxBuffer_, GetTimerText(__func__, packet));
  btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING, SnoopLogger::PacketType::SCO);
  backend_->sendScoData(packet);
}

void HciHalImpl::sendIsoData(HciPacket packet) {
  common::StopWatch stop_watch(common::StopWatch::hciHalTxBuffer_, GetTimerText(__func__, packet));
  btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING, SnoopLogger::PacketType::ISO);
  backend_->sendIsoData(packet);
}

uint16_t HciHalImpl::getMsftOpcode() {
  return android::sysprop::bluetooth::Hci::msft_vendor_opcode();
}

HciHalImpl::HciHalImpl(os::Handler* handler, LinkClocker& link_clocker, SnoopLogger* btsnoop_logger)
    : link_clocker_(link_clocker), btsnoop_logger_(btsnoop_logger) {
  common::StopWatch stop_watch(common::StopWatch::hciHalTxBuffer_, __func__);
  log::assert_that(backend_ == nullptr,
                   "Start can't be called more than once before Stop is called.");

  log::info("Initializing HCI HAL backend and callbacks !!");
  backend_ = HciBackend::CreateAidl(bluetooth::os::ParameterProvider::GetHciInstanceName());
  if (!backend_) {
    log::info("AIDL backend not available, falling back to HIDL");
    backend_ = HciBackend::CreateHidl(handler);
  } else {
    log::info("AIDL backend available");
  }

  log::assert_that(backend_ != nullptr, "No backend available");

  callbacks_ = std::make_shared<HciCallbacksImpl>(btsnoop_logger_, link_clocker_);

  backend_->initialize(callbacks_);
  callbacks_->waitForInitialization();
  log::info("HCI HAL initialization completed !!");
}

HciHalImpl::~HciHalImpl() {
  backend_.reset();
  callbacks_.reset();
  btsnoop_logger_ = nullptr;
}
}  // namespace bluetooth::hal
