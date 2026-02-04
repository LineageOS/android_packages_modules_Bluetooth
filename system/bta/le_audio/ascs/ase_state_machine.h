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

#include <base/memory/weak_ptr.h>
#include <bluetooth/types/address.h>

#include <optional>
#include <vector>

#include "ascs_types.h"
#include "bta/le_audio/common/le_audio_event_tracker.h"
#include "common/state_machine.h"
#include "osi/include/osi.h"
#include "stack/include/main_thread.h"

namespace bluetooth::le_audio {
static const char* EVT_LOG_TAG = "Ase State Machine";

/**
 * @brief Manages the state of a single Audio Stream Endpoint (ASE) as defined
 * by the Audio Stream Control Service (ASCS) specification v1.0.1.
 *
 * This class implements the state machine for an ASE, handling transitions
 * between states like IDLE, CODEC_CONFIGURED, QOS_CONFIGURED, ENABLING,
 * STREAMING, DISABLING, and RELEASING. It processes events triggered by client
 * requests (e.g., Config Codec, Enable) and internal events (e.g., CIS_LOST)
 * to ensure the ASE behaves according to the BAP specification.
 */
class AscsAseStateMachine : public common::StateMachine {
public:
  /** ASE State field values, as per ASCS specification. */
  enum StateId {
    IDLE = 0x00,
    CODEC_CONFIGURED,
    QOS_CONFIGURED,
    ENABLING,
    STREAMING,
    DISABLING,
    RELEASING,
  };

  /** Events that can trigger state transitions in the ASE state machine. */
  enum Events {
    CONFIG_CODEC = 0x01,
    CONFIG_QOS,
    ENABLE,
    RECEIVER_START_READY,
    DISABLE,
    RECEIVER_STOP_READY,
    UPDATE_METADATA,
    RELEASE,
    VALIDATE_ENABLE,
    /**
     * @brief A special case of CIS lost, not shown on the state machine transition diagram
     * and requires going directly to QoS and skip the Disabling state (ASCS_v1.0, Sec 3.2).
     */
    CIS_ASSIGNED,
    CIS_LOST,
  };

  /**
   * @brief A service interface for the state machine to report back results or
   * query the service about the current system state.
   */
  struct ServiceCallbacks {
    ServiceCallbacks() = default;
    virtual ~ServiceCallbacks() = default;
    /**
     * @brief Called when the ASE state machine completes a state transition.
     * @param ase_id The ID of the ASE.
     */
    virtual void OnAseTransition(uint8_t ase_id, const RawAddress& pseudo_addr) = 0;
  };

  // TODO: Create getters for these
  /**
   * @brief Codec configuration for the ASE.
   *
   * This value is present in all states except IDLE.
   */
  std::optional<ascs::AseStateCodecConfiguration> codec_configuration;

  /**
   * @brief QoS configuration for the ASE.
   *
   * This value is present in QOS_CONFIGURED, ENABLING, STREAMING, and
   * DISABLING states.
   */
  std::optional<ascs::AseStateQosConfiguration> qos_configuration;

  /**
   * @brief Metadata for the ASE.
   *
   * This value is present in ENABLING, STREAMING, and DISABLING states.
   */
  std::optional<std::vector<uint8_t>> metadata;

  /**
   * @brief Data path configuration for the ASE.
   *
   * This value is present in ENABLING, STREAMING, and DISABLING states.
   */
  std::optional<ascs::DataPathConfiguration> data_path_configuration;

  /**
   * @brief Target latency for the ASE.
   *
   * This value is present in all states except IDLE.
   */
  std::optional<uint8_t> target_latency;

  /** @return The ID of this ASE. */
  inline uint8_t GetAseId() const { return ase_id_; }
  /** @return The address of the peer device. */
  inline RawAddress GetPeer() const { return peer_; }
  /** @return The current state ID of the state machine. */
  inline StateId GetStateId() const { return static_cast<StateId>(this->StateMachine::StateId()); }
  /** @return The previous state ID of the state machine. */
  inline StateId GetPreviousStateId() const {
    return static_cast<StateId>(this->StateMachine::PreviousStateId());
  }
  /** @return True if this is a Sink ASE, false otherwise. */
  inline bool IsSinkAse() const { return !is_source_ase_; }
  /** @return True if this is a Source ASE, false otherwise. */
  inline bool IsSourceAse() const { return is_source_ase_; }
  /** @return The connection handle of the associated CIS. */
  inline uint16_t GetCisConnHandle() const { return cis_conn_handle_; }

