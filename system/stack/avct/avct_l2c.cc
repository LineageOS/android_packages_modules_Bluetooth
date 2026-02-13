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
 *  This AVCTP module interfaces to L2CAP
 *
 ******************************************************************************/
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstddef>
#include <cstdint>

#include "avct_int.h"
#include "btif/include/btif_av.h"
#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "stack/include/avct_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cap_types.h"
#include "stack/include/l2cdefs.h"

using namespace bluetooth;

/* callback function declarations */
static void avct_l2c_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t psm,
                                       uint8_t id);
static void avct_l2c_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result);
static void avct_l2c_config_cfm_cback(uint16_t lcid, uint16_t result, tL2CAP_CFG_INFO* p_cfg);
static void avct_l2c_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg);
static void avct_l2c_disconnect_ind_cback(uint16_t lcid, bool ack_needed);
static void avct_l2c_congestion_ind_cback(uint16_t lcid, bool is_congested);
static void avct_l2c_data_ind_cback(uint16_t lcid, BT_HDR* p_buf);
static void avct_on_l2cap_error(uint16_t lcid, uint16_t result);

/* L2CAP callback function structure */
const tL2CAP_APPL_INFO avct_l2c_appl = {
        avct_l2c_connect_ind_cback,
        avct_l2c_connect_cfm_cback,
        avct_l2c_config_ind_cback,
        avct_l2c_config_cfm_cback,
        avct_l2c_disconnect_ind_cback,
        NULL,
        avct_l2c_data_ind_cback,
        avct_l2c_congestion_ind_cback,
        NULL,
        avct_on_l2cap_error,
        NULL,
        NULL,
        NULL,
        NULL,
};

/*******************************************************************************
 *
 * Function         avct_l2c_is_passive
 *
 * Description      check is the CCB associated with the given LCB was created
 *                  as passive
 *
 * Returns          true, if the given LCB is created as AVCT_PASSIVE
 *
 ******************************************************************************/
static bool avct_l2c_is_passive(tAVCT_LCB* p_lcb) {
  bool is_passive = false;
  tAVCT_CCB* p_ccb = &avct_cb.ccb[0];
  int i;

  for (i = 0; i < AVCT_NUM_CONN; i++, p_ccb++) {
    if (p_ccb->allocated && (p_ccb->p_lcb == p_lcb)) {
      log::verbose("avct_l2c_is_ct control:x{:x}", p_ccb->cc.control);
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
 * Function         avct_l2c_connect_ind_cback
 *
 * Description      This is the L2CAP connect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t /* psm */,
                                uint8_t /* id */) {
  tL2CAP_CONN result = tL2CAP_CONN::L2CAP_CONN_OK;

  /* do we already have a channel for this peer? */
  tAVCT_LCB* p_lcb = avct_lcb_by_bd(bd_addr);
  if (p_lcb == nullptr) {
    /* no, allocate lcb */
    p_lcb = avct_lcb_alloc(bd_addr);
    if (p_lcb == nullptr) {
      /* no ccb available, reject L2CAP connection */
      result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
    }
  } else {
    /* else we already have a channel for this peer */
    if (!avct_l2c_is_passive(p_lcb) || (p_lcb->ch_state == AVCT_CH_OPEN)) {
      /* this LCB included CT role - reject */
      result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
    } else {
      /* TG role only - accept the connection from CT. move the channel ID to
       * the conflict list */
      p_lcb->conflict_lcid = p_lcb->ch_lcid;
      log::verbose("Accept connection from controller lcid:0x{:04x} conflict_lcid:0x{:04x}", lcid,
                   p_lcb->conflict_lcid);
    }
  }

  /* if result ok, proceed with connection */
  if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
    if (btif_av_src_sink_coexist_enabled()) {
      tAVCT_CCB* p_ccb = &avct_cb.ccb[0];
      for (int i = 0; i < AVCT_NUM_CONN; i++, p_ccb++) {
        if (p_ccb && p_ccb->allocated && (p_ccb->p_lcb == NULL) &&
            (p_ccb->cc.role == AVCT_ROLE_ACCEPTOR)) {
          p_ccb->p_lcb = p_lcb;
          log::verbose(
                  "Source and sink coexistance enabled acceptor bind ccb to lcb idx:{} "
                  "allocated:{} role {} pid 0x{:x}",
                  i, p_ccb->allocated, avct_role_text(p_ccb->cc.role), p_ccb->cc.pid);
        }
      }
    }
    /* store LCID */
    p_lcb->ch_lcid = lcid;

    /* transition to configuration state */
    p_lcb->ch_state = AVCT_CH_CFG;

    log::debug("Received remote connection request peer:{} lcid:0x{:04x} ch_state:{}", bd_addr,
               lcid, avct_ch_state_text(p_lcb->ch_state));
  } else {
    /* If we reject the connection, send DisconnectReq */
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to send L2CAP disconnect request peer:{} lcid:0x{:04x}", bd_addr, lcid);
    }
    log::info(
            "Ignoring remote connection request no link or no resources peer:{} lcid:0x{:04x} "
            "lcb_exists:{}",
            bd_addr, lcid, p_lcb != nullptr);
  }
}

