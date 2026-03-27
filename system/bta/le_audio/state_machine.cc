/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#include "state_machine.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <bluetooth/types/bt_transport.h>
#include <bluetooth/types/string_helpers.h>
#include <com_android_bluetooth_flags.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "bta_gatt_queue.h"
#include "client_parser.h"
#include "codec_manager.h"
#include "device_groups.h"
#include "devices.h"
#include "hardware/bt_le_audio.h"
#include "hci/hci_packets.h"
#include "internal_include/bt_trace.h"
#include "le_audio_health_status.h"
#include "le_audio_log_history.h"
#include "le_audio_types.h"
#include "le_audio_utils.h"
#include "osi/include/alarm.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/btm_iso_api_types.h"
#include "stack/include/gatt_api.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/hcimsgs.h"

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif  // TARGET_FLOSS

// clang-format off
/* ASCS state machine 1.0
 *
 * State machine manages group of ASEs to make transition from one state to
 * another according to specification and keeping involved necessary externals
 * like: ISO, CIG, ISO data path, audio path form/to upper layer.
 *
 * GroupStream (API): GroupStream method of this le audio implementation class
 *                    object should allow transition from Idle (No Caching),
 *                    Codec Configured (Caching after release) state to
 *                    Streaming for all ASEs in group within time limit. Time
 *                    limit should keep safe whole state machine from being
 *                    stucked in any in-middle state, which is not a destination
 *                    state.
 *
 *                    TODO Second functionality of streaming should be switch
 *                    context which will base on previous state, context type.
 *
 * GroupStop (API): GroupStop method of this le audio implementation class
 *                  object should allow safe transition from any state to Idle
 *                  or Codec Configured (if caching supported).
 *
 * ╔══════════════════╦═════════════════════════════╦══════════════╦══════════════════╦══════╗
 * ║  Current State   ║ ASE Control Point Operation ║    Result    ║    Next State    ║ Note ║
 * ╠══════════════════╬═════════════════════════════╬══════════════╬══════════════════╬══════╣
 * ║ Idle             ║ Config Codec                ║ Success      ║ Codec Configured ║  +   ║
 * ║ Codec Configured ║ Config Codec                ║ Success      ║ Codec Configured ║  -   ║
 * ║ Codec Configured ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Codec Configured ║ Config QoS                  ║ Success      ║ QoS Configured   ║  +   ║
 * ║ QoS Configured   ║ Config Codec                ║ Success      ║ Codec Configured ║  -   ║
 * ║ QoS Configured   ║ Config QoS                  ║ Success      ║ QoS Configured   ║  -   ║
 * ║ QoS Configured   ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ QoS Configured   ║ Enable                      ║ Success      ║ Enabling         ║  +   ║
 * ║ Enabling         ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Enabling         ║ Update Metadata             ║ Success      ║ Enabling         ║  -   ║
 * ║ Enabling         ║ Disable                     ║ Success      ║ Disabling        ║  -   ║
 * ║ Enabling         ║ Receiver Start Ready        ║ Success      ║ Streaming        ║  +   ║
 * ║ Streaming        ║ Update Metadata             ║ Success      ║ Streaming        ║  -   ║
 * ║ Streaming        ║ Disable                     ║ Success      ║ Disabling        ║  +   ║
 * ║ Streaming        ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Disabling        ║ Receiver Stop Ready         ║ Success      ║ QoS Configured   ║  +   ║
 * ║ Disabling        ║ Release                     ║ Success      ║ Releasing        ║  +   ║
 * ║ Releasing        ║ Released (no caching)       ║ Success      ║ Idle             ║  +   ║
 * ║ Releasing        ║ Released (caching)          ║ Success      ║ Codec Configured ║  -   ║
 * ╚══════════════════╩═════════════════════════════╩══════════════╩══════════════════╩══════╝
 *
 * + - supported transition
 * - - not supported
 */
// clang-format on

using bluetooth::common::ToString;
using bluetooth::hci::IsoManager;
using bluetooth::le_audio::GroupStreamStatus;
using bluetooth::le_audio::LeAudioDevice;
using bluetooth::le_audio::LeAudioDeviceGroup;
using bluetooth::le_audio::LeAudioGroupStateMachine;
using bluetooth::le_audio::StateMachineInvalidStatus;

using bluetooth::hci::ErrorCode;
using bluetooth::hci::ErrorCodeText;
using bluetooth::le_audio::CodecManager;
using bluetooth::le_audio::DsaMode;
using bluetooth::le_audio::DsaModes;
using bluetooth::le_audio::types::ase;
using bluetooth::le_audio::types::AseState;
using bluetooth::le_audio::types::AudioContexts;
using bluetooth::le_audio::types::BidirectionalPair;
using bluetooth::le_audio::types::CigState;
using bluetooth::le_audio::types::CisState;
using bluetooth::le_audio::types::DataPathState;
using bluetooth::le_audio::types::LeAudioContextType;
using bluetooth::le_audio::types::LeAudioLtvMap;

using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeCodecConfiguration;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeDisable;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeEnable;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeQosConfiguration;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeReceiverStartReady;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeReceiverStopReady;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeRelease;
using bluetooth::le_audio::client_parser::ascs::kCtpOpcodeUpdateMetadata;

namespace {

using namespace bluetooth;

constexpr int kNumberOfCisRetries = 2;

class LeAudioGroupStateMachineImpl;
LeAudioGroupStateMachineImpl* instance;

class LeAudioGroupStateMachineImpl : public LeAudioGroupStateMachine {
public:
  LeAudioGroupStateMachineImpl(Callbacks* state_machine_callbacks,
                               bluetooth::hci::iso_manager::IsoClientHandle iso_client_handle)
      : state_machine_callbacks_(state_machine_callbacks),
        watchdog_(alarm_new("LeAudioStateMachineTimer")), iso_client_handle_(iso_client_handle) {
    log_history_ = LeAudioLogHistory::Get();
  }

  ~LeAudioGroupStateMachineImpl() {
    alarm_free(watchdog_);
    watchdog_ = nullptr;
    log_history_->Cleanup();
    log_history_ = nullptr;
  }

  bool AttachToStream(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                      BidirectionalPair<std::vector<uint8_t>> ccids) override {
    log::info("group id: {} device: {}", group->group_id_, leAudioDevice->address_);

    /* This function is used to attach the device to the stream.
     * Limitation here is that device should be previously in the streaming
     * group and just got reconnected.
     */
    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
        group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::error("Group {} is not streaming or is in transition, state: {}, target state: {}",
                 group->group_id_, ToString(group->GetState()), ToString(group->GetTargetState()));
      return false;
    }

    /* This is cautious - mostly needed for unit test only */
    auto group_metadata_contexts = get_bidirectional(group->GetMetadataContexts());
    auto device_available_contexts = leAudioDevice->GetAvailableContexts();
    if (!group_metadata_contexts.test_any(device_available_contexts)) {
      log::info("{} does is not have required context type", leAudioDevice->address_);
      return false;
    }

    /* If remote device is in QoS state, go to enabling state. */
    if (leAudioDevice->HaveActiveAse() &&
        leAudioDevice->HaveAllActiveAsesSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
      log::info("{} in QoS state, proceed to Enable state", leAudioDevice->address_);
      PrepareAndSendEnable(leAudioDevice,
                           state_machine_callbacks_->OnGetEnabledDirections(group->group_id_));
      return true;
    }

    /* Invalidate configuration to make sure it is chosen properly when new
     * member connects
     */
    group->InvalidateCachedConfigurations();

    if (!group->Configure(group->GetConfigurationContextType(), group->GetMetadataContexts(),
                          ccids)) {
      log::error("failed to set ASE configuration");
      return false;
    }

    return PrepareAndSendCodecConfigure(group, leAudioDevice);
  }

  bool StartStream(LeAudioDeviceGroup* group, LeAudioContextType context_type,
                   const BidirectionalPair<AudioContexts>& metadata_context_types,
                   BidirectionalPair<std::vector<uint8_t>> ccid_lists) override {
    log::info("current state: {}", ToString(group->GetState()));

    switch (group->GetState()) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        if (group->IsConfiguredForContext(context_type)) {
          if (group->Activate(context_type, metadata_context_types, ccid_lists)) {
            SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
            if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
              if (group->cig.GetState() == CigState::CREATED) {
                if (PrepareAndSendQoSToTheGroup(group)) {
                  return true;
                }
                ClearGroup(group, true);
                return false;
              }
              group->cig.GenerateCisIds(context_type);
            }
            if (CigCreate(group)) {
              return true;
            }
          }
          log::info("Could not activate device, try to configure it again");
        }

        /* Deactivate previousely activated ASEs in case if there were just a
         * reconfiguration (group target state as CODEC CONFIGURED) and no
         * deactivation. Currently activated ASEs cannot be used for different
         * context.
         */
        group->Deactivate();

        /* We are going to reconfigure whole group. Clear Cises.*/
        ReleaseCisIds(group);

        /* If configuration is needed but for sure CIG does not exist at this moment */
        [[fallthrough]];

      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
        if (!group->Configure(context_type, metadata_context_types, ccid_lists)) {
          log::error("failed to set ASE configuration");
          return false;
        }

