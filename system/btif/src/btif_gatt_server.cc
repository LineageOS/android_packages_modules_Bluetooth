/******************************************************************************
 *
 *  Copyright 2009-2013 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/*******************************************************************************
 *
 *  Filename:      btif_gatt_server.c
 *
 *  Description:   GATT server implementation
 *
 ******************************************************************************/

#define LOG_TAG "bt_btif_gatt"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>
#include <hardware/bt_gatt_types.h>
#include <string.h>

#include "bta/include/bta_gatt_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_dm.h"
#include "btif/include/btif_gatt.h"
#include "btif/include/btif_gatt_util.h"
#include "btif_status.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/main_thread.h"
#include "stack/include/stack_le_connection.h"

using base::BindOnce;
using bluetooth::Uuid;
using std::vector;
using namespace bluetooth;

namespace {
tBT_TRANSPORT to_bt_transport(int val) {
  switch (val) {
    case 0:
      return BT_TRANSPORT_AUTO;
    case 1:
      return BT_TRANSPORT_BR_EDR;
    case 2:
      return BT_TRANSPORT_LE;
    default:
      break;
  }
  log::warn("Passed unexpected transport value:{}", val);
  return BT_TRANSPORT_AUTO;
}

int to_java_transport(tBT_TRANSPORT transport) {
  switch (transport) {
    case BT_TRANSPORT_AUTO:
      return 0;
    case BT_TRANSPORT_BR_EDR:
      return 1;
    case BT_TRANSPORT_LE:
      return 2;
    default:
      break;
  }
  log::warn("Passed unexpected transport value:{}", transport);
  return 0;
}
}  // namespace

/*******************************************************************************
 *  Constants & Macros
 ******************************************************************************/

#define CHECK_BTGATT_INIT()                \
  do {                                     \
    if (bt_gatt_callbacks == NULL) {       \
      log::warn("BTGATT not initialized"); \
      return BtifStatus(NOT_READY);        \
    } else {                               \
      log::verbose("");                    \
    }                                      \
  } while (0)

/*******************************************************************************
 *  Static variables
 ******************************************************************************/

extern const btgatt_callbacks_t* bt_gatt_callbacks;

/*******************************************************************************
 *  Static functions
 ******************************************************************************/

#define HAL_CBACK_IN_JNI(P_CB, P_CBACK, ...)                    \
  do {                                                          \
    if ((P_CB) && (P_CB)->P_CBACK) {                            \
      bluetooth::log::verbose("HAL {}->{}", #P_CB, #P_CBACK);   \
      do_in_jni_thread(BindOnce((P_CB)->P_CBACK, __VA_ARGS__)); \
    } else {                                                    \
      ASSERTC(0, "Callback is NULL", 0);                        \
    }                                                           \
  } while (0)

static void btapp_gatts_reg_cback(tGATT_STATUS status, tGATT_IF server_if,
                                  const bluetooth::Uuid& uuid) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->register_server_cb, status, server_if, uuid);
}

static void btapp_gatts_dereg_cback(tGATT_STATUS /*status*/, tGATT_IF /*server_if*/) {
  // Empty
}

static void btapp_gatts_connect_cback(tGATT_IF server_if, const RawAddress& remote_bda,
                                      tCONN_ID conn_id, tBT_TRANSPORT transport) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->connection_cb, conn_id, server_if,
                   to_java_transport(transport), true, remote_bda);
}

static void btapp_gatts_disconnect_cback(tGATT_IF server_if, const RawAddress& remote_bda,
                                         tCONN_ID conn_id, tBT_TRANSPORT transport) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->connection_cb, conn_id, server_if,
                   to_java_transport(transport), false, remote_bda);
}

static void btapp_gatts_stop_service_cback(tGATT_STATUS status, tGATT_IF server_if,
                                           uint16_t service_id) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->service_stopped_cb, status, server_if, service_id);
}

