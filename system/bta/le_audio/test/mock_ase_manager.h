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

#include <gmock/gmock.h>

#include "bta/le_audio/ascs/ase_manager.h"

namespace bluetooth::le_audio {

class MockAseManager : public AseManager {
public:
  explicit MockAseManager(std::shared_ptr<Ascs> ascs, AscsAseStateMachineFactory sm_factory,
                          IsoAppProxyFactory iso_app_factory)
      : AseManager(ascs, std::move(sm_factory), std::move(iso_app_factory)) {}
  MockAseManager(const MockAseManager&) = delete;
  MockAseManager& operator=(const MockAseManager&) = delete;

  MOCK_METHOD(void, Dump, (std::stringstream& stream), (const, override));
  MOCK_METHOD(void, Initialize, (const Ascs::ServiceDescriptor& desc, Callbacks* callbacks),
              (override));
  MOCK_METHOD(bool, IsKnownPeerDevice, (const RawAddress& pseudo_address), (const, override));
  MOCK_METHOD(std::set<RawAddress>, GetNonIdlePeerDevices, (), (const, override));
  MOCK_METHOD(bool, IsActiveSinkStream, (const RawAddress& pseudo_address), (const, override));
  MOCK_METHOD(bool, IsActiveSourceStream, (const RawAddress& pseudo_address), (const, override));
  MOCK_METHOD(bool, IsSinkAse, (uint8_t ase_id), (const, override));
  MOCK_METHOD(bool, IsSourceAse, (uint8_t ase_id), (const, override));
  MOCK_METHOD(void, OnDecodingSessionReady, (const RawAddress& pseudo_address), (override));
  MOCK_METHOD(void, OnEncodingSessionReady, (const RawAddress& pseudo_address), (override));
  MOCK_METHOD(bool, ConsumeAudioData,
              (const RawAddress& pseudo_address, uint8_t ase_id, uint8_t* data, uint16_t size),
              (override));
  MOCK_METHOD(void, ConfirmAseEnableRequest, (const RawAddress& peer_address, bool allowed),
              (override));
  MOCK_METHOD(void, ReleaseAse, (const RawAddress& peer_address, uint8_t ase_id), (override));
};

}  // namespace bluetooth::le_audio
