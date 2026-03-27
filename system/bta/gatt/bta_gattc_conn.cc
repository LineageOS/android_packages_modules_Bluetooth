
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

#define LOG_TAG "bt_bta_gattc"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>

#include "bta/gatt/bta_gattc_int.h"
#include "bta/include/bta_api.h"
#include "btif/include/btif_debug_conn.h"
#include "btif/include/btif_storage.h"
#include "device/include/interop.h"
#include "hardware/bt_gatt_types.h"
#include "hci/controller.h"
#include "main/shim/entry.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"
#include "stack/include/stack_app.h"
#include "stack/include/stack_le_connection.h"

using bluetooth::Uuid;
using namespace bluetooth;

/* send open callback */
static void bta_gattc_send_open_cback(tBTA_GATTC_RCB* p_clreg, tGATT_STATUS status,
                                      const RawAddress& remote_bda, tCONN_ID conn_id,
                                      tBT_TRANSPORT transport, uint16_t mtu) {
  tBTA_GATTC cb_data;

  if (p_clreg->p_cback) {
    memset(&cb_data, 0, sizeof(tBTA_GATTC));

    cb_data.open.status = status;
    cb_data.open.client_if = p_clreg->client_if;
    cb_data.open.conn_id = conn_id;
    cb_data.open.mtu = mtu;
    cb_data.open.transport = transport;
    cb_data.open.remote_bda = remote_bda;

    (*p_clreg->p_cback)(BTA_GATTC_OPEN_EVT, &cb_data);

    if (com_android_bluetooth_flags_gatt_conn_settings()) {
      if (GATT_DEF_BLE_MTU_SIZE != cb_data.open.mtu && cb_data.open.mtu) {
        tBTA_GATTC mtu_cb_data;
        mtu_cb_data.cfg_mtu.conn_id = conn_id;
        mtu_cb_data.cfg_mtu.status = status;
        mtu_cb_data.cfg_mtu.mtu = mtu;

        (*p_clreg->p_cback)(BTA_GATTC_CFG_MTU_EVT, &mtu_cb_data);
        bta_gattc_cl_set_reported_mtu(p_clreg->client_if, mtu);
      }
    }
  }
}

static void bta_gattc_init_bk_conn(tGATT_IF client_if, const RawAddress& remote_bda,
                                   tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                                   uint16_t preferred_mtu, bool prefer_relax_mode,
                                   bool auto_mtu_enabled, tBTA_GATTC_RCB* p_clreg);

static void bta_gattc_cancel_bk_conn(tGATT_IF client_if, const RawAddress& remote_bda);

void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                    uint16_t preferred_mtu, bool prefer_relax_mode, bool auto_mtu_enabled) {
  do_in_main_thread(base::Bind(&bta_gattc_process_api_open, client_if, remote_bda, addr_type,
                               connection_type, transport, preferred_mtu, prefer_relax_mode,
                               auto_mtu_enabled));
}

void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda,
                    tBTM_BLE_CONN_TYPE connection_type) {
  BTA_GATTC_Open(client_if, remote_bda, BLE_ADDR_PUBLIC, connection_type, BT_TRANSPORT_LE, 0, false,
                 false);
}

