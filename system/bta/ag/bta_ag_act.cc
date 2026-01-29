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
 *  This file contains action functions for the audio gateway.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/types/bt_transport.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>
#include <cstring>

#include "ag/bta_ag_at.h"
#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_dm_api.h"
#include "bta/include/bta_hfp_api.h"
#include "bta_ag_api.h"
#include "bta_ag_swb_aptx.h"
#include "bta_api.h"
#include "bta_sys.h"
#include "device/include/device_iot_conf_defs.h"
#include "osi/include/alarm.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/sdp_status.h"

#ifdef __ANDROID__
#endif

#include <bluetooth/types/address.h>

#include "btif/include/btif_config.h"
#include "device/include/device_iot_config.h"
#include "device/include/interop_config.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/port_api.h"
#include "stack/include/sdp_api.h"
#include "storage/config_keys.h"

using namespace bluetooth;
using namespace bluetooth::legacy::stack::sdp;
using namespace metrics;

/*****************************************************************************
 *  Constants
 ****************************************************************************/

/* maximum length of data to read from RFCOMM */
#define BTA_AG_RFC_READ_MAX 512

/* maximum AT command length */
#define BTA_AG_CMD_MAX 512

/* SLC TIMER exception for IOT devices */
#define SLC_EXCEPTION_TIMEOUT_MS 10000

/* Collision jitter in milliseconds */
#define BTA_AG_COLLISION_MIN_DELAY_MS 50
#define BTA_AG_COLLISION_JITTER_MS 450

const uint16_t bta_ag_uuid[BTA_AG_NUM_IDX] = {UUID_SERVCLASS_HEADSET_AUDIO_GATEWAY,
                                              UUID_SERVCLASS_AG_HANDSFREE};

const uint8_t bta_ag_sec_id[BTA_AG_NUM_IDX] = {BTM_SEC_SERVICE_HEADSET_AG,
                                               BTM_SEC_SERVICE_AG_HANDSFREE};

const tBTA_SERVICE_ID bta_ag_svc_id[BTA_AG_NUM_IDX] = {BTA_HSP_SERVICE_ID, BTA_HFP_SERVICE_ID};

const tBTA_SERVICE_MASK bta_ag_svc_mask[BTA_AG_NUM_IDX] = {BTA_HSP_SERVICE_MASK,
                                                           BTA_HFP_SERVICE_MASK};

typedef void (*tBTA_AG_ATCMD_CBACK)(tBTA_AG_SCB* p_scb, uint16_t cmd, uint8_t arg_type, char* p_arg,
                                    char* p_end, int16_t int_arg);

const tBTA_AG_ATCMD_CBACK bta_ag_at_cback_tbl[BTA_AG_NUM_IDX] = {bta_ag_at_hsp_cback,
                                                                 bta_ag_at_hfp_cback};

/*******************************************************************************
 *
 * Function         bta_ag_cback_open
 *
 * Description      Send open callback event to application.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_cback_open(tBTA_AG_SCB* p_scb, const RawAddress& bd_addr,
                              tBTA_AG_STATUS status) {
  tBTA_AG_OPEN open = {};

  /* call app callback with open event */
  open.hdr.handle = bta_ag_scb_to_idx(p_scb);
  open.hdr.app_id = p_scb->app_id;
  open.status = status;
  LogMetricAgOpenStatus(bd_addr, open.status);
  open.service_id = bta_ag_svc_id[p_scb->conn_service];
  open.bd_addr = bd_addr;

  (*bta_ag_cb.p_cback)(BTA_AG_OPEN_EVT, (tBTA_AG*)&open);
}

/*******************************************************************************
 *
 * Function         bta_ag_register
 *
 * Description      This function initializes values of the AG cb and sets up
 *                  the SDP record for the services.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_register(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  /* initialize control block */
  p_scb->reg_services = data.api_register.services;
  p_scb->features = data.api_register.features;
  p_scb->masked_features = data.api_register.features;
  p_scb->app_id = data.api_register.app_id;

  /* create SDP records */
  bta_ag_create_records(p_scb, data);

  /* start RFCOMM servers */
  bta_ag_start_servers(p_scb, p_scb->reg_services);

  /* call app callback with register event */
  tBTA_AG_REGISTER reg = {};
  reg.hdr.handle = bta_ag_scb_to_idx(p_scb);
  reg.hdr.app_id = p_scb->app_id;
  reg.status = BTA_AG_SUCCESS;
  (*bta_ag_cb.p_cback)(BTA_AG_REGISTER_EVT, (tBTA_AG*)&reg);
}

/*******************************************************************************
 *
 * Function         bta_ag_deregister
 *
 * Description      This function removes the sdp records, closes the RFCOMM
 *                  servers, and deallocates the service control block.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_deregister(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /*data*/) {
  /* set dealloc */
  p_scb->dealloc = true;

  /* remove sdp records */
  bta_ag_del_records(p_scb);

  /* remove rfcomm servers */
  bta_ag_close_servers(p_scb, p_scb->reg_services);

  /* reset sco state */
  bta_ag_sco_reset(p_scb);
  /* dealloc */
  bta_ag_scb_dealloc(p_scb);
}

