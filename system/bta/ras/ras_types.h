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

#pragma once

#include "bluetooth/uuid.h"

namespace ras {
static const uint16_t kFeatureSize = 0x04;
static const uint16_t kRingingCounterSize = 0x02;
static const uint16_t kCccValueSize = 0x02;

namespace uuid {
static const uint16_t kRangingService16Bit = 0x7F7D;
static const uint16_t kRasFeaturesCharacteristic16bit = 0x7F7C;
static const uint16_t kRasRealTimeRangingDataCharacteristic16bit = 0x7F7B;
static const uint16_t kRasOnDemandDataCharacteristic16bit = 0x7F7A;
static const uint16_t kRasControlPointCharacteristic16bit = 0x7F79;
static const uint16_t kRasRangingDataReadyCharacteristic16bit = 0x7F78;
static const uint16_t kRasRangingDataOverWrittenCharacteristic16bit = 0x7F77;
static const uint16_t kClientCharacteristicConfiguration16bit = 0x2902;

static const bluetooth::Uuid kRangingService =
    bluetooth::Uuid::From16Bit(kRangingService16Bit);
static const bluetooth::Uuid kRasFeaturesCharacteristic =
    bluetooth::Uuid::From16Bit(kRasFeaturesCharacteristic16bit);
static const bluetooth::Uuid kRasRealTimeRangingDataCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRealTimeRangingDataCharacteristic16bit);
static const bluetooth::Uuid kRasOnDemandDataCharacteristic =
    bluetooth::Uuid::From16Bit(kRasOnDemandDataCharacteristic16bit);
static const bluetooth::Uuid kRasControlPointCharacteristic =
    bluetooth::Uuid::From16Bit(kRasControlPointCharacteristic16bit);
static const bluetooth::Uuid kRasRangingDataReadyCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRangingDataReadyCharacteristic16bit);
static const bluetooth::Uuid kRasRangingDataOverWrittenCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRangingDataOverWrittenCharacteristic16bit);
static const bluetooth::Uuid kClientCharacteristicConfiguration =
    bluetooth::Uuid::From16Bit(kClientCharacteristicConfiguration16bit);

std::string getUuidName(const bluetooth::Uuid& uuid);

}  // namespace uuid

namespace feature {
static const uint32_t kRealTimeRangingData = 0x01;
static const uint32_t kRetrieveLostRangingDataSegments = 0x02;
static const uint32_t kAbortOperation = 0x04;
static const uint32_t kFilterRangingData = 0x08;
static const uint32_t kPctPhaseFormat = 0xA0;
}  // namespace feature

enum class Opcode : uint8_t {
  GET_RANGING_DATA = 0x00,
  ACK_RANGING_DATA = 0x01,
  RETRIEVE_LOST_RANGING_DATA_SEGMENTS = 0x02,
  ABORT_OPERATION = 0x03,
  FILTER = 0x04,
  PCT_FORMAT = 0x05,
};

static const uint8_t OPERATOR_NULL = 0x00;

std::string GetOpcodeText(Opcode opcode);

enum class EventCode : uint8_t {
  COMPLETE_RANGING_DATA_RESPONSE = 0x00,
  COMPLETE_LOST_RANGING_DATA_SEGMENT_RESPONSE = 0x01,
  RESPONSE_CODE = 0x02,
};

enum class ResponseCodeValue : uint8_t {
  RESERVED_FOR_FUTURE_USE = 0x00,
  SUCCESS = 0x01,
  OP_CODE_NOT_SUPPORTED = 0x02,
  INVALID_OPERATOR = 0x03,
  OPERATOR_NOT_SUPPORTED = 0x04,
  INVALID_OPERAND = 0x05,
  ABORT_UNSUCCESSFUL = 0x06,
  PROCEDURE_NOT_COMPLETED = 0x07,
  OPERAND_NOT_SUPPORTED = 0x08,
  NO_RECORDS_FOUND = 0x09,
};

std::string GetResponseOpcodeValueText(ResponseCodeValue response_code_value);

struct ControlPointCommand {
  Opcode opcode_;
  uint8_t parameter_[4];
};

struct ControlPointResponse {
  EventCode event_code_;
  uint8_t parameter_[4];
};

bool ParseControlPointCommand(ControlPointCommand* command,
                              const uint8_t* value, uint16_t len);

}  // namespace ras
