/******************************************************************************
 *
 *  Copyright 2004-2012 Broadcom Corporation
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
 *  This file contains functions for managing the SCO connection used in AG.
 *
 ******************************************************************************/

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <unordered_map>

#include "audio_hal_interface/hfp_client_interface.h"
#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_ag_swb_aptx.h"
#include "bta_ag_api.h"
#include "bta_sys.h"
#include "btm_api_types.h"
#include "btm_status.h"
#include "device/include/esco_parameters.h"
#include "hardware/bt_hf.h"
#include "hci/controller_interface.h"
#include "hci/hci_packets.h"
#include "hci_error_code.h"
#include "hcidefs.h"
#include "internal/btm_api.h"
#include "internal_include/bt_target.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sco.h"
#include "stack/btm/btm_sco_hfp_hal.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "types/raw_address.h"

extern tBTM_CB btm_cb;

using HfpInterface = bluetooth::audio::hfp::HfpClientInterface;
using namespace bluetooth;

/* Codec negotiation timeout */
#ifndef BTA_AG_CODEC_NEGOTIATION_TIMEOUT_MS
#define BTA_AG_CODEC_NEGOTIATION_TIMEOUT_MS (3 * 1000) /* 3 seconds */
#endif

#define BTM_VOICE_SETTING_CVSD                                                                     \
  ((uint16_t)(HCI_INP_CODING_LINEAR | HCI_INP_DATA_FMT_2S_COMPLEMENT | HCI_INP_SAMPLE_SIZE_16BIT | \
              HCI_AIR_CODING_FORMAT_CVSD))

#define BTM_VOICE_SETTING_TRANS                                                                    \
  ((uint16_t)(HCI_INP_CODING_LINEAR | HCI_INP_DATA_FMT_2S_COMPLEMENT | HCI_INP_SAMPLE_SIZE_16BIT | \
              HCI_AIR_CODING_FORMAT_TRANSPNT))

static void updateCodecParametersFromProviderInfo(tBTA_AG_UUID_CODEC esco_codec,
                                                  enh_esco_params_t& params);

static bool sco_allowed = true;
static bool hfp_software_datapath_enabled = false;
static RawAddress active_device_addr = {};
static std::unique_ptr<HfpInterface> hfp_client_interface;
static std::unique_ptr<HfpInterface::Offload> hfp_offload_interface;
static std::unique_ptr<HfpInterface::Encode> hfp_encode_interface;
static std::unique_ptr<HfpInterface::Decode> hfp_decode_interface;
static std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config> sco_config_map;
static std::unordered_map<tBTA_AG_UUID_CODEC, esco_coding_format_t> codec_coding_format_map{
        {tBTA_AG_UUID_CODEC::UUID_CODEC_LC3, ESCO_CODING_FORMAT_LC3},
        {tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC, ESCO_CODING_FORMAT_MSBC},
        {tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD, ESCO_CODING_FORMAT_CVSD},
};

/* sco events */
enum {
  BTA_AG_SCO_LISTEN_E,     /* listen request */
  BTA_AG_SCO_OPEN_E,       /* open request */
  BTA_AG_SCO_XFER_E,       /* transfer request */
  BTA_AG_SCO_CN_DONE_E,    /* codec negotiation done */
  BTA_AG_SCO_REOPEN_E,     /* Retry with other codec when failed */
  BTA_AG_SCO_CLOSE_E,      /* close request */
  BTA_AG_SCO_SHUTDOWN_E,   /* shutdown request */
  BTA_AG_SCO_CONN_OPEN_E,  /* sco open */
  BTA_AG_SCO_CONN_CLOSE_E, /* sco closed */
};

#define CASE_RETURN_STR(const) \
  case const:                  \
    return #const;

static const char* bta_ag_sco_evt_str(uint8_t event) {
  switch (event) {
    CASE_RETURN_STR(BTA_AG_SCO_LISTEN_E)
    CASE_RETURN_STR(BTA_AG_SCO_OPEN_E)
    CASE_RETURN_STR(BTA_AG_SCO_XFER_E)
    CASE_RETURN_STR(BTA_AG_SCO_CN_DONE_E)
    CASE_RETURN_STR(BTA_AG_SCO_REOPEN_E)
    CASE_RETURN_STR(BTA_AG_SCO_CLOSE_E)
    CASE_RETURN_STR(BTA_AG_SCO_SHUTDOWN_E)
    CASE_RETURN_STR(BTA_AG_SCO_CONN_OPEN_E)
    CASE_RETURN_STR(BTA_AG_SCO_CONN_CLOSE_E)
    default:
      return "Unknown SCO Event";
  }
}

static int codec_uuid_to_sample_rate(tBTA_AG_UUID_CODEC codec) {
  int sample_rate;
  switch (codec) {
    case tBTA_AG_UUID_CODEC::UUID_CODEC_LC3:
      sample_rate = 32000;
      break;
    case tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC:
      sample_rate = 16000;
      break;
    case tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD:
    default:
      sample_rate = 8000;
  }
  return sample_rate;
}

/**
 * Check if bd_addr is the current active device.
 *
 * @param bd_addr target device address
 * @return True if bd_addr is the current active device, False otherwise or if
 * no active device is set (i.e. active_device_addr is empty)
 */
