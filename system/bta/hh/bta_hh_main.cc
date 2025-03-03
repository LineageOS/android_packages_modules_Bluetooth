/******************************************************************************
 *
 *  Copyright 2005-2012 Broadcom Corporation
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
 *  This file contains the HID host main functions and state machine.
 *
 ******************************************************************************/

#define LOG_TAG "bt_bta_hh"

#include <bluetooth/log.h>

#include <cstdint>

#include "bta/hh/bta_hh_int.h"
#include "bta_hh_api.h"
#include "hiddefs.h"
#include "main/shim/dumpsys.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"

using namespace bluetooth;

/*****************************************************************************
 * Global data
 ****************************************************************************/
tBTA_HH_CB bta_hh_cb;

/*****************************************************************************
 * Static functions
 ****************************************************************************/
/*******************************************************************************
 *
 * Function         bta_hh_evt_code
 *
 * Description
 *
 * Returns          void
 *
 ******************************************************************************/
static const char* bta_hh_evt_code(tBTA_HH_INT_EVT evt_code) {
  switch (evt_code) {
    case BTA_HH_API_OPEN_EVT:
      return "BTA_HH_API_OPEN_EVT";
    case BTA_HH_API_CLOSE_EVT:
      return "BTA_HH_API_CLOSE_EVT";
    case BTA_HH_INT_OPEN_EVT:
      return "BTA_HH_INT_OPEN_EVT";
    case BTA_HH_INT_CLOSE_EVT:
      return "BTA_HH_INT_CLOSE_EVT";
    case BTA_HH_INT_HANDSK_EVT:
      return "BTA_HH_INT_HANDSK_EVT";
    case BTA_HH_INT_DATA_EVT:
      return "BTA_HH_INT_DATA_EVT";
    case BTA_HH_INT_CTRL_DATA:
      return "BTA_HH_INT_CTRL_DATA";
    case BTA_HH_API_WRITE_DEV_EVT:
      return "BTA_HH_API_WRITE_DEV_EVT";
    case BTA_HH_SDP_CMPL_EVT:
      return "BTA_HH_SDP_CMPL_EVT";
    case BTA_HH_API_MAINT_DEV_EVT:
      return "BTA_HH_API_MAINT_DEV_EVT";
    case BTA_HH_API_GET_DSCP_EVT:
      return "BTA_HH_API_GET_DSCP_EVT";
    case BTA_HH_OPEN_CMPL_EVT:
      return "BTA_HH_OPEN_CMPL_EVT";
    case BTA_HH_GATT_CLOSE_EVT:
      return "BTA_HH_GATT_CLOSE_EVT";
    case BTA_HH_GATT_OPEN_EVT:
      return "BTA_HH_GATT_OPEN_EVT";
    case BTA_HH_START_ENC_EVT:
      return "BTA_HH_START_ENC_EVT";
    case BTA_HH_ENC_CMPL_EVT:
      return "BTA_HH_ENC_CMPL_EVT";
    default:
      return "unknown HID Host event code";
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_state_code
 *
 * Description      get string representation of HID host state code.
 *
 * Returns          void
 *
 ******************************************************************************/
static const char* bta_hh_state_code(tBTA_HH_STATE state_code) {
  switch (state_code) {
    case BTA_HH_NULL_ST:
      return "BTA_HH_NULL_ST";
    case BTA_HH_IDLE_ST:
      return "BTA_HH_IDLE_ST";
    case BTA_HH_W4_CONN_ST:
      return "BTA_HH_W4_CONN_ST";
    case BTA_HH_CONN_ST:
      return "BTA_HH_CONN_ST";
    case BTA_HH_W4_SEC:
      return "BTA_HH_W4_SEC";
    default:
      return "unknown HID Host state";
  }
}

/* Finds the related control block, if any */
static tBTA_HH_DEV_CB* bta_hh_find_cb_by_event(const BT_HDR_RIGID* p_msg) {
  tBTA_HH_DEV_CB* p_cb = nullptr;

  if (p_msg->event == BTA_HH_API_OPEN_EVT) {
    // Connection requested, find or allocate the control block
    p_cb = bta_hh_get_cb(((tBTA_HH_API_CONN*)p_msg)->link_spec);
  } else if (p_msg->event == BTA_HH_API_MAINT_DEV_EVT) {
    if (((tBTA_HH_MAINT_DEV*)p_msg)->sub_event == BTA_HH_ADD_DEV_EVT) {
      // Device is being added, find or allocate the control block
      p_cb = bta_hh_get_cb(((tBTA_HH_MAINT_DEV*)p_msg)->link_spec);
    } else /* else remove device by handle */ {
      p_cb = bta_hh_find_cb_by_handle((uint8_t)p_msg->layer_specific);
      /* If BT disable is done while the HID device is connected and
       * Link_Key uses unauthenticated combination
       * then we can get into a situation where remove_bonding is called
       * with the index set to 0 (without getting
       * cleaned up). Only when VIRTUAL_UNPLUG is called do we cleanup the
       * index and make it MAX_KNOWN.
       * So if REMOVE_DEVICE is called and in_use is false then we should
       * treat this as a NULL p_cb. Hence we
       * force the index to be IDX_INVALID
       */
      if (p_cb != nullptr && !p_cb->in_use) {
        log::warn("Control block getting removed, device: {}, index: {}, handle: {}",
                  p_cb->link_spec, p_cb->index, p_cb->hid_handle);
        p_cb = nullptr;
      }
    }
  } else if (p_msg->event == BTA_HH_INT_OPEN_EVT) {
    p_cb = bta_hh_get_cb(((tBTA_HH_CBACK_DATA*)p_msg)->link_spec);
  } else {
    p_cb = bta_hh_find_cb_by_handle((uint8_t)p_msg->layer_specific);
  }

  return p_cb;
}

/* Handles events related to connection control blocks */
void bta_hh_sm_execute(tBTA_HH_DEV_CB* p_cb, tBTA_HH_INT_EVT event, const tBTA_HH_DATA* p_data) {
  tBTA_HH_STATE in_state = p_cb->state;
  if (p_cb->state == BTA_HH_NULL_ST || p_cb->state >= BTA_HH_INVALID_ST) {
    log::error("Invalid state State:{}, Event:{} for {}", bta_hh_state_code(in_state),
               bta_hh_evt_code(event), p_cb->link_spec);
    return;
  }

  bool unexpected_event = false;
  log::verbose("State {}, Event {} for {}", bta_hh_state_code(in_state), bta_hh_evt_code(event),
               p_cb->link_spec);

  switch (in_state) {
    case BTA_HH_IDLE_ST:
      switch (event) {
        case BTA_HH_API_OPEN_EVT:
          p_cb->state = BTA_HH_W4_CONN_ST;
          bta_hh_connect(p_cb, p_data);
          break;
        case BTA_HH_INT_OPEN_EVT:
          p_cb->state = BTA_HH_W4_CONN_ST;
          bta_hh_open_act(p_cb, p_data);
          break;
        case BTA_HH_INT_CLOSE_EVT:
          bta_hh_open_failure(p_cb, p_data);
          break;
        case BTA_HH_API_MAINT_DEV_EVT:
          bta_hh_maint_dev_act(p_cb, p_data);
          break;
        case BTA_HH_OPEN_CMPL_EVT:
          p_cb->state = BTA_HH_CONN_ST;
          bta_hh_open_cmpl_act(p_cb, p_data);
          break;
        case BTA_HH_GATT_OPEN_EVT:
          p_cb->state = BTA_HH_W4_CONN_ST;
          bta_hh_gatt_open(p_cb, p_data);
          break;
        default:
          unexpected_event = true;
          break;
      }
      break;
    case BTA_HH_W4_CONN_ST:
      switch (event) {
        case BTA_HH_API_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          break;
        case BTA_HH_INT_OPEN_EVT:
          bta_hh_open_act(p_cb, p_data);
          break;
        case BTA_HH_INT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_open_failure(p_cb, p_data);
          break;
        case BTA_HH_SDP_CMPL_EVT:
          bta_hh_sdp_cmpl(p_cb, p_data);
          break;
        case BTA_HH_API_WRITE_DEV_EVT:
          bta_hh_write_dev_act(p_cb, p_data);
          break;
        case BTA_HH_API_MAINT_DEV_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_maint_dev_act(p_cb, p_data);
          break;
        case BTA_HH_OPEN_CMPL_EVT:
          p_cb->state = BTA_HH_CONN_ST;
          bta_hh_open_cmpl_act(p_cb, p_data);
          break;
        case BTA_HH_GATT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_le_open_fail(p_cb, p_data);
          break;
        case BTA_HH_GATT_OPEN_EVT:
          bta_hh_gatt_open(p_cb, p_data);
          break;
        case BTA_HH_START_ENC_EVT:
          p_cb->state = BTA_HH_W4_SEC;
          bta_hh_start_security(p_cb, p_data);
          break;
        default:
          unexpected_event = true;
          break;
      }
      break;
    case BTA_HH_CONN_ST:
      switch (event) {
        case BTA_HH_API_CLOSE_EVT:
          bta_hh_api_disc_act(p_cb, p_data);
          break;
        case BTA_HH_INT_OPEN_EVT:
          bta_hh_open_act(p_cb, p_data);
          break;
        case BTA_HH_INT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_close_act(p_cb, p_data);
          break;
        case BTA_HH_INT_DATA_EVT:
          bta_hh_data_act(p_cb, p_data);
          break;
        case BTA_HH_INT_CTRL_DATA:
          bta_hh_ctrl_dat_act(p_cb, p_data);
          break;
        case BTA_HH_INT_HANDSK_EVT:
          bta_hh_handsk_act(p_cb, p_data);
          break;
        case BTA_HH_API_WRITE_DEV_EVT:
          bta_hh_write_dev_act(p_cb, p_data);
          break;
        case BTA_HH_API_GET_DSCP_EVT:
          bta_hh_get_dscp_act(p_cb, p_data);
          break;
        case BTA_HH_API_MAINT_DEV_EVT:
          bta_hh_maint_dev_act(p_cb, p_data);
          break;
        case BTA_HH_GATT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_gatt_close(p_cb, p_data);
          break;
        default:
          unexpected_event = true;
          break;
      }
      break;
    case BTA_HH_W4_SEC:
      switch (event) {
        case BTA_HH_API_CLOSE_EVT:
          bta_hh_api_disc_act(p_cb, p_data);
          break;
        case BTA_HH_INT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_open_failure(p_cb, p_data);
          break;
        case BTA_HH_API_MAINT_DEV_EVT:
          bta_hh_maint_dev_act(p_cb, p_data);
          break;
        case BTA_HH_GATT_CLOSE_EVT:
          p_cb->state = BTA_HH_IDLE_ST;
          bta_hh_le_open_fail(p_cb, p_data);
          break;
        case BTA_HH_ENC_CMPL_EVT:
          p_cb->state = BTA_HH_W4_CONN_ST;
          bta_hh_security_cmpl(p_cb, p_data);
          break;
        case BTA_HH_GATT_ENC_CMPL_EVT:
          bta_hh_le_notify_enc_cmpl(p_cb, p_data);
          break;
        default:
          unexpected_event = true;
          break;
      }
      break;
  }

  if (unexpected_event) {
    log::warn("Unexpected event event {} in state {} for {}", bta_hh_evt_code(event),
              bta_hh_state_code(in_state), p_cb->link_spec);
  } else if (in_state != p_cb->state) {
    log::debug("State Change: [{}] -> [{}] after Event [{}]", bta_hh_state_code(in_state),
               bta_hh_state_code(p_cb->state), bta_hh_evt_code(event));
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_hdl_failure
 *
 * Description      Handler for state machine failures
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_hdl_failure(tBTA_HH_INT_EVT event, const tBTA_HH_DATA* p_data) {
  if (bta_hh_cb.p_cback == nullptr) {
    log::error("No callback handler");
    return;
  }

  log::verbose("Event:{}", bta_hh_evt_code(event));
  tBTA_HH cback_data = {};
  tBTA_HH_EVT cback_event = BTA_HH_EMPTY_EVT;
  switch (event) {
    /* no control block available for new connection */
    case BTA_HH_API_OPEN_EVT:
      cback_event = BTA_HH_OPEN_EVT;
      /* build cback data */
      cback_data.conn.link_spec = ((tBTA_HH_API_CONN*)p_data)->link_spec;
      cback_data.conn.status = BTA_HH_ERR_DB_FULL;
      cback_data.conn.handle = BTA_HH_INVALID_HANDLE;
      break;
    /* DB full, BTA_HhAddDev */
    case BTA_HH_API_MAINT_DEV_EVT:
      cback_event = p_data->api_maintdev.sub_event;

      if (p_data->api_maintdev.sub_event == BTA_HH_ADD_DEV_EVT) {
        cback_data.dev_info.link_spec = p_data->api_maintdev.link_spec;
        cback_data.dev_info.status = BTA_HH_ERR_DB_FULL;
        cback_data.dev_info.handle = BTA_HH_INVALID_HANDLE;
      } else {
        cback_data.dev_info.status = BTA_HH_ERR_HDL;
        cback_data.dev_info.handle = (uint8_t)p_data->api_maintdev.hdr.layer_specific;
      }
      break;
    case BTA_HH_API_WRITE_DEV_EVT:
      cback_event = (p_data->api_sndcmd.t_type - HID_TRANS_GET_REPORT) + BTA_HH_GET_RPT_EVT;
      osi_free_and_reset((void**)&p_data->api_sndcmd.p_data);
      if (p_data->api_sndcmd.t_type == HID_TRANS_SET_PROTOCOL ||
          p_data->api_sndcmd.t_type == HID_TRANS_SET_REPORT ||
          p_data->api_sndcmd.t_type == HID_TRANS_SET_IDLE) {
        cback_data.dev_status.status = BTA_HH_ERR_HDL;
        cback_data.dev_status.handle = (uint8_t)p_data->api_sndcmd.hdr.layer_specific;
      } else if (p_data->api_sndcmd.t_type != HID_TRANS_DATA &&
                 p_data->api_sndcmd.t_type != HID_TRANS_CONTROL) {
        cback_data.hs_data.handle = (uint8_t)p_data->api_sndcmd.hdr.layer_specific;
        cback_data.hs_data.status = BTA_HH_ERR_HDL;
        /* hs_data.rsp_data will be all zero, which is not valid value */
      } else if (p_data->api_sndcmd.t_type == HID_TRANS_CONTROL &&
                 p_data->api_sndcmd.param == BTA_HH_CTRL_VIRTUAL_CABLE_UNPLUG) {
        cback_data.status = BTA_HH_ERR_HDL;
        cback_event = BTA_HH_VC_UNPLUG_EVT;
      } else {
        cback_event = 0;
      }
      break;

    case BTA_HH_API_CLOSE_EVT:
      cback_event = BTA_HH_CLOSE_EVT;
      cback_data.dev_status.status = BTA_HH_ERR_HDL;
      cback_data.dev_status.handle = (uint8_t)p_data->api_sndcmd.hdr.layer_specific;
      break;

    default:
      /* Likely an invalid handle, call bad API event */
      log::error("wrong device handle:{} in event:{}", p_data->hdr.layer_specific,
                 bta_hh_evt_code(event));
      /* Free the callback buffer now */
      if (p_data != nullptr) {
        osi_free_and_reset((void**)&p_data->hid_cback.p_data);
      }
      break;
  }

  if (cback_event) {
    (*bta_hh_cb.p_cback)(cback_event, &cback_data);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_hdl_event
 *
 * Description      HID host main event handling function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
bool bta_hh_hdl_event(const BT_HDR_RIGID* p_msg) {
  tBTA_HH_DEV_CB* p_cb = bta_hh_find_cb_by_event(p_msg);
  tBTA_HH_INT_EVT event = static_cast<tBTA_HH_INT_EVT>(p_msg->event);

  if (p_cb != nullptr) {
    bta_hh_sm_execute(p_cb, event, (tBTA_HH_DATA*)p_msg);
  } else {
    bta_hh_hdl_failure(event, (tBTA_HH_DATA*)p_msg);
  }

  return true;
}

#define DUMPSYS_TAG "shim::legacy::hid"
void bta_hh_dump(int fd) {
  for (auto dev : bta_hh_cb.kdev) {
    if (dev.in_use) {
      LOG_DUMPSYS(fd, "[%d] Device:%s, handle:%d, state:%s, sub class:%d, ", dev.index,
                  dev.link_spec.ToRedactedStringForLogging().c_str(), dev.hid_handle,
                  bta_hh_state_code(dev.state), dev.sub_class);
    }
  }
}
#undef DUMPSYS_TAG
