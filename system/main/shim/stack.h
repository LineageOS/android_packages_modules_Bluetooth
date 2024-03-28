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

#include <functional>
#include <mutex>

#include "module.h"
#include "os/handler.h"
#include "os/thread.h"
#include "stack_manager.h"

// The shim layer implementation on the Gd stack side.
namespace bluetooth {
namespace shim {

class Btm;

namespace legacy {
class Acl;
};  // namespace legacy

// GD shim stack, having modes corresponding to legacy stack
class Stack {
 public:
  static Stack* GetInstance();

  Stack();
  Stack(const Stack&) = delete;
  Stack& operator=(const Stack&) = delete;

  ~Stack() = default;

  // Running mode, everything is up
  void StartEverything();

  void Stop();
  bool IsRunning();
  bool IsDumpsysModuleStarted() const;

  StackManager* GetStackManager();
  const StackManager* GetStackManager() const;

  legacy::Acl* GetAcl();

  Btm* GetBtm();
  os::Handler* GetHandler();

  bool LockForDumpsys(std::function<void()> dumpsys_callback);

  // Start the list of modules with the given stack manager thread
  void StartModuleStack(const ModuleList* modules, const os::Thread* thread);

  // Run the callable object on the module instance
  template <typename T>
  bool CallOnModule(std::function<void(T* mod)> run) {
    std::lock_guard<std::recursive_mutex> lock(Stack::GetInstance()->mutex_);
    if (Stack::GetInstance()->is_running_) {
      run(Stack::GetInstance()->GetStackManager()->GetInstance<T>());
      return true;
    }
    return false;
  }

  size_t NumModules() const { return num_modules_; }

 private:
  struct impl;
  std::shared_ptr<impl> pimpl_;

  mutable std::recursive_mutex mutex_;
  StackManager stack_manager_;
  bool is_running_ = false;
  os::Thread* stack_thread_ = nullptr;
  os::Handler* stack_handler_ = nullptr;
  size_t num_modules_{0};
  void Start(ModuleList* modules);
};

}  // namespace shim
}  // namespace bluetooth
