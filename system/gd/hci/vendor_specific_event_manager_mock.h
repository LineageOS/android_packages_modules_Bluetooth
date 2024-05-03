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

#include <gmock/gmock.h>

#include "common/contextual_callback.h"
#include "hci/hci_packets.h"
#include "hci/vendor_specific_event_manager_interface.h"

namespace bluetooth::hci::testing {

class MockVendorSpecificEventManager : public VendorSpecificEventManagerInterface {
 public:
  MOCK_METHOD(
      (void),
      RegisterEventHandler,
      (VseSubeventCode, common::ContextualCallback<void(VendorSpecificEventView)>),
      (override));
  MOCK_METHOD((void), UnregisterEventHandler, (VseSubeventCode), (override));
};

}  // namespace bluetooth::hci::testing
