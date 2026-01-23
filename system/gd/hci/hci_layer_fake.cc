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

#include "hci/hci_layer_fake.h"

#include <base/functional/bind.h>
#include <bluetooth/log.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <chrono>
#include <future>

#include "packet/raw_builder.h"

namespace bluetooth {
namespace hci {

using common::BidiQueue;
using common::BidiQueueEnd;
using packet::kLittleEndian;
using packet::PacketView;
using packet::RawBuilder;

PacketView<packet::kLittleEndian> GetPacketView(std::unique_ptr<packet::BasePacketBuilder> packet) {
  auto bytes = std::make_shared<std::vector<uint8_t>>();
  BitInserter i(*bytes);
  bytes->reserve(packet->size());
  packet->Serialize(i);
  return packet::PacketView<packet::kLittleEndian>(bytes);
}

std::unique_ptr<BasePacketBuilder> NextPayload(uint16_t handle) {
  static uint32_t packet_number = 1;
  auto payload = std::make_unique<RawBuilder>();
  payload->AddOctets2(6);  // L2CAP PDU size
  payload->AddOctets2(2);  // L2CAP CID
  payload->AddOctets2(handle);
  payload->AddOctets4(packet_number++);
  return std::move(payload);
}

static std::unique_ptr<AclBuilder> NextAclPacket(uint16_t handle) {
  PacketBoundaryFlag packet_boundary_flag = PacketBoundaryFlag::FIRST_AUTOMATICALLY_FLUSHABLE;
  BroadcastFlag broadcast_flag = BroadcastFlag::POINT_TO_POINT;
  return AclBuilder::Create(handle, packet_boundary_flag, broadcast_flag, NextPayload(handle));
}

static void DebugPrintCommandOpcode(std::string prefix,
                                    const std::unique_ptr<CommandBuilder>& command) {
  std::shared_ptr<std::vector<uint8_t>> packet_bytes = std::make_shared<std::vector<uint8_t>>();
  BitInserter it(*packet_bytes);
  command->Serialize(it);
  PacketView<kLittleEndian> packet_bytes_view(packet_bytes);
  auto temp_cmd_view = CommandView::Create(packet_bytes_view);
  temp_cmd_view.IsValid();
  auto op_code = temp_cmd_view.GetOpCode();

  log::info("{} op_code: {}", prefix, OpCodeText(op_code));
}

void HciLayerFake::EnqueueCommand(
        std::unique_ptr<CommandBuilder> command,
        common::ContextualOnceCallback<void(CommandStatusView)> on_status) {
  {
    std::lock_guard<std::mutex> lock(mutex_);

    DebugPrintCommandOpcode(std::string("Sending with command status, "), command);

    command_queue_.push(std::move(command));
    command_status_callbacks_.push_back(std::move(on_status));
  }

  condition_.notify_all();
}

void HciLayerFake::EnqueueCommand(
        std::unique_ptr<CommandBuilder> command,
        common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) {
  {
    std::lock_guard<std::mutex> lock(mutex_);

    DebugPrintCommandOpcode(std::string("Sending with command complete, "), command);

    command_queue_.push(std::move(command));
    command_complete_callbacks_.push_back(std::move(on_complete));
  }

  condition_.notify_all();
}

void HciLayerFake::EnqueueCommand(
        std::unique_ptr<CommandBuilder> /* command */,
        common::ContextualOnceCallback<
                void(CommandStatusOrCompleteView)> /* on_status_or_complete */) {
  FAIL();
}

CommandView HciLayerFake::GetCommand() {
  std::lock_guard<std::mutex> lock(mutex_);

  if (!condition_.wait_for(
              mutex_, std::chrono::milliseconds(1000),
              [this]() EXCLUSIVE_LOCKS_REQUIRED(mutex_) { return !command_queue_.empty(); })) {
    ADD_FAILURE() << "Command queue is empty";
    return empty_command_view_;
  }

  auto last = std::move(command_queue_.front());
  command_queue_.pop();

  CommandView command_packet_view = CommandView::Create(GetPacketView(std::move(last)));
  log::assert_that(command_packet_view.IsValid(), "Got invalid command");
  return command_packet_view;
}

CommandView HciLayerFake::GetCommand(OpCode op_code) {
  auto next_command = GetCommand();
  while (next_command.GetOpCode() != op_code) {
    next_command = GetCommand();
  }
  return next_command;
}

void HciLayerFake::AssertNoQueuedCommand() {
  std::lock_guard lock(mutex_);
  EXPECT_TRUE(command_queue_.empty());
}

void HciLayerFake::RegisterEventHandler(EventCode event_code,
                                        common::ContextualCallback<void(EventView)> event_handler) {
  std::lock_guard lock(mutex_);
  registered_events_[event_code] = event_handler;
}

void HciLayerFake::UnregisterEventHandler(EventCode event_code) {
  std::lock_guard lock(mutex_);
  registered_events_.erase(event_code);
}

void HciLayerFake::RegisterLeEventHandler(
        SubeventCode subevent_code,
        common::ContextualCallback<void(LeMetaEventView)> event_handler) {
  std::lock_guard lock(mutex_);
  registered_le_events_[subevent_code] = event_handler;
}

void HciLayerFake::UnregisterLeEventHandler(SubeventCode subevent_code) {
  std::lock_guard lock(mutex_);
  registered_le_events_.erase(subevent_code);
}

void HciLayerFake::RegisterDevelopmentEventHandler(
        DevelopmentSubeventCode subevent_code,
        common::ContextualCallback<void(DevelopmentEventView)> event_handler) {
  std::lock_guard lock(mutex_);
  registered_development_events_[subevent_code] = event_handler;
}

void HciLayerFake::UnregisterDevelopmentEventHandler(DevelopmentSubeventCode subevent_code) {
  std::lock_guard lock(mutex_);
  registered_development_events_.erase(subevent_code);
}

void HciLayerFake::RegisterVendorSpecificEventHandler(
        VseSubeventCode subevent_code,
        common::ContextualCallback<void(VendorSpecificEventView)> event_handler) {
  std::lock_guard lock(mutex_);
  registered_vs_events_[subevent_code] = event_handler;
}

void HciLayerFake::UnregisterVendorSpecificEventHandler(VseSubeventCode subevent_code) {
  std::lock_guard lock(mutex_);
  registered_vs_events_.erase(subevent_code);
}

void HciLayerFake::IncomingEvent(std::unique_ptr<EventBuilder> event_builder) {
  auto packet = GetPacketView(std::move(event_builder));
  EventView event = EventView::Create(packet);
  ASSERT_TRUE(event.IsValid());
  EventCode event_code = event.GetEventCode();
  if (event_code == EventCode::COMMAND_COMPLETE) {
    CommandCompleteCallback(event);
  } else if (event_code == EventCode::COMMAND_STATUS) {
    CommandStatusCallback(event);
  } else {
    std::lock_guard lock(mutex_);
    ASSERT_NE(registered_events_.find(event_code), registered_events_.end())
            << EventCodeText(event_code);
    registered_events_[event_code](event);
  }
}

void HciLayerFake::IncomingVendorSpecificEvent(
        std::unique_ptr<VendorSpecificEventBuilder> event_builder) {
  std::lock_guard lock(mutex_);
  auto packet = GetPacketView(std::move(event_builder));
  EventView event = EventView::Create(packet);
  VendorSpecificEventView vs_event_view = VendorSpecificEventView::Create(event);
  ASSERT_TRUE(vs_event_view.IsValid());
  VseSubeventCode subevent_code = vs_event_view.GetSubeventCode();
  ASSERT_TRUE(registered_vs_events_.find(subevent_code) != registered_vs_events_.end());
  registered_vs_events_[subevent_code](vs_event_view);
}

void HciLayerFake::IncomingLeMetaEvent(std::unique_ptr<LeMetaEventBuilder> event_builder) {
  std::lock_guard lock(mutex_);
  auto packet = GetPacketView(std::move(event_builder));
  EventView event = EventView::Create(packet);
  LeMetaEventView meta_event_view = LeMetaEventView::Create(event);
  ASSERT_TRUE(meta_event_view.IsValid());
  SubeventCode subevent_code = meta_event_view.GetSubeventCode();
  ASSERT_TRUE(registered_le_events_.find(subevent_code) != registered_le_events_.end());
  registered_le_events_[subevent_code](meta_event_view);
}

void HciLayerFake::IncomingDevelopmentEvent(
        std::unique_ptr<DevelopmentEventBuilder> event_builder) {
  std::lock_guard lock(mutex_);
  auto packet = GetPacketView(std::move(event_builder));
  EventView event = EventView::Create(packet);
  DevelopmentEventView development_event_view = DevelopmentEventView::Create(event);
  ASSERT_TRUE(development_event_view.IsValid());
  DevelopmentSubeventCode subevent_code = development_event_view.GetSubeventCode();
  ASSERT_TRUE(registered_development_events_.find(subevent_code) !=
              registered_development_events_.end());
  registered_development_events_[subevent_code](development_event_view);
}

void HciLayerFake::CommandCompleteCallback(EventView event) {
  CommandCompleteView complete_view = CommandCompleteView::Create(event);
  ASSERT_TRUE(complete_view.IsValid());
  common::ContextualOnceCallback<void(CommandCompleteView)> callback;
  {
    std::lock_guard lock(mutex_);
    callback = std::move(command_complete_callbacks_.front());
    command_complete_callbacks_.pop_front();
  }
  callback(complete_view);
}

void HciLayerFake::CommandStatusCallback(EventView event) {
  CommandStatusView status_view = CommandStatusView::Create(event);
  ASSERT_TRUE(status_view.IsValid());
  common::ContextualOnceCallback<void(CommandStatusView)> callback;
  {
    std::lock_guard lock(mutex_);
    callback = std::move(command_status_callbacks_.front());
    command_status_callbacks_.pop_front();
  }
  callback(status_view);
}

void HciLayerFake::InitEmptyCommand() {
  std::lock_guard lock(mutex_);
  auto payload = std::make_unique<bluetooth::packet::RawBuilder>();
  auto command_builder = CommandBuilder::Create(OpCode::NONE, std::move(payload));
  empty_command_view_ = CommandView::Create(GetPacketView(std::move(command_builder)));
  ASSERT_TRUE(empty_command_view_.IsValid());
}

void HciLayerFake::SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) {
  router_.SetLeAclDataConsumer(le_acl_data_consumer);
}
void HciLayerFake::SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) {
  router_.SetClassicAclDataConsumer(classic_acl_data_consumer);
}

