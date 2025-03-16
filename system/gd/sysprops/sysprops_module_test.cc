/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "sysprops/sysprops_module.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <filesystem>

#include "os/files.h"
#include "os/parameter_provider.h"
#include "os/system_properties.h"

namespace testing {

class SyspropsModuleTest : public Test {
protected:
  void SetUp() override {
    EXPECT_TRUE(bluetooth::os::ClearSystemPropertiesForHost());
    temp_config_ = std::filesystem::temp_directory_path() / "temp_sysprops.conf";
    temp_override_dir_ = temp_config_ / ".d";
    DeleteConfigFiles();
    bluetooth::os::ParameterProvider::OverrideSyspropsFilePath(temp_config_);
  }

  void TearDown() override {
    EXPECT_TRUE(bluetooth::os::ClearSystemPropertiesForHost());
    test_registry_.StopAll();
    DeleteConfigFiles();
  }

  void DeleteConfigFiles() {
    if (std::filesystem::exists(temp_config_)) {
      EXPECT_TRUE(std::filesystem::remove(temp_config_));
    }
    if (std::filesystem::exists(temp_override_dir_)) {
      EXPECT_GT(std::filesystem::remove_all(temp_override_dir_), 0u);
      EXPECT_FALSE(std::filesystem::exists(temp_override_dir_));
    }
  }

  bluetooth::TestModuleRegistry test_registry_;
  std::filesystem::path temp_config_;
  std::filesystem::path temp_override_dir_;
};

static const std::string kSupportedSyspropName = "bluetooth.device.class_of_device";
static const std::string kSupportedSyspropValue = "0,1,4";
static const std::string kUnsupportedSyspropName = "i.am.an.unsupported.sysprop";
static const std::string kCorrectPrefixAflagName =
        "persist.device_config.aconfig_flags.bluetooth.com.android.bluetooth.flags.msft_addr_"
        "tracking_quirk";
static const std::string kCorrectPrefixAflagValue = "true";
static const std::string kIncorrectPrefixAflagName =
        "persist.device_config.aconfig_flags.not_bluetooth.testing_flag";

static const std::string kParseConfigTestConfig =
        "[Sysprops]\n" + kSupportedSyspropName + "=" + kSupportedSyspropValue + "\n" +
        kUnsupportedSyspropName + "=true\n" + "\n" + "[Aflags]\n" + kCorrectPrefixAflagName + "=" +
        kCorrectPrefixAflagValue + "\n" + kIncorrectPrefixAflagName + "=true\n";

TEST_F(SyspropsModuleTest, parse_config_test) {
  // Verify the state before test
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kSupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kUnsupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kCorrectPrefixAflagName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kIncorrectPrefixAflagName), std::nullopt);

  EXPECT_TRUE(bluetooth::os::WriteToFile(temp_config_.string(), kParseConfigTestConfig));
  auto* sysprops_module = new bluetooth::sysprops::SyspropsModule();
  test_registry_.InjectTestModule(&bluetooth::sysprops::SyspropsModule::Factory, sysprops_module);

  EXPECT_THAT(bluetooth::os::GetSystemProperty(kSupportedSyspropName),
              Optional(StrEq(kSupportedSyspropValue)));
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kUnsupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kCorrectPrefixAflagName),
              Optional(StrEq(kCorrectPrefixAflagValue)));
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kIncorrectPrefixAflagName), std::nullopt);
}

TEST_F(SyspropsModuleTest, empty_sysprops_file_path_test) {
  // Verify the state before test
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kSupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kUnsupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kCorrectPrefixAflagName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kIncorrectPrefixAflagName), std::nullopt);

  bluetooth::os::ParameterProvider::OverrideSyspropsFilePath("");
  auto* sysprops_module = new bluetooth::sysprops::SyspropsModule();
  test_registry_.InjectTestModule(&bluetooth::sysprops::SyspropsModule::Factory, sysprops_module);

  EXPECT_THAT(bluetooth::os::GetSystemProperty(kSupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kUnsupportedSyspropName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kCorrectPrefixAflagName), std::nullopt);
  EXPECT_THAT(bluetooth::os::GetSystemProperty(kIncorrectPrefixAflagName), std::nullopt);
}

}  // namespace testing
