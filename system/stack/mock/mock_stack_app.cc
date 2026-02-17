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
struct GATT_Deregister GATT_Deregister;
struct GATT_Register GATT_Register;
struct GATT_StartIf GATT_StartIf;

}  // namespace stack_app
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_app {

tGATT_IF GATT_Register::return_value = 0;

}  // namespace stack_app
}  // namespace mock
}  // namespace test

// Mocked functions, if any
tGATT_IF GATT_Register(const Uuid& app_uuid128, const std::string& name, tGATT_CBACK* p_cb_info,
                       bool eatt_support) {
  inc_func_call_count(__func__);
  return test::mock::stack_app::GATT_Register(app_uuid128, name, p_cb_info, eatt_support);
}
void GATT_Deregister(tGATT_IF gatt_if) {
  inc_func_call_count(__func__);
  test::mock::stack_app::GATT_Deregister(gatt_if);
}
void GATT_StartIf(tGATT_IF gatt_if) {
  inc_func_call_count(__func__);
  test::mock::stack_app::GATT_StartIf(gatt_if);
}
// END mockcify generation
