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

#include <gtest/gtest.h>

#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"

class RasUtilsTest : public ::testing::Test {};

TEST(RasUtilsTest, GetUuidName) {
  // Test known UUIDs
  EXPECT_EQ(ras::uuid::getUuidName(bluetooth::Uuid::From16Bit(ras::uuid::kRangingService16Bit)),
            "Ranging Service");
  EXPECT_EQ(ras::uuid::getUuidName(
                    bluetooth::Uuid::From16Bit(ras::uuid::kRasFeaturesCharacteristic16bit)),
            "RAS Features");
  EXPECT_EQ(ras::uuid::getUuidName(bluetooth::Uuid::From16Bit(
                    ras::uuid::kRasRealTimeRangingDataCharacteristic16bit)),
            "Real-time Ranging Data");
  EXPECT_EQ(ras::uuid::getUuidName(
                    bluetooth::Uuid::From16Bit(ras::uuid::kRasOnDemandDataCharacteristic16bit)),
            "On-demand Ranging Data");
  EXPECT_EQ(ras::uuid::getUuidName(
                    bluetooth::Uuid::From16Bit(ras::uuid::kRasControlPointCharacteristic16bit)),
            "RAS Control Point (RAS-CP)");
  EXPECT_EQ(ras::uuid::getUuidName(
                    bluetooth::Uuid::From16Bit(ras::uuid::kRasRangingDataReadyCharacteristic16bit)),
            "Ranging Data Ready");
  EXPECT_EQ(ras::uuid::getUuidName(bluetooth::Uuid::From16Bit(
                    ras::uuid::kRasRangingDataOverWrittenCharacteristic16bit)),
            "Ranging Data Overwritten");
  EXPECT_EQ(ras::uuid::getUuidName(
                    bluetooth::Uuid::From16Bit(ras::uuid::kClientCharacteristicConfiguration16bit)),
            "Client Characteristic Configuration");

  // Test unknown UUID
  EXPECT_EQ(ras::uuid::getUuidName(bluetooth::Uuid("00001101-0000-1000-8000-00805F9B34FB")),
            "Unknown UUID");
}

TEST(RasUtilsTest, ParseControlPointCommand) {
  // Test successful parsing of valid commands
  uint8_t valid_data_get_ranging_data[] = {0x00, 0x01, 0x02};
  ras::ControlPointCommand command_get_ranging_data;
  ASSERT_TRUE(ras::ParseControlPointCommand(&command_get_ranging_data, valid_data_get_ranging_data,
                                            sizeof(valid_data_get_ranging_data)));
  ASSERT_EQ(command_get_ranging_data.opcode_, ras::Opcode::GET_RANGING_DATA);
  ASSERT_EQ(command_get_ranging_data.parameter_[0], 0x01);
  ASSERT_EQ(command_get_ranging_data.parameter_[1], 0x02);

  uint8_t valid_data_ack_ranging_data[] = {0x01, 0x03, 0x04};
  ras::ControlPointCommand command_ack_ranging_data;
  ASSERT_TRUE(ras::ParseControlPointCommand(&command_ack_ranging_data, valid_data_ack_ranging_data,
                                            sizeof(valid_data_ack_ranging_data)));
  ASSERT_EQ(command_ack_ranging_data.opcode_, ras::Opcode::ACK_RANGING_DATA);
  ASSERT_EQ(command_ack_ranging_data.parameter_[0], 0x03);
  ASSERT_EQ(command_ack_ranging_data.parameter_[1], 0x04);

  uint8_t valid_data_retrieve_lost_ranging_data_segments[] = {0x02, 0x05, 0x06, 0x07, 0x08};
  ras::ControlPointCommand command_retrieve_lost_ranging_data_segments;
  ASSERT_TRUE(
          ras::ParseControlPointCommand(&command_retrieve_lost_ranging_data_segments,
                                        valid_data_retrieve_lost_ranging_data_segments,
                                        sizeof(valid_data_retrieve_lost_ranging_data_segments)));
  ASSERT_EQ(command_retrieve_lost_ranging_data_segments.opcode_,
            ras::Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS);
  ASSERT_EQ(command_retrieve_lost_ranging_data_segments.parameter_[0], 0x05);
  ASSERT_EQ(command_retrieve_lost_ranging_data_segments.parameter_[1], 0x06);
  ASSERT_EQ(command_retrieve_lost_ranging_data_segments.parameter_[2], 0x07);
  ASSERT_EQ(command_retrieve_lost_ranging_data_segments.parameter_[3], 0x08);

  uint8_t valid_data_abort_operation[] = {0x03};
  ras::ControlPointCommand command_abort_operation;
  ASSERT_TRUE(ras::ParseControlPointCommand(&command_abort_operation, valid_data_abort_operation,
                                            sizeof(valid_data_abort_operation)));
  ASSERT_EQ(command_abort_operation.opcode_, ras::Opcode::ABORT_OPERATION);

  uint8_t valid_data_filter[] = {0x04, 0x09, 0x0A};
  ras::ControlPointCommand command_filter;
  ASSERT_TRUE(ras::ParseControlPointCommand(&command_filter, valid_data_filter,
                                            sizeof(valid_data_filter)));
  ASSERT_EQ(command_filter.opcode_, ras::Opcode::FILTER);
  ASSERT_EQ(command_filter.parameter_[0], 0x09);
  ASSERT_EQ(command_filter.parameter_[1], 0x0A);

  // Test failed parsing of invalid commands
  uint8_t invalid_data_short_get_ranging_data[] = {0x00, 0x01};
  ras::ControlPointCommand command_invalid_short_get_ranging_data;
  ASSERT_FALSE(ras::ParseControlPointCommand(&command_invalid_short_get_ranging_data,
                                             invalid_data_short_get_ranging_data,
                                             sizeof(invalid_data_short_get_ranging_data)));

  uint8_t invalid_data_long_get_ranging_data[] = {0x00, 0x01, 0x02, 0x03};
  ras::ControlPointCommand command_invalid_long_get_ranging_data;
  ASSERT_FALSE(ras::ParseControlPointCommand(&command_invalid_long_get_ranging_data,
                                             invalid_data_long_get_ranging_data,
                                             sizeof(invalid_data_long_get_ranging_data)));

  uint8_t invalid_data_unknown_opcode[] = {0x05, 0x01, 0x02};
  ras::ControlPointCommand command_invalid_unknown_opcode;
  ASSERT_FALSE(ras::ParseControlPointCommand(&command_invalid_unknown_opcode,
                                             invalid_data_unknown_opcode,
                                             sizeof(invalid_data_unknown_opcode)));
}

