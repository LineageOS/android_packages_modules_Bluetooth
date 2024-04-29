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

#include "bta/include/bta_api.h"
#include "bta/sys/bta_sys.h"
#include "macros.h"
#include "stack/include/sdp_status.h"
#include "stack/sdp/sdp_discovery_db.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

#define BTA_SERVICE_ID_TO_SERVICE_MASK(id) (1 << (id))

typedef enum : uint16_t {
  /* service discovery events */
  BTA_DM_API_DISCOVER_EVT,
  BTA_DM_SDP_RESULT_EVT,
  BTA_DM_DISCOVERY_RESULT_EVT,
  BTA_DM_DISC_CLOSE_TOUT_EVT,
} tBTA_DM_DISC_EVT;

inline std::string bta_dm_event_text(const tBTA_DM_DISC_EVT& event) {
  switch (event) {
    CASE_RETURN_TEXT(BTA_DM_API_DISCOVER_EVT);
    CASE_RETURN_TEXT(BTA_DM_SDP_RESULT_EVT);
    CASE_RETURN_TEXT(BTA_DM_DISCOVERY_RESULT_EVT);
    CASE_RETURN_TEXT(BTA_DM_DISC_CLOSE_TOUT_EVT);
  }
}

/* data type for BTA_DM_API_DISCOVER_EVT */
typedef struct {
  RawAddress bd_addr;
  service_discovery_callbacks cbacks;
  tBT_TRANSPORT transport;
} tBTA_DM_API_DISCOVER;

typedef struct {
  RawAddress bd_addr;          /* BD address peer device. */
  tBTA_SERVICE_MASK services;  /* Services found on peer device. */
  bool is_gatt_over_ble;
  std::vector<bluetooth::Uuid> uuids;
  std::vector<bluetooth::Uuid> gatt_uuids;
  tBTA_STATUS result;
  tHCI_STATUS hci_status;
} tBTA_DM_SVC_RES;

using tBTA_DM_MSG = std::variant<tBTA_DM_API_DISCOVER, tBTA_DM_SVC_RES>;

typedef enum {
  BTA_DM_DISCOVER_IDLE,
  BTA_DM_DISCOVER_ACTIVE
} tBTA_DM_SERVICE_DISCOVERY_STATE;

inline std::string bta_dm_state_text(
    const tBTA_DM_SERVICE_DISCOVERY_STATE& state) {
  switch (state) {
    CASE_RETURN_TEXT(BTA_DM_DISCOVER_IDLE);
    CASE_RETURN_TEXT(BTA_DM_DISCOVER_ACTIVE);
  }
}

typedef struct {
  service_discovery_callbacks service_search_cbacks;
  tGATT_IF client_if;
  std::queue<tBTA_DM_API_DISCOVER> pending_discovery_queue;

  RawAddress peer_bdaddr;
  /* This covers service discovery state - callers of BTA_DmDiscover. That is
   * initial service discovery after bonding and
   * BluetoothDevice.fetchUuidsWithSdp(). Responsible for LE GATT Service
   * Discovery and SDP */
  tBTA_DM_SERVICE_DISCOVERY_STATE service_discovery_state;
  tBTA_SERVICE_MASK services_to_search;
  tBTA_SERVICE_MASK services_found;

  tSDP_DISCOVERY_DB* p_sdp_db;
  uint8_t service_index;
  uint8_t peer_scn;

  uint16_t conn_id;
  alarm_t* gatt_close_timer;    /* GATT channel close delay timer */
  RawAddress pending_close_bda; /* pending GATT channel remote device address */
} tBTA_DM_SERVICE_DISCOVERY_CB;

extern const uint32_t bta_service_id_to_btm_srv_id_lkup_tbl[];
extern const uint16_t bta_service_id_to_uuid_lkup_tbl[];

namespace fmt {
template <>
struct formatter<tBTA_DM_DISC_EVT> : enum_formatter<tBTA_DM_DISC_EVT> {};
template <>
struct formatter<tBTA_DM_SERVICE_DISCOVERY_STATE>
    : enum_formatter<tBTA_DM_SERVICE_DISCOVERY_STATE> {};
}  // namespace fmt
