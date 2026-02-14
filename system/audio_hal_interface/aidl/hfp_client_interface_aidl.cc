/*
 * Copyright 2023 The Android Open Source Project
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
#define LOG_TAG "BTAudioHfpAIDL"

#include "hfp_client_interface_aidl.h"

#include <bluetooth/log.h>
#include <bluetooth/metrics/bluetooth_event.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>

#include <map>

#include "aidl/android/hardware/bluetooth/audio/AudioConfiguration.h"
#include "aidl/transport_instance.h"
#include "bt_status.h"
#include "bta/ag/bta_ag_int.h"
#include "btif/include/btif_hf.h"
#include "btif_status.h"
#include "hardware/bluetooth.h"
#include "hardware/bluetooth_headset_interface.h"
#include "provider_info.h"
#include "stack/include/btm_api_types.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace hfp {

using namespace metrics;

static std::map<BtStatus, BluetoothAudioCtrlAck> status_to_ack_map = {
        {BtifStatus(), BluetoothAudioCtrlAck::SUCCESS_FINISHED},
        {BtifStatus(DONE), BluetoothAudioCtrlAck::SUCCESS_FINISHED},
        {BtifStatus(FAIL), BluetoothAudioCtrlAck::FAILURE},
        {BtifStatus(NOT_READY), BluetoothAudioCtrlAck::FAILURE_BUSY},
        {BtifStatus(BUSY), BluetoothAudioCtrlAck::FAILURE_BUSY},
        {BtifStatus(UNSUPPORTED), BluetoothAudioCtrlAck::FAILURE_UNSUPPORTED},
};

static std::string command_to_text(tHFP_CTRL_CMD cmd) {
  switch (cmd) {
    case HFP_CTRL_CMD_NONE:
      return "none";
    case HFP_CTRL_CMD_CHECK_READY:
      return "check ready";
    case HFP_CTRL_CMD_START:
      return "start";
    case HFP_CTRL_CMD_STOP:
      return "stop";
    case HFP_CTRL_CMD_SUSPEND:
      return "suspend";
    case HFP_CTRL_GET_INPUT_AUDIO_CONFIG:
      return "get input audio config";
    case HFP_CTRL_GET_OUTPUT_AUDIO_CONFIG:
      return "get output audio config";
    case HFP_CTRL_SET_OUTPUT_AUDIO_CONFIG:
      return "set output audio config";
    case HFP_CTRL_GET_PRESENTATION_POSITION:
      return "get presentation position";
    default:
      return "undefined";
  }
}

static tBTA_AG_SCB* get_hfp_active_device_control_block() {
  const RawAddress addr = bta_ag_get_active_device();
  if (addr.IsEmpty()) {
    log::error("No active device found");
    return nullptr;
  }
  auto idx = bta_ag_idx_by_bdaddr(&addr);
  if (idx == 0) {
    log::error("No index found for active device");
    return nullptr;
  }
  auto cb = bta_ag_scb_by_idx(idx);
  if (cb == nullptr) {
    log::error("No callback for the active device");
    return nullptr;
  }
  return cb;
}

std::unordered_map<tBTA_AG_UUID_CODEC, ::hfp::sco_config> HfpTransport::GetHfpScoConfig(
        SessionType sessionType) {
  auto providerInfo = ::bluetooth::audio::aidl::ProviderInfo::GetProviderInfo(sessionType);
  return providerInfo->GetHfpScoConfig();
}

bool HfpTransport::IsStreamActive() { return is_stream_active; }

void HfpTransport::SetStreamActive(bool active) { is_stream_active = active; }

HfpTransport::HfpTransport() {
  hfp_pending_cmd_ = HFP_CTRL_CMD_NONE;
  is_stream_active = false;
}

BluetoothAudioCtrlAck HfpTransport::StartRequest() {
  auto cb = get_hfp_active_device_control_block();
  if (cb == nullptr) {
    return BluetoothAudioCtrlAck::FAILURE;
  }

  if (hfp_pending_cmd_ == HFP_CTRL_CMD_START) {
    log::info("HFP_CTRL_CMD_START in progress");
    is_stream_active = true;
    LogMetricHfpStartStream(cb->peer_addr);
    return BluetoothAudioCtrlAck::PENDING;
  } else if (hfp_pending_cmd_ != HFP_CTRL_CMD_NONE) {
    log::warn("busy in pending_cmd={}, {}", hfp_pending_cmd_, command_to_text(hfp_pending_cmd_));
    return BluetoothAudioCtrlAck::FAILURE_BUSY;
  }

  if (bta_ag_sco_is_open(cb)) {
    // Already started, ACK back immediately.
    is_stream_active = true;
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  }

  /* Post start SCO event and wait for sco to open */
  hfp_pending_cmd_ = HFP_CTRL_CMD_START;

  // as ConnectAudio only queues the command into main thread, keep PENDING
  // status
  auto status = bluetooth::headset::GetInterface()->ConnectAudio(cb->peer_addr, 0);
  log::info("ConnectAudio status = {}", status);
  auto ctrl_ack = status_to_ack_map.find(status);
  if (ctrl_ack == status_to_ack_map.end()) {
    log::warn("Unmapped status={}", status);
    hfp_pending_cmd_ = HFP_CTRL_CMD_NONE;
    return BluetoothAudioCtrlAck::FAILURE;
  }
  if (ctrl_ack->second != BluetoothAudioCtrlAck::SUCCESS_FINISHED) {
    hfp_pending_cmd_ = HFP_CTRL_CMD_NONE;
    return ctrl_ack->second;
  }
  is_stream_active = true;
  LogMetricHfpStartStream(cb->peer_addr);
  return BluetoothAudioCtrlAck::PENDING;
}

