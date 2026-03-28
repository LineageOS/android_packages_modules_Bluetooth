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

#include "ase_manager.h"

#include <android_bluetooth_sysprop.h>
#include <hardware/bt_le_audio_server.h>

#include <bitset>

#include "ase_state_machine.h"
#include "bluetooth/types/address.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/le_audio/common/le_audio_event_tracker.h"
#include "bta/le_audio/le_audio_types.h"
#include "hci/controller.h"
#include "hci/hci_packets.h"
#include "main/shim/entry.h"
#include "osi/include/alarm.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/hci_error_code.h"

using namespace std::chrono_literals;
using namespace std::placeholders;

namespace bluetooth::le_audio {
static const char* EVT_LOG_ISO_TAG = "ASE Manager";

struct AseManager::manager_impl : public hci::iso_manager::CigCallbacks,
                                  public Ascs::Callbacks,
                                  public AscsAseStateMachine::ServiceCallbacks {
  struct PendingEnableOperation {
    std::unique_ptr<alarm_t, decltype(&alarm_free)> timeout_timer{alarm_new("ase_enable_timeout"),
                                                                  &alarm_free};
    std::vector<ascs::AseEnableReq> initial_request;
  };

  struct TimeoutCbContext {
    base::WeakPtr<manager_impl> manager_weak_ptr;
    RawAddress peer_address;
  };

  static void on_enable_request_timeout_callback(void* data) {
    log::assert_that(data != nullptr, "data should not be null");
    std::unique_ptr<TimeoutCbContext> context(static_cast<TimeoutCbContext*>(data));

    if (context->manager_weak_ptr) {
      context->manager_weak_ptr->OnEnableRequestTimeout(context->peer_address);
    } else {
      log::warn("Manager expired before timeout for {}", context->peer_address);
    }
  }

  manager_impl(std::shared_ptr<Ascs> ascs, AscsAseStateMachineFactory sm_factory,
               IsoAppProxyFactory iso_app_factory)
      : weak_factory_(this) {
    ascs_ = std::move(ascs);
    sm_factory_ = std::move(sm_factory);
    iso_app_ = iso_app_factory.Run(this);

    event_tracker_ = LeAudioEventTracker::GetLeAudioSinkInstance();
  }

  ~manager_impl() {
    log::debug("");
    iso_app_.reset();
    ascs_.reset();

    ase_state_machines_.clear();
    ase_ids_.sink.clear();
    ase_ids_.source.clear();
  }

  void Initialize(const Ascs::ServiceDescriptor& asc_svc_descriptor,
                  AseManager::Callbacks* callbacks) {
    log::assert_that(callbacks != nullptr, "Invalid callbacks!");

    app_callbacks_ = callbacks;
    ascs_->RegisterGattService(asc_svc_descriptor, this);
  }

  void OnEnableRequestTimeout(const RawAddress& peer_address) {
    log::info("Timeout for device {}", peer_address);
    if (pending_enable_operations_.count(peer_address) == 0) {
      log::warn("No pending operation for device {}", peer_address);
      return;
    }

    auto& pending_op = pending_enable_operations_.at(peer_address);

    Ascs::AseCtpResponse response_denial{
            .opcode = ascs::AseCtpOpcode::ENABLE,
    };
    for (const auto& req : pending_op.initial_request) {
      response_denial.entries.emplace_back(req.ase_id, ascs::AseCtpResponseCode::UNSPECIFIED_ERROR,
                                           ascs::AseCtpResponseReason::NO_REASON);
    }
    ascs_->AseCtpRequestResponse(peer_address, response_denial);

    pending_enable_operations_.erase(peer_address);
  }

  bool IsKnownPeerDevice(const RawAddress& pseudo_addr) const {
    return ascs_->GetConnectionId(pseudo_addr) != GATT_INVALID_CONN_ID;
  }

  std::set<RawAddress> GetNonIdlePeerDevices() const {
    std::set<RawAddress> devices;
    for (auto const& sm : ase_state_machines_) {
      devices.insert(sm->GetPeer());
    }
    return devices;
  }

  template <typename T>
  AscsAseStateMachine* GetStateMachineFiltered(T&& range) const {
    auto view = ase_state_machines_ | range | std::views::take(1);
    return view.begin() != view.end() ? (*view.begin()).get() : nullptr;
  }

  template <typename T>
  auto GetStateMachinesFiltered(T&& range) {
    return ase_state_machines_ | range |
           std::views::transform(
                   [](const auto& sm_ptr) -> AscsAseStateMachine* { return sm_ptr.get(); });
  }

  bool IsActiveSinkStream(const RawAddress& pseudo_addr) const {
    return GetStateMachineFiltered(AseFilters::PeerDevice(pseudo_addr) | AseFilters::Sink() |
                                   AseFilters::StateId(AscsAseStateMachine::StateId::STREAMING)) !=
           nullptr;
  }

  bool IsActiveSourceStream(const RawAddress& pseudo_addr) const {
    return GetStateMachineFiltered(AseFilters::PeerDevice(pseudo_addr) | AseFilters::Source() |
                                   AseFilters::StateId(AscsAseStateMachine::StateId::STREAMING)) !=
           nullptr;
  }

  bool AreAllSinkAsesInState(const RawAddress& address, AscsAseStateMachine::StateId state) {
    auto not_idling_sm = std::find_if(
            ase_state_machines_.begin(), ase_state_machines_.end(), [&](auto const& sm) {
              return (sm->GetPeer() == address) && sm->IsSinkAse() && (sm->GetStateId() != state);
            });
    return not_idling_sm == ase_state_machines_.end();
  }

  bool AreAllSourceAsesInState(const RawAddress& address, AscsAseStateMachine::StateId state) {
    auto not_idling_sm = std::find_if(
            ase_state_machines_.begin(), ase_state_machines_.end(), [&](auto const& sm) {
              return (sm->GetPeer() == address) && sm->IsSourceAse() && (sm->GetStateId() != state);
            });
    return not_idling_sm == ase_state_machines_.end();
  }

  AscsAseStateMachine* TryGetOrCreateStateMachine(const RawAddress& address, uint8_t ase_id) {
    // Note: The existing policy allows a single peer to own an ASE. The already owned ASE
    //       cannot be configured by another peer even though it looks unconfigured to him.
    auto sm_ptr =
            GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
    if (sm_ptr == nullptr) {
      // Instantiate the state machine - acquired by this peer device
      log::verbose("Instantiating ASE: {} state machine for: {}", ase_id, address);

      auto sm = sm_factory_.Run(IsSourceAse(ase_id), ase_id, address, this);
      sm->Start();
      ase_state_machines_.push_back(std::move(sm));
    }

    log::verbose("Found ASE: {} state machine for: {}", ase_id, address);
    return GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
  }

  Ascs::AseState GetCurrentAseState(AscsAseStateMachine* sm) {
    auto ase_state_id = sm->GetStateId();

    Ascs::AseState current_ase_state;
    current_ase_state.state = static_cast<ascs::AseState>(ase_state_id);

    switch (ase_state_id) {
      case AscsAseStateMachine::StateId::IDLE:
      case AscsAseStateMachine::StateId::RELEASING:
        current_ase_state.state_params = std::monostate();
        break;
      case AscsAseStateMachine::StateId::CODEC_CONFIGURED:
        log::assert_that(sm->codec_configuration.has_value(), "Missing codec configuration");
        current_ase_state.state_params = sm->codec_configuration.value();
        break;
      case AscsAseStateMachine::StateId::QOS_CONFIGURED:
        log::assert_that(sm->qos_configuration.has_value(), "Missing qos configuration");
        current_ase_state.state_params = sm->qos_configuration.value();
        break;
      case AscsAseStateMachine::StateId::ENABLING:
      case AscsAseStateMachine::StateId::STREAMING:
      case AscsAseStateMachine::StateId::DISABLING:
        log::assert_that(sm->qos_configuration.has_value(), "Missing qos configuration");
        log::assert_that(sm->metadata.has_value(), "Missing metadata");
        current_ase_state.state_params = ascs::AseStateTransientParams{
                .cig_id = sm->qos_configuration->cig_id,
                .cis_id = sm->qos_configuration->cis_id,
                .metadata = sm->metadata.value(),
        };
        break;
    }

    return current_ase_state;
  }

  Ascs::AseState OnGetAseState(const RawAddress& address, uint8_t ase_id) override {
    auto* sm = GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
    if (sm == nullptr) {
      return {.state = ascs::AseState::IDLE, .state_params = std::monostate{}};
    }

    log::info("Providing ASE Id: {} State: {}, peer: {}", ase_id, sm->GetStateId(), address);
    return GetCurrentAseState(sm);
  }

  void OnAscsRegistered(std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases) override {
    log::info("Sink ASEs : {}, Source ASEs : {}", sink_ases.size(), source_ases.size());
    ase_ids_.sink = sink_ases;
    ase_ids_.source = source_ases;

    app_callbacks_->OnAsesRegistered(sink_ases, source_ases);
  }

  void OnDeviceConnected(const RawAddress& address) override {
    log::info("Address: {}", address);

    app_callbacks_->OnClientConnected(address);
  }

  void OnDeviceDisconnected(const RawAddress& pseudo_addr) override {
    log::info("Address: {}", pseudo_addr);

    if (pending_enable_operations_.count(pseudo_addr)) {
      log::info("Cleaning up pending operation for device {}", pseudo_addr);
      alarm_cancel(pending_enable_operations_.at(pseudo_addr).timeout_timer.get());
      pending_enable_operations_.erase(pseudo_addr);
    }

    app_callbacks_->OnClientDisconnected(pseudo_addr);

    // RemoveStateMachines
    std::erase_if(ase_state_machines_, [&pseudo_addr](const auto& sm) {
      if (sm->GetPeer() == pseudo_addr) {
        log::debug("Removing ASE state machine for: {}, ase_id: {}", pseudo_addr, sm->GetAseId());
        return true;
      }
      return false;
    });

    // No need to notify ASE state change to peer device, as it was disconnected
    // Notify just the upper layer
    if (AreAllSinkAsesInState(pseudo_addr, AscsAseStateMachine::StateId::IDLE)) {
      app_callbacks_->OnAllSinkAsesInIdle(pseudo_addr);
    }
    if (AreAllSourceAsesInState(pseudo_addr, AscsAseStateMachine::StateId::IDLE)) {
      app_callbacks_->OnAllSourceAsesInIdle(pseudo_addr);
    }
  }

  inline bool IsSinkAse(uint8_t ase_id) const {
    return std::find(ase_ids_.sink.begin(), ase_ids_.sink.end(), ase_id) != ase_ids_.sink.end();
  }

  inline bool IsSourceAse(uint8_t ase_id) const {
    return std::find(ase_ids_.source.begin(), ase_ids_.source.end(), ase_id) !=
           ase_ids_.source.end();
  }

  void OnDecodingSessionReady(const RawAddress& pseudo_address) {
    log::debug("Address: {}", pseudo_address);

    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "OnDecodingSessionReady peer: {}", pseudo_address);

    // Let all the sink ASEs waiting in ENABLING state, know that the audio receiver is ready
    for (auto* sm : GetStateMachinesFiltered(
                 AseManager::AseFilters::PeerDevice(pseudo_address) |
                 AseManager::AseFilters::Sink() |
                 AseManager::AseFilters::StateId(AscsAseStateMachine::StateId::ENABLING))) {
      // Notify only those that have their CISes established
      if (iso_app_->HasCisConnected(sm->GetCisConnHandle())) {
        if (!sm->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_START_READY, nullptr)) {
          log::warn("Invalid state transition for {}", pseudo_address);
        }
      }
    }
  }

  void OnEncodingSessionReady(const RawAddress& pseudo_address) {
    log::debug("Address: {}", pseudo_address);

    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "NotifyEncodingSessionReady peer: {}", pseudo_address);
    // Note: Not much to do here, since all the source ASEs waiting in ENABLING state will
    // transition to STREAMING once the remote peer notifies rediness to receive the audio.
  }

  bool ConsumeAudioData(const RawAddress& /*pseudo_addr*/, uint8_t /*ase_id*/, uint8_t* /*data*/,
                        uint16_t /*size*/) {
    log::error("Not supported!");
    // TODO: Find the ASE and the related cis_con_hdl, then send the data over the HCI
    // Note: This is relevant only for the SW encoding path, which we do not support.
    return false;
  }

  void ConfigureDataPath(hci_data_direction_t direction, uint8_t dataPathId,
                         std::vector<uint8_t> dataPathConfig) const {
    if (!bluetooth::shim::GetController()->IsSupported(
                bluetooth::hci::OpCode::CONFIGURE_DATA_PATH)) {
      log::warn("Controller does not support config data path command");
      return;
    }

    log::debug("direction: {}, dataPathId: {}", static_cast<int>(direction), +dataPathId);

    // Avoid reconfiguring to the same data path
    auto it = configured_data_path_.find(direction);
    if (it != configured_data_path_.end()) {
      auto& dataPath = it->second;
      if ((dataPath.first == dataPathId) && (dataPath.second == dataPathConfig)) {
        log::debug("Data path for direction {} already configured to {}",
                   static_cast<int>(direction), +dataPathId);
        return;
      }
    }

    bluetooth::legacy::hci::GetInterface().ConfigureDataPath(direction, dataPathId, dataPathConfig);
  }

  bluetooth::hci::iso_manager::iso_data_path_params GetIsoDataPathParams(
          ascs::DataPathConfiguration const& data_path_cfg, bool is_sink_ase) const {
    bluetooth::hci::iso_manager::iso_data_path_params param = {
            .data_path_dir = is_sink_ase ? bluetooth::hci::iso_manager::kIsoDataPathDirectionOut
                                         : bluetooth::hci::iso_manager::kIsoDataPathDirectionIn,
            .data_path_id = data_path_cfg.dataPathId,
            .codec_id_format = data_path_cfg.isoDataPathConfig.codecId.coding_format,
            .codec_id_company = data_path_cfg.isoDataPathConfig.codecId.vendor_company_id,
            .codec_id_vendor = data_path_cfg.isoDataPathConfig.codecId.vendor_codec_id,
            .controller_delay = data_path_cfg.isoDataPathConfig.controllerDelayUs,
            .codec_conf = data_path_cfg.isoDataPathConfig.configuration,
    };
    return param;
  }

  void OnSetupIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    log::debug("CIG: {}, conn_hdl: {}, status: {}", cig_id, conn_handle,
               hci_status_code_text((tHCI_STATUS)(status)));
  }

  void OnRemoveIsoDataPath(uint8_t status, uint16_t conn_handle, uint8_t cig_id) override {
    log::debug("CIG: {}, conn_hdl: {}, status: {}", cig_id, conn_handle,
               hci_status_code_text((tHCI_STATUS)(status)));
  }

  void OnIsoLinkQualityRead(uint16_t /*conn_handle*/, uint8_t /*cig_id*/,
                            uint32_t /*tx_unacked_packets*/, uint32_t /*tx_flushed_packets*/,
                            uint32_t /*tx_last_subevent_packets*/,
                            uint32_t /*retransmitted_packets*/, uint32_t /*crc_error_packets*/,
                            uint32_t /*rx_unreceived_packets*/,
                            uint32_t /*duplicate_packets*/) override {
    log::debug("Not implemented");
  }

  void OnCigEvent(uint8_t /*event_type*/, void* /*data*/) override {
    log::debug("Not implemented");
  }

  void OnCisRequestEvent(const hci::iso_manager::cis_request_evt* evt) {
    auto* p_device = btm_find_dev_by_handle(evt->acl_conn_hdl);
    log::assert_that(p_device, "Missing security record for acl handle: 0x{:04x}",
                     evt->acl_conn_hdl);
    log::debug("CIS request, peer: {}", p_device->ble.pseudo_addr);

    auto enabling_state_machines = GetStateMachinesFiltered(
            AseManager::AseFilters::IsoIds(evt->cig_id, evt->cis_id) |
            AseManager::AseFilters::PeerDevice(p_device->ble.pseudo_addr) |
            AseManager::AseFilters::StateIds({AscsAseStateMachine::StateId::QOS_CONFIGURED,
                                              AscsAseStateMachine::StateId::ENABLING}));
    if (enabling_state_machines.empty()) {
      log::error("Rejecting CIS request, cig_id: {}, cis_id: {}, peer: {} - missing state machine",
                 evt->cig_id, evt->cis_id, p_device->ble.pseudo_addr);

      event_tracker_->OnEvent(
              EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
              "RejectIncomingCisConnection conn_hdl: {}, reason: HCI_ERR_UNSPECIFIED",
              evt->cis_conn_hdl);
      iso_app_->RejectIncomingCisConnection(evt->cis_conn_hdl, HCI_ERR_UNSPECIFIED);
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
                            "AcceptIncomingCisConnection conn_hdl: {}", evt->cis_conn_hdl);
    iso_app_->AcceptIncomingCisConnection(evt->cis_conn_hdl);

    for (auto* sm : enabling_state_machines) {
      sm->ProcessEvent(AscsAseStateMachine::Events::CIS_ASSIGNED, UINT_TO_PTR(evt->cis_conn_hdl));
    }
    log::debug("Accepting CIS request, cis_conn_hdl:{}, cig_id: {}, cis_id: {}, peer: {}",
               evt->cis_conn_hdl, evt->cig_id, evt->cis_id, p_device->ble.pseudo_addr);
  }

  void OnCisEstablishEvent(const hci::iso_manager::cis_establish_cmpl_evt* evt) {
    log::debug("CIS establish event, cis_conn_hdl: {}, status: {}", evt->cis_conn_hdl, evt->status);

    if (evt->status != HCI_SUCCESS) {
      log::error("Failed establishing CIS, status: {}", evt->status);
      return;
    }

    auto resuming_ase_state_machines = GetStateMachinesFiltered(
            AseManager::AseFilters::StateId(AscsAseStateMachine::StateId::ENABLING) |
            AseManager::AseFilters::CisConnHandle(evt->cis_conn_hdl));

    if (resuming_ase_state_machines.empty()) {
      log::error("Unable to find state machines for cis_conn_hdl: {}", evt->cis_conn_hdl);
      return;
    }

    for (auto* sm : resuming_ase_state_machines) {
      // Setup iso data path
      log::debug("Prepare ISO data path for SM in state: {}", sm->GetStateId());
      event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
                              "CIS established: SetupIsoDataPath {}, conn_hdl: {}, peer: {}",
                              sm->IsSinkAse() ? "Sink" : "Source", evt->cis_conn_hdl,
                              sm->GetPeer());

      auto const data_path_params =
              GetIsoDataPathParams(sm->data_path_configuration.value(), sm->IsSinkAse());

      log::info("configure_data_path for {}", sm->IsSinkAse() ? "Decode" : "Encode");
      ConfigureDataPath(sm->IsSinkAse() ? hci_data_direction_t::CONTROLLER_TO_HOST
                                        : hci_data_direction_t::HOST_TO_CONTROLLER,
                        sm->data_path_configuration->dataPathId,
                        sm->data_path_configuration->dataPathConfig);
      iso_app_->SetupIsoDataPath(evt->cis_conn_hdl, std::move(data_path_params));

      if (sm->IsSinkAse()) {
        app_callbacks_->OnDecodingIsoChannelParametersUpdated(
                sm->GetPeer(), BTM_Sec_GetAddressWithType(sm->GetPeer()), sm->GetAseId(),
                evt->cis_conn_hdl, sm->codec_configuration, sm->qos_configuration,
                sm->target_latency.value_or(0), sm->metadata);
      } else {
        app_callbacks_->OnEncodingIsoChannelParametersUpdated(
                evt->cis_conn_hdl, sm->GetPeer(), BTM_Sec_GetAddressWithType(sm->GetPeer()),
                sm->codec_configuration, sm->qos_configuration, sm->target_latency.value_or(0),
                sm->metadata);
      }
    }

    // If the specific session is already resumed, proceed these ASEs in ENABLING state to STREAMING
    // Note: There could be more than one CIS established event from a single device, be sure to
    //       move only those Sink ASEs to the STREAMING state, while the Source ASEs will transition
    //       when the peer device is ready to receive the data (on ReceiverStartReady).
    auto has_incoming_data = (evt->max_pdu_c_to_p != 0);
    for (auto* sm : resuming_ase_state_machines) {
      if (has_incoming_data && sm->IsSinkAse() && app_callbacks_->IsDecodingSessionReady()) {
        if (!sm->ProcessEvent(AscsAseStateMachine::Events::RECEIVER_START_READY, nullptr)) {
          log::warn("Invalid Sink state machine state transition for {}", sm->GetPeer());
        }
      }
    }
    // Note: IsoAppProxy tracks the established CIS'es, so that even if the audio session is not yet
    //       ready, we can query each CIS (bound to an ENABLING ASE) for its connection state, and
    //       send the RECEIVER_START_READY event to the relevant state machines once the audio
    //       session becomes ready (in OnDecodingSessionReady()).
  }

  void OnCisDisconnectedEvent(const hci::iso_manager::cis_disconnected_evt* evt) {
    log::debug("CIS disconnect event, cis_conn_hdl: {}, reason: {}", evt->cis_conn_hdl,
               evt->reason);

    for (auto* sm :
         GetStateMachinesFiltered(AseManager::AseFilters::CisConnHandle(evt->cis_conn_hdl))) {
      log::debug("RemoveIncomingCisEventsListener, peer: {}, cig_id: {}, cis_id: {}", sm->GetPeer(),
                 sm->qos_configuration->cig_id, sm->qos_configuration->cis_id);
      event_tracker_->OnEvent(
              EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
              "CIS Disconnected. RemoveIncomingCisEventsListener, peer: {}, cig_id: {}, cis_id: {}",
              sm->GetPeer(), sm->qos_configuration->cig_id, sm->qos_configuration->cis_id);
      iso_app_->RemoveIncomingCisEventsListener(sm->GetPeer(), sm->qos_configuration->cig_id,
                                                sm->qos_configuration->cis_id);

      // Possible autonomous state transition on CIS lost event
      sm->ProcessEvent(AscsAseStateMachine::Events::CIS_LOST, UINT_TO_PTR(evt->cis_conn_hdl));

      auto address_with_type = BTM_Sec_GetAddressWithType(sm->GetPeer());
      if (sm->IsSinkAse()) {
        app_callbacks_->OnDecodingIsoChannelParametersUpdated(
                sm->GetPeer(), address_with_type, sm->GetAseId(), evt->cis_conn_hdl,
                sm->codec_configuration, sm->qos_configuration, sm->target_latency.value_or(0),
                sm->metadata);
      } else {
        app_callbacks_->OnEncodingIsoChannelParametersUpdated(
                evt->cis_conn_hdl, sm->GetPeer(), address_with_type, sm->codec_configuration,
                sm->qos_configuration, sm->target_latency.value_or(0), sm->metadata);
      }
    }
  }

  void OnCisDataAvailable(const bluetooth::hci::iso_manager::cis_data_evt* evt) {
    auto* sm = GetStateMachineFiltered(AseManager::AseFilters::CisConnHandle(evt->cis_conn_hdl));
    if (!sm) {
      log::error("Unable to find state machine");
      return;
    }

    app_callbacks_->OnIsoDataReceived(sm->GetAseId(), evt, sm->codec_configuration,
                                      sm->qos_configuration, sm->metadata);
  }

  void OnCisEvent(uint8_t event_type, void* data) override {
    if (event_type != hci::iso_manager::kIsoEventCisDataAvailable) {
      log::debug("Event_type: {}", event_type);
    }
    switch (event_type) {
      case hci::iso_manager::kIsoEventCisRequest:
        OnCisRequestEvent(static_cast<hci::iso_manager::cis_request_evt*>(data));
        break;
      case hci::iso_manager::kIsoEventCisEstablishCmpl:
        OnCisEstablishEvent(static_cast<hci::iso_manager::cis_establish_cmpl_evt*>(data));
        break;
      case hci::iso_manager::kIsoEventCisDisconnected:
        OnCisDisconnectedEvent(static_cast<hci::iso_manager::cis_disconnected_evt*>(data));
        break;
      case hci::iso_manager::kIsoEventCisDataAvailable:
        OnCisDataAvailable(static_cast<bluetooth::hci::iso_manager::cis_data_evt*>(data));
        break;
      default:
        break;
    }
  }

  void OnAseTransition(uint8_t ase_id, const RawAddress& pseudo_addr) override {
    auto* sm = GetStateMachineFiltered(AseFilters::AseId(ase_id) |
                                       AseFilters::PeerDevice(pseudo_addr));
    if (!sm) {
      log::warn("State machine for ASE: {} no longer exists", ase_id);
      return;
    }

    log::debug("ASE ID: {}, state change: {} -> {}", ase_id, sm->GetPreviousStateId(),
               sm->GetStateId());

    if (sm->GetStateId() == AscsAseStateMachine::StateId::IDLE) {
      app_callbacks_->OnAseStopped(pseudo_addr, ase_id);
      if (sm->IsSinkAse()) {
        if (AreAllSinkAsesInState(pseudo_addr, AscsAseStateMachine::StateId::IDLE)) {
          app_callbacks_->OnAllSinkAsesInIdle(pseudo_addr);
        }
      } else {
        if (AreAllSourceAsesInState(pseudo_addr, AscsAseStateMachine::StateId::IDLE)) {
          app_callbacks_->OnAllSourceAsesInIdle(pseudo_addr);
        }
      }
    }

    // Handle other side effects of state transitions
    switch (sm->GetStateId()) {
      case AscsAseStateMachine::StateId::IDLE:
        // Nothing to do
        break;

      case AscsAseStateMachine::StateId::CODEC_CONFIGURED:
        if (sm->GetPreviousStateId() == AscsAseStateMachine::StateId::RELEASING) {
          log::debug("ASE {} stopped, previous state: {}", ase_id, sm->GetPreviousStateId());
          app_callbacks_->OnAseStopped(pseudo_addr, ase_id);
        }
        break;

      case AscsAseStateMachine::StateId::QOS_CONFIGURED:
        log::assert_that(sm->qos_configuration.has_value(), "State machine has no QoS applied");

        if (sm->GetPreviousStateId() == AscsAseStateMachine::StateId::STREAMING ||
            sm->GetPreviousStateId() == AscsAseStateMachine::StateId::DISABLING ||
            sm->GetPreviousStateId() == AscsAseStateMachine::StateId::ENABLING) {
          log::debug("ASE {} stopped, previous state: {}", ase_id, sm->GetPreviousStateId());
          app_callbacks_->OnAseStopped(pseudo_addr, ase_id);
        }

        // Detect going from STREAMING to QOS_CONFIGURED due to DISABLE/RELEASE operation and remove
        // any data path that was already set up.
        if (iso_app_->HasCisConnected(sm->GetCisConnHandle())) {
          if (sm->data_path_configuration) {
            log::debug("RemoveIsoDataPath for {}, conn_hdl: {}",
                       sm->IsSourceAse() ? "Controller Input" : "Controller Output",
                       sm->GetCisConnHandle());
            event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
                                    "RemoveIsoDataPath for {}, conn_hdl: {}",
                                    sm->IsSourceAse() ? "Controller Input" : "Controller Output",
                                    sm->GetCisConnHandle());

            auto direction =
                    sm->IsSourceAse()
                            ? bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput
                            : bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput;
            iso_app_->RemoveIsoDataPath(sm->GetCisConnHandle(), direction);
          }
        }
        break;

      case AscsAseStateMachine::StateId::ENABLING:
        event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
                                "AddIncomingCisEventsListener, peer: {}, cig_id: {}, cis_id: {}",
                                sm->GetPeer(), sm->qos_configuration->cig_id,
                                sm->qos_configuration->cis_id);
        iso_app_->AddIncomingCisEventsListener(sm->GetPeer(), sm->qos_configuration->cig_id,
                                               sm->qos_configuration->cis_id);
        break;

      case AscsAseStateMachine::StateId::STREAMING:
        if (sm->GetPreviousStateId() == AscsAseStateMachine::StateId::STREAMING) {
          // This is a metadata update
          bool success = true;
          auto ltv_map =
                  types::LeAudioLtvMap::Parse(sm->metadata->data(), sm->metadata->size(), success);
          if (success && ltv_map.GetAsLeAudioMetadata().streaming_audio_context.has_value()) {
            uint32_t audio_context_type =
                    ltv_map.GetAsLeAudioMetadata().streaming_audio_context->value();
            app_callbacks_->OnAseMetadataUpdated(sm->GetPeer(), sm->GetAseId(), audio_context_type);
          }
        } else {
          app_callbacks_->OnAseStreamStarted(sm->GetPeer(), sm->GetAseId(), 0);
        }
        break;

      case AscsAseStateMachine::StateId::DISABLING:
        if (sm->IsSinkAse()) {
          // For Sink ASE, we can stop the audio path as soon as we are
          // in the Disabling state (which means we are about to send
          // Receiver Stop Ready autonomously).
          app_callbacks_->OnAseStopped(sm->GetPeer(), sm->GetAseId());
        }
        break;

      case AscsAseStateMachine::StateId::RELEASING:
        // Going to Releasing from any state other than CODEC_CONFIGURED means that we could
        // already have the CIS established and data path configured
        if (sm->GetPreviousStateId() != AscsAseStateMachine::StateId::CODEC_CONFIGURED) {
          log::assert_that(sm->qos_configuration.has_value(),
                           "Qos configuration was not applied by the state machine");

          {
            log::debug("Removing any {} ISO data path if exists",
                       sm->IsSourceAse() ? "Controller Input" : "Controller Output");
            auto direction =
                    sm->IsSourceAse()
                            ? bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput
                            : bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput;

            event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::POINT,
                                    "RemoveIsoDataPath conn_hdl: {}", sm->GetCisConnHandle());
            iso_app_->RemoveIsoDataPath(sm->GetCisConnHandle(), direction);
          }
        }
        break;

      default:
        break;
    }

    // With CIS filter set up to accept the incoming CIS, we can update the state in ASCS
    ascs_->UpdateAseState(sm->GetPeer(), ase_id, GetCurrentAseState(sm));
  }

  void HandleAseCtpConfigCodec(const RawAddress& address,
                               const std::vector<ascs::AseCodecConfigurationReq>& params,
                               Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpConfigCodec: peer: {}, num_ases: {}", address,
                            params.size());
    log::debug("");

    // Before involving the SM, we should verify the requested codec parameters
    auto ase_configs = app_callbacks_->OnCodecConfigRequest(address, params);
    for (auto const& cfg : ase_configs) {
      auto [ase_id, ase_config_result] = cfg;

      // Check for any non-success statuses
      if (std::holds_alternative<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                  ase_config_result)) {
        auto& error_variant =
                std::get<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                        ase_config_result);
        log::warn("Rejected peer: {}, configuring ASE: {}, response_code: {}, reason: {}", address,
                  ase_id, error_variant.first, error_variant.second);
        response.entries.emplace_back(ase_id, error_variant.first, error_variant.second);
        continue;
      }

      auto* sm = TryGetOrCreateStateMachine(address, ase_id);
      if (!sm) {
        log::warn("Rejected peer: {}, configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::INSUFFICIENT_RESOURCES,
                                      ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      auto& ase_config = std::get<ascs::AseStateCodecConfiguration>(ase_config_result);
      auto request = std::find_if(params.begin(), params.end(),
                                  [&ase_id](auto const& param) { return param.ase_id == ase_id; });
      // Cache the target latency for the higher layer
      sm->target_latency = request != params.end() ? request->codec_configuration.target_latency
                                                   : le_audio::types::kTargetLatencyUndefined;
      if (!sm->ProcessEvent(AscsAseStateMachine::Events::CONFIG_CODEC, (void*)&ase_config)) {
        log::warn("Invalid state transition for {} configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(
                ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                    ascs::AseCtpResponseReason::NO_REASON);
    }
  }

  void HandleAseCtpConfigQos(const RawAddress& address,
                             const std::vector<ascs::AseQosConfigurationReq>& params,
                             Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpConfigQos: peer: {}, num_ases: {}", address,
                            params.size());
    log::debug("");

    std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                 ascs::AseStateQosConfiguration>>
            params_map;

    // Collect codec and qos configurations for each ASE
    for (auto const& param : params) {
      auto* sm = TryGetOrCreateStateMachine(address, param.ase_id);
      if (!sm) {
        log::warn("Rejected peer: {}, configuring ASE: {}", address, param.ase_id);
        response.entries.emplace_back(param.ase_id,
                                      ascs::AseCtpResponseCode::INSUFFICIENT_RESOURCES,
                                      ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      if (!sm->codec_configuration.has_value()) {
        log::warn("Rejected peer: {}, configuring ASE: {}", address, param.ase_id);
        response.entries.emplace_back(
                param.ase_id, ascs::AseCtpResponseCode::INVALID_CONFIGURATION_PARAMETER_VALUE,
                ascs::AseCtpResponseReason::INVALID_ASE_CIS_MAPPING);
        continue;
      }

      params_map[param.ase_id] =
              std::make_tuple(sm->codec_configuration->codec_id,
                              sm->codec_configuration->codec_spec_conf, param.qos_configuration);
    }

    // Before involving the SM, we should verify the requested qos parameters and get the data path
    // configuration matching the codec and QoS parameters
    auto qos_check_result = app_callbacks_->OnSetQosParameters(address, params_map);
    for (auto [ase_id, qos_result] : qos_check_result) {
      auto* sm =
              GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
      log::assert_that(sm, "State machine should exist here for ASE ID: {}", ase_id);

      if (std::holds_alternative<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                  qos_result)) {
        auto& error_variant =
                std::get<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                        qos_result);
        log::warn("Rejected peer: {}, configuring ASE: {}, response_code: {}, reason: {}", address,
                  ase_id, error_variant.first, error_variant.second);
        response.entries.emplace_back(ase_id, error_variant.first, error_variant.second);
        continue;
      }

      auto sm_params = std::make_pair(
              ascs::AseStateQosConfiguration(std::get<2>(params_map[ase_id])),
              ascs::DataPathConfiguration(std::get<ascs::DataPathConfiguration>(qos_result)));

      if (!sm->ProcessEvent(AscsAseStateMachine::Events::CONFIG_QOS, (void*)&sm_params)) {
        log::warn("Invalid state transition for {} configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(
                ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                    ascs::AseCtpResponseReason::NO_REASON);
    }
  }

  void HandleAseCtpEnable(const RawAddress& address, const std::vector<ascs::AseEnableReq>& params,
                          Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpEnable: peer: {}, num_ases: {}", address, params.size());
    log::debug("");

    if (pending_enable_operations_.count(address) > 0) {
      log::error("Device {} has a pending enable operation", address);
      for (const auto& param : params) {
        response.entries.emplace_back(param.ase_id, ascs::AseCtpResponseCode::UNSPECIFIED_ERROR,
                                      ascs::AseCtpResponseReason::NO_REASON);
      }
      return;
    }

    /* Validate all ASEs */
    for (const auto& param : params) {
      auto* sm = GetStateMachineFiltered(AseFilters::AseId(param.ase_id) |
                                         AseFilters::PeerDevice(address));
      if (!sm || !sm->ProcessEvent(AscsAseStateMachine::Events::VALIDATE_ENABLE, nullptr)) {
        log::warn("Cannot enable ASE {} for {}", param.ase_id, address);
        response.entries.emplace_back(
                param.ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
      }
    }

    if (!response.entries.empty()) {
      return;
    }

    /* Store pending request and ask upper layer for permission */
    auto& pending_op = pending_enable_operations_[address];
    pending_op.initial_request = params;

    auto context = std::make_unique<TimeoutCbContext>();
    context->manager_weak_ptr = weak_factory_.GetWeakPtr();
    context->peer_address = address;
    alarm_set_on_mloop(pending_op.timeout_timer.get(), (2000ms).count(),
                       on_enable_request_timeout_callback, context.release());

    std::vector<bluetooth::le_audio::AseEnableRequest> requests;
    for (const auto& param : params) {
      auto* sm = GetStateMachineFiltered(AseFilters::AseId(param.ase_id) |
                                         AseFilters::PeerDevice(address));
      if (!sm) {
        log::warn("State machine for ASE {} no longer exists", param.ase_id);
        continue;
      }

      bool success = true;
      auto ltv_map =
              types::LeAudioLtvMap::Parse(param.metadata.data(), param.metadata.size(), success);
      if (!success) {
        log::warn("Failed to parse metadata for ASE {}", param.ase_id);
        requests.push_back(bluetooth::le_audio::AseEnableRequest{
                .ase_id = param.ase_id,
                .direction = (uint8_t)(sm->IsSinkAse() ? types::kLeAudioDirectionSink
                                                       : types::kLeAudioDirectionSource),
                .audio_context_type =
                        (uint32_t)bluetooth::le_audio::types::LeAudioContextType::UNSPECIFIED,
                .codec_id = {sm->codec_configuration->codec_id.coding_format,
                             sm->codec_configuration->codec_id.vendor_company_id,
                             sm->codec_configuration->codec_id.vendor_codec_id},
        });
        continue;
      }

      auto metadata = ltv_map.GetAsLeAudioMetadata();
      if (metadata.streaming_audio_context.has_value()) {
        requests.push_back(bluetooth::le_audio::AseEnableRequest{
                .ase_id = param.ase_id,
                .direction = (uint8_t)(sm->IsSinkAse() ? types::kLeAudioDirectionSink
                                                       : types::kLeAudioDirectionSource),
                .audio_context_type = metadata.streaming_audio_context->value(),
                .codec_id = {sm->codec_configuration->codec_id.coding_format,
                             sm->codec_configuration->codec_id.vendor_company_id,
                             sm->codec_configuration->codec_id.vendor_codec_id},
        });
      } else {
        log::error("No streaming context!");
      }
    }

    if (!requests.empty()) {
      app_callbacks_->OnAseEnableRequest(address, requests);
    } else {
      log::error("Request empty!");
    }
  }

  void HandleAseCtpUpdateMetadata(const RawAddress& address,
                                  const std::vector<ascs::AseUpdateMetadataReq>& params,
                                  Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpUpdateMetadata: peer: {}, num_ases: {}", address,
                            params.size());
    log::debug("");

    bool all_configs_ok = true;
    std::map<uint8_t, std::vector<uint8_t>> params_map;

    // Collect metadata for each ASE
    for (auto const& param : params) {
      auto* sm = TryGetOrCreateStateMachine(address, param.ase_id);
      if (!sm) {
        log::warn("Rejected peer: {}, configuring ASE: {}", address, param.ase_id);
        response.entries.emplace_back(param.ase_id,
                                      ascs::AseCtpResponseCode::INSUFFICIENT_RESOURCES,
                                      ascs::AseCtpResponseReason::NO_REASON);
        all_configs_ok = false;
        continue;
      }

      if (!sm->codec_configuration.has_value()) {
        log::warn("Rejected peer: {}, configuring ASE: {}", address, param.ase_id);
        response.entries.emplace_back(
                param.ase_id, ascs::AseCtpResponseCode::INVALID_CONFIGURATION_PARAMETER_VALUE,
                ascs::AseCtpResponseReason::INVALID_ASE_CIS_MAPPING);
        all_configs_ok = false;
        continue;
      }

      params_map[param.ase_id] = param.metadata;
    }

    // Verify the enable parameters (e.g. the streaming context validity in the received metadata)S
    auto enable_check_result = app_callbacks_->OnUpdateMetadata(address, params_map);
    for (auto [ase_id, enable_result] : enable_check_result) {
      auto* sm =
              GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
      log::assert_that(sm, "State machine should exist here for ASE ID: {}", ase_id);

      if (std::holds_alternative<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                  enable_result)) {
        auto& error_variant =
                std::get<std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(
                        enable_result);
        log::warn("Rejected peer: {}, configuring ASE: {}, response_code: {}, reason: {}", address,
                  ase_id, error_variant.first, error_variant.second);
        response.entries.emplace_back(ase_id, error_variant.first, error_variant.second);
        all_configs_ok = false;
        continue;
      }

      auto& metadata = std::get<std::vector<uint8_t>>(enable_result);
      if (!sm->ProcessEvent(AscsAseStateMachine::Events::UPDATE_METADATA, (void*)&metadata)) {
        log::warn("Invalid state transition for {} configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(
                ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                    ascs::AseCtpResponseReason::NO_REASON);
    }

    if (!all_configs_ok) {
      log::warn("Unable to update metadata on all the ASEs requested by peer device: {}", address);
    }
  }

  void HandleAseCtpRelease(const RawAddress& address, const std::vector<uint8_t>& ase_ids,
                           Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpRelease: peer: {}, num_ases: {}", address, ase_ids.size());
    log::debug("");

    for (auto const& ase_id : ase_ids) {
      auto is_caching_configured =
              android::sysprop::bluetooth::LeAudio::is_peripheral_caching_supported().has_value();
      bool use_caching =
              android::sysprop::bluetooth::LeAudio::is_peripheral_caching_supported().value_or(
                      true);
      log::debug("use_caching_property: {}, use_caching: {}", is_caching_configured, use_caching);
      auto* sm =
              GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
      if (sm == nullptr) {
        response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::INVALID_ASE_ID,
                                      ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      // Explicitly stop the stream if it was active
      if ((sm->GetStateId() == AscsAseStateMachine::StateId::STREAMING) ||
          (sm->GetStateId() == AscsAseStateMachine::StateId::ENABLING)) {
        // The OnAseStopped callback will be sent from OnAseTransition when the
        // state machine reaches the IDLE state.
      }

      if (!sm->ProcessEvent(AscsAseStateMachine::Events::RELEASE, &use_caching)) {
        log::warn("Invalid state transition for {} configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(
                ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }
      response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                    ascs::AseCtpResponseReason::NO_REASON);
    }
  }

  void HandleAseCtpRequestWithAseIdsParam(AscsAseStateMachine::Events event,
                                          const RawAddress& address,
                                          const std::vector<uint8_t>& ase_ids,
                                          Ascs::AseCtpResponse& response) {
    event_tracker_->OnEvent(EVT_LOG_ISO_TAG, LeAudioEventTracker::EventType::SUBEVENT,
                            "HandleAseCtpRequestWithAseIdsParam: peer: {}, event: {}, num_ases: {}",
                            address, event, ase_ids.size());
    log::debug("");

    for (auto const& ase_id : ase_ids) {
      auto* sm =
              GetStateMachineFiltered(AseFilters::AseId(ase_id) | AseFilters::PeerDevice(address));
      if (sm == nullptr) {
        response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::INVALID_ASE_ID,
                                      ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      if (!sm->ProcessEvent(event, nullptr)) {
        log::warn("Invalid state transition for {} configuring ASE: {}", address, ase_id);
        response.entries.emplace_back(
                ase_id, ascs::AseCtpResponseCode::INVALID_ASE_STATE_MACHINE_TRANSITION,
                ascs::AseCtpResponseReason::NO_REASON);
        continue;
      }

      if (event == AscsAseStateMachine::Events::RECEIVER_STOP_READY) {
        // The OnAseStopped callback will be sent from OnAseTransition when the
        // state machine transitions from DISABLING to QOS_CONFIGURED.
      }

      response.entries.emplace_back(ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                    ascs::AseCtpResponseReason::NO_REASON);
    }
  }

  void OnAseControlPointRequest(const RawAddress& address, const Ascs::AseCtpRequest& request) {
    log::info("Handling ASE Control Point command: {} from device: {}", request.opcode, address);

    Ascs::AseCtpResponse response{.opcode = request.opcode};
    switch (request.opcode) {
      case ascs::AseCtpOpcode::CONFIG_CODEC:
        HandleAseCtpConfigCodec(
                address,
                std::get<std::vector<ascs::AseCodecConfigurationReq>>(request.request_params),
                response);
        break;

      case ascs::AseCtpOpcode::CONFIG_QOS:
        HandleAseCtpConfigQos(
                address,
                std::get<std::vector<ascs::AseQosConfigurationReq>>(request.request_params),
                response);
        break;

      case ascs::AseCtpOpcode::ENABLE:
        HandleAseCtpEnable(address,
                           std::get<std::vector<ascs::AseEnableReq>>(request.request_params),
                           response);
        if (response.entries.empty()) {
          // The request is pending user approval. Response will be sent later.
          return;
        }
        break;

      case ascs::AseCtpOpcode::RECEIVER_START_READY:
        HandleAseCtpRequestWithAseIdsParam(
                AscsAseStateMachine::Events::RECEIVER_START_READY, address,
                std::get<std::vector<uint8_t>>(request.request_params), response);
        break;

      case ascs::AseCtpOpcode::DISABLE:
        HandleAseCtpRequestWithAseIdsParam(AscsAseStateMachine::Events::DISABLE, address,
                                           std::get<std::vector<uint8_t>>(request.request_params),
                                           response);
        break;

      case ascs::AseCtpOpcode::RECEIVER_STOP_READY:
        HandleAseCtpRequestWithAseIdsParam(
                AscsAseStateMachine::Events::RECEIVER_STOP_READY, address,
                std::get<std::vector<uint8_t>>(request.request_params), response);
        break;

      case ascs::AseCtpOpcode::UPDATE_METADATA:
        HandleAseCtpUpdateMetadata(
                address, std::get<std::vector<ascs::AseUpdateMetadataReq>>(request.request_params),
                response);
        break;

      case ascs::AseCtpOpcode::RELEASE:
        HandleAseCtpRelease(address, std::get<std::vector<uint8_t>>(request.request_params),
                            response);
        break;

      default:
        break;
    }

    // Send the response
    log::debug("Sending Ctp response");
    ascs_->AseCtpRequestResponse(address, response);
  }

  void ConfirmAseEnableRequest(const RawAddress& peer_address, bool allowed) {
    if (pending_enable_operations_.count(peer_address) == 0) {
      log::warn("No pending operation for device {}", peer_address);
      return;
    }

    auto& pending_op = pending_enable_operations_.at(peer_address);
    alarm_cancel(pending_op.timeout_timer.get());

    Ascs::AseCtpResponse response{
            .opcode = ascs::AseCtpOpcode::ENABLE,
    };

    if (!allowed) {
      log::info("Enable request denied for device {}", peer_address);
      for (const auto& req : pending_op.initial_request) {
        response.entries.emplace_back(req.ase_id, ascs::AseCtpResponseCode::UNSPECIFIED_ERROR,
                                      ascs::AseCtpResponseReason::NO_REASON);
        auto* sm = GetStateMachineFiltered(AseFilters::AseId(req.ase_id) |
                                           AseFilters::PeerDevice(peer_address));
        if (sm) {
          sm->ProcessEvent(AscsAseStateMachine::Events::DISABLE, nullptr);
        }
      }
      ascs_->AseCtpRequestResponse(peer_address, response);
      pending_enable_operations_.erase(peer_address);
      return;
    }

    log::info("Enable request allowed for device {}", peer_address);
    for (const auto& req : pending_op.initial_request) {
      auto* sm = GetStateMachineFiltered(AseFilters::AseId(req.ase_id) |
                                         AseFilters::PeerDevice(peer_address));
      if (sm) {
        sm->ProcessEvent(AscsAseStateMachine::Events::ENABLE, (void*)&req.metadata);
        response.entries.emplace_back(req.ase_id, ascs::AseCtpResponseCode::SUCCESS,
                                      ascs::AseCtpResponseReason::NO_REASON);
      } else {
        response.entries.emplace_back(req.ase_id, ascs::AseCtpResponseCode::INVALID_ASE_ID,
                                      ascs::AseCtpResponseReason::NO_REASON);
      }
    }
    ascs_->AseCtpRequestResponse(peer_address, response);
    pending_enable_operations_.erase(peer_address);
  }

  void ReleaseAse(const RawAddress& peer_address, uint8_t ase_id) {
    auto* sm = GetStateMachineFiltered(AseFilters::AseId(ase_id) |
                                       AseFilters::PeerDevice(peer_address));
    if (sm) {
      bool use_caching = false;
      sm->ProcessEvent(AscsAseStateMachine::Events::RELEASE, &use_caching);
    }
  }

  void Dump(std::stringstream& stream) const {
    stream << "  ASE Manager:\n";
    stream << "    State Machines (" << ase_state_machines_.size() << "):\n";
    for (const auto& sm : ase_state_machines_) {
      sm->Dump(stream);
    }
  }

private:
  le_audio::types::BidirectionalPair<std::set<uint8_t>> ase_ids_;
  AscsAseStateMachineFactory sm_factory_;
  std::list<std::unique_ptr<AscsAseStateMachine>> ase_state_machines_;
  std::map<RawAddress, PendingEnableOperation> pending_enable_operations_;

  AseManager::Callbacks* app_callbacks_ = nullptr;
  std::shared_ptr<Ascs> ascs_;
  std::unique_ptr<IsoAppProxy> iso_app_;
  std::map<hci_data_direction_t, std::pair<uint8_t, std::vector<uint8_t>>> configured_data_path_;
  base::WeakPtrFactory<manager_impl> weak_factory_{this};
  std::shared_ptr<LeAudioEventTracker> event_tracker_;
};

AseManager::AseManager(std::shared_ptr<Ascs> ascs, AscsAseStateMachineFactory sm_factory,
                       IsoAppProxyFactory iso_app_factory) {
  manager_impl_ = std::make_unique<manager_impl>(std::move(ascs), std::move(sm_factory),
                                                 std::move(iso_app_factory));
}

AseManager::~AseManager() { manager_impl_.reset(); }

void AseManager::Dump(std::stringstream& stream) const { manager_impl_->Dump(stream); }

void AseManager::Initialize(const Ascs::ServiceDescriptor& asc_svc_descriptor,
                            Callbacks* callbacks) {
  manager_impl_->Initialize(asc_svc_descriptor, callbacks);
}

bool AseManager::IsKnownPeerDevice(const RawAddress& pseudo_addr) const {
  return manager_impl_->IsKnownPeerDevice(pseudo_addr);
}

std::set<RawAddress> AseManager::GetNonIdlePeerDevices() const {
  return manager_impl_->GetNonIdlePeerDevices();
}

bool AseManager::IsActiveSinkStream(const RawAddress& pseudo_addr) const {
  return manager_impl_->IsActiveSinkStream(pseudo_addr);
}

bool AseManager::IsActiveSourceStream(const RawAddress& pseudo_addr) const {
  return manager_impl_->IsActiveSourceStream(pseudo_addr);
}

bool AseManager::IsSourceAse(uint8_t ase_id) const { return manager_impl_->IsSourceAse(ase_id); }

bool AseManager::IsSinkAse(uint8_t ase_id) const { return manager_impl_->IsSinkAse(ase_id); }

void AseManager::OnDecodingSessionReady(const RawAddress& pseudo_address) {
  manager_impl_->OnDecodingSessionReady(pseudo_address);
}

void AseManager::OnEncodingSessionReady(const RawAddress& pseudo_address) {
  manager_impl_->OnEncodingSessionReady(pseudo_address);
}

bool AseManager::ConsumeAudioData(const RawAddress& pseudo_addr, uint8_t ase_id, uint8_t* data,
                                  uint16_t size) {
  return manager_impl_->ConsumeAudioData(pseudo_addr, ase_id, data, size);
}

void AseManager::ConfirmAseEnableRequest(const RawAddress& peer_address, bool allowed) {
  manager_impl_->ConfirmAseEnableRequest(peer_address, allowed);
}

void AseManager::ReleaseAse(const RawAddress& peer_address, uint8_t ase_id) {
  manager_impl_->ReleaseAse(peer_address, ase_id);
}
}  // namespace bluetooth::le_audio
