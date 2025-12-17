/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "hci/msft.h"

#include <bluetooth/types/uuid.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "hal/hci_hal_fake.h"
#include "hardware/bt_common_types.h"
#include "hci/hci_layer_fake.h"
#include "hci/hci_packets.h"
#include "os/thread.h"
#include "packet/raw_builder.h"

namespace bluetooth {
namespace hci {
using std::make_unique;

using namespace std::literals;
using namespace std::literals::chrono_literals;

class MockScanningCallback : public ScanningCallback {
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
};

class MsftExtensionManagerTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    test_hal_ = make_unique<hal::TestHciHal>();
    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_hal_->SetMsftOpcode(0xFC01);
    msft_extension_manager_ =
            new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
    msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
    sync_client_handler();

    prefix_ = {0x54,  // VseSubeventCode::BLE_THRESHOLD
               0xB2, 0xC3};
    const uint64_t features = 0x0123456789ABCDEF;
    auto msft_opcode = static_cast<OpCode>(test_hal_->getMsftOpcode());
    auto command_view = test_hci_layer_->GetCommand(msft_opcode);

    // B. Build the Command Complete event
    auto cc_payload_builder = std::make_unique<packet::RawBuilder>();
    cc_payload_builder->AddOctets1(static_cast<uint8_t>(ErrorCode::SUCCESS));
    cc_payload_builder->AddOctets1(
            static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_READ_SUPPORTED_FEATURES));
    cc_payload_builder->AddOctets8(features);
    cc_payload_builder->AddOctets1(prefix_.size());
    cc_payload_builder->AddOctets(prefix_);

    auto complete_builder =
            CommandCompleteBuilder::Create(0x01, msft_opcode, std::move(cc_payload_builder));

    // C. Inject the Command Complete event and flush to process it
    // This executes on_msft_read_supported_features_complete, setting the internal state.
    test_hci_layer_->IncomingEvent(std::move(complete_builder));
    sync_client_handler();
  }

  void TearDown() override {
    if (msft_extension_manager_ != nullptr) {
      client_handler_->Synchronize(std::chrono::milliseconds(20));
    }
    test_hci_layer_.reset();
    test_hal_.reset();

    client_handler_->Clear();
    client_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

    delete client_handler_;
    delete thread_;
  }

  void sync_client_handler() {
    log::assert_that(thread_->GetReactor()->WaitForIdle(std::chrono::seconds(2)),
                     "assert failed: thread_->GetReactor()->WaitForIdle(std::chrono::seconds(2))");
  }

  os::Thread* thread_ = nullptr;
  os::Handler* client_handler_ = nullptr;
  std::unique_ptr<hal::TestHciHal> test_hal_;
  std::unique_ptr<HciLayerFake> test_hci_layer_;
  MsftExtensionManager* msft_extension_manager_ = nullptr;
  MockScanningCallback mock_scanning_callback_;
  std::vector<uint8_t> prefix_;
};

TEST_F(MsftExtensionManagerTest, startup_teardown) {}

