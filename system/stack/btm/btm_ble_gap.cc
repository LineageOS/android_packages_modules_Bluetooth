/******************************************************************************
 *
 *  Copyright 2008-2014 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  This file contains functions for BLE GAP.
 *
 ******************************************************************************/

#define LOG_TAG "bt_btm_ble"

#include <android_bluetooth_sysprop.h>
#include <base/functional/bind.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/ble_scanner.h>

#include <cstdint>
#include <list>
#include <memory>
#include <vector>

#include "ble_appearance.h"
#include "bta/include/bta_api.h"
#include "btif/include/stack_manager_t.h"
#include "common/time_util.h"
#include "hci/controller.h"
#include "main/shim/acl_api.h"
#include "main/shim/entry.h"
#include "main/shim/le_scanning_manager.h"
#include "osi/include/properties.h"

#include "stack/btm/btm_ble_int.h"
#include "stack/btm/btm_ble_int_types.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/acl_api.h"
#include "stack/include/advertise_data_parser.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/bt_dev_class.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_ble_privacy.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/btm_status.h"
#include "stack/include/gap_api.h"
#include "stack/include/gattdefs.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/inq_hci_link_interface.h"
#include "stack/rnr/remote_name_request.h"

using namespace bluetooth;

#define BTM_EXT_BLE_RMT_NAME_TIMEOUT_MS (30 * 1000)
#define MIN_ADV_LENGTH 2
#define BTM_VSC_CHIP_CAPABILITY_RSP_LEN 9
#define BTM_VSC_CHIP_CAPABILITY_RSP_LEN_L_RELEASE BTM_VSC_CHIP_CAPABILITY_RSP_LEN
#define BTM_VSC_CHIP_CAPABILITY_RSP_LEN_M_RELEASE 15
#define BTM_VSC_CHIP_CAPABILITY_RSP_LEN_S_RELEASE 25

/* Sysprop paths for scan parameters */
static const char kPropertyInquiryScanInterval[] = "bluetooth.core.le.inquiry_scan_interval";
static const char kPropertyInquiryScanWindow[] = "bluetooth.core.le.inquiry_scan_window";

/* Error codes for toggling MSFT-based scanning */
const uint8_t MSFT_FILTER_ENABLE_SUCCESS = 0x00;
const uint8_t MSFT_FILTER_ENABLE_CMD_DISALLOWED = 0x0C;

#ifndef PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED
#define PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED \
  "bluetooth.core.gap.le.privacy.own_address_type.enabled"
#endif

static void btm_ble_start_scan();
static void btm_ble_stop_scan(bool update_scan_filter_policy);

static ::BleScannerInterface* scanner = bluetooth::shim::get_ble_scanner_instance();

using bluetooth::shim::GetController;

namespace {

constexpr char kBtmLogTag[] = "SCAN";

enum : uint8_t {
  BLE_EVT_CONNECTABLE_MASK = 1 << 0,
  BLE_EVT_SCANNABLE_MASK = 1 << 1,
  BLE_EVT_DIRECTED_MASK = 1 << 2,
  BLE_EVT_SCAN_RESPONSE_MASK = 1 << 3,
  BLE_EVT_LEGACY_MASK = 1 << 4,
};

class AdvertisingCache {
public:
  /* Set the data to |data| for device |addr_type, addr| */
  const std::vector<uint8_t>& Set(uint8_t addr_type, const RawAddress& addr,
                                  std::vector<uint8_t> data) {
    auto it = Find(addr_type, addr);
    if (it != items.end()) {
      it->data = std::move(data);
      return it->data;
    }

    if (items.size() > cache_max) {
      items.pop_back();
    }

    items.emplace_front(addr_type, addr, std::move(data));
    return items.front().data;
  }

  bool Exist(uint8_t addr_type, const RawAddress& addr) {
    auto it = Find(addr_type, addr);
    if (it != items.end()) {
      return true;
    }
    return false;
  }

  /* Append |data| for device |addr_type, addr| */
  const std::vector<uint8_t>& Append(uint8_t addr_type, const RawAddress& addr,
                                     std::vector<uint8_t> data) {
    auto it = Find(addr_type, addr);
    if (it != items.end()) {
      it->data.insert(it->data.end(), data.begin(), data.end());
      return it->data;
    }

    if (items.size() > cache_max) {
      items.pop_back();
    }

    items.emplace_front(addr_type, addr, std::move(data));
    return items.front().data;
  }

  /* Clear data for device |addr_type, addr| */
  void Clear(uint8_t addr_type, const RawAddress& addr) {
    auto it = Find(addr_type, addr);
    if (it != items.end()) {
      items.erase(it);
    }
  }

  void ClearAll() { items.clear(); }

private:
  struct Item {
    uint8_t addr_type;
    RawAddress addr;
    std::vector<uint8_t> data;

    Item(uint8_t addr_type, const RawAddress& addr, std::vector<uint8_t> data)
        : addr_type(addr_type), addr(addr), data(data) {}
  };

  std::list<Item>::iterator Find(uint8_t addr_type, const RawAddress& addr) {
    for (auto it = items.begin(); it != items.end(); it++) {
      if (it->addr_type == addr_type && it->addr == addr) {
        return it;
      }
    }
    return items.end();
  }

  /* we keep maximum 7 devices in the cache */
  const size_t cache_max = 7;
  std::list<Item> items;
};

/* Devices in this cache are waiting for either scan response, or chained packets
 * on secondary channel */
AdvertisingCache cache;

}  // namespace

/**********PAST & PS *******************/
using StartSyncCb = base::RepeatingCallback<void(
        uint8_t /*status*/, uint16_t /*sync_handle*/, uint8_t /*advertising_sid*/,
        uint8_t /*address_type*/, RawAddress /*address*/, uint8_t /*phy*/, uint16_t /*interval*/)>;
using SyncReportCb =
        base::RepeatingCallback<void(uint16_t /*sync_handle*/, int8_t /*tx_power*/, int8_t /*rssi*/,
                                     uint8_t /*status*/, std::vector<uint8_t> /*data*/)>;
using SyncLostCb = base::RepeatingCallback<void(uint16_t /*sync_handle*/)>;
using SyncTransferCb = base::RepeatingCallback<void(uint8_t /*status*/, RawAddress)>;
#define MAX_SYNC_TRANSACTION 16
#define SYNC_TIMEOUT (30 * 1000)
#define ADV_SYNC_ESTB_EVT_LEN 16
#define SYNC_LOST_EVT_LEN 3
typedef enum {
  PERIODIC_SYNC_IDLE = 0,
  PERIODIC_SYNC_PENDING,
  PERIODIC_SYNC_ESTABLISHED,
  PERIODIC_SYNC_LOST,
} tBTM_BLE_PERIODIC_SYNC_STATE;

static struct alarm_t* sync_timeout_alarm;
typedef struct {
  uint8_t sid;
  RawAddress remote_bda;
  tBTM_BLE_PERIODIC_SYNC_STATE sync_state;
  uint16_t sync_handle;
  bool in_use;
  StartSyncCb sync_start_cb;
  SyncReportCb sync_report_cb;
  SyncLostCb sync_lost_cb;
  BigInfoReportCb biginfo_report_cb;
} tBTM_BLE_PERIODIC_SYNC;

typedef struct {
  bool in_use;
  int conn_handle;
  RawAddress addr;
  SyncTransferCb cb;
} tBTM_BLE_PERIODIC_SYNC_TRANSFER;

typedef struct {
  bool busy;
  uint8_t sid;
  RawAddress address;
  uint16_t skip;
  uint16_t timeout;
} sync_node_t;
typedef struct {
  uint8_t sid;
  RawAddress address;
} remove_sync_node_t;
typedef enum {
  BTM_QUEUE_SYNC_REQ_EVT,
  BTM_QUEUE_SYNC_ADVANCE_EVT,
  BTM_QUEUE_SYNC_CLEANUP_EVT
} btif_queue_event_t;

typedef struct {
  tBTM_BLE_PERIODIC_SYNC p_sync[MAX_SYNC_TRANSACTION];
  tBTM_BLE_PERIODIC_SYNC_TRANSFER sync_transfer[MAX_SYNC_TRANSACTION];
} tBTM_BLE_PA_SYNC_TX_CB;
static tBTM_BLE_PA_SYNC_TX_CB btm_ble_pa_sync_cb;

/*****************************/
/*******************************************************************************
 *  Local functions
 ******************************************************************************/
static void btm_ble_stop_observe(void);
static void btm_ble_inquiry_timer_timeout(void* data);
static void btm_ble_observer_timer_timeout(void* data);
static DEV_CLASS btm_ble_appearance_to_cod(uint16_t appearance);
static void btm_ble_msft_adv_mon_enable(bool enable, bool restart_scan);
static void btm_update_scanner_filter_policy(uint8_t policy);
static bool use_msft_filtering();

enum : uint8_t {
  BTM_BLE_NOT_SCANNING = 0x00,
  BTM_BLE_INQ_RESULT = 0x01,
  BTM_BLE_OBS_RESULT = 0x02,
};

