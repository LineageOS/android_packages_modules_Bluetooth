/*
 * Copyright (C) 2026 The Android Open Source Project
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
#include <thread>

#include "base/threading/thread.h"
#include "gtest/gtest.h"
#include "os/asserting_timed_guard.h"

namespace bluetooth {
namespace os {
namespace {

using namespace std::chrono_literals;

class TimedMutexTest : public ::testing::Test {
protected:
  void SetUp() override {}

  void TearDown() override {}
};

TEST_F(TimedMutexTest, lockAndUnlock) {
  TimedMutex mutex;
  mutex.lock();
}

TEST_F(TimedMutexTest, tryLockFor_success) {
  TimedMutex mutex;
  ASSERT_TRUE(mutex.try_lock_for(1ms));
  EXPECT_EQ(mutex.get_owner_thread_tid(), static_cast<int>(syscall(SYS_gettid)));
}

TEST_F(TimedMutexTest, tryLockFor_fail) {
  TimedMutex mutex;
  mutex.lock();
  ASSERT_FALSE(mutex.try_lock_for(1ms));
}

TEST_F(TimedMutexTest, lockGuard) {
  TimedMutex mutex;
  mutex.lock();
  ASSERT_FALSE(mutex.try_lock_for(1ms));

  auto start_time = std::chrono::steady_clock::now();

  // wait for 10ms to let the mutex timeout, and assert that it does.
  {
    ASSERT_DEATH({ AssertingTimedGuard guard(mutex, 10ms); }, "");
  }

  auto end_time = std::chrono::steady_clock::now();
  auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);

  EXPECT_GE(elapsed_ms.count(), 10);  // > 10ms
}

}  // namespace
}  // namespace os
}  // namespace bluetooth
