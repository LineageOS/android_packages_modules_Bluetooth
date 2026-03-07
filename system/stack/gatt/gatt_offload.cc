/*
 * Copyright (C) 2025 The Android Open Source Project
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

/******************************************************************************
 *
 *  this file contains the GATT offload hal functions
 *
 ******************************************************************************/
#define LOG_TAG "gatt_offload"

#include <bluetooth/log.h>

#include "bluetooth/metrics/os_metrics.h"
#include "common/time_util.h"
#include "gd/os/rand.h"
#include "lpp/lpp_offload_interface.h"
#include "main/shim/entry.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;
using android::bluetooth::gatt::GattOffloadErrorEnum;
using android::bluetooth::gatt::GattOffloadSessionStateEnum;
using android::bluetooth::gatt::GattRoleEnum;
using hci::Address;

static bool add_gatt_db_elements_to_session(btgatt_db_element_t* service, size_t elements_count,
                                            bluetooth::hal::GattSession& hal_session);
static void add_hal_session(tGATT_OFFLOAD_SESSION session);
static bool check_duplicate_session(bluetooth::hal::GattSession& hal_session, tCONN_ID conn_id);
static bool check_offload_server_permission(tGATT_TCB* p_tcb, btgatt_db_element_t* service,
                                            size_t elements_count);
static bool contains_common_characteristic(bluetooth::hal::GattSession& s1,
                                           bluetooth::hal::GattSession& s2);
static bool contains_service_by_attribute_handle(hal::GattSession& hal_session, uint16_t handle);
static bool contains_session_by_id(uint16_t session_id);
static uint16_t create_offload_session_id();
static tCONN_ID get_conn_id_by_session_id(uint16_t session_id);
static tGATT_STATUS gatt_offload_characteristics_impl(tCONN_ID conn_id, bool is_server,
                                                      btgatt_db_element_t* service,
                                                      size_t elements_count, uint64_t endpoint_id,
                                                      uint64_t hub_id,
                                                      bluetooth::hal::GattSession& hal_session);
static std::optional<std::promise<btgatt_offload_result_t>>& get_promise_by_session_id(
        uint16_t session_id);
static void handle_error_report_database_out_of_sync(uint16_t acl_connection_handle,
                                                     uint16_t local_cid);
static void handle_error_report_protocol_violation(uint16_t acl_connection_handle,
                                                   uint16_t local_cid);
static void handle_error_report_response_timeout(uint16_t acl_connection_handle,
                                                 uint16_t local_cid);
static void on_gatt_offload_register_service_complete(uint16_t session_id, hal::GattStatus status);
static void on_gatt_offload_unregister_service_complete(uint16_t session_id);
static void on_gatt_offload_clear_services_complete(uint16_t acl_connection_handle);
static GattOffloadErrorEnum gatt_status_to_offload_error_enum(tGATT_STATUS status);
static GattOffloadErrorEnum gatt_hal_error_to_offload_error_enum(hal::GattError error);
static void on_gatt_offload_error_report(uint16_t acl_connection_handle, uint16_t local_cid,
                                         hal::GattError error);
static void remove_hal_session(uint16_t session_id, GattOffloadSessionStateEnum state,
                               GattOffloadErrorEnum error_code);
static Address get_peer_address(uint16_t conn_id);
static void send_offload_session_register_complete(uint16_t session_id, tGATT_STATUS status,
                                                   std::promise<btgatt_offload_result_t>& promise);
static bool try_sessions_by_acl_handle_to_unoffload(uint16_t acl_connection_handle,
                                                    hal::GattError reason);
static bool try_session_by_id_conn_id_to_unoffload(uint16_t session_id, tCONN_ID conn_id);

class GattOffloadHalCallback : public hal::GattHalCallback {
public:
  void registerServiceComplete(uint16_t session_id, hal::GattStatus status) const override {
    log::info("session_id: {}, status: {}", session_id, static_cast<int>(status));
    do_in_main_thread(
            base::BindOnce(on_gatt_offload_register_service_complete, session_id, status));
  }

  void unregisterServiceComplete(uint16_t session_id) const override {
    log::info("session_id: {}", session_id);
    do_in_main_thread(base::BindOnce(on_gatt_offload_unregister_service_complete, session_id));
  }

  void clearServicesComplete(uint16_t acl_connection_handle) const override {
    log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
    do_in_main_thread(
            base::BindOnce(on_gatt_offload_clear_services_complete, acl_connection_handle));
  }

