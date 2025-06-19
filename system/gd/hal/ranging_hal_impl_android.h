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

#include <unordered_map>

#include "ranging_hal.h"

namespace aidl::android::hardware::bluetooth::ranging {
class IBluetoothChannelSounding;
}
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSounding;

namespace bluetooth::hal {

class BluetoothChannelSoundingSessionTracker;

class RangingHalImpl : public RangingHal {
public:
  RangingHalImpl();

  ~RangingHalImpl() { bluetooth_channel_sounding_ = nullptr; }

  bool IsBound() override { return bluetooth_channel_sounding_ != nullptr; }

  RangingHalVersion GetRangingHalVersion() override { return hal_ver_; }

  void RegisterCallback(RangingHalCallback* callback) override { ranging_hal_callback_ = callback; }

  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override;

  void OpenSession(uint16_t connection_handle, uint16_t att_handle,
                   const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data,
                   uint8_t sight_type, uint8_t location_type) override;

  void HandleVendorSpecificReply(
          uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) override;

  void WriteRawData(uint16_t connection_handle, const ChannelSoundingRawData& raw_data) override;

  void UpdateChannelSoundingConfig(uint16_t connection_handle,
                                   const hci::LeCsConfigCompleteView& leCsConfigCompleteView,
                                   uint8_t local_supported_sw_time,
                                   uint8_t remote_supported_sw_time,
                                   uint16_t conn_interval) override;

  void UpdateConnInterval(uint16_t connection_handle, uint16_t conn_interval) override;

  void UpdateProcedureEnableConfig(
          uint16_t connection_handle,
          const hci::LeCsProcedureEnableCompleteView& leCsProcedureEnableCompleteView) override;

  void WriteProcedureData(const uint16_t connection_handle, hci::CsRole local_cs_role,
                          const ProcedureDataV2& procedure_data,
                          uint16_t procedure_counter) override;

  bool IsAbortedProcedureRequired(uint16_t connection_handle) override;

  std::vector<RangingSessionType> GetSupportedSessionTypes() override;

protected:
  RangingHalVersion get_ranging_hal_version();

private:
  std::shared_ptr<IBluetoothChannelSounding> bluetooth_channel_sounding_;
  RangingHalCallback* ranging_hal_callback_;
  std::unordered_map<uint16_t, std::shared_ptr<BluetoothChannelSoundingSessionTracker>>
          session_trackers_;
  RangingHalVersion hal_ver_;
};
}  // namespace bluetooth::hal
