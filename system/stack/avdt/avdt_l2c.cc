/******************************************************************************
 *
 *  Copyright 2002-2012 Broadcom Corporation
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
 *  This AVDTP adaptation layer module interfaces to L2CAP
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth-a2dp"

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/types/address.h>

#include <cstddef>
#include <cstdint>

#include <com_android_bluetooth_flags.h>

#include "avdt_int.h"
#include "bta/include/bta_av_api.h"
#include "device/include/interop.h"
#include "osi/include/allocator.h"
#include "stack/include/acl_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/hcidefs.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cap_types.h"
#include "stack/include/l2cdefs.h"

using namespace bluetooth;

/* callback function declarations */
static void avdt_l2c_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t psm, uint8_t id);
static void avdt_l2c_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result);
static void avdt_l2c_config_cfm_cback(uint16_t lcid, uint16_t result, tL2CAP_CFG_INFO* p_cfg);
static void avdt_l2c_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg);
static void avdt_l2c_disconnect_ind_cback(uint16_t lcid, bool ack_needed);
static void avdt_l2c_congestion_ind_cback(uint16_t lcid, bool is_congested);
static void avdt_l2c_data_ind_cback(uint16_t lcid, BT_HDR* p_buf);
static void avdt_on_l2cap_error(uint16_t lcid, uint16_t result);
static void avdt_ad_init_tc(AvdtpCcb* p_ccb, AvdtpTransportChannel* p_tbl, uint16_t lcid);

/* L2CAP callback function structure */
const tL2CAP_APPL_INFO avdt_l2c_appl = {avdt_l2c_connect_ind_cback,
                                        avdt_l2c_connect_cfm_cback,
                                        avdt_l2c_config_ind_cback,
                                        avdt_l2c_config_cfm_cback,
                                        avdt_l2c_disconnect_ind_cback,
                                        NULL,
                                        avdt_l2c_data_ind_cback,
                                        avdt_l2c_congestion_ind_cback,
                                        NULL,
                                        avdt_on_l2cap_error,
                                        NULL,
                                        NULL,
                                        NULL,
                                        NULL};

