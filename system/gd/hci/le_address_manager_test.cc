/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "hci/le_address_manager.h"

#include <bluetooth/types/bt_octets.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "hci/controller_mock.h"
#include "hci/hci_layer_fake.h"
#include "os/mock_rand.h"
#include "packet/raw_builder.h"

using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;

namespace bluetooth {
namespace hci {
namespace {

using packet::kLittleEndian;
using packet::PacketView;
using packet::RawBuilder;

class TestController : public testing::MockController {
public:
  bool IsSupported(OpCode op_code) const override { return supported_opcodes_.count(op_code) == 1; }

  void AddSupported(OpCode op_code) { supported_opcodes_.insert(op_code); }

  uint8_t GetLeNumberOfSupportedAdvertisingSets() const override { return num_advertisers_; }

  uint16_t GetLeMaximumAdvertisingDataLength() const override { return 0x0672; }

  bool SupportsBlePeriodicAdvertising() const override { return true; }

  bool SupportsBleExtendedAdvertising() const override { return support_ble_extended_advertising_; }

  void SetBleExtendedAdvertisingSupport(bool support) {
    support_ble_extended_advertising_ = support;
  }

  bool IsRpaGenerationSupported() const override { return rpa_generation_supported_; }

  void SetRpaGenerationSupport(bool support) { rpa_generation_supported_ = support; }

  VendorCapabilities GetVendorCapabilities() const override { return vendor_capabilities_; }

  uint8_t num_advertisers_{0};
  VendorCapabilities vendor_capabilities_;

private:
  std::set<OpCode> supported_opcodes_{};
  bool support_ble_extended_advertising_ = false;
  bool rpa_generation_supported_ = false;
};

class RotatorClient : public LeAddressManagerCallback {
public:
  RotatorClient(LeAddressManager* le_address_manager, size_t id)
      : le_address_manager_(le_address_manager), id_(id) {}

  void OnPause() {
    paused = true;
    le_address_manager_->AckPause(this);
  }

  void OnResume() {
    paused = false;
    le_address_manager_->AckResume(this);
    if (resume_promise_ != nullptr) {
      std::promise<void>* prom = resume_promise_.release();
      prom->set_value();
      delete prom;
    }
  }

  void WaitForResume() {
    if (paused) {
      resume_promise_ = std::make_unique<std::promise<void>>();
      auto resume_future = resume_promise_->get_future();
      auto result = resume_future.wait_for(std::chrono::milliseconds(1000));
      EXPECT_NE(std::future_status::timeout, result);
    }
  }

  bool paused{false};
  LeAddressManager* le_address_manager_;
  size_t id_;
  std::unique_ptr<std::promise<void>> resume_promise_;
};

class LeAddressManagerTest : public ::testing::Test {
public:
  void SetUp() override {
    thread_ = new Thread("thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    hci_layer_ = std::make_unique<HciLayerFake>(handler_);
    Address address({0x01, 0x02, 0x03, 0x04, 0x05, 0x06});
    controller_ = std::make_unique<TestController>();
    le_address_manager_ = new LeAddressManager(
            base::BindRepeating(&LeAddressManagerTest::enqueue_command, base::Unretained(this)),
            handler_, address, 0x3F, 0x3F, controller_.get());
    AllocateClients(1);
  }

  void sync_handler(os::Handler* /* handler */) {
    std::promise<void> promise;
    auto future = promise.get_future();
    handler_->Post(base::BindOnce(&std::promise<void>::set_value, base::Unretained(&promise)));
    auto future_status = future.wait_for(std::chrono::seconds(1));
    EXPECT_EQ(future_status, std::future_status::ready);
  }

  void TearDown() override {
    sync_handler(handler_);
    delete le_address_manager_;
    hci_layer_.reset();
    handler_->Clear();
    delete handler_;
    delete thread_;
  }

  void AllocateClients(size_t num_clients) {
    size_t first_id = clients.size();
    for (size_t i = 0; i < num_clients; i++) {
      clients.emplace_back(std::make_unique<RotatorClient>(le_address_manager_, first_id + i));
    }
  }

  void enqueue_command(std::unique_ptr<CommandBuilder> command_packet) {
    hci_layer_->EnqueueCommand(std::move(command_packet),
                               handler_->BindOnce(&LeAddressManager::OnCommandComplete,
                                                  base::Unretained(le_address_manager_)));
  }

  Thread* thread_;
  Handler* handler_;
  std::unique_ptr<HciLayerFake> hci_layer_ = nullptr;
  std::unique_ptr<TestController> controller_;
  LeAddressManager* le_address_manager_;
  std::vector<std::unique_ptr<RotatorClient>> clients;
};

TEST_F(LeAddressManagerTest, startup_teardown) {}

TEST_F(LeAddressManagerTest, register_unregister_callback) {
  le_address_manager_->Register(clients[0].get());
  sync_handler(handler_);
  le_address_manager_->Unregister(clients[0].get());
  sync_handler(handler_);
}

TEST_F(LeAddressManagerTest, rotator_address_for_single_client) {
  Octet16 irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                 0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  auto minimum_rotation_time = std::chrono::milliseconds(1000);
  auto maximum_rotation_time = std::chrono::milliseconds(3000);
  AddressWithType remote_address(Address::kEmpty, AddressType::RANDOM_DEVICE_ADDRESS);
  le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, remote_address, irk, false,
          minimum_rotation_time, maximum_rotation_time);

