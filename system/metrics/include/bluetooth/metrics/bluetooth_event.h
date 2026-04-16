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

#pragma once

#include <bluetooth/metrics/os_metrics.h>
#include <bluetooth/types/address.h>

#include "bta/include/bta_ag_api.h"
#include "bta/include/bta_sec_api.h"
#include "hardware/bluetooth.h"
#include "hci/address.h"
#include "hci/hci_packets.h"
#include "include/hardware/bt_sock.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/port_api.h"

namespace bluetooth::metrics {

android::bluetooth::State MapErrorCodeToState(hci::ErrorCode reason);

android::bluetooth::State MapHfpVersionToState(uint16_t version);

android::bluetooth::State MapScoCodecToState(uint16_t codec);

android::bluetooth::State MapAgOpenStatusToState(tBTA_AG_STATUS status);

void LogIncomingAclStartEvent(const hci::Address& address);

void LogAclCompletionEvent(const hci::Address& address, hci::ErrorCode reason,
                           bool is_locally_initiated);

void LogLeAclCompletionEvent(const hci::Address& address, hci::ErrorCode reason,
                             bool is_locally_initiated);

void LogRemoteNameRequestCompletion(const RawAddress& raw_address, tHCI_STATUS hci_status);

void LogAclDisconnectionEvent(const hci::Address& address, hci::ErrorCode reason,
                              bool is_locally_initiated);

void LogAclAfterRemoteNameRequest(const RawAddress& raw_address, tBTM_STATUS status);

void LogAuthenticationComplete(const RawAddress& raw_address, tHCI_STATUS hci_status);

void LogSDPComplete(const RawAddress& raw_address, tBTA_STATUS status);

void LogLePairingFail(const RawAddress& raw_address, uint8_t failure_reason, bool is_outgoing);

/**
 * Logs GATT connect/disconnect status
 * @param address Address of the device
 * @param is_connect indicates connection or disconnection
 * @param reason the reason/status for the connection event
 */
void LogMetricLeConnectionStatus(hci::Address address, bool is_connect, hci::ErrorCode reason);

/**
 * Logs LE filter accept list events
 * @param address Address of the device
 * @param is_add indicates addition or removal of the device in the accept list
 */
void LogMetricLeDeviceInAcceptList(hci::Address address, bool is_connect);

/**
 * Logs GATT lifecycle events
 * @param address Address of the device
 * @param is_connect indicates connection or disconnection
 * @param is_direct indicates direct or background connection, ignored for disconnection
 */
void LogMetricLeConnectionLifecycle(hci::Address address, bool is_connect, bool is_direct);

/*Log LE Connection Rejected Event
 * @param address Address of the device
 */
void LogMetricLeConnectionRejected(hci::Address address);

/**
 * Logs the AG version in a HFP session
 * @param address of a device
 * @param version AG HFP version
 */
void LogMetricHfpAgVersion(hci::Address address, uint16_t version);

/**
 * Logs the HF version in a HFP session
 * @param address of a device
 * @param version HF HFP Version
 */
void LogMetricHfpHfVersion(hci::Address address, uint16_t version);

/**
 * Logs a RFCOMM channel failure in a HFP session
 * @param address of a device
 */
void LogMetricHfpRfcommChannelFail(hci::Address address);

/**
 * Logs a RFCOMM collision failure in a HFP session
 * @param address of a device
 */
void LogMetricHfpRfcommCollisionFail(hci::Address address);

/**
 * Logs a RFCOMM AG open failure in a HFP session
 * @param address of a device
 */
void LogMetricHfpRfcommAgOpenFail(hci::Address address);

/**
 * Logs a SLC failure in a HFP Session
 * @param address of a device
 */
void LogMetricHfpSlcFail(hci::Address address);

/**
 * Logs when a SCO link is created in HFP
 * @param address
 */
void LogMetricScoLinkCreated(hci::Address address);

/**
 * Logs when a SCO link is removed in HFP
 * @param address
 */
void LogMetricScoLinkRemoved(hci::Address address);

/**
 * Logs what codec the SCO is using
 * @param address
 * @param codec
 */
void LogMetricScoCodec(hci::Address address, uint16_t codec);

/**
 * Logs when IBluetoothAudioPort#startStream() is called when opening a SCO
 * @param address
 */
void LogMetricHfpStartStream(hci::Address address);

/**
 * Logs when IBluetoothAudioPort#stopStream() is called when closing a SCO
 * @param address
 */
void LogMetricHfpSuspendStream(hci::Address address);

/**
 * Logs when IBluetoothAudioProvider#streamStarted() is called to indicate SCO has opened
 * @param address
 */
void LogMetricHfpStreamStarted(hci::Address address);

/**
 * Logs the status when the AG connection is opened
 * @param address
 * @param status
 */
void LogMetricAgOpenStatus(hci::Address address, tBTA_AG_STATUS status);

/**
 * Logs AVDTP L2CAP channel events
 * @param address
 * @param event The type of L2CAP from AVDTP event
 * @param l2cap_result The result of the L2CAP events
 */
void LogAvdtpL2capEvent(hci::Address address, EventType event, tL2CAP_CONN l2cap_result);

/**
 * Logs AVDTP L2CAP channel error events
 * @param address
 * @param l2cap_result The result of the L2CAP events
 */
void LogAvdtpL2capErrorEvent(hci::Address address, tL2CAP_CONN l2cap_result);

/**
 * Logs AVDTP discovery failure events
 * @param address
 */
void LogAvdtpDiscFailEvent(hci::Address address);

/**
 * Logs AVDTP get capabilities failure events
 * @param address
 */
void LogAvdtpGetCapFailEvent(hci::Address address);

/**
 * Logs AVDTP signaling timeout events
 * @param address
 * @param error_code The error code of the AVDTP signaling timeout
 */
void LogAvdtpSignalingTimeoutEvent(hci::Address address, uint16_t error_code);

/**
 * Logs AVDTP open rejected events
 * @param address
 */
void LogAvdtpOpenRejectedEvent(hci::Address address);

/**
 * Logs AVDTP open failure events
 * @param address
 */
void LogAvdtpOpenFailEvent(hci::Address address);

/**
 * Logs AVDTP set config rejected events
 * @param address
 */
void LogAvdtpSetConfigRejectedEvent(hci::Address address);

/**
 * Logs AVDTP start reject events
 * @param address
 */
void LogAvdtpStartRejectEvent(hci::Address address);

/**
 * Logs AVDTP suspend reject events
 * @param address
 */
void LogAvdtpSuspendRejectEvent(hci::Address address);

/**
 * Logs AVDTP abort response send events
 * @param address
 */
void LogAvdtpAbortResponseSendEvent(hci::Address address);

/**
 * Logs AVDTP close response send events
 * @param address
 */
void LogAvdtpCloseResponseSendEvent(hci::Address address);

/**
 * Logs A2DP BTIF AV state change report events
 * @param address
 * @param result The result of the state change
 */
void LogA2dpBtifAvStateChangeEvent(hci::Address address, uint8_t result);
/**
 * Logs the start of a client RFCOMM connection, typically initiated by calling
 * `RFCOMM_CreateConnectionWithSecurity()`.
 * @param address
 * @param event
 * @param uid
 */
void LogRfcommNativeStartEvent(hci::Address address, EventType event, int uid);

/**
 * Logs when a RFCOMM connection is successfully completed
 * @param address
 * @param event
 * @param uid
 * @param is_client true if the connection is client, false if server
 */
void LogRfcommNativeConnectionCompleteEvent(hci::Address address, EventType event, bool is_client,
                                            int uid);

/**
 * Logs when a RFCOMM connection is disconnected
 * @param address
 * @param event
 * @param uid
 */
void LogRfcommNativeDisconnectionEvent(hci::Address address, EventType event, int uid);
/**
 * Logs when a RFCOMM socket connection is disconnected with socket error code
 * @param address
 * @param uid
 * @param error_code error code of the socket disconnection
 */
void LogRfcommSocketDisconnectionEvent(hci::Address address, int uid,
                                       btsock_error_code_t error_code);

/**
 * Logs when port returns failure result
 * @param address
 * @param event
 * @param uid
 * @param result port result of the disconnection
 */
void LogRfcommPortFailureEvent(hci::Address address, EventType event, int uid, tPORT_RESULT result);

/**
 * Logs RFCOMM L2CAP channel events
 * @param address
 * @param event
 * @param l2cap_result The result of the L2CAP events
 */
void LogRfcommL2capEvent(hci::Address address, EventType event, tL2CAP_CONN l2cap_result);

/**
 * Logs RFCOMM multiplexer events
 * @param address
 * @param event
 * @param state
 */
void LogRfcommMxEvent(hci::Address address, State state);

/**
 * Logs the result of a bond repair attempt.
 * @param address address of the remote device
 * @param state the resulting bond state
 * @param fail_reason the reason for the failure
 */
void LogBondRepairComplete(hci::Address address, bt_bond_state_t state, uint8_t fail_reason);

}  // namespace bluetooth::metrics
