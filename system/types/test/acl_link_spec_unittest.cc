/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <bluetooth/types/acl_link_spec.h>
#include <gtest/gtest.h>

static constinit RawAddress RAW_ADDRESS_TEST1("01:02:03:04:05:06");

TEST(BleAddressWithTypeTest, TYPED_ADDRESS_TRANSPORT) {
  AclLinkSpec linkSpecA = {{BLE_ADDR_PUBLIC, RAW_ADDRESS_TEST1}, BT_TRANSPORT_AUTO};
  AclLinkSpec linkSpecB = {{BLE_ADDR_PUBLIC, RAW_ADDRESS_TEST1}, BT_TRANSPORT_BR_EDR};
  AclLinkSpec linkSpecC = {{BLE_ADDR_PUBLIC, RAW_ADDRESS_TEST1}, BT_TRANSPORT_LE};

  ASSERT_EQ(linkSpecA, linkSpecB);
  ASSERT_EQ(linkSpecA, linkSpecC);
  ASSERT_NE(linkSpecB, linkSpecC);

  ASSERT_FALSE(linkSpecA.StrictlyEquals(linkSpecB));
  ASSERT_FALSE(linkSpecA.StrictlyEquals(linkSpecC));
  ASSERT_FALSE(linkSpecB.StrictlyEquals(linkSpecC));
}
