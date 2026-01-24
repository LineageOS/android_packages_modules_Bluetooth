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

#include "hci/acl_manager/acl_scheduler.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <deque>
#include <optional>
#include <unordered_set>
#include <utility>
#include <variant>

namespace bluetooth {
namespace hci {

namespace acl_manager {

struct AclCreateConnectionQueueEntry {
  Address address;
  common::ContextualOnceCallback<void()> callback;
};

struct RemoteNameRequestQueueEntry {
  Address address;
  common::ContextualOnceCallback<void()> callback;
  common::ContextualOnceCallback<void()> callback_when_cancelled;
};

using QueueEntry = std::variant<AclCreateConnectionQueueEntry, RemoteNameRequestQueueEntry>;

struct AclScheduler::impl {
  impl(os::Handler* handler) : handler_(handler) {}

  void EnqueueOutgoingAclConnection(Address address,
                                    common::ContextualOnceCallback<void()> start_connection) {
    pending_outgoing_operations_.push_back(
            AclCreateConnectionQueueEntry{address, std::move(start_connection)});
    try_dequeue_next_operation();
  }

  void RegisterPendingIncomingConnection(Address address) {
    incoming_connecting_address_set_.insert(address);
  }

  void ReportAclConnectionCompletion(
          Address address, common::ContextualOnceCallback<void()> handle_outgoing_connection,
          common::ContextualOnceCallback<void()> handle_incoming_connection,
          common::ContextualOnceCallback<void(std::string)> handle_unknown_connection) {
    // Check if an outgoing request (a) exists, (b) is a Create Connection, (c) matches the received
    // address
    if (outgoing_entry_.has_value()) {
      auto entry = std::get_if<AclCreateConnectionQueueEntry>(&outgoing_entry_.value());
      if (entry != nullptr && entry->address == address) {
        // If so, clear the current entry and advance the queue
        outgoing_entry_.reset();
        handle_outgoing_connection();
        // Check if incoming request also exists for this address
        if (incoming_connecting_address_set_.find(address) !=
            incoming_connecting_address_set_.end()) {
          log::warn("Incoming connection request also exists for {}", address);
          incoming_connecting_address_set_.erase(address);
        }
        try_dequeue_next_operation();
        return;
      }
    }

    // Otherwise check if it's an incoming request and advance the queue if so
    if (incoming_connecting_address_set_.find(address) != incoming_connecting_address_set_.end()) {
      incoming_connecting_address_set_.erase(address);
      handle_incoming_connection();
    } else {
      handle_unknown_connection(set_of_incoming_connecting_addresses());
    }
    try_dequeue_next_operation();
  }

  void ReportOutgoingAclConnectionFailure() {
    if (!outgoing_entry_.has_value()) {
      log::error("Outgoing connection failure reported, but none present!");
      return;
    }
    auto entry = std::get_if<AclCreateConnectionQueueEntry>(&outgoing_entry_.value());
    if (entry == nullptr) {
      log::error("Outgoing connection failure reported, but we're currently doing an RNR!");
      return;
    }
    outgoing_entry_.reset();
    try_dequeue_next_operation();
  }

  void CancelAclConnection(Address address,
                           common::ContextualOnceCallback<void()> cancel_connection,
                           common::ContextualOnceCallback<void()> cancel_connection_completed) {
    auto ok = cancel_outgoing_or_queued_connection(
            [&](auto& entry) {
              auto entry_ptr = std::get_if<AclCreateConnectionQueueEntry>(&entry);
              return entry_ptr != nullptr && entry_ptr->address == address;
            },
            [&]() { cancel_connection(); },
            [&](auto /* entry */) { cancel_connection_completed(); });
    if (!ok) {
      log::error("Attempted to cancel connection to {} that does not exist", address);
    }
  }

  void EnqueueRemoteNameRequest(Address address,
                                common::ContextualOnceCallback<void()> start_request,
                                common::ContextualOnceCallback<void()> cancel_request_completed) {
    pending_outgoing_operations_.push_back(RemoteNameRequestQueueEntry{
            address, std::move(start_request), std::move(cancel_request_completed)});
    try_dequeue_next_operation();
  }

