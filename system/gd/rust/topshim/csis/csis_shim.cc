/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "gd/rust/topshim/csis/csis_shim.h"

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include <string>

#include "os/log.h"
#include "src/profiles/csis.rs.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {
namespace internal {

static CsisClientIntf* g_csis_if;

static BtCsisConnectionState to_rust_btcsis_connection_state(csis::ConnectionState state) {
  switch (state) {
    case csis::ConnectionState::DISCONNECTED:
      return BtCsisConnectionState::Disconnected;
    case csis::ConnectionState::CONNECTING:
      return BtCsisConnectionState::Connecting;
    case csis::ConnectionState::CONNECTED:
      return BtCsisConnectionState::Connected;
    case csis::ConnectionState::DISCONNECTING:
      return BtCsisConnectionState::Disconnecting;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtCsisConnectionState{};
}

static BtCsisGroupLockStatus to_rust_btcsis_group_lock_status(csis::CsisGroupLockStatus status) {
  switch (status) {
    case csis::CsisGroupLockStatus::SUCCESS:
      return BtCsisGroupLockStatus::Success;
    case csis::CsisGroupLockStatus::FAILED_INVALID_GROUP:
      return BtCsisGroupLockStatus::FailedInvalidGroup;
    case csis::CsisGroupLockStatus::FAILED_GROUP_EMPTY:
      return BtCsisGroupLockStatus::FailedGroupEmpty;
    case csis::CsisGroupLockStatus::FAILED_GROUP_NOT_CONNECTED:
      return BtCsisGroupLockStatus::FailedGroupNotConnected;
    case csis::CsisGroupLockStatus::FAILED_LOCKED_BY_OTHER:
      return BtCsisGroupLockStatus::FailedLockedByOther;
    case csis::CsisGroupLockStatus::FAILED_OTHER_REASON:
      return BtCsisGroupLockStatus::FailedOtherReason;
    case csis::CsisGroupLockStatus::LOCKED_GROUP_MEMBER_LOST:
      return BtCsisGroupLockStatus::LockedGroupMemberLost;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtCsisGroupLockStatus{};
}

static void connection_state_cb(const RawAddress& addr, csis::ConnectionState state) {
  csis_connection_state_callback(addr, to_rust_btcsis_connection_state(state));
}

static void device_available_cb(
    const RawAddress& addr, int group_id, int group_size, int rank, const bluetooth::Uuid& uuid) {
  csis_device_available_callback(addr, group_id, group_size, rank, uuid);
}

static void set_member_available_cb(const RawAddress& addr, int group_id) {
  csis_set_member_available_callback(addr, group_id);
}

static void group_lock_changed_cb(int group_id, bool locked, csis::CsisGroupLockStatus status) {
  csis_group_lock_changed_callback(group_id, locked, to_rust_btcsis_group_lock_status(status));
}
}  // namespace internal

class DBusCsisClientCallbacks : public csis::CsisClientCallbacks {
 public:
  static csis::CsisClientCallbacks* GetInstance() {
    static auto instance = new DBusCsisClientCallbacks();
    return instance;
  }

  DBusCsisClientCallbacks(){};

  void OnConnectionState(const RawAddress& addr, csis::ConnectionState state) override {
    log::info("addr={}, state={}", ADDRESS_TO_LOGGABLE_CSTR(addr), static_cast<uint8_t>(state));
    topshim::rust::internal::connection_state_cb(addr, state);
  }

  void OnDeviceAvailable(
      const RawAddress& addr,
      int group_id,
      int group_size,
      int rank,
      const bluetooth::Uuid& uuid) override {
    log::info(
        "addr={}, group_id={}, group_size={}, rank={}",
        ADDRESS_TO_LOGGABLE_CSTR(addr),
        group_id,
        group_size,
        rank);
    topshim::rust::internal::device_available_cb(addr, group_id, group_size, rank, uuid);
  }

  void OnSetMemberAvailable(const RawAddress& addr, int group_id) {
    log::info("addr={}, group_id={}", ADDRESS_TO_LOGGABLE_CSTR(addr), group_id);
    topshim::rust::internal::set_member_available_cb(addr, group_id);
  }

  void OnGroupLockChanged(int group_id, bool locked, csis::CsisGroupLockStatus status) {
    topshim::rust::internal::group_lock_changed_cb(group_id, locked, status);
  }
};

std::unique_ptr<CsisClientIntf> GetCsisClientProfile(const unsigned char* btif) {
  if (internal::g_csis_if) std::abort();

  const bt_interface_t* btif_ = reinterpret_cast<const bt_interface_t*>(btif);

  auto csis_if = std::make_unique<CsisClientIntf>(
      const_cast<csis::CsisClientInterface*>(reinterpret_cast<const csis::CsisClientInterface*>(
          btif_->get_profile_interface("csis_client"))));

  internal::g_csis_if = csis_if.get();

  return csis_if;
}

void CsisClientIntf::init(/*CsisClientCallbacks* callbacks*/) {
  return intf_->Init(DBusCsisClientCallbacks::GetInstance());
}

void CsisClientIntf::connect(RawAddress addr) {
  return intf_->Connect(addr);
}

void CsisClientIntf::disconnect(RawAddress addr) {
  return intf_->Disconnect(addr);
}

void CsisClientIntf::lock_group(int group_id, bool lock) {
  return intf_->LockGroup(group_id, lock);
}

void CsisClientIntf::remove_device(RawAddress addr) {
  return intf_->RemoveDevice(addr);
}

void CsisClientIntf::cleanup() {
  return intf_->Cleanup();
}
}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
