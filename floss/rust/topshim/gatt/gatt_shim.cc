/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "topshim/gatt/gatt_shim.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>

#include "src/profiles/gatt.rs.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {

namespace internal {

// Singleton instance of Gatt Interfaces
static GattClientIntf* g_client_if;
static GattServerIntf* g_server_if;
static GattIntf* g_gatt_if;
static BleAdvertiserIntf* g_ble_advertiser_if;
static BleScannerIntf* g_ble_scanner_if;

// Gatt Client callbacks
static void register_client_callback(int status, int client_if, const bluetooth::Uuid& app_uuid) {
  rusty::gc_register_client_cb(status, client_if, app_uuid);
}
static void connect_callback(int conn_id, int status, int client_if, int transport,
                             const RawAddress& bda) {
  rusty::gc_open_cb(conn_id, status, client_if, transport, bda);
}
static void disconnect_callback(int conn_id, int status, int client_if, int transport,
                                const RawAddress& bda) {
  rusty::gc_close_cb(conn_id, status, client_if, transport, bda);
}
static void register_for_notification_callback(int conn_id, int registered, int status,
                                               uint16_t handle) {
  rusty::gc_register_for_notification_cb(conn_id, registered, status, handle);
}
static void notify_callback(int conn_id, const btgatt_notify_params_t& p_data) {
  rusty::gc_notify_cb(conn_id, p_data);
}
static void read_characteristic_callback(int conn_id, int status,
                                         const btgatt_read_params_t& p_data) {
  rusty::gc_read_characteristic_cb(conn_id, status, p_data);
}
static void write_characteristic_callback(int conn_id, int status, uint16_t handle, uint16_t len,
                                          const uint8_t* value) {
  rusty::gc_write_characteristic_cb(conn_id, status, handle,
                                    ::rust::Slice<const uint8_t>{value, len});
}
static void read_descriptor_callback(int conn_id, int status, const btgatt_read_params_t& p_data) {
  rusty::gc_read_descriptor_cb(conn_id, status, p_data);
}
static void write_descriptor_callback(int conn_id, int status, uint16_t handle, uint16_t len,
                                      const uint8_t* value) {
  rusty::gc_write_descriptor_cb(conn_id, status, handle, ::rust::Slice<const uint8_t>{value, len});
}
static void execute_write_callback(int conn_id, int status) {
  rusty::gc_execute_write_cb(conn_id, status);
}
static void read_remote_rssi_callback(int client_if, const RawAddress& bda, int rssi, int status) {
  rusty::gc_read_remote_rssi_cb(client_if, bda, rssi, status);
}
static void configure_mtu_callback(int conn_id, int status, int mtu) {
  rusty::gc_configure_mtu_cb(conn_id, status, mtu);
}
static void gc_congestion_callback(int conn_id, bool congested) {
  rusty::gc_congestion_cb(conn_id, congested);
}
static void get_gatt_db_callback(int conn_id, const btgatt_db_element_t* db, int count) {
  rusty::gc_get_gatt_db_cb(
          conn_id, ::rust::Slice<const btgatt_db_element_t>{db, static_cast<size_t>(count)});
}
static void services_removed_callback(int conn_id, uint16_t start_handle, uint16_t end_handle) {}
static void services_added_callback(int conn_id, const btgatt_db_element_t& added,
                                    int added_count) {}
static void gc_phy_updated_callback(int conn_id, uint8_t tx_phy, uint8_t rx_phy, uint8_t status) {
  rusty::gc_phy_updated_cb(conn_id, tx_phy, rx_phy, status);
}
static void gc_conn_updated_callback(int conn_id, uint16_t interval, uint16_t latency,
                                     uint16_t timeout, uint8_t status) {
  rusty::gc_conn_updated_cb(conn_id, interval, latency, timeout, status);
}
static void service_changed_callback(int conn_id) { rusty::gc_service_changed_cb(conn_id); }
static void gc_subrate_change_callback(int conn_id, uint16_t subrate_factor, uint16_t latency,
                                       uint16_t cont_num, uint16_t timeout, uint8_t subrate_mode,
                                       uint8_t status) {}
static void gc_characteristics_unoffloaded_callback(int conn_id, int session_id, uint8_t status) {}

void ReadPhyCallback(int client_if, RawAddress addr, uint8_t tx_phy, uint8_t rx_phy,
                     uint8_t status) {
  rusty::read_phy_callback(client_if, addr, tx_phy, rx_phy, status);
}

btgatt_client_callbacks_t gatt_client_callbacks = {
        register_client_callback,
        connect_callback,
        disconnect_callback,
        register_for_notification_callback,
        notify_callback,
        read_characteristic_callback,
        write_characteristic_callback,
        read_descriptor_callback,
        write_descriptor_callback,
        execute_write_callback,
        read_remote_rssi_callback,
        configure_mtu_callback,
        gc_congestion_callback,
        get_gatt_db_callback,
        services_removed_callback,
        services_added_callback,
        gc_phy_updated_callback,
        gc_conn_updated_callback,
        service_changed_callback,
        gc_subrate_change_callback,
        gc_characteristics_unoffloaded_callback,
};

// Gatt Server callbacks
static void register_server_callback(int status, int server_if, const bluetooth::Uuid& app_uuid) {
  rusty::gs_register_server_cb(status, server_if, app_uuid);
}
static void connection_callback(int conn_id, int server_if, int transport, int connected,
                                const RawAddress& bda) {
  rusty::gs_connection_cb(conn_id, server_if, transport, connected, bda);
}
static void service_added_callback(int status, int server_if, const btgatt_db_element_t* service,
                                   size_t service_count) {
  rusty::gs_service_added_cb(status, server_if,
                             ::rust::Slice<const btgatt_db_element_t>{service, service_count});
}
static void service_deleted_callback(int status, int server_if, int srvc_handle) {
  rusty::gs_service_deleted_cb(status, server_if, srvc_handle);
}
static void request_read_characteristic_callback(int conn_id, int trans_id, const RawAddress& bda,
                                                 int attr_handle, int offset, bool is_long) {
  rusty::gs_request_read_characteristic_cb(conn_id, trans_id, bda, attr_handle, offset, is_long);
}
static void request_read_descriptor_callback(int conn_id, int trans_id, const RawAddress& bda,
                                             int attr_handle, int offset, bool is_long) {
  rusty::gs_request_read_descriptor_cb(conn_id, trans_id, bda, attr_handle, offset, is_long);
}
static void request_write_characteristic_callback(int conn_id, int trans_id, const RawAddress& bda,
                                                  int attr_handle, int offset, bool need_rsp,
                                                  bool is_prep, const uint8_t* value,
                                                  size_t length) {
  rusty::gs_request_write_characteristic_cb(conn_id, trans_id, bda, attr_handle, offset, need_rsp,
                                            is_prep, ::rust::Slice<const uint8_t>{value, length});
}
static void request_write_descriptor_callback(int conn_id, int trans_id, const RawAddress& bda,
                                              int attr_handle, int offset, bool need_rsp,
                                              bool is_prep, const uint8_t* value, size_t length) {
  rusty::gs_request_write_descriptor_cb(conn_id, trans_id, bda, attr_handle, offset, need_rsp,
                                        is_prep, ::rust::Slice<const uint8_t>{value, length});
}
static void request_exec_write_callback(int conn_id, int trans_id, const RawAddress& bda,
                                        int exec_write) {
  rusty::gs_request_exec_write_cb(conn_id, trans_id, bda, exec_write);
}
static void response_confirmation_callback(int status, int handle) {
  rusty::gs_response_confirmation_cb(status, handle);
}
static void indication_sent_callback(int conn_id, int status) {
  rusty::gs_indication_sent_cb(conn_id, status);
}
static void gs_congestion_callback(int conn_id, bool congested) {
  rusty::gs_congestion_cb(conn_id, congested);
}
static void mtu_changed_callback(int conn_id, int mtu) { rusty::gs_mtu_changed_cb(conn_id, mtu); }
static void gs_phy_updated_callback(int conn_id, uint8_t tx_phy, uint8_t rx_phy, uint8_t status) {
  rusty::gs_phy_updated_cb(conn_id, tx_phy, rx_phy, status);
}
static void gs_conn_updated_callback(int conn_id, uint16_t interval, uint16_t latency,
                                     uint16_t timeout, uint8_t status) {
  rusty::gs_conn_updated_cb(conn_id, interval, latency, timeout, status);
}
static void gs_subrate_change_callback(int conn_id, uint16_t subrate_factor, uint16_t latency,
                                       uint16_t cont_num, uint16_t timeout, uint8_t subrate_mode,
                                       uint8_t status) {
  rusty::gs_subrate_chg_cb(conn_id, subrate_factor, latency, cont_num, timeout, subrate_mode,
                           status);
}
static void gs_characteristics_unoffloaded_callback(int conn_id, int session_id, uint8_t status) {}

void ServerReadPhyCallback(int server_if, RawAddress addr, uint8_t tx_phy, uint8_t rx_phy,
                           uint8_t status) {
  rusty::server_read_phy_callback(server_if, addr, tx_phy, rx_phy, status);
}

btgatt_server_callbacks_t gatt_server_callbacks = {
        register_server_callback,
        connection_callback,
        service_added_callback,
        service_deleted_callback,
        request_read_characteristic_callback,
        request_read_descriptor_callback,
        request_write_characteristic_callback,
        request_write_descriptor_callback,
        request_exec_write_callback,
        response_confirmation_callback,
        indication_sent_callback,
        gs_congestion_callback,
        mtu_changed_callback,
        gs_phy_updated_callback,
        gs_conn_updated_callback,
        gs_subrate_change_callback,
        gs_characteristics_unoffloaded_callback,
};

btgatt_callbacks_t gatt_callbacks = {
        sizeof(gatt_callbacks),
        &gatt_client_callbacks,
        &gatt_server_callbacks,
};

}  // namespace internal

