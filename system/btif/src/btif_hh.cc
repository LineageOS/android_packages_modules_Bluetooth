/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
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

/*******************************************************************************
 *
 *  Filename:      btif_hh.c
 *
 *  Description:   HID Host Profile Bluetooth Interface
 *
 *
 ******************************************************************************/

#define LOG_TAG "bt_btif_hh"

#include "btif/include/btif_hh.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <unistd.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "bt_device_type.h"
#include "bta_api.h"
#include "bta_hh_api.h"
#include "bta_hh_co.h"
#include "bta_sec_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_dm.h"
#include "btif/include/btif_hd.h"
#include "btif/include/btif_metrics_logging.h"
#include "btif/include/btif_profile_storage.h"
#include "btif/include/btif_storage.h"
#include "btif/include/btif_util.h"
#include "hardware/bluetooth.h"
#include "include/hardware/bt_hh.h"
#include "internal_include/bt_target.h"
#include "main/shim/dumpsys.h"
#include "osi/include/alarm.h"
#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/hidh_api.h"
#include "types/ble_address_with_type.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

#define COD_HID_KEYBOARD 0x0540
#define COD_HID_POINTING 0x0580
#define COD_HID_COMBO 0x05C0

#define HID_REPORT_CAPSLOCK 0x39
#define HID_REPORT_NUMLOCK 0x53
#define HID_REPORT_SCROLLLOCK 0x47

// For Apple Magic Mouse
#define MAGICMOUSE_VENDOR_ID 0x05ac
#define MAGICMOUSE_PRODUCT_ID 0x030d

#define LOGITECH_KB_MX5500_VENDOR_ID 0x046D
#define LOGITECH_KB_MX5500_PRODUCT_ID 0xB30B

using namespace bluetooth;

static int btif_hh_keylockstates = 0;  // The current key state of each key

#define BTIF_TIMEOUT_VUP_MS (3 * 1000)

#define BTIF_HH_INCOMING_CONNECTION_DURING_BONDING_TIMEOUT_MS (4 * 1000)
#define BTIF_HH_UNEXPECTED_INCOMING_CONNECTION_TIMEOUT_MS (1 * 1000)

/* HH request events */
typedef enum {
  BTIF_HH_CONNECT_REQ_EVT = 0,
  BTIF_HH_DISCONNECT_REQ_EVT,
  BTIF_HH_VUP_REQ_EVT
} btif_hh_req_evt_t;

/*******************************************************************************
 *  Constants & Macros
 ******************************************************************************/

/*******************************************************************************
 *  Local type definitions
 ******************************************************************************/

typedef struct hid_kb_list {
  uint16_t product_id;
  uint16_t version_id;
  const char* kb_name;
} tHID_KB_LIST;

/*******************************************************************************
 *  Static variables
 ******************************************************************************/
btif_hh_cb_t btif_hh_cb;

static bthh_callbacks_t* bt_hh_callbacks = NULL;
static bthh_profile_enable_t bt_hh_enable_type = {.hidp_enabled = true, .hogp_enabled = true};

/* List of HID keyboards for which the NUMLOCK state needs to be
 * turned ON by default. Add devices to this list to apply the
 * NUMLOCK state toggle on fpr first connect.*/
static tHID_KB_LIST hid_kb_numlock_on_list[] = {
        {LOGITECH_KB_MX5500_PRODUCT_ID, LOGITECH_KB_MX5500_VENDOR_ID, "Logitech MX5500 Keyboard"}};

#define CHECK_BTHH_INIT()                 \
  do {                                    \
    if (bt_hh_callbacks == NULL) {        \
      log::error("BTHH not initialized"); \
      return BT_STATUS_NOT_READY;         \
    }                                     \
  } while (0)

#define BTHH_CHECK_NOT_DISABLED()                                           \
  do {                                                                      \
    if (btif_hh_cb.status == BTIF_HH_DISABLED) {                            \
      log::error("HH status = {}", btif_hh_status_text(btif_hh_cb.status)); \
      return BT_STATUS_UNEXPECTED_STATE;                                    \
    }                                                                       \
  } while (0)

#define BTHH_LOG_UNKNOWN_LINK(_link_spec) log::error("Unknown link: {}", (_link_spec))
#define BTHH_LOG_LINK(_link_spec) log::verbose("link spec: {}", (_link_spec))

#define BTHH_STATE_UPDATE(_link_spec, _state)                                                  \
  do {                                                                                         \
    log::verbose("link spec: {} state: {}", (_link_spec), bthh_connection_state_text(_state)); \
    HAL_CBACK(bt_hh_callbacks, connection_state_cb, &(_link_spec).addrt.bda,                   \
              (_link_spec).addrt.type, (_link_spec).transport, (_state));                      \
  } while (0)

/*******************************************************************************
 *  Static functions
 ******************************************************************************/

static void btif_hh_transport_select(tAclLinkSpec& link_spec);
static void btif_hh_timer_timeout(void* data);
static void bte_hh_evt(tBTA_HH_EVT event, tBTA_HH* p_data);

/*******************************************************************************
 *  Functions
 ******************************************************************************/

static int get_keylockstates() { return btif_hh_keylockstates; }

static void set_keylockstate(int keymask, bool isSet) {
  if (isSet) {
    btif_hh_keylockstates |= keymask;
  }
}

/*******************************************************************************
 *
 * Function         toggle_os_keylockstates
 *
 * Description      Function to toggle the keyboard lock states managed by the
 linux.
 *                  This function is used in by two call paths
 *                  (1) if the lock state change occurred from an onscreen
 keyboard,
 *                  this function is called to update the lock state maintained
                    for the HID keyboard(s)
 *                  (2) if a HID keyboard is disconnected and reconnected,
 *                  this function is called to update the lock state maintained
                    for the HID keyboard(s)
 * Returns          void
 ******************************************************************************/

static void toggle_os_keylockstates(int fd, int changedlockstates) {
  log::verbose("fd = {}, changedlockstates = 0x{:x}", fd, changedlockstates);
  uint8_t hidreport[9];
  int reportIndex;
  memset(hidreport, 0, 9);
  hidreport[0] = 1;
  reportIndex = 4;

  if (changedlockstates & BTIF_HH_KEYSTATE_MASK_CAPSLOCK) {
    log::verbose("Setting CAPSLOCK");
    hidreport[reportIndex++] = (uint8_t)HID_REPORT_CAPSLOCK;
  }

  if (changedlockstates & BTIF_HH_KEYSTATE_MASK_NUMLOCK) {
    log::verbose("Setting NUMLOCK");
    hidreport[reportIndex++] = (uint8_t)HID_REPORT_NUMLOCK;
  }

  if (changedlockstates & BTIF_HH_KEYSTATE_MASK_SCROLLLOCK) {
    log::verbose("Setting SCROLLLOCK");
    hidreport[reportIndex++] = (uint8_t)HID_REPORT_SCROLLLOCK;
  }

  log::verbose("Writing hidreport #1 to os:");
  log::verbose("| {:x} {:x} {:x}", hidreport[0], hidreport[1], hidreport[2]);
  log::verbose("| {:x} {:x} {:x}", hidreport[3], hidreport[4], hidreport[5]);
  log::verbose("| {:x} {:x} {:x}", hidreport[6], hidreport[7], hidreport[8]);
  bta_hh_co_write(fd, hidreport, sizeof(hidreport));
  usleep(200000);
  memset(hidreport, 0, 9);
  hidreport[0] = 1;
  log::verbose("Writing hidreport #2 to os:");
  log::verbose("| {:x} {:x} {:x}", hidreport[0], hidreport[1], hidreport[2]);
  log::verbose("| {:x} {:x} {:x}", hidreport[3], hidreport[4], hidreport[5]);
  log::verbose("| {:x} {:x} {:x}", hidreport[6], hidreport[7], hidreport[8]);
  bta_hh_co_write(fd, hidreport, sizeof(hidreport));
}

/*******************************************************************************
 *
 * Function         create_pbuf
 *
 * Description      Helper function to create p_buf for send_data or set_report
 *
 ******************************************************************************/
static BT_HDR* create_pbuf(uint16_t len, uint8_t* data) {
  BT_HDR* p_buf = (BT_HDR*)osi_malloc(len + BTA_HH_MIN_OFFSET + sizeof(BT_HDR));
  uint8_t* pbuf_data;

  p_buf->len = len;
  p_buf->offset = BTA_HH_MIN_OFFSET;

  pbuf_data = (uint8_t*)(p_buf + 1) + p_buf->offset;
  memcpy(pbuf_data, data, len);

  return p_buf;
}

/*******************************************************************************
 *
 * Function         update_keyboard_lockstates
 *
 * Description      Sends a report to the keyboard to set the lock states of
 *                  keys.
 *
 ******************************************************************************/
static void update_keyboard_lockstates(btif_hh_device_t* p_dev) {
  uint8_t len = 2; /* reportid + 1 byte report*/
  BT_HDR* p_buf;
  uint8_t data[] = {0x01,                                         /* report id */
                    static_cast<uint8_t>(btif_hh_keylockstates)}; /* keystate */

  /* Set report for other keyboards */
  log::verbose("setting report on dev_handle {} to 0x{:x}", p_dev->dev_handle,
               btif_hh_keylockstates);

  /* Get SetReport buffer */
  p_buf = create_pbuf(len, data);
  if (p_buf != NULL) {
    p_buf->layer_specific = BTA_HH_RPTT_OUTPUT;
    BTA_HhSendData(p_dev->dev_handle, p_dev->link_spec, p_buf);
  }
}

/*******************************************************************************
 *
 * Function         sync_lockstate_on_connect
 *
 * Description      Function to update the keyboard lock states managed by the
 *                  OS when a HID keyboard is connected or disconnected and
 *                  reconnected
 *
 * Returns          void
 ******************************************************************************/
