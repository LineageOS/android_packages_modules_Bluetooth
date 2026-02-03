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

#include <memory>
#include <utility>
#include <vector>

#include "common/bidi_queue.h"
#include "common/contextual_callback.h"
#include "hci/acl_connection_interface.h"
#include "hci/address.h"
#include "hci/class_of_device.h"
#include "hci/classic_acl_data_consumer.h"
#include "hci/distance_measurement_interface.h"
#include "hci/hci_packets.h"
#include "hci/inquiry_interface.h"
#include "hci/le_acl_connection_interface.h"
#include "hci/le_acl_data_consumer.h"
#include "hci/le_advertising_interface.h"
#include "hci/le_iso_interface.h"
#include "hci/le_scanning_interface.h"
#include "hci/le_security_interface.h"
#include "hci/security_interface.h"

namespace bluetooth {
namespace hci {

class HciInterface : public CommandInterface<CommandBuilder> {
public:
  HciInterface() = default;
  virtual ~HciInterface() = default;

  void EnqueueCommand(
          std::unique_ptr<CommandBuilder> command,
          common::ContextualOnceCallback<void(CommandCompleteView)> on_complete) override = 0;

  void EnqueueCommand(std::unique_ptr<CommandBuilder> command,
                      common::ContextualOnceCallback<void(CommandStatusView)> on_status) override =
          0;

  void EnqueueCommand(std::unique_ptr<CommandBuilder> command,
                      common::ContextualOnceCallback<void(CommandStatusOrCompleteView)>
                              on_status_or_complete) override = 0;

  virtual common::BidiQueueEnd<AclBuilder, AclView>* GetAclQueueEnd() = 0;

  virtual common::BidiQueueEnd<ScoBuilder, ScoView>* GetScoQueueEnd() = 0;

  virtual common::BidiQueueEnd<IsoBuilder, IsoView>* GetIsoQueueEnd() = 0;

  virtual void RegisterEventHandler(EventCode event_code,
                                    common::ContextualCallback<void(EventView)> event_handler) = 0;

  virtual void UnregisterEventHandler(EventCode event_code) = 0;

  virtual void RegisterDefaultVendorSpecificEventHandler(
          common::ContextualCallback<void(VendorSpecificEventView)> handler) = 0;

  virtual void UnregisterDefaultVendorSpecificEventHandler() = 0;

  virtual void RegisterLeEventHandler(
          SubeventCode subevent_code,
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual void UnregisterLeEventHandler(SubeventCode subevent_code) = 0;

  virtual void RegisterDevelopmentEventHandler(
          DevelopmentSubeventCode subevent_code,
          common::ContextualCallback<void(DevelopmentEventView)> event_handler) = 0;

  virtual void UnregisterDevelopmentEventHandler(DevelopmentSubeventCode subevent_code) = 0;

  virtual void RegisterVendorSpecificEventHandler(
          VseSubeventCode subevent_code,
          common::ContextualCallback<void(VendorSpecificEventView)> event_handler) = 0;

  virtual void UnregisterVendorSpecificEventHandler(VseSubeventCode subevent_code) = 0;

  virtual void SetVendorAclHandleRange(uint16_t min, uint16_t max) = 0;

  virtual void RegisterVendorSpecificAclHandler(
          common::ContextualCallback<void(uint16_t, std::vector<uint8_t>)> handler) = 0;

  virtual void UnregisterVendorSpecificAclHandler() = 0;

  virtual void RegisterForDisconnects(
          common::ContextualCallback<void(uint16_t, hci::ErrorCode)> on_disconnect) = 0;

  virtual SecurityInterface* GetSecurityInterface(
          common::ContextualCallback<void(EventView)> event_handler) = 0;

  virtual LeSecurityInterface* GetLeSecurityInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual AclConnectionInterface* GetAclConnectionInterface(
          common::ContextualCallback<void(EventView)> event_handler,
          common::ContextualCallback<void(uint16_t, hci::ErrorCode)> on_disconnect,
          common::ContextualCallback<void(Address, ClassOfDevice)> on_connection_request,
          common::ContextualCallback<void(hci::ErrorCode, uint16_t, uint8_t, uint16_t, uint16_t)>
                  on_read_remote_version_complete) = 0;
  virtual void PutAclConnectionInterface() = 0;

  virtual LeAclConnectionInterface* GetLeAclConnectionInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler,
          common::ContextualCallback<void(uint16_t, hci::ErrorCode)> on_disconnect,
          common::ContextualCallback<void(hci::ErrorCode, uint16_t, uint8_t, uint16_t, uint16_t)>
                  on_read_remote_version_complete) = 0;
  virtual void PutLeAclConnectionInterface() = 0;

  virtual LeAdvertisingInterface* GetLeAdvertisingInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual void ReleaseLeAdvertisingInterface() = 0;

  virtual LeScanningInterface* GetLeScanningInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual void ReleaseLeScanningInterface() = 0;

  virtual void RegisterForScoConnectionRequests(
          common::ContextualCallback<void(Address, ClassOfDevice, ConnectionRequestLinkType)>
                  on_sco_connection_request) = 0;

  virtual LeIsoInterface* GetLeIsoInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual DistanceMeasurementInterface* GetDistanceMeasurementInterface(
          common::ContextualCallback<void(LeMetaEventView)> event_handler) = 0;

  virtual void ReleaseDistanceMeasurementInterface() = 0;

  virtual std::unique_ptr<InquiryInterface> GetInquiryInterface(
          common::ContextualCallback<void(EventView)> event_handler) = 0;

  virtual void SetLeAclDataConsumer(LeAclDataConsumer* le_acl_data_consumer) = 0;

  virtual void SetClassicAclDataConsumer(ClassicAclDataConsumer* classic_acl_data_consumer) = 0;
};

}  // namespace hci
}  // namespace bluetooth
