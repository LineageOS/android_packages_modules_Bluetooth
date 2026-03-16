/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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
 *  This file contains functions that interface with the HCI transport. On
 *  the receive side, it routes events to the appropriate handler, e.g.
 *  L2CAP, ScoMgr. On the transmit side, it manages the command
 *  transmission.
 *
 ******************************************************************************/

#define LOG_TAG "bt_btu_hcif"

#include "stack/include/btu_hcif.h"

#include <base/functional/bind.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <bluetooth/metrics/os_metrics.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/hci_role.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "btm_iso_api.h"
#include "hci/hci_packets.h"
#include "hci_evt_length.h"
#include "internal_include/bt_target.h"
#include "main/shim/hci_layer.h"
#include "osi/include/allocator.h"
#include "stack/include/acl_hci_link_interface.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/inq_hci_link_interface.h"
#include "stack/include/main_thread.h"
#include "stack/include/sco_hci_link_interface.h"
#include "stack/include/sec_hci_link_interface.h"
#include "stack/include/smp_api.h"

using namespace bluetooth;
using bluetooth::hci::IsoManager;

/******************************************************************************/
/*            L O C A L    F U N C T I O N     P R O T O T Y P E S            */
/******************************************************************************/
static void btu_hcif_authentication_comp_evt(uint8_t* p);
static void btu_hcif_encryption_change_evt(uint8_t* p);
static void btu_hcif_encryption_change_evt_v2(uint8_t* p);
static void btu_hcif_command_complete_evt(bluetooth::hci::CommandCompleteView view, void* context);
static void btu_hcif_command_status_evt(uint8_t status, BT_HDR* command, void* context);
static void btu_hcif_mode_change_evt(uint8_t* p);
static void btu_hcif_link_key_notification_evt(const uint8_t* p);
static void btu_hcif_read_clock_off_comp_evt(uint8_t* p);
static void btu_hcif_esco_connection_comp_evt(const uint8_t* p);
static void btu_hcif_esco_connection_chg_evt(uint8_t* p);

/* Parsing functions for btm functions */

static void btu_hcif_sec_pin_code_request(const uint8_t* p);
static void btu_hcif_sec_link_key_request(const uint8_t* p);
static void btu_hcif_sec_rmt_host_support_feat_evt(const uint8_t* p);
static void btu_hcif_proc_sp_req_evt(tBTM_SP_EVT event, const uint8_t* p);
static void btu_hcif_rem_oob_req(const uint8_t* p);
static void btu_hcif_simple_pair_complete(const uint8_t* p);
static void btu_hcif_proc_sp_req_evt(const tBTM_SP_EVT event, const uint8_t* p);
static void btu_hcif_create_conn_cancel_complete(bluetooth::hci::CommandCompleteView view);
static void btu_hcif_read_local_oob_complete(bluetooth::hci::CommandCompleteView view);
static void btu_hcif_read_local_oob_extended_complete(bluetooth::hci::CommandCompleteView view);

/* Simple Pairing Events */
static void btu_hcif_io_cap_request_evt(const uint8_t* p);
static void btu_hcif_io_cap_response_evt(const uint8_t* p);

static void btu_ble_proc_ltk_req(uint8_t* p, uint16_t evt_len);
static void btu_hcif_encryption_key_refresh_cmpl_evt(uint8_t* p);

/**
 * Log HCI event metrics that are not handled in special functions
 * @param evt_code event code
 * @param p_event pointer to event parameter, skipping paremter length
 */
static void btu_hcif_log_event_metrics(uint8_t evt_code, const uint8_t* p_event) {
  uint32_t cmd = android::bluetooth::hci::CMD_UNKNOWN;
  uint16_t status = android::bluetooth::hci::STATUS_UNKNOWN;
  uint16_t reason = android::bluetooth::hci::STATUS_UNKNOWN;
  uint16_t handle = bluetooth::metrics::kUnknownConnectionHandle;
  int64_t value = 0;

  RawAddress bda = RawAddress::kEmpty;
  switch (evt_code) {
    case HCI_IO_CAPABILITY_REQUEST_EVT:
    case HCI_IO_CAPABILITY_RESPONSE_EVT:
    case HCI_LINK_KEY_REQUEST_EVT:
    case HCI_LINK_KEY_NOTIFICATION_EVT:
    case HCI_USER_PASSKEY_REQUEST_EVT:
    case HCI_USER_PASSKEY_NOTIFY_EVT:
    case HCI_USER_CONFIRMATION_REQUEST_EVT:
    case HCI_KEYPRESS_NOTIFY_EVT:
    case HCI_REMOTE_OOB_DATA_REQUEST_EVT:
      STREAM_TO_BDADDR(bda, p_event);
      bluetooth::metrics::LogMetricClassicPairingEvent(bda, handle, cmd, evt_code, status, reason,
                                                       value);
      break;
    case HCI_SIMPLE_PAIRING_COMPLETE_EVT:
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_BDADDR(bda, p_event);
      bluetooth::metrics::LogMetricClassicPairingEvent(bda, handle, cmd, evt_code, status, reason,
                                                       value);
      break;
    case HCI_AUTHENTICATION_COMP_EVT:
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_UINT16(handle, p_event);
      handle = HCID_GET_HANDLE(handle);
      bluetooth::metrics::LogMetricClassicPairingEvent(bda, handle, cmd, evt_code, status, reason,
                                                       value);
      break;
    case HCI_ENCRYPTION_CHANGE_EVT: {
      uint8_t encryption_enabled;
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_UINT16(handle, p_event);
      STREAM_TO_UINT8(encryption_enabled, p_event);
      bluetooth::metrics::LogMetricClassicPairingEvent(bda, handle, cmd, evt_code, status, reason,
                                                       encryption_enabled);
      break;
    }
    case HCI_ENCRYPTION_CHANGE_EVT_V2: {
      uint8_t encryption_enabled;
      uint8_t key_size;
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_UINT16(handle, p_event);
      STREAM_TO_UINT8(encryption_enabled, p_event);
      STREAM_TO_UINT8(key_size, p_event);
      bluetooth::metrics::LogMetricClassicPairingEvent(bda, handle, cmd, evt_code, status, reason,
                                                       encryption_enabled);
      break;
    }
    case HCI_ESCO_CONNECTION_COMP_EVT: {
      uint8_t link_type;
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_UINT16(handle, p_event);
      STREAM_TO_BDADDR(bda, p_event);
      STREAM_TO_UINT8(link_type, p_event);
      handle = HCID_GET_HANDLE(handle);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              bda, handle, android::bluetooth::DIRECTION_UNKNOWN, link_type, cmd, evt_code,
              android::bluetooth::hci::BLE_EVT_UNKNOWN, status, reason);
      break;
    }
    case HCI_ESCO_CONNECTION_CHANGED_EVT: {
      STREAM_TO_UINT8(status, p_event);
      STREAM_TO_UINT16(handle, p_event);
      handle = HCID_GET_HANDLE(handle);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              RawAddress::kEmpty, handle, android::bluetooth::DIRECTION_UNKNOWN,
              android::bluetooth::LINK_TYPE_UNKNOWN, cmd, evt_code,
              android::bluetooth::hci::BLE_EVT_UNKNOWN, status, reason);
      break;
    }
    // Ignore these events
    case HCI_BLE_EVENT:
      break;
    case HCI_VENDOR_SPECIFIC_EVT:
      break;
    case HCI_DEVELOPMENT_EVENT:
      break;

    case HCI_CONNECTION_COMP_EVT:        // EventCode::CONNECTION_COMPLETE
    case HCI_CONNECTION_REQUEST_EVT:     // EventCode::CONNECTION_REQUEST
    case HCI_DISCONNECTION_COMP_EVT:     // EventCode::DISCONNECTION_COMPLETE
    case HCI_RMT_NAME_REQUEST_COMP_EVT:  // EventCode::REMOTE_NAME_REQUEST_COMPLETE
    default:
      log::error(
              "Unexpectedly received event_code:0x{:02x} that should not be "
              "handled here",
              evt_code);
      break;
  }
}

