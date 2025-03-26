/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define LOG_TAG "rfc_metrics"

#include "../include/rfc_metrics.h"

#include <bluetooth/log.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/rfcomm/enums.pb.h>

#include "bta/include/bta_jv_api.h"
#include "common/time_util.h"
#include "main/shim/metrics_api.h"
#include "stack/btm/security_device_record.h"
#include "stack/include/btm_sec_api_types.h"
#include "stack/include/port_api.h"
#include "stack/rfcomm/port_int.h"
#include "stack/rfcomm/rfc_event.h"
#include "stack/rfcomm/rfc_state.h"
#include "types/raw_address.h"

using namespace bluetooth;

using namespace android::bluetooth;
using namespace android::bluetooth::rfcomm;

static SocketConnectionSecurity toSecurity(uint16_t sec_mask);
static PortResult toPortResult(tPORT_RESULT result);
static RfcommPortState toPortState(tRFC_PORT_STATE state);
static RfcommPortEvent toPortEvent(tRFC_PORT_EVENT event);

void port_collect_attempt_metrics(tPORT* p_port) {
  bool is_server = p_port->is_server;
  bool sdp_initiated = (p_port->sdp_duration_ms > 0);
  // If we're calling this metrics function, SDP completed with no problems
  BtaStatus sdp_status = sdp_initiated ? BTA_STATUS_SUCCESS : BTA_STATUS_UNKNOWN;
  RfcommPortSm sm_cb = p_port->rfc.sm_cb;
  log::assert_that(sm_cb.state == RFC_STATE_CLOSED, "Assert failed: Port not closed");
  uint64_t open_duration_ms = (sm_cb.close_timestamp - sm_cb.open_timestamp) / 1000;

  shim::LogMetricRfcommConnectionAtClose(
          p_port->bd_addr, toPortResult(sm_cb.close_reason), toSecurity(p_port->sec_mask),
          toPortEvent(sm_cb.last_event), toPortState(sm_cb.state_prior),
          static_cast<int32_t>(open_duration_ms), static_cast<int32_t>(p_port->app_uid), sdp_status,
          is_server, sdp_initiated, static_cast<int32_t>(p_port->sdp_duration_ms));
}

static SocketConnectionSecurity toSecurity(uint16_t sec_mask) {
  if (((sec_mask & BTM_SEC_IN_FLAGS) == (BTM_SEC_IN_AUTHENTICATE | BTM_SEC_IN_ENCRYPT)) ||
      ((sec_mask & BTM_SEC_OUT_FLAGS) == (BTM_SEC_OUT_AUTHENTICATE | BTM_SEC_OUT_ENCRYPT))) {
    return SocketConnectionSecurity::SOCKET_SECURITY_SECURE;
  } else if (((sec_mask & BTM_SEC_IN_FLAGS) == (BTM_SEC_NONE)) ||
             ((sec_mask & BTM_SEC_OUT_FLAGS) == (BTM_SEC_NONE))) {
    return SocketConnectionSecurity::SOCKET_SECURITY_INSECURE;
  }

  return SocketConnectionSecurity::SOCKET_SECURITY_UNKNOWN;
}

