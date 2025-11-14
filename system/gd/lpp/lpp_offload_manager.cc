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
#include "lpp_offload_manager.h"

#include <bluetooth/log.h>

#include <string>

#include "hal/socket_hal.h"
#include "os/handler.h"
#include "os/system_properties.h"

namespace bluetooth::lpp {

struct LppOffloadManager::impl {
  impl(os::Handler* handler, hal::SocketHal* socket_hal)
      : handler_(handler), socket_hal_(socket_hal) {
    log::info("");
    socket_capabilities_ = socket_hal_->GetSocketCapabilities();
  }

  bool register_socket_hal_callbacks(hal::SocketHalCallback* callbacks) {
    log::info("");
    return socket_hal_->RegisterCallback(callbacks);
  }

  hal::SocketCapabilities get_socket_capabilities() const {
    log::info("");
    return socket_capabilities_;
  }

  bool socket_opened(const hal::SocketContext& context) {
    log::info("socket_id: {}", context.socket_id);
    return socket_hal_->Opened(context);
  }

  void socket_closed(uint64_t socket_id) {
    log::info("socket_id: {}", socket_id);
    return socket_hal_->Closed(socket_id);
  }

  os::Handler* handler_;
  hal::SocketHal* socket_hal_;
  hal::SocketCapabilities socket_capabilities_;
};

LppOffloadManager::LppOffloadManager(os::Handler* handler, hal::SocketHal* socket_hal) {
  pimpl_ = std::make_unique<impl>(handler, socket_hal);
}

LppOffloadManager::~LppOffloadManager() = default;

bool LppOffloadManager::RegisterSocketHalCallback(hal::SocketHalCallback* callbacks) {
  return pimpl_->register_socket_hal_callbacks(callbacks);
}

hal::SocketCapabilities LppOffloadManager::GetSocketCapabilities() const {
  return pimpl_->get_socket_capabilities();
}

bool LppOffloadManager::SocketOpened(const hal::SocketContext& context) {
  return pimpl_->socket_opened(context);
}

void LppOffloadManager::SocketClosed(uint64_t socket_id) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::socket_closed, socket_id);
}

}  // namespace bluetooth::lpp
