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

#include <com_android_bluetooth_flags.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <hardware/audio.h>
#include <log/log.h>

#include <cerrno>

#include "aidl/android/hardware/bluetooth/audio/IBluetoothAudioProvider.h"
#include "aidl/audio_ctrl_ack.h"
#include "aidl/le_audio_software_aidl.h"
#include "aidl/le_audio_utils.h"
#include "audio_hal_interface/hal_version_manager.h"
#include "bta/le_audio/mock_codec_manager.h"
#include "hidl/le_audio_software_hidl.h"

#pragma GCC diagnostic ignored "-Wunused-private-field"

using testing::_;
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
BluetoothAudioClientInterface::BluetoothAudioClientInterface(
        IBluetoothTransportInstance* instance, bluetooth::common::MessageLoopThread* message_loop)
    : death_handler_thread_(message_loop),
      provider_(nullptr),
      provider_factory_(nullptr),
      session_started_(false),
      data_mq_(nullptr),
      transport_(instance),
      latency_modes_({LatencyMode::FREE}) {}

BluetoothAudioSinkClientInterface::BluetoothAudioSinkClientInterface(
        IBluetoothSinkTransportInstance* sink, bluetooth::common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{sink, message_loop}, sink_(sink) {}
BluetoothAudioSinkClientInterface::~BluetoothAudioSinkClientInterface() {}

size_t BluetoothAudioSinkClientInterface::ReadAudioData(uint8_t* p_buf, uint32_t len) {
  auto instance = MockBluetoothAudioClientInterfaceAidl::GetInstance();
  if (instance) {
    return instance->endpoint.ReadAudioData(p_buf, len);
  }
  return 0;
}

void BluetoothAudioClientInterface::SetCodecPriority(CodecId /*codec_id*/, int32_t /*priority*/) {}

BluetoothAudioSourceClientInterface::BluetoothAudioSourceClientInterface(
        IBluetoothSourceTransportInstance* source,
        bluetooth::common::MessageLoopThread* message_loop)
    : BluetoothAudioClientInterface{source, message_loop}, source_(source) {}
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
    instance->StreamSuspended(ack);
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

static void cleanup_message_loop_thread() {
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
    source_ = LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                       &message_loop_thread, is_broadcast_);

    if (is_broadcast_) {
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsBroadcastSourceAcquired());
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsBroadcastSinkAcquired());
    } else {
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsUnicastSourceAcquired());
      ASSERT_TRUE(LeAudioClientInterface::Get()->IsUnicastSinkAcquired());
    }
    com_android_bluetooth_flags_reset_flags();
    set_com_android_bluetooth_flags_leaudio_software_bt_request_lock_fix(true);
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

    if (LeAudioClientInterface::Get()->IsUnicastSourceAcquired() ||
        LeAudioClientInterface::Get()->IsBroadcastSourceAcquired()) {
      LeAudioClientInterface::Get()->ReleaseSource(source_);
      if (is_broadcast_) {
        ASSERT_FALSE(LeAudioClientInterface::Get()->IsBroadcastSourceAcquired());
      } else {
        ASSERT_FALSE(LeAudioClientInterface::Get()->IsUnicastSourceAcquired());
      }
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

// Test scenario: Verify that LeAudioClientInterface::Get() returns a valid,
// non-null instance and that subsequent calls return the same instance.
TEST_F(LeAudioSoftwareUnicastTestAidl, GetInstance) {
  ASSERT_NE(LeAudioClientInterface::Get(), nullptr);
  // Should return the same instance
  ASSERT_EQ(LeAudioClientInterface::Get(), LeAudioClientInterface::Get());
}

// Test scenario: Test the successful acquisition and release of both sink and
// source interfaces for a unicast session.
TEST_F(LeAudioSoftwareUnicastTestAidl, AcquireAndRelease) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);
}

