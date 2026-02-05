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

#include "hci/acl_manager/le_connection_callbacks.h"
#include "hci/address.h"
#include "hci/address_with_type.h"
#include "hci/hci_packets.h"
#include "hci/le_address_manager.h"
#include "os/handler.h"

namespace bluetooth {
namespace hci {

class AclManagerLe {
public:
  virtual ~AclManagerLe() = default;

  virtual void Dump(int /*fd*/) const = 0;

  // Should register only once when user module starts.
  virtual void RegisterLeCallbacks(acl_manager::LeConnectionCallbacks* callbacks,
                                   os::Handler* handler) = 0;
  virtual void UnregisterLeCallbacks(acl_manager::LeConnectionCallbacks* callbacks,
                                     std::promise<void> promise) = 0;


  // ***!!!***WARNING***!!!***

  // THIS API IS JUST FOR CONNECTION MANAGER!
  // NEVER USE THIS METHODS OUTSIDE OF CONNECTION MANAGER!
  // WANT A CONNECTION ? GO THROUGH CONNECTION MANAGER!

  // Generates OnLeConnectSuccess if connected, or OnLeConnectFail otherwise
  virtual void CreateLeConnection(AddressWithType address_with_type, bool is_direct,
                                  bool prefer_relax_mode) = 0;
  // Cancell all attempts to connect to device
  virtual void CancelLeConnect(AddressWithType address_with_type) = 0;
  // Cancel pending direct connection. If background connection is in progress, it's preserved
  virtual void CancelDirectConnect(AddressWithType address_with_type) = 0;

  // ***!!!***END OF WARNING***!!!***


  virtual void SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
          std::chrono::milliseconds minimum_rotation_time,
          std::chrono::milliseconds maximum_rotation_time) = 0;

  // TODO(jpawlowski): remove once we have config file abstraction in cert tests
  virtual void SetPrivacyPolicyForInitiatorAddressForTest(
          LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
          Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
          std::chrono::milliseconds maximum_rotation_time) = 0;

  virtual void RemoveFromBackgroundList(AddressWithType address_with_type) = 0;

  virtual void ClearFilterAcceptList() = 0;

  virtual void AddDeviceToResolvingList(AddressWithType address_with_type,
                                        const std::array<uint8_t, 16>& peer_irk,
                                        const std::array<uint8_t, 16>& local_irk) = 0;
  virtual void RemoveDeviceFromResolvingList(AddressWithType address_with_type) = 0;
  virtual void ClearResolvingList() = 0;

  virtual LeAddressManager* GetLeAddressManager() = 0;

  // Virtual ACL disconnect emitted during suspend.
  virtual void OnLeSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) = 0;
  virtual void SetSystemSuspendState(bool suspended, std::promise<void> promise) = 0;
  virtual Address HACK_GetLeAddress(uint16_t connection_handle) = 0;
};

}  // namespace hci
}  // namespace bluetooth
