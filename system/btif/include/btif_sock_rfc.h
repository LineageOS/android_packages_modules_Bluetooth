/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
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
 *  Filename:      btif_sock.h
 *
 *  Description:   Bluetooth socket Interface
 *
 ******************************************************************************/

#ifndef BTIF_SOCK_RFC_H
#define BTIF_SOCK_RFC_H

#include <bluetooth/types/address.h>
#include <bluetooth/types/uuid.h>

#include "bt_status.h"
#include "btif_uid.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_sock.h"

BtStatus btsock_rfc_init(int handle, uid_set_t* set);
void btsock_rfc_cleanup();
BtStatus btsock_rfc_control_req(uint8_t dlci, const RawAddress& bd_addr, uint8_t modem_signal,
                                uint8_t break_signal, uint8_t discard_buffers,
                                uint8_t break_signal_seq, bool fc);
BtStatus btsock_rfc_listen(const char* name, const bluetooth::Uuid* uuid, int channel, int* sock_fd,
                           int flags, int app_uid, btsock_data_path_t data_path,
                           const char* socket_name, uint64_t hub_id, uint64_t endpoint_id,
                           int max_rx_packet_size);
BtStatus btsock_rfc_connect(RawAddress bd_addr, const bluetooth::Uuid* uuid, int channel,
                            int* sock_fd, int flags, int app_uid, btsock_data_path_t data_path,
                            const char* socket_name, uint64_t hub_id, uint64_t endpoint_id,
                            int max_rx_packet_size);
void btsock_rfc_signaled(int fd, int flags, uint32_t user_id);
BtStatus btsock_rfc_disconnect(RawAddress bd_addr);
bool btsock_rfc_in_use(uint64_t socket_id);
void on_btsocket_rfc_opened_complete(uint64_t socket_id, bool success);
void on_btsocket_rfc_close(uint64_t socket_id);

#endif
