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

#include "le_audio_software.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <hardware/audio.h>
#include <log/log.h>

#include <cerrno>

#include "aidl/android/hardware/bluetooth/audio/IBluetoothAudioProvider.h"
#include "aidl/audio_ctrl_ack.h"
#include "aidl/le_audio_software_aidl.h"
#include "audio_hal_interface/hal_version_manager.h"
#include "bta/le_audio/mock_codec_manager.h"
#include "gmock/gmock.h"
#include "hidl/le_audio_software_hidl.h"

#pragma GCC diagnostic ignored "-Wunused-private-field"

using testing::Return;
using testing::Test;

using bluetooth::audio::le_audio::LeAudioClientInterface;
using bluetooth::audio::le_audio::StreamCallbacks;

// MOCKS
namespace {
class MockHalVersionManager {
public:
  MockHalVersionManager() = default;
  MOCK_METHOD((bluetooth::audio::BluetoothAudioHalVersion), GetHalVersion, ());
  MOCK_METHOD((bluetooth::audio::BluetoothAudioHalTransport), GetHalTransport, ());
  MOCK_METHOD((android::sp<bluetooth::audio::IBluetoothAudioProvidersFactory_2_1>),
              GetProvidersFactory_2_1, ());
  MOCK_METHOD((android::sp<bluetooth::audio::IBluetoothAudioProvidersFactory_2_0>),
              GetProvidersFactory_2_0, ());

  static void SetInstance(MockHalVersionManager* ptr) { MockHalVersionManager::instance_ptr = ptr; }

  static MockHalVersionManager* GetInstance() { return instance_ptr; }

private:
  static MockHalVersionManager* instance_ptr;
};
MockHalVersionManager* MockHalVersionManager::instance_ptr = nullptr;

class MockBluetoothAudioClientInterfaceBidirEndpoint {
public:
  MOCK_METHOD((size_t), WriteAudioData, (const uint8_t* /*p_buf*/, uint32_t /*len*/), ());
  MOCK_METHOD((size_t), ReadAudioData, (uint8_t* /*p_buf*/, uint32_t /*len*/), ());
};

class MockBluetoothAudioClientInterfaceHidl {
public:
  MockBluetoothAudioClientInterfaceBidirEndpoint endpoint;

  MOCK_METHOD((bool), IsValid, (), (const));
  MOCK_METHOD((void), FlushAudioData, ());
  MOCK_METHOD((bool), UpdateAudioConfig_2_1,
              (const bluetooth::audio::hidl::AudioConfiguration_2_1& /*audio_config_2_1*/));
  MOCK_METHOD((int), StartSession_2_1, ());
  MOCK_METHOD((void), StreamStarted,
              (const bluetooth::audio::hidl::BluetoothAudioCtrlAck& /*ack*/));
  MOCK_METHOD((int), EndSession, ());
  MOCK_METHOD((void), StreamSuspended,
              (const bluetooth::audio::hidl::BluetoothAudioCtrlAck& /*ack*/));

  static void SetInstance(MockBluetoothAudioClientInterfaceHidl* ptr) { instance_ptr = ptr; }

  static MockBluetoothAudioClientInterfaceHidl* GetInstance() { return instance_ptr; }

private:
  static MockBluetoothAudioClientInterfaceHidl* instance_ptr;
};
MockBluetoothAudioClientInterfaceHidl* MockBluetoothAudioClientInterfaceHidl::instance_ptr =
        nullptr;

class MockBluetoothAudioClientInterfaceAidl {
public:
  MockBluetoothAudioClientInterfaceBidirEndpoint endpoint;

