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

#include "hci/acl_manager/acl_manager_classic_impl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <future>

namespace bluetooth::hci::acl_manager {

using common::BindOnce;

using acl_manager::classic_impl;
using acl_manager::ConnectionCallbacks;

using acl_manager::AclScheduler;
using acl_manager::RoundRobinScheduler;

constexpr bool crash_on_unknown_handle = false;

AclManagerClassicImpl::AclManagerClassicImpl(os::Handler* handler, HciInterface& hci,
                                             AclScheduler& acl_scheduler,
                                             RemoteNameRequestModule& remote_name_request_module,
                                             RoundRobinScheduler& round_robin_scheduler)
    : handler_(handler),
      classic_impl_(hci, handler_, round_robin_scheduler, crash_on_unknown_handle, acl_scheduler,
                    remote_name_request_module) {
  hci.SetClassicAclDataConsumer(this);
  log::verbose("AclManagerClassic module started !!");
}

AclManagerClassicImpl::~AclManagerClassicImpl() {
  log::verbose("AclManagerClassic module stopped !!");
}

void AclManagerClassicImpl::RegisterCallbacks(ConnectionCallbacks* callbacks,
                                              os::Handler* handler) {
  log::assert_that(callbacks != nullptr && handler != nullptr,
                   "assert failed: callbacks != nullptr && handler != nullptr");
  handler_->Post(common::BindOnce(&classic_impl::handle_register_callbacks,
                                  common::Unretained(&classic_impl_), common::Unretained(callbacks),
                                  common::Unretained(handler)));
}

void AclManagerClassicImpl::UnregisterCallbacks(ConnectionCallbacks* callbacks,
                                                std::promise<void> promise) {
  log::assert_that(callbacks != nullptr, "assert failed: callbacks != nullptr");
  handler_->CallOn(&classic_impl_, &classic_impl::handle_unregister_callbacks,
                   common::Unretained(callbacks), std::move(promise));
}

void AclManagerClassicImpl::CreateConnection(Address address, uint16_t clock_offset) {
  handler_->CallOn(&classic_impl_, &classic_impl::create_connection, address, clock_offset);
}

void AclManagerClassicImpl::CancelConnect(Address address) {
  handler_->CallOn(&classic_impl_, &classic_impl::cancel_connect, address);
}

void AclManagerClassicImpl::CentralLinkKey(KeyFlag key_flag) {
  handler_->CallOn(&classic_impl_, &classic_impl::central_link_key, key_flag);
}

void AclManagerClassicImpl::SwitchRole(Address address, Role role) {
  handler_->CallOn(&classic_impl_, &classic_impl::switch_role, address, role);
}

uint16_t AclManagerClassicImpl::ReadDefaultLinkPolicySettings() {
  log::assert_that(default_link_policy_settings_ != 0xffff, "Settings were never written");
  return default_link_policy_settings_;
}

void AclManagerClassicImpl::WriteDefaultLinkPolicySettings(uint16_t default_link_policy_settings) {
  default_link_policy_settings_ = default_link_policy_settings;
  handler_->CallOn(&classic_impl_, &classic_impl::write_default_link_policy_settings,
                   default_link_policy_settings);
}

void AclManagerClassicImpl::OnClassicSuspendInitiatedDisconnect(uint16_t handle, ErrorCode reason) {
  handler_->CallOn(&classic_impl_, &classic_impl::on_classic_disconnect, handle, reason);
}

size_t AclManagerClassicImpl::GetAclCount() { return classic_impl_.get_connection_count(); }

bool AclManagerClassicImpl::SendPacketUpward(
        uint16_t handle, std::function<void(struct acl_manager::assembler* assembler)> cb) {
  return classic_impl_.send_packet_upward(handle, cb);
}

uint16_t AclManagerClassicImpl::HACK_GetHandle(Address address) {
  return classic_impl_.HACK_get_handle(address);
}

}  // namespace bluetooth::hci::acl_manager
