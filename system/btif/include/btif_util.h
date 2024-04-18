/******************************************************************************
 *
 *  Copyright (c) 2014 The Android Open Source Project
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

#ifndef BTIF_UTIL_H
#define BTIF_UTIL_H

#include <hardware/bluetooth.h>
#include <stdbool.h>
#include <sys/time.h>

#include "stack/include/bt_dev_class.h"

/*******************************************************************************
 *  Constants & Macros
 ******************************************************************************/

#define CASE_RETURN_STR(const) \
  case const:                  \
    return #const;

/*******************************************************************************
 *  Type definitions for callback functions
 ******************************************************************************/

/*******************************************************************************
 *  Functions
 ******************************************************************************/

std::string dump_bt_status(bt_status_t status);
std::string dump_dm_search_event(uint16_t event);
std::string dump_dm_event(uint16_t event);
std::string dump_hf_event(uint16_t event);
std::string dump_hf_client_event(uint16_t event);
std::string dump_hd_event(uint16_t event);
std::string dump_property_type(bt_property_type_t type);
std::string dump_adapter_scan_mode(bt_scan_mode_t mode);
std::string dump_thread_evt(bt_cb_thread_evt evt);
std::string dump_av_conn_state(uint16_t event);
std::string dump_av_audio_state(uint16_t event);
std::string dump_rc_opcode(uint8_t opcode);
std::string dump_rc_event(uint8_t event);
std::string dump_rc_notification_event_id(uint8_t event_id);
std::string dump_rc_pdu(uint8_t pdu);

uint32_t devclass2uint(const DEV_CLASS dev_class);
DEV_CLASS uint2devclass(uint32_t dev);

int ascii_2_hex(const char* p_ascii, int len, uint8_t* p_hex);

#endif /* BTIF_UTIL_H */
