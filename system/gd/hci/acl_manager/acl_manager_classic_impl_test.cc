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

#include "hci/acl_manager/acl_manager_classic_impl.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <future>

#include "common/bind.h"
#include "hci/acl_manager/acl_manager_le_impl.h"
#include "hci/acl_manager/connection_callbacks_mock.h"
#include "hci/acl_manager/connection_management_callbacks_mock.h"
#include "hci/address.h"
#include "hci/controller_mock.h"
#include "hci/hci_layer_fake.h"
#include "hci/remote_name_request_mock.h"
#include "os/fake_timer/fake_timerfd.h"
#include "os/thread.h"
#include "packet/raw_builder.h"
#include "storage/storage_module.h"

using bluetooth::common::BidiQueue;
using bluetooth::common::BidiQueueEnd;
using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::packet::kLittleEndian;
using bluetooth::packet::PacketView;
using bluetooth::packet::RawBuilder;
using testing::_;
using testing::ElementsAreArray;

namespace {

constexpr auto kTimeout = std::chrono::seconds(2);
constexpr auto kShortTimeout = std::chrono::milliseconds(100);
constexpr uint16_t kHciHandle = 123;
const bluetooth::hci::AddressWithType empty_address_with_type = bluetooth::hci::AddressWithType();

}  // namespace

namespace bluetooth {
namespace hci {
namespace acl_manager {

class TestController : public testing::MockController {
public:
  void RegisterCompletedAclPacketsCallback(
          common::ContextualCallback<void(uint16_t /* handle */, uint16_t /* packets */)> cb)
          override {
    acl_cb_ = cb;
  }

  void UnregisterCompletedAclPacketsCallback() override { acl_cb_ = {}; }

  uint16_t GetAclPacketLength() const override { return acl_buffer_length_; }

  uint16_t GetNumAclPacketBuffers() const override { return total_acl_buffers_; }

  bool IsSupported(bluetooth::hci::OpCode /* op_code */) const override { return false; }

  LeBufferSize GetLeBufferSize() const override {
    LeBufferSize le_buffer_size;
    le_buffer_size.total_num_le_packets_ = 2;
    le_buffer_size.le_data_packet_length_ = 32;
    return le_buffer_size;
  }

  void CompletePackets(uint16_t handle, uint16_t packets) { acl_cb_(handle, packets); }

  uint16_t acl_buffer_length_ = 1024;
  uint16_t total_acl_buffers_ = 2;
  common::ContextualCallback<void(uint16_t /* handle */, uint16_t /* packets */)> acl_cb_;
};

class FakeLeAclDataConsumer : public LeAclDataConsumer {
public:
  virtual bool SendPacketUpward(
          uint16_t /* handle */,
          std::function<void(struct acl_manager::assembler* assembler)> /* cb */) override {
    return false;
  }
};

class AclManagerClassicNoCallbacksTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    ASSERT_NE(client_handler_, nullptr);
    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_controller_ = std::make_unique<TestController>();

    test_hci_layer_->SetLeAclDataConsumer(&fakeLeAclDataConsumer_);

    EXPECT_CALL(*test_controller_, GetMacAddress());
    EXPECT_CALL(*test_controller_, GetLeFilterAcceptListSize());
    EXPECT_CALL(*test_controller_, GetLeResolvingListSize());
    EXPECT_CALL(*test_controller_, SupportsBlePrivacy());

    test_storage_ = std::make_unique<storage::StorageModule>(client_handler_);

    test_rnr_ = std::make_unique<RemoteNameRequestModuleMock>();

    test_acl_scheduler_ = std::make_unique<AclScheduler>(client_handler_);
    test_round_robin_scheduler_ = std::make_unique<RoundRobinScheduler>(
            client_handler_, *test_controller_, test_hci_layer_->GetAclQueueEnd());
    acl_manager_classic_ = std::make_unique<AclManagerClassicImpl>(
            client_handler_, *test_hci_layer_, *test_acl_scheduler_, *test_rnr_,
            *test_round_robin_scheduler_);
    acl_manager_ = std::make_unique<AclManagerLeImpl>(
            client_handler_, *test_hci_layer_, *test_controller_, *test_storage_,
            *test_round_robin_scheduler_, *acl_manager_classic_);

    remote = Address::FromString("A1:A2:A3:A4:A5:A6").value();

