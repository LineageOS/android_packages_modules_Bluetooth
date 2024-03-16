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

enum ProcedureDoneStatus : uint8_t {
  ALL_RESULTS_COMPLETE = 0x0,
  PARTIAL_RESULTS = 0x1,
  ABORTED = 0xf,
};

class RasServer {
 public:
  virtual ~RasServer() = default;
  virtual void Initialize() = 0;
  virtual void PushProcedureData(RawAddress address, uint16_t procedure_count,
                                 ProcedureDoneStatus procedure_done_status,
                                 std::vector<uint8_t> data) = 0;
};

RasServer* GetRasServer();

}  // namespace ras
}  // namespace bluetooth
