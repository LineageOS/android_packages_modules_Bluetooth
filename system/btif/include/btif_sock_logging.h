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

#pragma once

#include "types/raw_address.h"

void btif_sock_connection_logger(const RawAddress& address, int port, int type,
                                 int state, int role, int uid, int server_port,
                                 int64_t tx_bytes, int64_t rx_bytes,
                                 const char* server_name);
void btif_sock_dump(int fd);
