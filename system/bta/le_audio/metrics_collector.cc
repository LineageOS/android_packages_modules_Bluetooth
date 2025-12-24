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

#include <bluetooth/log.h>
#include <bluetooth/metrics/os_metrics.h>
#include <bluetooth/types/address.h>

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "hardware/bt_le_audio.h"
#include "le_audio_types.h"

namespace bluetooth::le_audio {

using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::types::LeAudioContextType;

const static metrics::ClockTimePoint kInvalidTimePoint{};

MetricsCollector* MetricsCollector::instance = nullptr;

static int64_t get_timedelta_nanos(const metrics::ClockTimePoint& t1,
                                   const metrics::ClockTimePoint& t2) {
  if (t1 == kInvalidTimePoint || t2 == kInvalidTimePoint) {
    return -1;
  }
  return std::abs(std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t2).count());
}

const static std::unordered_map<LeAudioContextType, LeAudioMetricsContextType> kContextTypeTable = {
        {LeAudioContextType::UNINITIALIZED, LeAudioMetricsContextType::INVALID},
        {LeAudioContextType::UNSPECIFIED, LeAudioMetricsContextType::UNSPECIFIED},
        {LeAudioContextType::CONVERSATIONAL, LeAudioMetricsContextType::COMMUNICATION},
        {LeAudioContextType::MEDIA, LeAudioMetricsContextType::MEDIA},
        {LeAudioContextType::GAME, LeAudioMetricsContextType::GAME},
        {LeAudioContextType::INSTRUCTIONAL, LeAudioMetricsContextType::INSTRUCTIONAL},
        {LeAudioContextType::VOICEASSISTANTS, LeAudioMetricsContextType::MAN_MACHINE},
        {LeAudioContextType::LIVE, LeAudioMetricsContextType::LIVE},
        {LeAudioContextType::SOUNDEFFECTS, LeAudioMetricsContextType::ATTENTION_SEEKING},
        {LeAudioContextType::NOTIFICATIONS, LeAudioMetricsContextType::ATTENTION_SEEKING},
        {LeAudioContextType::RINGTONE, LeAudioMetricsContextType::RINGTONE},
        {LeAudioContextType::ALERTS, LeAudioMetricsContextType::IMMEDIATE_ALERT},
        {LeAudioContextType::EMERGENCYALARM, LeAudioMetricsContextType::EMERGENCY_ALERT},
        {LeAudioContextType::RFU, LeAudioMetricsContextType::RFU},
};

static int32_t to_atom_context_type(const LeAudioContextType stack_type) {
  auto it = kContextTypeTable.find(stack_type);
  if (it != kContextTypeTable.end()) {
    return static_cast<int32_t>(it->second);
  }
  return static_cast<int32_t>(LeAudioMetricsContextType::INVALID);
}