  void errorReport(uint16_t acl_connection_handle, uint16_t local_cid,
                   hal::GattError error) const override {
    log::info("acl_connection_handle: 0x{:x} local_cid: 0x{:x} error: {}", acl_connection_handle,
              local_cid, static_cast<int>(error));
    do_in_main_thread(
            base::BindOnce(on_gatt_offload_error_report, acl_connection_handle, local_cid, error));
  }
};

/*******************************************************************************
 *
 * Function         gatt_offload_init
 *
 * Description      This function is called to initialize GATT offload interface.
 *
 * Returns          true if initialized successful
 *
 ******************************************************************************/
bool gatt_offload_init() {
  log::info("");
  auto lpp_offload_manager_interface = bluetooth::shim::GetLppOffloadManager();
  if (lpp_offload_manager_interface == nullptr) {
    log::warn("GetLppOffloadManager() returned nullptr!");
    return false;
  }
  static GattOffloadHalCallback gatt_offload_hal_cb;
  if (!lpp_offload_manager_interface->InitializeGattHal(&gatt_offload_hal_cb)) {
    log::warn("InitializeGattHal() failed!");
    return false;
  }
  return true;
}

/*******************************************************************************
 *
 * Function         gatt_offload_characteristics
 *
 * Description      This function is called to offload characteristics.
 *
 ******************************************************************************/
void gatt_offload_characteristics(tCONN_ID conn_id, bool is_server, btgatt_db_element_t* service,
                                  size_t elements_count, uint64_t endpoint_id, uint64_t hub_id,
                                  int uid, std::string attribution_tag,
                                  std::promise<btgatt_offload_result_t> promise) {
  bluetooth::hal::GattSession hal_session;
  tGATT_STATUS status = gatt_offload_characteristics_impl(
          conn_id, is_server, service, elements_count, endpoint_id, hub_id, hal_session);
  if (status != tGATT_STATUS::GATT_SUCCESS) {
    send_offload_session_register_complete(hal_session.id, status, promise);
    return;
  }
  add_hal_session(tGATT_OFFLOAD_SESSION{.hal_session = hal_session,
                                        .conn_id = conn_id,
                                        .promise_opt = std::move(promise),
                                        .uid = uid,
                                        .attribution_tag = std::move(attribution_tag)});
}

/*******************************************************************************
 *
 * Function         gatt_unoffload_session
 *
 * Description      This function is called to unoffload session.
 *
 ******************************************************************************/
void gatt_unoffload_session(tCONN_ID conn_id, uint16_t session_id, tGATT_STATUS status) {
  log::info("conn_id: {}, session_id: {}, status: {}", conn_id, session_id,
            static_cast<int>(status));
  if (!try_session_by_id_conn_id_to_unoffload(session_id, conn_id)) {
    return;
  }
  gatt_cb.offload_sessions[session_id].status = status;
  bluetooth::shim::GetLppOffloadManager()->UnregisterGattService(session_id);
}

/*******************************************************************************
 *
 * Function         gattc_inform_notification_handle
 *
 * Description      This function is invoked when a gatt client registers notification or indication
 *characteristic.
 *
 *                  Its primary responsibility is to manage the state of any existing offloaded
 *                  sessions. If the characteristic for which the client is attempting to register
 *                  was previously offloaded to a endpoint, the associated offload session will be
 *                  automatically unoffloaded (terminated). This ensures that the host-side GATT
 *                  stack takes precedence and properly handles the notification/indication
 *                  subscriptions for characteristics that the application intends to directly
 *                  manage.
 *
 ******************************************************************************/
void gattc_inform_notification_handle(tGATT_TCB* p_tcb, uint16_t handle) {
  log::info("remote_bda: {}, handle: 0x{:x}", p_tcb->peer_bda, handle);
  auto iter = gatt_cb.offload_sessions.begin();
  while (iter != gatt_cb.offload_sessions.end()) {
    tCONN_ID conn_id = iter->second.conn_id;
    hal::GattSession& hal_session = iter->second.hal_session;
    if (hal_session.role != hal::GATT_CLIENT) {
      ++iter;
      continue;
    }
    if (p_tcb->tcb_idx == gatt_get_tcb_idx(conn_id) &&
        contains_service_by_attribute_handle(hal_session, handle)) {
      gatt_unoffload_session(conn_id, hal_session.id, tGATT_STATUS::GATT_BUSY);
    }
    ++iter;
  }
}

/*******************************************************************************
 *
 * Function         gattc_offload_handle_service_changed_indication
 *
 * Description      This function is called to handle the service changed indication.
 *
 ******************************************************************************/
