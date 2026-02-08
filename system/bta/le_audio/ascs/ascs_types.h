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

#pragma once

#include <variant>

#include "bluetooth/log.h"
#include "bta/le_audio/le_audio_types.h"

namespace bluetooth::le_audio::ascs {
#define CASE_SET_PTR_TO_TOKEN_STR(nm, en) \
  case (nm::en):                          \
    ch = #en;                             \
    break

/**
 * @brief Represents the parameters for a "Config Codec" operation request.
 */
struct CodecConfigurationReq {
  /** The desired latency for the audio stream. */
  uint8_t target_latency;
  /** The desired PHY for the audio stream. */
  uint8_t target_phy;
  /** The codec ID to be used. */
  ::bluetooth::le_audio::types::LeAudioCodecId codec_id;
  /** Codec-specific configuration parameters. */
  std::vector<uint8_t> codec_spec_conf;
};

inline bool operator==(const CodecConfigurationReq& lhs, const CodecConfigurationReq& rhs) {
  return (lhs.target_latency == rhs.target_latency) && (lhs.target_phy == rhs.target_phy) &&
         (lhs.codec_id == rhs.codec_id) && (lhs.codec_spec_conf == rhs.codec_spec_conf);
}

/**
 * @brief Represents a "Config Codec" request for a single ASE.
 */
struct AseCodecConfigurationReq {
  /** The ID of the ASE to be configured. */
  uint8_t ase_id;
  /** The requested codec configuration. */
  CodecConfigurationReq codec_configuration;

  bool operator==(const AseCodecConfigurationReq& other) const {
    return (ase_id == other.ase_id) && (codec_configuration == other.codec_configuration);
  }
};

/**
 * @brief Represents the codec configuration of an ASE in the
 * `CODEC_CONFIGURED` state.
 */
struct AseStateCodecConfiguration {
  uint8_t framing;
  uint8_t preferred_phy;
  uint8_t preferred_retrans_nb;
  uint16_t max_transport_latency;
  uint32_t pres_delay_min;
  uint32_t pres_delay_max;
  uint32_t preferred_pres_delay_min;
  uint32_t preferred_pres_delay_max;
  ::bluetooth::le_audio::types::LeAudioCodecId codec_id;
  std::vector<uint8_t> codec_spec_conf;

  bool operator==(const AseStateCodecConfiguration&) const = default;
};

/**
 * @brief Represents the QoS configuration of an ASE in the `QOS_CONFIGURED`
 * state.
 */
struct AseStateQosConfiguration {
  uint8_t cig_id;
  uint8_t cis_id;
  uint32_t sdu_interval;
  uint8_t framing;
  uint8_t phy;
  uint16_t max_sdu;
  uint8_t retrans_nb;
  uint16_t max_transport_latency;
  uint32_t pres_delay;

  bool operator==(const AseStateQosConfiguration&) const = default;
};
/**
 * @brief Represents a "Config QoS" request for a single ASE.
 */
struct AseQosConfigurationReq {
  uint8_t ase_id;
  AseStateQosConfiguration qos_configuration;
};

/**
 * @brief Represents an "Enable" request for a single ASE.
 */
struct AseEnableReq {
  uint8_t ase_id;
  std::vector<uint8_t> metadata;

  bool operator==(const AseEnableReq& other) const = default;
};

/**
 * @brief Represents an "Update Metadata" request for a single ASE.
 */
struct AseUpdateMetadataReq {
  uint8_t ase_id;
  std::vector<uint8_t> metadata;

  bool operator==(const AseUpdateMetadataReq& other) const = default;
};

/**
 * @brief Represents the parameters for transient ASE states (Enabling,
 * Streaming, Disabling).
 */
struct AseStateTransientParams {
  uint8_t cig_id;
  uint8_t cis_id;
  std::vector<uint8_t> metadata;

  bool operator==(const AseStateTransientParams&) const = default;
};

/**
 * @brief Defines the operation codes for the ASE Control Point characteristic.
 */
enum class AseCtpOpcode {
  CONFIG_CODEC = 0x01,
  CONFIG_QOS,
  ENABLE,
  RECEIVER_START_READY,
  DISABLE,
  RECEIVER_STOP_READY,
  UPDATE_METADATA,
  RELEASE,
};

inline std::ostream& operator<<(std::ostream& out, const AseCtpOpcode& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, CONFIG_CODEC);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, CONFIG_QOS);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, ENABLE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, RECEIVER_START_READY);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, DISABLE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, RECEIVER_STOP_READY);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, UPDATE_METADATA);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpOpcode, RELEASE);
    default:
      ch = "Invalid Op code";
      break;
  }
  return out << ch;
}

/**
 * @brief Defines the states of an Audio Stream Endpoint (ASE).
 */
enum class AseState {
  IDLE = 0x00,
  CODEC_CONFIGURED,
  QOS_CONFIGURED,
  ENABLING,
  STREAMING,
  DISABLING,
  RELEASING,
};

