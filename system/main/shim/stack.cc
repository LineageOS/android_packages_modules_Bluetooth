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
  Acl* acl_ = nullptr;
  std::shared_ptr<storage::StorageModule> storage_ = nullptr;
  std::shared_ptr<hal::SnoopLogger> snoop_logger_ = nullptr;
#if TARGET_FLOSS
  std::unique_ptr<sysprops::SyspropsModule> sysprops_module_ = nullptr;
#endif
  std::unique_ptr<hal::SocketHal> socket_hal_ = nullptr;
  std::unique_ptr<lpp::LppOffloadManager> lpp_offload_manager_ = nullptr;
  std::unique_ptr<hal::LinkClocker> link_clocker_ = nullptr;
  std::unique_ptr<hal::HciHal> hci_hal_ = nullptr;
  std::unique_ptr<hal::RangingHal> ranging_hal_ = nullptr;
  std::unique_ptr<hci::HciLayer> hci_layer_ = nullptr;
  std::unique_ptr<hci::Controller> controller_ = nullptr;
  std::unique_ptr<hci::acl_manager::AclScheduler> acl_scheduler_ = nullptr;
  std::unique_ptr<hci::RemoteNameRequestModule> remote_name_request_ = nullptr;
  std::unique_ptr<hci::AclManagerImpl> acl_manager_ = nullptr;
  std::unique_ptr<hci::LeScanningManager> le_scanning_manager_ = nullptr;
  std::unique_ptr<hci::MsftExtensionManager> msft_extension_manager_ = nullptr;
  std::unique_ptr<hci::LeAdvertisingManager> le_advertising_manager_ = nullptr;
  std::unique_ptr<hci::DistanceMeasurementManager> distance_measurement_manager_ = nullptr;
};

Stack::Stack() { pimpl_ = std::make_shared<Stack::impl>(); }

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

  is_running_ = true;
  log::info("GD stack is running");

  std::promise<void> promise;
  auto future = promise.get_future();
  management_handler_->Post(
          common::BindOnce(&Stack::handle_start_up, common::Unretained(this), std::move(promise)));
  auto init_status = future.wait_for(
          std::chrono::milliseconds(get_gd_stack_timeout_ms(/* is_start = */ true)));

  {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    WakelockManager::Get().Release();

    log::info("init_status == {}", int(init_status));

    log::assert_that(init_status == std::future_status::ready, "Can't start stack");

    log::info("Successfully toggled Gd stack");

    // Make sure the leaf modules are started
    log::assert_that(pimpl_->hci_hal_ != nullptr, "assert failed pimpl_->hci_hal_ != nullptr");

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
  if(com::android::bluetooth::flags::same_handler_for_all_modules()) {
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
  return pimpl_->storage_.get();
}

hal::SnoopLogger* Stack::GetSnoopLogger() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->snoop_logger_.get();
}

lpp::LppOffloadInterface* Stack::GetLppOffloadInterface() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->lpp_offload_manager_.get();
}

hci::HciInterface* Stack::GetHciLayer() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->hci_layer_.get();
}

hci::Controller* Stack::GetController() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->controller_.get();
}

hci::RemoteNameRequestModule* Stack::GetRemoteNameRequest() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->remote_name_request_.get();
}

hci::AclManager* Stack::GetAclManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->acl_manager_.get();
}

hci::MsftExtensionManager* Stack::GetMsftExtensionManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->msft_extension_manager_.get();
}

hci::LeScanningManager* Stack::GetLeScanningManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->le_scanning_manager_.get();
}

hci::LeAdvertisingManager* Stack::GetLeAdvertisingManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->le_advertising_manager_.get();
}

hci::DistanceMeasurementManager* Stack::GetDistanceMeasurementManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return pimpl_->distance_measurement_manager_.get();
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
  if (com::android::bluetooth::flags::same_handler_for_all_modules()) {
    pimpl_->storage_ = std::make_shared<storage::StorageModule>(stack_handler_);
    pimpl_->snoop_logger_ = std::make_shared<hal::SnoopLogger>(stack_handler_);
  } else {
    pimpl_->storage_ = std::make_shared<storage::StorageModule>(new Handler(stack_thread_));
    pimpl_->snoop_logger_ = std::make_shared<hal::SnoopLogger>(new Handler(stack_thread_));
  }

#if TARGET_FLOSS
  log::info("Starting SyspropsModule");
  pimpl_->sysprops_module = std::make_unique<sysprops::SyspropsModule>();
#else
  if (com::android::bluetooth::flags::socket_settings_api()) {  // Added with aosp/3286716
    log::info("Starting SocketHal");
    pimpl_->socket_hal_ = std::make_unique<hal::SocketHalImpl>();

    log::info("Starting LppOffloadManager");
    pimpl_->lpp_offload_manager_ =
            std::make_unique<lpp::LppOffloadManager>(stack_handler_, pimpl_->socket_hal_.get());
  }