  le_address_manager_->Register(clients[0].get());
  sync_handler(handler_);
  hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
  hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
  le_address_manager_->Unregister(clients[0].get());
  sync_handler(handler_);
}

TEST_F(LeAddressManagerTest, rotator_non_resolvable_address_for_single_client) {
  Octet16 irk = {};
  auto minimum_rotation_time = std::chrono::milliseconds(1000);
  auto maximum_rotation_time = std::chrono::milliseconds(3000);
  AddressWithType remote_address(Address::kEmpty, AddressType::RANDOM_DEVICE_ADDRESS);
  le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy::USE_NON_RESOLVABLE_ADDRESS, remote_address, irk, false,
          minimum_rotation_time, maximum_rotation_time);

  le_address_manager_->Register(clients[0].get());
  sync_handler(handler_);
  hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
  hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
  le_address_manager_->Unregister(clients[0].get());
  sync_handler(handler_);
}

TEST_F(LeAddressManagerTest, set_resolvable_address_with_rpa_offload) {
  // This test verifies that when RPA offloading is supported, the initial
  // random address is still set via LeSetRandomAddress.
  controller_->SetRpaGenerationSupport(true);

  Octet16 irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                 0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  auto minimum_rotation_time = std::chrono::milliseconds(1000);
  auto maximum_rotation_time = std::chrono::milliseconds(3000);
  AddressWithType remote_address(Address::kEmpty, AddressType::RANDOM_DEVICE_ADDRESS);
  le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, remote_address, irk, false,
          minimum_rotation_time, maximum_rotation_time);

  le_address_manager_->Register(clients[0].get());
  sync_handler(handler_);

  // Verify that the RPA timeout is set for offloading.
  hci_layer_->GetCommand(OpCode::LE_SET_RESOLVABLE_PRIVATE_ADDRESS_TIMEOUT_V2);

  // Verify that the initial random address is set.
  hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
  hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
  le_address_manager_->Unregister(clients[0].get());
  sync_handler(handler_);
}

// TODO handle the case "register during rotate_random_address" and enable this
TEST_F(LeAddressManagerTest, DISABLED_rotator_address_for_multiple_clients) {
  AllocateClients(2);
  Octet16 irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                 0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  auto minimum_rotation_time = std::chrono::milliseconds(1000);
  auto maximum_rotation_time = std::chrono::milliseconds(3000);
  AddressWithType remote_address(Address::kEmpty, AddressType::RANDOM_DEVICE_ADDRESS);
  le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, remote_address, irk, false,
          minimum_rotation_time, maximum_rotation_time);
  le_address_manager_->Register(clients[0].get());
  le_address_manager_->Register(clients[1].get());
  le_address_manager_->Register(clients[2].get());
  sync_handler(handler_);

  le_address_manager_->Unregister(clients[0].get());
  le_address_manager_->Unregister(clients[1].get());
  le_address_manager_->Unregister(clients[2].get());
  sync_handler(handler_);
}

