/*
 * Copyright 2022 The Android Open Source Project
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

#include "btm_sco_hfp_hal.h"

#include "device/include/esco_parameters.h"
#include "osi/include/properties.h"

namespace hfp_hal_interface {

void init() {}

// This is not used in Android.
bool is_coding_format_supported(esco_coding_format_t /* coding_format */) { return true; }

// Android statically compiles WBS support.
bool get_wbs_supported() { return true; }

bool get_swb_supported() { return osi_property_get_bool("bluetooth.hfp.swb.supported", false); }

// Check if hardware offload is enabled
bool get_offload_enabled() { return true; }

// This is not used in Android.
bool enable_offload(bool /* enable */) { return true; }

// On Android, this is a no-op because the settings default to offloaded case.
void set_codec_datapath(tBTA_AG_UUID_CODEC /* codec_uuid */) {}

// No packet size limits on Android since it will be offloaded.
size_t get_packet_size(int /* codec */) { return kDefaultPacketSize; }

void notify_sco_connection_change(RawAddress /* device */, bool /* is_connected */,
                                  int /* codec */) {
  // Do nothing since this is handled by Android's audio hidl.
}

// On Android, this is a no-op because the settings default to work for Android.
void update_esco_parameters(enh_esco_params_t* /* p_parms */) {}
}  // namespace hfp_hal_interface