static bool ble_evt_type_is_connectable(uint16_t evt_type) {
  return evt_type & BLE_EVT_CONNECTABLE_MASK;
}

static bool ble_evt_type_is_scannable(uint16_t evt_type) {
  return evt_type & BLE_EVT_SCANNABLE_MASK;
}

static bool ble_evt_type_is_scan_resp(uint16_t evt_type) {
  return evt_type & BLE_EVT_SCAN_RESPONSE_MASK;
}

static bool ble_evt_type_is_legacy(uint16_t evt_type) {
  return evt_type & BLE_EVT_LEGACY_MASK;
}

static uint8_t ble_evt_type_data_status(uint16_t evt_type) { return (evt_type >> 5) & 3; }

void BTM_BleOpportunisticObserve(bool enable, tBTM_INQ_RESULTS_CB* p_results_cb) {
  if (enable) {
    btm_cb.ble_ctr_cb.p_opportunistic_obs_results_cb = p_results_cb;
  } else {
    btm_cb.ble_ctr_cb.p_opportunistic_obs_results_cb = NULL;
  }
}

void BTM_BleTargetAnnouncementObserve(bool enable, tBTM_INQ_RESULTS_CB* p_results_cb) {
  if (enable) {
    btm_cb.ble_ctr_cb.p_target_announcement_obs_results_cb = p_results_cb;
  } else {
    btm_cb.ble_ctr_cb.p_target_announcement_obs_results_cb = NULL;
  }
}

static std::pair<uint16_t /* interval */, uint16_t /* window */> get_low_latency_scan_params() {
  uint16_t scan_interval =
          osi_property_get_int32(kPropertyInquiryScanInterval, BTM_BLE_LOW_LATENCY_SCAN_INT);
  uint16_t scan_window =
          osi_property_get_int32(kPropertyInquiryScanWindow, BTM_BLE_LOW_LATENCY_SCAN_WIN);

  return std::make_pair(scan_interval, scan_window);
}

/*******************************************************************************
 *
 * Function         BTM_BleObserve
 *
 * Description      This procedure keep the device listening for advertising
 *                  events from a broadcast device.
 *
 * Parameters       start: start or stop observe.
 *                  duration: how long the scan should last, in seconds. 0 means
 *                  scan without timeout. Starting the scan second time without
 *                  timeout will disable the timer.
 *
 * Returns          void
 *
 ******************************************************************************/
tBTM_STATUS BTM_BleObserve(bool start, uint8_t duration, tBTM_INQ_RESULTS_CB* p_results_cb,
                           tBTM_INQUIRY_CMPL_CB* p_cmpl_cb) {
  tBTM_STATUS status = tBTM_STATUS::BTM_WRONG_MODE;
  uint8_t scan_phy = btm_cb.ble_ctr_cb.inq_var.scan_phy | BTM_BLE_DEFAULT_PHYS;

  // use low latency scanning
  uint16_t ll_scan_interval, ll_scan_window;
  std::tie(ll_scan_interval, ll_scan_window) = get_low_latency_scan_params();

  log::verbose("scan_type:{}, {}, {}", btm_cb.ble_ctr_cb.inq_var.scan_type, ll_scan_interval,
               ll_scan_window);

  if (!bluetooth::shim::GetController()->SupportsBle()) {
    return tBTM_STATUS::BTM_ILLEGAL_VALUE;
  }

  if (start) {
    /* shared inquiry database, do not allow observe if any inquiry is active.
     * except we are doing CSIS active scanning
     */
    if (btm_cb.ble_ctr_cb.is_ble_observe_active()) {
      if (duration == 0) {
        if (alarm_is_scheduled(btm_cb.ble_ctr_cb.observer_timer)) {
          alarm_cancel(btm_cb.ble_ctr_cb.observer_timer);
        } else {
          log::error("Scan with no duration started twice!");
        }
      }
      /*
       * we stop current observation request for below scenarios
       * 1. current ongoing scanning on 1m phy is low latency
       */
      bool is_ongoing_low_latency =
              btm_cb.ble_ctr_cb.inq_var.is_1m_phy_configured() &&
              btm_cb.ble_ctr_cb.inq_var.scan_interval_1m == ll_scan_interval &&
              btm_cb.ble_ctr_cb.inq_var.scan_window_1m == ll_scan_window;
      if (is_ongoing_low_latency) {
        log::warn("Observer was already active, is_low_latency: {}", is_ongoing_low_latency);
        return tBTM_STATUS::BTM_CMD_STARTED;
      }
      // stop any scan without low latency config
      btm_ble_stop_observe();
    }

    btm_cb.ble_ctr_cb.p_obs_results_cb = p_results_cb;
    btm_cb.ble_ctr_cb.p_obs_cmpl_cb = p_cmpl_cb;
    status = tBTM_STATUS::BTM_CMD_STARTED;

    /* scan is not started */
    if (!btm_cb.ble_ctr_cb.is_ble_scan_active()) {
      /* allow config of scan type */
      cache.ClearAll();
      btm_cb.ble_ctr_cb.inq_var.scan_type =
              (btm_cb.ble_ctr_cb.inq_var.scan_type == BTM_BLE_SCAN_MODE_NONE)
                      ? BTM_BLE_SCAN_MODE_ACTI
                      : btm_cb.ble_ctr_cb.inq_var.scan_type;
      btm_send_hci_set_scan_params(
              btm_cb.ble_ctr_cb.inq_var.scan_type, (uint16_t)ll_scan_interval,
              (uint8_t)ll_scan_window, btm_cb.ble_ctr_cb.inq_var.scan_interval_coded,
              btm_cb.ble_ctr_cb.inq_var.scan_window_coded, (uint16_t)scan_phy,
              btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, BTM_BLE_DEFAULT_SFP);
      if (use_msft_filtering()) {
        btm_ble_msft_adv_mon_enable(/*enable=*/false, /*restart_scan=*/true);
      } else {
        btm_ble_start_scan();
      }
    }

    btm_cb.neighbor.le_observe = {
            .start_time_ms = timestamper_in_milliseconds.GetTimestamp(),
            .results = 0,
    };

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le observe started",
                   "low latency scanning enabled");

    if (status == tBTM_STATUS::BTM_CMD_STARTED) {
      btm_cb.ble_ctr_cb.set_ble_observe_active();
      if (duration != 0) {
        /* start observer timer */
        uint64_t duration_ms = duration * 1000;
        alarm_set_on_mloop(btm_cb.ble_ctr_cb.observer_timer, duration_ms,
                           btm_ble_observer_timer_timeout, NULL);
      }
    }
  } else if (btm_cb.ble_ctr_cb.is_ble_observe_active()) {
    const uint64_t duration_timestamp =
            timestamper_in_milliseconds.GetTimestamp() - btm_cb.neighbor.le_observe.start_time_ms;
    BTM_LogHistory(
            kBtmLogTag, RawAddress::kEmpty, "Le observe stopped",
            std::format("duration_s:{:6.3f} results:{:<3}", (double)duration_timestamp / 1000.0,
                        btm_cb.neighbor.le_observe.results));
    status = tBTM_STATUS::BTM_CMD_STARTED;
    btm_ble_stop_observe();
  } else {
    log::error("Observe not active");
  }

  return status;
}

