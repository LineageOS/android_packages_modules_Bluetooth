/*
 * Copyright 2023 The Android Open Source Project
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

#define LOG_TAG "bt_bta_dm"

#include "bta/dm/bta_dm_disc.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <variant>
#include <vector>

#include "bta/dm/bta_dm_disc_int.h"
#include "bta/include/bta_gatt_api.h"
#include "btif/include/btif_storage.h"
#include "common/circular_buffer.h"
#include "common/strings.h"
#include "device/include/interop.h"
#include "internal_include/bt_target.h"
#include "main/shim/dumpsys.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/bt_name.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/gap_api.h"  // GAP_BleReadPeerPrefConnParams
#include "stack/include/hidh_api.h"
#include "stack/include/main_thread.h"
#include "stack/include/sdp_status.h"
#include "types/raw_address.h"

#ifdef TARGET_FLOSS
#include "stack/include/srvc_api.h"
#endif

using bluetooth::Uuid;
using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

static void btm_dm_start_gatt_discovery(const RawAddress& bd_addr);

namespace {
constexpr char kBtmLogTag[] = "SDP";

tBTA_DM_SERVICE_DISCOVERY_CB bta_dm_discovery_cb;
base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> default_sdp_performer =
        base::Bind(bta_dm_sdp_find_services);
base::RepeatingCallback<void(const RawAddress&)> default_gatt_performer =
        base::Bind(btm_dm_start_gatt_discovery);
base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> sdp_performer = default_sdp_performer;
base::RepeatingCallback<void(const RawAddress&)> gatt_performer = default_gatt_performer;

static bool is_same_device(const RawAddress& a, const RawAddress& b) {
  if (a == b) {
    return true;
  }

  auto devA = btm_find_dev(a);
  if (devA != nullptr && devA == btm_find_dev(b)) {
    return true;
  }

  return false;
}
}  // namespace

static void bta_dm_disc_sm_execute(tBTA_DM_DISC_EVT event, std::unique_ptr<tBTA_DM_MSG> msg);
static void post_disc_evt(tBTA_DM_DISC_EVT event, std::unique_ptr<tBTA_DM_MSG> msg) {
  if (do_in_main_thread(base::BindOnce(&bta_dm_disc_sm_execute, event, std::move(msg))) !=
      BT_STATUS_SUCCESS) {
    log::error("post_disc_evt failed");
  }
}

static void bta_dm_gatt_disc_complete(tCONN_ID conn_id, tGATT_STATUS status);
static void bta_dm_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);
static void bta_dm_execute_queued_discovery_request();
static void bta_dm_close_gatt_conn(uint16_t conn_id);

namespace {

struct gatt_interface_t {
  void (*BTA_GATTC_CancelOpen)(tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct);
  void (*BTA_GATTC_Refresh)(const RawAddress& remote_bda);
  void (*BTA_GATTC_GetGattDb)(tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                              btgatt_db_element_t** db, int* count);
  void (*BTA_GATTC_AppRegister)(const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                                BtaAppRegisterCallback cb, bool eatt_support);
  void (*BTA_GATTC_Close)(tCONN_ID conn_id);
  void (*BTA_GATTC_ServiceSearchRequest)(tCONN_ID conn_id, const bluetooth::Uuid* p_srvc_uuid);
  void (*BTA_GATTC_Open)(tGATT_IF client_if, const RawAddress& remote_bda,
                         tBTM_BLE_CONN_TYPE connection_type, bool opportunistic,
                         uint16_t preferred_mtu);
} default_gatt_interface = {
        .BTA_GATTC_CancelOpen =
                [](tGATT_IF client_if, const RawAddress& remote_bda, bool is_direct) {
                  BTA_GATTC_CancelOpen(client_if, remote_bda, is_direct);
                },
        .BTA_GATTC_Refresh = [](const RawAddress& remote_bda) { BTA_GATTC_Refresh(remote_bda); },
        .BTA_GATTC_GetGattDb =
                [](tCONN_ID conn_id, uint16_t start_handle, uint16_t end_handle,
                   btgatt_db_element_t** db, int* count) {
                  BTA_GATTC_GetGattDb(conn_id, start_handle, end_handle, db, count);
                },
        .BTA_GATTC_AppRegister =
                [](const std::string& name, tBTA_GATTC_CBACK* p_client_cb,
                   BtaAppRegisterCallback cb, bool eatt_support) {
                  BTA_GATTC_AppRegister(name, p_client_cb, cb, eatt_support);
                },
        .BTA_GATTC_Close = [](tCONN_ID conn_id) { BTA_GATTC_Close(conn_id); },
        .BTA_GATTC_ServiceSearchRequest =
                [](tCONN_ID conn_id, const bluetooth::Uuid* p_srvc_uuid) {
                  if (p_srvc_uuid) {
                    BTA_GATTC_ServiceSearchRequest(conn_id, *p_srvc_uuid);
                  } else {
                    BTA_GATTC_ServiceSearchAllRequest(conn_id);
                  }
                },
        .BTA_GATTC_Open =
                [](tGATT_IF client_if, const RawAddress& remote_bda,
                   tBTM_BLE_CONN_TYPE connection_type, bool opportunistic, uint16_t preferred_mtu) {
                  BTA_GATTC_Open(client_if, remote_bda, BLE_ADDR_PUBLIC, connection_type,
                                 BT_TRANSPORT_LE, opportunistic, LE_PHY_1M, preferred_mtu);
                },
};

gatt_interface_t* gatt_interface = &default_gatt_interface;

gatt_interface_t& get_gatt_interface() { return *gatt_interface; }

}  // namespace

void bta_dm_disc_gatt_cancel_open(const RawAddress& bd_addr) {
  get_gatt_interface().BTA_GATTC_CancelOpen(0, bd_addr, false);
  if (com::android::bluetooth::flags::cancel_open_discovery_client() &&
      bta_dm_discovery_cb.client_if != BTA_GATTS_INVALID_IF) {
    get_gatt_interface().BTA_GATTC_CancelOpen(bta_dm_discovery_cb.client_if, bd_addr, true);
  }
}

void bta_dm_disc_gatt_refresh(const RawAddress& bd_addr) {
  get_gatt_interface().BTA_GATTC_Refresh(bd_addr);
}

void bta_dm_disc_remove_device(const RawAddress& bd_addr) {
  if (bta_dm_discovery_cb.service_discovery_state == BTA_DM_DISCOVER_ACTIVE &&
      bta_dm_discovery_cb.peer_bdaddr == bd_addr) {
    log::info("Device removed while service discovery was pending, conclude the service discovery");
    bta_dm_gatt_disc_complete(GATT_INVALID_CONN_ID, (tGATT_STATUS)GATT_ERROR);
  }
}

static void bta_dm_discovery_set_state(tBTA_DM_SERVICE_DISCOVERY_STATE state) {
  bta_dm_discovery_cb.service_discovery_state = state;
}
static tBTA_DM_SERVICE_DISCOVERY_STATE bta_dm_discovery_get_state() {
  return bta_dm_discovery_cb.service_discovery_state;
}

// TODO. Currently we did nothing
static void bta_dm_discovery_cancel() {}

/*******************************************************************************
 *
 * Function         bta_dm_disc_disable_disc
 *
 * Description      Cancels an ongoing discovery in case of a Bluetooth disable
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_disc_disable_disc(void) {
  switch (bta_dm_discovery_get_state()) {
    case BTA_DM_DISCOVER_IDLE:
      break;
    case BTA_DM_DISCOVER_ACTIVE:
    default:
      log::debug("Discovery state machine is not idle so issuing discovery cancel current state:{}",
                 bta_dm_state_text(bta_dm_discovery_get_state()));
      bta_dm_discovery_cancel();
  }
}

void bta_dm_sdp_finished(RawAddress bda, tBTA_STATUS result, std::vector<bluetooth::Uuid> uuids,
                         std::vector<bluetooth::Uuid> gatt_uuids) {
  bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{
                                                              .bd_addr = bda,
                                                              .uuids = uuids,
                                                              .gatt_uuids = gatt_uuids,
                                                              .result = result,
                                                      }));
}

/* Callback from sdp with discovery status */
void bta_dm_sdp_callback(const RawAddress& /* bd_addr */, tSDP_STATUS sdp_status) {
  bool sdp_pending = bta_dm_discovery_cb.transports & BT_TRANSPORT_BR_EDR;
  log::info("{}, sdp_pending: {}", bta_dm_state_text(bta_dm_discovery_get_state()), sdp_pending);

  if (bta_dm_discovery_get_state() == BTA_DM_DISCOVER_IDLE || !sdp_pending ||
      !bta_dm_discovery_cb.sdp_state) {
    return;
  }

  do_in_main_thread(
          base::BindOnce(&bta_dm_sdp_result, sdp_status, bta_dm_discovery_cb.sdp_state.get()));
}