/** process connect API request */
void bta_gattc_process_api_open(tGATT_IF client_if, const RawAddress& remote_bda,
                                tBLE_ADDR_TYPE addr_type, tBTM_BLE_CONN_TYPE connection_type,
                                tBT_TRANSPORT transport, uint16_t preferred_mtu,
                                bool prefer_relax_mode, bool auto_mtu_enabled) {
  tBTA_GATTC_RCB* p_clreg = bta_gattc_cl_get_regcb(client_if);
  if (!p_clreg) {
    log::error("Failed, unknown client_if={}", client_if);
    return;
  }

  if ((connection_type == BTM_BLE_BKG_CONNECT_ALLOW_LIST) ||
      (connection_type == BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS)) {
    bta_gattc_init_bk_conn(client_if, remote_bda, connection_type, transport, preferred_mtu,
                           prefer_relax_mode, auto_mtu_enabled, p_clreg);
    return;
  }

  /* open/hold a connection */
  if (transport == BT_TRANSPORT_BR_EDR) {
    if (!GATT_BR_Connect(client_if, remote_bda)) {
      log::warn("Cannot establish Connection to {}. Return GATT_ERROR({})", remote_bda, GATT_ERROR);
      bta_gattc_send_open_cback(p_clreg, GATT_ERROR, remote_bda, GATT_INVALID_CONN_ID, transport,
                                0);
      return;
    }
  } else {
    // BT_TRANSPORT_LE
    if (!stack::leConnectionConnect(client_if, remote_bda, addr_type, connection_type,
                                    preferred_mtu, prefer_relax_mode, auto_mtu_enabled)) {
      log::warn("Cannot establish Connection to {}. Return GATT_ERROR({})", remote_bda, GATT_ERROR);
      bta_gattc_send_open_cback(p_clreg, GATT_ERROR, remote_bda, GATT_INVALID_CONN_ID, transport,
                                0);
      return;
    }
  }

  /* Re-enable notification registration for closed connection */
  for (int i = 0; i < BTA_GATTC_NOTIF_REG_MAX; i++) {
    if (p_clreg->notif_reg[i].in_use && p_clreg->notif_reg[i].remote_bda == remote_bda &&
        p_clreg->notif_reg[i].app_disconnected) {
      p_clreg->notif_reg[i].app_disconnected = false;
    }
  }

  tCONN_ID conn_id;
  if (GATT_GetConnIdIfConnected(client_if, remote_bda, &conn_id, transport)) {
    tBTA_GATTC_CLCB* p_clcb = bta_gattc_find_alloc_clcb(client_if, remote_bda, transport);
    if (p_clcb == nullptr) {
      log::error("No resources to open a new connection.");
      bta_gattc_send_open_cback(p_clreg, GATT_NO_RESOURCES, remote_bda, GATT_INVALID_CONN_ID,
                                transport, 0);
      return;
    }

    // Connection was already established, just initiate all the internal structs, start state
    // machine.
    p_clcb->bta_conn_id = conn_id;
    p_clcb->state = BTA_GATTC_CONN_ST;
    bta_gattc_conn(p_clcb);
    return;
  }

  /* we're establishing connection, wait for the callback event */
  p_clreg->connecting_to.insert(remote_bda);
}

void BTA_GATTC_CancelOpen(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) {
  do_in_main_thread(
          base::Bind(&bta_gattc_process_api_open_cancel, client_if, remote_bda, is_direct));
}

/** process connect API request */
void bta_gattc_process_api_open_cancel(tGATT_IF client_if, const RawAddress& remote_bda,
                                       bool is_direct) {
  if (!is_direct) {
    log::debug("Cancel GATT client background connection");
    bta_gattc_cancel_bk_conn(client_if, remote_bda);
    return;
  }
  log::debug("Cancel GATT client direct connection");

  tBTA_GATTC_RCB* p_clreg = bta_gattc_cl_get_regcb(client_if);
  if (!p_clreg) {
    log::info("No clreg, no direct connection to cancel to {}", remote_bda);
    return;
  }
  p_clreg->connecting_to.erase(remote_bda);

  std::ignore = stack::leConnectionCancelConnect(p_clreg->client_if, remote_bda, true);
}

/** Process API Open for a background connection */
static void bta_gattc_init_bk_conn(tGATT_IF client_if, const RawAddress& remote_bda,
                                   tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                                   uint16_t preferred_mtu, bool prefer_relax_mode,
                                   bool auto_mtu_enabled, tBTA_GATTC_RCB* p_clreg) {
  if (!bta_gattc_mark_bg_conn(client_if, remote_bda, true)) {
    log::warn("Unable to find space for accept list connection mask");
    bta_gattc_send_open_cback(p_clreg, GATT_NO_RESOURCES, remote_bda, GATT_INVALID_CONN_ID,
                              BT_TRANSPORT_LE, 0);
    return;
  }

  if (transport != BT_TRANSPORT_LE) {
    log::error("Background connect is just for LE transport! bd_addr={}", remote_bda);
    bta_gattc_send_open_cback(p_clreg, GATT_ILLEGAL_PARAMETER, remote_bda, GATT_INVALID_CONN_ID,
                              BT_TRANSPORT_LE, 0);
    return;
  }

  /* always call open to hold a connection */
  if (!stack::leConnectionConnect(client_if, remote_bda, BLE_ADDR_PUBLIC, connection_type,
                                  preferred_mtu, prefer_relax_mode, auto_mtu_enabled)) {
    log::error("Unable to connect to remote bd_addr={}", remote_bda);
    bta_gattc_send_open_cback(p_clreg, GATT_ILLEGAL_PARAMETER, remote_bda, GATT_INVALID_CONN_ID,
                              BT_TRANSPORT_LE, 0);
    return;
  }

  tCONN_ID conn_id;
  if (!GATT_GetConnIdIfConnected(client_if, remote_bda, &conn_id, transport)) {
    log::info("Not a connected remote device yet");
    return;
  }

  tBTA_GATTC_CLCB* p_clcb = bta_gattc_find_alloc_clcb(client_if, remote_bda, BT_TRANSPORT_LE);
  if (!p_clcb) {
    log::warn("Unable to find connection link for device:{}", remote_bda);
    return;
  }

  p_clcb->bta_conn_id = conn_id;
  p_clcb->state = BTA_GATTC_CONN_ST;
  bta_gattc_conn(p_clcb);
}

