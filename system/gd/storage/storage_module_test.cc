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

#include "storage/storage_module.h"

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <iomanip>
#include <optional>
#include <thread>

#include "common/bind.h"
#include "module.h"
#include "os/fake_timer/fake_timerfd.h"
#include "os/files.h"
#include "storage/config_cache.h"
#include "storage/config_keys.h"
#include "storage/device.h"
#include "storage/legacy_config_file.h"

namespace bluetooth::storage {

using ::testing::ElementsAre;
using ::testing::StrEq;

using bluetooth::hci::Address;
using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::storage::ConfigCache;
using bluetooth::storage::Device;
using bluetooth::storage::LegacyConfigFile;
using bluetooth::storage::StorageModule;

using namespace std::chrono_literals;

static const std::chrono::milliseconds kTestConfigSaveDelay = std::chrono::milliseconds(100);
static const size_t kTestTempDevicesCapacity = 10;

class StorageModuleTest : public testing::Test {
protected:
  void SetUp() override {
    thread_ = new bluetooth::os::Thread("test_thread", bluetooth::os::Thread::Priority::NORMAL);
    handler_ = new bluetooth::os::Handler(thread_);

    temp_dir_ = std::filesystem::temp_directory_path();
    temp_config_ = temp_dir_ / "temp_config.txt";

    DeleteConfigFiles();
    ASSERT_FALSE(std::filesystem::exists(temp_config_));

    storage_ = new StorageModule(handler_, temp_config_.string(), kTestConfigSaveDelay,
                                 kTestTempDevicesCapacity, false, false);
  }

  void TearDown() override {
    DeleteConfigFiles();
    handler_->Clear();
    handler_->WaitUntilStopped(200ms);
    thread_->Stop();
    delete storage_;
    delete handler_;
    delete thread_;
    handler_ = nullptr;
    thread_ = nullptr;
    storage_ = nullptr;
  }

  void DeleteConfigFiles() {
    if (std::filesystem::exists(temp_config_)) {
      ASSERT_TRUE(std::filesystem::remove(temp_config_));
    }
  }

  void SetProperty(std::string section, std::string property, std::string value) {
    storage_->SetProperty(std::move(section), std::move(property), std::move(value));
  }

  std::optional<std::string> GetProperty(const std::string& section, const std::string& property) {
    return storage_->GetProperty(section, property);
  }

  bool RemoveProperty(const std::string& section, const std::string& property) {
    return storage_->RemoveProperty(section, property);
  }

  void RemoveSection(const std::string& section) { storage_->RemoveSection(section); }
  bool HasSection(const std::string& section) { return storage_->HasSection(section); }
  std::vector<std::string> GetPersistentSections() { return storage_->GetPersistentSections(); }

  void FakeTimerAdvance(std::chrono::milliseconds time) {
    handler_->Post(bluetooth::common::BindOnce(fake_timerfd_advance, time.count()));
  }

  bool WaitForReactorIdle(std::chrono::milliseconds time) {
    bool stopped = thread_->GetReactor()->WaitForIdle(200ms);
    if (!stopped) {
      return false;
    }
    FakeTimerAdvance(time);
    return thread_->GetReactor()->WaitForIdle(200ms);
  }

  bluetooth::os::Thread* thread_;
  bluetooth::os::Handler* handler_;
  StorageModule* storage_;
  std::filesystem::path temp_dir_;
  std::filesystem::path temp_config_;
};

TEST_F(StorageModuleTest, empty_config_no_op_test) {
  storage_->Start();
  storage_->Stop();

  // Verify states after test
  ASSERT_TRUE(std::filesystem::exists(temp_config_));

  // Verify config after test
  auto config = LegacyConfigFile::FromPath(temp_config_.string()).Read(kTestTempDevicesCapacity);
  ASSERT_TRUE(config);
  ASSERT_TRUE(config->HasSection(StorageModule::kInfoSection));
}

static const std::string kReadTestConfig =
        "[Info]\n"
        "TimeCreated = 2020-05-20 01:20:56\n"
        "\n"
        "[Metrics]\n"
        "Salt256Bit = 1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\n"
        "\n"
        "[Adapter]\n"
        "Address = 01:02:03:ab:cd:ef\n"
        "LE_LOCAL_KEY_IRK = fedcba0987654321fedcba0987654321\n"
        "LE_LOCAL_KEY_IR = fedcba0987654321fedcba0987654322\n"
        "LE_LOCAL_KEY_DHK = fedcba0987654321fedcba0987654323\n"
        "LE_LOCAL_KEY_ER = fedcba0987654321fedcba0987654324\n"
        "ScanMode = 2\n"
        "DiscoveryTimeout = 120\n"
        "\n"
        "[01:02:03:ab:cd:ea]\n"
        "Name = hello world\n"
        "LinkKey = fedcba0987654321fedcba0987654328\n"
        "DevType = 1\n"
        "\n";

TEST_F(StorageModuleTest, read_existing_config_test) {
  ASSERT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kReadTestConfig));

  // Set up
  storage_->Start();

  // Test
  ASSERT_TRUE(HasSection("Metrics"));
  ASSERT_THAT(GetPersistentSections(), ElementsAre("01:02:03:ab:cd:ea"));
  ASSERT_THAT(GetProperty(StorageModule::kAdapterSection, BTIF_STORAGE_KEY_ADDRESS),
              Optional(StrEq("01:02:03:ab:cd:ef")));

  storage_->Stop();

  // Verify states after test
  ASSERT_TRUE(std::filesystem::exists(temp_config_));

  // Verify config after test
  auto config = bluetooth::os::ReadSmallFile(temp_config_.string());
  ASSERT_TRUE(config);
  ASSERT_EQ(*config, kReadTestConfig);
}

