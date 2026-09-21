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

#define LOG_TAG "a2dp_vendor_lhdcv3_encoder"

#include "stack/include/a2dp_vendor_lhdcv3_encoder.h"

#include <bluetooth/log.h>
#include <lhdcv3_api.h>
#include <stdio.h>
#include <string.h>

#include <cstdint>

#include "internal_include/bt_target.h"

#include "common/time_util.h"
#include "osi/include/allocator.h"
#include "stack/include/a2dp_vendor_lhdcv3.h"
// The quality mode numbering is shared across LHDC versions.
#include "stack/include/a2dp_vendor_lhdcv5_constants.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/avdt_api.h"

using namespace bluetooth;

#if (BTA_AV_CO_CP_SCMS_T == TRUE)
#define A2DP_LHDCV3_OFFSET (AVDT_MEDIA_OFFSET + A2DP_LHDCV3_MPL_HDR_LEN + 1)
#else
#define A2DP_LHDCV3_OFFSET (AVDT_MEDIA_OFFSET + A2DP_LHDCV3_MPL_HDR_LEN)
#endif

#define A2DP_LHDCV3_MAX_PCM_BYTES (A2DP_LHDCV3_BLOCK_SAMPLES * 2 * 4)

typedef struct {
  uint32_t sample_rate;
  uint32_t bits_per_sample;
  uint32_t quality_mode_index;
  uint32_t max_target_bitrate;
} tA2DP_LHDCV3_ENCODER_PARAMS;

// How the rate is moved when the stream adapts. A queue this long says the
// link is not keeping up; this many ticks with nothing waiting says it is
// keeping up with room to spare.
#define A2DP_LHDCV3_ABR_QUEUE_FULL 6
#define A2DP_LHDCV3_ABR_QUIET_TICKS 50

typedef struct {
  bool adapting;
  uint32_t quality;
  uint32_t ceiling;
  uint32_t quiet_ticks;
  uint32_t drops;
} tA2DP_LHDCV3_ABR_STATE;

typedef struct {
  uint32_t counter;
  uint32_t bytes_per_tick;
  uint64_t last_frame_us;
} tA2DP_LHDCV3_FEEDING_STATE;

typedef struct {
  a2dp_source_read_callback_t read_callback;
  a2dp_source_enqueue_callback_t enqueue_callback;
  uint32_t TxAaMtuSize;
  uint32_t TxQueueLength;
  tA2DP_LHDCV3_ABR_STATE abr;

  uint16_t peer_mtu;
  uint32_t timestamp;

  HANDLE_LHDC_V3 lhdc_handle;
  bool has_lhdc_handle;

  tA2DP_FEEDING_PARAMS feeding_params;
  tA2DP_LHDCV3_ENCODER_PARAMS encoder_params;
  tA2DP_LHDCV3_FEEDING_STATE feeding_state;
} tA2DP_LHDCV3_ENCODER_CB;

static tA2DP_LHDCV3_ENCODER_CB a2dp_lhdcv3_encoder_cb;

static bool a2dp_lhdcv3_read_feeding(uint8_t* read_buffer, uint32_t* bytes_read);
static void a2dp_lhdcv3_encode_blocks(uint8_t nb_blocks);
static void a2dp_lhdcv3_get_num_blocks(uint8_t* num_of_blocks, uint64_t timestamp_us);

bool A2DP_VendorLoadEncoderLhdcV3(void) {
  memset(&a2dp_lhdcv3_encoder_cb, 0, sizeof(a2dp_lhdcv3_encoder_cb));
  return true;
}

bool A2DP_VendorUnloadEncoderLhdcV3(void) {
  a2dp_vendor_lhdcv3_encoder_cleanup();
  return true;
}

static BT_HDR* bt_buf_new(void) {
  BT_HDR* p_buf = (BT_HDR*)osi_malloc(BT_DEFAULT_BUFFER_SIZE);
  if (p_buf == nullptr) {
    log::error("cannot allocate a media buffer");
    return nullptr;
  }
  p_buf->offset = A2DP_LHDCV3_OFFSET;
  p_buf->len = 0;
  p_buf->layer_specific = 0;
  return p_buf;
}

