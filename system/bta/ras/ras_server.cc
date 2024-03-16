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
#include <bluetooth/log.h>

#include <unordered_map>

#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/ras/ras_types.h"
#include "os/logging/log_adapter.h"
#include "stack/include/bt_types.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;

namespace {

class RasServerImpl;
RasServerImpl* instance;

static constexpr uint32_t kSupportedFeatures = 0;

class RasServerImpl : public bluetooth::ras::RasServer {
 public:
  struct RasCharacteristic {
    bluetooth::Uuid uuid_;
    uint16_t attribute_handle_;
    uint16_t attribute_handle_ccc_;
  };

  struct ClientTracker {
    uint16_t conn_id_;
    std::unordered_map<Uuid, uint16_t> ccc_values_;
  };

  void Initialize() {
    app_uuid_ = bluetooth::Uuid::GetRandom();
    log::info("Register server with uuid:{}", app_uuid_.ToString());

    BTA_GATTS_AppRegister(
        app_uuid_,
        [](tBTA_GATTS_EVT event, tBTA_GATTS* p_data) {
          if (instance && p_data) instance->GattsCallback(event, p_data);
        },
        false);
  }

  void GattsCallback(tBTA_GATTS_EVT event, tBTA_GATTS* p_data) {
    log::info("event: {}", gatt_server_event_text(event));
    switch (event) {
      case BTA_GATTS_CONNECT_EVT: {
        OnGattConnect(p_data);
      } break;
      case BTA_GATTS_REG_EVT: {
        OnGattServerRegister(p_data);
      } break;
      case BTA_GATTS_READ_CHARACTERISTIC_EVT: {
        OnReadCharacteristic(p_data);
      } break;
      case BTA_GATTS_READ_DESCRIPTOR_EVT: {
        OnReadDescriptor(p_data);
      } break;
      case BTA_GATTS_WRITE_DESCRIPTOR_EVT: {
        OnWriteDescriptor(p_data);
      } break;
      default:
        log::warn("Unhandled event {}", event);
    }
  }

  void OnGattConnect(tBTA_GATTS* p_data) {
    auto address = p_data->conn.remote_bda;
    log::info("Address: {}, conn_id:{}", ADDRESS_TO_LOGGABLE_STR(address),
              p_data->conn.conn_id);
    if (p_data->conn.transport == BT_TRANSPORT_BR_EDR) {
      log::warn("Skip BE/EDR connection");
      return;
    }

    if (trackers_.find(address) == trackers_.end()) {
      log::warn("Create new tracker");
    }
    trackers_[address].conn_id_ = p_data->conn.conn_id;
  }

