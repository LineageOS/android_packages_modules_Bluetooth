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
 *  This file contains the HID host action functions.
 *
 ******************************************************************************/

#define LOG_TAG "bt_bta_hh"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_transport.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#include "bta/hh/bta_hh_int.h"
#include "bta/include/bta_hh_api.h"
#include "bta/include/bta_hh_co.h"
#include "bta/sys/bta_sys.h"
#include "bta_api.h"
#include "bta_gatt_api.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/hiddefs.h"
#include "stack/include/hidh_api.h"
#include "stack/include/sdp_api.h"
#include "stack/include/sdp_device_id.h"
#include "stack/include/sdp_discovery_db.h"
#include "stack/include/sdp_status.h"

using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

/*****************************************************************************
 *  Constants
 ****************************************************************************/

namespace {

constexpr char kBtmLogTag[] = "HIDH";

}

/*****************************************************************************
 *  Local Function prototypes
 ****************************************************************************/
static void bta_hh_cback(uint8_t dev_handle, const RawAddress& addr, uint8_t event, uint32_t data,
                         BT_HDR* pdata);
static bthh_status_t bta_hh_get_trans_status(uint32_t result);

static const char* bta_hh_hid_event_name(uint16_t event);

/*****************************************************************************
 *  Action Functions
 ****************************************************************************/
/*******************************************************************************
 *
 * Function         bta_hh_api_enable
 *
 * Description      Perform necessary operations to enable HID host.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_api_enable(tBTA_HH_CBACK* p_cback, bool enable_hid, bool enable_hogp) {
  bthh_status_t status = BTHH_OK;
  uint8_t xx;

  /* initialize BTE HID */
  HID_HostInit();

  memset(&bta_hh_cb, 0, sizeof(tBTA_HH_CB));

  /* store parameters */
  bta_hh_cb.p_cback = p_cback;
  /* initialize device CB */
  for (xx = 0; xx < BTA_HH_MAX_DEVICE; xx++) {
    bta_hh_cb.kdev[xx].state = BTA_HH_IDLE_ST;
    bta_hh_cb.kdev[xx].hid_handle = BTA_HH_INVALID_HANDLE;
    bta_hh_cb.kdev[xx].index = xx;
  }

  /* initialize control block map */
  for (xx = 0; xx < BTA_HH_MAX_KNOWN; xx++) {
    bta_hh_cb.cb_index[xx] = BTA_HH_IDX_INVALID;
  }

  if (enable_hid) {
    /* Register with L2CAP */
    if (HID_HostRegister(bta_hh_cback) != HID_SUCCESS) {
      status = BTHH_ERR;
    }
  }

  if (status == BTHH_OK && enable_hogp) {
    bta_hh_le_enable();
  } else {
    /* signal BTA call back event */
    tBTA_HH bta_hh;
    bta_hh.status = status;
    if (status != BTHH_OK) {
      log::error("Failed to register, status:{}", status);
    }
    if (bta_hh_cb.p_cback) {
      (*bta_hh_cb.p_cback)(BTA_HH_ENABLE_EVT, &bta_hh);
    }
  }
}
/*******************************************************************************
 *
 * Function         bta_hh_api_disable
 *
 * Description      Perform necessary operations to disable HID host.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_api_disable(void) {
  uint8_t xx;

  /* service is not enabled */
  if (bta_hh_cb.p_cback == NULL) {
    return;
  }

  /* no live connection, signal DISC_CMPL_EVT directly */
  if (!bta_hh_cb.cnt_num) {
    bta_hh_disc_cmpl();
  } else /* otherwise, disconnect all live connections */
  {
    bta_hh_cb.w4_disable = true;

    for (xx = 0; xx < BTA_HH_MAX_DEVICE; xx++) {
      /* send API_CLOSE event to every connected device */
      if (bta_hh_cb.kdev[xx].state == BTA_HH_CONN_ST) {
        /* disconnect all connected devices */
        bta_hh_sm_execute(&bta_hh_cb.kdev[xx], BTA_HH_API_CLOSE_EVT, NULL);
      }
    }
  }

  return;
}

