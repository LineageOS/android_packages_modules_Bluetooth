/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <gmock/gmock.h>

#include "state_machine.h"

class MockLeAudioGroupStateMachine : public bluetooth::le_audio::LeAudioGroupStateMachine {
public:
  MOCK_METHOD((bool), StartStream,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::types::LeAudioContextType context_type,
               const bluetooth::le_audio::types::BidirectionalPair<
                       bluetooth::le_audio::types::AudioContexts>& metadata_context_types,
               bluetooth::le_audio::types::BidirectionalPair<std::vector<uint8_t>> ccid_list),
              (override));
  MOCK_METHOD((bool), AttachToStream,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice,
               bluetooth::le_audio::types::BidirectionalPair<std::vector<uint8_t>> ccids),
              (override));
  MOCK_METHOD((void), SuspendStream, (bluetooth::le_audio::LeAudioDeviceGroup * group), (override));
  MOCK_METHOD((bool), ConfigureStream,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::types::LeAudioContextType context_type,
               const bluetooth::le_audio::types::BidirectionalPair<
                       bluetooth::le_audio::types::AudioContexts>& metadata_context_types,
               bluetooth::le_audio::types::BidirectionalPair<std::vector<uint8_t>> ccid_lists,
               bool configure_qos),
              (override));
  MOCK_METHOD((void), StopStream, (bluetooth::le_audio::LeAudioDeviceGroup * group), (override));
  MOCK_METHOD((void), ProcessGattNotifEvent,
              (uint8_t* value, uint16_t len, bluetooth::le_audio::types::ase* ase,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice,
               bluetooth::le_audio::LeAudioDeviceGroup* group),
              (override));
  MOCK_METHOD((void), ProcessGattCtpNotification,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice, uint8_t* value, uint16_t len),
              (override));
  MOCK_METHOD((void), ProcessHciNotifOnCigCreate,
              (bluetooth::le_audio::LeAudioDeviceGroup * group, uint8_t status, uint8_t cig_id,
               std::vector<uint16_t> conn_handles),
              (override));
  MOCK_METHOD((void), ProcessHciNotifOnCigRemove,
              (uint8_t status, bluetooth::le_audio::LeAudioDeviceGroup* group), (override));
  MOCK_METHOD((void), ProcessHciNotifCisEstablished,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice,
               const bluetooth::hci::iso_manager::cis_establish_cmpl_evt* event),
              (override));
  MOCK_METHOD((void), ProcessHciNotifCisDisconnected,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice,
               const bluetooth::hci::iso_manager::cis_disconnected_evt* event),
              (override));
  MOCK_METHOD((void), ProcessHciNotifSetupIsoDataPath,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice, uint8_t status,
               uint16_t conn_hdl),
              (override));
  MOCK_METHOD((void), ProcessHciNotifRemoveIsoDataPath,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice, uint8_t status,
               uint16_t conn_hdl),
              (override));
  MOCK_METHOD((void), Initialize,
              (bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks * state_machine_callbacks));
  MOCK_METHOD((void), Cleanup, ());
  MOCK_METHOD((void), ProcessHciNotifIsoLinkQualityRead,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice, uint8_t conn_handle,
               uint32_t txUnackedPackets, uint32_t txFlushedPackets, uint32_t txLastSubeventPackets,
               uint32_t retransmittedPackets, uint32_t crcErrorPackets,
               uint32_t rxUnreceivedPackets, uint32_t duplicatePackets),
              (override));
  MOCK_METHOD((void), ProcessHciNotifAclDisconnected,
              (bluetooth::le_audio::LeAudioDeviceGroup * group,
               bluetooth::le_audio::LeAudioDevice* leAudioDevice),
              (override));

  static void SetMockInstanceForTesting(MockLeAudioGroupStateMachine* machine);
};