const static std::unordered_map<tGATT_STATUS, ConnectionStatus> kGattStatToConnectionStatusTable = {
        {GATT_SUCCESS, ConnectionStatus::SUCCESS},
        {GATT_INVALID_HANDLE, ConnectionStatus::FAILED_GATT_INVALID_HANDLE},
        {GATT_READ_NOT_PERMIT, ConnectionStatus::FAILED_GATT_READ_NOT_PERMIT},
        {GATT_WRITE_NOT_PERMIT, ConnectionStatus::FAILED_GATT_WRITE_NOT_PERMIT},
        {GATT_INVALID_PDU, ConnectionStatus::FAILED_GATT_INVALID_PDU},
        {GATT_INSUF_AUTHENTICATION, ConnectionStatus::FAILED_GATT_INSUF_AUTHENTICATION},
        {GATT_REQ_NOT_SUPPORTED, ConnectionStatus::FAILED_GATT_REQ_NOT_SUPPORTED},
        {GATT_INVALID_OFFSET, ConnectionStatus::FAILED_GATT_INVALID_OFFSET},
        {GATT_INSUF_AUTHORIZATION, ConnectionStatus::FAILED_GATT_INSUF_AUTHORIZATION},
        {GATT_PREPARE_Q_FULL, ConnectionStatus::FAILED_GATT_PREPARE_Q_FULL},
        {GATT_NOT_FOUND, ConnectionStatus::FAILED_GATT_NOT_FOUND},
        {GATT_NOT_LONG, ConnectionStatus::FAILED_GATT_NOT_LONG},
        {GATT_INSUF_KEY_SIZE, ConnectionStatus::FAILED_GATT_INSUF_KEY_SIZE},
        {GATT_INVALID_ATTR_LEN, ConnectionStatus::FAILED_GATT_INVALID_ATTR_LEN},
        {GATT_ERR_UNLIKELY, ConnectionStatus::FAILED_GATT_ERR_UNLIKELY},
        {GATT_INSUF_ENCRYPTION, ConnectionStatus::FAILED_GATT_INSUF_ENCRYPTION},
        {GATT_UNSUPPORT_GRP_TYPE, ConnectionStatus::FAILED_GATT_UNSUPPORT_GRP_TYPE},
        {GATT_INSUF_RESOURCE, ConnectionStatus::FAILED_GATT_INSUF_RESOURCE},
        {GATT_DATABASE_OUT_OF_SYNC, ConnectionStatus::FAILED_GATT_DATABASE_OUT_OF_SYNC},
        {GATT_VALUE_NOT_ALLOWED, ConnectionStatus::FAILED_GATT_VALUE_NOT_ALLOWED},
        {GATT_ILLEGAL_PARAMETER, ConnectionStatus::FAILED_GATT_ILLEGAL_PARAMETER},
        {GATT_NO_RESOURCES, ConnectionStatus::FAILED_GATT_NO_RESOURCES},
        {GATT_INTERNAL_ERROR, ConnectionStatus::FAILED_GATT_INTERNAL_ERROR},
        {GATT_WRONG_STATE, ConnectionStatus::FAILED_GATT_WRONG_STATE},
        {GATT_DB_FULL, ConnectionStatus::FAILED_GATT_DB_FULL},
        {GATT_BUSY, ConnectionStatus::FAILED_GATT_BUSY},
        {GATT_ERROR, ConnectionStatus::FAILED_GATT_ERROR},
        {GATT_CMD_STARTED, ConnectionStatus::FAILED_GATT_CMD_STARTED},
        {GATT_PENDING, ConnectionStatus::FAILED_GATT_PENDING},
        {GATT_AUTH_FAIL, ConnectionStatus::FAILED_GATT_AUTH_FAIL},
        {GATT_INVALID_CFG, ConnectionStatus::FAILED_GATT_INVALID_CFG},
        {GATT_SERVICE_STARTED, ConnectionStatus::FAILED_GATT_SERVICE_STARTED},
        {GATT_ENCRYPED_NO_MITM, ConnectionStatus::FAILED_GATT_ENCRYPED_NO_MITM},
        {GATT_NOT_ENCRYPTED, ConnectionStatus::FAILED_GATT_NOT_ENCRYPTED},
        {GATT_CONGESTED, ConnectionStatus::FAILED_GATT_CONGESTED},
        {GATT_DUP_REG, ConnectionStatus::FAILED_GATT_DUP_REG},
        {GATT_ALREADY_OPEN, ConnectionStatus::FAILED_GATT_ALREADY_OPEN},
        {GATT_CANCEL, ConnectionStatus::FAILED_GATT_CANCEL},
        {GATT_CONNECTION_TIMEOUT, ConnectionStatus::FAILED_GATT_CONNECTION_TIMEOUT},
        {GATT_CCC_CFG_ERR, ConnectionStatus::FAILED_GATT_CCC_CFG_ERR},
        {GATT_PRC_IN_PROGRESS, ConnectionStatus::FAILED_GATT_PRC_IN_PROGRESS},
        {GATT_OUT_OF_RANGE, ConnectionStatus::FAILED_GATT_OUT_OF_RANGE},
};

