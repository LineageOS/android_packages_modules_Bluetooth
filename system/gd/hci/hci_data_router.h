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

#include <com_android_bluetooth_flags.h>

#include <memory>

#include "common/bidi_queue.h"
#include "hci/acl_manager/assembler.h"
#include "hci/classic_acl_data_consumer.h"
#include "hci/hci_packets.h"
#include "hci/le_acl_data_consumer.h"
#include "os/alarm.h"
#include "os/handler.h"

#ifdef USE_FAKE_TIMERS
#include "os/fake_timer/fake_timerfd.h"
using bluetooth::os::fake_timer::fake_timerfd_get_clock;
#endif

namespace bluetooth::hci {

class HciDataRouter {
  static constexpr uint16_t kQualcommDebugHandle = 0xedc;
  static constexpr uint16_t kSamsungDebugHandle = 0xeef;
  static constexpr uint16_t kMtkDebugHandle = 0x5ff;
  static constexpr std::chrono::seconds kWaitBeforeDroppingUnknownAcl{1};

public:
  HciDataRouter(os::Handler* handler, common::BidiQueueEnd<AclBuilder, AclView>* hci_queue_end)
      : handler_(handler), hci_queue_end_(hci_queue_end) {
    hci_queue_end_->RegisterDequeue(
            handler_, common::Bind(&HciDataRouter::dequeue_and_route_acl_packet_to_connection,
                                   common::Unretained(this)));
  }

  HciDataRouter(const HciDataRouter&) = delete;
  HciDataRouter& operator=(const HciDataRouter&) = delete;

  virtual ~HciDataRouter() { hci_queue_end_->UnregisterDequeue(); }

  void SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) {
    le_acl_data_consumer_ = le_acl_data_consumer;
  }

  void SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) {
    classic_acl_data_consumer_ = classic_acl_data_consumer;
  }

