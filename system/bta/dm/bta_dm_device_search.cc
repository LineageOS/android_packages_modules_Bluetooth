/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "bt_bta_sd"

#include "bta/dm/bta_dm_device_search.h"

#include <android_bluetooth_flags.h>
#include <base/functional/bind.h>
#include <base/strings/stringprintf.h>
#include <bluetooth/log.h>
#include <stddef.h>

#include <cstdint>
#include <string>
#include <variant>
#include <vector>

#include "android_bluetooth_flags.h"
#include "bta/dm/bta_dm_device_search_int.h"
#include "bta/dm/bta_dm_disc_legacy.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_sdp_api.h"
#include "btif/include/btif_config.h"
#include "common/circular_buffer.h"
#include "common/init_flags.h"
#include "common/strings.h"
#include "device/include/interop.h"
#include "internal_include/bt_target.h"
#include "main/shim/dumpsys.h"
#include "os/logging/log_adapter.h"
#include "osi/include/allocator.h"
#include "stack/btm/btm_int_types.h"  // TimestampedStringCircularBuffer
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/bt_name.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/btm_sec_api.h"  // BTM_IsRemoteNameKnown
#include "stack/include/gap_api.h"      // GAP_BleReadPeerPrefConnParams
#include "stack/include/hidh_api.h"
#include "stack/include/main_thread.h"
#include "stack/include/sdp_status.h"
#include "stack/sdp/sdpint.h"  // is_sdp_pbap_pce_disabled
#include "storage/config_keys.h"
#include "types/raw_address.h"

using namespace bluetooth;

namespace {
constexpr char kBtmLogTag[] = "DEV_SEARCH";

tBTA_DM_SEARCH_CB bta_dm_search_cb;
}  // namespace

static void bta_dm_inq_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir,
                                  uint16_t eir_len);
static void bta_dm_inq_cmpl();
static void bta_dm_inq_cmpl_cb(void* p_result);
static void bta_dm_search_cmpl();
static void bta_dm_discover_next_device(void);
static void bta_dm_remname_cback(const tBTM_REMOTE_DEV_NAME* p);

static bool bta_dm_read_remote_device_name(const RawAddress& bd_addr,
                                           tBT_TRANSPORT transport);
static void bta_dm_discover_name(const RawAddress& remote_bd_addr);
static void bta_dm_execute_queued_search_request();
static void bta_dm_search_cancel_notify();
static void bta_dm_disable_search();

static void bta_dm_search_sm_execute(tBTA_DM_DEV_SEARCH_EVT event,
                                     std::unique_ptr<tBTA_DM_SEARCH_MSG> msg);
static void bta_dm_observe_results_cb(tBTM_INQ_RESULTS* p_inq,
                                      const uint8_t* p_eir, uint16_t eir_len);
static void bta_dm_observe_cmpl_cb(void* p_result);

static void bta_dm_search_set_state(tBTA_DM_DEVICE_SEARCH_STATE state) {
  bta_dm_search_cb.search_state = state;
}
static tBTA_DM_DEVICE_SEARCH_STATE bta_dm_search_get_state() {
  return bta_dm_search_cb.search_state;
}

static void post_search_evt(tBTA_DM_DEV_SEARCH_EVT event,
                            std::unique_ptr<tBTA_DM_SEARCH_MSG> msg) {
  if (do_in_main_thread(FROM_HERE, base::BindOnce(&bta_dm_search_sm_execute,
                                                  event, std::move(msg))) !=
      BT_STATUS_SUCCESS) {
    log::error("post_search_evt failed");
  }
}

void bta_dm_disc_disable_search() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    log::info("no-op when flag is disabled");
    return;
  }
  bta_dm_disable_search();
}