static void sync_lockstate_on_connect(btif_hh_device_t* p_dev, tBTA_HH_DEV_DSCP_INFO& dscp_info) {
  for (unsigned int i = 0; i < sizeof(hid_kb_numlock_on_list) / sizeof(tHID_KB_LIST); i++) {
    tHID_KB_LIST& kb = hid_kb_numlock_on_list[i];
    if (dscp_info.vendor_id == kb.version_id && dscp_info.product_id == kb.product_id) {
      log::verbose("idx[{}] Enabling NUMLOCK for device {} {}", i, p_dev->link_spec, kb.kb_name);
      // Enable NUMLOCK by default so that numeric keys work from first keyboard connect
      set_keylockstate(BTIF_HH_KEYSTATE_MASK_NUMLOCK, true);
      update_keyboard_lockstates(p_dev);

      // If the lockstate of caps, scroll or num is set, send a report to the kernel
      int keylockstates = get_keylockstates();
      if (keylockstates) {
        log::verbose("Sending HID report to kernel indicating lock key state 0x{:x} for device {}",
                     keylockstates, p_dev->link_spec);
        usleep(200000);
        int fd = (com::android::bluetooth::flags::hid_report_queuing() ? p_dev->internal_send_fd
                                                                       : p_dev->uhid.fd);
        toggle_os_keylockstates(fd, keylockstates);
      }
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function         btif_hh_find_added_dev
 *
 * Description      Return the added device pointer of the specified link spec
 *
 * Returns          Added device entry
 ******************************************************************************/
static btif_hh_added_device_t* btif_hh_find_added_dev(const tAclLinkSpec& link_spec) {
  for (int i = 0; i < BTIF_HH_MAX_ADDED_DEV; i++) {
    btif_hh_added_device_t* added_dev = &btif_hh_cb.added_devices[i];
    if (added_dev->link_spec == link_spec) {
      return added_dev;
    }
  }
  return NULL;
}

/*******************************************************************************
 *
 * Function         btif_hh_find_connected_dev_by_handle
 *
 * Description      Return the connected device pointer of the specified device
 *                  handle
 *
 * Returns          Device entry pointer in the device table
 ******************************************************************************/
btif_hh_device_t* btif_hh_find_connected_dev_by_handle(uint8_t handle) {
  uint32_t i;
  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    if (btif_hh_cb.devices[i].dev_status == BTHH_CONN_STATE_CONNECTED &&
        btif_hh_cb.devices[i].dev_handle == handle) {
      return &btif_hh_cb.devices[i];
    }
  }
  return NULL;
}

/*******************************************************************************
 *
 * Function         btif_hh_find_dev_by_handle
 *
 * Description      Return the device pointer of the specified device handle
 *
 * Returns          Device entry pointer in the device table
 ******************************************************************************/
btif_hh_device_t* btif_hh_find_dev_by_handle(uint8_t handle) {
  for (int i = 0; i < BTIF_HH_MAX_HID; i++) {
    btif_hh_device_t* p_dev = &btif_hh_cb.devices[i];
    if (p_dev->dev_status != BTHH_CONN_STATE_UNKNOWN && p_dev->dev_handle == handle) {
      return p_dev;
    }
  }
  return nullptr;
}

/*******************************************************************************
 *
 * Function         btif_hh_find_empty_dev
 *
 * Description      Return an empty device
 *
 * Returns          Device entry pointer in the device table
 ******************************************************************************/
btif_hh_device_t* btif_hh_find_empty_dev(void) {
  for (int i = 0; i < BTIF_HH_MAX_HID; i++) {
    btif_hh_device_t* p_dev = &btif_hh_cb.devices[i];
    if (p_dev->dev_status == BTHH_CONN_STATE_UNKNOWN) {
      return p_dev;
    }
  }
  return nullptr;
}

/*******************************************************************************
 *
 * Function         btif_hh_find_dev_by_link_spec
 *
 * Description      Return the device pointer of the specified ACL link
 *                  specification.
 *
 * Returns          Device entry pointer in the device table
 ******************************************************************************/
static btif_hh_device_t* btif_hh_find_dev_by_link_spec(const tAclLinkSpec& link_spec) {
  uint32_t i;
  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    if (btif_hh_cb.devices[i].dev_status != BTHH_CONN_STATE_UNKNOWN &&
        btif_hh_cb.devices[i].link_spec == link_spec) {
      return &btif_hh_cb.devices[i];
    }
  }
  return NULL;
}

/*******************************************************************************
 *
 * Function         btif_hh_find_connected_dev_by_link_spec
 *
 * Description      Return the connected device pointer of the specified ACL
 *                  link specification.
 *
 * Returns          Device entry pointer in the device table
 ******************************************************************************/
static btif_hh_device_t* btif_hh_find_connected_dev_by_link_spec(const tAclLinkSpec& link_spec) {
  uint32_t i;
  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    if (btif_hh_cb.devices[i].dev_status == BTHH_CONN_STATE_CONNECTED &&
        btif_hh_cb.devices[i].link_spec == link_spec) {
      return &btif_hh_cb.devices[i];
    }
  }
  return NULL;
}

/*******************************************************************************
 *
 * Function      btif_hh_stop_vup_timer
 *
 * Description  stop virtual unplug timer
 *
 * Returns      void
 ******************************************************************************/
static void btif_hh_stop_vup_timer(const tAclLinkSpec& link_spec) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);

  if (p_dev != NULL) {
    log::verbose("stop VUP timer");
    alarm_free(p_dev->vup_timer);
    p_dev->vup_timer = NULL;
  }
}
/*******************************************************************************
 *
 * Function      btif_hh_start_vup_timer
 *
 * Description  start virtual unplug timer
 *
 * Returns      void
 ******************************************************************************/
static void btif_hh_start_vup_timer(const tAclLinkSpec& link_spec) {
  log::verbose("");

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  log::assert_that(p_dev != NULL, "assert failed: p_dev != NULL");

  alarm_free(p_dev->vup_timer);
  p_dev->vup_timer = alarm_new("btif_hh.vup_timer");
  alarm_set_on_mloop(p_dev->vup_timer, BTIF_TIMEOUT_VUP_MS, btif_hh_timer_timeout, p_dev);
}

static void btif_hh_incoming_connection_timeout(void* data) {
  uint8_t handle = reinterpret_cast<size_t>(data) & 0xFF;
  tBTA_HH_CONN& conn = btif_hh_cb.pending_incoming_connection;
  if (conn.link_spec.addrt.bda.IsEmpty()) {
    log::warn("Unknown incoming connection timeout, handle: {}", handle);
    return;
  }

  if (conn.handle != handle) {
    log::error("Pending connection ({}) handle: {} does not match {}", conn.link_spec, conn.handle,
               handle);
  }
  log::warn("Reject unexpected incoming HID Connection, device: {}", conn.link_spec);
  log_counter_metrics_btif(
          android::bluetooth::CodePathCounterKeyEnum::HIDH_COUNT_INCOMING_CONNECTION_REJECTED, 1);

  btif_hh_device_t* p_dev = btif_hh_find_dev_by_link_spec(conn.link_spec);
  if (p_dev != nullptr) {
    p_dev->dev_status = BTHH_CONN_STATE_DISCONNECTED;
  }
  BTA_HhRemoveDev(conn.handle);
  btif_hh_cb.pending_incoming_connection = {};
}

static bthh_connection_state_t hh_get_state_on_disconnect(tAclLinkSpec& link_spec) {
  btif_hh_added_device_t* added_dev = btif_hh_find_added_dev(link_spec);
  if (added_dev != nullptr) {
    return added_dev->reconnect_allowed ? BTHH_CONN_STATE_ACCEPTING : BTHH_CONN_STATE_DISCONNECTED;
  } else {
    return BTHH_CONN_STATE_DISCONNECTED;
  }
}

static void hh_connect_complete(tBTA_HH_CONN& conn, bthh_connection_state_t state) {
  if (state != BTHH_CONN_STATE_CONNECTED) {
    if (!com::android::bluetooth::flags::close_hid_only_if_connected() ||
        conn.status == BTA_HH_OK) {
      BTA_HhClose(conn.handle);
    }
  }
  BTHH_STATE_UPDATE(conn.link_spec, state);
}

/*******************************************************************************
 *
 * Function         hh_add_device
 *
 * Description      Add a new device to the added device list.
 *
 * Returns          true if add successfully, otherwise false.
 ******************************************************************************/
static bool hh_add_device(const tAclLinkSpec& link_spec, tBTA_HH_ATTR_MASK attr_mask,
                          bool reconnect_allowed) {
  int i;

  // Check if already added
  if (btif_hh_find_added_dev(link_spec) != nullptr) {
    log::warn("Device {} already added", link_spec);
    return false;
  }

  // Use an empty slot for the new device
  for (i = 0; i < BTIF_HH_MAX_ADDED_DEV; i++) {
    btif_hh_added_device_t& dev = btif_hh_cb.added_devices[i];
    if (dev.link_spec.addrt.bda.IsEmpty()) {
      log::info("Added device {}", link_spec);
      dev.link_spec = link_spec;
      dev.dev_handle = BTA_HH_INVALID_HANDLE;
      dev.attr_mask = attr_mask;
      dev.reconnect_allowed = reconnect_allowed;
      return true;
    }
  }

  log::error("Out of space to add device");
  log_counter_metrics_btif(
          android::bluetooth::CodePathCounterKeyEnum::HIDH_COUNT_MAX_ADDED_DEVICE_LIMIT_REACHED, 1);
  return false;
}

/*******************************************************************************
 *  BTA_HH event handlers
 ******************************************************************************/
static void hh_enable_handler(tBTA_HH_STATUS& status) {
  log::verbose("Status ={}", status);
  if (status == BTA_HH_OK) {
    btif_hh_cb.status = BTIF_HH_ENABLED;
    log::verbose("Loading added devices");
    /* Add hid descriptors for already bonded hid devices*/
    btif_storage_load_bonded_hid_info();
  } else {
    btif_hh_cb.status = BTIF_HH_DISABLED;
    log::warn("HH enabling failed, status = {}", status);
  }
}

static void hh_disable_handler(tBTA_HH_STATUS& status) {
  if (btif_hh_cb.status == BTIF_HH_DISABLING) {
    bt_hh_callbacks = NULL;
  }

  btif_hh_cb.status = BTIF_HH_DISABLED;
  if (btif_hh_cb.service_dereg_active) {
    log::verbose("Enabling HID Device service");
    btif_hd_service_registration();
    btif_hh_cb.service_dereg_active = FALSE;
  }
  if (status == BTA_HH_OK) {
    int i;
    // Clear the control block
    for (i = 0; i < BTIF_HH_MAX_HID; i++) {
      alarm_free(btif_hh_cb.devices[i].vup_timer);
    }
    btif_hh_cb = {};
    for (i = 0; i < BTIF_HH_MAX_HID; i++) {
      btif_hh_cb.devices[i].dev_status = BTHH_CONN_STATE_UNKNOWN;
    }
  } else {
    log::warn("HH disabling failed, status = {}", status);
  }
}

static void hh_open_handler(tBTA_HH_CONN& conn) {
  log::debug("link spec = {}, status = {}, handle = {}", conn.link_spec, conn.status, conn.handle);

  // Initialize with disconnected/accepting state based on reconnection policy
  bthh_connection_state_t dev_status = hh_get_state_on_disconnect(conn.link_spec);

  // Use current state if the device instance already exists
  btif_hh_device_t* p_dev = btif_hh_find_dev_by_link_spec(conn.link_spec);
  if (p_dev != nullptr) {
    log::debug("Device instance found: {}, state: {}", p_dev->link_spec,
               bthh_connection_state_text(p_dev->dev_status));
    dev_status = p_dev->dev_status;
  }

  if (std::find(btif_hh_cb.new_connection_requests.begin(),
                btif_hh_cb.new_connection_requests.end(),
                conn.link_spec) != btif_hh_cb.new_connection_requests.end()) {
    log::verbose("Device connection was pending for: {}, status: {}", conn.link_spec,
                 btif_hh_status_text(btif_hh_cb.status));
    dev_status = BTHH_CONN_STATE_CONNECTING;
  }

  if (dev_status != BTHH_CONN_STATE_ACCEPTING && dev_status != BTHH_CONN_STATE_CONNECTING) {
    if (com::android::bluetooth::flags::early_incoming_hid_connection() &&
        conn.status == BTA_HH_OK && conn.link_spec.transport == BT_TRANSPORT_BR_EDR) {
      uint64_t delay = 0;
      if (btif_dm_is_pairing(conn.link_spec.addrt.bda)) {
        // Remote device is trying to connect while bonding is in progress. We should wait for
        // locally initiated connect request to plumb the remote device to UHID.
        log::warn(
                "Incoming HID connection during bonding, wait for local connect request {}, "
                "handle: {}",
                conn.link_spec, conn.handle);
        delay = BTIF_HH_INCOMING_CONNECTION_DURING_BONDING_TIMEOUT_MS;
      } else {
        // Unexpected incoming connection, wait for a while before rejecting.
        log::warn(
                "Unexpected incoming HID connection, wait for local connect request {}, handle: {}",
                conn.link_spec, conn.handle);
        delay = BTIF_HH_UNEXPECTED_INCOMING_CONNECTION_TIMEOUT_MS;
      }

      if (!btif_hh_cb.pending_incoming_connection.link_spec.addrt.bda.IsEmpty()) {
        log::error("Replacing existing pending connection {}",
                   btif_hh_cb.pending_incoming_connection.link_spec);
        BTA_HhRemoveDev(btif_hh_cb.pending_incoming_connection.handle);
      }
      btif_hh_cb.pending_incoming_connection = conn;
      alarm_cancel(btif_hh_cb.incoming_connection_timer);
      alarm_set_on_mloop(btif_hh_cb.incoming_connection_timer, delay,
                         btif_hh_incoming_connection_timeout, reinterpret_cast<void*>(conn.handle));

      return;
    }

    log::warn("Reject Incoming HID Connection, device: {}, state: {}", conn.link_spec,
              bthh_connection_state_text(dev_status));
    log_counter_metrics_btif(
            android::bluetooth::CodePathCounterKeyEnum::HIDH_COUNT_INCOMING_CONNECTION_REJECTED, 1);

    if (p_dev != nullptr) {
      p_dev->dev_status = BTHH_CONN_STATE_DISCONNECTED;
    }

    if (!com::android::bluetooth::flags::suppress_hid_rejection_broadcast()) {
      hh_connect_complete(conn, BTHH_CONN_STATE_DISCONNECTED);
      return;
    }
    BTA_HhClose(conn.handle);
    return;
  }

  btif_hh_cb.new_connection_requests.remove(conn.link_spec);

  if (conn.status != BTA_HH_OK) {
    btif_dm_hh_open_failed(&conn.link_spec.addrt.bda);
    p_dev = btif_hh_find_dev_by_link_spec(conn.link_spec);
    if (p_dev != nullptr) {
      btif_hh_stop_vup_timer(p_dev->link_spec);

      p_dev->dev_status = hh_get_state_on_disconnect(p_dev->link_spec);
    }
    hh_connect_complete(conn, BTHH_CONN_STATE_DISCONNECTED);
    return;
  }

  /* Initialize device driver */
  if (!bta_hh_co_open(conn.handle, conn.sub_class, conn.attr_mask, conn.app_id, conn.link_spec)) {
    log::warn("Failed to find the uhid driver");
    hh_connect_complete(conn, BTHH_CONN_STATE_DISCONNECTED);
    return;
  }

  p_dev = btif_hh_find_connected_dev_by_handle(conn.handle);
  if (p_dev == nullptr) {
    /* The connect request must have come from device side and exceeded the
     * connected HID device number. */
    log::warn("Cannot find device with handle {}", conn.handle);
    hh_connect_complete(conn, BTHH_CONN_STATE_DISCONNECTED);
    return;
  }

  log::info("Found device, getting dscp info for handle {}", conn.handle);

  if (!com::android::bluetooth::flags::hid_report_queuing()) {
    // link_spec and status is to be set in bta_hh_co_open instead.
    p_dev->link_spec = conn.link_spec;
    p_dev->dev_status = BTHH_CONN_STATE_CONNECTED;
  }
  hh_connect_complete(conn, BTHH_CONN_STATE_CONNECTED);

  if (!com::android::bluetooth::flags::dont_send_hid_set_idle()) {
    // Send set_idle if the peer_device is a keyboard
    // TODO (b/307923455): clean this, set idle is deprecated in HID spec v1.1.1
    if (btif_check_cod_hid_major(conn.link_spec.addrt.bda, COD_HID_KEYBOARD) ||
        btif_check_cod_hid_major(conn.link_spec.addrt.bda, COD_HID_COMBO)) {
      BTA_HhSetIdle(conn.handle, 0);
    }
  }
  BTA_HhGetDscpInfo(conn.handle);
}

static void hh_close_handler(tBTA_HH_CBDATA& dev_status) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_status.handle);
  if (p_dev == nullptr) {
    if (com::android::bluetooth::flags::early_incoming_hid_connection() &&
        btif_hh_cb.pending_incoming_connection.handle == dev_status.handle &&
        !btif_hh_cb.pending_incoming_connection.link_spec.addrt.bda.IsEmpty()) {
      log::warn("Pending incoming connection {} closed, handle: {} ",
                btif_hh_cb.pending_incoming_connection.link_spec, dev_status.handle);
      BTA_HhRemoveDev(btif_hh_cb.pending_incoming_connection.handle);
      alarm_cancel(btif_hh_cb.incoming_connection_timer);
      btif_hh_cb.pending_incoming_connection = {};
      return;
    }
    log::warn("Unknown device handle {}", dev_status.handle);
    return;
  }

  log::verbose("device {} status {}", p_dev->link_spec, dev_status.status);
  BTHH_STATE_UPDATE(p_dev->link_spec, BTHH_CONN_STATE_DISCONNECTING);
  btif_hh_stop_vup_timer(p_dev->link_spec);

  /* Remove device if locally initiated VUP */
  if (p_dev->local_vup) {
    log::info("Removing device {} after virtual unplug", p_dev->link_spec);
    p_dev->local_vup = false;
    btif_hh_remove_device(p_dev->link_spec);
    BTA_DmRemoveDevice(p_dev->link_spec.addrt.bda);
  } else if (dev_status.status == BTA_HH_HS_SERVICE_CHANGED) {
    /* Local disconnection due to service change in the HOGP device.
       HID descriptor would be read again, so remove it from cache. */
    log::warn("Removing cached descriptor due to service change, device {}", p_dev->link_spec);
    btif_storage_remove_hid_info(p_dev->link_spec);
  }

  p_dev->dev_status = hh_get_state_on_disconnect(p_dev->link_spec);
  bta_hh_co_close(p_dev);
  BTHH_STATE_UPDATE(p_dev->link_spec, p_dev->dev_status);
}

static void hh_get_rpt_handler(tBTA_HH_HSDATA& hs_data) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(hs_data.handle);
  if (p_dev == nullptr) {
    log::warn("Unknown device handle {}", hs_data.handle);
    return;
  }

  log::verbose("Status = {}, handle = {}", hs_data.status, hs_data.handle);
  BT_HDR* hdr = hs_data.rsp_data.p_rpt_data;

  if (hdr) { /* Get report response */
    uint8_t* data = (uint8_t*)(hdr + 1) + hdr->offset;
    uint16_t len = hdr->len;
    HAL_CBACK(bt_hh_callbacks, get_report_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
              p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
              (bthh_status_t)hs_data.status, data, len);

    bta_hh_co_get_rpt_rsp(p_dev->dev_handle, (tBTA_HH_STATUS)hs_data.status, data, len);
  } else { /* Handshake */
    HAL_CBACK(bt_hh_callbacks, handshake_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
              p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
              (bthh_status_t)hs_data.status);
    if (com::android::bluetooth::flags::forward_get_set_report_failure_to_uhid()) {
      bta_hh_co_get_rpt_rsp(p_dev->dev_handle, (tBTA_HH_STATUS)hs_data.status, NULL, 0);
    }
  }
}

static void hh_set_rpt_handler(tBTA_HH_CBDATA& dev_status) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_status.handle);
  if (p_dev == nullptr) {
    log::warn("Unknown device handle {}", dev_status.handle);
    return;
  }

  log::verbose("Status = {}, handle = {}", dev_status.status, dev_status.handle);
  HAL_CBACK(bt_hh_callbacks, handshake_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
            p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
            (bthh_status_t)dev_status.status);

  bta_hh_co_set_rpt_rsp(p_dev->dev_handle, dev_status.status);
}

static void hh_get_proto_handler(tBTA_HH_HSDATA& hs_data) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(hs_data.handle);
  if (p_dev == nullptr) {
    log::warn("Unknown device handle {}", hs_data.handle);
    return;
  }

  log::info("Status = {}, handle = {}, proto = [{}], {}", hs_data.status, hs_data.handle,
            hs_data.rsp_data.proto_mode,
            (hs_data.rsp_data.proto_mode == BTA_HH_PROTO_RPT_MODE)    ? "Report Mode"
            : (hs_data.rsp_data.proto_mode == BTA_HH_PROTO_BOOT_MODE) ? "Boot Mode"
                                                                      : "Unsupported");
  if (hs_data.rsp_data.proto_mode != BTA_HH_PROTO_UNKNOWN) {
    HAL_CBACK(bt_hh_callbacks, protocol_mode_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
              p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
              (bthh_status_t)hs_data.status, (bthh_protocol_mode_t)hs_data.rsp_data.proto_mode);
  } else {
    HAL_CBACK(bt_hh_callbacks, handshake_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
              p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
              (bthh_status_t)hs_data.status);
  }
}

static void hh_set_proto_handler(tBTA_HH_CBDATA& dev_status) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_status.handle);
  if (p_dev == nullptr) {
    log::warn("Unknown device handle {}", dev_status.handle);
    return;
  }

  log::verbose("Status = {}, handle = {}", dev_status.status, dev_status.handle);
  HAL_CBACK(bt_hh_callbacks, handshake_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
            p_dev->link_spec.addrt.type, p_dev->link_spec.transport,
            (bthh_status_t)dev_status.status);
}

