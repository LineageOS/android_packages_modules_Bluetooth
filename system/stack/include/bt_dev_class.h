/*
 * Copyright 2021 The Android Open Source Project
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

#include <array>
#include <cstddef>
#include <cstdint>
#include <sstream>

constexpr size_t kDevClassLength = 3;
typedef std::array<uint8_t, kDevClassLength> DEV_CLASS; /* Device class */

/* major class mask */
#define PHONE_COD_MAJOR_CLASS_MASK 0x1F00

/***************************
 * major device class field
 * Note: All values are deduced by basing BIT_X to BIT_8, values as per
 *  BT-spec assigned-numbers.
 ***************************/
#define COD_MAJOR_MISC 0x00
#define COD_MAJOR_COMPUTER 0x01         // BIT8
#define COD_MAJOR_PHONE 0x02        // BIT9
#define COD_MAJOR_LAN_NAP 0x03      // BIT8 | BIT9
#define COD_MAJOR_AUDIO 0x04        // BIT10
#define COD_MAJOR_PERIPHERAL 0x05   // BIT8 | BIT10
#define COD_MAJOR_IMAGING 0x06      // BIT9 | BIT10
#define COD_MAJOR_WEARABLE 0x07     // BIT8 | BIT9 | BIT10
#define COD_MAJOR_TOY 0x08          // BIT11
#define COD_MAJOR_HEALTH 0x09       // BIT8 | BIT11
#define COD_MAJOR_UNCLASSIFIED 0x1F // BIT8 | BIT9 | BIT10 | BIT11 | BIT12

/***************************
 * service class fields
 * Note: All values are deduced by basing BIT_X to BIT_8, values as per
 *  BT-spec assigned-numbers.
 ***************************/
#define COD_SERVICE_NA 0x0000
#define COD_SERVICE_LMTD_DISCOVER 0x0020  // BIT13 (eg. 13-8 = BIT5 = 0x0020)
#define COD_SERVICE_LE_AUDIO 0x0040       // BIT14
#define COD_SERVICE_POSITIONING 0x0100    // BIT16
#define COD_SERVICE_NETWORKING 0x0200     // BIT17
#define COD_SERVICE_RENDERING 0x0400      // BIT18
#define COD_SERVICE_CAPTURING 0x0800      // BIT19
#define COD_SERVICE_OBJ_TRANSFER 0x1000   // BIT20
#define COD_SERVICE_AUDIO 0x2000          // BIT21
#define COD_SERVICE_TELEPHONY 0x4000      // BIT22
#define COD_SERVICE_INFORMATION 0x8000    // BIT23

/***************************
 * minor device class field
 * Note: LSB[1:0] (2 bits) is don't care for minor device class.
 ***************************/
/* Minor Device class field - Computer Major Class (COD_MAJOR_COMPUTER) */
#define COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED 0x00
#define COD_MAJOR_COMPUTER_MINOR_DESKTOP_WORKSTATION 0x04    // BIT2
#define COD_MAJOR_COMPUTER_MINOR_SERVER_CLASS_COMPUTER 0x08  // BIT3
#define COD_MAJOR_COMPUTER_MINOR_LAPTOP 0x0C                 // BIT2 | BIT3
#define COD_MAJOR_COMPUTER_MINOR_HANDHELD_PC_PDA 0x10        // BIT4
#define COD_MAJOR_COMPUTER_MINOR_PALM_SIZE_PC_PDA 0x14       // BIT2 | BIT4
#define COD_MAJOR_COMPUTER_MINOR_WEARABLE_COMPUTER_WATCH_SIZE 0x18 // BIT3 | BIT4
#define COD_MAJOR_COMPUTER_MINOR_TABLET 0x1C         // BIT2 | BIT3 | BIT4

/* Minor Device class field - Phone Major Class (COD_MAJOR_PHONE) */
#define COD_MAJOR_PHONE_MINOR_UNCATEGORIZED 0x00
#define COD_MAJOR_PHONE_MINOR_CELLULAR 0x04  // BIT2
#define COD_MAJOR_PHONE_MINOR_CORDLESS 0x08  // BIT3
#define COD_MAJOR_PHONE_MINOR_SMARTPHONE 0x0C  // BIT2 | BIT3
#define COD_MAJOR_PHONE_MINOR_WIRED_MODEM_OR_VOICE_GATEWAY 0x10  // BIT4
#define COD_MAJOR_PHONE_MINOR_COMMON_ISDN_ACCESS 0x14  // BIT2 | BIT4

