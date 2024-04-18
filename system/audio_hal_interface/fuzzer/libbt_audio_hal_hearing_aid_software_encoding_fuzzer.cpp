/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include <binder/IServiceManager.h>
#include <fuzzbinder/random_binder.h>
#include <fuzzer/FuzzedDataProvider.h>

#include "audio_hal_interface/hearing_aid_software_encoding.h"
#include "osi/include/properties.h"

using namespace android;
[[clang::no_destroy]] static std::once_flag gSmOnce;

constexpr int32_t kRandomStringLength = 256;
constexpr int32_t kPropertyValueMax = 92;
constexpr int32_t kMaxBytes = 1000;

extern "C" {
struct android_namespace_t* android_get_exported_namespace(const char*) {
  return nullptr;
}
}

static void source_init_delayed(void) {}

bool hearingAidOnResumeReq(bool /*start_media_task*/) { return true; }

bool hearingAidOnSuspendReq() { return true; }

auto streamCb = bluetooth::audio::hearing_aid::StreamCallbacks{
    .on_resume_ = hearingAidOnResumeReq,
    .on_suspend_ = hearingAidOnSuspendReq,
};

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  FuzzedDataProvider fdp(data, size);

  const std::string property = "persist.bluetooth.a2dp_offload.disabled";
  char received[kPropertyValueMax];
  osi_property_get(property.c_str(), received, NULL);
  osi_property_set(property.c_str(), fdp.PickValueInArray({"true", "false"}));

  std::call_once(gSmOnce, [&] {
    auto sm = defaultServiceManager();
    auto binder = getRandomBinder(&fdp);
    sm->addService(String16("android.hardware.bluetooth.audio."
                            "IBluetoothAudioProviderFactory.ProviderInfo"),
                   binder);

    if (fdp.ConsumeBool()) {
      uint16_t delay = fdp.ConsumeIntegral<uint16_t>();
      bluetooth::audio::hearing_aid::set_remote_delay(delay);
    }
    std::string name = fdp.ConsumeRandomLengthString(kRandomStringLength);
    bluetooth::common::MessageLoopThread messageLoopThread(name);
    messageLoopThread.StartUp();
    bluetooth::audio::hearing_aid::init(streamCb, &messageLoopThread);
  });

  bluetooth::audio::hearing_aid::start_session();

  std::vector<uint8_t> buffer = fdp.ConsumeBytes<uint8_t>(kMaxBytes);
  bluetooth::audio::hearing_aid::read(buffer.data(), buffer.size());

  bluetooth::audio::hearing_aid::end_session();
  osi_property_set(property.c_str(), received);

  return 0;
}
