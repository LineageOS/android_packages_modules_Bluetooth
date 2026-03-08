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
#pragma once

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstdint>
#include <memory>
#include <string>

#include "common/circular_buffer.h"
#include "stack/acl/acl.h"
#include "stack/btm/btm_ble_int_types.h"
#include "stack/btm/btm_sco.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/rnr/remote_name_request.h"

using TimestampedStringCircularBuffer = bluetooth::common::TimestampedStringCircularBuffer;

constexpr size_t kBtmLogHistoryBufferSize = 200;
constexpr size_t kMaxInquiryScanHistory = 10;

extern bluetooth::common::TimestamperInMilliseconds timestamper_in_milliseconds;

/* Define a structure to hold all the BTM data
 */

/* Define the Device Management control structure
 */
typedef struct tBTM_DEVCB {
  tBTM_CMPL_CB* p_rln_cmpl_cb; /* Callback function to be called when  */
                               /* read local name function complete    */

  alarm_t* read_rssi_timer;          /* Read RSSI timer */
  tBTM_READ_RSSI_CB* p_rssi_cmpl_cb; /* Callback function to be called when  */
                                     /* read RSSI function completes */

  alarm_t* read_automatic_flush_timeout_timer;     /* Read Automatic Flush Timeout */
                                                   /* timer */
  tBTM_READ_AUTOMATIC_FLUSH_TIMEOUT_CB*
          p_automatic_flush_timeout_cmpl_cb; /* Callback function to be */
                                             /* called when read Automatic Flush Timeout function
                                                completes */

  DEV_CLASS dev_class; /* Local device class                   */

  tBTM_CMPL_CB* p_le_test_cmd_cmpl_cb; /* Callback function to be called when
                                       LE test mode command has been sent successfully */

  RawAddress read_tx_pwr_addr; /* read TX power target address     */

  void Init() {
    read_rssi_timer = alarm_new("btm.read_rssi_timer");
    read_automatic_flush_timeout_timer = alarm_new("btm.read_automatic_flush_timeout_timer");
  }

  void Free() {
    alarm_free(read_rssi_timer);
    alarm_free(read_automatic_flush_timeout_timer);
  }
} tBTM_DEVCB;

typedef struct tBTM_CB {
  /*****************************************************
  **      Control block for local device
  *****************************************************/
  tBTM_DEVCB devcb;

  /*****************************************************
  **      Control block for local LE device
  *****************************************************/
  tBTM_BLE_CB ble_ctr_cb;

public:
  tBTM_BLE_VSC_CB cmn_ble_vsc_cb;

  /* Packet types supported by the local device */
  uint16_t btm_sco_pkt_types_supported{0};

  /*****************************************************
  **      Inquiry
  *****************************************************/
  tBTM_INQUIRY_VAR_ST btm_inq_vars;

  /*****************************************************
  **      SCO Management
  *****************************************************/
  tSCO_CB sco_cb;

#define BTM_CODEC_TYPE_MAX_RECORDS 32
  tBTM_BT_DYNAMIC_AUDIO_BUFFER_CB dynamic_audio_buffer_cb[BTM_CODEC_TYPE_MAX_RECORDS];

  tACL_CB acl_cb_;

  std::shared_ptr<TimestampedStringCircularBuffer> history_{nullptr};

  struct {
    struct {
      uint64_t start_time_ms;
      uint64_t results;
    } classic_inquiry, le_scan, le_inquiry, le_observe, le_legacy_scan;
    std::unique_ptr<bluetooth::common::TimestampedCircularBuffer<tBTM_INQUIRY_CMPL>>
            inquiry_history_ = std::make_unique<
                    bluetooth::common::TimestampedCircularBuffer<tBTM_INQUIRY_CMPL>>(
                    kMaxInquiryScanHistory);
  } neighbor;

  bluetooth::stack::rnr::RemoteNameRequest rnr;

  void Init() {
    memset(&devcb, 0, sizeof(devcb));
    memset(&ble_ctr_cb, 0, sizeof(ble_ctr_cb));
    memset(&cmn_ble_vsc_cb, 0, sizeof(cmn_ble_vsc_cb));
    memset(&btm_inq_vars, 0, sizeof(btm_inq_vars));
    memset(&sco_cb, 0, sizeof(sco_cb));

    acl_cb_ = {};
    neighbor = {};
    rnr = {};
    rnr.remote_name_timer = alarm_new("rnr.remote_name_timer");

    /* Initialize BTM component structures */
    btm_inq_vars.Init(); /* Inquiry Database and Structures */
    sco_cb.Init();       /* SCO Database and Structures (If included) */
    devcb.Init();

    history_ = std::make_shared<TimestampedStringCircularBuffer>(kBtmLogHistoryBufferSize);
    bluetooth::log::assert_that(history_ != nullptr, "assert failed: history_ != nullptr");
    history_->Push(std::string("Initialized btm history"));
  }

  void Free() {
    alarm_free(rnr.remote_name_timer);
    history_.reset();

    devcb.Free();
    sco_cb.Free();
    btm_inq_vars.Free();
  }
} tBTM_CB;