        /* We are here only when CIG exists in idle state which might be due to controller
         * misbehavior */
        if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig() &&
            group->cig.GetState() == CigState::CREATED) {
          log::warn("Cig is created for group_id: {}", group->group_id_);
          group->cig.PrintCigState();
        } else {
          group->cig.GenerateCisIds(context_type);
        }

        /* All ASEs should aim to achieve target state */
        SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        if (!PrepareAndSendCodecConfigToTheGroup(group)) {
          group->PrintDebugState();
          ClearGroup(group, true);
        }
        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED: {
        LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
        if (!leAudioDevice) {
          group->PrintDebugState();
          log::error("group_id: {} has no active devices", group->group_id_);
          return false;
        }

        bool send_codec_config = false;
        bool wait_for_cig_removal = false;
        /* All ASEs should aim to achieve target state */
        SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

        if (!group->IsConfiguredForContext(context_type)) {
          log::info(
                  "Looks like configuration has changed for group_id: {}, try to "
                  "reconfigure.",
                  group->group_id_);

          const auto old_cig_hash = group->stream_conf.configuration_hash;
          if (!group->Configure(context_type, metadata_context_types, ccid_lists)) {
            log::error("Trying to start stream not configured for the context {} in group_id: {} ",
                       ToString(context_type), group->group_id_);
            group->PrintDebugState();
            StopStream(group);
            return false;
          }

          /* Check new CIG hash if it match to the old one. If yes, it means we can keep CIG and
           * just do reconfiguration */
          if (old_cig_hash != group->stream_conf.configuration_hash) {
            log::info("Need to reconfigure CIG as something has changed.");

            ReleaseCisIds(group);
            if (!com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
              group->cig.GenerateCisIds(context_type);
            }

            wait_for_cig_removal = true;

            if (!RemoveCigForGroup(group, CigState::RECONFIGURING)) {
              group->PrintDebugState();
              StopStream(group);
              return false;
            }
          } else {
            log::debug("CIG can stay, just reconfigure ASEs on remote device(s)");
            send_codec_config = true;
          }
        }

        log::debug("group_id: {}, wait_for_cig_removal: {}, send_codec_config: {}",
                   group->group_id_, wait_for_cig_removal, send_codec_config);

        // Even stream is already configured for the context, update the metadata.
        group->SetMetadataContexts(metadata_context_types);
        group->UpdateMetadataForActiveAndNotStreamingAses(ccid_lists);

        if (wait_for_cig_removal) {
          log::debug("Continue after CIG is removed");
          return true;
        }

        if (send_codec_config) {
          PrepareAndSendCodecConfigToTheGroup(group);
        } else {
          PrepareAndSendEnableToTheGroup(group);
        }
        break;
      }

      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        /* This case just updates the metadata for the stream, in case
         * stream configuration is satisfied. We can do that already for
         * all the devices in a group, without any state transitions.
         */
        if (!group->IsMetadataChanged(metadata_context_types, ccid_lists)) {
          return true;
        }

        group->SetMetadataContexts(metadata_context_types);

        LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
        if (!leAudioDevice) {
          log::error("group has no active devices");
          return false;
        }

        while (leAudioDevice) {
          PrepareAndSendUpdateMetadata(group, leAudioDevice, metadata_context_types, ccid_lists);
          leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
        }
        break;
      }

      default:
        log::error("Unable to transit from {}", ToString(group->GetState()));
        return false;
    }

    return true;
  }

  bool ConfigureStream(LeAudioDeviceGroup* group, LeAudioContextType context_type,
                       const BidirectionalPair<AudioContexts>& metadata_context_types,
                       BidirectionalPair<std::vector<uint8_t>> ccid_lists,
                       bool configure_qos) override {
    if (group->GetState() > AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) {
      log::error("Stream should be stopped or in configured stream. Current state: {}",
                 ToString(group->GetState()));
      return false;
    }

    if (configure_qos) {
      if (group->IsConfiguredForContext(context_type)) {
        if (group->Activate(context_type, metadata_context_types, ccid_lists)) {
          SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
          if (CigCreate(group)) {
            return true;
          }
        }
      }
      log::info("Could not activate device, try to configure it again");
    }

    group->Deactivate();
    ReleaseCisIds(group);

    if (!group->Configure(context_type, metadata_context_types, ccid_lists)) {
      log::error("Could not configure ASEs for group {} content type {}", group->group_id_,
                 int(context_type));

      return false;
    }

    group->cig.GenerateCisIds(context_type);
    if (configure_qos) {
      SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    } else {
      SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
    }
    return PrepareAndSendCodecConfigToTheGroup(group);
  }

  bool EnableStreamingDirection(LeAudioDeviceGroup* group, uint8_t remote_direction) {
    if (group == nullptr) {
      log::error("Group is null");
      return false;
    }

    if (!(group->GetActiveQoSConfiguredDirections() & remote_direction)) {
      log::error("group_id: {} is not in valid state to enable streaming for direction: {}",
                 group->group_id_, remote_direction);
      group->PrintDebugState();
      return false;
    }

    auto metadata = group->GetMetadataContexts();
    if (metadata.get(remote_direction).none()) {
      log::error("group_id: {}, there is NO metadata for direction: {}", group->group_id_,
                 remote_direction);
      group->PrintDebugState();
      return false;
    }

    return PrepareAndSendEnableToTheGroup(group, remote_direction);
  }

  bool DisableStreamingDirection(LeAudioDeviceGroup* group, uint8_t remote_direction) {
    if (group == nullptr) {
      log::error("Group is null");
      return false;
    }

    if (!(group->GetActiveEnabledDirections() & remote_direction)) {
      log::error("group_id: {} is not in valid state to disable streaming for direction: {}",
                 group->group_id_, remote_direction);
      return false;
    }

    return PrepareAndSendDisableToTheGroup(group, remote_direction) ==
           GroupStreamStatus::SUSPENDING;
  }

  void SuspendStream(LeAudioDeviceGroup* group) override {
    /* All ASEs should aim to achieve target state */
    SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    auto status = PrepareAndSendDisableToTheGroup(group);
    state_machine_callbacks_->StatusReportCb(group->group_id_, status);
  }

  void StopStream(LeAudioDeviceGroup* group) override {
    if (group->IsReleasingOrIdle()) {
      log::info("group: {} in_transition: {}, current_state {}", group->group_id_,
                group->IsInTransition(), ToString(group->GetState()));
      return;
    }

    /* All Ases should aim to achieve target state */
    SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

    auto status = PrepareAndSendReleaseToTheGroup(group);
    state_machine_callbacks_->StatusReportCb(group->group_id_, status);
  }

  void notifyLeAudioHealth(LeAudioDeviceGroup* group,
                           bluetooth::le_audio::LeAudioHealthGroupStatType stat) {
    auto leAudioHealthStatus = bluetooth::le_audio::LeAudioHealthStatus::Get();
    if (leAudioHealthStatus) {
      leAudioHealthStatus->AddStatisticForGroup(group, stat);
    }
  }

  void processReleaseCtpFailed(
          LeAudioDevice* leAudioDevice,
          const struct bluetooth::le_audio::client_parser::ascs::ctp_ase_entry& ase_entry) {
    log::warn(
            "Release failed for group_id: {},  {}, ase: {}, last_ase_ctp_command_sent: {:#x}, "
            "error: {:#x}, reason: {:#x}, let watchdog to fire",
            leAudioDevice->group_id_, leAudioDevice->address_, ase_entry.ase_id,
            leAudioDevice->last_ase_ctp_command_sent, ase_entry.response_code, ase_entry.reason);
  }

  void processCommonCtpFailed(
          uint8_t op, LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
          const struct bluetooth::le_audio::client_parser::ascs::ctp_ase_entry& ase_entry) {
    auto release_sent_to_remote = PrepareAndSendRelease(leAudioDevice);
    auto active_devices = group->GetNumOfActiveDevices();

    int releasing_devices = 0;
    for (auto dev = group->GetFirstActiveDevice(); dev; dev = group->GetNextActiveDevice(dev)) {
      if (dev->last_ase_ctp_command_sent ==
          bluetooth::le_audio::client_parser::ascs::kCtpOpcodeRelease) {
        releasing_devices++;
      }
    }

    log::error(
            "Releasing ASE due to control point error for {}, ase: {}, opcode: {:#x}, "
            "last_ase_ctp_command_sent: {:#x}, error: "
            "{:#x}, reason: {:#x}. release_sent_to_remote: {}, active_devices: {}, "
            "releasing_devices: {}",
            leAudioDevice->address_, ase_entry.ase_id, op, leAudioDevice->last_ase_ctp_command_sent,
            ase_entry.response_code, ase_entry.reason, release_sent_to_remote, active_devices,
            releasing_devices);

    // If there is no active devices it means, the whole set got released
    if (releasing_devices == 0 && active_devices == 0) {
      /* No remote communication expected */
      ClearGroup(group, true);
      notifyLeAudioHealth(
              group,
              bluetooth::le_audio::LeAudioHealthGroupStatType::STREAM_CREATE_SIGNALING_FAILED);
    } else if (active_devices != 0 && releasing_devices == active_devices) {
      group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::RELEASING);
      notifyLeAudioHealth(
              group,
              bluetooth::le_audio::LeAudioHealthGroupStatType::STREAM_CREATE_SIGNALING_FAILED);
    }
  }

  void ProcessGattCtpNotification(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                  uint8_t* value, uint16_t len) {
    auto ntf = std::make_unique<struct bluetooth::le_audio::client_parser::ascs::ctp_ntf>();

    bool valid_notification = ParseAseCtpNotification(*ntf, len, value);
    if (group == nullptr) {
      log::warn("Notification received to invalid group");
      return;
    }

    /* State machine looks on ASE state and base on it take decisions.
     * If ASE state is not achieve on time, timeout is reported and upper
     * layer mostlikely drops ACL considers that remote is in bad state.
     * However, it might happen that remote device rejects ASE configuration for
     * some reason and ASCS specification defines tones of different reasons.
     * Maybe in the future we will be able to handle all of them but for now it
     * seems to be important to allow remote device to reject ASE configuration
     * when stream is creating. e.g. Allow remote to reject Enable on unwanted
     * context type.
     */

    auto target_state = group->GetTargetState();
    auto current_state = group->GetState();
    auto in_transition = group->IsInTransition();
    if (!in_transition || target_state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::debug(
              "Not interested in ctp result for group {} inTransition: {} , targetState: {}, "
              "currentState: {}",
              group->group_id_, in_transition, ToString(target_state), ToString(current_state));
      return;
    }

    if (!valid_notification) {
      /* Do nothing, just allow guard timer to fire */
      log::error("Invalid CTP notification for group {}", group->group_id_);
      return;
    }

    for (auto& entry : ntf->entries) {
      // release ASEs on device which did not accept control point command
      if (entry.response_code ==
          bluetooth::le_audio::client_parser::ascs::kCtpResponseCodeSuccess) {
        continue;
      }

      // Handle error cases
      switch (ntf->op) {
        case kCtpOpcodeRelease:
          processReleaseCtpFailed(leAudioDevice, entry);
          return;
        case kCtpOpcodeCodecConfiguration:
        case kCtpOpcodeDisable:
        case kCtpOpcodeEnable:
        case kCtpOpcodeReceiverStartReady:
        case kCtpOpcodeReceiverStopReady:
        case kCtpOpcodeUpdateMetadata:
        case kCtpOpcodeQosConfiguration:
          processCommonCtpFailed(ntf->op, group, leAudioDevice, entry);
          return;
      }
    }

    log::debug("Ctp result OK for group {} inTransition: {} , targetState: {}, currentState: {}",
               group->group_id_, in_transition, ToString(target_state), ToString(current_state));
  }

  static void set_ase_data_path(const RawAddress& addr, struct ase* ase, DataPathState state) {
    log::debug("{}, ase_id: {}: {} -> {}", addr, ase->id,
               bluetooth::common::ToString(ase->data_path_state),
               bluetooth::common::ToString(state));
    ase->data_path_state = state;
  }

  void ProcessGattNotifEvent(uint8_t* value, uint16_t len, struct ase* ase,
                             LeAudioDevice* leAudioDevice, LeAudioDeviceGroup* group) override {
    struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr arh;

    ParseAseStatusHeader(arh, len, value);

    if (ase->id == 0x00) {
      /* Initial state of Ase - update id */
      log::info(", discovered ase id: {}", arh.id);
      ase->id = arh.id;
    }

    auto state = static_cast<AseState>(arh.state);

    log::info("{} , ASE id: {}, state changed {} -> {}", leAudioDevice->address_, ase->id,
              ToString(ase->state), ToString(state));

    log_history_->AddLogHistory(kLogAseStateNotif, leAudioDevice->group_id_,
                                leAudioDevice->address_,
                                "ASE_ID " + std::to_string(arh.id) + ": " + ToString(state),
                                "curr: " + ToString(ase->state));

    switch (state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
        AseStateMachineProcessIdle(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        AseStateMachineProcessCodecConfigured(
                arh, ase, value + bluetooth::le_audio::client_parser::ascs::kAseRspHdrMinLen,
                len - bluetooth::le_audio::client_parser::ascs::kAseRspHdrMinLen, group,
                leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        AseStateMachineProcessQosConfigured(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        AseStateMachineProcessEnabling(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        AseStateMachineProcessStreaming(
                arh, ase, value + bluetooth::le_audio::client_parser::ascs::kAseRspHdrMinLen,
                len - bluetooth::le_audio::client_parser::ascs::kAseRspHdrMinLen, group,
                leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING:
        AseStateMachineProcessDisabling(arh, ase, group, leAudioDevice);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING:
        AseStateMachineProcessReleasing(arh, ase, group, leAudioDevice);
        break;
      default:
        log::error("Wrong AES state: {}", static_cast<int>(arh.state));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE);
        break;
    }
  }

  void ProcessHciNotifOnCigCreate(LeAudioDeviceGroup* group, uint8_t status, uint8_t /*cig_id*/,
                                  std::vector<uint16_t> conn_handles) override {
    /* TODO: What if not all cises will be configured ?
     * conn_handle.size() != active ases in group
     */

    if (!group) {
      log::error(", group is null");
      return;
    }

    log_history_->AddLogHistory(kLogHciEvent, group->group_id_, RawAddress::kEmpty,
                                kLogCisCreateOp + "STATUS=" + loghex(status));

    if (status != HCI_SUCCESS) {
      if (status == HCI_ERR_COMMAND_DISALLOWED) {
        /*
         * We are here, because stack has no chance to remove CIG when it was
         * shut during streaming. In the same time, controller probably was not
         * Reseted, which creates the issue. Lets remove CIG and try to create
         * it again.
         */
        group->cig.SetState(CigState::RECOVERING);
        IsoManager::GetInstance()->RemoveCig(group->group_id_, true);
        return;
      }

      group->cig.SetState(CigState::NONE);
      log::error(", failed to create CIG, reason: 0x{:02x}, new cig state: {}", status,
                 ToString(group->cig.GetState()));
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIG);
      return;
    }

    log::assert_that(group->cig.GetState() == CigState::CREATING,
                     "Unexpected CIG creation group id: {}, cig state: {}", group->group_id_,
                     ToString(group->cig.GetState()));

    group->cig.SetState(CigState::CREATED);
    log::info("Group: {}, id: {} cig state: {}, number of cis handles: {}", std::format_ptr(group),
              group->group_id_, ToString(group->cig.GetState()),
              static_cast<int>(conn_handles.size()));

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
        group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
      /* Group is not going to stream. It happen while CIG was creating.
       * Remove CIG in such a case
       */
      log::warn("group_id {} is not going to stream anymore. Remove CIG.", group->group_id_);
      group->PrintDebugState();
      RemoveCigForGroup(group);
      return;
    }

    /* Assign all connection handles to CIS ids of the CIG */
    group->cig.AssignCisConnHandles(conn_handles);

    /* Assign all connection handles to multiple device ASEs */
    group->AssignCisConnHandlesToAses();

    /* No need to handle return here. If QoS fails, PrepareAndSendQoSToTheGroup will handle the
     * error path. */
    PrepareAndSendQoSToTheGroup(group);
  }

  void ProcessHciNotifyOnCigRemoveRecovering(uint8_t status, LeAudioDeviceGroup* group) {
    group->cig.SetState(CigState::NONE);

    if (status != HCI_SUCCESS) {
      log::error(
              "Could not recover from the COMMAND DISALLOAD on CigCreate. Status "
              "on CIG remove is 0x{:02x}",
              status);
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIG);
      return;
    }

    log::info("Succeed on CIG Recover - back to creating CIG");
    if (!CigCreate(group)) {
      if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
        /* If CIG recovery did not succeed let's clear cig.cises. */
        group->cig.ClearCisIds();
      }

      log::error("Could not create CIG. Stop the stream for group {}", group->group_id_);
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIG);
    }
  }

  void ProcessHciNotifOnCigRemoveDefault(uint8_t status, LeAudioDeviceGroup* group) {
    if (status != HCI_SUCCESS) {
      /* Move state back to Created */
      group->cig.SetState(CigState::CREATED);
      log::error("failed to remove cig, id: {}, status 0x{:02x}, new cig state: {}",
                 group->group_id_, status, ToString(group->cig.GetState()));
      return;
    }
    if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
      group->cig.ClearCisIds();
    }
    group->cig.SetState(CigState::NONE);
  }

  void ProcessHciNotifyOnCigRemoveReconfiguring(uint8_t status, LeAudioDeviceGroup* group) {
    /* We are here because we wanted to remove and restart configuration */
    if (status != HCI_SUCCESS) {
      log::error("Could not remove the cig for group_id: {}", group->group_id_);
      group->cig.SetState(CigState::CREATED);
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_REMOVE_CIG);
      return;
    }

    if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
      group->cig.ClearCisIds();
      group->cig.GenerateCisIds(group->GetConfigurationContextType());
    }

    group->cig.SetState(CigState::NONE);

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::warn("Unexpected group state: {}, group target state: {} for group_id: {}",
                ToString(group->GetState()), ToString(group->GetTargetState()), group->group_id_);
      group->PrintDebugState();
      return;
    }

    log::verbose("Continue reconfiguration for group_id: {}", group->group_id_);
    if (!PrepareAndSendCodecConfigToTheGroup(group)) {
      log::error("No active devices for group_id: {}", group->group_id_);
      group->PrintDebugState();
    }
  }

  void ProcessHciNotifOnCigRemove(uint8_t status, LeAudioDeviceGroup* group) override {
    log_history_->AddLogHistory(kLogHciEvent, group->group_id_, RawAddress::kEmpty,
                                kLogCigRemoveOp + " STATUS=" + loghex(status));
    CigState cig_state = group->cig.GetState();
    log::debug("group_id: {}, cig state: {}, status: {:#x}", group->group_id_, ToString(cig_state),
               status);

    switch (cig_state) {
      case CigState::RECOVERING:
        ProcessHciNotifyOnCigRemoveRecovering(status, group);
        break;
      case CigState::REMOVING:
        ProcessHciNotifOnCigRemoveDefault(status, group);
        break;
      case CigState::RECONFIGURING:
        ProcessHciNotifyOnCigRemoveReconfiguring(status, group);
        break;
      case CigState::NONE:
      case CigState::CREATING:
      case CigState::CREATED:
        log::fatal("Invalid CIG state {} for group {} - controller issue", ToString(cig_state),
                   group->group_id_);
        break;
    }
  }

  void ProcessHciNotifSetupIsoDataPath(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                       uint8_t status, uint16_t conn_handle) override {
    log_history_->AddLogHistory(
            kLogHciEvent, group->group_id_, leAudioDevice->address_,
            kLogSetDataPathOp + "cis_h:" + loghex(conn_handle) + " STATUS=" + loghex(status));

    /* Find ASE and later update state for the given cis.*/
    auto ase = leAudioDevice->GetAseWaitingForDataPathByConnHandle(conn_handle);

    if (status) {
      log::error("Failed to setup data path for {}, cis handle: {:#x}, error: {:#x}",
                 leAudioDevice->address_, conn_handle, status);
      if (ase) {
        set_ase_data_path(leAudioDevice->address_, ase, DataPathState::IDLE);
      }
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_SETUP_ISO_DATA_PATH);

      return;
    }

    if (group->dsa_.active &&
        (group->dsa_.mode == DsaMode::ISO_SW || group->dsa_.mode == DsaMode::ISO_HW) &&
        leAudioDevice->GetDsaDataPathState() == DataPathState::CONFIGURING) {
      log::info("Datapath configured for headtracking");
      leAudioDevice->SetDsaDataPathState(DataPathState::CONFIGURED);
      return;
    }

    if (!ase) {
      log::error("Cannot find ase by handle {}", conn_handle);
      if (ase) {
        log::error("ASE: {}", ase->cis_conn_hdl);
      }
      group->PrintDebugState();
      return;
    }

    set_ase_data_path(leAudioDevice->address_, ase, DataPathState::CONFIGURED);

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::warn("Group {} is not targeting streaming state any more", group->group_id_);
      return;
    }

    AddCisToStreamConfiguration(group, leAudioDevice, ase);

    if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
        !group->GetFirstActiveDeviceByCisAndDataPathState(CisState::CONNECTED,
                                                          DataPathState::IDLE)) {
      /* No more transition for group. Here we are for the late join device
       * scenario */
      cancel_watchdog_if_needed(group->group_id_);
    }

    if (group->GetNotifyStreamingWhenCisesAreReadyFlag() && group->IsGroupStreamReady()) {
      group->SetNotifyStreamingWhenCisesAreReadyFlag(false);
      log::info("Ready to notify Group Streaming.");
      cancel_watchdog_if_needed(group->group_id_);
      if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
      }
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::STREAMING);
    };
  }

  void ProcessHciNotifRemoveIsoDataPath(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                        uint8_t status, uint16_t conn_hdl) override {
    log_history_->AddLogHistory(kLogHciEvent, group->group_id_, leAudioDevice->address_,
                                kLogRemoveDataPathOp + "STATUS=" + loghex(status));

    if (status != HCI_SUCCESS) {
      log::error("failed to remove ISO data path, reason: 0x{:0x} - continuing stream closing",
                 status);
      /* Just continue - disconnecting CIS removes data path as well.*/
    }

    bool do_disconnect = false;

    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(conn_hdl);
    if (ases_pair.sink && (ases_pair.sink->data_path_state == DataPathState::REMOVING)) {
      set_ase_data_path(leAudioDevice->address_, ases_pair.sink, DataPathState::IDLE);

      if (ases_pair.sink->cis_state == CisState::CONNECTED) {
        ases_pair.sink->cis_state = CisState::DISCONNECTING;
        do_disconnect = true;
      }
    }

    if (ases_pair.source && (ases_pair.source->data_path_state == DataPathState::REMOVING)) {
      set_ase_data_path(leAudioDevice->address_, ases_pair.source, DataPathState::IDLE);

      if (ases_pair.source->cis_state == CisState::CONNECTED) {
        ases_pair.source->cis_state = CisState::DISCONNECTING;
        do_disconnect = true;
      }
    } else {
      if (group->dsa_.active && leAudioDevice->GetDsaDataPathState() == DataPathState::REMOVING) {
        log::info("DSA data path removed");
        leAudioDevice->SetDsaDataPathState(DataPathState::IDLE);
        leAudioDevice->SetDsaCisHandle(LE_AUDIO_INVALID_CIS_HANDLE);
      }
    }

    if (do_disconnect) {
      group->RemoveCisFromStreamIfNeeded(leAudioDevice, conn_hdl);
      IsoManager::GetInstance()->DisconnectCis(conn_hdl, HCI_ERR_PEER_USER);

      log_history_->AddLogHistory(kLogStateMachineTag, group->group_id_, leAudioDevice->address_,
                                  kLogCisDisconnectOp + "cis_h:" + loghex(conn_hdl));
    }
  }

  void ProcessHciNotifIsoLinkQualityRead(LeAudioDeviceGroup* /*group*/,
                                         LeAudioDevice* /*leAudioDevice*/, uint16_t conn_handle,
                                         uint32_t tx_unacked_packets, uint32_t tx_flushed_packets,
                                         uint32_t tx_last_subevent_packets,
                                         uint32_t retransmitted_packets, uint32_t crc_error_packets,
                                         uint32_t rx_unreceived_packets,
                                         uint32_t duplicate_packets) {
    log::info(
            "conn_handle: 0x{:x}, tx_unacked_packets: 0x{:x}, tx_flushed_packets: "
            "0x{:x}, tx_last_subevent_packets: 0x{:x}, retransmitted_packets: 0x{:x}, "
            "crc_error_packets: 0x{:x}, rx_unreceived_packets: 0x{:x}, "
            "duplicate_packets: 0x{:x}",
            conn_handle, tx_unacked_packets, tx_flushed_packets, tx_last_subevent_packets,
            retransmitted_packets, crc_error_packets, rx_unreceived_packets, duplicate_packets);
  }

  void ReleaseCisIds(LeAudioDeviceGroup* group) {
    if (group == nullptr) {
      log::debug("Group is null.");
      return;
    }
    log::debug("Releasing CIS is for group {}", group->group_id_);

    LeAudioDevice* leAudioDevice = group->GetFirstDevice();
    while (leAudioDevice != nullptr) {
      for (auto& ase : leAudioDevice->ases_) {
        ase.cis_id = bluetooth::le_audio::kInvalidCisId;
        ase.cis_conn_hdl = bluetooth::le_audio::kInvalidCisConnHandle;
        if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
          ase.cis_state = CisState::IDLE;
          ase.data_path_state = DataPathState::IDLE;
        }
      }
      leAudioDevice = group->GetNextDevice(leAudioDevice);
    }

    group->ClearAllCises();
  }

  void SendStreamingStatusCbIfNeeded(LeAudioDeviceGroup* group) {
    /* This function should be called when some of the set members got disconnected but there are
     * still other CISes connected. When state machine is in STREAMING state, status will be sent up
     * to the user, so it can update encoder or offloader.
     */
    log::info("group_id: {}", group->group_id_);
    if (group->HaveAllCisesDisconnected()) {
      log::info("All cises disconnected;");
      return;
    }

    if ((group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) &&
        (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING)) {
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::STREAMING);
    } else {
      log::warn("group_id {} not in streaming, CISes are still there", group->group_id_);
      group->PrintDebugState();
    }
  }

  bool RemoveCigForGroup(LeAudioDeviceGroup* group,
                         CigState cig_state_during_removal = CigState::REMOVING) {
    log::debug("Group: {}, id: {} cig state: {}, removing_state: {}", std::format_ptr(group),
               group->group_id_, ToString(group->cig.GetState()),
               ToString(cig_state_during_removal));
    if (group->cig.GetState() != CigState::CREATED) {
      log::warn("Group: {}, id: {} cig state: {} cannot be removed", std::format_ptr(group),
                group->group_id_, ToString(group->cig.GetState()));
      return false;
    }

    group->cig.SetState(cig_state_during_removal);
    IsoManager::GetInstance()->RemoveCig(group->group_id_);
    log::debug("Group: {}, id: {} cig state: {}", std::format_ptr(group), group->group_id_,
               ToString(group->cig.GetState()));
    log_history_->AddLogHistory(kLogStateMachineTag, group->group_id_, RawAddress::kEmpty,
                                kLogCigRemoveOp);
    return true;
  }

  void ProcessHciNotifAclDisconnected(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    leAudioDevice->FreeLinkQualityReports();
    if (!group) {
      log::error("group is null for device: {} group_id: {}", leAudioDevice->address_,
                 leAudioDevice->group_id_);
      /* mark ASEs as not used. */
      leAudioDevice->DeactivateAllAses();
      return;
    }

    /* It is possible that ACL disconnection came before CIS disconnect event */
    for (auto& ase : leAudioDevice->ases_) {
      if (ase.data_path_state == DataPathState::CONFIGURED ||
          ase.data_path_state == DataPathState::CONFIGURING) {
        RemoveDataPathByCisHandle(leAudioDevice, ase.cis_conn_hdl);
      }
      group->RemoveCisFromStreamIfNeeded(leAudioDevice, ase.cis_conn_hdl);
    }

    /* mark ASEs as not used. */
    leAudioDevice->DeactivateAllAses();

    /* Update the current group audio context availability which could change
     * due to disconnected group member.
     */
    group->ReloadAudioLocations();
    group->ReloadAudioDirections();
    group->InvalidateCachedConfigurations();
    group->InvalidateGroupStrategy();

    /* If group is in Idle and not transitioning, update the current group
     * audio context availability which could change due to disconnected group
     * member.
     */
    if ((group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) && !group->IsInTransition()) {
      log::info("group: {} is in IDLE", group->group_id_);

      /* When OnLeAudioDeviceSetStateTimeout happens, group will transition
       * to IDLE, and after that an ACL disconnect will be triggered. We need
       * to check if CIG is created and if it is, remove it so it can be created
       * again after reconnect. Otherwise we will get Command Disallowed on CIG
       * Create when starting stream.
       */
      if (group->cig.GetState() == CigState::CREATED) {
        log::info("CIG is in CREATED state so removing CIG for Group {}", group->group_id_);
        RemoveCigForGroup(group);
      }
      return;
    }

    log::debug("device: {}, group connected: {}, all active ase disconnected:: {}",
               leAudioDevice->address_, group->IsAnyDeviceConnected(),
               group->HaveAllCisesDisconnected());

    if (group->IsAnyDeviceConnected()) {
      /*
       * ACL of one of the device has been dropped. If number of CISes has
       * changed notify upper layer so the CodecManager can be updated with CIS
       * information.
       */
      if (!group->HaveAllCisesDisconnected()) {
        /* some CISes are connected */
        SendStreamingStatusCbIfNeeded(group);
        return;
      }

      if (!group->IsInTransitionTo(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE)) {
        /* do nothing if not transitioning to IDLE */
        return;
      }
    }

    /* Group is not connected and all the CISes are down.
     * Clean states and destroy HCI group
     */
    log::debug("Clearing inactive group");
    ClearGroup(group, true);
  }

  void cancel_watchdog_if_needed(int group_id) {
    if (alarm_is_scheduled(watchdog_)) {
      log_history_->AddLogHistory(kLogStateMachineTag, group_id, RawAddress::kEmpty,
                                  "WATCHDOG STOPPED");
      alarm_cancel(watchdog_);
    }
  }

  void applyDsaDataPath(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                        uint16_t conn_hdl) {
    if (!group->dsa_.active) {
      log::info("DSA mode not used");
      return;
    }

    DsaModes dsa_modes = leAudioDevice->GetDsaModes();
    if (dsa_modes.empty()) {
      log::warn("DSA mode not supported by this LE Audio device: {}", leAudioDevice->address_);
      group->dsa_.active = false;
      return;
    }

    if (std::find(dsa_modes.begin(), dsa_modes.end(), DsaMode::ISO_SW) == dsa_modes.end() &&
        std::find(dsa_modes.begin(), dsa_modes.end(), DsaMode::ISO_HW) == dsa_modes.end()) {
      log::warn("DSA mode not supported by this LE Audio device: {}", leAudioDevice->address_);
      group->dsa_.active = false;
      return;
    }

    uint8_t data_path_id = bluetooth::hci::iso_manager::kIsoDataPathHci;
    bluetooth::le_audio::types::LeAudioCodecId codec = {
            .coding_format = bluetooth::hci::kIsoCodingFormatTransparent,
            .vendor_company_id = 0x0000,
            .vendor_codec_id = 0x0000};
    log::info("DSA mode used: {}", static_cast<int>(group->dsa_.mode));

    auto config = group->GetActiveConfiguration();
    log::assert_that(config.get() != nullptr, "No valid active group configuration!");

    if (config->hasDsaBackChannel()) {
      auto const& cfg = config->confs.source.at(0);
      data_path_id = cfg.data_path_configuration.dataPathId;
      codec = cfg.data_path_configuration.isoDataPathConfig.codecId;

      log::debug("Setting DSA data path parameters: data_path_id: {}, codec: {}", data_path_id,
                 common::ToString(codec));

    } else {
      log::warn("Fallback to static DSA configuration for group: {}", group->group_id_);
      switch (group->dsa_.mode) {
        case DsaMode::ISO_HW:
          data_path_id = bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
          break;
        case DsaMode::ISO_SW:
          data_path_id = bluetooth::hci::iso_manager::kIsoDataPathHci;
          codec = bluetooth::le_audio::types::kLeAudioCodecHeadtracking;
          break;
        default:
          log::warn("Unexpected DsaMode: {}", static_cast<int>(group->dsa_.mode));
          group->dsa_.active = false;
          return;
      }
    }

    leAudioDevice->SetDsaDataPathState(DataPathState::CONFIGURING);
    leAudioDevice->SetDsaCisHandle(conn_hdl);

    log::verbose("DSA mode supported on this LE Audio device: {}, apply data path: {}",
                 leAudioDevice->address_, data_path_id);

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, group->group_id_, RawAddress::kEmpty,
            kLogSetDataPathOp + "cis_h:" + loghex(conn_hdl),
            "direction: " + loghex(bluetooth::hci::iso_manager::kIsoDataPathDirectionOut));

    bluetooth::hci::iso_manager::iso_data_path_params param = {
            .data_path_dir = bluetooth::hci::iso_manager::kIsoDataPathDirectionOut,
            .data_path_id = data_path_id,
            .codec_id_format = codec.coding_format,
            .codec_id_company = codec.vendor_company_id,
            .codec_id_vendor = codec.vendor_codec_id,
            .controller_delay = 0x00000000,
            .codec_conf = std::vector<uint8_t>(),
    };
    IsoManager::GetInstance()->SetupIsoDataPath(conn_hdl, std::move(param));
  }

  void ProcessHciNotifCisEstablished(
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
          const bluetooth::hci::iso_manager::cis_establish_cmpl_evt* event) override {
    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(event->cis_conn_hdl);

    log_history_->AddLogHistory(kLogHciEvent, group->group_id_, leAudioDevice->address_,
                                kLogCisEstablishedOp + "cis_h:" + loghex(event->cis_conn_hdl) +
                                        " STATUS=" + loghex(event->status));

    if (event->status != HCI_SUCCESS) {
      log::warn("{}: failed to create CIS 0x{:04x}, status: {} (0x{:02x})", leAudioDevice->address_,
                event->cis_conn_hdl, ErrorCodeText((ErrorCode)event->status), event->status);

      if (event->status == HCI_ERR_CANCELLED_BY_LOCAL_HOST) {
        log::info("{} CIS creation aborted by us, waiting for disconnection complete",
                  leAudioDevice->address_);
        return;
      }

      if (ases_pair.sink) {
        ases_pair.sink->cis_state = CisState::ASSIGNED;
      }
      if (ases_pair.source) {
        ases_pair.source->cis_state = CisState::ASSIGNED;
      }

      if (event->status == HCI_ERR_CONN_FAILED_ESTABLISHMENT &&
          ((leAudioDevice->cis_failed_to_be_established_retry_cnt_++) < kNumberOfCisRetries) &&
          (CisCreateForDevice(group, leAudioDevice))) {
        log::info("Retrying ({}) to create CIS for {}",
                  leAudioDevice->cis_failed_to_be_established_retry_cnt_, leAudioDevice->address_);
        return;
      }

      if (event->status == HCI_ERR_UNSUPPORTED_REM_FEATURE &&
          group->asymmetric_phy_for_unidirectional_cis_supported == true &&
          group->GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSource) == 0) {
        log::info(
                "Remote device may not support asymmetric phy for CIS, retry "
                "symmetric setting again");
        group->asymmetric_phy_for_unidirectional_cis_supported = false;
      }

      log::error("CIS creation failed {} times, stopping the stream",
                 leAudioDevice->cis_failed_to_be_established_retry_cnt_);
      leAudioDevice->cis_failed_to_be_established_retry_cnt_ = 0;

      /* CIS establishment failed. Remove CIG if no other CIS is already created
       * or pending. If CIS is established, this will be handled in disconnected
       * complete event
       */
      if (group->HaveAllCisesDisconnected()) {
        RemoveCigForGroup(group);
      }

      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIS);
      return;
    }

    if (leAudioDevice->cis_failed_to_be_established_retry_cnt_ > 0) {
      /* Reset retry counter */
      leAudioDevice->cis_failed_to_be_established_retry_cnt_ = 0;
    }

    bool is_cis_connecting =
            (ases_pair.sink && ases_pair.sink->cis_state == CisState::CONNECTING) ||
            (ases_pair.source && ases_pair.source->cis_state == CisState::CONNECTING);

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
        !is_cis_connecting) {
      bool is_cis_disconnecting =
              (ases_pair.sink && ases_pair.sink->cis_state == CisState::DISCONNECTING) ||
              (ases_pair.source && ases_pair.source->cis_state == CisState::DISCONNECTING);
      if (is_cis_disconnecting) {
        /* We are in the process of CIS disconnection while the Established event came.
         * The Disconnection Complete shall come right after.
         */
        log::info("{} got CIS is in disconnecting state", leAudioDevice->address_);
      } else {
        log::error("Unintended CIS establishment event came for group id: {}", group->group_id_);
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_CIS_ESTABLISHED_EVENT);
      }

      return;
    }

    if (ases_pair.sink) {
      ases_pair.sink->cis_state = CisState::CONNECTED;
    }
    if (ases_pair.source) {
      ases_pair.source->cis_state = CisState::CONNECTED;
    }

    if (ases_pair.sink && (ases_pair.sink->data_path_state == DataPathState::IDLE)) {
      PrepareDataPath(group->group_id_, ases_pair.sink);
      PrepareIsoDataPath(group->group_id_, leAudioDevice, ases_pair.sink);
    }

    if (ases_pair.source && (ases_pair.source->data_path_state == DataPathState::IDLE)) {
      PrepareDataPath(group->group_id_, ases_pair.source);
      PrepareIsoDataPath(group->group_id_, leAudioDevice, ases_pair.source);
    } else {
      PrepareDataPath(group->group_id_, ases_pair.sink);
      applyDsaDataPath(group, leAudioDevice, event->cis_conn_hdl);
    }

    if (osi_property_get_bool("persist.bluetooth.iso_link_quality_report", false)) {
      leAudioDevice->StartLinkQualityReports(event->cis_conn_hdl);
    }

    if (!leAudioDevice->HaveAllActiveAsesCisEst()) {
      /* More cis established events has to come */
      return;
    }

    if (!leAudioDevice->IsReadyToCreateStream()) {
      /* Device still remains in ready to create stream state. It means that
       * more enabling status notifications has to come. This may only happen
       * for reconnection scenario for bi-directional CIS.
       */
      return;
    }

    /* All CISes created. Send start ready for source ASE before we can go
     * to streaming state.
     */
    struct ase* ase = leAudioDevice->GetFirstActiveAse();
    log::assert_that(ase != nullptr,
                     "shouldn't be called without an active ASE, device {}, "
                     "group id: {}, cis handle 0x{:04x}",
                     leAudioDevice->address_, event->cig_id, event->cis_conn_hdl);

    PrepareAndSendReceiverStartReady(leAudioDevice, ase);
  }

  static void WriteToControlPoint(LeAudioDevice* leAudioDevice, std::vector<uint8_t> value) {
    tGATT_WRITE_TYPE write_type = GATT_WRITE_NO_RSP;

    if (value.size() > (leAudioDevice->mtu_ - 3)) {
      log::warn("{}, using long write procedure ({} > {})", leAudioDevice->address_, value.size(),
                leAudioDevice->mtu_ - 3);

      /* Note, that this type is actually LONG WRITE.
       * Meaning all the Prepare Writes plus Execute is handled in the stack
       */
      write_type = GATT_WRITE;
    }

    BtaGattQueue::WriteCharacteristic(leAudioDevice->conn_id_, leAudioDevice->ctp_hdls_.val_hdl,
                                      value, write_type, NULL, NULL);
  }

  static void RemoveDataPathByCisHandle(LeAudioDevice* leAudioDevice, uint16_t cis_conn_hdl) {
    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(cis_conn_hdl);
    uint8_t value = 0;

    if (ases_pair.sink && (ases_pair.sink->data_path_state == DataPathState::CONFIGURED ||
                           ases_pair.sink->data_path_state == DataPathState::CONFIGURING)) {
      value |= bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionInput;
      set_ase_data_path(leAudioDevice->address_, ases_pair.sink, DataPathState::REMOVING);
    }

    if (ases_pair.source && (ases_pair.source->data_path_state == DataPathState::CONFIGURED ||
                             ases_pair.source->data_path_state == DataPathState::CONFIGURING)) {
      value |= bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput;
      set_ase_data_path(leAudioDevice->address_, ases_pair.source, DataPathState::REMOVING);
    } else {
      if (leAudioDevice->GetDsaDataPathState() == DataPathState::CONFIGURED ||
          leAudioDevice->GetDsaDataPathState() == DataPathState::CONFIGURING) {
        value |= bluetooth::hci::iso_manager::kRemoveIsoDataPathDirectionOutput;
        leAudioDevice->SetDsaDataPathState(DataPathState::REMOVING);
      }
    }

    if (value == 0) {
      log::info("Data path was not set. Nothing to do here.");
      return;
    }

    IsoManager::GetInstance()->RemoveIsoDataPath(cis_conn_hdl, value);

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, leAudioDevice->group_id_, leAudioDevice->address_,
            kLogRemoveDataPathOp + " cis_h:" + loghex(cis_conn_hdl));
  }

  void PrepareAndSendReceiverStopReady(LeAudioDevice* leAudioDevice, struct ase* ase) {
    log::info("{}, ase_id: {}({:#x} state: {})", leAudioDevice->address_, ase->id, ase->direction,
              ToString(ase->state));

    if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
      log::warn("{}, ase_id: {}({:#x} not in valid state: {}", leAudioDevice->address_, ase->id,
                ase->direction, ToString(ase->state));
      return;
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeReceiverStopReady;

    SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    std::vector<uint8_t> ids = {ase->id};
    std::vector<uint8_t> value;

    bluetooth::le_audio::client_parser::ascs::PrepareAseCtpAudioReceiverStopReady(ids, value);
    WriteToControlPoint(leAudioDevice, value);

    log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                leAudioDevice->address_,
                                kLogAseStopReadyOp + "ASE_ID " + std::to_string(ase->id));
  }

  void ProcessHciNotifCisDisconnected(
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
          const bluetooth::hci::iso_manager::cis_disconnected_evt* event) override {
    /* Reset the disconnected CIS states */

    leAudioDevice->FreeLinkQualityReports();

    auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(event->cis_conn_hdl);

    log_history_->AddLogHistory(kLogHciEvent, group->group_id_, leAudioDevice->address_,
                                kLogCisDisconnectedOp + "cis_h:" + loghex(event->cis_conn_hdl) +
                                        " REASON=" + loghex(event->reason));

    if (ases_pair.sink) {
      ases_pair.sink->cis_state = CisState::ASSIGNED;
    }
    if (ases_pair.source) {
      ases_pair.source->cis_state = CisState::ASSIGNED;
    }

    RemoveDataPathByCisHandle(leAudioDevice, event->cis_conn_hdl);

    /* If this is peer disconnecting CIS, make sure to clear data path */
    if (event->reason != HCI_ERR_CONN_CAUSE_LOCAL_HOST) {
      // Make sure we won't stay in STREAMING state
      if (ases_pair.sink && ases_pair.sink->state == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        SetAseState(leAudioDevice, ases_pair.sink, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
      }
      if (ases_pair.source &&
          ases_pair.source->state == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        SetAseState(leAudioDevice, ases_pair.source,
                    AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
      }
    }

    group->RemoveCisFromStreamIfNeeded(leAudioDevice, event->cis_conn_hdl);

    auto target_state = group->GetTargetState();
    log::info(" group id {}, state {}, target state {}", group->group_id_,
              bluetooth::common::ToString(group->GetState()),
              bluetooth::common::ToString(target_state));

    switch (target_state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        /* Something wrong happen when streaming or when creating stream.
         * If there is other device connected and streaming, just leave it as it
         * is, otherwise stop the stream.
         */
        if (!group->HaveAllCisesDisconnected()) {
          /* There is ASE streaming for some device. Continue streaming. */
          SendStreamingStatusCbIfNeeded(group);
          log::warn("Group member disconnected during streaming. Cis handle 0x{:04x}",
                    event->cis_conn_hdl);
          return;
        }

        /* CISes are disconnected, but it could be a case here, that there is
         * another set member trying to get STREAMING state. Can happen when
         * while streaming user switch buds. In such a case, lets try to allow
         * that device to continue
         */

        LeAudioDevice* attaching_device = getDeviceTryingToAttachTheStream(group);
        if (attaching_device != nullptr) {
          /* There is a device willitng to stream. Let's wait for it to start
           * streaming */
          auto active_ase = attaching_device->GetFirstActiveAse();
          group->SetState(active_ase->state);

          /* this is just to start timer */
          group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
          log::info(
                  "{} is still attaching to stream while other members got "
                  "disconnected from the group_id: {}",
                  attaching_device->address_, group->group_id_);
          return;
        }

        log::info("Lost all members from the group {}", group->group_id_);
        if (!com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
          group->cig.ClearCisIds();
        }
        RemoveCigForGroup(group);

        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        /* If there is no more ase to stream. Notify it is in IDLE. */
        state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::IDLE);
        return;
      }

      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        /* Intentional group disconnect has finished, but the last CIS in the
         * event came after the ASE notification.
         * If group is already suspended and all CIS are disconnected, we can
         * report SUSPENDED state.
         */
        if ((group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) &&
            group->HaveAllCisesDisconnected()) {
          /* No more transition for group */
          cancel_watchdog_if_needed(group->group_id_);

          state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::SUSPENDED);
          return;
        }
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED: {
        /* Those two are used when closing the stream and CIS disconnection is
         * expected */
        if (!group->HaveAllCisesDisconnected()) {
          log::debug("Still waiting for all CISes being disconnected for group:{}",
                     group->group_id_);
          return;
        }

        auto current_group_state = group->GetState();
        log::info("group {} current state: {}, target state: {}", group->group_id_,
                  bluetooth::common::ToString(current_group_state),
                  bluetooth::common::ToString(target_state));
        /* It might happen that controller notified about CIS disconnection
         * later, after ASE state already changed.
         * In such an event, there is need to notify upper layer about state
         * from here.
         */
        if (current_group_state == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          cancel_watchdog_if_needed(group->group_id_);
          log::info("Cises disconnected for group {}, we are good in Idle state.",
                    group->group_id_);
          ReleaseCisIds(group);
          state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::IDLE);
        } else if (current_group_state == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED) {
          cancel_watchdog_if_needed(group->group_id_);
          auto reconfig = group->IsPendingConfiguration();
          log::info(
                  "Cises disconnected for group: {}, we are good in Configured "
                  "state, reconfig={}.",
                  group->group_id_, reconfig);

          /* This is Autonomous change if both, target and current state
           * is CODEC_CONFIGURED
           */
          if (target_state == current_group_state) {
            state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                     GroupStreamStatus::CONFIGURED_AUTONOMOUS);
          }
        }
        RemoveCigForGroup(group);
      } break;
      default:
        break;
    }

    /* We should send Receiver Stop Ready when acting as a source */
    if (ases_pair.source && ases_pair.source->state == AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
      PrepareAndSendReceiverStopReady(leAudioDevice, ases_pair.source);
    }

    /* Tear down CIS's data paths within the group */
    struct ase* ase = leAudioDevice->GetFirstActiveAseByCisAndDataPathState(
            CisState::CONNECTED, DataPathState::CONFIGURED);
    if (!ase) {
      leAudioDevice = group->GetNextActiveDevice(leAudioDevice);
      /* No more ASEs to disconnect their CISes */
      if (!leAudioDevice) {
        return;
      }

      ase = leAudioDevice->GetFirstActiveAse();
    }

    log::assert_that(ase, "shouldn't be called without an active ASE");
    if (ase->data_path_state == DataPathState::CONFIGURED) {
      RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
    }
  }

