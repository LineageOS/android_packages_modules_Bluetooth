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
#include <base/memory/weak_ptr.h>
#include <bind_helpers.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <utility>
#include <variant>

#include "bta/le_audio/ascs/ascs.h"
#include "bta/le_audio/ascs/ase_manager.h"
#include "bta/le_audio/audio_hal_client/peripheral_audio_hal_client.h"
#include "bta/le_audio/common/le_audio_event_tracker.h"
#include "bta/le_audio/le_audio_types.h"
#include "bta/le_audio/pacs/pacs.h"
#include "bta_gatt_api.h"
#include "bta_le_audio_server_api.h"
#include "hardware/bt_le_audio_server.h"
#include "hci/controller.h"
#include "le_audio_server_config_manager.h"
#include "main/shim/entry.h"
#include "main/shim/le_advertising_manager.h"
#include "stack/include/acl_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/main_thread.h"

using base::WeakPtrFactory;
using bluetooth::bta::le_audio::PeripheralAudioHalDecoder;
using bluetooth::bta::le_audio::PeripheralAudioHalEncoder;
using bluetooth::common::MessageLoopThread;
using bluetooth::hci::IsoManager;
using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::DsaModes;

namespace bluetooth::le_audio {
namespace {

class LeAudioServerImpl;
LeAudioServerImpl* instance;
std::mutex instance_mutex;

static const char* EVT_LOG_TAG = "BAP Peripheral";
static const char* EVT_LOG_AUDIO_TAG = "Audio Session";

class LeAudioServerImpl : public LeAudioServer,
                          public Pacs::Callbacks,
                          public AseManager::Callbacks {
public:
  LeAudioServerImpl(le_audio::LeAudioServerCallbacks* callbacks,
                    std::shared_ptr<LeAudioServerConfigManager> config_provider,
                    std::shared_ptr<Pacs> pacs, std::shared_ptr<Ascs> ascs,
                    std::shared_ptr<AseManager> ase_manager, MessageLoopThread* message_loop,
                    audio::le_audio::IPeripheralAudioSessionFactory* factory,
                    audio::le_audio::IPeripheralAudioProviderFactory* provider_factory)
      : callbacks_(callbacks),
        config_manager_(std::move(config_provider)),
        pacs_(std::move(pacs)),
        ascs_(std::move(ascs)),
        ase_manager_(std::move(ase_manager)),
        message_loop_(message_loop),
        peripheral_audio_factory_(factory),
        peripheral_audio_provider_factory_(provider_factory),
        weak_ptr_factory_(this) {}

  void Initialize() {
    if (peripheral_audio_provider_factory_ == nullptr) {
      peripheral_audio_provider_factory_ = audio::le_audio::IPeripheralAudioProviderFactory::Get();
    }

    // Use the audio provider factory to get Audio HAL capabilities
    auto playaback_capabilities =
            peripheral_audio_provider_factory_->GetPlaybackSessionAudioProvider()
                    ->GetProviderCapabilities();
    auto recording_capabilities =
            peripheral_audio_provider_factory_->GetRecordingSessionAudioProvider()
                    ->GetProviderCapabilities();
    pacs_->RegisterGattService(
            config_manager_->GetPacsDescriptor(std::move(playaback_capabilities),
                                               std::move(recording_capabilities)),
            this);

    ase_manager_->Initialize(config_manager_->GetAscsDescriptor(), this);

    event_tracker_ = LeAudioEventTracker::GetLeAudioSinkInstance();
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "Calling callback OnInitialized");
    callbacks_->OnInitialized();
  }

  ~LeAudioServerImpl() {
    // Release the owned references
    config_manager_.reset();
    pacs_.reset();
    ase_manager_.reset();
    ascs_.reset();
  }