static void hh_get_idle_handler(tBTA_HH_HSDATA& hs_data) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(hs_data.handle);
  if (p_dev == nullptr) {
    log::warn("Unknown device handle {}", hs_data.handle);
    return;
  }

  log::verbose("Handle = {}, status = {}, rate = {}", hs_data.handle, hs_data.status,
               hs_data.rsp_data.idle_rate);
  HAL_CBACK(bt_hh_callbacks, idle_time_cb, (RawAddress*)&(p_dev->link_spec.addrt.bda),
            p_dev->link_spec.addrt.type, p_dev->link_spec.transport, (bthh_status_t)hs_data.status,
            hs_data.rsp_data.idle_rate);
}

static void hh_set_idle_handler(tBTA_HH_CBDATA& dev_status) {
  log::verbose("Status = {}, handle = {}", dev_status.status, dev_status.handle);
}

static void hh_get_dscp_handler(tBTA_HH_DEV_DSCP_INFO& dscp_info) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dscp_info.hid_handle);
  if (p_dev == nullptr) {
    log::error("Unknown device handle {}", dscp_info.hid_handle);
    return;
  }

  log::verbose("Len = {}, handle = {}", dscp_info.descriptor.dl_len, dscp_info.hid_handle);
  int fd = (com::android::bluetooth::flags::hid_report_queuing() ? p_dev->internal_send_fd
                                                                 : p_dev->uhid.fd);
  if (fd < 0) {
    log::error("Failed to find the uhid driver for device {}", p_dev->link_spec);
    return;
  }

  const char* cached_name = nullptr;
  bt_bdname_t bdname = {};
  bt_property_t prop_name = {};
  BTIF_STORAGE_FILL_PROPERTY(&prop_name, BT_PROPERTY_BDNAME, sizeof(bt_bdname_t), &bdname);
  if (btif_storage_get_remote_device_property(&p_dev->link_spec.addrt.bda, &prop_name) ==
      BT_STATUS_SUCCESS) {
    cached_name = (char*)bdname.name;
  } else {
    cached_name = "Bluetooth HID";
  }
  log::info("Retrieved the cached name:{} for device {}", cached_name, p_dev->link_spec);
  bta_hh_co_send_hid_info(p_dev, cached_name, dscp_info.vendor_id, dscp_info.product_id,
                          dscp_info.version, dscp_info.ctry_code, dscp_info.descriptor.dl_len,
                          dscp_info.descriptor.dsc_list);
  if (hh_add_device(p_dev->link_spec, p_dev->attr_mask, true)) {
    bt_status_t ret;
    BTA_HhAddDev(p_dev->link_spec, p_dev->attr_mask, p_dev->sub_class, p_dev->app_id, dscp_info);
    // Save HID info in the persistent storage
    ret = btif_storage_add_hid_device_info(
            p_dev->link_spec, p_dev->attr_mask, p_dev->sub_class, p_dev->app_id,
            dscp_info.vendor_id, dscp_info.product_id, dscp_info.version, dscp_info.ctry_code,
            dscp_info.ssr_max_latency, dscp_info.ssr_min_tout, dscp_info.descriptor.dl_len,
            dscp_info.descriptor.dsc_list);

    // Allow incoming connections
    btif_storage_set_hid_connection_policy(p_dev->link_spec, true);

    ASSERTC(ret == BT_STATUS_SUCCESS, "storing hid info failed", ret);
    log::info("Added device {}", p_dev->link_spec);
  } else {
    log::warn("Device {} already added", p_dev->link_spec);
  }

  /* Sync HID Keyboard lockstates */
  sync_lockstate_on_connect(p_dev, dscp_info);
}

static void hh_add_dev_handler(tBTA_HH_DEV_INFO& dev_info) {
  btif_hh_added_device_t* added_dev = btif_hh_find_added_dev(dev_info.link_spec);
  if (added_dev == nullptr) {
    log::error("Unknown device {}", dev_info.link_spec);
    return;
  }

  log::info("Status = {}, handle = {}", dev_info.status, dev_info.handle);
  if (dev_info.status == BTA_HH_OK) {
    added_dev->dev_handle = dev_info.handle;
  } else {
    added_dev->link_spec = {};
    added_dev->dev_handle = BTA_HH_INVALID_HANDLE;
  }
}

static void hh_rmv_dev_handler(tBTA_HH_DEV_INFO& dev_info) {
  log::verbose("Status = {}, handle = {}, device = {}", dev_info.status, dev_info.handle,
               dev_info.link_spec);
}

static void hh_vc_unplug_handler(tBTA_HH_CBDATA& dev_status) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_status.handle);
  if (p_dev == nullptr) {
    log::error("Unknown device handle {}", dev_status.handle);
    return;
  }

  log::info("Device {} status {}", p_dev->link_spec, dev_status.status);

  /* Stop the VUP timer */
  btif_hh_stop_vup_timer(p_dev->link_spec);
  p_dev->dev_status = hh_get_state_on_disconnect(p_dev->link_spec);
  BTHH_STATE_UPDATE(p_dev->link_spec, p_dev->dev_status);

  if (!p_dev->local_vup) {
    log_counter_metrics_btif(android::bluetooth::CodePathCounterKeyEnum::
                                     HIDH_COUNT_VIRTUAL_UNPLUG_REQUESTED_BY_REMOTE_DEVICE,
                             1);
  }

  // Remove the HID device
  btif_hh_remove_device(p_dev->link_spec);
  if (p_dev->local_vup || btif_check_cod_hid(p_dev->link_spec.addrt.bda)) {
    // Remove the bond if locally initiated or remote device has major class HID
    p_dev->local_vup = false;
    BTA_DmRemoveDevice(p_dev->link_spec.addrt.bda);
  }
}

void btif_hh_load_bonded_dev(const tAclLinkSpec& link_spec_ref, tBTA_HH_ATTR_MASK attr_mask,
                             uint8_t sub_class, uint8_t app_id, tBTA_HH_DEV_DSCP_INFO dscp_info,
                             bool reconnect_allowed) {
  btif_hh_device_t* p_dev;
  uint8_t i;
  tAclLinkSpec link_spec = link_spec_ref;

  if (link_spec.transport == BT_TRANSPORT_AUTO) {
    log::warn("Resolving link spec {} transport to BREDR/LE", link_spec);
    btif_hh_transport_select(link_spec);
    reconnect_allowed = true;
    btif_storage_set_hid_connection_policy(link_spec, reconnect_allowed);

    // remove and re-write the hid info
    btif_storage_remove_hid_info(link_spec);
    btif_storage_add_hid_device_info(link_spec, attr_mask, sub_class, app_id, dscp_info.vendor_id,
                                     dscp_info.product_id, dscp_info.version, dscp_info.ctry_code,
                                     dscp_info.ssr_max_latency, dscp_info.ssr_min_tout,
                                     dscp_info.descriptor.dl_len, dscp_info.descriptor.dsc_list);
  }

  if (hh_add_device(link_spec, attr_mask, reconnect_allowed)) {
    if (reconnect_allowed) {
      BTHH_STATE_UPDATE(link_spec, BTHH_CONN_STATE_ACCEPTING);
    }
    BTA_HhAddDev(link_spec, attr_mask, sub_class, app_id, dscp_info);
  }
}

void btif_hh_disconnected(const RawAddress& addr, tBT_TRANSPORT transport) {
  if (!com::android::bluetooth::flags::hogp_reconnection()) {
    return;
  }

  // We want to reconnect HoGP in the background, so we're only interested in LE case.
  if (transport != BT_TRANSPORT_LE) {
    return;
  }

  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = addr;
  link_spec.addrt.type = BLE_ADDR_PUBLIC;
  link_spec.transport = BT_TRANSPORT_LE;

  if (com::android::bluetooth::flags::early_incoming_hid_connection() &&
      btif_hh_cb.pending_incoming_connection.link_spec == link_spec) {
    log::warn("Pending incoming connection {} closed, handle: {} ",
              btif_hh_cb.pending_incoming_connection.link_spec,
              btif_hh_cb.pending_incoming_connection.handle);
    BTA_HhRemoveDev(btif_hh_cb.pending_incoming_connection.handle);
    alarm_cancel(btif_hh_cb.incoming_connection_timer);
    btif_hh_cb.pending_incoming_connection = {};
  }

  btif_hh_device_t* p_dev = btif_hh_find_dev_by_link_spec(link_spec);
  if (p_dev == nullptr) {
    return;
  }

  btif_hh_added_device_t* added_dev = btif_hh_find_added_dev(link_spec);
  if (added_dev == nullptr || !added_dev->reconnect_allowed) {
    return;
  }

  log::debug("Rearm HoGP reconnection for {}", addr);
  BTA_HhOpen(p_dev->link_spec, false);
}

/*******************************************************************************
 **
 ** Function         btif_hh_remove_device
 **
 ** Description      Remove an added device from the stack.
 **
 ** Returns          void
 ******************************************************************************/
void btif_hh_remove_device(const tAclLinkSpec& link_spec) {
  BTHH_LOG_LINK(link_spec);
  bool announce_vup = false;

  if (com::android::bluetooth::flags::early_incoming_hid_connection() &&
      btif_hh_cb.pending_incoming_connection.link_spec == link_spec) {
    log::warn("Pending incoming connection {} closed, handle: {} ",
              btif_hh_cb.pending_incoming_connection.link_spec,
              btif_hh_cb.pending_incoming_connection.handle);
    BTA_HhRemoveDev(btif_hh_cb.pending_incoming_connection.handle);
    alarm_cancel(btif_hh_cb.incoming_connection_timer);
    btif_hh_cb.pending_incoming_connection = {};
  }

  for (int i = 0; i < BTIF_HH_MAX_ADDED_DEV; i++) {
    btif_hh_added_device_t* p_added_dev = &btif_hh_cb.added_devices[i];
    if (p_added_dev->link_spec == link_spec) {
      announce_vup = true;
      BTA_HhRemoveDev(p_added_dev->dev_handle);
      btif_storage_remove_hid_info(p_added_dev->link_spec);
      p_added_dev->link_spec = {};
      p_added_dev->dev_handle = BTA_HH_INVALID_HANDLE;

      /* Look for other instances only if AUTO transport was used */
      if (link_spec.transport != BT_TRANSPORT_AUTO) {
        break;
      }
    }
  }

  /* Remove all connections instances related to link_spec. If AUTO transport is
   * used, btif_hh_find_dev_by_link_spec() finds both HID and HOGP instances */
  btif_hh_device_t* p_dev;
  while ((p_dev = btif_hh_find_dev_by_link_spec(link_spec)) != nullptr) {
    announce_vup = true;
    // Notify service of disconnection to avoid state mismatch
    do_in_jni_thread(
            base::Bind([](tAclLinkSpec ls) { BTHH_STATE_UPDATE(ls, BTHH_CONN_STATE_DISCONNECTED); },
                       p_dev->link_spec));

    if (btif_hh_cb.device_num > 0) {
      btif_hh_cb.device_num--;
    } else {
      log::warn("device_num = 0");
    }

    if (com::android::bluetooth::flags::remove_pending_hid_connection()) {
      BTA_HhRemoveDev(p_dev->dev_handle);  // Remove the connection, in case it was pending
    }

    bta_hh_co_close(p_dev);
    p_dev->dev_status = BTHH_CONN_STATE_UNKNOWN;
    p_dev->dev_handle = BTA_HH_INVALID_HANDLE;
    if (!com::android::bluetooth::flags::hid_report_queuing()) {
      p_dev->uhid.ready_for_data = false;
    }
  }

  // Remove pending connection if address matches
  if (com::android::bluetooth::flags::vup_for_pending_connection()) {
    size_t pending_connections = btif_hh_cb.new_connection_requests.remove_if(
            [link_spec](auto ls) { return ls.addrt.bda == link_spec.addrt.bda; });
    if (pending_connections > 0) {
      announce_vup = true;
    }
  }

  if (!announce_vup) {
    log::info("Device {} not found", link_spec);
    return;
  }

  do_in_jni_thread(base::Bind(
          [](tAclLinkSpec ls) {
            HAL_CBACK(bt_hh_callbacks, virtual_unplug_cb, &ls.addrt.bda, ls.addrt.type,
                      ls.transport, BTHH_OK);
          },
          link_spec));
}

/*******************************************************************************
 **
 ** Function         btif_hh_remove_pending_connection
 **
 ** Description      Remove first time pending connection requests.
 **
 ** Returns          void
 ******************************************************************************/
static void btif_hh_remove_pending_connection(const tAclLinkSpec& link_spec) {
  if (!com::android::bluetooth::flags::vup_for_pending_connection()) {
    bool pending_connection = false;
    for (auto ls : btif_hh_cb.new_connection_requests) {
      if (ls.addrt.bda == link_spec.addrt.bda) {
        pending_connection = true;
        break;
      }
    }

    if (pending_connection) {
      btif_hh_cb.new_connection_requests.remove_if(
              [link_spec](auto ls) { return ls.addrt.bda == link_spec.addrt.bda; });

      // Notify service of disconnection to avoid state mismatch
      do_in_jni_thread(base::Bind(
              [](tAclLinkSpec ls) { BTHH_STATE_UPDATE(ls, BTHH_CONN_STATE_DISCONNECTED); },
              link_spec));
    }
    return;
  }

  size_t pending_connections = btif_hh_cb.new_connection_requests.remove_if([link_spec](auto ls) {
    if (ls.addrt.bda == link_spec.addrt.bda) {
      // Notify service of disconnection to avoid state mismatch
      do_in_jni_thread(base::Bind(
              [](tAclLinkSpec ls) { BTHH_STATE_UPDATE(ls, BTHH_CONN_STATE_DISCONNECTED); }, ls));

      return true;
    }
    return false;
  });

  if (pending_connections > 0) {
    log::verbose("Removed pending connections to {}", link_spec);
    do_in_jni_thread(base::Bind(
            [](tAclLinkSpec ls) {
              HAL_CBACK(bt_hh_callbacks, virtual_unplug_cb, &ls.addrt.bda, ls.addrt.type,
                        ls.transport, BTHH_OK);
            },
            link_spec));
  }
}

/*******************************************************************************
 *
 * Function         btif_hh_virtual_unplug
 *
 * Description      Virtual unplug initiated from the BTIF thread context
 *                  Special handling for HID mouse-
 *
 * Returns          void
 *
 ******************************************************************************/
bt_status_t btif_hh_virtual_unplug(const tAclLinkSpec& link_spec) {
  BTHH_LOG_LINK(link_spec);

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev != nullptr) {
    // Device is connected, send the VUP command and disconnect
    btif_hh_start_vup_timer(link_spec);
    p_dev->local_vup = true;
    if (p_dev->attr_mask & HID_VIRTUAL_CABLE) {
      log::info("Sending BTA_HH_CTRL_VIRTUAL_CABLE_UNPLUG for: {}", link_spec);
      BTA_HhSendCtrl(p_dev->dev_handle, BTA_HH_CTRL_VIRTUAL_CABLE_UNPLUG);
    } else {
      log::info("Virtual unplug not supported, disconnecting device: {}", link_spec);
      BTA_HhClose(p_dev->dev_handle);
    }
    return BT_STATUS_SUCCESS;
  }

  log::info("Device {} not opened, state = {}", link_spec, btif_hh_status_text(btif_hh_cb.status));

  // Remove the connecting or added device
  if (btif_hh_find_dev_by_link_spec(link_spec) != nullptr ||
      btif_hh_find_added_dev(link_spec) != nullptr) {
    if (!com::android::bluetooth::flags::vup_for_pending_connection()) {
      // Remove pending connection if address matches
      btif_hh_cb.new_connection_requests.remove_if(
              [link_spec](auto ls) { return ls.addrt.bda == link_spec.addrt.bda; });
    }
    btif_hh_remove_device(link_spec);
    BTA_DmRemoveDevice(link_spec.addrt.bda);
    return BT_STATUS_SUCCESS;
  }

  btif_hh_remove_pending_connection(link_spec);
  return BT_STATUS_DEVICE_NOT_FOUND;
}

/*******************************************************************************
 *
 * Function         btif_hh_connect
 *
 * Description      connection initiated from the BTIF thread context
 *
 * Returns          int status
 *
 ******************************************************************************/

bt_status_t btif_hh_connect(const tAclLinkSpec& link_spec) {
  CHECK_BTHH_INIT();
  log::verbose("BTHH");
  btif_hh_device_t* p_dev = btif_hh_find_dev_by_link_spec(link_spec);
  if (!p_dev && btif_hh_cb.device_num >= BTIF_HH_MAX_HID) {
    // No space for more HID device now.
    log::warn("Error, exceeded the maximum supported HID device number {}", BTIF_HH_MAX_HID);
    log_counter_metrics_btif(android::bluetooth::CodePathCounterKeyEnum::
                                     HIDH_COUNT_CONNECT_REQ_WHEN_MAX_DEVICE_LIMIT_REACHED,
                             1);
    return BT_STATUS_NOMEM;
  }

  btif_hh_added_device_t* added_dev = btif_hh_find_added_dev(link_spec);
  if (added_dev != nullptr) {
    log::info("Device {} already added, attr_mask = 0x{:x}", link_spec, added_dev->attr_mask);

    if (added_dev->dev_handle == BTA_HH_INVALID_HANDLE) {
      // No space for more HID device now.
      log::error("Device {} added but addition failed", link_spec);
      added_dev->link_spec = {};
      added_dev->dev_handle = BTA_HH_INVALID_HANDLE;
      return BT_STATUS_NOMEM;
    }

    // Reset the connection policy to allow incoming reconnections
    added_dev->reconnect_allowed = true;
    btif_storage_set_hid_connection_policy(link_spec, true);
  }

  if (p_dev && p_dev->dev_status == BTHH_CONN_STATE_CONNECTED) {
    log::debug("HidHost profile already connected for {}", link_spec);
    return BT_STATUS_SUCCESS;
  }

  if (p_dev) {
    p_dev->dev_status = BTHH_CONN_STATE_CONNECTING;
  }

  // Add the new connection to the pending list
  if (!com::android::bluetooth::flags::pending_hid_connection_cancellation() ||
      added_dev == nullptr) {
    btif_hh_cb.new_connection_requests.push_back(link_spec);
  }

  do_in_jni_thread(base::Bind(
          [](tAclLinkSpec link_spec) { BTHH_STATE_UPDATE(link_spec, BTHH_CONN_STATE_CONNECTING); },
          link_spec));

  if (com::android::bluetooth::flags::early_incoming_hid_connection() &&
      btif_hh_cb.pending_incoming_connection.link_spec == link_spec) {
    log::info("Resume pending incoming connection {}", link_spec);
    tBTA_HH_CONN conn = btif_hh_cb.pending_incoming_connection;
    alarm_cancel(btif_hh_cb.incoming_connection_timer);
    btif_hh_cb.pending_incoming_connection = {};
    hh_open_handler(conn);
    return BT_STATUS_SUCCESS;
  }

  /* Not checking the NORMALLY_Connectible flags from sdp record, and anyways
   sending this request from host, for subsequent user initiated connection.
   If the remote is not in pagescan mode, we will do 2 retries to connect before
   giving up */
  BTA_HhOpen(link_spec, true);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         btif_hh_disconnect
 *
 * Description      disconnection initiated from the BTIF thread context
 *
 * Returns          void
 *
 ******************************************************************************/
static void btif_hh_disconnect(const tAclLinkSpec& link_spec) {
  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == nullptr) {
    log::warn("Unable to disconnect unknown HID device:{}", link_spec);
    return;
  }
  log::debug("Disconnect and close request for HID device:{}", link_spec);
  BTA_HhClose(p_dev->dev_handle);
}

/*******************************************************************************
 *
 * Function         btif_btif_hh_setreport
 *
 * Description      setreport initiated from the UHID thread context
 *
 * Returns          void
 *
 ******************************************************************************/
void btif_hh_setreport(btif_hh_uhid_t* p_uhid, bthh_report_type_t r_type, uint16_t size,
                       uint8_t* report) {
  BT_HDR* p_buf = create_pbuf(size, report);
  if (p_buf == NULL) {
    log::error("Error, failed to allocate RPT buffer, size = {}", size);
    return;
  }
  BTA_HhSetReport(p_uhid->dev_handle, r_type, p_buf);
}

/*******************************************************************************
 *
 * Function         btif_btif_hh_senddata
 *
 * Description      senddata initiated from the UHID thread context
 *
 * Returns          void
 *
 ******************************************************************************/
void btif_hh_senddata(btif_hh_uhid_t* p_uhid, uint16_t size, uint8_t* report) {
  BT_HDR* p_buf = create_pbuf(size, report);
  if (p_buf == NULL) {
    log::error("Error, failed to allocate RPT buffer, size = {}", size);
    return;
  }
  p_buf->layer_specific = BTA_HH_RPTT_OUTPUT;
  BTA_HhSendData(p_uhid->dev_handle, p_uhid->link_spec, p_buf);
}