tBT_STATUS_LEGACY GattClientIntf::register_client(Uuid uuid, ::rust::Vec<uint8_t> name,
                                                  bool eatt_support) const {
  return toLegacyStatus(client_intf_->register_client(
          uuid, reinterpret_cast<const char*>(name.data()), eatt_support));
}

tBT_STATUS_LEGACY GattClientIntf::unregister_client(int client_if) const {
  return toLegacyStatus(client_intf_->unregister_client(client_if));
}

tBT_STATUS_LEGACY GattClientIntf::connect(int client_if, RawAddress bd_addr, uint8_t addr_type,
                                          bool is_direct, int transport, bool opportunistic,
                                          int preferred_mtu, bool prefer_relax_mode,
                                          bool auto_mtu_enabled) const {
  return toLegacyStatus(client_intf_->connect(client_if, bd_addr, addr_type, is_direct, transport,
                                              opportunistic, preferred_mtu, prefer_relax_mode,
                                              auto_mtu_enabled));
}

tBT_STATUS_LEGACY GattClientIntf::disconnect(int client_if, RawAddress bd_addr, int conn_id) const {
  return toLegacyStatus(client_intf_->disconnect(client_if, bd_addr, conn_id));
}

tBT_STATUS_LEGACY GattClientIntf::refresh(int client_if, RawAddress bd_addr) const {
  return toLegacyStatus(client_intf_->refresh(client_if, bd_addr));
}