void gattc_offload_handle_service_changed_indication(tGATT_TCB* p_tcb) {
  log::info("remote_bda: {}", p_tcb->peer_bda);
  auto iter = gatt_cb.offload_sessions.begin();
  while (iter != gatt_cb.offload_sessions.end()) {
    tCONN_ID conn_id = iter->second.conn_id;
    hal::GattSession& hal_session = iter->second.hal_session;
    if (hal_session.role != hal::GATT_CLIENT) {
      ++iter;
      continue;
    }
    if (p_tcb->tcb_idx == gatt_get_tcb_idx(conn_id)) {
      gatt_unoffload_session(conn_id, hal_session.id, tGATT_STATUS::GATT_BUSY);
    }
    ++iter;
  }
}

/*******************************************************************************
 *
 * Function         gatt_offload_clear_sessions_by_acl_handle
 *
 * Description      This function is called to clear offload sessions on the acl handle.
 *
 ******************************************************************************/
bool gatt_offload_clear_sessions_by_acl_handle(uint16_t acl_connection_handle,
                                               hal::GattError reason) {
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  if (!try_sessions_by_acl_handle_to_unoffload(acl_connection_handle, reason)) {
    return false;
  }
  bluetooth::shim::GetLppOffloadManager()->ClearGattServices(acl_connection_handle);
  return true;
}

/*******************************************************************************
 *
 * Function         gatt_offload_clear_sessions_by_conn_id
 *
 * Description      This function is called to clear offload sessions on the conn_id.
 *
 *
 ******************************************************************************/
void gatt_offload_clear_sessions_by_conn_id(tCONN_ID conn_id) {
  log::info("conn_id: {}", conn_id);
  auto iter = gatt_cb.offload_sessions.begin();
  while (iter != gatt_cb.offload_sessions.end()) {
    if (conn_id == iter->second.conn_id) {
      log::info("conn_id: {}", iter->second.conn_id);
      gatt_unoffload_session(iter->second.conn_id, iter->second.hal_session.id);
    }
    ++iter;
  }
}

/*******************************************************************************
 *
 * Function     gatt_offload_sessions_dump
 *
 * Description  Print gatt_cb.offload_sessions into dumpsys
 *
 * Returns      void
 *
 ******************************************************************************/
void gatt_offload_sessions_dump(int fd) {
  std::string result;
  auto out = std::back_inserter(result);

  std::map<int, std::vector<const tGATT_OFFLOAD_SESSION*>> sessions_by_uid;

  std::format_to(out, "Number of active offload sessions: {}\n", gatt_cb.offload_sessions.size());
  for (const auto& element : gatt_cb.offload_sessions) {
    sessions_by_uid[element.second.uid].push_back(&element.second);
  }

  for (const auto& pair : sessions_by_uid) {
    std::format_to(out, "  UID: {}\n", pair.first);
    for (const auto* session_ptr : pair.second) {
      const tGATT_OFFLOAD_SESSION& session = *session_ptr;
      std::format_to(out,
                     "    Session ID: {}, Conn ID: {}, ACL Handle: {:04x}, MTU: {}\n"
                     "      GATT Role: {}, Service UUID: {}\n"
                     "      Endpoint ID: {}, Hub ID: {}\n"
                     "      Attribution Tag: {}\n",
                     session.hal_session.id, session.conn_id,
                     session.hal_session.acl_connection_handle, session.hal_session.att_mtu,
                     static_cast<int>(session.hal_session.role), session.hal_session.service_uuid,
                     session.hal_session.endpoint_info.endpoint_id,
                     session.hal_session.endpoint_info.hub_id, session.attribution_tag);

      if (!session.hal_session.characteristics.empty()) {
        std::format_to(out, "      Characteristics:\n");
        for (const auto& characteristic : session.hal_session.characteristics) {
          std::format_to(out,
                         "        - UUID: {}\n"
                         "          Handle: {}, Properties: {:02x}\n",
                         characteristic.uuid, characteristic.value_handle,
                         characteristic.properties);
        }
      }
    }
  }
  dprintf(fd, "GATT offload sessions\n%s\n", result.c_str());
}

