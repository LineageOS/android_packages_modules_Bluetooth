/******************************************************************************
 *
 *  Copyright 2021 Google, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#define LOG_TAG "BluetoothMetrics"

#include "os/metrics.h"

#include <Counter.h>
#include <bluetooth/log.h>
#include <statslog_bt.h>

#include "a2dp_constants.h"
#include "common/audit_log.h"
#include "common/metric_id_manager.h"
#include "common/strings.h"
#include "hardware/bt_av.h"
#include "hci/hci_packets.h"
#include "main/shim/helpers.h"

namespace std {
template <>
struct formatter<android::bluetooth::DirectionEnum>
    : enum_formatter<android::bluetooth::DirectionEnum> {};
template <>
struct formatter<android::bluetooth::SocketConnectionstateEnum>
    : enum_formatter<android::bluetooth::SocketConnectionstateEnum> {};
template <>
struct formatter<android::bluetooth::SocketRoleEnum>
    : enum_formatter<android::bluetooth::SocketRoleEnum> {};
template <>
struct formatter<android::bluetooth::DeviceInfoSrcEnum>
    : enum_formatter<android::bluetooth::DeviceInfoSrcEnum> {};
template <>
struct formatter<android::bluetooth::AddressTypeEnum>
    : enum_formatter<android::bluetooth::AddressTypeEnum> {};
template <>
struct formatter<android::bluetooth::EventType> : enum_formatter<android::bluetooth::EventType> {};
template <>
struct formatter<android::bluetooth::State> : enum_formatter<android::bluetooth::State> {};
template <>
struct formatter<android::bluetooth::rfcomm::PortResult>
    : enum_formatter<android::bluetooth::rfcomm::PortResult> {};
template <>
struct formatter<android::bluetooth::rfcomm::RfcommPortState>
    : enum_formatter<android::bluetooth::rfcomm::RfcommPortState> {};
template <>
struct formatter<android::bluetooth::rfcomm::RfcommPortEvent>
    : enum_formatter<android::bluetooth::rfcomm::RfcommPortEvent> {};
template <>
struct formatter<android::bluetooth::rfcomm::SocketConnectionSecurity>
    : enum_formatter<android::bluetooth::rfcomm::SocketConnectionSecurity> {};
template <>
struct formatter<android::bluetooth::BtaStatus> : enum_formatter<android::bluetooth::BtaStatus> {};
}  // namespace std

namespace bluetooth {
namespace os {

using bluetooth::common::MetricIdManager;
using bluetooth::hci::Address;
using bluetooth::hci::ErrorCode;
using bluetooth::hci::EventCode;

/**
 * nullptr and size 0 represent missing value for obfuscated_id
 */
static const BytesField byteField(nullptr, 0);

void LogMetricLinkLayerConnectionEvent(const Address* address, uint32_t connection_handle,
                                       android::bluetooth::DirectionEnum direction,
                                       uint16_t link_type, uint32_t hci_cmd, uint16_t hci_event,
                                       uint16_t hci_ble_event, uint16_t cmd_status,
                                       uint16_t reason_code) {
  int metric_id = 0;
  if (address != nullptr) {
    metric_id = MetricIdManager::GetInstance().AllocateId(*address);
  }
  int ret = stats_write(BLUETOOTH_LINK_LAYER_CONNECTION_EVENT, byteField, connection_handle,
                        direction, link_type, hci_cmd, hci_event, hci_ble_event, cmd_status,
                        reason_code, metric_id);
  if (ret < 0) {
    log::warn(
            "Failed to log status {} , reason {}, from cmd {}, event {},  ble_event {}, for {}, "
            "handle "
            "{}, type {}, error {}",
            common::ToHexString(cmd_status), common::ToHexString(reason_code),
            common::ToHexString(hci_cmd), common::ToHexString(hci_event),
            common::ToHexString(hci_ble_event),
            address ? address->ToRedactedStringForLogging() : "(NULL)", connection_handle,
            common::ToHexString(link_type), ret);
  }
}

