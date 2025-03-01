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

/******************************************************************************
 *
 *  this file contains the PORT API definitions
 *
 ******************************************************************************/
#ifndef PORT_API_H
#define PORT_API_H

#include <hardware/bt_sock.h>

#include <cstdint>

#include "include/macros.h"
#include "internal_include/bt_target.h"
#include "types/raw_address.h"

/*****************************************************************************
 *  Constants and Types
 ****************************************************************************/

/*
 * Define port settings structure send from the application in the
 * set settings request, or to the application in the set settings indication.
 */
struct PortSettings {
#define PORT_BAUD_RATE_9600 0x03

  uint8_t baud_rate;

#define PORT_8_BITS 0x03

  uint8_t byte_size;

#define PORT_ONESTOPBIT 0x00
  uint8_t stop_bits;

#define PORT_PARITY_NO 0x00
  uint8_t parity;

#define PORT_ODD_PARITY 0x00

  uint8_t parity_type;

#define PORT_FC_OFF 0x00
#define PORT_FC_CTS_ON_INPUT 0x04
#define PORT_FC_CTS_ON_OUTPUT 0x08

  uint8_t fc_type;

  uint8_t rx_char1;

#define PORT_XON_DC1 0x11
  uint8_t xon_char;

#define PORT_XOFF_DC3 0x13
  uint8_t xoff_char;
};

/*
 * Define the callback function prototypes.  Parameters are specific
 * to each event and are described bellow
 */
typedef int(tPORT_DATA_CALLBACK)(uint16_t port_handle, void* p_data, uint16_t len);

#define DATA_CO_CALLBACK_TYPE_INCOMING 1
#define DATA_CO_CALLBACK_TYPE_OUTGOING_SIZE 2
#define DATA_CO_CALLBACK_TYPE_OUTGOING 3
typedef int(tPORT_DATA_CO_CALLBACK)(uint16_t port_handle, uint8_t* p_buf, uint16_t len, int type);

typedef void(tPORT_CALLBACK)(uint32_t code, uint16_t port_handle);

/*
 * Define events that registered application can receive in the callback
 */

#define PORT_EV_RXCHAR 0x00000001  /* Any Character received */
#define PORT_EV_RXFLAG 0x00000002  /* Received certain character */
#define PORT_EV_TXEMPTY 0x00000004 /* Transmitt Queue Empty */
#define PORT_EV_CTS 0x00000008     /* CTS changed state */
#define PORT_EV_DSR 0x00000010     /* DSR changed state */
#define PORT_EV_RLSD 0x00000020    /* RLSD changed state */
#define PORT_EV_BREAK 0x00000040   /* BREAK received */
#define PORT_EV_ERR 0x00000080     /* Line status error occurred */
#define PORT_EV_RING 0x00000100    /* Ring signal detected */
#define PORT_EV_CTSS 0x00000400    /* CTS state */
#define PORT_EV_DSRS 0x00000800    /* DSR state */
#define PORT_EV_RLSDS 0x00001000   /* RLSD state */
#define PORT_EV_OVERRUN 0x00002000 /* receiver buffer overrun */
#define PORT_EV_TXCHAR 0x00004000  /* Any character transmitted */

/* RFCOMM connection established */
#define PORT_EV_CONNECTED 0x00000200
/* Unable to establish connection  or disconnected */
#define PORT_EV_CONNECT_ERR 0x00008000
/* data flow enabled flag changed by remote */
#define PORT_EV_FC 0x00010000
/* data flow enable status true = enabled */
#define PORT_EV_FCS 0x00020000

/*
 * Define port result codes
 */
