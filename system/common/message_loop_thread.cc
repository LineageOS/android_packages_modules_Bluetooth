/*
 * Copyright 2018 The Android Open Source Project
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

#include "message_loop_thread.h"

#include <base/functional/callback.h>
#include <base/location.h>
#include <base/time/time.h>
#include <bluetooth/log.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <future>
#include <mutex>
#include <string>
#include <thread>

#include "common/postable_context.h"

namespace bluetooth {
namespace common {

static constexpr int kRealTimeFifoSchedulingPriority = 1;

static base::TimeDelta timeDeltaFromMicroseconds(std::chrono::microseconds t) {
#if BASE_VER < 931007
  return base::TimeDelta::FromMicroseconds(t.count());
#else
  return base::Microseconds(t.count());
#endif
}

MessageLoopThread::MessageLoopThread(const std::string& thread_name)
    : thread_name_(thread_name),
      message_loop_(nullptr),
      run_loop_(nullptr),
      thread_(nullptr),
      thread_id_(-1),
      linux_tid_(-1),
      weak_ptr_factory_(this),
      shutting_down_(false) {}

MessageLoopThread::~MessageLoopThread() { ShutDown(); }

void MessageLoopThread::StartUp() {
  std::promise<void> start_up_promise;
  std::future<void> start_up_future = start_up_promise.get_future();
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (thread_ != nullptr) {
      log::warn("thread {} is already started", *this);

      return;
    }
    thread_ = new std::thread(&MessageLoopThread::RunThread, this, std::move(start_up_promise));
  }
  start_up_future.wait();
}

bool MessageLoopThread::DoInThread(base::OnceClosure task) {
  return DoInThreadDelayed(std::move(task), std::chrono::microseconds(0));
}

bool MessageLoopThread::DoInThreadDelayed(base::OnceClosure task, std::chrono::microseconds delay) {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);

  if (message_loop_ == nullptr) {
    log::error("message loop is null for thread {}", *this);
    return false;
  }
  if (!message_loop_->task_runner()->PostDelayedTask(FROM_HERE, std::move(task),
                                                     timeDeltaFromMicroseconds(delay))) {
    log::error("failed to post task to message loop for thread {}", *this);
    return false;
  }
  return true;
}

void MessageLoopThread::ShutDown() {
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (thread_ == nullptr) {
      log::info("thread {} is already stopped", *this);
      return;
    }
    if (message_loop_ == nullptr) {
      log::info("message_loop_ is null. Already stopping");
      return;
    }
    if (shutting_down_) {
      log::info("waiting for thread to join");
      return;
    }
    shutting_down_ = true;
    log::assert_that(thread_id_ != base::PlatformThread::CurrentId(),
                     "should not be called on the thread itself. Otherwise, deadlock may happen.");
    run_loop_->QuitWhenIdle();
  }
  thread_->join();
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    delete thread_;
    thread_ = nullptr;
    shutting_down_ = false;
  }
}

base::PlatformThreadId MessageLoopThread::GetThreadId() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return thread_id_;
}

std::string MessageLoopThread::GetName() const { return thread_name_; }

std::string MessageLoopThread::ToString() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return std::format("{}({})", thread_name_, thread_id_);
}

bool MessageLoopThread::IsRunning() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return thread_id_ != -1;
}

// Non API method, should not be protected by API mutex
void MessageLoopThread::RunThread(MessageLoopThread* thread, std::promise<void> start_up_promise) {
  thread->Run(std::move(start_up_promise));
}

// This is only for use in tests.
btbase::AbstractMessageLoop* MessageLoopThread::message_loop() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return message_loop_;
}

bool MessageLoopThread::EnableRealTimeScheduling() {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);

  if (!IsRunning()) {
    log::error("thread {} is not running", *this);
    return false;
  }

  struct sched_param rt_params = {.sched_priority = kRealTimeFifoSchedulingPriority};
  int rc = sched_setscheduler(linux_tid_, SCHED_FIFO, &rt_params);
  if (rc != 0) {
    log::error("unable to set SCHED_FIFO priority {} for linux_tid {}, thread {}, error: {}",
               kRealTimeFifoSchedulingPriority, linux_tid_, *this, strerror(errno));
    return false;
  }
  return true;
}

base::WeakPtr<MessageLoopThread> MessageLoopThread::GetWeakPtr() {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return weak_ptr_factory_.GetWeakPtr();
}

void MessageLoopThread::Run(std::promise<void> start_up_promise) {
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);

    log::info("message loop starting for thread {}", thread_name_);
    base::PlatformThread::SetName(thread_name_);
    message_loop_ = new btbase::AbstractMessageLoop();
    run_loop_ = new base::RunLoop();
    thread_id_ = base::PlatformThread::CurrentId();
    linux_tid_ = static_cast<pid_t>(syscall(SYS_gettid));
    start_up_promise.set_value();
  }

  // Blocking until ShutDown() is called
  run_loop_->Run();

  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    thread_id_ = -1;
    linux_tid_ = -1;
    delete message_loop_;
    message_loop_ = nullptr;
    delete run_loop_;
    run_loop_ = nullptr;
    log::info("message loop finished for thread {}", thread_name_);
  }
}

void MessageLoopThread::Post(base::OnceClosure closure) { DoInThread(std::move(closure)); }

PostableContext* MessageLoopThread::Postable() { return this; }

}  // namespace common
}  // namespace bluetooth