static tGATT_STATUS gatt_offload_characteristics_impl(tCONN_ID conn_id, bool is_server,
                                                      btgatt_db_element_t* service,
                                                      size_t elements_count, uint64_t endpoint_id,
                                                      uint64_t hub_id,
                                                      bluetooth::hal::GattSession& hal_session) {
  log::info("conn_id:{}, is_server:{}, endpoint_id:{}, hub_id:{}", conn_id, is_server, endpoint_id,
            hub_id);

  uint8_t tcb_idx = gatt_get_tcb_idx(conn_id);
  tGATT_TCB* p_tcb = gatt_get_tcb_by_idx(tcb_idx);
  if (!p_tcb) {
    log::error("Unknown conn_id: 0x{:x}", conn_id);
    return tGATT_STATUS::GATT_INVALID_HANDLE;
  }
  uint16_t acl_handle = gatt_get_acl_handle_by_tcb(p_tcb);
  if (acl_handle == GATT_INVALID_ACL_HANDLE) {
    log::error("Invalid ACL handle at conn_id: 0x{:x}", conn_id);
    return tGATT_STATUS::GATT_INVALID_HANDLE;
  }
  if (service == nullptr) {
    log::error("Service is nullptr");
    return tGATT_STATUS::GATT_ILLEGAL_PARAMETER;
  }
  if (service->type != BTGATT_DB_PRIMARY_SERVICE) {
    log::error("Invalid service type: {}", service->type);
    return tGATT_STATUS::GATT_ILLEGAL_PARAMETER;
  }
  if (elements_count < 2) {
    log::error("Empty characteristic count. Elements count: {}", elements_count);
    return tGATT_STATUS::GATT_ILLEGAL_PARAMETER;
  }
  if (is_server && !check_offload_server_permission(p_tcb, service, elements_count)) {
    log::error("Failed in permission check at conn_id: 0x{:x}", conn_id);
    return tGATT_STATUS::GATT_INSUF_AUTHENTICATION;
  }
  hal_session.id = create_offload_session_id();
  hal_session.acl_connection_handle = acl_handle;
  hal_session.att_mtu = p_tcb->payload_size;
  hal_session.role = (is_server ? hal::GATT_SERVER : hal::GATT_CLIENT);
  hal_session.endpoint_info.hub_id = hub_id;
  hal_session.endpoint_info.endpoint_id = endpoint_id;
  if (!add_gatt_db_elements_to_session(service, elements_count, hal_session)) {
    log::error("Failed to add GATT database elements to GATT session");
    return tGATT_STATUS::GATT_ILLEGAL_PARAMETER;
  }
  if (check_duplicate_session(hal_session, conn_id)) {
    return tGATT_STATUS::GATT_DUP_REG;
  }
  if (!bluetooth::shim::GetLppOffloadManager()->RegisterGattService(hal_session)) {
    log::error("RegisterGattService() failed!");
    return tGATT_STATUS::GATT_INTERNAL_ERROR;
  }
  return tGATT_STATUS::GATT_SUCCESS;
}

static bool contains_session_by_id(uint16_t session_id) {
  auto iter = gatt_cb.offload_sessions.find(session_id);
  return iter != gatt_cb.offload_sessions.end();
}

static bool try_session_by_id_conn_id_to_unoffload(uint16_t session_id, tCONN_ID conn_id) {
  auto iter = std::find_if(gatt_cb.offload_sessions.begin(), gatt_cb.offload_sessions.end(),
                           [session_id, conn_id](auto& el) {
                             return el.first == session_id && el.second.conn_id == conn_id;
                           });
  if (iter == gatt_cb.offload_sessions.end()) {
    log::warn("session_id: {}, conn_id: {} doesn't exist", session_id, conn_id);
    return false;
  }
  auto& session_data = iter->second;
  if (session_data.in_unregistering_service || session_data.in_clearing_services) {
    log::info("session_id: {}, conn_id: {} unoffloading is already in progress", session_id,
              conn_id);
    return false;
  }
  session_data.in_unregistering_service = true;
  return true;
}

