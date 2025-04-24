/******************************************************************************
 *
 *  Copyright 2018 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth-asha"

#include <base/functional/bind.h>
#include <base/functional/callback.h>
#include <base/strings/string_number_conversions.h>  // HexEncode
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <deque>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <ostream>
#include <sstream>
#include <utility>
#include <vector>

#include "audio/asrc/asrc_resampler.h"
#include "bta/include/bta_gatt_api.h"
#include "bta/include/bta_gatt_queue.h"
#include "bta/include/bta_hearing_aid_api.h"
#include "btm_api_types.h"
#include "btm_ble_api_types.h"
#include "btm_iso_api.h"
#include "btm_sec_api_types.h"
#include "embdrv/g722/g722_enc_dec.h"
#include "gap_api.h"
#include "gatt/database.h"
#include "gatt_api.h"
#include "gattdefs.h"
#include "hardware/bt_gatt_types.h"
#include "hardware/bt_hearing_aid.h"
#include "hci/controller_interface.h"
#include "internal_include/bt_trace.h"
#include "l2cap_types.h"
#include "main/shim/entry.h"
#include "osi/include/allocator.h"
#include "osi/include/properties.h"
#include "profiles_api.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/acl_api_types.h"  // tBTM_RSSI_RESULT
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_types.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_status.h"
#include "stack/include/l2cap_interface.h"
#include "stack/include/main_thread.h"
#include "types/bluetooth/uuid.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

using base::Closure;
using bluetooth::Uuid;
using bluetooth::hci::IsoManager;
using bluetooth::hearing_aid::ConnectionState;
using namespace bluetooth;

// The MIN_CE_LEN parameter for Connection Parameters based on the current
// Connection Interval
constexpr uint16_t MIN_CE_LEN_10MS_CI = 0x0006;
constexpr uint16_t MIN_CE_LEN_20MS_CI = 0x000C;
constexpr uint16_t MAX_CE_LEN_20MS_CI = 0x000C;
constexpr uint16_t CE_LEN_20MS_CI_ISO_RUNNING = 0x0000;
constexpr uint16_t CONNECTION_INTERVAL_10MS_PARAM = 0x0008;
constexpr uint16_t CONNECTION_INTERVAL_20MS_PARAM = 0x0010;

void btif_storage_add_hearing_aid(const HearingDevice& dev_info);
bool btif_storage_get_hearing_aid_prop(const RawAddress& address, uint8_t* capabilities,
                                       uint64_t* hi_sync_id, uint16_t* render_delay,
                                       uint16_t* preparation_delay, uint16_t* codecs);

constexpr uint8_t CODEC_G722_16KHZ = 0x01;
constexpr uint8_t CODEC_G722_24KHZ = 0x02;

// audio control point opcodes
constexpr uint8_t CONTROL_POINT_OP_START = 0x01;
constexpr uint8_t CONTROL_POINT_OP_STOP = 0x02;
constexpr uint8_t CONTROL_POINT_OP_STATE_CHANGE = 0x03;

constexpr uint8_t STATE_CHANGE_OTHER_SIDE_DISCONNECTED = 0x00;
constexpr uint8_t STATE_CHANGE_OTHER_SIDE_CONNECTED = 0x01;
constexpr uint8_t STATE_CHANGE_CONN_UPDATE = 0x02;

// used to mark current_volume as not yet known, or possibly old
constexpr int8_t VOLUME_UNKNOWN = 127;
constexpr int8_t VOLUME_MIN = -127;

// audio type
constexpr uint8_t AUDIOTYPE_UNKNOWN = 0x00;

// Status of the other side Hearing Aids device
constexpr uint8_t OTHER_SIDE_NOT_STREAMING = 0x00;
constexpr uint8_t OTHER_SIDE_IS_STREAMING = 0x01;

// This ADD_RENDER_DELAY_INTERVALS is the number of connection intervals when
// the audio data packet is send by Audio Engine to when the Hearing Aids device
// received it from the air. We assumed that there is 2 data buffer queued from
// audio subsystem to bluetooth chip. Then the estimated OTA delay is two
// connnection intervals.
constexpr uint16_t ADD_RENDER_DELAY_INTERVALS = 4;

constexpr tCONN_ID INVALID_CONN_ID = 0;

namespace {

// clang-format off
Uuid HEARING_AID_UUID          = Uuid::FromString("FDF0");
Uuid READ_ONLY_PROPERTIES_UUID = Uuid::FromString("6333651e-c481-4a3e-9169-7c902aad37bb");
Uuid AUDIO_CONTROL_POINT_UUID  = Uuid::FromString("f0d4de7e-4a88-476c-9d9f-1937b0996cc0");
Uuid AUDIO_STATUS_UUID         = Uuid::FromString("38663f1a-e711-4cac-b641-326b56404837");
Uuid VOLUME_UUID               = Uuid::FromString("00e4ca9e-ab14-41e4-8823-f9e70c7e91df");
Uuid LE_PSM_UUID               = Uuid::FromString("2d410339-82b6-42aa-b34e-e2e01df8cc1a");
// clang-format on

static void read_rssi_callback(void* p_void);
static void hearingaid_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data);
static void encryption_callback(RawAddress, tBT_TRANSPORT, void*, tBTM_STATUS);

inline BT_HDR* malloc_l2cap_buf(uint16_t len) {
  BT_HDR* msg = (BT_HDR*)osi_malloc(BT_HDR_SIZE + L2CAP_MIN_OFFSET +
                                    len /* LE-only, no need for FCS here */);
  msg->offset = L2CAP_MIN_OFFSET;
  msg->len = len;
  return msg;
}

inline uint8_t* get_l2cap_sdu_start_ptr(BT_HDR* msg) {
  return (uint8_t*)(msg) + BT_HDR_SIZE + L2CAP_MIN_OFFSET;
}

class HearingAidImpl;
HearingAidImpl* instance;
std::mutex instance_mutex;
HearingAidAudioReceiver* audioReceiver;

class HearingDevices {
public:
  void Add(HearingDevice device) {
    if (FindByAddress(device.address) != nullptr) {
      return;
    }

    devices.push_back(device);
  }

  void Remove(const RawAddress& address) {
    for (auto it = devices.begin(); it != devices.end();) {
      if (it->address != address) {
        ++it;
        continue;
      }

      it = devices.erase(it);
      return;
    }
  }

  HearingDevice* FindByAddress(const RawAddress& address) {
    auto iter = std::find_if(
            devices.begin(), devices.end(),
            [&address](const HearingDevice& device) { return device.address == address; });

    return (iter == devices.end()) ? nullptr : &(*iter);
  }

  HearingDevice* FindOtherConnectedDeviceFromSet(const HearingDevice& device) {
    auto iter = std::find_if(devices.begin(), devices.end(), [&device](const HearingDevice& other) {
      return &device != &other && device.hi_sync_id == other.hi_sync_id &&
             other.conn_id != INVALID_CONN_ID;
    });

    return (iter == devices.end()) ? nullptr : &(*iter);
  }

  HearingDevice* FindByConnId(tCONN_ID conn_id) {
    auto iter = std::find_if(
            devices.begin(), devices.end(),
            [&conn_id](const HearingDevice& device) { return device.conn_id == conn_id; });

    return (iter == devices.end()) ? nullptr : &(*iter);
  }

  HearingDevice* FindByGapHandle(uint16_t gap_handle) {
    auto iter = std::find_if(
            devices.begin(), devices.end(),
            [&gap_handle](const HearingDevice& device) { return device.gap_handle == gap_handle; });

    return (iter == devices.end()) ? nullptr : &(*iter);
  }

  void StartRssiLog() {
    int read_rssi_start_interval_count = 0;

    for (auto& d : devices) {
      log::debug("bd_addr={} read_rssi_count={}", d.address, d.read_rssi_count);

      // Reset the count
      if (d.read_rssi_count <= 0) {
        d.read_rssi_count = READ_RSSI_NUM_TRIES;
        d.num_intervals_since_last_rssi_read = read_rssi_start_interval_count;

        // Spaced apart the Read RSSI commands to the BT controller.
        read_rssi_start_interval_count += PERIOD_TO_READ_RSSI_IN_INTERVALS / 2;
        read_rssi_start_interval_count %= PERIOD_TO_READ_RSSI_IN_INTERVALS;

        std::deque<rssi_log>& rssi_logs = d.audio_stats.rssi_history;
        if (rssi_logs.size() >= MAX_RSSI_HISTORY) {
          rssi_logs.pop_front();
        }
        rssi_logs.emplace_back();
      }
    }
  }

  size_t size() { return devices.size(); }

  std::vector<HearingDevice> devices;
};

static void write_rpt_ctl_cfg_cb(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                 uint16_t len, const uint8_t* /*value*/, void* /*data*/) {
  if (status != GATT_SUCCESS) {
    log::error("handle= {}, conn_id={}, status= 0x{:x}, length={}", handle, conn_id,
               static_cast<uint8_t>(status), len);
  }
}

g722_encode_state_t* encoder_state_left = nullptr;
g722_encode_state_t* encoder_state_right = nullptr;

inline void encoder_state_init() {
  if (encoder_state_left != nullptr) {
    log::warn("encoder already initialized");
    return;
  }
  encoder_state_left = g722_encode_init(nullptr, 64000, G722_PACKED);
  encoder_state_right = g722_encode_init(nullptr, 64000, G722_PACKED);
}

inline void encoder_state_release() {
  if (encoder_state_left != nullptr) {
    g722_encode_release(encoder_state_left);
    encoder_state_left = nullptr;
    g722_encode_release(encoder_state_right);
    encoder_state_right = nullptr;
  }
}

class HearingAidImpl : public HearingAid {
private:
  // Keep track of whether the Audio Service has resumed audio playback
  bool audio_running;
  bool is_iso_running = false;
  // For Testing: overwrite the MIN_CE_LEN and MAX_CE_LEN during connection
  // parameter updates
  int16_t overwrite_min_ce_len = -1;
  int16_t overwrite_max_ce_len = -1;
  const std::string PERSIST_MIN_CE_LEN_NAME = "persist.bluetooth.hearing_aid_min_ce_len";
  const std::string PERSIST_MAX_CE_LEN_NAME = "persist.bluetooth.hearing_aid_max_ce_len";
  // Record whether the connection parameter needs to update to a better one
  bool needs_parameter_update = false;
  std::chrono::time_point<std::chrono::steady_clock> last_drop_time_point =
          std::chrono::steady_clock::now();
  // at most 1 packet DROP per kDropFrequencyThreshold seconds
  static constexpr int64_t kDropFrequencyThreshold = 60;

