/******************************************************************************
 *
 *  Copyright (C) 2026, The Android Open Source Project
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

namespace aidl::android::hardware::bluetooth::audio::swoff::a2dp {

extern "C" {

/**
 * Supported Codecs types
 */
enum CodecType {
  SBC = 0,
};

/**
 * SBC Configuration
 */
struct SbcConfig {
  enum class AllocationMethod : int { LOUDNESS, SNR };

  AllocationMethod allocation_method;
  int block_length;
  int subbands;
  int min_bitpool;
  int max_bitpool;
};

/**
 * Audio configuration
 *
 * The bitdepth of the input PCM stream must be 16, 24 or 32 bits per sample.
 *
 * Supported sample rates and frame durations depend on the codec selection.
 */
struct AudioConfig {
  int channel_mode;
  int bitdepth;
  int sample_rate;
  int frame_duration_us;

  CodecType codec_type;
  union {
    SbcConfig sbc;
  } codec_config;
};

/**
 * Control Callbacks, from Rust to C++
 * These functions can be called from different threads, but NOT concurrently.
 */
struct A2dpCallbacks {
  /**
   * Passed as the first parameter of all functions.
   */
  void* handle;

  /**
   * Called when the A2DP stream has started; the input PCM FIFO
   * is open, and should be fed with `swoff_a2dp_write()`.
   * This function MUST not be NULL.
   */
  void (*start)(void* handle);

  /**
   * Called when the A2DP stream has stopped; the PCM FIFO
   * is closed, and calling `swoff_a2dp_write()` will fail.
   * This function MUST not be NULL.
   */
  void (*stop)(void* handle);
};

/**
 * Sets up the A2DP audio offload module with the specified configuration.
 * This initializes the single, global stream instance.
 *
 * @param audio A pointer to the audio configuration.
 * @param callbacks A pointer to the callback structure for stream events.
 * @return 0 on success, or a negative error code on failure.
 */
int swoff_a2dp_setup(const AudioConfig* audio, const A2dpCallbacks* callbacks);

/**
 * Tears down the A2DP audio offload module.
 * This function cleans up the previously configured stream.
 */
void swoff_a2dp_teardown();

/**
 * Writes a chunk of input PCM data to the active stream. The stream must be
 * PCM Stereo, with interleaved channels.
 *
 * This function may block until there is space in the input FIFO.
 *
 * @param data A pointer to the PCM data buffer.
 * @param len The length of the data buffer in bytes.
 * @return The number of bytes written on success, or a negative value on
 * error.
 */
ssize_t swoff_a2dp_write(const void* data, size_t len);

}  // extern "C"

/**
 * A C++ interface for managing the A2DP software offload audio stream.
 *
 * This class provides a simplified, static interface to the underlying
 * singleton stream managed by the Rust module.
 */
class A2dpAudio {
public:
  /**
   * A2dpAudio is a static-only utility class and cannot be instantiated.
   */
  A2dpAudio() = delete;

  /**
   * Interface for receiving callbacks about the stream's state.
   */
  class Callbacks {
  public:
    virtual ~Callbacks() = default;
    virtual void on_start() = 0;
    virtual void on_stop() = 0;
  };

  /**
   * Initializes the A2DP offload stream.
   *
   * @param audio_config The audio configuration for the stream.
   * @param callbacks A shared pointer to the callback handler.
   * @return True on success, false on failure.
   */
  static bool setup(const AudioConfig& audio_config, std::shared_ptr<Callbacks> callbacks) {
    if (instance_) {
      // Already initialized
      return false;
    }
    instance_ = std::move(callbacks);
    A2dpCallbacks callbacks_c{
            .handle = instance_.get(),
            .start = &A2dpAudio::on_start_c,
            .stop = &A2dpAudio::on_stop_c,
    };
    return swoff_a2dp_setup(&audio_config, &callbacks_c) == 0;
  }

  /**
   * Tears down and cleans up the A2DP offload stream.
   */
  static void teardown() {
    if (!instance_) {
      return;
    }
    swoff_a2dp_teardown();
    instance_ = nullptr;
  }

  /**
   * Writes PCM data to the stream.
   *
   * @param data A pointer to the data buffer.
   * @param len The length of the data in bytes.
   * @return The number of bytes successfully written.
   */
  static size_t write(const void* data, size_t len) {
    if (!instance_) {
      return 0;
    }
    ssize_t ret = swoff_a2dp_write(data, len);
    return ret < 0 ? 0 : static_cast<size_t>(ret);
  }

private:
  static void on_start_c(void* handle) { static_cast<Callbacks*>(handle)->on_start(); }

  static void on_stop_c(void* handle) { static_cast<Callbacks*>(handle)->on_stop(); }

  inline static std::shared_ptr<Callbacks> instance_ = nullptr;
};

}  // namespace aidl::android::hardware::bluetooth::audio::swoff::a2dp
