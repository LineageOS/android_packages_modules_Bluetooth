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

#include "peripheral_audio_hal_client.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "com_android_bluetooth_flags.h"
#include "common/message_loop_thread.h"
#include "mock_peripheral_audio_hal.h"

using namespace bluetooth::bta::le_audio;
namespace audio_le_audio = bluetooth::audio::le_audio;

using ::testing::_;
using ::testing::Return;

class PeripheralAudioHalClientTest : public ::testing::Test {
protected:
  void SetUp() override {
    message_loop_thread_ = std::make_unique<bluetooth::common::MessageLoopThread>(
            "test message loop", bluetooth::os::Thread::Priority::REAL_TIME);
    message_loop_thread_->StartUp();
    if (!message_loop_thread_->IsRunning()) {
      FAIL() << "unable to create message loop thread.";
    }

    if (!message_loop_thread_->EnableRealTimeScheduling()) {
      bluetooth::log::warn("Unable to set real time scheduling");
    }

    if (!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
      if (message_loop_thread_->message_loop() == nullptr) {
        FAIL() << "unable to get message loop.";
      }
    }

    // We own the unique_ptr's here. The factory will transfer ownership.
    mock_audio_out_unique_ptr = std::make_unique<MockPeripheralAudioOut>();
    mock_audio_in_unique_ptr = std::make_unique<MockPeripheralAudioIn>();
    mock_playback_provider_unique_ptr = std::make_unique<MockPeripheralAudioProvider>();
    mock_recording_provider_unique_ptr = std::make_unique<MockPeripheralAudioProvider>();
    // Keep raw pointers for setting expectations
    mock_audio_out_ = mock_audio_out_unique_ptr.get();
    mock_audio_in_ = mock_audio_in_unique_ptr.get();
    mock_playback_provider_ = mock_playback_provider_unique_ptr.get();
    mock_recording_provider_ = mock_recording_provider_unique_ptr.get();

    // Set the factory to return our mocks by moving the unique_ptr inside a
    // lambda. This is the correct way to handle ownership with gmock.
    ON_CALL(mock_factory_, AcquirePlaybackSession(_, _))
            .WillByDefault(
                    [this](auto&, auto*) -> std::unique_ptr<audio_le_audio::IPeripheralAudioOut> {
                      return std::move(mock_audio_out_unique_ptr);
                    });
    ON_CALL(mock_factory_, AcquireRecordingSession(_, _))
            .WillByDefault(
                    [this](auto&, auto*) -> std::unique_ptr<audio_le_audio::IPeripheralAudioIn> {
                      return std::move(mock_audio_in_unique_ptr);
                    });
  }

  void TearDown() override {
    message_loop_thread_->ShutDown();
    message_loop_thread_.reset();
    // If the unique_ptr was not moved, it will be deleted here.
    // If it was moved, this is a no-op.
    if (mock_audio_out_unique_ptr) {
      mock_audio_out_unique_ptr.reset();
    }
    if (mock_audio_in_unique_ptr) {
      mock_audio_in_unique_ptr.reset();
    }
    if (mock_playback_provider_unique_ptr) {
      mock_playback_provider_unique_ptr.reset();
    }
    if (mock_recording_provider_unique_ptr) {
      mock_recording_provider_unique_ptr.reset();
    }
  }

  MockPeripheralAudioSessionFactory mock_factory_;

  std::unique_ptr<MockPeripheralAudioOut> mock_audio_out_unique_ptr;
  MockPeripheralAudioOut* mock_audio_out_;
  std::unique_ptr<MockPeripheralAudioIn> mock_audio_in_unique_ptr;
  MockPeripheralAudioIn* mock_audio_in_;
  std::unique_ptr<MockPeripheralAudioProvider> mock_playback_provider_unique_ptr;
  MockPeripheralAudioProvider* mock_playback_provider_;
  std::unique_ptr<MockPeripheralAudioProvider> mock_recording_provider_unique_ptr;
  MockPeripheralAudioProvider* mock_recording_provider_;

  std::unique_ptr<bluetooth::common::MessageLoopThread> message_loop_thread_;
  audio_le_audio::PeripheralStreamCallbacks dummy_callbacks_{};
};

