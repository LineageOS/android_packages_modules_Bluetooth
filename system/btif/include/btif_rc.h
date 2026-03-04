/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstdint>
#include <mutex>

#include "bta/include/bta_av_api.h"
#include "btif_status.h"
#include "hardware/bt_rc.h"
#include "osi/include/alarm.h"
#include "osi/include/list.h"
#include "stack/include/avrc_defs.h"

class RawAddress;

/*****************************************************************************
 *  Constants & Macros
 *****************************************************************************/

#define RC_INVALID_TRACK_ID (0xFFFFFFFFFFFFFFFFULL)

/* cod value for Headsets */
#define COD_AV_HEADSETS 0x0404
/* for AVRC 1.4 need to change this */
#define MAX_RC_NOTIFICATIONS AVRC_EVT_VOLUME_CHANGE

#define IDX_GET_PLAY_STATUS_RSP 0
#define IDX_LIST_APP_ATTR_RSP 1
#define IDX_LIST_APP_VALUE_RSP 2
#define IDX_GET_CURR_APP_VAL_RSP 3
#define IDX_SET_APP_VAL_RSP 4
#define IDX_GET_APP_ATTR_TXT_RSP 5
#define IDX_GET_APP_VAL_TXT_RSP 6
#define IDX_GET_ELEMENT_ATTR_RSP 7
#define IDX_SET_ADDR_PLAYER_RSP 8
#define IDX_SET_BROWSED_PLAYER_RSP 9
#define IDX_GET_FOLDER_ITEMS_RSP 10
#define IDX_CHG_PATH_RSP 11
#define IDX_GET_ITEM_ATTR_RSP 12
#define IDX_PLAY_ITEM_RSP 13
#define IDX_GET_TOTAL_NUM_OF_ITEMS_RSP 14
#define IDX_SEARCH_RSP 15
#define IDX_ADD_TO_NOW_PLAYING_RSP 16

/* Update MAX value whenever IDX will be changed */
#define MAX_CMD_QUEUE_LEN 17

#define MAX_VOLUME 128
#define MAX_LABEL 16
#define MAX_TRANSACTIONS_PER_SESSION 16
#define PLAY_STATUS_PLAYING 1
#define BTIF_RC_NUM_CONN BT_RC_NUM_APP

/* Configurable playback_position_changed_update interval */
#define PLAY_POS_UPDATE_INTERVAL_PROPERTY \
  "bluetooth.avrcp.controller.playback_pos_update_interval_sec"
// Default interval associated with AVRC_EVT_PLAY_POS_CHANGED
#define DEFAULT_PLAY_POS_UPDATE_INTERVAL_SEC 2

#define CHECK_RC_CONNECTED(p_dev)                              \
  do {                                                         \
    if ((p_dev) == NULL || !(p_dev)->rc_connected) {           \
      bluetooth::log::warn("called when RC is not connected"); \
      return BtifStatus(NOT_READY);                            \
    }                                                          \
  } while (0)

#define CHECK_BR_CONNECTED(p_dev)                              \
  do {                                                         \
    if ((p_dev) == NULL || !(p_dev)->br_connected) {           \
      bluetooth::log::warn("called when BR is not connected"); \
      return BtifStatus(NOT_READY);                            \
    }                                                          \
  } while (0)

/*****************************************************************************
 *  Type definitions
 *****************************************************************************/

struct btif_rc_reg_notifications_t {
  uint8_t bNotify;
  uint8_t label;
};

struct btif_rc_cmd_ctxt_t {
  uint8_t label;
  uint8_t ctype;
  bool is_rsp_pending;
};

/* 2 second timeout to get command response, then we free label */
#define BTIF_RC_TIMEOUT_MS (2 * 1000)

typedef enum { eNOT_REGISTERED, eREGISTERED, eINTERIM } btif_rc_nfn_reg_status_t;

struct btif_rc_supported_event_t {
  uint8_t event_id;
  uint8_t label;
  btif_rc_nfn_reg_status_t status;
};

