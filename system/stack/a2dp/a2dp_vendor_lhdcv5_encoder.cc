/*
 * Copyright (C) 2025 The Android Open Source Project
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

#define LOG_TAG "bluetooth-a2dp"

#include "stack/include/a2dp_vendor_lhdcv5_encoder.h"

#include <bluetooth/log.h>
#include <dlfcn.h>
#include <lhdcv5BT.h>
#include <stdio.h>
#include <string.h>

#include "common/time_util.h"
#include "osi/include/allocator.h"
#include "stack/include/a2dp_vendor_lhdcv5.h"

using namespace bluetooth;

//
// Encoder for LHDC Source Codec
//
// #define MCPS_FRAME_ENCODE    // estimate MCPS of encoding frame

#ifdef MCPS_FRAME_ENCODE
#define STATIS_MILLION_UNIT (1000000)
#define STATIS_KILO_UNIT (1000)
#define STATIS_CPU_FREQ_MHZ (1708800 / 1000)  // referenced CPU clock: 1708800KHz
#define MCPS_FRAME_ENCODE_DUR (60)            // statistics duration (sec)

uint64_t mcpsStat_time_dur_cnt_lhdcv5 = 0;     // statistics duration counter (sec)
uint64_t mcpsStat_all_enc_us_lhdcv5 = 0;       // total execution time(us) of current encoded frames
uint64_t mcpsStat_all_enc_frm_lhdcv5 = 0;      // total number of current encoded frames
uint64_t mcpsStat_enc_frm_per_sec_lhdcv5 = 0;  // number of frame encoded in one sec
uint64_t mcpsStat_all_time_dur_ms_lhdcv5 =
        0;  // total real time(ms) when enter final statistics calculation
#endif

// A2DP LHDC encoder interval in milliseconds
#define A2DP_LHDC_ENCODER_SHORT_INTERVAL_MS 10
#define A2DP_LHDC_ENCODER_INTERVAL_MS 20

// offset
#if (BTA_AV_CO_CP_SCMS_T == TRUE)
#define A2DP_LHDC_OFFSET (AVDT_MEDIA_OFFSET + A2DP_LHDC_MPL_HDR_LEN + 1)
#else
#define A2DP_LHDC_OFFSET (AVDT_MEDIA_OFFSET + A2DP_LHDC_MPL_HDR_LEN)
#endif

typedef struct {
  tA2DP_SAMPLE_RATE sample_rate;
  uint32_t bits_per_sample;
  uint32_t quality_mode_index;
  uint32_t pcm_fmt;
  uint32_t max_target_bitrate;
  uint32_t min_target_bitrate;
  uint8_t isLLEnabled;
  uint8_t isLLessEnabled;
} tA2DP_LHDCV5_ENCODER_PARAMS;

typedef struct {
  uint32_t counter;
  uint32_t bytes_per_tick; /* pcm bytes read each media task tick */
  uint64_t last_frame_us;
} tA2DP_LHDCV5_FEEDING_STATE;

typedef struct {
  uint64_t session_start_us;

  uint32_t media_read_total_expected_packets;
  uint32_t media_read_total_expected_reads_count;
  uint32_t media_read_total_expected_read_bytes;

  uint32_t media_read_total_dropped_packets;
  uint32_t media_read_total_actual_reads_count;
  uint32_t media_read_total_actual_read_bytes;
} a2dp_lhdcv5_encoder_stats_t;

typedef struct {
  a2dp_source_read_callback_t read_callback;
  a2dp_source_enqueue_callback_t enqueue_callback;
  uint32_t TxAaMtuSize;
  uint32_t TxQueueLength;

  bool use_SCMS_T;
  bool is_peer_edr;          // True if the peer device supports EDR
  bool peer_supports_3mbps;  // True if the peer device supports 3Mbps EDR
  uint16_t peer_mtu;         // MTU of the A2DP peer
  uint32_t timestamp;        // Timestamp for the A2DP frames

  HANDLE_LHDC_BT lhdc_handle;
  bool has_lhdc_handle;  // True if lhdc_handle is valid

  tA2DP_FEEDING_PARAMS feeding_params;
  tA2DP_LHDCV5_ENCODER_PARAMS lhdc_encoder_params;
  tA2DP_LHDCV5_FEEDING_STATE lhdc_feeding_state;

  a2dp_lhdcv5_encoder_stats_t stats;
  uint32_t buf_seq;
  uint32_t bytes_read;
} tA2DP_LHDCV5_ENCODER_CB;

static tA2DP_LHDCV5_ENCODER_CB a2dp_lhdc_encoder_cb;

static void a2dp_vendor_lhdcv5_encoder_update(uint16_t peer_mtu, A2dpCodecConfig* a2dp_codec_config,
                                              bool* p_restart_input, bool* p_restart_output,
                                              bool* p_config_updated);

static void a2dp_lhdcv5_get_num_frame_iteration(uint8_t* num_of_iterations, uint8_t* num_of_frames,
                                                uint64_t timestamp_us);

static void a2dp_lhdcV5_encode_frames(uint8_t nb_frame);

static bool a2dp_lhdcv5_read_feeding(uint8_t* read_buffer, uint32_t* bytes_read);

static std::string quality_mode_index_to_name(uint32_t quality_mode_index);

bool A2DP_VendorLoadEncoderLhdcV5(void) {
  // Initialize the control block
  memset(&a2dp_lhdc_encoder_cb, 0, sizeof(a2dp_lhdc_encoder_cb));
  return true;
}

bool A2DP_VendorUnloadEncoderLhdcV5(void) {
  // Cleanup any LHDC-related state
  log::debug("has_lhdc_handle {}", a2dp_lhdc_encoder_cb.has_lhdc_handle);

  if (a2dp_lhdc_encoder_cb.has_lhdc_handle) {
    int32_t ret = lhdcv5BT_free_handle(a2dp_lhdc_encoder_cb.lhdc_handle);
    if (ret < 0) {
      log::error("free handle error {}", ret);
      return false;
    }
  } else {
    log::error("unload encoder error");
    return false;
  }

  memset(&a2dp_lhdc_encoder_cb, 0, sizeof(a2dp_lhdc_encoder_cb));

  return true;
}