  void OnGattServerRegister(tBTA_GATTS* p_data) {
    tGATT_STATUS status = p_data->reg_oper.status;
    log::info("status: {}", gatt_status_text(p_data->reg_oper.status));

    if (status != tGATT_STATUS::GATT_SUCCESS) {
      log::warn("Register Server fail");
      return;
    }
    server_if_ = p_data->reg_oper.server_if;

    uint16_t key_mask = ((16 - 7) << 12);
    std::vector<btgatt_db_element_t> service;
    // RAS service
    btgatt_db_element_t ranging_service;
    ranging_service.uuid = kRangingService;
    ranging_service.type = BTGATT_DB_PRIMARY_SERVICE;
    service.push_back(ranging_service);

    // RAS Features
    btgatt_db_element_t features_characteristic;
    features_characteristic.uuid = kRasFeaturesCharacteristic;
    features_characteristic.type = BTGATT_DB_CHARACTERISTIC;
    features_characteristic.properties = GATT_CHAR_PROP_BIT_READ;
    features_characteristic.permissions = GATT_PERM_READ_ENCRYPTED | key_mask;
    service.push_back(features_characteristic);

    // Real-time Ranging Data (Optional)
    btgatt_db_element_t real_time_ranging_data_characteristic;
    real_time_ranging_data_characteristic.uuid =
        kRasRealTimeRangingDataCharacteristic;
    real_time_ranging_data_characteristic.type = BTGATT_DB_CHARACTERISTIC;
    real_time_ranging_data_characteristic.properties =
        GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    real_time_ranging_data_characteristic.permissions =
        GATT_PERM_READ_ENCRYPTED | key_mask;
    service.push_back(real_time_ranging_data_characteristic);
    btgatt_db_element_t ccc_descriptor;
    ccc_descriptor.uuid = kClientCharacteristicConfiguration;
    ccc_descriptor.type = BTGATT_DB_DESCRIPTOR;
    ccc_descriptor.permissions = GATT_PERM_WRITE | GATT_PERM_READ | key_mask;
    service.push_back(ccc_descriptor);

    // On-demand Ranging Data
    btgatt_db_element_t on_demand_ranging_data_characteristic;
    on_demand_ranging_data_characteristic.uuid = kRasOnDemandDataCharacteristic;
    on_demand_ranging_data_characteristic.type = BTGATT_DB_CHARACTERISTIC;
    on_demand_ranging_data_characteristic.properties =
        GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    on_demand_ranging_data_characteristic.permissions =
        GATT_PERM_READ_ENCRYPTED | key_mask;
    service.push_back(on_demand_ranging_data_characteristic);
    service.push_back(ccc_descriptor);

    // RAS Control Point (RAS-CP)
    btgatt_db_element_t ras_control_point;
    ras_control_point.uuid = kRasControlPointCharacteristic;
    ras_control_point.type = BTGATT_DB_CHARACTERISTIC;
    ras_control_point.properties =
        GATT_CHAR_PROP_BIT_WRITE | GATT_CHAR_PROP_BIT_INDICATE;
    ras_control_point.permissions = GATT_PERM_WRITE_ENCRYPTED | key_mask;
    service.push_back(ras_control_point);
    service.push_back(ccc_descriptor);

    // Ranging Data Ready
    btgatt_db_element_t ranging_data_ready_characteristic;
    ranging_data_ready_characteristic.uuid = kRasRangingDataReadyCharacteristic;
    ranging_data_ready_characteristic.type = BTGATT_DB_CHARACTERISTIC;
    ranging_data_ready_characteristic.properties =
        GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    ranging_data_ready_characteristic.permissions =
        GATT_PERM_READ_ENCRYPTED | key_mask;
    service.push_back(ranging_data_ready_characteristic);
    service.push_back(ccc_descriptor);

    // Ranging Data Overwritten
    btgatt_db_element_t ranging_data_overwritten_characteristic;
    ranging_data_overwritten_characteristic.uuid =
        kRasRangingDataOverWrittenCharacteristic;
    ranging_data_overwritten_characteristic.type = BTGATT_DB_CHARACTERISTIC;
    ranging_data_overwritten_characteristic.properties =
        GATT_CHAR_PROP_BIT_NOTIFY | GATT_CHAR_PROP_BIT_INDICATE;
    ranging_data_overwritten_characteristic.permissions =
        GATT_PERM_READ_ENCRYPTED | key_mask;
    service.push_back(ranging_data_overwritten_characteristic);
    service.push_back(ccc_descriptor);

    BTA_GATTS_AddService(
        server_if_, service,
        base::BindRepeating([](tGATT_STATUS status, int server_if,
                               std::vector<btgatt_db_element_t> service) {
          if (instance) instance->OnServiceAdded(status, server_if, service);
        }));
  }

  void OnReadCharacteristic(tBTA_GATTS* p_data) {
    uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
    log::info("read_req_handle: 0x{:04x}, ", read_req_handle);

    tGATTS_RSP p_msg;
    p_msg.attr_value.handle = read_req_handle;
    if (characteristics_.find(read_req_handle) == characteristics_.end()) {
      log::error("Invalid handle 0x{:04x}", read_req_handle);
      BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                        GATT_INVALID_HANDLE, &p_msg);
      return;
    }

    auto uuid = characteristics_[read_req_handle].uuid_;
    log::info("Read uuid, {}", getUuidName(uuid));

