/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/metrics/os_metrics.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>

#include "bta/include/bta_ag_api.h"
#include "bta/include/bta_av_api.h"
#include "bta/include/bta_hfp_api.h"
#include "bta/include/bta_sec_api.h"
#include "main/shim/helpers.h"
#include "stack/include/avdt_api.h"
#include "stack/include/btm_api_types.h"

namespace bluetooth::metrics {

using hci::ErrorCode;

State MapErrorCodeToState(ErrorCode reason) {
  switch (reason) {
    case ErrorCode::SUCCESS:
      return State::SUCCESS;
    case ErrorCode::UNKNOWN_HCI_COMMAND:
      return State::UNKNOWN_HCI_COMMAND;
    case ErrorCode::UNKNOWN_CONNECTION:
      return State::NO_CONNECTION;
    case ErrorCode::HARDWARE_FAILURE:
      return State::HARDWARE_FAILURE;
    case ErrorCode::PAGE_TIMEOUT:
      return State::PAGE_TIMEOUT;
    case ErrorCode::AUTHENTICATION_FAILURE:
      return State::AUTH_FAILURE;
    case ErrorCode::PIN_OR_KEY_MISSING:
      return State::KEY_MISSING;
    case ErrorCode::MEMORY_CAPACITY_EXCEEDED:
      return State::MEMORY_CAPACITY_EXCEEDED;
    case ErrorCode::CONNECTION_TIMEOUT:
      return State::CONNECTION_TIMEOUT;
    case ErrorCode::CONNECTION_LIMIT_EXCEEDED:
      return State::CONNECTION_LIMIT_EXCEEDED;
    case ErrorCode::SYNCHRONOUS_CONNECTION_LIMIT_EXCEEDED:
      return State::SYNCHRONOUS_CONNECTION_LIMIT_EXCEEDED;
    case ErrorCode::CONNECTION_ALREADY_EXISTS:
      return State::ALREADY_CONNECTED;
    case ErrorCode::COMMAND_DISALLOWED:
      return State::COMMAND_DISALLOWED;
    case ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES:
      return State::RESOURCES_EXCEEDED;
    case ErrorCode::CONNECTION_REJECTED_SECURITY_REASONS:
      return State::CONNECTION_REJECTED_SECURITY_REASONS;
    case ErrorCode::CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR:
      return State::CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR;
    case ErrorCode::CONNECTION_ACCEPT_TIMEOUT:
      return State::CONNECTION_ACCEPT_TIMEOUT;
    case ErrorCode::UNSUPPORTED_FEATURE_OR_PARAMETER_VALUE:
      return State::UNSUPPORTED_FEATURE_OR_PARAMETER_VALUE;
    case ErrorCode::INVALID_HCI_COMMAND_PARAMETERS:
      return State::INVALID_HCI_COMMAND_PARAMETERS;
    case ErrorCode::REMOTE_USER_TERMINATED_CONNECTION:
      return State::REMOTE_USER_TERMINATED_CONNECTION;
    case ErrorCode::REMOTE_DEVICE_TERMINATED_CONNECTION_LOW_RESOURCES:
      return State::REMOTE_DEVICE_TERMINATED_CONNECTION_LOW_RESOURCES;
    case ErrorCode::REMOTE_DEVICE_TERMINATED_CONNECTION_POWER_OFF:
      return State::REMOTE_DEVICE_TERMINATED_CONNECTION_POWER_OFF;
    case ErrorCode::CONNECTION_TERMINATED_BY_LOCAL_HOST:
      return State::CONNECTION_TERMINATED_BY_LOCAL_HOST;
    case ErrorCode::REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case ErrorCode::PAIRING_NOT_ALLOWED:
      return State::PAIRING_NOT_ALLOWED;
    case ErrorCode::UNKNOWN_LMP_PDU:
      return State::UNKNOWN_LMP_PDU;
    case ErrorCode::UNSUPPORTED_REMOTE_OR_LMP_FEATURE:
      return State::UNSUPPORTED_REMOTE_OR_LMP_FEATURE;
    case ErrorCode::SCO_OFFSET_REJECTED:
      return State::SCO_OFFSET_REJECTED;
    case ErrorCode::SCO_INTERVAL_REJECTED:
      return State::SCO_INTERVAL_REJECTED;
    case ErrorCode::SCO_AIR_MODE_REJECTED:
      return State::SCO_AIR_MODE_REJECTED;
    case ErrorCode::INVALID_LMP_OR_LL_PARAMETERS:
      return State::INVALID_LMP_OR_LL_PARAMETERS;
    case ErrorCode::UNSPECIFIED_ERROR:
      return State::UNSPECIFIED_ERROR;
    case ErrorCode::UNSUPPORTED_LMP_OR_LL_PARAMETER:
      return State::UNSUPPORTED_LMP_OR_LL_PARAMETER;
    case ErrorCode::ROLE_CHANGE_NOT_ALLOWED:
      return State::ROLE_CHANGE_NOT_ALLOWED;
    case ErrorCode::TRANSACTION_RESPONSE_TIMEOUT:
      return State::TRANSACTION_RESPONSE_TIMEOUT;
    case ErrorCode::LINK_LAYER_COLLISION:
      return State::LINK_LAYER_COLLISION;
    case ErrorCode::LMP_PDU_NOT_ALLOWED:
      return State::LMP_PDU_NOT_ALLOWED;
    case ErrorCode::ENCRYPTION_MODE_NOT_ACCEPTABLE:
      return State::ENCRYPTION_MODE_NOT_ACCEPTABLE;
    case ErrorCode::LINK_KEY_CANNOT_BE_CHANGED:
      return State::LINK_KEY_CANNOT_BE_CHANGED;
    case ErrorCode::REQUESTED_QOS_NOT_SUPPORTED:
      return State::REQUESTED_QOS_NOT_SUPPORTED;
    case ErrorCode::INSTANT_PASSED:
      return State::INSTANT_PASSED;
    case ErrorCode::PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED:
      return State::PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED;
    case ErrorCode::DIFFERENT_TRANSACTION_COLLISION:
      return State::DIFFERENT_TRANSACTION_COLLISION;
    case ErrorCode::QOS_UNACCEPTABLE_PARAMETERS:
      return State::QOS_UNACCEPTABLE_PARAMETERS;
    case ErrorCode::QOS_REJECTED:
      return State::QOS_REJECTED;
    case ErrorCode::CHANNEL_ASSESSMENT_NOT_SUPPORTED:
      return State::CHANNEL_ASSESSMENT_NOT_SUPPORTED;
    case ErrorCode::INSUFFICIENT_SECURITY:
      return State::INSUFFICIENT_SECURITY;
    case ErrorCode::PARAMETER_OUT_OF_MANDATORY_RANGE:
      return State::PARAMETER_OUT_OF_MANDATORY_RANGE;
    case ErrorCode::ROLE_SWITCH_PENDING:
      return State::ROLE_SWITCH_PENDING;
    case ErrorCode::RESERVED_SLOT_VIOLATION:
      return State::RESERVED_SLOT_VIOLATION;
    case ErrorCode::ROLE_SWITCH_FAILED:
      return State::ROLE_SWITCH_FAILED;
    case ErrorCode::EXTENDED_INQUIRY_RESPONSE_TOO_LARGE:
      return State::EXTENDED_INQUIRY_RESPONSE_TOO_LARGE;
    case ErrorCode::SECURE_SIMPLE_PAIRING_NOT_SUPPORTED_BY_HOST:
      return State::SECURE_SIMPLE_PAIRING_NOT_SUPPORTED_BY_HOST;
    case ErrorCode::HOST_BUSY_PAIRING:
      return State::HOST_BUSY_PAIRING;
    case ErrorCode::CONNECTION_REJECTED_NO_SUITABLE_CHANNEL_FOUND:
      return State::CONNECTION_REJECTED_NO_SUITABLE_CHANNEL_FOUND;
    case ErrorCode::CONTROLLER_BUSY:
      return State::CONTROLLER_BUSY;
    case ErrorCode::UNACCEPTABLE_CONNECTION_PARAMETERS:
      return State::UNACCEPTABLE_CONNECTION_PARAMETERS;
    case ErrorCode::ADVERTISING_TIMEOUT:
      return State::ADVERTISING_TIMEOUT;
    case ErrorCode::CONNECTION_TERMINATED_DUE_TO_MIC_FAILURE:
      return State::CONNECTION_TERMINATED_DUE_TO_MIC_FAILURE;
    case ErrorCode::CONNECTION_FAILED_ESTABLISHMENT:
      return State::CONNECTION_FAILED_ESTABLISHMENT;
    case ErrorCode::COARSE_CLOCK_ADJUSTMENT_REJECTED:
      return State::COARSE_CLOCK_ADJUSTMENT_REJECTED;
    case ErrorCode::TYPE0_SUBMAP_NOT_DEFINED:
      return State::TYPE0_SUBMAP_NOT_DEFINED;
    case ErrorCode::UNKNOWN_ADVERTISING_IDENTIFIER:
      return State::UNKNOWN_ADVERTISING_IDENTIFIER;
    case ErrorCode::LIMIT_REACHED:
      return State::LIMIT_REACHED;
    case ErrorCode::OPERATION_CANCELLED_BY_HOST:
      return State::OPERATION_CANCELLED_BY_HOST;
    case ErrorCode::PACKET_TOO_LONG:
      return State::PACKET_TOO_LONG;
    default:
      return State::STATE_UNKNOWN;
  }
}

static State MapHCIStatusToState(tHCI_STATUS status) {
  switch (status) {
    case tHCI_STATUS::HCI_SUCCESS:
      return State::SUCCESS;
    case tHCI_STATUS::HCI_ERR_ILLEGAL_COMMAND:
      return State::ILLEGAL_COMMAND;
    case tHCI_STATUS::HCI_ERR_NO_CONNECTION:
      return State::NO_CONNECTION;
    case tHCI_STATUS::HCI_ERR_HW_FAILURE:
      return State::HW_FAILURE;
    case tHCI_STATUS::HCI_ERR_PAGE_TIMEOUT:
      return State::PAGE_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_AUTH_FAILURE:
      return State::AUTH_FAILURE;
    case tHCI_STATUS::HCI_ERR_KEY_MISSING:
      return State::KEY_MISSING;
    case tHCI_STATUS::HCI_ERR_MEMORY_FULL:
      return State::MEMORY_FULL;
    case tHCI_STATUS::HCI_ERR_CONNECTION_TOUT:
      return State::CONNECTION_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_MAX_NUM_OF_CONNECTIONS:
      return State::MAX_NUMBER_OF_CONNECTIONS;
    case tHCI_STATUS::HCI_ERR_MAX_NUM_OF_SCOS:
      return State::MAX_NUM_OF_SCOS;
    case tHCI_STATUS::HCI_ERR_CONNECTION_EXISTS:
      return State::ALREADY_CONNECTED;
    case tHCI_STATUS::HCI_ERR_COMMAND_DISALLOWED:
      return State::COMMAND_DISALLOWED;
    case tHCI_STATUS::HCI_ERR_HOST_REJECT_RESOURCES:
      return State::HOST_REJECT_RESOURCES;
    case tHCI_STATUS::HCI_ERR_HOST_REJECT_SECURITY:
      return State::HOST_REJECT_SECURITY;
    case tHCI_STATUS::HCI_ERR_HOST_REJECT_DEVICE:
      return State::HOST_REJECT_DEVICE;
    case tHCI_STATUS::HCI_ERR_HOST_TIMEOUT:
      return State::CONNECTION_ACCEPT_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_ILLEGAL_PARAMETER_FMT:
      return State::ILLEGAL_PARAMETER_FMT;
    case tHCI_STATUS::HCI_ERR_PEER_USER:
      return State::PEER_USER;
    case tHCI_STATUS::HCI_ERR_REMOTE_LOW_RESOURCE:
      return State::REMOTE_LOW_RESOURCE;
    case tHCI_STATUS::HCI_ERR_REMOTE_POWER_OFF:
      return State::REMOTE_POWER_OFF;
    case tHCI_STATUS::HCI_ERR_CONN_CAUSE_LOCAL_HOST:
      return State::CONN_CAUSE_LOCAL_HOST;
    case tHCI_STATUS::HCI_ERR_REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case tHCI_STATUS::HCI_ERR_PAIRING_NOT_ALLOWED:
      return State::PAIRING_NOT_ALLOWED;
    case tHCI_STATUS::HCI_ERR_UNSUPPORTED_REM_FEATURE:
      return State::UNSUPPORTED_REM_FEATURE;
    case tHCI_STATUS::HCI_ERR_UNSPECIFIED:
      return State::UNSPECIFIED;
    case tHCI_STATUS::HCI_ERR_LMP_RESPONSE_TIMEOUT:
      return State::TRANSACTION_RESPONSE_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_LMP_ERR_TRANS_COLLISION:
      return State::LMP_ERR_TRANS_COLLISION;
    case tHCI_STATUS::HCI_ERR_ENCRY_MODE_NOT_ACCEPTABLE:
      return State::ENCRYPTION_MODE_NOT_ACCEPTABLE;
    case tHCI_STATUS::HCI_ERR_UNIT_KEY_USED:
      return State::UNIT_KEY_USED;
    case tHCI_STATUS::HCI_ERR_PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED:
      return State::PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED;
    case tHCI_STATUS::HCI_ERR_DIFF_TRANSACTION_COLLISION:
      return State::DIFF_TRANSACTION_COLLISION;
    case tHCI_STATUS::HCI_ERR_INSUFFICIENT_SECURITY:
      return State::INSUFFICIENT_SECURITY;
    case tHCI_STATUS::HCI_ERR_ROLE_SWITCH_PENDING:
      return State::ROLE_SWITCH_PENDING;
    case tHCI_STATUS::HCI_ERR_ROLE_SWITCH_FAILED:
      return State::ROLE_SWITCH_FAILED;
    case tHCI_STATUS::HCI_ERR_HOST_BUSY_PAIRING:
      return State::HOST_BUSY_PAIRING;
    case tHCI_STATUS::HCI_ERR_UNACCEPT_CONN_INTERVAL:
      return State::UNACCEPT_CONN_INTERVAL;
    case tHCI_STATUS::HCI_ERR_ADVERTISING_TIMEOUT:
      return State::ADVERTISING_TIMEOUT;
    case tHCI_STATUS::HCI_ERR_CONN_FAILED_ESTABLISHMENT:
      return State::CONNECTION_FAILED_ESTABLISHMENT;
    case tHCI_STATUS::HCI_ERR_LIMIT_REACHED:
      return State::LIMIT_REACHED;
    case tHCI_STATUS::HCI_ERR_CANCELLED_BY_LOCAL_HOST:
      return State::CANCELLED_BY_LOCAL_HOST;
    case tHCI_STATUS::HCI_ERR_UNDEFINED:
      return State::UNDEFINED;
    default:
      return State::STATE_UNKNOWN;
  }
}

static State MapSmpStatusCodeToState(tSMP_STATUS status) {
  switch (status) {
    case tSMP_STATUS::SMP_SUCCESS:
      return State::SUCCESS;
    case tSMP_STATUS::SMP_PASSKEY_ENTRY_FAIL:
      return State::PASSKEY_ENTRY_FAIL;
    case tSMP_STATUS::SMP_OOB_FAIL:
      return State::OOB_FAIL;
    case tSMP_STATUS::SMP_PAIR_AUTH_FAIL:
      return State::AUTH_FAILURE;
    case tSMP_STATUS::SMP_CONFIRM_VALUE_ERR:
      return State::CONFIRM_VALUE_ERROR;
    case tSMP_STATUS::SMP_PAIR_NOT_SUPPORT:
      return State::PAIRING_NOT_ALLOWED;
    case tSMP_STATUS::SMP_ENC_KEY_SIZE:
      return State::ENC_KEY_SIZE;
    case tSMP_STATUS::SMP_INVALID_CMD:
      return State::INVALID_CMD;
    case tSMP_STATUS::SMP_PAIR_FAIL_UNKNOWN:
      return State::STATE_UNKNOWN;  // Assuming this maps to the default
    case tSMP_STATUS::SMP_REPEATED_ATTEMPTS:
      return State::REPEATED_ATTEMPTS;
    case tSMP_STATUS::SMP_INVALID_PARAMETERS:
      return State::INVALID_PARAMETERS;
    case tSMP_STATUS::SMP_DHKEY_CHK_FAIL:
      return State::DHKEY_CHK_FAIL;
    case tSMP_STATUS::SMP_NUMERIC_COMPAR_FAIL:
      return State::NUMERIC_COMPARISON_FAIL;
    case tSMP_STATUS::SMP_BR_PARING_IN_PROGR:
      return State::BR_PAIRING_IN_PROGRESS;
    case tSMP_STATUS::SMP_XTRANS_DERIVE_NOT_ALLOW:
      return State::CROSS_TRANSPORT_NOT_ALLOWED;
    case tSMP_STATUS::SMP_PAIR_INTERNAL_ERR:
      return State::INTERNAL_ERROR;
    case tSMP_STATUS::SMP_UNKNOWN_IO_CAP:
      return State::UNKNOWN_IO_CAP;
    case tSMP_STATUS::SMP_BUSY:
      return State::BUSY_PAIRING;
    case tSMP_STATUS::SMP_ENC_FAIL:
      return State::ENCRYPTION_FAIL;
    case tSMP_STATUS::SMP_STARTED:
      return State::STATE_UNKNOWN;  // Assuming this maps to the default
    case tSMP_STATUS::SMP_RSP_TIMEOUT:
      return State::RESPONSE_TIMEOUT;
    case tSMP_STATUS::SMP_FAIL:
      return State::FAIL;
    case tSMP_STATUS::SMP_CONN_TOUT:
      return State::CONNECTION_TIMEOUT;
    case tSMP_STATUS::SMP_SIRK_DEVICE_INVALID:
      return State::SIRK_DEVICE_INVALID;
    case tSMP_STATUS::SMP_USER_CANCELLED:
      return State::USER_CANCELLATION;
    default:
      return State::STATE_UNKNOWN;
  }
}

State MapHfpVersionToState(uint16_t version) {
  switch (version) {
    case HSP_VERSION_1_0:
      return State::VERSION_1_0;
    case HFP_VERSION_1_1:
      return State::VERSION_1_1;
    case HSP_VERSION_1_2:
      return State::VERSION_1_2;
    case HFP_VERSION_1_5:
      return State::VERSION_1_5;
    case HFP_VERSION_1_6:
      return State::VERSION_1_6;
    case HFP_VERSION_1_7:
      return State::VERSION_1_7;
    case HFP_VERSION_1_8:
      return State::VERSION_1_8;
    case HFP_VERSION_1_9:
      return State::VERSION_1_9;
    default:
      return State::VERSION_UNKNOWN;
  }
}

State MapScoCodecToState(uint16_t codec) {
  switch (codec) {
    case BTM_SCO_CODEC_CVSD:
      return State::CODEC_CVSD;
    case BTM_SCO_CODEC_MSBC:
      return State::CODEC_MSBC;
    case BTM_SCO_CODEC_LC3:
      return State::CODEC_LC3;
    case BTA_AG_SCO_APTX_SWB_SETTINGS_Q0_MASK:
      return State::CODEC_APTX_SWB_SETTINGS_Q0_MASK;
    case BTA_AG_SCO_APTX_SWB_SETTINGS_Q1_MASK:
      return State::CODEC_APTX_SWB_SETTINGS_Q1_MASK;
    case BTA_AG_SCO_APTX_SWB_SETTINGS_Q2_MASK:
      return State::CODEC_APTX_SWB_SETTINGS_Q2_MASK;
    case BTA_AG_SCO_APTX_SWB_SETTINGS_Q3_MASK:
      return State::CODEC_APTX_SWB_SETTINGS_Q3_MASK;
    default:
      return State::CODEC_UNKNOWN;
  }
}

State MapAgOpenStatusToState(tBTA_AG_STATUS status) {
  switch (status) {
    case BTA_AG_SUCCESS:
      return State::SUCCESS;
    case BTA_AG_FAIL_SDP:
      return State::SDP_DISCOVERY_FAILED;
    case BTA_AG_FAIL_RFCOMM:
      return State::RFCOMM_CONNECTION_FAILED;
    case BTA_AG_FAIL_RESOURCES:
      return State::RESOURCES_EXCEEDED;
    default:
      return State::FAIL;
  }
}

static State MapL2capResultToState(tL2CAP_CONN l2cap_result) {
  switch (l2cap_result) {
    case tL2CAP_CONN::L2CAP_CONN_OK:
      return State::L2CAP_CONN_STATUS_OK;
    case tL2CAP_CONN::L2CAP_CONN_PENDING:
      return State::L2CAP_CONN_STATUS_PENDING;
    case tL2CAP_CONN::L2CAP_CONN_NO_PSM:
      return State::L2CAP_CONN_STATUS_NO_PSM;
    case tL2CAP_CONN::L2CAP_CONN_SECURITY_BLOCK:
      return State::L2CAP_CONN_STATUS_SECURITY_BLOCK;
    case tL2CAP_CONN::L2CAP_CONN_NO_RESOURCES:
      return State::L2CAP_CONN_STATUS_NO_RESOURCES;
    case tL2CAP_CONN::L2CAP_CONN_TIMEOUT:
      return State::L2CAP_CONN_STATUS_TIMEOUT;
    case tL2CAP_CONN::L2CAP_CONN_OTHER_ERROR:
      return State::L2CAP_CONN_STATUS_OTHER_ERROR;
    case tL2CAP_CONN::L2CAP_CONN_ACL_CONNECTION_FAILED:
      return State::L2CAP_CONN_STATUS_ACL_CONNECTION_FAILED;
    case tL2CAP_CONN::L2CAP_CONN_CLIENT_SECURITY_CLEARANCE_FAILED:
      return State::L2CAP_CONN_STATUS_CLIENT_SECURITY_CLEARANCE_FAILED;
    case tL2CAP_CONN::L2CAP_CONN_NO_LINK:
      return State::L2CAP_CONN_STATUS_NO_LINK;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_AUTHENTICATION:
      return State::L2CAP_CONN_STATUS_INSUFFICIENT_AUTHENTICATION;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_AUTHORIZATION:
      return State::L2CAP_CONN_STATUS_INSUFFICIENT_AUTHORIZATION;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_ENCRYP_KEY_SIZE:
      return State::L2CAP_CONN_STATUS_INSUFFICIENT_ENCRYP_KEY_SIZE;
    case tL2CAP_CONN::L2CAP_CONN_INSUFFICIENT_ENCRYP:
      return State::L2CAP_CONN_STATUS_INSUFFICIENT_ENCRYP;
    case tL2CAP_CONN::L2CAP_CONN_INVALID_SOURCE_CID:
      return State::L2CAP_CONN_STATUS_INVALID_SOURCE_CID;
    case tL2CAP_CONN::L2CAP_CONN_SOURCE_CID_ALREADY_ALLOCATED:
      return State::L2CAP_CONN_STATUS_SOURCE_CID_ALREADY_ALLOCATED;
    case tL2CAP_CONN::L2CAP_CONN_UNACCEPTABLE_PARAMETERS:
      return State::L2CAP_CONN_STATUS_UNACCEPTABLE_PARAMETERS;
    case tL2CAP_CONN::L2CAP_CONN_INVALID_PARAMETERS:
      return State::L2CAP_CONN_STATUS_INVALID_PARAMETERS;
    default:
      return State::L2CAP_CONN_STATUS_UNKNOWN_ERROR;
  }
}

static State MapBtaAvResultToState(uint8_t result) {
  switch (result) {
    case BTA_AV_SUCCESS:
      return State::BTA_AV_STATUS_SUCCESS;
    case BTA_AV_FAIL:
      return State::BTA_AV_STATUS_FAIL;
    case BTA_AV_FAIL_SDP:
      return State::BTA_AV_STATUS_FAIL_SDP;
    case BTA_AV_FAIL_STREAM:
      return State::BTA_AV_STATUS_FAIL_STREAM;
    case BTA_AV_FAIL_RESOURCES:
      return State::BTA_AV_STATUS_FAIL_RESOURCES;
    case BTA_AV_FAIL_ROLE:
      return State::BTA_AV_STATUS_FAIL_ROLE;
    case BTA_AV_FAIL_GET_CAP:
      return State::BTA_AV_STATUS_FAIL_GET_CAP;
    default:
      return State::BTA_AV_STATUS_FAIL;
  }
}

static State MapAdditionalAvdtpErrorToState(uint16_t err_code) {
  switch (err_code) {
    case AVDT_ERR_CONNECT:
      return State::AVDT_STATUS_ERR_CONNECT;
    case AVDT_ERR_TIMEOUT:
      return State::AVDT_STATUS_ERR_TIMEOUT;
    default:
      return State::AVDTP_STATUS_UNKNOWN_ERROR;
  }
}

static State MapBtsockErrorToState(btsock_error_code_t error_code) {
  switch (error_code) {
    case BTSOCK_ERROR_NONE:
      return State::SOCKET_CLOSED;
    case BTSOCK_ERROR_CLIENT_INIT_FAILURE:
      return State::SOCKET_CLIENT_INIT_FAILURE;
    case BTSOCK_ERROR_CONNECTION_FAILURE:
      return State::SOCKET_CONNECTION_FAILURE;
    case BTSOCK_ERROR_OPEN_FAILURE:
      return State::SOCKET_OPEN_FAILURE;
    case BTSOCK_ERROR_OFFLOAD_SERVER_NOT_ACCEPTING:
      return State::SOCKET_OFFLOAD_SERVER_NOT_ACCEPTING;
    case BTSOCK_ERROR_OFFLOAD_HAL_OPEN_FAILURE:
      return State::SOCKET_OFFLOAD_HAL_OPEN_FAILURE;
    case BTSOCK_ERROR_SEND_TO_APP_FAILURE:
      return State::SOCKET_SEND_TO_APP_FAILURE;
    case BTSOCK_ERROR_RECEIVE_DATA_FAILURE:
      return State::SOCKET_RECEIVE_DATA_FAILURE;
    case BTSOCK_ERROR_READ_SIGNALED_FAILURE:
      return State::SOCKET_READ_SIGNALED_FAILURE;
    case BTSOCK_ERROR_WRITE_SIGNALED_FAILURE:
      return State::SOCKET_WRITE_SIGNALED_FAILURE;
    case BTSOCK_ERROR_SEND_SCN_FAILURE:
      return State::SOCKET_SEND_SCN_FAILURE;
    case BTSOCK_ERROR_SCN_ALLOCATION_FAILURE:
      return State::SOCKET_SCN_ALLOCATION_FAILURE;
    case BTSOCK_ERROR_SDP_DISCOVERY_FAILURE:
      return State::SOCKET_SDP_DISCOVERY_FAILURE;
    default:
      return State::STATE_UNKNOWN;
  }
}

static State MapPortResultToState(tPORT_RESULT result) {
  switch (result) {
    case PORT_SUCCESS:
      return State::SUCCESS;
    case PORT_ALREADY_OPENED:
      return State::PORT_STATUS_ALREADY_OPENED;
    case PORT_CMD_PENDING:
      return State::PORT_STATUS_CMD_PENDING;
    case PORT_APP_NOT_REGISTERED:
      return State::PORT_STATUS_APP_NOT_REGISTERED;
    case PORT_NO_MEM:
      return State::PORT_STATUS_NO_MEM;
    case PORT_NO_RESOURCES:
      return State::PORT_STATUS_NO_RESOURCES;
    case PORT_BAD_BD_ADDR:
      return State::PORT_STATUS_BAD_BD_ADDR;
    case PORT_BAD_HANDLE:
      return State::PORT_STATUS_BAD_HANDLE;
    case PORT_NOT_OPENED:
      return State::PORT_STATUS_NOT_OPENED;
    case PORT_LINE_ERR:
      return State::PORT_STATUS_LINE_ERR;
    case PORT_START_FAILED:
      return State::PORT_STATUS_START_FAILED;
    case PORT_PAR_NEG_FAILED:
      return State::PORT_STATUS_PAR_NEG_FAILED;
    case PORT_PORT_NEG_FAILED:
      return State::PORT_STATUS_PORT_NEG_FAILED;
    case PORT_SEC_FAILED:
      return State::PORT_STATUS_SEC_FAILED;
    case PORT_PEER_CONNECTION_FAILED:
      return State::PORT_STATUS_PEER_CONNECTION_FAILED;
    case PORT_PEER_FAILED:
      return State::PORT_STATUS_PEER_FAILED;
    case PORT_PEER_TIMEOUT:
      return State::PORT_STATUS_PEER_TIMEOUT;
    case PORT_CLOSED:
      return State::PORT_STATUS_CLOSED;
    case PORT_TX_FULL:
      return State::PORT_STATUS_TX_FULL;
    case PORT_LOCAL_CLOSED:
      return State::PORT_STATUS_LOCAL_CLOSED;
    case PORT_LOCAL_TIMEOUT:
      return State::PORT_STATUS_LOCAL_TIMEOUT;
    case PORT_TX_QUEUE_DISABLED:
      return State::PORT_STATUS_TX_QUEUE_DISABLED;
    case PORT_INVALID_SCN:
      return State::PORT_STATUS_INVALID_SCN;
    default:
      return State::STATE_UNKNOWN;
  }
}

void LogIncomingAclStartEvent(const hci::Address& address) {
  LogBluetoothEvent(address, EventType::ACL_CONNECTION_RESPONDER, State::START);
}

void LogAclCompletionEvent(const hci::Address& address, ErrorCode reason,
                           bool is_locally_initiated) {
  LogBluetoothEvent(address,
                    is_locally_initiated ? EventType::ACL_CONNECTION_INITIATOR
                                         : EventType::ACL_CONNECTION_RESPONDER,
                    MapErrorCodeToState(reason));
}

void LogRemoteNameRequestCompletion(const RawAddress& address, tHCI_STATUS hci_status) {
  LogBluetoothEvent(address, EventType::REMOTE_NAME_REQUEST, MapHCIStatusToState(hci_status));
}

void LogAclDisconnectionEvent(const hci::Address& address, ErrorCode reason,
                              bool is_locally_initiated) {
  LogBluetoothEvent(address,
                    is_locally_initiated ? EventType::ACL_DISCONNECTION_INITIATOR
                                         : EventType::ACL_DISCONNECTION_RESPONDER,
                    MapErrorCodeToState(reason));
}

void LogAclAfterRemoteNameRequest(const RawAddress& address, tBTM_STATUS status) {
  switch (status) {
    case tBTM_STATUS::BTM_SUCCESS:
      LogBluetoothEvent(address, EventType::ACL_CONNECTION_INITIATOR, State::ALREADY_CONNECTED);
      break;
    case tBTM_STATUS::BTM_NO_RESOURCES:
      LogBluetoothEvent(address, EventType::ACL_CONNECTION_INITIATOR,
                        MapErrorCodeToState(ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES));
      break;
    default:
      break;
  }
}

void LogAuthenticationComplete(const RawAddress& address, tHCI_STATUS hci_status) {
  LogBluetoothEvent(address,
                    hci_status == tHCI_STATUS::HCI_SUCCESS
                            ? EventType::AUTHENTICATION_COMPLETE
                            : EventType::AUTHENTICATION_COMPLETE_FAIL,
                    MapHCIStatusToState(hci_status));
}

void LogSDPComplete(const RawAddress& address, tBTA_STATUS status) {
  LogBluetoothEvent(address, EventType::SERVICE_DISCOVERY,
                    status == tBTA_STATUS::BTA_SUCCESS ? State::SUCCESS : State::FAIL);
}

void LogLeAclCompletionEvent(const hci::Address& address, hci::ErrorCode reason,
                             bool is_locally_initiated) {
  LogBluetoothEvent(address,
                    is_locally_initiated ? EventType::LE_ACL_CONNECTION_INITIATOR
                                         : EventType::LE_ACL_CONNECTION_RESPONDER,
                    MapErrorCodeToState(reason));
}

void LogLePairingFail(const RawAddress& address, uint8_t failure_reason, bool is_outgoing) {
  LogBluetoothEvent(address,
                    is_outgoing ? EventType::SMP_PAIRING_OUTGOING : EventType::SMP_PAIRING_INCOMING,
                    MapSmpStatusCodeToState(static_cast<tSMP_STATUS>(failure_reason)));
}

void LogMetricLeConnectionStatus(hci::Address address, bool is_connect, hci::ErrorCode reason) {
  LogBluetoothEvent(address,
                    is_connect ? EventType::GATT_CONNECT_NATIVE : EventType::GATT_DISCONNECT_NATIVE,
                    bluetooth::metrics::MapErrorCodeToState(reason));
}

void LogMetricLeDeviceInAcceptList(hci::Address address, bool is_add) {
  LogBluetoothEvent(address, EventType::LE_DEVICE_IN_ACCEPT_LIST,
                    is_add ? State::START : State::END);
}

void LogMetricLeConnectionLifecycle(hci::Address address, bool is_connect, bool is_direct) {
  if (is_connect) {
    LogBluetoothEvent(address, EventType::GATT_CONNECT_NATIVE,
                      is_direct ? State::DIRECT_CONNECT : State::INDIRECT_CONNECT);
  } else {
    LogBluetoothEvent(address, EventType::GATT_DISCONNECT_NATIVE, State::START);
  }
}

void LogMetricLeConnectionRejected(hci::Address address) {
  LogBluetoothEvent(address, EventType::LE_CONNECTION_REJECTED, State::ATTEMPT_IN_PROGRESS);
}

void LogMetricHfpAgVersion(hci::Address address, uint16_t version) {
  LogBluetoothEvent(address, EventType::HFP_AG_VERSION,
                    bluetooth::metrics::MapHfpVersionToState(version));
}

void LogMetricHfpHfVersion(hci::Address address, uint16_t version) {
  LogBluetoothEvent(address, EventType::HFP_HF_VERSION,
                    bluetooth::metrics::MapHfpVersionToState(version));
}

void LogMetricHfpRfcommChannelFail(hci::Address address) {
  LogBluetoothEvent(address, EventType::HFP_SESSION, State::HFP_RFCOMM_CHANNEL_FAIL);
}

void LogMetricHfpRfcommCollisionFail(hci::Address address) {
  LogBluetoothEvent(address, EventType::HFP_SESSION, State::HFP_RFCOMM_COLLISION_FAIL);
}

void LogMetricHfpRfcommAgOpenFail(hci::Address address) {
  LogBluetoothEvent(address, EventType::HFP_SESSION, State::HFP_RFCOMM_AG_OPEN_FAIL);
}

void LogMetricHfpSlcFail(hci::Address address) {
  LogBluetoothEvent(address, EventType::HFP_SESSION, State::HFP_SLC_FAIL_CONNECTION);
}

void LogMetricScoLinkCreated(hci::Address address) {
  LogBluetoothEvent(address, EventType::SCO_SESSION, State::SCO_LINK_CREATED);
}

void LogMetricScoLinkRemoved(hci::Address address) {
  LogBluetoothEvent(address, EventType::SCO_SESSION, State::SCO_LINK_REMOVED);
}

void LogMetricScoCodec(hci::Address address, uint16_t codec) {
  LogBluetoothEvent(address, EventType::SCO_CODEC, bluetooth::metrics::MapScoCodecToState(codec));
}

void LogMetricHfpStartStream(hci::Address address) {
  LogBluetoothEvent(address, EventType::SCO_SESSION, State::AUDIO_PORT_START_STREAM);
}

void LogMetricHfpSuspendStream(hci::Address address) {
  LogBluetoothEvent(address, EventType::SCO_SESSION, State::AUDIO_PORT_SUSPEND_STREAM);
}

void LogMetricHfpStreamStarted(hci::Address address) {
  LogBluetoothEvent(address, EventType::SCO_SESSION, State::AUDIO_PROVIDER_STREAM_STARTED);
}

void LogMetricAgOpenStatus(hci::Address address, tBTA_AG_STATUS status) {
  LogBluetoothEvent(address, EventType::HFP_SESSION,
                    bluetooth::metrics::MapAgOpenStatusToState(status));
}

void LogAvdtpL2capEvent(hci::Address address, EventType event, tL2CAP_CONN l2cap_result) {
  LogBluetoothEvent(address, event, bluetooth::metrics::MapL2capResultToState(l2cap_result));
}

void LogAvdtpL2capErrorEvent(hci::Address address, tL2CAP_CONN l2cap_result) {
  LogBluetoothEvent(address, EventType::AVDTP_ON_L2CAP_ERROR,
                    bluetooth::metrics::MapL2capResultToState(l2cap_result));
}

void LogAvdtpDiscFailEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_DISC_FAIL_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpGetCapFailEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_GETCAP_FAIL_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpSignalingTimeoutEvent(hci::Address address, uint16_t error_code) {
  LogBluetoothEvent(address, EventType::AVDTP_SIGNALING_TIMEOUT,
                    bluetooth::metrics::MapAdditionalAvdtpErrorToState(error_code));
}

void LogAvdtpOpenRejectedEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_OPEN_REJECT_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpOpenFailEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_OPEN_FAIL_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpSetConfigRejectedEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_SET_CONFIG_REJECT_EVT,
                    State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpStartRejectEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_START_REJECT_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpSuspendRejectEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_SUSPEND_REJECT_EVT, State::A2DP_EVENT_STATUS_FAILURE);
}

void LogAvdtpAbortResponseSendEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_ABORT_RESPONSE_SEND_EVT,
                    State::A2DP_EVENT_STATUS_SUCCESS);
}

void LogAvdtpCloseResponseSendEvent(hci::Address address) {
  LogBluetoothEvent(address, EventType::AVDTP_CLOSE_RESPONSE_SEND_EVT,
                    State::A2DP_EVENT_STATUS_SUCCESS);
}

void LogA2dpBtifAvStateChangeEvent(hci::Address address, uint8_t result) {
  LogBluetoothEvent(address, EventType::A2DP_BTIF_AV_STATE_CHANGE_EVT,
                    bluetooth::metrics::MapBtaAvResultToState(result));
}

void LogRfcommNativeStartEvent(hci::Address address, EventType event, int uid) {
  LogBluetoothEvent(address, event, State::START, uid);
}

void LogRfcommNativeConnectionCompleteEvent(hci::Address address, EventType event, bool is_client,
                                            int uid) {
  LogBluetoothEvent(address, event, is_client ? State::SUCCESS_CONNECT : State::SUCCESS_ACCEPT,
                    uid);
}

void LogRfcommNativeDisconnectionEvent(hci::Address address, EventType event, int uid) {
  LogBluetoothEvent(address, event, State::STATE_DISCONNECTED, uid);
}

