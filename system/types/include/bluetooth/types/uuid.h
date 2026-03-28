/******************************************************************************
 *
 *  Copyright (C) 2017 The Android Open Source Project
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
#include <format>
#include <string>

#include "consteval_helpers.h"

namespace bluetooth {

// This class is representing Bluetooth UUIDs across whole stack.
// Here are some general endianness rules:
// 1. UUID is internally kept as as Big Endian.
// 2. Bytes representing UUID coming from upper layers, Java or Binder, are Big
//    Endian.
// 3. Bytes representing UUID coming from lower layer, HCI packets, are Little
//    Endian.
// 4. UUID in storage is always string.
class Uuid final {
public:
  static constexpr size_t kNumBytes128 = 16;
  static constexpr size_t kNumBytes32 = 4;
  static constexpr size_t kNumBytes16 = 2;

  static constexpr size_t kString128BitLen = 36;

  static const Uuid kEmpty;  // 00000000-0000-0000-0000-000000000000

  using UUID128Bit = std::array<uint8_t, kNumBytes128>;

  Uuid() = default;

  // Consteval constructor to create an UUID from the 16-bit string representation with format
  // xxxx. Invalid input values will trigger compile time errors.
  consteval Uuid(const char (&s)[5]) {
    consteval_assert(s[4] == '\0', "expected nul termination");
    for (size_t i = 0; i < 4; i++) {
      consteval_assert(is_hex_char(s[i]), "expected alphanumerical character");
    }

    uu = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
          0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb};
    uu[2] = hex_to_byte(s[0], s[1]);
    uu[3] = hex_to_byte(s[2], s[3]);
  }

  // Consteval constructor to create an UUID from the 32-bit string representation with format
  // xxxxxxxx. Invalid input values will trigger compile time errors.
  consteval Uuid(const char (&s)[9]) {
    consteval_assert(s[8] == '\0', "expected nul termination");
    for (size_t i = 0; i < 8; i++) {
      consteval_assert(is_hex_char(s[i]), "expected alphanumerical character");
    }

    uu = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
          0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb};
    uu[0] = hex_to_byte(s[0], s[1]);
    uu[1] = hex_to_byte(s[2], s[3]);
    uu[2] = hex_to_byte(s[4], s[5]);
    uu[3] = hex_to_byte(s[6], s[7]);
  }

  // Consteval constructor to create an UUID from the 128-bit string representation with format
  // xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx. Invalid input values will trigger compile time errors.
  consteval Uuid(const char (&s)[37]) {
    consteval_assert(s[36] == '\0', "expected nul termination");
    for (size_t i = 0; i < 36; i++) {
      if (i == 8 || i == 13 || i == 18 || i == 23) {
        consteval_assert(s[i] == '-', "expected hyphens");
      } else {
        consteval_assert(is_hex_char(s[i]), "expected alphanumerical character");
      }
    }

    uu = {
            hex_to_byte(s[0], s[1]),   hex_to_byte(s[2], s[3]),   hex_to_byte(s[4], s[5]),
            hex_to_byte(s[6], s[7]),   hex_to_byte(s[9], s[10]),  hex_to_byte(s[11], s[12]),
            hex_to_byte(s[14], s[15]), hex_to_byte(s[16], s[17]), hex_to_byte(s[19], s[20]),
            hex_to_byte(s[21], s[22]), hex_to_byte(s[24], s[25]), hex_to_byte(s[26], s[27]),
            hex_to_byte(s[28], s[29]), hex_to_byte(s[30], s[31]), hex_to_byte(s[32], s[33]),
            hex_to_byte(s[34], s[35]),
    };
  }

  // Constructor from MSB/LSB
  Uuid(uint64_t msb, uint64_t lsb);

  // Returns the shortest possible representation of this UUID in bytes. Either
  // kNumBytes16, kNumBytes32, or kNumBytes128
  size_t GetShortestRepresentationSize() const;

  // Returns true if this UUID can be represented as 16 bit.
  bool Is16Bit() const;

  // Returns 16 bit Little Endian representation of this UUID. Use
  // GetShortestRepresentationSize() or Is16Bit() before using this method.
  uint16_t As16Bit() const;

  // Returns 32 bit Little Endian representation of this UUID. Use
  // GetShortestRepresentationSize() before using this method.
  uint32_t As32Bit() const;

  // Returns the most significant 64 bits of this UUID
  uint64_t msb() const;

  // Returns the least significant 64 bits of this UUID
  uint64_t lsb() const;

  // Converts string representing 128, 32, or 16 bit UUID in
  // xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx, xxxxxxxx, or xxxx format to UUID.
  // Returns std::nullopt is the input string is invalid.
  static std::optional<Uuid> FromString(const std::string& uuid);

  // Converts 16bit Little Endian representation of UUID to UUID
  static constexpr Uuid From16Bit(uint16_t uuid16bit) {
    Uuid u = From128BitBE({0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80,
                           0x5f, 0x9b, 0x34, 0xfb});
    u.uu[2] = (uint8_t)((0xFF00 & uuid16bit) >> 8);
    u.uu[3] = (uint8_t)(0x00FF & uuid16bit);
    return u;
  }

  // Converts 32bit Little Endian representation of UUID to UUID
  static Uuid From32Bit(uint32_t uuid32bit);

  // Converts 128 bit Big Endian array representing UUID to UUID.
  static constexpr Uuid From128BitBE(const UUID128Bit& uuid) {
    Uuid u(uuid);
    return u;
  }

  // Converts 128 bit Big Endian array representing UUID to UUID. |uuid| points
  // to beginning of array.
  static Uuid From128BitBE(const uint8_t* uuid);

  // Converts 128 bit Little Endian array representing UUID to UUID.
  static Uuid From128BitLE(const UUID128Bit& uuid);

  // Converts 128 bit Little Endian array representing UUID to UUID. |uuid|
  // points to beginning of array.
  static Uuid From128BitLE(const uint8_t* uuid);

  // Returns 128 bit Little Endian representation of this UUID
  const UUID128Bit To128BitLE() const;

  // Returns 128 bit Big Endian representation of this UUID
  const UUID128Bit& To128BitBE() const;

  // Returns string representing this UUID in
  // xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx format, lowercase.
  std::string ToString() const;

  // Returns true if this UUID is equal to kEmpty
  bool IsEmpty() const;

  // Returns true if this UUID is equal to kBase
  bool IsBase() const;

  // Returns true if this UUID is valid (i.e. neither 128-bit empty zeros
  // nor 16/32-bit 0-value which resolves to kBase).
  bool IsValid() const;

  bool operator<(const Uuid& rhs) const;
  bool operator==(const Uuid& rhs) const;
  bool operator!=(const Uuid& rhs) const;

private:
  constexpr Uuid(const UUID128Bit& val) : uu{val} {}

  // Network-byte-ordered ID (Big Endian).
  UUID128Bit uu;

  friend class UuidTest_ConstructorUuid16_Test;
  friend class UuidTest_ConstructorUuid32_Test;
  friend class UuidTest_ConstructorUuid128_Test;
};

}  // namespace bluetooth

namespace std {
template <>
struct hash<::bluetooth::Uuid> {
  std::size_t operator()(const ::bluetooth::Uuid& key) const {
    const auto& uuid_bytes = key.To128BitBE();
    std::hash<std::string> hash_fn;
    return hash_fn(
            std::string(reinterpret_cast<const char*>(uuid_bytes.data()), uuid_bytes.size()));
  }
};

template <>
struct formatter<::bluetooth::Uuid> : formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const ::bluetooth::Uuid& uuid, Context& ctx) const {
    std::string repr = uuid.ToString();
    return std::formatter<std::string>::format(repr, ctx);
  }
};
}  // namespace std
