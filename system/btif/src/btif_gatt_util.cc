/******************************************************************************
 *
 *  Copyright 2009-2013 Broadcom Corporation
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

#include "btif_gatt_util.h"

#include <algorithm>
#include <cstring>

/*******************************************************************************
 * BTIF -> BTA conversion functions
 ******************************************************************************/
void btif_to_bta_response(tGATTS_RSP* p_dest, btgatt_response_t* p_src) {
  p_dest->attr_value.auth_req = p_src->attr_value.auth_req;
  p_dest->attr_value.handle = p_src->attr_value.handle;
  p_dest->attr_value.len = std::min<uint16_t>(p_src->attr_value.len, GATT_MAX_ATTR_LEN);
  p_dest->attr_value.offset = p_src->attr_value.offset;
  memcpy(p_dest->attr_value.value, p_src->attr_value.value, p_dest->attr_value.len);
}

