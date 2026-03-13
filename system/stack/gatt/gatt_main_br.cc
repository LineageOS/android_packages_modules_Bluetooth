/******************************************************************************
 *
 *  Copyright 2008-2012 Broadcom Corporation
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
 *  this file contains GATT BR/EDR dynamic L2CAP channel configuration.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include "osi/include/allocator.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"

using namespace bluetooth;

static void gatt_l2cif_connect_ind_cback(const RawAddress& bd_addr, uint16_t l2cap_cid,
                                         uint16_t psm, uint8_t l2cap_id);
static void gatt_l2cif_connect_cfm_cback(uint16_t l2cap_cid, tL2CAP_CONN result);
static void gatt_l2cif_config_ind_cback(uint16_t l2cap_cid, tL2CAP_CFG_INFO* p_cfg);
static void gatt_l2cif_config_cfm_cback(uint16_t lcid, uint16_t result, tL2CAP_CFG_INFO* p_cfg);
static void gatt_l2cif_disconnect_ind_cback(uint16_t l2cap_cid, bool ack_needed);
static void gatt_l2cif_disconnect(uint16_t l2cap_cid);
static void gatt_l2cif_data_ind_cback(uint16_t l2cap_cid, BT_HDR* p_msg);
static void gatt_l2cif_congest_cback(uint16_t cid, bool congested);
static void gatt_on_l2cap_error(uint16_t lcid, uint16_t result);

const tL2CAP_APPL_INFO dyn_info = {gatt_l2cif_connect_ind_cback,
                                   gatt_l2cif_connect_cfm_cback,
                                   gatt_l2cif_config_ind_cback,
                                   gatt_l2cif_config_cfm_cback,
                                   gatt_l2cif_disconnect_ind_cback,
                                   NULL,
                                   gatt_l2cif_data_ind_cback,
                                   gatt_l2cif_congest_cback,
                                   NULL,
                                   gatt_on_l2cap_error,
                                   NULL,
                                   NULL,
                                   NULL,
                                   NULL};

void (*notify_pm_br_gatt_conn_open)(const RawAddress& bda) = nullptr;
void (*notify_pm_br_gatt_conn_close)(const RawAddress& bda) = nullptr;
void (*notify_pm_br_gatt_client_op)(const RawAddress& bda) = nullptr;
void (*notify_pm_br_gatt_server_op)(const RawAddress& bda) = nullptr;

void gatt_set_br_pm_callbacks(void (*open)(const RawAddress&), void (*close)(const RawAddress&),
                              void (*client)(const RawAddress&),
                              void (*server)(const RawAddress&)) {
  notify_pm_br_gatt_conn_open = open;
  notify_pm_br_gatt_conn_close = close;
  notify_pm_br_gatt_client_op = client;
  notify_pm_br_gatt_server_op = server;
}

void gatt_init_br() {
  /* Now, register with L2CAP for ATT PSM over BR/EDR */
  if (!stack::l2cap::get_interface().L2CA_RegisterWithSecurity(
              BT_PSM_ATT, dyn_info, false /* enable_snoop */, nullptr, GATT_MAX_MTU_SIZE, 0,
              BTM_SEC_NONE)) {
    log::error("ATT Dynamic Registration failed");
  }
}

/*******************************************************************************
 *
 * Function         gatt_l2cif_connect_ind
 *
 * Description      This function handles an inbound connection indication
 *                  from L2CAP. This is the case where we are acting as a
 *                  server.
 *
 * Returns          void
 *
 ******************************************************************************/
static void gatt_l2cif_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid,
                                         uint16_t /* psm */, uint8_t /* id */) {
  tL2CAP_CONN result = tL2CAP_CONN::L2CAP_CONN_OK;
  log::info("Connection indication cid = {}", lcid);

  /* new connection ? */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, BT_TRANSPORT_BR_EDR);
  if (p_tcb == NULL) {
    /* allocate tcb */
    p_tcb = gatt_allocate_tcb_by_bdaddr(bd_addr, BT_TRANSPORT_BR_EDR);
    if (p_tcb == NULL) {
      /* no tcb available, reject L2CAP connection */
      result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
    } else {
      p_tcb->att_lcid = lcid;
    }
  } else {
    /* existing connection , reject it */
    result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
  }

  /* If we reject the connection, send DisconnectReq */
  if (result != tL2CAP_CONN::L2CAP_CONN_OK) {
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to disconnect L2CAP peer:{} cid:{}", bd_addr, lcid);
    }
    return;
  }

  /* transition to configuration state */
  gatt_set_ch_state(p_tcb, GATT_CH_CFG);
}

static void gatt_on_l2cap_error(uint16_t lcid, uint16_t /* result */) {
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (p_tcb == nullptr) {
    return;
  }
  if (gatt_get_ch_state(p_tcb) == GATT_CH_CONN) {
    gatt_cleanup_upon_disc(p_tcb->peer_bda, GATT_CONN_L2C_FAILURE, BT_TRANSPORT_BR_EDR);
  } else {
    gatt_l2cif_disconnect(lcid);
  }
}

/** This is the L2CAP connect confirm callback function */
static void gatt_l2cif_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result) {
  tGATT_TCB* p_tcb;

  /* look up clcb for this channel */
  p_tcb = gatt_find_tcb_by_cid(lcid);
  if (!p_tcb) {
    return;
  }

  log::verbose("result: {} ch_state: {}, lcid:0x{:x}", result, gatt_get_ch_state(p_tcb),
               p_tcb->att_lcid);

  if (gatt_get_ch_state(p_tcb) == GATT_CH_CONN && result == tL2CAP_CONN::L2CAP_CONN_OK) {
    gatt_set_ch_state(p_tcb, GATT_CH_CFG);
  } else {
    gatt_on_l2cap_error(lcid, static_cast<uint16_t>(result));
  }
}

