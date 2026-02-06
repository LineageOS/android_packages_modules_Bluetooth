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

#pragma once

#include <bluetooth/types/address.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/enums.pb.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/gatt/enums.pb.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/hci/enums.pb.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/le/enums.pb.h>
#include <frameworks/proto_logging/stats/enums/bluetooth/rfcomm/enums.pb.h>

#include <vector>

#include "btif/include/btif_bqr.h"
#include "hci/address.h"

namespace bluetooth::metrics {

using CounterKey = android::bluetooth::CodePathCounterKeyEnum;
using EventType = android::bluetooth::EventType;
using State = android::bluetooth::State;

/** Unknown connection handle for metrics purpose. */
constexpr uint32_t kUnknownConnectionHandle = 0xFFFF;

/** Simple counter metric. */
void Counter(CounterKey key, int64_t count = 1);

/**
 * Logs a Bluetooth Event
 *
 * @param address address of associated device
 * @param event_type type of event where this is getting logged from
 * @param state state associated with the event
 */
void LogBluetoothEvent(const hci::Address& address, EventType event_type, State state);

/**
 * Logs a Bluetooth Event
 *
 * @param address address of associated device
 * @param event_type type of event where this is getting logged from
 * @param state state associated with the event
 * @param uid uid of the app associated with the event
 */
void LogBluetoothEvent(const hci::Address& address, EventType event_type, State state, int uid);

/**
 * Log link layer connection event
 *
 * @param address Stack wide consistent Bluetooth address of this event,
 *                kEmpty if unknown
 * @param connection_handle connection handle of this event,
 *                          {@link kUnknownConnectionHandle} if unknown
 * @param direction direction of this connection
 * @param link_type type of the link
 * @param hci_cmd HCI command opecode associated with this event, if any
 * @param hci_event HCI event code associated with this event, if any
 * @param hci_ble_event HCI BLE event code associated with this event, if any
 * @param cmd_status Command status associated with this event, if any
 * @param reason_code Reason code associated with this event, if any
 */
void LogMetricLinkLayerConnectionEvent(const hci::Address& address, uint32_t connection_handle,
                                       android::bluetooth::DirectionEnum direction,
                                       uint16_t link_type, uint32_t hci_cmd, uint16_t hci_event,
                                       uint16_t hci_ble_event, uint16_t cmd_status,
                                       uint16_t reason_code);

/**
 * Logs when Bluetooth controller failed to reply with command status within
 * a timeout period after receiving an HCI command from the host
 *
 * @param hci_cmd opcode of HCI command that caused this timeout
 */
void LogMetricHciTimeoutEvent(uint32_t hci_cmd);

/**
 * Logs when we receive Bluetooth Read Remote Version Information Complete
 * Event from the remote device, as documented by the Bluetooth Core HCI
 * specification
 *
 * Reference: 5.0 Core Specification, Vol 2, Part E, Page 1118
 *
 * @param handle handle of associated ACL connection
 * @param status HCI command status of this event
 * @param version version code from read remote version complete event
 * @param manufacturer_name manufacturer code from read remote version complete
 *                          event
 * @param subversion subversion code from read remote version complete event
 */
void LogMetricRemoteVersionInfo(uint16_t handle, uint8_t status, uint8_t version,
                                uint16_t manufacturer_name, uint16_t subversion);

/**
 * Log A2DP audio buffer overrun event
 *
 * @param address A2DP device associated with this event
 * @param encoding_interval_millis encoding interval in milliseconds
 * @param num_dropped_buffers number of encoded buffers dropped from Tx queue
 * @param num_dropped_encoded_frames number of encoded frames dropped from Tx
 *                                   queue
 * @param num_dropped_encoded_bytes number of encoded bytes dropped from Tx
 *                                  queue
 */
void LogMetricA2dpAudioOverrunEvent(const hci::Address& address, uint64_t encoding_interval_millis,
                                    int num_dropped_buffers, int num_dropped_encoded_frames,
                                    int num_dropped_encoded_bytes);

/**
 * Log A2DP audio playback state changed event
 *
 * @param address A2DP device associated with this event
 * @param playback_state A2DP audio playback state, on/off
 * @param audio_coding_mode A2DP audio codec encoding mode, hw/sw
 */
void LogMetricA2dpPlaybackEvent(const hci::Address& address, int playback_state,
                                int audio_coding_mode);

struct A2dpSession {
  int64_t codec_index = -1;
  bool is_a2dp_offload = false;
  int64_t audio_duration_ms = -1;
  int32_t media_timer_min_ms = -1;
  int32_t media_timer_max_ms = -1;
  int32_t media_timer_avg_ms = -1;
  int64_t total_scheduling_count = -1;
  int32_t buffer_overruns_max_count = -1;
  int32_t buffer_overruns_total = -1;
  float buffer_underruns_average = -1;
  int32_t buffer_underruns_count = -1;
};

/**
 * Log A2DP audio session metrics
 *
 * @param address  A2DP device associated with this session
 * @param session  Statistics of the reported session.
 */
void LogA2dpSessionReported(const hci::Address& address, const A2dpSession& session);

/**
 * Log HFP audio capture packet loss statistics
 *
 * @param address HFP device associated with this stats
 * @param num_decoded_frames number of decoded frames
 * @param packet_loss_ratio ratio of packet loss frames
 * @param codec_id codec ID of the packet (mSBC=2, LC3=3)
 */
void LogMetricHfpPacketLossStats(const hci::Address& address, int num_decoded_frames,
                                 double packet_loss_ratio, uint16_t codec_id);

/**
 * Logs when there is an event related to Bluetooth Security Manager Protocol
 *
 * @param address address of associated device
 * @param smp_cmd SMP command code associated with this event
 * @param direction direction of this SMP command
 * @param smp_fail_reason SMP pairing failure reason code from SMP spec
 */
void LogMetricSmpPairingEvent(const hci::Address& address, uint16_t smp_cmd,
                              android::bluetooth::DirectionEnum direction,
                              uint16_t smp_fail_reason);

/**
 * Logs there is an event related Bluetooth classic pairing
 *
 * @param address address of associated device
 * @param handle connection handle of this event,
 *               {@link kUnknownConnectionHandle} if unknown
 * @param hci_cmd HCI command associated with this event
 * @param hci_event HCI event associated with this event
 * @param cmd_status Command status associated with this event
 * @param reason_code Reason code associated with this event
 * @param event_value A status value related to this specific event
 */
void LogMetricClassicPairingEvent(const hci::Address& address, uint16_t handle, uint32_t hci_cmd,
                                  uint16_t hci_event, uint16_t cmd_status, uint16_t reason_code,
                                  int64_t event_value);

/**
 * Logs when certain Bluetooth SDP attributes are discovered
 *
 * @param address address of associated device
 * @param protocol_uuid 16 bit protocol UUID from Bluetooth Assigned Numbers
 * @param attribute_id 16 bit attribute ID from Bluetooth Assigned Numbers
 * @param attribute_size size of this attribute
 * @param attribute_value pointer to the attribute data, must be larger than
 *                        attribute_size
 */
void LogMetricSdpAttribute(const hci::Address& address, uint16_t protocol_uuid,
                           uint16_t attribute_id, size_t attribute_size,
                           const char* attribute_value);

/**
 * Logs when there is a change in Bluetooth socket connection state
 *
 * @param address address of associated device, empty if this is a server port
 * @param port port of this socket connection
 * @param type type of socket
 * @param connection_state socket connection state
 * @param tx_bytes number of bytes transmitted
 * @param rx_bytes number of bytes received
 * @param server_port server port of this socket, if any. When both
 *        |server_port| and |port| fields are populated, |port| must be spawned
 *        by |server_port|
 * @param socket_role role of this socket, server or connection
 * @param uid socket owner's uid
 * @param connection_duration_ms duration of socket connection in milliseconds
 * @param error_code error code of socket failures
 * @param is_hardware_offload whether this is a offload socket
 */
void LogMetricSocketConnectionState(const hci::Address& address, int port, int type,
                                    android::bluetooth::SocketConnectionstateEnum connection_state,
                                    int64_t tx_bytes, int64_t rx_bytes, int uid, int server_port,
                                    android::bluetooth::SocketRoleEnum socket_role,
                                    uint64_t connection_duration_ms,
                                    android::bluetooth::SocketErrorEnum error_code,
                                    bool is_hardware_offload);

/**
 * Logs when a Bluetooth device's manufacturer information is learnt
 *
 * @param address address of associated device
 * @param source_type where is this device info obtained from
 * @param source_name name of the data source, internal or external
 * @param manufacturer name of the manufacturer of this device
 * @param model model of this device
 * @param hardware_version hardware version of this device
 * @param software_version software version of this device
 */
void LogMetricManufacturerInfo(const hci::Address& address,
                               android::bluetooth::AddressTypeEnum address_type,
                               android::bluetooth::DeviceInfoSrcEnum source_type,
                               const std::string& source_name, const std::string& manufacturer,
                               const std::string& model, const std::string& hardware_version,
                               const std::string& software_version);

/**
 * Logs when received Bluetooth HAL crash reason report.
 *
 * @param address current connected address.
 * @param error_code the crash reason from bluetooth hal
 * @param vendor_error_code the vendor crash reason from bluetooth firmware
 */
void LogMetricBluetoothHalCrashReason(const hci::Address& address, uint32_t error_code,
                                      uint32_t vendor_error_code);

void LogMetricBluetoothLocalSupportedFeatures(uint32_t page_num, uint64_t features);

void LogMetricBluetoothLocalVersions(uint32_t lmp_manufacturer_name, uint8_t lmp_version,
                                     uint32_t lmp_subversion, uint8_t hci_version,
                                     uint32_t hci_revision);

void LogMetricBluetoothDisconnectionReasonReported(uint32_t reason, const hci::Address& address,
                                                   uint32_t connection_handle);

void LogMetricBluetoothRemoteSupportedFeatures(const hci::Address& address, uint32_t page,
                                               uint64_t features, uint32_t connection_handle);

using android::bluetooth::le::LeAclConnectionState;
using android::bluetooth::le::LeConnectionOriginType;
using android::bluetooth::le::LeConnectionState;
using android::bluetooth::le::LeConnectionType;

struct LEConnectionSessionOptions {
  // Contains the state of the LE-ACL Connection
  LeAclConnectionState acl_connection_state = LeAclConnectionState::LE_ACL_UNSPECIFIED;
  // Origin of the transaction
  LeConnectionOriginType origin_type = LeConnectionOriginType::ORIGIN_UNSPECIFIED;
  // Connection Type
  LeConnectionType transaction_type = LeConnectionType::CONNECTION_TYPE_UNSPECIFIED;
  // Transaction State
  LeConnectionState transaction_state = LeConnectionState::STATE_UNSPECIFIED;
  // Latency of the entire transaction
  int64_t latency = 0;
  // Address of the remote device
  hci::Address remote_address = hci::Address::kEmpty;
  // UID associated with the device
  int app_uid = 0;
  // Latency of the ACL Transaction
  int64_t acl_latency = 0;
  // Contains the error code associated with the ACL Connection if failed
  android::bluetooth::hci::StatusEnum status = android::bluetooth::hci::StatusEnum::STATUS_UNKNOWN;
  // Cancelled connection
  bool is_cancelled = false;
};

// Upload LE Session
void LogMetricBluetoothLEConnection(LEConnectionSessionOptions session_options);

/**
 * Logs an RFCOMM connection when an RFCOMM port closes
 *
 * @param address address of the peer device
 * @param close_reason reason that the port was closed
 * @param security security level of the connection
 * @param last_event event processed prior to "CLOSED"
 * @param previous_state state prior to "CLOSED"
 * @param open_duration_ms that the socket was opened, 0 if connection failed
 * @param uid UID of the app that called connect
 * @param sdp_status status code for sdp
 * @param is_server true if device is server
 * @param sdp_initiated true if sdp started for thie connection
 * @param sdp_duration_ms duration of sdp, 0 if it didn't happen
 */
void LogMetricRfcommConnectionAtClose(const hci::Address& address,
                                      android::bluetooth::rfcomm::PortResult close_reason,
                                      android::bluetooth::rfcomm::SocketConnectionSecurity security,
                                      android::bluetooth::rfcomm::RfcommPortEvent last_event,
                                      android::bluetooth::rfcomm::RfcommPortState previous_state,
                                      int32_t open_duration_ms, int32_t uid,
                                      android::bluetooth::BtaStatus sdp_status, bool is_server,
                                      bool sdp_initiated, int32_t sdp_duration_ms);

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
        const std::vector<bool>& is_gmap_active);

