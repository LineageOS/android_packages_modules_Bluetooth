/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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
#include <bluetooth/types/uuid.h>
#include <hardware/bt_vcp_controller.h>

#include <algorithm>
#include <queue>
#include <string>
#include <vector>

#include "bta/include/bta_groups.h"
#include "bta/vcp/vcs_types.h"
#include "osi/include/alarm.h"

namespace bluetooth {
namespace vcp {
namespace internal {

using namespace vcs;
using namespace vcs::uuid;

/* clang-format off */
/* Volume offset control point opcodes */
static constexpr uint8_t kVolumeOffsetControlPointOpcodeSet                 = 0x01;

/* Volume input control point opcodes */
static constexpr uint8_t kVolumeInputControlPointOpcodeSetGain              = 0x01;
static constexpr uint8_t kVolumeInputControlPointOpcodeUnmute               = 0x02;
static constexpr uint8_t kVolumeInputControlPointOpcodeMute                 = 0x03;
static constexpr uint8_t kVolumeInputControlPointOpcodeSetManualGainMode    = 0x04;
static constexpr uint8_t kVolumeInputControlPointOpcodeSetAutoGainMode      = 0x05;

static constexpr Uuid kVolumeOffsetUuid                          = Uuid::From16Bit(0x1845);
static constexpr Uuid kVolumeOffsetStateUuid                     = Uuid::From16Bit(0x2B80);
static constexpr Uuid kVolumeOffsetLocationUuid                  = Uuid::From16Bit(0x2B81);
static constexpr Uuid kVolumeOffsetControlPointUuid              = Uuid::From16Bit(0x2B82);
static constexpr Uuid kVolumeOffsetOutputDescriptionUuid         = Uuid::From16Bit(0x2B83);

static constexpr Uuid kVolumeAudioInputUuid                      = Uuid::From16Bit(0x1843);
static constexpr Uuid kVolumeAudioInputStateUuid                 = Uuid::From16Bit(0x2B77);
static constexpr Uuid kVolumeAudioInputGainSettingPropertiesUuid = Uuid::From16Bit(0x2B78);
static constexpr Uuid kVolumeAudioInputTypeUuid                  = Uuid::From16Bit(0x2B79);
static constexpr Uuid kVolumeAudioInputStatusUuid                = Uuid::From16Bit(0x2B7A);
static constexpr Uuid kVolumeAudioInputControlPointUuid          = Uuid::From16Bit(0x2B7B);
static constexpr Uuid kVolumeAudioInputDescriptionUuid           = Uuid::From16Bit(0x2B7C);

/* clang-format on */

struct VolumeOperation {
  int operation_id_;
  int group_id_;

  bool started_;
  bool is_autonomous_;

  uint8_t opcode_;
  std::vector<uint8_t> arguments_;

  std::vector<RawAddress> devices_;
  alarm_t* operation_timeout_;

  VolumeOperation(int operation_id, int group_id, bool is_autonomous, uint8_t opcode,
                  std::vector<uint8_t> arguments, std::vector<RawAddress> devices)
      : operation_id_(operation_id),
        group_id_(group_id),
        is_autonomous_(is_autonomous),
        opcode_(opcode),
        arguments_(arguments),
        devices_(devices) {
    auto name = "operation_timeout_" + std::to_string(operation_id);
    operation_timeout_ = alarm_new(name.c_str());
    started_ = false;
  }

  // Disallow copying due to owning operation_timeout_t which is freed in the
  // destructor - prevents double free.
  VolumeOperation(const VolumeOperation&) = delete;
  VolumeOperation& operator=(const VolumeOperation&) = delete;

  ~VolumeOperation() {
    if (operation_timeout_ == nullptr) {
      log::warn("operation_timeout_ should not be null, id {}, device cnt {}", operation_id_,
                devices_.size());
      return;
    }

    if (alarm_is_scheduled(operation_timeout_)) {
      alarm_cancel(operation_timeout_);
    }

    alarm_free(operation_timeout_);
    operation_timeout_ = nullptr;
  }

  bool IsGroupOperation(void) { return group_id_ != bluetooth::groups::kGroupUnknown; }

  bool IsStarted(void) { return started_; }
  void Start(void) { started_ = true; }
};

struct GainSettings {
  uint8_t unit = 0;
  int8_t min = 0;
  int8_t max = 0;

  GainSettings() {}
};

struct VolumeAudioInput {
  /* const */ uint8_t id;
  int8_t gain_setting = 0;
  bluetooth::aics::Mute mute = bluetooth::aics::Mute::DISABLED;
  bluetooth::aics::GainMode gain_mode = bluetooth::aics::GainMode::MANUAL_ONLY;
  VolumeInputStatus status = VolumeInputStatus::Inactive;
  VolumeInputType type = VolumeInputType::Unspecified;
  uint8_t change_counter = 0;
  std::string description = "";
  /* const */ uint16_t service_handle;
  /* const */ uint16_t state_handle;
  /* const */ uint16_t state_ccc_handle;
  /* const */ uint16_t gain_setting_handle;
  /* const */ uint16_t type_handle;
  /* const */ uint16_t status_handle;
  /* const */ uint16_t status_ccc_handle;
  /* const */ uint16_t control_point_handle;
  /* const */ uint16_t description_handle;
  /* const */ uint16_t description_ccc_handle;
  /* const */ bool description_writable;
  struct GainSettings gain_settings = GainSettings();