#define BTIF_RC_STS_TIMEOUT 0xFE

struct btif_rc_player_app_settings_t {
  bool query_started;
  uint8_t num_attrs;
  uint8_t num_ext_attrs;

  uint8_t attr_index;
  uint8_t ext_attr_index;
  uint8_t ext_val_index;
  btrc_player_app_attr_t attrs[AVRC_MAX_APP_ATTR_SIZE];
  btrc_player_app_ext_attr_t ext_attrs[AVRC_MAX_APP_ATTR_SIZE];
};

// The context associated with a passthru command
struct rc_passthru_context_t {
  uint8_t rc_id;
  uint8_t key_state;
  uint8_t custom_id;
};

// The context associated with a vendor command
struct rc_vendor_context_t {
  uint8_t pdu_id;
  uint8_t event_id;
};

// The context associated with a browsing command
struct rc_browse_context_t {
  uint8_t pdu_id;
};

typedef union {
  rc_vendor_context_t vendor;
  rc_browse_context_t browse;
  rc_passthru_context_t passthru;
} rc_command_context_t;

// The context associated with any command transaction requiring a label.
// The opcode determines how to determine the data in the union. Context is
// used to track which requests have which labels
struct rc_transaction_context_t {
  RawAddress rc_addr;
  uint8_t label;
  uint8_t opcode;
  rc_command_context_t command;
};

struct rc_transaction_t {
  bool in_use;
  uint8_t label;
  rc_transaction_context_t context;
  alarm_t* timer;
};

struct rc_transaction_set_t {
  std::recursive_mutex label_lock;
  rc_transaction_t transaction[MAX_TRANSACTIONS_PER_SESSION];
};

/* TODO : Merge btif_rc_reg_notifications_t and btif_rc_cmd_ctxt_t to a single
 * struct */
struct btif_rc_device_cb_t {
  bool rc_connected;
  bool br_connected;  // Browsing channel.
  uint8_t rc_handle;
  tBTA_AV_FEAT rc_features;
  uint16_t rc_cover_art_psm;  // AVRCP-BIP psm
  btrc_connection_state_t rc_state;
  RawAddress rc_addr;
  btif_rc_cmd_ctxt_t rc_pdu_info[MAX_CMD_QUEUE_LEN];
  btif_rc_reg_notifications_t rc_notif[MAX_RC_NOTIFICATIONS];
  unsigned int rc_volume;
  uint8_t rc_vol_label;
  list_t* rc_supported_event_list;
  btif_rc_player_app_settings_t rc_app_settings;
  alarm_t* rc_play_status_timer;
  bool rc_features_processed;
  uint64_t rc_playing_uid;
  bool rc_procedure_complete;
  rc_transaction_set_t transaction_set;
  tBTA_AV_FEAT peer_ct_features;
  tBTA_AV_FEAT peer_tg_features;
  uint8_t launch_cmd_pending; /* true: getcap/regvolume */
};

#define RC_PENDING_ACT_GET_CAP (1 << 0)
#define RC_PENDING_ACT_REG_VOL (1 << 1)
#define RC_PENDING_ACT_REPORT_CONN (1 << 2)

struct rc_cb_t {
  std::mutex lock;
  btif_rc_device_cb_t rc_multi_cb[BTIF_RC_NUM_CONN];
};

struct btif_rc_handle_t {
  uint8_t handle;
};

#define BTIF_STS_GEN_ERROR 0x06

/*****************************************************************************
 *  Function declarations
 *****************************************************************************/

const btrc_ctrl_interface_t* btif_rc_ctrl_get_interface(void);

void btif_rc_handler(tBTA_AV_EVT event, tBTA_AV* p_data);
uint8_t btif_rc_get_connected_peer_handle(const RawAddress& peer_addr);
bool btif_rc_is_connected_peer(const RawAddress& peer_addr);
void btif_rc_check_pending_cmd(const RawAddress& peer_addr);
void btif_rc_get_addr_by_handle(uint8_t handle, RawAddress& rc_addr);
void btif_debug_rc_dump(int fd);
