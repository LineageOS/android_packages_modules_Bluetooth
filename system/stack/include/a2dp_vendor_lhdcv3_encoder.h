/*
 * Copyright (C) 2026, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Interface to the A2DP LHDC V3 encoder.

#pragma once

#include <cstddef>
#include <cstdint>

#include "stack/include/a2dp_codec_api.h"

bool A2DP_VendorLoadEncoderLhdcV3(void);
bool A2DP_VendorUnloadEncoderLhdcV3(void);

void a2dp_vendor_lhdcv3_encoder_init(const tA2DP_ENCODER_INIT_PEER_PARAMS* p_peer_params,
                                     A2dpCodecConfig* a2dp_codec_config,
                                     a2dp_source_read_callback_t read_callback,
                                     a2dp_source_enqueue_callback_t enqueue_callback);
void a2dp_vendor_lhdcv3_encoder_cleanup(void);
void a2dp_vendor_lhdcv3_feeding_reset(void);
void a2dp_vendor_lhdcv3_feeding_flush(void);
uint64_t a2dp_vendor_lhdcv3_get_encoder_interval_ms(void);
int a2dp_vendor_lhdcv3_get_effective_frame_size(void);
void a2dp_vendor_lhdcv3_send_frames(uint64_t timestamp_us);
void a2dp_vendor_lhdcv3_set_transmit_queue_length(size_t transmit_queue_length);

// Bitrate the encoder is currently transmitting at, in kbps.
int a2dp_vendor_lhdcv3_get_bitrate(void);
