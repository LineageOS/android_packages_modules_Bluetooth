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
 *  This file contains L2CAP interface functions
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>

#include "hci/controller.h"
#include "internal_include/bt_target.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"
#include "stack/rfcomm/port_int.h"
#include "stack/rfcomm/rfc_int.h"

using namespace bluetooth;

/*
 * Define Callback functions to be called by L2CAP
 */
static void RFCOMM_ConnectInd(const RawAddress& bd_addr, uint16_t lcid, uint16_t psm, uint8_t id);
static void RFCOMM_ConnectCnf(uint16_t lcid, tL2CAP_CONN err);
static void RFCOMM_ConfigInd(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg);
static void RFCOMM_ConfigCnf(uint16_t lcid, uint16_t result, tL2CAP_CFG_INFO* p_cfg);
static void RFCOMM_DisconnectInd(uint16_t lcid, bool is_clear);
static void RFCOMM_BufDataInd(uint16_t lcid, BT_HDR* p_buf);
static void RFCOMM_CongestionStatusInd(uint16_t lcid, bool is_congested);

/*******************************************************************************
 *
 * Function         rfcomm_l2cap_if_init
 *
 * Description      This function is called during the RFCOMM task startup
 *                  to register interface functions with L2CAP.
 *
 ******************************************************************************/
void rfcomm_l2cap_if_init() {
  tL2CAP_APPL_INFO* p_l2c = &rfc_cb.rfc.reg_info;

  p_l2c->pL2CA_ConnectInd_Cb = RFCOMM_ConnectInd;
  p_l2c->pL2CA_ConnectCfm_Cb = RFCOMM_ConnectCnf;
  p_l2c->pL2CA_ConfigInd_Cb = RFCOMM_ConfigInd;
  p_l2c->pL2CA_ConfigCfm_Cb = RFCOMM_ConfigCnf;
  p_l2c->pL2CA_DisconnectInd_Cb = RFCOMM_DisconnectInd;
  p_l2c->pL2CA_DataInd_Cb = RFCOMM_BufDataInd;
  p_l2c->pL2CA_CongestionStatus_Cb = RFCOMM_CongestionStatusInd;
  p_l2c->pL2CA_TxComplete_Cb = nullptr;
  p_l2c->pL2CA_Error_Cb = rfc_on_l2cap_error;

  if (!stack::l2cap::get_interface().L2CA_Register(BT_PSM_RFCOMM, rfc_cb.rfc.reg_info,
                                                   true /* enable_snoop */, nullptr, L2CAP_MTU_SIZE,
                                                   0, 0)) {
    log::error("Unable to register with L2CAP profile RFCOMM psm:{}", BT_PSM_RFCOMM);
  }
}

/*******************************************************************************
 *
 * Function         RFCOMM_ConnectInd
 *
 * Description      This is a callback function called by L2CAP when
 *                  L2CA_ConnectInd received. Allocate multiplexer control
 *                  block and dispatch the event to it.
 *
 ******************************************************************************/
void RFCOMM_ConnectInd(const RawAddress& bd_addr, uint16_t lcid, uint16_t /* psm */, uint8_t id) {
  tRFC_MCB* p_mcb = rfc_alloc_multiplexer_channel(bd_addr, false);
  bluetooth::metrics::LogRfcommMxEvent(bd_addr,
                                       bluetooth::metrics::State::L2CAP_CONNECT_REQUEST_RECEIVED);
  if (p_mcb != nullptr && p_mcb->is_initiator && p_mcb->state != RFC_MX_STATE_IDLE) {
    /* Collision: We received a ConnectInd from L2CAP after sending our own L2CAP connection req.
     *
     * To avoid deadlock when both sides behave mirror-like, compare Local and Remote BD_ADDR.
     * Only the device with the lower address will swap roles to become an Acceptor.
     * The device with the higher address stays as an Initiator, rejects the incoming
     * colliding connection, and waits for its own outgoing connection to complete.
     *
     * For lower address device, the outgoing connection is cached and the incoming connection
     * is processed.  If the current state is RFC_MX_STATE_WAIT_CONN_CNF, the collision event
     * will effectively reset the state machine.
     */

    RawAddress local_addr =
            bluetooth::ToRawAddress(bluetooth::shim::GetController()->GetMacAddress());

    if (local_addr > bd_addr) {
      log::info(
              "RFCOMM MUX Collision - Local wins ({} > {}), rejecting incoming connection "
              "incoming lcid:{:x}",
              local_addr, bd_addr, lcid);
      if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
        log::warn("Unable to disconnect L2CAP cid:{}", lcid);
      }
      return;
    }

    log::info(
            "RFCOMM MUX Collision - Local loses ({} < {}), accepting incoming connection. "
            "incoming lcid:{:x}, cached lcid:{:x}",
            local_addr, bd_addr, lcid, p_mcb->lcid);
    bluetooth::metrics::LogRfcommMxEvent(
            p_mcb->bd_addr, bluetooth::metrics::State::COLLISION_DETECTED_ACCEPT_INCOMING);
    p_mcb->collision_outgoing_lcid = p_mcb->lcid;
    /* note: mcb will be stored if appropriate in COLLISION event */
    p_mcb->lcid = lcid;
    rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_COLLISION, &id);
    return;
  }

  /* store mcb even if p_mcb is null*/
  rfc_save_lcid_mcb(p_mcb, lcid);

  if (p_mcb == nullptr) {
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to disconnect L2CAP cid:{}", lcid);
    }
    return;
  }
  p_mcb->lcid = lcid;
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_CONN_IND, &id);
}

