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

#ifndef BTM_BLE_API_TYPES_H
#define BTM_BLE_API_TYPES_H

#include <base/functional/callback_forward.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/ble_address_with_type.h>
#include <bluetooth/types/bt_octets.h>
#include <hardware/bt_common_types.h>

#include <cstdint>
#include <vector>

#include "stack/include/bt_device_type.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"

#define CHNL_MAP_LEN 5
typedef uint8_t tBTM_BLE_CHNL_MAP[CHNL_MAP_LEN];

enum : uint8_t {
  /* 0x00-0x04 only used for set advertising parameter command */
  BTM_BLE_CONNECT_EVT = 0x00,
  /* Connectable directed advertising */
  BTM_BLE_CONNECT_DIR_EVT = 0x01,
  /* Scannable undirected advertising */
  BTM_BLE_DISCOVER_EVT = 0x02,
  /* Non connectable undirected advertising */
  BTM_BLE_NON_CONNECT_EVT = 0x03,
  /* Connectable low duty cycle directed advertising  */
  BTM_BLE_CONNECT_LO_DUTY_DIR_EVT = 0x04,
};

/* 0x00 - 0x04 can be received on adv event type */
typedef enum : uint8_t {
  BTM_BLE_ADV_IND_EVT = 0x00,
  BTM_BLE_ADV_DIRECT_IND_EVT = 0x01,
  BTM_BLE_ADV_SCAN_IND_EVT = 0x02,
  BTM_BLE_ADV_NONCONN_IND_EVT = 0x03,
  BTM_BLE_SCAN_RSP_EVT = 0x04,
} tBTM_BLE_EVT;

typedef uint32_t tBTM_BLE_REF_VALUE;

#define BTM_BLE_SCAN_MODE_PASS 0
#define BTM_BLE_SCAN_MODE_ACTI 1
#define BTM_BLE_SCAN_MODE_NONE 0xff
typedef uint8_t tBLE_SCAN_MODE;

#define BTM_BLE_BATCH_SCAN_MODE_DISABLE 0
#define BTM_BLE_BATCH_SCAN_MODE_PASS 1
#define BTM_BLE_BATCH_SCAN_MODE_ACTI 2
#define BTM_BLE_BATCH_SCAN_MODE_PASS_ACTI 3

typedef uint8_t tBTM_BLE_BATCH_SCAN_MODE;

/* advertising channel map */
#define BTM_BLE_ADV_CHNL_37 (0x01 << 0)
#define BTM_BLE_ADV_CHNL_38 (0x01 << 1)
#define BTM_BLE_ADV_CHNL_39 (0x01 << 2)
typedef uint8_t tBTM_BLE_ADV_CHNL_MAP;

/*d efault advertising channel map */
#ifndef BTM_BLE_DEFAULT_ADV_CHNL_MAP
#define BTM_BLE_DEFAULT_ADV_CHNL_MAP \
  (BTM_BLE_ADV_CHNL_37 | BTM_BLE_ADV_CHNL_38 | BTM_BLE_ADV_CHNL_39)
#endif

/* advertising filter policy */
#define AP_SCAN_CONN_ALL 0x00 /* default */
#define AP_SCAN_WL_CONN_ALL 0x01
#define AP_SCAN_ALL_CONN_WL 0x02
#define AP_SCAN_CONN_WL 0x03
#define AP_SCAN_CONN_POLICY_MAX 0x04
typedef uint8_t tBTM_BLE_AFP;

/* default advertising filter policy */
#ifndef BTM_BLE_DEFAULT_AFP
#define BTM_BLE_DEFAULT_AFP AP_SCAN_CONN_ALL
#endif

/* scanning filter policy */
/* 0: accept adv packet from all, directed adv pkt not directed */
/*    to local device is ignored */
#define SP_ADV_ALL 0x00
/* 1. only accept adv packet from devices in accept list */
#define SP_ACCEPT_LIST_ONLY 0x01

typedef uint8_t tBTM_BLE_SFP;

#ifndef BTM_BLE_DEFAULT_SFP
#define BTM_BLE_DEFAULT_SFP SP_ADV_ALL
#endif

/* Full scan boundary values */
#define BTM_BLE_ADV_SCAN_FULL_MIN 0x00
#define BTM_BLE_ADV_SCAN_FULL_MAX 0x64

/* Partial scan boundary values */
#define BTM_BLE_ADV_SCAN_TRUNC_MAX BTM_BLE_ADV_SCAN_FULL_MAX

/* Threshold values */
#define BTM_BLE_ADV_SCAN_THR_MAX BTM_BLE_ADV_SCAN_FULL_MAX

/* connection parameter boundary values */
#define BTM_BLE_SCAN_INT_MIN 0x0004
#define BTM_BLE_SCAN_INT_MAX 0x4000
#define BTM_BLE_SCAN_WIN_MIN 0x0004
#define BTM_BLE_SCAN_WIN_MAX 0x4000
#define BTM_BLE_EXT_SCAN_INT_MAX 0x00FFFFFF
#define BTM_BLE_EXT_SCAN_WIN_MAX 0xFFFF
#define BTM_BLE_CONN_INT_MIN 0x0006
#define BTM_BLE_CONN_INT_MAX 0x0C80
#define BTM_BLE_CONN_LATENCY_MAX 500
#define BTM_BLE_CONN_SUP_TOUT_MIN 0x000A
#define BTM_BLE_CONN_SUP_TOUT_MAX 0x0C80
/* use this value when a specific value not to be overwritten */
#define BTM_BLE_CONN_PARAM_UNDEF 0xffff
#define BTM_BLE_SCAN_PARAM_UNDEF 0xffff

/* default connection parameters if not configured, use GAP recommended value
 * for auto/selective connection */
/* default scan interval */
#ifndef BTM_BLE_SCAN_FAST_INT
#define BTM_BLE_SCAN_FAST_INT 96 /* 30 ~ 60 ms (use 60)  = 96 *0.625 */
#endif
/* default scan window for background connection, applicable for auto connection
 * or selective connection */
#ifndef BTM_BLE_SCAN_FAST_WIN
#define BTM_BLE_SCAN_FAST_WIN 48 /* 30 ms = 48 *0.625 */
#endif

/* default scan paramter used in reduced power cycle (background scanning) */
#ifndef BTM_BLE_SCAN_SLOW_INT_1
#define BTM_BLE_SCAN_SLOW_INT_1 2048 /* 1.28 s   = 2048 *0.625 */
#endif
#ifndef BTM_BLE_SCAN_SLOW_WIN_1
#define BTM_BLE_SCAN_SLOW_WIN_1 48 /* 30 ms = 48 *0.625 */
#endif

/* default scan paramter used in reduced power cycle (background scanning) */
#ifndef BTM_BLE_SCAN_SLOW_INT_2
#define BTM_BLE_SCAN_SLOW_INT_2 4096 /* 2.56 s   = 4096 *0.625 */
#endif
#ifndef BTM_BLE_SCAN_SLOW_WIN_2
#define BTM_BLE_SCAN_SLOW_WIN_2 36 /* 22.5 ms = 36 *0.625 */
#endif

/* default connection interval min */
#ifndef BTM_BLE_CONN_INT_MIN_DEF
/* recommended min: 30ms  = 24 * 1.25 */
#ifndef BTM_BLE_CONN_INT_MIN_DEF
#define BTM_BLE_CONN_INT_MIN_DEF 24
#endif
#endif

/* default connection interval max */
#ifndef BTM_BLE_CONN_INT_MAX_DEF
/* recommended max: 50 ms = 56 * 1.25 */
#ifndef BTM_BLE_CONN_INT_MAX_DEF
#define BTM_BLE_CONN_INT_MAX_DEF 40
#endif
#endif

/* default peripheral latency */
#ifndef BTM_BLE_CONN_PERIPHERAL_LATENCY_DEF
#define BTM_BLE_CONN_PERIPHERAL_LATENCY_DEF 0 /* 0 */
#endif

/* default supervision timeout */
#ifndef BTM_BLE_CONN_TIMEOUT_DEF
#define BTM_BLE_CONN_TIMEOUT_DEF 500
#endif

/* minimum supervision timeout */
#ifndef BTM_BLE_CONN_TIMEOUT_MIN_DEF
#define BTM_BLE_CONN_TIMEOUT_MIN_DEF 100
#endif

/* maximum supervision timeout */
#ifndef BTM_BLE_CONN_TIMEOUT_MAX_DEF
#define BTM_BLE_CONN_TIMEOUT_MAX_DEF 32000
#endif

/* minimum acceptable connection interval */
#ifndef BTM_BLE_CONN_INT_MIN_LIMIT
#define BTM_BLE_CONN_INT_MIN_LIMIT 0x0009
#endif

/* minimum acceptable connection interval when there is bonded Hearing Aid
 * device */
#ifndef BTM_BLE_CONN_INT_MIN_HEARINGAID
#define BTM_BLE_CONN_INT_MIN_HEARINGAID 0x0010
#endif

#define BTM_CMAC_TLEN_SIZE 8     /* 64 bits */
#define BTM_BLE_AUTH_SIGN_LEN 12 /* BLE data signature length 8 Bytes + 4 bytes counter*/
typedef uint8_t BLE_SIGNATURE[BTM_BLE_AUTH_SIGN_LEN]; /* Device address */

#ifndef BTM_BLE_HOST_SUPPORT
#define BTM_BLE_HOST_SUPPORT 0x01
#endif

#ifndef BTM_BLE_SIMULTANEOUS_HOST
#define BTM_BLE_SIMULTANEOUS_HOST 0x01
#endif

