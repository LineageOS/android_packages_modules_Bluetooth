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

#include <future>
#include <memory>

#include "hci/acl_manager/acl_manager_classic.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/acl_manager/classic_acl_count_provider.h"
#include "hci/acl_manager/classic_impl.h"
#include "hci/acl_manager/connection_callbacks.h"
#include "hci/acl_manager/round_robin_scheduler.h"
#include "hci/address.h"
#include "hci/classic_acl_data_consumer.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "hci/remote_name_request.h"
#include "os/handler.h"

namespace bluetooth {
namespace shim {
namespace legacy {
class Acl;
}  // namespace legacy

class Btm;
bool L2CA_SetAclPriority(uint16_t, bool);
}  // namespace shim

namespace hci::acl_manager {

class AclManagerClassicImpl : public AclManagerClassic,
                              public ClassicAclCountProvider,
                              public ClassicAclDataConsumer {
  friend class bluetooth::shim::legacy::Acl;

public:
  AclManagerClassicImpl(os::Handler* handler, HciInterface& hci_interface,
                        acl_manager::AclScheduler& acl_scheduler,
                        RemoteNameRequestModule& remote_name_request_module,
                        acl_manager::RoundRobinScheduler& round_robin_scheduler);
  AclManagerClassicImpl(const AclManagerClassicImpl&) = delete;
  AclManagerClassicImpl& operator=(const AclManagerClassicImpl&) = delete;

  // NOTE: It is necessary to forward declare a default destructor that
  // overrides the base class one, because "struct impl" is forwarded declared
  // in .cc and compiler needs a concrete definition of "struct impl" when
  // compiling AclManagerClassicImpl's destructor. Hence we need to forward declare the
  // destructor for AclManagerClassicImpl to delay compiling AclManagerClassicImpl's destructor
  // until it starts linking the .cc file.
  virtual ~AclManagerClassicImpl();

  // Should register only once when user module starts.
  // Generates OnConnectSuccess when an incoming connection is established.
  void RegisterCallbacks(acl_manager::ConnectionCallbacks* callbacks,
                         os::Handler* handler) override;
  void UnregisterCallbacks(acl_manager::ConnectionCallbacks* callbacks,
                           std::promise<void> promise) override;

  // Generates OnConnectSuccess if connected, or OnConnectFail otherwise
  void CreateConnection(Address address, uint16_t clock_offset) override;

  // Generates OnConnectFail with error code "terminated by local host 0x16" if
  // cancelled, or OnConnectSuccess if not successfully cancelled and already
  // connected
  void CancelConnect(Address address) override;

  void CentralLinkKey(KeyFlag key_flag) override;
  void SwitchRole(Address address, Role role) override;
  uint16_t ReadDefaultLinkPolicySettings() override;
  void WriteDefaultLinkPolicySettings(uint16_t default_link_policy_settings) override;

  // Virtual ACL disconnect emitted during suspend.
  void OnClassicSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) override;

  // ClassicAclCountProvider
  size_t GetAclCount() override;

  // ClassicAclDataConsumer
  bool SendPacketUpward(uint16_t handle,
                        std::function<void(struct acl_manager::assembler* assembler)> cb) override;

private:
  uint16_t HACK_GetHandle(const Address address);

  os::Handler* handler_ = nullptr;
  acl_manager::classic_impl classic_impl_;
  uint16_t default_link_policy_settings_ = 0xffff;
};

}  // namespace hci::acl_manager
}  // namespace bluetooth