/*******************************************************************************
 *
 * Function         RFCOMM_ConnectCnf
 *
 * Description      This is a callback function called by L2CAP when
 *                  L2CA_ConnectCnf received. Save L2CAP handle and dispatch
 *                  event to the FSM.
 *
 ******************************************************************************/
void RFCOMM_ConnectCnf(uint16_t lcid, tL2CAP_CONN result) {
  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);

  if (p_mcb == nullptr) {
    /* Check if we cached corresponding lcid for collision */
    for (auto& [cid, mcb] : rfc_lcid_mcb) {
      if (mcb == nullptr || mcb->collision_outgoing_lcid != lcid) {
        continue;
      }
      if (result != tL2CAP_CONN::L2CAP_CONN_OK) {
        /* We cached the corresponding lcid but its connection failed. */
        /* We can remove from cache */
        bluetooth::metrics::LogRfcommMxEvent(mcb->bd_addr,
                                             bluetooth::metrics::State::COLLISION_OUTGOING_FAILED);
        mcb->collision_outgoing_lcid = 0;
      } else {
        /* we started accepting incoming connection and outgoing connection went through */
        log::warn(
                "Collision case: outgoing connection went through, "
                "will retry if incoming connection fails");
        mcb->collision_outgoing_conn_cnf = true;
      }
      return;
    }
    log::error("MCB for LCID 0x{:x} not found", lcid);
    return;
  }

  bluetooth::metrics::LogRfcommL2capEvent(
          p_mcb->bd_addr, bluetooth::metrics::EventType::RFCOMM_L2CAP_CONNECTION_RESPONSE_RECEIVED,
          result);

  /* Save LCID to be used in all consecutive calls to L2CAP */
  p_mcb->lcid = lcid;

  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_CONN_CNF, &result);
}

/*******************************************************************************
 *
 * Function         RFCOMM_ConfigInd
 *
 * Description      This is a callback function called by L2CAP when
 *                  L2CA_ConfigInd received. Save parameters in the control
 *                  block and dispatch event to the FSM.
 *
 ******************************************************************************/
void RFCOMM_ConfigInd(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg) {
  if (p_cfg == nullptr) {
    log::error("Received l2cap configuration info with nullptr");
    return;
  }

  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);

  if (p_mcb == nullptr) {
    log::error("LCID 0x{:x} not found", lcid);
    for (auto& [cid, mcb] : rfc_lcid_mcb) {
      if (mcb != nullptr && mcb->collision_outgoing_lcid == lcid) {
        log::info("Collision case: ConfigInd for outgoing connection");
        tL2CAP_CFG_INFO l2cap_cfg_info(*p_cfg);
        mcb->collision_outgoing_cfg_complete = true;
        mcb->collision_cfg_info = l2cap_cfg_info;
        return;
      }
    }
    return;
  }

  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_CONF_IND, (void*)p_cfg);
}

/*******************************************************************************
 *
 * Function         RFCOMM_ConfigCnf
 *
 * Description      This is a callback function called by L2CAP when
 *                  L2CA_ConfigCnf received. Save L2CAP handle and dispatch
 *                  event to the FSM.
 *
 ******************************************************************************/
