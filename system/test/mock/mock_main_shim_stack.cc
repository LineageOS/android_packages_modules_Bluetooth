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

#include "hci/acl_manager.h"
#include "main/shim/stack.h"

namespace bluetooth {
namespace shim {

namespace testing {
legacy::Acl* acl_{nullptr};
Btm* btm_{nullptr};
Stack* instance_{nullptr};
}  // namespace testing

Stack* Stack::GetInstance() { return testing::instance_; }

void Stack::StartEverything() {}

void Stack::StartModuleStack(const ModuleList* /* modules */,
                             const os::Thread* /* thread */) {}

void Stack::Start(ModuleList* /* modules */) {}

void Stack::Stop() {}

bool Stack::IsRunning() { return stack_thread_ != nullptr; }

StackManager* Stack::GetStackManager() { return nullptr; }

const StackManager* Stack::GetStackManager() const { return nullptr; }

legacy::Acl* Stack::GetAcl() { return testing::acl_; }

Btm* Stack::GetBtm() { return testing::btm_; }

os::Handler* Stack::GetHandler() { return stack_handler_; }

bool Stack::IsDumpsysModuleStarted() const { return false; }

bool Stack::LockForDumpsys(std::function<void()> /* dumpsys_callback */) {
  return false;
}

}  // namespace shim
}  // namespace bluetooth
