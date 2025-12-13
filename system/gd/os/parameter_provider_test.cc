/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "os/parameter_provider.h"

#include <bluetooth/log.h>
#include <gtest/gtest.h>

#include <filesystem>
#include <format>
#include <string>

namespace bluetooth {
namespace os {

namespace {
constexpr char kDEFAULT_INSTANCE_NAME[] = "default";
constexpr char kHCI_INSTANCE_NAME[] = "hci1";

constexpr char kBT_CONFIG_FILE[] = "bt_config.conf";
}  // namespace

class ParameterProviderTest : public ::testing::Test {
protected:
  void SetUp() override {}

  void TearDown() override {}
};

TEST_F(ParameterProviderTest, ConfigFilePath_default_instance) {
  ParameterProvider::SetHciInstanceName(kDEFAULT_INSTANCE_NAME);

  const std::string filename =
          std::filesystem::path(ParameterProvider::ConfigFilePath()).filename();
  ASSERT_STREQ(kBT_CONFIG_FILE, filename.data());
}

TEST_F(ParameterProviderTest, ConfigFilePath_second_instance) {
  ParameterProvider::SetHciInstanceName(kHCI_INSTANCE_NAME);

  const std::string exp = std::format("{}_{}", kHCI_INSTANCE_NAME, kBT_CONFIG_FILE);
  const std::string filename =
          std::filesystem::path(ParameterProvider::ConfigFilePath()).filename();
  ASSERT_STREQ(exp.data(), filename.data());
}

TEST_F(ParameterProviderTest, SnoopLogDirPath_second_instance) {
  ParameterProvider::SetHciInstanceName(kDEFAULT_INSTANCE_NAME);

  const std::string default_dir_name = std::filesystem::path(ParameterProvider::SnoopLogDirPath());

  ParameterProvider::SetHciInstanceName(kHCI_INSTANCE_NAME);

  const std::string exp = std::format("{}/{}", default_dir_name, kHCI_INSTANCE_NAME);
  const std::string second_instance_name =
          std::filesystem::path(ParameterProvider::SnoopLogDirPath());
  ASSERT_STREQ(exp.data(), second_instance_name.data());
}

}  // namespace os
}  // namespace bluetooth
