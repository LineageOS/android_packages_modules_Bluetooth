/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <mutex>
#include <queue>

#include "hal/hci_hal.h"
#include "hal/link_clocker.h"
#include "hal/snoop_logger.h"
#include "os/reactor.h"
#include "os/thread.h"

namespace bluetooth {
namespace hal {

namespace {
constexpr int INVALID_FD = -1;
}

class HciHalImpl : public HciHal {
public:
  HciHalImpl(os::Handler*, LinkClocker&, SnoopLogger*);
  ~HciHalImpl();

  void registerIncomingPacketCallback(HciHalCallbacks* callback) override;
  void unregisterIncomingPacketCallback() override;
  void sendHciCommand(HciPacket command) override;
  void sendAclData(HciPacket data) override;
  void sendScoData(HciPacket data) override;
  void sendIsoData(HciPacket data) override;
  uint16_t getMsftOpcode() override;

private:
  // Held when APIs are called, NOT to be held during callbacks
  std::mutex api_mutex_;
  HciHalCallbacks* incoming_packet_callback_ = nullptr;
  std::mutex incoming_packet_callback_mutex_;
  int sock_fd_ = INVALID_FD;
  bluetooth::os::Thread hci_incoming_thread_ =
          bluetooth::os::Thread("hci_incoming_thread", bluetooth::os::Thread::Priority::NORMAL);
  bluetooth::os::Reactor::Reactable* reactable_ = nullptr;
  std::queue<std::vector<uint8_t>> hci_outgoing_queue_;
  [[maybe_unused]] LinkClocker& link_clocker_;
  SnoopLogger* btsnoop_logger_ = nullptr;

  void write_to_fd(HciPacket packet);
  void send_packet_ready();
  bool socketRecvAll(void* buffer, int bufferLen);
  void incoming_packet_received();
};

}  // namespace hal
}  // namespace bluetooth
