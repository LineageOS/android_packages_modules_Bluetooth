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

/*****************************************************************************
 *
 *  This file contains definitions internal to the PORT unit
 *
 *****************************************************************************/

#ifndef PORT_INT_H
#define PORT_INT_H

#include <cstdint>

#include "include/macros.h"
#include "internal_include/bt_target.h"
#include "osi/include/alarm.h"
#include "osi/include/fixed_queue.h"
#include "stack/include/l2cap_types.h"
#include "stack/include/port_api.h"
#include "stack/include/rfcdefs.h"
#include "stack/rfcomm/rfc_event.h"
#include "stack/rfcomm/rfc_state.h"
#include "types/raw_address.h"

/*
 * Flow control configuration values for the mux
 */
#define PORT_FC_UNDEFINED 0 /* mux flow control mechanism not defined yet */
#define PORT_FC_TS710 1     /* use TS 07.10 flow control  */
#define PORT_FC_CREDIT 2    /* use RFCOMM credit based flow control */

/*
 * Define Port Data Transfer control block
 */
typedef struct {
  fixed_queue_t* queue;       /* Queue of buffers waiting to be sent */
  bool peer_fc;               /* true if flow control is set based on peer's request */
  bool user_fc;               /* true if flow control is set based on user's request  */
  uint32_t queue_size;        /* Number of data bytes in the queue */
  tPORT_CALLBACK* p_callback; /* Address of the callback function */
} tPORT_DATA;

/*
 * Port control structure used to pass modem info
 */
typedef struct {
#define MODEM_SIGNAL_DTRDSR 0x01
#define MODEM_SIGNAL_RTSCTS 0x02
#define MODEM_SIGNAL_RI 0x04
#define MODEM_SIGNAL_DCD 0x08

  uint8_t modem_signal; /* [DTR/DSR | RTS/CTS | RI | DCD ] */

  uint8_t break_signal; /* 0-3 s in steps of 200 ms */

  uint8_t discard_buffers; /* 0 - do not discard, 1 - discard */

#define RFCOMM_CTRL_BREAK_ASAP 0
#define RFCOMM_CTRL_BREAK_IN_SEQ 1

  uint8_t break_signal_seq; /* as soon as possible | in sequence (default) */

  bool fc; /* true when the device is unable to accept frames */
} tPORT_CTRL;

/*
 * RFCOMM multiplexer Control Block
 */
typedef struct {
  alarm_t* mcb_timer = nullptr;              /* MCB timer */
  fixed_queue_t* cmd_q = nullptr;            /* Queue for command messages on this mux */
  uint8_t port_handles[RFCOMM_MAX_DLCI + 1]; /* Array for quick access to  */
  /* port handles based on dlci        */
  RawAddress bd_addr = RawAddress::kEmpty; /* BD ADDR of the peer if initiator */
  uint16_t lcid;                           /* Local cid used for this channel */
  uint16_t peer_l2cap_mtu;                 /* Max frame that can be sent to peer L2CAP */
  tRFC_MX_STATE state;                     /* Current multiplexer channel state */
  uint8_t is_initiator;                    /* true if this side sends SABME (dlci=0) */
  bool restart_required;                   /* true if has to restart channel after disc */
  bool peer_ready;                         /* True if other side can accept frames */
  uint8_t flow;                            /* flow control mechanism for this mux */
  bool l2cap_congested;                    /* true if L2CAP is congested */
  bool is_disc_initiator;                  /* true if initiated disc of port */
  uint16_t pending_lcid;                   /* store LCID for incoming connection while connecting */
  bool pending_configure_complete;         /* true if confiquration of the pending
                                              connection was completed*/
  tL2CAP_CFG_INFO pending_cfg_info = {};   /* store configure info for incoming
                                         connection while connecting */
} tRFC_MCB;

/*
 * RFCOMM Port State Machine Control Block
 */
struct RfcommPortSm {
  tRFC_PORT_STATE state;
  tRFC_PORT_STATE state_prior;
  tRFC_PORT_EVENT last_event;
  tPORT_RESULT close_reason;
  uint64_t open_timestamp;
  uint64_t close_timestamp;
};

/*
 * RFCOMM Port Connection Control Block
 */
typedef struct {
  RfcommPortSm sm_cb;  // State machine control block

#define RFC_RSP_PN 0x01
#define RFC_RSP_RPN_REPLY 0x02
#define RFC_RSP_RPN 0x04
#define RFC_RSP_MSC 0x08
#define RFC_RSP_RLS 0x10

  uint8_t expected_rsp;

  tRFC_MCB* p_mcb;

  alarm_t* port_timer;
} tRFC_PORT;

typedef enum : uint8_t {
  PORT_CONNECTION_STATE_CLOSED = 0,
  PORT_CONNECTION_STATE_OPENING = 1,
  PORT_CONNECTION_STATE_OPENED = 2,
  PORT_CONNECTION_STATE_CLOSING = 3,
} tPORT_CONNECTION_STATE;

inline std::string port_connection_state_text(const tPORT_CONNECTION_STATE& state) {
  switch (state) {
    CASE_RETURN_STRING(PORT_CONNECTION_STATE_CLOSED);
    CASE_RETURN_STRING(PORT_CONNECTION_STATE_OPENING);
    CASE_RETURN_STRING(PORT_CONNECTION_STATE_OPENED);
    CASE_RETURN_STRING(PORT_CONNECTION_STATE_CLOSING);
    default:
      break;
  }
  RETURN_UNKNOWN_TYPE_STRING(tPORT_CONNECTION_STATE, state);
}

