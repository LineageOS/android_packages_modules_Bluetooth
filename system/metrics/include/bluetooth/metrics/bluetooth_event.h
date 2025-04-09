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

#include "bta/include/bta_sec_api.h"
#include "hci/address.h"
#include "hci/hci_packets.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "types/raw_address.h"

namespace bluetooth::metrics {

android::bluetooth::State MapErrorCodeToState(hci::ErrorCode reason);

android::bluetooth::State MapHfpVersionToState(uint16_t version);

android::bluetooth::State MapScoCodecToState(uint16_t codec);

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

}  // namespace bluetooth::metrics