    hci::Address address = Address::FromString("D0:05:04:03:02:01").value();
    hci::AddressWithType address_with_type(address, hci::AddressType::RANDOM_DEVICE_ADDRESS);
    auto minimum_rotation_time = std::chrono::milliseconds(7 * 60 * 1000);
    auto maximum_rotation_time = std::chrono::milliseconds(15 * 60 * 1000);
    acl_manager_->SetPrivacyPolicyForInitiatorAddress(
            LeAddressManager::AddressPolicy::USE_STATIC_ADDRESS, address_with_type,
            minimum_rotation_time, maximum_rotation_time);

    auto set_random_address_packet =
            LeSetRandomAddressView::Create(LeAdvertisingCommandView::Create(
                    GetConnectionManagementCommand(OpCode::LE_SET_RANDOM_ADDRESS)));
    ASSERT_TRUE(set_random_address_packet.IsValid());
    my_initiating_address = AddressWithType(set_random_address_packet.GetRandomAddress(),
                                            AddressType::RANDOM_DEVICE_ADDRESS);
    test_hci_layer_->IncomingEvent(
            LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

    ON_CALL(mock_connection_callback_, OnConnectSuccess)
            .WillByDefault(
                    [this](std::unique_ptr<ClassicAclConnection> connection, Role /* role */) {
                      connections_.push_back(std::move(connection));
                      if (connection_promise_ != nullptr) {
                        connection_promise_->set_value();
                        connection_promise_.reset();
                      }
                    });
  }

  void TearDown() override {
    // Invalid mutex exception is raised if the connections
    // are cleared after the AclConnectionInterface is deleted
    // through fake_registry_.
    test_storage_.reset();
    connections_.clear();
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    test_rnr_.reset();
    test_acl_scheduler_.reset();
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    acl_manager_.reset();
    acl_manager_classic_.reset();
    test_round_robin_scheduler_.reset();
    test_controller_.reset();
    test_hci_layer_.reset();

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
  std::unique_ptr<AclScheduler> test_acl_scheduler_ = nullptr;
  std::unique_ptr<HciLayerFake> test_hci_layer_ = nullptr;
  std::unique_ptr<TestController> test_controller_ = nullptr;
  std::unique_ptr<RemoteNameRequestModuleMock> test_rnr_ = nullptr;
  std::unique_ptr<storage::StorageModule> test_storage_ = nullptr;
  std::unique_ptr<RoundRobinScheduler> test_round_robin_scheduler_ = nullptr;
  std::unique_ptr<AclManagerClassicImpl> acl_manager_classic_ = nullptr;
  std::unique_ptr<AclManagerLeImpl> acl_manager_ = nullptr;

  FakeLeAclDataConsumer fakeLeAclDataConsumer_;
  Address remote;
  AddressWithType my_initiating_address;
  const bool use_accept_list_ = true;  // gd currently only supports connect list

  std::future<void> GetConnectionFuture() {
    log::assert_that(connection_promise_ == nullptr, "Promises promises ... Only one at a time");
    connection_promise_ = std::make_unique<std::promise<void>>();
    return connection_promise_->get_future();
  }

  std::shared_ptr<ClassicAclConnection> GetLastConnection() { return connections_.back(); }

  void SendAclData(uint16_t handle, AclConnection::QueueUpEnd* queue_end) {
    std::promise<void> promise;
    auto future = promise.get_future();
    queue_end->RegisterEnqueue(
            client_handler_,
            common::Bind(
                    [](decltype(queue_end) queue_end, uint16_t handle, std::promise<void> promise) {
                      queue_end->UnregisterEnqueue();
                      promise.set_value();
                      return NextPayload(handle);
                    },
                    queue_end, handle, base::Passed(std::move(promise))));
    auto status = future.wait_for(kTimeout);
    ASSERT_EQ(status, std::future_status::ready);
  }

  ConnectionManagementCommandView GetConnectionManagementCommand(OpCode op_code) {
    auto base_command = test_hci_layer_->GetCommand();
    ConnectionManagementCommandView command =
            ConnectionManagementCommandView::Create(AclCommandView::Create(base_command));
    EXPECT_TRUE(command.IsValid());
    EXPECT_EQ(command.GetOpCode(), op_code);
    return command;
  }

  std::list<std::shared_ptr<ClassicAclConnection>> connections_;
  std::unique_ptr<std::promise<void>> connection_promise_;
  MockConnectionCallback mock_connection_callback_;
};

class AclManagerClassicTest : public AclManagerClassicNoCallbacksTest {
protected:
  void SetUp() override {
    AclManagerClassicNoCallbacksTest::SetUp();
    acl_manager_classic_->RegisterCallbacks(&mock_connection_callback_, client_handler_);
  }
  void TearDown() override { AclManagerClassicNoCallbacksTest::TearDown(); }
};

class AclManagerClassicWithConnectionTest : public AclManagerClassicTest {
protected:
  void SetUp() override {
    AclManagerClassicTest::SetUp();

    handle_ = 0x123;
    acl_manager_classic_->CreateConnection(remote, 0);

    // Wait for the connection request
    auto last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
    while (!last_command.IsValid()) {
      last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
    }

    auto first_connection = GetConnectionFuture();
    test_hci_layer_->IncomingEvent(ConnectionCompleteBuilder::Create(
            ErrorCode::SUCCESS, handle_, remote, LinkType::ACL, Enable::DISABLED));

    auto first_connection_status = first_connection.wait_for(kTimeout);
    ASSERT_EQ(first_connection_status, std::future_status::ready);

    connection_ = GetLastConnection();
    connection_->RegisterCallbacks(&mock_connection_management_callbacks_, client_handler_);
  }

