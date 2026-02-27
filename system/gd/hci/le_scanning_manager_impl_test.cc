/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "hci/le_scanning_manager_impl.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <future>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <queue>
#include <vector>

#include "hci/acl_manager/acl_manager_le.h"
#include "hci/address.h"
#include "hci/controller_mock.h"
#include "hci/hci_layer.h"
#include "hci/hci_layer_fake.h"
#include "hci/hci_packets.h"
#include "os/system_properties.h"
#include "os/thread.h"
#include "packet/raw_builder.h"
#include "storage/device.h"
#include "storage/storage_module.h"

using ::testing::_;
using ::testing::Eq;

using namespace bluetooth;
using namespace std::chrono_literals;

using packet::kLittleEndian;
using packet::PacketView;
using packet::RawBuilder;

namespace {

// Event type fields.
// TODO(b/315496838): Use a common enum for event type bits.
static constexpr uint16_t kConnectable = 0x1;
static constexpr uint16_t kScannable = 0x2;
static constexpr uint16_t kScanResponse = 0x8;
static constexpr uint16_t kLegacy = 0x10;

// Test variables for scan multiplexing tests.
static constexpr bluetooth::hci::LeScanType TEST_JAVA_SCAN_TYPE =
        bluetooth::hci::LeScanType::ACTIVE;
static constexpr bluetooth::hci::ScannerId TEST_JAVA_SCANNER_ID = 1;
static constexpr uint16_t TEST_JAVA_SCAN_INTERVAL_1M = 200;
static constexpr uint16_t TEST_JAVA_SCAN_WINDOW_1M = 250;
static constexpr uint16_t TEST_JAVA_SCAN_INTERVAL_CODED = 300;
static constexpr uint16_t TEST_JAVA_SCAN_WINDOW_CODED = 350;
static constexpr uint8_t TEST_JAVA_SCAN_PHY = 5;
static constexpr uint8_t TEST_DISCOVERY_DURATION = 10;

hci::AdvertisingPacketContentFilterCommand make_filter(const hci::ApcfFilterType& filter_type) {
  hci::AdvertisingPacketContentFilterCommand filter{};
  filter.filter_type = filter_type;

  switch (filter_type) {
    case hci::ApcfFilterType::AD_TYPE:
    case hci::ApcfFilterType::SERVICE_DATA:
      filter.ad_type = 0x09;
      filter.data = {0x12, 0x34, 0x56, 0x78};
      filter.data_mask = {0xff, 0xff, 0xff, 0xff};
      break;
    case hci::ApcfFilterType::BROADCASTER_ADDRESS:
      filter.address = hci::Address::kEmpty;
      filter.application_address_type = hci::ApcfApplicationAddressType::RANDOM;
      break;
    case hci::ApcfFilterType::SERVICE_UUID:
      filter.uuid = Uuid::From32Bit(0x12345678);
      filter.uuid_mask = Uuid::From32Bit(0xffffffff);
      break;
    case hci::ApcfFilterType::LOCAL_NAME:
      filter.name = {0x01, 0x02, 0x03};
      break;
    case hci::ApcfFilterType::MANUFACTURER_DATA:
      filter.company = 0x12;
      filter.company_mask = 0xff;
      filter.data = {0x12, 0x34, 0x56, 0x78};
      filter.data_mask = {0xff, 0xff, 0xff, 0xff};
      break;
    case hci::ApcfFilterType::TRANSPORT_DISCOVERY_DATA:
      filter.org_id = 0x02;
      filter.tds_flags = 0x01;
      filter.tds_flags_mask = 0xFF;
      filter.meta_data_type = hci::ApcfMetaDataType::WIFI_NAN_HASH;
      filter.meta_data = {0x4B, 0x14, 0x96, 0x96, 0x96, 0x5E, 0xA6, 0x33};
      break;
    default:
      break;
  }
  return filter;
}

hci::LeAdvertisingResponse make_advertising_report() {
  hci::LeAdvertisingResponse report{};
  report.event_type_ = hci::AdvertisingEventType::ADV_DIRECT_IND;
  report.address_type_ = hci::AddressType::PUBLIC_DEVICE_ADDRESS;
  report.address_ = hci::Address::FromString("12:34:56:78:9a:bc").value();
  std::vector<hci::LengthAndData> adv_data{};
  hci::LengthAndData data_item{};
  data_item.data_.push_back(static_cast<uint8_t>(hci::GapDataType::FLAGS));
  data_item.data_.push_back(0x34);
  adv_data.push_back(data_item);
  data_item.data_.push_back(static_cast<uint8_t>(hci::GapDataType::COMPLETE_LOCAL_NAME));
  for (auto octet : {'r', 'a', 'n', 'd', 'o', 'm', ' ', 'd', 'e', 'v', 'i', 'c', 'e'}) {
    data_item.data_.push_back(octet);
  }
  adv_data.push_back(data_item);
  report.advertising_data_ = adv_data;
  return report;
}

}  // namespace

namespace bluetooth {
namespace hci {
namespace {

class TestController : public testing::MockController {
public:
  bool IsSupported(OpCode op_code) const override { return supported_opcodes_.count(op_code) == 1; }

  void AddSupported(OpCode op_code) { supported_opcodes_.insert(op_code); }

  bool SupportsBleExtendedAdvertising() const override { return support_ble_extended_advertising_; }

  void SetBleExtendedAdvertisingSupport(bool support) {
    support_ble_extended_advertising_ = support;
  }

  bool SupportsBlePeriodicAdvertisingSyncTransferSender() const override {
    return support_ble_periodic_advertising_sync_transfer_;
  }

  void SetBlePeriodicAdvertisingSyncTransferSenderSupport(bool support) {
    support_ble_periodic_advertising_sync_transfer_ = support;
  }

private:
  std::set<OpCode> supported_opcodes_{};
  bool support_ble_extended_advertising_ = false;
  bool support_ble_periodic_advertising_sync_transfer_ = false;
};

class TestLeAddressManager : public LeAddressManager {
public:
  TestLeAddressManager(
          base::RepeatingCallback<void(std::unique_ptr<CommandBuilder>)> enqueue_command,
          os::Handler* handler, Address public_address, uint8_t accept_list_size,
          uint8_t resolving_list_size, Controller* controller)
      : LeAddressManager(std::move(enqueue_command), handler, public_address, accept_list_size,
                         resolving_list_size, controller) {}

  AddressPolicy Register(LeAddressManagerCallback* callback) override {
    client_ = callback;
    test_client_state_ = RESUMED;
    return AddressPolicy::USE_STATIC_ADDRESS;
  }

  void Unregister(LeAddressManagerCallback* /* callback */) override {
    if (!ignore_unregister_for_testing) {
      client_ = nullptr;
    }
    test_client_state_ = UNREGISTERED;
  }

  void AckPause(LeAddressManagerCallback* /* callback */) override { test_client_state_ = PAUSED; }

  void AckResume(LeAddressManagerCallback* /* callback */) override {
    test_client_state_ = RESUMED;
  }

