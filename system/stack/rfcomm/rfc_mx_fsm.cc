/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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
 *  This file contains state machine and action routines for multiplexer
 *  channel of the RFCOMM unit
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "common/time_util.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"
#include "stack/rfcomm/port_int.h"
#include "stack/rfcomm/rfc_int.h"

#define L2CAP_SUCCESS 0
#define L2CAP_ERROR 1

using namespace bluetooth;

/******************************************************************************/
/*            L O C A L    F U N C T I O N     P R O T O T Y P E S            */
/******************************************************************************/
static void rfc_mx_sm_state_idle(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_state_wait_conn_cnf(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_state_configure(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_sabme_wait_ua(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_state_wait_sabme(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_state_connected(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);
static void rfc_mx_sm_state_disc_wait_ua(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data);

static void rfc_mx_conf_ind(tRFC_MCB* p_mcb, tL2CAP_CFG_INFO* p_cfg);
static void rfc_mx_conf_cnf(tRFC_MCB* p_mcb, uint16_t result);

static void rfc_mx_retry_with_cached_lcid(tRFC_MCB* p_mcb);
static void rfc_mx_swap_directions(tRFC_MCB* p_mcb);
static void rfc_mx_handle_invalid_collision(tRFC_MCB* p_mcb);

/*******************************************************************************
 *
 * Function         rfc_mx_sm_execute
 *
 * Description      This function sends multiplexer events through the state
 *                  machine.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_execute(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data) {
  log::assert_that(p_mcb != nullptr, "NULL mcb for event {}", event);

  log::info("RFCOMM peer:{} event:{} state:{}", p_mcb->bd_addr, rfcomm_mx_event_text(event),
            rfcomm_mx_state_text(p_mcb->state));

  switch (p_mcb->state) {
    case RFC_MX_STATE_IDLE:
      rfc_mx_sm_state_idle(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_WAIT_CONN_CNF:
      rfc_mx_sm_state_wait_conn_cnf(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_CONFIGURE:
      rfc_mx_sm_state_configure(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_SABME_WAIT_UA:
      rfc_mx_sm_sabme_wait_ua(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_WAIT_SABME:
      rfc_mx_sm_state_wait_sabme(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_CONNECTED:
      rfc_mx_sm_state_connected(p_mcb, event, p_data);
      break;

    case RFC_MX_STATE_DISC_WAIT_UA:
      rfc_mx_sm_state_disc_wait_ua(p_mcb, event, p_data);
      break;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_idle
 *
 * Description      This function handles events when the multiplexer is in
 *                  IDLE state. This state exists when connection is being
 *                  initially established.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_idle(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* /* p_data */) {
  switch (event) {
    case RFC_MX_EVENT_START_REQ: {
      /* Initialize L2CAP MTU */
      p_mcb->peer_l2cap_mtu = L2CAP_DEFAULT_MTU - RFCOMM_MIN_OFFSET - 1;

      uint16_t lcid = stack::l2cap::get_interface().L2CA_ConnectReq(BT_PSM_RFCOMM, p_mcb->bd_addr);
      if (lcid == 0) {
        log::error("failed to open L2CAP channel for {}", p_mcb->bd_addr);
        bluetooth::metrics::LogRfcommMxEvent(
                p_mcb->bd_addr, bluetooth::metrics::State::L2CAP_CONNECT_REQUEST_FAILED);
        rfc_save_lcid_mcb(nullptr, p_mcb->lcid);
        p_mcb->lcid = 0;
        PORT_StartCnf(p_mcb, RFCOMM_ERROR);
        return;
      }
      p_mcb->lcid = lcid;
      /* Save entry for quicker access to mcb based on the LCID */
      rfc_save_lcid_mcb(p_mcb, p_mcb->lcid);

      p_mcb->state = RFC_MX_STATE_WAIT_CONN_CNF;
      return;
    }

    case RFC_MX_EVENT_CONN_IND:

      rfc_timer_start(p_mcb, RFCOMM_CONN_TIMEOUT);

      p_mcb->state = RFC_MX_STATE_CONFIGURE;
      return;

    case RFC_MX_EVENT_SABME:
      break;

    case RFC_MX_EVENT_UA:
    case RFC_MX_EVENT_DM:
      return;

    case RFC_MX_EVENT_DISC:
      rfc_send_dm(p_mcb, RFCOMM_MX_DLCI, true);
      return;

    case RFC_MX_EVENT_UIH:
      rfc_send_dm(p_mcb, RFCOMM_MX_DLCI, false);
      return;

    case RFC_MX_EVENT_COLLISION: {
      if (p_mcb->collision_outgoing_lcid == 0) {
        log::error("Cannot collide with an open port.");
        return;
      }

      /* if we're here, we reset the state machine after detecting a collision */
      /* start random timer between 4-14 seconds in case both devices collide */
      auto collision_timeout = (uint16_t)(bluetooth::common::time_get_os_boottime_ms() % 10 + 4);
      log::info("start timer for collision: timeout in {} seconds", collision_timeout);
      rfc_timer_start(p_mcb, collision_timeout);
      p_mcb->state = RFC_MX_STATE_CONFIGURE;
      return;
    }

    default:
      log::error("Mx error state {} event {}", rfcomm_mx_state_text(p_mcb->state),
                 rfcomm_mx_event_text(event));
      return;
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_wait_conn_cnf
 *
 * Description      This function handles events when the multiplexer is
 *                  waiting for Connection Confirm from L2CAP.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_wait_conn_cnf(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data) {
  switch (event) {
    case RFC_MX_EVENT_START_REQ:
      log::error("Mx error state:{} event:{}", rfcomm_mx_state_text(p_mcb->state),
                 rfcomm_mx_event_text(event));
      return;

    /* There is some new timing so that Config Ind comes before security is
       completed
       so we are still waiting fo the confirmation. */
    case RFC_MX_EVENT_CONF_IND:
      rfc_mx_conf_ind(p_mcb, (tL2CAP_CFG_INFO*)p_data);
      return;

    case RFC_MX_EVENT_CONN_CNF:
      if (*((uint16_t*)p_data) != L2CAP_SUCCESS) {
        p_mcb->state = RFC_MX_STATE_IDLE;

        PORT_StartCnf(p_mcb, *((uint16_t*)p_data));
        return;
      }
      p_mcb->state = RFC_MX_STATE_CONFIGURE;
      return;

    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_TIMEOUT:
      bluetooth::metrics::LogRfcommMxEvent(
              p_mcb->bd_addr, bluetooth::metrics::State::RFCOMM_MX_WAIT_CONN_CNF_TIMEOUT);
      p_mcb->state = RFC_MX_STATE_IDLE;
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} cid:{}", p_mcb->bd_addr,
                  p_mcb->lcid);
      }
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_COLLISION:
      if (p_mcb->collision_outgoing_lcid == 0) {
        log::error("Collision event without a cached lcid!");
        break;
      }
      rfc_save_lcid_mcb(p_mcb, p_mcb->lcid);
      rfc_mx_swap_directions(p_mcb);
      /* reset state machine */
      p_mcb->state = RFC_MX_STATE_IDLE;
      rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_COLLISION, p_data);
      return;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_configure
 *
 * Description      This function handles events when the multiplexer in the
 *                  configuration state.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_configure(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data) {
  switch (event) {
    case RFC_MX_EVENT_START_REQ:
    case RFC_MX_EVENT_CONN_CNF:

      log::error("Mx error state {} event {}", rfcomm_mx_state_text(p_mcb->state),
                 rfcomm_mx_event_text(event));
      return;

    case RFC_MX_EVENT_CONF_IND:
      rfc_mx_conf_ind(p_mcb, (tL2CAP_CFG_INFO*)p_data);
      return;

    case RFC_MX_EVENT_CONF_CNF:
      rfc_mx_conf_cnf(p_mcb, (uintptr_t)p_data);
      return;

    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_TIMEOUT:
      log::error("L2CAP configuration timeout for {}", p_mcb->bd_addr);
      bluetooth::metrics::LogRfcommMxEvent(
              p_mcb->bd_addr, bluetooth::metrics::State::RFCOMM_MX_L2CAP_CONFIG_TIMEOUT);
      p_mcb->state = RFC_MX_STATE_IDLE;
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} cid:0x{:x}", p_mcb->bd_addr,
                  p_mcb->lcid);
      }

      PORT_StartCnf(p_mcb, RFCOMM_ERROR);

      if (p_mcb->collision_outgoing_lcid) {
        log::info("Collision case: Incoming conn timeout, restarting outgoing connection");
        rfc_mx_retry_with_cached_lcid(p_mcb);
      }
      return;

    case RFC_MX_EVENT_COLLISION:
      rfc_mx_handle_invalid_collision(p_mcb);
      return;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_sabme_wait_ua
 *
 * Description      This function handles events when the multiplexer sent
 *                  SABME and is waiting for UA reply.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_sabme_wait_ua(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* /* p_data */) {
  switch (event) {
    case RFC_MX_EVENT_START_REQ:
    case RFC_MX_EVENT_CONN_CNF:
      log::error("Mx error state {} event {}", p_mcb->state, event);
      return;

      /* workaround: we don't support reconfig */
      /* commented out until we support reconfig
      case RFC_MX_EVENT_CONF_IND:
          rfc_mx_conf_ind (p_mcb, (tL2CAP_CFG_INFO *)p_data);
          return;

      case RFC_MX_EVENT_CONF_CNF:
          rfc_mx_conf_cnf (p_mcb, (tL2CAP_CFG_INFO *)p_data);
          return;
      */

    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_UA:
      rfc_timer_stop(p_mcb);

      p_mcb->state = RFC_MX_STATE_CONNECTED;
      p_mcb->peer_ready = true;

      PORT_StartCnf(p_mcb, RFCOMM_SUCCESS);
      return;

    case RFC_MX_EVENT_DM:
      bluetooth::metrics::LogRfcommMxEvent(p_mcb->bd_addr,
                                           bluetooth::metrics::State::PEER_REJECTED);
      rfc_timer_stop(p_mcb);
      [[fallthrough]];

    case RFC_MX_EVENT_CONF_IND: /* workaround: we don't support reconfig */
    case RFC_MX_EVENT_CONF_CNF: /* workaround: we don't support reconfig */
    case RFC_MX_EVENT_TIMEOUT:
      bluetooth::metrics::LogRfcommMxEvent(
              p_mcb->bd_addr, bluetooth::metrics::State::RFCOMM_MX_SABME_WAIT_UA_TIMEOUT);
      p_mcb->state = RFC_MX_STATE_IDLE;
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} cid:{}", p_mcb->bd_addr,
                  p_mcb->lcid);
      }

      PORT_StartCnf(p_mcb, RFCOMM_ERROR);
      return;

    case RFC_MX_EVENT_COLLISION:
      rfc_mx_handle_invalid_collision(p_mcb);
      return;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_wait_sabme
 *
 * Description      This function handles events when the multiplexer is
 *                  waiting for SABME on the acceptor side after configuration
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_wait_sabme(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data) {
  switch (event) {
    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_SABME:
      rfc_timer_stop(p_mcb);
      PORT_StartInd(p_mcb);
      return;

    case RFC_MX_EVENT_START_RSP:
      if (*((uint16_t*)p_data) != RFCOMM_SUCCESS) {
        bluetooth::metrics::LogRfcommMxEvent(p_mcb->bd_addr,
                                             bluetooth::metrics::State::HOST_REJECTED);
        rfc_send_dm(p_mcb, RFCOMM_MX_DLCI, true);
      } else {
        rfc_send_ua(p_mcb, RFCOMM_MX_DLCI);

        p_mcb->state = RFC_MX_STATE_CONNECTED;
        p_mcb->peer_ready = true;
        // If this was a collision case, cached lcid no longer needed
        p_mcb->collision_outgoing_lcid = 0;
        p_mcb->collision_outgoing_conn_cnf = false;
        p_mcb->collision_outgoing_cfg_complete = false;
        p_mcb->collision_cfg_info = {};
        PORT_StartCnf(p_mcb, RFCOMM_SUCCESS);
      }
      return;

    case RFC_MX_EVENT_CONF_IND: /* workaround: we don't support reconfig */
    case RFC_MX_EVENT_CONF_CNF: /* workaround: we don't support reconfig */
    case RFC_MX_EVENT_TIMEOUT:
      bluetooth::metrics::LogRfcommMxEvent(p_mcb->bd_addr,
                                           bluetooth::metrics::State::RFCOMM_MX_WAIT_SABME_TIMEOUT);
      p_mcb->state = RFC_MX_STATE_IDLE;

      if (p_mcb->collision_outgoing_lcid) {
        log::info("Collision case: Incoming conn timeout, restarting outgoing connection");
        rfc_mx_retry_with_cached_lcid(p_mcb);
      } else {
        if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
          log::warn("Unable to send L2CAP disconnect request peer:{} cid:0x{:x}", p_mcb->bd_addr,
                    p_mcb->lcid);
        }
        PORT_CloseInd(p_mcb);
      }
      return;

    case RFC_MX_EVENT_COLLISION:
      rfc_mx_handle_invalid_collision(p_mcb);
      return;

    default:
      log::warn("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_connected
 *
 * Description      This function handles events when the multiplexer is
 *                  in the CONNECTED state
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_connected(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* /* p_data */) {
  switch (event) {
    case RFC_MX_EVENT_TIMEOUT:
    case RFC_MX_EVENT_CLOSE_REQ:
      rfc_timer_start(p_mcb, RFC_DISC_TIMEOUT);
      p_mcb->state = RFC_MX_STATE_DISC_WAIT_UA;
      rfc_send_disc(p_mcb, RFCOMM_MX_DLCI);
      return;

    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_DISC:
      /* Reply with UA.  If initiator bring down L2CAP connection */
      /* If server wait for some time if client decide to reinitiate channel */
      rfc_send_ua(p_mcb, RFCOMM_MX_DLCI);
      if (p_mcb->is_initiator) {
        if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
          log::warn("Unable to send L2CAP disconnect request peer:{} cid:0x{:x}", p_mcb->bd_addr,
                    p_mcb->lcid);
        }
      }
      /* notify all ports that connection is gone */
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_COLLISION:
      rfc_mx_handle_invalid_collision(p_mcb);
      return;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

/*******************************************************************************
 *
 * Function         rfc_mx_sm_state_disc_wait_ua
 *
 * Description      This function handles events when the multiplexer sent
 *                  DISC and is waiting for UA reply.
 *
 * Returns          void
 *
 ******************************************************************************/
void rfc_mx_sm_state_disc_wait_ua(tRFC_MCB* p_mcb, RfcommMuxEvent event, void* p_data) {
  BT_HDR* p_buf;
  switch (event) {
    case RFC_MX_EVENT_UA:
    case RFC_MX_EVENT_DM:
    case RFC_MX_EVENT_TIMEOUT:
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} cid:{}", p_mcb->bd_addr,
                  p_mcb->lcid);
      }

      if (p_mcb->restart_required) {
        /* Start Request was received while disconnecting.  Execute it again */
        uint16_t lcid =
                stack::l2cap::get_interface().L2CA_ConnectReq(BT_PSM_RFCOMM, p_mcb->bd_addr);
        if (lcid == 0) {
          bluetooth::metrics::LogRfcommMxEvent(
                  p_mcb->bd_addr, bluetooth::metrics::State::L2CAP_CONNECT_REQUEST_FAILED);
          rfc_save_lcid_mcb(nullptr, p_mcb->lcid);
          p_mcb->lcid = 0;
          PORT_StartCnf(p_mcb, RFCOMM_ERROR);
          return;
        }
        p_mcb->lcid = lcid;
        /* Save entry for quicker access to mcb based on the LCID */
        rfc_save_lcid_mcb(p_mcb, p_mcb->lcid);

        /* clean up before reuse it */
        while ((p_buf = (BT_HDR*)fixed_queue_try_dequeue(p_mcb->cmd_q)) != nullptr) {
          osi_free(p_buf);
        }

        rfc_timer_start(p_mcb, RFC_MCB_INIT_INACT_TIMER);

        p_mcb->is_initiator = true;
        p_mcb->restart_required = false;

        p_mcb->state = RFC_MX_STATE_WAIT_CONN_CNF;
        return;
      }
      rfc_release_multiplexer_channel(p_mcb);
      return;

    case RFC_MX_EVENT_DISC:
      rfc_send_ua(p_mcb, RFCOMM_MX_DLCI);
      return;

    case RFC_MX_EVENT_UIH:
      osi_free(p_data);
      rfc_send_dm(p_mcb, RFCOMM_MX_DLCI, false);
      return;

    case RFC_MX_EVENT_START_REQ:
      p_mcb->restart_required = true;
      return;

    case RFC_MX_EVENT_DISC_IND:
      p_mcb->state = RFC_MX_STATE_IDLE;
      PORT_CloseInd(p_mcb);
      return;

    case RFC_MX_EVENT_CLOSE_REQ:
      return;

    case RFC_MX_EVENT_QOS_VIOLATION_IND:
      break;

    case RFC_MX_EVENT_COLLISION:
      rfc_mx_handle_invalid_collision(p_mcb);
      return;

    default:
      log::error("Received unexpected event:{} in state:{}", rfcomm_mx_event_text(event),
                 rfcomm_mx_state_text(p_mcb->state));
  }
  log::verbose("RFCOMM MX ignored - evt:{} in state:{}", rfcomm_mx_event_text(event),
               rfcomm_mx_state_text(p_mcb->state));
}

void rfc_on_l2cap_error(uint16_t lcid, uint16_t result) {
  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);
  if (p_mcb == nullptr) {
    for (auto& [cid, mcb] : rfc_lcid_mcb) {
      if (mcb != nullptr && mcb->collision_outgoing_lcid == lcid) {
        // outgoing connection failed - clear cache (and continue with incoming connection)
        bluetooth::metrics::LogRfcommMxEvent(
                mcb->bd_addr, bluetooth::metrics::State::COLLISION_RETRY_AS_ACCEPTOR);
        mcb->collision_outgoing_lcid = 0;
        mcb->collision_outgoing_conn_cnf = false;
        mcb->collision_outgoing_cfg_complete = false;
        mcb->collision_cfg_info = {};
        return;
      }
    }
    return;
  }

  bluetooth::metrics::LogRfcommL2capEvent(p_mcb->bd_addr,
                                          bluetooth::metrics::EventType::RFCOMM_ON_L2CAP_ERROR,
                                          to_l2cap_result_code(result));

  if (static_cast<uint16_t>(result) & L2CAP_CONN_INTERNAL_MASK) {
    p_mcb->lcid = lcid;
    rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_CONN_CNF, &result);
  } else if (result == static_cast<uint16_t>(tL2CAP_CFG_RESULT::L2CAP_CFG_FAILED_NO_REASON)) {
    log::error("failed to configure L2CAP for {}", p_mcb->bd_addr);
    if (p_mcb->is_initiator) {
      log::error("disconnect L2CAP due to config failure for {}", p_mcb->bd_addr);
      PORT_StartCnf(p_mcb, static_cast<uint16_t>(result));
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
        log::warn("Unable to send L2CAP disconnect request peer:{} cid:0x{:x}", p_mcb->bd_addr,
                  p_mcb->lcid);
      }
    }
    rfc_release_multiplexer_channel(p_mcb);
  }
}

