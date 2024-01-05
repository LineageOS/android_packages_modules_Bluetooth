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
 *   Functions generated:1
 *
 *  mockcify.pl ver 0.2
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_device_controller.h"

// Original included files, if any
#include "device/include/controller.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any
namespace test {
namespace mock {
namespace device_controller {

bool readable{false};
bool get_is_ready(void) { return readable; }

const controller_t interface = {
    get_is_ready,
};

}  // namespace device_controller
}  // namespace mock
}  // namespace test

// Mocked functions, if any
const controller_t* controller_get_interface() {
  return &test::mock::device_controller::interface;
}

// END mockcify generation