/*******************************************************************************
 *
 * Function         BTM_BleGetVendorCapabilities
 *
 * Description      This function reads local LE features
 *
 * Parameters       p_cmn_vsc_cb : Locala LE capability structure
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleGetVendorCapabilities(tBTM_BLE_VSC_CB* p_cmn_vsc_cb) {
  if (NULL != p_cmn_vsc_cb) {
    *p_cmn_vsc_cb = btm_cb.cmn_ble_vsc_cb;
  }
}

void BTM_BleGetDynamicAudioBuffer(tBTM_BT_DYNAMIC_AUDIO_BUFFER_CB p_dynamic_audio_buffer_cb[]) {
  log::verbose("BTM_BleGetDynamicAudioBuffer");

  if (NULL != p_dynamic_audio_buffer_cb) {
    for (int i = 0; i < 32; i++) {
      p_dynamic_audio_buffer_cb[i] = btm_cb.dynamic_audio_buffer_cb[i];
    }
  }
}

/******************************************************************************
 *
 * Function         BTM_BleReadControllerFeatures
 *
 * Description      Reads BLE specific controller features
 *
 * Parameters:      tBTM_BLE_CTRL_FEATURES_CBACK : Callback to notify when
 *                  features are read
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_BleReadControllerFeatures(tBTM_BLE_CTRL_FEATURES_CBACK* p_vsc_cback) {
  if (!android::sysprop::bluetooth::Ble::vnd_included()) {
    return;
  }

  if (btm_cb.cmn_ble_vsc_cb.values_read) {
    return;
  }

  log::verbose("BTM_BleReadControllerFeatures");

  btm_cb.cmn_ble_vsc_cb.values_read = true;
  bluetooth::hci::Controller::VendorCapabilities vendor_capabilities =
          GetController()->GetVendorCapabilities();

  btm_cb.cmn_ble_vsc_cb.adv_inst_max = vendor_capabilities.max_advt_instances_;
  btm_cb.cmn_ble_vsc_cb.rpa_offloading =
          vendor_capabilities.offloaded_resolution_of_private_address_;
  btm_cb.cmn_ble_vsc_cb.tot_scan_results_strg = vendor_capabilities.total_scan_results_storage_;
  btm_cb.cmn_ble_vsc_cb.max_irk_list_sz = vendor_capabilities.max_irk_list_sz_;
  btm_cb.cmn_ble_vsc_cb.filter_support = vendor_capabilities.filtering_support_;
  btm_cb.cmn_ble_vsc_cb.max_filter = vendor_capabilities.max_filter_;
  btm_cb.cmn_ble_vsc_cb.energy_support = vendor_capabilities.activity_energy_info_support_;

  btm_cb.cmn_ble_vsc_cb.version_supported = vendor_capabilities.version_supported_;
  btm_cb.cmn_ble_vsc_cb.total_trackable_advertisers =
          vendor_capabilities.total_num_of_advt_tracked_;
  btm_cb.cmn_ble_vsc_cb.extended_scan_support = vendor_capabilities.extended_scan_support_;
  btm_cb.cmn_ble_vsc_cb.debug_logging_supported = vendor_capabilities.debug_logging_supported_;

  btm_cb.cmn_ble_vsc_cb.le_address_generation_offloading_support =
          vendor_capabilities.le_address_generation_offloading_support_;
  btm_cb.cmn_ble_vsc_cb.a2dp_source_offload_capability_mask =
          vendor_capabilities.a2dp_source_offload_capability_mask_;
  btm_cb.cmn_ble_vsc_cb.quality_report_support =
          vendor_capabilities.bluetooth_quality_report_support_;
  btm_cb.cmn_ble_vsc_cb.dynamic_audio_buffer_support =
          vendor_capabilities.dynamic_audio_buffer_support_;
  btm_cb.cmn_ble_vsc_cb.a2dp_offload_v2_support = vendor_capabilities.a2dp_offload_v2_support_;
  btm_cb.cmn_ble_vsc_cb.big_set_channel_map_classification_support =
          vendor_capabilities.big_set_channel_map_classification_support_;

  if (vendor_capabilities.dynamic_audio_buffer_support_) {
    std::array<bluetooth::hci::DynamicAudioBufferCodecCapability, BTM_CODEC_TYPE_MAX_RECORDS>
            capabilities = GetController()->GetDabCodecCapabilities();

    for (size_t i = 0; i < capabilities.size(); i++) {
      btm_cb.dynamic_audio_buffer_cb[i].default_buffer_time = capabilities[i].default_time_ms_;
      btm_cb.dynamic_audio_buffer_cb[i].maximum_buffer_time = capabilities[i].maximum_time_ms_;
      btm_cb.dynamic_audio_buffer_cb[i].minimum_buffer_time = capabilities[i].minimum_time_ms_;
    }
  }

  if (btm_cb.cmn_ble_vsc_cb.filter_support == 1 &&
      GetController()->GetLocalVersionInformation().manufacturer_name_ == LMP_COMPID_QTI) {
    // QTI controller, TDS data filter are supported by default.
    btm_cb.cmn_ble_vsc_cb.adv_filter_extended_features_mask = 0x01;
  } else {
    btm_cb.cmn_ble_vsc_cb.adv_filter_extended_features_mask = 0x00;
  }

  log::verbose("irk={}, ADV ins:{}, rpa={}, ener={}, ext_scan={}",
               btm_cb.cmn_ble_vsc_cb.max_irk_list_sz, btm_cb.cmn_ble_vsc_cb.adv_inst_max,
               btm_cb.cmn_ble_vsc_cb.rpa_offloading, btm_cb.cmn_ble_vsc_cb.energy_support,
               btm_cb.cmn_ble_vsc_cb.extended_scan_support);

  if (btm_cb.cmn_ble_vsc_cb.max_filter > 0) {
    btm_ble_adv_filter_init();
  }

  /* VS capability included and non-4.2 device */
  if (GetController()->SupportsBle() && GetController()->SupportsBlePrivacy() &&
      btm_cb.cmn_ble_vsc_cb.max_irk_list_sz > 0 && GetController()->GetLeResolvingListSize() == 0) {
    btm_ble_resolving_list_init(btm_cb.cmn_ble_vsc_cb.max_irk_list_sz);
  }

  if (p_vsc_cback != NULL) {
    p_vsc_cback(tHCI_STATUS::HCI_SUCCESS);
  }
}

/*******************************************************************************
 *
 * Function         BTM_BleConfigPrivacy
 *
 * Description      This function is called to enable or disable the privacy in
 *                   LE channel of the local device.
 *
 * Parameters       privacy_mode:  privacy mode on or off.
 *
 * Returns          bool    privacy mode set success; otherwise failed.
 *
 ******************************************************************************/
bool BTM_BleConfigPrivacy(bool privacy_mode) {
  log::warn("{}", (int)privacy_mode);

  /* if LE is not supported, return error */
  if (!bluetooth::shim::GetController()->SupportsBle()) {
    return false;
  }

  tGAP_BLE_ATTR_VALUE gap_ble_attr_value;
  gap_ble_attr_value.addr_resolution = 0;
  if (!privacy_mode) /* if privacy disabled, always use public address */
  {
    btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type = BLE_ADDR_PUBLIC;
    /* Allow host use random address when privacy
     * mode is not enabled by setting the sysprop true */
    if (osi_property_get_bool(PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED, privacy_mode)) {
      btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type = BLE_ADDR_RANDOM;
    }
    btm_cb.ble_ctr_cb.privacy_mode = BTM_PRIVACY_NONE;
  } else /* privacy is turned on*/
  {
    /* always set host random address, used when privacy 1.1 or priavcy 1.2 is
     * disabled */
    btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type = BLE_ADDR_RANDOM;
    /* Allow host use public address when privacy
     * mode is enabled by setting the sysprop false */
    if (!osi_property_get_bool(PROPERTY_BLE_PRIVACY_OWN_ADDRESS_ENABLED, privacy_mode)) {
      btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type = BLE_ADDR_PUBLIC;
    }

    /* 4.2 controller only allow privacy 1.2 or mixed mode, resolvable private
     * address in controller */
    if (bluetooth::shim::GetController()->SupportsBlePrivacy()) {
      gap_ble_attr_value.addr_resolution = 1;
      btm_cb.ble_ctr_cb.privacy_mode = BTM_PRIVACY_1_2;
    } else { /* 4.1/4.0 controller */
      btm_cb.ble_ctr_cb.privacy_mode = BTM_PRIVACY_1_1;
    }
  }
  log::verbose("privacy_mode: {} own_addr_type: {}", btm_cb.ble_ctr_cb.privacy_mode,
               btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type);

  GAP_BleAttrDBUpdate(GATT_UUID_GAP_CENTRAL_ADDR_RESOL, &gap_ble_attr_value);

  bluetooth::shim::ACL_ConfigureLePrivacy(privacy_mode);
  return true;
}

/*******************************************************************************
 *
 * Function         BTM_BleLocalPrivacyEnabled
 *
 * Description        Checks if local device supports private address
 *
 * Returns          Return true if local privacy is enabled else false
 *
 ******************************************************************************/
bool BTM_BleLocalPrivacyEnabled(void) { return btm_cb.ble_ctr_cb.privacy_mode != BTM_PRIVACY_NONE; }

static void btm_send_hci_scan_enable(uint8_t enable, uint8_t filter_duplicates) {
  if (bluetooth::shim::GetController()->SupportsBleExtendedAdvertising()) {
    btsnd_hcic_ble_set_extended_scan_enable(enable, filter_duplicates, 0x0000, 0x0000);
  } else {
    btsnd_hcic_ble_set_scan_enable(enable, filter_duplicates);
  }
}

void btm_send_hci_set_scan_params(uint8_t scan_type, uint16_t scan_int_1m, uint16_t scan_win_1m,
                                  uint16_t scan_int_coded, uint16_t scan_win_coded,
                                  uint8_t scan_phy, tBLE_ADDR_TYPE addr_type_own,
                                  uint8_t scan_filter_policy) {
  if (bluetooth::shim::GetController()->SupportsBleExtendedAdvertising()) {
    std::vector<scanning_phy_cfg> phy_cfgs;
    if ((scan_phy & BTM_BLE_1M_PHY_MASK) != 0) {
      scanning_phy_cfg phy_cfg;
      phy_cfg.scan_type = scan_type;
      phy_cfg.scan_int = scan_int_1m;
      phy_cfg.scan_win = scan_win_1m;
      phy_cfgs.push_back(phy_cfg);
    }
    if ((scan_phy & BTM_BLE_CODED_PHY_MASK) != 0) {
      scanning_phy_cfg phy_cfg;
      phy_cfg.scan_type = scan_type;
      phy_cfg.scan_int = scan_int_coded;
      phy_cfg.scan_win = scan_win_coded;
      phy_cfgs.push_back(phy_cfg);
    }

    btsnd_hcic_ble_set_extended_scan_params(addr_type_own, scan_filter_policy, scan_phy,
                                            phy_cfgs.data());
  } else {
    btsnd_hcic_ble_set_scan_params(scan_type, scan_int_1m, scan_win_1m, addr_type_own,
                                   scan_filter_policy);
  }
}

