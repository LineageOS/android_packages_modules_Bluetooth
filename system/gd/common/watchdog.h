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

#ifndef ANDROID_WATCHDOG_H
#define ANDROID_WATCHDOG_H

#include <time.h>

#include <chrono>
#include <unordered_map>
#include <utility>

namespace bluetooth {
namespace common {

using namespace std::chrono_literals;

//
// A scoped Resource Acquisition Is Initialization (RAII) watchdog timer.
//
// This class is designed to prevent process hangs or deadlocks by enforcing
// a maximum execution time limit for a critical block of code.
// Details:
// 1. Resource: The Watchdog uses the POSIX `timer_create` mechanism with
// `CLOCK_MONOTONIC` to establish a system timer bound to the calling process.
// 2. Lifecycle: Upon construction with a valid timeout (`> 200ms`), the timer
// is immediately started when calling constructor. The timer is automatically
// and safely deleted in the destructor when the object goes out of scope.
// 3. Timeout Action: If the timer expires before the object is destroyed,
// a **SIGABRT (Abort Signal)** is delivered to the process. This causes the
// entire process to **immediately abort**, preventing a permanent hang and
// trigger a tombstone record for the process.
// 4. Invalid Input: If input parameter `timeout_ms` is less than or equal to the minimum
// threshold (currently hardcoded as 200ms), the Watchdog will **not** start
// the timer, will log a verbose message, and will render itself inactive
// for its lifetime.
//
// Example: Using the Watchdog in a time-critical function
// void time_critical_job()
// {
//    // This code block MUST complete within 500ms, or the process will abort.
//    bluetooth::common::Watchdog wd(500ms);
//
//    LOG_INFO("Starting operation...");
//
//    // Simulate a complex operation that takes 50ms
//    std::this_thread::sleep_for(50ms);  //stuff to do here
//
//    // The code finished successfully well within the limit.
//
// }
// 'wd' goes out of scope here; the timer is safely stopped and deleted.
class Watchdog final {
public:
  static constexpr std::chrono::milliseconds kMinimumTimeoutMs = 200ms;
  Watchdog(std::chrono::milliseconds timeout_ms);
  ~Watchdog();

private:
  timer_t timer_id_;
};
}  // namespace common
}  // namespace bluetooth

#endif  // ANDROID_WATCHDOG_H
