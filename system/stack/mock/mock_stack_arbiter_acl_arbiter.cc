/*
 * Copyright 2023 The Android Open Source Project
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

namespace bluetooth {
namespace shim {
namespace arbiter {

void AclArbiter::OnLeConnect(uint8_t /* tcb_idx */, uint16_t /* advertiser_id */) {}

void AclArbiter::OnLeDisconnect(uint8_t /* tcb_idx */) {}

InterceptAction AclArbiter::InterceptAttPacket(uint8_t /* tcb_idx */, const BT_HDR* /* packet */) {
  return InterceptAction::FORWARD;
}

void AclArbiter::OnOutgoingMtuReq(uint8_t /* tcb_idx */) {}

void AclArbiter::OnIncomingMtuResp(uint8_t /* tcb_idx */, size_t /* mtu */) {}

void AclArbiter::OnIncomingMtuReq(uint8_t /* tcb_idx */, size_t /* mtu */) {}

AclArbiter& GetArbiter() {
  static auto singleton = AclArbiter();
  return singleton;
}

}  // namespace arbiter
}  // namespace shim
}  // namespace bluetooth