/*******************************************************************************
 *
 * Function         avdt_l2c_connect_ind_cback
 *
 * Description      This is the L2CAP connect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_connect_ind_cback(const RawAddress& bd_addr, uint16_t lcid, uint16_t /* psm */,
                                uint8_t /* id */) {
  AvdtpCcb* p_ccb;
  AvdtpTransportChannel* p_tbl = NULL;
  tL2CAP_CONN result;

  log::verbose("lcid: 0x{:04x}, bd_addr: {}", lcid, bd_addr);
  /* do we already have a control channel for this peer? */
  p_ccb = avdt_ccb_by_bd(bd_addr);
  if (p_ccb == NULL) {
    /* no, allocate ccb */
    int channel_index = BTA_AvObtainPeerChannelIndex(bd_addr);
    if (channel_index >= 0) {
      p_ccb = avdt_ccb_alloc_by_channel_index(bd_addr, channel_index);
    }
    if (p_ccb == NULL) {
      p_ccb = avdt_ccb_alloc(bd_addr);
    }
    if (p_ccb == NULL) {
      /* no ccb available, reject L2CAP connection */
      log::warn("no ccb available, reject imcoming L2CAP connection for {}", bd_addr);
      result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
    } else {
      /* allocate and set up entry; first channel is always signaling */
      p_tbl = avdt_ad_tc_tbl_alloc(p_ccb);
      avdt_ad_init_tc(p_ccb, p_tbl, lcid);
      return;
    }
  } else {
    /* deal with simultaneous control channel connect case */
    p_tbl = avdt_ad_tc_tbl_by_st(AVDT_CHAN_SIG, p_ccb, AVDT_AD_ST_CONN);
    if (p_tbl != NULL) {
      if (com_android_bluetooth_flags_a2dp_l2cap_collision_coverage()) {
        /* Collision detected. Per AVDTP specification, the device that receives
         * the L2CAP_CONNECT_IND while it is in a connecting state shall accept
         * the incoming connection and abort its own outgoing connection. */
        log::warn("Collision detected, aborting outgoing connection.");
        uint16_t outgoing_lcid = avdtp_cb.ad.rt_tbl[avdt_ccb_to_idx(p_ccb)][p_tbl->tcid].lcid;

        /* Close the outgoing connection. */
        if (!stack::l2cap::get_interface().L2CA_DisconnectReq(outgoing_lcid)) {
          log::warn("Unable to disconnect L2CAP lcid: 0x{:04x}", outgoing_lcid);
        }
        /* Close action will release both p_ccb and p_tbl */
        avdt_ad_tc_close_ind(p_tbl);

        /* Reallocate the CCB and TC table entries for the incoming connection. */
        p_ccb = avdt_ccb_by_bd(bd_addr);
        if (p_ccb == NULL) {
          int channel_index = BTA_AvObtainPeerChannelIndex(bd_addr);
          if (channel_index >= 0) {
            p_ccb = avdt_ccb_alloc_by_channel_index(bd_addr, channel_index);
          }
          if (p_ccb == NULL) {
            p_ccb = avdt_ccb_alloc(bd_addr);
          }
        }

        if (p_ccb == NULL) {
          /* still no ccb available after aborting the outgoing connection,
           * reject the incoming L2CAP connection. */
          log::warn("still no ccb available, reject imcoming L2CAP connection for {}", bd_addr);
          result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
        } else {
          p_tbl = avdt_ad_tc_tbl_alloc(p_ccb);
          avdt_ad_init_tc(p_ccb, p_tbl, lcid);
          return;
        }
      } else {
        /* reject their connection */
        result = tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES;
      }
    } else {
      /* This must be a traffic channel; are we accepting a traffic channel
       * for this ccb?
       */
      p_tbl = avdt_ad_tc_tbl_by_st(AVDT_CHAN_MEDIA, p_ccb, AVDT_AD_ST_ACP);
      if (p_tbl != NULL) {
        log::verbose("lcid: 0x{:04x} AVDT_CHAN_MEDIA", lcid);
        /* yes; proceed with connection */
        result = tL2CAP_CONN::L2CAP_CONN_OK;
      } else {
        /* this must be a reporting channel; are we accepting a reporting
         * channel for this ccb?
         */
        p_tbl = avdt_ad_tc_tbl_by_st(AVDT_CHAN_REPORT, p_ccb, AVDT_AD_ST_ACP);
        if (p_tbl != NULL) {
          log::verbose("lcid: 0x{:04x} AVDT_CHAN_REPORT", lcid);
          /* yes; proceed with connection */
          result = tL2CAP_CONN::L2CAP_CONN_OK;
        } else {
          /* else we're not listening for traffic channel; reject */
          result = tL2CAP_CONN::L2CAP_CONN_NO_PSM;
        }
      }
    }
  }

  bluetooth::metrics::LogAvdtpL2capEvent(
          bd_addr, bluetooth::metrics::EventType::AVDTP_L2CAP_CONNECTION_REQUEST_RECEIVED, result);

  /* If we reject the connection, send DisconnectReq */
  if (result != tL2CAP_CONN::L2CAP_CONN_OK) {
    log::warn("lcid: 0x{:04x}, result: {}", lcid, l2cap_result_code_text(result));
    if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
      log::warn("Unable to disconnect L2CAP lcid: 0x{:04x}", lcid);
    }
    return;
  }

  /* if result ok, proceed with connection */
  /* store idx in LCID table, store LCID in routing table */
  avdtp_cb.ad.lcid_tbl[lcid] = avdt_ad_tc_tbl_to_idx(p_tbl);
  avdtp_cb.ad.rt_tbl[avdt_ccb_to_idx(p_ccb)][p_tbl->tcid].lcid = lcid;

  /* transition to configuration state */
  p_tbl->state = AVDT_AD_ST_CFG;
}

