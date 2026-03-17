/******************************************************************************
 *
 *  Copyright 2010-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This is the implementation of the API for GATT server of BTA.
 *
 ******************************************************************************/

#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "bta/gatt/bta_gatts_int.h"
#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;

void BTA_GATTS_Disable(void) {
  if (!bta_sys_is_register(BTA_ID_GATTS)) {
    log::warn("GATTS Module not enabled/already disabled");
    return;
  }

  do_in_main_thread(base::BindOnce(&bta_gatts_api_disable));
  bta_sys_deregister(BTA_ID_GATTS);
}

void BTA_GATTS_AppRegister(const bluetooth::Uuid& app_uuid, const tBTA_GATTS_CBACK* p_cback,
                           bool eatt_support,
                           void (*p_reg_cb)(tGATT_STATUS status, tGATT_IF server_if,
                                            const bluetooth::Uuid& uuid)) {
  do_in_main_thread(base::BindOnce(&bta_gatts_register, app_uuid, p_cback, eatt_support, p_reg_cb));
}

void BTA_GATTS_AppDeregister(tGATT_IF server_if) {
  do_in_main_thread(base::BindOnce(&bta_gatts_deregister, server_if));
}

static void bta_gatts_add_service_impl(tGATT_IF server_if, std::vector<btgatt_db_element_t> service,
                                       BTA_GATTS_AddServiceCb cb) {
  auto p_rcb = bta_gatts_find_app_rcb_by_app_if(server_if);
  if (!p_rcb) {
    std::move(cb).Run(GATT_ERROR, server_if, std::move(service));
    return;
  }

  tGATT_STATUS status = GATTS_AddService(server_if, service.data(), service.size());
  if (status != GATT_SERVICE_STARTED) {
    log::error("service creation failed.");
    std::move(cb).Run(GATT_ERROR, server_if, std::move(service));
    return;
  }

  std::move(cb).Run(GATT_SUCCESS, server_if, std::move(service));
  return;
}

void BTA_GATTS_AddService(tGATT_IF server_if, std::vector<btgatt_db_element_t> service,
                          BTA_GATTS_AddServiceCb cb) {
  do_in_main_thread(base::BindOnce(&bta_gatts_add_service_impl, server_if, std::move(service),
                                   std::move(cb)));
}

void BTA_GATTS_DeleteService(tGATT_IF server_if, uint16_t service_id,
                             void (*p_delete_service_cb)(tGATT_STATUS status, tGATT_IF server_if,
                                                         uint16_t service_id)) {
  do_in_main_thread(
          base::BindOnce(&bta_gatts_delete_service, server_if, service_id, p_delete_service_cb));
}

void BTA_GATTS_HandleValueIndication(uint16_t conn_id, uint16_t attr_id, std::vector<uint8_t> value,
                                     bool need_confirm) {
  if (value.size() > GATT_MAX_ATTR_LEN) {
    log::error("data to indicate is too long");
    return;
  }
  do_in_main_thread(
          base::BindOnce(&bta_gatts_indicate_handle, conn_id, attr_id, value, need_confirm));
}

void BTA_GATTS_SendRsp(uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
                       std::unique_ptr<tGATTS_RSP> rsp) {
  do_in_main_thread(base::BindOnce(&bta_gatts_send_rsp, conn_id, trans_id, status, std::move(rsp)));
}

void BTA_GATTS_Open(tGATT_IF server_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    bool is_direct, tBT_TRANSPORT transport) {
  do_in_main_thread(
          base::BindOnce(&bta_gatts_open, server_if, remote_bda, addr_type, is_direct, transport));
}

void BTA_GATTS_CancelOpen(tGATT_IF server_if, const RawAddress& remote_bda, bool is_direct) {
  do_in_main_thread(base::BindOnce(&bta_gatts_cancel_open, server_if, remote_bda, is_direct));
}

void BTA_GATTS_Close(uint16_t conn_id) {
  do_in_main_thread(base::BindOnce(&bta_gatts_close, conn_id));
}

void BTA_GATTS_InitBonded(void) {
  log::info("");
  do_in_main_thread(base::BindOnce(&gatt_load_bonded));
}

void BTA_GATTS_OffloadCharacteristics(tCONN_ID conn_id, std::vector<btgatt_db_element_t> service,
                                      uint64_t endpoint_id, uint64_t hub_id, int uid,
                                      std::string attribution_tag,
                                      std::promise<btgatt_offload_result_t> promise) {
  log::verbose("conn_id: {}, endpoint_id: {}, hub_id: {}, uid: {}, attribution_tag: {}", conn_id,
               endpoint_id, hub_id, uid, attribution_tag);
  GATTS_OffloadCharacteristics(conn_id, service.data(), service.size(), endpoint_id, hub_id, uid,
                               std::move(attribution_tag), std::move(promise));
}

void BTA_GATTS_UnoffloadCharacteristics(tCONN_ID conn_id, int session_id) {
  do_in_main_thread(base::BindOnce(&GATTS_UnoffloadCharacteristics, conn_id, session_id));
}
