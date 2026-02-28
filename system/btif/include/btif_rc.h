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

/* for AVRC 1.4 need to change this */
#define MAX_RC_NOTIFICATIONS AVRC_EVT_VOLUME_CHANGE

/* Update MAX value whenever IDX will be changed */
#define MAX_CMD_QUEUE_LEN 17

#define MAX_VOLUME 128
#define MAX_LABEL 16
#define MAX_TRANSACTIONS_PER_SESSION 16
#define BTIF_RC_NUM_CONN BT_RC_NUM_APP

/* Configurable playback_position_changed_update interval */
#define PLAY_POS_UPDATE_INTERVAL_PROPERTY \
  "bluetooth.avrcp.controller.playback_pos_update_interval_sec"
// Default interval associated with AVRC_EVT_PLAY_POS_CHANGED
#define DEFAULT_PLAY_POS_UPDATE_INTERVAL_SEC 2

#define CHECK_RC_CONNECTED(p_dev)                                                  \
  do {                                                                             \
    if ((p_dev) == NULL || (p_dev)->rc_state != BTRC_CONNECTION_STATE_CONNECTED) { \
      bluetooth::log::warn("called when RC is not connected");                     \
      return BtifStatus(NOT_READY);                                                \
    }                                                                              \
  } while (0)

#define CHECK_BR_CONNECTED(p_dev)                                                  \
  do {                                                                             \
    if ((p_dev) == NULL || (p_dev)->br_state != BTRC_CONNECTION_STATE_CONNECTED) { \
      bluetooth::log::warn("called when BR is not connected");                     \
      return BtifStatus(NOT_READY);                                                \
    }                                                                              \
  } while (0)

/*****************************************************************************
 *  Type definitions
 *****************************************************************************/
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

struct btif_rc_device_cb_t {
  uint8_t rc_handle;
  tBTA_AV_FEAT rc_features;
  uint16_t rc_cover_art_psm;  // AVRCP-BIP psm
  btrc_connection_state_t rc_state;
  btrc_connection_state_t br_state;  // Browsing channel state.
  RawAddress rc_addr;
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

namespace bluetooth {
namespace testing {
namespace avrc {

struct btif_rc_interface {
  /*************************************************************************
   * Group 1: Command Transmission & Infrastructure
   * Focus: Low-level command construction, transmission logic, and
   * upstream response routing.
   * Parameters like tAVRC_COMMAND and btif_rc_device_cb_t are central
   * for state-aware transmission.
   *************************************************************************/
  [[nodiscard]] BtStatus (*build_and_send_browsing_cmd)(tAVRC_COMMAND* avrc_cmd,
                                                        btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*build_and_send_vendor_cmd)(tAVRC_COMMAND* avrc_cmd,
                                                      tBTA_AV_CODE cmd_code,
                                                      btif_rc_device_cb_t* p_dev);
  void (*btif_rc_ctrl_upstreams_rsp_cmd)(uint8_t event, tAVRC_COMMAND* pavrc_cmd, uint8_t label,
                                         btif_rc_device_cb_t* p_dev);
  void (*send_reject_response)(uint8_t rc_handle, uint8_t label, uint8_t pdu, uint8_t status,
                               uint8_t opcode);

  /*************************************************************************
   * Group 2: Player Metadata & Status Tracking
   * Focus: Retrieval and handling of track metadata, playback status,
   * and player application settings.
   * Usage of attribute IDs and response structures is weighted heavily
   * for processing incoming media info.
   *************************************************************************/
  [[nodiscard]] BtStatus (*volume_change_notification_rsp)(const RawAddress& bd_addr,
                                                           btrc_notification_type_t type,
                                                           uint8_t volume, uint8_t label);
  [[nodiscard]] BtStatus (*set_volume_rsp)(const RawAddress& bd_addr, uint8_t abs_vol,
                                           uint8_t label);
  [[nodiscard]] BtStatus (*get_element_attribute_cmd)(uint8_t num_attribute,
                                                      const uint32_t* p_attr_ids,
                                                      btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*get_metadata_attribute_cmd)(uint8_t num_attribute,
                                                       const uint32_t* p_attr_ids,
                                                       btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*get_play_status_cmd)(btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*get_player_app_setting_attr_text_cmd)(uint8_t* attrs, uint8_t num_attrs,
                                                                 btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*get_player_app_setting_cmd)(uint8_t num_attrib, uint8_t* attrib_ids,
                                                       btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*get_player_app_setting_value_text_cmd)(uint8_t* vals, uint8_t num_vals,
                                                                  btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*list_player_app_setting_attrib_cmd)(btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*list_player_app_setting_value_cmd)(uint8_t attrib_id,
                                                              btif_rc_device_cb_t* p_dev);
  void (*handle_app_attr_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_LIST_APP_ATTR_RSP* p_rsp);
  void (*handle_app_attr_txt_response)(tBTA_AV_META_MSG* pmeta_msg,
                                       tAVRC_GET_APP_ATTR_TXT_RSP* p_rsp);
  void (*handle_app_attr_val_txt_response)(tBTA_AV_META_MSG* pmeta_msg,
                                           tAVRC_GET_APP_ATTR_TXT_RSP* p_rsp);
  void (*handle_app_cur_val_response)(tBTA_AV_META_MSG* pmeta_msg,
                                      tAVRC_GET_CUR_APP_VALUE_RSP* p_rsp);
  void (*handle_app_val_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_LIST_APP_VALUES_RSP* p_rsp);
  void (*handle_get_metadata_attr_response)(tBTA_AV_META_MSG* pmeta_msg,
                                            tAVRC_GET_ATTRS_RSP* p_rsp);
  void (*handle_get_playstatus_response)(tBTA_AV_META_MSG* pmeta_msg,
                                         tAVRC_GET_PLAY_STATUS_RSP* p_rsp);
  void (*handle_set_app_attr_val_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_RSP* p_rsp);
  void (*cleanup_app_attr_val_txt_response)(btif_rc_player_app_settings_t* p_app_settings);
  [[nodiscard]] bool (*rc_is_track_id_valid)(tAVRC_UID uid);

