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

#define LOG_TAG "bt_gd_shim"

#include "main/shim/stack_impl.h"

#include <bluetooth/log.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>
#include <fcntl.h>
#include <unistd.h>

#include <chrono>
#include <future>
#include <queue>
#include <string>

#include "main/shim/hci_layer.h"
#include "main/shim/le_advertising_manager.h"
#include "main/shim/le_scanning_manager.h"
#include "os/wakelock_manager.h"

using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;
using ::bluetooth::os::WakelockManager;

namespace bluetooth {
namespace shim {

StackImpl::Modules::Modules(os::Handler* handler)
    : storage_(handler),
      snoop_logger_(handler),
      link_clocker_(),
      hci_hal_(handler, link_clocker_, &snoop_logger_),
      ranging_hal_(),
      hci_layer_(handler, &hci_hal_, &storage_),
      controller_(handler, &hci_layer_),
      acl_scheduler_(handler),
      remote_name_request_(handler, hci_layer_, acl_scheduler_),
      round_robin_scheduler_(handler, controller_, hci_layer_.GetAclQueueEnd()),
      acl_manager_classic_(handler, hci_layer_, acl_scheduler_, remote_name_request_,
                           round_robin_scheduler_),
      acl_manager_(handler, hci_layer_, controller_, storage_, round_robin_scheduler_,
                   acl_manager_classic_),
#ifndef TARGET_FLOSS
      distance_measurement_manager_(handler, &hci_layer_, &controller_, &acl_manager_,
                                    &ranging_hal_),
#endif
      le_scanning_manager_(handler, &hci_layer_, &controller_, acl_manager_.GetLeAddressManager(),
                           &storage_),
      msft_extension_manager_(handler, &hci_hal_, &hci_layer_),
      le_advertising_manager_(handler, &hci_layer_, &controller_,
                              acl_manager_.GetLeAddressManager(), &acl_manager_),
      socket_hal_(),
      gatt_hal_(),
      lpp_offload_manager_(handler, &socket_hal_, &gatt_hal_) {
}

StackImpl::StackImpl() {}

Stack* Stack::GetInstance() {
  static StackImpl instance;
  return &instance;
}

void StackImpl::StartEverything() {
  {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    log::assert_that(!is_running_, "Gd stack already running");
    log::info("Starting Gd stack");

    stack_thread_ = new os::Thread("gd_stack_thread", os::Thread::Priority::REAL_TIME);
    stack_handler_ = new os::Handler(stack_thread_);

    if (!com_android_bluetooth_flags_threading_remove_management_thread()) {
      management_thread_ = new Thread("management_thread", Thread::Priority::NORMAL);
      management_handler_ = new Handler(management_thread_);
    }

    WakelockManager::Get().Acquire();
  }

  if (com_android_bluetooth_flags_threading_remove_management_thread()) {
    this->handle_start_up();
  } else {
    std::promise<void> promise;
    auto future = promise.get_future();
    management_handler_->Post(common::BindOnce(&StackImpl::handle_start_up_old,
                                               common::Unretained(this), std::move(promise)));

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

    auto init_status = future.wait_for(start_timeout);

    log::info("init_status == {}", int(init_status));

    if (init_status != std::future_status::ready) {
      /* Crash stuck thread and print it's stack trace, so that we know why startup is taking too
       * long */
      management_thread_->Abort();

      /* Crashed thread should take whole stack with it, but main thread is being executed
       * simultaneously. This sleep ensures that main thread doesn't execute any logic below, and
       * nicely dies with rest of stack.  */
      std::this_thread::sleep_for(std::chrono::milliseconds(2000));

      /* We should already be dead because of the Abort above, this is just in case the sleep above
       * was somehow too short */
      log::assert_that(init_status == std::future_status::ready, "Can't start stack");
    }
  }

  {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    WakelockManager::Get().Release();

    is_running_ = true;
    log::info("Successfully toggled Gd stack");

    modules_->acl_ = new Acl(stack_handler_, GetAclInterface());

    bluetooth::shim::hci_on_reset_complete();
    bluetooth::shim::init_advertising_manager();
    bluetooth::shim::init_scanning_manager();
#ifndef TARGET_FLOSS
    bluetooth::shim::init_distance_measurement_manager();
#endif
  }
}

void StackImpl::Stop() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  bluetooth::shim::hci_on_shutting_down();

