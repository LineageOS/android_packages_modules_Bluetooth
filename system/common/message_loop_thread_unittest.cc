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

#include <base/functional/bind.h>
#include <base/threading/platform_thread.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <gtest/gtest.h>
#include <sys/capability.h>
#include <syscall.h>

#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

using bluetooth::common::MessageLoopThread;
using namespace bluetooth;

/**
 * Unit tests to verify MessageLoopThread. Must have CAP_SYS_NICE capability.
 */
class MessageLoopThreadTest : public ::testing::Test {
public:
  void ShouldNotHappen() { FAIL() << "Should not happen"; }

  void GetThreadId(std::promise<base::PlatformThreadId> thread_id_promise) {
    thread_id_promise.set_value(base::PlatformThread::CurrentId());
  }

  void GetLinuxTid(std::promise<pid_t> tid_promise) {
    tid_promise.set_value(static_cast<pid_t>(syscall(SYS_gettid)));
  }

  void GetName(std::promise<std::string> name_promise) {
    char my_name[256];
    pthread_getname_np(pthread_self(), my_name, sizeof(my_name));
    name_promise.set_value(my_name);
  }

  void GetSchedulingPolicyAndPriority(int* scheduling_policy, int* schedule_priority,
                                      std::promise<void> execution_promise) {
    *scheduling_policy = sched_getscheduler(0);
    struct sched_param param = {};
    ASSERT_EQ(sched_getparam(0, &param), 0);
    *schedule_priority = param.sched_priority;
    execution_promise.set_value();
  }

  void SleepAndGetName(std::promise<std::string> name_promise, int sleep_ms) {
    std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
    GetName(std::move(name_promise));
  }

protected:
  static bool CanSetCurrentThreadPriority() {
    struct __user_cap_header_struct linux_user_header = {.version = _LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct linux_user_data[2] = {};
    if (capget(&linux_user_header, linux_user_data) != 0) {
      log::error("Failed to get capability for current thread, error: {}", strerror(errno));
      // Log record in XML
      RecordProperty("MessageLoopThreadTestCannotGetCapabilityReason", strerror(errno));
      return false;
    }
    return ((linux_user_data[0].permitted >> CAP_SYS_NICE) & 0x1) != 0;
  }
};

TEST_F(MessageLoopThreadTest, get_weak_ptr) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }
  base::WeakPtr<MessageLoopThread> message_loop_thread_ptr;
  {
    MessageLoopThread message_loop_thread("test_thread");
    message_loop_thread_ptr = message_loop_thread.GetWeakPtr();
    ASSERT_NE(message_loop_thread_ptr, nullptr);
  }
  ASSERT_EQ(message_loop_thread_ptr, nullptr);
}

TEST_F(MessageLoopThreadTest, test_running_thread) {
  MessageLoopThread message_loop_thread("test_thread");
  message_loop_thread.StartUp();
  if (!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    ASSERT_GE(message_loop_thread.GetThreadId(), 0);
    ASSERT_TRUE(message_loop_thread.IsRunning());
    message_loop_thread.ShutDown();
    ASSERT_LT(message_loop_thread.GetThreadId(), 0);
    ASSERT_FALSE(message_loop_thread.IsRunning());
    return;
  }

  ASSERT_TRUE(message_loop_thread.IsRunning());
  message_loop_thread.ShutDown();
  ASSERT_FALSE(message_loop_thread.IsRunning());
}

TEST_F(MessageLoopThreadTest, test_not_self) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }

  MessageLoopThread message_loop_thread("test_thread");
  message_loop_thread.StartUp();
  ASSERT_GE(message_loop_thread.GetThreadId(), 0);
  ASSERT_NE(message_loop_thread.GetThreadId(), base::PlatformThread::CurrentId());
}

TEST_F(MessageLoopThreadTest, test_shutdown_without_start) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }

  MessageLoopThread message_loop_thread("test_thread");
  message_loop_thread.ShutDown();
  ASSERT_LT(message_loop_thread.GetThreadId(), 0);
}