  MOCK_METHOD((bool), IsValid, (), (const));
  MOCK_METHOD((bool), SetAllowedLatencyModes,
              (std::vector<bluetooth::audio::aidl::LatencyMode> /*latency_modes*/));
  MOCK_METHOD((void), FlushAudioData, ());
  MOCK_METHOD((bool), UpdateAudioConfig,
              (const bluetooth::audio::aidl::AudioConfiguration& /*audio_config*/));
  MOCK_METHOD((int), StartSession, ());
  MOCK_METHOD((void), StreamStarted,
              (const bluetooth::audio::aidl::BluetoothAudioCtrlAck& /*ack*/));
  MOCK_METHOD((int), EndSession, ());
  MOCK_METHOD((void), StreamSuspended,
              (const bluetooth::audio::aidl::BluetoothAudioCtrlAck& /*ack*/));
  MOCK_METHOD((std::vector<bluetooth::audio::aidl::AudioCapabilities>), GetAudioCapabilities,
              (bluetooth::audio::aidl::SessionType /*session_type*/));
  MOCK_METHOD(
          (std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                               LeAudioAseConfigurationSetting>),
          GetLeAudioAseConfiguration,
          ((std::optional<std::vector<
                    std::optional<::aidl::android::hardware::bluetooth::audio::
                                          IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&),
           (std::optional<std::vector<
                    std::optional<::aidl::android::hardware::bluetooth::audio::
                                          IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&),
           (std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                                LeAudioConfigurationRequirement>&)));
  MOCK_METHOD(
          (::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                   LeAudioBroadcastConfigurationSetting),
          getLeAudioBroadcastConfiguration,
          ((const std::optional<std::vector<
                    std::optional<::aidl::android::hardware::bluetooth::audio::
                                          IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&),
           (const ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                    LeAudioBroadcastConfigurationRequirement&)));
  MOCK_METHOD(std::optional<bluetooth::audio::aidl::IBluetoothAudioProviderFactory::ProviderInfo>,
              GetProviderInfo,
              ((bluetooth::audio::aidl::SessionType),
               (std::shared_ptr<bluetooth::audio::aidl::IBluetoothAudioProviderFactory>)));

  static void SetInstance(MockBluetoothAudioClientInterfaceAidl* ptr) { instance_ptr = ptr; }

  static MockBluetoothAudioClientInterfaceAidl* GetInstance() { return instance_ptr; }

private:
  static MockBluetoothAudioClientInterfaceAidl* instance_ptr;
};
MockBluetoothAudioClientInterfaceAidl* MockBluetoothAudioClientInterfaceAidl::instance_ptr =
        nullptr;

class MockStreamCallbacks {
public:
  MOCK_METHOD((bool), OnResume, (bool));
  MOCK_METHOD((bool), OnSuspend, ());
  MOCK_METHOD((bool), OnSourceMetadataUpdate,
              ((const source_metadata_v7_t&), ::bluetooth::le_audio::DsaMode));
  MOCK_METHOD((bool), OnSinkMetadataUpdate, (const sink_metadata_v7_t&));
};
}  // namespace

namespace bluetooth::audio {
const BluetoothAudioHalVersion BluetoothAudioHalVersion::VERSION_UNAVAILABLE =
        BluetoothAudioHalVersion();
const BluetoothAudioHalVersion BluetoothAudioHalVersion::VERSION_2_1 =
        BluetoothAudioHalVersion(BluetoothAudioHalTransport::HIDL, 2, 1);

BluetoothAudioHalTransport HalVersionManager::GetHalTransport() {
  auto instance = MockHalVersionManager::GetInstance();
  if (instance) {
    return instance->GetHalTransport();
  }
  return BluetoothAudioHalTransport::UNKNOWN;
}

BluetoothAudioHalVersion HalVersionManager::GetHalVersion() {
  auto instance = MockHalVersionManager::GetInstance();
  if (instance) {
    return instance->GetHalVersion();
  }
  return BluetoothAudioHalVersion::VERSION_UNAVAILABLE;
}

namespace hidl {
class BluetoothAudioDeathRecipient : public ::android::hardware::hidl_death_recipient {
public:
  BluetoothAudioDeathRecipient(BluetoothAudioClientInterface* clientif,
                               bluetooth::common::MessageLoopThread* message_loop)
      : bluetooth_audio_clientif_(clientif), message_loop_(message_loop) {}

