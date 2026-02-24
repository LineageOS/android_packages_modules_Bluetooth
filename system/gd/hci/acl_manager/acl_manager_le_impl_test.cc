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

#include "hci/acl_manager/acl_manager_le_impl.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <future>

#include "common/bind.h"
#include "hci/acl_manager/le_connection_callbacks_mock.h"
#include "hci/acl_manager/le_connection_management_callbacks_mock.h"
#include "hci/address.h"
#include "hci/controller_mock.h"
#include "hci/hci_layer_fake.h"
#include "os/fake_timer/fake_timerfd.h"
#include "os/thread.h"
#include "storage/storage_module.h"

using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::packet::kLittleEndian;
using bluetooth::packet::PacketView;
using testing::_;

namespace {

constexpr auto kTimeout = std::chrono::seconds(2);
constexpr auto kShortTimeout = std::chrono::milliseconds(100);
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

class FakeClassicAclCountProvider : public ClassicAclCountProvider {
public:
  size_t GetAclCount() override { return 0; }
};
FakeClassicAclCountProvider acl_count_provider_mock;

class FakeClassicAclDataConsumer : public ClassicAclDataConsumer {
public:
  virtual bool SendPacketUpward(
          uint16_t /* handle */,
          std::function<void(struct acl_manager::assembler* assembler)> /* cb */) override {
    return false;
  }
};
FakeClassicAclDataConsumer fake_classic_acl_data_consumer;

class AclManagerNoCallbacksTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    ASSERT_NE(client_handler_, nullptr);
    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_controller_ = std::make_unique<TestController>();

    test_hci_layer_->SetClassicAclDataConsumer(&fake_classic_acl_data_consumer);

    EXPECT_CALL(*test_controller_, GetMacAddress());
    EXPECT_CALL(*test_controller_, GetLeFilterAcceptListSize());
    EXPECT_CALL(*test_controller_, GetLeResolvingListSize());
    EXPECT_CALL(*test_controller_, SupportsBlePrivacy());

    test_storage_ = std::make_unique<storage::StorageModule>(client_handler_);

    test_round_robin_scheduler_ = std::make_unique<RoundRobinScheduler>(
            client_handler_, *test_controller_, test_hci_layer_->GetAclQueueEnd());

    acl_manager_ = std::make_unique<AclManagerLeImpl>(
            client_handler_, *test_hci_layer_, *test_controller_, *test_storage_,
            *test_round_robin_scheduler_, acl_count_provider_mock);

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
  }

  void TearDown() override {
    // Invalid mutex exception is raised if the connections
    // are cleared after the AclConnectionInterface is deleted
    // through fake_registry_.
    test_storage_.reset();
    le_connections_.clear();
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    acl_manager_.reset();
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
  std::unique_ptr<HciLayerFake> test_hci_layer_ = nullptr;
  std::unique_ptr<TestController> test_controller_ = nullptr;
  std::unique_ptr<storage::StorageModule> test_storage_ = nullptr;
  std::unique_ptr<RoundRobinScheduler> test_round_robin_scheduler_ = nullptr;
  std::unique_ptr<AclManagerLeImpl> acl_manager_ = nullptr;
  Address remote;
  AddressWithType my_initiating_address;
  const bool use_accept_list_ = true;  // gd currently only supports connect list

  std::future<void> GetLeConnectionFuture() {
    log::assert_that(le_connection_promise_ == nullptr, "Promises promises ... Only one at a time");
    le_connection_promise_ = std::make_unique<std::promise<void>>();
    return le_connection_promise_->get_future();
  }

  std::shared_ptr<LeAclConnection> GetLastLeConnection() { return le_connections_.back(); }

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

  std::list<std::shared_ptr<LeAclConnection>> le_connections_;
  std::unique_ptr<std::promise<void>> le_connection_promise_;
  MockLeConnectionCallbacks mock_le_connection_callbacks_;
};

class AclManagerTest : public AclManagerNoCallbacksTest {
protected:
  void SetUp() override {
    AclManagerNoCallbacksTest::SetUp();
    acl_manager_->RegisterLeCallbacks(&mock_le_connection_callbacks_, client_handler_);
  }
  void TearDown() override { AclManagerNoCallbacksTest::TearDown(); }
};

TEST_F(AclManagerTest, startup_teardown) {}

