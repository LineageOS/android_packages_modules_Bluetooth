/*
 * Copyright 2021 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:12
 *
 *  mockcify.pl ver 0.2
 */
// Mock include file to share data between tests and mock
#include "mock_main_shim_metrics_api.h"

#include <cstdint>
#include <string>

// Original included files, if any
#include <bluetooth/metrics/os_metrics.h>

#include "test/common/mock_functions.h"
#include "types/raw_address.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any

using bluetooth::hci::Address;

namespace test {
namespace mock {
namespace main_shim_metrics_api {

// Function state capture and return values, if needed
struct LogMetricLinkLayerConnectionEvent LogMetricLinkLayerConnectionEvent;
struct LogMetricA2dpAudioUnderrunEvent LogMetricA2dpAudioUnderrunEvent;
struct LogMetricA2dpAudioOverrunEvent LogMetricA2dpAudioOverrunEvent;
struct LogMetricA2dpPlaybackEvent LogMetricA2dpPlaybackEvent;
struct LogMetricA2dpSessionMetricsEvent LogMetricA2dpSessionMetricsEvent;
struct LogMetricHfpPacketLossStats LogMetricHfpPacketLossStats;
struct LogMetricReadRssiResult LogMetricReadRssiResult;
struct LogMetricReadFailedContactCounterResult LogMetricReadFailedContactCounterResult;
struct LogMetricReadTxPowerLevelResult LogMetricReadTxPowerLevelResult;
struct LogMetricSmpPairingEvent LogMetricSmpPairingEvent;
struct LogMetricClassicPairingEvent LogMetricClassicPairingEvent;
struct LogMetricSdpAttribute LogMetricSdpAttribute;
struct LogMetricSocketConnectionState LogMetricSocketConnectionState;
struct LogMetricManufacturerInfo LogMetricManufacturerInfo;
struct LogMetricRfcommConnectionAtClose LogMetricRfcommConnectionAtClose;
struct LogMetricLeAudioBroadcastSessionReported LogMetricLeAudioBroadcastSessionReported;
struct LogMetricLeAudioConnectionSessionReported LogMetricLeAudioConnectionSessionReported;

}  // namespace main_shim_metrics_api
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void bluetooth::os::LogMetricLinkLayerConnectionEvent(const Address& raw_address,
                                                      uint32_t connection_handle,
                                                      android::bluetooth::DirectionEnum direction,
                                                      uint16_t link_type, uint32_t hci_cmd,
                                                      uint16_t hci_event, uint16_t hci_ble_event,
                                                      uint16_t cmd_status, uint16_t reason_code) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricLinkLayerConnectionEvent(
          raw_address, connection_handle, direction, link_type, hci_cmd, hci_event, hci_ble_event,
          cmd_status, reason_code);
}
void bluetooth::os::LogMetricA2dpAudioUnderrunEvent(const Address& raw_address,
                                                    uint64_t encoding_interval_millis,
                                                    int num_missing_pcm_bytes) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricA2dpAudioUnderrunEvent(
          raw_address, encoding_interval_millis, num_missing_pcm_bytes);
}
void bluetooth::os::LogMetricA2dpAudioOverrunEvent(const Address& raw_address,
                                                   uint64_t encoding_interval_millis,
                                                   int num_dropped_buffers,
                                                   int num_dropped_encoded_frames,
                                                   int num_dropped_encoded_bytes) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricA2dpAudioOverrunEvent(
          raw_address, encoding_interval_millis, num_dropped_buffers, num_dropped_encoded_frames,
          num_dropped_encoded_bytes);
}
void bluetooth::os::LogMetricA2dpPlaybackEvent(const Address& raw_address, int playback_state,
                                               int audio_coding_mode) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricA2dpPlaybackEvent(raw_address, playback_state,
                                                                audio_coding_mode);
}
void bluetooth::os::LogMetricA2dpSessionMetricsEvent(
        const Address& raw_address, int64_t audio_duration_ms, int media_timer_min_ms,
        int media_timer_max_ms, int /* media_timer_avg_ms */, int total_scheduling_count,
        int buffer_overruns_max_count, int buffer_overruns_total, float buffer_underruns_average,
        int buffer_underruns_count, int64_t codec_index, bool is_a2dp_offload) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricA2dpSessionMetricsEvent(
          raw_address, audio_duration_ms, media_timer_min_ms, media_timer_max_ms, audio_duration_ms,
          total_scheduling_count, buffer_overruns_max_count, buffer_overruns_total,
          buffer_underruns_average, buffer_underruns_count, codec_index, is_a2dp_offload);
}
void bluetooth::os::LogMetricHfpPacketLossStats(const Address& raw_address, int num_decoded_frames,
                                                double packet_loss_ratio, uint16_t codec_type) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricHfpPacketLossStats(raw_address, num_decoded_frames,
                                                                 packet_loss_ratio, codec_type);
}
void bluetooth::os::LogMetricReadRssiResult(const Address& raw_address, uint16_t handle,
                                            uint32_t cmd_status, int8_t rssi) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricReadRssiResult(raw_address, handle, cmd_status, rssi);
}
void bluetooth::os::LogMetricReadFailedContactCounterResult(const Address& raw_address,
                                                            uint16_t handle, uint32_t cmd_status,
                                                            int32_t failed_contact_counter) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricReadFailedContactCounterResult(
          raw_address, handle, cmd_status, failed_contact_counter);
}
void bluetooth::os::LogMetricReadTxPowerLevelResult(const Address& raw_address, uint16_t handle,
                                                    uint32_t cmd_status,
                                                    int32_t transmit_power_level) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricReadTxPowerLevelResult(
          raw_address, handle, cmd_status, transmit_power_level);
}
void bluetooth::os::LogMetricSmpPairingEvent(const Address& raw_address, uint16_t smp_cmd,
                                             android::bluetooth::DirectionEnum direction,
                                             uint16_t smp_fail_reason) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricSmpPairingEvent(raw_address, smp_cmd, direction,
                                                              smp_fail_reason);
}
void bluetooth::os::LogMetricClassicPairingEvent(const Address& raw_address, uint16_t handle,
                                                 uint32_t hci_cmd, uint16_t hci_event,
                                                 uint16_t cmd_status, uint16_t reason_code,
                                                 int64_t event_value) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricClassicPairingEvent(
          raw_address, handle, hci_cmd, hci_event, cmd_status, reason_code, event_value);
}
void bluetooth::os::LogMetricSdpAttribute(const Address& raw_address, uint16_t protocol_uuid,
                                          uint16_t attribute_id, size_t attribute_size,
                                          const char* attribute_value) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricSdpAttribute(raw_address, protocol_uuid, attribute_id,
                                                           attribute_size, attribute_value);
}
void bluetooth::os::LogMetricSocketConnectionState(
        const Address& raw_address, int port, int type,
        android::bluetooth::SocketConnectionstateEnum connection_state, int64_t tx_bytes,
        int64_t rx_bytes, int uid, int server_port, android::bluetooth::SocketRoleEnum socket_role,
        uint64_t connection_duration_ms, android::bluetooth::SocketErrorEnum error_code,
        bool is_hardware_offload) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricSocketConnectionState(
          raw_address, port, type, connection_state, tx_bytes, rx_bytes, uid, server_port,
          socket_role, connection_duration_ms, error_code, is_hardware_offload);
}
void bluetooth::os::LogMetricManufacturerInfo(
        const Address& raw_address, android::bluetooth::AddressTypeEnum address_type,
        android::bluetooth::DeviceInfoSrcEnum source_type, const std::string& source_name,
        const std::string& manufacturer, const std::string& model,
        const std::string& hardware_version, const std::string& software_version) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricManufacturerInfo(
          raw_address, address_type, source_type, source_name, manufacturer, model,
          hardware_version, software_version);
}
void bluetooth::os::CountCounterMetrics(android::bluetooth::CodePathCounterKeyEnum /* key */,
                                        int64_t /* count */) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricRfcommConnectionAtClose(
        const Address& address, android::bluetooth::rfcomm::PortResult close_reason,
        android::bluetooth::rfcomm::SocketConnectionSecurity security,
        android::bluetooth::rfcomm::RfcommPortEvent last_event,
        android::bluetooth::rfcomm::RfcommPortState previous_state, int32_t open_duration_ms,
        int32_t uid, android::bluetooth::BtaStatus sdp_status, bool is_server, bool sdp_initiated,
        int32_t sdp_duration_ms) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricRfcommConnectionAtClose(
          address, close_reason, security, last_event, previous_state, open_duration_ms, uid,
          sdp_status, is_server, sdp_initiated, sdp_duration_ms);
}
void bluetooth::os::LogMetricBluetoothEvent(const Address& address,
                                            android::bluetooth::EventType event_type,
                                            android::bluetooth::State state) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricLeAudioBroadcastSessionReported(int64_t duration_nanos) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricLeAudioBroadcastSessionReported(duration_nanos);
}
void bluetooth::os::LogMetricBluetoothDisconnectionReasonReported(uint32_t reason,
                                                                  const Address& address,
                                                                  uint32_t connection_handle) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                                               uint16_t manufacturer_name, uint16_t subversion) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricBluetoothRemoteSupportedFeatures(const Address& address, uint32_t page,
                                                              uint64_t features,
                                                              uint32_t connection_handle) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricBluetoothHalCrashReason(const Address& address, uint32_t error_code,
                                                     uint32_t vendor_error_code) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricHciTimeoutEvent(uint32_t hci_cmd) { inc_func_call_count(__func__); }
