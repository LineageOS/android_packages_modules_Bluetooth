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

#include "bta_gatt_api_mock.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include "bta/gatt/bta_gattc_int.h"

using namespace bluetooth;

static gatt::MockBtaGattInterface* gatt_interface = nullptr;
static gatt::MockBtaGattServerInterface* gatt_server_interface = nullptr;

void gatt::SetMockBtaGattInterface(MockBtaGattInterface* mock_bta_gatt_interface) {
  gatt_interface = mock_bta_gatt_interface;
}

void BTA_GATTC_AppRegister(const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                           BtaAppRegisterCallback cb, bool eatt_support) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->AppRegister(name, p_client_cb, std::move(cb), eatt_support);
}

void BTA_GATTC_AppDeregister(tGATT_IF client_if) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->AppDeregister(client_if);
}

void BTA_GATTC_Disable(void) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->Disable();
}

void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda,
                    tBTM_BLE_CONN_TYPE connection_type) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->Open(client_if, remote_bda, connection_type);
}

void BTA_GATTC_Open(tGATT_IF client_if, const RawAddress& remote_bda, tBLE_ADDR_TYPE addr_type,
                    tBTM_BLE_CONN_TYPE connection_type, tBT_TRANSPORT transport,
                    uint16_t preferred_mtu, bool prefer_relax_mode, bool auto_mtu_enabled) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->Open(client_if, remote_bda, addr_type, connection_type, transport, preferred_mtu,
                       prefer_relax_mode, auto_mtu_enabled);
}

void BTA_GATTC_CancelOpen(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->CancelOpen(client_if, remote_bda, is_direct);
}

void BTA_GATTC_Close(uint16_t conn_id) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->Close(conn_id);
}

void BTA_GATTC_ServiceSearchRequest(uint16_t conn_id) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ServiceSearchRequest(conn_id);
}

void BTA_GATTC_DiscoverServiceByUuid(tCONN_ID conn_id, const bluetooth::Uuid& srvc_uuid) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->DiscoverServiceByUuid(conn_id, srvc_uuid);
}

void BTA_GATTC_SendIndConfirm(uint16_t conn_id, uint16_t cid) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->SendIndConfirm(conn_id, cid);
}

const std::list<gatt::Service>* BTA_GATTC_GetServices(uint16_t conn_id) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->GetServices(conn_id);
}

const gatt::Characteristic* BTA_GATTC_GetCharacteristic(uint16_t conn_id, uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->GetCharacteristic(conn_id, handle);
}

const gatt::Service* BTA_GATTC_GetOwningService(uint16_t conn_id, uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->GetOwningService(conn_id, handle);
}

void BTA_GATTC_ReadCharacteristic(tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req,
                                  GATT_READ_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ReadCharacteristic(conn_id, handle, auth_req, callback, cb_data);
}

void BTA_GATTC_ReadUsingCharUuid(tCONN_ID conn_id, const bluetooth::Uuid& uuid, uint16_t s_handle,
                                 uint16_t e_handle, tGATT_AUTH_REQ auth_req,
                                 GATT_READ_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ReadUsingCharUuid(conn_id, uuid, s_handle, e_handle, auth_req, callback, cb_data);
}

void BTA_GATTC_ReadCharDescr(tCONN_ID conn_id, uint16_t handle, tGATT_AUTH_REQ auth_req,
                             GATT_READ_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ReadCharDescr(conn_id, handle, auth_req, callback, cb_data);
}

void BTA_GATTC_WriteCharValue(tCONN_ID conn_id, uint16_t handle, tGATT_WRITE_TYPE write_type,
                              std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req,
                              GATT_WRITE_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->WriteCharValue(conn_id, handle, write_type, value, auth_req, callback, cb_data);
}

void BTA_GATTC_WriteCharDescr(tCONN_ID conn_id, uint16_t handle, std::vector<uint8_t> value,
                              tGATT_AUTH_REQ auth_req, GATT_WRITE_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->WriteCharDescr(conn_id, handle, value, auth_req, callback, cb_data);
}

void BTA_GATTC_PrepareWrite(tCONN_ID conn_id, uint16_t handle, uint16_t offset,
                            std::vector<uint8_t> value, tGATT_AUTH_REQ auth_req,
                            GATT_WRITE_OP_CB callback, void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->PrepareWrite(conn_id, handle, offset, value, auth_req, callback, cb_data);
}

void BTA_GATTC_ExecuteWrite(tCONN_ID conn_id, bool is_execute) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ExecuteWrite(conn_id, is_execute);
}