tBT_STATUS_LEGACY GattClientIntf::search_service(int conn_id, Uuid filter_uuid) const {
  return toLegacyStatus(client_intf_->search_service(conn_id, &filter_uuid));
}

tBT_STATUS_LEGACY GattClientIntf::search_service_all(int conn_id) const {
  return toLegacyStatus(client_intf_->search_service(conn_id, nullptr));
}

void GattClientIntf::btif_gattc_discover_service_by_uuid(int conn_id, Uuid uuid) const {
  client_intf_->btif_gattc_discover_service_by_uuid(conn_id, uuid);
}

tBT_STATUS_LEGACY GattClientIntf::read_characteristic(int conn_id, uint16_t handle,
                                                      int auth_req) const {
  return toLegacyStatus(client_intf_->read_characteristic(conn_id, handle, auth_req));
}

tBT_STATUS_LEGACY GattClientIntf::read_using_characteristic_uuid(int conn_id, Uuid uuid,
                                                                 uint16_t s_handle,
                                                                 uint16_t e_handle,
                                                                 int auth_req) const {
  return toLegacyStatus(client_intf_->read_using_characteristic_uuid(conn_id, uuid, s_handle,
                                                                     e_handle, auth_req));
}

tBT_STATUS_LEGACY GattClientIntf::write_characteristic(int conn_id, uint16_t handle, int write_type,
                                                       int auth_req, ::rust::Vec<uint8_t> value,
                                                       size_t length) const {
  return toLegacyStatus(client_intf_->write_characteristic(
          conn_id, handle, write_type, auth_req, reinterpret_cast<uint8_t*>(value.data()), length));
}

tBT_STATUS_LEGACY GattClientIntf::read_descriptor(int conn_id, uint16_t handle,
                                                  int auth_req) const {
  return toLegacyStatus(client_intf_->read_descriptor(conn_id, handle, auth_req));
}

tBT_STATUS_LEGACY GattClientIntf::write_descriptor(int conn_id, uint16_t handle, int auth_req,
                                                   ::rust::Vec<uint8_t> value,
                                                   size_t length) const {
  return toLegacyStatus(
          client_intf_->write_descriptor(conn_id, handle, auth_req, value.data(), length));
}