/*******************************************************************************
 *
 * Function         btu_hcif_process_event
 *
 * Description      This function is called when an event is received from
 *                  the Host Controller.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_process_event(uint8_t /* controller_id */, const BT_HDR* p_msg) {
  uint8_t* p = (uint8_t*)(p_msg + 1) + p_msg->offset;
  uint8_t hci_evt_code, hci_evt_len;
  uint8_t sub_code;
  STREAM_TO_UINT8(hci_evt_code, p);
  STREAM_TO_UINT8(hci_evt_len, p);

  // validate event size
  if (hci_evt_len < hci_event_parameters_minimum_length[hci_evt_code]) {
    log::warn("evt:0x{:2X}, malformed event of size {}", hci_evt_code, hci_evt_len);
    return;
  }

  btu_hcif_log_event_metrics(hci_evt_code, p);

  switch (hci_evt_code) {
    case HCI_AUTHENTICATION_COMP_EVT:
      btu_hcif_authentication_comp_evt(p);
      break;
    case HCI_ENCRYPTION_CHANGE_EVT:
      btu_hcif_encryption_change_evt(p);
      break;
    case HCI_ENCRYPTION_CHANGE_EVT_V2:
      btu_hcif_encryption_change_evt_v2(p);
      break;
    case HCI_ENCRYPTION_KEY_REFRESH_COMP_EVT:
      btu_hcif_encryption_key_refresh_cmpl_evt(p);
      break;
    case HCI_COMMAND_COMPLETE_EVT:
      log::error(
              "should not have received a command complete event. Someone didn't "
              "go through the hci transmit_command function.");
      break;
    case HCI_COMMAND_STATUS_EVT:
      log::error(
              "should not have received a command status event. Someone didn't go "
              "through the hci transmit_command function.");
      break;
    case HCI_MODE_CHANGE_EVT:
      btu_hcif_mode_change_evt(p);
      break;
    case HCI_PIN_CODE_REQUEST_EVT:
      btu_hcif_sec_pin_code_request(p);
      break;
    case HCI_LINK_KEY_REQUEST_EVT:
      btu_hcif_sec_link_key_request(p);
      break;
    case HCI_LINK_KEY_NOTIFICATION_EVT:
      btu_hcif_link_key_notification_evt(p);
      break;
    case HCI_READ_CLOCK_OFF_COMP_EVT:
      btu_hcif_read_clock_off_comp_evt(p);
      break;
    case HCI_ESCO_CONNECTION_COMP_EVT:
      btu_hcif_esco_connection_comp_evt(p);
      break;
    case HCI_ESCO_CONNECTION_CHANGED_EVT:
      btu_hcif_esco_connection_chg_evt(p);
      break;
    case HCI_SNIFF_SUB_RATE_EVT:
      btm_pm_proc_ssr_evt(p, hci_evt_len);
      break;
    case HCI_RMT_HOST_SUP_FEAT_NOTIFY_EVT:
      btu_hcif_sec_rmt_host_support_feat_evt(p);
      break;
    case HCI_IO_CAPABILITY_REQUEST_EVT:
      btu_hcif_io_cap_request_evt(p);
      break;
    case HCI_IO_CAPABILITY_RESPONSE_EVT:
      btu_hcif_io_cap_response_evt(p);
      break;
    case HCI_USER_CONFIRMATION_REQUEST_EVT:
      btu_hcif_proc_sp_req_evt(BTM_SP_CFM_REQ_EVT, p);
      break;
    case HCI_USER_PASSKEY_REQUEST_EVT:
      btu_hcif_proc_sp_req_evt(BTM_SP_KEY_REQ_EVT, p);
      break;
    case HCI_REMOTE_OOB_DATA_REQUEST_EVT:
      btu_hcif_rem_oob_req(p);
      break;
    case HCI_SIMPLE_PAIRING_COMPLETE_EVT:
      btu_hcif_simple_pair_complete(p);
      break;
    case HCI_USER_PASSKEY_NOTIFY_EVT:
      btu_hcif_proc_sp_req_evt(BTM_SP_KEY_NOTIF_EVT, p);
      break;

    case HCI_BLE_EVENT: {
      STREAM_TO_UINT8(sub_code, p);

      uint8_t ble_evt_len = hci_evt_len - 1;
      switch (sub_code) {
        case HCI_BLE_READ_REMOTE_FEAT_CMPL_EVT:
          btm_ble_read_remote_features_complete(p, ble_evt_len);
          break;
        case HCI_BLE_LTK_REQ_EVT: /* received only at peripheral device */
          btu_ble_proc_ltk_req(p, ble_evt_len);
          break;

        case HCI_BLE_REQ_PEER_SCA_CPL_EVT:
          btm_acl_process_sca_cmpl_pkt(ble_evt_len, p);
          break;

        case HCI_BLE_CIS_EST_EVT:
        case HCI_BLE_CREATE_BIG_CPL_EVT:
        case HCI_BLE_TERM_BIG_CPL_EVT:
        case HCI_BLE_CIS_REQ_EVT:
        case HCI_BLE_BIG_SYNC_EST_EVT:
        case HCI_BLE_BIG_SYNC_LOST_EVT:
          IsoManager::GetInstance()->HandleHciEvent(sub_code, p, ble_evt_len);
          break;

        default:
          log::error(
                  "Unexpectedly received LE sub_event_code:0x{:02x} that should "
                  "not be handled here",
                  sub_code);
          break;
      }
    } break;

    case HCI_DEVELOPMENT_EVENT: {
      STREAM_TO_UINT8(sub_code, p);
      // Reserved for internal and IOP testing of under-development SIG featuress. Invoke the
      // handler with the sub_code for feature development. Please do not submit any code here.

      log::info("Unexpectedly received the development sub event code:0x{:02x}", sub_code);
    } break;

      // Events now captured by gd::hci_layer module
    case HCI_VENDOR_SPECIFIC_EVT:
    case HCI_HARDWARE_ERROR_EVT:
    case HCI_NUM_COMPL_DATA_PKTS_EVT:     // EventCode::NUMBER_OF_COMPLETED_PACKETS
    case HCI_CONNECTION_COMP_EVT:         // EventCode::CONNECTION_COMPLETE
    case HCI_CONNECTION_REQUEST_EVT:      // EventCode::CONNECTION_REQUEST
    case HCI_READ_RMT_FEATURES_COMP_EVT:  // EventCode::READ_REMOTE_SUPPORTED_FEATURES_COMPLETE
    case HCI_READ_RMT_VERSION_COMP_EVT:   // EventCode::READ_REMOTE_VERSION_INFORMATION_COMPLETE
    case HCI_ROLE_CHANGE_EVT:             // EventCode::ROLE_CHANGE
    case HCI_DISCONNECTION_COMP_EVT:      // EventCode::DISCONNECTION_COMPLETE
    case HCI_RMT_NAME_REQUEST_COMP_EVT:   // EventCode::REMOTE_NAME_REQUEST_COMPLETE
    default:
      log::error(
              "Unexpectedly received event_code:0x{:02x} that should not be "
              "handled here",
              hci_evt_code);
      break;
  }
}

