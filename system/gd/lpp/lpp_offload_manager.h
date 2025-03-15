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

#include <bluetooth/log.h>

#include <memory>
#include <string>

#include "lpp_offload_interface.h"
#include "module.h"

namespace bluetooth::lpp {

class LppOffloadManager : public bluetooth::Module, public LppOffloadInterface {
public:
  LppOffloadManager();

  LppOffloadManager(const LppOffloadManager&) = delete;

  LppOffloadManager& operator=(const LppOffloadManager&) = delete;

  ~LppOffloadManager();

  bool RegisterSocketHalCallback(hal::SocketHalCallback* callbacks) override;

  hal::SocketCapabilities GetSocketCapabilities() const override;

  bool SocketOpened(const hal::SocketContext& context) override;

  void SocketClosed(uint64_t socket_id) override;

  static const ModuleFactory Factory;

protected:
  void ListDependencies(ModuleList* list) const override;

  void Start() override;

  void Stop() override;

  std::string ToString() const override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace bluetooth::lpp
