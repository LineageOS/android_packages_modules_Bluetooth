/******************************************************************************
 *
 *  Copyright 2017 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <format>
#include <optional>
#include <string>

/** Bluetooth Address */
class RawAddress final {
public:
  std::array<uint8_t, 6> address;

  RawAddress() = default;
  constexpr RawAddress(std::array<uint8_t, 6> const& address) : address(address) {}

  bool operator<(const RawAddress& rhs) const { return address < rhs.address; }
  bool operator==(const RawAddress& rhs) const { return address == rhs.address; }
  bool operator>(const RawAddress& rhs) const { return rhs < *this; }
  bool operator<=(const RawAddress& rhs) const { return !(*this > rhs); }
  bool operator>=(const RawAddress& rhs) const { return !(*this < rhs); }
  bool operator!=(const RawAddress& rhs) const { return !(*this == rhs); }

  bool IsEmpty() const { return *this == kEmpty; }

  // Return a string representation in the form of
  // hexadecimal string separated by colon (:), e.g.,
  // "12:34:56:ab:cd:ef"
  std::string ToString() const;

  // Similar with ToString, ToRedactedStringForLogging returns a
  // colon separated hexadecimal reprentation of the address but, with the
  // leftmost 4 bytes masked with "xx", e.g., "xx:xx:xx:xx:ab:cd".
  std::string ToRedactedStringForLogging() const;

  // Converts |string| to RawAddress and places it in |to|. If |from| does
  // not represent a Bluetooth address, |to| is not modified and this function
  // returns false. Otherwise, it returns true.
  static std::optional<RawAddress> FromString(const std::string& from);

  // Copies |from| raw Bluetooth address octets to the local object.
  // Returns the number of copied octets - should be always RawAddress::kLength
  static RawAddress FromOctets(const uint8_t* from);

  static bool IsValidAddress(const std::string& address);

  static constexpr unsigned int kLength = 6;
  static const RawAddress kEmpty;  // 00:00:00:00:00:00
  static const RawAddress kAny;    // FF:FF:FF:FF:FF:FF
};

template <>
struct std::hash<RawAddress> {
  std::size_t operator()(const RawAddress& val) const {
    static_assert(sizeof(uint64_t) >= RawAddress::kLength);
    uint64_t int_addr = 0;
    memcpy(reinterpret_cast<uint8_t*>(&int_addr), val.address.data(), RawAddress::kLength);
    return std::hash<uint64_t>{}(int_addr);
  }
};

#define BD_ADDR_LEN 6 /* Device address length */

inline void BDADDR_TO_STREAM(uint8_t*& p, const RawAddress& a) {
  for (int ijk = 0; ijk < BD_ADDR_LEN; ijk++) {
    *(p)++ = a.address[BD_ADDR_LEN - 1 - ijk];
  }
}

inline void STREAM_TO_BDADDR(RawAddress& a, const uint8_t*& p) {
  uint8_t* pbda = a.address.data() + BD_ADDR_LEN - 1;
  for (int ijk = 0; ijk < BD_ADDR_LEN; ijk++) {
    *pbda-- = *(p)++;
  }
}

namespace std {
template <>
struct formatter<RawAddress> : formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const RawAddress& address, Context& ctx) const {
    std::string repr = address.ToRedactedStringForLogging();
    return std::formatter<std::string>::format(repr, ctx);
  }
};
}  // namespace std