static PortResult toPortResult(tPORT_RESULT result) {
  switch (result) {
    case PORT_SUCCESS:
      return PortResult::PORT_RESULT_SUCCESS;
    case PORT_UNKNOWN_ERROR:
      return PortResult::PORT_RESULT_UNKNOWN_ERROR;
    case PORT_ALREADY_OPENED:
      return PortResult::PORT_RESULT_ALREADY_OPENED;
    case PORT_CMD_PENDING:
      return PortResult::PORT_RESULT_CMD_PENDING;
    case PORT_APP_NOT_REGISTERED:
      return PortResult::PORT_RESULT_APP_NOT_REGISTERED;
    case PORT_NO_MEM:
      return PortResult::PORT_RESULT_NO_MEM;
    case PORT_NO_RESOURCES:
      return PortResult::PORT_RESULT_NO_RESOURCES;
    case PORT_BAD_BD_ADDR:
      return PortResult::PORT_RESULT_BAD_BD_ADDR;
    case PORT_BAD_HANDLE:
      return PortResult::PORT_RESULT_BAD_HANDLE;
    case PORT_NOT_OPENED:
      return PortResult::PORT_RESULT_NOT_OPENED;
    case PORT_LINE_ERR:
      return PortResult::PORT_RESULT_LINE_ERR;
    case PORT_START_FAILED:
      return PortResult::PORT_RESULT_START_FAILED;
    case PORT_PAR_NEG_FAILED:
      return PortResult::PORT_RESULT_PAR_NEG_FAILED;
    case PORT_PORT_NEG_FAILED:
      return PortResult::PORT_RESULT_PORT_NEG_FAILED;
    case PORT_SEC_FAILED:
      return PortResult::PORT_RESULT_SEC_FAILED;
    case PORT_PEER_CONNECTION_FAILED:
      return PortResult::PORT_RESULT_PEER_CONNECTION_FAILED;
    case PORT_PEER_FAILED:
      return PortResult::PORT_RESULT_PEER_FAILED;
    case PORT_PEER_TIMEOUT:
      return PortResult::PORT_RESULT_PEER_TIMEOUT;
    case PORT_CLOSED:
      return PortResult::PORT_RESULT_CLOSED;
    case PORT_TX_FULL:
      return PortResult::PORT_RESULT_TX_FULL;
    case PORT_LOCAL_CLOSED:
      return PortResult::PORT_RESULT_LOCAL_CLOSED;
    case PORT_LOCAL_TIMEOUT:
      return PortResult::PORT_RESULT_LOCAL_TIMEOUT;
    case PORT_TX_QUEUE_DISABLED:
      return PortResult::PORT_RESULT_TX_QUEUE_DISABLED;
    case PORT_PAGE_TIMEOUT:
      return PortResult::PORT_RESULT_PAGE_TIMEOUT;
    case PORT_INVALID_SCN:
      return PortResult::PORT_RESULT_INVALID_SCN;
    case PORT_ERR_MAX:
      return PortResult::PORT_RESULT_ERR_MAX;
  }
  return PortResult::PORT_RESULT_UNDEFINED;
}

static RfcommPortState toPortState(tRFC_PORT_STATE state) {
  switch (state) {
    case RFC_STATE_SABME_WAIT_UA:
      return RfcommPortState::PORT_STATE_SABME_WAIT_UA;
    case RFC_STATE_ORIG_WAIT_SEC_CHECK:
      return RfcommPortState::PORT_STATE_ORIG_WAIT_SEC_CHECK;
    case RFC_STATE_TERM_WAIT_SEC_CHECK:
      return RfcommPortState::PORT_STATE_TERM_WAIT_SEC_CHECK;
    case RFC_STATE_OPENED:
      return RfcommPortState::PORT_STATE_OPENED;
    case RFC_STATE_DISC_WAIT_UA:
      return RfcommPortState::PORT_STATE_DISC_WAIT_UA;
    case RFC_STATE_CLOSED:
      return RfcommPortState::PORT_STATE_CLOSED;
  }
  return RfcommPortState::PORT_STATE_UNKNOWN;
}

static RfcommPortEvent toPortEvent(tRFC_PORT_EVENT event) {
  switch (event) {
    case RFC_PORT_EVENT_SABME:
      return RfcommPortEvent::PORT_EVENT_SABME;
    case RFC_PORT_EVENT_UA:
      return RfcommPortEvent::PORT_EVENT_UA;
    case RFC_PORT_EVENT_DM:
      return RfcommPortEvent::PORT_EVENT_DM;
    case RFC_PORT_EVENT_DISC:
      return RfcommPortEvent::PORT_EVENT_DISC;
    case RFC_PORT_EVENT_UIH:
      return RfcommPortEvent::PORT_EVENT_UIH;
    case RFC_PORT_EVENT_TIMEOUT:
      return RfcommPortEvent::PORT_EVENT_TIMEOUT;
    case RFC_PORT_EVENT_OPEN:
      return RfcommPortEvent::PORT_EVENT_OPEN;
    case RFC_PORT_EVENT_ESTABLISH_RSP:
      return RfcommPortEvent::PORT_EVENT_ESTABLISH_RSP;
    case RFC_PORT_EVENT_CLOSE:
      return RfcommPortEvent::PORT_EVENT_CLOSE;
    case RFC_PORT_EVENT_CLEAR:
      return RfcommPortEvent::PORT_EVENT_CLEAR;
    case RFC_PORT_EVENT_DATA:
      return RfcommPortEvent::PORT_EVENT_DATA;
    case RFC_PORT_EVENT_SEC_COMPLETE:
      return RfcommPortEvent::PORT_EVENT_SEC_COMPLETE;
  }
  return RfcommPortEvent::PORT_EVENT_UNKNOWN;
}