static void avdt_on_l2cap_error(uint16_t lcid, uint16_t result) {
  AvdtpTransportChannel* p_tbl;
  AvdtpCcb* p_ccb;

  log::warn("lcid: 0x{:04x}, result: {}", lcid, to_l2cap_result_code(result));
  if (!stack::l2cap::get_interface().L2CA_DisconnectReq(lcid)) {
    log::warn("Unable to disconnect L2CAP lcid: 0x{:04x}", lcid);
  }

  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }

  p_ccb = avdt_ccb_by_idx(p_tbl->ccb_idx);
  if (p_ccb != NULL) {
    bluetooth::metrics::LogAvdtpL2capErrorEvent(p_ccb->peer_addr, to_l2cap_result_code(result));
  }
  avdt_ad_tc_close_ind(p_tbl);
}

/*******************************************************************************
 *
 * Function         avdt_l2c_connect_cfm_cback
 *
 * Description      This is the L2CAP connect confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_connect_cfm_cback(uint16_t lcid, tL2CAP_CONN result) {
  AvdtpTransportChannel* p_tbl;
  AvdtpCcb* p_ccb;

  log::verbose("lcid: 0x{:04x}, result: {}", lcid, l2cap_result_code_text(result));
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }

  p_ccb = avdt_ccb_by_idx(p_tbl->ccb_idx);
  if (p_ccb != NULL) {
    bluetooth::metrics::LogAvdtpL2capEvent(
            p_ccb->peer_addr,
            bluetooth::metrics::EventType::AVDTP_L2CAP_CONNECTION_RESPONSE_RECEIVED, result);
  }

  if (p_tbl->state != AVDT_AD_ST_CONN) {
    log::warn("Incorrect state: {}", tc_state_text(p_tbl->state));
    return;
  }

  if (result != tL2CAP_CONN::L2CAP_CONN_OK) {
    log::warn("lcid: 0x{:04x}, result: {}", lcid, l2cap_result_code_text(result));
    return;
  }

  if (p_tbl->tcid != AVDT_CHAN_SIG) {
    p_tbl->state = AVDT_AD_ST_CFG;
    return;
  }

  if (p_ccb == NULL) {
    log::warn("p_ccb is NULL");
    return;
  }

  p_tbl->state = AVDT_AD_ST_CFG;
  p_tbl->lcid = lcid;
  p_tbl->role = tAVDT_ROLE::AVDT_INT;

  if (interop_match_addr(INTEROP_2MBPS_LINK_ONLY, p_ccb->peer_addr)) {
    // Disable 3DH packets for AVDT ACL to improve sensitivity on HS
    btm_set_packet_types_from_address(
            p_ccb->peer_addr, (acl_get_supported_packet_types() | HCI_PKT_TYPES_MASK_NO_3_DH1 |
                               HCI_PKT_TYPES_MASK_NO_3_DH3 | HCI_PKT_TYPES_MASK_NO_3_DH5));
  }
}

/*******************************************************************************
 *
 * Function         avdt_l2c_config_cfm_cback
 *
 * Description      This is the L2CAP config confirm callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_config_cfm_cback(uint16_t lcid, uint16_t initiator, tL2CAP_CFG_INFO* p_cfg) {
  AvdtpTransportChannel* p_tbl;

  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }

  p_tbl->lcid = lcid;
  /* store the mtu in tbl */
  if (p_cfg->mtu_present) {
    p_tbl->peer_mtu = p_cfg->mtu;
  } else {
    p_tbl->peer_mtu = L2CAP_DEFAULT_MTU;
  }
  log::verbose("lcid: 0x{:04x}, initiator: {}, peer_mtu: {}", lcid, initiator, p_tbl->peer_mtu);
  /* if in correct state */
  if (p_tbl->state == AVDT_AD_ST_CFG) {
    avdt_ad_tc_open_ind(p_tbl);
  }
}

/*******************************************************************************
 *
 * Function         avdt_l2c_config_ind_cback
 *
 * Description      This is the L2CAP config indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_config_ind_cback(uint16_t lcid, tL2CAP_CFG_INFO* p_cfg) {
  AvdtpTransportChannel* p_tbl;

  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }

  /* store the mtu in tbl */
  if (p_cfg->mtu_present) {
    p_tbl->peer_mtu = p_cfg->mtu;
  } else {
    p_tbl->peer_mtu = L2CAP_DEFAULT_MTU;
  }
  log::verbose("lcid: 0x{:04x}, peer_mtu: {}", lcid, p_tbl->peer_mtu);
}