void HciLayerFake::IncomingAclData(uint16_t handle, std::unique_ptr<AclBuilder> acl_builder) {
  std::lock_guard lock(mutex_);
  auto packet = GetPacketView(std::move(acl_builder));
  auto acl_view = AclView::Create(packet);
  ASSERT_TRUE(acl_view.IsValid());
  ASSERT_EQ(handle, acl_view.GetHandle());
  if (vendor_connection_handle_min_ > 0 && handle >= vendor_connection_handle_min_ &&
      handle <= vendor_connection_handle_max_) {
    if (vendor_specific_acl_handler_) {
      auto payload = acl_view.GetPayload();
      std::vector<uint8_t> data(payload.begin(), payload.end());
      handler_->Post(common::BindOnce(
              [](common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler,
                 uint16_t handle, std::vector<uint8_t> data) { handler(handle, std::move(data)); },
              vendor_specific_acl_handler_, handle, std::move(data)));
    }
    return;
  }

  auto* queue_end = acl_queue_.GetDownEnd();
  std::promise<void> promise;
  auto future = promise.get_future();
  queue_end->RegisterEnqueue(
          handler_, common::Bind(
                            [](decltype(queue_end) queue_end, uint16_t /* handle */, AclView acl2,
                               std::promise<void> promise) {
                              queue_end->UnregisterEnqueue();
                              promise.set_value();
                              return std::make_unique<AclView>(acl2);
                            },
                            queue_end, handle, acl_view, base::Passed(std::move(promise))));
  auto status = future.wait_for(std::chrono::milliseconds(1000));
  ASSERT_EQ(status, std::future_status::ready);
}

