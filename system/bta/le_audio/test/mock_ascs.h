/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "bta/le_audio/ascs/ascs.h"

namespace bluetooth::le_audio {

class MockAscs : public Ascs {
public:
  MockAscs() = default;
  MockAscs(const MockAscs&) = delete;
  MockAscs& operator=(const MockAscs&) = delete;

  MOCK_METHOD(void, Dump, (std::stringstream& stream), (const, override));
  MOCK_METHOD(void, RegisterGattService,
              (const ServiceDescriptor& service_descriptor, Callbacks* callbacks), (override));
  MOCK_METHOD(void, UpdateAseState,
              (const RawAddress& pseudo_addr, uint8_t ase_id, const AseState& ase_state),
              (override));
  MOCK_METHOD(void, AseCtpRequestResponse,
              (const RawAddress& pseudo_addr, const AseCtpResponse& response), (override));
  MOCK_METHOD(uint16_t, GetConnectionId, (const RawAddress& pseudo_addr), (const, override));
};

}  // namespace bluetooth::le_audio
