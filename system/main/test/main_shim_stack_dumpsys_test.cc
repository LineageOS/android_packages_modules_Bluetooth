/*
 *  Copyright 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <unistd.h>

#include <cstdlib>
#include <memory>

#include "common/circular_buffer.h"
#include "common/strings.h"
#include "gd/module_jniloop.h"
#include "gd/module_mainloop.h"
#include "hci/include/packet_fragmenter.h"
#include "main/shim/dumpsys.h"
#include "main/shim/entry.h"
#include "main/shim/stack.h"
#include "module.h"
#include "os/thread.h"
#include "shim/dumpsys.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec_cb.h"
#include "stack/include/main_thread.h"
#include "test/mock/mock_main_shim_entry.h"

using ::testing::_;

using namespace bluetooth;
using namespace testing;

tBTM_CB btm_cb{};          // main::shim::le_scanning_manager
tBTM_SEC_CB btm_sec_cb{};  // main::shim::acl

const packet_fragmenter_t* packet_fragmenter_get_interface() {
  return nullptr;
}  // main::shim::hci_layer
bluetooth::common::TimestamperInMilliseconds
    timestamper_in_milliseconds;  // main::shim::le_scanning_manager

namespace {
constexpr char kLogTagStopped[] = "STOPPED";
constexpr char kLogTagStarting[] = "STARTING";
constexpr char kLogTagStarted[] = "STARTED";
constexpr char kLogTagQuiescing[] = "QUIESCING";
constexpr char kLogTagQuiesced[] = "QUIESCED";

constexpr char kTestStackThreadName[] = "test_stack_thread";
constexpr int kSyncMainLoopTimeoutMs = 3000;
constexpr int kWaitUntilHandlerStoppedMs = 2000;
constexpr size_t kNumTestClients = 10;

constexpr size_t kTagLength = 48 + sizeof(' ') + sizeof(' ');
inline void log_tag(std::string tag) {
  std::string prepend(kTagLength / 2 - tag.size() / 2, '=');
  std::string append(kTagLength / 2 - tag.size() / 2, '=');
  log::info("{} {} {}", prepend, tag, append);
}

class MainThread {
 public:
  MainThread() {
    main_thread_start_up();
    post_on_bt_main([]() { log::info("<=== tid Main loop started"); });
  }

  ~MainThread() {
    sync_main_handler();
    main_thread_shut_down();
  }

 private:
  void sync_main_handler() {
    std::promise promise = std::promise<void>();
    std::future future = promise.get_future();
    post_on_bt_main([&promise]() { promise.set_value(); });
    future.wait_for(std::chrono::milliseconds(kSyncMainLoopTimeoutMs));
  }
};

class TestStackManager {
 public:
  TestStackManager() {
    // Stack manager is started in the test after each test uses the default
    // or adds their own modules
  }

  ~TestStackManager() {
    log::debug("Deleting stack manager");
    Stop();
  }

  TestStackManager(const TestStackManager&) = delete;

  template <typename T>
  void AddModule() {
    modules_.add<T>();
  }

  void Start() {
    if (stack_started_) return;
    log::info("Starting up stack manager");
    stack_started_ = true;
    bluetooth::os::Thread* stack_thread = new bluetooth::os::Thread(
        kTestStackThreadName, bluetooth::os::Thread::Priority::NORMAL);
    bluetooth::shim::Stack::GetInstance()->StartModuleStack(&modules_,
                                                            stack_thread);
    bluetooth::shim::Stack::GetInstance()->GetHandler()->Call(
        []() { log::info("<=== tid GD Event loop started"); });
  }

  void Stop() {
    if (!stack_started_) return;
    stack_started_ = false;
    bluetooth::shim::Stack::GetInstance()->Stop();
  }

  // NOTE: Stack manager *must* be active else method returns nullptr
  // if stack manager has not started or shutdown
  template <typename T>
  static T* GetUnsafeModule() {
    return bluetooth::shim::Stack::GetInstance()
        ->GetStackManager()
        ->GetInstance<T>();
  }

  size_t NumModules() const { return modules_.NumModules(); }

 private:
  bluetooth::ModuleList modules_;
  bool stack_started_{false};
};

// Data returned via callback from a stack managed module
struct TestCallbackData {
  int iter;
  std::string tag;
};

// Data sent to a stack managed module via a module API
struct TestData {
  int iter;
  std::string tag;
  std::function<void(TestCallbackData callback_data)> callback;
};

}  // namespace

class TestStackDumpsysBase : public bluetooth::Module,
                             public ModuleMainloop,
                             public ModuleJniloop {
 public:
  TestStackDumpsysBase(const TestStackDumpsysBase&) = delete;
  TestStackDumpsysBase& operator=(const TestStackDumpsysBase&) = delete;

  virtual ~TestStackDumpsysBase(){};
  static const ModuleFactory Factory;

  virtual void TestMethod(TestData test_data) const {
    log::info("Test base class iter:{} tag:{}", test_data.iter, test_data.tag);
  }

 protected:
  void ListDependencies(ModuleList* /* list */) const override{};
  void Start() override { log::error("Started TestStackDumpsysBase"); };
  void Stop() override { log::error("Stopped TestStackDumpsysBase"); };
  std::string ToString() const override { return std::string("TestFunction"); }

  TestStackDumpsysBase() = default;
};