TEST(RasUtilsTest, GetOpcodeText) {
  // Test known opcodes
  EXPECT_EQ(ras::GetOpcodeText(ras::Opcode::GET_RANGING_DATA), "GET_RANGING_DATA");
  EXPECT_EQ(ras::GetOpcodeText(ras::Opcode::ACK_RANGING_DATA), "ACK_RANGING_DATA");
  EXPECT_EQ(ras::GetOpcodeText(ras::Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS),
            "RETRIEVE_LOST_RANGING_DATA_SEGMENTS");
  EXPECT_EQ(ras::GetOpcodeText(ras::Opcode::ABORT_OPERATION), "ABORT_OPERATION");
  EXPECT_EQ(ras::GetOpcodeText(ras::Opcode::FILTER), "FILTER");

  // Test unknown opcode (casting an invalid value to Opcode)
  EXPECT_EQ(ras::GetOpcodeText(static_cast<ras::Opcode>(0x05)), "Unknown Opcode");
}

TEST(RasUtilsTest, GetResponseOpcodeValueText) {
  // Test known response code values
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::RESERVED_FOR_FUTURE_USE),
            "RESERVED_FOR_FUTURE_USE");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::SUCCESS), "SUCCESS");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::OP_CODE_NOT_SUPPORTED),
            "OP_CODE_NOT_SUPPORTED");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::INVALID_PARAMETER),
            "INVALID_PARAMETER");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::PERSISTED), "PERSISTED");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::ABORT_UNSUCCESSFUL),
            "ABORT_UNSUCCESSFUL");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::PROCEDURE_NOT_COMPLETED),
            "PROCEDURE_NOT_COMPLETED");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::SERVER_BUSY), "SERVER_BUSY");
  EXPECT_EQ(ras::GetResponseOpcodeValueText(ras::ResponseCodeValue::NO_RECORDS_FOUND),
            "NO_RECORDS_FOUND");

  // Test unknown response code value (casting an invalid value to ResponseCodeValue)
  EXPECT_EQ(ras::GetResponseOpcodeValueText(static_cast<ras::ResponseCodeValue>(0x09)),
            "Reserved for Future Use");
}

TEST(RasUtilsTest, IsRangingServiceCharacteristic) {
  // Test true cases for Ranging Service characteristics
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRangingService16Bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasFeaturesCharacteristic16bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasRealTimeRangingDataCharacteristic16bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasOnDemandDataCharacteristic16bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasControlPointCharacteristic16bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasRangingDataReadyCharacteristic16bit)));
  EXPECT_TRUE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kRasRangingDataOverWrittenCharacteristic16bit)));

  // Test false cases for non-Ranging Service characteristics
  EXPECT_FALSE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid::From16Bit(ras::uuid::kClientCharacteristicConfiguration16bit)));
  EXPECT_FALSE(ras::IsRangingServiceCharacteristic(
          bluetooth::Uuid("00001101-0000-1000-8000-00805F9B34FB")));  // Random UUID
}
