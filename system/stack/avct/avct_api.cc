/******************************************************************************
 *
 *  Copyright 2003-2016 Broadcom Corporation
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
 *  This module contains API of the audio/video control transport protocol.
 *
 ******************************************************************************/

#include "stack/include/avct_api.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <string.h>

#include <cstdint>

#include "bta/include/bta_sec_api.h"
#include "internal_include/bt_target.h"
#include "main/shim/dumpsys.h"
#include "osi/include/allocator.h"
#include "osi/include/fixed_queue.h"
#include "stack/avct/avct_int.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cap_types.h"
#include "stack/include/l2cdefs.h"
#include "stack/l2cap/l2c_int.h"

using namespace bluetooth;

/* Control block for AVCT */
tAVCT_CB avct_cb;

/*******************************************************************************
 *
 * Function         AVCT_Register
 *
 * Description      This is the system level registration function for the
 *                  AVCTP protocol.  This function initializes AVCTP and
 *                  prepares the protocol stack for its use.  This function
 *                  must be called once by the system or platform using AVCTP
 *                  before the other functions of the API an be used.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void AVCT_Register() {
  log::verbose("AVCT_Register");

  /* initialize AVCTP data structures */
  memset(&avct_cb, 0, sizeof(tAVCT_CB));

  uint16_t sec = BTA_SEC_AUTHENTICATE | BTA_SEC_ENCRYPT;

  /* register PSM with L2CAP */
  if (!stack::l2cap::get_interface().L2CA_RegisterWithSecurity(
              BT_PSM_AVCTP, avct_l2c_appl, true /* enable_snoop */, nullptr, kAvrcMtu, 0, sec)) {
    log::error("Unable to register with L2CAP AVCT profile psm:{}", bt_psm_text(BT_PSM_AVCTP));
  }

  /* Include the browsing channel which uses eFCR */
  tL2CAP_ERTM_INFO ertm_info = {
          .preferred_mode = L2CAP_FCR_ERTM_MODE,
  };

  if (!stack::l2cap::get_interface().L2CA_RegisterWithSecurity(
              BT_PSM_AVCTP_BROWSE, avct_l2c_br_appl, true /*enable_snoop*/, &ertm_info, kAvrcBrMtu,
              AVCT_MIN_BROWSE_MTU, sec)) {
    log::error("Unable to register with L2CAP AVCT_BROWSE profile psm:{}",
               bt_psm_text(BT_PSM_AVCTP_BROWSE));
  }
}

/*******************************************************************************
 *
 * Function         AVCT_Deregister
 *
 * Description      This function is called to deregister use AVCTP protocol.
 *                  It is called when AVCTP is no longer being used by any
 *                  application in the system.  Before this function can be
 *                  called, all connections must be removed with
 *                  AVCT_RemoveConn().
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void AVCT_Deregister(void) {
  log::verbose("AVCT_Deregister");

  /* deregister PSM with L2CAP */
  stack::l2cap::get_interface().L2CA_Deregister(BT_PSM_AVCTP);

  /* deregister AVCT_BR_PSM with L2CAP */
  stack::l2cap::get_interface().L2CA_Deregister(BT_PSM_AVCTP_BROWSE);

  // Clean up AVCTP data structures
  for (int i = 0; i < AVCT_NUM_LINKS; i++) {
    osi_free_and_reset((void**)&(avct_cb.lcb[i].p_rx_msg));
    fixed_queue_free(avct_cb.lcb[i].tx_q, nullptr);
    avct_cb.lcb[i].tx_q = nullptr;
    osi_free_and_reset((void**)&(avct_cb.bcb[i].p_tx_msg));
  }
}

/*******************************************************************************
 *
 * Function         AVCT_CreateConn
 *
 * Description      Create an AVCTP connection.  There are two types of
 *                  connections, initiator and acceptor, as determined by
 *                  the p_cc->role parameter.  When this function is called to
 *                  create an initiator connection, an AVCTP connection to
 *                  the peer device is initiated if one does not already exist.
 *                  If an acceptor connection is created, the connection waits
 *                  passively for an incoming AVCTP connection from a peer
 *                  device.
 *
 *
 * Returns          AVCT_SUCCESS if successful, otherwise error.
 *
 ******************************************************************************/