  // Resampler context for audio stream.
  // Clock recovery uses L2CAP Flow Control Credit Ind acknowledgments
  // from either the left or right connection, whichever is first
  // connected.
  std::unique_ptr<bluetooth::audio::asrc::SourceAudioHalAsrc> asrc;

public:
  ~HearingAidImpl() override = default;

  HearingAidImpl(bluetooth::hearing_aid::HearingAidCallbacks* callbacks, Closure initCb)
      : audio_running(false),
        overwrite_min_ce_len(-1),
        overwrite_max_ce_len(-1),
        gatt_if(0),
        seq_counter(0),
        current_volume(VOLUME_UNKNOWN),
        callbacks(callbacks),
        codec_in_use(0) {
    default_data_interval_ms = (uint16_t)osi_property_get_int32(
            "persist.bluetooth.hearingaid.interval", (int32_t)HA_INTERVAL_20_MS);

    if ((default_data_interval_ms != HA_INTERVAL_10_MS) &&
        (default_data_interval_ms != HA_INTERVAL_20_MS)) {
      log::error("invalid interval={}ms. Overwrriting back to default", default_data_interval_ms);
      default_data_interval_ms = HA_INTERVAL_20_MS;
    }

    overwrite_min_ce_len = (int16_t)osi_property_get_int32(PERSIST_MIN_CE_LEN_NAME.c_str(), -1);
    overwrite_max_ce_len = (int16_t)osi_property_get_int32(PERSIST_MAX_CE_LEN_NAME.c_str(), -1);

    log::info("default_data_interval_ms={} overwrite_min_ce_len={} overwrite_max_ce_len={}",
              default_data_interval_ms, overwrite_min_ce_len, overwrite_max_ce_len);

    BTA_GATTC_AppRegister(
            "asha", hearingaid_gattc_callback,
            base::Bind(
                    [](Closure initCb, uint8_t client_id, uint8_t status) {
                      if (status != GATT_SUCCESS) {
                        log::error("Can't start Hearing Aid profile - no gatt clients left!");
                        return;
                      }
                      instance->gatt_if = client_id;
                      initCb.Run();
                    },
                    initCb),
            false);

    IsoManager::GetInstance()->Start();
    IsoManager::GetInstance()->RegisterOnIsoTrafficActiveCallback([](bool is_active) {
      if (!instance) {
        return;
      }
      instance->IsoTrafficEventCb(is_active);
    });
  }

  void IsoTrafficEventCb(bool is_active) {
    if (is_active) {
      is_iso_running = true;
      needs_parameter_update = true;
    } else {
      is_iso_running = false;
    }

    log::info("is_iso_running={} needs_parameter_update={}", is_iso_running,
              needs_parameter_update);

    if (needs_parameter_update) {
      for (auto& device : hearingDevices.devices) {
        if (device.conn_id != INVALID_CONN_ID) {
          device.connection_update_status = STARTED;
          device.requested_connection_interval = UpdateBleConnParams(device.address);
        }
      }
    }
  }

  // Reset and configure the ASHA resampling context using the input device
  // devices as reference for the BT clock estimation.
  void ConfigureAsrc() {
    // Create a new ASRC context if required.
    if (asrc == nullptr) {
      log::info("Configuring Asha resampler");
      asrc = std::make_unique<bluetooth::audio::asrc::SourceAudioHalAsrc>(
              /*thread*/ get_main_thread(),
              /*channels*/ 2,
              /*sample_rate*/ codec_in_use == CODEC_G722_24KHZ ? 24000 : 16000,
              /*bit_depth*/ 16,
              /*interval_us*/ default_data_interval_ms * 1000,
              /*num_burst_buffers*/ 0,
              /*burst_delay*/ 0);
    }
  }

  // Reset the ASHA resampling context.
  void ResetAsrc() {
    log::info("Resetting the Asha resampling context");
    asrc = nullptr;
  }

  uint16_t UpdateBleConnParams(const RawAddress& address) {
    /* List of parameters that depends on the chosen Connection Interval */
    uint16_t min_ce_len = MIN_CE_LEN_20MS_CI;
    uint16_t max_ce_len = MAX_CE_LEN_20MS_CI;
    uint16_t connection_interval;

    switch (default_data_interval_ms) {
      case HA_INTERVAL_10_MS:
        min_ce_len = MIN_CE_LEN_10MS_CI;
        connection_interval = CONNECTION_INTERVAL_10MS_PARAM;
        break;

      case HA_INTERVAL_20_MS:
        // When ISO is connected, the controller might not be able to
        // update the connection event length successfully.
        // So if ISO is running, we use a small ce length to connect first,
        // then update to a better value later on
        if (is_iso_running) {
          min_ce_len = CE_LEN_20MS_CI_ISO_RUNNING;
          max_ce_len = CE_LEN_20MS_CI_ISO_RUNNING;
          needs_parameter_update = true;
        } else {
          min_ce_len = MIN_CE_LEN_20MS_CI;
          max_ce_len = MAX_CE_LEN_20MS_CI;
          needs_parameter_update = false;
        }
        connection_interval = CONNECTION_INTERVAL_20MS_PARAM;
        break;

      default:
        log::error("invalid default_data_interval_ms={}", default_data_interval_ms);
        min_ce_len = MIN_CE_LEN_10MS_CI;
        connection_interval = CONNECTION_INTERVAL_10MS_PARAM;
    }

    if (overwrite_min_ce_len != -1) {
      log::warn("min_ce_len={} for device {} is overwritten to {}", min_ce_len, address,
                overwrite_min_ce_len);
      min_ce_len = overwrite_min_ce_len;
    }
    if (overwrite_max_ce_len != -1) {
      log::warn("max_ce_len={} for device {} is overwritten to {}", max_ce_len, address,
                overwrite_max_ce_len);
      max_ce_len = overwrite_max_ce_len;
    }

    log::info("L2CA_UpdateBleConnParams for device {} min_ce_len:{} max_ce_len:{}", address,
              min_ce_len, max_ce_len);
    if (!stack::l2cap::get_interface().L2CA_UpdateBleConnParams(
                address, connection_interval, connection_interval, 0x000A, 0x0064 /*1s*/,
                min_ce_len, max_ce_len)) {
      log::warn("Unable to update L2CAP ble connection parameters peer:{}", address);
    }
    return connection_interval;
  }

  bool IsBelowDropFrequency(std::chrono::time_point<std::chrono::steady_clock> tp) {
    auto duration = tp - last_drop_time_point;
    bool droppable = std::chrono::duration_cast<std::chrono::seconds>(duration).count() >=
                     kDropFrequencyThreshold;
    log::info("IsBelowDropFrequency {}", droppable);
    return droppable;
  }

  void Connect(const RawAddress& address) {
    log::info("bd_addr={}", address);
    hearingDevices.Add(HearingDevice(address, true));
    BTA_GATTC_Open(gatt_if, address, BTM_BLE_DIRECT_CONNECTION, false);
  }

  void AddToAcceptlist(const RawAddress& address) {
    log::info("bd_addr={}", address);
    hearingDevices.Add(HearingDevice(address, true));
    BTA_GATTC_Open(gatt_if, address, BTM_BLE_BKG_CONNECT_ALLOW_LIST, false);
  }

  void AddFromStorage(const HearingDevice& dev_info, bool is_acceptlisted) {
    log::info("bd_addr={} hi_sync_id=0x{:x} is_acceptlisted={}", dev_info.address,
              dev_info.hi_sync_id, is_acceptlisted);
    if (is_acceptlisted) {
      hearingDevices.Add(dev_info);

      // TODO: we should increase the scanning window for few seconds, to get
      // faster initial connection, same after hearing aid disconnects, i.e.
      // BTM_BleSetConnScanParams(2048, 1024);

      /* add device into BG connection to accept remote initiated connection */
      BTA_GATTC_Open(gatt_if, dev_info.address, BTM_BLE_BKG_CONNECT_ALLOW_LIST, false);
    }

    callbacks->OnDeviceAvailable(dev_info.capabilities, dev_info.hi_sync_id, dev_info.address);
  }

  int GetDeviceCount() { return hearingDevices.size(); }

