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

#include "adapter/bluetooth_test.h"
#include "osi/include/allocator.h"
#include "osi/include/compat.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

namespace {

// Each iteration of the test takes about 2 seconds to run, so choose a value
// that matches your time constraints. For example, 5 iterations would take
// about 10 seconds to run
const int kTestRepeatCount = 5;

}  // namespace

namespace bttest {

TEST_F(BluetoothTest, AdapterEnableDisable) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

  EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterRepeatedEnableDisable) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  for (int i = 0; i < kTestRepeatCount; ++i) {
    EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
  }
}

static bt_property_t* property_new_name(const char* name) {
  bluetooth::log::assert_that(name != NULL, "assert failed: name != NULL");
  bt_property_t* property = static_cast<bt_property_t*>(osi_calloc(sizeof(bt_property_t)));

  property->val = osi_calloc(sizeof(bt_bdname_t) + 1);
  osi_strlcpy((char*)property->val, name, sizeof(bt_bdname_t));

  property->type = BT_PROPERTY_BDNAME;
  property->len = sizeof(bt_bdname_t);

  return property;
}

static void property_free(bt_property_t* property) {
  if (property == NULL) {
    return;
  }

  osi_free(property->val);
  osi_free(property);
}

static const bt_bdname_t* property_as_name(const bt_property_t* property) {
  bluetooth::log::assert_that(property->type == BT_PROPERTY_BDNAME,
                              "assert failed: property_is_name(property)");
  return (const bt_bdname_t*)property->val;
}

static bool property_equals(const bt_property_t* p1, const bt_property_t* p2) {
  if (!p1 || !p2 || p1->type != p2->type) {
    return false;
  }

  if (p1->type == BT_PROPERTY_BDNAME && p1->len != p2->len) {
    const bt_property_t *shorter = p1, *longer = p2;
    if (p1->len > p2->len) {
      shorter = p2;
      longer = p1;
    }
    return strlen((const char*)longer->val) == (size_t)shorter->len &&
           !memcmp(longer->val, shorter->val, shorter->len);
  }

  return p1->len == p2->len && !memcmp(p1->val, p2->val, p1->len);
}

TEST_F(BluetoothTest, AdapterSetGetName) {
  bt_property_t* new_name = property_new_name("BluetoothTestName1");

  EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Test should be run with Adapter enabled";

  // Enabling the interface will call the properties callback twice before
  // ever reaching this point.
  ClearSemaphore(adapter_properties_callback_sem_);

  EXPECT_EQ(bt_interface()->get_adapter_property(BT_PROPERTY_BDNAME), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_properties_callback_sem_);
  EXPECT_GT(GetPropertiesChangedCount(), 0) << "Expected at least one adapter property to change";
  bt_property_t* name_property = GetProperty(BT_PROPERTY_BDNAME);
  EXPECT_NE(name_property, nullptr);
  if (property_equals(name_property, new_name)) {
    property_free(new_name);
    new_name = property_new_name("BluetoothTestName2");
  }
  std::string old_name((const char*)property_as_name(name_property)->name, name_property->len);

  EXPECT_EQ(bt_interface()->set_adapter_property(new_name), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_properties_callback_sem_);
  EXPECT_GT(GetPropertiesChangedCount(), 0) << "Expected at least one adapter property to change";
  EXPECT_TRUE(GetProperty(BT_PROPERTY_BDNAME)) << "The Bluetooth name property did not change.";
  EXPECT_TRUE(property_equals(GetProperty(BT_PROPERTY_BDNAME), new_name))
          << "Bluetooth name " << property_as_name(GetProperty(BT_PROPERTY_BDNAME))->name
          << " does not match test value " << property_as_name(new_name)->name;

  bt_property_t* old_name_property = property_new_name(old_name.c_str());
  EXPECT_EQ(bt_interface()->set_adapter_property(old_name_property), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_properties_callback_sem_);
  EXPECT_TRUE(property_equals(GetProperty(BT_PROPERTY_BDNAME), old_name_property))
          << "Bluetooth name " << property_as_name(GetProperty(BT_PROPERTY_BDNAME))->name
          << " does not match original name" << old_name;

  EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
  property_free(new_name);
  property_free(old_name_property);
}

TEST_F(BluetoothTest, AdapterStartDiscovery) {
  EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Test should be run with Adapter enabled";

  EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);
  EXPECT_EQ(GetDiscoveryState(), BT_DISCOVERY_STARTED) << "Unable to start discovery.";

  EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterCancelDiscovery) {
  EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_ON) << "Test should be run with Adapter enabled";

  EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);
  EXPECT_EQ(bt_interface()->cancel_discovery(), BT_STATUS_SUCCESS);
  semaphore_wait(discovery_state_changed_callback_sem_);

  EXPECT_EQ(GetDiscoveryState(), BT_DISCOVERY_STOPPED) << "Unable to stop discovery.";

  EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
  semaphore_wait(adapter_state_changed_callback_sem_);
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
}

TEST_F(BluetoothTest, AdapterDisableDuringBonding) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  RawAddress bdaddr = {{0x22, 0x22, 0x22, 0x22, 0x22, 0x22}};

  for (int i = 0; i < kTestRepeatCount; ++i) {
    EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    EXPECT_EQ(bt_interface()->create_bond(&bdaddr, BT_TRANSPORT_BR_EDR), BT_STATUS_SUCCESS);

    EXPECT_EQ(bt_interface()->cancel_bond(&bdaddr), BT_STATUS_SUCCESS);

    EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
  }
}

TEST_F(BluetoothTest, AdapterCleanupDuringDiscovery) {
  EXPECT_EQ(GetState(), BT_STATE_OFF) << "Test should be run with Adapter disabled";

  bt_callbacks_t* callbacks = bt_callbacks();
  ASSERT_TRUE(callbacks != nullptr);

  for (int i = 0; i < kTestRepeatCount; ++i) {
    bt_interface()->init(callbacks, false, false, 0, false);
    EXPECT_EQ(bt_interface()->enable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_ON) << "Adapter did not turn on.";

    EXPECT_EQ(bt_interface()->start_discovery(), BT_STATUS_SUCCESS);

    EXPECT_EQ(bt_interface()->disable(), BT_STATUS_SUCCESS);
    semaphore_wait(adapter_state_changed_callback_sem_);
    EXPECT_EQ(GetState(), BT_STATE_OFF) << "Adapter did not turn off.";
    bt_interface()->cleanup();
  }
}

}  // namespace bttest
