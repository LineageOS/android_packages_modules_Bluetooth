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

/**
 * Entrypoints called into Gabeldorsche from legacy stack
 *
 * Any marshalling/unmarshalling, data transformation of APIs to
 * or from the Gabeldorsche stack may be placed here.
 *
 * The idea is to effectively provide a binary interface to prevent cross
 * contamination of data structures and the like between the stacks.
 *
 * **ABSOLUTELY** No reference to Gabeldorsche stack other than well defined
 * interfaces may be made here
 */

namespace bluetooth {
namespace os {
class Handler;
}
namespace hal {
class SnoopLogger;
}

namespace hci {
namespace acl_manager {
class AclManagerClassic;
}
class Controller;
class HciInterface;
class AclManagerLe;
class RemoteNameRequestModule;
class DistanceMeasurementManager;
class LeAdvertisingManager;
class LeScanningManager;
class MsftExtensionManager;
}  // namespace hci

namespace lpp {
class LppOffloadInterface;
}

namespace storage {
class StorageModule;
}

namespace shim {

/* This returns a handler that might be used in shim to receive callbacks from
 * within the stack. */
os::Handler* GetGdShimHandler();
hci::LeAdvertisingManager* GetAdvertising();
bluetooth::hci::Controller* GetController();
hci::HciInterface* GetHciLayer();
hci::RemoteNameRequestModule* GetRemoteNameRequest();
hci::DistanceMeasurementManager* GetDistanceMeasurementManager();
hci::LeScanningManager* GetScanning();
lpp::LppOffloadInterface* GetLppOffloadManager();
hal::SnoopLogger* GetSnoopLogger();
storage::StorageModule* GetStorage();
hci::acl_manager::AclManagerClassic* GetAclManagerClassic();
hci::AclManagerLe* GetAclManagerLe();
hci::MsftExtensionManager* GetMsftExtensionManager();

}  // namespace shim
}  // namespace bluetooth