/*******************************************************************************
 *
 * Function         btif_hh_service_registration
 *
 * Description      Registers or derigisters the hid host service
 *
 * Returns          none
 *
 ******************************************************************************/
void btif_hh_service_registration(bool enable) {
  log::verbose("");

  log::verbose("enable = {}", enable);
  if (bt_hh_callbacks == NULL) {
    // The HID Host service was never initialized (it is either disabled or not
    // available in this build). We should proceed directly to changing the HID
    // Device service state (if needed).
    if (!enable) {
      btif_hd_service_registration();
    }
  } else if (enable) {
    BTA_HhEnable(bte_hh_evt, bt_hh_enable_type.hidp_enabled, bt_hh_enable_type.hogp_enabled);
  } else {
    btif_hh_cb.service_dereg_active = TRUE;
    BTA_HhDisable();
  }
}

/*******************************************************************************
 *
 *
 * Function         btif_hh_getreport
 *
 * Description      getreport initiated from the UHID thread context
 *
 * Returns          void
 *
 ******************************************************************************/
void btif_hh_getreport(btif_hh_uhid_t* p_uhid, bthh_report_type_t r_type, uint8_t reportId,
                       uint16_t bufferSize) {
  BTA_HhGetReport(p_uhid->dev_handle, r_type, reportId, bufferSize);
}

/*****************************************************************************
 *   Section name (Group of functions)
 ****************************************************************************/

/*****************************************************************************
 *
 *   btif hh api functions (no context switch)
 *
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         btif_hh_upstreams_evt
 *
 * Description      Executes HH UPSTREAMS events in btif context
 *
 * Returns          void
 *
 ******************************************************************************/
static void btif_hh_upstreams_evt(uint16_t event, char* p_param) {
  tBTA_HH* p_data = (tBTA_HH*)p_param;
  log::verbose("event={} dereg = {}", bta_hh_event_text(event), btif_hh_cb.service_dereg_active);

  switch (event) {
    case BTA_HH_ENABLE_EVT:
      hh_enable_handler(p_data->status);
      break;
    case BTA_HH_DISABLE_EVT:
      hh_disable_handler(p_data->status);
      break;
    case BTA_HH_OPEN_EVT:
      hh_open_handler(p_data->conn);
      break;
    case BTA_HH_CLOSE_EVT:
      hh_close_handler(p_data->dev_status);
      break;
    case BTA_HH_GET_RPT_EVT:
      hh_get_rpt_handler(p_data->hs_data);
      break;
    case BTA_HH_SET_RPT_EVT:
      hh_set_rpt_handler(p_data->dev_status);
      break;
    case BTA_HH_GET_PROTO_EVT:
      hh_get_proto_handler(p_data->hs_data);
      break;
    case BTA_HH_SET_PROTO_EVT:
      hh_set_proto_handler(p_data->dev_status);
      break;
    case BTA_HH_GET_IDLE_EVT:
      hh_get_idle_handler(p_data->hs_data);
      break;
    case BTA_HH_SET_IDLE_EVT:
      hh_set_idle_handler(p_data->dev_status);
      break;
    case BTA_HH_GET_DSCP_EVT:
      hh_get_dscp_handler(p_data->dscp_info);
      break;
    case BTA_HH_ADD_DEV_EVT:
      hh_add_dev_handler(p_data->dev_info);
      break;
    case BTA_HH_RMV_DEV_EVT:
      hh_rmv_dev_handler(p_data->dev_info);
      break;
    case BTA_HH_VC_UNPLUG_EVT:
      hh_vc_unplug_handler(p_data->dev_status);
      break;
    case BTA_HH_API_ERR_EVT:
      log::error("BTA_HH API_ERR");
      break;
    case BTA_HH_DATA_EVT:
      // data output is sent - do nothing.
      break;
    default:
      log::warn("Unhandled event: {}", event);
      break;
  }
}

/*******************************************************************************
 *
 * Function         btif_hh_hsdata_rpt_copy_cb
 *
 * Description      Deep copies the tBTA_HH_HSDATA structure
 *
 * Returns          void
 *
 ******************************************************************************/

static void btif_hh_hsdata_rpt_copy_cb(uint16_t /*event*/, char* p_dest, const char* p_src) {
  tBTA_HH_HSDATA* p_dst_data = (tBTA_HH_HSDATA*)p_dest;
  tBTA_HH_HSDATA* p_src_data = (tBTA_HH_HSDATA*)p_src;
  BT_HDR* hdr;

  if (!p_src) {
    log::error("Nothing to copy");
    return;
  }

  memcpy(p_dst_data, p_src_data, sizeof(tBTA_HH_HSDATA));

  hdr = p_src_data->rsp_data.p_rpt_data;
  if (hdr != NULL) {
    uint8_t* p_data = ((uint8_t*)p_dst_data) + sizeof(tBTA_HH_HSDATA);
    memcpy(p_data, hdr, BT_HDR_SIZE + hdr->offset + hdr->len);

    p_dst_data->rsp_data.p_rpt_data = (BT_HDR*)p_data;
  }
}

/*******************************************************************************
 *
 * Function         bte_hh_evt
 *
 * Description      Switches context from BTE to BTIF for all HH events
 *
 * Returns          void
 *
 ******************************************************************************/

static void bte_hh_evt(tBTA_HH_EVT event, tBTA_HH* p_data) {
  bt_status_t status;
  int param_len = 0;
  tBTIF_COPY_CBACK* p_copy_cback = NULL;

  if (BTA_HH_ENABLE_EVT == event) {
    param_len = sizeof(tBTA_HH_STATUS);
  } else if (BTA_HH_OPEN_EVT == event) {
    param_len = sizeof(tBTA_HH_CONN);
  } else if (BTA_HH_DISABLE_EVT == event) {
    param_len = sizeof(tBTA_HH_STATUS);
  } else if (BTA_HH_CLOSE_EVT == event) {
    param_len = sizeof(tBTA_HH_CBDATA);
  } else if (BTA_HH_GET_DSCP_EVT == event) {
    param_len = sizeof(tBTA_HH_DEV_DSCP_INFO);
  } else if ((BTA_HH_GET_PROTO_EVT == event) || (BTA_HH_GET_IDLE_EVT == event)) {
    param_len = sizeof(tBTA_HH_HSDATA);
  } else if (BTA_HH_GET_RPT_EVT == event) {
    BT_HDR* hdr = p_data->hs_data.rsp_data.p_rpt_data;
    param_len = sizeof(tBTA_HH_HSDATA);

    if (hdr != NULL) {
      p_copy_cback = btif_hh_hsdata_rpt_copy_cb;
      param_len += BT_HDR_SIZE + hdr->offset + hdr->len;
    }
  } else if ((BTA_HH_SET_PROTO_EVT == event) || (BTA_HH_SET_RPT_EVT == event) ||
             (BTA_HH_VC_UNPLUG_EVT == event) || (BTA_HH_SET_IDLE_EVT == event)) {
    param_len = sizeof(tBTA_HH_CBDATA);
  } else if ((BTA_HH_ADD_DEV_EVT == event) || (BTA_HH_RMV_DEV_EVT == event)) {
    param_len = sizeof(tBTA_HH_DEV_INFO);
  } else if (BTA_HH_API_ERR_EVT == event) {
    param_len = 0;
  }
  /* switch context to btif task context (copy full union size for convenience)
   */
  status = btif_transfer_context(btif_hh_upstreams_evt, (uint16_t)event, (char*)p_data, param_len,
                                 p_copy_cback);

  /* catch any failed context transfers */
  ASSERTC(status == BT_STATUS_SUCCESS, "context transfer failed", status);
}

/*******************************************************************************
 *
 * Function         btif_hh_handle_evt
 *
 * Description      Switches context for immediate callback
 *
 * Returns          void
 *
 ******************************************************************************/

static void btif_hh_handle_evt(uint16_t event, char* p_param) {
  log::assert_that(p_param != nullptr, "assert failed: p_param != nullptr");
  tAclLinkSpec link_spec = *(tAclLinkSpec*)p_param;

  switch (event) {
    case BTIF_HH_CONNECT_REQ_EVT: {
      log::debug("BTIF_HH_CONNECT_REQ_EVT: link spec:{}", link_spec);
      if (btif_hh_connect(link_spec) == BT_STATUS_SUCCESS) {
        BTHH_STATE_UPDATE(link_spec, BTHH_CONN_STATE_CONNECTING);
      } else {
        BTHH_STATE_UPDATE(link_spec, BTHH_CONN_STATE_DISCONNECTED);
      }
    } break;

    case BTIF_HH_DISCONNECT_REQ_EVT: {
      log::debug("BTIF_HH_DISCONNECT_REQ_EVT: link spec:{}", link_spec);
      btif_hh_disconnect(link_spec);
      BTHH_STATE_UPDATE(link_spec, BTHH_CONN_STATE_DISCONNECTING);
    } break;

    case BTIF_HH_VUP_REQ_EVT: {
      log::debug("BTIF_HH_VUP_REQ_EVT: link spec:{}", link_spec);
      if (btif_hh_virtual_unplug(link_spec) != BT_STATUS_SUCCESS) {
        log::warn("Unable to virtual unplug device remote:{}", link_spec);
      }
    } break;

    default: {
      log::warn("Unknown event received:{} remote:{}", event, link_spec);
    } break;
  }
}

/*******************************************************************************
 *
 * Function      btif_hh_timer_timeout
 *
 * Description   Process timer timeout
 *
 * Returns      void
 ******************************************************************************/
static void btif_hh_timer_timeout(void* data) {
  btif_hh_device_t* p_dev = (btif_hh_device_t*)data;
  tBTA_HH_EVT event = BTA_HH_VC_UNPLUG_EVT;
  tBTA_HH p_data;
  int param_len = sizeof(tBTA_HH_CBDATA);

  log::verbose("");
  if (p_dev->dev_status != BTHH_CONN_STATE_CONNECTED) {
    return;
  }

  memset(&p_data, 0, sizeof(tBTA_HH));
  p_data.dev_status.status = BTA_HH_ERR;  // tBTA_HH_STATUS
  p_data.dev_status.handle = p_dev->dev_handle;

  /* switch context to btif task context */
  btif_transfer_context(btif_hh_upstreams_evt, (uint16_t)event, (char*)&p_data, param_len, NULL);
}

/*******************************************************************************
 *
 * Function         btif_hh_init
 *
 * Description     initializes the hh interface
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t init(bthh_callbacks_t* callbacks) {
  uint32_t i;
  log::verbose("");

  bt_hh_callbacks = callbacks;
  btif_hh_cb = {};

  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    btif_hh_cb.devices[i].dev_status = BTHH_CONN_STATE_UNKNOWN;
  }
  btif_hh_cb.incoming_connection_timer = alarm_new("btif_hh.incoming_connection_timer");

  /* Invoke the enable service API to the core to set the appropriate service_id
   */
  btif_enable_service(BTA_HID_SERVICE_ID);
  return BT_STATUS_SUCCESS;
}
/*******************************************************************************
 *
 * Function         btif_hh_transport_select
 *
 * Description      Select HID transport based on services available.
 *
 * Returns          void
 *
 ******************************************************************************/
