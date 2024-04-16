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

#define LOG_TAG "bt_bta_dm"

#include "bta/dm/bta_dm_disc.h"

#include <android_bluetooth_flags.h>
#include <base/functional/bind.h>
#include <base/strings/stringprintf.h>
#include <bluetooth/log.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <variant>
#include <vector>

#include "android_bluetooth_flags.h"
#include "bta/dm/bta_dm_disc_int.h"
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

#ifdef TARGET_FLOSS
#include "stack/include/srvc_api.h"
#endif

using bluetooth::Uuid;
using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

tBTM_CONTRL_STATE bta_dm_pm_obtain_controller_state(void);

namespace {
constexpr char kBtmLogTag[] = "SDP";

tBTA_DM_SERVICE_DISCOVERY_CB bta_dm_discovery_cb;
}  // namespace

static void bta_dm_disc_sm_execute(tBTA_DM_DISC_EVT event,
                                   std::unique_ptr<tBTA_DM_MSG> msg);
static void post_disc_evt(tBTA_DM_DISC_EVT event,
                          std::unique_ptr<tBTA_DM_MSG> msg) {
  if (do_in_main_thread(FROM_HERE, base::BindOnce(&bta_dm_disc_sm_execute,
                                                  event, std::move(msg))) !=
      BT_STATUS_SUCCESS) {
    log::error("post_disc_evt failed");
  }
}

static void bta_dm_gatt_disc_complete(uint16_t conn_id, tGATT_STATUS status);
static void bta_dm_find_services(const RawAddress& bd_addr);
static void bta_dm_sdp_callback(const RawAddress& bd_addr,
                                tSDP_STATUS sdp_status);
static void bta_dm_discover_services(const RawAddress& remote_bd_addr,
                                     tBT_TRANSPORT transport);
static void bta_dm_disable_disc(void);
static void bta_dm_gattc_register(void);
static void btm_dm_start_gatt_discovery(const RawAddress& bd_addr);
static void bta_dm_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);
static void bta_dm_free_sdp_db();
static void bta_dm_execute_queued_discovery_request();
static void bta_dm_close_gatt_conn();

TimestampedStringCircularBuffer disc_gatt_history_{50};

namespace {

struct gatt_interface_t {
  void (*BTA_GATTC_CancelOpen)(tGATT_IF client_if, const RawAddress& remote_bda,
                               bool is_direct);
  void (*BTA_GATTC_Refresh)(const RawAddress& remote_bda);
  void (*BTA_GATTC_GetGattDb)(uint16_t conn_id, uint16_t start_handle,
                              uint16_t end_handle, btgatt_db_element_t** db,
                              int* count);
  void (*BTA_GATTC_AppRegister)(tBTA_GATTC_CBACK* p_client_cb,
                                BtaAppRegisterCallback cb, bool eatt_support);
  void (*BTA_GATTC_Close)(uint16_t conn_id);
  void (*BTA_GATTC_ServiceSearchRequest)(uint16_t conn_id,
                                         const bluetooth::Uuid* p_srvc_uuid);
  void (*BTA_GATTC_Open)(tGATT_IF client_if, const RawAddress& remote_bda,
                         tBTM_BLE_CONN_TYPE connection_type,
                         bool opportunistic);
} default_gatt_interface = {
    .BTA_GATTC_CancelOpen =
        [](tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) {
          disc_gatt_history_.Push(base::StringPrintf(
              "%-32s bd_addr:%s client_if:%hu is_direct:%c", "GATTC_CancelOpen",
              ADDRESS_TO_LOGGABLE_CSTR(remote_bda), client_if,
              (is_direct) ? 'T' : 'F'));
          BTA_GATTC_CancelOpen(client_if, remote_bda, is_direct);
        },
    .BTA_GATTC_Refresh =
        [](const RawAddress& remote_bda) {
          disc_gatt_history_.Push(
              base::StringPrintf("%-32s bd_addr:%s", "GATTC_Refresh",
                                 ADDRESS_TO_LOGGABLE_CSTR(remote_bda)));
          BTA_GATTC_Refresh(remote_bda);
        },
    .BTA_GATTC_GetGattDb =
        [](uint16_t conn_id, uint16_t start_handle, uint16_t end_handle,
           btgatt_db_element_t** db, int* count) {
          disc_gatt_history_.Push(base::StringPrintf(
              "%-32s conn_id:%hu start_handle:%hu end:handle:%hu",
              "GATTC_GetGattDb", conn_id, start_handle, end_handle));
          BTA_GATTC_GetGattDb(conn_id, start_handle, end_handle, db, count);
        },
    .BTA_GATTC_AppRegister =
        [](tBTA_GATTC_CBACK* p_client_cb, BtaAppRegisterCallback cb,
           bool eatt_support) {
          disc_gatt_history_.Push(
              base::StringPrintf("%-32s eatt_support:%c", "GATTC_AppRegister",
                                 (eatt_support) ? 'T' : 'F'));
          BTA_GATTC_AppRegister(p_client_cb, cb, eatt_support);
        },
    .BTA_GATTC_Close =
        [](uint16_t conn_id) {
          disc_gatt_history_.Push(
              base::StringPrintf("%-32s conn_id:%hu", "GATTC_Close", conn_id));
          BTA_GATTC_Close(conn_id);
        },
    .BTA_GATTC_ServiceSearchRequest =
        [](uint16_t conn_id, const bluetooth::Uuid* p_srvc_uuid) {
          disc_gatt_history_.Push(base::StringPrintf(
              "%-32s conn_id:%hu", "GATTC_ServiceSearchRequest", conn_id));
          BTA_GATTC_ServiceSearchRequest(conn_id, p_srvc_uuid);
        },
    .BTA_GATTC_Open =
        [](tGATT_IF client_if, const RawAddress& remote_bda,
           tBTM_BLE_CONN_TYPE connection_type, bool opportunistic) {
          disc_gatt_history_.Push(base::StringPrintf(
              "%-32s bd_addr:%s client_if:%hu type:0x%x opportunistic:%c",
              "GATTC_Open", ADDRESS_TO_LOGGABLE_CSTR(remote_bda), client_if,
              connection_type, (opportunistic) ? 'T' : 'F'));
          BTA_GATTC_Open(client_if, remote_bda, connection_type, opportunistic);
        },
};

gatt_interface_t* gatt_interface = &default_gatt_interface;

gatt_interface_t& get_gatt_interface() { return *gatt_interface; }

}  // namespace

void bta_dm_disc_disable_search_and_disc() {
  if (IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    log::info("No one should be calling this when flag is enabled");
    return;
  }
  bta_dm_disc_legacy::bta_dm_disc_disable_search_and_disc();
}

