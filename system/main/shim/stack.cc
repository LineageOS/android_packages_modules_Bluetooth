/*
 * Copyright 2019 The Android Open Source Project
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

#include "main/shim/stack.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <fcntl.h>
#include <unistd.h>

#include <chrono>
#include <future>
#include <queue>
#include <string>

#include "common/strings.h"
#include "hal/hci_hal_impl.h"
#include "hal/link_clocker.h"
#include "hal/ranging_hal_impl.h"
#include "hal/snoop_logger.h"
#include "hal/socket_hal_impl.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/acl_manager_impl.h"
#include "hci/controller_impl.h"
#include "hci/distance_measurement_manager_impl.h"
#include "hci/hci_layer.h"
#include "hci/le_advertising_manager_impl.h"
#include "hci/le_scanning_manager_impl.h"
#include "hci/msft.h"
#include "hci/remote_name_request_impl.h"
#include "lpp/lpp_offload_manager.h"
#include "main/shim/acl.h"
#include "main/shim/acl_interface.h"
#include "main/shim/distance_measurement_manager.h"
#include "main/shim/entry.h"
#include "main/shim/hci_layer.h"
#include "main/shim/le_advertising_manager.h"
#include "main/shim/le_scanning_manager.h"
#include "os/system_properties.h"
#include "os/wakelock_manager.h"
#include "storage/storage_module.h"

#if TARGET_FLOSS
#include "sysprops/sysprops_module.h"
#endif

using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;
using ::bluetooth::os::WakelockManager;

namespace bluetooth {
namespace shim {

struct Stack::impl {
  impl(os::Handler* handler)
      : storage_(handler),
        snoop_logger_(handler),
#ifdef TARGET_FLOSS
        sysprops_module_(),
#endif
        link_clocker_(),
        hci_hal_(handler, link_clocker_, &snoop_logger_),
        ranging_hal_(),
        hci_layer_(handler, &hci_hal_, &storage_),
        controller_(handler, &hci_layer_),
        acl_scheduler_(handler),
        remote_name_request_(handler, &hci_layer_, &acl_scheduler_),
        acl_manager_(handler, hci_layer_, controller_, acl_scheduler_, remote_name_request_,
                     storage_),
        le_scanning_manager_(handler, &hci_layer_, &controller_, acl_manager_.GetLeAddressManager(),
                             &storage_),
        msft_extension_manager_(handler, &hci_hal_, &hci_layer_),
        le_advertising_manager_(handler, &hci_layer_, &controller_,
                                acl_manager_.GetLeAddressManager(), &acl_manager_),
        distance_measurement_manager_(handler, &hci_layer_, &controller_, &acl_manager_,
                                      &ranging_hal_) {
#ifndef TARGET_FLOSS
    if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3286716
      socket_hal_ = std::make_unique<hal::SocketHalImpl>();
      lpp_offload_manager_ = std::make_unique<lpp::LppOffloadManager>(handler, socket_hal_.get());
    }
#endif
  }

  ~impl() {
    if (lpp_offload_manager_) {
      lpp_offload_manager_.reset();
    }

    if (socket_hal_) {
      socket_hal_.reset();
    }
  }

  Acl* acl_ = nullptr;
  storage::StorageModule storage_;
  hal::SnoopLogger snoop_logger_;
#if TARGET_FLOSS
  sysprops::SyspropsModule sysprops_module_;
#endif
  std::unique_ptr<hal::SocketHal> socket_hal_ = nullptr;
  std::unique_ptr<lpp::LppOffloadManager> lpp_offload_manager_ = nullptr;
  hal::LinkClocker link_clocker_;
  hal::HciHalImpl hci_hal_;
  hal::RangingHalImpl ranging_hal_;
  hci::HciLayer hci_layer_;
  hci::ControllerImpl controller_;
  hci::acl_manager::AclScheduler acl_scheduler_;
  hci::RemoteNameRequestModuleImpl remote_name_request_;
  hci::AclManagerImpl acl_manager_;
  hci::LeScanningManagerImpl le_scanning_manager_;
  hci::MsftExtensionManager msft_extension_manager_;
  hci::LeAdvertisingManagerImpl le_advertising_manager_;
  hci::DistanceMeasurementManagerImpl distance_measurement_manager_;
};

Stack::Stack() {}

Stack* Stack::GetInstance() {
  static Stack instance;
  return &instance;
}

void Stack::StartEverything() {
  {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    log::assert_that(!is_running_, "Gd stack already running");
    log::info("Starting Gd stack");

    stack_thread_ = new os::Thread("gd_stack_thread", os::Thread::Priority::REAL_TIME);
    stack_handler_ = new os::Handler(stack_thread_);

    management_thread_ = new Thread("management_thread", Thread::Priority::NORMAL);
    management_handler_ = new Handler(management_thread_);

    WakelockManager::Get().Acquire();
  }

  std::promise<void> promise;
  auto future = promise.get_future();
  management_handler_->Post(
          common::BindOnce(&Stack::handle_start_up, common::Unretained(this), std::move(promise)));
  auto init_status = future.wait_for(
          std::chrono::milliseconds(get_gd_stack_timeout_ms(/* is_start = */ true)));

  log::info("init_status == {}", int(init_status));

  if (init_status != std::future_status::ready) {
    /* Crash stuck thread and print it's stack trace, so that we know why starartup is taking too
     * long */
    management_thread_->Abort();

    /* Crashed thread should take whole stack with it, but main thread is being executed
     * simulteanously. This sleep ensures that main thread doesn't execute any logic below, and
     * nicely dies with rest of stack.  */
    std::this_thread::sleep_for(std::chrono::milliseconds(2000));

    /* We should already be dead because of the Abort above, this is just in case the sleep above
     * was somehow too short */
    log::assert_that(init_status == std::future_status::ready, "Can't start stack");
  }

  {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    WakelockManager::Get().Release();

    is_running_ = true;
    log::info("Successfully toggled Gd stack");

    pimpl_->acl_ = new Acl(stack_handler_, GetAclInterface());

    bluetooth::shim::hci_on_reset_complete();
    bluetooth::shim::init_advertising_manager();
    bluetooth::shim::init_scanning_manager();
    bluetooth::shim::init_distance_measurement_manager();
  }
}