  LeAddressManagerCallback* client_;
  bool ignore_unregister_for_testing = false;
  enum TestClientState {
    UNREGISTERED,
    PAUSED,
    RESUMED,
  };
  TestClientState test_client_state_ = UNREGISTERED;
};

class MockCallbacks : public bluetooth::hci::ScanningCallback {
public:
  MOCK_METHOD(void, OnScannerRegistered,
              (const bluetooth::Uuid app_uuid, ScannerId scanner_id, ScanningStatus status),
              (override));
  MOCK_METHOD(void, OnSetScannerParameterComplete, (ScannerId scanner_id, ScanningStatus status),
              (override));
  MOCK_METHOD(void, OnScanResult,
              (uint16_t event_type, uint8_t address_type, Address address, uint8_t primary_phy,
               uint8_t secondary_phy, uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
               uint16_t periodic_advertising_interval, std::vector<uint8_t> advertising_data),
              (override));
  MOCK_METHOD(void, OnTrackAdvFoundLost,
              (bluetooth::hci::AdvertisingFilterOnFoundOnLostInfo on_found_on_lost_info),
              (override));
  MOCK_METHOD(void, OnBatchScanReports,
              (int client_if, int status, int report_format, int num_records,
               std::vector<uint8_t> data),
              (override));
  MOCK_METHOD(void, OnBatchScanThresholdCrossed, (int client_if), (override));
  MOCK_METHOD(void, OnTimeout, (), (override));
  MOCK_METHOD(void, OnFilterEnable, (Enable enable, uint8_t status), (override));
  MOCK_METHOD(void, OnFilterParamSetup,
              (uint8_t available_spaces, ApcfAction action, uint8_t status), (override));
  MOCK_METHOD(void, OnFilterConfigCallback,
              (ApcfFilterType filter_type, uint8_t available_spaces, ApcfAction action,
               uint8_t status),
              (override));
  MOCK_METHOD(void, OnPeriodicSyncStarted,
              (int, uint8_t, uint16_t, uint8_t, AddressWithType, uint8_t, uint16_t));
  MOCK_METHOD(void, OnPeriodicSyncReport,
              (uint16_t, int8_t, int8_t, uint8_t, std::vector<uint8_t>));
  MOCK_METHOD(void, OnPeriodicSyncLost, (uint16_t));
  MOCK_METHOD(void, OnPeriodicSyncTransferred, (int, uint8_t, Address));
  MOCK_METHOD(void, OnBigInfoReport, (uint16_t, bool));
} mock_callbacks_;

class LeScanningManagerTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);

    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_controller_ = std::make_unique<TestController>();
    test_controller_->SetBlePeriodicAdvertisingSyncTransferSenderSupport(true);
    Address address({0x01, 0x02, 0x03, 0x04, 0x05, 0x06});
    test_le_address_manager_ = std::make_unique<TestLeAddressManager>(
            base::BindRepeating([](std::unique_ptr<CommandBuilder> /* command_packet */) {}),
            client_handler_, address, 0x3F, 0x3F, test_controller_.get());

    ASSERT_TRUE(client_handler_ != nullptr);
  }

  void TearDown() override {
    sync_client_handler();
    if (le_scanning_manager != nullptr) {
      client_handler_->Synchronize(std::chrono::milliseconds(20));
    }
    test_le_address_manager_.reset();
    test_controller_.reset();
    test_hci_layer_.reset();

    client_handler_->Clear();
    client_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

    delete client_handler_;
    delete thread_;
  }

  void start_le_scanning_manager() {
    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    le_scanning_manager = new LeScanningManagerImpl(
            client_handler_, test_hci_layer_.get(), test_controller_.get(),
            test_le_address_manager_.get(), nullptr /*storage_module*/);
    le_scanning_manager->RegisterScanningCallback(&mock_callbacks_);
    sync_client_handler();
  }

  void sync_client_handler() {
    log::assert_that(thread_->GetReactor()->WaitForIdle(std::chrono::seconds(2)),
                     "assert failed: thread_->GetReactor()->WaitForIdle(std::chrono::seconds(2))");
  }

  os::Thread* thread_ = nullptr;
  os::Handler* client_handler_ = nullptr;
  std::unique_ptr<HciLayerFake> test_hci_layer_ = nullptr;
  std::unique_ptr<TestController> test_controller_ = nullptr;
  std::unique_ptr<TestLeAddressManager> test_le_address_manager_ = nullptr;
  LeScanningManagerImpl* le_scanning_manager = nullptr;

  MockCallbacks mock_callbacks_;
};

class LeScanningManagerAndroidHciTest : public LeScanningManagerTest {
protected:
  void SetUp() override {
    LeScanningManagerTest::SetUp();
    test_controller_->AddSupported(OpCode::LE_EXTENDED_SCAN_PARAMS);
    test_controller_->AddSupported(OpCode::LE_ADV_FILTER);
    test_controller_->AddSupported(
            OpCode::LE_BATCH_SCAN);  // This line is not part of the selection, but it's good
                                     // practice to ensure all relevant setup is correct.
    test_controller_->SetBlePeriodicAdvertisingSyncTransferSenderSupport(true);
    Controller::VendorCapabilities vendor_caps = {};
    vendor_caps.total_num_of_advt_tracked_ = 1;
    ON_CALL(*test_controller_, GetVendorCapabilities())
            .WillByDefault(::testing::Return(vendor_caps));
    start_le_scanning_manager();

    ASSERT_EQ(OpCode::LE_ADV_FILTER, test_hci_layer_->GetCommand().GetOpCode());
    test_hci_layer_->IncomingEvent(LeAdvFilterReadExtendedFeaturesCompleteBuilder::Create(
            1, ErrorCode::SUCCESS, 0x01, 0x01));
  }

  void TearDown() override { LeScanningManagerTest::TearDown(); }
};

class LeScanningManagerExtendedTest : public LeScanningManagerTest {
protected:
  void SetUp() override {
    LeScanningManagerTest::SetUp();
    com_android_bluetooth_flags_reset_flags();
    test_controller_->AddSupported(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS);
    test_controller_->AddSupported(OpCode::LE_SET_EXTENDED_SCAN_ENABLE);
    test_controller_->SetBleExtendedAdvertisingSupport(true);
    start_le_scanning_manager();
  }
};

TEST_F(LeScanningManagerTest, startup_teardown) {}

TEST_F(LeScanningManagerTest, start_scan_test) {
  start_le_scanning_manager();

  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  LeAdvertisingResponse report = make_advertising_report();
  EXPECT_CALL(mock_callbacks_, OnScanResult);

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));
}

TEST_F(LeScanningManagerTest, legacy_adv_scan_ind_report_with_scan_response) {
  start_le_scanning_manager();

  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  LeAdvertisingResponse report = make_advertising_report();
  // Scannable & not connectable!
  report.event_type_ = AdvertisingEventType::ADV_SCAN_IND;

  uint16_t extended_event_type = kLegacy | kScannable;
  EXPECT_CALL(mock_callbacks_, OnScanResult(extended_event_type, _, _, _, _, _, _, _, _, _));

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));

  LeAdvertisingResponse scan_response = make_advertising_report();
  scan_response.event_type_ = AdvertisingEventType::SCAN_RESPONSE;

  // The 'connectable' bit should NOT be set.
  extended_event_type = kLegacy | kScannable | kScanResponse;
  EXPECT_CALL(mock_callbacks_, OnScanResult(extended_event_type, _, _, _, _, _, _, _, _, _));

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({scan_response}));
}

TEST_F(LeScanningManagerTest, legacy_adv_ind_report_with_scan_response) {
  start_le_scanning_manager();

  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  LeAdvertisingResponse report = make_advertising_report();
  // Scannable & connectable!
  report.event_type_ = AdvertisingEventType::ADV_IND;
  uint16_t extended_event_type = kLegacy | kScannable | kConnectable;
  EXPECT_CALL(mock_callbacks_, OnScanResult(extended_event_type, _, _, _, _, _, _, _, _, _));
  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));

  LeAdvertisingResponse scan_response = make_advertising_report();
  scan_response.event_type_ = AdvertisingEventType::SCAN_RESPONSE;

  extended_event_type = kLegacy | kScannable | kConnectable | kScanResponse;
  EXPECT_CALL(mock_callbacks_, OnScanResult(extended_event_type, _, _, _, _, _, _, _, _, _));

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({scan_response}));
}