void HciLayerFake::IncomingAclData(uint16_t handle) {
  IncomingAclData(handle, NextAclPacket(handle));
}

void HciLayerFake::AssertNoOutgoingAclData() {
  std::lock_guard lock(mutex_);
  auto queue_end = acl_queue_.GetDownEnd();
  EXPECT_EQ(queue_end->TryDequeue(), nullptr);
}

PacketView<kLittleEndian> HciLayerFake::OutgoingAclData() {
  std::lock_guard lock(mutex_);
  auto queue_end = acl_queue_.GetDownEnd();
  std::unique_ptr<AclBuilder> received;
  do {
    received = queue_end->TryDequeue();
  } while (received == nullptr);

  return GetPacketView(std::move(received));
}

BidiQueueEnd<AclBuilder, AclView>* HciLayerFake::GetAclQueueEnd() {
  std::lock_guard lock(mutex_);
  return acl_queue_.GetUpEnd();
}

void HciLayerFake::Disconnect(uint16_t handle, ErrorCode reason) {
  handler_->Post(
          common::BindOnce(&HciLayerFake::do_disconnect, common::Unretained(this), handle, reason));
}

void HciLayerFake::do_disconnect(uint16_t handle, ErrorCode reason) {
  HciLayer::Disconnect(handle, reason);
}

void HciLayerFake::SetVendorAclHandleRange(uint16_t min, uint16_t max) {
  std::lock_guard<std::mutex> lock(mutex_);
  vendor_connection_handle_min_ = min;
  vendor_connection_handle_max_ = max;
}

void HciLayerFake::RegisterVendorSpecificAclHandler(
        common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) {
  std::lock_guard<std::mutex> lock(mutex_);
  vendor_specific_acl_handler_ = handler;
}

void HciLayerFake::UnregisterVendorSpecificAclHandler() {
  std::lock_guard<std::mutex> lock(mutex_);
  vendor_specific_acl_handler_ = {};
}

HciLayerFake::HciLayerFake(os::Handler* handler)
    : HciLayer(handler), handler_(handler), router_(handler, acl_queue_.GetUpEnd()) {
  InitEmptyCommand();
  StartWithNoHalDependencies(handler);
}

}  // namespace hci
}  // namespace bluetooth