static bool check_offload_server_permission(tGATT_TCB* p_tcb, btgatt_db_element_t* service,
                                            size_t elements_count) {
  tGATT_SEC_FLAG sec_flag;
  uint8_t key_size;
  gatt_sr_get_sec_info(p_tcb->peer_bda, p_tcb->transport, &sec_flag, &key_size);

  btgatt_db_element_t* element = service + 1;
  for (size_t i = 0; i < elements_count - 1; i++, element++) {
    const Uuid& uuid = element->uuid;
    if (element->type == BTGATT_DB_CHARACTERISTIC) {
      log::info("uuid: {}, properties: 0x{:x} permission: 0x{:x}", uuid, element->properties,
                element->permissions);
      if ((element->permissions & GATT_READ_AUTH_REQUIRED) && !sec_flag.is_link_key_known &&
          !sec_flag.is_encrypted) {
        log::error("GATT_INSUF_AUTHENTICATION uuid: {}", uuid);
        return false;
      }

      if ((element->permissions & GATT_READ_MITM_REQUIRED) && !sec_flag.is_link_key_authed) {
        log::error("GATT_INSUF_AUTHENTICATION: MITM Required uuid: {}", uuid);
        return false;
      }

      if ((element->permissions & GATT_READ_ENCRYPTED_REQUIRED) && !sec_flag.is_encrypted) {
        log::error("GATT_INSUF_ENCRYPTION uuid: {}", uuid);
        return false;
      }

      if ((element->permissions & GATT_WRITE_AUTH_REQUIRED) && !sec_flag.is_link_key_known &&
          !sec_flag.is_encrypted) {
        log::error("GATT_INSUF_AUTHENTICATION uuid: {}", uuid);
        return false;
      }

      if ((element->permissions & GATT_WRITE_MITM_REQUIRED) && !sec_flag.is_link_key_authed) {
        log::error("GATT_INSUF_AUTHENTICATION: MITM Required uuid: {}", uuid);
        return false;
      }

      if ((element->permissions & GATT_WRITE_ENCRYPTED_PERM) && !sec_flag.is_encrypted) {
        log::error("GATT_INSUF_ENCRYPTION uuid: {}", uuid);
        return false;
      }
    } else {
      log::error("Unexpected database element type: {}, uuid: {}", element->type, uuid);
      return false;
    }
  }
  return true;
}

static void on_gatt_offload_register_service_complete(uint16_t session_id, hal::GattStatus status) {
  if (!contains_session_by_id(session_id)) {
    log::error("session_id: {} doesn't exist", session_id);
    return;
  }
  tCONN_ID conn_id = get_conn_id_by_session_id(session_id);
  log::info("session_id: {}, conn_id: {}, status: {}", session_id, conn_id,
            static_cast<int>(status));

  tGATT_STATUS return_status = (status == hal::GattStatus::GATT_SUCCESS)
                                       ? tGATT_STATUS::GATT_SUCCESS
                                       : tGATT_STATUS::GATT_INTERNAL_ERROR;
  std::optional<std::promise<btgatt_offload_result_t>>& promise_opt =
          get_promise_by_session_id(session_id);
  if (promise_opt.has_value()) {
    send_offload_session_register_complete(session_id, return_status, promise_opt.value());
    promise_opt.reset();
  } else {
    log::error("offload register service already completed on session_id: {}", session_id);
  }
  if (status != hal::GattStatus::GATT_SUCCESS) {
    remove_hal_session(session_id, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_FAILED,
                       GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_HAL_FAILURE);
  }
}

static void send_offload_session_register_complete(uint16_t session_id, tGATT_STATUS status,
                                                   std::promise<btgatt_offload_result_t>& promise) {
  uint16_t new_session_id =
          (status == tGATT_STATUS::GATT_SUCCESS) ? session_id : BTGATT_OFFLOAD_SESSION_ID_UNKNOWN;
  log::info("session_id: {}, status: {}", new_session_id, static_cast<int>(status));
  promise.set_value(btgatt_offload_result_t{new_session_id, status});
}

static void on_gatt_offload_unregister_service_complete(uint16_t session_id) {
  log::info("session_id: {}", session_id);
  auto it = gatt_cb.offload_sessions.find(session_id);
  if (it == gatt_cb.offload_sessions.end()) {
    log::error("session_id: {} doesn't exist", session_id);
    return;
  }
  tGATT_OFFLOAD_SESSION& session = it->second;
  tCONN_ID conn_id = get_conn_id_by_session_id(session_id);
  tGATT_IF gatt_if = gatt_get_gatt_if(conn_id);
  remove_hal_session(session_id, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STOPPED,
                     gatt_status_to_offload_error_enum(session.status));
  for (auto& [i, p_reg] : gatt_cb.cl_rcb_map) {
    if (p_reg->gatt_if != gatt_if) {
      continue;
    }
    if (p_reg->in_use && p_reg->app_cb.p_characteristics_unoffloaded_cb) {
      (*p_reg->app_cb.p_characteristics_unoffloaded_cb)(gatt_if, conn_id, session_id,
                                                        tGATT_STATUS::GATT_SUCCESS);
    }
  }
}