static void btif_hh_transport_select(tAclLinkSpec& link_spec) {
  bool hid_available = false;
  bool hogp_available = false;
  bool headtracker_available = false;
  bool le_preferred = false;
  const RawAddress& bd_addr = link_spec.addrt.bda;

  // Find the device type
  tBT_DEVICE_TYPE dev_type;
  tBLE_ADDR_TYPE addr_type;
  get_btm_client_interface().peer.BTM_ReadDevInfo(bd_addr, &dev_type, &addr_type);

  // Find which transports are already connected
  bool bredr_acl =
          get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_BR_EDR);
  bool le_acl = get_btm_client_interface().peer.BTM_IsAclConnectionUp(bd_addr, BT_TRANSPORT_LE);

  // Find available services
  std::vector<bluetooth::Uuid> remote_uuids = btif_storage_get_services(bd_addr);
  for (const auto& uuid : remote_uuids) {
    if (uuid.Is16Bit()) {
      if (uuid.As16Bit() == UUID_SERVCLASS_HUMAN_INTERFACE) {
        hid_available = true;
      } else if (uuid.As16Bit() == UUID_SERVCLASS_LE_HID) {
        hogp_available = true;
      }
    } else if (uuid == ANDROID_HEADTRACKER_SERVICE_UUID) {
      headtracker_available = true;
    }

    if (hid_available && (hogp_available || headtracker_available)) {
      // HOGP and Android Headtracker Service are mutually exclusive
      break;
    }
  }

  /* Decide whether to connect HID or HOGP */
  if (bredr_acl && hid_available) {
    le_preferred = false;
  } else if (le_acl && (hogp_available || headtracker_available)) {
    le_preferred = true;
  } else if (hid_available) {
    le_preferred = false;
  } else if (hogp_available || headtracker_available) {
    le_preferred = true;
  } else if (bredr_acl) {
    le_preferred = false;
  } else if (le_acl || dev_type == BT_DEVICE_TYPE_BLE) {
    le_preferred = true;
  } else {
    le_preferred = false;
  }

  link_spec.transport = le_preferred ? BT_TRANSPORT_LE : BT_TRANSPORT_BR_EDR;
  log::info(
          "link_spec:{}, bredr_acl:{}, hid_available:{}, le_acl:{}, "
          "hogp_available:{}, headtracker_available:{}, "
          "dev_type:{}, le_preferred:{}",
          link_spec, bredr_acl, hid_available, le_acl, hogp_available, headtracker_available,
          dev_type, le_preferred);
}
/*******************************************************************************
 *
 * Function        connect
 *
 * Description     connect to hid device
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t connect(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport) {
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  if (!com::android::bluetooth::flags::initiate_multiple_hid_connections() &&
      !btif_hh_cb.new_connection_requests.empty()) {
    log::warn("HH status = {}", btif_hh_status_text(btif_hh_cb.status));
    return BT_STATUS_BUSY;
  } else if (btif_hh_cb.status == BTIF_HH_DISABLED || btif_hh_cb.status == BTIF_HH_DISABLING) {
    log::warn("HH status = {}", btif_hh_status_text(btif_hh_cb.status));
    return BT_STATUS_NOT_READY;
  }

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev != nullptr) {
    log::warn("device {} already connected", p_dev->link_spec);
    return BT_STATUS_DONE;
  }

  if (link_spec.transport == BT_TRANSPORT_AUTO) {
    btif_hh_transport_select(link_spec);
  }

  return btif_transfer_context(btif_hh_handle_evt, BTIF_HH_CONNECT_REQ_EVT, (char*)&link_spec,
                               sizeof(tAclLinkSpec), NULL);
}

/*******************************************************************************
 *
 * Function         disconnect
 *
 * Description      disconnect from hid device
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t disconnect(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                              tBT_TRANSPORT transport, bool reconnect_allowed) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  if (btif_hh_cb.status == BTIF_HH_DISABLED || btif_hh_cb.status == BTIF_HH_DISABLING) {
    log::error("HH status = {}", btif_hh_status_text(btif_hh_cb.status));
    return BT_STATUS_UNHANDLED;
  }

  if (!reconnect_allowed) {
    log::info("Incoming reconnections disabled for device {}", link_spec);
    btif_hh_added_device_t* added_dev = btif_hh_find_added_dev(link_spec);
    if (added_dev != nullptr) {
      added_dev->reconnect_allowed = reconnect_allowed;
      btif_storage_set_hid_connection_policy(added_dev->link_spec, reconnect_allowed);
    }
  }

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == nullptr) {
    // Conclude the request if the device is already disconnected
    p_dev = btif_hh_find_dev_by_link_spec(link_spec);
    if (p_dev != nullptr && (p_dev->dev_status == BTHH_CONN_STATE_ACCEPTING ||
                             p_dev->dev_status == BTHH_CONN_STATE_CONNECTING)) {
      log::warn("Device {} already not connected, state: {}", p_dev->link_spec,
                bthh_connection_state_text(p_dev->dev_status));
      p_dev->dev_status = BTHH_CONN_STATE_DISCONNECTED;

      if (com::android::bluetooth::flags::pending_hid_connection_cancellation()) {
        btif_hh_cb.new_connection_requests.remove(link_spec);
      }
      return BT_STATUS_DONE;
    } else if (com::android::bluetooth::flags::initiate_multiple_hid_connections() &&
               std::find(btif_hh_cb.new_connection_requests.begin(),
                         btif_hh_cb.new_connection_requests.end(),
                         link_spec) != btif_hh_cb.new_connection_requests.end()) {
      btif_hh_cb.new_connection_requests.remove(link_spec);
      log::info("Pending connection cancelled {}", link_spec);
      return BT_STATUS_SUCCESS;
    }

    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_UNHANDLED;
  }

  return btif_transfer_context(btif_hh_handle_evt, BTIF_HH_DISCONNECT_REQ_EVT,
                               (char*)&p_dev->link_spec, sizeof(tAclLinkSpec), NULL);
}

/*******************************************************************************
 *
 * Function         virtual_unplug
 *
 * Description      Virtual UnPlug (VUP) the specified HID device.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t virtual_unplug(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                  tBT_TRANSPORT transport) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_dev_by_link_spec(link_spec);
  bool pending_connection = false;
  for (auto ls : btif_hh_cb.new_connection_requests) {
    if (ls.addrt.bda == link_spec.addrt.bda) {
      pending_connection = true;
      break;
    }
  }

  if (p_dev == nullptr && btif_hh_find_added_dev(link_spec) && !pending_connection) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }

  btif_transfer_context(btif_hh_handle_evt, BTIF_HH_VUP_REQ_EVT, (char*)&link_spec,
                        sizeof(tAclLinkSpec), NULL);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
**
** Function         get_idle_time
**
** Description      Get the HID idle time
**
** Returns         bt_status_t
**
*******************************************************************************/
static bt_status_t get_idle_time(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                 tBT_TRANSPORT transport) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }

  BTA_HhGetIdle(p_dev->dev_handle);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
**
** Function         set_idle_time
**
** Description      Set the HID idle time
**
** Returns         bt_status_t
**
*******************************************************************************/
static bt_status_t set_idle_time(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                 tBT_TRANSPORT transport, uint8_t idle_time) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);
  log::verbose("idle time: {}", idle_time);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }

  BTA_HhSetIdle(p_dev->dev_handle, idle_time);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         set_info
 *
 * Description      Set the HID device descriptor for the specified HID device.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t set_info(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                            bthh_hid_info_t hid_info) {
  CHECK_BTHH_INIT();
  tBTA_HH_DEV_DSCP_INFO dscp_info = {};
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);
  log::verbose(
          "sub_class = 0x{:02x}, app_id = {}, vendor_id = 0x{:04x}, "
          "product_id = 0x{:04x}, version= 0x{:04x}",
          hid_info.sub_class, hid_info.app_id, hid_info.vendor_id, hid_info.product_id,
          hid_info.version);

  BTHH_CHECK_NOT_DISABLED();

  dscp_info.vendor_id = hid_info.vendor_id;
  dscp_info.product_id = hid_info.product_id;
  dscp_info.version = hid_info.version;
  dscp_info.ctry_code = hid_info.ctry_code;

  dscp_info.descriptor.dl_len = hid_info.dl_len;
  dscp_info.descriptor.dsc_list = (uint8_t*)osi_malloc(dscp_info.descriptor.dl_len);
  memcpy(dscp_info.descriptor.dsc_list, &(hid_info.dsc_list), hid_info.dl_len);

  if (transport == BT_TRANSPORT_AUTO) {
    btif_hh_transport_select(link_spec);
  }

  if (hh_add_device(link_spec, hid_info.attr_mask, true)) {
    BTA_HhAddDev(link_spec, hid_info.attr_mask, hid_info.sub_class, hid_info.app_id, dscp_info);
  }

  osi_free_and_reset((void**)&dscp_info.descriptor.dsc_list);

  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         get_protocol
 *
 * Description      Get the HID proto mode.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t get_protocol(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                tBT_TRANSPORT transport, bthh_protocol_mode_t /* protocolMode */) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (!p_dev) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }

  BTA_HhGetProtoMode(p_dev->dev_handle);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         set_protocol
 *
 * Description      Set the HID proto mode.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t set_protocol(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                tBT_TRANSPORT transport, bthh_protocol_mode_t protocolMode) {
  CHECK_BTHH_INIT();
  btif_hh_device_t* p_dev;
  uint8_t proto_mode = protocolMode;
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);
  log::verbose("mode: {}", protocolMode);

  BTHH_CHECK_NOT_DISABLED();

  p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  } else if (protocolMode != BTA_HH_PROTO_RPT_MODE && protocolMode != BTA_HH_PROTO_BOOT_MODE) {
    log::warn("device proto_mode = {}", proto_mode);
    return BT_STATUS_PARM_INVALID;
  } else {
    BTA_HhSetProtoMode(p_dev->dev_handle, protocolMode);
  }

  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         get_report
 *
 * Description      Send a GET_REPORT to HID device.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t get_report(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                              tBT_TRANSPORT transport, bthh_report_type_t reportType,
                              uint8_t reportId, int bufferSize) {
  CHECK_BTHH_INIT();
  btif_hh_device_t* p_dev;
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);
  log::verbose("r_type: {}; rpt_id: {}; buf_size: {}", reportType, reportId, bufferSize);

  BTHH_CHECK_NOT_DISABLED();

  p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  } else if (((int)reportType) <= BTA_HH_RPTT_RESRV || ((int)reportType) > BTA_HH_RPTT_FEATURE) {
    log::error("report type={} not supported", reportType);
    log_counter_metrics_btif(
            android::bluetooth::CodePathCounterKeyEnum::HIDH_COUNT_WRONG_REPORT_TYPE, 1);
    return BT_STATUS_UNSUPPORTED;
  } else {
    BTA_HhGetReport(p_dev->dev_handle, reportType, reportId, bufferSize);
  }

  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         get_report_reply
 *
 * Description      Send a REPORT_REPLY/FEATURE_ANSWER to HID driver.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t get_report_reply(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                                    tBT_TRANSPORT transport, bthh_status_t status, char* report,
                                    uint16_t size) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }

  bta_hh_co_get_rpt_rsp(p_dev->dev_handle, (tBTA_HH_STATUS)status, (uint8_t*)report, size);
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         set_report
 *
 * Description      Send a SET_REPORT to HID device.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t set_report(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type,
                              tBT_TRANSPORT transport, bthh_report_type_t reportType,
                              char* report) {
  CHECK_BTHH_INIT();
  btif_hh_device_t* p_dev;
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);
  log::verbose("reportType: {}", reportType);

  BTHH_CHECK_NOT_DISABLED();

  p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  } else if (((int)reportType) <= BTA_HH_RPTT_RESRV || ((int)reportType) > BTA_HH_RPTT_FEATURE) {
    log::error("report type={} not supported", reportType);
    log_counter_metrics_btif(
            android::bluetooth::CodePathCounterKeyEnum::HIDH_COUNT_WRONG_REPORT_TYPE, 1);
    return BT_STATUS_UNSUPPORTED;
  } else {
    int hex_bytes_filled;
    size_t len = (strlen(report) + 1) / 2;
    uint8_t* hexbuf = (uint8_t*)osi_calloc(len);

    /* Build a SetReport data buffer */
    // TODO
    hex_bytes_filled = ascii_2_hex(report, len, hexbuf);
    log::info("Hex bytes filled, hex value: {}", hex_bytes_filled);
    if (hex_bytes_filled) {
      BT_HDR* p_buf = create_pbuf(hex_bytes_filled, hexbuf);
      if (p_buf == NULL) {
        log::error("failed to allocate RPT buffer, len = {}", hex_bytes_filled);
        osi_free(hexbuf);
        return BT_STATUS_NOMEM;
      }
      BTA_HhSetReport(p_dev->dev_handle, reportType, p_buf);
      osi_free(hexbuf);
      return BT_STATUS_SUCCESS;
    }
    osi_free(hexbuf);
    return BT_STATUS_FAIL;
  }
}

