/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <bluetooth/types/uuid.h>

#include <optional>
#include <string>
#include <vector>

namespace bluetooth {
namespace ccp {

// Represents a single call instance.
struct Call {
  uint8_t index;
  uint8_t state;
  uint8_t flags;
  std::string uri;
  std::optional<std::string> friendly_name;

  bool operator==(const Call& other) const {
    return index == other.index && state == other.state && flags == other.flags &&
           uri == other.uri && friendly_name == other.friendly_name;
  }
};

// Service UUID for the Telephony Bearer Service (TBS) which CCP interacts with.
static const bluetooth::Uuid kTelephonyBearerServiceUuid = bluetooth::Uuid::From16Bit(0x184B);
static const bluetooth::Uuid kGenericTelephonyBearerServiceUuid =
        bluetooth::Uuid::From16Bit(0x184C);

// Telephony Bearer Service Characteristics.
static const bluetooth::Uuid kBearerProviderNameUuid = bluetooth::Uuid::From16Bit(0x2BB3);
static const bluetooth::Uuid kBearerUciUuid = bluetooth::Uuid::From16Bit(0x2BB4);
static const bluetooth::Uuid kBearerTechnologyUuid = bluetooth::Uuid::From16Bit(0x2BB5);
static const bluetooth::Uuid kBearerUriSchemesSupportedListUuid =
        bluetooth::Uuid::From16Bit(0x2BB6);
static const bluetooth::Uuid kBearerSignalStrengthUuid = bluetooth::Uuid::From16Bit(0x2BB7);
static const bluetooth::Uuid kBearerSignalStrengthReportingIntervalUuid =
        bluetooth::Uuid::From16Bit(0x2BB8);
static const bluetooth::Uuid kBearerListCurrentCallsUuid = bluetooth::Uuid::From16Bit(0x2BB9);
static const bluetooth::Uuid kStatusFlagsUuid = bluetooth::Uuid::From16Bit(0x2BBB);
static const bluetooth::Uuid kIncomingCallTargetBearerUriUuid = bluetooth::Uuid::From16Bit(0x2BBC);
static const bluetooth::Uuid kCallStateUuid = bluetooth::Uuid::From16Bit(0x2BBD);
static const bluetooth::Uuid kCallControlPointUuid = bluetooth::Uuid::From16Bit(0x2BBE);
static const bluetooth::Uuid kCallControlPointOptionalOpcodesUuid =
        bluetooth::Uuid::From16Bit(0x2BBF);
static const bluetooth::Uuid kTerminationReasonUuid = bluetooth::Uuid::From16Bit(0x2BC0);
static const bluetooth::Uuid kIncomingCallUuid = bluetooth::Uuid::From16Bit(0x2BC1);
static const bluetooth::Uuid kCallFriendlyNameUuid = bluetooth::Uuid::From16Bit(0x2BC2);
static const bluetooth::Uuid kContentControlIdUuid = bluetooth::Uuid::From16Bit(0x2BBA);

// Opcodes for the Call Control Point characteristic.
static constexpr uint8_t kCallControlPointOpcodeAcceptCall = 0x00;
static constexpr uint8_t kCallControlPointOpcodeTerminateCall = 0x01;
static constexpr uint8_t kCallControlPointOpcodeHoldCall = 0x02;
static constexpr uint8_t kCallControlPointOpcodeRetrieveCall = 0x03;
static constexpr uint8_t kCallControlPointOpcodePlaceCall = 0x04;
static constexpr uint8_t kCallControlPointOpcodeJoinCalls = 0x05;

// Result codes for Call Control Point operations.
enum class CallControlResultCode : uint8_t {
  SUCCESS = 0x00,
  OPCODE_NOT_SUPPORTED = 0x01,
  OPERATION_NOT_POSSIBLE = 0x02,
  INVALID_CALL_INDEX = 0x03,
  STATE_MISMATCH = 0x04,
  LACK_OF_RESOURCES = 0x05,
  INVALID_OUTGOING_URI = 0x06,
};

// Result codes for Termination Reason characteristic.
enum class TerminationReasonCode : uint8_t {
  INVALID_URI = 0x00,
  CALL_FAILED = 0x01,
  REMOTE_ENDED_CALL = 0x02,
  SERVER_ENDED_CALL = 0x03,
  LINE_BUSY = 0x04,
  NETWORK_CONGESTION = 0x05,
  CLIENT_TERMINATED_CALL = 0x06,
  NO_SERVICE = 0x07,
  NO_ANSWER = 0x08,
  UNSPECIFIED = 0x09,
};

}  // namespace ccp
}  // namespace bluetooth