void bta_dm_disc_disable_disc() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    log::info("no-op when flag is disabled");
    return;
  }
  bta_dm_disable_disc();
}

void bta_dm_disc_gatt_cancel_open(const RawAddress& bd_addr) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_gatt_cancel_open(bd_addr);
    return;
  }
  get_gatt_interface().BTA_GATTC_CancelOpen(0, bd_addr, false);
}

void bta_dm_disc_gatt_refresh(const RawAddress& bd_addr) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_gatt_refresh(bd_addr);
    return;
  }
  get_gatt_interface().BTA_GATTC_Refresh(bd_addr);
}

void bta_dm_disc_remove_device(const RawAddress& bd_addr) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_remove_device(bd_addr);
    return;
  }
  if (bta_dm_discovery_cb.service_discovery_state == BTA_DM_DISCOVER_ACTIVE &&
      bta_dm_discovery_cb.peer_bdaddr == bd_addr) {
    log::info(
        "Device removed while service discovery was pending, conclude the "
        "service disvovery");
    bta_dm_gatt_disc_complete((uint16_t)GATT_INVALID_CONN_ID,
                              (tGATT_STATUS)GATT_ERROR);
  }
}

void bta_dm_disc_gattc_register() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_gattc_register();
    return;
  }
  bta_dm_gattc_register();
}

const uint16_t bta_service_id_to_uuid_lkup_tbl[BTA_MAX_SERVICE_ID] = {
    UUID_SERVCLASS_PNP_INFORMATION,       /* Reserved */
    UUID_SERVCLASS_SERIAL_PORT,           /* BTA_SPP_SERVICE_ID */
    UUID_SERVCLASS_DIALUP_NETWORKING,     /* BTA_DUN_SERVICE_ID */
    UUID_SERVCLASS_AUDIO_SOURCE,          /* BTA_A2DP_SOURCE_SERVICE_ID */
    UUID_SERVCLASS_LAN_ACCESS_USING_PPP,  /* BTA_LAP_SERVICE_ID */
    UUID_SERVCLASS_HEADSET,               /* BTA_HSP_HS_SERVICE_ID */
    UUID_SERVCLASS_HF_HANDSFREE,          /* BTA_HFP_HS_SERVICE_ID */
    UUID_SERVCLASS_OBEX_OBJECT_PUSH,      /* BTA_OPP_SERVICE_ID */
    UUID_SERVCLASS_OBEX_FILE_TRANSFER,    /* BTA_FTP_SERVICE_ID */
    UUID_SERVCLASS_CORDLESS_TELEPHONY,    /* BTA_CTP_SERVICE_ID */
    UUID_SERVCLASS_INTERCOM,              /* BTA_ICP_SERVICE_ID */
    UUID_SERVCLASS_IRMC_SYNC,             /* BTA_SYNC_SERVICE_ID */
    UUID_SERVCLASS_DIRECT_PRINTING,       /* BTA_BPP_SERVICE_ID */
    UUID_SERVCLASS_IMAGING_RESPONDER,     /* BTA_BIP_SERVICE_ID */
    UUID_SERVCLASS_PANU,                  /* BTA_PANU_SERVICE_ID */
    UUID_SERVCLASS_NAP,                   /* BTA_NAP_SERVICE_ID */
    UUID_SERVCLASS_GN,                    /* BTA_GN_SERVICE_ID */
    UUID_SERVCLASS_SAP,                   /* BTA_SAP_SERVICE_ID */
    UUID_SERVCLASS_AUDIO_SINK,            /* BTA_A2DP_SERVICE_ID */
    UUID_SERVCLASS_AV_REMOTE_CONTROL,     /* BTA_AVRCP_SERVICE_ID */
    UUID_SERVCLASS_HUMAN_INTERFACE,       /* BTA_HID_SERVICE_ID */
    UUID_SERVCLASS_VIDEO_SINK,            /* BTA_VDP_SERVICE_ID */
    UUID_SERVCLASS_PBAP_PSE,              /* BTA_PBAP_SERVICE_ID */
    UUID_SERVCLASS_HEADSET_AUDIO_GATEWAY, /* BTA_HSP_SERVICE_ID */
    UUID_SERVCLASS_AG_HANDSFREE,          /* BTA_HFP_SERVICE_ID */
    UUID_SERVCLASS_MESSAGE_ACCESS,        /* BTA_MAP_SERVICE_ID */
    UUID_SERVCLASS_MESSAGE_NOTIFICATION,  /* BTA_MN_SERVICE_ID */
    UUID_SERVCLASS_HDP_PROFILE,           /* BTA_HDP_SERVICE_ID */
    UUID_SERVCLASS_PBAP_PCE,              /* BTA_PCE_SERVICE_ID */
    UUID_PROTOCOL_ATT                     /* BTA_GATT_SERVICE_ID */
};

#define MAX_DISC_RAW_DATA_BUF (4096)
static uint8_t g_disc_raw_data_buf[MAX_DISC_RAW_DATA_BUF];

static void bta_dm_discovery_set_state(tBTA_DM_SERVICE_DISCOVERY_STATE state) {
  bta_dm_discovery_cb.service_discovery_state = state;
}
static tBTA_DM_SERVICE_DISCOVERY_STATE bta_dm_discovery_get_state() {
  return bta_dm_discovery_cb.service_discovery_state;
}

// TODO. Currently we did nothing
static void bta_dm_discovery_cancel() {}