  std::map<uint8_t, std::variant<ascs::AseStateCodecConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
  GetCodecConfig(const std::vector<ascs::AseCodecConfigurationReq>& requests) const {
    // Split the sink and source requests
    std::vector<bluetooth::audio::le_audio::endpoint_config_req> sink_req;
    std::vector<bluetooth::audio::le_audio::endpoint_config_req> source_req;
    for (const auto& request : requests) {
      auto& out_req = ase_manager_->IsSinkAse(request.ase_id) ? sink_req : source_req;
      out_req.push_back({.ase_id = request.ase_id,
                         .codec_configuration = {
                                 request.codec_configuration.target_latency,
                                 request.codec_configuration.target_phy,
                                 request.codec_configuration.codec_id,
                                 request.codec_configuration.codec_spec_conf,
                         }});
    }

    // Get the sink and source ASE configurations and merge the responses
    bluetooth::audio::le_audio::endpoint_config_rsp response;
    if (!sink_req.empty()) {
      log::assert_that(decoding_session_.get() != nullptr, "No active decoding session!");
      response.merge(decoding_session_->RequestAseConfigurations(sink_req, {}));
    }
    if (!source_req.empty()) {
      log::assert_that(encoding_session_.get() != nullptr, "No active encoding session!");
      response.merge(encoding_session_->RequestAseConfigurations({}, source_req));
    }
    return response;
  }

  std::map<uint8_t, std::variant<ascs::AseStateCodecConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
  OnCodecConfigRequest(const RawAddress& pseudo_address,
                       const std::vector<ascs::AseCodecConfigurationReq>& requests) override {
    // We need an active session to request the configurations from the provider
    log::debug("Address: {}", pseudo_address);
    log::debug("Start the audio session now");

    StartEncodingAudioSession();
    StartDecodingAudioSession();

    auto results = GetCodecConfig(requests);
    auto error_response = std::find_if(results.begin(), results.end(), [](auto const& res) {
      return std::holds_alternative<
              std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>(res.second);
    });
    if (error_response != results.end()) {
      log::debug("Configuration failure: Stopping the audio session");
      StopEncodingAudioSession();
      StopDecodingAudioSession();
      return results;
    }

    return results;
  }

  std::map<uint8_t, std::variant<ascs::DataPathConfiguration,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
  OnSetQosParameters(const RawAddress& pseudo_address,
                     std::map<uint8_t, std::tuple<types::LeAudioCodecId, std::vector<uint8_t>,
                                                  ascs::AseStateQosConfiguration>>
                             params) override {
    log::debug("Address: {}", pseudo_address);
    return config_manager_->VerifyQosParameters(params);
  }

  std::map<uint8_t, std::variant<std::vector<uint8_t>,
                                 std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
  OnUpdateMetadata(const RawAddress& pseudo_address,
                   std::map<uint8_t, std::vector<uint8_t>> params) override {
    log::debug("Address: {}", pseudo_address);

    // TODO(b/480940695): Support more advanced stream context management
    std::map<uint8_t, std::variant<std::vector<uint8_t>,
                                   std::pair<ascs::AseCtpResponseCode, ascs::AseCtpResponseReason>>>
            results;
    for (auto const& [ase_id, param] : params) {
      results[ase_id] = param;
    }
    return results;
  }

  le_audio::types::BidirectionalPair<le_audio::types::AudioContexts> OnGetAvailableAudioContexts(
          const RawAddress& address) override {
    return config_manager_->GetAvailableAudioContexts(address);
  }

  void OnAudioLocationsWritten(const RawAddress& address, uint8_t direction,
                               const le_audio::types::AudioLocations& audio_locations) override {
    log::info("address: {}, direction: {}, locations: 0x{:08x}", address, direction,
              audio_locations.to_ulong());
    // Note: Reject any location changes. Revisit the policy once there is a real demand for the
    // feature, and the requirements are known.
    pacs_->ConfirmAudioLocationsWritten(address, false);
  }

  void OnPacsRegistered(void) override { log::info("PACS registered successfully"); }

  void OnAsesRegistered(std::set<uint8_t> sink_ases, std::set<uint8_t> source_ases) override {
    log::info("Sink ASEs : {}, Source ASEs : {}", sink_ases.size(), source_ases.size());
  }

  void OnDeviceConnected(const RawAddress& address) override {
    log::info("Address: {} connected to PACS", address);
  }

  void OnDeviceDisconnected(const RawAddress& address) override {
    log::info("Address: {} disconnected from PACS", address);
  }

  void OnClientConnected(const RawAddress& address) override {
    log::info("Address: {} connected to ASCS", address);

    if (!ase_manager_->IsKnownPeerDevice(address)) {
      log::warn("Device {} not connected to ASC service", address);
      return;
    }

    auto conn_id = ascs_->GetConnectionId(address);
    if (conn_id == GATT_INVALID_CONN_ID) {
      log::warn("Device {} not connected to PAC service", address);
      return;
    }

    /* To be a Unicast Sink device, this device shall be a Peripheral device. */
    tHCI_ROLE role;
    auto role_status =
            get_btm_client_interface().link_policy.BTM_GetRole(address, BT_TRANSPORT_LE, &role);
    if (role_status != tBTM_STATUS::BTM_SUCCESS || role != HCI_ROLE_PERIPHERAL) {
      log::warn("Unicast server is not available for this connection. {}, status: {}, AclRole: {}",
                address, btm_status_text(role_status), hci_role_text(role));
      return;
    }

    callbacks_->OnConnectionStateChanged(address, GattConnectionState::CONNECTED);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "[{}] Calling callback OnConnectionStateChanged, state: CONNECTED",
                            address);
  }

