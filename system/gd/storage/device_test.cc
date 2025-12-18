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

#include "storage/device.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "storage/le_device.h"

using bluetooth::hci::Address;
using bluetooth::hci::DeviceType;
using bluetooth::storage::ConfigCache;
using bluetooth::storage::Device;
using ::testing::Eq;
using ::testing::MatchesRegex;
using ::testing::Optional;
using ::testing::StrEq;

TEST(DeviceTest, create_new_device_using_legacy_key_address) {
  ConfigCache config(10, Device::kLinkKeyProperties);

  // A new device
  Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  Device device(&config, address, Device::ConfigKeyAddressType::LEGACY_KEY_ADDRESS);
  ASSERT_EQ(device.GetLmpVersion(), std::nullopt);

  // An existing device
  Address address2 = {{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}};
  config.SetProperty(address2.ToString(), "LmpVer", "123");
  Device device2(&config, address2, Device::ConfigKeyAddressType::LEGACY_KEY_ADDRESS);
  ASSERT_THAT(device2.GetLmpVersion(), Optional(Eq(123)));
}

TEST(DeviceTest, create_new_device_using_classic_address) {
  ConfigCache config(10, Device::kLinkKeyProperties);

  // A new device
  Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  Device device(&config, address, Device::ConfigKeyAddressType::CLASSIC_ADDRESS);
  ASSERT_EQ(device.GetLmpVersion(), std::nullopt);

  // An existing device
  Address address2 = {{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}};
  config.SetProperty(address2.ToString(), "LmpVer", "123");
  Device device2(&config, address2, Device::ConfigKeyAddressType::CLASSIC_ADDRESS);
  ASSERT_THAT(device2.GetLmpVersion(), Optional(Eq(123)));
}

TEST(DeviceTest, create_new_device_using_le_identity_address) {
  ConfigCache config(10, Device::kLinkKeyProperties);

  // A new device
  Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  Device device(&config, address, Device::ConfigKeyAddressType::LE_IDENTITY_ADDRESS);
  ASSERT_EQ(device.GetLmpVersion(), std::nullopt);

  // An existing device
  Address pseudo_first_seen_address = {{0xab, 0xcd, 0xef, 0x12, 0x34, 0x56}};
  Address le_identity_address = {{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}};
  // first seen address used as key
  config.SetProperty(pseudo_first_seen_address.ToString(), "LmpVer", "123");
  config.SetProperty(pseudo_first_seen_address.ToString(), "LeIdentityAddr",
                     le_identity_address.ToString());
  config.SetProperty(address.ToString(), "LmpVer", "456");
  Device device2(&config, le_identity_address, Device::ConfigKeyAddressType::LE_IDENTITY_ADDRESS);
  ASSERT_THAT(device2.GetLmpVersion(), Optional(Eq(123)));
}

TEST(DeviceTest, set_device_type) {
  ConfigCache config(10, Device::kLinkKeyProperties);

  Address address = {{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  Device device(&config, address, Device::ConfigKeyAddressType::LEGACY_KEY_ADDRESS);
  ASSERT_EQ(device.GetDeviceType(), std::nullopt);

  device.SetDeviceType(DeviceType::BR_EDR);
  ASSERT_THAT(device.GetDeviceType(), Optional(Eq(DeviceType::BR_EDR)));

  device.SetDeviceType(DeviceType::LE);
  ASSERT_THAT(device.GetDeviceType(), Optional(Eq(DeviceType::DUAL)));
}
