/*
 * Copyright 2024 The Android Open Source Project
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

#include <cstdint>
#include <vector>

#include "types/raw_address.h"

namespace bluetooth {
namespace ras {

class RasServer {
 public:
  virtual ~RasServer() = default;
  virtual void Initialize() = 0;
  virtual void PushProcedureData(RawAddress address, uint16_t procedure_count,
                                 bool is_last, std::vector<uint8_t> data) = 0;
};

RasServer* GetRasServer();

class RasClientCallbacks {
 public:
  virtual ~RasClientCallbacks() = default;
  virtual void OnRemoteData(RawAddress address, std::vector<uint8_t> data) = 0;
};

class RasClient {
 public:
  virtual ~RasClient() = default;
  virtual void Initialize() = 0;
  virtual void RegisterCallbacks(RasClientCallbacks* callbacks) = 0;
  virtual void Connect(const RawAddress& address) = 0;
};

RasClient* GetRasClient();

}  // namespace ras
}  // namespace bluetooth
