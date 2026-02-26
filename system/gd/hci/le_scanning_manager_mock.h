/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <bluetooth/types/uuid.h>
#include <gmock/gmock.h>

#include <memory>

#include "hci/address.h"
#include "hci/le_scanning_callback.h"
#include "hci/le_scanning_manager.h"

namespace bluetooth {
namespace hci {
namespace testing {

class MockScanningCallback : public ScanningCallback {
  MOCK_METHOD(void, OnScannerRegistered, (const bluetooth::Uuid, ScannerId, ScanningStatus));
  MOCK_METHOD(void, OnSetScannerParameterComplete, (ScannerId scanner_id, ScanningStatus status));
  MOCK_METHOD(void, OnScanResult,
              (uint16_t, uint8_t, Address, uint8_t, uint8_t, uint8_t, int8_t, int8_t, uint16_t,
               std::vector<uint8_t>));
  MOCK_METHOD(void, OnTrackAdvFoundLost, (AdvertisingFilterOnFoundOnLostInfo));
  MOCK_METHOD(void, OnBatchScanReports, (int, int, int, int, std::vector<uint8_t>));
  MOCK_METHOD(void, OnBatchScanThresholdCrossed, (int));
  MOCK_METHOD(void, OnTimeout, ());
  MOCK_METHOD(void, OnFilterEnable, (Enable, uint8_t));
  MOCK_METHOD(void, OnFilterParamSetup, (uint8_t, ApcfAction, uint8_t));
  MOCK_METHOD(void, OnFilterConfigCallback, (ApcfFilterType, uint8_t, ApcfAction, uint8_t));
  MOCK_METHOD(void, OnPeriodicSyncStarted,
              (int, uint8_t, uint16_t, uint8_t, AddressWithType, uint8_t, uint16_t));
  MOCK_METHOD(void, OnPeriodicSyncReport,
              (uint16_t, int8_t, int8_t, uint8_t, std::vector<uint8_t>));
  MOCK_METHOD(void, OnPeriodicSyncLost, (uint16_t));
  MOCK_METHOD(void, OnPeriodicSyncTransferred, (int, uint8_t, Address));
};

class MockLeScanningManager : public LeScanningManager {
public:
  MOCK_METHOD(void, RegisterScanner, (const Uuid), (override));
  MOCK_METHOD(void, Unregister, (ScannerId), (override));
  MOCK_METHOD(void, Scan, (bool), (override));
  MOCK_METHOD(void, SetScanParameters,
              (LeScanType, ScannerId, uint16_t, uint16_t, ScannerId, uint16_t, uint16_t, uint8_t),
              (override));
  MOCK_METHOD(void, SetScanFilterPolicy, (LeScanningFilterPolicy filter_policy), (override));
  MOCK_METHOD(void, ScanFilterEnable, (bool), (override));
  MOCK_METHOD(void, ScanFilterParameterSetup, (ApcfAction, uint8_t, AdvertisingFilterParameter),
              (override));
  MOCK_METHOD(void, ScanFilterAdd, (uint8_t, std::vector<AdvertisingPacketContentFilterCommand>),
              (override));
  MOCK_METHOD(void, BatchScanConfigStorage, (uint8_t, uint8_t, uint8_t, ScannerId), (override));
  MOCK_METHOD(void, BatchScanEnable, (BatchScanMode, uint32_t, uint32_t, BatchScanDiscardRule),
              (override));
  MOCK_METHOD(void, BatchScanDisable, (), (override));
  MOCK_METHOD(void, BatchScanReadReport, (ScannerId, BatchScanMode), (override));
  MOCK_METHOD(void, TrackAdvertiser, (uint8_t, ScannerId), (override));
  MOCK_METHOD(void, RegisterScanningCallback, (ScanningCallback*), (override));
  MOCK_METHOD(void, StartSync, (uint8_t, const AddressWithType&, uint16_t, uint16_t, int),
              (override));
  MOCK_METHOD(void, StopSync, (uint16_t), (override));
  MOCK_METHOD(void, CancelCreateSync, (uint8_t, const Address&), (override));
  MOCK_METHOD(void, TransferSync,
              (const Address&, uint16_t connection_handle, uint16_t service_data,
               uint16_t sync_handle, int pa_source),
              (override));
  MOCK_METHOD(void, TransferSetInfo,
              (const Address&, uint16_t connection_handle, uint16_t service_data, uint8_t, int),
              (override));
  MOCK_METHOD(void, SyncTxParameters, (const Address&, uint8_t, uint16_t, uint16_t, int),
              (override));
  MOCK_METHOD(bool, IsAdTypeFilterSupported, (), (const override));
  MOCK_METHOD(bool, Is1mPhyConfigured, (), (const override));
  MOCK_METHOD(bool, IsCodedPhyConfigured, (), (const override));
  MOCK_METHOD(bool, IsScanActive, (), (const override));
  MOCK_METHOD(uint32_t, GetIntervalMs1m, (), (const override));
  MOCK_METHOD(uint16_t, GetWindowMs1m, (), (const override));
  MOCK_METHOD(uint32_t, GetIntervalMsCoded, (), (const override));
  MOCK_METHOD(uint16_t, GetWindowMsCoded, (), (const override));
  MOCK_METHOD(void, StartDiscovery, (uint8_t), (override));
  MOCK_METHOD(void, StopDiscovery, (), (override));
};

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