ConnectionStatus to_atom_gatt_status(tGATT_STATUS gatt_status) {
  auto it = kGattStatToConnectionStatusTable.find(gatt_status);
  if (it != kGattStatToConnectionStatusTable.end()) {
    return it->second;
  }
  return ConnectionStatus::FAILED;
}

const static std::unordered_map<tBTM_STATUS, ConnectionStatus> kBtmStatToConnectionStatusTable = {
        {tBTM_STATUS::BTM_SUCCESS, ConnectionStatus::SUCCESS},
        {tBTM_STATUS::BTM_CMD_STARTED, ConnectionStatus::FAILED_BTM_CMD_STARTED},
        {tBTM_STATUS::BTM_BUSY, ConnectionStatus::FAILED_BTM_BUSY},
        {tBTM_STATUS::BTM_NO_RESOURCES, ConnectionStatus::FAILED_BTM_NO_RESOURCES},
        {tBTM_STATUS::BTM_MODE_UNSUPPORTED, ConnectionStatus::FAILED_BTM_MODE_UNSUPPORTED},
        {tBTM_STATUS::BTM_ILLEGAL_VALUE, ConnectionStatus::FAILED_BTM_ILLEGAL_VALUE},
        {tBTM_STATUS::BTM_WRONG_MODE, ConnectionStatus::FAILED_BTM_WRONG_MODE},
        {tBTM_STATUS::BTM_UNKNOWN_ADDR, ConnectionStatus::FAILED_BTM_UNKNOWN_ADDR},
        {tBTM_STATUS::BTM_DEVICE_TIMEOUT, ConnectionStatus::FAILED_BTM_DEVICE_TIMEOUT},
        {tBTM_STATUS::BTM_BAD_VALUE_RET, ConnectionStatus::FAILED_BTM_BAD_VALUE_RET},
        {tBTM_STATUS::BTM_ERR_PROCESSING, ConnectionStatus::FAILED_BTM_ERR_PROCESSING},
        {tBTM_STATUS::BTM_NOT_AUTHORIZED, ConnectionStatus::FAILED_BTM_NOT_AUTHORIZED},
        {tBTM_STATUS::BTM_DEV_RESET, ConnectionStatus::FAILED_BTM_DEV_RESET},
        {tBTM_STATUS::BTM_CMD_STORED, ConnectionStatus::FAILED_BTM_CMD_STORED},
        {tBTM_STATUS::BTM_ILLEGAL_ACTION, ConnectionStatus::FAILED_BTM_ILLEGAL_ACTION},
        {tBTM_STATUS::BTM_DELAY_CHECK, ConnectionStatus::FAILED_BTM_DELAY_CHECK},
        {tBTM_STATUS::BTM_SCO_BAD_LENGTH, ConnectionStatus::FAILED_BTM_SCO_BAD_LENGTH},
        {tBTM_STATUS::BTM_SUCCESS_NO_SECURITY, ConnectionStatus::FAILED_BTM_SUCCESS_NO_SECURITY},
        {tBTM_STATUS::BTM_FAILED_ON_SECURITY, ConnectionStatus::FAILED_BTM_FAILED_ON_SECURITY},
        {tBTM_STATUS::BTM_REPEATED_ATTEMPTS, ConnectionStatus::FAILED_BTM_REPEATED_ATTEMPTS},
        {tBTM_STATUS::BTM_MODE4_LEVEL4_NOT_SUPPORTED,
         ConnectionStatus::FAILED_BTM_MODE4_LEVEL4_NOT_SUPPORTED},
        {tBTM_STATUS::BTM_DEV_RESTRICT_LISTED, ConnectionStatus::FAILED_BTM_DEV_RESTRICT_LISTED},
        {tBTM_STATUS::BTM_ERR_KEY_MISSING, ConnectionStatus::FAILED_BTM_ERR_KEY_MISSING},
        {tBTM_STATUS::BTM_NOT_AUTHENTICATED, ConnectionStatus::FAILED_BTM_NOT_AUTHENTICATED},
        {tBTM_STATUS::BTM_NOT_ENCRYPTED, ConnectionStatus::FAILED_BTM_NOT_ENCRYPTED},
        {tBTM_STATUS::BTM_INSUFFICIENT_ENCRYPT_KEY_SIZE,
         ConnectionStatus::FAILED_BTM_INSUFFICIENT_ENCRYPT_KEY_SIZE},
        {tBTM_STATUS::BTM_MAX_STATUS_VALUE,
         ConnectionStatus::FAILED_BTM_MAX_STATUS_VALUE},
        {tBTM_STATUS::BTM_UNDEFINED, ConnectionStatus::FAILED_BTM_UNDEFINED},
};

