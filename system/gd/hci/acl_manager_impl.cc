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

#include "hci/acl_manager_impl.h"

#include <bluetooth/log.h>

#include <atomic>
#include <format>
#include <future>
#include <mutex>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

#include "common/bidi_queue.h"
#include "common/byte_array.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/acl_manager/classic_impl.h"
#include "hci/acl_manager/le_acl_connection.h"
#include "hci/acl_manager/le_impl.h"
#include "hci/acl_manager/round_robin_scheduler.h"
#include "main/shim/entry.h"
#include "storage/config_keys.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace hci {

constexpr uint16_t kQualcommDebugHandle = 0xedc;
constexpr uint16_t kSamsungDebugHandle = 0xeef;

using common::Bind;
using common::BindOnce;

using acl_manager::classic_impl;
using acl_manager::ConnectionCallbacks;

using acl_manager::le_impl;
using acl_manager::LeConnectionCallbacks;

using acl_manager::AclScheduler;
using acl_manager::RoundRobinScheduler;

struct AclManagerImpl::impl {
  explicit impl(const AclManagerImpl& acl_manager, os::Handler* handler,
                HciInterface* hci_interface, Controller* controller, AclScheduler* acl_scheduler,
                RemoteNameRequestModule* remote_name_request_module)
      : acl_manager_(acl_manager) {
    handler_ = handler;
    hci_layer_ = hci_interface;
    controller_ = controller;
    round_robin_scheduler_ =
            new RoundRobinScheduler(handler_, controller_, hci_layer_->GetAclQueueEnd());
    acl_scheduler_ = acl_scheduler;
    remote_name_request_module_ = remote_name_request_module;

    bool crash_on_unknown_handle = false;
    {
      const std::lock_guard<std::mutex> lock(dumpsys_mutex_);
      classic_impl_ = new classic_impl(hci_layer_, controller_, handler_, round_robin_scheduler_,
                                       crash_on_unknown_handle, acl_scheduler_,
                                       remote_name_request_module_);
      le_impl_ = new le_impl(hci_layer_, controller_, handler_, round_robin_scheduler_,
                             crash_on_unknown_handle, classic_impl_);
    }

    hci_queue_end_ = hci_layer_->GetAclQueueEnd();
    hci_queue_end_->RegisterDequeue(handler_,
                                    common::Bind(&impl::dequeue_and_route_acl_packet_to_connection,
                                                 common::Unretained(this)));
  }

  ~impl() {
    hci_queue_end_->UnregisterDequeue();
    if (enqueue_registered_.exchange(false)) {
      hci_queue_end_->UnregisterEnqueue();
    }

    {
      const std::lock_guard<std::mutex> lock(dumpsys_mutex_);
      delete le_impl_;
      delete classic_impl_;
      le_impl_ = nullptr;
      classic_impl_ = nullptr;
    }

    unknown_acl_alarm_.reset();
    waiting_packets_.clear();

    delete round_robin_scheduler_;
    hci_queue_end_ = nullptr;
    handler_ = nullptr;
    hci_layer_ = nullptr;
    acl_scheduler_ = nullptr;
  }

  void retry_unknown_acl(bool timed_out) {
    std::vector<AclView> unsent_packets;
    for (const auto& itr : waiting_packets_) {
      auto handle = itr.GetHandle();
      if (!classic_impl_->send_packet_upward(handle,
                                             [itr](struct acl_manager::assembler* assembler) {
                                               assembler->on_incoming_packet(itr);
                                             }) &&
          !le_impl_->send_packet_upward(handle, [itr](struct acl_manager::assembler* assembler) {
            assembler->on_incoming_packet(itr);
          })) {
        if (!timed_out) {
          unsent_packets.push_back(itr);
        } else {
          log::error("Dropping packet of size {} to unknown connection 0x{:x}", itr.size(),
                     itr.GetHandle());
        }
      }
    }
    waiting_packets_ = std::move(unsent_packets);
  }

  static void on_unknown_acl_timer(struct AclManagerImpl::impl* impl) {
    log::info("Timer fired!");
    impl->retry_unknown_acl(/* timed_out = */ true);
    impl->unknown_acl_alarm_.reset();
  }

  // Invoked from some external Queue Reactable context 2
  void dequeue_and_route_acl_packet_to_connection() {
    // Retry any waiting packets first
    if (!waiting_packets_.empty()) {
      retry_unknown_acl(/* timed_out = */ false);
    }

    auto packet = hci_queue_end_->TryDequeue();
    log::assert_that(packet != nullptr, "assert failed: packet != nullptr");
    if (!packet->IsValid()) {
      log::info("Dropping invalid packet of size {}", packet->size());
      return;
    }
    uint16_t handle = packet->GetHandle();
    if (handle == kQualcommDebugHandle || handle == kSamsungDebugHandle) {
      return;
    }
    if (classic_impl_->send_packet_upward(handle,
                                          [&packet](struct acl_manager::assembler* assembler) {
                                            assembler->on_incoming_packet(*packet);
                                          })) {
      return;
    }
    if (le_impl_->send_packet_upward(handle, [&packet](struct acl_manager::assembler* assembler) {
          assembler->on_incoming_packet(*packet);
        })) {
      return;
    }
    if (unknown_acl_alarm_ == nullptr) {
      unknown_acl_alarm_.reset(new os::Alarm(handler_));
    }
    waiting_packets_.push_back(*packet);
    log::info("Saving packet of size {} to unknown connection 0x{:x}", packet->size(),
              packet->GetHandle());
    unknown_acl_alarm_->Schedule(BindOnce(&on_unknown_acl_timer, common::Unretained(this)),
                                 kWaitBeforeDroppingUnknownAcl);
  }

