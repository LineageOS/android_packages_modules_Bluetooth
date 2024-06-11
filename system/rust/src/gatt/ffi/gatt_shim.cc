// Copyright 2022, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "gatt_shim.h"

#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/log.h>

#include <cstdint>
#include <optional>

#include "include/hardware/bluetooth.h"
#include "include/hardware/bt_common_types.h"
#include "include/hardware/bt_gatt_client.h"
#include "include/hardware/bt_gatt_server.h"
#include "os/log.h"
#include "rust/cxx.h"
#include "stack/include/gatt_api.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

bt_status_t do_in_jni_thread(base::OnceClosure task);

namespace {
std::optional<RawAddress> AddressOfConnection(uint16_t conn_id) {
  tGATT_IF gatt_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;
  auto valid =
      GATT_GetConnectionInfor(conn_id, &gatt_if, remote_bda, &transport);
  if (!valid) {
    return std::nullopt;
  }
  return remote_bda;
}
}  // namespace

namespace bluetooth {
namespace gatt {

void GattServerCallbacks::OnServerRead(uint16_t conn_id, uint32_t trans_id,
                                       uint16_t attr_handle,
                                       AttributeBackingType attr_type,
                                       uint32_t offset, bool is_long) const {
  auto addr = AddressOfConnection(conn_id);
  if (!addr.has_value()) {
    log::warn(
        "Dropping server read characteristic since connection {} not found",
        conn_id);
    return;
  }

  switch (attr_type) {
    case AttributeBackingType::CHARACTERISTIC:
      do_in_jni_thread(base::BindOnce(callbacks.request_read_characteristic_cb,
                                      conn_id, trans_id, addr.value(),
                                      attr_handle, offset, is_long));
      break;
    case AttributeBackingType::DESCRIPTOR:
      do_in_jni_thread(base::BindOnce(callbacks.request_read_descriptor_cb,
                                      conn_id, trans_id, addr.value(),
                                      attr_handle, offset, is_long));
      break;
    default:
      log::fatal("Unexpected backing type {}", attr_type);
  }
}

static void request_write_with_vec(request_write_callback cb, int conn_id,
                                   int trans_id, const RawAddress& bda,
                                   int attr_handle, int offset, bool need_rsp,
                                   bool is_prep,
                                   const std::vector<uint8_t>& value) {
  cb(conn_id, trans_id, bda, attr_handle, offset, need_rsp, is_prep,
     value.data(), value.size());
}

void GattServerCallbacks::OnServerWrite(
    uint16_t conn_id, uint32_t trans_id, uint16_t attr_handle,
    AttributeBackingType attr_type, uint32_t offset, bool need_response,
    bool is_prepare, ::rust::Slice<const uint8_t> value) const {
  auto addr = AddressOfConnection(conn_id);
  if (!addr.has_value()) {
    log::warn(
        "Dropping server write characteristic since connection {} not found",
        conn_id);
    return;
  }

  auto buf = std::vector<uint8_t>(value.begin(), value.end());

  switch (attr_type) {
    case AttributeBackingType::CHARACTERISTIC:
      do_in_jni_thread(base::BindOnce(
          request_write_with_vec, callbacks.request_write_characteristic_cb,
          conn_id, trans_id, addr.value(), attr_handle, offset, need_response,
          is_prepare, std::move(buf)));
      break;
    case AttributeBackingType::DESCRIPTOR:
      do_in_jni_thread(base::BindOnce(
          request_write_with_vec, callbacks.request_write_descriptor_cb,
          conn_id, trans_id, addr.value(), attr_handle, offset, need_response,
          is_prepare, std::move(buf)));
      break;
    default:
      log::fatal("Unexpected backing type {}", attr_type);
  }
}

void GattServerCallbacks::OnIndicationSentConfirmation(uint16_t conn_id,
                                                       int status) const {
  do_in_jni_thread(
      base::BindOnce(callbacks.indication_sent_cb, conn_id, status));
}

void GattServerCallbacks::OnExecute(uint16_t conn_id, uint32_t trans_id,
                                    bool execute) const {
  auto addr = AddressOfConnection(conn_id);
  if (!addr.has_value()) {
    log::warn("Dropping server execute write since connection {} not found",
              conn_id);
    return;
  }

  do_in_jni_thread(base::BindOnce(callbacks.request_exec_write_cb, conn_id,
                                  trans_id, addr.value(), execute));
}

}  // namespace gatt
}  // namespace bluetooth
