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

/*******************************************************************************
 *  L2CAP Socket Interface
 ******************************************************************************/

#ifndef BTIF_SOCK_L2CAP_H
#define BTIF_SOCK_L2CAP_H

#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_sock.h>

#include "bt_status.h"
#include "btif_uid.h"

BtStatus btsock_l2cap_init(int handle, uid_set_t* set);
BtStatus btsock_l2cap_cleanup();
BtStatus btsock_l2cap_listen(const char* name, int channel, int* sock_fd, int flags, int app_uid,
                             btsock_data_path_t data_path, const char* socket_name, uint64_t hub_id,
                             uint64_t endpoint_id, int max_rx_packet_size);
BtStatus btsock_l2cap_connect(RawAddress bd_addr, int channel, int* sock_fd, int flags, int app_uid,
                              btsock_data_path_t data_path, const char* socket_name,
                              uint64_t hub_id, uint64_t endpoint_id, int max_rx_packet_size);
void btsock_l2cap_signaled(int fd, int flags, uint32_t user_id);
void on_l2cap_psm_assigned(int id, int psm);
BtStatus btsock_l2cap_disconnect(RawAddress bd_addr);
bool btsock_l2cap_in_use(uint64_t socket_id);
void on_btsocket_l2cap_opened_complete(uint64_t socket_id, bool success);
void on_btsocket_l2cap_close(uint64_t socket_id);

#endif
