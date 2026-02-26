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

#include <chrono>
#include <format>
#include <future>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <utility>

#include "com_android_bluetooth_flags.h"
#include "common/postable_context.h"
#include "os/handler.h"

namespace bluetooth {
namespace common {

tBlockedThreads MessageLoopThread::blocked_threads_;
int MessageLoopThread::sync_task_posted_count_ = 0;
static constexpr int kRealTimeFifoSchedulingPriority = 1;

static base::TimeDelta timeDeltaFromMicroseconds(std::chrono::microseconds t) {
#if BASE_VER < 931007
  return base::TimeDelta::FromMicroseconds(t.count());
#else
  return base::Microseconds(t.count());
#endif
}

MessageLoopThread::MessageLoopThread(const std::string& thread_name)
    : MessageLoopThread(thread_name, os::Thread::Priority::NORMAL) {}

MessageLoopThread::MessageLoopThread(const std::string& thread_name,
                                     os::Thread::Priority handler_thread_priority)
    : thread_name_(thread_name),
      message_loop_(nullptr),
      run_loop_(nullptr),
      thread_(nullptr),
      thread_id_(-1),
      linux_tid_(-1),
      shutting_down_(false),
      handler_thread_(nullptr),
      handler_(nullptr),
      handler_thread_priority_(handler_thread_priority) {
  // Thread and Handler will be initiated in StartUp().
}

MessageLoopThread::~MessageLoopThread() { ShutDown(); }

void MessageLoopThread::StartUp() {
  std::promise<void> start_up_promise;
  std::future<void> start_up_future = start_up_promise.get_future();

  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (handler_thread_ != nullptr) {
      log::warn("handler_thread_ {} is already started", *this);
      return;
    }

    handler_thread_ =
            new os::Thread(thread_name_, handler_thread_priority_, std::move(start_up_promise));
    handler_ = new os::Handler(handler_thread_);
  } else {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (thread_ != nullptr) {
      log::warn("thread {} is already started", *this);

      return;
    }
    thread_ = new std::thread(&MessageLoopThread::RunThread, this, std::move(start_up_promise));
  }

  start_up_future.wait();
  log::info("MessageLoopThread {} started", thread_name_);
}

bool MessageLoopThread::DoInThread(base::OnceClosure task) {
  return DoInThreadElseReturn(std::move(task)) == std::nullopt;
}

bool MessageLoopThread::DoInThreadDelayed(base::OnceClosure task, std::chrono::microseconds delay) {
  return DoInThreadDelayedElseReturn(std::move(task), delay) == std::nullopt;
}

std::optional<base::OnceClosure> MessageLoopThread::DoInThreadElseReturn(base::OnceClosure task) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (handler_ == nullptr) {
      log::error("handler is null for thread {}", *this);
      return std::move(task);
    }

    return handler_->Post(std::move(task));
  }

  return DoInThreadDelayedElseReturn(std::move(task), std::chrono::microseconds(0));
}

std::optional<base::OnceClosure> MessageLoopThread::DoInThreadDelayedElseReturn(
        base::OnceClosure task, std::chrono::microseconds delay) {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    if (handler_ == nullptr) {
      log::error("handler is null for thread {}", *this);
      return std::move(task);
    }

    return handler_->PostWithDelay(std::move(task),
                                   std::chrono::duration_cast<std::chrono::milliseconds>(delay));
  }

  if (message_loop_ == nullptr) {
    log::error("message loop is null for thread {}", *this);
    return std::move(task);
  }
  if (!message_loop_->task_runner()->PostDelayedTask(FROM_HERE, std::move(task),
                                                     timeDeltaFromMicroseconds(delay))) {
    log::error("failed to post task to message loop for thread {}", *this);
    return std::nullopt;  // this part of function is getting phased out, so returning nullopt
                          // instead of move(task), as its libchrome dependent.
  }
  return std::nullopt;
}

void MessageLoopThread::Suspend(os::Handler* caller_handler) {
  if (!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    return;
  }

  os::Handler* temp_handler;
  std::future<void> future;
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    /**
     * Temporarily set the handler to nullptr to prevent any further tasks from being posted, and
     * also avoiding dup Suspend and ShutDown calls.
     */
    temp_handler = caller_handler;
    if (temp_handler == nullptr) {
      temp_handler =
              handler_;  // If caller_handler is not provided, suspend the instance's handler_.
      handler_ = nullptr;
    }

    if (temp_handler == nullptr) {  // or (handler_thread_ == nullptr)
      log::error("handler is already stopped for thread {}", *this);
      return;
    }

    /**
     * Waiting for the handler to be idle.
     * This replicates RunLoop::QuitWhenIdle() functionality.
     */
    future = temp_handler->NotifyWhenIdle();
  }

  // Let this thread finish with `api_mutex_` released.
  log::assert_that(future.wait_for(kHandlerStopTimeout) == std::future_status::ready,
                   "assert failed: Thread {} is not idle after waiting for {} ms",
                   handler_thread_->GetThreadName(), kHandlerStopTimeout.count());
  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    temp_handler->Clear();
  }

  // To prevent deadlock, release the lock before waiting for the reactable to be unregistered.
  // This is safe because the handler is already cleared and will no longer accept tasks.
  temp_handler->WaitUntilStopped(kHandlerStopTimeout);

  {
    std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
    if (caller_handler == nullptr) {
      // Restore only if caller_handler is not provided, otherwise caller (ShutDown()) will manage.
      handler_ = temp_handler;
    }
  }
}

