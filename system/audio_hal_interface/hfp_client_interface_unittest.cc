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
#include "com_android_bluetooth_flags.h"

#pragma GCC diagnostic ignored "-Wunused-private-field"

using testing::Test;

using bluetooth::audio::hfp::HfpClientInterface;

bool sink_client_read_called = false;
bool source_client_write_called = false;
bool stream_started_called = false;
bool stream_suspended_called = false;
bluetooth::audio::aidl::BluetoothAudioCtrlAck stream_started_ack;
bool update_audio_config_called = false;
bool start_session_called = false;
bool end_session_called = false;

namespace bluetooth::audio {
namespace {
BluetoothAudioHalTransport hal_transport_ = BluetoothAudioHalTransport::AIDL;
BluetoothAudioHalVersion hal_version_ = BluetoothAudioHalVersion::VERSION_AIDL_V4;
}  // namespace

const BluetoothAudioHalVersion BluetoothAudioHalVersion::VERSION_AIDL_V4 =
        BluetoothAudioHalVersion(BluetoothAudioHalTransport::AIDL, 4, 0);

BluetoothAudioHalTransport HalVersionManager::GetHalTransport() {
  return bluetooth::audio::hal_transport_;
}
BluetoothAudioHalVersion HalVersionManager::GetHalVersion() {
  return bluetooth::audio::hal_version_;
}

namespace aidl {
BluetoothAudioClientInterface::BluetoothAudioClientInterface(
        IBluetoothTransportInstance* instance, common::MessageLoopThread* message_loop)
    : death_handler_thread_(message_loop),
      provider_(nullptr),
      provider_factory_(nullptr),
      session_started_(false),
      data_mq_(nullptr),
      transport_(instance),
      latency_modes_({LatencyMode::FREE}) {}

BluetoothAudioSinkClientInterface::BluetoothAudioSinkClientInterface(
        IBluetoothSinkTransportInstance* sink, common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{sink, message_loop}, sink_(sink) {}
BluetoothAudioSinkClientInterface::~BluetoothAudioSinkClientInterface() {}

size_t BluetoothAudioSinkClientInterface::ReadAudioData(uint8_t* /*p_buf*/, uint32_t len) {
  sink_client_read_called = true;
  return len;
}

BluetoothAudioSourceClientInterface::BluetoothAudioSourceClientInterface(
        IBluetoothSourceTransportInstance* source, common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{source, message_loop}, source_(source) {}
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
  update_audio_config_called = true;
  return true;
}

int BluetoothAudioClientInterface::StartSession() {
  start_session_called = true;
  return 0;
}

void BluetoothAudioClientInterface::StreamStarted(const BluetoothAudioCtrlAck& ack) {
  stream_started_called = true;
  stream_started_ack = ack;
}

int BluetoothAudioClientInterface::EndSession() {
  end_session_called = true;
  return 0;
}

void BluetoothAudioClientInterface::StreamSuspended(const BluetoothAudioCtrlAck& /*ack*/) {
  stream_suspended_called = true;
}

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
static uint8_t hfp_encoding_transport_pending_cmd = HFP_CTRL_CMD_NONE;
static uint8_t hfp_decoding_transport_pending_cmd = HFP_CTRL_CMD_NONE;

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
uint8_t HfpDecodingTransport::GetPendingCmd() const { return hfp_decoding_transport_pending_cmd; }
void HfpDecodingTransport::ResetPendingCmd() {
  hfp_decoding_transport_pending_cmd = HFP_CTRL_CMD_NONE;
}
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
uint8_t HfpEncodingTransport::GetPendingCmd() const { return hfp_encoding_transport_pending_cmd; }
void HfpEncodingTransport::ResetPendingCmd() {
  hfp_encoding_transport_pending_cmd = HFP_CTRL_CMD_NONE;
}
bool HfpEncodingTransport::IsStreamActive() { return encoding_transport_is_stream_active_ret; }

}  // namespace hfp
}  // namespace aidl
}  // namespace bluetooth::audio

