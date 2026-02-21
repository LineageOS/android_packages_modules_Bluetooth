/*
 * Copyright 2023 The Android Open Source Project
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

#include "btif/include/btif_dm.h"

#include <bluetooth/types/ble_address_with_type.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gtest/gtest.h>

#include <cstdint>
#include <memory>

#include "bta/include/bta_api_data_types.h"
#include "bta/include/bta_dm_api.h"
#include "btif/include/btif_config.h"
#include "btif/include/btif_storage.h"
#include "btif/include/mock_core_callbacks.h"
#include "btif/include/stack_manager_t.h"
#include "hardware/bluetooth.h"
#include "main/shim/entry.h"
#include "main/shim/shim.h"
#include "main/shim/stack.h"
#include "stack/btm/btm_security.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/hci_error_code.h"
#include "storage/storage_module.h"
#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_osi_properties.h"

#define TEST_BT com::android::bluetooth::flags

using bluetooth::core::testing::MockCoreInterface;
using ::testing::ElementsAre;

namespace {
const RawAddress kRawAddress("11:22:33:44:55:66");
constexpr char kBdName[] = {'k', 'B', 'd', 'N', 'a', 'm', 'e', '\0'};
}  // namespace

namespace {
constexpr tBTM_BLE_TX_TIME_MS tx_time = 0x12345678;
constexpr tBTM_BLE_RX_TIME_MS rx_time = 0x87654321;
constexpr tBTM_BLE_IDLE_TIME_MS idle_time = 0x2468acd0;
constexpr tBTM_BLE_ENERGY_USED energy_used = 0x13579bdf;
}  // namespace

class BtifDmWithMocksTest : public ::testing::Test {
protected:
  void SetUp() override { fake_osi_ = std::make_unique<test::fake::FakeOsi>(); }

  void TearDown() override { fake_osi_.reset(); }

  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

class BtifDmTest : public BtifDmWithMocksTest {
protected:
  void SetUp() override {
    BtifDmWithMocksTest::SetUp();
    mock_core_interface_ = std::make_unique<MockCoreInterface>();
    bluetooth::legacy::testing::set_interface_to_profiles(mock_core_interface_.get());
  }

  void TearDown() override {
    bluetooth::legacy::testing::set_interface_to_profiles(nullptr);
    mock_core_interface_.reset();
    BtifDmWithMocksTest::TearDown();
  }

  std::unique_ptr<MockCoreInterface> mock_core_interface_;
};

TEST_F(BtifDmTest, bta_energy_info_cb__with_no_uid) {
  static bool invoke_energy_info_cb_entered = false;
  bluetooth::core::testing::mock_event_callbacks.invoke_energy_info_cb =
          [](bt_activity_energy_info /* energy_info */, bt_uid_traffic_t* /* uid_data */) {
            invoke_energy_info_cb_entered = true;
          };

  bluetooth::legacy::testing::bta_energy_info_cb(tx_time, rx_time, idle_time, energy_used,
                                                 BTM_CONTRL_UNKNOWN, BTA_SUCCESS);

  ASSERT_FALSE(invoke_energy_info_cb_entered);
}

class BtifDmWithUidTest : public BtifDmTest {
protected:
  void SetUp() override {
    BtifDmTest::SetUp();
    btif_dm_init(uid_set_create());
  }

  void TearDown() override {
    btif_dm_cleanup();
    BtifDmTest::TearDown();
  }
};

TEST_F(BtifDmWithUidTest, bta_energy_info_cb__with_uid) {
  static bool invoke_energy_info_cb_entered = false;
  bluetooth::core::testing::mock_event_callbacks.invoke_energy_info_cb =
          [](bt_activity_energy_info /* energy_info */, bt_uid_traffic_t* /* uid_data */) {
            invoke_energy_info_cb_entered = true;
          };
  bluetooth::legacy::testing::bta_energy_info_cb(tx_time, rx_time, idle_time, energy_used,
                                                 BTM_CONTRL_UNKNOWN, BTA_SUCCESS);

  ASSERT_TRUE(invoke_energy_info_cb_entered);
}

// Mock implementation for GetStorage()
static bluetooth::storage::StorageModule* s_StorageModule = nullptr;
bluetooth::storage::StorageModule* bluetooth::shim::GetStorage() { return s_StorageModule; }