class AclManagerWithLeConnectionTest : public AclManagerTest {
protected:
  void SetUp() override {
    AclManagerTest::SetUp();

    remote_with_type_ = AddressWithType(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
    acl_manager_->CreateLeConnection(remote_with_type_, true, false);
    GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
    test_hci_layer_->IncomingEvent(
            LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
    auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
    auto le_connection_management_command_view =
            LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
    auto command_view = LeCreateConnectionView::Create(le_connection_management_command_view);
    ASSERT_TRUE(command_view.IsValid());
    if (use_accept_list_) {
      ASSERT_EQ(command_view.GetPeerAddress(), empty_address_with_type.GetAddress());
      ASSERT_EQ(command_view.GetPeerAddressType(), empty_address_with_type.GetAddressType());
    } else {
      ASSERT_EQ(command_view.GetPeerAddress(), remote);
      ASSERT_EQ(command_view.GetPeerAddressType(), AddressType::PUBLIC_DEVICE_ADDRESS);
    }

    test_hci_layer_->IncomingEvent(
            LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

    auto first_connection = GetLeConnectionFuture();
    EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(remote_with_type_, _))
            .WillRepeatedly([this](hci::AddressWithType /* address_with_type */,
                                   std::unique_ptr<LeAclConnection> connection) {
              le_connections_.push_back(std::move(connection));
              if (le_connection_promise_ != nullptr) {
                le_connection_promise_->set_value();
                le_connection_promise_.reset();
              }
            });

    if (send_early_acl_) {
      log::info("Sending a packet with handle 0x{:02x} ({})", handle_, handle_);
      test_hci_layer_->IncomingAclData(handle_);
    }

    test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
            ErrorCode::SUCCESS, handle_, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
            0x0100, 0x0010, 0x0C80, ClockAccuracy::PPM_30));

    GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
    test_hci_layer_->IncomingEvent(
            LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

    auto first_connection_status = first_connection.wait_for(kTimeout);
    ASSERT_EQ(first_connection_status, std::future_status::ready);

    connection_ = GetLastLeConnection();
  }

  void TearDown() override {
    // Invalid mutex exception is raised if the connection
    // is cleared after the AclConnectionInterface is deleted
    // through fake_registry_.
    connection_.reset();
    client_handler_->Synchronize(std::chrono::milliseconds(20));
    AclManagerTest::TearDown();
  }

  uint16_t handle_ = 0x123;
  bool send_early_acl_ = false;
  std::shared_ptr<LeAclConnection> connection_;
  AddressWithType remote_with_type_;
  MockLeConnectionManagementCallbacks mock_le_connection_management_callbacks_;
};

class AclManagerWithLateLeConnectionTest : public AclManagerWithLeConnectionTest {
protected:
  void SetUp() override {
    send_early_acl_ = true;
    AclManagerWithLeConnectionTest::SetUp();
  }
};

// TODO: implement version of this test where controller supports Extended Advertising Feature in
// GetLeLocalSupportedFeatures, and LE Extended Create Connection is used
TEST_F(AclManagerWithLeConnectionTest, invoke_registered_callback_le_connection_complete_success) {
  ASSERT_EQ(connection_->GetLocalAddress(), my_initiating_address);
  ASSERT_EQ(connection_->GetRemoteAddress(), remote_with_type_);
}

TEST_F(AclManagerTest, invoke_registered_callback_le_connection_complete_fail) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  auto le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto command_view = LeCreateConnectionView::Create(le_connection_management_command_view);
  ASSERT_TRUE(command_view.IsValid());
  if (use_accept_list_) {
    ASSERT_EQ(command_view.GetPeerAddress(), hci::Address::kEmpty);
  } else {
    ASSERT_EQ(command_view.GetPeerAddress(), remote);
  }
  EXPECT_EQ(command_view.GetPeerAddressType(), AddressType::PUBLIC_DEVICE_ADDRESS);

  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectFail(remote_with_type, ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES));

  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES, 0x123, Role::CENTRAL,
          AddressType::PUBLIC_DEVICE_ADDRESS, remote, 0x0100, 0x0010, 0x0011,
          ClockAccuracy::PPM_30));

  packet = GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto remove_command_view =
          LeRemoveDeviceFromFilterAcceptListView::Create(le_connection_management_command_view);
  ASSERT_TRUE(remove_command_view.IsValid());
  test_hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
}