/*
 * Minor Device class field -
 * LAN/Network Access Point Major Class (COD_MAJOR_LAN_NAP)
 */
#define COD_MAJOR_LAN_NAP_MINOR_FULLY_AVAILABLE 0x00
#define COD_MAJOR_LAN_NAP_MINOR_1_TO_17_PER_UTILIZED 0x20 // BIT5
#define COD_MAJOR_LAN_NAP_MINOR_17_TO_33_PER_UTILIZED 0x40 // BIT6
#define COD_MAJOR_LAN_NAP_MINOR_33_TO_50_PER_UTILIZED 0x60 // BIT5 | BIT6
#define COD_MAJOR_LAN_NAP_MINOR_50_TO_67_PER_UTILIZED 0x80 // BIT7
#define COD_MAJOR_LAN_NAP_MINOR_67_TO_83_PER_UTILIZED 0xA0 // BIT5 | BIT7
#define COD_MAJOR_LAN_NAP_MINOR_83_TO_99_PER_UTILIZED 0xC0 // BIT6 | BIT7
#define COD_MAJOR_LAN_NAP_MINOR_NO_SERVICE_AVAILABLE 0xE0 // BIT5 | BIT6 | BIT7

/* Minor Device class field - Audio/Video Major Class (COD_MAJOR_AUDIO) */
/* 0x00 is used as unclassified for all minor device classes */
#define COD_MINOR_UNCATEGORIZED 0x00
#define COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET 0x04 // BIT2
#define COD_MAJOR_AUDIO_MINOR_CONFM_HANDSFREE 0x08 // BIT3
#define COD_MAJOR_AUDIO_MINOR_MICROPHONE 0x10 // BIT4
#define COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER 0x14 // BIT2 | BIT4
#define COD_MAJOR_AUDIO_MINOR_HEADPHONES 0x18 // BIT3 | BIT4
#define COD_MAJOR_AUDIO_MINOR_PORTABLE_AUDIO 0x1C // BIT2 | BIT3 | BIT4
#define COD_MAJOR_AUDIO_MINOR_CAR_AUDIO 0x20 // BIT5
#define COD_MAJOR_AUDIO_MINOR_SET_TOP_BOX 0x24 // BIT2 | BIT5
#define COD_MAJOR_AUDIO_MINOR_HIFI_AUDIO 0x28 // BIT3 | BIT5
#define COD_MAJOR_AUDIO_MINOR_VCR 0x2C // BIT2 | BIT3 | BIT5
#define COD_MAJOR_AUDIO_MINOR_VIDEO_CAMERA 0x30 // BIT4 | BIT5
#define COD_MAJOR_AUDIO_MINOR_CAMCORDER 0x34 // BIT2 | BIT4 | BIT5
#define COD_MAJOR_AUDIO_MINOR_VIDEO_MONITOR 0x38 // BIT3 | BIT4 | BIT5
#define COD_MAJOR_AUDIO_MINOR_VIDEO_DISPLAY_AND_LOUDSPEAKER 0x3C // BIT2 |
                                                        // BIT3 | BIT4 | BIT5
#define COD_MAJOR_AUDIO_MINOR_VIDEO_CONFERENCING 0x40 // BIT6
#define COD_MAJOR_AUDIO_MINOR_GAMING_OR_TOY 0x48 // BIT3 | BIT6

/* Minor Device class field - Peripheral Major Class (COD_MAJOR_PERIPHERAL) */
/* Bits 6-7 independently specify mouse, keyboard, or combo mouse/keyboard */
#define COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED 0x00
#define COD_MAJOR_PERIPH_MINOR_KEYBOARD 0x40 // BIT6
#define COD_MAJOR_PERIPH_MINOR_POINTING 0x80 // BIT7
#define COD_MAJOR_PERIPH_MINOR_KEYBOARD_AND_POINTING_DEVICE 0xC0 // BIT6 | BIT7