// Test scenario: Ensure that attempting to acquire a sink interface a second
// time (while it's already acquired) fails and returns `nullptr`.
TEST_F(LeAudioSoftwareUnicastTestAidl, GetSinkTwice) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_EQ(LeAudioClientInterface::Get()->GetSink(*unicast_sink_stream_cb_, &message_loop_thread,
                                                   is_broadcast_),
            nullptr);
}

// Test scenario: Verify that attempting to release a sink interface that has
// not been acquired returns `false`.
TEST_F(LeAudioSoftwareUnicastTestAidl, ReleaseSinkNotAcquired) {
  LeAudioClientInterface::Sink sink(false);
  ASSERT_FALSE(LeAudioClientInterface::Get()->ReleaseSink(&sink));
}

// Test scenario: Ensure that attempting to acquire a source interface a second
// time (while it's already acquired) fails and returns `nullptr`.
TEST_F(LeAudioSoftwareUnicastTestAidl, GetSourceTwice) {
  ASSERT_NE(nullptr, source_);
  ASSERT_EQ(LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                     &message_loop_thread, is_broadcast_),
            nullptr);
}

// Test scenario: Verify that attempting to release a source interface that has
// not been acquired returns `false`.
TEST_F(LeAudioSoftwareUnicastTestAidl, ReleaseSourceNotAcquired) {
  LeAudioClientInterface::Source source;
  ASSERT_FALSE(LeAudioClientInterface::Get()->ReleaseSource(&source));
}

// Test scenario: Test the update of track metadata for both sink and source,
// including valid and empty track lists.
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
  auto& source_transport = ::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::
      interface_unicast_;
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

// Test scenario: Test the successful acquisition and release of both sink and
// source interfaces for a unicast session using the HIDL HAL.
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

// Test scenario: Test the successful acquisition and release of both sink and
// source interface for a broadcast session.
TEST_F(LeAudioSoftwareBroadcastTestAidl, AcquireAndRelease) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);
  ASSERT_NE(::bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::interface_broadcast_,
            nullptr);
  ASSERT_NE(::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface_broadcast_,
            nullptr);
  ASSERT_EQ(::bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::interface_unicast_, nullptr);
  ASSERT_EQ(::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface_unicast_,
            nullptr);
}

// Test scenario: Verify that a valid broadcast configuration can be retrieved
// for a broadcast sink.
TEST_F(LeAudioSoftwareBroadcastTestAidl, GetBroadcastConfig) {
  ASSERT_NE(nullptr, sink_);
  ASSERT_NE(nullptr, source_);
  ASSERT_NE(sink_->GetBroadcastConfig({}, std::nullopt), std::nullopt);
  ASSERT_NE(source_->GetBroadcastConfig({}, std::nullopt), std::nullopt);
}

// Test scenario: Verify that a broadcast source can be acquired for software
// decoding.
TEST_F(LeAudioSoftwareBroadcastTestAidl, GetSourceSoftwareDecoding) {
  // Release the source created in SetUp with ADSP location
  ASSERT_NE(nullptr, source_);
  LeAudioClientInterface::Get()->ReleaseSource(source_);
  source_ = nullptr;
  ASSERT_FALSE(LeAudioClientInterface::Get()->IsBroadcastSourceAcquired());

  // Set codec location to Host for software decoding
  ON_CALL(*mock_codec_manager_, GetCodecLocation())
          .WillByDefault(Return(::bluetooth::le_audio::types::CodecLocation::HOST));

  // Get source for broadcast software decoding
  source_ = LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                     &message_loop_thread, is_broadcast_);
  ASSERT_NE(nullptr, source_);
  ASSERT_TRUE(LeAudioClientInterface::Get()->IsBroadcastSourceAcquired());
  ASSERT_NE(::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface_broadcast_,
            nullptr);
  ASSERT_EQ(::bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::interface_unicast_,
            nullptr);
}

