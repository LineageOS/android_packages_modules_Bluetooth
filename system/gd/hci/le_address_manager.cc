/*
 * Copyright 2020 The Android Open Source Project
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

#include "hci/le_address_manager.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include <ctime>

#include "hci/controller.h"
#include "hci/octets.h"
#include "include/macros.h"
#include "os/rand.h"

// TODO(b/378143579) For peer address not in resolving list

namespace bluetooth {
namespace hci {

static constexpr uint8_t BLE_ADDR_MASK = 0xc0u;

enum class LeAddressManager::ClientState {
  WAITING_FOR_PAUSE,
  PAUSED,
  WAITING_FOR_RESUME,
  RESUMED,
};

std::string LeAddressManager::ClientStateText(const ClientState cs) {
  switch (cs) {
    CASE_RETURN_STRING(ClientState::WAITING_FOR_PAUSE);
    CASE_RETURN_STRING(ClientState::PAUSED);
    CASE_RETURN_STRING(ClientState::WAITING_FOR_RESUME);
    CASE_RETURN_STRING(ClientState::RESUMED);
  }
  RETURN_UNKNOWN_TYPE_STRING(ClientState, cs);
}

static std::string AddressPolicyText(const LeAddressManager::AddressPolicy policy) {
  switch (policy) {
    CASE_RETURN_STRING(LeAddressManager::AddressPolicy::POLICY_NOT_SET);
    CASE_RETURN_STRING(LeAddressManager::AddressPolicy::USE_PUBLIC_ADDRESS);
    CASE_RETURN_STRING(LeAddressManager::AddressPolicy::USE_STATIC_ADDRESS);
    CASE_RETURN_STRING(LeAddressManager::AddressPolicy::USE_NON_RESOLVABLE_ADDRESS);
    CASE_RETURN_STRING(LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS);
  }
  RETURN_UNKNOWN_TYPE_STRING(LeAddressManager::AddressPolicy, policy);
}

LeAddressManager::LeAddressManager(
        common::Callback<void(std::unique_ptr<CommandBuilder>)> enqueue_command,
        os::Handler* handler, Address public_address, uint8_t accept_list_size,
        uint8_t resolving_list_size, Controller* controller)
    : enqueue_command_(enqueue_command),
      handler_(handler),
      public_address_(public_address),
      accept_list_size_(accept_list_size),
      resolving_list_size_(resolving_list_size),
      controller_(controller) {}

LeAddressManager::~LeAddressManager() {
  if (address_rotation_wake_alarm_ != nullptr) {
    address_rotation_wake_alarm_->Cancel();
    address_rotation_wake_alarm_.reset();
  }
  if (address_rotation_non_wake_alarm_ != nullptr) {
    address_rotation_non_wake_alarm_->Cancel();
    address_rotation_non_wake_alarm_.reset();
  }
  if (address_rotation_interval_min.has_value()) {
    address_rotation_interval_min.reset();
  }
  if (address_rotation_interval_max.has_value()) {
    address_rotation_interval_max.reset();
  }
}

// Called on initialization, and on IRK rotation
void LeAddressManager::SetPrivacyPolicyForInitiatorAddress(
        AddressPolicy address_policy, AddressWithType fixed_address, Octet16 rotation_irk,
        bool supports_ble_privacy, std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  // Handle repeated calls to the function for IRK rotation
  if (address_policy_ != AddressPolicy::POLICY_NOT_SET) {
    // Need to update some parameteres like IRK if privacy is supported
    if (supports_ble_privacy) {
      log::info("Updating rotation parameters.");
      handler_->CallOn(
              this, &LeAddressManager::prepare_to_update_irk,
              UpdateIRKCommand{rotation_irk, minimum_rotation_time, maximum_rotation_time});
    }
    return;
  }
  log::assert_that(address_policy_ == AddressPolicy::POLICY_NOT_SET,
                   "assert failed: address_policy_ == AddressPolicy::POLICY_NOT_SET");
  log::assert_that(address_policy != AddressPolicy::POLICY_NOT_SET,
                   "assert failed: address_policy != AddressPolicy::POLICY_NOT_SET");
  log::assert_that(registered_clients_.empty(),
                   "Policy must be set before clients are registered.");
  address_policy_ = address_policy;
  supports_ble_privacy_ = supports_ble_privacy;
  log::info("New policy: {}", AddressPolicyText(address_policy));

  if (com::android::bluetooth::flags::nrpa_non_connectable_adv()) {
    minimum_rotation_time_ = minimum_rotation_time;
    maximum_rotation_time_ = maximum_rotation_time;
    log::info("minimum_rotation_time_={}ms, maximum_rotation_time_={}ms",
              minimum_rotation_time_.count(), maximum_rotation_time_.count());
  }

  switch (address_policy_) {
    case AddressPolicy::USE_PUBLIC_ADDRESS:
      le_address_ = AddressWithType(public_address_, AddressType::PUBLIC_DEVICE_ADDRESS);
      handler_->BindOnceOn(this, &LeAddressManager::resume_registered_clients)();
      break;
    case AddressPolicy::USE_STATIC_ADDRESS: {
      auto addr = fixed_address.GetAddress();
      auto address = addr.address;
      // The two most significant bits of the static address shall be equal to 1
      log::assert_that((address[5] & BLE_ADDR_MASK) == BLE_ADDR_MASK,
                       "The two most significant bits shall be equal to 1");
      // Bits of the random part of the address shall not be all 1 or all 0
      if ((address[0] == 0x00 && address[1] == 0x00 && address[2] == 0x00 && address[3] == 0x00 &&
           address[4] == 0x00 && address[5] == BLE_ADDR_MASK) ||
          (address[0] == 0xFF && address[1] == 0xFF && address[2] == 0xFF && address[3] == 0xFF &&
           address[4] == 0xFF && address[5] == 0xFF)) {
        log::fatal("Bits of the random part of the address shall not be all 1 or all 0");
      }
      le_address_ = fixed_address;
      auto packet = hci::LeSetRandomAddressBuilder::Create(le_address_.GetAddress());
      handler_->Post(common::BindOnce(enqueue_command_, std::move(packet)));
    } break;
    case AddressPolicy::USE_NON_RESOLVABLE_ADDRESS:
    case AddressPolicy::USE_RESOLVABLE_ADDRESS:
      le_address_ = fixed_address;
      rotation_irk_ = rotation_irk;
      if (!com::android::bluetooth::flags::nrpa_non_connectable_adv()) {
        minimum_rotation_time_ = minimum_rotation_time;
        maximum_rotation_time_ = maximum_rotation_time;
        log::info("minimum_rotation_time_={}ms, maximum_rotation_time_={}ms",
                  minimum_rotation_time_.count(), maximum_rotation_time_.count());
      }
      if (controller_->IsRpaGenerationSupported()) {
        auto min_seconds = std::chrono::duration_cast<std::chrono::seconds>(minimum_rotation_time_);
        auto max_seconds = std::chrono::duration_cast<std::chrono::seconds>(maximum_rotation_time_);
        log::info("Support RPA offload, set min_seconds={}s, max_seconds={}s", min_seconds.count(),
                  max_seconds.count());
        /* Default to 7 minutes minimum, 15 minutes maximum for random address refreshing;
         * device can override. */
        auto packet = hci::LeSetResolvablePrivateAddressTimeoutV2Builder::Create(
                min_seconds.count(), max_seconds.count());
        enqueue_command_.Run(std::move(packet));
      } else {
        if (com::android::bluetooth::flags::non_wake_alarm_for_rpa_rotation()) {
          address_rotation_wake_alarm_ = std::make_unique<os::Alarm>(handler_, true);
          address_rotation_non_wake_alarm_ = std::make_unique<os::Alarm>(handler_, false);
        } else {
          address_rotation_wake_alarm_ = std::make_unique<os::Alarm>(handler_);
        }
      }
      set_random_address();
      break;
    case AddressPolicy::POLICY_NOT_SET:
      log::fatal("invalid parameters");
  }
}

