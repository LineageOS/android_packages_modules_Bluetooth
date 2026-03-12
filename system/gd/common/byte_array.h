/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <bluetooth/types/string_helpers.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <initializer_list>
#include <optional>
#include <string>

#include "packet/custom_field_fixed_size_interface.h"

namespace bluetooth {
namespace common {

template <std::size_t LENGTH>
class ByteArray : public packet::CustomFieldFixedSizeInterface<ByteArray<LENGTH>> {
public:
  static constexpr size_t kLength = LENGTH;
  ByteArray() = default;
  ByteArray(const uint8_t (&d)[kLength]) { std::copy(d, d + kLength, data()); }
  ByteArray(std::array<uint8_t, kLength> a) : bytes(std::move(a)) {}

  std::array<uint8_t, kLength> bytes = {};

  uint8_t* data() override { return bytes.data(); }

  const uint8_t* data() const override { return bytes.data(); }

  // operators
  bool operator<(const ByteArray& rhs) const { return bytes < rhs.bytes; }
  bool operator==(const ByteArray& rhs) const { return bytes == rhs.bytes; }
  bool operator>(const ByteArray& rhs) const { return rhs < *this; }
  bool operator<=(const ByteArray& rhs) const { return !(*this > rhs); }
  bool operator>=(const ByteArray& rhs) const { return !(*this < rhs); }
  bool operator!=(const ByteArray& rhs) const { return !(*this == rhs); }

  std::string ToString() const { return common::ToHexString(bytes.begin(), bytes.end()); }
  static std::optional<ByteArray<kLength>> FromString(const std::string& from) {
    if (from.length() != (kLength * 2)) {
      return std::nullopt;
    }
    auto vec = common::FromHexString(from);
    if (!vec) {
      return std::nullopt;
    }
    ByteArray<kLength> byte_array = {};
    std::move(vec->data(), vec->data() + vec->size(), byte_array.data());
    return byte_array;
  }
};

}  // namespace common
}  // namespace bluetooth
