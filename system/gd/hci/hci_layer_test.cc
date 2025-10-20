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

#include "hci/hci_layer.h"

#include <com_android_bluetooth_flags.h>
#include <gtest/gtest.h>

#include <cstdint>
#include <list>
#include <memory>

#include "hal/hci_hal_fake.h"
#include "hci/hci_packets.h"
#include "os/thread.h"
#include "packet/bit_inserter.h"
#include "packet/raw_builder.h"

using bluetooth::os::Thread;
using bluetooth::packet::BitInserter;
using bluetooth::packet::RawBuilder;
using std::vector;

namespace {
vector<uint8_t> information_request = {
        0xfe, 0x2e, 0x0a, 0x00, 0x06, 0x00, 0x01, 0x00, 0x0a, 0x02, 0x02, 0x00, 0x02, 0x00,
};
// 0x00, 0x01, 0x02, 0x03, ...
vector<uint8_t> counting_bytes;
// 0xFF, 0xFE, 0xFD, 0xFC, ...
vector<uint8_t> counting_down_bytes;
const size_t count_size = 0x8;

}  // namespace

namespace bluetooth {
namespace hci {
namespace {

constexpr std::chrono::milliseconds kTimeout = HciLayer::kHciTimeoutMs / 2;

class DependsOnHci {
public:
  DependsOnHci(os::Handler* handler, HciInterface* hci) : handler_(handler), hci_(hci) {
    hci_->RegisterEventHandler(EventCode::CONNECTION_COMPLETE,
                               handler_->BindOn(this, &DependsOnHci::handle_event<EventView>));
    hci_->RegisterLeEventHandler(
            SubeventCode::CONNECTION_COMPLETE,
            handler_->BindOn(this, &DependsOnHci::handle_event<LeMetaEventView>));
    // We must unregister Dequeue registered by HciDataRouter for this tests
    hci_->GetAclQueueEnd()->UnregisterDequeue();
    hci_->GetAclQueueEnd()->RegisterDequeue(
            handler_, common::Bind(&DependsOnHci::handle_acl, common::Unretained(this)));
    hci_->GetIsoQueueEnd()->RegisterDequeue(
            handler_, common::Bind(&DependsOnHci::handle_iso, common::Unretained(this)));
  }

  ~DependsOnHci() {
    // HciDataRouter destructor will unregister our queue
    // hci_->GetAclQueueEnd()->UnregisterDequeue();
    hci_->GetIsoQueueEnd()->UnregisterDequeue();
  }

  void SendHciCommandExpectingStatus(std::unique_ptr<CommandBuilder> command) {
    hci_->EnqueueCommand(
            std::move(command),
            handler_->BindOnceOn(this, &DependsOnHci::handle_event<CommandStatusView>));
  }

  void SendHciCommandExpectingComplete(std::unique_ptr<CommandBuilder> command) {
    hci_->EnqueueCommand(
            std::move(command),
            handler_->BindOnceOn(this, &DependsOnHci::handle_event<CommandCompleteView>));
  }

  void SendSecurityCommandExpectingComplete(std::unique_ptr<SecurityCommandBuilder> command) {
    if (security_interface_ == nullptr) {
      security_interface_ = hci_->GetSecurityInterface(
              handler_->BindOn(this, &DependsOnHci::handle_event<EventView>));
    }
    hci_->EnqueueCommand(
            std::move(command),
            handler_->BindOnceOn(this, &DependsOnHci::handle_event<CommandCompleteView>));
  }

  void SendLeSecurityCommandExpectingComplete(std::unique_ptr<LeSecurityCommandBuilder> command) {
    if (le_security_interface_ == nullptr) {
      le_security_interface_ = hci_->GetLeSecurityInterface(
              handler_->BindOn(this, &DependsOnHci::handle_event<LeMetaEventView>));
    }
    hci_->EnqueueCommand(
            std::move(command),
            handler_->BindOnceOn(this, &DependsOnHci::handle_event<CommandCompleteView>));
  }

  void SendAclData(std::unique_ptr<AclBuilder> acl) {
    outgoing_acl_.push(std::move(acl));
    auto queue_end = hci_->GetAclQueueEnd();
    queue_end->RegisterEnqueue(
            handler_, common::Bind(&DependsOnHci::handle_enqueue, common::Unretained(this)));
  }

  void SendIsoData(std::unique_ptr<IsoBuilder> iso) {
    outgoing_iso_.push(std::move(iso));
    auto queue_end = hci_->GetIsoQueueEnd();
    queue_end->RegisterEnqueue(
            handler_, common::Bind(&DependsOnHci::handle_enqueue_iso, common::Unretained(this)));
  }

  std::optional<EventView> GetReceivedEvent(std::chrono::milliseconds timeout = kTimeout) {
    if (!incoming_events_.wait_to_take(timeout)) {
      return {};
    }
    auto event = EventView::Create(incoming_events_.take());
    log::assert_that(event.IsValid(), "assert failed: event.IsValid()");
    return event;
  }

  std::optional<AclView> GetReceivedAcl(
          std::chrono::milliseconds timeout = std::chrono::seconds(1)) {
    if (!incoming_acl_.wait_to_take(timeout)) {
      return {};
    }
    auto acl = AclView::Create(incoming_acl_.take());
    log::assert_that(acl.IsValid(), "assert failed: acl.IsValid()");
    return acl;
  }

  std::optional<IsoView> GetReceivedIso(
          std::chrono::milliseconds timeout = std::chrono::seconds(1)) {
    if (!incoming_iso_.wait_to_take(timeout)) {
      return {};
    }
    auto iso = IsoView::Create(incoming_iso_.take());
    log::assert_that(iso.IsValid(), "assert failed: iso.IsValid()");
    return iso;
  }

private:
  os::Handler* handler_ = nullptr;
  HciInterface* hci_ = nullptr;
  const SecurityInterface* security_interface_;
  const LeSecurityInterface* le_security_interface_;
  common::BlockingQueue<EventView> incoming_events_;
  common::BlockingQueue<AclView> incoming_acl_;
  common::BlockingQueue<IsoView> incoming_iso_;

  void handle_acl() {
    auto acl_ptr = hci_->GetAclQueueEnd()->TryDequeue();
    incoming_acl_.push(*acl_ptr);
  }

  template <typename T>
  void handle_event(T event) {
    incoming_events_.push(event);
  }

