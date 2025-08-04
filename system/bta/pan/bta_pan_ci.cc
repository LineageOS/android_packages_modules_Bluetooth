/******************************************************************************
 *
 *  Copyright 2004-2012 Broadcom Corporation
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
 *  This is the implementation file for data gateway call-in functions.
 *
 ******************************************************************************/

#include "bta/include/bta_pan_ci.h"

#include <bluetooth/types/address.h>

#include <cstddef>
#include <cstdint>

#include "bta/pan/bta_pan_int.h"
#include "osi/include/fixed_queue.h"
#include "stack/include/bt_hdr.h"

/*******************************************************************************
 *
 * Function         bta_pan_ci_readbuf
 *
 * Description
 *
 *
 * Returns          void
 *
 ******************************************************************************/
BT_HDR* bta_pan_ci_readbuf(uint16_t handle, RawAddress& src, RawAddress& dst, uint16_t* p_protocol,
                           bool* p_ext, bool* p_forward) {
  tBTA_PAN_SCB* p_scb = bta_pan_scb_by_handle(handle);
  BT_HDR* p_buf;

  if (p_scb == NULL) {
    return NULL;
  }

  p_buf = (BT_HDR*)fixed_queue_try_dequeue(p_scb->data_queue);
  if (p_buf != NULL) {
    src = ((tBTA_PAN_DATA_PARAMS*)p_buf)->src;
    dst = ((tBTA_PAN_DATA_PARAMS*)p_buf)->dst;
    *p_protocol = ((tBTA_PAN_DATA_PARAMS*)p_buf)->protocol;
    *p_ext = ((tBTA_PAN_DATA_PARAMS*)p_buf)->ext;
    *p_forward = ((tBTA_PAN_DATA_PARAMS*)p_buf)->forward;
  }

  return p_buf;
}
