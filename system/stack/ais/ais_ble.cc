/******************************************************************************
 *
 *  Copyright (C) 2024 The Android Open Source Project
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
#include <bluetooth/types/uuid.h>
#include <com_android_bluetooth_flags.h>

#include <array>

#include "os/system_properties.h"
#include "stack/include/ais_api.h"
#include "stack/include/bt_types.h"
#include "stack/include/gatt_api.h"
#include "stack/include/stack_app.h"

using namespace bluetooth;

using bluetooth::log::error;
using bluetooth::log::warn;
using stack::tGATT_REQ_CBACK;

static const char kPropertyAndroidAPILevel[] = "ro.build.version.sdk";
static const uint32_t kPropertyAndroidAPILevelDefault = 0;

constinit Uuid ANDROID_INFORMATION_SERVICE_UUID(ANDROID_INFORMATION_SERVICE_UUID_STRING);
constinit Uuid GATT_UUID_AIS_API_LEVEL(GATT_UUID_AIS_API_LEVEL_STRING);

/* LE AIS attribute handle */
static uint16_t attr_api_level_handle;

static uint32_t api_level;

static void ais_read_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                          const RawAddress& remote_bda, uint16_t handle,
                                          uint16_t offset, bool is_long);
static void ais_read_descriptor_cback(tCONN_ID /*conn_id*/, uint32_t /*trans_id*/,
                                      const RawAddress& /*remote_bda*/, uint16_t /*handle*/,
                                      uint16_t /*offset*/, bool /* is_long */) {
  warn("Unknown/unexpected LE AIS ATT request: READ_DESCRIPTOR");
}
static void ais_write_characteristic_or_descriptor_cback(tCONN_ID /*conn_id*/,
                                                         uint32_t /*trans_id*/,
                                                         const RawAddress& /*remote_bda*/,
                                                         uint16_t /*handle*/, uint16_t /*offset*/,
                                                         bool /*need_rsp*/, bool /*is_prep*/,
                                                         uint8_t* /*value*/, uint16_t /*len*/) {
  warn("Unknown/unexpected LE AIS ATT request.");
}

static void ais_exec_write_cback(tCONN_ID /*conn_id*/, uint32_t /*trans_id*/,
                                 const RawAddress& /*remote_bda*/, tGATT_EXEC_FLAG /*exec_write*/) {
}
static void ais_mtu_changed_cback(tCONN_ID /*conn_id*/, const RawAddress& /*remote_bda*/,
                                  uint16_t /*mtu*/) {}
static void ais_conf_cback(tCONN_ID /*conn_id*/, uint32_t /*trans_id*/,
                           const RawAddress& /*remote_bda*/) {}

static tGATT_REQ_CBACK ais_req_cback = {
        .read_characteristic_cb = ais_read_characteristic_cback,
        .read_descriptor_cb = ais_read_descriptor_cback,
        .write_characteristic_cb = ais_write_characteristic_or_descriptor_cback,
        .write_descriptor_cb = ais_write_characteristic_or_descriptor_cback,
        .exec_write_cb = ais_exec_write_cback,
        .mtu_changed_cb = ais_mtu_changed_cback,
        .conf_cb = ais_conf_cback,
};

static stack::tGATT_CBACK ais_cback = {
        .p_req_cb = &ais_req_cback,
};

static void ais_read_characteristic_cback(tCONN_ID conn_id, uint32_t trans_id,
                                          const RawAddress& /*remote_bda*/, uint16_t handle,
                                          uint16_t offset, bool is_long) {
  tGATT_STATUS status = GATT_INVALID_PDU;
  tGATTS_RSP rsp_msg = {};
  tGATT_VALUE* p_value = &rsp_msg.attr_value;
  uint8_t* p = p_value->value;

  p_value->handle = handle;

  if (handle == attr_api_level_handle) {
    if (is_long) {
      p_value->offset = offset;
      status = GATT_NOT_LONG;
    } else {
      UINT32_TO_STREAM(p, api_level);
      p_value->len = 4;
      status = GATT_SUCCESS;
    }
  } else {
    status = GATT_NOT_FOUND;
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
void AIS_Init(void) {
  api_level = bluetooth::os::GetSystemPropertyUint32(kPropertyAndroidAPILevel,
                                                     kPropertyAndroidAPILevelDefault);
  // Add Android OS identifier if API level is defined.
  if (api_level == kPropertyAndroidAPILevelDefault) {
    warn("Failed to identify API level. Cannot initialize AIS");
    return;
  }

  std::array<uint8_t, Uuid::kNumBytes128> tmp;
  tmp.fill(0xc5);  // any number is fine here
  Uuid app_uuid = Uuid::From128BitBE(tmp);

  tGATT_IF gatt_if = stack::appRegister(app_uuid, "Ais", &ais_cback, false);

  stack::appStartIf(gatt_if);

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
