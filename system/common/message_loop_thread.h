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

#pragma once

#include <base/functional/bind.h>
#include <base/location.h>
#include <base/run_loop.h>
#include <base/threading/platform_thread.h>
#include <bluetooth/log.h>
#include <unistd.h>

#include <chrono>
#include <future>
#include <mutex>
#include <ostream>
#include <string>
#include <thread>

#include "abstract_message_loop.h"
#include "common/postable_context.h"
#include "os/handler.h"
#include "os/thread.h"

namespace bluetooth {

namespace common {
/**
 * A thread-safe container to track blocked threads.
 */
struct tBlockedThreads {
public:
  void unblock(pid_t thread_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    blocked_.erase(thread_id);
  }

  // Checks if the passed thread id is blocked.
  bool blocked(pid_t thread_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    return blocked_.contains(thread_id);
  }

  /**
   * Checks if the passed thread id is blocked and sets it as blocked if not.
   * @return the blocked_ status it held before.
   */

  bool testAndBlock(pid_t thread_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (blocked_.contains(thread_id)) {
      return true;  // thread is already blocked
    }
    blocked_.insert(thread_id);  // mark thread as blocked
    return false;
  }

private:
  std::set<pid_t> blocked_;
  std::mutex mutex_;
};

/**
 * An interface to various thread related functionality
 */
class MessageLoopThread final : public PostableContext {
public:
  /**
   * Create a message loop thread with name. Thread won't be running until
   * StartUp is called.
   *
   * @param thread_name name of this worker thread
   */
  explicit MessageLoopThread(const std::string& thread_name);
  explicit MessageLoopThread(const std::string& thread_name,
                             os::Thread::Priority handler_thread_priority);

  MessageLoopThread(const MessageLoopThread&) = delete;
  MessageLoopThread& operator=(const MessageLoopThread&) = delete;

  /**
   * Destroys the message loop thread automatically when it goes out of scope
   */
  ~MessageLoopThread();

  /**
   * Start the underlying thread. Blocks until all thread infrastructure is
   * setup. IsRunning() and DoInThread() should return true after this call.
   * Blocks until the thread is successfully started.
   *
   * Repeated call to this method will only start this thread once
   */
  void StartUp();

  /**
   * Post a task to run on this thread
   *
   * @param task task created through base::Bind()
   * @return true if task is successfully scheduled, false if task cannot be
   * scheduled
   */
  bool DoInThread(base::OnceClosure task);

  /**
   * Post a task to run on this thread. If the task cannot be posted, it will be returned to the
   * caller.
   *
   * @param task task created through base::Bind()
   * @return std::nullopt if the task is successfully posted, otherwise the
   * task is returned to the caller.
   */
  std::optional<base::OnceClosure> DoInThreadElseReturn(base::OnceClosure task);

  /**
   * Suspend the current thread blocking the execution of new tasks on the thread.
   * ShutDown must be called after this to clean up the resources.
   *
   * @param caller_handler the handler on which Suspend is called. If nullptr, the instance's
   * handler_ will be suspended.
   */
  void Suspend(os::Handler* caller_handler = nullptr);

  /**
   * Shutdown the current thread as if it is never started. IsRunning() and
   * DoInThread() will return false after this call. Blocks until the thread is
   * joined and freed. This thread can be re-started again using StartUp()
   *
   * Repeated call to this method will only stop this thread once
   *
   * NOTE: Should never be called on the thread itself to avoid deadlock
   */
  void ShutDown();

  /**
   * Get the current thread ID returned by PlatformThread::CurrentId()
   *
   * On Android platform, this value should be the same as the tid logged by
   * logcat, which is returned by gettid(). On other platform, this thread id
   * may have different meanings. Therefore, this ID is only good for logging
   * and thread comparison purpose
   *
   * @return this thread's ID
   */
  base::PlatformThreadId GetThreadId() const;

  /**
   * Check if the current thread in use is same as this thread.
   * Note: This is only valid when flag replace_message_loop_thread_with_gd_handler is enabled.
   *
   * @return true if the current thread in use is same as this thread.
   */
  bool IsRunningOnSameThread() const;

