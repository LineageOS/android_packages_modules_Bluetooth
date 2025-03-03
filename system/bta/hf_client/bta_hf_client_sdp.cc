/******************************************************************************
 *
 *  Copyright 2014 The Android Open Source Project
 *  Copyright 2003-2012 Broadcom Corporation
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
 *  This file contains the audio gateway functions performing SDP
 *  operations.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <cstring>

#include "bta/hf_client/bta_hf_client_int.h"
#include "bta/include/bta_hf_client_api.h"
#include "bta/include/bta_rfcomm_metrics.h"
#include "bta/include/bta_rfcomm_scn.h"
#include "bta/sys/bta_sys.h"
#include "bta_hfp_api.h"
#include "common/bind.h"
#include "common/time_util.h"
#include "internal_include/bt_target.h"
#include "osi/include/allocator.h"
#include "sdp_status.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/sdp_api.h"
#include "stack/include/sdpdefs.h"
#include "stack/sdp/sdp_discovery_db.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using bluetooth::Uuid;
using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

/* Number of protocol elements in protocol element list. */
#define BTA_HF_CLIENT_NUM_PROTO_ELEMS 2

/* Number of elements in service class id list. */
#define BTA_HF_CLIENT_NUM_SVC_ELEMS 2

/*******************************************************************************
 *
 * Function         bta_hf_client_sdp_cback
 *
 * Description      SDP callback function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_hf_client_sdp_cback(tBTA_HF_CLIENT_CB* client_cb, const RawAddress& /* bd_addr */,
                                    tSDP_STATUS status) {
  uint16_t event;
  tBTA_HF_CLIENT_DISC_RESULT* p_buf =
          (tBTA_HF_CLIENT_DISC_RESULT*)osi_malloc(sizeof(tBTA_HF_CLIENT_DISC_RESULT));

  log::verbose("bta_hf_client_sdp_cback status:0x{:x}", status);

  /* set event according to int/acp */
  if (client_cb->role == BTA_HF_CLIENT_ACP) {
    event = BTA_HF_CLIENT_DISC_ACP_RES_EVT;
  } else {
    event = BTA_HF_CLIENT_DISC_INT_RES_EVT;
  }

  p_buf->hdr.event = event;
  p_buf->hdr.layer_specific = client_cb->handle;
  p_buf->status = status;

  client_cb->sdp_metrics.status =
          (status == tSDP_STATUS::SDP_SUCCESS) ? tBTA_JV_STATUS::SUCCESS : tBTA_JV_STATUS::FAILURE;
  client_cb->sdp_metrics.sdp_end_ms = common::time_gettimeofday_us() / 1000;

  bta_sys_sendmsg(p_buf);
}

/******************************************************************************
 *
 * Function         bta_hf_client_add_record
 *
 * Description      This function is called by a server application to add
 *                  HFP Client information to an SDP record.  Prior to
 *                  calling this function the application must call
 *                  get_legacy_stack_sdp_api()->handle.SDP_CreateRecord() to
 *                  create an SDP record.
 *
 * Returns          true if function execution succeeded,
 *                  false if function execution failed.
 *
 *****************************************************************************/