// tA2DP_ENCODER_INTERFACE::(encoder_init)
void a2dp_vendor_lhdcv5_encoder_init(const tA2DP_ENCODER_INIT_PEER_PARAMS* p_peer_params,
                                     A2dpCodecConfig* a2dp_codec_config,
                                     a2dp_source_read_callback_t read_callback,
                                     a2dp_source_enqueue_callback_t enqueue_callback) {
  if (p_peer_params == nullptr || a2dp_codec_config == nullptr || read_callback == nullptr ||
      enqueue_callback == nullptr) {
    log::error("null input");
    return;
  }

  if (a2dp_lhdc_encoder_cb.has_lhdc_handle) {
    int32_t ret = lhdcv5BT_free_handle(a2dp_lhdc_encoder_cb.lhdc_handle);
    if (ret < 0) {
      log::error("free handle error {}", ret);
      return;
    }
  }

  memset(&a2dp_lhdc_encoder_cb, 0, sizeof(a2dp_lhdc_encoder_cb));

  a2dp_lhdc_encoder_cb.stats.session_start_us = bluetooth::common::time_get_os_boottime_us();

  a2dp_lhdc_encoder_cb.read_callback = read_callback;
  a2dp_lhdc_encoder_cb.enqueue_callback = enqueue_callback;
  a2dp_lhdc_encoder_cb.is_peer_edr = p_peer_params->is_peer_edr;
  a2dp_lhdc_encoder_cb.peer_supports_3mbps = p_peer_params->peer_supports_3mbps;
  a2dp_lhdc_encoder_cb.peer_mtu = p_peer_params->peer_mtu;
  a2dp_lhdc_encoder_cb.timestamp = 0;

  a2dp_lhdc_encoder_cb.use_SCMS_T = false;  // TODO: should be a parameter
#if (BTA_AV_CO_CP_SCMS_T == TRUE)
  a2dp_lhdc_encoder_cb.use_SCMS_T = true;
#endif

  // NOTE: Ignore the restart_input / restart_output flags - this initization
  // happens when the connection is (re)started.
  bool restart_input = false;
  bool restart_output = false;
  bool config_updated = false;
  a2dp_vendor_lhdcv5_encoder_update(a2dp_lhdc_encoder_cb.peer_mtu, a2dp_codec_config,
                                    &restart_input, &restart_output, &config_updated);
}

#if 0
bool A2dpCodecConfigLhdcV5Source::updateEncoderUserConfig(
    const tA2DP_ENCODER_INIT_PEER_PARAMS* p_peer_params, bool* p_restart_input,
    bool* p_restart_output, bool* p_config_updated) {

  if (p_peer_params == nullptr || p_restart_input == nullptr ||
      p_restart_output == nullptr || p_config_updated == nullptr) {
    log::error("null input");
    return false;
  }

  if (a2dp_lhdc_encoder_cb.has_lhdc_handle) {
    lhdc_free_handle(a2dp_lhdc_encoder_cb.lhdc_handle);
    a2dp_lhdc_encoder_cb.has_lhdc_handle = false;
    log::debug("clean lhdc handle");
  }

  a2dp_lhdc_encoder_cb.is_peer_edr = p_peer_params->is_peer_edr;
  a2dp_lhdc_encoder_cb.peer_supports_3mbps = p_peer_params->peer_supports_3mbps;
  a2dp_lhdc_encoder_cb.peer_mtu = p_peer_params->peer_mtu;
  a2dp_lhdc_encoder_cb.timestamp = 0;

  if (a2dp_lhdc_encoder_cb.peer_mtu == 0) {
    log::error(
        "Cannot update the codec encoder for {}: "
        "invalid peer MTU",
        name());
    return false;
  }

  a2dp_vendor_lhdcv5_encoder_update(a2dp_lhdc_encoder_cb.peer_mtu, this,
      p_restart_input, p_restart_output,
      p_config_updated);
  return true;
}
#endif

// wrap index mapping from bt stack to codec library
static bool a2dp_vendor_lhdcv5_qualitymode_wrapper(uint32_t* out, uint32_t in) {
  if (!out) {
    return false;
  }

  switch (in) {
    case A2DP_LHDC_QUALITY_ABR:
      *out = LHDC_QUALITY_AUTO;
      return true;
    case A2DP_LHDC_QUALITY_HIGH1:
      *out = LHDC_QUALITY_HIGH1;
      return true;
    case A2DP_LHDC_QUALITY_HIGH:
      *out = LHDC_QUALITY_HIGH;
      return true;
    case A2DP_LHDC_QUALITY_MID:
      *out = LHDC_QUALITY_MID;
      return true;
    case A2DP_LHDC_QUALITY_LOW:
      *out = LHDC_QUALITY_LOW;
      return true;
    case A2DP_LHDC_QUALITY_LOW4:
      *out = LHDC_QUALITY_LOW4;
      return true;
    case A2DP_LHDC_QUALITY_LOW3:
      *out = LHDC_QUALITY_LOW3;
      return true;
    case A2DP_LHDC_QUALITY_LOW2:
      *out = LHDC_QUALITY_LOW2;
      return true;
    case A2DP_LHDC_QUALITY_LOW1:
      *out = LHDC_QUALITY_LOW1;
      return true;
    case A2DP_LHDC_QUALITY_LOW0:
      *out = LHDC_QUALITY_LOW0;
      return true;
  }

  return false;
}