void RFCOMM_ConfigCnf(uint16_t lcid, uint16_t /* initiator */, tL2CAP_CFG_INFO* p_cfg) {
  RFCOMM_ConfigInd(lcid, p_cfg);

  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);

  if (p_mcb == nullptr) {
    log::error("RFCOMM_ConfigCnf no MCB LCID:0x{:x}", lcid);
    return;
  }
  uintptr_t result_as_ptr = static_cast<unsigned>(tL2CAP_CFG_RESULT::L2CAP_CFG_OK);
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_CONF_CNF, (void*)result_as_ptr);
}

/*******************************************************************************
 *
 * Function         RFCOMM_DisconnectInd
 *
 * Description      This is a callback function called by L2CAP when
 *                  L2CA_DisconnectInd received. Dispatch event to the FSM.
 *
 ******************************************************************************/
void RFCOMM_DisconnectInd(uint16_t lcid, bool is_conf_needed) {
  log::verbose("lcid:0x{:x}, is_conf_needed:{}", lcid, is_conf_needed);
  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);
  if (p_mcb == nullptr) {
    for (auto& [cid, mcb] : rfc_lcid_mcb) {
      if (mcb != nullptr && mcb->collision_outgoing_lcid == lcid) {
        log::info("Collision case: DisconnectInd called for outgoing connection");
        // Clear cached info
        mcb->collision_outgoing_lcid = 0;
        mcb->collision_outgoing_conn_cnf = false;
        mcb->collision_outgoing_cfg_complete = false;
        mcb->collision_cfg_info = {};
        return;
      }
    }
    log::warn("no mcb for lcid 0x{:x}", lcid);
    return;
  }

  bluetooth::metrics::LogRfcommMxEvent(
          p_mcb->bd_addr, bluetooth::metrics::State::L2CAP_DISCONNECT_REQUEST_RECEIVED);
  rfc_mx_sm_execute(p_mcb, RFC_MX_EVENT_DISC_IND, nullptr);
}

/*******************************************************************************
 *
 * Function         RFCOMM_BufDataInd
 *
 * Description      This is a callback function called by L2CAP when
 *                  data RFCOMM frame is received. Parse the frames, check
 *                  the checksum and dispatch event to multiplexer or port
 *                  state machine depending on the frame destination.
 *
 ******************************************************************************/
