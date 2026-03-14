/******************************************************************************
 *
 *  Copyright 2006-2012 Broadcom Corporation
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
 *  This file contains action functions for BTA JV APIs.
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth"

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>
#include <unordered_set>

#include "bta/include/bta_jv_co.h"
#include "bta/include/bta_rfcomm_metrics.h"
#include "bta/include/bta_rfcomm_scn.h"
#include "bta/jv/bta_jv_int.h"
#include "bta/sys/bta_sys.h"
#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/gap_api.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/port_api.h"
#include "stack/include/rfcdefs.h"
#include "stack/include/sdp_api.h"

using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

BtaJvCb bta_jv_cb;
std::unordered_set<uint16_t> used_l2cap_classic_dynamic_psm;

static BtaJvPcb* bta_jv_add_rfc_port(BtaJvRfcommCb* p_cb, BtaJvPcb* p_pcb_open);
static tBTA_JV_STATUS bta_jv_free_set_pm_profile_cb(uint32_t jv_handle);
static void bta_jv_pm_conn_busy(BtaJvPmCb* p_cb);
static void bta_jv_pm_conn_idle(BtaJvPmCb* p_cb);
static void bta_jv_pm_state_change(BtaJvPmCb* p_cb, const tBTA_JV_CONN_STATE state);
static void bta_jv_reset_sniff_timer(BtaJvPmCb* p_cb);

#ifndef BTA_JV_SDP_DB_SIZE
#define BTA_JV_SDP_DB_SIZE 4500
#endif

#ifndef BTA_JV_SDP_RAW_DATA_SIZE
#define BTA_JV_SDP_RAW_DATA_SIZE 1800
#endif

static uint8_t bta_jv_sdp_raw_data[BTA_JV_SDP_RAW_DATA_SIZE];
static tSDP_DISCOVERY_DB bta_jv_sdp_db_data[BTA_JV_SDP_DB_SIZE / sizeof(tSDP_DISCOVERY_DB)];

/* JV configuration structure */
static struct tBTA_JV_CFG {
  uint16_t sdp_raw_size;        // The size of p_sdp_raw_data
  uint16_t sdp_db_size;         // The size of p_sdp_db
  uint8_t* p_sdp_raw_data;      // The data buffer to keep raw data
  tSDP_DISCOVERY_DB* p_sdp_db;  // The data buffer to keep SDP database
} bta_jv_cfg = {
        BTA_JV_SDP_RAW_DATA_SIZE,  // The size of p_sdp_raw_data
        (BTA_JV_SDP_DB_SIZE / sizeof(tSDP_DISCOVERY_DB)) *
                sizeof(tSDP_DISCOVERY_DB),  // The size of p_sdp_db_data
        bta_jv_sdp_raw_data,                // The data buffer to keep raw data
        bta_jv_sdp_db_data                  // The data buffer to keep SDP database
};

static tBTA_JV_CFG* p_bta_jv_cfg = &bta_jv_cfg;

/*******************************************************************************
 *
 * Function     bta_jv_alloc_sec_id
 *
 * Description  allocate a security id
 *
 * Returns      allocated security id or 0 in case of failure
 *
 ******************************************************************************/
static uint8_t bta_jv_alloc_sec_id(void) {
  uint8_t ret = 0;
  int i;
  for (i = 0; i < BTA_JV_NUM_SERVICE_ID; i++) {
    if (0 == bta_jv_cb.sec_id[i]) {
      bta_jv_cb.sec_id[i] = BTA_JV_FIRST_SERVICE_ID + i;
      ret = bta_jv_cb.sec_id[i];
      break;
    }
  }
  return ret;
}

/*******************************************************************************
 *
 * Function     get_sec_id_used
 *
 * Returns      number of in use security ids
 *
 *******************************************************************************/
static int get_sec_id_used(void) {
  int i;
  int used = 0;
  for (i = 0; i < BTA_JV_NUM_SERVICE_ID; i++) {
    if (bta_jv_cb.sec_id[i]) {
      used++;
    }
  }
  if (used == BTA_JV_NUM_SERVICE_ID) {
    log::error("sec id exceeds the limit={}", BTA_JV_NUM_SERVICE_ID);
  }
  return used;
}
/*******************************************************************************
 *
 * Function     get_rfc_cb_used
 *
 * Returns      number of in use rfc control blocks
 *
 *******************************************************************************/
static int get_rfc_cb_used(void) {
  int i;
  int used = 0;
  for (i = 0; i < BTA_JV_MAX_RFC_CONN; i++) {
    if (bta_jv_cb.rfc_cb[i].handle) {
      used++;
    }
  }
  if (used == BTA_JV_MAX_RFC_CONN) {
    log::error("rfc ctrl block exceeds the limit={}", BTA_JV_MAX_RFC_CONN);
  }
  return used;
}

/*******************************************************************************
 *
 * Function     bta_jv_free_sec_id
 *
 * Description  free the given security id
 *
 ******************************************************************************/