static void btu_hcif_log_command_metrics(uint16_t opcode, const uint8_t* p_cmd, uint16_t cmd_status,
                                         bool is_cmd_status) {
  static uint16_t kUnknownBleEvt = android::bluetooth::hci::BLE_EVT_UNKNOWN;

  uint16_t hci_event = android::bluetooth::hci::EVT_COMMAND_STATUS;
  if (!is_cmd_status) {
    hci_event = android::bluetooth::hci::EVT_UNKNOWN;
    cmd_status = android::bluetooth::hci::STATUS_UNKNOWN;
  }

  RawAddress bd_addr = RawAddress::kEmpty;
  uint16_t handle;
  uint8_t reason;

  switch (opcode) {
    case HCI_CREATE_CONNECTION:
    case HCI_CREATE_CONNECTION_CANCEL:
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              bd_addr, bluetooth::metrics::kUnknownConnectionHandle,
              android::bluetooth::DIRECTION_OUTGOING, android::bluetooth::LINK_TYPE_ACL, opcode,
              hci_event, kUnknownBleEvt, cmd_status, android::bluetooth::hci::STATUS_UNKNOWN);
      break;
    case HCI_DISCONNECT:
      STREAM_TO_UINT16(handle, p_cmd);
      STREAM_TO_UINT8(reason, p_cmd);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              RawAddress::kEmpty, handle, android::bluetooth::DIRECTION_UNKNOWN,
              android::bluetooth::LINK_TYPE_UNKNOWN, opcode, hci_event, kUnknownBleEvt, cmd_status,
              reason);
      break;
    case HCI_SETUP_ESCO_CONNECTION:
    case HCI_ENH_SETUP_ESCO_CONNECTION:
      STREAM_TO_UINT16(handle, p_cmd);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              RawAddress::kEmpty, handle, android::bluetooth::DIRECTION_OUTGOING,
              android::bluetooth::LINK_TYPE_UNKNOWN, opcode, hci_event, kUnknownBleEvt, cmd_status,
              android::bluetooth::hci::STATUS_UNKNOWN);
      break;
    case HCI_ACCEPT_CONNECTION_REQUEST:
    case HCI_ACCEPT_ESCO_CONNECTION:
    case HCI_ENH_ACCEPT_ESCO_CONNECTION:
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              bd_addr, bluetooth::metrics::kUnknownConnectionHandle,
              android::bluetooth::DIRECTION_INCOMING, android::bluetooth::LINK_TYPE_UNKNOWN, opcode,
              hci_event, kUnknownBleEvt, cmd_status, android::bluetooth::hci::STATUS_UNKNOWN);
      break;
    case HCI_REJECT_CONNECTION_REQUEST:
    case HCI_REJECT_ESCO_CONNECTION:
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      STREAM_TO_UINT8(reason, p_cmd);
      bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
              bd_addr, bluetooth::metrics::kUnknownConnectionHandle,
              android::bluetooth::DIRECTION_INCOMING, android::bluetooth::LINK_TYPE_UNKNOWN, opcode,
              hci_event, kUnknownBleEvt, cmd_status, reason);
      break;

      // BLE Commands
    case HCI_BLE_CREATE_LL_CONN: {
      p_cmd += 2;  // Skip LE_Scan_Interval
      p_cmd += 2;  // Skip LE_Scan_Window;
      uint8_t initiator_filter_policy;
      STREAM_TO_UINT8(initiator_filter_policy, p_cmd);
      uint8_t peer_address_type;
      STREAM_TO_UINT8(peer_address_type, p_cmd);
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      // Peer address should not be used if initiator filter policy is not 0x00
      if (initiator_filter_policy == 0x00) {
        if (peer_address_type == BLE_ADDR_PUBLIC_ID || peer_address_type == BLE_ADDR_RANDOM_ID) {
          // if identity address is not matched, this address is invalid
          if (!btm_identity_addr_to_random_pseudo(&bd_addr, &peer_address_type, false)) {
            bd_addr = RawAddress::kEmpty;
          }
        }
      }
      if (initiator_filter_policy == 0x00 || (cmd_status != HCI_SUCCESS && !is_cmd_status)) {
        // Selectively log to avoid log spam due to acceptlist connections:
        // - When doing non-acceptlist connection
        // - When there is an error in command status
        bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
                bd_addr, bluetooth::metrics::kUnknownConnectionHandle,
                android::bluetooth::DIRECTION_OUTGOING, android::bluetooth::LINK_TYPE_ACL, opcode,
                hci_event, kUnknownBleEvt, cmd_status, android::bluetooth::hci::STATUS_UNKNOWN);
      }
      break;
    }
    case HCI_LE_EXTENDED_CREATE_CONNECTION: {
      uint8_t initiator_filter_policy;
      STREAM_TO_UINT8(initiator_filter_policy, p_cmd);
      p_cmd += 1;  // Skip Own_Address_Type
      uint8_t peer_addr_type;
      STREAM_TO_UINT8(peer_addr_type, p_cmd);
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      // Peer address should not be used if initiator filter policy is not 0x00
      if (initiator_filter_policy == 0x00) {
        // if identity address is not matched, this should be a static address
        btm_identity_addr_to_random_pseudo(&bd_addr, &peer_addr_type, false);
      }
      if (initiator_filter_policy == 0x00 || (cmd_status != HCI_SUCCESS && !is_cmd_status)) {
        // Selectively log to avoid log spam due to acceptlist connections:
        // - When doing non-acceptlist connection
        // - When there is an error in command status
        bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
                bd_addr, bluetooth::metrics::kUnknownConnectionHandle,
                android::bluetooth::DIRECTION_OUTGOING, android::bluetooth::LINK_TYPE_ACL, opcode,
                hci_event, kUnknownBleEvt, cmd_status, android::bluetooth::hci::STATUS_UNKNOWN);
      }
      break;
    }
    case HCI_BLE_CREATE_CONN_CANCEL:
      if (cmd_status != HCI_SUCCESS && !is_cmd_status) {
        // Only log errors to prevent log spam due to acceptlist connections
        bluetooth::metrics::LogMetricLinkLayerConnectionEvent(
                RawAddress::kEmpty, bluetooth::metrics::kUnknownConnectionHandle,
                android::bluetooth::DIRECTION_OUTGOING, android::bluetooth::LINK_TYPE_ACL, opcode,
                hci_event, kUnknownBleEvt, cmd_status, android::bluetooth::hci::STATUS_UNKNOWN);
      }
      break;
    case HCI_READ_LOCAL_OOB_DATA:
    case HCI_READ_LOCAL_OOB_EXTENDED_DATA:
      bluetooth::metrics::LogMetricClassicPairingEvent(
              RawAddress::kEmpty, bluetooth::metrics::kUnknownConnectionHandle, opcode, hci_event,
              cmd_status, android::bluetooth::hci::STATUS_UNKNOWN, 0);
      break;
    case HCI_WRITE_SIMPLE_PAIRING_MODE: {
      uint8_t simple_pairing_mode;
      STREAM_TO_UINT8(simple_pairing_mode, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(
              RawAddress::kEmpty, bluetooth::metrics::kUnknownConnectionHandle, opcode, hci_event,
              cmd_status, android::bluetooth::hci::STATUS_UNKNOWN, simple_pairing_mode);
      break;
    }
    case HCI_WRITE_SECURE_CONNS_SUPPORT: {
      uint8_t secure_conn_host_support;
      STREAM_TO_UINT8(secure_conn_host_support, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(
              RawAddress::kEmpty, bluetooth::metrics::kUnknownConnectionHandle, opcode, hci_event,
              cmd_status, android::bluetooth::hci::STATUS_UNKNOWN, secure_conn_host_support);
      break;
    }
    case HCI_AUTHENTICATION_REQUESTED:
      STREAM_TO_UINT16(handle, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(RawAddress::kEmpty, handle, opcode,
                                                       hci_event, cmd_status,
                                                       android::bluetooth::hci::STATUS_UNKNOWN, 0);
      break;
    case HCI_SET_CONN_ENCRYPTION: {
      STREAM_TO_UINT16(handle, p_cmd);
      uint8_t encryption_enable;
      STREAM_TO_UINT8(encryption_enable, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(
              RawAddress::kEmpty, handle, opcode, hci_event, cmd_status,
              android::bluetooth::hci::STATUS_UNKNOWN, encryption_enable);
      break;
    }
    case HCI_DELETE_STORED_LINK_KEY: {
      uint8_t delete_all_flag;
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      STREAM_TO_UINT8(delete_all_flag, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(
              bd_addr, bluetooth::metrics::kUnknownConnectionHandle, opcode, hci_event, cmd_status,
              android::bluetooth::hci::STATUS_UNKNOWN, delete_all_flag);
      break;
    }
    case HCI_RMT_NAME_REQUEST:
    case HCI_RMT_NAME_REQUEST_CANCEL:
    case HCI_LINK_KEY_REQUEST_REPLY:
    case HCI_LINK_KEY_REQUEST_NEG_REPLY:
    case HCI_IO_CAPABILITY_REQUEST_REPLY:
    case HCI_USER_CONF_REQUEST_REPLY:
    case HCI_USER_CONF_VALUE_NEG_REPLY:
    case HCI_USER_PASSKEY_REQ_REPLY:
    case HCI_USER_PASSKEY_REQ_NEG_REPLY:
    case HCI_REM_OOB_DATA_REQ_REPLY:
    case HCI_REM_OOB_DATA_REQ_NEG_REPLY:
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(
              bd_addr, bluetooth::metrics::kUnknownConnectionHandle, opcode, hci_event, cmd_status,
              android::bluetooth::hci::STATUS_UNKNOWN, 0);
      break;
    case HCI_IO_CAP_REQ_NEG_REPLY:
      STREAM_TO_BDADDR(bd_addr, p_cmd);
      STREAM_TO_UINT8(reason, p_cmd);
      bluetooth::metrics::LogMetricClassicPairingEvent(bd_addr,
                                                       bluetooth::metrics::kUnknownConnectionHandle,
                                                       opcode, hci_event, cmd_status, reason, 0);
      break;
  }
}

/*******************************************************************************
 *
 * Function         btu_hcif_send_cmd
 *
 * Description      This function is called to send commands to the Host
 *                  Controller.
 *
 * Returns          void
 *
 ******************************************************************************/
void btu_hcif_send_cmd(uint8_t /* controller_id */, const BT_HDR* p_buf) {
  if (!p_buf) {
    return;
  }

  uint16_t opcode;
  const uint8_t* stream = p_buf->data + p_buf->offset;

  STREAM_TO_UINT16(opcode, stream);

  // Skip parameter length before logging
  stream++;
  btu_hcif_log_command_metrics(opcode, stream, android::bluetooth::hci::STATUS_UNKNOWN, false);

  bluetooth::shim::hci_layer_get_interface()->transmit_command(p_buf, btu_hcif_command_complete_evt,
                                                               btu_hcif_command_status_evt, NULL);
}

using hci_cmd_cb = base::OnceCallback<void(bluetooth::hci::CommandCompleteView)>;

struct cmd_with_cb_data {
  hci_cmd_cb cb;
};

static void cmd_with_cb_data_init(cmd_with_cb_data* cb_wrapper) {
  new (&cb_wrapper->cb) hci_cmd_cb;
}

static void cmd_with_cb_data_cleanup(cmd_with_cb_data* cb_wrapper) { cb_wrapper->cb.~hci_cmd_cb(); }

template <typename T>
static void log_classic_pairing_event_status_only(bluetooth::hci::CommandCompleteView view,
                                                  uint16_t opcode) {
  auto complete_view = T::Create(view);
  if (!complete_view.IsValid()) {
    log::error("Invalid complete_view for op_code 0x{:04x}", opcode);
    return;
  }
  uint16_t status = static_cast<uint16_t>(complete_view.GetStatus());
  bluetooth::metrics::LogMetricClassicPairingEvent(
          RawAddress::kEmpty, bluetooth::metrics::kUnknownConnectionHandle, opcode,
          android::bluetooth::hci::EVT_COMMAND_COMPLETE, status,
          android::bluetooth::hci::STATUS_UNKNOWN, 0);
}

template <typename T>
static void log_classic_pairing_event_status_bd_addr(bluetooth::hci::CommandCompleteView view,
                                                     uint16_t opcode) {
  auto complete_view = T::Create(view);
  if (!complete_view.IsValid()) {
    log::error("Invalid complete_view for op_code 0x{:04x}", opcode);
    return;
  }
  uint16_t status = static_cast<uint16_t>(complete_view.GetStatus());
  RawAddress bd_addr = RawAddress(complete_view.GetBdAddr().address);
  bluetooth::metrics::LogMetricClassicPairingEvent(
          bd_addr, bluetooth::metrics::kUnknownConnectionHandle, opcode,
          android::bluetooth::hci::EVT_COMMAND_COMPLETE, status,
          android::bluetooth::hci::STATUS_UNKNOWN, 0);
}

/**
 * Log command complete events that is not handled individually in this file
 * @param opcode opcode of the command
 * @param p_return_params pointer to returned parameter after parameter length
 *                        field
 */
static void btu_hcif_log_command_complete_metrics(bluetooth::hci::CommandCompleteView view) {
  uint16_t opcode = static_cast<uint16_t>(view.GetCommandOpCode());

  switch (opcode) {
    case HCI_DELETE_STORED_LINK_KEY:
      log_classic_pairing_event_status_only<bluetooth::hci::DeleteStoredLinkKeyCompleteView>(
              view, opcode);
      break;
    case HCI_READ_LOCAL_OOB_DATA:
      log_classic_pairing_event_status_only<bluetooth::hci::ReadLocalOobDataCompleteView>(view,
                                                                                          opcode);
      break;
    case HCI_READ_LOCAL_OOB_EXTENDED_DATA:
      log_classic_pairing_event_status_only<bluetooth::hci::ReadLocalOobExtendedDataCompleteView>(
              view, opcode);
      break;
    case HCI_WRITE_SIMPLE_PAIRING_MODE:
      log_classic_pairing_event_status_only<bluetooth::hci::WriteSimplePairingModeCompleteView>(
              view, opcode);
      break;
    case HCI_WRITE_SECURE_CONNS_SUPPORT:
      log_classic_pairing_event_status_only<
              bluetooth::hci::WriteSecureConnectionsHostSupportCompleteView>(view, opcode);
      break;
    case HCI_READ_ENCR_KEY_SIZE: {
      auto complete_view = bluetooth::hci::ReadEncryptionKeySizeCompleteView::Create(view);
      if (!complete_view.IsValid()) {
        log::error("Invalid complete_view for op_code 0x{:04x}", opcode);
        return;
      }
      uint16_t status = static_cast<uint16_t>(complete_view.GetStatus());
      uint16_t handle = complete_view.GetConnectionHandle();
      uint8_t key_size = complete_view.GetKeySize();
      bluetooth::metrics::LogMetricClassicPairingEvent(
              RawAddress::kEmpty, handle, opcode, android::bluetooth::hci::EVT_COMMAND_COMPLETE,
              status, android::bluetooth::hci::STATUS_UNKNOWN, key_size);
      break;
    }
    case HCI_LINK_KEY_REQUEST_REPLY:
      log_classic_pairing_event_status_bd_addr<bluetooth::hci::LinkKeyRequestReplyCompleteView>(
              view, opcode);
      break;
    case HCI_LINK_KEY_REQUEST_NEG_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::LinkKeyRequestNegativeReplyCompleteView>(view, opcode);
      break;
    case HCI_IO_CAPABILITY_REQUEST_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::IoCapabilityRequestReplyCompleteView>(view, opcode);
      break;
    case HCI_IO_CAP_REQ_NEG_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::IoCapabilityRequestNegativeReplyCompleteView>(view, opcode);
      break;
    case HCI_USER_CONF_REQUEST_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::UserConfirmationRequestReplyCompleteView>(view, opcode);
      break;
    case HCI_USER_CONF_VALUE_NEG_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::UserConfirmationRequestNegativeReplyCompleteView>(view, opcode);
      break;
    case HCI_USER_PASSKEY_REQ_REPLY:
      log_classic_pairing_event_status_bd_addr<bluetooth::hci::UserPasskeyRequestReplyCompleteView>(
              view, opcode);
      break;
    case HCI_USER_PASSKEY_REQ_NEG_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::UserPasskeyRequestNegativeReplyCompleteView>(view, opcode);
      break;
    case HCI_REM_OOB_DATA_REQ_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::RemoteOobDataRequestReplyCompleteView>(view, opcode);
      break;
    case HCI_REM_OOB_DATA_REQ_NEG_REPLY:
      log_classic_pairing_event_status_bd_addr<
              bluetooth::hci::RemoteOobDataRequestNegativeReplyCompleteView>(view, opcode);
      break;
  }
}

static void btu_hcif_command_complete_evt_with_cb_on_task(bluetooth::hci::CommandCompleteView view,
                                                          void* context) {
  if (!view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }

  btu_hcif_log_command_complete_metrics(view);

  cmd_with_cb_data* cb_wrapper = (cmd_with_cb_data*)context;
  std::move(cb_wrapper->cb).Run(std::move(view));
  cmd_with_cb_data_cleanup(cb_wrapper);
  osi_free(cb_wrapper);
}

static void btu_hcif_command_complete_evt_with_cb(bluetooth::hci::CommandCompleteView view,
                                                  void* context) {
  do_in_main_thread(
          base::BindOnce(btu_hcif_command_complete_evt_with_cb_on_task, std::move(view), context));
}

static void btu_hcif_command_status_evt_with_cb_on_task(uint8_t status, BT_HDR* event,
                                                        void* context) {
  command_opcode_t opcode;
  uint8_t* stream = event->data + event->offset;
  STREAM_TO_UINT16(opcode, stream);

  log::assert_that(status != 0, "assert failed: status != 0");

  // stream + 1 to skip parameter length field
  // No need to check length since stream is written by us
  btu_hcif_log_command_metrics(opcode, stream + 1, status, true);

  // synthesize a command complete event with the error status
  auto packet = std::make_shared<std::vector<uint8_t>>(6);
  (*packet)[0] = static_cast<uint8_t>(bluetooth::hci::EventCode::COMMAND_COMPLETE);
  (*packet)[1] = 4;
  (*packet)[2] = 1;  // num_hci_command_packets
  (*packet)[3] = static_cast<uint8_t>(opcode & 0xFF);
  (*packet)[4] = static_cast<uint8_t>(opcode >> 8);
  (*packet)[5] = status;  // Use the actual status here

  auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(packet);
  auto event_view = bluetooth::hci::EventView::Create(packet_view);
  auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
  if (!command_complete_view.IsValid()) {
    log::error("Invalid command complete view");
    return;
  }

  cmd_with_cb_data* cb_wrapper = (cmd_with_cb_data*)context;
  std::move(cb_wrapper->cb).Run(std::move(command_complete_view));
  cmd_with_cb_data_cleanup(cb_wrapper);
  osi_free(cb_wrapper);

  osi_free(event);
}

static void btu_hcif_command_status_evt_with_cb(uint8_t status, BT_HDR* command, void* context) {
  // Command is pending, we  report only error.
  if (!status) {
    osi_free(command);
    return;
  }

  do_in_main_thread(
          base::BindOnce(btu_hcif_command_status_evt_with_cb_on_task, status, command, context));
}

/* This function is called to send commands to the Host Controller. |cb| is
 * called when command status event is called with error code, or when the
 * command complete event is received. */
void btu_hcif_send_cmd_with_cb(uint16_t opcode, uint8_t* params, uint8_t params_len,
                               hci_cmd_cb cb) {
  BT_HDR* p = (BT_HDR*)osi_malloc(HCI_CMD_BUF_SIZE);
  uint8_t* pp = (uint8_t*)(p + 1);

  p->len = HCIC_PREAMBLE_SIZE + params_len;
  p->offset = 0;

  UINT16_TO_STREAM(pp, opcode);
  UINT8_TO_STREAM(pp, params_len);
  if (params) {
    memcpy(pp, params, params_len);
  }

  btu_hcif_log_command_metrics(opcode, pp, android::bluetooth::hci::STATUS_UNKNOWN, false);

  cmd_with_cb_data* cb_wrapper = (cmd_with_cb_data*)osi_malloc(sizeof(cmd_with_cb_data));

  cmd_with_cb_data_init(cb_wrapper);
  cb_wrapper->cb = std::move(cb);

  bluetooth::shim::hci_layer_get_interface()->transmit_command(
          p, btu_hcif_command_complete_evt_with_cb, btu_hcif_command_status_evt_with_cb,
          (void*)cb_wrapper);
}

/*******************************************************************************
 *
 * Function         btu_hcif_authentication_comp_evt
 *
 * Description      Process event HCI_AUTHENTICATION_COMP_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_authentication_comp_evt(uint8_t* p) {
  uint8_t status;
  uint16_t handle;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);

  btm_sec_auth_complete(handle, static_cast<tHCI_STATUS>(status));
}

/*******************************************************************************
 *
 * Function         btu_hcif_encryption_change_evt
 *
 * Description      Process event HCI_ENCRYPTION_CHANGE_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_encryption_change_evt(uint8_t* p) {
  uint8_t status;
  uint16_t handle;
  uint8_t encr_enable;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);
  STREAM_TO_UINT8(encr_enable, p);

  btm_sec_encryption_change_evt(handle, static_cast<tHCI_STATUS>(status), encr_enable, 0);
}

/*******************************************************************************
 *
 * Function         btu_hcif_encryption_change_evt_v2
 *
 * Description      Process event HCI_ENCRYPTION_CHANGE_EVT_V2
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_encryption_change_evt_v2(uint8_t* p) {
  uint8_t status;
  uint16_t handle;
  uint8_t encr_enable;
  uint8_t key_size;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);
  STREAM_TO_UINT8(encr_enable, p);
  STREAM_TO_UINT8(key_size, p);

  btm_sec_encryption_change_evt(handle, static_cast<tHCI_STATUS>(status), encr_enable, key_size);
}

/*******************************************************************************
 *
 * Function         btu_hcif_esco_connection_comp_evt
 *
 * Description      Process event HCI_ESCO_CONNECTION_COMP_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_esco_connection_comp_evt(const uint8_t* p) {
  tBTM_ESCO_DATA data;
  uint16_t handle;
  RawAddress bda;
  uint8_t status;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);
  STREAM_TO_BDADDR(bda, p);

  STREAM_TO_UINT8(data.link_type, p);
  STREAM_SKIP_UINT8(p);   // tx_interval
  STREAM_SKIP_UINT8(p);   // retrans_window
  STREAM_SKIP_UINT16(p);  // rx_pkt_len
  STREAM_SKIP_UINT16(p);  // tx_pkt_len
  STREAM_SKIP_UINT8(p);   // air_mode

  handle = HCID_GET_HANDLE(handle);
  data.bd_addr = bda;
  if (status == HCI_SUCCESS) {
    log::assert_that(handle <= HCI_HANDLE_MAX,
                     "Received eSCO connection complete event with invalid "
                     "handle: 0x{:X} that should be <= 0x{:X}",
                     handle, HCI_HANDLE_MAX);
    btm_sco_connected(bda, handle, &data);
  } else {
    btm_sco_connection_failed(static_cast<tHCI_STATUS>(status), bda, handle, &data);
  }
}

/*******************************************************************************
 *
 * Function         btu_hcif_esco_connection_chg_evt
 *
 * Description      Process event HCI_ESCO_CONNECTION_CHANGED_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_esco_connection_chg_evt(uint8_t* p) {
  uint16_t handle;
  uint16_t tx_pkt_len;
  uint16_t rx_pkt_len;
  uint8_t status;
  uint8_t tx_interval;
  uint8_t retrans_window;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);

  STREAM_TO_UINT8(tx_interval, p);
  STREAM_TO_UINT8(retrans_window, p);
  STREAM_TO_UINT16(rx_pkt_len, p);
  STREAM_TO_UINT16(tx_pkt_len, p);

  handle = HCID_GET_HANDLE(handle);
}

/*******************************************************************************
 *
 * Function         btu_hcif_hdl_command_complete
 *
 * Description      Handle command complete event
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_hdl_command_complete(bluetooth::hci::CommandCompleteView view) {
  uint16_t opcode = static_cast<uint16_t>(view.GetCommandOpCode());
  switch (opcode) {
    case HCI_SET_EVENT_FILTER:
      break;

    case HCI_DELETE_STORED_LINK_KEY:
      break;

    case HCI_READ_RSSI:
      btm_read_rssi_complete(std::move(view));
      break;

    case HCI_READ_AUTOMATIC_FLUSH_TIMEOUT:
      btm_read_automatic_flush_timeout_complete(std::move(view));
      break;

    case HCI_CREATE_CONNECTION_CANCEL:
      btu_hcif_create_conn_cancel_complete(std::move(view));
      break;

    case HCI_READ_LOCAL_OOB_DATA:
      btu_hcif_read_local_oob_complete(std::move(view));
      break;

    case HCI_READ_LOCAL_OOB_EXTENDED_DATA:
      btu_hcif_read_local_oob_extended_complete(std::move(view));
      break;

    case HCI_READ_INQ_TX_POWER_LEVEL:
      break;

    case HCI_BLE_CREATE_LL_CONN:
    case HCI_LE_EXTENDED_CREATE_CONNECTION:
      // No command complete event for those commands according to spec
      log::error("No command complete expected, but received!");
      break;

    case HCI_BLE_TRANSMITTER_TEST:
    case HCI_BLE_RECEIVER_TEST:
    case HCI_BLE_TEST_END:
      btm_ble_test_command_complete(std::move(view));
      break;

    case HCI_BLE_ADD_DEV_RESOLVING_LIST:
      btm_ble_add_resolving_list_entry_complete(std::move(view));
      break;

    case HCI_BLE_RM_DEV_RESOLVING_LIST:
      btm_ble_remove_resolving_list_entry_complete(std::move(view));
      break;

    case HCI_BLE_CLEAR_RESOLVING_LIST:
      btm_ble_clear_resolving_list_complete(std::move(view));
      break;

    case HCI_BLE_READ_RESOLVABLE_ADDR_PEER:
      btm_ble_read_resolving_list_entry_complete(std::move(view));
      break;

    // Explicitly handled command complete events
    case HCI_BLE_READ_RESOLVABLE_ADDR_LOCAL:
    case HCI_BLE_SET_ADDR_RESOLUTION_ENABLE:
    case HCI_BLE_SET_RAND_PRIV_ADDR_TIMEOUT:
    case HCI_WRITE_CLASS_OF_DEVICE:
    case HCI_WRITE_DEF_POLICY_SETTINGS:
    case HCI_WRITE_EXT_INQ_RESPONSE:
    case HCI_WRITE_INQSCAN_TYPE:
    case HCI_WRITE_INQUIRYSCAN_CFG:
    case HCI_WRITE_INQUIRY_MODE:
    case HCI_WRITE_LINK_SUPER_TOUT:
    case HCI_WRITE_PAGESCAN_CFG:
    case HCI_WRITE_PAGESCAN_TYPE:
    case HCI_WRITE_PAGE_TOUT:
    case HCI_WRITE_SCAN_ENABLE:
    case HCI_WRITE_VOICE_SETTINGS:
      break;

    default:
      log::error("Command complete for opcode:0x{:02x} should not be handled here", opcode);
      break;
  }
}

/*******************************************************************************
 *
 * Function         btu_hcif_command_complete_evt
 *
 * Description      Process event HCI_COMMAND_COMPLETE_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_command_complete_evt_on_task(bluetooth::hci::CommandCompleteView view) {
  btu_hcif_log_command_complete_metrics(view);

  btu_hcif_hdl_command_complete(std::move(view));
}

static void btu_hcif_command_complete_evt(bluetooth::hci::CommandCompleteView view,
                                          void* /* context */) {
  do_in_main_thread(base::BindOnce(btu_hcif_command_complete_evt_on_task, std::move(view)));
}

/*******************************************************************************
 *
 * Function         btu_hcif_hdl_command_status
 *
 * Description      Handle a command status event
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_hdl_command_status(uint16_t opcode, uint8_t status, const uint8_t* p_cmd) {
  log::assert_that(p_cmd != nullptr, "Null command for opcode 0x{:x}", opcode);
  p_cmd++;  // Skip parameter total length

  const tHCI_STATUS hci_status = to_hci_status_code(status);

  RawAddress bd_addr;
  uint16_t handle;

  switch (opcode) {
    case HCI_SWITCH_ROLE:
      if (status != HCI_SUCCESS) {
        // Tell BTM that the command failed
        STREAM_TO_BDADDR(bd_addr, p_cmd);
        btm_acl_role_changed(hci_status, bd_addr, HCI_ROLE_UNKNOWN);
      }
      break;
    case HCI_CREATE_CONNECTION:
      if (status != HCI_SUCCESS) {
        STREAM_TO_BDADDR(bd_addr, p_cmd);
        on_acl_br_edr_failed(bd_addr, hci_status, /* locally_initiated */ true);
      }
      break;
    case HCI_AUTHENTICATION_REQUESTED:
      if (status != HCI_SUCCESS) {
        // Device refused to start authentication
        // This is treated as an authentication failure
        btm_sec_auth_complete(HCI_INVALID_HANDLE, hci_status);
      }
      break;
    case HCI_SET_CONN_ENCRYPTION:
      if (status != HCI_SUCCESS) {
        // Device refused to start encryption
        // This is treated as an encryption failure
        btm_sec_encrypt_change(HCI_INVALID_HANDLE, hci_status, false, 0);
      }
      break;
    case HCI_SETUP_ESCO_CONNECTION:
    case HCI_ENH_SETUP_ESCO_CONNECTION:
      if (status != HCI_SUCCESS) {
        btm_sco_create_command_status_failed(hci_status);
      }
      break;

    case HCI_BLE_START_ENC:
      // Race condition: disconnection happened right before we send
      // "LE Encrypt", controller responds with no connection, we should
      // cancel the encryption attempt, rather than unpair the device.
      if (status == HCI_ERR_NO_CONNECTION) {
        smp_cancel_start_encryption_attempt(RawAddress::kEmpty);
      }
      break;

    // Link Policy Commands
    case HCI_EXIT_SNIFF_MODE:
      if (status != HCI_SUCCESS) {
        // Allow SCO initiation to continue if waiting for change mode event
        STREAM_TO_UINT16(handle, p_cmd);
        btm_sco_chk_pend_unsniff(hci_status, handle);
      }
      FALLTHROUGH_INTENDED; /* FALLTHROUGH */
    case HCI_SNIFF_MODE:
      btm_pm_proc_cmd_status(hci_status);
      break;

    // Command status event not handled by a specialized module
    case HCI_READ_RMT_CLOCK_OFFSET:    // 0x041f
    case HCI_CHANGE_CONN_PACKET_TYPE:  // 0x040f
      if (hci_status != HCI_SUCCESS) {
        log::warn("Received bad command status for opcode:0x{:02x} status:{}", opcode,
                  hci_status_code_text(hci_status));
      }
      break;

    default:
      log::error(
              "Command status for opcode:0x{:02x} should not be handled here "
              "status:{}",
              opcode, hci_status_code_text(hci_status));
  }
}

