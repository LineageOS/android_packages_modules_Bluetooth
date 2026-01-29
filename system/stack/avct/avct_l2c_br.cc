/******************************************************************************
 *
 *  Copyright 2008-2016 Broadcom Corporation
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

/*****************************************************************************
 *
 *  Name:           avct_l2c_br.cc
 *
 *  Description:    This AVCTP module interfaces to L2CAP
 *
 *****************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstddef>
#include <cstdint>

#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "stack/avct/avct_int.h"
#include "stack/include/avct_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cap_types.h"
#include "stack/include/l2cdefs.h"

using namespace bluetooth;

/* callback function declarations */
static void avct_l2c_br_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t psm,
                                          uint8_t id);
static void avct_l2c_br_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result);
static void avct_l2c_br_config_cfm_cback(uint16_t lcid, uint16_t result, tL2CAP_CFG_INFO* p_cfg);
static void avct_l2c_br_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg);
static void avct_l2c_br_disconnect_ind_cback(uint16_t lcid, bool ack_needed);
static void avct_l2c_br_congestion_ind_cback(uint16_t lcid, bool is_congested);
static void avct_l2c_br_data_ind_cback(uint16_t lcid, BT_HDR* p_buf);
static void avct_br_on_l2cap_error(uint16_t lcid, uint16_t result);

/* L2CAP callback function structure */
const tL2CAP_APPL_INFO avct_l2c_br_appl = {avct_l2c_br_connect_ind_cback,
                                           avct_l2c_br_connect_cfm_cback,
                                           avct_l2c_br_config_ind_cback,
                                           avct_l2c_br_config_cfm_cback,
                                           avct_l2c_br_disconnect_ind_cback,
                                           NULL,
                                           avct_l2c_br_data_ind_cback,
                                           avct_l2c_br_congestion_ind_cback,
                                           NULL,
                                           avct_br_on_l2cap_error,
                                           NULL,
                                           NULL,
                                           NULL,
                                           NULL};

/*******************************************************************************
 *
 * Function         avct_l2c_br_is_passive
 *
 * Description      check is the CCB associated with the given BCB was created
 *                  as passive
 *
 * Returns          true, if the given CCB is created as AVCT_PASSIVE
 *
 ******************************************************************************/
static bool avct_l2c_br_is_passive(tAVCT_BCB* p_bcb) {
  bool is_passive = false;
  tAVCT_CCB* p_ccb = &avct_cb.ccb[0];
  tAVCT_LCB* p_lcb = avct_lcb_by_bcb(p_bcb);
  int i;

  for (i = 0; i < AVCT_NUM_CONN; i++, p_ccb++) {
    if (p_ccb->allocated && (p_ccb->p_lcb == p_lcb)) {
      log::verbose("Is bcb associated ccb control passive :0x{:x}", p_ccb->cc.control);
      if (p_ccb->cc.control & AVCT_PASSIVE) {
        is_passive = true;
        break;
      }
    }
  }
  return is_passive;
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_connect_ind_cback
 *
 * Description      This is the L2CAP connect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t /* psm */,
                                   uint8_t /* id */) {
  tL2CAP_CONN result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
  tAVCT_BCB* p_bcb{nullptr};

  tAVCT_LCB* p_lcb = avct_lcb_by_bd(bd_addr);
  if (p_lcb != NULL) {
    /* control channel exists */
    p_bcb = avct_bcb_by_lcb(p_lcb);
    p_bcb->peer_addr = bd_addr;

    if (p_bcb->allocated == 0) {
      /* browsing channel does not exist yet and the browsing channel is
       * registered
       * - accept connection */
      p_bcb->allocated = p_lcb->allocated; /* copy the index from lcb */

      result = tL2CAP_CONN::L2CAP_CONN_OK;
    } else {
      if (!avct_l2c_br_is_passive(p_bcb) || (p_bcb->ch_state == AVCT_CH_OPEN)) {
        /* this BCB included CT role - reject */
        result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
      } else {
        /* add channel ID to conflict ID */
        p_bcb->conflict_lcid = p_bcb->ch_lcid;
        result = tL2CAP_CONN::L2CAP_CONN_OK;
        log::verbose("Detected conflict_lcid:0x{:x}", p_bcb->conflict_lcid);
      }
    }
  }

  /* if result ok, proceed with connection */
  if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
    /* store LCID */
    p_bcb->ch_lcid = lcid;
    /* transition to configuration state */
    p_bcb->ch_state = AVCT_CH_CFG;
  } else {
    /* else no control channel yet, reject */
    /* If we reject the connection, send DisconnectReq */
    log::verbose("Connection rejected to lcid:0x{:x}", lcid);
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to send L2CAP disconnect request cid:0x{:04x}", lcid);
    }
  }
}