void LogRfcommSocketDisconnectionEvent(hci::Address address, int uid,
                                       btsock_error_code_t error_code) {
  LogBluetoothEvent(address, EventType::RFCOMM_SOCKET_DISCONNECTION,
                    MapBtsockErrorToState(error_code), uid);
}

void LogRfcommPortFailureEvent(hci::Address address, EventType event, int uid,
                               tPORT_RESULT result) {
  LogBluetoothEvent(address, event, MapPortResultToState(result), uid);
}

void LogRfcommL2capEvent(hci::Address address, EventType event, tL2CAP_CONN l2cap_result) {
  LogBluetoothEvent(address, event, MapL2capResultToState(l2cap_result));
}

void LogRfcommMxEvent(hci::Address address, State state) {
  LogBluetoothEvent(address, EventType::RFCOMM_MX_EVENT, state);
}

void LogBondRepairComplete(hci::Address address, bt_bond_state_t state, uint8_t fail_reason) {
  if (state == BT_BOND_STATE_BONDED) {
    LogBluetoothEvent(address, EventType::BOND_REPAIR, State::SUCCESS);
  } else {
    State mapped_state = State::STATE_UNKNOWN;
    if (fail_reason >= BTA_DM_AUTH_FAIL_BASE) {
      mapped_state = MapSmpStatusCodeToState(
              static_cast<tSMP_STATUS>(fail_reason - BTA_DM_AUTH_FAIL_BASE));
    } else {
      mapped_state = MapHCIStatusToState(static_cast<tHCI_STATUS>(fail_reason));
    }
    LogBluetoothEvent(address, EventType::BOND_REPAIR_FAIL, mapped_state);
  }
}

}  // namespace bluetooth::metrics