bluetooth::os::Handler* bluetooth::shim::GetGdShimHandler() { return nullptr; }
bluetooth::hci::LeAdvertisingManager* bluetooth::shim::GetAdvertising() { return nullptr; }
bluetooth::hci::Controller* bluetooth::shim::GetController() { return nullptr; }
bluetooth::hci::HciInterface* bluetooth::shim::GetHciLayer() { return nullptr; }
bluetooth::hci::RemoteNameRequestModule* bluetooth::shim::GetRemoteNameRequest() { return nullptr; }
bluetooth::hci::LeScanningManager* bluetooth::shim::GetScanning() { return nullptr; }
bluetooth::hci::DistanceMeasurementManager* bluetooth::shim::GetDistanceMeasurementManager() {
  return nullptr;
}
bluetooth::hal::SnoopLogger* bluetooth::shim::GetSnoopLogger() { return nullptr; }
bluetooth::lpp::LppOffloadInterface* bluetooth::shim::GetLppOffloadManager() { return nullptr; }
bluetooth::hci::AclManagerLe* bluetooth::shim::GetAclManagerLe() { return nullptr; }
bluetooth::hci::acl_manager::AclManagerClassic* bluetooth::shim::GetAclManagerClassic() {
  return nullptr;
}
bluetooth::hci::MsftExtensionManager* bluetooth::shim::GetMsftExtensionManager() { return nullptr; }

bool bluetooth::shim::is_gd_stack_started_up() { return s_StorageModule != nullptr; }

class BtifDmWithStackTest : public BtifDmTest {
protected:
  void SetUp() override {
    BtifDmTest::SetUp();
    thread_ = new bluetooth::os::Thread("gd_stack_thread", bluetooth::os::Thread::Priority::NORMAL);
    storage_module_ = new bluetooth::storage::StorageModule(new bluetooth::os::Handler(thread_));
    s_StorageModule = storage_module_;
  }

  void TearDown() override {
    s_StorageModule = nullptr;
    delete storage_module_;
    delete thread_;
    BtifDmTest::TearDown();
  }

  bluetooth::os::Thread* thread_;
  bluetooth::storage::StorageModule* storage_module_;
};

TEST_F(BtifDmWithStackTest, btif_dm_search_services_evt__BTA_DM_NAME_READ_EVT) {
  static struct {
    bt_status_t status;
    RawAddress bd_addr;
    uint8_t address_type;
    int num_properties;
    std::vector<bt_property_t> properties;
  } invoke_remote_device_properties_cb{
          .status = BT_STATUS_NOT_READY,
          .bd_addr = RawAddress::kEmpty,
          .address_type = 0,
          .num_properties = -1,
          .properties = {},
  };

  bluetooth::core::testing::mock_event_callbacks.invoke_remote_device_properties_cb =
          [](bt_status_t status, RawAddress bd_addr, uint8_t address_type, int num_properties,
             bt_property_t* properties) {
            invoke_remote_device_properties_cb = {
                    .status = status,
                    .bd_addr = bd_addr,
                    .address_type = address_type,
                    .num_properties = num_properties,
                    .properties = std::vector<bt_property_t>(properties,
                                                             properties + (size_t)num_properties),
            };
          };

  BD_NAME bd_name;
  bd_name_from_char_pointer(bd_name, kBdName);

  bluetooth::legacy::testing::btif_on_name_read(kRawAddress, HCI_SUCCESS, bd_name, true);

  ASSERT_EQ(BT_STATUS_SUCCESS, invoke_remote_device_properties_cb.status);
  ASSERT_EQ(kRawAddress, invoke_remote_device_properties_cb.bd_addr);
  ASSERT_EQ(0, invoke_remote_device_properties_cb.address_type);
  ASSERT_EQ(2, invoke_remote_device_properties_cb.num_properties);
  ASSERT_EQ(BT_PROPERTY_BDNAME, invoke_remote_device_properties_cb.properties[0].type);
  ASSERT_EQ((int)strlen(kBdName), invoke_remote_device_properties_cb.properties[0].len);
  ASSERT_STREQ(kBdName, (const char*)invoke_remote_device_properties_cb.properties[0].val);
  ASSERT_EQ(BT_PROPERTY_REMOTE_ADDR_TYPE, invoke_remote_device_properties_cb.properties[1].type);
  ASSERT_EQ((int)sizeof(tBLE_ADDR_TYPE), invoke_remote_device_properties_cb.properties[1].len);
  ASSERT_EQ(BLE_ADDR_PUBLIC,
            *(tBLE_ADDR_TYPE*)invoke_remote_device_properties_cb.properties[1].val);
}

TEST_F(BtifDmWithStackTest, btif_dm_get_local_class_of_device__default) {
  DEV_CLASS dev_class = btif_dm_get_local_class_of_device();
  ASSERT_EQ(dev_class, kDevClassUnclassified);
}

std::string kClassOfDeviceText = "1,2,3";
DEV_CLASS kClassOfDevice = {1, 2, 3};
TEST_F(BtifDmWithStackTest, btif_dm_get_local_class_of_device__with_property) {
  test::mock::osi_properties::osi_property_get.body = [](const char* /* key */, char* value,
                                                         const char* /* default_value */) {
    std::copy(kClassOfDeviceText.begin(), kClassOfDeviceText.end(), value);
    return kClassOfDeviceText.size();
  };

  DEV_CLASS dev_class = btif_dm_get_local_class_of_device();
  if (dev_class != kClassOfDevice) {
    // If BAP is enabled, an extra bit gets set.
    DEV_CLASS dev_class_with_bap = kClassOfDevice;
    dev_class_with_bap[1] |= 0x01 << 6;
    ASSERT_EQ(dev_class, dev_class_with_bap);
  }
  test::mock::osi_properties::osi_property_get = {};
}