static uint32_t a2dp_lhdcv3_kbps_for_quality(uint32_t quality) {
  switch (quality) {
    case A2DP_LHDC_QUALITY_LOW0:
      return 64;
    case A2DP_LHDC_QUALITY_LOW1:
      return 128;
    case A2DP_LHDC_QUALITY_LOW2:
      return 192;
    case A2DP_LHDC_QUALITY_LOW3:
      return 256;
    case A2DP_LHDC_QUALITY_LOW4:
      return 320;
    case A2DP_LHDC_QUALITY_LOW:
      return 400;
    case A2DP_LHDC_QUALITY_MID:
      return 500;
    case A2DP_LHDC_QUALITY_HIGH1:
      return 1000;
    default:
      return 900;
  }
}

static uint32_t A2DP_QualityForMaxBitRateLhdcV3Kbps(uint32_t kbps) {
  uint32_t quality = A2DP_LHDC_QUALITY_LOW0;
  for (uint32_t i = A2DP_LHDC_QUALITY_LOW0; i <= A2DP_LHDC_QUALITY_HIGH1; i++) {
    if (a2dp_lhdcv3_kbps_for_quality(i) <= kbps) {
      quality = i;
    }
  }
  return quality;
}

void a2dp_vendor_lhdcv3_encoder_init(const tA2DP_ENCODER_INIT_PEER_PARAMS* p_peer_params,
                                     A2dpCodecConfig* a2dp_codec_config,
                                     a2dp_source_read_callback_t read_callback,
                                     a2dp_source_enqueue_callback_t enqueue_callback) {
  if (a2dp_codec_config == nullptr || p_peer_params == nullptr) {
    log::error("null input");
    return;
  }
  a2dp_vendor_lhdcv3_encoder_cleanup();

  a2dp_lhdcv3_encoder_cb.read_callback = read_callback;
  a2dp_lhdcv3_encoder_cb.enqueue_callback = enqueue_callback;
  a2dp_lhdcv3_encoder_cb.peer_mtu = p_peer_params->peer_mtu;
  a2dp_lhdcv3_encoder_cb.timestamp = 0;

  uint8_t codec_info[AVDT_CODEC_SIZE];
  if (!a2dp_codec_config->copyOutOtaCodecConfig(codec_info)) {
    log::error("cannot read the codec configuration");
    return;
  }

  btav_a2dp_codec_config_t codec_config = a2dp_codec_config->getCodecConfig();
  a2dp_lhdcv3_encoder_cb.feeding_params.sample_rate =
          (tA2DP_SAMPLE_RATE)A2DP_VendorGetTrackSampleRateLhdcV3(codec_info);
  a2dp_lhdcv3_encoder_cb.feeding_params.bits_per_sample =
          a2dp_codec_config->getAudioBitsPerSample();
  a2dp_lhdcv3_encoder_cb.feeding_params.channel_count =
          (tA2DP_CHANNEL_COUNT)A2DP_VendorGetTrackChannelCountLhdcV3(codec_info);

  a2dp_lhdcv3_encoder_cb.encoder_params.sample_rate =
          a2dp_lhdcv3_encoder_cb.feeding_params.sample_rate;
  a2dp_lhdcv3_encoder_cb.encoder_params.bits_per_sample =
          (uint32_t)A2DP_VendorGetTrackBitsPerSampleLhdcV3(codec_info);
  a2dp_lhdcv3_encoder_cb.encoder_params.quality_mode_index =
          (uint32_t)(codec_config.codec_specific_1 & A2DP_LHDC_QUALITY_MASK);
  if (!A2DP_VendorGetMaxBitRateLhdcV3(&a2dp_lhdcv3_encoder_cb.encoder_params.max_target_bitrate,
                                      codec_info)) {
    a2dp_lhdcv3_encoder_cb.encoder_params.max_target_bitrate = 900;
  }

  uint32_t mtu = a2dp_lhdcv3_encoder_cb.peer_mtu;
  uint32_t room = BT_DEFAULT_BUFFER_SIZE - A2DP_LHDCV3_OFFSET - sizeof(BT_HDR);
  if (mtu > room) {
    mtu = room;
  }
  a2dp_lhdcv3_encoder_cb.TxAaMtuSize = mtu;

  if (lhdcv3_enc_ffi_get_handle(&a2dp_lhdcv3_encoder_cb.lhdc_handle) != LHDC_V3_SUCCESS) {
    log::error("cannot get an encoder handle");
    return;
  }
  a2dp_lhdcv3_encoder_cb.has_lhdc_handle = true;

  uint32_t payload_mtu = a2dp_lhdcv3_encoder_cb.TxAaMtuSize > A2DP_LHDCV3_MPL_HDR_LEN
                                 ? a2dp_lhdcv3_encoder_cb.TxAaMtuSize - A2DP_LHDCV3_MPL_HDR_LEN
                                 : 0;
  // A stream whose rate adapts opens at the best rung the peer allows and
  // falls from there, so that is the rate the coder starts at: the rungs it
  // can reach later are the ones that leave halving as it is.
  a2dp_lhdcv3_encoder_cb.abr.adapting =
          a2dp_lhdcv3_encoder_cb.encoder_params.quality_mode_index == A2DP_LHDC_QUALITY_ABR;
  a2dp_lhdcv3_encoder_cb.abr.ceiling = A2DP_QualityForMaxBitRateLhdcV3Kbps(
          a2dp_lhdcv3_encoder_cb.encoder_params.max_target_bitrate);
  a2dp_lhdcv3_encoder_cb.abr.quality = a2dp_lhdcv3_encoder_cb.abr.ceiling;
  a2dp_lhdcv3_encoder_cb.abr.quiet_ticks = 0;
  uint32_t opening_quality = a2dp_lhdcv3_encoder_cb.abr.adapting
                                     ? a2dp_lhdcv3_encoder_cb.abr.ceiling
                                     : a2dp_lhdcv3_encoder_cb.encoder_params.quality_mode_index;

  int32_t ret = lhdcv3_enc_ffi_init_encoder(
          a2dp_lhdcv3_encoder_cb.lhdc_handle, a2dp_lhdcv3_encoder_cb.encoder_params.sample_rate,
          a2dp_lhdcv3_encoder_cb.encoder_params.bits_per_sample, opening_quality, payload_mtu,
          (uint32_t)a2dp_vendor_lhdcv3_get_encoder_interval_ms());
  if (ret != LHDC_V3_SUCCESS) {
    log::error("cannot initialise the encoder: {}", ret);
    a2dp_vendor_lhdcv3_encoder_cleanup();
    return;
  }

  log::info("rate {} depth {} quality {} mtu {} adapting {}",
            a2dp_lhdcv3_encoder_cb.encoder_params.sample_rate,
            a2dp_lhdcv3_encoder_cb.encoder_params.bits_per_sample, opening_quality, payload_mtu,
            a2dp_lhdcv3_encoder_cb.abr.adapting);
  a2dp_vendor_lhdcv3_feeding_reset();
}

