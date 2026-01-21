/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/functional/callback_forward.h>
#include <hardware/bt_le_audio_server.h>

#include <memory>
#include <vector>

namespace bluetooth::audio::le_audio {
// Dependencies forward declaration
class IPeripheralAudioSessionFactory;
class IPeripheralAudioProviderFactory;
}  // namespace bluetooth::audio::le_audio

namespace bluetooth::le_audio {
// Dependencies forward declaration
class AseManager;
class Pacs;
class Ascs;
class LeAudioServerConfigManager;

struct LeAudioServerDependencies {
  std::function<std::shared_ptr<LeAudioServerConfigManager>()> config_manager_factory;
  std::function<std::shared_ptr<Pacs>()> pacs_factory;
  std::function<std::shared_ptr<Ascs>()> ascs_factory;
  std::function<std::shared_ptr<AseManager>(std::shared_ptr<Ascs>)> ase_manager_factory;
  std::function<audio::le_audio::IPeripheralAudioSessionFactory*()>
          peripheral_audio_session_factory;
  std::function<audio::le_audio::IPeripheralAudioProviderFactory*()>
          peripheral_audio_provider_factory;
};

/* Interface class */
class LeAudioServer {
public:
  LeAudioServer(void) = default;
  virtual ~LeAudioServer(void) = default;

  static void Initialize(le_audio::LeAudioServerCallbacks* callbacks,
                         std::unique_ptr<LeAudioServerDependencies> dependencies);
  static void Cleanup(void);
  static LeAudioServer* Get(void);
  static void DebugDump(int fd);
  static void ConfirmStreamStartRequest(const RawAddress& peer_address, bool allowed);
  static void StopStream(const RawAddress& peer_address, uint8_t stream_id);
};
}  // namespace bluetooth::le_audio
