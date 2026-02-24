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

#include "stack/rnr/remote_name_request.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "btif/include/btif_config.h"
#include "main/shim/acl_api.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_device_record.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/btm_client_interface.h"

using namespace bluetooth;

void BTM_SecAddRmtNameNotifyCallback(BtmRemoteNameCallback& callback) {
  btm_cb.rnr.p_rmt_name_callback = &callback;
}

bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT /* transport */) {
  const BtmDevice* p_device = btm_find_dev(bd_addr);
  return (p_device == nullptr) ? false : p_device->sec_rec.is_name_known();
}

/*******************************************************************************
 *
 * Function         btm_inq_rmt_name_failed_cancelled
 *
 * Description      This function is if timeout expires or request is cancelled
 *                  while getting remote name.  This is done for devices that
 *                  incorrectly do not report operation failure
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_inq_rmt_name_failed_cancelled(void) {
  log::error("remname_active={}", btm_cb.rnr.remname_active);

  if (btm_cb.rnr.remname_active) {
    btm_process_remote_name(&btm_cb.rnr.remname_bda, NULL, 0, HCI_ERR_UNSPECIFIED);
  }

  btm_sec_rmt_name_request_complete(NULL, NULL, HCI_ERR_UNSPECIFIED);
}

void btm_inq_remote_name_timer_timeout(void* /* data */) { btm_inq_rmt_name_failed_cancelled(); }

static uint16_t get_cached_clock_offset(const RawAddress& remote_bda) {
  if (!com_android_bluetooth_flags_use_cached_clock_offset()) {
    int clock_offset_in_cfg = 0;
    return btif_get_device_clockoffset(remote_bda, &clock_offset_in_cfg)
                   ? static_cast<uint16_t>(clock_offset_in_cfg)
                   : 0;
  }
  return BTM_GetCachedClockOffset(remote_bda);
}