/** Process API Cancel Open for a background connection */
static void bta_gattc_cancel_bk_conn(tGATT_IF client_if, const RawAddress& remote_bda) {
  /* remove the device from the bg connection mask */
  if (bta_gattc_mark_bg_conn(client_if, remote_bda, false)) {
    if (!stack::leConnectionCancelConnect(client_if, remote_bda, false)) {
      log::error("failed for client_if={}, remote_bda={}, is_direct=false",
                 static_cast<int>(client_if), remote_bda);
    }
  }
}

/** receive connection callback from stack */
void bta_gattc_conn(tBTA_GATTC_CLCB* p_clcb) {
  log::verbose("server cache state={}", p_clcb->p_srcb->state);

  p_clcb->p_srcb->connected = true;

  if (p_clcb->p_srcb->mtu == 0) {
    log::verbose("MTU value being set to default MTU size");
    p_clcb->p_srcb->mtu = GATT_DEF_BLE_MTU_SIZE;
  }

  if (com_android_bluetooth_flags_gatt_conn_settings()) {
    if (p_clcb->p_srcb->mtu == GATT_DEF_BLE_MTU_SIZE) {
      // Set the default based on the APP's preference
      log::verbose("bd_addr: {}", p_clcb->bda);
      GATTC_SetDefaultMtu(p_clcb->bda);
    }
  }

  tBTA_GATTC_RCB* p_clreg = p_clcb->p_rcb;
  /* Re-enable notification registration for closed connection */
  for (int i = 0; i < BTA_GATTC_NOTIF_REG_MAX; i++) {
    if (p_clreg->notif_reg[i].in_use && p_clreg->notif_reg[i].remote_bda == p_clcb->bda &&
        p_clreg->notif_reg[i].app_disconnected) {
      p_clreg->notif_reg[i].app_disconnected = false;
    }
  }

  /* start database cache if needed */
  if (p_clcb->p_srcb->gatt_database.IsEmpty() || p_clcb->p_srcb->state != BTA_GATTC_SERV_IDLE) {
    if (p_clcb->p_srcb->state == BTA_GATTC_SERV_IDLE) {
      p_clcb->p_srcb->state = BTA_GATTC_SERV_LOAD;
      // Consider the case that if GATT Server is changed, but no service
      // changed indication is received, the database might be out of date. So
      // if robust caching is known to be supported, always check the db hash
      // first, before loading the stored database.

      // Only load the database if we are bonded, since the device cache is
      // meaningless otherwise (as we need to do rediscovery regardless)
      gatt::Database db =
              get_security_client_interface().BTM_IsBonded(p_clcb->bda, BT_TRANSPORT_AUTO)
                      ? bta_gattc_cache_load(p_clcb->p_srcb->server_bda)
                      : gatt::Database();
      auto robust_caching_support = GetRobustCachingSupport(p_clcb, db);
      log::info("Connected to {}, robust caching support is {}", p_clcb->bda,
                robust_caching_support);

      bool discovery_already_in_progress = false;
      if (!db.IsEmpty()) {
        if (p_clcb->p_srcb->srvc_hdl_chg == false) {
          log::info("{} conn_id=0x{:x} Will load gatt_database", p_clcb->bda, p_clcb->bta_conn_id);
          p_clcb->p_srcb->gatt_database = db;
        } else {
          discovery_already_in_progress = true;
          log::info("{} conn_id=0x{:x} Service discovery in progress, will not load database.",
                    p_clcb->bda, p_clcb->bta_conn_id);
          p_clcb->p_srcb->state = BTA_GATTC_SERV_IDLE;
          bta_gattc_set_state(p_clcb, BTA_GATTC_DISCOVER_ST);
        }
      }

      if (!discovery_already_in_progress) {
        if (db.IsEmpty() || robust_caching_support != RobustCachingSupport::UNSUPPORTED) {
          // If the peer device is expected to support robust caching, or if we
          // don't know its services yet, then we should do discovery (which may
          // short-circuit through a hash match, but might also do the full
          // discovery).
          p_clcb->p_srcb->state = BTA_GATTC_SERV_DISC;

          /* set true to read database hash before service discovery */
          p_clcb->p_srcb->srvc_hdl_db_hash = true;

          /* cache load failure, start discovery */
          bta_gattc_start_discover(p_clcb, NULL);
        } else {
          if (p_clcb->transport == BT_TRANSPORT_LE) {
            log::info("Using cached database without robust caching.");
            bluetooth::stack::l2cap::get_interface().L2CA_LockBleConnParamsForServiceDiscovery(
                    p_clcb->p_srcb->server_bda, false);
          }
          p_clcb->p_srcb->state = BTA_GATTC_SERV_IDLE;
          bta_gattc_reset_discover_st(p_clcb->p_srcb, GATT_SUCCESS);
        }
      }
    } else { /* cache is building */
      bta_gattc_set_state(p_clcb, BTA_GATTC_DISCOVER_ST);
    }
  } else {
    /* a pending service handle change indication */
    if (p_clcb->p_srcb->srvc_hdl_chg) {
      p_clcb->p_srcb->srvc_hdl_chg = false;

      /* set true to read database hash before service discovery */
      p_clcb->p_srcb->srvc_hdl_db_hash = true;

      /* start discovery */
      bta_gattc_sm_execute(p_clcb, BTA_GATTC_INT_DISCOVER_EVT, NULL);
    }
  }

  if (p_clcb->p_rcb) {
    bta_gattc_send_open_cback(p_clcb->p_rcb, GATT_SUCCESS, p_clcb->bda, p_clcb->bta_conn_id,
                              p_clcb->transport, p_clcb->p_srcb->mtu);
  }
}