void avct_br_on_l2cap_error(uint16_t lcid, uint16_t result) {
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == nullptr) {
    return;
  }

  if (p_bcb->ch_state == AVCT_CH_CONN && p_bcb->conflict_lcid == lcid) {
    log::verbose("Reset conflict_lcid:0x{:x}", p_bcb->conflict_lcid);
    p_bcb->conflict_lcid = 0;
    return;
  }
  /* store result value */
  p_bcb->ch_result = result;

  /* Send L2CAP disconnect req */
  avct_l2c_br_disconnect(lcid, 0 /* is_ack_needed */);
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_connect_cfm_cback
 *
 * Description      This is the L2CAP connect confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result) {
  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == nullptr) {
    log::warn(
            "Received browse connect confirm callback without a browse control channel "
            "lcid:0x{:02x} status:{}",
            lcid, l2cap_result_code_text(result));
    return;
  }
  if (p_bcb->ch_state == AVCT_CH_CONN) {
    /* if result successful */
    if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
      /* set channel state */
      p_bcb->ch_state = AVCT_CH_CFG;
    } else {
      log::error("Invoked with non OK lcid:0x{:04x} state:{} status:{}", lcid,
                 avct_ch_state_text(p_bcb->ch_state), l2cap_result_code_text(result));
    }
  } else if (p_bcb->conflict_lcid == lcid) {
    /* we must be in AVCT_CH_CFG state for the ch_lcid channel */
    if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
      /* just in case the peer also accepts our connection - Send L2CAP
       * disconnect req */
      log::verbose("Disconnect conflict_lcid:0x{:x}", p_bcb->conflict_lcid);
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} lcid:0x{:04x}", p_bcb->peer_addr,
                  lcid);
      }
    } else {
      log::error("Invoked with failure peer:{} lcid:0x{:04x} conflict_lcid:0x{:04x} status:{}",
                 p_bcb->peer_addr, lcid, p_bcb->conflict_lcid, l2cap_result_code_text(result));
    }
    p_bcb->conflict_lcid = 0;
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_config_cfm_cback
 *
 * Description      This is the L2CAP config confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_config_cfm_cback(uint16_t lcid, uint16_t initiator, tL2CAP_CFG_INFO* p_cfg) {
  log::verbose("lcid:0x{:04x} initiator:{}", lcid, initiator);
  avct_l2c_br_config_ind_cback(lcid, p_cfg);

  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if ((p_bcb == NULL) || (p_bcb->ch_state != AVCT_CH_CFG)) {
    log::warn(
            "Got config confirm callback with no browse channel or browse channel not in "
            "configuration state");
    return;
  }

  p_bcb->ch_state = AVCT_CH_OPEN;
  avct_bcb_event(p_bcb, AVCT_LCB_LL_OPEN_EVT, NULL);
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_config_ind_cback
 *
 * Description      This is the L2CAP config indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg) {
  log::verbose("lcid:0x{:04x}", lcid);
  const uint16_t max_mtu = BT_DEFAULT_BUFFER_SIZE - L2CAP_MIN_OFFSET - BT_HDR_SIZE;

  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == NULL) {
    log::warn("Unable to find browse control block lcid:0x{:02x}", lcid);
    return;
  }

  /* store the mtu in tbl */
  p_bcb->peer_mtu = L2CAP_DEFAULT_MTU;
  if (p_cfg->mtu_present) {
    p_bcb->peer_mtu = p_cfg->mtu;
  }

  if (p_bcb->peer_mtu > max_mtu) {
    p_bcb->peer_mtu = max_mtu;
  }

  log::verbose("peer_mtu:{} max_mtu:{}", p_bcb->peer_mtu, max_mtu);
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_disconnect_ind_cback
 *
 * Description      This is the L2CAP disconnect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_disconnect_ind_cback(uint16_t lcid, bool /* ack_needed */) {
  uint16_t result = AVCT_RESULT_FAIL;

  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == NULL) {
    return;
  }

  tAVCT_LCB_EVT avct_lcb_evt;
  avct_lcb_evt.result = result;
  avct_bcb_event(p_bcb, AVCT_LCB_LL_CLOSE_EVT, &avct_lcb_evt);
}

void avct_l2c_br_disconnect(uint16_t lcid, uint16_t result) {
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
    log::warn("Unable to send L2CAP disconnect request cid:0x{:04x}", lcid);
  }

  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == NULL) {
    return;
  }

  /* result value may be previously stored */
  uint16_t res = (p_bcb->ch_result != 0) ? p_bcb->ch_result : result;
  p_bcb->ch_result = 0;

  tAVCT_LCB_EVT avct_lcb_evt;
  avct_lcb_evt.result = res;
  avct_bcb_event(p_bcb, AVCT_LCB_LL_CLOSE_EVT, &avct_lcb_evt);
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_congestion_ind_cback
 *
 * Description      This is the L2CAP congestion indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_congestion_ind_cback(uint16_t lcid, bool is_congested) {
  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == NULL) {
    return;
  }

  tAVCT_LCB_EVT avct_lcb_evt;
  avct_lcb_evt.cong = is_congested;
  avct_bcb_event(p_bcb, AVCT_LCB_LL_CONG_EVT, &avct_lcb_evt);
}

/*******************************************************************************
 *
 * Function         avct_l2c_br_data_ind_cback
 *
 * Description      This is the L2CAP data indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_br_data_ind_cback(uint16_t lcid, BT_HDR* p_buf) {
  /* look up bcb for this channel */
  tAVCT_BCB* p_bcb = avct_bcb_by_lcid(lcid);
  if (p_bcb == NULL) {
    /* prevent buffer leak */
    osi_free(p_buf);
    return;
  }

  tAVCT_LCB_EVT evt_data{};
  evt_data.p_buf = p_buf;
  avct_bcb_event(p_bcb, AVCT_LCB_LL_MSG_EVT, &evt_data);
}