/*******************************************************************************
 *
 * Function         bta_dm_discover
 *
 * Description      Discovers services on a remote device
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_discover(tBTA_DM_API_DISCOVER& discover) {
  bta_dm_gattc_register();

  bta_dm_discovery_cb.service_search_cbacks = discover.cbacks;
  bta_dm_discovery_cb.services_to_search = BTA_ALL_SERVICE_MASK;
  bta_dm_discovery_cb.service_index = 0;
  bta_dm_discovery_cb.services_found = 0;

  log::info(
      "bta_dm_discovery: starting service discovery to {} , transport: {}",
      discover.bd_addr, bt_transport_text(discover.transport));
  bta_dm_discover_services(discover.bd_addr, discover.transport);
}

/*******************************************************************************
 *
 * Function         bta_dm_disable_search_and_disc
 *
 * Description      Cancels an ongoing search or discovery for devices in case
 *                  of a Bluetooth disable
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_disable_disc(void) {
  switch (bta_dm_discovery_get_state()) {
    case BTA_DM_DISCOVER_IDLE:
      break;
    case BTA_DM_DISCOVER_ACTIVE:
    default:
      log::debug(
          "Discovery state machine is not idle so issuing discovery cancel "
          "current "
          "state:{}",
          bta_dm_state_text(bta_dm_discovery_get_state()));
      bta_dm_discovery_cancel();
  }
}

static void store_avrcp_profile_feature(tSDP_DISC_REC* sdp_rec) {
  tSDP_DISC_ATTR* p_attr =
      get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
          sdp_rec, ATTR_ID_SUPPORTED_FEATURES);
  if (p_attr == NULL) {
    return;
  }

  uint16_t avrcp_features = p_attr->attr_value.v.u16;
  if (avrcp_features == 0) {
    return;
  }

  if (btif_config_set_bin(sdp_rec->remote_bd_addr.ToString().c_str(),
                          BTIF_STORAGE_KEY_AV_REM_CTRL_FEATURES,
                          (const uint8_t*)&avrcp_features,
                          sizeof(avrcp_features))) {
    log::info("Saving avrcp_features: 0x{:x}", avrcp_features);
  } else {
    log::info("Failed to store avrcp_features 0x{:x} for {}", avrcp_features,
              sdp_rec->remote_bd_addr);
  }
}

static void bta_dm_store_audio_profiles_version() {
  struct AudioProfile {
    const uint16_t servclass_uuid;
    const uint16_t btprofile_uuid;
    const char* profile_key;
    void (*store_audio_profile_feature)(tSDP_DISC_REC*);
  };

  std::array<AudioProfile, 1> audio_profiles = {{
      {
          .servclass_uuid = UUID_SERVCLASS_AV_REMOTE_CONTROL,
          .btprofile_uuid = UUID_SERVCLASS_AV_REMOTE_CONTROL,
          .profile_key = BTIF_STORAGE_KEY_AVRCP_CONTROLLER_VERSION,
          .store_audio_profile_feature = store_avrcp_profile_feature,
      },
  }};

  for (const auto& audio_profile : audio_profiles) {
    tSDP_DISC_REC* sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(
        bta_dm_discovery_cb.p_sdp_db, audio_profile.servclass_uuid, NULL);
    if (sdp_rec == NULL) continue;

    if (get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
            sdp_rec, ATTR_ID_BT_PROFILE_DESC_LIST) == NULL)
      continue;

    uint16_t profile_version = 0;
    /* get profile version (if failure, version parameter is not updated) */
    get_legacy_stack_sdp_api()->record.SDP_FindProfileVersionInRec(
        sdp_rec, audio_profile.btprofile_uuid, &profile_version);
    if (profile_version != 0) {
      if (btif_config_set_bin(sdp_rec->remote_bd_addr.ToString().c_str(),
                              audio_profile.profile_key,
                              (const uint8_t*)&profile_version,
                              sizeof(profile_version))) {
      } else {
        log::info("Failed to store peer profile version for {}",
                  sdp_rec->remote_bd_addr);
      }
    }
    audio_profile.store_audio_profile_feature(sdp_rec);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_sdp_result
 *
 * Description      Process the discovery result from sdp
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_sdp_result(tBTA_DM_SDP_RESULT& sdp_event) {
  tSDP_DISC_REC* p_sdp_rec = NULL;
  bool scn_found = false;
  uint16_t service = 0xFFFF;
  tSDP_PROTOCOL_ELEM pe;

  std::vector<Uuid> uuid_list;

  const tSDP_RESULT sdp_result = sdp_event.sdp_result;

  if ((sdp_event.sdp_result == SDP_SUCCESS) ||
      (sdp_event.sdp_result == SDP_NO_RECS_MATCH) ||
      (sdp_event.sdp_result == SDP_DB_FULL)) {
    log::verbose("sdp_result::0x{:x}", sdp_event.sdp_result);
    do {
      p_sdp_rec = NULL;
      if (bta_dm_discovery_cb.service_index == (BTA_USER_SERVICE_ID + 1)) {
        if (p_sdp_rec &&
            get_legacy_stack_sdp_api()->record.SDP_FindProtocolListElemInRec(
                p_sdp_rec, UUID_PROTOCOL_RFCOMM, &pe)) {
          bta_dm_discovery_cb.peer_scn = (uint8_t)pe.params[0];
          scn_found = true;
        }
      } else {
        service =
            bta_service_id_to_uuid_lkup_tbl[bta_dm_discovery_cb.service_index -
                                            1];
        p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(
            bta_dm_discovery_cb.p_sdp_db, service, p_sdp_rec);
      }
      /* finished with BR/EDR services, now we check the result for GATT based
       * service UUID */
      if (bta_dm_discovery_cb.service_index == BTA_MAX_SERVICE_ID) {
        /* all GATT based services */

        std::vector<Uuid> gatt_uuids;

        do {
          /* find a service record, report it */
          p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(
              bta_dm_discovery_cb.p_sdp_db, 0, p_sdp_rec);
          if (p_sdp_rec) {
            Uuid service_uuid;
            if (get_legacy_stack_sdp_api()->record.SDP_FindServiceUUIDInRec(
                    p_sdp_rec, &service_uuid)) {
              gatt_uuids.push_back(service_uuid);
            }
          }
        } while (p_sdp_rec);

        if (!gatt_uuids.empty()) {
          log::info("GATT services discovered using SDP");

          // send all result back to app
          bta_dm_discovery_cb.service_search_cbacks.on_gatt_results(
              bta_dm_discovery_cb.peer_bdaddr, BD_NAME{}, gatt_uuids,
              /* transport_le */ false);
        }
      } else {
        if ((p_sdp_rec != NULL)) {
          if (service != UUID_SERVCLASS_PNP_INFORMATION) {
            bta_dm_discovery_cb.services_found |=
                (tBTA_SERVICE_MASK)(BTA_SERVICE_ID_TO_SERVICE_MASK(
                    bta_dm_discovery_cb.service_index - 1));
            uint16_t tmp_svc = bta_service_id_to_uuid_lkup_tbl
                [bta_dm_discovery_cb.service_index - 1];
            /* Add to the list of UUIDs */
            uuid_list.push_back(Uuid::From16Bit(tmp_svc));
          }
        }
      }

      if (bta_dm_discovery_cb.services_to_search == 0) {
        bta_dm_discovery_cb.service_index++;
      } else /* regular one service per search or PNP search */
        break;

    } while (bta_dm_discovery_cb.service_index <= BTA_MAX_SERVICE_ID);

    log::verbose("services_found = {:04x}", bta_dm_discovery_cb.services_found);

    /* Collect the 128-bit services here and put them into the list */
    p_sdp_rec = NULL;
    do {
      /* find a service record, report it */
      p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb_128bit(
          bta_dm_discovery_cb.p_sdp_db, p_sdp_rec);
      if (p_sdp_rec) {
        // SDP_FindServiceUUIDInRec_128bit is used only once, refactor?
        Uuid temp_uuid;
        if (get_legacy_stack_sdp_api()->record.SDP_FindServiceUUIDInRec_128bit(
                p_sdp_rec, &temp_uuid)) {
          uuid_list.push_back(temp_uuid);
        }
      }
    } while (p_sdp_rec);

    if (bluetooth::common::init_flags::
            dynamic_avrcp_version_enhancement_is_enabled() &&
        bta_dm_discovery_cb.services_to_search == 0) {
      bta_dm_store_audio_profiles_version();
    }

#if TARGET_FLOSS
    tSDP_DI_GET_RECORD di_record;
    if (get_legacy_stack_sdp_api()->device_id.SDP_GetDiRecord(
            1, &di_record, bta_dm_discovery_cb.p_sdp_db) == SDP_SUCCESS) {
      bta_dm_discovery_cb.service_search_cbacks.on_did_received(
          bta_dm_discovery_cb.peer_bdaddr, di_record.rec.vendor_id_source,
          di_record.rec.vendor, di_record.rec.product, di_record.rec.version);
    }
#endif

    /* if there are more services to search for */
    if (bta_dm_discovery_cb.services_to_search) {
      /* Free up the p_sdp_db before checking the next one */
      bta_dm_free_sdp_db();
      bta_dm_find_services(bta_dm_discovery_cb.peer_bdaddr);
    } else {
      /* callbacks */
      /* start next bd_addr if necessary */
      BTM_LogHistory(
          kBtmLogTag, bta_dm_discovery_cb.peer_bdaddr, "Discovery completed",
          base::StringPrintf("Result:%s services_found:0x%x service_index:0x%d",
                             sdp_result_text(sdp_result).c_str(),
                             bta_dm_discovery_cb.services_found,
                             bta_dm_discovery_cb.service_index));

      auto msg = std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{});
      auto& disc_result = std::get<tBTA_DM_SVC_RES>(*msg);

      disc_result.result = BTA_SUCCESS;
      disc_result.uuids = std::move(uuid_list);
      // Copy the raw_data to the discovery result structure
      if (bta_dm_discovery_cb.p_sdp_db != NULL &&
          bta_dm_discovery_cb.p_sdp_db->raw_used != 0 &&
          bta_dm_discovery_cb.p_sdp_db->raw_data != NULL) {
        log::verbose("raw_data used = 0x{:x} raw_data_ptr = 0x{}",
                     bta_dm_discovery_cb.p_sdp_db->raw_used,
                     fmt::ptr(bta_dm_discovery_cb.p_sdp_db->raw_data));

        bta_dm_discovery_cb.p_sdp_db->raw_data =
            NULL;  // no need to free this - it is a global assigned.
        bta_dm_discovery_cb.p_sdp_db->raw_used = 0;
        bta_dm_discovery_cb.p_sdp_db->raw_size = 0;
      } else {
        log::verbose("raw data size is 0 or raw_data is null!!");
      }
      /* Done with p_sdp_db. Free it */
      bta_dm_free_sdp_db();
      disc_result.services = bta_dm_discovery_cb.services_found;

      // Piggy back the SCN over result field
      if (scn_found) {
        disc_result.result =
            static_cast<tBTA_STATUS>((3 + bta_dm_discovery_cb.peer_scn));
        disc_result.services |= BTA_USER_SERVICE_MASK;

        log::verbose("Piggy back the SCN over result field  SCN={}",
                     bta_dm_discovery_cb.peer_scn);
      }
      disc_result.bd_addr = bta_dm_discovery_cb.peer_bdaddr;

      bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::move(msg));
    }
  } else {
    BTM_LogHistory(
        kBtmLogTag, bta_dm_discovery_cb.peer_bdaddr, "Discovery failed",
        base::StringPrintf("Result:%s", sdp_result_text(sdp_result).c_str()));
    log::error("SDP connection failed {}", sdp_status_text(sdp_result));
    if (sdp_event.sdp_result == SDP_CONN_FAILED)
      bta_dm_discovery_cb.wait_disc = false;

    /* not able to connect go to next device */
    if (bta_dm_discovery_cb.p_sdp_db)
      osi_free_and_reset((void**)&bta_dm_discovery_cb.p_sdp_db);

    auto msg = std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{});
    auto& disc_result = std::get<tBTA_DM_SVC_RES>(*msg);

    disc_result.result = BTA_FAILURE;
    disc_result.services = bta_dm_discovery_cb.services_found;
    disc_result.bd_addr = bta_dm_discovery_cb.peer_bdaddr;

    bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::move(msg));
  }
}

