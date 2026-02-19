/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:33
 *
 *  mockcify.pl ver 0.2
 */

#include <functional>
#include <vector>

// Original included files, if any

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>

#include "stack/include/bt_hdr.h"
#include "stack/include/l2cap_interface.h"
#include "stack/l2cap/l2c_int.h"

// Mocked compile conditionals, if any
namespace test {
namespace mock {
namespace stack_l2cap_utils {

// Name: l2cu_find_lcb_by_bd_addr
// Params: const RawAddress& bd_addr, tBT_TRANSPORT transport
// Returns: tL2C_LCB*
struct l2cu_find_lcb_by_bd_addr {
  std::function<tL2C_LCB*(const RawAddress& bd_addr, tBT_TRANSPORT transport)> body{
          [](const RawAddress& /* bd_addr */, tBT_TRANSPORT /* transport */) { return nullptr; }};
  tL2C_LCB* operator()(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
    return body(bd_addr, transport);
  }
};
extern struct l2cu_find_lcb_by_bd_addr l2cu_find_lcb_by_bd_addr;
}  // namespace stack_l2cap_utils
}  // namespace mock
}  // namespace test