bool bta_hf_client_add_record(const char* p_service_name, uint8_t scn, tBTA_HF_CLIENT_FEAT features,
                              uint32_t sdp_handle) {
  tSDP_PROTOCOL_ELEM proto_elem_list[BTA_HF_CLIENT_NUM_PROTO_ELEMS];
  uint16_t svc_class_id_list[BTA_HF_CLIENT_NUM_SVC_ELEMS];
  uint16_t browse_list[] = {UUID_SERVCLASS_PUBLIC_BROWSE_GROUP};
  uint16_t version;
  uint16_t profile_uuid;
  bool result = true;
  uint8_t buf[2];
  uint16_t sdp_features = 0;

  log::verbose("bta_hf_client_add_record");
  log::info("features: {}", features);

  memset(proto_elem_list, 0, BTA_HF_CLIENT_NUM_PROTO_ELEMS * sizeof(tSDP_PROTOCOL_ELEM));

  /* add the protocol element sequence */
  proto_elem_list[0].protocol_uuid = UUID_PROTOCOL_L2CAP;
  proto_elem_list[0].num_params = 0;
  proto_elem_list[1].protocol_uuid = UUID_PROTOCOL_RFCOMM;
  proto_elem_list[1].num_params = 1;
  proto_elem_list[1].params[0] = scn;
  result &= get_legacy_stack_sdp_api()->handle.SDP_AddProtocolList(
          sdp_handle, BTA_HF_CLIENT_NUM_PROTO_ELEMS, proto_elem_list);

  /* add service class id list */
  svc_class_id_list[0] = UUID_SERVCLASS_HF_HANDSFREE;
  svc_class_id_list[1] = UUID_SERVCLASS_GENERIC_AUDIO;
  result &= get_legacy_stack_sdp_api()->handle.SDP_AddServiceClassIdList(
          sdp_handle, BTA_HF_CLIENT_NUM_SVC_ELEMS, svc_class_id_list);

  /* add profile descriptor list */
  profile_uuid = UUID_SERVCLASS_HF_HANDSFREE;
  version = get_default_hfp_version();

  result &= get_legacy_stack_sdp_api()->handle.SDP_AddProfileDescriptorList(sdp_handle,
                                                                            profile_uuid, version);

  /* add service name */
  if (p_service_name != NULL && p_service_name[0] != 0) {
    result &= get_legacy_stack_sdp_api()->handle.SDP_AddAttribute(
            sdp_handle, ATTR_ID_SERVICE_NAME, TEXT_STR_DESC_TYPE,
            (uint32_t)(strlen(p_service_name) + 1), (uint8_t*)p_service_name);
  }

  /* add features */
  if (features & BTA_HF_CLIENT_FEAT_ECNR) {
    sdp_features |= BTA_HF_CLIENT_FEAT_ECNR;
  }

  if (features & BTA_HF_CLIENT_FEAT_3WAY) {
    sdp_features |= BTA_HF_CLIENT_FEAT_3WAY;
  }

  if (features & BTA_HF_CLIENT_FEAT_CLI) {
    sdp_features |= BTA_HF_CLIENT_FEAT_CLI;
  }

  if (features & BTA_HF_CLIENT_FEAT_VREC) {
    sdp_features |= BTA_HF_CLIENT_FEAT_VREC;
  }

  if (features & BTA_HF_CLIENT_FEAT_VOL) {
    sdp_features |= BTA_HF_CLIENT_FEAT_VOL;
  }

  /* Codec bit position is different in SDP (bit 5) and in BRSF (bit 7) */
  if (features & BTA_HF_CLIENT_FEAT_CODEC) {
    sdp_features |= BTA_HF_CLIENT_WBS_SUPPORT;
  }

  /* Support swb */
  if (features & BTA_HF_CLIENT_FEAT_SWB) {
    sdp_features |= BTA_HF_CLIENT_FEAT_SWB_SUPPORT;
  }

  UINT16_TO_BE_FIELD(buf, sdp_features);
  result &= get_legacy_stack_sdp_api()->handle.SDP_AddAttribute(
          sdp_handle, ATTR_ID_SUPPORTED_FEATURES, UINT_DESC_TYPE, 2, buf);

  /* add browse group list */
  result &= get_legacy_stack_sdp_api()->handle.SDP_AddUuidSequence(
          sdp_handle, ATTR_ID_BROWSE_GROUP_LIST, 1, browse_list);

  return result;
}

/*******************************************************************************
 *
 * Function         bta_hf_client_create_record
 *
 * Description      Create SDP record for registered service.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hf_client_create_record(tBTA_HF_CLIENT_CB_ARR* client_cb_arr, const char* p_service_name) {
  /* add sdp record if not already registered */
  if (client_cb_arr->sdp_handle == 0) {
    client_cb_arr->sdp_handle = get_legacy_stack_sdp_api()->handle.SDP_CreateRecord();
    client_cb_arr->scn = BTA_AllocateSCN();
    bta_hf_client_add_record(p_service_name, client_cb_arr->scn, client_cb_arr->features,
                             client_cb_arr->sdp_handle);

    bta_sys_add_uuid(UUID_SERVCLASS_HF_HANDSFREE);
  }
}