/*******************************************************************************
 *
 * Function         rfc_mx_conf_cnf
 *
 * Description      This function handles L2CA_ConfigCnf message from the
 *                  L2CAP. If result is not success tell upper layer that
 *                  start has not been accepted.  If initiator send SABME
 *                  on DLCI 0.  T1 is still running.
 *
 ******************************************************************************/
static void rfc_mx_conf_cnf(tRFC_MCB* p_mcb, uint16_t /* result */) {
  if (p_mcb->state == RFC_MX_STATE_CONFIGURE) {
    if (p_mcb->is_initiator) {
      p_mcb->state = RFC_MX_STATE_SABME_WAIT_UA;
      rfc_send_sabme(p_mcb, RFCOMM_MX_DLCI);
      rfc_timer_start(p_mcb, RFC_T1_TIMEOUT);
    } else {
      p_mcb->state = RFC_MX_STATE_WAIT_SABME;
      /* increased from T2=20 to CONN=120 to allow user more than 10 sec to type in
       * the pin, which can be e.d. 16 digits */
      rfc_timer_start(p_mcb, RFCOMM_CONN_TIMEOUT);
    }
  }
}

/*******************************************************************************
 *
 * Function         rfc_mx_conf_ind
 *
 * Description      This function handles L2CA_ConfigInd message from the
 *                  L2CAP. Send the L2CA_ConfigRsp message.
 *
 ******************************************************************************/