struct StackRunningData {
  std::function<void(bool is_running)> cb;
};

class TestStackDumpsys1 : public TestStackDumpsysBase {
 public:
  TestStackDumpsys1(const TestStackDumpsys1&) = delete;
  TestStackDumpsys1& operator=(const TestStackDumpsys1&) = delete;
  virtual ~TestStackDumpsys1() = default;

  static const ModuleFactory Factory;

  void TestMethod(TestData test_data) const override;
  void IsStackRunning(StackRunningData stack_running_data) const;

 private:
  struct impl;
  std::shared_ptr<impl> impl_;
  TestStackDumpsys1();
};

struct TestStackDumpsys1::impl : public ModuleMainloop, public ModuleJniloop {
  void test(TestData test_data) {
    TestCallbackData callback_data{
        .iter = test_data.iter,
        .tag = std::string(__func__),
    };
    PostFunctionOnMain(
        [](std::function<void(TestCallbackData callback_data)> callback,
           TestCallbackData data) { callback(data); },
        test_data.callback, callback_data);
  }
  void is_stack_running(StackRunningData stack_running_data) const {
    bool is_running = bluetooth::shim::Stack::GetInstance()->IsRunning();
    if (stack_running_data.cb) {
      stack_running_data.cb(is_running);
    }
  }
};

TestStackDumpsys1::TestStackDumpsys1() : TestStackDumpsysBase() {
  impl_ = std::make_shared<impl>();
}

void TestStackDumpsys1::TestMethod(TestData test_data) const {
  PostMethodOnMain(impl_, &impl::test, test_data);
}

void TestStackDumpsys1::IsStackRunning(
    StackRunningData stack_running_data) const {
  GetHandler()->CallOn(impl_.get(), &impl::is_stack_running,
                       stack_running_data);
}

class TestStackDumpsys2 : public TestStackDumpsysBase {
 public:
  TestStackDumpsys2(const TestStackDumpsys2&) = delete;
  TestStackDumpsys2& operator=(const TestStackDumpsys2&) = delete;
  virtual ~TestStackDumpsys2() = default;

  static const ModuleFactory Factory;

  void TestMethod(TestData test_data) const override;

 private:
  struct impl;
  std::shared_ptr<impl> impl_;
  TestStackDumpsys2();
};

struct TestStackDumpsys2::impl : public ModuleMainloop, public ModuleJniloop {
  void test(TestData test_data) {
    TestCallbackData callback_data{
        .iter = test_data.iter,
        .tag = std::string(__func__),
    };
    PostFunctionOnMain(
        [](std::function<void(TestCallbackData callback_data)> callback,
           TestCallbackData data) { callback(data); },
        test_data.callback, callback_data);
  }
};

TestStackDumpsys2::TestStackDumpsys2() : TestStackDumpsysBase() {
  impl_ = std::make_shared<impl>();
}

