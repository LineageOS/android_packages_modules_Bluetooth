/*
 * Copyright 2025 The Android Open Source Project
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

#include <bluetooth/metrics/metric_id_api.h>
#include <bluetooth/metrics/os_metrics.h>
#include <gmock/gmock.h>

namespace bluetooth::metrics {

struct LeAudioMetricsCodecInfoVector {
  std::vector<int32_t> codec_format;
  std::vector<int32_t> vendor_company_id;
  std::vector<int32_t> vendor_codec_id;
  std::vector<int32_t> sink_sampling_frequency_hz;
  std::vector<int32_t> source_sampling_frequency_hz;
  std::vector<bool> is_dsa_active;
  std::vector<bool> is_gmap_active;

  bool operator==(const LeAudioMetricsCodecInfoVector& other) const {
    return codec_format == other.codec_format &&
           vendor_company_id == other.vendor_company_id &&
           vendor_codec_id == other.vendor_codec_id &&
           sink_sampling_frequency_hz == other.sink_sampling_frequency_hz &&
           source_sampling_frequency_hz == other.source_sampling_frequency_hz &&
           is_dsa_active == other.is_dsa_active && is_gmap_active == other.is_gmap_active;
  }
};

class MockMetrics {
public:
  static void SetInstance(std::shared_ptr<MockMetrics> instance);

  // Methods from metric_id_api.h
  MOCK_METHOD(bool, InitMetricIdAllocator,
              ((const std::unordered_map<RawAddress, int>&), CallbackLegacy, CallbackLegacy));
  MOCK_METHOD(bool, CloseMetricIdAllocator, ());
  MOCK_METHOD(bool, IsEmptyMetricIdAllocator, ());
  MOCK_METHOD(int, AllocateIdFromMetricIdAllocator, (const RawAddress&));
  MOCK_METHOD(bool, SaveDeviceOnMetricIdAllocator, (const RawAddress&));
  MOCK_METHOD(void, ForgetDeviceFromMetricIdAllocator, (const RawAddress&));
  MOCK_METHOD(bool, IsValidIdFromMetricIdAllocator, (int));

  // Methods from os_metrics.h
  MOCK_METHOD(void, Counter, (bluetooth::metrics::CounterKey, int64_t));
  MOCK_METHOD(void, LogBluetoothEvent, (const hci::Address&, EventType, State));
  MOCK_METHOD(void, LogBluetoothEvent, (const hci::Address&, EventType, State, int));
  MOCK_METHOD(void, LogMetricLinkLayerConnectionEvent,
              (const hci::Address&, uint32_t, android::bluetooth::DirectionEnum, uint16_t, uint32_t,
               uint16_t, uint16_t, uint16_t, uint16_t));
  MOCK_METHOD(void, LogMetricHciTimeoutEvent, (uint32_t));
  MOCK_METHOD(void, LogMetricRemoteVersionInfo, (uint16_t, uint8_t, uint8_t, uint16_t, uint16_t));
  MOCK_METHOD(void, LogMetricA2dpAudioOverrunEvent, (const hci::Address&, uint64_t, int, int, int));
  MOCK_METHOD(void, LogMetricA2dpPlaybackEvent, (const hci::Address&, int, int));
  MOCK_METHOD(void, LogA2dpSessionReported, (const hci::Address&, const A2dpSession&));
  MOCK_METHOD(void, LogMetricHfpPacketLossStats, (const hci::Address&, int, double, uint16_t));
  MOCK_METHOD(void, LogMetricSmpPairingEvent,
              (const hci::Address&, uint16_t, android::bluetooth::DirectionEnum, uint16_t));
  MOCK_METHOD(void, LogMetricClassicPairingEvent,
              (const hci::Address&, uint16_t, uint32_t, uint16_t, uint16_t, uint16_t, int64_t));
  MOCK_METHOD(void, LogMetricSdpAttribute,
              (const hci::Address&, uint16_t, uint16_t, size_t, const char*));
  MOCK_METHOD(void, LogMetricSocketConnectionState,
              (const hci::Address&, int, int, android::bluetooth::SocketConnectionstateEnum,
               int64_t, int64_t, int, int, android::bluetooth::SocketRoleEnum, uint64_t,
               android::bluetooth::SocketErrorEnum, bool));
  MOCK_METHOD(void, LogMetricManufacturerInfo,
              (const hci::Address&, android::bluetooth::AddressTypeEnum,
               android::bluetooth::DeviceInfoSrcEnum, const std::string&, const std::string&,
               const std::string&, const std::string&, const std::string&));
  MOCK_METHOD(void, LogMetricBluetoothHalCrashReason, (const hci::Address&, uint32_t, uint32_t));
  MOCK_METHOD(void, LogMetricBluetoothLocalSupportedFeatures, (uint32_t, uint64_t));
  MOCK_METHOD(void, LogMetricBluetoothLocalVersions,
              (uint32_t, uint8_t, uint32_t, uint8_t, uint32_t));
  MOCK_METHOD(void, LogMetricBluetoothDisconnectionReasonReported,
              (uint32_t, const hci::Address&, uint32_t));
  MOCK_METHOD(void, LogMetricBluetoothRemoteSupportedFeatures,
              (const hci::Address&, uint32_t, uint64_t, uint32_t));
  MOCK_METHOD(void, LogMetricBluetoothLEConnection, (LEConnectionSessionOptions));
  MOCK_METHOD(void, LogMetricRfcommConnectionAtClose,
              (const hci::Address&, android::bluetooth::rfcomm::PortResult,
               android::bluetooth::rfcomm::SocketConnectionSecurity,
               android::bluetooth::rfcomm::RfcommPortEvent,
               android::bluetooth::rfcomm::RfcommPortState, int32_t, int32_t,
               android::bluetooth::BtaStatus, bool, bool, int32_t));
  MOCK_METHOD(void, LogMetricLeAudioConnectionSessionReported,
              (int32_t, int32_t, int64_t, const std::vector<int64_t>&, const std::vector<int64_t>&,
               const std::vector<int64_t>&, const std::vector<int32_t>&,
               const std::vector<int32_t>&, const std::vector<RawAddress>&,
               const std::vector<int64_t>&, const std::vector<int64_t>&,
               const std::vector<int32_t>&, const LeAudioMetricsCodecInfoVector&));
  MOCK_METHOD(void, LogMetricLeAudioBroadcastSessionReported, (int64_t));
  MOCK_METHOD(void, LogMetricBluetoothQualityReport, (const RawAddress&,
                                                      const bqr::BqrLinkQualityEvent&));
  MOCK_METHOD(void, LogMetricsChannelSoundingRequesterSessionReported,
              (const hci::Address&, const std::vector<int32_t>&, const std::vector<int32_t>&,
               const std::vector<int32_t>&, android::bluetooth::ChannelSoundingStopReason, int32_t,
               int32_t, bool, android::bluetooth::ChannelSoundingType, int32_t, int32_t));
  MOCK_METHOD(void, LogMetricBluetoothEnergyMonitorReported,
              (uint16_t, const bqr::BqrEnergyMonitoringEventV7&));
  MOCK_METHOD(void, LogMetricBluetoothRFStatsReported, (uint16_t, const bqr::BqrRFStatsEvent&));
  MOCK_METHOD(void, LogGattOffloadSessionStateChanged,
              (const hci::Address& address, int32_t session_id,
               android::bluetooth::gatt::GattRoleEnum gatt_role,
               android::bluetooth::gatt::GattOffloadSessionStateEnum state,
               int32_t gatt_characteristic_properties_bitmask, int64_t session_duration_ms,
               android::bluetooth::gatt::GattOffloadErrorEnum error_code, int32_t uid,
               const std::string& attribution_tag));
};

}  // namespace bluetooth::metrics
