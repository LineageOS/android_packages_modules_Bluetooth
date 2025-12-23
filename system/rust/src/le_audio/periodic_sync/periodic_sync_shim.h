/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <cstdint>
#include <memory>

#include "rust/cxx.h"

namespace ffi {
// Shadow struct matching Rust's Address { value: u64 }.
struct Address {
  uint64_t value;
};
}  // namespace ffi

namespace bluetooth::shim {
namespace ffi {
struct PeriodicSyncCallbacks;
}  // namespace ffi

class BleScannerInterfaceShim {
public:
  BleScannerInterfaceShim();
  void StartSync(uint8_t advertising_sid, ::ffi::Address advertiser_addr,
                 uint8_t advertiser_addr_type, uint16_t skip, uint16_t timeout, int32_t reg_id);
  void StopSync(uint16_t handle);
  void RegisterCallbacksNative(rust::Box<::bluetooth::shim::ffi::PeriodicSyncCallbacks> cb,
                               uint8_t client_id);
};

std::unique_ptr<BleScannerInterfaceShim> GetBleScannerInterfaceShim();

}  // namespace bluetooth::shim
