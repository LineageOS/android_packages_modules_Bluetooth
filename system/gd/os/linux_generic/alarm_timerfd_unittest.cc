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

#include "os/alarm.h"

#include <cctype>
#include <chrono>
#include <future>
#include <memory>

#include "common/bind.h"
#include "gtest/gtest.h"


namespace bluetooth::common {

struct IsSpace {
  bool operator()(std::string::value_type v) {
    return isspace(static_cast<int>(v));
  }
};

std::string StringTrim(std::string str) {
  str.erase(str.begin(), std::find_if_not(str.begin(), str.end(), IsSpace{}));
  str.erase(std::find_if_not(str.rbegin(), str.rend(), IsSpace{}).base(), str.end());
  return str;
}
}  // namespace bluetooth::common

namespace bluetooth::os {

using common::BindOnce;
using std::chrono::milliseconds;
using std::chrono::seconds;

static constexpr seconds kForever = seconds(1);
static constexpr milliseconds kShortWait = milliseconds(10);

class AlarmOnTimerFdTest : public ::testing::Test {
 protected:
  void SetUp() override {
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new Handler(thread_);
    alarm_ = std::make_shared<Alarm>(handler_);
  }

  void TearDown() override {
    alarm_.reset();
    handler_->Clear();
    delete handler_;
    delete thread_;
  }

  std::shared_ptr<Alarm> get_new_alarm() {
    return std::make_shared<Alarm>(handler_);
  }

  std::shared_ptr<Alarm> alarm_;

 private:
  Handler* handler_;
  Thread* thread_;
};

TEST_F(AlarmOnTimerFdTest, cancel_while_not_armed) {
  alarm_->Cancel();
}

TEST_F(AlarmOnTimerFdTest, schedule) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise)), kShortWait);
  ASSERT_EQ(std::future_status::ready, future.wait_for(kForever));
}

TEST_F(AlarmOnTimerFdTest, cancel_alarm) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce([]() { FAIL(); }), kForever);
  alarm_->Cancel();
  ASSERT_NE(std::future_status::ready, future.wait_for(kShortWait));
}

TEST_F(AlarmOnTimerFdTest, cancel_alarm_from_callback) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce(&Alarm::Cancel, alarm_), kShortWait);
  // Could wait for kForever, but no need.  Just let others run twice for a short time.
  ASSERT_NE(std::future_status::ready, future.wait_for(kShortWait));
  ASSERT_NE(std::future_status::ready, future.wait_for(kShortWait));
}

TEST_F(AlarmOnTimerFdTest, schedule_while_alarm_armed) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(BindOnce([]() { FAIL(); }), kForever);
  alarm_->Schedule(BindOnce(&std::promise<void>::set_value, std::move(promise)), kShortWait);
  ASSERT_EQ(std::future_status::ready, future.wait_for(kForever));
}

TEST_F(AlarmOnTimerFdTest, delete_while_alarm_armed) {
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

TEST_F(TwoAlarmOnTimerFdTest, schedule_from_alarm) {
  auto promise = std::make_unique<std::promise<void>>();
  auto future = promise->get_future();
  alarm_->Schedule(
      BindOnce(
          [](std::shared_ptr<Alarm> alarm2, std::unique_ptr<std::promise<void>> promise) {
            alarm2->Schedule(
                BindOnce(&std::promise<void>::set_value, std::move(promise)), kShortWait);
          },
          alarm2,
          std::move(promise)),
      kShortWait);
  EXPECT_EQ(std::future_status::ready, future.wait_for(kForever));
}

}  // namespace bluetooth::os
