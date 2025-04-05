/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "hfp_client_interface.h"

#include <gtest/gtest.h>
#include <hardware/audio.h>
#include <log/log.h>

#include <cerrno>

#include "aidl/hfp_client_interface_aidl.h"
#include "aidl/transport_instance.h"
#include "audio_hal_interface/hal_version_manager.h"

#pragma GCC diagnostic ignored "-Wunused-private-field"

using testing::Test;

using bluetooth::audio::hfp::HfpClientInterface;

bool sink_client_read_called = false;
bool source_client_write_called = false;

namespace bluetooth::audio {
const BluetoothAudioHalVersion BluetoothAudioHalVersion::VERSION_AIDL_V4 =
        BluetoothAudioHalVersion(BluetoothAudioHalTransport::AIDL, 4, 0);

BluetoothAudioHalTransport HalVersionManager::GetHalTransport() {
  return BluetoothAudioHalTransport::AIDL;
}
BluetoothAudioHalVersion HalVersionManager::GetHalVersion() {
  return BluetoothAudioHalVersion::VERSION_AIDL_V4;
}

namespace aidl {
BluetoothAudioClientInterface::BluetoothAudioClientInterface(IBluetoothTransportInstance* instance)
    : provider_(nullptr),
      provider_factory_(nullptr),
      session_started_(false),
      data_mq_(nullptr),
      transport_(instance),
      latency_modes_({LatencyMode::FREE}) {}

BluetoothAudioSinkClientInterface::BluetoothAudioSinkClientInterface(
        IBluetoothSinkTransportInstance* sink)
    : BluetoothAudioClientInterface{sink}, sink_(sink) {}
BluetoothAudioSinkClientInterface::~BluetoothAudioSinkClientInterface() {}

size_t BluetoothAudioSinkClientInterface::ReadAudioData(uint8_t* /*p_buf*/, uint32_t len) {
  sink_client_read_called = true;
  return len;
}

BluetoothAudioSourceClientInterface::BluetoothAudioSourceClientInterface(
        IBluetoothSourceTransportInstance* source)
    : BluetoothAudioClientInterface{source}, source_(source) {}
BluetoothAudioSourceClientInterface::~BluetoothAudioSourceClientInterface() {}

size_t BluetoothAudioSourceClientInterface::WriteAudioData(const uint8_t* /*p_buf*/, uint32_t len) {
  source_client_write_called = true;
  return len;
}

bool BluetoothAudioClientInterface::IsValid() const { return true; }

bool BluetoothAudioClientInterface::SetAllowedLatencyModes(
        std::vector<LatencyMode> /*latency_modes*/) {
  return false;
}

void BluetoothAudioClientInterface::FlushAudioData() {}

bool BluetoothAudioClientInterface::UpdateAudioConfig(const AudioConfiguration& /*audio_config*/) {
  return false;
}

int BluetoothAudioClientInterface::StartSession() { return -EINVAL; }

void BluetoothAudioClientInterface::StreamStarted(const BluetoothAudioCtrlAck& /*ack*/) {}

int BluetoothAudioClientInterface::EndSession() { return -EINVAL; }

void BluetoothAudioClientInterface::StreamSuspended(const BluetoothAudioCtrlAck& /*ack*/) {}

std::vector<AudioCapabilities> BluetoothAudioClientInterface::GetAudioCapabilities(
        SessionType /*session_type*/) {
  return std::vector<AudioCapabilities>(0);
}

std::vector<IBluetoothAudioProvider::LeAudioAseConfigurationSetting>
BluetoothAudioClientInterface::GetLeAudioAseConfiguration(
        std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
        /*remoteSinkAudioCapabilities*/,
        std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
        /*remoteSourceAudioCapabilities*/,
        std::vector<IBluetoothAudioProvider::LeAudioConfigurationRequirement>& /*requirements*/) {
  return std::vector<IBluetoothAudioProvider::LeAudioAseConfigurationSetting>();
}

IBluetoothAudioProvider::LeAudioBroadcastConfigurationSetting
BluetoothAudioClientInterface::getLeAudioBroadcastConfiguration(
        const std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
        /*remoteSinkAudioCapabilities*/,
        const IBluetoothAudioProvider::LeAudioBroadcastConfigurationRequirement& /*requirement*/) {
  return IBluetoothAudioProvider::LeAudioBroadcastConfigurationSetting();
}

std::ostream& operator<<(std::ostream& os, const BluetoothAudioCtrlAck& /*ack*/) { return os; }

namespace hfp {

static bool encoding_transport_is_stream_active_ret;
static bool decoding_transport_is_stream_active_ret;

HfpTransport::HfpTransport() {}
BluetoothAudioCtrlAck HfpTransport::StartRequest() {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
void HfpTransport::StopRequest() {}
void HfpTransport::ResetPendingCmd() {}
uint8_t HfpTransport::GetPendingCmd() const { return HFP_CTRL_CMD_NONE; }
void HfpTransport::LogBytesProcessed(size_t /*bytes_read*/) {}
BluetoothAudioCtrlAck HfpTransport::SuspendRequest() {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
void HfpTransport::SetLatencyMode(LatencyMode /*latency_mode*/) {}
void HfpTransport::SourceMetadataChanged(const source_metadata_v7_t& /*source_metadata*/) {}
void HfpTransport::SinkMetadataChanged(const sink_metadata_v7_t&) {}
void HfpTransport::ResetPresentationPosition() {}
bool HfpTransport::GetPresentationPosition(uint64_t* /*remote_delay_report_ns*/,
                                           uint64_t* /*total_bytes_read*/,
                                           timespec* /*data_position*/) {
  return false;
}

std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config> HfpTransport::GetHfpScoConfig(
        SessionType /*sessionType*/) {
  return std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config>{};
}

// Source / sink functions
HfpDecodingTransport::HfpDecodingTransport(SessionType session_type)
    : IBluetoothSourceTransportInstance(session_type, (AudioConfiguration){}) {}

HfpDecodingTransport::~HfpDecodingTransport() {}
BluetoothAudioCtrlAck HfpDecodingTransport::StartRequest(bool /*is_low_latency*/) {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
BluetoothAudioCtrlAck HfpDecodingTransport::SuspendRequest() {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
void HfpDecodingTransport::SetLatencyMode(LatencyMode /*latency_mode*/) {}
bool HfpDecodingTransport::GetPresentationPosition(uint64_t* /*remote_delay_report_ns*/,
                                                   uint64_t* /*total_bytes_written*/,
                                                   timespec* /*data_position*/) {
  return false;
}
void HfpDecodingTransport::SourceMetadataChanged(const source_metadata_v7_t& /*source_metadata*/) {}
void HfpDecodingTransport::SinkMetadataChanged(const sink_metadata_v7_t& /*sink_metadata*/) {}
void HfpDecodingTransport::ResetPresentationPosition() {}
void HfpDecodingTransport::LogBytesWritten(size_t /*bytes_written*/) {}
uint8_t HfpDecodingTransport::GetPendingCmd() const { return HFP_CTRL_CMD_NONE; }
void HfpDecodingTransport::ResetPendingCmd() {}
void HfpDecodingTransport::StopRequest() {}
bool HfpDecodingTransport::IsStreamActive() { return decoding_transport_is_stream_active_ret; }

HfpEncodingTransport::HfpEncodingTransport(SessionType session_type)
    : IBluetoothSinkTransportInstance(session_type, (AudioConfiguration){}) {}
HfpEncodingTransport::~HfpEncodingTransport() {}
BluetoothAudioCtrlAck HfpEncodingTransport::StartRequest(bool /*is_low_latency*/) {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
BluetoothAudioCtrlAck HfpEncodingTransport::SuspendRequest() {
  return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
}
void HfpEncodingTransport::StopRequest() {}
void HfpEncodingTransport::SetLatencyMode(LatencyMode /*latency_mode*/) {}
bool HfpEncodingTransport::GetPresentationPosition(uint64_t* /*remote_delay_report_ns*/,
                                                   uint64_t* /*total_bytes_written*/,
                                                   timespec* /*data_position*/) {
  return false;
}

void HfpEncodingTransport::SourceMetadataChanged(const source_metadata_v7_t& /*source_metadata*/) {}
void HfpEncodingTransport::SinkMetadataChanged(const sink_metadata_v7_t& /*sink_metadata*/) {}
void HfpEncodingTransport::ResetPresentationPosition() {}
void HfpEncodingTransport::LogBytesRead(size_t /*bytes_written*/) {}
uint8_t HfpEncodingTransport::GetPendingCmd() const { return HFP_CTRL_CMD_NONE; }
void HfpEncodingTransport::ResetPendingCmd() {}
bool HfpEncodingTransport::IsStreamActive() { return encoding_transport_is_stream_active_ret; }

}  // namespace hfp
}  // namespace aidl
}  // namespace bluetooth::audio

namespace {

bluetooth::common::MessageLoopThread message_loop_thread("test message loop");
static base::MessageLoop* message_loop_;

static void init_message_loop_thread() {
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling()) {
    bluetooth::log::warn("Unable to set real time scheduling");
  }

  message_loop_ = message_loop_thread.message_loop();
  if (message_loop_ == nullptr) {
    FAIL() << "unable to get message loop.";
  }
}

static void cleanup_message_loop_thread() {
  message_loop_ = nullptr;
  message_loop_thread.ShutDown();
}

class HfpClientInterfaceTest : public Test {
protected:
  virtual void SetUp() override {
    init_message_loop_thread();
    sink_client_read_called = false;
    source_client_write_called = false;
    bluetooth::audio::aidl::hfp::encoding_transport_is_stream_active_ret = true;
    bluetooth::audio::aidl::hfp::decoding_transport_is_stream_active_ret = true;
  }

  virtual void TearDown() override { cleanup_message_loop_thread(); }
};

TEST_F(HfpClientInterfaceTest, InitEncodeInterfaceAndRead) {
  uint8_t data[48];
  HfpClientInterface::Encode* encode_ = nullptr;

  encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  encode_->Read(data, 48);
  ASSERT_EQ(1, sink_client_read_called);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, InitEncodeInterfaceAndReadWhenStreamInactive) {
  uint8_t data[48];
  data[0] = 0xab;

  HfpClientInterface::Encode* encode_ = nullptr;

  bluetooth::audio::aidl::hfp::encoding_transport_is_stream_active_ret = false;

  encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  encode_->Read(data, 48);
  ASSERT_EQ(0, sink_client_read_called);
  ASSERT_EQ(0x00, data[0]);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, InitDecodeInterfaceAndWrite) {
  uint8_t data[48];
  HfpClientInterface::Decode* decode_ = nullptr;

  decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  decode_->Write(data, 48);
  ASSERT_EQ(1, source_client_write_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, InitDecodeInterfaceAndWriteWhenStreamInactive) {
  uint8_t data[48];

  HfpClientInterface::Decode* decode_ = nullptr;

  bluetooth::audio::aidl::hfp::decoding_transport_is_stream_active_ret = false;

  decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  decode_->Write(data, 48);
  ASSERT_EQ(0, source_client_write_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}
}  // namespace
