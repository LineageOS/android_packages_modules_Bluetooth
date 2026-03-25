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
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/uuid.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "gd/hci/controller.h"
#include "gd/os/rand.h"
#include "hardware/bt_common_types.h"
#include "main/shim/entry.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/gatt_api.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;
using bluetooth::ras::VendorSpecificCharacteristic;
using bluetooth::stack::tGATT_REQ_CBACK;

namespace {

class RasServerImpl;
RasServerImpl* instance = nullptr;

static constexpr uint32_t kSupportedFeatures = feature::kRealTimeRangingData;
static constexpr uint16_t kBufferSize = 3;

class RasServerImpl : public bluetooth::ras::RasServer {
public:
  struct RasCharacteristic {
    bluetooth::Uuid uuid_;
    uint16_t attribute_handle_;
    uint16_t attribute_handle_ccc_;
  };

  RasServerImpl() { instance = this; }

  // Struct to save data of specific ranging counter
  struct DataBuffer {
    DataBuffer(uint16_t ranging_counter) : ranging_counter_(ranging_counter), segments_() {}
    uint16_t ranging_counter_;
    std::vector<std::vector<uint8_t>> segments_;
  };

  struct PendingWriteResponse {
    tCONN_ID conn_id_;
    uint32_t trans_id_;
    uint16_t write_req_handle_;
  };

  struct ClientTracker {
    tCONN_ID conn_id_;
    std::unordered_map<Uuid, uint16_t> ccc_values_;
    std::vector<DataBuffer> buffers_;
    bool handling_control_point_command_ = false;
    uint8_t vendor_specific_reply_counter_ = 0;
    PendingWriteResponse pending_write_response_;
    uint16_t last_ready_procedure_ = 0;
    uint16_t last_overwritten_procedure_ = 0;
    uint16_t mtu = kDefaultGattMtu;
  };

  void Initialize() override {
    do_in_main_thread(base::BindOnce(&RasServerImpl::do_initialize, base::Unretained(this)));
  }

  static void OnGattConnStatic(tGATT_IF /*server_if*/, const RawAddress& remote_bda,
                               tCONN_ID conn_id, bool connected, tGATT_DISCONN_REASON /*reason*/,
                               tBT_TRANSPORT transport) {
    if (instance) {
      if (connected) {
        instance->OnGattConnect(remote_bda, conn_id, transport);
      } else {
        instance->OnGattDisconnect(remote_bda, conn_id);
      }
    }
  }

  static void OnGattReadCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                             const RawAddress& remote_bda, uint16_t handle,
                                             uint16_t offset, bool is_long) {
    if (instance) {
      instance->OnReadCharacteristic(conn_id, trans_id, remote_bda, handle, offset, is_long);
    }
  }

