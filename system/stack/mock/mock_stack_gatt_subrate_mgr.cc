/*
 * Copyright 2020 The Android Open Source Project
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
 *   Functions generated:10
 */

#include <bluetooth/types/address.h>

#include <cstdint>

#include "stack/gatt/gatt_int.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/gatt_api.h"
#include "test/common/mock_functions.h"

void gatt_init_subrate_cb(const RawAddress& /* bd_addr */) { inc_func_call_count(__func__); }
void gatt_release_subrate_cb(const RawAddress& /* bd_addr */) { inc_func_call_count(__func__); }
bool gatt_register_subrate_config(tGATT_IF /* client_if */, const RawAddress& /* bd_addr */,
                                  tGATT_SUBRATE_MODE /* subrate_mode */) {
  inc_func_call_count(__func__);
  return false;
}
bool gatt_handle_subrate_cback_status(const RawAddress& /* bda */, uint16_t /* subrate_factor */,
                                      uint16_t /* latency */, uint16_t /* cont_num */,
                                      uint16_t /* timeout */, uint8_t /* status */) {
  inc_func_call_count(__func__);
  return false;
}
void gatt_handle_conn_parameter_cback_status(const RawAddress& /* bda */, uint16_t /* interval */) {
  inc_func_call_count(__func__);
}

void gatt_init_subrate_mode_config() { inc_func_call_count(__func__); }