/* Appearance Values Reported with BTM_BLE_AD_TYPE_APPEARANCE */
#define BTM_BLE_APPEARANCE_UNKNOWN BLE_APPEARANCE_UNKNOWN
#define BTM_BLE_APPEARANCE_GENERIC_PHONE BLE_APPEARANCE_GENERIC_PHONE
#define BTM_BLE_APPEARANCE_GENERIC_COMPUTER BLE_APPEARANCE_GENERIC_COMPUTER
#define BTM_BLE_APPEARANCE_GENERIC_WATCH BLE_APPEARANCE_GENERIC_WATCH
#define BTM_BLE_APPEARANCE_SPORTS_WATCH BLE_APPEARANCE_SPORTS_WATCH
#define BTM_BLE_APPEARANCE_GENERIC_CLOCK BLE_APPEARANCE_GENERIC_CLOCK
#define BTM_BLE_APPEARANCE_GENERIC_DISPLAY BLE_APPEARANCE_GENERIC_DISPLAY
#define BTM_BLE_APPEARANCE_GENERIC_REMOTE BLE_APPEARANCE_GENERIC_REMOTE
#define BTM_BLE_APPEARANCE_GENERIC_EYEGLASSES BLE_APPEARANCE_GENERIC_EYEGLASSES
#define BTM_BLE_APPEARANCE_GENERIC_TAG BLE_APPEARANCE_GENERIC_TAG
#define BTM_BLE_APPEARANCE_GENERIC_KEYRING BLE_APPEARANCE_GENERIC_KEYRING
#define BTM_BLE_APPEARANCE_GENERIC_MEDIA_PLAYER BLE_APPEARANCE_GENERIC_MEDIA_PLAYER
#define BTM_BLE_APPEARANCE_GENERIC_BARCODE_SCANNER BLE_APPEARANCE_GENERIC_BARCODE_SCANNER
#define BTM_BLE_APPEARANCE_GENERIC_THERMOMETER BLE_APPEARANCE_GENERIC_THERMOMETER
#define BTM_BLE_APPEARANCE_THERMOMETER_EAR BLE_APPEARANCE_THERMOMETER_EAR
#define BTM_BLE_APPEARANCE_GENERIC_HEART_RATE BLE_APPEARANCE_GENERIC_HEART_RATE
#define BTM_BLE_APPEARANCE_HEART_RATE_BELT BLE_APPEARANCE_HEART_RATE_BELT
#define BTM_BLE_APPEARANCE_GENERIC_BLOOD_PRESSURE BLE_APPEARANCE_GENERIC_BLOOD_PRESSURE
#define BTM_BLE_APPEARANCE_BLOOD_PRESSURE_ARM BLE_APPEARANCE_BLOOD_PRESSURE_ARM
#define BTM_BLE_APPEARANCE_BLOOD_PRESSURE_WRIST BLE_APPEARANCE_BLOOD_PRESSURE_WRIST
#define BTM_BLE_APPEARANCE_GENERIC_HID BLE_APPEARANCE_GENERIC_HID
#define BTM_BLE_APPEARANCE_HID_KEYBOARD BLE_APPEARANCE_HID_KEYBOARD
#define BTM_BLE_APPEARANCE_HID_MOUSE BLE_APPEARANCE_HID_MOUSE
#define BTM_BLE_APPEARANCE_HID_JOYSTICK BLE_APPEARANCE_HID_JOYSTICK
#define BTM_BLE_APPEARANCE_HID_GAMEPAD BLE_APPEARANCE_HID_GAMEPAD
#define BTM_BLE_APPEARANCE_HID_DIGITIZER_TABLET BLE_APPEARANCE_HID_DIGITIZER_TABLET
#define BTM_BLE_APPEARANCE_HID_CARD_READER BLE_APPEARANCE_HID_CARD_READER
#define BTM_BLE_APPEARANCE_HID_DIGITAL_PEN BLE_APPEARANCE_HID_DIGITAL_PEN
#define BTM_BLE_APPEARANCE_HID_BARCODE_SCANNER BLE_APPEARANCE_HID_BARCODE_SCANNER
#define BTM_BLE_APPEARANCE_GENERIC_GLUCOSE BLE_APPEARANCE_GENERIC_GLUCOSE
#define BTM_BLE_APPEARANCE_GENERIC_WALKING BLE_APPEARANCE_GENERIC_WALKING
#define BTM_BLE_APPEARANCE_WALKING_IN_SHOE BLE_APPEARANCE_WALKING_IN_SHOE
#define BTM_BLE_APPEARANCE_WALKING_ON_SHOE BLE_APPEARANCE_WALKING_ON_SHOE
#define BTM_BLE_APPEARANCE_WALKING_ON_HIP BLE_APPEARANCE_WALKING_ON_HIP
#define BTM_BLE_APPEARANCE_GENERIC_CYCLING BLE_APPEARANCE_GENERIC_CYCLING
#define BTM_BLE_APPEARANCE_CYCLING_COMPUTER BLE_APPEARANCE_CYCLING_COMPUTER
#define BTM_BLE_APPEARANCE_CYCLING_SPEED BLE_APPEARANCE_CYCLING_SPEED
#define BTM_BLE_APPEARANCE_CYCLING_CADENCE BLE_APPEARANCE_CYCLING_CADENCE
#define BTM_BLE_APPEARANCE_CYCLING_POWER BLE_APPEARANCE_CYCLING_POWER
#define BTM_BLE_APPEARANCE_CYCLING_SPEED_CADENCE BLE_APPEARANCE_CYCLING_SPEED_CADENCE
#define BTM_BLE_APPEARANCE_GENERIC_WEARABLE_AUDIO_DEVICE \
                BLE_APPEARANCE_GENERIC_WEARABLE_AUDIO_DEVICE
#define BTM_BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_EARBUD \
                BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_EARBUD
#define BTM_BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADSET \
                BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADSET
#define BTM_BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADPHONES \
                BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADPHONES
#define BTM_BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_NECK_BAND \
                BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_NECK_BAND
#define BTM_BLE_APPEARANCE_GENERIC_PULSE_OXIMETER BLE_APPEARANCE_GENERIC_PULSE_OXIMETER
#define BTM_BLE_APPEARANCE_PULSE_OXIMETER_FINGERTIP BLE_APPEARANCE_PULSE_OXIMETER_FINGERTIP
#define BTM_BLE_APPEARANCE_PULSE_OXIMETER_WRIST BLE_APPEARANCE_PULSE_OXIMETER_WRIST
#define BTM_BLE_APPEARANCE_GENERIC_WEIGHT BLE_APPEARANCE_GENERIC_WEIGHT
#define BTM_BLE_APPEARANCE_GENERIC_OUTDOOR_SPORTS \
                BLE_APPEARANCE_GENERIC_OUTDOOR_SPORTS
#define BTM_BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION \
                BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION
#define BTM_BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_AND_NAV \
                BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_AND_NAV