TEST_F(LeScanningManagerTest, is_ad_type_filter_supported_false_test) {
  start_le_scanning_manager();
  ASSERT_FALSE(le_scanning_manager->IsAdTypeFilterSupported());
}

TEST_F(LeScanningManagerTest, scan_filter_add_ad_type_not_supported_test) {
  start_le_scanning_manager();

  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(hci::ApcfFilterType::AD_TYPE));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
}

TEST_F(LeScanningManagerExtendedTest, is_coded_phy_supported_test) {
  int scan_phy = 4;  // BluetoothDevice.PHY_LE_CODED_MASK

  start_le_scanning_manager();
  le_scanning_manager->SetScanParameters(LeScanType::ACTIVE, 1, 0x0004, 4800, 1, 0x0004, 4800,
                                         scan_phy);
  le_scanning_manager->Scan(true);

  auto command_view = LeSetExtendedScanParametersView::Create(
          LeScanningCommandView::Create(test_hci_layer_->GetCommand()));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetScanningPhys(), scan_phy);
  ASSERT_EQ(command_view.GetParameters().size(), static_cast<size_t>(1));
}

TEST_F(LeScanningManagerExtendedTest, is_multiple_phy_supported_test) {
  int scan_phy = 5;  // BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_CODED_MASK

  start_le_scanning_manager();
  le_scanning_manager->SetScanParameters(LeScanType::ACTIVE, 1, 0x0004, 4800, 1, 0x0004, 4800,
                                         scan_phy);
  le_scanning_manager->Scan(true);

  auto command_view = LeSetExtendedScanParametersView::Create(
          LeScanningCommandView::Create(test_hci_layer_->GetCommand()));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetScanningPhys(), scan_phy);
  ASSERT_EQ(command_view.GetParameters().size(), static_cast<size_t>(2));
}

// Test for 'update_start_scan' when we have Java scan start request while no scan is ongoing
TEST_F(LeScanningManagerExtendedTest, scan_multiplexing_start_java_scan) {
  set_com_android_bluetooth_flags_migrate_btm_scan_to_gd(true);

  // Set Java scan parameters and enable Java scan
  le_scanning_manager->SetScanParameters(TEST_JAVA_SCAN_TYPE, TEST_JAVA_SCANNER_ID,
                                         TEST_JAVA_SCAN_INTERVAL_1M, TEST_JAVA_SCAN_WINDOW_1M,
                                         TEST_JAVA_SCANNER_ID, TEST_JAVA_SCAN_INTERVAL_CODED,
                                         TEST_JAVA_SCAN_WINDOW_CODED, TEST_JAVA_SCAN_PHY);
  le_scanning_manager->Scan(true);

  // Check if scan parameters were set successfully to requested Java scan parameters
  auto command_view = LeSetExtendedScanParametersView::Create(
          LeScanningCommandView::Create(test_hci_layer_->GetCommand()));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetScanningPhys(), TEST_JAVA_SCAN_PHY);
  auto scan_parameters = command_view.GetParameters();
  ASSERT_EQ(scan_parameters.size(), static_cast<size_t>(2));

  ASSERT_EQ(scan_parameters[0].le_scan_type_, TEST_JAVA_SCAN_TYPE);
  ASSERT_EQ(scan_parameters[0].le_scan_interval_, TEST_JAVA_SCAN_INTERVAL_1M);
  ASSERT_EQ(scan_parameters[0].le_scan_window_, TEST_JAVA_SCAN_WINDOW_1M);

  ASSERT_EQ(scan_parameters[1].le_scan_type_, TEST_JAVA_SCAN_TYPE);
  ASSERT_EQ(scan_parameters[1].le_scan_interval_, TEST_JAVA_SCAN_INTERVAL_CODED);
  ASSERT_EQ(scan_parameters[1].le_scan_window_, TEST_JAVA_SCAN_WINDOW_CODED);
}

// Test for 'update_start_scan' when we have discovery start request while no scan is ongoing
TEST_F(LeScanningManagerExtendedTest, scan_multiplexing_start_discovery) {
  set_com_android_bluetooth_flags_migrate_btm_scan_to_gd(true);

  // Start discovery
  le_scanning_manager->StartDiscovery(TEST_DISCOVERY_DURATION);

  // Check if scan parameters were set successfully to 1m low latency
  auto command_view = LeSetExtendedScanParametersView::Create(
          LeScanningCommandView::Create(test_hci_layer_->GetCommand()));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetScanningPhys(), LeScanningManagerImpl::k1mPhyMask);
  auto scan_parameters = command_view.GetParameters();
  ASSERT_EQ(scan_parameters.size(), static_cast<size_t>(1));

  ASSERT_EQ(scan_parameters[0].le_scan_type_, LeScanType::ACTIVE);
  ASSERT_EQ(scan_parameters[0].le_scan_interval_, LeScanningManagerImpl::kLeScanIntervalLowLatency);
  ASSERT_EQ(scan_parameters[0].le_scan_window_, LeScanningManagerImpl::kLeScanWindowLowLatency);
}

// Test for 'update_stop_scan' when we have Java scan stop request while only Java scan is ongoing
TEST_F(LeScanningManagerExtendedTest, scan_multiplexing_stop_java_scan) {
  set_com_android_bluetooth_flags_migrate_btm_scan_to_gd(true);
  // Enable Java scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Disable Java scan, should successfully dispatch a command to the controller
  le_scanning_manager->Scan(false);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();
}

// Test for 'update_stop_scan' when we hava discovery stop request while only discovery is ongoing
TEST_F(LeScanningManagerExtendedTest, scan_multiplexing_stop_discovery) {
  set_com_android_bluetooth_flags_migrate_btm_scan_to_gd(true);
  // Start discovery
  le_scanning_manager->StartDiscovery(TEST_DISCOVERY_DURATION);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Stop discovery, should successfully dispatch a command to the controller
  le_scanning_manager->StopDiscovery();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, startup_teardown) {}

TEST_F(LeScanningManagerAndroidHciTest, start_scan_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_EXTENDED_SCAN_PARAMS, test_hci_layer_->GetCommand().GetOpCode());

  LeAdvertisingResponse report = make_advertising_report();

  EXPECT_CALL(mock_callbacks_, OnScanResult);

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));
}