  /**
   * Get this thread's name set in constructor
   *
   * @return this thread's name set in constructor
   */
  std::string GetName() const;

  /**
   * Get a string representation of this thread
   *
   * @return a string representation of this thread
   */
  std::string ToString() const;

  /**
   * Check if this thread is running
   *
   * @return true iff this thread is running and is able to do task
   */
  bool IsRunning() const;

  /**
   * Attempt to make scheduling for this thread real time
   *
   * @return true on success, false otherwise
   */
  bool EnableRealTimeScheduling();

  /**
   * Return the weak pointer to this object. This can be useful when posting
   * delayed tasks to this MessageLoopThread using Timer.
   */
  base::WeakPtr<MessageLoopThread> GetWeakPtr();

  /**
   * Return the message loop for this thread. Accessing raw message loop is not
   * recommended as message loop can be freed internally.
   *
   * @return message loop associated with this thread, nullptr if thread is not
   * running
   */
  btbase::AbstractMessageLoop* message_loop() const;

  /**
   * Post a task to run on this thread after a specified delay. If the task
   * needs to be cancelable before it's run, use base::CancelableClosure type
   * for task closure. For example:
   * <code>
   * base::CancelableClosure cancelable_task;
   * cancelable_task.Reset(base::Bind(...)); // bind the task
   * same_thread->DoInThreadDelayed(cancelable_task.callback(), delay);
   * ...
   * // Cancel the task closure
   * same_thread->DoInThread(base::Bind(&base::CancelableClosure::Cancel,
   *                         base::Unretained(&cancelable_task)));
   * </code>
   *
   * Warning: base::CancelableClosure objects must be created on, posted to,
   * cancelled on, and destroyed on the same thread.
   *
   * Note: Please call this method very carefully as this is a blocking synchronous call. This will
   * block the current thread on the target thread and if there is an existing deadlock this may
   * result in a crash.
   *
   * @param task task created through base::Bind()
   * @param delay delay for the task to be executed
   * @return true if task is successfully scheduled, false if task cannot be
   * scheduled
   */
  bool DoInThreadDelayed(base::OnceClosure task, std::chrono::microseconds delay);

  /**
   * Post a task to run on this thread after a specified delay. If the task cannot be posted, it
   * will be returned to the caller.
   *
   * @param task task created through base::Bind()
   * @param delay delay for the task to be executed
   * @return std::nullopt if the task is successfully posted, otherwise the task is returned to the
   * caller.
   */
  std::optional<base::OnceClosure> DoInThreadDelayedElseReturn(base::OnceClosure task,
                                                               std::chrono::microseconds delay);

  /**
   * Wrapper around DoInThread without a location.
   */
  std::optional<base::OnceClosure> Post(base::OnceClosure closure) override;

  /**
   * Returns a postable object
   */
  PostableContext* Postable();

