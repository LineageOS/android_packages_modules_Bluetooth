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
#include "hal/fuzz/fuzz_hci_hal.h"
#include "hci/fuzz/hci_layer_fuzz_client.h"
#include "hci/hci_layer.h"
#include "os/fake_timer/fake_timerfd.h"
#include "os/handler.h"

using bluetooth::fuzz::GetArbitraryBytes;
using bluetooth::hal::HciHal;
using bluetooth::hal::fuzz::FuzzHciHal;
using bluetooth::hci::HciInterface;
using bluetooth::hci::HciLayer;
using bluetooth::hci::fuzz::HciLayerFuzzClient;
using bluetooth::os::Handler;
using bluetooth::os::Thread;
using bluetooth::os::fake_timer::fake_timerfd_reset;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  FuzzedDataProvider dataProvider(data, size);

  Thread thread_ = Thread("test_thread", Thread::Priority::NORMAL);
  Handler client_handler_ = Handler(&thread_);

  std::unique_ptr<FuzzHciHal> fuzzHal = std::make_unique<FuzzHciHal>();
  std::unique_ptr<HciInterface> hciLayer =
          std::make_unique<HciLayer>(&client_handler_, fuzzHal.get(), nullptr /* storage */);
  std::unique_ptr<HciLayerFuzzClient> fuzzClient =
          std::make_unique<HciLayerFuzzClient>(&client_handler_, hciLayer.get());

  while (dataProvider.remaining_bytes() > 0) {
    const uint8_t action = dataProvider.ConsumeIntegralInRange(1, 2);
    switch (action) {
      case 1:
        fuzzHal->injectArbitrary(dataProvider);
        break;
      case 2:
        fuzzClient->injectArbitrary(dataProvider);
        break;
    }
  }

  fuzzClient.reset();
  hciLayer.reset();
  fuzzHal.reset();

  client_handler_.Clear();
  client_handler_.WaitUntilStopped(bluetooth::kHandlerStopTimeout);

  fake_timerfd_reset();
  return 0;
}