static void rfc_mx_conf_ind(tRFC_MCB* p_mcb, tL2CAP_CFG_INFO* p_cfg) {
  /* Save peer L2CAP MTU if present */
  /* RFCOMM adds 3-4 bytes in the beginning and 1 bytes FCS */
  if (p_cfg->mtu_present) {
    p_mcb->peer_l2cap_mtu = p_cfg->mtu - RFCOMM_MIN_OFFSET - 1;
  } else {
    p_mcb->peer_l2cap_mtu = L2CAP_DEFAULT_MTU - RFCOMM_MIN_OFFSET - 1;
  }
}

/*******************************************************************************
 *
 * Function         rfc_mx_retry_with_cached_lcid
 *
 * Description      This function is called when an incoming connection failed
 *                  and there is a cached_lcid. Attempts to retry the connection
 *                  linked to the cached lcid
 *
 ******************************************************************************/
static void rfc_mx_retry_with_cached_lcid(tRFC_MCB* p_mcb) {
  /* clean up l2cap connection for failed lcid */
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
    log::warn("Unable to send L2CAP disconnect request peer:{} cid:{}", p_mcb->bd_addr,
              p_mcb->lcid);
  }

  bluetooth::metrics::LogRfcommMxEvent(p_mcb->bd_addr,
                                       bluetooth::metrics::State::COLLISION_RETRY_AS_INITIATOR);

  rfc_save_lcid_mcb(nullptr, p_mcb->lcid);
  p_mcb->lcid = p_mcb->collision_outgoing_lcid;
  /* store mcb into mapping table */
  rfc_save_lcid_mcb(p_mcb, p_mcb->lcid);
  p_mcb->collision_outgoing_lcid = 0;

  /* resume where we left off with this connection */
  p_mcb->state = RFC_MX_STATE_WAIT_CONN_CNF;
  /* update direction */
  rfc_mx_swap_directions(p_mcb);
  if (p_mcb->collision_outgoing_conn_cnf) {
    p_mcb->collision_outgoing_conn_cnf = false;
    p_mcb->state = RFC_MX_STATE_CONFIGURE;
  }
  if (p_mcb->collision_outgoing_cfg_complete) {
    p_mcb->collision_outgoing_cfg_complete = false;
    rfc_mx_conf_ind(p_mcb, &p_mcb->collision_cfg_info);
    rfc_mx_conf_cnf(p_mcb, static_cast<uint16_t>(tL2CAP_CONN::L2CAP_CONN_OK));
  }
}

