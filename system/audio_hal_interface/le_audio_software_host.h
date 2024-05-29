/*
 * Copyright 2024 The Android Open Source Project
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

#ifdef TARGET_FLOSS
#include <audio_hal_interface/audio_linux.h>
#else
#include <hardware/audio.h>
#endif

#include <stdint.h>

#include <vector>

// APIs exposed to the audio server.
namespace bluetooth {
namespace audio {
namespace le_audio {

struct btle_pcm_parameters {
  uint32_t data_interval_us;
  uint32_t sample_rate;
  uint8_t bits_per_sample;
  uint8_t channels_count;
};

enum class btle_stream_started_status : int32_t {
  CANCELED = -1,
  IDLE = 0,
  STARTED = 1,
};

// Invoked by audio server when it has audio data to stream.
// Returns whether the start request has been made successfully.
bool HostStartRequest();

// Invoked by audio server when audio streaming is done.
void HostStopRequest();

// Whether the peer stream has started.
// Returns 1, when the stream has started,
//         -1, when the previous request has been cancelled,
//         0, otherwise.
btle_stream_started_status GetHostStreamStarted();

// Returns the current host audio config.
btle_pcm_parameters GetHostPcmConfig();

// Invoked by audio server when metadata for playback path has changed.
void SourceMetadataChanged(const source_metadata_v7_t& metadata);

// Invoked by audio server to request audio data streamed from the peer.
// Returns whether the start request has been made successfully.
bool PeerStartRequest();

// Invoked by audio server when audio streaming is done.
void PeerStopRequest();

// Whether the peer stream has started.
// Returns 1, when the stream has started,
//         -1, when the previous request has been cancelled,
//         0, otherwise.
btle_stream_started_status GetPeerStreamStarted();

// Returns the current peer audio config.
btle_pcm_parameters GetPeerPcmConfig();

// Invoked by audio server when metadata for capture path has changed.
void SinkMetadataChanged(const sink_metadata_v7_t& metadata);

}  // namespace le_audio
}  // namespace audio
}  // namespace bluetooth
