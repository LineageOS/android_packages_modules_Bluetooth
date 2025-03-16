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

#include "test/mock/mock_main_shim_entry.h"

#include "hci/acl_manager_mock.h"
#include "hci/controller_interface_mock.h"
#include "hci/distance_measurement_manager_mock.h"
#include "hci/hci_interface.h"
#include "hci/le_advertising_manager_mock.h"
#include "hci/le_scanning_manager_mock.h"
#include "lpp/lpp_offload_interface_mock.h"
#include "main/shim/entry.h"
#include "main/shim/shim.h"
#include "os/handler.h"
#include "storage/storage_module.h"

namespace test {
namespace mock {
bool bluetooth_shim_is_gd_stack_started_up = false;
}  // namespace mock
}  // namespace test

namespace bluetooth {
namespace hci {
namespace testing {

std::unique_ptr<MockAclManager> mock_acl_manager_;
std::unique_ptr<MockControllerInterface> mock_controller_;
std::unique_ptr<MockHciLayer> mock_hci_layer_;
os::Handler* mock_gd_shim_handler_{nullptr};
MockLeAdvertisingManager* mock_le_advertising_manager_{nullptr};
MockLeScanningManager* mock_le_scanning_manager_{nullptr};
MockDistanceMeasurementManager* mock_distance_measurement_manager_{nullptr};
storage::StorageModule* mock_storage_{nullptr};

}  // namespace testing
}  // namespace hci

namespace lpp::testing {
MockLppOffloadInterface* mock_lpp_offload_interface_{nullptr};
}  // namespace lpp::testing

class Dumpsys;

namespace shim {

hci::AclManager* GetAclManager() { return hci::testing::mock_acl_manager_.get(); }
hci::ControllerInterface* GetController() { return hci::testing::mock_controller_.get(); }
hci::HciInterface* GetHciLayer() { return hci::testing::mock_hci_layer_.get(); }
hci::LeAdvertisingManager* GetAdvertising() { return hci::testing::mock_le_advertising_manager_; }
hci::LeScanningManager* GetScanning() { return hci::testing::mock_le_scanning_manager_; }
hci::DistanceMeasurementManager* GetDistanceMeasurementManager() {
  return hci::testing::mock_distance_measurement_manager_;
}
os::Handler* GetGdShimHandler() { return hci::testing::mock_gd_shim_handler_; }
hal::SnoopLogger* GetSnoopLogger() { return nullptr; }
storage::StorageModule* GetStorage() { return hci::testing::mock_storage_; }
metrics::CounterMetrics* GetCounterMetrics() { return nullptr; }
hci::MsftExtensionManager* GetMsftExtensionManager() { return nullptr; }
hci::RemoteNameRequestModule* GetRemoteNameRequest() { return nullptr; }
lpp::LppOffloadInterface* GetLppOffloadManager() {
  return lpp::testing::mock_lpp_offload_interface_;
}
bool is_gd_stack_started_up() { return test::mock::bluetooth_shim_is_gd_stack_started_up; }

}  // namespace shim
}  // namespace bluetooth