#define BTM_BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD \
                BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD
#define BTM_BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD_AND_NAV \
                BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD_AND_NAV

/* ADV data flag bit definition used for BTM_BLE_AD_TYPE_FLAG */
#define BTM_BLE_LIMIT_DISC_FLAG (0x01 << 0)
#define BTM_BLE_GEN_DISC_FLAG (0x01 << 1)
#define BTM_BLE_BREDR_NOT_SPT (0x01 << 2)
/* 4.1 spec adv flag for simultaneous BR/EDR+LE connection support */
#define BTM_BLE_DMT_CONTROLLER_SPT (0x01 << 3)
#define BTM_BLE_DMT_HOST_SPT (0x01 << 4)

// TODO(jpawlowski): this should be removed with code that depend on it.
#define BTM_BLE_AD_BIT_FLAGS (0x00000001 << 1)

#define BTM_BLE_AD_TYPE_FLAG HCI_EIR_FLAGS_TYPE /* 0x01 */
#define BTM_BLE_AD_TYPE_16SRV_CMPL                                          \
  HCI_EIR_COMPLETE_16BITS_UUID_TYPE                                 /* 0x03 \
                                                                     */
#define BTM_BLE_AD_TYPE_SERVICE_DATA_TYPE HCI_EIR_SERVICE_DATA_TYPE /* 0x16 */
#define BTM_BLE_AD_TYPE_APPEARANCE 0x19
#define BTM_BLE_AD_TYPE_RSI HCI_EIR_RSI_TYPE /* 0x2E */
#define BTM_BLE_AD_TYPE_BROADCAST_NAME 0x30

/*  Min/max Preferred  number of payload octets that the local Controller
    should include in a single Link Layer Data Channel PDU. */
#define BTM_BLE_DATA_SIZE_MAX 0x00fb
#define BTM_BLE_DATA_SIZE_MIN 0x001b

/*  Preferred maximum number of microseconds that the local Controller
    should use to transmit a single Link Layer Data Channel PDU. */
#define BTM_BLE_DATA_TX_TIME_MAX_LEGACY 0x0848
#define BTM_BLE_DATA_TX_TIME_MAX 0x4290

/* adv tx power in dBm */
typedef struct {
  uint8_t adv_inst_max; /* max adv instance supported in controller */
  uint8_t rpa_offloading;
  uint16_t tot_scan_results_strg;
  uint8_t max_irk_list_sz;
  uint8_t filter_support;
  uint8_t max_filter;
  uint8_t energy_support;
  bool values_read;
  uint16_t version_supported;
  uint16_t total_trackable_advertisers;
  uint8_t extended_scan_support;
  uint8_t debug_logging_supported;
  uint8_t le_address_generation_offloading_support;
  uint32_t a2dp_source_offload_capability_mask;
  uint8_t quality_report_support;
  uint32_t dynamic_audio_buffer_support;
  uint16_t adv_filter_extended_features_mask;
  uint8_t a2dp_offload_v2_support;
  uint16_t big_set_channel_map_classification_support;
} tBTM_BLE_VSC_CB;

/* Stored the default/maximum/minimum buffer time for dynamic audio buffer.
 * For A2DP offload usage, the unit is millisecond.
 * For A2DP legacy usage, the unit is buffer queue size*/
typedef struct {
  uint16_t default_buffer_time;
  uint16_t maximum_buffer_time;
  uint16_t minimum_buffer_time;
} tBTM_BT_DYNAMIC_AUDIO_BUFFER_CB;

typedef void(tBTM_BLE_ADV_DATA_CMPL_CBACK)(tBTM_STATUS status);

#ifndef BTM_BLE_MULTI_ADV_MAX
#define BTM_BLE_MULTI_ADV_MAX                           \
  16 /* controller returned adv_inst_max should be less \
        than this number */
#endif

typedef uint16_t tCONN_ID;
typedef uint8_t tGATT_IF;
typedef uint8_t tTCB_IDX;

/* connection manager doesn't generate its own IDs. Instead, all GATT clients
 * use their gatt_if to identify against connection manager. When stack tries to
 * create l2cap connection, it will use this fixed ID. */
