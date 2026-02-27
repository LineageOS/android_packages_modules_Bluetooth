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

#include "os/timed_mutex.h"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <sys/timerfd.h>

#include <ctime>

namespace bluetooth {
namespace os {

void TimedMutex::lock(const std::source_location location) {
  mutex_.lock();
  owner_thread_linux_tid_ = static_cast<int>(syscall(SYS_gettid));
  lock_location_ = location;
}

void TimedMutex::unlock() {
  lock_location_.reset();
  owner_thread_linux_tid_.reset();
  mutex_.unlock();
}

}  // namespace os
}  // namespace bluetooth