void a2dp_vendor_lhdcv3_encoder_cleanup(void) {
  if (a2dp_lhdcv3_encoder_cb.has_lhdc_handle && a2dp_lhdcv3_encoder_cb.lhdc_handle != nullptr) {
    lhdcv3_enc_ffi_free_handle(a2dp_lhdcv3_encoder_cb.lhdc_handle);
  }
  memset(&a2dp_lhdcv3_encoder_cb, 0, sizeof(a2dp_lhdcv3_encoder_cb));
}

void a2dp_vendor_lhdcv3_feeding_reset(void) {
  memset(&a2dp_lhdcv3_encoder_cb.feeding_state, 0,
         sizeof(a2dp_lhdcv3_encoder_cb.feeding_state));
  if (a2dp_lhdcv3_encoder_cb.feeding_params.sample_rate == 0) {
    return;
  }
  a2dp_lhdcv3_encoder_cb.feeding_state.bytes_per_tick =
          a2dp_lhdcv3_encoder_cb.feeding_params.sample_rate *
          a2dp_lhdcv3_encoder_cb.feeding_params.bits_per_sample / 8 *
          a2dp_lhdcv3_encoder_cb.feeding_params.channel_count *
          a2dp_vendor_lhdcv3_get_encoder_interval_ms() / 1000;
  a2dp_lhdcv3_encoder_cb.feeding_state.last_frame_us = 0;
}

void a2dp_vendor_lhdcv3_feeding_flush(void) {
  a2dp_lhdcv3_encoder_cb.feeding_state.counter = 0;
  if (a2dp_lhdcv3_encoder_cb.has_lhdc_handle) {
    lhdcv3_enc_ffi_flush(a2dp_lhdcv3_encoder_cb.lhdc_handle);
  }
}

uint64_t a2dp_vendor_lhdcv3_get_encoder_interval_ms(void) { return 20; }

int a2dp_vendor_lhdcv3_get_effective_frame_size(void) {
  return a2dp_lhdcv3_encoder_cb.TxAaMtuSize;
}

void a2dp_vendor_lhdcv3_set_transmit_queue_length(size_t transmit_queue_length) {
  a2dp_lhdcv3_encoder_cb.TxQueueLength = transmit_queue_length;
}

