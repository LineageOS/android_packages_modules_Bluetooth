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
 *   Functions generated:3
 *
 *  mockcify.pl ver 0.3.0
 */

// Mock include file to share data between tests and mock
#include "main/shim/hci_layer.h"

// Mocked internal structures, if any

namespace bluetooth::shim {
namespace testing {
const hci_t* test_interface = nullptr;
void hci_layer_set_interface(const hci_t* interface) {
  test_interface = interface;
}
}  // namespace testing
const hci_t* hci_layer_get_interface() { return testing::test_interface; }
}  // namespace bluetooth::shim
