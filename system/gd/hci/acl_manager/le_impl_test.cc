/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "hci/acl_manager/le_impl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include <chrono>
#include <future>

#include "common/bidi_queue.h"
#include "common/le_conn_params.h"
#include "hci/acl_manager/le_connection_callbacks.h"
#include "hci/acl_manager/le_connection_management_callbacks_mock.h"
#include "hci/address_with_type.h"
#include "hci/controller.h"
#include "hci/hci_layer_fake.h"
#include "hci/hci_packets.h"
#include "hci/octets.h"
#include "os/handler.h"
#include "packet/bit_inserter.h"
#include "packet/raw_builder.h"
#include "stack/l2cap/l2c_api.h"

using namespace bluetooth;
using namespace std::chrono_literals;

using ::bluetooth::common::BidiQueue;
using ::bluetooth::common::Callback;
using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;
using ::bluetooth::packet::BitInserter;
using ::bluetooth::packet::RawBuilder;

using ::testing::_;
using ::testing::DoAll;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Mock;
using ::testing::MockFunction;
using ::testing::SaveArg;
using ::testing::VariantWith;
using ::testing::WithArg;

namespace {
constexpr bool kCrashOnUnknownHandle = true;
constexpr char kFixedAddress[] = "c0:aa:bb:cc:dd:ee";
constexpr char kLocalRandomAddress[] = "04:c0:aa:bb:cc:dd:ee";
constexpr char kRemoteRandomAddress[] = "04:11:22:33:44:55";
constexpr char kRemoteAddress[] = "00:11:22:33:44:55";
constexpr uint16_t kHciHandle = 123;
[[maybe_unused]] constexpr bool kAddToFilterAcceptList = true;
[[maybe_unused]] constexpr bool kSkipFilterAcceptList = !kAddToFilterAcceptList;
[[maybe_unused]] constexpr bool kIsDirectConnection = true;
[[maybe_unused]] constexpr bool kIsBackgroundConnection = !kIsDirectConnection;
constexpr hci::Octet16 kRotationIrk = {};
constexpr std::chrono::milliseconds kMinimumRotationTime(14 * 1000);
constexpr std::chrono::milliseconds kMaximumRotationTime(16 * 1000);
constexpr uint16_t kIntervalMax = 0x40;
constexpr uint16_t kIntervalMin = 0x20;
constexpr uint16_t kLatency = 0x60;
constexpr uint16_t kLength = 0x5678;
constexpr uint16_t kTime = 0x1234;
constexpr uint16_t kTimeout = 0x80;
constexpr uint16_t kContinuationNumber = 0x32;
constexpr std::array<uint8_t, 16> kPeerIdentityResolvingKey({
        0x00,
        0x01,
        0x02,
        0x03,
        0x04,
        0x05,
        0x06,
        0x07,
        0x08,
        0x09,
        0x0a,
        0x0b,
        0x0c,
        0x0d,
        0x0e,
        0x0f,
});
constexpr std::array<uint8_t, 16> kLocalIdentityResolvingKey({
        0x80,
        0x81,
        0x82,
        0x83,
        0x84,
        0x85,
        0x86,
        0x87,
        0x88,
        0x89,
        0x8a,
        0x8b,
        0x8c,
        0x8d,
        0x8e,
        0x8f,
});

template <typename B>
std::shared_ptr<std::vector<uint8_t>> Serialize(std::unique_ptr<B> build) {
  auto bytes = std::make_shared<std::vector<uint8_t>>();
  BitInserter bi(*bytes);
  build->Serialize(bi);
  return bytes;
}

template <typename T>
T CreateAclCommandView(hci::CommandView command) {
  return T::Create(hci::AclCommandView::Create(command));
}

template <typename T>
T CreateLeConnectionManagementCommandView(hci::CommandView command) {
  return T::Create(CreateAclCommandView<hci::LeConnectionManagementCommandView>(command));
}

template <typename T>
T CreateLeSecurityCommandView(hci::CommandView command) {
  return T::Create(hci::LeSecurityCommandView::Create(command));
}

template <typename T>
T CreateLeEventView(std::shared_ptr<std::vector<uint8_t>> bytes) {
  return T::Create(hci::LeMetaEventView::Create(
          hci::EventView::Create(hci::PacketView<hci::kLittleEndian>(bytes))));
}

hci::CommandCompleteView ReturnCommandComplete(hci::OpCode op_code, hci::ErrorCode error_code) {
  std::vector<uint8_t> success_vector{static_cast<uint8_t>(error_code)};
  auto builder = hci::CommandCompleteBuilder::Create(uint8_t{1}, op_code,
                                                     std::make_unique<RawBuilder>(success_vector));
  auto bytes = Serialize<hci::CommandCompleteBuilder>(std::move(builder));
  return hci::CommandCompleteView::Create(
          hci::EventView::Create(hci::PacketView<hci::kLittleEndian>(bytes)));
}

hci::CommandStatusView ReturnCommandStatus(hci::OpCode op_code, hci::ErrorCode error_code) {
  std::vector<uint8_t> success_vector{static_cast<uint8_t>(error_code)};
  auto builder = hci::CommandStatusBuilder::Create(hci::ErrorCode::SUCCESS, uint8_t{1}, op_code,
                                                   std::make_unique<RawBuilder>(success_vector));
  auto bytes = Serialize<hci::CommandStatusBuilder>(std::move(builder));
  return hci::CommandStatusView::Create(
          hci::EventView::Create(hci::PacketView<hci::kLittleEndian>(bytes)));
}

}  // namespace

namespace bluetooth {
namespace hci {
namespace acl_manager {

namespace {

class TestController : public Controller {
public:
  bool IsSupported(OpCode op_code) const override {
    log::info("IsSupported");
    return supported_opcodes_.count(op_code) == 1;
  }

  void AddSupported(OpCode op_code) {
    log::info("AddSupported");
    supported_opcodes_.insert(op_code);
  }

  uint16_t GetNumAclPacketBuffers() const { return max_acl_packet_credits_; }

  uint16_t GetAclPacketLength() const { return hci_mtu_; }

  LeBufferSize GetLeBufferSize() const {
    LeBufferSize le_buffer_size;
    le_buffer_size.le_data_packet_length_ = le_hci_mtu_;
    le_buffer_size.total_num_le_packets_ = le_max_acl_packet_credits_;
    return le_buffer_size;
  }

  void RegisterCompletedAclPacketsCallback(CompletedAclPacketsCallback cb) {
    acl_credits_callback_ = cb;
  }

  void SendCompletedAclPacketsCallback(uint16_t handle, uint16_t credits) {
    acl_credits_callback_(handle, credits);
  }

  void UnregisterCompletedAclPacketsCallback() { acl_credits_callback_ = {}; }

