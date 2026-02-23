/******************************************************************************
 *
 *  Copyright 1999-2013 Broadcom Corporation
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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "srvc_dis_int.h"
#include "srvc_eng_int.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/gatt_api.h"
#include "stack/include/stack_app.h"

using namespace bluetooth;

static void srvc_eng_connect_cback(tGATT_IF /* gatt_if */, const RawAddress& bda, tCONN_ID conn_id,
                                   bool connected, tGATT_DISCONN_REASON reason,
                                   tBT_TRANSPORT transport);
static void srvc_eng_c_cmpl_cback(tCONN_ID conn_id, tGATTC_OPTYPE op, tGATT_STATUS status,
                                  tGATT_CL_COMPLETE* p_data);

static stack::tGATT_CBACK srvc_gatt_cback = {
        .p_conn_cb = srvc_eng_connect_cback,
        .p_cmpl_cb = srvc_eng_c_cmpl_cback,
        .p_disc_res_cb = nullptr,
        .p_disc_cmpl_cb = nullptr,
        .p_req_cb = nullptr,
        .p_enc_cmpl_cb = nullptr,
        .p_congestion_cb = nullptr,
        .p_phy_update_cb = nullptr,
        .p_conn_update_cb = nullptr,
        .p_subrate_chg_cb = nullptr,
};

/* type for action functions */
typedef void (*tSRVC_ENG_C_CMPL_ACTION)(tSRVC_CLCB* p_clcb, tGATTC_OPTYPE op, tGATT_STATUS status,
                                        tGATT_CL_COMPLETE* p_data);

static const tSRVC_ENG_C_CMPL_ACTION srvc_eng_c_cmpl_act[SRVC_ID_MAX] = {
        dis_c_cmpl_cback,
};

tSRVC_ENG_CB srvc_eng_cb;

/*******************************************************************************
 *
 * Function         srvc_eng_find_clcb_by_bd_addr
 *
 * Description      The function searches all LCBs with macthing bd address.
 *
 * Returns          Pointer to the found link connection control block.
 *
 ******************************************************************************/
static tSRVC_CLCB* srvc_eng_find_clcb_by_bd_addr(const RawAddress& bda) {
  uint8_t i_clcb;
  tSRVC_CLCB* p_clcb = NULL;

  for (i_clcb = 0, p_clcb = srvc_eng_cb.clcb; i_clcb < SRVC_MAX_APPS; i_clcb++, p_clcb++) {
    if (p_clcb->in_use && p_clcb->connected && p_clcb->bda == bda) {
      return p_clcb;
    }
  }

  return NULL;
}
/*******************************************************************************
 *
 * Function         srvc_eng_find_clcb_by_conn_id
 *
 * Description      The function searches all LCBs with macthing connection ID.
 *
 * Returns          Pointer to the found link connection control block.
 *
 ******************************************************************************/
tSRVC_CLCB* srvc_eng_find_clcb_by_conn_id(tCONN_ID conn_id) {
  uint8_t i_clcb;
  tSRVC_CLCB* p_clcb = NULL;

  for (i_clcb = 0, p_clcb = srvc_eng_cb.clcb; i_clcb < SRVC_MAX_APPS; i_clcb++, p_clcb++) {
    if (p_clcb->in_use && p_clcb->connected && p_clcb->conn_id == conn_id) {
      return p_clcb;
    }
  }

  return NULL;
}
/*******************************************************************************
 *
 * Function         srvc_eng_clcb_alloc
 *
 * Description      Allocate a GATT profile connection link control block
 *
 * Returns          NULL if not found. Otherwise pointer to the connection link
 *                  block.
 *
 ******************************************************************************/
static tSRVC_CLCB* srvc_eng_clcb_alloc(tCONN_ID conn_id, const RawAddress& bda) {
  uint8_t i_clcb = 0;
  tSRVC_CLCB* p_clcb = NULL;

  for (i_clcb = 0, p_clcb = srvc_eng_cb.clcb; i_clcb < SRVC_MAX_APPS; i_clcb++, p_clcb++) {
    if (!p_clcb->in_use) {
      p_clcb->in_use = true;
      p_clcb->conn_id = conn_id;
      p_clcb->connected = true;
      p_clcb->bda = bda;
      break;
    }
  }
  return p_clcb;
}
/*******************************************************************************
 *
 * Function         srvc_eng_clcb_dealloc
 *
 * Description      De-allocate a GATT profile connection link control block
 *
 * Returns          True the deallocation is successful
 *
 ******************************************************************************/
