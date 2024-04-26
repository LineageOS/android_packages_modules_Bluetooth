/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "bta_hh_headtracker"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "bta/hh/bta_hh_int.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "types/bluetooth/uuid.h"

using bluetooth::Uuid;
using namespace bluetooth;

static bool bta_hh_headtracker_parse_version_charac(
    tBTA_HH_DEV_CB* p_dev_cb, const gatt::Characteristic& charac) {
  tBTA_HH_LE_RPT* p_rpt = bta_hh_le_find_alloc_report_entry(
      p_dev_cb, p_dev_cb->hid_srvc.srvc_inst_id, GATT_UUID_HID_REPORT,
      charac.value_handle);
  if (p_rpt == nullptr) {
    log::error("Add report entry failed !!!");
    return false;
  }

  bta_hh_le_save_report_ref(p_dev_cb, p_rpt, BTA_HH_RPTT_FEATURE, 2);
  return true;
}

static bool bta_hh_headtracker_prase_control_charac(
    tBTA_HH_DEV_CB* p_dev_cb, const gatt::Characteristic& charac) {
  tBTA_HH_LE_RPT* p_rpt = bta_hh_le_find_alloc_report_entry(
      p_dev_cb, p_dev_cb->hid_srvc.srvc_inst_id, GATT_UUID_HID_REPORT,
      charac.value_handle);
  if (p_rpt == nullptr) {
    log::error("Add report entry failed !!!");
    return false;
  }

  bta_hh_le_save_report_ref(p_dev_cb, p_rpt, BTA_HH_RPTT_FEATURE, 1);
  return true;
}

static bool bta_hh_headtracker_parse_report_charac(
    tBTA_HH_DEV_CB* p_dev_cb, const gatt::Characteristic& charac) {
  tBTA_HH_LE_RPT* p_rpt = bta_hh_le_find_alloc_report_entry(
      p_dev_cb, p_dev_cb->hid_srvc.srvc_inst_id, GATT_UUID_HID_REPORT,
      charac.value_handle);
  if (p_rpt == nullptr) {
    log::error("Add report entry failed !!!");
    return false;
  }

  bta_hh_le_save_report_ref(p_dev_cb, p_rpt, BTA_HH_RPTT_INPUT, 1);
  return true;
}

/* Hardcoded Android Headtracker HID descriptor */
static const uint8_t ANDROID_HEADTRACKER_DESCRIPTOR[] = {
    0x05, 0x20, 0x09, 0xe1, 0xa1, 0x01, 0x85, 0x02, 0x0a, 0x08, 0x03, 0x15,
    0x00, 0x25, 0xff, 0x75, 0x08, 0x95, 0x19, 0xb1, 0x03, 0x0a, 0x02, 0x03,
    0x15, 0x00, 0x25, 0xff, 0x75, 0x08, 0x95, 0x10, 0xb1, 0x03, 0x85, 0x01,
    0x0a, 0x16, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0xa1,
    0x02, 0x0a, 0x40, 0x08, 0x0a, 0x41, 0x08, 0xb1, 0x00, 0xc0, 0x0a, 0x19,
    0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0xa1, 0x02, 0x0a,
    0x55, 0x08, 0x0a, 0x51, 0x08, 0xb1, 0x00, 0xc0, 0x0a, 0x0e, 0x03, 0x15,
    0x00, 0x25, 0x3f, 0x35, 0x0a, 0x45, 0x64, 0x75, 0x06, 0x95, 0x01, 0x66,
    0x01, 0x10, 0x55, 0x0d, 0xb1, 0x02, 0x0a, 0x10, 0xf4, 0x15, 0x00, 0x25,
    0x01, 0x75, 0x01, 0x95, 0x01, 0xa1, 0x02, 0x0a, 0x00, 0xf8, 0x0a, 0x01,
    0xf8, 0xb1, 0x00, 0xc0, 0xb1, 0x02, 0x0a, 0x44, 0x5,  0x16, 0x01, 0x80,
    0x26, 0xff, 0x7f, 0x37, 0x60, 0x4f, 0x46, 0xed, 0x47, 0xa1, 0xb0, 0xb9,
    0x12, 0x55, 0x08, 0x75, 0x10, 0x95, 0x03, 0x81, 0x02, 0x0a, 0x45, 0x05,
    0x16, 0x01, 0x80, 0x26, 0xff, 0x7f, 0x35, 0xe0, 0x45, 0x20, 0x55, 0x00,
    0x75, 0x10, 0x95, 0x03, 0x81, 0x02, 0x0a, 0x46, 0x05, 0x15, 0x00, 0x25,
    0xff, 0x35, 0x00, 0x45, 0xff, 0x55, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81,
    0x02, 0xc0};