/*******************************************************************************
 *
 * Function         btm_initiate_rem_name
 *
 * Description      This function looks initiates a remote name request.  It is
 *                  called either by GAP or by the API call
 *                  BTM_ReadRemoteDeviceName.
 *
 * Input Params:    remote_bda: Remote address to execute RNR
 *                  timeout_ms: Internal timeout to await response
 * *                p_cb:       Callback function called when
 *                              tBTM_STATUS::BTM_CMD_STARTED is returned.
 *                              A pointer to tBTM_REMOTE_DEV_NAME is
 *                              passed to the callback.
 *
 * Returns
 *                  tBTM_STATUS::BTM_CMD_STARTED is returned if the request was sent to HCI.
 *                    and the callback will be called.
 *                  BTM_BUSY if already in progress
 *                  BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
static tBTM_STATUS btm_initiate_rem_name(const RawAddress& remote_bda, uint64_t timeout_ms,
                                         tBTM_NAME_CMPL_CB* p_cb) {
  /*** Make sure the device is ready ***/
  if (!get_btm_client_interface().local.BTM_IsDeviceUp()) {
    return tBTM_STATUS::BTM_WRONG_MODE;
  }
  if (btm_cb.rnr.remname_active) {
    return tBTM_STATUS::BTM_BUSY;
  }

  uint16_t clock_offset = 0;
  uint8_t page_scan_rep_mode = HCI_PAGE_SCAN_REP_MODE_R1;
  uint8_t page_scan_mode = HCI_MANDATARY_PAGE_SCAN_MODE;

  /* If the database entry exists for the device, use its clock offset */
  tINQ_DB_ENT* p_i = btm_inq_db_find(remote_bda);
  if (p_i && (p_i->inq_info.results.inq_result_type & BT_DEVICE_TYPE_BREDR)) {
    tBTM_INQ_INFO* p_cur = &p_i->inq_info;
    clock_offset = p_cur->results.clock_offset | BTM_CLOCK_OFFSET_VALID;
    page_scan_rep_mode = p_cur->results.page_scan_rep_mode;
    if (com_android_bluetooth_flags_rnr_validate_page_scan_repetition_mode() &&
        page_scan_rep_mode >= HCI_PAGE_SCAN_REP_MODE_RESERVED_START) {
      log::info(
              "Invalid page scan repetition mode {} from remote_bda:{}, "
              "fallback to R1",
              page_scan_rep_mode, remote_bda);
      page_scan_rep_mode = HCI_PAGE_SCAN_REP_MODE_R1;
    }
    page_scan_mode = p_cur->results.page_scan_mode;
  }

  if ((clock_offset & BTM_CLOCK_OFFSET_VALID) == 0) {
    clock_offset = get_cached_clock_offset(remote_bda);
  }
  bluetooth::shim::ACL_RemoteNameRequest(remote_bda, page_scan_rep_mode, page_scan_mode,
                                         clock_offset);

  btm_cb.rnr.p_remname_cmpl_cb = p_cb;
  btm_cb.rnr.remname_bda = remote_bda;
  btm_cb.rnr.remname_dev_type = BT_DEVICE_TYPE_BREDR;
  btm_cb.rnr.remname_active = true;

  alarm_set_on_mloop(btm_cb.rnr.remote_name_timer, timeout_ms, btm_inq_remote_name_timer_timeout,
                     NULL);

  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_process_remote_name
 *
 * Description      This function is called when a remote name is received from
 *                  the device. If remote names are cached, it updates the
 *                  inquiry database.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_process_remote_name(const RawAddress* bda, const BD_NAME bdn, uint16_t /* evt_len */,
                             tHCI_STATUS hci_status) {
  tBTM_REMOTE_DEV_NAME rem_name = {
          .bd_addr = bda ? *bda : RawAddress::kEmpty,
          .remote_bd_name = {},
          .btm_status = tBTM_STATUS::BTM_BAD_VALUE_RET,
          .hci_status = hci_status,
  };

  bool on_le_link = (btm_cb.rnr.remname_dev_type == BT_DEVICE_TYPE_BLE);

  /* If the inquire BDA and remote DBA are the same, then stop the timer and set
   * the active to false */
  if (btm_cb.rnr.remname_active) {
    if (rem_name.bd_addr == RawAddress::kEmpty || rem_name.bd_addr == btm_cb.rnr.remname_bda) {
      log::info("RNR received expected name bd_addr:{} hci_status:{} le_link:{}",
                rem_name.bd_addr.ToRedactedStringForLogging(), hci_status_code_text(hci_status),
                on_le_link);

      if (on_le_link && hci_status == HCI_ERR_UNSPECIFIED) {
        btm_ble_cancel_remote_name(btm_cb.rnr.remname_bda);
      }
      alarm_cancel(btm_cb.rnr.remote_name_timer);
      /* Clean up and return the status if the command was not successful */
      /* Note: If part of the inquiry, the name is not stored, and the    */
      /*       inquiry complete callback is called.                       */

      if (hci_status == HCI_SUCCESS) {
        /* Copy the name from the data stream into the return structure */
        /* Note that even if it is not being returned, it is used as a  */
        /*      temporary buffer.                                       */
        rem_name.btm_status = tBTM_STATUS::BTM_SUCCESS;
        if (bdn) {
          bd_name_copy(rem_name.remote_bd_name, bdn);
        } else {
          log::warn("Received null name from remote device bd_addr:{}",
                    rem_name.bd_addr.ToRedactedStringForLogging());
        }
      }
      /* Reset the remote BDA and call callback if possible */
      btm_cb.rnr.remname_active = false;
      btm_cb.rnr.remname_bda = RawAddress::kEmpty;
      btm_cb.rnr.remname_dev_type = BT_DEVICE_TYPE_UNKNOWN;

      tBTM_NAME_CMPL_CB* p_cb = btm_cb.rnr.p_remname_cmpl_cb;
      btm_cb.rnr.p_remname_cmpl_cb = nullptr;
      if (p_cb) {
        (p_cb)(&rem_name);
      }
    } else {
      log::warn("RNR received UNKNOWN name bd_addr:{} hci_status:{} le_link:{}",
                rem_name.bd_addr.ToRedactedStringForLogging(), hci_status_code_text(hci_status),
                on_le_link);
    }
  } else {
    log::info(
            "RNR received UNEXPECTED name bd_addr:{} inq_addr:{} hci_status:{} "
            "le_link:{} rnr_active:{}",
            rem_name.bd_addr.ToRedactedStringForLogging(),
            btm_cb.rnr.remname_bda.ToRedactedStringForLogging(), hci_status_code_text(hci_status),
            on_le_link, btm_cb.rnr.remname_active);
  }
}