int a2dp_vendor_lhdcv3_get_bitrate(void) {
  if (a2dp_lhdcv3_encoder_cb.abr.adapting) {
    return (int)a2dp_lhdcv3_kbps_for_quality(a2dp_lhdcv3_encoder_cb.abr.quality) * 1000;
  }
  switch (a2dp_lhdcv3_encoder_cb.encoder_params.quality_mode_index) {
    case A2DP_LHDC_QUALITY_LOW0:
      return 64000;
    case A2DP_LHDC_QUALITY_LOW1:
      return 128000;
    case A2DP_LHDC_QUALITY_LOW2:
      return 192000;
    case A2DP_LHDC_QUALITY_LOW3:
      return 256000;
    case A2DP_LHDC_QUALITY_LOW4:
      return 320000;
    case A2DP_LHDC_QUALITY_LOW:
      return 400000;
    case A2DP_LHDC_QUALITY_MID:
      return 500000;
    case A2DP_LHDC_QUALITY_HIGH1:
      return 1000000;
    default:
      return 900000;
  }
}

static void a2dp_lhdcv3_get_num_blocks(uint8_t* num_of_blocks, uint64_t timestamp_us) {
  uint32_t bytes_per_block = A2DP_LHDCV3_BLOCK_SAMPLES *
                             a2dp_lhdcv3_encoder_cb.feeding_params.channel_count *
                             a2dp_lhdcv3_encoder_cb.feeding_params.bits_per_sample / 8;
  if (bytes_per_block == 0) {
    *num_of_blocks = 0;
    return;
  }

  uint64_t last = a2dp_lhdcv3_encoder_cb.feeding_state.last_frame_us;
  uint32_t due = a2dp_lhdcv3_encoder_cb.feeding_state.bytes_per_tick;
  if (last != 0) {
    uint64_t elapsed_us = timestamp_us - last;
    due = (uint32_t)(a2dp_lhdcv3_encoder_cb.feeding_params.sample_rate *
                     a2dp_lhdcv3_encoder_cb.feeding_params.channel_count *
                     a2dp_lhdcv3_encoder_cb.feeding_params.bits_per_sample / 8 * elapsed_us /
                     1000000);
  }
  a2dp_lhdcv3_encoder_cb.feeding_state.last_frame_us = timestamp_us;
  a2dp_lhdcv3_encoder_cb.feeding_state.counter += due;

  uint32_t blocks = a2dp_lhdcv3_encoder_cb.feeding_state.counter / bytes_per_block;
  a2dp_lhdcv3_encoder_cb.feeding_state.counter -= blocks * bytes_per_block;
  if (blocks > UINT8_MAX) {
    blocks = UINT8_MAX;
  }
  *num_of_blocks = (uint8_t)blocks;
}

static bool a2dp_lhdcv3_read_feeding(uint8_t* read_buffer, uint32_t* bytes_read) {
  uint32_t bytes_per_sample = a2dp_lhdcv3_encoder_cb.feeding_params.channel_count *
                              a2dp_lhdcv3_encoder_cb.feeding_params.bits_per_sample / 8;
  if (read_buffer == nullptr || bytes_read == nullptr || bytes_per_sample == 0) {
    return false;
  }
  uint32_t read_size = A2DP_LHDCV3_BLOCK_SAMPLES * bytes_per_sample;
  if (read_size > A2DP_LHDCV3_MAX_PCM_BYTES) {
    log::error("a block needs {} bytes, more than the read buffer holds", read_size);
    return false;
  }

  uint32_t got = a2dp_lhdcv3_encoder_cb.read_callback(read_buffer, read_size);
  if (got == 0) {
    return false;
  }
  if (got < read_size) {
    memset(read_buffer + got, 0, read_size - got);
  }
  *bytes_read = read_size;
  return true;
}

