/*
 * Copyright 2025 The Android Open Source Project
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

 #include "bluetooth/types/uuid.h"

 namespace vaps {
 static const uint16_t kCccValueSize = 0x02;
 static const uint16_t kVaSessionStateSize = 0x01;
 static const uint16_t kVaeUuidSize = 16;

 namespace uuid {
 static const uint16_t kVapsService16Bit = 0x7F64;
 static const uint16_t kVaeNameCharacteristic16bit = 0x7F63;
 static const uint16_t kVaeUuidCharacteristic16bit = 0x7F62;
 static const uint16_t kVaeControlPointCharacteristic16bit = 0x7F61;
 static const uint16_t kVaeCcidCharacteristic16bit = 0x7F5F;
 static const uint16_t kVaSessionStateCharacteristic16bit = 0x7F5E;
 static const uint16_t kClientCharacteristicConfiguration16bit = 0x2902;
 static const uint16_t kDefaultGattMtu = 23;

 static const bluetooth::Uuid kVapsService = bluetooth::Uuid::From16Bit(kVapsService16Bit);
 static const bluetooth::Uuid kVaeNameCharacteristic =
         bluetooth::Uuid::From16Bit(kVaeNameCharacteristic16bit);
 static const bluetooth::Uuid kVaeUuidCharacteristic =
         bluetooth::Uuid::From16Bit(kVaeUuidCharacteristic16bit);
 static const bluetooth::Uuid kVaeControlPointCharacteristic =
         bluetooth::Uuid::From16Bit(kVaeControlPointCharacteristic16bit);
 static const bluetooth::Uuid kVaeCcidCharacteristic =
         bluetooth::Uuid::From16Bit(kVaeCcidCharacteristic16bit);
 static const bluetooth::Uuid kVaSessionStateCharacteristic =
         bluetooth::Uuid::From16Bit(kVaSessionStateCharacteristic16bit);
 static const bluetooth::Uuid kClientCharacteristicConfiguration =
         bluetooth::Uuid::From16Bit(kClientCharacteristicConfiguration16bit);

 std::string getUuidName(const bluetooth::Uuid& uuid);

 }  // namespace uuid

 enum class VaSessionState : uint8_t {
   VA_SESSION_UNAVAILABLE = 0x00,
   VA_SESSION_RESET = 0x01,
   VA_SESSION_READY = 0x02,
   VA_SESSION_ACTIVE = 0x03,
 };

 std::string GetVaSessionStateText(VaSessionState va_session_state);

 enum class CtpOpcode : uint8_t {
   INITIALIZE_VA_SESSION = 0x00,
   START_VA_SESSION = 0x01,
   STOP_VA_SESSION = 0x02,
 };

 std::string GetCtpOpcodeText(CtpOpcode ctp_opcode);

 enum class CtpRespOpcode : uint8_t {
   RESPONSE_CODE = 0x00,
 };

 enum class ResponseCodeValue : uint8_t {
   RESERVED_FOR_FUTURE_USE = 0x00,
   SUCCESS = 0x01,
   OP_CODE_NOT_SUPPORTED = 0x02,
   OPERATION_FALIED = 0x03,
   INVALID_SESSION_STATE = 0x04,
 };

 std::string GetResponseCodeValueText(ResponseCodeValue response_code_value);

 struct ControlPointCommand {
   CtpOpcode ctp_opcode_;
   uint8_t parameter_[4];
   bool isValid_;
 };

 struct ControlPointResponse {
   CtpRespOpcode ctp_resp_opcode_;
   ResponseCodeValue code_value_;
 };

 ControlPointResponse ValidateControlPointOperation(ControlPointCommand* command,
                                                    const uint8_t* value,
                                                    uint16_t len,
                                                    VaSessionState va_session_state);

 bool IsVapsServiceCharacteristic(const bluetooth::Uuid& uuid);

 }  // namespace vaps
