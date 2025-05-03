/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <bluetooth/log.h>

#include "hci/address_with_type.h"
#include "hci/hci_packets.h"

namespace bluetooth {
namespace hci {

class OnAdvertisingSetTerminatedInterface {
public:
  virtual ~OnAdvertisingSetTerminatedInterface() = default;

  virtual void OnAdvertisingSetTerminated(ErrorCode status, uint16_t conn_handle,
                                          uint8_t adv_set_id, hci::AddressWithType adv_address,
                                          bool is_discoverable) = 0;
};

}  // namespace hci
}  // namespace bluetooth