TEST_F(AclManagerTest, cancel_le_connection) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

  acl_manager_->CancelLeConnect(remote_with_type);
  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);
  auto le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto command_view = LeCreateConnectionCancelView::Create(le_connection_management_command_view);
  ASSERT_TRUE(command_view.IsValid());

  test_hci_layer_->IncomingEvent(
          LeCreateConnectionCancelCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::UNKNOWN_CONNECTION, 0x123, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote, 0x0100, 0x0010, 0x0011, ClockAccuracy::PPM_30));

  packet = GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto remove_command_view =
          LeRemoveDeviceFromFilterAcceptListView::Create(le_connection_management_command_view);
  ASSERT_TRUE(remove_command_view.IsValid());

  test_hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
}

TEST_F(AclManagerTest, create_connection_with_fast_mode) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  auto command_view = LeCreateConnectionView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetLeScanInterval(), kScanIntervalFast);
  ASSERT_EQ(command_view.GetLeScanWindow(), kScanWindowFast);
  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

  auto first_connection = GetLeConnectionFuture();
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(remote_with_type, _))
          .WillRepeatedly([this](hci::AddressWithType /* address_with_type */,
                                 std::unique_ptr<LeAclConnection> connection) {
            le_connections_.push_back(std::move(connection));
            if (le_connection_promise_ != nullptr) {
              le_connection_promise_->set_value();
              le_connection_promise_.reset();
            }
          });

  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x00, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          0x0100, 0x0010, 0x0C80, ClockAccuracy::PPM_30));

  GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto first_connection_status = first_connection.wait_for(kTimeout);
  ASSERT_EQ(first_connection_status, std::future_status::ready);
}

TEST_F(AclManagerTest, create_connection_with_slow_mode) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, false, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  auto command_view = LeCreateConnectionView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(command_view.GetLeScanInterval(), kScanIntervalSlow);
  ASSERT_EQ(command_view.GetLeScanWindow(), kScanWindowSlow);
  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  auto first_connection = GetLeConnectionFuture();
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(remote_with_type, _))
          .WillRepeatedly([this](hci::AddressWithType /* address_with_type */,
                                 std::unique_ptr<LeAclConnection> connection) {
            le_connections_.push_back(std::move(connection));
            if (le_connection_promise_ != nullptr) {
              le_connection_promise_->set_value();
              le_connection_promise_.reset();
            }
          });

  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x00, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          0x0100, 0x0010, 0x0C80, ClockAccuracy::PPM_30));
  GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto first_connection_status = first_connection.wait_for(kTimeout);
  ASSERT_EQ(first_connection_status, std::future_status::ready);
}

TEST_F(AclManagerWithLeConnectionTest, acl_send_data_one_le_connection) {
  ASSERT_EQ(connection_->GetRemoteAddress(), remote_with_type_);
  ASSERT_EQ(connection_->GetHandle(), handle_);

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
}

TEST_F(AclManagerWithLeConnectionTest, invoke_registered_callback_le_connection_update_success) {
  ASSERT_EQ(connection_->GetLocalAddress(), my_initiating_address);
  ASSERT_EQ(connection_->GetRemoteAddress(), remote_with_type_);
  ASSERT_EQ(connection_->GetHandle(), handle_);
  connection_->RegisterCallbacks(&mock_le_connection_management_callbacks_, client_handler_);

  std::promise<ErrorCode> promise;
  ErrorCode hci_status = hci::ErrorCode::SUCCESS;
  uint16_t connection_interval_min = 0x0012;
  uint16_t connection_interval_max = 0x0080;
  uint16_t connection_interval = (connection_interval_max + connection_interval_min) / 2;
  uint16_t connection_latency = 0x0001;
  uint16_t supervision_timeout = 0x0A00;
  connection_->LeConnectionUpdate(connection_interval_min, connection_interval_max,
                                  connection_latency, supervision_timeout, 0x10, 0x20);
  auto update_packet = GetConnectionManagementCommand(OpCode::LE_CONNECTION_UPDATE);
  auto update_view = LeConnectionUpdateView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(update_packet)));
  ASSERT_TRUE(update_view.IsValid());
  EXPECT_EQ(update_view.GetConnectionHandle(), handle_);
  test_hci_layer_->IncomingEvent(LeConnectionUpdateStatusBuilder::Create(ErrorCode::SUCCESS, 0x1));
  EXPECT_CALL(mock_le_connection_management_callbacks_,
              OnConnectionUpdate(hci_status, connection_interval, connection_latency,
                                 supervision_timeout));
  test_hci_layer_->IncomingLeMetaEvent(LeConnectionUpdateCompleteBuilder::Create(
          ErrorCode::SUCCESS, handle_, connection_interval, connection_latency,
          supervision_timeout));
  sync_client_handler();
}

