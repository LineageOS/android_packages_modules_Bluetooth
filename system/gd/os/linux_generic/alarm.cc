/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "os/alarm.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <sys/timerfd.h>
#include <unistd.h>

#include <cstring>

#include "os/linux_generic/linux.h"
#include "os/utils.h"

#ifdef __ANDROID__
#define ALARM_CLOCK CLOCK_BOOTTIME_ALARM
#else
#define ALARM_CLOCK CLOCK_BOOTTIME
#endif

namespace bluetooth {
namespace os {

Alarm::Alarm(Thread* thread, std::chrono::milliseconds reactable_timeout)
    : Alarm(thread, true, reactable_timeout) {}

Alarm::Alarm(Thread* thread, bool isWakeAlarm, std::chrono::milliseconds reactable_timeout)
    : armed_time_(std::chrono::time_point<std::chrono::system_clock>::min()),
      thread_(thread),
      reactable_timeout_(reactable_timeout) {
  int timerfd_flag = TFD_NONBLOCK;

  fd_ = TIMERFD_CREATE(isWakeAlarm ? ALARM_CLOCK : CLOCK_BOOTTIME, timerfd_flag);

  log::assert_that(fd_ != -1, "cannot create timerfd: {}", strerror(errno));

  token_ = thread_->GetReactor()->Register(
          fd_, base::BindRepeating(&Alarm::on_fire, base::Unretained(this)),
          base::RepeatingClosure());
}

Alarm::~Alarm() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto reactor = thread_->GetReactor();
    reactor->Unregister(token_);
    token_ = nullptr;

    int close_status;
    RUN_NO_INTR(close_status = TIMERFD_CLOSE(fd_));
    log::assert_that(close_status != -1, "assert failed: close_status != -1");
  }

  // If the timeout is set, wait for the reactable to be unregistered.
  if (reactable_timeout_ != kDefaultReactableTimeout) {
    log::assert_that(thread_->GetReactor()->WaitForUnregisteredReactable(reactable_timeout_),
                     "assert failed: thread_->GetReactor()->WaitForUnregisteredReactable(timeout)");
  }
}

void Alarm::Schedule(base::OnceClosure task, std::chrono::milliseconds delay) {
  std::lock_guard<std::mutex> lock(mutex_);
  long delay_ms = delay.count();
  armed_time_ = std::chrono::system_clock::now();  // reset the armed time on every schedule
  itimerspec timer_itimerspec{{/* interval for periodic timer */},
                              {delay_ms / 1000, delay_ms % 1000 * 1000000}};
  int result = TIMERFD_SETTIME(fd_, 0, &timer_itimerspec, nullptr);
  log::assert_that(result == 0, "assert failed: result == 0");

  task_ = std::move(task);
}

void Alarm::Cancel() {
  std::lock_guard<std::mutex> lock(mutex_);
  itimerspec disarm_itimerspec{/* disarm timer */};
  int result = TIMERFD_SETTIME(fd_, 0, &disarm_itimerspec, nullptr);
  log::assert_that(result == 0 || errno == EAGAIN,
                   "Failed to disarm the timer: result: {}, errno: {}", result, strerror(errno));
}

void Alarm::on_fire() {
  std::unique_lock<std::mutex> lock(mutex_);
  auto task = std::move(task_);
  uint64_t times_invoked;
  auto bytes_read = read(fd_, &times_invoked, sizeof(uint64_t));
  lock.unlock();

  if (bytes_read == -1) {
    log::debug("No data to read.");
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
      log::debug("Alarm is already canceled or rescheduled.");
      return;
    }
  }

  log::assert_that(bytes_read == static_cast<ssize_t>(sizeof(uint64_t)),
                   "assert failed: bytes_read == static_cast<ssize_t>(sizeof(uint64_t))");
  log::assert_that(times_invoked == 1u, "Invoked number of times:{} fd:{}", times_invoked, fd_);

  if (task.is_null()) {
    log::warn("task is null.");
    return;
  }
  std::move(task).Run();
}

}  // namespace os
}  // namespace bluetooth