  void handle_iso() {
    auto iso_ptr = hci_->GetIsoQueueEnd()->TryDequeue();
    incoming_iso_.push(*iso_ptr);
  }

  std::queue<std::unique_ptr<AclBuilder>> outgoing_acl_;

  std::unique_ptr<AclBuilder> handle_enqueue() {
    hci_->GetAclQueueEnd()->UnregisterEnqueue();
    auto acl = std::move(outgoing_acl_.front());
    outgoing_acl_.pop();
    return acl;
  }

  std::queue<std::unique_ptr<IsoBuilder>> outgoing_iso_;

  std::unique_ptr<IsoBuilder> handle_enqueue_iso() {
    hci_->GetIsoQueueEnd()->UnregisterEnqueue();
    auto iso = std::move(outgoing_iso_.front());
    outgoing_iso_.pop();
    return iso;
  }
};

class HciTest : public ::testing::Test {
public:
  void SetUp() override {
    counting_bytes.reserve(count_size);
    counting_down_bytes.reserve(count_size);
    for (size_t i = 0; i < count_size; i++) {
      counting_bytes.push_back(i);
      counting_down_bytes.push_back(~i);
    }

    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    client_handler_ = new os::Handler(thread_);

    hal = std::make_unique<hal::TestHciHal>();
    storage = std::make_unique<storage::StorageModule>(client_handler_);

    hci = std::make_unique<HciLayer>(client_handler_, hal.get(), storage.get());
    upper = std::make_unique<DependsOnHci>(client_handler_, hci.get());

    // Verify that reset was received
    auto sent_command = hal->GetSentCommand();
    ASSERT_TRUE(sent_command.has_value());
    auto reset_view = ResetView::Create(CommandView::Create(*sent_command));
    ASSERT_TRUE(reset_view.IsValid());

    // Send the response event
    uint8_t num_packets = 1;
    ErrorCode error_code = ErrorCode::SUCCESS;
    hal->callbacks->hciEventReceived(
            GetPacketBytes(ResetCompleteBuilder::Create(num_packets, error_code)));
  }

  void TearDown() override {
    client_handler_->Synchronize(std::chrono::milliseconds(20));

    upper.reset();
    hci.reset();
    storage.reset();
    hal.reset();

    client_handler_->Clear();
    client_handler_->WaitUntilStopped(bluetooth::kHandlerStopTimeout);

    delete client_handler_;
    delete thread_;
  }

  std::vector<uint8_t> GetPacketBytes(std::unique_ptr<packet::BasePacketBuilder> packet) {
    std::vector<uint8_t> bytes;
    BitInserter i(bytes);
    bytes.reserve(packet->size());
    packet->Serialize(i);
    return bytes;
  }

  os::Thread* thread_ = nullptr;
  os::Handler* client_handler_ = nullptr;
  std::unique_ptr<hal::TestHciHal> hal = nullptr;
  std::unique_ptr<storage::StorageModule> storage = nullptr;
  std::unique_ptr<HciLayer> hci = nullptr;
  std::unique_ptr<DependsOnHci> upper = nullptr;
};

TEST_F(HciTest, initAndClose) {}

TEST_F(HciTest, leMetaEvent) {
  // Send an LE event
  ErrorCode status = ErrorCode::SUCCESS;
  uint16_t handle = 0x123;
  Role role = Role::CENTRAL;
  AddressType peer_address_type = AddressType::PUBLIC_DEVICE_ADDRESS;
  Address peer_address = Address::kAny;
  uint16_t conn_interval = 0x0ABC;
  uint16_t conn_latency = 0x0123;
  uint16_t supervision_timeout = 0x0B05;
  ClockAccuracy central_clock_accuracy = ClockAccuracy::PPM_50;
  hal->callbacks->hciEventReceived(GetPacketBytes(LeConnectionCompleteBuilder::Create(
          status, handle, role, peer_address_type, peer_address, conn_interval, conn_latency,
          supervision_timeout, central_clock_accuracy)));

  // Wait for the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(LeConnectionCompleteView::Create(LeMetaEventView::Create(EventView::Create(*event)))
                      .IsValid());
}

TEST_F(HciTest, postEventsOnceOnHciHandler) {
  // Send a CreateConnection command.
  Address addr = Address::FromString("01:02:03:04:05:06").value();
  upper->SendHciCommandExpectingStatus(CreateConnectionBuilder::Create(
          addr, 0, PageScanRepetitionMode::R0, 0, ClockOffsetValid::INVALID,
          CreateConnectionRoleSwitch::ALLOW_ROLE_SWITCH));

  // Validate the received command.
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command = CreateConnectionView::Create(
          ConnectionManagementCommandView::Create(AclCommandView::Create(*sent_command)));
  ASSERT_TRUE(command.IsValid());

  // Send a status and a connection complete at the same time.
  uint8_t num_packets = 1;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(CreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, num_packets)));
  hal->callbacks->hciEventReceived(GetPacketBytes(ConnectionCompleteBuilder::Create(
          ErrorCode::SUCCESS, 0x123, addr, LinkType::ACL, Enable::DISABLED)));

  // Make sure the status comes first.
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          CreateConnectionStatusView::Create(CommandStatusView::Create(EventView::Create(*event)))
                  .IsValid());
}

TEST_F(HciTest, DISABLED_hciTimeOut) {
  upper->SendHciCommandExpectingComplete(ResetBuilder::Create());
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto reset = ResetView::Create(*sent_command);
  ASSERT_TRUE(reset.IsValid());

  auto event = upper->GetReceivedEvent(HciLayer::kHciTimeoutMs);
  ASSERT_FALSE(event.has_value());

  sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto debug = ControllerDebugInfoView::Create(VendorCommandView::Create(*sent_command));
  ASSERT_TRUE(debug.IsValid());
}