  /*************************************************************************
   * Group 3: Player & Feature Discovery
   * Focus: Discovery of peer features, capabilities, and management of
   * connection-level metadata.
   *************************************************************************/
  [[nodiscard]] BtStatus (*getcapabilities_cmd)(uint8_t cap_id, btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*init_ctrl)(btrc_ctrl_callbacks_t* callbacks);
  [[nodiscard]] BtStatus (*send_groupnavigation_cmd)(const RawAddress& bd_addr, uint8_t key_code,
                                                     uint8_t key_state);
  [[nodiscard]] BtStatus (*send_passthrough_cmd)(const RawAddress& bd_addr, uint8_t key_code,
                                                 uint8_t key_state);
  [[nodiscard]] BtStatus (*change_folder_path_cmd)(const RawAddress& bd_addr, uint8_t direction,
                                                   uint8_t* uid);
  [[nodiscard]] BtStatus (*set_addressed_player_cmd)(const RawAddress& bd_addr, uint16_t id);
  [[nodiscard]] BtStatus (*set_browsed_player_cmd)(const RawAddress& bd_addr, uint16_t id);
  [[nodiscard]] BtStatus (*get_current_metadata_cmd)(const RawAddress& bd_addr);
  [[nodiscard]] BtStatus (*get_playback_state_cmd)(const RawAddress& bd_addr);
  [[nodiscard]] BtStatus (*get_now_playing_list_cmd)(const RawAddress& bd_addr, uint32_t start_item,
                                                     uint32_t end_item);
  [[nodiscard]] BtStatus (*get_folder_list_cmd)(const RawAddress& bd_addr, uint32_t start_item,
                                                uint32_t end_item);
  void (*handle_get_capability_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_GET_CAPS_RSP* p_rsp);
  void (*handle_rc_browse_connect)(tBTA_AV_RC_BROWSE_OPEN* p_rc_br_open);
  void (*handle_rc_connect)(tBTA_AV_RC_OPEN* p_rc_open);
  void (*handle_rc_ctrl_features)(btif_rc_device_cb_t* p_dev);
  void (*handle_rc_ctrl_features_all)(btif_rc_device_cb_t* p_dev);
  void (*handle_rc_ctrl_psm)(btif_rc_device_cb_t* p_dev);
  void (*handle_rc_disconnect)(tBTA_AV_RC_CLOSE* p_rc_close);
  void (*handle_set_addressed_player_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_RSP* p_rsp);
  void (*handle_set_browsed_player_response)(tBTA_AV_META_MSG* pmeta_msg,
                                             tAVRC_SET_BR_PLAYER_RSP* p_rsp);
  [[nodiscard]] std::string (*dump_peer_features)(const uint16_t feats);
  [[nodiscard]] uint8_t (*get_requested_attributes_list_size)(btif_rc_device_cb_t* p_dev);