static void on_gatt_offload_clear_services_complete(uint16_t acl_connection_handle) {
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  auto iter = gatt_cb.offload_sessions.begin();
  while ((iter = std::find_if(
                  iter, gatt_cb.offload_sessions.end(), [acl_connection_handle](auto& el) {
                    return el.second.hal_session.acl_connection_handle == acl_connection_handle;
                  })) != gatt_cb.offload_sessions.end()) {
    tGATT_OFFLOAD_SESSION& session = iter->second;
    tCONN_ID conn_id = session.conn_id;
    tGATT_IF gatt_if = gatt_get_gatt_if(conn_id);
    uint16_t session_id = session.hal_session.id;
    remove_hal_session(session_id, GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STOPPED,
                       GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE);
    for (auto& [i, p_reg] : gatt_cb.cl_rcb_map) {
      if (p_reg->gatt_if != gatt_if) {
        continue;
      }
      if (p_reg->in_use && p_reg->app_cb.p_characteristics_unoffloaded_cb) {
        (*p_reg->app_cb.p_characteristics_unoffloaded_cb)(gatt_if, conn_id, session_id,
                                                          tGATT_STATUS::GATT_SUCCESS);
      }
    }
    ++iter;
  }
}

static void on_gatt_offload_error_report(uint16_t acl_connection_handle, uint16_t local_cid,
                                         hal::GattError error) {
  if (error == hal::GattError::GATT_ERROR_DATABASE_OUT_OF_SYNC) {
    handle_error_report_database_out_of_sync(acl_connection_handle, local_cid);
  } else if (error == hal::GattError::GATT_ERROR_RESPONSE_TIMEOUT) {
    handle_error_report_response_timeout(acl_connection_handle, local_cid);
  } else if (error == hal::GattError::GATT_ERROR_PROTOCOL_VIOLATION) {
    handle_error_report_protocol_violation(acl_connection_handle, local_cid);
  } else {
    log::warn("Unknown error report type: {}", static_cast<int>(error));
  }
}

static void handle_error_report_database_out_of_sync(uint16_t acl_connection_handle,
                                                     uint16_t /* local_cid */) {
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  if (!gatt_offload_clear_sessions_by_acl_handle(acl_connection_handle,
                                                 hal::GattError::GATT_ERROR_DATABASE_OUT_OF_SYNC)) {
    log::error("Unknown acl_connection_handle: 0x{:x}", acl_connection_handle);
    return;
  }
  auto iter = std::find_if(gatt_cb.offload_sessions.begin(), gatt_cb.offload_sessions.end(),
                           [acl_connection_handle](auto& el) {
                             return el.second.hal_session.acl_connection_handle ==
                                    acl_connection_handle;
                           });
  tCONN_ID conn_id = iter->second.conn_id;
  tGATT_IF gatt_if = gatt_get_gatt_if(conn_id);
  tGATT_REG* p_reg = gatt_get_regcb(gatt_if);
  if (!p_reg) {
    log::error("p_reg not found for application gatt_if:{}", gatt_if);
    return;
  }
  log::info("Call service changed callback");
  if (p_reg->in_use && p_reg->app_cb.p_offloaded_service_chg_cb) {
    (*p_reg->app_cb.p_offloaded_service_chg_cb)(conn_id);
  } else {
    log::warn("Callback not found for application conn_id:{}", conn_id);
  }
}

static void handle_error_report_protocol_violation(uint16_t acl_connection_handle,
                                                   uint16_t /* local_cid */) {
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  if (!gatt_offload_clear_sessions_by_acl_handle(acl_connection_handle,
                                                 hal::GattError::GATT_ERROR_PROTOCOL_VIOLATION)) {
    log::error("Failed to clear offload sessions on the acl handle: 0x{:x}", acl_connection_handle);
    return;
  }
  tGATT_TCB* p_tcb = gatt_find_tcb_by_acl_handle(acl_connection_handle);
  if (!p_tcb) {
    log::error("Unknown acl_connection_handle: 0x{:x}", acl_connection_handle);
    return;
  }
  log::error("Disconnect ACL link at handle: 0x{:x}", acl_connection_handle);
  gatt_disconnect(p_tcb);
}

static void handle_error_report_response_timeout(uint16_t acl_connection_handle,
                                                 uint16_t /* local_cid */) {
  log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
  if (!gatt_offload_clear_sessions_by_acl_handle(acl_connection_handle,
                                                 hal::GattError::GATT_ERROR_RESPONSE_TIMEOUT)) {
    log::error("Failed to clear offload sessions on the acl handle: 0x{:x}", acl_connection_handle);
    return;
  }
  tGATT_TCB* p_tcb = gatt_find_tcb_by_acl_handle(acl_connection_handle);
  if (!p_tcb) {
    log::error("Unknown acl_connection_handle: 0x{:x}", acl_connection_handle);
    return;
  }
  log::error("Disconnect ACL link at handle: 0x{:x}", acl_connection_handle);
  gatt_disconnect(p_tcb);
}