TEST_F(AclManagerWithLeConnectionTest, invoke_registered_callback_le_disconnect) {
  ASSERT_EQ(connection_->GetRemoteAddress(), remote_with_type_);
  ASSERT_EQ(connection_->GetHandle(), handle_);
  connection_->RegisterCallbacks(&mock_le_connection_management_callbacks_, client_handler_);

  auto reason = ErrorCode::REMOTE_USER_TERMINATED_CONNECTION;
  EXPECT_CALL(mock_le_connection_management_callbacks_, OnDisconnection(reason));
  test_hci_layer_->Disconnect(handle_, reason);
  sync_client_handler();
}

TEST_F(AclManagerWithLeConnectionTest, invoke_registered_callback_le_disconnect_data_race) {
  ASSERT_EQ(connection_->GetRemoteAddress(), remote_with_type_);
  ASSERT_EQ(connection_->GetHandle(), handle_);
  connection_->RegisterCallbacks(&mock_le_connection_management_callbacks_, client_handler_);

  test_hci_layer_->IncomingAclData(handle_);
  auto reason = ErrorCode::REMOTE_USER_TERMINATED_CONNECTION;
  EXPECT_CALL(mock_le_connection_management_callbacks_, OnDisconnection(reason));
  test_hci_layer_->Disconnect(handle_, reason);
  sync_client_handler();
}

TEST_F(AclManagerWithLeConnectionTest, invoke_registered_callback_le_queue_disconnect) {
  auto reason = ErrorCode::REMOTE_USER_TERMINATED_CONNECTION;
  test_hci_layer_->Disconnect(handle_, reason);
  client_handler_->Synchronize(std::chrono::milliseconds(20));
  client_handler_->Synchronize(std::chrono::milliseconds(20));
  client_handler_->Synchronize(std::chrono::milliseconds(20));

  EXPECT_CALL(mock_le_connection_management_callbacks_, OnDisconnection(reason));
  connection_->RegisterCallbacks(&mock_le_connection_management_callbacks_, client_handler_);
  sync_client_handler();
}

TEST_F(AclManagerWithLateLeConnectionTest, and_receive_nothing) {}

TEST_F(AclManagerWithLateLeConnectionTest, receive_acl) {
  client_handler_->Post(common::BindOnce(fake_timerfd_advance, 1200));
  auto queue_end = connection_->GetAclQueueEnd();
  std::unique_ptr<PacketView<kLittleEndian>> received;
  do {
    received = queue_end->TryDequeue();
  } while (received == nullptr);

  {
    ASSERT_EQ(received->size(), 10u);
    auto itr = received->begin();
    ASSERT_EQ(itr.extract<uint16_t>(), 6u);  // L2CAP PDU size
    ASSERT_EQ(itr.extract<uint16_t>(), 2u);  // L2CAP CID
    ASSERT_EQ(itr.extract<uint16_t>(), handle_);
    ASSERT_GE(itr.extract<uint32_t>(), 0u);  // packet number
  }
}

TEST_F(AclManagerWithLateLeConnectionTest, receive_acl_in_order) {
  // Send packet #2 from HCI (the first was sent in the test)
  test_hci_layer_->IncomingAclData(handle_);
  auto queue_end = connection_->GetAclQueueEnd();

  std::unique_ptr<PacketView<kLittleEndian>> received;
  do {
    received = queue_end->TryDequeue();
  } while (received == nullptr);

  uint32_t first_packet_number = 0;
  {
    ASSERT_EQ(received->size(), 10u);
    auto itr = received->begin();
    ASSERT_EQ(itr.extract<uint16_t>(), 6u);  // L2CAP PDU size
    ASSERT_EQ(itr.extract<uint16_t>(), 2u);  // L2CAP CID
    ASSERT_EQ(itr.extract<uint16_t>(), handle_);

    first_packet_number = itr.extract<uint32_t>();
  }

  do {
    received = queue_end->TryDequeue();
  } while (received == nullptr);
  {
    ASSERT_EQ(received->size(), 10u);
    auto itr = received->begin();
    ASSERT_EQ(itr.extract<uint16_t>(), 6u);  // L2CAP PDU size
    ASSERT_EQ(itr.extract<uint16_t>(), 2u);  // L2CAP CID
    ASSERT_EQ(itr.extract<uint16_t>(), handle_);
    ASSERT_GT(itr.extract<uint32_t>(), first_packet_number);
  }
}

