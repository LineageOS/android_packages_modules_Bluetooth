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

#include "periodic_sync/periodic_sync_shim.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

#include "hardware/ble_scanner.h"
#include "main/shim/le_scanning_manager.h"
#include "periodic_sync/ffi.rs.h"
#include "rust/cxx.h"

namespace bluetooth::shim {
namespace {

using ::bluetooth::Uuid;
using ::bluetooth::shim::ffi::PeriodicSyncCallbacks;
using ::ffi::Address;

namespace shim_ffi = ::bluetooth::shim::ffi;

RawAddress ToRawAddress(Address addr) { return RawAddress::FromUint64(addr.value); }

Address FromRawAddress(RawAddress raw_addr) { return {raw_addr.ToUint64()}; }

}  // namespace

std::unique_ptr<BleScannerInterfaceShim> GetBleScannerInterfaceShim() {
  return std::make_unique<BleScannerInterfaceShim>();
}

BleScannerInterfaceShim::BleScannerInterfaceShim()
    : ble_scanner_interface_(get_ble_scanner_instance()) {}

void BleScannerInterfaceShim::StartSync(uint8_t advertising_sid, Address advertiser_addr,
                                        uint8_t advertiser_addr_type, uint16_t skip,
                                        uint16_t timeout, int32_t reg_id) {
  if (!ble_scanner_interface_) {
    log::warn("ble_scanner_interface_ is null.");
    return;
  }

  ble_scanner_interface_->StartSync(advertising_sid, ToRawAddress(advertiser_addr),
                                    advertiser_addr_type, skip, timeout, reg_id,
                                    kScannerClientIdLeAudio);
}

void BleScannerInterfaceShim::StopSync(uint16_t handle) {
  if (!ble_scanner_interface_) {
    log::warn("ble_scanner_interface_ is null.");
    return;
  }

  ble_scanner_interface_->StopSync(handle);
}

void BleScannerInterfaceShim::RegisterCallbacksNative(rust::Box<PeriodicSyncCallbacks> cb,
                                                      uint8_t client_id) {
  if (!ble_scanner_interface_) {
    log::warn("ble_scanner_interface_ is null.");
    return;
  }

  callback_shim_ = std::make_unique<ScanningCallbackShim>(std::move(cb));
  ble_scanner_interface_->RegisterCallbacksNative(callback_shim_.get(), client_id);
}

ScanningCallbackShim::ScanningCallbackShim(rust::Box<PeriodicSyncCallbacks> cb)
    : callbacks_(std::move(cb)) {}

void ScanningCallbackShim::OnScannerRegistered(const Uuid, uint8_t, uint8_t) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnSetScannerParameterComplete(uint8_t, uint8_t) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnScanResult(uint16_t, uint8_t, RawAddress, uint8_t, uint8_t, uint8_t,
                                        int8_t, int8_t, uint16_t, std::vector<uint8_t>) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnTrackAdvFoundLost(AdvertisingTrackInfo) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnBatchScanReports(int, int, int, int, std::vector<uint8_t>) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnBatchScanThresholdCrossed(int) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnPeriodicSyncTransferred(int, uint8_t, RawAddress) {
  // Ignored: Not used by the periodic sync manager.
}

void ScanningCallbackShim::OnPeriodicSyncStarted(int reg_id, uint8_t status, uint16_t sync_handle,
                                                 uint8_t advertising_sid,
                                                 uint8_t advertiser_addr_type,
                                                 RawAddress advertiser_addr, uint8_t phy,
                                                 uint16_t sync_interval) {
  callbacks_->OnPeriodicSyncStarted(reg_id, status, sync_handle, advertising_sid,
                                    advertiser_addr_type, FromRawAddress(advertiser_addr), phy,
                                    sync_interval);
}

void ScanningCallbackShim::OnPeriodicSyncReport(uint16_t sync_handle, int8_t tx_power, int8_t rssi,
                                                uint8_t status, std::vector<uint8_t> data) {
  callbacks_->OnPeriodicSyncReport(sync_handle, tx_power, rssi, status,
                                   rust::Slice<const uint8_t>(data.data(), data.size()));
}

void ScanningCallbackShim::OnPeriodicSyncLost(uint16_t sync_handle) {
  callbacks_->OnPeriodicSyncLost(sync_handle);
}

void ScanningCallbackShim::OnBigInfoReport(uint16_t sync_handle, bool encrypted) {
  callbacks_->OnBigInfoReport(sync_handle, encrypted);
}

}  // namespace bluetooth::shim
