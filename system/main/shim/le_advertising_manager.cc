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

#define LOG_TAG "bt_shim_advertiser"

#include "le_advertising_manager.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>

#include <vector>

#include "btif/include/btif_common.h"
#include "hci/le_advertising_manager.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/main_thread.h"
#include "utils.h"

using bluetooth::hci::Address;
using bluetooth::hci::AdvertiserAddressType;
using bluetooth::hci::GapData;
using bluetooth::shim::parse_gap_data;
using namespace bluetooth;

namespace {
constexpr char kBtmLogTag[] = "ADV";
}

class BleAdvertiserInterfaceImpl : public ::BleAdvertiserInterface,
                                   public bluetooth::hci::AdvertisingCallback {
public:
  ~BleAdvertiserInterfaceImpl() override {}

  void Init() {
    // Register callback
    bluetooth::shim::GetAdvertising()->RegisterAdvertisingCallback(this);
  }

  // ::BleAdvertiserInterface
  void RegisterAdvertiser(IdStatusCallback cb) override {
    log::info("in shim layer");

    bluetooth::shim::GetAdvertising()->RegisterAdvertiser(
            bluetooth::shim::GetGdShimHandler()->BindOnce(
                    [](::BleAdvertiserInterface::IdStatusCallback cb, uint8_t id,
                       AdvertisingCallback::AdvertisingStatus status) {
                      do_in_main_thread(base::BindOnce(
                              [](::BleAdvertiserInterface::IdStatusCallback cb, uint8_t id,
                                 AdvertisingCallback::AdvertisingStatus status) {
                                cb.Run(id, static_cast<uint8_t>(status));
                              },
                              cb, id, status));
                    },
                    cb));
  }

  // ::BleAdvertiserInterface
  void Unregister(uint8_t advertiser_id) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->RemoveAdvertiser(advertiser_id);
    int reg_id = bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    // if registered by native client, remove the register id
    if (client_id != kAdvertiserClientIdJni) {
      native_reg_id_map_[client_id].erase(reg_id);
    }
    if (com_android_bluetooth_flags_fix_private_gatt_advertisement()) {
      // TODO(b/406124107): When removing flag, consider this lock_guard.
      std::lock_guard<std::mutex> lock(reg_callback_mutex_);
      reg_id_to_reg_callback_.erase(reg_id);
    }

    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert stopped",
                   std::format("advert_id:{}", advertiser_id));
  }

  // ::BleAdvertiserInterface
  void GetOwnAddress(uint8_t advertiser_id,
                     ::BleAdvertiserInterface::GetAddressCallback cb) override {
    log::info("in shim layer");
    address_callbacks_[advertiser_id] = jni_thread_wrapper(std::move(cb));
    bluetooth::shim::GetAdvertising()->GetOwnAddress(advertiser_id);
  }

  // ::BleAdvertiserInterface
  void SetParameters(uint8_t advertiser_id, ::AdvertiseParameters params,
                     ::BleAdvertiserInterface::ParametersCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    bluetooth::shim::GetAdvertising()->SetParameters(advertiser_id, config);
  }

  // ::BleAdvertiserInterface
  void SetData(int advertiser_id, bool set_scan_rsp, std::vector<uint8_t> data,
               ::BleAdvertiserInterface::StatusCallback /* cb */) override {
    log::info("in shim layer");
    std::vector<GapData> advertising_data = {};
    parse_gap_data(data, advertising_data);
    bluetooth::shim::GetAdvertising()->SetData(advertiser_id, set_scan_rsp, advertising_data);
  }

  // ::BleAdvertiserInterface
  void Enable(uint8_t advertiser_id, bool enable, ::BleAdvertiserInterface::StatusCallback /* cb */,
              uint16_t duration, uint8_t maxExtAdvEvents,
              ::BleAdvertiserInterface::StatusCallback /* timeout_cb */) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->EnableAdvertiser(advertiser_id, enable, duration,
                                                        maxExtAdvEvents);
  }

  // nobody use this function
  // ::BleAdvertiserInterface
  void StartAdvertising(uint8_t advertiser_id, ::BleAdvertiserInterface::StatusCallback cb,
                        ::AdvertiseParameters params, std::vector<uint8_t> advertise_data,
                        std::vector<uint8_t> scan_response_data, int timeout_s,
                        ::BleAdvertiserInterface::StatusCallback timeout_cb) override {
    log::info("in shim layer");

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);

    parse_gap_data(advertise_data, config.advertisement);
    parse_gap_data(scan_response_data, config.scan_response);

    bluetooth::shim::GetAdvertising()->StartAdvertising(advertiser_id, config, timeout_s * 100, cb,
                                                        timeout_cb,
                                                        bluetooth::shim::GetGdShimHandler());
  }

  // ::BleAdvertiserInterface
  void StartAdvertisingSet(uint8_t client_id, int reg_id,
                           ::BleAdvertiserInterface::IdTxPowerStatusCallback register_cb,
                           ::AdvertiseParameters params, std::vector<uint8_t> advertise_data,
                           std::vector<uint8_t> scan_response_data,
                           ::PeriodicAdvertisingParameters periodic_params,
                           std::vector<uint8_t> periodic_data, uint16_t duration,
                           uint8_t maxExtAdvEvents,
                           ::BleAdvertiserInterface::IdStatusCallback /* timeout_cb */) override {
    log::info("in shim layer");

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    parse_periodic_advertising_parameter(config.periodic_advertising_parameters, periodic_params);

    parse_gap_data(advertise_data, config.advertisement);
    parse_gap_data(scan_response_data, config.scan_response);
    parse_gap_data(periodic_data, config.periodic_data);

    // if registered by native client, add the register id
    if (client_id != kAdvertiserClientIdJni) {
      native_reg_id_map_[client_id].insert(reg_id);
    }

    if (com_android_bluetooth_flags_fix_private_gatt_advertisement()) {
      // TODO(b/406124107): When removing flag, consider this lock_guard.
      std::lock_guard<std::mutex> lock(reg_callback_mutex_);
      if (!reg_id_to_reg_callback_.insert_or_assign(reg_id, register_cb).second) {
        log::warn("reg_id {} is already in the reg_id_to_reg_callback map!", reg_id);
      }
    }

    bluetooth::shim::GetAdvertising()->ExtendedCreateAdvertiser(
            client_id, reg_id, config, duration, maxExtAdvEvents,
            bluetooth::shim::GetGdShimHandler());

    log::info("create advertising set, client_id:{}, reg_id:{}", client_id, reg_id);
    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert started",
                   std::format("reg_id:{}", reg_id));

    return;
  }

  // ::BleAdvertiserInterface
  void SetPeriodicAdvertisingParameters(
          int advertiser_id, ::PeriodicAdvertisingParameters periodic_params,
          ::BleAdvertiserInterface::StatusCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::hci::PeriodicAdvertisingParameters parameters;
    parameters.max_interval = periodic_params.max_interval;
    parameters.min_interval = periodic_params.min_interval;
    parameters.properties = periodic_params.periodic_advertising_properties;
    bluetooth::shim::GetAdvertising()->SetPeriodicParameters(advertiser_id, parameters);
  }

  // ::BleAdvertiserInterface
  void SetPeriodicAdvertisingData(int advertiser_id, std::vector<uint8_t> data,
                                  ::BleAdvertiserInterface::StatusCallback /* cb */) override {
    log::info("in shim layer");
    std::vector<GapData> advertising_data = {};
    parse_gap_data(data, advertising_data);
    bluetooth::shim::GetAdvertising()->SetPeriodicData(advertiser_id, advertising_data);
  }

  // ::BleAdvertiserInterface
  void SetPeriodicAdvertisingEnable(int advertiser_id, bool enable, bool include_adi,
                                    ::BleAdvertiserInterface::StatusCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->EnablePeriodicAdvertising(advertiser_id, enable,
                                                                 include_adi);
  }

  // ::BleAdvertiserInterface
  void RegisterCallbacks(::AdvertisingCallbacks* callbacks) override {
    advertising_callbacks_ = callbacks;
  }

  // ::BleAdvertiserInterface
  void RegisterCallbacksNative(::AdvertisingCallbacks* callbacks, uint8_t client_id) override {
    native_adv_callbacks_map_[client_id] = callbacks;
  }

  // bluetooth::hci::AdvertisingCallback
  void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id, int8_t tx_power,
                               AdvertisingCallback::AdvertisingStatus status) override {
    if (status != AdvertisingCallback::AdvertisingStatus::SUCCESS) {
      log::info("Failed to start advertiser {}. Removing it.", advertiser_id);
      bluetooth::shim::GetAdvertising()->RemoveAdvertiser(advertiser_id);
    }

    uint8_t client_id = is_native_advertiser(reg_id);
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingSetStarted,
                                       base::Unretained(native_adv_callbacks_map_[client_id]),
                                       reg_id, advertiser_id, tx_power, status));
      return;
    }

    if (com_android_bluetooth_flags_fix_private_gatt_advertisement()) {
      // TODO(b/406124107): When removing flag, consider this lock_guard.
      std::lock_guard<std::mutex> lock(reg_callback_mutex_);
      if (reg_id_to_reg_callback_.contains(reg_id)) {
        do_in_jni_thread(
                base::BindOnce(reg_id_to_reg_callback_[reg_id], advertiser_id, tx_power, status));
        reg_id_to_reg_callback_.erase(reg_id);
      }
    }

    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingSetStarted,
                                    base::Unretained(advertising_callbacks_), reg_id, advertiser_id,
                                    tx_power, status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                            AdvertisingCallback::AdvertisingStatus status) override {
    int reg_id = bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingEnabled,
                                       base::Unretained(native_adv_callbacks_map_[client_id]),
                                       advertiser_id, enable, status));
      return;
    }
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingEnabled,
                                    base::Unretained(advertising_callbacks_), advertiser_id, enable,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnAdvertisingDataSet(uint8_t advertiser_id,
                            AdvertisingCallback::AdvertisingStatus status) override {
    int reg_id = bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingDataSet,
                                       base::Unretained(native_adv_callbacks_map_[client_id]),
                                       advertiser_id, status));
      return;
    }
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingDataSet,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnScanResponseDataSet(uint8_t advertiser_id,
                             AdvertisingCallback::AdvertisingStatus status) override {
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnScanResponseDataSet,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t tx_power,
                                      AdvertisingCallback::AdvertisingStatus status) override {
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnAdvertisingParametersUpdated,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    tx_power, status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnPeriodicAdvertisingParametersUpdated(
          uint8_t advertiser_id, AdvertisingCallback::AdvertisingStatus status) override {
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnPeriodicAdvertisingParametersUpdated,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id,
                                    AdvertisingCallback::AdvertisingStatus status) override {
    int reg_id = bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(base::BindOnce(&::AdvertisingCallbacks::OnPeriodicAdvertisingDataSet,
                                       base::Unretained(native_adv_callbacks_map_[client_id]),
                                       advertiser_id, status));
      return;
    }
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnPeriodicAdvertisingDataSet,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                    AdvertisingCallback::AdvertisingStatus status) override {
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnPeriodicAdvertisingEnabled,
                                    base::Unretained(advertising_callbacks_), advertiser_id, enable,
                                    status));
  }

  // bluetooth::hci::AdvertisingCallback
  void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type, Address address) override {
    RawAddress raw_address = bluetooth::ToRawAddress(address);
    auto cb_iter = address_callbacks_.find(advertiser_id);
    if (cb_iter != address_callbacks_.end()) {
      std::move(cb_iter->second).Run(address_type, raw_address);
      address_callbacks_.erase(cb_iter);
      return;
    }
    do_in_jni_thread(base::BindOnce(&::AdvertisingCallbacks::OnOwnAddressRead,
                                    base::Unretained(advertising_callbacks_), advertiser_id,
                                    address_type, raw_address));
  }

  ::AdvertisingCallbacks* advertising_callbacks_;
  std::map<uint8_t, ::AdvertisingCallbacks*> native_adv_callbacks_map_;

