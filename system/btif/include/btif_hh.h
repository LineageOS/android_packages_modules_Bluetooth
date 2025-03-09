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

#ifndef BTIF_HH_H
#define BTIF_HH_H

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_hh.h>
#include <pthread.h>
#include <stdint.h>

#include <list>

#include "bta/include/bta_hh_api.h"
#include "macros.h"
#include "osi/include/alarm.h"
#include "osi/include/fixed_queue.h"
#include "types/ble_address_with_type.h"
#include "types/raw_address.h"

/*******************************************************************************
 *  Constants & Macros
 ******************************************************************************/

#define BTIF_HH_MAX_HID 8
#define BTIF_HH_MAX_ADDED_DEV 32

#define BTIF_HH_MAX_KEYSTATES 3
#define BTIF_HH_KEYSTATE_MASK_NUMLOCK 0x01
#define BTIF_HH_KEYSTATE_MASK_CAPSLOCK 0x02
#define BTIF_HH_KEYSTATE_MASK_SCROLLLOCK 0x04

#define BTIF_HH_MAX_POLLING_ATTEMPTS 10
#define BTIF_HH_POLLING_SLEEP_DURATION_US 5000

#ifndef ENABLE_UHID_SET_REPORT
#if defined(__ANDROID__) || defined(TARGET_FLOSS)
#define ENABLE_UHID_SET_REPORT 1
#else
#define ENABLE_UHID_SET_REPORT 0
#endif
#endif

/*******************************************************************************
 *  Type definitions and return values
 ******************************************************************************/

typedef enum : unsigned {
  BTIF_HH_DISABLED = 0,
  BTIF_HH_ENABLED,
  BTIF_HH_DISABLING,
} BTIF_HH_STATUS;

inline std::string btif_hh_status_text(const BTIF_HH_STATUS& status) {
  switch (status) {
    CASE_RETURN_TEXT(BTIF_HH_DISABLED);
    CASE_RETURN_TEXT(BTIF_HH_ENABLED);
    CASE_RETURN_TEXT(BTIF_HH_DISABLING);
    default:
      return std::format("UNKNOWN[{}]", static_cast<unsigned>(status));
  }
}

/* Uhid thread has exclusive access to this block. */
typedef struct {
  int fd;                // for interfacing with uhid
  int internal_recv_fd;  // for receiving internal events in uhid thread
  int internal_send_fd;  // for passing to other threads so they can send
                         // internal events
  uint8_t dev_handle;
  tAclLinkSpec link_spec;
  bool ready_for_data;
  fixed_queue_t* get_rpt_id_queue;
#if ENABLE_UHID_SET_REPORT
  fixed_queue_t* set_rpt_id_queue;
#endif  // ENABLE_UHID_SET_REPORT
  fixed_queue_t* input_queue;  // to store the inputs before uhid is ready.
  alarm_t* delayed_ready_timer;  // to delay marking a device as ready, give input chance to listen.
  alarm_t* ready_disconn_timer;  // to disconnect device if still not ready after some time.
} btif_hh_uhid_t;

/* Control block to maintain properties of devices */
typedef struct {
  bthh_connection_state_t dev_status;
  uint8_t dev_handle;
  tAclLinkSpec link_spec;
  tBTA_HH_ATTR_MASK attr_mask;
  uint8_t sub_class;
  uint8_t app_id;
  int internal_send_fd;  // for sending internal events from btif
  pthread_t hh_poll_thread_id;
  alarm_t* vup_timer;
  bool local_vup;  // Indicated locally initiated VUP
} btif_hh_device_t;

/* Control block to maintain properties of devices */
typedef struct {
  uint8_t dev_handle;
  tAclLinkSpec link_spec;
  tBTA_HH_ATTR_MASK attr_mask;
  bool reconnect_allowed;  // Connection policy
} btif_hh_added_device_t;

/**
 * BTIF-HH control block to maintain added devices and currently
 * connected hid devices
 */
typedef struct {
  BTIF_HH_STATUS status;
  btif_hh_device_t devices[BTIF_HH_MAX_HID];
  uint32_t device_num;
  btif_hh_added_device_t added_devices[BTIF_HH_MAX_ADDED_DEV];
  bool service_dereg_active;

  std::list<tAclLinkSpec> new_connection_requests;

  tBTA_HH_CONN pending_incoming_connection;  // Unexpected incoming connection request
  alarm_t* incoming_connection_timer;        // Timer to handle unexpected incoming connection
} btif_hh_cb_t;

/*******************************************************************************
 *  Functions
 ******************************************************************************/

extern btif_hh_cb_t btif_hh_cb;

const bthh_interface_t* btif_hh_get_interface();
bt_status_t btif_hh_execute_service(bool b_enable);
btif_hh_device_t* btif_hh_find_connected_dev_by_handle(uint8_t handle);
btif_hh_device_t* btif_hh_find_dev_by_handle(uint8_t handle);
btif_hh_device_t* btif_hh_find_empty_dev(void);
bt_status_t btif_hh_virtual_unplug(const tAclLinkSpec& link_spec);
bt_status_t btif_hh_connect(const tAclLinkSpec& link_spec);
void btif_hh_remove_device(const tAclLinkSpec& link_spec);
void btif_hh_setreport(btif_hh_uhid_t* p_uhid, bthh_report_type_t r_type, uint16_t size,
                       uint8_t* report);
void btif_hh_senddata(btif_hh_uhid_t* p_uhid, uint16_t size, uint8_t* report);
void btif_hh_getreport(btif_hh_uhid_t* p_uhid, bthh_report_type_t r_type, uint8_t reportId,
                       uint16_t bufferSize);
void btif_hh_service_registration(bool enable);

void btif_hh_load_bonded_dev(const tAclLinkSpec& link_spec, tBTA_HH_ATTR_MASK attr_mask,
                             uint8_t sub_class, uint8_t app_id, tBTA_HH_DEV_DSCP_INFO dscp_info,
                             bool reconnect_allowed);
void btif_hh_disconnected(const RawAddress& addr, tBT_TRANSPORT transport);

int bta_hh_co_write(int fd, uint8_t* rpt, uint16_t len);
void bta_hh_co_close(btif_hh_device_t* p_dev);
void bta_hh_co_send_hid_info(btif_hh_device_t* p_dev, const char* dev_name, uint16_t vendor_id,
                             uint16_t product_id, uint16_t version, uint8_t ctry_code,
                             uint16_t dscp_len, uint8_t* p_dscp);

void DumpsysHid(int fd);

namespace bluetooth::legacy::testing {
void bte_hh_evt(tBTA_HH_EVT event, tBTA_HH* p_data);
}  // namespace bluetooth::legacy::testing

namespace std {
template <>
struct formatter<BTIF_HH_STATUS> : enum_formatter<BTIF_HH_STATUS> {};
}  // namespace std

#endif
