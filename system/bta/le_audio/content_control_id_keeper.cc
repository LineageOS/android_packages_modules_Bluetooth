/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "content_control_id_keeper.h"

#include <base/logging.h>
#include <bluetooth/log.h>

#include <bitset>
#include <map>

#include "common/strings.h"
#include "le_audio_types.h"
#include "os/log.h"

namespace {

using bluetooth::common::ToString;
using bluetooth::le_audio::types::LeAudioContextType;

}  // namespace

namespace bluetooth::le_audio {
struct ccid_keeper {
 public:
  ccid_keeper() {}

  ~ccid_keeper() {}

  void SetCcid(types::LeAudioContextType context_type, int ccid) {
    if (context_type >= LeAudioContextType::RFU) {
      log::error("Unknownd context type {}", ToString(context_type));
      return;
    }

    log::debug("Ccid: {}, context type {}", ccid, ToString(context_type));
    ccids_.insert_or_assign(context_type, ccid);
  }

  void SetCcid(const types::AudioContexts& contexts, int ccid) {
    if (contexts.none()) {
      RemoveCcid(ccid);
      return;
    }

    for (auto ctx : types::kLeAudioContextAllTypesArray) {
      if (contexts.test(ctx)) SetCcid(ctx, ccid);
    }
  }

  void RemoveCcid(int ccid) {
    log::debug("Ccid: {}", ccid);

    auto iter = ccids_.begin();
    while (iter != ccids_.end()) {
      if (iter->second == ccid) {
        iter = ccids_.erase(iter);
      } else {
        ++iter;
      }
    }
  }

  int GetCcid(types::LeAudioContextType context_type) const {
    if (context_type >= LeAudioContextType::RFU) {
      log::error("Unknownd context type {}", ToString(context_type));
      return -1;
    }

    if (ccids_.count(context_type) == 0) {
      log::debug("No CCID for context {}", ToString(context_type));
      return -1;
    }

    return ccids_.at(context_type);
  }

 private:
  /* Ccid informations */
  std::map<LeAudioContextType /* context */, int /*ccid */> ccids_;
};

struct ContentControlIdKeeper::impl {
  impl(const ContentControlIdKeeper& ccid_keeper) : ccid_keeper_(ccid_keeper) {}

  void Start() {
    LOG_ASSERT(!ccid_keeper_impl_);
    ccid_keeper_impl_ = std::make_unique<ccid_keeper>();
  }

  void Stop() {
    LOG_ASSERT(ccid_keeper_impl_);
    ccid_keeper_impl_.reset();
  }

  bool IsRunning() { return ccid_keeper_impl_ ? true : false; }

  const ContentControlIdKeeper& ccid_keeper_;
  std::unique_ptr<ccid_keeper> ccid_keeper_impl_;
};

ContentControlIdKeeper::ContentControlIdKeeper()
    : pimpl_(std::make_unique<impl>(*this)) {}

void ContentControlIdKeeper::Start() {
  if (!pimpl_->IsRunning()) pimpl_->Start();
}

void ContentControlIdKeeper::Stop() {
  if (pimpl_->IsRunning()) pimpl_->Stop();
}

int ContentControlIdKeeper::GetCcid(
    types::LeAudioContextType context_type) const {
  if (!pimpl_->IsRunning()) {
    return -1;
  }

  return pimpl_->ccid_keeper_impl_->GetCcid(context_type);
}

void ContentControlIdKeeper::SetCcid(types::LeAudioContextType context_type,
                                     int ccid) {
  if (pimpl_->IsRunning()) {
    if (context_type == types::LeAudioContextType::UNINITIALIZED) {
      pimpl_->ccid_keeper_impl_->RemoveCcid(ccid);
    } else {
      pimpl_->ccid_keeper_impl_->SetCcid(context_type, ccid);
    }
  }
}

void ContentControlIdKeeper::SetCcid(const types::AudioContexts& contexts,
                                     int ccid) {
  if (pimpl_->IsRunning()) pimpl_->ccid_keeper_impl_->SetCcid(contexts, ccid);
}

std::vector<uint8_t> ContentControlIdKeeper::GetAllCcids(
    const types::AudioContexts& contexts) const {
  std::vector<uint8_t> ccid_vec;
  for (LeAudioContextType context : types::kLeAudioContextAllTypesArray) {
    if (!contexts.test(context)) continue;
    auto ccid = GetCcid(context);
    if (ccid != -1) {
      // Remove duplicates in case more than one context maps to the same CCID
      if (std::find(ccid_vec.begin(), ccid_vec.end(), ccid) == ccid_vec.end()) {
        ccid_vec.push_back(static_cast<uint8_t>(ccid));
      }
    }
  }

  return ccid_vec;
}

}  // namespace bluetooth::le_audio