// Test scenario: Test the retrieval of a unicast configuration with valid
// requirements.
TEST_F(LeAudioSoftwareUnicastTestAidl, GetUnicastConfig) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements reqs = {
          .audio_context_type = ::bluetooth::le_audio::types::LeAudioContextType::MEDIA,
  };

  std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                      LeAudioAseConfigurationSetting>
          ret_configs(1);
  ret_configs[0].audioContext.bitmask =
          ::aidl::android::hardware::bluetooth::audio::AudioContext::MEDIA;
  ret_configs[0].sinkAseConfiguration.emplace(1);
  (*ret_configs[0].sinkAseConfiguration)[0].emplace();
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.targetLatency = ::aidl::android::
          hardware::bluetooth::audio::LeAudioAseConfiguration::TargetLatency::LOWER;
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.targetPhy =
          ::aidl::android::hardware::bluetooth::audio::Phy::TWO_M;
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.codecId.emplace();
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.codecId =
          ::aidl::android::hardware::bluetooth::audio::CodecId::Core::LC3;

  EXPECT_CALL(audio_client_interface_,
              GetLeAudioAseConfiguration(testing::_, testing::_, testing::_))
          .WillOnce(Return(ret_configs));
  ASSERT_NE(sink_->GetUnicastConfig(reqs), std::nullopt);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetUnicastConfigEmpty) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements reqs = {
          .audio_context_type = ::bluetooth::le_audio::types::LeAudioContextType::MEDIA,
  };

  std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                      LeAudioAseConfigurationSetting>
          ret_configs(0);
  EXPECT_CALL(audio_client_interface_,
              GetLeAudioAseConfiguration(testing::_, testing::_, testing::_))
          .WillOnce(Return(ret_configs));
  ASSERT_EQ(sink_->GetUnicastConfig(reqs), std::nullopt);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetUnicastConfigMultiple) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::CodecManager::UnicastConfigurationRequirements reqs = {
          .audio_context_type = ::bluetooth::le_audio::types::LeAudioContextType::MEDIA,
  };

  std::vector<::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider::
                      LeAudioAseConfigurationSetting>
          ret_configs(2);
  ret_configs[0].audioContext.bitmask =
          ::aidl::android::hardware::bluetooth::audio::AudioContext::MEDIA;
  ret_configs[0].sinkAseConfiguration.emplace(1);
  (*ret_configs[0].sinkAseConfiguration)[0].emplace();
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.targetLatency = ::aidl::android::
          hardware::bluetooth::audio::LeAudioAseConfiguration::TargetLatency::LOWER;
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.targetPhy =
          ::aidl::android::hardware::bluetooth::audio::Phy::TWO_M;
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.codecId.emplace();
  (*ret_configs[0].sinkAseConfiguration)[0]->aseConfiguration.codecId =
          ::aidl::android::hardware::bluetooth::audio::CodecId::Core::LC3;
  ret_configs[1].audioContext.bitmask =
          ::aidl::android::hardware::bluetooth::audio::AudioContext::MEDIA;
  ret_configs[1].sinkAseConfiguration.emplace(1);
  (*ret_configs[1].sinkAseConfiguration)[0].emplace();
  (*ret_configs[1].sinkAseConfiguration)[0]->aseConfiguration.targetLatency = ::aidl::android::
          hardware::bluetooth::audio::LeAudioAseConfiguration::TargetLatency::LOWER;
  (*ret_configs[1].sinkAseConfiguration)[0]->aseConfiguration.targetPhy =
          ::aidl::android::hardware::bluetooth::audio::Phy::TWO_M;
  (*ret_configs[1].sinkAseConfiguration)[0]->aseConfiguration.codecId.emplace();
  (*ret_configs[1].sinkAseConfiguration)[0]
          ->aseConfiguration.codecId
          ->set<::aidl::android::hardware::bluetooth::audio::CodecId::Tag::core>(
                  ::aidl::android::hardware::bluetooth::audio::CodecId::Core::LC3);
  EXPECT_CALL(audio_client_interface_,
              GetLeAudioAseConfiguration(testing::_, testing::_, testing::_))
          .WillOnce(Return(ret_configs));
  ASSERT_NE(sink_->GetUnicastConfig(reqs), std::nullopt);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetOffloadCapabilities) {
  auto capabilities = bluetooth::audio::le_audio::get_offload_capabilities();
  ASSERT_EQ(capabilities.unicast_offload_capabilities.size(), (size_t)0);
  ASSERT_EQ(capabilities.broadcast_offload_capabilities.size(), (size_t)0);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkSetPcmParameters) {
  ASSERT_NE(nullptr, sink_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  sink_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkSetPcmParametersAfterCleanup) {
  ASSERT_NE(nullptr, sink_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  sink_->Cleanup();
  sink_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkSetRemoteDelay) {
  ASSERT_NE(nullptr, sink_);
  sink_->SetRemoteDelay(10);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStartSession) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).WillOnce(Return(true));
  EXPECT_CALL(audio_client_interface_, StartSession()).WillOnce(Return(0));
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStartSessionAfterCleanup) {
  ASSERT_NE(nullptr, sink_);
  sink_->Cleanup();
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStartSessionUpdateConfigFail) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).WillOnce(Return(false));
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStopSession) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_, EndSession()).WillOnce(Return(0));
  sink_->StopSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkRead) {
  ASSERT_NE(nullptr, sink_);
  uint8_t buffer[10];
  EXPECT_CALL(audio_client_interface_.endpoint, ReadAudioData(buffer, 10)).WillOnce(Return(10));
  ASSERT_EQ(sink_->Read(buffer, 10), (size_t)10);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSetPcmParameters) {
  ASSERT_NE(nullptr, source_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  source_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSetPcmParametersAfterCleanup) {
  ASSERT_NE(nullptr, source_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  source_->Cleanup();
  source_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSetRemoteDelay) {
  ASSERT_NE(nullptr, source_);
  source_->SetRemoteDelay(10);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStartSession) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).WillOnce(Return(true));
  EXPECT_CALL(audio_client_interface_, StartSession()).WillOnce(Return(0));
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStartSessionAfterCleanup) {
  ASSERT_NE(nullptr, source_);
  source_->Cleanup();
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStartSessionUpdateConfigFail) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).WillOnce(Return(false));
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStopSession) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_, EndSession()).WillOnce(Return(0));
  source_->StopSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceWrite) {
  ASSERT_NE(nullptr, source_);
  uint8_t buffer[10] = {0};
  EXPECT_CALL(audio_client_interface_.endpoint, WriteAudioData(buffer, 10)).WillOnce(Return(10));
  ASSERT_EQ(source_->Write(buffer, 10), (size_t)10);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkConfirmStreamingRequest) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkConfirmStreamingRequestIdle) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::IDLE);
  EXPECT_CALL(audio_client_interface_, StreamStarted(_)).Times(0);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkConfirmStreamingRequestPendingBeforeResume) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_BEFORE_REQUEST);
  EXPECT_CALL(audio_client_interface_, StreamStarted(_)).Times(0);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkConfirmStreamingRequestConfirmed) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  EXPECT_CALL(audio_client_interface_, StreamStarted(_)).Times(0);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCancelStreamingRequest) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::aidl::BluetoothAudioCtrlAck::FAILURE))
          .Times(1);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCancelStreamingRequestIdle) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::IDLE);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCancelStreamingRequestPendingBeforeResume) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_BEFORE_REQUEST);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCancelStreamingRequestCanceled) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceConfirmStreamingRequest) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceConfirmStreamingRequestIdle) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::IDLE);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceConfirmStreamingRequestPendingBeforeResume) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_BEFORE_REQUEST);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceConfirmStreamingRequestConfirmed) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceCancelStreamingRequest) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::aidl::BluetoothAudioCtrlAck::FAILURE))
          .Times(1);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceCancelStreamingRequestIdle) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::IDLE);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceCancelStreamingRequestPendingBeforeResume) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_BEFORE_REQUEST);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceCancelStreamingRequestCanceled) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;
  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkSuspendedForReconfiguration) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(
          audio_client_interface_,
          StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_RECONFIGURATION))
          .Times(1);
  sink_->SuspendedForReconfiguration();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkReconfigurationComplete) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  sink_->ReconfigurationComplete();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStreamSuspended) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  sink_->StreamSuspended();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSuspendedForReconfiguration) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(
          audio_client_interface_,
          StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_RECONFIGURATION))
          .Times(1);
  source_->SuspendedForReconfiguration();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceReconfigurationComplete) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  source_->ReconfigurationComplete();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStreamSuspended) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::aidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  source_->StreamSuspended();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCleanupInvalidTransport) {
  ASSERT_NE(nullptr, sink_);
  // Set an invalid transport and expect no calls to the mock
  ON_CALL(hal_version_manager_, GetHalTransport)
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::UNKNOWN));
  sink_->Cleanup();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkStartSessionNonV2_1Hidl) {
  ASSERT_NE(nullptr, sink_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_UNAVAILABLE));
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkConfirmStreamingRequestInvalidStates) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  sink_->ConfirmStreamingRequest();

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkCancelStreamingRequestInvalidStates) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSinkTransport::instance_unicast_;

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  sink_->CancelStreamingRequest();

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkUpdateAudioConfigToHalNoOffload) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::stream_config config;
  // Set session to non-offload, expect no call
  ON_CALL(hal_version_manager_, GetHalTransport())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::AIDL));
  sink_->UpdateAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, GetBroadcastSinkConfigNoOffload) {
  // This test is for a unicast sink, but we are in a broadcast test fixture.
  // So we need to release the broadcast sink and create a unicast one.
  LeAudioClientInterface::Get()->ReleaseSink(sink_);
  sink_ = LeAudioClientInterface::Get()->GetSink(*unicast_sink_stream_cb_, &message_loop_thread,
                                                 false);
  ASSERT_NE(nullptr, sink_);
  ASSERT_FALSE(sink_->IsBroadcaster());
  ASSERT_EQ(sink_->GetBroadcastConfig({}, std::nullopt), std::nullopt);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, UpdateBroadcastSinkAudioConfigToHalNoOffload) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::broadcast_offload_config config;
  // Set session to non-offload, expect no call
  is_broadcast_ = false;
  sink_->UpdateBroadcastAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceStartSessionNonV2_1Hidl) {
  ASSERT_NE(nullptr, source_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_UNAVAILABLE));
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceConfirmStreamingRequestInvalidStates) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  source_->ConfirmStreamingRequest();

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceCancelStreamingRequestInvalidStates) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::aidl::le_audio::LeAudioSourceTransport::instance_unicast_;

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CONFIRMED);
  source_->CancelStreamingRequest();

  instance->SetBluetoothRequestState(bluetooth::audio::le_audio::BluetoothRequest::RESUME,
                                     bluetooth::audio::le_audio::BluetoothRequestState::CANCELED);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceUpdateAudioConfigToHalNoOffload) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::stream_config config;
  // Set session to non-offload, expect no call
  ON_CALL(hal_version_manager_, GetHalTransport())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::AIDL));
  source_->UpdateAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSetCodecPriorityNoOffload) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::types::LeAudioCodecId codec_id;
  // Set session to non-offload, expect no call
  ON_CALL(hal_version_manager_, GetHalTransport())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::AIDL));
  source_->SetCodecPriority(codec_id, 0);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, GetBroadcastSourceConfigNoOffload) {
  // This test is for a unicast source, but we are in a broadcast test fixture.
  // So we need to release the broadcast source and create a unicast one.
  LeAudioClientInterface::Get()->ReleaseSource(source_);
  source_ = LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                     &message_loop_thread, false);
  ASSERT_NE(nullptr, source_);
  ASSERT_FALSE(source_->IsBroadcastSink());
  ASSERT_EQ(source_->GetBroadcastConfig({}, std::nullopt), std::nullopt);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, UpdateBroadcastSourceAudioConfigToHalNoOffload) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::broadcast_offload_config config;
  // Set session to non-offload, expect no call
  is_broadcast_ = false;
  source_->UpdateBroadcastAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetSinkInvalidInterface) {
  // Release the valid sink first
  LeAudioClientInterface::Get()->ReleaseSink(sink_);
  sink_ = nullptr;

  ON_CALL(audio_client_interface_, IsValid).WillByDefault(Return(false));
  ASSERT_EQ(LeAudioClientInterface::Get()->GetSink(*unicast_sink_stream_cb_, &message_loop_thread,
                                                   is_broadcast_),
            nullptr);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetSourceInvalidInterface) {
  // Release the valid source first
  LeAudioClientInterface::Get()->ReleaseSource(source_);
  source_ = nullptr;

  ON_CALL(audio_client_interface_, IsValid).WillByDefault(Return(false));
  ASSERT_EQ(LeAudioClientInterface::Get()->GetSource(*unicast_source_stream_cb_,
                                                     &message_loop_thread, is_broadcast_),
            nullptr);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SetAllowedDsaModesNoUnicastSink) {
  // Release the unicast sink
  LeAudioClientInterface::Get()->ReleaseSink(sink_);
  sink_ = nullptr;
  LeAudioClientInterface::Get()->SetAllowedDsaModes({bluetooth::le_audio::DsaMode::ACL});
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetCodecConfigProviderInfoNoAidl) {
  ON_CALL(hal_version_manager_, GetHalTransport())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::HIDL));
  auto info = LeAudioClientInterface::Get()->GetCodecConfigProviderInfo();
  ASSERT_FALSE(info.has_value());
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkUpdateAudioConfigToHal) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::stream_config config;
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).Times(1);
  sink_->UpdateAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SinkSetCodecPriority) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::types::LeAudioCodecId codec_id;
  sink_->SetCodecPriority(codec_id, 0);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, UpdateBroadcastSinkAudioConfigToHal) {
  ASSERT_NE(nullptr, sink_);
  bluetooth::le_audio::broadcast_offload_config config;
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).Times(1);
  sink_->UpdateBroadcastAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceUpdateAudioConfigToHal) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::stream_config config;
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).Times(1);
  source_->UpdateAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SourceSetCodecPriority) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::types::LeAudioCodecId codec_id;
  source_->SetCodecPriority(codec_id, 0);
}