  void OnGattConnected(tGATT_STATUS status, tCONN_ID conn_id, tGATT_IF /*client_if*/,
                       RawAddress address, tBT_TRANSPORT /*transport*/, uint16_t /*mtu*/) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      /* When Hearing Aid is quickly disabled and enabled in settings, this case
       * might happen */
      log::warn("Closing connection to non hearing-aid device: bd_addr={}", address);
      BTA_GATTC_Close(conn_id);
      return;
    }

    log::info("address={}, conn_id={}", address, conn_id);

    if (status != GATT_SUCCESS) {
      if (!hearingDevice->connecting_actively) {
        // acceptlist connection failed, that's ok.
        return;
      }

      if (hearingDevice->switch_to_background_connection_after_failure) {
        hearingDevice->connecting_actively = false;
        hearingDevice->switch_to_background_connection_after_failure = false;
        BTA_GATTC_Open(gatt_if, address, BTM_BLE_BKG_CONNECT_ALLOW_LIST, false);
      } else {
        log::info("Failed to connect to Hearing Aid device, bda={}", address);

        hearingDevices.Remove(address);
        callbacks->OnConnectionState(ConnectionState::DISCONNECTED, address);
      }
      return;
    }

    hearingDevice->conn_id = conn_id;

    if (com::android::bluetooth::flags::gatt_queue_cleanup_connected()) {
      BtaGattQueue::Clean(conn_id);
    }

    uint64_t hi_sync_id = hearingDevice->hi_sync_id;

    // If there a background connection to the other device of a pair, promote
    // it to a direct connection to scan more aggressively for it
    if (hi_sync_id != 0) {
      for (auto& device : hearingDevices.devices) {
        if (device.hi_sync_id == hi_sync_id && device.conn_id == INVALID_CONN_ID &&
            !device.connecting_actively) {
          log::info("Promoting device from the set from background to direct connection, bda={}",
                    device.address);
          device.connecting_actively = true;
          device.switch_to_background_connection_after_failure = true;
          BTA_GATTC_Open(gatt_if, device.address, BTM_BLE_DIRECT_CONNECTION, false);
        }
      }
    }

    hearingDevice->connection_update_status = STARTED;
    hearingDevice->requested_connection_interval = UpdateBleConnParams(address);

    if (bluetooth::shim::GetController()->SupportsBle2mPhy()) {
      log::info("{} set preferred 2M PHY", address);
      get_btm_client_interface().ble.BTM_BleSetPhy(address, PHY_LE_2M, PHY_LE_2M, 0);
    }

    // Set data length
    // TODO(jpawlowski: for 16khz only 87 is required, optimize
    if (get_btm_client_interface().ble.BTM_SetBleDataLength(address, 167) !=
        tBTM_STATUS::BTM_SUCCESS) {
      log::warn("Unable to set BLE data length peer:{} size:{}", address, 167);
    }

    if (BTM_SecIsLeSecurityPending(address)) {
      /* if security collision happened, wait for encryption done
       * (BTA_GATTC_ENC_CMPL_CB_EVT) */
      return;
    }

    /* verify bond */
    if (BTM_IsEncrypted(address, BT_TRANSPORT_LE)) {
      /* if link has been encrypted */
      OnEncryptionComplete(address, true);
      return;
    }

    if (BTM_IsBonded(address, BT_TRANSPORT_LE)) {
      /* if bonded and link not encrypted */
      BTM_SetEncryption(address, BT_TRANSPORT_LE, encryption_callback, nullptr,
                        BTM_BLE_SEC_ENCRYPT);
      return;
    }

    /* otherwise let it go through */
    OnEncryptionComplete(address, true);
  }

  void OnConnectionUpdateComplete(tCONN_ID conn_id, tBTA_GATTC* p_data) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    if (p_data) {
      if (p_data->conn_update.status == 0) {
        bool same_conn_interval =
                (hearingDevice->requested_connection_interval == p_data->conn_update.interval);

        switch (hearingDevice->connection_update_status) {
          case COMPLETED:
            if (!same_conn_interval) {
              log::warn(
                      "Unexpected change. Redo. connection interval={}, "
                      "expected={}, conn_id={}, connection_update_status={}",
                      p_data->conn_update.interval, hearingDevice->requested_connection_interval,
                      conn_id, hearingDevice->connection_update_status);
              // Redo this connection interval change.
              hearingDevice->connection_update_status = AWAITING;
            }
            break;
          case STARTED:
            if (same_conn_interval) {
              log::info("Connection update completed: conn_id={} bd_addr={}", conn_id,
                        hearingDevice->address);
              hearingDevice->connection_update_status = COMPLETED;
            } else {
              log::warn(
                      "Ignored. Different connection interval={}, expected={}, "
                      "conn_id={}, connection_update_status={}",
                      p_data->conn_update.interval, hearingDevice->requested_connection_interval,
                      conn_id, hearingDevice->connection_update_status);
              // Wait for the right Connection Update Completion.
              return;
            }
            break;
          case AWAITING:
          case NONE:
            break;
        }

        // Inform this side and other side device (if any) of Connection
        // Updates.
        std::vector<uint8_t> conn_update({CONTROL_POINT_OP_STATE_CHANGE, STATE_CHANGE_CONN_UPDATE,
                                          (uint8_t)p_data->conn_update.interval});
        send_state_change_to_other_side(hearingDevice, conn_update);
        send_state_change(hearingDevice, conn_update);
      } else {
        log::info("error status=0x{:x}, conn_id={} bd_addr={}, connection_update_status={}",
                  static_cast<uint8_t>(p_data->conn_update.status), conn_id, hearingDevice->address,
                  hearingDevice->connection_update_status);
        if (hearingDevice->connection_update_status == STARTED) {
          // Redo this connection interval change.
          log::error("Redo Connection Interval change");
          hearingDevice->connection_update_status = AWAITING;
        }
      }
    } else {
      hearingDevice->connection_update_status = NONE;
    }

    if (!hearingDevice->accepting_audio && hearingDevice->connection_update_status == COMPLETED &&
        hearingDevice->gap_opened) {
      OnDeviceReady(hearingDevice->address);
    }

    for (auto& device : hearingDevices.devices) {
      if (device.conn_id != INVALID_CONN_ID && (device.connection_update_status == AWAITING)) {
        device.connection_update_status = STARTED;
        device.requested_connection_interval = UpdateBleConnParams(device.address);
        return;
      }
    }
  }

  // Completion Callback for the RSSI read operation.
  void OnReadRssiComplete(const RawAddress& address, int8_t rssi_value) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::info("Skipping unknown device {}", address);
      return;
    }

    log::debug("bd_addr={} rssi={}", address, (int)rssi_value);

    if (hearingDevice->read_rssi_count <= 0) {
      log::error("bd_addr={}, invalid read_rssi_count={}", address, hearingDevice->read_rssi_count);
      return;
    }

    rssi_log& last_log_set = hearingDevice->audio_stats.rssi_history.back();

    if (hearingDevice->read_rssi_count == READ_RSSI_NUM_TRIES) {
      // Store the timestamp only for the first one after packet flush
      clock_gettime(CLOCK_REALTIME, &last_log_set.timestamp);
      log::info("store time, bd_addr={}, rssi={}", address, (int)rssi_value);
    }

    last_log_set.rssi.emplace_back(rssi_value);
    hearingDevice->read_rssi_count--;
  }

  void OnEncryptionComplete(const RawAddress& address, bool success) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::error("unknown device: bd_addr={}", address);
      return;
    }

    if (!success) {
      log::error("encryption failed: bd_addr={}", address);
      BTA_GATTC_Close(hearingDevice->conn_id);
      if (hearingDevice->first_connection) {
        callbacks->OnConnectionState(ConnectionState::DISCONNECTED, address);
      }
      return;
    }

    log::info("encryption successful: bd_addr={}", address);

    if (hearingDevice->audio_control_point_handle && hearingDevice->audio_status_handle &&
        hearingDevice->audio_status_ccc_handle && hearingDevice->volume_handle &&
        hearingDevice->read_psm_handle) {
      // Use cached data, jump to read PSM
      ReadPSM(hearingDevice);
    } else {
      log::info("starting service search request for ASHA: bd_addr={}", address);
      hearingDevice->first_connection = true;
      BTA_GATTC_ServiceSearchRequest(hearingDevice->conn_id, HEARING_AID_UUID);
    }
  }

  // Just take care phy update successful case to avoid loop executing.
  void OnPhyUpdateEvent(tCONN_ID conn_id, uint8_t tx_phys, uint8_t rx_phys, tGATT_STATUS status) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    if (status != GATT_SUCCESS) {
      log::warn("phy update failed: bd_addr={} status={}", hearingDevice->address, status);
      return;
    }

    if (tx_phys == PHY_LE_2M && rx_phys == PHY_LE_2M) {
      log::info("phy update to 2M successful: bd_addr={}", hearingDevice->address);
      hearingDevice->phy_update_retry_remain = kPhyUpdateRetryLimit;
      return;
    }

    if (hearingDevice->phy_update_retry_remain > 0) {
      log::info(
              "phy update successful with unexpected phys, retrying:"
              " bd_addr={} tx_phy=0x{:x} rx_phy=0x{:x}",
              hearingDevice->address, tx_phys, rx_phys);
      get_btm_client_interface().ble.BTM_BleSetPhy(hearingDevice->address, PHY_LE_2M, PHY_LE_2M, 0);
      hearingDevice->phy_update_retry_remain--;
    } else {
      log::warn(
              "phy update successful with unexpected phys, exceeded retry count:"
              " bd_addr={} tx_phy=0x{:x} rx_phy=0x{:x}",
              hearingDevice->address, tx_phys, rx_phys);
    }
  }

  void OnServiceChangeEvent(const RawAddress& address) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::error("unknown device: bd_addr={}", address);
      return;
    }

    log::info("bd_addr={}", address);

    hearingDevice->first_connection = true;
    hearingDevice->service_changed_rcvd = true;
    BtaGattQueue::Clean(hearingDevice->conn_id);

    if (hearingDevice->gap_handle != GAP_INVALID_HANDLE) {
      GAP_ConnClose(hearingDevice->gap_handle);
      hearingDevice->gap_handle = GAP_INVALID_HANDLE;
    }
  }

  void OnServiceDiscDoneEvent(const RawAddress& address) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::error("unknown device: bd_addr={}", address);
      return;
    }

    log::info("bd_addr={}", address);

    if (hearingDevice->service_changed_rcvd ||
        !(hearingDevice->audio_control_point_handle && hearingDevice->audio_status_handle &&
          hearingDevice->audio_status_ccc_handle && hearingDevice->volume_handle &&
          hearingDevice->read_psm_handle)) {
      log::info("starting service search request for ASHA: bd_addr={}", address);
      BTA_GATTC_ServiceSearchRequest(hearingDevice->conn_id, HEARING_AID_UUID);
    }
  }

  void OnServiceSearchComplete(tCONN_ID conn_id, tGATT_STATUS status) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    // Known device, nothing to do.
    if (!hearingDevice->first_connection) {
      log::info("service discovery result ignored: bd_addr={}", hearingDevice->address);
      return;
    }

    if (status != GATT_SUCCESS) {
      /* close connection and report service discovery complete with error */
      log::error("service discovery failed: bd_addr={} status={}", hearingDevice->address, status);

      if (hearingDevice->first_connection) {
        callbacks->OnConnectionState(ConnectionState::DISCONNECTED, hearingDevice->address);
      }
      return;
    }

    log::info("service discovery successful: bd_addr={}", hearingDevice->address);

    const std::list<gatt::Service>* services = BTA_GATTC_GetServices(conn_id);

    const gatt::Service* service = nullptr;
    for (const gatt::Service& tmp : *services) {
      if (tmp.uuid == Uuid::From16Bit(UUID_SERVCLASS_GATT_SERVER)) {
        log::info("Found UUID_SERVCLASS_GATT_SERVER, handle=0x{:x}", tmp.handle);
        const gatt::Service* service_changed_service = &tmp;
        find_server_changed_ccc_handle(conn_id, service_changed_service);
      } else if (tmp.uuid == HEARING_AID_UUID) {
        log::info("Found Hearing Aid service, handle=0x{:x}", tmp.handle);
        service = &tmp;
      }
    }

    if (!service) {
      log::error("No Hearing Aid service found");
      callbacks->OnConnectionState(ConnectionState::DISCONNECTED, hearingDevice->address);
      return;
    }

    for (const gatt::Characteristic& charac : service->characteristics) {
      if (charac.uuid == READ_ONLY_PROPERTIES_UUID) {
        if (!btif_storage_get_hearing_aid_prop(
                    hearingDevice->address, &hearingDevice->capabilities,
                    &hearingDevice->hi_sync_id, &hearingDevice->render_delay,
                    &hearingDevice->preparation_delay, &hearingDevice->codecs)) {
          log::debug("Reading read only properties 0x{:x}", charac.value_handle);
          BtaGattQueue::ReadCharacteristic(conn_id, charac.value_handle,
                                           HearingAidImpl::OnReadOnlyPropertiesReadStatic, nullptr);
        }
      } else if (charac.uuid == AUDIO_CONTROL_POINT_UUID) {
        hearingDevice->audio_control_point_handle = charac.value_handle;
        // store audio control point!
      } else if (charac.uuid == AUDIO_STATUS_UUID) {
        hearingDevice->audio_status_handle = charac.value_handle;

        hearingDevice->audio_status_ccc_handle = find_ccc_handle(conn_id, charac.value_handle);
        if (!hearingDevice->audio_status_ccc_handle) {
          log::error("cannot find Audio Status CCC descriptor");
          continue;
        }

        log::info("audio_status_handle=0x{:x}, ccc=0x{:x}", charac.value_handle,
                  hearingDevice->audio_status_ccc_handle);
      } else if (charac.uuid == VOLUME_UUID) {
        hearingDevice->volume_handle = charac.value_handle;
      } else if (charac.uuid == LE_PSM_UUID) {
        hearingDevice->read_psm_handle = charac.value_handle;
      } else {
        log::warn("Unknown characteristic found:{}", charac.uuid.ToString());
      }
    }

    if (hearingDevice->service_changed_rcvd) {
      hearingDevice->service_changed_rcvd = false;
    }

    ReadPSM(hearingDevice);
  }

  void ReadPSM(HearingDevice* hearingDevice) {
    if (hearingDevice->read_psm_handle) {
      log::info("bd_addr={} handle=0x{:x}", hearingDevice->address, hearingDevice->read_psm_handle);
      BtaGattQueue::ReadCharacteristic(hearingDevice->conn_id, hearingDevice->read_psm_handle,
                                       HearingAidImpl::OnPsmReadStatic, nullptr);
    }
  }

  void OnNotificationEvent(tCONN_ID conn_id, uint16_t handle, uint16_t len, uint8_t* value) {
    HearingDevice* device = hearingDevices.FindByConnId(conn_id);
    if (!device) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    if (device->audio_status_handle != handle) {
      log::warn("unexpected handle: bd_addr={} audio_status_handle=0x{:x} handle=0x{:x}",
                device->address, device->audio_status_handle, handle);
      return;
    }

    if (len < 1) {
      log::warn("invalid data length (expected 1+ bytes): bd_addr={} len={}", device->address, len);
      return;
    }

    if (value[0] != 0) {
      log::warn("received error status: bd_addr={} status=0x{:x}", device->address, value[0]);
      return;
    }

    log::info("received success notification: bd_addr={} command_acked={}", device->address,
              device->command_acked);
    device->command_acked = true;
  }

  void OnReadOnlyPropertiesRead(tCONN_ID conn_id, tGATT_STATUS /*status*/, uint16_t /*handle*/,
                                uint16_t len, uint8_t* value, void* /*data*/) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    uint8_t* p = value;

    uint8_t version;
    STREAM_TO_UINT8(version, p);

    if (version != 0x01) {
      log::warn("unsupported version: bd_addr={} version=0x{:x}", hearingDevice->address, version);
      return;
    }

    // version 0x01 of read only properties:
    if (len < 17) {
      log::warn("invalid data length (expected 17+ bytes): bd_addr={} len={}",
                hearingDevice->address, len);
      return;
    }

    uint8_t capabilities;
    STREAM_TO_UINT8(capabilities, p);
    STREAM_TO_UINT64(hearingDevice->hi_sync_id, p);
    uint8_t feature_map;
    STREAM_TO_UINT8(feature_map, p);
    STREAM_TO_UINT16(hearingDevice->render_delay, p);
    STREAM_TO_UINT16(hearingDevice->preparation_delay, p);
    uint16_t codecs;
    STREAM_TO_UINT16(codecs, p);

    hearingDevice->capabilities = capabilities;
    hearingDevice->codecs = codecs;

    bool side = capabilities & CAPABILITY_SIDE;
    bool binaural = capabilities & CAPABILITY_BINAURAL;
    bool csis_capable = capabilities & CAPABILITY_CSIS;

    if (capabilities & CAPABILITY_RESERVED) {
      log::warn("reserved capabilities bits are set: bd_addr={} capabilities=0x{:x}",
                hearingDevice->address, capabilities);
    }

    bool g722_16khz_supported = codecs & (1 << CODEC_G722_16KHZ);
    bool g722_24khz_supported = codecs & (1 << CODEC_G722_24KHZ);

    if (!g722_16khz_supported) {
      log::warn("mandatory codec G722@16kHz not supported: bd_addr={}", hearingDevice->address);
    }

    log::info(
            "device capabilities: bd_addr={} side={} binaural={}"
            " CSIS_supported={} hi_sync_id=0x{:x} render_delay={}"
            " preparation_delay={} G722@16kHz_supported={} G722@24kHz_supported={}",
            hearingDevice->address, side ? "right" : "left", binaural, csis_capable,
            hearingDevice->hi_sync_id, hearingDevice->render_delay,
            hearingDevice->preparation_delay, g722_16khz_supported, g722_24khz_supported);
  }

  uint16_t CalcCompressedAudioPacketSize(uint16_t codec_type, int connection_interval) {
    int sample_rate;

    const int sample_bit_rate = 16;  /* 16 bits per sample */
    const int compression_ratio = 4; /* G.722 has a 4:1 compression ratio */
    if (codec_type == CODEC_G722_24KHZ) {
      sample_rate = 24000;
    } else {
      sample_rate = 16000;
    }

    // compressed_data_packet_size is the size in bytes of the compressed audio
    // data buffer that is generated for each connection interval.
    uint32_t compressed_data_packet_size =
            (sample_rate * connection_interval * (sample_bit_rate / 8) / compression_ratio) / 1000;
    return (uint16_t)compressed_data_packet_size;
  }

  void ChooseCodec(const HearingDevice& hearingDevice) {
    if (codec_in_use) {
      return;
    }

    // use the best codec available for this pair of devices.
    uint16_t codecs = hearingDevice.codecs;
    if (hearingDevice.hi_sync_id != 0) {
      for (const auto& device : hearingDevices.devices) {
        if (device.hi_sync_id != hearingDevice.hi_sync_id) {
          continue;
        }

        codecs &= device.codecs;
      }
    }

    if ((codecs & (1 << CODEC_G722_24KHZ)) &&
        bluetooth::shim::GetController()->SupportsBle2mPhy() &&
        default_data_interval_ms == HA_INTERVAL_10_MS) {
      codec_in_use = CODEC_G722_24KHZ;
    } else if (codecs & (1 << CODEC_G722_16KHZ)) {
      codec_in_use = CODEC_G722_16KHZ;
    }
  }

  void OnAudioStatus(tCONN_ID /*conn_id*/, tGATT_STATUS /*status*/, uint16_t /*handle*/,
                     uint16_t len, uint8_t* value, void* /*data*/) {
    log::info("{}", base::HexEncode(value, len));
  }

  void OnPsmRead(tCONN_ID conn_id, tGATT_STATUS status, uint16_t /*handle*/, uint16_t len,
                 uint8_t* value, void* /*data*/) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    if (status != GATT_SUCCESS) {
      log::error("error reading PSM: bd_addr={} status={}", hearingDevice->address, status);
      return;
    }

    if (len < 2) {
      log::error("invalid PSM length: bd_addr={} len={}", hearingDevice->address, len);
      return;
    }

    uint16_t psm = 0;
    STREAM_TO_UINT16(psm, value);

    log::info("read PSM: bd_addr={} psm=0x{:x}", hearingDevice->address, psm);

    if (hearingDevice->gap_handle == GAP_INVALID_HANDLE &&
        BTM_IsEncrypted(hearingDevice->address, BT_TRANSPORT_LE)) {
      ConnectSocket(hearingDevice, psm);
    }
  }

  void ConnectSocket(HearingDevice* hearingDevice, uint16_t psm) {
    tL2CAP_CFG_INFO cfg_info = tL2CAP_CFG_INFO{.mtu = 512};

    log::info("bd_addr={} psm=0x{:x}", hearingDevice->address, psm);

    SendEnableServiceChangedInd(hearingDevice);

    uint8_t service_id = hearingDevice->isLeft() ? BTM_SEC_SERVICE_HEARING_AID_LEFT
                                                 : BTM_SEC_SERVICE_HEARING_AID_RIGHT;
    uint16_t gap_handle = GAP_ConnOpen(
            "", service_id, false, &hearingDevice->address, psm, 514 /* MPS */, &cfg_info, nullptr,
            /// b/309483354:
            /// Encryption needs to be explicitly requested at channel
            /// establishment even though validation is performed in this module
            /// because of re-connection logic present in the L2CAP module.
            /// The L2CAP will automatically reconnect the LE-ACL link on
            /// disconnection when there is a pending channel request,
            /// which invalidates all encryption checks performed here.
            BTM_SEC_IN_ENCRYPT | BTM_SEC_OUT_ENCRYPT,
            HearingAidImpl::GapCallbackStatic, BT_TRANSPORT_LE);

    if (gap_handle == GAP_INVALID_HANDLE) {
      log::error("failed to open socket: bd_addr={}", hearingDevice->address);
    } else {
      hearingDevice->gap_handle = gap_handle;
      log::info("sent GAP connect request: bd_addr={}, gap_handle={}", hearingDevice->address,
                gap_handle);
    }
  }

  static void OnReadOnlyPropertiesReadStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                             uint16_t len, uint8_t* value, void* data) {
    if (instance) {
      instance->OnReadOnlyPropertiesRead(conn_id, status, handle, len, value, data);
    }
  }

  static void OnAudioStatusStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                  uint16_t len, uint8_t* value, void* data) {
    if (instance) {
      instance->OnAudioStatus(conn_id, status, handle, len, value, data);
    }
  }

  static void OnPsmReadStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle, uint16_t len,
                              uint8_t* value, void* data) {
    if (instance) {
      instance->OnPsmRead(conn_id, status, handle, len, value, data);
    }
  }

  /* CoC Socket, BLE connection parameter are ready */
  void OnDeviceReady(const RawAddress& address) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::error("unknown device: bd_addr={}", address);
      return;
    }

    log::info("bd_addr={}", address);

    if (hearingDevice->first_connection) {
      btif_storage_add_hearing_aid(*hearingDevice);

      hearingDevice->first_connection = false;
    }

    /* Register and enable the Audio Status Notification */
    tGATT_STATUS register_status = BTA_GATTC_RegisterForNotifications(
            gatt_if, address, hearingDevice->audio_status_handle);

    if (register_status != GATT_SUCCESS) {
      log::error("failed to register for notifications: bd_addr={} status={} handle=0x{:x}",
                 address, register_status, hearingDevice->audio_status_handle);
      return;
    }

    std::vector<uint8_t> value(2);
    uint8_t* ptr = value.data();
    UINT16_TO_STREAM(ptr, GATT_CHAR_CLIENT_CONFIG_NOTIFICATION);

    BtaGattQueue::WriteDescriptor(hearingDevice->conn_id, hearingDevice->audio_status_ccc_handle,
                                  std::move(value), GATT_WRITE, write_rpt_ctl_cfg_cb, nullptr);

    ChooseCodec(*hearingDevice);
    SendStart(hearingDevice);

    if (audio_running) {
      // Inform the other side (if any) of this connection
      std::vector<uint8_t> inform_conn_state(
              {CONTROL_POINT_OP_STATE_CHANGE, STATE_CHANGE_OTHER_SIDE_CONNECTED});
      send_state_change_to_other_side(hearingDevice, inform_conn_state);
    }

    hearingDevice->connecting_actively = false;
    hearingDevice->accepting_audio = true;

    StartSendingAudio(*hearingDevice);
    callbacks->OnDeviceAvailable(hearingDevice->capabilities, hearingDevice->hi_sync_id, address);
    callbacks->OnConnectionState(ConnectionState::CONNECTED, address);
  }

  void StartSendingAudio(const HearingDevice& hearingDevice) {
    log::info("bd_addr={}", hearingDevice.address);

    if (encoder_state_left == nullptr) {
      encoder_state_init();
      seq_counter = 0;

      CodecConfiguration codec;
      if (codec_in_use == CODEC_G722_24KHZ) {
        codec.sample_rate = 24000;
      } else {
        codec.sample_rate = 16000;
      }
      codec.bit_rate = 16;
      codec.data_interval_ms = default_data_interval_ms;

      uint16_t delay_report_ms = 0;
      if (hearingDevice.render_delay != 0) {
        delay_report_ms = hearingDevice.render_delay +
                          (ADD_RENDER_DELAY_INTERVALS * default_data_interval_ms);
      }

      HearingAidAudioSource::Start(codec, audioReceiver, delay_report_ms);
    }
  }

  void OnAudioSuspend(const std::function<void()>& stop_audio_ticks) {
    log::assert_that((bool)stop_audio_ticks, "stop_audio_ticks is empty");

    if (!audio_running) {
      log::warn("Unexpected audio suspend");
    } else {
      log::info("audio_running={}", audio_running);
    }

    // Close the ASRC context.
    ResetAsrc();

    audio_running = false;
    stop_audio_ticks();

    std::vector<uint8_t> stop({CONTROL_POINT_OP_STOP});
    for (auto& device : hearingDevices.devices) {
      if (!device.accepting_audio) {
        continue;
      }

      if (!device.playback_started) {
        log::warn("Playback not started, skip send Stop cmd, bd_addr={}", device.address);
      } else {
        log::info("send Stop cmd, bd_addr={}", device.address);
        device.playback_started = false;
        device.command_acked = false;
        BtaGattQueue::WriteCharacteristic(device.conn_id, device.audio_control_point_handle, stop,
                                          GATT_WRITE, nullptr, nullptr);
      }
    }
  }

  void OnAudioResume(const std::function<void()>& start_audio_ticks) {
    log::assert_that((bool)start_audio_ticks, "start_audio_ticks is empty");

    if (audio_running) {
      log::error("Unexpected Audio Resume");
    } else {
      log::info("audio_running={}", audio_running);
    }

    for (auto& device : hearingDevices.devices) {
      if (!device.accepting_audio) {
        continue;
      }
      audio_running = true;
      SendStart(&device);
    }

    if (!audio_running) {
      log::info("No device (0/{}) ready to start", GetDeviceCount());
      return;
    }

    // Open the ASRC context.
    ConfigureAsrc();

    // TODO: shall we also reset the encoder ?
    encoder_state_release();
    encoder_state_init();
    seq_counter = 0;

    start_audio_ticks();
  }

  uint8_t GetOtherSideStreamStatus(HearingDevice* this_side_device) {
    for (auto& device : hearingDevices.devices) {
      if ((device.address == this_side_device->address) ||
          (device.hi_sync_id != this_side_device->hi_sync_id)) {
        continue;
      }
      if (audio_running && (device.conn_id != INVALID_CONN_ID)) {
        return OTHER_SIDE_IS_STREAMING;
      } else {
        return OTHER_SIDE_NOT_STREAMING;
      }
    }
    return OTHER_SIDE_NOT_STREAMING;
  }

  void SendEnableServiceChangedInd(HearingDevice* device) {
    log::info("bd_addr={}", device->address);

    std::vector<uint8_t> value(2);
    uint8_t* ptr = value.data();
    UINT16_TO_STREAM(ptr, GATT_CHAR_CLIENT_CONFIG_INDICTION);

    BtaGattQueue::WriteDescriptor(device->conn_id, device->service_changed_ccc_handle,
                                  std::move(value), GATT_WRITE, nullptr, nullptr);
  }

  void SendStart(HearingDevice* device) {
    std::vector<uint8_t> start({CONTROL_POINT_OP_START, codec_in_use, AUDIOTYPE_UNKNOWN,
                                (uint8_t)current_volume, OTHER_SIDE_NOT_STREAMING});

    if (!audio_running) {
      if (!device->playback_started) {
        log::info("Skip Send Start since audio is not running, bd_addr={}", device->address);
      } else {
        log::error("Audio not running but Playback has started, bd_addr={}", device->address);
      }
      return;
    }

    if (current_volume == VOLUME_UNKNOWN) {
      start[3] = (uint8_t)VOLUME_MIN;
    }

    if (device->playback_started) {
      log::error("Playback already started, skip send Start cmd, bd_addr={}", device->address);
    } else {
      start[4] = GetOtherSideStreamStatus(device);
      log::info(
              "send Start cmd, volume=0x{:x}, audio type=0x{:x}, bd_addr={}, other "
              "side streaming=0x{:x}",
              start[3], start[2], device->address, start[4]);
      device->command_acked = false;
      BtaGattQueue::WriteCharacteristic(device->conn_id, device->audio_control_point_handle, start,
                                        GATT_WRITE, HearingAidImpl::StartAudioCtrlCallbackStatic,
                                        nullptr);
    }
  }

  static void StartAudioCtrlCallbackStatic(tCONN_ID conn_id, tGATT_STATUS status, uint16_t handle,
                                           uint16_t /*len*/, const uint8_t* /*value*/,
                                           void* /*data*/) {
    if (status != GATT_SUCCESS) {
      log::error("handle={}, conn_id={}, status=0x{:x}", handle, conn_id,
                 static_cast<uint8_t>(status));
      return;
    }
    if (!instance) {
      log::error("instance is null");
      return;
    }
    instance->StartAudioCtrlCallback(conn_id);
  }

  void StartAudioCtrlCallback(tCONN_ID conn_id) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("Skipping unknown device, conn_id=0x{:x}", conn_id);
      return;
    }
    log::info("device: {}", hearingDevice->address);
    hearingDevice->playback_started = true;
  }

  /* Compare the two sides LE CoC credit and return true to drop two sides
   * packet on these situations.
   * 1) The credit is close
   * 2) Other side is disconnected
   * 3) Get one side current credit value failure.
   *
   * Otherwise, just flush audio packet one side.
   */
  bool NeedToDropPacket(HearingDevice* target_side, HearingDevice* other_side) {
    // Just drop packet if the other side does not exist.
    if (!other_side) {
      log::debug("other side not connected to profile");
      return true;
    }

    uint16_t diff_credit = 0;

    uint16_t target_current_credit = stack::l2cap::get_interface().L2CA_GetPeerLECocCredit(
            target_side->address, GAP_ConnGetL2CAPCid(target_side->gap_handle));
    if (target_current_credit == L2CAP_LE_CREDIT_MAX) {
      log::error("Get target side credit value fail.");
      return true;
    }

    uint16_t other_current_credit = stack::l2cap::get_interface().L2CA_GetPeerLECocCredit(
            other_side->address, GAP_ConnGetL2CAPCid(other_side->gap_handle));
    if (other_current_credit == L2CAP_LE_CREDIT_MAX) {
      log::error("Get other side credit value fail.");
      return true;
    }

    if (target_current_credit > other_current_credit) {
      diff_credit = target_current_credit - other_current_credit;
    } else {
      diff_credit = other_current_credit - target_current_credit;
    }
    log::debug("Target({}) Credit: {}, Other({}) Credit: {}, Init Credit: {}", target_side->address,
               target_current_credit, other_side->address, other_current_credit, init_credit);
    return diff_credit < (init_credit / 2 - 1);
  }

  void OnAudioDataReadyResample(const std::vector<uint8_t>& data) {
    if (asrc == nullptr) {
      return OnAudioDataReady(data);
    }

    for (auto const resampled_data : asrc->Run(data)) {
      OnAudioDataReady(*resampled_data);
    }
  }

  void OnAudioDataReady(const std::vector<uint8_t>& data) {
    /* For now we assume data comes in as 16bit per sample 16kHz PCM stereo */
    bool need_drop = false;
    int num_samples = data.size() / (2 /*bytes_per_sample*/ * 2 /*number of channels*/);

    // The G.722 codec accept only even number of samples for encoding
    if (num_samples % 2 != 0) {
      log::fatal("num_samples is not even: {}", num_samples);
    }

    // TODO: we should cache left/right and current state, instead of recomputing
    // it for each packet, 100 times a second.
    HearingDevice* left = nullptr;
    HearingDevice* right = nullptr;
    for (auto& device : hearingDevices.devices) {
      if (!device.accepting_audio) {
        continue;
      }

      if (device.isLeft()) {
        left = &device;
      } else {
        right = &device;
      }
    }

    if (left == nullptr && right == nullptr) {
      log::warn("No more (0/{}) devices ready", GetDeviceCount());
      DoDisconnectAudioStop();
      return;
    }

    std::vector<uint16_t> chan_left;
    std::vector<uint16_t> chan_right;
    if (left == nullptr || right == nullptr) {
      for (int i = 0; i < num_samples; i++) {
        const uint8_t* sample = data.data() + i * 4;

        int16_t left = (int16_t)((*(sample + 1) << 8) + *sample) >> 1;

        sample += 2;
        int16_t right = (int16_t)((*(sample + 1) << 8) + *sample) >> 1;

        uint16_t mono_data = (int16_t)(((uint32_t)left + (uint32_t)right) >> 1);
        chan_left.push_back(mono_data);
        chan_right.push_back(mono_data);
      }
    } else {
      for (int i = 0; i < num_samples; i++) {
        const uint8_t* sample = data.data() + i * 4;

        uint16_t left = (int16_t)((*(sample + 1) << 8) + *sample) >> 1;
        chan_left.push_back(left);

        sample += 2;
        uint16_t right = (int16_t)((*(sample + 1) << 8) + *sample) >> 1;
        chan_right.push_back(right);
      }
    }

    // Skipping packets completely messes up the resampler context.
    // The flush threshold is set to the number of credits specified for the
    // ASHA l2cap streaming channel. This will ensure it is only triggered in
    // case of critical failure.
    uint16_t l2cap_flush_threshold = 8;

    // TODO: monural, binarual check

    // divide encoded data into packets, add header, send.

    // TODO: make those buffers static and global to prevent constant
    // reallocations
    // TODO: this should basically fit the encoded data, tune the size later
    std::vector<uint8_t> encoded_data_left;
    auto time_point = std::chrono::steady_clock::now();
    if (left) {
      // TODO: instead of a magic number, we need to figure out the correct
      // buffer size
      encoded_data_left.resize(4000);
      int encoded_size = g722_encode(encoder_state_left, encoded_data_left.data(),
                                     (const int16_t*)chan_left.data(), chan_left.size());
      encoded_data_left.resize(encoded_size);

      uint16_t cid = GAP_ConnGetL2CAPCid(left->gap_handle);
      uint16_t packets_in_chans =
              stack::l2cap::get_interface().L2CA_FlushChannel(cid, L2CAP_FLUSH_CHANS_GET);
      if (packets_in_chans > l2cap_flush_threshold) {
        // Compare the two sides LE CoC credit value to confirm need to drop or
        // skip audio packet.
        if (NeedToDropPacket(left, right) && IsBelowDropFrequency(time_point)) {
          log::info("{} triggers dropping, {} packets in channel", left->address, packets_in_chans);
          need_drop = true;
          left->audio_stats.trigger_drop_count++;
        } else {
          log::info("{} skipping {} packets", left->address, packets_in_chans);
          left->audio_stats.packet_flush_count += packets_in_chans;
          left->audio_stats.frame_flush_count++;
          const uint16_t buffers_left =
                  stack::l2cap::get_interface().L2CA_FlushChannel(cid, L2CAP_FLUSH_CHANS_ALL);
          if (buffers_left) {
            log::warn("Unable to flush L2CAP ALL (left HA) channel peer:{} cid:{} buffers_left:{}",
                      left->address, cid, buffers_left);
          }
        }
        hearingDevices.StartRssiLog();
      }
      check_and_do_rssi_read(left);
    }

    std::vector<uint8_t> encoded_data_right;
    if (right) {
      // TODO: instead of a magic number, we need to figure out the correct
      // buffer size
      encoded_data_right.resize(4000);
      int encoded_size = g722_encode(encoder_state_right, encoded_data_right.data(),
                                     (const int16_t*)chan_right.data(), chan_right.size());
      encoded_data_right.resize(encoded_size);

      uint16_t cid = GAP_ConnGetL2CAPCid(right->gap_handle);
      uint16_t packets_in_chans =
              stack::l2cap::get_interface().L2CA_FlushChannel(cid, L2CAP_FLUSH_CHANS_GET);
      if (packets_in_chans > l2cap_flush_threshold) {
        // Compare the two sides LE CoC credit value to confirm need to drop or
        // skip audio packet.
        if (NeedToDropPacket(right, left) && IsBelowDropFrequency(time_point)) {
          log::info("{} triggers dropping, {} packets in channel", right->address,
                    packets_in_chans);
          need_drop = true;
          right->audio_stats.trigger_drop_count++;
        } else {
          log::info("{} skipping {} packets", right->address, packets_in_chans);
          right->audio_stats.packet_flush_count += packets_in_chans;
          right->audio_stats.frame_flush_count++;
          const uint16_t buffers_left =
                  stack::l2cap::get_interface().L2CA_FlushChannel(cid, L2CAP_FLUSH_CHANS_ALL);
          if (buffers_left) {
            log::warn("Unable to flush L2CAP ALL (right HA) channel peer:{} cid:{} buffers_left:{}",
                      right->address, cid, buffers_left);
          }
        }
        hearingDevices.StartRssiLog();
      }
      check_and_do_rssi_read(right);
    }

    size_t encoded_data_size = std::max(encoded_data_left.size(), encoded_data_right.size());

    uint16_t packet_size = CalcCompressedAudioPacketSize(codec_in_use, default_data_interval_ms);

    if (need_drop) {
      last_drop_time_point = time_point;
      if (left) {
        left->audio_stats.packet_drop_count++;
      }
      if (right) {
        right->audio_stats.packet_drop_count++;
      }
      return;
    }

    for (size_t i = 0; i < encoded_data_size; i += packet_size) {
      if (left) {
        left->audio_stats.packet_send_count++;
        SendAudio(encoded_data_left.data() + i, packet_size, left);
      }
      if (right) {
        right->audio_stats.packet_send_count++;
        SendAudio(encoded_data_right.data() + i, packet_size, right);
      }
      seq_counter++;
    }
    if (left) {
      left->audio_stats.frame_send_count++;
    }
    if (right) {
      right->audio_stats.frame_send_count++;
    }
  }

  void SendAudio(uint8_t* encoded_data, uint16_t packet_size, HearingDevice* hearingAid) {
    if (!hearingAid->playback_started || !hearingAid->command_acked) {
      log::warn("Playback stalled: bd_addr={} cmd send={} cmd acked={}", hearingAid->address,
                hearingAid->playback_started, hearingAid->command_acked);
      return;
    }

    BT_HDR* audio_packet = malloc_l2cap_buf(packet_size + 1);
    uint8_t* p = get_l2cap_sdu_start_ptr(audio_packet);
    *p = seq_counter;
    p++;
    memcpy(p, encoded_data, packet_size);

    log::verbose("bd_addr={} packet_size={}", hearingAid->address, packet_size);

    uint16_t result = GAP_ConnWriteData(hearingAid->gap_handle, audio_packet);

    if (result != BT_PASS) {
      log::error("Error sending data: 0x{:x}", result);
    }
  }

  void GapCallback(uint16_t gap_handle, uint16_t event, tGAP_CB_DATA* /*data*/) {
    HearingDevice* hearingDevice = hearingDevices.FindByGapHandle(gap_handle);
    if (!hearingDevice) {
      log::error("unknown device: gap_handle={} event=0x{:x}", gap_handle, event);
      return;
    }

    switch (event) {
      case GAP_EVT_CONN_OPENED: {
        RawAddress address = *GAP_ConnGetRemoteAddr(gap_handle);
        uint16_t tx_mtu = GAP_ConnGetRemMtuSize(gap_handle);

        init_credit = stack::l2cap::get_interface().L2CA_GetPeerLECocCredit(
                address, GAP_ConnGetL2CAPCid(gap_handle));

        log::info("GAP_EVT_CONN_OPENED: bd_addr={} tx_mtu={} init_credit={}", address, tx_mtu,
                  init_credit);

        HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
        if (!hearingDevice) {
          log::error("unknown device: bd_addr={}", address);
          return;
        }
        hearingDevice->gap_opened = true;
        if (hearingDevice->connection_update_status == COMPLETED) {
          OnDeviceReady(address);
        }
        break;
      }

      case GAP_EVT_CONN_CLOSED:
        log::info("GAP_EVT_CONN_CLOSED: bd_addr={} accepting_audio={}", hearingDevice->address,
                  hearingDevice->accepting_audio);

        if (!hearingDevice->accepting_audio) {
          /* Disconnect connection when data channel is not available */
          BTA_GATTC_Close(hearingDevice->conn_id);
        } else {
          /* Just clean data channel related parameter when data channel is
           * available */
          hearingDevice->gap_handle = GAP_INVALID_HANDLE;
          hearingDevice->accepting_audio = false;
          hearingDevice->playback_started = false;
          hearingDevice->command_acked = false;
          hearingDevice->gap_opened = false;
        }
        break;

      case GAP_EVT_CONN_DATA_AVAIL: {
        log::verbose("GAP_EVT_CONN_DATA_AVAIL: bd_addr={}", hearingDevice->address);

        // only data we receive back from hearing aids are some stats, not
        // really important, but useful now for debugging.
        uint32_t bytes_to_read = 0;
        GAP_GetRxQueueCnt(gap_handle, &bytes_to_read);
        std::vector<uint8_t> buffer(bytes_to_read);

        uint16_t bytes_read = 0;
        // TODO:GAP_ConnReadData should accept uint32_t for length!
        GAP_ConnReadData(gap_handle, buffer.data(), buffer.size(), &bytes_read);

        if (bytes_read < 4) {
          log::warn("Wrong data length");
          return;
        }

        uint8_t* p = buffer.data();

        log::verbose("stats from the hearing aid:");
        for (size_t i = 0; i + 4 <= buffer.size(); i += 4) {
          uint16_t event_counter, frame_index;
          STREAM_TO_UINT16(event_counter, p);
          STREAM_TO_UINT16(frame_index, p);
          log::verbose("event_counter={} frame_index: {}", event_counter, frame_index);
        }
        break;
      }

      case GAP_EVT_TX_EMPTY:
        log::info("GAP_EVT_TX_EMPTY: bd_addr={}", hearingDevice->address);
        break;

      case GAP_EVT_CONN_CONGESTED:
        log::info("GAP_EVT_CONN_CONGESTED: bd_addr={}", hearingDevice->address);

        // TODO: make it into function
        HearingAidAudioSource::Stop();
        // TODO: kill the encoder only if all hearing aids are down.
        // g722_encode_release(encoder_state);
        // encoder_state_left = nulllptr;
        // encoder_state_right = nulllptr;
        break;

      case GAP_EVT_CONN_UNCONGESTED:
        log::info("GAP_EVT_CONN_UNCONGESTED: bd_addr={}", hearingDevice->address);
        break;
    }
  }

  static void GapCallbackStatic(uint16_t gap_handle, uint16_t event, tGAP_CB_DATA* data) {
    if (instance) {
      instance->GapCallback(gap_handle, event, data);
    }
  }

  void DumpRssi(int fd, const HearingDevice& device) {
    const struct AudioStats* stats = &device.audio_stats;

    if (stats->rssi_history.size() <= 0) {
      dprintf(fd, "  No RSSI history for %s:\n",
              device.address.ToRedactedStringForLogging().c_str());
      return;
    }
    dprintf(fd, "  RSSI history for %s:\n", device.address.ToRedactedStringForLogging().c_str());

    dprintf(fd, "    Time of RSSI    0.0  0.1  0.2  0.3  0.4  0.5  0.6  0.7  0.8  0.9\n");
    for (auto& rssi_logs : stats->rssi_history) {
      if (rssi_logs.rssi.size() <= 0) {
        break;
      }

      char eventtime[20];
      char temptime[20];
      struct tm* tstamp = localtime(&rssi_logs.timestamp.tv_sec);
      if (!strftime(temptime, sizeof(temptime), "%H:%M:%S", tstamp)) {
        log::error("strftime fails. tm_sec={}, tm_min={}, tm_hour={}", tstamp->tm_sec,
                   tstamp->tm_min, tstamp->tm_hour);
        osi_strlcpy(temptime, "UNKNOWN TIME", sizeof(temptime));
      }
      snprintf(eventtime, sizeof(eventtime), "%s.%03ld", temptime,
               rssi_logs.timestamp.tv_nsec / 1000000);

      dprintf(fd, "    %s: ", eventtime);

      for (auto rssi_value : rssi_logs.rssi) {
        dprintf(fd, " %04d", rssi_value);
      }
      dprintf(fd, "\n");
    }
  }

  void Dump(int fd) {
    std::stringstream stream;
    for (const auto& device : hearingDevices.devices) {
      bool side = device.capabilities & CAPABILITY_SIDE;
      bool standalone = device.capabilities & CAPABILITY_BINAURAL;
      stream << "  " << device.address.ToString() << " " << (device.accepting_audio ? "" : "not ")
             << "connected" << "\n    " << (standalone ? "binaural" : "monaural") << " "
             << (side ? "right" : "left") << " " << loghex(device.hi_sync_id) << std::endl;
      stream << "    Trigger dropped counts                                 : "
             << device.audio_stats.trigger_drop_count
             << "\n    Packet dropped counts                                  : "
             << device.audio_stats.packet_drop_count
             << "\n    Packet counts (send/flush)                             : "
             << device.audio_stats.packet_send_count << " / "
             << device.audio_stats.packet_flush_count
             << "\n    Frame counts (sent/flush)                              : "
             << device.audio_stats.frame_send_count << " / " << device.audio_stats.frame_flush_count
             << std::endl;

      DumpRssi(fd, device);
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  void Disconnect(const RawAddress& address) {
    HearingDevice* hearingDevice = hearingDevices.FindByAddress(address);
    if (!hearingDevice) {
      log::error("unknown device: bd_addr={}", address);
      return;
    }

    bool connected = hearingDevice->accepting_audio;
    bool connecting_by_user = hearingDevice->connecting_actively;

    log::info("bd_addr={} playback_started={} accepting_audio={}", hearingDevice->address,
              hearingDevice->playback_started, hearingDevice->accepting_audio);

    if (hearingDevice->connecting_actively) {
      // cancel pending direct connect
      BTA_GATTC_CancelOpen(gatt_if, address, true);
    }

    // Removes all registrations for connection.
    BTA_GATTC_CancelOpen(0, address, false);

    // Inform the other side (if any) of this disconnection
    std::vector<uint8_t> inform_disconn_state(
            {CONTROL_POINT_OP_STATE_CHANGE, STATE_CHANGE_OTHER_SIDE_DISCONNECTED});
    send_state_change_to_other_side(hearingDevice, inform_disconn_state);

    DoDisconnectCleanUp(hearingDevice);

    if (!connected) {
      /* In case user wanted to connect, sent DISCONNECTED state */
      if (connecting_by_user) {
        callbacks->OnConnectionState(ConnectionState::DISCONNECTED, address);
      }
      /* Do remove device when the address is useless. */
      hearingDevices.Remove(address);
      return;
    }

    callbacks->OnConnectionState(ConnectionState::DISCONNECTED, address);
    /* Do remove device when the address is useless. */
    hearingDevices.Remove(address);
    for (const auto& device : hearingDevices.devices) {
      if (device.accepting_audio) {
        return;
      }
    }

    log::info("No more (0/{}) devices ready", GetDeviceCount());
    DoDisconnectAudioStop();
  }

  void OnGattDisconnected(tCONN_ID conn_id, tGATT_IF /*client_if*/, RawAddress remote_bda) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x} bd_addr={}", conn_id, remote_bda);
      return;
    }

    log::info("conn_id=0x{:x} bd_addr={}", conn_id, remote_bda);

    // Inform the other side (if any) of this disconnection
    std::vector<uint8_t> inform_disconn_state(
            {CONTROL_POINT_OP_STATE_CHANGE, STATE_CHANGE_OTHER_SIDE_DISCONNECTED});
    send_state_change_to_other_side(hearingDevice, inform_disconn_state);

    DoDisconnectCleanUp(hearingDevice);

    HearingDevice* other_connected_device_from_set =
            hearingDevices.FindOtherConnectedDeviceFromSet(*hearingDevice);

    if (other_connected_device_from_set != nullptr) {
      log::info(
              "Another device from the set is still connected, issuing a direct "
              "connection, other_device_bda={}",
              other_connected_device_from_set->address);
    }

    // If another device from the pair is still connected, do a direct
    // connection to scan more aggressively and connect as fast as possible
    hearingDevice->connecting_actively = other_connected_device_from_set != nullptr;

    auto connection_type = hearingDevice->connecting_actively ? BTM_BLE_DIRECT_CONNECTION
                                                              : BTM_BLE_BKG_CONNECT_ALLOW_LIST;

    hearingDevice->switch_to_background_connection_after_failure =
            connection_type == BTM_BLE_DIRECT_CONNECTION;

    // This is needed just for the first connection. After stack is restarted,
    // code that loads device will add them to acceptlist.
    BTA_GATTC_Open(gatt_if, hearingDevice->address, connection_type, false);

    callbacks->OnConnectionState(ConnectionState::DISCONNECTED, remote_bda);

    for (const auto& device : hearingDevices.devices) {
      if (device.accepting_audio) {
        return;
      }
    }

    log::info("No more (0/{}) devices ready", GetDeviceCount());
    DoDisconnectAudioStop();
  }

  void DoDisconnectCleanUp(HearingDevice* hearingDevice) {
    if (hearingDevice->connection_update_status != COMPLETED) {
      log::info("connection update not completed: status={}, bd_addr={}",
                hearingDevice->connection_update_status, hearingDevice->address);

      if (hearingDevice->connection_update_status == STARTED) {
        OnConnectionUpdateComplete(hearingDevice->conn_id, NULL);
      }
    }
    hearingDevice->connection_update_status = NONE;
    hearingDevice->gap_opened = false;

    if (hearingDevice->conn_id != INVALID_CONN_ID) {
      BtaGattQueue::Clean(hearingDevice->conn_id);
      BTA_GATTC_Close(hearingDevice->conn_id);
      hearingDevice->conn_id = INVALID_CONN_ID;
    }

    if (hearingDevice->gap_handle != GAP_INVALID_HANDLE) {
      GAP_ConnClose(hearingDevice->gap_handle);
      hearingDevice->gap_handle = GAP_INVALID_HANDLE;
    }

    hearingDevice->accepting_audio = false;
    log::info("bd_addr={} playback_started={}", hearingDevice->address,
              hearingDevice->playback_started);
    hearingDevice->playback_started = false;
    hearingDevice->command_acked = false;
  }

  void DoDisconnectAudioStop() {
    HearingAidAudioSource::Stop();
    audio_running = false;
    encoder_state_release();
    current_volume = VOLUME_UNKNOWN;
    ResetAsrc();
  }

  void SetVolume(int8_t volume) {
    log::debug("{}", volume);
    current_volume = volume;
    for (HearingDevice& device : hearingDevices.devices) {
      if (!device.accepting_audio) {
        continue;
      }

      std::vector<uint8_t> volume_value({static_cast<unsigned char>(volume)});
      BtaGattQueue::WriteCharacteristic(device.conn_id, device.volume_handle, volume_value,
                                        GATT_WRITE_NO_RSP, nullptr, nullptr);
    }
  }

  void CleanUp() {
    BTA_GATTC_AppDeregister(gatt_if);
    for (HearingDevice& device : hearingDevices.devices) {
      DoDisconnectCleanUp(&device);
    }

    hearingDevices.devices.clear();

    encoder_state_release();
  }