/*******************************************************************************
 *
 * Function         bta_dm_search_start
 *
 * Description      Starts an inquiry
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_search_start(tBTA_DM_API_SEARCH& search) {
  get_btm_client_interface().db.BTM_ClearInqDb(nullptr);
  /* save search params */
  bta_dm_search_cb.p_device_search_cback = search.p_cback;

  const tBTM_STATUS btm_status =
      BTM_StartInquiry(bta_dm_inq_results_cb, bta_dm_inq_cmpl_cb);
  switch (btm_status) {
    case BTM_CMD_STARTED:
      // Completion callback will be executed when controller inquiry
      // timer pops or is cancelled by the user
      break;
    default:
      log::warn("Unable to start device discovery search btm_status:{}",
                btm_status_text(btm_status));
      // Not started so completion callback is executed now
      bta_dm_inq_cmpl();
      break;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_search_cancel
 *
 * Description      Cancels an ongoing search for devices
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_search_cancel() {
  if (BTM_IsInquiryActive()) {
    BTM_CancelInquiry();
    bta_dm_search_cancel_notify();
    bta_dm_search_cmpl();
  }
  /* If no Service Search going on then issue cancel remote name in case it is
     active */
  else if (!bta_dm_search_cb.name_discover_done) {
    get_btm_client_interface().peer.BTM_CancelRemoteDeviceName();
#ifndef TARGET_FLOSS
    /* bta_dm_search_cmpl is called when receiving the remote name cancel evt */
    if (!IS_FLAG_ENABLED(
            bta_dm_defer_device_discovery_state_change_until_rnr_complete)) {
      bta_dm_search_cmpl();
    }
#endif
  } else {
    bta_dm_inq_cmpl();
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_inq_cmpl_cb
 *
 * Description      Inquiry complete callback from BTM
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_inq_cmpl_cb(void* /* p_result */) {
  log::verbose("");

  bta_dm_inq_cmpl();
}

/*******************************************************************************
 *
 * Function         bta_dm_inq_results_cb
 *
 * Description      Inquiry results callback from BTM
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_inq_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir,
                                  uint16_t eir_len) {
  tBTA_DM_SEARCH result;
  tBTM_INQ_INFO* p_inq_info;
  uint16_t service_class;

  result.inq_res.bd_addr = p_inq->remote_bd_addr;

  // Pass the original address to GattService#onScanResult
  result.inq_res.original_bda = p_inq->original_bda;

  result.inq_res.dev_class = p_inq->dev_class;
  BTM_COD_SERVICE_CLASS(service_class, p_inq->dev_class);
  result.inq_res.is_limited =
      (service_class & BTM_COD_SERVICE_LMTD_DISCOVER) ? true : false;
  result.inq_res.rssi = p_inq->rssi;

  result.inq_res.ble_addr_type = p_inq->ble_addr_type;
  result.inq_res.inq_result_type = p_inq->inq_result_type;
  result.inq_res.device_type = p_inq->device_type;
  result.inq_res.flag = p_inq->flag;
  result.inq_res.include_rsi = p_inq->include_rsi;
  result.inq_res.clock_offset = p_inq->clock_offset;

  /* application will parse EIR to find out remote device name */
  result.inq_res.p_eir = const_cast<uint8_t*>(p_eir);
  result.inq_res.eir_len = eir_len;

  result.inq_res.ble_evt_type = p_inq->ble_evt_type;

  p_inq_info =
      get_btm_client_interface().db.BTM_InqDbRead(p_inq->remote_bd_addr);
  if (p_inq_info != NULL) {
    /* initialize remt_name_not_required to false so that we get the name by
     * default */
    result.inq_res.remt_name_not_required = false;
  }

  if (bta_dm_search_cb.p_device_search_cback)
    bta_dm_search_cb.p_device_search_cback(BTA_DM_INQ_RES_EVT, &result);

  if (p_inq_info) {
    /* application indicates if it knows the remote name, inside the callback
     copy that to the inquiry data base*/
    if (result.inq_res.remt_name_not_required)
      p_inq_info->appl_knows_rem_name = true;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_remname_cback
 *
 * Description      Remote name complete call back from BTM
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_remname_cback(const tBTM_REMOTE_DEV_NAME* p_remote_name) {
  log::assert_that(p_remote_name != nullptr,
                   "assert failed: p_remote_name != nullptr");

  log::info(
      "Remote name request complete peer:{} btm_status:{} hci_status:{} "
      "name[0]:{:c} length:{}",
      p_remote_name->bd_addr, btm_status_text(p_remote_name->status),
      hci_error_code_text(p_remote_name->hci_status),
      p_remote_name->remote_bd_name[0],
      strnlen((const char*)p_remote_name->remote_bd_name, BD_NAME_LEN));

  if (bta_dm_search_cb.peer_bdaddr != p_remote_name->bd_addr) {
    // if we got a different response, maybe ignore it
    // we will have made a request directly from BTM_ReadRemoteDeviceName so we
    // expect a dedicated response for us
    if (p_remote_name->hci_status == HCI_ERR_CONNECTION_EXISTS) {
      log::info(
          "Assume command failed due to disconnection hci_status:{} peer:{}",
          hci_error_code_text(p_remote_name->hci_status),
          p_remote_name->bd_addr);
    } else {
      log::info(
          "Ignored remote name response for the wrong address exp:{} act:{}",
          bta_dm_search_cb.peer_bdaddr, p_remote_name->bd_addr);
      return;
    }
  }

  /* remote name discovery is done but it could be failed */
  bta_dm_search_cb.name_discover_done = true;
  bd_name_copy(bta_dm_search_cb.peer_name, p_remote_name->remote_bd_name);

  auto msg = std::make_unique<tBTA_DM_SEARCH_MSG>(tBTA_DM_REMOTE_NAME{});
  auto& rmt_name_msg = std::get<tBTA_DM_REMOTE_NAME>(*msg);
  rmt_name_msg.bd_addr = bta_dm_search_cb.peer_bdaddr;
  rmt_name_msg.hci_status = p_remote_name->hci_status;
  bd_name_copy(rmt_name_msg.bd_name, p_remote_name->remote_bd_name);

  post_search_evt(BTA_DM_REMT_NAME_EVT, std::move(msg));
}

/*******************************************************************************
 *
 * Function         bta_dm_read_remote_device_name
 *
 * Description      Initiate to get remote device name
 *
 * Returns          true if started to get remote name
 *
 ******************************************************************************/
static bool bta_dm_read_remote_device_name(const RawAddress& bd_addr,
                                           tBT_TRANSPORT transport) {
  tBTM_STATUS btm_status;

  log::verbose("");

  bta_dm_search_cb.peer_bdaddr = bd_addr;
  bta_dm_search_cb.peer_name[0] = 0;

  btm_status = get_btm_client_interface().peer.BTM_ReadRemoteDeviceName(
      bta_dm_search_cb.peer_bdaddr, bta_dm_remname_cback, transport);

  if (btm_status == BTM_CMD_STARTED) {
    log::verbose("BTM_ReadRemoteDeviceName is started");

    return (true);
  } else if (btm_status == BTM_BUSY) {
    log::verbose("BTM_ReadRemoteDeviceName is busy");

    return (true);
  } else {
    log::warn("BTM_ReadRemoteDeviceName returns 0x{:02X}", btm_status);

    return (false);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_inq_cmpl
 *
 * Description      Process the inquiry complete event from BTM
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_inq_cmpl() {
  if (bta_dm_search_get_state() == BTA_DM_SEARCH_CANCELLING) {
    bta_dm_search_set_state(BTA_DM_SEARCH_IDLE);
    bta_dm_execute_queued_search_request();
    return;
  }

  if (bta_dm_search_get_state() != BTA_DM_SEARCH_ACTIVE) {
    return;
  }

  log::verbose("bta_dm_inq_cmpl");

  bta_dm_search_cb.p_btm_inq_info =
      get_btm_client_interface().db.BTM_InqDbFirst();
  if (bta_dm_search_cb.p_btm_inq_info != NULL) {
    /* start name discovery from the first device on inquiry result
     */
    bta_dm_search_cb.name_discover_done = false;
    bta_dm_search_cb.peer_name[0] = 0;
    bta_dm_discover_name(
        bta_dm_search_cb.p_btm_inq_info->results.remote_bd_addr);
  } else {
    bta_dm_search_cmpl();
  }
}

static void bta_dm_remote_name_cmpl(
    const tBTA_DM_REMOTE_NAME& remote_name_msg) {
  BTM_LogHistory(kBtmLogTag, remote_name_msg.bd_addr, "Remote name completed",
                 base::StringPrintf(
                     "status:%s state:%s name:\"%s\"",
                     hci_status_code_text(remote_name_msg.hci_status).c_str(),
                     bta_dm_state_text(bta_dm_search_get_state()).c_str(),
                     PRIVATE_NAME(remote_name_msg.bd_name)));

  tBTM_INQ_INFO* p_btm_inq_info =
      get_btm_client_interface().db.BTM_InqDbRead(remote_name_msg.bd_addr);
  if (!bd_name_is_empty(remote_name_msg.bd_name) && p_btm_inq_info) {
    p_btm_inq_info->appl_knows_rem_name = true;
  }

  // Callback with this property
  if (bta_dm_search_cb.p_device_search_cback != nullptr) {
    tBTA_DM_SEARCH search_data = {
        .name_res = {.bd_addr = remote_name_msg.bd_addr, .bd_name = {}},
    };
    if (remote_name_msg.hci_status == HCI_SUCCESS) {
      bd_name_copy(search_data.name_res.bd_name, remote_name_msg.bd_name);
    }
    bta_dm_search_cb.p_device_search_cback(BTA_DM_NAME_READ_EVT, &search_data);
  } else {
    log::warn("Received remote name complete without callback");
  }

  switch (bta_dm_search_get_state()) {
    case BTA_DM_SEARCH_ACTIVE:
      bta_dm_discover_name(bta_dm_search_cb.peer_bdaddr);
      break;
    case BTA_DM_SEARCH_IDLE:
    case BTA_DM_SEARCH_CANCELLING:
      log::warn("Received remote name request in state:{}",
                bta_dm_state_text(bta_dm_search_get_state()));
      break;
  }
}

static void bta_dm_search_cmpl() {
  bta_dm_search_set_state(BTA_DM_SEARCH_IDLE);

  if (bta_dm_search_cb.p_device_search_cback) {
    bta_dm_search_cb.p_device_search_cback(BTA_DM_DISC_CMPL_EVT, nullptr);
  }

  bta_dm_execute_queued_search_request();
}

static void bta_dm_execute_queued_search_request() {
  if (!bta_dm_search_cb.p_pending_search) return;

  log::info("Start pending search");
  post_search_evt(BTA_DM_API_SEARCH_EVT,
                  std::move(bta_dm_search_cb.p_pending_search));
  bta_dm_search_cb.p_pending_search.reset();
}

/*******************************************************************************
 *
 * Function         bta_dm_search_clear_queue
 *
 * Description      Clears the queue if API search cancel is called
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_search_clear_queue() {
  bta_dm_search_cb.p_pending_search.reset();
}

/*******************************************************************************
 *
 * Function         bta_dm_search_cancel_notify
 *
 * Description      Notify application that search has been cancelled
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_search_cancel_notify() {
  if (bta_dm_search_cb.p_device_search_cback) {
    bta_dm_search_cb.p_device_search_cback(BTA_DM_SEARCH_CANCEL_CMPL_EVT, NULL);
  }
  switch (bta_dm_search_get_state()) {
    case BTA_DM_SEARCH_ACTIVE:
    case BTA_DM_SEARCH_CANCELLING:
      if (!bta_dm_search_cb.name_discover_done) {
        get_btm_client_interface().peer.BTM_CancelRemoteDeviceName();
      }
      break;
    case BTA_DM_SEARCH_IDLE:
      // Nothing to do
      break;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_discover_next_device
 *
 * Description      Starts discovery on the next device in Inquiry data base
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_discover_next_device(void) {
  log::verbose("bta_dm_discover_next_device");

  /* searching next device on inquiry result */
  bta_dm_search_cb.p_btm_inq_info = get_btm_client_interface().db.BTM_InqDbNext(
      bta_dm_search_cb.p_btm_inq_info);
  if (bta_dm_search_cb.p_btm_inq_info != NULL) {
    bta_dm_search_cb.name_discover_done = false;
    bta_dm_search_cb.peer_name[0] = 0;
    bta_dm_discover_name(
        bta_dm_search_cb.p_btm_inq_info->results.remote_bd_addr);
  } else {
    post_search_evt(BTA_DM_SEARCH_CMPL_EVT, nullptr);
  }
}

/*TODO: this function is duplicated, make it common ?*/
static tBT_TRANSPORT bta_dm_determine_discovery_transport(
    const RawAddress& remote_bd_addr) {
  tBT_DEVICE_TYPE dev_type;
  tBLE_ADDR_TYPE addr_type;

  get_btm_client_interface().peer.BTM_ReadDevInfo(remote_bd_addr, &dev_type,
                                                  &addr_type);
  if (dev_type == BT_DEVICE_TYPE_BLE || addr_type == BLE_ADDR_RANDOM) {
    return BT_TRANSPORT_LE;
  } else if (dev_type == BT_DEVICE_TYPE_DUMO) {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(
            remote_bd_addr, BT_TRANSPORT_BR_EDR)) {
      return BT_TRANSPORT_BR_EDR;
    } else if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(
                   remote_bd_addr, BT_TRANSPORT_LE)) {
      return BT_TRANSPORT_LE;
    }
  }
  return BT_TRANSPORT_BR_EDR;
}

static void bta_dm_discover_name(const RawAddress& remote_bd_addr) {
  const tBT_TRANSPORT transport =
      bta_dm_determine_discovery_transport(remote_bd_addr);

  log::verbose("BDA: {}", remote_bd_addr);

  bta_dm_search_cb.peer_bdaddr = remote_bd_addr;

  log::verbose(
      "name_discover_done = {} p_btm_inq_info 0x{} state = {}, transport={}",
      bta_dm_search_cb.name_discover_done,
      fmt::ptr(bta_dm_search_cb.p_btm_inq_info), bta_dm_search_get_state(),
      transport);

  if (bta_dm_search_cb.p_btm_inq_info) {
    log::verbose("appl_knows_rem_name {}",
                 bta_dm_search_cb.p_btm_inq_info->appl_knows_rem_name);
  }
  if (((bta_dm_search_cb.p_btm_inq_info) &&
       (bta_dm_search_cb.p_btm_inq_info->results.device_type ==
        BT_DEVICE_TYPE_BLE) &&
       (bta_dm_search_get_state() == BTA_DM_SEARCH_ACTIVE)) ||
      (transport == BT_TRANSPORT_LE &&
       interop_match_addr(INTEROP_DISABLE_NAME_REQUEST,
                          &bta_dm_search_cb.peer_bdaddr))) {
    /* Do not perform RNR for LE devices at inquiry complete*/
    bta_dm_search_cb.name_discover_done = true;
  }
  // If we already have the name we can skip getting the name
  if (BTM_IsRemoteNameKnown(remote_bd_addr, transport) &&
      bluetooth::common::init_flags::sdp_skip_rnr_if_known_is_enabled()) {
    log::debug(
        "Security record already known skipping read remote name peer:{}",
        remote_bd_addr);
    bta_dm_search_cb.name_discover_done = true;
  }

  /* if name discovery is not done and application needs remote name */
  if ((!bta_dm_search_cb.name_discover_done) &&
      ((bta_dm_search_cb.p_btm_inq_info == NULL) ||
       (bta_dm_search_cb.p_btm_inq_info &&
        (!bta_dm_search_cb.p_btm_inq_info->appl_knows_rem_name)))) {
    if (bta_dm_read_remote_device_name(bta_dm_search_cb.peer_bdaddr,
                                       transport)) {
      BTM_LogHistory(kBtmLogTag, bta_dm_search_cb.peer_bdaddr,
                     "Read remote name",
                     base::StringPrintf("Transport:%s",
                                        bt_transport_text(transport).c_str()));
      return;
    } else {
      log::error("Unable to start read remote device name");
    }

    /* starting name discovery failed */
    bta_dm_search_cb.name_discover_done = true;
  }

  /* name discovery is done for this device */
  if (bta_dm_search_get_state() == BTA_DM_SEARCH_ACTIVE) {
    // if p_btm_inq_info is nullptr, there is no more inquiry results to
    // discover name for
    if (bta_dm_search_cb.p_btm_inq_info) {
      bta_dm_discover_next_device();
    } else {
      log::info("end of parsing inquiry result");
    }
  } else {
    log::info("name discovery finished in bad state: {}",
              bta_dm_state_text(bta_dm_search_get_state()));
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_is_search_request_queued
 *
 * Description      Checks if there is a queued search request
 *
 * Returns          bool
 *
 ******************************************************************************/
bool bta_dm_is_search_request_queued() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    return bta_dm_disc_legacy::bta_dm_is_search_request_queued();
  }
  return bta_dm_search_cb.p_pending_search != NULL;
}

/*******************************************************************************
 *
 * Function         bta_dm_queue_search
 *
 * Description      Queues search command
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_queue_search(tBTA_DM_API_SEARCH& search) {
  if (bta_dm_search_cb.p_pending_search) {
    log::warn("Overwrote previous device discovery inquiry scan request");
  }
  bta_dm_search_cb.p_pending_search.reset(new tBTA_DM_SEARCH_MSG(search));
  log::info("Queued device discovery inquiry scan request");
}

/*******************************************************************************
 *
 * Function         bta_dm_observe_results_cb
 *
 * Description      Callback for BLE Observe result
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_observe_results_cb(tBTM_INQ_RESULTS* p_inq,
                                      const uint8_t* p_eir, uint16_t eir_len) {
  tBTA_DM_SEARCH result;
  tBTM_INQ_INFO* p_inq_info;
  log::verbose("bta_dm_observe_results_cb");

  result.inq_res.bd_addr = p_inq->remote_bd_addr;
  result.inq_res.original_bda = p_inq->original_bda;
  result.inq_res.rssi = p_inq->rssi;
  result.inq_res.ble_addr_type = p_inq->ble_addr_type;
  result.inq_res.inq_result_type = p_inq->inq_result_type;
  result.inq_res.device_type = p_inq->device_type;
  result.inq_res.flag = p_inq->flag;
  result.inq_res.ble_evt_type = p_inq->ble_evt_type;
  result.inq_res.ble_primary_phy = p_inq->ble_primary_phy;
  result.inq_res.ble_secondary_phy = p_inq->ble_secondary_phy;
  result.inq_res.ble_advertising_sid = p_inq->ble_advertising_sid;
  result.inq_res.ble_tx_power = p_inq->ble_tx_power;
  result.inq_res.ble_periodic_adv_int = p_inq->ble_periodic_adv_int;

  /* application will parse EIR to find out remote device name */
  result.inq_res.p_eir = const_cast<uint8_t*>(p_eir);
  result.inq_res.eir_len = eir_len;

  p_inq_info =
      get_btm_client_interface().db.BTM_InqDbRead(p_inq->remote_bd_addr);
  if (p_inq_info != NULL) {
    /* initialize remt_name_not_required to false so that we get the name by
     * default */
    result.inq_res.remt_name_not_required = false;
  }

  if (p_inq_info) {
    /* application indicates if it knows the remote name, inside the callback
     copy that to the inquiry data base*/
    if (result.inq_res.remt_name_not_required)
      p_inq_info->appl_knows_rem_name = true;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_opportunistic_observe_results_cb
 *
 * Description      Callback for BLE Observe result
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_opportunistic_observe_results_cb(tBTM_INQ_RESULTS* p_inq,
                                                    const uint8_t* p_eir,
                                                    uint16_t eir_len) {
  tBTA_DM_SEARCH result;
  tBTM_INQ_INFO* p_inq_info;

  result.inq_res.bd_addr = p_inq->remote_bd_addr;
  result.inq_res.rssi = p_inq->rssi;
  result.inq_res.ble_addr_type = p_inq->ble_addr_type;
  result.inq_res.inq_result_type = p_inq->inq_result_type;
  result.inq_res.device_type = p_inq->device_type;
  result.inq_res.flag = p_inq->flag;
  result.inq_res.ble_evt_type = p_inq->ble_evt_type;
  result.inq_res.ble_primary_phy = p_inq->ble_primary_phy;
  result.inq_res.ble_secondary_phy = p_inq->ble_secondary_phy;
  result.inq_res.ble_advertising_sid = p_inq->ble_advertising_sid;
  result.inq_res.ble_tx_power = p_inq->ble_tx_power;
  result.inq_res.ble_periodic_adv_int = p_inq->ble_periodic_adv_int;

  /* application will parse EIR to find out remote device name */
  result.inq_res.p_eir = const_cast<uint8_t*>(p_eir);
  result.inq_res.eir_len = eir_len;

  p_inq_info =
      get_btm_client_interface().db.BTM_InqDbRead(p_inq->remote_bd_addr);
  if (p_inq_info != NULL) {
    /* initialize remt_name_not_required to false so that we get the name by
     * default */
    result.inq_res.remt_name_not_required = false;
  }

  if (bta_dm_search_cb.p_csis_scan_cback)
    bta_dm_search_cb.p_csis_scan_cback(BTA_DM_INQ_RES_EVT, &result);

  if (p_inq_info) {
    /* application indicates if it knows the remote name, inside the callback
     copy that to the inquiry data base*/
    if (result.inq_res.remt_name_not_required)
      p_inq_info->appl_knows_rem_name = true;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_observe_cmpl_cb
 *
 * Description      Callback for BLE Observe complete
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_observe_cmpl_cb(void* p_result) {
  log::verbose("bta_dm_observe_cmpl_cb");

  if (bta_dm_search_cb.p_csis_scan_cback) {
    auto num_resps = ((tBTM_INQUIRY_CMPL*)p_result)->num_resp;
    tBTA_DM_SEARCH data{.observe_cmpl{.num_resps = num_resps}};
    bta_dm_search_cb.p_csis_scan_cback(BTA_DM_OBSERVE_CMPL_EVT, &data);
  }
}

static void bta_dm_start_scan(uint8_t duration_sec,
                              bool low_latency_scan = false) {
  tBTM_STATUS status = get_btm_client_interface().ble.BTM_BleObserve(
      true, duration_sec, bta_dm_observe_results_cb, bta_dm_observe_cmpl_cb,
      low_latency_scan);

  if (status != BTM_CMD_STARTED) {
    log::warn("BTM_BleObserve  failed. status {}", status);
    if (bta_dm_search_cb.p_csis_scan_cback) {
      tBTA_DM_SEARCH data{.observe_cmpl = {.num_resps = 0}};
      bta_dm_search_cb.p_csis_scan_cback(BTA_DM_OBSERVE_CMPL_EVT, &data);
    }
  }
}

void bta_dm_ble_scan(bool start, uint8_t duration_sec,
                     bool low_latency_scan = false) {
  if (!start) {
    get_btm_client_interface().ble.BTM_BleObserve(false, 0, NULL, NULL, false);
    return;
  }

  bta_dm_start_scan(duration_sec, low_latency_scan);
}

void bta_dm_ble_csis_observe(bool observe, tBTA_DM_SEARCH_CBACK* p_cback) {
  if (!observe) {
    bta_dm_search_cb.p_csis_scan_cback = NULL;
    BTM_BleOpportunisticObserve(false, NULL);
    return;
  }

  /* Save the callback to be called when a scan results are available */
  bta_dm_search_cb.p_csis_scan_cback = p_cback;
  BTM_BleOpportunisticObserve(true, bta_dm_opportunistic_observe_results_cb);
}

namespace bluetooth {
namespace legacy {
namespace testing {

void bta_dm_remname_cback(const tBTM_REMOTE_DEV_NAME* p) {
  ::bta_dm_remname_cback(p);
}

void bta_dm_remote_name_cmpl(const tBTA_DM_REMOTE_NAME& remote_name_msg) {
  ::bta_dm_remote_name_cmpl(remote_name_msg);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

namespace {
constexpr size_t kSearchStateHistorySize = 50;
constexpr char kTimeFormatString[] = "%Y-%m-%d %H:%M:%S";

constexpr unsigned MillisPerSecond = 1000;
std::string EpochMillisToString(long long time_ms) {
  time_t time_sec = time_ms / MillisPerSecond;
  struct tm tm;
  localtime_r(&time_sec, &tm);
  std::string s = bluetooth::common::StringFormatTime(kTimeFormatString, tm);
  return base::StringPrintf(
      "%s.%03u", s.c_str(),
      static_cast<unsigned int>(time_ms % MillisPerSecond));
}

}  // namespace

struct tSEARCH_STATE_HISTORY {
  const tBTA_DM_DEVICE_SEARCH_STATE state;
  const tBTA_DM_DEV_SEARCH_EVT event;
  std::string ToString() const {
    return base::StringPrintf("state:%25s event:%s",
                              bta_dm_state_text(state).c_str(),
                              bta_dm_event_text(event).c_str());
  }
};

bluetooth::common::TimestampedCircularBuffer<tSEARCH_STATE_HISTORY>
    search_state_history_(kSearchStateHistorySize);

/*******************************************************************************
 *
 * Function         bta_dm_search_sm_execute
 *
 * Description      State machine event handling function for DM
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_search_sm_execute(tBTA_DM_DEV_SEARCH_EVT event,
                                     std::unique_ptr<tBTA_DM_SEARCH_MSG> msg) {
  log::info("state:{}, event:{}[0x{:x}]",
            bta_dm_state_text(bta_dm_search_get_state()),
            bta_dm_event_text(event), event);
  search_state_history_.Push({
      .state = bta_dm_search_get_state(),
      .event = event,
  });

  switch (bta_dm_search_get_state()) {
    case BTA_DM_SEARCH_IDLE:
      switch (event) {
        case BTA_DM_API_SEARCH_EVT:
          bta_dm_search_set_state(BTA_DM_SEARCH_ACTIVE);
          log::assert_that(std::holds_alternative<tBTA_DM_API_SEARCH>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_search_start(std::get<tBTA_DM_API_SEARCH>(*msg));
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_cancel_notify();
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}",
                    bta_dm_event_text(event), event,
                    bta_dm_state_text(bta_dm_search_get_state()));
      }
      break;
    case BTA_DM_SEARCH_ACTIVE:
      switch (event) {
        case BTA_DM_REMT_NAME_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_REMOTE_NAME>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_remote_name_cmpl(std::get<tBTA_DM_REMOTE_NAME>(*msg));
          break;
        case BTA_DM_SEARCH_CMPL_EVT:
          bta_dm_search_cmpl();
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_set_state(BTA_DM_SEARCH_CANCELLING);
          bta_dm_search_cancel();
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}",
                    bta_dm_event_text(event), event,
                    bta_dm_state_text(bta_dm_search_get_state()));
      }
      break;
    case BTA_DM_SEARCH_CANCELLING:
      switch (event) {
        case BTA_DM_API_SEARCH_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_API_SEARCH>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_queue_search(std::get<tBTA_DM_API_SEARCH>(*msg));
          break;
        case BTA_DM_API_SEARCH_CANCEL_EVT:
          bta_dm_search_clear_queue();
          bta_dm_search_cancel_notify();
          break;
        case BTA_DM_REMT_NAME_EVT:
        case BTA_DM_SEARCH_CMPL_EVT:
          bta_dm_search_set_state(BTA_DM_SEARCH_IDLE);
          bta_dm_search_cancel_notify();
          bta_dm_execute_queued_search_request();
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}",
                    bta_dm_event_text(event), event,
                    bta_dm_state_text(bta_dm_search_get_state()));
      }
      break;
  }
}

static void bta_dm_disable_search(void) {
  switch (bta_dm_search_get_state()) {
    case BTA_DM_SEARCH_IDLE:
      break;
    case BTA_DM_SEARCH_ACTIVE:
    case BTA_DM_SEARCH_CANCELLING:
    default:
      log::debug(
          "Search state machine is not idle so issuing search cancel current "
          "state:{}",
          bta_dm_state_text(bta_dm_search_get_state()));
      bta_dm_search_cancel();
  }
}

void bta_dm_disc_start_device_discovery(tBTA_DM_SEARCH_CBACK* p_cback) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_start_device_discovery(p_cback);
    return;
  }
  bta_dm_search_sm_execute(BTA_DM_API_SEARCH_EVT,
                           std::make_unique<tBTA_DM_SEARCH_MSG>(
                               tBTA_DM_API_SEARCH{.p_cback = p_cback}));
}

void bta_dm_disc_stop_device_discovery() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_stop_device_discovery();
    return;
  }
  bta_dm_search_sm_execute(BTA_DM_API_SEARCH_CANCEL_EVT, nullptr);
}

static void bta_dm_disc_init_search_cb(tBTA_DM_SEARCH_CB& bta_dm_search_cb) {
  bta_dm_search_cb = {};
  bta_dm_search_cb.search_state = BTA_DM_SEARCH_IDLE;
}

static void bta_dm_search_reset() {
  bta_dm_search_cb.p_pending_search.reset();
  bta_dm_disc_init_search_cb(::bta_dm_search_cb);
}

void bta_dm_search_stop() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    log::info("no-op when flag is disabled");
    return;
  }
  bta_dm_search_reset();
}

void bta_dm_disc_discover_next_device() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_discover_next_device();
    return;
  }
  bta_dm_discover_next_device();
}

#define DUMPSYS_TAG "shim::legacy::bta::dm"
void DumpsysBtaDmSearch(int fd) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    log::info("no-op when flag is disabled");
    return;
  }
  auto copy = search_state_history_.Pull();
  LOG_DUMPSYS(fd, " last %zu search state transitions", copy.size());
  for (const auto& it : copy) {
    LOG_DUMPSYS(fd, "   %s %s", EpochMillisToString(it.timestamp).c_str(),
                it.entry.ToString().c_str());
  }
  LOG_DUMPSYS(fd, " current bta_dm_search_state:%s",
              bta_dm_state_text(bta_dm_search_get_state()).c_str());
}
#undef DUMPSYS_TAG

namespace bluetooth {
namespace legacy {
namespace testing {

void bta_dm_disc_init_search_cb(tBTA_DM_SEARCH_CB& bta_dm_search_cb) {
  ::bta_dm_disc_init_search_cb(bta_dm_search_cb);
}
void bta_dm_discover_next_device() { ::bta_dm_discover_next_device(); }

tBTA_DM_SEARCH_CB bta_dm_disc_get_search_cb() {
  tBTA_DM_SEARCH_CB search_cb = {};
  ::bta_dm_disc_init_search_cb(search_cb);
  return search_cb;
}
tBTA_DM_SEARCH_CB& bta_dm_disc_search_cb() { return ::bta_dm_search_cb; }
bool bta_dm_read_remote_device_name(const RawAddress& bd_addr,
                                    tBT_TRANSPORT transport) {
  return ::bta_dm_read_remote_device_name(bd_addr, transport);
}

void bta_dm_inq_cmpl() { ::bta_dm_inq_cmpl(); }
void bta_dm_inq_cmpl_cb(void* p_result) { ::bta_dm_inq_cmpl_cb(p_result); }
void bta_dm_observe_cmpl_cb(void* p_result) {
  ::bta_dm_observe_cmpl_cb(p_result);
}
void bta_dm_observe_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir,
                               uint16_t eir_len) {
  ::bta_dm_observe_results_cb(p_inq, p_eir, eir_len);
}
void bta_dm_opportunistic_observe_results_cb(tBTM_INQ_RESULTS* p_inq,
                                             const uint8_t* p_eir,
                                             uint16_t eir_len) {
  ::bta_dm_opportunistic_observe_results_cb(p_inq, p_eir, eir_len);
}
void bta_dm_queue_search(tBTA_DM_API_SEARCH& search) {
  ::bta_dm_queue_search(search);
}

void bta_dm_start_scan(uint8_t duration_sec, bool low_latency_scan = false) {
  ::bta_dm_start_scan(duration_sec, low_latency_scan);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth
