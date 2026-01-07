/******************************************************************************
 *
 *  Copyright (C) 2022 Google, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "hal/snoop_logger_socket_thread.h"

#include <arpa/inet.h>
#include <bluetooth/log.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdbool.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include "hal/snoop_logger_common.h"

namespace bluetooth {
namespace hal {

SnoopLoggerSocketThread::SnoopLoggerSocketThread(int host, int port) {
  socket_ = std::make_unique<SnoopLoggerSocket>(&syscall_if, host, port);
  stop_thread_ = false;
  listen_thread_running_ = false;
}

SnoopLoggerSocketThread::~SnoopLoggerSocketThread() { Stop(); }

std::future<bool> SnoopLoggerSocketThread::Start() {
  log::debug("");
  std::promise<bool> thread_started;
  if (listen_thread_) {
    thread_started.set_value(true);
    return thread_started.get_future();
  }
  auto future = thread_started.get_future();
  stop_thread_ = false;
  listen_thread_ = std::make_unique<std::thread>(&SnoopLoggerSocketThread::Run, this,
                                                 std::move(thread_started));
  return future;
}

void SnoopLoggerSocketThread::Stop() {
  log::debug("");

  stop_thread_ = true;
  socket_->NotifySocketListener();

  if (listen_thread_ && listen_thread_->joinable()) {
    listen_thread_->join();
    listen_thread_.reset();
    socket_->Cleanup();
  }
}

void SnoopLoggerSocketThread::Write(const void* data, size_t length) {
  socket_->Write(data, length);
}

bool SnoopLoggerSocketThread::ThreadIsRunning() const { return listen_thread_running_; }

SnoopLoggerSocket* SnoopLoggerSocketThread::GetSocket() const { return socket_.get(); }

void SnoopLoggerSocketThread::Run(std::promise<bool> thread_started) {
  log::debug("");

  if (socket_->InitializeCommunications() != 0) {
    thread_started.set_value(false);
    return;
  }

  thread_started.set_value(true);

  while (!stop_thread_ && socket_->ProcessIncomingRequest()) {
  }

  // We don't call `socket_->Cleanup()` here because it's possible for that to lead to SIGPIPE: in
  // `Stop` it sets `stop_thread_` to true, and then calls `socket_->NotifySocketListener()`. Within
  // that small window, we might have checked `stop_thread_` above, and if we were to call
  // `socket_->Cleanup` here, that would then mean that `socket_->NotifySocketListener()` could
  // result in SIGPIPE, which, by default will terminate the process.

  listen_thread_running_ = false;
}

}  // namespace hal
}  // namespace bluetooth
