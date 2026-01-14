/*
 * Copyright 2019 The Android Open Source Project
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

#pragma once

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include "common/postable_context.h"
#include "include/hardware/bt_bqr.h"
#include "osi/include/osi.h"

namespace bluetooth {
namespace bqr {

// Bluetooth Quality Report (BQR)
//
// It is a feature to start the mechanism in the Bluetooth controller to report
// Bluetooth Quality event to the host and the following options can be enabled:
//   [Quality Monitoring Mode]
//     The controller shall periodically send Bluetooth Quality Report sub-event
//     to the host.
//
//   [Approaching LSTO]
//     Once no packets are received from the connected Bluetooth device for a
//     duration longer than the half of LSTO (Link Supervision TimeOut) value,
//     the controller shall report Approaching LSTO event to the host.
//
//   [A2DP Audio Choppy]
//     When the controller detects the factors which will cause audio choppy,
//     the controller shall report A2DP Audio Choppy event to the host.
//
//   [(e)SCO Voice Choppy]
//     When the controller detects the factors which will cause voice choppy,
//     the controller shall report (e)SCO Voice Choppy event to the host.
//
//   [Root Inflammation]
//     When the controller encounters an error it shall report Root Inflammation
//     event indicating the error code to the host.
//
//   [Vendor Specific Quality]
//     Used for the controller vendor to define the vendor proprietary quality
//     event(s).
//
//   [LMP/LL message trace]
//     The controller sends the LMP/LL message handshaking with the remote
//     device to the host.
//
//   [Bluetooth Multi-profile/Coex scheduling trace]
//     The controller sends its scheduling information on handling the Bluetooth
//     multiple profiles and wireless coexistence in the 2.4 Ghz band to the
//     host.
//
//   [Enable the Controller Debug Information mechanism]
//     After enabling the Controller Debug Information mechanism, the controller
//     just can autonomously report debug logging information via the Controller
//     Debug Info sub-event to the host.
//
//   [Connect Fail]
//     When the controller fails to create connection with remote side,
//     and remote responds for at least one time, the controller shall report
//     connection fail event to the host. However, if remote doesn't respond
//     at all(most likely remote is powered off or out of range), controller
//     will not report this event.

// BQR Event Mask
enum BqrEventMask : uint8_t {
  BQR_EVENT_MASK_MONITOR_MODE = 0x00,
  BQR_EVENT_MASK_APPROACH_LSTO = 0x01,
  BQR_EVENT_MASK_A2DP_AUDIO_CHOPPY = 0x02,
  BQR_EVENT_MASK_SCO_VOICE_CHOPPY = 0x03,
  BQR_EVENT_MASK_ROOT_INFLAMMATION = 0x04,
  BQR_EVENT_MASK_ENERGY_MONITOR = 0x05,
  BQR_EVENT_MASK_LE_AUDIO_CHOPPY = 0x06,
  BQR_EVENT_MASK_CONNECT_FAIL = 0x07,
  BQR_EVENT_MASK_RF_STATS_EVENT = 0x08,
  BQR_EVENT_MASK_RF_STATS_MONITOR = 0x09,
  BQR_EVENT_MASK_CONTROLLER_HEALTH_EVENT = 0x0A,
  BQR_EVENT_MASK_CONTROLLER_HEALTH_MONITOR = 0x0B,
  BQR_EVENT_MASK_LEA_BROADCAST_SOURCE_EVENT = 0x0C,
  BQR_EVENT_MASK_LEA_BROADCAST_SOURCE_MONITOR = 0xD,
  BQR_EVENT_MASK_CHANNEL_SOUNDING = 0x0E,
  BQR_EVENT_MASK_VENDOR_SPECIFIC_QUALITY = 0x0F,
  BQR_EVENT_MASK_LMP_LL_MESSAGE_TRACE = 0x10,
  BQR_EVENT_MASK_BT_SCHEDULING_TRACE = 0x11,
  BQR_EVENT_MASK_CONTROLLER_DBG_INFO = 0x12,
  BQR_EVENT_MASK_VENDOR_SPECIFIC_TRACE = 0x1F,
};

// Bit masks for the selected quality event reporting.
static constexpr uint32_t kQualityEventMaskAllOff = 0;
static constexpr uint32_t kQualityEventMaskMonitorMode = 0x1 << BQR_EVENT_MASK_MONITOR_MODE;
static constexpr uint32_t kQualityEventMaskApproachLsto = 0x1 << BQR_EVENT_MASK_APPROACH_LSTO;
static constexpr uint32_t kQualityEventMaskA2dpAudioChoppy = 0x1
                                                             << BQR_EVENT_MASK_A2DP_AUDIO_CHOPPY;
static constexpr uint32_t kQualityEventMaskScoVoiceChoppy = 0x1 << BQR_EVENT_MASK_SCO_VOICE_CHOPPY;
static constexpr uint32_t kQualityEventMaskRootInflammation = 0x1
                                                              << BQR_EVENT_MASK_ROOT_INFLAMMATION;
static constexpr uint32_t kQualityEventMaskEnergyMonitoring = 0x1 << BQR_EVENT_MASK_ENERGY_MONITOR;
static constexpr uint32_t kQualityEventMaskLeAudioChoppy = 0x1 << BQR_EVENT_MASK_LE_AUDIO_CHOPPY;
static constexpr uint32_t kQualityEventMaskConnectFail = 0x1 << BQR_EVENT_MASK_CONNECT_FAIL;
static constexpr uint32_t kQualityEventMaskAdvRFStatsEvent = 0x1 << BQR_EVENT_MASK_RF_STATS_EVENT;
static constexpr uint32_t kQualityEventMaskAdvRFStatsMonitor = 0x1
                                                               << BQR_EVENT_MASK_RF_STATS_MONITOR;
static constexpr uint32_t kQualityEventMaskHealthMonitorStatsEvent =
        0x1 << BQR_EVENT_MASK_CONTROLLER_HEALTH_EVENT;
static constexpr uint32_t kQualityEventMaskControllerHealthMonitor =
        0x1 << BQR_EVENT_MASK_CONTROLLER_HEALTH_MONITOR;
static constexpr uint32_t kQualityEventMaskLeaBroadcastSourceEvent =
        0x1 << BQR_EVENT_MASK_LEA_BROADCAST_SOURCE_EVENT;
static constexpr uint32_t kQualityEventMaskLeaBroadcastSourceMonitor =
        0x1 << BQR_EVENT_MASK_LEA_BROADCAST_SOURCE_MONITOR;
static constexpr uint32_t kQualityEventMaskChannelSounding = 0x1 << BQR_EVENT_MASK_CHANNEL_SOUNDING;
static constexpr uint32_t kQualityEventMaskVendorSpecificQuality =
        0x1 << BQR_EVENT_MASK_VENDOR_SPECIFIC_QUALITY;
static constexpr uint32_t kQualityEventMaskLmpMessageTrace = 0x1
                                                             << BQR_EVENT_MASK_LMP_LL_MESSAGE_TRACE;
static constexpr uint32_t kQualityEventMaskBtSchedulingTrace =
        0x1 << BQR_EVENT_MASK_BT_SCHEDULING_TRACE;
static constexpr uint32_t kQualityEventMaskControllerDbgInfo =
        0x1 << BQR_EVENT_MASK_CONTROLLER_DBG_INFO;
static constexpr uint32_t kQualityEventMaskVendorSpecificTrace =
        0x1 << BQR_EVENT_MASK_VENDOR_SPECIFIC_TRACE;
static constexpr uint32_t kQualityEventMaskAll =
        kQualityEventMaskMonitorMode | kQualityEventMaskApproachLsto |
        kQualityEventMaskA2dpAudioChoppy | kQualityEventMaskScoVoiceChoppy |
        kQualityEventMaskRootInflammation | kQualityEventMaskEnergyMonitoring |
        kQualityEventMaskLeAudioChoppy | kQualityEventMaskConnectFail |
        kQualityEventMaskAdvRFStatsEvent | kQualityEventMaskAdvRFStatsMonitor |
        kQualityEventMaskHealthMonitorStatsEvent | kQualityEventMaskControllerHealthMonitor |
        kQualityEventMaskVendorSpecificQuality | kQualityEventMaskLmpMessageTrace |
        kQualityEventMaskBtSchedulingTrace | kQualityEventMaskControllerDbgInfo |
        kQualityEventMaskLeaBroadcastSourceEvent | kQualityEventMaskLeaBroadcastSourceMonitor |
        kQualityEventMaskChannelSounding | kQualityEventMaskVendorSpecificTrace;
// Define the minimum time interval (in ms) of quality event reporting for the
// selected quality event(s). Controller Firmware should not report the next
// event within the defined Minimum Report Interval * Report Interval
// Multiple.
static constexpr uint16_t kMinReportIntervalNoLimit = 0;
static constexpr uint16_t kMinReportIntervalMaxMs = 0xFFFF;
// Define the Report Interval Multiple of quality event reporting for the
// selected quality event(s). Controller Firmware should not report the next
// event within interval: Minimum Report interval * Report Interval Multiple.
// When Report Interval Multiple set to 0 is equal set to 1
static constexpr uint32_t kReportIntervalMultipleNoLimit = 0;
static constexpr uint32_t kReportIntervalMultipleMax = 0xFFFFFFFF;
// The maximum count of Log Dump related event can be written in the log file.
static constexpr uint16_t kLogDumpEventPerFile = 0x00FF;
// Total length of all parameters of the link Quality related event except
// Vendor Specific Parameters.
static constexpr uint8_t kLinkQualityParamTotalLen = 48;
// 7.8.116 LE Read ISO Link Quality command
static constexpr uint8_t kISOLinkQualityParamTotalLen = 24;
// Total length of all parameters of the ROOT_INFLAMMATION event except Vendor
// Specific Parameters.
static constexpr uint8_t kRootInflammationParamTotalLen = 3;
// Total length of all parameters of the Log Dump related event except Vendor
// Specific Parameters.
static constexpr uint8_t kLogDumpParamTotalLen = 3;
// Remote address and calibration failure count parameters len
// Added in BQR V5.0
static constexpr uint8_t kVersion5_0ParamsTotalLen = 7;
// Added in BQR V6.0
static constexpr uint8_t kVersion6_0ParamsTotalLen = 6;
// Added in BQR V7.0 for Energy Monitor Event
static constexpr uint8_t kEnergyMonitorVersion7_0ParamsTotalLen = 8;

// Warning criteria of the RSSI value.
static constexpr int8_t kCriWarnRssi = -80;
// Warning criteria of the unused AFH channel count.
static constexpr uint8_t kCriWarnUnusedCh = 55;
// The queue size of recording the BQR events.
static constexpr uint8_t kBqrEventQueueSize = 25;
// The Property of BQR event mask configuration.
static constexpr const char* kpPropertyEventMask = "persist.bluetooth.bqr.event_mask";
// The Property of BQR Vendor Quality configuration.
static constexpr const char* kpPropertyVndQualityMask = "persist.bluetooth.bqr.vnd_quality_mask";
// The Property of BQR Vendor Trace configuration.
static constexpr const char* kpPropertyVndTraceMask = "persist.bluetooth.bqr.vnd_trace_mask";
// The Property of BQR minimum report interval configuration.
static constexpr const char* kpPropertyMinReportIntervalMs =
        "persist.bluetooth.bqr.min_interval_ms";
// The Property of BQR minimum report interval multiple.
static constexpr const char* kpPropertyIntervalMultiple = "persist.bluetooth.bqr.interval_multiple";
// Path of the LMP/LL message trace log file.
static constexpr const char* kpLmpLlMessageTraceLogPath =
        "/data/misc/bluetooth/logs/lmp_ll_message_trace.log";
// Path of the last LMP/LL message trace log file.
static constexpr const char* kpLmpLlMessageTraceLastLogPath =
        "/data/misc/bluetooth/logs/lmp_ll_message_trace.log.last";
// Path of the Bluetooth Multi-profile/Coex scheduling trace log file.
static constexpr const char* kpBtSchedulingTraceLogPath =
        "/data/misc/bluetooth/logs/bt_scheduling_trace.log";
// Path of the last Bluetooth Multi-profile/Coex scheduling trace log file.
static constexpr const char* kpBtSchedulingTraceLastLogPath =
        "/data/misc/bluetooth/logs/bt_scheduling_trace.log.last";
// The Property of BQR a2dp choppy report and sco choppy report thresholds.
// A2dp choppy will be reported only when a2dp choppy times is >=
// a2dp_choppy_threshold. The default value in firmware side is 1. It is same
// for sco choppy. Value format is a2dp_choppy_threshold,sco_choppy_threshold
static constexpr const char* kpPropertyChoppyThreshold = "persist.bluetooth.bqr.choppy_threshold";

// The version supports ISO packets start from v1.01(257)
static constexpr uint16_t kBqrIsoVersion = 0x101;
// The version supports vendor quality and trace log starting v1.02(258)
static constexpr uint16_t kBqrVndLogVersion = 0x102;
// The version supports remote address info and calibration failure count
// start from v1.03(259)
static constexpr uint16_t kBqrVersion5_0 = 0x103;
// The REPORT_ACTION_QUERY and BQR_Report_interval starting v1.04(260)
static constexpr uint16_t kBqrVersion6_0 = 0x104;
// The Health MonitorStatsEvent was introduced in v1.05(261)
static constexpr uint16_t kBqrVersion7_0 = 0x105;
// The features LeaBroadcastSource and ChannelSounding were introduced in v1.07(262)
static constexpr uint16_t kBqrVersion8_0 = 0x106;
// Action definition
//
// Action to Add, Delete or Clear the reporting of quality event(s).
// Delete will clear specific quality event(s) reporting. Clear will clear all
// quality events reporting.
enum BqrReportAction : uint8_t {
  REPORT_ACTION_ADD = 0x00,
  REPORT_ACTION_DELETE = 0x01,
  REPORT_ACTION_CLEAR = 0x02,
  REPORT_ACTION_QUERY = 0x03
};

// Report ID definition
enum BqrQualityReportId : uint8_t {
  QUALITY_REPORT_ID_MONITOR_MODE = 0x01,
  QUALITY_REPORT_ID_APPROACH_LSTO = 0x02,
  QUALITY_REPORT_ID_A2DP_AUDIO_CHOPPY = 0x03,
  QUALITY_REPORT_ID_SCO_VOICE_CHOPPY = 0x04,
  QUALITY_REPORT_ID_ROOT_INFLAMMATION = 0x05,
  QUALITY_REPORT_ID_ENERGY_MONITOR = 0x06,
  QUALITY_REPORT_ID_LE_AUDIO_CHOPPY = 0x07,
  QUALITY_REPORT_ID_CONNECT_FAIL = 0x08,
  QUALITY_REPORT_ID_RF_STATS = 0x09,
  QUALITY_REPORT_ID_VENDOR_SPECIFIC_QUALITY = 0x10,
  QUALITY_REPORT_ID_LMP_LL_MESSAGE_TRACE = 0x11,
  QUALITY_REPORT_ID_BT_SCHEDULING_TRACE = 0x12,
  QUALITY_REPORT_ID_CONTROLLER_DBG_INFO = 0x13,
  QUALITY_REPORT_ID_LEA_BROADCAST_SOURCE = 0x16,
  QUALITY_REPORT_ID_CHANNEL_SOUNDING = 0x17,
  QUALITY_REPORT_ID_VENDOR_SPECIFIC_TRACE = 0x20,
};

// Packet Type definition
enum BqrPacketType : uint8_t {
  PACKET_TYPE_ID = 0x01,
  PACKET_TYPE_NULL,
  PACKET_TYPE_POLL,
  PACKET_TYPE_FHS,
  PACKET_TYPE_HV1,
  PACKET_TYPE_HV2,
  PACKET_TYPE_HV3,
  PACKET_TYPE_DV,
  PACKET_TYPE_EV3,
  PACKET_TYPE_EV4,
  PACKET_TYPE_EV5,
  PACKET_TYPE_2EV3,
  PACKET_TYPE_2EV5,
  PACKET_TYPE_3EV3,
  PACKET_TYPE_3EV5,
  PACKET_TYPE_DM1,
  PACKET_TYPE_DH1,
  PACKET_TYPE_DM3,
  PACKET_TYPE_DH3,
  PACKET_TYPE_DM5,
  PACKET_TYPE_DH5,
  PACKET_TYPE_AUX1,
  PACKET_TYPE_2DH1,
  PACKET_TYPE_2DH3,
  PACKET_TYPE_2DH5,
  PACKET_TYPE_3DH1,
  PACKET_TYPE_3DH3,
  PACKET_TYPE_3DH5,
  PACKET_TYPE_ISO = 0x51
};

// Configuration Parameters
typedef struct {
  BqrReportAction report_action;
  uint32_t quality_event_mask;
  uint16_t minimum_report_interval_ms;
  uint32_t vnd_quality_mask;
  uint32_t vnd_trace_mask;
  uint32_t report_interval_multiple;
} BqrConfiguration;

// Link quality related BQR event
typedef struct {
  // Quality report ID.
  uint8_t quality_report_id;
  // Packet type of the connection.
  uint8_t packet_types;
  // Connection handle of the connection.
  uint16_t connection_handle;
  // Performing Role for the connection.
  uint8_t connection_role;
  // Current Transmit Power Level for the connection. This value is the same as
  // the controller's response to the HCI_Read_Transmit_Power_Level HCI command.
  int8_t tx_power_level;
  // Received Signal Strength Indication (RSSI) value for the connection. This
  // value is an absolute receiver signal strength value.
  int8_t rssi;
  // Signal-to-Noise Ratio (SNR) value for the connection. It is the average
  // SNR of all the channels used by the link currently.
  uint8_t snr;
  // Indicates the number of unused channels in AFH_channel_map.
  uint8_t unused_afh_channel_count;
  // Indicates the number of the channels which are interfered and quality is
  // bad but are still selected for AFH.
  uint8_t afh_select_unideal_channel_count;
  // Current Link Supervision Timeout Setting.
  // Unit: N * 0.3125 ms (1 Bluetooth Clock)
  uint16_t lsto;
  // Piconet Clock for the specified Connection_Handle. This value is the same
  // as the controller's response to HCI_Read_Clock HCI command with the
  // parameter "Which_Clock" of 0x01 (Piconet Clock).
  // Unit: N * 0.3125 ms (1 Bluetooth Clock)
  uint32_t connection_piconet_clock;
  // The count of retransmission.
  uint32_t retransmission_count;
  // The count of no RX.
  uint32_t no_rx_count;
  // The count of NAK (Negative Acknowledge).
  uint32_t nak_count;
  // Timestamp of last TX ACK.
  // Unit: N * 0.3125 ms (1 Bluetooth Clock)
  uint32_t last_tx_ack_timestamp;
  // The count of Flow-off (STOP).
  uint32_t flow_off_count;
  // Timestamp of last Flow-on (GO).
  // Unit: N * 0.3125 ms (1 Bluetooth Clock)
  uint32_t last_flow_on_timestamp;
  // Buffer overflow count (how many bytes of TX data are dropped) since the
  // last event.
  uint32_t buffer_overflow_bytes;
  // Buffer underflow count (in byte).
  uint32_t buffer_underflow_bytes;
  // Remote device address
  RawAddress bdaddr;
  // The count of calibration failed items
  uint8_t cal_failed_item_count;
  // The number of packets that are sent out.
  uint32_t tx_total_packets;
  // The number of packets that don't receive an acknowledgment.
  uint32_t tx_unacked_packets;
  // The number of packets that are not sent out by its flush point.
  uint32_t tx_flushed_packets;
  // The number of packets that Link Layer transmits a CIS Data PDU in the last
  // subevent of a CIS event.
  uint32_t tx_last_subevent_packets;
  // The number of received packages with CRC error since the last event.
  uint32_t crc_error_packets;
  // The number of duplicate(retransmission) packages that are received since
  // the last event.
  uint32_t rx_duplicate_packets;
  // The number of unreceived packets is the same as the parameter of LE Read
  // ISO Link Quality command.
  uint32_t rx_unreceived_packets;
  // Bitmask to indicate various coex related information
  uint16_t coex_info_mask;
  // For the controller vendor to obtain more vendor specific parameters.
  const uint8_t* vendor_specific_parameter;
} BqrLinkQualityEvent;

// Energy Monitor BQR event
typedef struct {
  // Quality report ID.
  uint8_t quality_report_id;
  // Average current consumption of all activities consumed by the controller (mA)
  uint16_t avg_current_consume;
  // Total time in the idle (low power states, sleep) state. (ms)
  uint32_t idle_total_time;
  // Indicates how many times the controller enters the idle state.
  uint32_t idle_state_enter_count;
  // Total time in the active (inquiring, paging, ACL/SCO/eSCO/BIS/CIS traffic, processing any task)
  // state. (ms)
  uint32_t active_total_time;
  // Indicates how many times the controller enters the active states.
  uint32_t active_state_enter_count;
  // Total time in the BR/EDR specific Tx(Transmitting for ACL/SCO/eSCO traffic)state (ms)
  uint32_t bredr_tx_total_time;
  // Indicates how many times the controller enters the BR/EDR specific Tx state.
  uint32_t bredr_tx_state_enter_count;
  // Average Tx power level of all the BR/EDR link(s) (dBm)
  uint8_t bredr_tx_avg_power_lv;
  // Total time in the BR/EDR specific Rx (Receiving from ACL/SCO/eSCO traffic) state. (ms)
  uint32_t bredr_rx_total_time;
  // Indicates how many times the controller enters the BR/EDR specific Rx state. (ms)
  uint32_t bredr_rx_state_enter_count;
  // Total time in the LE specific Tx (Transmitting for either ACL/BIS/CIS or LE advertising
  // traffic) state (ms)
  uint32_t le_tx_total_time;
  // Indicates how many times the controller enters theLE specific Tx state.
  uint32_t le_tx_state_enter_count;
  // Average Tx power level of all the LE link(s) (dBm)
  uint8_t le_tx_avg_power_lv;
  // Total time in the LE specific Rx (Receiving from either ACL/BIS/CIS or LE scanning traffic)
  // state. (ms)
  uint32_t le_rx_total_time;
  // Indicates how many times the controller enters the LE specific Rx state
  uint32_t le_rx_state_enter_count;
  // The total time duration to collect power related information (ms)
  uint32_t tm_period;
  // The time duration of RX active in one chain
  uint32_t rx_active_one_chain_time;
  // The time duration of RX active in two chain
  uint32_t rx_active_two_chain_time;
  // The time duration of internal TX active in one chain
  uint32_t tx_ipa_active_one_chain_time;
  // The time duration of internal TX active in two chain
  uint32_t tx_ipa_active_two_chain_time;
  // The time duration of external TX active in one chain
  uint32_t tx_epa_active_one_chain_time;
  // The time duration of external TX active in two chain
  uint32_t tx_epa_active_two_chain_time;
} __attribute__((__packed__)) BqrEnergyMonitorEvent;

// BQR Energy Monitoring Event Packet for BQRv7
typedef struct __attribute__((__packed__)) {
  BqrEnergyMonitorEvent base;
  // The time duration of the total RX active time on BR/EDR scan
  uint32_t bredr_rx_active_scan_total_time;
  // The time duration of the total RX active time on BLE scan
  uint32_t le_rx_active_scan_total_time;
} BqrEnergyMonitoringEventV7;

static constexpr uint8_t kEnergyMonitorParamTotalLen = sizeof(BqrEnergyMonitorEvent);

// RF Stats BQR event
typedef struct {
  // Quality report ID.
  uint8_t quality_report_id;
  // Extension for Further usage = 0x01 for BQRv6
  uint8_t ext_info;
  // time period (ms)
  uint32_t tm_period;
  // Packet counter of iPA BF
  uint32_t tx_pw_ipa_bf;
  // Packet counter of ePA BF
  uint32_t tx_pw_epa_bf;
  // Packet counter of iPA Div
  uint32_t tx_pw_ipa_div;
  // Packet counter of ePA Div
  uint32_t tx_pw_epa_div;
  // Packet counter of RSSI chain > -50 dBm
  uint32_t rssi_ch_50;
  // Packet counter of RSSI chain between  -50 dBm ~ >-55 dBm
  uint32_t rssi_ch_50_55;
  // Packet counter of RSSI chain between  -55 dBm ~ >-60 dBm
  uint32_t rssi_ch_55_60;
  // Packet counter of RSSI chain between  -60 dBm ~ >-65 dBm
  uint32_t rssi_ch_60_65;
  // Packet counter of RSSI chain between  -65 dBm ~ >-70 dBm
  uint32_t rssi_ch_65_70;
  // Packet counter of RSSI chain between  -70 dBm ~ >-75 dBm
  uint32_t rssi_ch_70_75;
  // Packet counter of RSSI chain between  -75 dBm ~ >-80 dBm
  uint32_t rssi_ch_75_80;
  // Packet counter of RSSI chain between  -80 dBm ~ >-85 dBm
  uint32_t rssi_ch_80_85;
  // Packet counter of RSSI chain between  -85 dBm ~ >-90 dBm
  uint32_t rssi_ch_85_90;
  // Packet counter of RSSI chain  < -90 dBm
  uint32_t rssi_ch_90;
  // Packet counter of RSSI delta < 2 dBm
  uint32_t rssi_delta_2_down;
  // Packet counter of  RSSI delta between 2 dBm ~ 5 dBm
  uint32_t rssi_delta_2_5;
  // Packet counter of  RSSI delta between 5 dBm ~ 8 dB
  uint32_t rssi_delta_5_8;
  // Packet counter of  RSSI delta between 8 dBm ~ 11 dBm
  uint32_t rssi_delta_8_11;
  // Packet counter of RSSI delta > 11 dBm
  uint32_t rssi_delta_11_up;
} __attribute__((__packed__)) BqrRFStatsEvent;

// Total length of all parameters of the RF Stats event
static constexpr uint8_t kRFStatsParamTotalLen = sizeof(BqrRFStatsEvent);

// LEA broadcast source BQR event
typedef struct {
  // Quality Report ID for the LEA broadcast source event. Should be 0x22.
  uint8_t quality_report_id;
  // The BIG handle allocated by the host.
  uint8_t big_handle;
  // The BD_ADDR type of the broadcast source sent over the air.
  uint8_t broadcast_source_bd_addr_type;
  // The BD_ADDR of the broadcast source sent over the air.
  uint8_t broadcast_source_bd_addr[6];
  // The BIG channel map that the controller prefers based on channel classification.
  uint8_t big_source_prefer_channel_map[5];
  // The BIG channel map that the controller actually used.
  uint8_t big_source_used_channel_map[5];
  // The current TX power used in the Broadcast ID.
  uint8_t big_tx_power;
  // Timestamp for the specified BIG Handle, based on the Piconet Clock.
  uint32_t timestamp;
  // The subscribed Broadcast ID of the client device.
  uint8_t broadcast_id_of_receiver_subscribed[3];
  // The BD_ADDR type of the broadcast receiver.
  uint8_t broadcast_receiver_bd_addr_type;
  // The BD_ADDR of the broadcast receiver.
  uint8_t broadcast_receiver_bd_addr[6];
  // The time duration to collect performance information, in milliseconds.
  uint32_t time_duration;
  // BIS choppy count within the time duration.
  uint32_t bis_choppy_count;
  // Packet Error Rate within the time duration.
  uint32_t per;
  // Number of times the receiver is out of sync.
  uint32_t no_sync_count;
  // Indicates the channel map of the broadcast receiver.
  uint8_t receiver_prefer_channel_map[5];
  // The power level at which a packet containing data is transmitted from the broadcast receiver.
  uint8_t receiver_tx_power;
  // The RSSI of the received packets from the broadcast receiver.
  uint8_t rssi;
  // Reserved for future use.
  uint8_t reserved[4];
} __attribute__((__packed__)) BqrLeaBroadcastSourceEvent;

// Total length of all parameters of the RF Stats event
static constexpr uint8_t kLeaBisSourceParamTotalLen = sizeof(BqrLeaBroadcastSourceEvent);

// Channel Sounding BQR event
// Corresponds to the "AGC Gain Settings" in the document.
struct AgcGainSettings {
  // AGC gain for core 0.
  uint8_t agc_gain_core0;
  // AGC gain for core 1.
  uint8_t agc_gain_core1;
  // TX power index for core 0.
  uint8_t tx_power_idx_core0;
  // TX power index for core 1.
  uint8_t tx_power_idx_core1;
  // Whether ePA is on for core 0.
  uint8_t is_epa_on_core0;
  // Whether ePA is on for core 1.
  uint8_t is_epa_on_core1;
};

// Corresponds to the "Tone Quality Indicator (TQI) Count" in the document.
struct TqiCount {
  // Counter for TQI = 0x0.
  uint8_t tqi_0;
  // Counter for TQI = 0x1.
  uint8_t tqi_1;
  // Counter for TQI = 0x2.
  uint8_t tqi_2;
  // Counter for TQI = 0x3.
  uint8_t tqi_3;
};

typedef struct {
  // Quality Report ID for the CS BQR event.
  uint8_t quality_report_id;
  // The CS handle allocated by host.
  uint16_t connection_handle;
  // CS config ID. Range 0 to 3.
  uint8_t cs_config_id;
  // CS procedure count since completion of the Channel Sounding Security Start procedure.
  uint16_t procedure_counter;
  // Frequency compensation value in units of 0.01 ppm.
  int16_t frequency_compensation;
  // Reference power level in dBm.
  uint8_t reference_power_level;
  // Selected TX power in dBm.
  uint8_t selected_tx_power;
  // AGC Gain settings.
  AgcGainSettings agc_gain_settings;
  // Duration for each CS subevent in microseconds.
  uint8_t subevent_len[3];
  // Number of ACL connection events between consecutive CS procedure anchor points.
  uint16_t procedure_interval;
  // CS procedure done status.
  uint8_t cs_procedure_done_status;
  // CS subevent done status.
  uint8_t cs_subevent_done_status;
  // CS abort reason.
  uint8_t cs_abort_reason;
  // Packet Quality for mode-0.
  uint8_t packet_quality_mode_0[3];
  // Packet RSSI for mode-0 in dBm.
  uint8_t packet_rssi_mode_0[3];
  // Packet Antenna for mode-0.
  uint8_t packet_antenna_mode_0[3];
  // Tone Quality Indicator (TQI) Count.
  TqiCount tqi_count[4];
  // Counter for non-mode-0 Packet_Quality.
  uint8_t packet_quality_count[3];
  // Counter for NADM > 0xX.
  uint8_t packet_nadm_count;
} __attribute__((__packed__)) BqrChannelSoundingEvent;

// Total length of all parameters of the Channel Sounding event
static constexpr uint8_t kCSParamTotalLen = sizeof(BqrChannelSoundingEvent);

// Log dump related BQR event
typedef struct {
  // Quality report ID.
  uint8_t quality_report_id;
  // Connection handle of the connection.
  uint16_t connection_handle;
  // For the controller vendor to obtain more vendor specific parameters.
  const uint8_t* vendor_specific_parameter;
} BqrLogDumpEvent;

// BQR sub-event of Vendor Specific Event
class BqrVseSubEvt {
public:
  // Parse the Link Quality related BQR event.
  //
  // @param length Total length of all parameters contained in the sub-event.
  // @param p_param_buf A pointer to the parameters contained in the sub-event.
  void ParseBqrLinkQualityEvt(uint8_t length, const uint8_t* p_param_buf);
  // Parse the Energy Monitor BQR event.
  //
  // @param length Total length of all parameters contained in the sub-event.
  // @param p_param_buf A pointer to the parameters contained in the sub-event.
  //
  // @return true if the event was parsed successfully, false otherwise.
  bool ParseBqrEnergyMonitorEvt(uint8_t length, const uint8_t* p_param_buf);
  // Parse the RF Stats BQR event.
  //
  // @param length Total length of all parameters contained in the sub-event.
  // @param p_param_buf A pointer to the parameters contained in the sub-event.
  //
  // @return true if the event was parsed successfully, false otherwise.
  bool ParseBqrRFStatsEvt(uint8_t length, const uint8_t* p_param_buf);
  // Write the LMP/LL message trace to the log file.
  //
  // @param fd The File Descriptor of the log file.
  // @param length Total length of all parameters contained in the sub-event.
  // @param p_param_buf A pointer to the parameters contained in the sub-event.
  void WriteLmpLlTraceLogFile(int fd, uint8_t length, const uint8_t* p_param_buf);
  // Write the Bluetooth Multi-profile/Coex scheduling trace to the log file.
  //
  // @param fd The File Descriptor of the log file.
  // @param length Total length of all parameters contained in the sub-event.
  // @param p_param_buf A pointer to the parameters contained in the sub-event.
  void WriteBtSchedulingTraceLogFile(int fd, uint8_t length, const uint8_t* p_param_buf);
  // Get a string representation of the Bluetooth Quality event.
  //
  // @return a string representation of the Bluetooth Quality event.
  std::string ToString() const;

  friend std::ostream& operator<<(std::ostream& os, const BqrVseSubEvt& a) {
    return os << a.ToString();
  }

  virtual ~BqrVseSubEvt() = default;
  // Link Quality related BQR event
  BqrLinkQualityEvent bqr_link_quality_event_ = {};
  // Energy Monitor BQR event
  BqrEnergyMonitoringEventV7 bqr_energy_monitor_event_ = {};
  // RF Stats BQR event
  BqrRFStatsEvent bqr_rf_stats_event_ = {};
  // Log Dump related BQR event
  BqrLogDumpEvent bqr_log_dump_event_ = {};
  // Local wall clock timestamp of receiving BQR VSE sub-event
  std::tm tm_timestamp_ = {};
};

BluetoothQualityReportInterface* getBluetoothQualityReportInterface();

// Enable Bluetooth Quality Report mechanism.
//
// Which Quality event will be enabled is according to the setting of the
// property "persist.bluetooth.bqr.event_mask".
// And the minimum time interval of quality event reporting depends on the
// setting of property "persist.bluetooth.bqr.min_interval_ms".
//
// @param to_bind gives the postable for the callback.
void EnableBtQualityReport(common::PostableContext* to_bind);

// Disable Bluetooth Quality Report mechanism.
void DisableBtQualityReport();

// Dump Bluetooth Quality Report information.
//
// @param fd The file descriptor to use for dumping information.
void DebugDump(int fd);

// Configure the file descriptor for the LMP/LL message trace log.
void SetLmpLlMessageTraceLogFd(int fd);

}  // namespace bqr
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::bqr::BqrReportAction>
    : enum_formatter<bluetooth::bqr::BqrReportAction> {};
template <>
struct formatter<bluetooth::bqr::BqrVseSubEvt> : ostream_formatter {};
}  // namespace std
