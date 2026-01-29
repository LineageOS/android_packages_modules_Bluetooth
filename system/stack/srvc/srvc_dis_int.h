/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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

#ifndef SRVC_DIS_INT_H
#define SRVC_DIS_INT_H

#include <queue>

#include "internal_include/bt_target.h"
#include "srvc_eng_int.h"
#include "stack/include/gatt_api.h"
#include "stack/include/srvc_api.h"

#define DIS_SYSTEM_ID_SIZE 8
#define DIS_PNP_ID_SIZE 7

typedef struct {
  tDIS_READ_CBACK* p_read_dis_cback;
  tDIS_ATTR_MASK mask;
  RawAddress addr;
} tDIS_REQ;

typedef struct {
  tDIS_READ_CBACK* p_read_dis_cback;
  uint8_t dis_read_uuid_idx;
  tDIS_ATTR_MASK request_mask;
  std::queue<tDIS_REQ> pend_reqs;
} tDIS_CB;

/* Global GATT data */
extern tDIS_CB dis_cb;

void dis_c_cmpl_cback(tSRVC_CLCB* p_clcb, tGATTC_OPTYPE op, tGATT_STATUS status,
                      tGATT_CL_COMPLETE* p_data);

#endif
