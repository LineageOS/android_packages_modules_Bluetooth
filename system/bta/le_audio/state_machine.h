/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
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

#pragma once

#include <vector>

#include "device_groups.h"
#include "devices.h"
#include "hardware/bt_le_audio.h"
#include "le_audio_types.h"
#include "stack/include/btm_iso_api.h"
#include "stack/include/btm_iso_api_types.h"

namespace bluetooth::le_audio {
#define CASE_SET_PTR_TO_TOKEN_STR(nm, en) \
  case (nm::en):                          \
    ch = #en;                             \
    break

enum class StateMachineInvalidStatus {
  AUTONOMOUS_DISABLE,
  FAILED_TO_CREATE_CIG,
  FAILED_TO_CREATE_CIS,
  FAILED_TO_REMOVE_CIG,
  FAILED_TO_SETUP_ISO_DATA_PATH,
  INVALID_ASE_STATE,
  INVALID_ASE_STATE_PARAMETERS,
  INVALID_ASE_STATE_TRANSITION,
  INVALID_CIS_ESTABLISHED_EVENT,
  UNABLE_TO_ASSIGN_CISES,
  INVALID_DEVICE_CONFIGURATION,
};

inline std::ostream& operator<<(std::ostream& out, const StateMachineInvalidStatus value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, AUTONOMOUS_DISABLE);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, FAILED_TO_CREATE_CIG);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, FAILED_TO_CREATE_CIS);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, FAILED_TO_REMOVE_CIG);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, FAILED_TO_SETUP_ISO_DATA_PATH);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, INVALID_ASE_STATE);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, INVALID_ASE_STATE_PARAMETERS);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, INVALID_ASE_STATE_TRANSITION);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, INVALID_CIS_ESTABLISHED_EVENT);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, UNABLE_TO_ASSIGN_CISES);
    CASE_SET_PTR_TO_TOKEN_STR(StateMachineInvalidStatus, INVALID_DEVICE_CONFIGURATION);
    default:
      ch = "Invalid status code";
      break;
  }
  return out << ch;
}

/* State machine interface */
class LeAudioGroupStateMachine {
public:
  class Callbacks {
  public:
    virtual ~Callbacks() = default;

    virtual void StatusReportCb(int group_id, bluetooth::le_audio::GroupStreamStatus status) = 0;
    virtual void OnStateTransitionTimeout(int group_id) = 0;
    virtual void OnUpdatedCisConfiguration(int group_id, uint8_t direction) = 0;
    virtual uint8_t OnGetEnabledDirections(int group_id) = 0;
    virtual void OnStateMachineInvalidStatusCb(int group_id,
                                               StateMachineInvalidStatus invalid_state) = 0;
  };

  virtual ~LeAudioGroupStateMachine() = default;

  static void Initialize(Callbacks* state_machine_callbacks,
                         bluetooth::hci::iso_manager::IsoClientHandle iso_client_handle);
  static void Cleanup(void);
  static LeAudioGroupStateMachine* Get(void);

  virtual bool AttachToStream(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                              types::BidirectionalPair<std::vector<uint8_t>> ccids) = 0;
  virtual bool StartStream(
          LeAudioDeviceGroup* group, types::LeAudioContextType context_type,
          const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
          types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {},
                                                                       .source = {}}) = 0;
  virtual void SuspendStream(LeAudioDeviceGroup* group) = 0;
  virtual bool ConfigureStream(
          LeAudioDeviceGroup* group, types::LeAudioContextType context_type,
          const types::BidirectionalPair<types::AudioContexts>& metadata_context_types,
          types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {.sink = {}, .source = {}},
          bool configure_qos = false) = 0;
  virtual bool EnableStreamingDirection(LeAudioDeviceGroup* group, uint8_t remote_direction) = 0;
  virtual bool DisableStreamingDirection(LeAudioDeviceGroup* group, uint8_t remote_direction) = 0;
  virtual void StopStream(LeAudioDeviceGroup* group) = 0;
  virtual void ProcessGattCtpNotification(LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
                                          uint8_t* value, uint16_t len) = 0;
  virtual void ProcessGattNotifEvent(uint8_t* value, uint16_t len, struct types::ase* ase,
                                     LeAudioDevice* leAudioDevice, LeAudioDeviceGroup* group) = 0;

  virtual void ProcessHciNotifOnCigCreate(LeAudioDeviceGroup* group, uint8_t status, uint8_t cig_id,
                                          std::vector<uint16_t> conn_handles) = 0;
  virtual void ProcessHciNotifOnCigRemove(uint8_t status, LeAudioDeviceGroup* group) = 0;
  virtual void ProcessHciNotifCisEstablished(
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
          const bluetooth::hci::iso_manager::cis_establish_cmpl_evt* event) = 0;
  virtual void ProcessHciNotifCisDisconnected(
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice,
          const bluetooth::hci::iso_manager::cis_disconnected_evt* event) = 0;
  virtual void ProcessHciNotifSetupIsoDataPath(LeAudioDeviceGroup* group,
                                               LeAudioDevice* leAudioDevice, uint8_t status,
                                               uint16_t conn_hdl) = 0;
  virtual void ProcessHciNotifRemoveIsoDataPath(LeAudioDeviceGroup* group,
                                                LeAudioDevice* leAudioDevice, uint8_t status,
                                                uint16_t conn_hdl) = 0;
  virtual void ProcessHciNotifIsoLinkQualityRead(
          LeAudioDeviceGroup* group, LeAudioDevice* leAudioDevice, uint16_t conn_handle,
          uint32_t tx_unacked_packets, uint32_t tx_flushed_packets,
          uint32_t tx_last_subevent_packets, uint32_t retransmitted_packets,
          uint32_t crc_error_packets, uint32_t rx_unreceived_packets,
          uint32_t duplicate_packets) = 0;
  virtual void ProcessHciNotifAclDisconnected(LeAudioDeviceGroup* group,
                                              LeAudioDevice* leAudioDevice) = 0;
};
}  // namespace bluetooth::le_audio

namespace std {
template <>
struct formatter<bluetooth::le_audio::StateMachineInvalidStatus> : ostream_formatter {};
}  // namespace std
