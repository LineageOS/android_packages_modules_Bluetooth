/*
 * Copyright 2023 The Android Open Source Project
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

#include "hci_hal.h"
#include "module.h"

namespace bluetooth::hal {

class ReadClockHandler {
 public:
  virtual ~ReadClockHandler() = default;

  /// Report a measurement of the BT clock.
  /// `timestamp` is the local time measured in microseconds,
  /// `bt_clock` is the local BT clock measured @ 51.2 KHz (32 times the
  /// BR/EDR packets rate), with precision 1/3200 Hz.
  virtual void OnEvent(uint32_t timestamp, uint32_t bt_clock) = 0;
};

class LinkClocker : public ::bluetooth::Module {
 public:
  static const ModuleFactory Factory;

  void OnHciEvent(const HciPacket& packet);

  static void Register(ReadClockHandler*);
  static void Unregister();

 protected:
  LinkClocker() = default;

  void ListDependencies(ModuleList*) const override {}
  void Start() override {}
  void Stop() override {}

  std::string ToString() const override {
    return std::string("LinkClocker");
  }
};

}  // namespace bluetooth::hal
