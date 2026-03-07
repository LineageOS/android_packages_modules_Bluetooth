/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "bt_shim_scanner"

#include "main/shim/le_scanning_manager.h"

#include <base/functional/bind.h>
#include <base/threading/thread.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>

#include "btif/include/btif_common.h"
#include "btif/include/btif_dm.h"
#include "hci/address.h"
#include "hci/le_scanning_manager.h"
#include "hci/msft.h"
#include "include/hardware/ble_scanner.h"
#include "main/shim/ble_scanner_interface_impl.h"
#include "main/shim/config.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "main/shim/shim.h"
#include "stack/acl/acl.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/advertise_data_parser.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/btm_status.h"
#include "stack/include/main_thread.h"
#include "storage/device.h"
#include "storage/le_device.h"
#include "storage/storage_module.h"

using namespace bluetooth;

namespace {
constexpr char kBtmLogTag[] = "SCAN";
constexpr uint16_t kAllowServiceDataFilter = 0x0040;
// Bit 8 for enable AD Type Check
constexpr uint16_t kAllowADTypeFilter = 0x100;
constexpr uint8_t kFilterLogicOr = 0x00;
constexpr uint8_t kFilterLogicAnd = 0x01;
constexpr uint8_t kLowestRssiValue = 129;
constexpr uint16_t kAllowAllFilter = 0x00;
constexpr uint16_t kListLogicOr = 0x01;
constexpr uint8_t k1mPhyMask = 1;
constexpr uint8_t kCodedPhyMask = 1 << 2;

constexpr uint16_t kScannableMask = 1 << 1;
constexpr uint16_t kScanResponseMask = 1 << 3;

class DefaultScanningCallback : public ::ScanningCallbacks {
  void OnScannerRegistered(const bluetooth::Uuid /* app_uuid */, uint8_t /* scanner_id */,
                           uint8_t /* status */) override {
    LogUnused();
  }
  void OnSetScannerParameterComplete(uint8_t /* scanner_id */, uint8_t /* status */) override {
    LogUnused();
  }
  void OnScanResult(uint16_t /* event_type */, uint8_t /* address_type */, RawAddress /* bda */,
                    uint8_t /* primary_phy */, uint8_t /* secondary_phy */,
                    uint8_t /* advertising_sid */, int8_t /* tx_power */, int8_t /* rssi */,
                    uint16_t /* periodic_advertising_interval */,
                    std::vector<uint8_t> /* advertising_data */) override {
    LogUnused();
  }
  void OnTrackAdvFoundLost(AdvertisingTrackInfo /* advertising_track_info */) override {
    LogUnused();
  }
  void OnBatchScanReports(int /* client_if */, int /* status */, int /* report_format */,
                          int /* num_records */, std::vector<uint8_t> /* data */) override {
    LogUnused();
  }
  void OnBatchScanThresholdCrossed(int /* client_if */) override { LogUnused(); }
  void OnPeriodicSyncStarted(int /* reg_id */, uint8_t /* status */, uint16_t /* sync_handle */,
                             uint8_t /* advertising_sid */, uint8_t /* address_type */,
                             RawAddress /* address */, uint8_t /* phy */,
                             uint16_t /* interval */) override {
    LogUnused();
  }
  void OnPeriodicSyncReport(uint16_t /* sync_handle */, int8_t /* tx_power */, int8_t /* rssi */,
                            uint8_t /* status */, std::vector<uint8_t> /* data */) override {
    LogUnused();
  }
  void OnPeriodicSyncLost(uint16_t /* sync_handle */) override { LogUnused(); }
  void OnPeriodicSyncTransferred(int /* pa_source */, uint8_t /* status */,
                                 RawAddress /* address */) override {
    LogUnused();
  }

  void OnBigInfoReport(uint16_t /* sync_handle */, bool /* encrypted */) override { LogUnused(); }

private:
  static void LogUnused() { log::warn("BLE Scanning callbacks have not been registered"); }
} default_scanning_callback_;

}  // namespace

::ScanningCallbacks* bluetooth::shim::default_scanning_callback =
        static_cast<::ScanningCallbacks*>(&default_scanning_callback_);
extern ::ScanningCallbacks* bluetooth::shim::default_scanning_callback;

using bluetooth::shim::BleScannerInterfaceImpl;

void BleScannerInterfaceImpl::Init() {
  log::info("init BleScannerInterfaceImpl");
  bluetooth::shim::GetScanning()->RegisterScanningCallback(this);

  if (bluetooth::shim::GetMsftExtensionManager()) {
    bluetooth::shim::GetMsftExtensionManager()->SetScanningCallback(this);
  }
}

/** Registers a scanner with the stack */
void BleScannerInterfaceImpl::RegisterScanner(const bluetooth::Uuid& app_uuid) {
  log::info("in shim layer, UUID={}", app_uuid);
  bluetooth::shim::GetScanning()->RegisterScanner(app_uuid);
}

/** Unregister a scanner from the stack */
void BleScannerInterfaceImpl::Unregister(int scanner_id) {
  log::info("in shim layer, scannerId={}", scanner_id);
  bluetooth::shim::GetScanning()->Unregister(scanner_id);
}

