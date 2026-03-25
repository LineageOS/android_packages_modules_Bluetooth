/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <bluetooth/types/uuid.h>

namespace bluetooth {
namespace mcp {

// Service UUID for the Media Control Service
static constexpr bluetooth::Uuid kMediaControlServiceUuid = bluetooth::Uuid::From16Bit(0x1848);
static constexpr bluetooth::Uuid kGenericMediaControlServiceUuid =
        bluetooth::Uuid::From16Bit(0x1849);

/* Media Control Service Characteristics */
static constexpr bluetooth::Uuid kMediaPlayerNameUuid = bluetooth::Uuid::From16Bit(0x2B93);
static constexpr bluetooth::Uuid kMediaPlayerIconObjIdUuid = bluetooth::Uuid::From16Bit(0x2B94);
static constexpr bluetooth::Uuid kMediaPlayerIconUrlUuid = bluetooth::Uuid::From16Bit(0x2B95);
static constexpr bluetooth::Uuid kTrackChangedUuid = bluetooth::Uuid::From16Bit(0x2B96);
static constexpr bluetooth::Uuid kTrackTitleUuid = bluetooth::Uuid::From16Bit(0x2B97);
static constexpr bluetooth::Uuid kTrackDurationUuid = bluetooth::Uuid::From16Bit(0x2B98);
static constexpr bluetooth::Uuid kTrackPositionUuid = bluetooth::Uuid::From16Bit(0x2B99);
static constexpr bluetooth::Uuid kPlaybackSpeedUuid = bluetooth::Uuid::From16Bit(0x2B9A);
static constexpr bluetooth::Uuid kSeekingSpeedUuid = bluetooth::Uuid::From16Bit(0x2B9B);
static constexpr bluetooth::Uuid kPlayingOrderUuid = bluetooth::Uuid::From16Bit(0x2BA1);
static constexpr bluetooth::Uuid kPlayingOrderSupportedUuid = bluetooth::Uuid::From16Bit(0x2BA2);
static constexpr bluetooth::Uuid kMediaStateUuid = bluetooth::Uuid::From16Bit(0x2BA3);
static constexpr bluetooth::Uuid kMediaControlPointUuid = bluetooth::Uuid::From16Bit(0x2BA4);
static constexpr bluetooth::Uuid kMediaControlPointOpcodesSupportedUuid =
        bluetooth::Uuid::From16Bit(0x2BA5);
static constexpr bluetooth::Uuid kSearchResultsObjIdUuid = bluetooth::Uuid::From16Bit(0x2BA6);
static constexpr bluetooth::Uuid kContentControlIdUuid = bluetooth::Uuid::From16Bit(0x2BBA);

static const uint16_t kInvalidGattHandle = 0x0000;

// Opcodes for the Media Control Point characteristic
static constexpr uint8_t kMcpOpcodePlay = 0x01;
static constexpr uint8_t kMcpOpcodePause = 0x02;
static constexpr uint8_t kMcpOpcodeFastRewind = 0x03;
static constexpr uint8_t kMcpOpcodeFastForward = 0x04;
static constexpr uint8_t kMcpOpcodeStop = 0x05;
static constexpr uint8_t kMcpOpcodeMoveRelative = 0x10;
static constexpr uint8_t kMcpOpcodePreviousSegment = 0x20;
static constexpr uint8_t kMcpOpcodeNextSegment = 0x21;
static constexpr uint8_t kMcpOpcodeFirstSegment = 0x22;
static constexpr uint8_t kMcpOpcodeLastSegment = 0x23;
static constexpr uint8_t kMcpOpcodeGotoSegment = 0x24;
static constexpr uint8_t kMcpOpcodePreviousTrack = 0x30;
static constexpr uint8_t kMcpOpcodeNextTrack = 0x31;
static constexpr uint8_t kMcpOpcodeFirstTrack = 0x32;
static constexpr uint8_t kMcpOpcodeLastTrack = 0x33;
static constexpr uint8_t kMcpOpcodeGotoTrack = 0x34;
static constexpr uint8_t kMcpOpcodePreviousGroup = 0x40;
static constexpr uint8_t kMcpOpcodeNextGroup = 0x41;
static constexpr uint8_t kMcpOpcodeFirstGroup = 0x42;
static constexpr uint8_t kMcpOpcodeLastGroup = 0x43;
static constexpr uint8_t kMcpOpcodeGotoGroup = 0x44;

// Special values for Track Position and Track Duration
static constexpr uint32_t kTrackPositionUnavailable = 0xFFFFFFFF;
static constexpr uint32_t kTrackDurationUnknown = 0xFFFFFFFF;

// Media State values
static constexpr uint8_t kMediaStateInactive = 0x00;
static constexpr uint8_t kMediaStatePlaying = 0x01;
static constexpr uint8_t kMediaStatePaused = 0x02;
static constexpr uint8_t kMediaStateSeeking = 0x03;

// Playing Order values
static constexpr uint8_t kPlayingOrderSingleOnce = 0x01;
static constexpr uint8_t kPlayingOrderSingleRepeat = 0x02;
static constexpr uint8_t kPlayingOrderInOrderOnce = 0x03;
static constexpr uint8_t kPlayingOrderInOrderRepeat = 0x04;
static constexpr uint8_t kPlayingOrderOldestOnce = 0x05;
static constexpr uint8_t kPlayingOrderOldestRepeat = 0x06;
static constexpr uint8_t kPlayingOrderNewestOnce = 0x07;
static constexpr uint8_t kPlayingOrderNewestRepeat = 0x08;
static constexpr uint8_t kPlayingOrderShuffleOnce = 0x09;
static constexpr uint8_t kPlayingOrderShuffleRepeat = 0x0A;

// Characteristic value lengths
static constexpr uint8_t kMediaStateLen = 1;
static constexpr uint8_t kPlaybackSpeedLen = 1;
static constexpr uint8_t kPlayingOrderLen = 1;
static constexpr uint8_t kSeekingSpeedLen = 1;
static constexpr uint8_t kPlayingOrdersSupportedLen = 2;
static constexpr uint8_t kOpcodesSupportedLen = 4;
static constexpr uint8_t kTrackDurationLen = 4;
static constexpr uint8_t kTrackPositionLen = 4;
static constexpr uint8_t kMcpNotificationLen = 2;

// Characteristic value indices
static constexpr uint8_t kMediaStateIndex = 0;
static constexpr uint8_t kPlaybackSpeedIndex = 0;
static constexpr uint8_t kPlayingOrderIndex = 0;
static constexpr uint8_t kSeekingSpeedIndex = 0;

// Media Control Point Notification indices
static constexpr uint8_t kMcpNotificationOpcodeIndex = 0;
static constexpr uint8_t kMcpNotificationResultIndex = 1;

}  // namespace mcp
}  // namespace bluetooth
