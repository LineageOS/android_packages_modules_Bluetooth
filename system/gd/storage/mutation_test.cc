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

#include "storage/mutation.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "storage/config_cache.h"
#include "storage/device.h"

namespace testing {

using bluetooth::storage::ConfigCache;
using bluetooth::storage::Device;
using bluetooth::storage::Mutation;
using bluetooth::storage::MutationEntry;

TEST(MutationTest, simple_sequence_test) {
  ConfigCache config(100, Device::kLinkKeyProperties);
  ConfigCache memory_only_config(100, {});
  config.SetProperty("A", "B", "C");
  config.SetProperty("AA:BB:CC:DD:EE:FF", "B", "C");
  config.SetProperty("AA:BB:CC:DD:EE:FF", "C", "D");
  config.SetProperty("CC:DD:EE:FF:00:11", "LinkKey", "AABBAABBCCDDEE");
  ASSERT_THAT(config.GetPersistentSections(), ElementsAre("CC:DD:EE:FF:00:11"));
  Mutation mutation2(&config, &memory_only_config);
  mutation2.Add(MutationEntry::Set(MutationEntry::PropertyType::NORMAL, "AA:BB:CC:DD:EE:FF",
                                   "LinkKey", "CCDDEEFFGG"));
  mutation2.Commit();
  ASSERT_THAT(config.GetPersistentSections(),
              ElementsAre("CC:DD:EE:FF:00:11", "AA:BB:CC:DD:EE:FF"));
}

TEST(MutationTest, add_to_different_configs) {
  ConfigCache config(100, Device::kLinkKeyProperties);
  ConfigCache memory_only_config(100, {});
  ASSERT_FALSE(config.HasSection("A"));
  Mutation mutation(&config, &memory_only_config);
  mutation.Add(MutationEntry::Set(MutationEntry::PropertyType::NORMAL, "A", "B", "C"));
  mutation.Add(MutationEntry::Set(MutationEntry::PropertyType::MEMORY_ONLY, "A", "D", "Hello"));
  mutation.Commit();
  ASSERT_TRUE(config.HasProperty("A", "B"));
  ASSERT_FALSE(config.HasProperty("A", "D"));
  ASSERT_THAT(config.GetProperty("A", "B"), Optional(StrEq("C")));
  ASSERT_FALSE(memory_only_config.HasProperty("A", "B"));
  ASSERT_TRUE(memory_only_config.HasProperty("A", "D"));
  ASSERT_THAT(memory_only_config.GetProperty("A", "D"), Optional(StrEq("Hello")));
}

}  // namespace testing
