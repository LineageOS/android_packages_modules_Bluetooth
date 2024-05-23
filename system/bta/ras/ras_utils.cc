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

#include <base/functional/bind.h>

#include <unordered_map>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "os/log.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/gap_api.h"
#include "types/bluetooth/uuid.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;

namespace ras {
std::string uuid::getUuidName(const bluetooth::Uuid& uuid) {
  switch (uuid.As16Bit()) {
    case kRangingService16Bit:
      return "Ranging Service";
    case kRasFeaturesCharacteristic16bit:
      return "RAS Features";
    case kRasRealTimeRangingDataCharacteristic16bit:
      return "Real-time Ranging Data";
    case kRasOnDemandDataCharacteristic16bit:
      return "On-demand Ranging Data";
    case kRasControlPointCharacteristic16bit:
      return "RAS Control Point (RAS-CP)";
    case kRasRangingDataReadyCharacteristic16bit:
      return "Ranging Data Ready";
    case kRasRangingDataOverWrittenCharacteristic16bit:
      return "Ranging Data Overwritten";
    case kClientCharacteristicConfiguration16bit:
      return "Client Characteristic Configuration";
    default:
      return "Unknown UUID";
  }
}

bool ParseControlPointCommand(ControlPointCommand* command,
                              const uint8_t* value, uint16_t len) {
  // Check for minimum expected length
  switch (value[0]) {
    case (uint8_t)Opcode::ABORT_OPERATION:
      break;
    case (uint8_t)Opcode::PCT_FORMAT: {
      if (len < 2) {
        return false;
      }
    } break;
    case (uint8_t)Opcode::GET_RANGING_DATA:
    case (uint8_t)Opcode::ACK_RANGING_DATA:
    case (uint8_t)Opcode::FILTER: {
      if (len < 3) {
        return false;
      }
    } break;
    case (uint8_t)Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS: {
      if (len < 5) {
        return false;
      }
    } break;
    default:
      log::warn("unknown opcode 0x{:02x}", value[0]);
      return false;
  }
  command->opcode_ = static_cast<Opcode>(value[0]);
  std::memcpy(command->parameter_, value + 1, len - 1);
  return true;
}

std::string GetOpcodeText(Opcode opcode) {
  switch (opcode) {
    case Opcode::GET_RANGING_DATA:
      return "GET_RANGING_DATA";
    case Opcode::ACK_RANGING_DATA:
      return "ACK_RANGING_DATA";
    case Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS:
      return "RETRIEVE_LOST_RANGING_DATA_SEGMENTS";
    case Opcode::ABORT_OPERATION:
      return "ABORT_OPERATION";
    case Opcode::FILTER:
      return "FILTER";
    case Opcode::PCT_FORMAT:
      return "PCT_FORMAT";
    default:
      return "Unknown Opcode";
  }
}

std::string GetResponseOpcodeValueText(ResponseCodeValue response_code_value) {
  switch (response_code_value) {
    case ResponseCodeValue::RESERVED_FOR_FUTURE_USE:
      return "RESERVED_FOR_FUTURE_USE";
    case ResponseCodeValue::SUCCESS:
      return "SUCCESS";
    case ResponseCodeValue::OP_CODE_NOT_SUPPORTED:
      return "OP_CODE_NOT_SUPPORTED";
    case ResponseCodeValue::INVALID_OPERATOR:
      return "INVALID_OPERATOR";
    case ResponseCodeValue::OPERATOR_NOT_SUPPORTED:
      return "OPERATOR_NOT_SUPPORTED";
    case ResponseCodeValue::INVALID_OPERAND:
      return "INVALID_OPERAND";
    case ResponseCodeValue::ABORT_UNSUCCESSFUL:
      return "ABORT_UNSUCCESSFUL";
    case ResponseCodeValue::PROCEDURE_NOT_COMPLETED:
      return "PROCEDURE_NOT_COMPLETED";
    case ResponseCodeValue::OPERAND_NOT_SUPPORTED:
      return "OPERAND_NOT_SUPPORTED";
    case ResponseCodeValue::NO_RECORDS_FOUND:
      return "NO_RECORDS_FOUND";
    default:
      return "Unknown Opcode";
  }
}

bool IsRangingServiceCharacteristic(const bluetooth::Uuid& uuid) {
  switch (uuid.As16Bit()) {
    case kRangingService16Bit:
    case kRasFeaturesCharacteristic16bit:
    case kRasRealTimeRangingDataCharacteristic16bit:
    case kRasOnDemandDataCharacteristic16bit:
    case kRasControlPointCharacteristic16bit:
    case kRasRangingDataReadyCharacteristic16bit:
    case kRasRangingDataOverWrittenCharacteristic16bit:
      return true;
    default:
      return false;
  }
}

}  // namespace ras