TEST_F(HciTest, noOpCredits) {
  // Send 0 credits
  uint8_t num_packets = 0;
  hal->callbacks->hciEventReceived(GetPacketBytes(NoCommandCompleteBuilder::Create(num_packets)));

  upper->SendHciCommandExpectingComplete(ReadLocalVersionInformationBuilder::Create());

  // Verify that nothing was sent
  ASSERT_FALSE(hal->GetSentCommand(std::chrono::milliseconds(10)).has_value());

  num_packets = 1;
  hal->callbacks->hciEventReceived(GetPacketBytes(NoCommandCompleteBuilder::Create(num_packets)));

  // Verify that one was sent
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  // Send the response event
  ErrorCode error_code = ErrorCode::SUCCESS;
  LocalVersionInformation local_version_information;
  local_version_information.hci_version_ = HciVersion::V_5_0;
  local_version_information.hci_revision_ = 0x1234;
  local_version_information.lmp_version_ = LmpVersion::V_4_2;
  local_version_information.manufacturer_name_ = 0xBAD;
  local_version_information.lmp_subversion_ = 0x5678;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(ReadLocalVersionInformationCompleteBuilder::Create(
                  num_packets, error_code, local_version_information)));

  // Wait for the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(ReadLocalVersionInformationCompleteView::Create(
                      CommandCompleteView::Create(EventView::Create(*event)))
                      .IsValid());
}

TEST_F(HciTest, creditsTest) {
  auto sent_command = hal->GetSentCommand(std::chrono::milliseconds(10));
  ASSERT_FALSE(sent_command.has_value());

  // Send all three commands
  upper->SendHciCommandExpectingComplete(ReadLocalVersionInformationBuilder::Create());
  upper->SendHciCommandExpectingComplete(ReadLocalSupportedCommandsBuilder::Create());
  upper->SendHciCommandExpectingComplete(ReadLocalSupportedFeaturesBuilder::Create());

  // Verify that the first one is sent
  sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto version_view = ReadLocalVersionInformationView::Create(CommandView::Create(*sent_command));
  ASSERT_TRUE(version_view.IsValid());

  // Verify that only one was sent
  sent_command = hal->GetSentCommand(std::chrono::milliseconds(10));
  ASSERT_FALSE(sent_command.has_value());

  // Send the response event
  uint8_t num_packets = 1;
  ErrorCode error_code = ErrorCode::SUCCESS;
  LocalVersionInformation local_version_information;
  local_version_information.hci_version_ = HciVersion::V_5_0;
  local_version_information.hci_revision_ = 0x1234;
  local_version_information.lmp_version_ = LmpVersion::V_4_2;
  local_version_information.manufacturer_name_ = 0xBAD;
  local_version_information.lmp_subversion_ = 0x5678;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(ReadLocalVersionInformationCompleteBuilder::Create(
                  num_packets, error_code, local_version_information)));

  // Wait for the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(ReadLocalVersionInformationCompleteView::Create(
                      CommandCompleteView::Create(EventView::Create(*event)))
                      .IsValid());

  // Verify that the second one is sent
  sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto supported_commands_view =
          ReadLocalSupportedCommandsView::Create(CommandView::Create(*sent_command));
  ASSERT_TRUE(supported_commands_view.IsValid());

  // Verify that only one was sent
  sent_command = hal->GetSentCommand(std::chrono::milliseconds(10));
  ASSERT_FALSE(sent_command.has_value());

  // Send the response event
  std::array<uint8_t, 64> supported_commands;
  for (uint8_t i = 0; i < 64; i++) {
    supported_commands[i] = i;
  }
  hal->callbacks->hciEventReceived(GetPacketBytes(ReadLocalSupportedCommandsCompleteBuilder::Create(
          num_packets, error_code, supported_commands)));

  // Wait for the event
  event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(ReadLocalSupportedCommandsCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
  // Verify that the third one is sent
  sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto supported_features_view =
          ReadLocalSupportedFeaturesView::Create(CommandView::Create(*sent_command));
  ASSERT_TRUE(supported_features_view.IsValid());

  // Verify that only one was sent
  sent_command = hal->GetSentCommand(std::chrono::milliseconds(10));
  ASSERT_FALSE(sent_command.has_value());

  // Send the response event
  uint64_t lmp_features = 0x012345678abcdef;
  hal->callbacks->hciEventReceived(GetPacketBytes(ReadLocalSupportedFeaturesCompleteBuilder::Create(
          num_packets, error_code, lmp_features)));

  // Wait for the event
  event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(ReadLocalSupportedFeaturesCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, leSecurityInterfaceTest) {
  // Send LeRand to the controller
  upper->SendLeSecurityCommandExpectingComplete(LeRandBuilder::Create());

  // Check the command
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  LeRandView view =
          LeRandView::Create(LeSecurityCommandView::Create(CommandView::Create(*sent_command)));
  ASSERT_TRUE(view.IsValid());

  // Send a Command Complete to the host
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  uint64_t rand = 0x0123456789abcdef;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(LeRandCompleteBuilder::Create(num_packets, status, rand)));

  // Verify the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(LeRandCompleteView::Create(CommandCompleteView::Create(*event)).IsValid());
}