  void TearDown() override {
    // Invalid mutex exception is raised if the connection
    // is cleared after the AclConnectionInterface is deleted
    // through fake_registry_.
    connection_.reset();
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    AclManagerClassicTest::TearDown();
  }

  uint16_t handle_;
  std::shared_ptr<ClassicAclConnection> connection_;

  MockConnectionManagementCallbacks mock_connection_management_callbacks_;
};

TEST_F(AclManagerClassicTest, startup_teardown) {}

TEST_F(AclManagerClassicTest, invoke_registered_callback_connection_complete_success) {
  acl_manager_classic_->CreateConnection(remote, 0);

  // Wait for the connection request
  auto last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
  while (!last_command.IsValid()) {
    last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
  }

  auto first_connection = GetConnectionFuture();

  test_hci_layer_->IncomingEvent(ConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, remote, LinkType::ACL, Enable::DISABLED));

  auto first_connection_status = first_connection.wait_for(kTimeout);
  ASSERT_EQ(first_connection_status, std::future_status::ready);

  auto connection = GetLastConnection();
  ASSERT_EQ(connection->GetAddress(), remote);
}

TEST_F(AclManagerClassicTest, invoke_registered_callback_connection_complete_fail) {
  acl_manager_classic_->CreateConnection(remote, 0);

  // Wait for the connection request
  auto last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
  while (!last_command.IsValid()) {
    last_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);
  }

  struct callback_t {
    hci::Address bd_addr;
    hci::ErrorCode reason;
    bool is_locally_initiated;
  };

  auto promise = std::promise<callback_t>();
  auto future = promise.get_future();
  ON_CALL(mock_connection_callback_, OnConnectFail)
          .WillByDefault([&promise](hci::Address bd_addr, hci::ErrorCode reason,
                                    bool is_locally_initiated) {
            promise.set_value({
                    .bd_addr = bd_addr,
                    .reason = reason,
                    .is_locally_initiated = is_locally_initiated,
            });
          });

  EXPECT_CALL(mock_connection_callback_, OnConnectFail(remote, ErrorCode::PAGE_TIMEOUT, true));

  // Remote response event to the connection request
  test_hci_layer_->IncomingEvent(ConnectionCompleteBuilder::Create(
          ErrorCode::PAGE_TIMEOUT, kHciHandle, remote, LinkType::ACL, Enable::DISABLED));

  ASSERT_EQ(std::future_status::ready, future.wait_for(kTimeout));
  auto callback = future.get();