private:
  uint8_t gatt_if;
  uint8_t seq_counter;
  /* current volume gain for the hearing aids*/
  int8_t current_volume;
  bluetooth::hearing_aid::HearingAidCallbacks* callbacks;

  /* currently used codec */
  uint8_t codec_in_use;

  uint16_t default_data_interval_ms;

  uint16_t init_credit;

  HearingDevices hearingDevices;

  void find_server_changed_ccc_handle(tCONN_ID conn_id, const gatt::Service* service) {
    HearingDevice* hearingDevice = hearingDevices.FindByConnId(conn_id);
    if (!hearingDevice) {
      log::error("unknown device: conn_id=0x{:x}", conn_id);
      return;
    }

    for (const gatt::Characteristic& charac : service->characteristics) {
      if (charac.uuid == Uuid::From16Bit(GATT_UUID_GATT_SRV_CHGD)) {
        hearingDevice->service_changed_ccc_handle = find_ccc_handle(conn_id, charac.value_handle);
        if (!hearingDevice->service_changed_ccc_handle) {
          log::error("failed to find service changed CCC descriptor: bd_addr={}",
                     hearingDevice->address);
          continue;
        }
        log::info("bd_addr={} service_changed_ccc=0x{:x}", hearingDevice->address,
                  hearingDevice->service_changed_ccc_handle);
        break;
      }
    }
  }

  // Find the handle for the client characteristics configuration of a given
  // characteristics
  uint16_t find_ccc_handle(tCONN_ID conn_id, uint16_t char_handle) {
    const gatt::Characteristic* p_char = BTA_GATTC_GetCharacteristic(conn_id, char_handle);

    if (!p_char) {
      log::warn("No such characteristic: {}", char_handle);
      return 0;
    }

    for (const gatt::Descriptor& desc : p_char->descriptors) {
      if (desc.uuid == Uuid::From16Bit(GATT_UUID_CHAR_CLIENT_CONFIG)) {
        return desc.handle;
      }
    }

    return 0;
  }

  void send_state_change(HearingDevice* device, std::vector<uint8_t> payload) {
    if (device->conn_id != INVALID_CONN_ID) {
      if (device->service_changed_rcvd) {
        log::info("service discover is in progress, skip send State Change cmd.");
        return;
      }
      // Send the data packet
      log::info("Send State Change: bd_addr={} status=0x{:x}", device->address, payload[1]);
      BtaGattQueue::WriteCharacteristic(device->conn_id, device->audio_control_point_handle,
                                        payload, GATT_WRITE_NO_RSP, nullptr, nullptr);
    }
  }

  void send_state_change_to_other_side(HearingDevice* this_side_device,
                                       std::vector<uint8_t> payload) {
    for (auto& device : hearingDevices.devices) {
      if ((device.address == this_side_device->address) ||
          (device.hi_sync_id != this_side_device->hi_sync_id)) {
        continue;
      }
      send_state_change(&device, payload);
    }
  }

  void check_and_do_rssi_read(HearingDevice* device) {
    if (device->read_rssi_count > 0) {
      device->num_intervals_since_last_rssi_read++;
      if (device->num_intervals_since_last_rssi_read >= PERIOD_TO_READ_RSSI_IN_INTERVALS) {
        device->num_intervals_since_last_rssi_read = 0;
        log::debug("bd_addr={}", device->address);
        if (get_btm_client_interface().link_controller.BTM_ReadRSSI(
                    device->address, read_rssi_callback) != tBTM_STATUS::BTM_CMD_STARTED) {
          log::warn("Unable to read RSSI peer:{}", device->address);
        }
      }
    }
  }
};

