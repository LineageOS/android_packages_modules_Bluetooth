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

#include <memory>
#include <vector>

#include "hci/address_with_type.h"
#include "hci/controller.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"
#include "hci/le_address_manager.h"
#include "hci/le_scanning_callback.h"
#include "hci/le_scanning_manager.h"
#include "storage/storage_module.h"

namespace bluetooth {
namespace hci {

class LeScanningManagerImpl : public LeScanningManager {
public:
  static constexpr uint8_t kMaxAppNum = 32;
  static constexpr uint8_t kAdvertisingDataInfoNotPresent = 0xff;
  static constexpr uint8_t kTxPowerInformationNotPresent = 0x7f;
  static constexpr uint8_t kNotPeriodicAdvertisement = 0x00;
  static constexpr ScannerId kInvalidScannerId = 0xFF;
  static constexpr uint8_t k1mPhyMask = 1;
  static constexpr uint8_t kCodedPhyMask = 1 << 2;
  static constexpr uint16_t kLeScanIntervalLowLatency = 160;  // 100ms = 160 * 0.625ms
  static constexpr uint16_t kLeScanWindowLowLatency = 160;    // 100ms = 160 * 0.625ms

  LeScanningManagerImpl(os::Handler* handler, hci::HciInterface* hci_layer,
                        hci::Controller* controller, hci::LeAddressManager* le_address_manager,
                        storage::StorageModule* storage_module);
  LeScanningManagerImpl(const LeScanningManagerImpl&) = delete;
  LeScanningManagerImpl& operator=(const LeScanningManagerImpl&) = delete;

  ~LeScanningManagerImpl();

  void RegisterScanner(const Uuid app_uuid) override;

  void Unregister(ScannerId scanner_id) override;

  void Scan(bool start) override;

  void SetScanParameters(LeScanType scan_type, ScannerId scanner_id_1m, uint16_t scan_interval_1m,
                         uint16_t scan_window_1m, ScannerId scanner_id_coded,
                         uint16_t scan_interval_coded, uint16_t scan_window_coded,
                         uint8_t scan_phy) override;

  void SetScanFilterPolicy(LeScanningFilterPolicy filter_policy) override;

  /* Scan filter */
  void ScanFilterEnable(bool enable) override;

  void ScanFilterParameterSetup(ApcfAction action, uint8_t filter_index,
                                AdvertisingFilterParameter advertising_filter_parameter) override;

  void ScanFilterAdd(uint8_t filter_index,
                     std::vector<AdvertisingPacketContentFilterCommand> filters) override;

  /*Batch Scan*/
  void BatchScanConfigStorage(uint8_t batch_scan_full_max, uint8_t batch_scan_truncated_max,
                              uint8_t batch_scan_notify_threshold, ScannerId scanner_id) override;
  void BatchScanEnable(BatchScanMode scan_mode, uint32_t duty_cycle_scan_window_slots,
                       uint32_t duty_cycle_scan_interval_slots,
                       BatchScanDiscardRule batch_scan_discard_rule) override;
  void BatchScanDisable() override;
  void BatchScanReadReport(ScannerId scanner_id, BatchScanMode scan_mode) override;

  void StartSync(uint8_t sid, const AddressWithType& address, uint16_t skip, uint16_t timeout,
                 int reg_id) override;

  void StopSync(uint16_t handle) override;

  void CancelCreateSync(uint8_t sid, const Address& address) override;

  void TransferSync(const Address& address, uint16_t handle, uint16_t service_data,
                    uint16_t sync_handle, int pa_source) override;

  void TransferSetInfo(const Address& address, uint16_t handle, uint16_t service_data,
                       uint8_t adv_handle, int pa_source) override;

  void SyncTxParameters(const Address& addr, uint8_t mode, uint16_t skip, uint16_t timeout,
                        int reg_id) override;

  void TrackAdvertiser(uint8_t filter_index, ScannerId scanner_id) override;

  void RegisterScanningCallback(ScanningCallback* scanning_callback) override;

  bool IsAdTypeFilterSupported() const override;

  bool Is1mPhyConfigured() const override;

  bool IsCodedPhyConfigured() const override;

  bool IsScanActive() const override;

  uint32_t GetIntervalMs1m() const override;

  uint16_t GetWindowMs1m() const override;

  uint32_t GetIntervalMsCoded() const override;

  uint16_t GetWindowMsCoded() const override;

  void StartDiscovery(uint8_t duration) override;

  void StopDiscovery() override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace hci
}  // namespace bluetooth