/*******************************************************************************
 *
 * Function         bta_hh_disc_cmpl
 *
 * Description      All connections have been closed, disable service.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_disc_cmpl(void) {
  log::debug("Disconnect complete");
  bthh_status_t status = BTHH_OK;

  /* Deregister with lower layer */
  if (HID_HostDeregister() != HID_SUCCESS) {
    status = BTHH_ERR;
  }

  if (bta_hh_cb.gatt_if != BTA_GATTS_INVALID_IF) {
    log::debug("Deregister HOGP host before cleanup");
    bta_hh_le_deregister();
  } else {
    bta_hh_cleanup_disable(status);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_sdp_cback
 *
 * Description      SDP callback function.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_sdp_cback(const RawAddress& bd_addr, tSDP_STATUS result, uint16_t attr_mask,
                             tHID_DEV_SDP_INFO* sdp_rec) {
  bthh_status_t status = BTHH_ERR_SDP;
  AclLinkSpec link_spec = {
          .addrt = {.type = BLE_ADDR_PUBLIC, .bda = bd_addr},
          .transport = BT_TRANSPORT_BR_EDR,
  };
  tBTA_HH_DEV_CB* p_cb = bta_hh_find_cb(link_spec);
  if (p_cb == nullptr) {
    log::error("Unknown device {}", bd_addr);
    return;
  }

  if (result == tSDP_STATUS::SDP_SUCCESS) {
    /* security is required for the connection, add attr_mask bit*/
    attr_mask |= HID_SEC_REQUIRED;

    log::verbose("Device:{} result:0x{:02x}, attr_mask:0x{:02x}, handle:0x{:x}", bd_addr, result,
                 attr_mask, p_cb->hid_handle);

    /* check to see type of device is supported , and should not been added
     * before */
    if (bta_hh_tod_spt(p_cb, sdp_rec->sub_class)) {
      uint8_t hdl = 0;
      /* if not added before */
      if (p_cb->hid_handle == BTA_HH_INVALID_HANDLE) {
        /*  add device/update attr_mask information */
        if (HID_HostAddDev(p_cb->link_spec.addrt.bda, attr_mask, &hdl) == HID_SUCCESS) {
          status = BTHH_OK;
          /* update cb_index[] map */
          bta_hh_cb.cb_index[hdl] = p_cb->index;
        } else {
          p_cb->app_id = 0;
        }
      } else {
        hdl = p_cb->hid_handle;
      }
      /* else : incoming connection after SDP should update the SDP information
       * as well */

      if (p_cb->app_id != 0) {
        /* update cb information with attr_mask, dscp_info etc. */
        bta_hh_add_device_to_list(p_cb, hdl, attr_mask, &sdp_rec->dscp_info, sdp_rec->sub_class,
                                  sdp_rec->ssr_max_latency, sdp_rec->ssr_min_tout, p_cb->app_id);

        p_cb->dscp_info.ctry_code = sdp_rec->ctry_code;

        status = BTHH_OK;
      }

    } else { /* type of device is not supported */
      status = BTHH_ERR_TOD_UNSPT;
    }
  }

  /* free disc_db when SDP is completed */
  osi_free_and_reset((void**)&p_cb->p_disc_db);

  /* send SDP_CMPL_EVT into state machine */
  tBTA_HH_DATA bta_hh_data;
  bta_hh_data.status = status;
  bta_hh_sm_execute(p_cb, BTA_HH_SDP_CMPL_EVT, &bta_hh_data);
}
/*******************************************************************************
 *
 * Function         bta_hh_di_sdp_cback
 *
 * Description      SDP DI callback function.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_di_sdp_cback(const RawAddress& bd_addr, tSDP_RESULT result) {
  bthh_status_t status = BTHH_ERR_SDP;
  AclLinkSpec link_spec = {
          .addrt = {.type = BLE_ADDR_PUBLIC, .bda = bd_addr},
          .transport = BT_TRANSPORT_BR_EDR,
  };
  tBTA_HH_DEV_CB* p_cb = bta_hh_find_cb(link_spec);
  if (p_cb == nullptr) {
    log::error("Unknown device {}", bd_addr);
    return;
  }

  log::verbose("device:{} result:0x{:02x}", bd_addr, result);

  /* if DI record does not exist on remote device, vendor_id in
   * tBTA_HH_DEV_DSCP_INFO will be set to 0xffff and we will allow the
   * connection to go through. Spec mandates that DI record be set, but many
   * HID devices do not set this. So for IOP purposes, we allow the connection
   * to go through and update the DI record to invalid DI entry.
   */
  if (result == tSDP_STATUS::SDP_SUCCESS || result == tSDP_STATUS::SDP_NO_RECS_MATCH) {
    if (result == tSDP_STATUS::SDP_SUCCESS &&
        get_legacy_stack_sdp_api()->SDP_GetNumDiRecords(p_cb->p_disc_db) != 0) {
      tSDP_DI_GET_RECORD di_rec;

      /* always update information with primary DI record */
      if (get_legacy_stack_sdp_api()->SDP_GetDiRecord(1, &di_rec, p_cb->p_disc_db) ==
          tSDP_STATUS::SDP_SUCCESS) {
        bta_hh_update_di_info(p_cb, di_rec.rec.vendor, di_rec.rec.product, di_rec.rec.version, 0,
                              0);
      }

    } else /* no DI record available */ {
      bta_hh_update_di_info(p_cb, BTA_HH_VENDOR_ID_INVALID, 0, 0, 0, 0);
    }

    tHID_STATUS ret = HID_HostGetSDPRecord(p_cb->link_spec.addrt.bda, p_cb->p_disc_db,
                                           p_bta_hh_cfg->sdp_db_size, bta_hh_sdp_cback);
    if (ret == HID_SUCCESS) {
      status = BTHH_OK;
    } else {
      log::warn("failure Status 0x{:2x}", ret);
    }
  }

  if (status != BTHH_OK) {
    osi_free_and_reset((void**)&p_cb->p_disc_db);
    /* send SDP_CMPL_EVT into state machine */
    tBTA_HH_DATA bta_hh_data;
    bta_hh_data.status = status;
    bta_hh_sm_execute(p_cb, BTA_HH_SDP_CMPL_EVT, &bta_hh_data);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_start_sdp
 *
 * Description      Start SDP service search, and obtain necessary SDP records.
 *                  Only one SDP service search request is allowed at the same
 *                  time. For every BTA_HhOpen API call, do SDP first unless SDP
 *                  has been done previously.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_start_sdp(tBTA_HH_DEV_CB* p_cb) {
  if (p_cb->p_disc_db != nullptr) {
    /* Incoming/outgoing collision case. DUT initiated HID connection at the
     * same time as the remote connected HID control channel.
     * When flow reaches here due to remote initiated connection, DUT may be
     * doing SDP. In such case, just do nothing and the ongoing SDP completion
     * or failure will handle this case.
     */
    log::warn("Ignoring as SDP already in progress");
    return;
  }

  p_cb->p_disc_db = (tSDP_DISCOVERY_DB*)osi_malloc(p_bta_hh_cfg->sdp_db_size);

  /* Do DI discovery first */
  if (get_legacy_stack_sdp_api()->SDP_DiDiscover(p_cb->link_spec.addrt.bda, p_cb->p_disc_db,
                                                 p_bta_hh_cfg->sdp_db_size,
                                                 bta_hh_di_sdp_cback) == tSDP_STATUS::SDP_SUCCESS) {
    // SDP search started successfully. Connection will be triggered at the end of successful SDP
    // search
  } else {
    log::error("SDP_DiDiscover failed");

    osi_free_and_reset((void**)&p_cb->p_disc_db);

    tBTA_HH_DATA bta_hh_data;
    bta_hh_data.status = BTHH_ERR_SDP;
    bta_hh_sm_execute(p_cb, BTA_HH_SDP_CMPL_EVT, &bta_hh_data);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_sdp_cmpl
 *
 * Description      When SDP completes, initiate a connection or report an error
 *                  depending on the SDP result.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_sdp_cmpl(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  log::assert_that(p_data != nullptr, "assert failed: p_data != nullptr");
  bthh_status_t status = p_data->status;

  /* if SDP compl success */
  if (status == BTHH_OK) {
    /* not incoming connection doing SDP, initiate a HID connection */
    if (!p_cb->incoming_conn) {
      /* open HID connection */
      tHID_STATUS ret = HID_HostOpenDev(p_cb->hid_handle);
      if (ret == HID_SUCCESS || ret == HID_ERR_ALREADY_CONN) {
        log::verbose("HID_HostOpenDev returned={}", hid_status_text(ret));
        status = BTHH_OK;
      } else if (ret == HID_ERR_CONN_IN_PROCESS) {
        /* Connection already in progress, return from here, SDP will be performed after connection
         * is completed */
        log::debug("connection already in progress for {}", p_cb->link_spec);
        return;
      } else {
        log::warn("HID_HostOpenDev failed: Status {}", hid_status_text(ret));
        /* open fail, remove device from management device list */
        HID_HostRemoveDev(p_cb->hid_handle);
        status = BTHH_ERR;
      }
    } else { // incoming connection SDP finished
      bta_hh_sm_execute(p_cb, BTA_HH_OPEN_CMPL_EVT, NULL);
    }
  } else {
    log::warn("{} status {}", p_cb->link_spec, p_data->status);
  }

  if (status != BTHH_OK) {
    log::warn("SDP failed for {} status:{} incoming:{}, conn hndl:{}, app_id:{}", p_cb->link_spec,
              status, p_cb->incoming_conn, p_cb->incoming_hid_handle, p_cb->app_id);
    /* Check if this was incoming connection request from an unknown device and connection failed
     * due to missing HID Device SDP UUID. In such condition, disconnect the link as well as remove
     * the device from list of HID devices */
    if (status == BTHH_ERR_SDP && p_cb->incoming_conn && p_cb->app_id == 0) {
      HID_HostRemoveDev(p_cb->incoming_hid_handle);
    }

    tBTA_HH_CONN conn_dat = {
            .link_spec = p_cb->link_spec, .status = status, .handle = p_cb->hid_handle};
    (*bta_hh_cb.p_cback)(BTA_HH_OPEN_EVT, (tBTA_HH*)&conn_dat);

    /* move state machine W4_CONN ->IDLE */
    bta_hh_sm_execute(p_cb, BTA_HH_API_CLOSE_EVT, NULL);

    /* if this is an outgoing connection to an unknown device, clean up cb */
    if (p_cb->app_id == 0 && !p_cb->incoming_conn) {
      /* clean up device control block */
      bta_hh_clean_up_kdev(p_cb);
    }
    bta_hh_trace_dev_db();
  }
  p_cb->incoming_conn = false;
  p_cb->incoming_hid_handle = BTA_HH_INVALID_HANDLE;
}

/*******************************************************************************
 *
 * Function         bta_hh_bredr_conn
 *
 * Description      Initiate BR/EDR HID connection. This may be triggered by
 *                  the local application or as a result of remote initiated
 *                  HID connection.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_bredr_conn(tBTA_HH_DEV_CB* p_cb) {
  if (p_cb->app_id == 0) {
    log::debug("Starting SDP for new device {}", p_cb->link_spec);
    bta_hh_start_sdp(p_cb);
    return;
  }

  tBTA_HH_DATA bta_hh_data = {.status = BTHH_OK};
  if (p_cb->hid_handle != BTA_HH_INVALID_HANDLE) {
    log::warn("Already connected to {}, handle: {}", p_cb->link_spec, p_cb->hid_handle);
    bta_hh_sm_execute(p_cb, BTA_HH_SDP_CMPL_EVT, &bta_hh_data);
    return;
  }

  log::verbose("Reconnecting to {} app_id: {}", p_cb->link_spec, p_cb->app_id);
  uint8_t hdl;
  if (HID_HostAddDev(p_cb->link_spec.addrt.bda, p_cb->attr_mask, &hdl) == HID_SUCCESS) {
    /* Update device CB with newly register device handle */
    bta_hh_add_device_to_list(p_cb, hdl, p_cb->attr_mask, nullptr, p_cb->sub_class,
                              p_cb->dscp_info.ssr_max_latency, p_cb->dscp_info.ssr_min_tout,
                              p_cb->app_id);
    bta_hh_cb.cb_index[hdl] = p_cb->index;
  } else {
    bta_hh_data.status = BTHH_ERR_NO_RES;
    log::warn("Failed to initiated connection to {}", p_cb->link_spec);
  }

  bta_hh_sm_execute(p_cb, BTA_HH_SDP_CMPL_EVT, &bta_hh_data);
}

/*******************************************************************************
 *
 * Function         bta_hh_le_connect_upgrade
 *
 * Description      Upgrade ongoing LE background connection to direct
 *                  connection. This function is called only in
 *                  BTA_HH_W4_CONN_ST state.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_connect_upgrade(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  const tBTA_HH_API_CONN& api_conn = p_data->api_conn;
  if (api_conn.link_spec.transport != BT_TRANSPORT_LE || !api_conn.direct) {
    log::info("Already connecting to {}", api_conn.link_spec);
    return;
  }

  log::info("Upgrading to direct connection {}", api_conn.link_spec);
  p_cb->mode = p_data->api_conn.mode;
  bta_hh_le_open_conn(p_cb, true);
}

/*******************************************************************************
 *
 * Function         bta_hh_connect
 *
 * Description      Start HID host connection.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_connect(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  p_cb->mode = p_data->api_conn.mode;

  // Initiate HID host connection
  if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
    bta_hh_le_open_conn(p_cb, p_data->api_conn.direct);
  } else {
    bta_hh_bredr_conn(p_cb);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_cancel_connect
 *
 * Description      Cancel HID host connection.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_cancel_connect(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* /* p_data */) {
  // Only applicable to LE background connection for now.
  if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
    bta_hh_le_remove_dev_bg_conn(p_cb);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_api_disc_act
 *
 * Description      HID Host initiate a disconnection.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_api_disc_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  log::assert_that(p_cb != nullptr, "assert failed: p_cb != nullptr");

  if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
    log::debug("Host initiating close to le device:{}", p_cb->link_spec);
    if (p_data != nullptr) {
      p_cb->status = p_data->api_close.status;
    }
    bta_hh_le_api_disc_act(p_cb);
    return;
  }

  bthh_status_t status = p_data != nullptr ? p_data->api_close.status : BTHH_OK;
  uint8_t hid_handle = (p_data != nullptr)
                               ? static_cast<uint8_t>(p_data->api_close.hdr.layer_specific)
                               : p_cb->hid_handle;
  tHID_STATUS result = HID_HostCloseDev(hid_handle);

  if (result != HID_SUCCESS) {
    log::warn("Failed closing classic device:{} status:{}", p_cb->link_spec,
              hid_status_text(result));
    if (status == BTHH_OK) {
      status = BTHH_ERR;
    }
  } else {
    log::debug("Host initiated close to classic device:{}", p_cb->link_spec);
  }

  tBTA_HH bta_hh = {
          .dev_status = {.status = status, .handle = hid_handle},
  };

  (*bta_hh_cb.p_cback)(BTA_HH_CLOSE_EVT, &bta_hh);
}