void BTA_GATTC_Close(tCONN_ID conn_id) {
  BT_HDR_RIGID* p_buf = (BT_HDR_RIGID*)osi_malloc(sizeof(BT_HDR_RIGID));

  p_buf->event = BTA_GATTC_API_CLOSE_EVT;
  p_buf->layer_specific = static_cast<uint16_t>(conn_id);

  bta_sys_sendmsg(p_buf);
}

/** close a GATTC connection */
void bta_gattc_close(tBTA_GATTC_CLCB* p_clcb, const tBTA_GATTC_DATA* p_data) {
  tBTA_GATTC_CBACK* p_cback = p_clcb->p_rcb->p_cback;
  tBTA_GATTC_RCB* p_clreg = p_clcb->p_rcb;
  tBTA_GATTC cb_data = {
          .close =
                  {
                          .conn_id = p_clcb->bta_conn_id,
                          .status = GATT_SUCCESS,
                          .client_if = p_clcb->p_rcb->client_if,
                          .remote_bda = p_clcb->bda,
                          .transport = p_clcb->transport,
                          .reason = GATT_CONN_OK,
                  },
  };

  if (com_android_bluetooth_flags_le_subrate_manager()) {
    stack::leConnectionUpdateSubrateConfig(p_clcb->p_rcb->client_if, p_clcb->bda,
                                           GATT_SUBRATE_MODE_OFF, 0, 0, 0);
  }

  /* Disable notification registration for closed connection */
  for (int i = 0; i < BTA_GATTC_NOTIF_REG_MAX; i++) {
    if (p_clreg->notif_reg[i].in_use && p_clreg->notif_reg[i].remote_bda == p_clcb->bda) {
      p_clreg->notif_reg[i].app_disconnected = true;
    }
  }

  if (p_data->hdr.event == BTA_GATTC_INT_DISCONN_EVT) {
    /* Since link has been disconnected by and it is possible that here are
     * already some new p_clcb created for the background connect, the number of
     * p_srcb->num_clcb is NOT 0. This will prevent p_srcb to be cleared inside
     * the bta_gattc_clcb_dealloc.
     *
     * In this point of time, we know that link does not exist, so let's make
     * sure the connection state, mtu and database is cleared.
     */
    bta_gattc_server_disconnected(p_clcb->p_srcb);
  }

  bta_gattc_clcb_dealloc(p_clcb);

  if (p_data->hdr.event == BTA_GATTC_API_CLOSE_EVT) {
    cb_data.close.status = GATT_Disconnect(static_cast<tCONN_ID>(p_data->hdr.layer_specific));
    cb_data.close.reason = GATT_CONN_TERMINATE_LOCAL_HOST;
    log::debug("Local close event client_if:{} conn_id:{} reason:{}", cb_data.close.client_if,
               cb_data.close.conn_id,
               gatt_disconnection_reason_text(
                       static_cast<tGATT_DISCONN_REASON>(cb_data.close.reason)));
  } else if (p_data->hdr.event == BTA_GATTC_INT_DISCONN_EVT) {
    cb_data.close.status = static_cast<tGATT_STATUS>(p_data->int_conn.reason);
    cb_data.close.reason = p_data->int_conn.reason;
    log::debug("Peer close disconnect event client_if:{} conn_id:{} reason:{}",
               cb_data.close.client_if, cb_data.close.conn_id,
               gatt_disconnection_reason_text(
                       static_cast<tGATT_DISCONN_REASON>(cb_data.close.reason)));
  }

  if (p_cback) {
    (*p_cback)(BTA_GATTC_CLOSE_EVT, &cb_data);
  }
}