static bool srvc_eng_clcb_dealloc(tCONN_ID conn_id) {
  uint8_t i_clcb = 0;
  tSRVC_CLCB* p_clcb = NULL;

  for (i_clcb = 0, p_clcb = srvc_eng_cb.clcb; i_clcb < SRVC_MAX_APPS; i_clcb++, p_clcb++) {
    if (p_clcb->in_use && p_clcb->connected && (p_clcb->conn_id == conn_id)) {
      unsigned j;
      for (j = 0; j < ARRAY_SIZE(p_clcb->dis_value.data_string); j++) {
        osi_free(p_clcb->dis_value.data_string[j]);
      }

      memset(p_clcb, 0, sizeof(tSRVC_CLCB));
      return true;
    }
  }
  return false;
}

/*******************************************************************************
 *
 * Function         srvc_eng_c_cmpl_cback
 *
 * Description      Client operation complete callback.
 *
 * Returns          void
 *
 ******************************************************************************/
static void srvc_eng_c_cmpl_cback(tCONN_ID conn_id, tGATTC_OPTYPE op, tGATT_STATUS status,
                                  tGATT_CL_COMPLETE* p_data) {
  tSRVC_CLCB* p_clcb = srvc_eng_find_clcb_by_conn_id(conn_id);

  log::verbose("srvc_eng_c_cmpl_cback() - op_code: 0x{:02x}  status: 0x{:02x}", op, status);

  if (p_clcb == NULL) {
    log::error("received for unknown connection");
    return;
  }

  if (p_clcb->cur_srvc_id != SRVC_ID_NONE && p_clcb->cur_srvc_id <= SRVC_ID_MAX) {
    srvc_eng_c_cmpl_act[p_clcb->cur_srvc_id - 1](p_clcb, op, status, p_data);
  }
}

/*******************************************************************************
 *
 * Function         srvc_eng_connect_cback
 *
 * Description      Gatt profile connection callback.
 *
 * Returns          void
 *
 ******************************************************************************/
static void srvc_eng_connect_cback(tGATT_IF /* gatt_if */, const RawAddress& bda, tCONN_ID conn_id,
                                   bool connected, tGATT_DISCONN_REASON /* reason */,
                                   tBT_TRANSPORT /* transport */) {
  log::verbose("from {} connected:{} conn_id={}", bda, connected, conn_id);

  if (connected) {
    if (srvc_eng_clcb_alloc(conn_id, bda) == NULL) {
      log::error("srvc_eng_connect_cback: no_resource");
      return;
    }
  } else {
    srvc_eng_clcb_dealloc(conn_id);
  }
}
/*******************************************************************************
 *
 * Function         srvc_eng_c_cmpl_cback
 *
 * Description      Client operation complete callback.
 *
 * Returns          void
 *
 ******************************************************************************/
bool srvc_eng_request_channel(const RawAddress& remote_bda, uint8_t srvc_id) {
  bool set = true;
  tSRVC_CLCB* p_clcb = srvc_eng_find_clcb_by_bd_addr(remote_bda);

  if (p_clcb == NULL) {
    p_clcb = srvc_eng_clcb_alloc(0, remote_bda);
  }

  if (p_clcb && p_clcb->cur_srvc_id == SRVC_ID_NONE) {
    p_clcb->cur_srvc_id = srvc_id;
  } else {
    set = false;
  }

  return set;
}
/*******************************************************************************
 *
 * Function         srvc_eng_release_channel
 *
 * Description      Client operation complete callback.
 *
 * Returns          void
 *
 ******************************************************************************/
void srvc_eng_release_channel(tCONN_ID conn_id) {
  tSRVC_CLCB* p_clcb = srvc_eng_find_clcb_by_conn_id(conn_id);

  if (p_clcb == NULL) {
    log::error("invalid connection id {}", conn_id);
    return;
  }

  p_clcb->cur_srvc_id = SRVC_ID_NONE;

  /* check pending request */
  if (GATT_Disconnect(p_clcb->conn_id) != GATT_SUCCESS) {
    log::warn("Unable to disconnect GATT conn_id:{}", p_clcb->conn_id);
  }
}
/*******************************************************************************
 *
 * Function         srvc_eng_init
 *
 * Description      Initialize the GATT Service engine.
 *
 ******************************************************************************/
tGATT_STATUS srvc_eng_init(void) {
  if (srvc_eng_cb.enabled) {
    log::verbose("DIS already initialized");
  } else {
    memset(&srvc_eng_cb, 0, sizeof(tSRVC_ENG_CB));

    /* Create a GATT profile service */
    bluetooth::Uuid app_uuid = bluetooth::Uuid::From16Bit(UUID_SERVCLASS_DEVICE_INFO);
    srvc_eng_cb.gatt_if =
            stack::appRegister(app_uuid, "GattServiceEngine", &srvc_gatt_cback, false);
    stack::appStartIf(srvc_eng_cb.gatt_if);

    log::verbose("Srvc_Init:  gatt_if={}", srvc_eng_cb.gatt_if);

    srvc_eng_cb.enabled = true;
    dis_cb.dis_read_uuid_idx = 0xff;
  }
  return GATT_SUCCESS;
}