typedef enum {
  PORT_SUCCESS = 0,
  PORT_UNKNOWN_ERROR = 1,
  PORT_ALREADY_OPENED = 2,
  PORT_CMD_PENDING = 3,
  PORT_APP_NOT_REGISTERED = 4,
  PORT_NO_MEM = 5,
  PORT_NO_RESOURCES = 6,
  PORT_BAD_BD_ADDR = 7,
  PORT_BAD_HANDLE = 9,
  PORT_NOT_OPENED = 10,
  PORT_LINE_ERR = 11,
  PORT_START_FAILED = 12,
  PORT_PAR_NEG_FAILED = 13,
  PORT_PORT_NEG_FAILED = 14,
  PORT_SEC_FAILED = 15,
  PORT_PEER_CONNECTION_FAILED = 16,
  PORT_PEER_FAILED = 17,
  PORT_PEER_TIMEOUT = 18,
  PORT_CLOSED = 19,
  PORT_TX_FULL = 20,
  PORT_LOCAL_CLOSED = 21,
  PORT_LOCAL_TIMEOUT = 22,
  PORT_TX_QUEUE_DISABLED = 23,
  PORT_PAGE_TIMEOUT = 24,
  PORT_INVALID_SCN = 25,
  PORT_ERR_MAX = 26,
} tPORT_RESULT;

inline std::string port_result_text(const tPORT_RESULT& result) {
  switch (result) {
    CASE_RETURN_STRING(PORT_SUCCESS);
    CASE_RETURN_STRING(PORT_UNKNOWN_ERROR);
    CASE_RETURN_STRING(PORT_ALREADY_OPENED);
    CASE_RETURN_STRING(PORT_CMD_PENDING);
    CASE_RETURN_STRING(PORT_APP_NOT_REGISTERED);
    CASE_RETURN_STRING(PORT_NO_MEM);
    CASE_RETURN_STRING(PORT_NO_RESOURCES);
    CASE_RETURN_STRING(PORT_BAD_BD_ADDR);
    CASE_RETURN_STRING(PORT_BAD_HANDLE);
    CASE_RETURN_STRING(PORT_NOT_OPENED);
    CASE_RETURN_STRING(PORT_LINE_ERR);
    CASE_RETURN_STRING(PORT_START_FAILED);
    CASE_RETURN_STRING(PORT_PAR_NEG_FAILED);
    CASE_RETURN_STRING(PORT_PORT_NEG_FAILED);
    CASE_RETURN_STRING(PORT_SEC_FAILED);
    CASE_RETURN_STRING(PORT_PEER_CONNECTION_FAILED);
    CASE_RETURN_STRING(PORT_PEER_FAILED);
    CASE_RETURN_STRING(PORT_PEER_TIMEOUT);
    CASE_RETURN_STRING(PORT_CLOSED);
    CASE_RETURN_STRING(PORT_TX_FULL);
    CASE_RETURN_STRING(PORT_LOCAL_CLOSED);
    CASE_RETURN_STRING(PORT_LOCAL_TIMEOUT);
    CASE_RETURN_STRING(PORT_TX_QUEUE_DISABLED);
    CASE_RETURN_STRING(PORT_PAGE_TIMEOUT);
    CASE_RETURN_STRING(PORT_INVALID_SCN);
    CASE_RETURN_STRING(PORT_ERR_MAX);
    default:
      break;
  }
  RETURN_UNKNOWN_TYPE_STRING(tPORT_RESULT, result);
}

/* Define a structure to hold the configuration parameters. Since the
 * parameters are optional, for each parameter there is a boolean to
 * use to signify its presence or absence.
 */
struct RfcommCfgInfo {
  bool init_credit_present;
  uint16_t init_credit;
  bool rx_mtu_present;
  uint16_t rx_mtu;
  btsock_data_path_t data_path{BTSOCK_DATA_PATH_NO_OFFLOAD};
};

namespace std {
template <>
struct formatter<tPORT_RESULT> : enum_formatter<tPORT_RESULT> {};
}  // namespace std

typedef void(tPORT_MGMT_CALLBACK)(const tPORT_RESULT code, uint16_t port_handle);