/*******************************************************************************
 *
 * Function         bta_ag_start_dereg
 *
 * Description      Start a deregister event.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_start_dereg(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /*data*/) {
  /* set dealloc */
  p_scb->dealloc = true;

  /* remove sdp records */
  bta_ag_del_records(p_scb);
}

/*******************************************************************************
 *
 * Function         bta_ag_start_open
 *
 * Description      This starts an AG open.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_start_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  p_scb->peer_addr = data.api_open.bd_addr;
  p_scb->open_services = p_scb->reg_services;

  /* Check if RFCOMM has any incoming connection to avoid collision. */
  if (PORT_IsCollisionDetected(p_scb->peer_addr)) {
    /* Let the incoming connection go through.                           */
    /* Issue collision for this scb for now.                             */
    /* We will decide what to do when we find incoming connection later. */
    bta_ag_collision_cback(BTA_SYS_CONN_OPEN, BTA_ID_AG, 0, p_scb->peer_addr);
    return;
  }

  /* close servers */
  bta_ag_close_servers(p_scb, p_scb->reg_services);

  /* set role */
  p_scb->role = BTA_AG_INT;

  /* do service search */
  bta_ag_do_disc(p_scb, p_scb->open_services);
}

/*******************************************************************************
 *
 * Function         bta_ag_disc_int_res
 *
 * Description      This function handles a discovery result when initiator.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_disc_int_res(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  uint16_t event = BTA_AG_DISC_FAIL_EVT;

  log::verbose("Status: {}", data.disc_result.status);

  /* if found service */
  if (data.disc_result.status == tSDP_STATUS::SDP_SUCCESS ||
      data.disc_result.status == tSDP_STATUS::SDP_DB_FULL) {
    /* get attributes */
    if (bta_ag_sdp_find_attr(p_scb, p_scb->open_services)) {
      /* set connected service */
      p_scb->conn_service = bta_ag_service_to_idx(p_scb->open_services);

      /* send ourselves sdp ok event */
      event = BTA_AG_DISC_OK_EVT;

      DEVICE_IOT_CONFIG_ADDR_SET_HEX_IF_GREATER(p_scb->peer_addr, IOT_CONF_KEY_HFP_VERSION,
                                                p_scb->peer_version, IOT_CONF_BYTE_NUM_2);
    }
  }

  /* free discovery db */
  bta_ag_free_db(p_scb, data);

  /* if service not found check if we should search for other service */
  if ((event == BTA_AG_DISC_FAIL_EVT) &&
      (data.disc_result.status == tSDP_STATUS::SDP_SUCCESS ||
       data.disc_result.status == tSDP_STATUS::SDP_DB_FULL ||
       data.disc_result.status == tSDP_STATUS::SDP_NO_RECS_MATCH)) {
    if ((p_scb->open_services & BTA_HFP_SERVICE_MASK) &&
        (p_scb->open_services & BTA_HSP_SERVICE_MASK)) {
      /* search for HSP */
      p_scb->open_services &= ~BTA_HFP_SERVICE_MASK;
      bta_ag_do_disc(p_scb, p_scb->open_services);
    } else if ((p_scb->open_services & BTA_HSP_SERVICE_MASK) &&
               (p_scb->hsp_version == HSP_VERSION_1_2)) {
      /* search for UUID_SERVCLASS_HEADSET instead */
      p_scb->hsp_version = HSP_VERSION_1_0;
      bta_ag_do_disc(p_scb, p_scb->open_services);
    } else {
      /* send ourselves sdp ok/fail event */
      bta_ag_sm_execute(p_scb, event, data);
    }
  } else {
    /* send ourselves sdp ok/fail event */
    bta_ag_sm_execute(p_scb, event, data);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_disc_acp_res
 *
 * Description      This function handles a discovery result when acceptor.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_disc_acp_res(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  /* if found service */
  if (data.disc_result.status == tSDP_STATUS::SDP_SUCCESS ||
      data.disc_result.status == tSDP_STATUS::SDP_DB_FULL) {
    /* get attributes */
    bta_ag_sdp_find_attr(p_scb, bta_ag_svc_mask[p_scb->conn_service]);
    DEVICE_IOT_CONFIG_ADDR_SET_HEX_IF_GREATER(p_scb->peer_addr, IOT_CONF_KEY_HFP_VERSION,
                                              p_scb->peer_version, IOT_CONF_BYTE_NUM_2);
  }

  /* free discovery db */
  bta_ag_free_db(p_scb, data);
}

/*******************************************************************************
 *
 * Function         bta_ag_disc_fail
 *
 * Description      This function handles a discovery failure.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_disc_fail(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  /* reopen registered servers */
  bta_ag_start_servers(p_scb, p_scb->reg_services);

  /* reinitialize stuff */

  /* clear the remote BD address */
  RawAddress peer_addr = p_scb->peer_addr;
  p_scb->peer_addr = RawAddress::kEmpty;

  /* call open cback w. failure */
  bta_ag_cback_open(p_scb, peer_addr, BTA_AG_FAIL_SDP);
}

