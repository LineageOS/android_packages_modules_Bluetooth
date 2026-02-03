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

#include "hci/acl_manager/acl_manager_le_impl.h"

#include <format>
#include <string>

#include "common/byte_array.h"
#include "hci/acl_manager/le_impl.h"
#include "storage/config_keys.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace hci::acl_manager {

using acl_manager::le_impl;
using acl_manager::LeConnectionCallbacks;

using acl_manager::RoundRobinScheduler;

constexpr bool crash_on_unknown_handle = false;

AclManagerLeImpl::AclManagerLeImpl(os::Handler* handler, hci::HciInterface& hci,
                                   hci::Controller& controller,
                                   storage::StorageModule& storage_module,
                                   acl_manager::RoundRobinScheduler& round_robin_scheduler,
                                   acl_manager::ClassicAclCountProvider& classic_acl_count_provider)
    : handler_(handler),
      storage_module_(storage_module),
      round_robin_scheduler_(round_robin_scheduler),
      le_impl_(hci, controller, handler_, round_robin_scheduler, storage_module,
               crash_on_unknown_handle, classic_acl_count_provider) {
  hci.SetLeAclDataConsumer(this);
  log::verbose("AclManagerLe module started !!");
}

void AclManagerLeImpl::RegisterLeCallbacks(LeConnectionCallbacks* callbacks, os::Handler* handler) {
  log::assert_that(callbacks != nullptr && handler != nullptr,
                   "assert failed: callbacks != nullptr && handler != nullptr");
  handler_->CallOn(&le_impl_, &le_impl::handle_register_le_callbacks, common::Unretained(callbacks),
                   common::Unretained(handler));
}

void AclManagerLeImpl::UnregisterLeCallbacks(LeConnectionCallbacks* callbacks,
                                             std::promise<void> promise) {
  log::assert_that(callbacks != nullptr, "assert failed: callbacks != nullptr");
  handler_->CallOn(&le_impl_, &le_impl::handle_unregister_le_callbacks,
                   common::Unretained(callbacks), std::move(promise));
}

void AclManagerLeImpl::CreateLeConnection(AddressWithType address_with_type, bool is_direct,
                                          bool prefer_relax_mode) {
  if (!is_direct) {
    handler_->CallOn(&le_impl_, &le_impl::add_device_to_background_connection_list,
                     address_with_type);
  }
  handler_->CallOn(&le_impl_, &le_impl::create_le_connection, address_with_type, true, is_direct,
                   prefer_relax_mode);
}

void AclManagerLeImpl::CancelLeConnect(AddressWithType address_with_type) {
  handler_->CallOn(&le_impl_, &le_impl::remove_device_from_background_connection_list,
                   address_with_type);
  handler_->CallOn(&le_impl_, &le_impl::cancel_connect, address_with_type);
}

void AclManagerLeImpl::CancelDirectConnect(AddressWithType address_with_type) {
  handler_->CallOn(&le_impl_, &le_impl::direct_connect_remove, address_with_type);
}

void AclManagerLeImpl::SetPrivacyPolicyForInitiatorAddress(
        LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
        std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  Octet16 rotation_irk{};
  auto irk_prop = storage_module_.GetProperty(BTIF_STORAGE_SECTION_ADAPTER,
                                              BTIF_STORAGE_KEY_LE_LOCAL_KEY_IRK);
  if (irk_prop.has_value()) {
    auto irk = common::ByteArray<16>::FromString(irk_prop.value());
    if (irk.has_value()) {
      rotation_irk = irk->bytes;
    }
  }
  handler_->CallOn(&le_impl_, &le_impl::set_privacy_policy_for_initiator_address, address_policy,
                   fixed_address, rotation_irk, minimum_rotation_time, maximum_rotation_time);
}

// TODO(jpawlowski): remove once we have config file abstraction in cert tests
void AclManagerLeImpl::SetPrivacyPolicyForInitiatorAddressForTest(
        LeAddressManager::AddressPolicy address_policy, AddressWithType fixed_address,
        Octet16 rotation_irk, std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  handler_->CallOn(&le_impl_, &le_impl::set_privacy_policy_for_initiator_address_for_test,
                   address_policy, fixed_address, rotation_irk, minimum_rotation_time,
                   maximum_rotation_time);
}

void AclManagerLeImpl::RemoveFromBackgroundList(AddressWithType address_with_type) {
  handler_->CallOn(&le_impl_, &le_impl::remove_device_from_background_connection_list,
                   address_with_type);
}

void AclManagerLeImpl::ClearFilterAcceptList() {
  handler_->CallOn(&le_impl_, &le_impl::clear_filter_accept_list);
}

void AclManagerLeImpl::AddDeviceToResolvingList(AddressWithType address_with_type,
                                                const std::array<uint8_t, 16>& peer_irk,
                                                const std::array<uint8_t, 16>& local_irk) {
  handler_->CallOn(&le_impl_, &le_impl::add_device_to_resolving_list, address_with_type, peer_irk,
                   local_irk);
}

void AclManagerLeImpl::RemoveDeviceFromResolvingList(AddressWithType address_with_type) {
  handler_->CallOn(&le_impl_, &le_impl::remove_device_from_resolving_list, address_with_type);
}

void AclManagerLeImpl::ClearResolvingList() {
  handler_->CallOn(&le_impl_, &le_impl::clear_resolving_list);
}

void AclManagerLeImpl::OnAdvertisingSetTerminated(ErrorCode status, uint16_t conn_handle,
                                                  uint8_t adv_set_id,
                                                  hci::AddressWithType adv_address,
                                                  bool is_discoverable) {
  if (status == ErrorCode::SUCCESS) {
    handler_->CallOn(&le_impl_, &le_impl::OnAdvertisingSetTerminated, conn_handle, adv_set_id,
                     adv_address, is_discoverable);
  }
}

void AclManagerLeImpl::OnLeSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) {
  handler_->CallOn(&le_impl_, &le_impl::on_le_disconnect, handle, reason);
}

void AclManagerLeImpl::SetSystemSuspendState(bool suspended, std::promise<void> promise) {
  handler_->CallOn(&le_impl_, &le_impl::set_system_suspend_state, suspended, std::move(promise));
}

LeAddressManager* AclManagerLeImpl::GetLeAddressManager() { return le_impl_.le_address_manager_; }

Address AclManagerLeImpl::HACK_GetLeAddress(uint16_t connection_handle) {
  return le_impl_.HACK_get_address(connection_handle);
}

void AclManagerLeImpl::HACK_SetAclTxPriority(uint8_t handle, bool high_priority) {
  handler_->CallOn(&round_robin_scheduler_, &RoundRobinScheduler::SetLinkPriority, handle,
                   high_priority);
}

template <typename OutputT>
void AclManagerLeImpl::dump(OutputT&& out) const {
  auto& accept_list = le_impl_.accept_list;
  const auto le_connectability_state_text =
          connectability_state_machine_text(le_impl_.connectability_state_);

  std::format_to(out, "\nACL Manager Dumpsys:\n");
  std::format_to(out,
                 "    le_connectability_state: \"{}\"\n"
                 "    le_filter_accept_list_count: {}\n"
                 "    le_filter_accept_list: [",
                 le_connectability_state_text, accept_list.size());
  for (const auto& it : accept_list) {
    std::format_to(out, "\n        \"{}\",", it.ToString());
  }
  std::format_to(out, "\n    ]\n");
}

void AclManagerLeImpl::Dump(int fd) const {
  std::string out;
  dump(std::back_inserter(out));
  dprintf(fd, "%s", out.c_str());
}

}  // namespace hci::acl_manager
}  // namespace bluetooth