TEST_F(LeAudioSoftwareBroadcastTestAidl, UpdateBroadcastSourceAudioConfigToHal) {
  ASSERT_NE(nullptr, source_);
  bluetooth::le_audio::broadcast_offload_config config;
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig(testing::_)).Times(1);
  source_->UpdateBroadcastAudioConfigToHal(config);
}

TEST_F(LeAudioSoftwareUnicastTestAidl, SetAllowedDsaModes) {
  LeAudioClientInterface::Get()->SetAllowedDsaModes({bluetooth::le_audio::DsaMode::ACL});
}

TEST_F(LeAudioSoftwareUnicastTestAidl, GetCodecConfigProviderInfo) {
  EXPECT_CALL(audio_client_interface_, GetProviderInfo(testing::_, testing::_))
          .Times(2)
          .WillRepeatedly(Return(std::nullopt));
  auto info = LeAudioClientInterface::Get()->GetCodecConfigProviderInfo();
  ASSERT_FALSE(info.has_value());
}

TEST_F(LeAudioSoftwareUnicastTestHidl, GetOffloadCapabilities) {
  ON_CALL(hal_version_manager_, GetHalTransport())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalTransport::HIDL));
  auto capabilities = bluetooth::audio::le_audio::get_offload_capabilities();
  ASSERT_EQ(capabilities.unicast_offload_capabilities.size(), (size_t)0);
  ASSERT_EQ(capabilities.broadcast_offload_capabilities.size(), (size_t)0);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkSetPcmParameters) {
  ASSERT_NE(nullptr, sink_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  sink_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkSetRemoteDelay) {
  ASSERT_NE(nullptr, sink_);
  sink_->SetRemoteDelay(10);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkStartSession) {
  ASSERT_NE(nullptr, sink_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_2_1));
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig_2_1(testing::_)).WillOnce(Return(true));
  EXPECT_CALL(audio_client_interface_, StartSession_2_1()).WillOnce(Return(0));
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkStartSessionUpdateConfigFail) {
  ASSERT_NE(nullptr, sink_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_2_1));
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig_2_1(testing::_)).WillOnce(Return(false));
  sink_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkStopSession) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_, EndSession()).WillOnce(Return(0));
  sink_->StopSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkRead) {
  ASSERT_NE(nullptr, sink_);
  uint8_t buffer[10];
  EXPECT_CALL(audio_client_interface_.endpoint, ReadAudioData(buffer, 10)).WillOnce(Return(10));
  ASSERT_EQ(sink_->Read(buffer, 10), (size_t)10);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceSetPcmParameters) {
  ASSERT_NE(nullptr, source_);
  LeAudioClientInterface::PcmParameters params = {.data_interval_us = 10000,
                                                  .sample_rate = 16000,
                                                  .bits_per_sample = 16,
                                                  .channels_count = 1};
  source_->SetPcmParameters(params);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceSetRemoteDelay) {
  ASSERT_NE(nullptr, source_);
  source_->SetRemoteDelay(10);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceStartSession) {
  ASSERT_NE(nullptr, source_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_2_1));
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig_2_1(testing::_)).WillOnce(Return(true));
  EXPECT_CALL(audio_client_interface_, StartSession_2_1()).WillOnce(Return(0));
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceStartSessionUpdateConfigFail) {
  ASSERT_NE(nullptr, source_);
  ON_CALL(hal_version_manager_, GetHalVersion())
          .WillByDefault(Return(bluetooth::audio::BluetoothAudioHalVersion::VERSION_2_1));
  EXPECT_CALL(audio_client_interface_, UpdateAudioConfig_2_1(testing::_)).WillOnce(Return(false));
  source_->StartSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceStopSession) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_, EndSession()).WillOnce(Return(0));
  source_->StopSession();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceWrite) {
  ASSERT_NE(nullptr, source_);
  uint8_t buffer[10] = {0};
  EXPECT_CALL(audio_client_interface_.endpoint, WriteAudioData(buffer, 10)).WillOnce(Return(10));
  ASSERT_EQ(source_->Write(buffer, 10), (size_t)10);
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkConfirmStreamingRequest) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::hidl::le_audio::LeAudioSinkTransport::instance;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  sink_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkCancelStreamingRequest) {
  ASSERT_NE(nullptr, sink_);
  auto instance = bluetooth::audio::hidl::le_audio::LeAudioSinkTransport::instance;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::hidl::BluetoothAudioCtrlAck::FAILURE))
          .Times(1);
  sink_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceConfirmStreamingRequest) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::hidl::le_audio::LeAudioSourceTransport::instance;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  source_->ConfirmStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceCancelStreamingRequest) {
  ASSERT_NE(nullptr, source_);
  auto instance = bluetooth::audio::hidl::le_audio::LeAudioSourceTransport::instance;
  instance->SetBluetoothRequestState(
          bluetooth::audio::le_audio::BluetoothRequest::RESUME,
          bluetooth::audio::le_audio::BluetoothRequestState::PENDING_AFTER_REQUEST);
  EXPECT_CALL(audio_client_interface_,
              StreamStarted(bluetooth::audio::hidl::BluetoothAudioCtrlAck::FAILURE))
          .Times(1);
  source_->CancelStreamingRequest();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SinkSuspendedForReconfiguration) {
  ASSERT_NE(nullptr, sink_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  sink_->SuspendedForReconfiguration();
}

TEST_F(LeAudioSoftwareUnicastTestHidl, SourceSuspendedForReconfiguration) {
  ASSERT_NE(nullptr, source_);
  EXPECT_CALL(audio_client_interface_,
              StreamSuspended(bluetooth::audio::hidl::BluetoothAudioCtrlAck::SUCCESS_FINISHED))
          .Times(1);
  source_->SuspendedForReconfiguration();
}

}  // namespace