// TODO(jpawlowski): remove once we have config file abstraction in cert tests
void LeAddressManager::SetPrivacyPolicyForInitiatorAddressForTest(
        AddressPolicy address_policy, AddressWithType fixed_address, Octet16 rotation_irk,
        std::chrono::milliseconds minimum_rotation_time,
        std::chrono::milliseconds maximum_rotation_time) {
  log::assert_that(address_policy != AddressPolicy::POLICY_NOT_SET,
                   "assert failed: address_policy != AddressPolicy::POLICY_NOT_SET");
  log::assert_that(registered_clients_.empty(),
                   "Policy must be set before clients are registered.");
  address_policy_ = address_policy;

  switch (address_policy_) {
    case AddressPolicy::USE_PUBLIC_ADDRESS:
      le_address_ = fixed_address;
      break;
    case AddressPolicy::USE_STATIC_ADDRESS: {
      auto addr = fixed_address.GetAddress();
      auto address = addr.address;
      // The two most significant bits of the static address shall be equal to 1
      log::assert_that((address[5] & BLE_ADDR_MASK) == BLE_ADDR_MASK,
                       "The two most significant bits shall be equal to 1");
      // Bits of the random part of the address shall not be all 1 or all 0
      if ((address[0] == 0x00 && address[1] == 0x00 && address[2] == 0x00 && address[3] == 0x00 &&
           address[4] == 0x00 && address[5] == BLE_ADDR_MASK) ||
          (address[0] == 0xFF && address[1] == 0xFF && address[2] == 0xFF && address[3] == 0xFF &&
           address[4] == 0xFF && address[5] == 0xFF)) {
        log::fatal("Bits of the random part of the address shall not be all 1 or all 0");
      }
      le_address_ = fixed_address;
      auto packet = hci::LeSetRandomAddressBuilder::Create(le_address_.GetAddress());
      handler_->Call(enqueue_command_, std::move(packet));
    } break;
    case AddressPolicy::USE_NON_RESOLVABLE_ADDRESS:
    case AddressPolicy::USE_RESOLVABLE_ADDRESS:
      rotation_irk_ = rotation_irk;
      minimum_rotation_time_ = minimum_rotation_time;
      maximum_rotation_time_ = maximum_rotation_time;
      log::info("minimum_rotation_time_={}ms, maximum_rotation_time_={}ms",
                minimum_rotation_time_.count(), maximum_rotation_time_.count());
      if (controller_->IsRpaGenerationSupported()) {
        auto min_seconds = std::chrono::duration_cast<std::chrono::seconds>(minimum_rotation_time_);
        auto max_seconds = std::chrono::duration_cast<std::chrono::seconds>(maximum_rotation_time_);
        log::info("Support RPA offload, set min_seconds={}s, max_seconds={}s", min_seconds.count(),
                  max_seconds.count());
        /* Default to 7 minutes minimum, 15 minutes maximum for random address refreshing;
         * device can override. */
        auto packet = hci::LeSetResolvablePrivateAddressTimeoutV2Builder::Create(
                min_seconds.count(), max_seconds.count());
        enqueue_command_.Run(std::move(packet));
      } else {
        if (com::android::bluetooth::flags::non_wake_alarm_for_rpa_rotation()) {
          address_rotation_wake_alarm_ = std::make_unique<os::Alarm>(handler_, true);
          address_rotation_non_wake_alarm_ = std::make_unique<os::Alarm>(handler_, false);
        } else {
          address_rotation_wake_alarm_ = std::make_unique<os::Alarm>(handler_);
        }
        set_random_address();
      }
      break;
    case AddressPolicy::POLICY_NOT_SET:
      log::fatal("invalid parameters");
  }
}
LeAddressManager::AddressPolicy LeAddressManager::GetAddressPolicy() { return address_policy_; }
bool LeAddressManager::RotatingAddress() {
  return address_policy_ == AddressPolicy::USE_RESOLVABLE_ADDRESS ||
         address_policy_ == AddressPolicy::USE_NON_RESOLVABLE_ADDRESS;
}
LeAddressManager::AddressPolicy LeAddressManager::Register(LeAddressManagerCallback* callback) {
  handler_->BindOnceOn(this, &LeAddressManager::register_client, callback)();
  return address_policy_;
}

