/*
 * Copyright 2020 The Android Open Source Project
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
#include <bluetooth/types/ble_address_with_type.h>

#include <cstdint>

#include "macros.h"
#include "osi/include/alarm.h"
#include "stack/btm/btm_eir.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_name.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/hci_error_code.h"

/* Discoverable modes */
enum : uint16_t {
  BTM_NON_DISCOVERABLE = 0,
  BTM_LIMITED_DISCOVERABLE = (1 << 0),
  BTM_GENERAL_DISCOVERABLE = (1 << 1),
  BTM_MAX_DISCOVERABLE = BTM_GENERAL_DISCOVERABLE,
  BTM_DISCOVERABLE_MASK = (BTM_LIMITED_DISCOVERABLE | BTM_GENERAL_DISCOVERABLE),
};

/* Connectable modes */
enum : uint16_t {
  BTM_NON_CONNECTABLE = 0,
  BTM_CONNECTABLE = (1 << 0),
  BTM_CONNECTABLE_MASK = (BTM_NON_CONNECTABLE | BTM_CONNECTABLE),

};

/* Inquiry modes
 * Note: These modes are associated with the inquiry active values (BTM_*ACTIVE)
 */
enum : uint8_t {
  BTM_INQUIRY_INACTIVE = 0x0,
  BTM_GENERAL_INQUIRY = 0x01,
  /* high nibble of inquiry mode for BLE inquiry mode */
  BTM_BLE_GENERAL_INQUIRY = 0x10,
  /* inquiry activity mask */
  BTM_INQUIRY_ACTIVE_MASK = (BTM_GENERAL_INQUIRY | BTM_BLE_GENERAL_INQUIRY),
};

/* Define scan types */
enum : uint16_t {
  BTM_SCAN_TYPE_STANDARD = 0,
  BTM_SCAN_TYPE_INTERLACED = 1, /* 1.2 devices only */
};

/* Define inquiry results mode */
typedef enum : uint8_t {
  BTM_INQ_RESULT_STANDARD = 0,
  BTM_INQ_RESULT_WITH_RSSI = 1,
  BTM_INQ_RESULT_EXTENDED = 2,
  /* RSSI value not supplied (ignore it) */
  BTM_INQ_RES_IGNORE_RSSI = 0x7f,
} tBTM_INQ_RESULT;
constexpr size_t kMaxNumberInquiryResults = BTM_INQ_RESULT_EXTENDED + 1;

/* These are the fields returned in each device's response to the inquiry.  It
 * is returned in the results callback if registered.
 */
typedef struct {
  uint16_t clock_offset;
  RawAddress remote_bd_addr;
  DEV_CLASS dev_class;
  uint8_t page_scan_rep_mode;
  uint8_t page_scan_per_mode;
  uint8_t page_scan_mode;
  int8_t rssi; /* Set to BTM_INQ_RES_IGNORE_RSSI if  not valid */
  uint32_t eir_uuid[BTM_EIR_SERVICE_ARRAY_SIZE];
  bool eir_complete_list;
  tBT_DEVICE_TYPE device_type;
  uint8_t inq_result_type;
  tBT_TRANSPORT last_inq_result_transport; /* Whether the last inquiry is from LE or BR/EDR */
  tBLE_ADDR_TYPE ble_addr_type;
  uint16_t ble_evt_type;
  uint8_t ble_primary_phy;
  uint8_t ble_secondary_phy;
  uint8_t ble_advertising_sid;
  int8_t ble_tx_power;
  uint16_t ble_periodic_adv_int;
  RawAddress ble_ad_rsi; /* Resolvable Set Identifier from advertising */
  bool ble_ad_is_le_audio_capable;
  uint8_t flag;
  bool include_rsi;
  RawAddress original_bda;
} tBTM_INQ_RESULTS;

/****************************************
 *  Device Discovery Callback Functions
 ****************************************/
/* Callback function for notifications when the BTM gets inquiry response.
 * First param is inquiry results database, second is pointer of EIR.
 */
typedef void(tBTM_INQ_RESULTS_CB)(tBTM_INQ_RESULTS* p_inq_results, const uint8_t* p_eir,
                                  uint16_t eir_len);

typedef struct {
  uint32_t inq_count; /* Used for determining if a response has already been */
  /* received for the current inquiry operation. (We do not   */
  /* want to flood the caller with multiple responses from    */
  /* the same device.                                         */
  RawAddress bd_addr;
} tINQ_BDADDR;

/* This is the inquiry response information held in its database by BTM, and
 * available to applications via BTM_InqDbRead, BTM_InqDbFirst, and
 * BTM_InqDbNext.
 */