TEST_F(LeScanningManagerAndroidHciTest, is_ad_type_filter_supported_true_test) {
  sync_client_handler();
  client_handler_->Post(base::BindOnce(
          [](LeScanningManagerImpl* le_scanning_manager) {
            ASSERT_TRUE(le_scanning_manager->IsAdTypeFilterSupported());
          },
          le_scanning_manager));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_enable_test) {
  le_scanning_manager->ScanFilterEnable(true);
  sync_client_handler();

  EXPECT_CALL(mock_callbacks_, OnFilterEnable);
  test_hci_layer_->IncomingEvent(LeAdvFilterEnableCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, Enable::ENABLED));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_parameter_test) {
  AdvertisingFilterParameter advertising_filter_parameter{};
  advertising_filter_parameter.delivery_mode = DeliveryMode::IMMEDIATE;
  le_scanning_manager->ScanFilterParameterSetup(ApcfAction::ADD, 0x01,
                                                advertising_filter_parameter);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterSetFilteringParametersView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::SET_FILTERING_PARAMETERS);

  EXPECT_CALL(mock_callbacks_, OnFilterParamSetup);
  test_hci_layer_->IncomingEvent(LeAdvFilterSetFilteringParametersCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_broadcaster_address_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(ApcfFilterType::BROADCASTER_ADDRESS));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterBroadcasterAddressView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::BROADCASTER_ADDRESS);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterBroadcasterAddressCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_service_uuid_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(ApcfFilterType::SERVICE_UUID));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterServiceUuidView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::SERVICE_UUID);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterServiceUuidCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_local_name_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(ApcfFilterType::LOCAL_NAME));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterLocalNameView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::LOCAL_NAME);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterLocalNameCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_manufacturer_data_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(ApcfFilterType::MANUFACTURER_DATA));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterManufacturerDataView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::MANUFACTURER_DATA);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterManufacturerDataCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_service_data_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(hci::ApcfFilterType::SERVICE_DATA));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterServiceDataView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));
  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::SERVICE_DATA);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterServiceDataCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_transport_discovery_data_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  filters.push_back(make_filter(hci::ApcfFilterType::TRANSPORT_DISCOVERY_DATA));
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  auto commandView = test_hci_layer_->GetCommand();
  ASSERT_EQ(OpCode::LE_ADV_FILTER, commandView.GetOpCode());
  auto filter_command_view = LeAdvFilterTransportDiscoveryDataView::Create(
          LeAdvFilterView::Create(LeScanningCommandView::Create(commandView)));

  ASSERT_TRUE(filter_command_view.IsValid());
  ASSERT_EQ(filter_command_view.GetApcfOpcode(), ApcfOpcode::TRANSPORT_DISCOVERY_DATA);

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterTransportDiscoveryDataCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, scan_filter_add_ad_type_test) {
  sync_client_handler();
  client_handler_->Post(base::BindOnce(
          [](LeScanningManagerImpl* le_scanning_manager) {
            ASSERT_TRUE(le_scanning_manager->IsAdTypeFilterSupported());
          },
          le_scanning_manager));

  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  hci::AdvertisingPacketContentFilterCommand filter = make_filter(hci::ApcfFilterType::AD_TYPE);
  filters.push_back(filter);
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  sync_client_handler();

  EXPECT_CALL(mock_callbacks_, OnFilterConfigCallback);
  test_hci_layer_->IncomingEvent(LeAdvFilterADTypeCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, ApcfAction::ADD, 0x0a));
}

TEST_F(LeScanningManagerAndroidHciTest, read_batch_scan_result) {
  le_scanning_manager->BatchScanConfigStorage(100, 0, 95, 0x00);
  sync_client_handler();
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeBatchScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeBatchScanSetStorageParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Enable batch scan

  le_scanning_manager->BatchScanEnable(BatchScanMode::FULL, 2400, 2400,
                                       BatchScanDiscardRule::OLDEST);
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeBatchScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Read batch scan data

  le_scanning_manager->BatchScanReadReport(0x01, BatchScanMode::FULL);
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());

  // We will send read command while num_of_record != 0
  std::vector<uint8_t> raw_data = {0x5c, 0x1f, 0xa2, 0xc3, 0x63, 0x5d, 0x01, 0xf5, 0xb3,
                                   0x5e, 0x00, 0x0c, 0x02, 0x01, 0x02, 0x05, 0x09, 0x6d,
                                   0x76, 0x38, 0x76, 0x02, 0x0a, 0xf5, 0x00};

  test_hci_layer_->IncomingEvent(LeBatchScanReadResultParametersCompleteRawBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, BatchScanDataRead::FULL_MODE_DATA, 1, raw_data));
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());

  // OnBatchScanReports will be trigger when num_of_record == 0
  EXPECT_CALL(mock_callbacks_, OnBatchScanReports);
  test_hci_layer_->IncomingEvent(LeBatchScanReadResultParametersCompleteRawBuilder::Create(
          uint8_t{1}, ErrorCode::SUCCESS, BatchScanDataRead::FULL_MODE_DATA, 0, {}));
}

TEST_F(LeScanningManagerAndroidHciTest, start_sync_test) {
  Address address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  const uint16_t handle = 0x0001;
  const uint16_t service_data = 0x0000;
  const uint16_t sync_handle = 0x0002;
  const int pa_source = 3;

  le_scanning_manager->TransferSync(address, handle, service_data, sync_handle, pa_source);
  sync_client_handler();

  ASSERT_EQ(OpCode::LE_PERIODIC_ADVERTISING_SYNC_TRANSFER,
            test_hci_layer_->GetCommand().GetOpCode());
}

TEST_F(LeScanningManagerAndroidHciTest, start_sync_invalid_handle_test) {
  Address address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  const uint16_t handle = 0xFFFF;
  const uint16_t service_data = 0x0000;
  const uint16_t sync_handle = 0x0002;
  const int pa_source = 3;

  EXPECT_CALL(mock_callbacks_,
              OnPeriodicSyncTransferred(pa_source, static_cast<int>(ErrorCode::UNKNOWN_CONNECTION),
                                        address));
  le_scanning_manager->TransferSync(address, handle, service_data, sync_handle, pa_source);
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, set_info_test) {
  Address address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  const uint16_t handle = 0x0001;
  const uint16_t service_data = 0x0000;
  const uint16_t sync_handle = 0x0002;
  const int pa_source = 3;

  le_scanning_manager->TransferSetInfo(address, handle, service_data, sync_handle, pa_source);
  sync_client_handler();

  ASSERT_EQ(OpCode::LE_PERIODIC_ADVERTISING_SET_INFO_TRANSFER,
            test_hci_layer_->GetCommand().GetOpCode());
}

TEST_F(LeScanningManagerAndroidHciTest, set_info_invalid_handle_test) {
  Address address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  const uint16_t handle = 0xFFFF;
  const uint16_t service_data = 0x0000;
  const uint16_t sync_handle = 0x0002;
  const int pa_source = 3;

  EXPECT_CALL(mock_callbacks_,
              OnPeriodicSyncTransferred(pa_source, static_cast<int>(ErrorCode::UNKNOWN_CONNECTION),
                                        address));
  le_scanning_manager->TransferSetInfo(address, handle, service_data, sync_handle, pa_source);
  sync_client_handler();
}

TEST_F(LeScanningManagerExtendedTest, startup_teardown) {}

TEST_F(LeScanningManagerExtendedTest, start_scan_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  LeExtendedAdvertisingResponse report{};
  report.connectable_ = 1;
  report.scannable_ = 0;
  report.address_type_ = DirectAdvertisingAddressType::PUBLIC_DEVICE_ADDRESS;
  report.address_ = Address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  std::vector<LengthAndData> adv_data{};
  LengthAndData data_item{};
  data_item.data_.push_back(static_cast<uint8_t>(GapDataType::FLAGS));
  data_item.data_.push_back(0x34);
  adv_data.push_back(data_item);
  data_item.data_.push_back(static_cast<uint8_t>(GapDataType::COMPLETE_LOCAL_NAME));
  for (auto octet : {'r', 'a', 'n', 'd', 'o', 'm', ' ', 'd', 'e', 'v', 'i', 'c', 'e'}) {
    data_item.data_.push_back(octet);
  }
  adv_data.push_back(data_item);

  report.advertising_data_ = adv_data;

  EXPECT_CALL(mock_callbacks_, OnScanResult);

  test_hci_layer_->IncomingLeMetaEvent(LeExtendedAdvertisingReportBuilder::Create({report}));
}

TEST_F(LeScanningManagerExtendedTest, start_scan_on_resume_conflict_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Pause scan
  test_le_address_manager_->client_->OnPause();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Disable scan
  le_scanning_manager->Scan(false);
  test_hci_layer_->AssertNoQueuedCommand();

  // Enable Scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Ensure there is no double enable commands on resume
  test_le_address_manager_->client_->OnResume();
  sync_client_handler();
  test_hci_layer_->AssertNoQueuedCommand();
}

