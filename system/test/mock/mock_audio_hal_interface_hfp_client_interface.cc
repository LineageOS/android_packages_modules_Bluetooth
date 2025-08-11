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

#include "mock_audio_hal_interface_hfp_client_interface.h"

#include <bluetooth/log.h>
#include <gmock/gmock.h>

#include "audio_hal_interface/hfp_client_interface.h"
#include "common/message_loop_thread.h"

namespace bluetooth::audio::hfp {

namespace testing::mock_hfp_client_interface {
std::unique_ptr<MockDecode> mock_decode_;
std::unique_ptr<MockEncode> mock_encode_;
std::unique_ptr<MockOffload> mock_offload_;
}  // namespace testing::mock_hfp_client_interface

HfpClientInterface::Decode* HfpClientInterface::GetDecode(bluetooth::common::MessageLoopThread*) {
  decode_ = testing::mock_hfp_client_interface::mock_decode_.get();
  return decode_;
}

bool HfpClientInterface::ReleaseDecode(HfpClientInterface::Decode*) {
  decode_ = nullptr;
  return true;
}

HfpClientInterface::Encode* HfpClientInterface::GetEncode(bluetooth::common::MessageLoopThread*) {
  encode_ = testing::mock_hfp_client_interface::mock_encode_.get();
  return encode_;
}

bool HfpClientInterface::ReleaseEncode(HfpClientInterface::Encode*) {
  encode_ = nullptr;
  return true;
}

HfpClientInterface::Offload* HfpClientInterface::GetOffload(bluetooth::common::MessageLoopThread*) {
  offload_ = testing::mock_hfp_client_interface::mock_offload_.get();
  return offload_;
}

bool HfpClientInterface::ReleaseOffload(HfpClientInterface::Offload*) {
  offload_ = nullptr;
  return true;
}

HfpClientInterface* HfpClientInterface::interface = nullptr;
HfpClientInterface* HfpClientInterface::Get() {
  if (HfpClientInterface::interface == nullptr) {
    HfpClientInterface::interface = new HfpClientInterface();
  }
  return HfpClientInterface::interface;
}

// Decode / Encode / Offload definition. Not used when mock.

void HfpClientInterface::Decode::Cleanup() {}
void HfpClientInterface::Decode::StartSession() {}
void HfpClientInterface::Decode::StopSession() {}
void HfpClientInterface::Decode::UpdateAudioConfigToHal(const ::hfp::offload_config&) {}
void HfpClientInterface::Decode::UpdateAudioConfigToHal(const ::hfp::pcm_config&) {}
void HfpClientInterface::Decode::ConfirmStreamingRequest() {}
void HfpClientInterface::Decode::CancelStreamingRequest() {}
size_t HfpClientInterface::Decode::Write(const uint8_t*, uint32_t) { return 0; }

void HfpClientInterface::Encode::Cleanup() {}
void HfpClientInterface::Encode::StartSession() {}
void HfpClientInterface::Encode::StopSession() {}
void HfpClientInterface::Encode::UpdateAudioConfigToHal(const ::hfp::offload_config&) {}
void HfpClientInterface::Encode::UpdateAudioConfigToHal(const ::hfp::pcm_config&) {}
void HfpClientInterface::Encode::ConfirmStreamingRequest() {}
void HfpClientInterface::Encode::CancelStreamingRequest() {}
size_t HfpClientInterface::Encode::Read(uint8_t*, uint32_t) { return 0; }

void HfpClientInterface::Offload::Cleanup() {}
void HfpClientInterface::Offload::StartSession() {}
void HfpClientInterface::Offload::StopSession() {}
void HfpClientInterface::Offload::UpdateAudioConfigToHal(const ::hfp::offload_config&) {}
void HfpClientInterface::Offload::UpdateAudioConfigToHal(const ::hfp::pcm_config&) {}
void HfpClientInterface::Offload::ConfirmStreamingRequest() {}
void HfpClientInterface::Offload::CancelStreamingRequest() {}
std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>
HfpClientInterface::Offload::GetHfpScoConfig() {
  return {};
}

}  // namespace bluetooth::audio::hfp
