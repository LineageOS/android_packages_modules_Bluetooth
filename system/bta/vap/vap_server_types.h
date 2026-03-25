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

 #pragma once

 #include "bluetooth/types/uuid.h"

 namespace vap {
 static const uint16_t kCccValueSize = 0x02;
 static const uint16_t kVaSessionStateSize = 0x01;
 static const uint16_t kVaUuidSize = 16;

 namespace uuid {
 static const uint16_t kGenericVasService16Bit = 0x185F;
 static const uint16_t kVaNameCharacteristic16bit = 0x2C31;
 static const uint16_t kVaUuidCharacteristic16bit = 0x2C32;
 static const uint16_t kVasControlPointCharacteristic16bit = 0x2C33;
 static const uint16_t kVaCcidCharacteristic16bit = 0x2BBA;
 static const uint16_t kVaSessionStateCharacteristic16bit = 0x2C35;
 static const uint16_t kVaSupportedFeaturesCharacteristic16bit = 0x2C38;
 static const uint16_t kClientCharacteristicConfiguration16bit = 0x2902;
 static const uint16_t kDefaultGattMtu = 23;

 static constexpr bluetooth::Uuid kGenericVasService =
         bluetooth::Uuid::From16Bit(kGenericVasService16Bit);
 static constexpr bluetooth::Uuid kVaNameCharacteristic =
         bluetooth::Uuid::From16Bit(kVaNameCharacteristic16bit);
 static constexpr bluetooth::Uuid kVaUuidCharacteristic =
         bluetooth::Uuid::From16Bit(kVaUuidCharacteristic16bit);
 static constexpr bluetooth::Uuid kVasControlPointCharacteristic =
         bluetooth::Uuid::From16Bit(kVasControlPointCharacteristic16bit);
 static constexpr bluetooth::Uuid kVaCcidCharacteristic =
         bluetooth::Uuid::From16Bit(kVaCcidCharacteristic16bit);
 static constexpr bluetooth::Uuid kVaSessionStateCharacteristic =
         bluetooth::Uuid::From16Bit(kVaSessionStateCharacteristic16bit);
 static constexpr bluetooth::Uuid kVaSupportedFeaturesCharacteristic =
         bluetooth::Uuid::From16Bit(kVaSupportedFeaturesCharacteristic16bit);
 static constexpr bluetooth::Uuid kClientCharacteristicConfiguration =
         bluetooth::Uuid::From16Bit(kClientCharacteristicConfiguration16bit);

 std::string getUuidName(const bluetooth::Uuid& uuid);

 }  // namespace uuid

 enum class VaSessionState : uint8_t {
   VA_SESSION_RESET = 0x00,
   VA_SESSION_UNAVAILABLE = 0x01,
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

 bool IsVapServiceCharacteristic(const bluetooth::Uuid& uuid);

 }  // namespace vap