/** Start or stop LE device scanning */
void BleScannerInterfaceImpl::Scan(bool start) {
  log::info("in shim layer {}", (start) ? "started" : "stopped");
  bluetooth::shim::GetScanning()->Scan(start);
  if (!com_android_bluetooth_flags_migrate_btm_scan_to_gd()) {
    // TODO(b/459944050): Remove BTM scan related code when Scan Multiplexing feature is complete.
    if (start && !btm_cb.ble_ctr_cb.is_ble_observe_active()) {
      btm_cb.neighbor.le_scan = {
              .start_time_ms = timestamper_in_milliseconds.GetTimestamp(),
              .results = 0,
      };
      BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le scan started");
      btm_cb.ble_ctr_cb.set_ble_observe_active();
    } else if (!start && btm_cb.ble_ctr_cb.is_ble_observe_active()) {
      // stopped
      const uint64_t duration_timestamp =
              timestamper_in_milliseconds.GetTimestamp() - btm_cb.neighbor.le_scan.start_time_ms;
      BTM_LogHistory(
              kBtmLogTag, RawAddress::kEmpty, "Le scan stopped",
              std::format("duration_s:{:6.3f} results:{:<3}", (double)duration_timestamp / 1000.0,
                          btm_cb.neighbor.le_scan.results));
      btm_cb.ble_ctr_cb.reset_ble_observe();
      btm_cb.neighbor.le_scan = {};
    } else {
      log::warn("Invalid state: start:{}, current scan state: {}", start,
                btm_cb.ble_ctr_cb.is_ble_observe_active());
      return;
    }
  }
}
/** Setup scan filter params */
void BleScannerInterfaceImpl::ScanFilterParamSetup(
        uint8_t client_if, uint8_t action, uint8_t filter_index,
        std::unique_ptr<btgatt_filt_param_setup_t> filt_param, FilterParamSetupCallback cb) {
  log::info("in shim layer, clientIf={}", client_if);

  auto apcf_action = static_cast<bluetooth::hci::ApcfAction>(action);
  bluetooth::hci::AdvertisingFilterParameter advertising_filter_parameter;

  if (filt_param != nullptr) {
    if (filt_param && filt_param->dely_mode == 1 && apcf_action == hci::ApcfAction::ADD) {
      bluetooth::shim::GetScanning()->TrackAdvertiser(filter_index, client_if);
    }
    advertising_filter_parameter.feature_selection = filt_param->feat_seln;
    advertising_filter_parameter.list_logic_type = filt_param->list_logic_type;
    advertising_filter_parameter.filter_logic_type = filt_param->filt_logic_type;
    advertising_filter_parameter.rssi_high_thresh = filt_param->rssi_high_thres;
    advertising_filter_parameter.delivery_mode =
            static_cast<bluetooth::hci::DeliveryMode>(filt_param->dely_mode);
    if (filt_param && filt_param->dely_mode == 1) {
      advertising_filter_parameter.onfound_timeout = filt_param->found_timeout;
      advertising_filter_parameter.onfound_timeout_cnt = filt_param->found_timeout_cnt;
      advertising_filter_parameter.rssi_low_thresh = filt_param->rssi_low_thres;
      advertising_filter_parameter.onlost_timeout = filt_param->lost_timeout;
      advertising_filter_parameter.num_of_tracking_entries = filt_param->num_of_tracking_entries;
    }
  }

  bluetooth::shim::GetScanning()->ScanFilterParameterSetup(apcf_action, filter_index,
                                                           advertising_filter_parameter);
  // TODO refactor callback mechanism
  do_in_jni_thread(base::BindOnce(std::move(cb), 0, 0, btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/** Configure a scan filter condition  */
void BleScannerInterfaceImpl::ScanFilterAdd(int filter_index, std::vector<ApcfCommand> filters,
                                            FilterConfigCallback cb) {
  log::info("in shim layer");
  std::vector<bluetooth::hci::AdvertisingPacketContentFilterCommand> new_filters = {};
  for (size_t i = 0; i < filters.size(); i++) {
    bluetooth::hci::AdvertisingPacketContentFilterCommand command{};
    if (!parse_filter_command(command, filters[i])) {
      log::error("invalid apcf command");
      return;
    }
    new_filters.push_back(command);
  }
  bluetooth::shim::GetScanning()->ScanFilterAdd(filter_index, new_filters);
  do_in_jni_thread(
          base::BindOnce(std::move(cb), 0, 0, 0, btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/** Clear all scan filter conditions for specific filter index*/
void BleScannerInterfaceImpl::ScanFilterClear(int /* filter_index */,
                                              FilterConfigCallback /* cb */) {
  log::info("in shim layer");
  // This function doesn't used in java layer
}

/** Enable / disable scan filter feature*/
void BleScannerInterfaceImpl::ScanFilterEnable(bool enable, EnableCallback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->ScanFilterEnable(enable);

  uint8_t action = enable ? 1 : 0;
  do_in_jni_thread(
          base::BindOnce(std::move(cb), action, btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/** Is MSFT Extension supported? */
bool BleScannerInterfaceImpl::IsMsftSupported() {
  log::info("in shim layer");

  return bluetooth::shim::GetMsftExtensionManager()->SupportsMsftExtensions();
}

/** Adds MSFT filter */
void BleScannerInterfaceImpl::MsftAdvMonitorAdd(MsftAdvMonitor monitor,
                                                MsftAdvMonitorAddCallback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetMsftExtensionManager()->MsftAdvMonitorAdd(
          monitor, base::BindOnce(&BleScannerInterfaceImpl::OnMsftAdvMonitorAdd,
                                  base::Unretained(this), std::move(cb)));
}

/** Removes MSFT filter */
void BleScannerInterfaceImpl::MsftAdvMonitorRemove(uint8_t monitor_handle,
                                                   MsftAdvMonitorRemoveCallback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetMsftExtensionManager()->MsftAdvMonitorRemove(
          monitor_handle, base::BindOnce(&BleScannerInterfaceImpl::OnMsftAdvMonitorRemove,
                                         base::Unretained(this), std::move(cb)));
}

/** Enable / disable MSFT scan filter */
void BleScannerInterfaceImpl::MsftAdvMonitorEnable(bool enable, MsftAdvMonitorEnableCallback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetMsftExtensionManager()->MsftAdvMonitorEnable(
          enable, base::BindOnce(&BleScannerInterfaceImpl::OnMsftAdvMonitorEnable,
                                 base::Unretained(this), std::move(cb), enable));
}

/** Callback of adding MSFT filter */
void BleScannerInterfaceImpl::OnMsftAdvMonitorAdd(MsftAdvMonitorAddCallback cb,
                                                  uint8_t monitor_handle,
                                                  bluetooth::hci::ErrorCode status) {
  log::info("in shim layer");
  do_in_jni_thread(base::BindOnce(std::move(cb), monitor_handle, (uint8_t)status));
}

/** Callback of removing MSFT filter */
void BleScannerInterfaceImpl::OnMsftAdvMonitorRemove(MsftAdvMonitorRemoveCallback cb,
                                                     bluetooth::hci::ErrorCode status) {
  log::info("in shim layer");
  do_in_jni_thread(base::BindOnce(std::move(cb), (uint8_t)status));
}

/** Callback of enabling / disabling MSFT scan filter */
void BleScannerInterfaceImpl::OnMsftAdvMonitorEnable(MsftAdvMonitorEnableCallback cb, bool enable,
                                                     bluetooth::hci::ErrorCode status) {
  log::info("in shim layer");

  if (status == bluetooth::hci::ErrorCode::SUCCESS) {
    bluetooth::shim::GetScanning()->SetScanFilterPolicy(
            enable ? bluetooth::hci::LeScanningFilterPolicy::FILTER_ACCEPT_LIST_ONLY
                   : bluetooth::hci::LeScanningFilterPolicy::ACCEPT_ALL);
    msft_adv_monitor_enabled_ = enable;
  }

  do_in_jni_thread(base::BindOnce(std::move(cb), enable, (uint8_t)status));
}

/** Sets the LE scan interval and window in units of N*0.625 msec */
void BleScannerInterfaceImpl::SetScanParameters(uint8_t scan_type, int scanner_id_1m,
                                                int scan_interval_1m, int scan_window_1m,
                                                int scanner_id_coded, int scan_interval_coded,
                                                int scan_window_coded, int scan_phy) {
  log::info("in shim layer, scannerId1m={}, scannerIdCoded={}", scanner_id_1m, scanner_id_coded);
  bool validated = true;
  // clear out any scan_phy bits that aren't valid
  scan_phy = scan_phy & (k1mPhyMask | kCodedPhyMask);
  if ((scan_phy & k1mPhyMask) != 0) {
    validated =
            BTM_BLE_ISVALID_PARAM(scan_interval_1m, BTM_BLE_SCAN_INT_MIN,
                                  BTM_BLE_EXT_SCAN_INT_MAX) &&
            BTM_BLE_ISVALID_PARAM(scan_window_1m, BTM_BLE_SCAN_WIN_MIN, BTM_BLE_EXT_SCAN_WIN_MAX);
  }
  if ((scan_phy & kCodedPhyMask) != 0) {
    validated = validated &&
                BTM_BLE_ISVALID_PARAM(scan_interval_coded, BTM_BLE_SCAN_INT_MIN,
                                      BTM_BLE_EXT_SCAN_INT_MAX) &&
                BTM_BLE_ISVALID_PARAM(scan_window_coded, BTM_BLE_SCAN_WIN_MIN,
                                      BTM_BLE_EXT_SCAN_WIN_MAX);
  }
  if (validated) {
    btm_cb.ble_ctr_cb.inq_var.scan_type = BTM_BLE_SCAN_MODE_ACTI;
    btm_cb.ble_ctr_cb.inq_var.scan_interval_1m = scan_interval_1m;
    btm_cb.ble_ctr_cb.inq_var.scan_window_1m = scan_window_1m;
    btm_cb.ble_ctr_cb.inq_var.scan_interval_coded = scan_interval_coded;
    btm_cb.ble_ctr_cb.inq_var.scan_window_coded = scan_window_coded;
    btm_cb.ble_ctr_cb.inq_var.scan_phy = scan_phy;
  }

  bluetooth::shim::GetScanning()->SetScanParameters(
          static_cast<bluetooth::hci::LeScanType>(scan_type), scanner_id_1m, scan_interval_1m,
          scan_window_1m, scanner_id_coded, scan_interval_coded, scan_window_coded, scan_phy);
}

/* Configure the batchscan storage */
void BleScannerInterfaceImpl::BatchScanConfigStorage(int client_if, int batch_scan_full_max,
                                                     int batch_scan_trunc_max,
                                                     int batch_scan_notify_threshold, Callback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->BatchScanConfigStorage(batch_scan_full_max, batch_scan_trunc_max,
                                                         batch_scan_notify_threshold, client_if);
  do_in_jni_thread(base::BindOnce(std::move(cb), btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/* Enable batchscan */
void BleScannerInterfaceImpl::BatchScanEnable(int scan_mode, int scan_interval, int scan_window,
                                              int /* addr_type */, int discard_rule, Callback cb) {
  log::info("in shim layer");
  auto batch_scan_mode = static_cast<bluetooth::hci::BatchScanMode>(scan_mode);
  auto batch_scan_discard_rule = static_cast<bluetooth::hci::BatchScanDiscardRule>(discard_rule);
  bluetooth::shim::GetScanning()->BatchScanEnable(batch_scan_mode, scan_window, scan_interval,
                                                  batch_scan_discard_rule);
  do_in_jni_thread(base::BindOnce(std::move(cb), btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/* Disable batchscan */
void BleScannerInterfaceImpl::BatchScanDisable(Callback cb) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->BatchScanDisable();
  do_in_jni_thread(base::BindOnce(std::move(cb), btm_status_value(tBTM_STATUS::BTM_SUCCESS)));
}

/* Read out batchscan reports */
void BleScannerInterfaceImpl::BatchScanReadReports(int client_if, int scan_mode) {
  log::info("in shim layer");
  auto batch_scan_mode = static_cast<bluetooth::hci::BatchScanMode>(scan_mode);
  auto scanner_id = static_cast<bluetooth::hci::ScannerId>(client_if);
  bluetooth::shim::GetScanning()->BatchScanReadReport(scanner_id, batch_scan_mode);
}

void BleScannerInterfaceImpl::StartSync(uint8_t sid, RawAddress address,
                                        tBLE_ADDR_TYPE address_type, uint16_t skip,
                                        uint16_t timeout, int reg_id, uint8_t client_id) {
  log::info("in shim layer, client_id={}", client_id);
  if (!is_ble_addr_type_valid(address_type)) {
    address_type = BLE_ADDR_RANDOM;
  }
  tINQ_DB_ENT* p_i = btm_inq_db_find(address);
  if (p_i) {
    address_type = p_i->inq_info.results.ble_addr_type;  // Random
  }
  btm_random_pseudo_to_identity_addr(&address, &address_type);
  address_type &= ~BLE_ADDR_TYPE_ID_BIT;
  periodic_sync_reg_id_to_client_map_[reg_id] = client_id;
  bluetooth::shim::GetScanning()->StartSync(sid, ToAddressWithType(address, address_type), skip,
                                            timeout, reg_id);
}

void BleScannerInterfaceImpl::StopSync(uint16_t handle) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->StopSync(handle);
}

void BleScannerInterfaceImpl::CancelCreateSync(uint8_t sid, RawAddress address) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->CancelCreateSync(sid, address);
}

void BleScannerInterfaceImpl::TransferSync(RawAddress address, uint16_t service_data,
                                           uint16_t sync_handle, int pa_source) {
  log::info("in shim layer");
  tACL_CONN* p_acl = btm_acl_for_bda(address, BT_TRANSPORT_LE);
  if (p_acl == NULL ||
      !HCI_LE_PERIODIC_ADVERTISING_SYNC_TRANSFER_RECIPIENT(p_acl->peer_le_features)) {
    log::error("[PAST] Remote doesn't support PAST");
    scanning_callbacks_->OnPeriodicSyncTransferred(
            pa_source, static_cast<uint8_t>(tBTM_STATUS::BTM_MODE_UNSUPPORTED), address);
    return;
  }

  bluetooth::shim::GetScanning()->TransferSync(address, p_acl->Handle(), service_data, sync_handle,
                                               pa_source);
}

void BleScannerInterfaceImpl::TransferSetInfo(RawAddress address, uint16_t service_data,
                                              uint8_t adv_handle, int pa_source) {
  log::info("in shim layer");
  tACL_CONN* p_acl = btm_acl_for_bda(address, BT_TRANSPORT_LE);
  if (p_acl == NULL ||
      !HCI_LE_PERIODIC_ADVERTISING_SYNC_TRANSFER_RECIPIENT(p_acl->peer_le_features)) {
    log::error("[PAST] Remote doesn't support PAST");
    scanning_callbacks_->OnPeriodicSyncTransferred(
            pa_source, static_cast<uint8_t>(tBTM_STATUS::BTM_MODE_UNSUPPORTED), address);
    return;
  }

  bluetooth::shim::GetScanning()->TransferSetInfo(address, p_acl->Handle(), service_data,
                                                  adv_handle, pa_source);
}

void BleScannerInterfaceImpl::SyncTxParameters(RawAddress addr, uint8_t mode, uint16_t skip,
                                               uint16_t timeout, int reg_id) {
  log::info("in shim layer");
  bluetooth::shim::GetScanning()->SyncTxParameters(addr, mode, skip, timeout, reg_id);
}

void BleScannerInterfaceImpl::RegisterCallbacks(ScanningCallbacks* callbacks) {
  log::info("in shim layer");
  scanning_callbacks_ = callbacks;
  RegisterCallbacksNative(callbacks, kScannerClientIdJni);
}

void BleScannerInterfaceImpl::RegisterCallbacksNative(ScanningCallbacks* callbacks,
                                                      uint8_t client_id) {
  log::info("in shim layer, client_id={}", client_id);
  if (callbacks) {
    native_client_to_callbacks_map_[client_id] = callbacks;
  }
}

void BleScannerInterfaceImpl::OnScannerRegistered(const bluetooth::Uuid app_uuid,
                                                  bluetooth::hci::ScannerId scanner_id,
                                                  ScanningStatus status) {
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnScannerRegistered,
                                  base::Unretained(scanning_callbacks_), app_uuid, scanner_id,
                                  status));
}

void BleScannerInterfaceImpl::OnSetScannerParameterComplete(bluetooth::hci::ScannerId scanner_id,
                                                            ScanningStatus status) {
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnSetScannerParameterComplete,
                                  base::Unretained(scanning_callbacks_), scanner_id, status));
}

void BleScannerInterfaceImpl::on_scan_result(uint16_t event_type, uint8_t address_type,
                                             bluetooth::hci::Address address, uint8_t primary_phy,
                                             uint8_t secondary_phy, uint8_t advertising_sid,
                                             int8_t tx_power, int8_t rssi,
                                             uint16_t periodic_advertising_interval,
                                             std::vector<uint8_t> advertising_data) {
  RawAddress raw_address = ToRawAddress(address);
  tBLE_ADDR_TYPE ble_addr_type = to_ble_addr_type(address_type);

  btm_cb.neighbor.le_scan.results++;

  // Do not update device properties of already bonded devices.
  if (!get_security_client_interface().BTM_IsBonded(raw_address, BT_TRANSPORT_AUTO)) {
    // Prevent updating properties without scan response
    if (!(event_type & kScannableMask) || (event_type & kScanResponseMask) ||
        msft_adv_monitor_enabled_) {
      do_in_jni_thread(base::BindOnce(&BleScannerInterfaceImpl::handle_remote_properties,
                                      base::Unretained(this), raw_address, ble_addr_type,
                                      advertising_data));
    }
  }

  do_in_jni_thread(base::BindOnce(
          &ScanningCallbacks::OnScanResult, base::Unretained(scanning_callbacks_), event_type,
          static_cast<uint8_t>(address_type), raw_address, primary_phy, secondary_phy,
          advertising_sid, tx_power, rssi, periodic_advertising_interval, advertising_data));

  // TODO: Remove when StartInquiry in GD part implemented
  if (!(event_type & kScannableMask) || (event_type & kScanResponseMask) ||
      msft_adv_monitor_enabled_) {
    btm_ble_process_adv_pkt_cont_for_inquiry(event_type, ble_addr_type, raw_address, primary_phy,
                                             secondary_phy, advertising_sid, tx_power, rssi,
                                             periodic_advertising_interval, advertising_data);
  }
}

void BleScannerInterfaceImpl::OnScanResult(uint16_t event_type, uint8_t address_type,
                                           bluetooth::hci::Address address, uint8_t primary_phy,
                                           uint8_t secondary_phy, uint8_t advertising_sid,
                                           int8_t tx_power, int8_t rssi,
                                           uint16_t periodic_advertising_interval,
                                           std::vector<uint8_t> advertising_data) {
  do_in_main_thread(base::BindOnce(&BleScannerInterfaceImpl::on_scan_result, base::Unretained(this),
                                   event_type, address_type, address, primary_phy, secondary_phy,
                                   advertising_sid, tx_power, rssi, periodic_advertising_interval,
                                   advertising_data));
}

void BleScannerInterfaceImpl::OnTrackAdvFoundLost(
        bluetooth::hci::AdvertisingFilterOnFoundOnLostInfo on_found_on_lost_info) {
  AdvertisingTrackInfo track_info = {};
  RawAddress raw_address = ToRawAddress(on_found_on_lost_info.advertiser_address);

  if (on_found_on_lost_info.advertiser_address_type != BLE_ADDR_ANONYMOUS) {
    btm_ble_process_adv_addr(raw_address, &on_found_on_lost_info.advertiser_address_type);
  }

  track_info.monitor_handle = on_found_on_lost_info.monitor_handle;
  track_info.advertiser_address = raw_address;
  track_info.advertiser_address_type = on_found_on_lost_info.advertiser_address_type;
  track_info.scanner_id = on_found_on_lost_info.scanner_id;
  track_info.filter_index = on_found_on_lost_info.filter_index;
  track_info.advertiser_state = on_found_on_lost_info.advertiser_state;
  track_info.advertiser_info_present =
          static_cast<uint8_t>(on_found_on_lost_info.advertiser_info_present);
  if (on_found_on_lost_info.advertiser_info_present ==
      bluetooth::hci::AdvtInfoPresent::ADVT_INFO_PRESENT) {
    track_info.tx_power = on_found_on_lost_info.tx_power;
    track_info.rssi = on_found_on_lost_info.rssi;
    track_info.time_stamp = on_found_on_lost_info.time_stamp;
    auto adv_data = on_found_on_lost_info.adv_packet;
    track_info.adv_packet_len = (uint8_t)adv_data.size();
    track_info.adv_packet.reserve(adv_data.size());
    track_info.adv_packet.insert(track_info.adv_packet.end(), adv_data.begin(), adv_data.end());
    auto scan_rsp_data = on_found_on_lost_info.scan_response;
    track_info.scan_response_len = (uint8_t)scan_rsp_data.size();
    track_info.scan_response.reserve(adv_data.size());
    track_info.scan_response.insert(track_info.scan_response.end(), scan_rsp_data.begin(),
                                    scan_rsp_data.end());
  }

  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnTrackAdvFoundLost,
                                  base::Unretained(scanning_callbacks_), track_info));
}

void BleScannerInterfaceImpl::OnBatchScanReports(int client_if, int status, int report_format,
                                                 int num_records, std::vector<uint8_t> data) {
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnBatchScanReports,
                                  base::Unretained(scanning_callbacks_), client_if, status,
                                  report_format, num_records, data));
}

void BleScannerInterfaceImpl::OnBatchScanThresholdCrossed(int client_if) {
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnBatchScanThresholdCrossed,
                                  base::Unretained(scanning_callbacks_), client_if));
}

void BleScannerInterfaceImpl::OnPeriodicSyncStarted(
        int reg_id, uint8_t status, uint16_t sync_handle, uint8_t advertising_sid,
        bluetooth::hci::AddressWithType address_with_type, uint8_t phy, uint16_t interval) {
  RawAddress raw_address = ToRawAddress(address_with_type.GetAddress());
  tBLE_ADDR_TYPE ble_addr_type = to_ble_addr_type((uint8_t)address_with_type.GetAddressType());
  if (ble_addr_type & BLE_ADDR_TYPE_ID_BIT) {
    btm_identity_addr_to_random_pseudo(&raw_address, &ble_addr_type, true);
  }

  if (com_android_bluetooth_flags_support_native_pa_callback()) {
    if (periodic_sync_reg_id_to_client_map_.count(reg_id)) {
      uint8_t client_id = periodic_sync_reg_id_to_client_map_[reg_id];
      periodic_sync_reg_id_to_client_map_.erase(reg_id);

      if (status == 0) {  // Success
        periodic_sync_handle_to_client_map_[sync_handle] = client_id;
      }

      if (native_client_to_callbacks_map_.count(client_id)) {
        do_in_jni_thread(
                base::BindOnce(&ScanningCallbacks::OnPeriodicSyncStarted,
                               base::Unretained(native_client_to_callbacks_map_[client_id]), reg_id,
                               status, sync_handle, advertising_sid,
                               static_cast<int>(ble_addr_type), raw_address, phy, interval));
      }
      return;
    }
    log::warn("OnPeriodicSyncStarted: Unknown reg_id={}", reg_id);
    return;
  }
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnPeriodicSyncStarted,
                                  base::Unretained(scanning_callbacks_), reg_id, status,
                                  sync_handle, advertising_sid, static_cast<int>(ble_addr_type),
                                  raw_address, phy, interval));
}