void LeAddressManager::register_client(LeAddressManagerCallback* callback) {
  registered_clients_.insert(
          std::pair<LeAddressManagerCallback*, ClientState>(callback, ClientState::RESUMED));
  if (address_policy_ == AddressPolicy::POLICY_NOT_SET) {
    log::info("address policy isn't set yet, pause clients and return");
    pause_registered_clients();
    return;
  } else if (address_policy_ == AddressPolicy::USE_RESOLVABLE_ADDRESS ||
             address_policy_ == AddressPolicy::USE_NON_RESOLVABLE_ADDRESS) {
    if (registered_clients_.size() == 1) {
      if (!controller_->IsRpaGenerationSupported()) {
        schedule_rotate_random_address();
        log::info("Scheduled address rotation for first client registered");
      }
    }
  }
  log::info("Client registered");
}

void LeAddressManager::Unregister(LeAddressManagerCallback* callback) {
  handler_->BindOnceOn(this, &LeAddressManager::unregister_client, callback)();
}

void LeAddressManager::unregister_client(LeAddressManagerCallback* callback) {
  if (registered_clients_.find(callback) != registered_clients_.end()) {
    if (registered_clients_.find(callback)->second == ClientState::WAITING_FOR_PAUSE) {
      ack_pause(callback);
    } else if (registered_clients_.find(callback)->second == ClientState::WAITING_FOR_RESUME) {
      ack_resume(callback);
    }
    registered_clients_.erase(callback);
    log::info("Client unregistered");
  }
  if (registered_clients_.empty()) {
    if (address_rotation_wake_alarm_ != nullptr) {
      address_rotation_wake_alarm_->Cancel();
    }
    if (address_rotation_non_wake_alarm_ != nullptr) {
      address_rotation_non_wake_alarm_->Cancel();
    }
    if (address_rotation_interval_min.has_value()) {
      address_rotation_interval_min.reset();
    }
    if (address_rotation_interval_max.has_value()) {
      address_rotation_interval_max.reset();
    }
    log::info("Cancelled address rotation alarm");
  }
}

