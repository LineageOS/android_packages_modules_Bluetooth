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

#include <bluetooth/log.h>

#include <future>
#include <memory>

#include "hci/acl_manager/acl_manager_le.h"
#include "hci/acl_manager/classic_acl_count_provider.h"
#include "hci/acl_manager/le_connection_callbacks.h"
#include "hci/acl_manager/le_impl.h"
#include "hci/acl_manager/round_robin_scheduler.h"
#include "hci/address.h"
#include "hci/address_with_type.h"
#include "hci/controller.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "hci/le_acl_data_consumer.h"
#include "hci/le_address_manager.h"
#include "hci/le_on_advertising_set_terminated_interface.h"
#include "os/handler.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace shim {
namespace legacy {
class Acl;
}  // namespace legacy

class Btm;
bool L2CA_SetAclPriority(uint16_t, bool);
}  // namespace shim

namespace hci::acl_manager {

class AclManagerLeImpl : public AclManagerLe,
                         public hci::OnAdvertisingSetTerminatedInterface,
                         public LeAclDataConsumer {
  friend class bluetooth::shim::legacy::Acl;
  friend bool bluetooth::shim::L2CA_SetAclPriority(uint16_t, bool);

public:
  AclManagerLeImpl(os::Handler* handler, hci::HciInterface& hci_interface,
                   hci::Controller& controller, storage::StorageModule& storage_module,
                   acl_manager::RoundRobinScheduler& round_robin_scheduler,
                   acl_manager::ClassicAclCountProvider& classic_acl_count_provider);
  AclManagerLeImpl(const AclManagerLeImpl&) = delete;
  AclManagerLeImpl& operator=(const AclManagerLeImpl&) = delete;

  // NOTE: It is necessary to forward declare a default destructor that
  // overrides the base class one, because "struct impl" is forwarded declared
  // in .cc and compiler needs a concrete definition of "struct impl" when
  // compiling AclManagerLeImpl's destructor. Hence we need to forward declare the
  // destructor for AclManagerLeImpl to delay compiling AclManagerLeImpl's destructor until
  // it starts linking the .cc file.
  virtual ~AclManagerLeImpl() { log::verbose("AclManagerLe module stopped !!"); }

  void Dump(int fd) const override;

  // Should register only once when user module starts.
  void RegisterLeCallbacks(acl_manager::LeConnectionCallbacks* callbacks,
                           os::Handler* handler) override;
  void UnregisterLeCallbacks(acl_manager::LeConnectionCallbacks* callbacks,
                             std::promise<void> promise) override;

  // Generates OnLeConnectSuccess if connected, or OnLeConnectFail otherwise
  void CreateLeConnection(AddressWithType address_with_type, bool is_direct,
                          bool prefer_relax_mode) override;
  void CancelLeConnect(AddressWithType address_with_type) override;
  void CancelDirectConnect(AddressWithType address_with_type) override;

  void SetPrivacyPolicyForInitiatorAddress(
          LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
          std::chrono::milliseconds minimum_rotation_time,
          std::chrono::milliseconds maximum_rotation_time) override;

  // TODO(jpawlowski): remove once we have config file abstraction in cert tests
  void SetPrivacyPolicyForInitiatorAddressForTest(
          LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
          Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
          std::chrono::milliseconds maximum_rotation_time) override;

  void RemoveFromBackgroundList(AddressWithType address_with_type) override;

  void ClearFilterAcceptList() override;

  void AddDeviceToResolvingList(AddressWithType address_with_type,
                                const std::array<uint8_t, 16>& peer_irk,
                                const std::array<uint8_t, 16>& local_irk) override;
  void RemoveDeviceFromResolvingList(AddressWithType address_with_type) override;
  void ClearResolvingList() override;

  // Callback from Advertising Manager to notify the advitiser (local) address
  void OnAdvertisingSetTerminated(ErrorCode status, uint16_t conn_handle, uint8_t adv_set_id,
                                  hci::AddressWithType adv_address, bool is_discoverable) override;

  LeAddressManager* GetLeAddressManager() override;

  // Virtual ACL disconnect emitted during suspend.
  void OnLeSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) override;
  void SetSystemSuspendState(bool suspended, std::promise<void> promise) override;

  Address HACK_GetLeAddress(uint16_t connection_handle) override;

  // LeAclDataConsumer
  bool SendPacketUpward(uint16_t handle,
                        std::function<void(struct acl_manager::assembler* assembler)> cb) override {
    return le_impl_.send_packet_upward(handle, cb);
  }

private:
  void HACK_SetAclTxPriority(uint8_t handle, bool high_priority);

  template <typename OutputT>
  void dump(OutputT&& out) const;

  os::Handler* handler_ = nullptr;
  storage::StorageModule& storage_module_;
  acl_manager::RoundRobinScheduler& round_robin_scheduler_;

  acl_manager::le_impl le_impl_;
};

}  // namespace hci::acl_manager
}  // namespace bluetooth