void bluetooth::legacy::testing::btu_hcif_hdl_command_status(uint16_t opcode, uint8_t status,
                                                             const uint8_t* p_cmd) {
  ::btu_hcif_hdl_command_status(opcode, status, p_cmd);
}

void bluetooth::legacy::testing::btu_hcif_process_event(uint8_t controller_id,
                                                        const BT_HDR* p_msg) {
  ::btu_hcif_process_event(controller_id, p_msg);
}

/*******************************************************************************
 *
 * Function         btu_hcif_command_status_evt
 *
 * Description      Process event HCI_COMMAND_STATUS_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_command_status_evt_on_task(uint8_t status, BT_HDR* event) {
  command_opcode_t opcode;
  uint8_t* stream = event->data + event->offset;
  STREAM_TO_UINT16(opcode, stream);

  // stream + 1 to skip parameter length field
  // No need to check length since stream is written by us
  btu_hcif_log_command_metrics(opcode, stream + 1, status, true);

  btu_hcif_hdl_command_status(opcode, status, stream);
  osi_free(event);
}

static void btu_hcif_command_status_evt(uint8_t status, BT_HDR* command, void* /* context */) {
  do_in_main_thread(base::BindOnce(btu_hcif_command_status_evt_on_task, status, command));
}

/*******************************************************************************
 *
 * Function         btu_hcif_mode_change_evt
 *
 * Description      Process event HCI_MODE_CHANGE_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_mode_change_evt(uint8_t* p) {
  uint8_t status;
  uint16_t handle;
  uint8_t current_mode;
  uint16_t interval;

  STREAM_TO_UINT8(status, p);

  STREAM_TO_UINT16(handle, p);
  STREAM_TO_UINT8(current_mode, p);
  STREAM_TO_UINT16(interval, p);

  btm_pm_proc_mode_change(static_cast<tHCI_STATUS>(status), handle,
                          static_cast<tHCI_MODE>(current_mode), interval);
  btm_sco_chk_pend_unsniff(static_cast<tHCI_STATUS>(status), handle);

#if (HID_DEV_INCLUDED == TRUE && HID_DEV_PM_INCLUDED == TRUE)
  hidd_pm_proc_mode_change(status, current_mode, interval);
#endif
}

/* Parsing functions for btm functions */

