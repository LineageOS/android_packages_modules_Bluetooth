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

#pragma once

#include "bta/include/bta_sec_api.h"
#include "hci/address.h"
#include "hci/hci_packets.h"
#include "os/metrics.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace metrics {

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

android::bluetooth::State MapErrorCodeToState(hci::ErrorCode reason);

android::bluetooth::State MapHfpVersionToState(uint16_t version);

android::bluetooth::State MapScoCodecToState(uint16_t codec);

}  // namespace metrics
}  // namespace bluetooth