/*******************************************************************************
 *
 * Function         avdt_l2c_disconnect_ind_cback
 *
 * Description      This is the L2CAP disconnect indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_disconnect_ind_cback(uint16_t lcid, bool ack_needed) {
  AvdtpTransportChannel* p_tbl;
  AvdtpCcb* p_ccb;

  log::verbose("lcid: 0x{:04x}, ack_needed: {}", lcid, ack_needed);
  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }

  p_ccb = avdt_ccb_by_idx(p_tbl->ccb_idx);
  if (p_ccb != NULL) {
    bluetooth::metrics::LogAvdtpL2capEvent(
            p_ccb->peer_addr,
            bluetooth::metrics::EventType::AVDTP_L2CAP_DISCONNECTION_REQUEST_RECEIVED,
            tL2CAP_CONN::L2CAP_CONN_OK);
  }

  avdt_ad_tc_close_ind(p_tbl);
}

/*******************************************************************************
 *
 * Function         avdt_l2c_congestion_ind_cback
 *
 * Description      This is the L2CAP congestion indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_congestion_ind_cback(uint16_t lcid, bool is_congested) {
  AvdtpTransportChannel* p_tbl;

  log::verbose("lcid: 0x{:04x}, is_congested: {}", lcid, is_congested);
  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    return;
  }
  avdt_ad_tc_cong_ind(p_tbl, is_congested);
}

/*******************************************************************************
 *
 * Function         avdt_l2c_data_ind_cback
 *
 * Description      This is the L2CAP data indication callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void avdt_l2c_data_ind_cback(uint16_t lcid, BT_HDR* p_buf) {
  AvdtpTransportChannel* p_tbl;

  /* look up info for this channel */
  p_tbl = avdt_ad_tc_tbl_by_lcid(lcid);
  if (p_tbl == NULL) {
    log::warn("Adaptation layer transport channel table is NULL");
    osi_free(p_buf);
    return;
  }
  avdt_ad_tc_data_ind(p_tbl, p_buf);
}

/*******************************************************************************
 *
 * Function         avdt_ad_init_tc
 *
 * Description      Helper function to initialize the signaling channel.
 *
 *
 * Returns          void
 *
 *
 ******************************************************************************/
static void avdt_ad_init_tc(AvdtpCcb* p_ccb, AvdtpTransportChannel* p_tbl, uint16_t lcid) {
  /* allocate and set up entry; first channel is always signaling */
  log::verbose("lcid: 0x{:04x} AVDT_CHAN_SIG", lcid);
  p_tbl->my_mtu = kAvdtpMtu;
  p_tbl->tcid = AVDT_CHAN_SIG;
  p_tbl->lcid = lcid;
  p_tbl->state = AVDT_AD_ST_CFG;
  p_tbl->role = tAVDT_ROLE::AVDT_ACP;

  if (interop_match_addr(INTEROP_2MBPS_LINK_ONLY, p_ccb->peer_addr)) {
    // Disable 3DH packets for AVDT ACL to improve sensitivity on HS
    btm_set_packet_types_from_address(
            p_ccb->peer_addr, (acl_get_supported_packet_types() | HCI_PKT_TYPES_MASK_NO_3_DH1 |
                               HCI_PKT_TYPES_MASK_NO_3_DH3 | HCI_PKT_TYPES_MASK_NO_3_DH5));
  }
  /* store idx in LCID table, store LCID in routing table */
  avdtp_cb.ad.lcid_tbl[p_tbl->lcid] = avdt_ad_tc_tbl_to_idx(p_tbl);
  avdtp_cb.ad.rt_tbl[avdt_ccb_to_idx(p_ccb)][p_tbl->tcid].lcid = p_tbl->lcid;
  bluetooth::metrics::LogAvdtpL2capEvent(
          p_ccb->peer_addr, bluetooth::metrics::EventType::AVDTP_L2CAP_CONNECTION_REQUEST_RECEIVED,
          tL2CAP_CONN::L2CAP_CONN_OK);
}
