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

#include "hci/acl_manager.h"
#include "hci/acl_manager/acl_manager_classic.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_interface.h"
#include "hci/le_advertising_manager.h"
#include "hci/le_scanning_manager.h"
#include "hci/remote_name_request.h"
#include "lpp/lpp_offload_interface.h"
#include "os/handler.h"
#include "os/thread.h"

// The shim layer implementation on the Gd stack side.
namespace bluetooth {

namespace hal {
class SnoopLogger;
}

namespace hci {
class MsftExtensionManager;
}

namespace storage {
class StorageModule;
}

namespace shim {

class Acl;

// GD shim stack, having modes corresponding to legacy stack
class Stack {
public:
  static Stack* GetInstance();

  Stack();
  Stack(const Stack&) = delete;
  Stack& operator=(const Stack&) = delete;

  virtual ~Stack() = default;

  // Running mode, everything is up
  void StartEverything();

  void Stop();
  bool IsRunning();

  virtual Acl* GetAcl() const;
  virtual storage::StorageModule* GetStorage() const;
  virtual hal::SnoopLogger* GetSnoopLogger() const;
  virtual lpp::LppOffloadInterface* GetLppOffloadInterface() const;
  virtual hci::HciInterface* GetHciLayer() const;
  virtual hci::Controller* GetController() const;
  virtual hci::RemoteNameRequestModule* GetRemoteNameRequest() const;
  virtual hci::acl_manager::AclManagerClassic* GetAclManagerClassic() const;
  virtual hci::AclManager* GetAclManager() const;
  virtual hci::MsftExtensionManager* GetMsftExtensionManager() const;
  virtual hci::LeScanningManager* GetLeScanningManager() const;
  virtual hci::LeAdvertisingManager* GetLeAdvertisingManager() const;
  virtual hci::DistanceMeasurementManager* GetDistanceMeasurementManager() const;
  os::Handler* GetHandler();

  void Dump(int fd, std::promise<void> promise) const;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;

  mutable std::recursive_mutex mutex_;
  bool is_running_ = false;
  os::Thread* stack_thread_ = nullptr;
  os::Handler* stack_handler_ = nullptr;

  os::Thread* management_thread_ = nullptr;
  os::Handler* management_handler_ = nullptr;

  void handle_start_up(std::promise<void> promise);
  void handle_shut_down(std::promise<void> promise);
  static std::chrono::milliseconds get_gd_stack_timeout_ms(bool is_start);
};

}  // namespace shim
}  // namespace bluetooth
