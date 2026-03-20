/******************************************************************************
 *
 *  Copyright 2003-2012 Broadcom Corporation
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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "bta/include/bta_api.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/sys/bta_sys.h"
#include "btif/include/btif_debug_conn.h"
#include "internal_include/bt_target.h"
#include "internal_include/bt_trace.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "stack/include/gatt_api.h"
#include "stack/include/main_thread.h"
#include "stack/include/stack_app.h"
#include "stack/include/stack_le_connection.h"

using namespace bluetooth;

namespace {
bool bta_gatts_enabled;
}

static void bta_gatts_nv_save_cback(bool is_saved, tGATTS_HNDL_RANGE* p_hndl_range);
static bool bta_gatts_nv_srv_chg_cback(tGATTS_SRV_CHG_CMD cmd, tGATTS_SRV_CHG_REQ* p_req,
                                       tGATTS_SRV_CHG_RSP* p_rsp);

static tGATT_APPL_INFO bta_gatts_nv_cback = {bta_gatts_nv_save_cback, bta_gatts_nv_srv_chg_cback};

/*******************************************************************************
 *
 * Function         bta_gatts_nv_save_cback
 *
 * Description      NV save callback function.
 *
 * Parameter        is_add: true is to add a handle range; otherwise is to
 *                          delete.
 * Returns          none.
 *
 ******************************************************************************/
static void bta_gatts_nv_save_cback(bool /*is_add*/, tGATTS_HNDL_RANGE* /*p_hndl_range*/) {}

/*******************************************************************************
 *
 * Function         bta_gatts_nv_srv_chg_cback
 *
 * Description      NV save callback function.
 *
 * Parameter        is_add: true is to add a handle range; otherwise is to
 *                          delete.
 * Returns          none.
 *
 ******************************************************************************/
static bool bta_gatts_nv_srv_chg_cback(tGATTS_SRV_CHG_CMD /*cmd*/, tGATTS_SRV_CHG_REQ* /*p_req*/,
                                       tGATTS_SRV_CHG_RSP* /*p_rsp*/) {
  return false;
}

static void bta_gatts_enable() {
  if (bta_gatts_enabled) {
    log::verbose("GATTS already enabled.");
    return;
  }

  bta_gatts_enabled = true;

  gatt_load_bonded();

  if (!GATTS_NVRegister(&bta_gatts_nv_cback)) {
    log::error("BTA GATTS NV register failed.");
  }
}

void BTA_GATTS_InitBonded(void) {
  log::info("");
  gatt_load_bonded();
}

void BTA_GATTS_Disable(void) {
  if (!bta_sys_is_register(BTA_ID_GATTS)) {
    log::warn("GATTS Module not enabled/already disabled");
    return;
  }

  if (!bta_gatts_enabled) {
    log::error("GATTS not enabled");
    return;
  }

  bta_sys_deregister(BTA_ID_GATTS);
}

tGATT_IF BTA_GATTS_AppRegister(const bluetooth::Uuid& app_uuid, const stack::tGATT_CBACK* p_cback,
                               bool eatt_support) {
  if (!bta_gatts_enabled) {
    bta_gatts_enable();
  }

  tGATT_IF gatt_if = stack::appRegister(app_uuid, "GattServer", p_cback, eatt_support);
  if (gatt_if) {
    do_in_main_thread(base::BindOnce(&stack::appStartIf, gatt_if));
  }
  return gatt_if;
}

void BTA_GATTS_AppDeregister(tGATT_IF gatt_if) { stack::appDeregister(gatt_if); }

tGATT_STATUS BTA_GATTS_AddService(tGATT_IF server_if, std::vector<btgatt_db_element_t>* service) {
  return GATTS_AddService(server_if, service->data(), service->size());
}

bool BTA_GATTS_DeleteService(tGATT_IF gatt_if, uint16_t service_id) {
  std::optional<Uuid> svc_uuid = GATTS_LookupServiceUuidByStartHandle(service_id);
  if (!svc_uuid) {
    log::error("can't delete service - no service {} found", service_id);
    return false;
  }

  return GATTS_DeleteService(gatt_if, &(svc_uuid.value()), service_id);
}

void BTA_GATTS_SendRsp(uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
                       std::unique_ptr<tGATTS_RSP> rsp) {
  if (GATTS_SendRsp(conn_id, trans_id, status, rsp.get()) != GATT_SUCCESS) {
    log::error("Sending response failed");
  }
}

tGATT_STATUS BTA_GATTS_HandleValueIndication(uint16_t conn_id, uint16_t attr_id,
                                             std::vector<uint8_t> value, bool need_confirm) {
  if (value.size() > GATT_MAX_ATTR_LEN) {
    log::error("data to indicate is too long");
    return GATT_ERROR;
  }

  tGATT_IF gatt_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;
  if (!GATT_GetConnectionInfor(conn_id, &gatt_if, remote_bda, &transport)) {
    log::error("Unknown connection_id=0x{:x} fail sending notification", conn_id);
    return GATT_ERROR;
  }

  if (need_confirm) {
    return GATTS_HandleValueIndication(conn_id, attr_id, value.size(), value.data());
  } else {
    return GATTS_HandleValueNotification(conn_id, attr_id, value.size(), value.data());
  }
}

static void notify_pm_br_gatt_conn_open(const RawAddress& bda) {
  bta_sys_conn_open(BTA_ID_GATTC, BTA_ALL_APP_ID, bda);
  bta_sys_conn_open(BTA_ID_GATTS, BTA_ALL_APP_ID, bda);
}

static void notify_pm_br_gatt_conn_close(const RawAddress& bda) {
  bta_sys_conn_close(BTA_ID_GATTC, BTA_ALL_APP_ID, bda);
  bta_sys_conn_close(BTA_ID_GATTS, BTA_ALL_APP_ID, bda);
}

static void notify_pm_br_gatt_client_op(const RawAddress& bda) {
  bta_sys_busy(BTA_ID_GATTC, BTA_ALL_APP_ID, bda);
  bta_sys_idle(BTA_ID_GATTC, BTA_ALL_APP_ID, bda);
}

static void notify_pm_br_gatt_server_op(const RawAddress& bda) {
  bta_sys_busy(BTA_ID_GATTS, BTA_ALL_APP_ID, bda);
  bta_sys_idle(BTA_ID_GATTS, BTA_ALL_APP_ID, bda);
}

void BTA_GATT_Init_gatt_pm_callbacks() {
  gatt_set_br_pm_callbacks(notify_pm_br_gatt_conn_open, notify_pm_br_gatt_conn_close,
                           notify_pm_br_gatt_client_op, notify_pm_br_gatt_server_op);
}
