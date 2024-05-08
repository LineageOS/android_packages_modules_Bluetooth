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

#include "gd/rust/topshim/vc/vc_shim.h"

#include <bluetooth/log.h>
#include <hardware/bluetooth.h>

#include <string>

#include "os/log.h"
#include "src/profiles/vc.rs.h"
#include "types/raw_address.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {
namespace internal {

static VolumeControlIntf* g_vc_if;

static BtVcConnectionState to_rust_btvc_connection_state(vc::ConnectionState state) {
  switch (state) {
    case vc::ConnectionState::DISCONNECTED:
      return BtVcConnectionState::Disconnected;
    case vc::ConnectionState::CONNECTING:
      return BtVcConnectionState::Connecting;
    case vc::ConnectionState::CONNECTED:
      return BtVcConnectionState::Connected;
    case vc::ConnectionState::DISCONNECTING:
      return BtVcConnectionState::Disconnecting;
    default:
      log::assert_that(false, "Unhandled enum value from C++");
  }
  return BtVcConnectionState{};
}

static void connection_state_cb(vc::ConnectionState state, const RawAddress& address) {
  vc_connection_state_callback(to_rust_btvc_connection_state(state), address);
}

static void volume_state_cb(
    const RawAddress& address, uint8_t volume, bool mute, bool is_autonomous) {
  vc_volume_state_callback(address, volume, mute, is_autonomous);
}

static void group_volume_state_cb(int group_id, uint8_t volume, bool mute, bool is_autonomous) {
  vc_group_volume_state_callback(group_id, volume, mute, is_autonomous);
}

static void device_available_cb(const RawAddress& address, uint8_t num_offset) {
  vc_device_available_callback(address, num_offset);
}

static void ext_audio_out_volume_offset_cb(
    const RawAddress& address, uint8_t ext_output_id, int16_t offset) {
  vc_ext_audio_out_volume_offset_callback(address, ext_output_id, offset);
}

static void ext_audio_out_location_cb(
    const RawAddress& address, uint8_t ext_output_id, uint32_t location) {
  vc_ext_audio_out_location_callback(address, ext_output_id, location);
}

static void ext_audio_out_description_cb(
    const RawAddress& address, uint8_t ext_output_id, std::string descr) {
  vc_ext_audio_out_description_callback(address, ext_output_id, descr);
}

}  // namespace internal

class DBusVolumeControlCallbacks : public vc::VolumeControlCallbacks {
 public:
  static vc::VolumeControlCallbacks* GetInstance() {
    static auto instance = new DBusVolumeControlCallbacks();
    return instance;
  }

  DBusVolumeControlCallbacks(){};

  void OnConnectionState(vc::ConnectionState state, const RawAddress& address) override {
    log::info("state={}, address={}", static_cast<int>(state), ADDRESS_TO_LOGGABLE_CSTR(address));
    topshim::rust::internal::connection_state_cb(state, address);
  }

  void OnVolumeStateChanged(
      const RawAddress& address, uint8_t volume, bool mute, bool is_autonomous) override {
    log::info(
        "address={}, volume={}, mute={}, is_autonomous={}",
        ADDRESS_TO_LOGGABLE_CSTR(address),
        volume,
        mute,
        is_autonomous);
    topshim::rust::internal::volume_state_cb(address, volume, mute, is_autonomous);
  }

  void OnGroupVolumeStateChanged(
      int group_id, uint8_t volume, bool mute, bool is_autonomous) override {
    log::info(
        "group_id={}, volume={}, mute={}, is_autonomous={}", group_id, volume, mute, is_autonomous);
    topshim::rust::internal::group_volume_state_cb(group_id, volume, mute, is_autonomous);
  }

  void OnDeviceAvailable(const RawAddress& address, uint8_t num_offset) override {
    log::info("address={}, num_offset={}", ADDRESS_TO_LOGGABLE_CSTR(address), num_offset);
    topshim::rust::internal::device_available_cb(address, num_offset);
  }

