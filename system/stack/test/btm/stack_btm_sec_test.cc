/*
 *  Copyright 2020 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

#include <bluetooth/types/address.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <vector>

#include "hci/hci_layer_mock.h"
#include "internal_include/bt_target.h"
#include "stack/btm/btm_ble_sec.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_device_record.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_security.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "stack/include/sec_hci_link_interface.h"
#include "stack/test/btm/btm_test_fixtures.h"
#include "test/mock/mock_main_shim_entry.h"

using namespace bluetooth;

using ::testing::Return;
using ::testing::Test;

namespace {
const RawAddress kRawAddress = RawAddress("11:22:33:44:55:66");
const uint8_t kBdName[] = "kBdName";
constexpr char kTimeFormat[] = "%Y-%m-%d %H:%M:%S";
}  // namespace

using bluetooth::legacy::testing::wipe_secrets_and_remove;

constexpr size_t kBtmSecMaxDeviceRecords = static_cast<size_t>(BTM_SEC_MAX_DEVICE_RECORDS + 1);

class StackBtmSecTest : public BtmWithMocksTest {
protected:
  void SetUp() override { BtmWithMocksTest::SetUp(); }
  void TearDown() override { BtmWithMocksTest::TearDown(); }
};

class StackBtmSecWithQueuesTest : public StackBtmSecTest {
protected:
  void SetUp() override {
    StackBtmSecTest::SetUp();
    up_thread_ = new bluetooth::os::Thread("up_thread", bluetooth::os::Thread::Priority::NORMAL);
    up_handler_ = new bluetooth::os::Handler(up_thread_);
    down_thread_ =
            new bluetooth::os::Thread("down_thread", bluetooth::os::Thread::Priority::NORMAL);
    down_handler_ = new bluetooth::os::Handler(down_thread_);
    bluetooth::hci::testing::mock_hci_layer_ =
            std::make_unique<bluetooth::hci::testing::MockHciLayer>();
    bluetooth::hci::testing::mock_gd_shim_handler_ = up_handler_;
  }
  void TearDown() override {
    up_handler_->Clear();
    delete up_handler_;
    delete up_thread_;
    down_handler_->Clear();
    delete down_handler_;
    delete down_thread_;
    bluetooth::hci::testing::mock_hci_layer_.reset();
    StackBtmSecTest::TearDown();
  }
  bluetooth::common::BidiQueue<bluetooth::hci::ScoView, bluetooth::hci::ScoBuilder> sco_queue_{10};
  bluetooth::os::Thread* up_thread_;
  bluetooth::os::Handler* up_handler_;
  bluetooth::os::Thread* down_thread_;
  bluetooth::os::Handler* down_handler_;
};

class StackBtmSecWithInitFreeTest : public StackBtmSecWithQueuesTest {
public:
protected:
  void SetUp() override {
    main_thread_start_up();
    post_on_bt_main([]() { log::info("Main thread started up"); });
    StackBtmSecWithQueuesTest::SetUp();
    get_security_client_interface().BTM_Sec_Init();
  }
  void TearDown() override {
    post_on_bt_main([]() { log::info("Main thread shutting down"); });
    main_thread_shut_down();
    get_security_client_interface().BTM_Sec_Free();
    StackBtmSecWithQueuesTest::TearDown();
  }
};

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_encrypt_change) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  // Check the collision conditionals
  ::BtmSecurity::Get().collision_start_time_ = 0UL;
  btm_sec_encrypt_change(classic_handle, HCI_ERR_LMP_ERR_TRANS_COLLISION, 0x01, 0x10);
  uint64_t collision_start_time = ::BtmSecurity::Get().collision_start_time_;
  ASSERT_NE(0UL, collision_start_time);

  ::BtmSecurity::Get().collision_start_time_ = 0UL;
  btm_sec_encrypt_change(classic_handle, HCI_ERR_DIFF_TRANSACTION_COLLISION, 0x01, 0x10);
  collision_start_time = ::BtmSecurity::Get().collision_start_time_;
  ASSERT_NE(0UL, collision_start_time);

  // No device
  ::BtmSecurity::Get().collision_start_time_ = 0;
  btm_sec_encrypt_change(classic_handle, HCI_SUCCESS, 0x01, 0x10);
  ASSERT_EQ(0UL, ::BtmSecurity::Get().collision_start_time_);

  // Setup device
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);
  ASSERT_EQ(BTM_SEC_IN_USE, p_device->sec_rec.sec_flags);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  // With classic device encryption enable
  btm_sec_encrypt_change(classic_handle, HCI_SUCCESS, 0x01, 0x10);
  ASSERT_EQ(BTM_SEC_IN_USE | BTM_SEC_AUTHENTICATED | BTM_SEC_ENCRYPTED,
            p_device->sec_rec.sec_flags);

  // With classic device encryption disable
  btm_sec_encrypt_change(classic_handle, HCI_SUCCESS, 0x00, 0x10);
  ASSERT_EQ(BTM_SEC_IN_USE | BTM_SEC_AUTHENTICATED, p_device->sec_rec.sec_flags);
  p_device->sec_rec.sec_flags = BTM_SEC_IN_USE;

  // With le device encryption enable
  btm_sec_encrypt_change(ble_handle, HCI_SUCCESS, 0x01, 0x10);
  ASSERT_EQ(BTM_SEC_IN_USE | BTM_SEC_LE_ENCRYPTED, p_device->sec_rec.sec_flags);

  // With le device encryption disable
  btm_sec_encrypt_change(ble_handle, HCI_SUCCESS, 0x00, 0x10);
  ASSERT_EQ(BTM_SEC_IN_USE, p_device->sec_rec.sec_flags);
  p_device->sec_rec.sec_flags = BTM_SEC_IN_USE;

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecWithInitFreeTest, BTM_SetEncryption) {
  const RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  const tBT_TRANSPORT transport{BT_TRANSPORT_LE};
  tBTM_SEC_CALLBACK* p_callback{nullptr};
  tBTM_BLE_SEC_ACT sec_act{BTM_BLE_SEC_ENCRYPT};

  // No device
  ASSERT_EQ(tBTM_STATUS::BTM_WRONG_MODE, get_security_client_interface().BTM_SetEncryption(
                                                 bd_addr, transport, p_callback, nullptr, sec_act));

  // With device
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);
  p_device->hci_handle = 0x1234;

  ASSERT_EQ(tBTM_STATUS::BTM_WRONG_MODE, get_security_client_interface().BTM_SetEncryption(
                                                 bd_addr, transport, p_callback, nullptr, sec_act));

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecTest, btm_ble_sec_req_act_text) {
  ASSERT_EQ("BTM_BLE_SEC_REQ_ACT_NONE", btm_ble_sec_req_act_text(BTM_BLE_SEC_REQ_ACT_NONE));
  ASSERT_EQ("BTM_BLE_SEC_REQ_ACT_ENCRYPT", btm_ble_sec_req_act_text(BTM_BLE_SEC_REQ_ACT_ENCRYPT));
  ASSERT_EQ("BTM_BLE_SEC_REQ_ACT_PAIR", btm_ble_sec_req_act_text(BTM_BLE_SEC_REQ_ACT_PAIR));
  ASSERT_EQ("BTM_BLE_SEC_REQ_ACT_DISCARD", btm_ble_sec_req_act_text(BTM_BLE_SEC_REQ_ACT_DISCARD));
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_allocate_dev_rec__all) {
  BtmDevice* p_devices[kBtmSecMaxDeviceRecords];
  const RawAddress bd_addr = RawAddress("11:22:33:44:55:66");

  // Fill up the records
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    for (size_t i = 0; i < kBtmSecMaxDeviceRecords; i++) {
      ASSERT_EQ(i, list_length(::BtmSecurity::Get().sec_dev_rec_));
      p_devices[i] = btm_sec_allocate_dev_rec(bd_addr);
      ASSERT_NE(nullptr, p_devices[i]);
    }
  } else {
    for (size_t i = 0; i < kBtmSecMaxDeviceRecords; i++) {
      p_devices[i] = btm_sec_allocate_dev_rec(bd_addr);
    }
  }

  // Second pass up the records
  if (!com_android_bluetooth_flags_use_array_instead_list_in_sec_dev_rec()) {
    for (size_t i = 0; i < kBtmSecMaxDeviceRecords; i++) {
      ASSERT_EQ(kBtmSecMaxDeviceRecords, list_length(::BtmSecurity::Get().sec_dev_rec_));
      p_devices[i] = btm_sec_allocate_dev_rec(bd_addr);
      ASSERT_NE(nullptr, p_devices[i]);
    }
  } else {
    for (size_t i = 0; i < kBtmSecMaxDeviceRecords; i++) {
      /**
       * Since we are now using BtmSecurity::Get().device_records_ as static array, so
       * there will be no deletion/creation of records, and hence the addresses will be the same.
       * So, need to store the timestamp, before the second allocation of record (or clean and
       * re-allocate in this case) and then see whether the timestamp has changed.
       */
      auto timestamp = p_devices[i]->timestamp;

      BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
      ASSERT_NE(nullptr, p_device);               // must be a valid entry
      ASSERT_NE(timestamp, p_device->timestamp);  // should be a new record
      p_devices[i] = p_device;
    }
  }

  // NOTE: The memory allocated for each record is automatically
  // allocated by the btm module and freed when the device record
  // list is freed.
  // Further, the memory for each record is reused when necessary.
}

