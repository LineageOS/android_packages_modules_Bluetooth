/*
 * Copyright 2026 The Android Open Source Project
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
 *   Functions generated:26
 *
 *  mockcify.pl ver 0.5.0
 */

#include <cstdint>
#include <functional>
#include <string>

// Original included files, if any

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>

#include "stack/include/stack_app.h"

// Original usings
using bluetooth::Uuid;

using namespace bluetooth;

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace stack_app {

// Name: appRegister
// Params: const Uuid& app_uuid128, std::string name, stack::tGATT_CBACK* p_cb_info,
// bool eatt_support Return: tGATT_IF
struct appRegister {
  static tGATT_IF return_value;
  std::function<tGATT_IF(const Uuid& app_uuid128, const std::string& name,
                         const stack::tGATT_CBACK* p_cb_info, bool eatt_support)>
          body{[](const Uuid& /* app_uuid128 */, const std::string& /* name */,
                  const stack::tGATT_CBACK* /* p_cb_info */,
                  bool /* eatt_support */) { return return_value; }};
  tGATT_IF operator()(const Uuid& app_uuid128, const std::string& name,
                      const stack::tGATT_CBACK* p_cb_info, bool eatt_support) {
    return body(app_uuid128, name, p_cb_info, eatt_support);
  }
};
extern struct appRegister appRegister;

// Name: appDeregister
// Params: tGATT_IF gatt_if
// Return: void
struct appDeregister {
  std::function<void(tGATT_IF gatt_if)> body{[](tGATT_IF /* gatt_if */) {}};
  void operator()(tGATT_IF gatt_if) { body(gatt_if); }
};
extern struct appDeregister appDeregister;

// Name: appStartIf
// Params: tGATT_IF gatt_if
// Return: void
struct appStartIf {
  std::function<void(tGATT_IF gatt_if)> body{[](tGATT_IF /* gatt_if */) {}};
  void operator()(tGATT_IF gatt_if) { body(gatt_if); }
};
extern struct appStartIf appStartIf;

}  // namespace stack_app
}  // namespace mock
}  // namespace test

// END mockcify generation
