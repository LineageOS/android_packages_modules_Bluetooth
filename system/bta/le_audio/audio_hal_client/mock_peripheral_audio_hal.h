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

#include "audio_hal_interface/le_audio_peripheral.h"

/*
 * Mocks for the new peripheral audio session interfaces
 */
class MockPeripheralAudioOut : public bluetooth::audio::le_audio::IPeripheralAudioOut {
public:
  MOCK_METHOD(size_t, Write, (const uint8_t*, uint32_t), (override));
  MOCK_METHOD(void, Start, (), (override));
  MOCK_METHOD(void, Stop, (), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD(void, ConfirmStreamingRequest, (), (override));
  MOCK_METHOD(void, CancelStreamingRequest, (), (override));
  MOCK_METHOD(::bluetooth::audio::le_audio::endpoint_config_rsp, RequestAseConfigurations,
              (const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
               const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>&
                       source_configs),
              (const override));
};

class MockPeripheralAudioIn : public bluetooth::audio::le_audio::IPeripheralAudioIn {
public:
  MOCK_METHOD(size_t, Read, (uint8_t*, uint32_t), (override));
  MOCK_METHOD(void, Start, (), (override));
  MOCK_METHOD(void, Stop, (), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const bluetooth::le_audio::stream_config&),
              (override));
  MOCK_METHOD(void, ConfirmStreamingRequest, (), (override));
  MOCK_METHOD(void, CancelStreamingRequest, (), (override));
  MOCK_METHOD(::bluetooth::audio::le_audio::endpoint_config_rsp, RequestAseConfigurations,
              (const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>& sink_configs,
               const std::vector<::bluetooth::audio::le_audio::endpoint_config_req>&
                       source_configs),
              (const override));
};

/*
 * Mock for the session factory to inject our mock sessions
 */
class MockPeripheralAudioSessionFactory
    : public bluetooth::audio::le_audio::IPeripheralAudioSessionFactory {
public:
  MOCK_METHOD((std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioOut>),
              AcquirePlaybackSession,
              (const bluetooth::audio::le_audio::PeripheralStreamCallbacks&,
               bluetooth::common::MessageLoopThread*),
              (override));
  MOCK_METHOD(void, ReleasePlaybackSession,
              (std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioOut>), (override));
  MOCK_METHOD((std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioIn>),
              AcquireRecordingSession,
              (const bluetooth::audio::le_audio::PeripheralStreamCallbacks&,
               bluetooth::common::MessageLoopThread*),
              (override));
  MOCK_METHOD(void, ReleaseRecordingSession,
              (std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioIn>), (override));
};

class MockPeripheralAudioProvider : public bluetooth::audio::le_audio::IPeripheralAudioProvider {
public:
  MOCK_METHOD(std::vector<bluetooth::audio::le_audio::AudioHalCapability>, GetProviderCapabilities,
              (), (override));
};

/*
 * Mock for the audio provider factory to inject our mock audio provider
 */
class MockPeripheralAudioProviderFactory
    : public bluetooth::audio::le_audio::IPeripheralAudioProviderFactory {
public:
  MOCK_METHOD(std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioProvider>,
                      GetPlaybackSessionAudioProvider, (), (override));
  MOCK_METHOD(std::unique_ptr<bluetooth::audio::le_audio::IPeripheralAudioProvider>,
                      GetRecordingSessionAudioProvider, (), (override));
};