// Static variables to hold callback results for tests.
static bt_bond_state_t latest_bond_state;
static int bond_state_changed_cb_count;

TEST_F(BtifDmWithStackTest, auth_cmpl_evt_fails_when_bonding) {
  // This test verifies that when authentication fails during an active bonding
  // process, the bond state is correctly updated and reported.

  // Mock the bond state changed callback to capture the latest state.
  latest_bond_state = BT_BOND_STATE_NONE;
  bluetooth::core::testing::mock_event_callbacks.invoke_bond_state_changed_cb =
          [](bt_status_t, RawAddress, tBT_TRANSPORT, bt_bond_state_t state, PairingType, int,
             PairingInitiator) { latest_bond_state = state; };

  // Simulate a PIN request to transition the internal state to BONDING.
  tBTA_DM_SEC sec_event_pin_req{};
  sec_event_pin_req.pin_req.bd_addr = kRawAddress;
  bd_name_from_char_pointer(sec_event_pin_req.pin_req.bd_name, kBdName);
  btif_dm_sec_evt(BTA_DM_PIN_REQ_EVT, &sec_event_pin_req);
  ASSERT_EQ(latest_bond_state, BT_BOND_STATE_BONDING);

  // Simulate an authentication complete event with a failure status.
  tBTA_DM_SEC sec_event_auth_cmpl{};
  sec_event_auth_cmpl.auth_cmpl.bd_addr = kRawAddress;
  sec_event_auth_cmpl.auth_cmpl.success = false;
  sec_event_auth_cmpl.auth_cmpl.fail_reason = HCI_ERR_AUTH_FAILURE;
  btif_dm_sec_evt(BTA_DM_AUTH_CMPL_EVT, &sec_event_auth_cmpl);

  // Verify that the bond state transitions back to NONE.
  ASSERT_EQ(latest_bond_state, BT_BOND_STATE_NONE);
}

TEST_F(BtifDmWithStackTest, auth_cmpl_evt_fails_when_not_bonding) {
  // This test verifies that if an authentication failure occurs when there is
  // no active bonding process, no bond state change callback is triggered.

  // Mock the bond state changed callback to count invocations.
  bond_state_changed_cb_count = 0;
  bluetooth::core::testing::mock_event_callbacks.invoke_bond_state_changed_cb =
          [](bt_status_t, RawAddress, tBT_TRANSPORT, bt_bond_state_t, PairingType, int,
             PairingInitiator) { bond_state_changed_cb_count++; };

  // The initial state is BT_BOND_STATE_NONE (not bonding).
  // Simulate an authentication complete event with a failure status.
  tBTA_DM_SEC sec_event_auth_cmpl{};
  sec_event_auth_cmpl.auth_cmpl.bd_addr = kRawAddress;
  sec_event_auth_cmpl.auth_cmpl.success = false;
  sec_event_auth_cmpl.auth_cmpl.fail_reason = HCI_ERR_AUTH_FAILURE;
  btif_dm_sec_evt(BTA_DM_AUTH_CMPL_EVT, &sec_event_auth_cmpl);

  // Verify that the bond state changed callback was not invoked.
  ASSERT_EQ(bond_state_changed_cb_count, 0);
}

TEST_F(BtifDmWithStackTest, test_btif_dm_reset_irk) {
  if (com_android_bluetooth_flags_btsec_cycle_irks()) {
    btif_storage_add_bredr_keys(kRawAddress,
                                PairingType{.algorithm = PairingAlgorithm::BREDR_LEGACY,
                                            .legacy_variant = LegacyPairingVariant::PIN},
                                SAMPLE_LTK, 0, 0);

    bt_status_t status = btif_storage_remove_bonded_device(kRawAddress);

    ASSERT_EQ(status, BT_STATUS_SUCCESS);

    auto paired_devices = btif_config_get_paired_devices();

    ASSERT_TRUE(paired_devices.empty());
  }
  thread_->GetReactor()->WaitForIdle(std::chrono::seconds(2));
}

TEST_F_WITH_FLAGS(BtifDmWithStackTest, btif_is_interesting_le_service_gmcs,
                  REQUIRES_FLAGS_ENABLED(
                          ACONFIG_FLAG(TEST_BT, leaudio_peripheral_mcp_link_abstraction_layer))) {
  auto uuid_gmcs = bluetooth::Uuid::From16Bit(0x1849);
  EXPECT_TRUE(btif_is_interesting_le_service(uuid_gmcs));

  auto uuid_le_audio = bluetooth::Uuid::From16Bit(0x184E);
  EXPECT_TRUE(btif_is_interesting_le_service(uuid_le_audio));

  auto uuid_random = bluetooth::Uuid::From16Bit(0x9999);
  EXPECT_FALSE(btif_is_interesting_le_service(uuid_random));
}