void BleScannerInterfaceImpl::OnPeriodicSyncReport(uint16_t sync_handle, int8_t tx_power,
                                                   int8_t rssi, uint8_t status,
                                                   std::vector<uint8_t> data) {
  if (com_android_bluetooth_flags_support_native_pa_callback()) {
    if (periodic_sync_handle_to_client_map_.count(sync_handle)) {
      uint8_t client_id = periodic_sync_handle_to_client_map_[sync_handle];
      if (native_client_to_callbacks_map_.count(client_id)) {
        do_in_jni_thread(
                base::BindOnce(&ScanningCallbacks::OnPeriodicSyncReport,
                               base::Unretained(native_client_to_callbacks_map_[client_id]),
                               sync_handle, tx_power, rssi, status, data));
      }
      return;
    }
    log::warn("OnPeriodicSyncReport: Unknown sync_handle={}", sync_handle);
    return;
  }
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnPeriodicSyncReport,
                                  base::Unretained(scanning_callbacks_), sync_handle, tx_power,
                                  rssi, status, std::move(data)));
}

void BleScannerInterfaceImpl::OnPeriodicSyncLost(uint16_t sync_handle) {
  if (com_android_bluetooth_flags_support_native_pa_callback()) {
    if (periodic_sync_handle_to_client_map_.count(sync_handle)) {
      uint8_t client_id = periodic_sync_handle_to_client_map_[sync_handle];
      periodic_sync_handle_to_client_map_.erase(sync_handle);

      if (native_client_to_callbacks_map_.count(client_id)) {
        do_in_jni_thread(base::BindOnce(
                &ScanningCallbacks::OnPeriodicSyncLost,
                base::Unretained(native_client_to_callbacks_map_[client_id]), sync_handle));
      }
      return;
    }
    log::warn("OnPeriodicSyncLost: Unknown sync_handle={}", sync_handle);
    return;
  }
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnPeriodicSyncLost,
                                  base::Unretained(scanning_callbacks_), sync_handle));
}

