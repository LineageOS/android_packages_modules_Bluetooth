/*
 * Copyright 2019 The Android Open Source Project
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
#define LOG_TAG "bt_gd_neigh"

#include "neighbor/discoverability.h"

#include <bluetooth/log.h>

#include <memory>

#include "common/bind.h"
#include "hci/hci_layer.h"
#include "hci/hci_packets.h"
#include "module.h"
#include "neighbor/scan.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace neighbor {

static constexpr uint8_t kGeneralInquiryAccessCode = 0x33;
static constexpr uint8_t kLimitedInquiryAccessCode = 0x00;

struct DiscoverabilityModule::impl {
  void StartDiscoverability(std::vector<hci::Lap>& laps);
  void StopDiscoverability();

  bool IsGeneralDiscoverabilityEnabled() const;
  bool IsLimitedDiscoverabilityEnabled() const;

  void Start();

  impl(DiscoverabilityModule& discoverability_module);

 private:
  uint8_t num_supported_iac_;
  std::vector<hci::Lap> laps_;

  void OnCommandComplete(hci::CommandCompleteView status);

  hci::HciLayer* hci_layer_;
  neighbor::ScanModule* scan_module_;
  os::Handler* handler_;

  DiscoverabilityModule& module_;
  void Dump() const;
};

const ModuleFactory neighbor::DiscoverabilityModule::Factory =
    ModuleFactory([]() { return new neighbor::DiscoverabilityModule(); });

neighbor::DiscoverabilityModule::impl::impl(neighbor::DiscoverabilityModule& module) : module_(module) {}

void neighbor::DiscoverabilityModule::impl::OnCommandComplete(hci::CommandCompleteView status) {
  switch (status.GetCommandOpCode()) {
    case hci::OpCode::READ_CURRENT_IAC_LAP: {
      auto packet = hci::ReadCurrentIacLapCompleteView::Create(status);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      laps_ = packet.GetLapsToRead();
    } break;

    case hci::OpCode::WRITE_CURRENT_IAC_LAP: {
      auto packet = hci::WriteCurrentIacLapCompleteView::Create(status);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
    } break;

    case hci::OpCode::READ_NUMBER_OF_SUPPORTED_IAC: {
      auto packet = hci::ReadNumberOfSupportedIacCompleteView::Create(status);
      log::assert_that(packet.IsValid(), "assert failed: packet.IsValid()");
      log::assert_that(
          packet.GetStatus() == hci::ErrorCode::SUCCESS,
          "assert failed: packet.GetStatus() == hci::ErrorCode::SUCCESS");
      num_supported_iac_ = packet.GetNumSupportIac();
    } break;
    default:
      log::warn("Unhandled command:{}", hci::OpCodeText(status.GetCommandOpCode()));
      break;
  }
}

void neighbor::DiscoverabilityModule::impl::StartDiscoverability(std::vector<hci::Lap>& laps) {
  log::assert_that(
      laps.size() <= num_supported_iac_, "assert failed: laps.size() <= num_supported_iac_");
  hci_layer_->EnqueueCommand(
      hci::WriteCurrentIacLapBuilder::Create(laps), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  hci_layer_->EnqueueCommand(
      hci::ReadCurrentIacLapBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  scan_module_->SetInquiryScan();
}

void neighbor::DiscoverabilityModule::impl::StopDiscoverability() {
  scan_module_->ClearInquiryScan();
}

bool neighbor::DiscoverabilityModule::impl::IsGeneralDiscoverabilityEnabled() const {
  return scan_module_->IsInquiryEnabled() && laps_.size() == 1;
}

bool neighbor::DiscoverabilityModule::impl::IsLimitedDiscoverabilityEnabled() const {
  return scan_module_->IsInquiryEnabled() && laps_.size() == 2;
}

void neighbor::DiscoverabilityModule::impl::Start() {
  hci_layer_ = module_.GetDependency<hci::HciLayer>();
  scan_module_ = module_.GetDependency<neighbor::ScanModule>();
  handler_ = module_.GetHandler();

  hci_layer_->EnqueueCommand(
      hci::ReadCurrentIacLapBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));

  hci_layer_->EnqueueCommand(
      hci::ReadNumberOfSupportedIacBuilder::Create(), handler_->BindOnceOn(this, &impl::OnCommandComplete));
  log::info("Started discoverability module");
}

void neighbor::DiscoverabilityModule::impl::Dump() const {
  log::info("Number of supported iacs:{}", num_supported_iac_);
  log::info("Number of current iacs:{}", laps_.size());
  for (auto it : laps_) {
    log::info("discoverability lap:{:x}", it.lap_);
  }
}

neighbor::DiscoverabilityModule::DiscoverabilityModule() : pimpl_(std::make_unique<impl>(*this)) {}

neighbor::DiscoverabilityModule::~DiscoverabilityModule() {
  pimpl_.reset();
}

void neighbor::DiscoverabilityModule::StartGeneralDiscoverability() {
  std::vector<hci::Lap> laps;
  {
    hci::Lap lap;
    lap.lap_ = kGeneralInquiryAccessCode;
    laps.push_back(lap);
  }
  pimpl_->StartDiscoverability(laps);
}

void neighbor::DiscoverabilityModule::StartLimitedDiscoverability() {
  std::vector<hci::Lap> laps;
  {
    hci::Lap lap;
    lap.lap_ = kGeneralInquiryAccessCode;
    laps.push_back(lap);
  }

  {
    hci::Lap lap;
    lap.lap_ = kLimitedInquiryAccessCode;
    laps.push_back(lap);
  }
  pimpl_->StartDiscoverability(laps);
}

void neighbor::DiscoverabilityModule::StopDiscoverability() {
  pimpl_->StopDiscoverability();
}

bool neighbor::DiscoverabilityModule::IsGeneralDiscoverabilityEnabled() const {
  return pimpl_->IsGeneralDiscoverabilityEnabled();
}

bool neighbor::DiscoverabilityModule::IsLimitedDiscoverabilityEnabled() const {
  return pimpl_->IsLimitedDiscoverabilityEnabled();
}

/**
 * Module stuff
 */
void neighbor::DiscoverabilityModule::ListDependencies(ModuleList* list) const {
  list->add<hci::HciLayer>();
  list->add<neighbor::ScanModule>();
}

void neighbor::DiscoverabilityModule::Start() {
  pimpl_->Start();
}

void neighbor::DiscoverabilityModule::Stop() {}

}  // namespace neighbor
}  // namespace bluetooth
