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

#include "adapter/bluetooth_test.h"

namespace bttest {

class BtStatusTest : public BluetoothTest {
protected:
  BtStatusTest() = default;
  BtStatusTest(const BtStatusTest&) = delete;
  BtStatusTest& operator=(const BtStatusTest&) = delete;

  virtual ~BtStatusTest() = default;

  virtual void SetUp();
  virtual void TearDown();

  friend void BtStatusCreation();

  const char* kSuccessStr = "BT_SUCCESS";
  const uint32_t kSuccessInt = 0;
};

}  // namespace bttest
