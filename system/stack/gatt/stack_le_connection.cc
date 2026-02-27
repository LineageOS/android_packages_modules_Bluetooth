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

#define LOG_TAG "stack_le_connection"

#include "stack/include/stack_le_connection.h"

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/metrics/os_metrics.h>
#include <bluetooth/types/bt_transport.h>
#include <com_android_bluetooth_flags.h>

#include <string>

#include "internal_include/bt_target.h"
#include "internal_include/stack_config.h"
#include "main/shim/helpers.h"
#include "osi/include/allocator.h"
#include "stack/arbiter/acl_arbiter.h"
#include "stack/btm/btm_dev.h"
#include "stack/connection_manager/connection_manager.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/gatt_api.h"
#include "stack/include/l2cap_interface.h"

using namespace bluetooth;

namespace bluetooth::stack {

/*******************************************************************************
 *
 * Function         leConnectionConnect
 *
 * Description      This function initiate a connection to a remote device on
 *                  GATT channel.
 *
 * Parameters       gatt_if: application interface
 *                  bd_addr: peer device address.
 *                  connection_type: is a direct connection or a background
 *                  auto connection or targeted announcements
 *
 * Returns          true if connection started; false if connection start
 *                  failure.
 *
 ******************************************************************************/
bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr, tBLE_ADDR_TYPE addr_type,
                         tBTM_BLE_CONN_TYPE connection_type, bool opportunistic,
                         uint16_t preferred_mtu, bool prefer_relax_mode, bool auto_mtu_enabled) {
  /* Make sure app is registered */
  tGATT_REG* p_reg = gatt_get_regcb(gatt_if);
  if (!p_reg) {
    log::error("Unable to find registered app gatt_if={}", gatt_if);
    return false;
  }

  bool is_direct = (connection_type == BTM_BLE_DIRECT_CONNECTION);

  if (bd_addr == RawAddress::kEmpty) {
    log::error("Unsupported empty address, gatt_if={}", gatt_if);
    return false;
  }

  if (opportunistic) {
    log::info("Registered for opportunistic connection gatt_if={}", gatt_if);
    return true;
  }

  bool ret = false;
  if (is_direct) {
    log::debug("Starting direct connect gatt_if={} address={} prefer_relax_mode={}", gatt_if,
               bd_addr, prefer_relax_mode);
    tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, BT_TRANSPORT_LE);

    if (p_tcb != nullptr) {
      uint8_t st = gatt_get_ch_state(p_tcb);
      if (st == GATT_CH_OPEN && p_tcb->app_hold_link.empty()) {
        gatt_update_app_use_link_flag(p_reg->gatt_if, p_tcb, true, true);
        ret = true;
      } else if (st == GATT_CH_CLOSING) {
        log::info("Must finish disconnection before new connection");
        /* need to complete the closing first */
        ret = false;
      } else {
        ret = true;
      }
    } else {
      log::verbose("Connecting without tcb to: {}", bd_addr);
      bool has_direct_conn = connection_manager::is_direct_connection(bd_addr);
      ret = connection_manager::direct_connect_add(gatt_if, bd_addr, addr_type, prefer_relax_mode);
      if (!has_direct_conn && ret) {
        bluetooth::metrics::LogMetricLeConnectionLifecycle(bd_addr, true /* is_connect */,
                                                           true /* is_direct */);
      }
    }
  } else {
    log::debug("Starting background connect gatt_if={} address={}", gatt_if, bd_addr);
    bluetooth::metrics::LogMetricLeConnectionLifecycle(bd_addr, true /* is_connect */, is_direct);
    if (!BTM_Sec_AddressKnown(bd_addr)) {
      //  RPA can rotate, causing address to "expire" in the background
      //  connection list. RPA is allowed for direct connect, as such request
      //  times out after 30 seconds
      log::warn("Unable to add RPA {} to background connection gatt_if={}", bd_addr, gatt_if);
      ret = false;
    } else {
      log::debug("Adding to background connect to device:{}", bd_addr);
      if (connection_type == BTM_BLE_BKG_CONNECT_ALLOW_LIST) {
        ret = connection_manager::background_connect_add(gatt_if, bd_addr);
      } else {
        ret = connection_manager::background_connect_targeted_announcement_add(gatt_if, bd_addr);
      }
    }
  }

  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, BT_TRANSPORT_LE);
  // background connections don't necessarily create tcb
  if (p_tcb && ret) {
    gatt_update_app_use_link_flag(p_reg->gatt_if, p_tcb, true, !is_direct);
  } else {
    if (p_tcb == nullptr) {
      log::debug("p_tcb is null");
    }
    if (!ret) {
      log::debug("Previous step returned false");
    }
  }

  if (ret) {
    // Save the current MTU preference for this app
    p_reg->mtu_prefs.erase(bd_addr);
    if (preferred_mtu > GATT_DEF_BLE_MTU_SIZE) {
      log::verbose("Saving MTU preference from app {} for {}", gatt_if, bd_addr);
      p_reg->mtu_prefs.insert({bd_addr, preferred_mtu});
    }
    p_reg->auto_mtu_enabled.erase(bd_addr);
    p_reg->auto_mtu_enabled.insert({bd_addr, auto_mtu_enabled});
    log::verbose("Saving MTU preference from app {} for {} : auto_mtu_enabled: {}", gatt_if,
                 bd_addr, auto_mtu_enabled);
  }

  return ret;
}

bool leConnectionConnect(tGATT_IF gatt_if, const RawAddress& bd_addr,
                         tBTM_BLE_CONN_TYPE connection_type, bool opportunistic) {
  return leConnectionConnect(gatt_if, bd_addr, BLE_ADDR_PUBLIC, connection_type, opportunistic, 0,
                             false, false);
}

