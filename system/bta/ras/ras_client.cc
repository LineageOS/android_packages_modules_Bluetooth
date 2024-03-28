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
#include <base/functional/bind.h>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;

namespace {

class RasClientImpl;
RasClientImpl* instance;

class RasClientImpl : public bluetooth::ras::RasClient {
 public:
  void Initialize() override {
    BTA_GATTC_AppRegister(
        [](tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
          if (instance && p_data) instance->GattcCallback(event, p_data);
        },
        base::Bind([](uint8_t client_id, uint8_t status) {
          if (status != GATT_SUCCESS) {
            log::error("Can't start Gatt client for Ranging Service");
            return;
          }
          log::info("Initialize, client_id {}", client_id);
          instance->gatt_if_ = client_id;
        }),
        true);
  }

  void GattcCallback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
    log::info("event: {}", gatt_client_event_text(event));
    switch (event) {
      default:
        log::warn("Unhandled event: {}", gatt_client_event_text(event).c_str());
    }
  }

 private:
  uint16_t gatt_if_;
};

}  // namespace

bluetooth::ras::RasClient* bluetooth::ras::GetRasClient() {
  if (instance == nullptr) {
    instance = new RasClientImpl();
  }
  return instance;
};