bool LeAddressManager::UnregisterSync(LeAddressManagerCallback* callback,
                                      std::chrono::milliseconds timeout) {
  handler_->BindOnceOn(this, &LeAddressManager::unregister_client, callback)();
  std::promise<void> promise;
  auto future = promise.get_future();
  handler_->Post(common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)));
  return future.wait_for(timeout) == std::future_status::ready;
}

void LeAddressManager::AckPause(LeAddressManagerCallback* callback) {
  handler_->BindOnceOn(this, &LeAddressManager::ack_pause, callback)();
}

void LeAddressManager::AckResume(LeAddressManagerCallback* callback) {
  handler_->BindOnceOn(this, &LeAddressManager::ack_resume, callback)();
}

AddressWithType LeAddressManager::GetInitiatorAddress() {
  log::assert_that(address_policy_ != AddressPolicy::POLICY_NOT_SET,
                   "assert failed: address_policy_ != AddressPolicy::POLICY_NOT_SET");
  return le_address_;
}

AddressWithType LeAddressManager::NewResolvableAddress() {
  log::assert_that(RotatingAddress(), "assert failed: RotatingAddress()");
  hci::Address address = generate_rpa();
  auto random_address = AddressWithType(address, AddressType::RANDOM_DEVICE_ADDRESS);
  return random_address;
}

AddressWithType LeAddressManager::NewNonResolvableAddress() {
  if (!com::android::bluetooth::flags::nrpa_non_connectable_adv()) {
    log::assert_that(RotatingAddress(), "assert failed: RotatingAddress()");
  }
  hci::Address address = generate_nrpa();
  auto random_address = AddressWithType(address, AddressType::RANDOM_DEVICE_ADDRESS);
  return random_address;
}

void LeAddressManager::pause_registered_clients() {
  for (auto& client : registered_clients_) {
    switch (client.second) {
      case ClientState::PAUSED:
      case ClientState::WAITING_FOR_PAUSE:
        break;
      case ClientState::WAITING_FOR_RESUME:
      case ClientState::RESUMED:
        client.second = ClientState::WAITING_FOR_PAUSE;
        client.first->OnPause();
        break;
    }
  }
}

void LeAddressManager::push_command(Command command) {
  pause_registered_clients();
  cached_commands_.push(std::move(command));
}

void LeAddressManager::ack_pause(LeAddressManagerCallback* callback) {
  if (registered_clients_.find(callback) == registered_clients_.end()) {
    log::info("No clients registered to ack pause");
    return;
  }
  registered_clients_.find(callback)->second = ClientState::PAUSED;
  for (auto client : registered_clients_) {
    switch (client.second) {
      case ClientState::PAUSED:
        log::verbose("Client already in paused state");
        break;
      case ClientState::WAITING_FOR_PAUSE:
        // make sure all client paused
        log::debug("Wait all clients paused, return");
        return;
      case ClientState::WAITING_FOR_RESUME:
      case ClientState::RESUMED:
        log::warn("Trigger OnPause for client {}", ClientStateText(client.second));
        client.second = ClientState::WAITING_FOR_PAUSE;
        client.first->OnPause();
        return;
    }
  }

  if (address_policy_ != AddressPolicy::POLICY_NOT_SET) {
    check_cached_commands();
  }
}

void LeAddressManager::resume_registered_clients() {
  // Do not resume clients if cached command is not empty
  if (!cached_commands_.empty()) {
    handle_next_command();
    return;
  }

  log::info("Resuming registered clients");
  for (auto& client : registered_clients_) {
    if (client.second != ClientState::PAUSED) {
      log::warn("client is not paused {}", ClientStateText(client.second));
    }
    client.second = ClientState::WAITING_FOR_RESUME;
    client.first->OnResume();
  }
}

void LeAddressManager::ack_resume(LeAddressManagerCallback* callback) {
  if (registered_clients_.find(callback) != registered_clients_.end()) {
    registered_clients_.find(callback)->second = ClientState::RESUMED;
  } else {
    log::info("Client not registered");
  }
}

void LeAddressManager::prepare_to_rotate() {
  Command command = {CommandType::ROTATE_RANDOM_ADDRESS, RotateRandomAddressCommand{}};
  cached_commands_.push(std::move(command));
  pause_registered_clients();
}

