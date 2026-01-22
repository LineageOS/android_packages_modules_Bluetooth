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

#include <bluetooth/types/address.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

#include "bta/include/bta_gatt_api.h"
#include "bta/vcp/vcp_controller_types.h"

namespace bluetooth {
namespace vcp {
namespace internal {

class VolumeControllerDevice {
public:
  RawAddress address;

  /* We are making active attempt to connect to this device */
  bool connecting_actively;

  bool known_service_handles_;

  uint8_t volume;
  uint8_t change_counter;
  bool mute;
  uint8_t flags;
  int group_id;

  tCONN_ID connection_id;
  uint16_t mtu_ = GATT_DEF_BLE_MTU_SIZE;

  /* Volume Control Service */
  uint16_t volume_state_handle;
  uint16_t volume_state_ccc_handle;
  uint16_t volume_control_point_handle;
  uint16_t volume_flags_handle;
  uint16_t volume_flags_ccc_handle;

  VolumeAudioInputs audio_inputs;
  VolumeOffsets audio_offsets;

  /* Set when device successfully reads server status and registers for
   * notifications */
  bool device_ready;

  VolumeControllerDevice(const RawAddress& address, bool connecting_actively)
      : address(address),
        connecting_actively(connecting_actively),
        known_service_handles_(false),
        volume(0),
        change_counter(0),
        mute(false),
        flags(0),
        group_id(bluetooth::groups::kGroupUnknown),
        connection_id(GATT_INVALID_CONN_ID),
        volume_state_handle(0),
        volume_state_ccc_handle(0),
        volume_control_point_handle(0),
        volume_flags_handle(0),
        volume_flags_ccc_handle(0),
        device_ready(false),
        requests_initiated(false) {}

  ~VolumeControllerDevice() = default;

  std::string ToRedactedStringForLogging() const { return address.ToRedactedStringForLogging(); }

  void DebugDump(int fd) {
    std::stringstream stream;
    stream << "   == device address: " << address.ToRedactedStringForLogging() << " ==\n";

    if (connection_id == GATT_INVALID_CONN_ID) {
      stream << "    Not connected\n";
    } else {
      stream << "    Connected. Conn_id = " << static_cast<int>(connection_id) << "\n";
    }

    stream << "    volume: " << +volume << "\n"
           << "    mute: " << +mute << "\n"
           << "    change_counter: " << +change_counter << "\n"
           << "    flags: " << +flags << "\n"
           << "    device ready: " << device_ready << "\n"
           << "    group_id: " << group_id << "\n"
           << "    connecting_actively: " << connecting_actively << "\n"
           << "    is encrypted: " << IsEncryptionEnabled() << "\n"
           << "    GATT operations initiated: " << requests_initiated << "\n"
           << "    GATT operations pending: " << handles_pending.size() << "\n";

    dprintf(fd, "%s", stream.str().c_str());
    audio_offsets.Dump(fd);
    audio_inputs.Dump(fd);
  }

  bool IsConnected() { return connection_id != GATT_INVALID_CONN_ID; }

  void Disconnect(tGATT_IF gatt_if);

  void DeregisterNotifications(tGATT_IF gatt_if);

  bool UpdateHandles(void);

  void ResetHandles(void);

  bool HasHandles(void) { return GATT_HANDLE_IS_VALID(volume_state_handle); }

  void ControlPointOperation(uint8_t opcode, const std::vector<uint8_t>* arg, GATT_WRITE_OP_CB cb,
                             void* cb_data);
  void GetExtAudioOutVolumeOffset(uint8_t ext_output_id, GATT_READ_OP_CB cb, void* cb_data);
  void SetExtAudioOutLocation(uint8_t ext_output_id, uint32_t location);
  void GetExtAudioOutLocation(uint8_t ext_output_id, GATT_READ_OP_CB cb, void* cb_data);
  void GetExtAudioOutDescription(uint8_t ext_output_id, GATT_READ_OP_CB cb, void* cb_data);
  void SetExtAudioOutDescription(uint8_t ext_output_id, const std::string& descr);
  void ExtAudioOutControlPointOperation(uint8_t ext_output_id, uint8_t opcode,
                                        const std::vector<uint8_t>* arg, GATT_WRITE_OP_CB cb,
                                        void* cb_data);
  void GetExtAudioInState(uint8_t ext_input_id, GATT_READ_OP_CB cb, void* cb_data);
  void GetExtAudioInStatus(uint8_t ext_input_id, GATT_READ_OP_CB cb, void* cb_data);
  void GetExtAudioInType(uint8_t ext_input_id, GATT_READ_OP_CB cb, void* cb_data);
  void GetExtAudioInGainProps(uint8_t ext_input_id, GATT_READ_OP_CB cb, void* cb_data);
  void GetExtAudioInDescription(uint8_t ext_input_id, GATT_READ_OP_CB cb, void* cb_data);
  void SetExtAudioInDescription(uint8_t ext_input_id, const std::string& descr);
  bool ExtAudioInControlPointOperation(uint8_t ext_input_id, uint8_t opcode,
                                       const std::vector<uint8_t>* arg, GATT_WRITE_OP_CB cb,
                                       void* cb_data);
  bool IsEncryptionEnabled();

