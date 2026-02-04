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

#include "ase_state_machine.h"

#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>

// clang-format off
/*
 *  ASE State Machine - possible state transitions
 *
 *                           Config Codec
 *              +-----------------------------------+
 *              |                                   |
 *              |                                   v
 *       +----------------+    Config QoS    +----------------+
 *       |     Idle       |----------------->| Codec          |
 *       |                |    +------------>| Configured     |
 *       +----------------+    |   +---------|                |
 *              ^              |   |         +----------------+
 *   Released   |              |   | Release        |  ^
 * (no caching) |     Released |   |                |  |
 *              |    (caching) |   |     Config QoS |  | Config Codec
 *              |              |   |                |  |
 *       +----------------+    |   |                v  |                  Receiver Stop
 *       |                |----+   |         +----------------+               Ready
 *       |   Releasing    |<-------+         | QoS            |<------------------------+
 *       |                |<-----------------| Configured     |<------+                 |
 *       +----------------+     Release      +----------------+       |                 |
 *            ^  ^  ^                               |                 | +------------------------------------+
 *            |  |  |                        Enable |         Disable | :     Disabling (Source ASE only)    :
 *            |  |  |                               v                 | :                                    :
 *            |  |  |           Release      +----------------+       | : An intermediate 'Disabling' state  :
 *            |  |  +------------------------| Enabling       |-------+ : going to 'Releasing' or 'Qos Conf` :
 *            |  |                           +----------------+         +------------------------------------+
 *            |  |                                  |                                   ^  |
 *            |  |                                  | Receiver Start                    |  |
 *            |  |                                  |     Ready                         |  |
 *            |  |                                  v                                   |  | Release
 *            |  |              Release      +----------------+       Disable           |  |
 *            |  +---------------------------| Streaming      |-------------------------+  |
 *            |                              +----------------+                            |
 *            +----------------------------------------------------------------------------+
 *
 * Note: 'Update Metadata' action in [Enabling] and [Streaming] states results in transition to the same state.
 *       'Config Codec' action in [Codec Configured] state results in transition to the same state.
 *       'Config QoS' action in [QoS Configured] state results in transition to the same state.
 */
// clang-format on

namespace bluetooth::le_audio {

template <typename T>
T& GetEventData(void* p_data) {
  return *static_cast<T*>(p_data);
}

// State: IDLE
void AscsAseStateMachine::StateIdle::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());
  sm->SetEnableConfirmed(false);
  sm->target_latency = std::nullopt;

  if (sm->PreviousStateId() == StateMachine::kStateInvalid) {
    // Ignore the initial state entry at sm->Start()
    return;
  }
  sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
}
void AscsAseStateMachine::StateIdle::OnExit() {}
bool AscsAseStateMachine::StateIdle::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC: {
      auto& codec_config = GetEventData<ascs::AseStateCodecConfiguration>(p_data);
      sm->TrackEvent(
              LeAudioEventTracker::EventType::SUBEVENT,
              "{}, event: {}, codec_id: {{format:0x{:02x}, company:0x{:04x}, codec:0x{:04x}}}",
              sm->GetHeaderString(), Events(event), codec_config.codec_id.coding_format,
              codec_config.codec_id.vendor_company_id, codec_config.codec_id.vendor_codec_id);
      sm->codec_configuration = codec_config;
      sm->AsyncTransitionTo(StateId::CODEC_CONFIGURED);
      return true;
    } break;

    case CONFIG_QOS:
      // Ignore
      break;
    case RELEASE:
      // Ignore
      break;
    case ENABLE:
      // Ignore
      break;
    case DISABLE:
      // Ignore
      break;
    case RECEIVER_START_READY:
      // Ignore
      break;
    case RECEIVER_STOP_READY:
      // Ignore
      break;
    case UPDATE_METADATA:
      // Ignore
      break;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: CODEC_CONFIGURED