void BleScannerInterfaceImpl::OnPeriodicSyncTransferred(int pa_source, uint8_t status,
                                                        bluetooth::hci::Address address) {
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnPeriodicSyncTransferred,
                                  base::Unretained(scanning_callbacks_), pa_source, status,
                                  ToRawAddress(address)));
}

void BleScannerInterfaceImpl::OnBigInfoReport(uint16_t sync_handle, bool encrypted) {
  if (com_android_bluetooth_flags_support_native_pa_callback()) {
    if (periodic_sync_handle_to_client_map_.count(sync_handle)) {
      uint8_t client_id = periodic_sync_handle_to_client_map_[sync_handle];
      if (native_client_to_callbacks_map_.count(client_id)) {
        do_in_jni_thread(
                base::BindOnce(&ScanningCallbacks::OnBigInfoReport,
                               base::Unretained(native_client_to_callbacks_map_[client_id]),
                               sync_handle, encrypted));
      }
      return;
    }
    log::warn("OnBigInfoReport: Unknown sync_handle={}", sync_handle);
    return;
  }
  do_in_jni_thread(base::BindOnce(&ScanningCallbacks::OnBigInfoReport,
                                  base::Unretained(scanning_callbacks_), sync_handle, encrypted));
}

void BleScannerInterfaceImpl::OnTimeout() {}
void BleScannerInterfaceImpl::OnFilterEnable(bluetooth::hci::Enable /* enable */,
                                             uint8_t /* status */) {}