/** Callback of peer's DIS reply. This is only called for floss */
#if TARGET_FLOSS
void bta_dm_sdp_received_di(const RawAddress& bd_addr, tSDP_DI_GET_RECORD& di_record) {
  bta_dm_discovery_cb.service_search_cbacks.on_did_received(
          bd_addr, di_record.rec.vendor_id_source, di_record.rec.vendor, di_record.rec.product,
          di_record.rec.version);
}

static void bta_dm_read_dis_cmpl(const RawAddress& addr, tDIS_VALUE* p_dis_value) {
  if (!p_dis_value) {
    log::warn("read DIS failed");
  } else {
    bta_dm_discovery_cb.service_search_cbacks.on_did_received(
            addr, p_dis_value->pnp_id.vendor_id_src, p_dis_value->pnp_id.vendor_id,
            p_dis_value->pnp_id.product_id, p_dis_value->pnp_id.product_version);
  }

  if (!bta_dm_discovery_cb.transports) {
    bta_dm_execute_queued_discovery_request();
  }
}
#endif

/*******************************************************************************
 *
 * Function         bta_dm_disc_result
 *
 * Description      Service discovery result when discovering services on a
 *                  device
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_disc_result(tBTA_DM_SVC_RES& disc_result) {
  log::verbose("");

  /* if any BR/EDR service discovery has been done, report the event */
  if (!disc_result.is_gatt_over_ble) {
    bta_dm_discovery_cb.transports &= ~BT_TRANSPORT_BR_EDR;

    auto& r = disc_result;
    if (!r.gatt_uuids.empty()) {
      log::info("Sending GATT services discovered using SDP");
      // send GATT result back to app, if any
      bta_dm_discovery_cb.service_search_cbacks.on_gatt_results(r.bd_addr, r.gatt_uuids,
                                                                /* transport_le */ false);
    }
    bta_dm_discovery_cb.service_search_cbacks.on_service_discovery_results(r.bd_addr, r.uuids,
                                                                           r.result);
  } else {
    char remote_name[BD_NAME_LEN] = "";
    bta_dm_discovery_cb.transports &= ~BT_TRANSPORT_LE;
    if (btif_storage_get_stored_remote_name(bta_dm_discovery_cb.peer_bdaddr, remote_name) &&
        interop_match_name(INTEROP_DISABLE_LE_CONN_PREFERRED_PARAMS, remote_name)) {
      // Some devices provide PPCP values that are incompatible with the device-side firmware.
      log::info("disable PPCP read: interop matched name {} address {}", remote_name,
                bta_dm_discovery_cb.peer_bdaddr);
    } else {
      log::info("reading PPCP");
      GAP_BleReadPeerPrefConnParams(bta_dm_discovery_cb.peer_bdaddr);
    }

    bta_dm_discovery_cb.service_search_cbacks.on_gatt_results(bta_dm_discovery_cb.peer_bdaddr,
                                                              disc_result.gatt_uuids,
                                                              /* transport_le */ true);
  }

  if (!bta_dm_discovery_cb.transports) {
    bta_dm_discovery_set_state(BTA_DM_DISCOVER_IDLE);
  }