  /**
   * @brief Sets the confirmation status for the enable request.
   * @param confirmed True if the request is confirmed, false otherwise.
   */
  void SetEnableConfirmed(bool confirmed) { enable_confirmed_ = confirmed; }

  /** @return True if the enable request has been confirmed, false otherwise. */
  bool IsEnableConfirmed(void) const { return enable_confirmed_; }

  /**
   * @brief Processes an event in the state machine.
   *
   * This method handles incoming events and triggers the appropriate state
   * transitions. It also manages the CIS connection handle.
   *
   * @param event The event to process.
   * @param p_data A pointer to event-specific data.
   * @return True if the event was processed, false otherwise.
   */
  virtual bool ProcessEvent(Events event, void* p_data) {
    const uint16_t cis_conn_hdl = PTR_TO_UINT(p_data);
    if (event == Events::CIS_ASSIGNED) {
      cis_conn_handle_ = cis_conn_hdl;
    } else if (event == Events::CIS_LOST && cis_conn_handle_ == cis_conn_hdl) {
      cis_conn_handle_ = INVALID_ACL_HANDLE;
    }
    // TODO: Log SM details on failure
    return common::StateMachine::ProcessEvent((uint32_t)event, p_data);
  }

  /**
   * @brief Dumps the state of the state machine to the given stream.
   *
   * @param stream The string stream to write the dump to.
   */
  void Dump(std::stringstream& stream) const;

  /**
   * @brief Represents the IDLE state of the ASE.
   * The ASE is not configured and not in use.
   */
  class StateIdle : public common::StateMachine::State {
  public:
    explicit StateIdle(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::IDLE), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the CODEC_CONFIGURED state of the ASE.
   * The ASE has been configured with a codec but QoS is not yet configured.
   */
  class StateCodecConfigured : public common::StateMachine::State {
  public:
    explicit StateCodecConfigured(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::CODEC_CONFIGURED), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the QOS_CONFIGURED state of the ASE.
   * The ASE has been configured with a codec and QoS parameters.
   */
  class StateQosConfigured : public common::StateMachine::State {
  public:
    explicit StateQosConfigured(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::QOS_CONFIGURED), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the ENABLING state of the ASE.
   * The ASE is being enabled and is waiting for the CIS to be established.
   */
  class StateEnabling : public common::StateMachine::State {
  public:
    explicit StateEnabling(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::ENABLING), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the DISABLING state of the ASE.
   * The ASE is being disabled and is waiting for the client to confirm with a
   * Receiver Stop Ready operation. This state is only for Source ASEs.
   */
  class StateDisabling : public common::StateMachine::State {
  public:
    explicit StateDisabling(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::DISABLING), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the STREAMING state of the ASE.
   * The ASE is actively streaming audio data.
   */
  class StateStreaming : public common::StateMachine::State {
  public:
    explicit StateStreaming(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::STREAMING), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Represents the RELEASING state of the ASE.
   * This is a transient state where the ASE is being released. It will
   * transition to either IDLE (no caching) or CODEC_CONFIGURED (caching).
   */
  class StateReleasing : public common::StateMachine::State {
  public:
    explicit StateReleasing(AscsAseStateMachine& sm)
        : common::StateMachine::State(sm, StateId::RELEASING), sm(&sm) {}
    void OnEnter() override;
    void OnExit() override;
    bool ProcessEvent(uint32_t event, void* p_data) override;

    // Exposed for state to access common data
    AscsAseStateMachine* sm;
  };

