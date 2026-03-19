/*
 * Copyright 2026 The Android Open Source Project
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
#include <bluetooth/types/address.h>
#include <hardware/bt_le_audio.h>

#include <memory>
#include <sstream>
#include <vector>

#include "bta/include/bta_le_audio_api.h"
#include "bta/include/bta_le_audio_server_api.h"
#include "bta/le_audio/ascs/ascs.h"
#include "bta/le_audio/ascs/ase_manager.h"
#include "bta/le_audio/ascs/ase_state_machine.h"
#include "bta/le_audio/common/iso_app_proxy.h"
#include "bta/le_audio/common/le_audio_event_tracker.h"
#include "bta/le_audio/pacs/pacs.h"
#include "bta/le_audio/server/le_audio_server_config_manager.h"
#include "test/common/mock_functions.h"

namespace bluetooth::le_audio {
std::shared_ptr<Pacs> InstantiatePacs() {
  inc_func_call_count(__func__);
  return nullptr;
}
void ReleasePacs(std::shared_ptr<Pacs>) { inc_func_call_count(__func__); }

std::shared_ptr<Ascs> InstantiateAscs() {
  inc_func_call_count(__func__);
  return nullptr;
}
void ReleaseAscs(const std::shared_ptr<Ascs>) { inc_func_call_count(__func__); }

void LeAudioServer::Initialize(le_audio::LeAudioServerCallbacks*,
                               std::unique_ptr<LeAudioServerDependencies>) {
  inc_func_call_count(__func__);
}

void LeAudioServer::Cleanup(void) { inc_func_call_count(__func__); }
LeAudioServer* LeAudioServer::Get(void) {
  inc_func_call_count(__func__);
  return nullptr;
}
void LeAudioServer::DebugDump(int) { inc_func_call_count(__func__); }
void LeAudioServer::ConfirmStreamStartRequest(const RawAddress&, bool) {
  inc_func_call_count(__func__);
}
void LeAudioServer::StopStream(const RawAddress&, uint8_t) { inc_func_call_count(__func__); }

struct AseManager::AseManager::manager_impl {};

le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>
LeAudioServerConfigManager::GetAvailableAudioContexts(const RawAddress&) {
  inc_func_call_count(__func__);
  return le_audio::types::BidirectionalPair<le_audio::types::AudioContexts>();
}

LeAudioServerConfigManager::LeAudioServerConfigManager() {}
LeAudioServerConfigManager::~LeAudioServerConfigManager() {}

Pacs::ServiceDescriptor LeAudioServerConfigManager::GetPacsDescriptor(
        const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>&,
        const std::vector<::bluetooth::audio::le_audio::AudioHalCapability>&) {
  inc_func_call_count(__func__);
  return Pacs::ServiceDescriptor();
}

Ascs::ServiceDescriptor LeAudioServerConfigManager::GetAscsDescriptor() {
  inc_func_call_count(__func__);
  return {};
}

LeAudioCodecConfiguration LeAudioServerConfigManager::GetAudioSessionConfig() {
  inc_func_call_count(__func__);
  return {};
}

std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                               std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
LeAudioServerConfigManager::VerifyQosParameters(
        std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                     ascs::AseStateQosConfiguration>>) {
  inc_func_call_count(__func__);
  return {};
}

AseManager::~AseManager() = default;
AseManager::AseManager(std::shared_ptr<Ascs>, AscsAseStateMachineFactory, IsoAppProxyFactory) {
  inc_func_call_count(__func__);
}
void AseManager::Initialize(Ascs::ServiceDescriptor const&, Callbacks*) {
  inc_func_call_count(__func__);
}
void AseManager::Dump(std::stringstream&) const { inc_func_call_count(__func__); }
bool AseManager::IsKnownPeerDevice(const RawAddress&) const { return false; }
std::set<RawAddress> AseManager::GetNonIdlePeerDevices() const {
  inc_func_call_count(__func__);
  return {};
}
bool AseManager::IsActiveSinkStream(const RawAddress&) const { return false; }
bool AseManager::IsActiveSourceStream(const RawAddress&) const { return false; }
bool AseManager::IsSinkAse(uint8_t) const { return false; }
bool AseManager::IsSourceAse(uint8_t) const { return false; }
void AseManager::OnDecodingSessionReady(const RawAddress&) { inc_func_call_count(__func__); }
void AseManager::OnEncodingSessionReady(const RawAddress&) { inc_func_call_count(__func__); }
bool AseManager::ConsumeAudioData(const RawAddress&, uint8_t, uint8_t*, uint16_t) {
  inc_func_call_count(__func__);
  return false;
}
void AseManager::ConfirmAseEnableRequest(const RawAddress&, bool) { inc_func_call_count(__func__); }
void AseManager::ReleaseAse(const RawAddress&, uint8_t) { inc_func_call_count(__func__); }

void AscsAseStateMachine::StateIdle::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateIdle::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateIdle::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateCodecConfigured::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateCodecConfigured::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateCodecConfigured::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateQosConfigured::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateQosConfigured::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateQosConfigured::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateEnabling::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateEnabling::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateEnabling::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateDisabling::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateDisabling::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateDisabling::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateStreaming::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateStreaming::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateStreaming::ProcessEvent(unsigned int, void*) { return false; }
void AscsAseStateMachine::StateReleasing::OnEnter() { inc_func_call_count(__func__); }
void AscsAseStateMachine::StateReleasing::OnExit() { inc_func_call_count(__func__); }
bool AscsAseStateMachine::StateReleasing::ProcessEvent(unsigned int, void*) { return false; }

}  // namespace bluetooth::le_audio

namespace bluetooth {

class IsoAppProxy::impl {};

IsoAppProxy::~IsoAppProxy() = default;
IsoAppProxy::IsoAppProxy(hci::iso_manager::CigCallbacks*, hci::iso_manager::BigCallbacks*,
                         std::function<void(bool)>) {
  inc_func_call_count(__func__);
}
void IsoAppProxy::AddIncomingCisEventsListener(const RawAddress&, uint8_t, uint8_t) {
  inc_func_call_count(__func__);
}
void IsoAppProxy::RemoveIncomingCisEventsListener(const RawAddress&, uint8_t, uint8_t) {
  inc_func_call_count(__func__);
}
void IsoAppProxy::SetupIsoDataPath(uint16_t, hci::iso_manager::iso_data_path_params) {
  inc_func_call_count(__func__);
}
void IsoAppProxy::RemoveIsoDataPath(uint16_t, uint8_t) { inc_func_call_count(__func__); }
void IsoAppProxy::SendIsoData(uint16_t, uint8_t const*, uint16_t) { inc_func_call_count(__func__); }
void IsoAppProxy::AcceptIncomingCisConnection(uint16_t) { inc_func_call_count(__func__); }
void IsoAppProxy::RejectIncomingCisConnection(uint16_t, uint8_t) { inc_func_call_count(__func__); }
bool IsoAppProxy::HasCisConnected(uint16_t) const {
  inc_func_call_count(__func__);
  return false;
}

std::shared_ptr<LeAudioEventTracker>& LeAudioEventTracker::GetLeAudioSinkInstance() {
  inc_func_call_count(__func__);
  static auto ptr = std::shared_ptr<LeAudioEventTracker>(nullptr);
  return ptr;
}

}  // namespace bluetooth