TEST_F(StackBtmSecTest, btm_oob_data_text) {
  std::vector<std::pair<tBTM_OOB_DATA, std::string>> datas = {
          std::make_pair(BTM_OOB_NONE, "BTM_OOB_NONE"),
          std::make_pair(BTM_OOB_PRESENT_192, "BTM_OOB_PRESENT_192"),
          std::make_pair(BTM_OOB_PRESENT_256, "BTM_OOB_PRESENT_256"),
          std::make_pair(BTM_OOB_PRESENT_192_AND_256, "BTM_OOB_PRESENT_192_AND_256"),
          std::make_pair(BTM_OOB_UNKNOWN, "BTM_OOB_UNKNOWN"),
  };
  for (const auto& data : datas) {
    ASSERT_STREQ(data.second.c_str(), btm_oob_data_text(data.first).c_str());
  }
  auto unknown = std::format("UNKNOWN[{}]", std::numeric_limits<std::uint8_t>::max());
  ASSERT_STREQ(
          unknown.c_str(),
          btm_oob_data_text(static_cast<tBTM_OOB_DATA>(std::numeric_limits<std::uint8_t>::max()))
                  .c_str());
}

TEST_F(StackBtmSecTest, bond_type_text) {
  std::vector<std::pair<tBTM_BOND_TYPE, std::string>> datas = {
          std::make_pair(BOND_TYPE_UNKNOWN, "BOND_TYPE_UNKNOWN"),
          std::make_pair(BOND_TYPE_PERSISTENT, "BOND_TYPE_PERSISTENT"),
          std::make_pair(BOND_TYPE_TEMPORARY, "BOND_TYPE_TEMPORARY"),
  };
  for (const auto& data : datas) {
    ASSERT_STREQ(data.second.c_str(), bond_type_text(data.first).c_str());
  }
  auto unknown = std::format("UNKNOWN[{}]", std::numeric_limits<std::uint8_t>::max());
  ASSERT_STREQ(unknown.c_str(),
               bond_type_text(static_cast<tBTM_BOND_TYPE>(std::numeric_limits<std::uint8_t>::max()))
                       .c_str());
}

