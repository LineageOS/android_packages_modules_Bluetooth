/*
 * Copyright 2025  The Android Open Source Project
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

#include "include/hardware/bt_csis.h"
#include "include/hardware/bt_has.h"
#include "include/hardware/bt_le_audio.h"
#include "include/hardware/bt_vc.h"

bluetooth::le_audio::LeAudioClientInterface* btif_le_audio_get_interface();
bluetooth::le_audio::LeAudioBroadcasterInterface* btif_le_audio_broadcaster_get_interface();
bluetooth::vc::VolumeControlInterface* btif_volume_control_get_interface();
bluetooth::csis::CsisClientInterface* btif_csis_client_get_interface();
bluetooth::has::HasClientInterface* btif_has_client_get_interface();