  void OnClientDisconnected(const RawAddress& address) override {
    log::info("Address: {} disconnected from PACS", address);
    callbacks_->OnConnectionStateChanged(address, GattConnectionState::DISCONNECTED);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "[{}] Calling callback OnConnectionStateChanged, state: DISCONNECTED",
                            address);
  }

  void OnAseEnableRequest(
          const RawAddress& address,
          const std::vector<bluetooth::le_audio::AseEnableRequest>& requests) override {
    callbacks_->OnStreamStartRequest(address, requests);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "[{}] Calling callback OnStreamStartRequest", address);
  }

  void OnIsoDataReceived(
          uint8_t /*ase_id*/, const bluetooth::hci::iso_manager::cis_data_evt* /*event*/,
          const std::optional<ascs::AseStateCodecConfiguration>& /*codec_configuration*/,
          const std::optional<ascs::AseStateQosConfiguration>& /*qos_configuration*/,
          const std::optional<std::vector<uint8_t>>& /*metadata*/) override {
    log::fatal("Software encoding in BT stack is not implemented - prefer software offloading");
  }

  bool is_decoding_session_started_ = false;
  bool is_decoding_session_resumed_ = false;

  void StartDecodingAudioSession() {
    log::debug("");
    if (decoding_session_) {
      log::debug("Already started");
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::START,
                            "Acquire DecodingAudioSession");

    auto weak_this = weak_ptr_factory_.GetWeakPtr();
    audio::le_audio::PeripheralStreamCallbacks callbacks = {
            .OnStartRequest =
                    [weak_this]() {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnDecodingAudioSessionResume();
                    },
            .OnSuspendRequest =
                    [weak_this]() {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnDecodingAudioSessionSuspend();
                    },
            .OnPlaybackMetadataUpdate =
                    [weak_this](const source_metadata_v7_t& metadata) {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnDecodingAudioSessionMetadataUpdate(metadata);
                    },
    };

    decoding_session_ = std::make_unique<bta::le_audio::PeripheralAudioHalDecoder>(
            callbacks, message_loop_, peripheral_audio_factory_);

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::START,
                            "Decoding: StartAudioSession");
    decoding_session_->Start();
    is_decoding_session_started_ = true;
  }

  void StopDecodingAudioSession() {
    log::debug("");

    if (!decoding_session_) {
      log::debug("Already stopped");
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::END,
                            "Decoding: StopAudioSession");
    decoding_session_->Stop();
    decoding_session_.reset();
    is_decoding_session_started_ = false;
  }

  bool IsDecodingSessionReady() const override {
    log::debug("{}", is_decoding_session_resumed_);

    return is_decoding_session_resumed_;
  }

  bool is_encoding_session_started_ = false;
  bool is_encoding_session_resumed_ = false;

  bool IsEncodingSessionReady() const override {
    log::debug("{}", is_encoding_session_started_);
    return is_encoding_session_resumed_;
  }

  void StartEncodingAudioSession() {
    log::debug("");
    if (encoding_session_) {
      log::debug("Already started");
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::START,
                            "Acquire EncodingAudioSession");

    auto weak_this = weak_ptr_factory_.GetWeakPtr();
    audio::le_audio::PeripheralStreamCallbacks callbacks = {
            .OnStartRequest =
                    [weak_this]() {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnEncodingAudioSessionResume();
                    },
            .OnSuspendRequest =
                    [weak_this]() {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnEncodingAudioSessionSuspend();
                    },
            .OnRecordingMetadataUpdate =
                    [weak_this](const sink_metadata_v7_t& metadata) {
                      if (!weak_this.get()) {
                        return;
                      }
                      weak_this->OnEncodingAudioSessionMetadataUpdate(metadata, DsaMode::DISABLED);
                    },
    };

    encoding_session_ = std::make_unique<bta::le_audio::PeripheralAudioHalEncoder>(
            callbacks, message_loop_, peripheral_audio_factory_);

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::START,
                            "Encoding: StartAudioSession");

    encoding_session_->Start();
    is_encoding_session_started_ = true;
  }

  void StopEncodingAudioSession() {
    log::debug("");

    if (!encoding_session_) {
      log::debug("Already stopped");
      return;
    }

    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::END,
                            "Encoding: StopAudioSession");
    encoding_session_->Stop();
    encoding_session_.reset();
    is_encoding_session_started_ = false;
  }

  void OnDecodingIsoChannelParametersUpdated(
          const RawAddress& pseudo_address, const tBLE_BD_ADDR address_with_type, int ase_id,
          uint16_t cis_conn_hdl,
          const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
          const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
          uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata) override {
    log::debug("Addr: {}, ase_id: {}", pseudo_address, ase_id);
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "Decoding: OnIsoChannelParametersUpdated, cis_con_hdl: {}",
                            cis_conn_hdl);

    const auto is_removing_stream = !codec_configuration || !qos_configuration || !metadata;
    log::assert_that(decoding_session_.get() != nullptr, "No decoding session created");
    if (decoding_session_) {
      if (is_removing_stream) {
        event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                                "Decoding: OnAudioChannelRemoved, pseudo_addr: {}, cis_con_hdl: {}",
                                pseudo_address, cis_conn_hdl);
        decoding_session_->OnAudioChannelRemoved(pseudo_address, cis_conn_hdl);
      } else {
        bta::le_audio::audio_channel_info audio_channel_info{
                address_with_type,
                codec_configuration->codec_id,
                codec_configuration->codec_spec_conf,
                target_latency,
                qos_configuration->pres_delay,
                qos_configuration->phy,
                metadata.value_or(std::vector<uint8_t>{})};

        event_tracker_->OnEvent(
                EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                "Decoding: OnAudioChannelParametersChanged, pseudo_addr: {}, cis_con_hdl: {}",
                pseudo_address, cis_conn_hdl);
        decoding_session_->OnAudioChannelParametersChanged(pseudo_address, cis_conn_hdl,
                                                           audio_channel_info);
      }
    }

    // Note: This is crucial to trigger the streaming in the upper layer
    if (is_decoding_session_started_ && !is_removing_stream) {
      callbacks_->OnSinkStreamReady(pseudo_address);
      event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                              "[{}] Calling callback OnSinkStreamReady", pseudo_address);
    }
  }

  void OnAseStreamStarted(const RawAddress& pseudo_address, uint8_t ase_id,
                          uint32_t audio_context_type) override {
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] OnAseStreamStarted, ase_id: {}, audio_context_type: {}",
                            pseudo_address, ase_id, audio_context_type);

    if (ase_manager_->IsSourceAse(ase_id)) {
      callbacks_->OnSourceStreamReady(pseudo_address);
      event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                              "[{}] Calling callback OnSourceStreamReady", pseudo_address);
    }

    callbacks_->OnStreamStarted(pseudo_address, ase_id, audio_context_type);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "[{}] Calling callback OnStreamStarted, ase_id: {}", pseudo_address,
                            ase_id);
  }

  void OnAseStopped(const RawAddress& pseudo_address, uint8_t ase_id) override {
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] OnAseStopped, ase_id: {}", pseudo_address, ase_id);
    callbacks_->OnStreamStopped(pseudo_address, ase_id);
    event_tracker_->OnEvent(EVT_LOG_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "[{}] Calling callback OnStreamStopped, ase_id: {}", pseudo_address,
                            ase_id);
  }

  void OnAseMetadataUpdated(const RawAddress& peer_address, uint8_t ase_id,
                            uint32_t audio_context_type) override {
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] OnAseMetadataUpdated, ase_id: {}, audio_context_type: {}",
                            peer_address, ase_id, audio_context_type);
    callbacks_->OnStreamMetadataUpdated(peer_address, ase_id, audio_context_type);
  }

  void OnEncodingIsoChannelParametersUpdated(
          uint16_t cis_conn_hdl, const RawAddress& pseudo_address,
          const tBLE_BD_ADDR address_with_type,
          const std::optional<ascs::AseStateCodecConfiguration>& codec_configuration,
          const std::optional<ascs::AseStateQosConfiguration>& qos_configuration,
          uint8_t target_latency, const std::optional<std::vector<uint8_t>>& metadata) override {
    log::debug("Addr: {}", pseudo_address);
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] Encoding: OnIsoChannelParametersUpdated, cis_con_hdl: {}",
                            pseudo_address, cis_conn_hdl);

    log::assert_that(encoding_session_.get() != nullptr, "No encoding session created");
    if (encoding_session_) {
      const auto is_removing_stream = !codec_configuration || !qos_configuration || !metadata;
      if (is_removing_stream) {
        event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                                "Encoding: OnAudioChannelRemoved, pseudo_addr: {}, cis_con_hdl: {}",
                                pseudo_address, cis_conn_hdl);
        encoding_session_->OnAudioChannelRemoved(pseudo_address, cis_conn_hdl);
      } else {
        bta::le_audio::audio_channel_info audio_channel_info{
                address_with_type,
                codec_configuration->codec_id,
                codec_configuration->codec_spec_conf,
                target_latency,
                qos_configuration->pres_delay,
                qos_configuration->phy,
                metadata.value_or(std::vector<uint8_t>{})};

        event_tracker_->OnEvent(
                EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                "Encoding: OnAudioChannelParametersChanged, pseudo_addr: {}, cis_con_hdl: {}",
                pseudo_address, cis_conn_hdl);
        encoding_session_->OnAudioChannelParametersChanged(pseudo_address, cis_conn_hdl,
                                                           audio_channel_info);
      }
    }
  }

  void OnAllSinkAsesInIdle(const RawAddress& pseudo_addr) override {
    log::debug("Addr: {}", pseudo_addr);
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] All Sink ASEs in IDLE", pseudo_addr);

    // If some remote still has any Sink ASEs in a non IDLE state, return early
    for (auto const& addr : ase_manager_->GetNonIdlePeerDevices()) {
      if (ase_manager_->IsActiveSinkStream(addr)) {
        log::debug("Some devices still have Sink ASEs configured. Audio session stays.");
        return;
      }
    }

    // If all ases went to IDLE, then there is no configuration caching (in the session as well)
    // and we can just close the session.
    StopDecodingAudioSession();
  }

  void OnAllSourceAsesInIdle(const RawAddress& pseudo_addr) override {
    log::debug("Addr: {}", pseudo_addr);
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                            "[{}] All Source ASEs in IDLE", pseudo_addr);

    // If some remote still has any Source ASEs in a non IDLE state, return early
    for (auto const& addr : ase_manager_->GetNonIdlePeerDevices()) {
      if (ase_manager_->IsActiveSourceStream(addr)) {
        log::debug("Some devices still have Source ASEs configured. Audio session stays.");
        return;
      }
    }

    // If all ases went to IDLE, then there is no configuration caching (in the session as well)
    // and we can just close the session.
    StopEncodingAudioSession();
  }

  void OnDecodingAudioSessionSuspend() {
    log::debug("");
    is_decoding_session_resumed_ = false;
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "Decoding: OnAudioSessionSuspend");
  }

  void OnDecodingAudioSessionResume() {
    log::debug("");
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "OnDecodingAudioSessionResume");
    is_decoding_session_resumed_ = true;

    if (decoding_session_) {
      event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                              "Decoding: ConfirmStreamingRequest");
      decoding_session_->ConfirmStreamingRequest();

      // Notify the AseManager that we are ready to decode the incoming audio streams
      auto remote_streaming_devices = decoding_session_->GetStreamingDevices();
      for (auto const& address : remote_streaming_devices) {
        ase_manager_->OnDecodingSessionReady(address);
      }
    }
  }

  void OnDecodingAudioSessionMetadataUpdate(const source_metadata_v7_t& metadata) {
    log::debug("metadata tracks count: {}", metadata.track_count);

    if (!is_decoding_session_started_) {
      log::warn("Decoding session not started, ignoring metadata update");
      return;
    }
  }

  void OnEncodingSessionAudioDataReady(const std::vector<uint8_t>& /*data*/) {
    log::warn("Non-offloaded audio data path not supported!");
  }

  void OnEncodingAudioSessionSuspend(void) {
    log::debug("");
    is_encoding_session_resumed_ = false;
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "Encoding: OnAudioSessionSuspend");
  }

  void OnEncodingAudioSessionResume(void) {
    log::debug("");
    event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::CALLBACK,
                            "OnEncodingAudioSessionResume");
    is_encoding_session_resumed_ = true;

    if (encoding_session_) {
      event_tracker_->OnEvent(EVT_LOG_AUDIO_TAG, LeAudioEventTracker::EventType::POINT,
                              "Encoding: ConfirmStreamingRequest");
      encoding_session_->ConfirmStreamingRequest();

      // Notify the AseManager that we are ready to encode the incoming audio streams
      auto remote_streaming_devices = encoding_session_->GetStreamingDevices();
      for (auto const& address : remote_streaming_devices) {
        ase_manager_->OnEncodingSessionReady(address);
      }
    }
  }

  void OnEncodingAudioSessionMetadataUpdate(const sink_metadata_v7_t& /*source_metadata*/,
                                            DsaMode /*dsa_mode*/) {
    log::debug("");
    if (!is_encoding_session_started_) {
      log::warn("Encoding session not started, ignoring metadata update");
      return;
    }
  }

  void Dump(int fd) {
    std::stringstream stream;
    ascs_->Dump(stream);
    stream << "\n";

    stream << "  Decoding session started: " << is_decoding_session_started_ << "\n";
    stream << "\n";
    stream << "  Encoding session started: " << is_encoding_session_started_ << "\n";
    stream << "\n";

    ase_manager_->Dump(stream);
    stream << "\n";

    pacs_->Dump(stream);
    stream << "\n";

    event_tracker_->Dump(stream);
    stream << "\n";

    dprintf(fd, "%s", stream.str().c_str());
  }

  void Cleanup(void) { log::info("LeAudioServerImpl cleanup."); }

  void ConfirmStreamStartRequest(const RawAddress& peer_address, bool allowed) {
    ase_manager_->ConfirmAseEnableRequest(peer_address, allowed);
  }

  void StopStream(const RawAddress& peer_address, uint8_t ase_id) {
    ase_manager_->ReleaseAse(peer_address, ase_id);
  }

private:
  le_audio::LeAudioServerCallbacks* callbacks_;

  std::shared_ptr<LeAudioServerConfigManager> config_manager_;
  std::shared_ptr<Pacs> pacs_;
  std::shared_ptr<Ascs> ascs_;
  std::shared_ptr<AseManager> ase_manager_;

  std::unique_ptr<bta::le_audio::PeripheralAudioHalDecoder> decoding_session_;
  std::unique_ptr<bta::le_audio::PeripheralAudioHalEncoder> encoding_session_;
  std::shared_ptr<LeAudioEventTracker> event_tracker_;

  MessageLoopThread* message_loop_ = nullptr;
  audio::le_audio::IPeripheralAudioSessionFactory* peripheral_audio_factory_ = nullptr;
  audio::le_audio::IPeripheralAudioProviderFactory* peripheral_audio_provider_factory_ = nullptr;
  base::WeakPtrFactory<LeAudioServerImpl> weak_ptr_factory_;
};

}  // namespace

LeAudioServer* LeAudioServer::Get() {
  log::assert_that(instance != nullptr, "assert failed: instance != nullptr");
  return instance;
}

void LeAudioServer::Initialize(le_audio::LeAudioServerCallbacks* callbacks,
                               std::unique_ptr<LeAudioServerDependencies> dependencies) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized");
    return;
  }

  if (!shim::GetController()->SupportsBleConnectedIsochronousStreamCentral() &&
      !shim::GetController()->SupportsBleConnectedIsochronousStreamPeripheral()) {
    log::error("Controller reports no ISO support. LeAudioServer Init aborted.");
    return;
  }

  auto config_provider = dependencies->config_manager_factory();
  auto pacs = dependencies->pacs_factory();
  auto ascs = dependencies->ascs_factory();
  auto ase_manager = dependencies->ase_manager_factory(ascs);
  auto session_factory = dependencies->peripheral_audio_session_factory();
  auto provider_factory = dependencies->peripheral_audio_provider_factory();

  instance = new LeAudioServerImpl(callbacks, config_provider, pacs, ascs, ase_manager,
                                   get_main_thread(), session_factory, provider_factory);
  instance->Initialize();
}

void LeAudioServer::ConfirmStreamStartRequest(const RawAddress& peer_address, bool allowed) {
  if (instance) {
    instance->ConfirmStreamStartRequest(peer_address, allowed);
  }
}

void LeAudioServer::StopStream(const RawAddress& peer_address, uint8_t ase_id) {
  if (instance) {
    instance->StopStream(peer_address, ase_id);
  }
}

void LeAudioServer::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);

  dprintf(fd, "LE Audio Server Manager: \n");
  if (instance) {
    instance->Dump(fd);
  } else {
    dprintf(fd, "  Not initialized \n");
  }

  IsoManager::GetInstance()->Dump(fd);
  dprintf(fd, "\n");
}

void LeAudioServer::Cleanup(void) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!instance) {
    log::error("Not initialized");
    return;
  }

  LeAudioServerImpl* ptr = instance;
  instance = nullptr;
  ptr->Cleanup();
  delete ptr;
  ptr = nullptr;
}

}  // namespace bluetooth::le_audio
