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
 *  This file contains the GATT client main functions and state machine.
 *
 ******************************************************************************/
#define LOG_TAG "bta_gattc_main"

#include <bluetooth/log.h>

#include "bta/gatt/bta_gattc_int.h"
#include "internal_include/bt_target.h"
#include "stack/include/bt_hdr.h"

using namespace bluetooth;

/*****************************************************************************
 * Constants and types
 ****************************************************************************/

/* state machine action enumeration list */
enum {
  BTA_GATTC_START_DISCOVER,
  BTA_GATTC_DISC_CMPL,
  BTA_GATTC_Q_CMD,
  BTA_GATTC_CLOSE,
  BTA_GATTC_READ,
  BTA_GATTC_WRITE,
  BTA_GATTC_OP_CMPL,
  BTA_GATTC_SEARCH,
  BTA_GATTC_FAIL,
  BTA_GATTC_CONFIRM,
  BTA_GATTC_EXEC,
  BTA_GATTC_READ_MULTI,
  BTA_GATTC_OP_CMPL_DURING_DISCOVERY,
  BTA_GATTC_DISC_CLOSE,
  BTA_GATTC_RESTART_DISCOVER,
  BTA_GATTC_CFG_MTU,

  BTA_GATTC_IGNORE
};
/* type for action functions */
typedef void (*tBTA_GATTC_ACTION)(tBTA_GATTC_CLCB* p_clcb, const tBTA_GATTC_DATA* p_data);

/* action function list */
const tBTA_GATTC_ACTION bta_gattc_action[] = {
        bta_gattc_start_discover,           /* BTA_GATTC_START_DISCOVER */
        bta_gattc_disc_cmpl,                /* BTA_GATTC_DISC_CMPL */
        bta_gattc_q_cmd,                    /* BTA_GATTC_Q_CMD */
        bta_gattc_close,                    /* BTA_GATTC_CLOSE */
        bta_gattc_read,                     /* BTA_GATTC_READ */
        bta_gattc_write,                    /* BTA_GATTC_WRITE */
        bta_gattc_op_cmpl,                  /* BTA_GATTC_OP_CMPL */
        bta_gattc_search,                   /* BTA_GATTC_SEARCH */
        bta_gattc_fail,                     /* BTA_GATTC_FAIL */
        bta_gattc_confirm,                  /* BTA_GATTC_CONFIRM */
        bta_gattc_execute,                  /* BTA_GATTC_EXEC */
        bta_gattc_read_multi,               /* BTA_GATTC_READ_MULTI */
        bta_gattc_op_cmpl_during_discovery, /* BTA_GATTC_OP_CMPL_DURING_DISCOVERY */
        bta_gattc_disc_close,               /* BTA_GATTC_DISC_CLOSE */
        bta_gattc_restart_discover,         /* BTA_GATTC_RESTART_DISCOVER */
        bta_gattc_cfg_mtu                   /* BTA_GATTC_CFG_MTU */
};

/* state table information */
#define BTA_GATTC_ACTIONS 1    /* number of actions */
#define BTA_GATTC_NEXT_STATE 1 /* position of next state */
#define BTA_GATTC_NUM_COLS 2   /* number of columns in state tables */