void AscsAseStateMachine::StateCodecConfigured::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());
  sm->SetEnableConfirmed(false);

  log::assert_that(sm->codec_configuration.has_value(), "Invalid codec configuration");
  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }
}
void AscsAseStateMachine::StateCodecConfigured::OnExit() {}
bool AscsAseStateMachine::StateCodecConfigured::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC: {
      auto& codec_config = GetEventData<ascs::AseStateCodecConfiguration>(p_data);
      sm->TrackEvent(
              LeAudioEventTracker::EventType::SUBEVENT,
              "{}, event: {}, codec_id: {{format:0x{:02x}, company:0x{:04x}, codec:0x{:04x}}}",
              sm->GetHeaderString(), Events(event), codec_config.codec_id.coding_format,
              codec_config.codec_id.vendor_company_id, codec_config.codec_id.vendor_codec_id);
      sm->codec_configuration = codec_config;
      sm->AsyncTransitionTo(StateId::CODEC_CONFIGURED);
      return true;
    }

    case CONFIG_QOS: {
      auto& sm_params =
              GetEventData<std::pair<ascs::AseStateQosConfiguration, ascs::DataPathConfiguration>>(
                      p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT,
                     "{}, event: {}, sdu_interval: {}, framing: {}, phy: {}", sm->GetHeaderString(),
                     Events(event), sm_params.first.sdu_interval, sm_params.first.framing,
                     sm_params.first.phy);
      sm->qos_configuration = sm_params.first;
      sm->data_path_configuration = sm_params.second;
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;
    }

    case RELEASE: {
      auto const is_caching = GetEventData<bool>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}, is_caching: {}",
                     sm->GetHeaderString(), Events(event), is_caching);
      if (!is_caching) {
        sm->codec_configuration = std::nullopt;
      }
      sm->AsyncTransitionTo(StateId::RELEASING);
      return true;
    }

    case ENABLE:
      // Ignore
      break;
    case DISABLE:
      // Ignore
      break;
    case RECEIVER_START_READY:
      // Ignore
      break;
    case RECEIVER_STOP_READY:
      // Ignore
      break;
    case UPDATE_METADATA:
      // Ignore
      break;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: QOS_CONFIGURED
void AscsAseStateMachine::StateQosConfigured::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());
  sm->SetEnableConfirmed(false);

  log::assert_that(sm->codec_configuration.has_value(), "Invalid codec configuration");
  log::assert_that(sm->qos_configuration.has_value(), "Invalid QoS configuration");

  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }

  sm->metadata = std::nullopt;
}
void AscsAseStateMachine::StateQosConfigured::OnExit() {}
bool AscsAseStateMachine::StateQosConfigured::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC: {
      sm->qos_configuration = std::nullopt;
      auto& codec_config = GetEventData<ascs::AseStateCodecConfiguration>(p_data);
      sm->TrackEvent(
              LeAudioEventTracker::EventType::SUBEVENT,
              "{}, event: {}, codec_id: {{format:0x{:02x}, company:0x{:04x}, codec:0x{:04x}}}",
              sm->GetHeaderString(), Events(event), codec_config.codec_id.coding_format,
              codec_config.codec_id.vendor_company_id, codec_config.codec_id.vendor_codec_id);
      sm->codec_configuration = codec_config;
      sm->AsyncTransitionTo(StateId::CODEC_CONFIGURED);
      return true;
    }

    case CONFIG_QOS: {
      auto& sm_params =
              GetEventData<std::pair<ascs::AseStateQosConfiguration, ascs::DataPathConfiguration>>(
                      p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT,
                     "{}, event: {}, sdu_interval: {}, framing: {}, phy: {}", sm->GetHeaderString(),
                     Events(event), sm_params.first.sdu_interval, sm_params.first.framing,
                     sm_params.first.phy);
      sm->qos_configuration = sm_params.first;
      sm->data_path_configuration = sm_params.second;
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;
    }

    case RELEASE: {
      auto const is_caching = GetEventData<bool>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}, is_caching: {}",
                     sm->GetHeaderString(), Events(event), is_caching);
      if (!is_caching) {
        sm->codec_configuration = std::nullopt;
        log::debug("No valid cached codec configuration, going to IDLE");
      }
      sm->AsyncTransitionTo(StateId::RELEASING);
      return true;
    }

    case ENABLE: {
      auto& metadata = GetEventData<std::vector<uint8_t>>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {},  metadata size: {}",
                     sm->GetHeaderString(), Events(event), metadata.size());
      sm->metadata = metadata;
      sm->AsyncTransitionTo(StateId::ENABLING);
      return true;
    }

    case DISABLE:
      // Ignore
      break;
    case RECEIVER_START_READY:
      // Ignore
      break;
    case RECEIVER_STOP_READY:
      // Ignore
      break;
    case UPDATE_METADATA:
      break;
    case CIS_ASSIGNED:
      // No action required
      break;
    case CIS_LOST:
      // No action required
      return true;
    case VALIDATE_ENABLE:
      return true;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: ENABLING
