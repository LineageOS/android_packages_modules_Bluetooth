/*
 * Copyright 2026 The Android Open Source Project
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

 #include <cstdint>
 #include <cstring>
 #include <string>

 #include "bluetooth/log.h"
 #include "bta/vap/vap_server_types.h"
 #include "bluetooth/types/uuid.h"

 using namespace bluetooth;
 using namespace ::vap;
 using namespace ::vap::uuid;

 namespace vap {
 std::string uuid::getUuidName(const bluetooth::Uuid& uuid) {
   switch (uuid.As16Bit()) {
     case kVaNameCharacteristic16bit:
       return "VA Name";
     case kVaUuidCharacteristic16bit:
       return "VA UUID";
     case kVasControlPointCharacteristic16bit:
       return "VAS Control Point";
     case kVaCcidCharacteristic16bit:
       return "VA CCID";
     case kVaSessionStateCharacteristic16bit:
       return "VA Session State";
     case kVaSupportedFeaturesCharacteristic16bit:
       return "VA Supported Features";
     case kClientCharacteristicConfiguration16bit:
       return "Client Characteristic Configuration";
     default:
       return "Unknown UUID";
   }
 }

 ControlPointResponse ValidateControlPointOperation(ControlPointCommand* command,
                                                    const uint8_t* value,
                                                    uint16_t len,
                                                    VaSessionState va_session_state) {
    command->ctp_opcode_ = static_cast<CtpOpcode>(value[0]);
    command->isValid_ = true;
    ControlPointResponse cp_rsp;
    cp_rsp.ctp_resp_opcode_ = CtpRespOpcode::RESPONSE_CODE;
    cp_rsp.code_value_ = ResponseCodeValue::SUCCESS;

    log::debug("va_session_state:{}, cp op:{}",
        GetVaSessionStateText(va_session_state), GetCtpOpcodeText(command->ctp_opcode_));

    // Check for minimum expected length
    switch (value[0]) {
      case (uint8_t)CtpOpcode::INITIALIZE_VA_SESSION:
      case (uint8_t)CtpOpcode::START_VA_SESSION:
      case (uint8_t)CtpOpcode::STOP_VA_SESSION:
      if (len != 1) {
        command->isValid_ = false;
        cp_rsp.code_value_ = ResponseCodeValue::OP_CODE_NOT_SUPPORTED;
      }
      break;
      default:
        log::warn("unknown CTP opcode 0x{:02x}", value[0]);
        command->isValid_ = false;
        cp_rsp.code_value_ = ResponseCodeValue::OP_CODE_NOT_SUPPORTED;
    }

    // Check if VA session state is valid for the CP operation
    switch (value[0]) {
      case (uint8_t)CtpOpcode::INITIALIZE_VA_SESSION:
        if (va_session_state == VaSessionState::VA_SESSION_UNAVAILABLE) {
          cp_rsp.code_value_ = ResponseCodeValue::INVALID_SESSION_STATE;
          command->isValid_ = false;
        }
        break;
      case (uint8_t)CtpOpcode::START_VA_SESSION:
        if (va_session_state != VaSessionState::VA_SESSION_READY) {
          cp_rsp.code_value_ = ResponseCodeValue::INVALID_SESSION_STATE;
          command->isValid_ = false;
        }
        break;
      case (uint8_t)CtpOpcode::STOP_VA_SESSION:
        if (va_session_state != VaSessionState::VA_SESSION_ACTIVE) {
          cp_rsp.code_value_ = ResponseCodeValue::INVALID_SESSION_STATE;
          command->isValid_ = false;
        }
        break;
    }

    log::info(" command_valid:{},cp_rsp event_code:{}, cp_rsp code_value:{}",
        command->isValid_, static_cast<int>(cp_rsp.ctp_resp_opcode_),
        GetResponseCodeValueText(cp_rsp.code_value_));
    return cp_rsp;
}

 std::string GetCtpOpcodeText(CtpOpcode ctp_opcode) {
   switch (ctp_opcode) {
     case CtpOpcode::INITIALIZE_VA_SESSION:
       return "INITIALIZE_VA_SESSION";
     case CtpOpcode::START_VA_SESSION:
       return "START_VA_SESSION";
     case CtpOpcode::STOP_VA_SESSION:
       return "STOP_VA_SESSION";
     default:
       return "Unknown CtpOpcode";
   }
 }

 std::string GetResponseCodeValueText(ResponseCodeValue response_code_value) {
   switch (response_code_value) {
     case ResponseCodeValue::RESERVED_FOR_FUTURE_USE:
       return "RESERVED_FOR_FUTURE_USE";
     case ResponseCodeValue::SUCCESS:
       return "SUCCESS";
     case ResponseCodeValue::OP_CODE_NOT_SUPPORTED:
       return "OP_CODE_NOT_SUPPORTED";
     case ResponseCodeValue::OPERATION_FALIED:
       return "OPERATION_FALIED";
     case ResponseCodeValue::INVALID_SESSION_STATE:
       return "INVALID_SESSION_STATE";
     default:
       return "Reserved for Future Use";
   }
 }

 std::string GetVaSessionStateText(VaSessionState va_session_state) {
   switch (va_session_state) {
     case VaSessionState::VA_SESSION_UNAVAILABLE:
       return "VA_SESSION_UNAVAILABLE";
     case VaSessionState::VA_SESSION_RESET:
       return "VA_SESSION_RESET";
     case VaSessionState::VA_SESSION_READY:
       return "VA_SESSION_READY";
     case VaSessionState::VA_SESSION_ACTIVE:
       return "VA_SESSION_ACTIVE";
     default:
       return "Unknown VA Session";
   }
 }

 bool IsVapServiceCharacteristic(const bluetooth::Uuid& uuid) {
   if (!uuid.Is16Bit()) {
     return false;
   }
   switch (uuid.As16Bit()) {
     case kVaNameCharacteristic16bit:
     case kVaUuidCharacteristic16bit:
     case kVasControlPointCharacteristic16bit:
     case kVaCcidCharacteristic16bit:
     case kVaSessionStateCharacteristic16bit:
     case kVaSupportedFeaturesCharacteristic16bit:
       return true;
     default:
       return false;
   }
 }

 }  // namespace vap
