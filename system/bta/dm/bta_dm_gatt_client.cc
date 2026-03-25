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

#include "bta/dm/bta_dm_gatt_client.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/string_helpers.h>
#include <bluetooth/types/uuid.h>

#include <cstdint>
#include <string>
#include <vector>

#include "bta/include/bta_gatt_api.h"
#include "main/shim/dumpsys.h"
#include "stack/btm/btm_int_types.h"

namespace {
TimestampedStringCircularBuffer gatt_history_{50};
constexpr char kTimeFormatString[] = "%Y-%m-%d %H:%M:%S";

constexpr unsigned MillisPerSecond = 1000;
std::string EpochMillisToString(uint64_t time_ms) {
  time_t time_sec = time_ms / MillisPerSecond;
  struct tm tm;
  localtime_r(&time_sec, &tm);
  std::string s = bluetooth::common::StringFormatTime(kTimeFormatString, tm);
  return std::format("{}.{:03}", s, time_ms % MillisPerSecond);
}
}  // namespace

static gatt_interface_t default_gatt_interface = {
        .BTA_GATTC_CancelOpen =
                [](tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) {
                  gatt_history_.Push(std::format("{:<32s} bd_addr:{} client_if:{} is_direct:{:c}",
                                                 "GATTC_CancelOpen", remote_bda, client_if,
                                                 is_direct ? 'T' : 'F'));
                  BTA_GATTC_CancelOpen(client_if, remote_bda, is_direct);
                },
        .BTA_GATTC_Refresh =
                [](tGATT_IF client_if, const RawAddress& remote_bda) {
                  gatt_history_.Push(std::format("{:<32s} bd_addr:{} client_if:{}", "GATTC_Refresh",
                                                 remote_bda, client_if));
                  BTA_GATTC_Refresh(client_if, remote_bda);
                },
        .BTA_GATTC_GetGattDb =
                [](tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                   btgatt_db_element_t** db, int* count) {
                  gatt_history_.Push(std::format("{:<32s} conn_id:{} start_handle:{} end:handle:{}",
                                                 "GATTC_GetGattDb", conn_id, start_handle,
                                                 end_handle));
                  BTA_GATTC_GetGattDb(conn_id, start_handle, end_handle, db, count);
                },
        .BTA_GATTC_AppRegister =
                [](const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                   BtaAppRegisterCallback cb, bool eatt_support) {
                  gatt_history_.Push(std::format("{:<32s} eatt_support:{:c}", "GATTC_AppRegister",
                                                 eatt_support ? 'T' : 'F'));
                  BTA_GATTC_AppRegister(name, p_client_cb, std::move(cb), eatt_support);
                },
        .BTA_GATTC_Close =
                [](tCONN_ID conn_id) {
                  gatt_history_.Push(std::format("{:<32s} conn_id:{}", "GATTC_Close", conn_id));
                  BTA_GATTC_Close(conn_id);
                },
        .BTA_GATTC_ServiceSearchRequest =
                [](tCONN_ID conn_id) {
                  gatt_history_.Push(
                          std::format("{:<32s} conn_id:{}", "GATTC_ServiceSearchRequest", conn_id));
                  BTA_GATTC_ServiceSearchRequest(conn_id);
                },
        .BTA_GATTC_Open =
                [](tGATT_IF client_if, const RawAddress& remote_bda,
                   tBTM_BLE_CONN_TYPE connection_type, uint16_t preferred_mtu,
                   bool prefer_relax_mode) {
                  gatt_history_.Push(std::format("{:<32s} bd_addr:{} client_if:{} type:0x{:x}",
                                                 "GATTC_Open", remote_bda, client_if,
                                                 connection_type));
                  BTA_GATTC_Open(client_if, remote_bda, BLE_ADDR_PUBLIC, connection_type,
                                 BT_TRANSPORT_LE, preferred_mtu, prefer_relax_mode);
                },
};

static gatt_interface_t* gatt_interface = &default_gatt_interface;

gatt_interface_t& get_gatt_interface() { return *gatt_interface; }

void gatt_history_callback(const std::string& entry) { gatt_history_.Push(entry); }

#define DUMPSYS_TAG "shim::legacy::bta::dm"
void DumpsysBtaDmGattClient(int fd) {
  auto gatt_history = gatt_history_.Pull();
  LOG_DUMPSYS(fd, " last %zu gatt history entries", gatt_history.size());
  for (const auto& it : gatt_history) {
    LOG_DUMPSYS(fd, "   %s %s", EpochMillisToString(it.timestamp).c_str(), it.entry.c_str());
  }
}
#undef DUMPSYS_TAG

namespace bluetooth::testing {

std::vector<bluetooth::common::TimestampedEntry<std::string>> PullCopyOfGattHistory() {
  return gatt_history_.Pull();
}

}  // namespace bluetooth::testing
