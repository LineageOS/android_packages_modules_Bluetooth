// Copyright 2022, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <bluetooth/log.h>

#include <cstdint>
#include <memory>

#include "include/hardware/bluetooth.h"
#include "include/hardware/bt_common_types.h"
#include "include/hardware/bt_gatt_client.h"
#include "include/hardware/bt_gatt_server.h"
#include "rust/cxx.h"
#include "stack/arbiter/acl_arbiter.h"

namespace bluetooth {
namespace gatt {

/// The GATT entity backing the value of a user-controlled attribute
enum class AttributeBackingType {
  /// A GATT characteristic
  CHARACTERISTIC,
  /// A GATT descriptor
  DESCRIPTOR,
};

class GattServerCallbacks {
public:
  GattServerCallbacks(const btgatt_server_callbacks_t& callbacks) : callbacks_(callbacks) {}

  void OnServerRead(uint16_t conn_id, uint32_t trans_id, uint16_t attr_handle,
                    AttributeBackingType attr_type, uint32_t offset, bool is_long) const;

  void OnServerWrite(uint16_t conn_id, uint32_t trans_id, uint16_t attr_handle,
                     AttributeBackingType attr_type, uint32_t offset, bool need_response,
                     bool is_prepare, ::rust::Slice<const uint8_t> value) const;

  void OnIndicationSentConfirmation(uint16_t conn_id, int status) const;

  void OnExecute(uint16_t conn_id, uint32_t trans_id, bool execute) const;

private:
  const btgatt_server_callbacks_t& callbacks_;
};

struct Arbiter;

}  // namespace gatt

namespace shim::arbiter {

// Wraps the Rust Arbiter and implements ArbiterInterface.
class ArbiterShim : public ArbiterInterface {
public:
  ArbiterShim(const AclArbiter* acl_arbiter, const bluetooth::gatt::Arbiter* arbiter)
      : acl_arbiter_(acl_arbiter), arbiter_(arbiter) {
    acl_arbiter_->set_arbiter(this);
  }
  ~ArbiterShim() { acl_arbiter_->set_arbiter(nullptr); }

  void OnLeConnect(uint8_t tcb_idx, uint16_t advertiser_id) override;
  void OnLeDisconnect(uint8_t tcb_idx) override;
  InterceptAction InterceptPacket(uint8_t tcb_idx, rust::Vec<uint8_t> buffer) override;
  void OnOutgoingMtuReq(uint8_t tcb_idx) override;
  void OnIncomingMtuResp(uint8_t tcb_idx, size_t mtu) override;
  void OnIncomingMtuReq(uint8_t tcb_idx, size_t mtu) override;

private:
  const AclArbiter* acl_arbiter_;
  const bluetooth::gatt::Arbiter* arbiter_;
};

// Registers `arbiter` with `acl_arbiter`.  The arbiter will be unregistered when the shim is
// dropped.
inline std::unique_ptr<ArbiterShim> RegisterArbiter(const AclArbiter* acl_arbiter,
                                                    const bluetooth::gatt::Arbiter* arbiter) {
  return std::make_unique<ArbiterShim>(acl_arbiter, arbiter);
}

}  // namespace shim::arbiter
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::gatt::AttributeBackingType>
    : enum_formatter<bluetooth::gatt::AttributeBackingType> {};
}  // namespace std