  MOCK_METHOD((void), serviceDied,
              (uint64_t /*cookie*/,
               const ::android::wp<::android::hidl::base::V1_0::IBase>& /*who*/),
              (override));

private:
  BluetoothAudioClientInterface* bluetooth_audio_clientif_;
  bluetooth::common::MessageLoopThread* message_loop_;
};

BluetoothAudioClientInterface::BluetoothAudioClientInterface(
        android::sp<BluetoothAudioDeathRecipient> death_recipient,
        IBluetoothTransportInstance* instance)
    : provider_(nullptr),
      provider_2_1_(nullptr),
      session_started_(false),
      mDataMQ(nullptr),
      transport_(instance) {
  death_recipient_ = death_recipient;
}

BluetoothAudioSinkClientInterface::BluetoothAudioSinkClientInterface(
        IBluetoothSinkTransportInstance* sink, bluetooth::common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{new BluetoothAudioDeathRecipient(this, message_loop), sink},
      sink_(sink) {}
BluetoothAudioSinkClientInterface::~BluetoothAudioSinkClientInterface() {}

size_t BluetoothAudioSinkClientInterface::ReadAudioData(uint8_t* p_buf, uint32_t len) {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->endpoint.ReadAudioData(p_buf, len);
  }
  return 0;
}

BluetoothAudioSourceClientInterface::BluetoothAudioSourceClientInterface(
        IBluetoothSourceTransportInstance* source,
        bluetooth::common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{new BluetoothAudioDeathRecipient(this, message_loop), source},
      source_(source) {}
BluetoothAudioSourceClientInterface::~BluetoothAudioSourceClientInterface() {}

bool BluetoothAudioClientInterface::IsValid() const {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->IsValid();
  }
  return false;
}

size_t BluetoothAudioSourceClientInterface::WriteAudioData(const uint8_t* p_buf, uint32_t len) {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->endpoint.WriteAudioData(p_buf, len);
  }
  return 0;
}

void BluetoothAudioClientInterface::FlushAudioData() {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    instance->FlushAudioData();
  }
}

bool BluetoothAudioClientInterface::UpdateAudioConfig_2_1(const AudioConfiguration_2_1& cfg) {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->UpdateAudioConfig_2_1(cfg);
  }
  return false;
}

int BluetoothAudioClientInterface::StartSession_2_1() {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->StartSession_2_1();
  }
  return -EINVAL;
}

void BluetoothAudioClientInterface::StreamStarted(const BluetoothAudioCtrlAck& ack) {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    instance->StreamStarted(ack);
  }
}

int BluetoothAudioClientInterface::EndSession() {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    return instance->EndSession();
  }
  return -EINVAL;
}

void BluetoothAudioClientInterface::StreamSuspended(const BluetoothAudioCtrlAck& ack) {
  auto instance = MockBluetoothAudioClientInterfaceHidl::GetInstance();
  if (instance) {
    instance->StreamSuspended(ack);
  }
}

std::ostream& operator<<(std::ostream& os, const BluetoothAudioCtrlAck& ack) {
  switch (ack) {
    case BluetoothAudioCtrlAck::SUCCESS_FINISHED:
      os << "SUCCESS_FINISHED";
      break;
    case BluetoothAudioCtrlAck::PENDING:
      os << "PENDING";
      break;
    case BluetoothAudioCtrlAck::FAILURE_UNSUPPORTED:
      os << "FAILURE_UNSUPPORTED";
      break;
    case BluetoothAudioCtrlAck::FAILURE_BUSY:
      os << "FAILURE_BUSY";
      break;
    case BluetoothAudioCtrlAck::FAILURE_DISCONNECTING:
      os << "FAILURE_DISCONNECTING";
      break;
    case BluetoothAudioCtrlAck::FAILURE:
      os << "FAILURE";
      break;
    default:
      os << "UNKNOWN";
      break;
  };
  return os;
}
}  // namespace hidl

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

size_t BluetoothAudioSinkClientInterface::ReadAudioData(uint8_t* p_buf, uint32_t len) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->endpoint.ReadAudioData(p_buf, len);
  }
  return 0;
}

BluetoothAudioSourceClientInterface::BluetoothAudioSourceClientInterface(
        IBluetoothSourceTransportInstance* source)
    : BluetoothAudioClientInterface{source}, source_(source) {}
BluetoothAudioSourceClientInterface::~BluetoothAudioSourceClientInterface() {}

size_t BluetoothAudioSourceClientInterface::WriteAudioData(const uint8_t* p_buf, uint32_t len) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->endpoint.WriteAudioData(p_buf, len);
  }
  return 0;
}

bool BluetoothAudioClientInterface::IsValid() const {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->IsValid();
  }
  return false;
}

bool BluetoothAudioClientInterface::SetAllowedLatencyModes(std::vector<LatencyMode> latency_modes) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->SetAllowedLatencyModes(latency_modes);
  }
  return false;
}

