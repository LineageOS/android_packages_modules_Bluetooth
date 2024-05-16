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

#define LOG_TAG "bt_bta_dm"

#include <base/functional/bind.h>
#include <base/strings/stringprintf.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <string>
#include <vector>

#include "bta/dm/bta_dm_disc.h"
#include "bta/dm/bta_dm_disc_int.h"
#include "bta/include/bta_sdp_api.h"
#include "btif/include/btif_config.h"
#include "com_android_bluetooth_flags.h"
#include "common/init_flags.h"
#include "common/strings.h"
#include "internal_include/bt_target.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_log_history.h"
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

namespace {
constexpr char kBtmLogTag[] = "SDP";
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

static void bta_dm_store_audio_profiles_version(tSDP_DISCOVERY_DB* p_sdp_db) {
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
        p_sdp_db, audio_profile.servclass_uuid, NULL);
    if (sdp_rec == NULL) continue;

    if (get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(
            sdp_rec, ATTR_ID_BT_PROFILE_DESC_LIST) == NULL)
      continue;

    uint16_t profile_version = 0;
    /* get profile version (if failure, version parameter is not updated) */
    if (!get_legacy_stack_sdp_api()->record.SDP_FindProfileVersionInRec(
            sdp_rec, audio_profile.btprofile_uuid, &profile_version)) {
      log::warn("Unable to find SDP profile version in record peer:{}",
                sdp_rec->remote_bd_addr);
    }
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

/* Process the discovery result from sdp */
void bta_dm_sdp_result(tSDP_STATUS sdp_result, tBTA_DM_SDP_STATE* sdp_state) {
  tSDP_DISC_REC* p_sdp_rec = NULL;
  bool scn_found = false;
  uint16_t service = 0xFFFF;
  tSDP_PROTOCOL_ELEM pe;

  std::vector<Uuid> uuid_list;
  tSDP_DISCOVERY_DB* p_sdp_db = (tSDP_DISCOVERY_DB*)sdp_state->sdp_db_buffer;

  if ((sdp_result == SDP_SUCCESS) || (sdp_result == SDP_NO_RECS_MATCH) ||
      (sdp_result == SDP_DB_FULL)) {
    log::verbose("sdp_result::0x{:x}", sdp_result);
    std::vector<Uuid> gatt_uuids;
    do {
      p_sdp_rec = NULL;
      if (sdp_state->service_index == (BTA_USER_SERVICE_ID + 1)) {
        if (p_sdp_rec &&
            get_legacy_stack_sdp_api()->record.SDP_FindProtocolListElemInRec(
                p_sdp_rec, UUID_PROTOCOL_RFCOMM, &pe)) {
          sdp_state->peer_scn = (uint8_t)pe.params[0];
          scn_found = true;
        }
      } else {
        service = bta_service_id_to_uuid_lkup_tbl[sdp_state->service_index - 1];
        p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(
            p_sdp_db, service, p_sdp_rec);
      }
      /* finished with BR/EDR services, now we check the result for GATT based
       * service UUID */
      if (sdp_state->service_index == BTA_MAX_SERVICE_ID) {
        /* all GATT based services */
        do {
          /* find a service record, report it */
          p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(
              p_sdp_db, 0, p_sdp_rec);
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
        }
      } else {
        if (p_sdp_rec != NULL && service != UUID_SERVCLASS_PNP_INFORMATION) {
          sdp_state->services_found |=
              (tBTA_SERVICE_MASK)(BTA_SERVICE_ID_TO_SERVICE_MASK(
                  sdp_state->service_index - 1));
          uint16_t tmp_svc =
              bta_service_id_to_uuid_lkup_tbl[sdp_state->service_index - 1];
          /* Add to the list of UUIDs */
          uuid_list.push_back(Uuid::From16Bit(tmp_svc));
        }
      }

      if (sdp_state->services_to_search == 0) {
        sdp_state->service_index++;
      } else /* regular one service per search or PNP search */
        break;

    } while (sdp_state->service_index <= BTA_MAX_SERVICE_ID);

    log::verbose("services_found = {:04x}", sdp_state->services_found);

    /* Collect the 128-bit services here and put them into the list */
    p_sdp_rec = NULL;
    do {
      /* find a service record, report it */
      p_sdp_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb_128bit(
          p_sdp_db, p_sdp_rec);
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
        sdp_state->services_to_search == 0) {
      bta_dm_store_audio_profiles_version(p_sdp_db);
    }

#if TARGET_FLOSS
    tSDP_DI_GET_RECORD di_record;
    if (get_legacy_stack_sdp_api()->device_id.SDP_GetDiRecord(
            1, &di_record, p_sdp_db) == SDP_SUCCESS) {
      bta_dm_sdp_received_di(sdp_state->bd_addr, di_record);
    }
#endif

    /* if there are more services to search for */
    if (sdp_state->services_to_search) {
      bta_dm_sdp_find_services(sdp_state);
      return;
    }

    /* callbacks */
    /* start next bd_addr if necessary */
    BTM_LogHistory(kBtmLogTag, sdp_state->bd_addr, "Discovery completed",
                   base::StringPrintf(
                       "Result:%s services_found:0x%x service_index:0x%d",
                       sdp_result_text(sdp_result).c_str(),
                       sdp_state->services_found, sdp_state->service_index));

    // Copy the raw_data to the discovery result structure
    if (p_sdp_db != NULL && p_sdp_db->raw_used != 0 &&
        p_sdp_db->raw_data != NULL) {
      log::verbose("raw_data used = 0x{:x} raw_data_ptr = 0x{}",
                   p_sdp_db->raw_used, fmt::ptr(p_sdp_db->raw_data));

      p_sdp_db->raw_data =
          NULL;  // no need to free this - it is a global assigned.
      p_sdp_db->raw_used = 0;
      p_sdp_db->raw_size = 0;
    } else {
      log::verbose("raw data size is 0 or raw_data is null!!");
    }

    tBTA_STATUS result = BTA_SUCCESS;
    auto services = sdp_state->services_found;
    // Piggy back the SCN over result field
    if (scn_found) {
      result = static_cast<tBTA_STATUS>((3 + sdp_state->peer_scn));
      services |= BTA_USER_SERVICE_MASK;

      log::verbose("Piggy back the SCN over result field  SCN={}",
                   sdp_state->peer_scn);
    }

    bta_dm_sdp_finished(sdp_state->bd_addr, result, services, uuid_list,
                        gatt_uuids);
  } else {
    BTM_LogHistory(
        kBtmLogTag, sdp_state->bd_addr, "Discovery failed",
        base::StringPrintf("Result:%s", sdp_result_text(sdp_result).c_str()));
    log::error("SDP connection failed {}", sdp_status_text(sdp_result));

    /* not able to connect go to next device */
    bta_dm_sdp_finished(sdp_state->bd_addr, BTA_FAILURE,
                        sdp_state->services_found);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_sdp_find_services
 *
 * Description      Starts discovery on a device
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sdp_find_services(tBTA_DM_SDP_STATE* sdp_state) {
  while (sdp_state->service_index < BTA_MAX_SERVICE_ID) {
    if (sdp_state->services_to_search &
        (tBTA_SERVICE_MASK)(BTA_SERVICE_ID_TO_SERVICE_MASK(
            sdp_state->service_index))) {
      break;
    }
    sdp_state->service_index++;
  }

  /* no more services to be discovered */
  if (sdp_state->service_index >= BTA_MAX_SERVICE_ID) {
    log::info("SDP - no more services to discover");
    bta_dm_sdp_finished(sdp_state->bd_addr, BTA_SUCCESS,
                        sdp_state->services_found);
    return;
  }

  /* try to search all services by search based on L2CAP UUID */
  log::info("services_to_search={:08x}", sdp_state->services_to_search);
  Uuid uuid = Uuid::kEmpty;
  if (sdp_state->services_to_search & BTA_RES_SERVICE_MASK) {
    uuid = Uuid::From16Bit(bta_service_id_to_uuid_lkup_tbl[0]);
    sdp_state->services_to_search &= ~BTA_RES_SERVICE_MASK;
  } else {
    uuid = Uuid::From16Bit(UUID_PROTOCOL_L2CAP);
    sdp_state->services_to_search = 0;
  }

  tSDP_DISCOVERY_DB* p_sdp_db = (tSDP_DISCOVERY_DB*)sdp_state->sdp_db_buffer;

  log::info("search UUID = {}", uuid.ToString());
  if (!get_legacy_stack_sdp_api()->service.SDP_InitDiscoveryDb(
          p_sdp_db, BTA_DM_SDP_DB_SIZE, 1, &uuid, 0, NULL)) {
    log::warn("Unable to initialize SDP service discovery db peer:{}",
              sdp_state->bd_addr);
  }

  sdp_state->g_disc_raw_data_buf = {};
  p_sdp_db->raw_data = sdp_state->g_disc_raw_data_buf.data();

  p_sdp_db->raw_size = MAX_DISC_RAW_DATA_BUF;

  if (!get_legacy_stack_sdp_api()->service.SDP_ServiceSearchAttributeRequest(
          sdp_state->bd_addr, p_sdp_db, &bta_dm_sdp_callback)) {
    /*
     * If discovery is not successful with this device, then
     * proceed with the next one.
     */
    log::warn("Unable to start SDP service search attribute request peer:{}",
              sdp_state->bd_addr);

    sdp_state->service_index = BTA_MAX_SERVICE_ID;
    bta_dm_sdp_finished(sdp_state->bd_addr, BTA_SUCCESS,
                        sdp_state->services_found);
    return;
  }

  if (uuid == Uuid::From16Bit(UUID_PROTOCOL_L2CAP)) {
    if (!is_sdp_pbap_pce_disabled(sdp_state->bd_addr)) {
      log::debug("SDP search for PBAP Client");
      BTA_SdpSearch(sdp_state->bd_addr,
                    Uuid::From16Bit(UUID_SERVCLASS_PBAP_PCE));
    }
  }
  sdp_state->service_index++;
}

namespace bluetooth {
namespace legacy {
namespace testing {

void bta_dm_sdp_find_services(tBTA_DM_SDP_STATE* sdp_state) {
  ::bta_dm_sdp_find_services(sdp_state);
}

void store_avrcp_profile_feature(tSDP_DISC_REC* sdp_rec) {
  ::store_avrcp_profile_feature(sdp_rec);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth
