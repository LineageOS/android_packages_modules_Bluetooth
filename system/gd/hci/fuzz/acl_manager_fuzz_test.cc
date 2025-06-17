/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <fuzzer/FuzzedDataProvider.h>
#include <stddef.h>
#include <stdint.h>

#include "fuzz/helpers.h"
#include "hci/acl_manager/acl_manager_classic_impl.h"
#include "hci/acl_manager/acl_manager_le_impl.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/controller_mock.h"
#include "hci/fuzz/fuzz_hci_layer.h"
#include "hci/hci_layer.h"
#include "hci/remote_name_request_mock.h"
#include "os/fake_timer/fake_timerfd.h"

using bluetooth::fuzz::GetArbitraryBytes;
using bluetooth::hci::HciLayer;
using bluetooth::hci::RemoteNameRequestModule;
using bluetooth::hci::RemoteNameRequestModuleMock;
using bluetooth::hci::acl_manager::AclManagerClassicImpl;
using bluetooth::hci::acl_manager::AclManagerLeImpl;
using bluetooth::hci::acl_manager::AclScheduler;
using bluetooth::hci::acl_manager::RoundRobinScheduler;
using bluetooth::hci::fuzz::FuzzHciLayer;
using bluetooth::hci::testing::MockController;
using bluetooth::os::Handler;
using bluetooth::os::Thread;
using bluetooth::os::fake_timer::fake_timerfd_advance;
using bluetooth::os::fake_timer::fake_timerfd_cap_at;
using bluetooth::os::fake_timer::fake_timerfd_reset;
using bluetooth::storage::StorageModule;

constexpr int32_t kMinTimeAdvanced = 0;
/**
 * kMaxTotalTimeAdvanced value is referenced from
 * kDefaultConfigSaveDelay defined in storage_module.cc
 */
constexpr int32_t kMaxTotalTimeAdvanced = 3000;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  FuzzedDataProvider dataProvider(data, size);

  Thread thread_ = Thread("test_thread", Thread::Priority::NORMAL);
  Handler client_handler_ = Handler(&thread_);

  std::unique_ptr<FuzzHciLayer> fuzzHci = std::make_unique<FuzzHciLayer>(&client_handler_);
  fuzzHci->TurnOnAutoReply(&dataProvider);

  std::unique_ptr<MockController> test_controller_ = std::make_unique<MockController>();
  std::unique_ptr<AclScheduler> test_acl_scheduler_ =
          std::make_unique<AclScheduler>(&client_handler_);
  std::unique_ptr<RemoteNameRequestModule> test_rnr_ =
          std::make_unique<RemoteNameRequestModuleMock>();
  std::unique_ptr<StorageModule> test_storage_ = std::make_unique<StorageModule>(&client_handler_);
  std::unique_ptr<RoundRobinScheduler> test_round_robin_scheduler_ =
          std::make_unique<RoundRobinScheduler>(&client_handler_, *test_controller_,
                                                fuzzHci->GetAclQueueEnd());
  std::unique_ptr<AclManagerClassicImpl> acl_manager_classic_ =
          std::make_unique<AclManagerClassicImpl>(&client_handler_, *fuzzHci, *test_acl_scheduler_,
                                                  *test_rnr_, *test_round_robin_scheduler_);
  std::unique_ptr<AclManagerLeImpl> acl_manager = std::make_unique<AclManagerLeImpl>(
          &client_handler_, *fuzzHci, *test_controller_, *test_storage_,
          *test_round_robin_scheduler_, *acl_manager_classic_);

  fuzzHci->TurnOffAutoReply();
  uint64_t totalAdvanceTime = 0;

  while (dataProvider.remaining_bytes() > 0) {
    const uint8_t action = dataProvider.ConsumeIntegralInRange(0, 2);

    switch (action) {
      case 1: {
        uint64_t advanceTime = dataProvider.ConsumeIntegralInRange<uint64_t>(kMinTimeAdvanced,
                                                                             kMaxTotalTimeAdvanced);
        totalAdvanceTime += advanceTime;
        if (totalAdvanceTime < kMaxTotalTimeAdvanced) {
          fake_timerfd_advance(advanceTime);
        }
        break;
      }
      case 2: {
        fuzzHci->injectArbitrary(dataProvider);
        break;
      }
    }
  }

  acl_manager.reset();
  acl_manager_classic_.reset();
  test_round_robin_scheduler_.reset();
  test_storage_.reset();
  test_rnr_.reset();
  test_acl_scheduler_.reset();
  test_controller_.reset();

  fuzzHci.reset();

  client_handler_.Clear();
  client_handler_.WaitUntilStopped(bluetooth::kHandlerStopTimeout);

  fake_timerfd_reset();
  return 0;
}
