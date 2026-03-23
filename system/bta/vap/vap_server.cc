/*
 * Copyright 2026 The Android Open Source Project
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

#include "bluetooth/types/address.h"
#include "bluetooth/types/ble_address_with_type.h"
#include "bluetooth/types/bt_transport.h"
#include "bluetooth/types/uuid.h"
#include "bta/include/bta_csis_api.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_vap_server_api.h"
#include "bta/le_audio/device_groups.h"
#include "bta/vap/vap_server_types.h"
#include "gd/common/utils.h"
#include "gd/os/rand.h"
#include "hardware/bt_common_types.h"
#include "main/shim/entry.h"
#include "stack/include/bt_types.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/gatt_api.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;
using bluetooth::csis::CsisClient;
using namespace ::vap;
using namespace ::vap::uuid;
using bluetooth::stack::tGATT_REQ_CBACK;

namespace {

class VapServerImpl;
VapServerImpl* instance;

static uint8_t kVapCcid = 0;
static uint8_t kVaSupportedFeatures = 0;

class VapServerImpl : public bluetooth::vap::VapServer {
public:
  struct VapCharacteristic {
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

  void Initialize(bluetooth::vap::VapServerCallbacks* callbacks) override {
    do_in_main_thread(
            base::BindOnce(&VapServerImpl::do_initialize, base::Unretained(this), callbacks));
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

  void do_initialize(bluetooth::vap::VapServerCallbacks* callbacks) {
    log::info("initialize vap server");
    callbacks_ = callbacks;

    Uuid uuid = Uuid::From128BitBE(bluetooth::os::GenerateRandom<Uuid::kNumBytes128>());
    app_uuid_ = uuid;
    log::info("Register server with uuid:{}", app_uuid_.ToString());

    static bluetooth::stack::tGATT_REQ_CBACK vap_req_cb = {
            .read_characteristic_cb = OnGattReadCharacteristicStatic,
            .read_descriptor_cb = OnGattReadDescriptorStatic,
            .write_characteristic_cb = OnGattWriteCharacteristicStatic,
            .write_descriptor_cb = OnGattWriteDescriptorStatic,
            .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
            .mtu_changed_cb = OnGattMtuChangedStatic,
            .conf_cb = tGATT_REQ_CBACK::do_nothing,
    };
    static const stack::tGATT_CBACK vap_ops = {
            .p_conn_cb = OnGattConnStatic,
            .p_req_cb = &vap_req_cb,
    };

    server_if_ = BTA_GATTS_AppRegister(app_uuid_, &vap_ops, true);
    log::info("server_if: {}", server_if_);

    if (server_if_ == stack::GATT_IF_INVALID) {
      log::warn("Register Server fail");
      return;
    }

    std::vector<btgatt_db_element_t> service = {
            // Generic Voice Assistant Service
            btgatt_db_element_t{.uuid = kGenericVasService, .type = BTGATT_DB_PRIMARY_SERVICE},

            // VA Name characteristic
            btgatt_db_element_t{.uuid = kVaNameCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                                .permissions = GATT_PERM_READ_ENCRYPTED},

            // CCC descriptor for VA Name characteristic
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ},

            // VA UUID characteristic
            btgatt_db_element_t{.uuid = kVaUuidCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            // CCC descriptor for VA UUID characteristic
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ},

            // VAS Control Point (VAS-CP) characteristic
            btgatt_db_element_t{
                    .uuid = kVasControlPointCharacteristic,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_WRITE_NR | GATT_CHAR_PROP_BIT_NOTIFY,
                    .permissions = GATT_PERM_WRITE_ENCRYPTED},
            // CCC descriptor for VAS Control Point
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ},

            // VA CCID characteristic
            btgatt_db_element_t{.uuid = kVaCcidCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            // CCC descriptor for VA CCID characteristic
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ},

            // VA Session State characteristic
            btgatt_db_element_t{.uuid = kVaSessionStateCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = (GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY),
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            // CCC descriptor for VA Session State characteristic
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ},

            // VA Supported Features characteristic
            btgatt_db_element_t{.uuid = kVaSupportedFeaturesCharacteristic,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = (GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY),
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            // CCC descriptor for VA Supported Features characteristic
            btgatt_db_element_t{.uuid = kClientCharacteristicConfiguration,
                                .type = BTGATT_DB_DESCRIPTOR,
                                .permissions = GATT_PERM_WRITE | GATT_PERM_READ}};

    auto status = BTA_GATTS_AddService(server_if_, &service);
    log::info("status: {}, server_if: {}", gatt_status_text(status), server_if_);
    VapCharacteristic* current_characteristic;
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

  void Cleanup() override {
    do_in_main_thread(base::BindOnce(&VapServerImpl::do_cleanup, base::Unretained(this)));
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
    va_name_.clear();
    va_session_state_ = VaSessionState::VA_SESSION_UNAVAILABLE;
    server_if_ = 0;

    instance = nullptr;
    log::info("cleanup done");
  }

   void set_ccid(int ccid) {
     log::info("ccid:{}", ccid);
     kVapCcid = ccid;
   }

   void set_va_name(std::string va_name) {
     log::info("va_name:{}", va_name);
     uint8_t va_session_state = (va_name == "None") ?
         static_cast<uint8_t>(VaSessionState::VA_SESSION_UNAVAILABLE):
         static_cast<uint8_t>(VaSessionState::VA_SESSION_RESET);
     va_name_ = va_name;

     for (auto& [bda, remote_client] : remote_clients_) {
       uint16_t ccc_va_session_state = remote_client.ccc_values_[kVaSessionStateCharacteristic];
       log::info("device:{}", bda);

       uint16_t ccc_va_name = remote_client.ccc_values_[kVaNameCharacteristic];
       uint16_t ccc_va_uuid = remote_client.ccc_values_[kVaUuidCharacteristic];
       // Send VA Name notification
       SendVaNameNotification(&remote_client, ccc_va_name, va_name);

       // Send VA UUID notification
       // Using VA name bytes for VA UUID as we don't have an API from VA apps
       SendVaUuidNotification(&remote_client, ccc_va_uuid, va_name);

       // Send VA Session State notification
       SendVaSessionStateNotification(&remote_client, ccc_va_session_state, va_session_state);
     }
     SetVaSessionState(static_cast<VaSessionState>(va_session_state));
   }

   void SetCcid(int ccid) {
    do_in_main_thread(base::BindOnce(&VapServerImpl::set_ccid, base::Unretained(this), ccid));
  }

   void SetVaName(std::string va_name) {
     do_in_main_thread(
         base::BindOnce(&VapServerImpl::set_va_name, base::Unretained(this), va_name));
   }

   void NotifyVaSessionInitialized(RawAddress bda) {
     bool is_success = true;
     log::info("NotifyVaSessionInitialized:, bda", bda);

     if (remote_clients_.find(bda) != remote_clients_.end()) {
       RemoteClient* remote_client = &remote_clients_[bda];
       uint16_t ccc_vas_control_point = remote_client->ccc_values_[kVasControlPointCharacteristic];
       ResponseCodeValue rsp_code_value =
           is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
       // Send VAS Control Point notification
       SendVasControlPointNotification(remote_client, rsp_code_value, ccc_vas_control_point);

       int group_id;
       auto csis_api = CsisClient::Get();
       if (csis_api == nullptr) {
         log::error("csis api is null");
         return;
       }

       group_id = csis_api->GetGroupId(bda, bluetooth::le_audio::uuid::kCapServiceUuid);
       log::info("group_id:{}", group_id);
       if (group_id != bluetooth::groups::kGroupUnknown) {
         std::vector<RawAddress> devices = csis_api->GetDeviceList(group_id);

         for (const auto& device : devices) {
           log::info("NotifyVaSessionInitialized:, device:{}", device);
           if (remote_clients_.find(device) != remote_clients_.end()) {
             RemoteClient* remote_client = &remote_clients_[device];
             uint16_t ccc_va_session_state =
                 remote_client->ccc_values_[kVaSessionStateCharacteristic];

             uint8_t va_session_state =
                 static_cast<uint8_t>(VaSessionState::VA_SESSION_READY);
             // Send VA Session State notification
             SendVaSessionStateNotification(remote_client, ccc_va_session_state,
                 va_session_state, /*is_group_device*/ true);
           }
         }
       }
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
         uint16_t ccc_vas_control_point =
                 remote_client->ccc_values_[kVasControlPointCharacteristic];
         uint16_t ccc_va_session_state =
             remote_client->ccc_values_[kVaSessionStateCharacteristic];
         ResponseCodeValue rsp_code_value =
             is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
         if (remote_client->handling_control_point_command_) {
           // Send VAS Control Point notification
           SendVasControlPointNotification(remote_client, rsp_code_value, ccc_vas_control_point);
         }

         uint8_t session_state = ComputeSessionState(true, is_success);
         // Send VA Session State notification
         SendVaSessionStateNotification(remote_client, ccc_va_session_state, session_state,
             /*is_group_device*/ true);
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
         uint16_t ccc_vas_control_point =
                 remote_client->ccc_values_[kVasControlPointCharacteristic];
         uint16_t ccc_va_session_state =
             remote_client->ccc_values_[kVaSessionStateCharacteristic];
         ResponseCodeValue rsp_code_value =
             is_success ? ResponseCodeValue::SUCCESS : ResponseCodeValue::OPERATION_FALIED;
         if (remote_client->handling_control_point_command_) {
           // Send VAS Control Point notification
           SendVasControlPointNotification(remote_client, rsp_code_value, ccc_vas_control_point);
         }

         uint8_t session_state = ComputeSessionState(false, is_success);
         // Send VA Session State notification
         SendVaSessionStateNotification(remote_client, ccc_va_session_state, session_state,
             /*is_group_device*/ true);
       }
     }
   }

   void SendVasControlPointNotification(RemoteClient* remote_client,
                                        ResponseCodeValue rsp_code_value,
                                        uint16_t ccc_vas_control_point) {
     log::info(" conn_id:{}, ccc_vas_cp:{}, rsp_code_value:{}, rsp_code_str:{}",
               remote_client->conn_id_, ccc_vas_control_point, (uint16_t)rsp_code_value,
               GetResponseCodeValueText(rsp_code_value));

     // Send VAS Control Point notification
     if (ccc_vas_control_point != GATT_CLT_CONFIG_NONE) {
       bool use_notification = ccc_vas_control_point & GATT_CLT_CONFIG_NOTIFICATION;
       uint16_t attr_id =
              GetCharacteristic(kVasControlPointCharacteristic)->attribute_handle_;
       std::vector<uint8_t> response(2, 0);
       response[0] = (uint8_t)CtpRespOpcode::RESPONSE_CODE;
       response[1] = (uint8_t)rsp_code_value;
       log::debug("Send VAS Control Point notification");
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
                                       uint8_t va_session_state,
                                       bool is_group_device = false) {
     uint8_t curr_va_session_state = static_cast<uint8_t>(GetVaSessionState());
     log::info(" conn_id:{}, ccc_va_session_state:{}, Curr VA session state: {},"
               " New VA session state:{}, is_group_device: {}", remote_client->conn_id_,
               ccc_va_session_state,
               GetVaSessionStateText(static_cast<VaSessionState>(curr_va_session_state)),
               GetVaSessionStateText(static_cast<VaSessionState>(va_session_state)),
               is_group_device);
     if ((curr_va_session_state == va_session_state) && !is_group_device) {
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

   void SendVaNameNotification(RemoteClient* remote_client,
                               uint16_t ccc_va_name,
                               std::string va_name) {
     log::info(" conn_id:{}, ccc_va_name:{}, VA name: {},",
               remote_client->conn_id_, ccc_va_name,
               va_name);
     if (ccc_va_name != GATT_CLT_CONFIG_NONE) {
       bool use_notification = ccc_va_name & GATT_CLT_CONFIG_NOTIFICATION;
       uint16_t attr_id =
               GetCharacteristic(kVaNameCharacteristic)->attribute_handle_;
       std::vector<uint8_t> value(va_name.begin(), va_name.end());

       log::debug("Send VA Name notification");
       BTA_GATTS_HandleValueIndication(remote_client->conn_id_, attr_id, value, !use_notification);
     }
   }

   void SendVaUuidNotification(RemoteClient* remote_client, uint16_t ccc_va_uuid,
                              std::string va_uuid) {
     log::info(" conn_id:{}, ccc_va_uuid:{}, VA UUID: {},",
               remote_client->conn_id_, ccc_va_uuid, va_uuid);
     if (ccc_va_uuid != GATT_CLT_CONFIG_NONE) {
       bool use_notification = ccc_va_uuid & GATT_CLT_CONFIG_NOTIFICATION;
       uint16_t attr_id =
               GetCharacteristic(kVaUuidCharacteristic)->attribute_handle_;
       std::string va_uuid_str = va_uuid.substr(0, 16);
       std::vector<uint8_t> value(va_uuid_str.begin(), va_uuid_str.end());

       log::debug("Send VA UUID notification");
       BTA_GATTS_HandleValueIndication(remote_client->conn_id_, attr_id, value, !use_notification);
     }
   }

   void OnGattConnect(const RawAddress& remote_bda, tCONN_ID conn_id, tBT_TRANSPORT transport) {
     log::info("Address: {}, conn_id:{}", remote_bda, conn_id);
     if (transport == BT_TRANSPORT_BR_EDR) {
       log::warn("Skip BE/EDR connection");
       return;
     }

     if (remote_clients_.find(remote_bda) == remote_clients_.end()) {
       log::warn("Create new remote_client");
     }
     remote_clients_[remote_bda].conn_id_ = conn_id;

     if (GetVaSessionState() != VaSessionState::VA_SESSION_UNAVAILABLE) {
       SetVaSessionState(VaSessionState::VA_SESSION_RESET);
     }
   }

   void OnGattMtuChanged(tCONN_ID /*conn_id*/, const RawAddress& remote_bda, uint16_t mtu) {
     log::info("mtu is changed as {}", mtu);
     auto it = remote_clients_.find(remote_bda);
     if (it != remote_clients_.end()) {
       it->second.mtu_ = mtu;
     }
   }

   void OnGattDisconnect(const RawAddress& remote_bda, tCONN_ID conn_id) {
     log::info("Address: {}, conn_id:{}", remote_bda, conn_id);
     remote_clients_.erase(remote_bda);
   }

   void OnReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                             uint16_t handle, uint16_t offset, bool /*is_long*/) {
     log::info("read_req_handle: 0x{:04x}, offset: 0x{:04x}", handle, offset);

     std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
     p_msg->attr_value.handle = handle;
     if (characteristics_.find(handle) == characteristics_.end()) {
       log::error("Invalid handle 0x{:04x}", handle);
       BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
       return;
     }

     auto uuid = characteristics_[handle].uuid_;
     log::info("Read uuid, {}", getUuidName(uuid));
     if (remote_clients_.find(remote_bda) == remote_clients_.end()) {
       log::warn("Can't find remote_client for {}", remote_bda);
       BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
       return;
     }
     RemoteClient* remote_client = &remote_clients_[remote_bda];

     // Check Characteristic UUIDs of GVAS service
     switch (uuid.As16Bit()) {
       case kVaNameCharacteristic16bit: {
         std::string service_name = va_name_;
         std::vector<uint8_t> svc_name(service_name.begin(), service_name.end());
         log::info("svc_name: {}", svc_name.size());

         // Copy from the offset
         size_t copy_len = 0;
         if (offset < svc_name.size()) {
           copy_len = std::min((size_t)(svc_name.size() - offset), (size_t)remote_client->mtu_);
           memcpy(p_msg->attr_value.value, svc_name.data() + offset, copy_len);
         }
         p_msg->attr_value.len = copy_len;
       } break;
       case kVaUuidCharacteristic16bit: {
         // Use VA name as VA UUID
         std::string va_uuid_str = va_name_.substr(0, kVaUuidSize);
         std::vector<uint8_t> va_uuid(va_uuid_str.begin(), va_uuid_str.end());

         p_msg->attr_value.len = kVaUuidSize;
         memcpy(p_msg->attr_value.value, va_uuid.data(), kVaUuidSize);
       } break;
       case kVaCcidCharacteristic16bit: {
         p_msg->attr_value.len = 1;
         memcpy(p_msg->attr_value.value, &kVapCcid, sizeof(uint8_t));
       } break;
       case kVaSessionStateCharacteristic16bit: {
         p_msg->attr_value.len = 1;
         memcpy(p_msg->attr_value.value, &va_session_state_, sizeof(uint8_t));
       } break;
       case kVaSupportedFeaturesCharacteristic16bit: {
         p_msg->attr_value.len = 1;
         memcpy(p_msg->attr_value.value, &kVaSupportedFeatures, sizeof(uint8_t));
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
     VapCharacteristic* characteristic = GetCharacteristicByCccHandle(handle);
     if (characteristic == nullptr) {
       log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", handle);
       BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
       return;
     }
     log::info("Read CCC for uuid, {}", getUuidName(characteristic->uuid_));
     uint16_t ccc_value = 0;
     if (remote_clients_.find(remote_bda) != remote_clients_.end()) {
       ccc_value = remote_clients_[remote_bda].ccc_values_[characteristic->uuid_];
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
     log::info("Write uuid, {}", getUuidName(uuid));

     // Check Characteristic UUID
     switch (uuid.As16Bit()) {
       case kVasControlPointCharacteristic16bit: {
         if (remote_clients_.find(remote_bda) == remote_clients_.end()) {
           log::warn("Can't find remote_clients for {}", remote_bda);
           BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
           return;
         }
         RemoteClient* remote_client = &remote_clients_[remote_bda];
         if (need_rsp) {
           BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
         }
         HandleControlPoint(remote_bda, remote_client, value, len);
       } break;
       default:
         log::warn("Unhandled uuid {}", uuid.ToString());
         BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
         return;
     }
   }

   void OnWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                          uint16_t handle, uint16_t /* offset */, bool /*need_rsp */,
                          bool /*is_prep */, uint8_t* value, uint16_t len) {
     log::info("conn_id:{}, handle:0x{:04x}, len:{}", conn_id, handle, len);

     std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
     p_msg->handle = handle;

     // Only Client Characteristic Configuration (CCC) descriptor is expected
     VapCharacteristic* characteristic = GetCharacteristicByCccHandle(handle);
     if (characteristic == nullptr) {
       log::warn("Can't find Characteristic for CCC Descriptor, handle 0x{:04x}", handle);
       BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
       return;
     }

     if (remote_clients_.find(remote_bda) == remote_clients_.end()) {
       log::warn("Can't find tracker for remote_bda {}", remote_bda);
       BTA_GATTS_SendRsp(conn_id, trans_id, GATT_ILLEGAL_PARAMETER, std::move(p_msg));
       return;
     }
     uint16_t ccc_value;
     STREAM_TO_UINT16(ccc_value, value);

     remote_clients_[remote_bda].ccc_values_[characteristic->uuid_] = ccc_value;
     log::info("Write CCC for {}, conn_id:{}, value:0x{:04x}", getUuidName(characteristic->uuid_),
               conn_id, ccc_value);
     BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
   }

   void DebugDump(int fd) {
     std::stringstream stream;

     dprintf(fd, "VAP Server Manager:\n");
     stream << "    VA Name: " << +va_name_.c_str() << "\n"
            << "    VA Session State: " << +GetVaSessionStateText(va_session_state_).c_str()<< "\n"
            << "    VAP CCID: " << +kVapCcid << "\n"
            << "    VA Supported Features: " << +kVaSupportedFeatures << "\n"
            << "    VAP GATT Server IF: " << +server_if_ << "\n";
     for (auto& [address, remote_client] : remote_clients_) {
       stream << "    Remote Client: " << address.ToString() << "\n";
       stream << "    Remote Client MTU: " << remote_client.mtu_ << "\n";
       stream << "    Remote Client conn_id: " << remote_client.conn_id_ << "\n";
       stream << "    CCCD VAS Control Point: "
              << remote_client.ccc_values_[kVasControlPointCharacteristic] << "\n";
       stream << "    CCCD VA Session State: "
              << remote_client.ccc_values_[kVaSessionStateCharacteristic] << "\n";
       stream << "    Handling Control Point Command:  "
              << remote_client.handling_control_point_command_ << "\n";
     }

     dprintf(fd, "%s", stream.str().c_str());
     dprintf(fd, "\n");
   }

   void HandleControlPoint(RawAddress bda, RemoteClient* remote_client, uint8_t* value,
                           uint16_t len) {
     ControlPointCommand command;
     uint16_t ccc_vas_control_point = GATT_CLT_CONFIG_NONE;
     VaSessionState va_session_state = GetVaSessionState();

     ccc_vas_control_point = remote_client->ccc_values_[kVasControlPointCharacteristic];
     if (ccc_vas_control_point == GATT_CLT_CONFIG_NONE) {
       log::warn(" VAS Control Point CCCD not configured by remote client, ignore the command");
       return;
     }

     ControlPointResponse cp_rsp =
             ValidateControlPointOperation(&command, value, len, va_session_state);

     if (!command.isValid_) {
       SendVasControlPointNotification(remote_client, cp_rsp.code_value_, ccc_vas_control_point);
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

   void NotifyVaSessionStateForPts(RawAddress bda, VaSessionState state) {
     log::info("bda: {}, state: {}", bda, static_cast<int>(state));

     if (remote_clients_.find(bda) != remote_clients_.end()) {
       RemoteClient* remote_client = &remote_clients_[bda];
       uint16_t ccc_vas_control_point = remote_client->ccc_values_[kVasControlPointCharacteristic];
       uint16_t ccc_va_session_state = remote_client->ccc_values_[kVaSessionStateCharacteristic];
       ResponseCodeValue rsp_code_value = ResponseCodeValue::SUCCESS;

       // Send VAS Control Point notification
       SendVasControlPointNotification(remote_client, rsp_code_value, ccc_vas_control_point);

       uint8_t va_session_state = static_cast<uint8_t>(state);
       // Send VA Session State notification
       SendVaSessionStateNotification(remote_client, ccc_va_session_state, va_session_state);
     }
   }

   void OnStartVaSession(RawAddress bda) {
     log::info("bda:{}", bda);

     if (bluetooth::common::IsPtsTestMode()) {
       NotifyVaSessionStateForPts(bda, VaSessionState::VA_SESSION_ACTIVE);
     } else {
       callbacks_->OnStartVaSession(bda);
     }
   }

   void OnStopVaSession(RawAddress bda) {
     log::info("bda:{}", bda);

     if (bluetooth::common::IsPtsTestMode()) {
       NotifyVaSessionStateForPts(bda, VaSessionState::VA_SESSION_READY);
     } else {
       callbacks_->OnStopVaSession(bda);
     }
   }

   void OnInitializeVaSession(RawAddress bda) {
     log::info("bda:{}", bda);
     NotifyVaSessionInitialized(bda);
     SetVaSessionState(VaSessionState::VA_SESSION_READY);
   }

   VapCharacteristic* GetCharacteristic(Uuid uuid) {
     for (auto& [attribute_handle, characteristic] : characteristics_) {
       if (characteristic.uuid_ == uuid) {
         return &characteristic;
       }
     }
     return nullptr;
   }

   VapCharacteristic* GetCharacteristicByCccHandle(uint16_t descriptor_handle) {
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
   std::unordered_map<uint16_t, VapCharacteristic> characteristics_;
   // A map to associate remote client with address
   std::unordered_map<RawAddress, RemoteClient> remote_clients_;
   bluetooth::vap::VapServerCallbacks* callbacks_;
   std::string va_name_;
   VaSessionState va_session_state_;
 };

 }  // namespace

 bluetooth::vap::VapServer* bluetooth::vap::GetVapServer() {
   if (instance == nullptr) {
     instance = new VapServerImpl();
   }
   return instance;
 }
