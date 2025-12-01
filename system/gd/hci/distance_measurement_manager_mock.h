/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "hci/distance_measurement_manager.h"

// Unit test interfaces
namespace bluetooth {
namespace hci {

namespace testing {

class MockDistanceMeasurementCallbacks : public DistanceMeasurementCallbacks {
public:
  MOCK_METHOD(void, OnDistanceMeasurementStarted, (Address, DistanceMeasurementMethod));
  MOCK_METHOD(void, OnDistanceMeasurementStopped,
              (Address, DistanceMeasurementErrorCode, DistanceMeasurementMethod));
  MOCK_METHOD(void, OnDistanceMeasurementResult,
              (Address, uint32_t, uint32_t, int, int, int, int, uint64_t, int, int, int8_t, double,
               DistanceMeasurementDetectedAttackLevel, double, DistanceMeasurementMethod));
  MOCK_METHOD(void, OnRasFragmentReady,
              (Address address, uint16_t procedure_counter, bool is_last,
               std::vector<uint8_t> raw_data));
  MOCK_METHOD(void, OnVendorSpecificCharacteristics,
              (std::vector<hal::VendorSpecificCharacteristic> vendor_specific_characteristics));
  MOCK_METHOD(void, OnVendorSpecificReply,
              (Address address, std::vector<bluetooth::hal::VendorSpecificCharacteristic>
                                        vendor_specific_characteristics));
  MOCK_METHOD(void, OnHandleVendorSpecificReplyComplete, (Address address, bool success));
  MOCK_METHOD(void, OnRangingHardwareOffloadEnabled, ());
};

class MockDistanceMeasurementManager : public DistanceMeasurementManager {
public:
  MOCK_METHOD(void, RegisterDistanceMeasurementCallbacks,
              (DistanceMeasurementCallbacks * callbacks), (override));
  MOCK_METHOD(void, StartDistanceMeasurement,
              (int32_t app_uid, const Address&, uint16_t connection_handle,
               hci::Role local_hci_role, uint16_t interval, DistanceMeasurementMethod method,
               DistanceMeasurementSightType sight_type,
               DistanceMeasurementLocationType location_type),
              (override));
  MOCK_METHOD(void, StopDistanceMeasurement,
              (const Address& address, uint16_t connection_handle,
               DistanceMeasurementMethod method),
              (override));
  MOCK_METHOD(void, HandleRasClientConnectedEvent,
              (const Address& address, uint16_t connection_handle, uint16_t att_handle,
               const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data,
               uint16_t conn_interval),
              (override));
  MOCK_METHOD(void, HandleRasClientDisconnectedEvent,
              (const Address& address, const ras::RasDisconnectReason& ras_disconnect_reason),
              (override));
  MOCK_METHOD(void, HandleVendorSpecificReply,
              (const Address& address, uint16_t connection_handle,
               const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply),
              (override));
  MOCK_METHOD(void, HandleRasServerConnected,
              (const Address& identity_address, uint16_t connection_handle,
               hci::Role local_hci_role),
              (override));
  MOCK_METHOD(void, HandleMtuChanged, (uint16_t connection_handle, uint16_t mtu), (override));
  MOCK_METHOD(void, HandleRasServerDisconnected,
              (const Address& identity_address, uint16_t connection_handle), (override));
  MOCK_METHOD(void, HandleVendorSpecificReplyComplete,
              (const Address& address, uint16_t connection_handle, bool success), (override));
  MOCK_METHOD(void, HandleRemoteData,
              (const Address& address, uint16_t connection_handle,
               const std::vector<uint8_t>& raw_data),
              (override));
  MOCK_METHOD(void, HandleRemoteDataTimeout, (const Address& address, uint16_t connection_handle),
              (override));
  MOCK_METHOD(void, HandleConnIntervalUpdated,
              (const Address& address, uint16_t connection_handle, uint16_t conn_interval),
              (override));
};

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
