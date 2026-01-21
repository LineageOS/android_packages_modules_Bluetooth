/*
 * Copyright 2022 The Android Open Source Project
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

#include "a2dp_aidl_transport.h"
#include "audio_aidl_interfaces.h"

namespace bluetooth {
namespace audio {
namespace aidl {
namespace a2dp {

using ::aidl::android::hardware::audio::common::SinkMetadata;
using ::aidl::android::hardware::audio::common::SourceMetadata;
using ::aidl::android::hardware::bluetooth::audio::BnBluetoothAudioPort;
using ::aidl::android::hardware::bluetooth::audio::CodecType;
using ::aidl::android::hardware::bluetooth::audio::IBluetoothAudioProvider;
using ::aidl::android::hardware::bluetooth::audio::LatencyMode;
using ::aidl::android::hardware::bluetooth::audio::PresentationPosition;

class BluetoothAudioPortImpl : public BnBluetoothAudioPort {
public:
  BluetoothAudioPortImpl(const std::shared_ptr<A2dpTransport>& transport_instance,
                         const std::shared_ptr<IBluetoothAudioProvider>& provider);

  ndk::ScopedAStatus startStream(bool is_low_latency) override;
  ndk::ScopedAStatus suspendStream() override;
  ndk::ScopedAStatus stopStream() override;
  ndk::ScopedAStatus getPresentationPosition(PresentationPosition* _aidl_return) override;
  ndk::ScopedAStatus updateSourceMetadata(const SourceMetadata& source_metadata) override;
  ndk::ScopedAStatus updateSinkMetadata(const SinkMetadata& sink_metadata) override;
  ndk::ScopedAStatus setLatencyMode(LatencyMode latency_mode) override;
  ndk::ScopedAStatus updateSinkLatency(int64_t in_latency_ms) override;

protected:
  virtual ~BluetoothAudioPortImpl();

  // Using weak_ptr here as BluetoothAudioPortImpl instance is shared with the BT Audio HAL and can
  // outlive the BluetoothAudioClientInterface and by extension this A2dpTransport instance.
  std::weak_ptr<A2dpTransport> transport_instance_;
  const std::shared_ptr<IBluetoothAudioProvider> provider_;

private:
  ndk::SpAIBinder createBinder() override;
};

}  // namespace a2dp
}  // namespace aidl
}  // namespace audio
}  // namespace bluetooth
