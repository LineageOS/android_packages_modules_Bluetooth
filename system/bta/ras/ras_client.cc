/*
 * Copyright 2024 The Android Open Source Project
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

#include <base/functional/bind.h>
#include <base/functional/callback.h>

#include <algorithm>
#include <cstdint>
#include <list>
#include <memory>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "bluetooth/log.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "btm_ble_api_types.h"
#include "gatt/database.h"
#include "gatt_api.h"
#include "gattdefs.h"
#include "gd/hci/controller_interface.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/gap_api.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"
#include "types/ble_address_with_type.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::feature;
using namespace ::ras::uuid;
using bluetooth::ras::RasDisconnectReason;
using bluetooth::ras::VendorSpecificCharacteristic;

namespace {

class RasClientImpl;
RasClientImpl* instance;

enum CallbackDataType { VENDOR_SPECIFIC_REPLY };
enum TimeoutType { TIMEOUT_NONE, FIRST_SEGMENT, FOLLOWING_SEGMENT, RANGING_DATA_READY };
enum RangingType { RANGING_TYPE_NONE, REAL_TIME, ON_DEMAND };

class RasClientImpl : public bluetooth::ras::RasClient {
  static constexpr uint16_t kCachedDataSize = 10;
  static constexpr uint16_t kInvalidGattHandle = 0x0000;
  static constexpr uint16_t kFirstSegmentRangingDataTimeoutMs = 5000;
  static constexpr uint16_t kFollowingSegmentTimeoutMs = 1000;
  static constexpr uint16_t kRangingDataReadyTimeoutMs = 5000;
  static constexpr uint16_t kInvalidConnInterval = 0;  // valid value is from 0x0006 to 0x0C0
  static constexpr uint16_t kMinimumRasMtu = 247;      // 4.1 Maximum transmission unit of RAP 1.0

public:
  struct GattReadCallbackData {
    const bool is_last_;
  };

  struct GattWriteCallbackData {
    const CallbackDataType type_;
  };

  struct CachedRasData {
    uint8_t id_ = 0;
    uint32_t remote_supported_features_;
    std::unordered_map<bluetooth::Uuid, std::vector<uint8_t>> vendor_specific_data_;
  };

  struct RasTracker {
    RasTracker(const RawAddress& address, const RawAddress& address_for_cs)
        : address_(address), address_for_cs_(address_for_cs) {}
    ~RasTracker() {
      if (ranging_data_timeout_timer_ != nullptr) {
        alarm_free(ranging_data_timeout_timer_);
      }
    }
    tCONN_ID conn_id_;
    RawAddress address_;
    RawAddress address_for_cs_;
    const gatt::Service* service_ = nullptr;
    uint32_t remote_supported_features_;
    uint16_t latest_ranging_counter_ = 0;
    bool handling_on_demand_data_ = false;
    bool is_connected_ = false;
    bool service_search_complete_ = false;
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics_;
    uint8_t write_reply_counter_ = 0;
    uint8_t write_reply_success_counter_ = 0;
    alarm_t* ranging_data_timeout_timer_ = nullptr;
    RangingType ranging_type_ = RANGING_TYPE_NONE;
    TimeoutType timeout_type_ = TIMEOUT_NONE;
    uint16_t conn_interval_ = kInvalidConnInterval;
    uint16_t mtu = kDefaultGattMtu;

    const gatt::Characteristic* FindCharacteristicByUuid(Uuid uuid) {
      if (service_ == nullptr) {
        log::error("Can't find Ranging Service");
        return nullptr;
      }
      for (auto& characteristic : service_->characteristics) {
        if (characteristic.uuid == uuid) {
          return &characteristic;
        }
      }
      return nullptr;
    }

    const gatt::Characteristic* FindCharacteristicByHandle(uint16_t handle) {
      for (auto& characteristic : service_->characteristics) {
        if (characteristic.value_handle == handle) {
          return &characteristic;
        }
      }
      return nullptr;
    }

    VendorSpecificCharacteristic* GetVendorSpecificCharacteristic(const bluetooth::Uuid& uuid) {
      for (auto& characteristic : vendor_specific_characteristics_) {
        if (characteristic.characteristicUuid_ == uuid) {
          return &characteristic;
        }
      }
      return nullptr;
    }
  };

  void Initialize() override {
    do_in_main_thread(base::BindOnce(&RasClientImpl::do_initialize, base::Unretained(this)));
  }

  void do_initialize() {
    auto controller = bluetooth::shim::GetController();
    if (controller && !controller->SupportsBleChannelSounding()) {
      log::info("controller does not support channel sounding.");
      return;
    }
    BTA_GATTC_AppRegister(
            "ranging_service",
            [](tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
              if (instance && p_data) {
                instance->GattcCallback(event, p_data);
              }
            },
            base::Bind([](uint8_t client_id, uint8_t status) {
              if (status != GATT_SUCCESS) {
                log::error("Can't start Gatt client for Ranging Service");
                return;
              }
              log::info("Initialize, client_id {}", client_id);
              instance->gatt_if_ = client_id;
            }),
            /*eatt_support=*/false);
  }

  void RegisterCallbacks(bluetooth::ras::RasClientCallbacks* callbacks) { callbacks_ = callbacks; }

  void Connect(const RawAddress& address) override {
    tBLE_BD_ADDR ble_bd_addr;
    ResolveAddress(ble_bd_addr, address);
    log::info("address {}, resolve {}", address, ble_bd_addr.bda);

    auto tracker = FindTrackerByAddress(ble_bd_addr.bda);
    if (tracker == nullptr) {
      trackers_.emplace_back(std::make_shared<RasTracker>(ble_bd_addr.bda, address));
    } else if (tracker->is_connected_) {
      log::info("Already connected");
      auto characteristic =
              tracker->FindCharacteristicByUuid(kRasRealTimeRangingDataCharacteristic);
      uint16_t real_time_att_handle =
              characteristic == nullptr ? kInvalidGattHandle : characteristic->value_handle;
      // Check if the Real-Time ranging unsubscribed due to timeout
      if (characteristic != nullptr && tracker->ranging_type_ == RANGING_TYPE_NONE) {
        tracker->ranging_type_ = REAL_TIME;
        SubscribeCharacteristic(tracker, kRasRealTimeRangingDataCharacteristic);
        SetTimeOutAlarm(tracker, kFirstSegmentRangingDataTimeoutMs, TimeoutType::FIRST_SEGMENT);
      }
      callbacks_->OnConnected(address, real_time_att_handle,
                              tracker->vendor_specific_characteristics_, tracker->conn_interval_);
      return;
    }
    BTA_GATTC_Open(gatt_if_, ble_bd_addr.bda, BTM_BLE_DIRECT_CONNECTION, true);
  }

  void SendVendorSpecificReply(
          const RawAddress& address,
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_data) {
    tBLE_BD_ADDR ble_bd_addr;
    ResolveAddress(ble_bd_addr, address);
    log::info("address {}, resolve {}", address, ble_bd_addr.bda);
    auto tracker = FindTrackerByAddress(ble_bd_addr.bda);

    for (auto& vendor_specific_characteristic : vendor_specific_data) {
      auto characteristic =
              tracker->FindCharacteristicByUuid(vendor_specific_characteristic.characteristicUuid_);
      if (characteristic == nullptr) {
        log::warn("Can't find characteristic uuid {}",
                  vendor_specific_characteristic.characteristicUuid_);
        return;
      }
      log::debug("write to remote, uuid {}, len {}",
                 vendor_specific_characteristic.characteristicUuid_,
                 vendor_specific_characteristic.value_.size());
      BTA_GATTC_WriteCharValue(tracker->conn_id_, characteristic->value_handle, GATT_WRITE_NO_RSP,
                               vendor_specific_characteristic.value_, GATT_AUTH_REQ_NO_MITM,
                               GattWriteCallback, &gatt_write_callback_data_);
    }
  }

  void GattcCallback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
    log::debug("event: {}", gatt_client_event_text(event));
    switch (event) {
      case BTA_GATTC_OPEN_EVT: {
        OnGattConnected(p_data->open);
      } break;
      case BTA_GATTC_CLOSE_EVT: {
        OnGattDisconnected(p_data->close);
        break;
      }
      case BTA_GATTC_SEARCH_CMPL_EVT: {
        OnGattServiceSearchComplete(p_data->search_cmpl);
      } break;
      case BTA_GATTC_CFG_MTU_EVT: {
        OnGattConfigMtu(p_data->cfg_mtu);
      } break;
      case BTA_GATTC_NOTIF_EVT: {
        OnGattNotification(p_data->notify);
      } break;
      case BTA_GATTC_CONN_UPDATE_EVT: {
        OnConnUpdated(p_data->conn_update);
      } break;
      default:
        log::warn("Unhandled event: {}", gatt_client_event_text(event));
    }
  }

  void OnConnUpdated(const tBTA_GATTC_CONN_UPDATE& evt) const {
    auto tracker = FindTrackerByHandle(evt.conn_id);
    if (tracker == nullptr) {
      log::debug("no ongoing measurement, skip");
      return;
    }
    if (tracker->conn_interval_ != evt.interval) {
      tracker->conn_interval_ = evt.interval;
      log::info("conn interval is updated as {}", evt.interval);
      callbacks_->OnConnIntervalUpdated(tracker->address_for_cs_, tracker->conn_interval_);
    } else {
      log::debug("conn interval was not updated");
    }
  }

  void OnGattConnected(const tBTA_GATTC_OPEN& evt) {
    log::info("{}, conn_id=0x{:04x}, transport:{}, status:{}", evt.remote_bda, evt.conn_id,
              bt_transport_text(evt.transport), gatt_status_text(evt.status));

    if (evt.transport != BT_TRANSPORT_LE) {
      log::warn("Only LE connection is allowed (transport {})", bt_transport_text(evt.transport));
      BTA_GATTC_Close(evt.conn_id);
      return;
    }

    auto tracker = FindTrackerByAddress(evt.remote_bda);
    if (tracker == nullptr) {
      log::warn("Skipping unknown device, address: {}", evt.remote_bda);
      BTA_GATTC_Close(evt.conn_id);
      return;
    }

    if (evt.status != GATT_SUCCESS) {
      log::error("Failed to connect to server device {}", evt.remote_bda);
      callbacks_->OnDisconnected(tracker->address_for_cs_,
                                 RasDisconnectReason::SERVER_NOT_AVAILABLE);
      return;
    }
    tracker->conn_id_ = evt.conn_id;
    tracker->is_connected_ = true;
    tracker->conn_interval_ =
            bluetooth::stack::l2cap::get_interface().L2CA_GetBleConnInterval(tracker->address_);
    log::debug("The initial conn interval {}", tracker->conn_interval_);
    log::info("Search service");
    BTA_GATTC_ServiceSearchRequest(tracker->conn_id_, kRangingService);
  }

  void OnGattDisconnected(const tBTA_GATTC_CLOSE& evt) {
    log::info("{}, conn_id=0x{:04x}, status:{}, reason:{}", evt.remote_bda, evt.conn_id,
              gatt_status_text(evt.status), gatt_disconnection_reason_text(evt.reason));

    auto tracker = FindTrackerByAddress(evt.remote_bda);
    if (tracker == nullptr) {
      log::warn("Skipping unknown device, address: {}", evt.remote_bda);
      BTA_GATTC_Close(evt.conn_id);
      return;
    }
    callbacks_->OnDisconnected(tracker->address_for_cs_, RasDisconnectReason::GATT_DISCONNECT);
    trackers_.remove(tracker);
  }

  void OnGattServiceSearchComplete(const tBTA_GATTC_SEARCH_CMPL& evt) {
    auto tracker = FindTrackerByHandle(evt.conn_id);
    if (tracker == nullptr) {
      log::warn("Can't find tracker for conn_id:{}", evt.conn_id);
      return;
    }

    // Get Ranging Service
    bool service_found = false;
    const std::list<gatt::Service>* all_services = BTA_GATTC_GetServices(evt.conn_id);
    for (const auto& service : *all_services) {
      if (service.uuid == kRangingService) {
        tracker->service_ = &service;
        service_found = true;
        break;
      }
    }
    // config mtu anyway, if it had been configured by others, it can get the current mtu.
    log::info("config the MTU size as RAP minimum value {}", kMinimumRasMtu);
    BTA_GATTC_ConfigureMTU(evt.conn_id, kMinimumRasMtu);

    if (tracker->service_search_complete_) {
      log::info("Service search already completed, ignore");
      return;
    } else if (!service_found) {
      log::error("Can't find Ranging Service in the services list");
      callbacks_->OnDisconnected(tracker->address_for_cs_,
                                 RasDisconnectReason::SERVER_NOT_AVAILABLE);
      return;
    } else {
      log::info("Found Ranging Service");
      tracker->service_search_complete_ = true;
      ListCharacteristic(tracker);
    }

    if (UseCachedData(tracker)) {
      log::info("Use cached data for Ras features and vendor specific characteristic");
      if (!SubscribeCharacteristic(tracker, kRasControlPointCharacteristic)) {
        callbacks_->OnDisconnected(tracker->address_for_cs_,
                                   RasDisconnectReason::SERVER_NOT_AVAILABLE);
      }
      AllCharacteristicsReadComplete(tracker);
    } else {
      // Read Vendor Specific Uuid
      for (auto& vendor_specific_characteristic : tracker->vendor_specific_characteristics_) {
        log::debug("Read vendor specific characteristic uuid {}",
                   vendor_specific_characteristic.characteristicUuid_);
        auto characteristic = tracker->FindCharacteristicByUuid(
                vendor_specific_characteristic.characteristicUuid_);
        BTA_GATTC_ReadCharacteristic(
                tracker->conn_id_, characteristic->value_handle, GATT_AUTH_REQ_NO_MITM,
                [](tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                   uint8_t* value, void* data) {
                  instance->OnReadCharacteristicCallback(conn_id, status, handle, len, value, data);
                },
                nullptr);
      }

      // Read Ras Features
      log::info("Read Ras Features");
      auto characteristic = tracker->FindCharacteristicByUuid(kRasFeaturesCharacteristic);
      if (characteristic == nullptr) {
        log::error("Can not find Characteristic for Ras Features");
        callbacks_->OnDisconnected(tracker->address_for_cs_,
                                   RasDisconnectReason::SERVER_NOT_AVAILABLE);
        return;
      }
      BTA_GATTC_ReadCharacteristic(
              tracker->conn_id_, characteristic->value_handle, GATT_AUTH_REQ_NO_MITM,
              [](uint16_t conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                 uint8_t* value, void* data) {
                instance->OnReadCharacteristicCallback(conn_id, status, handle, len, value, data);
              },
              &gatt_read_callback_data_);

      if (!SubscribeCharacteristic(tracker, kRasControlPointCharacteristic)) {
        callbacks_->OnDisconnected(tracker->address_for_cs_, RasDisconnectReason::FATAL_ERROR);
      }
    }
  }

  void OnGattConfigMtu(const tBTA_GATTC_CFG_MTU& evt) {
    if (evt.status != GATT_SUCCESS) {
      log::warn("Failed to config the MTU size:{}", evt.mtu);
      return;
    }
    // the MTU is always 517 since android 14
    log::info("conn_id=0x{:04x}, status:{}, mtu:{}", evt.conn_id, evt.status, evt.mtu);
    auto tracker = FindTrackerByHandle(evt.conn_id);
    if (tracker != nullptr) {
      tracker->mtu = evt.mtu;
      callbacks_->OnMtuChangedFromClient(tracker->address_for_cs_, evt.mtu);
    }
  }

  bool UseCachedData(std::shared_ptr<RasTracker> tracker) {
    auto cached_data = cached_data_.find(tracker->address_);
    if (cached_data == cached_data_.end()) {
      return false;
    }

    // Check if everything is cached
    auto cached_vendor_specific_data = cached_data->second.vendor_specific_data_;
    for (auto& vendor_specific_characteristic : tracker->vendor_specific_characteristics_) {
      auto uuid = vendor_specific_characteristic.characteristicUuid_;
      if (cached_vendor_specific_data.find(uuid) != cached_vendor_specific_data.end()) {
        vendor_specific_characteristic.value_ = cached_vendor_specific_data[uuid];
      } else {
        return false;
      }
    }

    // Update remote supported features
    tracker->remote_supported_features_ = cached_data->second.remote_supported_features_;
    return true;
  }

  void OnGattNotification(const tBTA_GATTC_NOTIFY& evt) {
    auto tracker = FindTrackerByHandle(evt.conn_id);
    if (tracker == nullptr) {
      log::warn("Can't find tracker for conn_id:{}", evt.conn_id);
      return;
    }
    auto characteristic = tracker->FindCharacteristicByHandle(evt.handle);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic for handle:{}", evt.handle);
      return;
    }

    uint16_t uuid_16bit = characteristic->uuid.As16Bit();
    log::debug("Handle uuid 0x{:04x}, {}, size {}", uuid_16bit, getUuidName(characteristic->uuid),
               evt.len);

    switch (uuid_16bit) {
      case kRasRealTimeRangingDataCharacteristic16bit:
      case kRasOnDemandDataCharacteristic16bit: {
        OnRemoteData(evt, tracker);
        break;
      }
      case kRasControlPointCharacteristic16bit: {
        OnControlPointEvent(evt, tracker);
      } break;
      case kRasRangingDataReadyCharacteristic16bit: {
        OnRangingDataReady(evt, tracker);
      } break;
      default:
        log::warn("Unexpected UUID");
    }
  }

  void OnRemoteData(const tBTA_GATTC_NOTIFY& evt, std::shared_ptr<RasTracker> tracker) {
    std::vector<uint8_t> data;
    data.resize(evt.len);
    std::copy(evt.value, evt.value + evt.len, data.begin());
    bool is_last = (data[0] >> 1 & 0x01);
    alarm_cancel(tracker->ranging_data_timeout_timer_);
    if (!is_last) {
      SetTimeOutAlarm(tracker, kFollowingSegmentTimeoutMs, FOLLOWING_SEGMENT);
    }
    callbacks_->OnRemoteData(tracker->address_for_cs_, data);
  }

  void OnControlPointEvent(const tBTA_GATTC_NOTIFY& evt, std::shared_ptr<RasTracker> tracker) {
    switch (evt.value[0]) {
      case (uint8_t)EventCode::COMPLETE_RANGING_DATA_RESPONSE: {
        uint16_t ranging_counter = evt.value[1];
        ranging_counter |= (evt.value[2] << 8);
        log::debug("Received complete ranging data response, ranging_counter: {}", ranging_counter);
        AckRangingData(ranging_counter, tracker);
      } break;
      case (uint8_t)EventCode::RESPONSE_CODE: {
        tracker->handling_on_demand_data_ = false;
        log::debug("Received response code 0x{:02x}", evt.value[1]);
      } break;
      default:
        log::warn("Unexpected event code 0x{:02x}", evt.value[0]);
    }
  }

  void OnRangingDataReady(const tBTA_GATTC_NOTIFY& evt, std::shared_ptr<RasTracker> tracker) {
    if (evt.len != kRingingCounterSize) {
      log::error("Invalid len for ranging data ready");
      return;
    }
    uint16_t ranging_counter = evt.value[0];
    ranging_counter |= (evt.value[1] << 8);
    log::debug("ranging_counter: {}", ranging_counter);

    // Send get ranging data command
    tracker->latest_ranging_counter_ = ranging_counter;
    if (tracker->timeout_type_ == RANGING_DATA_READY) {
      alarm_cancel(tracker->ranging_data_timeout_timer_);
    }
    GetRangingData(ranging_counter, tracker);
  }

  void GetRangingData(uint16_t ranging_counter, std::shared_ptr<RasTracker> tracker) {
    log::debug("ranging_counter:{}", ranging_counter);
    if (tracker->handling_on_demand_data_) {
      log::warn("Handling other procedure, skip");
      return;
    }

    auto characteristic = tracker->FindCharacteristicByUuid(kRasControlPointCharacteristic);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic for RAS-CP");
      return;
    }

    tracker->handling_on_demand_data_ = true;
    std::vector<uint8_t> value(3);
    value[0] = (uint8_t)Opcode::GET_RANGING_DATA;
    value[1] = (uint8_t)(ranging_counter & 0xFF);
    value[2] = (uint8_t)((ranging_counter >> 8) & 0xFF);
    BTA_GATTC_WriteCharValue(tracker->conn_id_, characteristic->value_handle, GATT_WRITE_NO_RSP,
                             value, GATT_AUTH_REQ_NO_MITM, GattWriteCallback, nullptr);
    SetTimeOutAlarm(tracker, kFirstSegmentRangingDataTimeoutMs, FIRST_SEGMENT);
  }

  void AckRangingData(uint16_t ranging_counter, std::shared_ptr<RasTracker> tracker) {
    log::debug("ranging_counter:{}", ranging_counter);
    auto characteristic = tracker->FindCharacteristicByUuid(kRasControlPointCharacteristic);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic for RAS-CP");
      return;
    }
    tracker->handling_on_demand_data_ = false;
    std::vector<uint8_t> value(3);
    value[0] = (uint8_t)Opcode::ACK_RANGING_DATA;
    value[1] = (uint8_t)(ranging_counter & 0xFF);
    value[2] = (uint8_t)((ranging_counter >> 8) & 0xFF);
    BTA_GATTC_WriteCharValue(tracker->conn_id_, characteristic->value_handle, GATT_WRITE_NO_RSP,
                             value, GATT_AUTH_REQ_NO_MITM, GattWriteCallback, nullptr);
    if (ranging_counter != tracker->latest_ranging_counter_) {
      GetRangingData(tracker->latest_ranging_counter_, tracker);
    }
  }

  void AbortOperation(std::shared_ptr<RasTracker> tracker) {
    log::debug("address {}", tracker->address_for_cs_);
    auto characteristic = tracker->FindCharacteristicByUuid(kRasControlPointCharacteristic);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic for RAS-CP");
      return;
    }
    tracker->handling_on_demand_data_ = false;
    std::vector<uint8_t> value{static_cast<uint8_t>(Opcode::ABORT_OPERATION)};
    BTA_GATTC_WriteCharValue(tracker->conn_id_, characteristic->value_handle, GATT_WRITE_NO_RSP,
                             value, GATT_AUTH_REQ_NO_MITM, GattWriteCallback, nullptr);
  }

  void GattWriteCallbackForVendorSpecificData(tCONN_ID conn_id, tGATT_STATUS status,
                                              uint16_t handle, const uint8_t* /*value*/,
                                              GattWriteCallbackData* data) {
    if (data != nullptr) {
      GattWriteCallbackData* structPtr = static_cast<GattWriteCallbackData*>(data);
      if (structPtr->type_ == CallbackDataType::VENDOR_SPECIFIC_REPLY) {
        log::info("Write vendor specific reply complete");
        auto tracker = FindTrackerByHandle(conn_id);
        tracker->write_reply_counter_++;
        if (status == GATT_SUCCESS) {
          tracker->write_reply_success_counter_++;
        } else {
          log::error(
                  "Fail to write vendor specific reply conn_id {}, status {}, "
                  "handle {}",
                  conn_id, gatt_status_text(status), handle);
        }
        // All reply complete
        if (tracker->write_reply_counter_ == tracker->vendor_specific_characteristics_.size()) {
          log::info(
                  "All vendor specific reply write complete, size {} "
                  "successCounter {}",
                  tracker->vendor_specific_characteristics_.size(),
                  tracker->write_reply_success_counter_);
          bool success = tracker->write_reply_success_counter_ ==
                         tracker->vendor_specific_characteristics_.size();
          tracker->write_reply_counter_ = 0;
          tracker->write_reply_success_counter_ = 0;
          callbacks_->OnWriteVendorSpecificReplyComplete(tracker->address_for_cs_, success);
        }
        return;
      }
    }
  }

  void GattWriteCallback(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                         const uint8_t* /*value*/) {
    if (status != GATT_SUCCESS) {
      log::error("Fail to write conn_id {}, status {}, handle {}", conn_id,
                 gatt_status_text(status), handle);
      auto tracker = FindTrackerByHandle(conn_id);
      if (tracker == nullptr) {
        log::warn("Can't find tracker for conn_id:{}", conn_id);
        return;
      }
      auto characteristic = tracker->FindCharacteristicByHandle(handle);
      if (characteristic == nullptr) {
        log::warn("Can't find characteristic for handle:{}", handle);
        return;
      }

      if (characteristic->uuid == kRasControlPointCharacteristic) {
        log::error("Write RAS-CP command fail");
        tracker->handling_on_demand_data_ = false;
      }
      return;
    }
  }

  static void GattWriteCallback(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                uint16_t /*len*/, const uint8_t* value, void* data) {
    if (instance != nullptr) {
      if (data != nullptr) {
        GattWriteCallbackData* structPtr = static_cast<GattWriteCallbackData*>(data);
        if (structPtr->type_ == CallbackDataType::VENDOR_SPECIFIC_REPLY) {
          instance->GattWriteCallbackForVendorSpecificData(conn_id, status, handle, value,
                                                           structPtr);
          return;
        }
      }
      instance->GattWriteCallback(conn_id, status, handle, value);
    }
  }

  bool SubscribeCharacteristic(std::shared_ptr<RasTracker> tracker, const Uuid uuid) {
    auto characteristic = tracker->FindCharacteristicByUuid(uuid);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic 0x{:04x}", uuid.As16Bit());
      return false;
    }
    uint16_t ccc_handle = FindCccHandle(characteristic);
    if (ccc_handle == GAP_INVALID_HANDLE) {
      log::warn("Can't find Client Characteristic Configuration descriptor");
      return false;
    }

    tGATT_STATUS register_status = BTA_GATTC_RegisterForNotifications(gatt_if_, tracker->address_,
                                                                      characteristic->value_handle);
    if (register_status != GATT_SUCCESS) {
      log::error("Fail to register, {}", gatt_status_text(register_status));
      return false;
    }

    std::vector<uint8_t> value(2);
    uint8_t* value_ptr = value.data();
    // Register notify is supported
    if (characteristic->properties & GATT_CHAR_PROP_BIT_NOTIFY) {
      UINT16_TO_STREAM(value_ptr, GATT_CHAR_CLIENT_CONFIG_NOTIFICATION);
    } else {
      UINT16_TO_STREAM(value_ptr, GATT_CHAR_CLIENT_CONFIG_INDICTION);
    }
    BTA_GATTC_WriteCharDescr(
            tracker->conn_id_, ccc_handle, value, GATT_AUTH_REQ_NONE,
            [](tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
               const uint8_t* value, void* data) {
              if (instance) {
                instance->OnDescriptorWrite(conn_id, status, handle, len, value, data);
              }
            },
            nullptr);
    return true;
  }

  void UnsubscribeCharacteristic(std::shared_ptr<RasTracker> tracker, const Uuid uuid) {
    auto characteristic = tracker->FindCharacteristicByUuid(uuid);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic 0x{:04x}", uuid.As16Bit());
      return;
    }
    uint16_t ccc_handle = FindCccHandle(characteristic);
    if (ccc_handle == GAP_INVALID_HANDLE) {
      log::warn("Can't find Client Characteristic Configuration descriptor");
      return;
    }

    tGATT_STATUS register_status = BTA_GATTC_DeregisterForNotifications(
            gatt_if_, tracker->address_, characteristic->value_handle);
    if (register_status != GATT_SUCCESS) {
      log::error("Fail to deregister, {}", gatt_status_text(register_status));
      return;
    }
    log::info("UnsubscribeCharacteristic 0x{:04x}", uuid.As16Bit());

    std::vector<uint8_t> ccc_none(2, 0);
    BTA_GATTC_WriteCharDescr(
            tracker->conn_id_, ccc_handle, ccc_none, GATT_AUTH_REQ_NONE,
            [](tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
               const uint8_t* value, void* data) {
              if (instance) {
                instance->OnDescriptorWrite(conn_id, status, handle, len, value, data);
              }
            },
            nullptr);
  }

  void OnDescriptorWrite(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t /*len*/,
                         const uint8_t* /*value*/, void* /*data*/) {
    log::info("conn_id:{}, handle:{}, status:{}", conn_id, handle, gatt_status_text(status));
  }

  void ListCharacteristic(std::shared_ptr<RasTracker> tracker) {
    tracker->vendor_specific_characteristics_.clear();
    for (auto& characteristic : tracker->service_->characteristics) {
      bool vendor_specific = !IsRangingServiceCharacteristic(characteristic.uuid);
      log::info(
              "{}Characteristic uuid:0x{:04x}, handle:0x{:04x}, "
              "properties:0x{:02x}, "
              "{}",
              vendor_specific ? "Vendor Specific " : "", characteristic.uuid.As16Bit(),
              characteristic.value_handle, characteristic.properties,
              getUuidName(characteristic.uuid));
      if (vendor_specific) {
        VendorSpecificCharacteristic vendor_specific_characteristic;
        vendor_specific_characteristic.characteristicUuid_ = characteristic.uuid;
        tracker->vendor_specific_characteristics_.emplace_back(vendor_specific_characteristic);
      }
      for (auto& descriptor : characteristic.descriptors) {
        log::info("\tDescriptor uuid:0x{:04x}, handle:0x{:04x}, {}", descriptor.uuid.As16Bit(),
                  descriptor.handle, getUuidName(descriptor.uuid));
      }
    }
  }

  void ResolveAddress(tBLE_BD_ADDR& ble_bd_addr, const RawAddress& address) {
    ble_bd_addr.bda = address;
    ble_bd_addr.type = BLE_ADDR_RANDOM;
    maybe_resolve_address(&ble_bd_addr.bda, &ble_bd_addr.type);
  }

  void OnReadCharacteristicCallback(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                    uint16_t len, uint8_t* value, void* data) {
    log::info("conn_id: {}, handle: {}, len: {}", conn_id, handle, len);
    if (status != GATT_SUCCESS) {
      log::error("Fail with status {}", gatt_status_text(status));
      return;
    }
    auto tracker = FindTrackerByHandle(conn_id);
    if (tracker == nullptr) {
      log::warn("Can't find tracker for conn_id:{}", conn_id);
      return;
    }
    auto characteristic = tracker->FindCharacteristicByHandle(handle);
    if (characteristic == nullptr) {
      log::warn("Can't find characteristic for handle:{}", handle);
      return;
    }

    auto vendor_specific_characteristic =
            tracker->GetVendorSpecificCharacteristic(characteristic->uuid);
    if (vendor_specific_characteristic != nullptr) {
      log::info("Update vendor specific data, uuid: {}",
                vendor_specific_characteristic->characteristicUuid_);
      vendor_specific_characteristic->value_.clear();
      vendor_specific_characteristic->value_.reserve(len);
      vendor_specific_characteristic->value_.assign(value, value + len);
      return;
    }

    uint16_t uuid_16bit = characteristic->uuid.As16Bit();
    log::info("Handle uuid 0x{:04x}, {}", uuid_16bit, getUuidName(characteristic->uuid));

    switch (uuid_16bit) {
      case kRasFeaturesCharacteristic16bit: {
        if (len != kFeatureSize) {
          log::error("Invalid len for Ras features");
          return;
        }
        STREAM_TO_UINT32(tracker->remote_supported_features_, value);
        log::info("Remote supported features : {}",
                  GetFeaturesString(tracker->remote_supported_features_));
      } break;
      default:
        log::warn("Unexpected UUID");
    }

    // Check is last read reply or not
    GattReadCallbackData* cb_data = static_cast<GattReadCallbackData*>(data);
    if (cb_data != nullptr) {
      StoreCachedData(tracker);
      AllCharacteristicsReadComplete(tracker);
    }
  }

  void AllCharacteristicsReadComplete(std::shared_ptr<RasTracker> tracker) {
    if (tracker->remote_supported_features_ & feature::kRealTimeRangingData) {
      log::info("Subscribe Real-time Ranging Data");
      tracker->ranging_type_ = REAL_TIME;
      if (!SubscribeCharacteristic(tracker, kRasRealTimeRangingDataCharacteristic)) {
        callbacks_->OnDisconnected(tracker->address_for_cs_,
                                   RasDisconnectReason::SERVER_NOT_AVAILABLE);
        return;
      }
      SetTimeOutAlarm(tracker, kFirstSegmentRangingDataTimeoutMs, TimeoutType::FIRST_SEGMENT);
    } else {
      log::info("Subscribe On-demand Ranging Data");
      tracker->ranging_type_ = ON_DEMAND;
      if (!SubscribeCharacteristic(tracker, kRasOnDemandDataCharacteristic) ||
          !SubscribeCharacteristic(tracker, kRasRangingDataReadyCharacteristic) ||
          !SubscribeCharacteristic(tracker, kRasRangingDataOverWrittenCharacteristic)) {
        callbacks_->OnDisconnected(tracker->address_for_cs_,
                                   RasDisconnectReason::SERVER_NOT_AVAILABLE);
        return;
      }
      SetTimeOutAlarm(tracker, kRangingDataReadyTimeoutMs, TimeoutType::RANGING_DATA_READY);
    }
    auto characteristic = tracker->FindCharacteristicByUuid(kRasRealTimeRangingDataCharacteristic);
    uint16_t real_time_att_handle =
            characteristic == nullptr ? kInvalidGattHandle : characteristic->value_handle;
    callbacks_->OnConnected(tracker->address_for_cs_, real_time_att_handle,
                            tracker->vendor_specific_characteristics_, tracker->conn_interval_);
  }

  void StoreCachedData(std::shared_ptr<RasTracker> tracker) {
    auto address = tracker->address_;
    auto cached_data = cached_data_.find(address);
    if (cached_data == cached_data_.end()) {
      uint8_t next_id = cached_data_.size();
      // Remove oldest cached data
      if (cached_data_.size() >= kCachedDataSize) {
        auto oldest_cached_data = std::min_element(
                cached_data_.begin(), cached_data_.end(),
                [](const auto& a, const auto& b) { return a.second.id_ < b.second.id_; });
        next_id = oldest_cached_data->second.id_ + kCachedDataSize;
        cached_data_.erase(oldest_cached_data);
      }

      // Create new cached data
      log::debug("Create new cached data {}", address);
      cached_data_[address].id_ = next_id;
      cached_data_[address].remote_supported_features_ = tracker->remote_supported_features_;
      for (auto data : tracker->vendor_specific_characteristics_) {
        cached_data_[address].vendor_specific_data_[data.characteristicUuid_] = data.value_;
      }

      // Check if the id will outside the valid range for the next data entry
      if (cached_data_[address].id_ == 255) {
        for (auto& [key, value] : cached_data_) {
          value.id_ %= (256 - kCachedDataSize);
        }
      }
    }
  }

  std::string GetFeaturesString(uint32_t value) {
    std::stringstream ss;
    ss << value;
    if (value == 0) {
      ss << "|No feature supported";
    } else {
      if ((value & kRealTimeRangingData) != 0) {
        ss << "|Real-time Ranging Data";
      }
      if ((value & kRetrieveLostRangingDataSegments) != 0) {
        ss << "|Retrieve Lost Ranging Data Segments";
      }
      if ((value & kAbortOperation) != 0) {
        ss << "|Abort Operation";
      }
      if ((value & kFilterRangingData) != 0) {
        ss << "|Filter Ranging Data";
      }
    }
    return ss.str();
  }

  uint16_t FindCccHandle(const gatt::Characteristic* characteristic) {
    for (auto descriptor : characteristic->descriptors) {
      if (descriptor.uuid == kClientCharacteristicConfiguration) {
        return descriptor.handle;
      }
    }
    return GAP_INVALID_HANDLE;
  }

  std::shared_ptr<RasTracker> FindTrackerByHandle(tCONN_ID conn_id) const {
    for (auto tracker : trackers_) {
      if (tracker->conn_id_ == conn_id) {
        return tracker;
      }
    }
    return nullptr;
  }

  std::shared_ptr<RasTracker> FindTrackerByAddress(const RawAddress& address) const {
    for (auto tracker : trackers_) {
      if (tracker->address_ == address) {
        return tracker;
      }
    }
    return nullptr;
  }

  void SetTimeOutAlarm(std::shared_ptr<RasTracker> tracker, uint16_t interval_ms,
                       TimeoutType timeout_type) {
    log::debug("ranging_type_: {}, timeout_type: {}", (uint8_t)tracker->ranging_type_,
               (uint8_t)timeout_type);
    tracker->timeout_type_ = timeout_type;
    tracker->ranging_data_timeout_timer_ = alarm_new("Ranging Data Timeout");
    alarm_set_on_mloop(
            tracker->ranging_data_timeout_timer_, interval_ms,
            [](void* data) {
              if (instance) {
                instance->OnRangingDataTimeout(reinterpret_cast<RawAddress*>(data));
              }
            },
            &tracker->address_);
  }

  void OnRangingDataTimeout(RawAddress* address) {
    auto tracker = FindTrackerByAddress(*address);
    if (tracker == nullptr) {
      log::warn("Skipping unknown device, address: {}", *address);
      return;
    }

    switch (tracker->timeout_type_) {
      case FIRST_SEGMENT:
      case FOLLOWING_SEGMENT: {
        auto timeout_type_text =
                tracker->timeout_type_ == FIRST_SEGMENT ? "first segment" : "following segment";
        if (tracker->ranging_type_ == REAL_TIME) {
          log::error("Timeout to receive {} of Real-time ranging data", timeout_type_text);
          UnsubscribeCharacteristic(tracker, kRasRealTimeRangingDataCharacteristic);
          tracker->ranging_type_ = RANGING_TYPE_NONE;
        } else {
          log::error("Timeout to receive {} of On-Demand ranging data", timeout_type_text);
          AbortOperation(tracker);
        }
      } break;
      case RANGING_DATA_READY: {
        log::error("Timeout to receive ranging data ready");
      } break;
      default:
        log::error("Unexpected timeout type {}", (uint16_t)tracker->timeout_type_);
        return;
    }
    callbacks_->OnRemoteDataTimeout(tracker->address_for_cs_);
  }

private:
  uint16_t gatt_if_;
  std::list<std::shared_ptr<RasTracker>> trackers_;
  bluetooth::ras::RasClientCallbacks* callbacks_;
  std::unordered_map<RawAddress, CachedRasData> cached_data_;
  GattReadCallbackData gatt_read_callback_data_{true};
  GattWriteCallbackData gatt_write_callback_data_{CallbackDataType::VENDOR_SPECIFIC_REPLY};
};

}  // namespace

bluetooth::ras::RasClient* bluetooth::ras::GetRasClient() {
  if (instance == nullptr) {
    instance = new RasClientImpl();
  }
  return instance;
}