  static void OnGattWriteCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                              const RawAddress& remote_bda, uint16_t handle,
                                              uint16_t offset, bool need_rsp, bool is_prep,
                                              uint8_t* value, uint16_t len) {
    if (instance) {
      instance->OnWriteCharacteristic(conn_id, trans_id, remote_bda, handle, offset, need_rsp,
                                      is_prep, value, len);
    }
  }

  static void OnGattReadDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                         const RawAddress& remote_bda, uint16_t handle,
                                         uint16_t offset, bool is_long) {
    if (instance) {
      instance->OnReadDescriptor(conn_id, trans_id, remote_bda, handle, offset, is_long);
    }
  }

  static void OnGattWriteDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                          const RawAddress& remote_bda, uint16_t handle,
                                          uint16_t offset, bool need_rsp, bool is_prep,
                                          uint8_t* value, uint16_t len) {
    if (instance) {
      instance->OnWriteDescriptor(conn_id, trans_id, remote_bda, handle, offset, need_rsp, is_prep,
                                  value, len);
    }
  }

  static void OnGattMtuChangedStatic(tCONN_ID conn_id, const RawAddress& remote_bda, uint16_t mtu) {
    if (instance) {
      instance->OnGattMtuChanged(conn_id, remote_bda, mtu);
    }
  }

  void do_initialize() {
    auto controller = bluetooth::shim::GetController();
    if (controller && !controller->SupportsBleChannelSounding()) {
      log::info("controller does not support channel sounding.");
      return;
    }
    Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
    app_uuid_ = uuid;
    log::info("Register server with uuid:{}", app_uuid_.ToString());

    static bluetooth::stack::tGATT_REQ_CBACK ras_p_req_cb = {
            .read_characteristic_cb = OnGattReadCharacteristicStatic,
            .read_descriptor_cb = OnGattReadDescriptorStatic,
            .write_characteristic_cb = OnGattWriteCharacteristicStatic,
            .write_descriptor_cb = OnGattWriteDescriptorStatic,
            .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
            .mtu_changed_cb = OnGattMtuChangedStatic,
            .conf_cb = tGATT_REQ_CBACK::do_nothing,
    };

    static const stack::tGATT_CBACK ras_ops = {
            .p_conn_cb = OnGattConnStatic,
            .p_req_cb = &ras_p_req_cb,
    };

    server_if_ = BTA_GATTS_AppRegister(app_uuid_, &ras_ops, false);
    log::info("server_if: {}", server_if_);

    if (server_if_ == stack::GATT_IF_INVALID) {
      log::warn("Register Server fail");
      return;
    }

    constexpr uint16_t key_mask = ((16 - 7) << 12);
    std::vector<btgatt_db_element_t> service = {
            // RAS service
            btgatt_db_element_t{.uuid = kRangingService, .type = BTGATT_DB_PRIMARY_SERVICE},
            // RAS Features
            btgatt_db_element_t{.uuid = kRasFeaturesCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ,
                                .permissions = GATT_PERM_READ_ENCRYPTED | key_mask},

            // Real-time Ranging Data (Optional)
            btgatt_db_element_t{
                    .uuid = kRasRealTimeRangingDataCharacteristic,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE,
                    .permissions = GATT_PERM_READ_ENCRYPTED | key_mask},
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask},

            // On-demand Ranging Data
            btgatt_db_element_t{
                    .uuid = kRasOnDemandDataCharacteristic,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE,
                    .permissions = GATT_PERM_READ_ENCRYPTED | key_mask},
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask},

            // RAS Control Point (RAS-CP)
            btgatt_db_element_t{
                    .uuid = kRasControlPointCharacteristic,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_INDICATE,
                    .permissions = GATT_PERM_WRITE_ENCRYPTED | key_mask},
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask},

            // Ranging Data Ready
            btgatt_db_element_t{.uuid = kRasRangingDataReadyCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY |
                                              GATT_CHAR_PROP_BIT_INDICATE,
                                .permissions = GATT_PERM_READ_ENCRYPTED | key_mask},
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask},

            // Ranging Data Overwritten
            btgatt_db_element_t{.uuid = kRasRangingDataOverWrittenCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY |
                                              GATT_CHAR_PROP_BIT_INDICATE,
                                .permissions = GATT_PERM_READ_ENCRYPTED | key_mask},
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask}};

    for (auto& vsc : vendor_specific_characteristics_) {
      service.push_back(btgatt_db_element_t{
              .uuid = vsc.characteristicUuid_,
              .type = BTGATT_DB_CHARACTERISTIC,
              .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE,
              .permissions = GATT_PERM_READ_ENCRYPTED | GATT_PERM_WRITE_ENCRYPTED | key_mask});
      log::info("Push vendor_specific_characteristics uuid {}", vsc.characteristicUuid_);
    }

    auto status = BTA_GATTS_AddService(server_if_, &service);
    log::info("status: {}, server_if: {}", gatt_status_text(status), server_if_);
    RasCharacteristic* current_characteristic;
    for (uint16_t i = 0; i < service.size(); i++) {
      uint16_t attribute_handle = service[i].attribute_handle;
      Uuid uuid = service[i].uuid;
      if (service[i].type == BTGATT_DB_CHARACTERISTIC) {
        log::info("Characteristic uuid: 0x{:04x}, handle:0x{:04x}, {}", uuid.As16Bit(),
                  attribute_handle, getUuidName(uuid));
        characteristics_[attribute_handle].attribute_handle_ = attribute_handle;
        characteristics_[attribute_handle].uuid_ = uuid;
        current_characteristic = &characteristics_[attribute_handle];
      } else if (service[i].type == BTGATT_DB_DESCRIPTOR) {
        log::info("\tDescriptor uuid: 0x{:04x}, handle: 0x{:04x}, {}", uuid.As16Bit(),
                  attribute_handle, getUuidName(uuid));
        if (service[i].uuid == kClientCharacteristicConfiguration) {
          current_characteristic->attribute_handle_ccc_ = attribute_handle;
        }
      }
    }
  }

  void RegisterCallbacks(bluetooth::ras::RasServerCallbacks* callbacks) { callbacks_ = callbacks; }

  void SetVendorSpecificCharacteristic(
          const std::vector<VendorSpecificCharacteristic>& vendor_specific_characteristics) {
    vendor_specific_characteristics_ = vendor_specific_characteristics;
  }

  void HandleVendorSpecificReplyComplete(RawAddress address, bool success) {
    log::info("address:{}, success:{}", address, success);
    tBLE_BD_ADDR ble_bd_addr;
    ResolveAddress(ble_bd_addr, address);
    if (trackers_.find(ble_bd_addr.bda) == trackers_.end()) {
      log::warn("Can't find tracker for address {}", address);
      return;
    }
    auto response = trackers_[ble_bd_addr.bda].pending_write_response_;
    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->attr_value.handle = response.write_req_handle_;
    GattStatus status = success ? GATT_SUCCESS : GATT_ERROR;
    BTA_GATTS_SendRsp(response.conn_id_, response.trans_id_, status, std::move(p_msg));
  }

  void PushProcedureData(RawAddress address, uint16_t procedure_counter, bool is_last,
                         std::vector<uint8_t> data) {
    log::debug("{}, counter:{}, is_last:{}, with size {}", address, procedure_counter, is_last,
               data.size());
    tBLE_BD_ADDR ble_bd_addr;
    ResolveAddress(ble_bd_addr, address);

    if (trackers_.find(ble_bd_addr.bda) == trackers_.end()) {
      log::warn("Can't find tracker for {}", ble_bd_addr.bda);
      return;
    }
    ClientTracker& tracker = trackers_[ble_bd_addr.bda];
    uint16_t ccc_real_time = tracker.ccc_values_[kRasRealTimeRangingDataCharacteristic];
    uint16_t ccc_data_ready = tracker.ccc_values_[kRasRangingDataReadyCharacteristic];
    uint16_t ccc_data_over_written = tracker.ccc_values_[kRasRangingDataOverWrittenCharacteristic];

    if (ccc_real_time != GATT_CLT_CONFIG_NONE) {
      bool use_notification = ccc_real_time & GATT_CLT_CONFIG_NOTIFICATION;
      uint16_t attr_id =
              GetCharacteristic(kRasRealTimeRangingDataCharacteristic)->attribute_handle_;
      log::debug("Send Real-time Ranging Data is_last {}", is_last);
      BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, data, !use_notification);
    }

    if (ccc_data_ready == GATT_CLT_CONFIG_NONE && ccc_data_over_written == GATT_CLT_CONFIG_NONE) {
      return;
    }
    std::lock_guard<std::mutex> lock(on_demand_ranging_mutex_);
    DataBuffer& data_buffer = InitDataBuffer(ble_bd_addr.bda, procedure_counter);
    data_buffer.segments_.push_back(data);
    tracker.last_ready_procedure_ = procedure_counter;

    // Send data ready
    if (is_last) {
      if (ccc_data_ready == GATT_CLT_CONFIG_NONE || ccc_real_time != GATT_CLT_CONFIG_NONE) {
        log::debug("Skip Ranging Data Ready");
      } else {
        bool need_confirm = ccc_data_ready & GATT_CLT_CONFIG_INDICATION;
        log::debug("Send data ready, ranging_counter {}, total fragment {}", procedure_counter,
                   data_buffer.segments_.size());
        uint16_t attr_id = GetCharacteristic(kRasRangingDataReadyCharacteristic)->attribute_handle_;
        std::vector<uint8_t> value(kRingingCounterSize);
        value[0] = (procedure_counter & 0xFF);
        value[1] = (procedure_counter >> 8) & 0xFF;
        BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, value, need_confirm);
      }
    }

    // Send data overwritten
    if (tracker.buffers_.size() > kBufferSize) {
      auto begin = tracker.buffers_.begin();
      tracker.last_overwritten_procedure_ = begin->ranging_counter_;
      if (ccc_data_over_written == GATT_CLT_CONFIG_NONE || ccc_real_time != GATT_CLT_CONFIG_NONE) {
        log::debug("Skip Ranging Data Over Written");
        tracker.buffers_.erase(begin);
        return;
      }
      bool need_confirm = ccc_data_over_written & GATT_CLT_CONFIG_INDICATION;
      log::debug("Send data over written, ranging_counter {}", begin->ranging_counter_);
      uint16_t attr_id =
              GetCharacteristic(kRasRangingDataOverWrittenCharacteristic)->attribute_handle_;
      std::vector<uint8_t> value(kRingingCounterSize);
      value[0] = (begin->ranging_counter_ & 0xFF);
      value[1] = (begin->ranging_counter_ >> 8) & 0xFF;
      BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, value, need_confirm);
      tracker.buffers_.erase(begin);
    }
  }

  void OnGattConnect(const RawAddress& remote_bda, tCONN_ID conn_id, tBT_TRANSPORT transport) {
    log::info("Address: {}, conn_id:{}", remote_bda, conn_id);
    if (transport == BT_TRANSPORT_BR_EDR) {
      log::warn("Skip BE/EDR connection");
      return;
    }

    if (trackers_.find(remote_bda) == trackers_.end()) {
      log::warn("Create new tracker");
    }
    trackers_[remote_bda].conn_id_ = conn_id;

    RawAddress identity_address = remote_bda;
    tBLE_ADDR_TYPE address_type = BLE_ADDR_PUBLIC_ID;
    btm_random_pseudo_to_identity_addr(&identity_address, &address_type);
    // TODO: optimize, remove this event, initialize the tracker within the GD on
    // demand.
    callbacks_->OnRasServerConnected(identity_address);
  }

  void OnGattMtuChanged(tCONN_ID /*conn_id*/, const RawAddress& remote_bda, uint16_t mtu) {
    log::info("mtu is changed as {}", mtu);
    auto it = trackers_.find(remote_bda);
    if (it != trackers_.end()) {
      it->second.mtu = mtu;

      RawAddress address = remote_bda;
      tBLE_ADDR_TYPE address_type = BLE_ADDR_PUBLIC_ID;
      btm_random_pseudo_to_identity_addr(&address, &address_type);
      callbacks_->OnMtuChangedFromServer(address, it->second.mtu);
    }
  }

  void OnGattDisconnect(const RawAddress& remote_bda, tCONN_ID conn_id) {
    log::info("Address: {}, conn_id:{}", remote_bda, conn_id);
    if (trackers_.find(remote_bda) != trackers_.end()) {
      NotifyRasServerDisconnected(remote_bda);
      trackers_.erase(remote_bda);
    }
  }

  void NotifyRasServerDisconnected(const RawAddress& remote_bda) {
    tBLE_BD_ADDR ble_identity_bd_addr;
    ble_identity_bd_addr.bda = remote_bda;
    ble_identity_bd_addr.type = BLE_ADDR_RANDOM;
    btm_random_pseudo_to_identity_addr(&ble_identity_bd_addr.bda, &ble_identity_bd_addr.type);

    callbacks_->OnRasServerDisconnected(ble_identity_bd_addr.bda);
  }

  void OnReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                            uint16_t handle, uint16_t /*offset*/, bool /*is_long*/) {
    log::info("read_req_handle: 0x{:04x},", handle);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->attr_value.handle = handle;
    if (characteristics_.find(handle) == characteristics_.end()) {
      log::error("Invalid handle 0x{:04x}", handle);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      return;
    }

    auto uuid = characteristics_[handle].uuid_;
    auto vendor_specific_characteristic = GetVendorSpecificCharacteristic(uuid);
    if (vendor_specific_characteristic != nullptr) {
      log::debug("Read vendor_specific_characteristic uuid {}", uuid);
      p_msg->attr_value.len = vendor_specific_characteristic->value_.size();
      std::copy(vendor_specific_characteristic->value_.begin(),
                vendor_specific_characteristic->value_.end(), p_msg->attr_value.value);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
      return;
    }
    log::info("Read uuid, {}", getUuidName(uuid));
    if (trackers_.find(remote_bda) == trackers_.end()) {
      log::warn("Can't find tracker for {}", remote_bda);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
      return;
    }
    ClientTracker* tracker = &trackers_[remote_bda];

    // Check Characteristic UUID
    switch (uuid.As16Bit()) {
      case kRasFeaturesCharacteristic16bit: {
        p_msg->attr_value.len = kFeatureSize;
        memcpy(p_msg->attr_value.value, &kSupportedFeatures, sizeof(uint32_t));
      } break;
      case kRasRangingDataReadyCharacteristic16bit: {
        p_msg->attr_value.len = kRingingCounterSize;
        p_msg->attr_value.value[0] = (tracker->last_ready_procedure_ & 0xFF);
        p_msg->attr_value.value[1] = (tracker->last_ready_procedure_ >> 8) & 0xFF;
      } break;
      case kRasRangingDataOverWrittenCharacteristic16bit: {
        p_msg->attr_value.len = kRingingCounterSize;
        p_msg->attr_value.value[0] = (tracker->last_overwritten_procedure_ & 0xFF);
        p_msg->attr_value.value[1] = (tracker->last_overwritten_procedure_ >> 8) & 0xFF;
      } break;
      default:
        log::warn("Unhandled uuid {}", uuid.ToString());
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
        return;
    }
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
  }

  void OnReadDescriptor(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                        uint16_t handle, uint16_t /*offset*/, bool /*is_long*/) {
    log::info("conn_id:{}, read_req_handle:0x{:04x}", conn_id, handle);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->attr_value.handle = handle;

    // Only Client Characteristic Configuration (CCC) descriptor is expected
    RasCharacteristic* characteristic = GetCharacteristicByCccHandle(handle);
    if (characteristic == nullptr) {
      log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", handle);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      return;
    }
    log::info("Read CCC for uuid, {}", getUuidName(characteristic->uuid_));
    uint16_t ccc_value = 0;
    if (trackers_.find(remote_bda) != trackers_.end()) {
      ccc_value = trackers_[remote_bda].ccc_values_[characteristic->uuid_];
    }

    p_msg->attr_value.len = kCccValueSize;
    memcpy(p_msg->attr_value.value, &ccc_value, sizeof(uint16_t));

    log::info("Send response for CCC value 0x{:04x}", ccc_value);
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
  }

  void OnWriteCharacteristic(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                             uint16_t handle, uint16_t /* offset */, bool need_rsp,
                             bool /* is_prep */, uint8_t* value, uint16_t len) {
    log::info("conn_id:{}, handle:0x{:04x}, need_rsp{}, len:{}", conn_id, handle, need_rsp, len);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->handle = handle;
    if (characteristics_.find(handle) == characteristics_.end()) {
      log::error("Invalid handle {}", handle);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      return;
    }

    auto uuid = characteristics_[handle].uuid_;
    auto vendor_specific_characteristic = GetVendorSpecificCharacteristic(uuid);
    if (vendor_specific_characteristic != nullptr) {
      WriteVendorSpecificCharacteristic(vendor_specific_characteristic, conn_id, trans_id,
                                        remote_bda, value, len, std::move(p_msg));
      return;
    }
    log::info("Write uuid, {}", getUuidName(uuid));

    // Check Characteristic UUID
    switch (uuid.As16Bit()) {
      case kRasControlPointCharacteristic16bit: {
        if (trackers_.find(remote_bda) == trackers_.end()) {
          log::warn("Can't find trackers for {}", remote_bda);
          BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
          return;
        }
        ClientTracker* tracker = &trackers_[remote_bda];
        if (need_rsp) {
          BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
        }
        HandleControlPoint(tracker, value, len);
      } break;
      default:
        log::warn("Unhandled uuid {}", uuid.ToString());
        BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
        return;
    }
  }

  void WriteVendorSpecificCharacteristic(
          VendorSpecificCharacteristic* vendor_specific_characteristic, tCONN_ID conn_id,
          uint32_t trans_id, const RawAddress& remote_bda, uint8_t* value, uint16_t len,
          std::unique_ptr<tGATTS_RSP> p_msg) {
    log::debug("uuid {}", vendor_specific_characteristic->characteristicUuid_);

    if (trackers_.find(remote_bda) == trackers_.end()) {
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      log::warn("Can't find tracker for remote_bda {}", remote_bda);
      return;
    }

    // Update reply value
    auto& tracker = trackers_[remote_bda];
    vendor_specific_characteristic->reply_value_.clear();
    vendor_specific_characteristic->reply_value_.reserve(len);
    vendor_specific_characteristic->reply_value_.assign(value, value + len);
    tracker.vendor_specific_reply_counter_++;

    if (tracker.vendor_specific_reply_counter_ == vendor_specific_characteristics_.size()) {
      log::info("All vendor specific characteristics written");
      tBLE_BD_ADDR ble_bd_addr;
      ble_bd_addr.bda = remote_bda;
      ble_bd_addr.type = BLE_ADDR_RANDOM;
      btm_random_pseudo_to_identity_addr(&ble_bd_addr.bda, &ble_bd_addr.type);
      tracker.vendor_specific_reply_counter_ = 0;
      tracker.pending_write_response_.conn_id_ = conn_id;
      tracker.pending_write_response_.trans_id_ = trans_id;
      tracker.pending_write_response_.write_req_handle_ = p_msg->handle;
      callbacks_->OnVendorSpecificReply(ble_bd_addr.bda, vendor_specific_characteristics_);
    } else {
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
    }
  }

  void OnWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                         uint16_t handle, uint16_t /*offset*/, bool /*need_rsp*/, bool /*is_prep*/,
                         uint8_t* value, uint16_t len) {
    log::info("conn_id:{}, handle:0x{:04x}, len:{}", conn_id, handle, len);

    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->handle = handle;

    // Only Client Characteristic Configuration (CCC) descriptor is expected
    RasCharacteristic* characteristic = GetCharacteristicByCccHandle(handle);
    if (characteristic == nullptr) {
      log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", handle);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      return;
    }

    if (trackers_.find(remote_bda) == trackers_.end()) {
      log::warn("Can't find tracker for remote_bda {}", remote_bda);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
      return;
    }
    ClientTracker* tracker = &trackers_[remote_bda];
    uint16_t ccc_value;
    STREAM_TO_UINT16(ccc_value, value);

    // Check that On-demand and Real-time are not registered at the same time
    uint16_t ccc_on_demand_temp = tracker->ccc_values_[kRasOnDemandDataCharacteristic];
    uint16_t ccc_real_time_temp = tracker->ccc_values_[kRasRealTimeRangingDataCharacteristic];
    if (characteristic->uuid_ == kRasRealTimeRangingDataCharacteristic) {
      ccc_real_time_temp = ccc_value;
    } else if (characteristic->uuid_ == kRasOnDemandDataCharacteristic) {
      ccc_on_demand_temp = ccc_value;
    }
    if (ccc_real_time_temp != GATT_CLT_CONFIG_NONE && ccc_on_demand_temp != GATT_CLT_CONFIG_NONE) {
      log::warn("Client Characteristic Configuration Descriptor Improperly Configured");
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_CCC_CFG_ERR, std::move(p_msg));
      return;
    }

    trackers_[remote_bda].ccc_values_[characteristic->uuid_] = ccc_value;
    log::info("Write CCC for {}, conn_id:{}, value:0x{:04x}", getUuidName(characteristic->uuid_),
              conn_id, ccc_value);
    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
  }

  void HandleControlPoint(ClientTracker* tracker, uint8_t* value, uint16_t len) {
    ControlPointCommand command;
    ParseControlPointCommand(&command, value, len);

    if (!command.isValid_) {
      SendResponseCode(ResponseCodeValue::INVALID_PARAMETER, tracker);
      return;
    }

    if (tracker->handling_control_point_command_ && command.opcode_ != Opcode::ABORT_OPERATION) {
      log::warn("Server busy");
      SendResponseCode(ResponseCodeValue::SERVER_BUSY, tracker);
      return;
    }

    tracker->handling_control_point_command_ = true;

    switch (command.opcode_) {
      case Opcode::GET_RANGING_DATA:
        OnGetRangingData(&command, tracker);
        break;
      case Opcode::ACK_RANGING_DATA:
        OnAckRangingData(&command, tracker);
        break;
      case Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS:
      case Opcode::ABORT_OPERATION:
      case Opcode::FILTER:
        log::warn("Unsupported opcode:0x{:02x}, {}", (uint16_t)command.opcode_,
                  GetOpcodeText(command.opcode_));
        SendResponseCode(ResponseCodeValue::OP_CODE_NOT_SUPPORTED, tracker);
        break;
      default:
        log::warn("Unknown opcode:0x{:02x}", (uint16_t)command.opcode_);
        SendResponseCode(ResponseCodeValue::OP_CODE_NOT_SUPPORTED, tracker);
    }
  }

  void OnGetRangingData(ControlPointCommand* command, ClientTracker* tracker) {
    const uint8_t* value = command->parameter_;
    uint16_t ranging_counter;
    STREAM_TO_UINT16(ranging_counter, value);
    log::info("ranging_counter:{}", ranging_counter);

    uint16_t ccc_value = tracker->ccc_values_[kRasOnDemandDataCharacteristic];
    uint16_t attr_id = GetCharacteristic(kRasOnDemandDataCharacteristic)->attribute_handle_;
    bool use_notification = ccc_value & GATT_CLT_CONFIG_NOTIFICATION;

    std::lock_guard<std::mutex> lock(on_demand_ranging_mutex_);
    auto it = std::find_if(tracker->buffers_.begin(), tracker->buffers_.end(),
                           [&ranging_counter](const DataBuffer& buffer) {
                             return buffer.ranging_counter_ == ranging_counter;
                           });
    if (it != tracker->buffers_.end()) {
      for (uint16_t i = 0; i < it->segments_.size(); i++) {
        if (ccc_value == GATT_CLT_CONFIG_NONE) {
          log::warn("On Demand Data is not subscribed, Skip");
          break;
        }
        log::info("Send On Demand Ranging Data, segment {}", i);
        BTA_GATTS_HandleValueIndication(tracker->conn_id_, attr_id, it->segments_[i],
                                        !use_notification);
      }
      log::info("Send COMPLETE_RANGING_DATA_RESPONSE, ranging_counter:{}", ranging_counter);
      std::vector<uint8_t> response(3, 0);
      response[0] = (uint8_t)EventCode::COMPLETE_RANGING_DATA_RESPONSE;
      response[1] = (ranging_counter & 0xFF);
      response[2] = (ranging_counter >> 8) & 0xFF;
      BTA_GATTS_HandleValueIndication(
              tracker->conn_id_,
              GetCharacteristic(kRasControlPointCharacteristic)->attribute_handle_, response, true);
      tracker->handling_control_point_command_ = false;
      return;
    } else {
      log::warn("No Records Found");
      SendResponseCode(ResponseCodeValue::NO_RECORDS_FOUND, tracker);
    }
  }

  void OnAckRangingData(ControlPointCommand* command, ClientTracker* tracker) {
    const uint8_t* value = command->parameter_;
    uint16_t ranging_counter;
    STREAM_TO_UINT16(ranging_counter, value);
    log::info("ranging_counter:{}", ranging_counter);

    std::lock_guard<std::mutex> lock(on_demand_ranging_mutex_);
    auto it = std::find_if(tracker->buffers_.begin(), tracker->buffers_.end(),
                           [&ranging_counter](const DataBuffer& buffer) {
                             return buffer.ranging_counter_ == ranging_counter;
                           });
    // If found, erase it
    if (it != tracker->buffers_.end()) {
      tracker->buffers_.erase(it);
      tracker->handling_control_point_command_ = false;
      SendResponseCode(ResponseCodeValue::SUCCESS, tracker);
    } else {
      log::warn("No Records Found");
      SendResponseCode(ResponseCodeValue::NO_RECORDS_FOUND, tracker);
    }
  }

  void SendResponseCode(ResponseCodeValue response_code_value, ClientTracker* tracker) {
    log::info("0x{:02x}, {}", (uint16_t)response_code_value,
              GetResponseOpcodeValueText(response_code_value));
    std::vector<uint8_t> response(2, 0);
    response[0] = (uint8_t)EventCode::RESPONSE_CODE;
    response[1] = (uint8_t)response_code_value;
    BTA_GATTS_HandleValueIndication(
            tracker->conn_id_, GetCharacteristic(kRasControlPointCharacteristic)->attribute_handle_,
            response, true);
    tracker->handling_control_point_command_ = false;
  }

  RasCharacteristic* GetCharacteristic(Uuid uuid) {
    for (auto& [attribute_handle, characteristic] : characteristics_) {
      if (characteristic.uuid_ == uuid) {
        return &characteristic;
      }
    }
    return nullptr;
  }

  RasCharacteristic* GetCharacteristicByCccHandle(uint16_t descriptor_handle) {
    for (auto& [attribute_handle, characteristic] : characteristics_) {
      if (characteristic.attribute_handle_ccc_ == descriptor_handle) {
        return &characteristic;
      }
    }
    return nullptr;
  }

  void ResolveAddress(tBLE_BD_ADDR& ble_bd_addr, const RawAddress& address) {
    ble_bd_addr.bda = address;
    ble_bd_addr.type = BLE_ADDR_RANDOM;
    maybe_resolve_address(&ble_bd_addr.bda, &ble_bd_addr.type);
  }

  DataBuffer& InitDataBuffer(RawAddress address, uint16_t procedure_counter) {
    std::vector<DataBuffer>& buffers = trackers_[address].buffers_;
    for (DataBuffer& data_buffer : buffers) {
      if (data_buffer.ranging_counter_ == procedure_counter) {
        // Data already exist, return
        return data_buffer;
      }
    }
    log::info("Create data for ranging_counter: {}, current size {}", procedure_counter,
              buffers.size());
    buffers.emplace_back(procedure_counter);
    return buffers.back();
  }

  VendorSpecificCharacteristic* GetVendorSpecificCharacteristic(const bluetooth::Uuid& uuid) {
    for (auto& characteristic : vendor_specific_characteristics_) {
      if (characteristic.characteristicUuid_ == uuid) {
        return &characteristic;
      }
    }
    return nullptr;
  }

private:
  bluetooth::Uuid app_uuid_;
  uint16_t server_if_;
  // A map to associate characteristics with handles
  std::unordered_map<uint16_t, RasCharacteristic> characteristics_;
  // A map to client trackers with address
  std::unordered_map<RawAddress, ClientTracker> trackers_;
  bluetooth::ras::RasServerCallbacks* callbacks_;
  std::mutex on_demand_ranging_mutex_;
  std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics_;
};

}  // namespace

bluetooth::ras::RasServer* bluetooth::ras::GetRasServer() {
  // Thread-safe initialization.
  // The constructor runs exactly once and sets the global 'instance' pointer.
  static RasServerImpl* safe_instance = new RasServerImpl();
  return safe_instance;
}