void LogMetricHciTimeoutEvent(uint32_t hci_cmd) {
  int ret = stats_write(BLUETOOTH_HCI_TIMEOUT_REPORTED, static_cast<int64_t>(hci_cmd));
  if (ret < 0) {
    log::warn("Failed for opcode {}, error {}", common::ToHexString(hci_cmd), ret);
  }
}

void LogMetricRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                                uint16_t manufacturer_name, uint16_t subversion) {
  int ret = stats_write(BLUETOOTH_REMOTE_VERSION_INFO_REPORTED, handle, status, version,
                        manufacturer_name, subversion);
  if (ret < 0) {
    log::warn(
            "Failed for handle {}, status {}, version {}, manufacturer_name {}, subversion {}, "
            "error "
            "{}",
            handle, common::ToHexString(status), common::ToHexString(version),
            common::ToHexString(manufacturer_name), common::ToHexString(subversion), ret);
  }
}

void LogMetricA2dpAudioUnderrunEvent(const Address& address, uint64_t encoding_interval_millis,
                                     int num_missing_pcm_bytes) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int64_t encoding_interval_nanos = encoding_interval_millis * 1000000;
  int ret = stats_write(BLUETOOTH_A2DP_AUDIO_UNDERRUN_REPORTED, byteField, encoding_interval_nanos,
                        num_missing_pcm_bytes, metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, encoding_interval_nanos {}, num_missing_pcm_bytes {}, error {}",
              address, encoding_interval_nanos, num_missing_pcm_bytes, ret);
  }
}

void LogMetricA2dpAudioOverrunEvent(const Address& address, uint64_t encoding_interval_millis,
                                    int num_dropped_buffers, int num_dropped_encoded_frames,
                                    int num_dropped_encoded_bytes) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }

  int64_t encoding_interval_nanos = encoding_interval_millis * 1000000;
  int ret = stats_write(BLUETOOTH_A2DP_AUDIO_OVERRUN_REPORTED, byteField, encoding_interval_nanos,
                        num_dropped_buffers, num_dropped_encoded_frames, num_dropped_encoded_bytes,
                        metric_id);
  if (ret < 0) {
    log::warn(
            "Failed to log for {}, encoding_interval_nanos {}, num_dropped_buffers {}, "
            "num_dropped_encoded_frames {}, num_dropped_encoded_bytes {}, error {}",
            address, encoding_interval_nanos, num_dropped_buffers, num_dropped_encoded_frames,
            num_dropped_encoded_bytes, ret);
  }
}

void LogMetricA2dpPlaybackEvent(const Address& address, int playback_state, int audio_coding_mode) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }

  int ret = stats_write(BLUETOOTH_A2DP_PLAYBACK_STATE_CHANGED, byteField, playback_state,
                        audio_coding_mode, metric_id);
  if (ret < 0) {
    log::warn("Failed to log for {}, playback_state {}, audio_coding_mode {},error {}", address,
              playback_state, audio_coding_mode, ret);
  }
}

void LogMetricA2dpSessionMetricsEvent(const hci::Address& address, int64_t audio_duration_ms,
                                      int media_timer_min_ms, int media_timer_max_ms,
                                      int media_timer_avg_ms, int total_scheduling_count,
                                      int buffer_overruns_max_count, int buffer_overruns_total,
                                      float buffer_underruns_average, int buffer_underruns_count,
                                      int64_t codec_index, bool is_a2dp_offload) {
  char const* expresslog_metric_id;
  a2dp::CodecId codec_id;
  switch (codec_index) {
    case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
      expresslog_metric_id = "bluetooth.value_sbc_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::SBC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
      expresslog_metric_id = "bluetooth.value_aac_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::AAC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
      expresslog_metric_id = "bluetooth.value_aptx_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::APTX;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
      expresslog_metric_id = "bluetooth.value_aptx_hd_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::APTX_HD;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
      expresslog_metric_id = "bluetooth.value_ldac_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::LDAC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
      expresslog_metric_id = "bluetooth.value_opus_codec_usage_over_a2dp";
      codec_id = a2dp::CodecId::OPUS;
      break;
    default:
      return;
  }

  android::expresslog::Counter::logIncrement(expresslog_metric_id);

  int32_t metric_id = MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(A2DP_SESSION_REPORTED, audio_duration_ms, media_timer_min_ms,
                        media_timer_max_ms, media_timer_avg_ms, total_scheduling_count,
                        buffer_overruns_max_count, buffer_overruns_total, buffer_underruns_average,
                        buffer_underruns_count, static_cast<uint64_t>(codec_id), is_a2dp_offload,
                        metric_id);

  if (ret < 0) {
    log::warn("failed to log a2dp_session_reported");
  }
}

