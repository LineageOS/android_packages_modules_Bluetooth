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

#include <bluetooth/log.h>
#include <bluetooth/metrics/os_metrics.h>
#include <bluetooth/types/string_helpers.h>
#include <statslog_bt.h>

#include "../metric_id_manager.h"
#include "common/audit_log.h"
#include "hardware/bt_av.h"
#include "hci/hci_packets.h"
#include "hci/hci_status.h"
#include "main/shim/helpers.h"
#include "stack/include/a2dp_constants.h"

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
struct formatter<bluetooth::metrics::EventType> : enum_formatter<bluetooth::metrics::EventType> {};
template <>
struct formatter<bluetooth::metrics::State> : enum_formatter<bluetooth::metrics::State> {};
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
template <>
struct formatter<android::bluetooth::SocketErrorEnum>
    : enum_formatter<android::bluetooth::SocketErrorEnum> {};
template <>
struct formatter<bluetooth::metrics::CounterKey> : enum_formatter<bluetooth::metrics::CounterKey> {
};
template <>
struct formatter<android::bluetooth::gatt::GattRoleEnum>
    : enum_formatter<android::bluetooth::gatt::GattRoleEnum> {};
template <>
struct formatter<android::bluetooth::gatt::GattOffloadSessionStateEnum>
    : enum_formatter<android::bluetooth::gatt::GattOffloadSessionStateEnum> {};
template <>
struct formatter<android::bluetooth::gatt::GattOffloadErrorEnum>
    : enum_formatter<android::bluetooth::gatt::GattOffloadErrorEnum> {};
}  // namespace std

namespace bluetooth::metrics {

using hci::Address;
using hci::ErrorCode;
using hci::EventCode;

/**
 * nullptr and size 0 represent missing value for obfuscated_id
 */
static const BytesField byteField(nullptr, 0);

void Counter(CounterKey key, int64_t count) {
  int ret = stats_write(BLUETOOTH_CODE_PATH_COUNTER, key, count);
  if (ret < 0) {
    log::warn("Failed counter metrics for {}, count {}, error {}", key, count, ret);
  }
}

void LogBluetoothEvent(const Address& address, EventType event_type, State state, int uid) {
  if (address.IsEmpty()) {
    log::warn("Failed BluetoothEvent Upload - Address is Empty");
    return;
  }

  int metric_id = MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(BLUETOOTH_CROSS_LAYER_EVENT_REPORTED, event_type, state, uid, metric_id,
                        BytesField(nullptr, 0));

  if (ret < 0) {
    log::warn("Failed BluetoothEvent Upload - Address {}, Event_type {}, State {}, Uid {}", address,
              event_type, state, uid);
  }
}

void LogBluetoothEvent(const Address& address, EventType event_type, State state) {
  LogBluetoothEvent(address, event_type, state, 0);
}

void LogMetricLinkLayerConnectionEvent(const Address& address, uint32_t connection_handle,
                                       android::bluetooth::DirectionEnum direction,
                                       uint16_t link_type, uint32_t hci_cmd, uint16_t hci_event,
                                       uint16_t hci_ble_event, uint16_t cmd_status,
                                       uint16_t reason_code) {
  int metric_id = address.IsEmpty() ? 0 : MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(BLUETOOTH_LINK_LAYER_CONNECTION_EVENT, byteField, connection_handle,
                        direction, link_type, hci_cmd, hci_event, hci_ble_event, cmd_status,
                        reason_code, metric_id);
  if (ret < 0) {
    log::warn(
            "Failed to log status {} , reason {}, from cmd {}, event {},  ble_event {}, for {}, "
            "handle {}, type {}, error {}",
            common::ToHexString(cmd_status), common::ToHexString(reason_code),
            common::ToHexString(hci_cmd), common::ToHexString(hci_event),
            common::ToHexString(hci_ble_event), address, connection_handle,
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
            "failed for handle {}, status 0x{:x}, version 0x{:x}, "
            "manufacturer_name 0x{:x}, subversion 0x{:x}, error {}",
            handle, status, version, manufacturer_name, subversion, ret);
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

void LogA2dpSessionReported(const hci::Address& address, const A2dpSession& session) {
  a2dp::CodecId codec_id;
  switch (session.codec_index) {
    case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
      codec_id = a2dp::CodecId::SBC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
      codec_id = a2dp::CodecId::AAC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
      codec_id = a2dp::CodecId::APTX;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
      codec_id = a2dp::CodecId::APTX_HD;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
      codec_id = a2dp::CodecId::LDAC;
      break;
    case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
      codec_id = a2dp::CodecId::OPUS;
      break;
    default:
      return;
  }

  int32_t metric_id = MetricIdManager::GetInstance().AllocateId(address);
  int ret = stats_write(A2DP_SESSION_REPORTED, session.audio_duration_ms,
                        session.media_timer_min_ms, session.media_timer_max_ms,
                        session.media_timer_avg_ms, session.total_scheduling_count,
                        session.buffer_overruns_max_count, session.buffer_overruns_total,
                        session.buffer_underruns_average, session.buffer_underruns_count,
                        static_cast<uint64_t>(codec_id), session.is_a2dp_offload, metric_id);

  if (ret < 0) {
    log::warn("failed to log a2dp_session_reported");
  }
}

void LogMetricHfpPacketLossStats(const Address& /* address */, int /* num_decoded_frames */,
                                 double /* packet_loss_ratio */, uint16_t /* codec_type */) {}

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
    common::LogConnectionAdminAuditEvent("Pairing", address,
                                         HciStatus(static_cast<ErrorCode>(cmd_status)));
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
                                    android::bluetooth::SocketRoleEnum socket_role,
                                    uint64_t connection_duration_ms,
                                    android::bluetooth::SocketErrorEnum error_code,
                                    bool is_hardware_offload) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }

