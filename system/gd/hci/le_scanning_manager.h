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

#include <vector>

#include "hci/address_with_type.h"
#include "hci/hci_packets.h"
#include "hci/le_scanning_callback.h"

namespace bluetooth {
namespace hci {

enum class BatchScanMode : uint8_t {
  DISABLE = 0,
  TRUNCATED = 1,
  FULL = 2,
  TRUNCATED_AND_FULL = 3,
};

class LeScanningManager {
public:
  virtual ~LeScanningManager() = default;

  virtual void RegisterScanner(const Uuid app_uuid) = 0;

  virtual void Unregister(ScannerId scanner_id) = 0;

  virtual void Scan(bool start) = 0;

  virtual void SetScanParameters(LeScanType scan_type, ScannerId scanner_id_1m,
                                 uint16_t scan_interval_1m, uint16_t scan_window_1m,
                                 ScannerId scanner_id_coded, uint16_t scan_interval_coded,
                                 uint16_t scan_window_coded, uint8_t scan_phy) = 0;

  virtual void SetScanFilterPolicy(LeScanningFilterPolicy filter_policy) = 0;

  /* Scan filter */
  virtual void ScanFilterEnable(bool enable) = 0;

  virtual void ScanFilterParameterSetup(
          ApcfAction action, uint8_t filter_index,
          AdvertisingFilterParameter advertising_filter_parameter) = 0;

  virtual void ScanFilterAdd(uint8_t filter_index,
                             std::vector<AdvertisingPacketContentFilterCommand> filters) = 0;

  /*Batch Scan*/
  virtual void BatchScanConfigStorage(uint8_t batch_scan_full_max, uint8_t batch_scan_truncated_max,
                                      uint8_t batch_scan_notify_threshold,
                                      ScannerId scanner_id) = 0;
  virtual void BatchScanEnable(BatchScanMode scan_mode, uint32_t duty_cycle_scan_window_slots,
                               uint32_t duty_cycle_scan_interval_slots,
                               BatchScanDiscardRule batch_scan_discard_rule) = 0;
  virtual void BatchScanDisable() = 0;
  virtual void BatchScanReadReport(ScannerId scanner_id, BatchScanMode scan_mode) = 0;

  virtual void StartSync(uint8_t sid, const AddressWithType& address, uint16_t skip,
                         uint16_t timeout, int reg_id) = 0;

  virtual void StopSync(uint16_t handle) = 0;

  virtual void CancelCreateSync(uint8_t sid, const Address& address) = 0;

  virtual void TransferSync(const Address& address, uint16_t handle, uint16_t service_data,
                            uint16_t sync_handle, int pa_source) = 0;

  virtual void TransferSetInfo(const Address& address, uint16_t handle, uint16_t service_data,
                               uint8_t adv_handle, int pa_source) = 0;

  virtual void SyncTxParameters(const Address& addr, uint8_t mode, uint16_t skip, uint16_t timeout,
                                int reg_id) = 0;

  virtual void TrackAdvertiser(uint8_t filter_index, ScannerId scanner_id) = 0;

  virtual void RegisterScanningCallback(ScanningCallback* scanning_callback) = 0;

  virtual bool IsAdTypeFilterSupported() const = 0;

  virtual bool Is1mPhyConfigured() const = 0;

  virtual bool IsCodedPhyConfigured() const = 0;

  virtual bool IsScanActive() const = 0;

  virtual uint32_t GetIntervalMs1m() const = 0;

  virtual uint16_t GetWindowMs1m() const = 0;

  virtual uint32_t GetIntervalMsCoded() const = 0;

  virtual uint16_t GetWindowMsCoded() const = 0;

  virtual void StartDiscovery(uint8_t duration) = 0;

  virtual void StopDiscovery() = 0;
};
}  // namespace hci
}  // namespace bluetooth