/* state table for open state */
static const uint8_t bta_gattc_st_connected[][BTA_GATTC_NUM_COLS] = {
        /* Event                            Action 1 Next state */
        /* BTA_GATTC_API_READ_EVT           */ {BTA_GATTC_READ, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_API_WRITE_EVT          */ {BTA_GATTC_WRITE, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_API_EXEC_EVT           */ {BTA_GATTC_EXEC, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_API_CFG_MTU_EVT        */ {BTA_GATTC_CFG_MTU, BTA_GATTC_CONN_ST},

        /* CLCB will be deleted, next state is not important*/
        /* BTA_GATTC_API_CLOSE_EVT          */ {BTA_GATTC_CLOSE, BTA_GATTC_CONN_ST},

        /* BTA_GATTC_API_SEARCH_EVT         */ {BTA_GATTC_SEARCH, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_API_CONFIRM_EVT        */ {BTA_GATTC_CONFIRM, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_API_READ_MULTI_EVT     */ {BTA_GATTC_READ_MULTI, BTA_GATTC_CONN_ST},

        /* BTA_GATTC_INT_DISCOVER_EVT       */ {BTA_GATTC_START_DISCOVER, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_DISCOVER_CMPL_EVT       */ {BTA_GATTC_IGNORE, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_OP_CMPL_EVT            */ {BTA_GATTC_OP_CMPL, BTA_GATTC_CONN_ST},
};

/* state table for discover state */
static const uint8_t bta_gattc_st_discover[][BTA_GATTC_NUM_COLS] = {
        /* Event                            Action 1 Next state */
        /* BTA_GATTC_API_READ_EVT           */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_API_WRITE_EVT          */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_API_EXEC_EVT           */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_API_CFG_MTU_EVT        */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},

        /* BTA_GATTC_API_CLOSE_EVT          */ {BTA_GATTC_DISC_CLOSE, BTA_GATTC_DISCOVER_ST},

        /* BTA_GATTC_API_SEARCH_EVT         */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_API_CONFIRM_EVT        */ {BTA_GATTC_CONFIRM, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_API_READ_MULTI_EVT     */ {BTA_GATTC_Q_CMD, BTA_GATTC_DISCOVER_ST},

        /* BTA_GATTC_INT_DISCOVER_EVT       */ {BTA_GATTC_RESTART_DISCOVER, BTA_GATTC_DISCOVER_ST},
        /* BTA_GATTC_DISCOVER_CMPL_EVT      */ {BTA_GATTC_DISC_CMPL, BTA_GATTC_CONN_ST},
        /* BTA_GATTC_OP_CMPL_EVT            */
        {BTA_GATTC_OP_CMPL_DURING_DISCOVERY, BTA_GATTC_DISCOVER_ST},
};

/* type for state table */
typedef const uint8_t (*tBTA_GATTC_ST_TBL)[BTA_GATTC_NUM_COLS];

/* state table */
const tBTA_GATTC_ST_TBL bta_gattc_st_tbl[] = {
        bta_gattc_st_connected, /* BTA_GATTC_CONN_ST */
        bta_gattc_st_discover   /* BTA_GATTC_DISCOVER_ST */
};

/*****************************************************************************
 * Global data
 ****************************************************************************/

/* GATTC control block */
tBTA_GATTC_CB bta_gattc_cb;

/*******************************************************************************
 *
 * Function         bta_gattc_sm_execute
 *
 * Description      State machine event handling function for GATTC
 *
 *
 * Returns          bool  : true if queued client request buffer can be
 *                          immediately released, else false
 *
 ******************************************************************************/
bool bta_gattc_sm_execute(tBTA_GATTC_CLCB* p_clcb, uint16_t event, const tBTA_GATTC_DATA* p_data) {
  tBTA_GATTC_ST_TBL state_table;
  uint8_t action;
  int i;
  bool rt = true;
  tBTA_GATTC_STATE in_state = p_clcb->state;
  uint16_t in_event = event;

  log::verbose("State {:#x} [{}], Event {:#x}[{}], Addr {}", in_state,
               bta_clcb_state_text(in_state), in_event, bta_gattc_evt_code_text(in_event),
               p_clcb->bda);

  /* look up the state table for the current state */
  state_table = bta_gattc_st_tbl[p_clcb->state];

  event &= 0x00FF;

  /* set next state */
  bta_gattc_set_state(p_clcb, (tBTA_GATTC_STATE)(state_table[event][BTA_GATTC_NEXT_STATE]));

  /* execute action functions */
  for (i = 0; i < BTA_GATTC_ACTIONS; i++) {
    action = state_table[event][i];
    if (action != BTA_GATTC_IGNORE) {
      (*bta_gattc_action[action])(p_clcb, p_data);
      if (bta_gattc_is_data_queued(p_clcb, p_data)) {
        /* buffer is queued, don't free in the bta dispatcher.
         * we free it ourselves when a completion event is received.
         */
        rt = false;
      }
    } else {
      break;
    }
  }

  return rt;
}

/*******************************************************************************
 *
 * Function         bta_gattc_hdl_event
 *
 * Description      GATT client main event handling function.
 *
 *
 * Returns          bool
 *
 ******************************************************************************/
bool bta_gattc_hdl_event(const BT_HDR_RIGID* p_msg) {
  tBTA_GATTC_CLCB* p_clcb = NULL;
  bool rt = true;
  const auto p = (tBTA_GATTC_DATA*)p_msg;

  log::verbose("Event:{}, conn_id: {:#x} ", bta_gattc_evt_code_text(p_msg->event),
               p->int_conn.hdr.layer_specific);
  p_clcb = bta_gattc_find_clcb_by_conn_id(static_cast<tCONN_ID>(p_msg->layer_specific));

  if (p_clcb != nullptr) {
    rt = bta_gattc_sm_execute(p_clcb, p_msg->event, p);
  } else {
    log::error("Ignore unknown conn ID: {}", p_msg->layer_specific);
  }

  return rt;
}