/** Callback of peer's DIS reply. This is only called for floss */
#if TARGET_FLOSS
static void bta_dm_read_dis_cmpl(const RawAddress& addr,
                                 tDIS_VALUE* p_dis_value) {
  if (!p_dis_value) {
    log::warn("read DIS failed");
  } else {
    bta_dm_discovery_cb.service_search_cbacks.on_did_received(
        addr, p_dis_value->pnp_id.vendor_id_src, p_dis_value->pnp_id.vendor_id,
        p_dis_value->pnp_id.product_id, p_dis_value->pnp_id.product_version);
  }

  bta_dm_execute_queued_discovery_request();
}
#endif

static void bta_dm_service_discovery_cmpl() {
  bta_dm_discovery_set_state(BTA_DM_DISCOVER_IDLE);

  uint16_t conn_id = bta_dm_discovery_cb.conn_id;

  std::vector<Uuid> gatt_services;

  bool send_gatt_results =
      bluetooth::common::init_flags::
              always_send_services_if_gatt_disc_done_is_enabled()
          ? bta_dm_discovery_cb.gatt_disc_active
          : false;

  /* no BLE connection, i.e. Classic service discovery end */
  if (conn_id == GATT_INVALID_CONN_ID) {
    if (bta_dm_discovery_cb.gatt_disc_active) {
      log::warn(
          "GATT active but no BLE connection, likely disconnected midway "
          "through");
    } else {
      log::info("No BLE connection, processing classic results");
    }
  } else {
    btgatt_db_element_t* db = NULL;
    int count = 0;
    get_gatt_interface().BTA_GATTC_GetGattDb(conn_id, 0x0000, 0xFFFF, &db,
                                             &count);
    if (count != 0) {
      for (int i = 0; i < count; i++) {
        // we process service entries only
        if (db[i].type == BTGATT_DB_PRIMARY_SERVICE) {
          gatt_services.push_back(db[i].uuid);
        }
      }
      osi_free(db);
      log::info(
          "GATT services discovered using LE Transport, will always send to "
          "upper layer");
      send_gatt_results = true;
    } else {
      log::warn("Empty GATT database - no BLE services discovered");
    }
  }

  // send all result back to app
  if (send_gatt_results) {
    if (bta_dm_discovery_cb.service_search_cbacks.on_gatt_results != nullptr) {
      log::info("Sending GATT results to upper layer");

      bta_dm_discovery_cb.service_search_cbacks.on_gatt_results(
          bta_dm_discovery_cb.peer_bdaddr, BD_NAME{}, gatt_services,
          /* transport_le */ true);
    } else {
      log::warn("on_gatt_results is nullptr!");
    }
  }

  bta_dm_discovery_cb.gatt_disc_active = false;

#if TARGET_FLOSS
  if (conn_id != GATT_INVALID_CONN_ID &&
      DIS_ReadDISInfo(bta_dm_discovery_cb.peer_bdaddr, bta_dm_read_dis_cmpl,
                      DIS_ATTR_PNP_ID_BIT)) {
    return;
  }
#endif

  bta_dm_execute_queued_discovery_request();
}