TEST_F(PeripheralAudioHalClientTest, Decoder_AcquiresAndForwards) {
  // Expect that the factory is called to acquire the session
  EXPECT_CALL(mock_factory_, AcquirePlaybackSession(_, message_loop_thread_.get()));
  PeripheralAudioHalDecoder decoder(dummy_callbacks_, message_loop_thread_.get(), &mock_factory_);

  // Expect that calls are forwarded to the session mock
  EXPECT_CALL(*mock_audio_out_, Start());
  decoder.Start();

  EXPECT_CALL(*mock_audio_out_, Stop());
  decoder.Stop();

  uint8_t buffer[] = {0x01, 0x02};
  EXPECT_CALL(*mock_audio_out_, Write(buffer, 2));
  decoder.Write(buffer, 2);
}

TEST_F(PeripheralAudioHalClientTest, Encoder_AcquiresAndForwards) {
  // Expect that the factory is called to acquire the session
  EXPECT_CALL(mock_factory_, AcquireRecordingSession(_, message_loop_thread_.get()));
  PeripheralAudioHalEncoder encoder(dummy_callbacks_, message_loop_thread_.get(), &mock_factory_);

  // Expect that calls are forwarded to the session mock
  EXPECT_CALL(*mock_audio_in_, ConfirmStreamingRequest());
  encoder.ConfirmStreamingRequest();

  uint8_t buffer[10] = {0};
  EXPECT_CALL(*mock_audio_in_, Read(buffer, 10));
  encoder.Read(buffer, 10);
}

TEST_F(PeripheralAudioHalClientTest, NoFactory_UsesDefault) {
  // This test verifies that the wrapper doesn't crash if no factory is
  // provided. It should fallback to the default implementation.
  // We can't test much further without a mock for the *real* default factory,
  // but this is a good sanity check.
  PeripheralAudioHalDecoder decoder(dummy_callbacks_, message_loop_thread_.get());
  decoder.Start();
}

TEST_F(PeripheralAudioHalClientTest, DecoderRemovingStreamUpdatesHal) {
  testing::InSequence s;

  // Expect factory call during construction of the decoder
  EXPECT_CALL(mock_factory_, AcquirePlaybackSession(_, message_loop_thread_.get()));
  PeripheralAudioHalDecoder decoder(dummy_callbacks_, message_loop_thread_.get(), &mock_factory_);
  ASSERT_NE(mock_audio_out_, nullptr);

  RawAddress test_address = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  const uint16_t cis_conn_handle = 0x0005;
  audio_channel_info channel_info;
  channel_info.address_with_type.bda = test_address;

  // Expect HAL update when adding a stream via ParametersChanged
  EXPECT_CALL(*mock_audio_out_, UpdateAudioConfigToHal(testing::Truly(
                                        [](const ::bluetooth::le_audio::stream_config& config) {
                                          return config.stream_map.size() == 1;
                                        })));

  decoder.OnAudioChannelParametersChanged(test_address, cis_conn_handle, channel_info);

  // Expect HAL update when removing the stream
  EXPECT_CALL(*mock_audio_out_, UpdateAudioConfigToHal(testing::Truly(
                                        [](const ::bluetooth::le_audio::stream_config& config) {
                                          return config.stream_map.empty();
                                        })));

  decoder.OnAudioChannelRemoved(test_address, cis_conn_handle);
}

TEST_F(PeripheralAudioHalClientTest, EncoderRemovingStreamUpdatesHal) {
  testing::InSequence s;

  EXPECT_CALL(mock_factory_, AcquireRecordingSession(_, message_loop_thread_.get()));
  PeripheralAudioHalEncoder encoder(dummy_callbacks_, message_loop_thread_.get(), &mock_factory_);
  ASSERT_NE(mock_audio_in_, nullptr);

  RawAddress test_address = std::array<uint8_t, 6>{0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
  const uint16_t cis_conn_handle = 0x0005;
  audio_channel_info channel_info;
  channel_info.address_with_type.bda = test_address;

  // Expect HAL update when adding a stream
  EXPECT_CALL(*mock_audio_in_, UpdateAudioConfigToHal(testing::Truly(
                                       [](const ::bluetooth::le_audio::stream_config& config) {
                                         return config.stream_map.size() == 1;
                                       })));
  encoder.OnAudioChannelParametersChanged(test_address, cis_conn_handle, channel_info);

  // Expect HAL update when removing the stream
  EXPECT_CALL(*mock_audio_in_, UpdateAudioConfigToHal(testing::Truly(
                                       [](const ::bluetooth::le_audio::stream_config& config) {
                                         return config.stream_map.empty();
                                       })));

  encoder.OnAudioChannelRemoved(test_address, cis_conn_handle);
}