  bool EnableEncryption();

  bool EnqueueInitialRequests(tGATT_IF gatt_if, GATT_READ_OP_CB chrc_read_cb,
                              GATT_WRITE_OP_CB cccd_write_cb);
  void EnqueueRemainingRequests(tGATT_IF gatt_if, GATT_READ_OP_CB chrc_read_cb,
                                GATT_READ_MULTI_OP_CB chrc_multi_read,
                                GATT_WRITE_OP_CB cccd_write_cb);
  bool VerifyReady();
  bool VerifyReady(uint16_t handle);
  bool IsReady() { return device_ready; }

private:
  /*
   * This is used to track the pending GATT operation handles. Once the operations are requested
   * and list is empty the device is assumed ready and connected. We are doing it because we want
   * to make sure all the required characteristics and descriptors are available on server side.
   */
  bool requests_initiated;
  std::unordered_set<uint16_t> handles_pending;

  uint16_t find_ccc_handle(uint16_t chrc_handle);
  bool set_volume_control_service_handles(const gatt::Service& service);
  void set_volume_offset_control_service_handles(const gatt::Service& service);
  void set_audio_input_control_service_handles(const gatt::Service& service);
  bool subscribe_for_notifications(tGATT_IF gatt_if, uint16_t handle, uint16_t ccc_handle,
                                   GATT_WRITE_OP_CB cb);
};

class VolumeControllerDevices {
public:
  void Add(const RawAddress& address, bool connecting_actively) {
    if (FindByAddress(address) != nullptr) {
      return;
    }

    devices_.emplace_back(address, connecting_actively);
  }

  void Remove(const RawAddress& address) {
    for (auto it = devices_.begin(); it != devices_.end(); it++) {
      if (it->address == address) {
        it = devices_.erase(it);
        break;
      }
    }
  }

  VolumeControllerDevice* FindByAddress(const RawAddress& address) {
    auto iter = std::find_if(
            devices_.begin(), devices_.end(),
            [&address](const VolumeControllerDevice& device) { return device.address == address; });

    return (iter == devices_.end()) ? nullptr : &(*iter);
  }

  VolumeControllerDevice* FindByConnId(tCONN_ID connection_id) {
    auto iter = std::find_if(devices_.begin(), devices_.end(),
                             [&connection_id](const VolumeControllerDevice& device) {
                               return device.connection_id == connection_id;
                             });

    return (iter == devices_.end()) ? nullptr : &(*iter);
  }

  std::vector<VolumeControllerDevice*> getGroupDevices(int group_id) {
    std::vector<VolumeControllerDevice*> groupDevices;
    std::for_each(devices_.begin(), devices_.end(),
                  [&groupDevices, &group_id](VolumeControllerDevice& device) {
                    if (device.group_id == group_id) {
                      groupDevices.push_back(&device);
                    }
                  });

    return groupDevices;
  }

  std::vector<RawAddress> getGroupDevicesAddresses(int group_id) {
    std::vector<RawAddress> groupDevicesAddresses;
    for (auto groupDevice : getGroupDevices(group_id)) {
      groupDevicesAddresses.push_back(groupDevice->address);
    }

    return groupDevicesAddresses;
  }

  size_t Size() { return devices_.size(); }

  void Clear() { devices_.clear(); }

  void DebugDump(int fd) {
    if (devices_.empty()) {
      dprintf(fd, "  No VC devices:\n");
    } else {
      dprintf(fd, "  Devices:\n");
      for (auto& device : devices_) {
        device.DebugDump(fd);
      }
    }
  }

  void Disconnect(tGATT_IF gatt_if) {
    for (auto& device : devices_) {
      device.Disconnect(gatt_if);
    }
  }

  void ControlPointOperation(const std::vector<RawAddress>& devices, uint8_t opcode,
                             const std::vector<uint8_t>* arg, GATT_WRITE_OP_CB cb, void* cb_data) {
    for (auto& addr : devices) {
      VolumeControllerDevice* device = FindByAddress(addr);
      if (device && device->IsConnected()) {
        device->ControlPointOperation(opcode, arg, cb, cb_data);
      }
    }
  }

private:
  std::vector<VolumeControllerDevice> devices_;
};

}  // namespace internal
}  // namespace vcp
}  // namespace bluetooth
