/*
 * Copyright 2025 The Android Open Source Project
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
#include <cstdint>

#include "stack/gatt/gatt_int.h"
#include "stack/include/gatt_api.h"
#include "test/common/mock_functions.h"
using namespace bluetooth;
bool gatt_offload_init() {
  inc_func_call_count(__func__);
  return true;
}
void gatt_offload_characteristics(tCONN_ID /* conn_id */, bool /* is_server */,
                                  btgatt_db_element_t* /* service */, size_t /* elements_count */,
                                  uint64_t /* endpoint_id */, uint64_t /* hub_id */, int /* uid */,
                                  std::string /* attribution_tag */,
                                  std::promise<btgatt_offload_result_t> /* promise */) {
  inc_func_call_count(__func__);
}

bool gatt_offload_clear_sessions_by_acl_handle(uint16_t /* acl_connection_handle */,
                                               bluetooth::hal::GattError /* reason */) {
  inc_func_call_count(__func__);
  return true;
}

void gatt_offload_clear_sessions_by_conn_id(tCONN_ID /* conn_id */) {
  inc_func_call_count(__func__);
}

void gatt_unoffload_session(tCONN_ID /* conn_id */, uint16_t /* session_id */,
                            tGATT_STATUS /* status */) {
  inc_func_call_count(__func__);
}

void gattc_inform_notification_handle(tGATT_TCB* /* p_tcb */, uint16_t /* handle */) {
  inc_func_call_count(__func__);
}

void gattc_offload_handle_service_changed_indication(tGATT_TCB* /* p_tcb */) {
  inc_func_call_count(__func__);
}