void BTA_GATTC_ReadMultiple(tCONN_ID conn_id, tBTA_GATTC_MULTI& p_read_multi, bool variable_len,
                            tGATT_AUTH_REQ auth_req, GATT_READ_MULTI_OP_CB callback,
                            void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ReadMultiple(conn_id, p_read_multi, variable_len, auth_req, callback, cb_data);
}

tGATT_STATUS BTA_GATTC_RegisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->RegisterForNotifications(client_if, remote_bda, handle);
}

tGATT_STATUS BTA_GATTC_DeregisterForNotifications(tGATT_IF client_if, const RawAddress& remote_bda,
                                                  uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->DeregisterForNotifications(client_if, remote_bda, handle);
}

void BTA_GATTC_ConfigureMTU(tCONN_ID conn_id, uint16_t mtu) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ConfigureMTU(conn_id, mtu);
}

void BTA_GATTC_ConfigureMTU(tCONN_ID conn_id, uint16_t mtu, GATT_CONFIGURE_MTU_OP_CB callback,
                            void* cb_data) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ConfigureMTU(conn_id, mtu, callback, cb_data);
}

void BTA_GATTC_Refresh(tGATT_IF client_if, const RawAddress& remote_bda) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->Refresh(client_if, remote_bda);
}

void BTA_GATTC_GetGattDb(tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                         btgatt_db_element_t** db, int* count) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->GetGattDb(conn_id, start_handle, end_handle, db, count);
}

const gatt::Descriptor* BTA_GATTC_GetDescriptor(tCONN_ID conn_id, uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->GetDescriptor(conn_id, handle);
}

const gatt::Characteristic* BTA_GATTC_GetOwningCharacteristic(tCONN_ID conn_id, uint16_t handle) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  return gatt_interface->GetOwningCharacteristic(conn_id, handle);
}

void BTA_GATTC_OffloadCharacteristics(tCONN_ID conn_id, std::vector<btgatt_db_element_t> service,
                                      uint64_t endpoint_id, uint64_t hub_id, int uid,
                                      std::string attribution_tag,
                                      std::promise<btgatt_offload_result_t> promise) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->OffloadCharacteristics(conn_id, service, endpoint_id, hub_id, uid,
                                         attribution_tag, std::move(promise));
}

void BTA_GATTC_UnoffloadCharacteristics(tCONN_ID conn_id, int session_id) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->UnoffloadCharacteristics(conn_id, session_id);
}

void BTA_GATT_Init_gatt_pm_callbacks() {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->InitGattPmCallbacks();
}

void bta_gattc_link_cache_for_bonded_device(const RawAddress& bd_addr) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->LinkCacheForBondedDevice(bd_addr);
}

void bta_gatt_client_dump(int fd) {
  log::assert_that(gatt_interface != nullptr, "Mock GATT interface not set!");
  gatt_interface->ClientDump(fd);
}

void BTA_GATTS_Disable(void) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  gatt_server_interface->Disable();
}
void BTA_GATTS_AppDeregister(tGATT_IF server_if) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  gatt_server_interface->AppDeregister(server_if);
}
tGATT_IF BTA_GATTS_AppRegister(const bluetooth::Uuid& app_uuid,
                               const bluetooth::stack::tGATT_CBACK* p_cback, bool eatt_support) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  return gatt_server_interface->AppRegister(app_uuid, p_cback, eatt_support);
}
tGATT_STATUS BTA_GATTS_AddService(tGATT_IF server_if, std::vector<btgatt_db_element_t>* service) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  return gatt_server_interface->AddService(server_if, service);
}
bool BTA_GATTS_DeleteService(tGATT_IF server_if, uint16_t service_id) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  return gatt_server_interface->DeleteService(server_if, service_id);
}
tGATT_STATUS BTA_GATTS_HandleValueIndication(uint16_t conn_id, uint16_t attr_id,
                                             std::vector<uint8_t> value, bool need_confirm) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  return gatt_server_interface->HandleValueIndication(conn_id, attr_id, value, need_confirm);
}
void BTA_GATTS_SendRsp(uint16_t conn_id, uint32_t trans_id, tGATT_STATUS status,
                       std::unique_ptr<tGATTS_RSP> p_msg) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  gatt_server_interface->SendRsp(conn_id, trans_id, status, std::move(p_msg));
}
void BTA_GATTS_InitBonded(void) {
  log::assert_that(gatt_server_interface != nullptr, "Mock GATT server interface not set!");
  gatt_server_interface->InitBonded();
}

void gatt::SetMockBtaGattServerInterface(
        MockBtaGattServerInterface* mock_bta_gatt_server_interface) {
  gatt_server_interface = mock_bta_gatt_server_interface;
}