// Update the A2DP LHDC encoder.
// |peer_mtu| is the peer MTU.
// |a2dp_codec_config| is the A2DP codec to use for the update.
static void a2dp_vendor_lhdcv5_encoder_update(uint16_t peer_mtu, A2dpCodecConfig* a2dp_codec_config,
                                              bool* p_restart_input, bool* p_restart_output,
                                              bool* p_config_updated) {
  tA2DP_LHDCV5_ENCODER_PARAMS* p_encoder_params = &a2dp_lhdc_encoder_cb.lhdc_encoder_params;
  uint8_t codec_info[AVDT_CODEC_SIZE];
  uint32_t verCode = 0;
  int32_t lib_ret = 0;
  uint32_t mtu_size = 0;
  uint32_t max_mtu_len = 0;
  uint32_t newValue_bt = 0, newValue_lib = 0;
  tA2DP_FEEDING_PARAMS* p_feeding_params;

  const uint8_t* p_codec_info;

  *p_restart_input = false;
  *p_restart_output = false;
  *p_config_updated = false;

  if (!a2dp_codec_config->copyOutOtaCodecConfig(codec_info)) {
    log::error("Cannot update the codec encoder for {}: invalid codec config",
               a2dp_codec_config->name());
    return;
  }
  p_codec_info = codec_info;

  btav_a2dp_codec_config_t codec_config = a2dp_codec_config->getCodecConfig();

  // get version
  if (!A2DP_VendorGetVersionLhdcV5(&verCode, p_codec_info)) {
    log::error("get version error!");
    goto fail;
  }
  log::debug("get version: {}", verCode);

  // get new handle
  if (!a2dp_lhdc_encoder_cb.has_lhdc_handle) {
    a2dp_lhdc_encoder_cb.lhdc_handle = nullptr;
    lib_ret = lhdcv5BT_get_handle(verCode, &a2dp_lhdc_encoder_cb.lhdc_handle);
    if (lib_ret != LHDC_FRET_SUCCESS) {
      log::error("[lib_ret] lhdc_get_handle error {}", lib_ret);
      goto fail;
    }

    if (a2dp_lhdc_encoder_cb.lhdc_handle == nullptr) {
      log::error("Cannot get LHDC encoder handle");
      goto fail;
    }
    a2dp_lhdc_encoder_cb.has_lhdc_handle = true;
  }

  //
  // setup feeding parameters for encoder feeding process
  //
  p_feeding_params = &a2dp_lhdc_encoder_cb.feeding_params;
  // sample rate (uint32_t)
  p_feeding_params->sample_rate = A2DP_VendorGetTrackSampleRateLhdcV5(p_codec_info);
  if (p_feeding_params->sample_rate < 0) {
    log::error("get track sample rate error");
    goto fail;
  }

  // bit per sample (uint8_t)
  p_feeding_params->bits_per_sample = A2DP_VendorGetTrackBitsPerSampleLhdcV5(p_codec_info);
  if (p_feeding_params->bits_per_sample < 0) {
    log::error("get bit per sample error");
    goto fail;
  }

  // channel count (uint8_t)
  p_feeding_params->channel_count = A2DP_VendorGetTrackChannelCountLhdcV5(p_codec_info);
  if (p_feeding_params->channel_count < 0) {
    log::error("get channel count error");
    goto fail;
  }
  log::debug("(feeding param) sample_rate={} bits_per_sample={} channel_count={}",
             p_feeding_params->sample_rate, p_feeding_params->bits_per_sample,
             p_feeding_params->channel_count);

  //
  // setup encoder parameters for configuring encoder
  //
  // sample rate tA2DP_SAMPLE_RATE(uint32_t)
  p_encoder_params->sample_rate = a2dp_lhdc_encoder_cb.feeding_params.sample_rate;

  // default mtu size (uint32_t)
  mtu_size = (BT_DEFAULT_BUFFER_SIZE - A2DP_LHDC_OFFSET - sizeof(BT_HDR));
  // allowed mtu size (uint32_t)
  a2dp_lhdc_encoder_cb.TxAaMtuSize = (mtu_size < peer_mtu) ? mtu_size : (uint32_t)peer_mtu;
  // real mtu size (uint32_t)
#if (BTA_AV_CO_CP_SCMS_T == TRUE)
  max_mtu_len = (uint32_t)(a2dp_lhdc_encoder_cb.TxAaMtuSize - A2DP_LHDC_MPL_HDR_LEN - 1);
#else
  max_mtu_len = (uint32_t)(a2dp_lhdc_encoder_cb.TxAaMtuSize - A2DP_LHDC_MPL_HDR_LEN);
#endif

  // max target bit rate
  if (!A2DP_VendorGetMaxBitRateLhdcV5(&newValue_bt, p_codec_info)) {
    log::error("get max_target_bitrate error");
    goto fail;
  }
  if (!a2dp_vendor_lhdcv5_qualitymode_wrapper(&newValue_lib, newValue_bt)) {
    log::error("wrap MBR qualiity mode error");
    goto fail;
  }
  p_encoder_params->max_target_bitrate = newValue_lib;

  // min target bit rate
  if (!A2DP_VendorGetMinBitRateLhdcV5(&newValue_bt, p_codec_info)) {
    log::error("get min_target_bitrate error");
    goto fail;
  }
  if (!a2dp_vendor_lhdcv5_qualitymode_wrapper(&newValue_lib, newValue_bt)) {
    log::error("wrap mBR qualiity mode error");
    goto fail;
  }
  p_encoder_params->min_target_bitrate = newValue_lib;

  // Low latency mode
  if (!A2DP_VendorHasLLFlagLhdcV5(&(p_encoder_params->isLLEnabled), p_codec_info)) {
    log::error("get Low latency enable error");
    goto fail;
  }

  // bit per sample
  switch ((int)p_feeding_params->bits_per_sample) {
    case 16:
      p_encoder_params->pcm_fmt = LHDCBT_SMPL_FMT_S16;
      break;
    case 24:
      p_encoder_params->pcm_fmt = LHDCBT_SMPL_FMT_S24;
      break;
  }

  // quality mode
  if ((codec_config.codec_specific_1 & A2DP_LHDC_VENDOR_CMD_MASK) == A2DP_LHDC_QUALITY_MAGIC_NUM) {
    newValue_bt = (codec_config.codec_specific_1 & A2DP_LHDC_QUALITY_MASK);
    if (!a2dp_vendor_lhdcv5_qualitymode_wrapper(&newValue_lib, newValue_bt)) {
      log::error("wrap quality mode error");
      goto fail;
    }

    if (newValue_lib != p_encoder_params->quality_mode_index) {
      p_encoder_params->quality_mode_index = newValue_lib;
    }
  } else {
    // give default: ABR
    newValue_bt = A2DP_LHDC_QUALITY_ABR;
    if (!a2dp_vendor_lhdcv5_qualitymode_wrapper(&newValue_lib, newValue_bt)) {
      log::error("(default) wrap quality mode error");
      goto fail;
    }
    p_encoder_params->quality_mode_index = newValue_lib;
  }

  // when in lossless, set max bit rate to current max bitrate stage
  if (p_encoder_params->isLLessEnabled == 1) {
    p_encoder_params->max_target_bitrate = LHDC_QUALITY_MAX_BITRATE;
    log::debug("set max target bitrate to unlimit when lossless enabled ({})",
               LHDC_QUALITY_MAX_BITRATE);
  }

  log::debug(
          "(encode param) sample_rate={} pcm_fmt={} peer_mtu={} mtu={} "
          "maxBitRateIdx={} minBitRateIdx={} isLLEnabled={} isLLessEnabled={} quality_mode={}({})",
          p_encoder_params->sample_rate,         // 44100, 48000, ...
          p_encoder_params->pcm_fmt,             // 16, 24, 32...
          peer_mtu, max_mtu_len,                 // number of bytes
          p_encoder_params->max_target_bitrate,  // index
          p_encoder_params->min_target_bitrate,  // index
          p_encoder_params->isLLEnabled, p_encoder_params->isLLessEnabled,
          quality_mode_index_to_name(p_encoder_params->quality_mode_index),
          p_encoder_params->quality_mode_index);

  // Initialize the encoder.
  // NOTE: MTU in the initialization must include the AVDT media header size.
  lib_ret = lhdcv5BT_init_encoder(a2dp_lhdc_encoder_cb.lhdc_handle, p_encoder_params->sample_rate,
                                  p_encoder_params->pcm_fmt, p_encoder_params->quality_mode_index,
                                  max_mtu_len,
                                  (uint32_t)a2dp_vendor_lhdcv5_get_encoder_interval_ms(), 0);

  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::error("[lib_ret] lhdc_init_encoder {}", lib_ret);
    goto fail;
  }
  // setup after encoder initialized
  lib_ret = lhdcv5BT_set_max_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle,
                                     p_encoder_params->max_target_bitrate);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::error("[lib_ret] set_max_bitrate {}", lib_ret);
    goto fail;
  }
  lib_ret = lhdcv5BT_set_min_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle,
                                     p_encoder_params->min_target_bitrate);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::error("[lib_ret] set_min_bitrate {}", lib_ret);
    goto fail;
  }
  lib_ret = lhdcv5BT_set_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle,
                                 p_encoder_params->quality_mode_index);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::error("[lib_ret] set_bitrate {}", lib_ret);
    goto fail;
  }

  // features can not be setup when enabling:
  //  lossless raw mode or lossless 96KHz
  if (!p_encoder_params->isLLessEnabled || p_encoder_params->sample_rate != 96000) {
// TODO
#if 0
    if (A2DP_VendorHasARFlagLhdcV5(&ret_value8, p_codec_info)) {
      lib_ret = lhdc_set_ext_func(a2dp_lhdc_encoder_cb.lhdc_handle, LHDCV5_EXT_FUNC_AR,
                                  (bool)ret_value8, NULL, 0);
      if (lib_ret != LHDC_FRET_SUCCESS) {
        log::error("[lib_ret] lhdc_set_ext_func AR(0x%X) {}", LHDCV5_EXT_FUNC_AR, lib_ret);
        goto fail;
      }
    }

    if (A2DP_VendorHasJASFlagLhdcV5(&ret_value8, p_codec_info)) {
      lib_ret = lhdc_set_ext_func(a2dp_lhdc_encoder_cb.lhdc_handle, LHDCV5_EXT_FUNC_JAS,
                                  (bool)ret_value8, NULL, 0);
      if (lib_ret != LHDC_FRET_SUCCESS) {
        log::error("[lib_ret] lhdc_set_ext_func JAS(0x%X) {}", LHDCV5_EXT_FUNC_JAS, lib_ret);
        goto fail;
      }
    }
#endif
  }