TEST_F(LeScanningManagerExtendedTest, on_pause_on_resume_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Pause scan
  test_le_address_manager_->client_->OnPause();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Ensure scan is resumed (enabled)
  test_le_address_manager_->client_->OnResume();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
}

TEST_F(LeScanningManagerExtendedTest, on_pause_already_paused_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Pause scan (first time)
  test_le_address_manager_->client_->OnPause();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Pause scan (second time) - should be ignored and scan_on_resume should remain true
  test_le_address_manager_->client_->OnPause();
  test_hci_layer_->AssertNoQueuedCommand();

  // Ensure scan is resumed (enabled)
  test_le_address_manager_->client_->OnResume();
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
}

TEST_F(LeScanningManagerExtendedTest, ignore_on_pause_on_resume_after_unregistered) {
  test_le_address_manager_->ignore_unregister_for_testing = true;

  // Register LeAddressManager
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Unregister LeAddressManager
  le_scanning_manager->Scan(false);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Unregistered client should ignore OnPause/OnResume
  ASSERT_NE(test_le_address_manager_->client_, nullptr);
  ASSERT_EQ(test_le_address_manager_->test_client_state_,
            TestLeAddressManager::TestClientState::UNREGISTERED);
  test_le_address_manager_->client_->OnPause();
  ASSERT_EQ(test_le_address_manager_->test_client_state_,
            TestLeAddressManager::TestClientState::UNREGISTERED);
  test_le_address_manager_->client_->OnResume();
  ASSERT_EQ(test_le_address_manager_->test_client_state_,
            TestLeAddressManager::TestClientState::UNREGISTERED);
}

TEST_F(LeScanningManagerExtendedTest, drop_insignificant_bytes_test) {
  // Enable scan
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_EXTENDED_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetExtendedScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  // Prepare advertisement report
  LeExtendedAdvertisingResponse advertisement_report{};
  advertisement_report.connectable_ = 1;
  advertisement_report.scannable_ = 1;
  advertisement_report.address_type_ = DirectAdvertisingAddressType::PUBLIC_DEVICE_ADDRESS;
  advertisement_report.address_ = Address::FromString("12:34:56:78:9a:bc").value();
  std::vector<LengthAndData> adv_data{};
  LengthAndData flags_data{};
  flags_data.data_.push_back(static_cast<uint8_t>(GapDataType::FLAGS));
  flags_data.data_.push_back(0x34);
  adv_data.push_back(flags_data);
  LengthAndData name_data{};
  name_data.data_.push_back(static_cast<uint8_t>(GapDataType::COMPLETE_LOCAL_NAME));
  for (auto octet : "random device") {
    name_data.data_.push_back(octet);
  }
  adv_data.push_back(name_data);
  for (int i = 0; i != 5; ++i) {
    adv_data.push_back({});  // pad with a few insignificant zeros
  }
  advertisement_report.advertising_data_ = adv_data;

  // Prepare scan response report
  auto scan_response_report = advertisement_report;
  scan_response_report.scan_response_ = true;
  LengthAndData extra_data{};
  extra_data.data_.push_back(static_cast<uint8_t>(GapDataType::MANUFACTURER_SPECIFIC_DATA));
  for (auto octet : "manufacturer specific") {
    extra_data.data_.push_back(octet);
  }
  adv_data = {extra_data};
  for (int i = 0; i != 5; ++i) {
    adv_data.push_back({});  // pad with a few insignificant zeros
  }
  scan_response_report.advertising_data_ = adv_data;

  {
    auto result_without_scan_response = std::vector<uint8_t>();
    packet::BitInserter it(result_without_scan_response);
    flags_data.Serialize(it);
    name_data.Serialize(it);
    EXPECT_CALL(mock_callbacks_,
                OnScanResult(_, _, _, _, _, _, _, _, _, result_without_scan_response));
  }

  // We expect the two reports to be concatenated, excluding the zero-padding
  auto result = std::vector<uint8_t>();
  packet::BitInserter it(result);
  flags_data.Serialize(it);
  name_data.Serialize(it);
  extra_data.Serialize(it);
  EXPECT_CALL(mock_callbacks_, OnScanResult(_, _, _, _, _, _, _, _, _, result));

  // Send both reports
  test_hci_layer_->IncomingLeMetaEvent(
          LeExtendedAdvertisingReportBuilder::Create({advertisement_report}));
  test_hci_layer_->IncomingLeMetaEvent(
          LeExtendedAdvertisingReportBuilder::Create({scan_response_report}));
}

TEST_F(LeScanningManagerTest, directed_advertising_report_test) {
  start_le_scanning_manager();

  // The directed advertising report handler only logs a warning and returns.
  // This test ensures the event is correctly handled without crashing.
  Address address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc});
  Address direct_address({0x12, 0x34, 0x56, 0x78, 0x9a, 0xbd});
  uint8_t rssi = -70;
  DirectAddressType address_type = DirectAddressType::RANDOM_DEVICE_ADDRESS;
  LeDirectedAdvertisingResponse response(DirectAdvertisingEventType::ADV_DIRECT_IND,
                                         DirectAdvertisingAddressType::PUBLIC_DEVICE_ADDRESS,
                                         address, address_type, direct_address, rssi);

  test_hci_layer_->IncomingLeMetaEvent(LeDirectedAdvertisingReportBuilder::Create({response}));
  sync_client_handler();
}
TEST_F(LeScanningManagerTest, scan_timeout_test) {
  start_le_scanning_manager();

  // When a scan timeout event is received, the OnTimeout callback should be called.
  EXPECT_CALL(mock_callbacks_, OnTimeout()).Times(1);
  test_hci_layer_->IncomingLeMetaEvent(LeScanTimeoutBuilder::Create());
  sync_client_handler();
}

// Moving failing tests to the new test fixture
TEST_F(LeScanningManagerTest, periodic_advertising_sync_established_test) {
  start_le_scanning_manager();

  ErrorCode status = ErrorCode::SUCCESS;
  uint16_t sync_handle = 0x0102;
  uint8_t advertising_sid = 0x05;
  SecondaryPhyType phy = SecondaryPhyType::LE_1M;
  uint16_t interval = 0x00A0;
  Address advertisier_address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  ClockAccuracy clock_accuracy = ClockAccuracy::PPM_100;
  int reg_id = 1;
  AddressWithType address_with_type =
          AddressWithType(advertisier_address, AddressType::PUBLIC_DEVICE_ADDRESS);

  // Start the sync process first
  uint16_t skip = 0;
  uint16_t timeout = 1000;
  le_scanning_manager->StartSync(advertising_sid, address_with_type, skip, timeout, reg_id);
  sync_client_handler();

  EXPECT_CALL(
          mock_callbacks_,
          OnPeriodicSyncStarted(reg_id, static_cast<uint8_t>(status), sync_handle, advertising_sid,
                                address_with_type, static_cast<uint8_t>(phy), interval))
          .Times(1);

  test_hci_layer_->IncomingLeMetaEvent(LePeriodicAdvertisingSyncEstablishedBuilder::Create(
          status, sync_handle, advertising_sid, AddressType::PUBLIC_DEVICE_ADDRESS,
          advertisier_address, phy, interval, clock_accuracy));
  sync_client_handler();
}

TEST_F(LeScanningManagerTest, periodic_advertising_sync_lost_test) {
  start_le_scanning_manager();
  uint16_t sync_handle = 0x0102;
  EXPECT_CALL(mock_callbacks_, OnPeriodicSyncLost(sync_handle)).Times(1);

  test_hci_layer_->IncomingLeMetaEvent(LePeriodicAdvertisingSyncLostBuilder::Create(sync_handle));
  sync_client_handler();
}

