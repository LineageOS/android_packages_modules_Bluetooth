/*
 * Copyright 2023 The Android Open Source Project
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

#include <base/strings/stringprintf.h>
#include <bluetooth/log.h>

#include <queue>
#include <string>

#include "macros.h"
#include "stack/btm/neighbor_inquiry.h"
#include "types/raw_address.h"

/* DM search events */
typedef enum : uint16_t {
  /* DM search API events */
  BTA_DM_API_SEARCH_EVT,
  BTA_DM_API_SEARCH_CANCEL_EVT,
  BTA_DM_INQUIRY_CMPL_EVT,
  BTA_DM_REMT_NAME_EVT,
  BTA_DM_SEARCH_CMPL_EVT,
} tBTA_DM_DEV_SEARCH_EVT;

inline std::string bta_dm_event_text(const tBTA_DM_DEV_SEARCH_EVT& event) {
  switch (event) {
    CASE_RETURN_TEXT(BTA_DM_API_SEARCH_EVT);
    CASE_RETURN_TEXT(BTA_DM_API_SEARCH_CANCEL_EVT);
    CASE_RETURN_TEXT(BTA_DM_INQUIRY_CMPL_EVT);
    CASE_RETURN_TEXT(BTA_DM_REMT_NAME_EVT);
    CASE_RETURN_TEXT(BTA_DM_SEARCH_CMPL_EVT);
  }
}

/* data type for BTA_DM_API_SEARCH_EVT */
typedef struct {
  tBTA_DM_SEARCH_CBACK* p_cback;
} tBTA_DM_API_SEARCH;

typedef struct {
  RawAddress bd_addr;
  BD_NAME bd_name; /* Name of peer device. */
  tHCI_STATUS hci_status;
} tBTA_DM_REMOTE_NAME;

using tBTA_DM_SEARCH_MSG =
    std::variant<tBTA_DM_API_SEARCH, tBTA_DM_REMOTE_NAME>;

/* DM search state */
typedef enum {
  BTA_DM_SEARCH_IDLE,
  BTA_DM_SEARCH_ACTIVE,
  BTA_DM_SEARCH_CANCELLING,
} tBTA_DM_DEVICE_SEARCH_STATE;

inline std::string bta_dm_state_text(const tBTA_DM_DEVICE_SEARCH_STATE& state) {
  switch (state) {
    CASE_RETURN_TEXT(BTA_DM_SEARCH_IDLE);
    CASE_RETURN_TEXT(BTA_DM_SEARCH_ACTIVE);
    CASE_RETURN_TEXT(BTA_DM_SEARCH_CANCELLING);
  }
}

/* DM search control block */
typedef struct {
  tBTA_DM_SEARCH_CBACK* p_device_search_cback;
  tBTM_INQ_INFO* p_btm_inq_info;
  /* This covers device search state. That is scanning through android Settings
   * to discover LE and Classic devices. Runs Name discovery on Inquiry Results
   */
  tBTA_DM_DEVICE_SEARCH_STATE search_state;
  bool name_discover_done;
  /* peer address used for name discovery */
  RawAddress peer_bdaddr;
  BD_NAME peer_name;
  std::unique_ptr<tBTA_DM_SEARCH_MSG> p_pending_search;
  tBTA_DM_SEARCH_CBACK* p_csis_scan_cback;
} tBTA_DM_SEARCH_CB;

namespace fmt {
template <>
struct formatter<tBTA_DM_DEV_SEARCH_EVT>
    : enum_formatter<tBTA_DM_DEV_SEARCH_EVT> {};
template <>
struct formatter<tBTA_DM_DEVICE_SEARCH_STATE>
    : enum_formatter<tBTA_DM_DEVICE_SEARCH_STATE> {};
}  // namespace fmt
