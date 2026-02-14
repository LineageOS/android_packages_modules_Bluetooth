/******************************************************************************
 *
 *  Copyright (C) 2025 The Android Open Source Project
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

#ifndef BLE_APPEARANCE_H
#define BLE_APPEARANCE_H

#include <cstdint>

#include "stack/include/bt_dev_class.h"

/**
 * BLE appearance values as per BT spec assigned numbers.
 * Definitions and mapping from BLE appearance to COD.
 * The set represents:
 * - BLE Appearance string
 * - BLE Appearance value
 * - Class of device Service
 * - COD Major Class
 * - COD Minor Class
 *
 * Note: To add mapping for a new BLE appearance value for a category, add a
 *  new macro to the appropriate APPEARANCE_TO_COD_XXXX macro, and then
 *  add this APPEARANCE_TO_COD_XXXX macro to the APPEARANCE_TO_COD macro
 *  (if not already added).
 */

/* Category Unknown [15:6] 0x000 */
#define APPEARANCE_TO_COD_UNKNOWN(X) \
  X(BLE_APPEARANCE_UNKNOWN, 0x0000, COD_SERVICE_NA, COD_MAJOR_UNCLASSIFIED, COD_MINOR_UNCATEGORIZED)

/* Category Phone [15:6] 0x001 */
#define APPEARANCE_TO_COD_PHONE(X)                                         \
  X(BLE_APPEARANCE_GENERIC_PHONE, 0x0040, COD_SERVICE_NA, COD_MAJOR_PHONE, \
    COD_MAJOR_PHONE_MINOR_UNCATEGORIZED)

/* Category Computer [15:6] 0x002 */
#define APPEARANCE_TO_COD_COMPUTER(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_COMPUTER, 0x0080, COD_SERVICE_NA, COD_MAJOR_COMPUTER,             \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_DESKTOP_WORKSTATION, 0x0081, COD_SERVICE_NA, COD_MAJOR_COMPUTER,          \
    COD_MAJOR_COMPUTER_MINOR_DESKTOP_WORKSTATION)                                            \
  X(BLE_APPEARANCE_SERVER_CLASS_COMPUTER, 0x0082, COD_SERVICE_NA, COD_MAJOR_COMPUTER,        \
    COD_MAJOR_COMPUTER_MINOR_SERVER_CLASS_COMPUTER)                                          \
  X(BLE_APPEARANCE_LAPTOP, 0x0083, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                       \
    COD_MAJOR_COMPUTER_MINOR_LAPTOP)                                                         \
  X(BLE_APPEARANCE_HANDHELD_PC_PDA, 0x0084, COD_SERVICE_NA, COD_MAJOR_COMPUTER,              \
    COD_MAJOR_COMPUTER_MINOR_HANDHELD_PC_PDA)                                                \
  X(BLE_APPEARANCE_PALM_SIZE_PC_PDA, 0x0085, COD_SERVICE_NA, COD_MAJOR_COMPUTER,             \
    COD_MAJOR_COMPUTER_MINOR_PALM_SIZE_PC_PDA)                                               \
  X(BLE_APPEARANCE_WEARABLE_COMPUTER_WATCH_SIZE, 0x0086, COD_SERVICE_NA, COD_MAJOR_COMPUTER, \
    COD_MAJOR_COMPUTER_MINOR_WEARABLE_COMPUTER_WATCH_SIZE)                                   \
  X(BLE_APPEARANCE_TABLET, 0x0087, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                       \
    COD_MAJOR_COMPUTER_MINOR_TABLET)                                                         \
  X(BLE_APPEARANCE_DOCKING_STATION, 0x0088, COD_SERVICE_NA, COD_MAJOR_COMPUTER,              \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_ALL_IN_ONE, 0x0089, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                   \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED) /* Or Desktop Workstation */                     \
  X(BLE_APPEARANCE_BLADE_SERVER, 0x008A, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                 \
    COD_MAJOR_COMPUTER_MINOR_SERVER_CLASS_COMPUTER)                                          \
  X(BLE_APPEARANCE_CONVERTIBLE, 0x008B, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                  \
    COD_MAJOR_COMPUTER_MINOR_LAPTOP) /* Or Tablet, depending on form */                      \
  X(BLE_APPEARANCE_DETACHABLE, 0x008C, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                   \
    COD_MAJOR_COMPUTER_MINOR_TABLET) /* Or Laptop, depending on form */                      \
  X(BLE_APPEARANCE_IOT_GATEWAY, 0x008D, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                  \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_MINI_PC, 0x008E, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                      \
    COD_MAJOR_COMPUTER_MINOR_DESKTOP_WORKSTATION) /* Or Uncategorized */                     \
  X(BLE_APPEARANCE_STICK_PC, 0x008F, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                     \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED)

/* Category Watch [15:6] 0x003 */
#define APPEARANCE_TO_COD_WATCH(X)                                            \
  X(BLE_APPEARANCE_GENERIC_WATCH, 0x00C0, COD_SERVICE_NA, COD_MAJOR_WEARABLE, \
    COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)                                     \
  X(BLE_APPEARANCE_SPORTS_WATCH, 0x00C1, COD_SERVICE_NA, COD_MAJOR_WEARABLE,  \
    COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)                                     \
  X(BLE_APPEARANCE_SMART_WATCH, 0x00C2, COD_SERVICE_NA, COD_MAJOR_WEARABLE,   \
    COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)

/* Category Clock [15:6] 0x004 */
#define APPEARANCE_TO_COD_CLOCK(X)                                           \
  X(BLE_APPEARANCE_GENERIC_CLOCK, 0x0100, COD_SERVICE_NA, COD_MAJOR_IMAGING, \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)

/* Category Display [15:6] 0x005 */
#define APPEARANCE_TO_COD_DISPLAY(X)                                           \
  X(BLE_APPEARANCE_GENERIC_DISPLAY, 0x0140, COD_SERVICE_NA, COD_MAJOR_IMAGING, \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)

/* Category Remote Control [15:6] 0x006 */
#define APPEARANCE_TO_COD_REMOTE_CONTROL(X)                                      \
  X(BLE_APPEARANCE_GENERIC_REMOTE, 0x0180, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_REMOTE_CONTROL)

/* Category Eye-glasses [15:6] 0x007 */
#define APPEARANCE_TO_COD_EYEGLASSES(X)                                            \
  X(BLE_APPEARANCE_GENERIC_EYEGLASSES, 0x01C0, COD_SERVICE_NA, COD_MAJOR_WEARABLE, \
    COD_MAJOR_WEARABLE_MINOR_GLASSES)