#ifdef MCPS_FRAME_ENCODE
  mcpsStat_all_enc_us_lhdcv5 = 0;
  mcpsStat_all_enc_frm_lhdcv5 = 0;
  mcpsStat_time_dur_cnt_lhdcv5 = 0;
  mcpsStat_enc_frm_per_sec_lhdcv5 = 0;
  mcpsStat_all_time_dur_ms_lhdcv5 = 0;
#endif

  return;

fail:
  if (a2dp_lhdc_encoder_cb.lhdc_handle) {
    a2dp_vendor_lhdcv5_encoder_cleanup();
  }
}

// tA2DP_ENCODER_INTERFACE::(encoder_cleanup)
void a2dp_vendor_lhdcv5_encoder_cleanup(void) {
  if (a2dp_lhdc_encoder_cb.has_lhdc_handle && a2dp_lhdc_encoder_cb.lhdc_handle) {
    int32_t lib_ret = lhdcv5BT_free_handle(a2dp_lhdc_encoder_cb.lhdc_handle);
    if (lib_ret != LHDC_FRET_SUCCESS) {
      log::error("free handle error {}", lib_ret);
      return;
    }
  } else {
    log::debug("nothing to clean");
    return;
  }
  memset(&a2dp_lhdc_encoder_cb, 0, sizeof(a2dp_lhdc_encoder_cb));
  log::debug("encoder cleaned up");
}