/*******************************************************************************
 *
 * Function         BTM_ReadRemoteDeviceName
 *
 * Description      This function initiates a remote device HCI command to the
 *                  controller and calls the callback when the process has
 *                  completed.
 *
 * Input Params:    remote_bda      - device address of name to retrieve
 *                  p_cb            - callback function called when
 *                                    tBTM_STATUS::BTM_CMD_STARTED is returned.
 *                                    A pointer to tBTM_REMOTE_DEV_NAME is
 *                                    passed to the callback.
 *
 * Returns
 *                  tBTM_STATUS::BTM_CMD_STARTED is returned if the request was successfully
 *                                  sent to HCI.
 *                  tBTM_STATUS::BTM_BUSY if already in progress
 *                  tBTM_STATUS::BTM_UNKNOWN_ADDR if device address is bad
 *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  tBTM_STATUS::BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
#define BTM_EXT_RMT_NAME_TIMEOUT_MS (40 * 1000) /* 40 seconds */
tBTM_STATUS BTM_ReadRemoteDeviceName(const RawAddress& remote_bda, tBTM_NAME_CMPL_CB* p_cb,
                                     tBT_TRANSPORT transport) {
  log::verbose("bd addr {}", remote_bda);
  /* Use LE transport when LE is the only available option */
  if (transport == BT_TRANSPORT_LE) {
    return btm_ble_read_remote_name(remote_bda, p_cb);
  }
  /* Use classic transport for BR/EDR and Dual Mode devices */
  return btm_initiate_rem_name(remote_bda, BTM_EXT_RMT_NAME_TIMEOUT_MS, p_cb);
}

/*******************************************************************************
 *
 * Function         BTM_CancelRemoteDeviceName
 *
 * Description      This function initiates the cancel request for the specified
 *                  remote device.
 *
 * Input Params:    None
 *
 * Returns
 *                  tBTM_STATUS::BTM_CMD_STARTED is returned if the request was successfully
 *                                  sent to HCI.
 *                  tBTM_STATUS::BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  tBTM_STATUS::BTM_WRONG_MODE if there is not an active remote name
 *                                 request.
 *
 ******************************************************************************/
tBTM_STATUS BTM_CancelRemoteDeviceName(void) {
  log::verbose("");

  /* Make sure there is not already one in progress */
  if (!btm_cb.rnr.remname_active) {
    return tBTM_STATUS::BTM_WRONG_MODE;
  }

  if (btm_cb.rnr.remname_dev_type == BT_DEVICE_TYPE_BLE) {
    /* Cancel remote name request for LE device, and process remote name
     * callback. */
    btm_inq_rmt_name_failed_cancelled();
  } else {
    bluetooth::shim::ACL_CancelRemoteNameRequest(btm_cb.rnr.remname_bda);
    btm_process_remote_name(&btm_cb.rnr.remname_bda, nullptr, 0, HCI_ERR_UNSPECIFIED);
  }
  return tBTM_STATUS::BTM_CMD_STARTED;
}

void bluetooth::stack::rnr::Impl::BTM_SecAddRmtNameNotifyCallback(BtmRemoteNameCallback& callback) {
  ::BTM_SecAddRmtNameNotifyCallback(callback);
}

bool bluetooth::stack::rnr::Impl::BTM_IsRemoteNameKnown(const RawAddress& bd_addr,
                                                        tBT_TRANSPORT transport) {
  return ::BTM_IsRemoteNameKnown(bd_addr, transport);
}

tBTM_STATUS bluetooth::stack::rnr::Impl::BTM_ReadRemoteDeviceName(const RawAddress& remote_bda,
                                                                  tBTM_NAME_CMPL_CB* p_cb,
                                                                  tBT_TRANSPORT transport) {
  return ::BTM_ReadRemoteDeviceName(remote_bda, p_cb, transport);
}

tBTM_STATUS bluetooth::stack::rnr::Impl::BTM_CancelRemoteDeviceName() {
  return ::BTM_CancelRemoteDeviceName();
}

void bluetooth::stack::rnr::Impl::btm_process_remote_name(const RawAddress* bda, const BD_NAME bdn,
                                                          uint16_t evt_len,
                                                          tHCI_STATUS hci_status) {
  ::btm_process_remote_name(bda, bdn, evt_len, hci_status);
}

static bluetooth::stack::rnr::Impl default_interface;
static bluetooth::stack::rnr::Interface* interface_ = &default_interface;

bluetooth::stack::rnr::Interface& get_stack_rnr_interface() { return *interface_; }