void AscsAseStateMachine::StateEnabling::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());

  log::assert_that(sm->metadata.has_value(), "Invalid metadata");
  log::assert_that(sm->codec_configuration.has_value(), "Invalid codec configuration");
  log::assert_that(sm->qos_configuration.has_value(), "Invalid QoS configuration");

  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }
}
void AscsAseStateMachine::StateEnabling::OnExit() {}
bool AscsAseStateMachine::StateEnabling::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC:
      // Ignore
      break;
    case CONFIG_QOS:
      // Ignore
      break;

    case RELEASE: {
      auto const is_caching = GetEventData<bool>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}, is_caching: {}",
                     sm->GetHeaderString(), Events(event), is_caching);
      if (!is_caching) {
        sm->codec_configuration = std::nullopt;
        log::debug("No valid cached codec configuration, going to IDLE");
      }
      sm->AsyncTransitionTo(StateId::RELEASING);
      return true;
    }

    case ENABLE:
      // Ignore
      break;

    case DISABLE:
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}",
                     sm->GetHeaderString(), Events(event));
      if (sm->is_source_ase_) {
        sm->AsyncTransitionTo(StateId::DISABLING);
      } else {
        sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      }
      return true;

    // Note: This is sent by AseManager when acting as Sink ASE and by peer device when Source ASE
    case RECEIVER_START_READY:
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}",
                     sm->GetHeaderString(), Events(event));
      sm->AsyncTransitionTo(StateId::STREAMING);
      return true;

    case RECEIVER_STOP_READY:
      // Ignore
      break;

    case UPDATE_METADATA: {
      auto& metadata = GetEventData<std::vector<uint8_t>>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {},  metadata size: {}",
                     sm->GetHeaderString(), Events(event), metadata.size());
      sm->metadata = metadata;
      sm->AsyncTransitionTo(StateId::ENABLING);
      return true;
    }
    case CIS_ASSIGNED:
      // No action required
      return true;

    case CIS_LOST:
      sm->TrackEvent(LeAudioEventTracker::EventType::POINT, "{}, event: {}", sm->GetHeaderString(),
                     Events(event));
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: DISABLING
void AscsAseStateMachine::StateDisabling::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());

  log::assert_that(sm->metadata.has_value(), "Invalid metadata");
  log::assert_that(sm->codec_configuration.has_value(), "Invalid codec configuration");
  log::assert_that(sm->qos_configuration.has_value(), "Invalid QoS configuration");

  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }
}
void AscsAseStateMachine::StateDisabling::OnExit() { sm->data_path_configuration = std::nullopt; }
bool AscsAseStateMachine::StateDisabling::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC:
      // Ignore
      break;
    case CONFIG_QOS:
      // Ignore
      break;

    case RELEASE: {
      auto const is_caching = GetEventData<bool>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}, is_caching: {}",
                     sm->GetHeaderString(), Events(event), is_caching);
      if (!is_caching) {
        sm->codec_configuration = std::nullopt;
      }
      sm->AsyncTransitionTo(StateId::RELEASING);
      return true;
    }

    case ENABLE:
      // Ignore
      break;
    case DISABLE:
      // Ignore
      break;
    case RECEIVER_START_READY:
      // Ignore
      break;

    case RECEIVER_STOP_READY:
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}",
                     sm->GetHeaderString(), Events(event));
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;

    case UPDATE_METADATA:
      // Ignore
      break;

    case CIS_LOST:
      sm->TrackEvent(LeAudioEventTracker::EventType::POINT, "{}, event: {}", sm->GetHeaderString(),
                     Events(event));
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: STREAMING
void AscsAseStateMachine::StateStreaming::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());

  log::assert_that(sm->metadata.has_value(), "Invalid metadata");
  log::assert_that(sm->codec_configuration.has_value(), "Invalid codec configuration");
  log::assert_that(sm->qos_configuration.has_value(), "Invalid QoS configuration");

  sm->SetEnableConfirmed(false);

  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }
}
void AscsAseStateMachine::StateStreaming::OnExit() {}
bool AscsAseStateMachine::StateStreaming::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC:
      // Ignore
      break;
    case CONFIG_QOS:
      // Ignore
      break;

    case RELEASE: {
      auto const is_caching = GetEventData<bool>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}, is_caching: {}",
                     sm->GetHeaderString(), Events(event), is_caching);
      if (!is_caching) {
        sm->codec_configuration = std::nullopt;
      }
      sm->AsyncTransitionTo(StateId::RELEASING);
      return true;
    }

    case ENABLE:
      // Ignore
      break;

    case DISABLE:
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {}",
                     sm->GetHeaderString(), Events(event));
      if (sm->is_source_ase_) {
        sm->AsyncTransitionTo(StateId::DISABLING);
      } else {
        sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      }
      return true;

    case RECEIVER_START_READY:
      // Ignore
      break;
    case RECEIVER_STOP_READY:
      // Ignore
      break;

    case UPDATE_METADATA: {
      auto& metadata = GetEventData<std::vector<uint8_t>>(p_data);
      sm->TrackEvent(LeAudioEventTracker::EventType::SUBEVENT, "{}, event: {},  metadata size: {}",
                     sm->GetHeaderString(), Events(event), metadata.size());
      sm->metadata = metadata;
      sm->AsyncTransitionTo(StateId::STREAMING);
      return true;
    }

    case CIS_LOST:
      sm->TrackEvent(LeAudioEventTracker::EventType::POINT, "{}, event: {}", sm->GetHeaderString(),
                     Events(event));
      sm->AsyncTransitionTo(StateId::QOS_CONFIGURED);
      return true;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

