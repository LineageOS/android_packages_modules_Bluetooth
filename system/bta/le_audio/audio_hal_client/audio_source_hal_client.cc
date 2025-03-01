/******************************************************************************
 *
 * Copyright 2019 HIMSA II K/S - www.himsa.com.Represented by EHIMA -
 * www.ehima.com
 * Copyright (c) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <ostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "audio/asrc/asrc_resampler.h"
#include "audio_hal_client.h"
#include "audio_hal_interface/le_audio_software.h"
#include "bta/le_audio/codec_manager.h"
#include "common/message_loop_thread.h"
#include "common/repeating_timer.h"
#include "common/time_util.h"
#include "hardware/bluetooth.h"
#include "le_audio/broadcaster/broadcaster_types.h"
#include "le_audio/le_audio_types.h"
#include "osi/include/wakelock.h"
#include "stack/include/main_thread.h"

namespace bluetooth::le_audio {
namespace {
struct AudioHalStats {
  size_t media_read_total_underflow_bytes;
  size_t media_read_total_underflow_count;
  uint64_t media_read_last_underflow_us;

  AudioHalStats() { Reset(); }

  void Reset() {
    media_read_total_underflow_bytes = 0;
    media_read_total_underflow_count = 0;
    media_read_last_underflow_us = 0;
  }
} sStats;

class SourceImpl : public LeAudioSourceAudioHalClient {
  enum LeAudioSinkHalState {
    HAL_UNINITIALIZED,
    HAL_STOPPED,
    HAL_STARTED,
  } le_audio_sink_hal_state_;

public:
  // Interface implementation
  bool Start(const LeAudioCodecConfiguration& codec_configuration,
             LeAudioSourceAudioHalClient::Callbacks* audioReceiver, DsaModes dsa_modes) override;
  void Stop() override;
  void ConfirmStreamingRequest() override;
  void CancelStreamingRequest() override;
  void UpdateRemoteDelay(uint16_t remote_delay_ms) override;
  void UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) override;
  std::optional<broadcaster::BroadcastConfiguration> GetBroadcastConfig(
          const std::vector<std::pair<types::LeAudioContextType, uint8_t>>& subgroup_quality,
          const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs)
          const override;
  std::optional<::bluetooth::le_audio::types::AudioSetConfiguration> GetUnicastConfig(
          const CodecManager::UnicastConfigurationRequirements& requirements) const override;
  void UpdateBroadcastAudioConfigToHal(
          const ::bluetooth::le_audio::broadcast_offload_config& config) override;
  void SuspendedForReconfiguration() override;
  void ReconfigurationComplete() override;

  // Internal functionality
  SourceImpl(bool is_broadcaster)
      : le_audio_sink_hal_state_(HAL_UNINITIALIZED),
        audio_timer_(
                /* clock_tick_us= */ bluetooth::common::time_get_audio_server_tick_us),
        is_broadcaster_(is_broadcaster) {}
  ~SourceImpl() override {
    if (le_audio_sink_hal_state_ != HAL_UNINITIALIZED) {
      Release();
    }
  }

  bool OnResumeReq(bool start_media_task);
  bool OnSuspendReq();
  bool OnMetadataUpdateReq(const source_metadata_v7_t& source_metadata, DsaMode latency_mode);
  bool Acquire();
  void Release();
  bool InitAudioSinkThread();

  bluetooth::common::MessageLoopThread* worker_thread_;
  bluetooth::common::RepeatingTimer audio_timer_;
  LeAudioCodecConfiguration source_codec_config_;
  void StartAudioTicks();
  void StopAudioTicks();
  void SendAudioData();

  bool is_broadcaster_;

  bluetooth::audio::le_audio::LeAudioClientInterface::Sink* halSinkInterface_ = nullptr;
  LeAudioSourceAudioHalClient::Callbacks* audioSourceCallbacks_ = nullptr;
  std::mutex audioSourceCallbacksMutex_;
  std::unique_ptr<bluetooth::audio::asrc::SourceAudioHalAsrc> asrc_;

  base::WeakPtrFactory<SourceImpl> weak_factory_{this};
};