inline constexpr tGATT_IF CONN_MGR_ID_L2CAP = static_cast<tGATT_IF>(0xf9);

typedef enum : uint8_t {
  BTM_BLE_DIRECT_CONNECTION = 0x00,
  BTM_BLE_BKG_CONNECT_ALLOW_LIST = 0x01,
  BTM_BLE_BKG_CONNECT_TARGETED_ANNOUNCEMENTS = 0x02,
  BTM_BLE_OPPORTUNISTIC = 0x03,
} tBTM_BLE_CONN_TYPE;

typedef void(tBTM_BLE_SCAN_THRESHOLD_CBACK)(tBTM_BLE_REF_VALUE ref_value);

#ifndef BTM_BLE_BATCH_SCAN_MAX
#define BTM_BLE_BATCH_SCAN_MAX 5
#endif

#ifndef BTM_BLE_BATCH_REP_MAIN_Q_SIZE
#define BTM_BLE_BATCH_REP_MAIN_Q_SIZE 2
#endif

typedef enum {
  BTM_BLE_SCAN_INVALID_STATE = 0,
  BTM_BLE_SCAN_ENABLE_CALLED = 1,
  BTM_BLE_SCAN_ENABLED_STATE = 2,
  BTM_BLE_SCAN_DISABLE_CALLED = 3,
  BTM_BLE_SCAN_DISABLED_STATE = 4
} tBTM_BLE_BATCH_SCAN_STATE;

enum { BTM_BLE_DISCARD_OLD_ITEMS, BTM_BLE_DISCARD_LOWER_RSSI_ITEMS };
typedef uint8_t tBTM_BLE_DISCARD_RULE;

typedef struct {
  tBTM_BLE_BATCH_SCAN_STATE cur_state;
  tBTM_BLE_BATCH_SCAN_MODE scan_mode;
  uint32_t scan_interval;
  uint32_t scan_window;
  tBLE_ADDR_TYPE addr_type;
  tBTM_BLE_DISCARD_RULE discard_rule;
  tBTM_BLE_SCAN_THRESHOLD_CBACK* p_thres_cback;
  tBTM_BLE_REF_VALUE ref_value;
} tBTM_BLE_BATCH_SCAN_CB;

/* filter selection bit index  */
#define BTM_BLE_PF_ADDR_FILTER 0
#define BTM_BLE_PF_SRVC_DATA 1
#define BTM_BLE_PF_SRVC_UUID 2
#define BTM_BLE_PF_SRVC_SOL_UUID 3
#define BTM_BLE_PF_LOCAL_NAME 4
#define BTM_BLE_PF_MANU_DATA 5
#define BTM_BLE_PF_SRVC_DATA_PATTERN 6
/* when passed in payload filter type all, only clear action is applicable */
#define BTM_BLE_PF_TYPE_ALL 7
#define BTM_BLE_PF_TYPE_MAX 8

/* max number of filter spot for different filter type */
#ifndef BTM_BLE_MAX_UUID_FILTER
#define BTM_BLE_MAX_UUID_FILTER 8
#endif
#ifndef BTM_BLE_MAX_ADDR_FILTER
#define BTM_BLE_MAX_ADDR_FILTER 8
#endif
#ifndef BTM_BLE_PF_STR_COND_MAX
#define BTM_BLE_PF_STR_COND_MAX 4 /* apply to manu data , or local name */
#endif
#ifndef BTM_BLE_PF_STR_LEN_MAX
#define BTM_BLE_PF_STR_LEN_MAX 29 /* match for first 29 bytes */
#endif

typedef uint8_t tBTM_BLE_PF_COND_TYPE;

#define BTM_BLE_PF_LOGIC_OR 0
#define BTM_BLE_PF_LOGIC_AND 1
typedef uint8_t tBTM_BLE_PF_LOGIC_TYPE;

#define BTM_BLE_PF_ENABLE 1
#define BTM_BLE_PF_CONFIG 2

typedef uint8_t tBTM_BLE_PF_FILT_INDEX;

enum { BTM_BLE_SCAN_COND_ADD, BTM_BLE_SCAN_COND_DELETE, BTM_BLE_SCAN_COND_CLEAR = 2 };
typedef uint8_t tBTM_BLE_SCAN_COND_OP;