void TestStackDumpsys2::TestMethod(TestData test_data) const {
  PostMethodOnMain(impl_, &impl::test, test_data);
}

class TestStackDumpsys3 : public TestStackDumpsysBase {
 public:
  TestStackDumpsys3(const TestStackDumpsys3&) = delete;
  TestStackDumpsys3& operator=(const TestStackDumpsys3&) = delete;
  virtual ~TestStackDumpsys3() = default;

  static const ModuleFactory Factory;

  void TestMethod(TestData test_data) const override;

 private:
  struct impl;
  std::shared_ptr<impl> impl_;
  TestStackDumpsys3();
};

struct TestStackDumpsys3::impl : public ModuleMainloop, public ModuleJniloop {
  void test(TestData test_data) {
    TestCallbackData callback_data{
        .iter = test_data.iter,
        .tag = std::string(__func__),
    };
    PostFunctionOnMain(
        [](std::function<void(TestCallbackData callback_data)> callback,
           TestCallbackData data) { callback(data); },
        test_data.callback, callback_data);
  }
};

TestStackDumpsys3::TestStackDumpsys3() : TestStackDumpsysBase() {
  impl_ = std::make_shared<impl>();
}

void TestStackDumpsys3::TestMethod(TestData test_data) const {
  PostMethodOnMain(impl_, &impl::test, test_data);
}

class TestStackDumpsys4 : public TestStackDumpsysBase {
 public:
  TestStackDumpsys4(const TestStackDumpsys4&) = delete;
  TestStackDumpsys4& operator=(const TestStackDumpsys3&) = delete;
  virtual ~TestStackDumpsys4() = default;

  static const ModuleFactory Factory;

  void TestMethod(TestData test_data) const override {
    log::info("mod:{} iter:{} tag:{}", __func__, test_data.iter, test_data.tag);
  }

 private:
  struct impl;
  std::shared_ptr<impl> impl_;
  TestStackDumpsys4() : TestStackDumpsysBase() {}
};

struct TestStackDumpsys4::impl : public ModuleMainloop, public ModuleJniloop {};

const ModuleFactory TestStackDumpsysBase::Factory =
    ModuleFactory([]() { return new TestStackDumpsysBase(); });

const ModuleFactory TestStackDumpsys1::Factory =
    ModuleFactory([]() { return new TestStackDumpsys1(); });
const ModuleFactory TestStackDumpsys2::Factory =
    ModuleFactory([]() { return new TestStackDumpsys2(); });
const ModuleFactory TestStackDumpsys3::Factory =
    ModuleFactory([]() { return new TestStackDumpsys3(); });
const ModuleFactory TestStackDumpsys4::Factory =
    ModuleFactory([]() { return new TestStackDumpsys4(); });

class StackWithMainThreadUnitTest : public ::testing::Test {
 protected:
  void SetUp() override { main_thread_ = std::make_unique<MainThread>(); }
  void TearDown() override { main_thread_.reset(); }

 private:
  std::unique_ptr<MainThread> main_thread_;
};

class StackLifecycleUnitTest : public StackWithMainThreadUnitTest {
 public:
  std::shared_ptr<TestStackManager> StackManager() const {
    return stack_manager_;
  }

 protected:
  void SetUp() override {
    StackWithMainThreadUnitTest::SetUp();
    stack_manager_ = std::make_shared<TestStackManager>();
  }

  void TearDown() override {
    stack_manager_.reset();
    StackWithMainThreadUnitTest::TearDown();
  }

 private:
  std::shared_ptr<TestStackManager> stack_manager_;
};

class MainShimStackDumpsysTest : public StackLifecycleUnitTest {
 protected:
  void SetUp() override {
    StackLifecycleUnitTest::SetUp();
    StackManager()->AddModule<TestStackDumpsys1>();
    StackManager()->AddModule<TestStackDumpsys2>();
    StackManager()->AddModule<TestStackDumpsys3>();
    StackManager()->AddModule<bluetooth::shim::Dumpsys>();
    StackManager()->Start();
    ASSERT_EQ(4U, StackManager()->NumModules());

    bluetooth::shim::RegisterDumpsysFunction((void*)this, [](int fd) {
      log::info("Callback to dump legacy data fd:{}", fd);
    });
  }