/*****************************************************************************
 *  External Function Declarations
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         RFCOMM_CreateConnectionWithSecurity
 *
 * Description      RFCOMM_CreateConnectionWithSecurity is used from the application to
 *                  establish a serial port connection to the peer device,
 *                  or allow RFCOMM to accept a connection from the peer
 *                  application.
 *
 * Parameters:      scn          - Service Channel Number as registered with
 *                                 the SDP (server) or obtained using SDP from
 *                                 the peer device (client).
 *                  is_server    - true if requesting application is a server
 *                  mtu          - Maximum frame size the application can accept
 *                  bd_addr      - address of the peer (client)
 *                  mask         - specifies events to be enabled.  A value
 *                                 of zero disables all events.
 *                  p_handle     - OUT pointer to the handle.
 *                  p_mgmt_callback - pointer to callback function to receive
 *                                 connection up/down events.
 *                  sec_mask     - bitmask of BTM_SEC_* values indicating the
 *                                 minimum security requirements for this
 *                  cfg          - optional configurations for the connection
 * Notes:
 *
 * Server can call this function with the same scn parameter multiple times if
 * it is ready to accept multiple simulteneous connections.
 *
 * DLCI for the connection is (scn * 2 + 1) if client originates connection on
 * existing none initiator multiplexer channel.  Otherwise it is (scn * 2).
 * For the server DLCI can be changed later if client will be calling it using
 * (scn * 2 + 1) dlci.
 *
 ******************************************************************************/
[[nodiscard]] int RFCOMM_CreateConnectionWithSecurity(uint16_t uuid, uint8_t scn, bool is_server,
                                                      uint16_t mtu, const RawAddress& bd_addr,
                                                      uint16_t* p_handle,
                                                      tPORT_MGMT_CALLBACK* p_mgmt_callback,
                                                      uint16_t sec_mask, RfcommCfgInfo cfg);

/*******************************************************************************
 *
 * Function         RFCOMM_ControlReqFromBTSOCK
 *
 * Description      Send control parameters to the peer.
 *                  So far only for qualification use.
 *                  RFCOMM layer starts the control request only when it is the
 *                  client. This API allows the host to start the control
 *                  request while it works as a RFCOMM server.
 *
 * Parameters:      dlci             - the DLCI to send the MSC command
 *                  bd_addr          - bd_addr of the peer
 *                  modem_signal     - [DTR/DSR | RTS/CTS | RI | DCD]
 *                  break_signal     - 0-3 s in steps of 200 ms
 *                  discard_buffers  - 0 for do not discard, 1 for discard
 *                  break_signal_seq - ASAP or in sequence
 *                  fc               - true when the device is unable to accept
 *                                     frames
 *
 ******************************************************************************/
[[nodiscard]] int RFCOMM_ControlReqFromBTSOCK(uint8_t dlci, const RawAddress& bd_addr,
                                              uint8_t modem_signal, uint8_t break_signal,
                                              uint8_t discard_buffers, uint8_t break_signal_seq,
                                              bool fc);

/*******************************************************************************
 *
 * Function         RFCOMM_RemoveConnection
 *
 * Description      This function is called to close the specified connection.
 *
 * Parameters:      handle     - Handle of the port returned in the Open
 *
 ******************************************************************************/
[[nodiscard]] int RFCOMM_RemoveConnection(uint16_t handle);

/*******************************************************************************
 *
 * Function         RFCOMM_RemoveServer
 *
 * Description      This function is called to close the server port.
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *
 ******************************************************************************/
[[nodiscard]] int RFCOMM_RemoveServer(uint16_t handle);

/*******************************************************************************
 *
 * Function         PORT_SetEventMaskAndCallback
 *
 * Description      Set event callback the specified connection.
 *
 * Parameters:      handle       - Handle of the port returned in the Open
 *                  mask         - specifies events to be enabled.  A value
 *                                 of zero disables all events.
 *                  p_callback   - address of the callback function which should
 *                                 be called from the RFCOMM when an event
 *                                 specified in the mask occurs.
 *
 ******************************************************************************/
[[nodiscard]] int PORT_SetEventMaskAndCallback(uint16_t port_handle, uint32_t mask,
                                               tPORT_CALLBACK* p_port_cb);

/*******************************************************************************
 *
 * Function         PORT_ClearKeepHandleFlag
 *
 * Description      Called to clear the keep handle flag, which will cause
 *                  not to keep the port handle open when closed
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *
 ******************************************************************************/
