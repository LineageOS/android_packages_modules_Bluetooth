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

#pragma once

#include <functional>
#include <mutex>

#include "hal/gatt_hal_impl.h"
#include "hal/hci_hal_impl.h"
#include "hal/link_clocker.h"
#include "hal/ranging_hal_impl.h"
#include "hal/snoop_logger.h"
#include "hal/socket_hal_impl.h"
#include "hci/acl_manager/acl_manager_classic_impl.h"
#include "hci/acl_manager/acl_manager_le_impl.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/controller_impl.h"
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
#include "main/shim/stack.h"
#include "os/handler.h"
#include "os/system_properties.h"
#include "os/thread.h"
#include "storage/storage_module.h"

#ifndef TARGET_FLOSS
#include "hci/distance_measurement_manager_impl.h"
#endif

// The shim layer implementation on the Gd stack side.
namespace bluetooth::shim {

// GD shim stack, having modes corresponding to legacy stack
class StackImpl : public Stack {
public:
  StackImpl();
  StackImpl(const Stack&) = delete;
  StackImpl& operator=(const Stack&) = delete;

  virtual ~StackImpl() = default;

  // Running mode, everything is up
  void StartEverything() override;

  void Stop() override;
  bool IsRunning() override;

  virtual Acl* GetAcl() const override;
  virtual storage::StorageModule* GetStorage() const override;
  virtual hal::SnoopLogger* GetSnoopLogger() const override;
  virtual lpp::LppOffloadInterface* GetLppOffloadInterface() const override;
  virtual hci::HciInterface* GetHciLayer() const override;
  virtual hci::Controller* GetController() const override;
  virtual hci::RemoteNameRequestModule* GetRemoteNameRequest() const override;
  virtual hci::acl_manager::AclManagerClassic* GetAclManagerClassic() const override;
  virtual hci::AclManagerLe* GetAclManagerLe() const override;
  virtual hci::MsftExtensionManager* GetMsftExtensionManager() const override;
  virtual hci::LeScanningManager* GetLeScanningManager() const override;
  virtual hci::LeAdvertisingManager* GetLeAdvertisingManager() const override;
  virtual hci::DistanceMeasurementManager* GetDistanceMeasurementManager() const override;
  os::Handler* GetHandler();

  void Dump(int fd, std::promise<void> promise) const override;

private:
  struct Modules {
    Modules(os::Handler* handler);

    Acl* acl_ = nullptr;
    storage::StorageModule storage_;
    hal::SnoopLogger snoop_logger_;
    hal::LinkClocker link_clocker_;
    hal::HciHalImpl hci_hal_;
    hal::RangingHalImpl ranging_hal_;
    hci::HciLayer hci_layer_;
    hci::ControllerImpl controller_;
    hci::acl_manager::AclScheduler acl_scheduler_;
    hci::RemoteNameRequestModuleImpl remote_name_request_;
    hci::acl_manager::RoundRobinScheduler round_robin_scheduler_;
    hci::acl_manager::AclManagerClassicImpl acl_manager_classic_;
    hci::acl_manager::AclManagerLeImpl acl_manager_;
#ifndef TARGET_FLOSS
    hci::DistanceMeasurementManagerImpl distance_measurement_manager_;
#endif
    hci::LeScanningManagerImpl le_scanning_manager_;
    hci::MsftExtensionManager msft_extension_manager_;
    hci::LeAdvertisingManagerImpl le_advertising_manager_;
    hal::SocketHalImpl socket_hal_;
    hal::GattHalImpl gatt_hal_;
    lpp::LppOffloadManager lpp_offload_manager_;
  };

  mutable std::optional<Modules> modules_;

  mutable std::recursive_mutex mutex_;
  bool is_running_ = false;
  os::Thread* stack_thread_ = nullptr;
  os::Handler* stack_handler_ = nullptr;

  os::Thread* management_thread_ = nullptr;
  os::Handler* management_handler_ = nullptr;

  void handle_start_up();
  void handle_start_up_old(std::promise<void> promise);
  void handle_shut_down();
  void handle_shut_down_old(std::promise<void> promise);
};

}  // namespace bluetooth::shim