inline std::ostream& operator<<(std::ostream& out, const AseState& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AseState, IDLE);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, CODEC_CONFIGURED);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, QOS_CONFIGURED);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, ENABLING);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, STREAMING);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, DISABLING);
    CASE_SET_PTR_TO_TOKEN_STR(AseState, RELEASING);
    default:
      ch = "Invalid ASE State";
      break;
  }
  return out << ch;
}

/**
 * @brief Defines the response codes for ASE Control Point operations.
 */
enum class AseCtpResponseCode {
  SUCCESS = 0x00,
  UNSUPPORTED_OPCODE,
  INVALID_LENGTH,
  INVALID_ASE_ID,
  INVALID_ASE_STATE_MACHINE_TRANSITION,
  INVALID_ASE_DIRECTION,
  UNSUPPORTED_AUDIO_CAPABILITIES,
  UNSUPPORTED_CONFIGURATION_PARAMETER_VALUE,
  REJECTED_CONFIGURATION_PARAMETER_VALUE,
  INVALID_CONFIGURATION_PARAMETER_VALUE,
  UNSUPPORTED_METADATA,
  REJECTED_METADATA,
  INVALID_METADATA,
  INSUFFICIENT_RESOURCES,
  UNSPECIFIED_ERROR,
};

inline std::ostream& operator<<(std::ostream& out, const AseCtpResponseCode& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, SUCCESS);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, UNSUPPORTED_OPCODE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_LENGTH);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_ASE_ID);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_ASE_STATE_MACHINE_TRANSITION);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_ASE_DIRECTION);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, UNSUPPORTED_AUDIO_CAPABILITIES);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, UNSUPPORTED_CONFIGURATION_PARAMETER_VALUE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, REJECTED_CONFIGURATION_PARAMETER_VALUE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_CONFIGURATION_PARAMETER_VALUE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, UNSUPPORTED_METADATA);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, REJECTED_METADATA);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INVALID_METADATA);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, INSUFFICIENT_RESOURCES);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseCode, UNSPECIFIED_ERROR);
    default:
      ch = "Invalid ASE Request Response Code";
      break;
  }
  return out << ch;
}

/**
 * @brief Defines the reason codes for ASE Control Point error responses.
 */
enum class AseCtpResponseReason {
  NO_REASON = 0x00,
  CODEC_ID,
  CODEC_SPECIFIC_CONFIGURATION,
  SDU_INTERVAL,
  FRAMING,
  PHY,
  MAXIMUM_SDU_SIZE,
  RETRANSMISSION_NUMBER,
  MAX_TRANSPORT_LATENCY,
  PRESENTATION_DELAY,
  INVALID_ASE_CIS_MAPPING,
};

inline std::ostream& operator<<(std::ostream& out, const AseCtpResponseReason& value) {
  const char* ch = 0;
  switch (value) {
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, NO_REASON);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, CODEC_ID);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, CODEC_SPECIFIC_CONFIGURATION);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, SDU_INTERVAL);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, FRAMING);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, PHY);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, MAXIMUM_SDU_SIZE);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, RETRANSMISSION_NUMBER);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, MAX_TRANSPORT_LATENCY);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, PRESENTATION_DELAY);
    CASE_SET_PTR_TO_TOKEN_STR(AseCtpResponseReason, INVALID_ASE_CIS_MAPPING);
    default:
      ch = "Invalid ASE Request Response Reason";
      break;
  }
  return out << ch;
}

/**
 * @brief Represents the parameters for an ASE Control Point response entry.
 */
struct AseCtpResponseParams {
  /** The ID of the ASE this response corresponds to. */
  uint8_t ase_id;
  /** The response code for the operation on this ASE. */
  AseCtpResponseCode response_code;
  /**
   * @brief The reason for the response.
   * This can be a standard reason code or a vendor-specific LTV type for
   * metadata-related errors.
   */
  std::variant<uint8_t, AseCtpResponseReason> reason;
};

using DataPathConfiguration = types::DataPathConfiguration;

/** @brief Constant indicating that unframed CIS is supported. */
constexpr uint8_t kFramingUnframedSupported = 0x00;
/** @brief Constant indicating that unframed CIS is not supported. */
constexpr uint8_t kFramingUnframedNotSupported = 0x01;

constexpr uint8_t kPhyLe1M = 0x01;
constexpr uint8_t kPhyLe2M = 0x02;
constexpr uint8_t kPhyLeCoded = 0x04;

}  // namespace bluetooth::le_audio::ascs

namespace std {
template <>
struct formatter<bluetooth::le_audio::ascs::AseCtpOpcode> : ostream_formatter {};
template <>
struct formatter<bluetooth::le_audio::ascs::AseState> : ostream_formatter {};
template <>
struct formatter<bluetooth::le_audio::ascs::AseCtpResponseCode> : ostream_formatter {};
template <>
struct formatter<bluetooth::le_audio::ascs::AseCtpResponseReason> : ostream_formatter {};
}  // namespace std
