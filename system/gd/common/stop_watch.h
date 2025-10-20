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

#pragma once

#include <chrono>
#include <mutex>
#include <string>

namespace bluetooth {
namespace common {

static const int LOG_BUFFER_LENGTH = 10;

typedef struct {
  std::chrono::system_clock::time_point timestamp;
  std::chrono::high_resolution_clock::time_point start_timestamp;
  std::chrono::high_resolution_clock::time_point end_timestamp;
  std::string message;
} StopWatchLog;

class StopWatchBuffer {
public:
  StopWatchBuffer(std::string buffer_name)
      : buffer_name_(std::move(buffer_name)), current_buffer_index_(0) {}
  void RecordLog(StopWatchLog log);
  void Dump();

private:
  std::string buffer_name_;
  std::array<StopWatchLog, LOG_BUFFER_LENGTH> stopwatch_logs_;
  int current_buffer_index_;
  std::recursive_mutex stopwatch_log_mutex_;
};

class StopWatch {
public:
  static void DumpStopWatchLog(void);
  StopWatch(StopWatchBuffer& buffer, std::string text);
  ~StopWatch();

  // Buffer for HciHalTx and HciCallbackRx
  static StopWatchBuffer hciHalTxBuffer_;
  static StopWatchBuffer hciHalRxBuffer_;

private:
  StopWatchBuffer& current_buffer_;
  std::string text_;
  std::chrono::system_clock::time_point timestamp_;
  std::chrono::high_resolution_clock::time_point start_timestamp_;
};

}  // namespace common
}  // namespace bluetooth
