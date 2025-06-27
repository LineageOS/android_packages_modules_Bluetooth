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

#include "raw_address.h"

#include <stdint.h>

#include <algorithm>
#include <array>
#include <iomanip>
#include <sstream>
#include <vector>

static_assert(sizeof(RawAddress) == 6, "RawAddress must be 6 bytes long!");

const RawAddress RawAddress::kAny{{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}};
const RawAddress RawAddress::kEmpty{{0x00, 0x00, 0x00, 0x00, 0x00, 0x00}};

RawAddress::RawAddress(const uint8_t (&addr)[6]) { std::copy(addr, addr + kLength, address); }

RawAddress::RawAddress(const std::array<uint8_t, kLength> mac) {
  std::copy(mac.begin(), mac.end(), address);
}

std::string RawAddress::ToString() const { return ToColonSepHexString(); }

std::string RawAddress::ToColonSepHexString() const {
  std::stringstream addr;
  addr << std::hex << std::setfill('0');
  for (size_t i = 0; i < 6; i++) {
    addr << std::setw(2) << +address[i];
    if (i != 5) {
      addr << ":";
    }
  }
  return addr.str();
}

std::string RawAddress::ToStringForLogging() const { return ToColonSepHexString(); }

std::string RawAddress::ToRedactedStringForLogging() const {
  if (*this == RawAddress::kAny || *this == RawAddress::kEmpty) {
    return ToStringForLogging();
  }
  std::stringstream addr;
  addr << std::hex << std::setfill('0');
  addr << "xx:xx:xx:xx:";
  addr << std::setw(2) << +address[4] << ":";
  addr << std::setw(2) << +address[5];
  return addr.str();
}

std::array<uint8_t, RawAddress::kLength> RawAddress::ToArray() const {
  std::array<uint8_t, kLength> mac;
  std::copy(std::begin(address), std::end(address), std::begin(mac));
  return mac;
}

bool RawAddress::FromString(const std::string& from, RawAddress& to) {
  RawAddress new_addr;
  if (from.length() != 17) {
    return false;
  }

  std::istringstream stream(from);
  std::string token;
  int index = 0;
  while (getline(stream, token, ':')) {
    if (index >= 6) {
      return false;
    }

    if (token.length() != 2) {
      return false;
    }

    char* temp = nullptr;
    new_addr.address[index] = std::strtol(token.c_str(), &temp, 16);
    if (temp == token.c_str()) {
      // string token is empty or has wrong format
      return false;
    }
    if (temp != (token.c_str() + token.size())) {
      // cannot parse whole string
      return false;
    }

    index++;
  }

  if (index != 6) {
    return false;
  }

  to = new_addr;
  return true;
}

size_t RawAddress::FromOctets(const uint8_t* from) {
  std::copy(from, from + kLength, address);
  return kLength;
}

bool RawAddress::IsValidAddress(const std::string& address) {
  RawAddress tmp;
  return RawAddress::FromString(address, tmp);
}
