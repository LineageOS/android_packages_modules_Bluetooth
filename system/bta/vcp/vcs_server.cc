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

#include "vcs_server.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/memory/weak_ptr.h>
#include <bluetooth/log.h>

#include <variant>
#include <vector>

#include "bta/le_audio/common/gatt_client_data_tracker.h"
#include "bta_gatt_api.h"

using bluetooth::stack::tGATT_REQ_CBACK;

namespace bluetooth::vcs {

// Static instance lifetime management
static std::shared_ptr<VcsServer> instance = nullptr;
std::shared_ptr<VcsServer> InstantiateVcsServer() {
  if (!instance) {
    struct VcsServerImpl : public VcsServer {};
    instance = std::make_shared<VcsServerImpl>();
  }
  return instance;
}

void ReleaseVcsServer(std::shared_ptr<VcsServer> shared_instance) {
  if (instance.get() != shared_instance.get()) {
    log::error("Not a valid VcsServer instance!");
    return;
  }
  // Just let the returned shared_instance to go out of scope to release it,
  // and release the static instance.
  instance.reset();
}

// Separates the implementation details from the interface
struct VcsServer::service_impl {
  base::WeakPtrFactory<VcsServer::service_impl> weak_factory_{this};

  int server_if_ = 0;
  Callbacks* callbacks_ = nullptr;
  GattClientDataTracker<std::monostate> device_tracker_;

  ServiceDescriptor service_descriptor_;
  uint8_t volume_setting_ = 0;
  MuteState mute_state_ = MuteState::kNotMuted;
  uint8_t change_counter_ = 0;
  VolumeFlags volume_flags_;

  // Attribute handle mapping
  uint16_t service_handle_ = kGattInvalidHandle;
  uint16_t volume_state_handle_ = kGattInvalidHandle;
  uint16_t volume_state_cccd_handle_ = kGattInvalidHandle;
  uint16_t volume_control_point_handle_ = kGattInvalidHandle;
  uint16_t volume_flags_handle_ = kGattInvalidHandle;
  uint16_t volume_flags_cccd_handle_ = kGattInvalidHandle;

  service_impl() = default;
  ~service_impl() {
    if (server_if_ != 0) {
      BTA_GATTS_AppDeregister(server_if_);
    }
    log::info("VCS GATT Server deregistered.");
  }

  void RegisterGattService(const ServiceDescriptor& service_descriptor, Callbacks* callbacks) {
    if (server_if_ != 0) {
      log::error("Already registered at server_if={}", server_if_);
      return;
    }

    if (callbacks == nullptr) {
      log::error("Null callbacks provided");
      return;
    }

    if (service_descriptor.step_size == 0) {
      log::error("Invalid step_size=0");
      return;
    }

    service_descriptor_ = service_descriptor;
    callbacks_ = callbacks;
    volume_setting_ = service_descriptor.initial_volume;
    mute_state_ = service_descriptor.initial_mute_state;
    volume_flags_.bits.volume_setting_persisted =
            service_descriptor.initial_volume_setting_persisted;
    change_counter_ = 0;

    log::info(
            "Registering VCS: initial_volume={}, initial_mute_state={}, "
            "initial_volume_setting_persisted={}, step_size={}",
            service_descriptor_.initial_volume,
            static_cast<int>(service_descriptor_.initial_mute_state),
            static_cast<int>(service_descriptor_.initial_volume_setting_persisted),
            service_descriptor_.step_size);

    static bluetooth::stack::tGATT_REQ_CBACK vcs_req_cb = {
            .read_characteristic_cb = OnGattReadCharacteristicStatic,
            .read_descriptor_cb = OnGattReadDescriptorStatic,
            .write_characteristic_cb = OnGattWriteCharacteristicStatic,
            .write_descriptor_cb = OnGattWriteDescriptorStatic,
            .exec_write_cb = tGATT_REQ_CBACK::do_nothing,
            .mtu_changed_cb = tGATT_REQ_CBACK::do_nothing,
            .conf_cb = tGATT_REQ_CBACK::do_nothing,
    };
    static const stack::tGATT_CBACK vcs_ops = {
            .p_conn_cb = OnGattConnStatic,
            .p_req_cb = &vcs_req_cb,
    };

    server_if_ = BTA_GATTS_AppRegister(uuid::kVolumeControlServiceUuid, &vcs_ops, false);
    log::assert_that(server_if_ != stack::GATT_IF_INVALID, "Failed to register GATT Server");
    log::info("GATT Server Registered with server_if: {}", server_if_);

    auto gatt_db = BuildGattDatabase();

    log::info("Adding LE Audio Service {} service to GATT database.", gatt_db.begin()->uuid);
    auto status = BTA_GATTS_AddService(server_if_, &gatt_db);
    log::assert_that(status == GATT_SERVICE_STARTED, "Unable to add VCS GATT service");
    log::assert_that(gatt_db.size() != 0, "Service is empty");
    log::assert_that(gatt_db.begin()->uuid == uuid::kVolumeControlServiceUuid, "Service not mine!");

    // Find and assign handles for each characteristic and descriptor
    for (const auto& element : gatt_db) {
      if (element.type == BTGATT_DB_PRIMARY_SERVICE) {
        service_handle_ = element.attribute_handle;
      } else if (element.type == BTGATT_DB_CHARACTERISTIC) {
        if (element.uuid == uuid::kVolumeStateUuid) {
          volume_state_handle_ = element.attribute_handle;
        } else if (element.uuid == uuid::kVolumeControlPointUuid) {
          volume_control_point_handle_ = element.attribute_handle;
        } else if (element.uuid == uuid::kVolumeFlagsUuid) {
          volume_flags_handle_ = element.attribute_handle;
        }
      } else if (element.type == BTGATT_DB_DESCRIPTOR &&
                 element.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG)) {
        // The CCCD handle is the one immediately following its characteristic
        // handle. This is a safe assumption as long as the CCCD is defined
        // right after its characteristic in `BuildGattDatabase`.
        if (element.attribute_handle == volume_state_handle_ + 1) {
          volume_state_cccd_handle_ = element.attribute_handle;
        } else if (element.attribute_handle == volume_flags_handle_ + 1) {
          volume_flags_cccd_handle_ = element.attribute_handle;
        }
      }
    }

