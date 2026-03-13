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

static void bta_gatts_conn_cback(tGATT_IF gatt_if, const RawAddress& bda, tCONN_ID conn_id,
                                 bool connected, tGATT_DISCONN_REASON reason,
                                 tBT_TRANSPORT transport);
static void bta_gatts_read_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                const RawAddress& remote_bda, uint16_t handle,
                                                uint16_t offset, bool is_long);
static void bta_gatts_read_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                            const RawAddress& remote_bda, uint16_t handle,
                                            uint16_t offset, bool is_long);
static void bta_gatts_write_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                 const RawAddress& remote_bda, uint16_t handle,
                                                 uint16_t offset, bool need_rsp, bool is_prep,
                                                 uint8_t* value, uint16_t len);
static void bta_gatts_write_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                             const RawAddress& remote_bda, uint16_t handle,
                                             uint16_t offset, bool need_rsp, bool is_prep,
                                             uint8_t* value, uint16_t len);
static void bta_gatts_exec_write_cback(tCONN_ID conn_id, uint32_t trans_id,
                                       const RawAddress& remote_bda, tGATT_EXEC_FLAG exec_write);
static void bta_gatts_mtu_changed_cback(tCONN_ID conn_id, const RawAddress& remote_bda,
                                        uint16_t mtu);
static void bta_gatts_conf_cback(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda);

static stack::tGATT_REQ_CBACK bta_gatts_req_cback = {
        .read_characteristic_cb = bta_gatts_read_characteristic_cback,
        .read_descriptor_cb = bta_gatts_read_descriptor_cback,
        .write_characteristic_cb = bta_gatts_write_characteristic_cback,
        .write_descriptor_cb = bta_gatts_write_descriptor_cback,
        .exec_write_cb = bta_gatts_exec_write_cback,
        .mtu_changed_cb = bta_gatts_mtu_changed_cback,
        .conf_cb = bta_gatts_conf_cback,
};

static void bta_gatts_cong_cback(tCONN_ID conn_id, bool congested);
static void bta_gatts_phy_update_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint8_t tx_phy,
                                       uint8_t rx_phy, tGATT_STATUS status);
static void bta_gatts_conn_update_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t interval,
                                        uint16_t latency, uint16_t timeout, tGATT_STATUS status);
static void bta_gatts_subrate_chg_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t subrate_factor,
                                        uint16_t latency, uint16_t cont_num, uint16_t timeout,
                                        tGATT_SUBRATE_MODE subrate_mode, tGATT_STATUS status);
static void bta_gatts_characteristics_unoffloaded_cback(tGATT_IF gatt_if, tCONN_ID conn_id,
                                                        uint32_t session_id, tGATT_STATUS status);

static stack::tGATT_CBACK bta_gatts_cback = {
        .p_conn_cb = bta_gatts_conn_cback,
        .p_cmpl_cb = nullptr,
        .p_disc_res_cb = nullptr,
        .p_disc_cmpl_cb = nullptr,
        .p_req_cb = &bta_gatts_req_cback,
        .p_enc_cmpl_cb = nullptr,
        .p_congestion_cb = bta_gatts_cong_cback,
        .p_phy_update_cb = bta_gatts_phy_update_cback,
        .p_conn_update_cb = bta_gatts_conn_update_cback,
        .p_subrate_chg_cb = bta_gatts_subrate_chg_cback,
        .p_characteristics_unoffloaded_cb = bta_gatts_characteristics_unoffloaded_cback,
        .p_offloaded_service_chg_cb = nullptr,
};

static tGATT_APPL_INFO bta_gatts_nv_cback = {bta_gatts_nv_save_cback, bta_gatts_nv_srv_chg_cback};

#define CALL_REG_CB(GATT_IF, P_CB, ...)                                \
  do {                                                                 \
    tBTA_GATTS_RCB* p_reg = bta_gatts_find_app_rcb_by_app_if(GATT_IF); \
    if (!p_reg || !p_reg->p_cback) {                                   \
      log::error("server_if={} not found", GATT_IF);                   \
      return;                                                          \
    }                                                                  \
                                                                       \
    if (p_reg->p_cback && p_reg->p_cback->P_CB) {                      \
      p_reg->p_cback->P_CB(__VA_ARGS__);                               \
    }                                                                  \
  } while (0)

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

void bta_gatts_register(const bluetooth::Uuid& app_uuid, const tBTA_GATTS_CBACK* p_cback,
                        bool eatt_support) {
  tGATT_STATUS status = GATT_SUCCESS;

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

    if (p_cback && p_cback->p_reg_cb) {
      p_cback->p_reg_cb(GATT_DUP_REG, BTA_GATTS_INVALID_IF, app_uuid);
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
    if (p_cback && p_cback->p_reg_cb) {
      p_cback->p_reg_cb(GATT_NO_RESOURCES, BTA_GATTS_INVALID_IF, app_uuid);
    }
    return;
  }

  log::info("register application first_unuse rcb_idx={}", first_unuse);

  bta_gatts_cb.rcb[first_unuse].in_use = true;
  bta_gatts_cb.rcb[first_unuse].p_cback = p_cback;
  bta_gatts_cb.rcb[first_unuse].app_uuid = app_uuid;
  bta_gatts_cb.rcb[first_unuse].gatt_if =
          stack::appRegister(app_uuid, "GattServer", &bta_gatts_cback, eatt_support);
  if (!bta_gatts_cb.rcb[first_unuse].gatt_if) {
    status = GATT_NO_RESOURCES;
  } else {
    do_in_main_thread(base::BindOnce(&bta_gatts_start_if, bta_gatts_cb.rcb[first_unuse].gatt_if));
  }

  if (p_cback && p_cback->p_reg_cb) {
    p_cback->p_reg_cb(status, bta_gatts_cb.rcb[first_unuse].gatt_if, app_uuid);
  }
}

void bta_gatts_start_if(tGATT_IF server_if) {
  if (bta_gatts_find_app_rcb_by_app_if(server_if)) {
    stack::appStartIf(server_if);
  } else {
    log::error("Unable to start app.: Unknown interface={}", server_if);
  }
}
/*******************************************************************************
 *
 * Function         bta_gatts_deregister
 *
 * Description      deregister an application.
 *
 * Returns          none.
 *
 ******************************************************************************/
void bta_gatts_deregister(tGATT_IF server_if) {
  tGATT_STATUS status = GATT_ERROR;
  const tBTA_GATTS_CBACK* p_cback = NULL;
  uint8_t i;

  for (i = 0; i < BTA_GATTS_MAX_APP_NUM; i++) {
    if (bta_gatts_cb.rcb[i].in_use && bta_gatts_cb.rcb[i].gatt_if == server_if) {
      p_cback = bta_gatts_cb.rcb[i].p_cback;
      status = GATT_SUCCESS;

      /* deregister the app */
      stack::appDeregister(bta_gatts_cb.rcb[i].gatt_if);

      /* reset cb */
      memset(&bta_gatts_cb.rcb[i], 0, sizeof(tBTA_GATTS_RCB));
      break;
    }
  }

  if (p_cback && p_cback->p_dereg_cb) {
    p_cback->p_dereg_cb(status, server_if);
  } else {
    log::error("application not registered.");
  }
}

