/******************************************************************************
 *
 *  Copyright 2005-2012 Broadcom Corporation
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
 *  This is the interface file for hid host call-out functions.
 *
 ******************************************************************************/
#ifndef BTA_HH_CO_H
#define BTA_HH_CO_H

#include <bluetooth/types/acl_link_spec.h>
#include <bluetooth/types/address.h>
#include <linux/uhid.h>

#include <cstdint>

#include "bta/include/bta_hh_api.h"

typedef struct {
  uint16_t rpt_uuid;
  uint8_t rpt_id;
  tBTA_HH_RPT_TYPE rpt_type;
  uint8_t srvc_inst_id;
  uint16_t char_inst_id;
} tBTA_HH_RPT_CACHE_ENTRY;

typedef enum : uint8_t {
  BTA_HH_UHID_INBOUND_INPUT_EVT,
  BTA_HH_UHID_INBOUND_READY_EVT,
  BTA_HH_UHID_INBOUND_CLOSE_EVT,
  BTA_HH_UHID_INBOUND_DSCP_EVT,
  BTA_HH_UHID_INBOUND_GET_REPORT_EVT,
  BTA_HH_UHID_INBOUND_SET_REPORT_EVT,
} tBTA_HH_UHID_INBOUND_EVT_TYPE;

typedef struct {
  tBTA_HH_UHID_INBOUND_EVT_TYPE type;
  union {
    uhid_event uhid;
  };
} __attribute__((__packed__)) tBTA_HH_TO_UHID_EVT;

/*******************************************************************************
 *
 * Function         bta_hh_co_data
 *
 * Description      This callout function is executed by HH when data is
 *                  received
 *                  in interupt channel.
 *
 * Parameters       dev_handle  - device handle
 *                  *p_rpt      - pointer to the report data
 *                  len         - length of report data
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_data(uint8_t dev_handle, uint8_t* p_rpt, uint16_t len);

/*******************************************************************************
 *
 * Function         bta_hh_co_open
 *
 * Description      This callout function is executed by HH when connection is
 *                  opened, and application may do some device specific
 *                  initialization.
 *
 * Returns          True if platform specific initialization is successful
 *
 ******************************************************************************/
bool bta_hh_co_open(uint8_t dev_handle, uint8_t sub_class, uint16_t attr_mask, uint8_t app_id,
                    AclLinkSpec& link_spec);

/*******************************************************************************
 *
 * Function         bta_hh_co_set_rpt_rsp
 *
 * Description      This callout function is executed by HH when Set Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_set_rpt_rsp(uint8_t dev_handle, uint8_t status);

/*******************************************************************************
 *
 * Function         bta_hh_co_get_rpt_rsp
 *
 * Description      This callout function is executed by HH when Get Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_get_rpt_rsp(uint8_t dev_handle, uint8_t status, const uint8_t* p_rpt, uint16_t len);

/*******************************************************************************
 *
 * Function         bta_hh_le_co_rpt_info
 *
 * Description      This callout function is to convey the report information on
 *                  a HOGP device to the application. Application can save this
 *                  information in NV if device is bonded and load it back when
 *                  stack reboot.
 *
 * Parameters       link_spec   - acl link specification
 *                  p_entry     - report entry pointer
 *                  app_id      - application id
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_le_co_rpt_info(const AclLinkSpec& link_spec, tBTA_HH_RPT_CACHE_ENTRY* p_entry,
                           uint8_t app_id);

/*******************************************************************************
 *
 * Function         bta_hh_le_co_cache_load
 *
 * Description      This callout function is to request the application to load
 *                  the cached HOGP report if there is any. When cache reading
 *                  is completed, bta_hh_le_ci_cache_load() is called by the
 *                  application.
 *
 * Parameters       link_spec  - acl link specification
 *                  p_num_rpt: number of cached report
 *                  app_id      - application id
 *
 * Returns          the acched report array
 *
 ******************************************************************************/
tBTA_HH_RPT_CACHE_ENTRY* bta_hh_le_co_cache_load(const AclLinkSpec& link_spec, uint8_t* p_num_rpt,
                                                 uint8_t app_id);

/*******************************************************************************
 *
 * Function         bta_hh_le_co_reset_rpt_cache
 *
 * Description      This callout function is to reset the HOGP device cache.
 *
 * Parameters       link_spec  - acl link specification
 *
 * Returns          none
 *
 ******************************************************************************/
void bta_hh_le_co_reset_rpt_cache(const AclLinkSpec& link_spec, uint8_t app_id);

#endif /* BTA_HH_CO_H */
