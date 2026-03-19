/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
#pragma once

#include <base/functional/callback.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>
#include <gmock/gmock.h>

#include "bta_gatt_api.h"

namespace gatt {

class BtaGattInterface {
public:
  virtual void AppRegister(const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                           BtaAppRegisterCallback cb, bool eatt_support) = 0;
  virtual void AppDeregister(tGATT_IF client_if) = 0;
  virtual void Open(tGATT_IF client_if, const RawAddress& remote_bda,
                    tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                    bool auto_mtu_enabled) = 0;
  virtual void Open(tGATT_IF client_if, const RawAddress& remote_bda,
                    tBTM_BLE_CONN_TYPE connection_type) = 0;
  virtual void CancelOpen(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) = 0;
  virtual void Close(uint16_t conn_id) = 0;
  virtual void ServiceSearchRequest(uint16_t conn_id, const bluetooth::Uuid* p_srvc_uuid) = 0;
  virtual void SendIndConfirm(uint16_t conn_id, uint16_t cid) = 0;
  virtual const std::list<Service>* GetServices(uint16_t conn_id) = 0;
  virtual const Characteristic* GetCharacteristic(uint16_t conn_id, uint16_t handle) = 0;
  virtual const Service* GetOwningService(uint16_t conn_id, uint16_t handle) = 0;
  virtual void ReadCharacteristic(tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req,
                                  GATT_READ_OP_CB callback, void* cb_data) = 0;
  virtual void WriteCharValue(tCONN_ID conn_id, uint16_t handle, tGATT_WRITE_TYPE write_type,
                              std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req,
                              GATT_WRITE_OP_CB callback, void* cb_data) = 0;
  virtual void WriteCharDescr(tCONN_ID conn_id, uint16_t handle, std::vector<uint8_t> value,
                              tGATT_AUTH_REQ auth_req, GATT_WRITE_OP_CB callback,
                              void* cb_data) = 0;
  virtual tGATT_STATUS RegisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                uint16_t handle) = 0;
  virtual tGATT_STATUS DeregisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                  uint16_t handle) = 0;
  virtual void ConfigureMTU(tCONN_ID conn_id, uint16_t mtu) = 0;
  virtual ~BtaGattInterface() = default;
};

class MockBtaGattInterface : public BtaGattInterface {
public:
  MOCK_METHOD((void), AppRegister,
              (const std::string& name, tBTA_GATTC_CBACK* p_client_cb, BtaAppRegisterCallback cb,
               bool eatt_support),
              (override));
  MOCK_METHOD((void), AppDeregister, (tGATT_IF client_if), (override));
  MOCK_METHOD((void), Open,
              (tGATT_IF client_if, const RawAddress& remote_bda, tBTM_BLE_CONN_TYPE connection_type,
               tBT_TRANSPORT transport, bool auto_mtu_enabled),
              (override));
  MOCK_METHOD((void), Open,
              (tGATT_IF client_if, const RawAddress& remote_bda,
               tBTM_BLE_CONN_TYPE connection_type));
  MOCK_METHOD((void), CancelOpen, (tGATT_IF client_if, const RawAddress& remote_bda,
               bool is_direct));
  MOCK_METHOD((void), Close, (uint16_t conn_id));
  MOCK_METHOD((void), ServiceSearchRequest, (uint16_t conn_id, const bluetooth::Uuid* p_srvc_uuid));
  MOCK_METHOD((void), SendIndConfirm, (uint16_t conn_id, uint16_t cid), (override));
  MOCK_METHOD((std::list<Service>*), GetServices, (uint16_t conn_id));
  MOCK_METHOD((const Characteristic*), GetCharacteristic, (uint16_t conn_id, uint16_t handle));
  MOCK_METHOD((const Service*), GetOwningService, (uint16_t conn_id, uint16_t handle));
  MOCK_METHOD((void), ReadCharacteristic,
              (tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req, GATT_READ_OP_CB callback,
               void* cb_data));
  MOCK_METHOD((void), WriteCharValue,
              (tCONN_ID conn_id, uint16_t handle, tGATT_WRITE_TYPE write_type,
               std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req, GATT_WRITE_OP_CB callback,
               void* cb_data));
  MOCK_METHOD((void), WriteCharDescr,
              (tCONN_ID conn_id, uint16_t handle, std::vector<uint8_t> value,
               tGATT_AUTH_REQ auth_req, GATT_WRITE_OP_CB callback, void* cb_data));
  MOCK_METHOD((tGATT_STATUS), RegisterForNotifications,
              (tGATT_IF client_if, const RawAddress& remote_bda, uint16_t handle));
  MOCK_METHOD((tGATT_STATUS), DeregisterForNotifications,
              (tGATT_IF client_if, const RawAddress& remote_bda, uint16_t handle));
  MOCK_METHOD((void), ConfigureMTU, (tCONN_ID conn_id, uint16_t mtu));
};