// State: RELEASING
void AscsAseStateMachine::StateReleasing::OnEnter() {
  log::debug("{}", sm->GetHeaderString());
  sm->TrackEvent(LeAudioEventTracker::EventType::TRANSITION, "{} -> {}", sm->GetPreviousStateId(),
                 sm->GetStateId());

  if (sm->callbacks_) {
    sm->callbacks_->OnAseTransition(sm->ase_id_, sm->peer_);
  }

  sm->data_path_configuration = std::nullopt;
  sm->metadata = std::nullopt;
  sm->qos_configuration = std::nullopt;

  if (sm->codec_configuration) {
    // Released (caching)
    sm->AsyncTransitionTo(StateId::CODEC_CONFIGURED);
  } else {
    // Released (no caching)
    sm->AsyncTransitionTo(StateId::IDLE);
  }
}
void AscsAseStateMachine::StateReleasing::OnExit() {}
bool AscsAseStateMachine::StateReleasing::ProcessEvent(uint32_t event, void* p_data) {
  log::debug("{}, event: {}", sm->GetHeaderString(), Events(event));

  switch (event) {
    case CONFIG_CODEC:
      // Ignore
      break;
    case CONFIG_QOS:
      // Ignore
      break;
    case RELEASE:
      // Ignore
      break;
    case ENABLE:
      // Ignore
      break;
    case DISABLE:
      // Ignore
      break;
    case RECEIVER_START_READY:
      // Ignore
      break;
    case RECEIVER_STOP_READY:
      // Ignore
      break;
    case UPDATE_METADATA:
      // Ignore
      break;
  }

  log::warn("{}, Unprocessed event: {}, data: {}", sm->GetHeaderString(), Events(event),
            p_data != nullptr ? "Some Data" : "nullptr");
  return false;
}