TEST_F(MessageLoopThreadTest, test_do_in_thread_before_start) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  ASSERT_FALSE(message_loop_thread.DoInThread(
          base::BindOnce(&MessageLoopThreadTest::ShouldNotHappen, base::Unretained(this))));
}

TEST_F(MessageLoopThreadTest, test_do_in_thread_after_shutdown) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  message_loop_thread.ShutDown();
  ASSERT_FALSE(message_loop_thread.DoInThread(
          base::BindOnce(&MessageLoopThreadTest::ShouldNotHappen, base::Unretained(this))));
}

TEST_F(MessageLoopThreadTest, test_name) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  if (!com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    ASSERT_GE(message_loop_thread.GetThreadId(), 0);
  }
  std::promise<std::string> name_promise;
  std::future<std::string> name_future = name_promise.get_future();
  message_loop_thread.DoInThread(base::BindOnce(&MessageLoopThreadTest::GetName,
                                                base::Unretained(this), std::move(name_promise)));
  std::string my_name = name_future.get();
  ASSERT_EQ(name, my_name);
  ASSERT_EQ(name, message_loop_thread.GetName());
}

TEST_F(MessageLoopThreadTest, test_thread_id) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }

  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  base::PlatformThreadId thread_id = message_loop_thread.GetThreadId();
  ASSERT_GE(thread_id, 0);
  std::promise<base::PlatformThreadId> thread_id_promise;
  std::future<base::PlatformThreadId> thread_id_future = thread_id_promise.get_future();
  message_loop_thread.DoInThread(base::BindOnce(&MessageLoopThreadTest::GetThreadId,
                                                base::Unretained(this),
                                                std::move(thread_id_promise)));
  base::PlatformThreadId my_thread_id = thread_id_future.get();
  ASSERT_EQ(thread_id, my_thread_id);
}

TEST_F(MessageLoopThreadTest, test_set_realtime_priority_fail_before_start) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  ASSERT_FALSE(message_loop_thread.EnableRealTimeScheduling());
}

TEST_F(MessageLoopThreadTest, test_set_realtime_priority_success) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name, os::Thread::Priority::REAL_TIME);
  message_loop_thread.StartUp();
  bool ret = message_loop_thread.EnableRealTimeScheduling();
  if (!ret) {
    if (CanSetCurrentThreadPriority()) {
      FAIL() << "Cannot set real time priority even though we have permission";
    } else {
      log::warn(
              "Allowing EnableRealTimeScheduling to fail because we don't have "
              "CAP_SYS_NICE capability");
      // Log record in XML
      RecordProperty("MessageLoopThreadTestConditionalSuccess",
                     "Mark test as success even though EnableRealTimeScheduling"
                     " failed because we don't have CAP_SYS_NICE capability");
      // Quit early since further verification is no longer needed
      return;
    }
  }
  std::promise<void> execution_promise;
  std::future<void> execution_future = execution_promise.get_future();
  int scheduling_policy = -1;
  int scheduling_priority = -1;
  message_loop_thread.DoInThread(base::BindOnce(
          &MessageLoopThreadTest::GetSchedulingPolicyAndPriority, base::Unretained(this),
          &scheduling_policy, &scheduling_priority, std::move(execution_promise)));
  execution_future.wait();
  ASSERT_EQ(scheduling_policy, SCHED_FIFO);
  // Internal implementation verified here
  ASSERT_EQ(scheduling_priority, 1);
  std::promise<pid_t> tid_promise;
  std::future<pid_t> tid_future = tid_promise.get_future();
  message_loop_thread.DoInThread(base::BindOnce(&MessageLoopThreadTest::GetLinuxTid,
                                                base::Unretained(this), std::move(tid_promise)));
  pid_t linux_tid = tid_future.get();
  ASSERT_GT(linux_tid, 0);
  ASSERT_EQ(sched_getscheduler(linux_tid), SCHED_FIFO);
  struct sched_param param = {};
  ASSERT_EQ(sched_getparam(linux_tid, &param), 0);
  // Internal implementation verified here
  ASSERT_EQ(param.sched_priority, 1);
}