TEST_F(LeScanningManagerTest, periodic_advertising_sync_transfer_received_test) {
  start_le_scanning_manager();
  uint16_t conn_handle = 0x0001;
  ErrorCode status = ErrorCode::SUCCESS;
  uint16_t service_data = 0x1234;
  uint16_t sync_handle = 0x0102;
  uint8_t sid = 0x05;
  Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  AddressType address_type = AddressType::PUBLIC_DEVICE_ADDRESS;
  SecondaryPhyType phy = SecondaryPhyType::LE_1M;
  uint16_t interval = 0x0100;
  ClockAccuracy clock_accuracy = ClockAccuracy ::PPM_100;

  // The transfer event is dispatched internally, but this test verifies the event is processed.
  test_hci_layer_->IncomingLeMetaEvent(LePeriodicAdvertisingSyncTransferReceivedBuilder::Create(
          status, conn_handle, service_data, sync_handle, sid, address_type, address, phy, interval,
          clock_accuracy));
  sync_client_handler();
}

// Test for the scenario where an application tries to register a scanner with an already used UUID.
TEST_F(LeScanningManagerTest, register_scanner) {
  start_le_scanning_manager();
  Uuid app_uuid = Uuid::From16Bit(1);
  EXPECT_CALL(mock_callbacks_,
              OnScannerRegistered(app_uuid, 1, ScanningCallback::ScanningStatus::SUCCESS));

  le_scanning_manager->RegisterScanner(app_uuid);
  sync_client_handler();
}

// Test for the scenario where the maximum number of scanners have been registered.
TEST_F(LeScanningManagerTest, register_scanner_max_clients_reached) {
  start_le_scanning_manager();
  // Register kMaxAppNum scanners
  for (uint8_t i = 1; i <= LeScanningManagerImpl::kMaxAppNum; ++i) {
    Uuid app_uuid = Uuid::From16Bit(i);
    EXPECT_CALL(mock_callbacks_,
                OnScannerRegistered(app_uuid, i, ScanningCallback::ScanningStatus::SUCCESS));
    le_scanning_manager->RegisterScanner(app_uuid);
    sync_client_handler();
  }

  // Try to register one more scanner beyond the maximum limit.
  Uuid new_app_uuid = Uuid::From16Bit(LeScanningManagerImpl::kMaxAppNum + 1);
  EXPECT_CALL(mock_callbacks_, OnScannerRegistered(new_app_uuid, 0x00,
                                                   ScanningCallback::ScanningStatus::NO_RESOURCES));
  le_scanning_manager->RegisterScanner(new_app_uuid);
  sync_client_handler();
}
// Test for unregistering a scanner with an invalid scanner ID (<= 0 or > kMaxAppNum).
TEST_F(LeScanningManagerTest, unregister_scanner_invalid_id) {
  start_le_scanning_manager();
  // No EXPECT_CALL is needed since the function should return early without calling the callback.
  le_scanning_manager->Unregister(0);
  le_scanning_manager->Unregister(LeScanningManagerImpl::kMaxAppNum + 1);
  sync_client_handler();
}

// Test for unregistering an already unregistered scanner ID.
TEST_F(LeScanningManagerTest, unregister_scanner_unused_id) {
  start_le_scanning_manager();
  // No EXPECT_CALL is needed as the function should log a warning but not trigger a callback.
  le_scanning_manager->Unregister(1);
  sync_client_handler();
}

// Test for the `validate_scan_params` function with invalid scan type.
TEST_F(LeScanningManagerTest, validate_scan_params_invalid_scan_type) {
  // The value 0x03 is not a valid LeScanType (should be ACTIVE=1 or PASSIVE=2)
  start_le_scanning_manager();
  LeScanType invalid_scan_type = static_cast<LeScanType>(0x03);
  uint8_t scanner_id = 1;
  uint16_t scan_interval = 1000;
  uint16_t scan_window = 1000;

  EXPECT_CALL(mock_callbacks_,
              OnSetScannerParameterComplete(scanner_id,
                                            ScanningCallback::ScanningStatus::ILLEGAL_PARAMETER));
  le_scanning_manager->SetScanParameters(invalid_scan_type, scanner_id, scan_interval, scan_window,
                                         scanner_id, scan_interval, scan_window, 1);
  sync_client_handler();
}

// Test for `validate_scan_params` with an invalid scan interval (too low).
TEST_F(LeScanningManagerTest, validate_scan_params_invalid_interval_too_low) {
  start_le_scanning_manager();
  LeScanType scan_type = LeScanType::ACTIVE;
  uint8_t scanner_id = 1;
  uint16_t scan_interval = 3;  // kLeScanIntervalMin is 4
  uint16_t scan_window = 1000;

  EXPECT_CALL(mock_callbacks_,
              OnSetScannerParameterComplete(scanner_id,
                                            ScanningCallback::ScanningStatus::ILLEGAL_PARAMETER));
  le_scanning_manager->SetScanParameters(scan_type, scanner_id, scan_interval, scan_window,
                                         scanner_id, scan_interval, scan_window, 1);
  sync_client_handler();
}

// Test for `validate_scan_params` with an invalid scan window (too low).
TEST_F(LeScanningManagerTest, validate_scan_params_invalid_window_too_low) {
  start_le_scanning_manager();
  LeScanType scan_type = LeScanType::ACTIVE;
  uint8_t scanner_id = 1;
  uint16_t scan_interval = 1000;
  uint16_t scan_window = 3;  // kLeScanWindowMin is 4

  EXPECT_CALL(mock_callbacks_,
              OnSetScannerParameterComplete(scanner_id,
                                            ScanningCallback::ScanningStatus::ILLEGAL_PARAMETER));
  le_scanning_manager->SetScanParameters(scan_type, scanner_id, scan_interval, scan_window,
                                         scanner_id, scan_interval, scan_window, 1);
  sync_client_handler();
}

// Test the 'DELETE' case in `scan_filter_parameter_setup` for a non-bonded device.
TEST_F(LeScanningManagerTest, scan_filter_parameter_setup_delete_non_bonded) {
  // Make is_filter_supported_ true for this test case
  test_controller_->AddSupported(OpCode::LE_ADV_FILTER);
  start_le_scanning_manager();

  // Set these to a single bit value (0 or 1) as required by the CheckParameterValues function.
  uint8_t transport_discovery_data_filter = 0x01;
  uint8_t ad_type_filter = 0x01;
  uint8_t filter_index = 0x01;
  Address test_address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  AddressWithType address_with_type(test_address, AddressType::PUBLIC_DEVICE_ADDRESS);

  // Set up the map entry indirectly by calling a public method.
  // First, set up expectations for the ADD command.
  std::vector<AdvertisingPacketContentFilterCommand> filters = {};
  hci::AdvertisingPacketContentFilterCommand filter =
          make_filter(hci::ApcfFilterType::BROADCASTER_ADDRESS);
  filter.address = test_address;
  filters.push_back(filter);

  le_scanning_manager->ScanFilterAdd(filter_index, filters);
  test_hci_layer_->IncomingEvent(LeAdvFilterReadExtendedFeaturesCompleteBuilder::Create(
          1, ErrorCode::SUCCESS, transport_discovery_data_filter, ad_type_filter));
  sync_client_handler();

  le_scanning_manager->ScanFilterParameterSetup(ApcfAction::DELETE, filter_index,
                                                AdvertisingFilterParameter{});
  test_hci_layer_->IncomingEvent(LeAdvFilterSetFilteringParametersCompleteBuilder::Create(
          1, ErrorCode::SUCCESS, ApcfAction::DELETE, 0x0a));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, start_sync_public_api) {
  uint8_t sid = 0x01;
  Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  AddressWithType address_with_type(address, AddressType::PUBLIC_DEVICE_ADDRESS);
  uint16_t skip = 0;
  uint16_t timeout = 1000;
  int reg_id = 1;

  // The call should not crash.
  le_scanning_manager->StartSync(sid, address_with_type, skip, timeout, reg_id);
  sync_client_handler();
}

