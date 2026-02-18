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
 *   Functions generated:14
 */

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>

#include "stack/gatt/gatt_int.h"
#include "test/common/mock_functions.h"

uint16_t gatt_profile_find_conn_id_by_bd_addr(const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
  return 0;
}
bool gatt_profile_get_eatt_support(const RawAddress& /* remote_bda */) {
  inc_func_call_count(__func__);
  return false;
}
bool gatt_profile_get_eatt_support_by_conn_id(uint16_t /* conn_id */) {
  inc_func_call_count(__func__);
  return true;
}
bool gatt_sr_is_cl_change_aware(tGATT_TCB& /* tcb */) {
  inc_func_call_count(__func__);
  return false;
}
void GATT_LE_ConfigServiceChangeCCC(const RawAddress& /* remote_bda */, bool /* enable */) {
  inc_func_call_count(__func__);
}
void gatt_profile_db_init(void) { inc_func_call_count(__func__); }
void gatt_sr_init_cl_status(tGATT_TCB& /* tcb */) { inc_func_call_count(__func__); }
void gatt_sr_update_cl_status(tGATT_TCB& /* tcb */, bool /* chg_aware */) {
  inc_func_call_count(__func__);
}
bool gatt_cl_read_sirk_req(const RawAddress& /* peer_bda */,
                           base::OnceCallback<void(tGATT_STATUS status, const RawAddress&,
                                                   uint8_t sirk_type, Octet16& sirk)>
                           /* cb */) {
  return false;
}