static void a2dp_lhdcv3_encode_blocks(uint8_t nb_blocks) {
  if (!a2dp_lhdcv3_encoder_cb.has_lhdc_handle || a2dp_lhdcv3_encoder_cb.lhdc_handle == nullptr) {
    log::error("the encoder has no handle");
    return;
  }

  uint8_t read_buffer[A2DP_LHDCV3_MAX_PCM_BYTES];
  for (uint8_t i = 0; i < nb_blocks; i++) {
    uint32_t bytes_read = 0;
    if (!a2dp_lhdcv3_read_feeding(read_buffer, &bytes_read)) {
      return;
    }

    BT_HDR* p_buf = bt_buf_new();
    if (p_buf == nullptr) {
      return;
    }
    uint8_t* packet = (uint8_t*)(p_buf + 1) + p_buf->offset;
    uint32_t room = BT_DEFAULT_BUFFER_SIZE - p_buf->offset - sizeof(BT_HDR);
    uint32_t written = 0;
    uint32_t frames = 0;
    int32_t ret = lhdcv3_enc_ffi_encode(a2dp_lhdcv3_encoder_cb.lhdc_handle, read_buffer,
                                        bytes_read, packet, room, &written, &frames);
    if (ret != LHDC_V3_SUCCESS) {
      log::error("the encoder refused a block: {}", ret);
      a2dp_lhdcv3_encoder_cb.abr.drops++;
      osi_free(p_buf);
      return;
    }
    if (written == 0 || frames == 0) {
      osi_free(p_buf);
      a2dp_lhdcv3_encoder_cb.timestamp += A2DP_LHDCV3_BLOCK_SAMPLES;
      continue;
    }

    p_buf->len = written;
    p_buf->layer_specific = frames;
    *((uint32_t*)(p_buf + 1)) = a2dp_lhdcv3_encoder_cb.timestamp;
    a2dp_lhdcv3_encoder_cb.timestamp += A2DP_LHDCV3_BLOCK_SAMPLES;

    if (!a2dp_lhdcv3_encoder_cb.enqueue_callback(p_buf, frames, written)) {
      return;
    }
  }
}

// The queue the stack has yet to send is what the link's headroom looks like
// from here: it grows when the rate is too high for the link and empties when
// it is not. A rate that would change whether the stream is halved is refused
// by the coder, so the ladder settles on one side of that on its own.
static void a2dp_lhdcv3_adapt_bitrate(void) {
  tA2DP_LHDCV3_ABR_STATE* abr = &a2dp_lhdcv3_encoder_cb.abr;
  if (!abr->adapting || !a2dp_lhdcv3_encoder_cb.has_lhdc_handle) {
    return;
  }
  uint32_t wanted = abr->quality;
  if (a2dp_lhdcv3_encoder_cb.TxQueueLength >= A2DP_LHDCV3_ABR_QUEUE_FULL) {
    if (wanted > A2DP_LHDC_QUALITY_LOW0) {
      wanted--;
    }
    abr->quiet_ticks = 0;
  } else if (a2dp_lhdcv3_encoder_cb.TxQueueLength == 0) {
    abr->quiet_ticks++;
    if (abr->quiet_ticks >= A2DP_LHDCV3_ABR_QUIET_TICKS) {
      if (wanted < abr->ceiling) {
        wanted++;
      }
      abr->quiet_ticks = 0;
    }
  } else {
    abr->quiet_ticks = 0;
  }
  if (wanted == abr->quality) {
    return;
  }
  uint32_t kbps = a2dp_lhdcv3_kbps_for_quality(wanted);
  if (lhdcv3_enc_ffi_set_bitrate(a2dp_lhdcv3_encoder_cb.lhdc_handle, kbps) != LHDC_V3_SUCCESS) {
    return;
  }
  log::info("rate now {} kbps, queue {}", kbps, a2dp_lhdcv3_encoder_cb.TxQueueLength);
  abr->quality = wanted;
}

void a2dp_vendor_lhdcv3_send_frames(uint64_t timestamp_us) {
  uint8_t nb_blocks = 0;
  a2dp_lhdcv3_adapt_bitrate();
  a2dp_lhdcv3_get_num_blocks(&nb_blocks, timestamp_us);
  if (nb_blocks == 0) {
    return;
  }
  a2dp_lhdcv3_encode_blocks(nb_blocks);
}

void A2dpCodecConfigLhdcV3Source::debug_codec_dump(int fd) {
  A2dpCodecConfig::debug_codec_dump(fd);
  dprintf(fd, "  LHDC V3 transmission bitrate (bps)                      : %d\n",
          a2dp_vendor_lhdcv3_get_bitrate());
  dprintf(fd, "  LHDC V3 saved transmit queue length                     : %zu\n",
          (size_t)a2dp_lhdcv3_encoder_cb.TxQueueLength);
  dprintf(fd, "  LHDC V3 rate adapts                                     : %s\n",
          a2dp_lhdcv3_encoder_cb.abr.adapting ? "true" : "false");
  dprintf(fd, "  LHDC V3 blocks the encoder refused                      : %u\n",
          a2dp_lhdcv3_encoder_cb.abr.drops);
}