/*******************************************************************************
 *
 * Function         bta_hh_headtracker_parse_service
 *
 * Description      This function discover all characteristics of the
 *                  headtracker service
 *
 * Parameters:
 *
 ******************************************************************************/
void bta_hh_headtracker_parse_service(tBTA_HH_DEV_CB* p_dev_cb,
                                      const gatt::Service* service) {
  log::info("");
  bta_hh_le_srvc_init(p_dev_cb, service->handle);
  p_dev_cb->mode = BTA_HH_PROTO_RPT_MODE;
  p_dev_cb->hid_srvc.is_headtracker = true;

  bta_hh_le_save_report_map(p_dev_cb,
                            (uint16_t)sizeof(ANDROID_HEADTRACKER_DESCRIPTOR),
                            (uint8_t*)&ANDROID_HEADTRACKER_DESCRIPTOR);

  bool version_found = false;
  bool control_found = false;
  bool data_found = false;

  for (const gatt::Characteristic& charac : service->characteristics) {
    if (charac.uuid == ANDROID_HEADTRACKER_VERSION_CHARAC_UUID) {
      version_found = bta_hh_headtracker_parse_version_charac(p_dev_cb, charac);
    } else if (charac.uuid == ANDROID_HEADTRACKER_CONTROL_CHARAC_UUID) {
      control_found = bta_hh_headtracker_prase_control_charac(p_dev_cb, charac);
    } else if (charac.uuid == ANDROID_HEADTRACKER_REPORT_CHARAC_UUID) {
      data_found = bta_hh_headtracker_parse_report_charac(p_dev_cb, charac);
    } else {
      log::warn("Unexpected characteristic {}", charac.uuid.ToString());
    }
  }

  tGATT_STATUS status = (version_found && control_found && data_found)
                            ? GATT_SUCCESS
                            : GATT_ERROR;
  bta_hh_le_service_parsed(p_dev_cb, status);
}

/*******************************************************************************
 *
 * Function         bta_hh_headtracker_supported
 *
 * Description      Checks if the connection instance is for headtracker
 *
 * Parameters:
 *
 ******************************************************************************/
bool bta_hh_headtracker_supported(tBTA_HH_DEV_CB* p_dev_cb) {
  return com::android::bluetooth::flags::android_headtracker_service() &&
         p_dev_cb->hid_srvc.is_headtracker;
}

/*******************************************************************************
 *
 * Function         bta_hh_get_uuid16
 *
 * Description      Maps Headtracker characteristic UUIDs to HOGP Report UUID
 *
 * Parameters:
 *
 ******************************************************************************/
uint16_t bta_hh_get_uuid16(tBTA_HH_DEV_CB* p_dev_cb, Uuid uuid) {
  if (bta_hh_headtracker_supported(p_dev_cb) &&
      (uuid == ANDROID_HEADTRACKER_VERSION_CHARAC_UUID ||
       uuid == ANDROID_HEADTRACKER_CONTROL_CHARAC_UUID ||
       uuid == ANDROID_HEADTRACKER_REPORT_CHARAC_UUID)) {
    return GATT_UUID_HID_REPORT;
  } else {
    return uuid.As16Bit();
  }
}