namespace {

bluetooth::common::MessageLoopThread message_loop_thread(
        "test message loop", bluetooth::os::Thread::Priority::REAL_TIME);

static void init_message_loop_thread() {
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling()) {
    bluetooth::log::warn("Unable to set real time scheduling");
  }

  if (!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    if (message_loop_thread.message_loop() == nullptr) {
      FAIL() << "unable to get message loop.";
    }
  }
}

static void cleanup_message_loop_thread() { message_loop_thread.ShutDown(); }

void SetHalVersion(bluetooth::audio::BluetoothAudioHalTransport transport, int major, int minor) {
  bluetooth::audio::hal_transport_ = transport;
  bluetooth::audio::hal_version_ =
          bluetooth::audio::BluetoothAudioHalVersion(transport, major, minor);
}

void SetEncodingPendingCmd(uint8_t cmd) {
  bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd = cmd;
}

void SetDecodingPendingCmd(uint8_t cmd) {
  bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd = cmd;
}

class HfpClientInterfaceTest : public Test {
protected:
  virtual void SetUp() override {
    init_message_loop_thread();
    sink_client_read_called = false;
    source_client_write_called = false;
    stream_started_called = false;
    stream_suspended_called = false;
    update_audio_config_called = false;
    start_session_called = false;
    end_session_called = false;
    bluetooth::audio::aidl::hfp::encoding_transport_is_stream_active_ret = true;
    bluetooth::audio::aidl::hfp::decoding_transport_is_stream_active_ret = true;
    SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
    SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
    SetHalVersion(bluetooth::audio::BluetoothAudioHalTransport::AIDL, 4, 0);
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

TEST_F(HfpClientInterfaceTest, GetEncodeTwice) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);
  ASSERT_EQ(nullptr, HfpClientInterface::Get()->GetEncode(&message_loop_thread));
  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, ReleaseWrongEncode) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);
  auto fake_encode = new HfpClientInterface::Encode();
  ASSERT_FALSE(HfpClientInterface::Get()->ReleaseEncode(fake_encode));
  delete fake_encode;
  ASSERT_TRUE(HfpClientInterface::Get()->ReleaseEncode(encode_));
}

TEST_F(HfpClientInterfaceTest, GetDecodeTwice) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);
  ASSERT_EQ(nullptr, HfpClientInterface::Get()->GetDecode(&message_loop_thread));
  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, ReleaseWrongDecode) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);
  auto fake_decode = new HfpClientInterface::Decode();
  ASSERT_FALSE(HfpClientInterface::Get()->ReleaseDecode(fake_decode));
  delete fake_decode;
  ASSERT_TRUE(HfpClientInterface::Get()->ReleaseDecode(decode_));
}

TEST_F(HfpClientInterfaceTest, GetOffloadTwice) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);
  ASSERT_EQ(nullptr, HfpClientInterface::Get()->GetOffload(&message_loop_thread));
  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, ReleaseWrongOffload) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);
  auto fake_offload = new HfpClientInterface::Offload();
  ASSERT_FALSE(HfpClientInterface::Get()->ReleaseOffload(fake_offload));
  delete fake_offload;
  ASSERT_TRUE(HfpClientInterface::Get()->ReleaseOffload(offload_));
}

