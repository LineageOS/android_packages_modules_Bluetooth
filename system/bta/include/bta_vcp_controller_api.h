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
#include <hardware/bt_vcp_controller.h>

#include <string>

class VolumeController {
public:
  virtual ~VolumeController() = default;

  static void Initialize(bluetooth::vcp::VolumeControllerCallbacks* callbacks,
                         base::OnceClosure initCb);
  static void CleanUp();
  static VolumeController* Get();
  static void DebugDump(int fd);

  static void AddFromStorage(const RawAddress& address);

  static bool IsRunning();

  /* Volume Control Server (VCS) */
  virtual void Connect(const RawAddress& address) = 0;
  virtual void Disconnect(const RawAddress& address) = 0;
  virtual void Remove(const RawAddress& address) = 0;
  virtual void SetVolume(std::variant<RawAddress, int> addr_or_group_id, uint8_t volume) = 0;
  virtual void Mute(std::variant<RawAddress, int> addr_or_group_id) = 0;
  virtual void UnMute(std::variant<RawAddress, int> addr_or_group_id) = 0;

  /* Volume Offset Control Service (VOCS) */
  virtual void SetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id,
                                          int16_t offset) = 0;
  virtual void GetExtAudioOutVolumeOffset(const RawAddress& address, uint8_t ext_output_id) = 0;

  /* Location as per Bluetooth Assigned Numbers.*/
  virtual void SetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id,
                                      uint32_t location) = 0;
  virtual void GetExtAudioOutLocation(const RawAddress& address, uint8_t ext_output_id) = 0;
  virtual void GetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id) = 0;
  virtual void SetExtAudioOutDescription(const RawAddress& address, uint8_t ext_output_id,
                                         std::string descr) = 0;

  /* Audio Input Control Service (AICS) */
  virtual void GetExtAudioInState(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInStatus(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInType(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInGainProps(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void GetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id) = 0;
  virtual void SetExtAudioInDescription(const RawAddress& address, uint8_t ext_input_id,
                                        std::string descr) = 0;
  virtual void SetExtAudioInGainSetting(const RawAddress& address, uint8_t ext_input_id,
                                        int8_t gain_setting) = 0;
  virtual void SetExtAudioInGainMode(const RawAddress& address, uint8_t ext_input_id,
                                     bluetooth::aics::GainMode gain_mode) = 0;
  virtual void SetExtAudioInMute(const RawAddress& address, uint8_t ext_input_id,
                                 bluetooth::aics::Mute mute) = 0;
};