/*******************************************************************************
 *
 * Function         bta_hf_client_del_record
 *
 * Description      Delete SDP record for registered service.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hf_client_del_record(tBTA_HF_CLIENT_CB_ARR* client_cb) {
  log::verbose("");

  if (client_cb->sdp_handle != 0) {
    if (get_legacy_stack_sdp_api()->handle.SDP_DeleteRecord(client_cb->sdp_handle)) {
      log::warn("Unable to delete SDP record handle:{}", client_cb->sdp_handle);
    }
    client_cb->sdp_handle = 0;
    BTA_FreeSCN(client_cb->scn);
    bta_sys_remove_uuid(UUID_SERVCLASS_HF_HANDSFREE);
  }
}

/*******************************************************************************
 *
 * Function         bta_hf_client_sdp_find_attr
 *
 * Description      Process SDP discovery results to find requested attribute
 *
 *
 * Returns          true if results found, false otherwise.
 *
 ******************************************************************************/
bool bta_hf_client_sdp_find_attr(tBTA_HF_CLIENT_CB* client_cb) {
  tSDP_DISC_REC* p_rec = NULL;
  tSDP_DISC_ATTR* p_attr;
  tSDP_PROTOCOL_ELEM pe;
  bool result = false;

  client_cb->peer_version = HFP_VERSION_1_1; /* Default version */

  /* loop through all records we found */
  while (true) {
    /* get next record; if none found, we're done */
    p_rec = get_legacy_stack_sdp_api()->db.SDP_FindServiceInDb(client_cb->p_disc_db,
                                                               UUID_SERVCLASS_AG_HANDSFREE, p_rec);
    if (p_rec == NULL) {
      break;
    }

    /* get scn from proto desc list if initiator */
    if (client_cb->role == BTA_HF_CLIENT_INT) {
      if (get_legacy_stack_sdp_api()->record.SDP_FindProtocolListElemInRec(
                  p_rec, UUID_PROTOCOL_RFCOMM, &pe)) {
        client_cb->peer_scn = (uint8_t)pe.params[0];
      } else {
        continue;
      }
    }

    /* get profile version (if failure, version parameter is not updated) */
    if (!get_legacy_stack_sdp_api()->record.SDP_FindProfileVersionInRec(
                p_rec, UUID_SERVCLASS_HF_HANDSFREE, &client_cb->peer_version)) {
      log::warn("Uable to find HFP profile version in SDP record peer:{}", p_rec->remote_bd_addr);
    }

    /* get features */
    p_attr = get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(p_rec,
                                                                       ATTR_ID_SUPPORTED_FEATURES);
    if (p_attr != NULL && SDP_DISC_ATTR_TYPE(p_attr->attr_len_type) == UINT_DESC_TYPE &&
        SDP_DISC_ATTR_LEN(p_attr->attr_len_type) >= 2) {
      /* Found attribute. Get value. */
      /* There might be race condition between SDP and BRSF.  */
      /* Do not update if we already received BRSF.           */
      if (client_cb->peer_features == 0) {
        client_cb->peer_features = p_attr->attr_value.v.u16;

        /* SDP and BRSF WBS bit are different, correct it if set */
        if (client_cb->peer_features & 0x0020) {
          client_cb->peer_features &= ~0x0020;
          client_cb->peer_features |= BTA_HF_CLIENT_PEER_CODEC;
        }

        /* get network for ability to reject calls */
        p_attr = get_legacy_stack_sdp_api()->record.SDP_FindAttributeInRec(p_rec, ATTR_ID_NETWORK);
        if (p_attr != NULL && SDP_DISC_ATTR_TYPE(p_attr->attr_len_type) == UINT_DESC_TYPE &&
            SDP_DISC_ATTR_LEN(p_attr->attr_len_type) >= 2) {
          if (p_attr->attr_value.v.u16 == 0x01) {
            client_cb->peer_features |= BTA_HF_CLIENT_PEER_REJECT;
          }
        }
      }
    }

    /* found what we needed */
    result = true;
    break;
  }

  log::verbose("peer_version=0x{:x} peer_features=0x{:x}", client_cb->peer_version,
               client_cb->peer_features);

  return result;
}