static bool is_interested_in_connection(tGATT_IF client_if, const RawAddress& remote_bda) {
  if (bta_gattc_check_bg_conn(client_if, remote_bda, HCI_ROLE_CENTRAL)) {
    return true;
  }
  tBTA_GATTC_RCB* p_clreg = bta_gattc_cl_get_regcb(client_if);
  if (!p_clreg) {
    return false;
  }

  if (p_clreg->connecting_to.contains(remote_bda)) {
    return true;
  }

  return false;
}

/** callback functions to GATT client stack */
void bta_gattc_conn_cback(tGATT_IF client_if, const RawAddress& remote_bda, tCONN_ID conn_id,
                          bool connected, tGATT_DISCONN_REASON reason, tBT_TRANSPORT transport) {
  if (connected) {
    if (!is_interested_in_connection(client_if, remote_bda)) {
      /* GATT clients are not interested in all connections, just ones we sign up for */
      return;
    }

    tBTA_GATTC_RCB* p_clreg = bta_gattc_cl_get_regcb(client_if);
    if (!p_clreg) {
      log::info("No clreg client_if:{} remote_bda:{}", client_if, remote_bda);
      std::ignore = GATT_Disconnect(conn_id);
      return;
    }
    p_clreg->connecting_to.erase(remote_bda);

    tBTA_GATTC_CLCB* p_clcb = bta_gattc_find_alloc_clcb(client_if, remote_bda, transport);
    if (!p_clcb) {
      log::warn("Unable to allocate connection link for device:{}", remote_bda);
      bta_gattc_send_open_cback(p_clreg, GATT_NO_RESOURCES, remote_bda, GATT_INVALID_CONN_ID,
                                transport, 0);
      std::ignore = GATT_Disconnect(conn_id);
      return;
    }

    p_clcb->bta_conn_id = conn_id;
    p_clcb->state = BTA_GATTC_CONN_ST;
    bta_gattc_conn(p_clcb);
    return;
  }

  tBTA_GATTC_DATA data;
  tBTA_GATTC_DATA* p_buf = &data;
  p_buf->int_conn.hdr.event = BTA_GATTC_INT_DISCONN_EVT;
  p_buf->int_conn.hdr.layer_specific = static_cast<uint16_t>(conn_id);
  p_buf->int_conn.client_if = client_if;
  p_buf->int_conn.role = bluetooth::stack::l2cap::get_interface().L2CA_GetBleConnRole(remote_bda);
  p_buf->int_conn.reason = reason;
  p_buf->int_conn.transport = transport;
  p_buf->int_conn.remote_bda = remote_bda;

  auto p_clcb = bta_gattc_find_int_disconn_clcb(p_buf);
  bool have_conn_id = (p_clcb && p_clcb->bta_conn_id != 0);
  if (have_conn_id) {
    bta_gattc_close(p_clcb, p_buf);
    return;
  }

  if (is_interested_in_connection(client_if, remote_bda)) {
    tBTA_GATTC_RCB* p_clreg = bta_gattc_cl_get_regcb(client_if);
    if (!p_clreg) {
      return;
    }

    if (reason == GATT_CONN_TIMEOUT) {
      log::warn(
              "Connection timed out after 30 seconds. conn_id=0x{:x}. Return "
              "GATT_CONNECTION_TIMEOUT({})",
              conn_id, GATT_CONNECTION_TIMEOUT);
      bta_gattc_send_open_cback(p_clreg, GATT_CONNECTION_TIMEOUT, remote_bda, conn_id, transport,
                                0);
    } else {
      log::warn("Cannot establish Connection. conn_id=0x{:x}. Return GATT_ERROR({})", conn_id,
                GATT_ERROR);
      bta_gattc_send_open_cback(p_clreg, GATT_ERROR, remote_bda, conn_id, transport, 0);
    }
  }
}
