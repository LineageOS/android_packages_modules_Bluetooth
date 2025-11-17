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

#include "watchdog.h"

#include <bluetooth/log.h>
#include <signal.h>
#include <time.h>

#include <chrono>
#include <cstring>

namespace bluetooth {
namespace common {
using namespace std::chrono_literals;

Watchdog::Watchdog(std::chrono::milliseconds timeout_ms) {
  struct sigevent sev;

  log::assert_that(timeout_ms >= kMinimumTimeoutMs,
                   "Failed to create timer(), timeout can not less than kMinimumTimeoutMs");

  sev.sigev_notify = SIGEV_SIGNAL;
  sev.sigev_signo = SIGABRT;
  sev.sigev_value.sival_ptr = &timer_id_;

  int err = timer_create(CLOCK_MONOTONIC, &sev, &timer_id_);
  log::assert_that(err == 0, "Failed to create timer: {}", strerror(errno));

  struct itimerspec spec{};
  spec.it_value.tv_sec = timeout_ms.count() / 1000;
  spec.it_value.tv_nsec = (timeout_ms.count() % 1000) * 1000000;
  err = timer_settime(timer_id_, 0, &spec, nullptr);

  log::assert_that(err == 0, "Failed to set timer: {}", strerror(errno));
  log::verbose("Watchdog created with timeout: {} ms", timeout_ms.count());
}

Watchdog::~Watchdog() {
  int err = timer_delete(timer_id_);
  log::assert_that(err == 0, "Failed to delete timer: {}", strerror(errno));
}

}  // namespace common
}  // namespace bluetooth