TEST_F(HciTest, securityInterfacesTest) {
  // Send WriteSimplePairingMode to the controller
  Enable enable = Enable::ENABLED;
  upper->SendSecurityCommandExpectingComplete(WriteSimplePairingModeBuilder::Create(enable));

  // Check the command
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto view = WriteSimplePairingModeView::Create(
          SecurityCommandView::Create(CommandView::Create(*sent_command)));
  ASSERT_TRUE(view.IsValid());

  // Send a Command Complete to the host
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(WriteSimplePairingModeCompleteBuilder::Create(num_packets, status)));

  // Verify the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(WriteSimplePairingModeCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, createConnectionTest) {
  // Send CreateConnection to the controller
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint16_t packet_type = 0x1234;
  PageScanRepetitionMode page_scan_repetition_mode = PageScanRepetitionMode::R0;
  uint16_t clock_offset = 0x3456;
  ClockOffsetValid clock_offset_valid = ClockOffsetValid::VALID;
  CreateConnectionRoleSwitch allow_role_switch = CreateConnectionRoleSwitch::ALLOW_ROLE_SWITCH;
  upper->SendHciCommandExpectingStatus(
          CreateConnectionBuilder::Create(bd_addr, packet_type, page_scan_repetition_mode,
                                          clock_offset, clock_offset_valid, allow_role_switch));

  // Check the command
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  CreateConnectionView view = CreateConnectionView::Create(ConnectionManagementCommandView::Create(
          AclCommandView::Create(CommandView::Create(*sent_command))));
  ASSERT_TRUE(view.IsValid());
  ASSERT_EQ(bd_addr, view.GetBdAddr());
  ASSERT_EQ(packet_type, view.GetPacketType());
  ASSERT_EQ(page_scan_repetition_mode, view.GetPageScanRepetitionMode());
  ASSERT_EQ(clock_offset, view.GetClockOffset());
  ASSERT_EQ(clock_offset_valid, view.GetClockOffsetValid());
  ASSERT_EQ(allow_role_switch, view.GetAllowRoleSwitch());

  // Send a Command Status to the host
  ErrorCode status = ErrorCode::SUCCESS;
  uint16_t handle = 0x123;
  LinkType link_type = LinkType::ACL;
  Enable encryption_enabled = Enable::DISABLED;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(CreateConnectionStatusBuilder::Create(ErrorCode::SUCCESS, 1)));

  // Verify the event
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(CreateConnectionStatusView::Create(CommandStatusView::Create(*event)).IsValid());

  // Send a ConnectionComplete to the host
  hal->callbacks->hciEventReceived(GetPacketBytes(ConnectionCompleteBuilder::Create(
          status, handle, bd_addr, link_type, encryption_enabled)));

  // Verify the event
  event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ConnectionCompleteView connection_complete_view = ConnectionCompleteView::Create(*event);
  ASSERT_TRUE(connection_complete_view.IsValid());
  ASSERT_EQ(status, connection_complete_view.GetStatus());
  ASSERT_EQ(handle, connection_complete_view.GetConnectionHandle());
  ASSERT_EQ(link_type, connection_complete_view.GetLinkType());
  ASSERT_EQ(encryption_enabled, connection_complete_view.GetEncryptionEnabled());

  // Send an ACL packet from the remote
  PacketBoundaryFlag packet_boundary_flag = PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE;
  BroadcastFlag broadcast_flag = BroadcastFlag::POINT_TO_POINT;
  auto acl_payload = std::make_unique<RawBuilder>();
  acl_payload->AddOctets(bd_addr.address);
  acl_payload->AddOctets2(handle);
  hal->callbacks->aclDataReceived(GetPacketBytes(AclBuilder::Create(
          handle, packet_boundary_flag, broadcast_flag, std::move(acl_payload))));

  // Verify the ACL packet
  auto acl_view_result = upper->GetReceivedAcl();
  ASSERT_TRUE(acl_view_result.has_value());
  auto acl_view = *acl_view_result;
  ASSERT_TRUE(acl_view.IsValid());
  ASSERT_EQ(Address::kLength + sizeof(handle), acl_view.GetPayload().size());
  auto itr = acl_view.GetPayload().begin();
  ASSERT_EQ(bd_addr, packet::extractAddress(itr));
  ASSERT_EQ(handle, itr.extract<uint16_t>());

  // Send an ACL packet from DependsOnHci
  PacketBoundaryFlag packet_boundary_flag2 = PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE;
  BroadcastFlag broadcast_flag2 = BroadcastFlag::POINT_TO_POINT;
  auto acl_payload2 = std::make_unique<RawBuilder>();
  acl_payload2->AddOctets2(handle);
  acl_payload2->AddOctets(bd_addr.address);
  upper->SendAclData(AclBuilder::Create(handle, packet_boundary_flag2, broadcast_flag2,
                                        std::move(acl_payload2)));

  // Verify the ACL packet
  auto sent_acl = hal->GetSentAcl();
  ASSERT_TRUE(sent_acl.has_value());
  AclView sent_acl_view = AclView::Create(*sent_acl);
  ASSERT_TRUE(sent_acl_view.IsValid());
  ASSERT_EQ(Address::kLength + sizeof(handle), sent_acl_view.GetPayload().size());
  auto sent_itr = sent_acl_view.GetPayload().begin();
  ASSERT_EQ(handle, sent_itr.extract<uint16_t>());
  ASSERT_EQ(bd_addr, packet::extractAddress(sent_itr));
}

TEST_F(HciTest, receiveMultipleAclPackets) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint16_t handle = 0x0001;
  const uint16_t num_packets = 100;
  PacketBoundaryFlag packet_boundary_flag = PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE;
  BroadcastFlag broadcast_flag = BroadcastFlag::POINT_TO_POINT;
  for (uint16_t i = 0; i < num_packets; i++) {
    auto acl_payload = std::make_unique<RawBuilder>();
    acl_payload->AddOctets(bd_addr.address);
    acl_payload->AddOctets2(handle);
    acl_payload->AddOctets2(i);
    hal->callbacks->aclDataReceived(GetPacketBytes(AclBuilder::Create(
            handle, packet_boundary_flag, broadcast_flag, std::move(acl_payload))));
  }

  for (uint16_t i = 0; i < num_packets; i++) {
    auto acl_opt = upper->GetReceivedAcl();
    ASSERT_TRUE(acl_opt.has_value());
    auto acl_view = *acl_opt;
    ASSERT_TRUE(acl_view.IsValid());
    ASSERT_EQ(Address::kLength + sizeof(handle) + sizeof(i), acl_view.GetPayload().size());
    auto itr = acl_view.GetPayload().begin();
    ASSERT_EQ(bd_addr, packet::extractAddress(itr));
    ASSERT_EQ(handle, itr.extract<uint16_t>());
    ASSERT_EQ(i, itr.extract<uint16_t>());
  }
}

TEST_F(HciTest, receiveMultipleIsoPackets) {
  uint16_t handle = 0x0001;
  const uint16_t num_packets = 100;
  IsoPacketBoundaryFlag packet_boundary_flag = IsoPacketBoundaryFlag::COMPLETE_SDU;
  TimeStampFlag timestamp_flag = TimeStampFlag::NOT_PRESENT;
  for (uint16_t i = 0; i < num_packets; i++) {
    auto iso_payload = std::make_unique<RawBuilder>();
    iso_payload->AddOctets2(handle);
    iso_payload->AddOctets2(i);
    hal->callbacks->isoDataReceived(GetPacketBytes(IsoBuilder::Create(
            handle, packet_boundary_flag, timestamp_flag, std::move(iso_payload))));
  }
  for (uint16_t i = 0; i < num_packets; i++) {
    auto iso_opt = upper->GetReceivedIso();
    ASSERT_TRUE(iso_opt.has_value());
    auto iso_view = *iso_opt;
    ASSERT_TRUE(iso_view.IsValid());
    ASSERT_EQ(sizeof(handle) + sizeof(i), iso_view.GetPayload().size());
    auto itr = iso_view.GetPayload().begin();
    ASSERT_EQ(handle, itr.extract<uint16_t>());
    ASSERT_EQ(i, itr.extract<uint16_t>());
  }
}

