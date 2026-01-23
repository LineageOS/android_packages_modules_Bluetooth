/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <android-base/thread_annotations.h>

#include <condition_variable>
#include <list>
#include <map>
#include <memory>
#include <queue>
#include <vector>

#include "hci/hci_data_router.h"
#include "hci/hci_layer.h"

namespace bluetooth {
namespace hci {

packet::PacketView<packet::kLittleEndian> GetPacketView(
        std::unique_ptr<packet::BasePacketBuilder> packet);

std::unique_ptr<BasePacketBuilder> NextPayload(uint16_t handle);

class HciLayerFake : public HciLayer {
public:
  HciLayerFake(os::Handler* handler);

  void EnqueueCommand(std::unique_ptr<CommandBuilder> command,
                      common::ContextualOnceCallback<void(CommandStatusView)> on_status) override;

  void EnqueueCommand(
          std::unique_ptr<CommandBuilder> command,
          common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) override;

  void EnqueueCommand(std::unique_ptr<CommandBuilder> command,
                      common::ContextualOnceCallback<void(CommandStatusOrCompleteView)>
                              on_status_or_complete) override;

  CommandView GetCommand() LOCKS_EXCLUDED(mutex_);

  CommandView GetCommand(OpCode op_code) LOCKS_EXCLUDED(mutex_);

  void AssertNoQueuedCommand() LOCKS_EXCLUDED(mutex_);

  void RegisterEventHandler(EventCode event_code,
                            common::ContextualCallback<void(EventView)> event_handler) override;

  void UnregisterEventHandler(EventCode event_code) override;

  void RegisterLeEventHandler(
          SubeventCode subevent_code,
          common::ContextualCallback<void(LeMetaEventView)> event_handler) override;

  void UnregisterLeEventHandler(SubeventCode subevent_code) override;

  void RegisterDevelopmentEventHandler(
          DevelopmentSubeventCode subevent_code,
          common::ContextualCallback<void(DevelopmentEventView)> event_handler) override;

  void UnregisterDevelopmentEventHandler(DevelopmentSubeventCode subevent_code) override;

  void RegisterVendorSpecificEventHandler(
          VseSubeventCode subevent_code,
          common::ContextualCallback<void(VendorSpecificEventView)> event_handler) override;

  void UnregisterVendorSpecificEventHandler(VseSubeventCode subevent_code) override;

  void IncomingEvent(std::unique_ptr<EventBuilder> event_builder) LOCKS_EXCLUDED(mutex_);

  void IncomingVendorSpecificEvent(std::unique_ptr<VendorSpecificEventBuilder> event_builder)
          LOCKS_EXCLUDED(mutex_);

  void IncomingLeMetaEvent(std::unique_ptr<LeMetaEventBuilder> event_builder)
          LOCKS_EXCLUDED(mutex_);

  void IncomingDevelopmentEvent(std::unique_ptr<DevelopmentEventBuilder> event_builder)
          LOCKS_EXCLUDED(mutex_);

  void CommandCompleteCallback(EventView event) LOCKS_EXCLUDED(mutex_);

  void CommandStatusCallback(EventView event) LOCKS_EXCLUDED(mutex_);

  void IncomingAclData(uint16_t handle) LOCKS_EXCLUDED(mutex_);

  void IncomingAclData(uint16_t handle, std::unique_ptr<AclBuilder> acl_builder)
          LOCKS_EXCLUDED(mutex_);

  void AssertNoOutgoingAclData() LOCKS_EXCLUDED(mutex_);

  packet::PacketView<packet::kLittleEndian> OutgoingAclData() LOCKS_EXCLUDED(mutex_);

  common::BidiQueueEnd<AclBuilder, AclView>* GetAclQueueEnd() override;

  void Disconnect(uint16_t handle, ErrorCode reason) override;

  void SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) override;
  void SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) override;

  void SetVendorAclHandleRange(uint16_t min, uint16_t max) override;
  void RegisterVendorSpecificAclHandler(
          common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) override;
  void UnregisterVendorSpecificAclHandler() override;

  os::Handler* handler_;

private:
  void InitEmptyCommand() LOCKS_EXCLUDED(mutex_);
  void do_disconnect(uint16_t handle, ErrorCode reason);

  std::list<common::ContextualOnceCallback<void(CommandCompleteView)>> command_complete_callbacks_
          GUARDED_BY(mutex_);
  std::list<common::ContextualOnceCallback<void(CommandStatusView)>> command_status_callbacks_
          GUARDED_BY(mutex_);
  std::map<EventCode, common::ContextualCallback<void(EventView)>> registered_events_
          GUARDED_BY(mutex_);
  std::map<SubeventCode, common::ContextualCallback<void(LeMetaEventView)>> registered_le_events_
          GUARDED_BY(mutex_);
  std::map<DevelopmentSubeventCode, common::ContextualCallback<void(DevelopmentEventView)>>
          registered_development_events_ GUARDED_BY(mutex_);
  std::map<VseSubeventCode, common::ContextualCallback<void(VendorSpecificEventView)>>
          registered_vs_events_ GUARDED_BY(mutex_);
  uint16_t vendor_connection_handle_min_ GUARDED_BY(mutex_) = 0;
  uint16_t vendor_connection_handle_max_ GUARDED_BY(mutex_) = 0;
  common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> vendor_specific_acl_handler_
          GUARDED_BY(mutex_);

  common::BidiQueue<AclView, AclBuilder> acl_queue_ GUARDED_BY(mutex_){
          3 /* TODO: Set queue depth */};

  mutable std::mutex mutex_;
  std::condition_variable_any condition_;  // Used to notify when new commands are enqueued.

  // Shared state between the test and stack threads
  std::queue<std::unique_ptr<CommandBuilder>> command_queue_ GUARDED_BY(mutex_);

  CommandView empty_command_view_ GUARDED_BY(mutex_) = CommandView::Create(
          PacketView<packet::kLittleEndian>(std::make_shared<std::vector<uint8_t>>()));

  HciDataRouter router_;
};

}  // namespace hci
}  // namespace bluetooth