/*******************************************************************************
 *
 * Function         bta_hh_open_cmpl_act
 *
 * Description      HID host connection completed
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_open_cmpl_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  uint8_t dev_handle = p_data ? (uint8_t)p_data->hid_cback.hdr.layer_specific : p_cb->hid_handle;
  tBTA_HH_CONN conn = {
          .link_spec = p_cb->link_spec,
          .status = p_cb->status,
          .handle = dev_handle,
          .scps_supported = p_cb->scps_supported,
          .sub_class = p_cb->sub_class,
          .attr_mask = p_cb->attr_mask,
          .app_id = p_cb->app_id,
  };

  p_cb->status = BTHH_OK;  // Reset status since it has been used now
  bta_hh_cb.cnt_num++;     // Increment connection count

  BTM_LogHistory(kBtmLogTag, p_cb->link_spec.addrt.bda, "Opened",
                 std::format("{} initiator:{}", bt_transport_text(p_cb->link_spec.transport),
                             (p_cb->incoming_conn) ? "remote" : "local"));

  if (p_cb->link_spec.transport != BT_TRANSPORT_LE) {
    /* inform role manager */
    bta_sys_conn_open(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);

    /* set protocol mode when not default report mode */
    if (p_cb->mode != BTA_HH_PROTO_RPT_MODE) {
      tHID_STATUS status = HID_HostWriteDev(dev_handle, HID_TRANS_SET_PROTOCOL,
                                            HID_PAR_PROTOCOL_BOOT_MODE, 0, 0, NULL);

      if (status == HID_SUCCESS) {
        p_cb->w4_evt = BTA_HH_SET_PROTO_EVT;
      } else {
        /* HID connection is up, while SET_PROTO fail */
        conn.status = BTHH_ERR_PROTO;
      }
    }
  }
  p_cb->incoming_conn = false;
  p_cb->incoming_hid_handle = BTA_HH_INVALID_HANDLE;

  (*bta_hh_cb.p_cback)(BTA_HH_OPEN_EVT, (tBTA_HH*)&conn);
}
/*******************************************************************************
 *
 * Function         bta_hh_open_act
 *
 * Description      HID host receive HID_OPEN_EVT .
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_open_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  uint8_t dev_handle = p_data ? (uint8_t)p_data->hid_cback.hdr.layer_specific : p_cb->hid_handle;

  log::verbose("Device[{}] connected", dev_handle);

  /* SDP has been done */
  if (p_cb->app_id != 0) {
    bta_hh_sm_execute(p_cb, BTA_HH_OPEN_CMPL_EVT, p_data);
  } else
  /*  app_id == 0 indicates an incoming connection request arrives without SDP
   *  performed, do it first
   */
  {
    p_cb->incoming_conn = true;
    /* store the handle here in case sdp fails - need to disconnect */
    p_cb->incoming_hid_handle = dev_handle;

    bta_hh_bredr_conn(p_cb);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_data_act
 *
 * Description      HID Host process a data report
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_data_act(tBTA_HH_DEV_CB* /*p_cb*/, const tBTA_HH_DATA* p_data) {
  BT_HDR* pdata = p_data->hid_cback.p_data;
  uint8_t* p_rpt = (uint8_t*)(pdata + 1) + pdata->offset;

  bta_hh_co_data((uint8_t)p_data->hid_cback.hdr.layer_specific, p_rpt, pdata->len);

  osi_free_and_reset((void**)&pdata);
}

/*******************************************************************************
 *
 * Function         bta_hh_handsk_act
 *
 * Description      HID Host process a handshake acknowledgement.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_handsk_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  log::verbose("HANDSHAKE received for: event={} data={}", bta_hh_event_text(p_cb->w4_evt),
               p_data->hid_cback.data);

  tBTA_HH bta_hh;
  memset(&bta_hh, 0, sizeof(tBTA_HH));

  switch (p_cb->w4_evt) {
    /* GET_ transsaction, handshake indicate unsupported request */
    case BTA_HH_GET_PROTO_EVT:
      bta_hh.hs_data.rsp_data.proto_mode = BTA_HH_PROTO_UNKNOWN;
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    case BTA_HH_GET_RPT_EVT:
    case BTA_HH_GET_IDLE_EVT:
      bta_hh.hs_data.handle = p_cb->hid_handle;
      /* if handshake gives an OK code for these transaction, fill in UNSUPT */
      bta_hh.hs_data.status = bta_hh_get_trans_status(p_data->hid_cback.data);
      if (bta_hh.hs_data.status == BTHH_OK) {
        bta_hh.hs_data.status = BTHH_HS_TRANS_NOT_SPT;
      }
      (*bta_hh_cb.p_cback)(p_cb->w4_evt, &bta_hh);
      p_cb->w4_evt = BTA_HH_EMPTY_EVT;
      break;

    /* acknoledgement from HID device for SET_ transaction */
    case BTA_HH_SET_RPT_EVT:
    case BTA_HH_SET_PROTO_EVT:
    case BTA_HH_SET_IDLE_EVT:
      bta_hh.dev_status.handle = p_cb->hid_handle;
      bta_hh.dev_status.status = bta_hh_get_trans_status(p_data->hid_cback.data);
      (*bta_hh_cb.p_cback)(p_cb->w4_evt, &bta_hh);
      p_cb->w4_evt = BTA_HH_EMPTY_EVT;
      break;

    /* SET_PROTOCOL when open connection */
    case BTA_HH_OPEN_EVT:
      bta_hh.conn.status = p_data->hid_cback.data ? BTHH_ERR_PROTO : BTHH_OK;
      bta_hh.conn.handle = p_cb->hid_handle;
      bta_hh.conn.link_spec = p_cb->link_spec;
      (*bta_hh_cb.p_cback)(p_cb->w4_evt, &bta_hh);
      bta_hh_trace_dev_db();
      p_cb->w4_evt = BTA_HH_EMPTY_EVT;
      break;

    default:
      /* unknow transaction handshake response */
      log::verbose("unknown transaction type {}", bta_hh_event_text(p_cb->w4_evt));
      break;
  }

  /* transaction achknoledgement received, inform PM for mode change */
  bta_sys_idle(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
  return;
}
/*******************************************************************************
 *
 * Function         bta_hh_ctrl_dat_act
 *
 * Description      HID Host process a data report from control channel.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_ctrl_dat_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  BT_HDR* pdata = p_data->hid_cback.p_data;
  uint8_t* data = (uint8_t*)(pdata + 1) + pdata->offset;
  tBTA_HH_HSDATA hs_data;

  log::verbose("Ctrl DATA received w4: event[{}]", bta_hh_event_text(p_cb->w4_evt));
  if (pdata->len == 0) {
    p_cb->w4_evt = BTA_HH_EMPTY_EVT;
    osi_free_and_reset((void**)&pdata);
    return;
  }
  hs_data.status = BTHH_OK;
  hs_data.handle = p_cb->hid_handle;

  switch (p_cb->w4_evt) {
    case BTA_HH_GET_IDLE_EVT:
      hs_data.rsp_data.idle_rate = *data;
      break;
    case BTA_HH_GET_RPT_EVT:
      hs_data.rsp_data.p_rpt_data = pdata;
      break;
    case BTA_HH_GET_PROTO_EVT:
      /* match up BTE/BTA report/boot mode def*/
      hs_data.rsp_data.proto_mode =
              ((*data) == HID_PAR_PROTOCOL_REPORT) ? BTA_HH_PROTO_RPT_MODE : BTA_HH_PROTO_BOOT_MODE;
      log::verbose("GET_PROTOCOL Mode = [{}]",
                   (hs_data.rsp_data.proto_mode == BTA_HH_PROTO_RPT_MODE) ? "Report" : "Boot");
      break;
    /* should not expect control DATA for SET_ transaction */
    case BTA_HH_SET_PROTO_EVT:
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    case BTA_HH_SET_RPT_EVT:
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    case BTA_HH_SET_IDLE_EVT:
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    default:
      log::verbose("invalid  transaction type for DATA payload:4_evt[{}]",
                   bta_hh_event_text(p_cb->w4_evt));
      break;
  }

  /* inform PM for mode change */
  bta_sys_busy(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
  bta_sys_idle(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);

  (*bta_hh_cb.p_cback)(p_cb->w4_evt, (tBTA_HH*)&hs_data);

  p_cb->w4_evt = BTA_HH_EMPTY_EVT;
  osi_free_and_reset((void**)&pdata);
}

