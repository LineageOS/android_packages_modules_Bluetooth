/*
 * Copyright 2023 The Android Open Source Project
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
 *   Functions generated:13
 *
 *  mockcify.pl ver 0.7.1
 */

// Mock include file to share data between tests and mock
#include "test/mock/mock_btif_sock_l2cap.h"

#include <cstdint>

#include "btif/include/btif_sock_l2cap.h"
#include "test/common/mock_functions.h"

// Original usings

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace btif_sock_l2cap {

// Function state capture and return values, if needed
struct on_btsocket_l2cap_close on_btsocket_l2cap_close;
struct on_btsocket_l2cap_opened_complete on_btsocket_l2cap_opened_complete;

}  // namespace btif_sock_l2cap
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace btif_sock_l2cap {}  // namespace btif_sock_l2cap
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void on_btsocket_l2cap_close(uint64_t socket_id) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_l2cap::on_btsocket_l2cap_close(socket_id);
}
void on_btsocket_l2cap_opened_complete(uint64_t socket_id, bool success) {
  inc_func_call_count(__func__);
  test::mock::btif_sock_l2cap::on_btsocket_l2cap_opened_complete(socket_id, success);
}
// Mocked functions complete
// END mockcify generation
