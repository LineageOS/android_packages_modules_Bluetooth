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

#include "hal/hci_backend.h"
#include "hal/hci_hal.h"
#include "hal/link_clocker.h"
#include "hal/snoop_logger.h"

namespace bluetooth::hal {
class HciCallbacksImpl;

class HciHalImpl : public HciHal {
public:
  HciHalImpl(os::Handler* handler, LinkClocker& link_clocker, SnoopLogger* btsnoop_logger);
  ~HciHalImpl();

  void sendHciCommand(HciPacket packet) override;
  void sendAclData(HciPacket packet) override;
  void sendScoData(HciPacket packet) override;
  void sendIsoData(HciPacket packet) override;

  void registerIncomingPacketCallback(HciHalCallbacks* callback) override;
  void unregisterIncomingPacketCallback() override;
  uint16_t getMsftOpcode() override;

private:
  std::shared_ptr<HciCallbacksImpl> callbacks_;
  std::shared_ptr<HciBackend> backend_;
  LinkClocker& link_clocker_;
  SnoopLogger* btsnoop_logger_ = nullptr;
};

}  // namespace bluetooth::hal
