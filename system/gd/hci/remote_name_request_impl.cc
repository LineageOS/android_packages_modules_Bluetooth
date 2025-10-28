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

#include "remote_name_request_impl.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "hci/acl_manager/acl_scheduler.h"
#include "hci/hci_interface.h"
#include "hci/hci_packets.h"

namespace bluetooth::hci {

RemoteNameRequestModuleImpl::RemoteNameRequestModuleImpl(os::Handler* handler,
                                                         HciInterface& hci_interface,
                                                         acl_manager::AclScheduler& acl_scheduler)
    : handler_(handler), hci_layer_(hci_interface), acl_scheduler_(acl_scheduler) {
  log::info("Starting RemoteNameRequestModuleImpl");
  hci_layer_.RegisterEventHandler(
          EventCode::REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION,
          handler_->BindOn(
                  this,
                  &RemoteNameRequestModuleImpl::on_remote_host_supported_features_notification));
  hci_layer_.RegisterEventHandler(
          EventCode::REMOTE_NAME_REQUEST_COMPLETE,
          handler_->BindOn(this, &RemoteNameRequestModuleImpl::on_remote_name_request_complete));
  log::verbose("RemoteNameRequest module started !!");
}

RemoteNameRequestModuleImpl::~RemoteNameRequestModuleImpl() {
  log::info("Destructing RemoteNameRequestModuleImpl");
  hci_layer_.UnregisterEventHandler(EventCode::REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION);
  hci_layer_.UnregisterEventHandler(EventCode::REMOTE_NAME_REQUEST_COMPLETE);
  if (com_android_bluetooth_flags_rnr_multiple_name_request()) {
    requests_.clear();
    on_remote_host_supported_features_notification_ = RemoteHostSupportedFeaturesCallback();
    on_remote_name_complete_ = RemoteNameCallback();
  }
  log::verbose("RemoteNameRequest module stopped !!");
}

void RemoteNameRequestModuleImpl::StartRemoteNameRequest(
        Address address, std::unique_ptr<RemoteNameRequestBuilder> request,
        CompletionCallback on_completion,
        RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification,
        RemoteNameCallback on_remote_name_complete) {
  log::info("Enqueuing remote name request to {}", address.ToRedactedStringForLogging());

  // This callback needs to be shared between the *start* callback and the *cancel_completed*
  // callback, so we refcount it for safety. But since the scheduler guarantees that exactly one
  // of these callbacks will be invokes, this is safe.
  auto on_remote_name_complete_ptr =
          std::make_shared<RemoteNameCallback>(std::move(on_remote_name_complete));

  acl_scheduler_.EnqueueRemoteNameRequest(
          address,
          handler_->BindOnceOn(this,
                               &RemoteNameRequestModuleImpl::actually_start_remote_name_request,
                               address, std::move(request), std::move(on_completion),
                               std::move(on_remote_host_supported_features_notification),
                               on_remote_name_complete_ptr),
          handler_->BindOnce(
                  [&](Address address,
                      std::shared_ptr<RemoteNameCallback> on_remote_name_complete_ptr) {
                    log::info("Dequeued remote name request to {} since it was cancelled",
                              address.ToRedactedStringForLogging());
                    (*on_remote_name_complete_ptr)(ErrorCode::PAGE_TIMEOUT, {});
                  },
                  address, on_remote_name_complete_ptr));
}

void RemoteNameRequestModuleImpl::CancelRemoteNameRequest(Address address) {
  log::info("Enqueuing cancel of remote name request to {}", address.ToRedactedStringForLogging());
  acl_scheduler_.CancelRemoteNameRequest(
          address, handler_->BindOnceOn(
                           this, &RemoteNameRequestModuleImpl::actually_cancel_remote_name_request,
                           address));
}

void RemoteNameRequestModuleImpl::ReportRemoteNameRequestCancellation(Address address) {
  handler_->CallOn(this, &RemoteNameRequestModuleImpl::ReportRemoteNameRequestCancellationImpl,
                   address);
}

// TODO(b/445714747): Remove when rnr_multiple_name_request is shipped
void RemoteNameRequestModuleImpl::ReportRemoteNameRequestCancellationImpl_(Address address) {
  if (pending_) {
    log::info(
            "Received CONNECTION_COMPLETE (corresponding INCORRECTLY to an RNR cancellation) "
            "from {}",
            address.ToRedactedStringForLogging());
    pending_ = false;
    on_remote_name_complete_(ErrorCode::UNKNOWN_CONNECTION, {});
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  } else {
    log::error(
            "Received unexpected CONNECTION_COMPLETE when no Remote Name Request OR ACL "
            "connection is outstanding");
  }
}

void RemoteNameRequestModuleImpl::ReportRemoteNameRequestCancellationImpl(Address address) {
  if (!com_android_bluetooth_flags_rnr_multiple_name_request()) {
    ReportRemoteNameRequestCancellationImpl_(address);
    return;
  }

  if (requests_.contains(address)) {
    log::info(
            "Received CONNECTION_COMPLETE (corresponding INCORRECTLY to an RNR cancellation) "
            "from {}",
            address.ToRedactedStringForLogging());
    requests_.at(address).on_remote_name_complete(ErrorCode::UNKNOWN_CONNECTION, {});
    requests_.erase(address);
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  } else {
    log::error(
            "Received unexpected CONNECTION_COMPLETE when no Remote Name Request OR ACL "
            "connection is outstanding");
  }
}

void RemoteNameRequestModuleImpl::actually_start_remote_name_request(
        Address address, std::unique_ptr<RemoteNameRequestBuilder> request,
        CompletionCallback on_completion,
        RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification,
        std::shared_ptr<RemoteNameCallback> on_remote_name_complete_ptr) {
  log::info("Starting remote name request to {}", address.ToRedactedStringForLogging());
  if (com_android_bluetooth_flags_rnr_multiple_name_request()) {
    log::assert_that(!requests_.contains(address), "assert failed: !requests_.contains(address)");
    RemoteNameCallbacks callbacks = {
            .on_remote_name_complete = std::move(*on_remote_name_complete_ptr.get()),
            .on_remote_host_supported_features_notification =
                    std::move(on_remote_host_supported_features_notification)};
    requests_.insert({address, std::move(callbacks)});
  } else {
    log::assert_that(pending_ == false, "assert failed: pending_ == false");
    pending_ = true;
    on_remote_name_complete_ = std::move(*on_remote_name_complete_ptr.get());
    on_remote_host_supported_features_notification_ =
            std::move(on_remote_host_supported_features_notification);
  }

  hci_layer_.EnqueueCommand(
          std::move(request),
          handler_->BindOnceOn(this,
                               &RemoteNameRequestModuleImpl::on_start_remote_name_request_status,
                               address, std::move(on_completion)));
}

// TODO(b/445714747): Remove when rnr_multiple_name_request is shipped
void RemoteNameRequestModuleImpl::on_start_remote_name_request_status_(
        Address address, CompletionCallback on_completion, CommandStatusView status) {
  log::assert_that(pending_ == true, "assert failed: pending_ == true");
  log::assert_that(status.GetCommandOpCode() == OpCode::REMOTE_NAME_REQUEST,
                   "assert failed: status.GetCommandOpCode() == OpCode::REMOTE_NAME_REQUEST");
  log::info("Started remote name request peer:{} status:{}", address.ToRedactedStringForLogging(),
            ErrorCodeText(status.GetStatus()));
  on_completion(status.GetStatus());
  if (status.GetStatus() != ErrorCode::SUCCESS /* pending */) {
    pending_ = false;
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  }
}

void RemoteNameRequestModuleImpl::on_start_remote_name_request_status(
        Address address, CompletionCallback on_completion, CommandStatusView status) {
  if (!com_android_bluetooth_flags_rnr_multiple_name_request()) {
    on_start_remote_name_request_status_(address, std::move(on_completion), status);
    return;
  }

  log::assert_that(requests_.contains(address), "assert failed: requests_.contains(address)");
  log::assert_that(status.GetCommandOpCode() == OpCode::REMOTE_NAME_REQUEST,
                   "assert failed: status.GetCommandOpCode() == OpCode::REMOTE_NAME_REQUEST");
  log::info("Started remote name request peer:{} status:{}", address.ToRedactedStringForLogging(),
            ErrorCodeText(status.GetStatus()));
  on_completion(status.GetStatus());
  if (status.GetStatus() != ErrorCode::SUCCESS /* pending */) {
    // Here callback is not called. Just call it to remove request
    requests_.erase(address);
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  }
}

void RemoteNameRequestModuleImpl::actually_cancel_remote_name_request(Address address) {
  if ((com_android_bluetooth_flags_rnr_multiple_name_request() && requests_.contains(address)) ||
      (!com_android_bluetooth_flags_rnr_multiple_name_request() && pending_)) {
    log::info("Cancelling remote name request to {}", address.ToRedactedStringForLogging());
    hci_layer_.EnqueueCommand(
            RemoteNameRequestCancelBuilder::Create(address),
            handler_->BindOnceOn(this, &RemoteNameRequestModuleImpl::check_cancel_status, address));
  } else {
    log::info("Ignoring cancel RNR as RNR event already received to {}",
              address.ToRedactedStringForLogging());
  }
}

// TODO(b/445714747): Remove when rnr_multiple_name_request is shipped
void RemoteNameRequestModuleImpl::on_remote_host_supported_features_(EventView view) {
  auto packet = RemoteHostSupportedFeaturesNotificationView::Create(view);
  log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
  if (pending_ && on_remote_host_supported_features_notification_) {
    log::info("Received REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION from {}",
              packet.GetBdAddr().ToRedactedStringForLogging());
    on_remote_host_supported_features_notification_(packet.GetHostSupportedFeatures());
    // Remove the callback so that we won't call it again.
    on_remote_host_supported_features_notification_ = RemoteHostSupportedFeaturesCallback();
  } else if (!pending_) {
    log::error(
            "Received unexpected REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION when no Remote Name "
            "Request is outstanding");
  } else {  // callback is not set, which indicates we have processed the feature notification.
    log::error(
            "Received more than one REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION during Remote "
            "Name Request");
  }
}

void RemoteNameRequestModuleImpl::on_remote_host_supported_features_notification(EventView view) {
  if (!com_android_bluetooth_flags_rnr_multiple_name_request()) {
    on_remote_host_supported_features_(std::move(view));
    return;
  }

  auto packet = RemoteHostSupportedFeaturesNotificationView::Create(view);
  log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");

  const Address& address = packet.GetBdAddr();
  if (requests_.contains(address)) {
    log::info("Received REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION from {}",
              address.ToRedactedStringForLogging());
    requests_.at(address).on_remote_host_supported_features_notification(
            packet.GetHostSupportedFeatures());
  } else {
    log::error(
            "Received unexpected REMOTE_HOST_SUPPORTED_FEATURES_NOTIFICATION when no Remote Name "
            "Request is outstanding");
  }
}

// TODO(b/445714747): Remove when rnr_multiple_name_request is shipped
void RemoteNameRequestModuleImpl::completed_(ErrorCode status, std::array<uint8_t, 248> name,
                                             Address address) {
  if (pending_) {
    log::info("Received REMOTE_NAME_REQUEST_COMPLETE from {} with status {}",
              address.ToRedactedStringForLogging(), ErrorCodeText(status));
    pending_ = false;
    on_remote_name_complete_(status, name);
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  } else {
    log::error("Received unexpected REMOTE_NAME_REQUEST_COMPLETE from {} with status {}",
               address.ToRedactedStringForLogging(), ErrorCodeText(status));
  }
}

void RemoteNameRequestModuleImpl::completed(ErrorCode status, std::array<uint8_t, 248> name,
                                            Address address) {
  if (!com_android_bluetooth_flags_rnr_multiple_name_request()) {
    completed_(status, name, address);
    return;
  }

  if (requests_.contains(address)) {
    log::info("Received REMOTE_NAME_REQUEST_COMPLETE from {} with status {}",
              address.ToRedactedStringForLogging(), ErrorCodeText(status));
    requests_.at(address).on_remote_name_complete(status, name);
    requests_.erase(address);
    acl_scheduler_.ReportRemoteNameRequestCompletion(address);
  } else {
    log::error("Received unexpected REMOTE_NAME_REQUEST_COMPLETE from {} with status {}",
               address.ToRedactedStringForLogging(), ErrorCodeText(status));
  }
}

void RemoteNameRequestModuleImpl::on_remote_name_request_complete(EventView view) {
  auto packet = RemoteNameRequestCompleteView::Create(view);
  log::assert_that(packet.IsValid(), "Invalid packet");
  completed(packet.GetStatus(), packet.GetRemoteName(), packet.GetBdAddr());
}

void RemoteNameRequestModuleImpl::check_cancel_status(Address remote,
                                                      CommandCompleteView complete) {
  auto packet = RemoteNameRequestCancelCompleteView::Create(complete);
  if (!packet.IsValid()) {
    completed(ErrorCode::UNSPECIFIED_ERROR, std::array<uint8_t, 248>{}, remote);
    return;
  }
  auto status = packet.GetStatus();
  if (status != ErrorCode::SUCCESS) {
    completed(status, std::array<uint8_t, 248>{}, packet.GetBdAddr());
  }
}

}  // namespace bluetooth::hci
