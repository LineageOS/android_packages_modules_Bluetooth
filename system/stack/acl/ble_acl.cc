/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "acl"

#include <bluetooth/log.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <com_android_bluetooth_flags.h>

#include <cstdint>

#include "stack/btm/btm_ble_int.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_sec.h"
#include "stack/connection_manager/connection_manager.h"
#include "stack/include/acl_api.h"
#include "stack/include/ble_acl_interface.h"
#include "stack/include/ble_hci_link_interface.h"
#include "stack/include/btm_ble_addr.h"
#include "stack/include/btm_ble_privacy.h"
#include "stack/include/gatt_api.h"
#include "stack/include/l2cap_hci_link_interface.h"

using namespace bluetooth;

static bool acl_ble_common_connection(const tBLE_BD_ADDR& address_with_type, uint16_t handle,
                                      tHCI_ROLE role, uint16_t conn_interval, uint16_t conn_latency,
                                      uint16_t conn_timeout,
                                      bool can_read_discoverable_characteristics) {
  // Allocate or update the security device record for this device
  btm_ble_connected(address_with_type.bda, handle, HCI_ENCRYPT_MODE_DISABLED, role,
                    address_with_type.type, can_read_discoverable_characteristics);

  // Update the link topology information for our device
  btm_ble_increment_link_topology_mask(role);

  // Inform l2cap of a potential connection.
  if (!l2cble_conn_comp(handle, role, address_with_type.bda, address_with_type.type, conn_interval,
                        conn_latency, conn_timeout)) {
    btm_sec_disconnect(handle, HCI_ERR_PEER_USER, "stack::acl::ble_acl fail");
    log::warn("Unable to complete l2cap connection");

    if (com_android_bluetooth_flags_move_conn_mgr_callbacks()) {
      connection_manager::on_connection_failed(address_with_type.bda);
    }
    return false;
  }

  connection_manager::on_connection_maybe(address_with_type.bda);

  AclLinkSpec link_spec = {.addrt = address_with_type, .transport = BT_TRANSPORT_LE};

  /* Tell BTM Acl management about the link */
  btm_acl_created(link_spec, handle, role);

  return true;
}

void acl_ble_enhanced_connection_complete(const tBLE_BD_ADDR& address_with_type, uint16_t handle,
                                          tHCI_ROLE role, uint16_t conn_interval,
                                          uint16_t conn_latency, uint16_t conn_timeout,
                                          const RawAddress& /* local_rpa */,
                                          const RawAddress& peer_rpa, tBLE_ADDR_TYPE peer_addr_type,
                                          bool can_read_discoverable_characteristics) {
  if (!acl_ble_common_connection(address_with_type, handle, role, conn_interval, conn_latency,
                                 conn_timeout, can_read_discoverable_characteristics)) {
    log::warn("Unable to create enhanced ble acl connection");
    return;
  }

  if (peer_addr_type & BLE_ADDR_TYPE_ID_BIT) {
    btm_ble_refresh_peer_resolvable_private_addr(address_with_type.bda, peer_rpa, BTM_BLE_ADDR_RRA);
  }
}

static bool maybe_resolve_received_address(const tBLE_BD_ADDR& address_with_type,
                                           tBLE_BD_ADDR* resolved_address_with_type) {
  log::assert_that(resolved_address_with_type != nullptr,
                   "assert failed: resolved_address_with_type != nullptr");

  *resolved_address_with_type = address_with_type;
  return maybe_resolve_address(&resolved_address_with_type->bda, &resolved_address_with_type->type);
}

void acl_ble_enhanced_connection_complete_from_shim(
        const tBLE_BD_ADDR& address_with_type, uint16_t handle, tHCI_ROLE role,
        uint16_t conn_interval, uint16_t conn_latency, uint16_t conn_timeout,
        const RawAddress& local_rpa, const RawAddress& peer_rpa, tBLE_ADDR_TYPE peer_addr_type,
        bool can_read_discoverable_characteristics) {
  tBLE_BD_ADDR resolved_address_with_type;
  maybe_resolve_received_address(address_with_type, &resolved_address_with_type);

  acl_ble_enhanced_connection_complete(resolved_address_with_type, handle, role, conn_interval,
                                       conn_latency, conn_timeout, local_rpa, peer_rpa,
                                       peer_addr_type, can_read_discoverable_characteristics);

  // The legacy stack continues the LE connection after the read remote
  // version complete has been received.
  // maybe_chain_more_commands_after_read_remote_version_complete
}

void acl_ble_connection_fail(const tBLE_BD_ADDR& address_with_type, uint16_t /* handle */,
                             bool /* enhanced */, tHCI_STATUS status) {
  AclLinkSpec link_spec = {.addrt = address_with_type, .transport = BT_TRANSPORT_LE};
  // LE connection failures are always locally initiated
  btm_acl_create_failed(link_spec, status, true /* locally_initiated */);

  if (status != HCI_ERR_ADVERTISING_TIMEOUT) {
    tBLE_BD_ADDR resolved_address_with_type;
    maybe_resolve_received_address(address_with_type, &resolved_address_with_type);
    connection_manager::on_connection_failed(resolved_address_with_type.bda);
    log::warn("LE connection fail peer:{} bd_addr:{} hci_status:{}", address_with_type,
              resolved_address_with_type.bda, hci_status_code_text(status));
  }
}

void acl_ble_update_event_received(tHCI_STATUS status, uint16_t handle, uint16_t interval,
                                   uint16_t latency, uint16_t timeout) {
  l2cble_process_conn_update_evt(handle, status, interval, latency, timeout);

  const BtmDevice* p_device = btm_find_dev_by_handle(handle);

  if (!p_device) {
    return;
  }

  gatt_notify_conn_update(p_device->ble.pseudo_addr, interval, latency, timeout, status);
}

void acl_ble_update_request_event_received(uint16_t handle, uint16_t interval_min,
                                           uint16_t interval_max, uint16_t latency,
                                           uint16_t timeout) {
  l2cble_process_rc_param_request_evt(handle, interval_min, interval_max, latency, timeout);
}

void acl_ble_data_length_change_event(uint16_t handle, uint16_t max_tx_octets, uint16_t max_tx_time,
                                      uint16_t max_rx_octets, uint16_t max_rx_time) {
  log::debug(
          "Data length change event received handle:0x{:04x} max_tx_octets:{} "
          "max_tx_time:{} max_rx_octets:{} max_rx_time:{}",
          handle, max_tx_octets, max_tx_time, max_rx_octets, max_rx_time);
  l2cble_process_data_length_change_event(handle, max_tx_octets, max_rx_octets);
}

uint64_t btm_get_next_private_address_interval_ms() {
  /* 7 minutes minimum, 15 minutes maximum for random address refreshing */
  const uint64_t interval_min_ms = (7 * 60 * 1000);
  const uint64_t interval_random_part_max_ms = (8 * 60 * 1000);

  return interval_min_ms + std::rand() % interval_random_part_max_ms;
}