TEST_F(StackBtmSecWithInitFreeTest, wipe_secrets_and_remove) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  // Setup device
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);
  ASSERT_EQ(BTM_SEC_IN_USE, p_device->sec_rec.sec_flags);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_rmt_name_request_complete) {
  btm_cb.history_ = std::make_shared<TimestampedStringCircularBuffer>(kBtmLogHistoryBufferSize);

  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_SUCCESS);
  btm_sec_rmt_name_request_complete(nullptr, nullptr, HCI_SUCCESS);
  btm_sec_rmt_name_request_complete(nullptr, kBdName, HCI_SUCCESS);
  btm_sec_rmt_name_request_complete(&kRawAddress, nullptr, HCI_SUCCESS);

  btm_sec_rmt_name_request_complete(&kRawAddress, kBdName, HCI_ERR_HW_FAILURE);
  btm_sec_rmt_name_request_complete(nullptr, nullptr, HCI_ERR_HW_FAILURE);
  btm_sec_rmt_name_request_complete(nullptr, kBdName, HCI_ERR_HW_FAILURE);
  btm_sec_rmt_name_request_complete(&kRawAddress, nullptr, HCI_ERR_HW_FAILURE);

  std::vector<common::TimestampedEntry<std::string>> history = btm_cb.history_->Pull();
  for (auto& record : history) {
    time_t then = record.timestamp / 1000;
    struct tm tm;
    localtime_r(&then, &tm);
    auto s2 = common::StringFormatTime(kTimeFormat, tm);
    log::debug("{}.{} {}", s2, static_cast<unsigned int>(record.timestamp % 1000), record.entry);
  }
  ASSERT_EQ(8U, history.size());
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_temp_bond_auth_authenticated_temporary) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  p_device->sec_rec.sec_flags |= BTM_SEC_AUTHENTICATED;
  p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
  p_device->sec_rec.bond_type = BOND_TYPE_TEMPORARY;

  BtmSecurity::Get().security_mode_ = BTM_SEC_MODE_SERVICE;
  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;

  uint16_t sec_req = BTM_SEC_IN_AUTHENTICATE;
  tBTM_STATUS status = tBTM_STATUS::BTM_UNDEFINED;

  status = btm_sec_service_access_request(bd_addr, false, sec_req, NULL, NULL);

  ASSERT_EQ(status, tBTM_STATUS::BTM_FAILED_ON_SECURITY);
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_temp_bond_auth_non_authenticated_temporary) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  p_device->sec_rec.sec_flags &= ~BTM_SEC_AUTHENTICATED;
  p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
  p_device->sec_rec.bond_type = BOND_TYPE_TEMPORARY;

  BtmSecurity::Get().security_mode_ = BTM_SEC_MODE_SERVICE;
  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;

  uint16_t sec_req = BTM_SEC_IN_AUTHENTICATE;
  tBTM_STATUS status = tBTM_STATUS::BTM_UNDEFINED;

  status = btm_sec_service_access_request(bd_addr, false, sec_req, NULL, NULL);

  // We're testing the temp bonding security behavior here, so all we care about
  // is that it doesn't fail on security.
  ASSERT_NE(status, tBTM_STATUS::BTM_FAILED_ON_SECURITY);
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_temp_bond_auth_authenticated_persistent) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  p_device->sec_rec.sec_flags |= BTM_SEC_AUTHENTICATED;
  p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
  p_device->sec_rec.bond_type = BOND_TYPE_PERSISTENT;

  BtmSecurity::Get().security_mode_ = BTM_SEC_MODE_SERVICE;
  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;

  uint16_t sec_req = BTM_SEC_IN_AUTHENTICATE;
  tBTM_STATUS status = tBTM_STATUS::BTM_UNDEFINED;

  status = btm_sec_service_access_request(bd_addr, false, sec_req, NULL, NULL);

  // We're testing the temp bonding security behavior here, so all we care about
  // is that it doesn't fail on security.
  ASSERT_NE(status, tBTM_STATUS::BTM_FAILED_ON_SECURITY);
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_temp_bond_auth_upgrade_needed) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
  p_device->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device->sec_rec.bond_type = BOND_TYPE_PERSISTENT;

  BtmSecurity::Get().security_mode_ = BTM_SEC_MODE_SERVICE;
  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;

  uint16_t sec_req = BTM_SEC_IN_AUTHENTICATE | BTM_SEC_IN_MIN_16_DIGIT_PIN;
  tBTM_STATUS status = tBTM_STATUS::BTM_UNDEFINED;

  // This should be marked in btm_sec_execute_procedure with "start_auth"
  // because BTM_SEC_IN_AUTHENTICATE is required but the security flags
  // do not contain BTM_SEC_AUTHENTICATED

  status = btm_sec_service_access_request(bd_addr, false, sec_req, NULL, NULL);

  // In this case we expect it to clear several security flags and return
  // BTM_CMD_STARTED.
  ASSERT_EQ(status, tBTM_STATUS::BTM_CMD_STARTED);
  ASSERT_FALSE(p_device->sec_rec.sec_flags & BTM_SEC_LINK_KEY_KNOWN);
}

