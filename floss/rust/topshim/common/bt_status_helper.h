/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "include/bt_status.h"
#include "include/bt_status_origin.h"
#include "include/hardware/bluetooth.h"

namespace bluetooth {
namespace topshim {
namespace rust {

// Same as `bt_status_t`. When `bt_status_t` is removed, this
// will remain to preserve backwards compatibility.
typedef uint32_t tBT_STATUS_LEGACY;

tBT_STATUS_LEGACY toLegacyStatus(BtStatus status) {
  if (status) {
    return BT_STATUS_SUCCESS;
  }

  if (status.origin() == BtStatusOrigin::BTIF) {
    return static_cast<tBT_STATUS_LEGACY>(status.code());
  }

  // If error has a new non-Btif origin, return a generic failure.
  return BT_STATUS_UNHANDLED;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
