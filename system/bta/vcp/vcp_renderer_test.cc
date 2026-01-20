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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/vcp/vcs_server.h"
#include "bta_vcp_renderer_api.h"

using ::testing::_;
using ::testing::DoAll;
using ::testing::InSequence;
using ::testing::NiceMock;
using ::testing::SaveArg;

namespace bluetooth::vcp {

static RawAddress GetTestAddress(uint8_t index) {
  EXPECT_LT(index, UINT8_MAX);
  std::array<uint8_t, 6> bytes{0xC0, 0xDE, 0xC0, 0xDE, 0x00, index};
  return RawAddress(bytes);
}

class MockVolumeRendererCallbacks : public VolumeRendererCallbacks {
public:
  // clang-format off
  MOCK_METHOD((void), OnInitialized, (), (override));
  MOCK_METHOD((void), OnGattConnectionStateChanged,
              (const RawAddress& address, GattConnectionState state), (override));
  MOCK_METHOD((void), OnVolumeStateChangeRequest, (uint8_t volume, MuteState mute_state),
              (override));
  // clang-format on
};

class MockVcsServer : public vcs::VcsServer {
public:
  MockVcsServer() : vcs::VcsServer() {}
  // clang-format off
  MOCK_METHOD((void), RegisterGattService,
              (const ServiceDescriptor& service_descriptor, Callbacks* callbacks), (override));
  MOCK_METHOD((void), UpdateVolumeState, (uint8_t volume, vcs::MuteState mute_state), (override));
  MOCK_METHOD((void), UpdateVolumeFlags, (const vcs::VolumeFlags& flags), (override));
  MOCK_METHOD((void), Dump, (std::stringstream & stream), (const, override));
  // clang-format on
};

class TestVcpServicesFactory : public VcpServicesFactory {
public:
  TestVcpServicesFactory(std::shared_ptr<vcs::VcsServer> vcs) : vcs_(vcs) {}

  // clang-format off
  MOCK_METHOD((std::shared_ptr<vcs::VcsServer>), InstantiateVcsServer, (), (override));
  MOCK_METHOD((void), ReleaseVcsServer, (std::shared_ptr<vcs::VcsServer> vcs), (override));
  // clang-format on

  std::shared_ptr<vcs::VcsServer> DoInstantiateVcsServer() { return vcs_; }

private:
  std::shared_ptr<vcs::VcsServer> vcs_;
};

class VolumeRendererTest : public ::testing::Test {
protected:
  void SetUp() override {
    callbacks_ = std::make_unique<MockVolumeRendererCallbacks>();
    vcs_server_ = std::make_shared<NiceMock<MockVcsServer>>();
    config_.initial_volume = 100;
    config_.initial_mute_state = MuteState::NOT_MUTED;
    config_.initial_volume_setting_persisted = VolumeSettingPersisted::RESET_VOLUME_SETTING;
    config_.volume_step_size = 10;
  }

  void TearDown() override {
    if (VolumeRenderer::Get()) {
      VolumeRenderer::Cleanup();
    }
    callbacks_.reset();
    vcs_server_.reset();
    vcp_renderer_ = nullptr;
    factory_.reset();
    vcs_callbacks_ = nullptr;
  }

  void Initialize() {
    factory_ = std::make_unique<NiceMock<TestVcpServicesFactory>>(vcs_server_);
    ON_CALL(*factory_, InstantiateVcsServer())
            .WillByDefault(testing::Invoke(factory_.get(),
                                           &TestVcpServicesFactory::DoInstantiateVcsServer));

    EXPECT_CALL(*factory_, InstantiateVcsServer());

    EXPECT_CALL(*vcs_server_, RegisterGattService(_, _))
            .WillOnce(DoAll(SaveArg<0>(&vcs_config_), SaveArg<1>(&vcs_callbacks_)));

    VolumeRenderer::Initialize(callbacks_.get(), config_, factory_.get());
    vcp_renderer_ = VolumeRenderer::Get();
    ASSERT_NE(vcp_renderer_, nullptr);
  }

  std::unique_ptr<MockVolumeRendererCallbacks> callbacks_;
  std::shared_ptr<MockVcsServer> vcs_server_;
  std::unique_ptr<TestVcpServicesFactory> factory_;
  VolumeRendererConfig config_;