/*******************************************************************************
 *
 * Function         send_data
 *
 * Description      Send a SEND_DATA to HID device.
 *
 * Returns         bt_status_t
 *
 ******************************************************************************/
static bt_status_t send_data(RawAddress* bd_addr, tBLE_ADDR_TYPE addr_type, tBT_TRANSPORT transport,
                             char* data) {
  CHECK_BTHH_INIT();
  tAclLinkSpec link_spec = {};
  link_spec.addrt.bda = *bd_addr;
  link_spec.addrt.type = addr_type;
  link_spec.transport = transport;

  BTHH_LOG_LINK(link_spec);

  BTHH_CHECK_NOT_DISABLED();

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_link_spec(link_spec);
  if (p_dev == NULL) {
    BTHH_LOG_UNKNOWN_LINK(link_spec);
    return BT_STATUS_DEVICE_NOT_FOUND;
  } else {
    int hex_bytes_filled;
    size_t len = (strlen(data) + 1) / 2;
    uint8_t* hexbuf = (uint8_t*)osi_calloc(len);

    /* Build a SendData data buffer */
    hex_bytes_filled = ascii_2_hex(data, len, hexbuf);
    log::info("Hex bytes filled, hex value: {}, {}", hex_bytes_filled, len);

    if (hex_bytes_filled) {
      BT_HDR* p_buf = create_pbuf(hex_bytes_filled, hexbuf);
      if (p_buf == NULL) {
        log::error("failed to allocate RPT buffer, len = {}", hex_bytes_filled);
        osi_free(hexbuf);
        return BT_STATUS_NOMEM;
      }
      p_buf->layer_specific = BTA_HH_RPTT_OUTPUT;
      BTA_HhSendData(p_dev->dev_handle, link_spec, p_buf);
      osi_free(hexbuf);
      return BT_STATUS_SUCCESS;
    }
    osi_free(hexbuf);
    return BT_STATUS_FAIL;
  }
}

/*******************************************************************************
 *
 * Function         cleanup
 *
 * Description      Closes the HH interface
 *
 * Returns          bt_status_t
 *
 ******************************************************************************/
static void cleanup(void) {
  log::verbose("");
  btif_hh_device_t* p_dev;
  int i;
  if (btif_hh_cb.status == BTIF_HH_DISABLED || btif_hh_cb.status == BTIF_HH_DISABLING) {
    log::warn("HH disabling or disabled already, status = {}",
              btif_hh_status_text(btif_hh_cb.status));
    return;
  }
  if (bt_hh_callbacks) {
    btif_hh_cb.status = BTIF_HH_DISABLING;
    /* update flag, not to enable hid device service now as BT is switching off
     */
    btif_hh_cb.service_dereg_active = FALSE;
    btif_disable_service(BTA_HID_SERVICE_ID);
  }
  alarm_free(btif_hh_cb.incoming_connection_timer);
  btif_hh_cb.incoming_connection_timer = nullptr;
  btif_hh_cb.pending_incoming_connection = {};
  btif_hh_cb.new_connection_requests.clear();
  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    p_dev = &btif_hh_cb.devices[i];
    int fd = (com::android::bluetooth::flags::hid_report_queuing() ? p_dev->internal_send_fd
                                                                   : p_dev->uhid.fd);
    if (p_dev->dev_status != BTHH_CONN_STATE_UNKNOWN && fd >= 0) {
      log::verbose("Closing uhid fd = {}", fd);
      bta_hh_co_close(p_dev);
    }
  }
}

/*******************************************************************************
 *
 * Function         configure_enabled_profiles
 *
 * Description      Configure HIDP or HOGP enablement. Require to cleanup and
 *re-init to take effect.
 *
 * Returns          void
 *
 ******************************************************************************/
static void configure_enabled_profiles(bool enable_hidp, bool enable_hogp) {
  bt_hh_enable_type.hidp_enabled = enable_hidp;
  bt_hh_enable_type.hogp_enabled = enable_hogp;
}

static const bthh_interface_t bthhInterface = {
        sizeof(bthhInterface),
        init,
        connect,
        disconnect,
        virtual_unplug,
        set_info,
        get_protocol,
        set_protocol,
        get_idle_time,
        set_idle_time,
        get_report,
        get_report_reply,
        set_report,
        send_data,
        cleanup,
        configure_enabled_profiles,
};

/*******************************************************************************
 *
 * Function         btif_hh_execute_service
 *
 * Description      Initializes/Shuts down the service
 *
 * Returns          BT_STATUS_SUCCESS on success, BT_STATUS_FAIL otherwise
 *
 ******************************************************************************/
bt_status_t btif_hh_execute_service(bool b_enable) {
  if (b_enable) {
    /* Enable and register with BTA-HH */
    BTA_HhEnable(bte_hh_evt, bt_hh_enable_type.hidp_enabled, bt_hh_enable_type.hogp_enabled);
  } else {
    /* Disable HH */
    BTA_HhDisable();
  }
  return BT_STATUS_SUCCESS;
}

/*******************************************************************************
 *
 * Function         btif_hh_get_interface
 *
 * Description      Get the hh callback interface
 *
 * Returns          bthh_interface_t
 *
 ******************************************************************************/
const bthh_interface_t* btif_hh_get_interface() {
  log::verbose("");
  return &bthhInterface;
}

#define DUMPSYS_TAG "shim::legacy::hid"
void DumpsysHid(int fd) {
  LOG_DUMPSYS_TITLE(fd, DUMPSYS_TAG);
  LOG_DUMPSYS(fd, "status:%s num_devices:%u", btif_hh_status_text(btif_hh_cb.status).c_str(),
              btif_hh_cb.device_num);
  LOG_DUMPSYS(fd, "status:%s", btif_hh_status_text(btif_hh_cb.status).c_str());
  for (auto link_spec : btif_hh_cb.new_connection_requests) {
    LOG_DUMPSYS(fd, "Pending connection: %s", link_spec.ToRedactedStringForLogging().c_str());
  }
  for (unsigned i = 0; i < BTIF_HH_MAX_HID; i++) {
    const btif_hh_device_t* p_dev = &btif_hh_cb.devices[i];
    if (p_dev->link_spec.addrt.bda != RawAddress::kEmpty) {
      int fd = (com::android::bluetooth::flags::hid_report_queuing() ? p_dev->internal_send_fd
                                                                     : p_dev->uhid.fd);
      LOG_DUMPSYS(fd, "  %u: addr:%s fd:%d state:%s thread_id:%d handle:%d", i,
                  p_dev->link_spec.ToRedactedStringForLogging().c_str(), fd,
                  bthh_connection_state_text(p_dev->dev_status).c_str(),
                  static_cast<int>(p_dev->hh_poll_thread_id), p_dev->dev_handle);
    }
  }
  for (unsigned i = 0; i < BTIF_HH_MAX_ADDED_DEV; i++) {
    const btif_hh_added_device_t* p_dev = &btif_hh_cb.added_devices[i];
    if (p_dev->link_spec.addrt.bda != RawAddress::kEmpty) {
      LOG_DUMPSYS(fd, "  %u: addr:%s reconnect:%s", i,
                  p_dev->link_spec.ToRedactedStringForLogging().c_str(),
                  p_dev->reconnect_allowed ? "T" : "F");
    }
  }

  if (com::android::bluetooth::flags::hid_report_queuing() &&
      !btif_hh_cb.pending_incoming_connection.link_spec.addrt.bda.IsEmpty()) {
    LOG_DUMPSYS(
            fd, "  Pending incoming connection: %s",
            btif_hh_cb.pending_incoming_connection.link_spec.ToRedactedStringForLogging().c_str());
  }
  BTA_HhDump(fd);
}

namespace bluetooth {
namespace legacy {
namespace testing {

void bte_hh_evt(tBTA_HH_EVT event, tBTA_HH* p_data) { ::bte_hh_evt(event, p_data); }

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

#undef DUMPSYS_TAG
