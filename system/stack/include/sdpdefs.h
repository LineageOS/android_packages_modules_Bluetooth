/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This file contains the definitions for the SDP API
 *
 ******************************************************************************/

#ifndef SDP_DEFS_H
#define SDP_DEFS_H

#include <base/strings/stringprintf.h>

#include <string>

#include "macros.h"

/* Define the service attribute IDs.
*/
#define ATTR_ID_SERVICE_RECORD_HDL 0x0000
#define ATTR_ID_SERVICE_CLASS_ID_LIST 0x0001
#define ATTR_ID_SERVICE_RECORD_STATE 0x0002
#define ATTR_ID_SERVICE_ID 0x0003
#define ATTR_ID_PROTOCOL_DESC_LIST 0x0004
#define ATTR_ID_BROWSE_GROUP_LIST 0x0005
#define ATTR_ID_LANGUAGE_BASE_ATTR_ID_LIST 0x0006
#define ATTR_ID_BT_PROFILE_DESC_LIST 0x0009
#define ATTR_ID_DOCUMENTATION_URL 0x000A
#define ATTR_ID_CLIENT_EXE_URL 0x000B
#define ATTR_ID_ADDITION_PROTO_DESC_LISTS 0x000D

#define LANGUAGE_BASE_ID 0x0100
#define ATTR_ID_SERVICE_NAME (LANGUAGE_BASE_ID + 0x0000)
#define ATTR_ID_SERVICE_DESCRIPTION (LANGUAGE_BASE_ID + 0x0001)
#define ATTR_ID_PROVIDER_NAME (LANGUAGE_BASE_ID + 0x0002)

/* Device Identification (DI)
*/
#define ATTR_ID_SPECIFICATION_ID 0x0200
#define ATTR_ID_VENDOR_ID 0x0201
#define ATTR_ID_PRODUCT_ID 0x0202
#define ATTR_ID_PRODUCT_VERSION 0x0203
#define ATTR_ID_PRIMARY_RECORD 0x0204
#define ATTR_ID_VENDOR_ID_SOURCE 0x0205

#define BLUETOOTH_DI_SPECIFICATION 0x0103 /* 1.3 */
#define DI_VENDOR_ID_SOURCE_BTSIG 0x0001
#define DI_VENDOR_ID_SOURCE_USBIF 0x0002

#define ATTR_ID_GOEP_L2CAP_PSM 0x0200

#define ATTR_ID_NETWORK 0x0301
#define ATTR_ID_FAX_CLASS_1_SUPPORT 0x0302
#define ATTR_ID_REMOTE_AUDIO_VOLUME_CONTROL 0x0302
#define ATTR_ID_SUPPORTED_FORMATS_LIST 0x0303
#define ATTR_ID_SUPPORTED_FEATURES 0x0311         /* HFP, BIP */
#define ATTR_ID_SUPPORTED_REPOSITORIES 0x0314  /* Phone book access Profile */
#define ATTR_ID_MAS_INSTANCE_ID 0x0315         /* MAP profile */
#define ATTR_ID_SUPPORTED_MSG_TYPE 0x0316      /* MAP profile */
#define ATTR_ID_MAP_SUPPORTED_FEATURES 0x0317  /* MAP profile */
#define ATTR_ID_PBAP_SUPPORTED_FEATURES 0x0317 /* PBAP profile */

/* These values are for the MPS (Multi-Profile Specification) */
#define ATTR_ID_MPS_SUPPORTED_SCENARIOS_MPSD 0x0200
#define ATTR_ID_MPS_SUPPORTED_SCENARIOS_MPMD 0x0201
#define ATTR_ID_MPS_SUPPORTED_DEPENDENCIES 0x0202

/* These values are for the PAN profile */
#define ATTR_ID_SECURITY_DESCRIPTION 0x030A
#define ATTR_ID_NET_ACCESS_TYPE 0x030B
#define ATTR_ID_MAX_NET_ACCESS_RATE 0x030C

/* These values are for HID profile */
#define ATTR_ID_HID_DEVICE_RELNUM 0x0200
#define ATTR_ID_HID_PARSER_VERSION 0x0201
#define ATTR_ID_HID_DEVICE_SUBCLASS 0x0202
#define ATTR_ID_HID_COUNTRY_CODE 0x0203
#define ATTR_ID_HID_VIRTUAL_CABLE 0x0204
#define ATTR_ID_HID_RECONNECT_INITIATE 0x0205
#define ATTR_ID_HID_DESCRIPTOR_LIST 0x0206
#define ATTR_ID_HID_LANGUAGE_ID_BASE 0x0207
#define ATTR_ID_HID_SDP_DISABLE 0x0208
#define ATTR_ID_HID_BATTERY_POWER 0x0209
#define ATTR_ID_HID_REMOTE_WAKE 0x020A
#define ATTR_ID_HID_PROFILE_VERSION 0x020B
#define ATTR_ID_HID_LINK_SUPERVISION_TO 0x020C
#define ATTR_ID_HID_NORMALLY_CONNECTABLE 0x020D
#define ATTR_ID_HID_BOOT_DEVICE 0x020E
#define ATTR_ID_HID_SSR_HOST_MAX_LAT 0x020F
#define ATTR_ID_HID_SSR_HOST_MIN_TOUT 0x0210

