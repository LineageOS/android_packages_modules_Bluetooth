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

#include <base/functional/bind.h>
#include <bluetooth/log.h>

#include <list>
#include <memory>
#include <mutex>
#include <sstream>

#include "bta/vcp/vcs_server.h"
#include "bta_vcp_renderer_api.h"

using namespace bluetooth;

namespace bluetooth::vcp {
namespace {

class DefaultVcpServicesFactory : public VcpServicesFactory {
public:
  std::shared_ptr<vcs::VcsServer> InstantiateVcsServer() override {
    return vcs::InstantiateVcsServer();
  }
  void ReleaseVcsServer(std::shared_ptr<vcs::VcsServer> vcs) override {
    vcs::ReleaseVcsServer(std::move(vcs));
  }
};

class VolumeRendererImpl : public VolumeRenderer, public vcs::VcsServer::Callbacks {
public:
  VolumeRendererImpl(VolumeRendererCallbacks* callbacks, const VolumeRendererConfig& config,
                     VcpServicesFactory* factory)
      : callbacks_(callbacks), config_(config), factory_(factory), subservices_registered_cnt_(0) {
    if (!factory_) {
      default_factory_ = std::make_unique<DefaultVcpServicesFactory>();
      factory_ = default_factory_.get();
    }
  }

  ~VolumeRendererImpl() { Cleanup(); }

  void Initialize() {
    vcs_ = factory_->InstantiateVcsServer();
    RegisterVcs();
  }

  void OnVcsServerRegistered(void) override {
    log::info("VCS registered");
    callbacks_->OnInitialized();
  }

  void OnDeviceConnected(const RawAddress& address) override {
    log::info("address: {}", address);
    if (std::find(connected_devices_.begin(), connected_devices_.end(), address) !=
        connected_devices_.end()) {
      log::warn("Device {} already connected", address);
      return;
    }
    connected_devices_.push_back(address);
    callbacks_->OnGattConnectionStateChanged(address, GattConnectionState::CONNECTED);
  }

  void OnDeviceDisconnected(const RawAddress& address) override {
    log::info("address: {}", address);
    auto it = std::find(connected_devices_.begin(), connected_devices_.end(), address);
    if (it == connected_devices_.end()) {
      log::warn("Device {} not connected", address);
      return;
    }
    connected_devices_.erase(it);
    callbacks_->OnGattConnectionStateChanged(address, GattConnectionState::DISCONNECTED);
  }

  void OnVolumeStateChangeRequest(const RawAddress& /* address */, uint8_t volume,
                                  vcs::MuteState mute_state) override {
    log::info("Volume state changed by remote to: volume {}, mute {}", volume,
              static_cast<int>(mute_state));
    callbacks_->OnVolumeStateChangeRequest(volume, static_cast<MuteState>(mute_state));
  }

  void UpdateVolumeState(uint8_t volume, MuteState mute_state) override {
    log::info("Volume state changed by local to: volume {}, mute {}", volume,
              static_cast<int>(mute_state));
    vcs_->UpdateVolumeState(volume, static_cast<vcs::MuteState>(mute_state));
  }

  void UpdateVolumeFlags(const VolumeFlags& flags) override {
    log::info("Volume flags changed by local to: raw=0x{:02x}", flags.raw);
    vcs::VolumeFlags vcs_flags;
    vcs_flags.raw = flags.raw;
    vcs_->UpdateVolumeFlags(vcs_flags);
  }

  void Dump(int fd) {
    std::stringstream stream;
    vcs_->Dump(stream);
    dprintf(fd, "%s", stream.str().c_str());
  }

  void Cleanup() {
    log::info("");
    connected_devices_.clear();
    subservices_registered_cnt_ = 0;

    if (vcs_) {
      factory_->ReleaseVcsServer(std::move(vcs_));
      vcs_ = nullptr;
    }
  }

private:
  void RegisterVcs() {
    vcs::VcsServer::ServiceDescriptor descriptor = {
            .step_size = config_.volume_step_size,
            .initial_volume = config_.initial_volume,
            .initial_mute_state = static_cast<vcs::MuteState>(config_.initial_mute_state),
            .initial_volume_setting_persisted = static_cast<vcs::VolumeSettingPersisted>(
                    config_.initial_volume_setting_persisted)};
    vcs_->RegisterGattService(descriptor, this);
  }

  VolumeRendererCallbacks* callbacks_;
  std::shared_ptr<vcs::VcsServer> vcs_;
  const VolumeRendererConfig config_;
  VcpServicesFactory* factory_;
  std::unique_ptr<VcpServicesFactory> default_factory_;
  size_t subservices_registered_cnt_;
  std::list<RawAddress> connected_devices_;
};

std::unique_ptr<VolumeRendererImpl> instance = nullptr;
std::mutex instance_mutex;

}  // namespace

VolumeRenderer* VolumeRenderer::Get(void) {
  log::assert_that(instance != nullptr, "Volume Renderer not initialized");
  return instance.get();
}

void VolumeRenderer::Initialize(VolumeRendererCallbacks* callbacks,
                                const VolumeRendererConfig& config, VcpServicesFactory* factory) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }

  instance = std::make_unique<VolumeRendererImpl>(callbacks, config, factory);
  instance->Initialize();
}

void VolumeRenderer::Cleanup(void) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    log::error("Not initialized");
    return;
  }

  instance->Cleanup();
  instance.reset();
}

void VolumeRenderer::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  dprintf(fd, "Volume Control Profile (VCP) Renderer:\n");
  if (instance) {
    instance->Dump(fd);
  } else {
    dprintf(fd, "  Not initialized\n");
  }
  dprintf(fd, "\n");
}

}  // namespace bluetooth::vcp
