/*
 *
 *  Copyright 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

#include <gtest/gtest.h>

#include <memory>
#include <vector>

#include "ble_hci_link_interface.h"
#include "btm_ble_api_types.h"
#include "hci/hci_packets.h"
#include "hci_error_code.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"

namespace {

class StackBTMRegressionTests : public ::testing::Test {
protected:
  void SetUp() override {}
  void TearDown() override {}
};

// regression test for b/260078907
TEST_F(StackBTMRegressionTests, OOB_in_btm_ble_add_resolving_list_entry_complete) {
  const std::vector<uint8_t> packet_bytes = {
          (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
          3,  // Param len for empty payload
          1,  // Num HCI Cmd Packets
          static_cast<uint8_t>(bluetooth::hci::OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST),
          static_cast<uint8_t>(bluetooth::hci::OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST) >> 8,
  };
  auto bytes = std::make_shared<std::vector<uint8_t>>(packet_bytes);
  auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(bytes);
  auto event_view = bluetooth::hci::EventView::Create(packet_view);
  ASSERT_TRUE(event_view.IsValid());
  auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
  ASSERT_TRUE(command_complete_view.IsValid());

  // This should not crash or read out of bounds.
  btm_ble_add_resolving_list_entry_complete(std::move(command_complete_view));
}

// regression test for b/255304475
TEST_F(StackBTMRegressionTests, OOB_in_btm_ble_clear_resolving_list_complete) {
  const std::vector<uint8_t> packet_bytes = {
          (uint8_t)bluetooth::hci::EventCode::COMMAND_COMPLETE,
          3,  // Param len for empty payload
          1,  // Num HCI Cmd Packets
          static_cast<uint8_t>(bluetooth::hci::OpCode::LE_CLEAR_RESOLVING_LIST),
          static_cast<uint8_t>(bluetooth::hci::OpCode::LE_CLEAR_RESOLVING_LIST) >> 8,
  };
  auto bytes = std::make_shared<std::vector<uint8_t>>(packet_bytes);
  auto packet_view = bluetooth::hci::PacketView<bluetooth::hci::kLittleEndian>(bytes);
  auto event_view = bluetooth::hci::EventView::Create(packet_view);
  ASSERT_TRUE(event_view.IsValid());
  auto command_complete_view = bluetooth::hci::CommandCompleteView::Create(event_view);
  ASSERT_TRUE(command_complete_view.IsValid());

  // This should not crash or read out of bounds.
  btm_ble_clear_resolving_list_complete(std::move(command_complete_view));
}

}  // namespace
