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

#include "os/alarm.h"

#include <future>
#include <memory>

#include "common/bind.h"
#include "gtest/gtest.h"
#include "os/fake_timer/fake_timerfd.h"

namespace bluetooth {
namespace os {
namespace {

using common::BindOnce;
using fake_timer::fake_timerfd_advance;
using fake_timer::fake_timerfd_reset;

class AlarmTest : public ::testing::TestWithParam<bool> {
protected:
  void SetUp() override {
    bool isWakeAlarm = GetParam();
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    alarm_ = std::make_shared<Alarm>(handler_, isWakeAlarm);
  }

  void TearDown() override {
    alarm_.reset();
    handler_->Clear();
    delete handler_;
    delete thread_;
    fake_timerfd_reset();
  }

  void fake_timer_advance(uint64_t ms) {
    handler_->Post(common::BindOnce(fake_timerfd_advance, ms));
  }

  std::shared_ptr<Alarm> get_new_alarm() { return std::make_shared<Alarm>(handler_, GetParam()); }

  std::shared_ptr<Alarm> alarm_;

private:
  Handler* handler_;
  Thread* thread_;
};

TEST_P(AlarmTest, cancel_while_not_armed) { alarm_->Cancel(); }

TEST_P(AlarmTest, schedule) {
  std::promise<void> promise;
  auto future = promise.get_future();
  int delay_ms = 10;
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)),
                   std::chrono::milliseconds(delay_ms));
  fake_timer_advance(10);
  future.get();
  ASSERT_FALSE(future.valid());
}

TEST_P(AlarmTest, cancel_alarm) {
  alarm_->Schedule(BindOnce([]() { FAIL() << "Should not happen"; }), std::chrono::milliseconds(3));
  alarm_->Cancel();
  std::this_thread::sleep_for(std::chrono::milliseconds(5));
}

TEST_P(AlarmTest, cancel_alarm_from_callback) {
  std::promise<void> promise;
  auto future = promise.get_future();
  alarm_->Schedule(BindOnce(
                           [](std::shared_ptr<Alarm> alarm, std::promise<void> promise) {
                             alarm->Cancel();
                             alarm.reset();  // Allow alarm to be freed by Teardown
                             promise.set_value();
                           },
                           alarm_, std::move(promise)),
                   std::chrono::milliseconds(1));
  fake_timer_advance(10);
  ASSERT_EQ(std::future_status::ready, future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(alarm_.use_count(), 1);
}

TEST_P(AlarmTest, schedule_while_alarm_armed) {
  alarm_->Schedule(BindOnce([]() { FAIL() << "Should not happen"; }), std::chrono::milliseconds(1));
  std::promise<void> promise;
  auto future = promise.get_future();
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)),
                   std::chrono::milliseconds(10));
  fake_timer_advance(10);
  future.get();
}

TEST_P(AlarmTest, delete_while_alarm_armed) {
  alarm_->Schedule(BindOnce([]() { FAIL() << "Should not happen"; }), std::chrono::milliseconds(1));
  alarm_.reset();
  std::this_thread::sleep_for(std::chrono::milliseconds(10));
}

class TwoAlarmTest : public AlarmTest {
protected:
  void SetUp() override {
    AlarmTest::SetUp();
    alarm2 = get_new_alarm();
  }

  void TearDown() override {
    alarm2.reset();
    AlarmTest::TearDown();
  }

  std::shared_ptr<Alarm> alarm2;
};

TEST_P(TwoAlarmTest, schedule_from_alarm_long) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  auto promise2 = std::make_unique<std::promise<void>>();
  auto future2 = promise2->get_future();
  alarm_->Schedule(
          BindOnce(
                  [](std::shared_ptr<Alarm> alarm2, std::unique_ptr<std::promise<void>> promise,
                     std::unique_ptr<std::promise<void>> promise2) {
                    promise->set_value();
                    alarm2->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise2)),
                                     std::chrono::milliseconds(10));
                  },
                  alarm2, std::move(promise), std::move(promise2)),
          std::chrono::milliseconds(1));
  fake_timer_advance(10);
  EXPECT_EQ(std::future_status::ready, future.wait_for(std::chrono::milliseconds(20)));
  fake_timer_advance(10);
  EXPECT_EQ(std::future_status::ready, future2.wait_for(std::chrono::milliseconds(20)));
}

INSTANTIATE_TEST_SUITE_P(
        /* no label */, AlarmTest, ::testing::Bool());

INSTANTIATE_TEST_SUITE_P(
        /* no label */, TwoAlarmTest, ::testing::Bool());

}  // namespace
}  // namespace os
}  // namespace bluetooth
