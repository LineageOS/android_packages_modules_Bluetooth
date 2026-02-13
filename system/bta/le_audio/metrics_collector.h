/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

#include <bluetooth/types/address.h>
#include <hardware/bt_le_audio.h>

#include <chrono>
#include <cstdint>
#include <memory>
#include <unordered_map>

#include "le_audio_types.h"
#include "stack/include/btm_status.h"
#include "stack/include/gatt_api.h"

namespace bluetooth::le_audio {

struct LeAudioMetricsCodecInfo {
  uint8_t codec_format = 0;
  uint16_t vendor_company_id = 0;
  uint16_t vendor_codec_id = 0;
  uint32_t sink_sampling_frequency_hz = 0;
  uint32_t source_sampling_frequency_hz = 0;
  bool is_dsa_active = false;
  bool is_gmap_active = false;
};

namespace metrics {
using ClockTimePoint = std::chrono::time_point<std::chrono::high_resolution_clock>;
}

enum ConnectionStatus : int32_t {
  UNKNOWN = 0,
  SUCCESS = 1,
  FAILED = 2,

  FAILED_CONNECT_UNBONDED_DEV = 3,
  FAILED_CONNECT_DISABLING_GROUP = 4,

  FAILED_GATT_INVALID_HANDLE = 5,
  FAILED_GATT_READ_NOT_PERMIT = 6,
  FAILED_GATT_WRITE_NOT_PERMIT = 7,
  FAILED_GATT_INVALID_PDU = 8,
  FAILED_GATT_INSUF_AUTHENTICATION = 9,
  FAILED_GATT_REQ_NOT_SUPPORTED = 10,
  FAILED_GATT_INVALID_OFFSET = 11,
  FAILED_GATT_INSUF_AUTHORIZATION = 12,
  FAILED_GATT_PREPARE_Q_FULL = 13,
  FAILED_GATT_NOT_FOUND = 14,
  FAILED_GATT_NOT_LONG = 15,
  FAILED_GATT_INSUF_KEY_SIZE = 16,
  FAILED_GATT_INVALID_ATTR_LEN = 17,
  FAILED_GATT_ERR_UNLIKELY = 18,
  FAILED_GATT_INSUF_ENCRYPTION = 19,
  FAILED_GATT_UNSUPPORT_GRP_TYPE = 20,
  FAILED_GATT_INSUF_RESOURCE = 21,
  FAILED_GATT_DATABASE_OUT_OF_SYNC = 22,
  FAILED_GATT_VALUE_NOT_ALLOWED = 23,
  FAILED_GATT_ILLEGAL_PARAMETER = 24,
  FAILED_GATT_NO_RESOURCES = 25,
  FAILED_GATT_INTERNAL_ERROR = 26,
  FAILED_GATT_WRONG_STATE = 27,
  FAILED_GATT_DB_FULL = 28,
  FAILED_GATT_BUSY = 29,
  FAILED_GATT_ERROR = 30,
  FAILED_GATT_CMD_STARTED = 31,
  FAILED_GATT_PENDING = 32,
  FAILED_GATT_AUTH_FAIL = 33,
  FAILED_GATT_INVALID_CFG = 34,
  FAILED_GATT_SERVICE_STARTED = 35,
  FAILED_GATT_ENCRYPED_NO_MITM = 36,
  FAILED_GATT_NOT_ENCRYPTED = 37,
  FAILED_GATT_CONGESTED = 38,
  FAILED_GATT_DUP_REG = 39,
  FAILED_GATT_ALREADY_OPEN = 40,
  FAILED_GATT_CANCEL = 41,
  FAILED_GATT_CONNECTION_TIMEOUT = 42,
  FAILED_GATT_CCC_CFG_ERR = 43,
  FAILED_GATT_PRC_IN_PROGRESS = 44,
  FAILED_GATT_OUT_OF_RANGE = 45,

  // 46-99 reserved for future use