/* Bits 2-5 OR'd with selection from bits 6-7 */
#define COD_MAJOR_PERIPH_MINOR_JOYSTICK 0x04  // BIT2
#define COD_MAJOR_PERIPH_MINOR_GAMEPAD 0x08   // BIT3
#define COD_MAJOR_PERIPH_MINOR_REMOTE_CONTROL 0x0C      // BIT2 | BIT3
#define COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE 0x10      // BIT4
#define COD_MAJOR_PERIPH_MINOR_DIGITIZING_TABLET 0x14   // BIT2 | BIT4
#define COD_MAJOR_PERIPH_MINOR_CARD_READER 0x18 /* e.g. SIM card reader, BIT3 | BIT4 */
#define COD_MAJOR_PERIPH_MINOR_DIGITAL_PEN 0x1C // Pen, BIT2 | BIT3 | BIT4
#define COD_MAJOR_PERIPH_MINOR_HANDHELD_SCANNER 0x20 // e.g. Barcode, RFID, BIT5
#define COD_MAJOR_PERIPH_MINOR_HANDHELD_GESTURAL_INP_DEVICE 0x24
                                        // e.g. "wand" form factor, BIT2 | BIT5

/* Minor Device class field - Imaging Major Class (COD_MAJOR_IMAGING)
 *
 * Bits 5-7 independently specify display, camera, scanner, or printer
 *  Note: Apart from the set bit, all other bits are don't care.
 */
#define COD_MAJOR_IMAGING_MINOR_DISPLAY 0x10  // BIT4
#define COD_MAJOR_IMAGING_MINOR_CAMERA 0x20   // BIT5
#define COD_MAJOR_IMAGING_MINOR_SCANNER 0x40  // BIT6
#define COD_MAJOR_IMAGING_MINOR_PRINTER 0x80  // BIT7

/* Minor Device class field - Wearable Major Class (COD_MAJOR_WEARABLE) */
#define COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH 0x04 // BIT2
#define COD_MAJOR_WEARABLE_MINOR_PAGER 0x08   // BIT3
#define COD_MJAOR_WEARABLE_MINOR_JACKET 0x0C  // BIT2 | BIT3
#define COD_MAJOR_WEARABLE_MINOR_HELMET 0x10  // BIT4
#define COD_MAJOR_WEARABLE_MINOR_GLASSES 0x14 // BIT2 | BIT4
#define COD_MAJOR_WEARABLE_MINOR_PIN 0x18
                                    // e.g. Label pin, broach, badge BIT3 | BIT4

/* Minor Device class field - Toy Major Class (COD_MAJOR_TOY) */
#define COD_MAJOR_TOY_MINOR_ROBOT 0x04 // BIT2
#define COD_MAJOR_TOY_MINOR_VEHICLE 0x08 // BIT3
#define COD_MAJOR_TOY_MINOR_DOLL_OR_ACTION_FIGURE 0x0C // BIT2 | BIT3
#define COD_MAJOR_TOY_MINOR_CONTROLLER 0x10 // BIT4
#define COD_MAJOR_TOY_MINOR_GAME 0x14 // BIT2 | BIT4

/* Minor Device class field - Health Major Class (COD_MAJOR_HEALTH) */
#define COD_MAJOR_HEALTH_MINOR_BLOOD_MONITOR 0x04 // Blood pressure monitor, BIT2
#define COD_MAJOR_HEALTH_MINOR_THERMOMETER 0x08   // BIT3
#define COD_MAJOR_HEALTH_MINOR_WEIGHING_SCALE 0x0C // BIT2 | BIT3
#define COD_MAJOR_HEALTH_MINOR_GLUCOSE_METER 0x10 // BIT4
#define COD_MAJOR_HEALTH_MINOR_PULSE_OXIMETER 0x14 // BIT2 | BIT4
#define COD_MAJOR_HEALTH_MINOR_HEART_PULSE_MONITOR 0x18 // BIT3 | BIT4
#define COD_MAJOR_HEALTH_MINOR_HEALTH_DATA_DISPLAY 0x1C // BIT2 | BIT3 | BIT4
#define COD_MAJOR_HEALTH_MINOR_STEP_COUNTER 0x20 // BIT5
#define COD_MAJOR_HEALTH_MINOR_BODY_COMPOSITION_ANALYZER 0x24 // BIT2 | BIT5
#define COD_MAJOR_HEALTH_MINOR_PEAK_FLOW_MONITOR 0x28 // BIT3 | BIT5
#define COD_MAJOR_HEALTH_MINOR_MEDICATION_MONITOR 0x2C // BIT2 | BIT3 | BIT5
#define COD_MAJOR_HEALTH_MINOR_KNEE_PROSTHESIS 0x30 // BIT4 | BIT5
#define COD_MAJOR_HEALTH_MINOR_ANKLE_PROSTHESIS 0x34 // BIT3 | BIT4 | BIT5
#define COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER 0x38 // BIT2 | BIT3 | BIT4 | BIT5
#define COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE 0x3C // BIT4 | BIT5