static void read_rssi_callback(void* p_void) {
  tBTM_RSSI_RESULT* p_result = (tBTM_RSSI_RESULT*)p_void;

  if (!p_result) {
    return;
  }

  if ((instance) && (p_result->status == tBTM_STATUS::BTM_SUCCESS)) {
    instance->OnReadRssiComplete(p_result->rem_bda, p_result->rssi);
  }
}

static void hearingaid_gattc_callback(tBTA_GATTC_EVT event, tBTA_GATTC* p_data) {
  if (p_data == nullptr) {
    return;
  }

  switch (event) {
    case BTA_GATTC_DEREG_EVT:
      log::info("");
      break;

    case BTA_GATTC_OPEN_EVT: {
      if (!instance) {
        return;
      }
      tBTA_GATTC_OPEN& o = p_data->open;
      instance->OnGattConnected(o.status, o.conn_id, o.client_if, o.remote_bda, o.transport, o.mtu);
      break;
    }

    case BTA_GATTC_CLOSE_EVT: {
      if (!instance) {
        return;
      }
      tBTA_GATTC_CLOSE& c = p_data->close;
      instance->OnGattDisconnected(c.conn_id, c.client_if, c.remote_bda);
    } break;

    case BTA_GATTC_SEARCH_CMPL_EVT:
      if (!instance) {
        return;
      }
      instance->OnServiceSearchComplete(p_data->search_cmpl.conn_id, p_data->search_cmpl.status);
      break;

    case BTA_GATTC_NOTIF_EVT:
      if (!instance) {
        return;
      }
      if (!p_data->notify.is_notify || p_data->notify.len > GATT_MAX_ATTR_LEN) {
        log::error("rejected BTA_GATTC_NOTIF_EVT. is_notify={}, len={}", p_data->notify.is_notify,
                   p_data->notify.len);
        break;
      }
      instance->OnNotificationEvent(p_data->notify.conn_id, p_data->notify.handle,
                                    p_data->notify.len, p_data->notify.value);
      break;

    case BTA_GATTC_ENC_CMPL_CB_EVT:
      if (!instance) {
        return;
      }
      instance->OnEncryptionComplete(p_data->enc_cmpl.remote_bda,
                                     BTM_IsEncrypted(p_data->enc_cmpl.remote_bda, BT_TRANSPORT_LE));
      break;

    case BTA_GATTC_CONN_UPDATE_EVT:
      if (!instance) {
        return;
      }
      instance->OnConnectionUpdateComplete(p_data->conn_update.conn_id, p_data);
      break;

    case BTA_GATTC_SRVC_CHG_EVT:
      if (!instance) {
        return;
      }
      instance->OnServiceChangeEvent(p_data->service_changed.remote_bda);
      break;

    case BTA_GATTC_SRVC_DISC_DONE_EVT:
      if (!instance) {
        return;
      }
      instance->OnServiceDiscDoneEvent(p_data->service_discovery_done.remote_bda);
      break;
    case BTA_GATTC_PHY_UPDATE_EVT: {
      if (!instance) {
        return;
      }
      tBTA_GATTC_PHY_UPDATE& p = p_data->phy_update;
      instance->OnPhyUpdateEvent(p.conn_id, p.tx_phy, p.rx_phy, p.status);
      break;
    }

    default:
      break;
  }
}

