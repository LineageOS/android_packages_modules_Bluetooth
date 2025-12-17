/******************************************************************************
 *
 *  Copyright 2002-2012 Broadcom Corporation
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
 *  This file contains HID HOST internal definitions
 *
 ******************************************************************************/

#ifndef HIDH_INT_H
#define HIDH_INT_H

#include <bluetooth/types/address.h>

#include <cstdint>

#include "internal_include/bt_target.h"
#include "stack/hid/hid_conn.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/hidh_api.h"
#include "stack/include/l2cap_types.h"

typedef enum {
  HIDH_DEV_UNUSED,
  HIDH_DEV_NO_CONN,
  HIDH_DEV_CONNECTED,
  HIDH_DEV_REMOVING,
} tHIDH_DEV_STATE;

typedef struct per_device_ctb {
  // BD-Addr of the host device
  RawAddress addr;
  // 0x01- virtual_cable; 0x02- normally_connectable; 0x03- reconn_initiate; 0x04- sdp_disable;
  uint16_t attr_mask;
  // Device state
  tHIDH_DEV_STATE state;
  // Remembers the number of connection attempts while CONNECTING
  uint8_t conn_tries;
  // L2CAP channel info
  tHID_CONN conn;
} tHID_HOST_DEV_CTB;

typedef struct host_ctb {
  tHID_HOST_DEV_CTB devices[HID_HOST_MAX_DEVICES];
  tHID_HOST_DEV_CALLBACK* callback; /* Application callbacks */
  tL2CAP_CFG_INFO l2cap_cfg;

#define MAX_SERVICE_DB_SIZE 4000

  bool sdp_busy;
  tHID_HOST_SDP_CALLBACK* sdp_cback;
  tSDP_DISCOVERY_DB* p_sdp_db;
  tHID_DEV_SDP_INFO sdp_rec;
  bool reg_flag;
} tHID_HOST_CTB;

tHID_STATUS hidh_conn_snd_data(uint8_t dhandle, uint8_t trans_type, uint8_t param, uint16_t data,
                               uint8_t rpt_id, BT_HDR* buf);
tHID_STATUS hidh_conn_reg(void);
void hidh_conn_dereg(void);
void hidh_conn_disconnect(uint8_t dhandle);
void hidh_conn_force_disconnect(uint8_t dhandle);
tHID_STATUS hidh_conn_initiate(uint8_t dhandle);
void hidh_dump(int fd);
bool hidh_in_use(const per_device_ctb& ctb);
void hidh_conn_reset(uint8_t dhandle);

/******************************************************************************
 * Main Control Block
 ******************************************************************************/
extern tHID_HOST_CTB hh_cb;

#endif