  /**
   * @brief Constructs an AscsAseStateMachine instance.
   *
   * @param is_source_ase True if this is a Source ASE, false for a Sink ASE.
   * @param ase_id The unique ID for this ASE.
   * @param peer The address of the peer device.
   * @param callbacks A pointer to the service callbacks implementation.
   */
  AscsAseStateMachine(bool is_source_ase, uint8_t ase_id, const RawAddress& peer,
                      ServiceCallbacks* callbacks)
      : is_source_ase_(is_source_ase), ase_id_(ase_id), peer_(peer), callbacks_(callbacks) {
    state_idle_ = new StateIdle(*this);
    state_codec_configured_ = new StateCodecConfigured(*this);
    state_qos_configured_ = new StateQosConfigured(*this);
    state_enabling_ = new StateEnabling(*this);
    state_disabling_ = new StateDisabling(*this);
    state_streaming_ = new StateStreaming(*this);
    state_releasing_ = new StateReleasing(*this);

    log::assert_that(callbacks != nullptr, "Callbacks not set!");

    event_tracker_ = LeAudioEventTracker::GetLeAudioSinkInstance();

    // Note: AddState() transfers the raw pointers ownership to the base class
    AddState(state_idle_);
    AddState(state_codec_configured_);
    AddState(state_qos_configured_);
    AddState(state_enabling_);
    AddState(state_disabling_);
    AddState(state_streaming_);
    AddState(state_releasing_);

    SetInitialState(state_idle_);
  }

  /** @brief Destroys the AscsAseStateMachine instance. */
  virtual ~AscsAseStateMachine() {}

  /**
   * @brief Logs a state machine event for tracking and debugging.
   *
   * @tparam T Variadic template for format arguments.
   * @param event_type The type of the event (e.g., POINT, SUBEVENT).
   * @param fmt The format string for the log message.
   * @param args The arguments for the format string.
   */
  template <typename... T>
  inline void TrackEvent(LeAudioEventTracker::EventType event_type, std::format_string<T&...> fmt,
                         T&&... args) {
    event_tracker_->OnEvent(EVT_LOG_TAG, event_type, fmt, args...);
  }

protected:
  /**
   * @brief Performs a synchronous transition to the destination state.
   * @param dest_state_id The ID of the destination state.
   */
  void SyncTransitionTo(int dest_state_id);
  /**
   * @brief Schedules an asynchronous transition to the destination state on the
   * main thread.
   * @param dest_state_id The ID of the destination state.
   */
  void AsyncTransitionTo(int dest_state_id);

private:
  const bool is_source_ase_;
  uint8_t ase_id_;
  RawAddress peer_;
  ServiceCallbacks* callbacks_ = nullptr;

  uint16_t cis_conn_handle_ = INVALID_ACL_HANDLE;
  bool enable_confirmed_ = false;

  StateIdle* state_idle_;
  StateCodecConfigured* state_codec_configured_;
  StateQosConfigured* state_qos_configured_;
  StateEnabling* state_enabling_;
  StateDisabling* state_disabling_;
  StateStreaming* state_streaming_;
  StateReleasing* state_releasing_;

  std::shared_ptr<LeAudioEventTracker> event_tracker_;

  // Weak factory used for safe and reliable asynchronous state transitions
  base::WeakPtrFactory<AscsAseStateMachine> weak_factory_{this};

  std::string GetHeaderString() const;
};

/**
 * @brief A factory function for creating AscsAseStateMachine instances.
 *
 * This is used to inject state machine instances into the AseManager,
 * allowing for easier testing and dependency management.
 */
using AscsAseStateMachineFactory = base::RepeatingCallback<std::unique_ptr<AscsAseStateMachine>(
        bool is_source_ase, uint8_t ase_id, const RawAddress& address,
        AscsAseStateMachine::ServiceCallbacks* cb)>;

std::ostream& operator<<(std::ostream& out, const AscsAseStateMachine::StateId& value);
std::ostream& operator<<(std::ostream& out, const AscsAseStateMachine::Events& value);
}  // namespace bluetooth::le_audio

namespace std {
template <>
struct formatter<bluetooth::le_audio::AscsAseStateMachine::StateId> : ostream_formatter {};
template <>
struct formatter<bluetooth::le_audio::AscsAseStateMachine::Events> : ostream_formatter {};
}  // namespace std
