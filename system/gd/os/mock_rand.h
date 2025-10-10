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
#pragma once

#include <gmock/gmock.h>

#include "os/rand.h"

namespace bluetooth {
namespace os {
namespace testing {

class MockRandomDataGenerator : public RandomDataGenerator {
public:
  MockRandomDataGenerator() = default;
  MockRandomDataGenerator(const MockRandomDataGenerator&) = delete;
  MockRandomDataGenerator& operator=(const MockRandomDataGenerator&) = delete;
  ~MockRandomDataGenerator() override = default;
  MOCK_METHOD((void), GenerateBytes, (uint8_t* data, size_t size), (override));
};

}  // namespace testing
}  // namespace os
}  // namespace bluetooth
