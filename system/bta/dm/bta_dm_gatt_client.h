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

#pragma once

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include <cstdint>
#include <string>

#include "bta/include/bta_gatt_api.h"
#include "gd/common/circular_buffer.h"
#include "include/hardware/bt_common_types.h"
#include "stack/include/btm_ble_api_types.h"

//
// Interface as a GATT client for bta clients
//
struct gatt_interface_t {
  void (*BTA_GATTC_CancelOpen)(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct);
  void (*BTA_GATTC_Refresh)(tGATT_IF client_if, const RawAddress& remote_bda);
  void (*BTA_GATTC_GetGattDb)(tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                              btgatt_db_element_t** db, int* count);
  void (*BTA_GATTC_AppRegister)(const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                                BtaAppRegisterCallback cb, bool eatt_support);
  void (*BTA_GATTC_Close)(tCONN_ID conn_id);
  void (*BTA_GATTC_ServiceSearchRequest)(tCONN_ID conn_id);
  void (*BTA_GATTC_Open)(tGATT_IF client_if, const RawAddress& remote_bda,
                         tBTM_BLE_CONN_TYPE connection_type, uint16_t preferred_mtu,
                         bool prefer_relax_mode);
};

//
// Returns the current GATT client interface
//
gatt_interface_t& get_gatt_interface();

//
// Appends a callback entry into GATT client API/callback history
//
void gatt_history_callback(const std::string& entry);

//
// Dumps the GATT client API/callback history to dumpsys
//
void DumpsysBtaDmGattClient(int fd);

namespace bluetooth::testing {
std::vector<bluetooth::common::TimestampedEntry<std::string>> PullCopyOfGattHistory();
}  // namespace bluetooth::testing
