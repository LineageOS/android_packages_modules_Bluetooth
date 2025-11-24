/*
 * Copyright 2024 The Android Open Source Project
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

#include <stdint.h>

#include <vector>

namespace bluetooth {
namespace hci_vs {

typedef std::array<uint8_t, 16> Cookie;

class BluetoothHciVendorSpecificCallbacks {
public:
  virtual ~BluetoothHciVendorSpecificCallbacks() = default;

  virtual void onCommandStatus(uint16_t ocf, uint8_t status, Cookie cookie) = 0;
  virtual void onCommandComplete(uint16_t ocf, std::vector<uint8_t> return_parameters,
                                 Cookie cookie) = 0;
  virtual void onEvent(uint8_t code, std::vector<uint8_t> data) = 0;
  virtual void onAclEvent(uint16_t handle, std::vector<uint8_t> data) = 0;
};

class BluetoothHciVendorSpecificInterface {
public:
  virtual ~BluetoothHciVendorSpecificInterface() = default;

  virtual void init(BluetoothHciVendorSpecificCallbacks* callbacks) = 0;

  virtual void sendCommand(uint16_t ocf, std::vector<uint8_t> parameters, Cookie cookie) = 0;
};

}  // namespace hci_vs
}  // namespace bluetooth