static void encryption_callback(RawAddress address, tBT_TRANSPORT, void*, tBTM_STATUS status) {
  if (instance) {
    instance->OnEncryptionComplete(address, status == tBTM_STATUS::BTM_SUCCESS ? true : false);
  }
}

class HearingAidAudioReceiverImpl : public HearingAidAudioReceiver {
public:
  void OnAudioDataReady(const std::vector<uint8_t>& data) override {
    if (instance) {
      instance->OnAudioDataReadyResample(data);
    }
  }
  void OnAudioSuspend(const std::function<void()>& stop_audio_ticks) override {
    if (instance) {
      instance->OnAudioSuspend(stop_audio_ticks);
    }
  }
  void OnAudioResume(const std::function<void()>& start_audio_ticks) override {
    if (instance) {
      instance->OnAudioResume(start_audio_ticks);
    }
  }
};

HearingAidAudioReceiverImpl audioReceiverImpl;

}  // namespace

void HearingAid::Initialize(bluetooth::hearing_aid::HearingAidCallbacks* callbacks,
                            Closure initCb) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (instance) {
    log::error("Already initialized!");
    return;
  }

  audioReceiver = &audioReceiverImpl;
  instance = new HearingAidImpl(callbacks, initCb);
  HearingAidAudioSource::Initialize();
}

