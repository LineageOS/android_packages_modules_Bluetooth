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

#include <unordered_map>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "os/log.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/gap_api.h"
#include "types/bluetooth/uuid.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;

namespace ras {
std::string uuid::getUuidName(const bluetooth::Uuid& uuid) {
  switch (uuid.As16Bit()) {
    case kRangingService16Bit:
      return "Ranging Service";
    case kRasFeaturesCharacteristic16bit:
      return "RAS Features";
    case kRasRealTimeRangingDataCharacteristic16bit:
      return "Real-time Ranging Data";
    case kRasOnDemandDataCharacteristic16bit:
      return "On-demand Ranging Data";
    case kRasControlPointCharacteristic16bit:
      return "RAS Control Point (RAS-CP)";
    case kRasRangingDataReadyCharacteristic16bit:
      return "Ranging Data Ready";
    case kRasRangingDataOverWrittenCharacteristic16bit:
      return "Ranging Data Overwritten";
    case kClientCharacteristicConfiguration16bit:
      return "Client Characteristic Configuration";
    default:
      return "Unknown UUID";
  }
}

}  // namespace ras