  VolumeRenderer* vcp_renderer_ = nullptr;
  vcs::VcsServer::Callbacks* vcs_callbacks_ = nullptr;
  vcs::VcsServer::ServiceDescriptor vcs_config_;
};

TEST_F(VolumeRendererTest, initializeAndCleanup) {
  // Before initialization, Get() should assert.
  EXPECT_DEATH(VolumeRenderer::Get(), "");

  InSequence s;
  Initialize();
  EXPECT_CALL(*callbacks_, OnInitialized());
  ASSERT_NE(vcs_callbacks_, nullptr);
  vcs_callbacks_->OnVcsServerRegistered();

  // Double initialize should be a no-op
  VolumeRenderer::Initialize(callbacks_.get(), config_, factory_.get());

  EXPECT_CALL(*factory_, ReleaseVcsServer(_));

  VolumeRenderer::Cleanup();

  // After cleanup, Get() should assert.
  EXPECT_DEATH(VolumeRenderer::Get(), "");

  Initialize();  // To not assert on TearDown
}

TEST_F(VolumeRendererTest, initializeAndConfigValidation) {
  InSequence s;
  Initialize();

  EXPECT_EQ(vcs_config_.step_size, config_.volume_step_size);
  EXPECT_EQ(vcs_config_.initial_volume, config_.initial_volume);
  EXPECT_EQ(vcs_config_.initial_mute_state,
            static_cast<vcs::MuteState>(config_.initial_mute_state));
  EXPECT_EQ(vcs_config_.initial_volume_setting_persisted,
            static_cast<vcs::VolumeSettingPersisted>(config_.initial_volume_setting_persisted));

  EXPECT_CALL(*callbacks_, OnInitialized());
  ASSERT_NE(vcs_callbacks_, nullptr);
  vcs_callbacks_->OnVcsServerRegistered();

  EXPECT_CALL(*factory_, ReleaseVcsServer(_));
}

TEST_F(VolumeRendererTest, connectionStateChanged) {
  Initialize();
  RawAddress addr = GetTestAddress(1);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnGattConnectionStateChanged(addr, GattConnectionState::CONNECTED));
  vcs_callbacks_->OnDeviceConnected(addr);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnGattConnectionStateChanged(addr, GattConnectionState::DISCONNECTED));
  vcs_callbacks_->OnDeviceDisconnected(addr);
}

TEST_F(VolumeRendererTest, onVolumeStateChange) {
  Initialize();
  RawAddress addr = GetTestAddress(1);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnVolumeStateChangeRequest(123, MuteState::MUTED));
  vcs_callbacks_->OnVolumeStateChangeRequest(addr, 123, vcs::MuteState::kMuted);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnVolumeStateChangeRequest(100, MuteState::NOT_MUTED));
  vcs_callbacks_->OnVolumeStateChangeRequest(addr, 100, vcs::MuteState::kNotMuted);
}

TEST_F(VolumeRendererTest, updateVolumeState) {
  Initialize();

  EXPECT_CALL(*vcs_server_, UpdateVolumeState(123, vcs::MuteState::kMuted));
  vcp_renderer_->UpdateVolumeState(123, MuteState::MUTED);

  EXPECT_CALL(*vcs_server_, UpdateVolumeState(100, vcs::MuteState::kNotMuted));
  vcp_renderer_->UpdateVolumeState(100, MuteState::NOT_MUTED);
}

TEST_F(VolumeRendererTest, updateVolumeFlags) {
  Initialize();

  EXPECT_CALL(*vcs_server_, UpdateVolumeFlags(_));
  VolumeFlags flags;
  flags.bits.volume_setting_persisted = VolumeSettingPersisted::USER_SET_VOLUME_SETTING;
  vcp_renderer_->UpdateVolumeFlags(flags);
}

TEST_F(VolumeRendererTest, dump) {
  Initialize();
  std::stringstream stream;

  EXPECT_CALL(*vcs_server_, Dump(_));
  vcp_renderer_->DebugDump(fileno(tmpfile()));
}

TEST_F(VolumeRendererTest, doubleConnectIsNoop) {
  Initialize();
  RawAddress addr = GetTestAddress(1);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnGattConnectionStateChanged(addr, GattConnectionState::CONNECTED))
          .Times(1);
  vcs_callbacks_->OnDeviceConnected(addr);
  vcs_callbacks_->OnDeviceConnected(addr);
}

TEST_F(VolumeRendererTest, disconnectNotConnectedIsNoop) {
  Initialize();
  RawAddress addr = GetTestAddress(1);

  ASSERT_NE(vcs_callbacks_, nullptr);
  EXPECT_CALL(*callbacks_, OnGattConnectionStateChanged(_, _)).Times(0);
  vcs_callbacks_->OnDeviceDisconnected(addr);
}

}  // namespace bluetooth::vcp