    // Check Characteristic UUID
    switch (uuid.As16Bit()) {
      case kRasFeaturesCharacteristic16bit: {
        p_msg.attr_value.len = kFeatureSize;
        memcpy(p_msg.attr_value.value, &kSupportedFeatures, sizeof(uint32_t));
      } break;
      default:
        log::warn("Unhandled uuid {}", uuid.ToString());
        BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                          GATT_ILLEGAL_PARAMETER, &p_msg);
        return;
    }
    BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                      GATT_SUCCESS, &p_msg);
  }

  void OnReadDescriptor(tBTA_GATTS* p_data) {
    uint16_t conn_id = p_data->req_data.conn_id;
    uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
    RawAddress remote_bda = p_data->req_data.remote_bda;
    log::info("conn_id:{}, read_req_handle:0x{:04x}", conn_id, read_req_handle);

    tGATTS_RSP p_msg;
    p_msg.attr_value.handle = read_req_handle;

    // Only Client Characteristic Configuration (CCC) descriptor is expected
    RasCharacteristic* characteristic =
        GetCharacteristicByCccHandle(read_req_handle);
    if (characteristic == nullptr) {
      log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}",
                read_req_handle);
      BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE,
                        &p_msg);
      return;
    }
    log::info("Read CCC for uuid, {}", getUuidName(characteristic->uuid_));
    uint16_t ccc_value = 0;
    if (trackers_.find(remote_bda) != trackers_.end()) {
      ccc_value = trackers_[remote_bda].ccc_values_[characteristic->uuid_];
    }

    p_msg.attr_value.len = kCccValueSize;
    memcpy(p_msg.attr_value.value, &ccc_value, sizeof(uint16_t));

    log::info("Send response for CCC value 0x{:04x}", ccc_value);
    BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
  }

  void OnWriteDescriptor(tBTA_GATTS* p_data) {
    uint16_t conn_id = p_data->req_data.conn_id;
    uint16_t write_req_handle = p_data->req_data.p_data->write_req.handle;
    uint16_t len = p_data->req_data.p_data->write_req.len;
    RawAddress remote_bda = p_data->req_data.remote_bda;
    log::info("conn_id:{}, write_req_handle:0x{:04x}, len:{}", conn_id,
              write_req_handle, len);

    tGATTS_RSP p_msg;
    p_msg.handle = write_req_handle;

    // Only Client Characteristic Configuration (CCC) descriptor is expected
    RasCharacteristic* characteristic =
        GetCharacteristicByCccHandle(write_req_handle);
    if (characteristic == nullptr) {
      log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}",
                write_req_handle);
      BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE,
                        &p_msg);
      return;
    }
    const uint8_t* value = p_data->req_data.p_data->write_req.value;
    uint16_t ccc_value;
    STREAM_TO_UINT16(ccc_value, value);
    if (trackers_.find(remote_bda) != trackers_.end()) {
      trackers_[remote_bda].ccc_values_[characteristic->uuid_] = ccc_value;
    }
    log::info("Write CCC for {}, conn_id:{}, value:0x{:04x}",
              getUuidName(characteristic->uuid_), conn_id, ccc_value);
    BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
  }

  void OnServiceAdded(tGATT_STATUS status, int server_if,
                      std::vector<btgatt_db_element_t> service) {
    log::info("status: {}, server_if: {}", gatt_status_text(status), server_if);
    RasCharacteristic* current_characteristic;
    for (uint16_t i = 0; i < service.size(); i++) {
      uint16_t attribute_handle = service[i].attribute_handle;
      Uuid uuid = service[i].uuid;
      if (service[i].type == BTGATT_DB_CHARACTERISTIC) {
        log::info("Characteristic uuid: 0x{:04x}, handle:0x{:04x}, {}",
                  uuid.As16Bit(), attribute_handle, getUuidName(uuid));
        characteristics_[attribute_handle].attribute_handle_ = attribute_handle;
        characteristics_[attribute_handle].uuid_ = uuid;
        current_characteristic = &characteristics_[attribute_handle];
      } else if (service[i].type == BTGATT_DB_DESCRIPTOR) {
        log::info("\tDescriptor uuid: 0x{:04x}, handle: 0x{:04x}, {}",
                  uuid.As16Bit(), attribute_handle, getUuidName(uuid));
        if (service[i].uuid == kClientCharacteristicConfiguration) {
          current_characteristic->attribute_handle_ccc_ = attribute_handle;
        }
      }
    }
  }

  RasCharacteristic* GetCharacteristicByCccHandle(uint16_t descriptor_handle) {
    for (auto& [attribute_handle, characteristic] : characteristics_) {
      if (characteristic.attribute_handle_ccc_ == descriptor_handle) {
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
};

}  // namespace

bluetooth::ras::RasServer* bluetooth::ras::GetRasServer() {
  if (instance == nullptr) {
    instance = new RasServerImpl();
  }
  return instance;
};