ConnectionStatus to_atom_btm_status(tBTM_STATUS btm_status) {
  auto it = kBtmStatToConnectionStatusTable.find(btm_status);
  if (it != kBtmStatToConnectionStatusTable.end()) {
    return it->second;
  }
  return ConnectionStatus::FAILED;
}

class DeviceMetrics {
public:
  RawAddress address_;
  metrics::ClockTimePoint connecting_timepoint_ = kInvalidTimePoint;
  metrics::ClockTimePoint connected_timepoint_ = kInvalidTimePoint;
  metrics::ClockTimePoint disconnected_timepoint_ = kInvalidTimePoint;
  int32_t connection_status_ = 0;
  int32_t disconnection_status_ = 0;

  DeviceMetrics(const RawAddress& address) : address_(address) {}

  void AddStateChangedEvent(ConnectionState state, ConnectionStatus status) {
    switch (state) {
      case ConnectionState::CONNECTING:
        connecting_timepoint_ = std::chrono::high_resolution_clock::now();
        break;
      case ConnectionState::CONNECTED:
        connected_timepoint_ = std::chrono::high_resolution_clock::now();
        connection_status_ = static_cast<int32_t>(status);
        break;
      case ConnectionState::DISCONNECTED:
        disconnected_timepoint_ = std::chrono::high_resolution_clock::now();
        disconnection_status_ = static_cast<int32_t>(status);
        break;
      case ConnectionState::DISCONNECTING:
        // Ignore
        break;
    }
  }
};

class GroupMetricsImpl : public GroupMetrics {
private:
  static constexpr int32_t kInvalidGroupId = -1;
  int32_t group_id_;
  int32_t group_size_;
  std::vector<std::unique_ptr<DeviceMetrics>> device_metrics_;
  std::unordered_map<RawAddress, DeviceMetrics*> opened_devices_;
  metrics::ClockTimePoint beginning_timepoint_;
  std::vector<int64_t> streaming_offset_nanos_;
  std::vector<int64_t> streaming_duration_nanos_;
  std::vector<int32_t> streaming_context_type_;
  std::vector<int32_t> codec_format_;
  std::vector<int32_t> vendor_company_id_;
  std::vector<int32_t> vendor_codec_id_;
  std::vector<int32_t> sink_sampling_frequency_hz_;
  std::vector<int32_t> source_sampling_frequency_hz_;
  std::vector<bool> is_dsa_active_;
  std::vector<bool> is_gmap_active_;

public:
  GroupMetricsImpl() : group_id_(kInvalidGroupId), group_size_(0) {
    beginning_timepoint_ = std::chrono::high_resolution_clock::now();
  }
  GroupMetricsImpl(int32_t group_id, int32_t group_size)
      : group_id_(group_id), group_size_(group_size) {
    beginning_timepoint_ = std::chrono::high_resolution_clock::now();
  }