  FAILED_BTM_CMD_STARTED = 100,
  FAILED_BTM_BUSY = 101,
  FAILED_BTM_NO_RESOURCES = 102,
  FAILED_BTM_MODE_UNSUPPORTED = 103,
  FAILED_BTM_ILLEGAL_VALUE = 104,
  FAILED_BTM_WRONG_MODE = 105,
  FAILED_BTM_UNKNOWN_ADDR = 106,
  FAILED_BTM_DEVICE_TIMEOUT = 107,
  FAILED_BTM_BAD_VALUE_RET = 108,
  FAILED_BTM_ERR_PROCESSING = 109,
  FAILED_BTM_NOT_AUTHORIZED = 110,
  FAILED_BTM_DEV_RESET = 111,
  FAILED_BTM_CMD_STORED = 112,
  FAILED_BTM_ILLEGAL_ACTION = 113,
  FAILED_BTM_DELAY_CHECK = 114,
  FAILED_BTM_SCO_BAD_LENGTH = 115,
  FAILED_BTM_SUCCESS_NO_SECURITY = 116,
  FAILED_BTM_FAILED_ON_SECURITY = 117,
  FAILED_BTM_REPEATED_ATTEMPTS = 118,
  FAILED_BTM_MODE4_LEVEL4_NOT_SUPPORTED = 119,
  FAILED_BTM_DEV_RESTRICT_LISTED = 120,
  FAILED_BTM_ERR_KEY_MISSING = 121,
  FAILED_BTM_NOT_AUTHENTICATED = 122,
  FAILED_BTM_NOT_ENCRYPTED = 123,
  FAILED_BTM_INSUFFICIENT_ENCRYPT_KEY_SIZE = 124,
  FAILED_BTM_MAX_STATUS_VALUE = 125,
  FAILED_BTM_UNDEFINED = 126
};

/* android.bluetooth.leaudio.ContextType */
enum class LeAudioMetricsContextType : int32_t {
  INVALID = 0,
  UNSPECIFIED = 1,
  COMMUNICATION = 2,
  MEDIA = 3,
  INSTRUCTIONAL = 4,
  ATTENTION_SEEKING = 5,
  IMMEDIATE_ALERT = 6,
  MAN_MACHINE = 7,
  EMERGENCY_ALERT = 8,
  RINGTONE = 9,
  TV = 10,
  LIVE = 11,
  GAME = 12,
  RFU = 13,
};

ConnectionStatus to_atom_gatt_status(tGATT_STATUS gatt_status);
ConnectionStatus to_atom_btm_status(tBTM_STATUS btm_status);

class GroupMetrics {
public:
  GroupMetrics() {}

  virtual ~GroupMetrics() {}

  virtual void AddStateChangedEvent(const RawAddress& address,
                                    bluetooth::le_audio::ConnectionState state,
                                    ConnectionStatus status) = 0;

  virtual void AddStreamStartedEvent(
          bluetooth::le_audio::types::LeAudioContextType context_type,
          const LeAudioMetricsCodecInfo& info) = 0;

  virtual void AddStreamEndedEvent() = 0;

  virtual void SetGroupSize(int32_t group_size) = 0;

  virtual bool IsClosed() = 0;

  virtual void WriteStats() = 0;

  virtual void Flush() = 0;
};

class MetricsCollector {
public:
  static MetricsCollector* Get();

  /**
   * Update the size of given group which will be used in the
   * LogMetricBluetoothLeAudioConnectionStateChanged()
   *
   * @param group_id ID of target group
   * @param group_size Size of target group
   */
  void OnGroupSizeUpdate(int32_t group_id, int32_t group_size);

  /**
   * When there is a change in Bluetooth LE Audio connection state
   *
   * @param group_id Group ID of the associated device.
   * @param address Address of the associated device.
   * @param state New LE Audio connetion state.
   * @param status status or reason of the state transition. Ignored at
   * CONNECTING states.
   */
  void OnConnectionStateChanged(int32_t group_id, const RawAddress& address,
                                bluetooth::le_audio::ConnectionState state,
                                ConnectionStatus status);

  /**
   * When there is a change in LE Audio stream started
   *
   * @param group_id Group ID of the associated stream.
   */
  void OnStreamStarted(int32_t group_id,
                       bluetooth::le_audio::types::LeAudioContextType context_type,
                       const LeAudioMetricsCodecInfo& info);

  /**
   * When there is a change in LE Audio stream started
   *
   * @param group_id Group ID of the associated stream.
   */
  void OnStreamEnded(int32_t group_id);

  /**
   * When there is a change in Bluetooth LE Audio broadcast state
   *
   * @param started if broadcast streaming is started.
   */
  void OnBroadcastStateChanged(bool started);

  /**
   * Flush all log to statsd
   *
   * @param group_id Group ID of the associated stream.
   */
  void Flush();

protected:
  MetricsCollector() {}

private:
  static MetricsCollector* instance;

  std::unordered_map<int32_t, std::unique_ptr<GroupMetrics>> opened_groups_;
  std::unordered_map<int32_t, int32_t> group_size_table_;

  metrics::ClockTimePoint broadcast_beginning_timepoint_;
};

}  // namespace bluetooth::le_audio