  ASSERT_EQ(remote, callback.bd_addr);
  ASSERT_EQ(ErrorCode::PAGE_TIMEOUT, callback.reason);
  ASSERT_EQ(true, callback.is_locally_initiated);
}

TEST_F(AclManagerClassicWithConnectionTest, invoke_registered_callback_disconnection_complete) {
  auto reason = ErrorCode::REMOTE_USER_TERMINATED_CONNECTION;
  EXPECT_CALL(mock_connection_management_callbacks_, OnDisconnection(reason));
  test_hci_layer_->Disconnect(handle_, reason);
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, acl_send_data_one_connection) {
  // Send a packet from HCI
  test_hci_layer_->IncomingAclData(handle_);
  auto queue_end = connection_->GetAclQueueEnd();

  std::unique_ptr<PacketView<kLittleEndian>> received;
  do {
    received = queue_end->TryDequeue();
  } while (received == nullptr);

  PacketView<kLittleEndian> received_packet = *received;

  // Send a packet from the connection
  SendAclData(handle_, connection_->GetAclQueueEnd());

  auto sent_packet = test_hci_layer_->OutgoingAclData();

  // Send another packet from the connection
  SendAclData(handle_, connection_->GetAclQueueEnd());

  sent_packet = test_hci_layer_->OutgoingAclData();
  auto reason = ErrorCode::AUTHENTICATION_FAILURE;
  EXPECT_CALL(mock_connection_management_callbacks_, OnDisconnection(reason));
  connection_->Disconnect(DisconnectReason::AUTHENTICATION_FAILURE);
  auto packet = GetConnectionManagementCommand(OpCode::DISCONNECT);
  auto command_view = DisconnectView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetConnectionHandle(), handle_);
  test_hci_layer_->Disconnect(handle_, reason);
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, acl_send_data_credits) {
  // Use all the credits
  for (uint16_t credits = 0; credits < test_controller_->total_acl_buffers_; credits++) {
    // Send a packet from the connection
    SendAclData(handle_, connection_->GetAclQueueEnd());

    auto sent_packet = test_hci_layer_->OutgoingAclData();
  }

  // Send another packet from the connection
  SendAclData(handle_, connection_->GetAclQueueEnd());

  test_hci_layer_->AssertNoOutgoingAclData();

  test_controller_->CompletePackets(handle_, 1);

  auto after_credits_sent_packet = test_hci_layer_->OutgoingAclData();
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_switch_role) {
  acl_manager_classic_->SwitchRole(connection_->GetAddress(), Role::PERIPHERAL);
  auto packet = GetConnectionManagementCommand(OpCode::SWITCH_ROLE);
  auto command_view = SwitchRoleView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetBdAddr(), connection_->GetAddress());
  ASSERT_EQ(command_view.GetRole(), Role::PERIPHERAL);

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnRoleChange(hci::ErrorCode::SUCCESS, Role::PERIPHERAL));
  test_hci_layer_->IncomingEvent(RoleChangeBuilder::Create(
          ErrorCode::SUCCESS, connection_->GetAddress(), Role::PERIPHERAL));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_write_default_link_policy_settings) {
  uint16_t link_policy_settings = 0x05;
  acl_manager_classic_->WriteDefaultLinkPolicySettings(link_policy_settings);
  auto packet = GetConnectionManagementCommand(OpCode::WRITE_DEFAULT_LINK_POLICY_SETTINGS);
  auto command_view = WriteDefaultLinkPolicySettingsView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetDefaultLinkPolicySettings(), 0x05);

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(
          WriteDefaultLinkPolicySettingsCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS));
  sync_client_handler();

  ASSERT_EQ(link_policy_settings, acl_manager_classic_->ReadDefaultLinkPolicySettings());
}

