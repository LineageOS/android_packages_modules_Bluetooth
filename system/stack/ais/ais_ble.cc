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

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <string.h>

#include <array>

#include "os/system_properties.h"
#include "stack/include/ais_api.h"
#include "stack/include/bt_types.h"
#include "stack/include/gatt_api.h"
#include "types/bluetooth/uuid.h"

using bluetooth::Uuid;
using bluetooth::log::error;
using bluetooth::log::warn;

static const char kPropertyAndroidAPILevel[] = "ro.build.version.sdk";
static const uint32_t kPropertyAndroidAPILevelDefault = 0;

const Uuid ANDROID_INFORMATION_SERVICE_UUID =
        Uuid::FromString(ANDROID_INFORMATION_SERVICE_UUID_STRING);
const Uuid GATT_UUID_AIS_API_LEVEL = Uuid::FromString(GATT_UUID_AIS_API_LEVEL_STRING);

/* LE AIS attribute handle */
static uint16_t attr_api_level_handle;

static uint32_t api_level;

static void ais_request_cback(tCONN_ID, uint32_t, tGATTS_REQ_TYPE, tGATTS_DATA*);

static tGATT_CBACK ais_cback = {
        .p_conn_cb = nullptr,
        .p_cmpl_cb = nullptr,
        .p_disc_res_cb = nullptr,
        .p_disc_cmpl_cb = nullptr,
        .p_req_cb = ais_request_cback,
        .p_enc_cmpl_cb = nullptr,
        .p_congestion_cb = nullptr,
        .p_phy_update_cb = nullptr,
        .p_conn_update_cb = nullptr,
        .p_subrate_chg_cb = nullptr,
};

/** AIS ATT server attribute access request callback */
static void ais_request_cback(tCONN_ID conn_id, uint32_t trans_id, tGATTS_REQ_TYPE type,
                       tGATTS_DATA* p_data) {
  tGATT_STATUS status = GATT_INVALID_PDU;
  tGATTS_RSP rsp_msg = {};
  uint16_t handle = p_data->read_req.handle;
  tGATT_VALUE* p_value = &rsp_msg.attr_value;
  uint8_t* p = p_value->value;

  if (type == GATTS_REQ_TYPE_READ_CHARACTERISTIC) {
    p_value->handle = handle;

    if (handle == attr_api_level_handle) {
      if (p_data->read_req.is_long) {
        p_value->offset = p_data->read_req.offset;
        status = GATT_NOT_LONG;
      } else {
        UINT32_TO_STREAM(p, api_level);
        p_value->len = 4;
        status = GATT_SUCCESS;
      }
    } else {
      status = GATT_NOT_FOUND;
    }
  } else {
    warn("Unknown/unexpected LE AIS ATT request: 0x{:02x}", type);
  }

  if (GATTS_SendRsp(conn_id, trans_id, status, &rsp_msg) != GATT_SUCCESS) {
    warn("Unable to send GATT server response conn_id:{}", conn_id);
  }
}

/*******************************************************************************
 *
 * Function         ais_attr_db_init
 *
 * Description      AIS ATT database initialization.
 *
 * Returns          void.
 *
 ******************************************************************************/
static void ais_attr_db_init(void) {
  api_level = bluetooth::os::GetSystemPropertyUint32(kPropertyAndroidAPILevel,
                                                     kPropertyAndroidAPILevelDefault);
  // Add Android OS identifier if API level is defined.
  if (api_level != kPropertyAndroidAPILevelDefault) {
    std::array<uint8_t, Uuid::kNumBytes128> tmp;
    tmp.fill(0xc5);  // any number is fine here
    Uuid app_uuid = Uuid::From128BitBE(tmp);

    tGATT_IF gatt_if = GATT_Register(app_uuid, "Ais", &ais_cback, false);

    GATT_StartIf(gatt_if);

    btgatt_db_element_t android_information_service[] = {
            {
                    .uuid = ANDROID_INFORMATION_SERVICE_UUID,
                    .type = BTGATT_DB_PRIMARY_SERVICE,
            },
            {
                    .uuid = GATT_UUID_AIS_API_LEVEL,
                    .type = BTGATT_DB_CHARACTERISTIC,
                    .properties = GATT_CHAR_PROP_BIT_READ,
                    .permissions = GATT_PERM_READ_IF_ENCRYPTED_OR_DISCOVERABLE,
            }};
    if (GATTS_AddService(gatt_if, android_information_service,
                         sizeof(android_information_service) / sizeof(btgatt_db_element_t)) !=
        GATT_SERVICE_STARTED) {
      error("Unable to add Android Information Server gatt_if:{}", gatt_if);
    }

    attr_api_level_handle = android_information_service[1].attribute_handle;
  }
}

/*
 * This routine should not be called except once per stack invocation.
 */
void AIS_Init(void) { ais_attr_db_init(); }