tBT_STATUS_LEGACY GattClientIntf::execute_write(int conn_id, int execute) const {
  return toLegacyStatus(client_intf_->execute_write(conn_id, execute));
}

tBT_STATUS_LEGACY GattClientIntf::register_for_notification(int client_if, RawAddress bd_addr,
                                                            uint16_t handle) const {
  return toLegacyStatus(client_intf_->register_for_notification(client_if, bd_addr, handle));
}

tBT_STATUS_LEGACY GattClientIntf::deregister_for_notification(int client_if, RawAddress bd_addr,
                                                              uint16_t handle) const {
  return toLegacyStatus(client_intf_->deregister_for_notification(client_if, bd_addr, handle));
}

tBT_STATUS_LEGACY GattClientIntf::read_remote_rssi(int client_if, RawAddress bd_addr) const {
  return toLegacyStatus(client_intf_->read_remote_rssi(client_if, bd_addr));
}

int GattClientIntf::get_device_type(RawAddress bd_addr) const {
  return client_intf_->get_device_type(bd_addr);
}

tBT_STATUS_LEGACY GattClientIntf::configure_mtu(int conn_id, int mtu) const {
  return toLegacyStatus(client_intf_->configure_mtu(conn_id, mtu));
}

tBT_STATUS_LEGACY GattClientIntf::conn_parameter_update(RawAddress bd_addr, int min_interval,
                                                        int max_interval, int latency, int timeout,
                                                        uint16_t min_ce_len,
                                                        uint16_t max_ce_len) const {
  return toLegacyStatus(client_intf_->conn_parameter_update(
          bd_addr, min_interval, max_interval, latency, timeout, min_ce_len, max_ce_len));
}

tBT_STATUS_LEGACY GattClientIntf::set_preferred_phy(RawAddress bd_addr, uint8_t tx_phy,
                                                    uint8_t rx_phy, uint16_t phy_options) const {
  return toLegacyStatus(client_intf_->set_preferred_phy(bd_addr, tx_phy, rx_phy, phy_options));
}

tBT_STATUS_LEGACY GattClientIntf::read_phy(int client_if, RawAddress addr) const {
  return toLegacyStatus(
          client_intf_->read_phy(addr, base::Bind(&internal::ReadPhyCallback, client_if, addr)));
}

tBT_STATUS_LEGACY GattClientIntf::subrate_request(RawAddress bd_addr, int subrate_min,
                                                  int subrate_max, int max_latency, int cont_num,
                                                  int timeout) const {
  return toLegacyStatus(client_intf_->subrate_request(bd_addr, subrate_min, subrate_max,
                                                      max_latency, cont_num, timeout));
}

tBT_STATUS_LEGACY GattClientIntf::subrate_mode_request(int client_if, RawAddress bd_addr,
                                                       uint8_t subrate_mode) const {
  return toLegacyStatus(client_intf_->subrate_mode_request(client_if, bd_addr, subrate_mode));
}

tBT_STATUS_LEGACY GattClientIntf::offload_characteristics(int conn_id, btgatt_db_element_t service,
                                                          size_t elements_count,
                                                          uint64_t endpoint_id, uint64_t hub_id,
                                                          btgatt_offload_result_t result) const {
  return toLegacyStatus(client_intf_->offload_characteristics(
          conn_id, &service, elements_count, endpoint_id, hub_id, -1, "", &result));
}

tBT_STATUS_LEGACY GattClientIntf::unoffload_characteristics(int conn_id, int session_id) const {
  return toLegacyStatus(client_intf_->unoffload_characteristics(conn_id, session_id));
}

std::unique_ptr<GattClientIntf> GetGattClientProfile(const BtIntf& intf) {
  if (internal::g_client_if) {
    std::abort();
  }

  auto gatt_if = reinterpret_cast<const btgatt_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_GATT_ID));
  auto client_if = std::make_unique<GattClientIntf>(gatt_if->client);
  internal::g_client_if = client_if.get();
  return client_if;
}

tBT_STATUS_LEGACY GattServerIntf::register_server(Uuid uuid, bool eatt_support) const {
  return toLegacyStatus(server_intf_->register_server(uuid, eatt_support));
}

tBT_STATUS_LEGACY GattServerIntf::unregister_server(int server_if) const {
  return toLegacyStatus(server_intf_->unregister_server(server_if));
}

tBT_STATUS_LEGACY GattServerIntf::connect(int server_if, RawAddress bd_addr, uint8_t addr_type,
                                          bool is_direct, int transport) const {
  return toLegacyStatus(server_intf_->connect(server_if, bd_addr, addr_type, is_direct, transport));
}

