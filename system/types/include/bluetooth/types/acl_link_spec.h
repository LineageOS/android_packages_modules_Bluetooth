/*
 * Copyright 2025 The Android Open Source Project
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

#include <bluetooth/types/ble_address_with_type.h>

#include <string>

struct AclLinkSpec {
  tBLE_BD_ADDR addrt;
  tBT_TRANSPORT transport;

  bool operator==(const AclLinkSpec rhs) const {
    if (rhs.addrt != addrt) {
      return false;
    }

    if (rhs.transport == BT_TRANSPORT_AUTO || transport == BT_TRANSPORT_AUTO) {
      return true;
    }

    return rhs.transport == transport;
  }

  bool operator!=(const AclLinkSpec rhs) const { return !(*this == rhs); }

  bool StrictlyEquals(const AclLinkSpec rhs) const {
    return rhs.addrt == addrt && rhs.transport == transport;
  }

  std::string ToString() const {
    return std::string(addrt.ToString() + "[" + bt_transport_text(transport) + "]");
  }

  std::string ToRedactedStringForLogging() const {
    return addrt.ToRedactedStringForLogging() + "[" + bt_transport_text(transport) + "]";
  }
};

namespace std {
template <>
struct formatter<AclLinkSpec> : formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const AclLinkSpec& address, Context& ctx) const {
    std::string repr = address.ToRedactedStringForLogging();
    return std::formatter<std::string>::format(repr, ctx);
  }
};
}  // namespace std