void LeAddressManager::schedule_rotate_random_address() {
  if (com::android::bluetooth::flags::non_wake_alarm_for_rpa_rotation()) {
    std::string client_name = "LeAddressManager";
    auto privateAddressIntervalRange = GetNextPrivateAddressIntervalRange(client_name);
    address_rotation_wake_alarm_->Schedule(
            common::BindOnce(
                    []() { log::info("deadline wakeup in schedule_rotate_random_address"); }),
            privateAddressIntervalRange.max);
    address_rotation_non_wake_alarm_->Schedule(
            common::BindOnce(&LeAddressManager::prepare_to_rotate, common::Unretained(this)),
            privateAddressIntervalRange.min);

    auto now = std::chrono::system_clock::now();
    if (address_rotation_interval_min.has_value()) {
      CheckAddressRotationHappenedInExpectedTimeInterval(
              *address_rotation_interval_min, *address_rotation_interval_max, now, client_name);
    }

    // Update the expected range here.
    address_rotation_interval_min.emplace(now + privateAddressIntervalRange.min);
    address_rotation_interval_max.emplace(now + privateAddressIntervalRange.max);
  } else {
    address_rotation_wake_alarm_->Schedule(
            common::BindOnce(&LeAddressManager::prepare_to_rotate, common::Unretained(this)),
            GetNextPrivateAddressIntervalMs());
  }
}

void LeAddressManager::set_random_address() {
  if (address_policy_ != AddressPolicy::USE_RESOLVABLE_ADDRESS &&
      address_policy_ != AddressPolicy::USE_NON_RESOLVABLE_ADDRESS) {
    log::fatal("Invalid address policy!");
    return;
  }

  hci::Address address;
  if (address_policy_ == AddressPolicy::USE_RESOLVABLE_ADDRESS) {
    address = generate_rpa();
  } else {
    address = generate_nrpa();
  }
  auto packet = hci::LeSetRandomAddressBuilder::Create(address);
  enqueue_command_.Run(std::move(packet));
  cached_address_ = AddressWithType(address, AddressType::RANDOM_DEVICE_ADDRESS);
}

void LeAddressManager::rotate_random_address() {
  if (address_policy_ != AddressPolicy::USE_RESOLVABLE_ADDRESS &&
      address_policy_ != AddressPolicy::USE_NON_RESOLVABLE_ADDRESS) {
    log::fatal("Invalid address policy!");
    return;
  }

  schedule_rotate_random_address();
  set_random_address();
}

void LeAddressManager::prepare_to_update_irk(UpdateIRKCommand update_irk_command) {
  Command command = {CommandType::UPDATE_IRK, update_irk_command};
  cached_commands_.push(std::move(command));
  if (registered_clients_.empty()) {
    handle_next_command();
  } else {
    pause_registered_clients();
  }
}

void LeAddressManager::update_irk(UpdateIRKCommand command) {
  rotation_irk_ = command.rotation_irk;
  minimum_rotation_time_ = command.minimum_rotation_time;
  maximum_rotation_time_ = command.maximum_rotation_time;
  log::info("minimum_rotation_time_={}ms, maximum_rotation_time_={}ms",
            minimum_rotation_time_.count(), maximum_rotation_time_.count());
  set_random_address();
  for (auto& client : registered_clients_) {
    client.first->NotifyOnIRKChange();
  }
}

/* This function generates Resolvable Private Address (RPA) from Identity
 * Resolving Key |irk| and |prand|*/
hci::Address LeAddressManager::generate_rpa() {
  // most significant bit, bit7, bit6 is 01 to be resolvable random
  // Bits of the random part of prand shall not be all 1 or all 0
  std::array<uint8_t, 3> prand = os::GenerateRandom<3>();
  constexpr uint8_t BLE_RESOLVE_ADDR_MSB = 0x40;
  prand[2] &= ~BLE_ADDR_MASK;
  if ((prand[0] == 0x00 && prand[1] == 0x00 && prand[2] == 0x00) ||
      (prand[0] == 0xFF && prand[1] == 0xFF && prand[2] == 0x3F)) {
    prand[0] = (uint8_t)(os::GenerateRandom() % 0xFE + 1);
  }
  prand[2] |= BLE_RESOLVE_ADDR_MSB;

  hci::Address address;
  address.address[3] = prand[0];
  address.address[4] = prand[1];
  address.address[5] = prand[2];

  Octet16 rand{};
  rand[0] = prand[0];
  rand[1] = prand[1];
  rand[2] = prand[2];

  /* encrypt with IRK */
  Octet16 p = crypto_toolbox::aes_128(rotation_irk_, rand);

  /* set hash to be LSB of rpAddress */
  address.address[0] = p[0];
  address.address[1] = p[1];
  address.address[2] = p[2];
  return address;
}