namespace std {
template <>
struct formatter<tPORT_CONNECTION_STATE> : enum_formatter<tPORT_CONNECTION_STATE> {};
}  // namespace std

/*
 * Define control block containing information about PORT connection
 */
typedef struct {
  uint8_t handle;  // Starting from 1, unique for this object
  bool in_use;     /* True when structure is allocated */

  tPORT_CONNECTION_STATE state; /* State of the application */

  uint8_t scn;              /* Service channel number */
  uint16_t uuid;            /* Service UUID */
  uint32_t app_uid;         /* UID of the app for which this socket was created */
  uint64_t sdp_duration_ms; /* Time it took to perform SDP (for metrics) */

  RawAddress bd_addr; /* BD ADDR of the device for the multiplexer channel */
  bool is_server;     /* true if the server application */
  uint8_t dlci;       /* DLCI of the connection */

  uint8_t line_status; /* Line status as reported by peer */

  uint8_t default_signal_state; /* Initial signal state depending on uuid */

  uint16_t mtu;      /* Max MTU that port can receive */
  uint16_t peer_mtu; /* Max MTU that port can send */

  tPORT_DATA tx; /* Control block for data from app to peer */
  tPORT_DATA rx; /* Control block for data from peer to app */

  PortSettings user_port_settings; /* Port parameters for user connection */
  PortSettings peer_port_settings; /* Port parameters for peer connection */

  tPORT_CTRL local_ctrl;
  tPORT_CTRL peer_ctrl;

#define PORT_CTRL_REQ_SENT 0x01
#define PORT_CTRL_REQ_CONFIRMED 0x02
#define PORT_CTRL_IND_RECEIVED 0x04
#define PORT_CTRL_IND_RESPONDED 0x08
#define PORT_CTRL_SETUP_COMPLETED \
  (PORT_CTRL_REQ_SENT | PORT_CTRL_REQ_CONFIRMED | PORT_CTRL_IND_RECEIVED | PORT_CTRL_IND_RESPONDED)

  uint8_t port_ctrl; /* Modem Status Command  */

  bool rx_flag_ev_pending; /* RXFLAG Character is received */

  tRFC_PORT rfc; /* RFCOMM port control block */

  uint32_t ev_mask;                           /* Event mask for the callback */
  tPORT_CALLBACK* p_callback;                 /* Pointer to users callback function */
  tPORT_MGMT_CALLBACK* p_mgmt_callback;       /* Callback function to receive connection up/down */
  tPORT_DATA_CALLBACK* p_data_callback;       /* Callback function to receive data indications */
  tPORT_DATA_CO_CALLBACK* p_data_co_callback; /* Callback function with callouts and flowctrl */
  uint16_t credit_tx;                         /* Flow control credits for tx path */
  uint16_t credit_rx;                         /* Flow control credits for rx path, this is */
                                              /* number of buffers peer is allowed to sent */
  uint16_t credit_rx_max;   /* Max number of credits we will allow this guy to sent */
  uint16_t credit_rx_low;   /* Number of credits when we send credit update */
  uint16_t rx_buf_critical; /* port receive queue critical watermark level */
  bool keep_port_handle;    /* true if port is not deallocated when closing */
  /* it is set to true for server when allocating port */
  uint16_t keep_mtu; /* Max MTU that port can receive by server */
  uint16_t sec_mask; /* Bitmask of security requirements for this port */
                     /* see the BTM_SEC_* values in btm_api_types.h */
  RfcommCfgInfo rfc_cfg_info; /* store optional rfc configure info for incoming */
                              /* connection while connecting */
} tPORT;

/* Define the PORT/RFCOMM control structure
 */
typedef struct {
  tPORT port[MAX_RFC_PORTS];            /* Port info pool */
  tRFC_MCB rfc_mcb[MAX_BD_CONNECTIONS]; /* RFCOMM bd_connections pool */
} tPORT_CB;

/*
 * Functions provided by the port_utils.cc
 */
tPORT* port_allocate_port(uint8_t dlci, const RawAddress& bd_addr);
void port_set_defaults(tPORT* p_port);
void port_select_mtu(tPORT* p_port);
void port_release_port(tPORT* p_port);
tPORT* port_find_mcb_dlci_port(tRFC_MCB* p_mcb, uint8_t dlci);
tRFC_MCB* port_find_mcb(const RawAddress& bd_addr);
tPORT* port_find_dlci_port(uint8_t dlci);
tPORT* port_find_port(uint8_t dlci, const RawAddress& bd_addr);
uint32_t port_get_signal_changes(tPORT* p_port, uint8_t old_signals, uint8_t signal);
uint32_t port_flow_control_user(tPORT* p_port);
void port_flow_control_peer(tPORT* p_port, bool enable, uint16_t count);

/*
 * Functions provided by the port_rfc.cc
 */
int port_open_continue(tPORT* p_port);
void port_start_port_open(tPORT* p_port);
void port_start_par_neg(tPORT* p_port);
void port_start_control(tPORT* p_port);
void port_start_close(tPORT* p_port);
void port_rfc_closed(tPORT* p_port, uint8_t res);

#endif