/*******************************************************************************
 *
 * Function         bta_dm_disc_result
 *
 * Description      Service discovery result when discovering services on a
 *                  device
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_disc_result(tBTA_DM_SVC_RES& disc_result) {
  log::verbose("");

  /* disc_res.device_type is set only when GATT discovery is finished in
   * bta_dm_gatt_disc_complete */
  bool is_gatt_over_ble = ((disc_result.device_type & BT_DEVICE_TYPE_BLE) != 0);

  /* if any BR/EDR service discovery has been done, report the event */
  if (!is_gatt_over_ble) {
    auto& r = disc_result;
    bta_dm_discovery_cb.service_search_cbacks.on_service_discovery_results(
        r.bd_addr, r.services, r.device_type, r.uuids, r.result, r.hci_status);
  } else {
    GAP_BleReadPeerPrefConnParams(bta_dm_discovery_cb.peer_bdaddr);
  }

  get_gatt_interface().BTA_GATTC_CancelOpen(0, bta_dm_discovery_cb.peer_bdaddr,
                                            true);

  bta_dm_service_discovery_cmpl();
}

/*******************************************************************************
 *
 * Function         bta_dm_free_sdp_db
 *
 * Description      Frees SDP data base
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_free_sdp_db() {
  osi_free_and_reset((void**)&bta_dm_discovery_cb.p_sdp_db);
}

/*******************************************************************************
 *
 * Function         bta_dm_queue_disc
 *
 * Description      Queues discovery command
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_queue_disc(tBTA_DM_API_DISCOVER& discovery) {
  log::info("bta_dm_discovery: queuing service discovery to {}",
            discovery.bd_addr);
  bta_dm_discovery_cb.pending_discovery_queue.push(discovery);
}

static void bta_dm_execute_queued_discovery_request() {
  if (bta_dm_discovery_cb.pending_discovery_queue.empty()) {
    log::info("No more service discovery queued");
    return;
  }

  tBTA_DM_API_DISCOVER pending_discovery =
      bta_dm_discovery_cb.pending_discovery_queue.front();
  bta_dm_discovery_cb.pending_discovery_queue.pop();
  log::info("Start pending discovery");
  post_disc_evt(
      BTA_DM_API_DISCOVER_EVT,
      std::make_unique<tBTA_DM_MSG>(tBTA_DM_API_DISCOVER{pending_discovery}));
}

/*******************************************************************************
 *
 * Function         bta_dm_find_services
 *
 * Description      Starts discovery on a device
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_find_services(const RawAddress& bd_addr) {
  while (bta_dm_discovery_cb.service_index < BTA_MAX_SERVICE_ID) {
    Uuid uuid = Uuid::kEmpty;
    if (bta_dm_discovery_cb.services_to_search &
        (tBTA_SERVICE_MASK)(BTA_SERVICE_ID_TO_SERVICE_MASK(
            bta_dm_discovery_cb.service_index))) {
      bta_dm_discovery_cb.p_sdp_db =
          (tSDP_DISCOVERY_DB*)osi_malloc(BTA_DM_SDP_DB_SIZE);

      /* try to search all services by search based on L2CAP UUID */
      log::info("services_to_search={:08x}",
                bta_dm_discovery_cb.services_to_search);
      if (bta_dm_discovery_cb.services_to_search & BTA_RES_SERVICE_MASK) {
        uuid = Uuid::From16Bit(bta_service_id_to_uuid_lkup_tbl[0]);
        bta_dm_discovery_cb.services_to_search &= ~BTA_RES_SERVICE_MASK;
      } else {
        uuid = Uuid::From16Bit(UUID_PROTOCOL_L2CAP);
        bta_dm_discovery_cb.services_to_search = 0;
      }

      log::info("search UUID = {}", uuid.ToString());
      get_legacy_stack_sdp_api()->service.SDP_InitDiscoveryDb(
          bta_dm_discovery_cb.p_sdp_db, BTA_DM_SDP_DB_SIZE, 1, &uuid, 0, NULL);

      memset(g_disc_raw_data_buf, 0, sizeof(g_disc_raw_data_buf));
      bta_dm_discovery_cb.p_sdp_db->raw_data = g_disc_raw_data_buf;

      bta_dm_discovery_cb.p_sdp_db->raw_size = MAX_DISC_RAW_DATA_BUF;

      if (!get_legacy_stack_sdp_api()
               ->service.SDP_ServiceSearchAttributeRequest(
                   bd_addr, bta_dm_discovery_cb.p_sdp_db,
                   &bta_dm_sdp_callback)) {
        /*
         * If discovery is not successful with this device, then
         * proceed with the next one.
         */
        osi_free_and_reset((void**)&bta_dm_discovery_cb.p_sdp_db);
        bta_dm_discovery_cb.service_index = BTA_MAX_SERVICE_ID;

      } else {
        if (uuid == Uuid::From16Bit(UUID_PROTOCOL_L2CAP)) {
          if (!is_sdp_pbap_pce_disabled(bd_addr)) {
            log::debug("SDP search for PBAP Client");
            BTA_SdpSearch(bd_addr, Uuid::From16Bit(UUID_SERVCLASS_PBAP_PCE));
          }
        }
        bta_dm_discovery_cb.service_index++;
        return;
      }
    }

    bta_dm_discovery_cb.service_index++;
  }

  /* no more services to be discovered */
  if (bta_dm_discovery_cb.service_index >= BTA_MAX_SERVICE_ID) {
    auto msg = std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{});
    auto& disc_result = std::get<tBTA_DM_SVC_RES>(*msg);
    disc_result.services = bta_dm_discovery_cb.services_found;
    disc_result.bd_addr = bta_dm_discovery_cb.peer_bdaddr;

    post_disc_evt(BTA_DM_DISCOVERY_RESULT_EVT, std::move(msg));
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_determine_discovery_transport
 *
 * Description      Starts name and service discovery on the device
 *
 * Returns          void
 *
 ******************************************************************************/
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