  void OnExtAudioOutVolumeOffsetChanged(
      const RawAddress& address, uint8_t ext_output_id, int16_t offset) override {
    log::info(
        "address={}, ext_output_id={}, offset={}",
        ADDRESS_TO_LOGGABLE_CSTR(address),
        ext_output_id,
        offset);
    topshim::rust::internal::ext_audio_out_volume_offset_cb(address, ext_output_id, offset);
  }

  void OnExtAudioOutLocationChanged(
      const RawAddress& address, uint8_t ext_output_id, uint32_t location) override {
    log::info(
        "address={}, ext_output_id, location={}",
        ADDRESS_TO_LOGGABLE_CSTR(address),
        ext_output_id,
        location);
    topshim::rust::internal::ext_audio_out_location_cb(address, ext_output_id, location);
  }

  void OnExtAudioOutDescriptionChanged(
      const RawAddress& address, uint8_t ext_output_id, std::string descr) override {
    log::info(
        "address={}, ext_output_id={}, descr={}",
        ADDRESS_TO_LOGGABLE_CSTR(address),
        ext_output_id,
        descr.c_str());
    topshim::rust::internal::ext_audio_out_description_cb(address, ext_output_id, descr);
  }
};

std::unique_ptr<VolumeControlIntf> GetVolumeControlProfile(const unsigned char* btif) {
  if (internal::g_vc_if) std::abort();

  const bt_interface_t* btif_ = reinterpret_cast<const bt_interface_t*>(btif);

  auto vc_if = std::make_unique<VolumeControlIntf>(
      const_cast<vc::VolumeControlInterface*>(reinterpret_cast<const vc::VolumeControlInterface*>(
          btif_->get_profile_interface("volume_control"))));

  internal::g_vc_if = vc_if.get();

  return vc_if;
}

void VolumeControlIntf::init(/*VolumeControlCallbacks* callbacks*/) {
  return intf_->Init(DBusVolumeControlCallbacks::GetInstance());
}

void VolumeControlIntf::cleanup() {
  return intf_->Cleanup();
}

void VolumeControlIntf::connect(RawAddress addr) {
  return intf_->Connect(addr);
}

void VolumeControlIntf::disconnect(RawAddress addr) {
  return intf_->Disconnect(addr);
}

void VolumeControlIntf::remove_device(RawAddress addr) {
  return intf_->RemoveDevice(addr);
}

void VolumeControlIntf::set_volume(int group_id, uint8_t volume) {
  return intf_->SetVolume(group_id, volume);
}

void VolumeControlIntf::mute(RawAddress addr) {
  return intf_->Mute(addr);
}

void VolumeControlIntf::unmute(RawAddress addr) {
  return intf_->Unmute(addr);
}

void VolumeControlIntf::get_ext_audio_out_volume_offset(RawAddress addr, uint8_t ext_output_id) {
  return intf_->GetExtAudioOutVolumeOffset(addr, ext_output_id);
}

void VolumeControlIntf::set_ext_audio_out_volume_offset(
    RawAddress addr, uint8_t ext_output_id, int16_t offset_val) {
  return intf_->SetExtAudioOutVolumeOffset(addr, ext_output_id, offset_val);
}

void VolumeControlIntf::get_ext_audio_out_location(RawAddress addr, uint8_t ext_output_id) {
  return intf_->GetExtAudioOutLocation(addr, ext_output_id);
}

void VolumeControlIntf::set_ext_audio_out_location(
    RawAddress addr, uint8_t ext_output_id, uint32_t location) {
  return intf_->SetExtAudioOutLocation(addr, ext_output_id, location);
}

void VolumeControlIntf::get_ext_audio_out_description(RawAddress addr, uint8_t ext_output_id) {
  return intf_->GetExtAudioOutDescription(addr, ext_output_id);
}

void VolumeControlIntf::set_ext_audio_out_description(
    RawAddress addr, uint8_t ext_output_id, const char* descr) {
  return intf_->SetExtAudioOutDescription(addr, ext_output_id, std::string(descr));
}
}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