TEST_F(StackBtmSecWithInitFreeTest, btm_sec_temp_bond_auth_encryption_required) {
  RawAddress bd_addr = RawAddress("A1:A2:A3:A4:A5:A6");
  const uint16_t classic_handle = 0x1234;
  const uint16_t ble_handle = 0x9876;

  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = classic_handle;
  p_device->ble_hci_handle = ble_handle;

  p_device->sec_rec.sec_flags |= BTM_SEC_AUTHENTICATED;
  p_device->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;
  p_device->sec_rec.bond_type = BOND_TYPE_PERSISTENT;

  BtmSecurity::Get().security_mode_ = BTM_SEC_MODE_SERVICE;
  BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;

  uint16_t sec_req = BTM_SEC_IN_AUTHENTICATE | BTM_SEC_OUT_ENCRYPT;
  tBTM_STATUS status = tBTM_STATUS::BTM_UNDEFINED;

  // In this case we need to encrypt the link, so we will mark the link
  // encrypted and return BTM_CMD_STARTED.
  status = btm_sec_service_access_request(bd_addr, true, sec_req, NULL, NULL);

  ASSERT_EQ(status, tBTM_STATUS::BTM_CMD_STARTED);
  ASSERT_EQ(p_device->sec_rec.classic_link, tSECURITY_STATE::ENCRYPTING);
}