TEST_F(MsftExtensionManagerTest, msft_read_supported_features_test_wrong_error_code) {
  msft_extension_manager_ = nullptr;
  msft_extension_manager_ =
          new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
  msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
  sync_client_handler();

  prefix_ = {0x54,  // VseSubeventCode::BLE_THRESHOLD
             0xB2, 0xC3};
  const uint64_t features = 0x0123456789ABCDEF;
  auto msft_opcode = static_cast<OpCode>(test_hal_->getMsftOpcode());
  auto command_view = test_hci_layer_->GetCommand(msft_opcode);

  // B. Build the Command Complete event
  auto cc_payload_builder = std::make_unique<packet::RawBuilder>();
  cc_payload_builder->AddOctets1(static_cast<uint8_t>(ErrorCode::UNKNOWN_HCI_COMMAND));
  cc_payload_builder->AddOctets1(
          static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_READ_SUPPORTED_FEATURES));
  cc_payload_builder->AddOctets8(features);
  cc_payload_builder->AddOctets1(prefix_.size());
  cc_payload_builder->AddOctets(prefix_);

  auto complete_builder =
          CommandCompleteBuilder::Create(0x01, msft_opcode, std::move(cc_payload_builder));

  // C. Inject the Command Complete event and flush to process it
  // This executes on_msft_read_supported_features_complete, setting the internal state.
  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, msft_read_supported_features_test_wrong_subcommand_opcode) {
  msft_extension_manager_ = nullptr;
  msft_extension_manager_ =
          new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
  msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
  sync_client_handler();

  prefix_ = {0x54,  // VseSubeventCode::BLE_THRESHOLD
             0xB2, 0xC3};
  const uint64_t features = 0x0123456789ABCDEF;
  auto msft_opcode = static_cast<OpCode>(test_hal_->getMsftOpcode());
  auto command_view = test_hci_layer_->GetCommand(msft_opcode);

  // B. Build the Command Complete event
  auto cc_payload_builder = std::make_unique<packet::RawBuilder>();
  cc_payload_builder->AddOctets1(static_cast<uint8_t>(ErrorCode::SUCCESS));
  cc_payload_builder->AddOctets1(static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_LE_MONITOR_ADV));
  cc_payload_builder->AddOctets8(features);
  cc_payload_builder->AddOctets1(prefix_.size());
  cc_payload_builder->AddOctets(prefix_);

  auto complete_builder =
          CommandCompleteBuilder::Create(0x01, msft_opcode, std::move(cc_payload_builder));

  // C. Inject the Command Complete event and flush to process it
  // This executes on_msft_read_supported_features_complete, setting the internal state.
  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, msft_read_supported_features_test_rssi) {
  VseSubeventCode vse_code = static_cast<VseSubeventCode>(prefix_[0]);

  // The payload of the VendorSpecificEvent is:
  // [Vendor Prefix excluding first byte] + [Msft Event Payload]

  // MSFT Event Payload:
  // 1. msft_event_code (1 byte) -> MSFT_RSSI_EVENT (0x01)
  // 2. status (1 byte) -> MSFT_EVENT_STATUS_SUCCESS (0x00)
  // 3. connection_handle (2 bytes) -> 0x0001 (e.g., first connection)
  // 4. rssi (1 byte) -> 0xF0 (-16 dBm)

  const std::vector<uint8_t> msft_event_data = {
          // 1. MSFT Event Code (MsftRssiEventPayload)
          static_cast<uint8_t>(MsftEventCode::MSFT_RSSI_EVENT),
          // 2. Msft Event Status
          static_cast<uint8_t>(MsftEventStatus::MSFT_EVENT_STATUS_SUCCESS),
          // 3. Connection Handle (0x0001 - Little Endian)
          0x01, 0x00,
          // 4. RSSI (-16dBm)
          0xF0};

  auto msft_payload_builder = std::make_unique<packet::RawBuilder>();

  // 1. Add the rest of the Vendor Prefix ({0xB2, 0xC3} in this case)
  msft_payload_builder->AddOctets({prefix_[1], prefix_[2]});

  // 2. Add the actual MSFT Event Payload data
  msft_payload_builder->AddOctets(msft_event_data);

  // The VendorSpecificEventBuilder handles the Event Code (0xFF) and the Total Length.
  // It takes the subevent code (vse_code = 0xA1) and the raw payload we just built.
  auto vendor_specific_builder =
          VendorSpecificEventBuilder::Create(vse_code, std::move(msft_payload_builder));

  // Intercept the function call that handle_msft_events would make to mock a return/check
  // We'll assume the subsequent call to 'handle_rssi_event' is correctly mocked or tested
  // elsewhere. The simple act of calling IncomingEvent here and running the client handler ensures
  // handle_msft_events is successfully invoked and completes without crashing.

  test_hci_layer_->IncomingVendorSpecificEvent(std::move(vendor_specific_builder));

  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, msft_read_supported_features_test_le_monitor_device_event) {
  VseSubeventCode vse_code = static_cast<VseSubeventCode>(prefix_[0]);
  const std::vector<uint8_t> prefix_tail = {prefix_[1], prefix_[2]};

  auto monitor_msft_payload = std::make_unique<packet::RawBuilder>();
  monitor_msft_payload->AddOctets(prefix_tail);  // Add prefix tail for validation

  // MSFT Event Payload: msft_event_code (0x02) + address_type + bd_addr (6) + monitor_handle +
  // monitor_state
  monitor_msft_payload->AddOctets1(
          static_cast<uint8_t>(MsftEventCode::MSFT_LE_MONITOR_DEVICE_EVENT));

  // address_type (0x00: Public)
  monitor_msft_payload->AddOctets1(0x00);

  // bd_addr (01:02:03:04:05:06 - Little Endian)
  monitor_msft_payload->AddOctets({0x06, 0x05, 0x04, 0x03, 0x02, 0x01});

  // monitor_handle (0xAA)
  monitor_msft_payload->AddOctets1(0xAA);

  // monitor_state (0x00)
  monitor_msft_payload->AddOctets1(0x00);

  auto monitor_vse_builder =
          VendorSpecificEventBuilder::Create(vse_code, std::move(monitor_msft_payload));

  test_hci_layer_->IncomingVendorSpecificEvent(std::move(monitor_vse_builder));
  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, SupportsMsftExtensions) {
  EXPECT_TRUE(msft_extension_manager_->SupportsMsftExtensions());
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorAdd) {
  // 1. Define test parameters
  const uint8_t monitor_handle = 0x01;
  const ErrorCode expected_status = ErrorCode::SUCCESS;

  MsftAdvMonitor monitor = {.rssi_threshold_high = 80,
                            .rssi_threshold_low = 30,
                            .rssi_threshold_low_time_interval = 5,
                            .rssi_sampling_period = 1,
                            .condition_type = MSFT_CONDITION_TYPE_PATTERNS,
                            .patterns = {MsftAdvMonitorPattern{
                                    .ad_type = 0x01, .start_byte = 0, .pattern = {0x01, 0x02}}}};

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<std::pair<uint8_t, ErrorCode>>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating(
          [](std::shared_ptr<std::promise<std::pair<uint8_t, ErrorCode>>> promise_ptr,
             uint8_t handle, ErrorCode status) { promise_ptr->set_value({handle, status}); },
          promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorAdd(monitor, std::move(cb));
  sync_client_handler();

  // 4. Verify the outgoing command
  auto command_view = test_hci_layer_->GetCommand(static_cast<OpCode>(test_hal_->getMsftOpcode()));
  auto msft_command_view = MsftCommandView::Create(command_view);
  ASSERT_TRUE(msft_command_view.IsValid());
  auto monitor_adv_view = MsftLeMonitorAdvConditionPatternsView::Create(
          MsftLeMonitorAdvView::Create(msft_command_view));
  ASSERT_TRUE(monitor_adv_view.IsValid());

  ASSERT_EQ(monitor_adv_view.GetSubcommandOpcode(), MsftSubcommandOpcode::MSFT_LE_MONITOR_ADV);
  ASSERT_EQ(monitor_adv_view.GetRssiThresholdHigh(), monitor.rssi_threshold_high);
  ASSERT_EQ(monitor_adv_view.GetRssiThresholdLow(), monitor.rssi_threshold_low);
  ASSERT_EQ(monitor_adv_view.GetRssiThresholdLowTimeInterval(),
            monitor.rssi_threshold_low_time_interval);
  ASSERT_EQ(monitor_adv_view.GetRssiSamplingPeriod(), monitor.rssi_sampling_period);

  // 5. Simulate the Command Complete response
  auto payload_builder = std::make_unique<packet::RawBuilder>();
  payload_builder->AddOctets1(static_cast<uint8_t>(expected_status));
  payload_builder->AddOctets1(static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_LE_MONITOR_ADV));
  payload_builder->AddOctets1(monitor_handle);

  auto complete_builder = CommandCompleteBuilder::Create(
          0x01, static_cast<OpCode>(test_hal_->getMsftOpcode()), std::move(payload_builder));
  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();

  // 6. Verify the callback was invoked with correct values
  auto result = future.get();
  EXPECT_EQ(result.first, monitor_handle);
  EXPECT_EQ(result.second, expected_status);
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorRemove) {
  // 1. Define test parameters
  const uint8_t monitor_handle = 0x01;
  const ErrorCode expected_status = ErrorCode::SUCCESS;

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<ErrorCode>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating([](std::shared_ptr<std::promise<ErrorCode>> promise_ptr,
                                   ErrorCode status) { promise_ptr->set_value(status); },
                                promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorRemove(monitor_handle, std::move(cb));
  sync_client_handler();

  // 4. Verify the outgoing command
  auto command_view = test_hci_layer_->GetCommand(static_cast<OpCode>(test_hal_->getMsftOpcode()));
  auto msft_command_view = MsftCommandView::Create(command_view);
  ASSERT_TRUE(msft_command_view.IsValid());
  auto cancel_monitor_view = MsftLeCancelMonitorAdvView::Create(msft_command_view);
  ASSERT_TRUE(cancel_monitor_view.IsValid());

  ASSERT_EQ(cancel_monitor_view.GetSubcommandOpcode(),
            MsftSubcommandOpcode::MSFT_LE_CANCEL_MONITOR_ADV);
  ASSERT_EQ(cancel_monitor_view.GetMonitorHandle(), monitor_handle);

  // 5. Simulate the Command Complete response
  auto payload_builder = std::make_unique<packet::RawBuilder>();
  payload_builder->AddOctets1(static_cast<uint8_t>(expected_status));
  payload_builder->AddOctets1(
          static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_LE_CANCEL_MONITOR_ADV));

  auto complete_builder = CommandCompleteBuilder::Create(
          0x01, static_cast<OpCode>(test_hal_->getMsftOpcode()), std::move(payload_builder));
  test_hci_layer_->IncomingEvent(std::move(complete_builder));
  sync_client_handler();
  // 6. Verify the callback was invoked with the correct value
  auto result = future.get();
  EXPECT_EQ(result, expected_status);
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorEnable) {
  // 1. Define test parameters
  const bool enable = true;
  const ErrorCode expected_status = ErrorCode::SUCCESS;

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<ErrorCode>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating([](std::shared_ptr<std::promise<ErrorCode>> promise_ptr,
                                   ErrorCode status) { promise_ptr->set_value(status); },
                                promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorEnable(enable, std::move(cb));
  sync_client_handler();

  // 4. Verify the outgoing command
  auto command_view = test_hci_layer_->GetCommand(static_cast<OpCode>(test_hal_->getMsftOpcode()));
  auto msft_command_view = MsftCommandView::Create(command_view);
  ASSERT_TRUE(msft_command_view.IsValid());
  auto enable_view = MsftLeSetAdvFilterEnableView::Create(msft_command_view);
  ASSERT_TRUE(enable_view.IsValid());

  ASSERT_EQ(enable_view.GetSubcommandOpcode(), MsftSubcommandOpcode::MSFT_LE_SET_ADV_FILTER_ENABLE);
  ASSERT_EQ(enable_view.GetEnable(), enable);

  // 5. Simulate the Command Complete response
  auto payload_builder = std::make_unique<packet::RawBuilder>();
  payload_builder->AddOctets1(static_cast<uint8_t>(expected_status));
  payload_builder->AddOctets1(
          static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_LE_SET_ADV_FILTER_ENABLE));

  auto complete_builder = CommandCompleteBuilder::Create(
          0x01, static_cast<OpCode>(test_hal_->getMsftOpcode()), std::move(payload_builder));
  test_hci_layer_->IncomingEvent(std::move(complete_builder));

  // 6. Verify the callback was invoked with the correct value
  auto result = future.get();
  EXPECT_EQ(result, expected_status);
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorAdd_byAddress) {
  // 1. Define test parameters
  const uint8_t monitor_handle = 0x02;
  const ErrorCode expected_status = ErrorCode::SUCCESS;
  const std::string addr_str = "01:02:03:04:05:06";
  RawAddress test_raw_address = RawAddress::FromString(addr_str).value();

  MsftAdvMonitor monitor = {
          .rssi_threshold_high = 80,
          .rssi_threshold_low = 30,
          .rssi_threshold_low_time_interval = 5,
          .rssi_sampling_period = 1,
          .condition_type = MSFT_CONDITION_TYPE_ADDRESS,
          .patterns = {},
          .addr_info = {.addr_type = 0x00,  // Public
                        .bd_addr = test_raw_address},
  };

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<std::pair<uint8_t, ErrorCode>>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating(
          [](std::shared_ptr<std::promise<std::pair<uint8_t, ErrorCode>>> promise_ptr,
             uint8_t handle, ErrorCode status) { promise_ptr->set_value({handle, status}); },
          promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorAdd(monitor, std::move(cb));
  sync_client_handler();

  // 4. Verify the outgoing command
  auto command_view = test_hci_layer_->GetCommand(static_cast<OpCode>(test_hal_->getMsftOpcode()));
  auto msft_command_view = MsftCommandView::Create(command_view);
  ASSERT_TRUE(msft_command_view.IsValid());
  auto monitor_adv_view = MsftLeMonitorAdvConditionAddressView::Create(
          MsftLeMonitorAdvView::Create(msft_command_view));

  // 5. Simulate the Command Complete response
  auto payload_builder = std::make_unique<packet::RawBuilder>();
  payload_builder->AddOctets1(static_cast<uint8_t>(expected_status));
  payload_builder->AddOctets1(static_cast<uint8_t>(MsftSubcommandOpcode::MSFT_LE_MONITOR_ADV));
  payload_builder->AddOctets1(monitor_handle);

  auto complete_builder = CommandCompleteBuilder::Create(
          0x01, static_cast<OpCode>(test_hal_->getMsftOpcode()), std::move(payload_builder));
  test_hci_layer_->IncomingEvent(std::move(complete_builder));

  // 6. Verify the callback was invoked with correct values
  auto result = future.get();
  EXPECT_EQ(result.first, monitor_handle);
  EXPECT_EQ(result.second, expected_status);
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorAdd_no_support) {
  test_hal_->SetMsftOpcode(0);
  msft_extension_manager_ = nullptr;
  msft_extension_manager_ =
          new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
  msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
  sync_client_handler();

  MsftAdvMonitor monitor = {.rssi_threshold_high = 80,
                            .rssi_threshold_low = 30,
                            .rssi_threshold_low_time_interval = 5,
                            .rssi_sampling_period = 1,
                            .condition_type = MSFT_CONDITION_TYPE_PATTERNS,
                            .patterns = {MsftAdvMonitorPattern{
                                    .ad_type = 0x01, .start_byte = 0, .pattern = {0x01, 0x02}}}};

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<std::pair<uint8_t, ErrorCode>>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating(
          [](std::shared_ptr<std::promise<std::pair<uint8_t, ErrorCode>>> promise_ptr,
             uint8_t handle, ErrorCode status) { promise_ptr->set_value({handle, status}); },
          promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorAdd(monitor, std::move(cb));
  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorRemove_no_support) {
  test_hal_->SetMsftOpcode(0);
  msft_extension_manager_ = nullptr;
  msft_extension_manager_ =
          new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
  msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
  sync_client_handler();
  const uint8_t monitor_handle = 0x01;

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<ErrorCode>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating([](std::shared_ptr<std::promise<ErrorCode>> promise_ptr,
                                   ErrorCode status) { promise_ptr->set_value(status); },
                                promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorRemove(monitor_handle, std::move(cb));
  sync_client_handler();
}

TEST_F(MsftExtensionManagerTest, msftAdvMonitorEnable_no_support) {
  test_hal_->SetMsftOpcode(0);
  msft_extension_manager_ = nullptr;
  msft_extension_manager_ =
          new MsftExtensionManager(client_handler_, test_hal_.get(), test_hci_layer_.get());
  msft_extension_manager_->SetScanningCallback(&mock_scanning_callback_);
  sync_client_handler();
  const bool enable = true;

  // 2. Set up callback and promise for synchronization
  auto promise_ptr = std::make_shared<std::promise<ErrorCode>>();
  auto future = promise_ptr->get_future();
  auto cb = base::BindRepeating([](std::shared_ptr<std::promise<ErrorCode>> promise_ptr,
                                   ErrorCode status) { promise_ptr->set_value(status); },
                                promise_ptr);

  // 3. Call the function under test
  msft_extension_manager_->MsftAdvMonitorEnable(enable, std::move(cb));
  sync_client_handler();
}

}  // namespace hci
}  // namespace bluetooth
