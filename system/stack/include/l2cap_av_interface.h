/*
 *  Copyright 2025 LineageOS
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
 */

#pragma once

#include <cstdint>
#include <base/functional/callback.h>
// This header contains functions for BTA advanced audio/video to invoke

using tL2C_COEX_READY = base::OnceCallback<void(bool)>;
void l2c_link_set_br_coex_buf_cap(uint16_t bufs_to_reserve, tL2C_COEX_READY cb);