uint16_t AVCT_CreateConn(uint8_t* p_handle, tAVCT_CC* p_cc, const RawAddress& peer_addr) {
  if (p_handle == nullptr || p_cc == nullptr) {
    return AVCT_RESULT_FAIL;
  }

  log::verbose("AVCT_CreateConn:{}, control:0x{:x} peer:{}", avct_role_text(p_cc->role),
               p_cc->control, peer_addr);

  /* Allocate ccb; if no ccbs, return failure */
  tAVCT_CCB* p_ccb = avct_ccb_alloc(p_cc);
  if (p_ccb == nullptr) {
    return AVCT_NO_RESOURCES;
  }

  /* get handle */
  *p_handle = avct_ccb_to_idx(p_ccb);

  /* if acceptor connection, we are done */
  if (p_cc->role != AVCT_ROLE_INITIATOR) {
    return AVCT_SUCCESS;
  }

  /* find link; if none allocate a new one */
  tAVCT_LCB* p_lcb = avct_lcb_by_bd(peer_addr);
  if (p_lcb == nullptr) {
    p_lcb = avct_lcb_alloc(peer_addr);
    if (p_lcb == nullptr) {
      /* no link resources; free ccb as well */
      avct_ccb_dealloc(p_ccb, AVCT_NO_EVT, 0, nullptr);
      return AVCT_NO_RESOURCES;
    }
  } else if (avct_lcb_has_pid(p_lcb, p_cc->pid)) {
    /* check if PID already in use */
    avct_ccb_dealloc(p_ccb, AVCT_NO_EVT, 0, nullptr);
    return AVCT_PID_IN_USE;
  }

  /* bind lcb to ccb */
  p_ccb->p_lcb = p_lcb;
  log::verbose("ch_state:{}", avct_ch_state_text(p_lcb->ch_state));
  tAVCT_LCB_EVT avct_lcb_evt = {
          .p_ccb = p_ccb,
  };
  avct_lcb_event(p_lcb, AVCT_LCB_UL_BIND_EVT, &avct_lcb_evt);

  return AVCT_SUCCESS;
}

/*******************************************************************************
 *
 * Function         AVCT_RemoveConn
 *
 * Description      Remove an AVCTP connection.  This function is called when
 *                  the application is no longer using a connection.  If this
 *                  is the last connection to a peer the L2CAP channel for AVCTP
 *                  will be closed.
 *
 *
 * Returns          AVCT_SUCCESS if successful, otherwise error.
 *
 ******************************************************************************/
uint16_t AVCT_RemoveConn(uint8_t handle) {
  log::verbose("AVCT_RemoveConn");

  /* map handle to ccb */
  tAVCT_CCB* p_ccb = avct_ccb_by_idx(handle);
  if (p_ccb == nullptr) {
    return AVCT_BAD_HANDLE;
  }

  /* if connection not bound to lcb, dealloc */
  if (p_ccb->p_lcb == nullptr) {
    avct_ccb_dealloc(p_ccb, AVCT_NO_EVT, 0, NULL);
  } else {
    /* send unbind event to lcb */
    tAVCT_LCB_EVT avct_lcb_evt = {
            .p_ccb = p_ccb,
    };
    avct_lcb_event(p_ccb->p_lcb, AVCT_LCB_UL_UNBIND_EVT, &avct_lcb_evt);
  }
  return AVCT_SUCCESS;
}

/*******************************************************************************
 *
 * Function         AVCT_CreateBrowse
 *
 * Description      Create an AVCTP Browse channel.  There are two types of
 *                  connections, initiator and acceptor, as determined by
 *                  the role parameter.  When this function is called to
 *                  create an initiator connection, the Browse channel to
 *                  the peer device is initiated if one does not already exist.
 *                  If an acceptor connection is created, the connection waits
 *                  passively for an incoming AVCTP connection from a peer
 *                  device.
 *
 *
 * Returns          AVCT_SUCCESS if successful, otherwise error.
 *
 ******************************************************************************/