#if TARGET_FLOSS
  if (bta_dm_discovery_cb.conn_id != GATT_INVALID_CONN_ID &&
      DIS_ReadDISInfo(bta_dm_discovery_cb.peer_bdaddr, bta_dm_read_dis_cmpl, DIS_ATTR_PNP_ID_BIT)) {
    return;
  }
#endif

  if (!bta_dm_discovery_cb.transports) {
    bta_dm_execute_queued_discovery_request();
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_queue_disc
 *
 * Description      Queues discovery command
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_queue_disc(tBTA_DM_API_DISCOVER& discovery) {
  log::info("bta_dm_discovery: queuing service discovery to {} [{}]", discovery.bd_addr,
            bt_transport_text(discovery.transport));
  bta_dm_discovery_cb.pending_discovery_queue.push(discovery);
}

static void bta_dm_execute_queued_discovery_request() {
  if (bta_dm_discovery_cb.pending_discovery_queue.empty()) {
    bta_dm_discovery_cb.sdp_state.reset();
    log::info("No more service discovery queued");
    return;
  }

  tBTA_DM_API_DISCOVER pending_discovery = bta_dm_discovery_cb.pending_discovery_queue.front();
  bta_dm_discovery_cb.pending_discovery_queue.pop();
  log::info("Start pending discovery {} [{}]", pending_discovery.bd_addr,
            pending_discovery.transport);
  post_disc_evt(BTA_DM_API_DISCOVER_EVT,
                std::make_unique<tBTA_DM_MSG>(tBTA_DM_API_DISCOVER{pending_discovery}));
}

/*******************************************************************************
 *
 * Function         bta_dm_determine_discovery_transport
 *
 * Description      Starts name and service discovery on the device
 *
 * Returns          void
 *
 ******************************************************************************/
static tBT_TRANSPORT bta_dm_determine_discovery_transport(const RawAddress& remote_bd_addr) {
  tBT_DEVICE_TYPE dev_type;
  tBLE_ADDR_TYPE addr_type;

  get_btm_client_interface().peer.BTM_ReadDevInfo(remote_bd_addr, &dev_type, &addr_type);
  if (dev_type == BT_DEVICE_TYPE_BLE || addr_type == BLE_ADDR_RANDOM) {
    return BT_TRANSPORT_LE;
  } else if (dev_type == BT_DEVICE_TYPE_DUMO) {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(remote_bd_addr,
                                                              BT_TRANSPORT_BR_EDR)) {
      return BT_TRANSPORT_BR_EDR;
    } else if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(remote_bd_addr,
                                                                     BT_TRANSPORT_LE)) {
      return BT_TRANSPORT_LE;
    }
  }
  return BT_TRANSPORT_BR_EDR;
}

