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

#pragma once

#include <bluetooth/types/address.h>
#include <hardware/bt_le_audio_server.h>

#include <memory>
#include <ranges>

#include "ascs.h"
#include "ase_state_machine.h"
#include "bta/le_audio/common/iso_app_proxy.h"

namespace bluetooth::le_audio {
struct stream_config;

class AseManager {
public:
  class Callbacks {
  public:
    Callbacks() = default;
    virtual ~Callbacks() = default;
    virtual void OnClientConnected(const RawAddress& pseudo_address) = 0;
    virtual void OnClientDisconnected(const RawAddress& pseudo_address) = 0;
    virtual void OnAsesRegistered(std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases) = 0;
    virtual void OnAllSinkAsesInIdle(const RawAddress& pseudo_addr) = 0;
    virtual void OnAllSourceAsesInIdle(const RawAddress& pseudo_addr) = 0;
    virtual std::map<uint8_t,
                     std::variant<ascs::AseStateCodecConfiguration,
                                  std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
    OnCodecConfigRequest(const RawAddress& pseudo_address,
                         const std::vector<ascs::AseCodecConfigurationReq>& requests) = 0;
    virtual std::map<uint8_t,
                     std::variant<ascs::DataPathConfiguration,
                                  std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
    OnSetQosParameters(const RawAddress& pseudo_address,
                       std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                                    ascs::AseStateQosConfiguration>>
                               params) = 0;
    virtual std::map<uint8_t,
                     std::variant<std::vector<uint8_t>,
                                  std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
    OnUpdateMetadata(const RawAddress& pseudo_address,
                     std::map<uint8_t, std::vector<uint8_t>> params) = 0;
    virtual bool IsDecodingSessionReady() const = 0;
    virtual bool IsEncodingSessionReady() const = 0;
    virtual void OnDecodingIsoChannelParametersUpdated(
            const RawAddress& pseudo_address, const tBLE_BD_ADDR address_with_type, int ase_id,
            uint16_t cis_conn_hdl,
            const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
            const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
            uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata) = 0;
    virtual void OnEncodingIsoChannelParametersUpdated(
            uint16_t cis_conn_hdl, const RawAddress& pseudo_address, const tBLE_BD_ADDR addr,
            const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
            const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
            uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata) = 0;
    virtual void OnIsoDataReceived(
            uint8_t ase_id, const bluetooth::hci::iso_manager::cis_data_evt* event,
            const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
            const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
            const std::optional<std::vector<uint8_t>>& metadata) = 0;
    virtual void OnAseEnableRequest(
            const RawAddress& address,
            const std::vector<bluetooth::le_audio::AseEnableRequest>& requests) = 0;
    virtual void OnAseStreamStarted(const RawAddress& pseudo_address, uint8_t ase_id,
                                    uint32_t audio_context_type) = 0;
    virtual void OnAseStopped(const RawAddress& pseudo_address, uint8_t ase_id) = 0;
    virtual void OnAseMetadataUpdated(const RawAddress& peer_address, uint8_t ase_id,
                                      uint32_t audio_context_type) = 0;
  };

  AseManager(std::shared_ptr<Ascs> ascs, AscsAseStateMachineFactory sm_factory,
             IsoAppProxyFactory iso_app_factory);
  virtual ~AseManager();
  virtual void Dump(std::stringstream& stream) const;

  virtual void Initialize(const Ascs::ServiceDescriptor& asc_svc_descriptor, Callbacks* callbacks);
  virtual bool IsKnownPeerDevice(const RawAddress& pseudo_address) const;
  virtual std::set<RawAddress> GetNonIdlePeerDevices() const;
  virtual bool IsActiveSinkStream(const RawAddress& pseudo_address) const;
  virtual bool IsActiveSourceStream(const RawAddress& pseudo_address) const;
  virtual bool IsSinkAse(uint8_t ase_id) const;
  virtual bool IsSourceAse(uint8_t ase_id) const;
  virtual void OnDecodingSessionReady(const RawAddress& pseudo_address);
  virtual void OnEncodingSessionReady(const RawAddress& pseudo_address);
  virtual bool ConsumeAudioData(const RawAddress& pseudo_address, uint8_t ase_id, uint8_t* data,
                                uint16_t size);
  virtual void ConfirmAseEnableRequest(const RawAddress& peer_address, bool allowed);
  virtual void ReleaseAse(const RawAddress& peer_address, uint8_t ase_id);

  struct AseFilters {
    static auto AseId(uint8_t ase_id) {
      return std::views::filter([ase_id](const std::unique_ptr<AscsAseStateMachine>& sm) {
        return sm->GetAseId() == ase_id;
      });
    }
    static auto Sink() {
      return std::views::filter(
              [](const std::unique_ptr<AscsAseStateMachine>& sm) { return !sm->IsSourceAse(); });
    }
    static auto Source() {
      return std::views::filter(
              [](const std::unique_ptr<AscsAseStateMachine>& sm) { return sm->IsSourceAse(); });
    }
    static auto StateId(AscsAseStateMachine::StateId state) {
      return std::views::filter([state](const std::unique_ptr<AscsAseStateMachine>& sm) {
        return sm->GetStateId() == state;
      });
    }
    /**
     * Filters state machines to only keep those whose state ID is in the provided list.
     */
    static auto StateIds(std::initializer_list<AscsAseStateMachine::StateId> states) {
      return std::views::filter(
              [states = std::vector(states)](const std::unique_ptr<AscsAseStateMachine>& sm) {
                // Check if the current state machine's state ID matches ANY ID in the 'states'
                // list.
                return std::any_of(states.begin(), states.end(),
                                   [&sm](AscsAseStateMachine::StateId allowed_state) {
                                     return sm->GetStateId() == allowed_state;
                                   });
              });
    }
    static auto PeerDevice(const RawAddress& pseudo_address) {
      return std::views::filter(
              [addr = pseudo_address](const std::unique_ptr<AscsAseStateMachine>& sm) {
                return sm->GetPeer() == addr;
              });
    }
    static auto IsoIds(uint8_t cig_id, uint8_t cis_id) {
      return std::views::filter([cig_id, cis_id](const std::unique_ptr<AscsAseStateMachine>& sm) {
        switch (sm->GetStateId()) {
          case AscsAseStateMachine::StateId::IDLE:
          case AscsAseStateMachine::StateId::CODEC_CONFIGURED:
            return false;
          case AscsAseStateMachine::StateId::QOS_CONFIGURED:
          case AscsAseStateMachine::StateId::ENABLING:
          case AscsAseStateMachine::StateId::DISABLING:
          case AscsAseStateMachine::StateId::STREAMING:
            return (sm->qos_configuration->cig_id == cig_id) &&
                   (sm->qos_configuration->cis_id == cis_id);
          case AscsAseStateMachine::StateId::RELEASING:
          default:
            return false;
        }
      });
    }
    static auto CisConnHandle(uint16_t conn_hdl) {
      return std::views::filter([conn_hdl](const std::unique_ptr<AscsAseStateMachine>& sm) {
        return sm->GetCisConnHandle() == conn_hdl;
      });
    }
  };

private:
  // Separates the implementation details from the interface
  struct manager_impl;
  std::unique_ptr<manager_impl> manager_impl_;
};
}  // namespace bluetooth::le_audio
