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
#include <bluetooth/types/address.h>
#include <hardware/bt_le_audio_server.h>

#include <memory>

#include "bta/le_audio/ascs/ascs.h"
#include "bta/le_audio/ascs/ase_manager.h"
#include "bta/le_audio/ascs/ase_state_machine.h"
#include "bta/le_audio/pacs/pacs.h"
#include "bta/le_audio/server/le_audio_server_config_manager.h"
#include "bta_le_audio_server_api.h"
#include "btif_common.h"
#include "btif_le_audio_peripheral.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;
using le_audio::GattConnectionState;
using le_audio::LeAudioServerCallbacks;
using le_audio::LeAudioServerInterface;

namespace {
class LeAudioServerInterfaceImpl;
std::unique_ptr<LeAudioServerInterface> leAudioServerInstance;

class LeAudioServerInterfaceImpl : public LeAudioServerInterface, public LeAudioServerCallbacks {
public:
  LeAudioServerInterfaceImpl() = default;
  ~LeAudioServerInterfaceImpl() = default;

  void OnInitialized(void) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnInitialized,
                                    weak_factory_.GetWeakPtr()));
  }

  void OnConnectionStateChanged(const RawAddress& addr, GattConnectionState state) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnConnectionStateChanged,
                                    weak_factory_.GetWeakPtr(), addr, state));
  }

  void OnStreamStartRequest(const RawAddress& address,
                            const std::vector<le_audio::AseEnableRequest>& requests) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnStreamStartRequest,
                                    weak_factory_.GetWeakPtr(), address, requests));
  }

  void OnStreamStarted(const RawAddress& address, uint8_t stream_id,
                       uint32_t audio_context_type) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnStreamStarted,
                                    weak_factory_.GetWeakPtr(), address, stream_id,
                                    audio_context_type));
  }

  void OnStreamMetadataUpdated(const RawAddress& address, uint8_t stream_id,
                               uint32_t audio_context_type) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnStreamMetadataUpdated,
                                    weak_factory_.GetWeakPtr(), address, stream_id,
                                    audio_context_type));
  }

  void OnSinkStreamReady(const RawAddress& address) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnSinkStreamReady,
                                    weak_factory_.GetWeakPtr(), address));
  }

  void OnSourceStreamReady(const RawAddress& address) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnSourceStreamReady,
                                    weak_factory_.GetWeakPtr(), address));
  }

  void OnStreamStopped(const RawAddress& address, uint8_t stream_id) override {
    do_in_jni_thread(base::BindOnce(&LeAudioServerInterfaceImpl::PostOnStreamStopped,
                                    weak_factory_.GetWeakPtr(), address, stream_id));
  }

  void Initialize(LeAudioServerCallbacks* callbacks) override {
    callbacks_ = callbacks;

    auto dependencies = std::make_unique<le_audio::LeAudioServerDependencies>();
    dependencies->pacs_factory = &bluetooth::le_audio::InstantiatePacs;
    dependencies->ascs_factory = &bluetooth::le_audio::InstantiateAscs;
    dependencies->config_manager_factory = []() {
      return std::make_shared<bluetooth::le_audio::LeAudioServerConfigManager>();
    };
    dependencies->ase_manager_factory = [](std::shared_ptr<le_audio::Ascs> ascs) {
      auto ascs_ase_sm_factory = base::BindRepeating(
              [](bool is_source_ase, uint8_t stream_id, const RawAddress& address,
                 bluetooth::le_audio::AscsAseStateMachine::ServiceCallbacks* cb) {
                return std::make_unique<bluetooth::le_audio::AscsAseStateMachine>(
                        is_source_ase, stream_id, address, cb);
              });
      auto iso_app_factory =
              base::BindRepeating([](bluetooth::hci::iso_manager::CigCallbacks* cig_callbacks) {
                return std::make_unique<bluetooth::IsoAppProxy>(cig_callbacks);
              });

      return std::make_shared<bluetooth::le_audio::AseManager>(ascs, std::move(ascs_ase_sm_factory),
                                                               std::move(iso_app_factory));
    };
    dependencies->peripheral_audio_session_factory = []() { return nullptr; };
    dependencies->peripheral_audio_provider_factory = []() { return nullptr; };

    do_in_main_thread(base::BindOnce(&bluetooth::le_audio::LeAudioServer::Initialize, this,
                                     std::move(dependencies)));
  }

  void InternalCleanup(void) {
    config_provider_.reset();
    ase_manager_.reset();
  }

  void Cleanup(void) override {
    do_in_main_thread(base::Bind(&bluetooth::le_audio::LeAudioServer::Cleanup));
    do_in_main_thread(base::Bind(&bluetooth::le_audio::ReleasePacs, std::move(pacs_)));
    do_in_main_thread(base::Bind(&bluetooth::le_audio::ReleaseAscs, std::move(ascs_)));
    do_in_main_thread(
            base::Bind(&LeAudioServerInterfaceImpl::InternalCleanup, weak_factory_.GetWeakPtr()));
  }

  void ConfirmStreamStartRequest(const RawAddress& peer_address, bool allowed) override {
    do_in_main_thread(base::BindOnce(&bluetooth::le_audio::LeAudioServer::ConfirmStreamStartRequest,
                                     peer_address, allowed));
  }

  void StopStream(const RawAddress& peer_address, uint8_t stream_id) override {
    do_in_main_thread(base::BindOnce(&bluetooth::le_audio::LeAudioServer::StopStream, peer_address,
                                     stream_id));
  }

  void DebugDump(int fd) { bluetooth::le_audio::LeAudioServer::DebugDump(fd); }