/* Discovers services on a remote device */
static void bta_dm_discover_services(tBTA_DM_API_DISCOVER& discover) {
  bta_dm_disc_gattc_register();

  RawAddress bd_addr = discover.bd_addr;
  tBT_TRANSPORT transport = (discover.transport == BT_TRANSPORT_AUTO)
                                    ? bta_dm_determine_discovery_transport(bd_addr)
                                    : discover.transport;

  log::info("starting service discovery to: {}, transport: {}", bd_addr,
            bt_transport_text(transport));

  bta_dm_discovery_cb.service_search_cbacks = discover.cbacks;

  bta_dm_discovery_cb.peer_bdaddr = bd_addr;

  /* Classic mouses with this attribute should not start SDP here, because the
    SDP has been done during bonding. SDP request here will interleave with
    connections to the Control or Interrupt channels */
  if (HID_HostSDPDisable(bd_addr)) {
    log::info("peer:{} with HIDSDPDisable attribute.", bd_addr);

    /* service discovery is done for this device */
    bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT,
                           std::make_unique<tBTA_DM_MSG>(
                                   tBTA_DM_SVC_RES{.bd_addr = bd_addr, .result = BTA_SUCCESS}));
    return;
  }

  BTM_LogHistory(kBtmLogTag, bd_addr, "Discovery started ",
                 std::format("Transport:{}", bt_transport_text(transport)));

  if (transport == BT_TRANSPORT_LE) {
    if (bta_dm_discovery_cb.transports & BT_TRANSPORT_LE) {
      log::info("won't start GATT discovery - already started {}", bd_addr);
      return;
    } else {
      log::info("starting GATT discovery on {}", bd_addr);
      /* start GATT for service discovery */
      bta_dm_discovery_cb.transports |= BT_TRANSPORT_LE;
      gatt_performer.Run(bd_addr);
      return;
    }
  }

  // transport == BT_TRANSPORT_BR_EDR
  if (bta_dm_discovery_cb.transports & BT_TRANSPORT_BR_EDR) {
    log::info("won't start SDP - already started {}", bd_addr);
  } else {
    log::info("starting SDP discovery on {}", bd_addr);
    bta_dm_discovery_cb.transports |= BT_TRANSPORT_BR_EDR;

    bta_dm_discovery_cb.sdp_state = std::make_unique<tBTA_DM_SDP_STATE>(tBTA_DM_SDP_STATE{
            .bd_addr = bd_addr,
            .services_to_search = BTA_ALL_SERVICE_MASK,
            .services_found = 0,
            .service_index = 0,
    });
    sdp_performer.Run(bta_dm_discovery_cb.sdp_state.get());
  }
}

void bta_dm_disc_override_sdp_performer_for_testing(
        base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> test_sdp_performer) {
  if (test_sdp_performer.is_null()) {
    sdp_performer = default_sdp_performer;
  } else {
    sdp_performer = test_sdp_performer;
  }
}
void bta_dm_disc_override_gatt_performer_for_testing(
        base::RepeatingCallback<void(const RawAddress&)> test_gatt_performer) {
  if (test_gatt_performer.is_null()) {
    gatt_performer = default_gatt_performer;
  } else {
    gatt_performer = test_gatt_performer;
  }
}