/**
 * Set the {@link MockBtaGattInterface} for testing
 *
 * @param mock_bta_gatt_interface pointer to mock bta gatt interface,
 * could be null
 */
void SetMockBtaGattInterface(MockBtaGattInterface* mock_bta_gatt_interface);

class BtaGattServerInterface {
public:
  virtual void Disable() = 0;
  virtual void AppRegister(const bluetooth::Uuid& /* app_uuid */,
                           const bluetooth::stack::tGATT_CBACK* /* p_cback */,
                           bool /* eatt_support */,
                           void (*p_reg_cb)(tGATT_STATUS status, tGATT_IF server_if,
                                            const bluetooth::Uuid& uuid)) = 0;
  virtual void AppDeregister(tGATT_IF server_if) = 0;
  virtual void AddService(tGATT_IF /* server_if */, std::vector<btgatt_db_element_t> /* service */,
                          BTA_GATTS_AddServiceCb /* cb */) = 0;
  virtual bool DeleteService(tGATT_IF /* server_if */, uint16_t /* service_id */) = 0;
  virtual tGATT_STATUS HandleValueIndication(uint16_t /* conn_id */, uint16_t /* attr_id */,
                                             std::vector<uint8_t> /* value */,
                                             bool /* need_confirm */) = 0;
  virtual void SendRsp(uint16_t /* conn_id */, uint32_t /* trans_id */, tGATT_STATUS /* status */,
                       std::unique_ptr<tGATTS_RSP> /* p_msg */) = 0;
  virtual void InitBonded() = 0;
  virtual ~BtaGattServerInterface() = default;
};

class MockBtaGattServerInterface : public BtaGattServerInterface {
public:
  MOCK_METHOD((void), Disable, ());
  MOCK_METHOD(
          void, AppRegister,
          (const bluetooth::Uuid& uuid, const bluetooth::stack::tGATT_CBACK* cb, bool eatt_support,
           void (*p_reg_cb)(tGATT_STATUS status, tGATT_IF server_if, const bluetooth::Uuid& uuid)),
          (override));
  MOCK_METHOD((void), AppDeregister, (tGATT_IF server_if), (override));
  MOCK_METHOD((void), AddService,
              (tGATT_IF server_if, std::vector<btgatt_db_element_t> service,
               BTA_GATTS_AddServiceCb cb),
              (override));
  MOCK_METHOD(bool, DeleteService, (tGATT_IF server_if, uint16_t service_id));
  MOCK_METHOD(tGATT_STATUS, HandleValueIndication,
              (uint16_t conn_id, uint16_t attr_id, std::vector<uint8_t> value, bool need_confirm));
  MOCK_METHOD(void, SendRsp,
              (uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
               std::unique_ptr<tGATTS_RSP> p_msg));
  MOCK_METHOD((void), InitBonded, ());
};

/**
 * Set the {@link MockBtaGattServerInterface} for testing
 *
 * @param mock_bta_gatt_server_interface pointer to mock bta gatt server interface,
 * could be null
 */
void SetMockBtaGattServerInterface(MockBtaGattServerInterface* mock_bta_gatt_server_interface);

}  // namespace gatt