  bool SupportsBlePrivacy() const override { return supports_ble_privacy_; }
  bool supports_ble_privacy_{false};

public:
  const uint16_t max_acl_packet_credits_ = 10;
  const uint16_t hci_mtu_ = 1024;
  const uint16_t le_max_acl_packet_credits_ = 15;
  const uint16_t le_hci_mtu_ = 27;

private:
  CompletedAclPacketsCallback acl_credits_callback_;
  std::set<OpCode> supported_opcodes_{};
};

}  // namespace

class MockLeConnectionCallbacks : public LeConnectionCallbacks {
public:
  MOCK_METHOD(void, OnLeConnectSuccess,
              (AddressWithType address_with_type, std::unique_ptr<LeAclConnection> connection),
              (override));
  MOCK_METHOD(void, OnLeConnectFail, (AddressWithType address_with_type, ErrorCode reason),
              (override));
};

class MockLeAcceptlistCallbacks : public LeAcceptlistCallbacks {
public:
  MOCK_METHOD(void, OnLeConnectSuccess, (AddressWithType address), (override));
  MOCK_METHOD(void, OnLeConnectFail, (AddressWithType address, ErrorCode reason), (override));
  MOCK_METHOD(void, OnLeDisconnection, (AddressWithType address), (override));
  MOCK_METHOD(void, OnResolvingListChange, (), (override));
};

class LeImplTest : public ::testing::Test {
protected:
  void SetUp() override {
    __android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);
    thread_ = new Thread("thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    controller_ = new TestController();
    hci_layer_ = new HciLayerFake();

    round_robin_scheduler_ = new RoundRobinScheduler(handler_, controller_, hci_queue_.GetUpEnd());
    hci_queue_.GetDownEnd()->RegisterDequeue(
            handler_, common::Bind(&LeImplTest::HciDownEndDequeue, common::Unretained(this)));

    classic_impl_ = new classic_impl(hci_layer_, controller_, handler_, round_robin_scheduler_,
                                     false, nullptr, nullptr);
    le_impl_ = new le_impl(hci_layer_, controller_, handler_, round_robin_scheduler_,
                           kCrashOnUnknownHandle, classic_impl_);
    le_impl_->handle_register_le_callbacks(&mock_le_connection_callbacks_, handler_);

    Address address;
    Address::FromString(kFixedAddress, address);
    fixed_address_ = AddressWithType(address, AddressType::PUBLIC_DEVICE_ADDRESS);

    Address::FromString(kRemoteAddress, remote_address_);
    remote_public_address_with_type_ =
            AddressWithType(remote_address_, AddressType::PUBLIC_DEVICE_ADDRESS);

    Address::FromString(kLocalRandomAddress, local_rpa_);
    Address::FromString(kRemoteRandomAddress, remote_rpa_);
  }

  void set_random_device_address_policy() {
    // Set address policy
    hci::Address address;
    Address::FromString("D0:05:04:03:02:01", address);
    hci::AddressWithType address_with_type(address, hci::AddressType::RANDOM_DEVICE_ADDRESS);
    Octet16 rotation_irk{};
    auto minimum_rotation_time = std::chrono::milliseconds(7 * 60 * 1000);
    auto maximum_rotation_time = std::chrono::milliseconds(15 * 60 * 1000);
    le_impl_->set_privacy_policy_for_initiator_address(
            LeAddressManager::AddressPolicy::USE_STATIC_ADDRESS, address_with_type, rotation_irk,
            minimum_rotation_time, maximum_rotation_time);
    hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
    hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }

  // Need to store the LeAclConnection so it is not immediately dropped => disconnected
  std::unique_ptr<LeAclConnection> create_enhanced_connection(std::string remote_address_string,
                                                              int handle) {
    std::unique_ptr<LeAclConnection> connection;

    hci::Address remote_address;
    Address::FromString(remote_address_string, remote_address);
    hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);
    le_impl_->create_le_connection(address_with_type, true, false);
    sync_handler();

    hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
    hci_layer_->IncomingEvent(
            LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
    hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION);
    hci_layer_->IncomingEvent(
            LeExtendedCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
    sync_handler();

    // Check state is ARMED
    EXPECT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

    // we need to capture the LeAclConnection so it is not immediately dropped => disconnected
    EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(address_with_type, _))
            .WillOnce([&](AddressWithType, std::unique_ptr<LeAclConnection> conn) {
              connection = std::move(conn);
              connection->RegisterCallbacks(&connection_management_callbacks_, handler_);
            });

    hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
            ErrorCode::SUCCESS, handle, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
            remote_address, Address::kEmpty, Address::kEmpty, 0x0024, 0x0000, 0x0011,
            ClockAccuracy::PPM_30));
    sync_handler();

    hci_layer_->GetCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
    hci_layer_->IncomingEvent(
            LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
    hci_layer_->AssertNoQueuedCommand();
    sync_handler();
    EXPECT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);

    return connection;
  }

  LeExtendedCreateConnectionView get_view_from_creating_connection(
          std::string remote_address_string) {
    hci::Address remote_address;
    Address::FromString(remote_address_string, remote_address);
    hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);

    // Create connection
    le_impl_->create_le_connection(address_with_type, true, false);

    hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
    hci_layer_->IncomingEvent(
            LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
    sync_handler();

    return CreateLeConnectionManagementCommandView<LeExtendedCreateConnectionView>(
            hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION));
  }

  void TearDown() override {
    com::android::bluetooth::flags::provider_->reset_flags();

    // We cannot teardown our structure without unregistering
    // from our own structure we created.
    if (le_impl_->address_manager_registered) {
      le_impl_->ready_to_unregister = true;
      le_impl_->check_for_unregister();
      sync_handler();
    }

    sync_handler();
    delete le_impl_;
    delete classic_impl_;

    hci_queue_.GetDownEnd()->UnregisterDequeue();

    delete hci_layer_;
    delete round_robin_scheduler_;
    delete controller_;

    handler_->Clear();
    delete handler_;
    delete thread_;
  }

  void sync_handler() {
    log::assert_that(thread_ != nullptr, "assert failed: thread_ != nullptr");
    log::assert_that(thread_->GetReactor()->WaitForIdle(2s),
                     "assert failed: thread_->GetReactor()->WaitForIdle(2s)");
  }

  void HciDownEndDequeue() {
    auto packet = hci_queue_.GetDownEnd()->TryDequeue();
    // Convert from a Builder to a View
    auto bytes = std::make_shared<std::vector<uint8_t>>();
    bluetooth::packet::BitInserter i(*bytes);
    bytes->reserve(packet->size());
    packet->Serialize(i);
    auto packet_view = bluetooth::packet::PacketView<bluetooth::packet::kLittleEndian>(bytes);
    AclView acl_packet_view = AclView::Create(packet_view);
    ASSERT_TRUE(acl_packet_view.IsValid());
    PacketView<true> count_view = acl_packet_view.GetPayload();
    sent_acl_packets_.push(acl_packet_view);

    packet_count_--;
    if (packet_count_ == 0) {
      packet_promise_->set_value();
      packet_promise_ = nullptr;
    }
  }

protected:
  void set_privacy_policy_for_initiator_address(const AddressWithType& address,
                                                const LeAddressManager::AddressPolicy& policy) {
    le_impl_->set_privacy_policy_for_initiator_address(policy, address, kRotationIrk,
                                                       kMinimumRotationTime, kMaximumRotationTime);
  }

  Address remote_address_;
  AddressWithType fixed_address_;
  Address local_rpa_;
  Address remote_rpa_;
  AddressWithType remote_public_address_with_type_;

  uint16_t packet_count_;
  std::unique_ptr<std::promise<void>> packet_promise_;
  std::unique_ptr<std::future<void>> packet_future_;
  std::queue<AclView> sent_acl_packets_;

  BidiQueue<AclView, AclBuilder> hci_queue_{3};

  Thread* thread_;
  Handler* handler_;
  HciLayerFake* hci_layer_{nullptr};
  classic_impl* classic_impl_;
  TestController* controller_;
  RoundRobinScheduler* round_robin_scheduler_{nullptr};

  MockLeConnectionCallbacks mock_le_connection_callbacks_;
  MockLeConnectionManagementCallbacks connection_management_callbacks_;

  struct le_impl* le_impl_;
};