// This function generates NON-Resolvable Private Address (NRPA)
hci::Address LeAddressManager::generate_nrpa() {
  // The two most significant bits of the address shall be equal to 0
  // Bits of the random part of the address shall not be all 1 or all 0
  std::array<uint8_t, 6> random = os::GenerateRandom<6>();
  random[5] &= ~BLE_ADDR_MASK;
  if ((random[0] == 0x00 && random[1] == 0x00 && random[2] == 0x00 && random[3] == 0x00 &&
       random[4] == 0x00 && random[5] == 0x00) ||
      (random[0] == 0xFF && random[1] == 0xFF && random[2] == 0xFF && random[3] == 0xFF &&
       random[4] == 0xFF && random[5] == 0x3F)) {
    random[0] = (uint8_t)(os::GenerateRandom() % 0xFE + 1);
  }

  hci::Address address;
  address.FromOctets(random.data());

  // the address shall not be equal to the public address
  while (address == public_address_) {
    address.address[0] = (uint8_t)(os::GenerateRandom() % 0xFE + 1);
  }

  return address;
}

std::chrono::milliseconds LeAddressManager::GetNextPrivateAddressIntervalMs() {
  auto interval_random_part_wake_delay = maximum_rotation_time_ - minimum_rotation_time_;
  auto random_ms =
          std::chrono::milliseconds(os::GenerateRandom()) % (interval_random_part_wake_delay);
  return minimum_rotation_time_ + random_ms;
}

PrivateAddressIntervalRange LeAddressManager::GetNextPrivateAddressIntervalRange(
        const std::string& client_name) {
  // Get both alarms' delays as following:
  // - Non-wake  : Random between [minimum_rotation_time_, (minimum_rotation_time_ + 2 min)]
  // - Wake      : Random between [(maximum_rotation_time_ - 2 min), maximum_rotation_time_]
  // - Ensure that delays are in the given range [minimum_rotation_time_, maximum_rotation_time_]
  // - Ensure that the non-wake alarm's delay is not greater than wake alarm's delay.
  auto random_part_max_length = std::chrono::minutes(2);

  auto nonwake_delay = minimum_rotation_time_ +
                       (std::chrono::milliseconds(os::GenerateRandom()) % random_part_max_length);
  nonwake_delay = min(nonwake_delay, maximum_rotation_time_);

  auto wake_delay = maximum_rotation_time_ -
                    (std::chrono::milliseconds(os::GenerateRandom()) % random_part_max_length);
  wake_delay = max(nonwake_delay, max(wake_delay, minimum_rotation_time_));

  // For readable logging, the durations are rounded down to integer seconds.
  auto min_minutes = std::chrono::duration_cast<std::chrono::minutes>(nonwake_delay);
  auto min_seconds = std::chrono::duration_cast<std::chrono::seconds>(nonwake_delay - min_minutes);
  auto max_minutes = std::chrono::duration_cast<std::chrono::minutes>(wake_delay);
  auto max_seconds = std::chrono::duration_cast<std::chrono::seconds>(wake_delay - max_minutes);
  log::info("client={}, nonwake={}m{}s, wake={}m{}s", client_name, min_minutes.count(),
            min_seconds.count(), max_minutes.count(), max_seconds.count());

  return PrivateAddressIntervalRange{nonwake_delay, wake_delay};
}

void LeAddressManager::CheckAddressRotationHappenedInExpectedTimeInterval(
        const std::chrono::time_point<std::chrono::system_clock>& interval_min,
        const std::chrono::time_point<std::chrono::system_clock>& interval_max,
        const std::chrono::time_point<std::chrono::system_clock>& event_time,
        const std::string& client_name) {
  // Give some tolerance to upper limit since alarms may ring a little bit late.
  auto upper_limit_tolerance = std::chrono::seconds(5);

  if (event_time < interval_min || event_time > interval_max + upper_limit_tolerance) {
    log::warn("RPA rotation happened outside expected time interval. client={}", client_name);

    auto tt_interval_min = std::chrono::system_clock::to_time_t(interval_min);
    auto tt_interval_max = std::chrono::system_clock::to_time_t(interval_max);
    auto tt_event_time = std::chrono::system_clock::to_time_t(event_time);
    log::warn("interval_min={}", ctime(&tt_interval_min));
    log::warn("interval_max={}", ctime(&tt_interval_max));
    log::warn("event_time=  {}", ctime(&tt_event_time));
  }
}

uint8_t LeAddressManager::GetFilterAcceptListSize() { return accept_list_size_; }

uint8_t LeAddressManager::GetResolvingListSize() { return resolving_list_size_; }

void LeAddressManager::handle_next_command() {
  for (auto client : registered_clients_) {
    if (client.second != ClientState::PAUSED) {
      // make sure all client paused, if not, this function will be trigger again by ack_pause
      log::info("waiting for ack_pause, return");
      return;
    }
  }

  log::assert_that(!cached_commands_.empty(), "assert failed: !cached_commands_.empty()");
  auto command = std::move(cached_commands_.front());
  cached_commands_.pop();

  std::visit(
          [this](auto&& command) {
            using T = std::decay_t<decltype(command)>;
            if constexpr (std::is_same_v<T, UpdateIRKCommand>) {
              update_irk(command);
            } else if constexpr (std::is_same_v<T, RotateRandomAddressCommand>) {
              rotate_random_address();
            } else if constexpr (std::is_same_v<T, HCICommand>) {
              enqueue_command_.Run(std::move(command.command));
            } else {
              static_assert(!sizeof(T*), "non-exhaustive visitor!");
            }
          },
          command.contents);
}