void BleScannerInterfaceImpl::OnFilterParamSetup(uint8_t /* available_spaces */,
                                                 bluetooth::hci::ApcfAction /* action */,
                                                 uint8_t /* status */) {}
void BleScannerInterfaceImpl::OnFilterConfigCallback(
        bluetooth::hci::ApcfFilterType /* filter_type */, uint8_t /* available_spaces */,
        bluetooth::hci::ApcfAction /* action */, uint8_t /* status */) {}

bool BleScannerInterfaceImpl::parse_filter_command(
        bluetooth::hci::AdvertisingPacketContentFilterCommand&
                advertising_packet_content_filter_command,
        ApcfCommand apcf_command) {
  advertising_packet_content_filter_command.filter_type =
          static_cast<bluetooth::hci::ApcfFilterType>(apcf_command.type);
  bluetooth::hci::Address address = apcf_command.address;
  advertising_packet_content_filter_command.address = address;
  advertising_packet_content_filter_command.application_address_type =
          static_cast<bluetooth::hci::ApcfApplicationAddressType>(apcf_command.addr_type);

  if (!apcf_command.uuid.IsEmpty()) {
    advertising_packet_content_filter_command.uuid = apcf_command.uuid;
  }

  if (!apcf_command.uuid_mask.IsEmpty()) {
    advertising_packet_content_filter_command.uuid_mask = apcf_command.uuid_mask;
  }

  advertising_packet_content_filter_command.name.assign(apcf_command.name.begin(),
                                                        apcf_command.name.end());
  advertising_packet_content_filter_command.company = apcf_command.company;
  advertising_packet_content_filter_command.company_mask = apcf_command.company_mask;
  advertising_packet_content_filter_command.ad_type = apcf_command.ad_type;
  advertising_packet_content_filter_command.org_id = apcf_command.org_id;
  advertising_packet_content_filter_command.tds_flags = apcf_command.tds_flags;
  advertising_packet_content_filter_command.tds_flags_mask = apcf_command.tds_flags_mask;
  advertising_packet_content_filter_command.meta_data_type =
          static_cast<bluetooth::hci::ApcfMetaDataType>(apcf_command.meta_data_type);
  advertising_packet_content_filter_command.meta_data.assign(apcf_command.meta_data.begin(),
                                                             apcf_command.meta_data.end());
  advertising_packet_content_filter_command.data.assign(apcf_command.data.begin(),
                                                        apcf_command.data.end());
  advertising_packet_content_filter_command.data_mask.assign(apcf_command.data_mask.begin(),
                                                             apcf_command.data_mask.end());
  advertising_packet_content_filter_command.irk = apcf_command.irk;
  return true;
}

