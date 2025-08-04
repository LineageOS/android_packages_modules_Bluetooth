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
 *  This is the interface file for pan call-in functions.
 *
 ******************************************************************************/
#ifndef BTA_PAN_CI_H
#define BTA_PAN_CI_H

#include <bluetooth/types/address.h>

#include <cstdint>

#include "bta/include/bta_pan_api.h"
#include "stack/include/bt_hdr.h"

/*****************************************************************************
 *  Function Declarations
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         bta_pan_ci_readbuf
 *
 * Description      This function is called by the phone to read data from PAN
 *                  when the TX path is configured to use a pull interface.
 *                  The caller must free the buffer when it is through
 *                  processing the buffer.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
BT_HDR* bta_pan_ci_readbuf(uint16_t handle, RawAddress& src, RawAddress& dst, uint16_t* p_protocol,
                           bool* p_ext, bool* p_forward);

#endif /* BTA_PAN_CI_H */
