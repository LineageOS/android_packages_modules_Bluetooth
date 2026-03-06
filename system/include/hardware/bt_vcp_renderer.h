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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <string>
#include <vector>

namespace bluetooth::vcp {

enum class GattConnectionState { DISCONNECTED = 0, CONNECTING, CONNECTED, DISCONNECTING };
enum class MuteState { NOT_MUTED = 0, MUTED };
enum class VolumeSettingPersisted : uint8_t {
  RESET_VOLUME_SETTING = 0,
  USER_SET_VOLUME_SETTING = 1,
};

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

struct VolumeRendererConfig {
  uint8_t initial_volume;
  MuteState initial_mute_state;
  VolumeSettingPersisted initial_volume_setting_persisted;
  uint8_t volume_step_size;
};

/**
 * @brief Callbacks for the Volume Renderer to notify the framework of events.
 */
class VolumeRendererCallbacks {
public:
  VolumeRendererCallbacks() = default;
  virtual ~VolumeRendererCallbacks() = default;

  /* Callback to notify Java that stack is ready */
  virtual void OnInitialized(void) = 0;

  /* Callback for profile connection state change */
  virtual void OnGattConnectionStateChanged(const RawAddress& address,
                                            GattConnectionState state) = 0;

  /* Callback for volume state change request from the Volume Controller */
  virtual void OnVolumeStateChangeRequest(uint8_t volume, MuteState mute_state) = 0;
};

/**
 * @brief The JNI-level interface for the Volume Renderer.
 */
class VolumeRendererInterface {
public:
  VolumeRendererInterface() = default;
  virtual ~VolumeRendererInterface() = default;

  /* Register the Vcp callbacks */
  virtual void Initialize(VolumeRendererCallbacks* callbacks,
                          const VolumeRendererConfig& config) = 0;

  /* Cleanup the Vcp */
  virtual void Cleanup(void) = 0;

  /* Updates the volume state */
  virtual void UpdateVolumeState(uint8_t volume, MuteState mute_state) = 0;

  /* Updates the volume flags */
  virtual void UpdateVolumeFlags(const VolumeFlags& flags) = 0;
};

}  // namespace bluetooth::vcp

namespace std {
template <>
struct formatter<bluetooth::vcp::GattConnectionState>
    : enum_formatter<bluetooth::vcp::GattConnectionState> {};
template <>
struct formatter<bluetooth::vcp::MuteState> : enum_formatter<bluetooth::vcp::MuteState> {};
template <>
struct formatter<bluetooth::vcp::VolumeSettingPersisted>
    : enum_formatter<bluetooth::vcp::VolumeSettingPersisted> {};
}  // namespace std