static void bta_jv_free_sec_id(uint8_t* p_sec_id) {
  uint8_t sec_id = *p_sec_id;
  *p_sec_id = 0;
  if (sec_id >= BTA_JV_FIRST_SERVICE_ID && sec_id <= BTA_JV_LAST_SERVICE_ID) {
    get_security_client_interface().BTM_SecClrService(sec_id);
    bta_jv_cb.sec_id[sec_id - BTA_JV_FIRST_SERVICE_ID] = 0;
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_from_gap_l2cap_err
 *
 * Description  Convert the L2CAP error result propagated from GAP to BTA JV
 *              L2CAP close reason code.
 *
 * Params       l2cap_result: The L2CAP result propagated from GAP error.
 *
 * Returns      Appropriate l2cap error reason value
 *              or BTA_JV_L2CAP_REASON_UNKNOWN if reason isn't defined yet.
 *
 ******************************************************************************/
static tBTA_JV_L2CAP_REASON bta_jv_from_gap_l2cap_err(const tL2CAP_CONN& l2cap_result) {
  switch (l2cap_result) {
    case tL2CAP_CONN::L2CAP_CONN_ACL_CONNECTION_FAILED:
      return BTA_JV_L2CAP_REASON_ACL_FAILURE;
    case tL2CAP_CONN::L2CAP_CONN_CLIENT_SECURITY_CLEARANCE_FAILED:
      return BTA_JV_L2CAP_REASON_CL_SEC_FAILURE;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_AUTHENTICATION:
      return BTA_JV_L2CAP_REASON_INSUFFICIENT_AUTHENTICATION;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_AUTHORIZATION:
      return BTA_JV_L2CAP_REASON_INSUFFICIENT_AUTHORIZATION;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_ENCRYP_KEY_SIZE:
      return BTA_JV_L2CAP_REASON_INSUFFICIENT_ENCRYP_KEY_SIZE;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_ENCRYP:
      return BTA_JV_L2CAP_REASON_INSUFFICIENT_ENCRYP;
    case tL2CAP_CONN::L2CAP_CONN_INVALID_SOURCE_CID:
      return BTA_JV_L2CAP_REASON_INVALID_SOURCE_CID;
    case tL2CAP_CONN::L2CAP_CONN_SOURCE_CID_ALREADY_ALLOCATED:
      return BTA_JV_L2CAP_REASON_SOURCE_CID_ALREADY_ALLOCATED;
    case tL2CAP_CONN::L2CAP_CONN_UNACCEPTABLE_PARAMETERS:
      return BTA_JV_L2CAP_REASON_UNACCEPTABLE_PARAMETERS;
    case tL2CAP_CONN::L2CAP_CONN_INVALID_PARAMETERS:
      return BTA_JV_L2CAP_REASON_INVALID_PARAMETERS;
    case tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES:
      return BTA_JV_L2CAP_REASON_NO_RESOURCES;
    case tL2CAP_CONN::L2CAP_CONN_NO_PSM:
      return BTA_JV_L2CAP_REASON_NO_PSM;
    case tL2CAP_CONN::L2CAP_CONN_TIMEOUT:
      return BTA_JV_L2CAP_REASON_TIMEOUT;
    default:
      return BTA_JV_L2CAP_REASON_UNKNOWN;
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_alloc_rfc_cb
 *
 * Description  allocate a control block for the given port handle
 *
 * Returns      pointer to allocated control block
 *
 ******************************************************************************/
static BtaJvRfcommCb* bta_jv_alloc_rfc_cb(uint8_t port_handle, BtaJvPcb** pp_pcb) {
  BtaJvRfcommCb* p_cb = nullptr;
  BtaJvPcb* p_pcb;
  int i, j;
  for (i = 0; i < BTA_JV_MAX_RFC_CONN; i++) {
    if (0 == bta_jv_cb.rfc_cb[i].handle) {
      p_cb = &bta_jv_cb.rfc_cb[i];
      // mask handle to distinguish it with L2CAP handle
      p_cb->handle = (i + 1) | BTA_JV_RFCOMM_MASK;

      p_cb->max_sess = 1;
      p_cb->curr_sess = 1;
      for (j = 0; j < BTA_JV_MAX_RFC_SR_SESSION; j++) {
        p_cb->port_hdls[j] = 0;
      }
      p_cb->port_hdls[0] = port_handle;
      log::verbose("port_handle={}, jv_handle=0x{:x}", port_handle, p_cb->handle);

      p_pcb = &bta_jv_cb.port_cb[port_handle - 1];
      p_pcb->handle = p_cb->handle;
      p_pcb->port_handle = port_handle;
      p_pcb->p_pm_cb = nullptr;
      *pp_pcb = p_pcb;
      break;
    }
  }
  if (p_cb == nullptr) {
    log::error("port_handle={} ctrl block exceeds limit:{}", port_handle, BTA_JV_MAX_RFC_CONN);
  }
  return p_cb;
}

/*******************************************************************************
 *
 * Function     bta_jv_rfc_port_to_pcb
 *
 * Returns      the port control block associated with the given port handle
 *
 ******************************************************************************/
static BtaJvPcb* bta_jv_rfc_port_to_pcb(uint8_t port_handle) {
  BtaJvPcb* p_pcb = nullptr;

  if ((port_handle > 0) && (port_handle <= MAX_RFC_PORTS) &&
      bta_jv_cb.port_cb[port_handle - 1].handle) {
    p_pcb = &bta_jv_cb.port_cb[port_handle - 1];
  }

  return p_pcb;
}

/*******************************************************************************
 *
 * Function     bta_jv_rfc_port_to_cb
 *
 * Returns      the RFCOMM control block associated with the given port handle
 *
 ******************************************************************************/
static BtaJvRfcommCb* bta_jv_rfc_port_to_cb(uint8_t port_handle) {
  BtaJvRfcommCb* p_cb = nullptr;
  uint32_t jv_handle;

  if ((port_handle > 0) && (port_handle <= MAX_RFC_PORTS) &&
      bta_jv_cb.port_cb[port_handle - 1].handle) {
    jv_handle = bta_jv_cb.port_cb[port_handle - 1].handle;
    jv_handle &= BTA_JV_RFC_HDL_MASK;
    jv_handle &= ~BTA_JV_RFCOMM_MASK;
    if (jv_handle) {
      p_cb = &bta_jv_cb.rfc_cb[jv_handle - 1];
    }
  } else {
    log::warn("jv_handle not found for port_handle:{}", port_handle);
  }
  return p_cb;
}

/*******************************************************************************
 *
 * Function     bta_jv_free_rfc_cb
 *
 * Description  Free the given RFCOMM control block and RFCOMM port control block
 *
 * Returns      tBTA_JV_STATUS::SUCCESS if success
 *              tBTA_JV_STATUS::FAILURE otherwise
 *
 ******************************************************************************/
static tBTA_JV_STATUS bta_jv_free_rfc_cb(BtaJvRfcommCb* p_cb, BtaJvPcb* p_pcb) {
  tBTA_JV_STATUS status = tBTA_JV_STATUS::SUCCESS;
  bool remove_server = false;

  if (p_cb == nullptr || p_pcb == nullptr) {
    log::error("p_cb or p_pcb cannot be null");
    return tBTA_JV_STATUS::FAILURE;
  }
  log::verbose("max_sess={}, curr_sess={}, port_handle={}, slot_id={}, state={}, jv_handle=0x{:x}",
               p_cb->max_sess, p_cb->curr_sess, p_pcb->port_handle, p_pcb->rfcomm_slot_id,
               bta_jv_state_text(p_pcb->state), p_pcb->handle);

  if (p_cb->curr_sess <= 0) {
    return tBTA_JV_STATUS::SUCCESS;
  }

  switch (p_pcb->state) {
    case BTA_JV_ST_CL_CLOSING:
    case BTA_JV_ST_SR_CLOSING:
      log::warn(
              "return on closing, port state={}, scn={}, port_handle={}, jv_handle=0x{:x},"
              " slot_id={}",
              bta_jv_state_text(p_pcb->state), p_cb->scn, p_pcb->port_handle, p_pcb->handle,
              p_pcb->rfcomm_slot_id);
      status = tBTA_JV_STATUS::FAILURE;
      return status;
    case BTA_JV_ST_CL_OPEN:
    case BTA_JV_ST_CL_OPENING:
      log::verbose("state={}, scn={}, slot_id={}", bta_jv_state_text(p_pcb->state), p_cb->scn,
                   p_pcb->rfcomm_slot_id);
      p_pcb->state = BTA_JV_ST_CL_CLOSING;
      break;
    case BTA_JV_ST_SR_LISTEN:
      p_pcb->state = BTA_JV_ST_SR_CLOSING;
      remove_server = true;
      log::verbose("state:BTA_JV_ST_SR_LISTEN, scn={}, slot_id={}", p_cb->scn,
                   p_pcb->rfcomm_slot_id);
      break;
    case BTA_JV_ST_SR_OPEN:
      p_pcb->state = BTA_JV_ST_SR_CLOSING;
      log::verbose("state:BTA_JV_ST_SR_OPEN, scn={} slot_id={}", p_cb->scn, p_pcb->rfcomm_slot_id);
      break;
    default:
      log::warn(
              "failed, ignore port state={}, scn={}, jv_handle=0x{:x}, port_handle={}, slot_id={}",
              bta_jv_state_text(p_pcb->state), p_cb->scn, p_pcb->handle, p_pcb->port_handle,
              p_pcb->rfcomm_slot_id);
      status = tBTA_JV_STATUS::FAILURE;
      break;
  }
  if (tBTA_JV_STATUS::SUCCESS == status) {
    int port_status;

    if (!remove_server) {
      port_status = RFCOMM_RemoveConnection(p_pcb->port_handle);
    } else {
      port_status = RFCOMM_RemoveServer(p_pcb->port_handle);
    }
    if (port_status != PORT_SUCCESS) {
      status = tBTA_JV_STATUS::FAILURE;
      log::warn("Remove jv_handle=0x{:x}, state={}, port_status={}, port_handle={}", p_pcb->handle,
                p_pcb->state, port_status, p_pcb->port_handle);
    }
  }

  p_pcb->port_handle = 0;
  p_pcb->state = BTA_JV_ST_NONE;
  bta_jv_free_set_pm_profile_cb(p_pcb->handle);

  // Initialize congestion flags
  p_pcb->cong = false;
  log::verbose("setting p_pcb->rfcomm_slot_id=0");
  p_pcb->rfcomm_slot_id = 0;
  int si = BTA_JV_RFC_HDL_TO_SIDX(p_pcb->handle);
  if (0 <= si && si < BTA_JV_MAX_RFC_SR_SESSION) {
    p_cb->port_hdls[si] = 0;
  }
  p_pcb->handle = 0;
  p_cb->curr_sess--;
  if (p_cb->curr_sess == 0) {
    p_cb->scn = 0;
    p_cb->p_cback = nullptr;
    p_cb->handle = 0;
    p_cb->curr_sess = -1;
  }

  return status;
}

/*******************************************************************************
 *
 * Function     bta_jv_free_l2c_cb
 *
 * Description  free the given L2CAP control block
 *
 * Returns      tBTA_JV_STATUS::SUCCESS if success
 *              tBTA_JV_STATUS::FAILURE otherwise
 *
 ******************************************************************************/
static tBTA_JV_STATUS bta_jv_free_l2c_cb(BtaJvL2capCb* p_cb) {
  tBTA_JV_STATUS status = tBTA_JV_STATUS::SUCCESS;

  if (BTA_JV_ST_NONE != p_cb->state) {
    bta_jv_free_set_pm_profile_cb((uint32_t)p_cb->handle);
    if (GAP_ConnClose(p_cb->handle) != BT_PASS) {
      status = tBTA_JV_STATUS::FAILURE;
    }
  }
  p_cb->psm = 0;
  p_cb->state = BTA_JV_ST_NONE;
  p_cb->cong = false;
  bta_jv_free_sec_id(&p_cb->sec_id);
  p_cb->p_cback = nullptr;
  p_cb->handle = 0;
  p_cb->l2cap_socket_id = 0;
  return status;
}

/*******************************************************************************
 *
 * Function    bta_jv_clear_pm_cb
 *
 * Description clears jv pm control block and optionally calls bta_sys_conn_close()
 *             In general close_conn should be set to true to remove registering
 *             with dm pm!
 *
 * WARNING:    Make sure to clear pointer form port or l2c to this control block
 *             too!
 *
 ******************************************************************************/
static void bta_jv_clear_pm_cb(BtaJvPmCb* p_pm_cb, bool close_conn) {
  // needs to be called if registered with bta pm, otherwise we may run out of dm pm slots!
  if (close_conn) {
    bta_sys_conn_close(BTA_ID_JV, p_pm_cb->app_id, p_pm_cb->peer_bd_addr);
  }
  p_pm_cb->state = BTA_JV_PM_FREE_ST;
  p_pm_cb->app_id = BTA_JV_PM_ALL;
  p_pm_cb->handle = BTA_JV_PM_HANDLE_CLEAR;
  p_pm_cb->peer_bd_addr = RawAddress::kEmpty;
  alarm_free(p_pm_cb->idle_timer);
  p_pm_cb->idle_timer = nullptr;
}

/*******************************************************************************
 *
 * Function     bta_jv_free_set_pm_profile_cb
 *
 * Description  free pm profile control block
 *
 * Returns     tBTA_JV_STATUS::SUCCESS if cb has been freed correctly,
 *             tBTA_JV_STATUS::FAILURE in case of no profile has been registered
 *                                     or already freed
 *
 ******************************************************************************/
static tBTA_JV_STATUS bta_jv_free_set_pm_profile_cb(uint32_t jv_handle) {
  tBTA_JV_STATUS status = tBTA_JV_STATUS::FAILURE;
  BtaJvPmCb** p_cb;
  int i, j, bd_counter = 0, appid_counter = 0;

  for (i = 0; i < BTA_JV_PM_MAX_NUM; i++) {
    p_cb = nullptr;
    if ((bta_jv_cb.pm_cb[i].state != BTA_JV_PM_FREE_ST) &&
        (jv_handle == bta_jv_cb.pm_cb[i].handle)) {
      for (j = 0; j < BTA_JV_PM_MAX_NUM; j++) {
        if (bta_jv_cb.pm_cb[j].peer_bd_addr == bta_jv_cb.pm_cb[i].peer_bd_addr) {
          bd_counter++;
        }
        if (bta_jv_cb.pm_cb[j].app_id == bta_jv_cb.pm_cb[i].app_id) {
          appid_counter++;
        }
      }

      log::verbose("jv_handle=0x{:x}, idx={}, app_id={}, bd_counter={}, appid_counter={}",
                   jv_handle, i, bta_jv_cb.pm_cb[i].app_id, bd_counter, appid_counter);
      if (bd_counter > 1) {
        bta_jv_pm_conn_idle(&bta_jv_cb.pm_cb[i]);
      }

      if (bd_counter <= 1 || (appid_counter <= 1)) {
        bta_jv_clear_pm_cb(&bta_jv_cb.pm_cb[i], true);
      } else {
        bta_jv_clear_pm_cb(&bta_jv_cb.pm_cb[i], false);
      }

      if (BTA_JV_RFCOMM_MASK & jv_handle) {
        uint32_t hi = ((jv_handle & BTA_JV_RFC_HDL_MASK) & ~BTA_JV_RFCOMM_MASK) - 1;
        uint32_t si = BTA_JV_RFC_HDL_TO_SIDX(jv_handle);
        if (hi < BTA_JV_MAX_RFC_CONN && bta_jv_cb.rfc_cb[hi].p_cback &&
            si < BTA_JV_MAX_RFC_SR_SESSION && bta_jv_cb.rfc_cb[hi].port_hdls[si]) {
          BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(bta_jv_cb.rfc_cb[hi].port_hdls[si]);
          if (p_pcb) {
            if (p_pcb->p_pm_cb == nullptr) {
              log::warn("jv_handle=0x{:x}, port_handle={}, i={}, no link to pm_cb?", jv_handle,
                        p_pcb->port_handle, i);
            }
            p_cb = &p_pcb->p_pm_cb;
          }
        }
      } else {
        if (jv_handle < BTA_JV_MAX_L2C_CONN) {
          BtaJvL2capCb* p_l2c_cb = &bta_jv_cb.l2c_cb[jv_handle];
          if (p_l2c_cb->p_pm_cb == nullptr) {
            log::warn("jv_handle=0x{:x}, i={} no link to pm_cb?", jv_handle, i);
          }
          p_cb = &p_l2c_cb->p_pm_cb;
        }
      }
      if (p_cb) {
        *p_cb = nullptr;
        status = tBTA_JV_STATUS::SUCCESS;
      }
    }
  }
  return status;
}

/*******************************************************************************
 *
 * Function    bta_jv_alloc_set_pm_profile_cb
 *
 * Description set PM profile control block
 *
 * Returns     pointer to allocated cb or nullptr in case of failure
 *
 ******************************************************************************/
static BtaJvPmCb* bta_jv_alloc_set_pm_profile_cb(uint32_t jv_handle, tBTA_JV_PM_ID app_id) {
  bool bRfcHandle = (jv_handle & BTA_JV_RFCOMM_MASK) != 0;
  RawAddress peer_bd_addr = RawAddress::kEmpty;
  int i, j;
  BtaJvPmCb** pp_cb;

  for (i = 0; i < BTA_JV_PM_MAX_NUM; i++) {
    pp_cb = nullptr;
    if (bta_jv_cb.pm_cb[i].state == BTA_JV_PM_FREE_ST) {
      // rfc handle bd addr retrieval requires core stack handle
      if (bRfcHandle) {
        for (j = 0; j < BTA_JV_MAX_RFC_CONN; j++) {
          if (jv_handle == bta_jv_cb.port_cb[j].handle) {
            pp_cb = &bta_jv_cb.port_cb[j].p_pm_cb;
            if (PORT_SUCCESS !=
                PORT_CheckConnection(bta_jv_cb.port_cb[j].port_handle, &peer_bd_addr, nullptr)) {
              i = BTA_JV_PM_MAX_NUM;
            }
            break;
          }
        }
      } else {
        // use jv handle for l2cap bd address retrieval
        for (j = 0; j < BTA_JV_MAX_L2C_CONN; j++) {
          if (jv_handle == bta_jv_cb.l2c_cb[j].handle) {
            pp_cb = &bta_jv_cb.l2c_cb[j].p_pm_cb;
            const RawAddress* p_bd_addr = GAP_ConnGetRemoteAddr((uint16_t)jv_handle);
            if (p_bd_addr) {
              peer_bd_addr = *p_bd_addr;
            } else {
              i = BTA_JV_PM_MAX_NUM;
            }
            break;
          }
        }
      }
      log::verbose("jv_handle=0x{:x}, app_id={}, idx={}, BTA_JV_PM_MAX_NUM={}, pp_cb={}", jv_handle,
                   app_id, i, BTA_JV_PM_MAX_NUM, std::format_ptr(pp_cb));
      break;
    }
  }

  if ((i != BTA_JV_PM_MAX_NUM) && (pp_cb != nullptr)) {
    *pp_cb = &bta_jv_cb.pm_cb[i];
    bta_jv_cb.pm_cb[i].handle = jv_handle;
    bta_jv_cb.pm_cb[i].app_id = app_id;
    bta_jv_cb.pm_cb[i].peer_bd_addr = peer_bd_addr;
    bta_jv_cb.pm_cb[i].state = BTA_JV_PM_IDLE_ST;
    bta_jv_cb.pm_cb[i].idle_timer = alarm_new("bta.jv_idle_timer");
    return &bta_jv_cb.pm_cb[i];
  }
  log::warn("jv_handle=0x{:x}, app_id={}, return nullptr", jv_handle, app_id);
  return nullptr;
}

/*******************************************************************************
 *
 * Function     bta_jv_check_psm
 *
 * Description  for now use only the legal PSM per JSR82 spec
 *
 * Returns      true, if allowed
 *
 ******************************************************************************/
static bool bta_jv_check_psm(uint16_t psm) {
  bool ret = false;

  if (L2C_IS_VALID_PSM(psm)) {
    if (psm < 0x1001) {
      // see if this is defined by spec
      switch (psm) {
        case BT_PSM_SDP:
        case BT_PSM_RFCOMM:  // 3
          // do not allow java app to use these 2 PSMs
          break;

        case BT_PSM_TCS:
        case BT_PSM_CTP:
          if (!bta_sys_is_register(BTA_ID_CT) && !bta_sys_is_register(BTA_ID_CG)) {
            ret = true;
          }
          break;

        case BT_PSM_BNEP:  // F
          if (!bta_sys_is_register(BTA_ID_PAN)) {
            ret = true;
          }
          break;

        case BT_PSM_HIDC:
        case BT_PSM_HIDI:
          // FIX: allow HID Device and HID Host to coexist
          if (!bta_sys_is_register(BTA_ID_HD) || !bta_sys_is_register(BTA_ID_HH)) {
            ret = true;
          }
          break;

        case BT_PSM_AVCTP:  // 0x17
        case BT_PSM_AVDTP:  // 0x19
          if (!bta_sys_is_register(BTA_ID_AV)) {
            ret = true;
          }
          break;

        default:
          ret = true;
          break;
      }
    } else {
      ret = true;
    }
  }
  return ret;
}

/*******************************************************************************
 *
 * Function     bta_jv_enable
 *
 * Description  Initializes Java interface
 *
 ******************************************************************************/
void bta_jv_enable(tBTA_JV_DM_CBACK* p_cback) {
  bta_jv_cb.p_dm_cback = p_cback;
  if (bta_jv_cb.p_dm_cback) {
    tBTA_JV bta_jv = {
            .status = tBTA_JV_STATUS::SUCCESS,
    };
    bta_jv_cb.p_dm_cback(BTA_JV_ENABLE_EVT, &bta_jv, 0);
  }
  memset(bta_jv_cb.free_psm_list, 0, sizeof(bta_jv_cb.free_psm_list));
  memset(bta_jv_cb.scn_in_use, 0, sizeof(bta_jv_cb.scn_in_use));
  bta_jv_cb.scn_search_index = 1;
}

/** Disables the BT device manager free the resources used by java */
void bta_jv_disable() { log::info(""); }

/*******************************************************************************
 *
 * Function     bta_jv_get_free_psm
 *
 * Description  We keep a list of PSMs that have been freed from java for reuse
 *              This function will return a free PSM, and delete it from the free list.
 *
 * Returns      freed PSM, 0 otherwise
 *
 ******************************************************************************/
static uint16_t bta_jv_get_free_psm() {
  const int cnt = sizeof(bta_jv_cb.free_psm_list) / sizeof(bta_jv_cb.free_psm_list[0]);
  for (int i = 0; i < cnt; i++) {
    uint16_t psm = bta_jv_cb.free_psm_list[i];
    if (psm != 0) {
      log::verbose("Reusing PSM=0x{:x}", psm);
      bta_jv_cb.free_psm_list[i] = 0;
      return psm;
    }
  }
  return 0;
}

/*******************************************************************************
 *
 * Function     bta_jv_set_free_psm
 *
 * Description  This function frees the given psm, and saves it to the list of
 *              free PSMs
 *
 *******************************************************************************/
static void bta_jv_set_free_psm(uint16_t psm) {
  int free_index = -1;
  const int cnt = sizeof(bta_jv_cb.free_psm_list) / sizeof(bta_jv_cb.free_psm_list[0]);
  for (int i = 0; i < cnt; i++) {
    if (bta_jv_cb.free_psm_list[i] == 0) {
      free_index = i;
    } else if (psm == bta_jv_cb.free_psm_list[i]) {
      return;  // PSM already freed?
    }
  }
  if (free_index != -1) {
    bta_jv_cb.free_psm_list[free_index] = psm;
    log::verbose("Recycling PSM=0x{:x}", psm);
  } else {
    log::error("unable to free psm=0x{:x} no more free slots", psm);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_allocate_l2cap_classic_psm
 *
 * Description  This function allocates a new PSM for L2CAP Classic
 *
 * Returns      PSM allocated
 *
 *******************************************************************************/
static uint16_t bta_jv_allocate_l2cap_classic_psm() {
  bool done = false;
  uint16_t psm = bta_jv_cb.dyn_psm;

  while (!done) {
    psm += 2;
    if (psm > 0xfeff) {
      psm = 0x1001;
    } else if (psm & 0x0100) {
      // the upper byte must be even
      psm += 0x0100;
    }

    // if psm is in range of reserved BRCM Aware features
    if ((BRCM_RESERVED_PSM_START <= psm) && (psm <= BRCM_RESERVED_PSM_END)) {
      continue;
    }

    // make sure the newly allocated psm is not used right now
    if (used_l2cap_classic_dynamic_psm.count(psm) == 0) {
      done = true;
    }
  }
  bta_jv_cb.dyn_psm = psm;

  return psm;
}

/*******************************************************************************
 *
 * Function     bta_jv_get_channel_id
 *
 * Description  Set a SCN (Server Channel Number) and trigger the appropriate callback
 *              SCN is either an RFCOMM channel or an L2CAP PSM
 *              Callback is either BTA_JV_GET_SCN_EVT or BTA_JV_GET_PSM_EVT
 *
 *******************************************************************************/
void bta_jv_get_channel_id(tBTA_JV_CONN_TYPE type /* One of BTA_JV_CONN_TYPE_ */,
                           int32_t channel /* optionally request a specific channel */,
                           uint32_t l2cap_socket_id, uint32_t rfcomm_slot_id,
                           uint32_t lecoc_fixed_psm_slots) {
  uint16_t psm = 0;

  switch (type) {
    case tBTA_JV_CONN_TYPE::RFCOMM: {
      uint8_t scn = 0;
      if (channel > 0) {
        if (BTA_TryAllocateSCN(channel)) {
          scn = static_cast<uint8_t>(channel);
        } else {
          log::error("rfc channel {} already in use or invalid", channel);
        }
      } else {
        scn = BTA_AllocateSCN();
        if (scn == 0) {
          log::error("out of rfc channels");
        }
      }
      if (bta_jv_cb.p_dm_cback) {
        tBTA_JV bta_jv;
        bta_jv.scn = scn;
        bta_jv_cb.p_dm_cback(BTA_JV_GET_SCN_EVT, &bta_jv, rfcomm_slot_id);
      }
      return;
    }
    case tBTA_JV_CONN_TYPE::L2CAP:
      psm = bta_jv_get_free_psm();
      if (psm == 0) {
        psm = bta_jv_allocate_l2cap_classic_psm();
        log::verbose("returned PSM=0x{:x}", psm);
      }
      break;
    case tBTA_JV_CONN_TYPE::L2CAP_LE:
      psm = stack::l2cap::get_interface().L2CA_AllocateLePSM(lecoc_fixed_psm_slots);
      if (psm == 0) {
        log::error("Error: No free LE PSM available");
      }
      break;
    default:
      break;
  }

  if (bta_jv_cb.p_dm_cback) {
    tBTA_JV bta_jv;
    bta_jv.psm = psm;
    bta_jv_cb.p_dm_cback(BTA_JV_GET_PSM_EVT, &bta_jv, l2cap_socket_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_free_scn
 *
 * Description  Free an SCN (Server Channel Number)
 *              SCN is either an RFCOMM channel or an L2CAP PSM
 *
 *******************************************************************************/
void bta_jv_free_scn(tBTA_JV_CONN_TYPE type /* One of BTA_JV_CONN_TYPE_ */, uint16_t scn) {
  switch (type) {
    case tBTA_JV_CONN_TYPE::RFCOMM:
      BTA_FreeSCN(scn);
      break;
    case tBTA_JV_CONN_TYPE::L2CAP:
      bta_jv_set_free_psm(scn);
      break;
    case tBTA_JV_CONN_TYPE::L2CAP_LE:
      log::verbose("type=BTA_JV_CONN_TYPE::L2CAP_LE. psm=0x{:x}", scn);
      stack::l2cap::get_interface().L2CA_FreeLePSM(scn);
      break;
    default:
      break;
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_start_discovery_cback
 *
 * Description  Callback for Start Discovery
 *
 * Returns      void
 *
 ******************************************************************************/
static void bta_jv_start_discovery_cback(uint32_t rfcomm_slot_id, const RawAddress& bd_addr,
                                         tSDP_RESULT result) {
  if (!bta_jv_cb.sdp_cb.sdp_active) {
    log::warn("Received unexpected service discovery callback bd_addr:{} result:{}", bd_addr,
              sdp_result_text(result), bta_jv_cb.sdp_cb.sdp_active);
  }
  if (bta_jv_cb.sdp_cb.bd_addr != bd_addr) {
    log::warn(
            "Received incorrect service discovery callback expected_bd_addr:{} "
            "actual_bd_addr:{} result:{}",
            bta_jv_cb.sdp_cb.bd_addr, bd_addr, sdp_result_text(result),
            bta_jv_cb.sdp_cb.sdp_active);
  }

  if (bta_jv_cb.p_dm_cback) {
    tBTA_JV bta_jv = {
            .disc_comp =
                    {
                            .status = tBTA_JV_STATUS::FAILURE,
                            .scn = 0,
                    },
    };
    if (result == tSDP_STATUS::SDP_SUCCESS || result == tSDP_STATUS::SDP_DB_FULL) {
      log::info("Received service discovery callback success bd_addr:{} result:{}", bd_addr,
                sdp_result_text(result));
      tSDP_PROTOCOL_ELEM pe;
      tSDP_DISC_REC* p_sdp_rec = nullptr;
      p_sdp_rec = get_legacy_stack_sdp_api()->SDP_FindServiceUUIDInDb(
              p_bta_jv_cfg->p_sdp_db, bta_jv_cb.sdp_cb.uuid, p_sdp_rec);
      log::verbose("bta_jv_cb.uuid={} p_sdp_rec={}", bta_jv_cb.sdp_cb.uuid,
                   std::format_ptr(p_sdp_rec));
      if (p_sdp_rec && get_legacy_stack_sdp_api()->SDP_FindProtocolListElemInRec(
                               p_sdp_rec, UUID_PROTOCOL_RFCOMM, &pe)) {
        bta_jv = {
                .disc_comp =
                        {
                                .status = tBTA_JV_STATUS::SUCCESS,
                                .scn = (uint8_t)pe.params[0],
                        },
        };
      }
    } else {
      log::warn("Received service discovery callback failed bd_addr:{} result:{}", bd_addr,
                sdp_result_text(result));
    }
    log::info("Issuing service discovery complete callback bd_addr:{} result:{} status:{} scn:{}",
              bd_addr, sdp_result_text(result), bta_jv_status_text(bta_jv.disc_comp.status),
              bta_jv.disc_comp.scn);
    bta_jv_cb.p_dm_cback(BTA_JV_DISCOVERY_COMP_EVT, &bta_jv, rfcomm_slot_id);
  } else {
    log::warn("Received service discovery callback when disabled bd_addr:{} result:{}", bd_addr,
              sdp_result_text(result));
  }
  bta_jv_cb.sdp_cb = {};
}

// Discovers services on a remote device
void bta_jv_start_discovery(const RawAddress& bd_addr, uint16_t num_uuid,
                            bluetooth::Uuid* uuid_list, uint32_t rfcomm_slot_id) {
  log::assert_that(uuid_list != nullptr, "assert failed: uuid_list != nullptr");
  if (bta_jv_cb.sdp_cb.sdp_active) {
    log::warn(
            "Unable to start discovery as already in progress active_bd_addr:{} "
            "request_bd_addr:{} num_uuid:{} slot_id:{}",
            bta_jv_cb.sdp_cb.bd_addr, bd_addr, num_uuid, rfcomm_slot_id);
    if (bta_jv_cb.p_dm_cback) {
      tBTA_JV bta_jv = {
              .status = tBTA_JV_STATUS::BUSY,
      };
      bta_jv_cb.p_dm_cback(BTA_JV_DISCOVERY_COMP_EVT, &bta_jv, rfcomm_slot_id);
    } else {
      log::warn(
              "bta::jv module DISABLED so unable to inform caller service discovery is "
              "unavailable");
    }
    return;
  }

  // init the database/set up the filter
  if (!get_legacy_stack_sdp_api()->SDP_InitDiscoveryDb(
              p_bta_jv_cfg->p_sdp_db, p_bta_jv_cfg->sdp_db_size, num_uuid, uuid_list, 0, nullptr)) {
    log::warn("Unable to initialize service discovery db bd_addr:{} num_uuid:{} slot_id:{}",
              bd_addr, num_uuid, rfcomm_slot_id);
  }

  // tell SDP to keep the raw data
  p_bta_jv_cfg->p_sdp_db->raw_data = p_bta_jv_cfg->p_sdp_raw_data;
  p_bta_jv_cfg->p_sdp_db->raw_size = p_bta_jv_cfg->sdp_raw_size;

  // Optimistically set this as active
  bta_jv_cb.sdp_cb = {
          .sdp_active = true,
          .bd_addr = bd_addr,
          .uuid = uuid_list[0],
  };

  if (!get_legacy_stack_sdp_api()->SDP_ServiceSearchAttributeRequest2(
              bd_addr, p_bta_jv_cfg->p_sdp_db,
              base::BindRepeating(&bta_jv_start_discovery_cback, rfcomm_slot_id))) {
    bta_jv_cb.sdp_cb = {};
    log::warn("Unable to original service discovery bd_addr:{} num_uuid:{} slot_id:{}", bd_addr,
              num_uuid, rfcomm_slot_id);
    // failed to start SDP. report the failure right away
    if (bta_jv_cb.p_dm_cback) {
      tBTA_JV bta_jv = {
              .status = tBTA_JV_STATUS::FAILURE,
      };
      bta_jv_cb.p_dm_cback(BTA_JV_DISCOVERY_COMP_EVT, &bta_jv, rfcomm_slot_id);
    } else {
      log::warn("No callback set for discovery complete event");
    }
  } else {
    log::info("Started service discovery bd_addr:{} num_uuid:{} slot_id:{}", bd_addr, num_uuid,
              rfcomm_slot_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_cancel_discovery
 *
 * Description  Cancels the discovery associated with the given rfcomm slot id
 *
 *******************************************************************************/
void bta_jv_cancel_discovery(uint32_t rfcomm_slot_id) {
  if (!bta_jv_cb.sdp_cb.sdp_active) {
    log::error("Canceling discovery but discovery is not active");
    return;
  }
  if (!get_legacy_stack_sdp_api()->SDP_CancelServiceSearch(p_bta_jv_cfg->p_sdp_db)) {
    log::error("Failed to cancel discovery, clean up the control block anyway");
    bta_jv_cb.sdp_cb = {};
    // Send complete event right away as we might not receive callback from stack
    if (bta_jv_cb.p_dm_cback) {
      tBTA_JV bta_jv = {
              .status = tBTA_JV_STATUS::FAILURE,
      };
      bta_jv_cb.p_dm_cback(BTA_JV_DISCOVERY_COMP_EVT, &bta_jv, rfcomm_slot_id);
    } else {
      log::warn("No callback set for discovery complete event");
    }
  } else {
    log::info("Canceled discovery");
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_create_record
 *
 * Description  Creates an SDP record
 *
 *******************************************************************************/
void bta_jv_create_record(uint32_t rfcomm_slot_id) {
  tBTA_JV_CREATE_RECORD evt_data;
  evt_data.status = tBTA_JV_STATUS::SUCCESS;
  if (bta_jv_cb.p_dm_cback) {
    // callback immediately to create the sdp record in stack thread context
    tBTA_JV bta_jv;
    bta_jv.create_rec = evt_data;
    bta_jv_cb.p_dm_cback(BTA_JV_CREATE_RECORD_EVT, &bta_jv, rfcomm_slot_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_delete_record
 *
 * Description  Deletes an SDP record
 *
 *******************************************************************************/
void bta_jv_delete_record(uint32_t handle) {
  if (handle) {
    // this is a record created by btif layer
    if (!get_legacy_stack_sdp_api()->SDP_DeleteRecord(handle)) {
      log::warn("Unable to delete SDP record handle:{}", handle);
    }
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_client_cback
 *
 * Description  handles the l2cap client events
 *
 * Returns      void
 *
 ******************************************************************************/
static void bta_jv_l2cap_client_cback(uint16_t gap_handle, uint16_t event, tGAP_CB_DATA* data) {
  BtaJvL2capCb* p_cb = &bta_jv_cb.l2c_cb[gap_handle];
  tBTA_JV evt_data;

  if (gap_handle >= BTA_JV_MAX_L2C_CONN && p_cb->p_cback == nullptr) {
    return;
  }

  log::verbose("gap_handle={}, evt=0x{:x}", gap_handle, event);
  evt_data.l2c_open.status = tBTA_JV_STATUS::SUCCESS;
  evt_data.l2c_open.handle = gap_handle;

  switch (event) {
    case GAP_EVT_CONN_OPENED:
      if (!GAP_IsTransportLe(gap_handle)) {
        evt_data.l2c_open.rem_bda = *GAP_ConnGetRemoteAddr(gap_handle);
        evt_data.l2c_open.tx_mtu = GAP_ConnGetRemMtuSize(gap_handle);
      } else {
        uint16_t remote_mtu, local_mps, remote_mps, local_credit, remote_credit;
        uint16_t local_cid, remote_cid, acl_handle;
        evt_data.l2c_open.rem_bda = *GAP_ConnGetRemoteAddr(gap_handle);
        if (GAP_GetLeChannelInfo(gap_handle, &remote_mtu, &local_mps, &remote_mps, &local_credit,
                                 &remote_credit, &local_cid, &remote_cid,
                                 &acl_handle) != PORT_SUCCESS) {
          log::warn("Unable to get GAP channel info gap_handle:{}", gap_handle);
        }
        evt_data.l2c_open.tx_mtu = remote_mtu;
        evt_data.l2c_open.local_coc_mps = local_mps;
        evt_data.l2c_open.remote_coc_mps = remote_mps;
        evt_data.l2c_open.local_coc_credit = local_credit;
        evt_data.l2c_open.remote_coc_credit = remote_credit;
        evt_data.l2c_open.local_cid = local_cid;
        evt_data.l2c_open.remote_cid = remote_cid;
        evt_data.l2c_open.acl_handle = acl_handle;
      }
      p_cb->state = BTA_JV_ST_CL_OPEN;
      p_cb->p_cback(BTA_JV_L2CAP_OPEN_EVT, &evt_data, p_cb->l2cap_socket_id);
      break;
    case GAP_EVT_CONN_CLOSED:
      p_cb->state = BTA_JV_ST_NONE;
      bta_jv_free_sec_id(&p_cb->sec_id);
      evt_data.l2c_close.async = true;
      evt_data.l2c_close.reason = data != nullptr ? bta_jv_from_gap_l2cap_err(data->l2cap_result)
                                                  : BTA_JV_L2CAP_REASON_EMPTY;
      p_cb->p_cback(BTA_JV_L2CAP_CLOSE_EVT, &evt_data, p_cb->l2cap_socket_id);
      p_cb->p_cback = nullptr;
      break;

    case GAP_EVT_CONN_DATA_AVAIL:
      evt_data.data_ind.handle = gap_handle;
      // Reset idle timer to avoid requesting sniff mode while receiving data
      if (!GAP_IsTransportLe(gap_handle)) {
        bta_jv_pm_conn_busy(p_cb->p_pm_cb);
      }
      p_cb->p_cback(BTA_JV_L2CAP_DATA_IND_EVT, &evt_data, p_cb->l2cap_socket_id);
      if (!GAP_IsTransportLe(gap_handle)) {
        bta_jv_pm_conn_idle(p_cb->p_pm_cb);
      }
      break;

    case GAP_EVT_TX_EMPTY:
      bta_jv_pm_conn_idle(p_cb->p_pm_cb);
      break;

    case GAP_EVT_CONN_CONGESTED:
    case GAP_EVT_CONN_UNCONGESTED:
      p_cb->cong = (event == GAP_EVT_CONN_CONGESTED) ? true : false;
      if (p_cb->cong && p_cb->p_pm_cb != nullptr) {
        bta_jv_pm_conn_busy(p_cb->p_pm_cb);
      }
      evt_data.l2c_cong.cong = p_cb->cong;
      p_cb->p_cback(BTA_JV_L2CAP_CONG_EVT, &evt_data, p_cb->l2cap_socket_id);
      break;

    default:
      break;
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_connect
 *
 * Description  Connects to a remote L2CAP device
 *
 *******************************************************************************/
void bta_jv_l2cap_connect(tBTA_JV_CONN_TYPE type, tBTA_SEC sec_mask, uint16_t remote_psm,
                          uint16_t rx_mtu, const RawAddress& peer_bd_addr,
                          std::unique_ptr<tL2CAP_CFG_INFO> cfg_param,
                          std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info, tBTA_JV_L2CAP_CBACK* p_cback,
                          uint32_t l2cap_socket_id) {
  uint16_t handle = GAP_INVALID_HANDLE;

  tL2CAP_CFG_INFO cfg;
  memset(&cfg, 0, sizeof(tL2CAP_CFG_INFO));
  if (cfg_param) {
    cfg = *cfg_param;
  }

  // We need to use this value for MTU to be able to handle cases where cfg is not set in req
  cfg.mtu_present = true;
  cfg.mtu = rx_mtu;

  uint8_t sec_id = bta_jv_alloc_sec_id();
  tBTA_JV_L2CAP_CL_INIT evt_data;
  evt_data.sec_id = sec_id;
  evt_data.status = tBTA_JV_STATUS::FAILURE;

  if (sec_id) {
    // PSM checking is not required for LE COC
    if ((type != tBTA_JV_CONN_TYPE::L2CAP) || (bta_jv_check_psm(remote_psm)))  // allowed
    {
      // Given a client socket type
      // return the associated transport
      const tBT_TRANSPORT transport = [](tBTA_JV_CONN_TYPE type) -> tBT_TRANSPORT {
        switch (type) {
          case tBTA_JV_CONN_TYPE::L2CAP:
            return BT_TRANSPORT_BR_EDR;
          case tBTA_JV_CONN_TYPE::L2CAP_LE:
            return BT_TRANSPORT_LE;
          case tBTA_JV_CONN_TYPE::RFCOMM:
          default:
            break;
        }
        log::warn("Unexpected socket type:{}", bta_jv_conn_type_text(type));
        return BT_TRANSPORT_AUTO;
      }(type);

      uint16_t max_mps = 0xffff;  // Let GAP_ConnOpen set the max_mps.
      handle = GAP_ConnOpen("", sec_id, 0, &peer_bd_addr, remote_psm, max_mps, &cfg,
                            ertm_info.get(), sec_mask, bta_jv_l2cap_client_cback, transport);
      if (handle != GAP_INVALID_HANDLE) {
        evt_data.status = tBTA_JV_STATUS::SUCCESS;
      }
    }
  }

  if (evt_data.status == tBTA_JV_STATUS::SUCCESS) {
    BtaJvL2capCb* p_cb;
    p_cb = &bta_jv_cb.l2c_cb[handle];
    p_cb->handle = handle;
    p_cb->p_cback = p_cback;
    p_cb->l2cap_socket_id = l2cap_socket_id;
    p_cb->psm = 0;  // not a server
    p_cb->sec_id = sec_id;
    p_cb->state = BTA_JV_ST_CL_OPENING;
  } else {
    bta_jv_free_sec_id(&sec_id);
  }

  evt_data.handle = handle;
  if (p_cback) {
    tBTA_JV bta_jv;
    bta_jv.l2c_cl_init = evt_data;
    p_cback(BTA_JV_L2CAP_CL_INIT_EVT, &bta_jv, l2cap_socket_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_close
 *
 * Description  Closes an L2CAP client connection
 *
 *******************************************************************************/
void bta_jv_l2cap_close(uint32_t handle, BtaJvL2capCb* p_cb) {
  tBTA_JV_L2CAP_CLOSE evt_data;
  tBTA_JV_L2CAP_CBACK* p_cback = p_cb->p_cback;
  uint32_t l2cap_socket_id = p_cb->l2cap_socket_id;

  evt_data.handle = handle;
  evt_data.status = bta_jv_free_l2c_cb(p_cb);
  evt_data.async = false;

  if (p_cback) {
    tBTA_JV bta_jv;
    bta_jv.l2c_close = evt_data;
    p_cback(BTA_JV_L2CAP_CLOSE_EVT, &bta_jv, l2cap_socket_id);
  }
}

/*******************************************************************************
 *
 * Function         bta_jv_l2cap_server_cback
 *
 * Description      handles the l2cap server callback
 *
 ******************************************************************************/
static void bta_jv_l2cap_server_cback(uint16_t gap_handle, uint16_t event,
                                      tGAP_CB_DATA* /* data */) {
  BtaJvL2capCb* p_cb = &bta_jv_cb.l2c_cb[gap_handle];
  tBTA_JV evt_data;
  tBTA_JV_L2CAP_CBACK* p_cback;
  uint32_t socket_id;

  if (gap_handle >= BTA_JV_MAX_L2C_CONN && p_cb->p_cback == nullptr) {
    return;
  }

  log::verbose("gap_handle={}, evt=0x{:x}", gap_handle, event);
  evt_data.l2c_open.status = tBTA_JV_STATUS::SUCCESS;
  evt_data.l2c_open.handle = gap_handle;

  switch (event) {
    case GAP_EVT_CONN_OPENED:
      if (!GAP_IsTransportLe(gap_handle)) {
        evt_data.l2c_open.rem_bda = *GAP_ConnGetRemoteAddr(gap_handle);
        evt_data.l2c_open.tx_mtu = GAP_ConnGetRemMtuSize(gap_handle);
      } else {
        uint16_t remote_mtu, local_mps, remote_mps, local_credit, remote_credit;
        uint16_t local_cid, remote_cid, acl_handle;
        evt_data.l2c_open.rem_bda = *GAP_ConnGetRemoteAddr(gap_handle);
        if (GAP_GetLeChannelInfo(gap_handle, &remote_mtu, &local_mps, &remote_mps, &local_credit,
                                 &remote_credit, &local_cid, &remote_cid,
                                 &acl_handle) != PORT_SUCCESS) {
          log::warn("Unable to get GAP channel info handle:{}", gap_handle);
        }
        evt_data.l2c_open.tx_mtu = remote_mtu;
        evt_data.l2c_open.local_coc_mps = local_mps;
        evt_data.l2c_open.remote_coc_mps = remote_mps;
        evt_data.l2c_open.local_coc_credit = local_credit;
        evt_data.l2c_open.remote_coc_credit = remote_credit;
        evt_data.l2c_open.local_cid = local_cid;
        evt_data.l2c_open.remote_cid = remote_cid;
        evt_data.l2c_open.acl_handle = acl_handle;
      }
      p_cb->state = BTA_JV_ST_SR_OPEN;
      p_cb->p_cback(BTA_JV_L2CAP_OPEN_EVT, &evt_data, p_cb->l2cap_socket_id);
      break;

    case GAP_EVT_CONN_CLOSED:
      evt_data.l2c_close.async = true;
      evt_data.l2c_close.handle = p_cb->handle;
      p_cback = p_cb->p_cback;
      socket_id = p_cb->l2cap_socket_id;
      evt_data.l2c_close.status = bta_jv_free_l2c_cb(p_cb);
      p_cback(BTA_JV_L2CAP_CLOSE_EVT, &evt_data, socket_id);
      break;

    case GAP_EVT_CONN_DATA_AVAIL:
      evt_data.data_ind.handle = gap_handle;
      // Reset idle timer to avoid requesting sniff mode while receiving data
      if (!GAP_IsTransportLe(gap_handle)) {
        bta_jv_pm_conn_busy(p_cb->p_pm_cb);
      }
      p_cb->p_cback(BTA_JV_L2CAP_DATA_IND_EVT, &evt_data, p_cb->l2cap_socket_id);
      if (!GAP_IsTransportLe(gap_handle)) {
        bta_jv_pm_conn_idle(p_cb->p_pm_cb);
      }
      break;

    case GAP_EVT_TX_EMPTY:
      bta_jv_pm_conn_idle(p_cb->p_pm_cb);
      break;

    case GAP_EVT_CONN_CONGESTED:
    case GAP_EVT_CONN_UNCONGESTED:
      p_cb->cong = (event == GAP_EVT_CONN_CONGESTED) ? true : false;
      if (p_cb->cong && p_cb->p_pm_cb != nullptr) {
        bta_jv_pm_conn_busy(p_cb->p_pm_cb);
      }
      evt_data.l2c_cong.cong = p_cb->cong;
      p_cb->p_cback(BTA_JV_L2CAP_CONG_EVT, &evt_data, p_cb->l2cap_socket_id);
      break;

    default:
      break;
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_start_server
 *
 * Description  Starts an L2cap Server
 *
 *******************************************************************************/
void bta_jv_l2cap_start_server(tBTA_JV_CONN_TYPE type, tBTA_SEC sec_mask, uint16_t local_psm,
                               uint16_t rx_mtu, std::unique_ptr<tL2CAP_CFG_INFO> cfg_param,
                               std::unique_ptr<tL2CAP_ERTM_INFO> ertm_info,
                               tBTA_JV_L2CAP_CBACK* p_cback, uint32_t l2cap_socket_id) {
  uint16_t handle;
  tBTA_JV_L2CAP_START evt_data;

  tL2CAP_CFG_INFO cfg;
  memset(&cfg, 0, sizeof(tL2CAP_CFG_INFO));
  if (cfg_param) {
    cfg = *cfg_param;
  }

  // FIX: MTU=0 means not present
  if (rx_mtu > 0) {
    cfg.mtu_present = true;
    cfg.mtu = rx_mtu;
  } else {
    cfg.mtu_present = false;
    cfg.mtu = 0;
  }

  uint8_t sec_id = bta_jv_alloc_sec_id();
  uint16_t max_mps = 0xffff;  // Let GAP_ConnOpen set the max_mps.
  // PSM checking is not required for LE COC

  // Given a server socket type
  // return the associated transport
  const tBT_TRANSPORT transport = [](tBTA_JV_CONN_TYPE type) -> tBT_TRANSPORT {
    switch (type) {
      case tBTA_JV_CONN_TYPE::L2CAP:
        return BT_TRANSPORT_BR_EDR;
      case tBTA_JV_CONN_TYPE::L2CAP_LE:
        return BT_TRANSPORT_LE;
      case tBTA_JV_CONN_TYPE::RFCOMM:
      default:
        break;
    }
    log::warn("Unexpected socket type:{}", bta_jv_conn_type_text(type));
    return BT_TRANSPORT_AUTO;
  }(type);

  if (0 == sec_id || ((type == tBTA_JV_CONN_TYPE::L2CAP) && (!bta_jv_check_psm(local_psm))) ||
      (handle = GAP_ConnOpen("JV L2CAP", sec_id, 1, nullptr, local_psm, max_mps, &cfg,
                             ertm_info.get(), sec_mask, bta_jv_l2cap_server_cback, transport)) ==
              GAP_INVALID_HANDLE) {
    bta_jv_free_sec_id(&sec_id);
    evt_data.status = tBTA_JV_STATUS::FAILURE;
  } else {
    BtaJvL2capCb* p_cb = &bta_jv_cb.l2c_cb[handle];
    evt_data.status = tBTA_JV_STATUS::SUCCESS;
    evt_data.handle = handle;
    evt_data.sec_id = sec_id;
    p_cb->p_cback = p_cback;
    p_cb->l2cap_socket_id = l2cap_socket_id;
    p_cb->handle = handle;
    p_cb->sec_id = sec_id;
    p_cb->state = BTA_JV_ST_SR_LISTEN;
    p_cb->psm = local_psm;
  }

  if (p_cback) {
    tBTA_JV bta_jv;
    bta_jv.l2c_start = evt_data;
    p_cback(BTA_JV_L2CAP_START_EVT, &bta_jv, l2cap_socket_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_stop_server
 *
 * Description  Closes an L2CAP client connection
 *
 *******************************************************************************/
void bta_jv_l2cap_stop_server(uint16_t /* local_psm */, uint32_t l2cap_socket_id) {
  for (int i = 0; i < BTA_JV_MAX_L2C_CONN; i++) {
    if (bta_jv_cb.l2c_cb[i].l2cap_socket_id == l2cap_socket_id) {
      BtaJvL2capCb* p_cb = &bta_jv_cb.l2c_cb[i];
      tBTA_JV_L2CAP_CBACK* p_cback = p_cb->p_cback;
      tBTA_JV_L2CAP_CLOSE evt_data;
      evt_data.handle = p_cb->handle;
      evt_data.status = bta_jv_free_l2c_cb(p_cb);
      evt_data.async = false;
      if (p_cback) {
        tBTA_JV bta_jv;
        bta_jv.l2c_close = evt_data;
        p_cback(BTA_JV_L2CAP_CLOSE_EVT, &bta_jv, l2cap_socket_id);
      }
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_l2cap_write
 *
 * Description  Writes to an L2cap connection
 *
 *******************************************************************************/
void bta_jv_l2cap_write(uint32_t handle, uint32_t req_id, BT_HDR* msg, uint32_t user_id,
                        BtaJvL2capCb* p_cb) {
  /* As we check this callback exists before the tBTA_JV_API_L2CAP_WRITE can be
   * send through the API this check should not be needed. But the API is not
   * designed to be used (safely at least) in a multi-threaded scheduler, hence
   * if the peer device disconnects the l2cap link after the API is called, but
   * before this message is handled, the ->p_cback will be cleared at this
   * point. At first glance this seems highly unlikely, but for all
   * obex-profiles with two channels connected - e.g. MAP, this happens around 1
   * of 4 disconnects, as a disconnect on the server channel causes a disconnect
   * to be send on the client (notification) channel, but at the peer typically
   * disconnects both the OBEX disconnect request crosses the incoming l2cap
   * disconnect. If p_cback is cleared, we simply discard the data. RISK: The
   * caller must handle any cleanup based on another signal than
   * BTA_JV_L2CAP_WRITE_EVT, which is typically not possible, as the pointer to
   * the allocated buffer is stored in this message, and can therefore not be
   * freed, hence we have a mem-leak-by-design.*/
  if (p_cb->p_cback == nullptr) {
    /* As this pointer is checked in the API function, this occurs only when the
     * channel is disconnected after the API function is called, but before the
     * message is handled. */
    log::error("p_cb->p_cback == nullptr");
    osi_free(msg);
    return;
  }

  tBTA_JV_L2CAP_WRITE evt_data;
  evt_data.status = tBTA_JV_STATUS::FAILURE;
  evt_data.handle = handle;
  evt_data.req_id = req_id;
  evt_data.cong = p_cb->cong;
  evt_data.len = msg->len;

  bta_jv_pm_conn_busy(p_cb->p_pm_cb);

  // TODO: this was set only for non-fixed channel packets. Is that needed ?
  msg->event = BT_EVT_TO_BTU_SP_DATA;

  if (evt_data.cong) {
    osi_free(msg);
  } else {
    if (GAP_ConnWriteData(handle, msg) == BT_PASS) {
      evt_data.status = tBTA_JV_STATUS::SUCCESS;
    }
  }

  tBTA_JV bta_jv;
  bta_jv.l2c_write = evt_data;
  p_cb->p_cback(BTA_JV_L2CAP_WRITE_EVT, &bta_jv, user_id);
}

/*******************************************************************************
 *
 * Function     bta_jv_port_data_co_cback
 *
 * Description  port data callback function of rfcomm connections
 *
 * Returns      0 if success, 1 otherwise
 *
 ******************************************************************************/
static int bta_jv_port_data_co_cback(uint8_t port_handle, uint8_t* buf, uint16_t len, int type) {
  BtaJvRfcommCb* p_cb = bta_jv_rfc_port_to_cb(port_handle);
  BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(port_handle);
  log::verbose("p_cb={}, p_pcb={}, len={}, type={}", std::format_ptr(p_cb), std::format_ptr(p_pcb),
               len, type);
  if (p_pcb != nullptr) {
    switch (type) {
      case DATA_CO_CALLBACK_TYPE_INCOMING:
        // Reset sniff timer when receiving data by sysproxy
        if (osi_property_get_bool("bluetooth.rfcomm.sysproxy.rx.exit_sniff", false)) {
          bta_jv_reset_sniff_timer(p_pcb->p_pm_cb);
        }
        return bta_co_rfc_data_incoming(p_pcb->rfcomm_slot_id, (BT_HDR*)buf);
      case DATA_CO_CALLBACK_TYPE_OUTGOING_SIZE:
        return bta_co_rfc_data_outgoing_size(p_pcb->rfcomm_slot_id, (int*)buf);
      case DATA_CO_CALLBACK_TYPE_OUTGOING:
        return bta_co_rfc_data_outgoing(p_pcb->rfcomm_slot_id, buf, len);
      default:
        log::error("unknown callout type={}", type);
        break;
    }
  }
  return 0;
}

/*******************************************************************************
 *
 * Function     bta_jv_port_mgmt_cl_cback
 *
 * Description  callback for port management function of rfcomm client connections
 *
 ******************************************************************************/
static void bta_jv_port_mgmt_cl_cback(const tPORT_RESULT code, uint8_t port_handle) {
  BtaJvRfcommCb* p_cb = bta_jv_rfc_port_to_cb(port_handle);
  BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(port_handle);
  RawAddress rem_bda = RawAddress::kEmpty;
  uint16_t lcid;
  tBTA_JV_RFCOMM_CBACK* p_cback;  // the callback function

  if (p_cb == nullptr) {
    log::warn("p_cb is nullptr, code={}, port_handle={}", code, port_handle);
    return;
  } else if (p_cb->p_cback == nullptr) {
    log::warn("p_cb->p_cback is nullptr, code={}, port_handle={}", code, port_handle);
    return;
  }

  log::verbose("code={}, port_handle={}, p_cb->handle=0x{:x}", code, port_handle, p_cb->handle);

  if (PORT_CheckConnection(port_handle, &rem_bda, &lcid) != PORT_SUCCESS) {
    log::warn("Unable to check RFCOMM connection peer:{} port_handle:{}", rem_bda, port_handle);
  }

  if (code == PORT_SUCCESS) {
    tBTA_JV evt_data = {
            .rfc_open =
                    {
                            .status = tBTA_JV_STATUS::SUCCESS,
                            .handle = p_cb->handle,
                            .rem_bda = rem_bda,
                    },
    };
    if (PORT_GetChannelInfo(port_handle, &evt_data.rfc_open.rx_mtu, &evt_data.rfc_open.tx_mtu,
                            &evt_data.rfc_open.local_credit, &evt_data.rfc_open.remote_credit,
                            &evt_data.rfc_open.local_cid, &evt_data.rfc_open.remote_cid,
                            &evt_data.rfc_open.dlci, &evt_data.rfc_open.max_frame_size,
                            &evt_data.rfc_open.acl_handle,
                            &evt_data.rfc_open.mux_initiator) != PORT_SUCCESS) {
      log::warn("Unable to get RFCOMM channel info peer:{} port_handle:{}", rem_bda, port_handle);
    }
    p_pcb->state = BTA_JV_ST_CL_OPEN;
    p_cb->p_cback(BTA_JV_RFCOMM_OPEN_EVT, &evt_data, p_pcb->rfcomm_slot_id);
  } else {
    tBTA_JV evt_data = {
            .rfc_close =
                    {
                            .status = tBTA_JV_STATUS::FAILURE,
                            .port_status = code,
                            .handle = p_cb->handle,
                            .async = (p_pcb->state == BTA_JV_ST_CL_CLOSING) ? false : true,
                    },
    };
    p_cback = p_cb->p_cback;
    p_cback(BTA_JV_RFCOMM_CLOSE_EVT, &evt_data, p_pcb->rfcomm_slot_id);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_port_event_cl_cback
 *
 * Description  Callback for RFCOMM client port events
 *
 ******************************************************************************/
static void bta_jv_port_event_cl_cback(uint32_t code, uint8_t port_handle) {
  BtaJvRfcommCb* p_cb = bta_jv_rfc_port_to_cb(port_handle);
  BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(port_handle);
  tBTA_JV evt_data;

  log::verbose("port_handle={}", port_handle);
  if (p_cb == nullptr || p_cb->p_cback == nullptr) {
    return;
  }

  log::verbose("code=0x{:x}, port_handle={}, p_cb->handle=0x{:x}", code, port_handle, p_cb->handle);
  if (code & PORT_EV_RXCHAR) {
    evt_data.data_ind.handle = p_cb->handle;
    p_cb->p_cback(BTA_JV_RFCOMM_DATA_IND_EVT, &evt_data, p_pcb->rfcomm_slot_id);
  }

  if (code & PORT_EV_FC) {
    p_pcb->cong = (code & PORT_EV_FCS) ? false : true;
    if (p_pcb->cong && p_pcb->p_pm_cb != nullptr) {
      bta_jv_pm_conn_busy(p_pcb->p_pm_cb);
    }
    evt_data.rfc_cong.cong = p_pcb->cong;
    evt_data.rfc_cong.handle = p_cb->handle;
    evt_data.rfc_cong.status = tBTA_JV_STATUS::SUCCESS;
    p_cb->p_cback(BTA_JV_RFCOMM_CONG_EVT, &evt_data, p_pcb->rfcomm_slot_id);
  }

  if (code & PORT_EV_TXEMPTY) {
    bta_jv_pm_conn_idle(p_pcb->p_pm_cb);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_rfcomm_connect
 *
 * Description  This function is called when a client initiates an RFCOMM connection
 *
 *******************************************************************************/
void bta_jv_rfcomm_connect(tBTA_SEC sec_mask, uint8_t remote_scn, const RawAddress& peer_bd_addr,
                           tBTA_JV_RFCOMM_CBACK* p_cback, uint32_t rfcomm_slot_id,
                           RfcommCfgInfo cfg, uint32_t app_uid, uint64_t sdp_duration_ms) {
  uint8_t handle = 0;
  uint32_t event_mask = BTA_JV_RFC_EV_MASK;
  int port_status;
  PortSettings port_settings;

  tBTA_JV bta_jv = {
          .rfc_cl_init =
                  {
                          .status = tBTA_JV_STATUS::SUCCESS,
                          .handle = 0,
                          .sec_id = 0,
                          .use_co = false,
                  },
  };

  bluetooth::metrics::LogRfcommNativeStartEvent(
          peer_bd_addr, bluetooth::metrics::EventType::RFCOMM_SOCKET_NATIVE_CONNECTION, app_uid);

  port_status = RFCOMM_CreateConnectionWithSecurity(UUID_SERVCLASS_SERIAL_PORT, remote_scn, false,
                                                    BTA_JV_DEF_RFC_MTU, peer_bd_addr, &handle,
                                                    bta_jv_port_mgmt_cl_cback, sec_mask, cfg);
  if (port_status != PORT_SUCCESS) {
    log::error("RFCOMM_CreateConnection failed");
    bta_collect_rfc_metrics_after_port_fail(
            static_cast<tPORT_RESULT>(port_status), sdp_duration_ms > 0, tBTA_JV_STATUS::SUCCESS,
            peer_bd_addr, static_cast<int>(app_uid), sec_mask, false, sdp_duration_ms);
    bta_jv.rfc_cl_init.status = tBTA_JV_STATUS::FAILURE;
  } else {
    BtaJvPcb* p_pcb;
    BtaJvRfcommCb* p_cb = bta_jv_alloc_rfc_cb(handle, &p_pcb);
    if (p_cb) {
      p_cb->p_cback = p_cback;
      p_cb->scn = 0;
      p_pcb->state = BTA_JV_ST_CL_OPENING;
      log::verbose("Set p_pcb->rfcomm_slot_id={}", rfcomm_slot_id);
      p_pcb->rfcomm_slot_id = rfcomm_slot_id;
      bta_jv.rfc_cl_init.use_co = true;

      if (PORT_SetSdpDuration(handle, sdp_duration_ms) != PORT_SUCCESS) {
        log::warn("Unable to set sdp_duration for port_handle:{}", handle);
      }
      if (PORT_SetAppUid(handle, app_uid) != PORT_SUCCESS) {
        log::warn("Unable to set app_uid for port_handle:{}", handle);
      }
      if (PORT_SetEventMaskAndCallback(handle, event_mask, bta_jv_port_event_cl_cback) !=
          PORT_SUCCESS) {
        log::warn("Unable to set RFCOMM client event mask and callback port_handle:{}", handle);
      }
      if (PORT_SetDataCOCallback(handle, bta_jv_port_data_co_cback) != PORT_SUCCESS) {
        log::warn("Unable to set RFCOMM client data callback port_handle:{}", handle);
      }
      if (PORT_GetSettings(handle, &port_settings) != PORT_SUCCESS) {
        log::warn("Unable to get RFCOMM client state port_handle:{}", handle);
      }

      port_settings.fc_type = (PORT_FC_CTS_ON_INPUT | PORT_FC_CTS_ON_OUTPUT);

      if (PORT_SetSettings(handle, &port_settings) != PORT_SUCCESS) {
        log::warn("Unable to set RFCOMM client state port_handle:{}", handle);
      }

      bta_jv.rfc_cl_init.handle = p_cb->handle;
    } else {
      bta_jv.rfc_cl_init.status = tBTA_JV_STATUS::FAILURE;
      log::error("run out of rfc control block");
    }
  }

  p_cback(BTA_JV_RFCOMM_CL_INIT_EVT, &bta_jv, rfcomm_slot_id);
  if (bta_jv.rfc_cl_init.status == tBTA_JV_STATUS::FAILURE) {
    if (handle) {
      if (RFCOMM_RemoveConnection(handle) != PORT_SUCCESS) {
        log::warn("Unable to remove RFCOMM connection port_handle:{}", handle);
      }
    }
  }
}

static int find_rfc_pcb(uint32_t rfcomm_slot_id, BtaJvRfcommCb** cb, BtaJvPcb** pcb) {
  *cb = nullptr;
  *pcb = nullptr;
  int i;
  for (i = 0; i < MAX_RFC_PORTS; i++) {
    uint32_t rfc_handle = bta_jv_cb.port_cb[i].handle & BTA_JV_RFC_HDL_MASK;
    rfc_handle &= ~BTA_JV_RFCOMM_MASK;
    if (rfc_handle && bta_jv_cb.port_cb[i].rfcomm_slot_id == rfcomm_slot_id) {
      *pcb = &bta_jv_cb.port_cb[i];
      *cb = &bta_jv_cb.rfc_cb[rfc_handle - 1];
      log::verbose("FOUND rfc_handle=0x{:x}, pcb->jv_handle=0x{:x}, state={}, cb->handle=0x{:x}",
                   rfc_handle, (*pcb)->handle, (*pcb)->state, (*cb)->handle);
      return 1;
    }
  }
  log::verbose("cannot find rfc_cb from user data:{}", rfcomm_slot_id);
  return 0;
}

/*******************************************************************************
 *
 * Function     bta_jv_rfcomm_close
 *
 * Description  Closes an RFCOMM connection
 *
 *******************************************************************************/
void bta_jv_rfcomm_close(uint32_t handle, uint32_t rfcomm_slot_id) {
  if (!handle) {
    log::error("rfc_handle is null");
    return;
  }

  log::verbose("rfc_handle={}", handle);

  BtaJvRfcommCb* p_cb = nullptr;
  BtaJvPcb* p_pcb = nullptr;

  if (!find_rfc_pcb(rfcomm_slot_id, &p_cb, &p_pcb)) {
    return;
  }
  bta_jv_free_rfc_cb(p_cb, p_pcb);
}

/*******************************************************************************
 *
 * Function     bta_jv_port_mgmt_sr_cback
 *
 * Description  callback for port management function of rfcomm server connections
 *
 ******************************************************************************/
static void bta_jv_port_mgmt_sr_cback(const tPORT_RESULT code, uint8_t port_handle) {
  BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(port_handle);
  BtaJvRfcommCb* p_cb = bta_jv_rfc_port_to_cb(port_handle);
  tBTA_JV evt_data;
  RawAddress rem_bda = RawAddress::kEmpty;
  uint16_t lcid;
  log::verbose("code={}, port_handle={}", code, port_handle);
  if (p_cb == nullptr || p_cb->p_cback == nullptr) {
    log::error("p_cb={}, p_cb->p_cback={}", std::format_ptr(p_cb),
               std::format_ptr(p_cb ? p_cb->p_cback : nullptr));
    return;
  }
  uint32_t rfcomm_slot_id = p_pcb->rfcomm_slot_id;
  log::verbose("code={}, port_handle={}, jv_handle=0x{:x}, slot_id={}", code, port_handle,
               p_cb->handle, p_pcb->rfcomm_slot_id);

  int status = PORT_CheckConnection(port_handle, &rem_bda, &lcid);
  int failed = true;
  if (code == PORT_SUCCESS) {
    if (status != PORT_SUCCESS) {
      log::error("PORT_CheckConnection returned {}, although port is supposed to be connected",
                 status);
    }
    evt_data.rfc_srv_open.handle = p_pcb->handle;
    evt_data.rfc_srv_open.status = tBTA_JV_STATUS::SUCCESS;
    evt_data.rfc_srv_open.rem_bda = rem_bda;
    if (PORT_GetChannelInfo(port_handle, &evt_data.rfc_srv_open.rx_mtu,
                            &evt_data.rfc_srv_open.tx_mtu, &evt_data.rfc_srv_open.local_credit,
                            &evt_data.rfc_srv_open.remote_credit, &evt_data.rfc_srv_open.local_cid,
                            &evt_data.rfc_srv_open.remote_cid, &evt_data.rfc_srv_open.dlci,
                            &evt_data.rfc_srv_open.max_frame_size,
                            &evt_data.rfc_srv_open.acl_handle,
                            &evt_data.rfc_srv_open.mux_initiator) != PORT_SUCCESS) {
      log::warn("Unable to get RFCOMM channel info peer:{} port_handle:{}", rem_bda, port_handle);
    }
    BtaJvPcb* p_pcb_new_listen = bta_jv_add_rfc_port(p_cb, p_pcb);
    if (p_pcb_new_listen) {
      evt_data.rfc_srv_open.new_listen_handle = p_pcb_new_listen->handle;
      p_pcb_new_listen->rfcomm_slot_id =
              p_cb->p_cback(BTA_JV_RFCOMM_SRV_OPEN_EVT, &evt_data, rfcomm_slot_id);
      if (p_pcb_new_listen->rfcomm_slot_id == 0) {
        log::error("p_pcb_new_listen->rfcomm_slot_id={}", p_pcb_new_listen->rfcomm_slot_id);
      } else {
        log::verbose("curr_sess={}, max_sess={}", p_cb->curr_sess, p_cb->max_sess);
        failed = false;
      }
    } else {
      log::error("failed to create new listen port");
    }
  }
  if (failed) {
    evt_data.rfc_close.handle = p_cb->handle;
    evt_data.rfc_close.status = tBTA_JV_STATUS::FAILURE;
    evt_data.rfc_close.async = true;
    evt_data.rfc_close.port_status = code;
    p_pcb->cong = false;

    tBTA_JV_RFCOMM_CBACK* p_cback = p_cb->p_cback;
    log::verbose("PORT_CLOSED before BTA_JV_RFCOMM_CLOSE_EVT: curr_sess={}, max_sess={}",
                 p_cb->curr_sess, p_cb->max_sess);
    if (BTA_JV_ST_SR_CLOSING == p_pcb->state) {
      evt_data.rfc_close.async = false;
      evt_data.rfc_close.status = tBTA_JV_STATUS::SUCCESS;
    }
    p_cback(BTA_JV_RFCOMM_CLOSE_EVT, &evt_data, rfcomm_slot_id);

    log::verbose("PORT_CLOSED after BTA_JV_RFCOMM_CLOSE_EVT: curr_sess={}, max_sess={}",
                 p_cb->curr_sess, p_cb->max_sess);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_port_event_sr_cback
 *
 * Description  Callback for RFCOMM server port events
 *
 ******************************************************************************/
static void bta_jv_port_event_sr_cback(uint32_t code, uint8_t port_handle) {
  BtaJvPcb* p_pcb = bta_jv_rfc_port_to_pcb(port_handle);
  BtaJvRfcommCb* p_cb = bta_jv_rfc_port_to_cb(port_handle);
  tBTA_JV evt_data;

  if (p_cb == nullptr || p_cb->p_cback == nullptr) {
    log::error("p_cb={}, p_cb->p_cback={}, port_handle={}", std::format_ptr(p_cb),
               std::format_ptr(p_cb ? p_cb->p_cback : nullptr), port_handle);
    return;
  }

  log::verbose("code=0x{:x}, port_handle={}, p_cb->handle=0x{:x}", code, port_handle, p_cb->handle);

  uint32_t user_data = p_pcb->rfcomm_slot_id;
  if (code & PORT_EV_RXCHAR) {
    evt_data.data_ind.handle = p_cb->handle;
    p_cb->p_cback(BTA_JV_RFCOMM_DATA_IND_EVT, &evt_data, user_data);
  }

  if (code & PORT_EV_FC) {
    p_pcb->cong = (code & PORT_EV_FCS) ? false : true;
    if (p_pcb->cong && p_pcb->p_pm_cb != nullptr) {
      bta_jv_pm_conn_busy(p_pcb->p_pm_cb);
    }
    evt_data.rfc_cong.cong = p_pcb->cong;
    evt_data.rfc_cong.handle = p_cb->handle;
    evt_data.rfc_cong.status = tBTA_JV_STATUS::SUCCESS;
    p_cb->p_cback(BTA_JV_RFCOMM_CONG_EVT, &evt_data, user_data);
  }

  if (code & PORT_EV_TXEMPTY) {
    bta_jv_pm_conn_idle(p_pcb->p_pm_cb);
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_add_rfc_port
 *
 * Description  add a port for server when the existing posts is open
 *
 * Returns      a pointer to BtaJvPcb just added
 *
 ******************************************************************************/
static BtaJvPcb* bta_jv_add_rfc_port(BtaJvRfcommCb* p_cb, BtaJvPcb* p_pcb_open) {
  uint8_t used = 0, i, listen = 0;
  uint32_t si = 0;
  int port_status;
  PortSettings port_settings;
  uint32_t event_mask = BTA_JV_RFC_EV_MASK;
  BtaJvPcb* p_pcb = nullptr;
  tBTA_SEC sec_mask;
  if (p_cb->max_sess > 1) {
    for (i = 0; i < p_cb->max_sess; i++) {
      if (p_cb->port_hdls[i] != 0) {
        p_pcb = &bta_jv_cb.port_cb[p_cb->port_hdls[i] - 1];
        if (p_pcb->state == BTA_JV_ST_SR_LISTEN) {
          listen++;
          if (p_pcb_open == p_pcb) {
            log::verbose("port_handle={}, change the listen port to open state",
                         p_pcb->port_handle);
            p_pcb->state = BTA_JV_ST_SR_OPEN;

          } else {
            log::error(
                    "open pcb not matching listen one, count={}, listen port_handle={}, "
                    "p_pcb_open->handle=0x{:x}",
                    listen, p_pcb->port_handle, p_pcb_open->handle);
            return nullptr;
          }
        }
        used++;
      } else if (si == 0) {
        si = i + 1;
      }
    }

    log::verbose("max_sess={}, used={}, curr_sess={}, listen={}, si={}", p_cb->max_sess, used,
                 p_cb->curr_sess, listen, si);
    if (used < p_cb->max_sess && listen == 1 && si) {
      si--;
      if (PORT_GetSecurityMask(p_pcb_open->port_handle, &sec_mask) != PORT_SUCCESS) {
        log::error("RFCOMM_CreateConnection failed: invalid port_handle");
      }

      port_status = RFCOMM_CreateConnectionWithSecurity(
              p_cb->sec_id, p_cb->scn, true, BTA_JV_DEF_RFC_MTU, RawAddress::kAny,
              &(p_cb->port_hdls[si]), bta_jv_port_mgmt_sr_cback, sec_mask, RfcommCfgInfo{});
      if (port_status == PORT_SUCCESS) {
        p_cb->curr_sess++;
        p_pcb = &bta_jv_cb.port_cb[p_cb->port_hdls[si] - 1];
        p_pcb->state = BTA_JV_ST_SR_LISTEN;
        p_pcb->port_handle = p_cb->port_hdls[si];
        log::verbose("setting p_pcb->rfcomm_slot_id={}", p_pcb_open->rfcomm_slot_id);
        p_pcb->rfcomm_slot_id = p_pcb_open->rfcomm_slot_id;

        if (PORT_ClearKeepHandleFlag(p_pcb->port_handle) != PORT_SUCCESS) {
          log::warn("Unable to clear RFCOMM server keep handle flag port_handle:{}",
                    p_pcb->port_handle);
        }
        if (PORT_SetEventMaskAndCallback(p_pcb->port_handle, event_mask,
                                         bta_jv_port_event_sr_cback) != PORT_SUCCESS) {
          log::warn("Unable to set RFCOMM server event mask and callback port_handle:{}",
                    p_pcb->port_handle);
        }
        if (PORT_SetDataCOCallback(p_pcb->port_handle, bta_jv_port_data_co_cback) != PORT_SUCCESS) {
          log::warn("Unable to set RFCOMM server data callback port_handle:{}", p_pcb->port_handle);
        }
        if (PORT_GetSettings(p_pcb->port_handle, &port_settings) != PORT_SUCCESS) {
          log::warn("Unable to get RFCOMM server state port_handle:{}", p_pcb->port_handle);
        }

        port_settings.fc_type = (PORT_FC_CTS_ON_INPUT | PORT_FC_CTS_ON_OUTPUT);

        if (PORT_SetSettings(p_pcb->port_handle, &port_settings) != PORT_SUCCESS) {
          log::warn("Unable to set RFCOMM server state port_handle:{}", p_pcb->port_handle);
        }
        p_pcb->handle = BTA_JV_RFC_H_S_TO_HDL(p_cb->handle, si);
        log::verbose("p_pcb->handle=0x{:x}, curr_sess={}", p_pcb->handle, p_cb->curr_sess);
      } else {
        log::error("RFCOMM_CreateConnection failed");
        bta_collect_rfc_metrics_after_port_fail(static_cast<tPORT_RESULT>(port_status), false,
                                                tBTA_JV_STATUS::SUCCESS, RawAddress::kAny, 0,
                                                sec_mask, true, 0);

        return nullptr;
      }
    } else {
      log::error("cannot create new rfc listen port");
      return nullptr;
    }
  }
  log::verbose("sec id in use={}, rfc_cb in use={}", get_sec_id_used(), get_rfc_cb_used());
  return p_pcb;
}

/*******************************************************************************
 *
 * Function     bta_jv_rfcomm_start_server
 *
 * Description  starts an RFCOMM server, which will wait for a client to connect
 *
 *******************************************************************************/
void bta_jv_rfcomm_start_server(tBTA_SEC sec_mask, uint8_t local_scn, uint8_t max_session,
                                tBTA_JV_RFCOMM_CBACK* p_cback, uint32_t rfcomm_slot_id,
                                RfcommCfgInfo cfg, uint32_t app_uid) {
  uint8_t handle = 0;
  uint32_t event_mask = BTA_JV_RFC_EV_MASK;
  int port_status;
  PortSettings port_settings;
  BtaJvRfcommCb* p_cb = nullptr;
  BtaJvPcb* p_pcb;
  tBTA_JV_RFCOMM_START evt_data;

  memset(&evt_data, 0, sizeof(evt_data));
  evt_data.status = tBTA_JV_STATUS::FAILURE;

  do {
    port_status = RFCOMM_CreateConnectionWithSecurity(0, local_scn, true, BTA_JV_DEF_RFC_MTU,
                                                      RawAddress::kAny, &handle,
                                                      bta_jv_port_mgmt_sr_cback, sec_mask, cfg);
    if (port_status != PORT_SUCCESS) {
      log::error("RFCOMM_CreateConnection failed");
      bta_collect_rfc_metrics_after_port_fail(static_cast<tPORT_RESULT>(port_status), false,
                                              tBTA_JV_STATUS::SUCCESS, RawAddress::kAny,
                                              static_cast<int>(app_uid), sec_mask, true, 0);
      break;
    }

    p_cb = bta_jv_alloc_rfc_cb(handle, &p_pcb);
    if (p_cb == nullptr) {
      log::error("run out of rfc control block");
      break;
    }

    p_cb->max_sess = max_session;
    p_cb->p_cback = p_cback;
    p_cb->scn = local_scn;
    p_pcb->state = BTA_JV_ST_SR_LISTEN;
    log::verbose("setting p_pcb->rfcomm_slot_id={}", rfcomm_slot_id);
    p_pcb->rfcomm_slot_id = rfcomm_slot_id;
    evt_data.status = tBTA_JV_STATUS::SUCCESS;
    evt_data.handle = p_cb->handle;
    evt_data.use_co = true;

    if (PORT_SetAppUid(handle, app_uid) != PORT_SUCCESS) {
      log::warn("Unable to set app_uid for port_handle:{}", handle);
    }
    if (PORT_ClearKeepHandleFlag(handle) != PORT_SUCCESS) {
      log::warn("Unable to clear RFCOMM server keep handle flag port_handle:{}", handle);
    }
    if (PORT_SetEventMaskAndCallback(handle, event_mask, bta_jv_port_event_sr_cback) !=
        PORT_SUCCESS) {
      log::warn("Unable to set RFCOMM server event mask and callback port_handle:{}", handle);
    }
    if (PORT_GetSettings(handle, &port_settings) != PORT_SUCCESS) {
      log::warn("Unable to get RFCOMM server state port_handle:{}", handle);
    }

    port_settings.fc_type = (PORT_FC_CTS_ON_INPUT | PORT_FC_CTS_ON_OUTPUT);

    if (PORT_SetSettings(handle, &port_settings) != PORT_SUCCESS) {
      log::warn("Unable to set RFCOMM port state port_handle:{}", handle);
    }
  } while (0);

  tBTA_JV bta_jv;
  bta_jv.rfc_start = evt_data;
  p_cback(BTA_JV_RFCOMM_START_EVT, &bta_jv, rfcomm_slot_id);
  if (bta_jv.rfc_start.status == tBTA_JV_STATUS::SUCCESS) {
    if (PORT_SetDataCOCallback(handle, bta_jv_port_data_co_cback) != PORT_SUCCESS) {
      log::error("Unable to set RFCOMM server data callback port_handle:{}", handle);
    }
  } else {
    if (handle) {
      if (RFCOMM_RemoveConnection(handle) != PORT_SUCCESS) {
        log::warn("Unable to remote RFCOMM server connection port_handle:{}", handle);
      }
    }
  }
}

/*******************************************************************************
 *
 * Function     bta_jv_rfcomm_stop_server
 *
 * Description  Stops an RFCOMM server
 *
 *******************************************************************************/
void bta_jv_rfcomm_stop_server(uint32_t handle, uint32_t rfcomm_slot_id) {
  if (!handle) {
    log::error("jv_handle is null");
    return;
  }

  log::verbose("jv_handle=0x{:x}, slot_id={}", handle, rfcomm_slot_id);
  BtaJvRfcommCb* p_cb = nullptr;
  BtaJvPcb* p_pcb = nullptr;

  if (!find_rfc_pcb(rfcomm_slot_id, &p_cb, &p_pcb)) {
    return;
  }
  log::verbose("p_pcb->handle=0x{:x}, p_pcb->port_handle={}", p_pcb->handle, p_pcb->port_handle);
  bta_jv_free_rfc_cb(p_cb, p_pcb);
}

/*******************************************************************************
 *
 * Function     bta_jv_rfcomm_write
 *
 * Description  Writes data to an RFCOMM connection
 *
 *******************************************************************************/
void bta_jv_rfcomm_write(uint32_t handle, uint32_t req_id, BtaJvRfcommCb* p_cb, BtaJvPcb* p_pcb) {
  if (p_pcb->state == BTA_JV_ST_NONE) {
    log::error("in state BTA_JV_ST_NONE - cannot write");
    return;
  }

  tBTA_JV_RFCOMM_WRITE evt_data;
  evt_data.status = tBTA_JV_STATUS::FAILURE;
  evt_data.handle = handle;
  evt_data.req_id = req_id;
  evt_data.cong = p_pcb->cong;
  evt_data.len = 0;

  bta_jv_pm_conn_busy(p_pcb->p_pm_cb);

  if (!evt_data.cong) {
    int write_status = PORT_WriteDataCO(p_pcb->port_handle, &evt_data.len);
    if (write_status == PORT_SUCCESS) {
      evt_data.status = tBTA_JV_STATUS::SUCCESS;
    } else {
      log::warn("write failed with result:{}", static_cast<tPORT_RESULT>(write_status));
    }
  } else {
    log::debug("write failed due to congestion");
  }

  // Update congestion flag
  evt_data.cong = p_pcb->cong;

  if (p_cb->p_cback == nullptr) {
    log::error("No JV callback set");
    return;
  }

  tBTA_JV bta_jv;
  bta_jv.rfc_write = evt_data;
  p_cb->p_cback(BTA_JV_RFCOMM_WRITE_EVT, &bta_jv, p_pcb->rfcomm_slot_id);
}

/*******************************************************************************
 *
 * Function     bta_jv_set_pm_profile
 *
 * Description  Set or free power mode profile for a JV application
 *
 *******************************************************************************/
void bta_jv_set_pm_profile(uint32_t handle, tBTA_JV_PM_ID app_id, tBTA_JV_CONN_STATE init_st) {
  log::verbose("jv_handle=0x{:x}, app_id={}, init_st={}", handle, app_id,
               bta_jv_conn_state_text(init_st));

  // clear PM control block
  if (app_id == BTA_JV_PM_ID_CLEAR) {
    tBTA_JV_STATUS status = bta_jv_free_set_pm_profile_cb(handle);
    if (status != tBTA_JV_STATUS::SUCCESS) {
      log::warn("Unable to free a power mode profile jv_handle:0x{:x} app_id:{} state:{} status:{}",
                handle, app_id, init_st, bta_jv_status_text(status));
    }
  } else {  // set PM control block
    BtaJvPmCb* p_cb = bta_jv_alloc_set_pm_profile_cb(handle, app_id);
    if (p_cb) {
      bta_jv_pm_state_change(p_cb, init_st);
    } else {
      log::warn("Unable to allocate a power mode profile jv_handle:0x{:x} app_id:{} state:{}",
                handle, app_id, init_st);
    }
  }
}

/*******************************************************************************
 *
 * Function    bta_jv_pm_conn_busy
 *
 * Description set pm connection busy state (input param safe)
 *
 * Params      p_cb: pm control block of jv connection
 *
 ******************************************************************************/
static void bta_jv_pm_conn_busy(BtaJvPmCb* p_cb) {
  if (p_cb == nullptr) {
    return;
  }
  if (BTA_JV_PM_BUSY_ST == p_cb->state) {
    return;
  }
  if (BTA_JV_PM_BUSY_TO_IDLE_ST == p_cb->state) {
    p_cb->state = BTA_JV_PM_BUSY_ST;
    return;
  }
  bta_jv_pm_state_change(p_cb, BTA_JV_CONN_BUSY);
}

/*******************************************************************************
 *
 * Function    bta_jv_pm_conn_idle
 *
 * Description set pm connection idle state (input param safe)
 *
 * Params      p_cb: pm control block of jv connection
 *
 * Returns     void
 *
 ******************************************************************************/
static void bta_jv_pm_conn_idle(BtaJvPmCb* p_cb) {
  if (p_cb == nullptr) {
    return;
  }

    if (p_cb->state != BTA_JV_PM_IDLE_ST && p_cb->state != BTA_JV_PM_BUSY_TO_IDLE_ST) {
      p_cb->state = BTA_JV_PM_BUSY_TO_IDLE_ST;
      // When busy -> idle -> busy -> idle, the alarm can be already scheduled.
      if (!alarm_is_scheduled(p_cb->idle_timer)) {
        alarm_set_on_mloop(p_cb->idle_timer, BTA_JV_PM_IDLE_TIMEOUT_MS, bta_jv_idle_timeout_handler,
                           p_cb);
      }
    }
}

/*******************************************************************************
 *
 * Function     bta_jv_pm_state_change
 *
 * Description  Notify power manager there is state change
 *
 * Params       p_cb: must be non-null
 *
 * Returns      void
 *
 ******************************************************************************/
static void bta_jv_pm_state_change(BtaJvPmCb* p_cb, const tBTA_JV_CONN_STATE state) {
  log::verbose("p_cb->handle=0x{:x}, busy/idle_state={}, app_id={}, conn_state={}", p_cb->handle,
               bta_jv_pm_state_text(p_cb->state), p_cb->app_id, bta_jv_conn_state_text(state));

  switch (state) {
    case BTA_JV_CONN_OPEN:
      bta_sys_conn_open(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_CONN_CLOSE:
      bta_sys_conn_close(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_APP_OPEN:
      bta_sys_app_open(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_APP_CLOSE:
      bta_sys_app_close(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_SCO_OPEN:
      bta_sys_sco_open(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_SCO_CLOSE:
      bta_sys_sco_close(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_CONN_IDLE:
      p_cb->state = BTA_JV_PM_IDLE_ST;
      bta_sys_idle(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    case BTA_JV_CONN_BUSY:
      p_cb->state = BTA_JV_PM_BUSY_ST;
      bta_sys_busy(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
      break;

    default:
      log::warn("Invalid state={}", bta_jv_conn_state_text(state));
      break;
  }
}

/*******************************************************************************
 *
 * Function    bta_jv_reset_sniff_timer
 *
 * Description reset pm sniff timer state (input param safe)
 *
 * Params      p_cb: pm control block of jv connection
 *
 ******************************************************************************/
static void bta_jv_reset_sniff_timer(BtaJvPmCb* p_cb) {
  if (p_cb != nullptr) {
    p_cb->state = BTA_JV_PM_IDLE_ST;
    bta_sys_reset_sniff(BTA_ID_JV, p_cb->app_id, p_cb->peer_bd_addr);
  }
}

/*******************************************************************************
 *
 * Function         bta_jv_idle_timeout_handler
 *
 * Description      Bta JV specific idle timeout handler
 *
 ******************************************************************************/
void bta_jv_idle_timeout_handler(void* data) {
  if (data == nullptr) {
    return;
  }

  BtaJvPmCb* p_cb = (BtaJvPmCb*)data;

  // The state has been changed
  if (p_cb->state != BTA_JV_PM_BUSY_TO_IDLE_ST) {
    return;
  }
  bta_jv_pm_state_change(p_cb, BTA_JV_CONN_IDLE);
}

/******************************************************************************/

namespace bluetooth::legacy::testing {

void bta_jv_start_discovery_cback(uint32_t rfcomm_slot_id, const RawAddress& bd_addr,
                                  tSDP_RESULT result) {
  ::bta_jv_start_discovery_cback(rfcomm_slot_id, bd_addr, result);
}

}  // namespace bluetooth::legacy::testing