/*******************************************************************************
 *
 * Function         bta_ag_open_fail
 *
 * Description      open connection failed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_open_fail(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  /* call open cback w. failure */
  log::debug("state {}", bta_ag_state_str(p_scb->state));
  bta_ag_cback_open(p_scb, data.api_open.bd_addr, BTA_AG_FAIL_RESOURCES);
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_fail
 *
 * Description      RFCOMM connection failed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_rfc_fail(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  log::info("reset p_scb with index={}", bta_ag_scb_to_idx(p_scb));
  RawAddress peer_addr = p_scb->peer_addr;


  for (uint8_t i = 0; i < BTA_AG_NUM_IDX; i++) {
    if (p_scb->serv_handle[i] != 0) {
      log::info("SCB idx {}: Removing server on serv_handle[{}] = {}",
                bta_ag_scb_to_idx(p_scb), i, p_scb->serv_handle[i]);
      if (RFCOMM_RemoveServer(p_scb->serv_handle[i]) != PORT_SUCCESS) {
        log::warn("RFCOMM_RemoveServer failed for handle {}",
                  p_scb->serv_handle[i]);
      }
      p_scb->serv_handle[i] = 0;
    }
  }

  /* reinitialize stuff */
  p_scb->state = BTA_AG_INIT_ST;
  p_scb->conn_handle = 0;
  p_scb->conn_service = 0;
  p_scb->peer_features = 0;
  p_scb->peer_codecs = BTM_SCO_CODEC_CVSD;
  p_scb->sco_codec = BTM_SCO_CODEC_CVSD;
  p_scb->is_aptx_swb_codec = false;
  p_scb->role = 0;
  p_scb->svc_conn = false;
  p_scb->hsp_version = HSP_VERSION_1_2;
  /*Clear the BD address*/
  p_scb->peer_addr = RawAddress::kEmpty;

  alarm_cancel(p_scb->collision_timer);

  /* reopen registered servers */
  bta_ag_start_servers(p_scb, p_scb->reg_services);

  /* call open cback w. failure */
  bta_ag_cback_open(p_scb, peer_addr, BTA_AG_FAIL_RFCOMM);
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_close
 *
 * Description      RFCOMM connection closed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_rfc_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  tBTA_AG_CLOSE close = {};
  tBTA_SERVICE_MASK services;
  int i, num_active_conn = 0;

  /* reinitialize stuff */
  p_scb->clip[0] = 0;
  p_scb->conn_service = 0;
  p_scb->peer_features = 0;
  p_scb->masked_features = p_scb->features;
  p_scb->peer_codecs = BTM_SCO_CODEC_CVSD;
  p_scb->sco_codec = BTM_SCO_CODEC_CVSD;
  /* Clear these flags upon SLC teardown */
  p_scb->codec_updated = false;
  p_scb->codec_fallback = false;
  p_scb->trying_cvsd_safe_settings = false;
  p_scb->codec_msbc_settings = BTA_AG_SCO_MSBC_SETTINGS_T2;
  p_scb->codec_cvsd_settings = BTA_AG_SCO_CVSD_SETTINGS_S4;
  p_scb->codec_aptx_settings = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
  p_scb->is_aptx_swb_codec = false;
  p_scb->codec_lc3_settings = BTA_AG_SCO_LC3_SETTINGS_T2;
  p_scb->role = 0;
  p_scb->svc_conn = false;
  p_scb->hsp_version = HSP_VERSION_1_2;
  bta_ag_at_reinit(&p_scb->at_cb);

  for (auto& peer_hf_indicator : p_scb->peer_hf_indicators) {
    peer_hf_indicator = {};
  }
  for (auto& local_hf_indicator : p_scb->local_hf_indicators) {
    local_hf_indicator = {};
  }

  /* stop timers */
  alarm_cancel(p_scb->ring_timer);
  alarm_cancel(p_scb->codec_negotiation_timer);
  alarm_cancel(p_scb->collision_timer);

  close.hdr.handle = bta_ag_scb_to_idx(p_scb);
  close.hdr.app_id = p_scb->app_id;
  close.bd_addr = p_scb->peer_addr;

  bta_sys_conn_close(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

  if (bta_ag_get_active_device() == p_scb->peer_addr) {
    bta_clear_active_device();
  }

  /* call close cback */
  (*bta_ag_cb.p_cback)(BTA_AG_CLOSE_EVT, (tBTA_AG*)&close);

  /* if not deregistering (deallocating) reopen registered servers */
  if (!p_scb->dealloc) {
    /* Clear peer bd_addr so instance can be reused */
    p_scb->peer_addr = RawAddress::kEmpty;

    /* start only unopened server */
    services = p_scb->reg_services;
    for (i = 0; i < BTA_AG_NUM_IDX && services != 0; i++) {
      if (p_scb->serv_handle[i]) {
        services &= ~((tBTA_SERVICE_MASK)1 << (BTA_HSP_SERVICE_ID + i));
      }
    }
    bta_ag_start_servers(p_scb, services);

    p_scb->conn_handle = 0;

    /* Make sure SCO state is BTA_AG_SCO_SHUTDOWN_ST */
    bta_ag_sco_shutdown(p_scb, tBTA_AG_DATA::kEmpty);

    /* Check if all the SLCs are down */
    for (i = 0; i < BTA_AG_MAX_NUM_CLIENTS; i++) {
      if (bta_ag_cb.scb[i].in_use && bta_ag_cb.scb[i].svc_conn) {
        num_active_conn++;
      }
    }

    if (!num_active_conn) {
      bta_sys_sco_unuse(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    }

    /* else close port and deallocate scb */
  } else {
    if (RFCOMM_RemoveServer(p_scb->conn_handle) != PORT_SUCCESS) {
      log::warn("Unable to remove RFCOMM server peer:{} handle:{}", p_scb->peer_addr,
                p_scb->conn_handle);
    }
      /* reset sco state */
    bta_ag_sco_reset(p_scb);
    bta_ag_scb_dealloc(p_scb);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_open
 *
 * Description      Handle RFCOMM channel open.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_rfc_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  /* initialize AT feature variables */
  p_scb->clip_enabled = false;
  p_scb->ccwa_enabled = false;
  p_scb->cmer_enabled = false;
  p_scb->cmee_enabled = false;
  p_scb->inband_enabled = ((p_scb->features & BTA_AG_FEAT_INBAND) == BTA_AG_FEAT_INBAND);
  if (p_scb->conn_service == BTA_AG_HFP) {
    size_t version_value_size = sizeof(p_scb->peer_version);
    if (!btif_config_get_bin(p_scb->peer_addr.ToString(), BTIF_STORAGE_KEY_HFP_VERSION,
                             (uint8_t*)&p_scb->peer_version, &version_value_size)) {
      log::warn("Failed read cached peer HFP version for {}", p_scb->peer_addr);
      p_scb->peer_version = HFP_HSP_VERSION_UNKNOWN;
    }
    size_t sdp_features_size = sizeof(p_scb->peer_sdp_features);
    if (btif_config_get_bin(p_scb->peer_addr.ToString(), BTIF_STORAGE_KEY_HFP_SDP_FEATURES,
                            (uint8_t*)&p_scb->peer_sdp_features, &sdp_features_size)) {
      bool sdp_wbs_support = p_scb->peer_sdp_features & BTA_AG_FEAT_WBS_SUPPORT;
      if (!p_scb->received_at_bac && sdp_wbs_support) {
        p_scb->codec_updated = true;
        p_scb->peer_codecs = BTM_SCO_CODEC_CVSD | BTM_SCO_CODEC_MSBC;
        p_scb->sco_codec = BTM_SCO_CODEC_MSBC;
      }
      bool sdp_swb_support = p_scb->peer_sdp_features & BTA_AG_FEAT_SWB_SUPPORT;
      if (!p_scb->received_at_bac && sdp_swb_support) {
        p_scb->codec_updated = true;
        p_scb->peer_codecs |= BTM_SCO_CODEC_LC3;
        p_scb->sco_codec = BTM_SCO_CODEC_LC3;
      }
    } else {
      log::warn("Failed read cached peer HFP SDP features for {}", p_scb->peer_addr);
    }
  }

  /* set up AT command interpreter */
  p_scb->at_cb.p_at_tbl = bta_ag_at_tbl[p_scb->conn_service];
  p_scb->at_cb.p_cmd_cback = bta_ag_at_cback_tbl[p_scb->conn_service];
  p_scb->at_cb.p_err_cback = bta_ag_at_err_cback;
  p_scb->at_cb.p_user = p_scb;
  p_scb->at_cb.cmd_max_len = BTA_AG_CMD_MAX;
  bta_ag_at_init(&p_scb->at_cb);

  bta_sys_conn_open(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

  bta_ag_cback_open(p_scb, p_scb->peer_addr, BTA_AG_SUCCESS);

  int ag_conn_timeout = p_bta_ag_cfg->conn_tout;
  if (interop_match_addr(INTEROP_INCREASE_AG_CONN_TIMEOUT, p_scb->peer_addr)) {
    /* use higher value for ag conn timeout */
    ag_conn_timeout = SLC_EXCEPTION_TIMEOUT_MS;
  }

  log::verbose("ag_conn_timeout: {}", ag_conn_timeout);
  if (p_scb->conn_service == BTA_AG_HFP) {
    /* if hfp start timer for service level conn */
    bta_sys_start_timer(p_scb->ring_timer, ag_conn_timeout, BTA_AG_SVC_TIMEOUT_EVT,
                        bta_ag_scb_to_idx(p_scb));
  } else {
    /* else service level conn is open */
    bta_ag_svc_conn_open(p_scb, data);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_setup_and_open
 *
 * Description      Encapsulate the "Success" logic from the end of bta_ag_rfc_acp_open
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_setup_and_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  bluetooth::metrics::LogRfcommNativeConnectionCompleteEvent(
      p_scb->peer_addr, bluetooth::metrics::EventType::RFCOMM_HFP_AG_CONNECTION, false, 0);

  uint16_t hfp_version = 0;
  p_scb->conn_handle = 0;

  /* determine connected service from port handle */
  for (uint8_t i = 0; i < BTA_AG_NUM_IDX; i++) {
    log::verbose("i = {} serv_handle = {} port_handle = {}", i,
                 p_scb->serv_handle[i], data.rfc.port_handle);

    if (p_scb->serv_handle[i] == data.rfc.port_handle) {
      p_scb->conn_service = i;
      p_scb->conn_handle = data.rfc.port_handle;
      break;
    }
  }

  if (p_scb->conn_handle == 0) {
    log::error("Failed to find service for port handle {}", data.rfc.port_handle);
    bta_ag_rfc_fail(p_scb, data);
    return;
  }

  log::verbose("conn_service = {} conn_handle = {}", p_scb->conn_service, p_scb->conn_handle);

  bta_ag_close_servers(p_scb, (p_scb->reg_services & ~bta_ag_svc_mask[p_scb->conn_service]));

  size_t version_value_size = sizeof(hfp_version);
  bool get_version = btif_config_get_bin(p_scb->peer_addr.ToString(), BTIF_STORAGE_KEY_HFP_VERSION,
                                         (uint8_t*)&hfp_version, &version_value_size);

  if (p_scb->conn_service == BTA_AG_HFP && get_version) {
    DEVICE_IOT_CONFIG_ADDR_SET_HEX_IF_GREATER(p_scb->peer_addr, IOT_CONF_KEY_HFP_VERSION,
                                              hfp_version, IOT_CONF_BYTE_NUM_2);
  }

  bta_ag_do_disc(p_scb, bta_ag_svc_mask[p_scb->conn_service]);
  bta_ag_rfc_open(p_scb, data);
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_collision_timer_cback
 *
 * Description      Handle the collision timer expiration
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_rfc_collision_timer_cback(void* data) {
  // Unpack data: [High 16 bits: SCB Index] [Low 16 bits: Incoming Port Handle]
  uintptr_t val = (uintptr_t)data;
  uint16_t scb_idx = (val >> 16) & 0xFFFF;
  uint16_t incoming_handle = val & 0xFFFF;

  tBTA_AG_SCB* p_scb = bta_ag_scb_by_idx(scb_idx);

  // 1. Basic Safety
  if (!p_scb || !p_scb->in_use) {
      log::warn("Collision timer expired but SCB {} is invalid.", scb_idx);
      return;
  }

  // 2. Shutdown Protection (Fix for Stress Test Hang)
  // If the stack is shutting down, we must NOT try to open a connection.
  if (p_scb->state != BTA_AG_OPEN_ST && p_scb->state != BTA_AG_OPENING_ST) {
      log::warn("Collision timer expired but state is {}, aborting.",
                 bta_ag_state_str(p_scb->state));
      return;
  }

  // 3. Check Incoming Connection Health
  // If the peer yielded (closed Incoming) while we waited, this check will fail.
  RawAddress temp_addr;
  int port_status = PORT_CheckConnection(incoming_handle, &temp_addr, nullptr);

  if (port_status != PORT_SUCCESS) {
      log::info("Collision timer expired. Incoming handle {} is invalid. Keeping Outgoing.",
                 incoming_handle);
      return;
  }

  // 4. Find the OUTGOING connection (the one we might close)
  uint16_t handle = bta_ag_idx_by_bdaddr(&p_scb->peer_addr);
  tBTA_AG_SCB* ag_scb = bta_ag_scb_by_idx(handle);

  // [CRITICAL CHECK] "Self-Discovery"
  // If the Outgoing connection (Channel 3) was already closed by the peer,
  // bta_ag_idx_by_bdaddr will find US (p_scb/Channel 4) because we are the only one left.
  // We must NOT close ourselves!
  if (ag_scb == p_scb) {
      log::info("Collision timer expired. Outgoing connection is already gone");
      ag_scb = nullptr;
  }

  // 5. Collision Resolution: We Lose.
  // Only close if we found a DISTINCT valid Outgoing connection.
  if (ag_scb && ag_scb->in_use && ag_scb->conn_handle > 0 && p_scb->in_use) {
      log::info("Collision timer expired. Yielding. Closing outgoing handle {}",
                 ag_scb->conn_handle);

      bluetooth::metrics::LogBluetoothEvent(
          p_scb->peer_addr, bluetooth::metrics::EventType::RFCOMM_HFP_AG_CONNECTION,
          bluetooth::metrics::State::COLLISION_DETECTED_ACCEPT_INCOMING, 0);

      if (RFCOMM_RemoveConnection(ag_scb->conn_handle) != PORT_SUCCESS) {
          log::warn("RFCOMM_RemoveConnection failed for handle {}", ag_scb->conn_handle);
      }
  }

  // 6. Finalize the Incoming Connection
  tBTA_AG_DATA open_data = {.rfc = {.port_handle = incoming_handle}};

  bta_ag_setup_and_open(p_scb, open_data);

  log::info("Flushing RFCOMM data after collision resolution");
  bta_ag_rfc_data(p_scb, open_data);
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_acp_open
 *
 * Description      Handle RFCOMM channel open when accepting connection.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_rfc_acp_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  log::verbose("serv_handle0 = {} serv_handle = {}", p_scb->serv_handle[0], p_scb->serv_handle[1]);
  /* set role */
  p_scb->role = BTA_AG_ACP;

  /* get bd addr of peer */
  uint16_t hfp_version = 0;
  RawAddress dev_addr = RawAddress::kEmpty;
  int status = PORT_CheckConnection(data.rfc.port_handle, &dev_addr, nullptr);
  if (status != PORT_SUCCESS) {
    log::error("PORT_CheckConnection returned {}", status);
    bta_ag_rfc_fail(p_scb, tBTA_AG_DATA::kEmpty);
    return;
  }

  /* Collision Handling */
  for (tBTA_AG_SCB& ag_scb : bta_ag_cb.scb) {
    // Cancel any pending collision timers
    if (ag_scb.in_use && alarm_is_scheduled(ag_scb.collision_timer)) {
      log::verbose("cancel collision alarm for {}", ag_scb.peer_addr);
      alarm_cancel(ag_scb.collision_timer);
      if (dev_addr != ag_scb.peer_addr && p_scb != &ag_scb) {
        // Resume outgoing connection if incoming is not on the same device
        bta_ag_resume_open(&ag_scb);
      }
    }
    if (dev_addr == ag_scb.peer_addr && p_scb != &ag_scb) {
      log::info("Collision detected with {}", dev_addr);

      if (ag_scb.conn_handle > 0) {
        if (com_android_bluetooth_flags_hfp_ag_rfc_race_condition_random_timer()) {
          uint64_t delay_ms = rand() % BTA_AG_COLLISION_JITTER_MS + BTA_AG_COLLISION_MIN_DELAY_MS;
          log::info("Starting random collision resolution timer: {}ms", delay_ms);

          p_scb->peer_addr = dev_addr;

          // PACK DATA: [Index of Incoming SCB | Incoming Handle]
          // We use p_scb (Incoming) for the timer so it survives if ag_scb (Outgoing) dies.
          uintptr_t cookie = ((uintptr_t)bta_ag_scb_to_idx(p_scb) << 16) | data.rfc.port_handle;

          alarm_set_on_mloop(p_scb->collision_timer, delay_ms,
                               bta_ag_rfc_collision_timer_cback, (void*)cookie);

          // Return immediately. We ignore the incoming connection for now.
          // ag_scb continues to manage the Outgoing connection.
          return;
        } else {
          bluetooth::metrics::LogBluetoothEvent(
                  ag_scb.peer_addr, bluetooth::metrics::EventType::RFCOMM_HFP_AG_CONNECTION,
                  bluetooth::metrics::State::COLLISION_DETECTED_ACCEPT_INCOMING, 0);
          status = RFCOMM_RemoveConnection(ag_scb.conn_handle);
          if (status != PORT_SUCCESS) {
            log::warn("RFCOMM_RemoveConnection failed for {}, handle {}, error {}", dev_addr,
                      ag_scb.conn_handle, status);
          }
        }
      } else {
        bta_ag_rfc_fail(&ag_scb, tBTA_AG_DATA::kEmpty);
      }
    }
    log::info("dev_addr={}, peer_addr={}, in_use={}, index={}", dev_addr, ag_scb.peer_addr,
              ag_scb.in_use, bta_ag_scb_to_idx(p_scb));
  }

  if (com_android_bluetooth_flags_hfp_ag_rfc_race_condition_random_timer()) {
    p_scb->peer_addr = dev_addr;
    bta_ag_setup_and_open(p_scb, data);
  } else {
    bluetooth::metrics::LogRfcommNativeConnectionCompleteEvent(
            p_scb->peer_addr, bluetooth::metrics::EventType::RFCOMM_HFP_AG_CONNECTION, false, 0);

    p_scb->peer_addr = dev_addr;

    /* determine connected service from port handle */
    for (uint8_t i = 0; i < BTA_AG_NUM_IDX; i++) {
      log::verbose("i = {} serv_handle = {} port_handle = {}", i, p_scb->serv_handle[i],
                   data.rfc.port_handle);

      if (p_scb->serv_handle[i] == data.rfc.port_handle) {
        p_scb->conn_service = i;
        p_scb->conn_handle = data.rfc.port_handle;
        break;
      }
    }

    log::verbose("conn_service = {} conn_handle = {}", p_scb->conn_service, p_scb->conn_handle);

    /* close any unopened server */
    bta_ag_close_servers(p_scb, (p_scb->reg_services & ~bta_ag_svc_mask[p_scb->conn_service]));

    size_t version_value_size = sizeof(hfp_version);
    bool get_version =
        btif_config_get_bin(p_scb->peer_addr.ToString(), BTIF_STORAGE_KEY_HFP_VERSION,
                            (uint8_t*)&hfp_version, &version_value_size);

    if (p_scb->conn_service == BTA_AG_HFP && get_version) {
      DEVICE_IOT_CONFIG_ADDR_SET_HEX_IF_GREATER(p_scb->peer_addr, IOT_CONF_KEY_HFP_VERSION,
                                                hfp_version, IOT_CONF_BYTE_NUM_2);
    }
    /* do service discovery to get features */
    bta_ag_do_disc(p_scb, bta_ag_svc_mask[p_scb->conn_service]);

    /* continue with common open processing */
    bta_ag_rfc_open(p_scb, data);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_rfc_data
 *
 * Description      Read and process data from RFCOMM.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_rfc_data(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  uint16_t len;
  char buf[BTA_AG_RFC_READ_MAX] = "";

  log::verbose("");

  /* do the following */
  for (;;) {
    /* read data from rfcomm; if bad status, we're done */
    if (PORT_ReadData(p_scb->conn_handle, buf, BTA_AG_RFC_READ_MAX, &len) != PORT_SUCCESS) {
      log::error("failed to read data {}", p_scb->peer_addr);
      break;
    }

    /* if no data, we're done */
    if (len == 0) {
      log::warn("no data for {}", p_scb->peer_addr);
      break;
    }

    /* run AT command interpreter on data */
    bta_sys_busy(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    bta_ag_at_parse(&p_scb->at_cb, buf, len);
    if ((p_scb->sco_idx != BTM_INVALID_SCO_INDEX) && bta_ag_sco_is_open(p_scb)) {
      log::verbose("change link policy for SCO");
      bta_sys_sco_open(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    } else {
      bta_sys_idle(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    }

    /* no more data to read, we're done */
    if (len < BTA_AG_RFC_READ_MAX) {
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_start_close
 *
 * Description      Start the process of closing SCO and RFCOMM connection.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_start_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  /* Take the link out of sniff and set L2C idle time to 0 */
  bta_dm_pm_active(p_scb->peer_addr);
  if (!stack::l2cap::get_interface().L2CA_SetIdleTimeoutByBdAddr(p_scb->peer_addr, 0,
                                                                 BT_TRANSPORT_BR_EDR)) {
    log::warn("Unable to set idle timeout peer:{}", p_scb->peer_addr);
  }

  /* if SCO is open close SCO and wait on RFCOMM close */
  if (bta_ag_sco_is_open(p_scb)) {
    p_scb->post_sco = BTA_AG_POST_SCO_CLOSE_RFC;
  } else {
    p_scb->post_sco = BTA_AG_POST_SCO_NONE;
    bta_ag_rfc_do_close(p_scb, data);
  }

  /* always do SCO shutdown to handle all SCO corner cases */
  bta_ag_sco_shutdown(p_scb, data);
}

/*******************************************************************************
 *
 * Function         bta_ag_post_sco_open
 *
 * Description      Perform post-SCO open action, if any
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_post_sco_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  switch (p_scb->post_sco) {
    case BTA_AG_POST_SCO_RING:
      bta_ag_send_ring(p_scb, data);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    case BTA_AG_POST_SCO_CALL_CONN:
      bta_ag_send_call_inds(p_scb, BTA_AG_IN_CALL_CONN_RES);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    default:
      break;
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_post_sco_close
 *
 * Description      Perform post-SCO close action, if any
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_post_sco_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  switch (p_scb->post_sco) {
    case BTA_AG_POST_SCO_CLOSE_RFC:
      bta_ag_rfc_do_close(p_scb, data);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    case BTA_AG_POST_SCO_CALL_CONN:
      bta_ag_send_call_inds(p_scb, BTA_AG_IN_CALL_CONN_RES);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    case BTA_AG_POST_SCO_CALL_ORIG:
      bta_ag_send_call_inds(p_scb, BTA_AG_OUT_CALL_ORIG_RES);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    case BTA_AG_POST_SCO_CALL_END:
      bta_ag_send_call_inds(p_scb, BTA_AG_END_CALL_RES);
      p_scb->post_sco = BTA_AG_POST_SCO_NONE;
      break;

    case BTA_AG_POST_SCO_CALL_END_INCALL:
      bta_ag_send_call_inds(p_scb, BTA_AG_END_CALL_RES);

      /* Sending callsetup IND and Ring were deferred to after SCO close. */
      bta_ag_send_call_inds(p_scb, BTA_AG_IN_CALL_RES);

      if (bta_ag_inband_enabled(p_scb) && !(p_scb->features & BTA_AG_FEAT_NOSCO)) {
        p_scb->post_sco = BTA_AG_POST_SCO_RING;
        if (!bta_ag_is_sco_open_allowed(p_scb, "BTA_AG_POST_SCO_CALL_END_INCALL")) {
          break;
        }
        if (bta_ag_is_sco_managed_by_audio()) {
          // let Audio HAL open the SCO
          break;
        }
        bta_ag_sco_open(p_scb, data);
      } else {
        p_scb->post_sco = BTA_AG_POST_SCO_NONE;
        bta_ag_send_ring(p_scb, data);
      }
      break;

    default:
      break;
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_svc_conn_open
 *
 * Description      Service level connection opened
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_svc_conn_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  tBTA_AG_CONN evt = {};

  if (!p_scb->svc_conn) {
    /* set state variable */
    p_scb->svc_conn = true;

    /* Clear AT+BIA mask from previous SLC if any. */
    p_scb->bia_masked_out = 0;

    alarm_cancel(p_scb->ring_timer);

    /* call callback */
    evt.hdr.handle = bta_ag_scb_to_idx(p_scb);
    evt.hdr.app_id = p_scb->app_id;
    evt.peer_feat = p_scb->peer_features;
    evt.bd_addr = p_scb->peer_addr;
    evt.peer_codec = p_scb->peer_codecs;

    if ((p_scb->call_ind != BTA_AG_CALL_INACTIVE) ||
        (p_scb->callsetup_ind != BTA_AG_CALLSETUP_NONE)) {
      bta_sys_sco_use(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    }
    if (bta_ag_get_active_device().IsEmpty()) {
      bta_ag_api_set_active_device(p_scb->peer_addr);
    }
    (*bta_ag_cb.p_cback)(BTA_AG_CONN_EVT, (tBTA_AG*)&evt);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_setcodec
 *
 * Description      Handle API SetCodec
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_setcodec(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  tBTA_AG_PEER_CODEC codec_type = data.api_setcodec.codec;
  tBTA_AG_VAL val = {};
  const bool aptx_voice =
          is_hfp_aptx_voice_enabled() && (codec_type == BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  log::verbose("aptx_voice={}, codec_type={:#x}", aptx_voice, codec_type);

  val.hdr.handle = bta_ag_scb_to_idx(p_scb);

  /* Check if the requested codec type is valid */
  if ((codec_type != BTM_SCO_CODEC_NONE) && (codec_type != BTM_SCO_CODEC_CVSD) &&
      (codec_type != BTM_SCO_CODEC_MSBC) && (codec_type != BTM_SCO_CODEC_LC3) && !aptx_voice) {
    val.num = codec_type;
    val.hdr.status = BTA_AG_FAIL_RESOURCES;
    log::error("error: unsupported codec type {}", codec_type);
    (*bta_ag_cb.p_cback)(BTA_AG_CODEC_EVT, (tBTA_AG*)&val);
    return;
  }

  if ((p_scb->peer_codecs & codec_type) || (codec_type == BTM_SCO_CODEC_NONE) ||
      (codec_type == BTM_SCO_CODEC_CVSD)) {
    p_scb->sco_codec = codec_type;
    p_scb->codec_updated = true;
    val.num = codec_type;
    val.hdr.status = BTA_AG_SUCCESS;
    log::verbose("Updated codec type {}", codec_type);
  } else {
    val.num = codec_type;
    val.hdr.status = BTA_AG_FAIL_RESOURCES;
    log::error("error: unsupported codec type {}", codec_type);
  }

  (*bta_ag_cb.p_cback)(BTA_AG_CODEC_EVT, (tBTA_AG*)&val);
}

static void bta_ag_collision_timer_cback(void* data) {
  if (data == nullptr) {
    log::error("data should never be null in a timer callback");
    return;
  }
  /* If the peer haven't opened AG connection     */
  /* we will restart opening process.             */
  bta_ag_resume_open(static_cast<tBTA_AG_SCB*>(data));
}

void bta_ag_handle_collision(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  /* Cancel SDP if it had been started. */
  if (p_scb->p_disc_db) {
    if (!get_legacy_stack_sdp_api()->SDP_CancelServiceSearch(p_scb->p_disc_db)) {
      log::warn("Unable to cancel SDP service discovery search peer:{}", p_scb->peer_addr);
    }
    bta_ag_free_db(p_scb, tBTA_AG_DATA::kEmpty);
  }

  /* reopen registered servers */
  /* Collision may be detected before or after we close servers. */
  if (bta_ag_is_server_closed(p_scb)) {
    bta_ag_start_servers(p_scb, p_scb->reg_services);
  }

  /* Start timer to han */
  alarm_set_on_mloop(p_scb->collision_timer, BTA_AG_COLLISION_TIMEOUT_MS,
                     bta_ag_collision_timer_cback, p_scb);
}