typedef struct {
  tBTM_INQ_RESULTS results;

  bool appl_knows_rem_name; /* set by application if it knows the remote name of
                               the peer device.
                               This is later used by application to determine if
                               remote name request is
                               required to be done. Having the flag here avoid
                               duplicate store of inquiry results */
  uint16_t remote_name_len;
  BD_NAME remote_name;
  uint8_t remote_name_type;
} tBTM_INQ_INFO;

typedef struct {
  uint64_t time_of_resp;
  uint32_t inq_count; /* "timestamps" the entry with a particular inquiry count   */
                      /* Used for determining if a response has already been      */
                      /* received for the current inquiry operation. (We do not   */
                      /* want to flood the caller with multiple responses from    */
                      /* the same device.                                         */
  tBTM_INQ_INFO inq_info;
  bool in_use;
  bool scan_rsp;
} tINQ_DB_ENT;

typedef struct /* contains the parameters passed to the inquiry functions */
{
  uint8_t mode;     /* general or limited */
  uint8_t duration; /* duration of the inquiry (1.28 sec increments) */
} tBTM_INQ_PARMS;

/* Structure returned with inquiry complete callback */
typedef struct {
  // Possible inquiry completion status
  enum STATUS {
    CANCELED,      // Expected user API cancel
    TIMER_POPPED,  // Expected controller initiated timeout
    NOT_STARTED,   // Unexpected controller unable to execute inquiry command
    SSP_ACTIVE,    // Unexpected secure simple pairing is operational
  };
  STATUS status;
  tHCI_STATUS hci_status;
  uint8_t num_resp; /* Number of results from the current inquiry */
  unsigned resp_type[kMaxNumberInquiryResults];
  uint64_t start_time_ms;
} tBTM_INQUIRY_CMPL;

inline std::string btm_inquiry_cmpl_status_text(const tBTM_INQUIRY_CMPL::STATUS& status) {
  switch (status) {
    CASE_RETURN_TEXT(tBTM_INQUIRY_CMPL::CANCELED);
    CASE_RETURN_TEXT(tBTM_INQUIRY_CMPL::TIMER_POPPED);
    CASE_RETURN_TEXT(tBTM_INQUIRY_CMPL::NOT_STARTED);
    CASE_RETURN_TEXT(tBTM_INQUIRY_CMPL::SSP_ACTIVE);
    default:
      return std::string("UNKNOWN[") + std::to_string(status) + std::string("]");
  }
}

using tBTM_INQUIRY_CMPL_CB = void(tBTM_INQUIRY_CMPL*);

struct tBTM_INQUIRY_VAR_ST {
  alarm_t* classic_inquiry_timer;

  uint16_t discoverable_mode;
  uint16_t connectable_mode;
  uint16_t page_scan_window;
  uint16_t page_scan_period;
  uint16_t inq_scan_window;
  uint16_t inq_scan_period;
  uint16_t inq_scan_type;
  uint16_t page_scan_type; /* current page scan type */

  tBTM_INQUIRY_CMPL_CB* p_inq_cmpl_cb;
  tBTM_INQ_RESULTS_CB* p_inq_results_cb;
  uint32_t inq_counter; /* Counter incremented each time an inquiry completes */
  /* Used for determining whether or not duplicate devices */
  /* have responded to the same inquiry */
  tBTM_INQ_PARMS inqparms;         /* Contains the parameters for the current inquiry */
  tBTM_INQUIRY_CMPL inq_cmpl_info; /* Status and number of responses from the last inquiry */

  uint16_t per_min_delay; /* Current periodic minimum delay */
  uint16_t per_max_delay; /* Current periodic maximum delay */
  /* inquiry that has been cancelled*/
  uint8_t inqfilt_type; /* Contains the inquiry filter type (BD ADDR, COD, or
                           Clear) */

#define BTM_INQ_INACTIVE_STATE 0
#define BTM_INQ_ACTIVE_STATE 3 /* Actual inquiry or periodic inquiry is in progress */

  uint8_t state;      /* Current state that the inquiry process is in */
  uint8_t inq_active; /* Bit Mask indicating type of inquiry is active */

  bool registered_for_hci_events;

  void Init();
  void Free();
};

bool btm_inq_find_bdaddr(const RawAddress& p_bda);
tINQ_DB_ENT* btm_inq_db_find(const RawAddress& p_bda);

namespace std {
template <>
struct formatter<tBTM_INQUIRY_CMPL::STATUS> : enum_formatter<tBTM_INQUIRY_CMPL::STATUS> {};
}  // namespace std
