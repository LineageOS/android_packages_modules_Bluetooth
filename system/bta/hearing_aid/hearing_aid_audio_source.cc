/******************************************************************************
 *
 *  Copyright 2018 The Android Open Source Project
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

#define LOG_TAG "bluetooth-asha"

#include <base/files/file_util.h>
#include <bluetooth/log.h>
#include <stdio.h>

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <ostream>
#include <sstream>
#include <vector>

#include "audio_hal_interface/hearing_aid_software_encoding.h"
#include "bta/include/bta_hearing_aid_api.h"
#include "common/message_loop_thread.h"
#include "common/repeating_timer.h"
#include "common/time_util.h"
#include "hardware/bluetooth.h"
#include "hardware/bt_av.h"
#include "osi/include/wakelock.h"
#include "stack/include/main_thread.h"

using namespace bluetooth;

namespace {

int bit_rate = -1;
int sample_rate = -1;
int data_interval_ms = -1;
int num_channels = 2;
bluetooth::common::RepeatingTimer audio_timer;
HearingAidAudioReceiver* localAudioReceiver = nullptr;

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
};

AudioHalStats stats;

bool hearing_aid_on_resume_req(bool start_media_task);
bool hearing_aid_on_suspend_req();

void send_audio_data() {
  uint32_t bytes_per_tick = (num_channels * sample_rate * data_interval_ms * (bit_rate / 8)) / 1000;

  uint8_t p_buf[bytes_per_tick];

  uint32_t bytes_read = 0;
  if (bluetooth::audio::hearing_aid::is_hal_enabled()) {
    bytes_read = bluetooth::audio::hearing_aid::read(p_buf, bytes_per_tick);
  }

  log::debug("bytes_read: {}", bytes_read);
  if (bytes_read < bytes_per_tick) {
    stats.media_read_total_underflow_bytes += bytes_per_tick - bytes_read;
    stats.media_read_total_underflow_count++;
    stats.media_read_last_underflow_us = bluetooth::common::time_get_os_boottime_us();
  }

  std::vector<uint8_t> data(p_buf, p_buf + bytes_read);

  if (localAudioReceiver != nullptr) {
    localAudioReceiver->OnAudioDataReady(data);
  }
}

void start_audio_ticks() {
  if (data_interval_ms != HA_INTERVAL_10_MS && data_interval_ms != HA_INTERVAL_20_MS) {
    log::fatal("Unsupported data interval: {}", data_interval_ms);
  }

  wakelock_acquire();
  audio_timer.SchedulePeriodic(get_main_thread()->GetWeakPtr(),
                               base::BindRepeating(&send_audio_data),
                               std::chrono::milliseconds(data_interval_ms));
  log::info("running with data interval: {}", data_interval_ms);
}

void stop_audio_ticks() {
  log::info("stopped");
  audio_timer.CancelAndWait();
  wakelock_release();
}

bool hearing_aid_on_resume_req(bool start_media_task) {
  if (localAudioReceiver == nullptr) {
    log::error("HEARING_AID_CTRL_CMD_START: audio receiver not started");
    return false;
  }
  bt_status_t status;
  if (start_media_task) {
    status = do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioResume,
                                              base::Unretained(localAudioReceiver),
                                              start_audio_ticks));
  } else {
    auto start_dummy_ticks = []() { log::info("start_audio_ticks: waiting for data path opened"); };
    status = do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioResume,
                                              base::Unretained(localAudioReceiver),
                                              start_dummy_ticks));
  }
  if (status != BT_STATUS_SUCCESS) {
    log::error("HEARING_AID_CTRL_CMD_START: do_in_main_thread err={}", status);
    return false;
  }
  return true;
}

bool hearing_aid_on_suspend_req() {
  if (localAudioReceiver == nullptr) {
    log::error("HEARING_AID_CTRL_CMD_SUSPEND: audio receiver not started");
    return false;
  }
  bt_status_t status =
          do_in_main_thread(base::BindOnce(&HearingAidAudioReceiver::OnAudioSuspend,
                                           base::Unretained(localAudioReceiver), stop_audio_ticks));
  if (status != BT_STATUS_SUCCESS) {
    log::error("HEARING_AID_CTRL_CMD_SUSPEND: do_in_main_thread err={}", status);
    return false;
  }
  return true;
}
}  // namespace

void HearingAidAudioSource::Start(const CodecConfiguration& codecConfiguration,
                                  HearingAidAudioReceiver* audioReceiver,
                                  uint16_t remote_delay_ms) {
  log::info("Hearing Aid Source Open");

  bit_rate = codecConfiguration.bit_rate;
  sample_rate = codecConfiguration.sample_rate;
  data_interval_ms = codecConfiguration.data_interval_ms;

  stats.Reset();

  if (bluetooth::audio::hearing_aid::is_hal_enabled()) {
    bluetooth::audio::hearing_aid::start_session();
    bluetooth::audio::hearing_aid::set_remote_delay(remote_delay_ms);
  }
  localAudioReceiver = audioReceiver;
}

void HearingAidAudioSource::Stop() {
  log::info("Hearing Aid Source Close");

  localAudioReceiver = nullptr;
  if (bluetooth::audio::hearing_aid::is_hal_enabled()) {
    bluetooth::audio::hearing_aid::end_session();
  }

  stop_audio_ticks();
}

void HearingAidAudioSource::Initialize() {
  auto stream_cb = bluetooth::audio::hearing_aid::StreamCallbacks{
          .on_resume_ = hearing_aid_on_resume_req,
          .on_suspend_ = hearing_aid_on_suspend_req,
  };
  if (!bluetooth::audio::hearing_aid::init(stream_cb, get_main_thread())) {
    log::error("Hearing AID HAL failed to initialize");
  }
}

void HearingAidAudioSource::CleanUp() {
  if (bluetooth::audio::hearing_aid::is_hal_enabled()) {
    bluetooth::audio::hearing_aid::cleanup();
  }
}

void HearingAidAudioSource::DebugDump(int fd) {
  uint64_t now_us = bluetooth::common::time_get_os_boottime_us();
  std::stringstream stream;
  stream << "  Hearing Aid Audio HAL:"
         << "\n    Counts (underflow)                                      : "
         << stats.media_read_total_underflow_count
         << "\n    Bytes (underflow)                                       : "
         << stats.media_read_total_underflow_bytes
         << "\n    Last update time ago in ms (underflow)                  : "
         << (stats.media_read_last_underflow_us > 0
                     ? (now_us - stats.media_read_last_underflow_us) / 1000
                     : 0)
         << std::endl;
  dprintf(fd, "%s", stream.str().c_str());
}