#ifndef BTA_DM_GATT_CLOSE_DELAY_TOUT
#define BTA_DM_GATT_CLOSE_DELAY_TOUT 1000
#endif

/*******************************************************************************
 *
 * Function         bta_dm_disc_gattc_register
 *
 * Description      Register with GATTC in DM if BLE is needed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_disc_gattc_register(void) {
  if (bta_dm_discovery_cb.client_if != BTA_GATTS_INVALID_IF) {
    // Already registered
    return;
  }
  get_gatt_interface().BTA_GATTC_AppRegister(
          "bta_dm_disc_gatt", bta_dm_gattc_callback,
          base::Bind([](uint8_t client_id, uint8_t status) {
            tGATT_STATUS gatt_status = static_cast<tGATT_STATUS>(status);
            if (static_cast<tGATT_STATUS>(status) == GATT_SUCCESS) {
              log::info("Registered device discovery search gatt client tGATT_IF:{}", client_id);
              bta_dm_discovery_cb.client_if = client_id;
            } else {
              log::warn(
                      "Failed to register device discovery search gatt client "
                      "gatt_status:{} previous tGATT_IF:{}",
                      bta_dm_discovery_cb.client_if, status);
              bta_dm_discovery_cb.client_if = BTA_GATTS_INVALID_IF;
            }
          }),
          false);
}

static void gatt_close_timer_cb(void* data) {
  uint16_t conn_id = PTR_TO_UINT(data);
  bta_dm_disc_sm_execute(BTA_DM_DISC_CLOSE_TOUT_EVT,
                         std::make_unique<tBTA_DM_MSG>(tBTA_DM_TOUT{.conn_id = conn_id}));
}

void bta_dm_gatt_finished(RawAddress bda, tBTA_STATUS result,
                          std::vector<bluetooth::Uuid> gatt_uuids) {
  bta_dm_disc_sm_execute(BTA_DM_DISCOVERY_RESULT_EVT, std::make_unique<tBTA_DM_MSG>(tBTA_DM_SVC_RES{
                                                              .bd_addr = bda,
                                                              .is_gatt_over_ble = true,
                                                              .gatt_uuids = gatt_uuids,
                                                              .result = result,
                                                      }));
}

/*******************************************************************************
 *
 * Function         bta_dm_gatt_disc_complete
 *
 * Description      This function process the GATT service search complete.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_gatt_disc_complete(tCONN_ID conn_id, tGATT_STATUS status) {
  bool sdp_pending = bta_dm_discovery_cb.transports & BT_TRANSPORT_BR_EDR;
  bool le_pending = bta_dm_discovery_cb.transports & BT_TRANSPORT_LE;

  log::verbose("conn_id = {}, status = {}, sdp_pending = {}, le_pending = {}", conn_id, status,
               sdp_pending, le_pending);

  if (sdp_pending && !le_pending) {
    /* LE Service discovery finished, and services were reported, but SDP is not
     * finished yet. gatt_close_timer closed the connection, and we received
     * this callback because of disconnection */
    return;
  }

  std::vector<Uuid> gatt_services;

  if (conn_id != GATT_INVALID_CONN_ID && status == GATT_SUCCESS) {
    btgatt_db_element_t* db = NULL;
    int count = 0;
    get_gatt_interface().BTA_GATTC_GetGattDb(conn_id, 0x0000, 0xFFFF, &db, &count);
    if (count != 0) {
      for (int i = 0; i < count; i++) {
        // we process service entries only
        if (db[i].type == BTGATT_DB_PRIMARY_SERVICE) {
          gatt_services.push_back(db[i].uuid);
        }
      }
      osi_free(db);
    }
    log::info("GATT services discovered using LE Transport, count: {}", gatt_services.size());
  }

  /* no more services to be discovered */
  bta_dm_gatt_finished(bta_dm_discovery_cb.peer_bdaddr,
                       (status == GATT_SUCCESS) ? BTA_SUCCESS : BTA_FAILURE,
                       std::move(gatt_services));

  if (conn_id != GATT_INVALID_CONN_ID) {
    bta_dm_discovery_cb.pending_close_bda = bta_dm_discovery_cb.peer_bdaddr;
    // Gatt will be close immediately if bluetooth.gatt.delay_close.enabled is
    // set to false. If property is true / unset there will be a delay
    if (bta_dm_discovery_cb.gatt_close_timer != nullptr) {
      /* start a GATT channel close delay timer */
      alarm_set_on_mloop(bta_dm_discovery_cb.gatt_close_timer, BTA_DM_GATT_CLOSE_DELAY_TOUT,
                         gatt_close_timer_cb, UINT_TO_PTR(conn_id));
    } else {
      bta_dm_disc_sm_execute(BTA_DM_DISC_CLOSE_TOUT_EVT,
                             std::make_unique<tBTA_DM_MSG>(tBTA_DM_TOUT{.conn_id = conn_id}));
    }
  } else {
    log::info("Discovery complete for invalid conn ID. Will pick up next job");

    if (com::android::bluetooth::flags::cancel_open_discovery_client()) {
      bta_dm_close_gatt_conn(bta_dm_discovery_cb.conn_id);
    } else {
      bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
    }
    if (bta_dm_discovery_cb.transports & BT_TRANSPORT_BR_EDR) {
      log::info("classic discovery still pending {}", bta_dm_discovery_cb.peer_bdaddr);
      return;
    }
    bta_dm_discovery_set_state(BTA_DM_DISCOVER_IDLE);
    bta_dm_execute_queued_discovery_request();
  }
}