  /*************************************************************************
   * Group 4: Device & Transaction Management
   * Focus: Lifecycle management of device control blocks and AVRCP
   * transaction state. Heavy usage of label and RawAddress parameters.
   *************************************************************************/
  [[nodiscard]] btif_rc_device_cb_t* (*get_device_cb)(unsigned index);
  [[nodiscard]] btif_rc_device_cb_t* (*alloc_device)();
  [[nodiscard]] btif_rc_device_cb_t* (*btif_rc_get_device_by_bda)(const RawAddress& bd_addr);
  [[nodiscard]] btif_rc_device_cb_t* (*btif_rc_get_device_by_handle)(uint8_t handle);
  [[nodiscard]] btif_rc_device_cb_t* (*get_connected_device)(int index);
  [[nodiscard]] BtStatus (*get_transaction)(btif_rc_device_cb_t* p_dev,
                                            rc_transaction_context_t& context,
                                            rc_transaction_t** ptransaction);
  [[nodiscard]] rc_transaction_t* (*get_transaction_by_lbl)(btif_rc_device_cb_t* p_dev,
                                                            uint8_t label);
  void (*initialize_device)(btif_rc_device_cb_t* p_dev);
  void (*init_all_transactions)(btif_rc_device_cb_t* p_dev);
  void (*initialize_transaction)(btif_rc_device_cb_t* p_dev, uint8_t lbl);
  void (*release_transaction)(btif_rc_device_cb_t* p_dev, uint8_t label);
  [[nodiscard]] std::string (*dump_transaction)(const rc_transaction_t* const transaction);

  /*************************************************************************
   * Group 5: Browsing & Content Navigation
   * Focus: Interaction with the media database, folder structures, and
   * track navigation.
   *************************************************************************/
  [[nodiscard]] BtStatus (*get_folder_items_cmd)(const RawAddress& bd_addr, uint8_t scope,
                                                 uint32_t start_item, uint32_t end_item);
  [[nodiscard]] BtStatus (*get_item_attribute_cmd)(uint64_t uid, int scope, uint8_t num_attribute,
                                                   const uint32_t* p_attr_ids,
                                                   btif_rc_device_cb_t* p_dev);
  [[nodiscard]] BtStatus (*play_item_cmd)(const RawAddress& bd_addr, uint8_t scope, uint8_t* uid,
                                          uint16_t uid_counter);
  void (*handle_change_path_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_CHG_PATH_RSP* p_rsp);
  void (*handle_get_folder_items_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_GET_ITEMS_RSP* p_rsp);
  void (*get_folder_item_type_folder)(const tAVRC_ITEM* avrc_item, btrc_folder_items_t* btrc_item);
  void (*get_folder_item_type_media)(const tAVRC_ITEM* avrc_item, btrc_folder_items_t* btrc_item);
  void (*get_folder_item_type_player)(const tAVRC_ITEM* avrc_item, btrc_folder_items_t* btrc_item);
  void (*cleanup_btrc_folder_items)(btrc_folder_items_t* btrc_items, uint8_t item_count);

  /*************************************************************************
   * Group 6: Event Notification & Timeout Handling
   * Focus: Management of asynchronous status updates and error recovery
   * for timed-out transactions.
   *************************************************************************/
  [[nodiscard]] BtStatus (*register_notification_cmd)(uint8_t event_id, uint32_t event_value,
                                                      btif_rc_device_cb_t* p_dev);
  void (*register_for_event_notification)(btif_rc_supported_event_t* p_event,
                                          btif_rc_device_cb_t* p_dev);
  void (*handle_notification_response)(tBTA_AV_META_MSG* pmeta_msg, tAVRC_REG_NOTIF_RSP* p_rsp);
  [[nodiscard]] bool (*iterate_supported_event_list_for_interim_rsp)(void* data, void* cb_data);
  void (*rc_notification_interim_timeout)(btif_rc_device_cb_t* p_dev, uint8_t event_id);
  void (*rc_ctrl_procedure_complete)(btif_rc_device_cb_t* p_dev);
  void (*start_transaction_timer)(btif_rc_device_cb_t* p_dev, uint8_t label, uint64_t timeout_ms);
  void (*clear_cmd_timeout)(btif_rc_device_cb_t* p_dev, uint8_t label);
  void (*passthru_cmd_timeout_handler)(btif_rc_device_cb_t* p_dev, uint8_t label,
                                       rc_passthru_context_t* p_context);
  void (*vendor_cmd_timeout_handler)(btif_rc_device_cb_t* p_dev, uint8_t label,
                                     rc_vendor_context_t* p_context);
  void (*browse_cmd_timeout_handler)(btif_rc_device_cb_t* p_dev, uint8_t label,
                                     rc_browse_context_t* p_context);
  void (*btif_rc_transaction_timer_timeout)(void* data);
  void (*btif_rc_transaction_timeout_handler)(uint16_t /* event */, char* data);
  void (*handle_rc_passthrough_rsp)(tBTA_AV_REMOTE_RSP* p_remote_rsp);
  void (*handle_rc_vendorunique_rsp)(tBTA_AV_REMOTE_RSP* p_remote_rsp);
  void (*handle_avk_rc_metamsg_cmd)(tBTA_AV_META_MSG* pmeta_msg);
  void (*handle_avk_rc_metamsg_rsp)(tBTA_AV_META_MSG* pmeta_msg);
};

btif_rc_interface* btif_rc_ctrl_get_interface();

}  // namespace avrc
}  // namespace testing
}  // namespace bluetooth
