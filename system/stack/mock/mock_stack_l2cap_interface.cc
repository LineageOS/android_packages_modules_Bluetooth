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

#include "stack/mock/mock_stack_l2cap_interface.h"

#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cap_module.h"

namespace {
bluetooth::testing::stack::l2cap::Mock default_l2cap_interface;
bluetooth::stack::l2cap::Interface* interface_ = &default_l2cap_interface;
bluetooth::testing::stack::l2cap::Mock* mock_interface_{};
}  // namespace

void bluetooth::testing::stack::l2cap::reset_interface() {
  mock_interface_ = nullptr;
  interface_ = &default_l2cap_interface;
}

void bluetooth::testing::stack::l2cap::set_interface(
        bluetooth::testing::stack::l2cap::Mock* mock_interface) {
  mock_interface_ = mock_interface;
}

bluetooth::stack::l2cap::Interface& bluetooth::stack::l2cap::get_interface() {
  return (mock_interface_ != nullptr) ? (*mock_interface_) : (*interface_);
}

uint16_t L2CA_LeCreditDefault() {
  return mock_interface_ ? mock_interface_->L2CA_LeCreditDefault() : 0;
}

uint16_t L2CA_LeCreditThreshold() {
  return mock_interface_ ? mock_interface_->L2CA_LeCreditThreshold() : 0;
}

void L2CA_Dumpsys(int /*fd*/) {}