class AclManagerWithResolvableAddressTest : public AclManagerNoCallbacksTest {
protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);
    ASSERT_NE(client_handler_, nullptr);

    test_hci_layer_ = std::make_unique<HciLayerFake>(client_handler_);
    test_hci_layer_->SetClassicAclDataConsumer(&fake_classic_acl_data_consumer);

    test_controller_ = std::make_unique<TestController>();
    test_storage_ = std::make_unique<storage::StorageModule>(client_handler_);

    test_round_robin_scheduler_ = std::make_unique<RoundRobinScheduler>(
            client_handler_, *test_controller_, test_hci_layer_->GetAclQueueEnd());
    acl_manager_ = std::make_unique<AclManagerLeImpl>(
            client_handler_, *test_hci_layer_, *test_controller_, *test_storage_,
            *test_round_robin_scheduler_, acl_count_provider_mock);

    remote = Address::FromString("A1:A2:A3:A4:A5:A6").value();

    hci::Address address = Address::FromString("D0:05:04:03:02:01").value();
    hci::AddressWithType address_with_type(address, hci::AddressType::RANDOM_DEVICE_ADDRESS);
    acl_manager_->RegisterLeCallbacks(&mock_le_connection_callbacks_, client_handler_);
    auto minimum_rotation_time = std::chrono::milliseconds(7 * 60 * 1000);
    auto maximum_rotation_time = std::chrono::milliseconds(15 * 60 * 1000);
    acl_manager_->SetPrivacyPolicyForInitiatorAddress(
            LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, address_with_type,
            minimum_rotation_time, maximum_rotation_time);

    GetConnectionManagementCommand(OpCode::LE_SET_RANDOM_ADDRESS);
    test_hci_layer_->IncomingEvent(
            LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
};

TEST_F(AclManagerWithResolvableAddressTest, create_connection_cancel_fail) {
  auto remote_with_type_ = AddressWithType(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type_, true, false);

  // Add device to connect list
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  // send create connection command
  GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

  client_handler_->Synchronize(std::chrono::milliseconds(20));
  client_handler_->Synchronize(std::chrono::milliseconds(20));

  Address remote2 = Address::FromString("A1:A2:A3:A4:A5:A7").value();
  auto remote_with_type2 = AddressWithType(remote2, AddressType::PUBLIC_DEVICE_ADDRESS);

  // create another connection
  acl_manager_->CreateLeConnection(remote_with_type2, true, false);

  // cancel previous connection
  GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);

  // receive connection complete of first device
  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x123, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          0x0100, 0x0010, 0x0011, ClockAccuracy::PPM_30));

  // receive create connection cancel complete with ErrorCode::CONNECTION_ALREADY_EXISTS
  test_hci_layer_->IncomingEvent(LeCreateConnectionCancelCompleteBuilder::Create(
          0x01, ErrorCode::CONNECTION_ALREADY_EXISTS));

  // Add another device to connect list
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  // Sync events.
}

class AclManagerLifeCycleTest : public AclManagerNoCallbacksTest {
protected:
  void SetUp() override {
    AclManagerNoCallbacksTest::SetUp();
    acl_manager_->RegisterLeCallbacks(&mock_le_connection_callbacks_, client_handler_);
  }

  AddressWithType remote_with_type_;
  uint16_t handle_{0x123};
};

TEST_F(AclManagerLifeCycleTest, unregister_le_before_connection_complete) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  auto le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto command_view = LeCreateConnectionView::Create(le_connection_management_command_view);
  ASSERT_TRUE(command_view.IsValid());
  if (use_accept_list_) {
    ASSERT_EQ(command_view.GetPeerAddress(), hci::Address::kEmpty);
  } else {
    ASSERT_EQ(command_view.GetPeerAddress(), remote);
  }
  ASSERT_EQ(command_view.GetPeerAddressType(), AddressType::PUBLIC_DEVICE_ADDRESS);

  // Unregister callbacks after sending connection request
  auto promise = std::promise<void>();
  auto future = promise.get_future();
  acl_manager_->UnregisterLeCallbacks(&mock_le_connection_callbacks_, std::move(promise));
  future.get();

  auto connection_future = GetLeConnectionFuture();
  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x123, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          0x0100, 0x0010, 0x0500, ClockAccuracy::PPM_30));

  sync_client_handler();
  auto connection_future_status = connection_future.wait_for(kShortTimeout);
  ASSERT_NE(connection_future_status, std::future_status::ready);
}