/*******************************************************************************
 *
 * Function         bta_hh_open_failure
 *
 * Description      report HID open failure when at wait for connection state
 *                  and receive device close event.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_open_failure(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  tBTA_HH_CONN conn_dat;
  uint32_t reason = p_data->hid_cback.data; /* Reason for closing (32-bit) */

  memset(&conn_dat, 0, sizeof(tBTA_HH_CONN));
  conn_dat.handle = p_cb->hid_handle;
  conn_dat.status = (reason == HID_ERR_AUTH_FAILED) ? BTHH_ERR_AUTH_FAILED : BTHH_ERR;
  conn_dat.link_spec = p_cb->link_spec;
  HID_HostCloseDev(p_cb->hid_handle);

  /* Report OPEN fail event */
  (*bta_hh_cb.p_cback)(BTA_HH_OPEN_EVT, (tBTA_HH*)&conn_dat);

  bta_hh_trace_dev_db();
  /* clean up control block, but retain SDP info and device handle */
  p_cb->vp = false;
  p_cb->w4_evt = 0;

  /* if no connection is active and HH disable is signaled, disable service */
  if (bta_hh_cb.cnt_num == 0 && bta_hh_cb.w4_disable) {
    bta_hh_disc_cmpl();
  }

  /* Error in opening hid connection, reset flags */
  p_cb->incoming_conn = false;
  p_cb->incoming_hid_handle = BTA_HH_INVALID_HANDLE;
}