/* Category Tag [15:6] 0x008 */
#define APPEARANCE_TO_COD_TAG(X)                                                 \
  X(BLE_APPEARANCE_GENERIC_TAG, 0x0200, COD_SERVICE_POSITIONING, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Keyring [15:6] 0x009 */
#define APPEARANCE_TO_COD_KEYRING(X)                                                 \
  X(BLE_APPEARANCE_GENERIC_KEYRING, 0x0240, COD_SERVICE_POSITIONING, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Media Player [15:6] 0x00A */
#define APPEARANCE_TO_COD_MEDIA_PLAYER(X)                                         \
  X(BLE_APPEARANCE_GENERIC_MEDIA_PLAYER, 0x0280, COD_SERVICE_NA, COD_MAJOR_AUDIO, \
    COD_MINOR_UNCATEGORIZED)

/* Category Barcode Scanner [15:6] 0x00B */
#define APPEARANCE_TO_COD_BARCODE_SCANNER(X)                                                     \
  X(BLE_APPEARANCE_GENERIC_BARCODE_SCANNER, 0x02C0, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)

/* Category Thermometer [15:6] 0x00C */
#define APPEARANCE_TO_COD_THERMOMETER(X)                                          \
  X(BLE_APPEARANCE_GENERIC_THERMOMETER, 0x0300, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_THERMOMETER)                                           \
  X(BLE_APPEARANCE_THERMOMETER_EAR, 0x0301, COD_SERVICE_NA, COD_MAJOR_HEALTH,     \
    COD_MAJOR_HEALTH_MINOR_THERMOMETER)

/* Category Heart Rate Sensor [15:6] 0x00D */
#define APPEARANCE_TO_COD_HEART_RATE_SENSOR(X)                                   \
  X(BLE_APPEARANCE_GENERIC_HEART_RATE, 0x0340, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_HEART_PULSE_MONITOR)                                  \
  X(BLE_APPEARANCE_HEART_RATE_BELT, 0x0341, COD_SERVICE_NA, COD_MAJOR_HEALTH,    \
    COD_MAJOR_HEALTH_MINOR_HEART_PULSE_MONITOR)

/* Category Blood Pressure [15:6] 0x00E */
#define APPEARANCE_TO_COD_BLOOD_PRESSURE(X)                                          \
  X(BLE_APPEARANCE_GENERIC_BLOOD_PRESSURE, 0x0380, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_BLOOD_MONITOR)                                            \
  X(BLE_APPEARANCE_BLOOD_PRESSURE_ARM, 0x0381, COD_SERVICE_NA, COD_MAJOR_HEALTH,     \
    COD_MAJOR_HEALTH_MINOR_BLOOD_MONITOR)                                            \
  X(BLE_APPEARANCE_BLOOD_PRESSURE_WRIST, 0x0382, COD_SERVICE_NA, COD_MAJOR_HEALTH,   \
    COD_MAJOR_HEALTH_MINOR_BLOOD_MONITOR)

/* Category HID [15:6] 0x00F */
#define APPEARANCE_TO_COD_HID(X)                                                          \
  X(BLE_APPEARANCE_GENERIC_HID, 0x03C0, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,             \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                 \
  X(BLE_APPEARANCE_HID_KEYBOARD, 0x03C1, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_KEYBOARD)                                                      \
  X(BLE_APPEARANCE_HID_MOUSE, 0x03C2, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,               \
    COD_MAJOR_PERIPH_MINOR_POINTING)                                                      \
  X(BLE_APPEARANCE_HID_JOYSTICK, 0x03C3, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_JOYSTICK)                                                      \
  X(BLE_APPEARANCE_HID_GAMEPAD, 0x03C4, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,             \
    COD_MAJOR_PERIPH_MINOR_GAMEPAD)                                                       \
  X(BLE_APPEARANCE_HID_DIGITIZER_TABLET, 0x03C5, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,    \
    COD_MAJOR_PERIPH_MINOR_DIGITIZING_TABLET)                                             \
  X(BLE_APPEARANCE_HID_CARD_READER, 0x03C6, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,         \
    COD_MAJOR_PERIPH_MINOR_CARD_READER)                                                   \
  X(BLE_APPEARANCE_HID_DIGITAL_PEN, 0x03C7, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,         \
    COD_MAJOR_PERIPH_MINOR_DIGITAL_PEN)                                                   \
  X(BLE_APPEARANCE_HID_BARCODE_SCANNER, 0x03C8, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,     \
    COD_MAJOR_PERIPH_MINOR_HANDHELD_GESTURAL_INP_DEVICE)                                  \
  X(BLE_APPEARANCE_HID_TOUCHPAD, 0x03C9, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_POINTING)                                                      \
  X(BLE_APPEARANCE_HID_PRESENTATION_REMOTE, 0x03CA, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_HANDHELD_GESTURAL_INP_DEVICE)

/* Category Glucose Meter [15:6] 0x010 */
#define APPEARANCE_TO_COD_GLUCOSE_METER(X)                                    \
  X(BLE_APPEARANCE_GENERIC_GLUCOSE, 0x0400, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GLUCOSE_METER)

/* Category Running Walking Sensor [15:6] 0x011 */
#define APPEARANCE_TO_COD_RUNNING_WALKING_SENSOR(X)                           \
  X(BLE_APPEARANCE_GENERIC_WALKING, 0x0440, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_STEP_COUNTER)                                      \
  X(BLE_APPEARANCE_WALKING_IN_SHOE, 0x0441, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_STEP_COUNTER)                                      \
  X(BLE_APPEARANCE_WALKING_ON_SHOE, 0x0442, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_STEP_COUNTER)                                      \
  X(BLE_APPEARANCE_WALKING_ON_HIP, 0x0443, COD_SERVICE_NA, COD_MAJOR_HEALTH,  \
    COD_MAJOR_HEALTH_MINOR_STEP_COUNTER)

/* Category Cycling [15:6] 0x012 */
#define APPEARANCE_TO_COD_CYCLING(X)                                                \
  X(BLE_APPEARANCE_GENERIC_CYCLING, 0x0480, COD_SERVICE_NA, COD_MAJOR_HEALTH,       \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                \
  X(BLE_APPEARANCE_CYCLING_COMPUTER, 0x0481, COD_SERVICE_NA, COD_MAJOR_HEALTH,      \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                \
  X(BLE_APPEARANCE_CYCLING_SPEED, 0x0482, COD_SERVICE_NA, COD_MAJOR_HEALTH,         \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                \
  X(BLE_APPEARANCE_CYCLING_CADENCE, 0x0483, COD_SERVICE_NA, COD_MAJOR_HEALTH,       \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                \
  X(BLE_APPEARANCE_CYCLING_POWER, 0x0484, COD_SERVICE_NA, COD_MAJOR_HEALTH,         \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                \
  X(BLE_APPEARANCE_CYCLING_SPEED_CADENCE, 0x0485, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)

/* Category Control Device [15:6] 0x013 */
#define APPEARANCE_TO_COD_CONTROL_DEVICE(X)                                                \
  X(BLE_APPEARANCE_GENERIC_CONTROL_DEVICE, 0x04C0, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,   \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SWITCH, 0x04C1, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,                   \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_MULTI_SWITCH, 0x04C2, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,             \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SWITCH_BUTTON, 0x04C3, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SWITCH_SLIDER, 0x04C4, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_ROTARY_SWITCH, 0x04C5, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_TOUCH_PANEL, 0x04C6, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,              \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SINGLE_SWITCH, 0x04C7, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_DOUBLE_SWITCH, 0x04C8, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_TRIPLE_SWITCH, 0x04C9, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_BATTERY_SWITCH, 0x04CA, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,           \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_ENERGY_HARVESTING_SWITCH, 0x04CB, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SWITCH_PUSH_BUTTON, 0x04CC, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,       \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)                                                  \
  X(BLE_APPEARANCE_SWITCH_DIAL, 0x04CD, COD_SERVICE_NA, COD_MAJOR_PERIPHERAL,              \
    COD_MAJOR_PERIPH_MINOR_UNCATEGORIZED)

/* Category Network Device [15:6] 0x014 */
#define APPEARANCE_TO_COD_NETWORK_DEVICE(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_NETWORK_DEVICE, 0x0500, COD_SERVICE_NETWORKING, COD_MAJOR_LAN_NAP,      \
    COD_MAJOR_LAN_NAP_MINOR_FULLY_AVAILABLE)                                                       \
  X(BLE_APPEARANCE_NETWORK_DEVICE_ACCESS_POINT, 0x0501, COD_SERVICE_NETWORKING, COD_MAJOR_LAN_NAP, \
    COD_MAJOR_LAN_NAP_MINOR_FULLY_AVAILABLE)                                                       \
  X(BLE_APPEARANCE_NETWORK_DEVICE_MESH_DEVICE, 0x0502, COD_SERVICE_NETWORKING, COD_MAJOR_LAN_NAP,  \
    COD_MAJOR_LAN_NAP_MINOR_FULLY_AVAILABLE)                                                       \
  X(BLE_APPEARANCE_NETWORK_DEVICE_MESH_NETWORK_PROXY, 0x0503, COD_SERVICE_NETWORKING,              \
    COD_MAJOR_LAN_NAP, COD_MAJOR_LAN_NAP_MINOR_FULLY_AVAILABLE)

/* Category Sensor [15:6] 0x015 */
#define APPEARANCE_TO_COD_SENSOR(X)                                                             \
  X(BLE_APPEARANCE_GENERIC_SENSOR, 0x0540, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,         \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_MOTION_SENSOR, 0x0541, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,          \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_AIR_QUALITY_SENSOR, 0x0542, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,     \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_TEMPERATURE_SENSOR, 0x0543, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,     \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_HUMIDITY_SENSOR, 0x0544, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,        \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_LEAK_SENSOR, 0x0545, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_SMOKE_SENSOR, 0x0546, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,           \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_OCCUPANCY_SENSOR, 0x0547, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,       \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_CONTACT_SENSOR, 0x0548, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,         \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_CARBON_MONOXIDE_SENSOR, 0x0549, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_CARBON_DIOXIDE_SENSOR, 0x054A, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,  \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_AMBIENT_LIGHT_SENSOR, 0x054B, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,   \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_ENERGY_SENSOR, 0x054C, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,          \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_COLOR_LIGHT_SENSOR, 0x054D, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,     \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_RAIN_SENSOR, 0x054E, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_FIRE_SENSOR, 0x054F, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_WIND_SENSOR, 0x0550, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_PROXIMITY_SENSOR, 0x0551, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,       \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_MULTI_SENSOR, 0x0552, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,           \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_FLUSH_MOUNTED_SENSOR, 0x0553, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,   \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_CEILING_MOUNTED_SENSOR, 0x0554, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL, \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_WALL_MOUNTED_SENSOR, 0x0555, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,    \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_MULTISENSOR, 0x0556, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,            \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_SENSOR_ENERGY_METER, 0x0557, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,    \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_SENSOR_FLAME_DETECTOR, 0x0558, COD_SERVICE_CAPTURING, COD_MAJOR_PERIPHERAL,  \
    COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                                      \
  X(BLE_APPEARANCE_VEHICLE_TIRE_PRESSURE_SENSOR, 0x0559, COD_SERVICE_CAPTURING,                 \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)

/* Category Light Fixtures [15:6] 0x016 */
#define APPEARANCE_TO_COD_LIGHT_FIXTURES(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_LIGHT_FIXTURE, 0x0580, COD_SERVICE_NA, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_WALL_LIGHT, 0x0581, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)    \
  X(BLE_APPEARANCE_CEILING_LIGHT, 0x0582, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_FLOOR_LIGHT, 0x0583, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)   \
  X(BLE_APPEARANCE_CABINET_LIGHT, 0x0584, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_DESK_LIGHT, 0x0585, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)    \
  X(BLE_APPEARANCE_TROFFER_LIGHT, 0x0586, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_PENDANT_LIGHT, 0x0587, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_IN_GROUND_LIGHT, 0x0588, COD_SERVICE_NA, COD_MAJOR_MISC,                        \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_FLOOD_LIGHT, 0x0589, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)   \
  X(BLE_APPEARANCE_UNDERWATER_LIGHT, 0x058A, COD_SERVICE_NA, COD_MAJOR_MISC,                       \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_BOLLARD_WITH_LIGHT, 0x058B, COD_SERVICE_NA, COD_MAJOR_MISC,                     \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_PATHWAY_LIGHT, 0x058C, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_GARDEN_LIGHT, 0x058D, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_POLE_TOP_LIGHT, 0x058E, COD_SERVICE_NA, COD_MAJOR_MISC,                         \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_SPOTLIGHT, 0x058F, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)     \
  X(BLE_APPEARANCE_LINEAR_LIGHT, 0x0590, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_STREET_LIGHT, 0x0591, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_SHELVES_LIGHT, 0x0592, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_BAY_LIGHT, 0x0593, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)     \
  X(BLE_APPEARANCE_EMERGENCY_EXIT_LIGHT, 0x0594, COD_SERVICE_NA, COD_MAJOR_MISC,                   \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_LIGHT_CONTROLLER, 0x0595, COD_SERVICE_NA, COD_MAJOR_MISC,                       \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_LIGHT_DRIVER, 0x0596, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_BULB, 0x0597, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)          \
  X(BLE_APPEARANCE_LOW_BAY_LIGHT, 0x0598, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_HIGH_BAY_LIGHT, 0x0599, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)

/* Category Fan [15:6] 0x017 */
#define APPEARANCE_TO_COD_FAN(X)                                                                  \
  X(BLE_APPEARANCE_GENERIC_FAN, 0x05C0, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_CEILING_FAN, 0x05C1, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_AXIAL_FAN, 0x05C2, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)    \
  X(BLE_APPEARANCE_EXHAUST_FAN, 0x05C3, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_PEDESTAL_FAN, 0x05C4, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_DESK_FAN, 0x05C5, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)     \
  X(BLE_APPEARANCE_WALL_FAN, 0x05C6, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)

/* Category HVAC [15:6] 0x018 */
#define APPEARANCE_TO_COD_HVAC(X)                                                                  \
  X(BLE_APPEARANCE_GENERIC_HVAC, 0x0600, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_HVAC_THERMOSTAT, 0x0601, COD_SERVICE_NA, COD_MAJOR_MISC,                        \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_HUMIDIFIER, 0x0602, COD_SERVICE_NA, COD_MAJOR_MISC,                        \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_DEHUMIDIFIER, 0x0603, COD_SERVICE_NA, COD_MAJOR_MISC,                      \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_HEATER, 0x0604, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)   \
  X(BLE_APPEARANCE_HVAC_RADIATOR, 0x0605, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_HVAC_BOILER, 0x0606, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)   \
  X(BLE_APPEARANCE_HVAC_HEAT_PUMP, 0x0607, COD_SERVICE_NA, COD_MAJOR_MISC,                         \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_INFRARED_HEATER, 0x0608, COD_SERVICE_NA, COD_MAJOR_MISC,                   \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_RADIANT_PANEL_HEATER, 0x0609, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_FAN_HEATER, 0x060A, COD_SERVICE_NA, COD_MAJOR_MISC,                        \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_HVAC_AIR_CURTAIN, 0x060B, COD_SERVICE_NA, COD_MAJOR_MISC,                       \
    COD_MINOR_UNCATEGORIZED)

/* Category Air Conditioning [15:6] 0x019 */
#define APPEARANCE_TO_COD_AIR_CONDITIONING(X)                                        \
  X(BLE_APPEARANCE_GENERIC_AIR_CONDITIONING, 0x0640, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Humidifier [15:6] 0x01A */
#define APPEARANCE_TO_COD_HUMIDIFIER(X)                                        \
  X(BLE_APPEARANCE_GENERIC_HUMIDIFIER, 0x0680, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Heating [15:6] 0x01B */
#define APPEARANCE_TO_COD_HEATING(X)                                                     \
  X(BLE_APPEARANCE_GENERIC_HEATING, 0x06C0, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_RADIATOR, 0x06C1, COD_SERVICE_NA, COD_MAJOR_MISC,             \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_BOILER, 0x06C2, COD_SERVICE_NA, COD_MAJOR_MISC,               \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_HEAT_PUMP, 0x06C3, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_INFRARED_HEATER, 0x06C4, COD_SERVICE_NA, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_RADIANT_PANEL_HEATER, 0x06C5, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_FAN_HEATER, 0x06C6, COD_SERVICE_NA, COD_MAJOR_MISC,           \
    COD_MINOR_UNCATEGORIZED)                                                             \
  X(BLE_APPEARANCE_HEATING_AIR_CURTAIN, 0x06C7, COD_SERVICE_NA, COD_MAJOR_MISC,          \
    COD_MINOR_UNCATEGORIZED)

/* Category Access Control [15:6] 0x01C */
#define APPEARANCE_TO_COD_ACCESS_CONTROL(X)                                                     \
  X(BLE_APPEARANCE_GENERIC_ACCESS_CONTROL, 0x0700, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,       \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_DOOR, 0x0701, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_GARAGE_DOOR, 0x0702, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_EMERGENCY_EXIT_DOOR, 0x0703, COD_SERVICE_CAPTURING,           \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_ACCESS_LOCK, 0x0704, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_ELEVATOR, 0x0705, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_WINDOW, 0x0706, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,        \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_ENTRANCE_GATE, 0x0707, COD_SERVICE_CAPTURING, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_DOOR_LOCK, 0x0708, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,     \
    COD_MINOR_UNCATEGORIZED)                                                                    \
  X(BLE_APPEARANCE_ACCESS_CONTROL_LOCKER, 0x0709, COD_SERVICE_CAPTURING, COD_MAJOR_MISC,        \
    COD_MINOR_UNCATEGORIZED)

/* Category Motorized Device [15:6] 0x01D */
#define APPEARANCE_TO_COD_MOTORIZED_DEVICE(X)                                          \
  X(BLE_APPEARANCE_GENERIC_MOTORIZED_DEVICE, 0x0740, COD_SERVICE_NA, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                           \
  X(BLE_APPEARANCE_MOTORIZED_GATE, 0x0741, COD_SERVICE_NA, COD_MAJOR_MISC,             \
    COD_MINOR_UNCATEGORIZED)                                                           \
  X(BLE_APPEARANCE_MOTORIZED_AWNING, 0x0742, COD_SERVICE_NA, COD_MAJOR_MISC,           \
    COD_MINOR_UNCATEGORIZED)                                                           \
  X(BLE_APPEARANCE_MOTORIZED_BLINDS_OR_SHADES, 0x0743, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                           \
  X(BLE_APPEARANCE_MOTORIZED_CURTAINS, 0x0744, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED)                                                           \
  X(BLE_APPEARANCE_MOTORIZED_SCREEN, 0x0745, COD_SERVICE_NA, COD_MAJOR_MISC,           \
    COD_MINOR_UNCATEGORIZED)

/* Category Power Device [15:6] 0x01E */
#define APPEARANCE_TO_COD_POWER_DEVICE(X)                                                         \
  X(BLE_APPEARANCE_GENERIC_POWER_DEVICE, 0x0780, COD_SERVICE_NA, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                      \
  X(BLE_APPEARANCE_POWER_OUTLET, 0x0781, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED) \
  X(BLE_APPEARANCE_POWER_STRIP, 0x0782, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)  \
  X(BLE_APPEARANCE_POWER_PLUG, 0x0783, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)   \
  X(BLE_APPEARANCE_POWER_SUPPLY, 0x0784, COD_SERVICE_NA, COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)

/* Category Light Source [15:6] 0x01F */
#define APPEARANCE_TO_COD_LIGHT_SOURCE(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_LIGHT_SOURCE, 0x07C0, COD_SERVICE_NA, COD_MAJOR_MISC,                 \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_INCANDESCENT_LIGHT_BULB, 0x07C1, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_LED_LAMP, 0x07C2, COD_SERVICE_NA, COD_MAJOR_MISC,                \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_HID_LAMP, 0x07C3, COD_SERVICE_NA, COD_MAJOR_MISC,                \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_FLUORESCENT_LAMP, 0x07C4, COD_SERVICE_NA, COD_MAJOR_MISC,        \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_LED_ARRAY, 0x07C5, COD_SERVICE_NA, COD_MAJOR_MISC,               \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_MULTI_COLOR_LED_ARRAY, 0x07C6, COD_SERVICE_NA, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_LOW_VOLTAGE_HALOGEN, 0x07C7, COD_SERVICE_NA, COD_MAJOR_MISC,     \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_LIGHT_SOURCE_ORGANIC_LIGHT_EMITTING_DIODE_OLED, 0x07C8, COD_SERVICE_NA,       \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)

/* Category Window Covering [15:6] 0x020 */
#define APPEARANCE_TO_COD_WINDOW_COVERING(X)                                                 \
  X(BLE_APPEARANCE_GENERIC_WINDOW_COVERING, 0x0800, COD_SERVICE_NA, COD_MAJOR_MISC,          \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_WINDOW_SHADES, 0x0801, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_WINDOW_BLINDS, 0x0802, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_WINDOW_AWNING, 0x0803, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_WINDOW_CURTAIN, 0x0804, COD_SERVICE_NA, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_EXTERIOR_SHUTTER, 0x0805, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                 \
  X(BLE_APPEARANCE_WINDOW_COVERING_EXTERIOR_SCREEN, 0x0806, COD_SERVICE_NA, COD_MAJOR_MISC,  \
    COD_MINOR_UNCATEGORIZED)

/* Category Audio Sink [15:6] 0x021 */
#define APPEARANCE_TO_COD_AUDIO_SINK(X)                                                          \
  X(BLE_APPEARANCE_GENERIC_AUDIO_SINK, 0x0840, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING),      \
    COD_MAJOR_AUDIO, COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)                                          \
  X(BLE_APPEARANCE_AUDIO_SINK_STANDALONE_SPEAKER, 0x0841,                                        \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), COD_MAJOR_AUDIO,                                \
    COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)                                                           \
  X(BLE_APPEARANCE_AUDIO_SINK_SOUNDBAR, 0x0842, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING),     \
    COD_MAJOR_AUDIO, COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)                                          \
  X(BLE_APPEARANCE_AUDIO_SINK_BOOKSHELF_SPEAKER, 0x0843,                                         \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), COD_MAJOR_AUDIO,                                \
    COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)                                                           \
  X(BLE_APPEARANCE_AUDIO_SINK_STANDMOUNTED_SPEAKER, 0x0844,                                      \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), COD_MAJOR_AUDIO,                                \
    COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)                                                           \
  X(BLE_APPEARANCE_AUDIO_SINK_SPEAKERPHONE, 0x0845, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), \
    COD_MAJOR_AUDIO, COD_MAJOR_AUDIO_MINOR_LOUDSPEAKER)

/* Category Audio Source [15:6] 0x022 */
#define APPEARANCE_TO_COD_AUDIO_SOURCE(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_AUDIO_SOURCE, 0x0880, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,             \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_MICROPHONE, 0x0881, (COD_SERVICE_AUDIO | COD_SERVICE_CAPTURING), \
    COD_MAJOR_AUDIO, COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                           \
  X(BLE_APPEARANCE_AUDIO_SOURCE_ALARM, 0x0882, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,               \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_BELL, 0x0883, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,                \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_HORN, 0x0884, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,                \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_BROADCASTING_DEVICE, 0x0885, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO, \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_SERVICE_DESK, 0x0886, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,        \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_KIOSK, 0x0887, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,               \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_BROADCASTING_ROOM, 0x0888, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,   \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)                                                            \
  X(BLE_APPEARANCE_AUDIO_SOURCE_AUDITORIUM, 0x0889, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,          \
    COD_MAJOR_AUDIO_MINOR_MICROPHONE)

/* Category Motorized Vehicle [15:6] 0x023 */
#define APPEARANCE_TO_COD_MOTORIZED_VEHICLE(X)                                                     \
  X(BLE_APPEARANCE_GENERIC_MOTORIZED_VEHICLE, 0x08C0, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_CAR, 0x08C1, COD_SERVICE_NA, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_LARGE_GOODS, 0x08C2, COD_SERVICE_NA, COD_MAJOR_MISC,          \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_2_WHEELED, 0x08C3, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_MOTORBIKE, 0x08C4, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_SCOOTER, 0x08C5, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_MOPED, 0x08C6, COD_SERVICE_NA, COD_MAJOR_MISC,                \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_3_WHEELED, 0x08C7, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_LIGHT_VEHICLE, 0x08C8, COD_SERVICE_NA, COD_MAJOR_MISC,        \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_QUAD_BIKE, 0x08C9, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_MINIBUS, 0x08CA, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_BUS, 0x08CB, COD_SERVICE_NA, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_TROLLEY, 0x08CC, COD_SERVICE_NA, COD_MAJOR_MISC,              \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_AGRICULTURAL_VEHICLE, 0x08CD, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_CAMPER_CARAVAN, 0x08CE, COD_SERVICE_NA, COD_MAJOR_MISC,       \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_MOTORIZED_VEHICLE_RECREATIONAL_VEHICLE_MOTOR_HOME, 0x08CF, COD_SERVICE_NA,      \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)

/* Category Domestic Appliance [15:6] 0x024 */
#define APPEARANCE_TO_COD_DOMESTIC_APPLIANCE(X)                                                \
  X(BLE_APPEARANCE_GENERIC_DOMESTIC_APPLIANCE, 0x0900, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_REFRIGERATOR, 0x0901, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_FREEZER, 0x0902, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_OVEN, 0x0903, COD_SERVICE_NA, COD_MAJOR_MISC,            \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_MICROWAVE, 0x0904, COD_SERVICE_NA, COD_MAJOR_MISC,       \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_TOASTER, 0x0905, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_WASHING_MACHINE, 0x0906, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_DRYER, 0x0907, COD_SERVICE_NA, COD_MAJOR_MISC,           \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_COFFEE_MAKER, 0x0908, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_CLOTHES_IRON, 0x0909, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_CURLING_IRON, 0x090A, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_HAIR_DRYER, 0x090B, COD_SERVICE_NA, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_VACUUM_CLEANER, 0x090C, COD_SERVICE_NA, COD_MAJOR_MISC,  \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_ROBOTIC_VACUUM_CLEANER, 0x090D, COD_SERVICE_NA,          \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_RICE_COOKER, 0x090E, COD_SERVICE_NA, COD_MAJOR_MISC,     \
    COD_MINOR_UNCATEGORIZED)                                                                   \
  X(BLE_APPEARANCE_DOMESTIC_APPLIANCE_CLOTHES_STEAMER, 0x090F, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Wearable Audio Device [15:6] 0x025 */
#define APPEARANCE_TO_COD_WEARABLE_AUDIO_DEVICE(X)                  \
  X(BLE_APPEARANCE_GENERIC_WEARABLE_AUDIO_DEVICE, 0x0940,           \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO), \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)                         \
  X(BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_EARBUD, 0x0941,            \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO), \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)                         \
  X(BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADSET, 0x0942,           \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO), \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)                         \
  X(BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_HEADPHONES, 0x0943,        \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO), \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)                         \
  X(BLE_APPEARANCE_WEARABLE_AUDIO_DEVICE_NECK_BAND, 0x0944,         \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO), \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)

/* Category Aircraft [15:6] 0x026 */
#define APPEARANCE_TO_COD_AIRCRAFT(X)                                                \
  X(BLE_APPEARANCE_GENERIC_AIRCRAFT, 0x0980, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED)                                                         \
  X(BLE_APPEARANCE_AIRCRAFT_LIGHT, 0x0981, COD_SERVICE_NA, COD_MAJOR_MISC,           \
    COD_MINOR_UNCATEGORIZED)                                                         \
  X(BLE_APPEARANCE_AIRCRAFT_MICROLIGHT, 0x0982, COD_SERVICE_NA, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                         \
  X(BLE_APPEARANCE_AIRCRAFT_PARAGLIDER, 0x0983, COD_SERVICE_NA, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                         \
  X(BLE_APPEARANCE_AIRCRAFT_LARGE_PASSENGER, 0x0984, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)

/* Category Audio/Video Equipment [15:6] 0x027 */
#define APPEARANCE_TO_COD_AV_EQUIPMENT(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_AV_EQUIPMENT, 0x09C0, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,             \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_AV_EQUIPMENT_AMPLIFIER, 0x09C1, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,           \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_AV_EQUIPMENT_RECEIVER, 0x09C2, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,            \
    COD_MINOR_UNCATEGORIZED)                                                                     \
  X(BLE_APPEARANCE_AV_EQUIPMENT_RADIO, 0x09C3, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,               \
    COD_MAJOR_AUDIO_MINOR_PORTABLE_AUDIO)                                                        \
  X(BLE_APPEARANCE_AV_EQUIPMENT_TUNER, 0x09C4, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,               \
    COD_MAJOR_AUDIO_MINOR_PORTABLE_AUDIO)                                                        \
  X(BLE_APPEARANCE_AV_EQUIPMENT_TURNTABLE, 0x09C5, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,           \
    COD_MAJOR_AUDIO_MINOR_PORTABLE_AUDIO)                                                        \
  X(BLE_APPEARANCE_AV_EQUIPMENT_CD_PLAYER, 0x09C6, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,           \
    COD_MAJOR_AUDIO_MINOR_VCR)                                                                   \
  X(BLE_APPEARANCE_AV_EQUIPMENT_DVD_PLAYER, 0x09C7, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,          \
    COD_MAJOR_AUDIO_MINOR_VCR)                                                                   \
  X(BLE_APPEARANCE_AV_EQUIPMENT_BLU_RAY_PLAYER, 0x09C8, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,      \
    COD_MAJOR_AUDIO_MINOR_VCR)                                                                   \
  X(BLE_APPEARANCE_AV_EQUIPMENT_OPTICAL_DISC_PLAYER, 0x09C9, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO, \
    COD_MAJOR_AUDIO_MINOR_VCR)                                                                   \
  X(BLE_APPEARANCE_AV_EQUIPMENT_SET_TOP_BOX, 0x09CA, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,         \
    COD_MAJOR_AUDIO_MINOR_VCR)                                                                   \
  X(BLE_APPEARANCE_AV_EQUIPMENT_AUDIO_CAR_AUDIO, 0x09CB, COD_SERVICE_AUDIO, COD_MAJOR_AUDIO,     \
    COD_MAJOR_AUDIO_MINOR_CAR_AUDIO)

/* Category Display Equipment [15:6] 0x028 */
#define APPEARANCE_TO_COD_DISPLAY_EQUIPMENT(X)                                                     \
  X(BLE_APPEARANCE_GENERIC_DISPLAY_EQUIPMENT, 0x0A00, COD_SERVICE_RENDERING, COD_MAJOR_IMAGING,    \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)                                                               \
  X(BLE_APPEARANCE_DISPLAY_EQUIPMENT_TELEVISION, 0x0A01, COD_SERVICE_RENDERING, COD_MAJOR_IMAGING, \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)                                                               \
  X(BLE_APPEARANCE_DISPLAY_EQUIPMENT_MONITOR, 0x0A02, COD_SERVICE_RENDERING, COD_MAJOR_IMAGING,    \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)                                                               \
  X(BLE_APPEARANCE_DISPLAY_EQUIPMENT_PROJECTOR, 0x0A03, COD_SERVICE_RENDERING, COD_MAJOR_IMAGING,  \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)

/* Category Hearing Aid [15:6] 0x029 */
#define APPEARANCE_TO_COD_HEARING_AID(X)                                                        \
  X(BLE_APPEARANCE_GENERIC_HEARING_AID, 0x0A40, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING),    \
    (COD_MAJOR_AUDIO | COD_MAJOR_HEALTH), COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)               \
  X(BLE_APPEARANCE_HEARING_AID_IN_EAR, 0x0A41, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING),     \
    (COD_MAJOR_AUDIO | COD_MAJOR_HEALTH), COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)               \
  X(BLE_APPEARANCE_HEARING_AID_BEHIND_EAR, 0x0A42, (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), \
    (COD_MAJOR_AUDIO | COD_MAJOR_HEALTH), COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)               \
  X(BLE_APPEARANCE_HEARING_AID_COCHLEAR_IMPLANT, 0x0A43,                                        \
    (COD_SERVICE_AUDIO | COD_SERVICE_RENDERING), (COD_MAJOR_AUDIO | COD_MAJOR_HEALTH),          \
    COD_MAJOR_AUDIO_MINOR_WEARABLE_HEADSET)

/* Category Gaming [15:6] 0x02A */
#define APPEARANCE_TO_COD_GAMING(X)                                                              \
  X(BLE_APPEARANCE_GENERIC_GAMING, 0x0A80, COD_SERVICE_NA, COD_MAJOR_COMPUTER,                   \
    COD_MAJOR_COMPUTER_MINOR_UNCATEGORIZED)                                                      \
  X(BLE_APPEARANCE_GAMING_HOME_VIDEO_GAME_CONSOLE, 0x0A81, COD_SERVICE_NA, COD_MAJOR_COMPUTER,   \
    COD_MAJOR_COMPUTER_MINOR_HANDHELD_PC_PDA)                                                    \
  X(BLE_APPEARANCE_GAMING_PORTABLE_HANDHELD_CONSOLE, 0x0A82, COD_SERVICE_NA, COD_MAJOR_COMPUTER, \
    COD_MAJOR_COMPUTER_MINOR_HANDHELD_PC_PDA)

/* Category Signage [15:6] 0x02B */
#define APPEARANCE_TO_COD_SIGNAGE(X)                                                    \
  X(BLE_APPEARANCE_GENERIC_SIGNAGE, 0x0AC0, COD_SERVICE_NA, COD_MAJOR_IMAGING,          \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)                                                    \
  X(BLE_APPEARANCE_SIGNAGE_DIGITAL, 0x0AC1, COD_SERVICE_NA, COD_MAJOR_IMAGING,          \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)                                                    \
  X(BLE_APPEARANCE_SIGNAGE_ELECTRONIC_LABEL, 0x0AC2, COD_SERVICE_NA, COD_MAJOR_IMAGING, \
    COD_MAJOR_IMAGING_MINOR_DISPLAY)

/* Category Pulse Oximeter [15:6] 0x031 */
#define APPEARANCE_TO_COD_PULSE_OXIMETER(X)                                            \
  X(BLE_APPEARANCE_GENERIC_PULSE_OXIMETER, 0x0C40, COD_SERVICE_NA, COD_MAJOR_HEALTH,   \
    COD_MAJOR_HEALTH_MINOR_PULSE_OXIMETER)                                             \
  X(BLE_APPEARANCE_PULSE_OXIMETER_FINGERTIP, 0x0C41, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_PULSE_OXIMETER)                                             \
  X(BLE_APPEARANCE_PULSE_OXIMETER_WRIST, 0x0C42, COD_SERVICE_NA, COD_MAJOR_HEALTH,     \
    COD_MAJOR_HEALTH_MINOR_PULSE_OXIMETER)

/* Category Weight Scale [15:6] 0x032 */
#define APPEARANCE_TO_COD_WEIGHT_SCALE(X)                                    \
  X(BLE_APPEARANCE_GENERIC_WEIGHT, 0x0C80, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_WEIGHING_SCALE)

/* Category Personal Mobility Device [15:6] 0x033 */
#define APPEARANCE_TO_COD_PERSONAL_MOBILITY_DEVICE(X)                                          \
  X(BLE_APPEARANCE_GENERIC_PERSONAL_MOBILITY_DEVICE, 0x0CC0, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                                           \
  X(BLE_APPEARANCE_PERSONAL_MOBILITY_DEVICE_POWERED_WHEELCHAIR, 0x0CC1, COD_SERVICE_NA,        \
    COD_MAJOR_HEALTH, COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)                         \
  X(BLE_APPEARANCE_PERSONAL_MOBILITY_DEVICE_MOBILITY_SCOOTER, 0x0CC2, COD_SERVICE_NA,          \
    COD_MAJOR_HEALTH, COD_MAJOR_HEALTH_MINOR_PERSONAL_MOBILITY_DEVICE)

/* Category Continuous Glucose Monitor [15:6] 0x034 */
#define APPEARANCE_TO_COD_CONTINUOUS_GLUCOSE_MONITOR(X)                                          \
  X(BLE_APPEARANCE_GENERIC_CONTINUOUS_GLUCOSE_MONITOR, 0x0D00, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GLUCOSE_METER)

/* Category Insulin Pump [15:6] 0x035 */
#define APPEARANCE_TO_COD_INSULIN_PUMP(X)                                          \
  X(BLE_APPEARANCE_GENERIC_INSULIN_PUMP, 0x0D40, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)                                 \
  X(BLE_APPEARANCE_INSULIN_PUMP_DURABLE, 0x0D41, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)                                 \
  X(BLE_APPEARANCE_INSULIN_PUMP_PATCH, 0x0D44, COD_SERVICE_NA, COD_MAJOR_HEALTH,   \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)                                 \
  X(BLE_APPEARANCE_INSULIN_PUMP_PEN, 0x0D48, COD_SERVICE_NA, COD_MAJOR_HEALTH,     \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)

/* Category Medication Delivery [15:6] 0x036 */
#define APPEARANCE_TO_COD_MEDICATION_DELIVERY(X)                                          \
  X(BLE_APPEARANCE_GENERIC_MEDICATION_DELIVERY, 0x0D80, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)

/* Category Spirometer [15:6] 0x037 */
#define APPEARANCE_TO_COD_SPIROMETER(X)                                           \
  X(BLE_APPEARANCE_GENERIC_SPIROMETER, 0x0DC0, COD_SERVICE_NA, COD_MAJOR_HEALTH,  \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)                                \
  X(BLE_APPEARANCE_SPIROMETER_HANDHELD, 0x0DC1, COD_SERVICE_NA, COD_MAJOR_HEALTH, \
    COD_MAJOR_HEALTH_MINOR_GENERIC_HEALTH_MANAGER)

/* Category Outdoor Sports Activity [15:6] 0x051 */
#define APPEARANCE_TO_COD_OUTDOOR_SPORTS(X)                                                      \
  X(BLE_APPEARANCE_GENERIC_OUTDOOR_SPORTS, 0x1440, COD_SERVICE_POSITIONING, COD_MAJOR_WEARABLE,  \
    COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)                                                        \
  X(BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION, 0x1441, COD_SERVICE_POSITIONING, COD_MAJOR_WEARABLE, \
    COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)                                                        \
  X(BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_AND_NAV, 0x1442, COD_SERVICE_POSITIONING,             \
    COD_MAJOR_WEARABLE, COD_MAJOR_WEARABLE_MINOR_WRIST_WATCH)                                    \
  X(BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD, 0x1443, COD_SERVICE_POSITIONING,                 \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                                 \
  X(BLE_APPEARANCE_OUTDOOR_SPORTS_LOCATION_POD_AND_NAV, 0x1444, COD_SERVICE_POSITIONING,         \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)

/* Category Industrial Measurement Device [15:6] 0x052 */
#define APPEARANCE_TO_COD_INDUSTRIAL_MEASUREMENT_DEVICE(X)                               \
  X(BLE_APPEARANCE_GENERIC_INDUSTRIAL_MEASUREMENT_DEVICE, 0x1480, COD_SERVICE_NA,        \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_TORQUE_TESTING, 0x1481, COD_SERVICE_NA, \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_CALIPER, 0x1482, COD_SERVICE_NA,        \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_DIAL_INDICATOR, 0x1483, COD_SERVICE_NA, \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_MICROMETER, 0x1484, COD_SERVICE_NA,     \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_HEIGHT_GAUGE, 0x1485, COD_SERVICE_NA,   \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)                         \
  X(BLE_APPEARANCE_INDUSTRIAL_MEASUREMENT_DEVICE_FORCE_GAUGE, 0x1486, COD_SERVICE_NA,    \
    COD_MAJOR_PERIPHERAL, COD_MAJOR_PERIPH_MINOR_SENSING_DEVICE)

/* Category Industrial Tools [15:6] 0x053 */
#define APPEARANCE_TO_COD_INDUSTRIAL_TOOLS(X)                                                      \
  X(BLE_APPEARANCE_GENERIC_INDUSTRIAL_TOOLS, 0x14C0, COD_SERVICE_NA, COD_MAJOR_MISC,               \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_MACHINE_TOOL_HOLDER, 0x14C1, COD_SERVICE_NA, COD_MAJOR_MISC,   \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_GENERIC_CLAMPING_DEVICE, 0x14C2, COD_SERVICE_NA,               \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_CLAMPING_JAWS_JAWS_CHUCK, 0x14C3, COD_SERVICE_NA,              \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_CLAMPING_COLLET_CHUCK, 0x14C4, COD_SERVICE_NA, COD_MAJOR_MISC, \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_CLAMPING_MANDREL, 0x14C5, COD_SERVICE_NA, COD_MAJOR_MISC,      \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_VISE, 0x14C6, COD_SERVICE_NA, COD_MAJOR_MISC,                  \
    COD_MINOR_UNCATEGORIZED)                                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_ZERO_POINT_CLAMPING_SYSTEM, 0x14C7, COD_SERVICE_NA,            \
    COD_MAJOR_MISC, COD_MINOR_UNCATEGORIZED)                                                       \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_TORQUE_WRENCH, 0x14C8, COD_SERVICE_NA, COD_MAJOR_MISC,         \
    COD_MINOR_UNCATEGORIZED) /* Torque implies sensing */                                          \
  X(BLE_APPEARANCE_INDUSTRIAL_TOOLS_TORQUE_SCREWDRIVER, 0x14C9, COD_SERVICE_NA, COD_MAJOR_MISC,    \
    COD_MINOR_UNCATEGORIZED) /* Torque implies sensing */

/*
 * Collection of all the appearance to COD functions per category.
 * Note: Add the macro call here if a new definition is added.
 */
#define APPEARANCE_TO_COD(X)                                                   \
  APPEARANCE_TO_COD_UNKNOWN(X)                                                 \
  APPEARANCE_TO_COD_PHONE(X)                                                   \
  APPEARANCE_TO_COD_COMPUTER(X) /* Extended to cover all computer sub-types */ \
  APPEARANCE_TO_COD_WATCH(X)                                                   \
  APPEARANCE_TO_COD_CLOCK(X)                                                   \
  APPEARANCE_TO_COD_DISPLAY(X)                                                 \
  APPEARANCE_TO_COD_REMOTE_CONTROL(X)                                          \
  APPEARANCE_TO_COD_EYEGLASSES(X)                                              \
  APPEARANCE_TO_COD_TAG(X)                                                     \
  APPEARANCE_TO_COD_KEYRING(X)                                                 \
  APPEARANCE_TO_COD_MEDIA_PLAYER(X)                                            \
  APPEARANCE_TO_COD_BARCODE_SCANNER(X)                                         \
  APPEARANCE_TO_COD_THERMOMETER(X)                                             \
  APPEARANCE_TO_COD_HEART_RATE_SENSOR(X)                                       \
  APPEARANCE_TO_COD_BLOOD_PRESSURE(X)                                          \
  APPEARANCE_TO_COD_HID(X)                                                     \
  APPEARANCE_TO_COD_GLUCOSE_METER(X)                                           \
  APPEARANCE_TO_COD_RUNNING_WALKING_SENSOR(X)                                  \
  APPEARANCE_TO_COD_CYCLING(X)                                                 \
  APPEARANCE_TO_COD_CONTROL_DEVICE(X)                                          \
  APPEARANCE_TO_COD_NETWORK_DEVICE(X)                                          \
  APPEARANCE_TO_COD_SENSOR(X)                                                  \
  APPEARANCE_TO_COD_LIGHT_FIXTURES(X)                                          \
  APPEARANCE_TO_COD_FAN(X)                                                     \
  APPEARANCE_TO_COD_HVAC(X)                                                    \
  APPEARANCE_TO_COD_AIR_CONDITIONING(X)                                        \
  APPEARANCE_TO_COD_HUMIDIFIER(X)                                              \
  APPEARANCE_TO_COD_HEATING(X)                                                 \
  APPEARANCE_TO_COD_ACCESS_CONTROL(X)                                          \
  APPEARANCE_TO_COD_MOTORIZED_DEVICE(X)                                        \
  APPEARANCE_TO_COD_POWER_DEVICE(X)                                            \
  APPEARANCE_TO_COD_LIGHT_SOURCE(X)                                            \
  APPEARANCE_TO_COD_WINDOW_COVERING(X)                                         \
  APPEARANCE_TO_COD_AUDIO_SINK(X)                                              \
  APPEARANCE_TO_COD_AUDIO_SOURCE(X)                                            \
  APPEARANCE_TO_COD_MOTORIZED_VEHICLE(X)                                       \
  APPEARANCE_TO_COD_DOMESTIC_APPLIANCE(X)                                      \
  APPEARANCE_TO_COD_WEARABLE_AUDIO_DEVICE(X)                                   \
  APPEARANCE_TO_COD_AIRCRAFT(X)                                                \
  APPEARANCE_TO_COD_AV_EQUIPMENT(X)                                            \
  APPEARANCE_TO_COD_DISPLAY_EQUIPMENT(X)                                       \
  APPEARANCE_TO_COD_HEARING_AID(X)                                             \
  APPEARANCE_TO_COD_GAMING(X)                                                  \
  APPEARANCE_TO_COD_SIGNAGE(X)                                                 \
  APPEARANCE_TO_COD_PULSE_OXIMETER(X)                                          \
  APPEARANCE_TO_COD_WEIGHT_SCALE(X)                                            \
  APPEARANCE_TO_COD_PERSONAL_MOBILITY_DEVICE(X)                                \
  APPEARANCE_TO_COD_CONTINUOUS_GLUCOSE_MONITOR(X)                              \
  APPEARANCE_TO_COD_INSULIN_PUMP(X)                                            \
  APPEARANCE_TO_COD_MEDICATION_DELIVERY(X)                                     \
  APPEARANCE_TO_COD_SPIROMETER(X)                                              \
  APPEARANCE_TO_COD_OUTDOOR_SPORTS(X)                                          \
  APPEARANCE_TO_COD_INDUSTRIAL_MEASUREMENT_DEVICE(X)                           \
  APPEARANCE_TO_COD_INDUSTRIAL_TOOLS(X)

// Generates the BLE appearance definitions for reference
#define GENERATE_BLE_APPEARANCE_DEFINITIONS(_appearance, _value, _service, _major, _minor) \
  constexpr uint16_t _appearance = _value;

// Adds the BLE appearance definitions to the COD case statement
// `_service` values were deduced by basing BIT_X to BIT_8, values as per
// BT-spec assigned-numbers, now shifting the values to the right by 8 bits.
#define ADD_APPEARANCE_TO_COD_CASE(_appearance, _value, _service, _major, _minor) \
  case _appearance:                                                               \
    return DEV_CLASS{(_service >> 8), _major, _minor};

// Generate the actual definition for each appearance.
APPEARANCE_TO_COD(GENERATE_BLE_APPEARANCE_DEFINITIONS)

#endif  // BLE_APPEARANCE_H