// TODO(b/459944050): Delete msft related functions when scan multiplexing feature is done.
/* Whether or not to use MSFT-based scan filtering */
static bool use_msft_filtering() {
  // We prefer to use APCF-based filtering over MSFT if it's available, so only use MSFT
  // filtering if APCF is not supported.
  return !BTM_BleIsFilteringSupported() && scanner->IsMsftSupported();
}

// TODO(b/459944050): Delete msft related functions when scan multiplexing feature is done.
/* MSFT advertisement enable callback */
static void msft_adv_mon_enable_cb(bool restart_scan, bool enable, uint8_t status) {
  if (status == MSFT_FILTER_ENABLE_CMD_DISALLOWED) {
    log::warn("MSFT: Advertisement monitor is already {}", enable ? "enabled" : "disabled");
  } else if (status != MSFT_FILTER_ENABLE_SUCCESS) {
    log::error("MSFT: {} advertisement monitor failed with status: {}",
               enable ? "Enabling" : "Disabling", status);
    return;
  } else {
    log::debug("MSFT: Advertisement monitor {}", enable ? "enabled" : "disabled");
  }

  // To retain the correct command sequencing, only re-enable LE scanning now
  // that we know MSFT filtered scanning has been re-enabled.
  if (!restart_scan) {
    return;
  }
  log::debug("MSFT: Restarting LE scan");
  btm_ble_start_scan();
}

/* Update MSFT-based scan to align with active scan requirements */
static void btm_ble_msft_adv_mon_enable(bool enable, bool restart_scan) {
  if (!use_msft_filtering()) {
    return;
  }

  log::debug("MSFT: {} advertisement monitor", enable ? "Enabling" : "Disabling");
  scanner->MsftAdvMonitorEnable(enable, base::Bind(msft_adv_mon_enable_cb, restart_scan));
}

/* Scan filter param config event */
static void btm_ble_scan_filt_param_cfg_evt(uint8_t /* avbl_space */,
                                            tBTM_BLE_SCAN_COND_OP /* action_type */,
                                            tBTM_STATUS btm_status) {
  if (btm_status == tBTM_STATUS::BTM_SUCCESS) {
    log::verbose("");
    return;
  }
  log::warn("{}", btm_status_text(btm_status));
}

/*******************************************************************************
 *
 * Function         btm_ble_start_inquiry
 *
 * Description      This function is called to start BLE inquiry procedure.
 *                  If the duration is zero, the periodic inquiry mode is
 *                  cancelled.
 *
 * Parameters:      duration - Duration of inquiry as a multiplier for 1.28
 *                             seconds.
 *
 * Returns          tBTM_STATUS::BTM_CMD_STARTED if successfully started
 *                  tBTM_STATUS::BTM_BUSY - if an inquiry is already active
 *
 ******************************************************************************/