TEST_F(MessageLoopThreadTest, test_message_loop_null_before_start) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  ASSERT_EQ(message_loop_thread.message_loop(), nullptr);
}

TEST_F(MessageLoopThreadTest, test_message_loop_not_null_start) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  ASSERT_NE(message_loop_thread.message_loop(), nullptr);
}

TEST_F(MessageLoopThreadTest, test_message_loop_null_after_stop) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }

  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  ASSERT_NE(message_loop_thread.message_loop(), nullptr);
  message_loop_thread.ShutDown();
  ASSERT_EQ(message_loop_thread.message_loop(), nullptr);
}

TEST_F(MessageLoopThreadTest, test_to_string_method) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  std::string thread_string_before_start = message_loop_thread.ToString();
  ASSERT_FALSE(thread_string_before_start.empty());
  log::info("Before start: {}", message_loop_thread);
  message_loop_thread.StartUp();
  std::string thread_string_running = message_loop_thread.ToString();
  ASSERT_FALSE(thread_string_running.empty());
  log::info("Running: {}", message_loop_thread);
  // String representation should look different when thread is not running
  ASSERT_STRNE(thread_string_running.c_str(), thread_string_before_start.c_str());
  message_loop_thread.ShutDown();
  std::string thread_string_after_shutdown = message_loop_thread.ToString();
  log::info("After shutdown: {}", message_loop_thread);
  // String representation should look the same when thread is not running
  ASSERT_STREQ(thread_string_after_shutdown.c_str(), thread_string_before_start.c_str());
}

// Verify the message loop thread will shutdown after callback finishes
TEST_F(MessageLoopThreadTest, shut_down_while_in_callback) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  std::promise<std::string> name_promise;
  std::future<std::string> name_future = name_promise.get_future();
  uint32_t delay_ms = 5;
  message_loop_thread.DoInThread(base::BindOnce(&MessageLoopThreadTest::SleepAndGetName,
                                                base::Unretained(this), std::move(name_promise),
                                                delay_ms));
  message_loop_thread.ShutDown();
  std::string my_name = name_future.get();
  ASSERT_EQ(name, my_name);
}

// Verify the message loop thread will shutdown after callback finishes
TEST_F(MessageLoopThreadTest, shut_down_while_in_callback_check_lock) {
  if (com_android_bluetooth_flags_replace_message_loop_thread_with_gd_handler()) {
    GTEST_SKIP()
            << "Skipping this test, flag replace_message_loop_thread_with_gd_handler is enabled.";
  }

  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  message_loop_thread.DoInThread(base::BindOnce(
          [](MessageLoopThread* thread) { thread->IsRunning(); }, &message_loop_thread));
  message_loop_thread.ShutDown();
}

// Verify multiple threads try shutdown, no deadlock/crash
TEST_F(MessageLoopThreadTest, shut_down_multi_thread) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  auto thread = std::thread(&MessageLoopThread::ShutDown, &message_loop_thread);
  message_loop_thread.ShutDown();
  thread.join();
}

// Verify multiple threads try startup, no deadlock/crash
TEST_F(MessageLoopThreadTest, start_up_multi_thread) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  auto thread = std::thread(&MessageLoopThread::StartUp, &message_loop_thread);
  thread.join();
}

// Verify multiple threads try startup/shutdown, no deadlock/crash
TEST_F(MessageLoopThreadTest, start_up_shut_down_multi_thread) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  auto thread = std::thread(&MessageLoopThread::ShutDown, &message_loop_thread);
  thread.join();
}

// Verify multiple threads try shutdown/startup, no deadlock/crash
TEST_F(MessageLoopThreadTest, shut_down_start_up_multi_thread) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  message_loop_thread.StartUp();
  message_loop_thread.ShutDown();
  auto thread = std::thread(&MessageLoopThread::StartUp, &message_loop_thread);
  thread.join();
}

