/*
 * Copyright 2026 The Android Open Source Project
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

#define LOG_TAG "stack_app"

#include <bluetooth/log.h>

#include <string>

#include "internal_include/bt_target.h"
#include "internal_include/stack_config.h"
#include "stack/connection_manager/connection_manager.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/gatt_api.h"

using namespace bluetooth;

using bluetooth::Uuid;

namespace bluetooth::stack {

// 0xF1 ~ 0xFF are reserved for special use cases.
inline constexpr tGATT_IF GATT_IF_MAX = static_cast<tGATT_IF>(0xf8);

static tGATT_IF FindNextFreeClRcbId();

/*******************************************************************************
 *
 * Function         stack::appRegister
 *
 * Description      This function is called to register an  application
 *                  with GATT
 *
 * Parameter        p_app_uuid128: Application UUID
 *                  p_cb_info: callback functions.
 *                  eatt_support: indicate eatt support.
 *
 * Returns          0 for error, otherwise the index of the client registered
 *                  with GATT
 *
 ******************************************************************************/
tGATT_IF appRegister(const Uuid& app_uuid128, const std::string& name, const tGATT_CBACK* p_cb_info,
                     bool eatt_support) {
  for (auto& [gatt_if, p_reg] : gatt_cb.cl_rcb_map) {
    if (p_reg->app_uuid128 == app_uuid128) {
      log::error("Application already registered, uuid={}", app_uuid128.ToString());
      return 0;
    }
  }

  if (stack_config_get_interface()->get_pts_use_eatt_for_all_services()) {
    log::info("PTS: Force to use EATT for servers");
    eatt_support = true;
  }

  if (gatt_cb.cl_rcb_map.size() >= GATT_IF_MAX) {
    log::error("Unable to register GATT client, MAX client reached: {}", gatt_cb.cl_rcb_map.size());
    return 0;
  }

  tGATT_IF gatt_if = FindNextFreeClRcbId();
  if (gatt_if == GATT_IF_INVALID) {
    return gatt_if;
  }

  auto [it, ret] = gatt_cb.cl_rcb_map.emplace(gatt_if, std::make_unique<tGATT_REG>());
  tGATT_REG* p_reg = it->second.get();
  p_reg->app_uuid128 = app_uuid128;
  p_reg->gatt_if = gatt_if;
  p_reg->app_cb = *p_cb_info;
  p_reg->in_use = true;
  p_reg->eatt_support = eatt_support;
  p_reg->name = name;
  log::info("Allocated name:{} uuid:{} gatt_if:{} eatt_support:{}", name, app_uuid128.ToString(),
            p_reg->gatt_if, eatt_support);

  return gatt_if;
}

static tGATT_IF FindNextFreeClRcbId() {
  tGATT_IF gatt_if = gatt_cb.last_gatt_if;
  for (int i = 0; i < GATT_IF_MAX; i++) {
    if (++gatt_if > GATT_IF_MAX) {
      gatt_if = static_cast<tGATT_IF>(1);
    }
    if (!gatt_cb.cl_rcb_map.contains(gatt_if)) {
      gatt_cb.last_gatt_if = gatt_if;
      return gatt_if;
    }
  }
  log::error("Unable to register GATT client, MAX client reached: {}", gatt_cb.cl_rcb_map.size());

  return GATT_IF_INVALID;
}

/*******************************************************************************
 *
 * Function         stack::appDeregister
 *
 * Description      This function deregistered the application from GATT.
 *
 * Parameters       gatt_if: application interface.
 *
 * Returns          None.
 *
 ******************************************************************************/
void appDeregister(tGATT_IF gatt_if) {
  log::info("gatt_if={}", gatt_if);

  tGATT_REG* p_reg = gatt_get_regcb(gatt_if);
  /* Index 0 is GAP and is never deregistered */
  if ((gatt_if == 0) || (p_reg == NULL)) {
    log::error("Unable to deregister client with invalid gatt_if={}", gatt_if);
    return;
  }

  /* stop all services  */
  /* todo an application can not be deregistered if its services is also used by
    other application
    deregistration need to be performed in an orderly fashion
    no check for now */
  for (auto it = gatt_cb.srv_list_info->begin(); it != gatt_cb.srv_list_info->end();) {
    if (it->gatt_if == gatt_if) {
      GATTS_StopService(it++->s_hdl);
    } else {
      ++it;
    }
  }

  /* free all services db buffers if owned by this application */
  gatt_free_srvc_db_buffer_app_id(p_reg->app_uuid128);

  /* When an application deregisters, check remove the link associated with the
   * app */
  tGATT_TCB* p_tcb;
  int i;
  for (i = 0, p_tcb = gatt_cb.tcb; i < GATT_MAX_PHY_CHANNEL; i++, p_tcb++) {
    if (!p_tcb->in_use) {
      continue;
    }

    if (gatt_get_ch_state(p_tcb) != GATT_CH_CLOSE) {
      gatt_update_app_use_link_flag(gatt_if, p_tcb, false, true);
    }

    for (auto clcb_it = gatt_cb.clcb_queue.begin(); clcb_it != gatt_cb.clcb_queue.end();) {
      if ((clcb_it->p_reg->gatt_if == gatt_if) && (clcb_it->p_tcb->tcb_idx == p_tcb->tcb_idx)) {
        alarm_cancel(clcb_it->gatt_rsp_timer_ent);
        gatt_clcb_invalidate(p_tcb, &(*clcb_it));
        clcb_it = gatt_cb.clcb_queue.erase(clcb_it);
      } else {
        clcb_it++;
      }
    }
  }

  connection_manager::on_app_deregistered(gatt_if);

  gatt_cb.cl_rcb_map.erase(gatt_if);
}

/*******************************************************************************
 *
 * Function         stack::appStartIf
 *
 * Description      This function is called after registration to start
 *                  receiving callbacks for registered interface.  Function may
 *                  call back with connection status and queued notifications
 *
 * Parameter        gatt_if: application interface.
 *
 * Returns          None.
 *
 ******************************************************************************/
void appStartIf(tGATT_IF gatt_if) {
  tGATT_REG* p_reg;
  tGATT_TCB* p_tcb;
  RawAddress bda = {};
  uint8_t start_idx, found_idx;
  tCONN_ID conn_id;
  tBT_TRANSPORT transport;

  log::debug("Starting GATT interface gatt_if_:{}", gatt_if);

  p_reg = gatt_get_regcb(gatt_if);
  if (p_reg != NULL) {
    start_idx = 0;
    while (gatt_find_the_connected_bda(start_idx, bda, &found_idx, &transport)) {
      p_tcb = gatt_find_tcb_by_addr(bda, transport);
      log::info("GATT interface {} already has connected device {}", gatt_if, bda);
      if (p_reg->app_cb.p_conn_cb && p_tcb) {
        conn_id = gatt_create_conn_id(p_tcb->tcb_idx, gatt_if);
        log::info("Invoking callback with connection id {}", conn_id);
        (*p_reg->app_cb.p_conn_cb)(gatt_if, bda, conn_id, true, GATT_CONN_OK, transport);
      } else {
        log::info("Skipping callback as none is registered");
      }
      start_idx = ++found_idx;
    }
  }
}

}  // namespace bluetooth::stack