uint16_t AVCT_CreateBrowse(uint8_t handle, tAVCT_ROLE role) {
  uint16_t result = AVCT_SUCCESS;
  tAVCT_CCB* p_ccb;
  tAVCT_BCB* p_bcb;
  tL2C_LCB* p_l2c_lcb;
  int index;

  log::verbose("AVCT_CreateBrowse: role:{}", avct_role_text(role));

  /* map handle to ccb */
  p_ccb = avct_ccb_by_idx(handle);
  if (p_ccb == NULL) {
    return AVCT_BAD_HANDLE;
  } else {
    /* mark this CCB as supporting browsing channel */
    if ((p_ccb->allocated & AVCT_ALOC_BCB) == 0) {
      p_ccb->allocated |= AVCT_ALOC_BCB;
    }
  }

  /* if initiator connection */
  if (role == AVCT_ROLE_INITIATOR) {
    /* the link control block must exist before this function is called as INT.
     */

    if (p_ccb->p_lcb == NULL) {
      return AVCT_NOT_OPEN;
    }
    p_l2c_lcb = l2cu_find_lcb_by_bd_addr(p_ccb->p_lcb->peer_addr, BT_TRANSPORT_BR_EDR);
    if (p_l2c_lcb == NULL) {
      log::warn("AVCT_CreateBrowse: l2cap lcb for peer does not exist");
      return AVCT_BAD_HANDLE;
    } else if (!(p_l2c_lcb->peer_ext_fea & L2CAP_EXTFEA_ENH_RETRANS)) {
      log::warn("AVCT_CreateBrowse: peer did not support ENH_RETRANS");
      return AVCT_BAD_HANDLE;
    }
    if (p_ccb->p_lcb->allocated == 0) {
      result = AVCT_NOT_OPEN;
    } else {
      /* find link; if none allocate a new one */
      index = p_ccb->p_lcb->allocated;
      if (index > AVCT_NUM_LINKS) {
        result = AVCT_BAD_HANDLE;
      } else {
        p_bcb = &avct_cb.bcb[index - 1];
        p_bcb->allocated = index;
      }
    }

    if (result == AVCT_SUCCESS) {
      /* bind bcb to ccb */
      p_ccb->p_bcb = p_bcb;
      p_bcb->peer_addr = p_ccb->p_lcb->peer_addr;
      log::verbose("Created BCB ch_state:{}", avct_ch_state_text(p_bcb->ch_state));
      tAVCT_LCB_EVT avct_lcb_evt;
      avct_lcb_evt.p_ccb = p_ccb;
      avct_bcb_event(p_bcb, AVCT_LCB_UL_BIND_EVT, &avct_lcb_evt);
    }
  }

  return result;
}

/*******************************************************************************
 *
 * Function         AVCT_RemoveBrowse
 *
 * Description      Remove an AVCTP Browse channel.  This function is called
 *                  when the application is no longer using a connection.  If
 *                  this is the last connection to a peer the L2CAP channel for
 *                  AVCTP will be closed.
 *
 *
 * Returns          AVCT_SUCCESS if successful, otherwise error.
 *
 ******************************************************************************/
uint16_t AVCT_RemoveBrowse(uint8_t handle) {
  log::verbose("AVCT_RemoveBrowse");

  /* map handle to ccb */
  tAVCT_CCB* p_ccb = avct_ccb_by_idx(handle);
  if (p_ccb == nullptr) {
    return AVCT_BAD_HANDLE;
  }

  if (p_ccb->p_bcb != nullptr) {
    /* send unbind event to bcb */
    tAVCT_LCB_EVT avct_lcb_evt = {
            .p_ccb = p_ccb,
    };
    avct_bcb_event(p_ccb->p_bcb, AVCT_LCB_UL_UNBIND_EVT, &avct_lcb_evt);
  }
  return AVCT_SUCCESS;
}

/*******************************************************************************
 *
 * Function         AVCT_GetBrowseMtu
 *
 * Description      Get the peer_mtu for the AVCTP Browse channel of the given
 *                  connection.
 *
 * Returns          the peer browsing channel MTU.
 *
 ******************************************************************************/
uint16_t AVCT_GetBrowseMtu(uint8_t handle) {
  uint16_t peer_mtu = AVCT_MIN_BROWSE_MTU;

  tAVCT_CCB* p_ccb = avct_ccb_by_idx(handle);

  if (p_ccb != NULL && p_ccb->p_bcb != NULL) {
    peer_mtu = p_ccb->p_bcb->peer_mtu;
  }

  return peer_mtu;
}

/*******************************************************************************
 *
 * Function         AVCT_GetPeerMtu
 *
 * Description      Get the peer_mtu for the AVCTP channel of the given
 *                  connection.
 *
 * Returns          the peer MTU size.
 *
 ******************************************************************************/
uint16_t AVCT_GetPeerMtu(uint8_t handle) {
  uint16_t peer_mtu = L2CAP_DEFAULT_MTU;
  tAVCT_CCB* p_ccb;

  /* map handle to ccb */
  p_ccb = avct_ccb_by_idx(handle);
  if (p_ccb != NULL) {
    if (p_ccb->p_lcb) {
      peer_mtu = p_ccb->p_lcb->peer_mtu;
    }
  }

  return peer_mtu;
}

/*******************************************************************************
 *
 * Function         AVCT_MsgReq
 *
 * Description      Send an AVCTP message to a peer device.  In calling
 *                  AVCT_MsgReq(), the application should keep track of the
 *                  congestion state of AVCTP as communicated with events
 *                  AVCT_CONG_IND_EVT and AVCT_UNCONG_IND_EVT.   If the
 *                  application calls AVCT_MsgReq() when AVCTP is congested
 *                  the message may be discarded.  The application may make its
 *                  first call to AVCT_MsgReq() after it receives an
 *                  AVCT_CONNECT_CFM_EVT or AVCT_CONNECT_IND_EVT on control
 *                  channel or AVCT_BROWSE_CONN_CFM_EVT or
 *                  AVCT_BROWSE_CONN_IND_EVT on browsing channel.
 *
 *                  p_msg->layer_specific must be set to
 *                  AVCT_DATA_CTRL for control channel traffic;
 *                  AVCT_DATA_BROWSE for for browse channel traffic.
 *
 * Returns          AVCT_SUCCESS if successful, otherwise error.
 *
 ******************************************************************************/