void LogMetricHfpPacketLossStats(const Address& /* address */, int /* num_decoded_frames */,
                                 double /* packet_loss_ratio */, uint16_t /* codec_type */) {}

void LogMetricMmcTranscodeRttStats(int /*maximum_rtt*/, double /*mean_rtt*/, int /*num_requests*/,
                                   int /*codec_type*/) {}

void LogMetricReadRssiResult(const Address& address, uint16_t handle, uint32_t cmd_status,
                             int8_t rssi) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_DEVICE_RSSI_REPORTED, byteField, handle, cmd_status, rssi,
                        metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, handle {}, status {}, rssi {} dBm, error {}", address, handle,
              common::ToHexString(cmd_status), rssi, ret);
  }
}

void LogMetricReadFailedContactCounterResult(const Address& address, uint16_t handle,
                                             uint32_t cmd_status, int32_t failed_contact_counter) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_DEVICE_FAILED_CONTACT_COUNTER_REPORTED, byteField, handle,
                        cmd_status, failed_contact_counter, metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, handle {}, status {}, failed_contact_counter {} packets, error {}",
              address, handle, common::ToHexString(cmd_status), failed_contact_counter, ret);
  }
}

void LogMetricReadTxPowerLevelResult(const Address& address, uint16_t handle, uint32_t cmd_status,
                                     int32_t transmit_power_level) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_DEVICE_TX_POWER_LEVEL_REPORTED, byteField, handle, cmd_status,
                        transmit_power_level, metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, handle {}, status {}, transmit_power_level {} packets, error {}",
              address, handle, common::ToHexString(cmd_status), transmit_power_level, ret);
  }
}

void LogMetricSmpPairingEvent(const Address& address, uint16_t smp_cmd,
                              android::bluetooth::DirectionEnum direction,
                              uint16_t smp_fail_reason) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_SMP_PAIRING_EVENT_REPORTED, byteField, smp_cmd, direction,
                        smp_fail_reason, metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, smp_cmd {}, direction {}, smp_fail_reason {}, error {}", address,
              common::ToHexString(smp_cmd), direction, common::ToHexString(smp_fail_reason), ret);
  }
}

void LogMetricClassicPairingEvent(const Address& address, uint16_t handle, uint32_t hci_cmd,
                                  uint16_t hci_event, uint16_t cmd_status, uint16_t reason_code,
                                  int64_t event_value) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_CLASSIC_PAIRING_EVENT_REPORTED, byteField, handle, hci_cmd,
                        hci_event, cmd_status, reason_code, event_value, metric_id);
  if (ret < 0) {
    log::warn(
            "Failed for {}, handle {}, hci_cmd {}, hci_event {}, cmd_status {}, reason {}, "
            "event_value "
            "{}, error {}",
            address, handle, common::ToHexString(hci_cmd), common::ToHexString(hci_event),
            common::ToHexString(cmd_status), common::ToHexString(reason_code), event_value, ret);
  }

  if (static_cast<EventCode>(hci_event) == EventCode::SIMPLE_PAIRING_COMPLETE) {
    common::LogConnectionAdminAuditEvent("Pairing", address, static_cast<ErrorCode>(cmd_status));
  }
}

void LogMetricSdpAttribute(const Address& address, uint16_t protocol_uuid, uint16_t attribute_id,
                           size_t attribute_size, const char* attribute_value) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  BytesField attribute_field(attribute_value, attribute_size);
  int ret = stats_write(BLUETOOTH_SDP_ATTRIBUTE_REPORTED, byteField, protocol_uuid, attribute_id,
                        attribute_field, metric_id);
  if (ret < 0) {
    log::warn("Failed for {}, protocol_uuid {}, attribute_id {}, error {}", address,
              common::ToHexString(protocol_uuid), common::ToHexString(attribute_id), ret);
  }
}

