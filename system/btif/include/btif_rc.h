/*
 * Copyright 2018 The Android Open Source Project
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

#pragma once

#include "bta_av_api.h"
#include "hardware/bt_rc.h"
#include "types/raw_address.h"

class RawAddress;

const btrc_ctrl_interface_t* btif_rc_ctrl_get_interface(void);

void btif_rc_handler(tBTA_AV_EVT event, tBTA_AV* p_data);
uint8_t btif_rc_get_connected_peer_handle(const RawAddress& peer_addr);
bool btif_rc_is_connected_peer(const RawAddress& peer_addr);
void btif_rc_check_pending_cmd(const RawAddress& peer_addr);
void btif_rc_get_addr_by_handle(uint8_t handle, RawAddress& rc_addr);
void btif_debug_rc_dump(int fd);
