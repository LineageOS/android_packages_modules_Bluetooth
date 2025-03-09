/*
 * Copyright 2024 The Android Open Source Project
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
#include "bluetooth_event.h"

#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>

#include "bta/include/bta_hfp_api.h"
#include "main/shim/helpers.h"
#include "os/metrics.h"
#include "stack/include/btm_api_types.h"

namespace bluetooth {
namespace metrics {

using android::bluetooth::EventType;
using android::bluetooth::State;
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
    case tHCI_STATUS::HCI_ERR_INSUFFCIENT_SECURITY:
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

void LogIncomingAclStartEvent(const hci::Address& address) {
  bluetooth::os::LogMetricBluetoothEvent(address, EventType::ACL_CONNECTION_RESPONDER,
                                         State::START);
}

void LogAclCompletionEvent(const hci::Address& address, ErrorCode reason,
                           bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated ? EventType::ACL_CONNECTION_INITIATOR
                                                              : EventType::ACL_CONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogRemoteNameRequestCompletion(const RawAddress& raw_address, tHCI_STATUS hci_status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, EventType::REMOTE_NAME_REQUEST,
          MapHCIStatusToState(hci_status));
}

void LogAclDisconnectionEvent(const hci::Address& address, ErrorCode reason,
                              bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated
                                                 ? EventType::ACL_DISCONNECTION_INITIATOR
                                                 : EventType::ACL_DISCONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogAclAfterRemoteNameRequest(const RawAddress& raw_address, tBTM_STATUS status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);

  switch (status) {
    case tBTM_STATUS::BTM_SUCCESS:
      bluetooth::os::LogMetricBluetoothEvent(address, EventType::ACL_CONNECTION_INITIATOR,
                                             State::ALREADY_CONNECTED);
      break;
    case tBTM_STATUS::BTM_NO_RESOURCES:
      bluetooth::os::LogMetricBluetoothEvent(
              address, EventType::ACL_CONNECTION_INITIATOR,
              MapErrorCodeToState(ErrorCode::CONNECTION_REJECTED_LIMITED_RESOURCES));
      break;
    default:
      break;
  }
}

void LogAuthenticationComplete(const RawAddress& raw_address, tHCI_STATUS hci_status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         hci_status == tHCI_STATUS::HCI_SUCCESS
                                                 ? EventType::AUTHENTICATION_COMPLETE
                                                 : EventType::AUTHENTICATION_COMPLETE_FAIL,
                                         MapHCIStatusToState(hci_status));
}

void LogSDPComplete(const RawAddress& raw_address, tBTA_STATUS status) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, EventType::SERVICE_DISCOVERY,
          status == tBTA_STATUS::BTA_SUCCESS ? State::SUCCESS : State::FAIL);
}

void LogLeAclCompletionEvent(const hci::Address& address, hci::ErrorCode reason,
                             bool is_locally_initiated) {
  bluetooth::os::LogMetricBluetoothEvent(address,
                                         is_locally_initiated
                                                 ? EventType::LE_ACL_CONNECTION_INITIATOR
                                                 : EventType::LE_ACL_CONNECTION_RESPONDER,
                                         MapErrorCodeToState(reason));
}

void LogLePairingFail(const RawAddress& raw_address, uint8_t failure_reason, bool is_outgoing) {
  hci::Address address = bluetooth::ToGdAddress(raw_address);
  bluetooth::os::LogMetricBluetoothEvent(
          address, is_outgoing ? EventType::SMP_PAIRING_OUTGOING : EventType::SMP_PAIRING_INCOMING,
          MapSmpStatusCodeToState(static_cast<tSMP_STATUS>(failure_reason)));
}

}  // namespace metrics
}  // namespace bluetooth