  void AddStateChangedEvent(const RawAddress& address, bluetooth::le_audio::ConnectionState state,
                            ConnectionStatus status) override {
    auto it = opened_devices_.find(address);
    if (it == opened_devices_.end()) {
      device_metrics_.push_back(std::make_unique<DeviceMetrics>(address));
      it = opened_devices_.insert(std::begin(opened_devices_),
                                  {address, device_metrics_.back().get()});
    }
    it->second->AddStateChangedEvent(state, status);
    if (state == bluetooth::le_audio::ConnectionState::DISCONNECTED ||
        (state == bluetooth::le_audio::ConnectionState::CONNECTED &&
         status != ConnectionStatus::SUCCESS)) {
      opened_devices_.erase(it);
    }
  }

  void AddStreamStartedEvent(bluetooth::le_audio::types::LeAudioContextType context_type,
                           const LeAudioMetricsCodecInfo& info) override {
    int32_t atom_context_type = to_atom_context_type(context_type);
    // Make sure events aligned
    // Check if the context type or codec info(Codec, DSA, GMAP)
    //    have changed while the stream is active.
    // If so, we implicitly split the session
    //    by ending the current stream segment and starting a new one.
    if (streaming_offset_nanos_.size() - streaming_duration_nanos_.size() != 0) {
      // Allow type switching. If the stream is active, vectors are not empty.
      if (streaming_context_type_.back() != atom_context_type ||
          codec_format_.back() != info.codec_format ||
          vendor_company_id_.back() != info.vendor_company_id ||
          vendor_codec_id_.back() != info.vendor_codec_id ||
          static_cast<uint32_t>(sink_sampling_frequency_hz_.back()) !=
                  info.sink_sampling_frequency_hz ||
          static_cast<uint32_t>(source_sampling_frequency_hz_.back()) !=
                  info.source_sampling_frequency_hz ||
          is_dsa_active_.back() != info.is_dsa_active ||
          is_gmap_active_.back() != info.is_gmap_active) {
        AddStreamEndedEvent();
      } else {
        return;
      }
    }
    streaming_offset_nanos_.push_back(
            get_timedelta_nanos(std::chrono::high_resolution_clock::now(), beginning_timepoint_));
    streaming_context_type_.push_back(atom_context_type);
    codec_format_.push_back(info.codec_format);
    vendor_company_id_.push_back(info.vendor_company_id);
    vendor_codec_id_.push_back(info.vendor_codec_id);
    sink_sampling_frequency_hz_.push_back(info.sink_sampling_frequency_hz);
    source_sampling_frequency_hz_.push_back(info.source_sampling_frequency_hz);
    is_dsa_active_.push_back(info.is_dsa_active);
    is_gmap_active_.push_back(info.is_gmap_active);
  }

  void AddStreamEndedEvent() override {
    // Make sure events aligned
    if (streaming_offset_nanos_.size() - streaming_duration_nanos_.size() != 1) {
      return;
    }
    streaming_duration_nanos_.push_back(
            get_timedelta_nanos(std::chrono::high_resolution_clock::now(), beginning_timepoint_) -
            streaming_offset_nanos_.back());
  }

  void SetGroupSize(int32_t group_size) override { group_size_ = group_size; }

  bool IsClosed() override { return opened_devices_.empty(); }

  void WriteStats() override {
    int64_t connection_duration_nanos =
            get_timedelta_nanos(beginning_timepoint_, std::chrono::high_resolution_clock::now());

    int len = device_metrics_.size();
    std::vector<int64_t> device_connecting_offset_nanos(len);
    std::vector<int64_t> device_connected_offset_nanos(len);
    std::vector<int64_t> device_connection_duration_nanos(len);
    std::vector<int32_t> device_connection_statuses(len);
    std::vector<int32_t> device_disconnection_statuses(len);
    std::vector<RawAddress> device_address(len);

    while (streaming_duration_nanos_.size() < streaming_offset_nanos_.size()) {
      AddStreamEndedEvent();
    }

    for (int i = 0; i < len; i++) {
      auto device_metric = device_metrics_[i].get();
      device_connecting_offset_nanos[i] =
              get_timedelta_nanos(device_metric->connecting_timepoint_, beginning_timepoint_);
      device_connected_offset_nanos[i] =
              get_timedelta_nanos(device_metric->connected_timepoint_, beginning_timepoint_);
      device_connection_duration_nanos[i] = get_timedelta_nanos(
              device_metric->disconnected_timepoint_, device_metric->connected_timepoint_);
      device_connection_statuses[i] = device_metric->connection_status_;
      device_disconnection_statuses[i] = device_metric->disconnection_status_;
      device_address[i] = device_metric->address_;
    }

    bluetooth::metrics::LogMetricLeAudioConnectionSessionReported(
            group_size_, group_id_, connection_duration_nanos, device_connecting_offset_nanos,
            device_connected_offset_nanos, device_connection_duration_nanos,
            device_connection_statuses, device_disconnection_statuses, device_address,
            streaming_offset_nanos_, streaming_duration_nanos_, streaming_context_type_,
            codec_format_, vendor_company_id_, vendor_codec_id_,
            sink_sampling_frequency_hz_, source_sampling_frequency_hz_,
            is_dsa_active_, is_gmap_active_);
  }

