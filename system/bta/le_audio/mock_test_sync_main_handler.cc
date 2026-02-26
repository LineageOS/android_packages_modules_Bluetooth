/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "mock_test_sync_main_handler.h"

#include <bluetooth/log.h>
#include <gtest/gtest.h>
#include <log/log.h>

#include <atomic>
#include <vector>

#include "btif_status.h"

bluetooth::common::MessageLoopThread message_loop_thread(
        "test message loop", bluetooth::os::Thread::Priority::REAL_TIME);

std::atomic<int> num_async_tasks;
std::vector<base::OnceClosure> pending_tasks_;

bluetooth::common::MessageLoopThread* get_main_thread() { return &message_loop_thread; }
std::mutex sync_mtx;
std::condition_variable sync_cv;

BtStatus do_in_main_thread(base::OnceClosure task) {
  num_async_tasks++;

  // Wrap the task with a counter. This counter is incremented when the task is posted
  // and decremented when the task is executed. This allows `SyncOnMainLoop` to
  // wait until all tasks posted to the main thread have completed.
  if (!message_loop_thread.DoInThread(base::BindOnce(
              [](base::OnceClosure task) {
                std::move(task).Run();

                if (--num_async_tasks == 0) {
                  std::lock_guard<std::mutex> lock(sync_mtx);
                  sync_cv.notify_all();
                }
              },
              std::move(task)))) {
    num_async_tasks--;
    bluetooth::log::error("failed to post task to task runner!");
    return BtifStatus(FAIL);
  }

  return BtifStatus();
}

void init_message_loop_thread() {
  num_async_tasks = 0;
  message_loop_thread.StartUp();
  if (!message_loop_thread.IsRunning()) {
    FAIL() << "unable to create message loop thread.";
  }

  if (!message_loop_thread.EnableRealTimeScheduling()) {
    bluetooth::log::error("Unable to set real time scheduling");
  }
}

void cleanup_message_loop_thread() { message_loop_thread.ShutDown(); }

void SyncOnMainLoop() {
  // This method blocks the calling thread until all tasks currently scheduled
  // on the `message_loop_thread` have been executed. Unlike other synchronization
  // methods that might only wait for a specific task or the current task to complete,
  // this ensures that the entire queue of pending asynchronous tasks is empty.
  // WARNING: Not tested with Timers pushing periodic tasks to the main loop
  if (message_loop_thread.IsRunningOnSameThread()) {
    bluetooth::log::warn("Tried syncing on the main loop from the main loop thread.");
    return;
  }

  std::unique_lock<std::mutex> lock(sync_mtx);
  sync_cv.wait(lock, [] { return num_async_tasks == 0; });
}