void BluetoothAudioClientInterface::FlushAudioData() {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    instance->FlushAudioData();
  }
}

bool BluetoothAudioClientInterface::UpdateAudioConfig(const AudioConfiguration& audio_config) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->UpdateAudioConfig(audio_config);
  }
  return false;
}

int BluetoothAudioClientInterface::StartSession() {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->StartSession();
  }
  return -EINVAL;
}

void BluetoothAudioClientInterface::StreamStarted(const BluetoothAudioCtrlAck& ack) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    instance->StreamStarted(ack);
  }
}

int BluetoothAudioClientInterface::EndSession() {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->EndSession();
  }
  return -EINVAL;
}

void BluetoothAudioClientInterface::StreamSuspended(const BluetoothAudioCtrlAck& ack) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->StreamSuspended(ack);
  }
}

std::vector<AudioCapabilities> BluetoothAudioClientInterface::GetAudioCapabilities(
        SessionType session_type) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->GetAudioCapabilities(session_type);
  }
  return std::vector<AudioCapabilities>(0);
}

std::optional<bluetooth::audio::aidl::IBluetoothAudioProviderFactory::ProviderInfo>
BluetoothAudioClientInterface::GetProviderInfo(
        bluetooth::audio::aidl::SessionType session_type,
        std::shared_ptr<bluetooth::audio::aidl::IBluetoothAudioProviderFactory> provider_factory) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->GetProviderInfo(session_type, provider_factory);
  }
  return std::nullopt;
}

std::vector<IBluetoothAudioProvider::LeAudioAseConfigurationSetting>
BluetoothAudioClientInterface::GetLeAudioAseConfiguration(
        std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
                remoteSinkAudioCapabilities,
        std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
                remoteSourceAudioCapabilities,
        std::vector<IBluetoothAudioProvider::LeAudioConfigurationRequirement>& requirements) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->GetLeAudioAseConfiguration(remoteSinkAudioCapabilities,
                                                remoteSourceAudioCapabilities, requirements);
  }

  return std::vector<IBluetoothAudioProvider::LeAudioAseConfigurationSetting>();
}

IBluetoothAudioProvider::LeAudioBroadcastConfigurationSetting
BluetoothAudioClientInterface::getLeAudioBroadcastConfiguration(
        const std::optional<
                std::vector<std::optional<IBluetoothAudioProvider::LeAudioDeviceCapabilities>>>&
                remoteSinkAudioCapabilities,
        const IBluetoothAudioProvider::LeAudioBroadcastConfigurationRequirement& requirement) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->getLeAudioBroadcastConfiguration(remoteSinkAudioCapabilities, requirement);
  }

  return IBluetoothAudioProvider::LeAudioBroadcastConfigurationSetting();
}

std::ostream& operator<<(std::ostream& os, const BluetoothAudioCtrlAck& ack) {
  switch (ack) {
    case BluetoothAudioCtrlAck::SUCCESS_FINISHED:
      os << "SUCCESS_FINISHED";
      break;
    case BluetoothAudioCtrlAck::SUCCESS_RECONFIGURATION:
      os << "SUCCESS_RECONFIGURATION";
      break;
    case BluetoothAudioCtrlAck::PENDING:
      os << "PENDING";
      break;
    case BluetoothAudioCtrlAck::FAILURE_UNSUPPORTED:
      os << "FAILURE_UNSUPPORTED";
      break;
    case BluetoothAudioCtrlAck::FAILURE_BUSY:
      os << "FAILURE_BUSY";
      break;
    case BluetoothAudioCtrlAck::FAILURE_DISCONNECTING:
      os << "FAILURE_DISCONNECTING";
      break;
    case BluetoothAudioCtrlAck::FAILURE:
      os << "FAILURE";
      break;
    default:
      os << "UNKNOWN";
      break;
  };
  return os;
}
}  // namespace aidl
}  // namespace bluetooth::audio

namespace bluetooth::le_audio::broadcaster {
std::ostream& operator<<(std::ostream& os, const BroadcastConfiguration&) { return os; }
}  // namespace bluetooth::le_audio::broadcaster

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

