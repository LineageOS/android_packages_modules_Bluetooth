/*
 * Copyright (C) 2025 The Android Open Source Project
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
#include <string>

#include "hal/ranging_hal.h"

namespace bluetooth {
namespace hal {
namespace testing {
class MockRangingHal : public RangingHal {
public:
  MOCK_METHOD(bool, IsBound, ());
  MOCK_METHOD(RangingHalVersion, GetRangingHalVersion, ());
  MOCK_METHOD(std::vector<VendorSpecificCharacteristic>, GetVendorSpecificCharacteristics, ());
  MOCK_METHOD(void, OpenSession,
              (uint16_t connection_handle, uint16_t att_handle,
               const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data));
  MOCK_METHOD(void, HandleVendorSpecificReply,
              (uint16_t connection_handle,
               const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply));
  MOCK_METHOD(void, WriteRawData,
              (uint16_t connection_handle, const ChannelSoundingRawData& raw_data));
  MOCK_METHOD(void, UpdateChannelSoundingConfig,
              (uint16_t connection_handle,
               const hci::LeCsConfigCompleteView& leCsConfigCompleteView,
               uint8_t local_supported_sw_time, uint8_t remote_supported_sw_time,
               uint16_t conn_interval));
  MOCK_METHOD(void, UpdateConnInterval, (uint16_t connection_handle, uint16_t conn_interval));
  MOCK_METHOD(void, UpdateProcedureEnableConfig,
              (uint16_t connection_handle,
               const hci::LeCsProcedureEnableCompleteView& leCsProcedureEnableCompleteView));
  MOCK_METHOD(void, WriteProcedureData,
              (uint16_t connection_handle, hci::CsRole local_cs_role,
               const ProcedureDataV2& procedure_data, uint16_t procedure_counter));
  MOCK_METHOD(bool, IsAbortedProcedureRequired, (uint16_t connection_handle));

  void RegisterCallback(RangingHalCallback* callback) override { ranging_hal_callback_ = callback; }
  RangingHalCallback* GetRangingHalCallback() { return ranging_hal_callback_; }

  void Start() override {}
  void Stop() override {}
  void ListDependencies(ModuleList* /*list*/) const override {}
  std::string ToString() const override { return std::string("mock ranging hal"); }

private:
  RangingHalCallback* ranging_hal_callback_ = nullptr;
};
}  // namespace testing
}  // namespace hal
}  // namespace bluetooth