void btu_hcif_sec_pin_code_request(const uint8_t* p) {
  RawAddress bda;

  STREAM_TO_BDADDR(bda, p);
  btm_sec_pin_code_request(bda);
}
void btu_hcif_sec_link_key_request(const uint8_t* p) {
  RawAddress bda;
  STREAM_TO_BDADDR(bda, p);
  btm_sec_link_key_request(bda);
}
void btu_hcif_rem_oob_req(const uint8_t* p) {
  RawAddress bda;
  STREAM_TO_BDADDR(bda, p);
  btm_rem_oob_req(bda);
}
void btu_hcif_simple_pair_complete(const uint8_t* p) {
  RawAddress bd_addr;
  uint8_t status;
  status = *p++;
  STREAM_TO_BDADDR(bd_addr, p);
  btm_simple_pair_complete(bd_addr, status);
}
void btu_hcif_sec_rmt_host_support_feat_evt(const uint8_t* p) {
  RawAddress bd_addr; /* peer address */
  uint8_t features_0;

  STREAM_TO_BDADDR(bd_addr, p);
  STREAM_TO_UINT8(features_0, p);
  btm_sec_rmt_host_support_feat_evt(bd_addr, features_0);
}
void btu_hcif_proc_sp_req_evt(tBTM_SP_EVT event, const uint8_t* p) {
  RawAddress bda;
  uint32_t value = 0;

  /* All events start with bd_addr */
  STREAM_TO_BDADDR(bda, p);
  switch (event) {
    case BTM_SP_CFM_REQ_EVT:
    case BTM_SP_KEY_NOTIF_EVT:
      STREAM_TO_UINT32(value, p);
      break;
    case BTM_SP_KEY_REQ_EVT:
      // No value needed.
      break;
    default:
      log::warn("unexpected event:{}", sp_evt_to_text(event));
      break;
  }
  btm_proc_sp_req_evt(event, bda, value);
}
void btu_hcif_create_conn_cancel_complete(bluetooth::hci::CommandCompleteView view) {
  auto complete_view = bluetooth::hci::CreateConnectionCancelCompleteView::Create(view);
  if (!complete_view.IsValid()) {
    log::error("Invalid complete_view");
    return;
  }
  uint8_t status = static_cast<uint8_t>(complete_view.GetStatus());
  RawAddress bd_addr = RawAddress(complete_view.GetBdAddr().address);
  btm_create_conn_cancel_complete(status, bd_addr);
}
void btu_hcif_read_local_oob_complete(bluetooth::hci::CommandCompleteView view) {
  tBTM_SP_LOC_OOB evt_data = {};
  auto read_local_oob_complete_view = bluetooth::hci::ReadLocalOobDataCompleteView::Create(view);
  if (!read_local_oob_complete_view.IsValid()) {
    log::error("Invalid read_local_oob_complete_view");
    return;
  }
  uint8_t status = static_cast<uint8_t>(read_local_oob_complete_view.GetStatus());
  if (status == HCI_SUCCESS) {
    evt_data.status = tBTM_STATUS::BTM_SUCCESS;
  } else {
    evt_data.status = tBTM_STATUS::BTM_ERR_PROCESSING;
  }
  evt_data.c_192 = read_local_oob_complete_view.GetC();
  evt_data.r_192 = read_local_oob_complete_view.GetR();
  btm_read_local_oob_complete(evt_data);
}

