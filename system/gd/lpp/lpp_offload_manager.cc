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
#include <com_android_bluetooth_flags.h>

#include <string>

#include "hal/gatt_hal.h"
#include "hal/socket_hal.h"
#include "os/handler.h"
#include "os/system_properties.h"

namespace bluetooth::lpp {

struct LppOffloadManager::impl {
  impl(os::Handler* handler, hal::SocketHal* socket_hal, hal::GattHal* gatt_hal)
      : handler_(handler), socket_hal_(socket_hal), gatt_hal_(gatt_hal) {
    log::info("");
    socket_capabilities_ = socket_hal_->GetSocketCapabilities();
    gatt_capabilities_ = gatt_hal_->GetGattCapabilities();
  }

  ~impl() = default;

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

  bool initialize_gatt_hal(hal::GattHalCallback* callbacks) {
    log::info("");
    return gatt_hal_->Initialize(callbacks);
  }

  hal::GattCapabilities get_gatt_capabilities() const {
    log::info("");
    return gatt_capabilities_;
  }

  bool register_gatt_service(const hal::GattSession& session) {
    log::info("session_id: {}", session.id);
    return gatt_hal_->RegisterService(session);
  }

  void unregister_gatt_service(int session_id) {
    log::info("session_id: {}", session_id);
    gatt_hal_->UnregisterService(session_id);
  }

  void clear_gatt_services(int acl_connection_handle) {
    log::info("acl_connection_handle: 0x{:x}", acl_connection_handle);
    gatt_hal_->ClearServices(acl_connection_handle);
  }

  os::Handler* handler_;
  hal::SocketHal* socket_hal_;
  hal::GattHal* gatt_hal_;
  hal::SocketCapabilities socket_capabilities_;
  hal::GattCapabilities gatt_capabilities_;
};

LppOffloadManager::LppOffloadManager(os::Handler* handler, hal::SocketHal* socket_hal,
                                     hal::GattHal* gatt_hal) {
  pimpl_ = std::make_unique<impl>(handler, socket_hal, gatt_hal);

  log::verbose("module started !!");
}

LppOffloadManager::~LppOffloadManager() {
  log::verbose("module stopped !!");
};

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

bool LppOffloadManager::InitializeGattHal(hal::GattHalCallback* callbacks) {
  return pimpl_->initialize_gatt_hal(callbacks);
}

hal::GattCapabilities LppOffloadManager::GetGattCapabilities() const {
  return pimpl_->get_gatt_capabilities();
}

bool LppOffloadManager::RegisterGattService(const hal::GattSession& session) {
  return pimpl_->register_gatt_service(session);
}

void LppOffloadManager::UnregisterGattService(int session_id) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::unregister_gatt_service, session_id);
}

void LppOffloadManager::ClearGattServices(int acl_connection_handle) {
  pimpl_->handler_->CallOn(pimpl_.get(), &impl::clear_gatt_services, acl_connection_handle);
}

}  // namespace bluetooth::lpp
