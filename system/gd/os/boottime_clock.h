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

#include <chrono>

// OS Agnostic Boottime Clock
namespace bluetooth {
namespace os {

/**
 * Boottime clock implementation.
 * This clock is monotonic and includes time spent during system suspend.
 *
 * Precision is kept to nanoseconds, which is the same as CLOCK_BOOTTIME.
 * For other precision requirements, use duration_cast to convert to other units.
 */
class boottime_clock {
public:
  using duration = std::chrono::nanoseconds;
  using time_point = std::chrono::time_point<std::chrono::nanoseconds>;
  static constexpr bool is_steady = true;
  static time_point now() noexcept;

private:
  static duration now_duration() noexcept;
};
}  // namespace os
}  // namespace bluetooth