// Test to cover the LeScanningManagerImpl::StopSync() public API.
TEST_F(LeScanningManagerAndroidHciTest, stop_sync_public_api) {
  uint16_t handle = 0x0001;

  // The call should not crash.
  le_scanning_manager->StopSync(handle);
  sync_client_handler();
}

// Test to cover the LeScanningManagerImpl::CancelCreateSync() public API.
TEST_F(LeScanningManagerAndroidHciTest, cancel_create_sync_public_api) {
  uint8_t sid = 0x01;
  Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});

  // The call should not crash.
  le_scanning_manager->CancelCreateSync(sid, address);
  sync_client_handler();
}

// Test to cover the LeScanningManagerImpl::SyncTxParameters() public API.
TEST_F(LeScanningManagerAndroidHciTest, sync_tx_parameters_public_api) {
  Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  uint8_t mode = 0;
  uint16_t skip = 0;
  uint16_t timeout = 1000;
  int reg_id = 1;

  // The call should not crash.
  le_scanning_manager->SyncTxParameters(address, mode, skip, timeout, reg_id);
  sync_client_handler();
}

// Test to cover the LeScanningManagerImpl::TrackAdvertiser() public API.
TEST_F(LeScanningManagerAndroidHciTest, track_advertiser_public_api) {
  uint8_t filter_index = 0x01;
  ScannerId scanner_id = 0x02;

  // The call should not crash.
  le_scanning_manager->TrackAdvertiser(filter_index, scanner_id);
  sync_client_handler();
}

// Test to cover BatchScanDisable() public API
TEST_F(LeScanningManagerTest, batch_scan_disable_public_api) {
  // We need to enable batch scan first. The implementation will send multiple
  // HCI commands for this. We will simply call the public API.
  start_le_scanning_manager();
  le_scanning_manager->BatchScanConfigStorage(100, 0, 95, 0x01);
  le_scanning_manager->BatchScanEnable(BatchScanMode::FULL, 2400, 2400,
                                       BatchScanDiscardRule::OLDEST);
  sync_client_handler();

  // Now, call the function to disable batch scan.
  le_scanning_manager->BatchScanDisable();
  sync_client_handler();

  // The test passes if the call completes without crashing.
}

// Test to cover SetScanFilterPolicy() public API
TEST_F(LeScanningManagerTest, set_scan_filter_policy_public_api) {
  start_le_scanning_manager();
  // This is a simple pass-through test. A successful, non-crashing call
  // is sufficient coverage for this line.
  le_scanning_manager->SetScanFilterPolicy(LeScanningFilterPolicy::FILTER_ACCEPT_LIST_ONLY);
  sync_client_handler();
}

// Test case for on_advertisement_tracking with an unregistered filter index.
TEST_F(LeScanningManagerAndroidHciTest, on_advertisement_tracking_unregistered) {
  Address test_address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});

  // The filter index is not registered with TrackAdvertiser, so no callback is expected.
  EXPECT_CALL(mock_callbacks_, OnTrackAdvFoundLost(_)).Times(0);

  VseSubeventCode subevent_code = VseSubeventCode ::BLE_THRESHOLD;
  std::unique_ptr<BasePacketBuilder> payload = std::make_unique<RawBuilder>();
  // Simulate an advertisement tracking event using the new helper method.
  auto event_builder =
          LEAdvertisementTrackingEventBuilder::Create(subevent_code, std::move(payload));
  test_hci_layer_->IncomingVendorSpecificEvent(std::move(event_builder));
  sync_client_handler();
}

// Test case for on_advertisement_tracking with a registered filter and no advertiser info.
TEST_F(LeScanningManagerAndroidHciTest, on_advertisement_tracking_no_info) {
  uint8_t filter_index = 0x01;
  ScannerId scanner_id = 0x02;
  Address test_address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});

  // Register the filter index first.
  le_scanning_manager->TrackAdvertiser(filter_index, scanner_id);
  sync_client_handler();

  // Expect the callback to be called with no info.
  EXPECT_CALL(mock_callbacks_, OnTrackAdvFoundLost(::testing::Field(
                                       &AdvertisingFilterOnFoundOnLostInfo::advertiser_info_present,
                                       AdvtInfoPresent::NO_ADVT_INFO_PRESENT)));

  // Simulate an advertisement tracking event without info.
  auto event_builder = LEAdvertisementTrackingWithInfoEventBuilder::Create(
          filter_index, 0x01, AdvtInfoPresent::NO_ADVT_INFO_PRESENT, test_address, 0x01, 0, 0, 0,
          {}, {});
  test_hci_layer_->IncomingVendorSpecificEvent(std::move(event_builder));
  sync_client_handler();
}

// Test case for on_advertisement_tracking with a registered filter and full advertiser info.
TEST_F(LeScanningManagerAndroidHciTest, on_advertisement_tracking_with_info) {
  uint8_t filter_index = 0x01;
  ScannerId scanner_id = 0x02;
  AdvtInfoPresent adv_info_present = AdvtInfoPresent::ADVT_INFO_PRESENT;
  Address test_address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
  int8_t tx_power = 10;
  int8_t rssi = -50;
  std::vector<uint8_t> adv_data = {0x01, 0x02, 0x03};
  std::vector<uint8_t> scan_rsp_data = {0x04, 0x05, 0x06};
  uint16_t timestamp = 0x1234;

  // Register the filter index first.
  le_scanning_manager->TrackAdvertiser(filter_index, scanner_id);
  sync_client_handler();

  // Expect the callback to be called with all info.
  EXPECT_CALL(
          mock_callbacks_,
          OnTrackAdvFoundLost(::testing::AllOf(
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::advertiser_info_present,
                                   AdvtInfoPresent::ADVT_INFO_PRESENT),
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::tx_power, tx_power),
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::rssi, rssi),
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::adv_packet, adv_data),
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::scan_response,
                                   scan_rsp_data),
                  ::testing::Field(&AdvertisingFilterOnFoundOnLostInfo::time_stamp, timestamp))));

  // Simulate an advertisement tracking event with info.
  auto event_builder = LEAdvertisementTrackingWithInfoEventBuilder::Create(
          filter_index, 0x01, adv_info_present, test_address, 0x01, tx_power, rssi, timestamp,
          adv_data, scan_rsp_data);
  test_hci_layer_->IncomingVendorSpecificEvent(std::move(event_builder));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, on_batch_scan_disable_complete_test) {
  le_scanning_manager->BatchScanDisable();
  sync_client_handler();
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());

  // The handler for disable is on_batch_scan_disable_complete, which asserts SUCCESS.
  // We need to create a CommandComplete for LE_BATCH_SCAN with a payload for
  // LeBatchScanSetScanParametersComplete.
  // The payload is status (1 byte) + batch_scan_opcode (1 byte).
  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets1(static_cast<uint8_t>(ErrorCode::SUCCESS));
  payload->AddOctets1(static_cast<uint8_t>(BatchScanOpcode::SET_SCAN_PARAMETERS));

  auto complete_builder =
          CommandCompleteBuilder::Create(1, OpCode::LE_BATCH_SCAN, std::move(payload));

  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, on_set_extended_scan_params_complete_test) {
  // Enable scan, which will trigger sending LE_EXTENDED_SCAN_PARAMS
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_EXTENDED_SCAN_PARAMS, test_hci_layer_->GetCommand().GetOpCode());

  // Test with success status
  test_hci_layer_->IncomingEvent(
          LeExtendedScanParamsCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // After scan parameters are set, scan enable is sent.
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Stop scan to be able to start again
  le_scanning_manager->Scan(false);
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();

  // Enable scan again to test error case
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_EXTENDED_SCAN_PARAMS, test_hci_layer_->GetCommand().GetOpCode());

  // Test with an error status
  test_hci_layer_->IncomingEvent(LeExtendedScanParamsCompleteBuilder::Create(
          uint8_t{1}, ErrorCode::INVALID_HCI_COMMAND_PARAMETERS));
  sync_client_handler();

  // After scan parameters are set (even with error), scan enable is sent.
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  sync_client_handler();
}

