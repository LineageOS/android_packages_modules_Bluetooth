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

#ifndef GAP_API_H
#define GAP_API_H

#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>

#include <cstdint>

#include "profiles_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/l2cap_types.h"

/*****************************************************************************
 *  Constants
 ****************************************************************************/
/*** GAP Error and Status Codes ***/
/* An illegal parameter was detected */
#define GAP_ERR_ILL_PARM (GAP_ERR_GRP + 0x09)

/* Bad GAP handle */
#define GAP_ERR_BAD_HANDLE (GAP_ERR_GRP + 0x0e)
/* Connection is in invalid state */
#define GAP_ERR_BAD_STATE (GAP_ERR_GRP + 0x10)
/* No data available */
#define GAP_NO_DATA_AVAIL (GAP_ERR_GRP + 0x11)
#define GAP_EVT_CONN_OPENED 0x0100
#define GAP_EVT_CONN_CLOSED 0x0101
#define GAP_EVT_CONN_DATA_AVAIL 0x0102
#define GAP_EVT_CONN_CONGESTED 0x0103
#define GAP_EVT_CONN_UNCONGESTED 0x0104
#define GAP_EVT_TX_EMPTY 0x0105

/*** used in connection variables and functions ***/
#define GAP_INVALID_HANDLE 0xFFFF

#ifndef GAP_PREFER_CONN_INT_MAX
#define GAP_PREFER_CONN_INT_MAX BTM_BLE_CONN_INT_MIN
#endif

#ifndef GAP_PREFER_CONN_INT_MIN
#define GAP_PREFER_CONN_INT_MIN BTM_BLE_CONN_INT_MIN
#endif

#ifndef GAP_PREFER_CONN_LATENCY
#define GAP_PREFER_CONN_LATENCY 0
#endif

#ifndef GAP_PREFER_CONN_SP_TOUT
#define GAP_PREFER_CONN_SP_TOUT 2000
#endif

struct tGAP_COC_CREDITS {
  uint16_t credits_received;
  uint16_t credit_count;
};

union tGAP_CB_DATA {
  tGAP_COC_CREDITS coc_credits;
  tL2CAP_CONN l2cap_result;
};

/*****************************************************************************
 *  Type Definitions
 ****************************************************************************/
/*
 * Callback function for connection services
 */
typedef void(tGAP_CONN_CALLBACK)(uint16_t gap_handle, uint16_t event, tGAP_CB_DATA* data);

typedef struct {
  uint16_t int_min;
  uint16_t int_max;
  uint16_t latency;
  uint16_t sp_tout;
} tGAP_BLE_PREF_PARAM;

typedef union {
  tGAP_BLE_PREF_PARAM conn_param;
  RawAddress reconn_bda;
  uint16_t icon;
  uint8_t* p_dev_name;
  uint8_t addr_resolution;
} tGAP_BLE_ATTR_VALUE;

typedef void(tGAP_BLE_CMPL_CBACK)(bool status, const RawAddress& addr, uint16_t length,
                                  char* p_name);

/*****************************************************************************
 *  External Function Declarations
 ****************************************************************************/

/*** Functions for L2CAP connection interface ***/

/*******************************************************************************
 *
 * Function         GAP_ConnOpen
 *
 * Description      This function is called to open a generic L2CAP connection.
 *
 * Returns          handle of the connection if successful, else
 *                  GAP_INVALID_HANDLE
 *
 ******************************************************************************/
