/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include "sniff_offload.h"

#include <algorithm>
#include <cstdint>
#include <format>

#include "bluetooth/log.h"
#include "common/bind.h"
#include "sniff_offload_config_reader.h"
#include "sniff_offload_structs.h"
#include "sniff_offload_vsc_sender.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/main_thread.h"

namespace bluetooth {
namespace sniff_offload {

class SniffOffloadImpl : public SniffOffload,
                         public std::enable_shared_from_this<SniffOffloadImpl> {
public:
  SniffOffloadImpl(SniffConfigReader* config_reader, SniffOffloadVscSender* vsc_sender,
                   std::chrono::milliseconds update_delay);

  ~SniffOffloadImpl() override;

  // Public Interface
  void OnProfileStateChanged(uint16_t connection_handle, uint8_t profile_id, uint8_t app_id,
                             ProfileState status) override;
  void Start(uint16_t subrating_max_latency, uint16_t subrating_min_remote_timeout,
             uint16_t subrating_min_local_timeout, bool suppress_mode_change_event,
             bool suppress_subrating_event,
             std::shared_ptr<sniff_offload::SniffOffloadCallbacks> callbacks) override;

private:
  static constexpr uint16_t kDefaultSendParametersTimeoutMs = 100;
  static constexpr uint16_t kDefaultInitIdleTimeout = 0;
  static constexpr uint16_t kAllAppId = 0xFF;

  // Valid range of Sniff Max Interval is 0x0002 to 0xFFFE as per
  // Bluetooth Core Spec. V6.0, Vol 4, Part E, 7.2.2
  // Sniff Offload shall use 0x0001 to select Prefer-Active operation mode.
  static constexpr uint16_t kDefaultSniffMaxInterval = 0x0001;

  // Manages the state for a single connection handle.
  class LinkStateManager {
  public:
    LinkStateManager(SniffOffloadImpl* owner, uint16_t handle,
                     std::chrono::milliseconds update_delay);

    // Processes a profile state change and triggers parameter updates.
    void UpdateProfileStatus(ProfileId profile_id, uint8_t app_id, ProfileState state);

    // Callback Interface to be called a WriteParameters completes.
    void ParametersUpdatedCallback(SniffOffloadParameters requested_params, tHCI_STATUS status);

  private:
    using ProfileAppKey = std::pair<ProfileId, uint8_t>;

    struct ProfileInfo {
      ProfileId profile_id_;
      ProfileState state;
      SniffOffloadConfig open_state_config;
    };

    // The function that is scheduled after a delay to process updates.
    void ProcessPendingUpdate();

    // Selects best parameters based on active profiles.
    void SelectAndUpdateParams();

    // Resets parameters to default before link removal.
    void UpdateDefaultParams();

    // Non-owning pointer to SniffOffloadImpl to post cleanup tasks.
    SniffOffloadImpl* owner_;
    uint16_t handle_;
    std::chrono::milliseconds update_delay_;

    // State for this specific link
    std::map<ProfileAppKey, ProfileInfo> active_profiles_;
    uint8_t active_sco_event_count_{0};
    SniffOffloadParameters active_link_params_;
    bool update_pending_{false};
  };

  void PerformStart(uint16_t subrating_max_latency, uint16_t subrating_min_remote_timeout,
                    uint16_t subrating_min_local_timeout, bool suppress_mode_change_event,
                    bool suppress_subrating_event);
  void SniffOffloadStartedCallback(tHCI_STATUS status);
  void HandleProfileStateChange(uint16_t handle, uint8_t profile_id, uint8_t app_id,
                                ProfileState state);

  /* Methods for LinkStateManager to access dependencies */

  // Function to help LinkStateManager read sniff config
  SniffOffloadConfig PerformReadSniffConfig(ProfileId profile_id, uint8_t app_id,
                                            ProfileState state);

  // Function to help LinkStateManager do write of sniff offload parameters
  void PerformWriteParameters(uint16_t handle, const SniffOffloadParameters& params);

  // Function to help LinkStateManager call the callback of individual connection parameters update
  void NotifyParametersUpdated(uint16_t handle, const SniffOffloadParameters& params,
                               tHCI_STATUS status);

  // Function to help LinkStateManager get the default parameters for all the links
  SniffOffloadParameters GetDefaultParams();

  // Callback function that the SniffOffloadImpl supplies to vsc sender while
  // doing WriteSniffOffloadParameters
  void HandleWriteParametersComplete(uint16_t handle, SniffOffloadParameters requested_params,
                                     tHCI_STATUS status);

  // Called by LinkStateManager when it is ready to be destroyed
  void CleanupLink(uint16_t handle);

  // Interfaces
  std::shared_ptr<SniffOffloadCallbacks> callbacks_;
  SniffConfigReader* config_reader_;
  SniffOffloadVscSender* vsc_interface_;

  // Common settings for all the links
  std::chrono::milliseconds sniff_offload_update_delay_{kDefaultSendParametersTimeoutMs};
  SniffOffloadParameters default_params_;

  // Per-link state managers.
  std::unordered_map<uint16_t, LinkStateManager> link_states_;

  bool sniff_offload_started_ = false;
};

// Implementation of SniffOffloadImpl
SniffOffloadImpl::SniffOffloadImpl(SniffConfigReader* config_reader,
                                   SniffOffloadVscSender* vsc_sender,
                                   std::chrono::milliseconds update_delay)
    : config_reader_(config_reader),
      vsc_interface_(vsc_sender),
      sniff_offload_update_delay_(update_delay) {}

// Destructor must be defined here where LinkStateManager is a complete type.
SniffOffloadImpl::~SniffOffloadImpl() = default;

void SniffOffloadImpl::OnProfileStateChanged(uint16_t handle, uint8_t profile_id,
                                             [[maybe_unused]] uint8_t app_id, ProfileState state) {
  if (!sniff_offload_started_) {
    log::error("Update profile event ignored: sniff offload is not started.");
    return;
  }
  do_in_main_thread(base::BindOnce(&SniffOffloadImpl::HandleProfileStateChange,
                                   base::Unretained(this), handle, profile_id, app_id, state));
}

void SniffOffloadImpl::HandleProfileStateChange(uint16_t handle, uint8_t profile_id, uint8_t app_id,
                                                ProfileState state) {
  // Find or create the state manager for this link.
  auto curr_link = link_states_.find(handle);
  if (curr_link == link_states_.end()) {
    log::info("No LinkStateManager for handle {:#06x}, creating a new one.", handle);
    curr_link =
            link_states_
                    .emplace(handle, LinkStateManager(this, handle, sniff_offload_update_delay_))
                    .first;
  }

  // Delegate the work to the specific LinkStateManager instance.
  curr_link->second.UpdateProfileStatus(static_cast<ProfileId>(profile_id), app_id, state);
}

void SniffOffloadImpl::CleanupLink(uint16_t handle) {
  log::info("Cleaning up and erasing LinkStateManager for handle {}.", handle);
  link_states_.erase(handle);
}

void SniffOffloadImpl::Start(uint16_t subrating_max_latency, uint16_t subrating_min_remote_timeout,
                             uint16_t subrating_min_local_timeout, bool suppress_mode_change_event,
                             bool suppress_subrating_event,
                             std::shared_ptr<sniff_offload::SniffOffloadCallbacks> callbacks) {
  if (sniff_offload_started_) {
    log::error("Sniff offload already started, cannot start again.");
    return;
  }

  callbacks_ = callbacks;

  do_in_main_thread(base::BindOnce(&SniffOffloadImpl::PerformStart, base::Unretained(this),
                                   subrating_max_latency, subrating_min_remote_timeout,
                                   subrating_min_local_timeout, suppress_mode_change_event,
                                   suppress_subrating_event));
}

void SniffOffloadImpl::PerformStart(uint16_t subrating_max_latency,
                                    uint16_t subrating_min_remote_timeout,
                                    uint16_t subrating_min_local_timeout,
                                    bool suppress_mode_change_event,
                                    bool suppress_subrating_event) {
  log::info("PerformStart(). Suppress Events: Mode Change: {}, Subrating: {}",
            suppress_mode_change_event, suppress_subrating_event);

  // Prefer-Active operation mode is the default handling for an ACL, so we should
  // retain the sniff-subrating parameters from the one requested in 'start' operation.
  // The allow_exit_sniff flags are implicitly true in this operation mode as a matter of
  // specification. Since these are the parameters before WriteSniffOffloadParameters is
  // ever issued to controller for an ACL, these are the parameters the link should be
  // reverted to when all the profiles have indicated that they have closed.
  default_params_ = {
          .sniff_max_interval = kDefaultSniffMaxInterval,
          .sniff_min_interval = 0,
          .sniff_attempts = 0,
          .sniff_timeout = 0,
          .link_idle_timeout = 0,
          .subrate_max_latency = subrating_max_latency,
          .min_remote_timeout = subrating_min_remote_timeout,
          .min_local_timeout = subrating_min_local_timeout,
          .allow_exit_on_rx = true,
          .allow_exit_on_tx = true,
  };

  // Actually send the VSC to enable Sniff Offload
  vsc_interface_->WriteSniffOffloadEnable(
          subrating_max_latency, subrating_min_remote_timeout, subrating_min_local_timeout,
          suppress_mode_change_event, suppress_subrating_event,
          [weak_this = weak_from_this()](tHCI_STATUS status) {
            auto weak_this_ptr = weak_this.lock();
            if (weak_this_ptr) {
              weak_this_ptr->SniffOffloadStartedCallback(status);
            } else {
              log::warn("SniffOffloadImpl has been destroyed before callback executed.");
            }
          });
}

void SniffOffloadImpl::SniffOffloadStartedCallback(tHCI_STATUS status) {
  if (status == tHCI_STATUS::HCI_SUCCESS) {
    sniff_offload_started_ = true;
  } else {
    log::error("Sniff Offload Start Failed");
  }
  callbacks_->OnSniffOffloadStarted(status);
}

SniffOffloadConfig SniffOffloadImpl::PerformReadSniffConfig(ProfileId profile_id, uint8_t app_id,
                                                            ProfileState state) {
  return config_reader_->ReadSniffConfig(profile_id, app_id, state);
}

void SniffOffloadImpl::PerformWriteParameters(uint16_t handle,
                                              const SniffOffloadParameters& params) {
  vsc_interface_->WriteSniffOffloadParameters(
          handle, params,
          [weak_this = weak_from_this(), handle, params](uint16_t, tHCI_STATUS status) {
            auto weak_this_ptr = weak_this.lock();
            if (weak_this_ptr) {
              weak_this_ptr->HandleWriteParametersComplete(handle, params, status);
            } else {
              log::warn("SniffOffloadImpl has been destroyed before callback executed.");
            }
          });
}

void SniffOffloadImpl::HandleWriteParametersComplete(uint16_t handle,
                                                     SniffOffloadParameters requested_params,
                                                     tHCI_STATUS status) {
  auto it = link_states_.find(handle);
  if (it == link_states_.end()) {
    log::warn("Received HandleWriteParametersComplete callback for a removed link handle {:#06x}",
              handle);
    return;
  }
  it->second.ParametersUpdatedCallback(requested_params, status);
}

void SniffOffloadImpl::NotifyParametersUpdated(uint16_t handle,
                                               const SniffOffloadParameters& params,
                                               tHCI_STATUS status) {
  callbacks_->OnLinkParamsUpdated(handle, params, status);
}

SniffOffloadParameters SniffOffloadImpl::GetDefaultParams() {
  return default_params_;
}

// Implementation of SniffOffloadImpl::LinkStateManager
SniffOffloadImpl::LinkStateManager::LinkStateManager(SniffOffloadImpl* owner, uint16_t handle,
                                                     std::chrono::milliseconds update_delay)
    : owner_(owner), handle_(handle), update_delay_(update_delay) {
  log::info("Created LinkStateManager for handle {}", handle);
  active_link_params_ = owner_->GetDefaultParams();
}

void SniffOffloadImpl::LinkStateManager::UpdateProfileStatus(ProfileId profile_id, uint8_t app_id,
                                                             ProfileState state) {
  log::info("LinkStateManager[{:#06x}]: Updating profile {} to state {}", handle_,
            static_cast<uint8_t>(profile_id), bta_sys_conn_status_text(state));

  // Update SCO counter
  if (state == ProfileState::BTA_SYS_SCO_OPEN) {
    active_sco_event_count_++;
  } else if (state == ProfileState::BTA_SYS_SCO_CLOSE) {
    if (active_sco_event_count_ > 0) {
      active_sco_event_count_--;
    } else {
      log::warn("LinkStateManager[{:#06x}]: Unmatched SCO_CLOSE event", handle_);
    }
  }

  // Update or remove profile/app info
  ProfileAppKey key = std::make_pair(profile_id, app_id);
  if (state == ProfileState::BTA_SYS_CONN_CLOSE) {
    active_profiles_.erase(key);
  } else {
    auto curr_profile = active_profiles_.find(key);
    if (curr_profile == active_profiles_.end()) {
      // We have no entry for the profile, create one
      active_profiles_[key] =
              ProfileInfo{key.first, state, {.priority_ = Priority::kNoPriority}};
    } else {
      auto profile_info = active_profiles_[key];
      profile_info.state = state;
      active_profiles_[key] = profile_info;
    }

    if (state == ProfileState::BTA_SYS_CONN_OPEN) {
      active_profiles_[key].open_state_config =
              owner_->PerformReadSniffConfig(key.first, key.second, state);
    }
  }

  // If this was the last profile, mark this instance for eventual removal.
  if (active_profiles_.empty()) {
    log::info("LinkStateManager[{:#06x}]: No active profiles left, this entry might be removed.",
              handle_);
    if (active_sco_event_count_ != 0) {
      log::warn("LinkStateManager[{:#06x}]: SCO count is {} after all profiles closed.", handle_,
                active_sco_event_count_);
    }
  }

  // Debounce update requests.
  if (!update_pending_) {
    log::info("LinkStateManager[{:#06x}]: Scheduling parameter update.", handle_);
    update_pending_ = true;
    do_in_main_thread_delayed(
            base::BindOnce(&LinkStateManager::ProcessPendingUpdate, base::Unretained(this)),
            update_delay_);
  } else {
    log::info("LinkStateManager[{:#06x}]: Parameter update already pending.", handle_);
  }
}

void SniffOffloadImpl::LinkStateManager::ProcessPendingUpdate() {
  update_pending_ = false;
  log::info("LinkStateManager[{:#06x}]: Processing pending update. active_profiles_.empty() : {}",
            handle_, active_profiles_.empty());

  if (active_profiles_.empty()) {
    // If we're removing this link, we must reset its params to default.
    if (active_link_params_ != owner_->GetDefaultParams()) {
      UpdateDefaultParams();
    } else {
      // Already at default, this can be cleaned up immediately.
      log::info("LinkStateManager[{:#06x}]: Already at default params. Cleaning up.", handle_);
      owner_->CleanupLink(handle_);
    }
  } else {
    // Standard update: select the best params for the active profiles.
    SelectAndUpdateParams();
  }
}

void SniffOffloadImpl::LinkStateManager::SelectAndUpdateParams() {
  static constexpr uint16_t kDefaultInitIdleTimeout = 0;
  uint16_t idle_timeout = kDefaultInitIdleTimeout;
  Priority priority = Priority::kNoPriority;
  SniffOffloadParameters new_params = owner_->GetDefaultParams();
  std::vector<SniffOffloadConfig> active_configs;
  bool all_allow_subrating = true;

  for (const auto& [key, info] : active_profiles_) {
    auto read_config = owner_->PerformReadSniffConfig(key.first, key.second, info.state);

    log::verbose("profile_id = {}, app_id = {}, state = {}", key.first, key.second, info.state);

    if (read_config.priority_ == Priority::kNoPriority) {
      log::verbose("Priority yielded is no priority. Read Open Config Priority{}",
                   static_cast<uint8_t>(active_profiles_[key].open_state_config.priority_));

      read_config = info.open_state_config;
    }

    log::verbose("Read priority taken in consideration. {}",
                 static_cast<uint8_t>(read_config.priority_));

    if (read_config.priority_ > priority) {
      priority = read_config.priority_;
      new_params = read_config.parameters_;
    }

    if (read_config.priority_ != Priority::kNoPriority) {
      active_configs.push_back(read_config);
      all_allow_subrating = (all_allow_subrating && read_config.allow_subrating_update_);
    }

    idle_timeout = std::max(idle_timeout, read_config.parameters_.link_idle_timeout);
    all_allow_subrating = (all_allow_subrating && read_config.allow_subrating_update_);
  }

  // Idle timeout should only be updated if it has increased
  // to a non-zero value after comparison of existing profiles.
  // else it is better for it to remain at default
  if (idle_timeout > kDefaultInitIdleTimeout) {
    new_params.link_idle_timeout = idle_timeout;
  }

  log::verbose("Active configurations count = {}.",  active_configs.size());
  if (all_allow_subrating && (active_configs.size() > 0)) {
    // Only start a comparison of subrating parameters if none prohibits
    const SniffOffloadConfig* min_subrate_config = nullptr;
    min_subrate_config = &active_configs[0];
    for (const auto& config : active_configs) {
      if (config.parameters_.subrate_max_latency <
          min_subrate_config->parameters_.subrate_max_latency) {
        min_subrate_config = &config;
      }
    }
    // Update the subrate parameters to that of minimum latency config
    new_params.subrate_max_latency = min_subrate_config->parameters_.subrate_max_latency;
    new_params.min_remote_timeout = min_subrate_config->parameters_.min_remote_timeout;
    new_params.min_local_timeout = min_subrate_config->parameters_.min_local_timeout;
  } else {
    // If any prohibits changing subrate parameters, only revert subrating parameters to original
    new_params.subrate_max_latency = active_link_params_.subrate_max_latency;
    new_params.min_remote_timeout = active_link_params_.min_remote_timeout;
    new_params.min_local_timeout = active_link_params_.min_local_timeout;
  }

  log::verbose(" comparison");
  log::verbose("  new_params = {}", new_params.ToString());
  log::verbose("   vs ");
  log::verbose("  active_link_params_ = {}", active_link_params_.ToString());

  if (new_params == active_link_params_) {
    log::info("LinkStateManager[{:#06x}]: Parameters unchanged. NOP.", handle_);
    return;
  }

  log::info("LinkStateManager[{:#06x}]: Writing new params: {}", handle_, new_params.ToString());
  owner_->PerformWriteParameters(handle_, new_params);
}

void SniffOffloadImpl::LinkStateManager::UpdateDefaultParams() {
  log::info("LinkStateManager[{:#06x}]: Resetting link to default parameters before removal.",
            handle_);
  owner_->PerformWriteParameters(handle_, owner_->GetDefaultParams());
}

void SniffOffloadImpl::LinkStateManager::ParametersUpdatedCallback(
        SniffOffloadParameters requested_params, tHCI_STATUS status) {
  if (status == HCI_SUCCESS) {
    log::info("LinkStateManager[{:#06x}]: Successfully updated params.", handle_);
    active_link_params_ = requested_params;
  } else {
    log::error("LinkStateManager[{:#06x}]: Failed to update params, status {}.", handle_, status);
  }

  owner_->NotifyParametersUpdated(handle_, requested_params, status);

  // If this callback was for the final "reset to default", this should now be destroyed.
  if (active_profiles_.empty()) {
    log::info("LinkStateManager[{:#06x}]: Final update complete. Cleaning up.", handle_);
    owner_->CleanupLink(handle_);
  }
}

std::shared_ptr<SniffOffload> GetSniffOffloadInstance(SniffConfigReader* config_reader,
                                                      SniffOffloadVscSender* vsc_sender,
                                                      std::chrono::milliseconds update_delay) {
  return std::make_shared<SniffOffloadImpl>(config_reader, vsc_sender, update_delay);
}

}  // namespace sniff_offload
}  // namespace bluetooth
