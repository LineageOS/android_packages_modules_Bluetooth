/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "storage/le_device.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "storage/device.h"

using bluetooth::hci::Address;
using bluetooth::hci::AddressType;
using bluetooth::hci::DeviceType;
using bluetooth::storage::ConfigCache;
using bluetooth::storage::Device;
using bluetooth::storage::LeDevice;
using ::testing::Eq;
using ::testing::Optional;

TEST(LeDeviceTest, create_new_le_device) {
  ConfigCache config(10, Device::kLinkKeyProperties);
  bluetooth::hci::Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  LeDevice device(&config, address.ToString());
  ASSERT_FALSE(device.GetAddressType());
}

TEST(LeDeviceTest, set_property) {
  ConfigCache config(10, Device::kLinkKeyProperties);
  Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  LeDevice device(&config, address.ToString());
  ASSERT_FALSE(device.GetAddressType());

  device.SetAddressType(AddressType::RANDOM_DEVICE_ADDRESS);
  ASSERT_THAT(device.GetAddressType(), Optional(Eq(AddressType::RANDOM_DEVICE_ADDRESS)));
}