/*******************************************************************************
 *
 * Function         bta_hf_client_do_disc
 *
 * Description      Do service discovery.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hf_client_do_disc(tBTA_HF_CLIENT_CB* client_cb) {
  Uuid uuid_list[1];
  uint16_t num_uuid = 1;
  uint16_t attr_list[4];
  uint8_t num_attr;
  bool db_inited = false;

  /* initiator; get proto list and features */
  if (client_cb->role == BTA_HF_CLIENT_INT) {
    attr_list[0] = ATTR_ID_SERVICE_CLASS_ID_LIST;
    attr_list[1] = ATTR_ID_PROTOCOL_DESC_LIST;
    attr_list[2] = ATTR_ID_BT_PROFILE_DESC_LIST;
    attr_list[3] = ATTR_ID_SUPPORTED_FEATURES;
    num_attr = 4;
    uuid_list[0] = Uuid::From16Bit(UUID_SERVCLASS_AG_HANDSFREE);
  } else {
    /* acceptor; get features */
    attr_list[0] = ATTR_ID_SERVICE_CLASS_ID_LIST;
    attr_list[1] = ATTR_ID_BT_PROFILE_DESC_LIST;
    attr_list[2] = ATTR_ID_SUPPORTED_FEATURES;
    num_attr = 3;
    uuid_list[0] = Uuid::From16Bit(UUID_SERVCLASS_AG_HANDSFREE);
  }

  /* If we already have a non-null discovery database at this point, we can get
   * into a race condition leading to UAF once this connection is closed.
   * This should only happen with malicious modifications to a client. */
  if (com::android::bluetooth::flags::btsec_check_valid_discovery_database() &&
      client_cb->p_disc_db != NULL) {
    log::error("Tried to set up a HF client with a preexisting discovery database.");
    client_cb->p_disc_db = NULL;
    // We manually set the state here because it's possible to call this from an
    // OPEN state, in which case the discovery fail event will be ignored.
    client_cb->state = 0;  // BTA_HF_CLIENT_INIT_ST
    return;
  }

  /* allocate buffer for sdp database */
  client_cb->p_disc_db = (tSDP_DISCOVERY_DB*)osi_malloc(BT_DEFAULT_BUFFER_SIZE);

  /* set up service discovery database; attr happens to be attr_list len */
  db_inited = get_legacy_stack_sdp_api()->service.SDP_InitDiscoveryDb(
          client_cb->p_disc_db, BT_DEFAULT_BUFFER_SIZE, num_uuid, uuid_list, num_attr, attr_list);

  if (db_inited) {
    /*Service discovery not initiated */
    db_inited = get_legacy_stack_sdp_api()->service.SDP_ServiceSearchAttributeRequest2(
            client_cb->peer_addr, client_cb->p_disc_db,
            base::BindRepeating(&bta_hf_client_sdp_cback, client_cb));
  }

  if (!db_inited) {
    log::warn("Unable to start SDP service search request peer:{}", client_cb->peer_addr);
    bta_collect_rfc_metrics_after_sdp_fail(tBTA_JV_STATUS::FAILURE, client_cb->peer_addr, 0,
                                           BTA_SEC_AUTHENTICATE | BTA_SEC_ENCRYPT, false, 0);
    /*free discover db */
    osi_free_and_reset((void**)&client_cb->p_disc_db);
    /* sent failed event */
    tBTA_HF_CLIENT_DATA msg;
    msg.hdr.layer_specific = client_cb->handle;
    bta_hf_client_sm_execute(BTA_HF_CLIENT_DISC_FAIL_EVT, &msg);
  }

  // Successfully started - record time SDP started
  client_cb->sdp_metrics.sdp_start_ms = common::time_gettimeofday_us() / 1000;
}

/*******************************************************************************
 *
 * Function         bta_hf_client_free_db
 *
 * Description      Free discovery database.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_hf_client_free_db(tBTA_HF_CLIENT_DATA* p_data) {
  log::assert_that(p_data != NULL, "assert failed: p_data != NULL");
  tBTA_HF_CLIENT_CB* client_cb = bta_hf_client_find_cb_by_handle(p_data->hdr.layer_specific);
  if (client_cb == NULL) {
    log::error("cb not found for handle {}", p_data->hdr.layer_specific);
    return;
  }

  osi_free_and_reset((void**)&client_cb->p_disc_db);
}
