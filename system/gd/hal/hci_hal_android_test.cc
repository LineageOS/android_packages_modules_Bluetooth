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

#include <gtest/gtest.h>

#include <chrono>
#include <queue>
#include <thread>

#include "hal/hci_backend.h"
#include "hal/hci_hal.h"
#include "os/thread.h"

using ::bluetooth::os::Thread;

namespace bluetooth::hal {

class TestBackend : public HciBackend {
public:
  static std::chrono::milliseconds initialization_delay;

  std::shared_ptr<HciBackendCallbacks> callbacks;
  struct {
    std::queue<std::vector<uint8_t>> cmd, acl, sco, iso;
  } queues;

  void initialize(std::shared_ptr<HciBackendCallbacks> callbacks) override {
    this->callbacks = callbacks;
    std::thread(
            [callbacks](std::chrono::milliseconds delay) {
              std::this_thread::sleep_for(delay);
              callbacks->initializationComplete();
            },
            TestBackend::initialization_delay)
            .detach();
  }

  void sendHciCommand(const std::vector<uint8_t>& command) override { queues.cmd.push(command); }
  void sendAclData(const std::vector<uint8_t>& packet) override { queues.acl.push(packet); }
  void sendScoData(const std::vector<uint8_t>& packet) override { queues.sco.push(packet); }
  void sendIsoData(const std::vector<uint8_t>& packet) override { queues.iso.push(packet); }
};

std::shared_ptr<TestBackend> backend;
std::chrono::milliseconds TestBackend::initialization_delay = std::chrono::milliseconds(0);

std::shared_ptr<HciBackend> HciBackend::CreateAidl() {
  backend = std::make_shared<TestBackend>();
  return backend;
}

std::shared_ptr<HciBackend> HciBackend::CreateHidl(
        [[maybe_unused]] ::bluetooth::os::Handler* handler) {
  backend = std::make_shared<TestBackend>();
  return backend;
}

namespace {

class HciHalAndroidTest : public ::testing::Test {
protected:
  void SetUp() override {
    thread_ = new Thread("test_thread", Thread::Priority::NORMAL);
    handler_ = new os::Handler(thread_);
    hal = fake_registry_.Start<HciHal>(thread_, handler_);
  }

  void TearDown() override {
    fake_registry_.StopAll();
    handler_->Clear();
    delete thread_;
    delete handler_;
  }

  HciHal* hal;

private:
  ModuleRegistry fake_registry_;
  Thread* thread_;
  os::Handler* handler_;
};

TEST_F(HciHalAndroidTest, init) {
  TearDown();

  TestBackend::initialization_delay = std::chrono::milliseconds(100);
  const auto t0 = std::chrono::steady_clock::now();
  SetUp();
  const auto t1 = std::chrono::steady_clock::now();

  EXPECT_GE(t1 - t0, TestBackend::initialization_delay);
  TestBackend::initialization_delay = std::chrono::milliseconds(0);
}

}  // namespace
}  // namespace bluetooth::hal
