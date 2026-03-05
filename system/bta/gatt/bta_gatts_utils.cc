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
 *  This file contains the GATT client utility function.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include <cstdint>

#include "bta/gatt/bta_gatts_int.h"
#include "internal_include/bt_target.h"

using namespace bluetooth;

/*******************************************************************************
 *
 * Function         bta_gatts_find_app_rcb_by_app_if
 *
 * Description      find the index of the application control block by app ID.
 *
 * Returns          pointer to the control block if success, otherwise NULL
 *
 ******************************************************************************/
tBTA_GATTS_RCB* bta_gatts_find_app_rcb_by_app_if(tGATT_IF server_if) {
  uint8_t i;
  tBTA_GATTS_RCB* p_reg;

  for (i = 0, p_reg = bta_gatts_cb.rcb; i < BTA_GATTS_MAX_APP_NUM; i++, p_reg++) {
    if (p_reg->in_use && p_reg->gatt_if == server_if) {
      return p_reg;
    }
  }
  return NULL;
}