  // Make sure gd acl flag is enabled and we started it up
  modules_->acl_->FinalShutdown();
  delete modules_->acl_;
  modules_->acl_ = nullptr;

  log::assert_that(is_running_, "Gd stack not running");
  is_running_ = false;
  log::info("GD stack is not running");

  stack_handler_->Clear();
  stack_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

  WakelockManager::Get().Acquire();

  if (com_android_bluetooth_flags_threading_remove_management_thread()) {
    this->handle_shut_down();
    WakelockManager::Get().Release();
    WakelockManager::Get().CleanUp();
  } else {
    std::promise<void> promise;
    auto future = promise.get_future();
    management_handler_->Post(common::BindOnce(&StackImpl::handle_shut_down_old,
                                               common::Unretained(this), std::move(promise)));

    std::chrono::milliseconds stop_timeout = std::chrono::milliseconds(12000);

    // This timeout is racing with the Kill from SystemServer, it should never fire here.
    // The management_handler_ thread should be removed and this run synchronously instead
    auto stop_status = future.wait_for(stop_timeout);

    WakelockManager::Get().Release();
    WakelockManager::Get().CleanUp();

    log::assert_that(stop_status == std::future_status::ready, "Can't stop stack");

    management_handler_->Clear();
    management_handler_->WaitUntilStopped(std::chrono::milliseconds(2000));
    delete management_handler_;
    delete management_thread_;
  }

  delete stack_handler_;
  stack_handler_ = nullptr;

  stack_thread_->Stop();
  delete stack_thread_;
  stack_thread_ = nullptr;

  log::info("Successfully shut down Gd stack");
}

bool StackImpl::IsRunning() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  return is_running_;
}

Acl* StackImpl::GetAcl() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  log::assert_that(modules_->acl_ != nullptr, "Acl shim layer has not been created");
  return modules_->acl_;
}

storage::StorageModule* StackImpl::GetStorage() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->storage_;
}

hal::SnoopLogger* StackImpl::GetSnoopLogger() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->snoop_logger_;
}

lpp::LppOffloadInterface* StackImpl::GetLppOffloadInterface() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->lpp_offload_manager_;
}

hci::HciInterface* StackImpl::GetHciLayer() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->hci_layer_;
}

hci::Controller* StackImpl::GetController() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->controller_;
}

hci::RemoteNameRequestModule* StackImpl::GetRemoteNameRequest() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->remote_name_request_;
}

hci::AclManagerLe* StackImpl::GetAclManagerLe() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->acl_manager_;
}

hci::acl_manager::AclManagerClassic* StackImpl::GetAclManagerClassic() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->acl_manager_classic_;
}

hci::MsftExtensionManager* StackImpl::GetMsftExtensionManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->msft_extension_manager_;
}

hci::LeScanningManager* StackImpl::GetLeScanningManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->le_scanning_manager_;
}

hci::LeAdvertisingManager* StackImpl::GetLeAdvertisingManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->le_advertising_manager_;
}

hci::DistanceMeasurementManager* StackImpl::GetDistanceMeasurementManager() const {
#ifdef TARGET_FLOSS
  return nullptr;
#else
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &modules_->distance_measurement_manager_;
#endif
}

os::Handler* StackImpl::GetHandler() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return stack_handler_;
}

void StackImpl::Dump(int fd, std::promise<void> promise) const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  if (is_running_ && fd >= 0) {
    stack_handler_->Call(
            [](int fd, std::promise<void> promise) {
              bluetooth::shim::GetController()->Dump(fd);
              bluetooth::shim::GetAclManagerLe()->Dump(fd);
              bluetooth::shim::GetAdvertising()->Dump(fd);
              bluetooth::os::WakelockManager::Get().Dump(fd);
              bluetooth::shim::GetSnoopLogger()->DumpSnoozLogToFile();
              promise.set_value();
            },
            fd, std::move(promise));
  } else {
    promise.set_value();
  }
}

void StackImpl::handle_start_up() { modules_.emplace(stack_handler_); }

void StackImpl::handle_start_up_old(std::promise<void> promise) {
  modules_.emplace(stack_handler_);
  promise.set_value();
}

void StackImpl::handle_shut_down() { modules_.reset(); }

void StackImpl::handle_shut_down_old(std::promise<void> promise) {
  modules_.reset();
  promise.set_value();
}
}  // namespace shim
}  // namespace bluetooth
