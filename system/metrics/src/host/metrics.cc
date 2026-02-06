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

#include <bluetooth/metrics/os_metrics.h>

namespace bluetooth::metrics {

using bluetooth::hci::Address;

void Counter(CounterKey /* key */, int64_t /* count */) {}

void LogBluetoothEvent(const Address& /* address */, EventType /* event type */,
                       State /* state */) {}

void LogBluetoothEvent(const Address& /* address */, EventType /* event type */, State /* state */,
                       int /* uid */) {}

void LogMetricClassicPairingEvent(const Address& /* address */, uint16_t /* handle */,
                                  uint32_t /* hci_cmd */, uint16_t /* hci_event */,
                                  uint16_t /* cmd_status */, uint16_t /* reason_code */,
                                  int64_t /* event_value */) {}

void LogMetricSocketConnectionState(
        const Address& /* address */, int /* port */, int /* type */,
        android::bluetooth::SocketConnectionstateEnum /* connection_state */,
        int64_t /* tx_bytes */, int64_t /* rx_bytes */, int /* uid */, int /* server_port */,
        android::bluetooth::SocketRoleEnum /* socket_role */, uint64_t /* connection_duration_ms */,
        android::bluetooth::SocketErrorEnum /* error_code */, bool /* is_hardware_offload */) {}

void LogMetricHciTimeoutEvent(uint32_t /* hci_cmd */) {}

void LogMetricA2dpAudioOverrunEvent(const Address& /* address */,
                                    uint64_t /* encoding_interval_millis */,
                                    int /* num_dropped_buffers */,
                                    int /* num_dropped_encoded_frames */,
                                    int /* num_dropped_encoded_bytes */) {}

void LogMetricHfpPacketLossStats(const Address& /* address */, int /* num_decoded_frames */,
                                 double /* packet_loss_ratio */, uint16_t /* codec_type */) {}

void LogMetricRemoteVersionInfo(uint16_t /* handle */, uint8_t /* status */, uint8_t /* version */,
                                uint16_t /* manufacturer_name */, uint16_t /* subversion */) {}

void LogMetricLinkLayerConnectionEvent(const Address& /* address */,
                                       uint32_t /* connection_handle */,
                                       android::bluetooth::DirectionEnum /* direction */,
                                       uint16_t /* link_type */, uint32_t /* hci_cmd */,
                                       uint16_t /* hci_event */, uint16_t /* hci_ble_event */,
                                       uint16_t /* cmd_status */, uint16_t /* reason_code */) {}

void LogMetricManufacturerInfo(const Address& /* address */,
                               android::bluetooth::AddressTypeEnum /* address_type */,
                               android::bluetooth::DeviceInfoSrcEnum /* source_type */,
                               const std::string& /* source_name */,
                               const std::string& /* manufacturer */,
                               const std::string& /* model */,
                               const std::string& /* hardware_version */,
                               const std::string& /* software_version */) {}

void LogMetricSdpAttribute(const Address& /* address */, uint16_t /* protocol_uuid */,
                           uint16_t /* attribute_id */, size_t /* attribute_size */,
                           const char* /* attribute_value */) {}

void LogMetricSmpPairingEvent(const Address& /* address */, uint16_t /* smp_cmd */,
                              android::bluetooth::DirectionEnum /* direction */,
                              uint16_t /* smp_fail_reason */) {}

void LogMetricA2dpPlaybackEvent(const Address& /* address */, int /* playback_state */,
                                int /* audio_coding_mode */) {}

void LogA2dpSessionReported(const Address& /* address */, const A2dpSession& /* session */) {}

void LogMetricBluetoothHalCrashReason(const Address& /* address */, uint32_t /* error_code */,
                                      uint32_t /* vendor_error_code */) {}

void LogMetricBluetoothLocalSupportedFeatures(uint32_t /* page_num */, uint64_t /* features */) {}

void LogMetricBluetoothLocalVersions(uint32_t /* lmp_manufacturer_name */,
                                     uint8_t /* lmp_version */, uint32_t /* lmp_subversion */,
                                     uint8_t /* hci_version */, uint32_t /* hci_reversion */) {}

void LogMetricBluetoothDisconnectionReasonReported(uint32_t /* reason */,
                                                   const Address& /* address */,
                                                   uint32_t /* connection_handle */) {}

void LogMetricBluetoothRemoteSupportedFeatures(const Address& /* address */, uint32_t /* page */,
                                               uint64_t /* features */,
                                               uint32_t /* connection_handle */) {}

void LogMetricBluetoothLEConnection(LEConnectionSessionOptions /* session_options */) {}

void LogMetricRfcommConnectionAtClose(
        const Address& /* raw_address */, android::bluetooth::rfcomm::PortResult /* close_reason */,
        android::bluetooth::rfcomm::SocketConnectionSecurity /* security */,
        android::bluetooth::rfcomm::RfcommPortEvent /* last_event */,
        android::bluetooth::rfcomm::RfcommPortState /* previous_state */,
        int32_t /* open_duration_ms */, int32_t /* uid */,
        android::bluetooth::BtaStatus /* sdp_status */, bool /* is_server */,
        bool /* sdp_initiated */, int32_t /* sdp_duration_ms */) {}

void LogMetricLeAudioConnectionSessionReported(
        int32_t /*group_size*/, int32_t /*group_metric_id*/, int64_t /*connection_duration_nanos*/,
        const std::vector<int64_t>& /*device_connecting_offset_nanos*/,
        const std::vector<int64_t>& /*device_connected_offset_nanos*/,
        const std::vector<int64_t>& /*device_connection_duration_nanos*/,
        const std::vector<int32_t>& /*device_connection_status*/,
        const std::vector<int32_t>& /*device_disconnection_status*/,
        const std::vector<RawAddress>& /*device_address*/,
        const std::vector<int64_t>& /*streaming_offset_nanos*/,
        const std::vector<int64_t>& /*streaming_duration_nanos*/,
        const std::vector<int32_t>& /*streaming_context_type*/,
        const std::vector<int32_t>& /*codec_format*/,
        const std::vector<int32_t>& /*vendor_company_id*/,
        const std::vector<int32_t>& /*vendor_codec_id*/,
        const std::vector<int32_t>& /*sink_sampling_frequency_hz*/,
        const std::vector<int32_t>& /*source_sampling_frequency_hz*/,
        const std::vector<bool>& /*is_dsa_active*/,
        const std::vector<bool>& /*is_gmap_active*/) {}

void LogMetricLeAudioBroadcastSessionReported(int64_t /*duration_nanos*/) {}

void LogMetricBluetoothQualityReport(const RawAddress& /*remote_addr*/,
                                     const bqr::BqrLinkQualityEvent& /*event*/) {}

void LogMetricsChannelSoundingRequesterSessionReported(
        const hci::Address& /*remote_addr*/, const std::vector<int32_t>& /*app_uids*/,
        const std::vector<int32_t>& /*security_levels*/,
        const std::vector<int32_t>& /*measurement_interval_ms*/,
        android::bluetooth::ChannelSoundingStopReason /*stop_reason*/, int32_t /*setup_latency_ms*/,
        int32_t /*duration_seconds*/, bool /*back_to_back*/,
        android::bluetooth::ChannelSoundingType /*cs_type*/, int32_t /*min_subevent_len*/,
        int32_t /*min_subevent_len_count*/) {}

void LogMetricBluetoothEnergyMonitorReported(
        uint16_t /*bqr_version*/, const bqr::BqrEnergyMonitoringEventV7& /*event*/) {}

void LogMetricBluetoothRFStatsReported(uint16_t /*bqr_version*/,
                                       const bqr::BqrRFStatsEvent& /*event*/) {}

void LogGattOffloadSessionStateChanged(
        const Address& /* address */, int32_t /* session_id */,
        android::bluetooth::gatt::GattRoleEnum /* gatt_role */,
        android::bluetooth::gatt::GattOffloadSessionStateEnum /* state */,
        int32_t /* gatt_characteristic_properties_bitmask */, int64_t /* session_duration_ms */,
        android::bluetooth::gatt::GattOffloadErrorEnum /* error_code */, int32_t /* uid */,
        const std::string& /* attribution_tag */) {}

}  // namespace bluetooth::metrics
