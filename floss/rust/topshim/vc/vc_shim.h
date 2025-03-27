/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <memory>

#include "include/hardware/bt_vc.h"
#include "rust/cxx.h"
#include "types/raw_address.h"

namespace bluetooth {
namespace topshim {
namespace rust {

class VolumeControlIntf {
public:
  VolumeControlIntf(vc::VolumeControlInterface* intf) : intf_(intf) {}

  void init(/*VolumeControlCallbacks* callbacks*/);
  void cleanup();
  void connect(RawAddress addr);
  void disconnect(RawAddress addr);
  void remove_device(RawAddress addr);
  void set_volume(int group_id, uint8_t volume);
  void mute(RawAddress addr);
  void unmute(RawAddress addr);
  void get_ext_audio_out_volume_offset(RawAddress addr, uint8_t ext_output_id);
  void set_ext_audio_out_volume_offset(RawAddress addr, uint8_t ext_output_id, int16_t offset_val);
  void get_ext_audio_out_location(RawAddress addr, uint8_t ext_output_id);
  void set_ext_audio_out_location(RawAddress addr, uint8_t ext_output_id, uint32_t location);
  void get_ext_audio_out_description(RawAddress addr, uint8_t ext_output_id);
  void set_ext_audio_out_description(RawAddress addr, uint8_t ext_output_id, const char* descr);

private:
  vc::VolumeControlInterface* intf_;
};

std::unique_ptr<VolumeControlIntf> GetVolumeControlProfile(const unsigned char* btif);

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