void btu_hcif_read_local_oob_extended_complete(bluetooth::hci::CommandCompleteView view) {
  auto read_local_oob_extended_complete_view =
          bluetooth::hci::ReadLocalOobExtendedDataCompleteView::Create(view);
  if (!read_local_oob_extended_complete_view.IsValid()) {
    log::error("Invalid read_local_oob_extended_complete_view");
    return;
  }

  tBTM_SP_LOC_OOB evt_data = {};
  uint8_t status = static_cast<uint8_t>(read_local_oob_extended_complete_view.GetStatus());
  if (status == HCI_SUCCESS) {
    evt_data.status = tBTM_STATUS::BTM_SUCCESS;
  } else {
    evt_data.status = tBTM_STATUS::BTM_ERR_PROCESSING;
  }
  evt_data.c_192 = read_local_oob_extended_complete_view.GetC192();
  evt_data.r_192 = read_local_oob_extended_complete_view.GetR192();
  evt_data.c_256 = read_local_oob_extended_complete_view.GetC256();
  evt_data.r_256 = read_local_oob_extended_complete_view.GetR256();
  btm_read_local_oob_complete(evt_data);
}

/*******************************************************************************
 *
 * Function         btu_hcif_link_key_notification_evt
 *
 * Description      Process event HCI_LINK_KEY_NOTIFICATION_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_link_key_notification_evt(const uint8_t* p) {
  RawAddress bda;
  Octet16 key;
  uint8_t key_type;

  STREAM_TO_BDADDR(bda, p);
  STREAM_TO_ARRAY16(key.data(), p);
  STREAM_TO_UINT8(key_type, p);

  btm_sec_link_key_notification(bda, key, key_type);
}

/*******************************************************************************
 *
 * Function         btu_hcif_read_clock_off_comp_evt
 *
 * Description      Process event HCI_READ_CLOCK_OFF_COMP_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_read_clock_off_comp_evt(uint8_t* p) {
  uint8_t status;
  uint16_t handle;
  uint16_t clock_offset;

  STREAM_TO_UINT8(status, p);

  /* If failed to get clock offset just drop the result */
  if (status != HCI_SUCCESS) {
    return;
  }

  STREAM_TO_UINT16(handle, p);
  STREAM_TO_UINT16(clock_offset, p);

  handle = HCID_GET_HANDLE(handle);

  btm_sec_update_clock_offset(handle, clock_offset);
}