TEST_F(HciTest, log_link_layer_command_status_CreateConnectionCancel) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();

  // Send the CreateConnectionCancel command. This populates the command queue.
  upper->SendHciCommandExpectingStatus(CreateConnectionCancelBuilder::Create(bd_addr));

  // Verify that the command was sent to the HAL.
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command_view = CreateConnectionCancelView::Create(
          ConnectionManagementCommandView::Create(AclCommandView::Create(*sent_command)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(bd_addr, command_view.GetBdAddr());

  // Get the OpCode from the sent command.
  OpCode op_code = command_view.GetOpCode();

  // Simulate receiving a Command Status event from the HAL, using the correct OpCode.
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  auto status_builder = CommandStatusBuilder::Create(status, num_packets, op_code,
                                                     std::make_unique<RawBuilder>());
  hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

  // Verify the event was received by the upper layer.
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(op_code, status_view.GetCommandOpCode());
  ASSERT_EQ(status, status_view.GetStatus());
}

// TEST_F(HciTest, log_link_layer_command_status_Disconnect) {
//   uint16_t handle = 0x123;
//   DisconnectReason reason = DisconnectReason::AUTHENTICATION_FAILURE;
//   upper->SendHciCommandExpectingStatus(DisconnectBuilder::Create(handle, reason));
//   auto sent_command = hal->GetSentCommand();
//   ASSERT_TRUE(sent_command.has_value());
//   auto command_view = DisconnectView::Create(
//           ConnectionManagementCommandView::Create(AclCommandView::Create(*sent_command)));
//   ASSERT_TRUE(command_view.IsValid());
//   ASSERT_EQ(handle, command_view.GetConnectionHandle());
//   ASSERT_EQ(reason, command_view.GetReason());

//   uint8_t num_packets = 1;
//   ErrorCode status = ErrorCode::HARDWARE_FAILURE;
//   auto status_builder = DisconnectStatusBuilder::Create(status, num_packets);
//   hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

//   auto event = upper->GetReceivedEvent();
//   ASSERT_TRUE(event.has_value());
//   ASSERT_TRUE(DisconnectStatusView::Create(CommandStatusView::Create(*event)).IsValid());
//   auto status_view = CommandStatusView::Create(*event);
//   ASSERT_EQ(status, status_view.GetStatus());
// }

TEST_F(HciTest, log_link_layer_command_status_SetupSynchronousConnection) {
  uint16_t handle = 0x123;
  uint32_t transmit_bandwidth = 0x1F40;
  uint32_t receive_bandwidth = 0x1F40;
  uint16_t max_latency = 0x14;
  uint16_t voice_setting = 0x14;
  RetransmissionEffort retransmission_effort = RetransmissionEffort::NO_RETRANSMISSION;
  uint16_t packet_type = static_cast<uint16_t>(BqrPacketType::TYPE_HV1) |
                         static_cast<uint16_t>(BqrPacketType::TYPE_HV2) |
                         static_cast<uint16_t>(BqrPacketType::TYPE_HV3);
  upper->SendHciCommandExpectingStatus(SetupSynchronousConnectionBuilder::Create(
          handle, transmit_bandwidth, receive_bandwidth, max_latency, voice_setting,
          retransmission_effort, packet_type));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  OpCode op_code = static_cast<OpCode>(0x428);
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  auto status_builder = CommandStatusBuilder::Create(status, num_packets, op_code,
                                                     std::make_unique<RawBuilder>());
  hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_EnhancedSetupSynchronousConnection) {
  uint16_t handle = 0x123;
  uint32_t transmit_bandwidth = 0x1F40;
  uint32_t receive_bandwidth = 0x1F40;

  // Correctly create ScoCodingFormat objects
  ScoCodingFormat transmit_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat receive_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat input_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat output_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);

  uint16_t transmit_codec_frame_size = 0x00;
  uint16_t receive_codec_frame_size = 0x00;
  uint32_t input_bandwidth = 0x1F40;
  uint32_t output_bandwidth = 0x1F40;
  uint16_t input_coded_data_bits = 0x00;
  uint16_t output_coded_data_bits = 0x00;

  // Correctly create ScoPcmDataFormat objects using a valid enum value
  ScoPcmDataFormat input_pcm_data_format = ScoPcmDataFormat::TWOS_COMPLEMENT;
  ScoPcmDataFormat output_pcm_data_format = ScoPcmDataFormat::TWOS_COMPLEMENT;

  uint8_t input_pcm_sample_payload_msb_position = 0x01;
  uint8_t output_pcm_sample_payload_msb_position = 0x01;
  ScoDataPath input_data_path = ScoDataPath::HCI;
  ScoDataPath output_data_path = ScoDataPath::HCI;
  uint8_t input_transport_unit_bits = 0x00;
  uint8_t output_transport_unit_bits = 0x00;
  uint16_t max_latency = 0x14;
  uint16_t packet_type = static_cast<uint16_t>(BqrPacketType::TYPE_EV3);
  RetransmissionEffort retransmission_effort = RetransmissionEffort::NO_RETRANSMISSION;

  upper->SendHciCommandExpectingStatus(EnhancedSetupSynchronousConnectionBuilder::Create(
          handle, transmit_bandwidth, receive_bandwidth, transmit_coding_format,
          receive_coding_format, transmit_codec_frame_size, receive_codec_frame_size,
          input_bandwidth, output_bandwidth, input_coding_format, output_coding_format,
          input_coded_data_bits, output_coded_data_bits, input_pcm_data_format,
          output_pcm_data_format, input_pcm_sample_payload_msb_position,
          output_pcm_sample_payload_msb_position, input_data_path, output_data_path,
          input_transport_unit_bits, output_transport_unit_bits, max_latency, packet_type,
          retransmission_effort));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  OpCode op_code = static_cast<OpCode>(0x43d);

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  auto status_builder = CommandStatusBuilder::Create(status, num_packets, op_code,
                                                     std::make_unique<RawBuilder>());
  hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_AcceptConnectionRequest) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  upper->SendHciCommandExpectingStatus(AcceptConnectionRequestBuilder::Create(
          bd_addr, AcceptConnectionRequestRole::BECOME_CENTRAL));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(AcceptConnectionRequestStatusBuilder::Create(status, num_packets)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_AcceptSynchronousConnection) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint32_t transmit_bandwidth = 0x1F40;
  uint32_t receive_bandwidth = 0x1F40;
  uint16_t max_latency = 0x14;
  uint16_t voice_setting = 0x14;
  RetransmissionEffort retransmission_effort = RetransmissionEffort::NO_RETRANSMISSION;
  uint16_t packet_type = static_cast<uint16_t>(BqrPacketType::TYPE_HV1);
  upper->SendHciCommandExpectingStatus(AcceptSynchronousConnectionBuilder::Create(
          bd_addr, transmit_bandwidth, receive_bandwidth, max_latency, voice_setting,
          retransmission_effort, packet_type));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(AcceptSynchronousConnectionStatusBuilder::Create(status, num_packets)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}