void HfpTransport::StopRequest() {
  log::info("handling");

  is_stream_active = false;

  RawAddress addr = bta_ag_get_active_device();
  if (addr.IsEmpty()) {
    log::error("No active device found");
    return;
  }
  hfp_pending_cmd_ = HFP_CTRL_CMD_STOP;
  auto status = bluetooth::headset::GetInterface()->DisconnectAudio(addr);
  log::info("DisconnectAudio status = {}", status);
  hfp_pending_cmd_ = HFP_CTRL_CMD_NONE;
  return;
}

void HfpTransport::ResetPendingCmd() { hfp_pending_cmd_ = HFP_CTRL_CMD_NONE; }

uint8_t HfpTransport::GetPendingCmd() const { return hfp_pending_cmd_; }

// Unimplemented functions
void HfpTransport::LogBytesProcessed(size_t /*bytes_read*/) {}

BluetoothAudioCtrlAck HfpTransport::SuspendRequest() {
  log::info("handling");
  if (hfp_pending_cmd_ != HFP_CTRL_CMD_NONE) {
    log::warn("busy in pending_cmd={}, {}", hfp_pending_cmd_, command_to_text(hfp_pending_cmd_));
    return BluetoothAudioCtrlAck::FAILURE_BUSY;
  }

  RawAddress addr = bta_ag_get_active_device();
  if (addr.IsEmpty()) {
    log::info("No active device found, mark SCO as suspended");
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  }

  hfp_pending_cmd_ = HFP_CTRL_CMD_SUSPEND;
  auto instance = bluetooth::headset::GetInterface();
  if (instance == nullptr) {
    log::error("headset instance is nullptr");
    return BluetoothAudioCtrlAck::FAILURE;
  }
  auto status = instance->DisconnectAudio(addr);
  log::info("DisconnectAudio status = {}", status);
  // once disconnect audio is queued, not waiting on that
  // because disconnect audio request can come when audio is disconnected
  hfp_pending_cmd_ = HFP_CTRL_CMD_NONE;
  if (status) {
    LogMetricHfpSuspendStream(addr);
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  } else {
    return BluetoothAudioCtrlAck::FAILURE;
  }
}

void HfpTransport::SetLatencyMode(LatencyMode /*latency_mode*/) {}

void HfpTransport::SourceMetadataChanged(const source_metadata_v7_t& /*source_metadata*/) {}

void HfpTransport::SinkMetadataChanged(const sink_metadata_v7_t&) {}

void HfpTransport::ResetPresentationPosition() {}

bool HfpTransport::GetPresentationPosition(uint64_t* /*remote_delay_report_ns*/,
                                           uint64_t* /*total_bytes_read*/,
                                           timespec* /*data_position*/) {
  return false;
}

// Source / sink functions
HfpDecodingTransport::HfpDecodingTransport(SessionType session_type)
    : IBluetoothSourceTransportInstance(session_type, (AudioConfiguration){}) {
  transport_ = new HfpTransport();
}

