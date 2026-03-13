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

#include <base/functional/callback.h>

#include <future>
#include <thread>

#include "common/bind.h"
#include "gtest/gtest.h"

namespace bluetooth {
namespace os {
namespace {

class HandlerTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
  }
  void TearDown() override {
    delete handler_;
    delete thread_;
  }

  Handler* handler_;
  Thread* thread_;
};

TEST_F(HandlerTest, empty) { handler_->Clear(); }

TEST_F(HandlerTest, post_task_invoked) {
  int val = 0;
  std::promise<void> closure_ran;
  auto future = closure_ran.get_future();
  base::OnceClosure closure = common::BindOnce(
          [](int* val, std::promise<void> closure_ran) {
            *val = *val + 1;
            closure_ran.set_value();
          },
          common::Unretained(&val), std::move(closure_ran));
  handler_->Post(std::move(closure));
  future.wait();
  ASSERT_EQ(val, 1);
  handler_->Clear();
}

TEST_F(HandlerTest, post_task_cleared) {
  int val = 0;
  std::promise<void> closure_started;
  auto closure_started_future = closure_started.get_future();
  std::promise<void> closure_can_continue;
  auto can_continue_future = closure_can_continue.get_future();
  std::promise<void> closure_finished;
  auto closure_finished_future = closure_finished.get_future();
  handler_->Post(common::BindOnce(
          [](int* val, std::promise<void> closure_started, std::future<void> can_continue_future,
             std::promise<void> closure_finished) {
            closure_started.set_value();
            *val = *val + 1;
            can_continue_future.wait();
            closure_finished.set_value();
          },
          common::Unretained(&val), std::move(closure_started), std::move(can_continue_future),
          std::move(closure_finished)));
  handler_->Post(common::BindOnce([]() { FAIL(); }));
  closure_started_future.wait();
  handler_->Clear();
  closure_can_continue.set_value();
  closure_finished_future.wait();
  ASSERT_EQ(val, 1);
  handler_->WaitUntilStopped(std::chrono::milliseconds(10));
}

void check_int(std::unique_ptr<int> number, std::shared_ptr<int> to_change) {
  *to_change = *number;
}

TEST_F(HandlerTest, once_callback) {
  auto number = std::make_unique<int>(1);
  auto to_change = std::make_shared<int>(0);
  auto once_callback = common::BindOnce(&check_int, std::move(number), to_change);
  std::move(once_callback).Run();
  EXPECT_EQ(*to_change, 1);
  handler_->Clear();
}

TEST_F(HandlerTest, callback_with_promise) {
  std::promise<void> promise;
  auto future = promise.get_future();
  auto once_callback =
          common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise));
  std::move(once_callback).Run();
  future.wait();
  handler_->Clear();
}

// Check that the handler is correctly notified when a task is pushed while the thread is active
// handling a different handler loop on the same thread (the same scenario would be reproduced
// by using an Alarm instead).
TEST_F(HandlerTest, regression_489590494) {
  Handler* other_handler = new Handler(thread_);
  std::promise<int> value_promise;
  std::future<int> value_future = value_promise.get_future();

  other_handler->Post(base::BindOnce(
          [](Handler* handler, std::promise<int> value_promise) {
            // Posting on the same thread, but a different handler.
            handler->Post(base::BindOnce(
                    [](std::promise<int> value_promise) { value_promise.set_value(42); },
                    std::move(value_promise)));
          },
          handler_, std::move(value_promise)));

  // The task posted on the original handler should execute within a reasonable delay.
  EXPECT_EQ(value_future.wait_for(std::chrono::milliseconds(10)), std::future_status::ready);

  other_handler->Clear();
  handler_->Clear();
  delete other_handler;
}

// For Death tests, all the threading needs to be done in the ASSERT_DEATH call
class HandlerDeathTest : public ::testing::Test {
protected:
  void ThreadSetUp() {
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
  }

  void ThreadTearDown() {
    delete handler_;
    delete thread_;
  }

  void ClearTwice() {
    ThreadSetUp();
    handler_->Clear();
    handler_->Clear();
    ThreadTearDown();
  }

  void NotCleared() {
    ThreadSetUp();
    ThreadTearDown();
  }

  Handler* handler_;
  Thread* thread_;
};

TEST_F(HandlerDeathTest, clear_after_handler_cleared) {
  ASSERT_DEATH(ClearTwice(), "Handlers must only be cleared once");
}

TEST_F(HandlerDeathTest, not_cleared_before_destruction) {
  ASSERT_DEATH(NotCleared(), "Handlers must be cleared");
}

}  // namespace
}  // namespace os
}  // namespace bluetooth
