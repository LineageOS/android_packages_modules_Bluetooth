/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>

#include <ctime>
#include <mutex>
#include <source_location>

namespace bluetooth {
namespace os {

/**
 * A `BasicLockable` non recursive mutex.
 * This is a wrapper around the standard library `std::timed_mutex`, which holds the linux thread id
 * of the owner of the mutex.
 */
class TimedMutex {
public:
  TimedMutex() = default;
  ~TimedMutex() { unlock(); }

  // `BasicLockable` implementation
  void lock(const std::source_location location = std::source_location::current());
  void unlock();

  // Wrappers around `std::timed_mutex::try_lock_for`
  template <typename Rep, typename Period>
  bool try_lock_for(const std::chrono::duration<Rep, Period>& timeout_duration,
                    const std::source_location location = std::source_location::current()) {
    if (mutex_.try_lock_for(timeout_duration)) {
      owner_thread_linux_tid_ = static_cast<int>(syscall(SYS_gettid));
      lock_location_ = location;
      return true;
    }

    return false;
  }

  // Generic functions
  std::optional<int> get_owner_thread_tid() const { return owner_thread_linux_tid_; }
  std::optional<std::source_location> get_lock_location() const { return lock_location_; }

private:
  std::timed_mutex mutex_;
  std::optional<int> owner_thread_linux_tid_;
  std::optional<std::source_location> lock_location_;
};
}  // namespace os
}  // namespace bluetooth
