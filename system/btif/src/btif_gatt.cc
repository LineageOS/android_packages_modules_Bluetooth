/******************************************************************************
 *
 *  Copyright 2009-2013 Broadcom Corporation
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
 *  Filename:      btif_gatt.c
 *
 *  Description:   GATT Profile Bluetooth Interface
 *
 ******************************************************************************/

#define LOG_TAG "bt_btif_gatt"

#include "btif_gatt.h"

#include <base/functional/bind.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>
#include <string.h>

#include "bta/include/bta_gatt_api.h"
#include "btif/include/btif_jni_task.h"
#include "btif_status.h"
#include "main/shim/distance_measurement_manager.h"
#include "main/shim/le_advertising_manager.h"
#include "stack/include/main_thread.h"

const btgatt_callbacks_t* bt_gatt_callbacks = NULL;

/*******************************************************************************
 *
 * Function         btif_gatt_init
 *
 * Description      Initializes the GATT interface
 *
 * Returns          BtStatus
 *
 ******************************************************************************/
static BtStatus btif_gatt_init(const btgatt_callbacks_t* callbacks) {
  bt_gatt_callbacks = callbacks;
  do_in_main_thread(base::BindOnce(&BTA_GATTS_InitBonded));
  return BtifStatus();
}

static void btif_gatt_cleanup_impl() {
  if (bt_gatt_callbacks) {
    bluetooth::log::info("btif_gatt_cleanup clearing bt_gatt_callbacks");
    bt_gatt_callbacks = NULL;
  }

  BTA_GATTC_Disable();
  do_in_main_thread(base::BindOnce(&BTA_GATTS_Disable));
}
/*******************************************************************************
 *
 * Function         btif_gatt_cleanup
 *
 * Description      Closes the GATT interface
 *
 * Returns          void
 *
 ******************************************************************************/
static void btif_gatt_cleanup(void) {
  BtStatus status = do_in_jni_thread(base::BindOnce(&btif_gatt_cleanup_impl));
  if (status != BtifStatus(SUCCESS)) {
    bluetooth::log::warn("can't post cleanup to JNI");
    return;
  }
  bluetooth::log::info("btif_gatt_cleanup finished success");
}

static btgatt_interface_t btgattInterface = {
        .size = sizeof(btgattInterface),

        .init = btif_gatt_init,
        .cleanup = btif_gatt_cleanup,

        .client = &btgattClientInterface,
        .server = &btgattServerInterface,
        .scanner = nullptr,                      // filled in btif_gatt_get_interface
        .advertiser = nullptr,                   // filled in btif_gatt_get_interface
        .distance_measurement_manager = nullptr  // filled in btif_gatt_get_interface
};

/*******************************************************************************
 *
 * Function         btif_gatt_get_interface
 *
 * Description      Get the gatt callback interface
 *
 * Returns          btgatt_interface_t
 *
 ******************************************************************************/
const btgatt_interface_t* btif_gatt_get_interface() {
  // TODO(jpawlowski) right now initializing advertiser field in static
  // structure cause explosion of dependencies. It must be initialized here
  // until those dependencies are properly abstracted for tests.
  btgattInterface.scanner = get_ble_scanner_instance();
  btgattInterface.advertiser = bluetooth::shim::get_ble_advertiser_instance();
#ifndef TARGET_FLOSS
  btgattInterface.distance_measurement_manager =
          bluetooth::shim::get_distance_measurement_instance();
#endif
  return &btgattInterface;
}
