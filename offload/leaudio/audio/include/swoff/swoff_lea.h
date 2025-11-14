/******************************************************************************
 *
 *  Copyright (C) 2025, The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <cstdbool>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace aidl::android::hardware::bluetooth::audio::swoff {

extern "C" {

/**
 * Supported Codecs types
 */
enum CodecType {
  LC3,
  OPUS,
};

/**
 * LC3 Configuration
 * When set to true, the `hr_mode` enables the High-Resolution mode, available
 * for 48000 and 96000 sampling rates, and SDU intervals 2.5, 5 and 10 ms.
 * The `block_bytes` indicated the size of concatened LC3 frames for all channels.
 */
struct Lc3Config {
  bool hr_mode;
  int block_bytes;
};

/**
 * Opus Configuration
 * The size of each Opus frame size should be set as the bitrate range from 64 to 510 kb/s.
 * The VBR (variable bitrate) flag enables the generation of smaller frame sizes depending
 * on the audio content.
 * The encoding complexity is defined by an integer from lowest (0) to highest (10).
 */
struct OpusConfig {
  int frame_bytes;
  bool vbr;
  int complexity;
};

/**
 * Audio configuration
 *
 * The bitdepth of the inputs PCM stream must be 16, 24 or 32 bits per sample.
 *
 * Supported sample rates depends on the codec selection:
 * - LC3 supports 8000, 16000, 24000, 32000 and 48000 Hz
 * - LC3 High-Resolution mode supports 48000 and 96000 Hz
 * - Opus supports 48000 and 96000 Hz
 *
 * Supported frame duration, defined in us, depends on the codec selection:
 * - LC3 supports 2500, 5000, 7500 and 10000 us.
 * - LC3 High-Resolution mode supports 2500, 5000 and 10000 us.
 * - Opus supports 20000 us.
 */
struct AudioConfig {
  int bitdepth;
  int sample_rate;
  int frame_duration_us;

  CodecType codec_type;
  union {
    Lc3Config lc3;
    OpusConfig opus;
  } codec_config;
};

/**
 * BIS/CIS definition
 */
struct IsoStream {
  uint16_t handle;
  uint32_t channel_allocation;
};

/**
 * Control Callbacks, from Rust to C
 * Theses functions can be called from different threads, but NOT concurrently.
 * Locking over `handle` is not necessary.
 */
struct LeAudioCCallbacks {
  /**
   * Passed as the first parameter of all functions.
   */
  void *handle;

  /**
   * Called when an ISO stream of a group has started; the input PCM FIFO
   * is open, and should be fed with `swoff_leaudio_write()`.
   * This function MUST not be NULL.
   */
  void (*start)(void *handle);

  /**
   * Called when all the ISO streams of a group have stopped; the PCM FIFO
   * is closed, calling `swoff_leaudio_write()` will fail.
   * This function MUST not be NULL.
   */
  void (*stop)(void *handle);
};

/**
 * Setup an input PCM audio stream transported on one or more CIS/BIS Isochronous
 * streams, as specified by the `num_iso_streams` entries of the `iso_streams` table.
 * When not NULL, the `handle` returned shall be passed of the first parameter
 * of other functions acting on this stream.
 * The `anchor_delay_us` indicates the delay (in micro-seconds) from the read of a PCM frame
 * to the isochronous anchor point of the transmission. This is precisely the configurable
 * latency until the input PCM reaches the air.
 * In case of error, a NULL value is returned.
 */
void *swoff_leaudio_setup(const IsoStream iso_streams[], size_t num_iso_streams,
                          const AudioConfig *audio, unsigned anchor_delay_us,
                          const LeAudioCCallbacks *callbacks);

/**
 * Free a non NULL `handle` returned by `swoff_leaudio_setup()`.
 * At the call of this function, the `handle` must no more by used by any other
 * interface functions.
 */
void swoff_leaudio_drop(void *handle);

/**
 * Write a chunk of input PCM stream. The stream must be PCM Stereo, channels
 * interleaved, and occupy 2, 3 or 4 bytes per samples according to the `bitdepth`
 * indicated by `swoff_leaudio_setup()`.
 *
 * This function blocks until there is space in the input FIFO; it is required
 * that the caller follows the imposed write speed.
 *
 * This function returns the number of bytes written, expected to be `len` unless
 * `callbacks.stop()` is called during the write procedure.
 */
size_t swoff_leaudio_write(void *handle, const void *data, size_t len);

}  // extern "C"

class LeAudioCallbacks {
public:
  virtual ~LeAudioCallbacks() = default;
  virtual void start(void) = 0;
  virtual void stop(void) = 0;
};

class LeAudioStream {
public:
  LeAudioStream(const std::vector<IsoStream> iso_streams, const AudioConfig &audio_config,
                std::shared_ptr<LeAudioCallbacks> callbacks, unsigned anchor_delay_us = 30000) {
    LeAudioCCallbacks callbacks_c{
            .handle = callbacks.get(),
            .start = [](void *instance) { static_cast<LeAudioCallbacks *>(instance)->start(); },
            .stop = [](void *instance) { static_cast<LeAudioCallbacks *>(instance)->stop(); },
    };
    handle_ = swoff_leaudio_setup(iso_streams.data(), iso_streams.size(), &audio_config,
                                  anchor_delay_us, &callbacks_c);
    if (!handle_) {
      abort();
    }
  }

  ~LeAudioStream() {
    swoff_leaudio_drop(handle_);
    handle_ = nullptr;
  }

  size_t write(const void *data, size_t len) { return swoff_leaudio_write(handle_, data, len); }

private:
  void *handle_;
};

}  // namespace aidl::android::hardware::bluetooth::audio::swoff
