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

#include "main/shim/stack.h"
#include "os/handler.h"
#include "os/thread.h"

// The shim layer implementation on the Gd stack side.
namespace bluetooth::shim {

// GD shim stack, having modes corresponding to legacy stack
class StackImpl : public Stack {
public:
  StackImpl();
  StackImpl(const Stack&) = delete;
  StackImpl& operator=(const Stack&) = delete;

  virtual ~StackImpl() = default;

  // Running mode, everything is up
  void StartEverything() override;

  void Stop() override;
  bool IsRunning() override;

  virtual Acl* GetAcl() const override;
  virtual storage::StorageModule* GetStorage() const override;
  virtual hal::SnoopLogger* GetSnoopLogger() const override;
  virtual lpp::LppOffloadInterface* GetLppOffloadInterface() const override;
  virtual hci::HciInterface* GetHciLayer() const override;
  virtual hci::Controller* GetController() const override;
  virtual hci::RemoteNameRequestModule* GetRemoteNameRequest() const override;
  virtual hci::acl_manager::AclManagerClassic* GetAclManagerClassic() const override;
  virtual hci::AclManagerLe* GetAclManagerLe() const override;
  virtual hci::MsftExtensionManager* GetMsftExtensionManager() const override;
  virtual hci::LeScanningManager* GetLeScanningManager() const override;
  virtual hci::LeAdvertisingManager* GetLeAdvertisingManager() const override;
  virtual hci::DistanceMeasurementManager* GetDistanceMeasurementManager() const override;
  os::Handler* GetHandler();

  void Dump(int fd, std::promise<void> promise) const override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;

  mutable std::recursive_mutex mutex_;
  bool is_running_ = false;
  os::Thread* stack_thread_ = nullptr;
  os::Handler* stack_handler_ = nullptr;

  os::Thread* management_thread_ = nullptr;
  os::Handler* management_handler_ = nullptr;

  void handle_start_up();
  void handle_start_up_old(std::promise<void> promise);
  void handle_shut_down();
  void handle_shut_down_old(std::promise<void> promise);
};

}  // namespace bluetooth::shim