static void bta_dm_discover_services(const RawAddress& remote_bd_addr,
                                     tBT_TRANSPORT transport) {
  if (transport == BT_TRANSPORT_AUTO) {
    transport = bta_dm_determine_discovery_transport(remote_bd_addr);
  }

  log::verbose("BDA: {}, transport={}, state = {}", remote_bd_addr, transport,
               bta_dm_discovery_get_state());

  bta_dm_discovery_cb.peer_bdaddr = remote_bd_addr;

  bool sdp_disable = HID_HostSDPDisable(remote_bd_addr);
  if (sdp_disable)
    log::debug("peer:{} with HIDSDPDisable attribute.", remote_bd_addr);

  /* if application wants to discover service and HIDSDPDisable attribute is
     false.
     Classic mouses with this attribute should not start SDP here, because the
     SDP has been done during bonding. SDP request here will interleave with
     connections to the Control or Interrupt channels */
  if (!sdp_disable) {
    BTM_LogHistory(kBtmLogTag, remote_bd_addr, "Discovery started ",
                   base::StringPrintf("Transport:%s",
                                      bt_transport_text(transport).c_str()));

    /* initialize variables */
    bta_dm_discovery_cb.service_index = 0;
    bta_dm_discovery_cb.services_found = 0;
    bta_dm_discovery_cb.services_to_search = BTA_ALL_SERVICE_MASK;

    /* if seaching with EIR is not completed */
    if (bta_dm_discovery_cb.services_to_search) {
      /* check whether connection already exists to the device
         if connection exists, we don't have to wait for ACL
         link to go down to start search on next device */
      if (transport == BT_TRANSPORT_BR_EDR) {
        if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(
                bta_dm_discovery_cb.peer_bdaddr, BT_TRANSPORT_BR_EDR))
          bta_dm_discovery_cb.wait_disc = false;
        else
          bta_dm_discovery_cb.wait_disc = true;
      }

      if (transport == BT_TRANSPORT_LE) {
        if (bta_dm_discovery_cb.services_to_search & BTA_BLE_SERVICE_MASK) {
          log::info("bta_dm_discovery: starting GATT discovery on {}",
                    bta_dm_discovery_cb.peer_bdaddr);
          // set the raw data buffer here
          memset(g_disc_raw_data_buf, 0, sizeof(g_disc_raw_data_buf));
          /* start GATT for service discovery */
          btm_dm_start_gatt_discovery(bta_dm_discovery_cb.peer_bdaddr);
          return;
        }
      } else {
        log::info("bta_dm_discovery: starting SDP discovery on {}",
                  bta_dm_discovery_cb.peer_bdaddr);
        bta_dm_discovery_cb.sdp_results = false;
        bta_dm_find_services(bta_dm_discovery_cb.peer_bdaddr);
        return;
      }
    }
  }

  /* service discovery is done for this device */
  auto msg = std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{});
  auto& svc_result = std::get<tBTA_DM_SVC_RES>(*msg);

  /* initialize the data structure */
  svc_result.result = BTA_SUCCESS;
  svc_result.services = bta_dm_discovery_cb.services_found;
  svc_result.bd_addr = bta_dm_discovery_cb.peer_bdaddr;

  bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::move(msg));
}

/*******************************************************************************
 *
 * Function         bta_dm_sdp_callback
 *
 * Description      Callback from sdp with discovery status
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_sdp_callback(const RawAddress& /* bd_addr */,
                                tSDP_STATUS sdp_status) {
  post_disc_evt(BTA_DM_SDP_RESULT_EVT,
                std::make_unique<tBTA_DM_MSG>(
                    tBTA_DM_SDP_RESULT{.sdp_result = sdp_status}));
}

#ifndef BTA_DM_GATT_CLOSE_DELAY_TOUT
#define BTA_DM_GATT_CLOSE_DELAY_TOUT 1000
#endif