private:
  static constexpr uint64_t kStateTransitionTimeoutMs = 3500;
  static constexpr char kStateTransitionTimeoutMsProp[] =
          "persist.bluetooth.leaudio.device.set.state.timeoutms";
  Callbacks* state_machine_callbacks_;
  alarm_t* watchdog_;
  LeAudioLogHistory* log_history_;
  bluetooth::hci::iso_manager::IsoClientHandle iso_client_handle_ =
          bluetooth::hci::iso_manager::kInvalidIsoClientHandle;

  /* This callback is called on timeout during transition to target state */
  void OnStateTransitionTimeout(int group_id) {
    log_history_->AddLogHistory(kLogStateMachineTag, group_id, RawAddress::kEmpty,
                                "WATCHDOG FIRED");
    state_machine_callbacks_->OnStateTransitionTimeout(group_id);
  }

  void SetTargetState(LeAudioDeviceGroup* group, AseState state) {
    auto current_state = ToString(group->GetTargetState());
    auto new_state = ToString(state);

    log::debug("Watchdog watch started for group={} transition from {} to {}", group->group_id_,
               current_state, new_state);

    group->SetTargetState(state);

    /* Group should tie in time to get requested status */
    uint64_t timeoutMs = kStateTransitionTimeoutMs;
    timeoutMs = osi_property_get_int32(kStateTransitionTimeoutMsProp, timeoutMs);

    cancel_watchdog_if_needed(group->group_id_);

    alarm_set_on_mloop(
            watchdog_, timeoutMs,
            [](void* data) {
              if (instance) {
                instance->OnStateTransitionTimeout(PTR_TO_INT(data));
              }
            },
            INT_TO_PTR(group->group_id_));

    log_history_->AddLogHistory(kLogStateMachineTag, group->group_id_, RawAddress::kEmpty,
                                "WATCHDOG STARTED");
  }

  void AddCisToStreamConfiguration(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                   const struct ase* ase) {
    group->stream_conf.codec_id = ase->codec_config.id;

    auto cis_conn_hdl = ase->cis_conn_hdl;
    auto& params = group->stream_conf.stream_params.get(ase->direction);
    log::info("Adding cis handle 0x{:04x} ({}) to stream list", cis_conn_hdl,
              ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "sink"
                                                                                  : "source");

    auto iter = std::find_if(
            params.stream_config.stream_map.begin(), params.stream_config.stream_map.end(),
            [cis_conn_hdl](auto& info) { return cis_conn_hdl == info.stream_handle; });
    log::assert_that(iter == params.stream_config.stream_map.end(),
                     "Stream is already there 0x{:04x}", cis_conn_hdl);

    params.num_of_devices++;
    params.num_of_channels += ase->codec_config.channel_count_per_iso_stream;

    auto ase_audio_channel_allocation = ase->codec_config.GetAudioChannelAllocation();
    params.audio_channel_allocation |= ase_audio_channel_allocation;

    params.stream_config.bits_per_sample = ase->codec_config.GetBitsPerSample();

    auto address_with_type = leAudioDevice->GetAddressWithType();
    auto info = ::bluetooth::le_audio::stream_map_info(ase->cis_conn_hdl,
                                                       ase_audio_channel_allocation, true);
    info.codec_config = ase->codec_config;
    info.target_latency = ase->target_latency;
    info.target_phy = ase->qos_config.phy;
    info.metadata = ase->metadata;
    info.address = address_with_type.bda;
    info.address_type = address_with_type.type;
    params.stream_config.stream_map.push_back(info);

    // Note that for the vendor codec some of the parameters will be missing
    auto core_config = ase->codec_config.params.GetAsCoreCodecConfig();
    if (params.stream_config.sampling_frequency_hz == 0) {
      params.stream_config.sampling_frequency_hz = core_config.GetSamplingFrequencyHz();
    } else {
      log::assert_that(
              params.stream_config.sampling_frequency_hz == core_config.GetSamplingFrequencyHz(),
              "sample freq mismatch: {}!={}", params.stream_config.sampling_frequency_hz,
              core_config.GetSamplingFrequencyHz());
    }

    if (params.stream_config.octets_per_codec_frame == 0) {
      params.stream_config.octets_per_codec_frame = *core_config.octets_per_codec_frame;
    } else {
      log::assert_that(
              params.stream_config.octets_per_codec_frame == *core_config.octets_per_codec_frame,
              "octets per frame mismatch: {}!={}", params.stream_config.octets_per_codec_frame,
              *core_config.octets_per_codec_frame);
    }

    if (params.stream_config.codec_frames_blocks_per_sdu == 0) {
      params.stream_config.codec_frames_blocks_per_sdu = *core_config.codec_frames_blocks_per_sdu;
    } else {
      log::assert_that(params.stream_config.codec_frames_blocks_per_sdu ==
                               *core_config.codec_frames_blocks_per_sdu,
                       "codec_frames_blocks_per_sdu: {}!={}",
                       params.stream_config.codec_frames_blocks_per_sdu,
                       *core_config.codec_frames_blocks_per_sdu);
    }

    if (params.stream_config.frame_duration_us == 0) {
      params.stream_config.frame_duration_us = core_config.GetFrameDurationUs();
    } else {
      log::assert_that(params.stream_config.frame_duration_us == core_config.GetFrameDurationUs(),
                       "frame_duration_us: {}!={}", params.stream_config.frame_duration_us,
                       core_config.GetFrameDurationUs());
    }

    params.stream_config.peer_delay_ms = group->GetRemoteDelay(ase->direction);

    log::info(
            "Added {} Stream Configuration. CIS Connection Handle: {}, Audio "
            "Channel Allocation: {}, Number Of Devices: {}, Number Of Channels: {}",
            ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "Sink" : "Source",
            cis_conn_hdl, ase_audio_channel_allocation, params.num_of_devices,
            params.num_of_channels);

    /* Update CodecManager stream configuration */
    state_machine_callbacks_->OnUpdatedCisConfiguration(group->group_id_, ase->direction);
  }

  static bool isIntervalAndLatencyProperlySet(uint32_t sdu_interval_us, uint16_t max_latency_ms) {
    log::verbose("sdu_interval_us: {}, max_latency_ms: {}", sdu_interval_us, max_latency_ms);

    if (sdu_interval_us == 0) {
      return max_latency_ms == bluetooth::le_audio::types::kMaxTransportLatencyMin;
    }
    return (1000 * max_latency_ms) >= sdu_interval_us;
  }

  void ApplyDsaParams(LeAudioDeviceGroup* group,
                      bluetooth::hci::iso_manager::cig_create_params& param) {
    /* Ignore bidirectional streaming */
    if (param.sdu_itv_p_to_c != 0) {
      log::debug("Bidirection streaming, ignore DSA mode {}", group->dsa_.mode);
      return;
    }
    log::info("DSA mode selected: {}", group->dsa_.mode);
    group->dsa_.active = false;

    switch (group->dsa_.mode) {
      case DsaMode::ISO_HW:
      case DsaMode::ISO_SW: {
        auto& cis_cfgs = param.cis_cfgs;
        auto it = cis_cfgs.begin();

        for (auto dsa_modes : group->GetAllowedDsaModesList()) {
          if (it == cis_cfgs.end()) {
            log::error("DSA mode {} selected but no CIS configuration found", group->dsa_.mode);
            return;
          }

          // Skip if the device does not support the selected DSA mode
          if (dsa_modes.empty() ||
              std::find(dsa_modes.begin(), dsa_modes.end(), group->dsa_.mode) == dsa_modes.end()) {
            it++;
            continue;
          }

          log::info("Device found with support for selected DsaMode");
          group->dsa_.active = true;
          auto config = group->GetActiveConfiguration();
          log::assert_that(config.get() != nullptr, "No valid active group configuration!");

          if (config->hasDsaBackChannel()) {
            auto const& cfg = config->confs.source.at(0);

            param.sdu_itv_p_to_c = cfg.qos.sduIntervalUs;
            param.max_trans_lat_p_to_c = cfg.qos.max_transport_latency;
            it->max_sdu_size_p_to_c = cfg.qos.maxSdu;
            it->rtn_p_to_c = cfg.qos.retransmission_number;

            log::debug(
                    "Applying DSA Cig parameters: sdu_itv_p_to_c:{}, max_trans_lat_p_to_c: {}, "
                    "max_sdu_size_p_to_c: {}, rtn_p_to_c: {}",
                    param.sdu_itv_p_to_c, param.max_trans_lat_p_to_c, it->max_sdu_size_p_to_c,
                    it->rtn_p_to_c);
          } else {
            log::warn("Fallback to static DSA configuration for group: {}", group->group_id_);
            param.sdu_itv_p_to_c = bluetooth::le_audio::types::kLeAudioHeadtrackerSduItv;
            param.max_trans_lat_p_to_c = bluetooth::le_audio::types::kLeAudioHeadtrackerMaxTransLat;
            it->max_sdu_size_p_to_c = bluetooth::le_audio::types::kLeAudioHeadtrackerMaxSduSize;

            // Early draft of DSA 2.0 spec mentioned allocating 15 bytes for headtracker data
            if (!group->DsaReducedSduSizeSupported()) {
              log::verbose("Device does not support reduced headtracker SDU");
              it->max_sdu_size_p_to_c = 15;
            }

            it->rtn_p_to_c = bluetooth::le_audio::types::kLeAudioHeadtrackerRtn;
          }
          it++;
        }
      } break;

      case DsaMode::ACL:
        /* Todo: Prioritize the ACL */
        break;

      case DsaMode::DISABLED:
      default:
        /* No need to change ISO parameters */
        break;
    }
  }

  bool CigCreate(LeAudioDeviceGroup* group) {
    uint32_t sdu_interval_c_to_p, sdu_interval_p_to_c;
    uint16_t max_trans_lat_c_to_p, max_trans_lat_p_to_c;
    uint8_t packing, framing, sca;
    std::vector<EXT_CIS_CFG> cis_cfgs;

    log::debug("Group: {}, id: {} cig state: {}", std::format_ptr(group), group->group_id_,
               ToString(group->cig.GetState()));

    if (group->cig.GetState() != CigState::NONE) {
      log::warn("Group {}, id: {} has invalid cig state: {}", std::format_ptr(group),
                group->group_id_, ToString(group->cig.GetState()));
      return false;
    }

    sdu_interval_c_to_p = group->GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSink);
    sdu_interval_p_to_c =
            group->GetSduInterval(bluetooth::le_audio::types::kLeAudioDirectionSource);
    sca = group->GetSCA();
    packing = group->GetPacking();
    framing = group->GetFraming();
    max_trans_lat_c_to_p = group->GetMaxTransportLatencyCToP();
    max_trans_lat_p_to_c = group->GetMaxTransportLatencyPToC();

    uint16_t max_sdu_size_c_to_p = 0;
    uint16_t max_sdu_size_p_to_c = 0;
    uint8_t phy_c_to_p = group->GetPhyBitmask(bluetooth::le_audio::types::kLeAudioDirectionSink);
    uint8_t phy_p_to_c = group->GetPhyBitmask(bluetooth::le_audio::types::kLeAudioDirectionSource);

    if (!isIntervalAndLatencyProperlySet(sdu_interval_c_to_p, max_trans_lat_c_to_p) ||
        !isIntervalAndLatencyProperlySet(sdu_interval_p_to_c, max_trans_lat_p_to_c)) {
      log::error("Latency and interval not properly set");
      group->PrintDebugState();
      return false;
    }

    // Use 1M Phy for the ACK packet from remote device to phone for better
    // sensitivity
    if (group->asymmetric_phy_for_unidirectional_cis_supported && sdu_interval_p_to_c == 0 &&
        (phy_p_to_c & bluetooth::hci::kIsoCigPhy1M) != 0) {
      log::info("Use asymmetric PHY for unidirectional CIS");
      phy_p_to_c = bluetooth::hci::kIsoCigPhy1M;
    }

    uint8_t rtn_c_to_p = 0;
    uint8_t rtn_p_to_c = 0;

    /* Currently assumed Sink/Source configuration is same across cis types.
     * If a cis in cises_ is currently associated with active device/ASE(s),
     * use the Sink/Source configuration for the same.
     * If a cis in cises_ is not currently associated with active device/ASE(s),
     * use the Sink/Source configuration for the cis in cises_
     * associated with a active device/ASE(s). When the same cis is associated
     * later, with active device/ASE(s), check if current configuration is
     * supported or not, if not, reconfigure CIG.
     */
    auto& cises = group->cig.GetCises();
    for (const struct bluetooth::le_audio::types::cis& cis : cises) {
      uint16_t max_sdu_size_c_to_p_temp =
              group->GetMaxSduSize(bluetooth::le_audio::types::kLeAudioDirectionSink, cis.id);
      uint16_t max_sdu_size_p_to_c_temp =
              group->GetMaxSduSize(bluetooth::le_audio::types::kLeAudioDirectionSource, cis.id);
      uint8_t rtn_c_to_p_temp =
              group->GetRtn(bluetooth::le_audio::types::kLeAudioDirectionSink, cis.id);
      uint8_t rtn_p_to_c_temp =
              group->GetRtn(bluetooth::le_audio::types::kLeAudioDirectionSource, cis.id);

      max_sdu_size_c_to_p =
              max_sdu_size_c_to_p_temp ? max_sdu_size_c_to_p_temp : max_sdu_size_c_to_p;
      max_sdu_size_p_to_c =
              max_sdu_size_p_to_c_temp ? max_sdu_size_p_to_c_temp : max_sdu_size_p_to_c;
      rtn_c_to_p = rtn_c_to_p_temp ? rtn_c_to_p_temp : rtn_c_to_p;
      rtn_p_to_c = rtn_p_to_c_temp ? rtn_p_to_c_temp : rtn_p_to_c;
    }

    for (const struct bluetooth::le_audio::types::cis& cis : cises) {
      EXT_CIS_CFG cis_cfg = {};

      cis_cfg.cis_id = cis.id;
      cis_cfg.phy_c_to_p = phy_c_to_p;
      cis_cfg.phy_p_to_c = phy_p_to_c;
      if (cis.type == bluetooth::le_audio::types::CisType::CIS_TYPE_BIDIRECTIONAL) {
        cis_cfg.max_sdu_size_c_to_p = max_sdu_size_c_to_p;
        cis_cfg.rtn_c_to_p = rtn_c_to_p;
        cis_cfg.max_sdu_size_p_to_c = max_sdu_size_p_to_c;
        cis_cfg.rtn_p_to_c = rtn_p_to_c;
        cis_cfgs.push_back(cis_cfg);
      } else if (cis.type == bluetooth::le_audio::types::CisType::CIS_TYPE_UNIDIRECTIONAL_SINK) {
        cis_cfg.max_sdu_size_c_to_p = max_sdu_size_c_to_p;
        cis_cfg.rtn_c_to_p = rtn_c_to_p;
        cis_cfg.max_sdu_size_p_to_c = 0;
        cis_cfg.rtn_p_to_c = 0;
        cis_cfgs.push_back(cis_cfg);
      } else {
        cis_cfg.max_sdu_size_c_to_p = 0;
        cis_cfg.rtn_c_to_p = 0;
        cis_cfg.max_sdu_size_p_to_c = max_sdu_size_p_to_c;
        cis_cfg.rtn_p_to_c = rtn_p_to_c;
        cis_cfgs.push_back(cis_cfg);
      }
    }

    /* Make sure, the parameters makes sense and CIG which is about to be created is useful in any
     * sense. e.g. Any direction is enabled, there is no logical mistakes in the parameters.
     */
    bool no_direction_enabled_due_to_sdu_interval =
            (sdu_interval_c_to_p == 0 && sdu_interval_p_to_c == 0);
    bool no_direction_enabled_due_max_latencies_setting =
            (max_trans_lat_c_to_p == bluetooth::le_audio::types::kMaxTransportLatencyMin &&
             max_trans_lat_p_to_c == bluetooth::le_audio::types::kMaxTransportLatencyMin);
    bool no_direction_enabled_due_max_sdu_sizes_zero =
            (max_sdu_size_c_to_p == 0 && max_sdu_size_p_to_c == 0);

    /* The mismatch where one of the sdu size or sdu interval is 0 while the other parameter is 0 is
     * a non-sense configuration. We should catch that and avoid creating such a CIG.
     */
    bool is_sdu_config_mismatch_fix_enabled =
            com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig();
    bool is_c_to_p_sdu_config_mismatched =
            ((max_sdu_size_c_to_p == 0) != (sdu_interval_c_to_p == 0));
    bool is_p_to_c_sdu_config_mismatched =
            ((max_sdu_size_p_to_c == 0) != (sdu_interval_p_to_c == 0));

    if (no_direction_enabled_due_to_sdu_interval ||
        no_direction_enabled_due_max_latencies_setting ||
        no_direction_enabled_due_max_sdu_sizes_zero ||
        (is_sdu_config_mismatch_fix_enabled &&
         (is_c_to_p_sdu_config_mismatched || is_p_to_c_sdu_config_mismatched))) {
      log::error("Trying to create invalid group");
      group->PrintDebugState();
      return false;
    }

    bluetooth::hci::iso_manager::cig_create_params param = {
            .sdu_itv_c_to_p = sdu_interval_c_to_p,
            .sdu_itv_p_to_c = sdu_interval_p_to_c,
            .sca = sca,
            .packing = packing,
            .framing = framing,
            .max_trans_lat_c_to_p = max_trans_lat_c_to_p,
            .max_trans_lat_p_to_c = max_trans_lat_p_to_c,
            .cis_cfgs = std::move(cis_cfgs),
    };

    ApplyDsaParams(group, param);

    log_history_->AddLogHistory(kLogStateMachineTag, group->group_id_, RawAddress::kEmpty,
                                kLogCigCreateOp + "#CIS: " + std::to_string(param.cis_cfgs.size()));

    group->cig.SetState(CigState::CREATING);
    IsoManager::GetInstance()->CreateCig(iso_client_handle_, group->group_id_, std::move(param));
    log::debug("Group: {}, id: {} cig state: {}", std::format_ptr(group), group->group_id_,
               ToString(group->cig.GetState()));
    return true;
  }

  static bool CisCreateForDevice(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    std::vector<EXT_CIS_CREATE_CFG> conn_pairs;
    struct ase* ase = leAudioDevice->GetFirstActiveAse();

    /* Make sure CIG is there */
    if (group->cig.GetState() != CigState::CREATED) {
      log::error("CIG is not created for group_id {}", group->group_id_);
      group->PrintDebugState();
      return false;
    }

    std::stringstream extra_stream;
    do {
      /* First in ase pair is Sink, second Source */
      auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(ase->cis_conn_hdl);

      /* Already in pending state - bi-directional CIS or seconde CIS to same
       * device */
      if (ase->cis_state == CisState::CONNECTING || ase->cis_state == CisState::CONNECTED) {
        continue;
      }

      if (ases_pair.sink) {
        ases_pair.sink->cis_state = CisState::CONNECTING;
      }
      if (ases_pair.source) {
        ases_pair.source->cis_state = CisState::CONNECTING;
      }

      uint16_t acl_handle = get_btm_client_interface().peer.BTM_GetHCIConnHandle(
              leAudioDevice->address_, BT_TRANSPORT_LE);
      conn_pairs.push_back({.cis_conn_handle = ase->cis_conn_hdl, .acl_conn_handle = acl_handle});
      log::info("cis handle: 0x{:04x}, acl handle: 0x{:04x}", ase->cis_conn_hdl, acl_handle);
      extra_stream << "cis_h:" << loghex(ase->cis_conn_hdl) << " acl_h:" << loghex(acl_handle)
                   << ";;";
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, leAudioDevice->group_id_, RawAddress::kEmpty,
            kLogCisCreateOp + "#CIS: " + std::to_string(conn_pairs.size()), extra_stream.str());

    IsoManager::GetInstance()->EstablishCis({.conn_pairs = std::move(conn_pairs)});

    return true;
  }

  static bool CisCreate(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    struct ase* ase;
    std::vector<EXT_CIS_CREATE_CFG> conn_pairs;

    log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

    /* Make sure CIG is there */
    if (group->cig.GetState() != CigState::CREATED) {
      log::error("CIG is not created for group_id {}", group->group_id_);
      group->PrintDebugState();
      return false;
    }

    do {
      ase = leAudioDevice->GetFirstActiveAse();
      log::assert_that(ase, "shouldn't be called without an active ASE");
      do {
        /* First is ase pair is Sink, second Source */
        auto ases_pair = leAudioDevice->GetAsesByCisConnHdl(ase->cis_conn_hdl);

        /* Already in pending state - bi-directional CIS */
        if (ase->cis_state == CisState::CONNECTING) {
          continue;
        }

        if (ases_pair.sink) {
          ases_pair.sink->cis_state = CisState::CONNECTING;
        }
        if (ases_pair.source) {
          ases_pair.source->cis_state = CisState::CONNECTING;
        }

        uint16_t acl_handle = get_btm_client_interface().peer.BTM_GetHCIConnHandle(
                leAudioDevice->address_, BT_TRANSPORT_LE);
        conn_pairs.push_back({.cis_conn_handle = ase->cis_conn_hdl, .acl_conn_handle = acl_handle});
        log::debug("cis handle: {} acl handle : 0x{:x}", ase->cis_conn_hdl, acl_handle);
      } while ((ase = leAudioDevice->GetNextActiveAse(ase)));
    } while ((leAudioDevice = group->GetNextActiveDevice(leAudioDevice)));

    IsoManager::GetInstance()->EstablishCis({.conn_pairs = std::move(conn_pairs)});

    return true;
  }

  static void PrepareIsoDataPath(int group_id, LeAudioDevice* leAudioDevice, struct ase* ase) {
    bluetooth::hci::iso_manager::iso_data_path_params param = {
            .data_path_dir = ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink
                                     ? bluetooth::hci::iso_manager::kIsoDataPathDirectionIn
                                     : bluetooth::hci::iso_manager::kIsoDataPathDirectionOut,
            .data_path_id = ase->data_path_configuration.dataPathId,
            .codec_id_format = ase->data_path_configuration.isoDataPathConfig.codecId.coding_format,
            .codec_id_company =
                    ase->data_path_configuration.isoDataPathConfig.codecId.vendor_company_id,
            .codec_id_vendor =
                    ase->data_path_configuration.isoDataPathConfig.codecId.vendor_codec_id,
            .controller_delay = ase->data_path_configuration.isoDataPathConfig.controllerDelayUs,
            .codec_conf = ase->data_path_configuration.isoDataPathConfig.configuration,
    };

    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, group_id, leAudioDevice->address_,
            kLogSetIsoDataPathOp + "cis_h:" + loghex(ase->cis_conn_hdl),
            "direction: " + loghex(param.data_path_dir) + ", codecId: " +
                    ToString(ase->data_path_configuration.isoDataPathConfig.codecId));

    set_ase_data_path(leAudioDevice->address_, ase, DataPathState::CONFIGURING);
    IsoManager::GetInstance()->SetupIsoDataPath(ase->cis_conn_hdl, std::move(param));
  }

  static void PrepareDataPath(int group_id, struct ase* ase) {
    if (!ase) {
      log::error("Invalid ASE");
      return;
    }

    hci_data_direction_t direction =
            ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink
                    ? hci_data_direction_t::HOST_TO_CONTROLLER
                    : hci_data_direction_t::CONTROLLER_TO_HOST;
    CodecManager::GetInstance()->ConfigureDataPath(direction,
                                                   ase->data_path_configuration.dataPathId,
                                                   ase->data_path_configuration.dataPathConfig);
    LeAudioLogHistory::Get()->AddLogHistory(
            kLogStateMachineTag, group_id, RawAddress::kEmpty,
            kLogSetDataPathOp + "cis_h:" + loghex(ase->cis_conn_hdl),
            "direction: " + loghex(static_cast<int>(direction)) +
                    ", dataPathId: " + ToString(ase->data_path_configuration.dataPathId));
  }

  static void ReleaseDataPath(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    log::assert_that(leAudioDevice, "Shouldn't be called without an active device.");

    auto ase = leAudioDevice->GetFirstActiveAseByCisAndDataPathState(CisState::CONNECTED,
                                                                     DataPathState::CONFIGURED);
    log::assert_that(ase, "Shouldn't be called without an active ASE.");
    RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
  }

  void SetAseExpectedState(LeAudioDevice* leAudioDevice, struct ase* ase, AseState state) {
    log::info("{} ({}), ase_id: {}, {} -> {}", leAudioDevice->address_, leAudioDevice->group_id_,
              ase->id, ToString(ase->expected_state), ToString(state));
    ase->expected_state = state;
  }

  void SetAseState(LeAudioDevice* leAudioDevice, struct ase* ase, AseState state) {
    log::info("{} ({}), ase_id: {}, {} -> {}", leAudioDevice->address_, leAudioDevice->group_id_,
              ase->id, ToString(ase->state), ToString(state));

    log_history_->AddLogHistory(kLogStateMachineTag, leAudioDevice->group_id_,
                                leAudioDevice->address_,
                                "ASE_ID " + std::to_string(ase->id) + ": " + kLogStateChangedOp,
                                ToString(ase->state) + "->" + ToString(state));

    ase->state = state;
  }

  LeAudioDevice* getDeviceTryingToAttachTheStream(LeAudioDeviceGroup* group) {
    /* Device which is attaching the stream is just an active device not in
     * STREAMING state and NOT in  the RELEASING state.
     * The precondition is, that TargetState is Streaming
     */

    log::debug("group_id: {}, targetState: {}", group->group_id_,
               ToString(group->GetTargetState()));

    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      return nullptr;
    }

    for (auto dev = group->GetFirstActiveDevice(); dev != nullptr;
         dev = group->GetNextActiveDevice(dev)) {
      if (!dev->HaveAllActiveAsesSameState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) &&
          !dev->HaveAnyReleasingAse()) {
        log::debug("Attaching device {} to group_id: {}", dev->address_, group->group_id_);
        return dev;
      }
    }
    return nullptr;
  }

  void AseStateMachineProcessIdle(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        ase->active = false;
        ase->configured_for_context_type =
                bluetooth::le_audio::types::LeAudioContextType::UNINITIALIZED;

        if (!leAudioDevice->HaveAllActiveAsesSameState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE)) {
          /* More ASEs notification from this device has to come for this group
           */
          log::debug("Wait for more ASE to configure for device {}", leAudioDevice->address_);
          return;
        }

        if (!group->HaveAllActiveDevicesAsesTheSameState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE)) {
          log::debug("Waiting for more devices to get into idle state");
          return;
        }

        /* Last node is in releasing state*/
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
        group->PrintDebugState();

        /* If all CISes are disconnected, notify upper layer about IDLE state,
         * otherwise wait for */
        if (!group->HaveAllCisesDisconnected() ||
            getDeviceTryingToAttachTheStream(group) != nullptr) {
          log::warn("Not all CISes removed before going to IDLE for group {}, waiting...",
                    group->group_id_);
          group->PrintDebugState();
          return;
        }

        cancel_watchdog_if_needed(group->group_id_);
        ReleaseCisIds(group);
        state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::IDLE);

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING:
        log::error("Ignore invalid attempt of state transition from  {} to {}, {}, ase_id: {}",
                   ToString(ase->state), ToString(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        log::error("Invalid state transition from {} to {}, {}, ase_id: {}. Stopping the stream.",
                   ToString(ase->state), ToString(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  bool PrepareAndSendQoSToTheGroup(LeAudioDeviceGroup* group) {
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      log::error("No active device for the group");
      group->PrintDebugState();
      ClearGroup(group, true);
      return false;
    }

    for (; leAudioDevice; leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      if (!PrepareAndSendConfigQos(group, leAudioDevice)) {
        log::warn("Could not trigger QoS configured state for group_id: {} device: {}",
                  group->group_id_, leAudioDevice->address_);
        return false;
      }
    }

    return true;
  }

  bool PrepareAndSendCodecConfigToTheGroup(LeAudioDeviceGroup* group) {
    log::info("group_id: {}", group->group_id_);
    auto leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      log::error("No active device for the group");
      return false;
    }

    bool result = false;
    for (; leAudioDevice; leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      if (PrepareAndSendCodecConfigure(group, leAudioDevice)) {
        result = true;
      }
    }
    return result;
  }

  bool PrepareAndSendCodecConfigure(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    struct bluetooth::le_audio::client_parser::ascs::ctp_codec_conf conf;
    std::vector<struct bluetooth::le_audio::client_parser::ascs::ctp_codec_conf> confs;
    struct ase* ase;
    std::stringstream msg_stream;
    std::stringstream extra_stream;

    if (!group->cig.AssignCisIds(leAudioDevice)) {
      log::error("unable to assign CIS IDs");
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::UNABLE_TO_ASSIGN_CISES);
      return false;
    }

    if (group->cig.GetState() == CigState::CREATED) {
      group->AssignCisConnHandlesToAses(leAudioDevice);
    }

    msg_stream << kLogAseConfigOp;

    ase = leAudioDevice->GetFirstActiveAse();
    log::assert_that(ase, "shouldn't be called without an active ASE");
    for (; ase != nullptr; ase = leAudioDevice->GetNextActiveAse(ase)) {
      log::debug("device: {}, ase_id: {}, cis_id: {}, ase state: {}", leAudioDevice->address_,
                 ase->id, ase->cis_id, ToString(ase->state));
      conf.ase_id = ase->id;
      conf.target_latency = ase->target_latency;
      conf.target_phy =
              le_audio::utils::GetTargetPhyFromPreferredPhy(group->GetPhyBitmask(ase->direction));
      conf.codec_id = ase->codec_config.id;

      if (!ase->codec_config.vendor_params.empty()) {
        log::debug("Using vendor codec configuration.");
        conf.codec_config = ase->codec_config.vendor_params;
      } else {
        conf.codec_config = ase->codec_config.params.RawPacket();
      }
      confs.push_back(conf);
      SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

      msg_stream << "ASE_ID " << +conf.ase_id << ",";
      if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
        extra_stream << "snk,";
      } else {
        extra_stream << "src,";
      }
      extra_stream << +conf.codec_id.coding_format << "," << +conf.target_latency << ";;";
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeCodecConfiguration;

    std::vector<uint8_t> value;
    log::info("{} -> ", leAudioDevice->address_);
    bluetooth::le_audio::client_parser::ascs::PrepareAseCtpCodecConfig(confs, value);
    WriteToControlPoint(leAudioDevice, value);

    log_history_->AddLogHistory(kLogControlPointCmd, group->group_id_, leAudioDevice->address_,
                                msg_stream.str(), extra_stream.str());
    return true;
  }

  void AseStateMachineProcessCodecConfigured(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          uint8_t* data, uint16_t len, LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");

      return;
    }

    /* Internal helper for filling in the QoS parameters for an ASE, based
     * on the codec configure state and the prefferend ASE QoS parameters.
     * Note: The whole group state dependent parameters (out_cfg.framing, and
     *       out.cfg.presentation_delay) are calculated later, in the
     *       PrepareAndSendConfigQos(), once the whole group transitions to a
     *       proper state.
     */
    auto qos_config_update = [leAudioDevice](
                                     const struct bluetooth::le_audio::client_parser::ascs::
                                             ase_codec_configured_state_params& rsp,
                                     bluetooth::le_audio::types::AseQosPreferences& out_qos,
                                     bluetooth::le_audio::types::AseQosConfiguration& out_cfg) {
      out_qos.supported_framing = rsp.framing;
      out_qos.preferred_phy = rsp.preferred_phy;
      out_qos.preferred_retrans_nb = rsp.preferred_retrans_nb;
      out_qos.pres_delay_min = rsp.pres_delay_min;
      out_qos.pres_delay_max = rsp.pres_delay_max;
      out_qos.preferred_pres_delay_min = rsp.preferred_pres_delay_min;
      out_qos.preferred_pres_delay_max = rsp.preferred_pres_delay_max;

      /* Validate and update QoS to be consistent */
      if ((!out_cfg.max_transport_latency ||
           out_cfg.max_transport_latency > rsp.max_transport_latency) ||
          !out_cfg.retrans_nb || !out_cfg.phy) {
        out_cfg.max_transport_latency = rsp.max_transport_latency;
        out_cfg.retrans_nb = rsp.preferred_retrans_nb;
        out_cfg.phy = leAudioDevice->GetPreferredPhyBitmask(rsp.preferred_phy);
        log::info(
                "Using server preferred QoS settings. Max Transport Latency: {}, "
                "Retransmission Number: {}, Phy: {}",
                out_cfg.max_transport_latency, out_cfg.retrans_nb, out_cfg.phy);
      }
    };

    /* ase contain current ASE state. New state is in "arh" */
    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE: {
        struct bluetooth::le_audio::client_parser::ascs::ase_codec_configured_state_params rsp;

        /* Cache codec configured status values for further
         * configuration/reconfiguration
         */
        if (!ParseAseStatusCodecConfiguredStateParams(rsp, len, data)) {
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device.
           * Reconfigure CIG if current CIG supported Max Transport Latency for
           * a direction, cannot be supported by the newly connected member
           * device's ASE for the direction.
           */

          uint16_t cig_curr_max_trans_lat_c_to_p = group->GetMaxTransportLatencyCToP();
          uint16_t cig_curr_max_trans_lat_p_to_c = group->GetMaxTransportLatencyPToC();

          if ((ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink &&
               cig_curr_max_trans_lat_c_to_p > rsp.max_transport_latency) ||
              (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSource &&
               cig_curr_max_trans_lat_p_to_c > rsp.max_transport_latency)) {
            group->SetPendingConfiguration();
            state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                    group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
            return;
          }
        }

        qos_config_update(rsp, ase->qos_preferences, ase->qos_config);
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
          /* This is autonomus change of the remote device */
          log::debug("Autonomus change for device {}, ase id {}. Just store it.",
                     leAudioDevice->address_, ase->id);
          if (group->HaveAllActiveDevicesAsesTheSameState(
                      AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED)) {
            group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
          }
          return;
        }

        if (leAudioDevice->HaveAnyUnconfiguredAses()) {
          /* More ASEs notification from this device has to come for this group
           */
          log::debug("More Ases to be configured for the device {}", leAudioDevice->address_);
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          /* Make sure that device is ready to be configured as we could also
           * get here triggered by the remote device. If device is not connected
           * yet, we should wait for the stack to trigger adding device to the
           * stream */
          if (leAudioDevice->GetConnectionState() ==
              bluetooth::le_audio::DeviceConnectState::CONNECTED) {
            if (!PrepareAndSendConfigQos(group, leAudioDevice)) {
              log::warn("Could not trigger QoS configured state for group_id: {} device: {}",
                        group->group_id_, leAudioDevice->address_);
              return;
            }
          } else {
            log::debug(
                    "Device {} initiated configured state but it is not yet ready to be configured",
                    leAudioDevice->address_);
          }
          return;
        }

        /* Configure ASEs for next device in group */
        if (group->HaveAnyActiveDeviceInUnconfiguredState()) {
          log::debug("Waiting for all the ASES in the Configured state");
          return;
        }

        /* Last node configured, process group to codec configured state */
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
            group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          if (group->cig.GetState() == CigState::CREATED) {
            /* It can happen on the earbuds switch scenario. When one device
             * is getting remove while other is adding to the stream and CIG is
             * already created.
             * Also if one of the set members got reconnected while the other was in QoSConfigured
             * state. In this case, state machine will keep CIG but will send Codec Config to all
             * the set members and when ASEs will move to Codec Configured State, we would like a
             * whole group to move to QoS Configure.*/
            if (!PrepareAndSendQoSToTheGroup(group)) {
              /* We are here only in case there is no Active devices from some reason.
               * PrepareAndSendQoSToTheGroup already removed the CIG and moved the state machine to
               * Idle.
               */
              if (com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
                log::error("Could not trigger QoS configured state for group_id: {}",
                           group->group_id_);
                return;
              }
            }
          } else if (!CigCreate(group)) {
            log::error("Could not create CIG. Stop the stream for group {}", group->group_id_);
            state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                    group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIG);
          }
          return;
        }

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED &&
            group->IsPendingConfiguration()) {
          log::info("Configured state completed");

          /* If all CISes are disconnected, notify upper layer about IDLE
           * state, otherwise wait for */
          if (!group->HaveAllCisesDisconnected()) {
            log::warn("Not all CISes removed before going to CONFIGURED for group {}, waiting...",
                      group->group_id_);
            group->PrintDebugState();
            return;
          }

          group->ClearPendingConfiguration();
          state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                   GroupStreamStatus::CONFIGURED_BY_USER);

          /* No more transition for group */
          cancel_watchdog_if_needed(group->group_id_);
          return;
        }

        log::error(", invalid state transition, from: {} to {}", ToString(group->GetState()),
                   ToString(group->GetTargetState()));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        log::verbose("Reconfiguring ase {} from QoS to Codec Configured group_id: {}", ase->id,
                     group->group_id_);
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
        group->PrintDebugState();

        FALLTHROUGH_INTENDED;
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED: {
        /* Received Configured in Configured state. This could be done
         * autonomously because of the reconfiguration done by us
         */

        struct bluetooth::le_audio::client_parser::ascs::ase_codec_configured_state_params rsp;

        /* Cache codec configured status values for further
         * configuration/reconfiguration
         */
        if (!ParseAseStatusCodecConfiguredStateParams(rsp, len, data)) {
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
          return;
        }

        /* This may be a notification from a re-configured ASE */
        ase->reconfigure = false;
        qos_config_update(rsp, ase->qos_preferences, ase->qos_config);

        if (leAudioDevice->HaveAnyUnconfiguredAses()) {
          /* Waiting for others to be reconfigured */
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
            group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          /* Make sure that device is ready to be configured as we could also
           * get here triggered by the remote device. If device is not connected
           * yet, we should wait for the stack to trigger adding device to the
           * stream */
          if (leAudioDevice->GetConnectionState() ==
              bluetooth::le_audio::DeviceConnectState::CONNECTED) {
            if (!PrepareAndSendConfigQos(group, leAudioDevice)) {
              log::warn("Could not trigger QoS configured state for group_id: {} device: {}",
                        group->group_id_, leAudioDevice->address_);
              return;
            }
          } else {
            log::debug(
                    "Device {} initiated configured state but it is not yet ready to be configured",
                    leAudioDevice->address_);
          }
          return;
        }

        if (group->HaveAnyActiveDeviceInUnconfiguredState()) {
          log::debug("Waiting for all the devices to be configured for group id {}",
                     group->group_id_);
          return;
        }

        /* Last node configured, process group to codec configured state */
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
            group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          if (group->cig.GetState() == CigState::CREATED) {
            /* It can happen on the earbuds switch scenario. When one device
             * is getting remove while other is adding to the stream and CIG is
             * already created.
             * Also it can happen, when second set member is adding while the other is in
             * Streaming or QoS Configured state.
             */
            bool qos_succeed = false;
            if (com_android_bluetooth_flags_leaudio_fix_qos_reconfiguration()) {
              qos_succeed = PrepareAndSendQoSToTheGroup(group);
            } else {
              qos_succeed = PrepareAndSendConfigQos(group, leAudioDevice);
            }

            if (!qos_succeed) {
              log::warn("Could not trigger QoS configured state for group_id: {} device: {}",
                        group->group_id_, leAudioDevice->address_);
              return;
            }
          } else if (!CigCreate(group)) {
            log::error("Could not create CIG. Stop the stream for group {}", group->group_id_);
            state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                    group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIG);
          }
          return;
        }

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED &&
            group->IsPendingConfiguration()) {
          log::info("Configured state completed");
          group->ClearPendingConfiguration();
          state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                   GroupStreamStatus::CONFIGURED_BY_USER);

          /* No more transition for group */
          cancel_watchdog_if_needed(group->group_id_);
          return;
        }

        log::info("Autonomous change, from: {} to {}", ToString(group->GetState()),
                  ToString(group->GetTargetState()));

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING:
        log::error("Ignore invalid attempt of state transition from {} to {}, {}, ase_id: {}",
                   ToString(ase->state),
                   ToString(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED),
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING:
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
        ase->active = false;

        if (!leAudioDevice->HaveAllActiveAsesSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED)) {
          /* More ASEs notification from this device has to come for this group
           */
          log::debug("Wait for more ASE to configure for device {}", leAudioDevice->address_);
          return;
        }

        {
          auto activeDevice = group->GetFirstActiveDevice();
          if (activeDevice) {
            log::debug("There is at least one active device {}, wait to become inactive",
                       activeDevice->address_);
            return;
          }
        }

        /* Last node is in releasing state*/
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED);
        /* Remote device has cache and keep staying in configured state after
         * release. Therefore, we assume this is a target state requested by
         * remote device.
         */
        group->SetTargetState(group->GetState());

        if (!group->HaveAllCisesDisconnected()) {
          log::warn("Not all CISes removed before going to IDLE for group {}, waiting...",
                    group->group_id_);
          group->PrintDebugState();
          return;
        }

        cancel_watchdog_if_needed(group->group_id_);

        state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                 GroupStreamStatus::CONFIGURED_AUTONOMOUS);
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        log::error("Invalid state transition from {} to {}, {}, ase_id: {}. Stopping the stream",
                   ToString(ase->state),
                   ToString(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED),
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  void AseStateMachineProcessQosConfigured(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");

      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        log::info(
                "Unexpected state transition from {} to {}, {}, ase_id: {}, "
                "fallback to transition from {} to {}",
                ToString(ase->state), ToString(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED),
                leAudioDevice->address_, ase->id,
                ToString(AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED),
                ToString(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED));
        group->PrintDebugState();
        [[fallthrough]];

      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING &&
            group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          log::warn("{}, ase_id: {}, target state: {}", leAudioDevice->address_, ase->id,
                    ToString(group->GetTargetState()));
          group->PrintDebugState();
          return;
        }

        if (!leAudioDevice->HaveAllActiveAsesSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
          /* More ASEs notification from this device has to come for this group
           */
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device. */
          PrepareAndSendEnable(leAudioDevice,
                               state_machine_callbacks_->OnGetEnabledDirections(group->group_id_));
          return;
        }

        if (!group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
          log::debug("Waiting for all the devices to be in QoS state");
          return;
        }

        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          cancel_watchdog_if_needed(group->group_id_);
          group->ClearPendingConfiguration();
          state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                   GroupStreamStatus::CONFIGURED_BY_USER);
          return;
        }
        PrepareAndSendEnableToTheGroup(group);

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
          /* Source ASE cannot go from Streaming to QoS Configured state */
          log::error("invalid state transition, from: {}, to: {}", static_cast<int>(ase->state),
                     static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED));
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
          return;
        }

        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        /* SINK ASE goes from STREAMING to QoS Configured when Disabling. */
        if (ase->expected_state == AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
          SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
          auto enabled_directions =
                  state_machine_callbacks_->OnGetEnabledDirections(group->group_id_);
          if (ase->direction & enabled_directions) {
            log::info("Quick disable / enable happened for {}, {}", leAudioDevice->address_,
                      ase->id);
            PrepareAndSendEnable(leAudioDevice, ase->direction, ase->id);
            return;
          }
        }

        if (group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
        }

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          /* Process the Disable Transition of the rest of group members if no
           * more ASE notifications has to come from this device. */
          ProcessGroupDisable(group);
        } else {
          /* Remote may autonomously bring ASEs to QoS configured state */
          ProcessAutonomousDisable(group, leAudioDevice, ase);
        }

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        if (ase->expected_state == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          /* We are here after Receiver stop ready. Check if we should not enable direction */
          auto enabled_directions =
                  state_machine_callbacks_->OnGetEnabledDirections(group->group_id_);
          if (ase->direction & enabled_directions) {
            log::info("Quick disable / enable happened for {}, {}", leAudioDevice->address_,
                      ase->id);
            PrepareAndSendEnable(leAudioDevice, ase->direction, ase->id);
            return;
          }
        }

        /* More ASEs notification from this device has to come for this group */
        if (!group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
          return;
        }

        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

        if (!group->HaveAllCisesDisconnected()) {
          return;
        }

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          /* No more transition for group */
          cancel_watchdog_if_needed(group->group_id_);

          state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::SUSPENDED);
        } else {
          log::error(", invalid state transition, from: {}, to: {}", ToString(group->GetState()),
                     ToString(group->GetTargetState()));
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
          return;
        }
        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_IDLE:
      case AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING:
        // Do nothing here, just print an error message
        log::error("Ignore invalid attempt of state transition from {} to {}, {}, ase_id: {}",
                   ToString(ase->state), ToString(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED),
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        log::error("Invalid state transition from {} to {}, {}, ase_id: {}. Stopping the stream.",
                   ToString(ase->state), ToString(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED),
                   leAudioDevice->address_, ase->id);
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  void ClearGroup(LeAudioDeviceGroup* group, bool report_idle_state) {
    log::debug("group_id: {}", group->group_id_);
    group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
    group->SetTargetState(AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);

    /* Clear group pending status */
    group->ClearStreamingMetadataContexts();
    group->ClearPendingConfiguration();

    cancel_watchdog_if_needed(group->group_id_);
    ReleaseCisIds(group);
    RemoveCigForGroup(group);

    if (report_idle_state) {
      state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::IDLE);
    }
  }

  bool PrepareAndSendEnableToTheGroup(LeAudioDeviceGroup* group, uint8_t remote_directions) {
    log::info("group_id: {}, remote_directions: {}", group->group_id_, remote_directions);

    auto leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      log::error("No active device for the group");
      group->PrintDebugState();
      ClearGroup(group, true);
      return false;
    }

    bool result = false;
    for (; leAudioDevice; leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      result |= PrepareAndSendEnable(leAudioDevice, remote_directions);
    }
    return result;
  }

  bool PrepareAndSendEnableToTheGroup(LeAudioDeviceGroup* group) {
    return PrepareAndSendEnableToTheGroup(
            group, state_machine_callbacks_->OnGetEnabledDirections(group->group_id_));
  }

  bool PrepareAndSendEnable(LeAudioDevice* leAudioDevice, uint8_t remote_directions,
                            int exact_ase = -1) {
    struct bluetooth::le_audio::client_parser::ascs::ctp_enable conf;
    std::vector<struct bluetooth::le_audio::client_parser::ascs::ctp_enable> confs;
    std::vector<uint8_t> value;
    struct ase* ase;
    std::stringstream msg_stream;
    std::stringstream extra_stream;

    msg_stream << kLogAseEnableOp;

    ase = leAudioDevice->GetFirstActiveAse();
    log::assert_that(ase, "shouldn't be called without an active ASE");
    do {
      if (!(ase->direction & remote_directions)) {
        log::info("group_id: {}, {}, ase_id: {} ({:#x}), enabled_directions {:#x} not to enable",
                  leAudioDevice->group_id_, leAudioDevice->address_, ase->id, ase->direction,
                  remote_directions);
        continue;
      }

      if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
        log::warn("group_id: {}, {}, ase_id: {} ({:#x}) not in QoS state: {} ",
                  leAudioDevice->group_id_, leAudioDevice->address_, ase->id, ase->direction,
                  ToString(ase->state));
        continue;
      }

      if (ase->expected_state == AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING) {
        log::warn("group_id: {}, {}, ase_id: {} ({:#x}) already enabling", leAudioDevice->group_id_,
                  leAudioDevice->address_, ase->id, ase->direction);
        continue;
      }

      if (exact_ase >= 0 && ase->id != exact_ase) {
        log::info("Not an exact ase: {} != {}", exact_ase, ase->id);
        continue;
      }

      log::debug("device: {}, ase_id: {}({:#x}), cis_id: {}, ase state: {}",
                 leAudioDevice->address_, ase->id, ase->direction, ase->cis_id,
                 ToString(ase->state));

      conf.ase_id = ase->id;
      conf.metadata = ase->metadata.RawPacket();
      confs.push_back(conf);
      SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

      /* Below is just for log history */
      msg_stream << "ASE_ID " << +ase->id << ",";
      extra_stream << "meta: " << base::HexEncode(conf.metadata.data(), conf.metadata.size())
                   << ";;";
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    if (confs.empty()) {
      log::warn("group_id: {}, {}, nothing to enable", leAudioDevice->group_id_,
                leAudioDevice->address_);
      return false;
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeEnable;

    bluetooth::le_audio::client_parser::ascs::PrepareAseCtpEnable(confs, value);
    WriteToControlPoint(leAudioDevice, value);

    log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);
    log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                leAudioDevice->address_, msg_stream.str(), extra_stream.str());
    return true;
  }

  GroupStreamStatus PrepareAndSendDisableToTheGroup(
          LeAudioDeviceGroup* group,
          uint8_t remote_directions = bluetooth::le_audio::types::kLeAudioDirectionBoth) {
    log::info("grop_id: {}", group->group_id_);

    auto leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      log::error("No active device for the group");
      group->PrintDebugState();
      ClearGroup(group, false);
      return GroupStreamStatus::IDLE;
    }

    bool sent = false;
    for (; leAudioDevice; leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      sent |= PrepareAndSendDisable(leAudioDevice, remote_directions);
    }

    return sent ? GroupStreamStatus::SUSPENDING : GroupStreamStatus::IDLE;
  }

  bool PrepareAndSendDisable(LeAudioDevice* leAudioDevice, uint8_t remote_directions) {
    ase* ase = leAudioDevice->GetFirstActiveAse();
    log::assert_that(ase, "shouldn't be called without an active ASE");

    std::stringstream msg_stream;
    msg_stream << kLogAseDisableOp;

    std::vector<uint8_t> ids;
    do {
      if (!(ase->direction & remote_directions)) {
        log::info(
                "group_id: {}, {}, ase_id: {} ({:#x}),  enabled_directions {:#x} not to disable: ",
                leAudioDevice->group_id_, leAudioDevice->address_, ase->id, ase->direction,
                remote_directions);
        continue;
      }

      if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
          ase->expected_state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        log::warn("Probably ASE is already disabling: ase->state {}, ase->expected_state {}",
                  ToString(ase->state), ToString(ase->expected_state));
        continue;
      }

      log::debug("device: {}, ase_id: {}({:#x}), cis_id: {}, ase state: {}",
                 leAudioDevice->address_, ase->id, ase->direction, ase->cis_id,
                 ToString(ase->state));

      ids.push_back(ase->id);
      SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);
      msg_stream << "ASE_ID " << +ase->id << ", ";
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    if (ids.empty()) {
      log::info("{}, no ASE to Disable, remote_directions: {}", leAudioDevice->address_,
                remote_directions);
      return false;
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeDisable;

    log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);
    std::vector<uint8_t> value;
    bluetooth::le_audio::client_parser::ascs::PrepareAseCtpDisable(ids, value);
    WriteToControlPoint(leAudioDevice, value);

    log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                leAudioDevice->address_, msg_stream.str());
    return true;
  }

  GroupStreamStatus PrepareAndSendReleaseToTheGroup(LeAudioDeviceGroup* group) {
    log::info("group_id: {}", group->group_id_);
    LeAudioDevice* leAudioDevice = group->GetFirstActiveDevice();
    if (!leAudioDevice) {
      log::error("No active device for the group");
      group->PrintDebugState();
      ClearGroup(group, false);
      return GroupStreamStatus::IDLE;
    }

    bool releasing = false;
    for (; leAudioDevice; leAudioDevice = group->GetNextActiveDevice(leAudioDevice)) {
      releasing |= PrepareAndSendRelease(leAudioDevice);
    }

    if (releasing) {
      return GroupStreamStatus::RELEASING;
    }

    return GroupStreamStatus::IDLE;
  }

  bool PrepareAndSendRelease(LeAudioDevice* leAudioDevice) {
    ase* ase = leAudioDevice->GetFirstActiveAse();
    log::assert_that(ase, "shouldn't be called without an active ASE");

    std::vector<uint8_t> ids;
    std::stringstream stream;
    stream << kLogAseReleaseOp;

    do {
      log::debug("device: {}, ase_id: {}, cis_id: {}, ase state: {}", leAudioDevice->address_,
                 ase->id, ase->cis_id, ToString(ase->state));
      if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
        ids.push_back(ase->id);
        SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

        stream << "ASE_ID " << +ase->id << ",";
      } else {
        log::info("{}, ase: {} already in idle. Deactivate it", leAudioDevice->address_, ase->id);
        ase->active = false;
      }
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    std::vector<uint8_t> value;
    if (!bluetooth::le_audio::client_parser::ascs::PrepareAseCtpRelease(ids, value)) {
      log::info("Nothing to send to {}", leAudioDevice->address_);
      return false;
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeRelease;

    WriteToControlPoint(leAudioDevice, value);

    log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);
    log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                leAudioDevice->address_, stream.str());
    return true;
  }

  bool PrepareAndSendConfigQos(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    std::vector<struct bluetooth::le_audio::client_parser::ascs::ctp_qos_conf> confs;

    bool validate_transport_latency = false;
    bool validate_max_sdu_size = false;

    std::stringstream msg_stream;
    msg_stream << kLogAseQoSConfigOp;

    std::stringstream extra_stream;
    int number_of_active_ases = 0;
    int number_of_streaming_ases = 0;

    for (struct ase* ase = leAudioDevice->GetFirstActiveAse(); ase != nullptr;
         ase = leAudioDevice->GetNextActiveAse(ase)) {
      log::debug("device: {}, ase_id: {}, cis_id: {}, ase state: {}", leAudioDevice->address_,
                 ase->id, ase->cis_id, ToString(ase->state));

      /* QoS Config can be done on ASEs which are in Codec Configured and QoS Configured state.
       * If ASE is streaming, it can be skipped.
       */
      number_of_active_ases++;
      if (ase->state == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        number_of_streaming_ases++;
        continue;
      }

      /* Fill in the whole group dependent ASE parameters */
      if (!group->GetPresentationDelay(&ase->qos_config.presentation_delay, ase->direction)) {
        log::error("inconsistent presentation delay for group");
        group->PrintDebugState();
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_DEVICE_CONFIGURATION);
        if (!com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
          return true;
        }
        return false;
      }
      ase->qos_config.framing = group->GetFraming();

      struct bluetooth::le_audio::client_parser::ascs::ctp_qos_conf conf;
      conf.ase_id = ase->id;
      conf.cig = group->group_id_;
      conf.cis = ase->cis_id;
      conf.framing = ase->qos_config.framing;
      conf.phy = ase->qos_config.phy;
      conf.max_sdu = ase->qos_config.max_sdu_size;
      conf.retrans_nb = ase->qos_config.retrans_nb;
      conf.pres_delay = ase->qos_config.presentation_delay;
      conf.sdu_interval = ase->qos_config.sdu_interval;

      if (conf.sdu_interval == 0) {
        log::error("Invalid SDU interval for group {}", group->group_id_);
        group->PrintDebugState();
        if (!com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
          return true;
        }
        log::assert_that(false, "SDU is 0 which shall be already catched when CIG is created.");
        return false;
      }

      msg_stream << "ASE " << +conf.ase_id << ",";
      if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
        conf.max_transport_latency = group->GetMaxTransportLatencyCToP();
        extra_stream << "snk,";
      } else {
        conf.max_transport_latency = group->GetMaxTransportLatencyPToC();
        extra_stream << "src,";
      }

      if (conf.max_transport_latency > bluetooth::le_audio::types::kMaxTransportLatencyMin) {
        validate_transport_latency = true;
      }

      if (conf.max_sdu > 0) {
        validate_max_sdu_size = true;
      }
      confs.push_back(conf);
      SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);

      // dir...cis_id,sdu,lat,rtn,phy,frm;;
      extra_stream << +conf.cis << "," << +conf.max_sdu << "," << +conf.max_transport_latency << ","
                   << +conf.retrans_nb << "," << +conf.phy << "," << +conf.framing << ";;";
    }

    if (number_of_streaming_ases > 0 && number_of_streaming_ases == number_of_active_ases) {
      log::debug("Device {} is already streaming", leAudioDevice->address_);
      /* If there is no need to send QoS Config because device is streaming, let's treat it as
       * success */
      return true;
    }

    if (confs.size() == 0 || !validate_transport_latency || !validate_max_sdu_size) {
      log::error("Invalid configuration or latency or sdu size");
      group->PrintDebugState();
      if (!com_android_bluetooth_flags_leaudio_fix_clear_cises_in_the_cig()) {
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
        return true;
      }

      log::assert_that(false,
                       "Transport latency, max sdu size and configuration existence shall be "
                       "already validated before CIG is created.");
      return false;
    }

    leAudioDevice->last_ase_ctp_command_sent =
            bluetooth::le_audio::client_parser::ascs::kCtpOpcodeQosConfiguration;

    std::vector<uint8_t> value;
    bluetooth::le_audio::client_parser::ascs::PrepareAseCtpConfigQos(confs, value);
    WriteToControlPoint(leAudioDevice, value);

    log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);
    log_history_->AddLogHistory(kLogControlPointCmd, group->group_id_, leAudioDevice->address_,
                                msg_stream.str(), extra_stream.str());

    return true;
  }

  void PrepareAndSendUpdateMetadata(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                    const BidirectionalPair<AudioContexts>& context_types,
                                    const BidirectionalPair<std::vector<uint8_t>>& ccid_lists) {
    std::vector<struct bluetooth::le_audio::client_parser::ascs::ctp_update_metadata> confs;

    std::stringstream msg_stream;
    msg_stream << kLogAseUpdateMetadataOp;

    std::stringstream extra_stream;

    if (!leAudioDevice->IsMetadataChanged(context_types, ccid_lists)) {
      return;
    }

    /* Request server to update ASEs with new metadata */
    for (struct ase* ase = leAudioDevice->GetFirstActiveAse(); ase != nullptr;
         ase = leAudioDevice->GetNextActiveAse(ase)) {
      log::debug("device: {}, ase_id: {}, cis_id: {}, ase state: {}, ase expected_state: {}",
                 leAudioDevice->address_, ase->id, ase->cis_id, ToString(ase->state),
                 ToString(ase->expected_state));

      if (ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING &&
          ase->state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        /* This might happen when update metadata happens on late connect */
        log::debug(
                "Metadata for ase_id {} cannot be updated due to invalid ase state - see log above",
                ase->id);
        continue;
      }

      if (ase->expected_state != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
        log::info(
                "Metadata for ase_id {} cannot be updated due to invalid ase state - see log above",
                ase->id);
        continue;
      }

      msg_stream << "ASE_ID " << +ase->id << ",";
      if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
        extra_stream << "snk,";
      } else {
        extra_stream << "src,";
      }

      /* Filter multidirectional audio context for each ase direction */
      auto directional_audio_context =
              context_types.get(ase->direction) & group->GetAvailableContexts(ase->direction);

      LeAudioLtvMap new_metadata;
      if (directional_audio_context.any()) {
        new_metadata = leAudioDevice->GetMetadata(directional_audio_context,
                                                  ccid_lists.get(ase->direction));
      } else {
        new_metadata = leAudioDevice->GetMetadata(AudioContexts(LeAudioContextType::UNSPECIFIED),
                                                  std::vector<uint8_t>());
      }

      /* Do not update if metadata did not changed. */
      if (ase->metadata == new_metadata) {
        continue;
      }

      ase->metadata = new_metadata;

      struct bluetooth::le_audio::client_parser::ascs::ctp_update_metadata conf;

      conf.ase_id = ase->id;
      conf.metadata = ase->metadata.RawPacket();
      confs.push_back(conf);

      extra_stream << "meta: " << base::HexEncode(conf.metadata.data(), conf.metadata.size())
                   << ";;";
    }

    if (confs.size() != 0) {
      leAudioDevice->last_ase_ctp_command_sent =
              bluetooth::le_audio::client_parser::ascs::kCtpOpcodeUpdateMetadata;

      std::vector<uint8_t> value;
      bluetooth::le_audio::client_parser::ascs::PrepareAseCtpUpdateMetadata(confs, value);
      WriteToControlPoint(leAudioDevice, value);

      log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);

      log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                  leAudioDevice->address_, msg_stream.str(), extra_stream.str());
    }
  }

  void PrepareAndSendReceiverStartReady(LeAudioDevice* leAudioDevice, struct ase* ase) {
    std::vector<uint8_t> ids;
    std::vector<uint8_t> value;
    std::stringstream stream;

    stream << kLogAseStartReadyOp;

    do {
      if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSource) {
        if (ase->expected_state != AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING) {
          continue;
        }
        stream << "ASE_ID " << +ase->id << ",";
        ids.push_back(ase->id);
        SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
      }
    } while ((ase = leAudioDevice->GetNextActiveAse(ase)));

    if (ids.size() > 0) {
      leAudioDevice->last_ase_ctp_command_sent =
              bluetooth::le_audio::client_parser::ascs::kCtpOpcodeReceiverStartReady;

      bluetooth::le_audio::client_parser::ascs::PrepareAseCtpAudioReceiverStartReady(ids, value);
      WriteToControlPoint(leAudioDevice, value);

      log::info("group_id: {}, {}", leAudioDevice->group_id_, leAudioDevice->address_);
      log_history_->AddLogHistory(kLogControlPointCmd, leAudioDevice->group_id_,
                                  leAudioDevice->address_, stream.str());
    }
  }

  void AseStateMachineProcessEnabling(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");
      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);

        if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          log::warn("{}, ase_id: {}, target state: {}", leAudioDevice->address_, ase->id,
                    ToString(group->GetTargetState()));
          group->PrintDebugState();
          return;
        }

        if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
          /* For remote Sink direction, STREAMING state is expected just after ENABLING */
          SetAseExpectedState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          if (ase->cis_state < CisState::CONNECTING) {
            /* We are here because of the reconnection of the single device. */
            if (!CisCreateForDevice(group, leAudioDevice)) {
              state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                      group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIS);
              return;
            }
          }

          if (!leAudioDevice->HaveAllActiveAsesCisEst()) {
            /* More cis established events has to come */
            return;
          }

          if (!leAudioDevice->IsReadyToCreateStream()) {
            /* Device still remains in ready to create stream state. It means
             * that more enabling status notifications has to come.
             */
            return;
          }

          /* All CISes created. Send start ready for source ASE before we can go
           * to streaming state.
           */
          struct ase* ase = leAudioDevice->GetFirstActiveAse();
          log::assert_that(ase != nullptr, "shouldn't be called without an active ASE, device {}",
                           leAudioDevice->address_.ToString());
          PrepareAndSendReceiverStartReady(leAudioDevice, ase);

          return;
        }

        if (leAudioDevice->IsReadyToCreateStream()) {
          ProcessGroupEnable(group);
        }

        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        /* Enable/Switch Content */
        break;
      default:
        log::error("invalid state transition, from: {}, to: {}", static_cast<int>(ase->state),
                   static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  void AseStateMachineProcessStreaming(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          uint8_t* data, uint16_t len, LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");

      return;
    }

    struct bluetooth::le_audio::client_parser::ascs::ase_transient_state_params rsp;

    bool valid_response = ParseAseStatusTransientStateParams(rsp, len, data);

    std::optional<AudioContexts> streaming_audio_context;
    LeAudioLtvMap meta;
    if (valid_response && !rsp.metadata.empty() &&
        meta.Parse(rsp.metadata.data(), rsp.metadata.size())) {
      streaming_audio_context = meta.GetAsLeAudioMetadata().streaming_audio_context;
      if (!streaming_audio_context) {
        log::error("{}, ase_id: {}, Did not found streaming metadata while parsing metadata: {}",
                   leAudioDevice->address_, ase->id, bluetooth::common::ToHexString(rsp.metadata));
      }
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED:
        log::error("{}, ase_id: {}, moving from QoS Configured to Streaming is impossible.",
                   leAudioDevice->address_, ase->id);
        group->PrintDebugState();
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;

      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING: {
        std::vector<uint8_t> value;

        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);
        if (streaming_audio_context) {
          group->SetStreamingMetadataContexts(streaming_audio_context.value(), ase->direction);
        }

        if (!group->HasAllRequiredStreamingAses()) {
          log::info("More Ases to get in streaming state for group_id: {}", group->group_id_);
          return;
        }

        /* The group is not ready to stream yet as there is still pending CIS Establish event and/or
         * Data Path setup complete event */
        if (!group->IsGroupStreamReady()) {
          log::info("CISes are not yet ready, wait for it.");
          group->SetNotifyStreamingWhenCisesAreReadyFlag(true);
          return;
        }

        if (group->GetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* We are here because of the reconnection of the single device */
          log::info("{}, Ase id: {}, ase state: {}", leAudioDevice->address_, ase->id,
                    bluetooth::common::ToString(ase->state));
          cancel_watchdog_if_needed(group->group_id_);
          state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::STREAMING);
          return;
        }

        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
          /* No more transition for group */
          cancel_watchdog_if_needed(group->group_id_);

          /* Last node is in streaming state */
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING);

          state_machine_callbacks_->StatusReportCb(group->group_id_, GroupStreamStatus::STREAMING);
          return;
        }

        log::error(", invalid state transition, from: {}, to: {}", ToString(group->GetState()),
                   ToString(group->GetTargetState()));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        if (!valid_response) {
          state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                  group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_PARAMETERS);
          return;
        }

        /* Cache current as streaming metadata */
        if (streaming_audio_context) {
          group->SetStreamingMetadataContexts(streaming_audio_context.value(), ase->direction);
        }

        break;
      }
      default:
        log::error("invalid state transition, from: {}, to: {}", static_cast<int>(ase->state),
                   static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  void AseStateMachineProcessDisabling(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");

      return;
    }

    if (ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink) {
      /* Sink ASE state machine does not have Disabling state */
      log::error(", invalid state transition, from: {} , to: {}", ToString(group->GetState()),
                 ToString(group->GetTargetState()));
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING:
        /* TODO: Disable */
        break;
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING:
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);

        /* If target state is QoS Configured it means it is Suspended procedure where CISes
         * are going to be disconnected*/
        if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
          /* Process the Disable Transition of the rest of group members if no
           * more ASE notifications has to come from this device. */
          if (leAudioDevice->IsReadyToSuspendStream()) {
            ProcessGroupDisable(group);
          }
          return;
        }

        if (ase->expected_state == AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
          PrepareAndSendReceiverStopReady(leAudioDevice, ase);
          return;
        }

        ProcessAutonomousDisable(group, leAudioDevice, ase);
        break;

      default:
        log::error("invalid state transition, from: {}, to: {}", static_cast<int>(ase->state),
                   static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING));
        state_machine_callbacks_->OnStateMachineInvalidStatusCb(
                group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
        break;
    }
  }

  typedef enum {
    CIS_DISCONNECTED,
    CIS_DISCONNECTING,
    CIS_STILL_NEEDED,
  } LocalCisDisconnectResult_t;

  LocalCisDisconnectResult_t DisconnectCisIfNeeded(LeAudioDeviceGroup* group,
                                                   LeAudioDevice* leAudioDevice, struct ase* ase) {
    log::debug(
            "Group id: {}, {}, ase id: {}, cis_handle: 0x{:04x}, direction: {}, "
            "data_path_state: {}, cis_state: {}",
            group->group_id_, leAudioDevice->address_, ase->id, ase->cis_conn_hdl,
            ase->direction == bluetooth::le_audio::types::kLeAudioDirectionSink ? "sink" : "source",
            bluetooth::common::ToString(ase->data_path_state),
            bluetooth::common::ToString(ase->cis_state));

    if (ase->cis_state == CisState::IDLE || ase->cis_state == CisState::ASSIGNED) {
      return CIS_DISCONNECTED;
    }

    if (ase->cis_state == CisState::DISCONNECTING) {
      log::debug(" CIS is already disconnecting, nothing to do here.");
      return CIS_DISCONNECTING;
    }

    auto bidirection_ase = leAudioDevice->GetAseToMatchBidirectionCis(ase);
    if (bidirection_ase != nullptr && bidirection_ase->cis_state == CisState::CONNECTED &&
        (bidirection_ase->state == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING ||
         bidirection_ase->state == AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING)) {
      log::info("Still waiting for the bidirectional ase {} to be released ({})",
                bidirection_ase->id, bluetooth::common::ToString(bidirection_ase->state));
      return CIS_STILL_NEEDED;
    }

    ase->cis_state = CisState::DISCONNECTING;
    if (bidirection_ase) {
      bidirection_ase->cis_state = CisState::DISCONNECTING;
    }

    group->RemoveCisFromStreamIfNeeded(leAudioDevice, ase->cis_conn_hdl);
    IsoManager::GetInstance()->DisconnectCis(ase->cis_conn_hdl, HCI_ERR_PEER_USER);
    log_history_->AddLogHistory(kLogStateMachineTag, group->group_id_, leAudioDevice->address_,
                                kLogCisDisconnectOp + "cis_h:" + loghex(ase->cis_conn_hdl));
    return CIS_DISCONNECTING;
  }

  void AseStateMachineProcessReleasing(
          struct bluetooth::le_audio::client_parser::ascs::ase_rsp_hdr& /*arh*/, struct ase* ase,
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice) {
    if (!group) {
      log::error("leAudioDevice doesn't belong to any group");

      return;
    }

    switch (ase->state) {
      case AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING:
      case AseState::BTA_LE_AUDIO_ASE_STATE_CODEC_CONFIGURED:
      case AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

        if (group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)) {
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
          group->ClearStreamingMetadataContexts();
        }

        bool remove_cig = (DisconnectCisIfNeeded(group, leAudioDevice, ase) == CIS_DISCONNECTED);

        if (remove_cig && group->cig.GetState() == CigState::CREATED &&
            group->HaveAllCisesDisconnected() &&
            getDeviceTryingToAttachTheStream(group) == nullptr) {
          RemoveCigForGroup(group);
        }

        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

        bool remove_cig = (DisconnectCisIfNeeded(group, leAudioDevice, ase) == CIS_DISCONNECTED);

        if (!group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)) {
          return;
        }
        group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
        group->ClearStreamingMetadataContexts();

        if (remove_cig) {
          /* In the ENABLING state most probably there was no CISes created.
           * Make sure group is destroyed here */
          RemoveCigForGroup(group);
        }
        break;
      }
      case AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING: {
        SetAseState(leAudioDevice, ase, AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);

        /* Happens when bi-directional completive ASE releasing state came */
        if (ase->cis_state == CisState::DISCONNECTING) {
          break;
        }

        if (ase->data_path_state == DataPathState::CONFIGURED) {
          RemoveDataPathByCisHandle(leAudioDevice, ase->cis_conn_hdl);
        } else if ((ase->cis_state == CisState::CONNECTED ||
                    ase->cis_state == CisState::CONNECTING) &&
                   ase->data_path_state == DataPathState::IDLE) {
          DisconnectCisIfNeeded(group, leAudioDevice, ase);
        } else {
          log::debug("Nothing to do ase data path state: {}",
                     static_cast<int>(ase->data_path_state));
        }

        if (group->HaveAllActiveDevicesAsesTheSameState(
                    AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING)) {
          group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING);
          group->ClearStreamingMetadataContexts();
          if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
            log::info("Group {} is doing autonomous release", group->group_id_);
            SetTargetState(group, AseState::BTA_LE_AUDIO_ASE_STATE_IDLE);
            state_machine_callbacks_->StatusReportCb(group->group_id_,
                                                     GroupStreamStatus::RELEASING_AUTONOMOUS);
          }
        }

        break;
      }
      default:
        log::error("invalid state transition, from: {}, to: {}", static_cast<int>(ase->state),
                   static_cast<int>(AseState::BTA_LE_AUDIO_ASE_STATE_RELEASING));
        break;
    }
  }

  void ProcessGroupEnable(LeAudioDeviceGroup* group) {
    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING) {
      /* Check if the group is ready to create stream. If not, keep waiting. */
      if (!group->IsGroupReadyToCreateStream()) {
        log::debug("Waiting for more ASEs to be in enabling or directly in streaming state");
        return;
      }

      /* Group can move to Enabling state now. */
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_ENABLING);
    }

    /* If Target State is not streaming, then something is wrong. */
    if (group->GetTargetState() != AseState::BTA_LE_AUDIO_ASE_STATE_STREAMING) {
      log::error(", invalid state transition, from: {} , to: {}", ToString(group->GetState()),
                 ToString(group->GetTargetState()));
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
      return;
    }

    /* Try to create CISes for the group */
    if (!CisCreate(group)) {
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::FAILED_TO_CREATE_CIS);
    }
  }

  void ProcessGroupDisable(LeAudioDeviceGroup* group) {
    /* Disable ASEs for next device in group. */
    if (group->GetState() != AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING) {
      if (!group->IsGroupReadyToSuspendStream()) {
        log::info("Waiting for all devices to be in disable state");
        return;
      }
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_DISABLING);
    }

    /* At this point all of the active ASEs within group are disabled. As there
     * is no Disabling state for Sink ASE, it might happen that all of the
     * active ASEs are Sink ASE and will transit to QoS state. So check
     * the group state, because we might be ready to release data path. */
    if (group->HaveAllActiveDevicesAsesTheSameState(
                AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED)) {
      group->SetState(AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED);
    }

    /* Transition to QoS configured is done by CIS disconnection */
    if (group->GetTargetState() == AseState::BTA_LE_AUDIO_ASE_STATE_QOS_CONFIGURED) {
      ReleaseDataPath(group);
    } else {
      log::error(", invalid state transition, from: {} , to: {}", ToString(group->GetState()),
                 ToString(group->GetTargetState()));
      state_machine_callbacks_->OnStateMachineInvalidStatusCb(
              group->group_id_, StateMachineInvalidStatus::INVALID_ASE_STATE_TRANSITION);
    }
  }

  void ProcessAutonomousDisable(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                struct ase* ase) {
    /* If there is any streaming ASE and connected CIS, there is nothing to do.
     * Otherwise, Release all the ASEs.
     */
    log::info("{}, ase {}", leAudioDevice->address_, ase->id);

    if (group->HaveAnyActiveDeviceInStreamingState() && !group->HaveAllCisesDisconnected()) {
      log::info("There is still some ASE streaming, do nothing");
      return;
    }

    /* If there is no more ASEs streaming, just stop the stream */
    state_machine_callbacks_->OnStateMachineInvalidStatusCb(
            group->group_id_, StateMachineInvalidStatus::AUTONOMOUS_DISABLE);
  }
};
}  // namespace

namespace bluetooth::le_audio {
void LeAudioGroupStateMachine::Initialize(
        Callbacks* state_machine_callbacks_,
        bluetooth::hci::iso_manager::IsoClientHandle iso_client_handle) {
  if (instance) {
    log::error("Already initialized");
    return;
  }

  instance = new LeAudioGroupStateMachineImpl(state_machine_callbacks_, iso_client_handle);
}

void LeAudioGroupStateMachine::Cleanup() {
  if (!instance) {
    return;
  }

  LeAudioGroupStateMachineImpl* ptr = instance;
  instance = nullptr;

  delete ptr;
}

LeAudioGroupStateMachine* LeAudioGroupStateMachine::Get() {
  log::assert_that(instance != nullptr, "assert failed: instance != nullptr");
  return instance;
}
}  // namespace bluetooth::le_audio
