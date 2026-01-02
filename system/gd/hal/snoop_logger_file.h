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

#pragma once

#include <bluetooth/log.h>

#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace bluetooth::hal {

// SnoopLoggerFile class handles write Bluetooth packets to a file and check the
// number of packets. If the number of packets exceeds a limit, open a new file.
class SnoopLoggerFile {
public:
  // Create a new file and update symlinks.
  SnoopLoggerFile(std::filesystem::path snoop_dir_path, bool is_filtered, int max_packet_count);
  ~SnoopLoggerFile();

  // Put in header for test
  struct PacketHeaderType {
    uint32_t length_original;
    uint32_t length_captured;
    uint32_t flags;
    uint32_t dropped_packets;
    uint64_t timestamp;
    uint8_t type;
  } __attribute__((__packed__));

  // Write packet to current file, automatically create new file
  // and update symlinks if limit is reached.
  void Write(const PacketHeaderType& header, const std::vector<uint8_t>& packet, size_t packet_len);

  // Delete log file and last log file based on log path.
  static void DeleteBtsnoopFiles(const std::filesystem::path dir_path, bool is_filtered);
  static void DeleteBtsnoozFiles(const std::filesystem::path dir_path);
  static std::filesystem::path AssembleFileName(const std::filesystem::path& dir_path,
                                                bool is_snooz, bool is_filtered, bool is_last);

private:
  std::ofstream btsnoop_ostream_;
  std::filesystem::path snoop_dir_path_;
  std::filesystem::path snoop_log_path_;
  bool is_filtered_;
  size_t max_packets_per_file_;
  size_t packet_counter_ = 0;
  mutable std::mutex file_mutex_;

  void OpenNextSnoopLogFile();
  void CloseCurrentSnoopLogFile();
};

#ifdef __ANDROID__
bool create_log_directories(std::filesystem::path dir);
#endif  // __ANDROID__

}  // namespace bluetooth::hal
