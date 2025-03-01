/*
 * Copyright 2024 The Android Open Source Project
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

#include <cctype>
#include <chrono>
#include <future>
#include <memory>

#include "common/bind.h"
#include "gtest/gtest.h"
#include "os/alarm.h"

namespace bluetooth::os {

using common::BindOnce;
using std::chrono::milliseconds;
using std::chrono::seconds;

static constexpr seconds kForever = seconds(1);
static constexpr milliseconds kShortWait = milliseconds(10);

class AlarmOnTimerFdTest : public ::testing::TestWithParam<bool> {
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
  }

  std::shared_ptr<Alarm> get_new_alarm() { return std::make_shared<Alarm>(handler_, GetParam()); }

  std::shared_ptr<Alarm> alarm_;

private:
  Handler* handler_;
  Thread* thread_;
};

TEST_P(AlarmOnTimerFdTest, cancel_while_not_armed) { alarm_->Cancel(); }

TEST_P(AlarmOnTimerFdTest, schedule) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise)), kShortWait);
  ASSERT_EQ(std::future_status::ready, future.wait_for(kForever));
}

TEST_P(AlarmOnTimerFdTest, cancel_alarm) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce([]() { FAIL(); }), kForever);
  alarm_->Cancel();
  ASSERT_NE(std::future_status::ready, future.wait_for(kShortWait));
}

TEST_P(AlarmOnTimerFdTest, cancel_alarm_from_callback) {
  auto promise = std::promise<void>();
  auto future = promise.get_future();
  alarm_->Schedule(BindOnce(
                           [](std::shared_ptr<Alarm> alarm, std::promise<void> promise) {
                             alarm->Cancel();
                             alarm.reset();  // Allow alarm to be freed by Teardown
                             promise.set_value();
                           },
                           alarm_, std::move(promise)),
                   kShortWait);
  ASSERT_EQ(std::future_status::ready, future.wait_for(kForever));
}

TEST_P(AlarmOnTimerFdTest, schedule_while_alarm_armed) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce([]() { FAIL(); }), kForever);
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise)), kShortWait);
  ASSERT_EQ(std::future_status::ready, future.wait_for(kForever));
}

TEST_P(AlarmOnTimerFdTest, delete_while_alarm_armed) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce([]() { FAIL(); }), kForever);
  alarm_.reset();
  ASSERT_NE(std::future_status::ready, future.wait_for(kShortWait));
}

class TwoAlarmOnTimerFdTest : public AlarmOnTimerFdTest {
protected:
  void SetUp() override {
    AlarmOnTimerFdTest::SetUp();
    alarm2 = get_new_alarm();
  }

  void TearDown() override {
    alarm2.reset();
    AlarmOnTimerFdTest::TearDown();
  }

  std::shared_ptr<Alarm> alarm2;
};

TEST_P(TwoAlarmOnTimerFdTest, schedule_from_alarm) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(
          BindOnce(
                  [](std::shared_ptr<Alarm> alarm2, std::unique_ptr<std::promise<void>> promise) {
                    alarm2->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise)),
                                     kShortWait);
                  },
                  alarm2, std::move(promise)),
          kShortWait);
  EXPECT_EQ(std::future_status::ready, future.wait_for(kForever));
}

INSTANTIATE_TEST_SUITE_P(
        /* no label */, AlarmOnTimerFdTest, ::testing::Bool());

INSTANTIATE_TEST_SUITE_P(
        /* no label */, TwoAlarmOnTimerFdTest, ::testing::Bool());

}  // namespace bluetooth::os
