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

#include "main/shim/entry.h"

#include "hal/snoop_logger.h"
#include "hci/acl_manager/acl_manager_le.h"
#include "hci/controller.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_interface.h"
#include "hci/le_advertising_manager.h"
#include "hci/le_scanning_manager.h"
#include "hci/msft.h"
#include "hci/remote_name_request.h"
#include "lpp/lpp_offload_manager.h"
#include "main/shim/shim.h"
#include "main/shim/stack.h"
#include "os/handler.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace shim {

os::Handler* GetGdShimHandler() { return Stack::GetInstance()->GetHandler(); }

hci::LeAdvertisingManager* GetAdvertising() {
  return Stack::GetInstance()->GetLeAdvertisingManager();
}

hci::Controller* GetController() { return Stack::GetInstance()->GetController(); }

hci::HciInterface* GetHciLayer() { return Stack::GetInstance()->GetHciLayer(); }

hci::RemoteNameRequestModule* GetRemoteNameRequest() {
  return Stack::GetInstance()->GetRemoteNameRequest();
}

hci::LeScanningManager* GetScanning() { return Stack::GetInstance()->GetLeScanningManager(); }

hci::DistanceMeasurementManager* GetDistanceMeasurementManager() {
  return Stack::GetInstance()->GetDistanceMeasurementManager();
}

hal::SnoopLogger* GetSnoopLogger() { return Stack::GetInstance()->GetSnoopLogger(); }

lpp::LppOffloadInterface* GetLppOffloadManager() {
  return Stack::GetInstance()->GetLppOffloadInterface();
}

storage::StorageModule* GetStorage() { return Stack::GetInstance()->GetStorage(); }

hci::acl_manager::AclManagerClassic* GetAclManagerClassic() {
  return Stack::GetInstance()->GetAclManagerClassic();
}
hci::AclManagerLe* GetAclManagerLe() { return Stack::GetInstance()->GetAclManagerLe(); }

hci::MsftExtensionManager* GetMsftExtensionManager() {
  return Stack::GetInstance()->GetMsftExtensionManager();
}

bool is_gd_stack_started_up() { return Stack::GetInstance()->IsRunning(); }

}  // namespace shim
}  // namespace bluetooth
