/*
 * Copyright 2025 The Android Open Source Project
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

#include <gmock/gmock.h>

#include "audio_hal_interface/hfp_client_interface.h"
#include "common/message_loop_thread.h"

namespace bluetooth::audio::hfp::testing::mock_hfp_client_interface {

class MockDecode : public HfpClientInterface::Decode {
public:
  MOCK_METHOD(void, Cleanup, (), (override));
  MOCK_METHOD(void, StartSession, (), (override));
  MOCK_METHOD(void, StopSession, (), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::offload_config& config), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::pcm_config& config), (override));
  MOCK_METHOD(void, ConfirmStreamingRequest, (), (override));
  MOCK_METHOD(void, CancelStreamingRequest, (), (override));
  MOCK_METHOD(size_t, Write, (const uint8_t* p_buf, uint32_t len), (override));
};

class MockEncode : public HfpClientInterface::Encode {
public:
  MOCK_METHOD(void, Cleanup, (), (override));
  MOCK_METHOD(void, StartSession, (), (override));
  MOCK_METHOD(void, StopSession, (), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::offload_config& config), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::pcm_config& config), (override));
  MOCK_METHOD(void, ConfirmStreamingRequest, (), (override));
  MOCK_METHOD(void, CancelStreamingRequest, (), (override));
  MOCK_METHOD(size_t, Read, (uint8_t* p_buf, uint32_t len), (override));
};

class MockOffload : public HfpClientInterface::Offload {
public:
  MOCK_METHOD(void, Cleanup, (), (override));
  MOCK_METHOD(void, StartSession, (), (override));
  MOCK_METHOD(void, StopSession, (), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::offload_config& config), (override));
  MOCK_METHOD(void, UpdateAudioConfigToHal, (const ::hfp::pcm_config& config), (override));
  MOCK_METHOD(void, ConfirmStreamingRequest, (), (override));
  MOCK_METHOD(void, CancelStreamingRequest, (), (override));
  MOCK_METHOD((std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>), GetHfpScoConfig, (),
              (override));
};

extern std::unique_ptr<MockDecode> mock_decode_;
extern std::unique_ptr<MockEncode> mock_encode_;
extern std::unique_ptr<MockOffload> mock_offload_;

}  // namespace bluetooth::audio::hfp::testing::mock_hfp_client_interface