class LeAudioSoftwareUnicastTest : public Test {
protected:
  virtual void SetUp() override {
    init_message_loop_thread();
    MockHalVersionManager::SetInstance(&hal_version_manager_);

    unicast_sink_stream_cb_.reset(new StreamCallbacks{
            std::bind(&MockStreamCallbacks::OnResume, &sink_stream_callbacks_,
                      std::placeholders::_1),
            std::bind(&MockStreamCallbacks::OnSuspend, &sink_stream_callbacks_),
            std::bind(&MockStreamCallbacks::OnSourceMetadataUpdate, &sink_stream_callbacks_,
                      std::placeholders::_1, std::placeholders::_2),
            std::bind(&MockStreamCallbacks::OnSinkMetadataUpdate, &sink_stream_callbacks_,
                      std::placeholders::_1),
    });

    unicast_source_stream_cb_.reset(new StreamCallbacks{
            std::bind(&MockStreamCallbacks::OnResume, &source_stream_callbacks_,
                      std::placeholders::_1),
            std::bind(&MockStreamCallbacks::OnSuspend, &source_stream_callbacks_),
            std::bind(&MockStreamCallbacks::OnSourceMetadataUpdate, &source_stream_callbacks_,
                      std::placeholders::_1, std::placeholders::_2),
            std::bind(&MockStreamCallbacks::OnSinkMetadataUpdate, &source_stream_callbacks_,
                      std::placeholders::_1),
    });

    sink_ = LeAudioClientInterface::Get()->GetSink(*unicast_sink_stream_cb_, &message_loop_thread,
                                                   is_broadcast_);
    if (is_broadcast_) {
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsBroadcastSinkAcquired());
    } else {
      source_ = LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                         &message_loop_thread);
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsSourceAcquired());
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsUnicastSinkAcquired());
    }
  }

  virtual void TearDown() override {
    if (LeAudioClientInterface::Get()->IsUnicastSinkAcquired() ||
        LeAudioClientInterface::Get()->IsBroadcastSinkAcquired()) {
      LeAudioClientInterface::Get()->ReleaseSink(sink_);
      if (is_broadcast_) {
        ASSERT_FALSE(LeAudioClientInterface::Get()->IsBroadcastSinkAcquired());
      } else {
        ASSERT_FALSE(LeAudioClientInterface::Get()->IsUnicastSinkAcquired());
      }
    }

    if (LeAudioClientInterface::Get()->IsSourceAcquired()) {
      LeAudioClientInterface::Get()->ReleaseSource(source_);
      ASSERT_FALSE(LeAudioClientInterface::Get()->IsSourceAcquired());
    }

    cleanup_message_loop_thread();

    unicast_sink_stream_cb_.reset();
    unicast_source_stream_cb_.reset();

    MockBluetoothAudioClientInterfaceHidl::SetInstance(nullptr);
    MockBluetoothAudioClientInterfaceAidl::SetInstance(nullptr);
    MockHalVersionManager::SetInstance(nullptr);
  }

  bool is_broadcast_ = false;
  LeAudioClientInterface::Sink* sink_ = nullptr;
  LeAudioClientInterface::Source* source_ = nullptr;

  MockHalVersionManager hal_version_manager_;
  MockStreamCallbacks sink_stream_callbacks_;
  MockStreamCallbacks source_stream_callbacks_;

  std::unique_ptr<StreamCallbacks> unicast_sink_stream_cb_;
  std::unique_ptr<StreamCallbacks> unicast_source_stream_cb_;
};

class LeAudioSoftwareUnicastTestAidl : public LeAudioSoftwareUnicastTest {
protected:
  void SetUpMockCodecManager(bluetooth::le_audio::types::CodecLocation location) {
    codec_manager_ = bluetooth::le_audio::CodecManager::GetInstance();
    ASSERT_NE(codec_manager_, nullptr);
    std::vector<bluetooth::le_audio::btle_audio_codec_config_t> mock_offloading_preference(0);
    codec_manager_->Start(mock_offloading_preference);
    mock_codec_manager_ = MockCodecManager::GetInstance();
    ASSERT_NE((void*)mock_codec_manager_, (void*)codec_manager_);
    ASSERT_NE(mock_codec_manager_, nullptr);
    ON_CALL(*mock_codec_manager_, GetCodecLocation()).WillByDefault(Return(location));
  }