static void btapp_gatts_delete_service_cback(tGATT_STATUS status, tGATT_IF server_if,
                                             uint16_t service_id) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->service_deleted_cb, status, server_if, service_id);
}

static void btapp_gatts_read_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                  const RawAddress& remote_bda, uint16_t handle,
                                                  uint16_t offset, bool is_long) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->request_read_characteristic_cb, conn_id, trans_id,
                   remote_bda, handle, offset, is_long);
}

static void btapp_gatts_read_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                              const RawAddress& remote_bda, uint16_t handle,
                                              uint16_t offset, bool is_long) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->request_read_descriptor_cb, conn_id, trans_id,
                   remote_bda, handle, offset, is_long);
}

static void btapp_gatts_write_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                   const RawAddress& remote_bda, uint16_t handle,
                                                   uint16_t offset, bool need_rsp, bool is_prep,
                                                   uint8_t* value, uint16_t len) {
  std::vector<uint8_t> val(value, value + len);
  do_in_jni_thread(BindOnce(
          [](tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda, uint16_t handle,
             uint16_t offset, bool need_rsp, bool is_prep, std::vector<uint8_t> value,
             uint16_t len) {
            auto callbacks = bt_gatt_callbacks;
            HAL_CBACK(callbacks, server->request_write_characteristic_cb, conn_id, trans_id,
                      remote_bda, handle, offset, need_rsp, is_prep, value.data(), len);
          },
          conn_id, trans_id, remote_bda, handle, offset, need_rsp, is_prep, std::move(val), len));
}

static void btapp_gatts_write_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                               const RawAddress& remote_bda, uint16_t handle,
                                               uint16_t offset, bool need_rsp, bool is_prep,
                                               uint8_t* value, uint16_t len) {
  std::vector<uint8_t> val(value, value + len);
  do_in_jni_thread(BindOnce(
          [](tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda, uint16_t handle,
             uint16_t offset, bool need_rsp, bool is_prep, std::vector<uint8_t> value,
             uint16_t len) {
            auto callbacks = bt_gatt_callbacks;
            HAL_CBACK(callbacks, server->request_write_descriptor_cb, conn_id, trans_id, remote_bda,
                      handle, offset, need_rsp, is_prep, value.data(), len);
          },
          conn_id, trans_id, remote_bda, handle, offset, need_rsp, is_prep, std::move(val), len));
}

static void btapp_gatts_exec_write_cback(tCONN_ID conn_id, uint32_t trans_id,
                                         const RawAddress& remote_bda, tGATT_EXEC_FLAG exec_write) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->request_exec_write_cb, conn_id, trans_id, remote_bda,
                   exec_write);
}

static void btapp_gatts_mtu_changed_cback(tCONN_ID conn_id, uint32_t /*trans_id*/,
                                          const RawAddress& /*remote_bda*/, uint16_t mtu) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->mtu_changed_cb, conn_id, mtu);
}

static void btapp_gatts_conf_cback(tCONN_ID conn_id, tGATT_STATUS status) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->indication_sent_cb, conn_id, status);
}

static void btapp_gatts_congestion_cback(tCONN_ID conn_id, bool congested) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->congestion_cb, conn_id, congested);
}

static void btapp_gatts_phy_update_cback(tGATT_IF /*server_if*/, tCONN_ID conn_id, uint8_t tx_phy,
                                         uint8_t rx_phy, tGATT_STATUS status) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->phy_updated_cb, conn_id, tx_phy, rx_phy, status);
}

static void btapp_gatts_conn_update_cback(tGATT_IF /*server_if*/, tCONN_ID conn_id,
                                          uint16_t interval, uint16_t latency, uint16_t timeout,
                                          tGATT_STATUS status) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->conn_updated_cb, conn_id, interval, latency, timeout,
                   status);
}