[[nodiscard]] int PORT_ClearKeepHandleFlag(uint16_t port_handle);

[[nodiscard]] int PORT_SetDataCOCallback(uint16_t port_handle, tPORT_DATA_CO_CALLBACK* p_port_cb);
/*******************************************************************************
 *
 * Function         PORT_CheckConnection
 *
 * Description      This function returns PORT_SUCCESS if connection referenced
 *                  by handle is up and running
 *
 * Parameters:      handle     - Handle of the port returned in the Open
 *                  bd_addr    - OUT bd_addr of the peer
 *                  p_lcid     - OUT L2CAP's LCID
 *
 ******************************************************************************/
[[nodiscard]] int PORT_CheckConnection(uint16_t handle, RawAddress* bd_addr, uint16_t* p_lcid);

/*******************************************************************************
 *
 * Function         PORT_IsCollisionDetected
 *
 * Description      This function returns true if there is already an incoming
 *                  RFCOMM connection in progress for this device
 *
 * Parameters:      true if any connection opening is found
 *                  bd_addr    - bd_addr of the peer
 *
 ******************************************************************************/
[[nodiscard]] bool PORT_IsCollisionDetected(RawAddress bd_addr);

/*******************************************************************************
 *
 * Function         PORT_SetAppUid
 *
 * Description      This function sets app_uid in port structure
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                  app_uid    - Uid of app that requested the socket
 *
 ******************************************************************************/
[[nodiscard]] int PORT_SetAppUid(uint16_t handle, uint32_t app_uid);

/*******************************************************************************
 *
 * Function         PORT_SetSdpDuration
 *
 * Description      This function saves calculated sdp duration (in
 *                  milliseconds) to port structure
 *
 * Parameters:      handle          - Handle returned in RFCOMM_CreateConnection
 *                  sdp_duration_ms - Time spent doing sdp
 *
 ******************************************************************************/
[[nodiscard]] int PORT_SetSdpDuration(uint16_t handle, uint64_t sdp_duration_ms);

/*******************************************************************************
 *
 * Function         PORT_SetState
 *
 * Description      This function configures connection according to the
 *                  specifications in the PortSettings structure.
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                  p_settings - Pointer to a PortSettings structure containing
 *                               configuration information for the connection.
 *
 ******************************************************************************/
[[nodiscard]] int PORT_SetSettings(uint16_t handle, PortSettings* p_settings);

/*******************************************************************************
 *
 * Function         PORT_GetSettings
 *
 * Description      This function is called to fill PortSettings structure
 *                  with the current control settings for the port
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                  p_settings - Pointer to a PortSettings structure in which
 *                               configuration information is returned.
 *
 ******************************************************************************/
[[nodiscard]] int PORT_GetSettings(uint16_t handle, PortSettings* p_settings);

/*******************************************************************************
 *
 * Function         PORT_FlowControl_MaxCredit
 *
 * Description      This function directs a specified connection to pass
 *                  flow control message to the peer device.  Enable flag passed
 *                  shows if port can accept more data. It also sends max credit
 *                  when data flow enabled
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                  enable     - enables data flow
 *
 ******************************************************************************/
[[nodiscard]] int PORT_FlowControl_MaxCredit(uint16_t handle, bool enable);

#define PORT_DTRDSR_ON 0x01
#define PORT_CTSRTS_ON 0x02
#define PORT_RING_ON 0x04
#define PORT_DCD_ON 0x08

/*
 * Define default initial local modem signals state after connection established
 */
#define PORT_OBEX_DEFAULT_SIGNAL_STATE (PORT_DTRDSR_ON | PORT_CTSRTS_ON | PORT_DCD_ON)
#define PORT_SPP_DEFAULT_SIGNAL_STATE (PORT_DTRDSR_ON | PORT_CTSRTS_ON | PORT_DCD_ON)
#define PORT_PPP_DEFAULT_SIGNAL_STATE (PORT_DTRDSR_ON | PORT_CTSRTS_ON | PORT_DCD_ON)
#define PORT_DUN_DEFAULT_SIGNAL_STATE (PORT_DTRDSR_ON | PORT_CTSRTS_ON)

