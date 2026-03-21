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

#include <base/functional/callback.h>

#include "bta/include/bta_mcp_client_api.h"
#include "test/common/mock_functions.h"

namespace bluetooth {
namespace mcp {

void McpClient::Initialize(McpClientCallbacks* /* callbacks */, base::OnceClosure /* initCb */) {
  inc_func_call_count(__func__);
}

void McpClient::Cleanup(void) { inc_func_call_count(__func__); }

McpClient* McpClient::Get(void) {
  inc_func_call_count(__func__);
  return nullptr;
}

void McpClient::AddFromStorage(const RawAddress& /* address */) { inc_func_call_count(__func__); }

void McpClient::DebugDump(int /* fd */) { inc_func_call_count(__func__); }

}  // namespace mcp
}  // namespace bluetooth
