/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_INCLUDE_BT_AV_H
#define ANDROID_INCLUDE_BT_AV_H

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>

#include <optional>
#include <sstream>
#include <vector>

#include "include/btif_status.h"
#include "stack/include/a2dp_constants.h"

__BEGIN_DECLS

// Must be kept in sync with BluetoothProfile.java
typedef enum {
  BTAV_CONNECTION_STATE_DISCONNECTED = 0,
  BTAV_CONNECTION_STATE_CONNECTING,
  BTAV_CONNECTION_STATE_CONNECTED,
  BTAV_CONNECTION_STATE_DISCONNECTING
} btav_connection_state_t;

/* Bluetooth AV datapath states */
typedef enum {
  BTAV_AUDIO_STATE_REMOTE_SUSPEND = 0,
  BTAV_AUDIO_STATE_STOPPED,
  BTAV_AUDIO_STATE_STARTED,
} btav_audio_state_t;

/*
 * Enum values for each A2DP supported codec.
 * There should be a separate entry for each A2DP codec that is supported
 * for encoding (SRC), and for decoding purpose (SINK).
 */
typedef enum {
  BTAV_A2DP_CODEC_INDEX_SOURCE_MIN = 0,

  // Add an entry for each source codec here.
  // NOTE: The values should be same as those listed in the following file:
  //   BluetoothCodecConfig.java
  BTAV_A2DP_CODEC_INDEX_SOURCE_SBC = 0,
  BTAV_A2DP_CODEC_INDEX_SOURCE_AAC,
  BTAV_A2DP_CODEC_INDEX_SOURCE_APTX,
  BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD,
  BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC,
  BTAV_A2DP_CODEC_INDEX_SOURCE_LC3,
  BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS,
  BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV5,

  BTAV_A2DP_CODEC_INDEX_SOURCE_MAX,

  // Range of codec indexes reserved for Offload codec extensibility.
  // Indexes in this range will be allocated for offloaded codecs
  // that the stack does not recognize.
  BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN = BTAV_A2DP_CODEC_INDEX_SOURCE_MAX,
  BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MAX = BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN + 4,

  BTAV_A2DP_CODEC_INDEX_SINK_MIN = BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MAX,

  // Add an entry for each sink codec here
  BTAV_A2DP_CODEC_INDEX_SINK_SBC = BTAV_A2DP_CODEC_INDEX_SINK_MIN,
  BTAV_A2DP_CODEC_INDEX_SINK_AAC,
  BTAV_A2DP_CODEC_INDEX_SINK_OPUS,

  BTAV_A2DP_CODEC_INDEX_SINK_MAX,

  // Range of codec indexes reserved for Offload codec extensibility.
  // Indexes in this range will be allocated for offloaded codecs
  // that the stack does not recognize.
  BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN = BTAV_A2DP_CODEC_INDEX_SINK_MAX,
  BTAV_A2DP_CODEC_INDEX_SINK_EXT_MAX = BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN + 4,

  BTAV_A2DP_CODEC_INDEX_MIN = BTAV_A2DP_CODEC_INDEX_SOURCE_MIN,
  BTAV_A2DP_CODEC_INDEX_MAX = BTAV_A2DP_CODEC_INDEX_SINK_EXT_MAX
} btav_a2dp_codec_index_t;

typedef enum {
  // Disable the codec.
  // NOTE: This value can be used only during initialization when
  // function btif_av_source_init() is called.
  BTAV_A2DP_CODEC_PRIORITY_DISABLED = -1,

  // Reset the codec priority to its default value.
  BTAV_A2DP_CODEC_PRIORITY_DEFAULT = 0,

  // Highest codec priority.
  BTAV_A2DP_CODEC_PRIORITY_HIGHEST = 1000 * 1000
} btav_a2dp_codec_priority_t;

typedef enum {
  BTAV_A2DP_CODEC_SAMPLE_RATE_NONE = 0x0,
  BTAV_A2DP_CODEC_SAMPLE_RATE_44100 = 0x1 << 0,
  BTAV_A2DP_CODEC_SAMPLE_RATE_48000 = 0x1 << 1,
  BTAV_A2DP_CODEC_SAMPLE_RATE_88200 = 0x1 << 2,
  BTAV_A2DP_CODEC_SAMPLE_RATE_96000 = 0x1 << 3,
  BTAV_A2DP_CODEC_SAMPLE_RATE_176400 = 0x1 << 4,
  BTAV_A2DP_CODEC_SAMPLE_RATE_192000 = 0x1 << 5,
  BTAV_A2DP_CODEC_SAMPLE_RATE_16000 = 0x1 << 6,
  BTAV_A2DP_CODEC_SAMPLE_RATE_24000 = 0x1 << 7
} btav_a2dp_codec_sample_rate_t;