void LogMetricLeAudioBroadcastSessionReported(int64_t duration_nanos);

void LogMetricBluetoothQualityReport(const RawAddress& remote_addr,
                                     const bqr::BqrLinkQualityEvent& event);

void LogMetricsChannelSoundingRequesterSessionReported(
        const hci::Address& remote_addr, const std::vector<int32_t>& app_uids,
        const std::vector<int32_t>& security_levels,
        const std::vector<int32_t>& measurement_interval_ms,
        android::bluetooth::ChannelSoundingStopReason stop_reason, int32_t setup_latency_ms,
        int32_t duration_seconds, bool back_to_back,
        android::bluetooth::ChannelSoundingType cs_type, int32_t min_subevent_len,
        int32_t min_subevent_len_count);

void LogMetricBluetoothEnergyMonitorReported(
        uint16_t bqr_version, const bluetooth::bqr::BqrEnergyMonitoringEventV7& event);

void LogMetricBluetoothRFStatsReported(uint16_t bqr_version,
                                       const bluetooth::bqr::BqrRFStatsEvent& event);

/**
 * Logs GATT Offload session state changed metrics.
 *
 * @param address Address of associated device
 * @param session_id Offload session ID assigned from host stack
 * @param gatt_role Role of the GATT connection (Client or Server)
 * @param state State of the GATT offload session
 * @param gatt_characteristic_properties_bitmask Bitmask representing the combined GATT
 * Characteristic Properties for ALL characteristics included in this offload session. This is an
 * OR'ed value of all individual characteristic properties. The bits are defined in
 * android.bluetooth.BluetoothGattCharacteristic and are based on the Bluetooth Core
 * Specification, Volume 3, Part G, Section 3.3.1.1.
 * @param session_duration_ms Duration of the offload session in milliseconds
 * @param error_code Error code of offload session failures
 * @param uid Connection owner's UID (e.g., App UID).
 * @param attribution_tag Tag to identify the last caller in the attribution chain, useful for
 * shared UIDs.
 */
void LogGattOffloadSessionStateChanged(const hci::Address& address, int32_t session_id,
                                       android::bluetooth::gatt::GattRoleEnum gatt_role,
                                       android::bluetooth::gatt::GattOffloadSessionStateEnum state,
                                       int32_t gatt_characteristic_properties_bitmask,
                                       int64_t session_duration_ms,
                                       android::bluetooth::gatt::GattOffloadErrorEnum error_code,
                                       int32_t uid, const std::string& attribution_tag);

}  // namespace bluetooth::metrics
