/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "hal/snoop_logger_file.h"

#include <sys/stat.h>

#include "hal/snoop_logger.h"
#include "hal/snoop_logger_common.h"

#ifdef __ANDROID__
#include "hal/snoop_logger_tracing.h"
#endif  // __ANDROID__
#include "os/parameter_provider.h"

#ifdef USE_FAKE_TIMERS
#include "os/fake_timer/fake_timerfd.h"
using bluetooth::os::fake_timer::fake_timerfd_get_clock;
#endif

namespace bluetooth::hal {

#ifdef __ANDROID__
static bool create_log_directories() {
  std::filesystem::path default_path = os::ParameterProvider::SnoopLogFilePath();
  std::filesystem::path default_dir_path = default_path.parent_path();

  if (std::filesystem::exists(default_dir_path)) {
    log::info("Directory {} already exists", default_dir_path.string());
    return true;
  }

  log::info("Creating directory: {}", default_dir_path.string());
  return std::filesystem::create_directories(default_dir_path);
}
#endif  // __ANDROID__

static std::filesystem::path get_last_log_path(const std::filesystem::path& log_file_path) {
  std::filesystem::path last_log_path = log_file_path;
  last_log_path += ".last";
  return last_log_path;
}

SnoopLoggerFile::SnoopLoggerFile(std::filesystem::path snoop_log_path, int max_packet_count)
    : snoop_log_path_(snoop_log_path), max_packets_per_file_(max_packet_count) {
  std::lock_guard<std::mutex> lock(file_mutex_);
  OpenNextSnoopLogFile();
}

void SnoopLoggerFile::OpenNextSnoopLogFile() {
  CloseCurrentSnoopLogFile();

  auto last_file_path = get_last_log_path(snoop_log_path_);

#ifdef __ANDROID__
  if (!create_log_directories()) {
    log::error("Could not recreate log directory");
  }
#endif  // __ANDROID__

  mode_t prevmask = umask(0);
  std::error_code ec;
  if (std::filesystem::exists(snoop_log_path_, ec)) {
    std::filesystem::rename(snoop_log_path_, last_file_path, ec);
    if (ec) {
      log::error("Unabled to rename existing snoop log from \"{}\" to \"{}\": {}",
                 snoop_log_path_.string(), last_file_path.string(), ec.message());
    }
  } else {
    log::info("Previous log file \"{}\" does not exist, skip renaming", snoop_log_path_.string());
    if (ec) {
      log::error("Failed to check file exists: {}", ec.message());
    }
  }
  // do not use std::ios::app as we want override the existing file
  btsnoop_ostream_.open(snoop_log_path_.string(), std::ios::binary | std::ios::out);

#ifdef USE_FAKE_TIMERS
  file_creation_time = fake_timerfd_get_clock();
#endif
  if (!btsnoop_ostream_.good()) {
    log::fatal("Unable to open snoop log at \"{}\", error: \"{}\"", snoop_log_path_.string(),
               strerror(errno));
  }
  umask(prevmask);
  if (!btsnoop_ostream_.write(reinterpret_cast<const char*>(&SnoopLoggerCommon::kBtSnoopFileHeader),
                              sizeof(SnoopLoggerCommon::FileHeaderType))) {
    log::fatal("Unable to write file header to \"{}\", error: \"{}\"", snoop_log_path_.string(),
               strerror(errno));
  }
  if (!btsnoop_ostream_.flush()) {
    log::error("Failed to flush, error: \"{}\"", strerror(errno));
  }
}

SnoopLoggerFile::~SnoopLoggerFile() {
  std::lock_guard<std::mutex> lock(file_mutex_);
  log::debug("Closing btsnoop log data at {}", snoop_log_path_.string());
  CloseCurrentSnoopLogFile();
}
void SnoopLoggerFile::Write(const PacketHeaderType& header, const std::vector<uint8_t>& packet,
                            size_t packet_len) {
  std::lock_guard<std::mutex> lock(file_mutex_);
  packet_counter_++;
  if (packet_counter_ > max_packets_per_file_) {
    OpenNextSnoopLogFile();
  }
  if (!btsnoop_ostream_.write(reinterpret_cast<const char*>(&header), sizeof(PacketHeaderType))) {
    log::error("Failed to write packet header for btsnoop, error: \"{}\"", strerror(errno));
  }
  if (!btsnoop_ostream_.write(reinterpret_cast<const char*>(packet.data()), packet_len - 1)) {
    log::error("Failed to write packet payload for btsnoop, error: \"{}\"", strerror(errno));
  }

  // std::ofstream::flush() pushes user data into kernel memory. The data will be written even if
  // this process crashes. However, data will be lost if there is a kernel panic, which is out of
  // scope of BT snoop log. NOTE: std::ofstream::write() followed by std::ofstream::flush() has
  // similar effect as UNIX write(fd, data, len)
  //       as write() syscall dumps data into kernel memory directly
  if (!btsnoop_ostream_.flush()) {
    log::error("Failed to flush, error: \"{}\"", strerror(errno));
  }
}

void SnoopLoggerFile::CloseCurrentSnoopLogFile() {
  if (btsnoop_ostream_.is_open()) {
    btsnoop_ostream_.flush();
    btsnoop_ostream_.close();
  }
  packet_counter_ = 0;
}

static void delete_log_file(const std::filesystem::path& log_path) {
  std::error_code ec;
  if (std::filesystem::exists(log_path, ec)) {
    if (!std::filesystem::remove(log_path, ec)) {
      log::error("Failed to remove main log file at \"{}\"", log_path.string());
    }
  } else {
    log::info("Log file does not exist at \"{}\"", log_path.string());
  }
  if (ec) {
    log::error("Error removing log file: ", ec.message());
  }
}

void SnoopLoggerFile::DeleteBtsnoopFiles(const std::filesystem::path log_path) {
  log::info("Deleting logs based on {} if they exist", log_path.string());
  delete_log_file(log_path);
  auto last_log_path = get_last_log_path(log_path);
  delete_log_file(last_log_path);
}
}  // namespace bluetooth::hal