bool bta_ag_sco_is_active_device(const RawAddress& bd_addr) {
  return !active_device_addr.IsEmpty() && active_device_addr == bd_addr;
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_conn_cback
 *
 * Description      BTM SCO connection callback.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_sco_conn_cback(uint16_t sco_idx) {
  uint16_t handle;
  tBTA_AG_SCB* p_scb;

  /* match callback to scb; first check current sco scb */
  if (bta_ag_cb.sco.p_curr_scb != nullptr && bta_ag_cb.sco.p_curr_scb->in_use) {
    handle = bta_ag_scb_to_idx(bta_ag_cb.sco.p_curr_scb);
  } else {
    /* then check for scb connected to this peer */
    /* Check if SLC is up */
    handle = bta_ag_idx_by_bdaddr(BTM_ReadScoBdAddr(sco_idx));
    p_scb = bta_ag_scb_by_idx(handle);
    if (p_scb && !p_scb->svc_conn) {
      handle = 0;
    }
  }

  if (handle != 0) {
    do_in_main_thread(base::BindOnce(&bta_ag_sm_execute_by_handle, handle, BTA_AG_SCO_OPEN_EVT,
                                     tBTA_AG_DATA::kEmpty));
  } else {
    /* no match found; disconnect sco, init sco variables */
    bta_ag_cb.sco.p_curr_scb = nullptr;
    bta_ag_cb.sco.state = BTA_AG_SCO_SHUTDOWN_ST;
    if (get_btm_client_interface().sco.BTM_RemoveSco(sco_idx) != tBTM_STATUS::BTM_SUCCESS) {
      log::warn("Unable to remove SCO idx:{}", sco_idx);
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_disc_cback
 *
 * Description      BTM SCO disconnection callback.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_sco_disc_cback(uint16_t sco_idx) {
  uint16_t handle = 0;

  log::debug("sco_idx: 0x{:x} sco.state:{}", sco_idx, bta_ag_cb.sco.state);
  log::debug("scb[0] in_use:{} sco_idx: 0x{:x} ag state:{}", bta_ag_cb.scb[0].in_use,
             bta_ag_cb.scb[0].sco_idx, bta_ag_state_str(bta_ag_cb.scb[0].state));
  log::debug("scb[1] in_use:{} sco_idx:0x{:x} ag state:{}", bta_ag_cb.scb[1].in_use,
             bta_ag_cb.scb[1].sco_idx, bta_ag_state_str(bta_ag_cb.scb[1].state));

  /* match callback to scb */
  if (bta_ag_cb.sco.p_curr_scb != nullptr && bta_ag_cb.sco.p_curr_scb->in_use) {
    /* We only care about callbacks for the active SCO */
    if (bta_ag_cb.sco.p_curr_scb->sco_idx != sco_idx) {
      if (bta_ag_cb.sco.p_curr_scb->sco_idx != 0xFFFF) {
        return;
      }
    }
    handle = bta_ag_scb_to_idx(bta_ag_cb.sco.p_curr_scb);
  }

  if (handle != 0) {
    const bool aptx_voice = is_hfp_aptx_voice_enabled() &&
                            (bta_ag_cb.sco.p_curr_scb->is_aptx_swb_codec == true) &&
                            (bta_ag_cb.sco.p_curr_scb->inuse_codec ==
                             tBTA_AG_UUID_CODEC::BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
    log::verbose("aptx_voice={}, inuse_codec={}", aptx_voice,
                 bta_ag_uuid_codec_text(bta_ag_cb.sco.p_curr_scb->inuse_codec));

    /* Restore settings */
    if (bta_ag_cb.sco.p_curr_scb->inuse_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC ||
        bta_ag_cb.sco.p_curr_scb->inuse_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_LC3 || aptx_voice ||
        (com::android::bluetooth::flags::fix_hfp_qual_1_9() &&
         bta_ag_cb.sco.p_curr_scb->inuse_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD &&
         bta_ag_cb.sco.p_curr_scb->codec_cvsd_settings != BTA_AG_SCO_CVSD_SETTINGS_S1)) {
      /* Bypass vendor specific and voice settings if enhanced eSCO supported */
      if (!(bluetooth::shim::GetController()->IsSupported(
                  bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))) {
        get_btm_client_interface().sco.BTM_WriteVoiceSettings(BTM_VOICE_SETTING_CVSD);
      }

      /* If SCO open was initiated by AG and failed for mSBC T2, try mSBC T1
       * 'Safe setting' first. If T1 also fails, try CVSD
       * same operations for LC3 settings */
      if (bta_ag_sco_is_opening(bta_ag_cb.sco.p_curr_scb) &&
          (!com::android::bluetooth::flags::fix_hfp_qual_1_9() || bta_ag_cb.sco.is_local)) {
        /* Don't bother to edit |p_curr_scb->state| because it is in
         * |BTA_AG_OPEN_ST|, which has the same value as |BTA_AG_SCO_CODEC_ST|
         */
        if (!com::android::bluetooth::flags::fix_hfp_qual_1_9()) {
          bta_ag_cb.sco.p_curr_scb->state = (tBTA_AG_STATE)BTA_AG_SCO_CODEC_ST;
        }
        if (bta_ag_cb.sco.p_curr_scb->inuse_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_LC3) {
          if (bta_ag_cb.sco.p_curr_scb->codec_lc3_settings == BTA_AG_SCO_LC3_SETTINGS_T2) {
            log::warn("eSCO/SCO failed to open, falling back to LC3 T1 settings");
            bta_ag_cb.sco.p_curr_scb->codec_lc3_settings = BTA_AG_SCO_LC3_SETTINGS_T1;
          } else {
            log::warn("eSCO/SCO failed to open, falling back to CVSD settings");
            bta_ag_cb.sco.p_curr_scb->inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD;
            bta_ag_cb.sco.p_curr_scb->codec_fallback = true;
          }
        } else if (bta_ag_cb.sco.p_curr_scb->inuse_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC ||
                   aptx_voice) {
          if (bta_ag_cb.sco.p_curr_scb->codec_msbc_settings == BTA_AG_SCO_MSBC_SETTINGS_T2) {
            log::warn("eSCO/SCO failed to open, falling back to mSBC T1 settings");
            bta_ag_cb.sco.p_curr_scb->codec_msbc_settings = BTA_AG_SCO_MSBC_SETTINGS_T1;

          } else {
            log::warn("eSCO/SCO failed to open, falling back to CVSD");
            bta_ag_cb.sco.p_curr_scb->inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD;
            bta_ag_cb.sco.p_curr_scb->codec_fallback = true;
          }
        } else {
          // Entering this block implies
          // - |fix_hfp_qual_1_9| is enabled, AND
          // - we just failed CVSD S2+.
          log::warn("eSCO/SCO failed to open, falling back to CVSD S1 settings");
          bta_ag_cb.sco.p_curr_scb->codec_cvsd_settings = BTA_AG_SCO_CVSD_SETTINGS_S1;
          bta_ag_cb.sco.p_curr_scb->trying_cvsd_safe_settings = true;
        }
      }
    } else if (bta_ag_sco_is_opening(bta_ag_cb.sco.p_curr_scb) &&
               (!com::android::bluetooth::flags::fix_hfp_qual_1_9() || bta_ag_cb.sco.is_local)) {
      log::error("eSCO/SCO failed to open, no more fall back");
      if (bta_ag_is_sco_managed_by_audio()) {
        if (hfp_software_datapath_enabled) {
          if (hfp_encode_interface) {
            hfp_encode_interface->CancelStreamingRequest();
            hfp_decode_interface->CancelStreamingRequest();
          }
        } else {
          hfp_offload_interface->CancelStreamingRequest();
        }
      }
    }

    bta_ag_cb.sco.p_curr_scb->inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE;

    do_in_main_thread(base::BindOnce(&bta_ag_sm_execute_by_handle, handle, BTA_AG_SCO_CLOSE_EVT,
                                     tBTA_AG_DATA::kEmpty));
  } else {
    /* no match found */
    log::verbose("no scb for ag_sco_disc_cback");

    /* sco could be closed after scb dealloc'ed */
    if (bta_ag_cb.sco.p_curr_scb != nullptr) {
      bta_ag_cb.sco.p_curr_scb->sco_idx = BTM_INVALID_SCO_INDEX;
      bta_ag_cb.sco.p_curr_scb = nullptr;
      bta_ag_cb.sco.state = BTA_AG_SCO_SHUTDOWN_ST;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_remove_sco
 *
 * Description      Removes the specified SCO from the system.
 *                  If only_active is true, then SCO is only removed if
 *                  connected
 *
 * Returns          bool   - true if SCO removal was started
 *
 ******************************************************************************/
static bool bta_ag_remove_sco(tBTA_AG_SCB* p_scb, bool only_active) {
  if (p_scb->sco_idx != BTM_INVALID_SCO_INDEX) {
    if (!only_active || p_scb->sco_idx == bta_ag_cb.sco.cur_idx) {
      tBTM_STATUS status = get_btm_client_interface().sco.BTM_RemoveSco(p_scb->sco_idx);
      log::debug("Removed SCO index:0x{:04x} status:{}", p_scb->sco_idx, btm_status_text(status));
      if (status == tBTM_STATUS::BTM_CMD_STARTED) {
        /* SCO is connected; set current control block */
        bta_ag_cb.sco.p_curr_scb = p_scb;
        return true;
      } else if ((status == tBTM_STATUS::BTM_SUCCESS) ||
                 (status == tBTM_STATUS::BTM_UNKNOWN_ADDR)) {
        /* If no connection reset the SCO handle */
        p_scb->sco_idx = BTM_INVALID_SCO_INDEX;
      }
    }
  }
  return false;
}

/*******************************************************************************
 *
 * Function         bta_ag_esco_connreq_cback
 *
 * Description      BTM eSCO connection requests and eSCO change requests
 *                  Only the connection requests are processed by BTA.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_esco_connreq_cback(tBTM_ESCO_EVT event, tBTM_ESCO_EVT_DATA* p_data) {
  /* Only process connection requests */
  if (event == BTM_ESCO_CONN_REQ_EVT) {
    uint16_t sco_inx = p_data->conn_evt.sco_inx;
    const RawAddress* remote_bda = BTM_ReadScoBdAddr(sco_inx);
    tBTA_AG_SCB* p_scb = bta_ag_scb_by_idx(bta_ag_idx_by_bdaddr(remote_bda));
    if (remote_bda && bta_ag_sco_is_active_device(*remote_bda) && p_scb && p_scb->svc_conn) {
      p_scb->sco_idx = sco_inx;

      /* If no other SCO active, allow this one */
      if (!bta_ag_cb.sco.p_curr_scb) {
        log::verbose("Accept Conn Request (sco_inx 0x{:04x})", sco_inx);
        bta_ag_sco_conn_rsp(p_scb, &p_data->conn_evt);

        bta_ag_cb.sco.state = BTA_AG_SCO_OPENING_ST;
        bta_ag_cb.sco.p_curr_scb = p_scb;
        bta_ag_cb.sco.cur_idx = p_scb->sco_idx;
      } else {
        /* Begin a transfer: Close current SCO before responding */
        log::verbose("bta_ag_esco_connreq_cback: Begin XFER");
        bta_ag_cb.sco.p_xfer_scb = p_scb;
        bta_ag_cb.sco.conn_data = p_data->conn_evt;
        bta_ag_cb.sco.state = BTA_AG_SCO_OPEN_XFER_ST;

        if (!bta_ag_remove_sco(bta_ag_cb.sco.p_curr_scb, true)) {
          log::error("Nothing to remove,so accept Conn Request(sco_inx 0x{:04x})", sco_inx);
          bta_ag_cb.sco.p_xfer_scb = nullptr;
          bta_ag_cb.sco.state = BTA_AG_SCO_LISTEN_ST;

          bta_ag_sco_conn_rsp(p_scb, &p_data->conn_evt);
        }
      }
    } else {
      log::warn("reject incoming SCO connection, remote_bda={}, active_bda={}, current_bda={}",
                remote_bda ? *remote_bda : RawAddress::kEmpty, active_device_addr,
                p_scb ? p_scb->peer_addr : RawAddress::kEmpty);
      get_btm_client_interface().sco.BTM_EScoConnRsp(
              p_data->conn_evt.sco_inx, HCI_ERR_HOST_REJECT_RESOURCES, (enh_esco_params_t*)nullptr);
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_cback_sco
 *
 * Description      Call application callback function with SCO event.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_cback_sco(tBTA_AG_SCB* p_scb, tBTA_AG_EVT event) {
  tBTA_AG_HDR sco = {};
  sco.handle = bta_ag_scb_to_idx(p_scb);
  sco.app_id = p_scb->app_id;
  /* call close cback */
  (*bta_ag_cb.p_cback)(static_cast<tBTA_AG_EVT>(event), (tBTA_AG*)&sco);
}

/*******************************************************************************
 *
 * Function         bta_ag_create_sco
 *
 * Description      Create a SCO connection for a given control block
 *                  p_scb : Pointer to the target AG control block
 *                  is_orig : Whether to initiate or listen for SCO connection
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_create_sco(tBTA_AG_SCB* p_scb, bool is_orig) {
  log::debug("BEFORE {}", p_scb->ToString());
  tBTA_AG_UUID_CODEC esco_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_CVSD;

  if (!bta_ag_sco_is_active_device(p_scb->peer_addr)) {
    log::warn("device {} is not active, active_device={}", p_scb->peer_addr, active_device_addr);
    if (bta_ag_cb.sco.p_curr_scb != nullptr && bta_ag_cb.sco.p_curr_scb->in_use &&
        p_scb == bta_ag_cb.sco.p_curr_scb) {
      do_in_main_thread(base::BindOnce(&bta_ag_sm_execute, p_scb, BTA_AG_SCO_CLOSE_EVT,
                                       tBTA_AG_DATA::kEmpty));
    }
    return;
  }
  /* Make sure this SCO handle is not already in use */
  if (p_scb->sco_idx != BTM_INVALID_SCO_INDEX) {
    log::error("device {}, index 0x{:04x} already in use!", p_scb->peer_addr, p_scb->sco_idx);
    return;
  }

  if ((p_scb->sco_codec == BTM_SCO_CODEC_MSBC) && !p_scb->codec_fallback &&
      hfp_hal_interface::get_wbs_supported()) {
    esco_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC;
  }

  if (is_hfp_aptx_voice_enabled()) {
    if ((p_scb->sco_codec == BTA_AG_SCO_APTX_SWB_SETTINGS_Q0) && !p_scb->codec_fallback) {
      esco_codec = tBTA_AG_UUID_CODEC ::BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
    }
  }

  if ((p_scb->sco_codec == BTM_SCO_CODEC_LC3) && !p_scb->codec_fallback &&
      hfp_hal_interface::get_swb_supported()) {
    esco_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_LC3;
  }

  p_scb->trying_cvsd_safe_settings = false;

  if (p_scb->codec_fallback) {
    p_scb->codec_fallback = false;
    /* Force AG to send +BCS for the next audio connection. */
    p_scb->codec_updated = true;
    /* reset to CVSD S4 settings as the preferred */
    p_scb->codec_cvsd_settings = BTA_AG_SCO_CVSD_SETTINGS_S4;
    /* Reset mSBC settings to T2 for the next audio connection */
    p_scb->codec_msbc_settings = BTA_AG_SCO_MSBC_SETTINGS_T2;
    /* Reset LC3 settings to T2 for the next audio connection */
    p_scb->codec_lc3_settings = BTA_AG_SCO_LC3_SETTINGS_T2;
    /* Reset SWB settings to Q3 for the next audio connection */
    p_scb->codec_aptx_settings = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
  }

  bool offload = hfp_hal_interface::get_offload_enabled();
  /* Initialize eSCO parameters */
  enh_esco_params_t params = {};
  /* If SWB/WBS are excluded, use CVSD by default,
   * index is 0 for CVSD by initialization.
   * If eSCO codec is mSBC, index is T2 or T1.
   * If eSCO coedc is LC3, index is T2 or T1. */
  log::warn("esco_codec: {}", (int)esco_codec);
  if (esco_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_LC3) {
    if (p_scb->codec_lc3_settings == BTA_AG_SCO_LC3_SETTINGS_T2) {
      params = esco_parameters_for_codec(ESCO_CODEC_LC3_T2, offload);
    } else {
      params = esco_parameters_for_codec(ESCO_CODEC_LC3_T1, offload);
    }
  } else if (is_hfp_aptx_voice_enabled() &&
             (p_scb->is_aptx_swb_codec == true && !p_scb->codec_updated)) {
    if (p_scb->codec_aptx_settings == BTA_AG_SCO_APTX_SWB_SETTINGS_Q3) {
      params = esco_parameters_for_codec(ESCO_CODEC_SWB_Q3, true);
    } else if (p_scb->codec_aptx_settings == BTA_AG_SCO_APTX_SWB_SETTINGS_Q2) {
      params = esco_parameters_for_codec(ESCO_CODEC_SWB_Q2, true);
    } else if (p_scb->codec_aptx_settings == BTA_AG_SCO_APTX_SWB_SETTINGS_Q1) {
      params = esco_parameters_for_codec(ESCO_CODEC_SWB_Q1, true);
    } else if (p_scb->codec_aptx_settings == BTA_AG_SCO_APTX_SWB_SETTINGS_Q0) {
      params = esco_parameters_for_codec(ESCO_CODEC_SWB_Q0, true);
    }
  } else if (esco_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC) {
    if (p_scb->codec_msbc_settings == BTA_AG_SCO_MSBC_SETTINGS_T2) {
      params = esco_parameters_for_codec(ESCO_CODEC_MSBC_T2, offload);
    } else {
      params = esco_parameters_for_codec(ESCO_CODEC_MSBC_T1, offload);
    }
  } else {
    if (com::android::bluetooth::flags::fix_hfp_qual_1_9() &&
        p_scb->codec_cvsd_settings == BTA_AG_SCO_CVSD_SETTINGS_S1) {
      params = esco_parameters_for_codec(ESCO_CODEC_CVSD_S1, offload);
    } else {
      if ((p_scb->features & BTA_AG_FEAT_ESCO_S4) &&
          (p_scb->peer_features & BTA_AG_PEER_FEAT_ESCO_S4)) {
        // HFP >=1.7 eSCO
        params = esco_parameters_for_codec(ESCO_CODEC_CVSD_S4, offload);
      } else {
        // HFP <=1.6 eSCO
        params = esco_parameters_for_codec(ESCO_CODEC_CVSD_S3, offload);
      }
    }
  }

  updateCodecParametersFromProviderInfo(esco_codec, params);

  /* Configure input/output data path based on HAL settings. */
  hfp_hal_interface::set_codec_datapath(esco_codec);
  hfp_hal_interface::update_esco_parameters(&params);

  /* If initiating, setup parameters to start SCO/eSCO connection */
  if (is_orig) {
    bta_ag_cb.sco.is_local = true;
    /* Set eSCO Mode */
    if (get_btm_client_interface().sco.BTM_SetEScoMode(&params) != tBTM_STATUS::BTM_SUCCESS) {
      log::warn("Unable to set ESCO mode");
    }
    bta_ag_cb.sco.p_curr_scb = p_scb;
    /* save the current codec as sco_codec can be updated while SCO is open. */
    p_scb->inuse_codec = esco_codec;

    /* tell sys to stop av if any */
    bta_sys_sco_use(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

    bta_ag_cb.sco.cur_idx = p_scb->sco_idx;

    /* Bypass voice settings if enhanced SCO setup command is supported */
    if (!(bluetooth::shim::GetController()->IsSupported(
                bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION))) {
      if (esco_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_MSBC ||
          esco_codec == tBTA_AG_UUID_CODEC::UUID_CODEC_LC3) {
        get_btm_client_interface().sco.BTM_WriteVoiceSettings(BTM_VOICE_SETTING_TRANS);
      } else {
        get_btm_client_interface().sco.BTM_WriteVoiceSettings(BTM_VOICE_SETTING_CVSD);
      }
    }

    if (get_btm_client_interface().sco.BTM_CreateSco(
                &p_scb->peer_addr, true, params.packet_types, &p_scb->sco_idx,
                bta_ag_sco_conn_cback, bta_ag_sco_disc_cback) == tBTM_STATUS::BTM_CMD_STARTED) {
      /* Initiating the connection, set the current sco handle */
      bta_ag_cb.sco.cur_idx = p_scb->sco_idx;
      /* Configure input/output data. */
      hfp_hal_interface::set_codec_datapath(esco_codec);
      log::verbose("initiated SCO connection");
    }

    log::debug("Initiating AG SCO inx 0x{:04x}, pkt types 0x{:04x}", p_scb->sco_idx,
               params.packet_types);
  } else {
    /* Not initiating, go to listen mode */
    tBTM_STATUS btm_status = get_btm_client_interface().sco.BTM_CreateSco(
            &p_scb->peer_addr, false, params.packet_types, &p_scb->sco_idx, bta_ag_sco_conn_cback,
            bta_ag_sco_disc_cback);
    if (btm_status == tBTM_STATUS::BTM_CMD_STARTED) {
      if (get_btm_client_interface().sco.BTM_RegForEScoEvts(
                  p_scb->sco_idx, bta_ag_esco_connreq_cback) != tBTM_STATUS::BTM_SUCCESS) {
        log::warn("Unable to register for ESCO events");
      }
    }
    log::debug("Listening AG SCO inx 0x{:04x} status:{} pkt types 0x{:04x}", p_scb->sco_idx,
               btm_status_text(btm_status), params.packet_types);
  }
  log::debug("AFTER {}", p_scb->ToString());
}

static void updateCodecParametersFromProviderInfo(tBTA_AG_UUID_CODEC esco_codec,
                                                  enh_esco_params_t& params) {
  if (bta_ag_is_sco_managed_by_audio() && !sco_config_map.empty()) {
    auto sco_config_it = sco_config_map.find(esco_codec);
    if (sco_config_it == sco_config_map.end()) {
      log::error("cannot find sco config for esco_codec index={}",
                 bta_ag_uuid_codec_text(esco_codec));
      return;
    }
    log::debug("use ProviderInfo to update (e)sco parameters");
    params.input_data_path = sco_config_it->second.inputDataPath;
    params.output_data_path = sco_config_it->second.outputDataPath;
    if (!sco_config_it->second.useControllerCodec) {
      log::debug("use DSP Codec instead of controller codec");

      esco_coding_format_t codingFormat = codec_coding_format_map[esco_codec];
      params.input_coding_format.coding_format = codingFormat;
      params.output_coding_format.coding_format = codingFormat;
      params.input_bandwidth = TXRX_64KBITS_RATE;
      params.output_bandwidth = TXRX_64KBITS_RATE;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_codec_negotiation_timer_cback
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ag_codec_negotiation_timer_cback(void* data) {
  log::warn("Codec negotiation timeout");
  tBTA_AG_SCB* p_scb = (tBTA_AG_SCB*)data;

  /* Announce that codec negotiation failed. */
  bta_ag_sco_codec_nego(p_scb, false);

  /* call app callback */
  bta_ag_cback_sco(p_scb, BTA_AG_AUDIO_CLOSE_EVT);
}

/*******************************************************************************
 *
 * Function         bta_ag_codec_negotiate
 *
 * Description      Initiate codec negotiation by sending AT command.
 *                  If not necessary, skip negotiation.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_codec_negotiate(tBTA_AG_SCB* p_scb) {
  bta_ag_cb.sco.p_curr_scb = p_scb;
  uint8_t* p_rem_feat = get_btm_client_interface().peer.BTM_ReadRemoteFeatures(p_scb->peer_addr);
  bool sdp_wbs_support = p_scb->peer_sdp_features & BTA_AG_FEAT_WBS_SUPPORT;
  bool sdp_swb_support = p_scb->peer_sdp_features & BTA_AG_FEAT_SWB_SUPPORT;

  if (p_rem_feat == nullptr) {
    log::warn("Skip codec negotiation, failed to read remote features");
    bta_ag_sco_codec_nego(p_scb, false);
    return;
  }

  // Workaround for misbehaving HFs, which indicate which one is not support on
  // Transparent Synchronous Data in Remote Supported Features, WBS and SWB in SDP
  // and Codec Negotiation in BRSF. Fluoride will assume CVSD codec by default.
  // In Sony XAV AX100 car kit and Sony MW600 Headset case, which indicate
  // Transparent Synchronous Data and WBS support, but no codec negotiation
  // support, using mSBC codec can result background noise or no audio.
  // In Skullcandy JIB case, which indicate WBS and codec negotiation support,
  // but no Transparent Synchronous Data support, using mSBC codec can result
  // SCO setup fail by Firmware reject.
  if (!HCI_LMP_TRANSPNT_SUPPORTED(p_rem_feat) ||
      !(sdp_wbs_support ||
        (com::android::bluetooth::flags::choose_wrong_hfp_codec_in_specific_config() &&
         sdp_swb_support)) ||
      !(p_scb->peer_features & BTA_AG_PEER_FEAT_CODEC)) {
    log::info("Assume CVSD by default due to mask mismatch");
    p_scb->sco_codec = BTM_SCO_CODEC_CVSD;
  }
  const bool aptx_voice = is_hfp_aptx_voice_enabled() &&
                          (get_swb_codec_status(bluetooth::headset::BTHF_SWB_CODEC_VENDOR_APTX,
                                                &p_scb->peer_addr) ||
                           p_scb->is_aptx_swb_codec);
  log::verbose("aptx_voice={}, is_aptx_swb_codec={}, Q0 codec supported={}", aptx_voice,
               p_scb->is_aptx_swb_codec,
               (p_scb->peer_codecs & BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK) != 0);

  if (((p_scb->codec_updated || p_scb->codec_fallback) && (p_scb->features & BTA_AG_FEAT_CODEC) &&
       (p_scb->peer_features & BTA_AG_PEER_FEAT_CODEC)) ||
      (aptx_voice)) {
    log::info("Starting codec negotiation");
    /* Change the power mode to Active until SCO open is completed. */
    bta_sys_busy(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

    if (get_swb_codec_status(bluetooth::headset::BTHF_SWB_CODEC_VENDOR_APTX, &p_scb->peer_addr) &&
        (p_scb->peer_codecs & BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK)) {
      if (p_scb->is_aptx_swb_codec == false) {
        p_scb->sco_codec = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
        p_scb->is_aptx_swb_codec = true;
      }
      log::verbose("Sending +QCS, sco_codec={}, is_aptx_swb_codec={}", p_scb->sco_codec,
                   p_scb->is_aptx_swb_codec);
      /* Send +QCS to the peer */
      bta_ag_send_qcs(p_scb);
    } else {
      if (aptx_voice) {
        p_scb->sco_codec = BTM_SCO_CODEC_MSBC;
        p_scb->is_aptx_swb_codec = false;
      }
      log::verbose("Sending +BCS, sco_codec={}, is_aptx_swb_codec={}", p_scb->sco_codec,
                   p_scb->is_aptx_swb_codec);
      /* Send +BCS to the peer */
      bta_ag_send_bcs(p_scb);
    }

    /* Start timer to handle timeout */
    alarm_set_on_mloop(p_scb->codec_negotiation_timer, BTA_AG_CODEC_NEGOTIATION_TIMEOUT_MS,
                       bta_ag_codec_negotiation_timer_cback, p_scb);
  } else {
    /* use same codec type as previous SCO connection, skip codec negotiation */
    log::info("Skip codec negotiation, using the same codec");
    bta_ag_sco_codec_nego(p_scb, true);
  }
}

static void bta_ag_sco_event(tBTA_AG_SCB* p_scb, uint8_t event) {
  tBTA_AG_SCO_CB* p_sco = &bta_ag_cb.sco;
  tBTA_AG_SCO previous_state = p_sco->state;
  log::info("device:{} index:0x{:04x} state:{}[0x{:02x}] event:{}[{}]", p_scb->peer_addr,
            p_scb->sco_idx, p_sco->state, static_cast<uint8_t>(p_sco->state),
            bta_ag_sco_evt_str(event), event);

  switch (p_sco->state) {
    case BTA_AG_SCO_SHUTDOWN_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection */
          bta_ag_create_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_SHUTDOWN_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_LISTEN_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection (Additional channel) */
          bta_ag_create_sco(p_scb, false);
          break;

        case BTA_AG_SCO_OPEN_E:
          /* remove listening connection */
          bta_ag_remove_sco(p_scb, false);

          /* start codec negotiation */
          p_sco->state = BTA_AG_SCO_CODEC_ST;
          bta_ag_codec_negotiate(p_scb);
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* remove listening connection */
          bta_ag_remove_sco(p_scb, false);

          if (p_scb == p_sco->p_curr_scb) {
            p_sco->p_curr_scb = nullptr;
          }

          /* If last SCO instance then finish shutting down */
          if (!bta_ag_other_scb_open(p_scb)) {
            p_sco->state = BTA_AG_SCO_SHUTDOWN_ST;
          }
          break;

        case BTA_AG_SCO_CLOSE_E:
          /* remove listening connection */
          /* Ignore the event. Keep listening SCO for the active SLC */
          log::warn("BTA_AG_SCO_LISTEN_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* sco failed; create sco listen connection */
          bta_ag_create_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_LISTEN_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_CODEC_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection (Additional channel) */
          bta_ag_create_sco(p_scb, false);
          break;

        case BTA_AG_SCO_CN_DONE_E:
          /* create sco connection to peer */
          bta_ag_create_sco(p_scb, true);
          p_sco->state = BTA_AG_SCO_OPENING_ST;
          break;

        case BTA_AG_SCO_XFER_E:
          /* save xfer scb */
          p_sco->p_xfer_scb = p_scb;
          p_sco->state = BTA_AG_SCO_CLOSE_XFER_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* remove listening connection */
          bta_ag_remove_sco(p_scb, false);

          if (p_scb == p_sco->p_curr_scb) {
            p_sco->p_curr_scb = nullptr;
          }

          /* If last SCO instance then finish shutting down */
          if (!bta_ag_other_scb_open(p_scb)) {
            p_sco->state = BTA_AG_SCO_SHUTDOWN_ST;
          } else if (com::android::bluetooth::flags::
                             update_sco_state_correctly_on_rfcomm_disconnect_during_codec_nego()) {
            /* just go back to listening */
            p_sco->state = BTA_AG_SCO_LISTEN_ST;
          }
          break;

        case BTA_AG_SCO_CLOSE_E:
          /* sco open is not started yet. just go back to listening */
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* sco failed; create sco listen connection */
          bta_ag_create_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_CODEC_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event), event);
          break;
      }
      break;

    case BTA_AG_SCO_OPENING_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* second headset has now joined */
          /* create sco listen connection (Additional channel) */
          if (p_scb != p_sco->p_curr_scb) {
            bta_ag_create_sco(p_scb, false);
          }
          break;

        case BTA_AG_SCO_REOPEN_E:
          /* start codec negotiation */
          p_sco->state = BTA_AG_SCO_CODEC_ST;
          bta_ag_codec_negotiate(p_scb);
          break;

        case BTA_AG_SCO_XFER_E:
          /* save xfer scb */
          p_sco->p_xfer_scb = p_scb;
          p_sco->state = BTA_AG_SCO_CLOSE_XFER_ST;
          break;

        case BTA_AG_SCO_CLOSE_E:
          p_sco->state = BTA_AG_SCO_OPEN_CL_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          if (com::android::bluetooth::flags::sco_state_machine_cleanup()) {
            /* remove listening connection */
            bta_ag_remove_sco(p_scb, false);

            /* If last SCO instance then finish shutting down */
            if (!bta_ag_other_scb_open(p_scb)) {
              p_sco->state = BTA_AG_SCO_SHUTDOWN_ST;
            } else if (p_scb == p_sco->p_curr_scb) {
              /* If current instance shutdown, move to listening */
              p_sco->state = BTA_AG_SCO_LISTEN_ST;
            }

            if (p_scb == p_sco->p_curr_scb) {
              p_sco->p_curr_scb = nullptr;
            }
          } else {
            /* If not opening scb, just close it */
            if (p_scb != p_sco->p_curr_scb) {
              /* remove listening connection */
              bta_ag_remove_sco(p_scb, false);
            } else {
              p_sco->state = BTA_AG_SCO_SHUTTING_ST;
            }
          }
          break;

        case BTA_AG_SCO_CONN_OPEN_E:
          p_sco->state = BTA_AG_SCO_OPEN_ST;
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* sco failed; create sco listen connection */
          bta_ag_create_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_OPENING_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_OPEN_CL_ST:
      switch (event) {
        case BTA_AG_SCO_XFER_E:
          /* save xfer scb */
          p_sco->p_xfer_scb = p_scb;

          p_sco->state = BTA_AG_SCO_CLOSE_XFER_ST;
          break;

        case BTA_AG_SCO_OPEN_E:
          p_sco->state = BTA_AG_SCO_OPENING_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* If not opening scb, just close it */
          if (p_scb != p_sco->p_curr_scb) {
            /* remove listening connection */
            bta_ag_remove_sco(p_scb, false);
          } else {
            p_sco->state = BTA_AG_SCO_SHUTTING_ST;
          }

          break;

        case BTA_AG_SCO_CONN_OPEN_E:
          /* close sco connection */
          bta_ag_remove_sco(p_scb, true);

          p_sco->state = BTA_AG_SCO_CLOSING_ST;
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* sco failed; create sco listen connection */

          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_OPEN_CL_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_OPEN_XFER_ST:
      switch (event) {
        case BTA_AG_SCO_CLOSE_E:
          /* close sco connection */
          bta_ag_remove_sco(p_scb, true);

          p_sco->state = BTA_AG_SCO_CLOSING_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* remove all connection */
          bta_ag_remove_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_SHUTTING_ST;

          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* closed sco; place in listen mode and
             accept the transferred connection */
          bta_ag_create_sco(p_scb, false); /* Back into listen mode */

          /* Accept sco connection with xfer scb */
          bta_ag_sco_conn_rsp(p_sco->p_xfer_scb, &p_sco->conn_data);
          p_sco->state = BTA_AG_SCO_OPENING_ST;
          p_sco->p_curr_scb = p_sco->p_xfer_scb;
          p_sco->cur_idx = p_sco->p_xfer_scb->sco_idx;
          p_sco->p_xfer_scb = nullptr;
          break;

        default:
          log::warn("BTA_AG_SCO_OPEN_XFER_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_OPEN_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* second headset has now joined */
          /* create sco listen connection (Additional channel) */
          if (p_scb != p_sco->p_curr_scb) {
            bta_ag_create_sco(p_scb, false);
          }
          break;

        case BTA_AG_SCO_XFER_E:
          /* close current sco connection */
          bta_ag_remove_sco(p_sco->p_curr_scb, true);

          /* save xfer scb */
          p_sco->p_xfer_scb = p_scb;

          p_sco->state = BTA_AG_SCO_CLOSE_XFER_ST;
          break;

        case BTA_AG_SCO_CLOSE_E:
          /* close sco connection if active */
          if (bta_ag_remove_sco(p_scb, true)) {
            p_sco->state = BTA_AG_SCO_CLOSING_ST;
          }
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* remove all listening connections */
          bta_ag_remove_sco(p_scb, false);

          /* If SCO was active on this scb, close it */
          if (p_scb == p_sco->p_curr_scb) {
            p_sco->state = BTA_AG_SCO_SHUTTING_ST;
          }
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* peer closed sco; create sco listen connection */
          bta_ag_create_sco(p_scb, false);
          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_OPEN_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event), event);
          break;
      }
      break;

    case BTA_AG_SCO_CLOSING_ST:
      switch (event) {
        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection (Additional channel) */
          if (p_scb != p_sco->p_curr_scb) {
            bta_ag_create_sco(p_scb, false);
          }
          break;

        case BTA_AG_SCO_OPEN_E:
          p_sco->state = BTA_AG_SCO_CLOSE_OP_ST;
          break;

        case BTA_AG_SCO_XFER_E:
          /* save xfer scb */
          p_sco->p_xfer_scb = p_scb;

          p_sco->state = BTA_AG_SCO_CLOSE_XFER_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* If not closing scb, just close it */
          if (p_scb != p_sco->p_curr_scb) {
            /* remove listening connection */
            bta_ag_remove_sco(p_scb, false);
          } else {
            p_sco->state = BTA_AG_SCO_SHUTTING_ST;
          }

          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* peer closed sco; create sco listen connection */
          bta_ag_create_sco(p_scb, false);

          p_sco->state = BTA_AG_SCO_LISTEN_ST;
          break;

        default:
          log::warn("BTA_AG_SCO_CLOSING_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_CLOSE_OP_ST:
      switch (event) {
        case BTA_AG_SCO_CLOSE_E:
          p_sco->state = BTA_AG_SCO_CLOSING_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          p_sco->state = BTA_AG_SCO_SHUTTING_ST;
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* start codec negotiation */
          p_sco->state = BTA_AG_SCO_CODEC_ST;
          bta_ag_codec_negotiate(p_scb);
          break;

        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection (Additional channel) */
          if (p_scb != p_sco->p_curr_scb) {
            bta_ag_create_sco(p_scb, false);
          }
          break;

        default:
          log::warn("BTA_AG_SCO_CLOSE_OP_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_CLOSE_XFER_ST:
      switch (event) {
        case BTA_AG_SCO_CONN_OPEN_E:
          /* close sco connection so headset can be transferred
             Probably entered this state from "opening state" */
          bta_ag_remove_sco(p_scb, true);
          break;

        case BTA_AG_SCO_CLOSE_E:
          /* clear xfer scb */
          p_sco->p_xfer_scb = nullptr;

          p_sco->state = BTA_AG_SCO_CLOSING_ST;
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          /* clear xfer scb */
          p_sco->p_xfer_scb = nullptr;

          p_sco->state = BTA_AG_SCO_SHUTTING_ST;
          break;

        case BTA_AG_SCO_CN_DONE_E:
        case BTA_AG_SCO_CONN_CLOSE_E: {
          /* closed sco; place old sco in listen mode,
             take current sco out of listen, and
             create originating sco for current */
          bta_ag_create_sco(p_scb, false);
          bta_ag_remove_sco(p_sco->p_xfer_scb, false);

          /* start codec negotiation */
          p_sco->state = BTA_AG_SCO_CODEC_ST;
          tBTA_AG_SCB* p_cn_scb = p_sco->p_xfer_scb;
          p_sco->p_xfer_scb = nullptr;
          bta_ag_codec_negotiate(p_cn_scb);
          break;
        }

        default:
          log::warn("BTA_AG_SCO_CLOSE_XFER_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    case BTA_AG_SCO_SHUTTING_ST:
      switch (event) {
        case BTA_AG_SCO_CONN_OPEN_E:
          /* close sco connection; wait for conn close event */
          bta_ag_remove_sco(p_scb, true);
          break;

        case BTA_AG_SCO_CONN_CLOSE_E:
          /* If last SCO instance then finish shutting down */
          if (!bta_ag_other_scb_open(p_scb)) {
            p_sco->state = BTA_AG_SCO_SHUTDOWN_ST;
            bta_sys_sco_unuse(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
          } else /* Other instance is still listening */
          {
            p_sco->state = BTA_AG_SCO_LISTEN_ST;
          }

          /* If SCO closed for other HS which is not being disconnected,
             then create listen sco connection for it as scb still open */
          if (bta_ag_scb_open(p_scb)) {
            bta_ag_create_sco(p_scb, false);
            p_sco->state = BTA_AG_SCO_LISTEN_ST;
          }

          if (p_scb == p_sco->p_curr_scb) {
            p_sco->p_curr_scb->sco_idx = BTM_INVALID_SCO_INDEX;
            p_sco->p_curr_scb = nullptr;
          }
          break;

        case BTA_AG_SCO_LISTEN_E:
          /* create sco listen connection (Additional channel) */
          if (p_scb != p_sco->p_curr_scb) {
            bta_ag_create_sco(p_scb, false);
          }
          break;

        case BTA_AG_SCO_SHUTDOWN_E:
          if (!bta_ag_other_scb_open(p_scb)) {
            p_sco->state = BTA_AG_SCO_SHUTDOWN_ST;
          } else /* Other instance is still listening */
          {
            p_sco->state = BTA_AG_SCO_LISTEN_ST;
          }

          if (p_scb == p_sco->p_curr_scb) {
            p_sco->p_curr_scb->sco_idx = BTM_INVALID_SCO_INDEX;
            p_sco->p_curr_scb = nullptr;
          }
          break;

        default:
          log::warn("BTA_AG_SCO_SHUTTING_ST: Ignoring event {}[{}]", bta_ag_sco_evt_str(event),
                    event);
          break;
      }
      break;

    default:
      break;
  }
  if (p_sco->state != previous_state) {
    log::warn(
            "SCO_state_change: [{}(0x{:02x})]->[{}(0x{:02x})] after event "
            "[{}(0x{:02x})]",
            previous_state, static_cast<uint8_t>(previous_state), p_sco->state,
            static_cast<uint8_t>(p_sco->state), bta_ag_sco_evt_str(event), event);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_is_open
 *
 * Description      Check if sco is open for this scb.
 *
 *
 * Returns          true if sco open for this scb, false otherwise.
 *
 ******************************************************************************/
bool bta_ag_sco_is_open(tBTA_AG_SCB* p_scb) {
  return (bta_ag_cb.sco.state == BTA_AG_SCO_OPEN_ST) && (bta_ag_cb.sco.p_curr_scb == p_scb);
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_is_opening
 *
 * Description      Check if sco is in Opening state.
 *
 *
 * Returns          true if sco is in Opening state for this scb, false
 *                  otherwise.
 *
 ******************************************************************************/
bool bta_ag_sco_is_opening(tBTA_AG_SCB* p_scb) {
  return (bta_ag_cb.sco.state == BTA_AG_SCO_OPENING_ST) && (bta_ag_cb.sco.p_curr_scb == p_scb);
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_listen
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_listen(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  log::info("{}", p_scb->peer_addr);
  bta_ag_sco_event(p_scb, BTA_AG_SCO_LISTEN_E);
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_open
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& data) {
  if (!sco_allowed) {
    log::info("not opening sco, by policy");
    return;
  }

  p_scb->disabled_codecs = data.api_audio_open.disabled_codecs;
  log::info("disabled_codecs = {}, sco_codec = {}", p_scb->disabled_codecs, p_scb->sco_codec);

  if (p_scb->disabled_codecs & p_scb->sco_codec) {
    tBTA_AG_PEER_CODEC updated_codec = BTM_SCO_CODEC_NONE;

    if (hfp_hal_interface::get_swb_supported() && (p_scb->peer_codecs & BTM_SCO_CODEC_LC3) &&
        !(p_scb->disabled_codecs & BTM_SCO_CODEC_LC3)) {
      updated_codec = BTM_SCO_CODEC_LC3;
    } else if ((p_scb->peer_codecs & BTM_SCO_CODEC_MSBC) &&
               !(p_scb->disabled_codecs & BTM_SCO_CODEC_MSBC)) {
      updated_codec = BTM_SCO_CODEC_MSBC;
    } else {
      updated_codec = BTM_SCO_CODEC_CVSD;
    }

    p_scb->sco_codec = updated_codec;
    p_scb->codec_updated = true;
  }

  /* if another scb using sco, this is a transfer */
  if (bta_ag_cb.sco.p_curr_scb && bta_ag_cb.sco.p_curr_scb != p_scb) {
    log::info("transfer {} -> {}", bta_ag_cb.sco.p_curr_scb->peer_addr, p_scb->peer_addr);
    bta_ag_sco_event(p_scb, BTA_AG_SCO_XFER_E);
  } else {
    /* else it is an open */
    log::info("open {}", p_scb->peer_addr);
    bta_ag_sco_event(p_scb, BTA_AG_SCO_OPEN_E);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_close
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  /* if scb is in use */
  /* sco_idx is not allocated in SCO_CODEC_ST, still need to move to listen
   * state. */
  if ((p_scb->sco_idx != BTM_INVALID_SCO_INDEX) || (bta_ag_cb.sco.state == BTA_AG_SCO_CODEC_ST)) {
    log::verbose("bta_ag_sco_close: sco_inx = {}", p_scb->sco_idx);
    bta_ag_sco_event(p_scb, BTA_AG_SCO_CLOSE_E);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_codec_nego
 *
 * Description      Handles result of eSCO codec negotiation
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_codec_nego(tBTA_AG_SCB* p_scb, bool result) {
  if (result) {
    /* Subsequent SCO connection will skip codec negotiation */
    log::info("Succeeded for index 0x{:04x}, device {}", p_scb->sco_idx, p_scb->peer_addr);
    p_scb->codec_updated = false;
    bta_ag_sco_event(p_scb, BTA_AG_SCO_CN_DONE_E);
  } else {
    /* codec negotiation failed */
    log::info("Failed for index 0x{:04x}, device {}", p_scb->sco_idx, p_scb->peer_addr);
    bta_ag_sco_event(p_scb, BTA_AG_SCO_CLOSE_E);
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_shutdown
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_shutdown(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  bta_ag_sco_event(p_scb, BTA_AG_SCO_SHUTDOWN_E);
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_write
 *
 * Description      Writes bytes to decoder interface
 *
 *
 * Returns          Number of bytes written
 *
 ******************************************************************************/
size_t bta_ag_sco_write(const uint8_t* p_buf, uint32_t len) {
  if (hfp_software_datapath_enabled && hfp_decode_interface) {
    return hfp_decode_interface->Write(p_buf, len);
  }
  return 0;
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_read
 *
 * Description      Reads bytes from encoder interface
 *
 *
 * Returns          Number of bytes read
 *
 ******************************************************************************/
size_t bta_ag_sco_read(uint8_t* p_buf, uint32_t len) {
  if (hfp_software_datapath_enabled && hfp_encode_interface) {
    return hfp_encode_interface->Read(p_buf, len);
  }
  return 0;
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_conn_open
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_conn_open(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  bta_ag_sco_event(p_scb, BTA_AG_SCO_CONN_OPEN_E);
  bta_sys_sco_open(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

  if (bta_ag_is_sco_managed_by_audio()) {
    bool is_controller_codec = false;
    if (sco_config_map.find(p_scb->inuse_codec) == sco_config_map.end()) {
      log::error("sco_config_map does not have inuse_codec={}",
                 bta_ag_uuid_codec_text(p_scb->inuse_codec));
    } else {
      is_controller_codec = sco_config_map[p_scb->inuse_codec].useControllerCodec;
    }

    if (hfp_software_datapath_enabled) {
      if (hfp_encode_interface) {
        int sample_rate = codec_uuid_to_sample_rate(p_scb->inuse_codec);
        hfp::pcm_config config{.sample_rate_hz = sample_rate};
        hfp_encode_interface->UpdateAudioConfigToHal(config);
        hfp_decode_interface->UpdateAudioConfigToHal(config);
      }
    } else {
      hfp::offload_config config{
              .sco_codec = p_scb->inuse_codec,
              .connection_handle = p_scb->conn_handle,
              .is_controller_codec = is_controller_codec,
              .is_nrec = p_scb->nrec_enabled,
      };

      hfp_offload_interface->UpdateAudioConfigToHal(config);
    }

    // ConfirmStreamingRequest before sends callback to java layer
    if (hfp_software_datapath_enabled) {
      if (hfp_encode_interface) {
        hfp_encode_interface->ConfirmStreamingRequest();
        hfp_decode_interface->ConfirmStreamingRequest();
      }
    } else {
      hfp_offload_interface->ConfirmStreamingRequest();
    }
  }

  /* call app callback */
  bta_ag_cback_sco(p_scb, BTA_AG_AUDIO_OPEN_EVT);

  /* reset to mSBC T2 settings as the preferred */
  p_scb->codec_msbc_settings = BTA_AG_SCO_MSBC_SETTINGS_T2;
  /* reset to LC3 T2 settings as the preferred */
  p_scb->codec_lc3_settings = BTA_AG_SCO_LC3_SETTINGS_T2;
  /* reset to SWB Q0 settings as the preferred */
  p_scb->codec_aptx_settings = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_conn_close
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_conn_close(tBTA_AG_SCB* p_scb, const tBTA_AG_DATA& /* data */) {
  /* clear current scb */
  bta_ag_cb.sco.p_curr_scb = nullptr;
  p_scb->sco_idx = BTM_INVALID_SCO_INDEX;
  const bool aptx_voice = is_hfp_aptx_voice_enabled() && p_scb->codec_fallback &&
                          (p_scb->sco_codec == BTA_AG_SCO_APTX_SWB_SETTINGS_Q0);
  log::verbose("aptx_voice={}, codec_fallback={:#x}, sco_codec={:#x}", aptx_voice,
               p_scb->codec_fallback, p_scb->sco_codec);

  /* codec_fallback is set when AG is initiator and connection failed for mSBC.
   * OR if codec is msbc and T2 settings failed, then retry Safe T1 settings
   * same operations for LC3 settings */
  if (p_scb->svc_conn && (p_scb->codec_fallback ||
                          (p_scb->sco_codec == BTM_SCO_CODEC_MSBC &&
                           p_scb->codec_msbc_settings == BTA_AG_SCO_MSBC_SETTINGS_T1) ||
                          (p_scb->sco_codec == BTM_SCO_CODEC_LC3 &&
                           p_scb->codec_lc3_settings == BTA_AG_SCO_LC3_SETTINGS_T1) ||
                          aptx_voice ||
                          (com::android::bluetooth::flags::fix_hfp_qual_1_9() &&
                           p_scb->sco_codec == BTM_SCO_CODEC_CVSD &&
                           p_scb->codec_cvsd_settings == BTA_AG_SCO_CVSD_SETTINGS_S1 &&
                           p_scb->trying_cvsd_safe_settings))) {
    bta_ag_sco_event(p_scb, BTA_AG_SCO_REOPEN_E);
  } else {
    /* Indicate if the closing of audio is because of transfer */
    bta_ag_sco_event(p_scb, BTA_AG_SCO_CONN_CLOSE_E);

    bta_sys_sco_close(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);

    bta_ag_stream_suspended();

    /* if av got suspended by this call, let it resume. */
    /* In case call stays alive regardless of sco, av should not be affected. */
    if (((p_scb->call_ind == BTA_AG_CALL_INACTIVE) &&
         (p_scb->callsetup_ind == BTA_AG_CALLSETUP_NONE)) ||
        (p_scb->post_sco == BTA_AG_POST_SCO_CALL_END)) {
      bta_sys_sco_unuse(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    }

    /* call app callback */
    bta_ag_cback_sco(p_scb, BTA_AG_AUDIO_CLOSE_EVT);
    p_scb->codec_cvsd_settings = BTA_AG_SCO_CVSD_SETTINGS_S4;
    p_scb->codec_msbc_settings = BTA_AG_SCO_MSBC_SETTINGS_T2;
    p_scb->codec_lc3_settings = BTA_AG_SCO_LC3_SETTINGS_T2;
    p_scb->codec_aptx_settings = BTA_AG_SCO_APTX_SWB_SETTINGS_Q0;
  }
}

/*******************************************************************************
 *
 * Function         bta_ag_sco_conn_rsp
 *
 * Description      Process the SCO connection request
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ag_sco_conn_rsp(tBTA_AG_SCB* p_scb, tBTM_ESCO_CONN_REQ_EVT_DATA* /*p_data*/) {
  bta_ag_cb.sco.is_local = false;

  log::verbose("eSCO {}, state {}",
               bluetooth::shim::GetController()->IsSupported(
                       bluetooth::hci::OpCode::ENHANCED_SETUP_SYNCHRONOUS_CONNECTION),
               bta_ag_cb.sco.state);

  if (bta_ag_cb.sco.state == BTA_AG_SCO_LISTEN_ST ||
      bta_ag_cb.sco.state == BTA_AG_SCO_CLOSE_XFER_ST ||
      bta_ag_cb.sco.state == BTA_AG_SCO_OPEN_XFER_ST) {
    /* tell sys to stop av if any */
    bta_sys_sco_use(BTA_ID_AG, p_scb->app_id, p_scb->peer_addr);
    /* When HS initiated SCO, it cannot be WBS. */
  }

  /* If SCO open was initiated from HS, it must be CVSD */
  p_scb->inuse_codec = tBTA_AG_UUID_CODEC::UUID_CODEC_NONE;
  /* Send pending commands to create SCO connection to peer */
  enh_esco_params_t params = {};
  bool offload = hfp_hal_interface::get_offload_enabled();
  bta_ag_cb.sco.p_curr_scb = p_scb;
  bta_ag_cb.sco.cur_idx = p_scb->sco_idx;

  // Local device accepted SCO connection from peer(HF)
  // Because HF devices usually do not send AT+BAC and +BCS command,
  // and there is no plan to implement corresponding command handlers,
  // so we only accept CVSD connection from HF no matter what's
  // requested.
  if ((p_scb->features & BTA_AG_FEAT_ESCO_S4) &&
      (p_scb->peer_features & BTA_AG_PEER_FEAT_ESCO_S4)) {
    // HFP >=1.7 eSCO
    params = esco_parameters_for_codec(ESCO_CODEC_CVSD_S4, offload);
  } else {
    // HFP <=1.6 eSCO
    params = esco_parameters_for_codec(ESCO_CODEC_CVSD_S3, offload);
  }

  // HFP v1.8 5.7.3 CVSD coding
  tSCO_CONN* p_sco = NULL;
  if (p_scb->sco_idx < BTM_MAX_SCO_LINKS) {
    p_sco = &btm_cb.sco_cb.sco_db[p_scb->sco_idx];
  }
  if (p_sco && (p_sco->esco.data.link_type == BTM_LINK_TYPE_SCO ||
                !btm_peer_supports_esco_ev3(p_sco->esco.data.bd_addr))) {
    params = esco_parameters_for_codec(SCO_CODEC_CVSD_D1, offload);
  }

  get_btm_client_interface().sco.BTM_EScoConnRsp(p_scb->sco_idx, HCI_SUCCESS, &params);
  log::verbose("listening for SCO connection");
}

bool bta_ag_get_sco_offload_enabled() { return hfp_hal_interface::get_offload_enabled(); }

void bta_ag_set_sco_offload_enabled(bool value) { hfp_hal_interface::enable_offload(value); }

void bta_ag_set_sco_allowed(bool value) {
  sco_allowed = value;
  log::verbose("{}", sco_allowed ? "sco now allowed" : "sco now not allowed");
}

bool bta_ag_is_sco_managed_by_audio() {
  bool value = false;
  if (com::android::bluetooth::flags::is_sco_managed_by_audio()) {
    value = osi_property_get_bool("bluetooth.sco.managed_by_audio", false);
    log::verbose("is_sco_managed_by_audio enabled={}", value);
  }
  return value;
}

void bta_ag_stream_suspended() {
  if (bta_ag_is_sco_managed_by_audio()) {
    if (hfp_software_datapath_enabled) {
      if (hfp_encode_interface) {
        hfp_encode_interface->CancelStreamingRequest();
        hfp_decode_interface->CancelStreamingRequest();
      }
    } else {
      if (hfp_offload_interface) {
        hfp_offload_interface->CancelStreamingRequest();
      }
    }
  }
}

const RawAddress& bta_ag_get_active_device() { return active_device_addr; }

void bta_clear_active_device() {
  log::debug("Set bta active device to null, current active device:{}", active_device_addr);
  if (bta_ag_is_sco_managed_by_audio()) {
    if (hfp_software_datapath_enabled) {
      if (hfp_encode_interface && !active_device_addr.IsEmpty()) {
        hfp_encode_interface->StopSession();
        hfp_decode_interface->StopSession();
      }
    } else {
      if (hfp_offload_interface && !active_device_addr.IsEmpty()) {
        hfp_offload_interface->StopSession();
      }
    }
  }
  active_device_addr = RawAddress::kEmpty;
}

void bta_ag_api_set_active_device(const RawAddress& new_active_device) {
  log::info("active_device_addr{}, new_active_device:{}", active_device_addr, new_active_device);
  if (new_active_device.IsEmpty()) {
    log::error("empty device");
    return;
  }

  if (bta_ag_is_sco_managed_by_audio()) {
    // Initialize and start HFP software data path
    if (!hfp_client_interface) {
      hfp_client_interface = std::unique_ptr<HfpInterface>(HfpInterface::Get());
      if (!hfp_client_interface) {
        log::error("could not acquire audio source interface");
      }
    }
    hfp_software_datapath_enabled =
            osi_property_get_bool("bluetooth.hfp.software_datapath.enabled", false);

    // Initialize and start HFP software datapath if enabled
    if (hfp_software_datapath_enabled) {
      if (hfp_client_interface && !hfp_encode_interface && !hfp_decode_interface) {
        hfp_encode_interface = std::unique_ptr<HfpInterface::Encode>(
                hfp_client_interface->GetEncode(get_main_thread()));
        hfp_decode_interface = std::unique_ptr<HfpInterface::Decode>(
                hfp_client_interface->GetDecode(get_main_thread()));
        if (!hfp_encode_interface || !hfp_decode_interface) {
          log::warn("could not get HFP SW interface");
        }
      }

      if (hfp_encode_interface && hfp_decode_interface) {
        if (active_device_addr.IsEmpty()) {
          hfp_encode_interface->StartSession();
          hfp_decode_interface->StartSession();
        }
      }
    } else {  // Initialize and start HFP offloading
      if (hfp_client_interface && !hfp_offload_interface) {
        hfp_offload_interface = std::unique_ptr<HfpInterface::Offload>(
                hfp_client_interface->GetOffload(get_main_thread()));
        if (!hfp_offload_interface) {
          log::warn("could not get offload interface");
        }
      }

      if (hfp_offload_interface) {
        sco_config_map = hfp_offload_interface->GetHfpScoConfig();
        // start audio session if there was no previous active device
        if (active_device_addr.IsEmpty()) {
          hfp_offload_interface->StartSession();
        }
      }
    }
  }
  active_device_addr = new_active_device;
}
