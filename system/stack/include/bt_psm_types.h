/*
 * Copyright 2023 The Android Open Source Project
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

#include <bluetooth/log.h>

#include <cstdint>
#include <string>

#include "include/macros.h"

enum tBT_PSM : uint16_t {
  BT_PSM_SDP = 0x0001,
  BT_PSM_RFCOMM = 0x0003,
  BT_PSM_TCS = 0x0005,
  BT_PSM_CTP = 0x0007,
  BT_PSM_BNEP = 0x000F,
  BT_PSM_HIDC = 0x0011,
  HID_PSM_CONTROL = 0x0011,
  BT_PSM_HIDI = 0x0013,
  HID_PSM_INTERRUPT = 0x0013,
  BT_PSM_UPNP = 0x0015,
  BT_PSM_AVCTP = 0x0017,
  BT_PSM_AVDTP = 0x0019,
  BT_PSM_AVCTP_13 = 0x001B, /* Advanced Control - Browsing */
  BT_PSM_UDI_CP = 0x001D, /* Unrestricted Digital Information Profile C-Plane */
  BT_PSM_ATT = 0x001F,    /* Attribute Protocol  */
  BT_PSM_EATT = 0x0027,
  /* We will not allocate a PSM in the reserved range to 3rd party apps
   */
  BRCM_RESERVED_PSM_START = 0x5AE1,
  BRCM_RESERVED_PSM_END = 0x5AFF,
};

inline std::string bt_psm_text(const tBT_PSM& psm) {
  switch (psm) {
    CASE_RETURN_STRING_HEX04(BT_PSM_SDP);
    CASE_RETURN_STRING_HEX04(BT_PSM_RFCOMM);
    CASE_RETURN_STRING_HEX04(BT_PSM_TCS);
    CASE_RETURN_STRING_HEX04(BT_PSM_CTP);
    CASE_RETURN_STRING_HEX04(BT_PSM_BNEP);
    CASE_RETURN_STRING_HEX04(BT_PSM_HIDC);
    CASE_RETURN_STRING_HEX04(BT_PSM_HIDI);
    CASE_RETURN_STRING_HEX04(BT_PSM_UPNP);
    CASE_RETURN_STRING_HEX04(BT_PSM_AVCTP);
    CASE_RETURN_STRING_HEX04(BT_PSM_AVDTP);
    CASE_RETURN_STRING_HEX04(BT_PSM_AVCTP_13);
    CASE_RETURN_STRING_HEX04(BT_PSM_UDI_CP);
    CASE_RETURN_STRING_HEX04(BT_PSM_ATT);
    CASE_RETURN_STRING_HEX04(BT_PSM_EATT);
    CASE_RETURN_STRING_HEX04(BRCM_RESERVED_PSM_START);
    CASE_RETURN_STRING_HEX04(BRCM_RESERVED_PSM_END);
  };
  RETURN_UNKNOWN_TYPE_STRING(type, psm);
}