typedef enum {
  BTAV_A2DP_CODEC_FRAME_SIZE_NONE = 0x0,
  BTAV_A2DP_CODEC_FRAME_SIZE_20MS = 0x1 << 0,
  BTAV_A2DP_CODEC_FRAME_SIZE_15MS = 0x1 << 1,
  BTAV_A2DP_CODEC_FRAME_SIZE_10MS = 0x1 << 2,
  BTAV_A2DP_CODEC_FRAME_SIZE_75MS = 0x1 << 3,
} btav_a2dp_codec_frame_size_t;

typedef enum {
  BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE = 0x0,
  BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16 = 0x1 << 0,
  BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24 = 0x1 << 1,
  BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32 = 0x1 << 2
} btav_a2dp_codec_bits_per_sample_t;

typedef enum {
  BTAV_A2DP_CODEC_CHANNEL_MODE_NONE = 0x0,
  BTAV_A2DP_CODEC_CHANNEL_MODE_MONO = 0x1 << 0,
  BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO = 0x1 << 1
} btav_a2dp_codec_channel_mode_t;

typedef enum {
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_UNSPECIFIED = 0x0,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_CONVERSATIONAL = 0x1 << 1,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_MEDIA = 0x1 << 2,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_GAME = 0x1 << 3,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_INSTRUCTIONAL = 0x1 << 4,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_VOICE_ASSISTANTS = 0x1 << 5,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_LIVE_AUDIO = 0x1 << 6,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_SOUND_EFFECTS = 0x1 << 7,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_NOTIFICATIONS = 0x1 << 8,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_RINGTONE_ALERTS = 0x1 << 9,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_ALERTS = 0x1 << 10,
  BTAV_A2DP_CODEC_AUDIO_CONTEXT_EMERGENCY_ALARM = 0x1 << 11,
} btav_a2dp_codec_audio_context_t;

typedef enum {
  BTAV_A2DP_SCMST_DISABLED = 0x00,
  BTAV_A2DP_SCMST_ENABLED = 0x01
} btav_a2dp_scmst_enable_status_t;

/*
 * Structure for representing codec capability or configuration.
 * It is used for configuring A2DP codec preference, and for reporting back
 * current configuration or codec capability.
 * For codec capability, fields "sample_rate", "bits_per_sample" and
 * "channel_mode" can contain bit-masks with all supported features.
 */
struct btav_a2dp_codec_config_t {
  btav_a2dp_codec_index_t codec_type;
  btav_a2dp_codec_priority_t codec_priority;  // Codec selection priority
                                              // relative to other codecs: larger value
                                              // means higher priority. If 0, reset to
                                              // default.
  btav_a2dp_codec_sample_rate_t sample_rate;
  btav_a2dp_codec_bits_per_sample_t bits_per_sample;
  btav_a2dp_codec_channel_mode_t channel_mode;
  int64_t codec_specific_1;  // Codec-specific value 1
  int64_t codec_specific_2;  // Codec-specific value 2
  int64_t codec_specific_3;  // Codec-specific value 3
  int64_t codec_specific_4;  // Codec-specific value 4
  btav_a2dp_codec_audio_context_t audio_context;

  bool operator==(const btav_a2dp_codec_config_t& codec_config) const {
    return codec_type == codec_config.codec_type && codec_priority == codec_config.codec_priority &&
           sample_rate == codec_config.sample_rate &&
           bits_per_sample == codec_config.bits_per_sample &&
           channel_mode == codec_config.channel_mode &&
           codec_specific_1 == codec_config.codec_specific_1 &&
           codec_specific_2 == codec_config.codec_specific_2 &&
           codec_specific_3 == codec_config.codec_specific_3 &&
           codec_specific_4 == codec_config.codec_specific_4;
  }

  std::string CodecNameStr() const {
    switch (codec_type) {
      case BTAV_A2DP_CODEC_INDEX_SOURCE_SBC:
        return "SBC";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_AAC:
        return "AAC";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX:
        return "aptX";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_HD:
        return "aptX HD";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_LDAC:
        return "LDAC";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_LHDCV5:
        return "LHDC";
      case BTAV_A2DP_CODEC_INDEX_SINK_SBC:
        return "SBC (Sink)";
      case BTAV_A2DP_CODEC_INDEX_SINK_AAC:
        return "AAC (Sink)";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_LC3:
        return "LC3";
      case BTAV_A2DP_CODEC_INDEX_SINK_OPUS:
        return "Opus (Sink)";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_OPUS:
        return "Opus";
      case BTAV_A2DP_CODEC_INDEX_MAX:
        return "Unknown(CODEC_INDEX_MAX)";
      case BTAV_A2DP_CODEC_INDEX_SOURCE_EXT_MIN:
      case BTAV_A2DP_CODEC_INDEX_SINK_EXT_MIN:
        return "Unknown(CODEC_EXT)";
    }
    return "Unknown";
  }

