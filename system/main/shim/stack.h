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

#include "hci/acl_manager/acl_manager_classic.h"
#include "hci/acl_manager/acl_manager_le.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_interface.h"
#include "hci/le_advertising_manager.h"
#include "hci/le_scanning_manager.h"
#include "hci/remote_name_request.h"
#include "lpp/lpp_offload_interface.h"
#include "os/handler.h"

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

  virtual ~Stack() = default;

  // Running mode, everything is up
  virtual void StartEverything() = 0;

  virtual void Stop() = 0;
  virtual bool IsRunning() = 0;

  virtual Acl* GetAcl() const = 0;
  virtual storage::StorageModule* GetStorage() const = 0;
  virtual hal::SnoopLogger* GetSnoopLogger() const = 0;
  virtual lpp::LppOffloadInterface* GetLppOffloadInterface() const = 0;
  virtual hci::HciInterface* GetHciLayer() const = 0;
  virtual hci::Controller* GetController() const = 0;
  virtual hci::RemoteNameRequestModule* GetRemoteNameRequest() const = 0;
  virtual hci::acl_manager::AclManagerClassic* GetAclManagerClassic() const = 0;
  virtual hci::AclManagerLe* GetAclManagerLe() const = 0;
  virtual hci::MsftExtensionManager* GetMsftExtensionManager() const = 0;
  virtual hci::LeScanningManager* GetLeScanningManager() const = 0;
  virtual hci::LeAdvertisingManager* GetLeAdvertisingManager() const = 0;
  virtual hci::DistanceMeasurementManager* GetDistanceMeasurementManager() const = 0;
  virtual os::Handler* GetHandler() = 0;

  virtual void Dump(int fd, std::promise<void> promise) const = 0;
};

}  // namespace shim
}  // namespace bluetooth