// Verify that Post executes in order
TEST_F(MessageLoopThreadTest, test_post_twice) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  int counter = 0;
  message_loop_thread.StartUp();
  message_loop_thread.Post(base::BindOnce(
          [](MessageLoopThread* /* thread */, int* counter) { ASSERT_EQ((*counter)++, 0); },
          &message_loop_thread, &counter));
  message_loop_thread.Post(base::BindOnce(
          [](MessageLoopThread* /* thread */, int* counter) { ASSERT_EQ((*counter)++, 1); },
          &message_loop_thread, &counter));
  message_loop_thread.ShutDown();
  ASSERT_EQ(counter, 2);
}

// Verifies that the caller thread waits for the callback to finish.
TEST_F(MessageLoopThreadTest, test_do_in_thread_synchronously_success_no_return) {
  std::string name1 = "test_thread1";
  MessageLoopThread message_loop_thread1(name1);

  message_loop_thread1.StartUp();

  std::string sequence = "";
  message_loop_thread1.DoInThreadSynchronously([](std::string* sequence) { (*sequence) += "1"; },
                                               &sequence);
  sequence += "2";

  // Verify that the callback is executed synchronously
  ASSERT_EQ(sequence, "12");  // should be "12" (not "21")
  message_loop_thread1.ShutDown();
}

// Verifies that the caller thread waits for the callback to finish, and the
// callback returns the correct value.
TEST_F(MessageLoopThreadTest, test_do_in_thread_synchronously_success_with_return_value) {
  std::string name1 = "test_thread1";
  MessageLoopThread message_loop_thread1(name1);

  message_loop_thread1.StartUp();

  int result = 0;
  std::string sequence = "";
  result = message_loop_thread1.DoInThreadSynchronously(
          [](std::string* sequence) {
            (*sequence) += "1";
            return 100;
          },
          &sequence);
  sequence += "2";

  // Verify that the callback is executed synchronously
  ASSERT_EQ(sequence, "12");
  ASSERT_EQ(result, 100);  // should not be 0 (default)

  message_loop_thread1.ShutDown();
}

// Verifies that the caller thread waits for the callback to finish, and because of a circular
// dependency, the task is executed on the caller thread itself.
TEST_F(MessageLoopThreadTest, test_do_in_thread_synchronously_deadlock) {
  std::string name1 = "test_thread1";
  std::string name2 = "test_thread2";
  int value = 1;
  MessageLoopThread message_loop_thread1(name1);
  MessageLoopThread message_loop_thread2(name2);
  std::promise<void>
          promise_t2_blocked;  // indicates that T2 is now executing something and is blocked.
  std::future<void> future_t2_blocked = promise_t2_blocked.get_future();

  message_loop_thread1.StartUp();
  message_loop_thread2.StartUp();

  // First, post a task asynchronously on T2, which will block on T1.
  // This task on T2, will notify "this" current test thread that T2 is now blocked. This will help
  // the test thread "this" to proceed with using blocking call on test_thread -> T1 -> T2
  // eventually.
  message_loop_thread2.DoInThread(base::BindOnce(
          [](MessageLoopThread* thread1, MessageLoopThread* thread2, int* value,
             std::promise<void> promise_t2_blocked) {
            promise_t2_blocked.set_value();  // Indicates that T2 is now blocked, we can now ask T1
                                             // to block on T2.
            std::this_thread::sleep_for(std::chrono::milliseconds(
                    10));  // wait for some time, so that T1 blocks on T2 (refer to next
                           // task after future_t2_blocked.wait()).

            // Now the attempt for T2 to block on T1 will fail, and T2 will execute this lambda on
            // T2 itself.
            thread1->DoInThreadSynchronously(
                    [](MessageLoopThread* thread2, int* value) {
                      // Just check whether this is executed first (value == 1), && this is executed
                      // on T2.
                      log::assert_that(thread2->IsRunningOnSameThread(),
                                       "assert failed: task should be executed on {} thread",
                                       thread2->GetName());
                      if (*value == 1) {
                        *value = 2;
                      }
                    },
                    std::move(thread2), std::move(value));
          },
          &message_loop_thread1, &message_loop_thread2, &value, std::move(promise_t2_blocked)));

  // Just waiting for T2 to start executing above lambda, so that we can proceed to block T1 on T2.
  future_t2_blocked.wait();

  // Test thread will block on T1, and T1 will block on T2.
  // Both of these lambdas below will be posted successfully on T1 and T2 respectively.
  message_loop_thread1.DoInThreadSynchronously(
          [](MessageLoopThread* thread2, int* value) {
            // Remember, that above lambda is waiting on "future_t2_blocked", but after that it
            // sleeps for 10ms. In that time, we instantly create a dependency from T1 to T2, so now
            // when the call in previous lambda "thread1->DoInThreadSynchronously" is called, it
            // will fail, and execute the lambda on T2 itself there.
            thread2->DoInThreadSynchronously(
                    [](int* value) {
                      // This sleep is just to make sure that T1 -> T2 dependency does not vanish
                      // before "thread1->DoInThreadSynchronously()" call starts.
                      std::this_thread::sleep_for(std::chrono::milliseconds(10));

                      // If *value == 1, it means the test failed, this internal lambda should not
                      // be executed first.
                      if (*value == 1) {
                        *value = 3;  // this will fail the test, if executed.
                      }
                    },
                    std::move(value));
          },
          &message_loop_thread2, &value);

  // We will reach here only when above "message_loop_thread1.DoInThreadSynchronously" call
  // succeeds. Then, if value == 2, it means our deadlock detection works.
  ASSERT_EQ(value, 2);

  message_loop_thread1.ShutDown();
  message_loop_thread2.ShutDown();
}