uint16_t GAP_ConnOpen(const char* p_serv_name, uint8_t service_id, bool is_server,
                      const RawAddress* p_rem_bda, uint16_t psm, uint16_t le_mps,
                      tL2CAP_CFG_INFO* p_cfg, tL2CAP_ERTM_INFO* ertm_info, uint16_t security,
                      tGAP_CONN_CALLBACK* p_cb, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         GAP_ConnClose
 *
 * Description      This function is called to close a connection.
 *
 * Returns          BT_PASS             - closed OK
 *                  GAP_ERR_BAD_HANDLE  - invalid handle
 *
 ******************************************************************************/
uint16_t GAP_ConnClose(uint16_t gap_handle);

/*******************************************************************************
 *
 * Function         GAP_ConnReadData
 *
 * Description      GKI buffer unaware application will call this function
 *                  after receiving GAP_EVT_RXDATA event. A data copy is made
 *                  into the receive buffer parameter.
 *
 * Returns          BT_PASS             - data read
 *                  GAP_ERR_BAD_HANDLE  - invalid handle
 *                  GAP_NO_DATA_AVAIL   - no data available
 *
 ******************************************************************************/
uint16_t GAP_ConnReadData(uint16_t gap_handle, uint8_t* p_data, uint16_t max_len, uint16_t* p_len);

/*******************************************************************************
 *
 * Function         GAP_GetRxQueueCnt
 *
 * Description      This function return number of bytes on the rx queue.
 *
 * Parameters:      handle     - Handle returned in the GAP_ConnOpen
 *                  p_rx_queue_count - Pointer to return queue count in.
 *
 *
 ******************************************************************************/
int GAP_GetRxQueueCnt(uint16_t handle, uint32_t* p_rx_queue_count);

/*******************************************************************************
 *
 * Function         GAP_ConnWriteData
 *
 * Description      GKI buffer unaware application will call this function
 *                  to send data to the connection. A data copy is made into a
 *                  GKI buffer.
 *
 * Returns          BT_PASS                 - data read
 *                  GAP_ERR_BAD_HANDLE      - invalid handle
 *                  GAP_ERR_BAD_STATE       - connection not established
 *                  GAP_CONGESTION          - system is congested
 *
 ******************************************************************************/
uint16_t GAP_ConnWriteData(uint16_t gap_handle, BT_HDR* msg);

/*******************************************************************************
 *
 * Function         GAP_ConnGetRemoteAddr
 *
 * Description      This function is called to get the remote BD address
 *                  of a connection.
 *
 * Returns          BT_PASS             - closed OK
 *                  GAP_ERR_BAD_HANDLE  - invalid handle
 *
 ******************************************************************************/
const RawAddress* GAP_ConnGetRemoteAddr(uint16_t gap_handle);

/*******************************************************************************
 *
 * Function         GAP_ConnGetRemMtuSize
 *
 * Description      Returns the remote device's MTU size.
 *
 * Returns          uint16_t - maximum size buffer that can be transmitted to
 *                             the peer
 *
 ******************************************************************************/
uint16_t GAP_ConnGetRemMtuSize(uint16_t gap_handle);

/*******************************************************************************
 *
 * Function         GAP_ConnGetL2CAPCid
 *
 * Description      Returns the L2CAP channel id
 *
 * Parameters:      handle      - Handle of the connection
 *
 * Returns          uint16_t    - The L2CAP channel id
 *                  0, if error
 *
 ******************************************************************************/
uint16_t GAP_ConnGetL2CAPCid(uint16_t gap_handle);

/*******************************************************************************
 *
 * Function         GAP_GetLeChannelInfo
 *
 * Description      This function is called to get LE L2CAP channel information
 *                  by the gap handle. All OUT parameters must NOT be nullptr.
 *
 * Parameters:      handle        - Handle of the port returned in the Open
 *                  remote_mtu    - OUT remote L2CAP MTU
 *                  local_mps     - OUT local L2CAP COC MPS
 *                  remote_mps    - OUT remote L2CAP COC MPS
 *                  local_credit  - OUT local L2CAP COC credit
 *                  remote_credit - OUT remote L2CAP COC credit
 *                  local_cid     - OUT local L2CAP CID
 *                  remote_cid    - OUT remote L2CAP CID
 *                  acl_handle    - OUT ACL handle
 *
 * Returns          true if request accepted
 *
 ******************************************************************************/
bool GAP_GetLeChannelInfo(uint16_t gap_handle, uint16_t* remote_mtu, uint16_t* local_mps,
                          uint16_t* remote_mps, uint16_t* local_credit, uint16_t* remote_credit,
                          uint16_t* local_cid, uint16_t* remote_cid, uint16_t* acl_handle);

/*******************************************************************************
 *
 * Function         GAP_IsTransportLe
 *
 * Description      This function returns if the transport is LE by the gap handle.
 *
 * Parameters:      handle        - Handle of the port returned in the Open
 *
 * Returns          true if transport is LE, else false
 *
 ******************************************************************************/
bool GAP_IsTransportLe(uint16_t gap_handle);

/*******************************************************************************
 *
 * Function         GAP_Init
 *
 * Description      Initializes the control blocks used by GAP.
 *                  This routine should not be called except once per
 *                      stack invocation.
 *
 * Returns          Nothing
 *
 ******************************************************************************/
void GAP_Init(void);

/*******************************************************************************
 *
 * Function         GAP_BleAttrDBUpdate
 *
 * Description      update GAP local BLE attribute database.
 *
 * Returns          Nothing
 *
 ******************************************************************************/
void GAP_BleAttrDBUpdate(uint16_t attr_uuid, tGAP_BLE_ATTR_VALUE* p_value);

/*******************************************************************************
 *
 * Function         GAP_BleReadPeerPrefConnParams
 *
 * Description      Start a process to read a connected peripheral's preferred
 *                  connection parameters
 *
 * Returns          true if read started, else false if GAP is busy
 *
 ******************************************************************************/
bool GAP_BleReadPeerPrefConnParams(const RawAddress& peer_bda);

/*******************************************************************************
 *
 * Function         GAP_BleReadPeerDevName
 *
 * Description      Start a process to read a connected peripheral's device
 *                  name.
 *
 * Returns          true if request accepted
 *
 ******************************************************************************/
bool GAP_BleReadPeerDevName(const RawAddress& peer_bda, tGAP_BLE_CMPL_CBACK* p_cback);

/*******************************************************************************
 *
 * Function         GAP_BleReadPeerAppearance
 *
 * Description      Start a process to read a connected peripheral's appearance.
 *
 * Returns          true if request accepted
 *
 ******************************************************************************/
bool GAP_BleReadPeerAppearance(const RawAddress& peer_bda, tGAP_BLE_CMPL_CBACK* p_cback);

/*******************************************************************************
 *
 * Function         GAP_BleCancelReadPeerDevName
 *
 * Description      Cancel reading a peripheral's device name.
 *
 * Returns          true if request accepted
 *
 ******************************************************************************/
bool GAP_BleCancelReadPeerDevName(const RawAddress& peer_bda);

#endif /* GAP_API_H */