TEST_F(AclManagerLifeCycleTest, unregister_le_before_enhanced_connection_complete) {
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  auto le_connection_management_command_view =
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet));
  auto command_view = LeCreateConnectionView::Create(le_connection_management_command_view);
  ASSERT_TRUE(command_view.IsValid());
  if (use_accept_list_) {
    ASSERT_EQ(command_view.GetPeerAddress(), hci::Address::kEmpty);
  } else {
    ASSERT_EQ(command_view.GetPeerAddress(), remote);
  }
  ASSERT_EQ(command_view.GetPeerAddressType(), AddressType::PUBLIC_DEVICE_ADDRESS);

  // Unregister callbacks after sending connection request
  auto promise = std::promise<void>();
  auto future = promise.get_future();
  acl_manager_->UnregisterLeCallbacks(&mock_le_connection_callbacks_, std::move(promise));
  future.get();

  auto connection_future = GetLeConnectionFuture();
  test_hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x123, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          Address::kEmpty, Address::kEmpty, 0x0100, 0x0010, 0x0500, ClockAccuracy::PPM_30));

  sync_client_handler();
  auto connection_future_status = connection_future.wait_for(kShortTimeout);
  ASSERT_NE(connection_future_status, std::future_status::ready);
}

TEST_F(AclManagerTest, acl_packet_dropped_after_timeout) {
  // In this test we send 8 packets(2 at a time) at t=0,300,600,900.
  // At t=1000ms alarm fires and rejects the first 2 packets for new behaviour, but
  // when flag is disabled the alarm would get postponed and will fire at
  // t=2200ms at which test would have completed execution.

  // we fake the time of 1000ms using fake timerfd.

  uint16_t handle = 0x123;
  int total_packets = 8, sent_packets = 0;
  int interval_ms = 300;

  while (sent_packets < total_packets) {
    test_hci_layer_->IncomingAclData(handle);
    test_hci_layer_->IncomingAclData(handle);
    client_handler_->Post(common::BindOnce(fake_timerfd_advance, interval_ms));
    sync_client_handler();
    sent_packets += 2;
  }
  sync_client_handler();

  // Create connection for handle
  AddressWithType remote_with_type(remote, AddressType::PUBLIC_DEVICE_ADDRESS);
  acl_manager_->CreateLeConnection(remote_with_type, true, false);
  GetConnectionManagementCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  auto packet = GetConnectionManagementCommand(OpCode::LE_CREATE_CONNECTION);
  test_hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));

  auto first_connection = GetLeConnectionFuture();
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(remote_with_type, _))
          .WillRepeatedly([this](hci::AddressWithType /* address_with_type */,
                                 std::unique_ptr<LeAclConnection> connection) {
            le_connections_.push_back(std::move(connection));
            if (le_connection_promise_ != nullptr) {
              le_connection_promise_->set_value();
              le_connection_promise_.reset();
            }
          });

  test_hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, handle, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS, remote,
          0x0100, 0x0010, 0x0C80, ClockAccuracy::PPM_30));

  GetConnectionManagementCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  test_hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  auto first_connection_status = first_connection.wait_for(kShortTimeout);
  ASSERT_EQ(first_connection_status, std::future_status::ready);
  auto connection = GetLastLeConnection();
  ASSERT_EQ(connection->GetHandle(), handle);

  // This time all waiting_packets would be sent successfully on retry and here
  // waiting_packets would contain all 8 packets when flag disabled, but when
  // flag enabled 2 packets would be rejected due to firing of alarm at
  // t=1000ms. Whenever sending packet is successful, it gets enqueued into
  // queue here from which we calculate received packets count.
  test_hci_layer_->IncomingAclData(handle);
  sync_client_handler();

  int received_count = 0;
  auto queue_end = connection->GetAclQueueEnd();
  while (queue_end->TryDequeue() != nullptr) {
    received_count++;
  }

  if (com_android_bluetooth_flags_discard_unknown_acl_packet()) {
    ASSERT_EQ(received_count, total_packets - 1);
  } else {
    // No packets are rejected because just alarm is rescheduled as per old behaviour and when after
    // establishing connection, all waiting packets would be sent successfully in retry_unknown_acl.
    ASSERT_EQ(received_count, total_packets + 1);
  }
}

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