static void avct_on_l2cap_error(uint16_t lcid, uint16_t result) {
  tAVCT_LCB* p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb == nullptr) {
    return;
  }
  if (p_lcb->ch_state == AVCT_CH_CONN) {
    log::verbose("avct_l2c_connect_cfm_cback conflict_lcid:0x{:x}", p_lcb->conflict_lcid);
    if (p_lcb->conflict_lcid == lcid) {
      p_lcb->conflict_lcid = 0;
    } else {
      tAVCT_LCB_EVT avct_lcb_evt;
      avct_lcb_evt.result = result;
      avct_lcb_event(p_lcb, AVCT_LCB_LL_CLOSE_EVT, &avct_lcb_evt);
    }
  } else if (p_lcb->ch_state == AVCT_CH_CFG) {
    log::verbose("ERROR avct_l2c_config_cfm_cback L2CA_DisconnectReq {}", p_lcb->ch_state);
    /* store result value */
    p_lcb->ch_result = result;

    /* Send L2CAP disconnect req */
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to send L2CAP disconnect request peer:{} lcid:0x{:04x}", p_lcb->peer_addr,
                lcid);
    }
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_connect_cfm_cback
 *
 * Description      This is the L2CAP connect confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result) {
  tAVCT_LCB* p_lcb;

  /* look up lcb for this channel */
  p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    log::verbose("lcid:0x{:04x} result:{} ch_state:{} conflict_lcid:0x{:04x}", lcid, result,
                 avct_ch_state_text(p_lcb->ch_state), p_lcb->conflict_lcid);
    /* if in correct state */
    if (p_lcb->ch_state == AVCT_CH_CONN) {
      /* if result successful */
      if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
        /* set channel state */
        p_lcb->ch_state = AVCT_CH_CFG;
      } else {
        /* else failure */
        log::error("invoked with non OK status lcid:0x{:04x} result:{}", lcid,
                   l2cap_result_code_text(result));
      }
    } else if (p_lcb->conflict_lcid == lcid) {
      /* we must be in AVCT_CH_CFG state for the ch_lcid channel */
      log::verbose("ch_state:{} conflict_lcid:0x{:04x}", avct_ch_state_text(p_lcb->ch_state),
                   p_lcb->conflict_lcid);
      if (result == tL2CAP_CONN::L2CAP_CONN_OK) {
        /* just in case the peer also accepts our connection - Send L2CAP
         * disconnect req */
        if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
          log::warn("Unable to send L2CAP disconnect request peer:{} cid:0x{:04x}",
                    p_lcb->peer_addr, lcid);
        }
      }
      p_lcb->conflict_lcid = 0;
    }
    log::verbose("ch_state:{}", avct_ch_state_text(p_lcb->ch_state));
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_config_cfm_cback
 *
 * Description      This is the L2CAP config confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_config_cfm_cback(uint16_t lcid, uint16_t is_initiator_local, tL2CAP_CFG_INFO* p_cfg) {
  avct_l2c_config_ind_cback(lcid, p_cfg);

  /* look up lcb for this channel */
  tAVCT_LCB* p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb == nullptr) {
    log::warn("Received config confirm for unknown peer lcid::0x{:04x} is_initiator_local:{}", lcid,
              is_initiator_local);
    return;
  }

  /* if in correct state */
  if (p_lcb->ch_state == AVCT_CH_CFG) {
    p_lcb->ch_state = AVCT_CH_OPEN;
    avct_lcb_event(p_lcb, AVCT_LCB_LL_OPEN_EVT, NULL);
  } else {
    log::warn(
            "Received config confirm in wrong state lcid:0x{:04x} ch_state:{} "
            "is_initiator_local:{}",
            lcid, avct_ch_state_text(p_lcb->ch_state), is_initiator_local);
  }
  log::verbose("ch_state lcid:0x{:04x} ch_state:{} is_initiator_local:{}", lcid,
               avct_ch_state_text(p_lcb->ch_state), is_initiator_local);
}