TEST_F(HfpClientInterfaceTest, EncodeStartSession) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  encode_->StartSession();
  ASSERT_TRUE(update_audio_config_called);
  ASSERT_TRUE(start_session_called);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, EncodeStopSession) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  encode_->StopSession();
  ASSERT_TRUE(end_session_called);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, EncodeUpdateAudioConfigToHalPcm) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  ::hfp::pcm_config pcm_config = {.sample_rate_hz = 16000};
  encode_->UpdateAudioConfigToHal(pcm_config);
  ASSERT_TRUE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, EncodeUpdateAudioConfigToHalOffload) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  ::hfp::offload_config offload_config = {};
  encode_->UpdateAudioConfigToHal(offload_config);
  // Should do nothing and log a warning.
  ASSERT_FALSE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, EncodeConfirmStreamingRequest) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  encode_->ConfirmStreamingRequest();
  ASSERT_FALSE(stream_started_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  stream_started_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  encode_->ConfirmStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, EncodeCancelStreamingRequest) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  encode_->CancelStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(stream_started_ack, bluetooth::audio::aidl::BluetoothAudioCtrlAck::FAILURE);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  stream_started_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  encode_->CancelStreamingRequest();
  ASSERT_FALSE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_SUSPEND
  stream_suspended_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_SUSPEND);
  encode_->CancelStreamingRequest();
  ASSERT_TRUE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  HfpClientInterface::Get()->ReleaseEncode(encode_);
}

TEST_F(HfpClientInterfaceTest, DecodeStartSession) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  decode_->StartSession();
  ASSERT_TRUE(update_audio_config_called);
  ASSERT_TRUE(start_session_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, DecodeStopSession) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  decode_->StopSession();
  ASSERT_TRUE(end_session_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, DecodeStartSessionUsesDecodeTransport) {
  // Get both encode and decode interfaces to ensure both transport instances are active.
  HfpClientInterface::Encode* encode = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode);
  HfpClientInterface::Decode* decode = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode);

  // Set a pending command on both transports to verify the correct one is used.
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);

  // When starting a decode session, it should reset the pending command on the
  // decode transport. The bug was that it incorrectly used the encode transport.
  decode->StartSession();
  ASSERT_TRUE(start_session_called);

  // Verify that the decode transport's command was reset, and the encode's was
  // not.
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);

  HfpClientInterface::Get()->ReleaseEncode(encode);
  HfpClientInterface::Get()->ReleaseDecode(decode);
}

TEST_F(HfpClientInterfaceTest, DecodeUpdateAudioConfigToHalPcm) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  ::hfp::pcm_config pcm_config = {.sample_rate_hz = 16000};
  decode_->UpdateAudioConfigToHal(pcm_config);
  ASSERT_TRUE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, DecodeUpdateAudioConfigToHalOffload) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  ::hfp::offload_config offload_config = {};
  decode_->UpdateAudioConfigToHal(offload_config);
  // Should do nothing and log a warning.
  ASSERT_FALSE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, DecodeConfirmStreamingRequest) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  decode_->ConfirmStreamingRequest();
  ASSERT_FALSE(stream_started_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  stream_started_called = false;
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  decode_->ConfirmStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, DecodeCancelStreamingRequest) {
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  decode_->CancelStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(stream_started_ack, bluetooth::audio::aidl::BluetoothAudioCtrlAck::FAILURE);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  stream_started_called = false;
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  decode_->CancelStreamingRequest();
  ASSERT_FALSE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_SUSPEND
  stream_suspended_called = false;
  SetDecodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_SUSPEND);
  decode_->CancelStreamingRequest();
  ASSERT_TRUE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_decoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  HfpClientInterface::Get()->ReleaseDecode(decode_);
}

