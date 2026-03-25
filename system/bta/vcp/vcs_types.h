/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <bluetooth/types/uuid.h>

#include "stack/include/gatt_api.h"

namespace bluetooth::vcs {

namespace uuid {
static constexpr Uuid kVolumeControlServiceUuid = Uuid::From16Bit(0x1844);
static constexpr Uuid kVolumeStateUuid = Uuid::From16Bit(0x2B7D);
static constexpr Uuid kVolumeControlPointUuid = Uuid::From16Bit(0x2B7E);
static constexpr Uuid kVolumeFlagsUuid = Uuid::From16Bit(0x2B7F);
}  // namespace uuid

static const uint16_t kGattInvalidHandle = 0x0000;

// VCS v1.0.1, Section 1.6, Table 1.2
static const tGATT_STATUS VCS_INVALID_CHANGE_COUNTER = static_cast<tGATT_STATUS>(0x80);
static const tGATT_STATUS VCS_OPCODE_NOT_SUPPORTED = static_cast<tGATT_STATUS>(0x81);

// VCS v1.0.1, Section 3.3.1, Volume Control Point Opcodes
static constexpr uint8_t kControlPointOpcodeRelativeVolumeDown = 0x00;
static constexpr uint8_t kControlPointOpcodeRelativeVolumeUp = 0x01;
static constexpr uint8_t kControlPointOpcodeUnmuteRelativeVolumeDown = 0x02;
static constexpr uint8_t kControlPointOpcodeUnmuteRelativeVolumeUp = 0x03;
static constexpr uint8_t kControlPointOpcodeSetAbsoluteVolume = 0x04;
static constexpr uint8_t kControlPointOpcodeUnmute = 0x05;
static constexpr uint8_t kControlPointOpcodeMute = 0x06;

static constexpr uint8_t kVolumeStateLen = 3;
static constexpr uint8_t kVolumeFlagsLen = 1;
static constexpr uint8_t kVolumeControlPointMinLen = 2;
static constexpr uint8_t kVolumeControlPointSetAbsoluteVolumeLen = 3;
static constexpr uint8_t kVolumeSettingMin = 0;
static constexpr uint8_t kVolumeSettingMax = 255;

static constexpr uint8_t kVolumeStateSettingIndex = 0;
static constexpr uint8_t kVolumeStateMuteIndex = 1;
static constexpr uint8_t kVolumeStateChangeCounterIndex = 2;
static constexpr uint8_t kVolumeFlagsIndex = 0;
static constexpr uint8_t kControlPointOpcodeIndex = 0;
static constexpr uint8_t kControlPointChangeCounterIndex = 1;
static constexpr uint8_t kControlPointVolumeSettingIndex = 2;

/**
 * @brief Represents the mute state of the device.
 *
 * See VCS v1.0.1, Section 3.1.2.
 */
enum class MuteState : uint8_t {
  kNotMuted = 0x00,
  kMuted = 0x01,
};

/**
 * @brief Represents the Volume Setting Persisted state of the device.
 *
 * See VCS v1.0.1, Section 3.3.1.
 */
enum class VolumeSettingPersisted : uint8_t {
  kResetVolumeSetting = 0,
  kUserSetVolumeSetting = 1,
};

/**
 * @brief Represents the Volume Flags characteristic.
 *
 * See VCS v1.0.1, Section 3.3.
 */
struct VolumeFlags {
  union {
    uint8_t raw;
    struct {
      VolumeSettingPersisted volume_setting_persisted : 1;
      uint8_t rfu : 7;
    } bits;
  };
  VolumeFlags() : raw(0) {}
};

}  // namespace bluetooth::vcs