private:
  struct WaitingPacket {
    AclView packet;
    std::chrono::steady_clock::time_point enqueued_timestamp;
    WaitingPacket(AclView packet, std::chrono::steady_clock::time_point enqueued_timestamp)
        : packet(std::move(packet)), enqueued_timestamp(enqueued_timestamp) {}
    WaitingPacket(const WaitingPacket&) = default;
    WaitingPacket& operator=(const WaitingPacket&) = default;
    WaitingPacket(WaitingPacket&&) = default;
    WaitingPacket& operator=(WaitingPacket&&) = default;
  };

  void retry_unknown_acl_packets_(bool timed_out) {
    std::vector<WaitingPacket> unsent_packets;
    for (const auto& itr : waiting_packets_) {
      auto handle = itr.packet.GetHandle();
      if (!classic_acl_data_consumer_->SendPacketUpward(
                  handle,
                  [itr](struct acl_manager::assembler* assembler) {
                    assembler->on_incoming_packet(itr.packet);
                  }) &&
          !le_acl_data_consumer_->SendPacketUpward(handle,
                                                   [itr](struct acl_manager::assembler* assembler) {
                                                     assembler->on_incoming_packet(itr.packet);
                                                   })) {
        if (!timed_out) {
          unsent_packets.push_back(itr);
        } else {
          log::error("Dropping packet of size {} to unknown connection 0x{:x}", itr.packet.size(),
                     itr.packet.GetHandle());
        }
      }
    }
    waiting_packets_ = std::move(unsent_packets);
  }

  void retry_unknown_acl(bool timed_out) {
    if (!com_android_bluetooth_flags_discard_unknown_acl_packet()) {
      retry_unknown_acl_packets_(timed_out);
      return;
    }
    std::erase_if(waiting_packets_, [this](const WaitingPacket& packet) {
      if ((classic_acl_data_consumer_->SendPacketUpward(
                  packet.packet.GetHandle(),
                  [&packet](struct acl_manager::assembler* assembler) {
                    assembler->on_incoming_packet(packet.packet);
                  })) ||
          (le_acl_data_consumer_->SendPacketUpward(
                  packet.packet.GetHandle(), [&packet](struct acl_manager::assembler* assembler) {
                    assembler->on_incoming_packet(packet.packet);
                  }))) {
        return true;
      }
#ifdef USE_FAKE_TIMERS
      auto now = std::chrono::steady_clock::time_point(
              std::chrono::milliseconds(static_cast<int64_t>(fake_timerfd_get_clock())));
#else
      auto now = std::chrono::steady_clock::now();
#endif
      bool expired = now >= packet.enqueued_timestamp + kWaitBeforeDroppingUnknownAcl;
      if (expired) {
        log::error("Dropping packet of size {} to unknown connection 0x{:x}", packet.packet.size(),
                   packet.packet.GetHandle());
        return true;
      }
      return false;
    });

    if (waiting_packets_.empty()) {
      if (!com_android_bluetooth_flags_fix_module_shutdown_sync_with_stack()) {
        unknown_acl_alarm_.reset();
      } else {
        // Do not reset the alarm, instead just cancel it.
        // Reset will wait on the reactable to shutdown, which will be a deadlock since the action
        // is still going on waiting on itself.
        unknown_acl_alarm_->Cancel();
      }
    } else if (timed_out) {
      unknown_acl_alarm_->Schedule(
              common::BindOnce(&HciDataRouter::on_unknown_acl_timer, common::Unretained(this)),
              kWaitBeforeDroppingUnknownAcl);
    }
  }

  void on_unknown_acl_timer() {
    log::info("Timer fired!");
    retry_unknown_acl(/* timed_out = */ true);
    if (!com_android_bluetooth_flags_discard_unknown_acl_packet() &&
        !com_android_bluetooth_flags_fix_module_shutdown_sync_with_stack()) {
      // Do not reset the alarm if the work is done as we are now waiting for the reactable to
      // finished which will overlap with this and never succeed.
      // Instead re-use this object as that will be rescheduled again in
      // `dequeue_and_route_acl_packet_to_connection()`.
      unknown_acl_alarm_.reset();
    }
  }

  void dequeue_and_route_acl_packet_to_connection() {
    // Retry any waiting packets first
    if (!waiting_packets_.empty()) {
      retry_unknown_acl(/* timed_out = */ false);
    }

    auto packet = hci_queue_end_->TryDequeue();
    log::assert_that(packet != nullptr, "assert failed: packet != nullptr");
    if (!packet->IsValid()) {
      log::info("Dropping invalid packet of size {}", packet->size());
      return;
    }
    uint16_t handle = packet->GetHandle();
    if (handle == kQualcommDebugHandle || handle == kSamsungDebugHandle ||
        handle == kMtkDebugHandle) {
      return;
    }
    if (classic_acl_data_consumer_->SendPacketUpward(
                handle, [&packet](struct acl_manager::assembler* assembler) {
                  assembler->on_incoming_packet(*packet);
                })) {
      return;
    }
    if (le_acl_data_consumer_->SendPacketUpward(
                handle, [&packet](struct acl_manager::assembler* assembler) {
                  assembler->on_incoming_packet(*packet);
                })) {
      return;
    }
    if (unknown_acl_alarm_ == nullptr) {
      if (com_android_bluetooth_flags_fix_module_shutdown_sync_with_stack()) {
        // Do a blocking wait for `kHandlerStopTimeout` before destructing the alarm. This prevents
        // the HciDataRouter destruction while the alarm's task is still running.
        unknown_acl_alarm_.reset(new os::Alarm(&handler_->thread(), kHandlerStopTimeout));
      } else {
        unknown_acl_alarm_.reset(new os::Alarm(&handler_->thread()));
      }
      if (com_android_bluetooth_flags_discard_unknown_acl_packet()) {
        unknown_acl_alarm_->Schedule(
                common::BindOnce(&HciDataRouter::on_unknown_acl_timer, common::Unretained(this)),
                kWaitBeforeDroppingUnknownAcl);
      }
    }
#ifdef USE_FAKE_TIMERS
    waiting_packets_.emplace_back(*packet,
                                  std::chrono::steady_clock::time_point(std::chrono::milliseconds(
                                          static_cast<int64_t>(fake_timerfd_get_clock()))));
#else
    waiting_packets_.emplace_back(*packet, std::chrono::steady_clock::now());
#endif
    log::info("Saving packet of size {} to unknown connection 0x{:x}", packet->size(),
              packet->GetHandle());
    if (!com_android_bluetooth_flags_discard_unknown_acl_packet()) {
      unknown_acl_alarm_->Schedule(
              common::BindOnce(&HciDataRouter::on_unknown_acl_timer, common::Unretained(this)),
              kWaitBeforeDroppingUnknownAcl);
    }
  }

  os::Handler* handler_;
  LeAclDataConsumer* le_acl_data_consumer_ = nullptr;
  ClassicAclDataConsumer* classic_acl_data_consumer_ = nullptr;
  common::BidiQueueEnd<AclBuilder, AclView>* hci_queue_end_ = nullptr;

  std::unique_ptr<os::Alarm> unknown_acl_alarm_;
  std::vector<WaitingPacket> waiting_packets_;
};

}  // namespace bluetooth::hci
