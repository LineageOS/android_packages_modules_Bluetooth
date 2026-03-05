/******************************************************************************
 *
 *  Copyright 2015 Google, Inc.
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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <com_android_bluetooth_flags.h>

#include "adapter/bluetooth_test.h"
#include "osi/include/allocator.h"
#include "osi/include/compat.h"
#include "osi/include/wakelock.h"

namespace {

// Each iteration of the test takes about 2 seconds to run, so choose a value
// that matches your time constraints. For example, 5 iterations would take
// about 10 seconds to run
const int kTestRepeatCount = 5;

}  // namespace

namespace bttest {

TEST_F(BluetoothTest, AdapterEnableDisable) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  bluetooth_enable("test_name");
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

  bluetooth_disable();
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterRepeatedEnableDisable) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  for (int i = 0; i < kTestRepeatCount; ++i) {
    bluetooth_enable("test_name");
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    bluetooth_disable();
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
  }
}

TEST_F(BluetoothTest, AdapterStartDiscovery) {
  bluetooth_enable("test_name");
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Test should be run with Adapter enabled";

  EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);
  EXPECT_EQ(GetDiscoveryState(), BT_DISCOVERY_STARTED) << "Unable to start discovery.";

  bluetooth_disable();
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterCancelDiscovery) {
  bluetooth_enable("test_name");
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Test should be run with Adapter enabled";

  EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);
  EXPECT_EQ(bt_interface()->cancel_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);

  EXPECT_EQ(GetDiscoveryState(), BT_DISCOVERY_STOPPED) << "Unable to stop discovery.";

  bluetooth_disable();
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterDisableDuringBonding) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  RawAddress bdaddr("22:22:22:22:22:22");

  for (int i = 0; i < kTestRepeatCount; ++i) {
    bluetooth_enable("test_name");
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    EXPECT_EQ(bt_interface()->create_bond(bdaddr, BT_TRANSPORT_BR_EDR), BT_STATUS_SUCCESS);

    EXPECT_EQ(bt_interface()->cancel_bond(bdaddr), BT_STATUS_SUCCESS);

    bluetooth_disable();
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
  }
}

TEST_F(BluetoothTest, AdapterCleanupDuringDiscovery) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  bt_callbacks_t* callbacks = bt_callbacks();
  ASSERT_TRUE(callbacks != nullptr);

  bluetooth_cleanup();  // init is called during SetUp, so we need to cleanup first

  for (int i = 0; i < kTestRepeatCount; ++i) {
    bluetooth_init(callbacks, false, false, 0, false, "default", nullptr, false);
    wakelock_set_os_callouts(nullptr);  // To force using 'native' wakelock in tests
    bluetooth_enable("test_name");
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);

    bluetooth_disable();
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
    bluetooth_cleanup();
  }

  // re-init to allow proper shutdown to happen
  bluetooth_init(callbacks, false, false, 0, false, "default", nullptr, false);
}

}  // namespace bttest
