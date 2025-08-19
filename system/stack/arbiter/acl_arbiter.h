/*
 * Copyright 2022 The Android Open Source Project
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

/// This class intercepts incoming connection requests and data packets, and
/// decides whether to intercept them or pass them to the legacy stack
///
/// It allows us to easily gate changes to the datapath and roll back to legacy
/// behavior if needed.

#pragma once

#include <base/thread_annotations.h>
#include <bluetooth/types/address.h>

#include <mutex>

#include "rust/cxx.h"
#include "stack/include/bt_hdr.h"

namespace bluetooth::shim::arbiter {

enum class InterceptAction {
  /// The packet should be forwarded to the legacy stack
  FORWARD,
  /// The packet should be dropped and not sent to legacy
  DROP
};

// The backing arbiter implements this interface.
class ArbiterInterface {
public:
  virtual ~ArbiterInterface() = default;

  virtual void OnLeConnect(uint8_t tcb_idx, uint16_t advertiser_id) = 0;
  virtual void OnLeDisconnect(uint8_t tcb_idx) = 0;
  virtual InterceptAction InterceptPacket(uint8_t tcb_idx, rust::Vec<uint8_t> buffer);

  virtual void OnOutgoingMtuReq(uint8_t tcb_idx) = 0;
  virtual void OnIncomingMtuResp(uint8_t tcb_idx, size_t mtu) = 0;
  virtual void OnIncomingMtuReq(uint8_t tcb_idx, size_t mtu) = 0;
};

class AclArbiter {
public:
  // Sets the backing arbiter. If arbiter == nullptr, the arbiter will default to forwarding
  // packets.
  void set_arbiter(ArbiterInterface* arbiter) const {
    std::lock_guard lock(mutex_);
    arbiter_ = arbiter;
  }

  void OnLeConnect(uint8_t tcb_idx, uint16_t advertiser_id);
  void OnLeDisconnect(uint8_t tcb_idx);
  InterceptAction InterceptAttPacket(uint8_t tcb_idx, const BT_HDR* packet);

  void OnOutgoingMtuReq(uint8_t tcb_idx);
  void OnIncomingMtuResp(uint8_t tcb_idx, size_t mtu);
  void OnIncomingMtuReq(uint8_t tcb_idx, size_t mtu);

  void SendPacketToPeer(uint8_t tcb_idx, ::rust::Vec<uint8_t> buffer);

private:
  // NOTE: These are `mutable` because `set_arbiter` above is `const` which helps when referencing
  // from within Rust.
  mutable std::mutex mutex_;
  mutable ArbiterInterface* arbiter_ GUARDED_BY(mutex_) = nullptr;
};

void SendPacketToPeer(uint8_t tcb_idx, ::rust::Vec<uint8_t> buffer);

AclArbiter& GetArbiter();

}  // namespace bluetooth::shim::arbiter