TEST_F(LeScanningManagerTest, LegacyAdvNonConnIndReport) {
  start_le_scanning_manager();

  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  LeAdvertisingResponse report = make_advertising_report();
  report.event_type_ = AdvertisingEventType::ADV_NONCONN_IND;

  uint16_t extended_event_type = kLegacy;
  EXPECT_CALL(mock_callbacks_, OnScanResult(extended_event_type, _, _, _, _, _, _, _, _, _));

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest,
       on_advertising_filter_complete_service_solicitation_uuid_test) {
  std::vector<AdvertisingPacketContentFilterCommand> filters;
  hci::AdvertisingPacketContentFilterCommand filter = {
          .filter_type = hci::ApcfFilterType::SERVICE_SOLICITATION_UUID,
          .uuid = Uuid::From16Bit(0x180A),
          .uuid_mask = Uuid::From16Bit(0xFFFF)};
  filters.push_back(filter);
  le_scanning_manager->ScanFilterAdd(0x01, filters);
  sync_client_handler();

  // Simulate the HCI event from the controller
  EXPECT_CALL(mock_callbacks_,
              OnFilterConfigCallback(ApcfFilterType::SERVICE_SOLICITATION_UUID,
                                     10,  // Example value for available spaces
                                     ApcfAction::ADD, (uint8_t)ErrorCode::SUCCESS));

  test_hci_layer_->IncomingEvent(LeAdvFilterSolicitationUuidCompleteBuilder::Create(
          0x01, ErrorCode::SUCCESS, ApcfAction::ADD, 10 /* Available spaces */));
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest,
       batch_scan_set_storage_parameters_complete_with_error_test) {
  le_scanning_manager->BatchScanConfigStorage(100, 0, 95, 0x00);
  sync_client_handler();
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());

  // First command (enable scan) succeeds
  auto payload_success = std::make_unique<RawBuilder>();
  payload_success->AddOctets1(static_cast<uint8_t>(ErrorCode::SUCCESS));
  payload_success->AddOctets1(static_cast<uint8_t>(BatchScanOpcode::ENABLE));
  auto success_builder =
          CommandCompleteBuilder::Create(1, OpCode::LE_BATCH_SCAN, std::move(payload_success));
  test_hci_layer_->IncomingEvent(std::move(success_builder));
  sync_client_handler();

  // Second command (set storage params) fails
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());
  auto payload_error = std::make_unique<RawBuilder>();
  payload_error->AddOctets1(static_cast<uint8_t>(ErrorCode::COMMAND_DISALLOWED));
  payload_error->AddOctets1(static_cast<uint8_t>(BatchScanOpcode::SET_STORAGE_PARAMETERS));
  auto error_builder =
          CommandCompleteBuilder::Create(1, OpCode::LE_BATCH_SCAN, std::move(payload_error));
  test_hci_layer_->IncomingEvent(std::move(error_builder));
  sync_client_handler();
}

TEST_F(LeScanningManagerTest, TrackAdvertiserMaxReached_AndroidHci) {
  test_controller_->AddSupported(OpCode::LE_EXTENDED_SCAN_PARAMS);
  test_controller_->AddSupported(OpCode::LE_ADV_FILTER);
  test_controller_->AddSupported(OpCode::LE_BATCH_SCAN);
  test_controller_->SetBlePeriodicAdvertisingSyncTransferSenderSupport(true);
  Controller::VendorCapabilities vendor_caps = {};
  vendor_caps.total_num_of_advt_tracked_ = 1;  // Max 1
  ON_CALL(*test_controller_, GetVendorCapabilities()).WillByDefault(::testing::Return(vendor_caps));

  start_le_scanning_manager();

  ASSERT_EQ(OpCode::LE_ADV_FILTER, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(LeAdvFilterReadExtendedFeaturesCompleteBuilder::Create(
          1, ErrorCode::SUCCESS, 0x01, 0x01));

  // Track first advertiser, should succeed
  uint8_t filter_index_1 = 0x01;
  ScannerId scanner_id_1 = 0x02;
  le_scanning_manager->TrackAdvertiser(filter_index_1, scanner_id_1);
  sync_client_handler();

  // Track second advertiser, should fail
  uint8_t filter_index_2 = 0x02;
  ScannerId scanner_id_2 = 0x03;
  EXPECT_CALL(mock_callbacks_, OnTrackAdvFoundLost(::testing::Field(
                                       &AdvertisingFilterOnFoundOnLostInfo::advertiser_info_present,
                                       AdvtInfoPresent::NO_ADVT_INFO_PRESENT)));
  le_scanning_manager->TrackAdvertiser(filter_index_2, scanner_id_2);
  sync_client_handler();
}

TEST_F(LeScanningManagerAndroidHciTest, batch_scan_enable_complete_with_error_test) {
  le_scanning_manager->BatchScanEnable(BatchScanMode::FULL, 2400, 2400,
                                       BatchScanDiscardRule::OLDEST);
  sync_client_handler();
  ASSERT_EQ(OpCode::LE_BATCH_SCAN, test_hci_layer_->GetCommand().GetOpCode());

  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets1(static_cast<uint8_t>(ErrorCode::COMMAND_DISALLOWED));
  payload->AddOctets1(static_cast<uint8_t>(BatchScanOpcode::ENABLE));
  auto complete_builder =
          CommandCompleteBuilder::Create(1, OpCode::LE_BATCH_SCAN, std::move(payload));
  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();
}

TEST_F(LeScanningManagerTest, CompensationValueOutOfRangeHigh) {
  const std::string kLeRxPathLossCompensation = "128";
  ASSERT_TRUE(os::SetSystemProperty(kLeRxPathLossCompensation, "128"));
  start_le_scanning_manager();
  le_scanning_manager->Scan(true);
  ASSERT_EQ(OpCode::LE_SET_SCAN_PARAMETERS, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanParametersCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));
  ASSERT_EQ(OpCode::LE_SET_SCAN_ENABLE, test_hci_layer_->GetCommand().GetOpCode());
  test_hci_layer_->IncomingEvent(
          LeSetScanEnableCompleteBuilder::Create(uint8_t{1}, ErrorCode::SUCCESS));

  LeAdvertisingResponse report = make_advertising_report();
  report.rssi_ = 10;

  // Compensation is invalid, so it should be 0. RSSI should be unchanged.
  EXPECT_CALL(mock_callbacks_, OnScanResult(_, _, _, _, _, _, _, 10, _, _));

  test_hci_layer_->IncomingLeMetaEvent(LeAdvertisingReportBuilder::Create({report}));
  sync_client_handler();
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
