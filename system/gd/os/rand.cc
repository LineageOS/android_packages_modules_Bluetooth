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

#include "os/rand.h"

#include <bluetooth/log.h>
#include <openssl/rand.h>

namespace bluetooth {
namespace os {

namespace {

class DefaultRandomDataGenerator : public RandomDataGenerator {
public:
  void GenerateBytes(uint8_t* data, size_t size) override {
    log::assert_that(RAND_bytes(data, size) == 1, "RAND_bytes failed");
  }
};

DefaultRandomDataGenerator default_generator;
RandomDataGenerator* random_generator_instance = &default_generator;

}  // namespace

void SetRandomDataGeneratorForTesting(RandomDataGenerator* generator) {
  if (generator) {
    random_generator_instance = generator;
  } else {
    random_generator_instance = &default_generator;
  }
}

RandomDataGenerator* GetRandomDataGenerator() { return random_generator_instance; }

}  // namespace os
}  // namespace bluetooth
