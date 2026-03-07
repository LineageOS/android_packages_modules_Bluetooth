/******************************************************************************
 *
 *  Copyright 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  this file contains GATT LE L2CAP channel configuration and callbacks.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "btif/include/btif_dm.h"
#include "btif/include/btif_storage.h"
#include "btif/include/stack_manager_t.h"
#include "internal_include/stack_config.h"
#include "main/shim/acl_api.h"
#include "osi/include/allocator.h"
#include "stack/arbiter/acl_arbiter.h"
#include "stack/connection_manager/connection_manager.h"
#include "stack/eatt/eatt.h"
#include "stack/gatt/gatt_int.h"
#include "stack/include/acl_api.h"
#include "stack/include/btm_sec_api.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/l2cdefs.h"
#include "stack/include/srvc_api.h"  // tDIS_VALUE

using bluetooth::eatt::EattExtension;
using namespace bluetooth;

static void gatt_le_connect_cback(uint16_t chan, const RawAddress& bd_addr, bool connected,
                                  uint16_t reason, tBT_TRANSPORT transport);
static void gatt_le_data_ind(uint16_t chan, const RawAddress& bd_addr, BT_HDR* p_buf);
static void gatt_le_cong_cback(const RawAddress& remote_bda, bool congest);
static bool check_cached_model_name(const RawAddress& bd_addr);
static void read_dis_cback(const RawAddress& bd_addr, tDIS_VALUE* p_dis_value);

void gatt_init_le(void) {
  tL2CAP_FIXED_CHNL_REG fixed_reg = {
          .pL2CA_FixedConn_Cb = gatt_le_connect_cback,
          .pL2CA_FixedData_Cb = gatt_le_data_ind,
          .pL2CA_FixedCong_Cb = gatt_le_cong_cback, /* congestion callback */
          .default_idle_tout = L2CAP_NO_IDLE_TIMEOUT};

  if (!stack::l2cap::get_interface().L2CA_RegisterFixedChannel(L2CAP_ATT_CID, &fixed_reg)) {
    log::error("Unable to register L2CAP ATT fixed channel");
  }
}

namespace connection_manager {
void on_connection_timed_out(uint8_t /* app_id */, const RawAddress& address) {
  gatt_le_connect_cback(L2CAP_ATT_CID, address, false, 0x08, BT_TRANSPORT_LE);
}
}  // namespace connection_manager

/** This callback function is called by L2CAP to indicate that the ATT fixed
 * channel for LE is connected (conn = true)/disconnected (conn = false).
 */