bool SourceImpl::Acquire() {
  auto sink_stream_cb = bluetooth::audio::le_audio::StreamCallbacks{
          .on_resume_ = std::bind(&SourceImpl::OnResumeReq, this, std::placeholders::_1),
          .on_suspend_ = std::bind(&SourceImpl::OnSuspendReq, this),
          .on_metadata_update_ = std::bind(&SourceImpl::OnMetadataUpdateReq, this,
                                           std::placeholders::_1, std::placeholders::_2),
          .on_sink_metadata_update_ =
                  [](const sink_metadata_v7_t& /*sink_metadata*/) {
                    // TODO: update microphone configuration based on sink metadata
                    return true;
                  },
  };

  /* Get pointer to singleton LE audio client interface */
  auto halInterface = audio::le_audio::LeAudioClientInterface::Get();
  if (halInterface == nullptr) {
    log::error("Can't get LE Audio HAL interface");
    return false;
  }

  halSinkInterface_ = halInterface->GetSink(sink_stream_cb, get_main_thread(), is_broadcaster_);

  if (halSinkInterface_ == nullptr) {
    log::error("Can't get Audio HAL Audio sink interface");
    return false;
  }

  log::info("");
  le_audio_sink_hal_state_ = HAL_STOPPED;
  return this->InitAudioSinkThread();
}

void SourceImpl::Release() {
  if (le_audio_sink_hal_state_ == HAL_UNINITIALIZED) {
    log::warn("Audio HAL Audio sink is not running");
    return;
  }

  log::info("");
  worker_thread_->ShutDown();

  if (halSinkInterface_) {
    if (le_audio_sink_hal_state_ == HAL_STARTED) {
      halSinkInterface_->StopSession();
      le_audio_sink_hal_state_ = HAL_STOPPED;
    }

    halSinkInterface_->Cleanup();

    auto halInterface = audio::le_audio::LeAudioClientInterface::Get();
    if (halInterface != nullptr) {
      halInterface->ReleaseSink(halSinkInterface_);
    } else {
      log::error("Can't get LE Audio HAL interface");
    }

    le_audio_sink_hal_state_ = HAL_UNINITIALIZED;
    halSinkInterface_ = nullptr;
  }
}