  /**
   * Posts a task to run on this thread and waits for the task to complete. This is a wrapping
   * function around DoInThread and DetectCircularDependency. It also detects circular dependency
   * and if found just execute the functor on the caller thread itself without posting. This is a
   * blocking call, and will return only after the task is completed.
   *
   * @param func_ptr the function to run
   * @param args the arguments to pass to the function
   * @return the result of the function execution
   */
  template <typename Functor, typename... Args>
  auto DoInThreadSynchronously(Functor&& func_ptr, Args&&... args) {
    using resultType = decltype(std::declval<Functor>()(std::declval<Args>()...));
    using promiseType = std::promise<resultType>;

    auto promise = std::make_shared<promiseType>();
    std::future<resultType> future = promise->get_future();
    auto task = [](Functor&& func, std::shared_ptr<promiseType> p, MessageLoopThread* self,
                   int task_number, std::decay_t<Args>... a) {
      if (self) {
        log::info("Task#: {} started on thread: {}", task_number, self->GetName());
      }
      if constexpr (std::is_void_v<resultType>) {
        std::move(func)(std::move(a)...);
        p->set_value();
      } else {
        p->set_value(std::move(func)(std::move(a)...));
      }
    };

    // If target thread is not same as current thread, and if the target thread is also not blocked.
    auto target_thread_id = GetLinuxThreadId(this);
    auto caller_thread_id = GetLinuxThreadId();
    sync_task_posted_count_++;
    if (target_thread_id != -1 && !blocked_threads_.blocked(target_thread_id)) {
      if (!IsRunningOnSameThread()) {
        // block current thread, currently its unblocked
        blocked_threads_.testAndBlock(caller_thread_id);
        log::info("Blocked current_thread id: {}, on the target thread: {}({}), for task#: {}",
                  caller_thread_id, target_thread_id, thread_name_, sync_task_posted_count_);
        std::optional<base::OnceClosure> posted_task = DoInThreadElseReturn(
                base::BindOnce(task, std::forward<Functor>(func_ptr), std::move(promise), this,
                               sync_task_posted_count_, std::forward<Args>(args)...));
        if (!posted_task.has_value()) {
          auto result = future.wait_for(kHandlerStopTimeout);
          blocked_threads_.unblock(caller_thread_id);  // unblock current thread
          log::assert_that(result == std::future_status::ready,
                           "assert failed: Thread: {}, is not idle after waiting for: {} ms",
                           thread_name_, kHandlerStopTimeout.count());
        } else {
          blocked_threads_.unblock(caller_thread_id);  // unblock current thread
          log::warn(
                  "Failed to post task#: {} on thread: {}, executing task on current thread "
                  "instead.",
                  sync_task_posted_count_, thread_name_);
          std::move(posted_task.value()).Run();  // execute the task on current thread
        }
        return future.get();
      }
    } else {
      log::warn("Target thread: {}(tid: {}) is blocked, executing task on current thread",
                thread_name_, target_thread_id);
    }

    task(std::forward<Functor>(func_ptr), std::move(promise), nullptr, sync_task_posted_count_,
         std::forward<Args>(args)...);
    return future.get();
  }

  /**
   * Returns the current thread ID.
   * If a Thread argument is provided, the thread ID of the provided thread is
   * returned, otherwise the current thread ID is returned.
   *
   * @param context the MessageLoopThread object to get the thread ID from. If
   * nullptr, the current thread ID is returned.
   * @return the current thread ID.
   */
  static pid_t GetLinuxThreadId(MessageLoopThread* context = nullptr);

  pid_t GetLinuxTid() const { return linux_tid_; }

private:
  /**
   * Static method to run the thread
   *
   * This is used instead of a C++ lambda because of the use of std::shared_ptr
   *
   * @param context needs to be a pointer to an instance of MessageLoopThread
   * @param start_up_promise a std::promise that is used to notify calling
   * thread the completion of message loop start-up
   */
  static void RunThread(MessageLoopThread* context, std::promise<void> start_up_promise);

  /**
   * Actual method to run the thread, blocking until ShutDown() is called
   *
   * @param start_up_promise a std::promise that is used to notify calling
   * thread the completion of message loop start-up
   */
  void Run(std::promise<void> start_up_promise);

  mutable std::recursive_mutex api_mutex_;
  const std::string thread_name_;
  btbase::AbstractMessageLoop* message_loop_;
  base::RunLoop* run_loop_;
  std::thread* thread_;
  base::PlatformThreadId thread_id_;
  // Linux specific abstractions
  pid_t linux_tid_;
  bool shutting_down_;

  os::Thread* handler_thread_;
  os::Handler* handler_;
  os::Thread::Priority handler_thread_priority_;
  static tBlockedThreads blocked_threads_;
  // Member variables should appear before the WeakPtrFactory, to ensure
  // that any WeakPtrs are invalidated before its members
  // variable's destructors are executed, rendering them invalid.
  base::WeakPtrFactory<MessageLoopThread> weak_ptr_factory_{this};
  static int sync_task_posted_count_;
};

inline std::ostream& operator<<(std::ostream& os, const bluetooth::common::MessageLoopThread& a) {
  os << a.ToString();
  return os;
}

}  // namespace common
}  // namespace bluetooth

namespace std {
template <>
struct formatter<bluetooth::common::MessageLoopThread> : ostream_formatter {};
}  // namespace std