// Test fixture for testing the security upgrade logic.
class StackBtmSecSecurityUpgradeTest : public StackBtmSecWithInitFreeTest {
protected:
  void SetUp() override {
    StackBtmSecWithInitFreeTest::SetUp();
    p_device_ = btm_sec_allocate_dev_rec(kRawAddress);
    ASSERT_NE(p_device_, nullptr);
    p_device_->hci_handle = 0x1;  // Needed for btm_sec_service_access_request
    p_device_->sec_rec.sec_flags |= BTM_SEC_NAME_KNOWN;  // Avoid RNR
    p_device_->sm4 = BTM_SM4_TRUE;                       // Enable SM4 path
    BtmSecurity::Get().pairing_state_ = BTM_PAIR_STATE_IDLE;  // Ensure not busy
  }

  void TearDown() override {
    wipe_secrets_and_remove(p_device_);
    StackBtmSecWithInitFreeTest::TearDown();
  }

  BtmDevice* p_device_;
};

// Verifies that no upgrade is triggered when the existing security is sufficient.
TEST_F(StackBtmSecSecurityUpgradeTest, PairedNoUpgradeNeeded) {
  // Setup: Paired with authenticated key, persistent bond, no special requirements.
  p_device_->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device_->sec_rec.link_key_type = BTM_LKEY_TYPE_AUTH_COMB;
  p_device_->sec_rec.bond_type = BOND_TYPE_PERSISTENT;
  uint16_t initial_sec_flags = p_device_->sec_rec.sec_flags;
  uint8_t initial_sm4 = p_device_->sm4;

  // Action: Request access for a service with no special security.
  btm_sec_service_access_request(kRawAddress, true /* outgoing */, BTM_SEC_NONE, NULL, NULL);

  // Assert: No upgrade is triggered.
  ASSERT_EQ(p_device_->sm4, initial_sm4);
  ASSERT_EQ(p_device_->sec_rec.sec_flags, initial_sec_flags);
}

// Verifies that an upgrade is triggered to create a persistent bond when
// accessing a secure service with a temporary bond.
TEST_F(StackBtmSecSecurityUpgradeTest, TemporaryBondingUpgrade) {
  // Setup: Paired with a temporary bond.
  p_device_->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device_->sec_rec.bond_type = BOND_TYPE_TEMPORARY;

  // Action: Request access for a service that requires authentication.
  btm_sec_service_access_request(kRawAddress, true /* outgoing */, BTM_SEC_OUT_AUTHENTICATE, NULL,
                                 NULL);

  // Assert: Upgrade is triggered, and link key status is cleared for re-pairing.
  ASSERT_TRUE(p_device_->sm4 & BTM_SM4_UPGRADE);
  ASSERT_FALSE(p_device_->sec_rec.sec_flags & BTM_SEC_LINK_KEY_KNOWN);
}

