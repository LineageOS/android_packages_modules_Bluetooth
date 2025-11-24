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

#define LOG_TAG "bt_btif_sock_hal"

#include "btif/include/btif_sock_hal.h"

#include "btif/include/btif_sock_l2cap.h"
#include "btif/include/btif_sock_rfc.h"
#include "btif_status.h"
#include "lpp/lpp_offload_interface.h"
#include "main/shim/entry.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;

class BtifSocketHalCallback : public hal::SocketHalCallback {
public:
  void SocketOpenedComplete(uint64_t socket_id, hal::SocketStatus status) const override {
    log::info("socket_id: {}, status: {}", socket_id, static_cast<int>(status));
    if (btsock_l2cap_in_use(socket_id)) {
      do_in_main_thread(base::BindOnce(on_btsocket_l2cap_opened_complete, socket_id,
                                       (status == hal::SocketStatus::SUCCESS)));
    } else if (btsock_rfc_in_use(socket_id)) {
      do_in_main_thread(base::BindOnce(on_btsocket_rfc_opened_complete, socket_id,
                                       (status == hal::SocketStatus::SUCCESS)));
    } else {
      log::error("Unable to find socket with socket_id:{}", socket_id);
    }
  }

  void SocketClose(uint64_t socket_id) const override {
    log::info("socket_id: {}", socket_id);
    if (btsock_l2cap_in_use(socket_id)) {
      do_in_main_thread(base::BindOnce(on_btsocket_l2cap_close, socket_id));
    } else if (btsock_rfc_in_use(socket_id)) {
      do_in_main_thread(base::BindOnce(on_btsocket_rfc_close, socket_id));
    } else {
      log::error("Unable to find socket with socket_id:{}", socket_id);
    }
  }
};

static BtifSocketHalCallback btif_socket_hal_cb;

BtStatus btsock_hal_init() {
  log::info("");
  auto lpp_offload_manager_interface = bluetooth::shim::GetLppOffloadManager();
  if (lpp_offload_manager_interface == nullptr) {
    log::warn("GetLppOffloadManager() returned nullptr!");
    return BtifStatus(FAIL);
  }
  if (!lpp_offload_manager_interface->RegisterSocketHalCallback(&btif_socket_hal_cb)) {
    log::warn("RegisterSocketHalCallback() failed!");
    return BtifStatus(FAIL);
  }
  return BtifStatus();
}