  template <typename OutputT>
  void dump(OutputT&& out) const;

  const AclManagerImpl& acl_manager_;

  classic_impl* classic_impl_ = nullptr;
  le_impl* le_impl_ = nullptr;
  AclScheduler* acl_scheduler_ = nullptr;
  RemoteNameRequestModule* remote_name_request_module_ = nullptr;
  os::Handler* handler_ = nullptr;
  Controller* controller_ = nullptr;
  HciInterface* hci_layer_ = nullptr;
  RoundRobinScheduler* round_robin_scheduler_ = nullptr;
  common::BidiQueueEnd<AclBuilder, AclView>* hci_queue_end_ = nullptr;
  std::atomic_bool enqueue_registered_ = false;
  uint16_t default_link_policy_settings_ = 0xffff;
  mutable std::mutex dumpsys_mutex_;
  std::unique_ptr<os::Alarm> unknown_acl_alarm_;
  std::vector<AclView> waiting_packets_;
  static constexpr std::chrono::seconds kWaitBeforeDroppingUnknownAcl{1};
};

void AclManagerImpl::RegisterCallbacks(ConnectionCallbacks* callbacks, os::Handler* handler) {
  log::assert_that(callbacks != nullptr && handler != nullptr,
                   "assert failed: callbacks != nullptr && handler != nullptr");
  pimpl_->handler_->Post(common::BindOnce(
          &classic_impl::handle_register_callbacks, common::Unretained(pimpl_->classic_impl_),
          common::Unretained(callbacks), common::Unretained(handler)));
}

void AclManagerImpl::UnregisterCallbacks(ConnectionCallbacks* callbacks,
                                         std::promise<void> promise) {
  log::assert_that(callbacks != nullptr, "assert failed: callbacks != nullptr");
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::handle_unregister_callbacks,
                           common::Unretained(callbacks), std::move(promise));
}

void AclManagerImpl::RegisterLeCallbacks(LeConnectionCallbacks* callbacks, os::Handler* handler) {
  log::assert_that(callbacks != nullptr && handler != nullptr,
                   "assert failed: callbacks != nullptr && handler != nullptr");
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::handle_register_le_callbacks,
                           common::Unretained(callbacks), common::Unretained(handler));
}

void AclManagerImpl::UnregisterLeCallbacks(LeConnectionCallbacks* callbacks,
                                           std::promise<void> promise) {
  log::assert_that(callbacks != nullptr, "assert failed: callbacks != nullptr");
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::handle_unregister_le_callbacks,
                           common::Unretained(callbacks), std::move(promise));
}

void AclManagerImpl::CreateConnection(Address address) {
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::create_connection, address);
}

void AclManagerImpl::CreateLeConnection(AddressWithType address_with_type, bool is_direct,
                                        bool prefer_relax_mode) {
  if (!is_direct) {
    pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::add_device_to_background_connection_list,
                             address_with_type);
  }
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::create_le_connection, address_with_type,
                           true, is_direct, prefer_relax_mode);
}

void AclManagerImpl::SetPrivacyPolicyForInitiatorAddress(
        LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
        std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  Octet16 rotation_irk{};
  auto irk_prop = shim::GetStorage()->GetProperty(BTIF_STORAGE_SECTION_ADAPTER,
                                                  BTIF_STORAGE_KEY_LE_LOCAL_KEY_IRK);
  if (irk_prop.has_value()) {
    auto irk = common::ByteArray<16>::FromString(irk_prop.value());
    if (irk.has_value()) {
      rotation_irk = irk->bytes;
    }
  }
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::set_privacy_policy_for_initiator_address,
                           address_policy, fixed_address, rotation_irk, minimum_rotation_time,
                           maximum_rotation_time);
}

// TODO(jpawlowski): remove once we have config file abstraction in cert tests
void AclManagerImpl::SetPrivacyPolicyForInitiatorAddressForTest(
        LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
        Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_,
                           &le_impl::set_privacy_policy_for_initiator_address_for_test,
                           address_policy, fixed_address, rotation_irk, minimum_rotation_time,
                           maximum_rotation_time);
}

void AclManagerImpl::CancelConnect(Address address) {
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::cancel_connect, address);
}

void AclManagerImpl::CancelLeConnect(AddressWithType address_with_type) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_,
                           &le_impl::remove_device_from_background_connection_list,
                           address_with_type);
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::cancel_connect, address_with_type);
}

