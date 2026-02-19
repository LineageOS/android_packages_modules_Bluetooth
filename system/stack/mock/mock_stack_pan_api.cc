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
 *   Functions generated:12
 *
 *  mockcify.pl ver 0.2.1
 */
// Mock include file to share data between tests and mock
#include "stack/mock/mock_stack_pan_api.h"

#include <cstdint>
#include <string>

#include "test/common/mock_functions.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_pan_api {

// Function state capture and return values, if needed
struct PAN_Connect PAN_Connect;
struct PAN_Disconnect PAN_Disconnect;
struct PAN_SetRole PAN_SetRole;
struct PAN_WriteBuf PAN_WriteBuf;
struct PAN_Deregister PAN_Deregister;
struct PAN_Dumpsys PAN_Dumpsys;
struct PAN_Init PAN_Init;
struct PAN_Register PAN_Register;

}  // namespace stack_pan_api
}  // namespace mock
}  // namespace test

// Mocked functions, if any
tPAN_RESULT PAN_Connect(const RawAddress& rem_bda, tPAN_ROLE src_role, tPAN_ROLE dst_role,
                        uint16_t* handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_pan_api::PAN_Connect(rem_bda, src_role, dst_role, handle);
}
tPAN_RESULT PAN_Disconnect(uint16_t handle) {
  inc_func_call_count(__func__);
  return test::mock::stack_pan_api::PAN_Disconnect(handle);
}
tPAN_RESULT PAN_SetRole(uint8_t role, std::string p_user_name, std::string p_nap_name) {
  inc_func_call_count(__func__);
  return test::mock::stack_pan_api::PAN_SetRole(role, p_user_name, p_nap_name);
}
tPAN_RESULT PAN_WriteBuf(uint16_t handle, const RawAddress& dst, const RawAddress& src,
                         uint16_t protocol, BT_HDR* p_buf, bool ext) {
  inc_func_call_count(__func__);
  return test::mock::stack_pan_api::PAN_WriteBuf(handle, dst, src, protocol, p_buf, ext);
}
void PAN_Deregister(void) {
  inc_func_call_count(__func__);
  test::mock::stack_pan_api::PAN_Deregister();
}
void PAN_Dumpsys(int fd) {
  inc_func_call_count(__func__);
  test::mock::stack_pan_api::PAN_Dumpsys(fd);
}
void PAN_Init(void) {
  inc_func_call_count(__func__);
  test::mock::stack_pan_api::PAN_Init();
}
void PAN_Register(tPAN_REGISTER* p_register) {
  inc_func_call_count(__func__);
  test::mock::stack_pan_api::PAN_Register(p_register);
}

// END mockcify generation
