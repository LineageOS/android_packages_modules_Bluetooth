/******************************************************************************
 *
 *  Copyright 2019 The Android Open Source Project
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
#include <initializer_list>
#include <optional>
#include <string>

class RawAddress;

namespace bluetooth {
namespace hci {

class Address final {
public:
  static constexpr size_t kLength = 6;

  // Bluetooth MAC address bytes saved in little endian format.
  // The address MSB is address[5], the address LSB is address[0].
  // Note that the textual representation follows the big endian format,
  // ie. Address{0, 1, 2, 3, 4, 5} is represented as 05:04:03:02:01:00.
  std::array<uint8_t, kLength> address = {};

  Address() = default;
  Address(const uint8_t (&addr)[kLength]);
  Address(std::initializer_list<uint8_t> l);
  Address(const RawAddress& address);

  uint8_t* data() { return address.data(); }
  const uint8_t* data() const { return address.data(); }

  std::string ToString() const;
  std::string ToColonSepHexString() const;
  std::string ToRedactedStringForLogging() const;
  static std::optional<Address> FromString(const std::string& from);

  bool operator<(const Address& rhs) const { return address < rhs.address; }
  bool operator==(const Address& rhs) const { return address == rhs.address; }
  bool operator>(const Address& rhs) const { return rhs < *this; }
  bool operator<=(const Address& rhs) const { return !(*this > rhs); }
  bool operator>=(const Address& rhs) const { return !(*this < rhs); }
  bool operator!=(const Address& rhs) const { return !(*this == rhs); }

  bool IsEmpty() const { return *this == kEmpty; }

  // Converts |string| to Address and places it in |to|. If |from| does
  // not represent a Bluetooth address, |to| is not modified and this function
  // returns false. Otherwise, it returns true.
  static bool FromString(const std::string& from, Address& to);

  // Copies |from| raw Bluetooth address octets to the local object.
  // Returns the number of copied octets - should be always Address::kLength
  size_t FromOctets(const uint8_t* from);

  static bool IsValidAddress(const std::string& address);

  static const Address kEmpty;  // 00:00:00:00:00:00
  static const Address kAny;    // FF:FF:FF:FF:FF:FF

private:
  std::string _ToMaskedColonSepHexString(int bytes_to_mask) const;
};

}  // namespace hci
}  // namespace bluetooth

namespace std {
template <>
struct hash<bluetooth::hci::Address> {
  std::size_t operator()(const bluetooth::hci::Address& val) const {
    static_assert(sizeof(uint64_t) >= bluetooth::hci::Address::kLength);
    uint64_t int_addr = 0;
    memcpy(reinterpret_cast<uint8_t*>(&int_addr), val.data(), bluetooth::hci::Address::kLength);
    return std::hash<uint64_t>{}(int_addr);
  }
};
}  // namespace std

#if __has_include(<bluetooth/log.h>)
#include <bluetooth/log.h>

namespace std {
template <>
struct formatter<bluetooth::hci::Address> : formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const bluetooth::hci::Address& address, Context& ctx) const {
    std::string repr = address.ToRedactedStringForLogging();
    return std::formatter<std::string>::format(repr, ctx);
  }
};
}  // namespace std

#endif  // __has_include(<bluetooth/log.h>)
