/*
 * Copyright 2020 The Android Open Source Project
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

#include "os/files.h"

#include <bluetooth/log.h>
#include <fcntl.h>
#include <libgen.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <fstream>
#include <streambuf>
#include <string>

#include "os/log.h"

namespace {

void HandleError(const std::string& temp_path, int* dir_fd, FILE** fp) {
  // This indicates there is a write issue.  Unlink as partial data is not
  // acceptable.
  unlink(temp_path.c_str());
  if (*fp) {
    fclose(*fp);
    *fp = nullptr;
  }
  if (*dir_fd != -1) {
    close(*dir_fd);
    *dir_fd = -1;
  }
}

}  // namespace

namespace bluetooth {
namespace os {

bool FileExists(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  return input.good();
}

bool RenameFile(const std::string& from, const std::string& to) {
  if (std::rename(from.c_str(), to.c_str()) != 0) {
    log::error("unable to rename file from '{}' to '{}', error: {}", from, to, strerror(errno));
    return false;
  }
  return true;
}

std::optional<std::string> ReadSmallFile(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) {
    log::warn("Failed to open file '{}', error: {}", path, strerror(errno));
    return std::nullopt;
  }
  auto file_size = input.tellg();
  if (file_size < 0) {
    log::warn("Failed to get file size for '{}', error: {}", path, strerror(errno));
    return std::nullopt;
  }
  std::string result(file_size, '\0');
  if (!input.seekg(0)) {
    log::warn("Failed to go back to the beginning of file '{}', error: {}", path, strerror(errno));
    return std::nullopt;
  }
  if (!input.read(result.data(), result.size())) {
    log::warn("Failed to read file '{}', error: {}", path, strerror(errno));
    return std::nullopt;
  }
  input.close();
  return result;
}

bool WriteToFile(const std::string& path, const std::string& data) {
  log::assert_that(!path.empty(), "assert failed: !path.empty()");
  // Steps to ensure content of data gets to disk:
  //
  // 1) Open and write to temp file (e.g. bt_config.conf.new).
  // 2) Flush the stream buffer to the temp file.
  // 3) Sync the temp file to disk with fsync().
  // 4) Rename temp file to actual config file (e.g. bt_config.conf).
  //    This ensures atomic update.
  // 5) Sync directory that has the conf file with fsync().
  //    This ensures directory entries are up-to-date.
  //
  // We are using traditional C type file methods because C++ std::filesystem and std::ofstream do not support:
  // - Operation on directories
  // - fsync() to ensure content is written to disk

  // Build temp config file based on config file (e.g. bt_config.conf.new).
  const std::string temp_path = path + ".new";

  // Extract directory from file path (e.g. /data/misc/bluedroid).
  // libc++fs is not supported in APEX yet and hence cannot use std::filesystem::path::parent_path
  std::string directory_path;
  {
    // Make a temporary variable as inputs to dirname() will be modified and return value points to input char array
    // temp_path_for_dir must not be destroyed until results from dirname is appended to directory_path
    std::string temp_path_for_dir(path);
    directory_path.append(dirname(temp_path_for_dir.data()));
  }
  if (directory_path.empty()) {
    log::error("error extracting directory from '{}', error: {}", path, strerror(errno));
    return false;
  }

  int dir_fd = open(directory_path.c_str(), O_RDONLY | O_DIRECTORY);
  if (dir_fd < 0) {
    log::error("unable to open dir '{}', error: {}", directory_path, strerror(errno));
    return false;
  }

  FILE* fp = std::fopen(temp_path.c_str(), "wt");
  if (!fp) {
    log::error("unable to write to file '{}', error: {}", temp_path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }

  if (std::fprintf(fp, "%s", data.c_str()) < 0) {
    log::error("unable to write to file '{}', error: {}", temp_path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }

  // Flush the stream buffer to the temp file.
  if (std::fflush(fp) != 0) {
    log::error("unable to write flush buffer to file '{}', error: {}", temp_path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }

  // Sync written temp file out to disk. fsync() is blocking until data makes it
  // to disk.
  if (fsync(fileno(fp)) != 0) {
    log::warn("unable to fsync file '{}', error: {}", temp_path, strerror(errno));
    // Allow fsync to fail and continue
  }

  if (std::fclose(fp) != 0) {
    log::error("unable to close file '{}', error: {}", temp_path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }
  fp = nullptr;

  // Change the file's permissions to Read/Write by User and Group
  if (chmod(temp_path.c_str(), S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP) != 0) {
    log::error("unable to change file permissions '{}', error: {}", temp_path, strerror(errno));

    struct stat dirstat {};
    if (fstat(dir_fd, &dirstat) == 0) {
      log::error("dir st_mode = 0x{:02x}", dirstat.st_mode);
      log::error("dir uid = {}", dirstat.st_uid);
      log::error("dir gid = {}", dirstat.st_gid);
    } else {
      log::error("unable to call fstat on the directory, error: {}", strerror(errno));
    }

    struct stat filestat {};
    if (stat(temp_path.c_str(), &filestat) == 0) {
      log::error("file st_mode = 0x{:02x}", filestat.st_mode);
      log::error("file uid = {}", filestat.st_uid);
      log::error("file gid = {}", filestat.st_gid);
    } else {
      log::error("unable to call stat, error: {}", strerror(errno));
    }

    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }

  // Rename written temp file to the actual config file.
  if (std::rename(temp_path.c_str(), path.c_str()) != 0) {
    log::error(
        "unable to commit file from '{}' to '{}', error: {}", temp_path, path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }

  // This should ensure the directory is updated as well.
  if (fsync(dir_fd) != 0) {
    log::warn("unable to fsync dir '{}', error: {}", directory_path, strerror(errno));
  }

  if (close(dir_fd) != 0) {
    log::error("unable to close dir '{}', error: {}", directory_path, strerror(errno));
    HandleError(temp_path, &dir_fd, &fp);
    return false;
  }
  return true;
}

bool RemoveFile(const std::string& path) {
  if (remove(path.c_str()) != 0) {
    log::error("unable to remove file '{}', error: {}", path, strerror(errno));
    return false;
  }
  return true;
}

std::optional<std::chrono::time_point<std::chrono::system_clock, std::chrono::nanoseconds>> FileCreatedTime(
    const std::string& path) {
  struct stat file_info;
  if (stat(path.c_str(), &file_info) != 0) {
    log::error("unable to read '{}' file metadata, error: {}", path, strerror(errno));
    return std::nullopt;
  }
  using namespace std::chrono;
  using namespace std::chrono_literals;
  auto created_ts = file_info.st_ctim;
  auto d = seconds{created_ts.tv_sec} + nanoseconds{created_ts.tv_nsec};
  return time_point<system_clock>(duration_cast<system_clock::duration>(d));
}

}  // namespace os
}  // namespace bluetooth
