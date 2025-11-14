/*
 * Copyright 2025 The Android Open Source Project
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

#pragma once

#include <gtest/gtest.h>

namespace fuzzer_validation_test {

class TestEnvironment : public testing::Environment {
public:
  TestEnvironment(const char *name) : corpus_name(name) {}

  void SetUp() override;
  void TearDown() override;

private:
  const char *corpus_name;
};

void runValidationTest();

}  // namespace fuzzer_validation_test