// Verifies that an upgrade is triggered when MITM is required, but the existing
// key is unauthenticated and IO capabilities support MITM.
TEST_F(StackBtmSecSecurityUpgradeTest, MitmUpgradePossible) {
  // Setup: Paired with an unauthenticated key, but MITM is possible.
  p_device_->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device_->sec_rec.link_key_type = BTM_LKEY_TYPE_UNAUTH_COMB;
  p_device_->sec_rec.bond_type = BOND_TYPE_PERSISTENT;
  p_device_->sec_rec.rmt_io_caps = BtIoCap::KEYBOARD_ONLY;

  // Action: Request access for a service that requires MITM.
  btm_sec_service_access_request(kRawAddress, true /* outgoing */, BTM_SEC_OUT_MITM, NULL, NULL);

  // Assert: Upgrade is triggered, and link key status is cleared for re-pairing.
  ASSERT_TRUE(p_device_->sm4 & BTM_SM4_UPGRADE);
  ASSERT_FALSE(p_device_->sec_rec.sec_flags & BTM_SEC_LINK_KEY_KNOWN);
}

// Verifies that no upgrade is triggered when MITM is required, but the IO
// capabilities do not support it.
TEST_F(StackBtmSecSecurityUpgradeTest, MitmUpgradeNotPossible) {
  // Setup: Paired with an unauthenticated key, but MITM is not possible.
  p_device_->sec_rec.sec_flags |= BTM_SEC_LINK_KEY_KNOWN;
  p_device_->sec_rec.link_key_type = BTM_LKEY_TYPE_UNAUTH_COMB;
  p_device_->sec_rec.bond_type = BOND_TYPE_PERSISTENT;
  p_device_->sec_rec.rmt_io_caps = BtIoCap::NO_INPUT_NO_OUTPUT;
  uint16_t initial_sec_flags = p_device_->sec_rec.sec_flags;
  uint8_t initial_sm4 = p_device_->sm4;

  // Action: Request access for a service that requires MITM.
  btm_sec_service_access_request(kRawAddress, true /* outgoing */, BTM_SEC_OUT_MITM, NULL, NULL);

  // Assert: No upgrade is triggered because IO caps don't support it.
  ASSERT_EQ(p_device_->sm4, initial_sm4);
  ASSERT_EQ(p_device_->sec_rec.sec_flags, initial_sec_flags);
}

// Must be global to resolve the symbol within the legacy stack
struct alarm_t {
  alarm_callback_t cb;
  void* data;
};

static tBTM_STATUS dummy_pin_callback(const RawAddress&, const DEV_CLASS&, const BD_NAME&, bool,
                                      PairingAlgorithm) {
  return tBTM_STATUS::BTM_SUCCESS;
}
static tBTM_STATUS dummy_link_key_callback(const RawAddress&, const BD_NAME&, const LinkKey&,
                                           uint8_t, bool) {
  return tBTM_STATUS::BTM_SUCCESS;
}
static void dummy_auth_complete_callback(const RawAddress&, const BD_NAME&, tHCI_REASON) {}
static void dummy_bond_cancel_cmpl_callback(tBTM_STATUS) {}
static tBTM_STATUS dummy_sp_callback(tBTM_SP_EVT, tBTM_SP_EVT_DATA*) {
  return tBTM_STATUS::BTM_SUCCESS;
}
static tBTM_STATUS dummy_le_callback(tBTM_LE_EVT, const RawAddress&, tBTM_LE_EVT_DATA*) {
  return tBTM_STATUS::BTM_SUCCESS;
}
static void dummy_le_key_callback(uint8_t, tBTM_BLE_LOCAL_KEYS*) {}
static tBTM_STATUS dummy_sirk_verification_callback(const RawAddress&) {
  return tBTM_STATUS::BTM_SUCCESS;
}