// tA2DP_ENCODER_INTERFACE::(feeding_reset)
void a2dp_vendor_lhdcv5_feeding_reset(void) {
  /* By default, just clear the entire state */
  memset(&a2dp_lhdc_encoder_cb.lhdc_feeding_state, 0,
         sizeof(a2dp_lhdc_encoder_cb.lhdc_feeding_state));

  uint32_t encoder_interval = (uint32_t)a2dp_vendor_lhdcv5_get_encoder_interval_ms();
  a2dp_lhdc_encoder_cb.lhdc_feeding_state.bytes_per_tick =
          (a2dp_lhdc_encoder_cb.feeding_params.sample_rate *
           a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample / 8 *
           a2dp_lhdc_encoder_cb.feeding_params.channel_count * encoder_interval) /
          1000;
  a2dp_lhdc_encoder_cb.buf_seq = 0;
  a2dp_lhdc_encoder_cb.bytes_read = 0;
  a2dp_lhdc_encoder_cb.lhdc_feeding_state.last_frame_us = 0;

  tA2DP_LHDCV5_ENCODER_PARAMS* p_encoder_params = &a2dp_lhdc_encoder_cb.lhdc_encoder_params;
  if (p_encoder_params->quality_mode_index == LHDC_QUALITY_AUTO) {
    if (a2dp_lhdc_encoder_cb.has_lhdc_handle) {
      lhdcv5BT_set_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle, LHDC_QUALITY_CTRL_RESET_ABR);
    }
  }

#ifdef MCPS_FRAME_ENCODE
  mcpsStat_all_enc_us_lhdcv5 = 0;
  mcpsStat_all_enc_frm_lhdcv5 = 0;
  mcpsStat_time_dur_cnt_lhdcv5 = 0;
  mcpsStat_enc_frm_per_sec_lhdcv5 = 0;
  mcpsStat_all_time_dur_ms_lhdcv5 = 0;
#endif

  log::debug("PCM bytes per tick {}, reset timestamp",
             a2dp_lhdc_encoder_cb.lhdc_feeding_state.bytes_per_tick);
}

// tA2DP_ENCODER_INTERFACE::(feeding_flush)
void a2dp_vendor_lhdcv5_feeding_flush(void) {
  a2dp_lhdc_encoder_cb.lhdc_feeding_state.counter = 0;
  log::debug("");
}

// tA2DP_ENCODER_INTERFACE::(get_encoder_interval_ms)
uint64_t a2dp_vendor_lhdcv5_get_encoder_interval_ms(void) {
  log::debug("A2DP_LHDC_ENCODER_INTERVAL_MS {}",
             a2dp_lhdc_encoder_cb.lhdc_encoder_params.isLLEnabled
                     ? A2DP_LHDC_ENCODER_SHORT_INTERVAL_MS
                     : A2DP_LHDC_ENCODER_INTERVAL_MS);

  if (a2dp_lhdc_encoder_cb.lhdc_encoder_params.isLLEnabled) {
    return A2DP_LHDC_ENCODER_SHORT_INTERVAL_MS;
  } else {
    return A2DP_LHDC_ENCODER_INTERVAL_MS;
  }
}

int a2dp_vendor_lhdcv5_get_effective_frame_size() { return a2dp_lhdc_encoder_cb.TxAaMtuSize; }

// tA2DP_ENCODER_INTERFACE::(send_frames)
void a2dp_vendor_lhdcv5_send_frames(uint64_t timestamp_us) {
  uint8_t nb_frame = 0;
  uint8_t nb_iterations = 0;

  a2dp_lhdcv5_get_num_frame_iteration(&nb_iterations, &nb_frame, timestamp_us);
  log::debug("Sending {} frames per iteration, {} iterations", nb_frame, nb_iterations);

  if (nb_frame == 0) {
    return;
  }

  for (uint8_t counter = 0; counter < nb_iterations; counter++) {
    // Transcode frame and enqueue
    a2dp_lhdcV5_encode_frames(nb_frame);
  }
}

// Obtains the number of frames to send and number of iterations
// to be used. |num_of_iterations| and |num_of_frames| parameters
// are used as output param for returning the respective values.
static void a2dp_lhdcv5_get_num_frame_iteration(uint8_t* num_of_iterations, uint8_t* num_of_frames,
                                                uint64_t timestamp_us) {
  uint32_t result = 0;
  uint8_t nof = 0;
  uint8_t noi = 1;
  uint32_t pcm_bytes_per_frame = 0;
  uint32_t samples_per_frame = 0;
  int32_t lib_ret = 0;

  lib_ret = lhdcv5BT_get_block_Size(a2dp_lhdc_encoder_cb.lhdc_handle, &samples_per_frame);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::debug("get block size error {}", lib_ret);
    return;
  }

  pcm_bytes_per_frame = samples_per_frame * a2dp_lhdc_encoder_cb.feeding_params.channel_count *
                        a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample / 8;

  uint32_t encoder_interval = (uint32_t)a2dp_vendor_lhdcv5_get_encoder_interval_ms();
  uint32_t us_this_tick = encoder_interval * 1000;
  uint64_t now_us = timestamp_us;

  // not the first time, calculate time offset
  if (a2dp_lhdc_encoder_cb.lhdc_feeding_state.last_frame_us != 0) {
    us_this_tick = (now_us - a2dp_lhdc_encoder_cb.lhdc_feeding_state.last_frame_us);
  }
  a2dp_lhdc_encoder_cb.lhdc_feeding_state.last_frame_us = now_us;

  a2dp_lhdc_encoder_cb.lhdc_feeding_state.counter +=
          a2dp_lhdc_encoder_cb.lhdc_feeding_state.bytes_per_tick * us_this_tick /
          (encoder_interval * 1000);

  result = a2dp_lhdc_encoder_cb.lhdc_feeding_state.counter / pcm_bytes_per_frame;
  a2dp_lhdc_encoder_cb.lhdc_feeding_state.counter -= result * pcm_bytes_per_frame;
  nof = result;

  log::debug("samples_per_frame={} pcm_bytes_per_frame={} nb_frame={}", samples_per_frame,
             pcm_bytes_per_frame, nof);

  *num_of_frames = nof;
  *num_of_iterations = noi;
}

