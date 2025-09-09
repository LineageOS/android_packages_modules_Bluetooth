//
//  Copyright 2025 Google, Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

#pragma once

// All status code origins.
enum BtStatusOrigin : uint16_t {
  // 0x0000 - 0x007F are reserved for Bluetooth spec origins
  HCI = 0x0000,

  // 0x0080 - 0x00FF are reserved for native stack origins
  BTIF = 0x0080,

  // 0x0100 - 0x01FF are reserved for Java stack origins
  // 0x0200 - 0xFFFE are reserved for future use
};

// Stringify origin status codes.
static const std::string toStringBtStatusOrigin(BtStatusOrigin origin) {
  switch (origin) {
    case HCI:
      return "HCI";
    case BTIF:
      return "BTIF";
    default:
      return std::format("Unknown origin {:#x}", (uint16_t)origin);
  }
}