void LogMetricSocketConnectionState(const Address& address, int port, int type,
                                    android::bluetooth::SocketConnectionstateEnum connection_state,
                                    int64_t tx_bytes, int64_t rx_bytes, int uid, int server_port,
                                    android::bluetooth::SocketRoleEnum socket_role) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_SOCKET_CONNECTION_STATE_CHANGED, byteField, port, type,
                        connection_state, tx_bytes, rx_bytes, uid, server_port, socket_role,
                        metric_id);
  if (ret < 0) {
    log::warn(
            "Failed for {}, port {}, type {}, state {}, tx_bytes {}, rx_bytes {}, uid {}, "
            "server_port "
            "{}, socket_role {}, error {}",
            address, port, type, connection_state, tx_bytes, rx_bytes, uid, server_port,
            socket_role, ret);
  }
}

void LogMetricManufacturerInfo(const Address& address,
                               android::bluetooth::AddressTypeEnum address_type,
                               android::bluetooth::DeviceInfoSrcEnum source_type,
                               const std::string& source_name, const std::string& manufacturer,
                               const std::string& model, const std::string& hardware_version,
                               const std::string& software_version) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_DEVICE_INFO_REPORTED, byteField, source_type, source_name.c_str(),
                        manufacturer.c_str(), model.c_str(), hardware_version.c_str(),
                        software_version.c_str(), metric_id, address_type, address.address[5],
                        address.address[4], address.address[3]);
  if (ret < 0) {
    log::warn(
            "Failed for {}, source_type {}, source_name {}, manufacturer {}, model {}, "
            "hardware_version {}, software_version {}, MAC address type {} MAC address prefix {} "
            "{} "
            "{}, error {}",
            address, source_type, source_name, manufacturer, model, hardware_version,
            software_version, address_type, address.address[5], address.address[4],
            address.address[3], ret);
  }
}

void LogMetricBluetoothHalCrashReason(const Address& address, uint32_t error_code,
                                      uint32_t vendor_error_code) {
  int ret = stats_write(BLUETOOTH_HAL_CRASH_REASON_REPORTED, 0 /* metric_id */, byteField,
                        error_code, vendor_error_code);
  if (ret < 0) {
    log::warn("Failed for {}, error_code {}, vendor_error_code {}, error {}", address,
              common::ToHexString(error_code), common::ToHexString(vendor_error_code), ret);
  }
}

void LogMetricBluetoothLocalSupportedFeatures(uint32_t page_num, uint64_t features) {
  int ret = stats_write(BLUETOOTH_LOCAL_SUPPORTED_FEATURES_REPORTED, page_num,
                        static_cast<int64_t>(features));
  if (ret < 0) {
    log::warn(
            "Failed for LogMetricBluetoothLocalSupportedFeatures, page_num {}, features {}, error "
            "{}",
            page_num, features, ret);
  }
}

void LogMetricBluetoothLocalVersions(uint32_t lmp_manufacturer_name, uint8_t lmp_version,
                                     uint32_t lmp_subversion, uint8_t hci_version,
                                     uint32_t hci_revision) {
  int ret = stats_write(BLUETOOTH_LOCAL_VERSIONS_REPORTED,
                        static_cast<int32_t>(lmp_manufacturer_name),
                        static_cast<int32_t>(lmp_version), static_cast<int32_t>(lmp_subversion),
                        static_cast<int32_t>(hci_version), static_cast<int32_t>(hci_revision));
  if (ret < 0) {
    log::warn(
            "Failed for LogMetricBluetoothLocalVersions, lmp_manufacturer_name {}, lmp_version {}, "
            "lmp_subversion {}, hci_version {}, hci_revision {}, error {}",
            lmp_manufacturer_name, lmp_version, lmp_subversion, hci_version, hci_revision, ret);
  }
}

