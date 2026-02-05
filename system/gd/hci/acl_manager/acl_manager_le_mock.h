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

#include "hci/acl_manager/acl_manager_le.h"

// Unit test interfaces
namespace bluetooth {
namespace hci {
namespace testing {

using acl_manager::LeAclConnection;
using acl_manager::LeConnectionCallbacks;
using acl_manager::LeConnectionManagementCallbacks;

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

class MockAclManager : public AclManagerLe {
public:
  MOCK_METHOD(void, Dump, (int /*fd*/), (const override));
  MOCK_METHOD(void, RegisterLeCallbacks,
              (acl_manager::LeConnectionCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(void, UnregisterLeCallbacks,
              (acl_manager::LeConnectionCallbacks * callbacks, std::promise<void> promise),
              (override));
  MOCK_METHOD(void, CreateLeConnection,
              (AddressWithType address_with_type, bool is_direct, bool prefer_relax_mode),
              (override));
  MOCK_METHOD(void, CancelLeConnect, (AddressWithType address_with_type), (override));
  MOCK_METHOD(void, CancelDirectConnect, (AddressWithType address_with_type), (override));
  MOCK_METHOD(void, SetPrivacyPolicyForInitiatorAddress,
              (LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
               std::chrono::milliseconds minimum_rotation_time,
               std::chrono::milliseconds maximum_rotation_time),
              (override));
  MOCK_METHOD(void, SetPrivacyPolicyForInitiatorAddressForTest,
              (LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
               Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
               std::chrono::milliseconds maximum_rotation_time),
              (override));
  MOCK_METHOD(void, RemoveFromBackgroundList, (AddressWithType address_with_type), (override));
  MOCK_METHOD(void, ClearFilterAcceptList, (), (override));
  MOCK_METHOD(void, AddDeviceToResolvingList,
              (AddressWithType, (const std::array<uint8_t, 16>&), (const std::array<uint8_t, 16>&)),
              (override));
  MOCK_METHOD(void, RemoveDeviceFromResolvingList, (AddressWithType address_with_type), (override));
  MOCK_METHOD(void, ClearResolvingList, (), (override));
  MOCK_METHOD(LeAddressManager*, GetLeAddressManager, (), (override));
  MOCK_METHOD(void, OnLeSuspendInitiatedDisconnect, (uint16_t handle, ErrorCode reason),
              (override));
  MOCK_METHOD(void, SetSystemSuspendState, (bool suspended, std::promise<void> promise),
              (override));
  MOCK_METHOD(Address, HACK_GetLeAddress, (uint16_t connection_handle), (override));
};

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