HfpDecodingTransport::~HfpDecodingTransport() { delete transport_; }

BluetoothAudioCtrlAck HfpDecodingTransport::StartRequest(bool /*is_low_latency*/) {
  return transport_->StartRequest();
}

BluetoothAudioCtrlAck HfpDecodingTransport::SuspendRequest() {
  transport_->SetStreamActive(false);

  if (HfpEncodingTransport::instance_ && HfpEncodingTransport::instance_->IsStreamActive()) {
    log::info("SCO will suspend when encoding transport suspends.");
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  }

  return transport_->SuspendRequest();
}

void HfpDecodingTransport::SetLatencyMode(LatencyMode latency_mode) {
  transport_->SetLatencyMode(latency_mode);
}

bool HfpDecodingTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                                   uint64_t* total_bytes_written,
                                                   timespec* data_position) {
  return transport_->GetPresentationPosition(remote_delay_report_ns, total_bytes_written,
                                             data_position);
}

void HfpDecodingTransport::SourceMetadataChanged(const source_metadata_v7_t& source_metadata) {
  transport_->SourceMetadataChanged(source_metadata);
}

void HfpDecodingTransport::SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata) {
  transport_->SinkMetadataChanged(sink_metadata);
}

void HfpDecodingTransport::ResetPresentationPosition() { transport_->ResetPresentationPosition(); }

void HfpDecodingTransport::LogBytesWritten(size_t bytes_written) {
  transport_->LogBytesProcessed(bytes_written);
}

uint8_t HfpDecodingTransport::GetPendingCmd() const { return transport_->GetPendingCmd(); }

void HfpDecodingTransport::ResetPendingCmd() { transport_->ResetPendingCmd(); }

bool HfpDecodingTransport::IsStreamActive() { return transport_->IsStreamActive(); }

void HfpDecodingTransport::StopRequest() { transport_->StopRequest(); }

HfpEncodingTransport::HfpEncodingTransport(SessionType session_type)
    : IBluetoothSinkTransportInstance(session_type, (AudioConfiguration){}) {
  transport_ = new HfpTransport();
}

HfpEncodingTransport::~HfpEncodingTransport() { delete transport_; }

BluetoothAudioCtrlAck HfpEncodingTransport::StartRequest(bool /*is_low_latency*/) {
  return transport_->StartRequest();
}

BluetoothAudioCtrlAck HfpEncodingTransport::SuspendRequest() {
  transport_->SetStreamActive(false);

  if (HfpDecodingTransport::instance_ && HfpDecodingTransport::instance_->IsStreamActive()) {
    log::info("SCO will suspend when decoding transport suspends.");
    return BluetoothAudioCtrlAck::SUCCESS_FINISHED;
  }

  return transport_->SuspendRequest();
}

void HfpEncodingTransport::StopRequest() { transport_->StopRequest(); }

void HfpEncodingTransport::SetLatencyMode(LatencyMode latency_mode) {
  transport_->SetLatencyMode(latency_mode);
}

bool HfpEncodingTransport::GetPresentationPosition(uint64_t* remote_delay_report_ns,
                                                   uint64_t* total_bytes_written,
                                                   timespec* data_position) {
  return transport_->GetPresentationPosition(remote_delay_report_ns, total_bytes_written,
                                             data_position);
}

void HfpEncodingTransport::SourceMetadataChanged(const source_metadata_v7_t& source_metadata) {
  transport_->SourceMetadataChanged(source_metadata);
}

void HfpEncodingTransport::SinkMetadataChanged(const sink_metadata_v7_t& sink_metadata) {
  transport_->SinkMetadataChanged(sink_metadata);
}

void HfpEncodingTransport::ResetPresentationPosition() { transport_->ResetPresentationPosition(); }

void HfpEncodingTransport::LogBytesRead(size_t bytes_written) {
  transport_->LogBytesProcessed(bytes_written);
}

uint8_t HfpEncodingTransport::GetPendingCmd() const { return transport_->GetPendingCmd(); }

void HfpEncodingTransport::ResetPendingCmd() { transport_->ResetPendingCmd(); }

bool HfpEncodingTransport::IsStreamActive() { return transport_->IsStreamActive(); }

}  // namespace hfp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
