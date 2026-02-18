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

#include "stack/mock/mock_stack_rnr_interface.h"

using ::testing::StrictMock;

namespace {
StrictMock<bluetooth::testing::stack::rnr::Mock> default_interface;
bluetooth::stack::rnr::Interface* interface_ = &default_interface;
}  // namespace

void bluetooth::testing::stack::rnr::reset_interface() { interface_ = &default_interface; }
void bluetooth::testing::stack::rnr::set_interface(bluetooth::stack::rnr::Interface* interface) {
  interface_ = interface;
}

bluetooth::stack::rnr::Interface& get_stack_rnr_interface() { return *interface_; }
