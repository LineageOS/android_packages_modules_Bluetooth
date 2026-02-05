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
#include "watchdog.h"

#include <gtest/gtest-spi.h>
#include <gtest/gtest.h>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace bluetooth {
namespace common {
using namespace std::chrono_literals;
class BluetoothWatchdogTest : public ::testing::Test {
protected:
  static constexpr std::chrono::milliseconds kTestTimeoutMs =
          bluetooth::common::Watchdog::kMinimumTimeoutMs + 50ms;
  std::condition_variable cv;
  std::mutex mtx;
  bool fired = false;
};
class BluetoothWatchdogDeathTest : public BluetoothWatchdogTest {};

TEST_F(BluetoothWatchdogDeathTest, WatchdogShouldFireAndAbortProcess) {
  auto watchdog_deleter = [this](bluetooth::common::Watchdog* ptr) {
    std::lock_guard<std::mutex> lock(mtx);
    fired = true;
    cv.notify_all();
    delete ptr;
  };
  using WatchdogUniquePtr =
          std::unique_ptr<bluetooth::common::Watchdog, decltype(watchdog_deleter)>;
  EXPECT_DEATH(
          {
            fired = false;
            WatchdogUniquePtr wd_ptr(new bluetooth::common::Watchdog(kTestTimeoutMs),
                                     watchdog_deleter);
            std::unique_lock<std::mutex> lock(mtx);
            cv.wait_for(lock, std::chrono::milliseconds(kTestTimeoutMs) + 20ms,
                        [&] { return fired; });
            // never gets here as watchdog fired expected.
          },
          "");
}

TEST_F(BluetoothWatchdogTest, WatchdogShouldNotFireIfDeletedInTime) {
  ASSERT_NO_FATAL_FAILURE({
    std::unique_ptr<bluetooth::common::Watchdog> wd;
    wd = std::make_unique<bluetooth::common::Watchdog>(kTestTimeoutMs);
    wd.reset();
  });
}

TEST_F(BluetoothWatchdogDeathTest, WatchdogWithZeroAsTimeout) {
  EXPECT_DEATH({ bluetooth::common::Watchdog wd(0ms); }, "");
}

TEST_F(BluetoothWatchdogDeathTest, WatchdogWithInvalidTimeout) {
  EXPECT_DEATH({ bluetooth::common::Watchdog wd(-1ms); }, "");
}

TEST_F(BluetoothWatchdogDeathTest, WatchdogWithMinimumTimeout) {
  ASSERT_NO_FATAL_FAILURE({
    std::unique_ptr<bluetooth::common::Watchdog> wd;
    wd = std::make_unique<bluetooth::common::Watchdog>(
            bluetooth::common::Watchdog::kMinimumTimeoutMs);
    wd.reset();
  });
}

}  // namespace common
}  // namespace bluetooth