/*******************************************************************************
 *
 * Function         rfc_mx_swap_directions
 *
 * Description      This function is called when the direction of the mux control
 *                  control block needs to change from initiator to acceptor of vis
 *                  versa.
 *
 ******************************************************************************/
static void rfc_mx_swap_directions(tRFC_MCB* p_mcb) {
  if (p_mcb->is_initiator) {
    /* mux is changing from initiator -> acceptor */
    for (uint16_t dlci = 0; dlci < RFCOMM_MAX_DLCI; dlci += 2) {
      uint8_t handle = p_mcb->port_handles[dlci];
      if (handle != 0) {
        p_mcb->port_handles[dlci] = 0;
        p_mcb->port_handles[dlci + 1] = handle;
        rfc_cb.port.port[handle - 1].dlci += 1;
        log::info("RFCOMM MUX - DLCI: {} -> {}", dlci, rfc_cb.port.port[handle - 1].dlci);
      }
    }
  } else {
    /* mux is changing from acceptor -> initiator */
    for (uint16_t dlci = 1; dlci <= RFCOMM_MAX_DLCI; dlci += 2) {
      uint8_t handle = p_mcb->port_handles[dlci];
      if (handle != 0) {
        p_mcb->port_handles[dlci] = 0;
        p_mcb->port_handles[dlci - 1] = handle;
        rfc_cb.port.port[handle - 1].dlci -= 1;
        log::info("RFCOMM MUX - DLCI: {} -> {}", dlci, rfc_cb.port.port[handle - 1].dlci);
      }
    }
  }
  p_mcb->is_initiator = !p_mcb->is_initiator;
}

/*******************************************************************************
 *
 * Function         rfc_mx_handle_invalid_collision
 *
 * Description      This function is called when the collision criteria is met
 *                  but the mux is not in a state to accept a connection.
 *
 ******************************************************************************/
static void rfc_mx_handle_invalid_collision(tRFC_MCB* p_mcb) {
  log::warn("we cannot accept connection request from peer at this state.  lcid:0x{:x}",
            p_mcb->lcid);
  /* don't update lcid - disconnect instead */
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(p_mcb->lcid)) {
    log::warn("Unable to disconnect L2CAP cid:0x{:x}", p_mcb->lcid);
  }

  /* set p_mcb to pre-collision values */
  p_mcb->lcid = p_mcb->collision_outgoing_lcid;
  p_mcb->collision_outgoing_lcid = 0;
}
