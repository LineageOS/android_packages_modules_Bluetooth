/*
 * Copyright 2022 The Android Open Source Project
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

#include "metrics_collector.h"

#include <bluetooth/metrics/os_metrics.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include <cstdint>
#include <vector>

#include "metrics/mock/metrics_mock.h"
#include "types/raw_address.h"

using testing::_;
using testing::AnyNumber;
using testing::AtLeast;
using testing::AtMost;
using testing::DoAll;
using testing::ElementsAre;
using testing::Gt;
using testing::Invoke;
using testing::IsEmpty;
using testing::Mock;
using testing::MockFunction;
using testing::NotNull;
using testing::Return;
using testing::SaveArg;
using testing::SetArgPointee;
using testing::Test;
using testing::WithArg;

namespace bluetooth::le_audio {

const int32_t group_id1 = 1;
const RawAddress device1 = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});

const int32_t group_id2 = 2;
const RawAddress device2 = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x67});
const RawAddress device3 = RawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x68});

class MockMetricsCollector : public MetricsCollector {
public:
  MockMetricsCollector() {}
};

class MetricsCollectorTest : public Test {
protected:
  std::unique_ptr<MetricsCollector> collector;
  std::shared_ptr<::bluetooth::metrics::MockMetrics> metrics;

  void SetUp() override {
    collector = std::make_unique<MockMetricsCollector>();
    metrics = std::make_shared<::bluetooth::metrics::MockMetrics>();
    ::bluetooth::metrics::MockMetrics::SetInstance(metrics);
  }

  void TearDown() override {
    ::bluetooth::metrics::MockMetrics::SetInstance(nullptr);
    metrics = nullptr;
    collector = nullptr;
  }
};

TEST_F(MetricsCollectorTest, Initialize) {}

TEST_F(MetricsCollectorTest, ConnectionFailed) {
  EXPECT_CALL(*metrics, LogMetricLeAudioConnectionSessionReported(
                                _, group_id1, _, _, _, _, ElementsAre(ConnectionStatus::FAILED),
                                ElementsAre(_), ElementsAre(device1), _, _, _))
          .Times(1);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::FAILED);
}

TEST_F(MetricsCollectorTest, ConnectingConnectedDisconnected) {
  EXPECT_CALL(*metrics, LogMetricLeAudioConnectionSessionReported(
                                _, group_id1, _, ElementsAre(_), ElementsAre(_), ElementsAre(_),
                                ElementsAre(ConnectionStatus::SUCCESS),
                                ElementsAre(ConnectionStatus::SUCCESS), ElementsAre(device1),
                                IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(1);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, SingleDeviceTwoConnections) {
  EXPECT_CALL(*metrics, LogMetricLeAudioConnectionSessionReported(
                                _, group_id1, _, ElementsAre(_), ElementsAre(_), ElementsAre(_),
                                ElementsAre(ConnectionStatus::SUCCESS),
                                ElementsAre(ConnectionStatus::SUCCESS), ElementsAre(device1),
                                IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(2);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, StereoGroupBasicTest) {
  EXPECT_CALL(*metrics,
              LogMetricLeAudioConnectionSessionReported(
                      _, group_id2, _, ElementsAre(_, _), ElementsAre(_, _), ElementsAre(_, _),
                      ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS),
                      ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS),
                      ElementsAre(device2, device3), IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(1);

  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, StereoGroupMultiReconnections) {
  EXPECT_CALL(
          *metrics,
          LogMetricLeAudioConnectionSessionReported(
                  _, group_id2, _, ElementsAre(_, _, _), ElementsAre(_, _, _), ElementsAre(_, _, _),
                  ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS,
                              ConnectionStatus::SUCCESS),
                  ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS,
                              ConnectionStatus::SUCCESS),
                  ElementsAre(device2, device3, device3), IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(1);

  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, MixGroups) {
  EXPECT_CALL(*metrics,
              LogMetricLeAudioConnectionSessionReported(
                      _, group_id2, _, ElementsAre(_, _), ElementsAre(_, _), ElementsAre(_, _),
                      ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS),
                      ElementsAre(ConnectionStatus::SUCCESS, ConnectionStatus::SUCCESS),
                      ElementsAre(device2, device3), IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(1);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device3,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id2, device2,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);

  EXPECT_CALL(*metrics, LogMetricLeAudioConnectionSessionReported(
                                _, group_id1, _, ElementsAre(_), ElementsAre(_), ElementsAre(_),
                                ElementsAre(ConnectionStatus::SUCCESS),
                                ElementsAre(ConnectionStatus::SUCCESS), ElementsAre(device1),
                                IsEmpty(), IsEmpty(), IsEmpty()))
          .Times(1);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, GroupSizeUpdated) {
  EXPECT_CALL(*metrics,
              LogMetricLeAudioConnectionSessionReported(2, group_id1, _, _, _, _, _, _, _, _, _, _))
          .Times(1);

  collector->OnGroupSizeUpdate(group_id2, 1);
  collector->OnGroupSizeUpdate(group_id1, 2);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, StreamingSessions) {
  EXPECT_CALL(*metrics,
              LogMetricLeAudioConnectionSessionReported(
                      _, group_id1, _, _, _, _, _, _, _, ElementsAre(_, _), ElementsAre(_, _),
                      ElementsAre(static_cast<int32_t>(LeAudioMetricsContextType::MEDIA),
                                  static_cast<int32_t>(LeAudioMetricsContextType::COMMUNICATION))))
          .Times(1);

  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTING,
                                      ConnectionStatus::UNKNOWN);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::CONNECTED,
                                      ConnectionStatus::SUCCESS);
  collector->OnStreamStarted(group_id1, bluetooth::le_audio::types::LeAudioContextType::MEDIA);
  collector->OnStreamEnded(group_id1);
  collector->OnStreamStarted(group_id1,
                             bluetooth::le_audio::types::LeAudioContextType::CONVERSATIONAL);
  collector->OnStreamEnded(group_id1);
  collector->OnConnectionStateChanged(group_id1, device1,
                                      bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                      ConnectionStatus::SUCCESS);
}

TEST_F(MetricsCollectorTest, BroadastSessions) {
  EXPECT_CALL(*metrics, LogMetricLeAudioBroadcastSessionReported(Gt(0))).Times(1);
  collector->OnBroadcastStateChanged(true);
  collector->OnBroadcastStateChanged(false);

  EXPECT_CALL(*metrics, LogMetricLeAudioBroadcastSessionReported(Gt(0))).Times(1);
  collector->OnBroadcastStateChanged(true);
  collector->OnBroadcastStateChanged(false);
}

}  // namespace bluetooth::le_audio
