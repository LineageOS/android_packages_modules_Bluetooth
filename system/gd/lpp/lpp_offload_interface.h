/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "hal/socket_hal.h"

namespace bluetooth::lpp {

/**
 * Interface to low-power processors (LPPs) for supporting LPP offload features.
 *
 * This interface allows inheritance from multiple offload HAL interfaces, enabling a unified
 * offload function management approach through a single interface accessible from the upper layer.
 */
class LppOffloadInterface {
public:
  LppOffloadInterface() = default;

  virtual ~LppOffloadInterface() = default;

  LppOffloadInterface(const LppOffloadInterface&) = delete;

  LppOffloadInterface& operator=(const LppOffloadInterface&) = delete;

  /**
   * Registers a socket hal callback function to receive asynchronous events from socket HAL.
   *
   * The provided callback function must be executed on the main thread.
   *
   * @param callback A pointer to the callback function. Must not be nullptr and must have static
   * lifetime.
   * @return True if the callback was successfully registered, false otherwise.
   */
  virtual bool RegisterSocketHalCallback(hal::SocketHalCallback* callbacks) = 0;

  /**
   * Retrieves the supported offload socket capabilities.
   *
   * @return Supported socket capabilities
   */
  virtual hal::SocketCapabilities GetSocketCapabilities() const = 0;

  /**
   * Notifies the socket HAL that the socket has been opened.
   *
   * If this method returns true, SocketHalCallback.SocketOpenedComplete() shall be called to
   * indicate the result of this operation.
   *
   * @param context Socket context including socket ID, channel, hub, and endpoint info
   * @return True if calling this method was successful, false otherwise
   */
  virtual bool SocketOpened(const hal::SocketContext& context) = 0;

  /**
   * Notifies the socket HAL that the socket has been closed.
   *
   * @param socket_id Identifier assigned to the socket by the host stack
   */
  virtual void SocketClosed(uint64_t socket_id) = 0;
};

}  // namespace bluetooth::lpp