tBT_STATUS_LEGACY GattServerIntf::disconnect(int server_if, RawAddress bd_addr, int conn_id) const {
  return toLegacyStatus(server_intf_->disconnect(server_if, bd_addr, conn_id));
}

tBT_STATUS_LEGACY GattServerIntf::add_service(int server_if,
                                              ::rust::Slice<const btgatt_db_element_t> service,
                                              size_t service_count) const {
  return toLegacyStatus(server_intf_->add_service(server_if, service.data(), service_count));
}

tBT_STATUS_LEGACY GattServerIntf::delete_service(int server_if, int service_handle) const {
  return toLegacyStatus(server_intf_->delete_service(server_if, service_handle));
}

tBT_STATUS_LEGACY GattServerIntf::send_indication(int server_if, int attribute_handle, int conn_id,
                                                  int confirm, ::rust::Vec<uint8_t> value,
                                                  size_t length) const {
  return toLegacyStatus(server_intf_->send_indication(server_if, attribute_handle, conn_id, confirm,
                                                      value.data(), length));
}

tBT_STATUS_LEGACY GattServerIntf::send_response(int conn_id, int trans_id, int status,
                                                btgatt_response_t response) const {
  return toLegacyStatus(server_intf_->send_response(conn_id, trans_id, status, response));
}

tBT_STATUS_LEGACY GattServerIntf::set_preferred_phy(RawAddress bd_addr, uint8_t tx_phy,
                                                    uint8_t rx_phy, uint16_t phy_options) const {
  return toLegacyStatus(server_intf_->set_preferred_phy(bd_addr, tx_phy, rx_phy, phy_options));
}

tBT_STATUS_LEGACY GattServerIntf::read_phy(int server_if, RawAddress bd_addr) const {
  return toLegacyStatus(server_intf_->read_phy(
          bd_addr, base::Bind(&internal::ServerReadPhyCallback, server_if, bd_addr)));
}

tBT_STATUS_LEGACY GattServerIntf::offload_characteristics(int conn_id, btgatt_db_element_t service,
                                                          size_t element_count,
                                                          uint64_t endpoint_id, uint64_t hub_id,
                                                          btgatt_offload_result_t result) const {
  return toLegacyStatus(server_intf_->offload_characteristics(
          conn_id, &service, element_count, endpoint_id, hub_id, -1, "", &result));
}

tBT_STATUS_LEGACY GattServerIntf::unoffload_characteristics(int conn_id, int session_id) const {
  return toLegacyStatus(server_intf_->unoffload_characteristics(conn_id, session_id));
}

std::unique_ptr<GattServerIntf> GetGattServerProfile(const BtIntf& intf) {
  if (internal::g_server_if) {
    std::abort();
  }

  auto gatt_if = reinterpret_cast<const btgatt_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_GATT_ID));
  auto server_if = std::make_unique<GattServerIntf>(gatt_if->server);
  internal::g_server_if = server_if.get();
  return server_if;
}

tBT_STATUS_LEGACY GattIntf::init() const {
  return toLegacyStatus(gatt_intf_->init(&internal::gatt_callbacks));
}

void GattIntf::cleanup() const { gatt_intf_->cleanup(); }

std::unique_ptr<BleAdvertiserIntf> GattIntf::GetBleAdvertiserIntf() const {
  if (internal::g_ble_advertiser_if) {
    std::abort();
  }

  auto g_ble_advertiser_if = std::make_unique<BleAdvertiserIntf>(gatt_intf_->advertiser);
  internal::g_ble_advertiser_if = g_ble_advertiser_if.get();
  return g_ble_advertiser_if;
}

std::unique_ptr<BleScannerIntf> GattIntf::GetBleScannerIntf() const {
  if (internal::g_ble_scanner_if) {
    std::abort();
  }

  auto g_ble_scanner_if = std::make_unique<BleScannerIntf>(gatt_intf_->scanner);
  internal::g_ble_scanner_if = g_ble_scanner_if.get();
  return g_ble_scanner_if;
}

std::unique_ptr<GattIntf> GetGattProfile(const BtIntf& intf) {
  if (internal::g_gatt_if) {
    std::abort();
  }

  auto gatt_if = std::make_unique<GattIntf>(reinterpret_cast<const btgatt_interface_t*>(
          intf.get_profile_interface(BT_PROFILE_GATT_ID)));
  internal::g_gatt_if = gatt_if.get();
  return gatt_if;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