  void ReportRemoteNameRequestCompletion(Address /* address */) {
    if (!outgoing_entry_.has_value()) {
      log::error("Remote name request completion reported, but none taking place!");
      return;
    }

    std::visit(
            [](auto&& entry) {
              using T = std::decay_t<decltype(entry)>;
              if constexpr (std::is_same_v<T, RemoteNameRequestQueueEntry>) {
                log::info("Remote name request completed");
              } else if constexpr (std::is_same_v<T, AclCreateConnectionQueueEntry>) {
                log::error(
                        "Received RNR completion when ACL connection is outstanding - assuming the "
                        "connection has failed and continuing");
              } else {
                static_assert(!sizeof(T*), "non-exhaustive visitor!");
              }
            },
            outgoing_entry_.value());

    outgoing_entry_.reset();
    try_dequeue_next_operation();
  }

  void CancelRemoteNameRequest(Address address,
                               common::ContextualOnceCallback<void()> cancel_request) {
    auto ok = cancel_outgoing_or_queued_connection(
            [&](auto& entry) {
              auto entry_ptr = std::get_if<RemoteNameRequestQueueEntry>(&entry);
              return entry_ptr != nullptr && entry_ptr->address == address;
            },
            [&]() { cancel_request(); },
            [](auto entry) {
              std::get<RemoteNameRequestQueueEntry>(entry).callback_when_cancelled();
            });
    if (!ok) {
      log::error("Attempted to cancel remote name request to {} that does not exist", address);
    }
  }

  void Stop() {
    stopped_ = true;
  }

private:
  bool ready_to_send_next_operation() const {
    if (stopped_) {
      return false;
    }
    if (pending_outgoing_operations_.empty()) {
      return false;
    }
    if (const RemoteNameRequestQueueEntry* peek =
                std::get_if<RemoteNameRequestQueueEntry>(&pending_outgoing_operations_.front())) {
      if (incoming_connecting_address_set_.contains(peek->address)) {
        log::info("Pending incoming connection and outgoing RNR to same peer:{}", peek->address);
        return true;
      }
    }
    return incoming_connecting_address_set_.empty() && !outgoing_entry_.has_value();
  }

  std::stringstream log_queue_entry(const QueueEntry& entry) {
    std::stringstream ss;
    if (const RemoteNameRequestQueueEntry* peek =
                std::get_if<RemoteNameRequestQueueEntry>(&entry)) {
      ss << "RNR to " << peek->address.ToRedactedStringForLogging();
    } else if (const AclCreateConnectionQueueEntry* peek =
                       std::get_if<AclCreateConnectionQueueEntry>(&entry)) {
      ss << "ACL connection to " << peek->address.ToRedactedStringForLogging();
    } else {
      ss << "Unknown entry type";
    }
    return ss;
  }

  inline void log_try_dequeue_next_operation() {
    log::info(
            "Could not send next operation postponed to next iteration, stopped: {}, "
            "pending_outgoing_operations_ is_empty: "
            "{}, outgoing_entry_ has_value: {}, incoming_connecting_address_set_ is_empty: {}",
            stopped_, pending_outgoing_operations_.empty(), outgoing_entry_.has_value(),
            incoming_connecting_address_set_.empty());


    // log the contents of the pending_outgoing_operations_
    if (!pending_outgoing_operations_.empty()) {
      std::stringstream log_message;
      log_message << "Pending Outgoing Operations: ";
      for (const auto& entry : pending_outgoing_operations_) {
        log_message << log_queue_entry(entry).str() << ", ";
      }
      log::info("{}", log_message.str());
    }


    // Aggregate contents of the incoming_connecting_address_set_
    if (!incoming_connecting_address_set_.empty()) {
      std::stringstream log_message;
      log_message << "Incoming Connections from: ";
      for (const auto& address : incoming_connecting_address_set_) {
        log_message << address.ToRedactedStringForLogging() << ", ";
      }
      log::info("{}", log_message.str());
    }


    // Aggregate contents of the outgoing_entry_
    if (outgoing_entry_.has_value()) {
      log::info("Current Outgoing Entry: {}", log_queue_entry(outgoing_entry_.value()).str());
    }
  }

  void try_dequeue_next_operation() {
    if (ready_to_send_next_operation()) {
      log::info("Pending connections is not empty; so sending next connection");
      auto entry = std::move(pending_outgoing_operations_.front());
      pending_outgoing_operations_.pop_front();
      std::visit([](auto&& variant) { variant.callback(); }, entry);
      outgoing_entry_ = std::move(entry);
    } else {
      // log the reasons on why we're not sending the next operation
      log_try_dequeue_next_operation();
    }
  }

