/******************************************************************************
 *
 *  Copyright 2008-2012 Broadcom Corporation
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
 *  This is the implementation for the audio/video registration module.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

#include "avrcp_sdp_records.h"
#include "bta/ar/bta_ar_int.h"
#include "bta/include/bta_ar_api.h"
#include "bta/sys/bta_sys.h"
#include "profile/avrcp/avrcp_sdp_service.h"
#include "stack/include/avct_api.h"
#include "stack/include/avdt_api.h"
#include "stack/include/avrc_api.h"
#include "stack/include/avrc_defs.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/sdp_api.h"
#include "stack/include/sdpdefs.h"

using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth::avrcp;
using namespace bluetooth;

/* AV control block */
tBTA_AR_CB bta_ar_cb;

/*******************************************************************************
 *
 * Function         bta_ar_init
 *
 * Description      This function is called to register to AVDTP.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ar_init(void) {
  /* initialize control block */
  memset(&bta_ar_cb, 0, sizeof(tBTA_AR_CB));
}

/*******************************************************************************
 *
 * Function         bta_ar_reg_avdt
 *
 * Description      This function is called to register to AVDTP.
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_ar_avdt_cback(uint8_t handle, const RawAddress& bd_addr, uint8_t event,
                              tAVDT_CTRL* p_data, uint8_t scb_index) {
  /* route the AVDT registration callback to av or avk */
  if (bta_ar_cb.p_av_conn_cback) {
    (*bta_ar_cb.p_av_conn_cback)(handle, bd_addr, event, p_data, scb_index);
  }
}

/*******************************************************************************
 *
 * Function         bta_ar_reg_avdt
 *
 * Description      AR module registration to AVDT.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ar_reg_avdt(AvdtpRcb* p_reg, tAVDT_CTRL_CBACK* p_cback) {
  bta_ar_cb.p_av_conn_cback = p_cback;
  if (bta_ar_cb.avdt_registered == 0) {
    AVDT_Register(p_reg, bta_ar_avdt_cback);
  } else {
    log::warn("doesn't register again (registered:{})", bta_ar_cb.avdt_registered);
  }
  bta_ar_cb.avdt_registered |= BTA_AR_AV_MASK;
}

/*******************************************************************************
 *
 * Function         bta_ar_dereg_avdt
 *
 * Description      This function is called to de-register from AVDTP.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ar_dereg_avdt() {
  bta_ar_cb.p_av_conn_cback = NULL;
  bta_ar_cb.avdt_registered &= ~BTA_AR_AV_MASK;

  if (bta_ar_cb.avdt_registered == 0) {
    AVDT_Deregister();
  }
}

/*******************************************************************************
 *
 * Function         bta_ar_reg_avct
 *
 * Description      This function is called to register to AVCTP.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ar_reg_avct() {
  if (bta_ar_cb.avct_registered == 0) {
    AVCT_Register();
  }
  bta_ar_cb.avct_registered |= BTA_AR_AV_MASK;
}

/*******************************************************************************
 *
 * Function         bta_ar_dereg_avct
 *
 * Description      This function is called to deregister from AVCTP.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_ar_dereg_avct() {
  bta_ar_cb.avct_registered &= ~BTA_AR_AV_MASK;

  if (bta_ar_cb.avct_registered == 0) {
    AVCT_Deregister();
  }
}

/******************************************************************************
 *
 * Function         bta_ar_reg_avrc
 *
 * Description      This function is called to register an SDP record for AVRCP.
 *
 * Returns          void
 *
 *****************************************************************************/
void bta_ar_reg_avrc(uint16_t service_uuid, const char* service_name, const char* provider_name,
                     uint16_t categories, bool browse_supported, uint16_t profile_version) {
  if (!categories) {
    return;
  }

  const std::shared_ptr<AvrcpSdpService>& avrcp_sdp_service = AvrcpSdpService::Get();
  AvrcpSdpRecord add_record_request = {service_uuid,
                                       service_name,
                                       provider_name,
                                       categories,
                                       browse_supported,
                                       profile_version,
                                       0};
  if (service_uuid == UUID_SERVCLASS_AV_REM_CTRL_TARGET) {
    avrcp_sdp_service->AddRecord(add_record_request, bta_ar_cb.sdp_tg_request_id);
    log::debug("Assigned target request id {}", bta_ar_cb.sdp_tg_request_id);
  } else if (service_uuid == UUID_SERVCLASS_AV_REMOTE_CONTROL ||
             service_uuid == UUID_SERVCLASS_AV_REM_CTRL_CONTROL) {
    avrcp_sdp_service->AddRecord(add_record_request, bta_ar_cb.sdp_ct_request_id);
    log::debug("Assigned control request id {}", bta_ar_cb.sdp_ct_request_id);
  }
}

/******************************************************************************
 *
 * Function         bta_ar_dereg_avrc
 *
 * Description      This function is called to de-register/delete an SDP record
 *                  for AVRCP.
 *
 * Returns          void
 *
 *****************************************************************************/
void bta_ar_dereg_avrc(uint16_t service_uuid) {
  log::verbose("Deregister AVRC 0x{:x}", service_uuid);

  const std::shared_ptr<AvrcpSdpService>& avrcp_sdp_service = AvrcpSdpService::Get();
  if (service_uuid == UUID_SERVCLASS_AV_REM_CTRL_TARGET &&
      bta_ar_cb.sdp_tg_request_id != UNASSIGNED_REQUEST_ID) {
    avrcp_sdp_service->RemoveRecord(UUID_SERVCLASS_AV_REM_CTRL_TARGET, bta_ar_cb.sdp_tg_request_id);
    bta_ar_cb.sdp_tg_request_id = UNASSIGNED_REQUEST_ID;
  } else if ((service_uuid == UUID_SERVCLASS_AV_REMOTE_CONTROL ||
              service_uuid == UUID_SERVCLASS_AV_REM_CTRL_CONTROL) &&
             bta_ar_cb.sdp_ct_request_id != UNASSIGNED_REQUEST_ID) {
    avrcp_sdp_service->RemoveRecord(UUID_SERVCLASS_AV_REMOTE_CONTROL, bta_ar_cb.sdp_ct_request_id);
    bta_ar_cb.sdp_ct_request_id = UNASSIGNED_REQUEST_ID;
  }
}