TEST_F(HciTest, log_link_layer_command_status_EnhancedAcceptSynchronousConnection) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint32_t transmit_bandwidth = 0x1F40;
  uint32_t receive_bandwidth = 0x1F40;

  // Correctly create ScoCodingFormat objects
  ScoCodingFormat transmit_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat receive_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat input_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);
  ScoCodingFormat output_coding_format(ScoCodingFormatValues::LINEAR_PCM, 0, 0);

  uint16_t transmit_codec_frame_size = 0x00;
  uint16_t receive_codec_frame_size = 0x00;
  uint32_t input_bandwidth = 0x1F40;
  uint32_t output_bandwidth = 0x1F40;
  uint16_t input_coded_data_bits = 0x00;
  uint16_t output_coded_data_bits = 0x00;

  // Correctly create ScoPcmDataFormat objects
  ScoPcmDataFormat input_pcm_data_format = ScoPcmDataFormat::TWOS_COMPLEMENT;
  ScoPcmDataFormat output_pcm_data_format = ScoPcmDataFormat::TWOS_COMPLEMENT;

  uint8_t input_pcm_sample_payload_msb_position = 0x01;
  uint8_t output_pcm_sample_payload_msb_position = 0x01;
  ScoDataPath input_data_path = ScoDataPath::HCI;
  ScoDataPath output_data_path = ScoDataPath::HCI;
  uint8_t input_transport_unit_bits = 0x00;
  uint8_t output_transport_unit_bits = 0x00;
  uint16_t max_latency = 0x14;
  uint16_t packet_type = static_cast<uint16_t>(BqrPacketType::TYPE_EV3);
  RetransmissionEffort retransmission_effort = RetransmissionEffort::NO_RETRANSMISSION;

  upper->SendHciCommandExpectingStatus(EnhancedAcceptSynchronousConnectionBuilder::Create(
          bd_addr, transmit_bandwidth, receive_bandwidth, transmit_coding_format,
          receive_coding_format, transmit_codec_frame_size, receive_codec_frame_size,
          input_bandwidth, output_bandwidth, input_coding_format, output_coding_format,
          input_coded_data_bits, output_coded_data_bits, input_pcm_data_format,
          output_pcm_data_format, input_pcm_sample_payload_msb_position,
          output_pcm_sample_payload_msb_position, input_data_path, output_data_path,
          input_transport_unit_bits, output_transport_unit_bits, max_latency, packet_type,
          retransmission_effort));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  auto status_builder =
          EnhancedAcceptSynchronousConnectionStatusBuilder::Create(status, num_packets);
  hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_RejectConnectionRequest) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  RejectConnectionReason reason = RejectConnectionReason::LIMITED_RESOURCES;
  upper->SendHciCommandExpectingStatus(RejectConnectionRequestBuilder::Create(bd_addr, reason));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(RejectConnectionRequestStatusBuilder::Create(status, num_packets)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_RejectSynchronousConnection) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  RejectConnectionReason reason = RejectConnectionReason::LIMITED_RESOURCES;
  upper->SendHciCommandExpectingStatus(RejectSynchronousConnectionBuilder::Create(bd_addr, reason));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(RejectSynchronousConnectionStatusBuilder::Create(status, num_packets)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeCreateConnection) {
  // 1. Create the LeCreateConnPhyScanParameters object
  vector<LeCreateConnPhyScanParameters> phy_params;
  LeCreateConnPhyScanParameters le_phy_params(0x0123,  // scan_interval
                                              0x4567,  // scan_window
                                              0x0ABC,  // conn_interval_min
                                              0x0DEF,  // conn_interval_max
                                              0x0102,  // conn_latency
                                              0x0304,  // supervision_timeout
                                              0x0506,  // min_ce_length
                                              0x0708   // max_ce_length
  );
  phy_params.push_back(le_phy_params);

  // 3. Send the command and expect a status response.
  Address peer_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  upper->SendHciCommandExpectingStatus(LeExtendedCreateConnectionBuilder::Create(
          InitiatorFilterPolicy::USE_PEER_ADDRESS, OwnAddressType::PUBLIC_DEVICE_ADDRESS,
          AddressType::PUBLIC_DEVICE_ADDRESS, peer_addr, static_cast<uint8_t>(PhyType::LE_1M),
          phy_params));

  OpCode op_code = static_cast<OpCode>(OpCode::LE_EXTENDED_CREATE_CONNECTION);

  // Verify the command was sent to the fake HAL.
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  // Simulate receiving a Command Status event from the HAL, using the generic builder.
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  auto status_builder = CommandStatusBuilder::Create(status, num_packets, op_code,
                                                     std::make_unique<RawBuilder>());
  hal->callbacks->hciEventReceived(GetPacketBytes(std::move(status_builder)));

  // Verify the HCI layer correctly processed the event.
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(op_code, status_view.GetCommandOpCode());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeExtendedCreateConnection) {
  vector<LeCreateConnPhyScanParameters> phy_params;
  LeCreateConnPhyScanParameters le_phy_params(0x0123,  // scan_interval
                                              0x4567,  // scan_window
                                              0x0ABC,  // conn_interval_min
                                              0x0DEF,  // conn_interval_max
                                              0x0102,  // conn_latency
                                              0x0304,  // supervision_timeout
                                              0x0506,  // min_ce_length
                                              0x0708   // max_ce_length
  );
  phy_params.push_back(le_phy_params);

  Address peer_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();

  upper->SendHciCommandExpectingStatus(LeExtendedCreateConnectionBuilder::Create(
          InitiatorFilterPolicy::USE_PEER_ADDRESS, OwnAddressType::PUBLIC_DEVICE_ADDRESS,
          AddressType::PUBLIC_DEVICE_ADDRESS, peer_addr, static_cast<uint8_t>(PhyType::LE_1M),
          phy_params));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(LeExtendedCreateConnectionStatusBuilder::Create(status, num_packets)));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeCreateConnectionCancel) {
  upper->SendHciCommandExpectingStatus(LeCreateConnectionCancelBuilder::Create());
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::UNKNOWN_CONNECTION;  // Non-SUCCESS status to hit the if
  hal->callbacks->hciEventReceived(GetPacketBytes(
          CommandStatusBuilder::Create(status, num_packets, OpCode::LE_CREATE_CONNECTION_CANCEL,
                                       std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeClearFilterAcceptList) {
  upper->SendHciCommandExpectingStatus(LeClearFilterAcceptListBuilder::Create());
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(GetPacketBytes(
          CommandStatusBuilder::Create(status, num_packets, OpCode::LE_CLEAR_FILTER_ACCEPT_LIST,
                                       std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeAddDeviceToFilterAcceptList) {
  Address addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  upper->SendHciCommandExpectingStatus(
          LeAddDeviceToFilterAcceptListBuilder::Create(FilterAcceptListAddressType::PUBLIC, addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST,
          std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_link_layer_command_status_LeRemoveDeviceFromFilterAcceptList) {
  Address addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  upper->SendHciCommandExpectingStatus(LeRemoveDeviceFromFilterAcceptListBuilder::Create(
          FilterAcceptListAddressType::PUBLIC, addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::HARDWARE_FAILURE;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST,
          std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_pairing_command_complete_ReadLocalOobData) {
  upper->SendHciCommandExpectingComplete(ReadLocalOobDataBuilder::Create());
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(
          GetPacketBytes(ReadLocalOobDataCompleteBuilder::Create(num_packets, status, {}, {})));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(ReadLocalOobDataCompleteView::Create(CommandCompleteView::Create(*event)).IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_WriteSecureConnectionsHostSupport) {
  Enable enable = Enable::ENABLED;
  upper->SendHciCommandExpectingComplete(WriteSecureConnectionsHostSupportBuilder::Create(enable));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(GetPacketBytes(
          WriteSecureConnectionsHostSupportCompleteBuilder::Create(num_packets, status)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          WriteSecureConnectionsHostSupportCompleteView::Create(CommandCompleteView::Create(*event))
                  .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_ReadEncryptionKeySize) {
  uint16_t connection_handle = 0x123;
  upper->SendHciCommandExpectingComplete(ReadEncryptionKeySizeBuilder::Create(connection_handle));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  uint8_t key_size = 0x10;
  hal->callbacks->hciEventReceived(GetPacketBytes(ReadEncryptionKeySizeCompleteBuilder::Create(
          num_packets, status, connection_handle, key_size)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          ReadEncryptionKeySizeCompleteView::Create(CommandCompleteView::Create(*event)).IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_LinkKeyRequestReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  std::array<uint8_t, 16> key = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
  upper->SendHciCommandExpectingComplete(LinkKeyRequestReplyBuilder::Create(bd_addr, key));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(
          GetPacketBytes(LinkKeyRequestReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          LinkKeyRequestReplyCompleteView::Create(CommandCompleteView::Create(*event)).IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_LinkKeyRequestNegativeReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(LinkKeyRequestNegativeReplyBuilder::Create(bd_addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          LinkKeyRequestNegativeReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(LinkKeyRequestNegativeReplyCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_IoCapabilityRequestReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  IoCapability io_capability = IoCapability::NO_INPUT_NO_OUTPUT;
  OobDataPresent oob_data_present = OobDataPresent::NOT_PRESENT;
  AuthenticationRequirements authentication_requirements = AuthenticationRequirements::NO_BONDING;
  upper->SendHciCommandExpectingComplete(IoCapabilityRequestReplyBuilder::Create(
          bd_addr, io_capability, oob_data_present, authentication_requirements));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          IoCapabilityRequestReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(IoCapabilityRequestReplyCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_IoCapabilityRequestNegativeReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(
          IoCapabilityRequestNegativeReplyBuilder::Create(bd_addr, ErrorCode::LIMIT_REACHED));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          IoCapabilityRequestNegativeReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          IoCapabilityRequestNegativeReplyCompleteView::Create(CommandCompleteView::Create(*event))
                  .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_UserConfirmationRequestReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(UserConfirmationRequestReplyBuilder::Create(bd_addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          UserConfirmationRequestReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(UserConfirmationRequestReplyCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_UserConfirmationRequestNegativeReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(
          UserConfirmationRequestNegativeReplyBuilder::Create(bd_addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(
          GetPacketBytes(UserConfirmationRequestNegativeReplyCompleteBuilder::Create(
                  num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(UserConfirmationRequestNegativeReplyCompleteView::Create(
                      CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_UserPasskeyRequestReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  uint32_t passkey = 123456;
  upper->SendHciCommandExpectingComplete(UserPasskeyRequestReplyBuilder::Create(bd_addr, passkey));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          UserPasskeyRequestReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(UserPasskeyRequestReplyCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_UserPasskeyRequestNegativeReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(UserPasskeyRequestNegativeReplyBuilder::Create(bd_addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          UserPasskeyRequestNegativeReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          UserPasskeyRequestNegativeReplyCompleteView::Create(CommandCompleteView::Create(*event))
                  .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_RemoteOobDataRequestReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  std::array<uint8_t, 16> c = {0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                               0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20};
  std::array<uint8_t, 16> r = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                               0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30};
  upper->SendHciCommandExpectingComplete(RemoteOobDataRequestReplyBuilder::Create(bd_addr, c, r));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          RemoteOobDataRequestReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(RemoteOobDataRequestReplyCompleteView::Create(CommandCompleteView::Create(*event))
                      .IsValid());
}

TEST_F(HciTest, log_pairing_command_complete_RemoteOobDataRequestNegativeReply) {
  Address bd_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  upper->SendHciCommandExpectingComplete(RemoteOobDataRequestNegativeReplyBuilder::Create(bd_addr));
  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  hal->callbacks->hciEventReceived(GetPacketBytes(
          RemoteOobDataRequestNegativeReplyCompleteBuilder::Create(num_packets, status, bd_addr)));
  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(
          RemoteOobDataRequestNegativeReplyCompleteView::Create(CommandCompleteView::Create(*event))
                  .IsValid());
}

TEST_F(HciTest, log_pairing_command_status_AuthenticationRequested) {
  uint16_t connection_handle = 0x100;
  upper->SendHciCommandExpectingStatus(AuthenticationRequestedBuilder::Create(connection_handle));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command_view = AuthenticationRequestedView::Create(
          ConnectionManagementCommandView::Create(AclCommandView::Create(*sent_command)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(connection_handle, command_view.GetConnectionHandle());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::AUTHENTICATION_REQUESTED, std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_pairing_command_status_SetConnectionEncryption) {
  uint16_t connection_handle = 0x101;
  Enable encryption_enable = Enable::ENABLED;
  upper->SendHciCommandExpectingStatus(
          SetConnectionEncryptionBuilder::Create(connection_handle, encryption_enable));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command_view = SetConnectionEncryptionView::Create(
          ConnectionManagementCommandView::Create(AclCommandView::Create(*sent_command)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(connection_handle, command_view.GetConnectionHandle());
  ASSERT_EQ(encryption_enable, command_view.GetEncryptionEnable());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::SET_CONNECTION_ENCRYPTION, std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_pairing_command_status_RemoteNameRequest) {
  Address bd_addr = Address::FromString("A1:B2:C3:D4:E5:F6").value();
  PageScanRepetitionMode page_scan_mode = PageScanRepetitionMode::R2;
  uint16_t clock_offset = 0x1234;
  ClockOffsetValid clock_offset_valid = ClockOffsetValid::VALID;
  upper->SendHciCommandExpectingStatus(RemoteNameRequestBuilder::Create(
          bd_addr, page_scan_mode, clock_offset, clock_offset_valid));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command_view = RemoteNameRequestView::Create(
          DiscoveryCommandView::Create(CommandView::Create(*sent_command)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(bd_addr, command_view.GetBdAddr());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::REMOTE_NAME_REQUEST, std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_pairing_command_status_RemoteNameRequestCancel) {
  Address bd_addr = Address::FromString("A1:B2:C3:D4:E5:F6").value();
  upper->SendHciCommandExpectingStatus(RemoteNameRequestCancelBuilder::Create(bd_addr));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto command_view = RemoteNameRequestCancelView::Create(
          DiscoveryCommandView::Create(CommandView::Create(*sent_command)));
  ASSERT_TRUE(command_view.IsValid());
  ASSERT_EQ(bd_addr, command_view.GetBdAddr());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::SUCCESS;
  hal->callbacks->hciEventReceived(GetPacketBytes(
          CommandStatusBuilder::Create(status, num_packets, OpCode::REMOTE_NAME_REQUEST_CANCEL,
                                       std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

TEST_F(HciTest, log_le_connection_command_LeCreateConnection) {
  Address peer_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint16_t scan_interval = 0x1234;
  uint16_t scan_window = 0x1234;
  InitiatorFilterPolicy initiator_filter_policy = InitiatorFilterPolicy::USE_PEER_ADDRESS;
  AddressType peer_address_type = AddressType::PUBLIC_DEVICE_ADDRESS;
  uint16_t conn_interval_min = 0x0ABC;
  uint16_t conn_interval_max = 0x0DEF;
  uint16_t conn_latency = 0x0123;
  uint16_t supervision_timeout = 0x0B05;
  uint16_t minimum_ce_length = 0x0001;
  uint16_t maximum_ce_length = 0x0002;

  upper->SendHciCommandExpectingStatus(LeCreateConnectionBuilder::Create(
          scan_interval, scan_window, initiator_filter_policy, peer_address_type, peer_addr,
          OwnAddressType::PUBLIC_DEVICE_ADDRESS, conn_interval_min, conn_interval_max, conn_latency,
          supervision_timeout, minimum_ce_length, maximum_ce_length));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());
  auto view = LeCreateConnectionView::Create(
          LeConnectionManagementCommandView::Create(ConnectionManagementCommandView::Create(
                  AclCommandView::Create(CommandView::Create(*sent_command)))));
  ASSERT_TRUE(view.IsValid());
  ASSERT_EQ(peer_addr, view.GetPeerAddress());
}

TEST_F(HciTest, log_le_connection_command_status_LeCreateConnection) {
  Address peer_addr = Address::FromString("A1:A2:A3:A4:A5:A6").value();
  uint16_t scan_interval = 0x1234;
  uint16_t scan_window = 0x1234;
  InitiatorFilterPolicy initiator_filter_policy = InitiatorFilterPolicy::USE_PEER_ADDRESS;
  AddressType peer_address_type = AddressType::PUBLIC_DEVICE_ADDRESS;
  uint16_t conn_interval_min = 0x0ABC;
  uint16_t conn_interval_max = 0x0DEF;
  uint16_t conn_latency = 0x0123;
  uint16_t supervision_timeout = 0x0B05;
  uint16_t minimum_ce_length = 0x0001;
  uint16_t maximum_ce_length = 0x0002;

  upper->SendHciCommandExpectingStatus(LeCreateConnectionBuilder::Create(
          scan_interval, scan_window, initiator_filter_policy, peer_address_type, peer_addr,
          OwnAddressType::PUBLIC_DEVICE_ADDRESS, conn_interval_min, conn_interval_max, conn_latency,
          supervision_timeout, minimum_ce_length, maximum_ce_length));

  auto sent_command = hal->GetSentCommand();
  ASSERT_TRUE(sent_command.has_value());

  uint8_t num_packets = 1;
  ErrorCode status = ErrorCode::CONNECTION_FAILED_ESTABLISHMENT;
  hal->callbacks->hciEventReceived(GetPacketBytes(CommandStatusBuilder::Create(
          status, num_packets, OpCode::LE_CREATE_CONNECTION, std::make_unique<RawBuilder>())));

  auto event = upper->GetReceivedEvent();
  ASSERT_TRUE(event.has_value());
  auto status_view = CommandStatusView::Create(*event);
  ASSERT_TRUE(status_view.IsValid());
  ASSERT_EQ(status, status_view.GetStatus());
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
