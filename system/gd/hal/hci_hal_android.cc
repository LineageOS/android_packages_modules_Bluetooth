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

#include <android_bluetooth_sysprop.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <future>
#include <mutex>

#include "common/stop_watch.h"
#include "common/strings.h"
#include "hal/hci_backend.h"
#include "hal/hci_hal.h"
#include "hal/link_clocker.h"
#include "hal/snoop_logger.h"
#include "main/shim/entry.h"

namespace bluetooth::hal {

template <class VecType>
std::string GetTimerText(const char* func_name, VecType vec) {
  return common::StringFormat(
          "%s: len %zu, 1st 5 bytes '%s'", func_name, vec.size(),
          common::ToHexString(vec.begin(), std::min(vec.end(), vec.begin() + 5)).c_str());
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
  std::promise<void>* const init_promise = &init_promise_;

  HciCallbacksImpl(SnoopLogger* btsnoop_logger, LinkClocker* link_clocker)
      : link_clocker_(link_clocker), btsnoop_logger_(btsnoop_logger) {}

  void SetCallback(HciHalCallbacks* callback) {
    log::assert_that(callback_ == &kNullCallbacks, "callbacks already set");
    log::assert_that(callback != nullptr, "callback != nullptr");
    std::lock_guard<std::mutex> lock(mutex_);
    callback_ = callback;
  }

  void ResetCallback() {
    std::lock_guard<std::mutex> lock(mutex_);
    log::info("callbacks have been reset!");
    callback_ = &kNullCallbacks;
  }

  void initializationComplete() override {
    common::StopWatch stop_watch(__func__);
    init_promise_.set_value();
  }

  void hciEventReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(GetTimerText(__func__, packet));
    link_clocker_->OnHciEvent(packet);
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::EVT);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->hciEventReceived(packet);
    }
  }

  void aclDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(GetTimerText(__func__, packet));
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::ACL);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->aclDataReceived(packet);
    }
  }

  void scoDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(GetTimerText(__func__, packet));
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::INCOMING,
                             SnoopLogger::PacketType::SCO);
    {
      std::lock_guard<std::mutex> lock(mutex_);
      callback_->scoDataReceived(packet);
    }
  }

  void isoDataReceived(const std::vector<uint8_t>& packet) override {
    common::StopWatch stop_watch(GetTimerText(__func__, packet));
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
  LinkClocker* link_clocker_;
  SnoopLogger* btsnoop_logger_;
};

class HciHalImpl : public HciHal {
public:
  void registerIncomingPacketCallback(HciHalCallbacks* callback) override {
    callbacks_->SetCallback(callback);
  }

  void unregisterIncomingPacketCallback() override { callbacks_->ResetCallback(); }

  void sendHciCommand(HciPacket packet) override {
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING,
                             SnoopLogger::PacketType::CMD);
    backend_->sendHciCommand(packet);
  }

  void sendAclData(HciPacket packet) override {
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING,
                             SnoopLogger::PacketType::ACL);
    backend_->sendAclData(packet);
  }

  void sendScoData(HciPacket packet) override {
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING,
                             SnoopLogger::PacketType::SCO);
    backend_->sendScoData(packet);
  }

  void sendIsoData(HciPacket packet) override {
    btsnoop_logger_->Capture(packet, SnoopLogger::Direction::OUTGOING,
                             SnoopLogger::PacketType::ISO);
    backend_->sendIsoData(packet);
  }

  uint16_t getMsftOpcode() override {
    if (com::android::bluetooth::flags::le_scan_msft_support()) {
      return android::sysprop::bluetooth::Hci::msft_vendor_opcode().value_or(0);
    }
    return 0;
  }

protected:
  void ListDependencies(ModuleList* list) const override { list->add<LinkClocker>(); }

  void Start() override {
    common::StopWatch stop_watch(__func__);
    log::assert_that(backend_ == nullptr,
                     "Start can't be called more than once before Stop is called.");

    link_clocker_ = GetDependency<LinkClocker>();
    btsnoop_logger_ = shim::GetSnoopLogger();

    backend_ = HciBackend::CreateAidl();
    if (!backend_) {
      backend_ = HciBackend::CreateHidl(GetHandler());
    }

    log::assert_that(backend_ != nullptr, "No backend available");

    callbacks_ = std::make_shared<HciCallbacksImpl>(btsnoop_logger_, link_clocker_);

    backend_->initialize(callbacks_);
    callbacks_->init_promise->get_future().wait();
  }

  void Stop() override {
    backend_.reset();
    callbacks_.reset();
    btsnoop_logger_ = nullptr;
    link_clocker_ = nullptr;
  }

  std::string ToString() const override { return std::string("HciHal"); }

private:
  std::shared_ptr<HciCallbacksImpl> callbacks_;
  std::shared_ptr<HciBackend> backend_;
  SnoopLogger* btsnoop_logger_ = nullptr;
  LinkClocker* link_clocker_ = nullptr;
};

const ModuleFactory HciHal::Factory = ModuleFactory([]() { return new HciHalImpl(); });

}  // namespace bluetooth::hal
