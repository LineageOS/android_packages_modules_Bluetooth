/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <aics/api.h>
#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>

#include <string>
#include <variant>

namespace bluetooth {
namespace vcp {

// Must be kept in sync with BluetoothProfile.java
enum class ConnectionState { DISCONNECTED = 0, CONNECTING, CONNECTED, DISCONNECTING };

/* Audio input types */
enum class VolumeInputType : uint8_t {
  Unspecified = 0x00,
  Bluetooth,
  Microphone,
  Analog,
  Digital,
  Radio,
  Streaming,
  Ambient,
  RFU
};

enum class VolumeInputStatus : uint8_t { Inactive = 0x00, Active, RFU };

class VolumeControllerCallbacks {
public:
  virtual ~VolumeControllerCallbacks() = default;

  /** Callback for profile connection state change */
  virtual void OnConnectionState(ConnectionState state, const RawAddress& address) = 0;

  /* Callback for the volume change changed on the device */
  virtual void OnVolumeStateChanged(const RawAddress& address, uint8_t volume, bool mute,
                                    uint8_t flags, bool isAutonomous) = 0;

  /* Callback for the volume change changed on the group*/
  virtual void OnGroupVolumeStateChanged(int group_id, uint8_t volume, bool mute,
                                         bool isAutonomous) = 0;

  virtual void OnDeviceAvailable(const RawAddress& address, int group_id, uint8_t num_offset,
                                 uint8_t num_input) = 0;

  /* Callbacks for Volume Offset Control Service (VOCS) - Extended Audio Outputs
   */
  virtual void OnExtAudioOutVolumeOffsetChanged(const RawAddress& address, uint8_t ext_output_id,
                                                int16_t offset) = 0;
  virtual void OnExtAudioOutLocationChanged(const RawAddress& address, uint8_t ext_output_id,
                                            uint32_t location) = 0;
  virtual void OnExtAudioOutDescriptionChanged(const RawAddress& address, uint8_t ext_output_id,
                                               std::string descr) = 0;

  /* Callbacks for Audio Input Stream (AIS) - Extended Audio Inputs */
  virtual void OnExtAudioInStateChanged(const RawAddress& address, uint8_t ext_input_id,
                                        int8_t gain_setting, bluetooth::aics::Mute mute,
                                        bluetooth::aics::GainMode gain_mode) = 0;
  virtual void OnExtAudioInSetGainSettingFailed(const RawAddress& address,
                                                uint8_t ext_input_id) = 0;
  virtual void OnExtAudioInSetMuteFailed(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void OnExtAudioInSetGainModeFailed(const RawAddress& address, uint8_t ext_input_id) = 0;

  virtual void OnExtAudioInStatusChanged(const RawAddress& address, uint8_t ext_input_id,
                                         VolumeInputStatus status) = 0;

  virtual void OnExtAudioInTypeChanged(const RawAddress& address, uint8_t ext_input_id,
                                       VolumeInputType type) = 0;

  virtual void OnExtAudioInGainSettingPropertiesChanged(const RawAddress& address,
                                                        uint8_t ext_input_id, uint8_t unit,
                                                        int8_t min, int8_t max) = 0;

  virtual void OnExtAudioInDescriptionChanged(const RawAddress& address, uint8_t ext_input_id,
                                              std::string description, bool is_writable) = 0;
};

class VolumeControllerInterface {
public:
  virtual ~VolumeControllerInterface() = default;

  /** Register the Volume Control callbacks */
  virtual void Init(VolumeControllerCallbacks* callbacks) = 0;

  /** Closes the interface */
  virtual void Cleanup(void) = 0;

  /** Connect to Volume Control */
  virtual void Connect(const RawAddress& address) = 0;

  /** Disconnect from Volume Control */
  virtual void Disconnect(const RawAddress& address) = 0;

  /** Called when Volume control devices is unbonded */
  virtual void RemoveDevice(const RawAddress& address) = 0;

  /** Set the volume */
  virtual void SetVolume(std::variant<RawAddress, int> addr_or_group_id, uint8_t volume) = 0;
  /** Mute the volume */
  virtual void Mute(std::variant<RawAddress, int> addr_or_group_id) = 0;

  /** Unmute the volume */
  virtual void Unmute(std::variant<RawAddress, int> addr_or_group_id) = 0;

  virtual void GetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id) = 0;
  virtual void SetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id,
                                          int16_t offset_val) = 0;
  virtual void GetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id) = 0;
  virtual void SetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id,
                                      uint32_t location) = 0;
  virtual void GetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id) = 0;
  virtual void SetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id,
                                         std::string descr) = 0;
  virtual void GetExtAudioInState(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInStatus(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInType(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInGainProps(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual bool SetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id,
                                        std::string descr) = 0;
  virtual bool SetExtAudioInGainSetting(const RawAddress& address, uint8_t ext_input_id,
                                        int8_t gain_setting) = 0;
  virtual bool SetExtAudioInGainMode(const RawAddress& address, uint8_t ext_input_id,
                                     bluetooth::aics::GainMode gain_mode) = 0;
  virtual bool SetExtAudioInMute(const RawAddress& address, uint8_t ext_input_id,
                                 bluetooth::aics::Mute mute) = 0;
};

} /* namespace vcp */
} /* namespace bluetooth */

namespace std {
template <>
struct formatter<bluetooth::vcp::VolumeInputType>
    : enum_formatter<bluetooth::vcp::VolumeInputType> {};
template <>
struct formatter<bluetooth::vcp::VolumeInputStatus>
    : enum_formatter<bluetooth::vcp::VolumeInputStatus> {};
}  // namespace std