  std::string ToString() const {
    std::string sample_rate_str;
    AppendCapability(sample_rate_str, (sample_rate == BTAV_A2DP_CODEC_SAMPLE_RATE_NONE), "NONE");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_44100), "44100");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_48000), "48000");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_88200), "88200");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_96000), "96000");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_176400), "176400");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_192000), "192000");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_16000), "16000");
    AppendCapability(sample_rate_str, (sample_rate & BTAV_A2DP_CODEC_SAMPLE_RATE_24000), "24000");

    std::string bits_per_sample_str;
    AppendCapability(bits_per_sample_str, (bits_per_sample == BTAV_A2DP_CODEC_BITS_PER_SAMPLE_NONE),
                     "NONE");
    AppendCapability(bits_per_sample_str, (bits_per_sample & BTAV_A2DP_CODEC_BITS_PER_SAMPLE_16),
                     "16");
    AppendCapability(bits_per_sample_str, (bits_per_sample & BTAV_A2DP_CODEC_BITS_PER_SAMPLE_24),
                     "24");
    AppendCapability(bits_per_sample_str, (bits_per_sample & BTAV_A2DP_CODEC_BITS_PER_SAMPLE_32),
                     "32");

    std::string channel_mode_str;
    AppendCapability(channel_mode_str, (channel_mode == BTAV_A2DP_CODEC_CHANNEL_MODE_NONE), "NONE");
    AppendCapability(channel_mode_str, (channel_mode & BTAV_A2DP_CODEC_CHANNEL_MODE_MONO), "MONO");
    AppendCapability(channel_mode_str, (channel_mode & BTAV_A2DP_CODEC_CHANNEL_MODE_STEREO),
                     "STEREO");

    return "codec: " + CodecNameStr() + " priority: " + std::to_string(codec_priority) +
           " sample_rate: " + sample_rate_str + " bits_per_sample: " + bits_per_sample_str +
           " channel_mode: " + channel_mode_str +
           " codec_specific_1: " + std::to_string(codec_specific_1) +
           " codec_specific_2: " + std::to_string(codec_specific_2) +
           " codec_specific_3: " + std::to_string(codec_specific_3) +
           " codec_specific_4: " + std::to_string(codec_specific_4);
  }

  static std::string PrintCodecs(std::vector<btav_a2dp_codec_config_t> codecs) {
    std::ostringstream oss;
    for (size_t i = 0; i < codecs.size(); i++) {
      oss << codecs[i].CodecNameStr();
      if (i != (codecs.size() - 1)) {
        oss << ", ";
      }
    }

    return oss.str();
  }

private:
  static std::string AppendCapability(std::string& result, bool append, const std::string& name) {
    if (!append) {
      return result;
    }
    if (!result.empty()) {
      result += "|";
    }
    result += name;
    return result;
  }
};

typedef struct {
  btav_a2dp_scmst_enable_status_t enable_status;
  uint8_t cp_header;
} btav_a2dp_scmst_info_t;

typedef struct {
  BtStatus status = BtifStatus();
  uint8_t error_code;
  std::optional<std::string> error_msg;
} btav_error_t;

typedef struct {
  ::bluetooth::a2dp::CodecId codec_id;
  std::string name;
  uint8_t media_codec_capabilites[20];
  btav_a2dp_codec_config_t codec_capabilities;
  bool lossless;

  std::string ToString() const;
} btav_a2dp_codec_info_t;

struct btav_a2dp_hal_provider_info_t {
  std::vector<btav_a2dp_codec_info_t> source_codecs;
  std::vector<btav_a2dp_codec_info_t> sink_codecs;
};

/**
 * NOTE:
 *
 * 1. AVRCP 1.0 shall be supported initially. AVRCP passthrough commands
 *    shall be handled internally via uinput
 *
 * 2. A2DP data path shall be handled via a socket pipe between the AudioFlinger
 *    android_audio_hw library and the Bluetooth stack.
 *
 */

__END_DECLS

namespace std {
template <>
struct formatter<btav_connection_state_t> : enum_formatter<btav_connection_state_t> {};
template <>
struct formatter<btav_audio_state_t> : enum_formatter<btav_audio_state_t> {};
template <>
struct formatter<btav_a2dp_codec_bits_per_sample_t>
    : enum_formatter<btav_a2dp_codec_bits_per_sample_t> {};
template <>
struct formatter<btav_a2dp_codec_priority_t> : enum_formatter<btav_a2dp_codec_priority_t> {};
template <>
struct formatter<btav_a2dp_codec_index_t> : enum_formatter<btav_a2dp_codec_index_t> {};
template <>
struct formatter<btav_a2dp_codec_sample_rate_t> : enum_formatter<btav_a2dp_codec_sample_rate_t> {};
template <>
struct formatter<btav_a2dp_codec_channel_mode_t> : enum_formatter<btav_a2dp_codec_channel_mode_t> {
};
template <>
struct formatter<btav_a2dp_scmst_enable_status_t>
    : enum_formatter<btav_a2dp_scmst_enable_status_t> {};
}  // namespace std

#endif /* ANDROID_INCLUDE_BT_AV_H */