  int ret = stats_write(BLUETOOTH_SOCKET_CONNECTION_STATE_CHANGED, byteField, port, type,
                        connection_state, tx_bytes, rx_bytes, uid, server_port, socket_role,
                        metric_id, static_cast<int64_t>(connection_duration_ms), error_code,
                        is_hardware_offload);

  if (ret < 0) {
    log::warn(
            "Failed for {}, port {}, type {}, state {}, tx_bytes {}, rx_bytes {}, uid {}, "
            "server_port "
            "{}, socket_role {}, error {}, connection_duration_ms {}, socket_error_code {}, "
            "is_hardware_offload {}",
            address, port, type, connection_state, tx_bytes, rx_bytes, uid, server_port,
            socket_role, ret, connection_duration_ms, error_code, is_hardware_offload);
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

void LogMetricBluetoothLEConnection(LEConnectionSessionOptions session_options) {
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

void LogMetricLeAudioConnectionSessionReported(
        int32_t group_size, int32_t group_metric_id, int64_t connection_duration_nanos,
        const std::vector<int64_t>& device_connecting_offset_nanos,
        const std::vector<int64_t>& device_connected_offset_nanos,
        const std::vector<int64_t>& device_connection_duration_nanos,
        const std::vector<int32_t>& device_connection_status,
        const std::vector<int32_t>& device_disconnection_status,
        const std::vector<RawAddress>& device_address,
        const std::vector<int64_t>& streaming_offset_nanos,
        const std::vector<int64_t>& streaming_duration_nanos,
        const std::vector<int32_t>& streaming_context_type,
        const std::vector<int32_t>& codec_format,
        const std::vector<int32_t>& vendor_company_id,
        const std::vector<int32_t>& vendor_codec_id,
        const std::vector<int32_t>& sink_sampling_frequency_hz,
        const std::vector<int32_t>& source_sampling_frequency_hz,
        const std::vector<bool>& is_dsa_active,
        const std::vector<bool>& is_gmap_active) {
  std::vector<int32_t> device_metric_id(device_address.size());
  for (uint64_t i = 0; i < device_address.size(); i++) {
    if (!device_address[i].IsEmpty()) {
      device_metric_id[i] = MetricIdManager::GetInstance().AllocateId(device_address[i]);
    } else {
      device_metric_id[i] = 0;
    }
  }

  auto temp_is_dsa_active_buffer = std::make_unique<bool[]>(is_dsa_active.size());
  std::copy(is_dsa_active.begin(), is_dsa_active.end(), temp_is_dsa_active_buffer.get());
  auto temp_is_gmap_active_buffer = std::make_unique<bool[]>(is_gmap_active.size());
  std::copy(is_gmap_active.begin(), is_gmap_active.end(), temp_is_gmap_active_buffer.get());

  int ret = stats_write(
          LE_AUDIO_CONNECTION_SESSION_REPORTED, group_size, group_metric_id,
          connection_duration_nanos, device_connecting_offset_nanos, device_connected_offset_nanos,
          device_connection_duration_nanos, device_connection_status, device_disconnection_status,
          device_metric_id, streaming_offset_nanos, streaming_duration_nanos,
          streaming_context_type, codec_format, vendor_company_id, vendor_codec_id,
          sink_sampling_frequency_hz, source_sampling_frequency_hz,
          temp_is_dsa_active_buffer.get(), is_dsa_active.size(),
          temp_is_gmap_active_buffer.get(), is_gmap_active.size());
  if (ret < 0) {
    log::warn(
            "failed for group {}device_connecting_offset_nanos[{}], "
            "device_connected_offset_nanos[{}], "
            "device_connection_duration_nanos[{}], device_connection_status[{}], "
            "device_disconnection_status[{}], device_metric_id[{}], "
            "streaming_offset_nanos[{}], streaming_duration_nanos[{}], "
            "streaming_context_type[{}], "
            "codec_format[{}], vendor_company_id[{}], vendor_codec_id[{}], "
            "sink_sampling_frequency_hz[{}], source_sampling_frequency_hz[{}], "
            "is_dsa_active[{}], is_gmap_active[{}]",
            group_metric_id, device_connecting_offset_nanos.size(),
            device_connected_offset_nanos.size(), device_connection_duration_nanos.size(),
            device_connection_status.size(), device_disconnection_status.size(),
            device_metric_id.size(), streaming_offset_nanos.size(), streaming_duration_nanos.size(),
            streaming_context_type.size(),
            codec_format.size(), vendor_company_id.size(), vendor_codec_id.size(),
            sink_sampling_frequency_hz.size(), source_sampling_frequency_hz.size(),
            is_dsa_active.size(), is_gmap_active.size());
  }
}

void LogMetricLeAudioBroadcastSessionReported(int64_t duration_nanos) {
  int ret = stats_write(LE_AUDIO_BROADCAST_SESSION_REPORTED, duration_nanos);
  if (ret < 0) {
    log::warn("failed for duration={}", duration_nanos);
  }
}

void LogMetricBluetoothQualityReport(const RawAddress& remote_addr,
                                     const bqr::BqrLinkQualityEvent& event) {
  int32_t metric_id = 0;
  if (!remote_addr.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(remote_addr);
  }
  int ret = stats_write(
          BLUETOOTH_QUALITY_REPORT_REPORTED, event.quality_report_id, event.packet_types,
          event.connection_handle, event.connection_role, event.tx_power_level, event.rssi,
          event.snr, event.unused_afh_channel_count, event.afh_select_unideal_channel_count,
          event.lsto, event.connection_piconet_clock, event.retransmission_count, event.no_rx_count,
          event.nak_count, event.last_tx_ack_timestamp, event.flow_off_count,
          event.last_flow_on_timestamp, event.buffer_overflow_bytes, event.buffer_underflow_bytes,
          metric_id);
  if (ret < 0) {
    log::warn("failed to log BQR event to statsd, error {}", ret);
  }
}

void LogMetricsChannelSoundingRequesterSessionReported(
        const hci::Address& remote_addr, const std::vector<int32_t>& app_uids,
        const std::vector<int32_t>& security_levels,
        const std::vector<int32_t>& measurement_interval_ms,
        android::bluetooth::ChannelSoundingStopReason stop_reason, int32_t setup_latency_ms,
        int32_t duration_seconds, bool back_to_back,
        android::bluetooth::ChannelSoundingType cs_type, int32_t min_subevent_len,
        int32_t min_subevent_len_count) {
  int32_t metric_id = 0;
  if (!remote_addr.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(remote_addr);
  }
  int ret = stats_write(CHANNEL_SOUNDING_REQUESTER_SESSION_REPORTED, metric_id, app_uids,
                        security_levels, measurement_interval_ms, stop_reason, setup_latency_ms,
                        duration_seconds, back_to_back, cs_type, min_subevent_len,
                        min_subevent_len_count);
  if (ret < 0) {
    log::warn("failed to log the channel sounding session to statsd, error {}", ret);
  }
}

void LogMetricBluetoothEnergyMonitorReported(
        uint16_t bqr_version, const bluetooth::bqr::BqrEnergyMonitoringEventV7& event) {
  int ret = stats_write(
          BLUETOOTH_ENERGY_MONITOR_REPORTED, bqr_version, event.base.quality_report_id,
          event.base.avg_current_consume, event.base.idle_total_time,
          event.base.idle_state_enter_count, event.base.active_total_time,
          event.base.active_state_enter_count, event.base.bredr_tx_total_time,
          event.base.bredr_tx_state_enter_count, event.base.bredr_tx_avg_power_lv,
          event.base.bredr_rx_total_time, event.base.bredr_rx_state_enter_count,
          event.base.le_tx_total_time, event.base.le_tx_state_enter_count,
          event.base.le_tx_avg_power_lv, event.base.le_rx_total_time,
          event.base.le_rx_state_enter_count, event.base.tm_period,
          event.base.rx_active_one_chain_time, event.base.rx_active_two_chain_time,
          event.base.tx_ipa_active_one_chain_time, event.base.tx_ipa_active_two_chain_time,
          event.base.tx_epa_active_one_chain_time, event.base.tx_epa_active_two_chain_time,
          event.bredr_rx_active_scan_total_time, event.le_rx_active_scan_total_time);
  if (ret < 0) {
    log::warn("failed to log BQR energy monitor event to statsd, error {}", ret);
  }
}

void LogMetricBluetoothRFStatsReported(uint16_t bqr_version, const bqr::BqrRFStatsEvent& event) {
  int ret = stats_write(
          BLUETOOTH_RF_STATS_REPORTED, bqr_version, event.quality_report_id, event.tm_period,
          event.tx_pw_ipa_bf, event.tx_pw_epa_bf, event.tx_pw_ipa_div, event.tx_pw_epa_div,
          event.rssi_ch_50, event.rssi_ch_50_55, event.rssi_ch_55_60, event.rssi_ch_60_65,
          event.rssi_ch_65_70, event.rssi_ch_70_75, event.rssi_ch_75_80, event.rssi_ch_80_85,
          event.rssi_ch_85_90, event.rssi_ch_90, event.rssi_delta_2_down, event.rssi_delta_2_5,
          event.rssi_delta_5_8, event.rssi_delta_8_11, event.rssi_delta_11_up);
  if (ret < 0) {
    log::warn("failed to log BQR RF stats event to statsd, error {}", ret);
  }
}

void LogGattOffloadSessionStateChanged(const Address& address, int32_t session_id,
                                       android::bluetooth::gatt::GattRoleEnum gatt_role,
                                       android::bluetooth::gatt::GattOffloadSessionStateEnum state,
                                       int32_t gatt_characteristic_properties_bitmask,
                                       int64_t session_duration_ms,
                                       android::bluetooth::gatt::GattOffloadErrorEnum error_code,
                                       int32_t uid, const std::string& attribution_tag) {
  int metric_id = 0;
  if (!address.IsEmpty()) {
    metric_id = MetricIdManager::GetInstance().AllocateId(address);
  }
  int ret = stats_write(BLUETOOTH_GATT_OFFLOAD_SESSION_STATE_CHANGED, metric_id, session_id,
                        gatt_role, state, gatt_characteristic_properties_bitmask,
                        session_duration_ms, error_code, uid, attribution_tag.c_str());
  if (ret < 0) {
    log::warn("failed to log gatt offload session state changed to statsd, error {}", ret);
  }
}

}  // namespace bluetooth::metrics