  virtual void SetUp() override {
    SetUpMockCodecManager(::bluetooth::le_audio::types::CodecLocation::ADSP);
    ON_CALL(hal_version_manager_, GetHalTransport)
            .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::AIDL));

    MockBluetoothAudioClientInterfaceAidl::SetInstance(&audio_client_interface_);
    ON_CALL(audio_client_interface_, IsValid).WillByDefault(Return(true));

    LeAudioSoftwareUnicastTest::SetUp();
  }

  MockBluetoothAudioClientInterfaceAidl audio_client_interface_;
  bluetooth::le_audio::CodecManager* codec_manager_;
  MockCodecManager* mock_codec_manager_;
};

TEST_F(LeAudioSoftwareUnicastTestAidl, AcquireAndRelease) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, TrackListUpdate) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);

  // Recording tracks updates twice - with a valid track and with an empty track list
  auto& sink_transport =
          ::bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::interface_unicast_;
  ASSERT_NE(sink_transport, nullptr);
  record_track_metadata_v7 recording_tracks[] = {
          {
                  .base =
                          {
                                  .source = AUDIO_SOURCE_MIC,
                                  .gain = 1.0f,
                                  .dest_device = AUDIO_DEVICE_IN_DEFAULT,
                          },
                  .channel_mask =
                          audio_channel_mask_t(AUDIO_CHANNEL_IN_LEFT | AUDIO_CHANNEL_IN_RIGHT),
                  .tags = {'t', 'a', 'g'},
          },
  };
  EXPECT_CALL(sink_stream_callbacks_, OnSinkMetadataUpdate(testing::_)).Times(2);
  sink_transport->GetTransportInstance()->SinkMetadataChanged(
          sink_metadata_v7_t({.track_count = 1, .tracks = recording_tracks}));
  sink_transport->GetTransportInstance()->SinkMetadataChanged(
          sink_metadata_v7_t({.track_count = 0, .tracks = nullptr}));

  // Playback tracks updates twice - with a valid track and with an empty track list
  auto& source_transport = ::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface;
  ASSERT_NE(source_transport, nullptr);
  playback_track_metadata_v7 playback_tracks[] = {
          {
                  .base =
                          {
                                  .usage = AUDIO_USAGE_MEDIA,
                                  .content_type = AUDIO_CONTENT_TYPE_MOVIE,
                                  .gain = 1.0f,
                          },
                  .channel_mask =
                          audio_channel_mask_t(AUDIO_CHANNEL_IN_LEFT | AUDIO_CHANNEL_IN_RIGHT),
                  .tags = {'t', 'a', 'g'},
          },
  };
  EXPECT_CALL(source_stream_callbacks_, OnSourceMetadataUpdate(testing::_, testing::_)).Times(2);
  source_transport->GetTransportInstance()->SourceMetadataChanged(
          source_metadata_v7_t({.track_count = 1, .tracks = playback_tracks}));
  source_transport->GetTransportInstance()->SourceMetadataChanged(
          source_metadata_v7_t({.track_count = 0, .tracks = nullptr}));
}

class LeAudioSoftwareUnicastTestHidl : public LeAudioSoftwareUnicastTest {
protected:
  virtual void SetUp() override {
    ON_CALL(hal_version_manager_, GetHalTransport)
            .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::HIDL));

    MockBluetoothAudioClientInterfaceHidl::SetInstance(&audio_client_interface_);
    ON_CALL(audio_client_interface_, IsValid).WillByDefault(Return(true));

    LeAudioSoftwareUnicastTest::SetUp();
  }

  MockBluetoothAudioClientInterfaceHidl audio_client_interface_;
};

TEST_F(LeAudioSoftwareUnicastTestHidl, AcquireAndRelease) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);
}

class LeAudioSoftwareBroadcastTestAidl : public LeAudioSoftwareUnicastTestAidl {
protected:
  virtual void SetUp() override {
    is_broadcast_ = true;
    LeAudioSoftwareUnicastTestAidl::SetUp();
  }
};

TEST_F(LeAudioSoftwareBroadcastTestAidl, AcquireAndRelease) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_EQ(nullptr, source_);
  ASSERT_NE(::bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::interface_broadcast_,
            nullptr);
  ASSERT_EQ(::bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::interface_unicast_, nullptr);
  ASSERT_EQ(::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface, nullptr);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, GetBroadcastConfig) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(sink_->GetBroadcastConfig({}, std::nullopt), std::nullopt);
}

}  // namespace
