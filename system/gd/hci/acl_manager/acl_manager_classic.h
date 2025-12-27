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

#include "hci/acl_manager/connection_callbacks.h"
#include "hci/address.h"
#include "hci/hci_packets.h"
#include "os/handler.h"

namespace bluetooth::hci::acl_manager {

/* Interface for managing ACLs of Classic transport */
class AclManagerClassic {
public:
  virtual ~AclManagerClassic() = default;

  // Should register only once when user module starts.
  // Generates OnConnectSuccess when an incoming connection is established.
  virtual void RegisterCallbacks(acl_manager::ConnectionCallbacks* callbacks,
                                 os::Handler* handler) = 0;
  virtual void UnregisterCallbacks(acl_manager::ConnectionCallbacks* callbacks,
                                   std::promise<void> promise) = 0;

  // Generates OnConnectSuccess if connected, or OnConnectFail otherwise
  virtual void CreateConnection(Address address, uint16_t clock_offset) = 0;

  // Generates OnConnectFail with error code "terminated by local host 0x16" if
  // cancelled, or OnConnectSuccess if not successfully cancelled and already
  // connected
  virtual void CancelConnect(Address address) = 0;

  virtual void CentralLinkKey(KeyFlag key_flag) = 0;
  virtual void SwitchRole(Address address, Role role) = 0;
  virtual uint16_t ReadDefaultLinkPolicySettings() = 0;
  virtual void WriteDefaultLinkPolicySettings(uint16_t default_link_policy_settings) = 0;

  // Virtual ACL disconnect emitted during suspend.
  virtual void OnClassicSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) = 0;
};

}  // namespace bluetooth::hci::acl_manager
