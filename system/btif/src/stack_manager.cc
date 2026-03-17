/******************************************************************************
 *
 *  Copyright 2014 Google, Inc.
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

#define LOG_TAG "bt_stack_manager"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>

#include <cstdlib>
#include <cstring>

#include "bta/dm/bta_dm_int.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_ras_api.h"
#include "bta/sys/bta_sys.h"
#include "btcore/include/module.h"
#include "btcore/include/osi_module.h"
#include "btif/include/btif_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_config.h"
#include "btif/include/btif_debug_conn.h"
#include "btif/include/btif_profile_queue.h"
#include "btif/include/core_callbacks.h"
#include "btif/include/stack_manager_t.h"
#include "device/include/device_iot_config.h"
#include "device/include/interop.h"
#include "internal_include/bt_target.h"
#include "internal_include/stack_config.h"
#include "main/shim/shim.h"
#include "stack/include/acl_api.h"
#include "stack/include/ais_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/gap_api.h"
#include "stack/include/gatt_api.h"
#include "stack/include/l2cap_module.h"
#include "stack/include/main_thread.h"
#include "stack/include/port_api.h"
#include "stack/include/sdp_api.h"
#include "stack/include/smp_api.h"

// Validate or respond to various conditional compilation flags

// Once BTA_PAN_INCLUDED is no longer exposed via bt_target.h
// this check and error statement may be removed.
static_assert(BTA_PAN_INCLUDED,
              "#define BTA_PAN_INCLUDED preprocessor compilation flag is unsupported"
              "  Pan profile is always included in the bluetooth stack"
              "*** Conditional Compilation Directive error");

// Once PAN_SUPPORTS_ROLE_NAP is no longer exposed via bt_target.h
// this check and error statement may be removed.
static_assert(PAN_SUPPORTS_ROLE_NAP,
              "#define PAN_SUPPORTS_ROLE_NAP preprocessor compilation flag is unsupported"
              "  Pan profile always supports network access point in the bluetooth stack"
              "*** Conditional Compilation Directive error");

// Once PAN_SUPPORTS_ROLE_PANU is no longer exposed via bt_target.h
// this check and error statement may be removed.
static_assert(PAN_SUPPORTS_ROLE_PANU,
              "#define PAN_SUPPORTS_ROLE_PANU preprocessor compilation flag is "
              "unsupported"
              "  Pan profile always supports user as a client in the bluetooth stack"
              "*** Conditional Compilation Directive error");

// Once BTA_HH_INCLUDED is no longer exposed via bt_target.h
// this check and error statement may be removed.
static_assert(BTA_HH_INCLUDED,
              "#define BTA_HH_INCLUDED preprocessor compilation flag is "
              "unsupported"
              "  Host interface device profile is always enabled in the bluetooth stack"
              "*** Conditional Compilation Directive error");

using namespace bluetooth;

// If initialized, any of the bluetooth API functions can be called.
// (e.g. turning logging on and off, enabling/disabling the stack, etc)
static bool stack_is_initialized;
// If running, the stack is fully up and able to bluetooth.
static bool is_running;

static void event_signal_stack_up(void* context);

static bluetooth::core::CoreInterface* interfaceToProfiles;

bluetooth::core::CoreInterface* GetInterfaceToProfiles() { return interfaceToProfiles; }

// Unvetted includes/imports, etc which should be removed or vetted in the
// future
static future_t* hack_future;
// End unvetted section

bool stack_is_running() { return is_running; }

// Internal functions
extern const module_t btif_config_module;
extern const module_t gd_shim_module;
extern const module_t interop_module;
extern const module_t osi_module;
extern const module_t stack_config_module;
extern const module_t device_iot_config_module;

struct module_lookup {
  const char* name;
  const module_t* module;
};

const struct module_lookup module_table[] = {
        {BTIF_CONFIG_MODULE, &btif_config_module},
        {GD_SHIM_MODULE, &gd_shim_module},
        {INTEROP_MODULE, &interop_module},
        {OSI_MODULE, &osi_module},
        {STACK_CONFIG_MODULE, &stack_config_module},
        {DEVICE_IOT_CONFIG_MODULE, &device_iot_config_module},
        {NULL, NULL},
};

static const module_t* get_local_module(const char* name) {
  size_t len = strlen(name);

  for (const struct module_lookup* l = module_table; l->module; l++) {
    if (strncmp(l->name, name, len) == 0) {
      return l->module;
    }
  }

  log::fatal("Cannot find module {}, aborting", name);
  return nullptr;
}

// Synchronous function to initialize the stack
void stack_init(bluetooth::core::CoreInterface* interface) {
  log::info("Initializing the stack");
  log::assert_that(!stack_is_initialized, "assert failed: !stack_is_initialized");

  // all callbacks out of libbluetooth-core happen via this interface
  interfaceToProfiles = interface;

  module_management_start();

  main_thread_start_up();

  module_init(get_local_module(DEVICE_IOT_CONFIG_MODULE));
  module_init(get_local_module(OSI_MODULE));
  module_start_up(get_local_module(GD_SHIM_MODULE));
  module_init(get_local_module(BTIF_CONFIG_MODULE));
  btif_init_bluetooth();

  module_init(get_local_module(INTEROP_MODULE));
  module_init(get_local_module(STACK_CONFIG_MODULE));

  // stack init is synchronous, so no waiting necessary here
  stack_is_initialized = true;
  log::info("finished");
}

// Synchronous function to start up the stack
void stack_enable(ProfileStartCallback startProfiles, const std::string local_name) {
  log::info("Bringing up the stack");
  log::assert_that(!is_running, "assert failed: !is_running");
  log::assert_that(stack_is_initialized, "assert failed: stack_is_initialized");

  get_btm_client_interface().lifecycle.btm_init();
  module_start_up(get_local_module(BTIF_CONFIG_MODULE));

  l2c_init();
  sdp_init();
  gatt_init();
  SMP_Init(get_security_client_interface().BTM_GetSecurityMode());
  get_btm_client_interface().lifecycle.btm_ble_init();

  RFCOMM_Init();
  GAP_Init();
  AIS_Init();

  startProfiles();

  bta_sys_init();
  BTA_GATT_Init_gatt_pm_callbacks();
  gatt_set_debug_conn_state_cb(btif_debug_conn_state);

  btif_init_ok();
  BTA_dm_init();
  bta_dm_enable(btif_dm_sec_evt, btif_dm_acl_evt);

  btm_acl_device_down();
  get_btm_client_interface().lifecycle.BTM_reset_complete();

  BTA_dm_on_hw_on(local_name);

  bluetooth::ras::GetRasServer()->Initialize();
  bluetooth::ras::GetRasClient()->Initialize();

  is_running = true;
  log::info("finished");
  do_in_jni_thread(base::BindOnce(event_signal_stack_up, nullptr));
}

void stack_disable(ProfileStopCallback stopProfiles) {
  log::info("Bringing down the stack");
  log::assert_that(is_running, "assert failed: is_running");

  future_t* local_hack_future = future_new();
  hack_future = local_hack_future;
  is_running = false;

  btif_dm_on_disable();
  stopProfiles();

  do_in_main_thread(base::BindOnce(bta_dm_disable));

  btif_dm_cleanup();

  future_await(local_hack_future);

  bta_sys_disable();
  BTA_dm_on_hw_off();

  module_shut_down(get_local_module(BTIF_CONFIG_MODULE));
  module_shut_down(get_local_module(DEVICE_IOT_CONFIG_MODULE));

  gatt_free();
  do_in_main_thread(base::BindOnce(sdp_free));
  l2c_free();
  get_btm_client_interface().lifecycle.btm_ble_free();

  // btm_free() is called in main thread, and is a blocking call.
  get_btm_client_interface().lifecycle.btm_free();

  log::info("Native disable done. Notifying the java now");

  GetInterfaceToProfiles()->events->invoke_adapter_state_changed_cb(BT_STATE_OFF);

  log::info("Finished");
}

// Synchronous function to clean up the stack
void stack_cleanup() {
  log::info("Cleaning up the stack");
  log::assert_that(stack_is_initialized, "assert failed: stack_is_initialized");
  stack_is_initialized = false;
  log::assert_that(!is_running, "assert failed: !is_running");

  btif_cleanup_bluetooth();

  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    main_thread_suspend();
  } else {
    main_thread_shut_down();
  }

  module_clean_up(get_local_module(STACK_CONFIG_MODULE));
  module_clean_up(get_local_module(INTEROP_MODULE));

  module_clean_up(get_local_module(BTIF_CONFIG_MODULE));
  module_clean_up(get_local_module(DEVICE_IOT_CONFIG_MODULE));

  log::info("Gd shim module disabled");
  module_shut_down(get_local_module(GD_SHIM_MODULE));

  module_clean_up(get_local_module(OSI_MODULE));

  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    main_thread_shut_down();
  }

  module_management_stop();
  log::info("finished");
}

static void event_signal_stack_up(void* /* context */) {
  // Notify BTIF connect queue that we've brought up the stack. It's
  // now time to dispatch all the pending profile connect requests.
  btif_queue_connect_next();
  GetInterfaceToProfiles()->events->invoke_adapter_state_changed_cb(BT_STATE_ON);
}

future_t* stack_manager_get_hack_future() { return hack_future; }

namespace bluetooth {
namespace legacy {
namespace testing {

void set_interface_to_profiles(bluetooth::core::CoreInterface* interfaceToProfiles) {
  ::interfaceToProfiles = interfaceToProfiles;
}

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth
