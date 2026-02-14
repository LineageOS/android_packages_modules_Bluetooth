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

#define LOG_TAG "TestA2dpTransport"

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "a2dp_encoding.h"
#include "audio_hal_interface/aidl/a2dp/a2dp_aidl_transport.h"
#include "audio_hal_interface/aidl/a2dp/client_interface_aidl.h"
#include "stack/include/a2dp_constants.h"

using bluetooth::audio::a2dp::Status;
using ::testing::_;
using ::testing::Eq;
using ::testing::Return;
using ::testing::Test;

namespace {
class MockStreamCallbacks : public bluetooth::audio::a2dp::StreamCallbacks {
public:
  // clang-format off
  MOCK_METHOD((Status), StartStream, (bool /*low_latency*/), (const));
  MOCK_METHOD((Status), SuspendStream, (), (const));
  MOCK_METHOD((Status), StopStream, (), (const));
  MOCK_METHOD((Status), SetLatencyMode, (bool /*low_latency*/), (const));
  MOCK_METHOD((Status), SourceMetadataChanged,
              (btav_a2dp_codec_audio_context_t /*audio_context*/),
              (const));
  // clang-format on
};

class A2dpAidlTransportTest : public Test {
public:
  bluetooth::audio::aidl::a2dp::A2dpTransport* software_transport_aidl;
  MockStreamCallbacks stream_callbacks_aidl;

  void SetUp() override {
    software_transport_aidl = new bluetooth::audio::aidl::a2dp::A2dpTransport(
            ::aidl::android::hardware::bluetooth::audio::SessionType::
                    A2DP_SOFTWARE_ENCODING_DATAPATH,
            &stream_callbacks_aidl);

    ASSERT_NE(software_transport_aidl, nullptr);
  }

  void TearDown() override {
    delete software_transport_aidl;
    software_transport_aidl = nullptr;
  }
};
}  // namespace

//=============================================================================
// A2dpAidlTransportTest
//=============================================================================

TEST_F(A2dpAidlTransportTest, UpdateAudioConfiguration) {
  auto audio_config = ::aidl::android::hardware::bluetooth::audio::AudioConfiguration();
  software_transport_aidl->UpdateAudioConfiguration(audio_config);
  ASSERT_EQ(software_transport_aidl->GetAudioConfiguration(), audio_config);
}

TEST_F(A2dpAidlTransportTest, UpdateAudioConfiguration_UnsupportedConfig) {
  // Store the initial audio configuration to compare against later.
  auto initial_config = software_transport_aidl->GetAudioConfiguration();

  // Create an audio configuration with an unsupported tag.
  // `hfpConfig` is used here as an example of a valid but unsupported config.
  auto unsupported_config =
      ::aidl::android::hardware::bluetooth::audio::AudioConfiguration();
  unsupported_config.set<
      ::aidl::android::hardware::bluetooth::audio::AudioConfiguration::hfpConfig>(
      ::aidl::android::hardware::bluetooth::audio::HfpConfiguration{});

  // Attempt to update with the unsupported configuration.
  software_transport_aidl->UpdateAudioConfiguration(unsupported_config);

  // Verify that the transport's audio configuration remains unchanged.
  ASSERT_EQ(software_transport_aidl->GetAudioConfiguration(), initial_config);
}

TEST_F(A2dpAidlTransportTest, AssertCorrectSession) {
  ASSERT_NE(software_transport_aidl, nullptr);
  ASSERT_EQ(software_transport_aidl->GetSessionType(),
            ::aidl::android::hardware::bluetooth::audio::SessionType::
                    A2DP_SOFTWARE_ENCODING_DATAPATH);
}

TEST_F(A2dpAidlTransportTest, StartRequest_Success) {
  EXPECT_CALL(stream_callbacks_aidl, StartStream(Eq(false)))
          .Times(1)
          .WillOnce(Return(Status::SUCCESS));
  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::SUCCESS);
}

TEST_F(A2dpAidlTransportTest, StartRequest_AlreadyPending) {
  EXPECT_CALL(stream_callbacks_aidl, StartStream(Eq(false)))
          .Times(1)
          .WillOnce(Return(Status::PENDING));

  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::PENDING);
  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::PENDING);
}

TEST_F(A2dpAidlTransportTest, StartRequest_OtherPendingCommand) {
  EXPECT_CALL(stream_callbacks_aidl, SuspendStream()).Times(1).WillOnce(Return(Status::PENDING));

  ASSERT_EQ(software_transport_aidl->SuspendRequest(), Status::PENDING);
  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::FAILURE);
}

TEST_F(A2dpAidlTransportTest, SuspendRequest_Success) {
  EXPECT_CALL(stream_callbacks_aidl, SuspendStream()).Times(1).WillOnce(Return(Status::SUCCESS));
  ASSERT_EQ(software_transport_aidl->SuspendRequest(), Status::SUCCESS);
}

TEST_F(A2dpAidlTransportTest, SuspendRequest_AlreadyPending) {
  EXPECT_CALL(stream_callbacks_aidl, SuspendStream()).Times(1).WillOnce(Return(Status::PENDING));

  ASSERT_EQ(software_transport_aidl->SuspendRequest(), Status::PENDING);
  ASSERT_EQ(software_transport_aidl->SuspendRequest(), Status::PENDING);
}

TEST_F(A2dpAidlTransportTest, SuspendRequest_OtherPendingCommand) {
  EXPECT_CALL(stream_callbacks_aidl, StartStream(Eq(false)))
          .Times(1)
          .WillOnce(Return(Status::PENDING));

  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::PENDING);
  ASSERT_EQ(software_transport_aidl->SuspendRequest(), Status::FAILURE);
}

TEST_F(A2dpAidlTransportTest, StopRequest_Success) {
  EXPECT_CALL(stream_callbacks_aidl, StopStream()).Times(1).WillOnce(Return(Status::SUCCESS));
  software_transport_aidl->StopRequest();
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_NONE);
}

TEST_F(A2dpAidlTransportTest, StopRequest_AlreadyPending) {
  EXPECT_CALL(stream_callbacks_aidl, StopStream()).Times(2).WillRepeatedly(Return(Status::PENDING));

  software_transport_aidl->StopRequest();
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_STOP);
  software_transport_aidl->StopRequest();
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_STOP);
}

TEST_F(A2dpAidlTransportTest, StopRequest_OtherPendingCommand) {
  EXPECT_CALL(stream_callbacks_aidl, StartStream(Eq(false)))
          .Times(1)
          .WillOnce(Return(Status::PENDING));
  EXPECT_CALL(stream_callbacks_aidl, StopStream()).Times(1).WillOnce(Return(Status::SUCCESS));

  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::PENDING);
  software_transport_aidl->StopRequest();
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_NONE);
}

TEST_F(A2dpAidlTransportTest, SetLatencyMode) {
  EXPECT_CALL(stream_callbacks_aidl, SetLatencyMode(Eq(true))).Times(1);
  software_transport_aidl->SetLatencyMode(
          ::aidl::android::hardware::bluetooth::audio::LatencyMode::LOW_LATENCY);
}

TEST_F(A2dpAidlTransportTest, SourceMetadataChanged) {
  const btav_a2dp_codec_audio_context_t audio_context = {};
  EXPECT_CALL(stream_callbacks_aidl, SourceMetadataChanged(Eq(audio_context))).Times(1);
  software_transport_aidl->SourceMetadataChanged(audio_context);
}

TEST_F(A2dpAidlTransportTest, ResetPendingCmd) {
  EXPECT_CALL(stream_callbacks_aidl, StartStream(Eq(false)))
          .Times(1)
          .WillOnce(Return(Status::PENDING));

  ASSERT_EQ(software_transport_aidl->StartRequest(false), Status::PENDING);
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_START);

  software_transport_aidl->ResetPendingCmd();
  ASSERT_EQ(software_transport_aidl->GetPendingCmd(),
            bluetooth::audio::aidl::a2dp::A2DP_CTRL_CMD_NONE);
}