/*******************************************************************************
 *
 * Function         stack::leConnectionCancelConnect
 *
 * Description      This function terminates the connection initiation to a
 *                  remote device on GATT channel.
 *
 * Parameters       gatt_if: client interface. If 0 used as unconditionally
 *                           disconnect, typically used for direct connection
 *                           cancellation.
 *                  bd_addr: peer device address.
 *
 * Returns          true if the connection started; false otherwise.
 *
 ******************************************************************************/
bool leConnectionCancelConnect(tGATT_IF gatt_if, const RawAddress& bd_addr, bool is_direct) {
  log::info("gatt_if:{}, address: {}, direct:{}", gatt_if, bd_addr, is_direct);

  tGATT_REG* p_reg;
  if (gatt_if) {
    p_reg = gatt_get_regcb(gatt_if);
    if (!p_reg) {
      log::error("gatt_if={} is not registered", gatt_if);
      return false;
    }

    if (is_direct) {
      return gatt_cancel_open(gatt_if, bd_addr);
    } else {
      return gatt_auto_connect_dev_remove(p_reg->gatt_if, bd_addr);
    }
  }

  log::verbose("unconditional");

  /* only LE connection can be cancelled */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, BT_TRANSPORT_LE);
  if (p_tcb && !p_tcb->app_hold_link.empty()) {
    for (auto it = p_tcb->app_hold_link.begin(); it != p_tcb->app_hold_link.end();) {
      auto next = std::next(it);
      // gatt_cancel_open modifies the app_hold_link.
      gatt_cancel_open(*it, bd_addr);

      it = next;
    }
  }

  // Notify connecting clients of unconditional disconnect
  if (com_android_bluetooth_flags_notify_unconditional_disconnect_le() && gatt_if == 0 && !p_tcb) {
    gatt_cleanup_upon_disc(bd_addr, GATT_CONN_TERMINATE_LOCAL_HOST, BT_TRANSPORT_LE);
  }
  if (!connection_manager::remove_unconditional(bd_addr)) {
    log::error("no app associated with the bg device for unconditional removal");
    return false;
  }

  return true;
}

void leConnectionUpdateSubrateConfig(tGATT_SUBRATE_MODE subrate_mode, uint16_t subrate_max,
                                     uint16_t subrate_min, uint16_t cont_num) {
  if (!gatt_cb.subrate_mode_config.contains(subrate_mode)) {
    log::warn("This is a unknown subrate mode to update: {}", subrate_mode);
    return;
  }
  if (subrate_min > subrate_max || cont_num >= subrate_max || subrate_min > 500 ||
      subrate_max > 500) {
    log::error("Invalid subrate parameter to update: {} {} {} {}", subrate_mode, subrate_max,
               subrate_min, cont_num);
    return;
  }
  log::debug("Update subrate mode config: {} {} {} {}", subrate_mode, subrate_max, subrate_min,
             cont_num);
  gatt_cb.subrate_mode_config[subrate_mode].subrate_max = subrate_max;
  gatt_cb.subrate_mode_config[subrate_mode].subrate_min = subrate_min;
  gatt_cb.subrate_mode_config[subrate_mode].cont_num = cont_num;
}

bool leConnectionSubrateModeRequest(tGATT_IF client_if, const RawAddress& bd_addr,
                                    tGATT_SUBRATE_MODE subrate_mode) {
  log::debug("client_if:{} addr:{}, subrate_mode:{}", client_if, bd_addr, subrate_mode);
  if (!gatt_register_subrate_config(client_if, bd_addr, subrate_mode)) {
    return false;
  }
  return true;
}

/*******************************************************************************
 *
 * Function         leConnectionSubrateRequest
 *
 * Description      subrate request, can only be used when connection is up.
 *
 * Parameters:      bd_addr       - BD address of the peer
 *                  subrate_min   - subrate factor minimum, [0x0001 - 0x01F4]
 *                  subrate_max   - subrate factor maximum, [0x0001 - 0x01F4]
 *                  max_latency   - max peripheral latency [0x0000 - 01F3]
 *                  cont_num      - continuation number [0x0000 - 01F3]
 *                  timeout       - supervision timeout [0x000a - 0xc80]
 *
 * Returns          void
 *
 ******************************************************************************/
void leConnectionSubrateRequest(const RawAddress& bd_addr, uint16_t subrate_min,
                                uint16_t subrate_max, uint16_t max_latency, uint16_t cont_num,
                                uint16_t timeout) {
  // Logging done in l2c_ble.cc
  if (!stack::l2cap::get_interface().L2CA_SubrateRequest(bd_addr, subrate_min, subrate_max,
                                                         max_latency, cont_num, timeout)) {
    log::warn("Unable to set L2CAP ble subrating peer:{}", bd_addr);
  }
}

void leConnectionUpdate(const RawAddress& bd_addr, uint16_t min_interval, uint16_t max_interval,
                        uint16_t latency, uint16_t timeout, uint16_t min_ce_len,
                        uint16_t max_ce_len) {
  stack::l2cap::get_interface().L2CA_AdjustConnectionIntervals(&min_interval, &max_interval,
                                                               BTM_BLE_CONN_INT_MIN);

  if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
    if (!stack::l2cap::get_interface().L2CA_UpdateBleConnParams(
                bd_addr, min_interval, max_interval, latency, timeout, min_ce_len, max_ce_len)) {
      log::error("Update connection parameters failed!");
    }
  } else {
    get_btm_client_interface().ble.BTM_BleSetPrefConnParams(bd_addr, min_interval, max_interval,
                                                            latency, timeout);
  }
}

}  // namespace bluetooth::stack
