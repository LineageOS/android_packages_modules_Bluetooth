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

/*
 * Generated mock file from original source file
 *   Functions generated:17
 *
 *  mockcify.pl ver 0.2.1
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_btif_bluetooth.h"

#include <cstdint>

#include "btif/include/btif_api.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any

namespace test {
namespace mock {
namespace btif_bluetooth {

// Function state capture and return values, if needed
struct is_atv_device is_atv_device;
struct is_common_criteria_mode is_common_criteria_mode;
struct is_restricted_mode is_restricted_mode;
struct get_common_criteria_config_compare_result get_common_criteria_config_compare_result;
struct invoke_switch_buffer_size_cb invoke_switch_buffer_size_cb;

}  // namespace btif_bluetooth
}  // namespace mock
}  // namespace test

// Mocked functions, if any
bool is_atv_device() {
  inc_func_call_count(__func__);
  return test::mock::btif_bluetooth::is_atv_device();
}
bool is_common_criteria_mode() {
  inc_func_call_count(__func__);
  return test::mock::btif_bluetooth::is_common_criteria_mode();
}
bool is_restricted_mode() {
  inc_func_call_count(__func__);
  return test::mock::btif_bluetooth::is_restricted_mode();
}
int get_common_criteria_config_compare_result() {
  inc_func_call_count(__func__);
  return test::mock::btif_bluetooth::get_common_criteria_config_compare_result();
}
void invoke_switch_buffer_size_cb(bool invoke_switch_buffer_size_cb) {
  inc_func_call_count(__func__);
  test::mock::btif_bluetooth::invoke_switch_buffer_size_cb(invoke_switch_buffer_size_cb);
}

// END mockcify generation