tBTM_STATUS btm_ble_start_inquiry(uint8_t duration) {
  log::verbose("btm_ble_start_inquiry: inq_active = 0x{:02x}", btm_cb.btm_inq_vars.inq_active);

  /* if selective connection is active, or inquiry is already active, reject it
   */
  if (btm_cb.ble_ctr_cb.is_ble_inquiry_active()) {
    log::error("LE Inquiry is active, can not start inquiry");
    return tBTM_STATUS::BTM_BUSY;
  }

  /* Cleanup anything remaining on index 0 */
  BTM_BleAdvFilterParamSetup(BTM_BLE_SCAN_COND_DELETE, static_cast<tBTM_BLE_PF_FILT_INDEX>(0),
                             nullptr, base::Bind(btm_ble_scan_filt_param_cfg_evt));

  auto adv_filt_param = std::make_unique<btgatt_filt_param_setup_t>();
  /* Add an allow-all filter on index 0*/
  adv_filt_param->dely_mode = IMMEDIATE_DELY_MODE;
  adv_filt_param->feat_seln = ALLOW_ALL_FILTER;
  adv_filt_param->filt_logic_type = BTA_DM_BLE_PF_FILT_LOGIC_OR;
  adv_filt_param->list_logic_type = BTA_DM_BLE_PF_LIST_LOGIC_OR;
  adv_filt_param->rssi_low_thres = LOWEST_RSSI_VALUE;
  adv_filt_param->rssi_high_thres = LOWEST_RSSI_VALUE;
  BTM_BleAdvFilterParamSetup(BTM_BLE_SCAN_COND_ADD, static_cast<tBTM_BLE_PF_FILT_INDEX>(0),
                             std::move(adv_filt_param),
                             base::Bind(btm_ble_scan_filt_param_cfg_evt));

  uint16_t scan_interval, scan_window;

  std::tie(scan_interval, scan_window) = get_low_latency_scan_params();
  uint8_t scan_phy = BTM_BLE_DEFAULT_PHYS;

  if (!btm_cb.ble_ctr_cb.is_ble_scan_active()) {
    cache.ClearAll();
    btm_send_hci_set_scan_params(BTM_BLE_SCAN_MODE_ACTI, scan_interval, scan_window, 0, 0, scan_phy,
                                 btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, SP_ADV_ALL);
    btm_cb.ble_ctr_cb.inq_var.scan_type = BTM_BLE_SCAN_MODE_ACTI;

    if (use_msft_filtering()) {
      btm_ble_msft_adv_mon_enable(/*enable=*/false, /*restart_scan=*/true);
    } else {
      btm_ble_start_scan();
    }
  } else if (!btm_cb.ble_ctr_cb.inq_var.is_1m_phy_configured() ||
             (btm_cb.ble_ctr_cb.inq_var.scan_interval_1m != scan_interval) ||
             (btm_cb.ble_ctr_cb.inq_var.scan_window_1m != scan_window)) {
    log::verbose("restart LE scan with low latency scan params");
    btm_ble_stop_scan(/*update_scan_filter_policy=*/false);
    btm_send_hci_set_scan_params(BTM_BLE_SCAN_MODE_ACTI, scan_interval, scan_window,
                                 btm_cb.ble_ctr_cb.inq_var.scan_interval_coded,
                                 btm_cb.ble_ctr_cb.inq_var.scan_window_coded,
                                 btm_cb.ble_ctr_cb.inq_var.scan_phy | scan_phy,
                                 btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, SP_ADV_ALL);
    if (use_msft_filtering()) {
      btm_ble_msft_adv_mon_enable(/*enable=*/false, /*restart_scan=*/true);
    } else {
      btm_ble_start_scan();
    }
  }

  btm_cb.btm_inq_vars.inq_active |= BTM_BLE_GENERAL_INQUIRY;
  btm_cb.ble_ctr_cb.set_ble_inquiry_active();

  log::verbose("btm_ble_start_inquiry inq_active = 0x{:02x}", btm_cb.btm_inq_vars.inq_active);

  if (duration != 0) {
    /* start inquiry timer */
    uint64_t duration_ms = duration * 1280;
    alarm_set_on_mloop(btm_cb.ble_ctr_cb.inq_var.inquiry_timer, duration_ms,
                       btm_ble_inquiry_timer_timeout, NULL);
  }

  btm_cb.neighbor.le_inquiry = {
          .start_time_ms = timestamper_in_milliseconds.GetTimestamp(),
          .results = 0,
  };
  BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le inquiry started");

  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_ble_read_remote_name_cmpl
 *
 * Description      This function is called when BLE remote name is received.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_read_remote_name_cmpl(bool status, const RawAddress& bda, uint16_t length,
                                          char* p_name) {
  if (!stack_is_running()) {
    log::warn("stack is not running");
    return;
  }

  tHCI_STATUS hci_status = HCI_SUCCESS;
  BD_NAME bd_name;
  bd_name_from_char_pointer(bd_name, p_name);

  if ((!status) || (length == 0)) {
    hci_status = HCI_ERR_HOST_TIMEOUT;
  }

  get_stack_rnr_interface().btm_process_remote_name(&bda, bd_name, length + 1, hci_status);
  btm_sec_rmt_name_request_complete(&bda, (const uint8_t*)p_name, hci_status);
}

/*******************************************************************************
 *
 * Function         btm_ble_read_remote_name
 *
 * Description      This function read remote LE device name using GATT read
 *                  procedure.
 *
 * Parameters:       None.
 *
 * Returns          void
 *
 ******************************************************************************/
tBTM_STATUS btm_ble_read_remote_name(const RawAddress& remote_bda, tBTM_NAME_CMPL_CB* p_cb) {
  if (!bluetooth::shim::GetController()->SupportsBle()) {
    return tBTM_STATUS::BTM_ERR_PROCESSING;
  }

  tINQ_DB_ENT* p_i = btm_inq_db_find(remote_bda);
  if (p_i && !ble_evt_type_is_connectable(p_i->inq_info.results.ble_evt_type)) {
    if (BTM_IsAclConnectionUp(remote_bda, BT_TRANSPORT_LE)) {
      log::verbose("name request to non-connectable device, but already connected");
    } else {
      log::verbose("name request to non-connectable device failed.");
      return tBTM_STATUS::BTM_ERR_PROCESSING;
    }
  }

  /* read remote device name using GATT procedure */
  if (btm_cb.rnr.remname_active) {
    log::warn("Unable to start GATT RNR procedure for peer:{} busy with peer:{}", remote_bda,
              btm_cb.rnr.remname_bda);
    return tBTM_STATUS::BTM_BUSY;
  }

  if (!GAP_BleReadPeerDevName(remote_bda, btm_ble_read_remote_name_cmpl)) {
    return tBTM_STATUS::BTM_BUSY;
  }

  btm_cb.rnr.p_remname_cmpl_cb = p_cb;
  btm_cb.rnr.remname_active = true;
  btm_cb.rnr.remname_bda = remote_bda;
  btm_cb.rnr.remname_dev_type = BT_DEVICE_TYPE_BLE;

  alarm_set_on_mloop(btm_cb.rnr.remote_name_timer, BTM_EXT_BLE_RMT_NAME_TIMEOUT_MS,
                     btm_inq_remote_name_timer_timeout, NULL);

  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_ble_read_remote_appearance_cmpl
 *
 * Description      This function is called when peer's appearance value is received.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_read_remote_appearance_cmpl(bool status, const RawAddress& bda, uint16_t length,
                                                char* data) {
  if (!status) {
    log::error("Failed to read appearance of {}", bda);
    return;
  }
  if (length != 2 || data == nullptr) {
    log::error("Invalid appearance value size {} for {}", length, bda);
    return;
  }

  uint16_t appearance = data[0] + (data[1] << 8);
  DEV_CLASS cod = btm_ble_appearance_to_cod(appearance);
  log::info("Appearance 0x{:04x}, Class of Device {} found for {}", appearance, dev_class_text(cod),
            bda);

  BtmDevice* p_device = btm_get_dev(bda);
  if (p_device != nullptr) {
    p_device->dev_class = cod;
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_read_remote_cod
 *
 * Description      Finds Class of Device by reading GATT appearance characteristic
 *
 * Parameters:      Device address
 *
 * Returns          void
 *
 ******************************************************************************/
tBTM_STATUS btm_ble_read_remote_cod(const RawAddress& remote_bda) {
  if (!bluetooth::shim::GetController()->SupportsBle()) {
    return tBTM_STATUS::BTM_ERR_PROCESSING;
  }

  if (!GAP_BleReadPeerAppearance(remote_bda, btm_ble_read_remote_appearance_cmpl)) {
    return tBTM_STATUS::BTM_BUSY;
  }

  log::verbose("Reading appearance characteristic {}", remote_bda);
  return tBTM_STATUS::BTM_CMD_STARTED;
}

/*******************************************************************************
 *
 * Function         btm_ble_cancel_remote_name
 *
 * Description      This function cancel read remote LE device name.
 *
 * Parameters:       None.
 *
 * Returns          void
 *
 ******************************************************************************/
bool btm_ble_cancel_remote_name(const RawAddress& remote_bda) {
  bool status;

  status = GAP_BleCancelReadPeerDevName(remote_bda);

  btm_cb.rnr.remname_active = false;
  btm_cb.rnr.remname_bda = RawAddress::kEmpty;
  btm_cb.rnr.remname_dev_type = BT_DEVICE_TYPE_UNKNOWN;
  alarm_cancel(btm_cb.rnr.remote_name_timer);

  return status;
}

/**
 * Check ADV flag to make sure device is discoverable and match the search
 * condition
 */
static uint8_t btm_ble_is_discoverable(const RawAddress& /* bda */,
                                       std::vector<uint8_t> const& adv_data) {
  uint8_t scan_state = BTM_BLE_NOT_SCANNING;

  /* Set observe bit to 1 when observe is active */
  if (btm_cb.ble_ctr_cb.is_ble_observe_active()) {
    scan_state |= BTM_BLE_OBS_RESULT;
  }

  if (!adv_data.empty()) {
    uint8_t flag = 0;
    uint8_t data_len;
    const uint8_t* p_flag =
            AdvertiseDataParser::GetFieldByType(adv_data, BTM_BLE_AD_TYPE_FLAG, &data_len);
    if (p_flag != NULL && data_len != 0) {
      flag = *p_flag;

      /* Set inquiry bit to 1 when inquiry is active, and GENERAL DISCOVERABLE or LIMITED
       * DISCOVERABLE is true */
      if ((btm_cb.btm_inq_vars.inq_active & BTM_BLE_GENERAL_INQUIRY) &&
          (flag & (BTM_BLE_LIMIT_DISC_FLAG | BTM_BLE_GEN_DISC_FLAG)) != 0) {
        scan_state |= BTM_BLE_INQ_RESULT;
      }
    }
  }
  return scan_state;
}

/**
 * Converts BLE appearance value to Class of Device
 * Note: To add mapping for a new BLE appearance value for a category, add the
 *  mapping under the appropriate APPEARANCE_TO_COD_XXXX macro.
 */
static DEV_CLASS btm_ble_appearance_to_cod(uint16_t appearance) {
  switch (appearance) {
    APPEARANCE_TO_COD(ADD_APPEARANCE_TO_COD_CASE);
    // No need of adding default case
  };

  return kDevClassEmpty;
}

DEV_CLASS btm_ble_get_appearance_as_cod(std::vector<uint8_t> const& data) {
  /* Check to see the BLE device has the Appearance UUID in the advertising
   * data. If it does then try to convert the appearance value to a class of
   * device value Fluoride can use. Otherwise fall back to trying to infer if
   * it is a HID device based on the service class.
   */
  uint8_t len;
  const uint8_t* p_uuid16 =
          AdvertiseDataParser::GetFieldByType(data, BTM_BLE_AD_TYPE_APPEARANCE, &len);
  if (p_uuid16 && len == 2) {
    return btm_ble_appearance_to_cod((uint16_t)p_uuid16[0] | (p_uuid16[1] << 8));
  }

  p_uuid16 = AdvertiseDataParser::GetFieldByType(data, BTM_BLE_AD_TYPE_16SRV_CMPL, &len);
  if (p_uuid16 == NULL) {
    return kDevClassUnclassified;
  }

  for (uint8_t i = 0; i + 2 <= len; i = i + 2) {
    /* if this BLE device supports HID over LE, set HID Major in class of
     * device */
    if ((p_uuid16[i] | (p_uuid16[i + 1] << 8)) == UUID_SERVCLASS_LE_HID) {
      DEV_CLASS dev_class;
      dev_class[0] = 0;
      dev_class[1] = BTM_COD_MAJOR_PERIPHERAL;
      dev_class[2] = 0;
      return dev_class;
    }
  }

  return kDevClassUnclassified;
}

/**
 * Update adv packet information into inquiry result.
 */
static void btm_ble_update_inq_result(tINQ_DB_ENT* p_i, uint8_t addr_type,
                                      const RawAddress& /* bda */, uint16_t evt_type,
                                      uint8_t primary_phy, uint8_t secondary_phy,
                                      uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                                      uint16_t periodic_adv_int, std::vector<uint8_t> const& data) {
  tBTM_INQ_RESULTS* p_cur = &p_i->inq_info.results;
  uint8_t len;

  /* Save the info */
  p_cur->inq_result_type |= BT_DEVICE_TYPE_BLE;
  p_cur->last_inq_result_transport = BT_TRANSPORT_LE;
  p_cur->ble_addr_type = static_cast<tBLE_ADDR_TYPE>(addr_type);
  p_cur->rssi = rssi;
  p_cur->ble_primary_phy = primary_phy;
  p_cur->ble_secondary_phy = secondary_phy;
  p_cur->ble_advertising_sid = advertising_sid;
  p_cur->ble_tx_power = tx_power;
  p_cur->ble_periodic_adv_int = periodic_adv_int;

  if (btm_cb.ble_ctr_cb.inq_var.scan_type == BTM_BLE_SCAN_MODE_ACTI &&
      ble_evt_type_is_scannable(evt_type) && !ble_evt_type_is_scan_resp(evt_type)) {
    p_i->scan_rsp = false;
  } else {
    p_i->scan_rsp = true;
  }

  if (p_i->inq_count != btm_cb.btm_inq_vars.inq_counter) {
    p_cur->device_type = BT_DEVICE_TYPE_BLE;
  } else {
    p_cur->device_type |= BT_DEVICE_TYPE_BLE;
  }

  if (evt_type != BTM_BLE_SCAN_RSP_EVT) {
    p_cur->ble_evt_type = evt_type;
  }

  p_i->inq_count = btm_cb.btm_inq_vars.inq_counter; /* Mark entry for current inquiry */

  bool has_advertising_flags = false;
  if (!data.empty()) {
    uint8_t local_flag = 0;
    const uint8_t* p_flag = AdvertiseDataParser::GetFieldByType(data, BTM_BLE_AD_TYPE_FLAG, &len);
    if (p_flag != NULL && len != 0) {
      has_advertising_flags = true;
      p_cur->flag = *p_flag;
      local_flag = *p_flag;
    }

    // CoD received from inquiry response should not be overwritten by the appearance value. So
    // update it only if it is not known.
    if (p_cur->dev_class == kDevClassUnclassified || p_cur->dev_class == kDevClassEmpty) {
      p_cur->dev_class = btm_ble_get_appearance_as_cod(data);
    }

    const uint8_t* p_rsi = AdvertiseDataParser::GetFieldByType(data, BTM_BLE_AD_TYPE_RSI, &len);
    if (p_rsi != nullptr && len == 6) {
      STREAM_TO_BDADDR(p_cur->ble_ad_rsi, p_rsi);
    }

    const uint8_t* p_service_data = data.data();
    uint8_t service_data_len = 0;

    while ((p_service_data = AdvertiseDataParser::GetFieldByType(
                    p_service_data + service_data_len,
                    data.size() - (p_service_data - data.data()) - service_data_len,
                    BTM_BLE_AD_TYPE_SERVICE_DATA_TYPE, &service_data_len))) {
      uint16_t uuid;
      const uint8_t* p_uuid = p_service_data;
      if (service_data_len < 2) {
        continue;
      }
      STREAM_TO_UINT16(uuid, p_uuid);

      if (uuid == 0x184E /* Audio Stream Control service */ ||
          uuid == 0x184F /* Broadcast Audio Scan service */ ||
          uuid == 0x1850 /* Published Audio Capabilities service */ ||
          uuid == 0x1853 /* Common Audio service */) {
        p_cur->ble_ad_is_le_audio_capable = true;
        break;
      }
    }
    // Non-connectable packets may omit flags entirely, in which case nothing
    // should be assumed about their values (CSSv10, 1.3.1). Thus, do not
    // interpret the device type unless this packet has the flags set or is
    // connectable.
    if (ble_evt_type_is_connectable(evt_type) && !has_advertising_flags) {
      // Assume that all-zero flags were received
      has_advertising_flags = true;
      local_flag = 0;
    }
    if (has_advertising_flags && (local_flag & BTM_BLE_BREDR_NOT_SPT) == 0) {
      if (com_android_bluetooth_flags_unify_device_type_verification_logic() ||
          p_cur->ble_addr_type != BLE_ADDR_RANDOM) {
        log::verbose("NOT_BR_EDR support bit not set, treat device as DUMO");
        p_cur->device_type |= BT_DEVICE_TYPE_DUMO;
      } else {
        log::verbose("Random address, treat device as LE only");
      }
    } else {
      log::verbose("NOT_BR/EDR support bit set, treat device as LE only");
    }
  }
}

void btm_ble_process_adv_addr(RawAddress& bda, tBLE_ADDR_TYPE* addr_type) {
  /* map address to security record */
  bool match = btm_identity_addr_to_random_pseudo(&bda, addr_type, false);

  log::verbose("bda={}", bda);
  /* always do RRA resolution on host */
  if (!match && BTM_BLE_IS_RESOLVE_BDA(bda)) {
    BtmDevice* match_dev = btm_ble_resolve_random_addr(bda);
    if (match_dev) {
      match_dev->ble.active_addr_type = BTM_BLE_ADDR_RRA;
      match_dev->ble.cur_rand_addr = bda;

      if (btm_ble_init_pseudo_addr(match_dev, bda)) {
        bda = match_dev->bd_addr;
      } else {
        // Assign the original address to be the current report address
        bda = match_dev->ble.pseudo_addr;
        *addr_type = match_dev->ble.AddressType();
      }
    }
  }
}

/**
 * This function is called after random address resolution is done, and proceed
 * to process adv packet.
 */
void btm_ble_process_adv_pkt_cont(uint16_t evt_type, tBLE_ADDR_TYPE addr_type,
                                  const RawAddress& bda, uint8_t primary_phy, uint8_t secondary_phy,
                                  uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                                  uint16_t periodic_adv_int, uint8_t data_len, const uint8_t* data,
                                  const RawAddress& original_bda) {
  bool update = true;

  std::vector<uint8_t> tmp;
  if (data_len != 0) {
    tmp.insert(tmp.begin(), data, data + data_len);
  }

  bool is_scannable = ble_evt_type_is_scannable(evt_type);
  bool is_scan_resp = ble_evt_type_is_scan_resp(evt_type);
  bool is_legacy = ble_evt_type_is_legacy(evt_type);

  // We might receive a legacy scan response without receiving a ADV_IND
  // or ADV_SCAN_IND before. Only parsing the scan response data which
  // has no ad flag, the device will be set to DUMO mode. The create bond
  // procedure will use the wrong device mode.
  // In such case no necessary to report scan response
  if (is_legacy && is_scan_resp && !cache.Exist(addr_type, bda)) {
    return;
  }

  bool is_start = is_legacy && is_scannable && !is_scan_resp;

  if (is_legacy) {
    AdvertiseDataParser::RemoveTrailingZeros(tmp);
  }

  // We might have send scan request to this device before, but didn't get the
  // response. In such case make sure data is put at start, not appended to
  // already existing data.
  std::vector<uint8_t> const& adv_data = is_start ? cache.Set(addr_type, bda, std::move(tmp))
                                                  : cache.Append(addr_type, bda, std::move(tmp));

  bool data_complete = (ble_evt_type_data_status(evt_type) != 0x01);

  if (!data_complete) {
    // If we didn't receive whole adv data yet, don't report the device.
    log::verbose("Data not complete yet, waiting for more {}", bda);
    return;
  }

  bool is_active_scan = btm_cb.ble_ctr_cb.inq_var.scan_type == BTM_BLE_SCAN_MODE_ACTI;
  if (is_active_scan && is_scannable && !is_scan_resp) {
    // If we didn't receive scan response yet, don't report the device.
    log::verbose("Waiting for scan response {}", bda);
    return;
  }

  if (!AdvertiseDataParser::IsValid(adv_data)) {
    log::verbose("Dropping bad advertisement packet: {}",
                 base::HexEncode(adv_data.data(), adv_data.size()));
    cache.Clear(addr_type, bda);
    return;
  }

  bool include_rsi = false;
  uint8_t len;
  if (AdvertiseDataParser::GetFieldByType(adv_data, BTM_BLE_AD_TYPE_RSI, &len)) {
    include_rsi = true;
  }

  tINQ_DB_ENT* p_i = btm_inq_db_find(bda);

  /* Check if this address has already been processed for this inquiry */
  if (btm_inq_find_bdaddr(bda)) {
    /* never been report as an LE device */
    if (p_i && (!(p_i->inq_info.results.device_type & BT_DEVICE_TYPE_BLE) ||
                /* scan response to be updated */
                (!p_i->scan_rsp) || (!p_i->inq_info.results.include_rsi && include_rsi))) {
      update = true;
    } else if (btm_cb.ble_ctr_cb.is_ble_observe_active()) {
      update = false;
    } else {
      /* if yes, skip it */
      cache.Clear(addr_type, bda);
      return; /* assumption: one result per event */
    }
  }
  /* If existing entry, use that, else get  a new one (possibly reusing the
   * oldest) */
  if (p_i == NULL) {
    p_i = btm_inq_db_new(bda, true);
    if (p_i != NULL) {
      btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
      p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
    } else {
      return;
    }
  } else if (p_i->inq_count !=
             btm_cb.btm_inq_vars.inq_counter) /* first time seen in this inquiry */
  {
    p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
    btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
  }

  /* update the LE device information in inquiry database */
  btm_ble_update_inq_result(p_i, addr_type, bda, evt_type, primary_phy, secondary_phy,
                            advertising_sid, tx_power, rssi, periodic_adv_int, adv_data);

  if (include_rsi) {
    (&p_i->inq_info.results)->include_rsi = true;
  }

  tBTM_INQ_RESULTS_CB* p_opportunistic_obs_results_cb =
          btm_cb.ble_ctr_cb.p_opportunistic_obs_results_cb;
  if (p_opportunistic_obs_results_cb) {
    (p_opportunistic_obs_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                                     const_cast<uint8_t*>(adv_data.data()), adv_data.size());
  }

  tBTM_INQ_RESULTS_CB* p_target_announcement_obs_results_cb =
          btm_cb.ble_ctr_cb.p_target_announcement_obs_results_cb;
  if (p_target_announcement_obs_results_cb) {
    (p_target_announcement_obs_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                                           const_cast<uint8_t*>(adv_data.data()), adv_data.size());
  }

  uint8_t result = btm_ble_is_discoverable(bda, adv_data);
  if (result == 0) {
    // Device no longer discoverable so discard outstanding advertising packet
    cache.Clear(addr_type, bda);
    return;
  }

  if (!update) {
    result &= ~BTM_BLE_INQ_RESULT;
  }

  tBTM_INQ_RESULTS_CB* p_inq_results_cb = btm_cb.btm_inq_vars.p_inq_results_cb;
  if (p_inq_results_cb && (result & BTM_BLE_INQ_RESULT)) {
    (p_inq_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                       const_cast<uint8_t*>(adv_data.data()), adv_data.size());
  }

  // Pass address up to ScanController#onScanResult
  p_i->inq_info.results.original_bda = original_bda;

  tBTM_INQ_RESULTS_CB* p_obs_results_cb = btm_cb.ble_ctr_cb.p_obs_results_cb;
  if (p_obs_results_cb && (result & BTM_BLE_OBS_RESULT)) {
    (p_obs_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                       const_cast<uint8_t*>(adv_data.data()), adv_data.size());
  }

  cache.Clear(addr_type, bda);
}

/**
 * This function copy from btm_ble_process_adv_pkt_cont to process adv packet
 * from gd scanning module to handle inquiry result callback.
 */
void btm_ble_process_adv_pkt_cont_for_inquiry(uint16_t evt_type, tBLE_ADDR_TYPE addr_type,
                                              const RawAddress& bda, uint8_t primary_phy,
                                              uint8_t secondary_phy, uint8_t advertising_sid,
                                              int8_t tx_power, int8_t rssi,
                                              uint16_t periodic_adv_int,
                                              std::vector<uint8_t> advertising_data) {
  bool update = true;
  bool include_rsi = false;

  uint8_t len;
  const uint8_t* p_flag =
          AdvertiseDataParser::GetFieldByType(advertising_data, BTM_BLE_AD_TYPE_FLAG, &len);

  if (len > 1) {
    log::warn("Dropping bad advertising packet from {}: len={}", bda, len);
    return;
  }

  if (AdvertiseDataParser::GetFieldByType(advertising_data, BTM_BLE_AD_TYPE_RSI, &len)) {
    include_rsi = true;
  }

  tINQ_DB_ENT* p_i = btm_inq_db_find(bda);

  /* Check if this address has already been processed for this inquiry */
  if (btm_inq_find_bdaddr(bda)) {
    /* never been reported as an LE device */
    if (p_i && (!(p_i->inq_info.results.device_type & BT_DEVICE_TYPE_BLE) ||
                /* scan response to be updated */
                (!p_i->scan_rsp) || (!p_i->inq_info.results.include_rsi && include_rsi) ||
                /* previous report had null flag and current report has flag value */
                (!p_i->inq_info.results.flag && p_flag && *p_flag))) {
      update = true;
    } else if (btm_cb.ble_ctr_cb.is_ble_observe_active()) {
      btm_cb.neighbor.le_observe.results++;
      update = false;
    } else {
      /* if yes, skip it */
      log::debug("Address has already been processed for this inquiry, and no update on flag");
      return; /* assumption: one result per event */
    }
  }

  /* If existing entry, use that, else get a new one (possibly reusing the
   * oldest) */
  if (p_i == NULL) {
    p_i = btm_inq_db_new(bda, true);
    if (p_i != NULL) {
      btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
      p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
      btm_cb.neighbor.le_inquiry.results++;
      btm_cb.neighbor.le_legacy_scan.results++;
    } else {
      log::debug("Unable to allocate entry for inquiry result");
      return;
    }
  } else if (p_i->inq_count !=
             btm_cb.btm_inq_vars.inq_counter) /* first time seen in this inquiry */
  {
    p_i->time_of_resp = bluetooth::common::time_get_os_boottime_ms();
    btm_cb.btm_inq_vars.inq_cmpl_info.num_resp++;
  }

  /* update the LE device information in inquiry database */
  btm_ble_update_inq_result(p_i, addr_type, bda, evt_type, primary_phy, secondary_phy,
                            advertising_sid, tx_power, rssi, periodic_adv_int, advertising_data);

  if (include_rsi) {
    (&p_i->inq_info.results)->include_rsi = true;
  }

  tBTM_INQ_RESULTS_CB* p_opportunistic_obs_results_cb =
          btm_cb.ble_ctr_cb.p_opportunistic_obs_results_cb;
  if (p_opportunistic_obs_results_cb) {
    (p_opportunistic_obs_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                                     const_cast<uint8_t*>(advertising_data.data()),
                                     advertising_data.size());
  }

  tBTM_INQ_RESULTS_CB* p_target_announcement_obs_results_cb =
          btm_cb.ble_ctr_cb.p_target_announcement_obs_results_cb;
  if (p_target_announcement_obs_results_cb) {
    (p_target_announcement_obs_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                                           const_cast<uint8_t*>(advertising_data.data()),
                                           advertising_data.size());
  }

  uint8_t result = btm_ble_is_discoverable(bda, advertising_data);
  if (result == 0) {
    log::debug("BLE is not scanning");
    return;
  }

  if (!update) {
    /* This will result in no inquiry result callback being sent */
    log::verbose("There was no update from the previous inquiry result, so removing inquiry bit");
    result &= ~BTM_BLE_INQ_RESULT;
  }

  tBTM_INQ_RESULTS_CB* p_inq_results_cb = btm_cb.btm_inq_vars.p_inq_results_cb;
  if (p_inq_results_cb && (result & BTM_BLE_INQ_RESULT)) {
    (p_inq_results_cb)((tBTM_INQ_RESULTS*)&p_i->inq_info.results,
                       const_cast<uint8_t*>(advertising_data.data()), advertising_data.size());
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_start_scan
 *
 * Description      Start the BLE scan.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_start_scan() {
  btm_cb.neighbor.le_legacy_scan = {
          .start_time_ms = timestamper_in_milliseconds.GetTimestamp(),
          .results = 0,
  };
  BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le legacy scan started", "Duplicates:disable");

  /* start scan, disable duplicate filtering */
  btm_send_hci_scan_enable(BTM_BLE_SCAN_ENABLE, BTM_BLE_DUPLICATE_DISABLE);
}

/*******************************************************************************
 *
 * Function         btm_update_scanner_filter_policy
 *
 * Description      This function updates the filter policy of scanner
 ******************************************************************************/
static void btm_update_scanner_filter_policy(tBTM_BLE_SFP scan_policy) {
  uint32_t scan_interval_1m = !btm_cb.ble_ctr_cb.inq_var.scan_interval_1m
                                      ? BTM_BLE_GAP_DISC_SCAN_INT
                                      : btm_cb.ble_ctr_cb.inq_var.scan_interval_1m;
  uint32_t scan_window_1m = !btm_cb.ble_ctr_cb.inq_var.scan_window_1m
                                    ? BTM_BLE_GAP_DISC_SCAN_WIN
                                    : btm_cb.ble_ctr_cb.inq_var.scan_window_1m;
  uint32_t scan_interval_coded = !btm_cb.ble_ctr_cb.inq_var.scan_interval_coded
                                         ? BTM_BLE_GAP_DISC_SCAN_INT
                                         : btm_cb.ble_ctr_cb.inq_var.scan_interval_coded;
  uint32_t scan_window_coded = !btm_cb.ble_ctr_cb.inq_var.scan_window_coded
                                       ? BTM_BLE_GAP_DISC_SCAN_WIN
                                       : btm_cb.ble_ctr_cb.inq_var.scan_window_coded;
  uint8_t scan_phy = !btm_cb.ble_ctr_cb.inq_var.scan_phy ? BTM_BLE_DEFAULT_PHYS
                                                         : btm_cb.ble_ctr_cb.inq_var.scan_phy;

  log::verbose("");

  btm_cb.ble_ctr_cb.inq_var.sfp = scan_policy;
  btm_cb.ble_ctr_cb.inq_var.scan_type =
          btm_cb.ble_ctr_cb.inq_var.scan_type == BTM_BLE_SCAN_MODE_NONE
                  ? BTM_BLE_SCAN_MODE_ACTI
                  : btm_cb.ble_ctr_cb.inq_var.scan_type;

  btm_send_hci_set_scan_params(btm_cb.ble_ctr_cb.inq_var.scan_type, (uint16_t)scan_interval_1m,
                               (uint16_t)scan_window_1m, (uint16_t)scan_interval_coded,
                               (uint16_t)scan_window_coded, (uint8_t)scan_phy,
                               btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, scan_policy);
}

/*******************************************************************************
 *
 * Function         btm_ble_stop_scan
 *
 * Description      Stop the BLE scan.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_stop_scan(bool update_scan_filter_policy = true) {
  /* Clear the inquiry callback if set */
  btm_cb.ble_ctr_cb.inq_var.scan_type = BTM_BLE_SCAN_MODE_NONE;

  /* stop discovery now */
  const uint64_t duration_timestamp =
          timestamper_in_milliseconds.GetTimestamp() - btm_cb.neighbor.le_legacy_scan.start_time_ms;
  BTM_LogHistory(
          kBtmLogTag, RawAddress::kEmpty, "Le legacy scan stopped",
          std::format("duration_s:{:6.3f} results:{:<3}", (double)duration_timestamp / 1000.0,
                      btm_cb.neighbor.le_legacy_scan.results));
  btm_send_hci_scan_enable(BTM_BLE_SCAN_DISABLE, BTM_BLE_DUPLICATE_ENABLE);

  if (update_scan_filter_policy) {
    btm_update_scanner_filter_policy(SP_ADV_ALL);
  }

  // For simplicity, disable MSFT filtered scan whenever we stop LE scanning.
  // Defer the decision on whether or not to re-enable it for later.
  btm_ble_msft_adv_mon_enable(/*enable=*/false, /*restart_scan=*/false);
}
/*******************************************************************************
 *
 * Function         btm_ble_stop_inquiry
 *
 * Description      Stop the BLE Inquiry.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_stop_inquiry(void) {
  alarm_cancel(btm_cb.ble_ctr_cb.inq_var.inquiry_timer);

  const uint64_t duration_timestamp =
          timestamper_in_milliseconds.GetTimestamp() - btm_cb.neighbor.le_inquiry.start_time_ms;
  BTM_LogHistory(
          kBtmLogTag, RawAddress::kEmpty, "Le inquiry stopped",
          std::format("duration_s:{:6.3f} results:{:<3}", (double)duration_timestamp / 1000.0,
                      btm_cb.neighbor.le_inquiry.results));
  btm_cb.ble_ctr_cb.reset_ble_inquiry();

  /* Cleanup anything remaining on index 0 */
  BTM_BleAdvFilterParamSetup(BTM_BLE_SCAN_COND_DELETE, static_cast<tBTM_BLE_PF_FILT_INDEX>(0),
                             nullptr, base::Bind(btm_ble_scan_filt_param_cfg_evt));

  /* If no more scan activity, stop LE scan now */
  if (!btm_cb.ble_ctr_cb.is_ble_scan_active()) {
    btm_ble_stop_scan();
  } else if (!btm_cb.ble_ctr_cb.inq_var.is_1m_phy_configured() ||
             get_low_latency_scan_params() != std::pair(btm_cb.ble_ctr_cb.inq_var.scan_interval_1m,
                                                        btm_cb.ble_ctr_cb.inq_var.scan_window_1m)) {
    log::verbose("Setting scan parameters to values requested previously from ongoing observer");
    btm_ble_stop_scan();
    uint8_t scan_filter_policy = use_msft_filtering() ? SP_ACCEPT_LIST_ONLY : SP_ADV_ALL;
    btm_send_hci_set_scan_params(
            BTM_BLE_SCAN_MODE_ACTI, btm_cb.ble_ctr_cb.inq_var.scan_interval_1m,
            btm_cb.ble_ctr_cb.inq_var.scan_window_1m, btm_cb.ble_ctr_cb.inq_var.scan_interval_coded,
            btm_cb.ble_ctr_cb.inq_var.scan_window_coded, btm_cb.ble_ctr_cb.inq_var.scan_phy,
            btm_cb.ble_ctr_cb.addr_mgnt_cb.own_addr_type, scan_filter_policy);
    if (use_msft_filtering()) {
      btm_ble_msft_adv_mon_enable(/*enable=*/true, /*restart_scan=*/true);
    } else {
      btm_ble_start_scan();
    }
  }

  /* If we have a callback registered for inquiry complete, call it */
  log::verbose("BTM Inq Compl Callback: status 0x{:02x}, num results {}",
               btm_cb.btm_inq_vars.inq_cmpl_info.status,
               btm_cb.btm_inq_vars.inq_cmpl_info.num_resp);

  // TODO: remove this call and make btm_process_inq_complete static
  btm_process_inq_complete(HCI_SUCCESS,
                           (uint8_t)(btm_cb.btm_inq_vars.inqparms.mode & BTM_BLE_GENERAL_INQUIRY));
}

/*******************************************************************************
 *
 * Function         btm_ble_stop_observe
 *
 * Description      Stop the BLE Observe.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btm_ble_stop_observe(void) {
  tBTM_INQUIRY_CMPL_CB* p_obs_cb = btm_cb.ble_ctr_cb.p_obs_cmpl_cb;

  alarm_cancel(btm_cb.ble_ctr_cb.observer_timer);

  btm_cb.ble_ctr_cb.reset_ble_observe();

  btm_cb.ble_ctr_cb.p_obs_results_cb = NULL;
  btm_cb.ble_ctr_cb.p_obs_cmpl_cb = NULL;

  if (!btm_cb.ble_ctr_cb.is_ble_scan_active()) {
    btm_ble_stop_scan();
  }

  if (p_obs_cb) {
    (p_obs_cb)(&btm_cb.btm_inq_vars.inq_cmpl_info);
  }
}

static void btm_ble_inquiry_timer_timeout(void* /* data */) { btm_ble_stop_inquiry(); }

static void btm_ble_observer_timer_timeout(void* /* data */) { btm_ble_stop_observe(); }

/*******************************************************************************
 *
 * Function         btm_ble_read_remote_features_complete
 *
 * Description      This function is called when the command complete message
 *                  is received from the HCI for the read LE remote feature
 *                  supported complete event.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_read_remote_features_complete(uint8_t* p, uint8_t length) {
  uint16_t handle;
  uint8_t status;

  if (length < 3) {
    goto err_out;
  }

  STREAM_TO_UINT8(status, p);
  STREAM_TO_UINT16(handle, p);
  handle = handle & 0x0FFF;  // only 12 bits meaningful

  if (status != HCI_SUCCESS) {
    if (status != HCI_ERR_UNSUPPORTED_REM_FEATURE) {
      log::error("Failed to read remote features status:{}",
                 hci_error_code_text(static_cast<tHCI_STATUS>(status)));
      return;
    }
    log::warn("Remote does not support reading remote feature");
  }

  if (status == HCI_SUCCESS) {
    // BD_FEATURES_LEN additional bytes are read
    // in acl_set_peer_le_features_from_handle
    if (length < 3 + BD_FEATURES_LEN) {
      goto err_out;
    }

    if (!acl_set_peer_le_features_from_handle(handle, p)) {
      log::error("Unable to find existing connection after read remote features");
      return;
    }

    if (com_android_bluetooth_flags_le_subrate_manager()) {
      const BtmDevice* p_device = btm_find_dev_by_handle(handle);
      if (p_device) {
          // init when acl connected & remote_feature received
          gatt_init_subrate_cb(p_device->ble.pseudo_addr);
      }
    }
  }

  btsnd_hcic_rmt_ver_req(handle);

  return;

err_out:
  log::error("Bogus event packet, too short");
}

void btm_ble_increment_link_topology_mask(uint8_t link_role) {
  btm_cb.ble_ctr_cb.link_count[link_role]++;
}

void btm_ble_decrement_link_topology_mask(uint8_t link_role) {
  if (btm_cb.ble_ctr_cb.link_count[link_role] > 0) {
    btm_cb.ble_ctr_cb.link_count[link_role]--;
  }
}

/*******************************************************************************
 *
 * Function         btm_ble_init
 *
 * Description      Initialize the control block variable values.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_ble_init(void) {
  log::verbose("");

  alarm_free(btm_cb.ble_ctr_cb.observer_timer);
  memset(&btm_cb.ble_ctr_cb, 0, sizeof(tBTM_BLE_CB));
  memset(&(btm_cb.cmn_ble_vsc_cb), 0, sizeof(tBTM_BLE_VSC_CB));
  btm_cb.cmn_ble_vsc_cb.values_read = false;

  btm_cb.ble_ctr_cb.observer_timer = alarm_new("btm_ble.observer_timer");

  btm_cb.ble_ctr_cb.inq_var.scan_type = BTM_BLE_SCAN_MODE_NONE;
  btm_cb.ble_ctr_cb.inq_var.adv_chnl_map = BTM_BLE_DEFAULT_ADV_CHNL_MAP;
  btm_cb.ble_ctr_cb.inq_var.afp = BTM_BLE_DEFAULT_AFP;
  btm_cb.ble_ctr_cb.inq_var.sfp = BTM_BLE_DEFAULT_SFP;
  btm_cb.ble_ctr_cb.inq_var.inquiry_timer = alarm_new("btm_ble_inq.inquiry_timer");

  btm_cb.ble_ctr_cb.inq_var.evt_type = BTM_BLE_NON_CONNECT_EVT;

  btm_cb.ble_ctr_cb.addr_mgnt_cb.refresh_raddr_timer =
          alarm_new("btm_ble_addr.refresh_raddr_timer");
  btm_ble_pa_sync_cb = {};
  sync_timeout_alarm = alarm_new("btm.sync_start_task");
  if (!android::sysprop::bluetooth::Ble::vnd_included()) {
    btm_ble_adv_filter_init();
  }
}

// Clean up btm ble control block
void btm_ble_free() { alarm_free(btm_cb.ble_ctr_cb.addr_mgnt_cb.refresh_raddr_timer); }