/* 0x00 is used as unclassified for all minor device classes */
#define BTM_COD_MINOR_UNCLASSIFIED COD_MINOR_UNCATEGORIZED
#define BTM_COD_MINOR_WEARABLE_HEADSET COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET
#define BTM_COD_MINOR_CONFM_HANDSFREE COD_MAJOR_AUDIO_MINOR_CONFM_HANDSFREE
#define BTM_COD_MINOR_CAR_AUDIO COD_MAJOR_AUDIO_MINOR_CAR_AUDIO
#define BTM_COD_MINOR_SET_TOP_BOX COD_MAJOR_AUDIO_MINOR_SET_TOP_BOX

/* minor device class field for Peripheral Major Class */
/* Bits 6-7 independently specify mouse, keyboard, or combo mouse/keyboard */
#define BTM_COD_MINOR_KEYBOARD COD_MAJOR_PERIPH_MINOR_KEYBOARD
#define BTM_COD_MINOR_POINTING COD_MAJOR_PERIPH_MINOR_POINTING
/* Bits 2-5 OR'd with selection from bits 6-7 */
/* #define BTM_COD_MINOR_UNCLASSIFIED       0x00    */
#define BTM_COD_MINOR_JOYSTICK COD_MAJOR_PERIPH_MINOR_JOYSTICK
#define BTM_COD_MINOR_GAMEPAD COD_MAJOR_PERIPH_MINOR_GAMEPAD
#define BTM_COD_MINOR_REMOTE_CONTROL COD_MAJOR_PERIPH_MINOR_REMOTE_CONTROL
#define BTM_COD_MINOR_DIGITIZING_TABLET COD_MAJOR_PERIPH_MINOR_DIGITIZING_TABLET
#define BTM_COD_MINOR_CARD_READER COD_MAJOR_PERIPH_MINOR_CARD_READER
#define BTM_COD_MINOR_DIGITAL_PAN COD_MAJOR_PERIPH_MINOR_DIGITAL_PEN

/* minor device class field for Imaging Major Class */
/* Bits 5-7 independently specify display, camera, scanner, or printer */
#define BTM_COD_MINOR_DISPLAY COD_MAJOR_IMAGING_MINOR_DISPLAY
/* Bits 2-3 Reserved */
/* #define BTM_COD_MINOR_UNCLASSIFIED       0x00    */

/* minor device class field for Wearable Major Class */
/* Bits 2-7 meaningful    */
#define BTM_COD_MINOR_WRIST_WATCH COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH
#define BTM_COD_MINOR_GLASSES COD_MAJOR_WEARABLE_MINOR_GLASSES

/* minor device class field for Health Major Class */
/* Bits 2-7 meaningful    */
#define BTM_COD_MINOR_BLOOD_MONITOR COD_MAJOR_HEALTH_MINOR_BLOOD_MONITOR
#define BTM_COD_MINOR_THERMOMETER COD_MAJOR_HEALTH_MINOR_THERMOMETER
#define BTM_COD_MINOR_WEIGHING_SCALE COD_MAJOR_HEALTH_MINOR_WEIGHING_SCALE
#define BTM_COD_MINOR_GLUCOSE_METER COD_MAJOR_HEALTH_MINOR_GLUCOSE_METER
#define BTM_COD_MINOR_PULSE_OXIMETER COD_MAJOR_HEALTH_MINOR_PULSE_OXIMETER
#define BTM_COD_MINOR_HEART_PULSE_MONITOR COD_MAJOR_HEALTH_MINOR_HEART_PULSE_MONITOR
#define BTM_COD_MINOR_STEP_COUNTER COD_MAJOR_HEALTH_MINOR_STEP_COUNTER

/***************************
 * major device class field
 ***************************/
