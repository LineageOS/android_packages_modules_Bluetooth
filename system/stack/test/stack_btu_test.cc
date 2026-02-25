/*
 * Copyright 2021 The Android Open Source Project
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

#include <com_android_bluetooth_flags.h>
#include <gtest/gtest.h>

#include <cstdint>

#include "stack/include/btu_hcif.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/hcidefs.h"
#include "test/common/mock_functions.h"

/* Function for test provided by btu_hcif.cc */
void btu_hcif_hdl_command_status(uint16_t opcode, uint8_t status, const uint8_t* p_cmd);

class StackBtuTest : public ::testing::Test {
protected:
  void SetUp() override {
    reset_mock_function_count_map();
    com_android_bluetooth_flags_reset_flags();
  }
};

TEST_F(StackBtuTest, post_on_main) {}

TEST_F(StackBtuTest, btm_sco_connection_failed_called_fixed) {
  uint8_t test_data[18];  // garbage data for testing
  bluetooth::legacy::testing::btu_hcif_hdl_command_status(HCI_SETUP_ESCO_CONNECTION,
                                                          HCI_ERR_UNSPECIFIED, test_data);
  ASSERT_EQ(0, get_func_call_count("btm_sco_connection_failed"));

  // prepare sco complete event with an error
  BT_HDR* esco_command_complete_ev = (BT_HDR*)malloc(sizeof(BT_HDR) + sizeof(test_data));
  esco_command_complete_ev->event = HCI_ESCO_CONNECTION_COMP_EVT,
  esco_command_complete_ev->len = sizeof(test_data);
  esco_command_complete_ev->offset = 0,

  // Event code
          esco_command_complete_ev->data[0] = HCI_ESCO_CONNECTION_COMP_EVT;
  // Event len
  esco_command_complete_ev->data[1] = 17;
  // Event status - error
  esco_command_complete_ev->data[2] = 0x0a;

  bluetooth::legacy::testing::btu_hcif_process_event(0, esco_command_complete_ev);
  ASSERT_EQ(1, get_func_call_count("btm_sco_connection_failed"));
}

TEST_F(StackBtuTest, btm_sco_create_connection_status_failed_called) {
  uint8_t test_data[10];  // garbage data for testing
  bluetooth::legacy::testing::btu_hcif_hdl_command_status(HCI_SETUP_ESCO_CONNECTION,
                                                          HCI_ERR_UNSPECIFIED, test_data);
  ASSERT_EQ(1, get_func_call_count("btm_sco_create_command_status_failed"));
}