TEST_F(LeAddressManagerTest, generate_rpa_with_invalid_prands) {
  constexpr uint8_t BLE_ADDR_MASK = 0b11000000;
  constexpr uint8_t BLE_RESOLVE_ADDR_MSB = 0b01000000;
  std::array<uint8_t, 3> invalid_prand_zeros = {0x00, 0x00, 0x00};
  std::array<uint8_t, 3> invalid_prand_ones = {0xff, 0xff, 0xff};
  std::array<uint8_t, 3> valid_prand = {0x41, 0x73, 0xFF};

  // Set up mock random generator for subsequent calls to generate_rpa()
  auto mock_random_generator = new bluetooth::os::testing::MockRandomDataGenerator;
  bluetooth::os::SetRandomDataGeneratorForTesting(mock_random_generator);
  {
    ::testing::InSequence s;
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 3))
            .WillOnce(::testing::SetArrayArgument<0>(invalid_prand_zeros.begin(),
                                                     invalid_prand_zeros.end()));
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 3))
            .WillOnce(::testing::SetArrayArgument<0>(invalid_prand_ones.begin(),
                                                     invalid_prand_ones.end()));
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 3))
            .WillOnce(::testing::SetArrayArgument<0>(valid_prand.begin(), valid_prand.end()));
  }

  // Trigger a new address generation by setting the policy again.
  // This will cause the internal generate_rpa() method to be called again,
  // which will trigger the InSequence mocks for the random data generator.
  le_address_manager_->SetPrivacyPolicyForInitiatorAddressForTest(
          LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, AddressWithType(), Octet16(),
          std::chrono::milliseconds(1000), std::chrono::milliseconds(3000));
  sync_handler(handler_);

  // Get the LE_SET_RANDOM_ADDRESS command and extract the RPA.
  auto packet = hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
  auto packet_view = LeSetRandomAddressView::Create(
          LeAdvertisingCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(packet_view.IsValid());
  hci::Address rpa = packet_view.GetRandomAddress();

  // Match the random part of the RPA with the valid_prand value
  ASSERT_EQ(rpa.address[5] >> 6, BLE_RESOLVE_ADDR_MSB >> 6);  // Check two most significant bits
  ASSERT_EQ(rpa.address[5] | BLE_ADDR_MASK, valid_prand[2] | BLE_ADDR_MASK);
  ASSERT_EQ(rpa.address[4], valid_prand[1]);
  ASSERT_EQ(rpa.address[3], valid_prand[0]);

  bluetooth::os::SetRandomDataGeneratorForTesting(nullptr);
  delete mock_random_generator;
}

TEST_F(LeAddressManagerTest, generate_nrpa_with_invalid_random) {
  constexpr uint8_t BLE_ADDR_MASK = 0b11000000;
  std::array<uint8_t, 6> invalid_random_zeros = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
  std::array<uint8_t, 6> invalid_random_ones = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
  // Public address set in LeAddressManagerTest::SetUp()
  std::array<uint8_t, 6> public_address_raw = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
  std::array<uint8_t, 6> valid_random = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

  auto mock_random_generator = new bluetooth::os::testing::MockRandomDataGenerator;
  bluetooth::os::SetRandomDataGeneratorForTesting(mock_random_generator);
  {
    ::testing::InSequence s;
    // First return all zeros, which is invalid
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 6))
            .WillOnce(::testing::SetArrayArgument<0>(invalid_random_zeros.begin(),
                                                     invalid_random_zeros.end()));
    // Next return all ones, which is invalid
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 6))
            .WillOnce(::testing::SetArrayArgument<0>(invalid_random_ones.begin(),
                                                     invalid_random_ones.end()));
    // Next return the public address, which is invalid
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 6))
            .WillOnce(::testing::SetArrayArgument<0>(public_address_raw.begin(),
                                                     public_address_raw.end()));
    // Finally, return a valid random value
    EXPECT_CALL(*mock_random_generator, GenerateBytes(::testing::_, 6))
            .WillOnce(::testing::SetArrayArgument<0>(valid_random.begin(), valid_random.end()));
  }

  // Trigger a new address generation by setting the policy again.
  // This will cause the internal generate_nrpa() method to be called again,
  // which will trigger the InSequence mocks for the random data generator.
  le_address_manager_->SetPrivacyPolicyForInitiatorAddressForTest(
          LeAddressManager::AddressPolicy::USE_NON_RESOLVABLE_ADDRESS, AddressWithType(), Octet16(),
          std::chrono::milliseconds(1000), std::chrono::milliseconds(3000));
  sync_handler(handler_);

  auto packet = hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
  auto packet_view = LeSetRandomAddressView::Create(
          LeAdvertisingCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(packet_view.IsValid());
  hci::Address nrpa = packet_view.GetRandomAddress();
  // Check it is a non-resolvable random address (top two bits are 00)
  ASSERT_EQ(nrpa.address[5] & BLE_ADDR_MASK, 0);

  std::array<uint8_t, 6> correct_random = valid_random;
  correct_random[5] &= ~BLE_ADDR_MASK;
  hci::Address correct_nrpa;
  correct_nrpa.FromOctets(correct_random.data());

  ASSERT_EQ(nrpa, correct_nrpa);

  bluetooth::os::SetRandomDataGeneratorForTesting(nullptr);
  delete mock_random_generator;
}

