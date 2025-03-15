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

// AIDL uses syslog.h, so these defines conflict with log/log.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING

#include "ranging_hal.h"

namespace bluetooth {
namespace hal {

class RangingHalHost : public RangingHal {
public:
  bool IsBound() override { return false; }
  RangingHalVersion GetRangingHalVersion() { return V_UNKNOWN; }
  void RegisterCallback(RangingHalCallback* /* callback */) override {}
  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override {
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics = {};
    return vendor_specific_characteristics;
  }
  void OpenSession(uint16_t /* connection_handle */, uint16_t /* att_handle */,
                   const std::vector<hal::VendorSpecificCharacteristic>& /* vendor_specific_data */)
          override {}

  void HandleVendorSpecificReply(
          uint16_t /* connection_handle */,
          const std::vector<hal::VendorSpecificCharacteristic>& /* vendor_specific_reply */)
          override {}

  void WriteRawData(uint16_t /* connection_handle */,
                    const ChannelSoundingRawData& /* raw_data */) override {}

  void UpdateChannelSoundingConfig(uint16_t /* connection_handle */,
                                   const hci::LeCsConfigCompleteView& /* leCsConfigCompleteView */,
                                   uint8_t /* local_supported_sw_time */,
                                   uint8_t /* remote_supported_sw_time */,
                                   uint16_t /* conn_interval */) override {}

  void UpdateProcedureEnableConfig(
          uint16_t /* connection_handle */,
          const hci::LeCsProcedureEnableCompleteView& /* leCsProcedureEnableCompleteView */)
          override {}

  void WriteProcedureData(uint16_t /* connection_handle */, hci::CsRole /* local_cs_role */,
                          const ProcedureDataV2& /* procedure_data */,
                          uint16_t /* procedure_counter */) {}

  void UpdateConnInterval(uint16_t /* connection_handle */, uint16_t /* conn_interval */) override {
  }

  bool IsAbortedProcedureRequired(uint16_t /*connection_handle*/) { return false; }

protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {}

  void Stop() override {}

  std::string ToString() const override { return std::string("RangingHalHost"); }
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalHost(); });

}  // namespace hal
}  // namespace bluetooth