void bta_gatts_delete_service(tGATT_IF gatt_if, uint16_t service_id) {
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

  if (p_rcb->p_cback && p_rcb->p_cback->p_delete_service_cb) {
    p_rcb->p_cback->p_delete_service_cb(status, p_rcb->gatt_if, service_id);
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

  /* if over BR_EDR, inform PM for mode change */
  if (transport == BT_TRANSPORT_BR_EDR) {
    bta_sys_busy(BTA_ID_GATTS, BTA_ALL_APP_ID, remote_bda);
    bta_sys_idle(BTA_ID_GATTS, BTA_ALL_APP_ID, remote_bda);
  }

  if (status == GATT_SUCCESS && need_confirm) {
    // in this case we will call p_conf_cb when handling GATTS_REQ_TYPE_CONF
    return;
  }

  if (p_rcb->p_cback && p_rcb->p_cback->p_conf_cb) {
    p_rcb->p_cback->p_conf_cb(conn_id, status);
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
  bool success;
  if (transport == BT_TRANSPORT_BR_EDR) {
    success = GATT_BR_Connect(p_rcb->gatt_if, remote_bda);
  } else {
    tBTM_BLE_CONN_TYPE connection_type =
            is_direct ? BTM_BLE_DIRECT_CONNECTION : BTM_BLE_BKG_CONNECT_ALLOW_LIST;
    success = stack::leConnectionConnect(p_rcb->gatt_if, remote_bda, addr_type, connection_type, 0,
                                         false, false);
  }

  tGATT_STATUS status = GATT_ERROR;
  if (success) {
    status = GATT_SUCCESS;
    tCONN_ID conn_id;
    if (GATT_GetConnIdIfConnected(p_rcb->gatt_if, remote_bda, &conn_id, transport)) {
      status = GATT_ALREADY_OPEN;
    }
  }

  if (p_rcb->p_cback && p_rcb->p_cback->p_req_open_cb) {
    p_rcb->p_cback->p_req_open_cb(status);
  }
}

void bta_gatts_cancel_open(tGATT_IF server_if, const RawAddress& remote_bda, bool is_direct) {
  tGATT_STATUS status = GATT_ERROR;

  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(server_if);
  if (!p_rcb) {
    log::error("Inavlid server_if={}", server_if);
    return;
  }

  if (!stack::leConnectionCancelConnect(p_rcb->gatt_if, remote_bda, is_direct)) {
    log::error("failed for open request");
  } else {
    status = GATT_SUCCESS;
  }

  if (p_rcb->p_cback && p_rcb->p_cback->p_cancel_open_cb) {
    p_rcb->p_cback->p_cancel_open_cb(status);
  }
}

void bta_gatts_close(uint16_t conn_id) {
  tGATT_IF gatt_if;
  RawAddress remote_bda;
  tBT_TRANSPORT transport;

  if (!GATT_GetConnectionInfor(conn_id, &gatt_if, remote_bda, &transport)) {
    log::error("Unknown connection_id=0x{:x}", conn_id);
    return;
  }

  log::debug("Disconnecting gatt_if={}, remote_bda={}, transport={}", gatt_if, remote_bda,
             transport);
  tGATT_STATUS status = GATT_Disconnect(conn_id);
  if (status != GATT_SUCCESS) {
    log::error("fail conn_id={}", conn_id);
    status = GATT_ERROR;
  }

  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(gatt_if);
  if (!p_rcb || !p_rcb->p_cback) {
    return;
  }

  if (transport == BT_TRANSPORT_BR_EDR) {
    bta_sys_conn_close(BTA_ID_GATTS, BTA_ALL_APP_ID, remote_bda);
  }

  if (p_rcb->p_cback && p_rcb->p_cback->p_close_cb) {
    p_rcb->p_cback->p_close_cb(status);
  }
}

static tBTA_GATTS_RCB* bta_gatts_get_rcb_and_inform_pm(tCONN_ID conn_id,
                                                       const RawAddress& remote_bda) {
  tGATT_IF gatt_if;
  tBT_TRANSPORT transport;
  RawAddress unused_bda;

  if (!GATT_GetConnectionInfor(conn_id, &gatt_if, unused_bda, &transport)) {
    log::error("request received on unknown conn_id=0x{:x}", conn_id);
    return nullptr;
  }

  tBTA_GATTS_RCB* p_rcb = bta_gatts_find_app_rcb_by_app_if(gatt_if);

  if (!p_rcb || !p_rcb->p_cback) {
    log::error("connection request on gatt_if={} is not interested", gatt_if);
    return nullptr;
  }

  /* if over BR_EDR, inform PM for mode change */
  if (transport == BT_TRANSPORT_BR_EDR) {
    bta_sys_busy(BTA_ID_GATTS, BTA_ALL_APP_ID, remote_bda);
    bta_sys_idle(BTA_ID_GATTS, BTA_ALL_APP_ID, remote_bda);
  }

  return p_rcb;
}

static void bta_gatts_read_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                const RawAddress& remote_bda, uint16_t handle,
                                                uint16_t offset, bool is_long) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_read_characteristic_cb) {
    p_rcb->p_cback->p_read_characteristic_cb(conn_id, trans_id, remote_bda, handle, offset,
                                             is_long);
  }
}

static void bta_gatts_read_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                            const RawAddress& remote_bda, uint16_t handle,
                                            uint16_t offset, bool is_long) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_read_descriptor_cb) {
    p_rcb->p_cback->p_read_descriptor_cb(conn_id, trans_id, remote_bda, handle, offset, is_long);
  }
}

static void bta_gatts_write_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                                 const RawAddress& remote_bda, uint16_t handle,
                                                 uint16_t offset, bool need_rsp, bool is_prep,
                                                 uint8_t* value, uint16_t len) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_write_characteristic_cb) {
    p_rcb->p_cback->p_write_characteristic_cb(conn_id, trans_id, remote_bda, handle, offset,
                                              need_rsp, is_prep, value, len);
  }
}

