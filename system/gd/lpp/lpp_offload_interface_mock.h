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
#pragma once

#include <gmock/gmock.h>

#include "lpp/lpp_offload_interface.h"

// Unit test interfaces
namespace bluetooth::lpp::testing {

class MockLppOffloadInterface : public LppOffloadInterface {
public:
  MOCK_METHOD(bool, RegisterSocketHalCallback, (hal::SocketHalCallback*), (override));
  MOCK_METHOD(hal::SocketCapabilities, GetSocketCapabilities, (), (const override));
  MOCK_METHOD(bool, SocketOpened, (const hal::SocketContext&), (override));
  MOCK_METHOD(void, SocketClosed, (uint64_t), (override));
};

}  // namespace bluetooth::lpp::testing
