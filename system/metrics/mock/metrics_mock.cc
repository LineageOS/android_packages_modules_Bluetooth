/*
 * Copyright 2025 The Android Open Source Project
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

#include "metrics_mock.h"

#include <bluetooth/metrics/os_metrics.h>
#include <gmock/gmock.h>

namespace bluetooth::metrics {

// Current mock instances backing the metrics API.
static std::shared_ptr<MockMetrics> metricsInstance;

void MockMetrics::SetInstance(std::shared_ptr<MockMetrics> instance) {
  metricsInstance = std::move(instance);
}

bool InitMetricIdAllocator(const std::unordered_map<RawAddress, int>& paired_device_map,
                           CallbackLegacy save_id_callback, CallbackLegacy forget_device_callback) {
  if (metricsInstance) {
    return metricsInstance->InitMetricIdAllocator(paired_device_map, save_id_callback,
                                                  forget_device_callback);
  }
  return true;
}

bool CloseMetricIdAllocator() {
  if (metricsInstance) {
    return metricsInstance->CloseMetricIdAllocator();
  }
  return true;
}

bool IsEmptyMetricIdAllocator() {
  if (metricsInstance) {
    return metricsInstance->IsEmptyMetricIdAllocator();
  }
  return false;
}

bool IsValidIdFromMetricIdAllocator(int id) {
  if (metricsInstance) {
    return metricsInstance->IsValidIdFromMetricIdAllocator(id);
  }
  return false;
}

bool SaveDeviceOnMetricIdAllocator(const RawAddress& address) {
  if (metricsInstance) {
    return metricsInstance->SaveDeviceOnMetricIdAllocator(address);
  }
  return false;
}

int AllocateIdFromMetricIdAllocator(const RawAddress& address) {
  if (metricsInstance) {
    return metricsInstance->AllocateIdFromMetricIdAllocator(address);
  }
  return 0;
}

void ForgetDeviceFromMetricIdAllocator(const RawAddress& address) {
  if (metricsInstance) {
    return metricsInstance->ForgetDeviceFromMetricIdAllocator(address);
  }
}

void Counter(CounterKey key, int64_t count) {
  if (metricsInstance) {
    metricsInstance->Counter(key, count);
  }
}

void LogBluetoothEvent(const hci::Address& address, EventType event_type, State state) {
  if (metricsInstance) {
    metricsInstance->LogBluetoothEvent(address, event_type, state);
  }
}

void LogBluetoothEvent(const hci::Address& address, EventType event_type, State state, int uid) {
  if (metricsInstance) {
    metricsInstance->LogBluetoothEvent(address, event_type, state, uid);
  }
}

void LogMetricLinkLayerConnectionEvent(const hci::Address& address, uint32_t connection_handle,
                                       android::bluetooth::DirectionEnum direction,
                                       uint16_t link_type, uint32_t hci_cmd, uint16_t hci_event,
                                       uint16_t hci_ble_event, uint16_t cmd_status,
                                       uint16_t reason_code) {
  if (metricsInstance) {
    metricsInstance->LogMetricLinkLayerConnectionEvent(address, connection_handle, direction,
                                                       link_type, hci_cmd, hci_event, hci_ble_event,
                                                       cmd_status, reason_code);
  }
}

void LogMetricHciTimeoutEvent(uint32_t hci_cmd) {
  if (metricsInstance) {
    metricsInstance->LogMetricHciTimeoutEvent(hci_cmd);
  }
}

void LogMetricRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                                uint16_t manufacturer_name, uint16_t subversion) {
  if (metricsInstance) {
    metricsInstance->LogMetricRemoteVersionInfo(handle, status, version, manufacturer_name,
                                                subversion);
  }
}

void LogMetricA2dpAudioOverrunEvent(const hci::Address& address, uint64_t encoding_interval_millis,
                                    int num_dropped_buffers, int num_dropped_encoded_frames,
                                    int num_dropped_encoded_bytes) {
  if (metricsInstance) {
    metricsInstance->LogMetricA2dpAudioOverrunEvent(address, encoding_interval_millis,
                                                    num_dropped_buffers, num_dropped_encoded_frames,
                                                    num_dropped_encoded_bytes);
  }
}

void LogMetricA2dpPlaybackEvent(const hci::Address& address, int playback_state,
                                int audio_coding_mode) {
  if (metricsInstance) {
    metricsInstance->LogMetricA2dpPlaybackEvent(address, playback_state, audio_coding_mode);
  }
}

void LogA2dpSessionReported(const hci::Address& address, const A2dpSession& session) {
  if (metricsInstance) {
    metricsInstance->LogA2dpSessionReported(address, session);
  }
}

void LogMetricHfpPacketLossStats(const hci::Address& address, int num_decoded_frames,
                                 double packet_loss_ratio, uint16_t codec_id) {
  if (metricsInstance) {
    metricsInstance->LogMetricHfpPacketLossStats(address, num_decoded_frames, packet_loss_ratio,
                                                 codec_id);
  }
}

void LogMetricSmpPairingEvent(const hci::Address& address, uint16_t smp_cmd,
                              android::bluetooth::DirectionEnum direction,
                              uint16_t smp_fail_reason) {
  if (metricsInstance) {
    metricsInstance->LogMetricSmpPairingEvent(address, smp_cmd, direction, smp_fail_reason);
  }
}

void LogMetricClassicPairingEvent(const hci::Address& address, uint16_t handle, uint32_t hci_cmd,
                                  uint16_t hci_event, uint16_t cmd_status, uint16_t reason_code,
                                  int64_t event_value) {
  if (metricsInstance) {
    metricsInstance->LogMetricClassicPairingEvent(address, handle, hci_cmd, hci_event, cmd_status,
                                                  reason_code, event_value);
  }
}

void LogMetricSdpAttribute(const hci::Address& address, uint16_t protocol_uuid,
                           uint16_t attribute_id, size_t attribute_size,
                           const char* attribute_value) {
  if (metricsInstance) {
    metricsInstance->LogMetricSdpAttribute(address, protocol_uuid, attribute_id, attribute_size,
                                           attribute_value);
  }
}

void LogMetricSocketConnectionState(const hci::Address& address, int port, int type,
                                    android::bluetooth::SocketConnectionstateEnum connection_state,
                                    int64_t tx_bytes, int64_t rx_bytes, int uid, int server_port,
                                    android::bluetooth::SocketRoleEnum socket_role,
                                    uint64_t connection_duration_ms,
                                    android::bluetooth::SocketErrorEnum error_code,
                                    bool is_hardware_offload) {
  if (metricsInstance) {
    metricsInstance->LogMetricSocketConnectionState(
            address, port, type, connection_state, tx_bytes, rx_bytes, uid, server_port,
            socket_role, connection_duration_ms, error_code, is_hardware_offload);
  }
}

void LogMetricManufacturerInfo(const hci::Address& address,
                               android::bluetooth::AddressTypeEnum address_type,
                               android::bluetooth::DeviceInfoSrcEnum source_type,
                               const std::string& source_name, const std::string& manufacturer,
                               const std::string& model, const std::string& hardware_version,
                               const std::string& software_version) {
  if (metricsInstance) {
    metricsInstance->LogMetricManufacturerInfo(address, address_type, source_type, source_name,
                                               manufacturer, model, hardware_version,
                                               software_version);
  }
}

void LogMetricBluetoothHalCrashReason(const hci::Address& address, uint32_t error_code,
                                      uint32_t vendor_error_code) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothHalCrashReason(address, error_code, vendor_error_code);
  }
}

void LogMetricBluetoothLocalSupportedFeatures(uint32_t page_num, uint64_t features) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothLocalSupportedFeatures(page_num, features);
  }
}

void LogMetricBluetoothLocalVersions(uint32_t lmp_manufacturer_name, uint8_t lmp_version,
                                     uint32_t lmp_subversion, uint8_t hci_version,
                                     uint32_t hci_revision) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothLocalVersions(lmp_manufacturer_name, lmp_version,
                                                     lmp_subversion, hci_version, hci_revision);
  }
}

void LogMetricBluetoothDisconnectionReasonReported(uint32_t reason, const hci::Address& address,
                                                   uint32_t connection_handle) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothDisconnectionReasonReported(reason, address,
                                                                   connection_handle);
  }
}

void LogMetricBluetoothRemoteSupportedFeatures(const hci::Address& address, uint32_t page,
                                               uint64_t features, uint32_t connection_handle) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothRemoteSupportedFeatures(address, page, features,
                                                               connection_handle);
  }
}

void LogMetricBluetoothLEConnection(LEConnectionSessionOptions session_options) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothLEConnection(session_options);
  }
}

void LogMetricRfcommConnectionAtClose(const hci::Address& address,
                                      android::bluetooth::rfcomm::PortResult close_reason,
                                      android::bluetooth::rfcomm::SocketConnectionSecurity security,
                                      android::bluetooth::rfcomm::RfcommPortEvent last_event,
                                      android::bluetooth::rfcomm::RfcommPortState previous_state,
                                      int32_t open_duration_ms, int32_t uid,
                                      android::bluetooth::BtaStatus sdp_status, bool is_server,
                                      bool sdp_initiated, int32_t sdp_duration_ms) {
  if (metricsInstance) {
    metricsInstance->LogMetricRfcommConnectionAtClose(
            address, close_reason, security, last_event, previous_state, open_duration_ms, uid,
            sdp_status, is_server, sdp_initiated, sdp_duration_ms);
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
  if (metricsInstance) {
    LeAudioMetricsCodecInfoVector codec_info = {
        .codec_format = codec_format,
        .vendor_company_id = vendor_company_id,
        .vendor_codec_id = vendor_codec_id,
        .sink_sampling_frequency_hz = sink_sampling_frequency_hz,
        .source_sampling_frequency_hz = source_sampling_frequency_hz,
        .is_dsa_active = is_dsa_active,
        .is_gmap_active = is_gmap_active,
    };
    metricsInstance->LogMetricLeAudioConnectionSessionReported(
            group_size, group_metric_id, connection_duration_nanos, device_connecting_offset_nanos,
            device_connected_offset_nanos, device_connection_duration_nanos,
            device_connection_status, device_disconnection_status, device_address,
            streaming_offset_nanos, streaming_duration_nanos, streaming_context_type,
            codec_info);
  }
}

void LogMetricLeAudioBroadcastSessionReported(int64_t duration_nanos) {
  if (metricsInstance) {
    metricsInstance->LogMetricLeAudioBroadcastSessionReported(duration_nanos);
  }
}

void LogMetricBluetoothQualityReport(const RawAddress& remote_addr,
                                     const bqr::BqrLinkQualityEvent& event) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothQualityReport(remote_addr, event);
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
  if (metricsInstance) {
    metricsInstance->LogMetricsChannelSoundingRequesterSessionReported(
            remote_addr, app_uids, security_levels, measurement_interval_ms, stop_reason,
            setup_latency_ms, duration_seconds, back_to_back, cs_type, min_subevent_len,
            min_subevent_len_count);
  }
}

void LogMetricBluetoothEnergyMonitorReported(
        uint16_t bqr_version, const bqr::BqrEnergyMonitoringEventV7& event) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothEnergyMonitorReported(bqr_version, event);
  }
}

void LogMetricBluetoothRFStatsReported(uint16_t bqr_version, const bqr::BqrRFStatsEvent& event) {
  if (metricsInstance) {
    metricsInstance->LogMetricBluetoothRFStatsReported(bqr_version, event);
  }
}

void LogGattOffloadSessionStateChanged(const hci::Address& address, int32_t session_id,
                                       android::bluetooth::gatt::GattRoleEnum gatt_role,
                                       android::bluetooth::gatt::GattOffloadSessionStateEnum state,
                                       int32_t gatt_characteristic_properties_bitmask,
                                       int64_t session_duration_ms,
                                       android::bluetooth::gatt::GattOffloadErrorEnum error_code,
                                       int32_t uid, const std::string& attribution_tag) {
  if (metricsInstance) {
    metricsInstance->LogGattOffloadSessionStateChanged(
            address, session_id, gatt_role, state, gatt_characteristic_properties_bitmask,
            session_duration_ms, error_code, uid, attribution_tag);
  }
}

}  // namespace bluetooth::metrics