static void bta_gatts_write_descriptor_cback(tCONN_ID conn_id, uint32_t trans_id,
                                             const RawAddress& remote_bda, uint16_t handle,
                                             uint16_t offset, bool need_rsp, bool is_prep,
                                             uint8_t* value, uint16_t len) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_write_descriptor_cb) {
    p_rcb->p_cback->p_write_descriptor_cb(conn_id, trans_id, remote_bda, handle, offset, need_rsp,
                                          is_prep, value, len);
  }
}

static void bta_gatts_exec_write_cback(tCONN_ID conn_id, uint32_t trans_id,
                                       const RawAddress& remote_bda, tGATT_EXEC_FLAG exec_write) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_exec_write_cb) {
    p_rcb->p_cback->p_exec_write_cb(conn_id, trans_id, remote_bda, exec_write);
  }
}

static void bta_gatts_mtu_changed_cback(tCONN_ID conn_id, const RawAddress& remote_bda,
                                        uint16_t mtu) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_mtu_changed_cb) {
    p_rcb->p_cback->p_mtu_changed_cb(conn_id, remote_bda, mtu);
  }
}

static void bta_gatts_conf_cback(tCONN_ID conn_id, uint32_t /*trans_id*/,
                                 const RawAddress& remote_bda) {
  tBTA_GATTS_RCB* p_rcb = bta_gatts_get_rcb_and_inform_pm(conn_id, remote_bda);
  if (p_rcb && p_rcb->p_cback->p_conf_cb) {
    p_rcb->p_cback->p_conf_cb(conn_id, GATT_SUCCESS);
  }
}

static void bta_gatts_conn_cback(tGATT_IF gatt_if, const RawAddress& bdaddr, tCONN_ID conn_id,
                                 bool connected, tGATT_DISCONN_REASON, tBT_TRANSPORT transport) {
  log::verbose("bda={} gatt_if= {}, conn_id=0x{:x} connected={}", bdaddr, gatt_if, conn_id,
               connected);

  if (connected) {
    btif_debug_conn_state(bdaddr, BTIF_DEBUG_CONNECTED, GATT_CONN_OK);
  } else {
    btif_debug_conn_state(bdaddr, BTIF_DEBUG_DISCONNECTED, GATT_CONN_OK);
  }

  /* there is no RM for GATT */
  if (transport == BT_TRANSPORT_BR_EDR) {
    if (connected) {
      bta_sys_conn_open(BTA_ID_GATTS, BTA_ALL_APP_ID, bdaddr);
    } else {
      bta_sys_conn_close(BTA_ID_GATTS, BTA_ALL_APP_ID, bdaddr);
    }
  }

  if (connected) {
    CALL_REG_CB(gatt_if, p_connect_cb, gatt_if, bdaddr, conn_id, transport);
  } else {
    CALL_REG_CB(gatt_if, p_disconnect_cb, gatt_if, bdaddr, conn_id, transport);
  }
}

static void bta_gatts_phy_update_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint8_t tx_phy,
                                       uint8_t rx_phy, tGATT_STATUS status) {
  CALL_REG_CB(gatt_if, p_phy_update_cb, gatt_if, conn_id, tx_phy, rx_phy, status);
}

static void bta_gatts_conn_update_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t interval,
                                        uint16_t latency, uint16_t timeout, tGATT_STATUS status) {
  CALL_REG_CB(gatt_if, p_conn_update_cb, gatt_if, conn_id, interval, latency, timeout, status);
}

static void bta_gatts_subrate_chg_cback(tGATT_IF gatt_if, tCONN_ID conn_id, uint16_t subrate_factor,
                                        uint16_t latency, uint16_t cont_num, uint16_t timeout,
                                        tGATT_SUBRATE_MODE subrate_mode, tGATT_STATUS status) {
  CALL_REG_CB(gatt_if, p_subrate_chg_cb, gatt_if, conn_id, subrate_factor, latency, cont_num,
              timeout, subrate_mode, status);
}

static void bta_gatts_cong_cback(tCONN_ID conn_id, bool congested) {
  tGATT_IF gatt_if;
  tBT_TRANSPORT transport;
  RawAddress remote_bda;

  if (!GATT_GetConnectionInfor(conn_id, &gatt_if, remote_bda, &transport)) {
    return;
  }

  CALL_REG_CB(gatt_if, p_congestion_cb, conn_id, congested);
}

static void bta_gatts_characteristics_unoffloaded_cback(tGATT_IF gatt_if, tCONN_ID conn_id,
                                                        uint32_t session_id, tGATT_STATUS status) {
  CALL_REG_CB(gatt_if, p_characteristics_unoffloaded_cb, conn_id, session_id, status);
}
