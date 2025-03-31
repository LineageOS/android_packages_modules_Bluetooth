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

#include <bluetooth/metrics/os_metrics.h>
#include <gmock/gmock.h>

namespace bluetooth::os {

class MockMetrics {
public:
  static void SetInstance(std::shared_ptr<MockMetrics> instance);

  MOCK_METHOD(void, LogMetricLinkLayerConnectionEvent,
              (const hci::Address&, uint32_t, android::bluetooth::DirectionEnum, uint16_t, uint32_t,
               uint16_t, uint16_t, uint16_t, uint16_t));
  MOCK_METHOD(void, LogMetricHciTimeoutEvent, (uint32_t));
  MOCK_METHOD(void, LogMetricRemoteVersionInfo, (uint16_t, uint8_t, uint8_t, uint16_t, uint16_t));
  MOCK_METHOD(void, LogMetricA2dpAudioUnderrunEvent, (const hci::Address&, uint64_t, int));
  MOCK_METHOD(void, LogMetricA2dpAudioOverrunEvent, (const hci::Address&, uint64_t, int, int, int));
  MOCK_METHOD(void, LogMetricA2dpPlaybackEvent, (const hci::Address&, int, int));
  MOCK_METHOD(void, LogMetricA2dpSessionMetricsEvent,
              (const hci::Address&, int64_t, int, int, int, int, int, int, float, int, int64_t,
               bool));
  MOCK_METHOD(void, LogMetricHfpPacketLossStats, (const hci::Address&, int, double, uint16_t));
  MOCK_METHOD(void, LogMetricReadRssiResult, (const hci::Address&, uint16_t, uint32_t, int8_t));
  MOCK_METHOD(void, LogMetricReadFailedContactCounterResult,
              (const hci::Address&, uint16_t, uint32_t, int32_t));
  MOCK_METHOD(void, LogMetricReadTxPowerLevelResult,
              (const hci::Address&, uint16_t, uint32_t, int32_t));
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
  MOCK_METHOD(void, CountCounterMetrics, (android::bluetooth::CodePathCounterKeyEnum, int64_t));
  MOCK_METHOD(void, LogMetricBluetoothLEConnection, (os::LEConnectionSessionOptions));
  MOCK_METHOD(void, LogMetricBluetoothEvent,
              (const hci::Address&, android::bluetooth::EventType, android::bluetooth::State));
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
               const std::vector<int32_t>&));
  MOCK_METHOD(void, LogMetricLeAudioBroadcastSessionReported, (int64_t));
  MOCK_METHOD(void, LogMetricBluetoothQualityReport, (const bqr::BqrLinkQualityEvent&));
};

}  // namespace bluetooth::os
