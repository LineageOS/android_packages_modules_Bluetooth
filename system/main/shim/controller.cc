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

#define LOG_TAG "bt_shim_controller"

#include "main/shim/controller.h"

#include "btcore/include/module.h"
#include "hci/controller.h"
#include "hci/controller_interface.h"
#include "include/check.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "main/shim/shim.h"
#include "osi/include/future.h"
#include "stack/include/btm_status.h"
#include "types/raw_address.h"

using ::bluetooth::shim::GetController;

// Module lifecycle functions
static future_t* start_up(void);
static future_t* shut_down(void);

EXPORT_SYMBOL extern const module_t gd_controller_module = {
    .name = GD_CONTROLLER_MODULE,
    .init = nullptr,
    .start_up = start_up,
    .shut_down = shut_down,
    .clean_up = nullptr,
    .dependencies = {GD_SHIM_MODULE, nullptr}};

struct {
  bool ready;
} data_;

static future_t* start_up(void) {
  LOG_INFO("%s Starting up", __func__);
  data_.ready = true;

  return future_new_immediate(FUTURE_SUCCESS);
}

static future_t* shut_down(void) {
  data_.ready = false;
  return future_new_immediate(FUTURE_SUCCESS);
}

/**
 * Module methods
 */

static bool get_is_ready(void) { return data_.ready; }

static const controller_t interface = {.get_is_ready = get_is_ready};

const controller_t* bluetooth::shim::controller_get_interface() {
  static bool loaded = false;
  if (!loaded) {
    loaded = true;
  }
  return &interface;
}
