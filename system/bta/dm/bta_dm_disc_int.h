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

#include <bluetooth/log.h>

#include <queue>
#include <string>

#include "bta/dm/bta_dm_device_search_int.h"
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
  RawAddress bd_addr; /* BD address peer device. */
  bool is_gatt_over_ble;
  std::vector<bluetooth::Uuid> uuids;
  std::vector<bluetooth::Uuid> gatt_uuids;
  tBTA_STATUS result;
  tHCI_STATUS hci_status;
} tBTA_DM_SVC_RES;

/* data type for BTA_DM_API_DISCOVER_EVT */
typedef struct {
  uint16_t conn_id;
} tBTA_DM_TOUT;

using tBTA_DM_MSG = std::variant<tBTA_DM_API_DISCOVER, tBTA_DM_SVC_RES, tBTA_DM_TOUT>;

typedef enum { BTA_DM_DISCOVER_IDLE, BTA_DM_DISCOVER_ACTIVE } tBTA_DM_SERVICE_DISCOVERY_STATE;

inline std::string bta_dm_state_text(const tBTA_DM_SERVICE_DISCOVERY_STATE& state) {
  switch (state) {
    CASE_RETURN_TEXT(BTA_DM_DISCOVER_IDLE);
    CASE_RETURN_TEXT(BTA_DM_DISCOVER_ACTIVE);
  }
}

#define MAX_DISC_RAW_DATA_BUF (4096)

typedef struct {
  RawAddress bd_addr;
  tBTA_SERVICE_MASK services_to_search;
  tBTA_SERVICE_MASK services_found;

  uint8_t service_index;
  uint8_t peer_scn;

  std::array<uint8_t, MAX_DISC_RAW_DATA_BUF> g_disc_raw_data_buf;

  /* sdp_db must be together with sdp_db_buffer*/
  alignas(tSDP_DISCOVERY_DB) uint8_t sdp_db_buffer[BTA_DM_SDP_DB_SIZE];
} tBTA_DM_SDP_STATE;

typedef struct {
  service_discovery_callbacks service_search_cbacks;
  tGATT_IF client_if;
  std::queue<tBTA_DM_API_DISCOVER> pending_discovery_queue;

  RawAddress peer_bdaddr;
  uint8_t transports;
  /* This covers service discovery state - callers of BTA_DmDiscover. That is
   * initial service discovery after bonding and
   * BluetoothDevice.fetchUuidsWithSdp(). Responsible for LE GATT Service
   * Discovery and SDP */
  tBTA_DM_SERVICE_DISCOVERY_STATE service_discovery_state;
  std::unique_ptr<tBTA_DM_SDP_STATE> sdp_state;

  tCONN_ID conn_id;
  alarm_t* gatt_close_timer;    /* GATT channel close delay timer */
  RawAddress pending_close_bda; /* pending GATT channel remote device address */
} tBTA_DM_SERVICE_DISCOVERY_CB;

extern const uint32_t bta_service_id_to_btm_srv_id_lkup_tbl[];
extern const uint16_t bta_service_id_to_uuid_lkup_tbl[];

void bta_dm_disc_override_sdp_performer_for_testing(
        base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> sdp_performer);
void bta_dm_disc_override_gatt_performer_for_testing(
        base::RepeatingCallback<void(const RawAddress&)> test_gatt_performer);
void bta_dm_sdp_find_services(tBTA_DM_SDP_STATE* sdp_state);
void bta_dm_sdp_result(tSDP_STATUS sdp_result, tBTA_DM_SDP_STATE* sdp_state);
void bta_dm_sdp_finished(RawAddress bda, tBTA_STATUS result,
                         std::vector<bluetooth::Uuid> uuids = {},
                         std::vector<bluetooth::Uuid> gatt_uuids = {});
void bta_dm_gatt_finished(RawAddress bda, tBTA_STATUS result,
                          std::vector<bluetooth::Uuid> gatt_uuids = {});
void bta_dm_sdp_callback(const RawAddress& bd_addr, tSDP_STATUS sdp_status);

#ifdef TARGET_FLOSS
void bta_dm_sdp_received_di(const RawAddress& bd_addr, tSDP_DI_GET_RECORD& di_record);
#endif

namespace std {
template <>
struct formatter<tBTA_DM_DISC_EVT> : enum_formatter<tBTA_DM_DISC_EVT> {};
template <>
struct formatter<tBTA_DM_SERVICE_DISCOVERY_STATE>
    : enum_formatter<tBTA_DM_SERVICE_DISCOVERY_STATE> {};
}  // namespace std

namespace bluetooth::legacy::testing {

tBT_TRANSPORT bta_dm_determine_discovery_transport(const RawAddress& bd_addr);
void bta_dm_remote_name_cmpl(const tBTA_DM_REMOTE_NAME& remote_name_msg);

}  // namespace bluetooth::legacy::testing
