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
/*
 * Generated mock file from original source file
 *   Functions generated:26
 *
 *  mockcify.pl ver 0.5.0
 */
// Mock include file to share data between tests and mock
#include "stack/mock/mock_stack_app.h"

#include <cstdint>
#include <string>

#include "test/common/mock_functions.h"

// Original usings
using bluetooth::Uuid;

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_app {

// Function state capture and return values, if needed
struct appDeregister appDeregister;
struct appRegister appRegister;
struct appStartIf appStartIf;

}  // namespace stack_app
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_app {

tGATT_IF appRegister::return_value = 0;

}  // namespace stack_app
}  // namespace mock
}  // namespace test

// Mocked functions, if any
namespace bluetooth::stack {
tGATT_IF appRegister(const Uuid& app_uuid128, const std::string& name, const tGATT_CBACK* p_cb_info,
                     bool eatt_support) {
  inc_func_call_count(__func__);
  return test::mock::stack_app::appRegister(app_uuid128, name, p_cb_info, eatt_support);
}
void appDeregister(tGATT_IF gatt_if) {
  inc_func_call_count(__func__);
  test::mock::stack_app::appDeregister(gatt_if);
}
void appStartIf(tGATT_IF gatt_if) {
  inc_func_call_count(__func__);
  test::mock::stack_app::appStartIf(gatt_if);
}
}  // namespace bluetooth::stack
// END mockcify generation
