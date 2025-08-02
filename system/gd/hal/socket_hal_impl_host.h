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

#pragma once

#include <bluetooth/log.h>

#include "hal/socket_hal.h"

namespace bluetooth::hal {

class SocketHalImpl : public SocketHal {
public:
  SocketHalImpl() { log::verbose("SocketHal module started !!"); }

  ~SocketHalImpl() { log::verbose("SocketHal module stopped !!"); }

protected:
  hal::SocketCapabilities GetSocketCapabilities() const override { return {}; }

  bool RegisterCallback(hal::SocketHalCallback const* /*callback*/) override { return true; }

  bool Opened(const hal::SocketContext& /*context*/) const override { return false; }

  void Closed(uint64_t /*socket_id*/) const override {}
};

}  // namespace bluetooth::hal
