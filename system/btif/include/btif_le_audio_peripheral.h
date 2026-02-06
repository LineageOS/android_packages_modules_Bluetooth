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

#pragma once

#include "include/hardware/bt_le_audio_server.h"
#include "include/hardware/bt_mcp_client.h"
#include "include/hardware/bt_vcp_renderer.h"

bluetooth::le_audio::LeAudioServerInterface* btif_le_audio_server_get_interface();
void btif_debug_le_audio_server_dump(int fd);

bluetooth::mcp::McpClientInterface* btif_mcp_client_get_interface();
bluetooth::vcp::VolumeRendererInterface* btif_vcp_renderer_get_interface();