static BtmAppReg dummy_app_reg = {dummy_pin_callback,
                                  dummy_link_key_callback,
                                  dummy_auth_complete_callback,
                                  dummy_bond_cancel_cmpl_callback,
                                  dummy_sp_callback,
                                  dummy_le_callback,
                                  dummy_le_key_callback,
                                  dummy_sirk_verification_callback};

// Test fixture for testing the Link Key Request Timer logic.
class StackBtmSecLinkKeyRequestTest : public StackBtmSecWithInitFreeTest {
protected:
  void SetUp() override {
    StackBtmSecWithInitFreeTest::SetUp();
    btm_sec_register(dummy_app_reg);
  }

  void TearDown() override {
    // Clean up timer if it exists to avoid leaks if Free doesn't handle it
    BtmSecurity::Get().ResetLinkKeyRequestTimer();
    StackBtmSecWithInitFreeTest::TearDown();
  }
};

TEST_F(StackBtmSecLinkKeyRequestTest, LinkKeyRequest_TimerStarted) {
  set_com_android_bluetooth_flags_link_key_request_timer(true);

  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");

  // Allocate device record
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  ASSERT_NE(nullptr, p_device);

  // Prepare conditions
  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;

  // Act
  btm_sec_link_key_request(bd_addr);

  // Assert
  ASSERT_NE(nullptr, BtmSecurity::Get().lk_req_timer_);
  ASSERT_NE(nullptr, BtmSecurity::Get().lk_req_timer_->cb);

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecLinkKeyRequestTest, LinkKeyRequest_Timeout) {
  set_com_android_bluetooth_flags_link_key_request_timer(true);

  // Allocate device record
  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);

  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;

  btm_sec_link_key_request(bd_addr);
  ASSERT_NE(nullptr, BtmSecurity::Get().lk_req_timer_);

  // Trigger callback
  alarm_callback_t cb = BtmSecurity::Get().lk_req_timer_->cb;
  void* data = BtmSecurity::Get().lk_req_timer_->data;
  cb(data);

  // Verify timer is reset
  ASSERT_EQ(nullptr, BtmSecurity::Get().lk_req_timer_);

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecLinkKeyRequestTest, LinkKeyRequest_ReplyCancelsTimer) {
  set_com_android_bluetooth_flags_link_key_request_timer(true);

  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);

  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;

  btm_sec_link_key_request(bd_addr);
  ASSERT_NE(nullptr, BtmSecurity::Get().lk_req_timer_);

  // Act: Reply with link key (via notification)
  LinkKey link_key;
  // Key type for CTKD: BTM_LTK_DERIVED_LKEY_OFFSET + BTM_LKEY_TYPE_COMBINATION ...
  uint8_t key_type = BTM_LTK_DERIVED_LKEY_OFFSET + BTM_LKEY_TYPE_AUTH_COMB_P_256;

  btm_sec_link_key_notification(bd_addr, link_key, key_type);

  // Assert
  ASSERT_EQ(nullptr, BtmSecurity::Get().lk_req_timer_);

  wipe_secrets_and_remove(p_device);
}

TEST_F(StackBtmSecLinkKeyRequestTest, LinkKeyRequest_DisconnectCancelsTimer) {
  set_com_android_bluetooth_flags_link_key_request_timer(true);

  RawAddress bd_addr = RawAddress("11:22:33:44:55:66");
  BtmDevice* p_device = btm_sec_allocate_dev_rec(bd_addr);
  p_device->hci_handle = 0x1234;

  BtmSecurity::Get().link_spec_.addrt.bda = bd_addr;
  BtmSecurity::Get().link_spec_.transport = BT_TRANSPORT_LE;

  btm_sec_link_key_request(bd_addr);
  ASSERT_NE(nullptr, BtmSecurity::Get().lk_req_timer_);

  // Act
  btm_sec_disconnected(0x1234, HCI_ERR_CONN_CAUSE_LOCAL_HOST, "test");

  // Assert
  ASSERT_EQ(nullptr, BtmSecurity::Get().lk_req_timer_);

  wipe_secrets_and_remove(p_device);
}
