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

#pragma once

#include <map>

#include "hci/acl_manager/acl_scheduler.h"
#include "hci/hci_interface.h"
#include "hci/remote_name_request.h"

namespace bluetooth {
namespace hci {

// Historical note: This class is intended to provide a shim at the *HCI* layer, so legacy Remote
// Name Requests can interoperate with the GD ACL scheduler. Thus, we intentionally do not merge
// identical requests, cache responses, or handle request timeouts - we leave this to our callers.
// When GD clients start to use this module, richer functionality should be added.
class RemoteNameRequestModuleImpl : public RemoteNameRequestModule {
public:
  RemoteNameRequestModuleImpl(os::Handler* handler, HciInterface& hci_interface,
                              acl_manager::AclScheduler& acl_scheduler);
  ~RemoteNameRequestModuleImpl();

  // Dispatch a Remote Name Request
  void StartRemoteNameRequest(
          Address address, std::unique_ptr<RemoteNameRequestBuilder> request,
          CompletionCallback on_completion,
          RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification,
          RemoteNameCallback on_remote_name_complete) override;

  // Cancel a Remote Name Request
  void CancelRemoteNameRequest(Address address) override;

  // Due to controller bugs (b/184239841), an ACL connection completion is sometimes reported in
  // place of an RNR completion This method lets the ACL manager inform the RNR module if this
  // happens, since we don't get the appropriate HCI event.
  void ReportRemoteNameRequestCancellation(Address address) override;

private:
  void ReportRemoteNameRequestCancellationImpl_(Address address);
  void on_start_remote_name_request_status_(Address address, CompletionCallback on_completion,
                                            CommandStatusView status);
  void on_remote_host_supported_features_(EventView view);
  void completed_(ErrorCode status, std::array<uint8_t, 248> name, Address address);

  void ReportRemoteNameRequestCancellationImpl(Address address);
  void actually_start_remote_name_request(
          Address address, std::unique_ptr<RemoteNameRequestBuilder> request,
          CompletionCallback on_completion,
          RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification,
          std::shared_ptr<RemoteNameCallback> on_remote_name_complete_ptr);
  void on_start_remote_name_request_status(Address address, CompletionCallback on_completion,
                                           CommandStatusView status);
  void actually_cancel_remote_name_request(Address address);
  void on_remote_host_supported_features_notification(EventView view);
  void completed(ErrorCode status, std::array<uint8_t, 248> name, Address address);
  void on_remote_name_request_complete(EventView view);
  void check_cancel_status(Address remote, CommandCompleteView complete);

  os::Handler* handler_;
  HciInterface& hci_layer_;
  acl_manager::AclScheduler& acl_scheduler_;

  struct RemoteNameCallbacks {
    RemoteNameCallback on_remote_name_complete;
    RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification;
  };
  std::map<Address, RemoteNameCallbacks> requests_;

  // TODO(b/445714747): Remove when rnr_multiple_name_request is shipped
  bool pending_ = false;
  RemoteHostSupportedFeaturesCallback on_remote_host_supported_features_notification_;
  RemoteNameCallback on_remote_name_complete_;
};

}  // namespace hci
}  // namespace bluetooth