TEST_F(HfpClientInterfaceTest, OffloadStartSession) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  offload_->StartSession();
  ASSERT_TRUE(update_audio_config_called);
  ASSERT_TRUE(start_session_called);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadStopSession) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  offload_->StopSession();
  ASSERT_TRUE(end_session_called);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadUpdateAudioConfigToHalOffload) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  ::hfp::offload_config offload_config = {};
  offload_->UpdateAudioConfigToHal(offload_config);
  ASSERT_TRUE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadUpdateAudioConfigToHalPcm) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  ::hfp::pcm_config pcm_config = {};
  offload_->UpdateAudioConfigToHal(pcm_config);
  // Should do nothing and log a warning.
  ASSERT_FALSE(update_audio_config_called);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadConfirmStreamingRequest) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  offload_->ConfirmStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  stream_started_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  offload_->ConfirmStreamingRequest();
  ASSERT_FALSE(stream_started_called);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadCancelStreamingRequest) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  // Test case: pending_cmd is HFP_CTRL_CMD_START
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_START);
  offload_->CancelStreamingRequest();
  ASSERT_TRUE(stream_started_called);
  ASSERT_EQ(stream_started_ack, bluetooth::audio::aidl::BluetoothAudioCtrlAck::FAILURE);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_NONE
  stream_started_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);
  offload_->CancelStreamingRequest();
  ASSERT_FALSE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  // Test case: pending_cmd is HFP_CTRL_CMD_SUSPEND
  stream_suspended_called = false;
  SetEncodingPendingCmd(bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_SUSPEND);
  offload_->CancelStreamingRequest();
  ASSERT_TRUE(stream_suspended_called);
  ASSERT_EQ(bluetooth::audio::aidl::hfp::hfp_encoding_transport_pending_cmd,
            bluetooth::audio::aidl::hfp::HFP_CTRL_CMD_NONE);

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, OffloadGetHfpScoConfig) {
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  auto config = offload_->GetHfpScoConfig();
  ASSERT_TRUE(config.empty());

  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

TEST_F(HfpClientInterfaceTest, GetInterfacesWithUnsupportedHalVersion) {
  SetHalVersion(bluetooth::audio::BluetoothAudioHalTransport::AIDL, 3, 0);
  ASSERT_EQ(nullptr, HfpClientInterface::Get());
}

TEST_F(HfpClientInterfaceTest, UnsupportedHalVersion) {
  HfpClientInterface::Encode* encode_ = HfpClientInterface::Get()->GetEncode(&message_loop_thread);
  ASSERT_NE(nullptr, encode_);
  HfpClientInterface::Decode* decode_ = HfpClientInterface::Get()->GetDecode(&message_loop_thread);
  ASSERT_NE(nullptr, decode_);
  HfpClientInterface::Offload* offload_ =
          HfpClientInterface::Get()->GetOffload(&message_loop_thread);
  ASSERT_NE(nullptr, offload_);

  // Set an unsupported version
  SetHalVersion(bluetooth::audio::BluetoothAudioHalTransport::AIDL, 3, 0);

  // Test Encode methods
  uint8_t data[10] = {1};
  encode_->StartSession();
  ASSERT_FALSE(start_session_called);
  encode_->StopSession();
  ASSERT_FALSE(end_session_called);
  ASSERT_EQ(0u, encode_->Read(data, 10));
  ::hfp::pcm_config pcm_config = {};
  encode_->UpdateAudioConfigToHal(pcm_config);
  ASSERT_FALSE(update_audio_config_called);

  // Test Decode methods
  decode_->StartSession();
  ASSERT_FALSE(start_session_called);
  decode_->StopSession();
  ASSERT_FALSE(end_session_called);
  ASSERT_EQ(0u, decode_->Write(data, 10));
  decode_->UpdateAudioConfigToHal(pcm_config);
  ASSERT_FALSE(update_audio_config_called);

  // Test Offload methods
  offload_->StartSession();
  ASSERT_FALSE(start_session_called);
  offload_->StopSession();
  ASSERT_FALSE(end_session_called);
  ::hfp::offload_config offload_config = {};
  offload_->UpdateAudioConfigToHal(offload_config);
  ASSERT_FALSE(update_audio_config_called);

  // Restore supported version for cleanup
  SetHalVersion(bluetooth::audio::BluetoothAudioHalTransport::AIDL, 4, 0);
  HfpClientInterface::Get()->ReleaseEncode(encode_);
  HfpClientInterface::Get()->ReleaseDecode(decode_);
  HfpClientInterface::Get()->ReleaseOffload(offload_);
}

}  // namespace