  void TearDown() override {
    bluetooth::shim::UnregisterDumpsysFunction((void*)this);
    StackLifecycleUnitTest::TearDown();
  }
};

struct CallablePostCnt {
  size_t success{0};
  size_t misses{0};
  CallablePostCnt operator+=(const CallablePostCnt& post_cnt) {
    return CallablePostCnt(
        {success += post_cnt.success, misses += post_cnt.misses});
  }
};

// Provide a client user of the stack manager module services
class Client {
 public:
  Client(int id) : id_(id) {}
  Client(const Client&) = default;
  virtual ~Client() = default;

  // Start up the client a thread and handler
  void Start() {
    thread_ = new os::Thread(common::StringFormat("ClientThread%d", id_),
                             os::Thread::Priority::NORMAL);
    handler_ = new os::Handler(thread_);
    handler_->Post(common::BindOnce(
        [](int id) { log::info("<=== tid Started client id:{}", id); }, id_));
  }

  // Ensure all the client handlers are running
  void Await() {
    std::promise<void> promise;
    std::future future = promise.get_future();
    handler_->Post(
        base::BindOnce([](std::promise<void> promise) { promise.set_value(); },
                       std::move(promise)));
    future.wait();
  }

  // Post a work task on behalf of this client
  void Post(common::OnceClosure closure) {
    if (quiesced_) {
      post_cnt_.misses++;
    } else {
      post_cnt_.success++;
      handler_->Post(std::move(closure));
    }
  }

  // Safely prevent new work tasks from being posted
  void Quiesce() {
    if (quiesced_) return;
    quiesced_ = true;
    std::promise promise = std::promise<void>();
    std::future future = promise.get_future();
    handler_->Post(common::BindOnce(
        [](std::promise<void> promise, int id) {
          promise.set_value();
          log::info("<=== tid Quiesced client id:{}", id);
        },
        std::move(promise), id_));
    future.wait_for(std::chrono::milliseconds(kSyncMainLoopTimeoutMs));
  }

  // Queisces if needed and stops the client then releases associated resources
  void Stop() {
    if (!quiesced_) {
      Quiesce();
    }
    handler_->Clear();
    handler_->WaitUntilStopped(
        std::chrono::milliseconds(kWaitUntilHandlerStoppedMs));
    delete handler_;
    delete thread_;
  }

  int Id() const { return id_; }

  CallablePostCnt GetCallablePostCnt() const { return post_cnt_; }

  std::string Name() const {
    return common::StringFormat("%s%d", __func__, id_);
  }

 private:
  int id_{0};
  CallablePostCnt post_cnt_{};
  bool quiesced_{false};
  os::Handler* handler_{nullptr};
  os::Thread* thread_{nullptr};
};

// Convenience object to handle multiple clients with logging
class ClientGroup {
 public:
  explicit ClientGroup(size_t num_clients) {
    for (size_t i = 0; i < num_clients; i++) {
      clients_.emplace_back(std::make_unique<Client>(i));
    }
  }

  void Start() {
    log_tag(kLogTagStarting);
    for (auto& c : clients_) {
      c->Start();
    }
  }

  void Await() {
    for (auto& c : clients_) {
      c->Await();
    }
    log_tag(kLogTagStarted);
  }

  void Quiesce() {
    log_tag(kLogTagQuiescing);
    for (auto& c : clients_) {
      c->Quiesce();
    }
    log_tag(kLogTagQuiesced);
  }

  void Stop() {
    for (auto& c : clients_) {
      c->Stop();
    }
    log_tag(kLogTagStopped);
  }

  void Dump() const {
    for (auto& c : clients_) {
      log::info("Callable post cnt client_id:{} success:{} misses:{}", c->Id(),
                c->GetCallablePostCnt().success,
                c->GetCallablePostCnt().misses);
    }
  }

  CallablePostCnt GetCallablePostCnt() const {
    CallablePostCnt post_cnt{};
    for (auto& c : clients_) {
      post_cnt += c->GetCallablePostCnt();
    }
    return post_cnt;
  }