static BT_HDR* bt_buf_new(void) {
  BT_HDR* p_buf = (BT_HDR*)osi_malloc(BT_DEFAULT_BUFFER_SIZE);
  if (p_buf == NULL) {
    // LeoKu(C): should not happen
    log::error("bt_buf_new failed!");
    return NULL;
  }

  p_buf->offset = A2DP_LHDC_OFFSET;
  p_buf->len = 0;
  p_buf->layer_specific = 0;
  return p_buf;
}

static void a2dp_lhdcV5_encode_frames(uint8_t nb_frame) {
  static float mtu_usage = 0;
  static int mtu_usage_cnt = 0;
  static uint64_t time_prev_ms = bluetooth::common::time_get_os_boottime_ms();
  static uint32_t all_send_bytes = 0;
  uint8_t read_buffer[LHDC_MAX_SAMPLE_FRAME * 2 * 4];
  uint32_t samples_per_frame = 0;
  uint32_t out_frames = 0;
  uint8_t remain_nb_frame = nb_frame;
  uint32_t written = 0;
  uint32_t bytes_read = 0;
  uint8_t* packet = nullptr;
  BT_HDR* p_buf = nullptr;
  int32_t lib_ret = 0;
  uint32_t pcm_bytes_per_frame = 0;
  uint32_t max_mtu_len = 0;

  uint32_t written_frame = 0;
  uint32_t temp_bytes_read = 0;

#ifdef MCPS_FRAME_ENCODE
  uint64_t time_prev_enc_us_2 = 0;
  uint64_t time_aft_enc_us_2 = 0;
#endif

  lib_ret = lhdcv5BT_get_block_Size(a2dp_lhdc_encoder_cb.lhdc_handle, &samples_per_frame);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::error("[lib_ret] lhdc_get_block_size error {}", lib_ret);
    return;
  }
  pcm_bytes_per_frame = samples_per_frame * a2dp_lhdc_encoder_cb.feeding_params.channel_count *
                        a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample / 8;

  if (pcm_bytes_per_frame > sizeof(read_buffer)) {
    log::error("expected read size error");
    return;
  }

  // check codec handle existed
  if (!a2dp_lhdc_encoder_cb.has_lhdc_handle || !a2dp_lhdc_encoder_cb.lhdc_handle) {
    log::error("encoder handle invalid error");
    return;
  }

#if (BTA_AV_CO_CP_SCMS_T == TRUE)
  max_mtu_len = (uint32_t)(a2dp_lhdc_encoder_cb.TxAaMtuSize - A2DP_LHDC_MPL_HDR_LEN - 1);
#else
  max_mtu_len = (uint32_t)(a2dp_lhdc_encoder_cb.TxAaMtuSize - A2DP_LHDC_MPL_HDR_LEN);