private:
  // Convert ble advertising parameters into implemented configuration parameters
  void parse_parameter(bluetooth::hci::AdvertisingConfig& config, ::AdvertiseParameters params) {
    config.connectable = params.advertising_event_properties & 0x01;
    config.scannable = params.advertising_event_properties & 0x02;
    config.directed = params.advertising_event_properties & 0x04;
    config.high_duty_cycle = params.advertising_event_properties & 0x08;
    config.legacy_pdus = params.advertising_event_properties & 0x10;
    config.anonymous = params.advertising_event_properties & 0x20;
    config.include_tx_power = params.advertising_event_properties & 0x40;
    config.discoverable = params.discoverable;
    config.interval_min = params.min_interval;
    config.interval_max = params.max_interval;
    config.channel_map = params.channel_map;
    config.tx_power = params.tx_power;
    config.use_le_coded_phy = params.primary_advertising_phy == 0x03;
    config.secondary_advertising_phy =
            static_cast<bluetooth::hci::SecondaryPhyType>(params.secondary_advertising_phy);
    config.enable_scan_request_notifications =
            static_cast<bluetooth::hci::Enable>(params.scan_request_notification_enable);
    config.peer_address = params.peer_address;
    // Matching the ADDRESS_TYPE_* enums from Java
    switch (params.own_address_type) {
      case -1:
        config.requested_advertiser_address_type = AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 0:
        config.requested_advertiser_address_type = AdvertiserAddressType::PUBLIC;
        break;
      case 1:
        config.requested_advertiser_address_type = AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 2:
        config.requested_advertiser_address_type = AdvertiserAddressType::NONRESOLVABLE_RANDOM;
        break;
      default:
        log::error("Received unexpected address type: {}", params.own_address_type);
        config.requested_advertiser_address_type = AdvertiserAddressType::RESOLVABLE_RANDOM;
    }
    switch (params.peer_address_type) {
      case 0:
        config.peer_address_type =
                bluetooth::hci::PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS;
        break;
      case 1:
        config.peer_address_type =
                bluetooth::hci::PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS;
        break;
      default:
        log::error("Received unexpected peer address type: {}", params.peer_address_type);
    }
  }

  // Convert ble periodic advertising parameters into implemented configuration parameters
  void parse_periodic_advertising_parameter(bluetooth::hci::PeriodicAdvertisingParameters& config,
                                            ::PeriodicAdvertisingParameters periodic_params) {
    config.max_interval = periodic_params.max_interval;
    config.min_interval = periodic_params.min_interval;
    config.properties = periodic_params.periodic_advertising_properties;
    config.enable = periodic_params.enable;
    config.include_adi = periodic_params.include_adi;
  }

  uint8_t is_native_advertiser(int reg_id) {
    // Return client id if it's native advertiser, otherwise return jni id as
    // default
    for (auto const& entry : native_adv_callbacks_map_) {
      if (native_reg_id_map_[entry.first].count(reg_id)) {
        return entry.first;
      }
    }
    return kAdvertiserClientIdJni;
  }

  std::map<uint8_t, ::BleAdvertiserInterface::GetAddressCallback> address_callbacks_;
  std::map<uint8_t, std::set<int>> native_reg_id_map_;

  std::mutex reg_callback_mutex_;
  std::map<uint8_t, ::BleAdvertiserInterface::IdTxPowerStatusCallback> reg_id_to_reg_callback_
          GUARDED_BY(reg_callback_mutex_);
};

::BleAdvertiserInterface* bluetooth::shim::get_ble_advertiser_instance() {
  static BleAdvertiserInterfaceImpl* bt_le_advertiser_instance = nullptr;
  if (bt_le_advertiser_instance == nullptr) {
    bt_le_advertiser_instance = new BleAdvertiserInterfaceImpl();
  }
  return bt_le_advertiser_instance;
}

void bluetooth::shim::init_advertising_manager() {
  static_cast<BleAdvertiserInterfaceImpl*>(bluetooth::shim::get_ble_advertiser_instance())->Init();
}
