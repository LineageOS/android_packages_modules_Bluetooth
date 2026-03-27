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

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/thread_annotations.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <queue>

#include "common/postable_context.h"
#include "os/alarm.h"
#include "os/boottime_clock.h"
#include "os/thread.h"

namespace bluetooth {
// Timeout for waiting for a handler to stop, used in Handler::WaitUntilStopped()
constexpr std::chrono::milliseconds kHandlerStopTimeout = std::chrono::milliseconds(2000);
using TimePoint = os::boottime_clock::time_point;
using DelayedTask = std::pair<TimePoint, base::OnceClosure>;

// Define the lambda comparator
inline auto compare_task_by_time = [](const DelayedTask& a, const DelayedTask& b) {
  // For a min-heap of time_points (earliest time has highest priority),
  // this returns true if 'a' should come after 'b' (i.e., 'a' has a later time).
  return a.first > b.first;
};

// Priority queue of delayed tasks, the minimum to maximum priority.
// Note: `time_point` delay is absolute time in the future, and will be compared against the
// current time.
using DelayedTaskQueue =
        std::priority_queue<DelayedTask, std::vector<DelayedTask>, decltype(compare_task_by_time)>;

namespace os {

// A message-queue style handler for reactor-based thread to handle incoming events from different
// threads. When it's constructed, it will register a reactable on the specified thread; when it's
// destroyed, it will unregister itself from the thread.
class Handler : public common::PostableContext {
public:
  // Create and register a handler on given thread
  explicit Handler(Thread* thread);

  Handler(const Handler&) = delete;
  Handler& operator=(const Handler&) = delete;

  // Unregister this handler from the thread and release resource. Unhandled events will be
  // discarded and not executed.
  virtual ~Handler();

  // Enqueue a closure to the queue of this handler
  virtual std::optional<base::OnceClosure> Post(base::OnceClosure closure) override;

  // Remove all pending events from the queue of this handler, and asynchronously stop the handler.
  void Clear();

  // Die if the current reactable doesn't stop before the timeout.  Must be called after Clear()
  void WaitUntilStopped(std::chrono::milliseconds timeout);

  template <typename Functor, typename... Args>
  void Call(Functor&& functor, Args&&... args) {
    Post(base::BindOnce(std::forward<Functor>(functor), std::forward<Args>(args)...));
  }

  template <typename T, typename Functor, typename... Args>
  void CallOn(T* obj, Functor&& functor, Args&&... args) {
    Post(base::BindOnce(std::forward<Functor>(functor), base::Unretained(obj),
                        std::forward<Args>(args)...));
  }

  Thread& thread() const { return *thread_; }

  // Returns true if `Clear` has been called, but the handler could still be running (see
  // WaitUntilStopped).
  bool IsCleared() const LOCKS_EXCLUDED(mutex_) {
    std::lock_guard<std::mutex> lock(mutex_);
    return reactable_ == nullptr;
  }

  bool Synchronize(std::chrono::milliseconds timeout) {
    std::promise<void> promise;
    auto future = promise.get_future();
    Post(base::BindOnce(&std::promise<void>::set_value, base::Unretained(&promise)));
    return future.wait_for(timeout) == std::future_status::ready;
  }

  common::PostableContext* GetPostableContext() { return this; }

  std::optional<base::OnceClosure> PostWithDelay(base::OnceClosure closure,
                                                 std::chrono::milliseconds delay);

  std::future<void> NotifyWhenIdle();

  template <typename T>
  friend class Queue;

  friend class Alarm;

  friend class RepeatingAlarm;

private:
  inline bool was_cleared() const EXCLUSIVE_LOCKS_REQUIRED(mutex_) {
    return tasks_ == nullptr || delayed_tasks_ == nullptr;
  }
  std::queue<base::OnceClosure>* tasks_ GUARDED_BY(mutex_);
  // Flag to track whether this handler is currently draining tasks on its thread.
  // This is used to avoid redundant eventfd notifications when new tasks are posted
  // while the handler is already in its dispatch loop.
  bool is_active_ GUARDED_BY(mutex_){false};

  Thread* thread_;
  std::unique_ptr<Reactor::Event> event_;
  Reactor::Reactable* reactable_ GUARDED_BY(mutex_);
  mutable std::mutex mutex_;
  std::optional<std::promise<void>> promise_to_quit_when_idle_ GUARDED_BY(mutex_);

  // A priority queue (minimum priority to maximum priority) of delayed tasks, once the delayed task
  // timer expires, the task will be Post()ed to the handler.
  DelayedTaskQueue* delayed_tasks_ GUARDED_BY(mutex_);

  // The alarm used to schedule delayed tasks.
  Alarm* alarm_ GUARDED_BY(mutex_);

  void handle_all_queued_events();
  void handle_delayed_event();
  void reschedule_delayed_tasks();

  // Notify the promise if the handler is idle (no tasks and no delayed tasks).
  inline void notify_promise_if_idle() EXCLUSIVE_LOCKS_REQUIRED(mutex_) {
    if ((promise_to_quit_when_idle_.has_value()) && tasks_->empty()) {
      promise_to_quit_when_idle_.value().set_value();
      promise_to_quit_when_idle_ = std::nullopt;
    }
  }
};

}  // namespace os
}  // namespace bluetooth
