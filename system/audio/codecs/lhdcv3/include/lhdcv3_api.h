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

#pragma once

#include <stdint.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* HANDLE_LHDC_V3;
typedef int32_t STATUS_LHDC_V3;

#define LHDC_V3_SUCCESS 0
#define LHDC_V3_ERROR_PARAM (-1)
#define LHDC_V3_ERROR_STATE (-2)
#define LHDC_V3_ERROR_ROOM (-3)

STATUS_LHDC_V3 lhdcv3_enc_ffi_get_handle(HANDLE_LHDC_V3* handle);

STATUS_LHDC_V3 lhdcv3_enc_ffi_free_handle(HANDLE_LHDC_V3 handle);

/* sample_rate: 44100 | 48000 | 96000
 * bits_per_sample: 16 | 24
 * bitrate_index: as the A2DP quality mode selects
 * mtu: bytes one media packet may carry
 * interval_ms: how much audio one packet should cover */
STATUS_LHDC_V3 lhdcv3_enc_ffi_init_encoder(HANDLE_LHDC_V3 handle, uint32_t sample_rate,
                                           uint32_t bits_per_sample, uint32_t bitrate_index,
                                           uint32_t mtu, uint32_t interval_ms);

/* Samples per channel one encode call takes. */
STATUS_LHDC_V3 lhdcv3_enc_ffi_get_block_size(HANDLE_LHDC_V3 handle, uint32_t* samples);

/* Sets the rate the coder aims at, for a stream whose rate adapts. Refused
 * where the rate would change whether the stream is halved. */
STATUS_LHDC_V3 lhdcv3_enc_ffi_set_bitrate(HANDLE_LHDC_V3 handle, uint32_t kbps);

/* Drops whatever the coder holds, for a stream being flushed. */
STATUS_LHDC_V3 lhdcv3_enc_ffi_flush(HANDLE_LHDC_V3 handle);

/* Codes one block of interleaved stereo PCM. Writes a packet's worth of frames
 * when one is ready, and nothing otherwise; written_frames says how many. */
STATUS_LHDC_V3 lhdcv3_enc_ffi_encode(HANDLE_LHDC_V3 handle, const uint8_t* pcm, size_t pcm_len,
                                     uint8_t* out, size_t out_len, uint32_t* written_bytes,
                                     uint32_t* written_frames);

#ifdef __cplusplus
}
#endif