/* BLE adv payload filtering config complete callback */
using tBTM_BLE_PF_CFG_CBACK =
        base::OnceCallback<void(uint8_t /* avbl_space */, tBTM_BLE_SCAN_COND_OP /* action */,
                                tBTM_STATUS /* btm_status */)>;

/* BLE adv payload filtering param setup complete callback */
using tBTM_BLE_PF_PARAM_CB =
        base::OnceCallback<void(uint8_t /* avbl_space */, tBTM_BLE_SCAN_COND_OP /* action */,
                                tBTM_STATUS /* btm_status */)>;

#ifndef BTM_CS_IRK_LIST_MAX
#define BTM_CS_IRK_LIST_MAX 0x20
#endif

typedef struct {
  bool in_use;
  RawAddress bd_addr;
  uint8_t pf_counter[BTM_BLE_PF_TYPE_MAX]; /* number of filter indexed by
                                              tBTM_BLE_PF_COND_TYPE */
} tBTM_BLE_PF_COUNT;

typedef struct {
  bool enable;
  uint8_t op_type;
  tBTM_BLE_PF_COUNT* p_addr_filter_count; /* per BDA filter array */
  tBLE_BD_ADDR cur_filter_target;
} tBTM_BLE_ADV_FILTER_CB;

/* Sub codes */
#define BTM_BLE_META_PF_ENABLE 0x00
#define BTM_BLE_META_PF_FEAT_SEL 0x01
#define BTM_BLE_META_PF_ADDR 0x02
#define BTM_BLE_META_PF_UUID 0x03
#define BTM_BLE_META_PF_SOL_UUID 0x04
#define BTM_BLE_META_PF_LOCAL_NAME 0x05
#define BTM_BLE_META_PF_MANU_DATA 0x06
#define BTM_BLE_META_PF_SRVC_DATA 0x07
#define BTM_BLE_META_PF_ALL 0x08

#define ADV_INFO_PRESENT 0x00
#define NO_ADV_INFO_PRESENT 0x01

typedef btgatt_track_adv_info_t tBTM_BLE_TRACK_ADV_DATA;

typedef void(tBTM_BLE_TRACK_ADV_CBACK)(tBTM_BLE_TRACK_ADV_DATA* p_track_adv_data);

typedef struct {
  tBTM_BLE_REF_VALUE ref_value;
  tBTM_BLE_TRACK_ADV_CBACK* p_track_cback;
} tBTM_BLE_ADV_TRACK_CB;

typedef uint32_t tBTM_BLE_TX_TIME_MS;
typedef uint32_t tBTM_BLE_RX_TIME_MS;
typedef uint32_t tBTM_BLE_IDLE_TIME_MS;
typedef uint32_t tBTM_BLE_ENERGY_USED;

typedef void(tBTM_BLE_ENERGY_INFO_CBACK)(tBTM_BLE_TX_TIME_MS tx_time, tBTM_BLE_RX_TIME_MS rx_time,
                                         tBTM_BLE_IDLE_TIME_MS idle_time,
                                         tBTM_BLE_ENERGY_USED energy_used, tHCI_STATUS status);

typedef struct {
  tBTM_BLE_ENERGY_INFO_CBACK* p_ener_cback;
} tBTM_BLE_ENERGY_INFO_CB;

typedef void(tBTM_BLE_CTRL_FEATURES_CBACK)(tHCI_STATUS status);

typedef struct {
  RawAddress addr;
  tBLE_ADDR_TYPE addr_type;
  tBT_DEVICE_TYPE device_type;
} DevInfo;

static inline std::string DeviceInfoText(const DevInfo& dev_info) {
  return std::format("{}({}) Device type: {})", dev_info.addr.ToRedactedStringForLogging(),
                     AddressTypeText(dev_info.addr_type), DeviceTypeText(dev_info.device_type));
}

namespace std {
template <>
struct formatter<tBTM_BLE_CONN_TYPE> : enum_formatter<tBTM_BLE_CONN_TYPE> {};
template <>
struct formatter<DevInfo> : string_formatter<DevInfo, &DeviceInfoText> {};
}  // namespace std

#endif  // BTM_BLE_API_TYPES_H
