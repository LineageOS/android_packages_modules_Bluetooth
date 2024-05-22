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

#include "module.h"

namespace bluetooth {
namespace hal {

struct VendorSpecificCharacteristic {
  std::array<uint8_t, 16> characteristicUuid_;
  std::vector<uint8_t> value_;
};

class RangingHal : public ::bluetooth::Module {
 public:
  static const ModuleFactory Factory;

  virtual ~RangingHal() = default;
  virtual bool isBound() = 0;
  virtual std::vector<VendorSpecificCharacteristic> getVendorSpecificCharacteristics() = 0;
};

}  // namespace hal
}  // namespace bluetooth
