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
#include "stack/include/btm_ble_addr.h"

using namespace bluetooth;
using namespace ::ras;
using namespace ::ras::uuid;
using bluetooth::ras::VendorSpecificCharacteristic;

namespace {

class RasServerImpl;
RasServerImpl* instance;

static constexpr uint32_t kSupportedFeatures = feature::kRealTimeRangingData;
static constexpr uint16_t kBufferSize = 3;

class RasServerImpl : public bluetooth::ras::RasServer {
 public:
  struct RasCharacteristic {
    bluetooth::Uuid uuid_;
    uint16_t attribute_handle_;
    uint16_t attribute_handle_ccc_;
  };

  // Struct to save data of specific ranging counter
  struct DataBuffer {
    DataBuffer(uint16_t ranging_counter)
        : ranging_counter_(ranging_counter), segments_() {}
    uint16_t ranging_counter_;
    std::vector<std::vector<uint8_t>> segments_;
  };

  struct ClientTracker {
    uint16_t conn_id_;
    std::unordered_map<Uuid, uint16_t> ccc_values_;
    std::vector<DataBuffer> buffers_;
    bool handling_control_point_command_ = false;
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

  void SetVendorSpecificCharacteristic(
      const std::vector<VendorSpecificCharacteristic>&
          vendor_specific_characteristics) {
    vendor_specific_characteristics_ = vendor_specific_characteristics;
  }

  void PushProcedureData(RawAddress address, uint16_t procedure_counter,
                         bool is_last, std::vector<uint8_t> data) {
    log::debug("{}, counter:{}, is_last:{}, with size {}", address,
               procedure_counter, is_last, data.size());
    tBLE_BD_ADDR ble_bd_addr;
    ResolveAddress(ble_bd_addr, address);

    if (trackers_.find(ble_bd_addr.bda) == trackers_.end()) {
      log::warn("Can't find tracker for {}", ble_bd_addr.bda);
      return;
    }
    ClientTracker& tracker = trackers_[ble_bd_addr.bda];
    uint16_t ccc_real_time =
        tracker.ccc_values_[kRasRealTimeRangingDataCharacteristic];
    uint16_t ccc_data_ready =
        tracker.ccc_values_[kRasRangingDataReadyCharacteristic];
    uint16_t ccc_data_over_written =
        tracker.ccc_values_[kRasRangingDataOverWrittenCharacteristic];

    if (ccc_real_time != GATT_CLT_CONFIG_NONE) {
      bool need_confirm = ccc_real_time == GATT_CHAR_CLIENT_CONFIG_INDICTION;
      uint16_t attr_id =
          GetCharacteristic(kRasRealTimeRangingDataCharacteristic)
              ->attribute_handle_;
      log::debug("Send Real-time Ranging Data");
      BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, data,
                                      need_confirm);
    }

    if (ccc_data_ready == GATT_CLT_CONFIG_NONE &&
        ccc_data_over_written == GATT_CLT_CONFIG_NONE) {
      return;
    }
    std::lock_guard<std::mutex> lock(on_demand_ranging_mutex_);
    DataBuffer& data_buffer =
        InitDataBuffer(ble_bd_addr.bda, procedure_counter);
    data_buffer.segments_.push_back(data);

    // Send data ready
    if (is_last) {
      if (ccc_data_ready == GATT_CLT_CONFIG_NONE) {
        log::debug("Skip Ranging Data Ready");
      } else {
        bool need_confirm = ccc_data_ready & GATT_CLT_CONFIG_INDICATION;
        log::debug("Send data ready, ranging_counter {}", procedure_counter);
        uint16_t attr_id = GetCharacteristic(kRasRangingDataReadyCharacteristic)
                               ->attribute_handle_;
        std::vector<uint8_t> value(kRingingCounterSize);
        value[0] = (procedure_counter & 0xFF);
        value[1] = (procedure_counter >> 8) & 0xFF;
        BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, value,
                                        need_confirm);
      }
    }