bool SourceImpl::OnResumeReq(bool /*start_media_task*/) {
  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  if (audioSourceCallbacks_ == nullptr) {
    log::error("audioSourceCallbacks_ not set");
    return false;
  }
  bt_status_t status =
          do_in_main_thread(base::BindOnce(&LeAudioSourceAudioHalClient::Callbacks::OnAudioResume,
                                           audioSourceCallbacks_->weak_factory_.GetWeakPtr()));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

void SourceImpl::SendAudioData() {
  if (halSinkInterface_ == nullptr) {
    log::error("Audio HAL Audio sink interface not acquired - aborting");
    return;
  }

  // 24 bit audio is aligned to 32bit
  int bytes_per_sample = (source_codec_config_.bits_per_sample == 24)
                                 ? 4
                                 : (source_codec_config_.bits_per_sample / 8);
  uint32_t bytes_per_tick = (source_codec_config_.num_channels * source_codec_config_.sample_rate *
                             source_codec_config_.data_interval_us / 1000 * bytes_per_sample) /
                            1000;
  std::vector<uint8_t> data(bytes_per_tick);

  uint32_t bytes_read = halSinkInterface_->Read(data.data(), bytes_per_tick);
  if (bytes_read < bytes_per_tick) {
    sStats.media_read_total_underflow_bytes += bytes_per_tick - bytes_read;
    sStats.media_read_total_underflow_count++;
    sStats.media_read_last_underflow_us = bluetooth::common::time_get_os_boottime_us();
  }

  auto asrc_buffers = asrc_->Run(data);

  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  for (auto buffer : asrc_buffers) {
    if (audioSourceCallbacks_ != nullptr) {
      audioSourceCallbacks_->OnAudioDataReady(*buffer);
    }
  }
}

bool SourceImpl::InitAudioSinkThread() {
  const std::string thread_name = is_broadcaster_ ? "bt_le_audio_broadcast_sink_worker_thread"
                                                  : "bt_le_audio_unicast_sink_worker_thread";
  worker_thread_ = new bluetooth::common::MessageLoopThread(thread_name);

  worker_thread_->StartUp();
  if (!worker_thread_->IsRunning()) {
    log::error("Unable to start up the BLE audio sink worker thread");
    return false;
  }

  /* Schedule the rest of the operations */
  if (!worker_thread_->EnableRealTimeScheduling()) {
#if defined(__ANDROID__)
    log::fatal("Failed to increase media thread priority");
#endif
  }

  return true;
}

void SourceImpl::StartAudioTicks() {
  wakelock_acquire();
  asrc_ = std::make_unique<bluetooth::audio::asrc::SourceAudioHalAsrc>(
          worker_thread_, source_codec_config_.num_channels, source_codec_config_.sample_rate,
          source_codec_config_.bits_per_sample, source_codec_config_.data_interval_us);
  audio_timer_.SchedulePeriodic(
          worker_thread_->GetWeakPtr(),
          base::BindRepeating(&SourceImpl::SendAudioData, weak_factory_.GetWeakPtr()),
          std::chrono::microseconds(source_codec_config_.data_interval_us));
}

void SourceImpl::StopAudioTicks() {
  audio_timer_.CancelAndWait();
  asrc_.reset(nullptr);
  wakelock_release();
}

bool SourceImpl::OnSuspendReq() {
  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  if (CodecManager::GetInstance()->GetCodecLocation() == types::CodecLocation::HOST) {
    if (com::android::bluetooth::flags::run_ble_audio_ticks_in_worker_thread()) {
      worker_thread_->DoInThread(
              base::BindOnce(&SourceImpl::StopAudioTicks, weak_factory_.GetWeakPtr()));
    } else {
      StopAudioTicks();
    }
  }

  if (audioSourceCallbacks_ == nullptr) {
    log::error("audioSourceCallbacks_ not set");
    return false;
  }

  bt_status_t status =
          do_in_main_thread(base::BindOnce(&LeAudioSourceAudioHalClient::Callbacks::OnAudioSuspend,
                                           audioSourceCallbacks_->weak_factory_.GetWeakPtr()));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

bool SourceImpl::OnMetadataUpdateReq(const source_metadata_v7_t& source_metadata,
                                     DsaMode dsa_mode) {
  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  if (audioSourceCallbacks_ == nullptr) {
    log::error("audio receiver not started");
    return false;
  }

  std::vector<struct playback_track_metadata_v7> metadata;
  if (source_metadata.tracks != nullptr) {
    metadata = std::vector<struct playback_track_metadata_v7>(
            source_metadata.tracks, source_metadata.tracks + source_metadata.track_count);
  }

  bt_status_t status = do_in_main_thread(base::BindOnce(
          &LeAudioSourceAudioHalClient::Callbacks::OnAudioMetadataUpdate,
          audioSourceCallbacks_->weak_factory_.GetWeakPtr(), std::move(metadata), dsa_mode));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

bool SourceImpl::Start(const LeAudioCodecConfiguration& codec_configuration,
                       LeAudioSourceAudioHalClient::Callbacks* audioReceiver, DsaModes dsa_modes) {
  if (!halSinkInterface_) {
    log::error("Audio HAL Audio sink interface not acquired");
    return false;
  }

  if (le_audio_sink_hal_state_ == HAL_STARTED) {
    log::error("Audio HAL Audio sink is already in use");
    return false;
  }

  log::info("bit rate: {}, num channels: {}, sample rate: {}, data interval: {}",
            codec_configuration.bits_per_sample, codec_configuration.num_channels,
            codec_configuration.sample_rate, codec_configuration.data_interval_us);

  sStats.Reset();

  /* Global config for periodic audio data */
  source_codec_config_ = codec_configuration;
  audio::le_audio::LeAudioClientInterface::PcmParameters pcmParameters = {
          .data_interval_us = codec_configuration.data_interval_us,
          .sample_rate = codec_configuration.sample_rate,
          .bits_per_sample = codec_configuration.bits_per_sample,
          .channels_count = codec_configuration.num_channels};

  halSinkInterface_->SetPcmParameters(pcmParameters);
  audio::le_audio::LeAudioClientInterface::Get()->SetAllowedDsaModes(dsa_modes);
  halSinkInterface_->StartSession();

  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  audioSourceCallbacks_ = audioReceiver;
  le_audio_sink_hal_state_ = HAL_STARTED;
  return true;
}

void SourceImpl::Stop() {
  if (!halSinkInterface_) {
    log::error("Audio HAL Audio sink interface already stopped");
    return;
  }

  if (le_audio_sink_hal_state_ != HAL_STARTED) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");

  halSinkInterface_->StopSession();
  le_audio_sink_hal_state_ = HAL_STOPPED;

  if (CodecManager::GetInstance()->GetCodecLocation() == types::CodecLocation::HOST) {
    if (com::android::bluetooth::flags::run_ble_audio_ticks_in_worker_thread()) {
      worker_thread_->DoInThread(
              base::BindOnce(&SourceImpl::StopAudioTicks, weak_factory_.GetWeakPtr()));
    } else {
      StopAudioTicks();
    }
  }

  std::lock_guard<std::mutex> guard(audioSourceCallbacksMutex_);
  audioSourceCallbacks_ = nullptr;
}

void SourceImpl::ConfirmStreamingRequest() {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->ConfirmStreamingRequest();

  if (CodecManager::GetInstance()->GetCodecLocation() != types::CodecLocation::HOST) {
    return;
  }

  if (com::android::bluetooth::flags::run_ble_audio_ticks_in_worker_thread()) {
    worker_thread_->DoInThread(
            base::BindOnce(&SourceImpl::StartAudioTicks, weak_factory_.GetWeakPtr()));
  } else {
    StartAudioTicks();
  }
}

void SourceImpl::SuspendedForReconfiguration() {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->SuspendedForReconfiguration();
}

void SourceImpl::ReconfigurationComplete() {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->ReconfigurationComplete();
}

void SourceImpl::CancelStreamingRequest() {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->CancelStreamingRequest();
}

void SourceImpl::UpdateRemoteDelay(uint16_t remote_delay_ms) {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->SetRemoteDelay(remote_delay_ms);
}

void SourceImpl::UpdateAudioConfigToHal(const ::bluetooth::le_audio::stream_config& config) {
  if ((halSinkInterface_ == nullptr) || (le_audio_sink_hal_state_ != HAL_STARTED)) {
    log::error("Audio HAL Audio sink was not started!");
    return;
  }

  log::info("");
  halSinkInterface_->UpdateAudioConfigToHal(config);
}

std::optional<broadcaster::BroadcastConfiguration> SourceImpl::GetBroadcastConfig(
        const std::vector<std::pair<types::LeAudioContextType, uint8_t>>& subgroup_quality,
        const std::optional<std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs) const {
  if (halSinkInterface_ == nullptr) {
    log::error("Audio HAL Audio sink is null!");
    return std::nullopt;
  }

  log::info("");
  return halSinkInterface_->GetBroadcastConfig(subgroup_quality, pacs);
}

std::optional<::bluetooth::le_audio::types::AudioSetConfiguration> SourceImpl::GetUnicastConfig(
        const CodecManager::UnicastConfigurationRequirements& requirements) const {
  if (halSinkInterface_ == nullptr) {
    log::error("Audio HAL Audio sink is null!");
    return std::nullopt;
  }

  log::info("");
  return halSinkInterface_->GetUnicastConfig(requirements);
}

void SourceImpl::UpdateBroadcastAudioConfigToHal(
        const ::bluetooth::le_audio::broadcast_offload_config& config) {
  if (halSinkInterface_ == nullptr) {
    log::error("Audio HAL Audio sink interface not acquired");
    return;
  }

  log::info("");
  halSinkInterface_->UpdateBroadcastAudioConfigToHal(config);
}
}  // namespace

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireUnicast() {
  std::unique_ptr<SourceImpl> impl(new SourceImpl(false));
  if (!impl->Acquire()) {
    log::error("Could not acquire Unicast Source on LE Audio HAL enpoint");
    impl.reset();
    return nullptr;
  }

  log::info("");
  return std::move(impl);
}

std::unique_ptr<LeAudioSourceAudioHalClient> LeAudioSourceAudioHalClient::AcquireBroadcast() {
  std::unique_ptr<SourceImpl> impl(new SourceImpl(true));
  if (!impl->Acquire()) {
    log::error("Could not acquire Broadcast Source on LE Audio HAL endpoint");
    impl.reset();
    return nullptr;
  }

  log::info("");
  return std::move(impl);
}

void LeAudioSourceAudioHalClient::DebugDump(int fd) {
  uint64_t now_us = bluetooth::common::time_get_os_boottime_us();
  std::stringstream stream;
  stream << "  LE AudioHalClient:"
         << "\n    Counts (underflow)                                      : "
         << sStats.media_read_total_underflow_count
         << "\n    Bytes (underflow)                                       : "
         << sStats.media_read_total_underflow_bytes
         << "\n    Last update time ago in ms (underflow)                  : "
         << (sStats.media_read_last_underflow_us > 0
                     ? (now_us - sStats.media_read_last_underflow_us) / 1000
                     : 0)
         << std::endl;
  dprintf(fd, "%s", stream.str().c_str());
}
}  // namespace bluetooth::le_audio
