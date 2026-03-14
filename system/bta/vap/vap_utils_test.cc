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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/vap/vap_server_types.h"
#include "bluetooth/types/uuid.h"

namespace vap {
namespace uuid {
std::string getUuidName(const bluetooth::Uuid& uuid);
}  // namespace uuid
ControlPointResponse ValidateControlPointOperation(ControlPointCommand* command,
                                                   const uint8_t* value, uint16_t len,
                                                   VaSessionState va_session_state);
std::string GetCtpOpcodeText(CtpOpcode ctp_opcode);
std::string GetResponseCodeValueText(ResponseCodeValue response_code_value);
std::string GetVaSessionStateText(VaSessionState va_session_state);
bool IsVapServiceCharacteristic(const bluetooth::Uuid& uuid);
}  // namespace vap

using namespace ::testing;
using namespace ::vap;
using namespace ::vap::uuid;

// --- Tests for vap_server_utils.cc ---

TEST(VapServerUtilsTest, GetUuidName) {
  EXPECT_EQ(::vap::uuid::getUuidName(kVaNameCharacteristic), "VA Name");
  EXPECT_EQ(::vap::uuid::getUuidName(kVaUuidCharacteristic), "VA UUID");
  EXPECT_EQ(::vap::uuid::getUuidName(kVasControlPointCharacteristic), "VAS Control Point");
  EXPECT_EQ(::vap::uuid::getUuidName(kVaCcidCharacteristic), "VA CCID");
  EXPECT_EQ(::vap::uuid::getUuidName(kVaSessionStateCharacteristic), "VA Session State");
  EXPECT_EQ(::vap::uuid::getUuidName(kVaSupportedFeaturesCharacteristic), "VA Supported Features");
  EXPECT_EQ(::vap::uuid::getUuidName(kClientCharacteristicConfiguration),
            "Client Characteristic Configuration");
  EXPECT_EQ(::vap::uuid::getUuidName(bluetooth::Uuid::From16Bit(0x0000)), "Unknown UUID");
}

TEST(VapServerUtilsTest, ValidateControlPointOperation_Valid) {
  ControlPointCommand command;

  uint8_t init_value[] = {(uint8_t)CtpOpcode::INITIALIZE_VA_SESSION};
  auto response =
          ValidateControlPointOperation(&command, init_value, 1, VaSessionState::VA_SESSION_RESET);
  EXPECT_TRUE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::SUCCESS);
}

TEST(VapServerUtilsTest, ValidateControlPointOperation_InvalidState) {
  ControlPointCommand command;
  uint8_t start_value[] = {(uint8_t)CtpOpcode::START_VA_SESSION};
  auto response = ValidateControlPointOperation(&command, start_value, 1,
                                                VaSessionState::VA_SESSION_ACTIVE);
  EXPECT_FALSE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::INVALID_SESSION_STATE);

  uint8_t stop_value[] = {(uint8_t)CtpOpcode::STOP_VA_SESSION};
  response =
          ValidateControlPointOperation(&command, stop_value, 1, VaSessionState::VA_SESSION_READY);
  EXPECT_FALSE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::INVALID_SESSION_STATE);

  uint8_t init_value[] = {(uint8_t)CtpOpcode::INITIALIZE_VA_SESSION};
  response = ValidateControlPointOperation(&command, init_value, 1,
                                           VaSessionState::VA_SESSION_UNAVAILABLE);
  EXPECT_FALSE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::INVALID_SESSION_STATE);
}

TEST(VapServerUtilsTest, ValidateControlPointOperation_InvalidOpcode) {
  ControlPointCommand command;
  uint8_t invalid_value[] = {0xFF};  // Invalid opcode
  auto response = ValidateControlPointOperation(&command, invalid_value, 1,
                                                VaSessionState::VA_SESSION_READY);
  EXPECT_FALSE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::OP_CODE_NOT_SUPPORTED);
}

TEST(VapServerUtilsTest, ValidateControlPointOperation_InvalidLength) {
  ControlPointCommand command;
  uint8_t value[] = {(uint8_t)CtpOpcode::START_VA_SESSION, 0x00};
  auto response =
          ValidateControlPointOperation(&command, value, 2, VaSessionState::VA_SESSION_READY);
  EXPECT_FALSE(command.isValid_);
  EXPECT_EQ(response.code_value_, ResponseCodeValue::OP_CODE_NOT_SUPPORTED);
}

TEST(VapServerUtilsTest, GetEnumText) {
  EXPECT_EQ(GetCtpOpcodeText(CtpOpcode::START_VA_SESSION), "START_VA_SESSION");
  EXPECT_EQ(GetCtpOpcodeText((CtpOpcode)0xFF), "Unknown CtpOpcode");

  EXPECT_EQ(GetResponseCodeValueText(ResponseCodeValue::SUCCESS), "SUCCESS");
  EXPECT_EQ(GetResponseCodeValueText((ResponseCodeValue)0xFF), "Reserved for Future Use");

  EXPECT_EQ(GetVaSessionStateText(VaSessionState::VA_SESSION_ACTIVE), "VA_SESSION_ACTIVE");
  EXPECT_EQ(GetVaSessionStateText((VaSessionState)0xFF), "Unknown VA Session");
}

TEST(VapServerUtilsTest, IsVapServiceCharacteristic) {
  EXPECT_TRUE(IsVapServiceCharacteristic(kVaNameCharacteristic));
  EXPECT_TRUE(IsVapServiceCharacteristic(kVaUuidCharacteristic));
  EXPECT_TRUE(IsVapServiceCharacteristic(kVasControlPointCharacteristic));
  EXPECT_TRUE(IsVapServiceCharacteristic(kVaCcidCharacteristic));
  EXPECT_TRUE(IsVapServiceCharacteristic(kVaSessionStateCharacteristic));
  EXPECT_TRUE(IsVapServiceCharacteristic(kVaSupportedFeaturesCharacteristic));
  EXPECT_FALSE(IsVapServiceCharacteristic(kClientCharacteristicConfiguration));
  EXPECT_FALSE(IsVapServiceCharacteristic(bluetooth::Uuid::From16Bit(0x1234)));
  EXPECT_FALSE(IsVapServiceCharacteristic(bluetooth::Uuid::From128BitBE({0x01})));
}