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

#pragma once

#include <base/functional/callback.h>

#include <functional>
#include <memory>
#include <mutex>

#include "os/thread.h"
#include "os/utils.h"

namespace bluetooth {
namespace os {

#define kDefaultReactableTimeout std::chrono::milliseconds(0)

// A single-shot alarm for reactor-based thread, implemented by Linux timerfd.
// When it's constructed, it will register a reactable on the specified thread; when it's destroyed,
// it will unregister itself from the thread.
class Alarm {
public:
  // Create and register a single-shot alarm on a given thread. This creates a wake alarm.
  // `reactable_timeout` is the timeout for waiting for the reactable to be unregistered.
  // Note: If set, this will block the ~Alarm() until the reactor gets idle.
  explicit Alarm(Thread* thread,
                 std::chrono::milliseconds reactable_timeout = kDefaultReactableTimeout);

  // Create and register a single-shot alarm on a given thread.
  // This constructor can specify whether the alarm will be a wake alarm or a non-wake alarm.
  // `reactable_timeout` is the timeout for waiting for the reactable to be unregistered.
  // Note: If set, this will block the ~Alarm() until the reactor gets idle.
  explicit Alarm(Thread* thread, bool isWakeAlarm,
                 std::chrono::milliseconds reactable_timeout = kDefaultReactableTimeout);

  Alarm(const Alarm&) = delete;
  Alarm& operator=(const Alarm&) = delete;

  // Unregister this alarm from the thread and release resource
  ~Alarm();

  // Schedule the alarm with given delay
  void Schedule(base::OnceClosure task, std::chrono::milliseconds delay);

  // Cancel the alarm. No-op if it's not armed.
  void Cancel();

  std::chrono::system_clock::time_point GetArmedTime() { return armed_time_; }

private:
  base::OnceClosure task_;
  std::chrono::system_clock::time_point armed_time_;
  Thread* thread_;
  int fd_ = 0;
  Reactor::Reactable* token_;
  mutable std::mutex mutex_;
  void on_fire();

  std::chrono::milliseconds reactable_timeout_;
};

}  // namespace os

}  // namespace bluetooth