bool HearingAid::IsHearingAidRunning() { return instance; }

void HearingAid::Connect(const RawAddress& address) {
  if (!instance) {
    log::error("Hearing Aid instance is not available");
    return;
  }
  instance->Connect(address);
}

void HearingAid::Disconnect(const RawAddress& address) {
  if (!instance) {
    log::error("Hearing Aid instance is not available");
    return;
  }
  instance->Disconnect(address);
}

void HearingAid::AddToAcceptlist(const RawAddress& address) {
  if (!instance) {
    log::error("Hearing Aid instance is not available");
    return;
  }
  instance->AddToAcceptlist(address);
}

void HearingAid::SetVolume(int8_t volume) {
  if (!instance) {
    log::error("Hearing Aid instance is not available");
    return;
  }
  instance->SetVolume(volume);
}

void HearingAid::AddFromStorage(const HearingDevice& dev_info, bool is_acceptlisted) {
  if (!instance) {
    log::error("Not initialized yet");
  }

  instance->AddFromStorage(dev_info, is_acceptlisted);
}

int HearingAid::GetDeviceCount() {
  if (!instance) {
    log::info("Not initialized yet");
    return 0;
  }

  return instance->GetDeviceCount();
}

void HearingAid::CleanUp() {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  // Must stop audio source to make sure it doesn't call any of callbacks on our
  // soon to be  null instance
  HearingAidAudioSource::Stop();

  HearingAidImpl* ptr = instance;
  instance = nullptr;
  HearingAidAudioSource::CleanUp();

  ptr->CleanUp();

  delete ptr;
}

void HearingAid::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  dprintf(fd, "Hearing Aid Manager:\n");
  if (instance) {
    instance->Dump(fd);
  }
  HearingAidAudioSource::DebugDump(fd);
  dprintf(fd, "\n");
}