TEST_F(AclManagerClassicWithConnectionTest, send_authentication_requested) {
  connection_->AuthenticationRequested();
  auto packet = GetConnectionManagementCommand(OpCode::AUTHENTICATION_REQUESTED);
  auto command_view = AuthenticationRequestedView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnAuthenticationComplete);
  test_hci_layer_->IncomingEvent(
          AuthenticationCompleteBuilder::Create(ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_clock_offset) {
  connection_->ReadClockOffset();
  auto packet = GetConnectionManagementCommand(OpCode::READ_CLOCK_OFFSET);
  auto command_view = ReadClockOffsetView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadClockOffsetComplete(0x0123));
  test_hci_layer_->IncomingEvent(
          ReadClockOffsetCompleteBuilder::Create(ErrorCode::SUCCESS, handle_, 0x0123));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_sniff_mode) {
  connection_->SniffMode(0x0500, 0x0020, 0x0040, 0x0014);
  auto packet = GetConnectionManagementCommand(OpCode::SNIFF_MODE);
  auto command_view = SniffModeView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetSniffMaxInterval(), 0x0500);
  ASSERT_EQ(command_view.GetSniffMinInterval(), 0x0020);
  ASSERT_EQ(command_view.GetSniffAttempt(), 0x0040);
  ASSERT_EQ(command_view.GetSniffTimeout(), 0x0014);

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnModeChange(ErrorCode::SUCCESS, Mode::SNIFF, 0x0028));
  test_hci_layer_->IncomingEvent(
          ModeChangeBuilder::Create(ErrorCode::SUCCESS, handle_, Mode::SNIFF, 0x0028));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_exit_sniff_mode) {
  connection_->ExitSniffMode();
  auto packet = GetConnectionManagementCommand(OpCode::EXIT_SNIFF_MODE);
  auto command_view = ExitSniffModeView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnModeChange(ErrorCode::SUCCESS, Mode::ACTIVE, 0x00));
  test_hci_layer_->IncomingEvent(
          ModeChangeBuilder::Create(ErrorCode::SUCCESS, handle_, Mode::ACTIVE, 0x00));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_qos_setup) {
  connection_->QosSetup(ServiceType::BEST_EFFORT, 0x1234, 0x1233, 0x1232, 0x1231);
  auto packet = GetConnectionManagementCommand(OpCode::QOS_SETUP);
  auto command_view = QosSetupView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetServiceType(), ServiceType::BEST_EFFORT);
  ASSERT_EQ(command_view.GetTokenRate(), 0x1234u);
  ASSERT_EQ(command_view.GetPeakBandwidth(), 0x1233u);
  ASSERT_EQ(command_view.GetLatency(), 0x1232u);
  ASSERT_EQ(command_view.GetDelayVariation(), 0x1231u);

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnQosSetupComplete(ServiceType::BEST_EFFORT, 0x1234, 0x1233, 0x1232, 0x1231));
  test_hci_layer_->IncomingEvent(QosSetupCompleteBuilder::Create(
          ErrorCode::SUCCESS, handle_, ServiceType::BEST_EFFORT, 0x1234, 0x1233, 0x1232, 0x1231));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_flow_specification) {
  connection_->FlowSpecification(FlowDirection::OUTGOING_FLOW, ServiceType::BEST_EFFORT, 0x1234,
                                 0x1233, 0x1232, 0x1231);
  auto packet = GetConnectionManagementCommand(OpCode::FLOW_SPECIFICATION);
  auto command_view = FlowSpecificationView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetFlowDirection(), FlowDirection::OUTGOING_FLOW);
  ASSERT_EQ(command_view.GetServiceType(), ServiceType::BEST_EFFORT);
  ASSERT_EQ(command_view.GetTokenRate(), 0x1234u);
  ASSERT_EQ(command_view.GetTokenBucketSize(), 0x1233u);
  ASSERT_EQ(command_view.GetPeakBandwidth(), 0x1232u);
  ASSERT_EQ(command_view.GetAccessLatency(), 0x1231u);

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnFlowSpecificationComplete(FlowDirection::OUTGOING_FLOW, ServiceType::BEST_EFFORT,
                                          0x1234, 0x1233, 0x1232, 0x1231));
  test_hci_layer_->IncomingEvent(FlowSpecificationCompleteBuilder::Create(
          ErrorCode::SUCCESS, handle_, FlowDirection::OUTGOING_FLOW, ServiceType::BEST_EFFORT,
          0x1234, 0x1233, 0x1232, 0x1231));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_flush) {
  connection_->Flush();
  auto packet = GetConnectionManagementCommand(OpCode::ENHANCED_FLUSH);
  auto command_view = EnhancedFlushView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnFlushOccurred());
  test_hci_layer_->IncomingEvent(EnhancedFlushCompleteBuilder::Create(handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_role_discovery) {
  connection_->RoleDiscovery();
  auto packet = GetConnectionManagementCommand(OpCode::ROLE_DISCOVERY);
  auto command_view = RoleDiscoveryView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnRoleDiscoveryComplete(Role::CENTRAL));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(RoleDiscoveryCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, Role::CENTRAL));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_link_policy_settings) {
  connection_->ReadLinkPolicySettings();
  auto packet = GetConnectionManagementCommand(OpCode::READ_LINK_POLICY_SETTINGS);
  auto command_view = ReadLinkPolicySettingsView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadLinkPolicySettingsComplete(0x07));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadLinkPolicySettingsCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, 0x07));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_write_link_policy_settings) {
  connection_->WriteLinkPolicySettings(0x05);
  auto packet = GetConnectionManagementCommand(OpCode::WRITE_LINK_POLICY_SETTINGS);
  auto command_view = WriteLinkPolicySettingsView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetLinkPolicySettings(), 0x05);

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(
          WriteLinkPolicySettingsCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_sniff_subrating) {
  connection_->SniffSubrating(0x1234, 0x1235, 0x1236);
  auto packet = GetConnectionManagementCommand(OpCode::SNIFF_SUBRATING);
  auto command_view = SniffSubratingView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetMaximumLatency(), 0x1234);
  ASSERT_EQ(command_view.GetMinimumRemoteTimeout(), 0x1235);
  ASSERT_EQ(command_view.GetMinimumLocalTimeout(), 0x1236);

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(
          SniffSubratingCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_automatic_flush_timeout) {
  connection_->ReadAutomaticFlushTimeout();
  auto packet = GetConnectionManagementCommand(OpCode::READ_AUTOMATIC_FLUSH_TIMEOUT);
  auto command_view = ReadAutomaticFlushTimeoutView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadAutomaticFlushTimeoutComplete(0x07ff));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadAutomaticFlushTimeoutCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, 0x07ff));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_write_automatic_flush_timeout) {
  connection_->WriteAutomaticFlushTimeout(0x07FF);
  auto packet = GetConnectionManagementCommand(OpCode::WRITE_AUTOMATIC_FLUSH_TIMEOUT);
  auto command_view = WriteAutomaticFlushTimeoutView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetFlushTimeout(), 0x07FF);

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(WriteAutomaticFlushTimeoutCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_transmit_power_level) {
  connection_->ReadTransmitPowerLevel(TransmitPowerLevelType::CURRENT);
  auto packet = GetConnectionManagementCommand(OpCode::READ_TRANSMIT_POWER_LEVEL);
  auto command_view = ReadTransmitPowerLevelView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetTransmitPowerLevelType(), TransmitPowerLevelType::CURRENT);

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadTransmitPowerLevelComplete(0x07));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadTransmitPowerLevelCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, 0x07));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_link_supervision_timeout) {
  connection_->ReadLinkSupervisionTimeout();
  auto packet = GetConnectionManagementCommand(OpCode::READ_LINK_SUPERVISION_TIMEOUT);
  auto command_view = ReadLinkSupervisionTimeoutView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadLinkSupervisionTimeoutComplete(0x5677));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadLinkSupervisionTimeoutCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, 0x5677));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_write_link_supervision_timeout) {
  connection_->WriteLinkSupervisionTimeout(0x5678);
  auto packet = GetConnectionManagementCommand(OpCode::WRITE_LINK_SUPERVISION_TIMEOUT);
  auto command_view = WriteLinkSupervisionTimeoutView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetLinkSupervisionTimeout(), 0x5678);

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(WriteLinkSupervisionTimeoutCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_failed_contact_counter) {
  connection_->ReadFailedContactCounter();
  auto packet = GetConnectionManagementCommand(OpCode::READ_FAILED_CONTACT_COUNTER);
  auto command_view = ReadFailedContactCounterView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadFailedContactCounterComplete(0x00));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadFailedContactCounterCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, 0x00));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_reset_failed_contact_counter) {
  connection_->ResetFailedContactCounter();
  auto packet = GetConnectionManagementCommand(OpCode::RESET_FAILED_CONTACT_COUNTER);
  auto command_view = ResetFailedContactCounterView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ResetFailedContactCounterCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_link_quality) {
  connection_->ReadLinkQuality();
  auto packet = GetConnectionManagementCommand(OpCode::READ_LINK_QUALITY);
  auto command_view = ReadLinkQualityView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadLinkQualityComplete(0xa9));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(
          ReadLinkQualityCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, handle_, 0xa9));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_afh_channel_map) {
  connection_->ReadAfhChannelMap();
  auto packet = GetConnectionManagementCommand(OpCode::READ_AFH_CHANNEL_MAP);
  auto command_view = ReadAfhChannelMapView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  std::array<uint8_t, 10> afh_channel_map = {0x00, 0x01, 0x02, 0x03, 0x04,
                                             0x05, 0x06, 0x07, 0x08, 0x09};

  EXPECT_CALL(mock_connection_management_callbacks_,
              OnReadAfhChannelMapComplete(AfhMode::AFH_ENABLED, afh_channel_map));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadAfhChannelMapCompleteBuilder::Create(
          num_packets, ErrorCode::SUCCESS, handle_, AfhMode::AFH_ENABLED, afh_channel_map));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_rssi) {
  connection_->ReadRssi();
  auto packet = GetConnectionManagementCommand(OpCode::READ_RSSI);
  auto command_view = ReadRssiView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  sync_client_handler();
  EXPECT_CALL(mock_connection_management_callbacks_, OnReadRssiComplete(0x00));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(
          ReadRssiCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS, handle_, 0x00));
  sync_client_handler();
}

