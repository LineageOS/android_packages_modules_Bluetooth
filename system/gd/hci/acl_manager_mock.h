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
#pragma once

#include <gmock/gmock.h>

#include "hci/acl_manager.h"

// Unit test interfaces
namespace bluetooth {
namespace hci {
namespace testing {

using acl_manager::LeAclConnection;
using acl_manager::LeConnectionCallbacks;
using acl_manager::LeConnectionManagementCallbacks;

using acl_manager::ClassicAclConnection;
using acl_manager::ConnectionCallbacks;
using acl_manager::ConnectionManagementCallbacks;

class MockClassicAclConnection : public ClassicAclConnection {
public:
  MOCK_METHOD(Address, GetAddress, (), (const, override));
  MOCK_METHOD(bool, Disconnect, (DisconnectReason reason), (override));
  MOCK_METHOD(void, RegisterCallbacks,
              (ConnectionManagementCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(bool, ReadRemoteVersionInformation, (), (override));
  MOCK_METHOD(bool, ReadRemoteSupportedFeatures, (), (override));
  MOCK_METHOD(bool, ReadRemoteExtendedFeatures, (uint8_t), (override));

  QueueUpEnd* GetAclQueueEnd() const override { return acl_queue_.GetUpEnd(); }
  mutable common::BidiQueue<PacketView<kLittleEndian>, BasePacketBuilder> acl_queue_{10};
};

class MockLeAclConnection : public LeAclConnection {
public:
  MOCK_METHOD(AddressWithType, GetLocalAddress, (), (const, override));
  MOCK_METHOD(AddressWithType, GetRemoteAddress, (), (const, override));
  MOCK_METHOD(void, Disconnect, (DisconnectReason reason), (override));
  MOCK_METHOD(void, RegisterCallbacks,
              (LeConnectionManagementCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(bool, ReadRemoteVersionInformation, (), (override));

  QueueUpEnd* GetAclQueueEnd() const override { return acl_queue_.GetUpEnd(); }
  mutable common::BidiQueue<PacketView<kLittleEndian>, BasePacketBuilder> acl_queue_{10};
};

class MockAclManager : public AclManager {
public:
  MOCK_METHOD(void, Dump, (int /*fd*/), (const override));

  // Should register only once when user module starts.
  // Generates OnConnectSuccess when an incoming connection is established.
  MOCK_METHOD(void, RegisterCallbacks,
              (acl_manager::ConnectionCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(void, UnregisterCallbacks,
              (acl_manager::ConnectionCallbacks * callbacks, std::promise<void> promise),
              (override));

  // Should register only once when user module starts.
  MOCK_METHOD(void, RegisterLeCallbacks,
              (acl_manager::LeConnectionCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(void, UnregisterLeCallbacks,
              (acl_manager::LeConnectionCallbacks * callbacks, std::promise<void> promise),
              (override));

  // Generates OnConnectSuccess if connected, or OnConnectFail otherwise
  MOCK_METHOD(void, CreateConnection, (Address address), (override));

  // Generates OnLeConnectSuccess if connected, or OnLeConnectFail otherwise
  MOCK_METHOD(void, CreateLeConnection,
              (AddressWithType address_with_type, bool is_direct, bool prefer_relax_mode),
              (override));

  MOCK_METHOD(void, SetPrivacyPolicyForInitiatorAddress,
              (LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
               std::chrono::milliseconds minimum_rotation_time,
               std::chrono::milliseconds maximum_rotation_time),
              (override));

  // TODO(jpawlowski): remove once we have config file abstraction in cert tests
  MOCK_METHOD(void, SetPrivacyPolicyForInitiatorAddressForTest,
              (LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
               Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
               std::chrono::milliseconds maximum_rotation_time),
              (override));

  // Generates OnConnectFail with error code "terminated by local host 0x16" if
  // cancelled, or OnConnectSuccess if not successfully cancelled and already
  // connected
  MOCK_METHOD(void, CancelConnect, (Address address), (override));
  MOCK_METHOD(void, RemoveFromBackgroundList, (AddressWithType address_with_type), (override));

  MOCK_METHOD(void, CancelLeConnect, (AddressWithType address_with_type), (override));

  MOCK_METHOD(void, ClearFilterAcceptList, (), (override));

  MOCK_METHOD(void, AddDeviceToResolvingList,
              (AddressWithType, (const std::array<uint8_t, 16>&), (const std::array<uint8_t, 16>&)),
              (override));
  MOCK_METHOD(void, RemoveDeviceFromResolvingList, (AddressWithType address_with_type), (override));
  MOCK_METHOD(void, ClearResolvingList, (), (override));

  MOCK_METHOD(void, CentralLinkKey, (KeyFlag key_flag), (override));
  MOCK_METHOD(void, SwitchRole, (Address address, Role role), (override));
  MOCK_METHOD(uint16_t, ReadDefaultLinkPolicySettings, (), (override));
  MOCK_METHOD(void, WriteDefaultLinkPolicySettings, (uint16_t default_link_policy_settings),
              (override));

  // Callback from Advertising Manager to notify the advitiser (local) address
  MOCK_METHOD(void, OnAdvertisingSetTerminated,
              (ErrorCode status, uint16_t conn_handle, uint8_t adv_set_id,
               hci::AddressWithType adv_address, bool is_discoverable),
              (override));

  MOCK_METHOD(LeAddressManager*, GetLeAddressManager, (), (override));

  // Virtual ACL disconnect emitted during suspend.
  MOCK_METHOD(void, OnClassicSuspendInitiatedDisconnect, (uint16_t handle, ErrorCode reason),
              (override));
  MOCK_METHOD(void, OnLeSuspendInitiatedDisconnect, (uint16_t handle, ErrorCode reason),
              (override));
  MOCK_METHOD(void, SetSystemSuspendState, (bool suspended), (override));
  MOCK_METHOD(Address, HACK_GetLeAddress, (uint16_t connection_handle), (override));
};

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