void LogMetricBluetoothDisconnectionReasonReported(uint32_t reason, const Address& address,
                                                   uint32_t connection_handle) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_DISCONNECTION_REASON_REPORTED, reason, metric_id,
                        connection_handle);
  if (ret < 0) {
    log::warn(
            "Failed for LogMetricBluetoothDisconnectionReasonReported, reason {}, metric_id {}, "
            "connection_handle {}, error {}",
            reason, metric_id, connection_handle, ret);
  }
}

void LogMetricBluetoothRemoteSupportedFeatures(const Address& address, uint32_t page,
                                               uint64_t features, uint32_t connection_handle) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_REMOTE_SUPPORTED_FEATURES_REPORTED, metric_id, page,
                        static_cast<int64_t>(features), connection_handle);
  if (ret < 0) {
    log::warn(
            "Failed for LogMetricBluetoothRemoteSupportedFeatures, metric_id {}, page {}, features "
            "{}, "
            "connection_handle {}, error {}",
            metric_id, page, features, connection_handle, ret);
  }
}

void LogMetricBluetoothCodePathCounterMetrics(int32_t key, int64_t count) {
  int ret = stats_write(BLUETOOTH_CODE_PATH_COUNTER, key, count);
  if (ret < 0) {
    log::warn("Failed counter metrics for {}, count {}, error {}", key, count, ret);
  }
}

void LogMetricBluetoothLEConnection(os::LEConnectionSessionOptions session_options) {
  int metric_id = 0;
  if (!session_options.remote_address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(session_options.remote_address);
  }
  int ret = stats_write(BLUETOOTH_LE_SESSION_CONNECTED, session_options.acl_connection_state,
                        session_options.origin_type, session_options.transaction_type,
                        session_options.transaction_state, session_options.latency, metric_id,
                        session_options.app_uid, session_options.acl_latency,
                        session_options.status, session_options.is_cancelled);

  if (ret < 0) {
    log::warn(
            "Failed BluetoothLeSessionConnected - Address: {}, ACL Connection State: {}, Origin "
            "Type:  "
            "{}",
            session_options.remote_address,
            common::ToHexString(session_options.acl_connection_state),
            common::ToHexString(session_options.origin_type));
  }
}

void LogMetricBluetoothEvent(const Address& address, android::bluetooth::EventType event_type,
                             android::bluetooth::State state) {
  if (address.IsEmpty()) {
    log::warn("Failed BluetoothEvent Upload - Address is Empty");
    return;
  }
  int metric_id = MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(BLUETOOTH_CROSS_LAYER_EVENT_REPORTED,
                        event_type,
                        state,
                        0,
                        metric_id,
                        BytesField(nullptr, 0));
  if (ret < 0) {
    log::warn("Failed BluetoothEvent Upload - Address {}, Event_type {}, State {}", address,
              event_type, state);
  }
}

void LogMetricRfcommConnectionAtClose(const Address& address,
                                      android::bluetooth::rfcomm::PortResult close_reason,
                                      android::bluetooth::rfcomm::SocketConnectionSecurity security,
                                      android::bluetooth::rfcomm::RfcommPortEvent last_event,
                                      android::bluetooth::rfcomm::RfcommPortState previous_state,
                                      int32_t open_duration_ms, int32_t uid,
                                      android::bluetooth::BtaStatus sdp_status, bool is_server,
                                      bool sdp_initiated, int32_t sdp_duration_ms) {
  int metric_id = 0;
  if (address.IsEmpty()) {
    log::warn("Failed to upload - Address is empty");
    return;
  }
  metric_id = MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(BLUETOOTH_RFCOMM_CONNECTION_REPORTED_AT_CLOSE, close_reason, security,
                        last_event, previous_state, open_duration_ms, uid, metric_id, sdp_status,
                        is_server, sdp_initiated, sdp_duration_ms);
  if (ret < 0) {
    log::warn("Failed to log RFCOMM Connection metric for uid {}, close reason {}", uid,
              close_reason);
  }
}

}  // namespace os
}  // namespace bluetooth