void LeAddressManager::AddDeviceToFilterAcceptList(
        FilterAcceptListAddressType accept_list_address_type, bluetooth::hci::Address address) {
  auto packet_builder =
          hci::LeAddDeviceToFilterAcceptListBuilder::Create(accept_list_address_type, address);
  Command command = {CommandType::ADD_DEVICE_TO_ACCEPT_LIST, HCICommand{std::move(packet_builder)}};
  handler_->BindOnceOn(this, &LeAddressManager::push_command, std::move(command))();
}

void LeAddressManager::AddDeviceToResolvingList(PeerAddressType peer_identity_address_type,
                                                Address peer_identity_address,
                                                const std::array<uint8_t, 16>& peer_irk,
                                                const std::array<uint8_t, 16>& local_irk) {
  if (!supports_ble_privacy_) {
    return;
  }

  // Disable Address resolution
  auto disable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::DISABLED);
  Command disable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                     HCICommand{std::move(disable_builder)}};
  cached_commands_.push(std::move(disable));

  auto packet_builder = hci::LeAddDeviceToResolvingListBuilder::Create(
          peer_identity_address_type, peer_identity_address, peer_irk, local_irk);
  Command command = {CommandType::ADD_DEVICE_TO_RESOLVING_LIST,
                     HCICommand{std::move(packet_builder)}};
  cached_commands_.push(std::move(command));

  if (supports_ble_privacy_) {
    auto packet_builder = hci::LeSetPrivacyModeBuilder::Create(
            peer_identity_address_type, peer_identity_address, PrivacyMode::DEVICE);
    Command command = {CommandType::LE_SET_PRIVACY_MODE, HCICommand{std::move(packet_builder)}};
    cached_commands_.push(std::move(command));
  }

  // Enable Address resolution
  auto enable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::ENABLED);
  Command enable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                    HCICommand{std::move(enable_builder)}};
  cached_commands_.push(std::move(enable));

  if (registered_clients_.empty()) {
    handler_->BindOnceOn(this, &LeAddressManager::handle_next_command)();
  } else {
    handler_->BindOnceOn(this, &LeAddressManager::pause_registered_clients)();
  }
}

void LeAddressManager::RemoveDeviceFromFilterAcceptList(
        FilterAcceptListAddressType accept_list_address_type, bluetooth::hci::Address address) {
  auto packet_builder =
          hci::LeRemoveDeviceFromFilterAcceptListBuilder::Create(accept_list_address_type, address);
  Command command = {CommandType::REMOVE_DEVICE_FROM_ACCEPT_LIST,
                     HCICommand{std::move(packet_builder)}};
  handler_->BindOnceOn(this, &LeAddressManager::push_command, std::move(command))();
}

void LeAddressManager::RemoveDeviceFromResolvingList(PeerAddressType peer_identity_address_type,
                                                     Address peer_identity_address) {
  if (!supports_ble_privacy_) {
    return;
  }

  // Disable Address resolution
  auto disable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::DISABLED);
  Command disable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                     HCICommand{std::move(disable_builder)}};
  cached_commands_.push(std::move(disable));

  auto packet_builder = hci::LeRemoveDeviceFromResolvingListBuilder::Create(
          peer_identity_address_type, peer_identity_address);
  Command command = {CommandType::REMOVE_DEVICE_FROM_RESOLVING_LIST,
                     HCICommand{std::move(packet_builder)}};
  cached_commands_.push(std::move(command));

  // Enable Address resolution
  auto enable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::ENABLED);
  Command enable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                    HCICommand{std::move(enable_builder)}};
  cached_commands_.push(std::move(enable));

  if (registered_clients_.empty()) {
    handler_->BindOnceOn(this, &LeAddressManager::handle_next_command)();
  } else {
    handler_->BindOnceOn(this, &LeAddressManager::pause_registered_clients)();
  }
}

void LeAddressManager::ClearFilterAcceptList() {
  auto packet_builder = hci::LeClearFilterAcceptListBuilder::Create();
  Command command = {CommandType::CLEAR_ACCEPT_LIST, HCICommand{std::move(packet_builder)}};
  handler_->BindOnceOn(this, &LeAddressManager::push_command, std::move(command))();
}