void RFCOMM_BufDataInd(uint16_t lcid, BT_HDR* p_buf) {
  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);

  if (p_mcb == nullptr) {
    log::warn("Cannot find RFCOMM multiplexer for lcid 0x{:x}", lcid);
    osi_free(p_buf);
    return;
  }

  RfcommEvent event = rfc_parse_data(p_mcb, &rfc_cb.rfc.rx_frame, p_buf);

  /* If the frame did not pass validation just ignore it */
  if (event == RFC_EVENT_BAD_FRAME) {
    log::warn("Bad RFCOMM frame from lcid=0x{:x}, bd_addr={}, p_mcb={}", lcid, p_mcb->bd_addr,
              std::format_ptr(p_mcb));
    osi_free(p_buf);
    return;
  }

  if (rfc_cb.rfc.rx_frame.dlci == RFCOMM_MX_DLCI) {
    log::verbose("handle multiplexer event {}, p_mcb={}", rfcomm_event_text(event),
                 std::format_ptr(p_mcb));
    /* Take special care of the Multiplexer Control Messages */
    if (event == RFC_EVENT_UIH) {
      rfc_process_mx_message(p_mcb, p_buf);
      return;
    }

    /* Other multiplexer events go to state machine */
    rfc_mx_sm_execute(p_mcb, static_cast<RfcommMuxEvent>(event), nullptr);
    osi_free(p_buf);
    return;
  }

  /* The frame was received on the data channel DLCI, verify that DLC exists */
  tPORT* p_port = port_find_mcb_dlci_port(p_mcb, rfc_cb.rfc.rx_frame.dlci);
  if (p_port == nullptr || !p_port->p_mcb) {
    /* If this is a SABME on new port, check if any app is waiting for it */
    if (event != RFC_EVENT_SABME) {
      log::warn("no for none-SABME event, lcid=0x{:x}, bd_addr={}, p_mcb={}", lcid, p_mcb->bd_addr,
                std::format_ptr(p_mcb));
      if ((p_mcb->is_initiator && !rfc_cb.rfc.rx_frame.cr) ||
          (!p_mcb->is_initiator && rfc_cb.rfc.rx_frame.cr)) {
        log::error("Disconnecting RFCOMM, lcid=0x{:x}, bd_addr={}, p_mcb={}", lcid, p_mcb->bd_addr,
                   std::format_ptr(p_mcb));
        rfc_send_dm(p_mcb, rfc_cb.rfc.rx_frame.dlci, rfc_cb.rfc.rx_frame.pf);
      }
      osi_free(p_buf);
      return;
    }

    p_port = port_find_dlci_port(rfc_cb.rfc.rx_frame.dlci);
    if (p_port == nullptr) {
      log::error(
              "Disconnecting RFCOMM, no port for dlci {}, lcid=0x{:x}, bd_addr={}, "
              "p_mcb={}",
              rfc_cb.rfc.rx_frame.dlci, lcid, p_mcb->bd_addr, std::format_ptr(p_mcb));
      rfc_send_dm(p_mcb, rfc_cb.rfc.rx_frame.dlci, true);
      osi_free(p_buf);
      return;
    }
    log::verbose("port_handles[dlci={}]:{}->{}, p_mcb={}", rfc_cb.rfc.rx_frame.dlci,
                 p_mcb->port_handles[rfc_cb.rfc.rx_frame.dlci], p_port->handle,
                 std::format_ptr(p_mcb));
    p_mcb->port_handles[rfc_cb.rfc.rx_frame.dlci] = p_port->handle;
    p_port->p_mcb = p_mcb;
    if (com_android_bluetooth_flags_hfp_collision_fix_rfcomm_port_rx_buf_critical_error()) {
      port_select_mtu(p_port);
    }
  }

  if (event == RFC_EVENT_UIH) {
    log::verbose("Handling UIH event, buf_len={}, credit={}", p_buf->len,
                 rfc_cb.rfc.rx_frame.credit);
    if (p_buf->len > 0) {
      rfc_port_sm_execute(p_port, static_cast<RfcommPortEvent>(event), p_buf);
    } else {
      osi_free(p_buf);
    }

    if (rfc_cb.rfc.rx_frame.credit != 0) {
      rfc_inc_credit(p_port, rfc_cb.rfc.rx_frame.credit);
    }

    return;
  }
  rfc_port_sm_execute(p_port, static_cast<RfcommPortEvent>(event), nullptr);
  osi_free(p_buf);
}

/*******************************************************************************
 *
 * Function         RFCOMM_CongestionStatusInd
 *
 * Description      This is a callback function called by L2CAP when
 *                  data RFCOMM L2CAP congestion status changes
 *
 ******************************************************************************/
void RFCOMM_CongestionStatusInd(uint16_t lcid, bool is_congested) {
  tRFC_MCB* p_mcb = rfc_find_lcid_mcb(lcid);

  if (p_mcb == nullptr) {
    log::error("RFCOMM_CongestionStatusInd dropped LCID:0x{:x}", lcid);
    return;
  } else {
    log::verbose("RFCOMM_CongestionStatusInd LCID:0x{:x}", lcid);
  }
  rfc_process_l2cap_congestion(p_mcb, is_congested);
}

/*******************************************************************************
 *
 * Function         rfc_find_lcid_mcb
 *
 * Description      This function returns MCB block supporting local cid
 *
 ******************************************************************************/
tRFC_MCB* rfc_find_lcid_mcb(uint16_t lcid) {
  auto it = rfc_lcid_mcb.find(lcid);
  if (it == rfc_lcid_mcb.end()) {
    log::warn("no mcb saved for lcid:0x{:x}", lcid);
    return nullptr;
  }

  tRFC_MCB* p_mcb = it->second;
  if (p_mcb->lcid != lcid) {
    log::warn("LCID reused lcid=0x{:x}, current_lcid=0x{:x}", lcid, p_mcb->lcid);
    return nullptr;
  }
  return p_mcb;
}

/*******************************************************************************
 *
 * Function         rfc_save_lcid_mcb
 *
 * Description      This function saves a (lcid, p_mcb) mapping to rfc_lcid_mcb
 *
 ******************************************************************************/
void rfc_save_lcid_mcb(tRFC_MCB* p_mcb, uint16_t lcid) {
  if (p_mcb == nullptr) {
    rfc_lcid_mcb.erase(lcid);
    return;
  }
  rfc_lcid_mcb[lcid] = p_mcb;
}