  template <typename T, typename U, typename V>
  bool cancel_outgoing_or_queued_connection(T matcher, U cancel_outgoing, V cancelled_queued) {
    // Check if relevant connection is currently outgoing
    if (outgoing_entry_.has_value()) {
      if (matcher(outgoing_entry_.value())) {
        cancel_outgoing();
        return true;
      }
    }
    // Otherwise, clear from the queue
    auto it = std::find_if(pending_outgoing_operations_.begin(), pending_outgoing_operations_.end(),
                           matcher);
    if (it == pending_outgoing_operations_.end()) {
      return false;
    }
    cancelled_queued(std::move(*it));
    pending_outgoing_operations_.erase(it);
    return true;
  }

  const std::string set_of_incoming_connecting_addresses() const {
    std::stringstream buffer;
    for (const auto& c : incoming_connecting_address_set_) {
      buffer << " " << c.ToRedactedStringForLogging();
    }
    return buffer.str();
  }

  std::optional<QueueEntry> outgoing_entry_;
  std::deque<QueueEntry> pending_outgoing_operations_;
  std::unordered_set<Address> incoming_connecting_address_set_;
  bool stopped_ = false;

public:
  os::Handler* handler_;
};

AclScheduler::AclScheduler(os::Handler* handler) : pimpl_(std::make_unique<impl>(handler)) {
  log::verbose("module started !!");
}

AclScheduler::~AclScheduler() {
  pimpl_->Stop();
  pimpl_.reset();
  log::verbose("module stopped !!");
}

void AclScheduler::EnqueueOutgoingAclConnection(
        Address address, common::ContextualOnceCallback<void()> start_connection) {
  pimpl_->handler_->Call(&impl::EnqueueOutgoingAclConnection, common::Unretained(pimpl_.get()),
                         address, std::move(start_connection));
}

void AclScheduler::RegisterPendingIncomingConnection(Address address) {
  pimpl_->handler_->Call(&impl::RegisterPendingIncomingConnection, common::Unretained(pimpl_.get()),
                         address);
}

void AclScheduler::ReportAclConnectionCompletion(
        Address address, common::ContextualOnceCallback<void()> handle_outgoing_connection,
        common::ContextualOnceCallback<void()> handle_incoming_connection,
        common::ContextualOnceCallback<void(std::string)> handle_unknown_connection) {
  pimpl_->handler_->Call(&impl::ReportAclConnectionCompletion, common::Unretained(pimpl_.get()),
                         address, std::move(handle_outgoing_connection),
                         std::move(handle_incoming_connection),
                         std::move(handle_unknown_connection));
}

void AclScheduler::ReportOutgoingAclConnectionFailure() {
  pimpl_->handler_->Call(&impl::ReportOutgoingAclConnectionFailure,
                         common::Unretained(pimpl_.get()));
}

void AclScheduler::CancelAclConnection(
        Address address, common::ContextualOnceCallback<void()> cancel_connection,
        common::ContextualOnceCallback<void()> cancel_connection_completed) {
  pimpl_->handler_->Call(&impl::CancelAclConnection, common::Unretained(pimpl_.get()), address,
                         std::move(cancel_connection), std::move(cancel_connection_completed));
}

void AclScheduler::EnqueueRemoteNameRequest(
        Address address, common::ContextualOnceCallback<void()> start_request,
        common::ContextualOnceCallback<void()> cancel_request_completed) {
  pimpl_->handler_->Call(&impl::EnqueueRemoteNameRequest, common::Unretained(pimpl_.get()), address,
                         std::move(start_request), std::move(cancel_request_completed));
}

void AclScheduler::ReportRemoteNameRequestCompletion(Address address) {
  pimpl_->handler_->Call(&impl::ReportRemoteNameRequestCompletion, common::Unretained(pimpl_.get()),
                         address);
}

void AclScheduler::CancelRemoteNameRequest(Address address,
                                           common::ContextualOnceCallback<void()> cancel_request) {
  pimpl_->handler_->Call(&impl::CancelRemoteNameRequest, common::Unretained(pimpl_.get()), address,
                         std::move(cancel_request));
}

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