  void Flush() {
    for (auto& p : opened_devices_) {
      p.second->AddStateChangedEvent(bluetooth::le_audio::ConnectionState::DISCONNECTED,
                                     ConnectionStatus::SUCCESS);
    }
    WriteStats();
  }
};

/* Metrics Colloctor */

MetricsCollector* MetricsCollector::Get() {
  if (MetricsCollector::instance == nullptr) {
    MetricsCollector::instance = new MetricsCollector();
  }
  return MetricsCollector::instance;
}

void MetricsCollector::OnGroupSizeUpdate(int32_t group_id, int32_t group_size) {
  group_size_table_[group_id] = group_size;
  auto it = opened_groups_.find(group_id);
  if (it != opened_groups_.end()) {
    it->second->SetGroupSize(group_size);
  }
}

void MetricsCollector::OnConnectionStateChanged(int32_t group_id, const RawAddress& address,
                                                bluetooth::le_audio::ConnectionState state,
                                                ConnectionStatus status) {
  if (address.IsEmpty() || group_id <= 0) {
    return;
  }
  auto it = opened_groups_.find(group_id);
  if (it == opened_groups_.end()) {
    it = opened_groups_.insert(
            std::begin(opened_groups_),
            {group_id, std::make_unique<GroupMetricsImpl>(group_id, group_size_table_[group_id])});
  }
  it->second->AddStateChangedEvent(address, state, status);

  if (it->second->IsClosed()) {
    it->second->WriteStats();
    opened_groups_.erase(it);
  }
}

void MetricsCollector::OnStreamStarted(
        int32_t group_id, bluetooth::le_audio::types::LeAudioContextType context_type,
        const LeAudioMetricsCodecInfo& info) {
  if (group_id <= 0) {
    return;
  }
  auto it = opened_groups_.find(group_id);
  if (it != opened_groups_.end()) {
    it->second->AddStreamStartedEvent(context_type, info);
  }
}

void MetricsCollector::OnStreamEnded(int32_t group_id) {
  if (group_id <= 0) {
    return;
  }
  auto it = opened_groups_.find(group_id);
  if (it != opened_groups_.end()) {
    it->second->AddStreamEndedEvent();
  }
}

void MetricsCollector::OnBroadcastStateChanged(bool started) {
  if (started) {
    broadcast_beginning_timepoint_ = std::chrono::high_resolution_clock::now();
  } else {
    auto broadcast_ending_timepoint_ = std::chrono::high_resolution_clock::now();
    bluetooth::metrics::LogMetricLeAudioBroadcastSessionReported(
            get_timedelta_nanos(broadcast_beginning_timepoint_, broadcast_ending_timepoint_));
    broadcast_beginning_timepoint_ = kInvalidTimePoint;
  }
}

void MetricsCollector::Flush() {
  log::info("");
  for (auto& p : opened_groups_) {
    p.second->Flush();
  }
  opened_groups_.clear();
}

}  // namespace bluetooth::le_audio