// Verifies that the linux thread ids are extracted correctly in all scenarios as complete
// DoInThreadSynchronously logic depends on it.
TEST_F(MessageLoopThreadTest, test_linux_tid_success) {
  std::string name1 = "test_thread1";
  MessageLoopThread message_loop_thread1(name1);

  message_loop_thread1.StartUp();

  std::promise<pid_t> tid_promise;
  std::future<pid_t> tid_future = tid_promise.get_future();
  message_loop_thread1.DoInThread(base::BindOnce(
          [](std::promise<pid_t> tid_promise) {
            tid_promise.set_value(MessageLoopThread::GetLinuxThreadId());
          },
          std::move(tid_promise)));
  auto thread1_tid = tid_future.get();  // getting thread1 tid as current thread id
  auto thread1_tid_from_t2 = MessageLoopThread::GetLinuxThreadId(
          &message_loop_thread1);  // getting thread1 tid through context argument passed.

  ASSERT_EQ(thread1_tid, thread1_tid_from_t2);
  // Verify that the callback is executed synchronously
  message_loop_thread1.ShutDown();
}

TEST_F(MessageLoopThreadTest, test_do_in_thread_else_return_with_move_only_argument) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  // Note: We are not starting the thread, so DoInThreadElseReturn will return the task.

  std::promise<int> p;
  std::future<int> f = p.get_future();
  int value_set = 42;

  auto task = base::BindOnce(
          [](std::promise<int> promise, int value_set) { promise.set_value(value_set); },
          std::move(p), value_set);

  auto returned_task = message_loop_thread.DoInThreadElseReturn(std::move(task));

  // The task should be returned since the thread is not running
  ASSERT_TRUE(returned_task.has_value());

  // Now, run the returned task and check the future
  std::move(returned_task.value()).Run();
  ASSERT_EQ(f.get(), value_set);
}

TEST_F(MessageLoopThreadTest, test_do_in_thread_delayed_else_return_with_move_only_argument) {
  std::string name = "test_thread";
  MessageLoopThread message_loop_thread(name);
  // Note: We are not starting the thread, so DoInThreadDelayedElseReturn will return the task.

  std::promise<int> p;
  std::future<int> f = p.get_future();
  int value_set = 42;

  auto task = base::BindOnce(
          [](std::promise<int> promise, int value_set) { promise.set_value(value_set); },
          std::move(p), value_set);

  auto returned_task = message_loop_thread.DoInThreadDelayedElseReturn(
          std::move(task), std::chrono::milliseconds(1));

  // The task should be returned since the thread is not running
  ASSERT_TRUE(returned_task.has_value());

  // Now, run the returned task and check the future
  std::move(returned_task.value()).Run();
  ASSERT_EQ(f.get(), value_set);
}
