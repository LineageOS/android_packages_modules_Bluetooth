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

#include <bluetooth/types/address.h>

#include <memory>

#include "vcs_types.h"

namespace bluetooth::vcs {

/**
 * @brief Volume Control Service (VCS) Server.
 *
 * This class provides an API to manage the Volume Control Service (VCS)
 * as a GATT server. It's part of the overall LE Audio implementation. It
 * handles the registration of the GATT service, manages connections from
 * clients (Volume Controllers), and processes client requests to read and
 * control the volume state.
 *
 * This service exposes:
 * - Volume State characteristic
 * - Volume Control Point characteristic
 * - Volume Flags characteristic
 */
class VcsServer {
public:
  /**
   * @brief Defines the GATT database structure for the VC service.
   */
  struct ServiceDescriptor {
    /**
     * @brief The step size for relative volume changes.
     *
     * This value must be positive.
     */
    uint8_t step_size;

    /** @brief The initial volume setting. */
    uint8_t initial_volume;

    /** @brief The initial mute state. */
    MuteState initial_mute_state;

    /** @brief The initial volume setting persisted flag. */
    VolumeSettingPersisted initial_volume_setting_persisted;
  };

  /**
   * @brief Callbacks for the VC service to notify of events.
   *
   * An implementation of this interface must be provided to the
   * `RegisterGattService` method.
   */
  struct Callbacks {
    Callbacks() = default;
    virtual ~Callbacks() = default;

    /**
     * @brief Called when the VCS GATT service has been registered.
     */
    virtual void OnVcsServerRegistered(void) = 0;

    /**
     * @brief Called when a remote device connects to the service.
     * @param pseudo_addr The address of the connected device.
     */
    virtual void OnDeviceConnected(const RawAddress& pseudo_addr) = 0;

    /**
     * @brief Called when a remote device disconnects from the service.
     * @param pseudo_addr The address of the disconnected device.
     */
    virtual void OnDeviceDisconnected(const RawAddress& pseudo_addr) = 0;

    /**
     * @brief Called when a client requests to change the volume setting or mute state.
     *
     * @param pseudo_addr The address of the device making the request.
     * @param volume The new volume setting requested by the client.
     * @param mute_state The new mute state requested by the client.
     */
    virtual void OnVolumeStateChangeRequest(const RawAddress& pseudo_addr, uint8_t volume,
                                            MuteState mute_state) = 0;
  };

  // This is not a copyable class
  VcsServer(const VcsServer&) = delete;
  VcsServer& operator=(const VcsServer&) = delete;
  VcsServer(VcsServer&&) = delete;
  VcsServer& operator=(VcsServer&&) = delete;

  /**
   * @brief Registers the VCS GATT service with the given descriptor and
   * callbacks.
   *
   * This method should be called once to initialize the service.
   *
   * @param service_descriptor The service parameters.
   * @param callbacks The callback handler for service events.
   */
  virtual void RegisterGattService(const ServiceDescriptor& service_descriptor,
                                   Callbacks* callbacks);

  /**
   * @brief Updates the volume state.
   *
   * This will update the Volume State characteristic in VCS and notify
   * subscribed clients.
   *
   * @param volume The new volume setting.
   * @param mute_state The new mute state.
   */
  virtual void UpdateVolumeState(uint8_t volume, MuteState mute_state);

  /**
   * @brief Updates the volume flags.
   *
   * This will update the Volume Flags characteristic in VCS and notify
   * subscribed clients.
   *
   * @param flags The new volume flags.
   */
  virtual void UpdateVolumeFlags(const VolumeFlags& flags);

  /**
   * @brief Dumps the state of the service to the given stream.
   *
   * @param stream The string stream to write the dump to.
   */
  virtual void Dump(std::stringstream& stream) const;

protected:
  // Protected constructor and destructor for singleton-like pattern
  VcsServer();
  virtual ~VcsServer();

private:
  struct service_impl;
  std::unique_ptr<service_impl> service_impl_;
};

/**
 * @brief Factory method to get a shared instance of the VcsServer.
 *
 * This is required to manage a single static (but shared) instance, needed by
 * the static GATT callback API.
 *
 * @return A shared pointer to the VcsServer instance.
 */
std::shared_ptr<VcsServer> InstantiateVcsServer();

/**
 * @brief Releases the shared instance of the VcsServer.
 *
 * @param shared_instance The shared pointer obtained from
 * `InstantiateVcsServer`.
 */
void ReleaseVcsServer(std::shared_ptr<VcsServer> shared_instance);

}  // namespace bluetooth::vcs
