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

#include "bluetooth/uuid.h"

namespace ras {
static const uint16_t kFeatureSize = 0x04;
static const uint16_t kRingingCounterSize = 0x02;
static const uint16_t kCccValueSize = 0x02;
static const uint16_t kControlPointCommandSize = 0x08;

namespace uuid {
static const uint16_t kRangingService16Bit = 0x7F7D;
static const uint16_t kRasFeaturesCharacteristic16bit = 0x7F7C;
static const uint16_t kRasRealTimeRangingDataCharacteristic16bit = 0x7F7B;
static const uint16_t kRasOnDemandDataCharacteristic16bit = 0x7F7A;
static const uint16_t kRasControlPointCharacteristic16bit = 0x7F79;
static const uint16_t kRasRangingDataReadyCharacteristic16bit = 0x7F78;
static const uint16_t kRasRangingDataOverWrittenCharacteristic16bit = 0x7F77;
static const uint16_t kClientCharacteristicConfiguration16bit = 0x2902;

static const bluetooth::Uuid kRangingService =
    bluetooth::Uuid::From16Bit(kRangingService16Bit);
static const bluetooth::Uuid kRasFeaturesCharacteristic =
    bluetooth::Uuid::From16Bit(kRasFeaturesCharacteristic16bit);
static const bluetooth::Uuid kRasRealTimeRangingDataCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRealTimeRangingDataCharacteristic16bit);
static const bluetooth::Uuid kRasOnDemandDataCharacteristic =
    bluetooth::Uuid::From16Bit(kRasOnDemandDataCharacteristic16bit);
static const bluetooth::Uuid kRasControlPointCharacteristic =
    bluetooth::Uuid::From16Bit(kRasControlPointCharacteristic16bit);
static const bluetooth::Uuid kRasRangingDataReadyCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRangingDataReadyCharacteristic16bit);
static const bluetooth::Uuid kRasRangingDataOverWrittenCharacteristic =
    bluetooth::Uuid::From16Bit(kRasRangingDataOverWrittenCharacteristic16bit);
static const bluetooth::Uuid kClientCharacteristicConfiguration =
    bluetooth::Uuid::From16Bit(kClientCharacteristicConfiguration16bit);

std::string getUuidName(const bluetooth::Uuid& uuid);

}  // namespace uuid

namespace feature {
static const uint32_t kRealTimeRangingData = 0x01;
static const uint32_t kRetrieveLostRangingDataSegments = 0x02;
static const uint32_t kAbortOperation = 0x04;
static const uint32_t kFilterRangingData = 0x08;
static const uint32_t kPctPhaseFormat = 0xA0;
}  // namespace feature

}  // namespace ras
