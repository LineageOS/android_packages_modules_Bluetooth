/*
 * Copyright 2019 The Android Open Source Project
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

#pragma once

#include <bluetooth/log.h>

#include <chrono>
#include <functional>
#include <future>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "common/bind.h"
#include "os/handler.h"
#include "os/thread.h"

namespace bluetooth {
namespace shim {
class Stack;
}  // namespace shim

class Module;
class ModuleRegistry;
class TestModuleRegistry;
class FuzzTestModuleRegistry;

class ModuleFactory {
  friend ModuleRegistry;
  friend FuzzTestModuleRegistry;

public:
  ModuleFactory(std::function<Module*()> ctor);

private:
  std::function<Module*()> ctor_;
};

class ModuleList {
  friend Module;
  friend ModuleRegistry;

public:
  template <class T>
  void add() {
    list_.push_back(&T::Factory);
  }

  // Return the number of modules in this list
  size_t NumModules() const { return list_.size(); }

private:
  std::vector<const ModuleFactory*> list_;
};

// Each leaf node module must have a factory like so:
//
// static const ModuleFactory Factory;
//
// which will provide a constructor for the module registry to call.
// The module registry will also use the factory as the identifier
// for that module.
class Module {
  friend ModuleRegistry;
  friend TestModuleRegistry;

public:
  virtual ~Module() = default;

protected:
  Module() = default;
  Module(os::Handler* handler) : handler_(handler) {}

  // Populate the provided list with modules that must start before yours
  virtual void ListDependencies(ModuleList* list) const = 0;

  // You can grab your started dependencies during or after this call
  // using GetDependency(), or access the module registry via GetModuleRegistry()
  virtual void Start() = 0;

  // Release all resources, you're about to be deleted
  virtual void Stop() = 0;

  virtual std::string ToString() const = 0;

  ::bluetooth::os::Handler* GetHandler() const;

  const ModuleRegistry* GetModuleRegistry() const;

  template <class T>
  T* GetDependency() const {
    return static_cast<T*>(GetDependency(&T::Factory));
  }

  template <typename Functor, typename... Args>
  void Call(Functor&& functor, Args&&... args) {
    GetHandler()->Call(std::forward<Functor>(functor), std::forward<Args>(args)...);
  }

  template <typename T, typename Functor, typename... Args>
  void CallOn(T* obj, Functor&& functor, Args&&... args) {
    GetHandler()->CallOn(obj, std::forward<Functor>(functor), std::forward<Args>(args)...);
  }

private:
  Module* GetDependency(const ModuleFactory* module) const;

  ::bluetooth::os::Handler* handler_ = nullptr;
  ModuleList dependencies_;
  const ModuleRegistry* registry_ = nullptr;
};

class ModuleRegistry {
  friend Module;
  friend shim::Stack;

public:
  template <class T>
  bool IsStarted() const {
    return IsStarted(&T::Factory);
  }

  bool IsStarted(const ModuleFactory* factory) const;

  // Start all the modules on this list and their dependencies
  // in dependency order
  void Start(ModuleList* modules, ::bluetooth::os::Thread* thread, os::Handler* handler);

  template <class T>
  T* Start(::bluetooth::os::Thread* thread, os::Handler* handler) {
    return static_cast<T*>(Start(&T::Factory, thread, handler));
  }

  Module* Start(const ModuleFactory* id, ::bluetooth::os::Thread* thread, os::Handler* handler);

  // Stop all running modules in reverse order of start
  void StopAll();

protected:
  Module* Get(const ModuleFactory* module) const;

  void set_registry_and_handler(Module* instance, ::bluetooth::os::Thread* thread,
                                os::Handler* handler) const;

  os::Handler* GetModuleHandler(const ModuleFactory* module) const;

  std::map<const ModuleFactory*, Module*> started_modules_;
  std::vector<const ModuleFactory*> start_order_;
  std::string last_instance_;

private:
  mutable std::mutex started_modules_guard_;
};

class TestModuleRegistry : public ModuleRegistry {
public:
  void InjectTestModule(const ModuleFactory* module, Module* instance) {
    start_order_.push_back(module);
    started_modules_[module] = instance;
    set_registry_and_handler(instance, &test_thread, test_handler_);
    instance->Start();
  }

  Module* GetModuleUnderTest(const ModuleFactory* module) const { return Get(module); }

  template <class T>
  T* GetModuleUnderTest() const {
    return static_cast<T*>(GetModuleUnderTest(&T::Factory));
  }

  os::Handler* GetTestModuleHandler(const ModuleFactory* module) const {
    return GetModuleHandler(module);
  }

  os::Thread& GetTestThread() { return test_thread; }
  os::Handler* GetTestHandler() { return test_handler_; }

  bool SynchronizeModuleHandler(const ModuleFactory* module,
                                std::chrono::milliseconds timeout) const {
    return SynchronizeHandler(GetTestModuleHandler(module), timeout);
  }

  bool SynchronizeHandler(os::Handler* handler, std::chrono::milliseconds timeout) const {
    std::promise<void> promise;
    auto future = promise.get_future();
    handler->Post(common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)));
    return future.wait_for(timeout) == std::future_status::ready;
  }

private:
  os::Thread test_thread{"test_thread", os::Thread::Priority::NORMAL};
  os::Handler* test_handler_ = new os::Handler(&test_thread);
};

class FuzzTestModuleRegistry : public TestModuleRegistry {
public:
  template <class T>
  T* Inject(const ModuleFactory* overriding) {
    Module* instance = T::Factory.ctor_();
    InjectTestModule(overriding, instance);
    return static_cast<T*>(instance);
  }

  template <class T>
  T* Start() {
    return ModuleRegistry::Start<T>(&GetTestThread(), GetTestHandler());
  }

  void WaitForIdleAndStopAll() {
    if (!GetTestThread().GetReactor()->WaitForIdle(std::chrono::milliseconds(100))) {
      log::error("idle timed out");
    }
    StopAll();
  }
};

}  // namespace bluetooth
