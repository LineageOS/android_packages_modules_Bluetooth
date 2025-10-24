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

#include <hardware/bt_vcp_renderer.h>

#include <memory>

namespace bluetooth::vcs {
class VcsServer;
}

namespace bluetooth::vcp {

class VcpServicesFactory {
public:
  virtual ~VcpServicesFactory() = default;
  virtual std::shared_ptr<vcs::VcsServer> InstantiateVcsServer() = 0;
  virtual void ReleaseVcsServer(std::shared_ptr<vcs::VcsServer> vcs) = 0;
};

/**
 * @brief The main interface for the Volume Control Profile (VCP) server.
 */
class VolumeRenderer {
public:
  VolumeRenderer(void) = default;
  virtual ~VolumeRenderer(void) = default;

  /**
   * @brief Initializes the Volume Renderer.
   *
   * @param callbacks The callbacks for the Volume Renderer.
   * @param config The configuration for the Volume Renderer.
   * @param factory The factory for creating VCP services, nullptr for default factory
   */
  static void Initialize(VolumeRendererCallbacks* callbacks, const VolumeRendererConfig& config,
                         VcpServicesFactory* factory = nullptr);
  /**
   * @brief Cleans up the Volume Renderer.
   */
  static void Cleanup(void);

  /**
   * @brief Gets the singleton instance of the Volume Renderer.
   */
  static VolumeRenderer* Get(void);

  /**
   * @brief Dumps debug information for the Volume Renderer.
   */
  static void DebugDump(int fd);

  /**
   * @brief Updates the volume state.
   *
   * @param volume The new volume setting.
   * @param mute_state The new mute state.
   */
  virtual void UpdateVolumeState(uint8_t volume, MuteState mute_state) = 0;

  /**
   * @brief Updates the volume flags.
   *
   * @param flags The new volume flags.
   */
  virtual void UpdateVolumeFlags(const VolumeFlags& flags) = 0;
};
}  // namespace bluetooth::vcp