inline std::string sdp_attr_id_text(const unsigned& id) {
  switch (id) {
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_RECORD_HDL);
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_CLASS_ID_LIST);
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_RECORD_STATE);
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_ID);
    CASE_RETURN_TEXT(ATTR_ID_PROTOCOL_DESC_LIST);
    CASE_RETURN_TEXT(ATTR_ID_BROWSE_GROUP_LIST);
    CASE_RETURN_TEXT(ATTR_ID_LANGUAGE_BASE_ATTR_ID_LIST);
    CASE_RETURN_TEXT(ATTR_ID_BT_PROFILE_DESC_LIST);
    CASE_RETURN_TEXT(ATTR_ID_DOCUMENTATION_URL);
    CASE_RETURN_TEXT(ATTR_ID_CLIENT_EXE_URL);
    CASE_RETURN_TEXT(ATTR_ID_ADDITION_PROTO_DESC_LISTS);
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_NAME);
    CASE_RETURN_TEXT(ATTR_ID_SERVICE_DESCRIPTION);
    CASE_RETURN_TEXT(ATTR_ID_PROVIDER_NAME);
    default:
      return base::StringPrintf("SDP_ATTR_ID:[%u]", id);
  }
}

#define UUID_CODEC_CVSD 0x0001 /* CVSD */
#define UUID_CODEC_MSBC 0x0002 /* mSBC */
#define UUID_CODEC_LC3 0x0003  /* LC3 */

/* Define all the 'Descriptor Type' values.
*/
enum : unsigned {
  NIL_DESC_TYPE = 0,
  UINT_DESC_TYPE = 1,
  TWO_COMP_INT_DESC_TYPE = 2,
  UUID_DESC_TYPE = 3,
  TEXT_STR_DESC_TYPE = 4,
  BOOLEAN_DESC_TYPE = 5,
  DATA_ELE_SEQ_DESC_TYPE = 6,
  DATA_ELE_ALT_DESC_TYPE = 7,
  URL_DESC_TYPE = 8,
};

inline std::string sdp_attr_type_text(const unsigned& type) {
  switch (type) {
    CASE_RETURN_TEXT(NIL_DESC_TYPE);
    CASE_RETURN_TEXT(UINT_DESC_TYPE);
    CASE_RETURN_TEXT(TWO_COMP_INT_DESC_TYPE);
    CASE_RETURN_TEXT(UUID_DESC_TYPE);
    CASE_RETURN_TEXT(TEXT_STR_DESC_TYPE);
    CASE_RETURN_TEXT(BOOLEAN_DESC_TYPE);
    CASE_RETURN_TEXT(DATA_ELE_SEQ_DESC_TYPE);
    CASE_RETURN_TEXT(DATA_ELE_ALT_DESC_TYPE);
    CASE_RETURN_TEXT(URL_DESC_TYPE);
    default:
      return base::StringPrintf("UNKNOWN[%u]", type);
  }
}

/* Define all the "Descriptor Size" values.
*/
enum : unsigned {
  SIZE_ONE_BYTE = 0,
  SIZE_TWO_BYTES = 1,
  SIZE_FOUR_BYTES = 2,
  SIZE_EIGHT_BYTES = 3,
  SIZE_SIXTEEN_BYTES = 4,
  SIZE_IN_NEXT_BYTE = 5,
  SIZE_IN_NEXT_WORD = 6,
  SIZE_IN_NEXT_LONG = 7,
};

inline std::string sdp_attr_size_text(const unsigned& size) {
  switch (size) {
    CASE_RETURN_TEXT(SIZE_ONE_BYTE);
    CASE_RETURN_TEXT(SIZE_TWO_BYTES);
    CASE_RETURN_TEXT(SIZE_FOUR_BYTES);
    CASE_RETURN_TEXT(SIZE_EIGHT_BYTES);
    CASE_RETURN_TEXT(SIZE_SIXTEEN_BYTES);
    CASE_RETURN_TEXT(SIZE_IN_NEXT_BYTE);
    CASE_RETURN_TEXT(SIZE_IN_NEXT_WORD);
    CASE_RETURN_TEXT(SIZE_IN_NEXT_LONG);
    default:
      return base::StringPrintf("UNKNOWN[%u]", size);
  }
}

inline unsigned element_type(const uint8_t& element) { return element >> 3; }

inline unsigned element_size(const uint8_t& element) { return element & 0x7; }

/* Language Encoding Constants */
#define LANG_ID_CODE_ENGLISH ((uint16_t)0x656e)     /* "en" */
#define LANG_ID_CHAR_ENCODE_UTF8 ((uint16_t)0x006a) /* UTF-8 */

/* Constants used for display purposes only.  These define overlapping attribute
 * values */
#define ATTR_ID_DATA_STORES_OR_NETWORK 0x0301

#endif
