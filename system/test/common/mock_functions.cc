/*
 * Copyright 2021 The Android Open Source Project
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

#include "test/common/mock_functions.h"

#include <bluetooth/log.h>

#include <map>
#include <mutex>

std::mutex mutex_{};

static std::map<std::string, int>& _get_func_call_count_map() {
  static std::map<std::string, int> mock_function_count_map;
  return mock_function_count_map;
}

int get_func_call_count(const char* fn) {
  std::lock_guard<std::mutex> lock(mutex_);
  return _get_func_call_count_map()[fn];
}
void inc_func_call_count(const char* fn) {
  std::lock_guard<std::mutex> lock(mutex_);
  _get_func_call_count_map()[fn]++;
}

void reset_mock_function_count_map() {
  std::lock_guard<std::mutex> lock(mutex_);
  _get_func_call_count_map().clear();
}

int get_func_call_size() {
  std::lock_guard<std::mutex> lock(mutex_);
  return _get_func_call_count_map().size();
}

void dump_mock_function_count_map() {
  std::lock_guard<std::mutex> lock(mutex_);
  bluetooth::log::info("Mock function count map size:{}",
                       _get_func_call_count_map().size());

  for (const auto& it : _get_func_call_count_map()) {
    bluetooth::log::info("function:{}: call_count:{}", it.first, it.second);
  }
}
