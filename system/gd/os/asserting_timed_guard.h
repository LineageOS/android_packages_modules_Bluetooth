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
#include <source_location>

#include "os/timed_mutex.h"

namespace bluetooth {
namespace os {

// A RAII wrapper, which wraps `TimedMutex`.
// This will `assert` if the mutex is not locked within the given timeout.
class AssertingTimedGuard {
public:
  template <typename Rep, typename Period>
  AssertingTimedGuard(
          TimedMutex& mutex, const std::chrono::duration<Rep, Period>& timeout_duration,
          const std::source_location requesting_location = std::source_location::current())
      : mutex_(mutex) {
    // Assert if the mutex is not locked within the given timeout.
    if (!mutex.try_lock_for(timeout_duration, requesting_location)) {
      int requesting_thread_linux_tid = static_cast<int>(syscall(SYS_gettid));
      std::optional<int> owner_thread_linux_tid = mutex.get_owner_thread_tid();
      std::optional<std::source_location> current_lock_location = mutex.get_lock_location();
      log::assert_that(
              false,
              "assert failed: mutex_.try_lock_for({}), current_owner thread-id: {}, locked "
              "at {}:{} in {}, requesting thread-id: {} from {}:{} in {}",
              timeout_duration, owner_thread_linux_tid.value(),
              current_lock_location.value().file_name(), current_lock_location.value().line(),
              current_lock_location.value().function_name(), requesting_thread_linux_tid,
              requesting_location.file_name(), requesting_location.line(),
              requesting_location.function_name());
    }
  }

  ~AssertingTimedGuard() { mutex_.unlock(); }

  AssertingTimedGuard(const AssertingTimedGuard&) = delete;
  AssertingTimedGuard& operator=(const AssertingTimedGuard&) = delete;

private:
  TimedMutex& mutex_;
};

}  // namespace os
}  // namespace bluetooth