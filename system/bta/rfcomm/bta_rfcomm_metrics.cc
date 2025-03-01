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
#define LOG_TAG "bta_rfcomm_metrics"

#include "bta_rfcomm_metrics.h"

#include <bluetooth/log.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/rfcomm/enums.pb.h>

#include "bta_sec_api.h"
#include "main/shim/metrics_api.h"
#include "stack/include/btm_sec_api_types.h"

using namespace bluetooth;

using namespace android::bluetooth;
using namespace android::bluetooth::rfcomm;

static BtaStatus toStatus(tBTA_JV_STATUS status);
static SocketConnectionSecurity toSecurity(int security);
static PortResult toPortResult(tPORT_RESULT result);

// logged if SDP result is either FAILED or BUSY
void bta_collect_rfc_metrics_after_sdp_fail(tBTA_JV_STATUS sdp_status, RawAddress addr, int app_uid,
                                            int security, bool is_server,
                                            uint64_t sdp_duration_ms) {
  // We only call this function in the case where we started (and failed) sdp
  bool sdp_initiated = true;

  // We didn't make it to the stage of making a port, so assign default values for these fields
  PortResult close_reason = PortResult::PORT_RESULT_UNDEFINED;
  RfcommPortState state_prior = RfcommPortState::PORT_STATE_UNKNOWN;
  RfcommPortEvent last_event = RfcommPortEvent::PORT_EVENT_UNKNOWN;
  int open_duration_ms = 0;

  shim::LogMetricRfcommConnectionAtClose(
          addr, close_reason, toSecurity(security), last_event, state_prior, open_duration_ms,
          app_uid, toStatus(sdp_status), is_server, sdp_initiated, sdp_duration_ms);
}

void bta_collect_rfc_metrics_after_port_fail(tPORT_RESULT port_result, bool sdp_initiated,
                                             tBTA_JV_STATUS sdp_status, RawAddress addr,
                                             int app_uid, int security, bool is_server,
                                             uint64_t sdp_duration_ms) {
  BtaStatus reported_status;
  if (sdp_status == tBTA_JV_STATUS::SUCCESS && !sdp_initiated) {
    reported_status = BtaStatus::BTA_STATUS_UNKNOWN;
  } else {
    reported_status = toStatus(sdp_status);
  }
  RfcommPortState state_prior = RfcommPortState::PORT_STATE_UNKNOWN;
  RfcommPortEvent last_event = RfcommPortEvent::PORT_EVENT_UNKNOWN;
  int open_duration_ms = 0;

  shim::LogMetricRfcommConnectionAtClose(
          addr, toPortResult(port_result), toSecurity(security), last_event, state_prior,
          open_duration_ms, app_uid, reported_status, is_server, sdp_initiated, sdp_duration_ms);
}

static BtaStatus toStatus(tBTA_JV_STATUS status) {
  switch (status) {
    case tBTA_JV_STATUS::SUCCESS:
      return BtaStatus::BTA_STATUS_SUCCESS;
    case tBTA_JV_STATUS::FAILURE:
      return BtaStatus::BTA_STATUS_FAILURE;
    case tBTA_JV_STATUS::BUSY:
      return BtaStatus::BTA_STATUS_BUSY;
  }
  return BtaStatus::BTA_STATUS_UNKNOWN;
}

static SocketConnectionSecurity toSecurity(int security) {
  if ((security == (BTM_SEC_IN_AUTHENTICATE | BTM_SEC_IN_ENCRYPT)) ||
      (security == (BTM_SEC_OUT_AUTHENTICATE | BTM_SEC_OUT_ENCRYPT)) ||
      (security == (BTA_SEC_AUTHENTICATE | BTA_SEC_ENCRYPT))) {
    return SocketConnectionSecurity::SOCKET_SECURITY_SECURE;
  } else if (security == BTM_SEC_NONE) {
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