/*******************************************************************************
 *
 * Function         avct_l2c_config_ind_cback
 *
 * Description      This is the L2CAP config indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg) {
  tAVCT_LCB* p_lcb;

  /* look up lcb for this channel */
  p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    log::verbose("avct_l2c_config_ind_cback: 0x{:04x}, ch_state:{}", lcid,
                 avct_ch_state_text(p_lcb->ch_state));
    /* store the mtu in tbl */
    if (p_cfg->mtu_present) {
      p_lcb->peer_mtu = p_cfg->mtu;
    } else {
      p_lcb->peer_mtu = L2CAP_DEFAULT_MTU;
    }
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_disconnect_ind_cback
 *
 * Description      This is the L2CAP disconnect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_disconnect_ind_cback(uint16_t lcid, bool /* ack_needed */) {
  tAVCT_LCB* p_lcb;
  uint16_t result = AVCT_RESULT_FAIL;

  /* look up lcb for this channel */
  p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    log::verbose("avct_l2c_disconnect_ind_cback: 0x{:x}, ch_state: {}", lcid, p_lcb->ch_state);
    tAVCT_LCB_EVT avct_lcb_evt;
    avct_lcb_evt.result = result;
    avct_lcb_event(p_lcb, AVCT_LCB_LL_CLOSE_EVT, &avct_lcb_evt);
    log::verbose("ch_state di: {}", p_lcb->ch_state);
  }
}

void avct_l2c_disconnect(uint16_t lcid, uint16_t result) {
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
    log::warn("Unable to send L2CAP disconnect request cid:{}", lcid);
  }

  tAVCT_LCB* p_lcb;
  uint16_t res;

  /* look up lcb for this channel */
  p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    log::verbose("avct_l2c_disconnect_cfm_cback: 0x{:x}, ch_state: {}, res: {}", lcid,
                 p_lcb->ch_state, result);
    /* result value may be previously stored */
    res = (p_lcb->ch_result != 0) ? p_lcb->ch_result : result;
    p_lcb->ch_result = 0;

    tAVCT_LCB_EVT avct_lcb_evt;
    avct_lcb_evt.result = res;
    avct_lcb_event(p_lcb, AVCT_LCB_LL_CLOSE_EVT, &avct_lcb_evt);
    log::verbose("ch_state:{}", p_lcb->ch_state);
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_congestion_ind_cback
 *
 * Description      This is the L2CAP congestion indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_congestion_ind_cback(uint16_t lcid, bool is_congested) {
  tAVCT_LCB* p_lcb;

  log::verbose("avct_l2c_congestion_ind_cback: 0x{:x}", lcid);
  /* look up lcb for this channel */
  p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    tAVCT_LCB_EVT avct_lcb_evt;
    avct_lcb_evt.cong = is_congested;
    avct_lcb_event(p_lcb, AVCT_LCB_LL_CONG_EVT, &avct_lcb_evt);
  }
}

/*******************************************************************************
 *
 * Function         avct_l2c_data_ind_cback
 *
 * Description      This is the L2CAP data indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void avct_l2c_data_ind_cback(uint16_t lcid, BT_HDR* p_buf) {
  log::verbose("lcid: 0x{:02x}", lcid);
  /* look up lcb for this channel */
  tAVCT_LCB* p_lcb = avct_lcb_by_lcid(lcid);
  if (p_lcb != NULL) {
    avct_lcb_event(p_lcb, AVCT_LCB_LL_MSG_EVT, (tAVCT_LCB_EVT*)&p_buf);
  } else {
    /* prevent buffer leak */
    log::warn("ERROR -> avct_l2c_data_ind_cback drop buffer");
    osi_free(p_buf);
  }
}
