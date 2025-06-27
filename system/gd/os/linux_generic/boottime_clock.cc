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

#include <bluetooth/log.h>
#include <os/boottime_clock.h>
#include <time.h>

#include <chrono>
#include <cstring>
#include <ctime>

namespace bluetooth {
namespace os {

boottime_clock::time_point boottime_clock::now() noexcept {
  auto total_duration = now_duration();
  return time_point(total_duration);
}

boottime_clock::duration boottime_clock::now_duration() noexcept {
  // Directly use CLOCK_BOOTTIME for Android.
  struct timespec ts;
  int result = clock_gettime(CLOCK_BOOTTIME, &ts);
  log::assert_that(result == 0, "assert failed: result == 0, result: {}, errno: {}", result,
                   std::strerror(errno));
  return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::seconds(ts.tv_sec) +
                                                              std::chrono::nanoseconds(ts.tv_nsec));
}

}  // namespace os
}  // namespace bluetooth