#endif

  log::info("Starting LinkClocker");
  pimpl_->link_clocker_ = std::make_unique<hal::LinkClocker>();

  log::info("Starting HciHal");
  pimpl_->hci_hal_ = std::make_unique<hal::HciHalImpl>(stack_handler_, pimpl_->link_clocker_.get(),
                                                       pimpl_->snoop_logger_.get());

  log::info("Starting RangingHal");
  pimpl_->ranging_hal_ = std::make_unique<hal::RangingHalImpl>();

  log::info("Starting HciLayer");
  pimpl_->hci_layer_ = std::make_unique<hci::HciLayer>(stack_handler_, pimpl_->hci_hal_.get(),
                                                       pimpl_->storage_.get());

  log::info("Starting Controller");
  pimpl_->controller_ =
          std::make_unique<hci::ControllerImpl>(stack_handler_, pimpl_->hci_layer_.get());

  log::info("Starting AclScheduler");
  pimpl_->acl_scheduler_ = std::make_unique<hci::acl_manager::AclScheduler>(stack_handler_);

  log::info("Starting RemoteNameRequestModule");
  pimpl_->remote_name_request_ = std::make_unique<hci::RemoteNameRequestModuleImpl>(
          stack_handler_, pimpl_->hci_layer_.get(), pimpl_->acl_scheduler_.get());

  log::info("Starting AclManagerImpl");
  pimpl_->acl_manager_ = std::make_unique<hci::AclManagerImpl>(
          stack_handler_, pimpl_->hci_layer_.get(), pimpl_->controller_.get(),
          pimpl_->acl_scheduler_.get(), pimpl_->remote_name_request_.get(), pimpl_->storage_.get());

  log::info("Starting MsftExtensionManager");
  pimpl_->msft_extension_manager_ = std::make_unique<hci::MsftExtensionManager>(
          stack_handler_, pimpl_->hci_hal_.get(), pimpl_->hci_layer_.get());

  log::info("Starting LeScanningManagerImpl");
  pimpl_->le_scanning_manager_ = std::make_unique<hci::LeScanningManagerImpl>(
          stack_handler_, pimpl_->hci_layer_.get(), pimpl_->controller_.get(),
          pimpl_->acl_manager_->GetLeAddressManager(), pimpl_->storage_.get());

  log::info("Starting LeAdvertisingManagerImpl");
  pimpl_->le_advertising_manager_ = std::make_unique<hci::LeAdvertisingManagerImpl>(
          stack_handler_, pimpl_->hci_layer_.get(), pimpl_->controller_.get(),
          pimpl_->acl_manager_->GetLeAddressManager(), pimpl_->acl_manager_.get());

  log::info("Starting DistanceMeasurementManagerImpl");
  pimpl_->distance_measurement_manager_ = std::make_unique<hci::DistanceMeasurementManagerImpl>(
          stack_handler_, pimpl_->hci_layer_.get(), pimpl_->controller_.get(),
          pimpl_->acl_manager_.get(), pimpl_->ranging_hal_.get());

  promise.set_value();
}

void Stack::handle_shut_down(std::promise<void> promise) {
  log::info("Stopping DistanceMeasurementManagerImpl");
  pimpl_->distance_measurement_manager_.reset();

  log::info("Stopping LeAdvertisingManagerImpl");
  pimpl_->le_advertising_manager_.reset();

  log::info("Stopping LeScanningManagerImpl");
  pimpl_->le_scanning_manager_.reset();

  log::info("Stopping MsftExtensionManager");
  pimpl_->msft_extension_manager_.reset();

  log::info("Stopping AclManagerImpl");
  pimpl_->acl_manager_.reset();

  log::info("Stopping RemoteNameRequestModule");
  pimpl_->remote_name_request_.reset();

  log::info("Stopping AclScheduler");
  pimpl_->acl_scheduler_.reset();

  log::info("Stopping Controller");
  pimpl_->controller_.reset();

  log::info("Stopping HCI");
  pimpl_->hci_layer_.reset();

  log::info("Stopping RangingHal");
  pimpl_->ranging_hal_.reset();

  log::info("Stopping HciHal");
  pimpl_->hci_hal_.reset();

  log::info("Stopping LinkClocker");
  pimpl_->link_clocker_.reset();

  if (pimpl_->lpp_offload_manager_) {
    log::info("Stopping LppOffloadManager");
    pimpl_->lpp_offload_manager_.reset();
  }

  if (pimpl_->socket_hal_) {
    log::info("Stopping SocketHal");
    pimpl_->socket_hal_.reset();
  }

#if TARGET_FLOSS
  log::info("Stopping SyspropsModule");
  pimpl_->sysprops_module_.reset();
#endif

  pimpl_->snoop_logger_.reset();
  pimpl_->storage_.reset();

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
