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

/******************************************************************************
 *
 *  This file contains the GATT Server action functions for the state
 *  machine.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "bta/gatt/bta_gatts_int.h"
#include "bta/include/bta_api.h"
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

/* GATTS control block */
tBTA_GATTS_CB bta_gatts_cb;

static void bta_gatts_nv_save_cback(bool is_saved, tGATTS_HNDL_RANGE* p_hndl_range);
static bool bta_gatts_nv_srv_chg_cback(tGATTS_SRV_CHG_CMD cmd, tGATTS_SRV_CHG_REQ* p_req,
                                       tGATTS_SRV_CHG_RSP* p_rsp);

static void bta_gatts_start_if(tGATT_IF server_if);

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
  if (bta_gatts_cb.enabled) {
    log::verbose("GATTS already enabled.");
    return;
  }

  memset(&bta_gatts_cb, 0, sizeof(tBTA_GATTS_CB));

  bta_gatts_cb.enabled = true;

  gatt_load_bonded();

  if (!GATTS_NVRegister(&bta_gatts_nv_cback)) {
    log::error("BTA GATTS NV register failed.");
  }
}

void bta_gatts_api_disable() {
  if (!bta_gatts_cb.enabled) {
    log::error("GATTS not enabled");
    return;
  }

  for (uint8_t i = 0; i < BTA_GATTS_MAX_APP_NUM; i++) {
    if (bta_gatts_cb.rcb[i].in_use) {
      stack::appDeregister(bta_gatts_cb.rcb[i].gatt_if);
    }
  }
  memset(&bta_gatts_cb, 0, sizeof(tBTA_GATTS_CB));
}

void bta_gatts_register(const bluetooth::Uuid& app_uuid, const stack::tGATT_CBACK* p_cback,
                        bool eatt_support,
                        void (*p_reg_cb)(tGATT_STATUS status, tGATT_IF server_if,
                                         const bluetooth::Uuid& uuid)) {
  if (!bta_gatts_cb.enabled) {
    bta_gatts_enable();
  }

  for (uint8_t i = 0; i < BTA_GATTS_MAX_APP_NUM; i++) {
    if (!bta_gatts_cb.rcb[i].in_use) {
      continue;
    }
    if (bta_gatts_cb.rcb[i].app_uuid != app_uuid) {
      continue;
    }

    log::error("application already registered.");

    if (p_reg_cb) {
      p_reg_cb(GATT_DUP_REG, BTA_GATTS_INVALID_IF, app_uuid);
    }
    return;
  }

  uint8_t first_unuse = 0xff;

  for (uint8_t i = 0; i < BTA_GATTS_MAX_APP_NUM; i++) {
    if (bta_gatts_cb.rcb[i].in_use) {
      continue;
    }

    first_unuse = i;
    break;
  }

  if (first_unuse == 0xff) {
    if (p_reg_cb) {
      p_reg_cb(GATT_NO_RESOURCES, BTA_GATTS_INVALID_IF, app_uuid);
    }
    return;
  }

  log::info("register application first_unuse rcb_idx={}", first_unuse);

  bta_gatts_cb.rcb[first_unuse].in_use = true;
  bta_gatts_cb.rcb[first_unuse].p_cback = p_cback;
  bta_gatts_cb.rcb[first_unuse].app_uuid = app_uuid;
  bta_gatts_cb.rcb[first_unuse].gatt_if =
          stack::appRegister(app_uuid, "GattServer", p_cback, eatt_support);

  tGATT_STATUS status = GATT_SUCCESS;
  if (!bta_gatts_cb.rcb[first_unuse].gatt_if) {
    status = GATT_NO_RESOURCES;
  } else {
    do_in_main_thread(base::BindOnce(&bta_gatts_start_if, bta_gatts_cb.rcb[first_unuse].gatt_if));
  }

  if (p_reg_cb) {
    p_reg_cb(status, bta_gatts_cb.rcb[first_unuse].gatt_if, app_uuid);
  }
}

void bta_gatts_start_if(tGATT_IF server_if) {
  if (bta_gatts_find_app_rcb_by_app_if(server_if)) {
    stack::appStartIf(server_if);
  } else {
    log::error("Unable to start app.: Unknown interface={}", server_if);
  }
}