static bool add_gatt_db_elements_to_session(btgatt_db_element_t* service, size_t elements_count,
                                            bluetooth::hal::GattSession& hal_session) {
  hal_session.service_uuid = service->uuid;

  btgatt_db_element_t* element = service + 1;
  for (size_t i = 0; i < elements_count - 1; i++, element++) {
    if (element->type == BTGATT_DB_CHARACTERISTIC) {
      log::verbose("uuid: {}, properties: 0x{:x}, attribute_handle: 0x{:x}", element->uuid,
                   element->properties, element->attribute_handle);
      bluetooth::hal::GattCharacteristic hal_characteristic(element->uuid, element->properties,
                                                            element->attribute_handle);
      hal_session.characteristics.push_back(std::move(hal_characteristic));
    } else {
      log::error("Unknown attribute type: {}", element->type);
      return false;
    }
  }
  return true;
}

static uint16_t create_offload_session_id() {
  uint16_t session_id;
  do {
    std::array<uint8_t, 2> prand = bluetooth::os::GenerateRandom<2>();
    session_id = (static_cast<uint16_t>(prand[1]) << 8) | prand[0];
  } while (session_id == BTGATT_OFFLOAD_SESSION_ID_UNKNOWN || contains_session_by_id(session_id));
  return session_id;
}

static bool check_duplicate_session(bluetooth::hal::GattSession& hal_session, tCONN_ID conn_id) {
  log::info("session_id: {}, conn_id: {}", hal_session.id, conn_id);
  if (contains_session_by_id(hal_session.id)) {
    log::error("session_id: {} already exist", hal_session.id);
    return true;
  }
  auto iter = gatt_cb.offload_sessions.begin();
  while (iter != gatt_cb.offload_sessions.end()) {
    if (hal_session.acl_connection_handle != iter->second.hal_session.acl_connection_handle ||
        hal_session.role != iter->second.hal_session.role) {
      ++iter;
      continue;
    }
    if (contains_common_characteristic(hal_session, iter->second.hal_session)) {
      log::error("session_id: {} conn_id: {} has a common characteristic",
                 iter->second.hal_session.id, iter->second.conn_id);
      return true;
    }
    ++iter;
  }
  return false;
}

static tCONN_ID get_conn_id_by_session_id(uint16_t session_id) {
  return gatt_cb.offload_sessions[session_id].conn_id;
}

static std::optional<std::promise<btgatt_offload_result_t>>& get_promise_by_session_id(
        uint16_t session_id) {
  return gatt_cb.offload_sessions[session_id].promise_opt;
}

static bool try_sessions_by_acl_handle_to_unoffload(uint16_t acl_connection_handle,
                                                    hal::GattError reason) {
  bool found_handle = false;
  bool need_unoffload = false;
  for (auto& session_pair : gatt_cb.offload_sessions) {
    auto& session_data = session_pair.second;
    if (session_data.hal_session.acl_connection_handle == acl_connection_handle) {
      found_handle = true;
      if (session_data.stop_reason == hal::GattError::GATT_ERROR_NONE) {
        session_data.stop_reason = reason;
      }
      if (!session_data.in_clearing_services) {
        session_data.in_clearing_services = true;
        need_unoffload = true;
      }
    }
  }
  if (!found_handle) {
    log::warn("acl_connection_handle: {} doesn't exist", acl_connection_handle);
    return false;
  }
  if (!need_unoffload) {
    log::info("acl_connection_handle: {} unoffloading is already in progress",
              acl_connection_handle);
    return false;
  }
  return true;
}

static bool contains_service_by_attribute_handle(hal::GattSession& hal_session, uint16_t handle) {
  auto iter = std::find_if(hal_session.characteristics.begin(), hal_session.characteristics.end(),
                           [handle](auto& el) { return el.value_handle == handle; });
  return iter != hal_session.characteristics.end();
}

static bool contains_common_characteristic(bluetooth::hal::GattSession& s1,
                                           bluetooth::hal::GattSession& s2) {
  for (const auto& elem1 : s1.characteristics) {
    for (const auto& elem2 : s2.characteristics) {
      if (elem1.value_handle == elem2.value_handle) {
        log::error("service: {} and service: {} have a common value_handle: 0x{:x}",
                   s1.service_uuid, s2.service_uuid, elem1.value_handle);
        return true;
      }
    }
  }
  return false;
}