void bluetooth::os::LogMetricBluetoothLocalVersions(uint32_t lmp_manufacturer_name,
                                                    uint8_t lmp_version, uint32_t lmp_subversion,
                                                    uint8_t hci_version, uint32_t hci_revision) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricBluetoothLocalSupportedFeatures(uint32_t page_num, uint64_t features) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricBluetoothQualityReport(
        uint8_t quality_report_id, uint8_t packet_types, uint16_t connection_handle,
        uint8_t connection_role, int8_t tx_power_level, int8_t rssi, uint8_t snr,
        uint8_t unused_afh_channel_count, uint8_t afh_select_unideal_channel_count, uint16_t lsto,
        uint32_t connection_piconet_clock, uint32_t retransmission_count, uint32_t no_rx_count,
        uint32_t nak_count, uint32_t last_tx_ack_timestamp, uint32_t flow_off_count,
        uint32_t last_flow_on_timestamp, uint32_t buffer_overflow_bytes,
        uint32_t buffer_underflow_bytes) {
  inc_func_call_count(__func__);
}
void bluetooth::os::LogMetricLeAudioConnectionSessionReported(
        int32_t group_size, int32_t group_metric_id, int64_t connection_duration_nanos,
        const std::vector<int64_t>& device_connecting_offset_nanos,
        const std::vector<int64_t>& device_connected_offset_nanos,
        const std::vector<int64_t>& device_connection_duration_nanos,
        const std::vector<int32_t>& device_connection_status,
        const std::vector<int32_t>& device_disconnection_status,
        const std::vector<RawAddress>& device_address,
        const std::vector<int64_t>& streaming_offset_nanos,
        const std::vector<int64_t>& streaming_duration_nanos,
        const std::vector<int32_t>& streaming_context_type) {
  inc_func_call_count(__func__);
  test::mock::main_shim_metrics_api::LogMetricLeAudioConnectionSessionReported(
          group_size, group_metric_id, connection_duration_nanos, device_connecting_offset_nanos,
          device_connected_offset_nanos, device_connection_duration_nanos, device_connection_status,
          device_disconnection_status, device_address, streaming_offset_nanos,
          streaming_duration_nanos, streaming_context_type);
}
// END mockcify generation
