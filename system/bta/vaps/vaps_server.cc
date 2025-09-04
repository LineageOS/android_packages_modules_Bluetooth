/*
 * Copyright 2025 The Android Open Source Project
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

 #include <algorithm>
 #include <cstdint>
 #include <cstring>
 #include <mutex>
 #include <unordered_map>
 #include <vector>

 #include "bta/include/bta_gatt_api.h"
 #include "bta/include/bta_vaps_server_api.h"
 #include "bta/vaps/vaps_server_types.h"
 #include "bta/le_audio/device_groups.h"
 #include "btm_ble_api_types.h"
 #include "gatt_api.h"
 #include "gd/os/rand.h"
 #include "hardware/bt_common_types.h"
 #include "main/shim/entry.h"
 #include "stack/include/bt_types.h"
 #include "stack/include/btm_ble_addr.h"
 #include "stack/include/main_thread.h"
 #include "bluetooth/types/ble_address_with_type.h"
 #include "bluetooth/types/bt_transport.h"
 #include "bluetooth/types/uuid.h"
 #include "bluetooth/types/address.h"

 using namespace bluetooth;
 using namespace ::vaps;
 using namespace ::vaps::uuid;

 namespace {

 class VapsServerImpl;
 VapsServerImpl* instance;

 static uint8_t kVaeUuid[kVaeUuidSize] = {};
 static uint8_t kVapsCcid = 0;

 class VapsServerImpl : public bluetooth::vaps::VapsServer {
 public:
   struct VapsCharacteristic {
     bluetooth::Uuid uuid_;
     uint16_t attribute_handle_;
     uint16_t attribute_handle_ccc_;
   };

   struct PendingWriteResponse {
     tCONN_ID conn_id_;
     uint32_t trans_id_;
     uint16_t write_req_handle_;
   };

   struct RemoteClient {
     tCONN_ID conn_id_;
     std::unordered_map<Uuid, uint16_t> ccc_values_;
     bool handling_control_point_command_ = false;
     PendingWriteResponse pending_write_response_;
     uint16_t mtu_ = kDefaultGattMtu;
   };

   void Initialize(bluetooth::vaps::VapsServerCallbacks* callbacks) override {
     do_in_main_thread(base::BindOnce(
         &VapsServerImpl::do_initialize, base::Unretained(this), callbacks));
   }

   void do_initialize(bluetooth::vaps::VapsServerCallbacks* callbacks) {
     log::info("initialize vaps server");
     callbacks_ = callbacks;

     Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
     app_uuid_ = uuid;
     log::info("Register server with uuid:{}", app_uuid_.ToString());
     BTA_GATTS_AppRegister(
             app_uuid_,
             [](tBTA_GATTS_EVT event, tBTA_GATTS* p_data) {
               if (instance && p_data) {
                 instance->GattsCallback(event, p_data);
               }
             },
             false);
   }

   void Cleanup() override {
     do_in_main_thread(base::BindOnce(
         &VapsServerImpl::do_cleanup, base::Unretained(this)));
   }

   void do_cleanup() {
     if (!instance) {
       log::error("Not initialized");
       return;
     }

     if (server_if_) {
       BTA_GATTS_AppDeregister(server_if_);
     }
     characteristics_.clear();
     remote_clients_.clear();
     callbacks_ = nullptr;
     vae_name_.clear();
     va_session_state_ = VaSessionState::VA_SESSION_UNAVAILABLE;
     server_if_ = 0;

     instance = nullptr;
     log::info("cleanup done");
  }

   void set_ccid(int ccid) {
     log::info("ccid:{}", ccid);
     kVapsCcid = ccid;
   }

   void set_vae_name(std::string vae_name) {
     log::info("vae_name:{}", vae_name);
     uint8_t va_session_state = (vae_name == "None") ?
         static_cast<uint8_t>(VaSessionState::VA_SESSION_UNAVAILABLE):
         static_cast<uint8_t>(VaSessionState::VA_SESSION_RESET);
     vae_name_ = vae_name;

     for (auto& [bda, remote_client] : remote_clients_) {
       uint16_t ccc_va_session_state = remote_client.ccc_values_[kVaSessionStateCharacteristic];
       log::info("device:{}", bda);
       //Send VA Session State notification
       SendVaSessionStateNotification(&remote_client, ccc_va_session_state, va_session_state);
     }
     SetVaSessionState(static_cast<VaSessionState>(va_session_state));
   }

   void SetCcid(int ccid) {
    do_in_main_thread(base::BindOnce(&VapsServerImpl::set_ccid, base::Unretained(this), ccid));
  }

   void SetVaeName(std::string vae_name) {
     do_in_main_thread(
         base::BindOnce(&VapsServerImpl::set_vae_name, base::Unretained(this), vae_name));
   }

   void NotifyVaSessionInitialized(RawAddress bda) {
     bool is_success = true;
     log::info("NotifyVaSessionInitialized:, bda", bda);

     if (remote_clients_.find(bda) != remote_clients_.end()) {
       RemoteClient* remote_client = &remote_clients_[bda];
       uint16_t ccc_vae_control_point = remote_client->ccc_values_[kVaeControlPointCharacteristic];
       uint16_t ccc_va_session_state = remote_client->ccc_values_[kVaSessionStateCharacteristic];
       ResponseCodeValue rsp_code_value =
           is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
       //Send VAE Control Point notification
       SendVaeControlPointNotification(remote_client, rsp_code_value, ccc_vae_control_point);

       uint8_t va_session_state =
           static_cast<uint8_t>(VaSessionState::VA_SESSION_READY);
       //Send VA Session State notification
       SendVaSessionStateNotification(remote_client, ccc_va_session_state, va_session_state);
     }
   }

   void NotifyVaSessionStarted(std::vector<RawAddress> devices, bool is_success) {
     log::info("NotifyVaSessionStarted:, is_success:{}", is_success);

     if (devices.empty()) {
       log::error(" No devices to notify");
     }
     if (GetVaSessionState() == VaSessionState::VA_SESSION_ACTIVE) {
       log::error("VA session is already active");
       return;
     }
     log::debug(" Number of devices: {}", devices.size());

     for (const auto& device : devices) {
       log::info("NotifyVaSessionStarted:, device:{}", device);
       if (remote_clients_.find(device) != remote_clients_.end()) {
         RemoteClient* remote_client = &remote_clients_[device];
         uint16_t ccc_vae_control_point =
             remote_client->ccc_values_[kVaeControlPointCharacteristic];
         uint16_t ccc_va_session_state =
             remote_client->ccc_values_[kVaSessionStateCharacteristic];
         ResponseCodeValue rsp_code_value =
             is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
         if (remote_client->handling_control_point_command_) {
           //Send VAE Control Point notification
           SendVaeControlPointNotification(remote_client, rsp_code_value, ccc_vae_control_point);
         }

         uint8_t session_state = ComputeSessionState(true, is_success);
         //Send VA Session State notification
         SendVaSessionStateNotification(remote_client, ccc_va_session_state, session_state);
       }
     }
   }

   void NotifyVaSessionStopped(std::vector<RawAddress> devices, bool is_success) {
     log::info("NotifyVaSessionStopped:, is_success:{}", is_success);
     if (devices.empty()) {
       log::error(" No devices to notify");
     }

     if (GetVaSessionState() != VaSessionState::VA_SESSION_ACTIVE) {
       log::warn("VA session is not active");
       return;
     }
     log::debug(" Number of devices: {}", devices.size());

     for (const auto& device : devices) {
       if (remote_clients_.find(device) != remote_clients_.end()) {
         RemoteClient* remote_client = &remote_clients_[device];
         uint16_t ccc_vae_control_point =
             remote_client->ccc_values_[kVaeControlPointCharacteristic];
         uint16_t ccc_va_session_state =
             remote_client->ccc_values_[kVaSessionStateCharacteristic];
         ResponseCodeValue rsp_code_value =
             is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
         if (remote_client->handling_control_point_command_) {
           //Send VAE Control Point notification
           SendVaeControlPointNotification(remote_client, rsp_code_value, ccc_vae_control_point);
         }

         uint8_t session_state = ComputeSessionState(false, is_success);
         //Send VA Session State notification
         SendVaSessionStateNotification(remote_client, ccc_va_session_state, session_state);
       }
     }
   }

   void SendVaeControlPointNotification(RemoteClient* remote_client,
                                        ResponseCodeValue rsp_code_value,
                                        uint16_t ccc_vae_control_point) {
     log::info(" conn_id:{}, ccc_vae_cp:{}, rsp_code_value:{}, rsp_code_str:{}",
               remote_client->conn_id_, ccc_vae_control_point,
               (uint16_t)rsp_code_value, GetResponseCodeValueText(rsp_code_value));

     //Send VAE Control Point notification
     if (ccc_vae_control_point != GATT_CLT_CONFIG_NONE) {
       bool use_notification = ccc_vae_control_point & GATT_CLT_CONFIG_NOTIFICATION;
       uint16_t attr_id =
              GetCharacteristic(kVaeControlPointCharacteristic)->attribute_handle_;
       std::vector<uint8_t> response(2, 0);
       response[0] = (uint8_t)CtpRespOpcode::RESPONSE_CODE;
       response[1] = (uint8_t)rsp_code_value;
       log::debug("Send VAE Control Point notification");
       BTA_GATTS_HandleValueIndication(remote_client->conn_id_, attr_id,
                                       response, !use_notification);
       remote_client->handling_control_point_command_ = false;
     }
   }

   uint8_t ComputeSessionState(bool is_started, bool is_success) {
     uint8_t session_state = 0xFF;
     log::info(" is_started:{}, is_success:{}", is_started, is_success);
     if (is_success) {
       if (is_started) {
         session_state = static_cast<uint8_t>(VaSessionState::VA_SESSION_ACTIVE);
       } else {
         session_state = static_cast<uint8_t>(VaSessionState::VA_SESSION_READY);
       }
     } else {
       session_state = static_cast<uint8_t>(VaSessionState::VA_SESSION_READY);
     }
     return session_state;
   }

   void SendVaSessionStateNotification(RemoteClient* remote_client,
                                       uint16_t ccc_va_session_state,
                                       uint8_t va_session_state) {
     uint8_t curr_va_session_state = static_cast<uint8_t>(GetVaSessionState());
     log::info(" conn_id:{}, ccc_va_session_state:{}, Curr VA session state: {},"
               " New VA session state:{}", remote_client->conn_id_,
               ccc_va_session_state,
               GetVaSessionStateText(static_cast<VaSessionState>(curr_va_session_state)),
               GetVaSessionStateText(static_cast<VaSessionState>(va_session_state)));
     if (curr_va_session_state == va_session_state) {
       log::info(" Not sending VA Session state notification - no change in VA session state");
       return;
     }

     SetVaSessionState(static_cast<VaSessionState>(va_session_state));
     if (ccc_va_session_state != GATT_CLT_CONFIG_NONE) {
       bool use_notification = ccc_va_session_state & GATT_CLT_CONFIG_NOTIFICATION;
       uint16_t attr_id =
               GetCharacteristic(kVaSessionStateCharacteristic)->attribute_handle_;
       std::vector<uint8_t> value(kVaSessionStateSize, 0);

       value[0] = va_session_state;

       log::debug("Send VA Session State notification");
       BTA_GATTS_HandleValueIndication(remote_client->conn_id_, attr_id, value, !use_notification);
     }
   }

   void GattsCallback(tBTA_GATTS_EVT event, tBTA_GATTS* p_data) {
     log::info("event: {}", gatt_server_event_text(event));
     switch (event) {
       case BTA_GATTS_CONNECT_EVT: {
         OnGattConnect(p_data);
       } break;
       case BTA_GATTS_DISCONNECT_EVT: {
         OnGattDisconnect(p_data);
       } break;
       case BTA_GATTS_MTU_EVT: {
         OnGattMtuChanged(p_data->req_data);
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

     if (remote_clients_.find(address) == remote_clients_.end()) {
       log::warn("Create new remote_client");
     }
     remote_clients_[address].conn_id_ = p_data->conn.conn_id;

     if (GetVaSessionState() != VaSessionState::VA_SESSION_UNAVAILABLE) {
       SetVaSessionState(VaSessionState::VA_SESSION_RESET);
     }
   }

   void OnGattMtuChanged(const tBTA_GATTS_REQ& req_data) {
     auto remote_bda = req_data.remote_bda;
     log::info("mtu is changed as {}", req_data.p_data->mtu);
     auto it = remote_clients_.find(remote_bda);
     if (it != remote_clients_.end()) {
       it->second.mtu_ = req_data.p_data->mtu;
     }
   }

   void OnGattDisconnect(tBTA_GATTS* p_data) {
     auto remote_bda = p_data->conn.remote_bda;
     log::info("Address: {}, conn_id:{}", remote_bda, p_data->conn.conn_id);
     remote_clients_.erase(remote_bda);
   }

   void OnGattServerRegister(tBTA_GATTS* p_data) {
     tGATT_STATUS status = p_data->reg_oper.status;
     log::info("status: {}", gatt_status_text(p_data->reg_oper.status));

     if (status != tGATT_STATUS::GATT_SUCCESS) {
       log::warn("Register Server fail");
       return;
     }
     server_if_ = p_data->reg_oper.server_if;

     std::vector<btgatt_db_element_t> service;
     // VAPS service
     btgatt_db_element_t vaps_service;
     vaps_service.uuid = kVapsService;
     vaps_service.type = BTGATT_DB_PRIMARY_SERVICE;
     service.push_back(vaps_service);

     // VAE Service Name characteristic
     btgatt_db_element_t vae_svc_name_characteristic;
     vae_svc_name_characteristic.uuid = kVaeNameCharacteristic;
     vae_svc_name_characteristic.type = BTGATT_DB_CHARACTERISTIC;
     vae_svc_name_characteristic.properties = GATT_CHAR_PROP_BIT_READ;
     vae_svc_name_characteristic.permissions = GATT_PERM_READ_ENCRYPTED;
     service.push_back(vae_svc_name_characteristic);

     // VAE Service UUID characteristic
     btgatt_db_element_t vae_svc_uuid_characteristic;
     vae_svc_uuid_characteristic.uuid = kVaeUuidCharacteristic;
     vae_svc_uuid_characteristic.type = BTGATT_DB_CHARACTERISTIC;
     vae_svc_uuid_characteristic.properties = GATT_CHAR_PROP_BIT_READ;
     vae_svc_uuid_characteristic.permissions = GATT_PERM_READ_ENCRYPTED;
     service.push_back(vae_svc_uuid_characteristic);

     // VAE Control Point (VAPS-CP) characteristic
     btgatt_db_element_t vaps_control_point;
     vaps_control_point.uuid = kVaeControlPointCharacteristic;
     vaps_control_point.type = BTGATT_DB_CHARACTERISTIC;
     vaps_control_point.properties = GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_NOTIFY;
     vaps_control_point.permissions = GATT_PERM_WRITE_ENCRYPTED;
     service.push_back(vaps_control_point);
     //CCC descriptor for VAE Control Point
     btgatt_db_element_t ccc_descriptor;
     ccc_descriptor.uuid = kClientCharacteristicConfiguration;
     ccc_descriptor.type = BTGATT_DB_DESCRIPTOR;
     ccc_descriptor.permissions = GATT_PERM_WRITE | GATT_PERM_READ;
     service.push_back(ccc_descriptor);

     // VAE CCID characteristic
     btgatt_db_element_t vae_ccid_characteristic;
     vae_ccid_characteristic.uuid = kVaeCcidCharacteristic;
     vae_ccid_characteristic.type = BTGATT_DB_CHARACTERISTIC;
     vae_ccid_characteristic.properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY;
     vae_ccid_characteristic.permissions = GATT_PERM_READ_ENCRYPTED;
     service.push_back(vae_ccid_characteristic);
     //CCC descriptor for VAE CCID characteristic
     service.push_back(ccc_descriptor);

     // VA Session State characteristic
     btgatt_db_element_t va_session_state_characteristic;
     va_session_state_characteristic.uuid = kVaSessionStateCharacteristic;
     va_session_state_characteristic.type = BTGATT_DB_CHARACTERISTIC;
     va_session_state_characteristic.properties =
          (GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY);
     va_session_state_characteristic.permissions = GATT_PERM_READ_ENCRYPTED;
     service.push_back(va_session_state_characteristic);
     //CCC descriptor for VA Session State characteristic
     service.push_back(ccc_descriptor);

     BTA_GATTS_AddService(server_if_, service,
                          base::BindRepeating([](tGATT_STATUS status, int server_if,
                                                 std::vector<btgatt_db_element_t> service) {
                            if (instance) {
                              instance->OnServiceAdded(status, server_if, service);
                            }
                          }));
   }

   void OnReadCharacteristic(tBTA_GATTS* p_data) {
     uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
     uint16_t offset = p_data->req_data.p_data->read_req.offset;
     log::info("read_req_handle: 0x{:04x}, offset: 0x{:04x}", read_req_handle, offset);

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
     if (remote_clients_.find(p_data->req_data.remote_bda) == remote_clients_.end()) {
       log::warn("Can't find remote_client for {}", p_data->req_data.remote_bda);
       BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                         GATT_ILLEGAL_PARAMETER,
                         &p_msg);
       return;
     }
     RemoteClient* remote_client = &remote_clients_[p_data->req_data.remote_bda];

     // Check Characteristic UUIDs of VAPS service
     switch (uuid.As16Bit()) {
       case kVaeNameCharacteristic16bit: {
        std::string service_name = vae_name_;
        std::vector<uint8_t> svc_name(service_name.begin(), service_name.end());
        log::info("svc_name: {}", svc_name.size());

        // Copy from the offset
        size_t copy_len = 0;
        if (offset < svc_name.size()) {
          copy_len = std::min((size_t)(svc_name.size() - offset), (size_t)remote_client->mtu_);
          memcpy(p_msg.attr_value.value, svc_name.data() + offset, copy_len);
        }
        p_msg.attr_value.len = copy_len;
      } break;
       case kVaeUuidCharacteristic16bit: {
         p_msg.attr_value.len = kVaeUuidSize;
         memcpy(p_msg.attr_value.value, kVaeUuid, kVaeUuidSize);
       } break;
       case kVaeCcidCharacteristic16bit: {
         p_msg.attr_value.len = 1;
         memcpy(p_msg.attr_value.value, &kVapsCcid, sizeof(uint8_t));
       } break;
       case kVaSessionStateCharacteristic16bit: {
         p_msg.attr_value.len = 1;
         memcpy(p_msg.attr_value.value, &va_session_state_, sizeof(uint8_t));
       } break;
       default:
         log::warn("Unhandled uuid {}", uuid.ToString());
         BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                           GATT_ILLEGAL_PARAMETER, &p_msg);
         return;
     }
     BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
   }

   void OnReadDescriptor(tBTA_GATTS* p_data) {
     tCONN_ID conn_id = p_data->req_data.conn_id;
     uint16_t read_req_handle = p_data->req_data.p_data->read_req.handle;
     RawAddress remote_bda = p_data->req_data.remote_bda;
     log::info("conn_id:{}, read_req_handle:0x{:04x}", conn_id, read_req_handle);

     tGATTS_RSP p_msg;
     p_msg.attr_value.handle = read_req_handle;

     // Only Client Characteristic Configuration (CCC) descriptor is expected
     VapsCharacteristic* characteristic = GetCharacteristicByCccHandle(read_req_handle);
     if (characteristic == nullptr) {
       log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", read_req_handle);
       BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE, &p_msg);
       return;
     }
     log::info("Read CCC for uuid, {}", getUuidName(characteristic->uuid_));
     uint16_t ccc_value = 0;
     if (remote_clients_.find(remote_bda) != remote_clients_.end()) {
       ccc_value = remote_clients_[remote_bda].ccc_values_[characteristic->uuid_];
     }

     p_msg.attr_value.len = kCccValueSize;
     memcpy(p_msg.attr_value.value, &ccc_value, sizeof(uint16_t));

     log::info("Send response for CCC value 0x{:04x}", ccc_value);
     BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
   }

   void OnWriteCharacteristic(tBTA_GATTS* p_data) {
     tCONN_ID conn_id = p_data->req_data.conn_id;
     uint16_t write_req_handle = p_data->req_data.p_data->write_req.handle;
     uint16_t len = p_data->req_data.p_data->write_req.len;
     bool need_rsp = p_data->req_data.p_data->write_req.need_rsp;
     log::info("conn_id:{}, write_req_handle:0x{:04x}, need_rsp{}, len:{}", conn_id,
               write_req_handle, need_rsp, len);

     tGATTS_RSP p_msg;
     p_msg.handle = write_req_handle;
     if (characteristics_.find(write_req_handle) == characteristics_.end()) {
       log::error("Invalid handle {}", write_req_handle);
       BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE,
                         &p_msg);
       return;
     }

     auto uuid = characteristics_[write_req_handle].uuid_;
     log::info("Write uuid, {}", getUuidName(uuid));

     // Check Characteristic UUID
     switch (uuid.As16Bit()) {
       case kVaeControlPointCharacteristic16bit: {
         if (remote_clients_.find(p_data->req_data.remote_bda) == remote_clients_.end()) {
           log::warn("Can't find remote_clients for {}", p_data->req_data.remote_bda);
           BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_ILLEGAL_PARAMETER, &p_msg);
           return;
         }
         RemoteClient* remote_client = &remote_clients_[p_data->req_data.remote_bda];
         if (need_rsp) {
           BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
         }
         HandleControlPoint(p_data->req_data.remote_bda, remote_client,
                            &p_data->req_data.p_data->write_req);
       } break;
       default:
         log::warn("Unhandled uuid {}", uuid.ToString());
         BTA_GATTS_SendRsp(p_data->req_data.conn_id, p_data->req_data.trans_id,
                           GATT_ILLEGAL_PARAMETER, &p_msg);
         return;
     }
   }

   void OnWriteDescriptor(tBTA_GATTS* p_data) {
     tCONN_ID conn_id = p_data->req_data.conn_id;
     uint16_t write_req_handle = p_data->req_data.p_data->write_req.handle;
     uint16_t len = p_data->req_data.p_data->write_req.len;
     RawAddress remote_bda = p_data->req_data.remote_bda;
     log::info("conn_id:{}, write_req_handle:0x{:04x}, len:{}", conn_id, write_req_handle, len);

     tGATTS_RSP p_msg;
     p_msg.handle = write_req_handle;

     // Only Client Characteristic Configuration (CCC) descriptor is expected
     VapsCharacteristic* characteristic = GetCharacteristicByCccHandle(write_req_handle);
     if (characteristic == nullptr) {
       log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", write_req_handle);
       BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_INVALID_HANDLE, &p_msg);
       return;
     }

     if (remote_clients_.find(remote_bda) == remote_clients_.end()) {
       log::warn("Can't find remote_client for remote_bda {}", remote_bda);
       BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_ILLEGAL_PARAMETER, &p_msg);
       return;
     }
     const uint8_t* value = p_data->req_data.p_data->write_req.value;
     uint16_t ccc_value;
     STREAM_TO_UINT16(ccc_value, value);

     remote_clients_[remote_bda].ccc_values_[characteristic->uuid_] = ccc_value;
     log::info("Write CCC for {}, conn_id:{}, value:0x{:04x}", getUuidName(characteristic->uuid_),
               conn_id, ccc_value);
     BTA_GATTS_SendRsp(conn_id, p_data->req_data.trans_id, GATT_SUCCESS, &p_msg);
   }

   void DebugDump(int fd) {
     std::stringstream stream;

     dprintf(fd, "VAPS Server Manager:\n");
     stream << "    VAE Name: " << +vae_name_.c_str() << "\n"
            << "    VA Session State: " << +GetVaSessionStateText(va_session_state_).c_str()<< "\n"
            << "    VAPS CCID: " << +kVapsCcid << "\n"
            << "    VAPS GATT Server IF: " << +server_if_ << "\n";
     for (auto& [address, remote_client] : remote_clients_) {
       stream << "    Remote Client: " << address.ToString() << "\n";
       stream << "    Remote Client MTU: " << remote_client.mtu_ << "\n";
       stream << "    Remote Client conn_id: " << remote_client.conn_id_ << "\n";
       stream << "    CCCD VAE Control Point: "
              << remote_client.ccc_values_[kVaeControlPointCharacteristic] << "\n";
       stream << "    CCCD VA Session State: "
              << remote_client.ccc_values_[kVaSessionStateCharacteristic] << "\n";
       stream << "    Handling Control Point Command:  "
              << remote_client.handling_control_point_command_ << "\n";
     }

     dprintf(fd, "%s", stream.str().c_str());
     dprintf(fd, "\n");
   }

   void HandleControlPoint(RawAddress bda, RemoteClient* remote_client,
                           tGATT_WRITE_REQ* write_req) {
     ControlPointCommand command;
     VaSessionState va_session_state = GetVaSessionState();
     ControlPointResponse cp_rsp =
         ValidateControlPointOperation(&command, write_req->value,
                                       write_req->len, va_session_state);

     uint16_t ccc_vae_control_point = remote_client->ccc_values_[kVaeControlPointCharacteristic];

     if (!command.isValid_) {
       SendVaeControlPointNotification(remote_client, cp_rsp.code_value_,
                                       ccc_vae_control_point);
       return;
     }
     remote_client->handling_control_point_command_ = true;

     switch (command.ctp_opcode_) {
       case CtpOpcode::START_VA_SESSION:
         OnStartVaSession(bda);
         break;
       case CtpOpcode::STOP_VA_SESSION:
         OnStopVaSession(bda);
         break;
       case CtpOpcode::INITIALIZE_VA_SESSION:
         OnInitializeVaSession(bda);
         break;
     }
   }

   void OnStartVaSession(RawAddress bda) {
     log::info("bda:{}", bda);
     callbacks_->OnStartVaSession(bda);
   }

   void OnStopVaSession(RawAddress bda) {
     log::info("bda:{}", bda);
     callbacks_->OnStopVaSession(bda);
   }

   void OnInitializeVaSession(RawAddress bda) {
     log::info("bda:{}", bda);
     NotifyVaSessionInitialized(bda);
     SetVaSessionState(VaSessionState::VA_SESSION_READY);
   }

   void OnServiceAdded(tGATT_STATUS status, int server_if,
                       std::vector<btgatt_db_element_t> service) {
     log::info("status: {}, server_if: {}", gatt_status_text(status), server_if);
     VapsCharacteristic* current_characteristic;
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
     callbacks_->OnInitialized();
   }

   VapsCharacteristic* GetCharacteristic(Uuid uuid) {
     for (auto& [attribute_handle, characteristic] : characteristics_) {
       if (characteristic.uuid_ == uuid) {
         return &characteristic;
       }
     }
     return nullptr;
   }

   VapsCharacteristic* GetCharacteristicByCccHandle(uint16_t descriptor_handle) {
     for (auto& [attribute_handle, characteristic] : characteristics_) {
       if (characteristic.attribute_handle_ccc_ == descriptor_handle) {
         return &characteristic;
       }
     }
     return nullptr;
   }

   void SetVaSessionState(VaSessionState state) {
     log::debug("{} ({:x}) -> {} ({:x})",
                 GetVaSessionStateText(va_session_state_),
                 static_cast<int>(va_session_state_),
                 GetVaSessionStateText(state),
                 static_cast<int>(state));
     va_session_state_ = state;
   }

   VaSessionState GetVaSessionState(void) const {
     log::debug("{} ({:x})",
                 GetVaSessionStateText(va_session_state_),
                 static_cast<int>(va_session_state_));
     return va_session_state_;
   }

 private:
   bluetooth::Uuid app_uuid_;
   uint16_t server_if_;
   // A map to associate characteristics with handles
   std::unordered_map<uint16_t, VapsCharacteristic> characteristics_;
   // A map to associate remote client with address
   std::unordered_map<RawAddress, RemoteClient> remote_clients_;
   bluetooth::vaps::VapsServerCallbacks* callbacks_;
   std::string vae_name_;
   VaSessionState va_session_state_;
 };

 }  // namespace

 bluetooth::vaps::VapsServer* bluetooth::vaps::GetVapsServer() {
   if (instance == nullptr) {
     instance = new VapsServerImpl();
   }
   return instance;
 }