static void add_hal_session(tGATT_OFFLOAD_SESSION session) {
  uint16_t session_id = session.hal_session.id;
  session.creation_timestamp_ms = bluetooth::common::time_get_os_boottime_ms();
  log::info("session_id: {}, conn_id: {}", session.hal_session.id, session.conn_id);

  auto role = (session.hal_session.role == hal::GATT_SERVER) ? GattRoleEnum::GATT_ROLE_SERVER
                                                             : GattRoleEnum::GATT_ROLE_CLIENT;

  Address peer_address = get_peer_address(session.conn_id);

  int32_t characteristic_properties_bitmask = 0;
  for (const auto& characteristic : session.hal_session.characteristics) {
    characteristic_properties_bitmask |= characteristic.properties;
  }

  bluetooth::metrics::LogGattOffloadSessionStateChanged(
          peer_address, session_id, role,
          GattOffloadSessionStateEnum::GATT_OFFLOAD_SESSION_STATE_STARTED,
          characteristic_properties_bitmask, /*session_duration_ms=*/0,
          GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE, session.uid, session.attribution_tag);
  gatt_cb.offload_sessions[session_id] = std::move(session);
}

static void remove_hal_session(uint16_t session_id, GattOffloadSessionStateEnum state,
                               GattOffloadErrorEnum error) {
  log::info("session_id: {}", session_id);
  auto it = gatt_cb.offload_sessions.find(session_id);
  if (it == gatt_cb.offload_sessions.end()) {
    log::error("Unknown session_id: {}", session_id);
    return;
  }

  auto& session = it->second;
  auto role = (session.hal_session.role == hal::GATT_SERVER) ? GattRoleEnum::GATT_ROLE_SERVER
                                                             : GattRoleEnum::GATT_ROLE_CLIENT;
  uint64_t session_duration_ms =
          bluetooth::common::time_get_os_boottime_ms() - session.creation_timestamp_ms;

  Address peer_address = get_peer_address(session.conn_id);

  int32_t characteristic_properties_bitmask = 0;
  for (const auto& characteristic : session.hal_session.characteristics) {
    characteristic_properties_bitmask |= characteristic.properties;
  }

  bluetooth::metrics::LogGattOffloadSessionStateChanged(
          peer_address, session_id, role, state, characteristic_properties_bitmask,
          session_duration_ms,
          session.stop_reason == hal::GattError::GATT_ERROR_NONE
                  ? error
                  : gatt_hal_error_to_offload_error_enum(session.stop_reason),
          session.uid, session.attribution_tag);
  gatt_cb.offload_sessions.erase(it);
}

static Address get_peer_address(uint16_t conn_id) {
  uint8_t tcb_idx = gatt_get_tcb_idx(conn_id);
  tGATT_TCB* p_tcb = gatt_get_tcb_by_idx(tcb_idx);
  if (p_tcb == nullptr) {
    log::warn("No TCB found for conn_id: {}", conn_id);
    return Address::kEmpty;  // Return an empty address
  }
  return Address(p_tcb->peer_bda);
}

static GattOffloadErrorEnum gatt_status_to_offload_error_enum(tGATT_STATUS status) {
  switch (status) {
    case tGATT_STATUS::GATT_SUCCESS:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE;
    case tGATT_STATUS::GATT_DUP_REG:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_DUPLICATE_SESSION;
    case tGATT_STATUS::GATT_BUSY:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_CONCURRENT_NOTIFICATION_INDICATION;
    case tGATT_STATUS::GATT_INSUF_AUTHENTICATION:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_PERMISSION_DENIED;
    case tGATT_STATUS::GATT_ILLEGAL_PARAMETER:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_INVALID_PARAMETERS;
    case tGATT_STATUS::GATT_INTERNAL_ERROR:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_INTERNAL_ERROR;
    case tGATT_STATUS::GATT_INVALID_HANDLE:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_INVALID_HANDLE;
    default:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_UNKNOWN;
  }
}

static GattOffloadErrorEnum gatt_hal_error_to_offload_error_enum(hal::GattError error) {
  switch (error) {
    case hal::GattError::GATT_ERROR_DATABASE_OUT_OF_SYNC:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_DATABASE_OUT_OF_SYNC;
    case hal::GattError::GATT_ERROR_RESPONSE_TIMEOUT:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_RESPONSE_TIMEOUT;
    case hal::GattError::GATT_ERROR_PROTOCOL_VIOLATION:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_PROTOCOL_VIOLATION;
    case hal::GattError::GATT_ERROR_NONE:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_NONE;
    default:
      return GattOffloadErrorEnum::GATT_OFFLOAD_ERROR_UNKNOWN;
  }
}