static void btapp_gatts_subrate_chg_cback(tGATT_IF /*server_if*/, tCONN_ID conn_id,
                                          uint16_t subrate_factor, uint16_t latency,
                                          uint16_t cont_num, uint16_t timeout,
                                          tGATT_SUBRATE_MODE subrate_mode, tGATT_STATUS status) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->subrate_chg_cb, conn_id, subrate_factor, latency,
                   cont_num, timeout, subrate_mode, status);
}

static void btapp_gatts_req_open_cback(tGATT_STATUS /*status*/) {
  // Empty
}

static void btapp_gatts_cancel_open_cback(tGATT_STATUS /*status*/) {
  // Empty
}

static void btapp_gatts_close_cback(tGATT_STATUS /*status*/) {
  // Empty
}

static void btapp_gatts_characteristics_unoffloaded_cback(tCONN_ID conn_id, uint32_t session_id,
                                                          tGATT_STATUS status) {
  HAL_CBACK_IN_JNI(bt_gatt_callbacks, server->characteristics_unoffloaded_cb, conn_id, session_id,
                   status);
}

static const tBTA_GATTS_CBACK btapp_gatts_callbacks = {
        .p_reg_cb = btapp_gatts_reg_cback,
        .p_dereg_cb = btapp_gatts_dereg_cback,
        .p_connect_cb = btapp_gatts_connect_cback,
        .p_disconnect_cb = btapp_gatts_disconnect_cback,
        .p_stop_service_cb = btapp_gatts_stop_service_cback,
        .p_delete_service_cb = btapp_gatts_delete_service_cback,
        .p_read_characteristic_cb = btapp_gatts_read_characteristic_cback,
        .p_read_descriptor_cb = btapp_gatts_read_descriptor_cback,
        .p_write_characteristic_cb = btapp_gatts_write_characteristic_cback,
        .p_write_descriptor_cb = btapp_gatts_write_descriptor_cback,
        .p_exec_write_cb = btapp_gatts_exec_write_cback,
        .p_mtu_changed_cb = btapp_gatts_mtu_changed_cback,
        .p_conf_cb = btapp_gatts_conf_cback,
        .p_congestion_cb = btapp_gatts_congestion_cback,
        .p_phy_update_cb = btapp_gatts_phy_update_cback,
        .p_conn_update_cb = btapp_gatts_conn_update_cback,
        .p_subrate_chg_cb = btapp_gatts_subrate_chg_cback,
        .p_req_open_cb = btapp_gatts_req_open_cback,
        .p_cancel_open_cb = btapp_gatts_cancel_open_cback,
        .p_close_cb = btapp_gatts_close_cback,
        .p_characteristics_unoffloaded_cb = btapp_gatts_characteristics_unoffloaded_cback,
};

/*******************************************************************************
 *  Server API Functions
 ******************************************************************************/
static BtStatus btif_gatts_register_app(const Uuid& bt_uuid, bool eatt_support) {
  CHECK_BTGATT_INIT();

  return do_in_jni_thread(
          BindOnce(&BTA_GATTS_AppRegister, bt_uuid, &btapp_gatts_callbacks, eatt_support));
}

static BtStatus btif_gatts_unregister_app(int server_if) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(BindOnce(&BTA_GATTS_AppDeregister, server_if));
}

static void btif_gatts_open_impl_use_address_type(int server_if, const RawAddress& address,
                                                  tBLE_ADDR_TYPE addr_type, bool is_direct,
                                                  tBT_TRANSPORT transport) {
  int device_type = BT_DEVICE_TYPE_UNKNOWN;
  if (btif_get_address_type(address, &addr_type) && btif_get_device_type(address, &device_type) &&
      device_type != BT_DEVICE_TYPE_BREDR) {
    BTA_DmAddBleDevice(address, addr_type, device_type);
  }

  // Determine transport
  if (transport == BT_TRANSPORT_AUTO) {
    switch (device_type) {
      case BT_DEVICE_TYPE_BREDR:
        transport = BT_TRANSPORT_BR_EDR;
        break;

      case BT_DEVICE_TYPE_BLE:
      case BT_DEVICE_TYPE_DUMO:
        transport = BT_TRANSPORT_LE;
        break;

      default:
        log::error("Unknown device type {}", DeviceTypeText(device_type));
        // transport must not be AUTO for finding control blocks. Use LE for backward compatibility.
        transport = BT_TRANSPORT_LE;
        break;
    }
  }

  log::info("addr_type:{}, transport:{}", addr_type, bt_transport_text(transport));
  BTA_GATTS_Open(server_if, address, addr_type, is_direct, transport);
}