void BleScannerInterfaceImpl::handle_remote_properties(RawAddress bd_addr, tBLE_ADDR_TYPE addr_type,
                                                       std::vector<uint8_t> advertising_data) {
  if (!bluetooth::shim::is_gd_stack_started_up()) {
    log::warn("Gd stack is stopped, return");
    return;
  }

  // skip anonymous advertisement
  if (addr_type == BLE_ADDR_ANONYMOUS) {
    return;
  }

  auto device_type = bluetooth::hci::DeviceType::LE;
  uint8_t flag_len;
  const uint8_t* p_flag =
          AdvertiseDataParser::GetFieldByType(advertising_data, BTM_BLE_AD_TYPE_FLAG, &flag_len);

  if (p_flag != NULL && flag_len != 0) {
    if ((BTM_BLE_BREDR_NOT_SPT & *p_flag) == 0) {
      device_type = bluetooth::hci::DeviceType::DUAL;
    }
  }

  uint8_t remote_name_len;
  const uint8_t* p_eir_remote_name = AdvertiseDataParser::GetFieldByType(
          advertising_data, HCI_EIR_COMPLETE_LOCAL_NAME_TYPE, &remote_name_len);

  if (p_eir_remote_name == NULL) {
    p_eir_remote_name = AdvertiseDataParser::GetFieldByType(
            advertising_data, HCI_EIR_SHORTENED_LOCAL_NAME_TYPE, &remote_name_len);
  }

  bt_bdname_t bdname = {0};

  // update device name
  if (p_eir_remote_name) {
    if (remote_name_len > BD_NAME_LEN + 1 ||
        (remote_name_len == BD_NAME_LEN + 1 && p_eir_remote_name[BD_NAME_LEN] != '\0')) {
      log::info("dropping invalid packet - device name too long: {}", remote_name_len);
      return;
    }

    memcpy(bdname.name, p_eir_remote_name, remote_name_len);
    if (remote_name_len < BD_NAME_LEN + 1) {
      bdname.name[remote_name_len] = '\0';
    }
    btif_update_remote_properties(bd_addr, bdname.name, kDevClassEmpty, device_type);
  }

  DEV_CLASS dev_class = btm_ble_get_appearance_as_cod(advertising_data);
  if (dev_class != kDevClassUnclassified) {
    int cod = 0;
    // Use appearance to update COD if it is unknown
    if (!BtifConfigInterface::GetInt(bd_addr.ToString(), BTIF_STORAGE_KEY_DEV_CLASS, &cod) ||
        cod == COD_UNCLASSIFIED || cod == 0) {
      btif_update_remote_properties(bd_addr, bdname.name, dev_class, device_type);
    }
  }

  auto* storage_module = bluetooth::shim::GetStorage();
  bluetooth::hci::Address address = bd_addr;

  // update device type
  bluetooth::storage::Device device = storage_module->GetDeviceByLegacyKey(address);
  device.SetDeviceType(device_type);

  // update address type
  bluetooth::storage::LeDevice le_device = device.Le();
  le_device.SetAddressType((bluetooth::hci::AddressType)addr_type);
}