    log::info("Service handle: 0x{:04x}", service_handle_);
    log::info("volume_state_handle: 0x{:04x}", volume_state_handle_);
    log::info("volume_state_cccd_handle: 0x{:04x}", volume_state_cccd_handle_);
    log::info("volume_control_point_handle: 0x{:04x}", volume_control_point_handle_);
    log::info("volume_flags_handle: 0x{:04x}", volume_flags_handle_);
    log::info("volume_flags_cccd_handle: 0x{:04x}", volume_flags_cccd_handle_);

    callbacks_->OnVcsServerRegistered();
  }

  static void OnGattConnStatic(tGATT_IF /*server_if*/, const RawAddress& remote_bda,
                               tCONN_ID conn_id, bool connected, tGATT_DISCONN_REASON /*reason*/,
                               tBT_TRANSPORT transport) {
    if (instance) {
      if (connected) {
        instance->service_impl_->OnGattConnect(remote_bda, conn_id, transport);
      } else {
        instance->service_impl_->OnGattDisconnect(remote_bda, conn_id);
      }
    }
  }

  static void OnGattReadCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                             const RawAddress& remote_bda, uint16_t handle,
                                             uint16_t offset, bool is_long) {
    if (instance) {
      instance->service_impl_->OnGattReadCharacteristic(conn_id, trans_id, remote_bda, handle,
                                                        offset, is_long);
    }
  }

  static void OnGattWriteCharacteristicStatic(tCONN_ID conn_id, uint32_t trans_id,
                                              const RawAddress& remote_bda, uint16_t handle,
                                              uint16_t offset, bool need_rsp, bool is_prep,
                                              uint8_t* value, uint16_t len) {
    if (instance) {
      instance->service_impl_->OnGattWriteCharacteristic(conn_id, trans_id, remote_bda, handle,
                                                         offset, need_rsp, is_prep, value, len);
    }
  }

  static void OnGattReadDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                         const RawAddress& /*remote_bda*/, uint16_t handle,
                                         uint16_t offset, bool is_long) {
    if (instance) {
      instance->service_impl_->device_tracker_.OnGattReadDescriptor(conn_id, trans_id, handle,
                                                                    offset, is_long);
    }
  }

  static void OnGattWriteDescriptorStatic(tCONN_ID conn_id, uint32_t trans_id,
                                          const RawAddress& /*remote_bda*/, uint16_t handle,
                                          uint16_t offset, bool need_rsp, bool is_prep,
                                          uint8_t* value, uint16_t len) {
    if (instance) {
      instance->service_impl_->OnGattWriteDescriptor(conn_id, trans_id, handle, offset, need_rsp,
                                                     is_prep, value, len);
    }
  }

  void OnGattConnect(const RawAddress& remote_bda, tCONN_ID conn_id, tBT_TRANSPORT transport) {
    auto device = device_tracker_.OnGattConnectedEventHandler(conn_id, remote_bda, transport,
                                                              std::monostate{});
    if (device) {
      callbacks_->OnDeviceConnected(device->pseudo_addr);
    }
  }

  void OnGattDisconnect(const RawAddress& /*remote_bda*/, tCONN_ID conn_id) {
    auto device = device_tracker_.OnGattDisconnectedEventHandler(conn_id, RawAddress::kEmpty);
    if (device) {
      callbacks_->OnDeviceDisconnected(device->pseudo_addr);
    }
  }

  void OnGattWriteDescriptor(tCONN_ID conn_id, uint32_t trans_id, uint16_t handle, uint16_t offset,
                             bool need_rsp, bool is_prep, uint8_t* value, uint16_t len) {
    device_tracker_.OnGattWriteDescriptor(conn_id, trans_id, handle, offset, len, need_rsp, is_prep,
                                          value);

    auto const& device = device_tracker_.FindConnectedDevice(conn_id);
    if (device) {
      if (handle == volume_state_cccd_handle_) {
        SendVolumeStateNotificationIfNeeded(conn_id, device);
      } else if (handle == volume_flags_cccd_handle_) {
        SendVolumeFlagsNotificationIfNeeded(conn_id, device);
      }
    }
  }

  // Prepares the attribute database structure according to the requirements
  static std::vector<btgatt_db_element_t> BuildGattDatabase() {
    return {btgatt_db_element_t{.uuid = uuid::kVolumeControlServiceUuid,
                                .type = BTGATT_DB_PRIMARY_SERVICE},

            // Volume State Characteristic
            btgatt_db_element_t{.uuid = uuid::kVolumeStateUuid,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            btgatt_db_element_t{
                    .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                    .type = BTGATT_DB_DESCRIPTOR,
                    .permissions = GATT_PERM_READ_ENCRYPTED | GATT_PERM_WRITE_ENCRYPTED},

            // Volume Control Point Characteristic
            btgatt_db_element_t{.uuid = uuid::kVolumeControlPointUuid,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_WRITE,
                                .permissions = GATT_PERM_WRITE_ENCRYPTED},

            // Volume Flags Characteristic
            btgatt_db_element_t{.uuid = uuid::kVolumeFlagsUuid,
                                .type = BTGATT_DB_CHARACTERISTIC,
                                .properties = GATT_CHAR_PROP_BIT_READ | GATT_CHAR_PROP_BIT_NOTIFY,
                                .permissions = GATT_PERM_READ_ENCRYPTED},
            btgatt_db_element_t{
                    .uuid = Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG),
                    .type = BTGATT_DB_DESCRIPTOR,
                    .permissions = GATT_PERM_READ_ENCRYPTED | GATT_PERM_WRITE_ENCRYPTED}};
    }

  void OnGattReadCharacteristic(tCONN_ID conn_id, uint32_t trans_id,
                                const RawAddress& /*remote_bda*/, uint16_t handle,
                                uint16_t /*offset*/, bool /*is_long*/) {
    std::unique_ptr<tGATTS_RSP> p_msg = std::make_unique<tGATTS_RSP>();
    p_msg->attr_value.handle = handle;

    if (handle == volume_state_handle_) {
      p_msg->attr_value.len = kVolumeStateLen;
      p_msg->attr_value.value[kVolumeStateSettingIndex] = volume_setting_;
      p_msg->attr_value.value[kVolumeStateMuteIndex] = static_cast<uint8_t>(mute_state_);
      p_msg->attr_value.value[kVolumeStateChangeCounterIndex] = change_counter_;
      log::info("Reading Volume State: vol={}, mute={}, counter={}", volume_setting_,
                static_cast<int>(mute_state_), change_counter_);
    } else if (handle == volume_flags_handle_) {
      p_msg->attr_value.len = kVolumeFlagsLen;
      p_msg->attr_value.value[kVolumeFlagsIndex] = volume_flags_.raw;
      log::info("Reading Volume Flags: {}", (int)p_msg->attr_value.value[kVolumeFlagsIndex]);
    } else {
      log::warn("Unhandled read request for invalid handle: 0x{:04x}", handle);
      p_msg->attr_value.len = 0;
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_HANDLE, std::move(p_msg));
      return;
    }

    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, std::move(p_msg));
  }

  void OnGattWriteCharacteristic(tCONN_ID conn_id, uint32_t trans_id, const RawAddress& remote_bda,
                                 uint16_t handle, uint16_t /*offset*/, bool need_rsp,
                                 bool /*is_prep*/, uint8_t* value, uint16_t len) {
    log::info("From device {}, conn_id {}, handle 0x{:04x}, len {}, need_rsp {}", remote_bda,
              conn_id, handle, len, need_rsp ? "true" : "false");

    if (handle != volume_control_point_handle_) {
      log::warn("Unhandled write request for invalid handle: 0x{:04x}", handle);
      return;
    }

    if (len < kVolumeControlPointMinLen) {
      log::warn("Invalid VCP write length: {}", len);
      BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_ATTR_LEN, nullptr);
      return;
    }

    uint8_t opcode = value[kControlPointOpcodeIndex];
    uint8_t counter = value[kControlPointChangeCounterIndex];

    if (counter != change_counter_) {
      log::warn("Invalid change counter. Expected {}, got {}", change_counter_, counter);
      BTA_GATTS_SendRsp(conn_id, trans_id, VCS_INVALID_CHANGE_COUNTER, nullptr);
      return;
    }

    uint8_t new_volume = volume_setting_;
    MuteState new_mute = mute_state_;

    switch (opcode) {
      case kControlPointOpcodeRelativeVolumeDown:
        new_volume = (volume_setting_ >= service_descriptor_.step_size)
                             ? (volume_setting_ - service_descriptor_.step_size)
                             : kVolumeSettingMin;
        break;
      case kControlPointOpcodeRelativeVolumeUp:
        new_volume = (volume_setting_ <= (kVolumeSettingMax - service_descriptor_.step_size))
                             ? (volume_setting_ + service_descriptor_.step_size)
                             : kVolumeSettingMax;
        break;
      case kControlPointOpcodeUnmuteRelativeVolumeDown:
        new_mute = MuteState::kNotMuted;
        new_volume = (volume_setting_ >= service_descriptor_.step_size)
                             ? (volume_setting_ - service_descriptor_.step_size)
                             : kVolumeSettingMin;
        break;
      case kControlPointOpcodeUnmuteRelativeVolumeUp:
        new_mute = MuteState::kNotMuted;
        new_volume = (volume_setting_ <= (kVolumeSettingMax - service_descriptor_.step_size))
                             ? (volume_setting_ + service_descriptor_.step_size)
                             : kVolumeSettingMax;
        break;
      case kControlPointOpcodeSetAbsoluteVolume:
        if (len != kVolumeControlPointSetAbsoluteVolumeLen) {
          BTA_GATTS_SendRsp(conn_id, trans_id, GATT_INVALID_ATTR_LEN, nullptr);
          return;
        }
        new_volume = value[kControlPointVolumeSettingIndex];
        break;
      case kControlPointOpcodeUnmute:
        new_mute = MuteState::kNotMuted;
        break;
      case kControlPointOpcodeMute:
        new_mute = MuteState::kMuted;
        break;
      default:
        log::warn("Opcode not supported: 0x{:02x}", opcode);
        BTA_GATTS_SendRsp(conn_id, trans_id, VCS_OPCODE_NOT_SUPPORTED, nullptr);
        return;
    }

    BTA_GATTS_SendRsp(conn_id, trans_id, GATT_SUCCESS, nullptr);

    auto const& device = device_tracker_.FindConnectedDevice(conn_id);
    if (device) {
      callbacks_->OnVolumeStateChangeRequest(device->pseudo_addr, new_volume, new_mute);
    }
  }

  void UpdateVolumeState(uint8_t volume, MuteState mute_state) {
    log::info("Updating volume state to: vol={}, mute={}", volume, static_cast<int>(mute_state));
    if (volume_setting_ == volume && mute_state_ == mute_state) {
      log::debug("Volume state is already the same.");
      return;
    }

    volume_setting_ = volume;
    mute_state_ = mute_state;
    change_counter_++;

    for (auto const& [conn_id, device] : device_tracker_.GetConnectedDevices()) {
      SendVolumeStateNotificationIfNeeded(conn_id, device);
    }
  }

  void UpdateVolumeFlags(const VolumeFlags& flags) {
    log::info("Updating volume flags to: raw=0x{:02x}", flags.raw);
    if (volume_flags_.raw == flags.raw) {
      log::debug("Volume flags are already the same (0x{:02x})", flags.raw);
      return;
    }

    volume_flags_ = flags;

    for (auto const& [conn_id, device] : device_tracker_.GetConnectedDevices()) {
      SendVolumeFlagsNotificationIfNeeded(conn_id, device);
    }
  }

  void SendVolumeStateNotificationIfNeeded(
          uint16_t conn_id,
          const std::shared_ptr<GattClientDataTracker<std::monostate>::DeviceEntry>& device) {
    auto ccc_value = device->GetDescriptorValueAsU16(volume_state_cccd_handle_);
    if ((ccc_value & GATT_CLT_CONFIG_NOTIFICATION) != 0) {
      log::info("Notifying volume state to device {}", device->pseudo_addr);
      std::vector<uint8_t> value = {volume_setting_, static_cast<uint8_t>(mute_state_),
                                    change_counter_};
      BTA_GATTS_HandleValueIndication(conn_id, volume_state_handle_, value, false);
    }
  }

  void SendVolumeFlagsNotificationIfNeeded(
          uint16_t conn_id,
          const std::shared_ptr<GattClientDataTracker<std::monostate>::DeviceEntry>& device) {
    auto ccc_value = device->GetDescriptorValueAsU16(volume_flags_cccd_handle_);
    if ((ccc_value & GATT_CLT_CONFIG_NOTIFICATION) != 0) {
      log::info("Notifying volume flags to device {}", device->pseudo_addr);
      std::vector<uint8_t> value = {volume_flags_.raw};
      BTA_GATTS_HandleValueIndication(conn_id, volume_flags_handle_, value, false);
    }
  }

  void Dump(std::stringstream& stream) const {
    stream << "  Volume Control Service (VCS) Server:\n";
    stream << "    server_if: " << +server_if_ << "\n";
    stream << "    callbacks: " << (callbacks_ == nullptr ? "NOT SET" : "SET") << "\n";
    stream << "    service_handle: 0x" << std::hex << service_handle_ << "\n";
    stream << "    volume_state_handle: 0x" << std::hex << volume_state_handle_ << "\n";
    stream << "    volume_state_cccd_handle: 0x" << std::hex << volume_state_cccd_handle_ << "\n";
    stream << "    volume_control_point_handle: 0x" << std::hex << volume_control_point_handle_
           << "\n";
    stream << "    volume_flags_handle: 0x" << std::hex << volume_flags_handle_ << "\n";
    stream << "    volume_flags_cccd_handle: 0x" << std::hex << volume_flags_cccd_handle_ << "\n";
    stream << "    volume_setting: " << std::dec << +volume_setting_ << "\n";
    stream << "    mute_state: " << static_cast<int>(mute_state_) << "\n";
    stream << "    change_counter: " << std::dec << +change_counter_ << "\n";
    stream << "    volume_flags.raw: 0x" << std::hex << +volume_flags_.raw << "\n";
    stream << "    step_size: " << std::dec << +service_descriptor_.step_size << "\n";

    device_tracker_.Dump(stream, "    ",
                         [](std::stringstream& /* stream */, const std::monostate& /* device */) {
                           // No per-device data to dump for VCS yet.
                         });
  }
};

VcsServer::VcsServer() : service_impl_(std::make_unique<service_impl>()) {}

VcsServer::~VcsServer() { service_impl_.reset(); }

void VcsServer::RegisterGattService(const ServiceDescriptor& service_descriptor,
                                    Callbacks* callbacks) {
  service_impl_->RegisterGattService(service_descriptor, callbacks);
}

void VcsServer::UpdateVolumeState(uint8_t volume, MuteState mute_state) {
  service_impl_->UpdateVolumeState(volume, mute_state);
}

void VcsServer::UpdateVolumeFlags(const VolumeFlags& flags) {
  service_impl_->UpdateVolumeFlags(flags);
}

void VcsServer::Dump(std::stringstream& stream) const {
  if (service_impl_) {
    service_impl_->Dump(stream);
  }
}

}  // namespace bluetooth::vcs
