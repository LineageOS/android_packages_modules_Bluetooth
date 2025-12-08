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

#pragma once

#include <cstdint>

#include "osi/include/alarm.h"

int get_alarm_set_on_mloop_call_count(const char* alarm_name);
int get_alarm_cancel_call_count(const char* alarm_name);
void reset_alarm_mock_function_count_map();

struct fake_osi_alarm_set_on_mloop {
  alarm_t* alarm;
  uint64_t interval_ms{0};
  alarm_callback_t cb{};
  void* data{nullptr};
};

void fake_osi_alarm_expired(struct fake_osi_alarm_set_on_mloop& fake_alarm,
                            bool is_periodic = false);