BleScannerInterface* bluetooth::shim::get_ble_scanner_instance() {
  static BleScannerInterfaceImpl* bt_le_scanner_instance = nullptr;
  if (bt_le_scanner_instance == nullptr) {
    bt_le_scanner_instance = new BleScannerInterfaceImpl();
  }
  return bt_le_scanner_instance;
}

void bluetooth::shim::init_scanning_manager() {
  static_cast<BleScannerInterfaceImpl*>(bluetooth::shim::get_ble_scanner_instance())->Init();
}

bool bluetooth::shim::is_ad_type_filter_supported() {
  return bluetooth::shim::GetScanning()->IsAdTypeFilterSupported();
}

void bluetooth::shim::set_ad_type_rsi_filter(bool enable) {
  bluetooth::hci::AdvertisingFilterParameter advertising_filter_parameter;
  bluetooth::shim::GetScanning()->ScanFilterParameterSetup(bluetooth::hci::ApcfAction::DELETE, 0x00,
                                                           advertising_filter_parameter);
  if (enable) {
    std::vector<bluetooth::hci::AdvertisingPacketContentFilterCommand> filters = {};
    bluetooth::hci::AdvertisingPacketContentFilterCommand filter{};
    filter.filter_type = bluetooth::hci::ApcfFilterType::AD_TYPE;
    filter.ad_type = BTM_BLE_AD_TYPE_RSI;
    filters.push_back(filter);
    bluetooth::shim::GetScanning()->ScanFilterAdd(0x00, filters);

    advertising_filter_parameter.delivery_mode = bluetooth::hci::DeliveryMode::IMMEDIATE;
    advertising_filter_parameter.feature_selection = kAllowADTypeFilter;
    advertising_filter_parameter.list_logic_type = kAllowADTypeFilter;
    advertising_filter_parameter.filter_logic_type = kFilterLogicOr;
    advertising_filter_parameter.rssi_high_thresh = kLowestRssiValue;
    bluetooth::shim::GetScanning()->ScanFilterParameterSetup(bluetooth::hci::ApcfAction::ADD, 0x00,
                                                             advertising_filter_parameter);
  }
}

