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

#pragma once

#include <cstdint>
#include <memory>
#include <vector>

// syslog.h conflicts with os/*.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING
#include "os/handler.h"

namespace bluetooth::hal {

class HciBackendCallbacks {
 public:
  virtual ~HciBackendCallbacks() = default;
  virtual void initializationComplete(void) = 0;
  virtual void hciEventReceived(const std::vector<uint8_t>&) = 0;
  virtual void aclDataReceived(const std::vector<uint8_t>&) = 0;
  virtual void scoDataReceived(const std::vector<uint8_t>&) = 0;
  virtual void isoDataReceived(const std::vector<uint8_t>&) = 0;
};

class HciBackend {
 public:
  static std::shared_ptr<HciBackend> CreateAidl();
  static std::shared_ptr<HciBackend> CreateHidl(::bluetooth::os::Handler*);

  virtual ~HciBackend() = default;
  virtual void initialize(std::shared_ptr<HciBackendCallbacks>) = 0;
  virtual void sendHciCommand(const std::vector<uint8_t>&) = 0;
  virtual void sendAclData(const std::vector<uint8_t>&) = 0;
  virtual void sendScoData(const std::vector<uint8_t>&) = 0;
  virtual void sendIsoData(const std::vector<uint8_t>&) = 0;
};

}  // namespace bluetooth::hal