    // Send data overwritten
    if (tracker.buffers_.size() > kBufferSize) {
      auto begin = tracker.buffers_.begin();
      if (ccc_data_over_written == GATT_CLT_CONFIG_NONE) {
        log::debug("Skip Ranging Data Over Written");
        tracker.buffers_.erase(begin);
        return;
      }
      bool need_confirm = ccc_data_over_written & GATT_CLT_CONFIG_INDICATION;
      log::debug("Send data over written, ranging_counter {}",
                 begin->ranging_counter_);
      uint16_t attr_id =
          GetCharacteristic(kRasRangingDataOverWrittenCharacteristic)
              ->attribute_handle_;
      std::vector<uint8_t> value(kRingingCounterSize);
      value[0] = (begin->ranging_counter_ & 0xFF);
      value[1] = (begin->ranging_counter_ >> 8) & 0xFF;
      BTA_GATTS_HandleValueIndication(tracker.conn_id_, attr_id, value,
                                      need_confirm);
      tracker.buffers_.erase(begin);
    }
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
      case BTA_GATTS_WRITE_CHARACTERISTIC_EVT: {
        OnWriteCharacteristic(p_data);
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
    log::info("Address: {}, conn_id:{}", address, p_data->conn.conn_id);
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

    for (auto& vendor_specific_characteristics :
         vendor_specific_characteristics_) {
      btgatt_db_element_t characteristics;
      characteristics.uuid =
          vendor_specific_characteristics.characteristicUuid_;
      characteristics.type = BTGATT_DB_CHARACTERISTIC;
      characteristics.properties =
          GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_WRITE;
      characteristics.permissions =
          GATT_PERM_READ_ENCRYPTED | GATT_PERM_WRITE_ENCRYPTED | key_mask;
      service.push_back(characteristics);
      log::info("Push vendor_specific_characteristics uuid {}",
                characteristics.uuid);
    }

    BTA_GATTS_AddService(
        server_if_, service,
        base::BindRepeating([](tGATT_STATUS status, int server_if,
                               std::vector<btgatt_db_element_t> service) {
          if (instance) instance->OnServiceAdded(status, server_if, service);
        }));
  }

  void OnReadCharacteristic(tBTA_GATTS* p_data) {
    uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
    log::info("read_req_handle: 0x{:04x},", read_req_handle);

    tGATTS_RSP p_msg;
    p_msg.attr_value.handle = read_req_handle;
    if (characteristics_.find(read_req_handle) == characteristics_.end()) {
      log::error("Invalid handle 0x{:04x}", read_req_handle);
      BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                        GATT_INVALID_HANDLE, &p_msg);
      return;
    }

    auto uuid = characteristics_[read_req_handle].uuid_;
    auto vendor_specific_characteristic = GetVendorSpecificCharacteristic(uuid);
    if (vendor_specific_characteristic != nullptr) {
      log::debug("Read vendor_specific_characteristic uuid {}", uuid);
      p_msg.attr_value.len = vendor_specific_characteristic->value_.size();
      std::copy(vendor_specific_characteristic->value_.begin(),
                vendor_specific_characteristic->value_.end(),
                p_msg.attr_value.value);
      BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                        GATT_SUCCESS, &p_msg);
      return;
    }
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

  void OnWriteCharacteristic(tBTA_GATTS* p_data) {
    uint16_t conn_id = p_data->req_data.conn_id;
    uint16_t write_req_handle = p_data->req_data.p_data->write_req.handle;
    uint16_t len = p_data->req_data.p_data->write_req.len;
    log::info("conn_id:{}, write_req_handle:{}, len:{}", conn_id,
              write_req_handle, len);

    tGATTS_RSP p_msg;
    p_msg.handle = write_req_handle;
    if (characteristics_.find(write_req_handle) == characteristics_.end()) {
      log::error("Invalid handle {}", write_req_handle);
      BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                        GATT_INVALID_HANDLE, &p_msg);
      return;
    }

    auto uuid = characteristics_[write_req_handle].uuid_;
    log::info("Write uuid, {}", getUuidName(uuid));