void bluetooth::shim::set_empty_filter(bool enable) {
  bluetooth::hci::AdvertisingFilterParameter advertising_filter_parameter;
  bluetooth::shim::GetScanning()->ScanFilterParameterSetup(bluetooth::hci::ApcfAction::DELETE, 0x00,
                                                           advertising_filter_parameter);
  if (enable) {
    /* Add an allow-all filter on index 0 */
    advertising_filter_parameter.delivery_mode = bluetooth::hci::DeliveryMode::IMMEDIATE;
    advertising_filter_parameter.feature_selection = kAllowAllFilter;
    advertising_filter_parameter.list_logic_type = kListLogicOr;
    advertising_filter_parameter.filter_logic_type = kFilterLogicOr;
    advertising_filter_parameter.rssi_high_thresh = kLowestRssiValue;
    bluetooth::shim::GetScanning()->ScanFilterParameterSetup(bluetooth::hci::ApcfAction::ADD, 0x00,
                                                             advertising_filter_parameter);
  }
}

void bluetooth::shim::set_target_announcements_filter(bool enable) {
  uint8_t filter_index = 0x03;

  log::debug("enable {}", enable);

  bluetooth::hci::AdvertisingFilterParameter advertising_filter_parameter = {};
  bluetooth::shim::GetScanning()->ScanFilterParameterSetup(
          bluetooth::hci::ApcfAction::DELETE, filter_index, advertising_filter_parameter);

  if (!enable) {
    return;
  }

  advertising_filter_parameter.delivery_mode = bluetooth::hci::DeliveryMode::IMMEDIATE;
  advertising_filter_parameter.feature_selection = kAllowServiceDataFilter;
  advertising_filter_parameter.list_logic_type = kListLogicOr;
  advertising_filter_parameter.filter_logic_type = kFilterLogicAnd;
  advertising_filter_parameter.rssi_high_thresh = kLowestRssiValue;

  /* Add targeted announcements filter on index 4 */
  std::vector<bluetooth::hci::AdvertisingPacketContentFilterCommand> cap_bap_filter = {};

  bluetooth::hci::AdvertisingPacketContentFilterCommand cap_filter{};
  cap_filter.filter_type = bluetooth::hci::ApcfFilterType::SERVICE_DATA;
  cap_filter.data = {0x53, 0x18, 0x01};
  cap_filter.data_mask = {0xFF, 0xFF, 0xFF};
  cap_bap_filter.push_back(cap_filter);

  bluetooth::hci::AdvertisingPacketContentFilterCommand bap_filter{};
  bap_filter.filter_type = bluetooth::hci::ApcfFilterType::SERVICE_DATA;
  bap_filter.data = {0x4e, 0x18, 0x01};
  bap_filter.data_mask = {0xFF, 0xFF, 0xFF};

  cap_bap_filter.push_back(bap_filter);
  bluetooth::shim::GetScanning()->ScanFilterAdd(filter_index, cap_bap_filter);

  bluetooth::shim::GetScanning()->ScanFilterParameterSetup(
          bluetooth::hci::ApcfAction::ADD, filter_index, advertising_filter_parameter);
}