TEST_F(AclManagerClassicWithConnectionTest, send_read_clock) {
  connection_->ReadClock(WhichClock::LOCAL);
  auto packet = GetConnectionManagementCommand(OpCode::READ_CLOCK);
  auto command_view = ReadClockView::Create(packet);
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetWhichClock(), WhichClock::LOCAL);

  EXPECT_CALL(mock_connection_management_callbacks_, OnReadClockComplete(0x00002e6a, 0x0000));
  uint8_t num_packets = 1;
  test_hci_layer_->IncomingEvent(ReadClockCompleteBuilder::Create(num_packets, ErrorCode::SUCCESS,
                                                                  handle_, 0x00002e6a, 0x0000));
  sync_client_handler();
}

class AclManagerClassicWithResolvableAddressTest : public AclManagerClassicNoCallbacksTest {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    ASSERT_NE(client_handler_, nullptr);

    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_controller_ = std::make_unique<TestController>();
    test_storage_ = std::make_unique<storage::StorageModule>(client_handler_);

    test_rnr_ = std::make_unique<RemoteNameRequestModuleMock>();
    test_acl_scheduler_ = std::make_unique<AclScheduler>(client_handler_);
    test_round_robin_scheduler_ = std::make_unique<RoundRobinScheduler>(
            client_handler_, *test_controller_, test_hci_layer_->GetAclQueueEnd());
    acl_manager_classic_ = std::make_unique<AclManagerClassicImpl>(
            client_handler_, *test_hci_layer_, *test_acl_scheduler_, *test_rnr_,
            *test_round_robin_scheduler_);
    acl_manager_ = std::make_unique<AclManagerLeImpl>(
            client_handler_, *test_hci_layer_, *test_controller_, *test_storage_,
            *test_round_robin_scheduler_, *acl_manager_classic_);