  size_t NumClients() const { return clients_.size(); }

  std::vector<std::unique_ptr<Client>> clients_;
};

class MainShimStackDumpsysWithClientsTest : public MainShimStackDumpsysTest {
 protected:
  void SetUp() override {
    MainShimStackDumpsysTest::SetUp();
    client_group_.Start();
    client_group_.Await();
  }

  void TearDown() override {
    client_group_.Quiesce();
    client_group_.Stop();
    MainShimStackDumpsysTest::TearDown();
  }
  ClientGroup client_group_ = ClientGroup(kNumTestClients);
};

TEST_F(MainShimStackDumpsysWithClientsTest, all_clients_check_stack_running) {
  StackRunningData stack_running_data = {
      .cb =
          [](bool is_stack_running) {
            log::info("Stack is running:{}", (is_stack_running) ? 'T' : 'F');
          },
  };

  // Ensure the dumpsys instance is included within the stack
  ASSERT_NE(nullptr, bluetooth::shim::GetDumpsys());

  for (auto& c : client_group_.clients_) {
    c->Post(base::BindOnce(
        [](StackRunningData stack_running_data) {
          bluetooth::shim::Stack::GetInstance()
              ->GetStackManager()
              ->GetInstance<TestStackDumpsys1>()
              ->IsStackRunning(stack_running_data);
        },
        stack_running_data));
  }
}

TEST_F(MainShimStackDumpsysWithClientsTest,
       all_clients_check_stack_running_with_iterations) {
  StackRunningData stack_running_data = {
      .cb =
          [](bool is_stack_running) {
            log::info("Run on mainloop: Stack is running:{}",
                      (is_stack_running) ? 'T' : 'F');
          },
  };

  // Ensure the dumpsys instance is included within the stack
  ASSERT_NE(nullptr, bluetooth::shim::GetDumpsys());

  for (int i = 0; i < 2; i++) {
    log::info("Iteration:{}", i);
    for (auto& c : client_group_.clients_) {
      c->Post(base::BindOnce(
          [](StackRunningData stack_running_data) {
            bluetooth::shim::Stack::GetInstance()
                ->GetStackManager()
                ->GetInstance<TestStackDumpsys1>()
                ->IsStackRunning(stack_running_data);
          },
          stack_running_data));
    }
  }
}

TEST_F(MainShimStackDumpsysWithClientsTest, dumpsys_single_client) {
  // Ensure the dumpsys instance is included within the stack
  ASSERT_NE(nullptr, bluetooth::shim::GetDumpsys());

  const int fd = 1;
  client_group_.clients_[0]->Post(
      base::BindOnce([](int fd) { bluetooth::shim::Dump(fd, nullptr); }, fd));
}

TEST_F(MainShimStackDumpsysWithClientsTest,
       dumpsys_single_client_with_running_check) {
  StackRunningData stack_running_data = {
      .cb =
          [](bool is_stack_running) {
            log::info("Stack is running:{}", (is_stack_running) ? 'T' : 'F');
          },
  };

  // Ensure the dumpsys instance is included within the stack
  ASSERT_NE(nullptr, bluetooth::shim::GetDumpsys());

  const int fd = 1;
  client_group_.clients_[0]->Post(base::BindOnce(
      [](StackRunningData stack_running_data) {
        bluetooth::shim::Stack::GetInstance()
            ->GetStackManager()
            ->GetInstance<TestStackDumpsys1>()
            ->IsStackRunning(stack_running_data);
      },
      stack_running_data));
  client_group_.clients_[0]->Post(
      base::BindOnce([](int fd) { bluetooth::shim::Dump(fd, nullptr); }, fd));
}

TEST_F(MainShimStackDumpsysWithClientsTest, dumpsys_many_clients) {
  StackRunningData stack_running_data = {
      .cb =
          [](bool is_stack_running) {
            log::info("Stack is running:{}", (is_stack_running) ? 'T' : 'F');
          },
  };

  const int fd = 1;
  for (auto& c : client_group_.clients_) {
    c->Post(
        base::BindOnce([](int fd) { bluetooth::shim::Dump(fd, nullptr); }, fd));
  }
}