/** This is the L2CAP config confirm callback function */
void gatt_l2cif_config_cfm_cback(uint16_t lcid, uint16_t /* initiator */, tL2CAP_CFG_INFO* p_cfg) {
  gatt_l2cif_config_ind_cback(lcid, p_cfg);

  /* look up clcb for this channel */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (!p_tcb) {
    return;
  }

  /* if in incorrect state */
  if (gatt_get_ch_state(p_tcb) != GATT_CH_CFG) {
    return;
  }

  gatt_set_ch_state(p_tcb, GATT_CH_OPEN);

  tGATTS_SRV_CHG* p_srv_chg_clt = gatt_is_bda_in_the_srv_chg_clt_list(p_tcb->peer_bda);
  if (p_srv_chg_clt != NULL) {
    gatt_chk_srv_chg(p_srv_chg_clt);
  } else if (get_security_client_interface().BTM_IsBonded(p_tcb->peer_bda, BT_TRANSPORT_AUTO)) {
    gatt_add_a_bonded_dev_for_srv_chg(p_tcb->peer_bda);
  }

  /* send callback */
  gatt_send_conn_cback(p_tcb);
  if (notify_pm_br_gatt_conn_open) {
    (*notify_pm_br_gatt_conn_open)(p_tcb->peer_bda);
  }
}

/** This is the L2CAP config indication callback function */
void gatt_l2cif_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg) {
  /* look up clcb for this channel */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (!p_tcb) {
    return;
  }

  /* GATT uses the smaller of our MTU and peer's MTU  */
  if (p_cfg->mtu_present && p_cfg->mtu < L2CAP_DEFAULT_MTU) {
    p_tcb->payload_size = p_cfg->mtu;
  } else {
    p_tcb->payload_size = L2CAP_DEFAULT_MTU;
  }
}

/** This is the L2CAP disconnect indication callback function */
void gatt_l2cif_disconnect_ind_cback(uint16_t lcid, bool /* ack_needed */) {
  /* look up clcb for this channel */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (!p_tcb) {
    return;
  }

  if (gatt_is_bda_in_the_srv_chg_clt_list(p_tcb->peer_bda) == nullptr &&
      get_security_client_interface().BTM_IsBonded(p_tcb->peer_bda, BT_TRANSPORT_AUTO)) {
    gatt_add_a_bonded_dev_for_srv_chg(p_tcb->peer_bda);
  }
  /* send disconnect callback */
  gatt_cleanup_upon_disc(p_tcb->peer_bda, GATT_CONN_TERMINATE_PEER_USER, BT_TRANSPORT_BR_EDR);
  if (notify_pm_br_gatt_conn_close) {
    (*notify_pm_br_gatt_conn_close)(p_tcb->peer_bda);
  }
}

static void gatt_l2cif_disconnect(uint16_t lcid) {
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
    log::warn("Unable to disconnect L2CAP cid:{}", lcid);
  }

  /* look up clcb for this channel */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (!p_tcb) {
    return;
  }

  /* If the device is not in the service changed client list, add it... */
  if (gatt_is_bda_in_the_srv_chg_clt_list(p_tcb->peer_bda) == nullptr &&
      get_security_client_interface().BTM_IsBonded(p_tcb->peer_bda, BT_TRANSPORT_AUTO)) {
    gatt_add_a_bonded_dev_for_srv_chg(p_tcb->peer_bda);
  }

  gatt_cleanup_upon_disc(p_tcb->peer_bda, GATT_CONN_TERMINATE_LOCAL_HOST, BT_TRANSPORT_BR_EDR);
  if (notify_pm_br_gatt_conn_close) {
    (*notify_pm_br_gatt_conn_close)(p_tcb->peer_bda);
  }
}

static void notify_pm_gatt_op(tGATT_TCB& tcb, BT_HDR* p_buf) {
  uint8_t* p = (uint8_t*)(p_buf + 1) + p_buf->offset;
  if (p_buf->len <= 0) {
    log::error("invalid data length, ignore");
    return;
  }
  uint8_t op_code = *p;

  if ((op_code % 2) == 0) {
    /* message from client */
    notify_pm_br_gatt_server_op(tcb.peer_bda);
  } else {
    /* message from server */
    notify_pm_br_gatt_client_op(tcb.peer_bda);
  }
}

/** This is the L2CAP data indication callback function */
static void gatt_l2cif_data_ind_cback(uint16_t lcid, BT_HDR* p_buf) {
  /* look up clcb for this channel */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);
  if (p_tcb && gatt_get_ch_state(p_tcb) == GATT_CH_OPEN) {
    notify_pm_gatt_op(*p_tcb, p_buf);
    /* process the data */
    gatt_data_process(*p_tcb, lcid, p_buf);
  }

  osi_free(p_buf);
}

/** L2CAP congestion callback */
static void gatt_l2cif_congest_cback(uint16_t lcid, bool congested) {
  tGATT_TCB* p_tcb = gatt_find_tcb_by_cid(lcid);

  if (p_tcb != NULL) {
    gatt_channel_congestion(p_tcb, congested);
  }
}

bool gatt_disconnect_br(tGATT_TCB* p_tcb) {
  tGATT_CH_STATE ch_state = gatt_get_ch_state(p_tcb);
  if ((ch_state == GATT_CH_OPEN) || (ch_state == GATT_CH_CFG)) {
    gatt_l2cif_disconnect(p_tcb->att_lcid);
  } else {
    log::verbose("gatt_disconnect_br channel not opened");
  }
  return true;
}
