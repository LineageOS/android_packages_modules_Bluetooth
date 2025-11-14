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

#include "stack/arbiter/acl_arbiter.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>

#include <iterator>

#include "osi/include/allocator.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/main_thread.h"

namespace bluetooth {
namespace shim {
namespace arbiter {

void AclArbiter::OnLeConnect(uint8_t tcb_idx, uint16_t advertiser_id) {
#ifdef TARGET_FLOSS
  return;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::info("Notifying Rust of LE connection");
    arbiter_->OnLeConnect(tcb_idx, advertiser_id);
  }
}

void AclArbiter::OnLeDisconnect(uint8_t tcb_idx) {
#ifdef TARGET_FLOSS
  return;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::info("Notifying Rust of LE disconnection");
    arbiter_->OnLeDisconnect(tcb_idx);
  }
}

InterceptAction AclArbiter::InterceptAttPacket(uint8_t tcb_idx, const BT_HDR* packet) {
#ifdef TARGET_FLOSS
  return InterceptAction::FORWARD;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::debug("Intercepting ATT packet and forwarding to Rust");

    uint8_t* packet_start = (uint8_t*)(packet + 1) + packet->offset;
    uint8_t* packet_end = packet_start + packet->len;

    auto vec = ::rust::Vec<uint8_t>();
    std::copy(packet_start, packet_end, std::back_inserter(vec));
    return arbiter_->InterceptPacket(tcb_idx, std::move(vec));
  } else {
    return InterceptAction::FORWARD;
  }
}

void AclArbiter::OnOutgoingMtuReq(uint8_t tcb_idx) {
#ifdef TARGET_FLOSS
  return;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::debug("Notifying Rust of outgoing MTU request");
    arbiter_->OnOutgoingMtuReq(tcb_idx);
  }
}

void AclArbiter::OnIncomingMtuResp(uint8_t tcb_idx, size_t mtu) {
#ifdef TARGET_FLOSS
  return;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::debug("Notifying Rust of incoming MTU response {}", mtu);
    arbiter_->OnIncomingMtuResp(tcb_idx, mtu);
  }
}

void AclArbiter::OnIncomingMtuReq(uint8_t tcb_idx, size_t mtu) {
#ifdef TARGET_FLOSS
  return;
#endif
  std::lock_guard lock(mutex_);
  if (arbiter_) {
    log::debug("Notifying Rust of incoming MTU request {}", mtu);
    arbiter_->OnIncomingMtuReq(tcb_idx, mtu);
  }
}

void AclArbiter::SendPacketToPeer(uint8_t tcb_idx, ::rust::Vec<uint8_t> buffer) {
#ifdef TARGET_FLOSS
  return;
#endif
  tGATT_TCB* p_tcb = gatt_get_tcb_by_idx(tcb_idx);
  if (p_tcb != nullptr) {
    BT_HDR* p_buf = (BT_HDR*)osi_malloc(sizeof(BT_HDR) + buffer.size() + L2CAP_MIN_OFFSET);
    if (p_buf == nullptr) {
      log::fatal("OOM when sending packet");
    }
    auto p = (uint8_t*)(p_buf + 1) + L2CAP_MIN_OFFSET;
    std::copy(buffer.begin(), buffer.end(), p);
    p_buf->offset = L2CAP_MIN_OFFSET;
    p_buf->len = buffer.size();
    if (stack::l2cap::get_interface().L2CA_SendFixedChnlData(L2CAP_ATT_CID, p_tcb->peer_bda,
                                                             p_buf) != tL2CAP_DW_RESULT::SUCCESS) {
      log::warn("Unable to send L2CAP data peer:{} fixed_cid:{} len:{}", p_tcb->peer_bda,
                L2CAP_ATT_CID, buffer.size());
    }
  } else {
    log::error("Dropping packet since connection no longer exists");
  }
}

void SendPacketToPeer(uint8_t tcb_idx, ::rust::Vec<uint8_t> buffer) {
  do_in_main_thread(base::BindOnce(&AclArbiter::SendPacketToPeer, base::Unretained(&GetArbiter()),
                                   tcb_idx, std::move(buffer)));
}

AclArbiter& GetArbiter() {
  static auto* singleton = new AclArbiter();
  return *singleton;
}

}  // namespace arbiter
}  // namespace shim
}  // namespace bluetooth