TEST_F(StorageModuleTest, save_config_test) {
  // Prepare config file
  ASSERT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kReadTestConfig));

  // Set up
  storage_->Start();

  // Test
  // Change a property
  ASSERT_THAT(GetProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME),
              Optional(StrEq("hello world")));
  SetProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME, "foo");
  ASSERT_THAT(GetProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME), Optional(StrEq("foo")));
  ASSERT_TRUE(WaitForReactorIdle(kTestConfigSaveDelay));

  auto config = LegacyConfigFile::FromPath(temp_config_.string()).Read(kTestTempDevicesCapacity);
  ASSERT_TRUE(config);
  ASSERT_THAT(GetProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME), Optional(StrEq("foo")));

  // Remove a property
  RemoveProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME);
  ASSERT_TRUE(WaitForReactorIdle(kTestConfigSaveDelay));
  bluetooth::log::info("After waiting 2");
  config = LegacyConfigFile::FromPath(temp_config_.string()).Read(kTestTempDevicesCapacity);
  ASSERT_TRUE(config);
  ASSERT_FALSE(config->HasProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME));

  // Remove a section
  RemoveSection("01:02:03:ab:cd:ea");
  ASSERT_TRUE(WaitForReactorIdle(kTestConfigSaveDelay));
  bluetooth::log::info("After waiting 3");
  config = LegacyConfigFile::FromPath(temp_config_.string()).Read(kTestTempDevicesCapacity);
  ASSERT_TRUE(config);
  ASSERT_FALSE(config->HasSection("01:02:03:ab:cd:ea"));

  // Tear down
  storage_->Stop();

  // Verify states after test
  ASSERT_TRUE(std::filesystem::exists(temp_config_));
}

TEST_F(StorageModuleTest, get_bonded_devices_test) {
  // Prepare config file
  ASSERT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kReadTestConfig));

  // Set up
  storage_->Start();

  ASSERT_EQ(storage_->GetBondedDevices().size(), 1u);
  auto address = Address::FromString("01:02:03:ab:cd:ea");
  ASSERT_EQ(address, storage_->GetBondedDevices()[0].GetAddress());

  storage_->Stop();
}

TEST_F(StorageModuleTest, unchanged_config_causes_no_write) {
  // Prepare config file
  ASSERT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kReadTestConfig));

  // Set up
  storage_->Start();

  ASSERT_EQ(storage_->GetBondedDevices().size(), 1u);
  auto address = Address::FromString("01:02:03:ab:cd:ea");
  ASSERT_EQ(address, storage_->GetBondedDevices()[0].GetAddress());

  // Remove the file after it was read, so we can check if it was written with exists()
  DeleteConfigFiles();

  // Tear down
  storage_->Stop();

  ASSERT_FALSE(std::filesystem::exists(temp_config_));
}

TEST_F(StorageModuleTest, changed_config_causes_a_write) {
  // Prepare config file
  ASSERT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kReadTestConfig));

  // Set up
  storage_->Start();

  // Remove the file after it was read, so we can check if it was written with exists()
  DeleteConfigFiles();

  // Change a property
  SetProperty("01:02:03:ab:cd:ea", BTIF_STORAGE_KEY_NAME, "foo");

  ASSERT_TRUE(WaitForReactorIdle(std::chrono::milliseconds(1)));

  // Tear down
  storage_->Stop();

  ASSERT_TRUE(std::filesystem::exists(temp_config_));
}

TEST_F(StorageModuleTest, no_config_causes_a_write) {
  storage_->Start();
  storage_->Stop();

  ASSERT_TRUE(std::filesystem::exists(temp_config_));
}

}  // namespace bluetooth::storage
