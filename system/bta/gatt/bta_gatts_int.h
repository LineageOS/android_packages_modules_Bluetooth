/******************************************************************************
 *
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
 *  This is the private file for the BTA GATT server.
 *
 ******************************************************************************/
#ifndef BTA_GATTS_INT_H
#define BTA_GATTS_INT_H

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include <cstdint>

#include "bta/include/bta_gatt_api.h"
#include "bta/sys/bta_sys.h"
#include "hardware/bt_gatt_types.h"
#include "internal_include/bt_target.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/gatt_api.h"

/*****************************************************************************
 *  Constants and data types
 ****************************************************************************/

/* max number of application allowed on device */
#define BTA_GATTS_MAX_APP_NUM GATT_MAX_SR_PROFILES

/* max number of services allowed in the device */
#define BTA_GATTS_MAX_SRVC_NUM GATT_MAX_SR_PROFILES

/* application registration control block */
typedef struct {
  bool in_use;
  bluetooth::Uuid app_uuid;
  const tBTA_GATTS_CBACK* p_cback;
  tGATT_IF gatt_if;
} tBTA_GATTS_RCB;

/* service registration control block */
typedef struct {
  bluetooth::Uuid service_uuid; /* service UUID */
  uint16_t service_id;          /* service start handle */
  uint8_t rcb_idx;
  uint8_t idx; /* self index of serviec CB */
  bool in_use;
} tBTA_GATTS_SRVC_CB;

/* GATT server control block */
typedef struct {
  bool enabled;
  tBTA_GATTS_RCB rcb[BTA_GATTS_MAX_APP_NUM];
  tBTA_GATTS_SRVC_CB srvc_cb[BTA_GATTS_MAX_SRVC_NUM];
} tBTA_GATTS_CB;

/*****************************************************************************
 *  Global data
 ****************************************************************************/

/* GATTC control block */
extern tBTA_GATTS_CB bta_gatts_cb;

/*****************************************************************************
 *  Function prototypes
 ****************************************************************************/
void bta_gatts_api_disable();
void bta_gatts_register(const bluetooth::Uuid& app_uuid, const tBTA_GATTS_CBACK* p_cback,
                        bool eatt_support);
void bta_gatts_start_if(tGATT_IF server_if);
void bta_gatts_deregister(tGATT_IF server_if);
void bta_gatts_delete_service(uint16_t service_id);

void bta_gatts_send_rsp(uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
                        std::unique_ptr<tGATTS_RSP> rsp);
void bta_gatts_indicate_handle(uint16_t conn_id, uint16_t attr_id, std::vector<uint8_t> value,
                               bool need_confirm);

void bta_gatts_open(tGATT_IF server_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    bool is_direct, tBT_TRANSPORT transport);
void bta_gatts_cancel_open(tGATT_IF server_if, const RawAddress& remote_bda, bool is_direct);
void bta_gatts_close(uint16_t conn_id);

tBTA_GATTS_RCB* bta_gatts_find_app_rcb_by_app_if(tGATT_IF server_if);
uint8_t bta_gatts_find_app_rcb_idx_by_app_if(tBTA_GATTS_CB* p_cb, tGATT_IF server_if);
uint8_t bta_gatts_alloc_srvc_cb(tBTA_GATTS_CB* p_cb, uint8_t rcb_idx);
tBTA_GATTS_SRVC_CB* bta_gatts_find_srvc_cb_by_srvc_id(tBTA_GATTS_CB* p_cb, uint16_t service_id);
tBTA_GATTS_SRVC_CB* bta_gatts_find_srvc_cb_by_attr_id(tBTA_GATTS_CB* p_cb, uint16_t attr_id);

#endif /* BTA_GATTS_INT_H */