private:
  void PostOnInitialized() { callbacks_->OnInitialized(); }

  void PostOnConnectionStateChanged(const RawAddress& addr, GattConnectionState state) {
    callbacks_->OnConnectionStateChanged(addr, state);
  }

  void PostOnStreamStartRequest(const RawAddress& address,
                                const std::vector<le_audio::AseEnableRequest>& requests) {
    callbacks_->OnStreamStartRequest(address, requests);
  }

  void PostOnStreamStarted(const RawAddress& address, uint8_t stream_id,
                           uint32_t audio_context_type) {
    callbacks_->OnStreamStarted(address, stream_id, audio_context_type);
  }

  void PostOnStreamMetadataUpdated(const RawAddress& address, uint8_t stream_id,
                                   uint32_t audio_context_type) {
    callbacks_->OnStreamMetadataUpdated(address, stream_id, audio_context_type);
  }

  void PostOnSinkStreamReady(const RawAddress& address) { callbacks_->OnSinkStreamReady(address); }

  void PostOnSourceStreamReady(const RawAddress& address) {
    callbacks_->OnSourceStreamReady(address);
  }

  void PostOnStreamStopped(const RawAddress& address, uint8_t stream_id) {
    callbacks_->OnStreamStopped(address, stream_id);
  }

  LeAudioServerCallbacks* callbacks_;
  std::shared_ptr<bluetooth::le_audio::LeAudioServerConfigManager> config_provider_;
  std::shared_ptr<bluetooth::le_audio::Pacs> pacs_;
  std::shared_ptr<bluetooth::le_audio::Ascs> ascs_;
  std::shared_ptr<bluetooth::le_audio::AseManager> ase_manager_;

  base::WeakPtrFactory<LeAudioServerInterfaceImpl> weak_factory_{this};
};

} /* namespace */

LeAudioServerInterface* btif_le_audio_server_get_interface() {
  if (!leAudioServerInstance) {
    leAudioServerInstance.reset(new LeAudioServerInterfaceImpl());
  }

  return leAudioServerInstance.get();
}

void btif_debug_le_audio_server_dump(int fd) {
  if (leAudioServerInstance) {
    static_cast<LeAudioServerInterfaceImpl*>(leAudioServerInstance.get())->DebugDump(fd);
  }
}