/*******************************************************************************
 *
 * Function         bta_hh_close_act
 *
 * Description      HID Host process a close event
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_close_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  uint32_t reason = p_data->hid_cback.data; /* Reason for closing (32-bit) */
  const bool l2cap_conn_fail = reason & HID_L2CAP_CONN_FAIL;
  const bool l2cap_req_fail = reason & HID_L2CAP_REQ_FAIL;
  const bool l2cap_cfg_fail = reason & HID_L2CAP_CFG_FAIL;
  const tHID_STATUS hid_status = static_cast<tHID_STATUS>(reason & 0xff);

  /* if HID_HDEV_EVT_VC_UNPLUG was received, report BTA_HH_VC_UNPLUG_EVT */
  uint16_t event = p_cb->vp ? BTA_HH_VC_UNPLUG_EVT : BTA_HH_CLOSE_EVT;

  BTM_LogHistory(kBtmLogTag, p_cb->link_spec.addrt.bda, "Closed",
                 std::format("{} reason {} {} {} {}",
                             (p_cb->link_spec.transport == BT_TRANSPORT_LE) ? "le" : "classic",
                             hid_status_text(hid_status), l2cap_conn_fail ? "l2cap_conn_fail" : "",
                             l2cap_req_fail ? "l2cap_req_fail" : "",
                             l2cap_cfg_fail ? "l2cap_cfg_fail" : ""));

  /* inform role manager */
  bta_sys_conn_close(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
  /* update total conn number */
  bta_hh_cb.cnt_num--;

  tBTA_HH_CBDATA disc_dat = {.status = p_cb->status, .handle = p_cb->hid_handle};
  if (disc_dat.status == BTHH_OK && reason != 0) {
    disc_dat.status = BTHH_ERR;
  }
  p_cb->status = BTHH_OK;  // Reset status since it has been used now

  (*bta_hh_cb.p_cback)(event, (tBTA_HH*)&disc_dat);

  /* if virtually unplug, remove device */
  if (p_cb->vp) {
    HID_HostRemoveDev(p_cb->hid_handle);
    bta_hh_clean_up_kdev(p_cb);
  }

  bta_hh_trace_dev_db();

  /* clean up control block, but retain SDP info and device handle */
  p_cb->vp = false;
  p_cb->w4_evt = BTA_HH_EMPTY_EVT;

  /* if no connection is active and HH disable is signaled, disable service */
  if (bta_hh_cb.cnt_num == 0 && bta_hh_cb.w4_disable) {
    bta_hh_disc_cmpl();
  }

  return;
}

