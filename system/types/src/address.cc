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

#include <bluetooth/types/address.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <format>
#include <sstream>

static_assert(sizeof(RawAddress) == 6, "RawAddress must be 6 bytes long!");

const RawAddress RawAddress::kAny("ff:ff:ff:ff:ff:ff");
const RawAddress RawAddress::kEmpty("00:00:00:00:00:00");

std::string RawAddress::ToString() const {
  return std::format("{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}", address[0], address[1],
                     address[2], address[3], address[4], address[5]);
}

std::string RawAddress::ToRedactedStringForLogging() const {
  if (*this == RawAddress::kAny || *this == RawAddress::kEmpty) {
    return ToString();
  }

  return std::format("xx:xx:xx:xx:{:02x}:{:02x}", address[4], address[5]);
}

uint64_t RawAddress::ToUint64() const {
  return (static_cast<uint64_t>(address[0]) << 40) | (static_cast<uint64_t>(address[1]) << 32) |
         (static_cast<uint64_t>(address[2]) << 24) | (static_cast<uint64_t>(address[3]) << 16) |
         (static_cast<uint64_t>(address[4]) << 8) | (static_cast<uint64_t>(address[5]));
}

RawAddress RawAddress::FromUint64(uint64_t addr) {
  return RawAddress(std::array<uint8_t, 6>{
          static_cast<uint8_t>(addr >> 40),
          static_cast<uint8_t>(addr >> 32),
          static_cast<uint8_t>(addr >> 24),
          static_cast<uint8_t>(addr >> 16),
          static_cast<uint8_t>(addr >> 8),
          static_cast<uint8_t>(addr),
  });
}

std::optional<RawAddress> RawAddress::FromString(const std::string& from) {
  if (from.length() != 17) {
    return std::nullopt;
  }

  std::array<uint8_t, 6> address;
  std::istringstream stream(from);
  std::string token;
  int index = 0;

  while (getline(stream, token, ':')) {
    if (index >= 6) {
      return std::nullopt;
    }

    if (token.length() != 2) {
      return std::nullopt;
    }

    char* temp = nullptr;
    address[index] = std::strtol(token.c_str(), &temp, 16);

    if (temp == token.c_str()) {
      // string token is empty or has wrong format
      return std::nullopt;
    }

    if (temp != (token.c_str() + token.size())) {
      // cannot parse whole string
      return std::nullopt;
    }

    index++;
  }

  if (index != 6) {
    return std::nullopt;
  }

  return RawAddress(address);
}

RawAddress RawAddress::FromOctets(const uint8_t* from) {
  std::array<uint8_t, 6> address;
  std::copy(from, from + kLength, address.data());
  return RawAddress(address);
}

bool RawAddress::IsValidAddress(const std::string& address) {
  return RawAddress::FromString(address).has_value();
}
