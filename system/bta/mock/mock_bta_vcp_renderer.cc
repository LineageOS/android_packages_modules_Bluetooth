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

#include "bta/include/bta_vcp_renderer_api.h"
#include "bta/vcp/vcs_server.h"
#include "test/common/mock_functions.h"

namespace bluetooth::vcp {

void VolumeRenderer::Initialize(VolumeRendererCallbacks* /* callbacks */,
                                const VolumeRendererConfig& /* config */,
                                VcpServicesFactory* /* factory */) {
  inc_func_call_count(__func__);
}

void VolumeRenderer::Cleanup(void) { inc_func_call_count(__func__); }

VolumeRenderer* VolumeRenderer::Get(void) {
  inc_func_call_count(__func__);
  return nullptr;
}

void VolumeRenderer::DebugDump(int /* fd */) { inc_func_call_count(__func__); }

}  // namespace bluetooth::vcp

namespace bluetooth::vcs {

std::shared_ptr<VcsServer> InstantiateVcsServer(void) {
  inc_func_call_count(__func__);
  return nullptr;
}

void ReleaseVcsServer(std::shared_ptr<VcsServer> /*vcs*/) { inc_func_call_count(__func__); }

}  // namespace bluetooth::vcs