/*******************************************************************************
 *
 * Function         bta_hh_get_dscp_act
 *
 * Description      Get device report descriptor
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_get_dscp_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* /* p_data */) {
  if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
    if (p_cb->hid_srvc.state >= BTA_HH_SERVICE_DISCOVERED) {
      p_cb->dscp_info.hid_handle = p_cb->hid_handle;
    }
    bta_hh_le_get_dscp_act(p_cb);
  } else {
    p_cb->dscp_info.hid_handle = p_cb->hid_handle;
    (*bta_hh_cb.p_cback)(BTA_HH_GET_DSCP_EVT, (tBTA_HH*)&p_cb->dscp_info);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_maint_dev_act
 *
 * Description      HID Host maintain device list.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hh_maint_dev_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  const tBTA_HH_MAINT_DEV* p_dev_info = &p_data->api_maintdev;
  tBTA_HH_DEV_INFO dev_info;
  uint8_t dev_handle;

  dev_info.status = BTHH_ERR;
  dev_info.handle = BTA_HH_INVALID_HANDLE;

  switch (p_dev_info->sub_event) {
    case BTA_HH_ADD_DEV_EVT: /* add a device */
      dev_info.link_spec = p_dev_info->link_spec;
      /* initialize callback data */
      if (p_cb->hid_handle == BTA_HH_INVALID_HANDLE) {
        tBT_TRANSPORT transport = p_data->api_maintdev.link_spec.transport;
        if (transport == BT_TRANSPORT_LE) {
          p_cb->link_spec.transport = BT_TRANSPORT_LE;
          dev_info.handle = bta_hh_le_add_device(p_cb, p_dev_info);
          if (dev_info.handle != BTA_HH_INVALID_HANDLE) {
            dev_info.status = BTHH_OK;
          }
        } else if (transport == BT_TRANSPORT_BR_EDR) {
          if (HID_HostAddDev(p_dev_info->link_spec.addrt.bda, p_dev_info->attr_mask, &dev_handle) ==
              HID_SUCCESS) {
            dev_info.handle = dev_handle;
            dev_info.status = BTHH_OK;
            p_cb->link_spec.transport = BT_TRANSPORT_BR_EDR;

            /* update DI information */
            bta_hh_update_di_info(p_cb, p_dev_info->dscp_info.vendor_id,
                                  p_dev_info->dscp_info.product_id, p_dev_info->dscp_info.version,
                                  p_dev_info->dscp_info.flag, p_dev_info->dscp_info.ctry_code);

            /* add to BTA device list */
            bta_hh_add_device_to_list(p_cb, dev_handle, p_dev_info->attr_mask,
                                      &p_dev_info->dscp_info.descriptor, p_dev_info->sub_class,
                                      p_dev_info->dscp_info.ssr_max_latency,
                                      p_dev_info->dscp_info.ssr_min_tout, p_dev_info->app_id);
            /* update cb_index[] map */
            bta_hh_cb.cb_index[dev_handle] = p_cb->index;
          }
        } else {
          log::error("unexpected BT transport: {}", bt_transport_text(transport));
          break;
        }
      } else /* device already been added */
      {
        dev_info.handle = p_cb->hid_handle;
        dev_info.status = BTHH_OK;
      }
      bta_hh_trace_dev_db();

      break;
    case BTA_HH_RMV_DEV_EVT: /* remove device */
      dev_info.handle = (uint8_t)p_dev_info->hdr.layer_specific;
      dev_info.link_spec = p_cb->link_spec;

      if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
        bta_hh_le_remove_dev_bg_conn(p_cb);
        bta_hh_sm_execute(p_cb, BTA_HH_API_CLOSE_EVT, NULL);
        bta_hh_clean_up_kdev(p_cb);
      } else {
        if (HID_HostRemoveDev(dev_info.handle) == HID_SUCCESS) {
          dev_info.status = BTHH_OK;

          /* remove from known device list in BTA */
          bta_hh_clean_up_kdev(p_cb);
        } else {
          log::warn("Failed to remove device {}", dev_info.link_spec);
          bta_hh_clean_up_kdev(p_cb);
        }
      }
      break;

    default:
      log::warn("Invalid command {} for device {}", p_dev_info->sub_event, p_dev_info->link_spec);
      break;
  }

  (*bta_hh_cb.p_cback)(p_dev_info->sub_event, (tBTA_HH*)&dev_info);
}
/*******************************************************************************
 *
 * Function         bta_hh_write_dev_act
 *
 * Description      Write device action. can be SET/GET/DATA transaction.
 *
 * Returns          void
 *
 ******************************************************************************/