    remote = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  }
};

class AclManagerClassicLifeCycleTest : public AclManagerClassicNoCallbacksTest {
protected:
  void SetUp() override {
    AclManagerClassicNoCallbacksTest::SetUp();
    acl_manager_classic_->RegisterCallbacks(&mock_connection_callback_, client_handler_);
  }

  AddressWithType remote_with_type_;
  uint16_t handle_{0x123};
};

TEST_F(AclManagerClassicLifeCycleTest, unregister_classic_after_create_connection) {
  // Inject create connection
  acl_manager_classic_->CreateConnection(remote, 0);
  auto connection_command = GetConnectionManagementCommand(OpCode::CREATE_CONNECTION);

  // Unregister callbacks after sending connection request
  auto promise = std::promise<void>();
  auto future = promise.get_future();
  acl_manager_classic_->UnregisterCallbacks(&mock_connection_callback_, std::move(promise));
  future.get();

  // Inject peer sending connection complete
  auto connection_future = GetConnectionFuture();
  test_hci_layer_->IncomingEvent(ConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, handle_, remote, LinkType::ACL, Enable::DISABLED));

  sync_client_handler();
  auto connection_future_status = connection_future.wait_for(kShortTimeout);
  ASSERT_NE(connection_future_status, std::future_status::ready);
}

class AclManagerClassicWithConnectionAssemblerTest : public AclManagerClassicWithConnectionTest {
protected:
  void SetUp() override {
    AclManagerClassicWithConnectionTest::SetUp();
    connection_queue_end_ = connection_->GetAclQueueEnd();
  }

  std::vector<uint8_t> MakeAclPayload(size_t length, uint16_t cid, uint8_t offset) {
    std::vector<uint8_t> acl_payload;
    acl_payload.push_back(length & 0xff);
    acl_payload.push_back((length >> 8u) & 0xff);
    acl_payload.push_back(cid & 0xff);
    acl_payload.push_back((cid >> 8u) & 0xff);
    for (uint8_t i = 0; i < length; i++) {
      acl_payload.push_back(i + offset);
    }
    return acl_payload;
  }