/* This function close the GATT connection after delay timeout */
static void bta_dm_close_gatt_conn(uint16_t conn_id) {
  if (com::android::bluetooth::flags::bta_dm_disc_close_proper_conn_id()) {
    if (conn_id != GATT_INVALID_CONN_ID) {
      BTA_GATTC_Close(conn_id);
    }
  } else {
    if (bta_dm_discovery_cb.conn_id != GATT_INVALID_CONN_ID) {
      BTA_GATTC_Close(bta_dm_discovery_cb.conn_id);
    }
  }

  bta_dm_discovery_cb.pending_close_bda = RawAddress::kEmpty;

  if (!com::android::bluetooth::flags::bta_dm_disc_close_proper_conn_id() ||
      bta_dm_discovery_cb.conn_id == conn_id) {
    bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
  }
}
/*******************************************************************************
 *
 * Function         btm_dm_start_gatt_discovery
 *
 * Description      This is GATT initiate the service search by open a GATT
 *                  connection first.
 *
 * Parameters:
 *
 ******************************************************************************/
static void btm_dm_start_gatt_discovery(const RawAddress& bd_addr) {
  constexpr bool kUseOpportunistic = true;

  /* connection is already open */
  if (bta_dm_discovery_cb.pending_close_bda == bd_addr &&
      bta_dm_discovery_cb.conn_id != GATT_INVALID_CONN_ID) {
    bta_dm_discovery_cb.pending_close_bda = RawAddress::kEmpty;
    alarm_cancel(bta_dm_discovery_cb.gatt_close_timer);
    get_gatt_interface().BTA_GATTC_ServiceSearchRequest(bta_dm_discovery_cb.conn_id, nullptr);
  } else {
    if (get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE)) {
      log::debug(
              "Use existing gatt client connection for discovery peer:{} "
              "transport:{} opportunistic:{:c}",
              bd_addr, bt_transport_text(BT_TRANSPORT_LE), (kUseOpportunistic) ? 'T' : 'F');
      get_gatt_interface().BTA_GATTC_Open(bta_dm_discovery_cb.client_if, bd_addr,
                                          BTM_BLE_DIRECT_CONNECTION, kUseOpportunistic, 0);
    } else {
      log::debug(
              "Opening new gatt client connection for discovery peer:{} "
              "transport:{} opportunistic:{:c}",
              bd_addr, bt_transport_text(BT_TRANSPORT_LE), (!kUseOpportunistic) ? 'T' : 'F');
      get_gatt_interface().BTA_GATTC_Open(bta_dm_discovery_cb.client_if, bd_addr,
                                          BTM_BLE_DIRECT_CONNECTION, !kUseOpportunistic, 0);
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_proc_open_evt
 *
 * Description      process BTA_GATTC_OPEN_EVT in DM.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_proc_open_evt(tBTA_GATTC_OPEN* p_data) {
  log::verbose("DM Search state= {} bta_dm_discovery_cb.peer_dbaddr:{} connected_bda={}",
               bta_dm_discovery_get_state(), bta_dm_discovery_cb.peer_bdaddr, p_data->remote_bda);

  log::debug("BTA_GATTC_OPEN_EVT conn_id = {} client_if={} status = {}", p_data->conn_id,
             p_data->client_if, p_data->status);

  bta_dm_discovery_cb.conn_id = p_data->conn_id;

  if (p_data->status == GATT_SUCCESS) {
    get_gatt_interface().BTA_GATTC_ServiceSearchRequest(p_data->conn_id, nullptr);
  } else {
    bta_dm_gatt_disc_complete(GATT_INVALID_CONN_ID, p_data->status);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_gattc_callback
 *
 * Description      This is GATT client callback function used in DM.
 *
 * Parameters:
 *
 ******************************************************************************/
static void bta_dm_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
  log::verbose("bta_dm_gattc_callback event = {}", event);

  switch (event) {
    case BTA_GATTC_OPEN_EVT:
      bta_dm_proc_open_evt(&p_data->open);
      break;

    case BTA_GATTC_SEARCH_CMPL_EVT:
      if (bta_dm_discovery_get_state() == BTA_DM_DISCOVER_ACTIVE) {
        bta_dm_gatt_disc_complete(p_data->search_cmpl.conn_id, p_data->search_cmpl.status);
      }
      break;

    case BTA_GATTC_CLOSE_EVT:
      log::info("BTA_GATTC_CLOSE_EVT reason = {}", p_data->close.reason);

      if (p_data->close.remote_bda == bta_dm_discovery_cb.peer_bdaddr) {
        bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
      }

      if (bta_dm_discovery_get_state() == BTA_DM_DISCOVER_ACTIVE) {
        /* in case of disconnect before search is completed */
        if (p_data->close.remote_bda == bta_dm_discovery_cb.peer_bdaddr) {
          bta_dm_gatt_disc_complete(GATT_INVALID_CONN_ID, (tGATT_STATUS)GATT_ERROR);
        }
      }
      break;

    case BTA_GATTC_CANCEL_OPEN_EVT:
    case BTA_GATTC_CFG_MTU_EVT:
    case BTA_GATTC_CONGEST_EVT:
    case BTA_GATTC_CONN_UPDATE_EVT:
    case BTA_GATTC_DEREG_EVT:
    case BTA_GATTC_ENC_CMPL_CB_EVT:
    case BTA_GATTC_EXEC_EVT:
    case BTA_GATTC_NOTIF_EVT:
    case BTA_GATTC_PHY_UPDATE_EVT:
    case BTA_GATTC_SEARCH_RES_EVT:
    case BTA_GATTC_SRVC_CHG_EVT:
    case BTA_GATTC_SRVC_DISC_DONE_EVT:
    case BTA_GATTC_SUBRATE_CHG_EVT:
      break;
  }
}

namespace bluetooth {
namespace legacy {
namespace testing {

tBT_TRANSPORT bta_dm_determine_discovery_transport(const RawAddress& bd_addr) {
  return ::bta_dm_determine_discovery_transport(bd_addr);
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

namespace {
constexpr char kTimeFormatString[] = "%Y-%m-%d %H:%M:%S";

constexpr unsigned MillisPerSecond = 1000;
std::string EpochMillisToString(uint64_t time_ms) {
  time_t time_sec = time_ms / MillisPerSecond;
  struct tm tm;
  localtime_r(&time_sec, &tm);
  std::string s = bluetooth::common::StringFormatTime(kTimeFormatString, tm);
  return std::format("{}.{:03}", s, time_ms % MillisPerSecond);
}

}  // namespace

struct tDISCOVERY_STATE_HISTORY {
  const tBTA_DM_SERVICE_DISCOVERY_STATE state;
  const tBTA_DM_DISC_EVT event;
  std::string ToString() const {
    return std::format("state:{:25s} event:{}", bta_dm_state_text(state), bta_dm_event_text(event));
  }
};

bluetooth::common::TimestampedCircularBuffer<tDISCOVERY_STATE_HISTORY> discovery_state_history_(
        50 /*history size*/);

static void bta_dm_disc_sm_execute(tBTA_DM_DISC_EVT event, std::unique_ptr<tBTA_DM_MSG> msg) {
  log::info("state:{}, event:{}[0x{:x}]", bta_dm_state_text(bta_dm_discovery_get_state()),
            bta_dm_event_text(event), event);
  discovery_state_history_.Push({
          .state = bta_dm_discovery_get_state(),
          .event = event,
  });

  switch (bta_dm_discovery_get_state()) {
    case BTA_DM_DISCOVER_IDLE:
      switch (event) {
        case BTA_DM_API_DISCOVER_EVT:
          bta_dm_discovery_set_state(BTA_DM_DISCOVER_ACTIVE);
          log::assert_that(std::holds_alternative<tBTA_DM_API_DISCOVER>(*msg),
                           "bad message type: {}", msg->index());

          bta_dm_discover_services(std::get<tBTA_DM_API_DISCOVER>(*msg));
          break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_TOUT>(*msg), "bad message type: {}",
                           msg->index());
          bta_dm_close_gatt_conn(std::get<tBTA_DM_TOUT>(*msg).conn_id);
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}", bta_dm_event_text(event),
                    event, bta_dm_state_text(bta_dm_discovery_get_state()));
      }
      break;

    case BTA_DM_DISCOVER_ACTIVE:
      switch (event) {
        case BTA_DM_DISCOVERY_RESULT_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_SVC_RES>(*msg), "bad message type: {}",
                           msg->index());

          bta_dm_disc_result(std::get<tBTA_DM_SVC_RES>(*msg));
          break;
        case BTA_DM_API_DISCOVER_EVT: {
          log::assert_that(std::holds_alternative<tBTA_DM_API_DISCOVER>(*msg),
                           "bad message type: {}", msg->index());

          auto req = std::get<tBTA_DM_API_DISCOVER>(*msg);
          if (is_same_device(req.bd_addr, bta_dm_discovery_cb.peer_bdaddr)) {
            bta_dm_discover_services(std::get<tBTA_DM_API_DISCOVER>(*msg));
          } else {
            bta_dm_queue_disc(std::get<tBTA_DM_API_DISCOVER>(*msg));
          }
        } break;
        case BTA_DM_DISC_CLOSE_TOUT_EVT:
          log::assert_that(std::holds_alternative<tBTA_DM_TOUT>(*msg), "bad message type: {}",
                           msg->index());
          bta_dm_close_gatt_conn(std::get<tBTA_DM_TOUT>(*msg).conn_id);
          break;
        default:
          log::info("Received unexpected event {}[0x{:x}] in state {}", bta_dm_event_text(event),
                    event, bta_dm_state_text(bta_dm_discovery_get_state()));
      }
      break;
  }
}

static void bta_dm_disc_init_discovery_cb(tBTA_DM_SERVICE_DISCOVERY_CB& bta_dm_discovery_cb) {
  bta_dm_discovery_cb = {};
  bta_dm_discovery_cb.service_discovery_state = BTA_DM_DISCOVER_IDLE;
  bta_dm_discovery_cb.conn_id = GATT_INVALID_CONN_ID;
}

static void bta_dm_disc_reset() {
  alarm_free(bta_dm_discovery_cb.gatt_close_timer);
  bta_dm_disc_init_discovery_cb(::bta_dm_discovery_cb);
}

void bta_dm_disc_start(bool delay_close_gatt) {
  bta_dm_disc_reset();
  bta_dm_discovery_cb.gatt_close_timer =
          delay_close_gatt ? alarm_new("bta_dm_search.gatt_close_timer") : nullptr;
  bta_dm_discovery_cb.pending_discovery_queue = {};
}

void bta_dm_disc_stop() { bta_dm_disc_reset(); }

void bta_dm_disc_start_service_discovery(service_discovery_callbacks cbacks,
                                         const RawAddress& bd_addr, tBT_TRANSPORT transport) {
  bta_dm_disc_sm_execute(BTA_DM_API_DISCOVER_EVT,
                         std::make_unique<tBTA_DM_MSG>(tBTA_DM_API_DISCOVER{
                                 .bd_addr = bd_addr, .cbacks = cbacks, .transport = transport}));
}

#define DUMPSYS_TAG "shim::legacy::bta::dm"
void DumpsysBtaDmDisc(int fd) {
  auto copy = discovery_state_history_.Pull();
  LOG_DUMPSYS(fd, " last %zu discovery state transitions", copy.size());
  for (const auto& it : copy) {
    LOG_DUMPSYS(fd, "   %s %s", EpochMillisToString(it.timestamp).c_str(),
                it.entry.ToString().c_str());
  }
  LOG_DUMPSYS(fd, " current bta_dm_discovery_state:%s",
              bta_dm_state_text(bta_dm_discovery_get_state()).c_str());
}
#undef DUMPSYS_TAG