void Stack::Stop() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  bluetooth::shim::hci_on_shutting_down();

  // Make sure gd acl flag is enabled and we started it up
  pimpl_->acl_->FinalShutdown();
  delete pimpl_->acl_;
  pimpl_->acl_ = nullptr;

  log::assert_that(is_running_, "Gd stack not running");
  is_running_ = false;
  log::info("GD stack is not running");

  stack_handler_->Clear();
  if (com::android::bluetooth::flags::same_handler_for_all_modules()) {
    stack_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);
  }

  WakelockManager::Get().Acquire();

  std::promise<void> promise;
  auto future = promise.get_future();
  management_handler_->Post(
          common::BindOnce(&Stack::handle_shut_down, common::Unretained(this), std::move(promise)));

  auto stop_status = future.wait_for(
          std::chrono::milliseconds(get_gd_stack_timeout_ms(/* is_start = */ false)));

  WakelockManager::Get().Release();
  WakelockManager::Get().CleanUp();

  log::assert_that(stop_status == std::future_status::ready, "Can't stop stack");

  management_handler_->Clear();
  management_handler_->WaitUntilStopped(std::chrono::milliseconds(2000));
  delete management_handler_;
  delete management_thread_;

  delete stack_handler_;
  stack_handler_ = nullptr;

  stack_thread_->Stop();
  delete stack_thread_;
  stack_thread_ = nullptr;

  log::info("Successfully shut down Gd stack");
}

bool Stack::IsRunning() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  return is_running_;
}

Acl* Stack::GetAcl() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  log::assert_that(pimpl_->acl_ != nullptr, "Acl shim layer has not been created");
  return pimpl_->acl_;
}

storage::StorageModule* Stack::GetStorage() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->storage_;
}

hal::SnoopLogger* Stack::GetSnoopLogger() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->snoop_logger_;
}

lpp::LppOffloadInterface* Stack::GetLppOffloadInterface() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->lpp_offload_manager_.get();
}

hci::HciInterface* Stack::GetHciLayer() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->hci_layer_;
}

hci::Controller* Stack::GetController() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->controller_;
}

hci::RemoteNameRequestModule* Stack::GetRemoteNameRequest() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->remote_name_request_;
}

hci::AclManager* Stack::GetAclManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->acl_manager_;
}

hci::MsftExtensionManager* Stack::GetMsftExtensionManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->msft_extension_manager_;
}

hci::LeScanningManager* Stack::GetLeScanningManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->le_scanning_manager_;
}

hci::LeAdvertisingManager* Stack::GetLeAdvertisingManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->le_advertising_manager_;
}

hci::DistanceMeasurementManager* Stack::GetDistanceMeasurementManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &pimpl_->distance_measurement_manager_;
}

os::Handler* Stack::GetHandler() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return stack_handler_;
}

void Stack::Dump(int fd, std::promise<void> promise) const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  if (is_running_ && fd >= 0) {
    stack_handler_->Call(
            [](int fd, std::promise<void> promise) {
              bluetooth::shim::GetController()->Dump(fd);
              bluetooth::shim::GetAclManager()->Dump(fd);
              bluetooth::os::WakelockManager::Get().Dump(fd);
              bluetooth::shim::GetSnoopLogger()->DumpSnoozLogToFile();
              promise.set_value();
            },
            fd, std::move(promise));
  } else {
    promise.set_value();
  }
}

void Stack::handle_start_up(std::promise<void> promise) {
  pimpl_ = std::make_unique<Stack::impl>(stack_handler_);
  promise.set_value();
}

void Stack::handle_shut_down(std::promise<void> promise) {
  pimpl_.reset();
  promise.set_value();
}

std::chrono::milliseconds Stack::get_gd_stack_timeout_ms(bool is_start) {
  auto gd_timeout = os::GetSystemPropertyUint32(
          is_start ? "bluetooth.gd.start_timeout" : "bluetooth.gd.stop_timeout",
          /* default_value = */ is_start ? 3000 : 5000);
  return std::chrono::milliseconds(gd_timeout *
                                   os::GetSystemPropertyUint32("ro.hw_timeout_multiplier",
                                                               /* default_value = */ 1));
}

}  // namespace shim
}  // namespace bluetooth
