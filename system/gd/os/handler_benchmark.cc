/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <future>

#include "benchmark/benchmark.h"
#include "os/handler.h"
#include "os/thread.h"

using ::benchmark::State;
using ::bluetooth::common::BindOnce;
using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;

class BM_HandlerPerformance : public ::benchmark::Fixture {
protected:
  void SetUp(State& st) override {
    ::benchmark::Fixture::SetUp(st);
    thread_ = std::make_unique<Thread>("BM_HandlerPerformance thread", Thread::Priority::NORMAL);
    handler_ = std::make_unique<Handler>(thread_.get());
    promise_ = std::promise<void>();
    counter_ = 0;
  }

  void TearDown(State& st) override {
    handler_->Clear();
    handler_->WaitUntilStopped(std::chrono::milliseconds(1000));
    handler_ = nullptr;
    thread_->Stop();
    thread_ = nullptr;

    ::benchmark::Fixture::TearDown(st);
  }

  void callback() { promise_.set_value(); }

  void callback_batch() {
    counter_++;
    if (counter_ >= iteration_count_) {
      promise_.set_value();
    }
  }

  void set_iteration_count(int iteration_count) { iteration_count_ = iteration_count; }

  int counter_;
  int iteration_count_;
  std::promise<void> promise_;
  std::unique_ptr<Thread> thread_;
  std::unique_ptr<Handler> handler_;
};

/**
 * Benchmark the accuracy of the timer in the handler, PostWithDelay().
 * Argument "Arg" is the delay in milliseconds.
 * Expectation is that the iteration should complete within the delay time.
 */
BENCHMARK_DEFINE_F(BM_HandlerPerformance, delayed_task_accuracy)(State& state) {
  for (auto _ : state) {
    auto delay = std::chrono::milliseconds(state.range(0));  // delay in milliseconds
    std::future<void> future = promise_.get_future();
    auto start_time = std::chrono::system_clock::now();
    handler_->PostWithDelay(
            BindOnce(&BM_HandlerPerformance_delayed_task_accuracy_Benchmark::callback,
                     bluetooth::common::Unretained(this)),
            delay);
    future.wait();
    auto end_time = std::chrono::system_clock::now();
    auto duration = end_time - start_time;
    state.SetIterationTime(static_cast<double>(duration.count()) *
                           1e-6);  // convert to milliseconds
  }
}

// Argument "Arg" is the delay in milliseconds.
BENCHMARK_REGISTER_F(BM_HandlerPerformance, delayed_task_accuracy)
        ->Arg(1)
        ->Arg(10)
        ->Arg(50)
        ->Arg(100)
        ->Arg(500)
        ->Arg(1000)
        ->Iterations(1)
        ->UseRealTime();

/**
 * Benchmark the competence of the delayes_tasks_ queue, post back to back calls with same delay,
 * and see if these are executed within the same time, claiming that processing post expiry is done
 * in batch.
 *
 * Argument "Arg" is defined as:
 *  [0] -> Number of posts to the handler,
 *  [1] -> delay in milliseconds
 *
 * Expectation is that the iteration should complete within the same time as delay (approximately).
 * But with higher number of posts, the time taken may be some multiples of the delay.
 */
BENCHMARK_DEFINE_F(BM_HandlerPerformance, post_task_accuracy)(State& state) {
  for (auto _ : state) {
    // [0] -> Iteration, [1] -> delay in milliseconds
    auto iteration = state.range(0);
    auto delay = std::chrono::milliseconds(state.range(1));  // delay in milliseconds
    std::future<void> future = promise_.get_future();
    auto start_time = std::chrono::system_clock::now();
    set_iteration_count(iteration);
    for (int i = 0; i < iteration; i++) {
      handler_->PostWithDelay(
              BindOnce(&BM_HandlerPerformance_post_task_accuracy_Benchmark::callback_batch,
                       bluetooth::common::Unretained(this)),
              delay);
    }
    future.wait();
    auto end_time = std::chrono::system_clock::now();
    auto duration = end_time - start_time;
    state.SetIterationTime(static_cast<double>(duration.count()) *
                           1e-6);  // convert to milliseconds
  }
}

BENCHMARK_REGISTER_F(BM_HandlerPerformance, post_task_accuracy)
        ->Args({2000, 1})
        ->Args({2000, 10})
        ->Args({2000, 50})
        ->Args({2000, 100})
        ->Args({2000, 500})
        ->Args({2000, 1000})
        ->Iterations(1)
        ->UseRealTime();

/**
 * Benchmark the competence of the delayes_tasks_ queue, post back to back calls with different
 * delays (range provided, [10, 100] -> 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
 *
 * Argument "Arg" is defined as:
 *  [0] -> delay start in milliseconds,
 *  [1] -> delay end in milliseconds
 *
 * The test is run for (delay_end - delay_start)/10 iterations, each 10ms apart.
 * Expectation is that each iteration should complete within the maximum delay time from the range.
 */
BENCHMARK_DEFINE_F(BM_HandlerPerformance, post_task_accuracy_varying_delay)(State& state) {
  for (auto _ : state) {
    // [1] -> delay start, [2] -> delay end
    auto delay_start = state.range(0);
    auto delay_end = state.range(1);
    std::future<void> future = promise_.get_future();
    auto start_time = std::chrono::system_clock::now();

    // Post tasks with increasing delay, each 10ms apart.
    auto iteration = ((delay_end - delay_start) / 10) + 1;
    set_iteration_count(iteration);
    for (int i = 0; i <= iteration; i++) {
      handler_->PostWithDelay(
              BindOnce(&BM_HandlerPerformance_post_task_accuracy_varying_delay_Benchmark::
                               callback_batch,
                       bluetooth::common::Unretained(this)),
              std::chrono::milliseconds(delay_start + i * 10));
    }
    future.wait();
    auto end_time = std::chrono::system_clock::now();
    auto duration = end_time - start_time;
    state.SetIterationTime(static_cast<double>(duration.count()) *
                           1e-6);  // convert to milliseconds
  }
}

BENCHMARK_REGISTER_F(BM_HandlerPerformance, post_task_accuracy_varying_delay)
        ->Args({10, 100})
        ->Args({110, 200})
        ->Args({210, 300})
        ->Args({310, 400})
        ->Args({410, 500})
        ->Args({510, 600})
        ->Args({610, 700})
        ->Args({710, 800})
        ->Args({810, 900})
        ->Args({910, 1000})
        ->Iterations(1)
        ->UseRealTime();