#define PORT_ERR_BREAK 0x01   /* Break condition occurred on the peer device */
#define PORT_ERR_OVERRUN 0x02 /* Overrun is reported by peer device */
#define PORT_ERR_FRAME 0x04   /* Framing error reported by peer device */
#define PORT_ERR_RXOVER 0x08  /* Input queue overflow occurred */
#define PORT_ERR_TXFULL 0x10  /* Output queue overflow occurred */

/*******************************************************************************
 *
 * Function         PORT_ReadData
 *
 * Description      Normally application will call this function after receiving
 *                  PORT_EVT_RXCHAR event.
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                                callback.
 *                  p_data      - Data area
 *                  max_len     - Byte count requested
 *                  p_len       - Byte count received
 *
 ******************************************************************************/
[[nodiscard]] int PORT_ReadData(uint16_t handle, char* p_data, uint16_t max_len, uint16_t* p_len);

/*******************************************************************************
 *
 * Function         PORT_WriteData
 *
 * Description      This function is called from the legacy application to
 *                  send data.
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *                  p_data      - Data area
 *                  max_len     - Byte count to write
 *                  p_len       - Bytes written
 *
 ******************************************************************************/
[[nodiscard]] int PORT_WriteData(uint16_t handle, const char* p_data, uint16_t max_len,
                                 uint16_t* p_len);

/*******************************************************************************
 *
 * Function         PORT_WriteDataCO
 *
 * Description      Normally not GKI aware application will call this function
 *                  to send data to the port by callout functions.
 *
 * Parameters:      handle     - Handle returned in the RFCOMM_CreateConnection
 *
 ******************************************************************************/
[[nodiscard]] int PORT_WriteDataCO(uint16_t handle, int* p_len);

/*******************************************************************************
 *
 * Function         RFCOMM_Init
 *
 * Description      This function is called to initialize RFCOMM layer
 *
 ******************************************************************************/
void RFCOMM_Init(void);

/*******************************************************************************
 *
 * Function         PORT_GetResultString
 *
 * Description      This function returns the human-readable string for a given
 *                  result code.
 *
 * Returns          a pointer to the human-readable string for the given
 *                  result. Note that the string returned must not be freed.
 *
 ******************************************************************************/
[[nodiscard]] const char* PORT_GetResultString(const uint8_t result_code);

/*******************************************************************************
 *
 * Function         PORT_GetSecurityMask
 *
 * Description      This function returns the security bitmask for a port.
 *
 * Returns          the security bitmask.
 *
 ******************************************************************************/
[[nodiscard]] int PORT_GetSecurityMask(uint16_t handle, uint16_t* sec_mask);

/*******************************************************************************
 *
 * Function         PORT_GetChannelInfo
 *
 * Description      This function is called to get RFCOMM channel information
 *                  by the handle of the port. All OUT parameters must NOT be nullptr.
 *
 * Parameters:      handle        - Handle of the port returned in the Open
 *                  local_mtu     - OUT local L2CAP MTU
 *                  remote_mtu    - OUT remote L2CAP MTU
 *                  local_credit  - OUT local RFCOMM credit
 *                  remote_credit - OUT remote RFCOMM credit
 *                  local_cid     - OUT local L2CAP CID
 *                  remote_cid    - OUT remote L2CAP CID
 *                  dlci          - OUT dlci
 *                  max_frame_size- OUT max frame size for RFCOMM
 *                  acl_handle    - OUT ACL handle
 *                  mux_initiator - OUT is initiator of the RFCOMM multiplexer control channel
 *
 ******************************************************************************/
[[nodiscard]] int PORT_GetChannelInfo(uint16_t handle, uint16_t* local_mtu, uint16_t* remote_mtu,
                                      uint16_t* local_credit, uint16_t* remote_credit,
                                      uint16_t* local_cid, uint16_t* remote_cid, uint16_t* dlci,
                                      uint16_t* max_frame_size, uint16_t* acl_handle,
                                      bool* mux_initiator);

#endif /* PORT_API_H */