class LeAddressManagerWithSingleClientTest : public LeAddressManagerTest {
public:
  void SetUp() override {
    thread_ = new Thread("thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    hci_layer_ = std::make_unique<HciLayerFake>(handler_);
    Address address({0x01, 0x02, 0x03, 0x04, 0x05, 0x06});
    controller_ = std::make_unique<TestController>();
    le_address_manager_ = new LeAddressManager(
            base::BindRepeating(&LeAddressManagerWithSingleClientTest::enqueue_command,
                                base::Unretained(this)),
            handler_, address, 0x3F, 0x3F, controller_.get());
    AllocateClients(1);

    Octet16 irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                   0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
    auto minimum_rotation_time = std::chrono::milliseconds(1000);
    auto maximum_rotation_time = std::chrono::milliseconds(3000);
    AddressWithType remote_address(Address::kEmpty, AddressType::RANDOM_DEVICE_ADDRESS);
    le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
            LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS, remote_address, irk, false,
            minimum_rotation_time, maximum_rotation_time);

    le_address_manager_->Register(clients[0].get());
    sync_handler(handler_);
    hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);
    hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }

  void enqueue_command(std::unique_ptr<CommandBuilder> command_packet) {
    hci_layer_->EnqueueCommand(std::move(command_packet),
                               handler_->BindOnce(&LeAddressManager::OnCommandComplete,
                                                  base::Unretained(le_address_manager_)));
  }

  void TearDown() override {
    le_address_manager_->Unregister(clients[0].get());
    sync_handler(handler_);
    delete le_address_manager_;
    hci_layer_.reset();
    handler_->Clear();
    delete handler_;
    delete thread_;
  }
};

TEST_F(LeAddressManagerWithSingleClientTest, add_device_to_accept_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  le_address_manager_->AddDeviceToFilterAcceptList(FilterAcceptListAddressType::RANDOM, address);
  auto packet = hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  auto packet_view = LeAddDeviceToFilterAcceptListView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(packet_view.IsValid());
  ASSERT_EQ(FilterAcceptListAddressType::RANDOM, packet_view.GetAddressType());
  ASSERT_EQ(address, packet_view.GetAddress());

  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
}

TEST_F(LeAddressManagerWithSingleClientTest, remove_device_from_accept_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  le_address_manager_->AddDeviceToFilterAcceptList(FilterAcceptListAddressType::RANDOM, address);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  le_address_manager_->RemoveDeviceFromFilterAcceptList(FilterAcceptListAddressType::RANDOM,
                                                        address);
  auto packet = hci_layer_->GetCommand(OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST);
  auto packet_view = LeRemoveDeviceFromFilterAcceptListView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(packet_view.IsValid());
  ASSERT_EQ(FilterAcceptListAddressType::RANDOM, packet_view.GetAddressType());
  ASSERT_EQ(address, packet_view.GetAddress());
  hci_layer_->IncomingEvent(
          LeRemoveDeviceFromFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
}

TEST_F(LeAddressManagerWithSingleClientTest, clear_filter_accept_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  le_address_manager_->AddDeviceToFilterAcceptList(FilterAcceptListAddressType::RANDOM, address);
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  le_address_manager_->ClearFilterAcceptList();
  hci_layer_->GetCommand(OpCode::LE_CLEAR_FILTER_ACCEPT_LIST);
  hci_layer_->IncomingEvent(
          LeClearFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  clients[0].get()->WaitForResume();
}

// b/260916288
TEST_F(LeAddressManagerWithSingleClientTest, DISABLED_add_device_to_resolving_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  Octet16 peer_irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                      0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  Octet16 local_irk = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                       0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10};

  le_address_manager_->AddDeviceToResolvingList(PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS,
                                                address, peer_irk, local_irk);
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::DISABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST);
    auto packet_view =
            LeAddDeviceToResolvingListView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS,
              packet_view.GetPeerIdentityAddressType());
    ASSERT_EQ(address, packet_view.GetPeerIdentityAddress());
    ASSERT_EQ(peer_irk, packet_view.GetPeerIrk());
    ASSERT_EQ(local_irk, packet_view.GetLocalIrk());
    hci_layer_->IncomingEvent(
            LeAddDeviceToResolvingListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::ENABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  clients[0].get()->WaitForResume();
}

