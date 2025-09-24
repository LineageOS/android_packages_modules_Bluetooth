/******************************************************************************
 *
 *  Copyright 2025 Google, Inc.
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
 ******************************************************************************/

#include <jni.h>

#include "bt_status.h"

#include "bt_status/bt_status_test.h"
#include "hci/hci_status.h"

namespace bttest {

// Success by default
TEST_F(BtStatusTest, BtStatusSuccess) {
  HciStatus s;

  ASSERT_TRUE(s);
  ASSERT_EQ(s.toString(), kSuccessStr);
  ASSERT_EQ(s.toUint32(), kSuccessInt);
}

// Success from generic BtStatus object
TEST_F(BtStatusTest, BtStatusSuccessGeneric) {
  HciStatus sOriginal;
  BtStatus s = sOriginal;

  ASSERT_TRUE(s);
  ASSERT_EQ(s.toString(), kSuccessStr);
  ASSERT_EQ(s.toUint32(), kSuccessInt);
}

// Success from BtStatus copy constructor
TEST_F(BtStatusTest, BtStatusSuccessCopy) {
  HciStatus sOriginal;
  BtStatus s(sOriginal);

  ASSERT_TRUE(s);
  ASSERT_EQ(s.toString(), kSuccessStr);
  ASSERT_EQ(s.toUint32(), kSuccessInt);
}

// Equality check
TEST_F(BtStatusTest, BtStatusEquality) {
  HciStatus s1;
  HciStatus s2;

  ASSERT_TRUE(s1 == s2);
}

// Passing objects to Java layer should only pass the internal code
// to preserve functionality
TEST_F(BtStatusTest, BtStatusJInt) {
  BtStatus status = HciStatus(ErrorCode::UNKNOWN_HCI_COMMAND);
  jint expected_jint = (jint)ErrorCode::UNKNOWN_HCI_COMMAND;
  jint actual_jint = (jint)status;

  ASSERT_EQ(expected_jint, actual_jint);
}

}  // namespace bttest
