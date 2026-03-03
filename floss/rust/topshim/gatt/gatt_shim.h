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
#ifndef GD_RUST_TOPSHIM_GATT_GATT_SHIM_H
#define GD_RUST_TOPSHIM_GATT_GATT_SHIM_H

#include <bluetooth/types/uuid.h>
#include <hardware/bt_common_types.h>
#include <hardware/bt_gatt.h>

#include <memory>

#include "rust/cxx.h"
#include "topshim/btif/btif_shim.h"
#include "topshim/common/bt_status_helper.h"
#include "topshim/gatt/gatt_ble_advertiser_shim.h"
#include "topshim/gatt/gatt_ble_scanner_shim.h"

namespace bluetooth {
namespace topshim {
namespace rust {

class GattClientIntf {
public:
  GattClientIntf(const btgatt_client_interface_t* client_intf) : client_intf_(client_intf) {}
  ~GattClientIntf() = default;

  tBT_STATUS_LEGACY register_client(Uuid uuid, ::rust::Vec<uint8_t> name, bool eatt_support) const;
  tBT_STATUS_LEGACY unregister_client(int client_if) const;
  tBT_STATUS_LEGACY connect(int client_if, RawAddress bd_addr, uint8_t addr_type, bool is_direct,
                            int transport, bool opportunistic, int preferred_mtu,
                            bool prefer_relax_mode, bool auto_mtu_enabled) const;
  tBT_STATUS_LEGACY disconnect(int client_if, RawAddress bd_addr, int conn_id) const;
  tBT_STATUS_LEGACY refresh(int client_if, RawAddress bd_addr) const;
  tBT_STATUS_LEGACY search_service(int conn_id, Uuid filter_uuid) const;
  tBT_STATUS_LEGACY search_service_all(int conn_id) const;
  void btif_gattc_discover_service_by_uuid(int conn_id, Uuid uuid) const;
  tBT_STATUS_LEGACY read_characteristic(int conn_id, uint16_t handle, int auth_req) const;
  tBT_STATUS_LEGACY read_using_characteristic_uuid(int conn_id, Uuid uuid, uint16_t s_handle,
                                                   uint16_t e_handle, int auth_req) const;
  tBT_STATUS_LEGACY write_characteristic(int conn_id, uint16_t handle, int write_type, int auth_req,
                                         ::rust::Vec<uint8_t> value, size_t length) const;
  tBT_STATUS_LEGACY read_descriptor(int conn_id, uint16_t handle, int auth_req) const;
  tBT_STATUS_LEGACY write_descriptor(int conn_id, uint16_t handle, int auth_req,
                                     ::rust::Vec<uint8_t> value, size_t length) const;
  tBT_STATUS_LEGACY execute_write(int conn_id, int execute) const;
  tBT_STATUS_LEGACY register_for_notification(int client_if, RawAddress bd_addr,
                                              uint16_t handle) const;
  tBT_STATUS_LEGACY deregister_for_notification(int client_if, RawAddress bd_addr,
                                                uint16_t handle) const;
  tBT_STATUS_LEGACY read_remote_rssi(int client_if, RawAddress bd_addr) const;
  int get_device_type(RawAddress bd_addr) const;
  tBT_STATUS_LEGACY configure_mtu(int conn_id, int mtu) const;
  tBT_STATUS_LEGACY conn_parameter_update(RawAddress bd_addr, int min_interval, int max_interval,
                                          int latency, int timeout, uint16_t min_ce_len,
                                          uint16_t max_ce_len) const;
  tBT_STATUS_LEGACY set_preferred_phy(RawAddress bd_addr, uint8_t tx_phy, uint8_t rx_phy,
                                      uint16_t phy_options) const;
  tBT_STATUS_LEGACY read_phy(int client_if, RawAddress bt_addr) const;
  tBT_STATUS_LEGACY subrate_request(RawAddress bd_addr, int subrate_min, int subrate_max,
                                    int max_latency, int cont_num, int timeout) const;
  tBT_STATUS_LEGACY subrate_mode_request(int client_if, RawAddress bd_addr,
                                         uint8_t subrate_mode) const;
  tBT_STATUS_LEGACY offload_characteristics(int conn_id, btgatt_db_element_t service,
                                            size_t elements_count, uint64_t endpoint_id,
                                            uint64_t hub_id, btgatt_offload_result_t result) const;
  tBT_STATUS_LEGACY unoffload_characteristics(int conn_id, int session_id) const;

private:
  const btgatt_client_interface_t* client_intf_;
};

std::unique_ptr<GattClientIntf> GetGattClientProfile(const BtIntf& intf);

class GattServerIntf {
public:
  GattServerIntf(const btgatt_server_interface_t* server_intf) : server_intf_(server_intf) {}
  ~GattServerIntf() = default;

  tBT_STATUS_LEGACY register_server(Uuid uuid, bool eatt_support) const;
  tBT_STATUS_LEGACY unregister_server(int server_if) const;
  tBT_STATUS_LEGACY connect(int server_if, RawAddress bd_addr, uint8_t addr_type, bool is_direct,
                            int transport) const;
  tBT_STATUS_LEGACY disconnect(int server_if, RawAddress bd_addr, int conn_id) const;
  tBT_STATUS_LEGACY add_service(int server_if, ::rust::Slice<const btgatt_db_element_t> service,
                                size_t service_count) const;
  tBT_STATUS_LEGACY delete_service(int server_if, int service_handle) const;
  tBT_STATUS_LEGACY send_indication(int server_if, int attribute_handle, int conn_id, int confirm,
                                    ::rust::Vec<uint8_t> value, size_t length) const;
  tBT_STATUS_LEGACY send_response(int conn_id, int trans_id, int status,
                                  btgatt_response_t response) const;
  tBT_STATUS_LEGACY set_preferred_phy(RawAddress bd_addr, uint8_t tx_phy, uint8_t rx_phy,
                                      uint16_t phy_options) const;
  tBT_STATUS_LEGACY read_phy(int server_if, RawAddress bt_addr) const;
  tBT_STATUS_LEGACY offload_characteristics(int conn_id, btgatt_db_element_t service,
                                            size_t element_count, uint64_t endpoint_id,
                                            uint64_t hub_id, btgatt_offload_result_t result) const;
  tBT_STATUS_LEGACY unoffload_characteristics(int conn_id, int session_id) const;

private:
  const btgatt_server_interface_t* server_intf_;
};

std::unique_ptr<GattServerIntf> GetGattServerProfile(const BtIntf& intf);

class GattIntf {
public:
  GattIntf(const btgatt_interface_t* gatt_intf) : gatt_intf_(gatt_intf) {}
  ~GattIntf() = default;

  tBT_STATUS_LEGACY init() const;
  void cleanup() const;

  std::unique_ptr<BleAdvertiserIntf> GetBleAdvertiserIntf() const;
  std::unique_ptr<BleScannerIntf> GetBleScannerIntf() const;

private:
  const btgatt_interface_t* gatt_intf_;
};

std::unique_ptr<GattIntf> GetGattProfile(const BtIntf& intf);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth

#endif  // GD_RUST_TOPSHIM_GATT_GATT_SHIM_H