    // Check Characteristic UUID
    switch (uuid.As16Bit()) {
      case kRasControlPointCharacteristic16bit: {
        if (trackers_.find(p_data->req_data.remote_bda) == trackers_.end()) {
          log::warn("Can't find trackers for {}", p_data->req_data.remote_bda);
          BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id,
                            GATT_ILLEGAL_PARAMETER, &p_msg);
          return;
        }
        ClientTracker* tracker = &trackers_[p_data->req_data.remote_bda];
        if (tracker->handling_control_point_command_) {
          log::warn("Procedure Already In Progress");
          BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id,
                            GATT_PRC_IN_PROGRESS, &p_msg);
          return;
        }
        BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS,
                          &p_msg);
        HandleControlPoint(tracker, &p_data->req_data.p_data->write_req);
      } break;
      default:
        log::warn("Unhandled uuid {}", uuid.ToString());
        BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                          GATT_ILLEGAL_PARAMETER, &p_msg);
        return;
    }
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

  void HandleControlPoint(ClientTracker* tracker, tGATT_WRITE_REQ* write_req) {
    ControlPointCommand command;
    if (!ParseControlPointCommand(&command, write_req->value, write_req->len)) {
      return;
    }

    tracker->handling_control_point_command_ = true;

    switch (command.opcode_) {
      case Opcode::GET_RANGING_DATA: {
        OnGetRangingData(&command, tracker);
      } break;
      case Opcode::ACK_RANGING_DATA: {
        OnAckRangingData(&command, tracker);
      } break;
      case Opcode::RETRIEVE_LOST_RANGING_DATA_SEGMENTS:
      case Opcode::ABORT_OPERATION:
      case Opcode::FILTER:
      case Opcode::PCT_FORMAT: {
        log::warn("Unsupported opcode:0x{:02x}, {}", (uint16_t)command.opcode_,
                  GetOpcodeText(command.opcode_));
        SendResponseCode(ResponseCodeValue::OP_CODE_NOT_SUPPORTED, tracker);
      } break;
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
    uint16_t attr_id =
        GetCharacteristic(kRasOnDemandDataCharacteristic)->attribute_handle_;
    bool need_confirm = ccc_value & GATT_CLT_CONFIG_INDICATION;

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
        BTA_GATTS_HandleValueIndication(tracker->conn_id_, attr_id,
                                        it->segments_[i], need_confirm);
      }
      log::info("Send COMPLETE_RANGING_DATA_RESPONSE, ranging_counter:{}",
                ranging_counter);
      std::vector<uint8_t> response(8, 0);
      response[0] = (uint8_t)EventCode::COMPLETE_RANGING_DATA_RESPONSE;
      response[1] = (ranging_counter & 0xFF);
      response[2] = (ranging_counter >> 8) & 0xFF;
      BTA_GATTS_HandleValueIndication(
          tracker->conn_id_,
          GetCharacteristic(kRasControlPointCharacteristic)->attribute_handle_,
          response, true);
      tracker->handling_control_point_command_ = false;
      return;
    } else {
      log::warn("No Records Found");
      SendResponseCode(ResponseCodeValue::NO_RECORDS_FOUND, tracker);
    }
  };

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
  };

  void SendResponseCode(ResponseCodeValue response_code_value,
                        ClientTracker* tracker) {
    log::info("0x{:02x}, {}", (uint16_t)response_code_value,
              GetResponseOpcodeValueText(response_code_value));
    std::vector<uint8_t> response(8, 0);
    response[0] = (uint8_t)EventCode::RESPONSE_CODE;
    response[1] = (uint8_t)response_code_value;
    BTA_GATTS_HandleValueIndication(
        tracker->conn_id_,
        GetCharacteristic(kRasControlPointCharacteristic)->attribute_handle_,
        response, true);
    tracker->handling_control_point_command_ = false;
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
    log::info("Create data for ranging_counter: {}, current size {}",
              procedure_counter, buffers.size());
    buffers.emplace_back(procedure_counter);
    return buffers.back();
  }

  VendorSpecificCharacteristic* GetVendorSpecificCharacteristic(
      const bluetooth::Uuid& uuid) {
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
  std::mutex on_demand_ranging_mutex_;
  std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics_;
};

}  // namespace

bluetooth::ras::RasServer* bluetooth::ras::GetRasServer() {
  if (instance == nullptr) {
    instance = new RasServerImpl();
  }
  return instance;
};