static void gatt_le_connect_cback(uint16_t /* chan */, const RawAddress& bd_addr, bool connected,
                                  uint16_t reason, tBT_TRANSPORT transport) {
  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, transport);
  bool check_srv_chg = false;
  tGATTS_SRV_CHG* p_srv_chg_clt = NULL;

  if (transport == BT_TRANSPORT_BR_EDR) {
    log::warn("Ignoring fixed channel connect/disconnect on br_edr for GATT");
    return;
  }

  log::verbose("GATT   ATT protocol channel with BDA: {} is {}", bd_addr,
               (connected) ? "connected" : "disconnected");

  p_srv_chg_clt = gatt_is_bda_in_the_srv_chg_clt_list(bd_addr);
  if (p_srv_chg_clt != NULL) {
    check_srv_chg = true;
  } else if (get_security_client_interface().BTM_IsBonded(bd_addr, BT_TRANSPORT_AUTO)) {
    gatt_add_a_bonded_dev_for_srv_chg(bd_addr);
    if (com_android_bluetooth_flags_send_service_changed_indication_upon_reconnection()) {
      p_srv_chg_clt = gatt_is_bda_in_the_srv_chg_clt_list(bd_addr);
      check_srv_chg = (p_srv_chg_clt != NULL);
    }
  }

  if (!connected) {
    if (p_tcb != nullptr) {
      bluetooth::shim::arbiter::GetArbiter().OnLeDisconnect(p_tcb->tcb_idx);
    }
    if (!com_android_bluetooth_flags_move_conn_mgr_callbacks()) {
      connection_manager::on_connection_complete(bd_addr);
    }
    gatt_cleanup_upon_disc(bd_addr, static_cast<tGATT_DISCONN_REASON>(reason), transport);

    if (com_android_bluetooth_flags_le_subrate_manager()) {
      // release when acl disconnected
      gatt_release_subrate_cb(bd_addr);
    }

    return;
  }

  if (!p_tcb) {
    p_tcb = gatt_allocate_tcb_by_bdaddr(bd_addr, BT_TRANSPORT_LE);
    if (!p_tcb) {
      log::error("Disconnecting address:{} due to out of resources.", bd_addr);
      // When single FIXED channel cannot be created, there is no reason to
      // keep the link
      btm_remove_acl(bd_addr, transport);
      return;
    }
    p_tcb->att_lcid = L2CAP_ATT_CID;
    p_tcb->ch_state = GATT_CH_CONN;
  }

  /* Queue MTU exchange before the connection callback
   * is pushed to application layers so that MTU exchange
   * is the very first GATT exchange
   */
  if (com_android_bluetooth_flags_gatt_conn_settings()) {
    p_tcb->payload_size = GATT_DEF_BLE_MTU_SIZE;
    // Set the default based on the APP's preference
    GATTC_SetDefaultMtu(p_tcb->peer_bda);
  }

  /* this is incoming connection or background connection callback */
  if (gatt_get_ch_state(p_tcb) == GATT_CH_CONN) {
    /* send callback */
    gatt_set_ch_state(p_tcb, GATT_CH_OPEN);
    if (!com_android_bluetooth_flags_gatt_conn_settings()) {
      p_tcb->payload_size = GATT_DEF_BLE_MTU_SIZE;
    }

    // Update connection state
    gatt_send_conn_cback(p_tcb);
  }
  if (check_srv_chg) {
    // If the database hash has been changed, we should send it.
    if (com_android_bluetooth_flags_send_service_changed_indication_upon_reconnection() &&
        !p_srv_chg_clt->srv_changed && !p_tcb->is_robust_cache_change_aware) {
      p_srv_chg_clt->srv_changed = true;
      p_srv_chg_clt->start_handle = GATT_GATT_START_HANDLE;
    }
    gatt_chk_srv_chg(p_srv_chg_clt);
  }

  auto advertising_set = bluetooth::shim::ACL_GetAdvertisingSetConnectedTo(bd_addr);

  if (advertising_set.has_value()) {
    bluetooth::shim::arbiter::GetArbiter().OnLeConnect(p_tcb->tcb_idx, advertising_set.value());
  }

  bool device_le_audio_capable = is_le_audio_capable_during_service_discovery(bd_addr);
  if (device_le_audio_capable) {
    log::info("Read model name for le audio capable device");
    if (!check_cached_model_name(bd_addr)) {
      if (!DIS_ReadDISInfo(bd_addr, read_dis_cback, DIS_ATTR_MODEL_NUM_BIT)) {
        log::warn("Read DIS failed");
      }
    }
  } else if (check_cached_model_name(bd_addr)) {
    log::info("Get cache model name for device");
  }

  if (stack_config_get_interface()->get_pts_connect_eatt_before_encryption()) {
    log::info("Start EATT before encryption");
    EattExtension::GetInstance()->Connect(bd_addr);
  }

  /* TODO: This preference should be used to exchange MTU with the peer device before the apps are
   * notified of the connection. */
  uint16_t app_mtu_pref = gatt_get_apps_preferred_mtu(bd_addr);
  gatt_remove_apps_mtu_prefs(bd_addr);
  p_tcb->app_mtu_pref = app_mtu_pref;
  if (app_mtu_pref > GATT_DEF_BLE_MTU_SIZE) {
    log::verbose("Combined app MTU prefs for {}: {}", bd_addr, app_mtu_pref);
  }
}