void AscsAseStateMachine::SyncTransitionTo(int dest_state_id) {
  log::verbose("{}, target state: {}", GetHeaderString(), StateId(dest_state_id));
  TransitionTo(dest_state_id);
}

void AscsAseStateMachine::AsyncTransitionTo(int dest_state_id) {
  log::verbose("{}, target state: {}", GetHeaderString(), StateId(dest_state_id));
  do_in_main_thread(base::BindOnce(&AscsAseStateMachine::SyncTransitionTo,
                                   weak_factory_.GetWeakPtr(), dest_state_id));
}

std::string AscsAseStateMachine::GetHeaderString() const {
  std::stringstream stream;
  stream << (is_source_ase_ ? "Source" : "Sink") << " ASE id:" << +ase_id_;
  stream << ", state: " << GetStateId();
  stream << ", peer_address: " << peer_.ToString();

  return stream.str();
}

void AscsAseStateMachine::Dump(std::stringstream& stream) const {
  stream << "      " << GetHeaderString() << "\n";
  stream << "        cis_conn_handle: 0x" << std::hex << cis_conn_handle_ << std::dec << "\n";
  stream << "        previous_state: " << GetPreviousStateId() << "\n";

  if (codec_configuration) {
    stream << "        codec_configuration: \n";
    stream << "          codec_id: " << codec_configuration->codec_id << "\n";
    stream << "          framing: " << +codec_configuration->framing << "\n";
    stream << "          preferred_phy: " << +codec_configuration->preferred_phy << "\n";
    stream << "          preferred_retrans_nb: " << +codec_configuration->preferred_retrans_nb
           << "\n";
    stream << "          max_transport_latency: " << codec_configuration->max_transport_latency
           << "\n";
    stream << "          presentation_delay: " << codec_configuration->pres_delay_min << "-"
           << codec_configuration->pres_delay_max << "\n";
    stream << "          preferred_presentation_delay: "
           << codec_configuration->preferred_pres_delay_min << "-"
           << codec_configuration->preferred_pres_delay_max << "\n";
  }

  if (qos_configuration) {
    stream << "        qos_configuration: \n";
    stream << "          cig_id: " << +qos_configuration->cig_id << "\n";
    stream << "          cis_id: " << +qos_configuration->cis_id << "\n";
    stream << "          sdu_interval: " << qos_configuration->sdu_interval << "\n";
    stream << "          framing: " << +qos_configuration->framing << "\n";
    stream << "          phy: " << +qos_configuration->phy << "\n";
    stream << "          max_sdu: " << qos_configuration->max_sdu << "\n";
    stream << "          retrans_nb: " << +qos_configuration->retrans_nb << "\n";
    stream << "          max_transport_latency: " << qos_configuration->max_transport_latency
           << "\n";
    stream << "          presentation_delay: " << qos_configuration->pres_delay << "\n";
  }

  if (metadata) {
    stream << "        metadata: " << base::HexEncode(metadata->data(), metadata->size()) << "\n";
  }
}

std::ostream& operator<<(std::ostream& out, const AscsAseStateMachine::StateId& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, IDLE);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, CODEC_CONFIGURED);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, QOS_CONFIGURED);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, ENABLING);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, DISABLING);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, STREAMING);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::StateId, RELEASING);
    default:
      ch = "Invalid ASE State ID";
      break;
  }
  return out << ch << " (0x" << std::hex << static_cast<int>(value) << ")";
}

std::ostream& operator<<(std::ostream& out, const AscsAseStateMachine::Events& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, CONFIG_CODEC);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, CONFIG_QOS);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, ENABLE);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, RECEIVER_START_READY);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, DISABLE);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, RECEIVER_STOP_READY);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, UPDATE_METADATA);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, RELEASE);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, CIS_LOST);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, CIS_ASSIGNED);
    CASE_SET_PTR_TO_TOKEN_STR(AscsAseStateMachine::Events, VALIDATE_ENABLE);
    default:
      ch = "Invalid ASE State Machine event";
      break;
  }
  return out << ch << " (0x" << std::hex << static_cast<int>(value) << ")";
}

}  // namespace bluetooth::le_audio