  explicit VolumeAudioInput(uint8_t id, uint16_t service_handle, uint16_t state_handle,
                            uint16_t state_ccc_handle, uint16_t gain_setting_handle,
                            uint16_t type_handle, uint16_t status_handle,
                            uint16_t status_ccc_handle, uint16_t control_point_handle,
                            uint16_t description_handle, uint16_t description_ccc_handle,
                            bool description_writable)
      : id(id),
        service_handle(service_handle),
        state_handle(state_handle),
        state_ccc_handle(state_ccc_handle),
        gain_setting_handle(gain_setting_handle),
        type_handle(type_handle),
        status_handle(status_handle),
        status_ccc_handle(status_ccc_handle),
        control_point_handle(control_point_handle),
        description_handle(description_handle),
        description_ccc_handle(description_ccc_handle),
        description_writable(description_writable) {}
};

class VolumeAudioInputs {
public:
  void Add(VolumeAudioInput input) { volume_audio_inputs.push_back(input); }

  VolumeAudioInput* FindByType(VolumeInputType type) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&type](const VolumeAudioInput& item) { return item.type == type; });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  VolumeAudioInput* FindByServiceHandle(uint16_t service_handle) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&service_handle](const VolumeAudioInput& item) {
                               return item.service_handle == service_handle;
                             });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  VolumeAudioInput* FindById(uint8_t id) {
    auto iter = std::find_if(volume_audio_inputs.begin(), volume_audio_inputs.end(),
                             [&id](const VolumeAudioInput& item) { return item.id == id; });

    return (iter == volume_audio_inputs.end()) ? nullptr : &(*iter);
  }

  void Clear() { volume_audio_inputs.clear(); }

  size_t Size() { return volume_audio_inputs.size(); }

  void Dump(int fd) {
    std::stringstream stream;
    int n = Size();
    stream << "     == number of inputs: " << n << " ==\n";

    for (auto const& v : volume_audio_inputs) {
      stream << "   id: " << +v.id << "\n"
             << "    description: " << v.description << "\n"
             << "    type: " << static_cast<int>(v.type) << "\n"
             << "    status: " << static_cast<int>(v.status) << "\n"
             << "    changeCnt: " << +v.change_counter << "\n"
             << "    service_handle: " << +v.service_handle << "\n"
             << "    description_writable" << v.description_writable << "\n";
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  std::vector<VolumeAudioInput> volume_audio_inputs;
};

struct VolumeOffset {
  uint8_t id;
  uint8_t change_counter;
  int16_t offset;
  uint32_t location;
  std::string description;
  uint16_t service_handle;
  uint16_t state_handle;
  uint16_t state_ccc_handle;
  uint16_t audio_location_handle;
  uint16_t audio_location_ccc_handle;
  uint16_t audio_descr_handle;
  uint16_t audio_descr_ccc_handle;
  uint16_t control_point_handle;
  bool audio_location_writable;
  bool audio_descr_writable;

  explicit VolumeOffset(uint16_t service_handle)
      : id(0),
        change_counter(0),
        offset(0),
        location(0),
        description(""),
        service_handle(service_handle),
        state_handle(0),
        state_ccc_handle(0),
        audio_location_handle(0),
        audio_location_ccc_handle(0),
        audio_descr_handle(0),
        audio_descr_ccc_handle(0),
        control_point_handle(0),
        audio_location_writable(false),
        audio_descr_writable(false) {}
};

class VolumeOffsets {
public:
  void Add(VolumeOffset offset) {
    offset.id = static_cast<uint8_t>(Size()) + 1;
    volume_offsets.push_back(offset);
  }

  VolumeOffset* FindByLocation(uint8_t location) {
    auto iter = std::find_if(
            volume_offsets.begin(), volume_offsets.end(),
            [&location](const VolumeOffset& item) { return item.location == location; });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  VolumeOffset* FindByServiceHandle(uint16_t service_handle) {
    auto iter = std::find_if(volume_offsets.begin(), volume_offsets.end(),
                             [&service_handle](const VolumeOffset& item) {
                               return item.service_handle == service_handle;
                             });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  VolumeOffset* FindById(uint16_t id) {
    auto iter = std::find_if(volume_offsets.begin(), volume_offsets.end(),
                             [&id](const VolumeOffset& item) { return item.id == id; });

    return (iter == volume_offsets.end()) ? nullptr : &(*iter);
  }

  void Clear() { volume_offsets.clear(); }

  size_t Size() { return volume_offsets.size(); }

  void Dump(int fd) {
    std::stringstream stream;
    int n = Size();
    stream << "     == number of offsets: " << n << " ==\n";

    for (auto const& v : volume_offsets) {
      stream << "   id: " << +v.id << "\n"
             << "    offset: " << +v.offset << "\n"
             << "    changeCnt: " << +v.change_counter << "\n"
             << "    location: " << +v.location << "\n"
             << "    description: " << v.description << "\n"
             << "    service_handle: " << +v.service_handle << "\n"
             << "    audio_location_writable " << v.audio_location_writable << "\n"
             << "    audio_descr_writable: " << v.audio_descr_writable << "\n";
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  std::vector<VolumeOffset> volume_offsets;
};

}  // namespace internal
}  // namespace vcp
}  // namespace bluetooth