/* Deregister an application */
void bta_gatts_deregister(tGATT_IF server_if) {
  for (uint8_t i = 0; i < BTA_GATTS_MAX_APP_NUM; i++) {
    if (bta_gatts_cb.rcb[i].in_use && bta_gatts_cb.rcb[i].gatt_if == server_if) {
      /* deregister the app */
      stack::appDeregister(bta_gatts_cb.rcb[i].gatt_if);

      /* reset cb */
      memset(&bta_gatts_cb.rcb[i], 0, sizeof(tBTA_GATTS_RCB));
      break;
    }
  }
}

void bta_gatts_delete_service(tGATT_IF gatt_if, uint16_t service_id,
                              void (*p_delete_service_cb)(tGATT_STATUS status, tGATT_IF server_if,
                                                          uint16_t service_id)) {
  std::optional<Uuid> svc_uuid = GATTS_LookupServiceUuidByStartHandle(service_id);
  if (!svc_uuid) {
    log::error("can't delete service - no service {} found", service_id);
    return;
  }

  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(gatt_if);
  if (!p_rcb) {
    /* this is only useful thing of BTA layer, we ensure BTA apps can't stop internal services, if
     * they just guess the service_id (start_handle) */
    log::error("gatt_if={} not found", gatt_if);
    return;
  }

  tGATT_STATUS status;
  if (GATTS_DeleteService(p_rcb->gatt_if, &(svc_uuid.value()), service_id)) {
    status = GATT_SUCCESS;
  } else {
    status = GATT_ERROR;
  }

  if (p_delete_service_cb) {
    p_delete_service_cb(status, p_rcb->gatt_if, service_id);
  }
}

void bta_gatts_send_rsp(uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
                        std::unique_ptr<tGATTS_RSP> rsp) {
  if (GATTS_SendRsp(conn_id, trans_id, status, rsp.get()) != GATT_SUCCESS) {
    log::error("Sending response failed");
  }
}

void bta_gatts_indicate_handle(uint16_t conn_id, uint16_t attr_id, std::vector<uint8_t> value,
                               bool need_confirm) {
  tGATT_IF gatt_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;
  if (!GATT_GetConnectionInfor(conn_id, &gatt_if, remote_bda, &transport)) {
    log::error("Unknown connection_id=0x{:x} fail sending notification", conn_id);
    return;
  }

  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(gatt_if);
  if (!p_rcb) {
    log::error("server_if={} not found", gatt_if);
    return;
  }

  tGATT_STATUS status;
  if (need_confirm) {
    status = GATTS_HandleValueIndication(conn_id, attr_id, value.size(), value.data());
  } else {
    status = GATTS_HandleValueNotification(conn_id, attr_id, value.size(), value.data());
  }

  if (status == GATT_SUCCESS && need_confirm) {
    // in this case we will call p_conf_cb when handling GATTS_REQ_TYPE_CONF
    return;
  }

  if (p_rcb->p_cback && p_rcb->p_cback->p_req_cb) {
    if (status != GATT_SUCCESS) {
      p_rcb->p_cback->p_req_cb->conf_send_fail_cb(conn_id, status);
      return;
    }
    p_rcb->p_cback->p_req_cb->conf_cb(conn_id, 0, remote_bda);
  }
}

void bta_gatts_open(tGATT_IF server_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    bool is_direct, tBT_TRANSPORT transport) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(server_if);
  if (!p_rcb) {
    log::error("Inavlid server_if={}", server_if);
    return;
  }

  /* should always get the connection ID */
  if (transport == BT_TRANSPORT_BR_EDR) {
    std::ignore = GATT_BR_Connect(p_rcb->gatt_if, remote_bda);
  } else {
    tBTM_BLE_CONN_TYPE connection_type =
            is_direct ? BTM_BLE_DIRECT_CONNECTION : BTM_BLE_BKG_CONNECT_ALLOW_LIST;
    std::ignore = stack::leConnectionConnect(p_rcb->gatt_if, remote_bda, addr_type, connection_type,
                                             0, false, false);
  }
}

void bta_gatts_cancel_open(tGATT_IF server_if, const RawAddress& remote_bda, bool is_direct) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(server_if);
  if (!p_rcb) {
    log::error("Inavlid server_if={}", server_if);
    return;
  }

  if (!stack::leConnectionCancelConnect(p_rcb->gatt_if, remote_bda, is_direct)) {
    log::error("failed for open request");
  }
}

void bta_gatts_close(uint16_t conn_id) { std::ignore = GATT_Disconnect(conn_id); }

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