/*******************************************************************************
 *
 * Function         bta_dm_gattc_register
 *
 * Description      Register with GATTC in DM if BLE is needed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_gattc_register(void) {
  if (bta_dm_discovery_cb.client_if != BTA_GATTS_INVALID_IF) {
    // Already registered
    return;
  }
  get_gatt_interface().BTA_GATTC_AppRegister(
      bta_dm_gattc_callback, base::Bind([](uint8_t client_id, uint8_t status) {
        tGATT_STATUS gatt_status = static_cast<tGATT_STATUS>(status);
        disc_gatt_history_.Push(base::StringPrintf(
            "%-32s client_id:%hu status:%s", "GATTC_RegisteredCallback",
            client_id, gatt_status_text(gatt_status).c_str()));
        if (static_cast<tGATT_STATUS>(status) == GATT_SUCCESS) {
          log::info(
              "Registered device discovery search gatt client tGATT_IF:{}",
              client_id);
          bta_dm_discovery_cb.client_if = client_id;
        } else {
          log::warn(
              "Failed to register device discovery search gatt client "
              "gatt_status:{} previous tGATT_IF:{}",
              bta_dm_discovery_cb.client_if, status);
          bta_dm_discovery_cb.client_if = BTA_GATTS_INVALID_IF;
        }
      }),
      false);
}

static void gatt_close_timer_cb(void*) {
  bta_dm_disc_sm_execute(BTA_DM_DISC_CLOSE_TOUT_EVT, nullptr);
}

/*******************************************************************************
 *
 * Function         bta_dm_gatt_disc_complete
 *
 * Description      This function process the GATT service search complete.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_gatt_disc_complete(uint16_t conn_id, tGATT_STATUS status) {
  log::verbose("conn_id = {}", conn_id);

  auto msg = std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{});
  auto& svc_result = std::get<tBTA_DM_SVC_RES>(*msg);

  /* no more services to be discovered */
  svc_result.result = (status == GATT_SUCCESS) ? BTA_SUCCESS : BTA_FAILURE;
  log::verbose("service found: 0x{:08x}", bta_dm_discovery_cb.services_found);
  svc_result.services = bta_dm_discovery_cb.services_found;
  svc_result.bd_addr = bta_dm_discovery_cb.peer_bdaddr;
  svc_result.device_type |= BT_DEVICE_TYPE_BLE;

  bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::move(msg));

  if (conn_id != GATT_INVALID_CONN_ID) {
    bta_dm_discovery_cb.pending_close_bda = bta_dm_discovery_cb.peer_bdaddr;
    // Gatt will be close immediately if bluetooth.gatt.delay_close.enabled is
    // set to false. If property is true / unset there will be a delay
    if (bta_dm_discovery_cb.gatt_close_timer != nullptr) {
      /* start a GATT channel close delay timer */
      alarm_set_on_mloop(bta_dm_discovery_cb.gatt_close_timer,
                         BTA_DM_GATT_CLOSE_DELAY_TOUT, gatt_close_timer_cb, 0);
    } else {
      bta_dm_disc_sm_execute(BTA_DM_DISC_CLOSE_TOUT_EVT, nullptr);
    }
  } else {
    bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;

    if (IS_FLAG_ENABLED(bta_dm_disc_stuck_in_cancelling_fix)) {
      log::info(
          "Discovery complete for invalid conn ID. Will pick up next job");
      bta_dm_discovery_set_state(BTA_DM_DISCOVER_IDLE);
      bta_dm_free_sdp_db();
      bta_dm_execute_queued_discovery_request();
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_close_gatt_conn
 *
 * Description      This function close the GATT connection after delay
 *timeout.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_close_gatt_conn() {
  if (bta_dm_discovery_cb.conn_id != GATT_INVALID_CONN_ID)
    BTA_GATTC_Close(bta_dm_discovery_cb.conn_id);

  bta_dm_discovery_cb.pending_close_bda = RawAddress::kEmpty;
  bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
}
/*******************************************************************************
 *
 * Function         btm_dm_start_gatt_discovery
 *
 * Description      This is GATT initiate the service search by open a GATT
 *                  connection first.
 *
 * Parameters:
 *
 ******************************************************************************/
static void btm_dm_start_gatt_discovery(const RawAddress& bd_addr) {
  constexpr bool kUseOpportunistic = true;

  bta_dm_discovery_cb.gatt_disc_active = true;

  /* connection is already open */
  if (bta_dm_discovery_cb.pending_close_bda == bd_addr &&
      bta_dm_discovery_cb.conn_id != GATT_INVALID_CONN_ID) {
    bta_dm_discovery_cb.pending_close_bda = RawAddress::kEmpty;
    alarm_cancel(bta_dm_discovery_cb.gatt_close_timer);
    get_gatt_interface().BTA_GATTC_ServiceSearchRequest(
        bta_dm_discovery_cb.conn_id, nullptr);
  } else {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(
            bd_addr, BT_TRANSPORT_LE)) {
      log::debug(
          "Use existing gatt client connection for discovery peer:{} "
          "transport:{} opportunistic:{:c}",
          bd_addr, bt_transport_text(BT_TRANSPORT_LE),
          (kUseOpportunistic) ? 'T' : 'F');
      get_gatt_interface().BTA_GATTC_Open(bta_dm_discovery_cb.client_if,
                                          bd_addr, BTM_BLE_DIRECT_CONNECTION,
                                          kUseOpportunistic);
    } else {
      log::debug(
          "Opening new gatt client connection for discovery peer:{} "
          "transport:{} opportunistic:{:c}",
          bd_addr, bt_transport_text(BT_TRANSPORT_LE),
          (!kUseOpportunistic) ? 'T' : 'F');
      get_gatt_interface().BTA_GATTC_Open(bta_dm_discovery_cb.client_if,
                                          bd_addr, BTM_BLE_DIRECT_CONNECTION,
                                          !kUseOpportunistic);
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_proc_open_evt
 *
 * Description      process BTA_GATTC_OPEN_EVT in DM.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_proc_open_evt(tBTA_GATTC_OPEN* p_data) {
  log::verbose(
      "DM Search state= {} bta_dm_discovery_cb.peer_dbaddr:{} connected_bda={}",
      bta_dm_discovery_get_state(), bta_dm_discovery_cb.peer_bdaddr,
      p_data->remote_bda);

  log::debug("BTA_GATTC_OPEN_EVT conn_id = {} client_if={} status = {}",
             p_data->conn_id, p_data->client_if, p_data->status);

  disc_gatt_history_.Push(base::StringPrintf(
      "%-32s bd_addr:%s conn_id:%hu client_if:%hu event:%s",
      "GATTC_EventCallback", ADDRESS_TO_LOGGABLE_CSTR(p_data->remote_bda),
      p_data->conn_id, p_data->client_if,
      gatt_client_event_text(BTA_GATTC_OPEN_EVT).c_str()));

  bta_dm_discovery_cb.conn_id = p_data->conn_id;

  if (p_data->status == GATT_SUCCESS) {
    get_gatt_interface().BTA_GATTC_ServiceSearchRequest(p_data->conn_id,
                                                        nullptr);
  } else {
    bta_dm_gatt_disc_complete(GATT_INVALID_CONN_ID, p_data->status);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_gattc_callback
 *
 * Description      This is GATT client callback function used in DM.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
  log::verbose("bta_dm_gattc_callback event = {}", event);

  switch (event) {
    case BTA_GATTC_OPEN_EVT:
      bta_dm_proc_open_evt(&p_data->open);
      break;

    case BTA_GATTC_SEARCH_CMPL_EVT:
      if (bta_dm_discovery_get_state() == BTA_DM_DISCOVER_ACTIVE) {
        bta_dm_gatt_disc_complete(p_data->search_cmpl.conn_id,
                                  p_data->search_cmpl.status);
      }
      disc_gatt_history_.Push(base::StringPrintf(
          "%-32s conn_id:%hu status:%s", "GATTC_EventCallback",
          p_data->search_cmpl.conn_id,
          gatt_status_text(p_data->search_cmpl.status).c_str()));
      break;

    case BTA_GATTC_CLOSE_EVT:
      log::info("BTA_GATTC_CLOSE_EVT reason = {}", p_data->close.reason);

      if (p_data->close.remote_bda == bta_dm_discovery_cb.peer_bdaddr) {
        bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
      }

      if (bta_dm_discovery_get_state() == BTA_DM_DISCOVER_ACTIVE) {
        /* in case of disconnect before search is completed */
        if (p_data->close.remote_bda == bta_dm_discovery_cb.peer_bdaddr) {
          bta_dm_gatt_disc_complete((uint16_t)GATT_INVALID_CONN_ID,
                                    (tGATT_STATUS)GATT_ERROR);
        }
      }
      break;

    case BTA_GATTC_ACL_EVT:
    case BTA_GATTC_CANCEL_OPEN_EVT:
    case BTA_GATTC_CFG_MTU_EVT:
    case BTA_GATTC_CONGEST_EVT:
    case BTA_GATTC_CONN_UPDATE_EVT:
    case BTA_GATTC_DEREG_EVT:
    case BTA_GATTC_ENC_CMPL_CB_EVT:
    case BTA_GATTC_EXEC_EVT:
    case BTA_GATTC_NOTIF_EVT:
    case BTA_GATTC_PHY_UPDATE_EVT:
    case BTA_GATTC_SEARCH_RES_EVT:
    case BTA_GATTC_SRVC_CHG_EVT:
    case BTA_GATTC_SRVC_DISC_DONE_EVT:
    case BTA_GATTC_SUBRATE_CHG_EVT:
      disc_gatt_history_.Push(
          base::StringPrintf("%-32s event:%s", "GATTC_EventCallback",
                             gatt_client_event_text(event).c_str()));
      break;
  }
}

namespace bluetooth {
namespace legacy {
namespace testing {

tBT_TRANSPORT bta_dm_determine_discovery_transport(const RawAddress& bd_addr) {
  return ::bta_dm_determine_discovery_transport(bd_addr);
}

void bta_dm_sdp_result(tBTA_DM_SDP_RESULT& sdp_event) {
  ::bta_dm_sdp_result(sdp_event);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

namespace {
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

struct tDISCOVERY_STATE_HISTORY {
  const tBTA_DM_SERVICE_DISCOVERY_STATE state;
  const tBTA_DM_DISC_EVT event;
  std::string ToString() const {
    return base::StringPrintf("state:%25s event:%s",
                              bta_dm_state_text(state).c_str(),
                              bta_dm_event_text(event).c_str());
  }
};

bluetooth::common::TimestampedCircularBuffer<tDISCOVERY_STATE_HISTORY>
    discovery_state_history_(50 /*history size*/);

static void bta_dm_disc_sm_execute(tBTA_DM_DISC_EVT event,
                                   std::unique_ptr<tBTA_DM_MSG> msg) {
  log::info("state:{}, event:{}[0x{:x}]",
            bta_dm_state_text(bta_dm_discovery_get_state()),
            bta_dm_event_text(event), event);
  discovery_state_history_.Push({
      .state = bta_dm_discovery_get_state(),
      .event = event,
  });

  switch (bta_dm_discovery_get_state()) {
    case BTA_DM_DISCOVER_IDLE:
      switch (event) {
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_discovery_set_state(BTA_DM_DISCOVER_ACTIVE);
          log::assert_that(std::holds_alternative<tBTA_DM_API_DISCOVER>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_discover(std::get<tBTA_DM_API_DISCOVER>(*msg));
          break;
        case BTA_DM_SDP_RESULT_EVT:
          bta_dm_free_sdp_db();
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          bta_dm_close_gatt_conn();
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}",
                    bta_dm_event_text(event), event,
                    bta_dm_state_text(bta_dm_discovery_get_state()));
      }
      break;

    case BTA_DM_DISCOVER_ACTIVE:
      switch (event) {
        case BTA_DM_SDP_RESULT_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_SDP_RESULT>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_sdp_result(std::get<tBTA_DM_SDP_RESULT>(*msg));
          break;
        case BTA_DM_DISCOVERY_RESULT_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_SVC_RES>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_disc_result(std::get<tBTA_DM_SVC_RES>(*msg));
          break;
        case BTA_DM_API_DISCOVER_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_API_DISCOVER>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_queue_disc(std::get<tBTA_DM_API_DISCOVER>(*msg));
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          bta_dm_close_gatt_conn();
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}",
                    bta_dm_event_text(event), event,
                    bta_dm_state_text(bta_dm_discovery_get_state()));
      }
      break;
  }
}

static void bta_dm_disc_init_discovery_cb(
    tBTA_DM_SERVICE_DISCOVERY_CB& bta_dm_discovery_cb) {
  bta_dm_discovery_cb = {};
  bta_dm_discovery_cb.service_discovery_state = BTA_DM_DISCOVER_IDLE;
  bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
}

static void bta_dm_disc_reset() {
  alarm_free(bta_dm_discovery_cb.search_timer);
  alarm_free(bta_dm_discovery_cb.gatt_close_timer);
  bta_dm_disc_init_discovery_cb(::bta_dm_discovery_cb);
}

void bta_dm_disc_start(bool delay_close_gatt) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_start(delay_close_gatt);
    return;
  }
  bta_dm_disc_reset();
  bta_dm_discovery_cb.search_timer = alarm_new("bta_dm_search.search_timer");
  bta_dm_discovery_cb.gatt_close_timer =
      delay_close_gatt ? alarm_new("bta_dm_search.gatt_close_timer") : nullptr;
  bta_dm_discovery_cb.pending_discovery_queue = {};
}

void bta_dm_disc_acl_down(const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_acl_down(bd_addr, transport);
    return;
  }
  switch (transport) {
    case BT_TRANSPORT_BR_EDR:
      if (bta_dm_discovery_cb.wait_disc &&
          bta_dm_discovery_cb.peer_bdaddr == bd_addr) {
        bta_dm_discovery_cb.wait_disc = false;

        if (bta_dm_discovery_cb.sdp_results) {
          log::verbose("timer stopped");
          alarm_cancel(bta_dm_discovery_cb.search_timer);
          bta_dm_disc_discover_next_device();
        }
      }
      break;

    case BT_TRANSPORT_LE:
    default:
      break;
  }
}

void bta_dm_disc_stop() {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_stop();
    return;
  }
  bta_dm_disc_reset();
}

void bta_dm_disc_start_service_discovery(service_discovery_callbacks cbacks,
                                         const RawAddress& bd_addr,
                                         tBT_TRANSPORT transport) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::bta_dm_disc_start_service_discovery(cbacks, bd_addr,
                                                            transport);
    return;
  }
  bta_dm_disc_sm_execute(
      BTA_DM_API_DISCOVER_EVT,
      std::make_unique<tBTA_DM_MSG>(tBTA_DM_API_DISCOVER{
          .bd_addr = bd_addr, .cbacks = cbacks, .transport = transport}));
}

#define DUMPSYS_TAG "shim::legacy::bta::dm"
void DumpsysBtaDmDisc(int fd) {
  if (!IS_FLAG_ENABLED(separate_service_and_device_discovery)) {
    bta_dm_disc_legacy::DumpsysBtaDmDisc(fd);
    return;
  }
  auto copy = discovery_state_history_.Pull();
  LOG_DUMPSYS(fd, " last %zu discovery state transitions", copy.size());
  for (const auto& it : copy) {
    LOG_DUMPSYS(fd, "   %s %s", EpochMillisToString(it.timestamp).c_str(),
                it.entry.ToString().c_str());
  }
  LOG_DUMPSYS(fd, " current bta_dm_discovery_state:%s",
              bta_dm_state_text(bta_dm_discovery_get_state()).c_str());
}
#undef DUMPSYS_TAG

namespace bluetooth {
namespace legacy {
namespace testing {

tBTA_DM_SERVICE_DISCOVERY_CB& bta_dm_discovery_cb() {
  return ::bta_dm_discovery_cb;
}

void bta_dm_find_services(const RawAddress& bd_addr) {
  ::bta_dm_find_services(bd_addr);
}

void store_avrcp_profile_feature(tSDP_DISC_REC* sdp_rec) {
  ::store_avrcp_profile_feature(sdp_rec);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth
