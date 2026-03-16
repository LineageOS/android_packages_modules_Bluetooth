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

#include "bta/include/bta_ras_api.h"

class MockRasServer : public bluetooth::ras::RasServer {
  void Initialize() override {}
  void RegisterCallbacks(bluetooth::ras::RasServerCallbacks* /* callbacks */) override {}
  void HandleVendorSpecificReplyComplete(RawAddress /* address */, bool /* success */) override {}
  void PushProcedureData(RawAddress /* address */, uint16_t /* procedure_count */,
                         bool /* is_last */, std::vector<uint8_t> /* data */) override {}
  void SetVendorSpecificCharacteristic(
          const std::vector<bluetooth::ras::VendorSpecificCharacteristic>&
          /* vendor_specific_characteristics */) override {}
};

namespace bluetooth {
namespace ras {

RasServer* GetRasServer() {
  static MockRasServer* instance = nullptr;
  if (instance == nullptr) {
    instance = new MockRasServer();
  }
  return instance;
}

}  // namespace ras
}  // namespace bluetooth