#endif

  while (nb_frame) {
    // create a temp output buffer
    if ((p_buf = bt_buf_new()) == NULL) {
      log::error("create buf error");
      return;
    }

    written_frame = 0;
    do {
      temp_bytes_read = 0;
      // read from feeding buffer
      if (a2dp_lhdcv5_read_feeding(read_buffer, &temp_bytes_read)) {
        a2dp_lhdc_encoder_cb.bytes_read += temp_bytes_read;
        packet = (uint8_t*)(p_buf + 1) + p_buf->offset + p_buf->len;

        log::debug("nb_frame({}) to encode...", nb_frame);
        // to encode

#ifdef MCPS_FRAME_ENCODE
        time_prev_enc_us_2 = bluetooth::common::time_get_os_boottime_us();
#endif
        lib_ret = lhdcv5BT_encode(
                a2dp_lhdc_encoder_cb.lhdc_handle, read_buffer, temp_bytes_read, packet,
                BT_DEFAULT_BUFFER_SIZE - (p_buf->offset + p_buf->len + sizeof(BT_HDR)), &written,
                &out_frames);

        if (lib_ret != LHDC_FRET_SUCCESS) {
          log::error("[lib_ret] lhdc_encode_func error {}", lib_ret);
          a2dp_lhdc_encoder_cb.stats.media_read_total_dropped_packets++;
          osi_free(p_buf);
          return;
        }
#ifdef MCPS_FRAME_ENCODE
#if 0
        // For old frame per packet mechanism
        if (mcpsStat_time_dur_cnt_lhdcv5 > 0) {  // begin after statistics initialized
          if (out_frames > 0) {
            time_aft_enc_us_2 = bluetooth::common::time_get_os_boottime_us();
            mcpsStat_all_enc_us_lhdcv5 += (time_aft_enc_us_2 - time_prev_enc_us_2);
            mcpsStat_all_enc_frm_lhdcv5 += out_frames;
          }
        }
#else
        // For new frame per packet mechanism
        if (mcpsStat_time_dur_cnt_lhdcv5 > 0) {  // begin after statistics initialized
          time_aft_enc_us_2 = bluetooth::common::time_get_os_boottime_us();
          mcpsStat_all_enc_us_lhdcv5 += (time_aft_enc_us_2 - time_prev_enc_us_2);
          mcpsStat_all_enc_frm_lhdcv5 += 1;
        }
#endif
#endif

        log::debug("nb_frame({}) - written:{}, out_frames:{}", nb_frame, written, out_frames);
        p_buf->len += written;
        all_send_bytes += written;
        nb_frame--;
        written_frame += out_frames;  // added a frame to the buffer
      } else {
        log::debug("nb_frame({}) - underflow", nb_frame);
        a2dp_lhdc_encoder_cb.lhdc_feeding_state.counter +=
                nb_frame * samples_per_frame * a2dp_lhdc_encoder_cb.feeding_params.channel_count *
                a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample / 8;
        // no more pcm to read
        nb_frame = 0;
      }
    } while ((written == 0) && nb_frame);

    if (p_buf->len) {
      /*
       * Timestamp of the media packet header represent the TS of the
       * first frame, i.e. the timestamp before including this frame.
       */
      p_buf->layer_specific = a2dp_lhdc_encoder_cb.buf_seq++;
      p_buf->layer_specific <<= 8;
      p_buf->layer_specific |= (written_frame << A2DP_LHDC_HDR_NUM_SHIFT);

      *((uint32_t*)(p_buf + 1)) = a2dp_lhdc_encoder_cb.timestamp;
      log::debug("Timestamp ({})", a2dp_lhdc_encoder_cb.timestamp);

      a2dp_lhdc_encoder_cb.timestamp += (written_frame * samples_per_frame);

      remain_nb_frame = nb_frame;
      log::debug("nb_frame({}) - remain_nb_frame:{}", nb_frame + 1, remain_nb_frame);

      mtu_usage += ((float)p_buf->len) / max_mtu_len;
      mtu_usage_cnt++;

      log::debug("Bytes read for pkt({})", a2dp_lhdc_encoder_cb.bytes_read);
      log::debug("Output frames({}) encoded pkt len({})", written_frame, p_buf->len);
      bytes_read = a2dp_lhdc_encoder_cb.bytes_read;
      a2dp_lhdc_encoder_cb.bytes_read = 0;

      if (!a2dp_lhdc_encoder_cb.enqueue_callback(p_buf, 1, bytes_read)) {
        return;
      }
    } else {
      log::debug("free buffer len({})", p_buf->len);
      a2dp_lhdc_encoder_cb.stats.media_read_total_dropped_packets++;
      osi_free(p_buf);
    }
  }

  // for statistics
  uint64_t time_now_ms = bluetooth::common::time_get_os_boottime_ms();
  if (time_now_ms - time_prev_ms >= 1000) {
#ifdef MCPS_FRAME_ENCODE
    mcpsStat_time_dur_cnt_lhdcv5++;

    if (mcpsStat_time_dur_cnt_lhdcv5 <= MCPS_FRAME_ENCODE_DUR) {  // how long we stop statistics
      if (mcpsStat_time_dur_cnt_lhdcv5 <= 1) {
        mcpsStat_all_time_dur_ms_lhdcv5 = 0;  // ignore previous uninitialized records
      } else {
        mcpsStat_all_time_dur_ms_lhdcv5 += (time_now_ms - time_prev_ms);
      }

      /*
       * MCPS STATISTICS LOG:
       *  [cnt:{} time_now_ms({})-time_prev_ms({})=({}) %.2fs]:
       *    cnt       : the number of rounds
       *    time_now_ms    : now system time point (ms)
       *    time_prev_ms : previous system time point (ms)
       *  [{} {} {} kbps]: sampleRate, bitDepth, Bitrate
       *  frmPerTheSec: number of frames encoded in "this second"
       *  avgFrmPerSec: so far, the average number of encoded frames per second
       *  avgUsPerfrm:  so far, the average execution time(us) of each encoded frame
       *  avgMCPS:  so far, the average MCPS
       */
      log::debug(
              "[MCPS_STAT]: [cnt:{} time_now_ms({})-time_prev_ms({})=({}) %.2fs] [{} {} {} kbps] "
              "frmPerTheSec({}) avgFrmPerSec(%.2f) avgUsPerfrm:(%.2f)us, avgMCPS:(%.3f)",
              (uint32_t)mcpsStat_time_dur_cnt_lhdcv5, (uint32_t)time_now_ms, (uint32_t)time_prev_ms,
              (uint32_t)mcpsStat_all_time_dur_ms_lhdcv5,            // total elapsed time (in ms)
              (float)mcpsStat_all_time_dur_ms_lhdcv5 / 1000,        // total elapsed time (in sec)
              a2dp_lhdc_encoder_cb.feeding_params.sample_rate,      // current sample rate
              a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample,  // current bit depth
              (all_send_bytes * 8) / 1000,                          // current bit rate

              (uint32_t)(mcpsStat_all_enc_frm_lhdcv5 -
                         mcpsStat_enc_frm_per_sec_lhdcv5),  // frmPerTheSec
              (float)mcpsStat_all_enc_frm_lhdcv5 /
                      ((float)mcpsStat_all_time_dur_ms_lhdcv5 / 1000),  // avgFrmPerSec
              (float)mcpsStat_all_enc_us_lhdcv5 /
                      (float)mcpsStat_all_enc_frm_lhdcv5,  // avgUsPerfrm

              // avgMCPS = (avgUsPerfrm / STATIS_MILLION_UNIT) * CPU_Freq_MHz * avgFrmPerSec
              ((float)mcpsStat_all_enc_us_lhdcv5 / (float)mcpsStat_all_enc_frm_lhdcv5) *
                      STATIS_CPU_FREQ_MHZ *
                      ((float)mcpsStat_all_enc_frm_lhdcv5 /
                       ((float)mcpsStat_all_time_dur_ms_lhdcv5 / 1000)) /
                      STATIS_MILLION_UNIT);
    }
    mcpsStat_enc_frm_per_sec_lhdcv5 = mcpsStat_all_enc_frm_lhdcv5;
#endif
    log::debug("current data rate about {} kbps, packet usage %.2f%%", (all_send_bytes * 8) / 1000,
               (mtu_usage * 100) / mtu_usage_cnt);
    all_send_bytes = 0;
    mtu_usage_cnt = 0;
    mtu_usage = 0;
    time_prev_ms = time_now_ms;
  }
}