static bool check_cached_model_name(const RawAddress& bd_addr) {
  bt_property_t prop;
  bt_bdname_t model_name;
  BTIF_STORAGE_FILL_PROPERTY(&prop, BT_PROPERTY_REMOTE_MODEL_NUM, sizeof(model_name), &model_name);

  if (btif_storage_get_remote_device_property(bd_addr, &prop) != BT_STATUS_SUCCESS ||
      prop.len == 0) {
    log::info("Device {} no cached model name", bd_addr);
    return false;
  }

  tBLE_ADDR_TYPE addr_type = BLE_ADDR_PUBLIC;
  bt_property_t addr_type_prop = {BT_PROPERTY_REMOTE_ADDR_TYPE, sizeof(addr_type), &addr_type};
  btif_storage_get_remote_device_property(bd_addr, &addr_type_prop);

  GetInterfaceToProfiles()->events->invoke_remote_device_properties_cb(BT_STATUS_SUCCESS, bd_addr,
                                                                       addr_type, 1, &prop);
  return true;
}

static void read_dis_cback(const RawAddress& bd_addr, tDIS_VALUE* p_dis_value) {
  if (p_dis_value == NULL) {
    log::error("received unexpected/error DIS callback");
    return;
  }

  if (p_dis_value->attr_mask & DIS_ATTR_MODEL_NUM_BIT) {
    for (int i = 0; i < DIS_MAX_STRING_DATA; i++) {
      if (p_dis_value->data_string[i] != NULL) {
        bt_property_t prop;
        prop.type = BT_PROPERTY_REMOTE_MODEL_NUM;
        prop.val = p_dis_value->data_string[i];
        prop.len = strlen((char*)prop.val);

        log::info("Device {}, model name: {}", bd_addr, (char*)prop.val);

        btif_storage_set_remote_device_property(bd_addr, &prop);

        tBLE_ADDR_TYPE addr_type = BLE_ADDR_PUBLIC;
        bt_property_t addr_type_prop = {BT_PROPERTY_REMOTE_ADDR_TYPE, sizeof(addr_type),
                                        &addr_type};
        btif_storage_get_remote_device_property(bd_addr, &addr_type_prop);

        GetInterfaceToProfiles()->events->invoke_remote_device_properties_cb(
                BT_STATUS_SUCCESS, bd_addr, addr_type, 1, &prop);
      }
    }
  } else {
    log::error("unknown bit, mask: {}", (int)p_dis_value->attr_mask);
  }
}

/** This function is called when GATT fixed channel is congested or uncongested
 */
static void gatt_le_cong_cback(const RawAddress& remote_bda, bool congested) {
  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(remote_bda, BT_TRANSPORT_LE);
  if (!p_tcb) {
    return;
  }

  /* if uncongested, check to see if there is any more pending data */
  gatt_channel_congestion(p_tcb, congested);
}

/*******************************************************************************
 *
 * Function         gatt_le_data_ind
 *
 * Description      This function is called when data is received from L2CAP.
 *                  if we are the originator of the connection, we are the ATT
 *                  client, and the received message is queued up for the
 *                  client.
 *
 *                  If we are the destination of the connection, we are the ATT
 *                  server, so the message is passed to the server processing
 *                  function.
 *
 * Returns          void
 *
 ******************************************************************************/
static void gatt_le_data_ind(uint16_t /* chan */, const RawAddress& bd_addr, BT_HDR* p_buf) {
  /* Find CCB based on bd addr */
  tGATT_TCB* p_tcb = gatt_find_tcb_by_addr(bd_addr, BT_TRANSPORT_LE);
  if (p_tcb) {
    auto decision =
            bluetooth::shim::arbiter::GetArbiter().InterceptAttPacket(p_tcb->tcb_idx, p_buf);

    if (decision == bluetooth::shim::arbiter::InterceptAction::DROP) {
      // do nothing, just free it at the end
    } else if (gatt_get_ch_state(p_tcb) < GATT_CH_OPEN) {
      log::warn("ATT - Ignored L2CAP data while in state: {}", gatt_get_ch_state(p_tcb));
    } else {
      gatt_data_process(*p_tcb, L2CAP_ATT_CID, p_buf);
    }
  }

  osi_free(p_buf);
}
