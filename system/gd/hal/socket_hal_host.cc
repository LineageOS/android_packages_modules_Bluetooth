/*
 * Copyright (C) 2024 The Android Open Source Project
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
#include "hal/socket_hal.h"

namespace bluetooth::hal {

class SocketHalHost : public SocketHal {
protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {}

  void Stop() override { socket_hal_cb_ = nullptr; }

  std::string ToString() const override { return std::string("SocketHalHost"); }

  hal::SocketCapabilities GetSocketCapabilities() const override { return {}; }

  bool RegisterCallback(hal::SocketHalCallback const* /*callback*/) override { return false; }

  bool Opened(const hal::SocketContext& /*context*/) const override { return false; }

  void Closed(uint64_t /*socket_id*/) const override {}

private:
  hal::SocketHalCallback* socket_hal_cb_;
};

const ModuleFactory SocketHal::Factory = ModuleFactory([]() { return new SocketHalHost(); });

}  // namespace bluetooth::hal