class LeImplRegisteredWithAddressManagerTest : public LeImplTest {
protected:
  void SetUp() override {
    LeImplTest::SetUp();
    set_privacy_policy_for_initiator_address(fixed_address_,
                                             LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS);

    le_impl_->register_with_address_manager();
    sync_handler();  // Let |LeAddressManager::register_client| execute on handler
    ASSERT_TRUE(le_impl_->address_manager_registered);
    ASSERT_TRUE(le_impl_->pause_connection);
  }

  void TearDown() override { LeImplTest::TearDown(); }
};

class LeImplWithConnectionTest : public LeImplTest {
protected:
  void SetUp() override {
    LeImplTest::SetUp();
    set_random_device_address_policy();

    EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _))
            .WillOnce([&](AddressWithType addr, std::unique_ptr<LeAclConnection> conn) {
              remote_address_with_type_ = addr;
              connection_ = std::move(conn);
              connection_->RegisterCallbacks(&connection_management_callbacks_, handler_);
            });

    auto command = LeEnhancedConnectionCompleteBuilder::Create(
            ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
            remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011,
            ClockAccuracy::PPM_30);
    auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
    auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
    ASSERT_TRUE(view.IsValid());
    le_impl_->on_le_event(view);

    sync_handler();
    ASSERT_EQ(remote_public_address_with_type_, remote_address_with_type_);
  }

  void TearDown() override {
    connection_.reset();
    LeImplTest::TearDown();
  }

  AddressWithType remote_address_with_type_;
  std::unique_ptr<LeAclConnection> connection_;
};

