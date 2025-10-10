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

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace bluetooth {
namespace os {

class RandomDataGenerator {
public:
  virtual ~RandomDataGenerator() = default;
  virtual void GenerateBytes(uint8_t* data, size_t size) = 0;
};

void SetRandomDataGeneratorForTesting(RandomDataGenerator* generator);
RandomDataGenerator* GetRandomDataGenerator();

template <size_t SIZE>
std::array<uint8_t, SIZE> GenerateRandom() {
  std::array<uint8_t, SIZE> ret;
  GetRandomDataGenerator()->GenerateBytes(ret.data(), ret.size());
  return ret;
}

inline uint32_t GenerateRandom() {
  uint32_t ret{};
  GetRandomDataGenerator()->GenerateBytes(reinterpret_cast<uint8_t*>(&ret), sizeof(uint32_t));
  return ret;
}

inline uint64_t GenerateRandomUint64() {
  uint64_t ret{};
  GetRandomDataGenerator()->GenerateBytes(reinterpret_cast<uint8_t*>(&ret), sizeof(uint64_t));
  return ret;
}

}  // namespace os
}  // namespace bluetooth