static bool a2dp_lhdcv5_read_feeding(uint8_t* read_buffer, uint32_t* bytes_read) {
  uint32_t read_size = 0;
  uint32_t samples_per_frame = 0;
  uint32_t bytes_per_sample = a2dp_lhdc_encoder_cb.feeding_params.channel_count *
                              a2dp_lhdc_encoder_cb.feeding_params.bits_per_sample / 8;
  uint32_t nb_byte_read;

  if (read_buffer == nullptr || bytes_read == nullptr) {
    log::error("null input");
    return false;
  }

  int32_t lib_ret = lhdcv5BT_get_block_Size(a2dp_lhdc_encoder_cb.lhdc_handle, &samples_per_frame);
  if (lib_ret != LHDC_FRET_SUCCESS) {
    log::debug("[lib_ret] lhdc_get_block_size error {}", lib_ret);
    return false;
  }
  read_size = samples_per_frame * bytes_per_sample;

  a2dp_lhdc_encoder_cb.stats.media_read_total_expected_reads_count++;
  a2dp_lhdc_encoder_cb.stats.media_read_total_expected_read_bytes += read_size;

  /* Read Data from UIPC channel */
  nb_byte_read = a2dp_lhdc_encoder_cb.read_callback(read_buffer, read_size);
  log::debug("expected read bytes {}, actual read bytes {}", read_size, nb_byte_read);

  // TODO: what to do if not alignment?
  if ((nb_byte_read % bytes_per_sample) != 0) {
    log::debug("PCM data not alignment. The audio sample is shfit {} bytes!",
               nb_byte_read % bytes_per_sample);
  }
  a2dp_lhdc_encoder_cb.stats.media_read_total_actual_read_bytes += nb_byte_read;

  // if actual read < want to read
  if (nb_byte_read < read_size) {
    if (nb_byte_read == 0) {
      return false;
    }

    /* Fill the unfilled part of the read buffer with silence (0) */
    memset(((uint8_t*)read_buffer) + nb_byte_read, 0, read_size - nb_byte_read);
    nb_byte_read = read_size;
  }
  a2dp_lhdc_encoder_cb.stats.media_read_total_actual_reads_count++;
  *bytes_read = nb_byte_read;

  return true;
}

// library index mapping: quality mode index
static std::string quality_mode_index_to_name(uint32_t quality_mode_index) {
  switch (quality_mode_index) {
    case LHDC_QUALITY_AUTO:
      return "ABR";
    case LHDC_QUALITY_HIGH1:
      return "HIGH1_1000";
    case LHDC_QUALITY_HIGH:
      return "HIGH_900";
    case LHDC_QUALITY_MID:
      return "MID_500";
    case LHDC_QUALITY_LOW:
      return "LOW_400";
    case LHDC_QUALITY_LOW4:
      return "LOW_320";
    case LHDC_QUALITY_LOW3:
      return "LOW_256";
    case LHDC_QUALITY_LOW2:
      return "LOW_192";
    case LHDC_QUALITY_LOW1:
      return "LOW_160";
    case LHDC_QUALITY_LOW0:
      return "LOW_64";
    default:
      return "Unknown";
  }
}

// tA2DP_ENCODER_INTERFACE::(set_transmit_queue_length)
void a2dp_vendor_lhdcv5_set_transmit_queue_length(size_t transmit_queue_length) {
  a2dp_lhdc_encoder_cb.TxQueueLength = transmit_queue_length;

  /* TODO: the implementation for lhdc_auto_adjust_bitrate is not present in
           the lhdc rust library
    int32_t lib_ret = 0;
    tA2DP_LHDCV5_ENCODER_PARAMS* p_encoder_params = &a2dp_lhdc_encoder_cb.lhdc_encoder_params;
    if (p_encoder_params->quality_mode_index == LHDC_QUALITY_AUTO) {
        lib_ret = lhdc_auto_adjust_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle,
                                           (uint32_t)transmit_queue_length);
        if (lib_ret != LHDC_FRET_SUCCESS) {
          log::error("[lib_ret] lhdc_auto_adjust_bitrate error {}", lib_ret);
        }
    }
   */
}

void A2dpCodecConfigLhdcV5Source::debug_codec_dump(int fd) {
  a2dp_lhdcv5_encoder_stats_t* stats = &a2dp_lhdc_encoder_cb.stats;
  tA2DP_LHDCV5_ENCODER_PARAMS* p_encoder_params = &a2dp_lhdc_encoder_cb.lhdc_encoder_params;

  uint32_t lib_value = 0;
  int32_t lib_ret = 0;

  A2dpCodecConfig::debug_codec_dump(fd);

  dprintf(fd, "  Packet counts (expected/dropped)                        : %zu / %zu\n",
          (size_t)stats->media_read_total_expected_packets,
          (size_t)stats->media_read_total_dropped_packets);

  dprintf(fd, "  PCM read counts (expected/actual)                       : %zu / %zu\n",
          (size_t)stats->media_read_total_expected_reads_count,
          (size_t)stats->media_read_total_actual_reads_count);

  dprintf(fd, "  PCM read bytes (expected/actual)                        : %zu / %zu\n",
          (size_t)stats->media_read_total_expected_read_bytes,
          (size_t)stats->media_read_total_actual_read_bytes);

  dprintf(fd, "  LHDC quality mode                                       : %s\n",
          quality_mode_index_to_name(p_encoder_params->quality_mode_index).c_str());

  lib_ret = lhdcv5BT_get_bitrate(a2dp_lhdc_encoder_cb.lhdc_handle, &lib_value);
  if (lib_ret == LHDC_FRET_SUCCESS) {
    dprintf(fd, "  LHDC transmission bitrate (Kbps)                        : %zu\n",
            (size_t)lib_value);
  }

  dprintf(fd, "  LHDC saved transmit queue length                        : %zu\n",
          (size_t)a2dp_lhdc_encoder_cb.TxQueueLength);
}
