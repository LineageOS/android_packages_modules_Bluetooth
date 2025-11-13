/*
 * Copyright 2019 The Android Open Source Project
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

#define LOG_TAG "bt_shim"

#include "main/shim/shim.h"

#include <bluetooth/log.h>

#include "main/shim/hci_layer.h"
#include "main/shim/stack.h"
#include "stack/include/btu_hcif.h"
#include "stack/include/main_thread.h"

static const hci_t* hci;

static void post_to_main_message_loop(BT_HDR* p_msg) {
  if (!do_in_main_thread(base::Bind(&btu_hci_msg_process, p_msg))) {
    bluetooth::log::error("do_in_main_thread failed");
  }
}

static future_t* ShimModuleStartUp() {
  hci = bluetooth::shim::hci_layer_get_interface();
  bluetooth::log::assert_that(hci, "could not get hci layer interface.");

  hci->set_data_cb(base::Bind(&post_to_main_message_loop));

  bluetooth::shim::Stack::GetInstance()->StartEverything();
  return kReturnImmediate;
}

static future_t* GeneralShutDown() {
  bluetooth::shim::Stack::GetInstance()->Stop();
  return kReturnImmediate;
}

EXPORT_SYMBOL extern const module_t gd_shim_module = {.name = GD_SHIM_MODULE,
                                                      .init = kUnusedModuleApi,
                                                      .start_up = ShimModuleStartUp,
                                                      .shut_down = GeneralShutDown,
                                                      .clean_up = kUnusedModuleApi,
                                                      .dependencies = {kUnusedModuleDependencies}};
