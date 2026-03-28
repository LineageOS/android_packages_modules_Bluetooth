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

#include "os/handler.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <sys/timerfd.h>

#include <chrono>
#include <ctime>

#include "os/reactor.h"
namespace bluetooth {
namespace os {

Handler::Handler(Thread* thread)
    : tasks_(new std::queue<base::OnceClosure>()),
      thread_(thread),
      delayed_tasks_(new DelayedTaskQueue()) {
  alarm_ = new Alarm(thread_, false);
  event_ = thread_->GetReactor()->NewEvent();
  reactable_ = thread_->GetReactor()->Register(
          event_->Id(),
          base::BindRepeating(&Handler::handle_all_queued_events, base::Unretained(this)),
          base::RepeatingClosure());
}

Handler::~Handler() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    log::assert_that(was_cleared(),
                     "Handlers must be cleared before they are destroyed, thread: {}",
                     thread_->GetThreadName());
  }
  event_->Close();
}

std::optional<base::OnceClosure> Handler::Post(base::OnceClosure closure) {
  bool should_notify = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (was_cleared()) {
      log::warn("Posting to a handler which has been cleared, thread: {}",
                thread_->GetThreadName());
      return std::move(closure);
    }
    tasks_->emplace(std::move(closure));
    if (!is_active_) {
      is_active_ = true;
      should_notify = true;
    }
  }

  // We only skip notification if we are currently inside the handle_all_queued_events
  // loop for this specific handler.
  // Otherwise, we must notify to ensure the Reactor triggers a new
  // handle_all_queued_events turn.
  if (should_notify) {
    event_->Notify();
  }
  return std::nullopt;
}

void Handler::Clear() {
  std::queue<base::OnceClosure>* tmp = nullptr;
  Reactor::Reactable* reactable = nullptr;
  DelayedTaskQueue* delayed_tasks = nullptr;
  Alarm* alarm = nullptr;

  {
    std::lock_guard<std::mutex> lock(mutex_);
    log::assert_that(!was_cleared(), "Handlers must only be cleared once, thread: {}",
                     thread_->GetThreadName());
    std::swap(tasks_, tmp);
    std::swap(reactable_, reactable);
    std::swap(delayed_tasks_, delayed_tasks);
    std::swap(alarm_, alarm);
  }

  alarm->Cancel();

  delete tmp;
  delete delayed_tasks;
  delete alarm;
  // TODO: Log all the pending tasks from the queue.

  event_->Clear();

  thread_->GetReactor()->Unregister(reactable);
}

void Handler::WaitUntilStopped(std::chrono::milliseconds timeout) {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    log::assert_that(reactable_ == nullptr, "assert failed: reactable_ == nullptr, for thread: {}",
                     thread_->GetThreadName());
  }
  log::assert_that(thread_->GetReactor()->WaitForUnregisteredReactable(timeout),
                   "assert failed: thread_->GetReactor()->WaitForUnregisteredReactable(timeout)");
}

void Handler::handle_all_queued_events() {
  event_->Read();
  while (true) {
    base::OnceClosure closure;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (was_cleared() || tasks_->empty()) {
        is_active_ = false;
        return;
      }
      is_active_ = true;

      closure = std::move(tasks_->front());
      tasks_->pop();
      notify_promise_if_idle();
    }
    std::move(closure).Run();
  }
}

std::future<void> Handler::NotifyWhenIdle() {
  std::lock_guard<std::mutex> lock(mutex_);
  log::assert_that(!promise_to_quit_when_idle_.has_value(),
                   "assert failed: called more than once before setting the promise, thread: {}",
                   thread_->GetThreadName());

  promise_to_quit_when_idle_ = std::promise<void>();
  std::future<void> future = promise_to_quit_when_idle_.value().get_future();
  notify_promise_if_idle();
  return future;
}

std::optional<base::OnceClosure> Handler::PostWithDelay(base::OnceClosure closure,
                                                        std::chrono::milliseconds delay) {
  if (delay == std::chrono::milliseconds::zero()) {
    return Post(std::move(closure));
  }

  bool reschedule = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (was_cleared()) {
      log::warn("Posting to a handler which has been cleared, thread: {}",
                thread_->GetThreadName());
      return std::move(closure);
    }

    auto time_to_run = boottime_clock::now() + delay;
    // If the delayed task queue is empty, or the top of the queue (for which the timer is already
    // running) is later than the current time, then we need to restart the timer.
    if (delayed_tasks_->empty() || delayed_tasks_->top().first > time_to_run) {
      reschedule = true;
    }
    delayed_tasks_->emplace(time_to_run, std::move(closure));
  }

  if (reschedule) {
    reschedule_delayed_tasks();
  }
  return std::nullopt;
}

void Handler::handle_delayed_event() {
  TimePoint deadline = boottime_clock::now();
  int num_tasks_posted = 0;

  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (was_cleared()) {
      log::warn("Timer expired, but found no tasks to post, thread: {}", thread_->GetThreadName());
      return;
    }

    // Post all the expired tasks to the main task queue.
    // Either the task is expired (<= deadline), or the task is due in less than 1ms.
    while (!delayed_tasks_->empty() &&
           ((delayed_tasks_->top().first - deadline) <= std::chrono::milliseconds(1))) {
      base::OnceClosure closure =
              std::move(const_cast<base::OnceClosure&>(delayed_tasks_->top().second));
      delayed_tasks_->pop();
      tasks_->emplace(std::move(closure));
      num_tasks_posted++;
    }
  }

  // If there are tasks posted, notify the Reactor.
  if (num_tasks_posted > 0) {
    event_->Notify(num_tasks_posted);
  }

  reschedule_delayed_tasks();
}

// Start/Restart the alarm for the delayed tasks.
// Note: Caller must ensure that this function will only be called when there is a need to
// start/restart the alarm.
void Handler::reschedule_delayed_tasks() {
  std::lock_guard<std::mutex> lock(mutex_);
  TimePoint current_time = boottime_clock::now();

  if (was_cleared() || delayed_tasks_->empty()) {
    return;
  }
  std::chrono::duration next_task_time = delayed_tasks_->top().first - current_time;
  std::chrono::milliseconds next_task_time_ms =
          std::max(std::chrono::duration_cast<std::chrono::milliseconds>(next_task_time),
                   std::chrono::milliseconds(1));
  alarm_->Schedule(base::BindOnce(&Handler::handle_delayed_event, base::Unretained(this)),
                   next_task_time_ms);
}

}  // namespace os
}  // namespace bluetooth