void LeAddressManager::ClearResolvingList() {
  if (!supports_ble_privacy_) {
    return;
  }

  // Disable Address resolution
  auto disable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::DISABLED);
  Command disable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                     HCICommand{std::move(disable_builder)}};
  cached_commands_.push(std::move(disable));

  auto packet_builder = hci::LeClearResolvingListBuilder::Create();
  Command command = {CommandType::CLEAR_RESOLVING_LIST, HCICommand{std::move(packet_builder)}};
  cached_commands_.push(std::move(command));

  // Enable Address resolution
  auto enable_builder = hci::LeSetAddressResolutionEnableBuilder::Create(hci::Enable::ENABLED);
  Command enable = {CommandType::SET_ADDRESS_RESOLUTION_ENABLE,
                    HCICommand{std::move(enable_builder)}};
  cached_commands_.push(std::move(enable));

  handler_->BindOnceOn(this, &LeAddressManager::pause_registered_clients)();
}

template <class View>
void LeAddressManager::on_command_complete(CommandCompleteView view) {
  auto op_code = view.GetCommandOpCode();

  auto complete_view = View::Create(view);
  if (!complete_view.IsValid()) {
    log::error("Received {} complete with invalid packet", hci::OpCodeText(op_code));
    return;
  }
  auto status = complete_view.GetStatus();
  if (status != ErrorCode::SUCCESS) {
    log::error("Received {} complete with status {}", hci::OpCodeText(op_code),
               ErrorCodeText(complete_view.GetStatus()));
  }
}

void LeAddressManager::OnCommandComplete(bluetooth::hci::CommandCompleteView view) {
  if (!view.IsValid()) {
    log::error("Received command complete with invalid packet");
    return;
  }
  auto op_code = view.GetCommandOpCode();
  log::info("Received command complete with op_code {}", OpCodeText(op_code));

  switch (op_code) {
    case OpCode::LE_SET_RANDOM_ADDRESS: {
      // The command was sent before any client registered, we can make sure all the clients paused
      // when command complete.
      if (address_policy_ == AddressPolicy::USE_STATIC_ADDRESS) {
        log::info(
                "Received LE_SET_RANDOM_ADDRESS complete and Address policy is USE_STATIC_ADDRESS, "
                "return");
        return;
      }
      auto complete_view = LeSetRandomAddressCompleteView::Create(view);
      if (!complete_view.IsValid()) {
        log::error("Received LE_SET_RANDOM_ADDRESS complete with invalid packet");
      } else {
        if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
          log::error("Received LE_SET_RANDOM_ADDRESS complete with status {}",
                     ErrorCodeText(complete_view.GetStatus()));
        } else {
          log::info("update random address : {}", cached_address_.GetAddress());
          le_address_ = cached_address_;
        }
      }
    } break;

    case OpCode::LE_SET_PRIVACY_MODE:
      on_command_complete<LeSetPrivacyModeCompleteView>(view);
      break;

    case OpCode::LE_ADD_DEVICE_TO_RESOLVING_LIST:
      on_command_complete<LeAddDeviceToResolvingListCompleteView>(view);
      break;

    case OpCode::LE_REMOVE_DEVICE_FROM_RESOLVING_LIST:
      on_command_complete<LeRemoveDeviceFromResolvingListCompleteView>(view);
      break;

    case OpCode::LE_CLEAR_RESOLVING_LIST:
      on_command_complete<LeClearResolvingListCompleteView>(view);
      break;

    case OpCode::LE_ADD_DEVICE_TO_FILTER_ACCEPT_LIST:
      on_command_complete<LeAddDeviceToFilterAcceptListCompleteView>(view);
      break;

    case OpCode::LE_REMOVE_DEVICE_FROM_FILTER_ACCEPT_LIST:
      on_command_complete<LeRemoveDeviceFromFilterAcceptListCompleteView>(view);
      break;

    case OpCode::LE_SET_ADDRESS_RESOLUTION_ENABLE:
      on_command_complete<LeSetAddressResolutionEnableCompleteView>(view);
      break;

    case OpCode::LE_CLEAR_FILTER_ACCEPT_LIST:
      on_command_complete<LeClearFilterAcceptListCompleteView>(view);
      break;

    case OpCode::LE_SET_RESOLVABLE_PRIVATE_ADDRESS_TIMEOUT_V2:
      on_command_complete<LeSetResolvablePrivateAddressTimeoutV2CompleteView>(view);
      break;

    default:
      log::error("Received UNSUPPORTED command {} complete", hci::OpCodeText(op_code));
      break;
  }

  handler_->BindOnceOn(this, &LeAddressManager::check_cached_commands)();
}

void LeAddressManager::check_cached_commands() {
  for (auto client : registered_clients_) {
    if (client.second != ClientState::PAUSED && !cached_commands_.empty()) {
      pause_registered_clients();
      return;
    }
  }

  if (cached_commands_.empty()) {
    resume_registered_clients();
  } else {
    handle_next_command();
  }
}

}  // namespace hci
}  // namespace bluetooth
