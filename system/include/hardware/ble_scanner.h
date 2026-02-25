/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef ANDROID_INCLUDE_BLE_SCANNER_H
#define ANDROID_INCLUDE_BLE_SCANNER_H

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <stdint.h>

#include <memory>
#include <vector>

#include "bt_common_types.h"
#include "bt_gatt_client.h"
#include "bt_gatt_types.h"

constexpr uint8_t kScannerClientIdJni = 0xff;
constexpr uint8_t kScannerClientIdLeAudio = 0x1;

class AdvertisingTrackInfo {
public:
  // For MSFT-based advertisement monitor.
  uint8_t monitor_handle;
  uint8_t scanner_id;
  uint8_t filter_index;
  uint8_t advertiser_state;
  uint8_t advertiser_info_present;
  RawAddress advertiser_address;
  uint8_t advertiser_address_type;
  uint8_t tx_power;
  int8_t rssi;
  uint16_t time_stamp;
  uint8_t adv_packet_len;
  std::vector<uint8_t> adv_packet;
  uint8_t scan_response_len;
  std::vector<uint8_t> scan_response;
};

/**
 * LE Scanning related callbacks invoked from from the Bluetooth native stack
 * All callbacks are invoked on the JNI thread
 */
class ScanningCallbacks {
public:
  virtual ~ScanningCallbacks() = default;
  virtual void OnScannerRegistered(const bluetooth::Uuid app_uuid, uint8_t scannerId,
                                   uint8_t status) = 0;
  virtual void OnSetScannerParameterComplete(uint8_t scannerId, uint8_t status) = 0;
  virtual void OnScanResult(uint16_t event_type, uint8_t addr_type, RawAddress bda,
                            uint8_t primary_phy, uint8_t secondary_phy, uint8_t advertising_sid,
                            int8_t tx_power, int8_t rssi, uint16_t periodic_adv_int,
                            std::vector<uint8_t> adv_data) = 0;
  virtual void OnTrackAdvFoundLost(AdvertisingTrackInfo advertising_track_info) = 0;
  virtual void OnBatchScanReports(int client_if, int status, int report_format, int num_records,
                                  std::vector<uint8_t> data) = 0;
  virtual void OnBatchScanThresholdCrossed(int client_if) = 0;
  virtual void OnPeriodicSyncStarted(int reg_id, uint8_t status, uint16_t sync_handle,
                                     uint8_t advertising_sid, uint8_t address_type,
                                     RawAddress address, uint8_t phy, uint16_t interval) = 0;
  virtual void OnPeriodicSyncReport(uint16_t sync_handle, int8_t tx_power, int8_t rssi,
                                    uint8_t status, std::vector<uint8_t> data) = 0;
  virtual void OnPeriodicSyncLost(uint16_t sync_handle) = 0;
  virtual void OnPeriodicSyncTransferred(int pa_source, uint8_t status, RawAddress address) = 0;
  virtual void OnBigInfoReport(uint16_t sync_handle, bool encrypted) = 0;
};

class BleScannerInterface {
public:
  virtual ~BleScannerInterface() = default;

  using RegisterCallback =
          base::OnceCallback<void(uint8_t /* scanner_id */, uint8_t /* btm_status */)>;

  using Callback = base::OnceCallback<void(uint8_t /* btm_status */)>;

  using EnableCallback = base::OnceCallback<void(uint8_t /* action */, uint8_t /* btm_status */)>;

  using FilterParamSetupCallback = base::OnceCallback<void(
          uint8_t /* avbl_space */, uint8_t /* action_type */, uint8_t /* btm_status */)>;

  using FilterConfigCallback =
          base::OnceCallback<void(uint8_t /* filt_type */, uint8_t /* avbl_space */,
                                  uint8_t /* action */, uint8_t /* btm_status */)>;

  using MsftAdvMonitorAddCallback =
          base::OnceCallback<void(uint8_t /* monitor_handle */, uint8_t /* status */)>;