  void SendSinglePacket(const std::vector<uint8_t>& acl_payload) {
    auto payload_builder = std::make_unique<RawBuilder>(acl_payload);

    test_hci_layer_->IncomingAclData(
            handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                        BroadcastFlag::POINT_TO_POINT, std::move(payload_builder)));
  }

  void ReceiveAndCheckSinglePacket(const std::vector<uint8_t>& acl_payload) {
    std::unique_ptr<PacketView<kLittleEndian>> received;
    do {
      received = connection_queue_end_->TryDequeue();
    } while (received == nullptr);

    std::vector<uint8_t> received_vector;
    for (uint8_t byte : *received) {
      received_vector.push_back(byte);
    }

    EXPECT_THAT(received_vector, ElementsAreArray(acl_payload));
  }

  void SendAndReceiveSinglePacket(const std::vector<uint8_t>& acl_payload) {
    SendSinglePacket(acl_payload);
    ReceiveAndCheckSinglePacket(acl_payload);
  }

  void TearDown() override {
    // Make sure that all previous packets were received and the assembler is in a good state.
    SendAndReceiveSinglePacket(MakeAclPayload(0x60, 0xACC, 3));
    AclManagerClassicWithConnectionTest::TearDown();
  }
  AclConnection::QueueUpEnd* connection_queue_end_{};
};

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_single_packet) {}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_short_packet_discarded) {
  std::vector<uint8_t> invalid_payload{1, 2};
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(invalid_payload)));
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_two_short_packets_discarded) {
  std::vector<uint8_t> invalid_payload{1, 2};
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(invalid_payload)));
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(invalid_payload)));
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_single_valid_packet) {
  SendAndReceiveSinglePacket(MakeAclPayload(20, 0x41, 2));
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_one_byte_packets) {
  size_t payload_size = 0x30;
  std::vector<uint8_t> payload = MakeAclPayload(payload_size, 0xABB /* cid */, 4 /* offset */);
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(std::vector<uint8_t>{
                                              payload.cbegin(), payload.cbegin() + 1})));
  for (size_t i = 1; i < payload.size(); i++) {
    test_hci_layer_->IncomingAclData(
            handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::CONTINUING_FRAGMENT,
                                        BroadcastFlag::POINT_TO_POINT,
                                        std::make_unique<RawBuilder>(std::vector<uint8_t>{
                                                payload.cbegin() + i, payload.cbegin() + i + 1})));
  }
  ReceiveAndCheckSinglePacket(payload);
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_two_byte_packets) {
  size_t payload_size = 0x30;  // must be even
  std::vector<uint8_t> payload = MakeAclPayload(payload_size, 0xABB /* cid */, 4 /* offset */);
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(std::vector<uint8_t>{
                                              payload.cbegin(), payload.cbegin() + 2})));
  for (size_t i = 1; i < payload.size() / 2; i++) {
    test_hci_layer_->IncomingAclData(
            handle_,
            AclBuilder::Create(handle_, PacketBoundaryFlag::CONTINUING_FRAGMENT,
                               BroadcastFlag::POINT_TO_POINT,
                               std::make_unique<RawBuilder>(std::vector<uint8_t>{
                                       payload.cbegin() + 2 * i, payload.cbegin() + 2 * (i + 1)})));
  }
  ReceiveAndCheckSinglePacket(payload);
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_continuation_without_begin) {
  size_t payload_size = 0x30;
  std::vector<uint8_t> payload = MakeAclPayload(payload_size, 0xABB /* cid */, 4 /* offset */);
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::CONTINUING_FRAGMENT,
                                      BroadcastFlag::POINT_TO_POINT,
                                      std::make_unique<RawBuilder>(std::vector<uint8_t>{
                                              payload.cbegin(), payload.cend()})));
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_drop_broadcasts) {
  test_hci_layer_->IncomingAclData(
          handle_, AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE,
                                      BroadcastFlag::ACTIVE_PERIPHERAL_BROADCAST,
                                      std::make_unique<RawBuilder>(MakeAclPayload(
                                              20, 0xBBB /* cid */, 5 /* offset */))));
}

TEST_F(AclManagerClassicWithConnectionAssemblerTest, assembler_test_drop_non_flushable) {
  test_hci_layer_->IncomingAclData(
          handle_,
          AclBuilder::Create(handle_, PacketBoundaryFlag::FIRST_NON_AUTOMATICALLY_FLUSHABLE,
                             BroadcastFlag::POINT_TO_POINT,
                             std::make_unique<RawBuilder>(
                                     MakeAclPayload(20, 0xAAA /* cid */, 6 /* offset */))));
}

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
