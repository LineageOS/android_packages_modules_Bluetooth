/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <ostream>
#include <vector>

namespace bluetooth::le_audio {

enum class GattConnectionState { DISCONNECTED = 0, CONNECTED };

struct LeAudioServerCodecId {
  uint8_t coding_format = 0x00;
  uint16_t vendor_company_id = 0x0000;
  uint16_t vendor_codec_id = 0x0000;

  bool operator==(const LeAudioServerCodecId&) const = default;
};

struct LeAudioServerCodecIdRepository {
  static constexpr LeAudioServerCodecId kCodecIdLc3 = {
          .coding_format = 0x06, .vendor_company_id = 0x0000, .vendor_codec_id = 0x0000};

  static LeAudioServerCodecId buildVendorCodecId(uint16_t company_id, uint16_t codec_id) {
    return {.coding_format = 0xFF, .vendor_company_id = company_id, .vendor_codec_id = codec_id};
  }
};

struct LeAudioServerCodecConfig {
  LeAudioServerCodecId codecId;
  uint32_t sample_rate_hz = 0;
  uint8_t channel_count = 0;
};

struct AseEnableRequest {
  uint8_t ase_id = 0;
  uint8_t direction = 0;
  uint32_t audio_context_type = 0;
  LeAudioServerCodecId codec_id;
  uint32_t sample_rate_hz = 0;
};

class LeAudioServerCallbacks {
public:
  LeAudioServerCallbacks() = default;
  virtual ~LeAudioServerCallbacks() = default;

  /**
   * Callback to notify Java that the LE Audio peripheral stack is initialized.
   */
  virtual void OnInitialized(void) = 0;

  /**
   * Callback to notify of a change in the GATT connection state to a peer
   * device.
   */
  virtual void OnConnectionStateChanged(const RawAddress& address, GattConnectionState state) = 0;

  /**
   * Callback to forward a peer device's request to start one or more audio
   * streams. The upper layer must respond by calling
   * LeAudioServerInterface::ConfirmStreamStartRequest.
   */
  virtual void OnStreamStartRequest(const RawAddress& address,
                                    const std::vector<AseEnableRequest>& requests) = 0;

  /**
   * Callback to notify that an audio stream has successfully transitioned to
   * the Streaming state.
   *
   * @param stream_id corresponds to the ASE ID
   */
  virtual void OnStreamStarted(const RawAddress& address, uint8_t stream_id,
                               uint32_t audio_context_type) = 0;

  /**
   * Callback to notify that a peer device has updated the metadata for an
   * active audio stream.
   *
   * @param stream_id corresponds to the ASE ID
   */
  virtual void OnStreamMetadataUpdated(const RawAddress& address, uint8_t stream_id,
                                       uint32_t audio_context_type) = 0;

  /**
   * Callback to notify that the underlying transport for a sink audio stream
   * (from peer to us) is established and ready. This is the signal to start
   * the corresponding HwAudioSource player in the Kotlin layer.
   */
  virtual void OnSinkStreamReady(const RawAddress& address) = 0;

  /**
   * Callback to notify that the underlying transport for a source audio stream
   * (from us to peer) is established and ready.
   */
  virtual void OnSourceStreamReady(const RawAddress& address) = 0;

  /**
   * A unified callback to notify that an audio stream has stopped for any
   * reason (e.g., peer issued a Release, a Disable, or the link was lost).
   * This is the signal to stop the corresponding HwAudioSource player.
   *
   * @param stream_id corresponds to the ASE ID
   */
  virtual void OnStreamStopped(const RawAddress& address, uint8_t stream_id) = 0;
};

class LeAudioServerInterface {
public:
  LeAudioServerInterface() = default;
  virtual ~LeAudioServerInterface() = default;

  /* Register the LeAudio callbacks */
  virtual void Initialize(LeAudioServerCallbacks* callbacks) = 0;

  /* Cleanup the LeAudio */
  virtual void Cleanup(void) = 0;

  /* Confirm the streaming request */
  virtual void ConfirmStreamStartRequest(const RawAddress& peer_address, bool allowed) = 0;

  /* Release the stream */
  virtual void StopStream(const RawAddress& peer_address, uint8_t ase_id) = 0;
};

} /* namespace bluetooth::le_audio */

namespace std {
template <>
struct formatter<bluetooth::le_audio::GattConnectionState>
    : enum_formatter<bluetooth::le_audio::GattConnectionState> {};
}  // namespace std
