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

#include "consteval_helpers.h"

/** Bluetooth Address */
class RawAddress final {
public:
  std::array<uint8_t, 6> address;

  RawAddress() = default;
  constexpr RawAddress(std::array<uint8_t, 6> const& address) : address(address) {}

  // Consteval constructor to create an address from the string representation with format
  // xx:xx:xx:xx:xx:xx. Invalid input values will trigger compile time errors.
  consteval RawAddress(const char (&s)[18]) {
    using bluetooth::consteval_assert;
    using bluetooth::hex_to_byte;
    using bluetooth::is_hex_char;

    consteval_assert(s[17] == '\0', "expected nul termination");
    for (size_t i = 0; i < 17; i++) {
      if (i % 3 == 2) {
        consteval_assert(s[i] == ':', "expected colon separator");
      } else {
        consteval_assert(is_hex_char(s[i]), "expected alphanumerical character");
      }
    }

    address = {
            hex_to_byte(s[0], s[1]),  hex_to_byte(s[3], s[4]),   hex_to_byte(s[6], s[7]),
            hex_to_byte(s[9], s[10]), hex_to_byte(s[12], s[13]), hex_to_byte(s[15], s[16]),
    };
  }

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

  // Returns a 64-bit integer representation of the address in big-endian order.
  uint64_t ToUint64() const;

  // Creates a RawAddress from a 64-bit integer representation.
  static RawAddress FromUint64(uint64_t addr);

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

namespace std {
template <>
struct hash<RawAddress> {
  std::size_t operator()(const RawAddress& val) const {
    static_assert(sizeof(uint64_t) >= RawAddress::kLength);
    uint64_t int_addr = 0;
    memcpy(reinterpret_cast<uint8_t*>(&int_addr), val.address.data(), RawAddress::kLength);
    return std::hash<uint64_t>{}(int_addr);
  }
};

template <>
struct formatter<RawAddress> : formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const RawAddress& address, Context& ctx) const {
    std::string repr = address.ToRedactedStringForLogging();
    return std::formatter<std::string>::format(repr, ctx);
  }
};
}  // namespace std