static BtStatus btif_gatts_open(int server_if, const RawAddress& bd_addr, uint8_t addr_type,
                                bool is_direct, int transport) {
  CHECK_BTGATT_INIT();

  return do_in_jni_thread(BindOnce(&btif_gatts_open_impl_use_address_type, server_if, bd_addr,
                                   addr_type, is_direct, to_bt_transport(transport)));
}

static void btif_gatts_close_impl(int server_if, const RawAddress& address, int conn_id) {
  // Close active connection
  if (conn_id != 0) {
    BTA_GATTS_Close(static_cast<tCONN_ID>(conn_id));
  } else {
    BTA_GATTS_CancelOpen(server_if, address, true);
  }

  // Cancel pending background connections
  BTA_GATTS_CancelOpen(server_if, address, false);
}

static BtStatus btif_gatts_close(int server_if, const RawAddress& bd_addr, int conn_id) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(BindOnce(&btif_gatts_close_impl, server_if, bd_addr, conn_id));
}

static void on_service_added_cb(tGATT_STATUS status, int server_if,
                                vector<btgatt_db_element_t> service) {
  auto callbacks = bt_gatt_callbacks;
  HAL_CBACK(callbacks, server->service_added_cb, status, server_if, service.data(), service.size());
}

static void add_service_impl(int server_if, vector<btgatt_db_element_t> service) {
  // TODO(jpawlowski): btif should be a pass through layer, and no checks should
  // be made here. This exception is added only until GATT server code is
  // refactored, and one can distinguish stack-internal aps from external apps
  if (service[0].uuid == Uuid::From16Bit(UUID_SERVCLASS_GATT_SERVER) ||
      service[0].uuid == Uuid::From16Bit(UUID_SERVCLASS_GAP_SERVER)) {
    log::error("Attempt to register restricted service");
    auto callbacks = bt_gatt_callbacks;
    HAL_CBACK(callbacks, server->service_added_cb, BtifStatus(AUTH_REJECTED), server_if,
              service.data(), service.size());
    return;
  }

  BTA_GATTS_AddService(server_if, service,
                       jni_thread_wrapper(base::BindOnce(&on_service_added_cb)));
}

static BtStatus btif_gatts_add_service(int server_if, const btgatt_db_element_t* service,
                                       size_t service_count) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(
          BindOnce(&add_service_impl, server_if, std::vector(service, service + service_count)));
}

static BtStatus btif_gatts_stop_service(int /* server_if */, int service_handle) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(BindOnce(&BTA_GATTS_StopService, service_handle));
}

static BtStatus btif_gatts_delete_service(int /* server_if */, int service_handle) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(BindOnce(&BTA_GATTS_DeleteService, service_handle));
}

static BtStatus btif_gatts_send_indication(int /* server_if */, int attribute_handle, int conn_id,
                                           int confirm, const uint8_t* value, size_t length) {
  CHECK_BTGATT_INIT();

  if (length > GATT_MAX_ATTR_LEN) {
    length = GATT_MAX_ATTR_LEN;
  }

  return do_in_jni_thread(BindOnce(&BTA_GATTS_HandleValueIndication, static_cast<tCONN_ID>(conn_id),
                                   attribute_handle, std::vector(value, value + length), confirm));
  // TODO: Might need to send an ACK if handle value indication is
  //       invoked without need for confirmation.
}