#define BTM_COD_MAJOR_COMPUTER COD_MAJOR_COMPUTER
#define BTM_COD_MAJOR_PHONE COD_MAJOR_PHONE
#define BTM_COD_MAJOR_AUDIO COD_MAJOR_AUDIO
#define BTM_COD_MAJOR_PERIPHERAL COD_MAJOR_PERIPHERAL
#define BTM_COD_MAJOR_IMAGING COD_MAJOR_IMAGING
#define BTM_COD_MAJOR_WEARABLE COD_MAJOR_WEARABLE
#define BTM_COD_MAJOR_HEALTH COD_MAJOR_HEALTH
#define BTM_COD_MAJOR_UNCLASSIFIED COD_MAJOR_UNCLASSIFIED

/***************************
 * service class fields
 ***************************/
#define BTM_COD_SERVICE_LMTD_DISCOVER COD_SERVICE_LMTD_DISCOVER
#define BTM_COD_SERVICE_LE_AUDIO COD_SERVICE_LE_AUDIO
#define BTM_COD_SERVICE_POSITIONING COD_SERVICE_POSITIONING
#define BTM_COD_SERVICE_NETWORKING COD_SERVICE_NETWORKING
#define BTM_COD_SERVICE_RENDERING COD_SERVICE_RENDERING
#define BTM_COD_SERVICE_CAPTURING COD_SERVICE_CAPTURING
#define BTM_COD_SERVICE_OBJ_TRANSFER COD_SERVICE_OBJ_TRANSFER
#define BTM_COD_SERVICE_AUDIO COD_SERVICE_AUDIO
#define BTM_COD_SERVICE_TELEPHONY COD_SERVICE_TELEPHONY
#define BTM_COD_SERVICE_INFORMATION COD_SERVICE_INFORMATION

/* the COD masks */
#define BTM_COD_MINOR_CLASS_MASK 0xFC
#define BTM_COD_MAJOR_CLASS_MASK 0x1F
#define BTM_COD_SERVICE_CLASS_LO_B 0x00E0
#define BTM_COD_SERVICE_CLASS_MASK 0xFFE0

inline constexpr DEV_CLASS kDevClassEmpty = {COD_SERVICE_NA, COD_MAJOR_MISC,
                                             COD_MINOR_UNCATEGORIZED};
inline constexpr DEV_CLASS kDevClassUnclassified = {0x00, BTM_COD_MAJOR_UNCLASSIFIED,
                                                    BTM_COD_MINOR_UNCLASSIFIED};

/* class of device field macros */
#define BTM_COD_MINOR_CLASS(u8, pd) \
  { (u8) = (pd)[2] & BTM_COD_MINOR_CLASS_MASK; }
#define BTM_COD_MAJOR_CLASS(u8, pd) \
  { (u8) = (pd)[1] & BTM_COD_MAJOR_CLASS_MASK; }
#define BTM_COD_SERVICE_CLASS(u16, pd) \
  {                                    \
    (u16) = (pd)[0];                   \
    (u16) <<= 8;                       \
    (u16) += (pd)[1] & 0xE0;           \
  }

/* to set the fields (assumes that format type is always 0) */
#define FIELDS_TO_COD(pd, mn, mj, sv)                     \
  {                                                       \
    (pd)[2] = mn;                                         \
    (pd)[1] = (mj) + ((sv) & BTM_COD_SERVICE_CLASS_LO_B); \
    (pd)[0] = (sv) >> 8;                                  \
  }

inline std::string dev_class_text(const DEV_CLASS& dev_class) {
  std::ostringstream oss;
  uint16_t sv;
  uint8_t mj, mn;
  BTM_COD_SERVICE_CLASS(sv, dev_class);
  BTM_COD_MAJOR_CLASS(mj, dev_class);
  BTM_COD_MINOR_CLASS(mn, dev_class);
  oss << std::hex << (int)sv << "-" << (int)mj << "-" << (int)mn;
  return oss.str();
}

#define DEVCLASS_TO_STREAM(p, a)                \
  {                                             \
    size_t ijk;                                 \
    for (ijk = 0; ijk < kDevClassLength; ijk++) \
      *(p)++ = (a)[kDevClassLength - 1 - ijk];  \
  }

#define STREAM_TO_DEVCLASS(a, p)                   \
  {                                                \
    size_t ijk;                                    \
    uint8_t* _pa = a.data() + kDevClassLength - 1; \
    for (ijk = 0; ijk < kDevClassLength; ijk++)    \
      *_pa-- = *(p)++;                             \
  }
