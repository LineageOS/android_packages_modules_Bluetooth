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

/* LHDC V3 Media Codec Capabilities, from the LOSC octet:
 *
 *   H0        LOSC, A2DP_LHDCV3_CODEC_LEN
 *   H1        media type
 *   H2        codec type, non-A2DP
 *   P0-P3     vendor ID, little endian
 *   P4-P5     codec ID, little endian
 *   P6        sample rates, bit depth, JAS, AR
 *   P7        version, max target bit rate, low latency, LLAC
 *   P8        channel split, meta, min bit rate, LARC, V4
 */
#define A2DP_LHDCV3_CODEC_LEN 11

#define A2DP_LHDC_VENDOR_ID_V3 0x053A
#define A2DP_LHDCV3_CODEC_ID 0x4C33

/* P6 */
#define A2DP_LHDCV3_SAMPLING_FREQ_MASK (0x0F)
#define A2DP_LHDCV3_SAMPLING_FREQ_44100 (0x08)
#define A2DP_LHDCV3_SAMPLING_FREQ_48000 (0x04)
#define A2DP_LHDCV3_SAMPLING_FREQ_88200 (0x02)
#define A2DP_LHDCV3_SAMPLING_FREQ_96000 (0x01)
#define A2DP_LHDCV3_SAMPLING_FREQ_NS (0x00)

#define A2DP_LHDCV3_BIT_FMT_MASK (0x30)
#define A2DP_LHDCV3_BIT_FMT_24 (0x10)
#define A2DP_LHDCV3_BIT_FMT_16 (0x20)
#define A2DP_LHDCV3_BIT_FMT_NS (0x00)

#define A2DP_LHDCV3_FEATURE_JAS (0x40)
#define A2DP_LHDCV3_FEATURE_AR (0x80)

/* P7 */
#define A2DP_LHDCV3_VERSION_MASK (0x0F)
#define A2DP_LHDCV3_VER_3 (0x01)
#define A2DP_LHDCV3_VER_4 (0x02)
#define A2DP_LHDCV3_VER_NS (0x00)

#define A2DP_LHDCV3_MAX_BIT_RATE_MASK (0x30)
#define A2DP_LHDCV3_MAX_BIT_RATE_900K (0x00)
#define A2DP_LHDCV3_MAX_BIT_RATE_500K (0x10)
#define A2DP_LHDCV3_MAX_BIT_RATE_400K (0x20)

#define A2DP_LHDCV3_FEATURE_LL (0x40)
#define A2DP_LHDCV3_FEATURE_LLAC (0x80)

/* P8 */
#define A2DP_LHDCV3_CH_SPLIT_MASK (0x0F)
#define A2DP_LHDCV3_CH_SPLIT_NONE (0x01)
#define A2DP_LHDCV3_CH_SPLIT_TWS (0x02)
#define A2DP_LHDCV3_CH_SPLIT_NS (0x00)

#define A2DP_LHDCV3_FEATURE_META (0x10)
#define A2DP_LHDCV3_FEATURE_MIN_BR (0x20)
#define A2DP_LHDCV3_FEATURE_LARC (0x40)
#define A2DP_LHDCV3_FEATURE_V4 (0x80)

/* Samples one frame carries, at every rate and depth. */
#define A2DP_LHDCV3_BLOCK_SAMPLES 256

/* The media payload header the transport adds ahead of the frames, carrying
 * how many of them the packet holds. */
#define A2DP_LHDCV3_MPL_HDR_LEN 2
#define A2DP_LHDCV3_HDR_NUM_SHIFT 2