  using MsftAdvMonitorRemoveCallback = base::OnceCallback<void(uint8_t /* status */)>;

  using MsftAdvMonitorEnableCallback =
          base::OnceCallback<void(bool /* enable */, uint8_t /* status */)>;

  /** Registers a scanner with the stack */
  virtual void RegisterScanner(const bluetooth::Uuid& app_uuid) = 0;

  /** Unregister a scanner from the stack */
  virtual void Unregister(int scanner_id) = 0;

  /** Start or stop LE device scanning */
  virtual void Scan(bool start) = 0;

  /** Setup scan filter params */
  virtual void ScanFilterParamSetup(uint8_t client_if, uint8_t action, uint8_t filt_index,
                                    std::unique_ptr<btgatt_filt_param_setup_t> filt_param,
                                    FilterParamSetupCallback cb) = 0;

  /** Configure a scan filter condition  */
  virtual void ScanFilterAdd(int filter_index, std::vector<ApcfCommand> filters,
                             FilterConfigCallback cb) = 0;

  /** Clear all scan filter conditions for specific filter index*/
  virtual void ScanFilterClear(int filt_index, FilterConfigCallback cb) = 0;

  /** Enable / disable scan filter feature*/
  virtual void ScanFilterEnable(bool enable, EnableCallback cb) = 0;

  /** Is MSFT Extension supported? */
  virtual bool IsMsftSupported() = 0;

  /** Configures MSFT scan filter (advertisement monitor) */
  virtual void MsftAdvMonitorAdd(MsftAdvMonitor monitor, MsftAdvMonitorAddCallback cb) = 0;

  /** Removes previously added MSFT scan filter */
  virtual void MsftAdvMonitorRemove(uint8_t monitor_handle, MsftAdvMonitorRemoveCallback cb) = 0;

  /** Enable / disable MSFT scan filter feature */
  virtual void MsftAdvMonitorEnable(bool enable, MsftAdvMonitorEnableCallback cb) = 0;

  /** Sets the LE scan interval and window in units of N*0.625 msec */
  virtual void SetScanParameters(uint8_t scan_type, int scanner_id_1m, int scan_interval_1m,
                                 int scan_window_1m, int scanner_id_coded, int scan_interval_coded,
                                 int scan_window_coded, int scan_phy) = 0;

  /* Configure the batchscan storage */
  virtual void BatchScanConfigStorage(int client_if, int batch_scan_full_max,
                                      int batch_scan_trunc_max, int batch_scan_notify_threshold,
                                      Callback cb) = 0;

  /* Enable batchscan */
  virtual void BatchScanEnable(int scan_mode, int scan_interval, int scan_window, int addr_type,
                               int discard_rule, Callback cb) = 0;

  /* Disable batchscan */
  virtual void BatchScanDisable(Callback cb) = 0;

  /* Read out batchscan reports */
  virtual void BatchScanReadReports(int client_if, int scan_mode) = 0;

  virtual void StartSync(uint8_t sid, RawAddress address, uint8_t address_type, uint16_t skip,
                         uint16_t timeout, int reg_id, uint8_t client_id = kScannerClientIdJni) = 0;
  virtual void StopSync(uint16_t handle) = 0;

  virtual void RegisterCallbacks(ScanningCallbacks* callbacks) = 0;
  virtual void RegisterCallbacksNative(ScanningCallbacks* callbacks, uint8_t client_id) = 0;

  virtual void CancelCreateSync(uint8_t sid, RawAddress address) = 0;

  virtual void TransferSync(RawAddress address, uint16_t service_data, uint16_t sync_handle,
                            int pa_source) = 0;
  virtual void TransferSetInfo(RawAddress address, uint16_t service_data, uint8_t adv_handle,
                               int pa_source) = 0;
  virtual void SyncTxParameters(RawAddress addr, uint8_t mode, uint16_t skip, uint16_t timeout,
                                int reg_id) = 0;
};

#endif /* ANDROID_INCLUDE_BLE_SCANNER_H */