static uint8_t convert_api_sndcmd_param(const tBTA_HH_CMD_DATA& api_sndcmd) {
  uint8_t api_sndcmd_param = api_sndcmd.param;
  if (api_sndcmd.t_type == HID_TRANS_SET_PROTOCOL) {
    api_sndcmd_param = (api_sndcmd.param == BTA_HH_PROTO_RPT_MODE) ? HID_PAR_PROTOCOL_REPORT
                                                                   : HID_PAR_PROTOCOL_BOOT_MODE;
  }
  return api_sndcmd_param;
}

void bta_hh_write_dev_act(tBTA_HH_DEV_CB* p_cb, const tBTA_HH_DATA* p_data) {
  uint16_t event = (p_data->api_sndcmd.t_type - HID_TRANS_GET_REPORT) + BTA_HH_GET_RPT_EVT;

  if (p_cb->link_spec.transport == BT_TRANSPORT_LE) {
    bta_hh_le_write_dev_act(p_cb, p_data);
  } else {
    /* match up BTE/BTA report/boot mode def */
    const uint8_t api_sndcmd_param = convert_api_sndcmd_param(p_data->api_sndcmd);

    tHID_STATUS status = HID_HostWriteDev(p_cb->hid_handle, p_data->api_sndcmd.t_type,
                                          api_sndcmd_param, p_data->api_sndcmd.data,
                                          p_data->api_sndcmd.rpt_id, p_data->api_sndcmd.p_data);
    if (status != HID_SUCCESS) {
      log::error("HID_HostWriteDev Error, status:{}", status);

      if (p_data->api_sndcmd.t_type != HID_TRANS_CONTROL &&
          p_data->api_sndcmd.t_type != HID_TRANS_DATA) {
        BT_HDR cbhdr = {
                .event = BTA_HH_GET_RPT_EVT,
                .len = 0,
                .offset = 0,
                .layer_specific = 0,
        };
        tBTA_HH cbdata = {
                .hs_data =
                        {
                                .status = BTHH_ERR,
                                .handle = p_cb->hid_handle,
                                .rsp_data =
                                        {
                                                .p_rpt_data = &cbhdr,
                                        },
                        },
        };
        (*bta_hh_cb.p_cback)(event, &cbdata);
      } else if (api_sndcmd_param == BTA_HH_CTRL_VIRTUAL_CABLE_UNPLUG) {
        tBTA_HH cbdata = {
                .dev_status =
                        {
                                .status = BTHH_ERR,
                                .handle = p_cb->hid_handle,
                        },
        };
        (*bta_hh_cb.p_cback)(BTA_HH_VC_UNPLUG_EVT, &cbdata);
      } else {
        log::error(
                "skipped executing callback in hid host error handling. command "
                "type:{}, param:{}",
                p_data->api_sndcmd.t_type, p_data->api_sndcmd.param);
      }
    } else {
      switch (p_data->api_sndcmd.t_type) {
        case HID_TRANS_SET_PROTOCOL:
          FALLTHROUGH_INTENDED; /* FALLTHROUGH */
        case HID_TRANS_GET_REPORT:
          FALLTHROUGH_INTENDED; /* FALLTHROUGH */
        case HID_TRANS_SET_REPORT:
          FALLTHROUGH_INTENDED; /* FALLTHROUGH */
        case HID_TRANS_GET_PROTOCOL:
          FALLTHROUGH_INTENDED; /* FALLTHROUGH */
        case HID_TRANS_GET_IDLE:
          FALLTHROUGH_INTENDED;  /* FALLTHROUGH */
        case HID_TRANS_SET_IDLE: /* set w4_handsk event name for callback
                                    function use */
          p_cb->w4_evt = event;
          break;
        case HID_TRANS_DATA:    /* output report */
          FALLTHROUGH_INTENDED; /* FALLTHROUGH */
        case HID_TRANS_CONTROL:
          /* no handshake event will be generated */
          /* if VC_UNPLUG is issued, set flag */
          if (api_sndcmd_param == BTA_HH_CTRL_VIRTUAL_CABLE_UNPLUG) {
            p_cb->vp = true;
          }

          break;
        /* currently not expected */
        case HID_TRANS_DATAC:
        default:
          log::verbose("cmd type={}", p_data->api_sndcmd.t_type);
          break;
      }

      /* if not control type transaction, notify PM for energy control */
      if (p_data->api_sndcmd.t_type != HID_TRANS_CONTROL) {
        /* inform PM for mode change */
        bta_sys_busy(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
        bta_sys_idle(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
      } else if (api_sndcmd_param == BTA_HH_CTRL_SUSPEND) {
        bta_sys_sco_close(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
      } else if (api_sndcmd_param == BTA_HH_CTRL_EXIT_SUSPEND) {
        bta_sys_busy(BTA_ID_HH, p_cb->app_id, p_cb->link_spec.addrt.bda);
      }
    }
  }
  return;
}

/*****************************************************************************
 *  Static Function
 ****************************************************************************/
/*******************************************************************************
 *
 * Function         bta_hh_cback
 *
 * Description      BTA HH callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hh_cback(uint8_t dev_handle, const RawAddress& addr, uint8_t event, uint32_t data,
                         BT_HDR* pdata) {
  uint16_t sm_event = BTA_HH_INVALID_EVT;
  uint8_t xx = 0;

  log::verbose("HID_event [{}]", bta_hh_hid_event_name(event));

  switch (event) {
    case HID_HDEV_EVT_OPEN:
      sm_event = BTA_HH_INT_OPEN_EVT;
      break;
    case HID_HDEV_EVT_CLOSE:
      sm_event = BTA_HH_INT_CLOSE_EVT;
      break;
    case HID_HDEV_EVT_INTR_DATA:
      sm_event = BTA_HH_INT_DATA_EVT;
      break;
    case HID_HDEV_EVT_HANDSHAKE:
      sm_event = BTA_HH_INT_HANDSK_EVT;
      break;
    case HID_HDEV_EVT_CTRL_DATA:
      sm_event = BTA_HH_INT_CTRL_DATA;
      break;
    case HID_HDEV_EVT_RETRYING:
      break;
    case HID_HDEV_EVT_INTR_DATC:
    case HID_HDEV_EVT_CTRL_DATC:
      /* Unhandled events: Free buffer for DATAC */
      osi_free_and_reset((void**)&pdata);
      break;
    case HID_HDEV_EVT_VC_UNPLUG:
      for (xx = 0; xx < BTA_HH_MAX_DEVICE; xx++) {
        if (bta_hh_cb.kdev[xx].hid_handle == dev_handle) {
          bta_hh_cb.kdev[xx].vp = true;
          break;
        }
      }
      break;
  }

  if (sm_event != BTA_HH_INVALID_EVT) {
    tBTA_HH_CBACK_DATA* p_buf =
            (tBTA_HH_CBACK_DATA*)osi_malloc(sizeof(tBTA_HH_CBACK_DATA) + sizeof(BT_HDR));
    p_buf->hdr.event = sm_event;
    p_buf->hdr.layer_specific = (uint16_t)dev_handle;
    p_buf->data = data;
    p_buf->link_spec.addrt.bda = addr;
    p_buf->link_spec.addrt.type = BLE_ADDR_PUBLIC;
    p_buf->link_spec.transport = BT_TRANSPORT_BR_EDR;
    p_buf->p_data = pdata;

    bta_sys_sendmsg(p_buf);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_get_trans_status
 *
 * Description      translate a handshake result code into BTA HH
 *                  status code
 *
 ******************************************************************************/
static bthh_status_t bta_hh_get_trans_status(uint32_t result) {
  switch (result) {
    case HID_PAR_HANDSHAKE_RSP_SUCCESS: /*   (0) */
      return BTHH_OK;
    case HID_PAR_HANDSHAKE_RSP_NOT_READY:           /*   (1) */
    case HID_PAR_HANDSHAKE_RSP_ERR_INVALID_REP_ID:  /*   (2) */
    case HID_PAR_HANDSHAKE_RSP_ERR_UNSUPPORTED_REQ: /*   (3) */
    case HID_PAR_HANDSHAKE_RSP_ERR_INVALID_PARAM:   /*   (4) */
      return static_cast<bthh_status_t>(result);
    case HID_PAR_HANDSHAKE_RSP_ERR_UNKNOWN: /*   (14) */
    case HID_PAR_HANDSHAKE_RSP_ERR_FATAL:   /*   (15) */
    default:
      return BTHH_HS_ERROR;
      break;
  }
}
/*****************************************************************************
 *  Debug Functions
 ****************************************************************************/

static const char* bta_hh_hid_event_name(uint16_t event) {
  switch (event) {
    case HID_HDEV_EVT_OPEN:
      return "HID_HDEV_EVT_OPEN";
    case HID_HDEV_EVT_CLOSE:
      return "HID_HDEV_EVT_CLOSE";
    case HID_HDEV_EVT_RETRYING:
      return "HID_HDEV_EVT_RETRYING";
    case HID_HDEV_EVT_INTR_DATA:
      return "HID_HDEV_EVT_INTR_DATA";
    case HID_HDEV_EVT_INTR_DATC:
      return "HID_HDEV_EVT_INTR_DATC";
    case HID_HDEV_EVT_CTRL_DATA:
      return "HID_HDEV_EVT_CTRL_DATA";
    case HID_HDEV_EVT_CTRL_DATC:
      return "HID_HDEV_EVT_CTRL_DATC";
    case HID_HDEV_EVT_HANDSHAKE:
      return "HID_HDEV_EVT_HANDSHAKE";
    case HID_HDEV_EVT_VC_UNPLUG:
      return "HID_HDEV_EVT_VC_UNPLUG";
    default:
      return "Unknown HID event";
  }
}