/**********************************************
 * Simple Pairing Events
 **********************************************/

/*******************************************************************************
 *
 * Function         btu_hcif_io_cap_request_evt
 *
 * Description      Process event HCI_IO_CAPABILITY_REQUEST_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_io_cap_request_evt(const uint8_t* p) {
  RawAddress bda;
  STREAM_TO_BDADDR(bda, p);
  btm_io_capabilities_req(bda);
}

/*******************************************************************************
 *
 * Function         btu_hcif_io_cap_request_evt
 *
 * Description      Process event HCI_IO_CAPABILITY_REQUEST_EVT
 *
 * Returns          void
 *
 ******************************************************************************/
static void btu_hcif_io_cap_response_evt(const uint8_t* p) {
  tBTM_SP_IO_RSP evt_data;

  STREAM_TO_BDADDR(evt_data.bd_addr, p);

  uint8_t io_cap;
  STREAM_TO_UINT8(io_cap, p);
  evt_data.io_cap = static_cast<BtIoCap>(io_cap);

  STREAM_TO_UINT8(evt_data.oob_data, p);
  STREAM_TO_UINT8(evt_data.auth_req, p);
  btm_io_capabilities_rsp(evt_data);
}

/**********************************************
 * End of Simple Pairing Events
 **********************************************/

