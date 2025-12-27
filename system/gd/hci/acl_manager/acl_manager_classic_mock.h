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

#include "hci/acl_manager/acl_manager_classic.h"
#include "hci/acl_manager/classic_acl_connection.h"

// Unit test interfaces
namespace bluetooth::hci::acl_manager {
namespace testing {

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

class MockAclManagerClassic : public AclManagerClassic {
public:
  MOCK_METHOD(void, RegisterCallbacks,
              (acl_manager::ConnectionCallbacks * callbacks, os::Handler* handler), (override));
  MOCK_METHOD(void, UnregisterCallbacks,
              (acl_manager::ConnectionCallbacks * callbacks, std::promise<void> promise),
              (override));
  MOCK_METHOD(void, CreateConnection, (Address address, uint16_t clock_offset), (override));
  MOCK_METHOD(void, CancelConnect, (Address address), (override));
  MOCK_METHOD(void, CentralLinkKey, (KeyFlag key_flag), (override));
  MOCK_METHOD(void, SwitchRole, (Address address, Role role), (override));
  MOCK_METHOD(uint16_t, ReadDefaultLinkPolicySettings, (), (override));
  MOCK_METHOD(void, WriteDefaultLinkPolicySettings, (uint16_t default_link_policy_settings),
              (override));
  // Virtual ACL disconnect emitted during suspend.
  MOCK_METHOD(void, OnClassicSuspendInitiatedDisconnect, (uint16_t handle, ErrorCode reason),
              (override));
};

}  // namespace testing
}  // namespace bluetooth::hci::acl_manager