uint16_t AVCT_MsgReq(uint8_t handle, uint8_t label, uint8_t cr, BT_HDR* p_msg) {
  uint16_t result = AVCT_SUCCESS;
  tAVCT_CCB* p_ccb;
  tAVCT_UL_MSG ul_msg;

  log::verbose("");

  /* verify p_msg parameter */
  if (p_msg == NULL) {
    return AVCT_NO_RESOURCES;
  }
  log::verbose("msg_len:{} msg_layer_specific:{}", p_msg->len, p_msg->layer_specific);

  /* map handle to ccb */
  p_ccb = avct_ccb_by_idx(handle);
  if (p_ccb == NULL) {
    result = AVCT_BAD_HANDLE;
    osi_free(p_msg);
  } else if (p_ccb->p_lcb == NULL) {
    /* verify channel is bound to link */
    result = AVCT_NOT_OPEN;
    osi_free(p_msg);
  }

  if (result == AVCT_SUCCESS) {
    ul_msg.p_buf = p_msg;
    ul_msg.p_ccb = p_ccb;
    ul_msg.label = label;
    ul_msg.cr = cr;

    /* send msg event to bcb */
    if (p_msg->layer_specific == AVCT_DATA_BROWSE) {
      if (p_ccb->p_bcb == NULL && (p_ccb->allocated & AVCT_ALOC_BCB) == 0) {
        /* BCB channel is not open and not allocated */
        result = AVCT_BAD_HANDLE;
        osi_free(p_msg);
      } else {
        p_ccb->p_bcb = avct_bcb_by_lcb(p_ccb->p_lcb);
        tAVCT_LCB_EVT avct_lcb_evt;
        avct_lcb_evt.ul_msg = ul_msg;
        avct_bcb_event(p_ccb->p_bcb, AVCT_LCB_UL_MSG_EVT, &avct_lcb_evt);
      }
    } else {
      /* send msg event to lcb */
      tAVCT_LCB_EVT avct_lcb_evt;
      avct_lcb_evt.ul_msg = ul_msg;
      avct_lcb_event(p_ccb->p_lcb, AVCT_LCB_UL_MSG_EVT, &avct_lcb_evt);
    }
  }
  return result;
}

#define DUMPSYS_TAG "stack::avct"

void AVCT_Dumpsys(int fd) {
  LOG_DUMPSYS_TITLE(fd, DUMPSYS_TAG);
  for (int i = 0; i < AVCT_NUM_CONN; i++) {
    const tAVCT_CCB& ccb = avct_cb.ccb[i];
    if (!ccb.allocated) {
      continue;
    }
    LOG_DUMPSYS(fd, " Id:%2u profile_uuid:0x%04x role:%s control:0x%2x", i, ccb.cc.pid,
                avct_role_text(ccb.cc.role).c_str(), ccb.cc.control);
    if (ccb.p_lcb) {  // tAVCT_LCB
      LOG_DUMPSYS(fd,
                  "  Link  : peer:%s lcid:0x%04x sm_state:%-24s ch_state:%s conflict_lcid:0x%04x",
                  std::format("{}", ccb.p_lcb->peer_addr).c_str(), ccb.p_lcb->ch_lcid,
                  avct_sm_state_text(ccb.p_lcb->state).c_str(),
                  avct_ch_state_text(ccb.p_lcb->ch_state).c_str(), ccb.p_lcb->conflict_lcid);
    } else {
      LOG_DUMPSYS(fd, "  Link  : No link channel");
    }

    if (ccb.p_bcb) {  // tAVCT_BCB
      LOG_DUMPSYS(fd,
                  "  Browse: peer:%s lcid:0x%04x sm_state:%-24s ch_state:%s conflict_lcid:0x%04x",
                  std::format("{}", ccb.p_bcb->peer_addr).c_str(), ccb.p_bcb->ch_lcid,
                  avct_sm_state_text(ccb.p_bcb->state).c_str(),
                  avct_ch_state_text(ccb.p_bcb->ch_state).c_str(), ccb.p_bcb->conflict_lcid);
    } else {
      LOG_DUMPSYS(fd, "  Browse: No browse channel");
    }
  }
}
#undef DUMPSYS_TAG