// b/260916288
TEST_F(LeAddressManagerWithSingleClientTest, DISABLED_remove_device_from_resolving_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  Octet16 peer_irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                      0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  Octet16 local_irk = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                       0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10};
  le_address_manager_->AddDeviceToResolvingList(PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS,
                                                address, peer_irk, local_irk);
  hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
  hci_layer_->IncomingEvent(
          LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToResolvingListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
  hci_layer_->IncomingEvent(
          LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  le_address_manager_->RemoveDeviceFromResolvingList(
          PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS, address);
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::DISABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_REMOVE_DEVICE_FROM_RESOLVING_LIST);
    auto packet_view =
            LeRemoveDeviceFromResolvingListView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS,
              packet_view.GetPeerIdentityAddressType());
    ASSERT_EQ(address, packet_view.GetPeerIdentityAddress());
    hci_layer_->IncomingEvent(
            LeRemoveDeviceFromResolvingListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::ENABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  clients[0].get()->WaitForResume();
}

// b/260916288
TEST_F(LeAddressManagerWithSingleClientTest, DISABLED_clear_resolving_list) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  Octet16 peer_irk = {0xec, 0x02, 0x34, 0xa3, 0x57, 0xc8, 0xad, 0x05,
                      0x34, 0x10, 0x10, 0xa6, 0x0a, 0x39, 0x7d, 0x9b};
  Octet16 local_irk = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                       0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10};
  le_address_manager_->AddDeviceToResolvingList(PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS,
                                                address, peer_irk, local_irk);
  hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
  hci_layer_->IncomingEvent(
          LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST);
  hci_layer_->IncomingEvent(
          LeAddDeviceToResolvingListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
  hci_layer_->IncomingEvent(
          LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  le_address_manager_->ClearResolvingList();
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::DISABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_CLEAR_RESOLVING_LIST);
    auto packet_view = LeClearResolvingListView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    hci_layer_->IncomingEvent(
            LeClearResolvingListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }
  {
    auto packet = hci_layer_->GetCommand(OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE);
    auto packet_view =
            LeSetAddressResolutionEnableView::Create(LeSecurityCommandView::Create(packet));
    ASSERT_TRUE(packet_view.IsValid());
    ASSERT_EQ(Enable::ENABLED, packet_view.GetAddressResolutionEnable());
    hci_layer_->IncomingEvent(
            LeSetAddressResolutionEnableCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));
  }

  clients[0].get()->WaitForResume();
}

TEST_F(LeAddressManagerWithSingleClientTest, register_during_command_complete) {
  Address address = Address::FromString("01:02:03:04:05:06").value();
  le_address_manager_->AddDeviceToFilterAcceptList(FilterAcceptListAddressType::RANDOM, address);
  auto packet = hci_layer_->GetCommand(OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST);
  auto packet_view = LeAddDeviceToFilterAcceptListView::Create(
          LeConnectionManagementCommandView::Create(AclCommandView::Create(packet)));
  ASSERT_TRUE(packet_view.IsValid());
  ASSERT_EQ(FilterAcceptListAddressType::RANDOM, packet_view.GetAddressType());
  ASSERT_EQ(address, packet_view.GetAddress());
  hci_layer_->IncomingEvent(
          LeAddDeviceToFilterAcceptListCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  AllocateClients(1);
  le_address_manager_->Register(clients[1].get());
  clients[0].get()->WaitForResume();
  clients[1].get()->WaitForResume();
}

TEST_F(LeAddressManagerWithSingleClientTest, PrepareToRotateAddress) {
  // At the start of the test, the client should be resumed.
  ASSERT_FALSE(clients[0]->paused);

  // Trigger the address rotation.
  le_address_manager_->PrepareToRotateAddress();
  sync_handler(handler_);

  // The client should have been paused and should have acked the pause.
  // This triggers the execution of the rotation command.
  ASSERT_TRUE(clients[0]->paused);

  // Verify that a new random address is set.
  hci_layer_->GetCommand(OpCode::LE_SET_RANDOM_ADDRESS);

  // Send the command complete event.
  hci_layer_->IncomingEvent(LeSetRandomAddressCompleteBuilder::Create(0x01, ErrorCode::SUCCESS));

  // After the command is complete, the client should be resumed.
  clients[0]->WaitForResume();
  ASSERT_FALSE(clients[0]->paused);
}

}  // namespace
}  // namespace hci
}  // namespace bluetooth