static void btu_hcif_encryption_key_refresh_cmpl_evt(uint8_t* p) {
  uint8_t status;
  uint16_t handle;

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);

  btm_sec_encryption_key_refresh_complete(handle, static_cast<tHCI_STATUS>(status));
}

/**********************************************
 * BLE Events
 **********************************************/

static void btu_ble_proc_ltk_req(uint8_t* p, uint16_t evt_len) {
  uint16_t ediv, handle;
  Octet8 rand;

  // following the spec in Core_v5.3/Vol 4/Part E
  // / 7.7.65.5 LE Long Term Key Request event
  // A BLE Long Term Key Request event contains:
  // - 1-byte subevent (already consumed in btu_hcif_process_event)
  // - 2-byte connection handler
  // - 8-byte random number
  // - 2 byte Encrypted_Diversifier
  if (evt_len < 2 + 8 + 2) {
    log::error("Event packet too short");
    return;
  }

  STREAM_TO_UINT16(handle, p);
  STREAM_TO_ARRAY(rand.data(), p, kOctet8Length);
  STREAM_TO_UINT16(ediv, p);
  btm_ble_ltk_request(handle, rand, ediv);
  /* This is empty until an upper layer cares about returning event */
}

/**********************************************
 * End of BLE Events Handler
 **********************************************/

void btu_hci_msg_process(BT_HDR* p_msg) {
  /* Determine the input message type. */
  switch (p_msg->event & BT_EVT_MASK) {
    case BT_EVT_TO_BTU_HCI_EVT:
      btu_hcif_process_event((uint8_t)(p_msg->event & BT_SUB_EVT_MASK), p_msg);
      osi_free(p_msg);
      break;

    case BT_EVT_TO_BTU_HCI_ISO:
      IsoManager::GetInstance()->HandleIsoData(p_msg);
      osi_free(p_msg);
      break;

    default:
      osi_free(p_msg);
      break;
  }
}