TEST_F(LeImplTest, add_device_to_accept_list) {
  le_impl_->add_device_to_accept_list(
          {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(1UL, le_impl_->accept_list.size());

  le_impl_->add_device_to_accept_list(
          {{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());

  le_impl_->add_device_to_accept_list(
          {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());

  le_impl_->add_device_to_accept_list(
          {{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());
}

TEST_F(LeImplTest, remove_device_from_accept_list) {
  le_impl_->add_device_to_accept_list(
          {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_accept_list(
          {{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_accept_list(
          {{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->add_device_to_accept_list(
          {{0x31, 0x32, 0x33, 0x34, 0x35, 0x36}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(4UL, le_impl_->accept_list.size());

  le_impl_->remove_device_from_accept_list(
          {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(3UL, le_impl_->accept_list.size());

  le_impl_->remove_device_from_accept_list(
          {{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());

  le_impl_->remove_device_from_accept_list(
          {{0x11, 0x12, 0x13, 0x14, 0x15, 0x16}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());

  le_impl_->remove_device_from_accept_list({Address::kEmpty, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(2UL, le_impl_->accept_list.size());

  le_impl_->remove_device_from_accept_list(
          {{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS});
  le_impl_->remove_device_from_accept_list(
          {{0x31, 0x32, 0x33, 0x34, 0x35, 0x36}, AddressType::PUBLIC_DEVICE_ADDRESS});
  ASSERT_EQ(0UL, le_impl_->accept_list.size());
}

TEST_F(LeImplTest, connection_complete_with_periperal_role) {
  set_random_device_address_policy();

  // Create connection
  le_impl_->create_le_connection(
          {{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS}, true, false);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Receive connection complete of incoming connection (Role::PERIPHERAL)
  hci::Address remote_address;
  Address::FromString("D0:05:04:03:02:01", remote_address);
  hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(address_with_type, _));
  hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x0041, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30));
  sync_handler();

  // Check state is still ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, enhanced_connection_complete_with_periperal_role) {
  set_random_device_address_policy();

  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  // Create connection
  le_impl_->create_le_connection(
          {{0x21, 0x22, 0x23, 0x24, 0x25, 0x26}, AddressType::PUBLIC_DEVICE_ADDRESS}, true, false);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(
          LeExtendedCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Receive connection complete of incoming connection (Role::PERIPHERAL)
  hci::Address remote_address;
  Address::FromString("D0:05:04:03:02:01", remote_address);
  hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(address_with_type, _));
  hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x0041, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address, Address::kEmpty, Address::kEmpty, 0x0024, 0x0000, 0x0011,
          ClockAccuracy::PPM_30));
  sync_handler();

  // Check state is still ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, connection_complete_with_central_role) {
  set_random_device_address_policy();

  hci::Address remote_address;
  Address::FromString("D0:05:04:03:02:01", remote_address);
  hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);
  // Create connection
  le_impl_->create_le_connection(address_with_type, true, false);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Receive connection complete of outgoing connection (Role::CENTRAL)
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(address_with_type, _));
  hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x0041, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30));
  sync_handler();

  // Check state is DISARMED
  ASSERT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, enhanced_connection_complete_with_central_role) {
  set_random_device_address_policy();

  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  hci::Address remote_address;
  Address::FromString("D0:05:04:03:02:01", remote_address);
  hci::AddressWithType address_with_type(remote_address, hci::AddressType::PUBLIC_DEVICE_ADDRESS);
  // Create connection
  le_impl_->create_le_connection(address_with_type, true, false);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(
          LeExtendedCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Receive connection complete of outgoing connection (Role::CENTRAL)
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(address_with_type, _));
  hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x0041, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address, Address::kEmpty, Address::kEmpty, 0x0024, 0x0000, 0x0011,
          ClockAccuracy::PPM_30));
  sync_handler();

  // Check state is DISARMED
  ASSERT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, aggressive_connection_mode_selected_when_no_ongoing_le_connections_exist) {
  if (LeConnectionParameters::GetAggressiveConnThreshold() == 0) {
    GTEST_SKIP() << "Skipping test because the threshold is zero";
  }

  com::android::bluetooth::flags::provider_->initial_conn_params_p1(true);
  set_random_device_address_policy();
  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);

  LeExtendedCreateConnectionView view = get_view_from_creating_connection("FF:EE:DD:CC:BB:AA");

  ASSERT_TRUE(view.IsValid());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_min_,
            LeConnectionParameters::GetMinConnIntervalAggressive());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_max_,
            LeConnectionParameters::GetMaxConnIntervalAggressive());
}

TEST_F(LeImplTest, aggressive_connection_mode_selected_when_few_le_connections_exist) {
  if (LeConnectionParameters::GetAggressiveConnThreshold() == 0) {
    GTEST_SKIP() << "Skipping test because the threshold is zero";
  }

  com::android::bluetooth::flags::provider_->initial_conn_params_p1(true);
  set_random_device_address_policy();
  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);

  std::vector<std::unique_ptr<LeAclConnection>> connections;
  for (uint32_t i = 0; i < LeConnectionParameters::GetAggressiveConnThreshold() - 1; i++) {
    std::stringstream addr_string_stream;
    addr_string_stream << "A0:05:04:03:02:" << std::hex << std::setw(2) << std::setfill('0') << i;

    connections.push_back(create_enhanced_connection(addr_string_stream.str(), i /* handle */));
  }

  LeExtendedCreateConnectionView view = get_view_from_creating_connection("FF:EE:DD:CC:BB:AA");

  ASSERT_TRUE(view.IsValid());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_min_,
            LeConnectionParameters::GetMinConnIntervalAggressive());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_max_,
            LeConnectionParameters::GetMaxConnIntervalAggressive());
}

TEST_F(LeImplTest, relaxed_connection_mode_selected_when_enough_le_connections_exist) {
  com::android::bluetooth::flags::provider_->initial_conn_params_p1(true);
  set_random_device_address_policy();
  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);

  std::vector<std::unique_ptr<LeAclConnection>> connections;
  for (uint32_t i = 0; i < LeConnectionParameters::GetAggressiveConnThreshold(); i++) {
    std::stringstream addr_string_stream;
    addr_string_stream << "A0:05:04:03:02:" << std::hex << std::setw(2) << std::setfill('0') << i;

    connections.push_back(create_enhanced_connection(addr_string_stream.str(), i /* handle */));
  }

  LeExtendedCreateConnectionView view = get_view_from_creating_connection("FF:EE:DD:CC:BB:AA");

  ASSERT_TRUE(view.IsValid());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_min_,
            LeConnectionParameters::GetMinConnIntervalRelaxed());
  ASSERT_EQ(view.GetPhyScanParameters()[0].conn_interval_max_,
            LeConnectionParameters::GetMaxConnIntervalRelaxed());
}

// b/260917913
TEST_F(LeImplTest, DISABLED_register_with_address_manager__AddressPolicyNotSet) {
  std::promise<void> promise;
  auto future = promise.get_future();
  handler_->Post(common::BindOnce(
          [](struct le_impl* le_impl, os::Handler* handler, std::promise<void> promise) {
            le_impl->register_with_address_manager();
            handler->Post(common::BindOnce([](std::promise<void> promise) { promise.set_value(); },
                                           std::move(promise)));
          },
          le_impl_, handler_, std::move(promise)));

  // Let |LeAddressManager::register_client| execute on handler
  auto status = future.wait_for(2s);
  ASSERT_EQ(status, std::future_status::ready);

  handler_->Post(common::BindOnce(
          [](struct le_impl* le_impl) {
            ASSERT_TRUE(le_impl->address_manager_registered);
            ASSERT_TRUE(le_impl->pause_connection);
          },
          le_impl_));

  std::promise<void> promise2;
  auto future2 = promise2.get_future();
  handler_->Post(common::BindOnce(
          [](struct le_impl* le_impl, os::Handler* handler, std::promise<void> promise) {
            le_impl->ready_to_unregister = true;
            le_impl->check_for_unregister();
            ASSERT_FALSE(le_impl->address_manager_registered);
            ASSERT_FALSE(le_impl->pause_connection);
            handler->Post(common::BindOnce([](std::promise<void> promise) { promise.set_value(); },
                                           std::move(promise)));
          },
          le_impl_, handler_, std::move(promise2)));

  // Let |LeAddressManager::unregister_client| execute on handler
  auto status2 = future2.wait_for(2s);
  ASSERT_EQ(status2, std::future_status::ready);
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_DISARMED) {
  le_impl_->connectability_state_ = ConnectabilityState::DISARMED;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_create_connection(
          ReturnCommandStatus(OpCode::LE_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_DISARMED_extended) {
  le_impl_->connectability_state_ = ConnectabilityState::DISARMED;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_extended_create_connection(
          ReturnCommandStatus(OpCode::LE_EXTENDED_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_ARMING) {
  le_impl_->connectability_state_ = ConnectabilityState::ARMING;
  le_impl_->disarm_connectability();
  ASSERT_TRUE(le_impl_->disarmed_while_arming_);
  le_impl_->on_create_connection(
          ReturnCommandStatus(OpCode::LE_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_ARMING_extended) {
  le_impl_->connectability_state_ = ConnectabilityState::ARMING;
  le_impl_->disarm_connectability();
  ASSERT_TRUE(le_impl_->disarmed_while_arming_);

  le_impl_->on_extended_create_connection(
          ReturnCommandStatus(OpCode::LE_EXTENDED_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_ARMED) {
  le_impl_->connectability_state_ = ConnectabilityState::ARMED;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_create_connection(
          ReturnCommandStatus(OpCode::LE_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_ARMED_extended) {
  le_impl_->connectability_state_ = ConnectabilityState::ARMED;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_extended_create_connection(
          ReturnCommandStatus(OpCode::LE_EXTENDED_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_DISARMING) {
  le_impl_->connectability_state_ = ConnectabilityState::DISARMING;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_create_connection(
          ReturnCommandStatus(OpCode::LE_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_disarm_connectability_DISARMING_extended) {
  le_impl_->connectability_state_ = ConnectabilityState::DISARMING;
  le_impl_->disarm_connectability();
  ASSERT_FALSE(le_impl_->disarmed_while_arming_);

  le_impl_->on_extended_create_connection(
          ReturnCommandStatus(OpCode::LE_EXTENDED_CREATE_CONNECTION, ErrorCode::SUCCESS));
}

// b/260917913
TEST_F(LeImplTest, DISABLED_register_with_address_manager__AddressPolicyPublicAddress) {
  set_privacy_policy_for_initiator_address(fixed_address_,
                                           LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS);

  le_impl_->register_with_address_manager();
  sync_handler();  // Let |eAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();  // Let |LeAddressManager::unregister_client| execute on handler
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

// b/260917913
TEST_F(LeImplTest, DISABLED_register_with_address_manager__AddressPolicyStaticAddress) {
  set_privacy_policy_for_initiator_address(fixed_address_,
                                           LeAddressManager::AddressPolicy::USE_STATIC_ADDRESS);

  le_impl_->register_with_address_manager();
  sync_handler();  // Let |LeAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();  // Let |LeAddressManager::unregister_client| execute on handler
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

// b/260917913
TEST_F(LeImplTest, DISABLED_register_with_address_manager__AddressPolicyNonResolvableAddress) {
  set_privacy_policy_for_initiator_address(
          fixed_address_, LeAddressManager::AddressPolicy::USE_NON_RESOLVABLE_ADDRESS);

  le_impl_->register_with_address_manager();
  sync_handler();  // Let |LeAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();  // Let |LeAddressManager::unregister_client| execute on handler
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

// b/260917913
TEST_F(LeImplTest, DISABLED_register_with_address_manager__AddressPolicyResolvableAddress) {
  set_privacy_policy_for_initiator_address(fixed_address_,
                                           LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS);

  le_impl_->register_with_address_manager();
  sync_handler();  // Let |LeAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();  // Let |LeAddressManager::unregister_client| execute on handler
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

// b/260920739
TEST_F(LeImplTest, DISABLED_add_device_to_resolving_list) {
  // Some kind of privacy policy must be set for LeAddressManager to operate properly
  set_privacy_policy_for_initiator_address(fixed_address_,
                                           LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS);
  // Let LeAddressManager::resume_registered_clients execute
  sync_handler();

  hci_layer_->AssertNoQueuedCommand();

  // le_impl should not be registered with address manager
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);

  ASSERT_EQ(0UL, le_impl_->le_address_manager_->NumberCachedCommands());
  // Acknowledge that the le_impl has quiesced all relevant controller state
  le_impl_->add_device_to_resolving_list(remote_public_address_with_type_,
                                         kPeerIdentityResolvingKey, kLocalIdentityResolvingKey);
  ASSERT_EQ(3UL, le_impl_->le_address_manager_->NumberCachedCommands());

  sync_handler();  // Let |LeAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->le_address_manager_->AckPause(le_impl_);
  sync_handler();  // Allow |LeAddressManager::ack_pause| to complete

  {
    // Inform controller to disable address resolution
    auto command =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(Enable::DISABLED, command.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  {
    auto command =
            CreateLeSecurityCommandView<LeAddDeviceToResolvingListView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS,
              command.GetPeerIdentityAddressType());
    ASSERT_EQ(remote_public_address_with_type_.GetAddress(), command.GetPeerIdentityAddress());
    ASSERT_EQ(kPeerIdentityResolvingKey, command.GetPeerIrk());
    ASSERT_EQ(kLocalIdentityResolvingKey, command.GetLocalIrk());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  {
    auto command =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(Enable::ENABLED, command.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  hci_layer_->AssertNoQueuedCommand();
  ASSERT_TRUE(le_impl_->address_manager_registered);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

TEST_F(LeImplTest, add_device_to_resolving_list__SupportsBlePrivacy) {
  controller_->supports_ble_privacy_ = true;

  // Some kind of privacy policy must be set for LeAddressManager to operate properly
  set_privacy_policy_for_initiator_address(fixed_address_,
                                           LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS);
  // Let LeAddressManager::resume_registered_clients execute
  sync_handler();

  hci_layer_->AssertNoQueuedCommand();

  // le_impl should not be registered with address manager
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);

  ASSERT_EQ(0UL, le_impl_->le_address_manager_->NumberCachedCommands());
  // Acknowledge that the le_impl has quiesced all relevant controller state
  le_impl_->add_device_to_resolving_list(remote_public_address_with_type_,
                                         kPeerIdentityResolvingKey, kLocalIdentityResolvingKey);
  ASSERT_EQ(4UL, le_impl_->le_address_manager_->NumberCachedCommands());

  sync_handler();  // Let |LeAddressManager::register_client| execute on handler
  ASSERT_TRUE(le_impl_->address_manager_registered);
  ASSERT_TRUE(le_impl_->pause_connection);

  le_impl_->le_address_manager_->AckPause(le_impl_);
  sync_handler();  // Allow |LeAddressManager::ack_pause| to complete

  {
    // Inform controller to disable address resolution
    auto command =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(Enable::DISABLED, command.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  {
    auto command =
            CreateLeSecurityCommandView<LeAddDeviceToResolvingListView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS,
              command.GetPeerIdentityAddressType());
    ASSERT_EQ(remote_public_address_with_type_.GetAddress(), command.GetPeerIdentityAddress());
    ASSERT_EQ(kPeerIdentityResolvingKey, command.GetPeerIrk());
    ASSERT_EQ(kLocalIdentityResolvingKey, command.GetLocalIrk());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  {
    auto command = CreateLeSecurityCommandView<LeSetPrivacyModeView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(PrivacyMode::DEVICE, command.GetPrivacyMode());
    ASSERT_EQ(remote_public_address_with_type_.GetAddress(), command.GetPeerIdentityAddress());
    ASSERT_EQ(PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS,
              command.GetPeerIdentityAddressType());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_PRIVACY_MODE, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  {
    auto command =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(command.IsValid());
    ASSERT_EQ(Enable::ENABLED, command.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }
  sync_handler();  // |LeAddressManager::check_cached_commands|

  ASSERT_TRUE(le_impl_->address_manager_registered);

  le_impl_->ready_to_unregister = true;

  le_impl_->check_for_unregister();
  sync_handler();
  ASSERT_FALSE(le_impl_->address_manager_registered);
  ASSERT_FALSE(le_impl_->pause_connection);
}

TEST_F(LeImplTest, connectability_state_machine_text) {
  ASSERT_STREQ("ConnectabilityState::DISARMED",
               connectability_state_machine_text(ConnectabilityState::DISARMED).c_str());
  ASSERT_STREQ("ConnectabilityState::ARMING",
               connectability_state_machine_text(ConnectabilityState::ARMING).c_str());
  ASSERT_STREQ("ConnectabilityState::ARMED",
               connectability_state_machine_text(ConnectabilityState::ARMED).c_str());
  ASSERT_STREQ("ConnectabilityState::DISARMING",
               connectability_state_machine_text(ConnectabilityState::DISARMING).c_str());
}

TEST_F(LeImplTest, on_le_event__CONNECTION_COMPLETE_CENTRAL) {
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _)).Times(1);
  set_random_device_address_policy();
  auto command = LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
}

TEST_F(LeImplTest, on_le_event__CONNECTION_COMPLETE_PERIPHERAL) {
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _)).Times(1);
  set_random_device_address_policy();
  auto command = LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
}

TEST_F(LeImplTest, on_le_event__ENHANCED_CONNECTION_COMPLETE_CENTRAL) {
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _)).Times(1);
  set_random_device_address_policy();
  auto command = LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
}

TEST_F(LeImplTest, on_le_event__ENHANCED_CONNECTION_COMPLETE_PERIPHERAL) {
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _)).Times(1);
  set_random_device_address_policy();
  auto command = LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
}

TEST_F(LeImplWithConnectionTest, on_le_event__PHY_UPDATE_COMPLETE) {
  hci::ErrorCode hci_status{ErrorCode::STATUS_UNKNOWN};
  hci::PhyType tx_phy{0};
  hci::PhyType rx_phy{0};

  // Send a phy update
  {
    EXPECT_CALL(connection_management_callbacks_, OnPhyUpdate(_, _, _))
            .WillOnce([&](hci::ErrorCode _hci_status, uint8_t _tx_phy, uint8_t _rx_phy) {
              hci_status = _hci_status;
              tx_phy = static_cast<PhyType>(_tx_phy);
              rx_phy = static_cast<PhyType>(_rx_phy);
            });
    auto command = LePhyUpdateCompleteBuilder::Create(ErrorCode::SUCCESS, kHciHandle, 0x01, 0x02);
    auto bytes = Serialize<LePhyUpdateCompleteBuilder>(std::move(command));
    auto view = CreateLeEventView<hci::LePhyUpdateCompleteView>(bytes);
    ASSERT_TRUE(view.IsValid());
    le_impl_->on_le_event(view);
  }

  sync_handler();
  ASSERT_EQ(ErrorCode::SUCCESS, hci_status);
  ASSERT_EQ(PhyType::LE_1M, tx_phy);
  ASSERT_EQ(PhyType::LE_2M, rx_phy);
}

TEST_F(LeImplWithConnectionTest, on_le_event__SUBRATE_CHANGE_EVENT) {
  // Send a subrate event
  EXPECT_CALL(connection_management_callbacks_,
              OnLeSubrateChange(ErrorCode::SUCCESS, 0x01, 0x02, 0x03, 0x04));
  auto command =
          LeSubrateChangeBuilder::Create(ErrorCode::SUCCESS, kHciHandle, 0x01, 0x02, 0x03, 0x04);
  auto bytes = Serialize<LeSubrateChangeBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeSubrateChangeView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);

  sync_handler();
}

TEST_F(LeImplWithConnectionTest, on_le_event__DATA_LENGTH_CHANGE) {
  uint16_t tx_octets{0};
  uint16_t tx_time{0};
  uint16_t rx_octets{0};
  uint16_t rx_time{0};

  // Send a data length event
  {
    EXPECT_CALL(connection_management_callbacks_, OnDataLengthChange(_, _, _, _))
            .WillOnce([&](uint16_t _tx_octets, uint16_t _tx_time, uint16_t _rx_octets,
                          uint16_t _rx_time) {
              tx_octets = _tx_octets;
              tx_time = _tx_time;
              rx_octets = _rx_octets;
              rx_time = _rx_time;
            });
    auto command = LeDataLengthChangeBuilder::Create(kHciHandle, 0x1234, 0x5678, 0x9abc, 0xdef0);
    auto bytes = Serialize<LeDataLengthChangeBuilder>(std::move(command));
    auto view = CreateLeEventView<hci::LeDataLengthChangeView>(bytes);
    ASSERT_TRUE(view.IsValid());
    le_impl_->on_le_event(view);
  }

  sync_handler();
  ASSERT_EQ(0x1234, tx_octets);
  ASSERT_EQ(0x5678, tx_time);
  ASSERT_EQ(0x9abc, rx_octets);
  ASSERT_EQ(0xdef0, rx_time);
}

TEST_F(LeImplWithConnectionTest, on_le_event__REMOTE_CONNECTION_PARAMETER_REQUEST) {
  std::promise<void> request_promise;
  auto request = request_promise.get_future();
  EXPECT_CALL(connection_management_callbacks_,
              OnParameterUpdateRequest(kIntervalMin, kIntervalMax, kLatency, kTimeout))
          .WillOnce([&request_promise]() { request_promise.set_value(); });

  // Send a remote connection parameter request
  auto command = hci::LeRemoteConnectionParameterRequestBuilder::Create(
          kHciHandle, kIntervalMin, kIntervalMax, kLatency, kTimeout);
  auto bytes = Serialize<LeRemoteConnectionParameterRequestBuilder>(std::move(command));
  {
    auto view = CreateLeEventView<hci::LeRemoteConnectionParameterRequestView>(bytes);
    ASSERT_TRUE(view.IsValid());
    le_impl_->on_le_event(view);
  }

  ASSERT_EQ(std::future_status::ready, request.wait_for(std::chrono::seconds(1)));
}

// b/260920739
TEST_F(LeImplRegisteredWithAddressManagerTest, DISABLED_clear_resolving_list) {
  le_impl_->clear_resolving_list();
  ASSERT_EQ(3UL, le_impl_->le_address_manager_->NumberCachedCommands());

  sync_handler();  // Allow |LeAddressManager::pause_registered_clients| to complete
  sync_handler();  // Allow |LeAddressManager::handle_next_command| to complete

  {
    auto view =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(view.IsValid());
    ASSERT_EQ(Enable::DISABLED, view.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }

  sync_handler();  // Allow |LeAddressManager::check_cached_commands| to complete
  {
    auto view = CreateLeSecurityCommandView<LeClearResolvingListView>(hci_layer_->GetCommand());
    ASSERT_TRUE(view.IsValid());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_CLEAR_RESOLVING_LIST, ErrorCode::SUCCESS));
  }

  sync_handler();  // Allow |LeAddressManager::handle_next_command| to complete
  {
    auto view =
            CreateLeSecurityCommandView<LeSetAddressResolutionEnableView>(hci_layer_->GetCommand());
    ASSERT_TRUE(view.IsValid());
    ASSERT_EQ(Enable::ENABLED, view.GetAddressResolutionEnable());
    le_impl_->le_address_manager_->OnCommandComplete(
            ReturnCommandComplete(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE, ErrorCode::SUCCESS));
  }
  hci_layer_->AssertNoQueuedCommand();
}

TEST_F(LeImplRegisteredWithAddressManagerTest, ignore_on_pause_on_resume_after_unregistered) {
  le_impl_->ready_to_unregister = true;
  le_impl_->check_for_unregister();
  // OnPause should be ignored
  le_impl_->OnPause();
  ASSERT_FALSE(le_impl_->pause_connection);
  // OnResume should be ignored
  le_impl_->pause_connection = true;
  le_impl_->OnResume();
  ASSERT_TRUE(le_impl_->pause_connection);
}

TEST_F(LeImplWithConnectionTest, HACK_get_handle) {
  sync_handler();

  ASSERT_EQ(kHciHandle, le_impl_->HACK_get_handle(remote_address_));
}

TEST_F(LeImplTest, on_le_connection_canceled_on_pause) {
  set_random_device_address_policy();
  le_impl_->pause_connection = true;
  le_impl_->on_le_connection_canceled_on_pause();
  ASSERT_TRUE(le_impl_->arm_on_resume_);
  ASSERT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, on_create_connection_timeout) {
  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectFail(_, ErrorCode::CONNECTION_ACCEPT_TIMEOUT))
          .Times(1);
  le_impl_->create_connection_timeout_alarms_.emplace(
          std::piecewise_construct,
          std::forward_as_tuple(remote_public_address_with_type_.GetAddress(),
                                remote_public_address_with_type_.GetAddressType()),
          std::forward_as_tuple(handler_));
  le_impl_->on_create_connection_timeout(remote_public_address_with_type_);
  sync_handler();
  ASSERT_TRUE(le_impl_->create_connection_timeout_alarms_.empty());
}

// b/260917913
TEST_F(LeImplTest, DISABLED_on_common_le_connection_complete__NoPriorConnection) {
  le_impl_->on_common_le_connection_complete(remote_public_address_with_type_);
  ASSERT_TRUE(le_impl_->connecting_le_.empty());
}

TEST_F(LeImplTest, cancel_connect) {
  le_impl_->create_connection_timeout_alarms_.emplace(
          std::piecewise_construct,
          std::forward_as_tuple(remote_public_address_with_type_.GetAddress(),
                                remote_public_address_with_type_.GetAddressType()),
          std::forward_as_tuple(handler_));
  le_impl_->cancel_connect(remote_public_address_with_type_);
  sync_handler();
  ASSERT_TRUE(le_impl_->create_connection_timeout_alarms_.empty());
}

enum class ConnectionCompleteType { CONNECTION_COMPLETE, ENHANCED_CONNECTION_COMPLETE };

class LeImplTestParameterizedByConnectionCompleteEventType
    : public LeImplTest,
      public ::testing::WithParamInterface<ConnectionCompleteType> {};

TEST_P(LeImplTestParameterizedByConnectionCompleteEventType,
       ConnectionCompleteAsPeripheralWithAdvertisingSet) {
  // arrange
  controller_->AddSupported(hci::OpCode::LE_MULTI_ADVT);
  set_random_device_address_policy();

  auto advertising_set_id = 13;

  hci::Address advertiser_address;
  Address::FromString("A0:A1:A2:A3:A4:A5", advertiser_address);
  hci::AddressWithType advertiser_address_with_type(advertiser_address,
                                                    hci::AddressType::PUBLIC_DEVICE_ADDRESS);

  // expect
  ::testing::InSequence s;
  MockFunction<void(std::string check_point_name)> check;
  std::unique_ptr<LeAclConnection> connection{};
  EXPECT_CALL(check, Call("terminating_advertising_set"));
  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectSuccess(remote_public_address_with_type_, _))
          .WillOnce(WithArg<1>(::testing::Invoke(
                  [&](std::unique_ptr<LeAclConnection> conn) { connection = std::move(conn); })));

  // act
  switch (GetParam()) {
    case ConnectionCompleteType::CONNECTION_COMPLETE: {
      hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
              ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
              remote_address_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30));
    } break;
    case ConnectionCompleteType::ENHANCED_CONNECTION_COMPLETE: {
      hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
              ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
              remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011,
              ClockAccuracy::PPM_30));
    } break;
    default: {
      log::fatal("unexpected case");
    }
  }
  sync_handler();

  check.Call("terminating_advertising_set");
  le_impl_->OnAdvertisingSetTerminated(kHciHandle, advertising_set_id, advertiser_address_with_type,
                                       false /* is_discoverable */);
  sync_handler();

  // assert
  Mock::VerifyAndClearExpectations(&mock_le_connection_callbacks_);
  ASSERT_NE(connection, nullptr);
  EXPECT_THAT(connection->GetRoleSpecificData(),
              VariantWith<DataAsPeripheral>(Field("local_address", &DataAsPeripheral::local_address,
                                                  Eq(advertiser_address_with_type))));
}

INSTANTIATE_TEST_SUITE_P(ConnectionCompleteAsPeripheralWithAdvertisingSet,
                         LeImplTestParameterizedByConnectionCompleteEventType,
                         ::testing::Values(ConnectionCompleteType::CONNECTION_COMPLETE,
                                           ConnectionCompleteType::ENHANCED_CONNECTION_COMPLETE));

class LeImplTestParameterizedByDiscoverability : public LeImplTest,
                                                 public ::testing::WithParamInterface<bool> {};

TEST_P(LeImplTestParameterizedByDiscoverability, ConnectionCompleteAsDiscoverable) {
  // arrange
  controller_->AddSupported(hci::OpCode::LE_MULTI_ADVT);
  set_random_device_address_policy();
  auto is_discoverable = GetParam();

  // expect
  std::unique_ptr<LeAclConnection> connection{};
  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectSuccess(remote_public_address_with_type_, _))
          .WillOnce(WithArg<1>(::testing::Invoke(
                  [&](std::unique_ptr<LeAclConnection> conn) { connection = std::move(conn); })));

  // act
  hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30));
  // the sync is needed since otherwise the OnAdvertisingSetTerminated() event arrives first, due to
  // handler indirection (2 hops vs 1 hop) this isn't a bug in production since there we'd have:
  // 1. Connection Complete: HCI -> LE_IMPL (2 hops)
  // 2. Advertising Set Terminated: HCI -> ADV -> LE_IMPL (3 hops)
  // so this sync is only needed in test
  sync_handler();
  le_impl_->OnAdvertisingSetTerminated(kHciHandle, 1 /* advertiser_set_id */, fixed_address_,
                                       is_discoverable);
  sync_handler();

  // assert
  ASSERT_NE(connection, nullptr);
  EXPECT_THAT(connection->GetRoleSpecificData(),
              VariantWith<DataAsPeripheral>(Field("connected_to_discoverable",
                                                  &DataAsPeripheral::connected_to_discoverable,
                                                  Eq(is_discoverable))));
}

INSTANTIATE_TEST_SUITE_P(LeImplTestParameterizedByDiscoverability,
                         LeImplTestParameterizedByDiscoverability, ::testing::Values(false, true));

TEST_F(LeImplTest, ConnectionCompleteAcceptlistCallback) {
  // arrange
  MockLeAcceptlistCallbacks callbacks;
  le_impl_->handle_register_le_acceptlist_callbacks(&callbacks);
  set_random_device_address_policy();

  // expect
  AddressWithType remote_address;
  EXPECT_CALL(callbacks, OnLeConnectSuccess(_)).WillOnce([&](AddressWithType addr) {
    remote_address = addr;
  });

  // act
  auto command = LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
  sync_handler();

  // assert
  ASSERT_EQ(remote_public_address_with_type_, remote_address);
}

TEST_F(LeImplTest, ResolvingListCallback) {
  // arrange
  MockLeAcceptlistCallbacks callbacks;
  le_impl_->handle_register_le_acceptlist_callbacks(&callbacks);

  // expect
  AddressWithType remote_address;
  EXPECT_CALL(callbacks, OnResolvingListChange()).Times(1);

  // act
  le_impl_->add_device_to_resolving_list(remote_public_address_with_type_,
                                         kPeerIdentityResolvingKey, kLocalIdentityResolvingKey);

  // assert
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(LeImplTest, ConnectionFailedAcceptlistCallback) {
  // arrange
  MockLeAcceptlistCallbacks callbacks;
  le_impl_->handle_register_le_acceptlist_callbacks(&callbacks);
  set_random_device_address_policy();

  // expect
  AddressWithType remote_address;
  ErrorCode reason;
  EXPECT_CALL(callbacks, OnLeConnectFail(_, _))
          .WillOnce([&](AddressWithType addr, ErrorCode error) {
            remote_address = addr;
            reason = error;
          });

  // act
  auto command = LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::CONTROLLER_BUSY, kHciHandle, Role::PERIPHERAL,
          AddressType::PUBLIC_DEVICE_ADDRESS, remote_address_, local_rpa_, remote_rpa_, 0x0024,
          0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
  sync_handler();

  // assert
  EXPECT_EQ(remote_address, remote_public_address_with_type_);
  EXPECT_EQ(reason, ErrorCode::CONTROLLER_BUSY);
}

TEST_F(LeImplTest, DisconnectionAcceptlistCallback) {
  // expect
  MockLeAcceptlistCallbacks callbacks;
  AddressWithType remote_address;
  EXPECT_CALL(callbacks, OnLeDisconnection(_)).WillOnce([&](AddressWithType addr) {
    remote_address = addr;
  });
  // we need to capture the LeAclConnection so it is not immediately dropped => disconnected
  std::unique_ptr<LeAclConnection> connection;
  EXPECT_CALL(mock_le_connection_callbacks_, OnLeConnectSuccess(_, _))
          .WillOnce([&](AddressWithType, std::unique_ptr<LeAclConnection> conn) {
            connection = std::move(conn);
            connection->RegisterCallbacks(&connection_management_callbacks_, handler_);
          });

  // arrange: an active connection to a peer
  le_impl_->handle_register_le_acceptlist_callbacks(&callbacks);
  set_random_device_address_policy();
  auto command = LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::PERIPHERAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30);
  auto bytes = Serialize<LeEnhancedConnectionCompleteBuilder>(std::move(command));
  auto view = CreateLeEventView<hci::LeEnhancedConnectionCompleteView>(bytes);
  ASSERT_TRUE(view.IsValid());
  le_impl_->on_le_event(view);
  sync_handler();

  // act
  le_impl_->on_le_disconnect(kHciHandle, ErrorCode::REMOTE_USER_TERMINATED_CONNECTION);
  sync_handler();

  // assert
  EXPECT_EQ(remote_public_address_with_type_, remote_address);
  Mock::VerifyAndClearExpectations(&callbacks);
}

TEST_F(LeImplTest, direct_connection_after_background_connection) {
  set_random_device_address_policy();

  hci::AddressWithType address({0x21, 0x22, 0x23, 0x24, 0x25, 0x26},
                               AddressType::PUBLIC_DEVICE_ADDRESS);

  // arrange: Create background connection. Remember that acl_manager adds device background list
  le_impl_->add_device_to_background_connection_list(address);
  le_impl_->create_le_connection(address, true, /* is_direct */ false);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto raw_bg_create_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // act: Create direct connection
  le_impl_->create_le_connection(address, true, /* is_direct */ true);
  auto cancel_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);
  if (cancel_connection.IsValid()) {
    hci_layer_->IncomingEvent(
            LeCreateConnectionCancelCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
    hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
            ErrorCode::UNKNOWN_CONNECTION, kHciHandle, Role::CENTRAL,
            AddressType::PUBLIC_DEVICE_ADDRESS, Address::kEmpty, 0x0000, 0x0000, 0x0000,
            ClockAccuracy::PPM_30));
  }
  auto raw_direct_create_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);

  // assert
  auto bg_create_connection =
          LeCreateConnectionView::Create(LeConnectionManagementCommandView::Create(
                  AclCommandView::Create(raw_bg_create_connection)));
  EXPECT_TRUE(bg_create_connection.IsValid());
  auto direct_create_connection =
          LeCreateConnectionView::Create(LeConnectionManagementCommandView::Create(
                  AclCommandView::Create(raw_direct_create_connection)));
  EXPECT_TRUE(direct_create_connection.IsValid());
  log::info("Scan Interval {}", direct_create_connection.GetLeScanInterval());
  ASSERT_NE(direct_create_connection.GetLeScanInterval(), bg_create_connection.GetLeScanInterval());

  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Simulate timeout on direct connect. Verify background connect is still in place
  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectFail(_, ErrorCode::CONNECTION_ACCEPT_TIMEOUT))
          .Times(1);
  le_impl_->on_create_connection_timeout(address);
  sync_handler();
  cancel_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);
  hci_layer_->IncomingEvent(
          LeCreateConnectionCancelCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::UNKNOWN_CONNECTION, kHciHandle, Role::CENTRAL,
          AddressType::PUBLIC_DEVICE_ADDRESS, Address::kEmpty, 0x0000, 0x0000, 0x0000,
          ClockAccuracy::PPM_30));
  EXPECT_TRUE(cancel_connection.IsValid());
  raw_bg_create_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);
  bg_create_connection = LeCreateConnectionView::Create(LeConnectionManagementCommandView::Create(
          AclCommandView::Create(raw_bg_create_connection)));
  EXPECT_TRUE(bg_create_connection.IsValid());
  sync_handler();
  ASSERT_TRUE(le_impl_->create_connection_timeout_alarms_.empty());

  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, direct_connection_after_direct_connection) {
  set_random_device_address_policy();

  hci::AddressWithType address({0x21, 0x22, 0x23, 0x24, 0x25, 0x26},
                               AddressType::PUBLIC_DEVICE_ADDRESS);

  // Create first direct connection
  le_impl_->create_le_connection(address, true, /* is_direct */ true);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  auto raw_direct_1_create_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(LeCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();

  // Check state is ARMED
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // assert
  auto direct_1_create_connection =
          LeCreateConnectionView::Create(LeConnectionManagementCommandView::Create(
                  AclCommandView::Create(raw_direct_1_create_connection)));
  EXPECT_TRUE(direct_1_create_connection.IsValid());

  log::info("Second direct connect to the same device");

  // Create second direct connection
  le_impl_->create_le_connection(address, true, /* is_direct */ true);
  sync_handler();

  CommandView cancel_connection = CommandView::Create(
          PacketView<packet::kLittleEndian>(std::make_shared<std::vector<uint8_t>>()));

  hci_layer_->AssertNoQueuedCommand();

  log::info("Simulate timeout");

  EXPECT_CALL(mock_le_connection_callbacks_,
              OnLeConnectFail(_, ErrorCode::CONNECTION_ACCEPT_TIMEOUT))
          .Times(1);
  le_impl_->on_create_connection_timeout(address);
  sync_handler();
  cancel_connection = hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);
  EXPECT_TRUE(cancel_connection.IsValid());
  hci_layer_->IncomingEvent(
          LeCreateConnectionCancelCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->IncomingLeMetaEvent(LeConnectionCompleteBuilder::Create(
          ErrorCode::UNKNOWN_CONNECTION, kHciHandle, Role::CENTRAL,
          AddressType::PUBLIC_DEVICE_ADDRESS, Address::kEmpty, 0x0000, 0x0000, 0x0000,
          ClockAccuracy::PPM_30));
  sync_handler();
  ASSERT_TRUE(le_impl_->create_connection_timeout_alarms_.empty());

  hci_layer_->GetCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->AssertNoQueuedCommand();
  ASSERT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);
}

TEST_F(LeImplTest, direct_connection_cancel_but_connected) {
  com::android::bluetooth::flags::provider_->le_impl_ack_pause_disarmed(true);

  set_random_device_address_policy();
  controller_->AddSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION);

  hci::AddressWithType address({0x21, 0x22, 0x23, 0x24, 0x25, 0x26},
                               AddressType::PUBLIC_DEVICE_ADDRESS);

  // Create first direct connection
  le_impl_->create_le_connection(address, true, /* is_direct */ true);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(
          LeExtendedCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();
  ASSERT_EQ(ConnectabilityState::ARMED, le_impl_->connectability_state_);

  // Cancel the connection
  le_impl_->cancel_connect(address);
  hci_layer_->GetCommand(OpCode::LE_CREATE_CONNECTION_CANCEL);
  hci_layer_->IncomingEvent(
          LeCreateConnectionCancelCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  // According to the spec, UNKNOWN_CONNECTION should be reported but some controller could
  // report SUCCESS when there is a race between cancel and connect.
  hci_layer_->IncomingLeMetaEvent(LeEnhancedConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, kHciHandle, Role::CENTRAL, AddressType::PUBLIC_DEVICE_ADDRESS,
          remote_address_, local_rpa_, remote_rpa_, 0x0024, 0x0000, 0x0011, ClockAccuracy::PPM_30));
  sync_handler();
  ASSERT_EQ(ConnectabilityState::DISARMED, le_impl_->connectability_state_);
  ASSERT_TRUE(le_impl_->accept_list.empty());

  // Disconnect and reconnect
  le_impl_->on_le_disconnect(kHciHandle, ErrorCode::REMOTE_USER_TERMINATED_CONNECTION);
  sync_handler();

  le_impl_->create_le_connection(address, true, /* is_direct */ true);
  ASSERT_TRUE(le_impl_->accept_list.contains(address));
  sync_handler();

  le_impl_->OnPause();
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_EXTENDED_CREATE_CONNECTION);
  hci_layer_->IncomingEvent(
          LeExtendedCreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 0x01));
  sync_handler();
}

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