static void btif_gatts_send_response_impl(int conn_id, int trans_id, int status,
                                          btgatt_response_t response) {
  std::unique_ptr<tGATTS_RSP> rsp_struct = std::make_unique<tGATTS_RSP>();
  btif_to_bta_response(rsp_struct.get(), &response);

  uint16_t handle = rsp_struct->attr_value.handle;
  BTA_GATTS_SendRsp(static_cast<tCONN_ID>(conn_id), trans_id, static_cast<tGATT_STATUS>(status),
                    std::move(rsp_struct));

  auto callbacks = bt_gatt_callbacks;
  HAL_CBACK(callbacks, server->response_confirmation_cb, 0, handle);
}

static BtStatus btif_gatts_send_response(int conn_id, int trans_id, int status,
                                         const btgatt_response_t& response) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(
          BindOnce(&btif_gatts_send_response_impl, conn_id, trans_id, status, response));
}

static BtStatus btif_gatts_set_preferred_phy(const RawAddress& bd_addr, uint8_t tx_phy,
                                             uint8_t rx_phy, uint16_t phy_options) {
  CHECK_BTGATT_INIT();
  do_in_main_thread(BindOnce(&stack::leConnectionSetPhy, bd_addr, tx_phy, rx_phy, phy_options));
  return BtifStatus();
}

static BtStatus btif_gatts_read_phy(
        const RawAddress& bd_addr,
        base::OnceCallback<void(uint8_t tx_phy, uint8_t rx_phy, uint8_t status)> cb) {
  CHECK_BTGATT_INIT();
  do_in_main_thread(
          BindOnce(&stack::leConnectionReadPhy, bd_addr, jni_thread_wrapper(std::move(cb))));
  return BtifStatus();
}

static BtStatus btif_gatts_offload_characteristics(int conn_id, btgatt_db_element_t* service,
                                                   size_t elements_count, uint64_t endpoint_id,
                                                   uint64_t hub_id, int uid,
                                                   std::string attribution_tag,
                                                   btgatt_offload_result_t* result) {
  CHECK_BTGATT_INIT();
  std::promise<btgatt_offload_result_t> promise;
  std::future future = promise.get_future();

  BtStatus status = do_in_main_thread(
          base::BindOnce(&BTA_GATTS_OffloadCharacteristics, static_cast<tCONN_ID>(conn_id),
                         std::vector(service, service + elements_count), endpoint_id, hub_id, uid,
                         std::move(attribution_tag), std::move(promise)));
  if (!status) {
    return status;
  }
  log::info("Waiting for request status");
  auto request_status = future.wait_for(std::chrono::seconds(5));

  if (request_status != std::future_status::ready) {
    log::error("Offload request is not ready");
    return BtifStatus(TIMEOUT);
  }
  btgatt_offload_result_t request_result = future.get();
  log::info("session_id: {} status: {}", request_result.session_id, request_result.status);
  *result = request_result;
  return BtifStatus();
}

static BtStatus btif_gatts_unoffload_characteristics(int conn_id, int session_id) {
  CHECK_BTGATT_INIT();
  return do_in_jni_thread(BindOnce(base::IgnoreResult(&BTA_GATTS_UnoffloadCharacteristics),
                                   static_cast<tCONN_ID>(conn_id), session_id));
}

const btgatt_server_interface_t btgattServerInterface = {btif_gatts_register_app,
                                                         btif_gatts_unregister_app,
                                                         btif_gatts_open,
                                                         btif_gatts_close,
                                                         btif_gatts_add_service,
                                                         btif_gatts_stop_service,
                                                         btif_gatts_delete_service,
                                                         btif_gatts_send_indication,
                                                         btif_gatts_send_response,
                                                         btif_gatts_set_preferred_phy,
                                                         btif_gatts_read_phy,
                                                         btif_gatts_offload_characteristics,
                                                         btif_gatts_unoffload_characteristics};