void MessageLoopThread::ShutDown() {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    os::Handler* temp_handler;
    {
      std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
      /**
       * Temporarily set the handler to nullptr to prevent any further tasks from being posted, and
       * also avoiding dup Suspend and ShutDown calls.
       */
      temp_handler = handler_;
      handler_ = nullptr;

      if (temp_handler == nullptr) {  // or (handler_thread_ == nullptr)
        log::error("handler is already stopped for thread {}", *this);
        return;
      }
    }

    if (!temp_handler->IsCleared()) {
      log::info("MessageLoopThread was not previously suspended");
      Suspend(temp_handler);
    }

    {
      std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
      log::info("MessageLoopThread {}, is getting stopped.", GetName());
      delete temp_handler;
      delete handler_thread_;
      // The destructor of os::Thread will stop and join the thread.

      handler_thread_ = nullptr;
      return;
    }
  }

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
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    // Invalidate this call, as Handler's Thread ID is std::thread::id, which is not compatible with
    // base::PlatformThreadId (pid_t).
    log::fatal(
            "GetThreadId should not be called when flag "
            "replace_message_loop_thread_with_gd_handler is enabled.");
#if defined(TARGET_FLOSS) && BASE_VER >= 1419016
    return base::PlatformThreadId(-1);
#else
    return -1;
#endif  // defined(TARGET_FLOSS) && BASE_VER >= 1419016
  }

  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  return thread_id_;
}

bool MessageLoopThread::IsRunningOnSameThread() const {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    return handler_thread_ != nullptr && handler_thread_->IsSameThread();
  }

  return thread_id_ == base::PlatformThread::CurrentId();
}

std::string MessageLoopThread::GetName() const { return thread_name_; }

std::string MessageLoopThread::ToString() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    // TODO: In C++23, std::thread::id will have a formatter, use that instead.
    std::string thread_id = (handler_thread_ != nullptr)
                                    ? (std::stringstream{} << handler_thread_->GetThreadId()).str()
                                    : "null";
    return std::format("{} (thread_id: {})", thread_name_, thread_id);
  }

#if defined(TARGET_FLOSS) && BASE_VER >= 1419016
  return std::format("{}({})", thread_name_, thread_id_.raw());
#else
  return std::format("{}({})", thread_name_, thread_id_);
#endif  // defined(TARGET_FLOSS) && BASE_VER >= 1419016
}

bool MessageLoopThread::IsRunning() const {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    return handler_thread_ != nullptr;
  }

#if defined(TARGET_FLOSS) && BASE_VER >= 1419016
  return thread_id_.raw() != -1;
#else
  return thread_id_ != -1;
#endif  // defined(TARGET_FLOSS) && BASE_VER >= 1419016
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

  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    return handler_thread_->GetPriority() == os::Thread::Priority::REAL_TIME;
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

// Note: Crash if called when flag replace_message_loop_thread_with_gd_handler is enabled.
base::WeakPtr<MessageLoopThread> MessageLoopThread::GetWeakPtr() {
  log::assert_that(!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler(),
                   "This function should not be called when flag "
                   "replace_message_loop_thread_with_gd_handler is enabled.");
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
#if defined(TARGET_FLOSS) && BASE_VER >= 1419016
    thread_id_ = base::PlatformThreadId(-1);
#else
    thread_id_ = -1;
#endif  // defined(TARGET_FLOSS) && BASE_VER >= 1419016
    linux_tid_ = -1;
    delete message_loop_;
    message_loop_ = nullptr;
    delete run_loop_;
    run_loop_ = nullptr;
    log::info("message loop finished for thread {}", thread_name_);
  }
}

std::optional<base::OnceClosure> MessageLoopThread::Post(base::OnceClosure closure) {
  return DoInThreadElseReturn(std::move(closure));
}

PostableContext* MessageLoopThread::Postable() {
  std::lock_guard<std::recursive_mutex> api_lock(api_mutex_);
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    return handler_;
  }

  return this;
}

pid_t MessageLoopThread::GetLinuxThreadId(MessageLoopThread* context) {
  if (context == nullptr) {
    return static_cast<pid_t>(syscall(SYS_gettid));
  }

  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    // If handler_thread_ is stopped, return -1 (invalid tid)
    return (context->handler_thread_ == nullptr) ? -1 : context->handler_thread_->GetLinuxTid();
  }
  return context->GetLinuxTid();
}

}  // namespace common
}  // namespace bluetooth
