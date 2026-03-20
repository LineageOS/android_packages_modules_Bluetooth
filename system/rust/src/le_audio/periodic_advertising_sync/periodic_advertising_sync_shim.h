/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <rust/cxx.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "hardware/ble_scanner.h"

namespace ffi {
// Shadow struct matching Rust's Address { value: u64 }.
struct Address {
  uint64_t value;
};
}  // namespace ffi

namespace bluetooth::shim {
namespace ffi {
struct PeriodicAdvertisingSyncCallbacks;
}  // namespace ffi

class ScanningCallbackShim : public ScanningCallbacks {
public:
  explicit ScanningCallbackShim(
          rust::Box<::bluetooth::shim::ffi::PeriodicAdvertisingSyncCallbacks> callback);
  ~ScanningCallbackShim() override = default;

  void OnScannerRegistered(const ::bluetooth::Uuid app_uuid, uint8_t scannerId,
                           uint8_t status) override;
  void OnSetScannerParameterComplete(uint8_t scannerId, uint8_t status) override;
  void OnScanResult(uint16_t event_type, uint8_t addr_type, RawAddress bda, uint8_t primary_phy,
                    uint8_t secondary_phy, uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                    uint16_t periodic_adv_int, std::vector<uint8_t> adv_data) override;
  void OnTrackAdvFoundLost(AdvertisingTrackInfo advertising_track_info) override;
  void OnBatchScanReports(int client_if, int status, int report_format, int num_records,
                          std::vector<uint8_t> data) override;
  void OnBatchScanThresholdCrossed(int client_if) override;
  void OnPeriodicSyncTransferred(int pa_source, uint8_t status, RawAddress address) override;

  // Only implement below functions.
  void OnPeriodicSyncStarted(int reg_id, uint8_t status, uint16_t sync_handle,
                             uint8_t advertising_sid, uint8_t advertiser_addr_type,
                             RawAddress advertiser_addr, uint8_t advertiser_phy,
                             uint16_t periodic_advertising_interval) override;
  void OnPeriodicSyncReport(uint16_t sync_handle, int8_t tx_power, int8_t rssi, uint8_t status,
                            std::vector<uint8_t> data) override;
  void OnPeriodicSyncLost(uint16_t sync_handle) override;
  void OnBigInfoReport(uint16_t sync_handle, bool encryption) override;

private:
  rust::Box<::bluetooth::shim::ffi::PeriodicAdvertisingSyncCallbacks> callbacks_;
};

class BleScannerInterfaceShim {
public:
  BleScannerInterfaceShim();
  void StartSync(uint8_t advertising_sid, ::ffi::Address advertiser_addr,
                 uint8_t advertiser_addr_type, uint16_t skip, uint16_t timeout, int32_t reg_id);
  void StopSync(uint16_t handle);
  void RegisterCallbacksNative(
          rust::Box<::bluetooth::shim::ffi::PeriodicAdvertisingSyncCallbacks> callback,
          uint8_t client_id);

private:
  BleScannerInterface* ble_scanner_interface_;
  std::unique_ptr<ScanningCallbackShim> callback_shim_;
};

std::unique_ptr<BleScannerInterfaceShim> GetBleScannerInterfaceShim();

}  // namespace bluetooth::shim
