/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "BtStopWatch"

#include "common/stop_watch.h"

#include <bluetooth/log.h>

#include <array>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <utility>

namespace bluetooth {
namespace common {

StopWatchBuffer StopWatch::hciHalTxBuffer_("HciHalTx");
StopWatchBuffer StopWatch::hciHalRxBuffer_("HciHalRx");

void StopWatchBuffer::RecordLog(StopWatchLog log) {
  std::unique_lock<std::recursive_mutex> lock(stopwatch_log_mutex_, std::defer_lock);

  if (!lock.try_lock()) {
    log::info("try_lock fail. log content: {}, took {} us", log.message,
              static_cast<size_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                          stopwatch_logs_[current_buffer_index_].end_timestamp -
                                          stopwatch_logs_[current_buffer_index_].start_timestamp)
                                          .count()));
    return;
  }

  // Update the input `log` to the respective stopwatch_logs buffer
  stopwatch_logs_[current_buffer_index_] = std::move(log);
  current_buffer_index_ = (current_buffer_index_ + 1) % LOG_BUFFER_LENGTH;
  lock.unlock();
}

void StopWatchBuffer::Dump() {
  std::lock_guard<std::recursive_mutex> lock(stopwatch_log_mutex_);
  log::info("=-----------------------------------=");
  log::info("Bluetooth stopwatch log history for {}:", buffer_name_);
  int current_buffer_index = current_buffer_index_;
  for (int i = 0; i < LOG_BUFFER_LENGTH; i++) {
    // Start dumping the logs
    if (stopwatch_logs_[current_buffer_index].message.empty()) {
      current_buffer_index = (current_buffer_index + 1) % LOG_BUFFER_LENGTH;
      continue;
    }
    std::stringstream ss;
    auto now = stopwatch_logs_[current_buffer_index].timestamp;
    auto millis =
            std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000;
    auto now_time_t = std::chrono::system_clock::to_time_t(now);
    ss << std::put_time(std::localtime(&now_time_t), "%Y-%m-%d %H:%M:%S");
    ss << '.' << std::setfill('0') << std::setw(3) << millis.count();
    std::string start_timestamp = ss.str();
    log::info("{}: {}: took {} us", start_timestamp, stopwatch_logs_[current_buffer_index].message,
              static_cast<size_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                          stopwatch_logs_[current_buffer_index].end_timestamp -
                                          stopwatch_logs_[current_buffer_index].start_timestamp)
                                          .count()));
    current_buffer_index = (current_buffer_index + 1) % LOG_BUFFER_LENGTH;
  }
  log::info("=-----------------------------------=");
}

void StopWatch::DumpStopWatchLog() {
  StopWatch::hciHalTxBuffer_.Dump();
  StopWatch::hciHalRxBuffer_.Dump();
}

StopWatch::StopWatch(StopWatchBuffer& buffer, std::string text)
    : current_buffer_(buffer),
      text_(std::move(text)),
      timestamp_(std::chrono::system_clock::now()),
      start_timestamp_(std::chrono::high_resolution_clock::now()) {
  // check that current_buffer_ is hciHalTxBuffer or hciCallbackRxBuffer
  log::assert_that(&current_buffer_ == &StopWatch::hciHalTxBuffer_ ||
                           &current_buffer_ == &StopWatch::hciHalRxBuffer_,
                   "current_buffer_ is not hciHalTxBuffer or hciHalRxBuffer");
}

StopWatch::~StopWatch() {
  StopWatchLog sw_log;
  sw_log.timestamp = timestamp_;
  sw_log.start_timestamp = start_timestamp_;
  sw_log.end_timestamp = std::chrono::high_resolution_clock::now();
  sw_log.message = std::move(text_);

  current_buffer_.RecordLog(std::move(sw_log));
}

}  // namespace common
}  // namespace bluetooth
