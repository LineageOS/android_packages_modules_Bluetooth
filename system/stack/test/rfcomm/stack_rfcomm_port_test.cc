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

#include <gtest/gtest.h>

#include "stack/include/port_api.h"
#include "stack/rfcomm/rfc_int.h"
#include "types/raw_address.h"

namespace {
const RawAddress kRawAddress = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
const RawAddress kRawAddress2 = RawAddress({0x01, 0x02, 0x03, 0x04, 0x05, 0x06});
}  // namespace

class StackRfcommPortTest : public ::testing::Test {
protected:
  void SetUp() override {}
  void TearDown() override {}

private:
};

TEST_F(StackRfcommPortTest, PORT_IsCollisionDetected__basic) {
  RawAddress test_bd_addr(kRawAddress);
  RawAddress different_bd_addr(kRawAddress2);

  rfc_cb.port.rfc_mcb[0].bd_addr = test_bd_addr;
  // no collisions will happen if the bd_addr don't match, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(different_bd_addr));
  }

  rfc_cb.port.rfc_mcb[0].is_initiator = false;
  // no collisions will happen if not initiator, regardless of state
  for (int state_int = RFC_MX_STATE_IDLE; state_int <= RFC_MX_STATE_DISC_WAIT_UA; state_int++) {
    rfc_cb.port.rfc_mcb[0].state = tRFC_MX_STATE(state_int);
    ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  }

  // possible collisions if bd_addr match and is initiator
  rfc_cb.port.rfc_mcb[0].is_initiator = true;

  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_IDLE;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_CONN_CNF;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONFIGURE;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_SABME_WAIT_UA;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_WAIT_SABME;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_CONNECTED;

  rfc_cb.port.port[0].rfc.p_mcb = &rfc_cb.port.rfc_mcb[0];
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_OPENED;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.port[0].rfc.sm_cb.state = RFC_STATE_TERM_WAIT_SEC_CHECK;
  ASSERT_TRUE(PORT_IsCollisionDetected(test_bd_addr));
  rfc_cb.port.rfc_mcb[0].state = RFC_MX_STATE_DISC_WAIT_UA;
  ASSERT_FALSE(PORT_IsCollisionDetected(test_bd_addr));
}
