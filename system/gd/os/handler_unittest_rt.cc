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

#include <chrono>
#include <future>

#include "bluetooth/log.h"
#include "common/bind.h"
#include "gtest/gtest.h"
#include "os/handler.h"

/**
 * This tests the handler with a real timer.
 */
namespace bluetooth {
namespace os {
namespace {
class HandlerDelayTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
  }
  void TearDown() override {
    handler_->Clear();
    delete handler_;
    delete thread_;
  }

  Thread* thread_;
  Handler* handler_;
};

constexpr std::chrono::milliseconds kAllowedJitter(10);  // 10ms of jitter
constexpr int kTaskIterationCount = 50;
using TimePoint = os::boottime_clock::time_point;
#define DURATION_TO_MS(duration) std::chrono::duration_cast<std::chrono::milliseconds>(duration)

/**
 * Test that the task is executed within the expected time window.
 * Task posted with the delay of 100ms, and the expected time window is 100ms + 5ms of jitter.
 */
TEST_F(HandlerDelayTest, post_task_with_delay) {
  TimePoint start_time = os::boottime_clock::now();

  std::chrono::milliseconds delay(100);  // 100ms delay
  std::promise<void> closure_ran;
  auto future = closure_ran.get_future();

  log::info("posting task with delay");
  handler_->PostWithDelay(common::BindOnce([](std::promise<void> promise) { promise.set_value(); },
                                           std::move(closure_ran)),
                          delay);

  ASSERT_EQ(future.wait_for(delay + kAllowedJitter), std::future_status::ready)
          << "Task did not execute within the expected time window, time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time)
          << " ms, actual delay: " << delay.count();
}

/**
 * Test that a batch of tasks are executed within the same time window.
 * Task posted with the same delay in a batch.
 * Expectation: The total task execution time is equal to the delay of the single
 * task (+jitter) as all the tasks are having same delay, so all should complete within the same
 * time window.
 */
TEST_F(HandlerDelayTest, post_task_with_same_delay_in_batch) {
  TimePoint start_time = os::boottime_clock::now();
  std::chrono::milliseconds delay(100);  // 100ms delay
  std::promise<void> closure_ran;
  auto future = closure_ran.get_future();
  int counter = 0;
  log::info("posting task with delay in batch");
  for (int i = 0; i < kTaskIterationCount; i++) {
    handler_->PostWithDelay(common::BindOnce(
                                    [](std::promise<void>* promise, int* counter) {
                                      if (++(*counter) == kTaskIterationCount) {
                                        (*promise).set_value();
                                      }
                                    },
                                    common::Unretained(&closure_ran), common::Unretained(&counter)),
                            delay);
  }

  ASSERT_EQ(future.wait_for(delay + kAllowedJitter), std::future_status::ready)
          << "All tasks did not execute within the expected time window (only " << counter
          << " tasks executed), time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time)
          << " ms, actual delay: " << delay.count();
}

/**
 * Tests that the batch of tasks are executed within the maximum delay of the tasks in the batch.
 * The tasks are posted with different delay (increasing) in a batch. We are validating 3 cases:
 * 1. All the tasks are finished within the maximum delay of the tasks (last task having maximum
 * delay).
 * 2. The tasks are executed in the expected order, first task executed first, last task executed
 * last, in the increasing order of delay.
 * 3. Every task is executed within their expected delay (+jitter allowed).
 */
TEST_F(HandlerDelayTest, post_task_with_different_delay_in_increasing_order) {
  TimePoint start_time = os::boottime_clock::now();
  std::chrono::milliseconds delay(100), delay_sum(100), deltaInDelays(10);  // 100ms delay
  std::promise<void> closure_ran;
  auto future = closure_ran.get_future();
  int counter = 0;  // total of 50 tasks, 100+50*10 = 600ms delay in total
  TimePoint
          tasks_completed_time[kTaskIterationCount];  // store the time when each task is completed

  log::info("posting task with delay in increasing order");
  for (int i = 0; i < kTaskIterationCount; i++) {
    handler_->PostWithDelay(common::BindOnce(
                                    [](std::promise<void>* promise, int* counter,
                                       TimePoint* tasks_completed_time, int task_index) {
                                      tasks_completed_time[task_index] = os::boottime_clock::now();
                                      if (++(*counter) == kTaskIterationCount) {
                                        (*promise).set_value();
                                      }
                                    },
                                    common::Unretained(&closure_ran), common::Unretained(&counter),
                                    common::Unretained(tasks_completed_time), i),
                            delay_sum);
    delay_sum += deltaInDelays;
  }

  ASSERT_EQ(future.wait_for(delay_sum + 3 * kAllowedJitter), std::future_status::ready)
          << "Task did not execute within the expected time window, time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time)
          << " ms, actual delay: " << delay_sum.count();

  // Check that the tasks are completed in the expected order
  for (int i = 0; i < kTaskIterationCount; i++) {
    std::chrono::milliseconds actual_delay_in_ms =
            DURATION_TO_MS(tasks_completed_time[i] - start_time);
    std::chrono::milliseconds expected_delay_in_ms(delay.count() + (deltaInDelays.count() * i));

    if (i > 0) {
      ASSERT_LT(tasks_completed_time[i - 1], tasks_completed_time[i])
              << "Task " << i
              << " completed at: " << DURATION_TO_MS(tasks_completed_time[i].time_since_epoch())
              << " ms, before task " << i - 1
              << " completed at: " << DURATION_TO_MS(tasks_completed_time[i - 1].time_since_epoch())
              << " ms";
    }

    ASSERT_NEAR(actual_delay_in_ms.count(), expected_delay_in_ms.count(), kAllowedJitter.count())
            << "Task " << i << ", started at: " << DURATION_TO_MS(start_time.time_since_epoch())
            << ", executed at " << DURATION_TO_MS(tasks_completed_time[i].time_since_epoch())
            << " ms, with actual delay: " << actual_delay_in_ms
            << "ms, while the expected delay was: " << expected_delay_in_ms;
  }
}

/**
 * Tests that the batch of tasks are executed within the maximum delay of the tasks in the batch.
 * The tasks are posted with different delay (decreasing) in a batch. We are validating 3 cases:
 * 1. All the tasks are finished within the maximum delay of the tasks (last task having maximum
 * delay).
 * 2. The tasks are executed in the expected order, first task executed first, last task executed
 * last, in the increasing order of delay.
 * 3. Every task is executed within their expected delay (+jitter allowed).
 */
TEST_F(HandlerDelayTest, post_task_with_different_delay_in_decreasing_order) {
  TimePoint start_time = os::boottime_clock::now();
  std::chrono::milliseconds delay(600), delay_sum(600), deltaInDelays(10);  // 600ms delay
  std::promise<void> closure_ran;
  auto future = closure_ran.get_future();
  int counter = 0;
  TimePoint
          tasks_completed_time[kTaskIterationCount];  // store the time when each task is completed

  log::info("posting task with delay in decreasing order");
  for (int i = 0; i < kTaskIterationCount; i++) {
    handler_->PostWithDelay(common::BindOnce(
                                    [](std::promise<void>* promise, int* counter,
                                       TimePoint* tasks_completed_time, int task_index) {
                                      tasks_completed_time[task_index] = os::boottime_clock::now();
                                      if (++(*counter) == kTaskIterationCount) {
                                        (*promise).set_value();
                                      }
                                    },
                                    common::Unretained(&closure_ran), common::Unretained(&counter),
                                    common::Unretained(tasks_completed_time), i),
                            delay_sum);
    delay_sum -= deltaInDelays;
  }

  ASSERT_EQ(future.wait_for(delay + kAllowedJitter), std::future_status::ready)
          << "Task did not execute within the expected time window, time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time)
          << ", actual delay: " << delay.count();

  // Check that the tasks are completed in the expected order
  for (int i = 0; i < kTaskIterationCount; i++) {
    std::chrono::milliseconds actual_delay_in_ms =
            DURATION_TO_MS(tasks_completed_time[i] - start_time);
    std::chrono::milliseconds expected_delay_in_ms(delay.count() - (deltaInDelays.count() * i));

    if (i > 0) {
      ASSERT_GT(tasks_completed_time[i - 1], tasks_completed_time[i])
              << "Task " << i
              << " completed at: " << DURATION_TO_MS(tasks_completed_time[i].time_since_epoch())
              << " ms after task " << i - 1
              << " completed at: " << DURATION_TO_MS(tasks_completed_time[i - 1].time_since_epoch())
              << " ms";
    }

    ASSERT_NEAR(actual_delay_in_ms.count(), expected_delay_in_ms.count(), kAllowedJitter.count())
            << "Task " << i << ", started at: " << DURATION_TO_MS(start_time.time_since_epoch())
            << ", executed at " << DURATION_TO_MS(tasks_completed_time[i].time_since_epoch())
            << " ms, with actual delay: " << actual_delay_in_ms
            << "ms, while the expected delay was: " << expected_delay_in_ms;
  }
}

/**
 * Tests that the 2 task posted with different delay are executed within the expected time window.
 * Task posted with the delay of 100ms, let it complete, then a task of 200ms delay is posted, and
 * both should be executed within their respective expected time window (+jitter allowed)
 */
TEST_F(HandlerDelayTest, post_task_with_promise) {
  TimePoint start_time = os::boottime_clock::now();
  std::promise<void> promise;
  auto future = promise.get_future();
  std::chrono::milliseconds delay(100);  // 100ms delay
  log::info("posting task with promise");
  handler_->PostWithDelay(
          common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)), delay);
  ASSERT_EQ(future.wait_for(delay + kAllowedJitter), std::future_status::ready)
          << "Task did not execute within the expected time window, time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time)
          << ", actual delay: " << delay.count();

  TimePoint start_time2 = os::boottime_clock::now();
  std::promise<void> promise2;
  auto future2 = promise2.get_future();
  std::chrono::milliseconds delay2(200);  // 200ms delay

  handler_->PostWithDelay(
          common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise2)), delay2);
  ASSERT_EQ(future2.wait_for(delay2 + kAllowedJitter), std::future_status::ready)
          << "Task did not execute within the expected time window, time taken: "
          << DURATION_TO_MS(os::boottime_clock::now() - start_time2)
          << ", actual delay: " << delay2.count();
}

}  // namespace
}  // namespace os
}  // namespace bluetooth