void AclManagerImpl::RemoveFromBackgroundList(AddressWithType address_with_type) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_,
                           &le_impl::remove_device_from_background_connection_list,
                           address_with_type);
}

void AclManagerImpl::ClearFilterAcceptList() {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::clear_filter_accept_list);
}

void AclManagerImpl::AddDeviceToResolvingList(AddressWithType address_with_type,
                                              const std::array<uint8_t, 16>& peer_irk,
                                              const std::array<uint8_t, 16>& local_irk) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::add_device_to_resolving_list,
                           address_with_type, peer_irk, local_irk);
}

void AclManagerImpl::RemoveDeviceFromResolvingList(AddressWithType address_with_type) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::remove_device_from_resolving_list,
                           address_with_type);
}

void AclManagerImpl::ClearResolvingList() {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::clear_resolving_list);
}

void AclManagerImpl::CentralLinkKey(KeyFlag key_flag) {
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::central_link_key, key_flag);
}

void AclManagerImpl::SwitchRole(Address address, Role role) {
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::switch_role, address, role);
}

uint16_t AclManagerImpl::ReadDefaultLinkPolicySettings() {
  log::assert_that(pimpl_->default_link_policy_settings_ != 0xffff, "Settings were never written");
  return pimpl_->default_link_policy_settings_;
}

void AclManagerImpl::WriteDefaultLinkPolicySettings(uint16_t default_link_policy_settings) {
  pimpl_->default_link_policy_settings_ = default_link_policy_settings;
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::write_default_link_policy_settings,
                           default_link_policy_settings);
}

void AclManagerImpl::OnAdvertisingSetTerminated(ErrorCode status, uint16_t conn_handle,
                                                uint8_t adv_set_id,
                                                hci::AddressWithType adv_address,
                                                bool is_discoverable) {
  if (status == ErrorCode::SUCCESS) {
    pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::OnAdvertisingSetTerminated, conn_handle,
                             adv_set_id, adv_address, is_discoverable);
  }
}

void AclManagerImpl::OnClassicSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) {
  pimpl_->handler_->CallOn(pimpl_->classic_impl_, &classic_impl::on_classic_disconnect, handle,
                           reason);
}

void AclManagerImpl::OnLeSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::on_le_disconnect, handle, reason);
}

void AclManagerImpl::SetSystemSuspendState(bool suspended) {
  pimpl_->handler_->CallOn(pimpl_->le_impl_, &le_impl::set_system_suspend_state, suspended);
}

LeAddressManager* AclManagerImpl::GetLeAddressManager() {
  return pimpl_->le_impl_->le_address_manager_;
}

uint16_t AclManagerImpl::HACK_GetHandle(Address address) {
  return pimpl_->classic_impl_->HACK_get_handle(address);
}

Address AclManagerImpl::HACK_GetLeAddress(uint16_t connection_handle) {
  return pimpl_->le_impl_->HACK_get_address(connection_handle);
}

void AclManagerImpl::HACK_SetAclTxPriority(uint8_t handle, bool high_priority) {
  pimpl_->handler_->CallOn(pimpl_->round_robin_scheduler_, &RoundRobinScheduler::SetLinkPriority,
                           handle, high_priority);
}

AclManagerImpl::AclManagerImpl(os::Handler* handler, HciInterface* hci_interface,
                               Controller* controller, AclScheduler* acl_scheduler,
                               RemoteNameRequestModule* remote_name_request_module) {
  pimpl_ = std::make_unique<impl>(*this, handler, hci_interface, controller, acl_scheduler,
                                  remote_name_request_module);
}

AclManagerImpl::~AclManagerImpl() = default;

template <typename OutputT>
void AclManagerImpl::impl::dump(OutputT&& out) const {
  const std::lock_guard<std::mutex> lock(dumpsys_mutex_);
  const auto accept_list =
          (le_impl_ != nullptr) ? le_impl_->accept_list : std::unordered_set<AddressWithType>();
  const auto le_connectability_state_text =
          (le_impl_ != nullptr) ? connectability_state_machine_text(le_impl_->connectability_state_)
                                : "INDETERMINATE";
  const auto le_create_connection_timeout_alarms_count =
          (le_impl_ != nullptr)
                  ? static_cast<int>(le_impl_->create_connection_timeout_alarms_.size())
                  : 0;

  std::format_to(out, "\nACL Manager Dumpsys:\n");
  std::format_to(out,
                 "    le_connectability_state: \"{}\"\n"
                 "    le_create_connection_timeout_alarms_count: {}\n"
                 "    le_filter_accept_list_count: {}\n"
                 "    le_filter_accept_list: [",
                 le_connectability_state_text, le_create_connection_timeout_alarms_count,
                 accept_list.size());
  for (const auto& it : accept_list) {
    std::format_to(out, "\n        \"{}\",", it.ToString());
  }
  std::format_to(out, "\n    ]\n");
}

void AclManagerImpl::Dump(int fd) const {
  std::string out;
  pimpl_->dump(std::back_inserter(out));
  dprintf(fd, "%s", out.c_str());
}

}  // namespace hci
}  // namespace bluetooth
